package com.meridian.controller;

import com.meridian.domain.CreateIntent;
import com.meridian.service.IntentPresenter;
import com.meridian.service.PaymentMethodCatalog;
import com.meridian.service.RehearsalBank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2")
public class V2ApiController {
    private final PaymentMethodCatalog catalog;
    private final RehearsalBank bank;
    private final IntentPresenter presenter;

    public V2ApiController(PaymentMethodCatalog catalog, RehearsalBank bank, IntentPresenter presenter) {
        this.catalog = catalog;
        this.bank = bank;
        this.presenter = presenter;
    }

    @GetMapping("/payment-methods")
    public Map<String, Object> paymentMethods(@RequestParam(value = "corridor", required = false) String corridor) {
        return catalog.catalog(corridor);
    }

    @PostMapping("/payment-intents")
    public ResponseEntity<Map<String, Object>> createIntent(
        @RequestHeader(value = "X-Rehearsal-Session", required = false) String room,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestBody CreateIntent body) {
        session(room);
        idempotency(key);
        if (body == null || body.catalogVersion() == null || body.catalogVersion().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "catalogVersion is required");
        }
        if (!PaymentMethodCatalog.VERSION.equals(body.catalogVersion())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "ok", false,
                "error", "Payment method catalog is stale. Fetch GET /api/v2/payment-methods again.",
                "code", "CATALOG_STALE"));
        }
        rejectProviderSelection(body);
        String corridor = body.corridor() == null || body.corridor().isBlank() ? "UK" : body.corridor();
        bank.payment(room, key, new RehearsalBank.Payment(
            body.recipientId(), body.amountMinor(), body.methodId(), body.note(), body.scenario()), corridor);
        var view = bank.findByClientKey(room, key);
        return ResponseEntity.status(presenter.httpStatus(view.phase())).body(presenter.present(room, view));
    }

    @GetMapping("/payment-intents/{id}")
    public Map<String, Object> getIntent(
        @RequestHeader(value = "X-Rehearsal-Session", required = false) String room,
        @PathVariable String id) {
        return presenter.present(room, bank.findIntent(room, id));
    }

    private void session(String room) {
        if (room == null || !room.matches("[A-Za-z0-9_-]{3,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid rehearsal session");
        }
    }

    private void idempotency(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
        }
    }

    private void rejectProviderSelection(CreateIntent body) {
        if (body.descriptor() == null) return;
        for (String forbidden : List.of("provider", "providerId", "acquirer")) {
            if (body.descriptor().containsKey(forbidden)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provider is resolved by the server");
            }
        }
        Object echoed = body.descriptor().get("methodId");
        if (echoed != null && !(echoed instanceof String methodId && methodId.equals(body.methodId()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Descriptor method does not match methodId");
        }
    }
}
