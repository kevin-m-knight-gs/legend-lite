# exposure-agg

Layer 2. Depends on **credit-core** and **collateral-core**, and on nothing else. Package root
`exposure_agg::`, prefixes `EXA_` (tables + views), `Exa_` (joins), `Exa` (filters), `Exa`
(class names), `exa` (set ids).

Net exposure after collateral: gross exposure up the credit leg, collateral value after
haircut up the collateral leg, the difference netted under an agreement, and what wrong-way
risk and the concentration add-on then do to it.

## The diamond, and how it is closed

Both dependencies reach `core-party` by different routes:

    credit-core       CDC_OBLIGOR.ENTITY_ID                    -> the party that OWES
    collateral-core   COL_AGREEMENT.COUNTERPARTY_ENTITY_ID     -> the party that PLEDGES
                      COL_COLLATERAL.ISSUER_ID                 -> whose paper was pledged

Three arrivals at one key in a project **exposure-agg is not allowed to name**. The apex is
therefore a class of this project's own — `exposure_agg::ExaCounterparty` — which holds
`entityId` and joins BOTH ways from that one column:

    Join Exa_Counterparty_Obligor(EXA_COUNTERPARTY.ENTITY_ID = CDC_OBLIGOR.ENTITY_ID)
    Join Exa_Counterparty_Agreement(EXA_COUNTERPARTY.ENTITY_ID = COL_AGREEMENT.COUNTERPARTY_ENTITY_ID)

`CP_LEGAL_ENTITY` is *visible* here — both dependency stores drag `core_party::Store` in, so it
arrives twice by two routes — and is deliberately never named. The two legs meet on this side
of the boundary because that is the only place they legitimately can.

**What the diamond buys, concretely:** `exposure_agg::ExaExposureBreak` compares the credit
system's exposure against the collateral system's margin call. Neither dependency can raise
that break alone; this is the first row in the graph that has both numbers.

## The temporal obligation is NOT restated here

`credit_core::CdcObligor.obligorRatings` reaches `core_ratings::RatingVersion`, which is
`<<temporal.businesstemporal>>`. That obligation stays where credit-core put it and lands on
whoever asks:

    exposure_agg::ExaCounterparty.all()->map(c | $c.obligor.obligorRatings(%2024-03-31))

Nothing in `exposure_agg::` is milestoned and nothing here adds a temporal column.

## Exports

| element | kind | note |
| --- | --- | --- |
| exposure_agg::ExaCounterparty | class | **the apex of the diamond**: holds `entityId`, joins to `credit_core::CdcObligor` one way and `collateral_core::ColCollateralAgreement` the other |
| exposure_agg::ExaNettingSet | class | what legally nets with what; `isEnforceable` false means gross is the number |
| exposure_agg::ExaNettingSetMember | class | composite key (nettingSetId, exposureId); dated membership, so an exposure can move between sets |
| exposure_agg::ExaExposureLine | class | one `credit_core::CdcExposure` as it enters a net exposure, plus the PFE add-on |
| exposure_agg::ExaValuationBasis | class | how gross was measured (MTM / EAD / SA-CCR); `measuresPfe` stops the add-on being applied twice |
| exposure_agg::ExaCollateralPosition | class | **left side of BOTH or-joins**: carries `issuerEntityId` and `assetClass` as OWN columns, both NOT NULL |
| exposure_agg::ExaHaircutApplication | class | composite key (positionId, asOfDate); the haircut components and which authority set them |
| exposure_agg::ExaNetExposure | class | **the grain**: one counterparty, one netting set, one date — gross, collateral, net side by side |
| exposure_agg::ExaExposureRun | class | the batch; an incomplete run is a run whose numbers must not be read |
| exposure_agg::ExaWrongWayRule | class | **the first disjunctive class**: exactly one of `relatedEntityId` / `relatedAssetClass` is set; `ruleBasis` records which |
| exposure_agg::ExaWrongWayAssessment | class | what the rules did to one net exposure; `matchedOnAxis` records which side of the `or` fired |
| exposure_agg::ExaConcentrationBucket | class | **the second disjunctive class**: scoped `bucketIssuerId` / `bucketAssetClass` |
| exposure_agg::ExaAddOnRate | class | composite key (bucketCode, bandCode); the add-on grid |
| exposure_agg::ExaConcentrationAddOn | class | the add-on charged, per counterparty per bucket per date; cites `collateral_core::ColConcentrationLimit` |
| exposure_agg::ExaThresholdApplication | class | composite key (nettingSetId, asOfDate); why a large net produced no call |
| exposure_agg::ExaExposureAdjustment | class | manual adjustment with raiser and approver |
| exposure_agg::ExaCollateralShortfall | class | the uncovered amount, aged and escalated |
| exposure_agg::ExaExposureBreak | class | **credit leg vs collateral leg**; reaches `collateral_core::ColMarginCall` |
| exposure_agg::ExaCreditOfficerReview | class | the sign-off; an unsigned day is unread, not wrong |
| exposure_agg::ExaCounterpartyDayNet | class | **AGGREGATION, the headline**: net exposure per counterparty per date — grouped, drops the netting set |
| exposure_agg::ExaNettingSetDayNet | class | **AGGREGATION**: per netting set per date; `nettingBenefit()` is what the legal opinion pays for |
| exposure_agg::ExaCollateralIssuerTotal | class | **AGGREGATION**: collateral per issuer per counterparty per date; feeds the add-on |
| exposure_agg::ExaPortfolioDayNet | class | **AGGREGATION**: per `credit_core::CdcPortfolio` per date; crosses counterparties on purpose |
| exposure_agg::Store | store | 19 tables `EXA_*`, 4 aggregation views `EXA_*`, 40 joins `Exa_*` (2 disjunctive), 5 filters `Exa*`; `include`s **both** dependency stores |
| exposure_agg::Mapping | mapping | 23 class sets `exa*` (4 rooted on views); `include`s **both** dependency mappings |

No associations are exported — every relationship is a plain property resolved by a property
mapping over a join. A downstream project that wants a navigable edge back into this project
declares its own `Association` and its own `AssociationMapping`.

## Tables

`EXA_COUNTERPARTY`, `EXA_NETTING_SET`, `EXA_NETTING_SET_MEMBER`\*, `EXA_EXPOSURE_LINE`,
`EXA_VALUATION_BASIS`, `EXA_COLLATERAL_POSITION`, `EXA_HAIRCUT_APPLICATION`\*,
`EXA_NET_EXPOSURE`, `EXA_RUN`, `EXA_WRONG_WAY_RULE`, `EXA_WRONG_WAY_ASSESSMENT`,
`EXA_CONCENTRATION_BUCKET`, `EXA_ADD_ON_RATE`\*, `EXA_CONCENTRATION_ADD_ON`,
`EXA_THRESHOLD_APPLICATION`\*, `EXA_ADJUSTMENT`, `EXA_SHORTFALL`, `EXA_BREAK`, `EXA_REVIEW`
(\* = composite primary key)

Note `EXA_NET_EXPOSURE.NET_EXPOSURE_AMOUNT` — the column is not `NET_EXPOSURE`, because that
is the table's own name. The property is `netExposure`.

## Views (the aggregations)

| view | ~groupBy | note |
| --- | --- | --- |
| `EXA_COUNTERPARTY_DAY_NET` | (COUNTERPARTY_ID, AS_OF_DATE) | over `EXA_NET_EXPOSURE`; the headline |
| `EXA_NETTING_SET_DAY_NET` | (NETTING_SET_ID, AS_OF_DATE) | over `EXA_NET_EXPOSURE` |
| `EXA_COLLATERAL_ISSUER_TOTAL` | (COUNTERPARTY_ID, ISSUER_ENTITY_ID, AS_OF_DATE) | over `EXA_COLLATERAL_POSITION` |
| `EXA_PORTFOLIO_DAY_NET` | (PORTFOLIO_ID, AS_OF_DATE) | over `EXA_NET_EXPOSURE`; the one grouped set with a cross-project join out of it |

An absent group is **not** a zero. A counterparty with no netting sets on a date forms no
group and does not appear; a downstream report that needs a zero row has to supply it.

## Joins

Disjunctive (**the or-joins**, one line each, unqualified `or`):

    Join Exa_WrongWayScope(EXA_COLLATERAL_POSITION.ISSUER_ENTITY_ID = EXA_WRONG_WAY_RULE.RELATED_ENTITY_ID or EXA_COLLATERAL_POSITION.ASSET_CLASS = EXA_WRONG_WAY_RULE.RELATED_ASSET_CLASS)
    Join Exa_ConcentrationScope(EXA_COLLATERAL_POSITION.ISSUER_ENTITY_ID = EXA_CONCENTRATION_BUCKET.BUCKET_ISSUER_ID or EXA_COLLATERAL_POSITION.ASSET_CLASS = EXA_CONCENTRATION_BUCKET.BUCKET_ASSET_CLASS)

Closing the diamond: `Exa_Counterparty_Obligor`, `Exa_Counterparty_Agreement`.

Other cross-project: `Exa_NettingSet_Agreement`, `Exa_Member_Exposure`, `Exa_Line_Exposure`,
`Exa_Line_RiskMeasure`, `Exa_NetExposure_Portfolio`, `Exa_Position_Collateral`,
`Exa_Position_Valuation` (two columns, into `COL_VALUATION`'s composite key), `Exa_AddOn_Limit`,
`Exa_Break_MarginCall`, `Exa_PortfolioDayNet_Portfolio` (leaves a `~groupBy`).

Inside: `Exa_Counterparty_NettingSet`, `Exa_Counterparty_NetExposure`, `Exa_Counterparty_Review`,
`Exa_Counterparty_AddOn`, `Exa_NettingSet_Member`, `Exa_NettingSet_NetExposure`,
`Exa_NettingSet_Threshold`, `Exa_NetExposure_Line`, `Exa_NetExposure_Position`,
`Exa_NetExposure_Assessment`, `Exa_NetExposure_Adjustment`, `Exa_NetExposure_Shortfall`,
`Exa_NetExposure_Break`, `Exa_NetExposure_Run`, `Exa_NetExposure_Basis`, `Exa_Line_Basis`,
`Exa_Position_Haircut`, `Exa_Assessment_Rule`, `Exa_Bucket_AddOn`, `Exa_Bucket_Rate`.

Into the aggregations: `Exa_NetExposure_CounterpartyDayNet`, `Exa_NetExposure_NettingSetDayNet`,
`Exa_NetExposure_PortfolioDayNet`, `Exa_Position_IssuerTotal`, `Exa_Counterparty_DayNet`,
`Exa_NettingSet_DayNet`.

## Set ids (extend or name these; they are a GLOBAL namespace)

`exaCounterparty`, `exaNettingSet`, `exaNettingSetMember`, `exaExposureLine`,
`exaValuationBasis`, `exaCollateralPosition`, `exaHaircutApplication`, `exaNetExposure`,
`exaRun`, `exaWrongWayRule`, `exaWrongWayAssessment`, `exaConcentrationBucket`, `exaAddOnRate`,
`exaConcentrationAddOn`, `exaThresholdApplication`, `exaAdjustment`, `exaShortfall`, `exaBreak`,
`exaReview`, `exaCounterpartyDayNet`, `exaNettingSetDayNet`, `exaCollateralIssuerTotal`,
`exaPortfolioDayNet`

Five of those do not follow the class name: `exaRun` (`ExaExposureRun`), `exaAdjustment`
(`ExaExposureAdjustment`), `exaShortfall` (`ExaCollateralShortfall`), `exaBreak`
(`ExaExposureBreak`), `exaReview` (`ExaCreditOfficerReview`). Read them from here rather than
guessing.

## Filters

`ExaOpenBreaks` (RESOLVED_DATE is null), `ExaUnsignedReviews` (SIGNED_OFF_DATE is null),
`ExaCurrentWrongWayRules` (EXPIRY_DATE is null), `ExaLiveNettingSets` (CLOSED_DATE is null),
`ExaOpenShortfalls` (CLEARED_DATE is null). All null tests — a `Filter` will not take a
boolean literal.

## Notes for downstream

- **Every set id here is explicit**, so the default ids (`exposure_agg_ExaNetExposure` and
  friends) do NOT exist. `extends [...]` and any cross-project `AssociationMapping` must name
  the explicit id from the list above.
- **The or-join ends are to-many on both sides**, exactly as in collateral-core.
  `position.wrongWayRules` returns every rule that hits on either axis and
  `rule.scopedCollateral` returns every position. Take the harshest `multiplier` and the
  tightest bucket across the set; do not assume one. `ExaWrongWayAssessment.matchedOnAxis`
  records which side actually fired, because the `or` itself cannot tell you.
- **The aggregate classes are groups, not rows.** Their primary key IS the `~groupBy`.
  Navigating `netExposure.counterpartyDayNet` crosses into a `GROUP BY` and never into another
  net-exposure row.
- **Do not net across netting sets.** `ExaCounterpartyDayNet` sums them, which is correct for
  a credit line and wrong for a legal claim. Where `ExaNettingSet.isEnforceable` is false the
  set does not net at all and the gross is the number.
- `ExaNetExposure.uncoveredAmount()` recomputes gross less collateral. Where it disagrees with
  the stored `netExposure`, a threshold was applied or the set is unenforceable — both worth
  seeing rather than smoothing.
- Derived properties available: `ExaCounterparty.isCovered()`; `ExaNettingSet.isOpen()` /
  `.netsLegally()`; `ExaNettingSetMember.isCurrentMember()`; `ExaExposureLine.grossWithAddOn()`;
  `ExaCollateralPosition.valueAfterHaircut()` / `.haircutAmount()`;
  `ExaHaircutApplication.componentHaircutPct()` / `.valueRetainedPct()`;
  `ExaNetExposure.coverageRatio()` / `.uncoveredAmount()` / `.isUncovered()` /
  `.netAfterAddOn()` / `.wrongWayAdjustedNet()`; `ExaExposureRun.isComplete()`;
  `ExaWrongWayRule.isEntityRule()` / `.isAssetClassRule()` / `.isUnscoped()` / `.isCurrent()`;
  `ExaWrongWayAssessment.isWrongWay()` / `.matchedBothAxes()`;
  `ExaConcentrationBucket.isIssuerBucket()` / `.isAssetClassBucket()` / `.isUnscoped()`;
  `ExaAddOnRate.bandWidthPct()`; `ExaConcentrationAddOn.excessAmount()`;
  `ExaThresholdApplication.callableAmount()` / `.breachesThreshold()`;
  `ExaExposureAdjustment.isApproved()` / `.isLive()`; `ExaCollateralShortfall.coveragePct()` /
  `.isCleared()`; `ExaExposureBreak.difference()` / `.isOpen()`;
  `ExaCreditOfficerReview.isSignedOff()`; and on the aggregates
  `ExaCounterpartyDayNet.coverageRatio()` / `.averageNetPerSet()` / `.netAfterAddOn()`,
  `ExaNettingSetDayNet.nettingBenefit()`, `ExaCollateralIssuerTotal.averagePieceValue()` /
  `.totalHaircutAmount()`, `ExaPortfolioDayNet.nettingBenefit()`. Every one that divides is
  `Float[1]`, because `/` widens.
- The aggregate sums are `Float[0..1]`, not `Float[1]`: a `sum()` over a nullable DOUBLE is
  nullable, so the derived properties above `->orElse(0.0)` before arithmetic.
- No `###Data`, no Runtime, no seeded rows.
