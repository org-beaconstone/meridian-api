package com.meridian.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
  "spring.datasource.url=jdbc:h2:mem:catalog-reconciliation;DB_CLOSE_DELAY=-1",
  "spring.h2.console.enabled=false"
})
class CatalogReconciliationTest {
  @Autowired
  private MockMvc mvc;

  @Test
  void catalogPublishesTheBaselineProviderRegistryOnly() throws Exception {
    mvc.perform(get("/api/v1/catalog"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.demoDate").value("2026-09-18"))
      .andExpect(jsonPath("$.providers.length()").value(2))
      .andExpect(jsonPath("$.providers[0].id").value("adyen"))
      .andExpect(jsonPath("$.providers[0].name").value("Adyen"))
      .andExpect(jsonPath("$.providers[0].description").value("Card payment processor"))
      .andExpect(jsonPath("$.providers[0].methods.length()").value(1))
      .andExpect(jsonPath("$.providers[0].methods[0]").value("card"))
      .andExpect(jsonPath("$.providers[0].corridors").doesNotExist())
      .andExpect(jsonPath("$.providers[1].id").value("worldpay"))
      .andExpect(jsonPath("$.providers[1].name").value("Worldpay"))
      .andExpect(jsonPath("$.providers[1].description").value("Bank transfer processor"))
      .andExpect(jsonPath("$.providers[1].methods[0]").value("bank"))
      .andExpect(jsonPath("$.providers[1].corridors").doesNotExist())
      .andExpect(jsonPath("$.configVersion").doesNotExist())
      .andExpect(content().string(not(containsString("adyen_card"))))
      .andExpect(content().string(not(containsString("worldpay_bank"))));
  }

  @Test
  void cardPaymentIsRoutedFromTheCatalogToAdyen() throws Exception {
    mvc.perform(post("/api/v1/payments")
        .header("X-Rehearsal-Session", "recon-card")
        .header("Idempotency-Key", "recon-card-1")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":2599,\"method\":\"card\",\"scenario\":\"success\"}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.transaction.method").value("card"))
      .andExpect(jsonPath("$.transaction.provider").value("adyen"));
  }

  @Test
  void bankPaymentIsRoutedFromTheCatalogToWorldpay() throws Exception {
    mvc.perform(post("/api/v1/payments")
        .header("X-Rehearsal-Session", "recon-bank")
        .header("Idempotency-Key", "recon-bank-1")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":1000,\"method\":\"bank\",\"scenario\":\"success\"}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.transaction.method").value("bank"))
      .andExpect(jsonPath("$.transaction.provider").value("worldpay"));
  }

  @Test
  void compositeMethodIdsStayOffTheWire() throws Exception {
    mvc.perform(post("/api/v1/payments")
        .header("X-Rehearsal-Session", "recon-composite")
        .header("Idempotency-Key", "recon-composite-1")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":1000,\"method\":\"adyen_card\",\"scenario\":\"success\"}"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.error").value("Unknown payment method"));

    mvc.perform(post("/api/v1/payments")
        .header("X-Rehearsal-Session", "recon-composite")
        .header("Idempotency-Key", "recon-composite-2")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":1000,\"method\":\"worldpay_bank\",\"scenario\":\"success\"}"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.error").value("Unknown payment method"));
  }

  @Test
  void changingTheWireMethodReusesNoIdempotencyKey() throws Exception {
    String body = "{\"recipientId\":\"birch-bloom\",\"amountMinor\":1500,\"method\":\"card\",\"note\":\"same\",\"scenario\":\"success\"}";
    mvc.perform(post("/api/v1/payments")
        .header("X-Rehearsal-Session", "recon-idem")
        .header("Idempotency-Key", "recon-idem-1")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body))
      .andExpect(status().isOk());

    mvc.perform(post("/api/v1/payments")
        .header("X-Rehearsal-Session", "recon-idem")
        .header("Idempotency-Key", "recon-idem-1")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":1500,\"method\":\"bank\",\"note\":\"same\",\"scenario\":\"success\"}"))
      .andExpect(status().isConflict());
  }

  @Test
  void providerConfigIsNotASeparateEndpoint() throws Exception {
    mvc.perform(get("/api/v1/config/payment-providers").header("X-Rehearsal-Session", "recon-config"))
      .andExpect(status().isNotFound());
  }
}
