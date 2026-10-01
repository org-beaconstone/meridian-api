package com.meridian.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "meridian.webhook.secret=test-key-webhook-secret",
    "spring.datasource.url=jdbc:h2:mem:v2-contract;DB_CLOSE_DELAY=-1",
    "spring.h2.console.enabled=false"
})
class V2ContractTest {
    private static final String SECRET = "test-key-webhook-secret";
    private static final String VERSION = "2026.09.18-uk-us-1";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    void catalogPublishesVersionExpiryCurrencyAndOpenDescriptors() throws Exception {
        MvcResult result = mvc.perform(get("/api/v2/payment-methods"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.catalogVersion").value(VERSION))
            .andExpect(jsonPath("$.currency.code").value("GBP"))
            .andExpect(jsonPath("$.currency.minorUnitExponent").value(2))
            .andExpect(jsonPath("$.currency.amountEncoding").value("integer-minor"))
            .andExpect(jsonPath("$.currency.decoder").value("legacy-integer"))
            .andExpect(jsonPath("$.methods[0].descriptor.open").value(true))
            .andExpect(jsonPath("$.methods[0].descriptor.providerResolvedBy").value("server"))
            .andExpect(jsonPath("$.methods[0].id").value("card"))
            .andExpect(jsonPath("$.methods[1].id").value("bank"))
            .andExpect(jsonPath("$.corridors[0].flow").value("fixed"))
            .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        Instant issued = Instant.parse(body.get("issuedAt").asText());
        Instant expires = Instant.parse(body.get("expiresAt").asText());
        assertEquals(15 * 60, expires.getEpochSecond() - issued.getEpochSecond());
        String raw = result.getResponse().getContentAsString();
        assertFalse(raw.contains("adyen"));
        assertFalse(raw.contains("worldpay"));

        mvc.perform(get("/api/v2/payment-methods").param("corridor", "US"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.corridors.length()").value(1))
            .andExpect(jsonPath("$.corridors[0].code").value("US"));
        mvc.perform(get("/api/v2/payment-methods").param("corridor", "EU"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void v1CatalogAndIntegerDecoderStayInPlace() throws Exception {
        mvc.perform(get("/api/v1/catalog"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.providers[0].id").value("adyen"))
            .andExpect(jsonPath("$.providers[0].methods[0]").value("card"))
            .andExpect(jsonPath("$.providers[1].id").value("worldpay"))
            .andExpect(jsonPath("$.providers[1].methods[0]").value("bank"));
        mvc.perform(post("/api/v1/payments")
                .header("X-Rehearsal-Session", "v1-still-works")
                .header("Idempotency-Key", "v1-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":2599,\"method\":\"card\",\"scenario\":\"success\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transaction.amount").value(2599))
            .andExpect(jsonPath("$.transaction.provider").value("adyen"));
        mvc.perform(post("/api/v1/payments")
                .header("X-Rehearsal-Session", "v1-frac")
                .header("Idempotency-Key", "v1-frac")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":25.99,\"method\":\"card\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_JSON"));
    }

    @Test
    void fixedUkCardAndUsBankResolveOnTheLedger() throws Exception {
        String ukId = create("uk-card", "uk-card-key", "UK", "card", 2599, "success");
        assertLedger("uk-card", ukId, "adyen", "card", 2599, 1248050 - 2599);
        String usId = create("us-bank", "us-bank-key", "US", "bank", 1000, "success");
        assertLedger("us-bank", usId, "worldpay", "bank", 1000, 1248050 - 1000);
    }

    @Test
    void createRejectsProviderSelectionStaleCatalogAndLooseAmounts() throws Exception {
        mvc.perform(post("/api/v2/payment-intents")
                .header("X-Rehearsal-Session", "reject-provider")
                .header("Idempotency-Key", "reject-provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content(intent("UK", "card", 1000, "success", "{\"provider\":\"adyen\",\"methodId\":\"card\"}")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Provider is resolved by the server"));
        mvc.perform(post("/api/v2/payment-intents")
                .header("X-Rehearsal-Session", "stale-catalog")
                .header("Idempotency-Key", "stale-catalog")
                .contentType(MediaType.APPLICATION_JSON)
                .content(intent("UK", "card", 1000, "success", "null").replace(VERSION, "stale")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CATALOG_STALE"));
        mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", "stale-catalog"))
            .andExpect(jsonPath("$.balance").value(1248050));
        mvc.perform(post("/api/v2/payment-intents")
                .header("X-Rehearsal-Session", "frac-v2")
                .header("Idempotency-Key", "frac-v2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":25.99,\"methodId\":\"card\",\"catalogVersion\":\"" + VERSION + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_JSON"));
        mvc.perform(post("/api/v2/payment-intents")
                .header("X-Rehearsal-Session", "str-v2")
                .header("Idempotency-Key", "str-v2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":\"2599\",\"methodId\":\"card\",\"catalogVersion\":\"" + VERSION + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_JSON"));
    }

    @Test
    void openDescriptorEchoAndAuthoritativeLifecycle() throws Exception {
        String body = intent("UK", "card", 5000, "pending", "{\"methodId\":\"card\",\"futureFlag\":true}");
        MvcResult created = mvc.perform(post("/api/v2/payment-intents")
                .header("X-Rehearsal-Session", "lifecycle")
                .header("Idempotency-Key", "lifecycle-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("requires_action"))
            .andExpect(jsonPath("$.authoritative").value(true))
            .andExpect(jsonPath("$.action.type").value("await_webhook"))
            .andExpect(jsonPath("$.action.payload.instruction").exists())
            .andExpect(jsonPath("$.receipt").value(nullValue()))
            .andExpect(jsonPath("$.returnState.verifiable").value(true))
            .andExpect(jsonPath("$.returnState.algorithm").value("HMAC-SHA256"))
            .andReturn();
        JsonNode intent = json.readTree(created.getResponse().getContentAsString());
        String id = intent.get("id").asText();
        assertSignature(intent.get("returnState"));
        mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", "lifecycle"))
            .andExpect(jsonPath("$.balance").value(1248050));

        MvcResult replay = mvc.perform(post("/api/v2/payment-intents")
                .header("X-Rehearsal-Session", "lifecycle")
                .header("Idempotency-Key", "lifecycle-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.id").value(id))
            .andReturn();
        assertEquals(id, json.readTree(replay.getResponse().getContentAsString()).get("id").asText());

        String raw = "{\"sessionId\":\"lifecycle\",\"eventId\":\"evt-v2\",\"paymentId\":\"" + id + "\",\"status\":\"completed\"}";
        long ts = Instant.now().getEpochSecond();
        mvc.perform(post("/api/v1/webhooks/adyen")
                .header("X-Webhook-Timestamp", String.valueOf(ts))
                .header("X-Meridian-Signature", hmac(SECRET, ts + "." + raw))
                .contentType(MediaType.APPLICATION_JSON)
                .content(raw))
            .andExpect(status().isOk());

        MvcResult current = mvc.perform(get("/api/v2/payment-intents/" + id).header("X-Rehearsal-Session", "lifecycle"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("succeeded"))
            .andExpect(jsonPath("$.authoritative").value(true))
            .andExpect(jsonPath("$.action").value(nullValue()))
            .andExpect(jsonPath("$.receipt.amountMinor").value(5000))
            .andExpect(jsonPath("$.receipt.method").value("card"))
            .andExpect(jsonPath("$.code").value(nullValue()))
            .andReturn();
        JsonNode done = json.readTree(current.getResponse().getContentAsString());
        assertSignature(done.get("returnState"));
        assertEquals("succeeded", done.get("returnState").get("payload").get("status").asText());
        assertFalse(done.get("receipt").has("provider"));
        mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", "lifecycle"))
            .andExpect(jsonPath("$.balance").value(1248050 - 5000));
        mvc.perform(get("/api/v2/payment-intents/" + id).header("X-Rehearsal-Session", "other-room"))
            .andExpect(status().isNotFound());
    }

    @Test
    void declinedUnavailableAndCorridorConflict() throws Exception {
        mvc.perform(post("/api/v2/payment-intents")
                .header("X-Rehearsal-Session", "declined-v2")
                .header("Idempotency-Key", "declined-v2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(intent("US", "card", 2000, "declined", "null")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.status").value("declined"))
            .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"))
            .andExpect(jsonPath("$.action").value(nullValue()));
        mvc.perform(post("/api/v2/payment-intents")
                .header("X-Rehearsal-Session", "missing-version")
                .header("Idempotency-Key", "missing-version")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":1000,\"methodId\":\"card\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("catalogVersion is required"));
        mvc.perform(post("/api/v2/payment-intents")
                .header("X-Rehearsal-Session", "unavailable-v2")
                .header("Idempotency-Key", "unavailable-v2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(intent("UK", "bank", 2000, "unavailable", "null")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("unavailable"))
            .andExpect(jsonPath("$.action.type").value("retry_same_key"))
            .andExpect(jsonPath("$.action.payload.sameKeyRequired").value(true));
        create("corridor-lock", "corridor-lock", "US", "bank", 1500, "success");
        mvc.perform(post("/api/v2/payment-intents")
                .header("X-Rehearsal-Session", "corridor-lock")
                .header("Idempotency-Key", "corridor-lock")
                .contentType(MediaType.APPLICATION_JSON)
                .content(intent("UK", "bank", 1500, "success", "null")))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", "corridor-lock"))
            .andExpect(jsonPath("$.balance").value(1248050 - 1500));
    }

    private String create(String room, String key, String corridor, String method, int amount, String scenario) throws Exception {
        MvcResult result = mvc.perform(post("/api/v2/payment-intents")
                .header("X-Rehearsal-Session", room)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(intent(corridor, method, amount, scenario, "null")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("succeeded"))
            .andExpect(jsonPath("$.corridor").value(corridor))
            .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void assertLedger(String room, String id, String provider, String method, int amount, int balance) throws Exception {
        MvcResult state = mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", room))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode body = json.readTree(state.getResponse().getContentAsString());
        assertEquals(balance, body.get("balance").asInt());
        JsonNode match = null;
        for (JsonNode txn : body.get("transactions")) {
            if (id.equals(txn.get("id").asText())) match = txn;
        }
        assertTrue(match != null);
        assertEquals(provider, match.get("provider").asText());
        assertEquals(method, match.get("method").asText());
        assertEquals(amount, match.get("amount").asInt());
    }

    private String intent(String corridor, String method, int amount, String scenario, String descriptor) {
        return "{\"recipientId\":\"birch-bloom\",\"amountMinor\":" + amount
            + ",\"methodId\":\"" + method + "\",\"corridor\":\"" + corridor
            + "\",\"scenario\":\"" + scenario + "\",\"catalogVersion\":\"" + VERSION
            + "\",\"descriptor\":" + descriptor + "}";
    }

    private void assertSignature(JsonNode returnState) {
        String canonical = returnState.get("canonical").asText();
        assertEquals(hmac(SECRET, canonical), returnState.get("signature").asText());
        assertTrue(canonical.contains("\"status\""));
    }

    private String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
