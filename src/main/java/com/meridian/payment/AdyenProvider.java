package com.meridian.payment;
import org.springframework.stereotype.Component;
@Component
public class AdyenProvider implements PaymentProvider {
  public String getProviderId() { return "adyen"; }
  public Outcome authorize(String id,String scenario,String corridor) { return Simulator.outcome(id,scenario); }
}
