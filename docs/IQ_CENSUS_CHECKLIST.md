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
| 0.5 | CASE/IF, CAST and coercion: branch unification, rounding versus truncation, string-to-number and string-to-date, DECIMAL scale, integer division, a zero divisor | partial. String to date reads the year-first spellings and, measured on IQ 17.1 (2026-10-16), blanks around the separators, a month's English name or a start of it in either order, AM and PM, and under IQ's engine a time alone on 1900-01-01: a computed string passes through linear replacements into one ISO spelling, then is parsed once. Done (2026-10-15, measured on IQ 17.1): a CAST to a shorter VARCHAR or CHAR is cut to n bytes, never inside a character, and CHAR(n) padded to n bytes (D-C2); a narrower NUMERIC, and a DOUBLE to a NUMERIC of at most 18 digits, truncate toward zero (D-N6); a number or a date to too short a text raises for that row (D-C1); a NUMERIC as text, in CAST, `\|\|` and STRING, keeps its 0 before the point (D-C8); SIGN and TRUNCNUM keep their argument's type (D-F19). A string read as an INTEGER or a NUMERIC by IQ's engine's own reading (white space around, a sign, digits with one point; no exponent; '' its error), cut toward zero. CASE, IF and COALESCE of a string and a number are IQ's error -1009145, refused. Refused by name: `CAST(x AS NUMERIC)` without a precision (a NUMERIC(126,38) under IQ's engine), a DOUBLE to a NUMERIC of more than 18 digits, a NUMERIC constant or a DOUBLE as text, a string that is no constant read as a DOUBLE (IQ's reading is not correctly rounded: '1.555' is 1.5550000000000002). Nothing open. |
| 0.6 | Joins, inner and outer: ON versus WHERE under outer joins, text and NULL keys, legacy outer-join syntax | done (2026-10-15): the T-SQL `*=` / `=*` outer joins as IQ 17.1 reads them (its PUBLIC tsql_outer_joins is On; Off, IQ's -1099): a WHERE condition that reads the NULL-extended table joins, the rest filters; a chain (-680), a cycle (-136) and an ANSI join beside them (-681) are IQ's errors, refused; under OR refused. Out: two output columns of one name (S73). KEY JOIN (IQ's foreign-key join) is IQ's own, and comes after Tier 0. |
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
| 2.16 | Statistical and list aggregates: STDDEV and VARIANCE (sample, population), MEDIAN, PERCENTILE_CONT/DISC, LIST's order (GROUP_CONCAT) | done, the rest refused by name. Done (2026-10-15, measured on IQ 17.1): PERCENTILE_CONT and PERCENTILE_DISC (WITHIN GROUP; IQ's errors for them raised before translating), MEDIAN of a NUMERIC (its type, the midpoint cut toward zero: D-F20) and of a DOUBLE. Refused by name: STDDEV and VARIANCE (IQ's engine computes them by a lossy formula of its own: VARIANCE of 1000000.1, .2, .3 is 0.0100 there, 0.01 exactly), PERCENTILE_DISC of a BIGINT, a NUMERIC of more than 15 digits, a string or a date, MEDIAN of a BIGINT (IQ's midpoint wraps). LIST ordered by another column, IQ's NULL first ascending and last descending; two keys, and two LISTs of one query ordered differently (D-A1: IQ may order both alike), refused. |
| 2.16b | Subtotals: ROLLUP, CUBE, GROUPING SETS, GROUPING() (BI tools' totals) | done (2026-10-16, measured on IQ 17.1). GROUP BY ROLLUP(...), CUBE(...), WITH ROLLUP, WITH CUBE and ( ) are read as a UNION ALL of their groupings. A key not grouped by is NULL of its type, and GROUPING(k) is 0 or 1. Keys may be select-list aliases (an alias before a column of its name). A grouping by nothing is one row even over no rows. TOP, DISTINCT and HAVING apply to the whole. IQ's errors, raised: GROUPING SETS, a key beside ROLLUP or CUBE, PERCENTILE beside CUBE, MEDIAN and LIST beside either (-1001030), GROUPING of no key (-149). Refused by name: ROLLUP or CUBE in a subquery, a derived table or a set operation's branch; CUBE of more than 6 keys; SELECT *; a window function beside it; an ORDER BY key that is none of the items. |
| 2.16c | CROSS APPLY, OUTER APPLY, LATERAL | done (2026-10-16, measured on IQ 17.1). IQ's engine answers APPLY of a query that reads nothing before it (CROSS APPLY a join, OUTER APPLY a LEFT JOIN on a true condition), of a table, of a FROM function, and of computed columns: a query of one row without FROM that reads the row before it, `CROSS APPLY (SELECT t.id * 2 AS d) x`, chained or joined after, OUTER as CROSS. Lite translates those as an extend of the left relation, in `SqlToPure`, so the Postgres island's FROM-less LATERAL is answered too. IQ's errors, raised: a query with FROM that reads the row before it is -1001030 (a lateral derived table, a correlated UNION) or the internal error -1006000 (an aggregate, DISTINCT, a window); LATERAL is -1001030; VALUES is -131; an APPLY without a name is -131. Refused by name: a FROM function's argument read from the row (sa_rowgenerator(1, t.id)). |
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

## The real-world corpus lane (2026-10-15)

The census ranks features; the corpus lane measures the island against real queries. `IqCorpusTest`
(`//core:iq_corpus_test`, manual) reads every query of each suite under `-Diq.corpus.root` as an island over the suite's
tables (empty, on DuckDB) and ranks what it refuses. The corpora stay private (licences, size): SEDE (11,708 Stack
Exchange Data Explorer queries, T-SQL), SQLStorm v1.0's SQL Server set (18,251), the Public BI benchmark (646 Tableau
queries), BIRD Mini-Dev (500). A query is ANSWERED, REFUSED (by name), IQ_ERROR (the island says IQ raises an error,
its syntax error or an error it names), DB_ERROR or CRASH.

| Suite | Answered before | Answered now | IQ's own error now | Refused by name now |
|---|---|---|---|---|
| SEDE | 40.6% | 56.8% | 15.5% | 27.4% |
| SQLStorm | 21.7% | 30.7% | 50.7% | 18.6% |
| Public BI | 93.0% | 94.4% | 2.6% | 2.9% |
| BIRD | 58.2% | 69.2% | 18.4% | 12.4% |

(2026-10-16. Answered fell a little where former wrong answers are now refused (the windowed derived tables, below).
IQ's own error now counts each refusal that names IQ's code, measured. In SQLStorm, 5,724 queries end in T-SQL's
OFFSET ... FETCH, IQ's syntax error -131. What lite does not answer is mostly SQL IQ itself rejects.)

Measured on the live IQ 17.1 and built (parity cases: `lite/corpus_gaps.yaml`):
- a statement's trailing separator (1,691 of SEDE's queries);
- COUNT's UNSIGNED BIGINT beside other numbers. With a signed integer IQ shows a NUMERIC(20,0) that it computes as a whole number (its `/` divides whole); with a NUMERIC, a NUMERIC of its 20 digits; with a DOUBLE, a DOUBLE. This covers COALESCE, ISNULL, CASE, NULLIF, CAST, SUM and AVG of counts, and NUMERIC and DOUBLE arithmetic (about 1,300 queries);
- NULLIF of numbers, by COALESCE's type;
- a DATE compared with a TIMESTAMP, at its midnight (227);
- a reading of the clock moved by DATEADD (225);
- ROUND of a NUMERIC past 18 digits;
- a quotient or a ROUND whose IQ type passes 38 digits (`COUNT(id) * 100.0 / COUNT(*)`, IQ's NUMERIC(44,21)): computed where the value fits, refused at run time where it does not;
- national strings (`N'...'`);
- GROUP BY expressions that read no select-list alias;
- REAL and FLOAT, IQ's single precision (`lite/iq_reals.yaml`), rounded by Veltkamp's split in double arithmetic: FLOAT and FLOAT(1..24) are REAL, FLOAT(25..53) DOUBLE; a value past the largest single is IQ's error, one below the smallest normal 0. Two REALs give a REAL in SQL Anywhere's front end, a DOUBLE (unrounded) under IQ's engine. A REAL with another number is a DOUBLE, in COALESCE, CASE and UNION too. SUM and AVG are DOUBLEs, MIN and MAX REALs;
- an aggregate's DISTINCT over an expression, and one in a SELECT DISTINCT, under IQ's engine (the research's D-R1 held that IQ restricts them; IQ 17.1 reads them);
- ROUND of a DOUBLE or a REAL. IQ's steps are the sign times floor(|x| * 10^n + 0.5) / 10^n, each step a DOUBLE: ROUND(2.675e0, 2) is 2.68 and ROUND(1.005e0, 2) 1.0. This holds for its front end and SQL Anywhere 17 alike;
- several clock readings in one statement under IQ's engine, which reads one clock. D-T13 is SQL Anywhere's front end's;
- the names IQ gives unaliased CASTs and CONVERTs. A whole item is named as its operand (`CAST(t.ts AS DATE)` is `ts`). One inside an item is written out in lower case: `cast(t.sm as numeric(10,2))`, `convert(varchar(10),t.sm)`. The type keeps its written spelling, except that INT is `integer`, DOUBLE PRECISION is `double` and FLOAT is `real`. This fixed 34 of the research's naming cases, under both judges;
- LIKE's `[set]` classes of ASCII members, ranges and a leading `^`;
- DATALENGTH: a string's bytes, and a column's storage width under IQ's engine;
- a table's `WITH (NOLOCK)` and `WITH (READUNCOMMITTED)`, read and dropped. After a derived table or a FROM function a hint is IQ's -131;
- a constant date string beside a DATE or a TIMESTAMP in COALESCE, ISNULL or CASE, read as one. `COALESCE(ts, '1900-01-01')` is a TIMESTAMP.
- a string function's count or position computed in 64 bits or a BIGINT (2026-10-17, 23 queries, `SUBSTRING(Tags, 2, LEN(Tags) - 2)`): read. Postgres's dialect narrows it to the 32-bit integer its substr, left, right, repeat, lpad and rpad take (`StringCountsAsInt`). A value past 32 bits is refused by name where the row computes it: IQ raises for a computed one and gives `''` for a BIGINT column's extremes. CHAR of a computed code stays refused (DuckDB's chr).
- a year alone, or a year and a month, as a date (2026-10-17): the month's first day. `'2010'`, `'2010-05'`, `'2010/5'`, `'2010 May'` and `'May 2010'` are read in comparisons, CAST and CONVERT, with a time after them or not.

Measured to be IQ's own errors, raised or named by the island:
- `OFFSET ... FETCH`, `LIMIT`, `INTERVAL` and `FILTER` are -131;
- `STRING_AGG`, `CONCAT`, `EOMONTH`, `FORMAT` and `STRING_SPLIT` are -265, though a user function could hold the name, so these stay refused by name;
- the T-SQL date parts `yyyy`, `d`, `m`, `ww`, `n`, `s` and `q`, `x.Comment`, `x.Date`, `x.Key`, `x.Order` and `x.User` (reserved words after a dot), and `Date`, `Comment`, `time` and `Key` standing alone are all -131;
- under IQ's engine:
  - TOP in any subquery, derived table or common table expression is -1001030 (the research's D-R1 had held that IQ may ignore it);
  - a subquery inside a conditional expression is -1001030;
  - a subquery in an outer join's ON is -1001047 unless its conjunct reads the null-supplying side alone (directly and through correlation; in a FULL JOIN always): 21 shapes measured. It is -1001047 too where no conjunct without a subquery reads a column beside it (2026-10-17: `ON u.id = (SELECT MAX(id) FROM u)` alone, or beside `1 = 1`; SQLStorm's 6 such queries over a common table expression);
  - an integer where DATEADD or DATEDIFF reads a date is -1001011;
  - CAST to NVARCHAR or NCHAR is -1001030;
  - a number compared with a string (`PostTypeId = '1'`, `YEAR(ts) = '2026'`, in IN, BETWEEN and CASE alike), and a date or timestamp compared with a number, is -1001013. A string that is no number is the front end's -157 first.
- numbers ending in a four-digit year (`'01/01/2018'`, `'8-16-2011'`, `'05/2010'`) are no date under DATE_ORDER YMD (2026-10-17, 55 of SEDE's queries). Compared, IQ's engine raises -157 beside a DATE column and -1009145 beside a TIMESTAMP or a computed DATE; SQL Anywhere's front end raises -157. In CAST and CONVERT, where IQ's engine raises -1001006, they stay refused by name, as every constant IQ cannot convert is.

Open, ranked by the corpora:
- CAST to DECIMAL without a precision, IQ's NUMERIC(126,38) (70);
- a subquery in a derived table's select list (192): built 2026-10-16, measured on IQ 17.1 over 40 shapes. IQ's engine reads such a subquery as if it were written where its column is read. Answered: unqualified reads in the select list (in arithmetic and functions too), in ORDER BY, under DISTINCT and TOP, and the derived table's other columns anywhere. IQ's errors, refused naming them: a qualified read of any of its columns (-1005004); the subquery's column in WHERE, GROUP BY or HAVING, or inside a conditional or an aggregate (-1001030). Still refused by name: a join beside it, a window over the column, and a nested derived table;
- a statement with TOP whose select list can raise (114): built 2026-10-16 (the user's decision), measured on IQ 17.1,
  SQL Anywhere 17 and Postgres 16 (`lite/corpus_gaps.yaml`'s TOP cases, `sql-parity/lite/math.yaml`).
  - IQ computes the items its ORDER BY does not read only for the rows it keeps: under its engine the rows up to the
    limit's last (START AT's skipped rows too), in SQL Anywhere (its front end, and SQL Anywhere 17) the rows it
    returns. ORDER BY's items, and all of a DISTINCT's, are computed for every row. Grouped queries and windows alike.
  - `SqlToPure.assemble` computes those items after the sort and the limit (`InputDialect.rowLimitItems`), and the
    Lowerer no longer merges an extend after a limit into the truncated select (`Fold.extendFolds`), which made the
    database compute it for every row first. Both lanes answer every measured case.
  - Postgres differs: it computes a cheap item (a division) for every row before its sort, so the Postgres island
    keeps that order;
- the outer join ON shapes IQ reads but DuckDB cannot plan: a subquery in a RIGHT JOIN's ON (refused by name). Built 2026-10-17: a LEFT JOIN's ON conjunct holding a scalar subquery and reading the null-supplying side alone (`ON u.t_id > 0 AND u.id = (SELECT MAX(id) FROM u)`) filters that side before the join in DuckDB's dialect (`OuterJoinSubqueryFilters`), which DuckDB cannot plan otherwise. SQLStorm's 6 queries once listed here are IQ's -1001047 (above);
- CROSS APPLY (676), which this round measured only as IQ's internal error on one correlated shape (2.16c).

The refusals the live IQ answers (2026-10-17): every query the island refused, or that lite's database failed, over
the dba.stackexchange sample was sent to the live IQ (`refused_suite`, 1,599 queries; IQ answers 582). By the
island's reason, those IQ answers, and what became of them:
- built this round: a second statement without a separator, named (85 of 108; out: the island reads one SELECT);
  month-first dates, IQ's error (55 of 61) and a year and a month, read (6); a string function's 64-bit count (23);
  LIKE's sets of any member (13 of 17); `*` in a grouped query showing its group keys (7); a GROUP BY of constants
  only, NULL too (4: IQ 17.1 reads it, where the research's D-R1 held that its engine restricts it); LOG and LOG10
  of a COUNT and STR of NTILE, IQ's unsigned results (13); DATEADD by a computed count, refused at run time past
  SQL Anywhere's bound only (10); NULLIF of a date and a date string (3); an IN subquery that is a UNION (5 of 8:
  INTERSECT and EXCEPT there are IQ's -1001030). Judged against IQ 17.1's stored answers: 110 PASS where 0 did;
  the rest are ties of ROW_NUMBER. A CONVERT style to a shorter string stays refused: IQ's engine cuts it in some
  places and raises -1009145 in others (a styled result compared with a string, CONVERT(CHAR(6), d, 112) ranked);
- measured once the live IQ returned (2026-10-17): IQ 17.1 reserves four words SQL Anywhere 17 does not, as no name
  anywhere (`aes_decrypt`, `clear`, `iq`, `timeline`: SEDE's `AS Timeline`), now the iq17 profiles' keywords; its other
  extra words of sa_reserved_words() are names (true and false only as values). A CONVERT style's text longer than
  its string is IQ's error for each row computing it (-1009145 under its engine, -638 in its front end; none over no
  rows), raised so, where the corpus's earlier PASS were queries no row reached. IQ's engine reads a DOUBLE from a
  17-digit string one unit off in 6 of 267 Tableau-style constants (its front end exactly), so such a CAST stays
  refused: an equality filter on it may match no row in IQ. A DOUBLE sum's overflow is its engine's -1001010 in every
  group, those TOP drops too (the paused AVG decision, measured);
- DATE, YEAR, DATEPART and DATEADD of a computed string (BIRD's `DATE(SUBSTR(event_date, 1, 10))`): read as CAST to
  TIMESTAMP reads it under IQ's engine (measured: the same values, its -1001006 alike);
- TRUE and FALSE (BIRD's `THEN TRUE ELSE FALSE`): IQ 17.1's constants 1 and 0, SMALLINTs as the literals are
  (`TRUE + 1` is 2), named `true` unaliased, no alias; the iq17 profiles read them so, and the listed true-keyword
  divergence is gone. NULLIF of a date and a string that is no date is its engine's -1001014 whether or not a row
  reaches it (it converts the constant as it plans; its front end's NULLIF(d, '') is d);
- a date string IQ cannot convert names both codes rather than one: which IQ reports is its evaluation order's (a
  DATEPART of a string's -1001011 may come first; a BETWEEN over a computed DATE in a grouped query is -157);
- lite's database failing on SEDE's empty sample tables (27 of 40): each answers over the data, PASS; the other 13
  are IQ's errors too (division by zero, the outer join ON rule above);
- AVG or SUM read for some groups only (39, Tableau's TOP n ... AVG(CAST(x AS double)) among them): built
  2026-10-17 where the statement's own SELECT reads it so (a CASE, a COALESCE, its row limit). IQ checks the running
  total for overflow in every group (D-E3; measured on IQ 17.1, its engine's -1001010 for a DOUBLE in a group TOP
  drops): the check is now also a filter of every group after the aggregation (`Aggregation.guard`), which no read
  pattern skips. Still refused by name: an aggregate of a derived table, a set operation's branch or a subquery, where
  a WHERE on a group key around it may reach below its aggregation;
- out, the 38 digits of lite's DuckDB decimals: CAST to DECIMAL without a precision (64, IQ's NUMERIC(126,38)), AVG
  of a wide NUMERIC (53, IQ's NUMERIC(127,59): '9.83333...' to 59 places), a NUMERIC quotient or product past 38
  digits (46), CAST of a NUMERIC to more places (8). IQ answers them exactly; DuckDB holds no decimal past 38 digits
  (Postgres's numeric would), so lite refuses by name rather than answer other digits;
- an expression nesting too many checked operations (39 of 44, a scale bound): an integer quotient is lite's own
  native now (`meta::legend::lite::quotient`, the database's integer division), where the islands' Float estimate read
  each operand about fourteen times (`CONVERT(int, LEN(x) * (1 + y) / 1000)`: 30 KB of SQL to 600 bytes); 5 more
  answer. A scalar subquery a select item computes on every path is computed once now, a column before the item
  (`SqlToPure.output`, both SQL islands): a correlated COUNT under a DOUBLE quotient was written 504 times (187 KB,
  1,520 SELECTs; now 86 KB, 10), and one more answers; the SE judge is unchanged but ties (PASS 9,932 -> 9,935). The
  rest repeated a long operand through checked operations' NULL and zero tests. Built 2026-10-17: an item past the
  size bound has its long operands (64 Pure nodes or more, read on every path) each computed once, bottom up, a
  column before it (`SqlToPure.output`), and the lowering writes a large computed column (48 SQL nodes or more) that
  later columns read twice in its own select rather than into each read (`Fold.rereadsLarge`). 32 of the 44 answer
  (sede-13007: 9.6 KB of SQL, where the bound lifted wrote 1.5 MB); 8 pass the bound still; the SE judge unchanged
  but ties;
- not built yet: the name IQ gives an
  unaliased expression it spells with its own rules (32: tables qualified, `count()`, an inner alias kept); RAND (8,
  in ORDER BY: no answer to judge); DATEADD's counts that may pass 32 bits (10); STR, LOG and LOG10 of an UNSIGNED
  number (13); CONVERT's styles to other lengths (5).

### Phase 2: the answers judged (2026-10-16)

The data is a sample of dba.stackexchange's (29 tables), loaded into the live IQ as `ll_*` tables and into each lane's
database. The live IQ answered all 12,445 SEDE and SQLStorm queries the island translates. The snapper's machinery
collected the answers (the scratchpad's `corpus_answers.py`; the data and the answers stay private). `IqCorpusTest`
judges them as parity runs (`-Diq.corpus.answers`), and `-Diq.corpus.rows` writes lite's rows of each FAIL. A FAIL
whose rows are IQ's as a multiset is PASS_UNORDERED: a real-world ORDER BY ties. Each remaining FAIL with an ORDER BY or
a TOP was judged again with every output position appended to its ORDER BY. IQ answered these tie-broken twins too.

| Verdict | First judge | Now |
|---|---|---|
| PASS | 10,471 | 10,433 |
| PASS_UNORDERED | 1,454 | 1,434 |
| BOTH_ERROR | 46 | 62 |
| REFUSED | 1 | 182 |
| FAIL | 437 | 319 |
| BUG (lite answers where IQ raises) | 20 | 5 |
| ERROR (lite raises where IQ answers) | 5 | 0 |

Every remaining FAIL is accounted for. Of the tie-broken twins:
- 148 PASS: their FAIL was ties only;
- 135 are ROW_NUMBER or NTILE numbering tied rows, each engine its own way;
- 10 read the clock;
- 7 are D-N3: IQ types a column by its data's range (`ROUND(1.0 * Score / ViewCount, 8)` is a NUMERIC(12,7) over this
  data);
- 2 are an IQ defect, listed under iq-defect: a WHERE or an outer join's condition on the null-supplying side of a
  windowed derived table's LEFT JOIN, applied below its windows;
- 1 is a name: a window ordered by aggregates in a grouped query.

The BUGs left are IQ's optimizer limit -1005015 on three Cartesian products, which no translation can know, and a
FROM-rewrite artifact of the answers driver.

Wrong answers found and fixed, each measured on the live IQ (parity cases: `lite/corpus_gaps.yaml`):
- a correlated read of an enclosing row's window column (a Lowerer fix);
- an ORDER BY name reads a select item only by its alias; otherwise it is a FROM column, an ambiguous one -144, DISTINCT
  too;
- a computed date-and-time string cast to DATE did not check its time;
- a WHERE over a windowed derived table or CTE that reads none of its window columns. Lite's lowering folded it into
  the window's WHERE, as Pure composes an extend and a filter (`Fold.filterSlot`), so the window ranked the filtered
  rows. Also, window functions over a WHERE that reads a derived window column (21 corpus queries). Fixed 2026-10-16
  in both SQL islands by inline views: `SqlToPure` marks a derived table or CTE whose own query computes windows
  (`meta::legend::lite::inlineView`), and `InlineViewChecker` stamps each window inside as a boundary, so the lowerer
  computes them in their own SELECT. The Postgres island had the same wrong answers: 4 Postgres-judged cases in
  `sql-parity/lite/windows.yaml`, now PASS.

Built, measured on the live IQ:
- a time alone read on 1900-01-01 under IQ's engine;
- a grouped query's unaliased items named by their own spelling under IQ's engine (56 queries);
- a CONVERT with a style named as IQ names it;
- computed date strings with month names, blanks and AM/PM.

IQ's errors raised by name, measured: a window function in a condition's subquery (-1001030); two subqueries compared
and a subquery as BETWEEN's operand (-1001030); an alias of a select-list subquery read in WHERE, GROUP BY or HAVING
(-1001030); a subquery inside a condition's expression, now naming IQ's -1001030.

Judged again on 2026-10-17, the lane rebuilt and the live IQ asked afresh (12,536 SEDE and SQLStorm queries
answered): PASS 10,643, PASS_UNORDERED 1,504, FAIL 357, BUG 8. The tie-broken twins of the FAILs: 166 PASS, and
every other FAIL is a numbering of tied rows (ROW_NUMBER, RANK: 166), a clock (12) or IQ's data-dependent NUMERIC
scale (D-N3, 12); none unexplained. The BUGs are IQ's defects and limits (its internal error -1006001 in an ORDER
BY, three times; its Cartesian-product limit -1005015, four) and one table alias `limit` IQ reads as its clause.
One more IQ defect found: rows tied on the ORDER BY can carry each other's columns in IQ's answer (sqlstorm-10142:
user 508's latest post is 2011-01-27 in the data, lite's answer; IQ's row gives user 50's 2011-01-06).

BIRD's answers judged too (2026-10-16). BIRD's 11 databases were sampled consistently by their foreign keys: parents
first, a child keeping the rows that reach kept parents, then filled to 600 rows a table (32,206 rows; the scratchpad's
`bird_sample.py`). They were loaded into the live IQ as `ll_b_*` and judged as the dba.stackexchange sample is:
- 342 PASS and 132 BOTH_ERROR: 474 of 500, 94.8%;
- 21 refused by name;
- 4 FAIL: 3 ties, and 1 name, fixed: a column named by a reserved word shows in double quotes, t."date";
- 1 ERROR: FALSE, a keyword in IQ 17.1 (the listed true-keyword divergence).

The landing's review (2026-10-16): three independent reviewers; the fixes are listed in `docs/IN_FLIGHT.md`, each
measured on IQ 17.1 and SQL Anywhere 17 where IQ decides (`lite/corpus_gaps.yaml`'s review cases). Known, recorded:
- ROLLUP or CUBE with TOP computes its items before the limit (the union's), so an item that raises for a dropped
  group raises where IQ answers;
- an APPLY's query that reads the left side by an unqualified name its own FROM lacks may bind an enclosing query's
  column, where IQ's engine raises -1001030;
- a rename's frame (the SQL islands' last step where an output is named apart from its column) carries no ORDER BY:
  the subselect's order rides alone, which DuckDB and Postgres keep, other databases need not;
- the listed iq17 divergences (`iq17-divergences.tsv`) answer where IQ answers otherwise or raises; refusing them by
  name under the iq17 profiles is the cheaper honest form (the review's finding 3), not done here.

Decisions for the user: none open. (Decided 2026-10-16 by the user and built, above: the window boundary, as inline
views; TOP's select list, computed after the limit.)
