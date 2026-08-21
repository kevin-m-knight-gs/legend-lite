# collateral-core

Layer 1. Depends on **core-party** and **core-instrument**, and on nothing else. Package root
`collateral_core::`, prefixes `COL_` (tables), `Col_` (joins), `Col` (filters + class names),
`col` (set ids).

What is pledged under a collateral agreement, and whether it is allowed to be there.

**The shape to know before using it: eligibility is a DISJUNCTION.** A Schedule A line is
negotiated against a named ISSUER *or* against an ASSET CLASS, never both on one line, but a
piece of collateral is eligible if it matches on *either* axis. So the join is an unqualified
`or`:

    Join Col_EligibilityRule(COL_COLLATERAL.ISSUER_ID = COL_ELIGIBILITY_RULE.ELIGIBLE_ISSUER_ID or COL_COLLATERAL.ASSET_CLASS = COL_ELIGIBILITY_RULE.ELIGIBLE_ASSET_CLASS)

An `or` matches EVERY rule that applies — a French government bond matches the "French
Republic" issuer line *and* the "government bonds" class line — so
`collateral_core::ColEligibilityMatch` is to-**many on both ends**. Downstream code that
expects one rule per piece is wrong. `Col_ConcentrationScope` is the same disjunction on the
limit side, with the same consequence.

## Exports

| element | kind | note |
| --- | --- | --- |
| collateral_core::ColCollateralAgreement | class | the CSA: two entities, thresholds, MTA, independent amount; points at both schedules |
| collateral_core::ColEligibilitySchedule | class | Schedule A itself — named, versioned, reused across many agreements |
| collateral_core::ColEligibilityRule | class | **the disjunctive class**: exactly one of `eligibleIssuerId` / `eligibleAssetClass` is set; `ruleBasis` records which |
| collateral_core::ColConcentrationLimit | class | a cap scoped the same two ways (`limitIssuerId` / `limitAssetClass`); portfolio-level, not piece-level |
| collateral_core::ColHaircutSchedule | class | the named haircut grid, regulatory or house |
| collateral_core::ColHaircutBand | class | one cell of the grid; keyed (haircutScheduleId, assetClass, ratingBand) |
| collateral_core::ColCollateralAccount | class | segregated / omnibus / triparty account at a custodian |
| collateral_core::ColCollateral | class | one posted security lot; carries `issuerId` and `assetClass` as OWN columns — they are the left side of both or-joins |
| collateral_core::ColCollateralValuation | class | the daily mark; keyed (collateralId, valuationDate) |
| collateral_core::ColMarginCall | class | exposure vs collateral held vs the amount to move; VM and IM are separate rows |
| collateral_core::ColAllocation | class | collateral against a call; keyed (callId, collateralId) — neither id alone is the grain |
| collateral_core::ColSubstitution | class | one piece pulled, another put in; two joins over one table |
| collateral_core::ColEligibilityMatch | association | **the or-join**: ColCollateral[*] `matchingRules` ↔ ColEligibilityRule[*] `eligibleCollateral` — to-many BOTH ways |
| collateral_core::ColConcentrationScope | association | **or-join**: ColCollateral[*] `applicableLimits` ↔ ColConcentrationLimit[*] `limitedCollateral` |
| collateral_core::ColCollateralHaircutBands | association | ColCollateral[*] `candidateBands` ↔ ColHaircutBand[*] `bandedCollateral` (asset-class axis alone) |
| collateral_core::ColAgreementSchedule | association | ColCollateralAgreement[*] `eligibilitySchedule`[0..1] ↔ ColEligibilitySchedule `agreements`[*] |
| collateral_core::ColScheduleRules | association | ColEligibilitySchedule[1] `ruleSchedule` ↔ ColEligibilityRule[*] `rules` |
| collateral_core::ColScheduleLimits | association | ColEligibilitySchedule[1] `limitSchedule` ↔ ColConcentrationLimit[*] `concentrationLimits` |
| collateral_core::ColAgreementHaircutSchedule | association | ColHaircutSchedule[0..1] `haircutSchedule` ↔ ColCollateralAgreement[*] `haircutAgreements` |
| collateral_core::ColHaircutBands | association | ColHaircutSchedule[1] `bandSchedule` ↔ ColHaircutBand[*] `bands` |
| collateral_core::ColAgreementCollateral | association | ColCollateralAgreement[1] `agreement` ↔ ColCollateral[*] `collateral` |
| collateral_core::ColAgreementAccounts | association | ColCollateralAgreement[1] `accountAgreement` ↔ ColCollateralAccount[*] `accounts` |
| collateral_core::ColAccountCollateral | association | ColCollateralAccount[0..1] `account` ↔ ColCollateral[*] `accountCollateral` |
| collateral_core::ColCollateralValuations | association | ColCollateral[1] `valuedCollateral` ↔ ColCollateralValuation[*] `valuations` |
| collateral_core::ColAgreementCalls | association | ColCollateralAgreement[1] `callAgreement` ↔ ColMarginCall[*] `marginCalls` |
| collateral_core::ColCallAllocations | association | ColMarginCall[1] `marginCall` ↔ ColAllocation[*] `allocations` |
| collateral_core::ColCollateralAllocations | association | ColCollateral[1] `allocatedCollateral` ↔ ColAllocation[*] `collateralAllocations` |
| collateral_core::ColAgreementSubstitutions | association | ColCollateralAgreement[1] `substitutionAgreement` ↔ ColSubstitution[*] `substitutions` |
| collateral_core::ColOutgoingSubstitutions | association | ColCollateral[1] `outgoingCollateral` ↔ ColSubstitution[*] `substitutionsOut` |
| collateral_core::ColIncomingSubstitutions | association | ColCollateral[0..1] `incomingCollateral` ↔ ColSubstitution[*] `substitutionsIn` |
| collateral_core::ColCollateralInstrument | association | **cross-project**: core_instrument::Instrument[0..1] `instrument` ↔ ColCollateral[*] `collateralPieces` |
| collateral_core::ColCollateralIssuer | association | **cross-project**: core_party::LegalEntity[0..1] `issuer` ↔ ColCollateral[*] `issuedCollateral` |
| collateral_core::ColRuleEligibleIssuer | association | **cross-project**: core_party::LegalEntity[0..1] `eligibleIssuer` ↔ ColEligibilityRule[*] `issuerRules` (axis one) |
| collateral_core::ColRuleAssetClass | association | **cross-project**: core_instrument::AssetClassDefinition[0..1] `eligibleAssetClassDefinition` ↔ ColEligibilityRule[*] `assetClassRules` (axis two) |
| collateral_core::ColAgreementPrincipal | association | **cross-project**: core_party::LegalEntity[1] `principalEntity` ↔ ColCollateralAgreement[*] `principalAgreements` |
| collateral_core::ColAgreementCounterparty | association | **cross-project**: core_party::LegalEntity[1] `counterpartyEntity` ↔ ColCollateralAgreement[*] `counterpartyAgreements` |
| collateral_core::ColAccountCustodian | association | **cross-project**: core_party::LegalEntity[0..1] `custodian` ↔ ColCollateralAccount[*] `custodiedAccounts` |
| collateral_core::Store | store | 12 tables `COL_*`, 25 joins `Col_*` (2 disjunctive), 4 filters `Col*`; includes `core_party::Store` and `core_instrument::Store` |
| collateral_core::Mapping | mapping | 12 class sets `col*`, 25 association mappings; includes `core_party::Mapping` and `core_instrument::Mapping` |

## Tables

`COL_AGREEMENT`, `COL_ELIGIBILITY_SCHEDULE`, `COL_ELIGIBILITY_RULE`,
`COL_CONCENTRATION_LIMIT`, `COL_HAIRCUT_SCHEDULE`, `COL_HAIRCUT_BAND`\*, `COL_ACCOUNT`,
`COL_COLLATERAL`, `COL_VALUATION`\*, `COL_MARGIN_CALL`, `COL_ALLOCATION`\*,
`COL_SUBSTITUTION`  (\* = composite primary key)

## Joins

Disjunctive: **`Col_EligibilityRule`**, **`Col_ConcentrationScope`**.

Key joins: `Col_AgreementSchedule`, `Col_ScheduleRule`, `Col_ScheduleLimit`,
`Col_AgreementHaircutSchedule`, `Col_HaircutBand`, `Col_AgreementCollateral`,
`Col_AgreementAccount`, `Col_AccountCollateral`, `Col_CollateralValuation`,
`Col_AgreementCall`, `Col_CallAllocation`, `Col_CollateralAllocation`,
`Col_AgreementSubstitution`, `Col_OutgoingCollateral`, `Col_IncomingCollateral`,
`Col_CollateralHaircutBand`.

Cross-project: `Col_CollateralInstrument`, `Col_RuleAssetClass` (→ `CI_ASSET_CLASS.CODE`),
`Col_CollateralIssuer`, `Col_RuleEligibleIssuer`, `Col_AgreementPrincipal`,
`Col_AgreementCounterparty`, `Col_AccountCustodian` (all → `CP_LEGAL_ENTITY.ENTITY_ID`).

## Set ids (extend or name these; they are a GLOBAL namespace)

`colAgreement`, `colEligibilitySchedule`, `colEligibilityRule`, `colConcentrationLimit`,
`colHaircutSchedule`, `colHaircutBand`, `colAccount`, `colCollateral`, `colValuation`,
`colMarginCall`, `colAllocation`, `colSubstitution`

## Filters

`ColHeldCollateral` (RETURN_DATE is null), `ColOpenMarginCalls` (SETTLED_DATE is null),
`ColLiveAgreements` (TERMINATION_DATE is null), `ColCurrentRules` (EXPIRY_DATE is null).
All null tests — a `Filter` will not take a boolean literal.

## Notes for downstream

- **The or-join ends are to-many on both sides.** `piece.matchingRules` returns every rule
  that hits on either axis, and `rule.eligibleCollateral` returns every piece. Take the
  tightest `maxHaircutPct` / cap across the set; do not assume one.
- A rule with `isExclusion` true carves something OUT. The or-join returns it like any other
  match, so exclusions have to be applied after the join, not by it.
- `ColCollateral.issuerId` and `.assetClass` are denormalised onto the collateral on purpose:
  a join condition cannot hop through `CI_INSTRUMENT` to reach the issuer, so both axes of the
  disjunction must be columns on `COL_COLLATERAL`.
- Cross-project association mappings here name the dependencies' explicit set ids —
  `cpLegalEntity`, `ciBase`, `ciAssetClassDefinition`. Their default ids do not exist.
- `ColEligibilityRule.isIssuerRule()` / `.isAssetClassRule()` / `.isUnscoped()` split the
  disjunction on a single rule; `isUnscoped()` finds rows that match nothing, which is a data
  error worth querying for.
- Other derived properties: `ColCollateralAgreement.isTerminated()`/`.callTrigger()`,
  `ColCollateral.isHeld()`/`.unitValue()`, `ColMarginCall.isOpen()`/`.coveragePct()`,
  `ColCollateralValuation.haircutAmount()`, `ColHaircutBand.valueRetainedPct()`. Every one
  that divides is `Float[1]`, because `/` widens.
- No `###Data`, no Runtime, no seeded rows.
