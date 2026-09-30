package com.meridian.payment;

/** Simulation boundary only. No provider credentials or actual external requests. */
public interface PaymentProvider {
  enum Outcome { SUCCESS, DECLINED, UNAVAILABLE, PENDING }

  String getProviderId();

  Outcome authorize(String persistedPaymentIntentId, String scenario, String corridor);

  /**
   * Mock-sandbox step-up authorization. Must not contact a remote gateway.
   * Unknown decline codes are hard. An ambiguous result is not a decline.
   */
  SandboxAuthorization authorizeSandbox(String persistedPaymentIntentId, String scenario, String corridor);
}
