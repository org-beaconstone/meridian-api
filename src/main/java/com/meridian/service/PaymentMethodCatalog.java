package com.meridian.service;

import com.meridian.domain.Provider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider-neutral method catalog for fixed UK and US rehearsal flows.
 * Clients select a method id. The server resolves the provider.
 */
@Service
public class PaymentMethodCatalog {
    public static final String VERSION = "2026.09.18-uk-us-1";
    public static final Duration CACHE_TTL = Duration.ofMinutes(15);
    private static final List<String> CORRIDORS = List.of("UK", "US");

    private final FixtureService fixture;

    public PaymentMethodCatalog(FixtureService fixture) {
        this.fixture = fixture;
    }

    public Map<String, Object> catalog(String corridor) {
        String filter = corridor == null || corridor.isBlank() ? null : corridor;
        if (filter != null && !CORRIDORS.contains(filter)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown corridor");
        }
        Instant issued = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("catalogVersion", VERSION);
        body.put("issuedAt", issued.toString());
        body.put("expiresAt", issued.plus(CACHE_TTL).toString());
        body.put("simulation", true);
        body.put("currency", currency());
        body.put("corridors", corridors(filter));
        body.put("methods", methods());
        return body;
    }

    private Map<String, Object> currency() {
        Map<String, Object> currency = new LinkedHashMap<>();
        currency.put("code", "GBP");
        currency.put("minorUnitExponent", 2);
        currency.put("amountEncoding", "integer-minor");
        currency.put("decoder", "legacy-integer");
        return currency;
    }

    private List<Map<String, Object>> corridors(String filter) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String code : CORRIDORS) {
            if (filter != null && !filter.equals(code)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("flow", "fixed");
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> methods() {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Provider provider : fixture.getProviders()) {
            if (provider.getMethods() == null) continue;
            for (String methodId : provider.getMethods()) {
                if (!"card".equals(methodId) && !"bank".equals(methodId)) continue;
                byId.putIfAbsent(methodId, method(methodId));
            }
        }
        return new ArrayList<>(byId.values());
    }

    private Map<String, Object> method(String methodId) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("open", true);
        descriptor.put("scheme", "meridian.payment-method");
        descriptor.put("flow", "fixed-uk-us");
        descriptor.put("methodId", methodId);
        descriptor.put("amountEncoding", "integer-minor");
        descriptor.put("minMinor", 1);
        descriptor.put("maxMinor", 1000000);
        descriptor.put("providerResolvedBy", "server");

        Map<String, Object> method = new LinkedHashMap<>();
        method.put("id", methodId);
        method.put("displayName", "card".equals(methodId) ? "Debit card" : "Bank payment");
        method.put("corridors", CORRIDORS);
        method.put("descriptor", descriptor);
        return method;
    }
}
