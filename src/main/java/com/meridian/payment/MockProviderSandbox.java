package com.meridian.payment;

/**
 * In-process stand-in for a provider sandbox. It never opens a socket.
 * Unknown decline codes are hard. Soft declines are marked failover-eligible and are not captured.
 */
public final class MockProviderSandbox {
  private MockProviderSandbox() {}

  public static SandboxAuthorization dispatch(String providerId, String persistedPaymentIntentId, String scenario, String corridor) {
    if (persistedPaymentIntentId == null || persistedPaymentIntentId.isBlank()) {
      throw new IllegalArgumentException("Persisted intent required");
    }
    if (corridor == null || corridor.isBlank()) throw new IllegalArgumentException("Corridor required");
    var spec = spec(providerId);
    return switch (scenario) {
      case "success" -> settled(providerId, spec.settledCode());
      case "soft_decline" -> classifyCode(providerId, spec.softScenarioCode());
      case "hard_decline" -> classifyCode(providerId, spec.hardScenarioCode());
      case "unknown_code" -> classifyCode(providerId, spec.unknownScenarioCode());
      case "ambiguous" -> SandboxAuthorization.ambiguous(providerId);
      default -> throw new IllegalArgumentException("Unknown scenario");
    };
  }

  /** Explicit provider code map. Codes missing from both lists are hard, never soft. */
  public static SandboxAuthorization classifyCode(String providerId, String code) {
    var spec = spec(providerId);
    if (code == null || code.isBlank()) return classified(providerId, "UNKNOWN", "HARD", false);
    if (spec.softCodes().contains(code)) return classified(providerId, code, "SOFT", true);
    return classified(providerId, code, "HARD", false);
  }

  private static SandboxCatalog.ProviderSpec spec(String providerId) {
    var spec = SandboxCatalog.get().provider(providerId);
    if (spec == null) throw new IllegalArgumentException("Unknown provider");
    return spec;
  }

  private static SandboxAuthorization settled(String providerId, String code) {
    return new SandboxAuthorization(providerId, "SETTLED", code, "NONE", false, true);
  }

  private static SandboxAuthorization classified(String providerId, String code, String declineClass, boolean failoverEligible) {
    String outcome = "SOFT".equals(declineClass) ? "SOFT_DECLINE" : "HARD_DECLINE";
    return new SandboxAuthorization(providerId, outcome, code, declineClass, failoverEligible, false);
  }
}
