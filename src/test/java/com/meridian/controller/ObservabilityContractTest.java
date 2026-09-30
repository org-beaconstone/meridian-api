package com.meridian.controller;

import com.meridian.observability.PaymentMetrics;
import com.meridian.resilience.CircuitBreakerSettings;
import com.meridian.resilience.CorridorCircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "meridian.webhook.secret=test-key-webhook-secret",
    "spring.datasource.url=jdbc:h2:mem:observability;DB_CLOSE_DELAY=-1",
    "spring.h2.console.enabled=false"
})
class ObservabilityContractTest {
    @Autowired private MockMvc mvc;
    @Autowired private CorridorCircuitBreaker breakers;
    @Autowired private PaymentMetrics metrics;
    @Autowired private CircuitBreakerSettings settings;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper json;

    @BeforeEach
    void resetSignals() {
        breakers.reset();
        metrics.reset();
    }

    @Test
    void thresholdsMatchTheEuropeanSlo() {
        assertEquals(0.05, settings.errorRateThreshold(), 0.0000001);
        assertEquals(60, settings.window().toSeconds());
        assertEquals(20, settings.minSamples());
    }

    @Test
    void metricsCarryCorridorAndProviderLabels() throws Exception {
        pay("metrics-uk", "uk-1", "success", null, "card").andExpect(status().isOk());
        String scrape = scrape();
        assertTrue(metricValue(scrape, "payment_submissions_total", Map.of("corridor", "UK", "provider", "adyen", "outcome", "success")) >= 1);
        assertEquals(0.0, metricValue(scrape, "circuit_breaker_state", Map.of("corridor", "UK", "provider", "adyen")), 0.001);
        assertEquals(0.0, metricValue(scrape, "circuit_breaker_state", Map.of("corridor", "EU", "provider", "worldpay")), 0.001);
        assertEquals(1.0, metricValue(scrape, "sca_step_up_success_rate", Map.of("corridor", "UK", "provider", "adyen")), 0.001);
        assertTrue(scrape.contains("payment_authorization_seconds_bucket"));
        assertTrue(scrape.contains("le=\"0.8\"") || scrape.contains("le=\"0.800\""));
        assertTrue(scrape.contains("payment_provider_timeouts_total"));
    }

    @Test
    void openEuropeanBreakerReturnsDegradedStatusWithoutFailover() throws Exception {
        String room = "eu-breaker";
        long balance = balance(room);
        int transactions = transactions(room);
        for (int i = 0; i < 20; i++) {
            pay(room, "eu-miss-" + i, "unavailable", "EU", "card")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
        }
        assertEquals(balance, balance(room));
        assertEquals(transactions, transactions(room));
        pay(room, "eu-degraded", "success", "EU", "card")
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.code").value("PROVIDER_DEGRADED"))
            .andExpect(jsonPath("$.provider").value("adyen"))
            .andExpect(jsonPath("$.corridor").value("EU"))
            .andExpect(jsonPath("$.eurOptionsEnabled").value(false))
            .andExpect(jsonPath("$.fallbackCorridor").value("UK"));
        pay(room, "eu-degraded", "success", "EU", "card")
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("PROVIDER_DEGRADED"));
        assertEquals(balance, balance(room));
        assertEquals(transactions, transactions(room));

        pay(room, "uk-still-up", "success", "UK", "card")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transaction.provider").value("adyen"))
            .andExpect(jsonPath("$.transaction.method").value("card"));
        pay(room, "eu-bank-still-up", "success", "EU", "bank")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transaction.provider").value("worldpay"))
            .andExpect(jsonPath("$.transaction.method").value("bank"));

        mvc.perform(get("/api/v1/corridors"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.simulation").value(true))
            .andExpect(jsonPath("$.ledgerCurrency").value("GBP"))
            .andExpect(jsonPath("$.eurOptionsEnabled").value(false))
            .andExpect(jsonPath("$.fallbackCorridor").value("UK"))
            .andExpect(jsonPath("$.corridors[2].id").value("EU"))
            .andExpect(jsonPath("$.corridors[2].providers[0].id").value("adyen"))
            .andExpect(jsonPath("$.corridors[2].providers[0].state").value("OPEN"))
            .andExpect(jsonPath("$.corridors[2].providers[1].id").value("worldpay"))
            .andExpect(jsonPath("$.corridors[2].providers[1].accepting").value(true))
            .andExpect(jsonPath("$.corridors[0].id").value("UK"))
            .andExpect(jsonPath("$.corridors[0].providers[0].state").value("CLOSED"));

        String scrape = scrape();
        assertEquals(1.0, metricValue(scrape, "circuit_breaker_state", Map.of("corridor", "EU", "provider", "adyen")), 0.001);
        assertTrue(metricValue(scrape, "payment_submissions_total", Map.of("corridor", "EU", "provider", "adyen", "outcome", "degraded")) >= 1);
        assertEquals(20.0, metricValue(scrape, "payment_provider_timeouts_total", Map.of("corridor", "EU", "provider", "adyen")), 0.001);
        assertEquals(0.0, metricValue(scrape, "sca_step_up_success_rate", Map.of("corridor", "EU", "provider", "adyen")), 0.001);

        breakers.reset();
        pay(room, "eu-degraded", "success", "EU", "card")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transaction.provider").value("adyen"))
            .andExpect(jsonPath("$.transaction.amount").value(100));
    }

    @Test
    void declinesDoNotOpenTheBreakerOrCountAsTimeouts() throws Exception {
        for (int i = 0; i < 20; i++) {
            pay("decline-storm", "decline-" + i, "declined", "US", "card")
                .andExpect(status().isUnprocessableEntity());
        }
        pay("decline-storm", "decline-ok", "success", "US", "card").andExpect(status().isOk());
        String scrape = scrape();
        assertEquals(0.0, metricValue(scrape, "circuit_breaker_state", Map.of("corridor", "US", "provider", "adyen")), 0.001);
        assertEquals(0.0, metricValue(scrape, "payment_provider_timeouts_total", Map.of("corridor", "US", "provider", "adyen")), 0.001);
        assertTrue(metricValue(scrape, "sca_step_up_success_rate", Map.of("corridor", "US", "provider", "adyen")) < 0.92);
    }

    @Test
    void webContractShapesStayIntact() throws Exception {
        mvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.service").value("meridian-api"))
            .andExpect(jsonPath("$.simulation").value(true));
        mvc.perform(get("/api/v1/catalog"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.providers.length()").value(2))
            .andExpect(jsonPath("$.providers[0].id").value("adyen"))
            .andExpect(jsonPath("$.providers[1].id").value("worldpay"));
        pay("unknown-corridor", "bad-corridor", "success", "EUR", "card").andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/corridors")).andExpect(jsonPath("$.eurOptionsEnabled").value(true));
    }

    private org.springframework.test.web.servlet.ResultActions pay(String room, String key, String scenario, String corridor, String method) throws Exception {
        String corridorField = corridor == null ? "" : ",\"corridor\":\"" + corridor + "\"";
        return mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", room)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":100,\"method\":\"" + method + "\",\"scenario\":\"" + scenario + "\"" + corridorField + "}"));
    }

    private String scrape() throws Exception {
        return mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private long balance(String room) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", room)).andExpect(status().isOk()).andReturn();
        return ((Number) json.readValue(result.getResponse().getContentAsString(), Map.class).get("balance")).longValue();
    }

    private int transactions(String room) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", room)).andExpect(status().isOk()).andReturn();
        return ((java.util.List<?>) json.readValue(result.getResponse().getContentAsString(), Map.class).get("transactions")).size();
    }

    private static double metricValue(String scrape, String name, Map<String, String> labels) {
        for (String line : scrape.split("\n")) {
            if (line.startsWith("#") || !line.startsWith(name + "{")) continue;
            boolean matched = labels.entrySet().stream().allMatch(label -> line.contains(label.getKey() + "=\"" + label.getValue() + "\""));
            if (!matched) continue;
            String value = line.substring(line.lastIndexOf('}') + 1).trim().split(" ")[0];
            return Double.parseDouble(value);
        }
        throw new AssertionError("Missing metric " + name + " " + labels + "\n" + scrape);
    }
}
