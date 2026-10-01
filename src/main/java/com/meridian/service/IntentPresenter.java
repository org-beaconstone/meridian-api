package com.meridian.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.domain.Transaction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Maps a stored intent onto the v2 lifecycle resource. GET is authoritative. */
@Component
public class IntentPresenter {
    private final RehearsalBank bank;
    private final ObjectMapper json;
    private final String secret;

    public IntentPresenter(RehearsalBank bank, ObjectMapper json, @Value("${meridian.webhook.secret:}") String secret) {
        this.bank = bank;
        this.json = json;
        this.secret = secret == null ? "" : secret;
    }

    public int httpStatus(String phase) {
        return switch (status(phase)) {
            case "succeeded" -> 200;
            case "declined" -> 422;
            case "unavailable" -> 503;
            default -> 202;
        };
    }

    public Map<String, Object> present(String room, RehearsalBank.IntentView view) {
        String status = status(view.phase());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", view.id());
        body.put("status", status);
        body.put("authoritative", true);
        body.put("simulation", true);
        body.put("amountMinor", view.amountMinor());
        body.put("currency", "GBP");
        body.put("methodId", view.method());
        body.put("corridor", view.corridor());
        body.put("catalogVersion", PaymentMethodCatalog.VERSION);
        body.put("action", action(status, view.id()));
        body.put("returnState", returnState(view, status));
        body.put("receipt", receipt(room, view, status));
        body.put("code", code(status));
        body.put("error", error(status));
        return body;
    }

    static String status(String phase) {
        return switch (phase) {
            case "completed" -> "succeeded";
            case "pending" -> "requires_action";
            case "declined", "declined-final" -> "declined";
            case "unavailable" -> "unavailable";
            default -> "processing";
        };
    }

    private Map<String, Object> action(String status, String id) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intentId", id);
        String type = switch (status) {
            case "processing" -> "poll";
            case "requires_action" -> "await_webhook";
            case "unavailable" -> "retry_same_key";
            default -> null;
        };
        if (type == null) return null;
        if ("poll".equals(type)) payload.put("href", "/api/v2/payment-intents/" + id);
        if ("await_webhook".equals(type)) payload.put("instruction", "Wait for the simulation callback. Do not create another payment.");
        if ("retry_same_key".equals(type)) payload.put("sameKeyRequired", true);
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", type);
        action.put("payload", payload);
        return action;
    }

    private Map<String, Object> returnState(RehearsalBank.IntentView view, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intentId", view.id());
        payload.put("status", status);
        payload.put("amountMinor", view.amountMinor());
        payload.put("currency", "GBP");
        payload.put("methodId", view.method());
        payload.put("corridor", view.corridor());
        payload.put("catalogVersion", PaymentMethodCatalog.VERSION);
        String canonical;
        try {
            canonical = json.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String signature = secret.isBlank() ? null : hmac(canonical);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("algorithm", "HMAC-SHA256");
        state.put("payload", payload);
        state.put("canonical", canonical);
        state.put("signature", signature);
        state.put("verifiable", signature != null);
        return state;
    }

    private Map<String, Object> receipt(String room, RehearsalBank.IntentView view, String status) {
        if (!"succeeded".equals(status)) return null;
        Transaction txn = bank.state(room).getTransactions().stream()
            .filter(item -> view.id().equals(item.getId()))
            .findFirst()
            .orElse(null);
        if (txn == null) return null;
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("transactionId", txn.getId());
        receipt.put("reference", txn.getReference());
        receipt.put("recipientId", txn.getRecipientId());
        receipt.put("amountMinor", txn.getAmount());
        receipt.put("method", txn.getMethod());
        receipt.put("status", txn.getStatus());
        return receipt;
    }

    private String code(String status) {
        return switch (status) {
            case "requires_action", "processing" -> "PAYMENT_PENDING";
            case "declined" -> "PAYMENT_DECLINED";
            case "unavailable" -> "PROVIDER_UNAVAILABLE";
            default -> null;
        };
    }

    private String error(String status) {
        return switch (status) {
            case "requires_action", "processing" -> "Payment pending confirmation. Do not create another payment.";
            case "declined" -> "Payment declined. No debit was made.";
            case "unavailable" -> "Provider unavailable before authorization. No debit was made.";
            default -> null;
        };
    }

    private String hmac(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
