# Existing Meridian source context

The user supplied these existing sources. No duplicate pages or documents were created, and none were edited. They describe a fictional demo workspace, not assertions about actual provider capability or regulatory clearance.

## Read from Confluence

1. [Company strategy FY26-28](https://beacon-stone.atlassian.net/wiki/spaces/SPT/pages/148209689): New York, approximately 4M UK/US customers; European expansion; triple digital payment volume against the **FY25 exit run rate**. Adyen primary and Worldpay failover/legacy acquiring are the story's current footprint.
2. [Payment Provider Expansion Playbook](https://beacon-stone.atlassian.net/wiki/spaces/SPT/pages/147947579): rubric weights 25 regulatory, 20 coverage, 20 technical fit, 15 commercials, 10 reliability, 10 support. Below 70 rejected; 70-79 requires a written exception; 80+ proceeds to sandbox. Hard compliance no-go gates cannot be traded for commercial scores. Sandbox > pilot > full.
3. [Payments Compliance and Regulatory Policy](https://beacon-stone.atlassian.net/wiki/spaces/SPT/pages/147980346): SOC 2 Type II, PCI-DSS L1 AOC, applicable PSD2/SCA, no raw PAN in Meridian, residency, incident commitments and regulatory action gates. European clearance remains blocked in the story.
4. [API Integration Standards](https://beacon-stone.atlassian.net/wiki/spaces/SPT/pages/147947597): provider secrets only in the backend; persisted intent idempotency before provider calls; signed/replay-safe callbacks; bounded retries and per-provider/corridor breakers; no fallback until confirmed no capture.

Source snapshots are in `source-context/` for offline reference. The online pages are authoritative if they change.

## Supplied Google working documents

- [Provider expansion working draft](https://docs.google.com/document/d/1KeMKQyaUu-sLtFqwvm3cI8zoZo_QwXe7yDvB9qK3n54/edit)
- [Compliance working draft](https://docs.google.com/document/d/1-gkUCrdTfqE2eKZgfTi83LD3y9sNkO0f2lf3oCodMYg/edit)

These links were provided and cross-referenced by the Confluence pages. Google document content was not readable through available authentication; no claims above are attributed to unverified Google text. The documents already provide the third-party context surface for TWG, but connector indexing/readability for the rehearsal identity still needs confirmation.

## Teams and issue references

Payments Platform, Trust & Safety and Growth Engineering appear in the source pages. PAY-1187, PAY-1204, MAPI-771, MWEB-318 and MMOB-402 are **documented references**, not independently queried issue statuses in this implementation pass. The PAY/MAPI/MWEB/MMOB project keys provide the cross-repo story.

## Implementation versus story

The browser rehearsal maps card to Adyen and bank payment to Worldpay for a simple visible two-choice flow. It does not claim to implement the story's production acquiring/failover arrangements. Adapters simulate outcomes. No real authentication, vendor requests, automatic fallback, SCA or circuit breakers are claimed. Native configuration intentionally still hardcodes two providers, preserving the mobile gap.
