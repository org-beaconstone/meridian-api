package com.meridian.controller;

import com.meridian.payment.MethodCatalog;
import com.meridian.payment.ReturnStateSigner;
import com.meridian.service.RehearsalBank;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/v2")
public class PaymentIntentController {
  private final RehearsalBank bank;
  private final ReturnStateSigner signer;

  public PaymentIntentController(RehearsalBank bank, ReturnStateSigner signer) {
    this.bank = bank;
    this.signer = signer;
  }

  @GetMapping("/payment-methods")
  public Map<String, Object> paymentMethods() { return MethodCatalog.document(); }

  @PostMapping("/payment-intents")
  public ResponseEntity<Map<String, Object>> create(@RequestHeader(value = "X-Rehearsal-Session", required = false) String room,
    @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody RehearsalBank.IntentRequest body) {
    var result = bank.createIntent(room, key, body);
    signer.seal(result.body());
    return ResponseEntity.status(result.httpStatus()).body(result.body());
  }

  @GetMapping("/payment-intents/{id}")
  public Map<String, Object> read(@RequestHeader(value = "X-Rehearsal-Session", required = false) String room, @PathVariable String id) {
    var body = bank.readIntent(room, id);
    signer.seal(body);
    return body;
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<?> domainError(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode()).body(Map.of("ok", false, "error", Objects.requireNonNullElse(e.getReason(), "Request failed"), "code", "HTTP_" + e.getStatusCode().value()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<?> jsonError() {
    return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Invalid JSON request: integer amounts and known fields required", "code", "INVALID_JSON"));
  }
}
