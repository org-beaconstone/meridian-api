package com.meridian.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.service.*;
import com.meridian.payment.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class LedgerConcurrencyTest {
  private RehearsalBank bank(){var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");var json=new ObjectMapper();return new RehearsalBank(new JdbcTemplate(ds),new TransactionTemplate(new DataSourceTransactionManager(ds)),json,new FixtureService(json),List.of(new AdyenProvider(),new WorldpayProvider()),"");}
  @Test void exactlyOneOverspendingConcurrentPaymentSucceeds() throws Exception {
    var bank=bank();try(var pool=Executors.newFixedThreadPool(2)){
      var gate=new CountDownLatch(1);List<Future<Boolean>> results=new ArrayList<>();
      for(int i=0;i<2;i++){String key="pay-"+i;results.add(pool.submit(()->{gate.await();try{return Boolean.TRUE.equals(bank.payment("race-room",key,new RehearsalBank.Payment("northline-studio",800000L,"card","","success")).get("ok"));}catch(org.springframework.web.server.ResponseStatusException e){assertEquals(400,e.getStatusCode().value());return false;}}));}
      gate.countDown();int succeeded=0;for(var result:results)if(result.get(10,TimeUnit.SECONDS))succeeded++;
      assertEquals(1,succeeded);assertEquals(448050,bank.state("race-room").getBalance());assertEquals(9,bank.state("race-room").getTransactions().size());
    }
  }
  @Test void concurrentBudgetAndPaymentsKeepEveryWrite() throws Exception {
    var bank=bank();try(var pool=Executors.newFixedThreadPool(3)){
      var gate=new CountDownLatch(1);List<Future<?>> jobs=new ArrayList<>();
      jobs.add(pool.submit(()->{await(gate);bank.budget("race-budget",new RehearsalBank.Limit("Shopping",150000L));}));
      for(int i=0;i<2;i++){String key="pay-"+i;jobs.add(pool.submit(()->{await(gate);bank.payment("race-budget",key,new RehearsalBank.Payment("northline-studio",1000L,"card","","success"));}));}
      gate.countDown();for(var job:jobs)job.get(10,TimeUnit.SECONDS);
      var state=bank.state("race-budget");assertEquals(1246050,state.getBalance());assertEquals(10,state.getTransactions().size());assertEquals(150000,state.getBudgets().stream().filter(b->b.getCategory().equals("Shopping")).findFirst().orElseThrow().getLimit());
    }
  }
  private void await(CountDownLatch gate){try{gate.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}
}
