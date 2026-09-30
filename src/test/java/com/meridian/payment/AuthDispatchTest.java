package com.meridian.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.service.FixtureService;
import com.meridian.service.RehearsalBank;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AuthDispatchTest {
  private static final class CountingProvider implements PaymentProvider {
    private final String id;
    private int sandboxCalls;

    private CountingProvider(String id) { this.id = id; }

    public String getProviderId() { return id; }

    public Outcome authorize(String intentId, String scenario, String corridor) {
      return switch (scenario) {
        case "success" -> Outcome.SUCCESS;
        case "declined" -> Outcome.DECLINED;
        case "unavailable" -> Outcome.UNAVAILABLE;
        case "pending" -> Outcome.PENDING;
        default -> throw new IllegalArgumentException("Unknown scenario");
      };
    }

    public SandboxAuthorization authorizeSandbox(String intentId, String scenario, String corridor) {
      sandboxCalls++;
      return MockProviderSandbox.dispatch(id, intentId, scenario, corridor);
    }
  }

  private RehearsalBank bank(CountingProvider adyen, CountingProvider worldpay) {
    var ds = new DriverManagerDataSource("jdbc:h2:mem:auth-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    var json = new ObjectMapper();
    return new RehearsalBank(new JdbcTemplate(ds), new TransactionTemplate(new DataSourceTransactionManager(ds)), json, new FixtureService(json), List.of(adyen, worldpay), "");
  }

  private RehearsalBank.Authorization auth(String corridor, String method, String scenario, String token) {
    return new RehearsalBank.Authorization("northline-studio", 2500L, method, "sandbox", corridor, scenario, token);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> events(RehearsalBank bank, String room) {
    return (List<Map<String, Object>>) bank.events(room).get("events");
  }

  @Test void europeanSoftDeclineStaysOnAdyenAndDoesNotDebit() {
    var adyen = new CountingProvider("adyen");
    var worldpay = new CountingProvider("worldpay");
    var bank = bank(adyen, worldpay);
    var result = bank.authorize("soft-room", "soft-key", "trace-soft-1", auth("EU", "bank", "soft_decline", "sca_ok_demo"));
    assertEquals("SOFT_DECLINE", result.get("code"));
    assertEquals("FAILED", result.get("status"));
    assertEquals("adyen", result.get("provider"));
    assertEquals("51", result.get("providerCode"));
    assertEquals(true, result.get("failoverEligible"));
    assertEquals(false, result.get("failoverAttempted"));
    assertEquals(1248050, bank.state("soft-room").getBalance());
    assertEquals(1, adyen.sandboxCalls);
    assertEquals(0, worldpay.sandboxCalls);
    var replay = bank.authorize("soft-room", "soft-key", "trace-other-value", auth("EU", "bank", "success", "sca_ok_demo"));
    assertEquals("SOFT_DECLINE", replay.get("code"));
    assertEquals("trace-soft-1", replay.get("traceId"));
    assertEquals(1, adyen.sandboxCalls);
    assertEquals(0, worldpay.sandboxCalls);
    var types = events(bank, "soft-room").stream().map(event -> event.get("type")).toList();
    assertTrue(types.containsAll(List.of("sca.authenticated", "routing.decided", "auth.processing", "auth.failed", "routing.failover_withheld")));
    assertTrue(events(bank, "soft-room").stream().allMatch(event -> "trace-soft-1".equals(event.get("traceId"))));
    assertTrue(events(bank, "soft-room").stream().noneMatch(event -> event.toString().contains("sca_ok_demo")));
  }

  @Test void ambiguousOutcomeReservesFundsAndNeverCallsThePairedProvider() {
    var adyen = new CountingProvider("adyen");
    var worldpay = new CountingProvider("worldpay");
    var bank = bank(adyen, worldpay);
    var body = new RehearsalBank.Authorization("northline-studio", 800000L, "card", "", "DE", "ambiguous", "sca_challenge_ok");
    var first = bank.authorize("amb-room", "amb-key", "trace-amb-11", body);
    assertEquals("AUTHORIZATION_AMBIGUOUS", first.get("code"));
    assertEquals("PROCESSING", first.get("status"));
    assertEquals(false, first.get("failoverEligible"));
    assertEquals(false, first.get("failoverAttempted"));
    assertEquals(1248050, bank.state("amb-room").getBalance());
    var second = bank.authorize("amb-room", "amb-key", "trace-amb-22", body);
    assertEquals(first.get("paymentId"), second.get("paymentId"));
    assertEquals("trace-amb-11", second.get("traceId"));
    assertEquals(1, adyen.sandboxCalls);
    assertEquals(0, worldpay.sandboxCalls);
    var blocked = assertThrows(ResponseStatusException.class, () -> bank.authorize("amb-room", "other-key", "trace-amb-33", body));
    assertEquals(400, blocked.getStatusCode().value());
    assertEquals(1, adyen.sandboxCalls);
  }

  @Test void hardDeclineIsTerminalAndUnknownCodeIsHard() {
    var adyen = new CountingProvider("adyen");
    var worldpay = new CountingProvider("worldpay");
    var bank = bank(adyen, worldpay);
    var hard = bank.authorize("hard-room", "hard-key", "trace-hard-1", auth("UK", "card", "hard_decline", "sca_ok_demo"));
    assertEquals("HARD_DECLINE", hard.get("code"));
    assertEquals("05", hard.get("providerCode"));
    assertEquals(false, hard.get("failoverEligible"));
    assertEquals("HARD_DECLINE", bank.authorize("hard-room", "hard-key", "trace-hard-1", auth("UK", "card", "success", "sca_ok_demo")).get("code"));
    assertEquals(1, adyen.sandboxCalls);
    var unknown = bank.authorize("hard-room", "unknown-key", "trace-hard-2", auth("US", "bank", "unknown_code", "sca_ok_demo"));
    assertEquals("HARD_DECLINE", unknown.get("code"));
    assertEquals("worldpay", unknown.get("provider"));
    assertEquals("UNMAPPED", unknown.get("providerCode"));
    assertEquals(false, unknown.get("failoverEligible"));
    assertEquals(1, worldpay.sandboxCalls);
    assertEquals(1248050, bank.state("hard-room").getBalance());
  }

  @Test void successfulReplayDebitsOnceAndDomesticBankUsesWorldpay() {
    var adyen = new CountingProvider("adyen");
    var worldpay = new CountingProvider("worldpay");
    var bank = bank(adyen, worldpay);
    var first = bank.authorize("ok-room", "ok-key", "trace-ok-123", auth("UK", "bank", "success", "sca_ok_demo"));
    assertEquals(true, first.get("ok"));
    assertEquals("SUCCEEDED", first.get("status"));
    assertEquals("worldpay", first.get("provider"));
    assertEquals("SANDBOX_AUTHORISED", first.get("providerCode"));
    assertEquals(1245550, bank.state("ok-room").getBalance());
    var second = bank.authorize("ok-room", "ok-key", null, auth("UK", "bank", "soft_decline", "sca_ok_demo"));
    assertEquals(first.get("paymentId"), second.get("paymentId"));
    assertEquals("trace-ok-123", second.get("traceId"));
    assertEquals(1245550, bank.state("ok-room").getBalance());
    assertEquals(0, adyen.sandboxCalls);
    assertEquals(1, worldpay.sandboxCalls);
    var mismatch = assertThrows(ResponseStatusException.class, () -> bank.authorize("ok-room", "ok-key", "trace-ok-123", new RehearsalBank.Authorization("birch-bloom", 2500L, "bank", "sandbox", "UK", "success", "sca_ok_demo")));
    assertEquals(409, mismatch.getStatusCode().value());
  }

  @Test void failedChallengeDoesNotCallAProviderAndInvalidTokenCanBeReplaced() {
    var adyen = new CountingProvider("adyen");
    var worldpay = new CountingProvider("worldpay");
    var bank = bank(adyen, worldpay);
    var failed = bank.authorize("sca-room", "sca-key", "trace-sca-11", auth("EU", "card", "success", "sca_challenge_fail"));
    assertEquals("SCA_CHALLENGE_FAILED", failed.get("code"));
    assertEquals("FAILED", failed.get("status"));
    assertEquals(0, adyen.sandboxCalls);
    assertEquals(0, worldpay.sandboxCalls);
    assertEquals(1248050, bank.state("sca-room").getBalance());
    assertEquals("SCA_CHALLENGE_FAILED", bank.authorize("sca-room", "sca-key", "trace-sca-11", auth("EU", "card", "success", "sca_challenge_fail")).get("code"));
    var replaced = assertThrows(ResponseStatusException.class, () -> bank.authorize("sca-room", "sca-key", "trace-sca-11", auth("EU", "card", "success", "sca_ok_demo")));
    assertEquals(409, replaced.getStatusCode().value());
    assertEquals("INVALID_SCA_TOKEN", bank.authorize("sca-room", "fresh-key", "trace-sca-22", auth("EU", "card", "success", "sca_not_a_fixture")).get("code"));
    assertEquals("SCA_TOKEN_EXPIRED", bank.authorize("sca-room", "fresh-key", "trace-sca-22", auth("EU", "card", "success", "sca_expired")).get("code"));
    var recovered = bank.authorize("sca-room", "fresh-key", "trace-sca-22", auth("FR", "card", "success", "sca_challenge_ok"));
    assertEquals(true, recovered.get("ok"));
    assertEquals("adyen", recovered.get("provider"));
    assertEquals(1, adyen.sandboxCalls);
    assertTrue(events(bank, "sca-room").stream().noneMatch(event -> String.valueOf(event).contains("sca_challenge_ok") || String.valueOf(event).contains("sca_expired")));
  }

  @Test void exactlyOneOverspendingAuthorizationSucceedsAndPaymentSharesTheReservation() throws Exception {
    var adyen = new CountingProvider("adyen");
    var worldpay = new CountingProvider("worldpay");
    var bank = bank(adyen, worldpay);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        String key = "auth-" + i;
        results.add(pool.submit(() -> {
          gate.await();
          try {
            return Boolean.TRUE.equals(bank.authorize("race-room", key, "trace-race-" + key, new RehearsalBank.Authorization("northline-studio", 800000L, "card", "", "EU", "success", "sca_ok_demo")).get("ok"));
          } catch (ResponseStatusException e) {
            assertEquals(400, e.getStatusCode().value());
            return false;
          }
        }));
      }
      gate.countDown();
      int succeeded = 0;
      for (var result : results) if (result.get(10, TimeUnit.SECONDS)) succeeded++;
      assertEquals(1, succeeded);
      assertEquals(448050, bank.state("race-room").getBalance());
      assertEquals(9, bank.state("race-room").getTransactions().size());
    }
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      List<Future<Boolean>> results = new ArrayList<>();
      results.add(pool.submit(() -> {
        gate.await();
        try {
          return Boolean.TRUE.equals(bank.authorize("shared-room", "auth-key", "trace-shared-1", new RehearsalBank.Authorization("northline-studio", 800000L, "card", "", "UK", "success", "sca_ok_demo")).get("ok"));
        } catch (ResponseStatusException e) {
          return false;
        }
      }));
      results.add(pool.submit(() -> {
        gate.await();
        try {
          return Boolean.TRUE.equals(bank.payment("shared-room", "pay-key", new RehearsalBank.Payment("northline-studio", 800000L, "bank", "", "success")).get("ok"));
        } catch (ResponseStatusException e) {
          return false;
        }
      }));
      gate.countDown();
      int succeeded = 0;
      for (var result : results) if (result.get(10, TimeUnit.SECONDS)) succeeded++;
      assertEquals(1, succeeded);
      assertEquals(448050, bank.state("shared-room").getBalance());
    }
  }
}
