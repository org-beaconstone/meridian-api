package com.meridian.payment;

import java.util.Set;

/**
 * Selects an existing adapter. European corridors use Adyen as the rehearsal assumption
 * and stay inside the mock sandbox. UK and US keep the card/bank split already used by
 * {@code POST /payments}. The paired id is recorded for classification only.
 */
public final class ProviderRouter {
  public static final Set<String> EUROPEAN = Set.of(
    "EU", "DE", "FR", "NL", "IE", "ES", "IT", "BE", "AT", "PT", "FI", "SE", "DK", "PL", "LU", "NO");
  public static final Set<String> DOMESTIC = Set.of("UK", "US");

  public record Decision(String providerId, String corridor, String reason, String pairedProviderId) {}

  private ProviderRouter() {}

  public static Decision route(String corridor, String method) {
    if (corridor == null || method == null) return null;
    if (EUROPEAN.contains(corridor)) {
      return new Decision("adyen", corridor, "european-corridor-adyen-sandbox", "worldpay");
    }
    if (DOMESTIC.contains(corridor)) {
      String providerId = "card".equals(method) ? "adyen" : "worldpay";
      String paired = "adyen".equals(providerId) ? "worldpay" : "adyen";
      return new Decision(providerId, corridor, "uk-us-method-route", paired);
    }
    return null;
  }
}
