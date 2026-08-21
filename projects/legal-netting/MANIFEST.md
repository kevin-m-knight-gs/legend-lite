# legal-netting

Layer 2, depends on **legal-core** and **exposure-agg** and nothing else. Package root
`legal_netting::`, prefixes `NET_` (tables), `Net_` (joins), `Net` (filters), `net` (set ids).

The netting set: the population one master agreement covers, and the unit exposure is measured
on. Its members, the netting opinion assessment that lets it net for capital at all, the
branch-by-branch coverage of that opinion, the capital treatment that results, and the
close-out amount the whole construction exists to produce.

The shape to know before using it, because every class is a consequence of it:

* **Netting is a property of a SET, and the set is defined by a contract.** Two trades net
  because they are governed by the same single agreement between the same two legal persons —
  not because they face the same name and not because they are the same product. So `NetSet`
  is the closure of a `legal_core::MasterAgreement`, and it is the grain everything downstream
  reads. It is keyed by its own id and *not* by `agreementId`, because an umbrella master
  carries several sets and an opinion carve-out splits one.
* **The set only nets FOR CAPITAL if a clean opinion covers it.** The opinion itself is
  upstream (`legal_core::NettingOpinion`, keyed on the triple jurisdiction × master form ×
  counterparty type). What this project adds is `NetOpinionAssessment` — the firm's own dated
  determination that a particular opinion reaches a particular set. Absent a current, clean,
  supportive assessment the set still exists and is still measured; it is measured **gross**,
  and `NetCapitalTreatment.recognisedAsNet` is where that shows.
* **`NetSet.counterpartyJurisdiction` is the counterparty's INSOLVENCY jurisdiction**, not the
  agreement's governing law. An English-law ISDA with a German bank is opined on under German
  insolvency law. Conflating the two is how a set gets netted that should not be.
* Sets do **not** net against each other. That is what `NetSetOffRight` is for, and it is why
  `NetCounterpartyRollup.sumOfSetNets` is a sum of set-level nets and never a net across sets.

## Exports

| element | kind | note |
| --- | --- | --- |
| `legal_netting::NetSet` | class | **the set** — closure of one `legal_core::MasterAgreement`; keyed `nettingSetId`; derived `isOpen()`, `nettableByContract()` |
| `legal_netting::NetSetMember` | class | keyed (set, exposure, **date**) — membership is a daily statement; `signedMarkToMarket` is signed; derived `isPayable()` |
| `legal_netting::NetEligibilityRule` | class | keyed `ruleCode`; the rule that decided a member's `isNettable`, held as data so the decision is attributable |
| `legal_netting::NetExclusion` | class | keyed (set, exclusionCode); a set-specific carve-out, the shape a qualified opinion actually takes; derived `isActive()` |
| `legal_netting::NetOpinionAssessment` | class | keyed (set, **assessedOn**), append-only; carries all three columns of the upstream opinion key; derived `givesCapitalRelief()` |
| `legal_netting::NetBranchCoverage` | class | keyed (set, branch) — a multibranch master needs an opinion per booking jurisdiction, and the weakest branch governs |
| `legal_netting::NetCapitalTreatment` | class | keyed (set, date); holds gross AND net because the benefit is the difference; derived `nettingBenefit()`; `counterpartyId` denormalised for the rollup |
| `legal_netting::NetCloseOutAmount` | class | keyed (set, determinedOn); the single net sum; derived `netTerminationAmount()` = closeOut + unpaids − collateral |
| `legal_netting::NetCloseOutComponent` | class | keyed (set, determinedOn, componentCode) — the breakdown that defends the number |
| `legal_netting::NetSetOffRight` | class | keyed `setOffId`; reaches ACROSS agreements, which is what distinguishes set-off from netting |
| `legal_netting::NetSetRun` | class | keyed `runId`; the batch that built the sets; derived `averageSetSize()` is `Float[1]` |
| `legal_netting::NetSetRollup` | class | **AGGREGATION** — over view `NET_SET_ROLLUP`, grouped (set, date); `totalGross` vs `netExposure` side by side |
| `legal_netting::NetCounterpartyRollup` | class | **AGGREGATION** — over view `NET_COUNTERPARTY_ROLLUP`, grouped (counterparty, date); a sum of set nets, never a net across sets |
| `legal_netting::NetSetMembers` | association | NetSet[1] `memberSet` ↔ NetSetMember[*] `members` |
| `legal_netting::NetSetAssessments` | association | NetSet[1] `assessedSet` ↔ NetOpinionAssessment[*] `opinionAssessments` |
| `legal_netting::NetSetBranches` | association | NetSet[1] `coveredSet` ↔ NetBranchCoverage[*] `branchCoverage` |
| `legal_netting::NetSetExclusions` | association | NetSet[1] `excludedSet` ↔ NetExclusion[*] `exclusions` |
| `legal_netting::NetSetTreatment` | association | NetSet[1] `treatedSet` ↔ NetCapitalTreatment[*] `capitalTreatments` |
| `legal_netting::NetSetCloseOut` | association | NetSet[1] `closedOutSet` ↔ NetCloseOutAmount[*] `closeOutAmounts` |
| `legal_netting::NetCloseOutBreakdown` | association | NetCloseOutAmount[1] `closeOutAmount` ↔ NetCloseOutComponent[*] `components`; two-column join |
| `legal_netting::NetSetOffRights` | association | NetSet[1] `setOffSet` ↔ NetSetOffRight[*] `setOffRights` |
| `legal_netting::NetMemberRule` | association | NetEligibilityRule[0..1] `eligibilityRule` ↔ NetSetMember[*] `ruledMembers` |
| `legal_netting::NetRunSets` | association | NetSetRun[1] `buildingRun` ↔ NetSet[*] `builtSets` |
| `legal_netting::NetSetRollups` | association | NetSet[1] `rolledUpSet` ↔ NetSetRollup[*] `rollups` — set to its own aggregate |
| `legal_netting::NetSetAgreement` | association | **crosses to legal-core**: MasterAgreement[1] `nettingAgreement` ↔ NetSet[*] `nettingSets` |
| `legal_netting::NetAssessedOpinion` | association | **crosses to legal-core**: NettingOpinion[0..1] `appliedOpinion` ↔ NetOpinionAssessment[*] `setAssessments`; **three-column** join |
| `legal_netting::NetCloseOutBasis` | association | **crosses to legal-core**: CloseOutTerms[0..1] `closeOutBasis` ↔ NetCloseOutAmount[*] `determinedCloseOuts` |
| `legal_netting::NetSetNetExposure` | association | **crosses to exposure-agg**: NetSet[0..1] `governingLegalSet` ↔ ExaNetExposure[*] `nettedExposures` |
| `legal_netting::NetMemberExposureLine` | association | **crosses to exposure-agg**: ExaExposureLine[0..1] `exposureLine` ↔ NetSetMember[*] `nettingSetMembers`; two-column join |
| `legal_netting::Store` | store | `include legal_core::Store` + `include exposure_agg::Store`; 11 tables `NET_*`, 2 views, 16 joins `Net_*`, filters `NetOpenSets`, `NetNettableMembers` |
| `legal_netting::Mapping` | mapping | `include legal_core::Mapping` + `include exposure_agg::Mapping`; 13 class sets `net*`, 16 association mappings; `~primaryKey` on every set |

## Properties added to upstream classes

Downstream projects see these on the upstream classes once legal-netting is in the graph:

| class | property | type |
| --- | --- | --- |
| `legal_core::MasterAgreement` | `nettingSets` | `legal_netting::NetSet[*]` |
| `legal_core::NettingOpinion` | `setAssessments` | `legal_netting::NetOpinionAssessment[*]` |
| `legal_core::CloseOutTerms` | `determinedCloseOuts` | `legal_netting::NetCloseOutAmount[*]` |
| `exposure_agg::ExaNetExposure` | `governingLegalSet` | `legal_netting::NetSet[0..1]` |
| `exposure_agg::ExaExposureLine` | `nettingSetMembers` | `legal_netting::NetSetMember[*]` |

## Tables and views

`NET_SET`, `NET_SET_MEMBER`\*\*, `NET_ELIGIBILITY_RULE`, `NET_EXCLUSION`\*,
`NET_OPINION_ASSESSMENT`\*, `NET_BRANCH_COVERAGE`\*, `NET_CAPITAL_TREATMENT`\*,
`NET_CLOSE_OUT_AMOUNT`\*, `NET_CLOSE_OUT_COMPONENT`\*\*, `NET_SET_OFF_RIGHT`, `NET_SET_RUN`
(\* = composite primary key, \*\* = three-column primary key)

Views (Legend views, no DDL, nothing seeds them): `NET_SET_ROLLUP` grouped
(NETTING_SET_ID, COB_DATE) over `NET_SET_MEMBER`; `NET_COUNTERPARTY_ROLLUP` grouped
(COUNTERPARTY_ID, COB_DATE) over `NET_CAPITAL_TREATMENT`.

## Joins

Inside legal-netting: `Net_Set_Member`, `Net_Set_Assessment`, `Net_Set_Branch`,
`Net_Set_Exclusion`, `Net_Set_Treatment`, `Net_Set_CloseOut`, `Net_Set_SetOff`,
`Net_Rule_Member`, `Net_Run_Set`, `Net_Set_Rollup`, `Net_CloseOut_Component` (two-column).

Into legal-core: `Net_Agreement_Set`, `Net_Opinion_Assessment` (**three-column**),
`Net_CloseOut_Terms`.

Into exposure-agg: `Net_Set_NetExposure`, `Net_Member_ExposureLine` (two-column).

## Set ids

`netSet`, `netSetMember`, `netEligibilityRule`, `netExclusion`, `netOpinionAssessment`,
`netBranchCoverage`, `netCapitalTreatment`, `netCloseOutAmount`, `netCloseOutComponent`,
`netSetOffRight`, `netSetRun`, `netSetRollup`, `netCounterpartyRollup`

## Notes for downstream

- Every set id above is explicit, so the default ids (`legal_netting_NetSet`) do **not** exist.
  An `extends [...]` or a cross-project `AssociationMapping` must name the `net*` id.
- The five cross-boundary joins are declared in `legal_netting::Store`, which is why that store
  includes both dependency stores. A downstream store that wants them should include
  `legal_netting::Store` rather than redeclare them. Note that this store sits at the bottom of
  a **diamond**: `core_party::Store` arrives through `legal_core::Store` and again through
  `exposure_agg::Store`'s own `credit_core`/`collateral_core` includes, and it resolves once.
- Filters available: `NetOpenSets` (`CLOSED_DATE is null`), `NetNettableMembers`
  (`ELIGIBILITY_RULE_CODE is not null`). Declared for downstream use; nothing here maps a
  narrowed set, because `NetSetRollup` has to see the gross population.
- Derived properties available: `NetSet.isOpen()`, `NetSet.nettableByContract()`,
  `NetSetMember.isPayable()`, `NetExclusion.isActive()`,
  `NetOpinionAssessment.givesCapitalRelief()`, `NetCapitalTreatment.nettingBenefit()`,
  `NetCloseOutAmount.netTerminationAmount()`, `NetSetRun.averageSetSize()`.
- **`nettableByContract()` is not the capital answer.** It reads one column of the agreement.
  The capital answer is `NetCapitalTreatment.recognisedAsNet`, which is the outcome of a
  current clean assessment, no live exclusion, and every booking branch covered. There is
  deliberately no `NetSet.isNettable` property, because it would have to assert all four.
- `exposure_agg::ExaNettingSet` and `legal_netting::NetSet` are **different classes about the
  same id**. The first is the measurement-side grouping key; this one is the contract it comes
  from, with the opinion and the close-out attached. They meet on `nettingSetId` through
  `NetSetNetExposure` and neither restates the other's numbers.
- `NetCapitalTreatment` holds gross and net rather than one and a derivation, because a
  supervisor asks for the difference directly and a derived answer cannot be tied out.
- No `###Data`, no Runtime, no seeded rows.
