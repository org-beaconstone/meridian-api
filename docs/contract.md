# Connected rehearsal contract v1

All amounts are integer GBP pence. No real provider calls. Base path `/api/v1`. JSON UTF-8. Server state is authoritative and scoped by `X-Rehearsal-Session` (3-64 URL-safe ASCII letters/digits/underscore/hyphen). Session IDs isolate fictional rehearsal state, not authentication. Default clients use a user-visible `meridian-rehearsal` room. Missing/invalid session on stateful endpoints returns 400. Health/catalog do not require session.

## Endpoints

- `GET /health`: `{status:"UP",service:"meridian-api",simulation:true}`.
- `GET /catalog`: `{demoDate:"2026-09-18",recipients:Recipient[],providers:Provider[]}`.
- `GET /state`: exactly BankState below.
- `POST /payments`, header `Idempotency-Key`: 1-100 safe characters. Body `{recipientId,amountMinor,method,note,scenario,corridor}`. Method `card|bank`; scenario `success|declined|unavailable|pending` (pending for webhook exercise). Note defaults empty max200. amountMinor 1..1000000 integer and <=balance. `corridor` is optional `UK|US|EU` and defaults to `UK`. Amounts stay integer GBP pence on every corridor, including EU. The EU corridor is a simulated rail on Adyen and Worldpay, not a third provider and not a euro ledger.
- Payment success: `{ok:true,state:BankState,transaction:Transaction}` HTTP200. Failure `{ok:false,error:string,code:string}` HTTP400 validation,409 idempotency mismatch,422 decline,503 unavailable. A pending result is HTTP202 `{ok:false,error:"Payment pending confirmation",code:"PAYMENT_PENDING",paymentId:string}` and does not debit; webhook completion debits once. Clients must not report pending as completed or retry using a new key. An open circuit returns HTTP503 `{ok:false,code:"PROVIDER_DEGRADED",provider,corridor,eurOptionsEnabled}`. EU responses also include `fallbackCorridor:"UK"`. No debit is made and the other provider is not called. Same key can retry after the lane closes. `GET /corridors` needs no session and reports simulated lane state. `eurOptionsEnabled` is false when either EU lane is open so clients hide EUR options; a provider whose own EU lane is still closed still accepts an explicit EU submission.
- Repeated successful payment key and same normalized payload returns same transaction and CURRENT state, no second debit. Reused key different recipient/amount/method/note returns409. Scenario is a simulation control, not part of business payload. Failed transient simulation can retry with same key. Per-session writes are atomic.
- `PATCH /budgets` body `{category,limitMinor}` positive <=1000000 integer, known category. Returns `{ok:true,state}`.
- `POST /reset` resets that session and returns `{ok:true,state}`.
- `GET /events` returns `{events:AuditEvent[]}` latest first for the session; no sensitive data.
- `POST /webhooks/{provider}` supports synthetic HMAC-signed events for pending attempts only. Header `X-Webhook-Timestamp` epoch seconds, `X-Meridian-Signature` lowercase SHA256 HMAC hex of `timestamp + '.' + exact raw JSON`. Key from `MERIDIAN_WEBHOOK_SECRET`, disabled when absent. Body `{sessionId,eventId,paymentId,status}`; status completed/declined. Max5minute skew, event replay dedup, provider must match stored attempt, no payment creation from unsolicited callbacks. Returns `{ok:true,duplicate:boolean}`. This is a Meridian simulation signature, not an Adyen/Worldpay vendor protocol.

## BankState (same web baseline)

`{version:1,balance:number,transactions:Transaction[],budgets:Budget[]}`.
Transaction `{id,reference,recipientId,name,category,amount,date,provider,method,status,note}`. Positive outgoing amount; date YYYY-MM-DD. provider adyen/worldpay. method card/bank. status completed/declined. Budget `{category,limit}`. Categories Shopping, Food & drink, Transport, Bills, Lifestyle. Fixture must exactly match existing web `createInitialState()`, recipients and provider registry (only Adyen/card and Worldpay/bank). Fixed rehearsal date 2026-09-18.

## Observability

`GET /actuator/prometheus` exposes rehearsal series. `payment_submissions_total`, `sca_step_up_success_rate` and `circuit_breaker_state` carry `corridor` and `provider` labels (`adyen|worldpay`, `UK|US|EU`). Breaker state is 0 closed, 1 open, 2 half-open. A lane opens when provider errors exceed 5% across at least 20 attempts in a rolling minute. Declines are not errors. The `unavailable` scenario counts as a timeout. SCA success rate is a rolling 5-minute gauge. Alert rules in `ops/prometheus/alerts.yml` fire when SCA verification stays below 92% or provider timeouts exceed 1.5% over 5 minutes. `ops/grafana/meridian-sepa-slo.json` charts 99.95% API availability and p95 authorization latency against the 800ms SEPA Instant SLO. No series implies a live vendor integration.

## Deployment topology

Java API serves `/api/v1`. Vite dev servers and production Nginx proxies expose same-origin `/api/v1` to clients. Web uses `VITE_API_BASE_URL=/api/v1` in connected builds; unset means existing explicitly labelled standalone simulation. Browser mobile companion lives in meridian-mobile/preview and calls the same API; it is not the native build. Native Swift/Kotlin clients use configurable base URL and shared session. Do not silently switch from server mode to local success on errors.
