# Payment methods and intents contract v2

Additive contract for PAY-187. Base path `/api/v2`. JSON UTF-8. v1 in [contract.md](contract.md) is unchanged: same routes, same integer GBP pence decoding, same UK and US card and bank settlement.

This is a fictional rehearsal. Responses set `simulation: true`. No provider credentials, card numbers, or live vendor calls are accepted or stored. Session ids isolate demo state. They are not authentication.

## Catalog

`GET /api/v2/payment-methods` does not require `X-Rehearsal-Session`.

```json
{
  "catalogVersion": "2026.09.18-uk-us",
  "expiresAt": "2026-10-18T00:00:00Z",
  "descriptorPolicy": "open",
  "currency": { "code": "GBP", "minorUnits": 2, "minorUnitName": "pence", "symbol": "£" },
  "corridors": ["UK", "US"],
  "simulation": true,
  "methods": [
    {
      "descriptor": "md_card",
      "label": "Debit card",
      "legacyMethod": "card",
      "corridors": ["UK", "US"],
      "currencies": ["GBP"],
      "flow": "server_confirmed",
      "amount": { "minimumMinor": 1, "maximumMinor": 1000000, "currency": "GBP" }
    },
    {
      "descriptor": "md_bank",
      "label": "Bank payment",
      "legacyMethod": "bank",
      "corridors": ["UK", "US"],
      "currencies": ["GBP"],
      "flow": "server_confirmed",
      "amount": { "minimumMinor": 1, "maximumMinor": 1000000, "currency": "GBP" }
    }
  ]
}
```

`descriptorPolicy: "open"` means clients treat `descriptor` as an opaque string taken from this document. The request schema is a string, not a closed enum. A future method is a new catalog entry. Clients must not branch on provider names. This document does not include provider ids.

`expiresAt` tells clients when to refresh. The server accepts the current `catalogVersion` independent of the host clock so the fixed rehearsal date keeps working. A new payment whose `catalogVersion` is not `2026.09.18-uk-us` is `409` with error `Payment method catalog is stale`. Replaying an existing key with its original version still returns that intent.

Currency for both UK and US in this contract is integer GBP pence (`minorUnits: 2`). There is no decimal or string amount decoder.

## Intents

`POST /api/v2/payment-intents` and `GET /api/v2/payment-intents/{id}`.

Headers for both: `X-Rehearsal-Session` (3–64 URL-safe characters). POST also requires `Idempotency-Key` (1–100 URL-safe characters). Writes are synchronized per session and share the v1 ledger, reservations, and idempotency table.

POST body, unknown fields rejected:

```json
{
  "recipientId": "birch-bloom",
  "amountMinor": 2599,
  "descriptor": "md_card",
  "corridor": "UK",
  "note": "",
  "catalogVersion": "2026.09.18-uk-us",
  "scenario": "success"
}
```

| Field | Rule |
| --- | --- |
| `recipientId` | Known rehearsal recipient |
| `amountMinor` | JSON integer 1..1000000. Floats and numeric strings are `400` `INVALID_JSON` |
| `descriptor` | Syntax `[a-z0-9_]{1,64}`. New payments must be in the current catalog |
| `corridor` | `UK` or `US` |
| `note` | Optional, max 200, default empty |
| `catalogVersion` | Required. Part of the idempotency payload |
| `scenario` | Optional simulation control: `success`, `declined`, `unavailable`, `pending`. Default `success`. Not part of the idempotency payload |

Business payload for idempotency is recipient, amount, descriptor, corridor, note, and catalog version. The same key and payload return the original intent. A different payload on that key is `409`. A key already used by v1 is a different payload and is `409`. Known `declined` and `unavailable` results may be retried with the same key and a new scenario. `succeeded`, `requires_action`, and reconciliation declines must not be re-authorized and must not be retried with a new key.

`scenario` is rehearsal-only. It is not a production field.

### Authoritative lifecycle

`status` is the lifecycle. Clients ignore local guesses when it disagrees. `authoritative` is always `true` on a resource. `statusReason` is display text and does not override `status`.

| `status` | Meaning | POST | GET |
| --- | --- | --- | --- |
| `succeeded` | Debited once | 200 | 200 |
| `requires_action` | Waiting. No debit | 202 | 200 |
| `declined` | Terminal or retryable decline. No debit | 422 | 200 |
| `failed` | Resolved provider unavailable before authorization. No debit | 503 | 200 |

GET of a known v2 intent is always HTTP 200 so a poll is not mistaken for a create error. Unknown ids, other sessions, and v1 payment ids are `404`.

### Action payload

Terminal intents set `action` to `null`. `requires_action` returns:

```json
{
  "type": "await_confirmation",
  "payload": { "intentId": "<id>", "simulation": true, "clientAction": "poll" }
}
```

The client polls `GET /api/v2/payment-intents/{id}`. Completion of a pending intent is the existing signed simulation webhook on `/api/v1/webhooks/{provider}`. That callback is server-side. Clients do not choose a provider and do not get a provider id in the v2 resource.

`succeeded` also returns `balanceMinor` (room balance at read time, which can change after later payments) and `receipt` (the debit: id, reference, recipient, name, category, amountMinor, date, legacy method, status, note). The receipt omits the provider id.

### Signed return state

Every POST and GET resource includes `issuedAt` (ISO-8601) and `returnState` (lowercase hex HMAC-SHA256). The signed snapshot is this UTF-8 string, fields joined by `|`, with empty text where the field is absent:

```text
v2|{id}|{status}|{amountMinor}|{descriptor}|{corridor}|{catalogVersion}|{balanceMinor}|{receiptId}|{actionType}|{issuedAt}
```

`amountMinor` and `balanceMinor` are decimal integers. `receiptId` is `receipt.id`. `actionType` is `action.type`. The key is `MERIDIAN_RETURN_SECRET`, or the published simulation default `rehearsal-return-state` when unset. This is a Meridian rehearsal signature, not an Adyen or Worldpay signature.

Each response is a new snapshot. Clients verify `returnState` against the fields in that response. A previous signature does not advance the lifecycle. GET remains the authority after a redirect or a poll.

## Server resolution

These mappings are not client fields. The provider port is still the two rehearsal adapters. One descriptor resolves to one adapter. An unavailable or ambiguous outcome is not sent to the other adapter.

| Descriptor | Legacy method | Corridor | Adapter |
| --- | --- | --- | --- |
| `md_card` | `card` | UK, US | Adyen |
| `md_bank` | `bank` | UK, US | Worldpay |

v1 `method: card|bank` keeps that same pairing and still passes corridor `UK` into the port. US v2 traffic uses the same adapters and the same GBP pence amounts. Other corridors, including any European corridor, are `400` `Unsupported corridor`.

The intent id is stored before the adapter is called. `pending` and `submitting` reserve `amountMinor` against later v1 and v2 spends. Reset of the session clears both.

## Errors

Domain errors use the v1 envelope: `{ok:false, error, code}` where `code` is `HTTP_` plus the status. Unreadable JSON, including fractional or string amounts and unknown fields, is `400` `{ok:false, error:"Invalid JSON request: integer amounts and known fields required", code:"INVALID_JSON"}`.

## Review packet

PAY-187 asks Payments Platform, Mobile, Trust & Safety, and Support to sign off on this specification. The checks below are what this repository implements for that review. Names are not recorded here as signatures.

| Team | What this contract gives them |
| --- | --- |
| Payments Platform | One catalog, server-side descriptor resolution, persisted idempotency, no cross-provider fallback, shared per-session ledger |
| Mobile | Open descriptors plus `legacyMethod` for the fixed card and bank flows, poll action, integer pence, no provider ids in the payload |
| Trust & Safety | Signed return state, no PAN or credentials, simulation flag, session isolation without an authentication claim |
| Support | Authoritative `status` and `statusReason`, GET by id, stable error envelope, v1 audit and state left in place |

Machine-readable paths: [openapi-v2.yaml](openapi-v2.yaml).
