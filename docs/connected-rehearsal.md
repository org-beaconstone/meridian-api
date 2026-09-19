# Connected Meridian rehearsal

## Start

Check out `meridian-api`, `meridian-web` and `meridian-mobile` as sibling folders. From `meridian-api`, run `node scripts/rehearsal.mjs`. Ports default to API 8080, web 5175, mobile preview 5176. `API_PORT`, `WEB_PORT` and `MOBILE_PORT` can override occupied ports. All processes stop together on Ctrl+C.

Alternatively, `docker compose up --build` starts the supplied three-container topology. Docker orchestration is provided, but was not executed on the authoring machine because Docker's daemon was unavailable.

## Five-minute story

1. Open the web and mobile browser companion side by side. Both show **Connected API**, room `meridian-rehearsal`, and £12,480.50 after a room reset.
2. Web: Make a payment, Northline Studio, `25.99`, Debit card (Adyen). Review and confirm. Mobile refreshes to £12,454.51.
3. Mobile: Make a payment, Northline Studio, `10.00`, Bank payment (Worldpay). Review and confirm. Web refreshes to £12,444.51.
4. Mobile Settings: set Shopping's monthly limit to £1,500. Web Budgets refreshes to the same value.
5. Change mobile to a different room. Its fresh balance is £12,480.50 while web retains the original room state.
6. Rejoin the same room to share changes again. A reset affects every client in that room, with a confirmation prompt.

Payment outcomes are selected in rehearsal controls, not encoded as magic amounts. There is no provider fallback hidden behind a particular amount.

## Native surfaces

Swift: `cd ../meridian-mobile/ios && swift run MeridianDesktop` starts the compiled native SwiftUI desktop rehearsal. Point it at `http://127.0.0.1:8080/api/v1` and the same room. The same view source has an iOS XcodeGen definition. iOS device builds were not run.

Android: native Compose sources live in `android/app`; the shared Kotlin SDK is independently Maven-tested. Import the Gradle project with Android Studio and API 34. Emulator base URL is `http://10.0.2.2:8080/api/v1`. No APK/device build was verified on the authoring machine.

The phone-shaped browser companion is explicitly **not** the native binary. It exists so the complete flow can be rehearsed without SDK/emulator installation.

## Planner gap

The existing two-provider registry is the baseline. Mobile method configuration intentionally remains hardcoded to the same two. The new European provider is neither selected nor implemented. Use [the six source documents](context.md) and the three code repositories together for the planning exercise.

## Hosting

The established Kaizen link continues to serve standalone Meridian Money. The connected demo is local/Compose, not falsely presented as Java running in Kaizen. Java hosting needs an approved runtime and access controls before a durable shared connected URL can be offered.
