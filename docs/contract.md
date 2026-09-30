# Connected rehearsal contract v1

All amounts are integer GBP pence. No real provider calls. Base path `/api/v1`. JSON UTF-8. Server state is authoritative and scoped by `X-Rehearsal-Session` (3-64 URL-safe ASCII letters/digits/underscore/hyphen). Session IDs isolate fictional rehearsal state, not authentication. Default clients use a user-visible `meridian-rehearsal` room. Missing/invalid session on stateful endpoints returns 400. Health/catalog do not require session.

## Endpoints

- `GET /health`: `{status:"UP",service:"meridian-api",simulation:true}`.
- `GET /catalog`: `{demoDate:"2026-09-18",recipients:Recipient[],providers:Provider[]}`.
- `GET /state`: exactly BankState below.
- `POST /payments`, header `Idempotency-Key`: 1-100 safe characters. Body `{recipientId,amountMinor,method,note,scenario}`. Method `card|bank`; scenario `success|declined|unavailable|pending` (pending for webhook exercise). Note defaults empty max200. amountMinor 1..1000000 integer and <=balance.
- Payment success: `{ok:true,state:BankState,transaction:Transaction}` HTTP200. Failure `{ok:false,error:string,code:string}` HTTP400 validation,409 idempotency mismatch,422 decline,503 unavailable. A pending result is HTTP202 `{ok:false,error:"Payment pending confirmation",code:"PAYMENT_PENDING",paymentId:string}` and does not debit; webhook completion debits once. Clients must not report pending as completed or retry using a new key.
- `POST /payments/auth`, headers `Idempotency-Key` and optional `X-Trace-Id` (`[A-Za-z0-9_-]{8,64}`; generated when omitted). Body `{recipientId,amountMinor,method,note,corridor,scenario,scaToken}`. Method `card|bank`. Corridor `UK`, `US`, or a rehearsal European code (`EU`, `DE`, `FR`, `NL`, `IE`, `ES`, `IT`, `BE`, `AT`, `PT`, `FI`, `SE`, `DK`, `PL`, `LU`, `NO`). Scenario `success|soft_decline|hard_decline|ambiguous|unknown_code` (default `success`) is a simulation control and is not part of the idempotency payload. `scaToken` is a synthetic fixture name, not a credential: `sca_ok_demo`, `sca_challenge_ok`, `sca_challenge_fail`, `sca_expired`.
  - A passing token persists the intent as `PROCESSING` before the in-process sandbox call, then moves it to `SUCCEEDED` (HTTP200 `{ok:true,status,code:"AUTHORIZED",traceId,paymentId,provider,corridor,declineClass:"NONE",failoverEligible:false,failoverAttempted:false,providerCode,simulation:true,state,transaction}`) or `FAILED`.
  - Soft decline HTTP422 `{code:"SOFT_DECLINE",declineClass:"SOFT",failoverEligible:true,failoverAttempted:false}`. Hard decline and an unmapped sandbox code HTTP422 `{code:"HARD_DECLINE",declineClass:"HARD",failoverEligible:false}`. Challenge failure HTTP422 `{code:"SCA_CHALLENGE_FAILED"}` and does not call a provider. Expired or unknown tokens HTTP400 `SCA_TOKEN_EXPIRED` or `INVALID_SCA_TOKEN` and do not persist an intent.
  - Ambiguous sandbox results stay `PROCESSING`, HTTP503 `{code:"AUTHORIZATION_AMBIGUOUS",failoverEligible:false,failoverAttempted:false}`, reserve the amount, and are not sent to the paired provider. Replaying the same key returns the stored outcome and does not call a provider again. A changed business payload, including the step-up token, returns 409.
  - European corridors route to the Adyen adapter (`european-corridor-adyen-sandbox`). That is the rehearsal stand-in for the PAY-148 Adyen working assumption and does not call Adyen. UK and US keep card→Adyen and bank→Worldpay. Soft declines record the paired adapter and still do not dispatch it. State changes, step-up outcomes, and routing decisions append audit events that include `traceId` and omit the token. `GET /events` returns them latest first. Reset clears them with the room.
- Repeated successful payment key and same normalized payload returns same transaction and CURRENT state, no second debit. Reused key different recipient/amount/method/note returns409. Scenario is a simulation control, not part of business payload. Failed transient simulation can retry with same key. Per-session writes are atomic.
- `PATCH /budgets` body `{category,limitMinor}` positive <=1000000 integer, known category. Returns `{ok:true,state}`.
- `POST /reset` resets that session and returns `{ok:true,state}`.
- `GET /events` returns `{events:AuditEvent[]}` latest first for the session; no sensitive data.
- `POST /webhooks/{provider}` supports synthetic HMAC-signed events for pending attempts only. Header `X-Webhook-Timestamp` epoch seconds, `X-Meridian-Signature` lowercase SHA256 HMAC hex of `timestamp + '.' + exact raw JSON`. Key from `MERIDIAN_WEBHOOK_SECRET`, disabled when absent. Body `{sessionId,eventId,paymentId,status}`; status completed/declined. Max5minute skew, event replay dedup, provider must match stored attempt, no payment creation from unsolicited callbacks. Returns `{ok:true,duplicate:boolean}`. This is a Meridian simulation signature, not an Adyen/Worldpay vendor protocol.

## BankState (same web baseline)

`{version:1,balance:number,transactions:Transaction[],budgets:Budget[]}`.
Transaction `{id,reference,recipientId,name,category,amount,date,provider,method,status,note}`. Positive outgoing amount; date YYYY-MM-DD. provider adyen/worldpay. method card/bank. status completed/declined. Budget `{category,limit}`. Categories Shopping, Food & drink, Transport, Bills, Lifestyle. Fixture must exactly match existing web `createInitialState()`, recipients and provider registry (only Adyen/card and Worldpay/bank). Fixed rehearsal date 2026-09-18.

## Deployment topology

Java API serves `/api/v1`. Vite dev servers and production Nginx proxies expose same-origin `/api/v1` to clients. Web uses `VITE_API_BASE_URL=/api/v1` in connected builds; unset means existing explicitly labelled standalone simulation. Browser mobile companion lives in meridian-mobile/preview and calls the same API; it is not the native build. Native Swift/Kotlin clients use configurable base URL and shared session. Do not silently switch from server mode to local success on errors.
