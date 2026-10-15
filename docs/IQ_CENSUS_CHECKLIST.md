# The `#IQ{...}#` island's census checklist: what to build, in order

Created 2026-10-15 at the user's direction ("how do we stop going off track and focus on these tiers").

**The rule.** Work goes top-down through this list:
- take the first item marked **open**;
- no item from a lower tier, no divergence-allowlist entry (`core/src/test/resources/iq-parity/iq17-divergences.tsv`) and no scale tail while an item above it is open, unless the user directs otherwise;
- every commit names the item it closes (`checklist 0.7`) and updates its status here in the same commit.

**The source** is the world SQL usage census, compiled 2026-10-14 from:
- SF25: the Snowflake VLDB 2025 study of 667M production BI queries;
- RS24: the Amazon Redshift fleet, VLDB 2024;
- GR18: Tableau Public, DBTest 2018;
- SQLShare and the other studies it cites.

The tiers are that study's: usage weighted by the chance that two dialects disagree silently. Shares are from SF25 unless the item says otherwise.

**Each item is judged by the live SAP IQ 17.1** (`iq17-*` profiles). Where IQ's engine differs from SQL Anywhere 17, which judges the `iq16-*` profiles, the decision is a row of `docs/SEMANTICS_REGISTER.md` (S52).

Status words:
- **done**: built and measured;
- **open**: not built;
- **partial**: built for the common forms, the rest refused by name;
- **out**: deliberately not built, with the reason.

## Tier 0: in most real queries, and silently different across dialects

| # | Item | Status |
|---|---|---|
| 0.1 | String comparison: case, collation, trailing blanks; equality and IN filters, join keys, GROUP BY, DISTINCT, MIN/MAX, ORDER BY on text | done. Allowlisted remainders: a tab in blank padding (D-S5); UTF-8 CHAR(n) counting bytes. |
| 0.2 | NULL semantics: IS [NOT] NULL, outer-join NULLs, COALESCE/ISNULL typing, NULL sort position, NOT IN with a NULL member, NULL in concatenation, `''` versus NULL, aggregates over empty and all-NULL input | done. Allowlisted remainder: IS UNKNOWN over NOT IN of a subquery holding NULL. |
| 0.3 | GROUP BY with SUM/COUNT/MIN/MAX: result type and overflow of SUM, AVG of integers, COUNT(*) versus COUNT(col), an aggregate over no rows, GROUP BY on text by its collation | done for every value within a BIGINT. Deferred (the user, 2026-10-15: features before overflow edges): IQ's engine widens an integer result past a BIGINT to a NUMERIC (D-N3, 15 allowlisted runs), where lite's database raises; it matters only past 9.2 × 10^18. |
| 0.4 | ORDER BY plus TOP/START AT: NULL placement, collation order, ties | done. IQ has no LIMIT/OFFSET, and TOP 0 is IQ's error. `ORDER BY (1)`, a position under IQ's engine and a constant on SQL Anywhere's front end, is refused by name under IQ's engine (2026-10-15). |
| 0.5 | CASE/IF, CAST and coercion: branch unification, rounding versus truncation, string-to-number and string-to-date, DECIMAL scale, integer division, a zero divisor | partial. String to date reads the year-first spellings. Done (2026-10-15, measured on IQ 17.1): a CAST to a shorter VARCHAR or CHAR is cut to n bytes, never inside a character, and CHAR(n) padded to n bytes (D-C2); a narrower NUMERIC, and a DOUBLE to a NUMERIC of at most 18 digits, truncate toward zero (D-N6); a number or a date to too short a text raises for that row (D-C1); a NUMERIC as text, in CAST, `\|\|` and STRING, keeps its 0 before the point (D-C8); SIGN and TRUNCNUM keep their argument's type (D-F19). A string read as an INTEGER or a NUMERIC by IQ's engine's own reading (white space around, a sign, digits with one point; no exponent; '' its error), cut toward zero. CASE, IF and COALESCE of a string and a number are IQ's error -1009145, refused. Refused by name: `CAST(x AS NUMERIC)` without a precision (a NUMERIC(126,38) under IQ's engine), a DOUBLE to a NUMERIC of more than 18 digits, a NUMERIC constant or a DOUBLE as text, a string that is no constant read as a DOUBLE (IQ's reading is not correctly rounded: '1.555' is 1.5550000000000002). Nothing open. |
| 0.6 | Joins, inner and outer: ON versus WHERE under outer joins, text and NULL keys, legacy outer-join syntax | done (2026-10-15): the T-SQL `*=` / `=*` outer joins as IQ 17.1 reads them (its PUBLIC tsql_outer_joins is On; Off, IQ's -1099): a WHERE condition that reads the NULL-extended table joins, the rest filters; a chain (-680), a cycle (-136) and an ANSI join beside them (-681) are IQ's errors, refused; under OR refused. Out: two output columns of one name (S58). KEY JOIN (IQ's foreign-key join) is IQ's own, and comes after Tier 0. |
| 0.7 | Dates: EXTRACT/DATEPART, DATEDIFF's boundaries, DATEADD at month end, week and weekday numbering, CURRENT DATE, fractional seconds, date arithmetic | done (2026-10-15): EXTRACT, DATEPART/DATENAME with IQ's calendar weeks (from Sunday, D-T10), DATEDIFF, DATEADD, date ± days and date − date, strings read as dates, DATEFORMAT (its tokens as measured; another refused by name), DAYNAME, MONTHNAME, DATETIME, YMD. LAST_DAY is not IQ's (-265). |

## Tier 1: common, with moderate risk

| # | Item | Status |
|---|---|---|
| 1.8 | CTEs and derived tables: scoping; a scalar subquery of more than one row raises | done. WITH RECURSIVE is out: IQ's engine rejects it over IQ tables (-706), and only its front end runs it. |
| 1.9 | IN lists: NOT IN and NULL, long lists | done (20,000 values). A column in the list is IQ's -1001030, refused. |
| 1.10 | String functions: LIKE (case, ESCAPE, blanks), SUBSTRING with a start of 0 or a negative start, LEN/LENGTH and blanks, CHARINDEX/LOCATE, `+` versus `\|\|`, TRIM, UPPER/LOWER | done (2026-10-15, measured on IQ 17.1): REVERSE, INSERTSTR, BYTE_LENGTH (UTF-8 bytes), GREATER/LESSER and ARGN (typed as CASE; the second of equals), POWER under IQ's engine (NULL where it has no DOUBLE); LENGTH and BYTE_LENGTH of an integer column, its storage width (D-S7). Refused by name: LENGTH of a date or a timestamp (a storage length varying with the value), SOUNDEX and DIFFERENCE (SQL Anywhere's own numeric code). NUMBER(*): the result's rows numbered in its order, before TOP and START AT (as ROW_NUMBER over the ORDER BY); IQ's -864 and -1001030 placements refused. REGEXP_SUBSTR: the first match from a start, for patterns of single-character atoms with greedy quantifiers; refused by name: alternation and groups (IQ matches leftmost-longest, DuckDB leftmost-first), the backslash and POSIX classes (IQ's are Unicode's), an occurrence past 1 (IQ counts it from one character past the last match's start), an escape character. |
| 1.11 | Window functions: ranking ties, the default RANGE frame, LAG/LEAD defaults, NTILE | done |
| 1.12 | UNION / UNION ALL: type and length unification, deduplication by the collation | done |
| 1.13 | OR, NOT, `<>`, ranges, BETWEEN with reversed bounds | done |

## Tier 2: rarer, or of less risk

| # | Item | Status |
|---|---|---|
| 2.14 | EXISTS and IN subqueries, semi and anti joins, correlated subqueries | done, with IQ's engine's positions refused as IQ refuses them |
| 2.15 | COUNT(DISTINCT) and DISTINCT, by the collation | done |
| 2.16 | Statistical and list aggregates: STDDEV and VARIANCE (sample, population), MEDIAN, PERCENTILE_CONT/DISC, LIST's order (GROUP_CONCAT) | open: STDDEV, VARIANCE, PERCENTILE, MEDIAN of a NUMERIC, LIST ordered by another column |
| 2.16b | Subtotals: ROLLUP, CUBE, GROUPING SETS, GROUPING() (BI tools' totals) | open |
| 2.16c | CROSS APPLY, OUTER APPLY, LATERAL | open |
| 2.17 | Session and metadata statements (31% of BI statements) | out: the island reads one SELECT and refuses IQ's catalog by name (standing rule) |
| 2.18 | DML and CTAS | out: the island is the read path |

## Scale (the census's §3 budgets)

| Shape | Census budget | Status |
|---|---|---|
| Joins | 0-50 routine, 100-350 stress (outlier p99 352; 247 outer joins in one Tableau query) | done: 352 joins plan in 1.3 s, 500 in 3.7 s. Above 1,000, DuckDB's parser depth (0.0003% of joining queries). |
| SQL text | p99 16 KB; 1 MB, 16 MB and 27 MB stress | done: 1 MB in 2.3 s, 16 MB in 21 s (translate + compile + plan) |
| Projection expressions | 1,000 routine; 8,000 and 290,000 stress | done: 16,000 items in 3-7 s |
| Expression depth | past 100; recursive descent survives 1,000 | done for CASE (one flat CASE) and measure sums. Parentheses nested 1,000 deep are DuckDB's own limit. |
| GROUP BY keys | more than 10 | done (50) |
| UNION ALL | a chain of 127; over 1,000 inputs as the extreme | done to 500. Over 1,000 inputs, DuckDB's parser depth. |
| IN lists | 1 to 10,000+, with NULL and NOT IN | done (20,000) |
| Measure sums | 10 to 50 terms | done for integers and DOUBLEs (100 terms). Open: NUMERIC(18,2) past ~20 terms, where IQ's typing outgrows lite's 38 digits. |
