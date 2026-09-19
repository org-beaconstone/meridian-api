package com.meridian.controller;
import com.meridian.domain.*;
import com.meridian.service.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.*;
@RestController
@RequestMapping("/api/v1")
public class ApiController {
  private final RehearsalBank bank; private final FixtureService fixture;
  public ApiController(RehearsalBank bank,FixtureService fixture) { this.bank=bank; this.fixture=fixture; }
  @GetMapping("/health") public Object health() { return Map.of("status","UP","service","meridian-api","simulation",true); }
  @GetMapping("/catalog") public Object catalog() { return new CatalogResponse(fixture.getDemoDate(),fixture.getRecipients(),fixture.getProviders()); }
  @GetMapping("/state") public BankState state(@RequestHeader(value="X-Rehearsal-Session",required=false) String room) { return bank.state(room); }
  @PostMapping("/reset") public Object reset(@RequestHeader(value="X-Rehearsal-Session",required=false) String room) { return bank.reset(room); }
  @PatchMapping("/budgets") public Object budget(@RequestHeader(value="X-Rehearsal-Session",required=false) String room,@RequestBody RehearsalBank.Limit body) { return bank.budget(room,body); }
  @GetMapping("/events") public Object events(@RequestHeader(value="X-Rehearsal-Session",required=false) String room) { return bank.events(room); }
  @PostMapping("/payments") public ResponseEntity<?> payment(@RequestHeader(value="X-Rehearsal-Session",required=false) String room,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody RehearsalBank.Payment body) {
    var result=bank.payment(room,key,body); String code=(String)result.get("code");
    int status=code==null?200:switch(code) { case "PAYMENT_PENDING"->202; case "PAYMENT_DECLINED"->422; case "PROVIDER_UNAVAILABLE"->503; default->400; };
    return ResponseEntity.status(status).body(result);
  }
  @PostMapping("/webhooks/{provider}") public Object webhook(@PathVariable String provider,@RequestHeader(value="X-Webhook-Timestamp",required=false) String timestamp,@RequestHeader(value="X-Meridian-Signature",required=false) String signature,@RequestBody String raw) { return bank.webhook(provider,timestamp,signature,raw); }
  @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> domainError(ResponseStatusException e) { return ResponseEntity.status(e.getStatusCode()).body(Map.of("ok",false,"error",Objects.requireNonNullElse(e.getReason(),"Request failed"),"code","HTTP_"+e.getStatusCode().value())); }
  @ExceptionHandler(HttpMessageNotReadableException.class) public ResponseEntity<?> jsonError() { return ResponseEntity.badRequest().body(Map.of("ok",false,"error","Invalid JSON request: integer amounts and known fields required","code","INVALID_JSON")); }
}
