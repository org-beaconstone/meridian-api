# Payment method and intent contract v2

Provider-neutral catalog and payment-intent contract for mobile clients and the Meridian payment orchestrator. Reference PAY-134 and MAPI-771. This is a fictional rehearsal. Amounts are integer GBP pence. No provider credentials, PAN, or live vendor calls.

`/api/v1` is unchanged. v2 is additive. The same `X-Rehearsal-Session` ledger is authoritative for both versions.

Machine-readable paths and schemas: [openapi-v2.yaml](openapi-v2.yaml). Narrative rules in this file win if an example drifts.

## Compatibility

- v1 `POST /api/v1/payments` remains the fixed UK flow: `card` resolves to Adyen and `bank` resolves to Worldpay. Clients that omit a corridor stay on that path.
- v1 and v2 share room state, reservations, webhook completion, and the single session write monitor.
- Amounts stay on the legacy integer decoder. JSON numbers that are not integers, and numeric strings, are rejected. The US corridor does not switch the currency or the decoder.
- Idempotency keys are compared against the exact stored business payload. A v1 key and a v2 key are different encodings even when the payment looks the same. Do not reuse a key across versions.
- European corridors are absent. No third provider is named or selectable.

## GET /api/v2/payment-methods

No session header. The body is a cacheable catalog.

| Field | Value |
| --- | --- |
| `catalogVersion` | `2026-09-18.1` |
| `expiresAt` | `2026-09-19T00:00:00Z` |
| `currency.code` | `GBP` |
| `currency.exponent` | `2` |
| `currency.minorUnit` | `pence` |
| `currency.amountEncoding` | `integer` |

`methods` lists open descriptors. A descriptor binds a method and the corridors where that method is offered. It does not name a provider. Clients render `id` and `displayName`, restrict checkout to `descriptor.corridors`, and send `method` plus `corridor` on the intent. Unknown descriptor properties must be ignored so later resolution metadata can be added without a client release.

| id | displayName | binding | corridors | flows |
| --- | --- | --- | --- | --- |
| `card` | Debit card | `open` | `UK`, `US` | `fixed-uk-card`, `fixed-us-card` |
| `bank` | Bank payment | `open` | `UK`, `US` | `fixed-uk-bank`, `fixed-us-bank` |

`binding: "open"` means the catalog has not pinned a provider. The orchestrator resolves one when the intent is created.

## Fixed UK and US resolution

Resolution runs only on the server, from the method and corridor. The pairing is fixed:

| Corridor | Method | Flow | Provider |
| --- | --- | --- | --- |
| `UK` | `card` | `fixed-uk-card` | Adyen |
| `UK` | `bank` | `fixed-uk-bank` | Worldpay |
| `US` | `card` | `fixed-us-card` | Adyen |
| `US` | `bank` | `fixed-us-bank` | Worldpay |

Any other corridor, including `EU`, is HTTP 400. The server does not fall back to the other provider after an ambiguous outcome.

## POST /api/v2/payment-intents

Headers: `X-Rehearsal-Session` (same 3-64 character rule as v1), `Idempotency-Key` (1-100 safe characters).

Body fields: `recipientId`, `amountMinor`, `method` (`card` or `bank`), `corridor` (`UK` or `US`), optional `note` (max 200, default empty), optional `scenario` (`success`, `declined`, `unavailable`, `pending`; default `success`). `scenario` is a simulation control and is not part of the idempotency payload. Unknown JSON properties are rejected.

`amountMinor` is a JSON integer from 1 to 1000000, in GBP pence, and must fit the available balance after pending reservations. The decoder is the v1 decoder: floats are not accepted as ints, and strings are not coerced to numbers.

The server persists its intent id before the provider port is invoked. The same key and same business payload (`recipientId`, `amountMinor`, `method`, `note`, `corridor`) returns the current intent and does not debit again. The same key with a different business payload is HTTP 409. `declined` and `unavailable` may be retried with the same key. `pending_confirmation`, `processing`, `succeeded`, and a reconciliation decline may not be replaced with a new attempt on that key.

HTTP status of the command:

| Lifecycle `status` | HTTP | `code` |
| --- | --- | --- |
| `succeeded` | 200 | `PAYMENT_SUCCEEDED` |
| `pending_confirmation` | 202 | `PAYMENT_PENDING` |
| `processing` | 202 | `PAYMENT_PENDING` |
| `declined` | 422 | `PAYMENT_DECLINED` |
| `unavailable` | 503 | `PROVIDER_UNAVAILABLE` |

Validation and idempotency conflicts use 400 and 409 with `{ok:false,error,code}`.

## GET /api/v2/payment-intents/{id}

Requires the same session. HTTP 200 returns the intent resource. HTTP 404 when that session has no such id. This read is the authoritative lifecycle. A previously signed `returnState` is a snapshot, not a newer status.

`status` is only one of: `processing`, `pending_confirmation`, `succeeded`, `declined`, `unavailable`. Clients must not invent statuses. `ok` is true only for `succeeded`.

## Action payloads

`action.type` tells the client what to do next. `action.payload` is the only action data.

| `status` | `action.type` | Payload |
| --- | --- | --- |
| `succeeded` | `complete` | `transactionId`, `reference`, `retryable:false` |
| `pending_confirmation` | `await_confirmation` | `paymentId`, `retryable:false`, instruction not to create another payment |
| `processing` | `await_confirmation` | `paymentId`, `retryable:false`, instruction that the payment is still processing |
| `declined` | `terminal` | `code:PAYMENT_DECLINED`, `final` (true after a reconciliation decline), `retryable:false` |
| `unavailable` | `retry` | `code:PROVIDER_UNAVAILABLE`, `retryable:true`, `sameIdempotencyKey:true` |

A pending intent is completed by the existing simulation webhook `POST /api/v1/webhooks/{provider}`. `{provider}` must be the resolved provider (`adyen` or `worldpay`). The webhook remains a Meridian HMAC test event, not a vendor protocol. Completion debits once. A decline webhook sets a final decline.

## Signed return state

Every intent response includes `returnState`:

```json
{"intentId":"...","status":"succeeded","amountMinor":2599,"issuedAt":"2026-09-18T12:00:00Z","algorithm":"HMAC-SHA256","signature":"<lowercase hex>"}
```

`signature` is HMAC-SHA256 over the UTF-8 string `intentId + '.' + status + '.' + amountMinor + '.' + issuedAt`, using `MERIDIAN_RETURN_STATE_SECRET`. When that variable is unset, the rehearsal default `meridian-rehearsal-return-state` is used. `issuedAt` is the exact ISO-8601 instant string inside the object. The key is a simulation secret, not a provider credential.

Clients may check the signature before showing the snapshot. They must call GET before treating the status as final, because a later webhook can move `pending_confirmation` to `succeeded` or `declined`.

`resolution.flow` is the flow id from the table above. `resolution.provider` is the provider the orchestrator already bound, for support and reconciliation. Mobile clients do not choose it and do not branch checkout on it.

`state` is the current room `BankState`. `transaction` is present only when `status` is `succeeded`.

## Sign-off

The specification is ready for review. Names below are the accountable teams. This document does not record a human approval.

| Team | Review |
| --- | --- |
| Payments Platform | Resolver table, persisted intent before the provider port, no cross-provider fallback, v1 payloads unchanged |
| Mobile | Open descriptors, integer `amountMinor`, action payloads, signed snapshot versus GET |
| Trust & Safety | No PAN or provider secrets in the contract, session id is not authentication, EU corridor stays closed, webhook and return-state signatures are simulation-only |
| Support | Lifecycle vocabulary, `final` decline, provider on `resolution` for an already created intent, same room ledger as v1 |
