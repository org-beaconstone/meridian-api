package com.meridian.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
  "spring.datasource.url=jdbc:h2:mem:auth-http;DB_CLOSE_DELAY=-1",
  "spring.h2.console.enabled=false"
})
class PaymentAuthTest {
  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  @Test void successDebitsOnceAndReplayKeepsTheTrace() throws Exception {
    String body = auth("EU", "card", "success", "sca_ok_demo", 2599);
    MvcResult first = mvc.perform(post("/api/v1/payments/auth")
        .header("X-Rehearsal-Session", "http-auth-ok")
        .header("Idempotency-Key", "auth-ok")
        .header("X-Trace-Id", "trace-http-ok")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.ok").value(true))
      .andExpect(jsonPath("$.status").value("SUCCEEDED"))
      .andExpect(jsonPath("$.code").value("AUTHORIZED"))
      .andExpect(jsonPath("$.provider").value("adyen"))
      .andExpect(jsonPath("$.providerCode").value("SANDBOX_00"))
      .andExpect(jsonPath("$.failoverAttempted").value(false))
      .andExpect(jsonPath("$.simulation").value(true))
      .andExpect(jsonPath("$.transaction.amount").value(2599))
      .andReturn();
    String paymentId = json.readTree(first.getResponse().getContentAsString()).get("paymentId").asText();
    mvc.perform(post("/api/v1/payments/auth")
        .header("X-Rehearsal-Session", "http-auth-ok")
        .header("Idempotency-Key", "auth-ok")
        .header("X-Trace-Id", "trace-http-new")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.paymentId").value(paymentId))
      .andExpect(jsonPath("$.traceId").value("trace-http-ok"));
    mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", "http-auth-ok"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.balance").value(1245451));
    MvcResult events = mvc.perform(get("/api/v1/events").header("X-Rehearsal-Session", "http-auth-ok"))
      .andExpect(status().isOk()).andReturn();
    String eventBody = events.getResponse().getContentAsString();
    assertFalse(eventBody.contains("sca_ok_demo"));
    assertTrue(eventBody.contains("trace-http-ok"));
    assertTrue(eventBody.contains("routing.decided"));
    assertTrue(eventBody.contains("auth.succeeded"));
  }

  @Test void classifiedDeclinesAndAmbiguousReservation() throws Exception {
    mvc.perform(post("/api/v1/payments/auth")
        .header("X-Rehearsal-Session", "http-auth-soft")
        .header("Idempotency-Key", "soft")
        .contentType(MediaType.APPLICATION_JSON)
        .content(auth("DE", "bank", "soft_decline", "sca_challenge_ok", 1500)))
      .andExpect(status().isUnprocessableEntity())
      .andExpect(jsonPath("$.code").value("SOFT_DECLINE"))
      .andExpect(jsonPath("$.status").value("FAILED"))
      .andExpect(jsonPath("$.provider").value("adyen"))
      .andExpect(jsonPath("$.failoverEligible").value(true))
      .andExpect(jsonPath("$.failoverAttempted").value(false));
    mvc.perform(post("/api/v1/payments/auth")
        .header("X-Rehearsal-Session", "http-auth-hard")
        .header("Idempotency-Key", "hard")
        .contentType(MediaType.APPLICATION_JSON)
        .content(auth("UK", "bank", "hard_decline", "sca_ok_demo", 1500)))
      .andExpect(status().isUnprocessableEntity())
      .andExpect(jsonPath("$.code").value("HARD_DECLINE"))
      .andExpect(jsonPath("$.provider").value("worldpay"))
      .andExpect(jsonPath("$.providerCode").value("REFUSED"))
      .andExpect(jsonPath("$.failoverEligible").value(false));
    mvc.perform(post("/api/v1/payments/auth")
        .header("X-Rehearsal-Session", "http-auth-amb")
        .header("Idempotency-Key", "amb")
        .contentType(MediaType.APPLICATION_JSON)
        .content(auth("EU", "card", "ambiguous", "sca_ok_demo", 800000)))
      .andExpect(status().isServiceUnavailable())
      .andExpect(jsonPath("$.code").value("AUTHORIZATION_AMBIGUOUS"))
      .andExpect(jsonPath("$.status").value("PROCESSING"))
      .andExpect(jsonPath("$.failoverAttempted").value(false));
    mvc.perform(post("/api/v1/payments/auth")
        .header("X-Rehearsal-Session", "http-auth-amb")
        .header("Idempotency-Key", "amb-2")
        .contentType(MediaType.APPLICATION_JSON)
        .content(auth("EU", "card", "success", "sca_ok_demo", 800000)))
      .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", "http-auth-amb"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.balance").value(1248050));
  }

  @Test void validationReplayIsolationAndExistingPaymentContract() throws Exception {
    mvc.perform(post("/api/v1/payments/auth")
        .header("X-Rehearsal-Session", "http-auth-bad")
        .header("Idempotency-Key", "bad")
        .contentType(MediaType.APPLICATION_JSON)
        .content(auth("EU", "card", "success", "not-a-token", 1000)))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("INVALID_SCA_TOKEN"));
    mvc.perform(post("/api/v1/payments/auth")
        .header("X-Rehearsal-Session", "http-auth-bad")
        .header("Idempotency-Key", "bad")
        .contentType(MediaType.APPLICATION_JSON)
        .content(auth("EU", "card", "success", "sca_ok_demo", 1000)))
      .andExpect(status().isOk());
    mvc.perform(post("/api/v1/payments/auth")
        .header("X-Rehearsal-Session", "http-auth-bad")
        .header("Idempotency-Key", "bad")
        .contentType(MediaType.APPLICATION_JSON)
        .content(auth("EU", "card", "success", "sca_challenge_ok", 1000)))
      .andExpect(status().isConflict());
    mvc.perform(post("/api/v1/payments/auth")
        .header("X-Rehearsal-Session", "http-auth-val")
        .header("Idempotency-Key", "val")
        .header("X-Trace-Id", "short")
        .contentType(MediaType.APPLICATION_JSON)
        .content(auth("eu", "card", "success", "sca_ok_demo", 1000)))
      .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/payments/auth")
        .header("X-Rehearsal-Session", "http-auth-val")
        .header("Idempotency-Key", "val")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":25.99,\"method\":\"card\",\"corridor\":\"EU\",\"scaToken\":\"sca_ok_demo\"}"))
      .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/payments/auth")
        .header("X-Rehearsal-Session", "http-auth-val")
        .header("Idempotency-Key", "val")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":1000,\"method\":\"card\",\"corridor\":\"EU\",\"scaToken\":\"sca_ok_demo\",\"pan\":\"4111111111111111\"}"))
      .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/payments/auth").header("Idempotency-Key", "missing-room").contentType(MediaType.APPLICATION_JSON).content(auth("UK", "card", "success", "sca_ok_demo", 1000)))
      .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/payments/auth")
        .header("X-Rehearsal-Session", "room-a")
        .header("Idempotency-Key", "shared")
        .contentType(MediaType.APPLICATION_JSON)
        .content(auth("US", "card", "success", "sca_ok_demo", 1000)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.provider").value("adyen"));
    mvc.perform(post("/api/v1/payments/auth")
        .header("X-Rehearsal-Session", "room-b")
        .header("Idempotency-Key", "shared")
        .contentType(MediaType.APPLICATION_JSON)
        .content(auth("US", "card", "success", "sca_ok_demo", 1000)))
      .andExpect(status().isOk());
    mvc.perform(post("/api/v1/payments")
        .header("X-Rehearsal-Session", "http-legacy")
        .header("Idempotency-Key", "legacy-bank")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":1000,\"method\":\"bank\",\"scenario\":\"success\"}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.transaction.provider").value("worldpay"));
    mvc.perform(post("/api/v1/reset").header("X-Rehearsal-Session", "http-auth-bad")).andExpect(status().isOk());
    mvc.perform(post("/api/v1/payments/auth")
        .header("X-Rehearsal-Session", "http-auth-bad")
        .header("Idempotency-Key", "bad")
        .contentType(MediaType.APPLICATION_JSON)
        .content(auth("EU", "card", "success", "sca_challenge_fail", 1000)))
      .andExpect(status().isUnprocessableEntity())
      .andExpect(jsonPath("$.code").value("SCA_CHALLENGE_FAILED"));
    MvcResult state = mvc.perform(get("/api/v1/state").header("X-Rehearsal-Session", "http-auth-bad")).andExpect(status().isOk()).andReturn();
    Map<?, ?> parsed = json.readValue(state.getResponse().getContentAsString(), Map.class);
    assertEquals(1248050, ((Number) parsed.get("balance")).intValue());
    assertEquals(8, ((List<?>) parsed.get("transactions")).size());
  }

  private String auth(String corridor, String method, String scenario, String token, int amount) {
    return "{\"recipientId\":\"northline-studio\",\"amountMinor\":" + amount + ",\"method\":\"" + method
      + "\",\"corridor\":\"" + corridor + "\",\"scenario\":\"" + scenario + "\",\"scaToken\":\"" + token + "\"}";
  }
}
