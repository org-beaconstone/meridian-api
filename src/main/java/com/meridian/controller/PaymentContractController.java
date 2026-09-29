package com.meridian.controller;

import com.meridian.payment.PaymentFlowCatalog;
import com.meridian.service.RehearsalBank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v2")
public class PaymentContractController {
  private final RehearsalBank bank;
  private final PaymentFlowCatalog catalog;

  public PaymentContractController(RehearsalBank bank, PaymentFlowCatalog catalog) {
    this.bank = bank;
    this.catalog = catalog;
  }

  @GetMapping("/payment-methods")
  public Map<String, Object> paymentMethods() {
    return catalog.catalog();
  }

  @PostMapping("/payment-intents")
  public ResponseEntity<Map<String, Object>> createIntent(
      @RequestHeader(value = "X-Rehearsal-Session", required = false) String room,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody RehearsalBank.IntentRequest body) {
    var result = bank.createIntent(room, key, body);
    return ResponseEntity.status(httpStatus((String) result.get("status"))).body(result);
  }

  @GetMapping("/payment-intents/{id}")
  public Map<String, Object> readIntent(
      @RequestHeader(value = "X-Rehearsal-Session", required = false) String room,
      @PathVariable String id) {
    return bank.readIntent(room, id);
  }

  static int httpStatus(String lifecycle) {
    return switch (lifecycle) {
      case "succeeded" -> 200;
      case "pending_confirmation", "processing" -> 202;
      case "declined" -> 422;
      case "unavailable" -> 503;
      default -> 400;
    };
  }
}
