# Meridian API

- This is a fictional rehearsal bank. Never send real payments or store financial credentials.
- Java 21 / Spring Boot. Provider port has Adyen and Worldpay only; no third-provider references.
- Backend is authoritative in connected mode. Amounts are integer GBP pence. Preserve the web contract.
- Isolate state by X-Rehearsal-Session. No authentication claim: session ID isolates synthetic demo data only.
- Idempotent payment writes, synchronized per session; never retry or fall back to another provider after an ambiguous outcome.
- All webhooks are simulation-only with signed test events; do not claim real vendor integration.
- Test API contracts, validation, concurrency, replay handling and client errors.
- Do not modify sibling apps or secrets. Source context URLs are provided in docs/context.md.
