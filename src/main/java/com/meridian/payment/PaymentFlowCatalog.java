package com.meridian.payment;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Provider-neutral method catalog and the fixed UK/US resolution table.
 * Descriptors do not name a provider. The orchestrator binds a provider when an intent is created.
 */
@Component
public class PaymentFlowCatalog {
  public static final String CATALOG_VERSION = "2026-09-18.1";
  public static final String EXPIRES_AT = "2026-09-19T00:00:00Z";

  public record Resolution(String method, String corridor, String provider, String flow) {}

  public Map<String, Object> currency() {
    var currency = new LinkedHashMap<String, Object>();
    currency.put("code", "GBP");
    currency.put("exponent", 2);
    currency.put("minorUnit", "pence");
    currency.put("amountEncoding", "integer");
    return currency;
  }

  public Map<String, Object> catalog() {
    var body = new LinkedHashMap<String, Object>();
    body.put("catalogVersion", CATALOG_VERSION);
    body.put("expiresAt", EXPIRES_AT);
    body.put("currency", currency());
    body.put("methods", List.of(method("card", "Debit card"), method("bank", "Bank payment")));
    return body;
  }

  public Resolution resolve(String method, String corridor) {
    if (!"card".equals(method) && !"bank".equals(method)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown payment method");
    }
    if (!"UK".equals(corridor) && !"US".equals(corridor)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported corridor");
    }
    String provider = "card".equals(method) ? "adyen" : "worldpay";
    String flow = "fixed-" + corridor.toLowerCase(Locale.ROOT) + "-" + method;
    return new Resolution(method, corridor, provider, flow);
  }

  private Map<String, Object> method(String id, String displayName) {
    var descriptor = new LinkedHashMap<String, Object>();
    descriptor.put("binding", "open");
    descriptor.put("method", id);
    descriptor.put("corridors", List.of("UK", "US"));
    descriptor.put("flows", List.of(resolve(id, "UK").flow(), resolve(id, "US").flow()));
    var method = new LinkedHashMap<String, Object>();
    method.put("id", id);
    method.put("displayName", displayName);
    method.put("descriptor", descriptor);
    return method;
  }
}
