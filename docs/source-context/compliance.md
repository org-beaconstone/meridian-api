**Binding policy.** Applies to every payment provider, processor or payment-adjacent vendor touching customer funds or cardholder data. Binding on Payments Platform, Growth Engineering and any team integrating a provider into `meridian-web`, `meridian-api` or `meridian-mobile`. This is not advisory.

**Owner**  

Trust & Safety

**Co-owner**  

Payments Platform (technical controls)

**Review cadence**  

Quarterly, or immediately after a material regulatory change

**Status** BINDING

**UK / US corridors** CLEARED

**European corridors** NOT CLEARED

**Blocking ticket** PAY-1204

---

## Scope

Meridian Bank serves approximately 4 million customers across the UK and US and is opening European corridors during FY26 under the [Company Strategy FY26-28](https://beacon-stone.atlassian.net/wiki/spaces/SPT/pages/148209689/Meridian+Bank+Company+Strategy+FY26-28). That expansion moves us into regulatory territory we were not previously exposed to. This is the current gate set.
<https://docs.google.com/document/d/1-gkUCrdTfqE2eKZgfTi83LD3y9sNkO0f2lf3oCodMYg/edit>

## Hard no-go criteria

A provider failing **any** of the following is rejected. These are not scored, weighted or traded against commercials. Neither Trust & Safety nor the Payments Platform lead can waive them.

| # | Criterion | Detail |
| --- | --- | --- |
| 1 | **No SOC 2 Type II report** | Type I is not acceptable. Must be current within 12 months and cover the services we intend to use, not a sibling product |
| 2 | **No PCI-DSS Level 1 Service Provider attestation** | We need the AOC naming the contracting entity, not a logo on a marketing page |
| 3 | **No PSD2 / SCA capability** | In any corridor where SCA applies, including 3DS2 and correct exemption handling |
| 4 | **Cardholder data landing in Meridian systems** | Our model is tokenised. A provider requiring raw PAN in our infrastructure is out |
| 5 | **No acceptable data residency position** | For the target corridor |
| 6 | **No documented breach notification commitment** | With a defined maximum window |
| 7 | **Unresolved regulatory action** | In a corridor we intend to operate in |

## PCI-DSS

We run a tokenised model specifically to contain our own PCI scope. It only works if providers hold up their end.

- Providers must be **PCI-DSS Level 1 Service Providers** with a current Attestation of Compliance
- Card capture happens in provider-hosted fields or provider SDKs. `meridian-web` never handles raw PAN and the checkout UI must not introduce a path where it could
- The shared SDK in `meridian-mobile` follows the same rule. A provider requiring a custom native card form is a scope expansion needing Trust & Safety review before Phase 1
- Annual re-attestation is tracked. A lapsed AOC moves a live provider to **failover-only** until resolved

## PSD2 and Strong Customer Authentication

This is where European expansion changes our obligations most, and where the current two-provider footprint is weakest.

- Full **SCA** support for in-scope European transactions, including 3DS2 challenge and frictionless flows
- Support for standard **exemption** types, returning enough data for us to evidence why an exemption was applied. Exemptions we cannot evidence are exemptions we will not use
- **Transaction Risk Analysis** handling must be explicit. If a provider applies TRA on our behalf we need the fraud rate reporting that justifies it
- Soft declines must be distinguishable from hard declines in the provider response

## Data residency

- **UK and EU customer payment data stays in UK or EU processing regions.** A provider that can only process in a US region is not eligible for those corridors
- Cross-border transfers need a documented lawful basis. "The provider is large and well known" is not a lawful basis
- Sub-processors must be disclosed in writing *before* Phase 1 sandbox, not discovered during pilot
- Backup and disaster recovery regions count as processing regions. This has already ruled out two candidates

## KYC and AML

Must meet or exceed our own standards. We inherit exposure from a provider's weak onboarding.

Applied continuously, not only at onboarding.

Paths defined and contactable, with a named human, before go-live.

Data must be exportable into our own monitoring. A provider that will not share the underlying data is asking us to accept their judgement, which we cannot do under our own obligations.

Customer identity data shared with a provider is minimised to what the payment flow genuinely requires.

## Current provider position

| Provider | PCI-DSS L1 | SOC 2 Type II | SCA / 3DS2 | Corridors cleared |
| --- | --- | --- | --- | --- |
| Adyen | YES | YES | YES | UK, US |
| Worldpay | YES | YES | YES | UK, US |
| *European corridors* | NO PROVIDER ASSESSED |  |  |  |

**European corridors are not covered by either provider under this policy.** Compliance clearance for the FY26 European expansion is blocked on provider selection, which remains open. Trust & Safety has asked Payments Platform to bring candidates through the [Payment Provider Expansion Playbook](https://beacon-stone.atlassian.net/wiki/spaces/SPT/pages/147947579/Payment+Provider+Expansion+Playbook) scoring before any commercial conversation advances.

Tracked as **PAY-1187**, compliance review sub-task **PAY-1204**.

## Exceptions
