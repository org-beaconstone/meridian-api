package com.meridian.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payment.ReturnStateSigner;
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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "meridian.webhook.secret=test-key-webhook-secret",
    "meridian.return-state.secret=meridian-rehearsal-return-state",
    "spring.datasource.url=jdbc:h2:mem:contract-v2;DB_CLOSE_DELAY=-1",
    "spring.h2.console.enabled=false"
})
public class PaymentContractV2Test {
    private static final String SECRET = "test-key-webhook-secret";
    private static final String RETURN_SECRET = ReturnStateSigner.DEFAULT_SECRET;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    void catalogPublishesVersionExpiryCurrencyAndOpenDescriptors() throws Exception {
        MvcResult result = mvc.perform(get("/api/v2/payment-methods"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.catalogVersion").value("2026-09-18.1"))
            .andExpect(jsonPath("$.expiresAt").value("2026-09-19T00:00:00Z"))
            .andExpect(jsonPath("$.currency.code").value("GBP"))
            .andExpect(jsonPath("$.currency.exponent").value(2))
            .andExpect(jsonPath("$.currency.minorUnit").value("pence"))
            .andExpect(jsonPath("$.currency.amountEncoding").value("integer"))
            .andExpect(jsonPath("$.methods.length()").value(2))
            .andExpect(jsonPath("$.methods[0].id").value("card"))
            .andExpect(jsonPath("$.methods[0].displayName").value("Debit card"))
            .andExpect(jsonPath("$.methods[0].descriptor.binding").value("open"))
            .andExpect(jsonPath("$.methods[0].descriptor.corridors[0]").value("UK"))
            .andExpect(jsonPath("$.methods[0].descriptor.corridors[1]").value("US"))
            .andExpect(jsonPath("$.methods[0].descriptor.flows[0]").value("fixed-uk-card"))
            .andExpect(jsonPath("$.methods[0].descriptor.flows[1]").value("fixed-us-card"))
            .andExpect(jsonPath("$.methods[1].id").value("bank"))
            .andExpect(jsonPath("$.methods[1].descriptor.flows[0]").value("fixed-uk-bank"))
            .andExpect(jsonPath("$.methods[1].descriptor.flows[1]").value("fixed-us-bank"))
            .andReturn();
        String body = result.getResponse().getContentAsString();
        assert !body.contains("adyen") && !body.contains("worldpay") : "Catalog descriptors must stay provider-neutral";
    }

    @Test
    void ukCardIntentResolvesAdyenAndDebitsSharedLedger() throws Exception {
        MvcResult before = mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", "v2-uk-card"))
            .andExpect(status().isOk()).andReturn();
        long balance = ((Number) json.readValue(before.getResponse().getContentAsString(), Map.class).get("balance")).longValue();

        MvcResult created = mvc.perform(post("/api/v2/payment-intents")
            .header("X-Rehearsal-Session", "v2-uk-card")
            .header("Idempotency-Key", "uk-card-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"northline-studio\",\"amountMinor\":2599,\"method\":\"card\",\"corridor\":\"UK\",\"scenario\":\"success\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.code").value("PAYMENT_SUCCEEDED"))
            .andExpect(jsonPath("$.status").value("succeeded"))
            .andExpect(jsonPath("$.authoritative").value(true))
            .andExpect(jsonPath("$.amountMinor").value(2599))
            .andExpect(jsonPath("$.currency.amountEncoding").value("integer"))
            .andExpect(jsonPath("$.resolution.flow").value("fixed-uk-card"))
            .andExpect(jsonPath("$.resolution.provider").value("adyen"))
            .andExpect(jsonPath("$.action.type").value("complete"))
            .andExpect(jsonPath("$.action.payload.retryable").value(false))
            .andExpect(jsonPath("$.transaction.provider").value("adyen"))
            .andExpect(jsonPath("$.transaction.method").value("card"))
            .andExpect(jsonPath("$.state.balance").value(balance - 2599))
            .andReturn();
        Map<String, Object> intent = json.readValue(created.getResponse().getContentAsString(), Map.class);
        assertSigned(intent);

        mvc.perform(get("/api/v2/payment-intents/" + intent.get("id")).header("X-Rehearsal-Session", "v2-uk-card"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("succeeded"))
            .andExpect(jsonPath("$.state.balance").value(balance - 2599));

        mvc.perform(post("/api/v2/payment-intents")
            .header("X-Rehearsal-Session", "v2-uk-card")
            .header("Idempotency-Key", "uk-card-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"northline-studio\",\"amountMinor\":2599,\"method\":\"card\",\"corridor\":\"UK\",\"scenario\":\"success\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(intent.get("id")))
            .andExpect(jsonPath("$.state.balance").value(balance - 2599));
    }

    @Test
    void usBankIntentResolvesWorldpay() throws Exception {
        mvc.perform(post("/api/v2/payment-intents")
            .header("X-Rehearsal-Session", "v2-us-bank")
            .header("Idempotency-Key", "us-bank-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":1000,\"method\":\"bank\",\"corridor\":\"US\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currency.code").value("GBP"))
            .andExpect(jsonPath("$.resolution.flow").value("fixed-us-bank"))
            .andExpect(jsonPath("$.resolution.provider").value("worldpay"))
            .andExpect(jsonPath("$.transaction.provider").value("worldpay"))
            .andExpect(jsonPath("$.transaction.amount").value(1000));
    }

    @Test
    void legacyIntegerDecoderRejectsFloatStringAndUnknownFields() throws Exception {
        mvc.perform(post("/api/v2/payment-intents")
            .header("X-Rehearsal-Session", "v2-float")
            .header("Idempotency-Key", "float")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":25.99,\"method\":\"card\",\"corridor\":\"UK\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_JSON"));
        mvc.perform(post("/api/v2/payment-intents")
            .header("X-Rehearsal-Session", "v2-string")
            .header("Idempotency-Key", "string")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":\"5000\",\"method\":\"card\",\"corridor\":\"US\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_JSON"));
        mvc.perform(post("/api/v2/payment-intents")
            .header("X-Rehearsal-Session", "v2-unknown")
            .header("Idempotency-Key", "unknown")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":5000,\"method\":\"card\",\"corridor\":\"UK\",\"provider\":\"adyen\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void corridorAndPayloadMismatchesStayOnTheFixedFlows() throws Exception {
        mvc.perform(post("/api/v2/payment-intents")
            .header("X-Rehearsal-Session", "v2-eu")
            .header("Idempotency-Key", "eu")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":500,\"method\":\"card\",\"corridor\":\"EU\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v2/payment-intents")
            .header("X-Rehearsal-Session", "v2-mismatch")
            .header("Idempotency-Key", "same")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":500,\"method\":\"card\",\"corridor\":\"UK\"}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v2/payment-intents")
            .header("X-Rehearsal-Session", "v2-mismatch")
            .header("Idempotency-Key", "same")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":500,\"method\":\"card\",\"corridor\":\"US\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void pendingWebhookThenGetIsAuthoritative() throws Exception {
        MvcResult created = mvc.perform(post("/api/v2/payment-intents")
            .header("X-Rehearsal-Session", "v2-pending")
            .header("Idempotency-Key", "pending-us")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":4000,\"method\":\"bank\",\"corridor\":\"US\",\"scenario\":\"pending\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("pending_confirmation"))
            .andExpect(jsonPath("$.action.type").value("await_confirmation"))
            .andExpect(jsonPath("$.action.payload.retryable").value(false))
            .andExpect(jsonPath("$.resolution.provider").value("worldpay"))
            .andReturn();
        Map<String, Object> intent = json.readValue(created.getResponse().getContentAsString(), Map.class);
        assertSigned(intent);
        String id = (String) intent.get("id");
        long reservedBalance = ((Number) ((Map<?, ?>) intent.get("state")).get("balance")).longValue();

        String wrong = "{\"sessionId\":\"v2-pending\",\"eventId\":\"evt-v2\",\"paymentId\":\"" + id + "\",\"status\":\"completed\"}";
        long ts = Instant.now().getEpochSecond();
        mvc.perform(post("/api/v1/webhooks/adyen")
            .header("X-Webhook-Timestamp", String.valueOf(ts))
            .header("X-Meridian-Signature", hmac(SECRET, ts + "." + wrong))
            .contentType(MediaType.APPLICATION_JSON)
            .content(wrong))
            .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/webhooks/worldpay")
            .header("X-Webhook-Timestamp", String.valueOf(ts))
            .header("X-Meridian-Signature", hmac(SECRET, ts + "." + wrong))
            .contentType(MediaType.APPLICATION_JSON)
            .content(wrong))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.duplicate").value(false));

        MvcResult after = mvc.perform(get("/api/v2/payment-intents/" + id).header("X-Rehearsal-Session", "v2-pending"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("succeeded"))
            .andExpect(jsonPath("$.authoritative").value(true))
            .andExpect(jsonPath("$.action.type").value("complete"))
            .andExpect(jsonPath("$.state.balance").value(reservedBalance - 4000))
            .andExpect(jsonPath("$.transaction.provider").value("worldpay"))
            .andReturn();
        assertSigned(json.readValue(after.getResponse().getContentAsString(), Map.class));

        mvc.perform(get("/api/v2/payment-intents/" + id).header("X-Rehearsal-Session", "other-room"))
            .andExpect(status().isNotFound());
    }

    @Test
    void declinedAndUnavailableActionsMatchTheContract() throws Exception {
        mvc.perform(post("/api/v2/payment-intents")
            .header("X-Rehearsal-Session", "v2-declined")
            .header("Idempotency-Key", "declined")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":700,\"method\":\"card\",\"corridor\":\"UK\",\"scenario\":\"declined\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.status").value("declined"))
            .andExpect(jsonPath("$.action.type").value("terminal"))
            .andExpect(jsonPath("$.action.payload.final").value(false));

        mvc.perform(post("/api/v2/payment-intents")
            .header("X-Rehearsal-Session", "v2-retry")
            .header("Idempotency-Key", "retry")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":700,\"method\":\"bank\",\"corridor\":\"US\",\"scenario\":\"unavailable\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.action.type").value("retry"))
            .andExpect(jsonPath("$.action.payload.sameIdempotencyKey").value(true));

        mvc.perform(post("/api/v2/payment-intents")
            .header("X-Rehearsal-Session", "v2-retry")
            .header("Idempotency-Key", "retry")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":700,\"method\":\"bank\",\"corridor\":\"US\",\"scenario\":\"success\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("succeeded"))
            .andExpect(jsonPath("$.resolution.provider").value("worldpay"));
    }

    @Test
    void v1PaymentRemainsReadableAsUkIntent() throws Exception {
        MvcResult paid = mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", "v2-from-v1")
            .header("Idempotency-Key", "legacy")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":1500,\"method\":\"bank\",\"scenario\":\"success\"}"))
            .andExpect(status().isOk())
            .andReturn();
        String id = (String) ((Map<?, ?>) json.readValue(paid.getResponse().getContentAsString(), Map.class).get("transaction")).get("id");
        mvc.perform(get("/api/v2/payment-intents/" + id).header("X-Rehearsal-Session", "v2-from-v1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("succeeded"))
            .andExpect(jsonPath("$.corridor").value("UK"))
            .andExpect(jsonPath("$.method").value("bank"))
            .andExpect(jsonPath("$.resolution.flow").value("fixed-uk-bank"))
            .andExpect(jsonPath("$.resolution.provider").value("worldpay"))
            .andExpect(jsonPath("$.amountMinor").value(1500));
    }

    @Test
    void reconciliationDeclineIsFinal() throws Exception {
        MvcResult created = mvc.perform(post("/api/v2/payment-intents")
            .header("X-Rehearsal-Session", "v2-final")
            .header("Idempotency-Key", "final")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":900,\"method\":\"card\",\"corridor\":\"UK\",\"scenario\":\"pending\"}"))
            .andExpect(status().isAccepted()).andReturn();
        String id = (String) json.readValue(created.getResponse().getContentAsString(), Map.class).get("id");
        String raw = "{\"sessionId\":\"v2-final\",\"eventId\":\"evt-final\",\"paymentId\":\"" + id + "\",\"status\":\"declined\"}";
        long ts = Instant.now().getEpochSecond();
        mvc.perform(post("/api/v1/webhooks/adyen")
            .header("X-Webhook-Timestamp", String.valueOf(ts))
            .header("X-Meridian-Signature", hmac(SECRET, ts + "." + raw))
            .contentType(MediaType.APPLICATION_JSON).content(raw))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v2/payment-intents")
            .header("X-Rehearsal-Session", "v2-final")
            .header("Idempotency-Key", "final")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":900,\"method\":\"card\",\"corridor\":\"UK\",\"scenario\":\"success\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.status").value("declined"))
            .andExpect(jsonPath("$.action.payload.final").value(true));
        mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", "v2-final"))
            .andExpect(jsonPath("$.balance").value(1248050));
    }

    @Test
    void concurrentIntentsReserveOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger success = new AtomicInteger();
        try {
            for (int i = 0; i < 2; i++) {
                int idx = i;
                pool.submit(() -> {
                    try {
                        mvc.perform(post("/api/v2/payment-intents")
                            .header("X-Rehearsal-Session", "v2-concurrent")
                            .header("Idempotency-Key", "conc-" + idx)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":800000,\"method\":\"card\",\"corridor\":\"UK\"}"))
                            .andExpect(status().isOk());
                        success.incrementAndGet();
                    } catch (Exception ignored) {}
                });
            }
            pool.shutdown();
            pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            if (!pool.isTerminated()) pool.shutdownNow();
        }
        assert success.get() == 1 : "Expected one of the concurrent intents to debit";
    }

    @SuppressWarnings("unchecked")
    private void assertSigned(Map<String, Object> intent) throws Exception {
        Map<String, Object> state = (Map<String, Object>) intent.get("returnState");
        String canonical = ReturnStateSigner.canonical(
            (String) state.get("intentId"),
            (String) state.get("status"),
            ((Number) state.get("amountMinor")).longValue(),
            (String) state.get("issuedAt"));
        assert state.get("signature").equals(hmac(RETURN_SECRET, canonical)) : "Return state signature mismatch";
        assert intent.get("id").equals(state.get("intentId"));
        assert intent.get("status").equals(state.get("status"));
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
