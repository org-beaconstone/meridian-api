package com.meridian.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/** HMAC of the authoritative intent snapshot. Simulation key only; not a vendor signature. */
@Component
public class ReturnStateSigner {
  private final String secret;

  public ReturnStateSigner(@Value("${meridian.return-state.secret:rehearsal-return-state}") String secret) {
    this.secret = (secret == null || secret.isBlank()) ? "rehearsal-return-state" : secret;
  }

  public void seal(Map<String, Object> body) {
    String issuedAt = Instant.now().toString();
    body.put("issuedAt", issuedAt);
    body.put("returnState", hmac(canonical(body, issuedAt)));
  }

  /**
   * Pipe-joined snapshot. Empty balance, receipt id, and action type mean those fields are absent.
   * v2|{id}|{status}|{amountMinor}|{descriptor}|{corridor}|{catalogVersion}|{balanceMinor}|{receiptId}|{actionType}|{issuedAt}
   */
  public static String canonical(Map<String, Object> body, String issuedAt) {
    String receiptId = "";
    Object receipt = body.get("receipt");
    if (receipt instanceof Map<?, ?> map && map.get("id") != null) receiptId = String.valueOf(map.get("id"));
    String actionType = "";
    Object action = body.get("action");
    if (action instanceof Map<?, ?> map && map.get("type") != null) actionType = String.valueOf(map.get("type"));
    String balance = body.get("balanceMinor") == null ? "" : Long.toString(((Number) body.get("balanceMinor")).longValue());
    return String.join("|",
      "v2",
      String.valueOf(body.get("id")),
      String.valueOf(body.get("status")),
      Long.toString(((Number) body.get("amountMinor")).longValue()),
      String.valueOf(body.get("descriptor")),
      String.valueOf(body.get("corridor")),
      String.valueOf(body.get("catalogVersion")),
      balance,
      receiptId,
      actionType,
      issuedAt);
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
