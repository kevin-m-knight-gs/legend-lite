# regulatory-capital

Layer 3. Depends on **exposure-agg**, **regulatory-extract** and **margin-calc**, and on
nothing else. Package root `regulatory_capital::`; prefixes `RCP_` (tables + views), `Rcp_`
(joins), `Rcp` (filters), `Rcp` (class names), `rcp` (set ids).

32 classes, 24 tables, 3 store views, 41 joins, 10 filters, 32 mapping sets. No enums, no
profiles, no functions, no associations — this project exports **classes, a store and a
mapping**.

What the firm must hold against what it is exposed to, and what it is allowed to pay out once
it holds it: risk-weighted assets by approach, credit risk mitigation, SA-CCR, the CVA charge,
operational risk, the output floor, and the buffers that restrict distributions when breached.

## The fan-in, which is why this project exists

The widest at layer 3 by dependency count. Three L2 projects arrive here and their closures
are large:

    exposure-agg          -> credit-core, collateral-core           (the credit exposure)
    regulatory-extract    -> trade-capture, reference-data, client-core   (the reported view)
    margin-calc           -> position-keeping, risk-core            (the margin numbers)

Seventeen projects are in the compiled closure and every table in all of them is VISIBLE from
these three files. **Not one outside the three direct dependencies is named.** No `credit_`,
`collateral_`, `trade_`, `reference_`, `client_`, `position_` or `risk_` package appears
anywhere in `model.pure`, `store.pure` or `mapping.pure`. Where this project needs something
that lives further down, it goes through the dependency's own model — `$exposure.netExposure`
returns exposure-agg's class and *its* properties reach credit-core — rather than round it.

`CP_LEGAL_ENTITY`, `CI_INSTRUMENT`, `TC_TRADE` and `PK_POSITION` all arrive transitively and
are deliberately never named. Include your DIRECT dependencies only: adding
`include core_party::Store` on top would be both a redundant include and an undeclared
dependency.

## The two constructs

### `~filter` SUBTYPES — one table, five faces

`[rcpExposure]` is the ROOT set (`*`) over `RCP_EXPOSURE`, so `RcpExposure.all()` is the whole
regulatory book. Five sets `extends [rcpExposure]`, each adding one `~filter` declared in this
project's store and only the columns and links that mean something once the row is known to be
of that type. None restates the key, the main table or any of the fifty inherited property
mappings.

| set | class | filter | predicate |
| --- | --- | --- | --- |
| `rcpSaExposure` | `RcpStandardisedExposure` | `RcpStandardisedRows` | `APPROACH_CODE = 'SA'` |
| `rcpIrbExposure` | `RcpIrbExposure` | `RcpIrbRows` | `APPROACH_CODE = 'IRB'` |
| `rcpCcrExposure` | `RcpCounterpartyCreditExposure` | `RcpCounterpartyRiskRows` | `EXPOSURE_TYPE_CODE = 'DERIVATIVE'` |
| `rcpDefaulted` | `RcpDefaultedExposure` | `RcpDefaultedRows` | `DEFAULT_STATUS = 'DEFAULTED'` |
| `rcpUnmitigated` | `RcpUnmitigatedExposure` | `RcpUnmitigatedRows` | `CRM_ID is null` |

**They are not disjoint, and that is the point.** An IRB derivative that has defaulted is a row
of `rcpIrbExposure`, of `rcpCcrExposure` AND of `rcpDefaulted`: the approach is an identity,
the product type is another identity, and default is a state. Counting the book by summing the
five sets double-counts. That is a property of the domain, not of the mapping.

No filter uses a boolean literal — `Filter X(T.IS_X = true)` fails with `Unexpected token
'true'` — so each is a string comparison or a null test.

### AGGREGATION — three store views

Each is mapped onto a class whose `~primaryKey` IS its `~groupBy`.

| view | ~groupBy | one row is |
| --- | --- | --- |
| `RCP_RWA_BY_APPROACH_CLASS` | (REPORTING_DATE, APPROACH_CODE, EXPOSURE_CLASS_CODE) | one cell of the credit risk template |
| `RCP_ENTITY_RWA_TOTAL` | (ENTITY_ID, REPORTING_DATE) | the entity's whole credit denominator |
| `RCP_COUNTERPARTY_CCR_TOTAL` | (COUNTERPARTY_ID, REPORTING_DATE) | SA-CCR rolled up from netting set to counterparty |

The first has THREE grouping columns deliberately: an approach and an exposure class are part
of what a cell MEANS, and grouping by date alone would add a sovereign at 0% to an equity at
250% and call the result a risk weight.

A fourth aggregation is **read, not declared**: `RcpExposure.counterpartyDayNet` navigates into
exposure-agg's own view `EXA_COUNTERPARTY_DAY_NET` on (COUNTERPARTY_ID, AS_OF_DATE), so this
project's exposure can be compared with the credit system's day total without summing a leg.

These are Legend views, not database ones: no DDL, nothing seeds them, the engine folds the
`GROUP BY` into the SQL. **An absent group is not a zero** — a cell with no exposures on a date
does not form and does not appear.

## The calculation, in the order the numbers are produced

1. every exposure is classified into an **exposure class** and put on an **approach**.
   Standardised reads a weight off a grid indexed by class and credit quality step; IRB
   computes one from the firm's own PD, LGD and maturity.
2. **credit risk mitigation** reduces it — collateral through the comprehensive approach after
   supervisory haircuts, a guarantee by SUBSTITUTING the provider's weight on the covered part.
   Substitution is a transfer of the requirement, not a deletion of it.
3. derivatives get **SA-CCR**: EAD = alpha × (RC + PFE), alpha = 1.4, PFE built from per-asset
   class add-ons inside hedging sets. Offsets are recognised INSIDE a hedging set and nowhere
   else.
4. the same book attracts a **CVA capital charge** for the mark-to-market loss on the
   counterparty's own spread, reducible by eligible hedges.
5. **operational risk** under the standardised approach: a Business Indicator Component scaled
   by an Internal Loss Multiplier built from ten years of internal losses.
6. the **output floor** puts the modelled total under 72.5% of the same book restated
   standardised. A modelled RWA below the floor is not the number; the floor is.
7. own funds over the floored RWA gives the ratios, the **buffers** stack above the minimum,
   and breaching the combined buffer requirement does not close the firm — it puts it in an
   MDA bucket and **restricts distributions**.

## Exports

| element | kind | note |
| --- | --- | --- |
| `regulatory_capital::RcpCapitalRun` | class | one execution of the batch; an incomplete run is unreadable, not partially readable |
| `regulatory_capital::RcpReportingEntity` | class | the scope of consolidation, not the company; links out to `regulatory_extract::RexReportingEntity` |
| `regulatory_capital::RcpExposure` | class | **the root set**; one regulatory exposure on one date; 3 constraints; the whole three-way fan-in hangs off it |
| `regulatory_capital::RcpStandardisedExposure` | class | `extends` the base; the grid lookup — quality step and grid cell |
| `regulatory_capital::RcpIrbExposure` | class | `extends` the base; the model, the grade and the parameters that went in |
| `regulatory_capital::RcpCounterpartyCreditExposure` | class | `extends` the base; the SA-CCR decomposition |
| `regulatory_capital::RcpDefaultedExposure` | class | `extends` the base; workout, recovery, days past due |
| `regulatory_capital::RcpUnmitigatedExposure` | class | `extends` the base; why there is no protection |
| `regulatory_capital::RcpExposureClass` | class | the class as data; the axis the grid is indexed on |
| `regulatory_capital::RcpCreditQualityStep` | class | ECAI symbol → step. Composite PK (ecai, symbol): one agency's A− is another's A3 |
| `regulatory_capital::RcpRiskWeight` | class | ONE CELL of the grid. Composite PK (class, step) |
| `regulatory_capital::RcpIrbModel` | class | the model AND its permission; a withdrawn approval sends the book back to standardised |
| `regulatory_capital::RcpIrbParameterSet` | class | one grade of one model. Composite PK (model, grade) |
| `regulatory_capital::RcpCreditRiskMitigation` | class | funded or unfunded protection; `recognisedValue()` is zero when ineligible |
| `regulatory_capital::RcpSupervisoryHaircut` | class | the published haircut grid. Composite PK (collateral type, maturity band) |
| `regulatory_capital::RcpGuarantee` | class | substitution: the covered part moves to the provider's weight, and `substitutionBenefit()` can be negative |
| `regulatory_capital::RcpNettingSetCapital` | class | **SA-CCR per set per date**; 2 constraints; reaches BOTH exposure-agg and margin-calc |
| `regulatory_capital::RcpHedgingSet` | class | the level at which offsets are ALLOWED |
| `regulatory_capital::RcpSaccrAddOn` | class | where the PFE came from. Composite PK (hedging set, asset class) |
| `regulatory_capital::RcpCvaCharge` | class | the spread-widening charge. Composite PK (counterparty, date); reaches `exposure_agg::ExaCounterparty` |
| `regulatory_capital::RcpCvaHedge` | class | only some hedges count; an index hedge is recognised to the extent it correlates |
| `regulatory_capital::RcpOperationalRiskIndicator` | class | the Business Indicator. Composite PK (entity, financial year) |
| `regulatory_capital::RcpOperationalRiskCharge` | class | BIC × ILM. Composite PK (entity, date); ILM is 1.0 under five years of loss data |
| `regulatory_capital::RcpOwnFunds` | class | **the numerator**, in three tiers. 2 constraints; carries the leverage exposure measure too |
| `regulatory_capital::RcpCapitalRequirement` | class | **the row a supervisor reads**: every risk type, pre-floor and post-floor, and the ratios. 1 constraint |
| `regulatory_capital::RcpOutputFloor` | class | 72.5% of the standardised restatement. 1 constraint; `benefitLostToFloor()` is what the models stopped being worth |
| `regulatory_capital::RcpCapitalBuffer` | class | one buffer as a row — different bodies, different timetables |
| `regulatory_capital::RcpBufferRequirement` | class | the combined requirement. Composite PK (entity, date) |
| `regulatory_capital::RcpDistributionRestriction` | class | the MDA: the quartile decides the payout factor, the factor caps dividends AND bonuses together |
| `regulatory_capital::RcpRwaByApproachClass` | class | **AGGREGATE on a view**: date × approach × exposure class |
| `regulatory_capital::RcpEntityRwaTotal` | class | **AGGREGATE on a view**: the entity's whole denominator |
| `regulatory_capital::RcpCounterpartyCcrTotal` | class | **AGGREGATE on a view**: SA-CCR per counterparty |
| `regulatory_capital::Store` | store | 24 `RCP_` tables, 3 `RCP_` views, 41 `Rcp_` joins, 10 `Rcp` filters; `include`s all three dependency stores |
| `regulatory_capital::Mapping` | mapping | 32 sets, 5 of them `extends [rcpExposure]` with a `~filter`, 3 rooted on views; `include`s all three dependency mappings |

No associations are exported — every relationship is a plain property resolved by a property
mapping over a join. A downstream project that wants a navigable edge back declares its own
`Association` and `AssociationMapping`.

## The cross-project references (ten of them, into three projects)

Every target id is the dependency's **explicit** one, read out of its MANIFEST. The defaults
(`exposure_agg_ExaNetExposure`, `margin_calc_MgnInitialMargin`) do not exist in any of the
three.

| on class | property | type | target set |
| --- | --- | --- | --- |
| `RcpExposure` | `netExposure` | `exposure_agg::ExaNetExposure[0..1]` | `[exaNetExposure]` |
| `RcpExposure` | `counterpartyDayNet` | `exposure_agg::ExaCounterpartyDayNet[0..1]` | `[exaCounterpartyDayNet]` — **into someone else's VIEW** |
| `RcpExposure` | `transactionReport` | `regulatory_extract::RexTransactionReport[0..1]` | `[rexTransaction]` |
| `RcpReportingEntity` | `reportingProfile` | `regulatory_extract::RexReportingEntity[0..1]` | `[rexReportingEntity]` |
| `RcpNettingSetCapital` | `exaNettingSet` | `exposure_agg::ExaNettingSet[0..1]` | `[exaNettingSet]` |
| `RcpNettingSetCapital` | `marginPortfolio` | `margin_calc::MgnMarginPortfolio[0..1]` | `[mgnMarginPortfolio]` |
| `RcpNettingSetCapital` | `modelInitialMargin` | `margin_calc::MgnInitialMargin[0..1]` | `[mgnInitialMargin]` — **three-column join** |
| `RcpCvaCharge` | `counterparty` | `exposure_agg::ExaCounterparty[0..1]` | `[exaCounterparty]` |

Flattened chain properties, for a return that groups without navigating:

| on class | property | reaches |
| --- | --- | --- |
| `RcpExposure` | `creditSystemNetExposure`, `creditSystemCollateralValue` | `EXA_NET_EXPOSURE`, 1 hop |
| `RcpExposure` | `reportedRegimeCode` | `rex.REX_TRANSACTION`, 1 hop — note the **schema segment** |
| `RcpNettingSetCapital` | `modelTotalInitialMargin` | `MGN_INITIAL_MARGIN`, 1 hop |

regulatory-extract owns a schema, so every reference to one of its columns writes
`[regulatory_extract::Store]rex.REX_TRANSACTION.REGIME_CODE`. exposure-agg and margin-calc use
the default schema and take no segment. Getting that wrong fails with an error naming the
column and not the schema.

## Set ids (a GLOBAL namespace — reference these, do not guess)

`rcpRun`, `rcpEntity`, `rcpExposure` (root), `rcpSaExposure`, `rcpIrbExposure`,
`rcpCcrExposure`, `rcpDefaulted`, `rcpUnmitigated`, `rcpExposureClass`, `rcpQualityStep`,
`rcpRiskWeight`, `rcpIrbModel`, `rcpParameterSet`, `rcpCrm`, `rcpHaircut`, `rcpGuarantee`,
`rcpNettingSetCapital`, `rcpHedgingSet`, `rcpAddOn`, `rcpCvaCharge`, `rcpCvaHedge`,
`rcpOpRiskIndicator`, `rcpOpRiskCharge`, `rcpOwnFunds`, `rcpRequirement`, `rcpOutputFloor`,
`rcpBuffer`, `rcpBufferRequirement`, `rcpRestriction`, `rcpRwaCell`, `rcpEntityRwaTotal`,
`rcpCcrTotal`.

Nine do not follow the class name and must be read from here: `rcpRun`
(`RcpCapitalRun`), `rcpEntity` (`RcpReportingEntity`), `rcpQualityStep`
(`RcpCreditQualityStep`), `rcpCrm` (`RcpCreditRiskMitigation`), `rcpHaircut`
(`RcpSupervisoryHaircut`), `rcpAddOn` (`RcpSaccrAddOn`), `rcpRequirement`
(`RcpCapitalRequirement`), `rcpRestriction` (`RcpDistributionRestriction`), `rcpRwaCell`
(`RcpRwaByApproachClass`).

**Every set id here is explicit**, so the default ids (`regulatory_capital_RcpExposure` and
friends) do NOT exist. A downstream `extends [...]` or cross-project `AssociationMapping` must
name the explicit one.

## Store surface

All tables in the DEFAULT schema.

| table | primary key |
| --- | --- |
| `RCP_CAPITAL_RUN` | RUN_ID |
| `RCP_REPORTING_ENTITY` | ENTITY_ID |
| `RCP_EXPOSURE` | EXPOSURE_ID |
| `RCP_EXPOSURE_CLASS` | EXPOSURE_CLASS_CODE |
| `RCP_CREDIT_QUALITY_STEP` | ECAI_CODE, RATING_SYMBOL |
| `RCP_RISK_WEIGHT` | EXPOSURE_CLASS_CODE, CREDIT_QUALITY_STEP |
| `RCP_IRB_MODEL` | MODEL_ID |
| `RCP_IRB_PARAMETER_SET` | MODEL_ID, RATING_GRADE |
| `RCP_CRM` | CRM_ID |
| `RCP_SUPERVISORY_HAIRCUT` | COLLATERAL_TYPE_CODE, RESIDUAL_MATURITY_BAND |
| `RCP_GUARANTEE` | GUARANTEE_ID |
| `RCP_NETTING_SET_CAPITAL` | NETTING_SET_ID, REPORTING_DATE |
| `RCP_HEDGING_SET` | HEDGING_SET_ID |
| `RCP_SACCR_ADD_ON` | HEDGING_SET_ID, ASSET_CLASS |
| `RCP_CVA_CHARGE` | COUNTERPARTY_ID, REPORTING_DATE |
| `RCP_CVA_HEDGE` | HEDGE_ID |
| `RCP_OP_RISK_INDICATOR` | ENTITY_ID, FINANCIAL_YEAR |
| `RCP_OP_RISK_CHARGE` | ENTITY_ID, REPORTING_DATE |
| `RCP_OWN_FUNDS` | ENTITY_ID, REPORTING_DATE |
| `RCP_CAPITAL_REQUIREMENT` | ENTITY_ID, REPORTING_DATE |
| `RCP_OUTPUT_FLOOR` | ENTITY_ID, REPORTING_DATE |
| `RCP_CAPITAL_BUFFER` | BUFFER_CODE |
| `RCP_BUFFER_REQUIREMENT` | ENTITY_ID, REPORTING_DATE |
| `RCP_DISTRIBUTION_RESTRICTION` | ENTITY_ID, REPORTING_DATE |

`RCP_EXPOSURE` is one wide table in five blocks: base, standardised, IRB, counterparty credit
risk, default, unmitigated. The blocks after the base are nullable on purpose — an IRB
exposure has no credit quality step and a standardised one has no PD, and forcing either to a
sentinel would make an absent parameter indistinguishable from a zero one.

Note `RCP_CAPITAL_REQUIREMENT` carries `PRE_FLOOR_RISK_WEIGHTED_AMOUNT` **and**
`TOTAL_RISK_WEIGHTED_AMOUNT`. They are two columns on purpose: the difference between them is
what the output floor did, and one column would erase it.

### Joins that leave this project

`Rcp_ExposureNetExposure`, `Rcp_ExposureCounterpartyDayNet` (two columns, into a view),
`Rcp_NettingSetExaNettingSet`, `Rcp_CvaChargeCounterparty` (exposure-agg);
`Rcp_NettingSetMarginPortfolio`, `Rcp_NettingSetInitialMargin` (**three columns**, into
`MGN_INITIAL_MARGIN`'s composite key) (margin-calc);
`Rcp_ExposureTransactionReport`, `Rcp_EntityReportingProfile` (regulatory-extract — both write
the `rex.` schema segment).

Multi-column joins inside the project: `Rcp_ExposureNettingSetCapital`,
`Rcp_ExposureQualityStep`, `Rcp_ExposureGridWeight`, `Rcp_ExposureParameterSet`,
`Rcp_CrmHaircutSchedule`, `Rcp_NettingSetHedgingSets`, `Rcp_NettingSetCvaCharge`,
`Rcp_CvaChargeHedges`, `Rcp_IndicatorCharges`, `Rcp_RequirementOwnFunds`,
`Rcp_RequirementOutputFloor`, `Rcp_RequirementBuffer`, `Rcp_RequirementOpRisk`,
`Rcp_BufferRestriction`, and the three-column `Rcp_ExposureRwaCell`.

### Filters

Applied, as the five subtype discriminators: `RcpStandardisedRows`, `RcpIrbRows`,
`RcpCounterpartyRiskRows`, `RcpDefaultedRows`, `RcpUnmitigatedRows`.

Declared and unapplied, for a downstream mapping: `RcpApprovedModels`
(`RCP_IRB_MODEL.WITHDRAWN_ON is null`), `RcpCurrentRiskWeights`
(`RCP_RISK_WEIGHT.SUPERSEDED_ON is null`), `RcpLiveProtection`
(`RCP_CRM.PROTECTION_END is null`), `RcpInForceBuffers`
(`RCP_CAPITAL_BUFFER.REVOKED_ON is null`), `RcpUnfiledReturns`
(`RCP_CAPITAL_REQUIREMENT.FILED_ON is null`).

## Datatypes, by column

| type | columns |
| --- | --- |
| NUMERIC(24,8) | every money column — exposures, RWA, own funds, charges, add-ons, buffers, distributions |
| FLOAT | every ratio — RISK_WEIGHT_PCT, CREDIT_CONVERSION_FACTOR, POINT_IN_TIME_PD, LOSS_GIVEN_DEFAULT, ASSET_CORRELATION, ALPHA_FACTOR, all `*_HAIRCUT_PCT`, SUPERVISORY_FACTOR, MATURITY_FACTOR, SUPERVISORY_DELTA, all `*_BUFFER_PCT`, FLOOR_CALIBRATION_PCT, PAYOUT_FACTOR_PCT, the three ratios |
| SMALLINT | CREDIT_QUALITY_STEP, QUALITY_STEP, HOLDING_PERIOD_DAYS, MARGIN_PERIOD_OF_RISK_DAYS, BI_BUCKET, LOSS_DATA_YEARS, SYSTEMIC_BUCKET, MDA_QUARTILE |
| CHAR(3) | CURRENCY, REPORTING_CURRENCY |

`REAL` is not used anywhere (docs/UPSTREAM_FINDINGS.md F53).

## Class constraints

`RcpExposure` (exposure value not negative, risk weight between 0 and 1250%, RWA not
negative), `RcpNettingSetCapital` (alpha is exactly 1.4, replacement cost not negative),
`RcpOwnFunds` (CET1 within total, deductions held positive), `RcpCapitalRequirement` (total RWA
positive — a zero denominator makes every ratio infinite), `RcpOutputFloor` (the calibration is
a ratio in (0, 1]). Every comparison is **fully parenthesised**: Pure binds `&&` tighter than
the comparison operators, so an unparenthesised `$this.a >= 0.0 && $this.b <= 1.0` does not
compile.

## Notes for downstream

- **The five exposure sets overlap.** Summing them double-counts. `rcpSaExposure` and
  `rcpIrbExposure` ARE disjoint from each other; nothing else is disjoint from anything.
- **The aggregate classes are groups, not rows.** Their primary key IS the `~groupBy`.
  Navigating `exposure.rwaCell` crosses into a `GROUP BY` and never into another exposure. An
  absent group is not a zero.
- **`preFloorRiskWeightedAmount` and `totalRiskWeightedAmount` are different numbers on
  purpose.** `isFloorBinding()` says which one the models produced and which one is filed;
  `RcpOutputFloor.benefitLostToFloor()` says what the models stopped being worth.
- **A buffer breach is not a minimum breach.** `RcpBufferRequirement.isBreached` does not mean
  the firm is undercapitalised; it means `RcpDistributionRestriction` applies and a share of
  profits stops being the firm's to pay out. `exceedsCap()` compares what the board proposed —
  dividend AND bonus pool, because the cap covers both — with what is left.
- **A guarantee moves a requirement, it does not delete one.** `substitutionBenefit()` is
  negative where the protection provider is worse than the obligor, which is a legal and real
  outcome, not a data error.
- **Regulatory collateral and model initial margin are different numbers for the same
  collateral.** `RcpNettingSetCapital.marginGap()` states the difference rather than
  reconciling it away.
- **Where this project's derived `derivedX()` disagrees with the stored `X`**, an override, a
  supporting factor or a manual adjustment is in play. Both are exposed on purpose:
  `derivedRwa()`, `derivedEad()`, `derivedPreFloorRwa()`, `derivedMinimum()`, `derivedFloor()`,
  `derivedCombinedPct()`, `derivedMda()`, `derivedBusinessIndicator()`, `derivedCapital()`,
  `derivedAddOn()`, `derivedCet1()`, `derivedExpectedLoss()`, `derivedRecognisedAmount()`.
- Every derived property that divides is `Float[1]`, because `/` widens even between two
  Integers. Aggregate sums are `Float[0..1]` — a `sum()` over a nullable column is nullable —
  so the derived properties above them `->orElse(...)` before arithmetic.
- Tables are declared and unseeded. No `###Data`, no `Runtime`.

## Verify

    python3 scripts/projects/check.py regulatory-capital
    compiles  regulatory-capital (+17 deps)
