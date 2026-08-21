# core-price

Layer 0, no dependencies. Price observations keyed by instrument, date and source, over a
store whose columns are deliberately varied: SMALLINT, CHAR(n), NUMERIC(20,6) and FLOAT
alongside VARCHAR, DATE, TIMESTAMP and BIT. `REAL` is not used anywhere (see
docs/UPSTREAM_FINDINGS.md F53).

Prefixes: tables `CPR_`, joins `Cpr_`, filters `Cpr`, set ids `cpr`.

| element | kind | note |
| --- | --- | --- |
| core_price::CprPriceObservation | class | one vendor's price for one instrument on one date; composite key (instrumentId, observationDate, sourceId); 3 constraints, 4 derived properties |
| core_price::CprStalePrice | class | subtype of CprPriceObservation, `~filter CprStaleRows` over the same table and key |
| core_price::CprQuote | class | bid/ask on the same (instrument, date, source) triple |
| core_price::CprPriceSource | class | the vendor or venue that publishes a price; keyed by sourceId |
| core_price::CprSourceTier | class | approval band a source sits in; keyed by a CHAR(1) tierCode |
| core_price::CprPriceCurrency | class | quote currency; keyed by the CHAR(3) ISO code |
| core_price::CprPriceAdjustment | class | corporate-action factor; key (instrumentId, effectiveDate, adjustmentSeq) |
| core_price::CprConsensusPrice | class | cross-vendor agreed price; key (instrumentId, observationDate), no source leg |
| core_price::CprPriceStatistic | class | rolling window statistic; key (instrumentId, observationDate, windowDays) |
| core_price::CprPriceException | class | break raised when vendors disagree; key (instrumentId, observationDate, exceptionSeq) |
| core_price::CprVendorCoverage | class | vendor entitlement; key (sourceId, instrumentId) |
| core_price::CprPriceDay | class | the distinct (instrument, date) pairs, mapped `~distinct` over the observation table |
| core_price::Store | store | tables CPR_PRICE_OBSERVATION, CPR_QUOTE, CPR_PRICE_SOURCE, CPR_SOURCE_TIER, CPR_PRICE_CURRENCY, CPR_PRICE_ADJUSTMENT, CPR_CONSENSUS_PRICE, CPR_PRICE_STATISTIC, CPR_PRICE_EXCEPTION, CPR_VENDOR_COVERAGE |
| core_price::Mapping | mapping | set ids cprObservation, cprStalePrice, cprQuote, cprSource, cprTier, cprCurrency, cprAdjustment, cprConsensus, cprStatistic, cprException, cprCoverage, cprPriceDay |

## Joins, for a downstream store that includes this one

| join | shape |
| --- | --- |
| Cpr_ObservationSource | CPR_PRICE_OBSERVATION.SOURCE_ID = CPR_PRICE_SOURCE.SOURCE_ID |
| Cpr_SourceTier | CPR_PRICE_SOURCE.TIER_CODE = CPR_SOURCE_TIER.TIER_CODE |
| Cpr_ObservationCurrency | CPR_PRICE_OBSERVATION.CURRENCY_CODE = CPR_PRICE_CURRENCY.CURRENCY_CODE |
| Cpr_ObservationQuote | three columns: instrument, date and source |
| Cpr_ObservationConsensus | two columns: instrument and date |
| Cpr_ObservationStatistic | two columns: instrument and date, to-many over windowDays |
| Cpr_ObservationException | two columns: instrument and date, to-many |
| Cpr_ObservationAdjustment | CPR_PRICE_OBSERVATION.INSTRUMENT_ID = CPR_PRICE_ADJUSTMENT.INSTRUMENT_ID |
| Cpr_SourceCoverage | CPR_VENDOR_COVERAGE.SOURCE_ID = CPR_PRICE_SOURCE.SOURCE_ID |

## Filters

| filter | selects |
| --- | --- |
| CprStaleRows | CPR_PRICE_OBSERVATION.IS_STALE = 1 |
| CprPrimarySourceRows | CPR_PRICE_SOURCE.IS_PRIMARY = 1 |

## Datatypes, by column

| type | columns |
| --- | --- |
| SMALLINT | PRICE_DECIMALS, QUOTE_SEQUENCE, BID_SIZE, ASK_SIZE, RANK_ORDER, LATENCY_SECONDS, MAX_LATENCY_SECONDS, MINOR_UNITS, QUOTE_DECIMALS, ADJUSTMENT_SEQ, CONTRIBUTOR_COUNT, WINDOW_DAYS, OBSERVATION_COUNT, EXCEPTION_SEQ, SEVERITY, PRIORITY |
| CHAR(n) | CURRENCY_CODE / DEFAULT_CURRENCY CHAR(3), VENDOR_CODE / METHOD_CODE / EXCEPTION_CODE CHAR(4), TIER_CODE / QUOTE_CONDITION CHAR(1) |
| NUMERIC(20,6) | OPEN/HIGH/LOW/CLOSE_PRICE, VOLUME, BID_PRICE, ASK_PRICE, ADJ_FACTOR, CUMULATIVE_FACTOR, CONSENSUS_PRICE, MEAN/MIN/MAX_PRICE |
| FLOAT | PCT_CHANGE, SPREAD_PCT, RELIABILITY_PCT, MIN_RELIABILITY_PCT, PCT_IMPACT, DISPERSION_PCT, STDDEV_PCT, RETURN_PCT, DEVIATION_PCT, COVERAGE_PCT |

## Notes for downstream

- SMALLINT and NUMERIC columns land on `Integer` and `Float` properties respectively; CHAR(n)
  on `String`, BIT on `Boolean`, DATE on `StrictDate`, TIMESTAMP on `DateTime`.
- `CprPriceObservation` carries three class-level constraints
  (`highCoversLow`, `closeWithinRange`, `decimalsArePositive`), so a subtype that extends it
  inherits them.
- No enums, no profiles, no functions, no associations and no `###Data` element are exported.
