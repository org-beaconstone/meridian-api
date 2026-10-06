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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
    "meridian.return-state.secret=test-return-secret",
    "spring.datasource.url=jdbc:h2:mem:intent-contract;DB_CLOSE_DELAY=-1",
    "spring.h2.console.enabled=false"
})
class PaymentIntentContractTest {
    private static final String CATALOG = "2026.09.18-uk-us";
    private static final String RETURN_SECRET = "test-return-secret";
    private static final String WEBHOOK_SECRET = "test-key-webhook-secret";
    private static final long OPENING = 1_248_050L;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    void catalogPublishesVersionExpiryCurrencyAndOpenDescriptors() throws Exception {
        mvc.perform(get("/api/v2/payment-methods"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.catalogVersion").value(CATALOG))
            .andExpect(jsonPath("$.expiresAt").value("2026-10-18T00:00:00Z"))
            .andExpect(jsonPath("$.descriptorPolicy").value("open"))
            .andExpect(jsonPath("$.currency.code").value("GBP"))
            .andExpect(jsonPath("$.currency.minorUnits").value(2))
            .andExpect(jsonPath("$.currency.minorUnitName").value("pence"))
            .andExpect(jsonPath("$.corridors[0]").value("UK"))
            .andExpect(jsonPath("$.corridors[1]").value("US"))
            .andExpect(jsonPath("$.methods.length()").value(2))
            .andExpect(jsonPath("$.methods[0].descriptor").value("md_card"))
            .andExpect(jsonPath("$.methods[0].legacyMethod").value("card"))
            .andExpect(jsonPath("$.methods[1].descriptor").value("md_bank"))
            .andExpect(jsonPath("$.methods[1].legacyMethod").value("bank"))
            .andExpect(jsonPath("$.simulation").value(true));
        String body = mvc.perform(get("/api/v2/payment-methods")).andReturn().getResponse().getContentAsString().toLowerCase();
        assertFalse(body.contains("adyen"));
        assertFalse(body.contains("worldpay"));
    }

    @Test
    void ukCardSucceedsThroughAdyenWithoutExposingProvider() throws Exception {
        MvcResult result = submit("uk-card", "key-uk-card", intent("md_card", "UK", 2599, "success"));
        assertEquals(200, result.getResponse().getStatus());
        Map<String, Object> body = body(result);
        assertEquals("succeeded", body.get("status"));
        assertEquals(true, body.get("authoritative"));
        assertEquals(CATALOG, body.get("catalogVersion"));
        assertEquals("md_card", body.get("descriptor"));
        assertEquals("UK", body.get("corridor"));
        assertEquals(null, body.get("action"));
        assertEquals(OPENING - 2599, ((Number) body.get("balanceMinor")).longValue());
        @SuppressWarnings("unchecked") Map<String, Object> receipt = (Map<String, Object>) body.get("receipt");
        assertEquals("card", receipt.get("method"));
        assertEquals(2599, ((Number) receipt.get("amountMinor")).intValue());
        assertFalse(receipt.containsKey("provider"));
        assertSigned(body);
        assertFalse(result.getResponse().getContentAsString().toLowerCase().contains("adyen"));
        assertLedger("uk-card", (String) body.get("id"), "adyen", OPENING - 2599);
    }

    @Test
    void usBankSucceedsThroughWorldpayWithoutExposingProvider() throws Exception {
        MvcResult result = submit("us-bank", "key-us-bank", intent("md_bank", "US", 1000, "success"));
        assertEquals(200, result.getResponse().getStatus());
        Map<String, Object> body = body(result);
        assertEquals("md_bank", body.get("descriptor"));
        assertEquals("US", body.get("corridor"));
        assertEquals("bank", ((Map<?, ?>) body.get("receipt")).get("method"));
        assertSigned(body);
        assertFalse(result.getResponse().getContentAsString().toLowerCase().contains("worldpay"));
        assertLedger("us-bank", (String) body.get("id"), "worldpay", OPENING - 1000);
    }

    @Test
    void legacyIntegerDecoderRejectsFractionalAndStringAmounts() throws Exception {
        mvc.perform(post("/api/v2/payment-intents").header("X-Rehearsal-Session", "frac").header("Idempotency-Key", "k")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":25.99,\"descriptor\":\"md_card\",\"corridor\":\"UK\",\"catalogVersion\":\"" + CATALOG + "\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JSON"));
        mvc.perform(post("/api/v2/payment-intents").header("X-Rehearsal-Session", "str").header("Idempotency-Key", "k")
                .contentType(MediaType.APPLICATION_JSON).content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":\"2599\",\"descriptor\":\"md_card\",\"corridor\":\"UK\",\"catalogVersion\":\"" + CATALOG + "\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JSON"));
        mvc.perform(post("/api/v2/payment-intents").header("X-Rehearsal-Session", "unk").header("Idempotency-Key", "k")
                .contentType(MediaType.APPLICATION_JSON).content(intent("md_card", "UK", 100, "success").replace("}", ",\"provider\":\"adyen\"}")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JSON"));
    }

    @Test
    void unpublishedDescriptorAndNonUkUsCorridorAreRejected() throws Exception {
        mvc.perform(post("/api/v2/payment-intents").header("X-Rehearsal-Session", "open-desc").header("Idempotency-Key", "k")
                .contentType(MediaType.APPLICATION_JSON).content(intent("md_future", "UK", 100, "success")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Unknown payment method descriptor"));
        mvc.perform(post("/api/v2/payment-intents").header("X-Rehearsal-Session", "eu-corridor").header("Idempotency-Key", "k")
                .contentType(MediaType.APPLICATION_JSON).content(intent("md_card", "EU", 100, "success")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Unsupported corridor"));
        mvc.perform(post("/api/v2/payment-intents").header("X-Rehearsal-Session", "stale").header("Idempotency-Key", "k")
                .contentType(MediaType.APPLICATION_JSON).content(intent("md_card", "UK", 100, "success").replace(CATALOG, "1999.01.01-uk-us")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Payment method catalog is stale"));
    }

    @Test
    void idempotentReplayDoesNotDebitTwiceAndMismatchConflicts() throws Exception {
        MvcResult first = submit("idem", "same-key", intent("md_card", "UK", 1500, "success"));
        assertEquals(200, first.getResponse().getStatus());
        String id = (String) body(first).get("id");
        MvcResult second = submit("idem", "same-key", intent("md_card", "UK", 1500, "success"));
        assertEquals(200, second.getResponse().getStatus());
        assertEquals(id, body(second).get("id"));
        assertEquals(OPENING - 1500, ((Number) body(second).get("balanceMinor")).longValue());
        mvc.perform(post("/api/v2/payment-intents").header("X-Rehearsal-Session", "idem").header("Idempotency-Key", "same-key")
                .contentType(MediaType.APPLICATION_JSON).content(intent("md_card", "US", 1500, "success")))
            .andExpect(status().isConflict());
    }

    @Test
    void pendingActionIsPolledThenWebhookCompletesOnce() throws Exception {
        MvcResult created = submit("pend", "pend-key", intent("md_card", "UK", 5000, "pending"));
        assertEquals(202, created.getResponse().getStatus());
        Map<String, Object> pending = body(created);
        assertEquals("requires_action", pending.get("status"));
        assertEquals(true, pending.get("authoritative"));
        assertFalse(pending.containsKey("balanceMinor"));
        @SuppressWarnings("unchecked") Map<String, Object> action = (Map<String, Object>) pending.get("action");
        assertEquals("await_confirmation", action.get("type"));
        @SuppressWarnings("unchecked") Map<String, Object> payload = (Map<String, Object>) action.get("payload");
        assertEquals(pending.get("id"), payload.get("intentId"));
        assertEquals("poll", payload.get("clientAction"));
        assertSigned(pending);
        mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", "pend")).andExpect(jsonPath("$.balance").value((int) OPENING));

        MvcResult polled = mvc.perform(get("/api/v2/payment-intents/" + pending.get("id")).header("X-Rehearsal-Session", "pend")).andReturn();
        assertEquals(200, polled.getResponse().getStatus());
        assertEquals("requires_action", body(polled).get("status"));
        assertSigned(body(polled));
        mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", "pend")).andExpect(jsonPath("$.balance").value((int) OPENING));

        webhook("pend", (String) pending.get("id"), "evt-1", "completed", "adyen");
        MvcResult done = mvc.perform(get("/api/v2/payment-intents/" + pending.get("id")).header("X-Rehearsal-Session", "pend")).andReturn();
        assertEquals("succeeded", body(done).get("status"));
        assertEquals(null, body(done).get("action"));
        assertEquals(OPENING - 5000, ((Number) body(done).get("balanceMinor")).longValue());
        assertSigned(body(done));
        webhook("pend", (String) pending.get("id"), "evt-1", "completed", "adyen");
        mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", "pend")).andExpect(jsonPath("$.balance").value((int) (OPENING - 5000)));
    }

    @Test
    void declinedCanRetrySameKeyAndUnavailableDoesNotChangeProvider() throws Exception {
        MvcResult declined = submit("dec", "dec-key", intent("md_card", "UK", 2000, "declined"));
        assertEquals(422, declined.getResponse().getStatus());
        assertEquals("declined", body(declined).get("status"));
        assertSigned(body(declined));
        MvcResult retried = submit("dec", "dec-key", intent("md_card", "UK", 2000, "success"));
        assertEquals(200, retried.getResponse().getStatus());
        assertEquals(body(declined).get("id"), body(retried).get("id"));
        assertLedger("dec", (String) body(retried).get("id"), "adyen", OPENING - 2000);

        MvcResult failed = submit("unavail", "unavail-key", intent("md_bank", "US", 2000, "unavailable"));
        assertEquals(503, failed.getResponse().getStatus());
        assertEquals("failed", body(failed).get("status"));
        mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", "unavail")).andExpect(jsonPath("$.balance").value((int) OPENING));
        MvcResult recovered = submit("unavail", "unavail-key", intent("md_bank", "US", 2000, "success"));
        assertEquals(200, recovered.getResponse().getStatus());
        assertLedger("unavail", (String) body(recovered).get("id"), "worldpay", OPENING - 2000);
    }

    @Test
    void readsStayInsideTheSessionAndV1ContractStillDebits() throws Exception {
        MvcResult created = submit("room-a", "k", intent("md_card", "UK", 100, "success"));
        String id = (String) body(created).get("id");
        mvc.perform(get("/api/v2/payment-intents/" + id).header("X-Rehearsal-Session", "room-b")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v2/payment-intents/missing-intent").header("X-Rehearsal-Session", "room-a")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v2/payment-intents").header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON).content(intent("md_card", "UK", 100, "success")))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/payments").header("X-Rehearsal-Session", "v1-still").header("Idempotency-Key", "v1-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":2599,\"method\":\"card\",\"scenario\":\"success\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.transaction.provider").value("adyen"));
        mvc.perform(post("/api/v2/payment-intents").header("X-Rehearsal-Session", "v1-still").header("Idempotency-Key", "v1-key")
                .contentType(MediaType.APPLICATION_JSON).content(intent("md_card", "UK", 2599, "success")))
            .andExpect(status().isConflict());
    }

    @Test
    void pendingReservationBlocksASecondSpendAndResetClearsTheKey() throws Exception {
        submit("reserve", "hold", intent("md_card", "UK", 800000, "pending"));
        mvc.perform(post("/api/v1/payments").header("X-Rehearsal-Session", "reserve").header("Idempotency-Key", "other")
                .contentType(MediaType.APPLICATION_JSON).content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":800000,\"method\":\"bank\",\"scenario\":\"success\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", "reserve")).andExpect(jsonPath("$.balance").value((int) OPENING));

        submit("reset-me", "reuse", intent("md_card", "UK", 100, "success"));
        mvc.perform(post("/api/v1/reset").header("X-Rehearsal-Session", "reset-me")).andExpect(status().isOk());
        MvcResult again = submit("reset-me", "reuse", intent("md_bank", "US", 200, "success"));
        assertEquals(200, again.getResponse().getStatus());
        assertEquals(OPENING - 200, ((Number) body(again).get("balanceMinor")).longValue());
    }

    @Test
    void reconciliationDeclineStaysTerminal() throws Exception {
        MvcResult created = submit("final", "final-key", intent("md_card", "UK", 2500, "pending"));
        webhook("final", (String) body(created).get("id"), "evt-no", "declined", "adyen");
        MvcResult replay = submit("final", "final-key", intent("md_card", "UK", 2500, "success"));
        assertEquals(422, replay.getResponse().getStatus());
        assertEquals("declined", body(replay).get("status"));
        assertEquals("Payment was declined by reconciliation", body(replay).get("statusReason"));
        mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", "final")).andExpect(jsonPath("$.balance").value((int) OPENING));
    }

    @Test
    void changedStatusDoesNotMatchTheSignedReturnState() throws Exception {
        Map<String, Object> body = body(submit("sig", "sig-key", intent("md_card", "UK", 100, "success")));
        assertSigned(body);
        body.put("status", "failed");
        String forged = canonical(body, (String) body.get("issuedAt"));
        assertFalse(hmac(RETURN_SECRET, forged).equals(body.get("returnState")));
    }

    @Test
    void concurrentOverspendAllowsOneIntent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger succeeded = new AtomicInteger();
        try {
            for (int i = 0; i < 2; i++) {
                int n = i;
                pool.submit(() -> {
                    try {
                        MvcResult result = submit("race", "race-" + n, intent("md_card", "UK", 800000, "success"));
                        if (result.getResponse().getStatus() == 200) succeeded.incrementAndGet();
                    } catch (Exception ignored) {
                        // The losing writer is reported through HTTP status.
                    }
                });
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(1, succeeded.get());
        mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", "race")).andExpect(jsonPath("$.balance").value(448050));
    }

    private void assertLedger(String session, String id, String provider, long balance) throws Exception {
        Map<String, Object> state = body(mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", session)).andReturn());
        assertEquals(balance, ((Number) state.get("balance")).longValue());
        boolean found = false;
        for (Object item : (List<?>) state.get("transactions")) {
            Map<?, ?> txn = (Map<?, ?>) item;
            if (id.equals(txn.get("id"))) {
                found = true;
                assertEquals(provider, txn.get("provider"));
            }
        }
        assertTrue(found);
    }

    private MvcResult submit(String session, String key, String payload) throws Exception {
        return mvc.perform(post("/api/v2/payment-intents")
            .header("X-Rehearsal-Session", session)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload)).andReturn();
    }

    private String intent(String descriptor, String corridor, int amount, String scenario) {
        return "{\"recipientId\":\"birch-bloom\",\"amountMinor\":" + amount + ",\"descriptor\":\"" + descriptor
            + "\",\"corridor\":\"" + corridor + "\",\"catalogVersion\":\"" + CATALOG + "\",\"scenario\":\"" + scenario + "\"}";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    private void webhook(String session, String paymentId, String eventId, String outcome, String provider) throws Exception {
        String raw = "{\"sessionId\":\"" + session + "\",\"eventId\":\"" + eventId + "\",\"paymentId\":\"" + paymentId + "\",\"status\":\"" + outcome + "\"}";
        long ts = Instant.now().getEpochSecond();
        mvc.perform(post("/api/v1/webhooks/" + provider)
                .header("X-Webhook-Timestamp", String.valueOf(ts))
                .header("X-Meridian-Signature", hmac(WEBHOOK_SECRET, ts + "." + raw))
                .contentType(MediaType.APPLICATION_JSON).content(raw))
            .andExpect(status().isOk());
    }

    private void assertSigned(Map<String, Object> body) {
        String issuedAt = (String) body.get("issuedAt");
        String canonical = canonical(body, issuedAt);
        assertEquals(canonical, ReturnStateSigner.canonical(body, issuedAt));
        assertEquals(hmac(RETURN_SECRET, canonical), body.get("returnState"));
    }

    private String canonical(Map<String, Object> body, String issuedAt) {
        String receiptId = "";
        if (body.get("receipt") instanceof Map<?, ?> receipt && receipt.get("id") != null) receiptId = String.valueOf(receipt.get("id"));
        String actionType = "";
        if (body.get("action") instanceof Map<?, ?> action && action.get("type") != null) actionType = String.valueOf(action.get("type"));
        String balance = body.get("balanceMinor") == null ? "" : Long.toString(((Number) body.get("balanceMinor")).longValue());
        return String.join("|", "v2", String.valueOf(body.get("id")), String.valueOf(body.get("status")),
            Long.toString(((Number) body.get("amountMinor")).longValue()), String.valueOf(body.get("descriptor")),
            String.valueOf(body.get("corridor")), String.valueOf(body.get("catalogVersion")), balance, receiptId, actionType, issuedAt);
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
