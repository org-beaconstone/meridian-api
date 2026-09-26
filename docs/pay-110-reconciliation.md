# PAY-110 reconciliation checkpoint

Recorded 2026-09-26 against `meridian-api` main and the in-flight `meridian-mobile` catalog drafts. This checkpoint is the precondition for general availability of the config-driven mobile provider picker. General availability is not granted here.

Payments Platform owns the catalog contract below. Mobile sign-off is still required on the client drafts that match it.

## Current provider abstraction (MAPI-771)

MAPI-771 is in progress. The provider port is `PaymentProvider`: `getProviderId()` plus `authorize(persistedPaymentIntentId, scenario, corridor)`. The only adapters are Adyen (`adyen`) and Worldpay (`worldpay`). Both simulate outcomes. They do not call a vendor.

`GET /api/v1/catalog` remains the provider registry:

| Provider id | Name | Methods | Description |
| --- | --- | --- | --- |
| `adyen` | Adyen | `card` | Card payment processor |
| `worldpay` | Worldpay | `bank` | Bank transfer processor |

`POST /api/v1/payments` still takes `method` of `card` or `bank`. The server chooses the provider from that catalog registry and stores it on the intent before authorization. The web contract is unchanged: same catalog fields, same method enum, same idempotency payload (`recipientId`, `amountMinor`, `method`, `note`). Scenario stays a simulation control and is not part of the payload.

The corridor argument on the provider port is the rehearsal value `UK`. The catalog has no corridor list. `Simulator` does not branch on corridor.

Startup refuses a catalog that disagrees with the adapter set: an unknown provider id, a method owned by two providers, an empty method list, or an adapter missing from the catalog.

## Mobile models compared

Shipped `meridian-mobile` main still uses closed `PaymentMethod` (`card`, `bank`) and `ProviderId` (`adyen`, `worldpay`) and posts those method names. That matches this API.

The parallel drafts, compared with this catalog:

| Draft | Mobile PR | What it reads | What it posts | Checkpoint result |
| --- | --- | --- | --- | --- |
| iOS config-driven catalog (PAY-97) | meridian-mobile#10 | `GET /catalog`. Keeps only Adyen+`card` and Worldpay+`bank`. Client ids `adyen_card` and `worldpay_bank`. Flag `MERIDIAN_CONFIG_DRIVEN_CATALOG` defaults off. | `card` or `bank` | Matches the API. Composite ids stay on the client. |
| Android config-driven catalog (PAY-98) | meridian-mobile#7 | `GET /catalog`. Same two pairs. Flag `MERIDIAN_CATALOG_CONFIG_DRIVEN` defaults off. Flag-off option ids are `card` and `bank`; flag-on ids are `adyen_card` and `worldpay_bank`. | `card` or `bank` | Matches the wire contract. Flag name and flag-off option ids differ from the iOS draft. |
| iOS catalog picker (PAY-103) | meridian-mobile#8 | `GET /catalog`, every method string. Closed enums removed. No feature flag. | The catalog method string unchanged | Matches today because those strings are `card` and `bank`. It will post a future catalog code the API rejects. |
| Android catalog picker (PAY-102) | meridian-mobile#6 | Same as PAY-103. | The catalog method string unchanged | Same as PAY-103. |
| Android remote picker (PAY-58) | meridian-mobile#5 | `GET /config/payment-providers`, plus `corridors` and `configVersion`. Default mode is remote. | `card` or `bank` | Diverges. That path is not on this API. The catalog has no corridors and no config version. The Milestone 2 seed names the provider "Debit card" / "Bank payment" instead of Adyen / Worldpay. |
| iOS dynamic-catalog tests (PAY-18) | meridian-mobile#3 | `GET /catalog`, with an open provider id for unrecognized values. | Unchanged `card` / `bank` | Decode stays compatible with this catalog. Fixtures that add further providers are tests only. This API does not serve them. |

Session-banner work (meridian-mobile#9 and #11) reads existing session state. It does not change the payment method contract.

## Divergences

1. Two client id schemes are in draft at once: composite ids (`adyen_card`, `worldpay_bank`) and raw catalog methods (`card`, `bank`). The API accepts only the catalog methods.
2. PAY-102 and PAY-103 forward the catalog method string as the payment body. That is safe for the current fixture and unsafe if the catalog ever grows a display id.
3. PAY-58 calls `GET /config/payment-providers` and filters on corridors. This API serves provider configuration only from `GET /catalog`.
4. Flag-on iOS and Android use different environment flag names. Flag-off Android option ids differ from the flag-on composite ids.
5. Display copy ("Debit card", "Bank payment") is client-owned. Catalog `name` is the provider name (`Adyen`, `Worldpay`).
6. Provider selection used to be a `card`/`bank` branch in the orchestrator, beside the catalog. Routing now follows the catalog registry, so the list mobile reads and the provider the server stores are the same map.
7. European provider selection (PAY-1187) is still open. This checkpoint does not add a provider, a corridor list, or a new method.

## Resolution agreed by Payments Platform

1. `GET /api/v1/catalog` is the only provider-config source for the mobile picker. `GET /config/payment-providers` is not part of the contract.
2. `POST /api/v1/payments` `method` is the catalog method code: `card` or `bank`. Composite ids are client presentation. The API rejects them with `400` and `Unknown payment method`.
3. The server selects the provider from the catalog registry after the intent is persisted. Mobile does not send a provider id, and a method-id substitution must not change the idempotency payload. The same key with `card` then `bank` returns `409`.
4. The config-driven picker that may go to general availability is the PAY-97 / PAY-98 shape: feature flag default off, baseline pairs only, last-known-good cache, wire method `card` or `bank`. PAY-102 and PAY-103 need the same normalization before they are the GA client. PAY-58 needs to read `GET /catalog` before it is the GA client.
5. Client display labels stay in the app. Catalog `name` stays the provider name from the fixture.
6. iOS and Android flag names are a mobile alignment item. The API does not read them.
7. No third provider and no corridor field land in this checkpoint. PAY-1187 remains the gate for any later catalog entry.

## Sign-off

| Party | Status | Record |
| --- | --- | --- |
| Payments Platform | Signed off for this contract | 2026-09-26. Catalog registry in `fixture.json`, routing in `CatalogRouting`, coverage in `CatalogReconciliationTest` and `CatalogRoutingTest`. Web clients keep `card` / `bank`. |
| Mobile | Outstanding | Confirm PAY-97 and PAY-98 as the GA catalog model, including one shared flag name, and confirm PAY-58, PAY-102, and PAY-103 will follow the wire rules above. |

General availability of the config-driven picker waits on the Mobile row.
