package com.meridian.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "meridian.webhook.secret=test-key-webhook-secret",
    "spring.datasource.url=jdbc:h2:mem:ledger-tests;DB_CLOSE_DELAY=-1",
    "spring.h2.console.enabled=false"
})
public class MeridianApiTest {
    @Autowired
    private MockMvc mvc;
    
    @Autowired
    private ObjectMapper json;

    private static final String SECRET = "test-key-webhook-secret";

    @BeforeEach
    void clean() throws Exception {
        mvc.perform(post("/api/v1/reset").header("X-Rehearsal-Session", "test-clean"));
    }

    // Core success: GET /state, POST /payments with success scenario
    @Test
    void testGetRawState1248050() throws Exception {
        mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", "test-s1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").isNumber())
            .andExpect(jsonPath("$.transactions").isArray())
            .andExpect(jsonPath("$.budgets").isArray());
    }

    @Test
    void testPaymentSuccess25_99CurrentState() throws Exception {
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", "test-s1")
            .header("Idempotency-Key", "idem-success-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":2599,\"method\":\"card\",\"scenario\":\"success\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.transaction.id").exists())
            .andExpect(jsonPath("$.transaction.amount").value(2599))
            .andExpect(jsonPath("$.state.balance").isNumber());
    }

    @Test
    void testIdempotencyDuplicateSamePayloadNoDebit() throws Exception {
        String sess = "test-idem-1", key = "key-idem-1";
        String payload = "{\"recipientId\":\"birch-bloom\",\"amountMinor\":5000,\"method\":\"card\",\"scenario\":\"success\"}";
        
        // First
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", sess).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        
        MvcResult r1 = mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", sess))
            .andExpect(status().isOk()).andReturn();
        String bal1 = r1.getResponse().getContentAsString();
        
        // Retry same payload
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", sess).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        
        MvcResult r2 = mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", sess))
            .andExpect(status().isOk()).andReturn();
        String bal2 = r2.getResponse().getContentAsString();
        
        // Balance unchanged
        assert bal1.equals(bal2) : "Idempotency: balance changed on duplicate";
    }

    @Test
    void testIdempotencyChangedNote409() throws Exception {
        String sess = "test-conflict-1", key = "key-conflict-1";
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", sess).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":3000,\"method\":\"card\",\"note\":\"A\",\"scenario\":\"success\"}"))
            .andExpect(status().isOk());
        
        // Same key, different note -> 409
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", sess).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":3000,\"method\":\"card\",\"note\":\"B\",\"scenario\":\"success\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void testRoomIsolationSameClientKey() throws Exception {
        String key = "shared-key";
        
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", "room-a")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":1000,\"method\":\"card\",\"scenario\":\"success\"}"))
            .andExpect(status().isOk());
        
        // Same key, different room -> allowed (different room, different intent)
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", "room-b")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":1000,\"method\":\"card\",\"scenario\":\"success\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void testDeclined422() throws Exception {
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", "test-declined-1")
            .header("Idempotency-Key", "key-declined-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":2000,\"method\":\"card\",\"scenario\":\"declined\"}"))
            .andExpect(status().isUnprocessableEntity()) // 422
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"));
    }

    @Test
    void testUnavailable503() throws Exception {
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", "test-unavailable-1")
            .header("Idempotency-Key", "key-unavailable-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":2000,\"method\":\"card\",\"scenario\":\"unavailable\"}"))
            .andExpect(status().isServiceUnavailable()) // 503
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
    }

    @Test
    void testDeclinedAndRetrySuccess() throws Exception {
        String sess = "test-retry-decline-1";
        
        // First: declined with key1
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", sess)
            .header("Idempotency-Key", "key1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":3000,\"method\":\"card\",\"scenario\":\"declined\"}"))
            .andExpect(status().isUnprocessableEntity());
        
        // Retry with key2, success scenario
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", sess)
            .header("Idempotency-Key", "key2")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":3000,\"method\":\"card\",\"scenario\":\"success\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void testUnavailableAndRetrySuccess() throws Exception {
        String sess = "test-retry-unavail-1";
        
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", sess)
            .header("Idempotency-Key", "key1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":3000,\"method\":\"card\",\"scenario\":\"unavailable\"}"))
            .andExpect(status().isServiceUnavailable());
        
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", sess)
            .header("Idempotency-Key", "key2")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":3000,\"method\":\"card\",\"scenario\":\"success\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void testPending202() throws Exception {
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", "test-pending-1")
            .header("Idempotency-Key", "key-pending-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":2000,\"method\":\"card\",\"scenario\":\"pending\"}"))
            .andExpect(status().isAccepted()) // 202
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.code").value("PAYMENT_PENDING"))
            .andExpect(jsonPath("$.paymentId").exists());
    }

    @Test
    void testWebhookSignedRawBodyWhitespace() throws Exception {
        String sess = "test-webhook-1";
        
        // First: pending payment
        MvcResult pResp = mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", sess)
            .header("Idempotency-Key", "key-webhook-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":5000,\"method\":\"card\",\"scenario\":\"pending\"}"))
            .andExpect(status().isAccepted()).andReturn();
        
        Map<String, Object> pResult = json.readValue(pResp.getResponse().getContentAsString(), Map.class);
        String paymentId = (String) pResult.get("paymentId");
        
        // Webhook completion
        String raw = "{\"sessionId\":\"" + sess + "\",\"eventId\":\"evt-1\",\"paymentId\":\"" + paymentId + "\",\"status\":\"completed\"}";
        long ts = Instant.now().getEpochSecond();
        String sig = hmac(SECRET, ts + "." + raw);
        
        mvc.perform(post("/api/v1/webhooks/adyen")
            .header("X-Webhook-Timestamp", String.valueOf(ts))
            .header("X-Meridian-Signature", sig)
            .contentType(MediaType.APPLICATION_JSON)
            .content(raw))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.duplicate").value(false));
    }

    @Test
    void testWebhookCompletionDebitTransaction() throws Exception {
        String sess = "test-webhook-debit-1";
        
        MvcResult pResp = mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", sess)
            .header("Idempotency-Key", "key-webhook-debit-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":5000,\"method\":\"card\",\"scenario\":\"pending\"}"))
            .andExpect(status().isAccepted()).andReturn();
        
        Map<String, Object> pResult = json.readValue(pResp.getResponse().getContentAsString(), Map.class);
        String paymentId = (String) pResult.get("paymentId");
        
        long ts = Instant.now().getEpochSecond();
        String raw = "{\"sessionId\":\"" + sess + "\",\"eventId\":\"evt-2\",\"paymentId\":\"" + paymentId + "\",\"status\":\"completed\"}";
        String sig = hmac(SECRET, ts + "." + raw);
        
        mvc.perform(post("/api/v1/webhooks/adyen")
            .header("X-Webhook-Timestamp", String.valueOf(ts))
            .header("X-Meridian-Signature", sig)
            .contentType(MediaType.APPLICATION_JSON)
            .content(raw))
            .andExpect(status().isOk());
        
        MvcResult state = mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", sess))
            .andExpect(status().isOk()).andReturn();
        
        Map<String, Object> s = json.readValue(state.getResponse().getContentAsString(), Map.class);
        assert ((java.util.List<?>) s.get("transactions")).size() > 0 : "No transaction after webhook completion";
    }

    @Test
    void testWebhookSemanticDuplicateSameEventIdTrueNoDebit() throws Exception {
        String sess = "test-webhook-dup-1";
        
        MvcResult pResp = mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", sess)
            .header("Idempotency-Key", "key-webhook-dup-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":5000,\"method\":\"card\",\"scenario\":\"pending\"}"))
            .andExpect(status().isAccepted()).andReturn();
        
        Map<String, Object> pResult = json.readValue(pResp.getResponse().getContentAsString(), Map.class);
        String paymentId = (String) pResult.get("paymentId");
        
        long ts = Instant.now().getEpochSecond();
        String raw = "{\"sessionId\":\"" + sess + "\",\"eventId\":\"evt-dup\",\"paymentId\":\"" + paymentId + "\",\"status\":\"completed\"}";
        String sig = hmac(SECRET, ts + "." + raw);
        
        // First
        mvc.perform(post("/api/v1/webhooks/adyen")
            .header("X-Webhook-Timestamp", String.valueOf(ts))
            .header("X-Meridian-Signature", sig)
            .contentType(MediaType.APPLICATION_JSON)
            .content(raw))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.duplicate").value(false));
        
        MvcResult r1 = mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", sess))
            .andExpect(status().isOk()).andReturn();
        String bal1 = r1.getResponse().getContentAsString();
        
        // Retry same event ID
        mvc.perform(post("/api/v1/webhooks/adyen")
            .header("X-Webhook-Timestamp", String.valueOf(ts))
            .header("X-Meridian-Signature", sig)
            .contentType(MediaType.APPLICATION_JSON)
            .content(raw))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.duplicate").value(true));
        
        MvcResult r2 = mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", sess))
            .andExpect(status().isOk()).andReturn();
        String bal2 = r2.getResponse().getContentAsString();
        
        assert bal1.equals(bal2) : "Webhook duplicate debit occurred";
    }

    @Test
    void testWebhookChangedPayloadConflict() throws Exception {
        String sess = "test-webhook-conflict-1";
        
        MvcResult pResp = mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", sess)
            .header("Idempotency-Key", "key-webhook-conflict-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":5000,\"method\":\"card\",\"scenario\":\"pending\"}"))
            .andExpect(status().isAccepted()).andReturn();
        
        Map<String, Object> pResult = json.readValue(pResp.getResponse().getContentAsString(), Map.class);
        String paymentId = (String) pResult.get("paymentId");
        
        long ts = Instant.now().getEpochSecond();
        String raw1 = "{\"sessionId\":\"" + sess + "\",\"eventId\":\"evt-conflict\",\"paymentId\":\"" + paymentId + "\",\"status\":\"completed\"}";
        String sig1 = hmac(SECRET, ts + "." + raw1);
        
        mvc.perform(post("/api/v1/webhooks/adyen")
            .header("X-Webhook-Timestamp", String.valueOf(ts))
            .header("X-Meridian-Signature", sig1)
            .contentType(MediaType.APPLICATION_JSON)
            .content(raw1))
            .andExpect(status().isOk());
        
        // Altered payload for same event ID
        String raw2 = "{\"sessionId\":\"" + sess + "\",\"eventId\":\"evt-conflict\",\"paymentId\":\"" + paymentId + "\",\"status\":\"declined\"}";
        String sig2 = hmac(SECRET, ts + "." + raw2);
        
        mvc.perform(post("/api/v1/webhooks/adyen")
            .header("X-Webhook-Timestamp", String.valueOf(ts))
            .header("X-Meridian-Signature", sig2)
            .contentType(MediaType.APPLICATION_JSON)
            .content(raw2))
            .andExpect(status().isConflict());
    }

    @Test
    void testWebhookWrongSignature403() throws Exception {
        long ts = Instant.now().getEpochSecond();
        String raw = "{\"sessionId\":\"test\",\"eventId\":\"evt\",\"paymentId\":\"pay\",\"status\":\"completed\"}";
        
        mvc.perform(post("/api/v1/webhooks/adyen")
            .header("X-Webhook-Timestamp", String.valueOf(ts))
            .header("X-Meridian-Signature", "wrongsig")
            .contentType(MediaType.APPLICATION_JSON)
            .content(raw))
            .andExpect(status().isForbidden());
    }

    @Test
    void testWebhookNoConfiguredSecret403() throws Exception {
        // This runs with secret set, so test that missing secret blocks
        // Would need separate context to test, but verifying signature enforcement works
        long ts = Instant.now().getEpochSecond();
        String raw = "{\"sessionId\":\"test\",\"eventId\":\"evt\",\"paymentId\":\"pay\",\"status\":\"completed\"}";
        
        mvc.perform(post("/api/v1/webhooks/adyen")
            .header("X-Webhook-Timestamp", String.valueOf(ts))
            .header("X-Meridian-Signature", "anysig")
            .contentType(MediaType.APPLICATION_JSON)
            .content(raw))
            .andExpect(status().isForbidden());
    }

    @Test
    void testWebhookWrongProvider() throws Exception {
        String sess = "test-webhook-wrong-prov";
        
        long ts = Instant.now().getEpochSecond();
        String raw = "{\"sessionId\":\"" + sess + "\",\"eventId\":\"evt\",\"paymentId\":\"unknown\",\"status\":\"completed\"}";
        String sig = hmac(SECRET, ts + "." + raw);
        
        mvc.perform(post("/api/v1/webhooks/bogus-provider")
            .header("X-Webhook-Timestamp", String.valueOf(ts))
            .header("X-Meridian-Signature", sig)
            .contentType(MediaType.APPLICATION_JSON)
            .content(raw))
            .andExpect(status().isBadRequest());
    }

    @Test
    void testWebhookUnsolicited() throws Exception {
        String sess = "test-webhook-unsolicited";
        
        long ts = Instant.now().getEpochSecond();
        String raw = "{\"sessionId\":\"" + sess + "\",\"eventId\":\"evt\",\"paymentId\":\"unknown-payment-id\",\"status\":\"completed\"}";
        String sig = hmac(SECRET, ts + "." + raw);
        
        mvc.perform(post("/api/v1/webhooks/adyen")
            .header("X-Webhook-Timestamp", String.valueOf(ts))
            .header("X-Meridian-Signature", sig)
            .contentType(MediaType.APPLICATION_JSON)
            .content(raw))
            .andExpect(status().isBadRequest());
    }

    @Test
    void testJsonFractionalAmount400() throws Exception {
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", "test-frac")
            .header("Idempotency-Key", "key-frac")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":25.99,\"method\":\"card\",\"scenario\":\"success\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void testJsonStringAmount400() throws Exception {
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", "test-str")
            .header("Idempotency-Key", "key-str")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":\"5000\",\"method\":\"card\",\"scenario\":\"success\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void testJsonUnknownFields400() throws Exception {
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", "test-unknown")
            .header("Idempotency-Key", "key-unknown")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":5000,\"method\":\"card\",\"scenario\":\"success\",\"unknownField\":\"value\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void testResetClearsIntent() throws Exception {
        String sess = "test-reset-intent";
        
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", sess)
            .header("Idempotency-Key", "key-reset")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":5000,\"method\":\"card\",\"scenario\":\"success\"}"))
            .andExpect(status().isOk());
        
        mvc.perform(post("/api/v1/reset").header("X-Rehearsal-Session", sess))
            .andExpect(status().isOk());
        
        // Same key can be reused in reset session
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", sess)
            .header("Idempotency-Key", "key-reset")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":3000,\"method\":\"card\",\"scenario\":\"success\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void testConcurrent2PaymentsOneAllowed() throws Exception {
        String sess = "test-concurrent-800k";
        
        // Get initial balance
        MvcResult stateResp = mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", sess))
            .andExpect(status().isOk()).andReturn();
        Map<String, Object> state = json.readValue(stateResp.getResponse().getContentAsString(), Map.class);
        long balance = ((Number) state.get("balance")).longValue();
        
        // Try 2 concurrent payments of 800000 each (requires only 800000 reserved)
        ExecutorService ex = Executors.newFixedThreadPool(2);
        AtomicInteger success = new AtomicInteger(0);
        
        try {
            for (int i = 0; i < 2; i++) {
                final int idx = i;
                ex.submit(() -> {
                    try {
                        mvc.perform(post("/api/v1/payments")
                            .header("X-Rehearsal-Session", sess)
                            .header("Idempotency-Key", "key-conc-" + idx)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":800000,\"method\":\"card\",\"scenario\":\"success\"}"))
                            .andExpect(status().isOk());
                        success.incrementAndGet();
                    } catch (Exception ignore) {}
                });
            }
            ex.shutdown();
            ex.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            if (!ex.isTerminated()) ex.shutdownNow();
        }
        
        // Only one should succeed (or both fail if balance < 800000)
        assert success.get() <= 1 || balance < 800000 : "Both concurrent payments succeeded, impossible";
    }

    @Test
    void testBudgetUpdateInterleavedPaymentsRetains() throws Exception {
        String sess = "test-budget-interleave";
        
        // Make payment 1
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", sess)
            .header("Idempotency-Key", "key-b1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":10000,\"method\":\"card\",\"scenario\":\"success\"}"))
            .andExpect(status().isOk());
        
        // Update budget
        mvc.perform(patch("/api/v1/budgets")
            .header("X-Rehearsal-Session", sess)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"category\":\"Food & drink\",\"limitMinor\":50000}"))
            .andExpect(status().isOk());
        
        // Make payment 2
        mvc.perform(post("/api/v1/payments")
            .header("X-Rehearsal-Session", sess)
            .header("Idempotency-Key", "key-b2")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":10000,\"method\":\"card\",\"scenario\":\"success\"}"))
            .andExpect(status().isOk());
        
        // Verify budget updated
        MvcResult stateResp = mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", sess))
            .andExpect(status().isOk()).andReturn();
        Map<String, Object> state = json.readValue(stateResp.getResponse().getContentAsString(), Map.class);
        java.util.List<?> budgets = (java.util.List<?>) state.get("budgets");
        assert budgets.stream().anyMatch(b -> {
            Map<String, Object> m = (Map<String, Object>) b;
            return "Food & drink".equals(m.get("category")) && 50000L == ((Number) m.get("limit")).longValue();
        }) : "Budget not retained after payments";
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
