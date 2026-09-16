# core-tenor

Layer 0, no dependencies. Package root `core_tenor::`, prefix `CTN` / `Ctn` / `ctn`.

Tenor buckets, joined by a RANGE on days rather than by a key. A dated item carries
`daysAway` and no bucket id; which bucket applies is a property of that number, so the store
resolves it with `Ctn_DatedBucket`, an inequality join on the half-open interval
`[MIN_DAYS, MAX_DAYS)`.

| element | kind | note |
| --- | --- | --- |
| core_tenor::CtnTenorBand | enum | The nine standard bands: D0_7, D8_30, M1_3, M3_6, M6_12, Y1_3, Y3_5, Y5_10, Y10_PLUS |
| core_tenor::CtnDatedItemKind | enum | CASHFLOW, FIXING, RESET, MATURITY |
| core_tenor::CtnTenorLadder | class | A named, non-overlapping ladder of buckets; `buckets: CtnTenorBucket[*]` |
| core_tenor::CtnTenorBucket | class | One bucket: `band`, `minDays`, `maxDays`, `sortOrder`, `ladder[0..1]`, `items[*]`, derived `spanDays()` |
| core_tenor::CtnDatedItem | class | A dated amount: `kind`, `valueDate`, `daysAway`, `amount`, `currency`, `bucket[0..1]` via the range join |
| core_tenor::CtnBucketProfile | class | Per-bucket rollup as of a date; reaches `bucket[0..1]` by KEY, not by range |
| core_tenor::Store | store | Tables CTN_TENOR_LADDER, CTN_BUCKET, CTN_DATED_ITEM, CTN_BUCKET_PROFILE |
| core_tenor::Mapping | mapping | Set ids ctnLadder, ctnBucket, ctnDatedItem, ctnProfile; enumeration mappings CtnBandMapping, CtnItemKindMapping |

## Joins in `core_tenor::Store`

| join | condition | note |
| --- | --- | --- |
| Ctn_DatedBucket | `CTN_DATED_ITEM.DAYS_AWAY >= CTN_BUCKET.MIN_DAYS and CTN_DATED_ITEM.DAYS_AWAY < CTN_BUCKET.MAX_DAYS` | RANGE join. Traversed both ways: `CtnDatedItem.bucket` and `CtnTenorBucket.items` |
| Ctn_BucketLadder | `CTN_BUCKET.LADDER_ID = CTN_TENOR_LADDER.LADDER_ID` | ordinary key join |
| Ctn_ProfileBucket | `CTN_BUCKET_PROFILE.BUCKET_ID = CTN_BUCKET.BUCKET_ID` | ordinary key join |

## For downstream projects

`include core_tenor::Store` in a Database and `include core_tenor::Mapping` in a Mapping to
band your own amounts against `CTN_BUCKET`. Declare your own range join against
`CTN_BUCKET.MIN_DAYS` / `MAX_DAYS`; the bucket table has no key you can join on instead.

No `###Data` element and no Runtime: the tables are declared and unseeded.
