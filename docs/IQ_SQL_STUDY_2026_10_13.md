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
- **Timestamps from 7911-01-01 on** lose their hours and minutes. The schema holds none; `engine=sa` cases avoid them.
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
    Anywhere as in lite: outside the decision.
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
  refused by name.
- **O3. Host variables and parameters.** `@var`, `:name` and `?` as Pure variables in scope (the Postgres island's
  `var('name')` is the precedent). Open: how a positional `?` binds, and the research's ruling that a column wins over
  a variable of the same name (GRAMMAR L27).
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

_Not landed._

### 9.4 M1b: relations (PR5)

_Not landed._

### 9.5 M1c: grouping (PR6)

_Not landed._

### 9.6 M1d: functions (PR7)

_Not landed._

### 9.7 M1e: names, variables and profiles (PR8)

_Not landed._

### 9.8 The natives

_Not landed._
