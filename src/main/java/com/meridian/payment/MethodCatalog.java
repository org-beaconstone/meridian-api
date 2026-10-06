package com.meridian.payment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider-neutral catalog for UK and US rehearsal flows.
 * Clients receive descriptors. Provider ids stay on the server.
 */
public final class MethodCatalog {
  public static final String VERSION = "2026.09.18-uk-us";
  public static final String EXPIRES_AT = "2026-10-18T00:00:00Z";
  public static final String DESCRIPTOR_POLICY = "open";

  public record Resolved(String descriptor, String legacyMethod, String providerId, String label) {}

  private static final List<Resolved> METHODS = List.of(
    new Resolved("md_card", "card", "adyen", "Debit card"),
    new Resolved("md_bank", "bank", "worldpay", "Bank payment")
  );

  private MethodCatalog() {}

  public static Resolved resolve(String descriptor) {
    for (var method : METHODS) if (method.descriptor().equals(descriptor)) return method;
    return null;
  }

  public static Map<String, Object> document() {
    List<Map<String, Object>> methods = new ArrayList<>();
    for (var method : METHODS) {
      Map<String, Object> amount = new LinkedHashMap<>();
      amount.put("minimumMinor", 1);
      amount.put("maximumMinor", 1000000);
      amount.put("currency", "GBP");
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("descriptor", method.descriptor());
      item.put("label", method.label());
      item.put("legacyMethod", method.legacyMethod());
      item.put("corridors", List.of("UK", "US"));
      item.put("currencies", List.of("GBP"));
      item.put("flow", "server_confirmed");
      item.put("amount", amount);
      methods.add(item);
    }
    Map<String, Object> currency = new LinkedHashMap<>();
    currency.put("code", "GBP");
    currency.put("minorUnits", 2);
    currency.put("minorUnitName", "pence");
    currency.put("symbol", "£");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("catalogVersion", VERSION);
    body.put("expiresAt", EXPIRES_AT);
    body.put("descriptorPolicy", DESCRIPTOR_POLICY);
    body.put("currency", currency);
    body.put("corridors", List.of("UK", "US"));
    body.put("simulation", true);
    body.put("methods", methods);
    return body;
  }
}
