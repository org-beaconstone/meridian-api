package com.meridian.payment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProviderRouterTest {
  @Test void europeanCorridorsUseAdyenSandboxAssumption() {
    for (String corridor : ProviderRouter.EUROPEAN) {
      var card = ProviderRouter.route(corridor, "card");
      var bank = ProviderRouter.route(corridor, "bank");
      assertEquals("adyen", card.providerId());
      assertEquals("adyen", bank.providerId());
      assertEquals("worldpay", card.pairedProviderId());
      assertEquals("european-corridor-adyen-sandbox", card.reason());
    }
  }

  @Test void domesticCorridorsKeepTheExistingMethodSplit() {
    assertEquals("adyen", ProviderRouter.route("UK", "card").providerId());
    assertEquals("worldpay", ProviderRouter.route("UK", "bank").providerId());
    assertEquals("adyen", ProviderRouter.route("US", "card").providerId());
    assertEquals("worldpay", ProviderRouter.route("US", "bank").providerId());
    assertEquals("uk-us-method-route", ProviderRouter.route("US", "bank").reason());
    assertEquals("adyen", ProviderRouter.route("UK", "bank").pairedProviderId());
  }

  @Test void unknownCorridorDoesNotSelectAProvider() {
    assertNull(ProviderRouter.route("ZZ", "card"));
    assertNull(ProviderRouter.route(null, "card"));
    assertNull(ProviderRouter.route("EU", null));
  }
}
