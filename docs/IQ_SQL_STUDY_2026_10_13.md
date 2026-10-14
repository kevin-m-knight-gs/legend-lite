# SAP IQ SQL in Pure: the `#IQ{...}#` island, the research behind it, and the plan

**Status: study and plan (2026-10-13), with the user's decisions recorded in §4. The line is announced in
`docs/IN_FLIGHT.md` ("A tenth line"), on the branch `claude/iq-island`. Nothing has landed yet; §9 records each slice
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
2. **Status.** The research is done and the decisions are taken (§4). The first landing (§7, PR1 and PR2) is announced
   in `IN_FLIGHT`, with its cross-area edits listed there. No code is on the branch yet.
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
| `syntax-cases.jsonl` | derived from the research: id, probe SQL, options, policy, SA's outcome. No rule text, no notes, and no probe that reproduces a manual's example (the derivation checks each probe against the manuals' text and drops matches) |

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

### 9.1 The first landing: the seam, the island and M0's pipeline (PR1, PR2)

_Not landed._

### 9.2 The rest of M0: T-SQL forms, row limits, IF … ENDIF, concatenation, integer arithmetic (PR3)

_Not landed._

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
