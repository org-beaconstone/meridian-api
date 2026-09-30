package com.meridian.payment;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MockProviderSandboxTest {
  @Test void fixturesListOnlyAdyenAndWorldpay() {
    assertEquals(Set.of("adyen", "worldpay"), SandboxCatalog.get().providerIds());
  }

  @Test void adyenAndWorldpayMapScenariosThroughTheirOwnCodes() {
    var adyen = MockProviderSandbox.dispatch("adyen", "intent-1", "success", "EU");
    assertEquals("SETTLED", adyen.outcome());
    assertEquals("SANDBOX_00", adyen.providerCode());
    assertTrue(adyen.captured());
    assertFalse(adyen.failoverEligible());

    var worldpay = MockProviderSandbox.dispatch("worldpay", "intent-2", "success", "UK");
    assertEquals("SANDBOX_AUTHORISED", worldpay.providerCode());
    assertTrue(worldpay.captured());

    var soft = MockProviderSandbox.dispatch("adyen", "intent-3", "soft_decline", "DE");
    assertEquals("SOFT_DECLINE", soft.outcome());
    assertEquals("SOFT", soft.declineClass());
    assertEquals("51", soft.providerCode());
    assertTrue(soft.failoverEligible());
    assertFalse(soft.captured());

    var wpSoft = MockProviderSandbox.dispatch("worldpay", "intent-4", "soft_decline", "US");
    assertEquals("INSUFFICIENT_FUNDS", wpSoft.providerCode());
    assertTrue(wpSoft.failoverEligible());

    var hard = MockProviderSandbox.dispatch("adyen", "intent-5", "hard_decline", "EU");
    assertEquals("HARD", hard.declineClass());
    assertEquals("05", hard.providerCode());
    assertFalse(hard.failoverEligible());
    assertFalse(hard.captured());

    var wpHard = MockProviderSandbox.dispatch("worldpay", "intent-6", "hard_decline", "UK");
    assertEquals("REFUSED", wpHard.providerCode());
    assertFalse(wpHard.failoverEligible());
  }

  @Test void unknownCodesAreHardAndAmbiguousIsNotADecline() {
    var unknown = MockProviderSandbox.classifyCode("adyen", "999");
    assertEquals("HARD", unknown.declineClass());
    assertFalse(unknown.failoverEligible());
    assertFalse(unknown.captured());
    assertEquals("HARD", MockProviderSandbox.classifyCode("worldpay", "UNMAPPED").declineClass());
    assertEquals("HARD", MockProviderSandbox.classifyCode("adyen", "").declineClass());
    assertEquals("HARD", MockProviderSandbox.classifyCode("worldpay", "SOMETHING_NEW").declineClass());
    assertTrue(MockProviderSandbox.classifyCode("adyen", "65").failoverEligible());
    assertTrue(MockProviderSandbox.classifyCode("adyen", "91").failoverEligible());
    assertFalse(MockProviderSandbox.classifyCode("adyen", "41").failoverEligible());
    assertTrue(MockProviderSandbox.classifyCode("worldpay", "SCA_SOFT_DECLINE").failoverEligible());
    assertFalse(MockProviderSandbox.classifyCode("worldpay", "LOST_CARD").failoverEligible());

    var ambiguous = MockProviderSandbox.dispatch("adyen", "intent-7", "ambiguous", "EU");
    assertEquals("AMBIGUOUS", ambiguous.outcome());
    assertEquals("NONE", ambiguous.declineClass());
    assertFalse(ambiguous.failoverEligible());
    assertFalse(ambiguous.captured());

    var unmappedScenario = MockProviderSandbox.dispatch("adyen", "intent-8", "unknown_code", "FR");
    assertEquals("HARD_DECLINE", unmappedScenario.outcome());
    assertEquals("999", unmappedScenario.providerCode());
    assertFalse(unmappedScenario.failoverEligible());
  }

  @Test void sandboxRejectsAMissingIntentAndUnknownProvider() {
    assertThrows(IllegalArgumentException.class, () -> MockProviderSandbox.dispatch("adyen", " ", "success", "EU"));
    assertThrows(IllegalArgumentException.class, () -> MockProviderSandbox.dispatch("other", "intent", "success", "EU"));
    assertThrows(IllegalArgumentException.class, () -> MockProviderSandbox.classifyCode("other", "05"));
  }

  @Test void stepUpFixturesClassifyChallengeOutcomes() {
    assertEquals(ScaStepUp.Verdict.AUTHENTICATED, ScaStepUp.verify("sca_ok_demo").verdict());
    assertEquals("FRICTIONLESS", ScaStepUp.verify("sca_ok_demo").flow());
    assertEquals("CHALLENGE", ScaStepUp.verify("sca_challenge_ok").flow());
    assertEquals(ScaStepUp.Verdict.CHALLENGE_FAILED, ScaStepUp.verify("sca_challenge_fail").verdict());
    assertEquals(ScaStepUp.Verdict.EXPIRED, ScaStepUp.verify("sca_expired").verdict());
    assertEquals(ScaStepUp.Verdict.MALFORMED, ScaStepUp.verify("sca_not_a_fixture").verdict());
    assertEquals(ScaStepUp.Verdict.MALFORMED, ScaStepUp.verify("4111111111111111").verdict());
    assertEquals(ScaStepUp.Verdict.MALFORMED, ScaStepUp.verify("").verdict());
  }
}
