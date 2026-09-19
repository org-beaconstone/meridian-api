The agreed route for evaluating, scoring and onboarding a new payment provider at **Meridian Bank**. If a provider has not been through these phases in order, it does not carry production traffic. That applies to pilots run by Growth Engineering as well.

**Owner**  

Payments Platform

**Contributors**  

Trust & Safety · Growth Engineering

**Review cadence**  

Quarterly

**Status** ACTIVE

**Live providers** 2

**European provider** NOT SELECTED

**Mobile readiness** BLOCKED — MMOB-402

---

## Why this exists

We are committed to becoming a genuine multi-provider platform under the [Company Strategy FY26-28](https://beacon-stone.atlassian.net/wiki/spaces/SPT/pages/148209689/Meridian+Bank+Company+Strategy+FY26-28), and our last two onboardings were run as bespoke projects with no shared definition of done.
[https://docs.google.com/document/d/1KeMKQyaUu-sLtFqwvm3cI8zoZo\_QwXe7yDvB9qK3n54/edit](https://docs.google.com/document/d/1KeMKQyaUu-sLtFqwvm3cI8zoZo_QwXe7yDvB9qK3n54/edit)

## Current provider footprint

| Provider | Corridors | Live since | Role | Status |
| --- | --- | --- | --- | --- |
| Adyen | UK, US | FY23 Q2 | Default card acquiring, primary routing | LIVE |
| Worldpay | UK, US | FY24 Q1 | Failover acquiring, legacy merchant accounts | LIVE |
| *European corridors* | EU (FY26 target) | — | *No provider contracted* | GAP |

Both live providers sit behind the provider abstraction layer in `meridian-web`, the pluggable provider interface in `meridian-api`, and the shared payment SDK in `meridian-mobile`.

### The open gap

We have **no provider contracted for the European corridors** in the FY26 expansion plan. Neither Adyen nor Worldpay gives us acceptable local payment method coverage in the target markets at our projected volume. Selection is open and unresolved under **PAY-1187**.

Mobile is further behind than web and API. Provider configuration in `meridian-mobile` is currently hardcoded to the two providers above, so adding a third is a release, not a config change. Tracked as **MMOB-402**.

## Vendor scoring

Candidates are scored out of 100.

- **Below 70** — rejected
- **70 to 79** — requires a written exception signed by the Payments Platform lead and Trust & Safety
- **80 and above** — proceeds to Phase 1

| Category | Weight | What we are actually asking |
| --- | --- | --- |
| Regulatory standing | **25** | Clears every gate in the [Payments Compliance and Regulatory Policy](https://beacon-stone.atlassian.net/wiki/spaces/SPT/pages/147980346/Payments+Compliance+and+Regulatory+Policy), including SOC 2 Type II. Pass/fail before anything else is scored |
| Market coverage | **20** | Local payment methods, settlement currencies, acquiring licences in the target corridor |
| Technical fit | **20** | Fits the existing provider interface without a bespoke path. Webhook model, idempotency, reconciliation exports |
| Commercials | **15** | Blended cost per transaction at projected FY27 volume, not list price |
| Reliability | **10** | Published uptime, incident history, status page quality, real degradation behaviour |
| Support model | **10** | Named technical contact, escalation path, sandbox stability |

## Rollout phases

Each phase has an exit gate owned by a named person. The gate is a decision, not a date.

### Phase 1 — Sandbox

**Owner** Payments Platform · **Typical duration** 3-4 weeks

* [ ] Provider interface implemented in `meridian-api` behind a feature flag, default off
* [ ] No customer traffic, no production credentials, no PAN data
* [ ] Contract tests against the provider sandbox including deliberate failure injection
* [ ] Webhook signature verification and replay handling proven
* [ ] Reconciliation export parsed end to end

**Exit gate.** Payments Platform confirms the provider fits the existing interface. If it needs a bespoke path outside the abstraction, stop and re-score technical fit. See [API Integration Standards for Payment Flows](https://beacon-stone.atlassian.net/wiki/spaces/SPT/pages/147947597/API+Integration+Standards+for+Payment+Flows).

### Phase 2 — Pilot

**Owner** Payments Platform with Growth Engineering · **Typical duration** 6-8 weeks

* [ ] Capped live traffic starting at 1% of a single corridor
* [ ] Real money, real settlement, real reconciliation, reviewed daily for the first fortnight
* [ ] Trust & Safety monitors fraud signal quality and chargeback handling
* [ ] Checkout changes in `meridian-web` behind the same flag (MWEB)
* [ ] Mobile stays off unless MMOB-402 has landed

**Exit gate.** Joint sign-off from Payments Platform and Trust & Safety, with 30 consecutive days of clean reconciliation and no unexplained settlement variance.

### Phase 3 — Full

**Owner** Payments Platform

* [ ] Staged ramp: 1% → 10% → 50% → routing-eligible
* [ ] Provider enters routing logic in `meridian-api` as a first-class option (MAPI)
* [ ] Failover pairing agreed and tested. A new provider is never both primary and sole option in a corridor
* [ ] Runbook published, on-call trained, dashboards live

**Exit gate.** Payments Platform lead confirms the provider is in routing and the runbook has been exercised in a game day.

## Lessons we keep relearning

A sandbox that does not reproduce production failure modes is close to useless. Ask for forced-decline and forced-timeout scenarios in writing during Phase 1.

We twice reached pilot before discovering the settlement file could not be mapped to our ledger. Reconciliation is now a Phase 1 exit item, not a Phase 3 discovery.

Until **MMOB-402** lands, assume mobile is a phase behind web and API. Do not commit launch dates that assume parity.

Third-party provider marks in checkout are governed by the brand guidelines. Growth Engineering does not place them ad hoc; raise it on **MWEB** with a design review.

## Open work

| Reference | Summary | Team | Status |
| --- | --- | --- | --- |
| **PAY-1187** | European corridor provider selection | Payments Platform | OPEN |
| **PAY-1204** | Compliance review sub-task for the above | Trust & Safety | BLOCKED |
| **MAPI-771** | Provider interface hardening ahead of a third provider | Payments Platform | IN PROGRESS |
| **MMOB-402** | Remove hardcoded provider config from the mobile SDK | Payments Platform | NOT STARTED |
| **MWEB-318** | Payment method selector supporting more than two providers | Growth Engineering | IN PROGRESS |
