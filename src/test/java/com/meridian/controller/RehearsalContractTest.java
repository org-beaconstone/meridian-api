package com.meridian.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Locks the connected rehearsal contract for catalog discovery and payment submission.
 *
 * PAY-149 asked these same routes to return EUR rails (SEPA Instant and EUR card),
 * SCA step-up challenges, and to require Bearer JWT plus TLS 1.3. That work stays
 * blocked: European corridors are not cleared (PAY-1204), no European provider is
 * selected (PAY-1187), and the web contract is integer GBP pence with Adyen card
 * and Worldpay bank only. A rehearsal session isolates synthetic data; it is not
 * authentication. HTTP 202 remains the webhook pending result, not an SCA challenge.
 */
@SpringBootTest(properties = "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "meridian.webhook.secret=test-key-webhook-secret",
    "spring.datasource.url=jdbc:h2:mem:contract-tests;DB_CLOSE_DELAY=-1",
    "spring.h2.console.enabled=false"
})
class RehearsalContractTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private Environment environment;

    @Test
    void catalogSerializesFixedRehearsalProviders() throws Exception {
        mvc.perform(get("/api/v1/catalog"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.demoDate").value("2026-09-18"))
            .andExpect(jsonPath("$.recipients.length()").value(5))
            .andExpect(jsonPath("$.providers.length()").value(2))
            .andExpect(jsonPath("$.providers[0].id").value("adyen"))
            .andExpect(jsonPath("$.providers[0].methods.length()").value(1))
            .andExpect(jsonPath("$.providers[0].methods[0]").value("card"))
            .andExpect(jsonPath("$.providers[1].id").value("worldpay"))
            .andExpect(jsonPath("$.providers[1].methods[0]").value("bank"))
            .andExpect(jsonPath("$.rails").doesNotExist())
            .andExpect(jsonPath("$.paymentMethods").doesNotExist())
            .andExpect(jsonPath("$.providers[0].requiresSca").doesNotExist())
            .andExpect(jsonPath("$.providers[0].maxAmountMinor").doesNotExist())
            .andExpect(content().string(not(containsString("SEPA_INSTANT"))))
            .andExpect(content().string(not(containsString("requiresSca"))))
            .andExpect(content().string(not(containsString("EUR"))));
    }

    @Test
    void regionAndCurrencyHeadersLeaveCatalogUnchanged() throws Exception {
        mvc.perform(get("/api/v1/catalog")
                .header("X-Region", "EU")
                .header("X-Currency", "EUR")
                .header("X-Corridor", "EU"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.demoDate").value("2026-09-18"))
            .andExpect(jsonPath("$.providers.length()").value(2))
            .andExpect(jsonPath("$.providers[0].id").value("adyen"))
            .andExpect(jsonPath("$.providers[0].methods[0]").value("card"))
            .andExpect(jsonPath("$.providers[1].id").value("worldpay"))
            .andExpect(jsonPath("$.providers[1].methods[0]").value("bank"))
            .andExpect(content().string(not(containsString("SEPA_INSTANT"))))
            .andExpect(content().string(not(containsString("SCA_STEP_UP_REQUIRED"))))
            .andExpect(content().string(not(containsString("EUR"))));
    }

    @Test
    void catalogAndPaymentDoNotRequireBearerJwt() throws Exception {
        mvc.perform(get("/api/v1/catalog").header("Authorization", "Bearer not-a-jwt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.providers.length()").value(2));

        mvc.perform(post("/api/v1/payments")
                .header("X-Rehearsal-Session", "contract-no-auth")
                .header("Idempotency-Key", "idem-no-bearer")
                .header("Authorization", "Bearer not-a-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":1000,\"method\":\"card\",\"scenario\":\"success\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.transaction.provider").value("adyen"))
            .andExpect(jsonPath("$.transaction.method").value("card"))
            .andExpect(content().string(not(containsString("SCA_STEP_UP_REQUIRED"))));

        assertNotEquals("true", environment.getProperty("server.ssl.enabled"));
    }

    @Test
    void paymentRequiresIdempotencyKeyWithoutForcingUuid() throws Exception {
        mvc.perform(post("/api/v1/payments")
                .header("X-Rehearsal-Session", "contract-missing-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":1000,\"method\":\"card\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.code").value("HTTP_400"))
            .andExpect(jsonPath("$.error").value("Invalid Idempotency-Key"));

        mvc.perform(post("/api/v1/payments")
                .header("X-Rehearsal-Session", "contract-plain-key")
                .header("Idempotency-Key", "idem-not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":1200,\"method\":\"bank\",\"scenario\":\"success\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.transaction.provider").value("worldpay"))
            .andExpect(jsonPath("$.transaction.amount").value(1200));
    }

    @Test
    void currencyFieldIsRejectedRatherThanInterpreted() throws Exception {
        for (String currency : new String[] {"EUR", "GBP", "eu"}) {
            mvc.perform(post("/api/v1/payments")
                    .header("X-Rehearsal-Session", "contract-currency-" + currency)
                    .header("Idempotency-Key", "idem-currency-" + currency)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":1000,\"method\":\"card\",\"currency\":\"" + currency + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_JSON"))
                .andExpect(jsonPath("$.error").value("Invalid JSON request: integer amounts and known fields required"))
                .andExpect(content().string(not(containsString("SCA_STEP_UP_REQUIRED"))))
                .andExpect(content().string(not(containsString("PENDING_AUTHENTICATION"))));
        }
    }

    @Test
    void europeanRailsAreUnknownMethods() throws Exception {
        for (String method : new String[] {"SEPA_INSTANT", "CARD", "sepa"}) {
            mvc.perform(post("/api/v1/payments")
                    .header("X-Rehearsal-Session", "contract-method")
                    .header("Idempotency-Key", "idem-method-" + method)
                    .header("X-Currency", "EUR")
                    .header("X-Region", "EU")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":1000,\"method\":\"" + method + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value("HTTP_400"))
                .andExpect(jsonPath("$.error").value("Unknown payment method"))
                .andExpect(content().string(not(containsString("BIOMETRIC_OR_PIN"))))
                .andExpect(content().string(not(containsString("PENDING_AUTHENTICATION"))));
        }
    }

    @Test
    void acceptedPaymentIsWebhookPendingNotScaStepUp() throws Exception {
        mvc.perform(post("/api/v1/payments")
                .header("X-Rehearsal-Session", "contract-pending")
                .header("Idempotency-Key", "11111111-1111-1111-1111-111111111111")
                .header("X-Currency", "EUR")
                .header("X-Region", "EU")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\":\"birch-bloom\",\"amountMinor\":2000,\"method\":\"card\",\"scenario\":\"pending\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.code").value("PAYMENT_PENDING"))
            .andExpect(jsonPath("$.error").value("Payment pending confirmation. Do not create another payment."))
            .andExpect(jsonPath("$.paymentId").exists())
            .andExpect(jsonPath("$.status").doesNotExist())
            .andExpect(jsonPath("$.challengeType").doesNotExist())
            .andExpect(content().string(not(containsString("SCA_STEP_UP_REQUIRED"))))
            .andExpect(content().string(not(containsString("PENDING_AUTHENTICATION"))))
            .andExpect(content().string(not(containsString("BIOMETRIC_OR_PIN"))));
    }
}
