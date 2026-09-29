package com.meridian.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Simulation HMAC over an intent snapshot. Not an Adyen or Worldpay signature. */
@Component
public class ReturnStateSigner {
  public static final String DEFAULT_SECRET = "meridian-rehearsal-return-state";
  private final String secret;

  public ReturnStateSigner(@Value("${meridian.return-state.secret:meridian-rehearsal-return-state}") String secret) {
    this.secret = secret == null || secret.isBlank() ? DEFAULT_SECRET : secret;
  }

  public Map<String, Object> sign(String intentId, String status, long amountMinor) {
    String issuedAt = Instant.now().toString();
    var state = new LinkedHashMap<String, Object>();
    state.put("intentId", intentId);
    state.put("status", status);
    state.put("amountMinor", amountMinor);
    state.put("issuedAt", issuedAt);
    state.put("algorithm", "HMAC-SHA256");
    state.put("signature", hmac(canonical(intentId, status, amountMinor, issuedAt)));
    return state;
  }

  public static String canonical(String intentId, String status, long amountMinor, String issuedAt) {
    return intentId + "." + status + "." + amountMinor + "." + issuedAt;
  }

  private String hmac(String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
