# Payment methods and intents contract v2

Status: **ready for review** (PAY-163). This document is the specification Payments Platform, Mobile, Trust & Safety, and Support are asked to sign. It does not record their approval.

Fictional rehearsal only. No live acquiring, no card data, and no vendor protocol. Version 1 in [contract.md](contract.md) stays the web and current mobile contract. `/api/v2` is additive.

Amounts are integer GBP pence on the existing Jackson integer decoder (`FAIL_ON_UNKNOWN_PROPERTIES`, floats rejected, numeric strings rejected). A room id in `X-Rehearsal-Session` isolates synthetic state. It is not authentication.

## Compatibility

- `GET /api/v1/catalog` still returns the fixed rehearsal date, recipients, and the Adyen card and Worldpay bank registry.
- `POST /api/v1/payments` still accepts `method` `card|bank`, integer `amountMinor`, and the same idempotency and error rules.
- UK and US v2 flows use that same registry. Card resolves to Adyen. Bank resolves to Worldpay. Both corridors share the GBP pence ledger. There is no European method and no third provider.
- An ambiguous or pending outcome is never retried on another provider. Unavailable before authorization may be retried with the **same** idempotency key. Pending must not be resent as a new payment.
- v1 and v2 writes share one session ledger and one idempotency record. The business payload is recipient, amount, method, and note. Scenario is a simulation control. A stored corridor conflicts with a different non-null corridor for the same key.

## GET /api/v2/payment-methods

No session header. Optional query `corridor=UK|US` limits the top-level `corridors` array. Any other corridor is HTTP 400.

```json
{
  "catalogVersion": "2026.09.18-uk-us-1",
  "issuedAt": "2026-10-01T22:00:00Z",
  "expiresAt": "2026-10-01T22:15:00Z",
  "simulation": true,
  "currency": {
    "code": "GBP",
    "minorUnitExponent": 2,
    "amountEncoding": "integer-minor",
    "decoder": "legacy-integer"
  },
  "corridors": [
    {"code": "UK", "flow": "fixed"},
    {"code": "US", "flow": "fixed"}
  ],
  "methods": [
    {
      "id": "card",
      "displayName": "Debit card",
      "corridors": ["UK", "US"],
      "descriptor": {
        "open": true,
        "scheme": "meridian.payment-method",
        "flow": "fixed-uk-us",
        "methodId": "card",
        "amountEncoding": "integer-minor",
        "minMinor": 1,
        "maxMinor": 1000000,
        "providerResolvedBy": "server"
      }
    }
  ]
}
```

`catalogVersion` is the compatibility token clients send on create. `expiresAt` is a 15-minute client cache TTL measured from `issuedAt`. After it passes, the client refetches. The server keeps accepting the current version until that version changes. A different version is HTTP 409 `CATALOG_STALE` and does not create an intent.

`currency.decoder` is `legacy-integer`: minor units are JSON integers only.

Each `descriptor` is an **open** object (`descriptor.open` is true, and the schema allows additional properties). Clients must ignore unknown descriptor fields. They must not treat the descriptor as a provider list. The catalog does not name Adyen or Worldpay. `providerResolvedBy: server` means the orchestrator binds the method to a provider when the intent is persisted.

The bank method uses the same descriptor shape with `id` and `methodId` `bank` and `displayName` `Bank payment`.

## POST /api/v2/payment-intents

Headers: `X-Rehearsal-Session`, `Idempotency-Key` (same patterns as v1).

```json
{
  "recipientId": "birch-bloom",
  "amountMinor": 2599,
  "methodId": "card",
  "corridor": "UK",
  "note": "",
  "scenario": "success",
  "catalogVersion": "2026.09.18-uk-us-1",
  "descriptor": {"methodId": "card", "futureFlag": true}
}
```

`corridor` defaults to `UK` when omitted. `US` is the other fixed flow. `scenario` defaults to `success` and is not part of the idempotency payload.

The request object is closed. `provider`, `providerId`, and `acquirer` are rejected with HTTP 400, including inside `descriptor`. Unknown top-level fields are rejected. `descriptor` itself is open: unknown keys other than the forbidden provider keys are ignored, and `descriptor.methodId` must match `methodId` when present.

Validation order: session, idempotency key, catalog version, descriptor, then the v1 payment rules (integer amount 1..1000000, known recipient, note length, known method, available balance).

Create and read share one intent resource:

| Field | Meaning |
| --- | --- |
| `status` | Authoritative lifecycle: `processing`, `requires_action`, `succeeded`, `declined`, `unavailable` |
| `authoritative` | `true` on this response. A previously stored `returnState` is not authoritative if a later GET differs |
| `action` | Next client step, or `null` when the lifecycle needs none |
| `returnState` | Signed snapshot of this response's lifecycle |
| `receipt` | Provider-neutral receipt when `succeeded`, otherwise `null` |
| `code` / `error` | Present for non-success lifecycle values, otherwise `null` |
| `simulation` | Always `true` |

HTTP status on create follows the lifecycle: 200 succeeded, 202 `requires_action` or `processing`, 422 declined, 503 unavailable, 400 validation, 409 stale catalog or idempotency mismatch.

### Action payloads

`action` is `null` for `succeeded` and `declined`. Otherwise:

| `action.type` | When | `action.payload` |
| --- | --- | --- |
| `poll` | `processing` | `intentId`, `href` (`/api/v2/payment-intents/{id}`) |
| `await_webhook` | `requires_action` | `intentId`, `instruction` telling the client to wait and not to create another payment |
| `retry_same_key` | `unavailable` | `intentId`, `sameKeyRequired: true` |

`action.payload` is an open object. Clients must ignore unknown payload fields. Completion of a pending intent still uses the v1 simulation callback `POST /api/v1/webhooks/{provider}`. v2 does not add a vendor webhook.

### Signed return state

```json
"returnState": {
  "algorithm": "HMAC-SHA256",
  "payload": {
    "intentId": "...",
    "status": "succeeded",
    "amountMinor": 2599,
    "currency": "GBP",
    "methodId": "card",
    "corridor": "UK",
    "catalogVersion": "2026.09.18-uk-us-1"
  },
  "canonical": "{...exact JSON of payload...}",
  "signature": "<lowercase hex>",
  "verifiable": true
}
```

`signature` is HMAC-SHA256 over the exact `canonical` string, keyed with `MERIDIAN_WEBHOOK_SECRET`. This is a Meridian simulation signature, not an Adyen or Worldpay signature. When the secret is unset, `signature` is null and `verifiable` is false. Clients that keep a return state must still call GET before showing a final result.

### Lifecycle

| Stored phase | `status` | Client action |
| --- | --- | --- |
| `submitting`, `prepared` | `processing` | Poll GET |
| `pending` | `requires_action` | Wait for the simulation callback, then GET |
| `completed` | `succeeded` | Show `receipt`. Ledger debit has happened once |
| `declined`, `declined-final` | `declined` | No debit. `declined-final` is reconciliation and is terminal |
| `unavailable` | `unavailable` | Same idempotency key may be reused. No fallback provider |

`GET /api/v2/payment-intents/{id}` returns this resource with HTTP 200 for every known phase, including declined and unavailable. Unknown ids in the session are HTTP 404. Another session's id is HTTP 404. Missing or invalid session is HTTP 400.

The receipt omits the provider. Support uses v1 `GET /state` and `GET /events`, which still record `adyen` or `worldpay`.

## Fixed UK and US resolution

Resolution happens once, in the orchestrator, before the provider port is called, and is stored with the intent.

| Corridor | Method | Provider port |
| --- | --- | --- |
| UK | card | Adyen |
| UK | bank | Worldpay |
| US | card | Adyen |
| US | bank | Worldpay |

v1 payments omit corridor and are authorized as UK. The port does not receive a client-supplied provider id.

## Review record

Sign-off is outstanding. Each team confirms the row below. This repository does not treat the row as approved.

| Team | Confirm | Status |
| --- | --- | --- |
| Payments Platform | Catalog version, server-side resolution, shared idempotency, no fallback after an ambiguous outcome, integer pence ledger | Pending sign-off |
| Mobile | Open descriptors, no hardcoded provider ids, refetch on `expiresAt` and `CATALOG_STALE`, GET wins over a saved return state | Pending sign-off |
| Trust & Safety | Simulation only, no PAN or credentials, session id is not authentication, signed snapshot uses the rehearsal secret | Pending sign-off |
| Support | Lifecycle names above, GET is the status to quote, provider remains on the v1 ledger and audit feed | Pending sign-off |

Machine-readable shapes are in [openapi-v2.yaml](openapi-v2.yaml).
