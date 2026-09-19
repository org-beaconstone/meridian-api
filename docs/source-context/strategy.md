**Meridian Bank** is a digital-first bank headquartered in New York, serving approximately **4 million customers** across the United Kingdom and the United States, with European corridors opening in FY26. We operate no branch network.

**Document owner**  

Office of the CTO

**Contributors**  

Payments Platform · Trust & Safety · Growth Engineering

**Review cadence**  

Half-yearly

**Status** APPROVED BY BOARD

**Approved** 

**European expansion** AT RISK

**Provider decision** OPEN — PAY-1187

---

## Where we are today

Every customer relationship we have runs through the app or the web experience. That makes our payment rails not a back-office concern but the product itself.

We are currently live with **two payment providers**, covering the UK and US only.

| Provider | Corridors | Live since | Role |
| --- | --- | --- | --- |
| Adyen | UK, US | FY23 Q2 | Default card acquiring, primary routing |
| Worldpay | UK, US | FY24 Q1 | Failover acquiring, legacy merchant accounts |

**No provider is contracted for Europe.** Neither of our current providers gives acceptable local payment method coverage, settlement currencies or acquiring licences in the FY26 target markets at our projected volume. Selection is open and unresolved.

## The FY26-28 ambition

Three commitments came out of the February board session.

### 1. Geographic expansion into Europe

We open our first European corridors during FY26. This is the largest single driver of work across Payments Platform and Trust & Safety this year, and the commitment most at risk.

The blocker is not appetite or engineering capacity. It is provider coverage. That work is open under **PAY-1187**.

### 2. Triple digital payment volume by FY28

Measured against the FY25 exit run rate. This is a volume target, not a revenue target, and it assumes the European corridors land on schedule.

Growth Engineering owns the conversion side. Payments Platform owns throughput and reliability. The two are not independent: a provider that improves conversion but degrades our reconciliation position is a net loss, and we have made that mistake before.

### 3. Become a genuine multi-provider payment platform

Today we describe ourselves as multi-provider. In practice we have two providers, one of which is effectively a failover, and a mobile SDK where provider configuration is hardcoded. That is not a platform. It is a pair of integrations with a shared interface in front of them.

By the end of FY27 we want:

* [ ] Any new provider onboarded through the documented [Payment Provider Expansion Playbook](https://beacon-stone.atlassian.net/wiki/spaces/SPT/pages/147947579/Payment+Provider+Expansion+Playbook) rather than a bespoke project
* [ ] No provider-specific branching outside the provider abstraction layer
* [ ] Provider configuration deployable without an app store release
* [ ] Routing decisions made on cost, corridor and health, not on which integration is less fragile

## What this means for each surface

| Repository | Stack | Owning team | FY26 focus | Jira |
| --- | --- | --- | --- | --- |
| `meridian-web` | React / TypeScript | Growth Engineering | Payment method selector that handles more than two providers cleanly; checkout UI able to present local European methods | **MWEB** |
| `meridian-api` | Java / Spring Boot | Payments Platform | Harden the pluggable provider interface, routing logic and webhook handling ahead of a third provider | **MAPI** |
| `meridian-mobile` | Swift + Kotlin | Payments Platform | Remove hardcoded two-provider configuration from the shared payment SDK | **MMOB** |

Cross-cutting payments work, including provider selection and compliance review, is tracked under **PAY**.

## Known risks

**Provider selection is on the critical path.** Everything in the European expansion sequences behind **PAY-1187**. Each week selection stays open is a week of integration work that cannot start.

Because provider configuration in `meridian-mobile` is hardcoded to two providers, mobile cannot follow web and API through a phased rollout. Until **MMOB-402** lands, assume any new provider is web and API only at launch.

Some provider-specific handling still sits outside the abstraction layer in `meridian-api`. Adding a third provider on top of that is how we end up with three bespoke paths instead of one interface. **MAPI-771** covers the cleanup and it needs to land *before*, not during, the next integration.

Trust & Safety has been explicit that a provider failing any hard no-go criterion is rejected outright, regardless of commercial pressure or timeline. See the [Payments Compliance and Regulatory Policy](https://beacon-stone.atlassian.net/wiki/spaces/SPT/pages/147980346/Payments+Compliance+and+Regulatory+Policy).

## Related documents

**Confluence**

- [Payment Provider Expansion Playbook](https://beacon-stone.atlassian.net/wiki/spaces/SPT/pages/147947579/Payment+Provider+Expansion+Playbook) — how we evaluate, score and onboard providers
- [Payments Compliance and Regulatory Policy](https://beacon-stone.atlassian.net/wiki/spaces/SPT/pages/147980346/Payments+Compliance+and+Regulatory+Policy) — the gates every provider must clear
- [API Integration Standards for Payment Flows](https://beacon-stone.atlassian.net/wiki/spaces/SPT/pages/147947597/API+Integration+Standards+for+Payment+Flows) — auth, retry, fallback and circuit breakers

**Working documents (Google Drive)**
[https://docs.google.com/document/d/1KeMKQyaUu-sLtFqwvm3cI8zoZo\_QwXe7yDvB9qK3n54/edit](https://docs.google.com/document/d/1KeMKQyaUu-sLtFqwvm3cI8zoZo_QwXe7yDvB9qK3n54/edit)<https://docs.google.com/document/d/1-gkUCrdTfqE2eKZgfTi83LD3y9sNkO0f2lf3oCodMYg/edit>
