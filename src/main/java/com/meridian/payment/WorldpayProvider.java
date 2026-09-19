package com.meridian.payment;
import org.springframework.stereotype.Component;
@Component
public class WorldpayProvider implements PaymentProvider {
  public String getProviderId() { return "worldpay"; }
  public Outcome authorize(String id,String scenario,String corridor) { return Simulator.outcome(id,scenario); }
}
