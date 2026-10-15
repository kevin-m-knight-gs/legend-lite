# SAP IQ SQL in Pure: the `#IQ{...}#` island, the research behind it, and the plan

**Status: study and plan (2026-10-13), with the user's decisions recorded in §4. The line is announced in
`docs/IN_FLIGHT.md` ("A tenth line"), on the branch `claude/iq-island`. The first landing: §9.1; §9 records each slice
as it lands.** This is the counterpart of `docs/LEGEND_SQL_STUDY_2026_10_11.md`, which did the same for Postgres and
the `#SQL{...}#` island. Read that study's §3.4, §8 and §9 first: this line follows its method (the dialect is the
spec, a refusal by name beats a possibly wrong answer, answers snapped from the real database and ratcheted per case)
and reuses its translator.

§0 is the summary; §1–§3 are the ground (what IQ SQL is, the oracle, the research); §4 is the decisions; §5–§6 are
the design; §7 is the plan; §8 the risks; §9 the record of landings.

---

## 0. Start here

1. **What the island is.** `#IQ{...}#` makes a query written for SAP IQ 16.2 an ordinary Pure Relation expression:

   ```
   #IQ{SELECT TOP 3 dept, COUNT(*) AS n
       FROM tb('e2e::IQ.emp')
       WHERE salary / 1000 >= 50
       GROUP BY dept
       ORDER BY n DESC}#->filter(r | $r.n > 1)
   ```

   The translator reads IQ's SQL and builds the Pure a person would write (filter, groupBy, sort, limit over
   `#>{e2e::IQ.emp}#`). The compiler types it once, like any query, and it runs on any backend legend-lite supports.
   It returns **IQ's answer**: here `salary / 1000` truncates toward zero when `salary` is an integer column (D-N3),
   and a NULL sort key would sort as the lowest value.
   `#IQ:<profile>{...}#` names the connection profile the SQL was written for (§4, I2).
   **A worked example:** `docs/IQ_SQL_SHOWCASE.md` follows one non-trivial query end to end -- SQL Anywhere's answer,
   lite's identical one, the Pure it desugars to, and the SQL it plans for Snowflake and DuckDB (pinned by a test).
2. **Status.** The research is done and the decisions are taken (§4). The first landing (§7, PR1 to PR3) is announced
   in `IN_FLIGHT`, with its cross-area edits listed there, and built on the branch (§9.1).
3. **Where the research lives: the user's private repository `neema2/iq-sql-research`. None of it is copied here.**
   - Its rules quote SAP's manuals sentence by sentence. It also holds the TPC-H full disclosure reports run on Sybase
     IQ (their queries fall under the TPC EULA) and about 4,000 queries collected from public GitHub repositories,
     which belong to their authors.
   - This repository holds only what we wrote: behaviour in our own words, the research's ids, and page numbers.
     Nothing in it is SAP's text, a manual's example, a TPC query or a corpus query.
4. **How to cite the research** (the ids are stable):

   | Id | What it names | Example |
   |---|---|---|
   | GRAMMAR `L`, `E`, `C`, `F`, `T`, `Q`, `S`, `R`, `G`, `X`, `K` + number | one of the 244 grammar productions (lexical, expressions, conditions, functions, types, query expressions, SELECT, FROM, GROUP BY, statements, client syntax) | GRAMMAR L28 (parameter markers) |
   | `D-<group><n>` | one of SEMANTICS §5's 68 provisional decisions | D-N3 (integer division) |
   | `VP-…` | a probe in the verification pack for real IQ | VP-N1-01 |
   | `IQ p.N`, `IQ-prog p.N`, `SA p.N` | a page of the IQ 16.2 SQL Reference, the IQ 16.0 SP04 Programming guide, the SA 17 SQL Reference | IQ p.187 |
   | SEMANTICS §n, TYPING §n, OPTIONS §n | a section of the research's spec | SEMANTICS §2 |

5. **The one-line rule of the line:** zero FAIL, zero BUG, as in the Postgres line. Where IQ's answer is not known
   for certain, or legend-lite cannot compute it without Java computing a value, the translator refuses by name and
   position.

---

## 1. What IQ SQL is

### 1.1 Two engines behind one parser

SAP IQ 16.2 is a column-store engine behind a SQL Anywhere front end. The IQ server embeds a SQL Anywhere server that
holds the catalog and parses every statement (IQ p.187, p.1565, p.1739). A statement that reads an IQ-store table is
then executed by the IQ engine; anything else is executed by SQL Anywhere itself. So IQ has **one parser and two sets
of execution rules**:

- **The parser is shared.** Keywords, precedence, literal typing and string escapes never depend on which engine runs
  the statement (SEMANTICS §2.3). Everything SQL Anywhere 17 accepts is IQ syntax too.
- **The answers can differ.** The same expression over an IQ table and over a constant can give different values or
  types (§1.3). Community reports describe the same split from the outside (SEMANTICS §2.1).

### 1.2 Which engine runs a query, and why it decides the oracle

| Query | Engine | `engine` flag |
|---|---|---|
| No FROM; FROM only `SYS.DUMMY`, `sa_rowgenerator(...)`, constant derived tables, catalog tables or SA procedures | the SA front end | `sa` |
| FROM reads any IQ-store table, including `iq_dummy` (a one-row IQ table) and IQ temporary tables; its subqueries and derived tables too | the IQ engine | `iq` |
| IQ tables mixed with catalog tables | IQ (D-E1) | `iq` |

In legend-lite, a `tb('pkg::DB.schema.table')` source is a user table, which in IQ would live in the IQ store. So an
island over `tb()` sources is `engine=iq`, and an island over nothing, DUMMY or `sa_rowgenerator` is `engine=sa`
(§4, I3).

**This split decides the oracle.** IQ itself cannot be run here (§2.1), but its front end can: SQL Anywhere 17 is the
very program that answers IQ's `engine=sa` queries. So SA 17 is a true oracle for `engine=sa`, and for `engine=iq`
only where the rule does not differ between the engines (§2.4). Everything else rests on the IQ documentation, read
under a policy the user chose (§4, I4), and stays provisional until a real IQ server answers the verification pack.

### 1.3 Where the engines differ

The rows the translator must key on `engine` (SEMANTICS §2.2, §5). Each is in our own words; the id names the
decision that settles it provisionally.

| Behaviour | IQ engine (`iq`) | SA front end (`sa`) | Id |
|---|---|---|---|
| DATEDIFF in hours, minutes, seconds and finer | whole units elapsed, truncated | unit boundaries crossed (hours and seconds derived from finer boundaries) | IQ p.264; D-T5 |
| One-argument DAYS, MONTHS, WEEKS… | counted from 1 January of year 0 | counted from 29 February of year 0, 59 days later | D-T4 |
| CHAR(n) values | blank padded to n; the pad counts in lengths and concatenation, not in comparisons | CHAR behaves as VARCHAR | D-S1 |
| A date or time with missing parts | year 1900, day 1900-01-01 | the current year or date | D-T8 |
| Decimal result types | the research's formulas at precision 126, scale 38 | the same formulas, capped by the database's PRECISION and SCALE | D-N1 |
| A quotient of decimal constants | at least six fractional digits | the formula's scale (two digits for `1.0/3.0`) | D-N2 |
| `x = NULL` under ANSINULL off | IS NULL everywhere, CASE included | only in comparisons standing directly in WHERE, HAVING or ON | D-L1 |
| DATEADD, and a date plus or minus a number | a DATE stays a DATE | always a TIMESTAMP | D-T2, D-T12 |
| CONVERT to text without a style | the session's date and time format options | a fixed default style | D-C6 |
| COUNT's type | unsigned 64-bit | 32-bit INTEGER | D-F15 |
| CEIL, CEILING, FLOOR | the argument's type | DOUBLE | D-F2 |
| Several LIST calls ordered differently in one block | the first order applies to all | an error | D-A1 |
| ROUND of a tie | an IQ-only option picks away from zero (the default) or to even | always away from zero | IQ p.448 |

### 1.4 What makes it a dialect of its own

IQ SQL is not ANSI SQL with a few extensions: only 14% of real hand-written IQ and SA queries use nothing beyond an
ANSI core (§3.3). The differences from Postgres that the translator spells out from the first slice:

- **Names** match without regard to case, quoted or not, folding only A–Z. `[x]` and `` `x` `` are identifiers; `"x"`
  is an identifier or a string, as the `quoted_identifier` option says, and TDS logins turn it off (GRAMMAR L10–L12,
  L22).
- **Data compares by the database's settings.** An IQ database compares text case-sensitively and in byte order by
  default (every upper-case letter before every lower-case one); SA's default database compares without case. Case
  folding in UPPER, LOWER and case-insensitive comparison touches A–Z only, never `é`.
- **Trailing blanks.** Under blank padding, `=`, `<`, IN, DISTINCT and GROUP BY ignore them; LIKE does not (D-S5).
- **NULL is the lowest value:** first in ascending order, last in descending. `NULLS FIRST/LAST` is rejected (D-W1).
- **`+` is overloaded.** Two numbers add, two strings concatenate, a string and a number do decimal arithmetic. A
  single NULL operand of `||` or of string `+` counts as the empty string; two NULLs give NULL (D-S3).
- **Operators.** `^` is exclusive or, not a power. The bitwise operators bind tighter than `*`; unary minus binds
  looser than them (D-N4).
- **Integers.** `/` of two integers truncates toward zero and keeps an integer type; `%` takes the dividend's sign
  (D-N3). Overflow is an error, never a wider type, even for small literals: `32767` is a SMALLINT (TYPING §2–§3).
- **Literals.** Integers are typed by magnitude (SMALLINT, INTEGER, then NUMERIC), decimals by their digits without
  trailing zeros, E-notation as NUMERIC (D-N5). `DATE '…'` and `INTERVAL` literals are rejected; a string converts
  where it is used.
- **Comparisons** of a string with a number compare both as NUMERIC, not DOUBLE; a string with a date compares as a
  TIMESTAMP (SEMANTICS §3.4).
- **`IF c THEN a ELSE b ENDIF`** is NULL when `c` is unknown, ELSE or not.
- **Row limits.** `TOP n [START AT m]` (m counts from 1) and `FIRST`. A literal `TOP 0` is an error; an expression
  that is 0 gives no rows (D-Q1). `LIMIT` exists only when an option enables it (GRAMMAR S6).
- **T-SQL forms** are everyday IQ: `alias = expr`, `'string'` aliases, ISNULL, `CONVERT(type, value [, style])`, and
  `*=`/`=*` outer joins.
- **Joins.** NATURAL JOIN does not merge the shared column; `JOIN … USING` is rejected; KEY JOIN joins through the
  declared foreign keys.
- **No boolean values.** A predicate is not a value, and `TRUE` is a column name.
- **Backslash escapes** in strings are on by default and off on TDS logins (the `escape_character` option).

---

## 2. The oracle: SQL Anywhere 17 on this Mac

### 2.1 The install, and how it is reached

- **No IQ.** IQ 16.2 has no macOS build. The user ruled out the Linux trial for development: checks against real IQ
  wait for a licence, and the research's verification pack (166 probes) is ready for that day.
- **SQL Anywhere 17.0.11.7254 SP1** is installed at `/Applications/SQLAnywhere17`. Its binaries are x86_64 and run
  under Rosetta on this arm64 Mac.
- **No JVM driver.** Both SA JDBC jars load native x86_64 libraries, which an arm64 JVM cannot load; the only bundled
  JVM is an x86_64 Java 8. So Bazel's test JVMs cannot reach SA at all.
- **The working path:** Python, through `ctypes` over `libdbcapi_r.dylib`, run as
  `arch -x86_64 /Library/Developer/CommandLineTools/usr/bin/python3 -I` after sourcing
  `System/bin64/sa_config.sh`. The research's runner (`sarun.py`, `runprobes.sh`) is ported to
  `tools/iq-oracle/snap.py`. It starts its own server on a private port, restarts it after a crash, and skips a list
  of known crashers (§2.5).

### 2.2 Two databases, five profiles

`snap.py` creates both databases in a temporary directory and seeds both from `iq-parity/schema.yaml`:

| Database | Created as | Stands for |
|---|---|---|
| `lite_iq.db` | `dbinit -c -b -z ISO_BINENG`, then PUBLIC `precision` 126 and `scale` 38 | an IQ database's catalog store: case-sensitive, blank padding, ISO-8859-1 byte order, IQ's decimal limits |
| `lite_sa.db` | `dbinit` defaults | a plain SQL Anywhere database: case-insensitive, no padding, UTF8BIN, precision 30, scale 6 |

The five profiles (OPTIONS §9) map onto them. A profile is creation settings plus session options; the session options
are set with `SET TEMPORARY OPTION` before each case:

| Profile | Database | Session options on top of the defaults |
|---|---|---|
| `iq16-default` | `lite_iq` | none: a connection at the database's defaults (Embedded SQL, dbcapi) |
| `iq16-odbc` | `lite_iq` | the date and time formats the IQ ODBC driver and Interactive SQL set (six fractional digits in timestamps, none in times) |
| `iq16-tds` | `lite_iq` | what a TDS login (jConnect, Open Client) sets: ANSINULL off, QUOTED_IDENTIFIER off, DATE_ORDER MDY, ESCAPE_CHARACTER off |
| `sqla17-default` | `lite_sa` | none |
| `sqla17-tds` | `lite_sa` | as `iq16-tds` |

A caveat the research records (SEMANTICS §4.7): TDS and ODBC behaviour was reproduced by setting the options, never
over a real TDS or ODBC connection.

### 2.3 Snapped answers; CI judges from the file

- **The file:** `core/src/test/resources/iq-parity/iq-answers.jsonl`, one line per case and profile: the column names
  and SA types, the cells or the error (SQLCODE, SQLSTATE), the SA build, the database, and a `sqlSha` over the SQL,
  the profile and the schema.
- **CI judges from the committed file.** Neither lane talks to SA; they run the island on DuckDB and Postgres and
  compare with the snapped answer, by `SqlParity`'s rules.
- **The local drift check** replaces the Postgres line's live lane. `snap.py --check` asks SA again and fails on any
  difference; `--write` snaps new or changed cases. Each landing runs `--check` once and records it in its GATES
  entry. Between checks, the STALE guard (§6.3) stops an edited case from being judged by an old answer.
- **Not snapped:** volatile values (the clock, RAND, NEWID, the server's name and version), as in `sql-parity`.

### 2.4 What SA can judge, and what it cannot

| Case | Judge |
|---|---|
| `engine=sa` (no FROM, DUMMY, `sa_rowgenerator`) | SA's answer on the profile's database (`sa:lite_iq` or `sa:lite_sa`), except the defects of §2.5 |
| `engine=iq`, where the rule is engine-neutral: no row of §1.3, no per-engine decision, not in SEMANTICS §4 | SA's answer; the engine-neutrality guard (§6.3) proves the case is neutral |
| `engine=iq` under a per-engine decision the line has adopted | the decision: the case names `decision: D-xx` and carries its `expect:` |
| IQ-only functions and syntax (LAG, LEAD, NTILE, the PERCENTILE and DATEFLOOR families, LPAD, RPAD…), IQ-only options, IQ-store-only rules, restrictions only the IQ engine enforces (D-R1), the 92 rules where SA and the documentation disagree unexplained | a decision with `expect:`, or a refusal by name |

The seeded tables are SA tables, so SA executes them with front-end rules. That is exactly why an `engine=iq` case
over them is only judged where the rules agree.

### 2.5 SA 17 defects the tests must avoid

Found by the research (SEMANTICS §7.1), each with a minimal reproduction. None is ever reproduced, for either engine:

- **Server crashes:** FIRST_VALUE in its aggregate form with an ORDER BY inside the call; the smallest INTEGER `%` −1
  over INTEGER columns. Both are in the known-crashers list; no case may contain them.
- **ROLLUP, CUBE and GROUPING SETS** where two grouping columns are the same constant: the rolled-up column keeps its
  value instead of NULL.
- **`*=` over a constant derived table** keeps the constant for unmatched rows instead of NULL.
- **Timestamps from 7911-01-01 on** lose their hours and minutes, a constant's (`'7911-01-01 10:30'` reads as
  7911-01-01 00:00) and DATEADD's result of any part (`9999-12-31 22:30` plus an hour is `9999-12-31 00:00`,
  `2026-01-01 10:30` plus 6000 years `8026-01-01 00:00`); there a DATEADD past 9999-12-31 below a day answers rather
  than raising -158, and one of an hour onto 7911-01-01 raises it. The schema holds none; the island refuses such a
  constant with a time, and a DATEADD that may reach those years with a time of day (IQ island, 2026-10-14: review
  4's M2, §9.11).
- **Also:** a decimal beyond its declared precision prints as `?` instead of an overflow error; `f(constant) IS NULL`
  is folded without evaluating `f`; DATEADD in milliseconds drops microseconds; STDDEV loses all precision for large
  equal values; RAND collapses when grouped in a derived table.
- **Constant expressions are typed from their value** (`ABS` of a BIGINT constant is a one-digit NUMERIC). Types are
  therefore judged over the schema's typed columns, never over constants (TYPING, method caveat).

---

## 3. The research, in numbers

### 3.1 Sources

- **Read in full:** the SAP IQ 16.2 SQL Reference (2,174 pages) and the SQL Anywhere 17 SQL Reference (1,972 pages).
  The IQ 16.0 SP04 Programming guide was read for its OLAP appendix and client chapters.
- **Coverage audit:** all 4,204 outline headings of the three were walked (1,384, 1,745 and 1,075); no relevant one is
  left uncovered.
- **Measured:** every claim that could be probed was run on SA 17 against both test databases. "Measurements override
  documents" is the research's rule.

### 3.2 The spec

| Part | Size | Status |
|---|---|---|
| Rules from the manuals | 3,480 | 1,672 verified on SA (26 after correcting the reader); 283 where the manual gives no value and SA's is recorded; 837 documentation only (212 IQ-only features, 36 IQ-store rules, 13 options SA lacks, 576 not probe-able); 92 where SA and the manual disagree unexplained, all settled by decisions; 60 IQ restrictions SA does not enforce; 38 manual errors; 4 that crash SA |
| Grammar | 244 productions | each with its source and a policy: accept, flag, warn, reject, gated by an option, or accept-and-translate (client layers only) |
| Syntax cases | 11,304 | 8,551 accept, 2,447 reject, 179 flag, 127 gated by an option; each with SA's measured outcome |
| Reserved words | 255 | each measured as a bare alias on SA 17 |
| Options | 385 (IQ ∪ SA) | tiers 17 / 18 / 31 / 319: modelled in every profile, modelled when reached, pinned and refused otherwise, ignored |
| Typing | 22,969 cases | operators, comparisons, casts, functions, aggregates and unification over 24 types on both databases (85,716 probe runs); the derived formulas reproduce 13,320 of 13,322 measured result types |
| Decisions | 68 | 46 follow the IQ manual, 8 follow SA 17, 14 follow corroborating evidence where the manual contradicts itself; 49 apply to `engine=iq` only |
| Verification pack | 166 probes | 132 confirm or overturn a decision; 34 cover IQ-engine rules and community reports. Ready to run on real IQ |

### 3.3 Real-world SQL

- **The corpus:** 4,046 distinct SELECTs from 121 public repositories; 3,208 of them hand-written (89 repositories),
  without templated SQL, test fixtures and generated DDL guards. Contexts: 2,759 bare statements, 1,133 parenthesised
  queries inside procedural code, 84 INSERT … SELECT, 68 CREATE VIEW.
- **SA 17 parses 95.3% of it.** Of 171 syntax errors, 30 are genuine dialect issues (ODBC `{oj}` escapes, IQ-only LAG
  and LEAD, legacy reserved words as names, user domains, `= *` with a space). No real query needed a form the grammar
  lacks.
- **The most common constructs** (share of hand-written queries): owner-qualified tables 41.8%, SELECT … INTO 23.0%,
  `:host` variables 16.2%, `@variables` 14.7%, SA catalog tables 13.0%, comma joins 11.9%, quoted identifiers 11.8%.
- **Coverage by milestone** (a query is covered when every construct in it is in that milestone or an earlier one):

  | Milestone | Hand-written queries | Repository-weighted |
  |---|---|---|
  | M1, the core dialect SELECT | **86.3%** | 83.6% |
  | M2, the Watcom and T-SQL library, KEY JOIN, `*=`, ODBC escapes | **97.7%** | 96.3% |
  | M3, windows, CTEs, rare clauses | 100% | 100% |

  An ANSI-only core covers **14.0%** (33.0% with owner-qualified names). M1 without the catalog track covers 68.6%:
  20.3% of queries read catalog tables, `sp_iq*` or `sa_*` procedures, or DUMMY.

### 3.4 Other tools

- **No existing tool implements IQ or SQL Anywhere semantics.** sqlglot has no Sybase dialect, and its T-SQL dialect
  gives wrong answers for IQ input in at least nine reproduced places. The one open-source SA → Postgres converter is
  syntax-level, GPL, with known semantic errors. Calcite renders `START AT` off by one. There is no Trino, Spark,
  sqlparser-rs, ZetaSQL or JSqlParser dialect.
- **T-SQL answers are not IQ answers.** Of 4,196 Babelfish test SELECTs that both SQL Server and SA accept, 62.5% give
  the same value on SA. Such suites are query corpora to re-answer on SA, never expected outputs.
- **Probes from other projects:** 892 claims harvested from about 35 projects (jOOQ's Sybase dialect the richest): on
  SA's defaults 629 confirmed, 231 contradicted. A red team wrote 969 cases in other dialects' habits; SA accepted 664.

So the translator is built from the spec and the oracle, as the Postgres one was built from Postgres's manual and
source; there is nothing to port.

---

## 4. Decisions

**Decided by the user, 2026-10-13** (`I` for island, distinct from the research's `D-` ids):

- **I1. The island: `#IQ{...}#`, and `#IQ:<profile>{...}#`.** The default profile is `iq16-default`. The LEGEND_ENGINE
  surface keeps its refusal of unknown island types; upstream has no IQ island.
- **I2. A closed profile set:** `iq16-default`, `iq16-odbc`, `iq16-tds`, `sqla17-default`, `sqla17-tds` (§2.2). Any
  other name is refused by name. A profile is fixed for the island; per-island option overrides are not offered.
- **I3. `engine=auto`.** An island over `tb()` sources is `engine=iq`; one with no FROM, or over DUMMY or
  `sa_rowgenerator`, is `engine=sa`; a mix is `iq` (D-E1). **Only the typing and function layer reads the engine,
  never the parser**, because IQ's parser is shared (§1.1).
- **I4. Split by engine, with provisional decisions.** Queries on IQ tables follow the IQ documentation; constant and
  catalog-only queries follow SA 17; where the manual contradicts itself, the side independent evidence supports wins;
  where it is silent, SA's value holds for both engines (D-P1). Every result of the policy is provisional until the
  verification pack runs on real IQ. Within it:
  - **Adopted,** low risk: for example D-N3 (integer division and overflow), D-Q1 (literal TOP 0), D-S3 (NULL || NULL
    is NULL), D-L1 (ANSINULL off rewrites everywhere under `engine=iq`).
  - **Refused by name until verified,** the research's highest risks: **D-N1** (IQ-engine decimal types), **D-N6**
    (narrowing a DOUBLE to NUMERIC truncates), **D-S5** (LIKE over padded CHAR), **D-C6** (CONVERT without a style),
    **D-T2/D-T12** (DATEADD and date ± n keep DATE), **D-F1** (ROUND of a DOUBLE typed NUMERIC). The refusal is
    UNSUPPORTED and its message names the decision.
  - The rest of the research's ten highest risks (D-R1, D-C7, D-N2, D-C1/D-C2) are reached in later slices; each is
    put to the same test when its slice comes. **A refusal by name is always better than a possibly wrong answer.**
  - **D-E2, provisional (the lead, 2026-10-14; the second review's B2).** The research has no rule for the order in
    which a search condition's operands are evaluated, and its D-E1 is the engine of a mixed statement, so this one
    takes the next number. When an operand of AND or OR in a WHERE can raise a run-time error (an integer result out
    of its type, a zero divisor), whether it is computed for a row that another operand already decides is a matter
    of the plan, in IQ and in lite's databases alike: SQL Anywhere computed such an operand for rows another operand
    excluded, except when an equality filter ran first, while DuckDB and Postgres skip it. So lite may answer where
    IQ raises (or raise where IQ answers); where no row raises, the two answers never differ. Ordinary WHERE
    arithmetic is not refused. A parity case whose WHERE puts such an operand beside AND or OR is not judged (`skip:
    plan-dependent run-time error (D-E2)`) or is rewritten, and the harness fails a judged one (§6.3). AND and OR
    inside a CASE's or an IF's condition, and the arguments of COALESCE, ISNULL, CASE and IF, are lazy in SQL
    Anywhere as in lite: outside the decision. (2026-10-14, the third review's M4: a HAVING is such a search condition
    too, and a SUM or an AVG an operand that can raise; the harness reads both.)
  - **Refused by name after the second review (2026-10-14):** a select-list alias read inside an ORDER BY expression
    (IQ reads the alias there; a bare alias or a position is read as before); an operation that can raise (a result
    that may leave its type, a divisor that may be zero) in the select list or ORDER BY of a statement with TOP,
    FIRST or START AT (IQ computes the select list only for the rows it keeps; lite's databases may compute it for
    the rows the limit drops); TOP, FIRST or START AT over a FROM clause without ORDER BY (the rows kept are the
    plan's choice); an expression whose checked operations would write out more than a bound of Pure nodes (each
    repeats its operands: about fourfold per nested level, twentyfold per quotient). A constant result out of its
    type's range is IQ's error at translation only when every operand is a constant IQ folds and none is NULL;
    otherwise the check runs where the value is computed. A BIGINT result is never checked by lite: the database
    raises its own overflow first.
  - **D-E3, provisional (M1c, 2026-10-14; for the lead).** SQL Anywhere checks a SUM's (and an AVG's) running total
    against the result type in the order its plan reads the rows (measured: 2147483647, 1, -5 raises -158 in one order
    and answers 2147483643 in another), and IQ's order is its own plan's too. The island raises IQ's -158 wherever some
    order could overflow: the sum of the positive values above the type's largest, or of the negative values below its
    smallest. It never answers where SQL Anywhere raises; where only some orders overflow it raises and SQL Anywhere may
    answer. A parity case in that zone is not judged (`skip: plan-dependent run-time error (D-E3)`). (2026-10-14, the
    third review's B1: the check is computed where the sum is read, and SQL Anywhere checks every group in the
    aggregation, so a checked SUM or AVG read only for some groups -- inside IF or CASE, an argument of COALESCE,
    ISNULL, IFNULL, NULLIF or ARGN, under AND, OR, NOT, a truth test, BETWEEN or IN in HAVING, under a row limit, in a
    subquery -- is refused by name; read everywhere, it raises as before.)
  - **D-T13, provisional (the lead, 2026-10-14; the third review's M3).** lite's clock is its database session's, in
    UTC (DuckDB's, and the embedded Postgres's under `SET TimeZone='UTC'`); IQ's is the server's local time. A query
    that shows the clock, or compares it with data or a constant near the hours they differ by, answers otherwise
    wherever the server is not on UTC; parity cases compare clock readings only with each other (or far from any
    edge). SQL Anywhere reads GETDATE() apart from a statement's other readings (measured: `GETDATE() = CURRENT
    TIMESTAMP` is mostly false, `GETDATE() < NOW()` mostly true), where lite reads one clock per statement: a
    statement reading GETDATE() beside another reading of the clock is refused by name; NOW(), CURRENT TIMESTAMP,
    TODAY() and CURRENT DATE are one statement time in both.
- **I5. The oracle is local; CI judges the committed answers** (§2.3), guarded so that the file cannot silently fall
  behind the cases (§6.3).
- **I6. One translator.** `SqlToPure` gains an input-dialect seam and Postgres's behaviour moves behind it unchanged;
  the IQ reader is new (§5). No fork.
- **I7. Nothing from SAP's manuals in this repository** (§0, point 3), and no TPC or corpus query text.

**Open:**

- **O1. The natives the island needs** (§5.5), each announced to the database owner (the ninth line owns
  `sql/dialect/`) before it lands, and refused by name until then:
  - an **ASCII-only case fold** for UPPER, LOWER and case-insensitive keys (Pure's `toUpper`/`toLower` render the
    database's Unicode functions);
  - an **ASCII-only rtrim** for blank padding (DuckDB's `rtrim` strips every Unicode space; the Postgres study's §9.4
    left the same question to the user);
  - **exact decimal division** at the formula's scale, truncating (`divide(x, y, n)` goes through a double on DuckDB).
- **O2. Owner-qualified names** (41.8% of real queries). What `dba.t` means without `tb()`: the schema `dba` of a
  Database the island names once, a package, or a profile setting. Until decided, a bare owner-qualified name is
  refused by name. **Drafted by M1e (IQ island, 2026-10-14; provisional, for the lead; §9.7):** the model is the
  island's catalog. An IQ owner is a schema of the model's Databases, matched as IQ matches names; the tables declared
  outside any schema are the island's user's, `DBA` (the user IQ and SQL Anywhere create a database with, the oracle's),
  as are an unqualified name's and T-SQL's `db..t`; a database's and a server's qualifiers are read and ignored, as IQ
  ignores them. An owner that is no schema (nor `DBA`) is IQ's -140, an owner's table not there IQ's -141; a name two
  Databases hold, an unqualified one `DBA` does not own (IQ would search its catalog too) and IQ's own users' tables
  (its catalog: `SYS`, `dbo`, `PUBLIC`, ...) are refused by name; `tb('...')` always names one table.
- **O3. Host variables and parameters.** `@var`, `:name` and `?` as Pure variables in scope (the Postgres island's
  `var('name')` is the precedent). Open: how a positional `?` binds, and the research's ruling that a column wins over
  a variable of the same name (GRAMMAR L27). **Drafted by M1e (IQ island, 2026-10-14; provisional, for the lead;
  §9.7):** `@name` and `:name` read the enclosing Pure query's variable `$name` in scope, its name as written (Pure's
  names are case-sensitive), typed by its Pure type (an Integer as IQ's BIGINT, a Float a DOUBLE, a `Decimal(p,s)` that
  NUMERIC, a String a VARCHAR, a StrictDate a DATE, a DateTime a TIMESTAMP; nullable when it may hold none); a column of
  `@name`'s name in scope is read first (GRAMMAR L27; measured with `tsql_variables` Off, and refused by name under a
  TDS profile, where a client binds `@name` as a placeholder that reads its value: §9.15); none is IQ's -143 (`@name`)
  or -188 (`:name`). A positional `?`
  (and `:?`) names nothing in scope: refused by name. An unaliased item over a variable is refused (the oracle can only
  run the value written in, which SQL Anywhere names otherwise).
- **O4. Error text.** Recommended: an error is judged by being an error, as in `sql-parity`; the snapped file keeps
  SQLCODE and SQLSTATE, and lite's messages are its own words naming IQ's code where there is one.

---

## 5. Architecture

```
Typer's island arm ─▶ compiler/spec/SqlIslands: picks the dialect, types each FROM source, passes store types
                              │
                              ▼
#IQ{...}#  ─▶ IqLexer, IqParser    (protocol/sql/iq/) ─┐
#SQL{...}# ─▶ SqlLexer, SqlParser  (protocol/sql/)    ─┴─▶ SqlAst ─▶ SqlToPure + InputDialect
                                                                    (PostgresDialect | IqDialect)
                                                                           │ a Relation ValueSpecification
                                                                           ▼
                                             NameResolver ─▶ Typer types it once ─▶ G … K   (unchanged)
```

### 5.1 One translator, an input-dialect seam

`SqlToPure` (6,224 lines at 987c37e18) already holds the hard, dialect-free machinery: scopes and bindings, joins,
set operations and WITH, plain and grouped selects, windows, three-valued logic, subqueries, BETWEEN, IN and IS
DISTINCT, the exact integer quotient, and the Pure builders. PR1 moves only what is Postgres's own behind an
`InputDialect` interface, into `PostgresDialect`:

| Behind `InputDialect` | Postgres today | IQ |
|---|---|---|
| reading the text | `SqlLexer`, `SqlParser` | `IqLexer`, `IqParser` |
| names: fold, resolve, hint | fold unquoted to lower case, then a unique case-insensitive match (D1 of the Postgres study) | ASCII case-insensitive, quoted or not; two candidates are an ambiguity error |
| default null order | NULLS LAST ascending | NULL lowest: first ascending |
| a select item's default name | `?column?` | the expression's text (so the AST keeps each item's source span) |
| literal and operator typing; unification | Postgres's | IQ's lattice and formulas (TYPING §1–§8) |
| the function catalog, casts, LIKE | Postgres's | IQ's, per engine |
| messages | Postgres's | our own words with IQ's codes (O4) |

- **Interface shape:** `parse(sql, profile)`, `resolve(ident, names, what)`, `nullsFirst(desc)`,
  `defaultName(expr, text)`, `typeOf(pureType, storeType)`, `literal(expr)`, `binary(op, l, r, pos)`,
  `unify(what, types)`, `function(call, args)`, `cast(t, typeName)`, `like(...)`, `message(problem, args)`.
- **Types:** the translator's `Tx` gains an opaque, nullable type detail. Postgres leaves it null; IQ puts an
  `IqType` there (family, integer width, unsigned, precision and scale, CHAR length).
- **Errors:** the `SqlTranslationException` call sites stay where they are; only the text comes from the dialect.
- **The judge of PR1:** both `sql-parity` ledgers unchanged, and the generated Pure of every parity case identical
  before and after (a one-off dump through `PureComposer`). `SqlToPureTest`, `SqlParserTest` and
  `PostgresDateTimeTest` stay green.

### 5.2 The IQ reader: `protocol/sql/iq/`

- **`IqLexer`** reads under the profile's options: `[x]` and backtick identifiers; `"x"` per `quoted_identifier`;
  backslash escapes per `escape_character`; nested comments and `//`; money and binary literals refused by name;
  glued tokens warned (GRAMMAR L1–L31).
- **`IqParser`** produces the shared `SqlAst`. `TOP`, `FIRST` and `START AT` become limit and offset.
  `IF c THEN a ELSE b ENDIF` becomes `CASE WHEN c THEN a WHEN NOT c THEN b END`, so an unknown condition gives NULL.
  T-SQL `alias = expr` and string aliases are aliases. Forms from other dialects are rejected with a message naming
  the IQ spelling (GRAMMAR §13).
- **`IqDialect`, `IqTypes`, `IqFunctions`, `IqProfile`;** later `IqDateTime` (IQ's date spellings, DATE_ORDER and
  NEAREST_CENTURY), the counterpart of `PostgresDateTime`.

### 5.3 Plumbing (the cross-area edits announced in `IN_FLIGHT`)

- **`protocol/spec/SqlIsland`** gains the island's language and a nullable profile, rather than a new record, so
  `ValueSpecification`, `Typer` and `NameResolver` stay untouched.
- **`parser/SpecParser`** gains `case "IQ"` and the `IQ:<profile>` form. The lexer needs no change: an island's tag
  is everything between `#` and `{`.
- **The wire and the printers:** a `classInstance` of type `IQ` with the value `{sql, profile}`, in
  `SpecIslandReader`, `ProtocolEmitter` and `PureComposer`.
- **`compiler/spec/SqlIslands`** picks the dialect by language, keeps each language's opening length for positions,
  and passes the store's column types (§5.4).
- **Docs:** a LITE-DESIGN family for the island in `docs/OWN_CORPUS_DECISIONS.md`, and `docs/SEMANTICS_REGISTER.md`
  rows for what lite cannot represent (§8).

### 5.4 Store types through the seam

IQ's answers depend on declared types the Pure type does not carry: an INT and a SMALLINT are both Integer, a
NUMERIC(10,2) and a NUMERIC(38,0) both Decimal, and a CHAR(10) pads where a VARCHAR(10) does not. The compiler knows
them: `TypedTableReference.storedTypes()` holds each column's declared store type. `SqlIslands` spells it as text and
passes it in the translator's `Column` callback, beside the Pure type. Text, because the protocol layer may not import
`com.legend.sql` (`ArchitectureTest.protocolIsTheBottomLayer`). A derived table or subquery loses the store type; IQ's
rules then apply to the Pure type's widest reading, or the translator refuses where the width would change the
answer.

### 5.5 Where natives are needed: Java never computes a value

As in the Postgres line (`docs/TENET_CHARTER.md` C2), a missing semantic is a new Pure native or a refusal by name,
never Java computing a value:

| IQ behaviour | How |
|---|---|
| ASCII-only case fold; CASE IGNORE keys | a new native, or a composition of `replace` (O1) |
| blank padding: comparisons ignore trailing spaces, only U+0020 | an ASCII rtrim (O1) |
| exact decimal `/` at the formula's scale | a lowering fix for the database owner (O1); until then decimal `/` is refused |
| checked SMALLINT and INT overflow, `/` and `%` by zero | built from existing natives, as `SqlToPure`'s range checks already are |
| CHAR(n) padding under `engine=iq` | `rpad` to n, from the store type (D-S1) |
| byte-order collation | already: DuckDB and the embedded Postgres (`--no-locale`) compare bytes |
| unsigned integers, TIME, BINARY | refused by name |

### 5.6 Constraints

- **Invariant 7b:** `protocol/**` depends only on protocol, values, JSON and the JDK.
- **TeaVM** compiles core to WebAssembly: no JDK method it lacks (`String.isBlank`, `Character.toString(int)`). Each
  landing builds `//sdlc-server:page` and `//wasm:planner`.
- **Guards** move only with a dated justification naming this line: `CodeShapeGuardrailTest` (the new lexer's and
  parser's cursor fields), `PlatformNamesGuardrailTest` (keyword arms; prefer `Set` and `Map` lookups).
- **`//parser-equivalence:diagnostics`** runs on every landing that touches a parser, lexer or protocol file; its
  `parser_parity` and `round_trip_proof` stay unchanged.

---

## 6. The harness

### 6.1 Files: `core/src/test/resources/iq-parity/`

| File | Contents |
|---|---|
| `schema.yaml` | the tables, in IQ types, seeded with edge values (NULLs, trailing blanks, mixed case, range limits). Typed columns, not constants, so SA's constant folding (§2.5) cannot change a type |
| `lite/*.yaml` | the cases: `id`, `sql`, optional `profile`, `engine`, `skip`; a provisional case adds `decision: D-xx` and `expect:` |
| `iq-answers.jsonl` | SA's answer per case and profile (§2.3) |
| `ledger.tsv` | per case: the DuckDB status, the Postgres status, and the judge (`sa:lite_iq`, `sa:lite_sa` or `D-xx`) |
| `syntax-cases.jsonl` | derived from the research: id, probe SQL, options, policy, SA's outcome. No rule text, no notes, and no probe that reproduces a manual's example. The derivation (re-run 2026-10-14) keeps only the research's own probe families and drops a case whose note or rule marks a documentation example, whose probe shares a run of six words with the manuals' text, or holds a name or a string of twelve characters or more that the manuals print |

### 6.2 The ledger and the lanes

- **`testcases/IqParity.java`** reuses `SqlParity`'s grid, comparison, statuses, ratchet, ledger I/O and YAML reader.
  It has its own `outcome()` (`SqlParity` writes `#SQL{` itself) and its own FROM rewrite, through `IqLexer`.
- **Statuses** as in `sql-parity`: PASS, FAIL (a wrong answer), BUG (answers what the judge rejects), ERROR (errs where
  the judge answers), REFUSED (by name), BOTH_ERROR, SKIP. A changed status fails the run (REGRESSION, FIX DETECTED,
  STATUS MISMATCH); `-Diq.parity.update` rewrites the ledger.
- **The lanes:** `//core:iq_parity_test` (DuckDB) and `//core:iq_parity_postgres_test` (embedded Postgres), both in
  `//gates:core`. The Postgres lane runs the translated Pure on Postgres; unlike `sql-parity`'s, it has no drift step,
  since its oracle is not Postgres.
- **The target:** 0 FAIL, 0 BUG, 0 ERROR in both lanes, always.

### 6.3 Guards

- **UNSNAPPED:** a case and profile with no answer in the file fails.
- **STALE:** an answer whose `sqlSha` does not match the case's SQL, profile and schema fails, so an edited case is
  never judged by its old answer.
- **GONE:** an answer whose case no longer exists fails.
- **Engine neutrality:** a case judged by `sa` with `engine=auto` over `tb()` sources is translated under both engines.
  If the two Pure texts differ, the rule is engine-dependent, and the case must either name its decision or pin
  `engine: sa`. This is the mechanical form of §2.4.
- **Crashers:** `snap.py` refuses to send a case matching the known-crashers list.
- **Evaluation order (D-E2, §4):** a judged case whose WHERE puts arithmetic or NULLIF over a column beside AND or OR
  fails the lane: its answer depends on IQ's plan, so it is skipped or rewritten.
- **An `engine: iq` pin needs a decision:** a case SQL Anywhere judges must translate alike under both engines' rules,
  which a pin to the IQ engine alone would hide.
- **The judge** compares a both-error's SQLCODE when legend-lite's error names IQ's (`(IQ: SQLCODE n, ...)`): another
  code than SQL Anywhere's is a FAIL. It compares a NUMERIC column's scale with each of lite's decimal cells.

### 6.4 The syntax-case ratchet

`IqSyntaxCasesTest` reads `syntax-cases.jsonl` and parses each probe under its options. The parser's verdict is
compared with the policy: accept parses; reject is a syntax error (or the named post-parse error); flag parses with a
warning under `engine=iq`; gated follows its option. The count of agreeing cases is ratcheted: it only goes up, and a
case that agreed and stops agreeing fails. Parsing runs ahead of translation: a form may parse long before it
translates, and until it translates it is refused by name.

---

## 7. Milestones and slices

The milestones are the research's (BUILD_PRIORITY), ordered by real use; the slices are this line's landings. Each
landing: `//gates:local`, the TeaVM builds, CI, an independent review, a GATES entry, this document's §9, `IN_FLIGHT`.

| Milestone | Content | Real queries covered (cumulative) |
|---|---|---|
| **M0** foundation | lexer, names, the option vector, profiles | a prerequisite |
| **M1** core dialect SELECT | 50 constructs (below) | 86.3% |
| **M2** Watcom and T-SQL library | LIST, DATEADD/DATEDIFF/DATEPART per engine, KEY JOIN, `*=`, CONVERT styles, ROUND/TRUNCNUM, CHARINDEX, ODBC escapes | 97.7% |
| **M3** windows, CTEs, rare clauses | OVER and the WINDOW clause, IQ's LAG/LEAD, WITH, LIKE `[set]`, NATURAL JOIN, APPLY | 100% |
| **M4** statement contexts | parse and refuse with a message: INTO, SET, DECLARE, procedural wrappers, DML | the contexts real queries arrive in |
| **M5** completeness | ROLLUP/CUBE/GROUPING SETS, INTERSECT/EXCEPT, PIVOT, MEDIAN and the statistics, hints, §13 lenient rewrites | no corpus use; the case set |

**The slices** (each construct with its count in the 3,208 hand-written queries):

| Slice | Milestone | Content |
|---|---|---|
| **PR1** seam | — | `InputDialect`, `PostgresDialect`: a pure move, judged as §5.1 says. No IQ behaviour |
| **PR2** pipeline | M0 | `#IQ{SELECT … FROM tb(…) WHERE … ORDER BY …}#` under `iq16-default`; `IqLexer` (the M0 productions), an `IqParser` skeleton, `IqDialect` (names, null order, messages), `IqProfile`; the plumbing (§5.3); the harness with about 40 cases; unit tests and a protocol round trip. Reaches `SELECT *` 323, ORDER BY 390 (by ordinal 41), quoted identifiers 378, `[bracket]` 36 |
| **PR3** rest of M0 | M0 | `alias = expr` 45, string aliases 39; TOP 73, FIRST 33, TOP … START AT (literal TOP 0 an error, PERCENT refused); IF … ENDIF 62; ISNULL 116 with IQ's unification; `+` 75 (string with number refused until PR4); `\|\|` 46; integer `/` 35 and `%` (literal widths, result widths per TYPING §3, checked ranges, truncation; decimal `/` refused); store types; `IqSyntaxCasesTest`; `lite/m0.yaml`, about 120 cases |
| **PR4** M1a | M1 | comparisons with IQ's conversions (string with number as NUMERIC, string with date as TIMESTAMP); BETWEEN 73, IN, CASE 146, IS [NOT] TRUE/FALSE/UNKNOWN; LIKE 125 without `[set]`; EXISTS 79; DUMMY 75 and `sa_rowgenerator` (a range, flattened, as `generate_series` is for Postgres); `lite/rules.yaml`, generated from the research's rule probes, `engine=sa` |
| **PR5** M1b | M1 | comma joins 381, outer joins 105 and CROSS JOIN 7, derived tables 204, subqueries 410, IN (subquery) 59, UNION 112 with IQ's union typing; NATURAL JOIN without merging |
| **PR6** M1c | M1 | GROUP BY 278 (names and ordinals), HAVING 61, DISTINCT 141; COUNT, SUM, AVG, MIN, MAX with IQ's types |
| **PR7** M1d | M1 | COALESCE 133, UPPER/LOWER 70 (after the native), CAST 69, CONVERT 63 without a style, SUBSTRING 62 (under `ansi_substring`), the TRIM family 62, LEFT/RIGHT 37, LENGTH and LEN 30, CHAR and ASCII 25, REPLACE 7; GETDATE 54 and NOW, compared with CURRENT TIMESTAMP |
| **PR8** M1e | M1 | owner-qualified tables 1,342 (after O2); `@var` 472, `:host` 520 and `?` 26 as Pure variables (after O3); the `iq16-odbc` and `iq16-tds` profiles; `IqDateTime` |
| **Then** | — | the natives (O1), announced to the ninth line; M2; M3; M4's messages; M5 |

**Caveats on the shares:**
- They are the research's, for a statement-level translator. An island is a value, so SELECT … INTO (23.0% of the
  queries, most of them PowerBuilder host variables) is refused by name inside one; the island's own share is lower
  until a statement front end exists (M4).
- The catalog track (SA catalog tables, `sp_iq*` and `sa_*` procedures, 20.3% with DUMMY) is not scheduled. In
  legend-lite it would be catalog views generated from the model, as the Postgres study proposes for `pg_catalog`
  (its §3.5). Until then, catalog names are refused by name.
- Re-measure after each milestone with the research's coverage script; its milestone lists are the contract.

---

## 8. Risks

- **SA is not the IQ engine.** It judges `engine=sa` cases and engine-neutral rules only. Every IQ-engine rule rests
  on the manuals and a provisional decision; any of them may be overturned by real IQ. Mitigation: the riskiest are
  refused (I4), each adopted one names its decision in the case, and the verification pack is ready.
- **The oracle is local.** CI never sees SA. Mitigation: the STALE, UNSNAPPED and GONE guards, and a recorded
  `--check` per landing. A machine without SA cannot add a case; that is by design.
- **Ownership and contention.** `protocol/sql` is the seventh line's and `sql/dialect` the ninth's. PR1 rewrites the
  heart of the seventh line's translator while its stress-corpus fixes are in flight
  (`claude/loving-euler-gjta59`). Mitigation: PR1 rebases after them, is a pure move judged by identical Pure, and the
  seventh line may carry the seam itself (`IN_FLIGHT`).
- **What lite cannot represent:**
  - Decimal is 38 digits on every backend; IQ's is 126. A wider value is the database's overflow error, never a
    wrong answer (as in the Postgres line's §9.3).
  - No unsigned integers, no TIME, no BINARY: refused by name.
  - An IQ database's text is ISO-8859-1; lite's is Unicode. Byte order agrees over Latin-1 letters but not beyond.
  - The store type is lost through derived tables and subqueries (§5.4).

  Each becomes a `SEMANTICS_REGISTER` row.
- **The size of the SQL.** Range checks and three-valued spellings add CASEs to every integer operation. The stress
  corpus already met a 96 KB `+` chain. Mitigation: check only what can overflow (the store type bounds the operand),
  and measure the generated SQL's size in the harness.
- **Statement-level SQL in an expression island** (INTO, variables, procedural wrappers) lowers the real-world share
  (§7). The island refuses it with a message; the statement front end is M4.
- **Copyright.** The research is full of SAP's text. Mitigation: I7; behaviour in our own words, ids and page numbers
  only; the syntax-case derivation drops any probe that matches a manual's text (§6.1); every landing's review checks
  the diff for quoted text.
- **TeaVM and native image.** New code in core must compile to WebAssembly and native image; the island's code paths
  run in the browser too.

---

## 9. Steps landed

Each slice records here what works, how, its evidence (cases, ledgers in both lanes, the drift check), what is refused
by name, what its review found, and the ledger after it, in the form of the Postgres study's §9.

### 9.1 The first landing: the seam, the oracle, the island and M0 (PR1 to PR3, built 2026-10-13 to 14, on the branch)

The plan's first three slices land together, as four commits on `claude/iq-island-1` over main 941f8382d: the study
and the oracle (5c977f4bf), the seam (27e1e4dbf), the island end to end (9fb52c67e), and the rest of M0 with the two
independent reviews' fixes (9ba06c4ad). They were built on `claude/iq-island` (e955977d1, 5cce1ec71, 670761332) and
rebased; §9.2 is this section. Every case answers what SQL Anywhere 17 answers (or what an adopted decision says), is
refused by name, or is an invalid query both reject.

**The seam (PR1): Postgres moved, unchanged.**
- `InputDialect` is the seam. `PostgresDialect` holds what was Postgres's own in `SqlToPure`: the reader, folding and
  resolving names and the hint, the default null order, a select item's default name, type kinds and literal typing,
  the binary, cast, LIKE and unification entries, the function catalog, and the ten name-resolution messages. `Tx`
  gains an opaque type detail (null for Postgres); a select item keeps its source text. The rest of M0 added two
  hooks whose defaults are Postgres's old code (`unary`, `rowCount`).
- **The judge: the generated Pure, byte for byte.** A one-off dump (out of the tree) wrote each case's Pure text and
  its protocol tree for 2,225 cases (the 2,180 `sql-parity` cases and the 45 island cases) before any edit and after:
  byte-identical. 95 more queries reach every hook and every converted message: the 2,320 lines from HEAD's classes and
  from the seam are byte-identical, and stayed so after the island, the rest of M0 and the second review's fixes. Both
  `sql-parity` ledgers are unchanged.
- Six expression shapes bisected for stack depth on a 512 KB thread: none got shallower.
- Found, not changed (a pure move): an unaliased select item of n nested casts costs 2^n in Postgres's default name
  (`PostgresDialect.defaultName` reads its operand twice per cast). It predates the seam; it is the seventh line's.

**The oracle, `tools/iq-oracle/`** (§2 is its design).
- `snap.py` (Python's standard library over SQL Anywhere's C client library, run isolated as x86_64 under Rosetta)
  creates both databases afresh in a temporary directory, reads back their creation settings, seeds both from
  `iq-parity/schema.yaml`, and runs each case under each of its profiles, the profile's options set as temporary
  options and read back. Its own server listens on a free loopback port with every secured feature off.
- `--write` snaps; `--check` reports DRIFT, UNSNAPPED, STALE, GONE, CRASH or FAILED. A lost connection rebuilds the
  server and retries once; a second loss is a CRASH, and such a case is listed in `known_crashers.txt` and skipped. A
  TIMESTAMP column is fetched a second time at six fractional digits.
- The answers file's format 2: the `sqlSha` covers the SQL, the profile, a decided case's `decision` and `expect`, and
  the schema, so editing any of them makes the answer STALE.
- A manual Bazel target, and `//tools/iq-oracle:tools_build_test` in `//gates:checks`. CI never runs SQL Anywhere.

**The island (PR2).** `#IQ{...}#`, and `#IQ:<profile>{...}#` for the five profiles (any other refused by name), on
LEGEND_LITE and on LEGEND_PLATFORM, where it replaces legend-pure's legacy graph-fetch reading of the same spelling
(nothing in the repository writes it). LEGEND_ENGINE keeps its refusal.
- **The plumbing** (§5.3): `SqlIsland` gains its language and profile; `SpecParser`'s IQ arm; the wire (a
  `classInstance` of type `IQ`) and the printers; `SqlIslands` picks the dialect and passes each column's store type.
- **The reader,** `protocol/sql/iq/`: `IqLexer`, `IqParser` (to the shared `SqlAst`), `IqDialect`, `IqProfile`,
  `IqType`, `IqTypes`. The engine is the statement's (I3): `iq` over `tb()` sources, `sa` without FROM. A `sqla17-*`
  profile is a SQL Anywhere database, so always `sa`.
- **Declared:** `docs/OWN_CORPUS_DECISIONS.md` §14 (LITE-DESIGN-iq-island: one own-corpus model whose function is an
  island) and `docs/SEMANTICS_REGISTER.md` S50 to S54.

**What the translator now reads** (`lite/basics.yaml`, `lite/m0.yaml`):
- **Names** without regard to ASCII case, quoted or not: `[x]`, `` `x` ``, and `"x"` as `quoted_identifier` says (a
  string under a TDS login). Two candidates are an ambiguity. A column shows as its reference is written, an unaliased
  constant as SQL Anywhere renders it (`1.50` as `1.5`, measured).
- **Text:** backslash escapes as `escape_character` says; nested comments. Strings compare case-sensitively in an IQ
  database, and `=` and `<>` ignore trailing blanks (one value starts with the other, the rest spaces). Under the IQ
  engine a CHAR(n) column the statement refers to is padded to n (D-S1).
- **NULL** is the lowest value. Under `ansinull` Off, `= NULL` is `IS NULL` in every search condition, and in a CASE's
  or an IF's condition under the IQ engine only (D-L1): SQL Anywhere's front end leaves it UNKNOWN there (measured).
- **Without FROM:** one row, under SQL Anywhere's front end.
- **Integers** as IQ types them: a constant SMALLINT, INTEGER or BIGINT by its value, a result by TYPING §3. Overflow
  is IQ's error -158, checked with Pure natives only where the operands' ranges allow it, never for a BIGINT (the
  database raises its own). `/` truncates toward zero, `%` keeps the dividend's sign, a zero divisor is an error
  (D-N3). An operand narrower than 64 bits is widened with `floor`.
- **BIT** is the integer 0 or 1 (the lead's decision): it compares, sorts and computes as one.
- **`||` and `+` of strings:** one join of the run, a single NULL read as '', all NULL giving NULL (D-S3); an integer
  joins as its digits.
- **Row limits:** `TOP n`, `TOP ALL`, `FIRST`, `START AT m`, and parenthesized counts of `+ - *` over integers, each
  typed, checked and folded by the planner. A literal `TOP 0`, a negative count and a START AT below 1 are -674 (D-Q1).
- **T-SQL forms:** `alias = expression` (never a comparison), `'string'` aliases with and without AS.
- **IF … ENDIF** as `CASE WHEN c THEN a WHEN NOT c THEN b END`, so UNKNOWN gives NULL, ELSE or not; searched and
  simple CASE (the simple one through `=`, so padding applies). One result type by IQ's unification, shared by CASE,
  IF, ISNULL, COALESCE and IFNULL.
- **The catalog:** ISNULL, COALESCE, IFNULL (NULL when its first argument is not, typed as the second), NULLIF (its
  second argument read as the first's type). A wrong argument count is -154.
- **IQ's errors** found in translation carry IQ's SQLCODE and SQLSTATE beside our own words: -131, -139, -142, -143,
  -144, -154, -158, -674, -830.

**The decisions** (§4, I4). **Adopted:** D-S1 and D-L1 make the IQ engine's answer differ from SQL Anywhere's here, so
their nine runs (seven D-S1, two D-L1) are judged by their `expect`, SQL Anywhere's rows as the decision changes them
(the second review checked each by hand). D-N3, D-Q1 and D-S3 hold alike in both engines here, so SQL Anywhere judges
them. **D-E2 is new** (the second review's B2; §4): an operand that can raise beside AND or OR in a WHERE is computed
or not as the plan goes, in IQ and in lite's databases alike, so lite answers such a query and a parity case holding
one is never judged; its three cases are skipped by name, and the harness fails a judged one. **Refused until the
verification pack runs:** D-N1 is reached here, and its refusal names it (arithmetic and unification over NUMERIC).
The constructs of D-N6, D-S5, D-C6, D-T2/D-T12 and D-F1 (a DOUBLE narrowed to NUMERIC, LIKE, CONVERT, DATEADD and a
date ± n, ROUND) are not read yet and are refused by name before translation; each refusal will name its decision
when its slice reaches the construct.

**Refused by name, each because lite's answer would or could differ from IQ's:**
- **Natives not built yet (O1):** ordering strings (`<`, ORDER BY, DISTINCT) needs an ASCII-only right trim (IQ drops
  the trailing blanks before comparing, which no padding reproduces for characters below the space); every string
  comparison in a `sqla17-*` database needs an ASCII-only case fold.
- **Conversions IQ makes by its own rules:** a string met with a number or a date; `+` of a string and anything else,
  an untyped NULL included (IQ's NUMERIC addition); a CHAR result of different lengths under the IQ engine (whether IQ
  pads it is not decided).
- **Types:** arithmetic on NUMERIC (D-N1), DOUBLE or dates; results IQ types NUMERIC or unsigned (BIGINT `%`, BIT `%`,
  BIT + BIT, TINYINT); a table holding a REAL or FLOAT (DuckDB compares them in single precision), an unsized CHAR, a
  BINARY or another type the island does not read.
- **Constants lite cannot hold as the profile's database does:** more than 38 digits or 38 places (IQ's NUMERIC holds
  126); more significant digits than a `sqla17-*` database keeps (30); a character outside ISO-8859-1 under an IQ
  profile, `\xHH` above 0x7F under a UTF-8 one, a NUL.
- **Other dialects' names:** a FROM function other than `tb`, and any call the IQ catalog lacks, refused before
  translation, so no Postgres reading of a name (`generate_series`) applies.
- **What IQ reads otherwise:** a select-list alias read in WHERE, in the select list or inside an ORDER BY expression
  (IQ reads the alias before a column of its name; a bare ORDER BY alias and a position are read); an unaliased
  computed item (SQL Anywhere names it from the expression's text).
- **Row limits:** `TOP n PERCENT` (IQ has no such keyword); a row limit over a FROM clause without ORDER BY (the rows
  kept are the plan's); an operation that can raise in the select list or ORDER BY of a row-limited statement (IQ
  computes them only for the rows it keeps, lite's databases may compute them for every row).
- **Size:** an expression whose checked operations would write out more than 20,000 Pure nodes (each check repeats
  its operands: about fourfold per nested level, twentyfold per quotient). It is refused in about a millisecond.
- **Not read yet (M1 onwards):** joins (a comma join too), subqueries, set operations, GROUP BY and aggregates,
  BETWEEN, IN, LIKE, EXISTS, CAST and the other special forms, the bitwise operators, DUMMY and `sa_rowgenerator`,
  parameters and variables.

**The harness** (§6). `IqParity` and `IqAnswers` run each case and profile on DuckDB (`//core:iq_parity_test`) and on
the embedded Postgres 16 (`//core:iq_parity_postgres_test`), both in `//gates:core`, against the snapped answers.
- **Its own strict judge** (the first review's M1): integers exact, numerics exact by value and by SQL Anywhere's
  scale, doubles within 1e-15 relative only where SQL Anywhere's cell is a double, text and column names exact, each
  column's type family checked against SQL Anywhere's. A Java exception is ERROR, never BOTH_ERROR. A both-error whose
  message names an IQ SQLCODE other than SQL Anywhere's is a FAIL (43 of the 77 name one; all agree).
- **Guards:** UNSNAPPED, STALE, GONE; a pinned engine its island does not run in; engine neutrality (a case SQL
  Anywhere judges translates alike under both engines' rules, or names its decision); an `engine: iq` pin needs a
  decision; evaluation order (D-E2).
- **The syntax ratchet,** `//core:iq_syntax_cases_test` (in `//gates:core`): 7,169 cases derived from the research
  (probe SQL, options and policy only), each read under its options and counted per policy and verdict in
  `syntax-pins.tsv`: accept PARSED 659, REFUSED 4,465, REJECTED 1; accept-with-option REFUSED 92, REJECTED 1; flag
  PARSED 8, REFUSED 128; reject PARSED 31, REFUSED 1,197, REJECTED 587; none crashed. The 31 rejects that parse are
  SQL Anywhere's post-parse errors the translation reaches; the 2 accepting cases rejected are a reserved word an
  option frees and a byte-order mark. Both are lists by id that may only shrink. A fall in an accepting policy's PARSED
  is a REGRESSION, a gain a FIX DETECTED until recorded. It caught one regression on the way: names of more than two
  parts were refused, where IQ takes four.

**The first independent review** (2026-10-14, of the seam and the island, by an agent that wrote none of it;
read-only; each claim run on lite's DuckDB lane and on SQL Anywhere 17, its values typed through `sa_rowgenerator` so
that SQL Anywhere cannot fold them): 6 BLOCKER, 4 MAJOR, 7 MINOR. The seam was clean. Each blocker was an accepted
query answered otherwise than SQL Anywhere answers it; each is fixed in the rest of M0 and pinned by a case in
`lite/m0.yaml` or a unit test:
- A REAL or FLOAT column met an exact constant in single precision on DuckDB (a REAL 0.1 equal to `0.1`): its table
  is refused.
- `ORDER BY +1` sorted by a constant and `ORDER BY (1)` by a position, where SQL Anywhere reads `+1` as a position and
  `(1)` as a constant: so read now, by the reader and by the harness's order check.
- Postgres's set-returning functions were read in an IQ FROM (`generate_series`): refused before translation.
- Two select-list aliases equal but for case were accepted: IQ's -830.
- A constant of more than 38 places became a double (`1e-50` as 0): refused.
- Under a `sqla17-*` profile, more than 30 significant digits were kept where the database cuts them: refused.
- MAJOR: the reused cell comparison (absolute and relative tolerances, integers through a double, types never
  compared, names without case) could hide such answers: the strict judge above. A decided case's rows could be edited
  without a STALE, and the engine-neutrality guard was not built: both done. The island was not declared, and the
  census's objection had been met by moving a test: the LITE-DESIGN family, the register rows and an own-corpus model
  now. A `sqla17-*` profile took IQ-engine rules (CHAR padding) and read `\xE9` as U+00E9: it is SQL Anywhere's
  engine now, and such an escape is refused.
- MINOR: the platform surface recorded in IN_FLIGHT; long-scale comparisons and `\x00` refused; an unsized CHAR
  refused, never a crash; TINYINT and unsigned never computed; the alias walk exhaustive; the profiles tied to the
  answers file by a test. The strict judge then found two more defects: `%` gave a Pure Number (now an Integer), and
  an all-NULL CASE crashed the lowering (now one typed NULL).

**The second independent review** (2026-10-14, of the rest of M0, by another agent; read-only): 3 BLOCKER, 3 MAJOR,
9 MINOR. Its 8,768 per-row arithmetic probes (every integer column pair, the boundary values, both lanes, both SQL
Anywhere databases) found no wrong value, type family or overflow point. Each fix is pinned by a case or a test that
fails on the reviewed translator over the same harness:
- B1: an ORDER BY expression naming a select-list alias read the FROM column (`ORDER BY it + 0` after `sm AS it`),
  where IQ reads the alias: refused (nine cases; a bare alias and a qualified column stay read).
- B2: AND and OR skip an operand on DuckDB and Postgres where SQL Anywhere raised IQ's -158 or -628 for rows another
  operand excluded, except when an equality filter ran first: plan-dependent, so D-E2 and its guard.
- B3: the derived syntax cases held documentation examples, some with the manuals' sample-database values.
  Re-derived from the research's own probe families, dropping every case whose note or rule marks an example, whose
  probe shares a run of six words with the manuals' text, or that holds a name or a string of twelve characters or
  more the manuals print: 1,601 of 8,770 dropped, every kept case unchanged and keeping its verdict. The pins were
  re-seeded with a dated comment; a grep of the whole change for the manuals' sample names finds none.
- M1: checked arithmetic grew the SQL about fourfold per nested operator; a twelve-term SMALLINT sum exhausted an 8 GB
  heap. No check is emitted for a BIGINT result, and the size bound refuses the rest. A Pure `let` would not help: the
  lowering substitutes lets.
- M2: TOP, FIRST and START AT computed the select list for the rows they drop (DuckDB raised where SQL Anywhere
  answers, and the two lanes disagreed): refused where an operation there can raise.
- M3: a constant that may be NULL (`IF id = 1 THEN 32767 ENDIF + 1`) raised -158 at translation where IQ answers NULL.
  Translation raises only when every operand is a constant IQ folds and none is NULL; otherwise each row is checked.
- MINOR, fixed: the judge compares a NUMERIC column's scale (it found literals keeping their written trailing zeros:
  `1.50E1` is now 15, at IQ's scale) and a both-error's SQLCODE; an `engine: iq` pin needs a decision; a row limit
  over a FROM clause without ORDER BY is refused.

**Recorded, not fixed:**
- The syntax ratchet counts PARSED per policy rather than pinning ids, and CI runs none of the parsed probes (the
  second review ran all 732 that parsed then: every lite answer it could compare agreed with SQL Anywhere's).
- A decided case's `expect` is not derived from SQL Anywhere's rows mechanically; the second review checked all nine
  by hand.
- IQ's run-time errors reach the user in lite's words (an overflow as a failed read of one value, a zero divisor as
  lite's division error), without IQ's SQLCODE: they need a raise native that carries a message.
- `IqDialect.plus` translates a run's leaves again at each level: time, not size, and bounded by the size bound.
- `SELECT id, ID … ORDER BY id` is a false ambiguity (the shared ORDER BY path); `rpad` cuts a stored CHAR value
  longer than its length.
- The own-corpus census classifies an `#IQ{` snippet before reading its error (its pin of 1 bounds the effect).

**Evidence** (the GATES entry of 2026-10-14 holds the runs):
- **The answers:** `tools/iq-oracle/snap.sh --write`, then `--check`: 312 cases, 329 answers (3 skipped), SQL
  Anywhere 17.0.11.7254, clean.
- **The lanes:** both give every run the same status; after the island alone they held 84 runs (PASS 59, REFUSED 13,
  BOTH_ERROR 12), after the rest of M0 and the first review's fixes 300 (PASS 195, REFUSED 31, BOTH_ERROR 74). The
  `sql-parity` ledgers are unchanged (2,180 cases: PASS 1,527, REFUSED 449, BOTH_ERROR 136, SKIP 68).
- **Units:** `IqLexerTest` 11, `IqParserTest` 15, `IqToPureTest` 25, `IqIslandRoundTripTest` (text, wire and text
  again; the profile set; the engine surface's refusal), and `IqParityTest` (the lane, and ten tests of the harness:
  the judge, the guards, the FROM rewrite).
- **The local gate:** `//gates:local` on 670761332, before the second review's fixes: 363 pass, 2 skipped. After the
  fixes: both IQ lanes, the syntax ratchet, both `sql-parity` lanes, `//core:core_tests`, `//gates:checks` and
  `//parser-equivalence:diagnostics` (166 pass, 2 skipped); `//sdlc-server:page` and `//wasm:planner` build (TeaVM).
- **Guards** moved only with a dated entry: `CodeShapeGuardrailTest` (the reader's cursors); `OwnDialectCensusTest`,
  `OwnCorpusConformanceTest` and `FixtureAdjudicationTest` (the island as a declared extension); `syntax-pins.tsv`
  (re-seeded). The other guards that objected on the way were met in the code: `SqlTextRatchetTest`,
  `ParserBoundaryArchTest`, `ErrorShapeGuardrailTest`, `IdentityGuardrailTest`.

**The ledger after this landing,** identical in both lanes (`iq-parity/ledger.tsv`):

  | | `lite/basics.yaml` (73 cases) | `lite/m0.yaml` (242 cases) | all |
  |---|---|---|---|
  | runs (case × profile) | 84 | 248 | 332 |
  | PASS: SQL Anywhere's rows, or the decision's | 62 | 143 | 205 |
  | FAIL: a wrong answer | **0** | **0** | **0** |
  | BUG: answers what SQL Anywhere rejects | **0** | **0** | **0** |
  | ERROR: errs where SQL Anywhere answers | **0** | **0** | **0** |
  | REFUSED: not supported yet, by name | 10 | 37 | 47 |
  | BOTH_ERROR: SQL Anywhere rejects it, and so does legend-lite | 12 | 65 | 77 |
  | SKIP: plan-dependent (D-E2) | 0 | 3 | 3 |

  By profile: `iq16-default` 307 runs, `iq16-tds` 17, `sqla17-default` 7, `iq16-odbc` 1.

**Next steps** (§7's slices, each with its own cases, review and record here):
1. **M1a, predicates and constants:** comparisons with IQ's conversions (a string with a number as NUMERIC, with a
   date as a TIMESTAMP); BETWEEN, IN, IS TRUE/FALSE/UNKNOWN, LIKE without `[set]` (D-S5 over padded CHAR), EXISTS;
   DUMMY and `sa_rowgenerator` (engine `sa`, a range flattened); the research's rule probes as `lite/rules.yaml`.
2. **M1b, relations:** comma, outer and cross joins, derived tables, subqueries, IN (subquery), UNION with IQ's union
   typing, NATURAL JOIN without merging; the store type through a derived table (§5.4).
3. **M1c, grouping:** GROUP BY (names and positions), HAVING, DISTINCT; COUNT, SUM, AVG, MIN and MAX in IQ's types.
4. **M1d, functions:** CAST, CONVERT without a style (D-C6), SUBSTRING, the TRIM family, LEFT and RIGHT, LENGTH, CHAR
   and ASCII, REPLACE, UPPER and LOWER after their native, GETDATE and NOW; a single-pass `+`.
5. **M1e, names and profiles:** owner-qualified tables (O2), `@var`, `:host` and `?` (O3), the `iq16-odbc` and
   `iq16-tds` profiles in full, `IqDateTime`.
6. **The natives** (O1, each announced to the ninth line): an ASCII-only case fold, an ASCII-only right trim, exact
   decimal division; and, from the reviews, a range check that takes its operand once (it would lift the size bound)
   and a raise that carries IQ's code and message.

Open for the user: whether the IQ engine pads a CHAR(n) result of CASE, IF or ISNULL; how a string meets a number in
`+`, comparisons and unification (NUMERIC, D-N1). The review carry-overs above go with the slice whose path they touch.

### 9.2 The rest of M0: T-SQL forms, row limits, IF … ENDIF, concatenation, integer arithmetic (PR3)

_Landed with the first landing: §9.1._

### 9.3 M1a: predicates and constants (PR4)

Built 2026-10-14 on `claude/iq-m1`, over the first landing (§9.1). Every case answers what SQL Anywhere 17 answers (or
what a decision says), is refused by name, or is an invalid query both reject.

**The seam, extended; Postgres unchanged.** Four hooks whose defaults are Postgres's old code: `between`, `inList` and
`isDistinct` (the shared three-valued forms), and `source`, a FROM item a dialect reads as a relation of its own
(`SqlToPure.Relation`). The generated-Pure dump (out of the tree: the 2,180 `sql-parity` cases, the 45 island cases, the
seam's 95 probes and 14 more for the new hooks) is byte-identical before and after; both `sql-parity` ledgers are
unchanged.

**What the translator now reads** (`lite/m1a.yaml`):
- **SQL Anywhere's own relations.** `DUMMY` (`SYS.DUMMY`): one row, `dummy_col` the INTEGER 0.
  `sa_rowgenerator([start [, end [, step]]])` (`dbo.` too): Pure's range, flattened into `row_num`, an INTEGER of the
  range; 0 to 100 when not written; no rows for a step that is not positive or a start beyond the end (measured). Its
  arguments are integer constants: outside an INTEGER IQ's -158, a fourth one -154, NULL and expressions refused. A
  statement over them alone runs in SQL Anywhere's front end (`engine=sa`), as one without FROM does.
- **Comparisons with IQ's conversions** (TYPING §9, measured). A string with a number compares as NUMERIC: in SQL
  Anywhere's front end, for a constant string (blanks around it ignored, nothing but blanks 0, a sign, a point, an
  exponent), refused under the IQ engine (which NUMERIC is D-N1), with a DOUBLE, for a string that is no constant or is
  spelled otherwise, and for one the profile's SCALE rounds (6 places in a `sqla17-*` database) or lite's 38 digits
  cannot hold. A string with a date or a timestamp compares as a TIMESTAMP, in both engines: a constant in ISO spelling,
  `yyyy-mm-dd [hh:nn[:ss[.ffffff]]]`, which every profile's date order reads alike (measured); with a DATE only at
  midnight. An exact number with a DOUBLE compares as a DOUBLE.
- **BETWEEN, IN, IS [NOT] DISTINCT FROM** compare as IQ's `>=`, `<=` and `=` do (its conversions, its blank padding),
  in three-valued logic. A NULL in an IN list, or a NULL operand, is UNKNOWN, never IS NULL under ansinull Off
  (measured); IS DISTINCT FROM is never UNKNOWN. **The truth tests** `IS [NOT] TRUE | FALSE | UNKNOWN` follow a
  predicate or a parenthesized condition only, binding tighter than NOT and AND; one of a value, a second one, and IS
  NULL of a condition are IQ's syntax errors.
- **LIKE without `[set]` classes**: `%` and `_` (a line feed too), case-sensitively in an IQ database, trailing blanks
  significant (measured: LIKE does not ignore them as `=` does), no default escape, `ESCAPE c` before `%`, `_`, `[` or
  itself; an integer is matched as its digits. Refused by name: a `sqla17-*` profile's case-insensitive match (the
  ASCII-only fold), under the IQ engine an operand reading a CHAR(n) column (D-S5), a pattern or an escape that is no
  constant, a `[set]` class, an escape IQ raises -629 or -630 for, a pattern of more than 126 characters (SQL Anywhere
  raises -606 for some values).
- **NUMERIC arithmetic** in SQL Anywhere's front end: `+ - *` at TYPING §4's precision and scale, a numeric literal
  its digits without trailing zeros, an integer literal the NUMERIC of its digits, an integer column (10,0) or (20,0)
  (measured); each operand a 38-digit decimal where DuckDB would otherwise compute a product in 64 bits; with an
  untyped NULL a DOUBLE NULL (measured). Refused: under the IQ engine (D-N1), a result the profile's PRECISION caps
  (30 in a `sqla17-*` database), one of more than 38 digits, `/` (the exact-division native) and `%`.
- **DOUBLE arithmetic** in both engines: `+ - * /` and the sign, IEEE as every database computes it, the other operand
  converted (an integer; a NUMERIC in SQL Anywhere's front end). Past the largest DOUBLE IQ's -158 and by zero its -628,
  each checked, where DuckDB would answer an infinity. Refused: `%` (a NUMERIC in IQ), a NUMERIC under the IQ engine,
  and, as for checked integers, an operation in the select list of a row-limited statement.

**The rule corpus, `lite/rules.yaml`.** 594 of the research's rule probes whose status is "verified on SA (IQ-like db)"
or "verified after correction" and that are FROM-less or read only `sa_rowgenerator` and DUMMY: the rule's id and its
probe, never its text, `engine: sa`, under `iq16-default`. A script out of the tree derives it, with the syntax cases'
copyright filter (§6.1) and one more: dropped 279 the research marks documentation examples, 124 sharing a run of six
words with the manuals, 7 holding a name they print, 79 a literal they print, 29 a literal a manual prints on a line
calling the same function; also 48 with options other than `iq16-default`'s own and 512 reading other sources. Snapped
twice, no drift. Today PASS 3, BOTH_ERROR 74, REFUSED 517 (most need functions, CAST, or the name SQL Anywhere gives an
unaliased expression): the ratchet later slices burn down.

**Evidence** (the runs are kept with the private research):
- `tools/iq-oracle/snap.sh --write`, then `--check`: 1,134 cases, 1,174 answers (3 skipped), clean.
- Both lanes give every run the same status (`iq-parity/ledger.tsv`):

  | | `basics` (73 cases) | `m0` (242) | `m1a` (228) | `rules` (594) | all |
  |---|---|---|---|---|---|
  | runs | 84 | 248 | 251 | 594 | 1,177 |
  | PASS | 63 | 145 | 189 | 3 | 400 |
  | FAIL, BUG, ERROR | **0** | **0** | **0** | **0** | **0** |
  | REFUSED | 9 | 35 | 33 | 517 | 594 |
  | BOTH_ERROR | 12 | 65 | 29 | 74 | 180 |
  | SKIP (D-E2) | 0 | 3 | 0 | 0 | 3 |

  Three earlier cases now pass, renamed: `basics/like_of_a_varchar`, `m0/numeric_constant_arithmetic_in_sql_anywhere`,
  `m0/double_arithmetic_of_one_row`.
- The syntax ratchet (`syntax-pins.tsv`, a dated note): accept PARSED 659 to 765, reject REJECTED 587 to 592 (BETWEEN
  without AND, an empty IN list, IN without a list, a truth test in the select list, IN in a call), reject PARSED 31
  with its list unchanged. Out of the tree, every one of the 803 parsed syntax probes was snapped on SQL Anywhere and
  run on the DuckDB lane: PASS 463, BOTH_ERROR 51, REFUSED 289, none otherwise.
- Units: `IqParserTest` and `IqToPureTest` gain 4 and 10 tests. The local gates: both IQ lanes, the syntax ratchet,
  both `sql-parity` lanes, `//core:core_tests`, `//gates:checks` and `//parser-equivalence:diagnostics` (166 pass, 2
  skipped); `//sdlc-server:page` and `//wasm:planner` build (TeaVM).

**Recorded, not fixed:**
- SQL Anywhere names an unaliased computed item by its own rendering of it (`if 1 = 1 then 'yes' else 'no' endif`,
  `2+3*4`, `NULLIF(1,1)`); the island refuses such an item, which blocks many rule probes.
- Postgres raises an underflow for a DOUBLE product below its smallest number where SQL Anywhere and DuckDB answer 0;
  no case reaches it.
- An IN list is a balanced OR of IQ's comparisons, so a list of thousands of values meets the size bound.
- `sa_rowgenerator` under TOP without ORDER BY is refused (the rows kept are the plan's), though its rows come in order.

### 9.4 M1b: relations (PR5)

Built 2026-10-14 on `claude/iq-m1`, over M1a (§9.3). Every case answers what SQL Anywhere 17 answers, is refused by
name, or is an invalid query both reject.

**The seam, extended; Postgres unchanged.** Five hooks whose defaults are Postgres's old code: `defaultName(item,
tables)` (an unaliased item's name, told which FROM item each of its columns reads), `functionName` (an unaliased FROM
function's correlation name), `quantifiedTest` (one subquery row compared, for ANY, ALL and IN), `setColumn` (a set
operation's column type) and `mergesNaturalJoin`. The translator also carries a dialect's type detail through a derived
table's and a scalar subquery's columns (none in Postgres's), asks the dialect's key rule of a distinct set operation's
columns and of its ORDER BY keys (a no-op in Postgres's), and never reads a dialect's own relation (`sa_rowgenerator`) as
a lateral item. The generated-Pure dump (out of the tree: the 2,180 `sql-parity` cases, the 45 island cases, the seam's
and M1a's probes and 24 more for the new hooks, 2,358 in all) is byte-identical before and after; both `sql-parity`
ledgers are unchanged.

**An unaliased item's name** (`IqNames`; 138 probe statements of several items each on SQL Anywhere 17, each rule
reproduced by a case). SQL
Anywhere names such an item by its own rendering of the expression, which is not its text:
- a column inside an expression is qualified by its FROM item's correlation name as declared (`id + 1` over `t a` is
  `a.id+1`); one written qualified keeps its parts as written (`A.ID+1`); at the top an item's outer parentheses and
  signs `+` go, and a column then shows as its last part is written (`(a.id)`, `+a.id` are `id`);
- `+ - * /` tight, a minus before a minus spaced (`1- -2`), `||` and comparisons spaced, `%` as `remainder(a,b)`,
  `!=` as `<>`, `!<` as `>=`; every written parenthesis kept but the outermost;
- constants by value: `1.50` is `1.5`, `0.5` is `.5`, a whole numeric `n.0` up to an INT's largest and `n` beyond
  (`2147483647.0` but `2147483648`), `1e3` is `1000.0`; strings quoted, control characters as `\xHH`; NULL `null`;
- words in lower case, a space after each unless a parenthesis or a minus follows (`if(t.id = 1) then-1`); a simple
  CASE stays simple, `END CASE` is `end`, `END IF` `endif`; `x IN (...)` is `x in( a,b ) ` with its plain constants
  first and a trailing space; NOT before LIKE, BETWEEN, IN or IS NULL is written inside it; IS DISTINCT FROM is
  `not x is not distinct from y`; a truth test's NOT goes in front;
- a call's name as written, a reserved word double-quoted (`"CHAR"(65)`), its arguments joined by `,`;
- the name cut to 255 characters; a parenthesized query shown as an item takes its one item's name.

A comment or a line break between an item's tokens changes SQL Anywhere's name in ways not modelled (`1\n+ 1` is
`1 +1`, `f(\n1)` is `f( 1)`), and an expression over a derived table's column is named by the column's definition
(`t.id as x+1`): such an item is refused by name where the statement shows its name, and named by its text where no
one sees it (a subquery's).

**What the translator now reads** (`lite/m1b.yaml`, 284 cases):
- **Joins:** comma joins, `[INNER] JOIN ... ON`, `LEFT`, `RIGHT` and `FULL [OUTER] JOIN ... ON`, `CROSS JOIN`,
  parenthesized joins (a comma inside them a cross join), each ON by IQ's comparisons (blank padding, case). Column
  references resolve across the joined items, qualified or not, IQ's -139, -142, -143, -144 and -824 as SQL Anywhere
  raises them. **NATURAL JOIN** does not merge (measured): its condition is IQ's `=` of each shared column (names
  without regard to ASCII case), ANDed with any ON written; an unqualified shared column is -144; none shared is an
  error. An unaliased table is known by its name, `tb('...t')` as `t`.
- **Derived tables:** `(query) [AS] name [(column, ...)]`, the name required (-131), each column named (an unnamed
  expression -163, a column list too long -812), the IQ types of their columns carried out of them.
- **Subqueries:** a scalar subquery (more than one row the database's error, as SQL Anywhere's -186), EXISTS and NOT
  EXISTS, `[NOT] IN (query)` and `op ANY | SOME | ALL (query)` in three-valued logic, each row compared by IQ's `=`;
  correlated references; one column where one is read (-151). Under the IQ engine, where IQ's engine restricts them
  (the research's GRAMMAR E15 and appendix B1): a scalar subquery only as a whole select-list item or a WHERE
  comparison's operand, EXISTS, IN and ANY only as WHERE predicates; refused by name inside CASE, IF, a function or
  arithmetic, in ORDER BY, in a join's ON, in a derived table's select list, and a set operation inside a subquery.
  SQL Anywhere's front end (a statement over DUMMY or `sa_rowgenerator` alone) takes them all.
- **Set operations:** UNION, EXCEPT and INTERSECT, `ALL` or `DISTINCT`, INTERSECT binding tighter, UNION and EXCEPT
  left to right; parenthesized branches; each column one type by IQ's unification (SMALLINT with BIGINT is BIGINT, an
  untyped NULL with an integer the integer); the output names the first branch's; ORDER BY a position or a
  first-branch name (an expression, a qualified name, a position beyond the list -152). Refused: EXCEPT ALL and
  INTERSECT ALL, a NUMERIC column (D-N1), a NULL with any type but an integer, every mix of kinds (IQ converts), strings
  under DISTINCT or ORDER BY (the ASCII-only right trim, O1), a parenthesized branch with its own ORDER BY (IQ's manual
  forbids it), a row limit in a branch.

**The research's syntax cases are split** on some errors IQ raises before running a query: the same form is labelled
accept in one case and reject in another, and SQL Anywhere raises the error for both (a set operation's branches of
different widths, -153; a set-operation ORDER BY name the first branch does not show, -854; a derived table's short
column list, -163; a name no derived table has, -142, -143, -144; a correlation name over 128 bytes, -250). The reader
refuses these by name, the message naming IQ's code, so that neither verdict is given on a form the cases disagree on;
a parity case of each is a BOTH_ERROR whose code the harness checks.

**The rule corpus, `lite/rules.yaml`.** PASS 3 to 29, REFUSED 517 to 491, BOTH_ERROR 74. Of the 491 still refused, 308
parse with every unaliased item's name rendered (a function the catalog lacks blocks them: the function slice's), 9
have a name not rendered (comments and line breaks, a subquery inside an item, DATEDIFF's and DATENAME's date-part
arguments), 174 do not parse (CAST, CONVERT, `DATE(...)`, `CHAR(...)` and the other keyword-named forms, binary
literals, the bitwise operators).

**Evidence** (the runs are kept with the private research):
- `tools/iq-oracle/snap.sh --write`, then `--check`: 1,418 cases, 1,458 answers (3 skipped), clean.
- Both lanes give every run the same status (`iq-parity/ledger.tsv`):

  | | `basics` (73 cases) | `m0` (242) | `m1a` (228) | `m1b` (284) | `rules` (594) | all |
  |---|---|---|---|---|---|---|
  | runs | 84 | 248 | 251 | 284 | 594 | 1,461 |
  | PASS | 65 | 146 | 190 | 197 | 29 | 627 |
  | FAIL, BUG, ERROR | **0** | **0** | **0** | **0** | **0** | **0** |
  | REFUSED | 7 | 34 | 32 | 32 | 491 | 596 |
  | BOTH_ERROR | 12 | 65 | 29 | 55 | 74 | 235 |
  | SKIP (D-E2) | 0 | 3 | 0 | 0 | 0 | 3 |

  Three earlier cases now pass, renamed: `basics/comma_join_of_two_tables`,
  `m0/an_unaliased_call_is_named_as_sql_anywhere_renders_it`, `m1a/rowgenerator_unaliased_expression_is_named`.
- The syntax ratchet (`syntax-pins.tsv`, a dated note): accept PARSED 765 to 1,490, flag PARSED 8 to 28, reject
  REJECTED 592 to 642, reject PARSED 31 to 26 (five names no FROM item has, refused before translation now); no accept
  case rejected, no reject case parsing that did not. Out of the tree, every parsed syntax probe that SQL Anywhere's
  client takes (1,537) was snapped and run on the DuckDB lane: PASS 1,279, BOTH_ERROR 49, REFUSED 209, none otherwise
  (the run found two defects, both fixed before landing: a name across a line break, an over-long correlation name).
- Units: `IqParserTest` and `IqToPureTest` gain 6 and 4 tests (joins and their syntax errors, derived tables, set
  operations, subqueries, an item read as written; names, NATURAL joins and the FROM clause's errors, the engine's
  subquery restrictions, set-operation typing); three assertions moved with dated comments.
- The local gates: both IQ lanes, the syntax ratchet, both `sql-parity` lanes, `//core:core_tests`, `//gates:checks`
  and `//parser-equivalence:diagnostics` (166 pass, 2 skipped); `//sdlc-server:page` and `//wasm:planner` build
  (TeaVM).

**Recorded, not fixed:**
- On Postgres, an untaken overflow check over an enclosing query's column inside a correlated subquery's comparison
  (`WHERE u.t_id = t.id + 100`) raised: the planner estimates the parameter-only CASE and evaluates its raise branch (the
  ninth line's lowering; its own comment records the same for an argument of unnest). DuckDB answers. The case was
  rewritten; the query is not refused.
- In the `#SQL{...}#` island too, `SELECT NULL INTERSECT SELECT NULL` finds no row (the row match compares an untyped
  NULL column with `=`) where Postgres finds one; the island refuses an untyped NULL in INTERSECT and EXCEPT. The seventh
  line's.
- SQL Anywhere reads two unaliased references to one table in a FROM list (`FROM t, t`) as one table; refused by name.
- An IN subquery inside an IF in the select list is refused under the IQ engine (the flag list names scalar subqueries
  there; the island refuses every subquery there, conservatively).

### 9.5 M1c: grouping (PR6)

Built 2026-10-14 on `claude/iq-m1`, over M1b (§9.4). Every case answers what SQL Anywhere 17 answers, is refused by
name, or is an invalid query both reject.

**The seam, extended; Postgres unchanged.** Four hooks whose defaults are Postgres's old code: `grouping` (a SELECT's
GROUP BY and HAVING as the dialect reads them), `groupsByPosition` (whether an integer in GROUP BY is a select-list
position), `isAggregate` (the dialect's aggregate names) and `aggregate` (one of the dialect's own aggregates: the
grouped relation's columns it is computed from, the column computed from them after the grouping, and its type, in
`SqlToPure.Aggregation`, built through `SqlToPure.Grouping`). The translator also asks the dialect's key rule of each
GROUP BY key (a no-op in Postgres's) and carries a computed key's type detail (none in Postgres's). The generated-Pure
dump (out of the tree: the 2,180 `sql-parity` cases, the 45 island cases, the earlier slices' probes and 16 more for
grouping, 2,374 in all) is byte-identical before and after; both `sql-parity` ledgers are unchanged.

**What the translator now reads** (`IqAggregates`, `lite/m1c.yaml`, 286 cases; every rule measured on SQL Anywhere 17):
- **GROUP BY** of columns and expressions. A select-list alias is read before a column of its name, anywhere in GROUP BY
  and HAVING (an aggregate's argument too): `SELECT j AS k ... GROUP BY k` groups by `j`. An integer there is a
  constant, never a position: `SELECT k, COUNT(*) ... GROUP BY 1` is IQ's -149; a constant beside another key divides no
  group and is dropped; a GROUP BY of constants only is refused (the IQ engine restricts it, the research's D-R1; and
  lite's databases would read the integer as a position). NULL is one group, the lowest. A column the GROUP BY does not
  read is -149 in the select list and HAVING, -854 in ORDER BY; a column of a GROUP BY expression read outside it
  (`SELECT 1 + k ... GROUP BY k + 1`, which SQL Anywhere matches, or `k * 3`, which it rejects) is refused.
- **HAVING** with or without GROUP BY, its aliases read as GROUP BY's are. **SELECT DISTINCT** over numbers, dates,
  timestamps and bits, ordered by a position, a name, or one of its items (an ORDER BY of a column it does not show is
  -854; an expression of its columns is refused).
- **COUNT** (`*`, `()`, a value, DISTINCT): SQL Anywhere's INTEGER in its front end; IQ's UNSIGNED BIGINT under the IQ
  engine (the research's D-F15). The value is the same, and the harness's type check sees an integer either way, so a
  COUNT shown, compared or ordered is exact in both engines; arithmetic on it (and a UNION or CASE over it) is refused
  under the IQ engine, where the unsigned type would change its range (`COUNT(*) * 2147483647` is -158 in SQL Anywhere,
  not in IQ).
- **SUM**: of a SMALLINT or an INTEGER an INTEGER, of a BIGINT a BIGINT, of a BIT a NUMERIC(18,0), of a DOUBLE a DOUBLE,
  of a NUMERIC(p,s) in SQL Anywhere's front end a NUMERIC(p+8,s) (refused under the IQ engine: D-N1). SQL Anywhere
  checks each running total against the type, in its plan's order: 2147483647, 1, -5 raises -158 in one order and
  answers 2147483643 in another (measured). The island raises -158 wherever some order could overflow -- the positive
  values' sum above the type's largest, or the negative values' below its smallest -- so it never answers where SQL
  Anywhere raises (D-E3, §4).
- **AVG** of a DOUBLE, checked as its SUM is. **MIN** and **MAX** keep their argument's type (integers, NUMERICs,
  DOUBLEs, dates, timestamps, bits). **MEDIAN** of a SMALLINT or an INTEGER keeps its type, the middle values' mean
  floored (-1, -4 give -3; D-F17); of a BIT, a date or NULL, IQ's -156.
- **LIST**`(x [, delimiter] ORDER BY x [ASC | DESC])` of an integer column: its values' digits in that order, NULLs
  skipped, joined by the delimiter (',' by default, none for '' or NULL), '' over no rows. Two LISTs ordered differently
  are SQL Anywhere's -904 (a constant or repeated key orders nothing); the IQ engine orders both by the first (D-A1):
  refused there.
- **The names of a grouped query's unaliased items.** SQL Anywhere names a part of such an item that is a GROUP BY item
  or an aggregate by the rendering it gives that item where it first reads it: the GROUP BY first, then HAVING, then the
  select list from the left (never ORDER BY). So `SELECT K ... GROUP BY k` shows `k`, `SELECT K + 1 ... GROUP BY k`
  `t.k+1`, `SELECT 2 * (k + 1) ... GROUP BY k + 1` `2*t.k+1` (the parentheses go), `SELECT SUM(V) ... HAVING sum(v) > 0`
  `sum(t.v)`, `SELECT SUM(k), SUM(ALL k) * 2` `SUM(t.k)` and `SUM(t.k)*2`; `COUNT(*)` is `COUNT()`, DISTINCT
  `COUNT(distinct t.v)`, LIST's ORDER BY ` order by t.id asc`. A derived table's column inside an aggregate is `d.x`
  when the derived table is a set operation or grouped, and its definition otherwise (`COUNT(null as a)`): refused
  there. Refused too: an aggregate IQ may read as an earlier one not written alike (`SUM(v + 1)` and `SUM(1 + v)` are
  one to SQL Anywhere, so are `SUM(a.v)` and `SUM(v)`), and an aggregate in parentheses (SQL Anywhere keeps them in
  `(SUM(t.k))*2` and drops them in `SUM(t.k)+1`).
- **The errors IQ raises before running** a grouped query, raised before translating: an aggregate in WHERE, ON or GROUP
  BY (-150), an aggregate over both an enclosing query's column and its own (-861), an aggregate's arguments counted
  (-154), an ORDER BY inside any aggregate but LIST (-949), a position in LIST's ORDER BY (-152), `GROUP BY ALL`, HAVING
  before GROUP BY, `COUNT(DISTINCT *)`, NULLS FIRST in LIST (-131).
- **Refused by name:** ROLLUP, CUBE, WITH ROLLUP/CUBE, GROUPING SETS, `GROUP BY ( )` and composite items (the IQ engine
  restricts them, and SQL Anywhere answers some wrongly), GROUPING(); strings as GROUP BY or DISTINCT keys and in
  COUNT(DISTINCT), MIN, MAX and LIST (IQ compares them ignoring trailing blanks, and case in a `sqla17` profile: the
  natives of O1); AVG of an exact number (a NUMERIC quotient at the profile's scale: exact division, O1); STDDEV and
  VARIANCE (SQL Anywhere's formula loses precision); MEDIAN of a BIGINT, a DOUBLE or a NUMERIC, and MEDIAN(DISTINCT);
  LIST without ORDER BY or ordered by anything but its value, of strings or of non-integers, LIST(DISTINCT), a delimiter
  that is no constant; SUM of a date, a string or NULL; under the IQ engine an aggregate's DISTINCT over anything but a
  column and a SELECT DISTINCT with an aggregate's DISTINCT (D-R1); HAVING without GROUP BY that reads no aggregate
  (lite's planner tests such a condition before aggregating). An owner-qualified table name is refused when read (the
  study's O2), `sa_rowgenerator` without parentheses, and `dbo` or `SYS` as a table.

**The research's syntax cases are split** on three more forms, now visible because COUNT(*) is read: NOT NOT (-131), an
ON naming an item before the comma (-824) and two correlation names alike but for case (-139), each labelled accept in
one case and reject in another while SQL Anywhere raises the error for both. The reader refuses each by name with IQ's
code, as §9.4 does for the others.

**The rule corpus, `lite/rules.yaml`.** PASS 29 to 35, REFUSED 491 to 485, BOTH_ERROR 74: the six probes COUNT(*) alone
blocked (over `sa_rowgenerator`, DUMMY, a cross join, HAVING). None of the 485 still refused is blocked by grouping: 305
need a function the catalog lacks (the function slice's), 131 a special form, the rest literals, variables and operators
of later slices.

**Evidence** (the runs are kept with the private research):
- `tools/iq-oracle/snap.sh --write`, then `--check`: 1,703 cases, 1,743 answers (4 skipped), clean. The schema gains the
  tables `g` (keys with duplicates and NULL groups, each type's aggregate edge) and `o` (running totals past the largest
  value); every earlier answer is unchanged but for its `sqlSha`.
- Both lanes give every run the same status (`iq-parity/ledger.tsv`):

  | | `basics` | `m0` | `m1a` | `m1b` | `m1c` (286 cases) | `rules` | all |
  |---|---|---|---|---|---|---|---|
  | runs | 84 | 248 | 251 | 284 | 286 | 594 | 1,747 |
  | PASS | 65 | 146 | 190 | 197 | 189 | 35 | 822 |
  | FAIL, BUG, ERROR | **0** | **0** | **0** | **0** | **0** | **0** | **0** |
  | REFUSED | 7 | 34 | 32 | 32 | 48 | 485 | 638 |
  | BOTH_ERROR | 12 | 65 | 29 | 55 | 48 | 74 | 283 |
  | SKIP | 0 | 3 | 0 | 0 | 1 (D-E3) | 0 | 4 |

- The syntax ratchet (`syntax-pins.tsv`, a dated note): accept PARSED 1,490 to 1,901, flag PARSED 28 to 48, reject
  REJECTED 642 to 723, reject PARSED 26 to 24 (an owner-qualified table and `sa_rowgenerator` without parentheses,
  refused now); the owner-qualified refusal moves 11 accept cases from PARSED to REFUSED (the island refused each when
  translating already); no accept case rejected, no reject case parsing that did not. Out of the tree, every parsed
  syntax probe SQL Anywhere's client takes (1,964) was snapped and run on the DuckDB lane: PASS 1,552, BOTH_ERROR 46,
  REFUSED 366, none otherwise (the run found three names and one -904 rule, each fixed before landing: a merged derived
  table's column inside an aggregate, `distinct(` without a space, constant LIST keys).
- Units: `IqParserTest` and `IqToPureTest` gain 1 and 3 tests (GROUP BY, HAVING and the aggregates' forms and spans;
  aliases and constants in GROUP BY, the aggregates' types and checks, a grouped query's names); three assertions moved
  with dated comments. The local gates: both IQ lanes, the syntax ratchet, both `sql-parity` lanes, `//core:core_tests`,
  `//gates:checks` and `//parser-equivalence:diagnostics`; `//sdlc-server:page` and `//wasm:planner` build (TeaVM).

**Recorded, not fixed:**
- lite's planner applies a filter that reads no column of a grouped relation before the aggregation: `HAVING 1 = 0` over
  an aggregate without GROUP BY answers one row where every database answers none (refused in the island; the
  `#SQL{...}#` island answers the one row too: its lowering is `WHERE FALSE`).
- A constant GROUP BY key reaches the database as `GROUP BY <integer>`, which DuckDB and Postgres read as a position
  (the island drops or refuses such keys).
- Postgres has no `sum` of an untyped NULL, and lite's Postgres lowering takes no percentile of DISTINCT values (both
  refused in the island).

### 9.6 M1d: functions (PR7)

Built 2026-10-14 on `claude/iq-m1-fn`, over M1a (§9.3), beside M1b. Every case answers what SQL Anywhere 17 answers (or
what a decision says), is refused by name, or is an invalid query both reject. Each function is translated exactly with
Pure's existing natives -- Java reads the arguments' types and the constants' spellings, never computes a value -- or is
refused by name, naming the native or the decision it waits for.

**Where it lives.** `protocol/sql/iq/IqFunctions.java` (new): the catalog beyond the NULL family, CAST and CONVERT.
`IqDialect` routes to it (`function`, `cast`, the argument counts of its parse) and gains three small things: `Cast` in
its walk of the reader's forms, LIKE over a CAST to CHAR(n) refused under the IQ engine as LIKE over a CHAR(n) column is
(D-S5), and `sa_rowgenerator`'s `row_num` marked as computed in 64 bits (it is a BIGINT in lite's databases). `IqParser`
reads `CAST(x AS type)`, `CONVERT(type, x)` (a style refused by name), `CURRENT DATE`, `CURRENT TIMESTAMP`,
`CURRENT_TIMESTAMP` (with parentheses IQ's -131), `f(*)` (GETDATE, NOW, TODAY, PI), the calls a reserved word heads
(`CHAR`, `LEFT`, `RIGHT`, `DATE`), and the date part of DATEPART, DATENAME, DATEADD and DATEDIFF as a keyword of its own
(a string or an unknown part, SQL Server's `yyyy` among them, IQ's -131). The data types: CHAR, VARCHAR (a CHAR or BYTE
unit), LONG VARCHAR (TEXT), SMALLINT, INTEGER, BIGINT, NUMERIC (DECIMAL, DEC, MONEY, SMALLMONEY), DOUBLE (FLOAT(25..53)),
DATE, TIMESTAMP (DATETIME, SMALLDATETIME), BIT; a size outside its type's range is IQ's -131; REAL, TINYINT, the
unsigned types, TIME, the binary and national types and a domain are refused by name. No `SqlToPure` change: the
Postgres island is untouched.

**What the translator now reads** (`lite/m1d.yaml`; every rule measured on SQL Anywhere 17, and run differentially over
some 1,600 more probes on both lanes):
- **Length.** `LENGTH`, `CHAR_LENGTH`, `LEN`: characters, trailing blanks counted (LEN is LENGTH: D-S6); a CHAR(n)
  column under the IQ engine counts n (D-S1); an integer's digits and sign.
- **Substrings.** `SUBSTRING` and `SUBSTR` under ANSI_SUBSTRING On (every profile's): a start below 1 counts virtual
  positions (`('mnopqrst', -3, 5)` is 'm'), a missing length is the largest INTEGER (so a start of -2147483641 keeps
  'mnopq'), a negative length IQ's -1090 for a string that is not NULL. `LEFT` and `RIGHT`: a negative count is '' (Pure's
  left leaves that many off the other end).
- **Trimming.** `TRIM`, `LTRIM`, `RTRIM` drop what an ISO-8859-1 database trims -- the space and the tab through the
  carriage return, no other (measured over the 255; DuckDB's trim drops Unicode spaces and no tab, Postgres's the space
  alone, so neither is used: two anchored `regexpReplace`s, the line feed apart since Postgres's lowering reads a bracket
  newline-sensitively); `TRIM(s, set)` of a constant set, case-sensitively.
- **Searching.** `REPLACE` (every occurrence, left to right; '' searched is no change), `CHARINDEX`, `LOCATE` (its start:
  from there on; negative, the last occurrence in the string less its last -start - 1 characters, through
  `reverseString`; '' is found at 1 whatever the start) and `PATINDEX` of a constant pattern of one literal between %s
  (0 for a NULL argument), each case-sensitively, as an IQ database matches; a search of 255 characters or more is NULL
  (measured: 254 is read).
- **Building.** `REPEAT`, `REPLICATE`, `SPACE` (a count of 0 or less is ''), `STUFF` (SQL Anywhere's front end: NULL for
  a start out of 1 .. length + 1, a NULL replacement deletes), `ASCII` and `CHAR` (an ISO-8859-1 database's codes, NULL
  outside 0 .. 255), `STRING` (a NULL is '', all NULL is NULL, D-S3's rule).
- **Numbers.** `ABS` (SQL Anywhere's unsigned SMALLINT and INTEGER and BIGINT's NUMERIC(20,0); under the IQ engine the
  argument's type, its smallest value IQ's -158: D-F5, adopted -- it can only raise, never answer otherwise), `SIGN`,
  `CEILING`/`CEIL`/`FLOOR` in SQL Anywhere's front end (the argument as a DOUBLE first -- FLOOR of NUMERIC
  0.99999999999999999999 is 1 -- then whole), `ROUND` (half away from zero; a NUMERIC keeps its type and a column's result
  past its precision is IQ's -158, while a folded constant gets a digit more, measured) and `TRUNCNUM` of integers and
  NUMERICs, `MOD` and `REMAINDER` as `%`, `SQRT`, `EXP`, `LN`, `LOG` (natural) and `LOG10` (NULL outside the domain in SQL
  Anywhere's front end, an error under the IQ engine: D-F9, adopted, an error only; EXP's overflow NULL and its underflow
  0, where Postgres raises), `PI`.
- **Dates.** `GETDATE`, `NOW`, `CURRENT TIMESTAMP` are Pure's `now()`, `TODAY` and `CURRENT DATE` its `today()`: one value
  per statement (SQL Anywhere reads GETDATE's clock a few microseconds before CURRENT TIMESTAMP's, so the cases compare
  them with `<=`, and the snapped answers never depend on the day). `YEAR` .. `SECOND`, `QUARTER`, `DOW`, `DATE`;
  `DATEPART` and `DATENAME` of every part but TZOFFSET (the week Sunday-based with week 1 holding 1 January, the weekday
  from Sunday at the profiles' first day of the week, milliseconds and microseconds of the second; the ISO parts in SQL
  Anywhere's front end only). `DATEADD` (a TIMESTAMP, a month's end clamped) and `DATEDIFF` (boundaries crossed: YEAR ..
  DAY, WEEK in Sundays, MINUTE, MILLISECOND, MICROSECOND; HOUR and SECOND the minute and millisecond boundaries over 60
  and 1000, truncated; DAYOFYEAR the days of the year's difference) in SQL Anywhere's front end only. Of a date, a
  timestamp or a constant string in ISO spelling.
- **CAST and CONVERT without a style** to SMALLINT, INTEGER, BIGINT (truncating toward zero; a string by IQ's reading: a
  string that begins or ends with a space loses its white space at both ends, then an optional sign and digits, none
  0), NUMERIC(p, s) (rounding half away from zero; an integer's NUMERIC(10,0) or (20,0), a NUMERIC's own, a string's the
  profile's PRECISION and SCALE when no precision is written), DOUBLE, CHAR(n), VARCHAR(n), LONG VARCHAR (a longer string
  IQ's -638 in SQL Anywhere's front end unless the rest is spaces; a CHAR(n) padded under the IQ engine, D-S1; an
  integer its digits, too long -157; a NUMERIC IQ's text, `-.50`; a DATE yyyy-mm-dd), DATE, TIMESTAMP, and an integer to
  BIT. CONVERT is CAST but where a date becomes text.

**Refused by name** (the native or decision each waits for):
- natives: `UPPER`, `LOWER`, `UCASE`, `LCASE` and every case-insensitive match of a `sqla17-*` profile (REPLACE, CHARINDEX,
  LOCATE, PATINDEX) -- the ASCII-only case fold (O1); a count or position that lite's SQL computes in 64 bits (a BIGINT,
  `row_num`, any arithmetic) -- Postgres's substr, left, right, repeat and chr take a 32-bit integer and the lowering does
  not cast one (DuckDB's chr too); `ROUND` and `TRUNCNUM` of a DOUBLE and a DOUBLE to NUMERIC -- SQL Anywhere rounds the
  decimal digits (ROUND(2.675e0, 2) is 2.68); `POWER` -- SQL Anywhere answers +Infinity or NULL where lite's databases
  raise; `TRIM` and `ASCII` in a UTF-8 database (Unicode white space; a character above 127 is NULL); a DOUBLE, a
  timestamp or a date written as text by IQ's formats (STRING, LENGTH, CAST), and CONVERT's styles (M2).
- decisions, under the IQ engine: CEILING and FLOOR (D-F2), ROUND (D-F1), STUFF (D-F3), PATINDEX of %s alone (D-F6),
  DATEADD (D-T2, D-T12), DATEDIFF (D-T11), the ISO date parts (D-T10), a CAST to a shorter string (D-C2), CONVERT of a
  date to text (D-C6), a DOUBLE to NUMERIC (D-N6), a NUMERIC or a date to a too-short text (D-C1), a nonzero integer to
  BIT; LIKE over a CAST to CHAR(n) (D-S5).
- SQL Anywhere's own quirks: DATEADD(MILLISECOND) (it cuts the timestamp to milliseconds: D-T3), DATEDIFF of a weekday
  or a CAL part (0); a constant IQ cannot convert (CAST('abc' AS INT), a date of no day, 2 to BIT), other dialects'
  CHARINDEX start, ROUND third argument and LOG base, a constant negative SUBSTRING length -- each IQ's error wherever
  computed, refused before translation.

**Evidence** (the runs are kept with the private research):
- `lite/m1d.yaml`, 344 cases: `snap.sh --write`, then `--check`, clean. Both lanes give every run the same status: PASS
  209, BOTH_ERROR 62, REFUSED 73; FAIL, BUG, ERROR 0. The earlier files' statuses are unchanged (1,177 runs).
- The rule corpus as committed is unchanged (its unaliased items wait for M1b's default names); with every select item
  aliased, 157 more of its 594 probes answer as SQL Anywhere does (32 did before).
- The syntax ratchet: accept PARSED 765 -> 1,315, flag PARSED 8 -> 10, reject REJECTED 592 -> 697; reject PARSED and
  accept-rejected unchanged.
- Units: `IqFunctionsTest` (15), `IqParserTest` one assertion moved (CAST reads now).

**Recorded, not fixed (for other lines):**
- Postgres's substr, left, right, repeat and lpad/rpad, and DuckDB's chr, take a 32-bit integer and the lowering passes a
  BIGINT through: every computed count is refused here.
- A date constant before the year 100 is written without its leading zeros (`DATE '1-01-01'`), which Postgres reads as
  2001: refused here; M1a's comparisons with such a string reach it too.
- `IF c THEN 'x' ELSE NULL ENDIF` (both other branches NULL) trips the compiler's multiplicity-stamp invariant (a Java
  exception), in M0's IF, not this slice's.
- lite's clock is its database session's (UTC in the tests), IQ's the server's local time: the island compares clocks
  only with each other.

### 9.7 M1e: names, variables and profiles (PR8)

Built 2026-10-14 on `claude/iq-m1e`, over the merged M1 (§9.9, §9.10). Every case answers what SQL Anywhere 17 answers,
is refused by name, or is an invalid query both reject. The O2 and O3 designs in §4 are this slice's drafts, for the
lead.

**Where it lives.** Two new files in `protocol/sql/iq/`: `IqBindings` (an owner-qualified table bound to the model's
tables; a host variable read as the enclosing Pure query's variable) and `IqDateTime` (IQ's date and timestamp strings,
read by DATE_ORDER and NEAREST_CENTURY; a date or a timestamp written as text by the profile's formats). The seam gains
two hooks, each Postgres's answer the refusal it was (its generated Pure byte-identical): `InputDialect.table` (a FROM
table named without `tb()`) and `InputDialect.parameter` (a parameter: Postgres's `$1`, IQ's `@name` and `:name`, now a
`SqlAst.Param` with a name), with two callbacks on `SqlToPure.Sources` beside `columns`, `tables` (the model's tables)
and `variable` (a variable's type), which `compiler/spec/SqlIslands` answers from the model (each Database's own tables,
computed once per model) and the Typer's scope; Postgres's dialect never asks. A lambda parameter of the translation (`r`, `y`, `a`, ...) that would hide a variable the
island reads is refused by name there. `IqParser` reads `db..t`, `:name` and `@name`; `IqDialect` and `IqFunctions` call
`IqDateTime` where a constant string is read as a date and where a date becomes text.

**What the translator now reads** (`lite/m1e.yaml`; each rule measured on SQL Anywhere 17 over the parity tables):
- **Owner-qualified tables** (O2). `DBA.t`, `dba.T`, `"DBA"."t"`, `[dba].[t]`, `` `DBA`.`u` ``, `DBA . u`,
  `mydb.DBA.t`, `srv.mydb.DBA.t`, `nosuchdb.DBA.u`, `mydb..t` read the model's `t` (the database and server qualifiers
  are ignored, measured), in joins, subqueries, derived tables, set operations and grouping, and so does `DUMMY` through
  its qualifiers (`mydb.SYS.DUMMY`, `XXX..DUMMY`). IQ's errors: an owner of no user, -140 (`nobody.t`,
  `srv.mydb.NOBODY.DUMMY`); a table its owner does not own, -141 (`DBA.nosuch`, `DBA.DUMMY`); a name of five parts, `..`
  after a second part or before an owner, an empty delimited part, -131.
- **Host variables** (O3). `@lo`, `:lo` of an Integer, `@name` of a String, `@day` of a StrictDate, `@stamp` of a
  DateTime, `@rate` of a Float, `@amt` of a Decimal(38,2): in WHERE, BETWEEN, IN and NOT IN lists, arithmetic, CASE,
  IF, COALESCE, HAVING, a grouped select list, ON, subqueries, derived tables, CAST, DATEPART, UPPER, LENGTH, ORDER BY,
  over an owner-qualified table, under every profile. `SELECT @a1 FROM (SELECT 1 AS @a1) d` reads the column. IQ's
  errors: `@lo` of no variable, -143; `:lo` of none, -188; `: lo`, `::lo`, `:1`, `:a:b`, -131.
- **Profiles: dates and timestamps as text.** CAST to VARCHAR, LONG VARCHAR and VARCHAR of no length, `||`, STRING,
  LENGTH, LEFT, SUBSTRING and LIKE of a DATE or a TIMESTAMP write it in the profile's DATE_FORMAT (`YYYY-MM-DD`) and
  TIMESTAMP_FORMAT: three places of the second, cut (`23:59:59.999999` is `23:59:59.999`), at the defaults and on a TDS
  login, six through IQ's ODBC driver -- composed of Pure's own natives (each part's digits padded with zeros), the
  database computing each value. A target shorter than the text is refused (D-C1), CONVERT without a style too (SQL
  Anywhere's style 0, D-C6).
- **Profiles: ansinull Off.** `= NULL`, `<> NULL` and `!= NULL` are IS [NOT] NULL in a join's ON (inner, left, right),
  a HAVING (under NOT too), a comma join's WHERE, and the WHERE of an IN subquery, an EXISTS and a derived table, in
  SQL Anywhere's front end as under the IQ engine (measured); a NULL that is no constant (`CAST(NULL AS INT)`, `NULL +
  1`, `IN (NULL)`, `NULLIF(1, 1)`) stays unknown; a CASE's condition in the front end stays unknown (the IQ engine's,
  D-L1, as before).
- **IqDateTime: a constant date or timestamp string**, wherever M1a's comparisons and M1d's conversions read one
  (`dt = '...'`, IN, BETWEEN, CAST, DATEPART), by the profile's DATE_ORDER (YMD; MDY on a TDS login): three numbers
  (`03/04/05` is 2003-04-05 in YMD and 2005-03-04 in MDY; `3/4/2026` is IQ's error in YMD), a four-digit year first in
  every order (`2026/3/4`, `2026-03/04`, `2026.3.4`), `yyyymmdd` and `yymmdd`, a month's English name or three-letter
  abbreviation (`Mar 4 2026`, `March 4, 2026`, `mar 4 26`, `4 Mar 2026`, `04-MAR-2026`, `2026 Mar 04`); a year of one or
  two digits by NEAREST_CENTURY 50 (`49` is 2049, `50` 1950); then after spaces or `T` a time of an hour alone, hours and
  minutes or seconds, a fraction after `.`, `:` (decimal places: `12:34:56:7` is .7) or `,`, past six places cut, AM or
  PM (12 AM midnight, 13 PM 13), `24:00:00` the next day's midnight. Measured over 1,218 strings, orders and types: the
  reader's 508 readings agree with SQL Anywhere's, none differs; what it does not read is refused by name.
- **Fixed on the way:** nothing answered otherwise; the reader's `owner.table.*` (IQ's) was a syntax error and is
  refused by name now.

**Refused by name:** a column reference qualified by an owner (`DBA.t.id`, `mydb.SYS.DUMMY.dummy_col`) and
`owner.table.*`; a name two of the model's Databases hold; an unqualified name `DBA` does not own; IQ's own users'
tables (`SYS.SYSTAB`, `dbo.sysobjects`; its catalog, not scheduled); a table twice without a correlation name (SQL
Anywhere reads the two as one, as before); `?` and `:?` (no name to bind); `:a.b`; a variable of another type (Boolean,
a Decimal of no known precision), of many values, named like the translation's lambda parameters, in TOP or START AT, or
read in an unaliased item; LIKE with a variable pattern; a time alone, a month and a year alone, ISO weeks, ordinal
dates, weekday names, other month abbreviations (IQ matches any prefix: `Ma` is March), a zone offset, a tab or spaces
inside a time; a date before the year 100 (review 3's B3).

**Evidence** (the runs are kept with the private research):
- `lite/m1e.yaml`, 212 cases, 329 runs over the five profiles: `snap.sh --write`, then `--check`, clean (2,499 cases,
  2,810 answers; every earlier answer identical). Both lanes give every run the same status: PASS 275, BOTH_ERROR 37,
  REFUSED 17; FAIL, BUG, ERROR 0. Eleven earlier runs answer now (eight `*_is_refused` cases renamed, with a dated
  note); none answers otherwise. All files: 2,814 runs, PASS 1,794, REFUSED 649, BOTH_ERROR 367, SKIP 4.
- The syntax ratchet: accept PARSED 2,689 -> 2,705 (thirteen owner-qualified and `db..t` names, three date constants);
  flag, reject and both lists unchanged. The sixteen, snapped and run: PASS 8, BOTH_ERROR 2, REFUSED 6, none otherwise.
- The generated Pure of the Postgres island's 2,394 cases (the merge's 2,385 and nine probes of the new hooks:
  `$1`, a table name without `tb()`, a three-part column) is byte-identical (sha256 36aa7f14...); both `sql-parity`
  lanes unchanged.
- Units: `IqBindingsTest` (3), `IqDateTimeTest` (6), `IqParserTest` (two more), `IqParityTest.aCaseBindsItsParamsAsLets...`.

**The harness.** A case may bind variables: `params: "lo = 3; name = 'x'; day = %2026-03-04"`. The Java harness lets each
before the island (`|let lo = 3; ... #IQ{...}#;`); `tools/iq-oracle/snap.py` writes each into the SQL SQL Anywhere runs as
its type's constant (`CAST(3 AS BIGINT)`, `'x'`, `CAST('2026-03-04' AS DATE)`), noted in the answers' meta; the params
are part of the answer's `sqlSha`.

**Recorded, not fixed:** lite's catalog is the model: an owner a real IQ database has but the model does not is -140 in
lite, -141 in IQ (both errors); a variable's name is matched as Pure matches names, with case (IQ's, without: `@LO` of
`$lo` is -143 in lite).

### 9.8 The natives

Built 2026-10-14 on `claude/iq-m1-fn`, over M1d (§9.6), beside M1b and M1c. The three natives the study's O1 asked of
the database owner (§4, §5.5) each turned out to be composable exactly of Pure natives that exist, measured on SQL
Anywhere 17 and run on both lanes: no file under `sql/dialect/` changed, and none under `protocol/sql/` outside `iq/`.

**What the translator now reads** (`lite/natives.yaml`; the runs are kept with the private research):
- **IQ's ASCII-only case fold.** `UPPER`, `LOWER`, `UCASE` and `LCASE` fold A-Z only, in an ISO-8859-1 and a UTF-8
  database alike (measured: é, ß, a Greek or a Cyrillic letter stays). Composed of 26 of Pure's `replace`, which both
  databases compute character for character (DuckDB's `upper` and `lower` fold every letter, the embedded Postgres's,
  in the C locale, the ASCII ones only: neither is used). A constant without a letter to fold is left as it is.
- **A `sqla17-*` profile's case-insensitive database.** Its comparisons (`=`, `<>`, `<`, `<=`, `>`, `>=`, BETWEEN, IN, IS
  DISTINCT FROM, a simple CASE, NULLIF) compare the two strings with their ASCII letters folded to lower case -- the
  order is the folded strings' (measured: `'_' < 'a'`, `'[' < 'a'`, `'Z' > '['`; `'é' <> 'É'`), trailing blanks
  significant; LIKE spells each ASCII letter of its pattern as a class of its two cases; REPLACE is a global
  `regexpReplace` with the same classes (a constant search and replacement, the replacement's backslashes doubled);
  LOCATE, CHARINDEX and PATINDEX fold both strings, which moves no position; a TRIM set holds both cases of its
  letters.
- **Blank padding where IQ orders strings.** In an IQ profile's database `<`, `<=`, `>`, `>=` and BETWEEN compare two
  strings without their trailing spaces -- U+0020 only, a tab or a no-break space is a character -- because IQ drops
  the blanks rather than padding the shorter string (measured: `'a' < 'a\t'`, `'a  ' < 'a\x01'`): one anchored
  `regexpReplace` on each side (DuckDB's `rtrim` drops every Unicode space, Postgres's the space alone). `=` and IN keep
  M1a's form. Both engines: IQ's collation is the database's, not the engine's (the research's SEMANTICS §3.3).
- **TRIM in a UTF-8 database.** Measured over U+0085, U+00A0, U+1680, U+180E, U+2000-U+200B, U+2028, U+2029, U+202F,
  U+205F, U+3000 and U+FEFF, SQL Anywhere's TRIM in its UTF-8 database drops exactly what it drops in an ISO-8859-1 one
  (the space and the tab .. the carriage return): M1d's refusal was too careful, and its regular expressions serve both.
- **NUMERIC `/` and `%` in SQL Anywhere's front end.** The rule, measured over 3,400 quotients of every shape in both
  databases, each one of them: TYPING §4's type (p1 + p2 + 1, s1 + p2 - s2 + 1, then the profile's cap), and as its
  value the exact quotient cut to ceil((p + 1) / 2) base-100 digits from its first nonzero one, then rounded half away
  from zero at s -- which is the quotient rounded half away from zero, but truncated where p is odd, s even and the
  quotient has all its p - s whole digits (two NUMERIC(1,0): 5/3 is 1.66 and 2/3 is .67; 8.5 / 8 is 1.063). That is the
  value SQL Anywhere shows; as an operand, a CAST, a comparison or a key it reads the cut value unrounded
  (`(10.0/3)*3` is 10.00 and `10.0/3 = 3.33` is false), so a quotient is read as a select item of a SELECT without
  DISTINCT only. Composed exactly: with n = |x| * 10^s and d = |y|, the rounded quotient is the whole quotient of 2n + d
  by 2d and the truncated one that of n by d, each from double estimates biased below by 2^-44 and cut to a BIGINT a
  chunk of digits at a time, which exact decimal arithmetic corrects (one estimate for a quotient of 13 digits, three
  for 38) before a last comparison of the rest; `%` is Pure's `rem` of the two at one scale (both databases' mod is
  exact for two decimals of one scale; DuckDB's of two scales is a double's). A quotient of 34 digits over 200,000 rows
  takes about a second on either lane, every value exact. AVG of a NUMERIC is the same quotient of its SUM by its
  COUNT at AVG's NUMERIC(p+19, s+11) (129 AVGs measured): `IqFunctions.quotient`, for M1c's AVG.
- **Fixed on the way:** M1d's CAST of a constant to NUMERIC(p, s) with a scale to pad overflowed on DuckDB past 18
  digits (DuckDB adds two narrow decimals in 18: `756929791864553.5 + 0.00000`); the padding adds a 38-digit zero first.

**Refused by name:**
- under the IQ engine, NUMERIC `/` and `%` (D-N1, as before);
- a quotient anywhere but a shown select item (an operand, a CAST, a comparison, a CASE, ORDER BY's expression,
  DISTINCT's select list); one whose operand SQL Anywhere types by rules not read (a constant integer expression, which
  it folds and types by its value: `(2+3)/1.5` divides a NUMERIC(1,0); a function or a CASE); one whose capped type may
  not hold it (SQL Anywhere shows '?'); a step of more than lite's 38 digits, the scale counted (DuckDB's limit); a
  divisor that may be 0 in the select list of a row-limited statement; `%` of an untyped NULL (a NUMERIC NULL, not a
  DOUBLE); a capped `%`, and one SQL Anywhere computes in more digits than its PRECISION (it loses the last ones there:
  measured);
- ORDER BY, DISTINCT and GROUP BY over strings, and MIN and MAX of strings (M1c's): the collation key is composed
  (`IqDialect.collated`), but the translator's keys take no dialect's yet, and among rows of one key SQL Anywhere shows
  the first it meets (measured: DISTINCT over 'b ', 'b' and 'b  ' shows 'b ', MAX of them 'b '), the plan's choice;
- REPLACE in a `sqla17-*` profile with a search or a replacement that is no constant.

**Evidence:**
- `lite/natives.yaml`, 148 cases, 219 runs: `snap.sh --write`, then `--check`, clean (1,626 cases, 1,737 answers). Both
  lanes give every run the same status: PASS 193, REFUSED 20, BOTH_ERROR 6; FAIL, BUG, ERROR 0. Fourteen earlier runs
  answer now (eleven `*_is_refused` cases renamed, with a dated note); the rest unchanged. All runs: PASS 816,
  REFUSED 673, BOTH_ERROR 248, SKIP 3.
- Differentially, out of the tree: 952 quotients and remainders against each database (0 FAIL on either lane) and 900
  string comparisons, folds, searches and trims against each (0 FAIL).
- The rule corpus as committed is unchanged (its unaliased items wait for M1b's names); with every item aliased, six
  more probes answer (LCASE, LOWER, UCASE, UPPER, MOD and REMAINDER of NUMERICs).
- The syntax ratchet is unchanged (the reader did not change). The `sql-parity` lanes are unchanged, and the
  generated Pure of the Postgres island's 2,334 cases is byte-identical (sha1 7592b778...).

**For the ninth line (optional):** a native for the exact whole quotient of two decimals would replace the estimates
(a quotient of 38 digits writes some 9,000 characters of SQL): DuckDB `CAST(CAST(a * 10^S AS HUGEINT) // CAST(b * 10^S
AS HUGEINT) AS DECIMAL(38,0))`, Postgres `div(a, b)`; the private research keeps the design and the announcement.

### 9.9 The merge: IQ's collation as a key, AVG of a NUMERIC

Built 2026-10-14 on `claude/iq-m1`: M1b and M1c (§9.4, §9.5) merged with M1d and the natives (§9.6, §9.8), and what the
two left between them finished. The translator's seam changed in one place, Postgres's translation in none.

**The merge.** Both branches gave `IqDialect` a field `shown`: M1b's (the select items whose names a statement shows)
keeps the name, the natives' (the expressions a statement shows as they are, where a NUMERIC quotient is rounded) is
`shownValues`. M1d's CAST is a child expression beside M1b's subqueries; M1c's aggregates and M1d's special forms sit
side by side in the reader. Where M1d's forms meet M1b's names, SQL Anywhere's rendering is read (measured): a date
part as written, in lower case (`DATEPART(mm,...)`, `DATENAME(month,...)`), `CURRENT DATE` and `CURRENT TIMESTAMP` (or
`CURRENT_TIMESTAMP`) in lower case, `f(*)` as `f()`, a call a reserved word heads in double quotes (`"CHAR"(65)`,
`"left"('abc',1)`); a CAST stays unnamed (refused). The natives' quotient met M1b's derived tables: SQL Anywhere merges a
derived SELECT into its query, so a column defined by a constant integer expression enters a quotient as the folded
constant (`1.5/x` over `(SELECT 2+3 AS x) d` is a NUMERIC(4,3), `.300`, where the column's own type would give a
NUMERIC(13,12)); a set operation's column is typed as the set operation (NUMERIC(10,0), measured). A quotient over a
derived column defined without a column, an aggregate or a subquery is refused (`IqDialect.folded`).

**IQ's collation as a key.** The seam's `InputDialect.key` returned nothing or refused; it now returns the dialect's
key -- what ORDER BY sorts by, or GROUP BY groups by, in the value's place -- and Postgres's answer is still none (its
Pure is byte-identical). The key is the natives' `IqDialect.collated`: in an IQ profile's database the string without
its trailing spaces, in a `sqla17` profile's with its ASCII letters folded. Measured on SQL Anywhere 17 first:
- ORDER BY sorts by the key; the order among rows whose keys tie is the plan's (small sorts break ties one way, a
  5,000-row TOP another): the harness compares a string sort key by the collation.
- DISTINCT, GROUP BY, UNION, INTERSECT, EXCEPT, MIN, MAX and LIST show one value of each group of equal keys, and it is
  the first row SQL Anywhere meets: `DISTINCT x` over `'a', 'a '` shows `'a'`, over `'a ', 'a'` shows `'a '`; MIN, MAX,
  GROUP BY, UNION and INTERSECT alike (INTERSECT the left's); over `sa_rowgenerator` the first row's. That is the plan's
  choice, so the island shows such a value only where no two different values can share a key (`IqKeys`, from the
  expression's form): RTRIM and TRIM end in no space; LOWER and LCASE hold no upper-case letter, UPPER and UCASE no
  lower-case one; a constant is itself; CASE and IF are their branches; a derived table's column is its definition (in
  every branch of a set operation). There the values are their own keys, and the translator's DISTINCT, grouping and
  set operations are IQ's; MIN, MAX and LIST also need the order to be the key's (UPPER in a `sqla17` profile is refused
  there: `'_'` sorts after `'A'` but before `'a'`).
- COUNT(DISTINCT x) counts the distinct keys; a GROUP BY key the query does not show (not read outside its aggregates
  in the select list, HAVING or ORDER BY, but an ORDER BY of the key itself) groups by the key: both whatever the
  strings.
- LIST skips `''` as it skips NULL; a blank is kept (measured: `' ,  ,a,b'` in either database).
- Refused by name: DISTINCT, a set operation, a GROUP BY key shown, MIN, MAX and LIST over any other string (a column's
  above all: `SELECT DISTINCT s FROM g`), with the reason.

**AVG of a NUMERIC** in SQL Anywhere's front end is the natives' quotient of its SUM by its COUNT at NUMERIC(p+19,
s+11), shown only (an operand reads it unrounded); refused where the profile's PRECISION caps the type (a `sqla17`
profile's 30 digits from a NUMERIC(12,s) on), under the IQ engine (D-N1), and of an integer (a NUMERIC(49,21)).

**Kept apart by the merge's syntax ratchet:** MEDIAN of a CAST to BIT or a date is IQ's -156 before translating, and a
GROUP BY of an integer constant beside a column it does not group is refused naming IQ's -149 where the syntax cases call
it accepted (IQ groups by the constant, other dialects by the position).

**Evidence:**
- `lite/collation.yaml`, 130 cases, 213 runs (iq16-default, sqla17-default, iq16-tds): PASS 155, REFUSED 58. Four
  earlier cases answer now and are renamed (`join_on_strings_in_a_sql_anywhere_database`,
  `union_all_ordered_by_a_string`, `count_distinct_of_strings`, `ordering_strings_by_their_collation_key`); none
  answers otherwise.
- All files, both lanes, identical: 2,523 runs, PASS 1,492, REFUSED 676, BOTH_ERROR 351, SKIP 4; FAIL, BUG, ERROR 0.
  `snap.sh --write` then `--check`: clean (2,325 cases, 2,519 answers). The rules corpus: PASS 35 (M1c) -> 128,
  REFUSED 485 -> 392, BOTH_ERROR 74 -> 74.
- The syntax ratchet: accept PARSED 2,689, flag PARSED 50, reject REJECTED 838, reject PARSED 24, each at least both
  parents' (the pins' dated note). Every parsed probe SQL Anywhere's client takes (2,718) answers as SQL Anywhere does on
  the DuckDB lane but the nine that show the clock itself.
- The generated Pure of the Postgres island's 2,385 cases (parity 2,180, island 45, probes 160, eleven new over string
  keys) is byte-identical at M1c, at the natives and at the merge (sha256 c4407896...); both `sql-parity` lanes
  unchanged.

**Refused, and recorded for the compiler line:** a set operation whose first branch shows a NULL constant (`NULL`, or
M1d's `CAST(NULL AS ...)`, which M1b's set operations now take) has its column stamped as always empty by the compiler
(the multiplicity stamp of a concatenation is its first input's), so a query that reads the set operation's column --
`x = 'a'` over `(SELECT CAST(NULL AS VARCHAR(3)) AS x UNION ALL SELECT 'a') d` found no row, `x = 1` over `(SELECT NULL
AS x UNION ALL SELECT 1) d` raised, a string's collation key sorted at random. Such a set operation read by another
query, or ordered by a string, is refused by name (`IqDialect.nullFirstBranches`); a NULL in a later branch, and a chain
of set operations ordered by an integer, are read.

### 9.10 The third review's findings, fixed

Built 2026-10-14 on `claude/iq-m1`, over §9.9, by the lead's rulings on the third review of M1a-M1d (four blockers,
five majors, the minors). Each fix is pinned by a case in `lite/m1review.yaml` (or a unit test where the harness cannot
judge one) that failed, or answered where SQL Anywhere raises, before it.

- **B1 (D-E3's check is read lazily).** The SUM's (and the DOUBLE AVG's) overflow check is a finish over the grouped
  rows that the lowering inlines where the value is read, so lite raised -158 only for the groups whose value it read;
  SQL Anywhere raises in the aggregation, for every group (measured: `IF k = 4 THEN 0 ELSE SUM(v) ENDIF` raises). A
  checked SUM or AVG read for some groups only is refused by name (`IqDialect.lazyAggregates`, D-E3 above); twelve
  cases and a unit test (the HAVING-under-AND forms, which D-E2's guard keeps from being judged).
- **B2.** An ESCAPE that is `%`, `_` or `[` is refused: SQL Anywhere reads the wildcard, not the escape (`'%%' ESCAPE
  '%'` matches every string). `]` is read as before.
- **B3.** A date read from a string with a year below 100 is refused wherever it is read (`IqDialect.isoDate`, one
  helper for M1a's comparisons and M1d's constants): lite's lowering writes the year without its zeros, which Postgres
  reads as this century's (`dt < '0012-12-12'` answered three rows on the Postgres lane). For the ninth line: write
  four-digit years; the refusal lifts then.
- **B4.** DATEADD's result outside the years 1 to 9999 is IQ's -158 (a check of the result's year, composed of Pure
  natives), as SQL Anywhere raises. SQL Anywhere wraps some counts instead of raising (measured: DAY 1,500,000,000 is
  2598-05-12, WEEK 200,000,000 is 2408-03-25, QUARTER 2,000,000,000 is 6036-09-01, YEAR -2,147,483,648 leaves the date
  as it is): its defect; a count whose range passes the last count that raises is refused by name (an INTEGER column's
  for DAY, WEEK, QUARTER and YEAR; a SMALLINT's is read). Also recorded as SQL Anywhere's: a timestamp of a year past
  about 6000, and every sub-day DATEADD there, comes back with its hours and minutes cleared (`9999-12-31 23:30` plus an
  hour is `9999-12-31 00:00`); no case reaches those years.
- **M1 (names).** SQL Anywhere drops the parentheses inside an item that is a chain of `+` and `-` over non-negative
  number constants (and products of them, whose factors keep theirs) where it optimizes the statement -- a join, a
  derived table, `sa_rowgenerator`, a set operation (its branches, even without FROM), grouping, DISTINCT, a subquery --
  and keeps them where it does not (one SELECT over nothing, DUMMY or one table, with a WHERE, an ORDER BY or a row
  limit): `1 + (2)` is `1+2` over `sa_rowgenerator`, `1+(2)` over `t`; `1 - (2 - 3)` is `1-2-3`. Measured over 18
  statement shapes and 49 item forms; any other query's item (a subquery's, a derived table's) is not named
  (`IqNames.Chains`).
- **M2.** SQL Anywhere's MEDIAN computes the midpoint in INTEGER and wraps where two values differ by more than
  2^31 - 1 (MEDIAN of 2147483647 and -2147483648 is -2147483648): its defect, and the IQ engine's own is not measured,
  so a MEDIAN of an INTEGER whose range allows it is refused under both engines; a SMALLINT, and an integer of a known
  small range (`row_num`), is read. Six M1c runs that answered are refused now.
- **M3 (the clock).** D-T13 above. Three M1d cases that put GETDATE() beside another reading were split.
- **M4.** D-E2's harness guard reads each query's HAVING, and counts SUM and AVG as operands that can raise; M1c's
  `having_two_aggregates` reads MAX beside COUNT now.
- **M5.** `IF c THEN a ELSE NULL ENDIF`, a CASE's `ELSE NULL`, and a CASE's last `WHEN ... THEN NULL` whose condition
  cannot raise (IS NULL, a truth test and their logic) are read without that branch -- the same value -- so the
  compiler's multiplicity stamp of a CASE whose last branches are all NULL (it fired a Java exception) is not met;
  the item keeps its written name. A last NULL branch after a comparison (which may convert, and so raise) is refused.
  The stamp's root cause is the compiler line's (`StampCensus`'s CASE arm), recorded.
- **m1.** NUMERIC `+` and `-` of more than 18 digits widen both operands (DuckDB aligned the two scales in the narrower
  width: `99999999999999999.5 + 1.00` overflowed).
- **m2.** `lite/rules.yaml` was re-checked with a stricter literal filter (a literal the manuals print quoted anywhere,
  or one an example rule of the same section holds): 95 probes removed, the review's five among them.
- **m4.** A DOUBLE product or quotient that underflows to 0 is 0 in SQL Anywhere and DuckDB and an error in Postgres:
  where both operands read a column it is computed so that no step underflows (the smaller factor scaled by 2^1074
  first; the lowering writes a product of products as one left-to-right chain, measured), equal on both lanes; one of
  constants only, which Postgres folds whole, is refused unless a literal operand rules the underflow out; one with a
  constant operand is computed as before (Postgres would fold the constant's scaled copies too: an underflow there,
  which needs a value below about 1e-162, is recorded, not guarded). `sa_rowgenerator`'s translation-time -158 over a range no row reaches answers since the merge
  (M1d's `row_num` typing): pinned.

**Evidence:** both lanes identical; FAIL, BUG, ERROR 0. The counts are kept with the private
research.

### 9.11 The fourth review's findings, fixed

Built 2026-10-14 on `claude/iq-m1`, over §9.10, by the lead's rulings on the fourth review (of the merge and of §9.10:
three blockers, two majors, three minors). Each fix is pinned by a case in `lite/review4.yaml` (or a unit test) that
failed, raised a Java error, or answered where SQL Anywhere raises, before it.

- **B1 (a `*` hides which column an item is).** `IqKeys` read a derived table's or a set operation's column by its item
  index; after a `*` that is some later column, so the second branch's raw strings were taken for the first's
  `RTRIM(x)` (DISTINCT, GROUP BY, MAX, UNION and EXCEPT answered wrong). A branch with a `*` at or before the item is no
  proof of "its own key": refused. `IqDialect.folded` takes every name of a column list from that item on.
- **B2 (a shown quotient read again).** SQL Anywhere rounds a NUMERIC quotient or AVG only where it shows it; its
  alias or position in GROUP BY, HAVING or ORDER BY, and the same AVG written again, read it unrounded (`GROUP BY q`
  over `v / w AS q` makes three groups where the shown .9990s make two). A quotient or an AVG is shown only where
  nothing else in the statement reads it, else refused by name. The harness's tie rule (rows tied on their shown sort
  keys are in the plan's order) no longer applies to a statement any of whose items is or reads a quotient, an AVG or
  a MEDIAN: there the rows are compared in their order.
- **B3 (a checked SUM through a derived table).** D-E3's check runs where the query around a derived table reads its
  column, for some rows only (IF, CASE, AND in WHERE, TOP); SQL Anywhere raises in the aggregation. A checked SUM or AVG
  in any query but the statement's own SELECT (a derived table, a subquery, a set operation's branch) is refused.
- **M1 (constant-chain names).** Measured over the parity tables t, g, u, o and DUMMY (115 statement shapes, every
  profile alike): the parentheses are kept under no WHERE, or one comparison of a column written first with a constant
  (a number not negative, a string, NULL), a column BETWEEN two such constants, or a column IS NULL, whatever the ORDER
  BY or row limit; IN, OR, AND, NOT, LIKE, IS NOT NULL, NOT BETWEEN, a constant written first, a negative number, an
  expression on either side and a test of constants drop them. Any other WHERE's chain is not named (refused).
- **M2 (SQL Anywhere's far-year timestamps).** Measured: from 7911-01-01 on its timestamps lose their hours and minutes
  (§2.5), wherever they come from, and the defect is not confined to 9999-12-31. A timestamp constant from 7911 on with
  a time is refused, and so is a DATEADD whose result may reach 7911 with a time of day -- a part below a day, or a
  timestamp not known to be midnight -- by the latest year a date may reach, carried in its type from constants
  through CAST, DATE(), DATEADD and CASE. A stored column's date (whose year the translation cannot know) is read: the
  schema holds none of those years.
- **m1.** INTERSECT keeps the left's rows, but its column was typed as not NULL where the right's is not, so a collation
  key or a function over it failed in the compiler; the island types it as either side's (EXCEPT the left's).
- **m2.** A NULL of a type (`CAST(NULL AS VARCHAR(3))`, CONVERT) as an IF's ELSE or a CASE's last value is read as
  §9.10's M5 reads an untyped one, kept as the CASE's ELSE so that its type stays the CASE's; two of different types
  are refused.
- **m3.** The manuals' example strings in `natives.yaml`, `m1d.yaml` (the substring, LTRIM, LOCATE and STUFF cases now
  read `'mnopqrst'`, `'xyyxXwxy'`, `'yxyxxYzyq'` and positions of their own) and the `rules.yaml` note are replaced.
  The literal filter (the derivations' n-gram, demo-name and printed-literal tests, and review 3's stricter one) ran
  over every file under `iq-parity/` and the IQ test sources: two case ids renamed, 186 of the 7,169 syntax cases
  dropped (the ratchet's pins moved with a dated note), and 0 hits after.

**Evidence:** both lanes identical, 2,592 runs: PASS 1,529, REFUSED 718, BOTH_ERROR 341, SKIP 4; FAIL, BUG, ERROR 0.
The measurements and the counts are kept with the private research.

### 9.12 The merge: M1e with the fourth review's fixes

Built 2026-10-14 on `claude/iq-m1`: M1e (§9.7) merged with §9.11. Postgres's translation is unchanged (its generated
Pure byte-identical), and no run of either branch changed its status but M1e's own improvements.

- **Dates.** M1e replaced M1a's ISO-only reader (`IqDialect.isoDate`) by `IqDateTime.read`, while §9.11's M2 had added
  its constant guard to the old reader, so M1e's other spellings let a timed timestamp after 7910 through:
  `CAST('8000/06/01 13:00' AS TIMESTAMP)`, `'Jun 1 8000 13:00'` and a comparison with it answered 13:00 where SQL
  Anywhere drops the time (four FAILs on M1e's tree). The guard now lives in `IqDateTime.read`, beside review 3's B3: a
  timestamp constant after 7910 with hours or minutes, in any spelling the reader takes, is refused by name; its
  seconds, a midnight and the years up to 7910 are read (`IqDateTimeTest`). Every date constant the reader reads
  carries its year for M2's DATEADD guard (through CAST, DATE(), a date argument, and in a comparison with a date). A
  host variable's date has no year known to the translation, so a DATEADD over it that may reach 7911 with a time of
  day is refused, as one over a computed date is. Seven cases in `lite/review4.yaml` pin it.
- **Cases.** M1e's `lite/m1e.yaml` and §9.11's `lite/review4.yaml` run together; the answers file was re-snapped (every
  answer of both parents identical, but for the renamed ids). M1e's files pass §9.11's literal filter but for one
  date-format spelling in a test's comment, which is no text of the manuals'.
- **The syntax ratchet.** §9.11's case file (its 186 dropped cases stay dropped; none of M1e's 16 newly parsed cases is
  among them): accept PARSED 2,658 -> 2,674.

**Evidence:** both lanes identical, 2,928 runs: PASS 1,817, REFUSED 729, BOTH_ERROR 378, SKIP 4; FAIL, BUG, ERROR 0.
The counts are kept with the private research.

### 9.13 The showcase

`docs/IQ_SQL_SHOWCASE.md` follows one non-trivial query through the island: bracketed names, T-SQL's aliases, `TOP ...
START AT`, `IF ... ENDIF`, string `+`, IQ's comparison, a join to a grouped derived table with `LIST`, `DATENAME`, an
owner-qualified table and a host variable. Its three cases are `lite/showcase.yaml` (PASS on both lanes), and the SQL
the query plans for Snowflake (rendered only) and for DuckDB is pinned in `iq-parity/showcase/` by
`IqParityTest.theShowcasePlansItsPinnedSql` (`-Diq.showcase.update=<that directory>` rewrites it).

**Evidence:** both lanes identical, 2,931 runs: PASS 1,820, REFUSED 729, BOTH_ERROR 378, SKIP 4; FAIL, BUG, ERROR 0.

### 9.14 The fifth review's findings, fixed

Built 2026-10-14 on `claude/iq-m1`, over §9.12 and §9.13. The fifth review read M1e and the merge (three blockers, five
majors, six minors). Its blockers were wrong answers through host variables that the oracle could not see, because it
wrote each value into the SQL as a constant. Each fix is pinned by a case in `lite/review5.yaml` (75 runs) or a unit
test, and each case failed, answered where SQL Anywhere raises, or raised a Java error before its fix.

- **B1 (a BIGINT variable near the bounds).** Lite's lowering writes an integer literal beyond 2^61 as a wider integer
  (a HUGEINT on DuckDB, a NUMERIC(38) on Postgres), so `@big + 1`, `id * @lo` (2^62) and `-@lo` (the smallest BIGINT)
  answered past BIGINT, where IQ raises -158. 13 runs were BUG on both lanes.
  - An Integer variable is now read as IQ's BIGINT: a value BIGINT cannot hold (a Pure Integer may be larger) raises.
    The test is written as differences (`v - max > 0`, `v + max < -1`), because the planner folds a comparison of two
    constants beyond `Long.MAX_VALUE` wrongly (recorded for the other lines).
  - Its type carries `loose` (the database may compute it wider than 64 bits), and any BIGINT result over it is
    checked by the island, not left to the database's own overflow. That covers `+ - * / %`, the sign, ABS, NULLIF,
    CASE, the set operations and CAST.
  - A BIGINT constant near 2^63 (`CAST('9223372036854775807' AS BIGINT) + 1`) had the same defect and is checked too.
- **B2 (ansinull Off).** Measured on SQL Anywhere 17 with real variables under the TDS profiles (about 50
  forms, kept with the private research):
  - `x = @v`, `x <> @v` and `x != @v` are two-valued in a search condition (WHERE, HAVING, ON, a subquery's WHERE)
    when x is a column (corrected by §9.15: also a constant, arithmetic and a CAST over columns, and a derived table's
    column defined by one; a function's, an aggregate's or a scalar subquery's value stays three-valued): a NULL x
    makes them FALSE, not UNKNOWN, so `NOT (x = @v)` keeps the rows where x is NULL. For such an x this holds with the
    variable on either side, parenthesized or not, and for strings too.
  - These stay three-valued: `<`, `<=`, `>`, `>=`, IN, NOT IN, BETWEEN, LIKE, a variable inside an expression
    (`x = @v + 0`), and every comparison outside a search condition (the select list's IF and CASE, ORDER BY).
  - Inside an IF's or a CASE's condition within a search condition, SQL Anywhere's answer follows its own rewriting of
    the condition. `IF x = @v THEN 1 ELSE 0 ENDIF = 0` is two-valued, but inside a COALESCE it is three-valued.
  - Lite now reads such a comparison two-valued where the search condition reaches it through AND, OR and NOT. Inside
    an IF, a CASE or a function there, it refuses the comparison by name when it may be UNKNOWN. (This fix read every
    operand two-valued, a column's or not: §9.15 narrows it to the measured operands.)
  - Before the fix, 12 runs were FAIL on both lanes (the review's r5f had passed only because the oracle wrote a
    constant).
- **B3 (a date variable before the year 100).** A StrictDate or DateTime variable reaches the SQL as a literal. Lite's
  Postgres rendering (the ninth line's `sql/dialect/`, recorded there) writes it with the year's own digits
  (`DATE '12-12-12'`), and Postgres reads that as 2012. No Pure guard over the value can catch it, because the year is
  lost in the literal itself. Date and timestamp variables are refused by name until the renderer writes four-digit
  years. That is a loss of coverage: 11 runs of M1e's and review 4's date-variable cases that passed (all after the
  year 100) are now `..._is_refused`. Before the fix, four runs were FAIL on Postgres.
- **M1 (a `[0..1]` variable).** A comparison with a variable that may hold no value read it through `toOne`. Postgres
  folds `toOne`'s raise while estimating the comparison, so the read failed with a value present and without one
  (recorded for the other lines). Such a variable is refused by name. The harness spells a NULL (`null:Integer`, an
  empty Pure value; a real NULL variable for the oracle), and four cases pin the refusal. Under ansinull Off SQL
  Anywhere also answers `sm = @x` (x NULL) two-valued.
- **M2 (an alias spelled like a variable).** SQL Anywhere reads a select-list alias `"@lo"` before the variable `@lo`,
  both in WHERE and among the select items. Lite read the variable, and with no variable it raised -143, which SQL
  Anywhere does not. Such a reference is refused by name (`IqDialect.noAliasRead`). Three runs were FAIL and one ERROR.
- **M3 (a model table DUMMY).** SQL Anywhere reads the user's own table DUMMY before SYS.DUMMY, for an unqualified
  `DUMMY` and for `db..DUMMY`. The engine (SQL Anywhere's front end for DUMMY, IQ's for a table) is chosen before the
  model is read, so when the model holds a DBA table named DUMMY such a name is refused by name, through a new seam
  hook, `InputDialect.tableSource`, which defaults to `source`. `SYS.DUMMY` stays IQ's and `DBA.DUMMY` reads the
  model's table. This is pinned by `IqBindingsTest.aModelTableNamedDummyIsNotIqsDummy`: before the fix both names read
  SYS.DUMMY.
- **M4 (a DATE cast to text).** CAST and CONVERT of a DATE to a string now go through `IqDateTime.text`, as `||`,
  STRING and a TIMESTAMP's CAST already did, so a year below 1000 keeps its zeros (`0500-01-01`). Three runs were FAIL.
- **M5 (the oracle).** `tools/iq-oracle/snap.py` now makes each param a real SQL Anywhere variable on the case's
  connection:
  - `CREATE VARIABLE` of BIGINT, DOUBLE, NUMERIC(38,s), LONG VARCHAR, DATE or TIMESTAMP, then `SET` to the value (a
    NULL left unset), and `DROP` after.
  - It runs the case's SQL as written, but for `:name`, which is written `@name`: in dynamic SQL SQL Anywhere reads
    `:name` only as a placeholder a client binds (-188 with none).
  - Under a TDS profile, where `tsql_variables` On makes `@name` such a placeholder too, the case runs with
    `tsql_variables` Off. (§9.15: that also makes a column or an alias of `@name`'s name read before the variable,
    which a placeholder never is; the tool now rejects a case whose SQL names one.)
  - A value its type cannot hold is SQL Anywhere's error from the SET.

  Re-snapped, every answer of a case with params is the same as before but for these:
  - four cases' string column types (`long varchar` for `varchar` or `char`);
  - `host_unaliased_is_refused`'s column name;
  - `host_variable_as_a_top_count_is_refused`, which SQL Anywhere now answers (BOTH_ERROR -> REFUSED);
  - `host_variable_name_in_another_case_is_iq_error_143`, now `..._reads_it` (SQL Anywhere matches the name without
    case: PASS).

  The Java harness reads the same spelling (`IqParity.Param`: its SQL Anywhere type and value). Both readers reject a
  string param with a backslash, which they would read otherwise, and names that differ only in case.
- **m1.** A host variable's name is matched as IQ matches names: the one variable in scope of that name but for ASCII
  case. When the enclosing query has two such variables, the name is refused. A new seam method,
  `SqlToPure.Sources.variables`, lists the names in scope.
- **m4.** The GROUP BY matcher (`IqAggregates.shape`) compares host variables by their prefix and name, so
  `GROUP BY id + @lo` reads `id + @lo` in the select list.
- **m6.** The harness types a negative decimal param (`-0.50D`) as a plain Decimal, as Pure does, so the
  engine-neutrality check and the run both refuse it.

**Recorded, not fixed:**
- m2: a String variable holding a character outside ISO-8859-1 under an `iq16-*` profile, which IQ converts to 0x1A
  when it is bound and lite keeps. The value is not known at translation, and S53 refuses such constants.
- m3: a model View named through an owner is IQ's -141 (the catalog lists tables only).
- m5: the lambda-parameter check refuses any variable named like any lambda parameter of the translation, even where no
  lambda of that name encloses the reference. It is sound but broad.
- TOP @n stays refused, though SQL Anywhere answers it.

**Evidence:** both lanes identical, 3,006 runs: PASS 1,856, REFUSED 757, BOTH_ERROR 389, SKIP 4; FAIL, BUG, ERROR 0.
The answers: 2,641 cases, 3,002 answers. The measurements, the before/after of each case and the
counts are kept with the private research.

### 9.15 The sixth review's findings, fixed

Built 2026-10-14 on `claude/iq-island-2`, over the second landing's squashed commit. The sixth review, the final audit
before the landing, found one blocker, two majors and three minors. Each fix is pinned by a case in `lite/review6.yaml`
(64 cases, 89 runs) or a unit test; each case that is now refused answered otherwise than SQL Anywhere before the fix.

- **B1 (ansinull Off: two-valued for every operand).** §9.14's B2 fix read `x = @v` two-valued under ansinull Off for
  any nullable `x`. Re-measured on SQL Anywhere 17 with real variables under iq16-tds and sqla17-tds (about 70 forms
  beside the review's, kept with the private research), the rule depends on the operand:
  - Two-valued: a column (of a table, either side of an inner or outer join, an enclosing query's, a GROUP BY key or a
    select-list alias of one in HAVING); a constant; `+ - * / %`, `||`, string `+` and a sign over such operands (a
    column with a constant or with another column, nested); a CAST or CONVERT of one (to an integer, a NUMERIC or a
    string); a derived table's column, through `*` too, whose definition is one (a plain column, `sm + 0`, `sm + id`,
    a DISTINCT's, a grouped query's key, every branch of a UNION ALL, a NULL constant). The variable may be on either
    side. A view's column follows its definition the same way (measured; the island reads no views).
  - Three-valued: a function's value (NULLIF, COALESCE, ISNULL, ABS, UPPER, TRIM, LENGTH), an aggregate's in HAVING
    (MAX, MIN, or a select-list alias of one), a scalar subquery's (an aggregate or a plain column), and a derived
    table's column defined by a function, which SQL Anywhere merges into the query (over a table and over
    sa_rowgenerator alike, and through a LEFT JOIN, in WHERE and in ON).
  - Two-valued too, but not read here: a CASE's and an IF's value, and a derived column SQL Anywhere does not merge
    (a grouped query's aggregate, a UNION ALL branch's NULLIF, a CASE over sa_rowgenerator). Whether it merges is its
    plan's choice.

  Lite now reads `x = @v` two-valued only where `x` is one of the first list (`IqDialect.twoValuedOperand`, resolving
  a column through the derived tables and the enclosing queries' FROM items it may come from; an unqualified name an
  inner table may hold and an outer derived table defines otherwise is refused). Any other operand that may be NULL is
  refused by name under a NOT. Reached through AND and OR alone, FALSE and UNKNOWN filter alike, so there it answers
  three-valued, as before §9.14. The review's 13 wrong answers (on both lanes: NULLIF, COALESCE, ISNULL, UPPER, TRIM,
  LENGTH, ABS, MAX and MIN in HAVING, two scalar subqueries, a derived NULLIF column and one through a LEFT JOIN), and
  four the re-measurement found (the variable on the left of a function, a derived NULLIF over sa_rowgenerator and in
  an ON, a derived ABS), were FAIL (one ERROR) and are REFUSED. Coverage lost: 8 runs that answered right are refused
  (CASE, IF, an aggregate's HAVING alias, a grouped derived aggregate, a UNION ALL's NULLIF branch).
- **B1, found while re-measuring: HAVING's alias.** A HAVING that reads a select-list alias (`SELECT sm AS z ... GROUP
  BY sm HAVING NOT (z = @lo)`) is rewritten with the alias's expression in place (`IqAggregates.grouping`, M1c), which
  rebuilt the comparison after it was placed, so it was read three-valued: three runs were FAIL. The rewrite is now
  made once per SELECT and placed as rewritten (`IqDialect.grouping` returns it). And every comparison of a statement
  with a host variable is placed before translating (three-valued outside a search condition): one the translation
  meets unplaced is refused by name, never read three-valued by default.
- **M1 (a column named like a variable under a TDS profile).** Under iq16-tds and sqla17-tds (`tsql_variables` On, as
  a TDS login sets it) a client binds `@name` as a placeholder, and SQL Anywhere reads the bound value even where a
  column `@name` is in scope (measured through dbcapi's bound parameters); with `tsql_variables` Off, a connection's
  variable reads the column first (GRAMMAR L27, S56). Which one an island's `@name` is under a TDS profile is not
  decided, so such an `@name` is refused by name there (`IqProfile.tsqlVariables`); `:name` never reads a column, and
  an alias of `@name`'s name is refused under every profile (§9.14, M2). No committed case moved: the column-first
  cases run under iq16-default. Pinned by `IqBindingsTest.underATdsProfileAColumnNamedLikeAVariableIsRefused`.
- **M2 (references to private files).** Fourteen committed lines of this landing (this document, `IqFunctions`,
  `IqDateTimeTest`) and the header of `lite/rules.yaml` named local files of the line's working notes or of the
  private research. They now say what the evidence is and that it is kept with the private research.
- **m1 (the oracle's `:name`).** `snap.py` writes `:name` as `@name`, which a column or an alias spelled `@name`
  would shadow, where a bound `:name` never is. The tool now rejects a case with params whose SQL names a column or
  alias spelled like one of them (any quoted name `@name`) where it writes `:name`, or where the case runs under a TDS
  profile (`tsql_variables` On there: M1); such a case is rewritten or skipped by its own `skip:` key.
- **m2.** §9.14's B2 bullet, its M5 bullet, the O3 decision (§4) and the register's S56 now state the rules as
  measured: two-valued for the operands above only, and a column before a variable only with `tsql_variables` Off.
- **m3 (not changed).** `NOT (-sm = @lo)` and `NOT (sm + 1 = @lo)` raise in lite where the overflowing rows make SQL
  Anywhere raise too (BOTH_ERROR). `-sm` is now two-valued by measurement (`-t_id` over `u`, which cannot overflow,
  answers), and `NOT (t_id + 1 = @lo)` over `u` answers on both lanes.

**Evidence:** both lanes identical, 3,095 runs: PASS 1,913, REFUSED 789, BOTH_ERROR 389, SKIP 4; FAIL, BUG, ERROR 0.
No earlier run moved. The answers: 2,705 cases, 3,091 answers (89 new; every earlier answer identical). The syntax
verdicts are unchanged. The measurements and the before/after of each case are kept with the private research.

### 9.16 The third landing, first part: the M1 gaps

Built 2026-10-14 on `claude/iq-island-3`, over the second landing (§9.15). It reads the SELECT features the island
refused though they need no decision, each measured on SQL Anywhere 17 first; catalog tables, server-state procedures
and procedural wrappers stay refused by name. Every case answers what SQL Anywhere 17 answers, is refused by name, or
is an error both raise: `lite/m1gaps.yaml` (356 cases, 360 runs) pins each item and its near misses.

**Where it lives.** One new file, `protocol/sql/iq/IqAliases` (the select-list aliases, read when parsing). The seam
gains two hooks, each Postgres's answer what it was (its generated Pure byte-identical): `InputDialect.columnNotHere`
(a name the dialect refuses before it is looked up in an enclosing query) and `InputDialect.namesUnread` (a SELECT
whose output names no one reads); and two `Problem`s (`DISTINCT_ORDER_KEY`, `SET_WIDTHS`) whose Postgres messages are
the texts they replace. `SqlToPure.Grouping.groupKey` says whether an expression is a GROUP BY key.

1. **A select-list alias read where IQ reads it** (`IqAliases`): in WHERE, ON, the select list (a later item's alias
   too), GROUP BY, HAVING and inside an ORDER BY expression, an alias is its expression, written there, before a
   column of its name (measured: the alias wins at its own level; a name inside its own definition is the column; a
   definition through itself is IQ's -831; an aggregate's alias in WHERE -150; an ON reading an item outside its join
   -824; a qualified name is never an alias). Inside a subquery IQ reads the subquery's own columns first, then the
   enclosing query's alias, then the enclosing query's columns: that read is refused by name
   (`IqDialect.columnNotHere`). **A latent wrong answer fixed:** the second landing answered such a read with the
   enclosing column (found by this landing's probes; no committed case read one). Under ansinull Off an alias whose
   expression reads a host variable is refused in WHERE and ON: where SQL Anywhere reads such a comparison
   two-valued (§9.15) is measured for comparisons written in place only.
2. **The name of an unaliased item** (`IqNames`): an alias the item reads is written as written (double quotes only
   for a name that is not regular; `id AS id` read in `id+1` is `id+1`, not `t.id+1`: **a latent wrong name of the
   second landing, fixed**). An item written over several lines or with comments is named as SQL Anywhere's printer
   marks its line breaks: a leaf's line is its last token's (`\n`, `\r\n` and a lone `\r` each a break; a comment with
   a line break counts); a break between two operands writes a space and the indentation before the operator, inside
   a CASE or an IF value two spaces more for each level; a call's argument, CASE's THEN, WHEN, ELSE and END each by
   their own measured rule. Measured over about 2,900 generated items (random and realistic): every name lite gives
   matches. Refused by name: a break SQL Anywhere marks otherwise (inside a call named by a reserved word, LEFT's,
   an aggregate's ORDER BY, a chain of `+`/`-` with parentheses, an ELSE after a value that ends in END, IS DISTINCT
   FROM, a truth test, ESCAPE, an IN list it reorders), and SQL Anywhere's folding of a CASE or an IF whose test is a
   constant and whose value is not, over a table (**a latent wrong name of the second landing**: it names the folded
   value).
3. **`owner.table.column`** (`IqAliases.owned`): bound to the FROM item its owner and table name (an unaliased table,
   or `tb()` with the same name); none is IQ's -845, a name of no FROM item -142. An owner of IQ's own (`dbo`, `sys`,
   ...) qualifying a name that is no catalog name's (`IqBindings.catalogName`) reads the model's table of a schema of
   that owner's name when the model has one (`IqBindingsTest`; the parity model has no such schema); the catalog's
   names, and such a name the model does not hold, stay refused with the same message as before.
4. **Two output columns of one name** are answered where no one reads the names: an EXISTS's query, a set
   operation's later branch, a derived table given a full column list (renamed internally). Where the result shows
   them (the statement's own SELECT, an IN's or a scalar subquery's single column) the refusal stays: a relation's
   names are distinct.
5. **LIKE with a pattern the database computes** (`IqDialect.runtimePattern`, `runtimeLike`): a column, a host
   variable, `'%' || x || '%'`: the regular expression is built in the database -- each character itself (a
   backslash and the regular expression's own characters escaped), `%` any run, `_` one character (a line feed
   too), matched whole. A pattern holding `[` (IQ's set class: measured, `[b]` is a set and an unclosed `[` UNKNOWN)
   or longer than 126 characters (SQL Anywhere's -606 for some values) raises an error, never answers otherwise.
   Refused by name: ESCAPE, a profile that matches without case (`sqla17-*`), a CHAR(n) operand or pattern under
   IQ's engine (D-S5), a pattern that is no string.
6. **LIST forms** (`IqAggregates.list`): LIST of a GROUP BY key, in any order or none (its group's values are one
   value), and LIST(DISTINCT key) of an integer key (the value once). Still refused: LIST without ORDER BY of
   anything else (its order is the plan's), LIST(DISTINCT) of anything else (lite's Postgres orders a DISTINCT
   aggregate only by its argument's text), LIST(DISTINCT) of a string key (one collation key, several spellings).
7. **A checked SUM or AVG (D-E3) read for every group** is answered: a CASE's or an IF's first test, the first
   argument of COALESCE, ISNULL, IFNULL or ARGN, both of NULLIF's, a NOT's or a truth test's operand, BETWEEN's and
   IN's tested value; and an aggregate also read so elsewhere in its query (`IqDialect.lazyCalls`). Read only for
   some groups (a CASE's value or later test, a later argument, under AND or OR, BETWEEN's bounds, IN's list) it
   stays refused.
8. **TOP or FIRST in a subquery or a derived table** with ORDER BY (or under EXISTS, where only whether a row exists
   is read) is answered in SQL Anywhere's front end (`sa_rowgenerator`, `DUMMY`, a `sqla17-*` profile). Under IQ's
   engine it stays refused (the research's D-R1: IQ may ignore it there), and so does one without ORDER BY outside
   EXISTS and one in a set operation's branch.
9. **A subquery without FROM** is its one item's value: a whole select item `(SELECT expr)` under every engine,
   anywhere in SQL Anywhere's front end (IQ's engine restricts subquery positions, §9.4). One with WHERE, an
   aggregate, a row limit, DISTINCT or two items stays refused. TIME and REAL stay refused: lite has no time-of-day
   lowering, and SQL Anywhere computes and prints REAL in single precision, which no Pure type holds.
10. **IQ's codes for errors lite raised in Postgres's words**: an ORDER BY key no SELECT DISTINCT item shows is -854;
    set-operation branches of different widths -153; a column neither grouped nor aggregated -149, and -854 when
    ORDER BY reads it (measured). `SELECT ... FROM t INTO x` is refused as INTO (a statement, not a value);
    DATEPART, DATENAME, DATEADD and DATEDIFF with the wrong number of arguments are -154 (before the date part is
    read).

**Moved runs.** Eighteen REFUSED runs are PASS on both lanes, none otherwise: `basics/alias_read_in_where`; ten of
`m0.yaml`'s alias reads in an ORDER BY expression or WHERE (`alias_after_a_plus_sign_in_order_by`,
`alias_inside_an_order_by_sum`, `alias_inside_case_in_order_by`, `alias_inside_if_in_order_by`,
`alias_inside_isnull_in_order_by`, `alias_inside_nullif_in_order_by`, `alias_negated_in_order_by`,
`alias_of_another_case_inside_order_by`, `alias_of_no_column_inside_order_by`, `tsql_alias_read_in_where`);
`m1a/like_column_pattern_is_refused`; `m1b/name_with_a_comment_is_refused`;
`m1c/having_an_alias_before_a_column_of_its_name`; `m1e/owner_qualified_column_is_refused`;
`m1e/host_string_like_pattern_is_refused`; `rules/c01_language-220`; `rules/c09_statements-81` (all `@iq16-default`).
The four whose ids say "refused" keep their ids, the ledger's keys, with a dated comment. Every other run is unchanged.

**The syntax cases.** Accept PARSED 2,674 -> 2,687 (REFUSED 2,274 -> 2,261), thirteen cases: alias reads, FROM-less
subqueries with TOP and ORDER BY, and `tempdb.dbo.#t` (parsed now, refused by name when translated). Flag, reject and
both lists unchanged. The thirteen were snapped on SQL Anywhere 17 and run on both lanes: PASS 9, REFUSED 3, BOTH_ERROR
1, none otherwise.

**Evidence:** both lanes identical, 3,455 runs: PASS 2,184, REFUSED 835, BOTH_ERROR 432, SKIP 4; FAIL, BUG, ERROR 0
(was 3,095: PASS 1,913, REFUSED 789, BOTH_ERROR 389, SKIP 4). `m1gaps.yaml` alone: PASS 253, REFUSED 64, BOTH_ERROR 43.
The answers: 360 new; every earlier answer identical. The Postgres island's Pure is byte-identical and both
`sql-parity` ledgers unchanged. The measurements (the probe sets and the naming datasets) are kept with the private
research.

### 9.17 Settings: IQ's options, by IQ's own names

Built 2026-10-14 on `claude/iq-island-3`, over the M1 gaps (§9.16): the third landing's second part, the in-area core of
the options framework. An island is read under one **settings vector**, built the way IQ builds a connection's, and every
rule of the translator that depends on a setting reads the vector, never a profile's name. The five profile names stay,
as presets of it. The settings themselves will come from Pure: tagged values of a platform Profile whose tags are IQ's
exact names, on a Database element (the database's level) and on the enclosing function (the query's); that plumbing
touches the model and the compiler and lands separately. **No header**: no Legend syntax is added (the user's ruling,
2026-10-14: no Legend parser feature that upstream lacks), so an island still opens `#IQ{` or `#IQ:<preset>{`, and
nothing between `#IQ` and its brace sets anything. Inside the island, IQ's own SET statements may come before the SELECT
(below): that is IQ's syntax, read by the island's IQ reader, not the Legend parser's. Every case answers what SQL
Anywhere 17 answers, is refused by name, or is an error both raise: `lite/options/*.yaml`.
The measurements behind it (each name, level, value and error below on SQL Anywhere 17, in an IQ-like and a default
database) are kept with the private research.

**The names are IQ's own**, matched without regard to case, as IQ matches them; values are IQ's spellings (`On`,
`Off`, an integer, `DMY`), read back as `CONNECTION_PROPERTY` and `DB_PROPERTY` spell them:

- **database properties**, `DB_PROPERTY`'s names: `CaseSensitive`, `BlankPadding` (`On`/`Off`), `Collation`
  (`ISO_BINENG`, `UTF8BIN`), `CharSet` (`ISO_8859-1:1987`, `UTF-8`), `AccentSensitive`. The oracle's IQ-like database
  reads On / On / ISO_BINENG / ISO_8859-1:1987 / Off, its SQL Anywhere one Off / Off / UTF8BIN / UTF-8 / Off;
- **the connection**: `CommProtocol` (`'TDS'` or `'CmdSeq'`), `CONNECTION_PROPERTY`'s name;
- **options**: `SET OPTION`'s and `SYSOPTIONS`'s names, in lower case (`ansinull`, `date_order`, `precision`, ...).

**Where it lives.** `protocol/sql/iq/IqOption` is the closed registry: each database property, the connection's
protocol, and each Tier 1 to 3 option of the research's (and `tsql_variables`, which the island reads), with its level
(database property, connection, PUBLIC-only option, option), tier, kind of value, default under each engine base (IQ
16, SQL Anywhere 17: an option of SQL Anywhere's that IQ's documentation does not name is IQ's too, with SQL
Anywhere's default; an IQ-only one is absent from SQL Anywhere 17), TDS and ODBC login values, and status (built,
some values built, provisional with its decision, not built yet, pinned, ignored); and, by name only, the 318 Tier 4
options (IQ's documentation's, SQL Anywhere 17's, both), which change no SELECT. `IqSettings` is the vector, with IQ's
levels; `IqSetPrefix` reads the SET statements before a SELECT. `IqProfile` is the five presets: a base and a client (one at the database's defaults, IQ's ODBC driver, a TDS
login). A first commit made the translator read the vector with no other change (every ledger row, answer, syntax
verdict and the Postgres island's Pure identical).

**The one entry point**, `IqSettings.resolve(preset, settings)`: each setting `(level, name, value)`, its value text (a
tagged value's), built by IQ's precedence -- the base's defaults, the database's level, the login's overrides, the
query's level -- with IQ's errors, as SQL Anywhere 17 raises them:

- **the database's level** (`DATABASE`): a database property is the database's; any option (a PUBLIC-only one too) its
  PUBLIC value, which the login's temporary values hide, as in IQ (measured: a TDS login's `date_order` MDY stays MDY
  over `PUBLIC.date_order = 'DMY'`); `CommProtocol` the login (`'TDS'` a TDS login's `sp_tsql_environment` overrides,
  `'CmdSeq'` none; `'TDS'` with `iq16-odbc` refused). A name of no setting is an error (an unknown tag);
- **the query's level** (`QUERY`): an option is the connection's temporary value, after the login's. A database
  property, `CommProtocol`, a name of no option, an option the base lacks (an IQ-only option under SQL Anywhere 17) and a
  PUBLIC-only option are IQ's -203: IQ sets no such temporary option;
- **at either level**: a value the setting does not take is IQ's -201 -- On and Off only (in any case, never 1 or
  'yes'), YMD, MDY or DMY, an integer in its range written as digits (`'060'` is 60; `' 60 '` and `'1e1'` are -201), a
  number with a fraction -201 under SQL Anywhere 17 and refused by name under IQ 16 (IQ is documented to cut it: the
  research's D-Q2); an option with no effect on a SELECT (Tier 4) is accepted and ignored, its value unchecked; a
  setting given twice at one level is refused by name.

**IQ's SET statements before the SELECT**, so that an IQ script's SET lines read as written, and for the wire protocol
to come (a client sends them before its query): a run of them, each ended by `;`, then exactly one SELECT, after the
settings given. They are IQ's syntax, inside the island's SQL, read by the island's IQ reader (`IqSetPrefix`, through
`IqLexer`, when `IqDialect` parses the SQL), never by the Legend parser. Each is read under the settings the ones before
it made (a `quoted_identifier` or `escape_character` a statement sets changes how the next is lexed), the SELECT under
the last. The SELECT is the island's text with the statements blanked, line breaks kept, so every position is the
island's own. An option has IQ's four levels here, measured: the connection's temporary value, the user's, a temporary
PUBLIC one and PUBLIC's, the first of them set being the value (a user's value, `SET OPTION x`, replaces the
connection's temporary one at once, the login's included; a PUBLIC value is hidden by either). Read:

- `SET [EXISTING] [TEMPORARY] OPTION [PUBLIC.]name = [value]`: the name plain, `"quoted"` or `[bracketed]`; the value a
  word (`On`, `DMY`), a number (`6e1`, `60.0` and the string `'060'` are 60), a string, `NULL` or none (a reset).
  Measured errors: a sign or a parenthesis before the value, or a second value, -131; a name of no option -203 when set
  temporarily (also `TEMPORARY ... PUBLIC.`), -200 for the user, and for PUBLIC IQ's own new option, which no SELECT
  reads -- so are a database property and `CommProtocol`, no options of IQ's; a PUBLIC-only option (`precision`,
  `scale`, `reserved_keywords`, ...) set temporarily -203, for the user -202; a value the option does not take -201
  (On/Off only, never 1 or 'yes'; an integer's range). A reset of a temporary value falls back to the user's or
  PUBLIC's; of the user's, to PUBLIC's (the login's temporary value is gone too). Refused by name: a reset of a PUBLIC
  value (SQL Anywhere then reads its server's own default, no base's), an owner other than `PUBLIC`, a variable as the
  value.
- T-SQL's forms SQL Anywhere 17 reads as options: `SET ANSINULL ON|OFF` (and `ANSI_NULLS`), `SET QUOTED_IDENTIFIER
  ON|OFF`, `SET STRING_RTRUNCATION ON|OFF`, `SET DATEFIRST n` (`first_day_of_week`; outside 1-7 -201), `SET DATEFORMAT
  ymd|mdy|dmy` (`date_order`; another word -201), `SET ROWCOUNT n` (`row_count`; n an integer literal up to
  2147483647), each as the connection's temporary value; and the ones of no effect on a SELECT, accepted: `SET
  CHAINED`, `ANSI_PERMISSIONS`, `SELF_RECURSION`, `PREFETCH`, `NOCOUNT`, `ARITHABORT`, `ARITHIGNORE` (`ON|OFF`), `SET
  TEXTSIZE n`, `SET TRANSACTION ISOLATION LEVEL ...`, `SET STATISTICS IO ON`, `SET SHOWPLAN ON`. A form SQL Anywhere 17
  does not read (`SET CLOSE_ON_ENDTRANS`, `SET ESCAPE_CHARACTER`, `SET DATE_ORDER`, SQL Server's `SET XACT_ABORT` and
  the like) is -131, `SET LANGUAGE` -611; another SET (a variable's), a SET without its `;`, and SET statements with no
  SELECT after them are refused by name.

`lite/options/set_statements.yaml` runs each form, each level, the errors, and each option of `lite/options` set by a
statement; a statement over the settings given (`query.date_order='DMY'`, then `SET TEMPORARY OPTION date_order`)
follows IQ's levels too.

What the settings need that is not built is refused by name when the SELECT is read (`IqSettings.check`, for the
settings it is read under):

| option | values read | refused by name |
|---|---|---|
| `ansinull` | On, Off (both engines: Off in a CASE's condition stays three-valued in SQL Anywhere's front end, D-L1 under IQ's) | -- |
| `quoted_identifier`, `escape_character` | On, Off (each changes how the SQL is lexed) | -- |
| `date_order` | YMD, MDY, DMY | -- |
| `nearest_century` | 0 to 100 | a fraction under IQ 16 (D-Q2) |
| `date_format`, `timestamp_format`, `time_format` | any value accepted; a value is written as text in `YYYY-MM-DD` (any case) and three or six places of a second | another format where a date or a timestamp is written in it |
| `precision`, `scale` | any PRECISION and SCALE (each construct refuses what needs more than lite's 38 digits) | a SCALE above the PRECISION |
| `divide_by_zero_error` | On (-628); Off: NULL of the operation's type for every type, `/`, `%` and MOD, guarded where the database computes it | Off with a divisor of constants alone that is no literal (a database folds it before the guard) |
| `ansi_substring` | On; Off: a negative start counts from the end, 0 is the first position (the last for a negative length), a negative length takes the characters ending at the start (measured over every start from -10 to 10 and length from -3 to 10) | -- |
| `first_day_of_week` | 1 to 7: DATEPART's weekday counts from it (no other part moves, nor DATENAME, nor DOW) | a value other than 7 under IQ's engine (it documents DATE_FIRST_DAY_OF_WEEK: D-T9) |
| `tsql_variables` | On, Off (§9.15) | -- |
| `conversion_error`, `string_rtruncation` | On | Off (D-C1, D-C2) |
| `min_numeric_division_scale`, `date_first_day_of_week` (IQ 16 only) | the default | another value (D-N2, D-T9) |

Tier 2 options are read at their default (`row_count` 0, `return_date_time_as_string` Off, ...) and any other value is
refused by name (`row_count`'s needs D-Q3); Tier 3 options are pinned to theirs. A setting a later level hides changes
nothing and is no refusal.

**Database properties as independent knobs.** The translator read them separately already; two reads tied to the
base are now the charset's (`CharSet`): a name's length in bytes (IQ's -250 past 128) and where SQL Anywhere cuts an
unaliased item's name at 255 bytes. Every combination is read, each judged on a database the oracle makes for it: on
the IQ 16 base, CaseSensitive On or Off with ISO_BINENG or UTF8BIN (always blank-padded: BlankPadding Off is refused by
name, as IQ creates no other); on the SQL Anywhere base, each of CaseSensitive, BlankPadding and the two collations.
Refused by name: AccentSensitive On, a collation other than ISO_BINENG and UTF8BIN, a charset other than their two, a
collation and a charset that disagree. `lite/options/database_properties.yaml` runs fourteen string queries
(equality with and without trailing blanks, an ordering, LIKE, IN, an accented letter, ORDER BY, UPPER and LOWER,
LOCATE, REPLACE, COUNT DISTINCT, a grouping key) under each of the ten combinations beyond the two bases'.

**A real IQ 17.1's database.** A HANA Cloud data lake (IQ 17.1) reads back CaseSensitive On, BlankPadding On,
Collation UTF8BIN, CharSet UTF-8 and CommProtocol CmdSeq. Given at the database's level over the IQ base, every one of
them is read and none is refused (`IqSettingsTest.anIq17DataLakesSettingsAreExpressed`; `settings.yaml`'s
`an_iq_17_data_lakes_database`, on the oracle's database of that combination, under both IQ presets: CmdSeq drops a TDS
preset's login). The presets themselves stay IQ 16's and SQL Anywhere 17's in this part.

**A latent wrong answer fixed.** A string compared with a column of a NUMERIC(p, s) is that NUMERIC first, rounded to s
places, where with an integer column or an expression of any type it is a NUMERIC of the PRECISION and SCALE (measured
at five pairs of them: against a NUMERIC(10,2) column `'0.005'` is 0.01, so `nm > '0.005'` is false for 0.01). The
island read it at PRECISION and SCALE everywhere; such a string with more places (or more whole digits) than the column
holds is now refused by name (no committed case compared one: every earlier run is as it was).

**The harness and the oracle.** A case's `settings` key gives its settings, `database.<name>=<value>` and
`query.<name>=<value>`, as the tagged values will. Until they reach an island, the lanes give them to
`IqSettings.resolve` and translate the case's SQL under the result as the island's seam does (the schema's tables its
sources), then run the translation; its answer is stamped with them (`sqlSha`'s one more part).
`tools/iq-oracle/snap.py` runs such a case on a connection of its own: the database its properties need (made with
`dbinit`'s `-c`, `-b` and `-z`, IQ 16's PUBLIC precision and scale on the IQ base; eleven beyond the two), the login
`CommProtocol` gives, each other database-level option for PUBLIC and each query-level setting temporarily, then the
case's SET statements, each in turn; then every option the case set is restored from the catalog's `SYSOPTIONS` (a
temporary PUBLIC value reset). A case whose SQL begins with SET statements runs so too. The case files may now sit in `lite/`'s subdirectories.

**Moved runs.** None: every earlier run is as it was on both lanes, and every earlier answer and syntax verdict.

**Evidence:** both lanes identical, 3,925 runs: PASS 2,552, REFUSED 880, BOTH_ERROR 489, SKIP 4; FAIL, BUG, ERROR 0
(was 3,455: PASS 2,184, REFUSED 835, BOTH_ERROR 432, SKIP 4). `lite/options/` alone, 339 cases, 470 runs: PASS 368,
REFUSED 45, BOTH_ERROR 57, of which the SET statements' 63 cases, 101 runs: PASS 72, REFUSED 2, BOTH_ERROR 27 (without
them, 3,824 runs: PASS 2,480, REFUSED 878, BOTH_ERROR 462). The answers: 470 new; every earlier answer identical; the
answers file's meta line lists the new databases and how settings and SET statements are applied. The Postgres island's Pure is byte-identical and both `sql-parity` ledgers
unchanged.

**Not in this part.** The Pure plumbing (the platform Profile and the tagged values on a Database and a function, read
where the island is compiled); the presets' move to IQ 17.1 (the fourth landing); and a lite-only assertion that the
data's strings are canonical (for grouping over strings): each waits on the user's decision or on other lines' files.
