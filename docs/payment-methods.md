# Supported Payment Methods

> **Scope:** This describes the simulation-only payment integration layer in Meridian API.
> No real provider credentials, external requests, or production acquiring arrangements are represented.

## Summary

Meridian API supports **two payment methods**, each routed to a dedicated provider:

| Method | Provider   | Provider ID  | Description              |
|--------|------------|--------------|--------------------------|
| `card` | Adyen      | `adyen`      | Card payment processor   |
| `bank` | Worldpay   | `worldpay`   | Bank transfer processor  |

## Method Routing

The routing is determined at payment-intent creation time in `RehearsalBank.java`:

```
method = "card"  →  provider = adyen
method = "bank"  →  provider = worldpay
```

The method value is fixed once an idempotency key is persisted. Any subsequent request with the same key and a different payload is rejected with `409 Conflict`.

## Validation Rules

- `method` must be exactly `"card"` or `"bank"` — any other value returns `400 Bad Request` with `"Unknown payment method"`.
- `amountMinor` must be an integer in GBP pence, between 1 and 1,000,000 (£0.01 – £10,000.00).
- `recipientId` must match a known recipient from the catalog.

## Provider Outcomes (Simulation)

Both providers delegate to the shared `Simulator` and support the following scenarios, supplied via the `scenario` field on the payment request:

| Scenario      | Outcome                              | HTTP status |
|---------------|--------------------------------------|-------------|
| `success`     | Payment authorised and settled       | `200`       |
| `declined`    | Payment declined, no debit           | `422`       |
| `unavailable` | Provider unavailable, no debit       | `503`       |
| `pending`     | Async confirmation required          | `202`       |

Default scenario when omitted is `success`.

## Webhook / Async Confirmation

Pending payments can be finalised via the webhook endpoint:

```
POST /api/v1/webhooks/{provider}
```

Supported `{provider}` values: `adyen`, `worldpay`.

Webhook payloads are HMAC-SHA256 signed and replay-protected (5-minute timestamp window + event-ID deduplication). Webhooks are simulation-only; no real vendor events are processed.

## Catalog Endpoint

The full list of providers and their supported methods is available at:

```
GET /api/v1/catalog
```

Response includes a `providers` array matching the table above.

---

*This document reflects the current implementation. The broader company strategy (docs/context.md) references Adyen as primary and Worldpay as failover/legacy acquiring — that production arrangement is outside the scope of this rehearsal simulation.*
