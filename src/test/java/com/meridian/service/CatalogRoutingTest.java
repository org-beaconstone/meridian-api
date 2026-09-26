package com.meridian.service;

import com.meridian.domain.Provider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogRoutingTest {
  @Test
  void baselineCatalogMapsCardToAdyenAndBankToWorldpay() {
    CatalogRouting routing = new CatalogRouting(baseline(), Set.of("adyen", "worldpay"));
    assertTrue(routing.supportsMethod("card"));
    assertTrue(routing.supportsMethod("bank"));
    assertEquals("adyen", routing.providerIdForMethod("card"));
    assertEquals("worldpay", routing.providerIdForMethod("bank"));
    assertFalse(routing.supportsMethod("adyen_card"));
    assertFalse(routing.supportsMethod("worldpay_bank"));
  }

  @Test
  void rejectsAMethodClaimedByTwoProviders() {
    List<Provider> catalog = List.of(
      new Provider("adyen", "Adyen", "Card payment processor", List.of("card")),
      new Provider("worldpay", "Worldpay", "Bank transfer processor", List.of("card"))
    );
    assertThrows(IllegalStateException.class, () -> new CatalogRouting(catalog, Set.of("adyen", "worldpay")));
  }

  @Test
  void rejectsACatalogProviderWithoutAnAdapter() {
    assertThrows(IllegalStateException.class, () -> new CatalogRouting(baseline(), Set.of("adyen")));
  }

  @Test
  void rejectsAnAdapterMissingFromTheCatalog() {
    assertThrows(IllegalStateException.class, () -> new CatalogRouting(baseline(), Set.of("adyen", "worldpay", "unused")));
  }

  private static List<Provider> baseline() {
    return List.of(
      new Provider("adyen", "Adyen", "Card payment processor", List.of("card")),
      new Provider("worldpay", "Worldpay", "Bank transfer processor", List.of("bank"))
    );
  }
}
