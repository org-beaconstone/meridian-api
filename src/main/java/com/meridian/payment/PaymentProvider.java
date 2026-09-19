package com.meridian.payment;
/** Simulation boundary only. No provider credentials or actual external requests. */
public interface PaymentProvider {
  enum Outcome { SUCCESS, DECLINED, UNAVAILABLE, PENDING }
  String getProviderId();
  Outcome authorize(String persistedPaymentIntentId, String scenario, String corridor);
}
