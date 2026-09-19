package com.meridian.payment;
final class Simulator {
  static PaymentProvider.Outcome outcome(String id,String scenario) {
    if(id==null || id.isBlank()) throw new IllegalArgumentException("Persisted intent required");
    return switch(scenario) {
      case "success" -> PaymentProvider.Outcome.SUCCESS;
      case "declined" -> PaymentProvider.Outcome.DECLINED;
      case "unavailable" -> PaymentProvider.Outcome.UNAVAILABLE;
      case "pending" -> PaymentProvider.Outcome.PENDING;
      default -> throw new IllegalArgumentException("Unknown scenario");
    };
  }
}
