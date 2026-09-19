# Connected implementation verification

Verified 19 September 2026 on macOS, Java21, Node26 and Swift6.2.3.

- `mvn verify`: **30 backend tests**. Includes raw state contract, payment idempotency and payload mismatch, room isolation, validation, signed webhook completion/replay, concurrency, pending reservations and file-database reopen persistence.
- `meridian-web npm run check`: lint, TypeScript, production build, **132 domain/API-client tests**. All **10 original production browser tests** pass, including storage resilience and automated desktop/mobile accessibility.
- Connected Playwright: web payment updates mobile; mobile bank payment updates web; budget edits propagate; room switch isolates state; audit events exist; no page/console errors; mobile no horizontal overflow.
- Swift: `swift build` compiles SDK and actual SwiftUI desktop executable. `swift run MeridianSDKChecks`: **20 executable assertions**. `MeridianLiveChecks` verifies real Java HTTP transport, payment, same-key duplicate, pending response and reset.
- Kotlin: Maven compiles actual SDK sources, **22 tests** including a local HTTP server transport test for headers and pending202 response handling. `LiveChecksKt` also passed against the real Java API with bank payment, duplicate-key retry, pending response and reset.
- Mobile browser companion: **19 amount-parser tests** and a connected mobile UI smoke test pass.
- Unified `node scripts/rehearsal.mjs` launcher verified with custom free ports; the cross-client browser test then passed against those launched services.

## Not verified or not implemented

- No iOS simulator/device or Android APK build: full Xcode/Android SDK unavailable. Android Compose source and iOS XcodeGen definition are supplied, not device-certified.
- Docker Compose validated structurally but containers were not started because Docker daemon was unavailable. Java/Vite processes were used for the combined browser test.
- No real payment-provider integration, production auth, SCA, KYC, automatic cross-provider fallback or operational circuit breakers.
- Existing Kaizen deployment stays standalone. Java API is not claimed to run there.
- Google working docs could not be read through current access. Four supplied Confluence pages were read; six existing sources are linked, no duplicates published.
