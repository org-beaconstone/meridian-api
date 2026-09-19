# Meridian API

Java 21 / Spring Boot payment orchestrator for the fictional Meridian Bank rehearsal. Works with [meridian-web](https://github.com/org-beaconstone/meridian-web) and [meridian-mobile](https://github.com/org-beaconstone/meridian-mobile). Only Adyen and Worldpay adapters exist. They simulate outcomes without contacting either provider.

## Start all three together

Clone all three repos as siblings, then from `meridian-api`:

```sh
node scripts/rehearsal.mjs
```

Requires Java 21, Maven, Node 22.12+ and npm. The launcher builds/tests the backend, installs missing frontend dependencies, starts the API before the clients, and stops only its own processes on Ctrl+C.

- Web: http://127.0.0.1:5175
- Mobile browser companion: http://127.0.0.1:5176
- Java API: http://127.0.0.1:8080/api/v1/health
- Shared room: `meridian-rehearsal`

If ports are occupied, don't stop unrelated apps. Use:

```sh
API_PORT=8088 WEB_PORT=5185 MOBILE_PORT=5186 node scripts/rehearsal.mjs
```

Or use Docker with the same sibling layout:

```sh
docker compose up --build
```

Compose is supplied but was not run locally because Docker's daemon was unavailable. The directly launched Java/Vite connected scenario was browser-tested. Frontends tolerate the API startup interval and retry connection.

## Backend only

```sh
mvn clean verify
java -jar target/meridian-api-1.0.0.jar
```

`PORT` defaults 8080. `MERIDIAN_BIND` defaults 127.0.0.1; the container explicitly binds 0.0.0.0 internally, while Compose publishes ports to the host loopback address only. H2 persists to `data/meridian-connected`; override with `MERIDIAN_DB_URL`. One Java instance owns the ledger. Do not horizontally scale this rehearsal implementation.

## Implemented contract

See [contract](docs/contract.md). Each client sends `X-Rehearsal-Session`; payment POSTs also send `Idempotency-Key`. Money is integer GBP pence. Raw `GET /state` returns the same state shape as the original web demo. Payment, budget, reset and reconciliation writes share one monitor held through transaction commit.

The server persists its own payment-intent ID before invoking the provider port. Known declined/unavailable simulations can retry with the same client key. Same key with changed business payload is rejected. Pending/submitting intents reserve spending capacity and cannot be resent blindly. Signed webhook completion appends the transaction and debits once. Reset clears only the selected room. `/events` exposes the latest synthetic audit entries.

## Synthetic webhooks

Disabled unless `MERIDIAN_WEBHOOK_SECRET` is set. Never use a production provider secret. The test signature is HMAC-SHA256 over `timestamp + '.' + exact raw body`, with a five-minute window. It is a Meridian simulation protocol, not either vendor's real signing protocol. Create a pending intent before sending a completion callback. See [webhook helper](scripts/webhook.py).

## Context and limitations

[Existing source documents](docs/context.md) are authoritative demo story context. Confluence pages were read; the supplied Google Docs are linked but their contents could not be fetched with current access. No external document or Jira record was modified.

No real auth, SCA, KYC, settlement, vendor SDK, automatic provider fallback or operational circuit breaker is implemented. There is no card-data collection. A room ID is not authentication. The API is for trusted local rehearsals; do not expose it directly to the public internet. State and journal persist locally without application-level encryption or retention automation.

The existing [Kaizen site](https://meridian-money.kaizen.shared.atlassian-3p.com/) remains the standalone web simulation, not a hosted Java backend. See [connected rehearsal](docs/connected-rehearsal.md) and [verification](docs/verification.md).
