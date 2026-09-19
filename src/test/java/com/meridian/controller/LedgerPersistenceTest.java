package com.meridian.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.service.*;
import com.meridian.payment.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class LedgerPersistenceTest {
  private RehearsalBank bank(String url,String secret) {
    var ds=new DriverManagerDataSource(url,"sa","");var mapper=new ObjectMapper();
    return new RehearsalBank(new JdbcTemplate(ds),new TransactionTemplate(new DataSourceTransactionManager(ds)),mapper,new FixtureService(mapper),List.of(new AdyenProvider(),new WorldpayProvider()),secret);
  }
  @Test void preservesLedgerAndIdempotencyAfterReopeningFileDatabase() throws Exception {
    var dir=Files.createTempDirectory(Path.of("target"),"tmp_rovo_persist_");String url="jdbc:h2:file:"+dir.resolve("bank").toAbsolutePath();
    var original=bank(url,"");var draft=new RehearsalBank.Payment("northline-studio",2599L,"card","same","success");
    assertEquals(true,original.payment("persist-room","same-key",draft).get("ok"));
    var reopened=bank(url,"");assertEquals(1245451,reopened.state("persist-room").getBalance());
    assertEquals(true,reopened.payment("persist-room","same-key",draft).get("ok"));assertEquals(1245451,reopened.state("persist-room").getBalance());
    Files.deleteIfExists(dir.resolve("bank.mv.db")); Files.deleteIfExists(dir.resolve("bank.trace.db"));Files.delete(dir);
  }
  @Test void retriesKnownUnavailableUsingSameKeyAndKeepsOneDebit() {
    var bank=bank("jdbc:h2:mem:retry-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","");
    assertEquals("PROVIDER_UNAVAILABLE",bank.payment("retry-room","same-key",new RehearsalBank.Payment("northline-studio",200L,"card","same","unavailable")).get("code"));
    assertEquals(true,bank.payment("retry-room","same-key",new RehearsalBank.Payment("northline-studio",200L,"card","same","success")).get("ok"));
    assertEquals(1247850,bank.state("retry-room").getBalance());
  }
  @Test void rejectsAllCallbacksWhenNoSecretConfigured() {
    var bank=bank("jdbc:h2:mem:disabled-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","");
    var error=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->bank.webhook("adyen","0","0","{}"));assertEquals(403,error.getStatusCode().value());
  }
  @Test void reservesPendingFundsBeforeAnotherPayment() {
    var bank=bank("jdbc:h2:mem:reserve-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","");
    assertEquals("PAYMENT_PENDING",bank.payment("reserve-room","pending",new RehearsalBank.Payment("northline-studio",800000L,"card","","pending")).get("code"));
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->bank.payment("reserve-room","second",new RehearsalBank.Payment("northline-studio",800000L,"card","","success")));
    assertEquals(1248050,bank.state("reserve-room").getBalance());
  }
}
