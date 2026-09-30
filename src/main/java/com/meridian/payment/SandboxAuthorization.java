package com.meridian.payment;

/**
 * In-process sandbox result. {@code captured} is true only for a confirmed settlement.
 * Ambiguous results are not declines and are not eligible for another provider.
 */
public record SandboxAuthorization(
  String providerId,
  String outcome,
  String providerCode,
  String declineClass,
  boolean failoverEligible,
  boolean captured
) {
  public static SandboxAuthorization ambiguous(String providerId) {
    return new SandboxAuthorization(providerId, "AMBIGUOUS", "AMBIGUOUS", "NONE", false, false);
  }
}
