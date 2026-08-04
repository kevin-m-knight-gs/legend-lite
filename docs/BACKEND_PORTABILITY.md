# Backend portability — the cross-cutting conclusions

> **What this is.** Four backends were probed to the standard `H2_BACKEND.md` set: execution against
> a real engine, value equality checked, documentation treated as a claim to verify. The per-backend
> evidence lives in `H2_BACKEND.md`, `SQLITE_BACKEND.md`, `POSTGRES_BACKEND.md` and
> `MARIADB_BACKEND.md`. **This document holds the conclusions that no single backend doc owns** —
> the ones that only become visible when you put four capability maps side by side.
>
> Companion: `DUCKDB_FUNCTION_COVERAGE.md` (the vocabulary-growth question, which turns out to share
> a root cause with §5 below).
>
> **§7 is a different kind of evidence** — not what the engines can do, but **how upstream Legend
> actually tests them**: three tiers, split by whether anyone can get a server. It answers "there
> are DB2 tests upstream, how do those run?" (they never touch a database) and it settles what our
> own harness should and should not reproduce natively.

**Evidence standard.** ~5,000 probe statements, one shared harness, the same corpus on every
backend, so results are comparable cell-by-cell. Real engines throughout: DuckDB 1.5.0.0,
SQLite 3.47.1, PostgreSQL 18.4, MariaDB 11.4.5, H2 2.4.240.

---

## 1. Reachability, side by side

| Backend | A native | C rendering | B rewrite | D impossible | reachable |
|---|---:|---:|---:|---:|---:|
| **Postgres 18.4** | 155 | 56 | 52 | **4** | **98.5%** |
| **SQLite 3.47.1** | 105 | 55 | 78 | 29 | **89%** |
| **H2 2.4.240** *(from `H2_BACKEND.md` §2, different denominator)* | 138 | 65 | 3 | 49 | **80%** |

> Denominators are not identical — this study slices 267 constructs, `H2_BACKEND.md` slices 256, and
> the partitions overlap imperfectly. **Comparable in spirit, not subtractable.** MariaDB's totals
> are in `MARIADB_BACKEND.md`.

Look at the **B column**. H2 has 3; SQLite has 78. That single contrast is the finding of §2.

---

## 2. H2 is the outlier, not the template

`H2_BACKEND.md` §4 is dominated by **D1 — no `LATERAL`, no correlated table function**, and §4.1
concludes that reaching H2 requires re-lowering collections from *values* to *relations*: a Phase-H/I
redesign of `Scalars.java` + `Fold.java` + `ListShapes.java`.

That conclusion is **correct for H2 and true of no other backend measured.**

| | mechanism for correlated row explosion |
|---|---|
| **Postgres** | full `LATERAL` in every position, plus `unnest`, plus `JSON_TABLE` (17+) |
| **SQLite** | no `LATERAL` — but `json_each` accepts a **row-correlated argument**, in every position probed including inside a scalar subquery |
| **MariaDB** | no `LATERAL` — but **has `JSON_TABLE`** (10.6+), row-correlated, contrary to the widespread claim it is MySQL-only |
| **H2** | **none. Verified absent at 2.4.240.** |

Verified end-to-end, not merely parsed: on all three new backends, *build a carrier column by
aggregation → correlate-explode → filter → re-aggregate* returns the identical value to DuckDB. The
empty-collection case — the one graph fetch depends on — holds too: inner join 3 rows, left join 6,
with the empty-list rows preserved.

### 2.1 Reconciliation with `CARRIER_REDESIGN.md` (R0 landed 2026-08-01)

A parallel workstream is executing the §4.1 carrier redesign, and its measurement **sharpens this
section rather than contradicting it — but it also corrects a conclusion this study would otherwise
have drawn.**

That doc measures **1,250 of 2,538 corpus tests walling on collection carriers on H2** (UNNEST 792,
array literal 226, LIST_AGG 133, …), and records the decisive observation:

> *legend-engine passes these same tests on H2 — the walls are OUR lowering's DuckDB-native carrier
> choices, not H2 limits.*

**That reframes the redesign.** It is not "H2's compatibility mode", which is how a
reachability-percentage reading of this study would cast it. It is **"stop being DuckDB-native"** —
and that is universally valuable, because every finding in §3, §5 and §6 below is downstream of the
same root cause. The semantic-node + per-dialect-strategy-pass architecture it lands is precisely the
"one rewrite pass parameterised by N spellings" §3 arrives at independently.

**What this study contributes to that design, and it is a live constraint:**

> The redesign's *target* need not be relations-only. Relations-only is what H2 forces because it
> cannot explode a correlated collection. **Postgres, SQLite and MariaDB all can** (§2), so their
> strategy rules can keep collections as **values exploded at point of use** — which is cheaper and
> preserves DuckDB's shape. A `DialectCapability` for *correlated explosion* would let one semantic
> node serve both worlds, rather than every backend paying H2's relational cost.

**The roadmap consequence, corrected:** the carrier redesign is not a precondition for one backend
out of four — it is the removal of a DuckDB assumption that taxes all of them. It should be
sequenced early. What should *not* be assumed is that its output shape must be H2's.

---

## 3. The carrier question has one answer, not N

`H2_BACKEND.md` §4.1 argues the relational carrier "is the right redesign for N backends generally,
since Postgres, Snowflake and BigQuery each need a *different* one." Measured, that is **right on
diagnosis, wrong on remedy.**

**One JSON carrier serves all of them.** Build with `json_group_array` / `json_agg` /
`JSON_ARRAYAGG`; explode with `json_each` / `jsonb_array_elements` / `unnest` / `JSON_TABLE`. Under
that carrier the `LIST_*` constructions are the **same query shape** on every backend — explode to
rows, do relational work, re-aggregate. That is *one rewrite pass parameterised by three spellings*,
not three redesigns. Postgres's D count for the collections slice drops from 1 to **0** under it.

**And the intuitive alternative is a trap.** Postgres has a real `ARRAY` type, the identical
`ARRAY[1,2,3]` literal, and the *same* `java.sql.Array` read-back as DuckDB. It looks free. It is
free until `List<List<T>>`:

| | |
|---|---|
| `ARRAY[ARRAY[1,2],ARRAY[3]]` | **ERROR** — Postgres arrays are rectangular, not jagged |
| `cardinality(ARRAY[ARRAY[1,2],ARRAY[3,4]])` | **4**, not 2 — `LIST_LENGTH` silently wrong |
| `arr[1]` on a 2-D array | **NULL** |
| `unnest` on a 2-D array | flattens **all** levels |

Three of those are silent wrong answers. **Pure's `List<List<T>>` is unrepresentable in native
arrays.** Choosing them means the type system must know "this collection is nested" and fall back to
`jsonb` — two carriers on one backend, strictly worse than one.

**Recommendation:** JSON as the *declared* carrier for `SqlType.Array` on every backend, with native
arrays permitted only as a flat, depth-1 optimisation where they exist.

*Free win found along the way:* `ARRAY[…]` is accepted by **both** DuckDB and Postgres, while
DuckDB's `[…]` is a Postgres syntax error. One-line renderer change, array literals become portable
at zero cost.

---

## 4. The unowned responsibility: session configuration

This is the macro finding. **On every backend measured, correctness depends on session settings that
no component in legend-lite owns.**

| Backend | Setting | What goes wrong silently if unset |
|---|---|---|
| **MariaDB** | `ANSI_QUOTES` | `SELECT "name"` returns the **string `'name'`**. legend-lite quotes every identifier with `"`, so **every column reference becomes a constant.** No error. |
| **MariaDB** | `PIPES_AS_CONCAT` | `'a' \|\| 'b'` → `0`. `\|\|` is OR; parses, returns a number. |
| **MariaDB** | `NO_BACKSLASH_ESCAPES` | `LENGTH('\\')` is 1 not 2 — every regex pattern corrupted at parse. |
| **MariaDB** | `collation_connection` | default is case-**insensitive** and PAD SPACE: `'abc'='ABC'` and `'abc'='abc '` are both true. Only `utf8mb4_nopad_bin` matches DuckDB. |
| **Postgres** | JVM `TZ` | pgjdbc renders `timestamptz` in the **JVM** zone; `SET TIME ZONE 'GMT'` does **not** fix it — it only changes server text. |
| **SQLite** | `Ddl` emitting `TEXT` not `DATE` | `DATE` columns have NUMERIC affinity — `CAST('2020-01-15' AS DATE)` → `Integer:2020`, month and day **discarded**. |
| **H2** | `MODE=LEGACY`, 26-word `NON_KEYWORDS`, `timeZone='GMT'` | recorded in `H2_BACKEND.md` §7 |

Note the shape of this table. These are not capability gaps, and they are not spelling. They are a
**responsibility that is smeared across connection strings, DDL emission and the JDBC layer with no
single owner.** `H2_BACKEND.md` §11 already recorded the symptom without naming the disease: core's
`DbMetaData` opens `jdbc:h2:mem:` at two call sites *with different settings than `H2Verify`* — the
same backend configured two ways in one codebase.

> **Recommendation: make session policy a first-class, per-dialect concept** — a declared record of
> the settings a backend requires, applied at connection open and **asserted**, rather than embedded
> in whichever URL string each call site happens to write. This is the same "one owner per behavior"
> rule the project already applies to lowering, applied to configuration.

The MariaDB `ANSI_QUOTES` row is the argument for urgency: it is a single flag standing between
"MariaDB works" and "every query returns column names as data, silently."

---

## 5. `normalize` is the correctness seam, and one channel bypasses it entirely

### 5.1 One semantic function, four Java types

`SUM` over an integer column, same query, four drivers:

| DuckDB | Postgres | MariaDB | SQLite |
|---|---|---|---|
| `BigInteger` | `Long` / `BigDecimal` | `BigDecimal` | `Integer` |

None is wrong. `H2_BACKEND.md` §8 predicted this is where the deferred `ValueCodec` step
materialises; it does. The count of `normalize` families per backend: **H2 four, Postgres four,
SQLite eight, MariaDB nineteen rows.**

On SQLite `normalize` is not cosmetics — it is load-bearing for correctness, because SQLite has
storage-class affinity rather than types and **a single column can return different Java types row to
row**. That last property means normalize must be driven by the **declared SQL type**, never by
`instanceof` on the returned object.

### 5.2 The channel that has no seam at all

`SqlAgg.Reducer` / `RankingFn` / `ValueFn` carry a raw **`String fn`** that
`AnsiSqlRenderer.java:621-622, 667` renders **verbatim** — no dialect mapping, no exhaustiveness
check, no capability wall. `AGENTS.md:148-150` forbids this by name: *"No MIR record has a `String`
field encoding a SQL operation… no `String funcName`, no `String op`, no `String sqlName`."*

Only `EngineStyleH2` overrides `reducer()`. So on **every** other backend, every aggregate and window
function name is emitted unchanged, whatever the target actually spells it.

This is the same defect `DUCKDB_FUNCTION_COVERAGE.md` reaches from the opposite direction: the
"passthrough escape hatch" that the *implement-every-DuckDB-function* question proposes adding
**already exists, undeclared and leaking.** Two independent investigations landing on one root cause
is the strongest signal in this study.

> **Fix this before adding backends, not after.** It is a prerequisite for both workstreams: a
> stringly-typed channel cannot be made portable, and it silently defeats the javac exhaustiveness
> guarantee that makes `SqlFn` safe to extend.

---

## 6. The dominant defect class is silent wrong answers, not capability gaps

Capability gaps announce themselves — a `D` throws, gets declared in the gap registry, and stays
legible. The findings that will actually cost correctness all **execute cleanly and return the wrong
value.** A partial roll-call, all measured:

| | DuckDB | target |
|---|---|---|
| `SELECT id, regexp_matches(name,'[0-9]') FROM emp` | **5 rows** | **Postgres: 0 rows** — set-returning function acts as a filter |
| `'abc123' ~ 'bc1'` | `false` (full match) | `true` (Postgres: partial match) — inverted |
| `SELECT "name" FROM emp` | `alice` | `name` (MariaDB, no ANSI_QUOTES) |
| `CAST('2020-01-15' AS DATE)` | `2020-01-15` | `Integer:2020` (SQLite) |
| `CAST(1 AS DECIMAL)/CAST(3 AS DECIMAL)` | `0.333…` | `0` (SQLite) |
| `round(2.5)` on a DOUBLE column | `3` | `2` (Postgres — half-even) |
| `-8 >> 1` | `-4` | `9223372036854775804` (MariaDB — unsigned) |
| `greatest(sal,100)`, `sal` NULL | `100` | `NULL` (SQLite) |
| `regexp_replace` | first match | **all** matches (MariaDB, same function name) |
| default NULL sort order | LAST/LAST | SQLite FIRST/LAST · Postgres LAST/FIRST |

The strings slice alone documents **11**; collections documents **15**; the other slices add dozens
more. **Not one of them raises an error on any backend.**

This has a direct methodological consequence: **a portability sweep that asserts only "did it run"
is worthless here.** Row equality against the DuckDB reference is the minimum honest gate, which is
what the project's existing contract already says — and it is why the probe harness compared *values*
rather than parse success.

---

## 7. How upstream tests backends — the three tiers

> Measured against `legend-engine 4.135.0` / `legend-pure 5.89.2`. This section exists because a
> reasonable question — *"legend-engine has DB2 tests, how do they run those?"* — has an answer
> that reframes what a backend doc like this one is even claiming.

Upstream splits every database into exactly three tiers, and the split is not about importance.
It is about **whether anyone can get a server**.

| tier | how a server is obtained | databases | runs in CI? |
|---|---|---|---|
| **I — executed, containerised** | testcontainers, public image | Postgres `postgres:16.10`, Oracle `gvenzl/oracle-xe:21-slim-faststart`, SqlServer `mcr.microsoft.com/mssql/server:2019-latest` (`ACCEPT_EULA=Y`), MemSQL `ghcr.io/singlestore-labs/singlestoredb-dev`, ClickHouse, Trino; H2 + DuckDB in-process; **Spanner via the *emulator*** `gcr.io/cloud-spanner-emulator/emulator` | **yes, every build**, unconditional |
| **II — executed, credential-gated** | a real SaaS account | Snowflake, Databricks | **skipped by default** — poms carry `<skip>true</skip>`, re-enabled by a `pct-cloud-test` profile reading AWS Secrets Manager; CI activates it only on same-repo PRs/pushes, so **fork PRs skip silently** |
| **III — SQL text only** | *none* | **DB2**, Sybase, SybaseIQ, Hive, Presto, SparkSQL, Athena, Aurora, BigQuery, Redshift | n/a — no database is involved |

Registries are overridable (`-Dlegend.engine.testcontainer.registry=…`). Nothing is `@Ignore`d;
gating is entirely pom-profile plus CI conditionals.

> **Postgres is tier I, and upstream's own docs say otherwise — the docs are wrong.**
> `legend-engine/CLAUDE.md:41` lists Postgres among *"Cloud stores (`pct-cloud-test` profile,
> CI-only, requires secrets)"*. The code disagrees:
> `PostgresTestConnectionIntegration.java:49` is
> `new PostgreSQLContainer(DockerImageName.parse("postgres").withTag("16.10"))`, the module's pom
> declares the testcontainers dependencies with **no `<skip>`**, and `postgres` is a group in
> `.github/workflows/resources/modulesToTest.json`. This table follows the code — the same
> discipline as §9's *"don't trust documentation on any of this"*, applied to upstream's prose
> rather than a vendor's.

**Tier III is not a choice.** It is what a database gets when nobody can run one. No vendor sits
there on purpose.

### 7.1 DB2 in particular — and why our renderer is already at parity

DB2 has roughly 30 tests upstream and **not one of them touches a database.** All are of the form:

```pure
let db2sql = toSQLString(|Person.all()->project(p|length($p.firstName), 'nameLength'),
                         simpleRelationalMapping, DatabaseType.DB2, ...);
assertEquals('select CHARACTER_LENGTH("root".FIRSTNAME,CODEUNITS32) as "nameLength" from personTable as "root"', $db2sql);
```

spread across `testToSQLString.pure`, `testSqlFunctionsInMapping.pure`, `testPostProcessor.pure`
(`testDb2ColumnRename`), `executionPlanTest.pure`, and a few TDS/groupBy tests.

**DB2 is not runnable in upstream Legend at all.** A repo-wide search for `com.ibm` / `jcc` /
`jdbc:db2` returns three hits, all documentation URLs inside comments — there is no JDBC
dependency at any scope, so `DatabaseManager.fromString("DB2")` reaches
`throw new RuntimeException(dbType + " not supported yet")`. IBM does publish a free Db2 Community
Edition container; **upstream does not use it** — no compose file, no testcontainers reference, no
image name anywhere. The driver-licensing question is sidestepped by never pulling the driver.

DB2 also sits at the *lowest* tier structurally: no `-pure`, `-protocol`, `-grammar`, `-execution`,
`-connection`, `-PCT` or `-SDT` module, and its 196-line dialect is still inside
`core_relational/.../sqlQueryToString/dbSpecific/db2/` when every other vendor was promoted out to
`dbExtension/`. Even Hive and Sybase have their own `-pure` module. There is no
`Test_Relational_DB2_PCT.java` and no `pct-manifests/relational-db2/` — the complete manifest set is
h2, duckdb, postgres, snowflake, databricks, clickhouse, trino, oracle, memsql, sqlserver, spanner,
deephaven, java, core-compiled, core-interpreted.

> **Consequence for us: `EngineStyleH2`'s sibling `EngineStyleDB2` is at parity with upstream by
> construction.** `TENET_REMEDIATION.md` §C1 flags it as "respells only the dialect-owned function
> forms the DB2 goldens pin" and calls that curve-fitting to the corpus. That judgment should be
> revised: golden text is **the entire DB2 surface that exists upstream**. There is no execution
> semantics to be behind on and no PCT baseline to chase. `EngineStyleDB2` is complete, not a stub.

*One inference, flagged as inference:* a dead `private` method
`batchInsertRealizedRelationalResultToDB2TempTable(...)` with no callers, plus `SESSION.`-prefixed
temp tables in the dialect, suggests DB2 **is** executed in a Goldman-internal fork. Nothing like
it is in the open-source repos.

### 7.2 What to copy, and what our no-Docker choice cost

**Copy tier I for anything with a public image.** This study deliberately ran real engines with
**no Docker** — embedded Postgres, MariaDB4j, in-process SQLite — which bought fast local runs and
a genuinely offline harness, and cost a native-toolchain fight (the MariaDB4j `libpcre2` patch:
build from source, `install_name_tool -change`, re-`codesign`, patch inside the jar in `~/.m2`).
That trade was right for the four backends here. It stops being right the moment we want Oracle,
SqlServer, ClickHouse or Trino evidence: upstream's images are public, pinned, and already
parameterised, and reproducing any of those natively is strictly harder than running the container.

**Copy tier II verbatim for `CLOUD_BACKENDS.md`.** `<skip>true</skip>` plus a credential-gated
profile is exactly the shape our Databricks Free Edition / Snowflake trial work needs: the tests
exist and are runnable by whoever has an account, and they never break a build for anyone who
doesn't.

**Do not aim for tier III.** If a backend can only be verified by comparing SQL text, we have a
dialect, not a backend — and §6's central finding is that the defects that matter here *execute
cleanly*. Text comparison cannot see any of them.

---

## 8. Recommended sequencing

**Do first — these are defects today, independent of any new backend:**

1. **Close the stringly-typed aggregate/window channel** (§5.2). Prerequisite for everything else,
   and a violation of a written invariant.
2. **Fix `regexp_matches` in projections and `~` semantics** (§6). Latent wrong-row bugs in any
   dialect reusing those spellings.
3. **Render `ROUND_HALF_UP` as `round(CAST(x AS numeric))`** — currently delivers banker's rounding
   on Postgres DOUBLE columns, the opposite of its name.
4. **`Ddl` emits `TEXT` for temporal columns on SQLite** — one line, removes a whole corruption class.
5. **Render `ARRAY[…]` not `[…]`** — free portability, correct on DuckDB too.

**Then the seam work, once:**

6. **Session policy as a per-dialect declared record** (§4), applied and asserted at connection open.
7. **`SqlDialect.normalize` populated per backend** (§5.1), driven by declared SQL type.
8. **Reconcile dialect and connection** (`H2_BACKEND.md` H5.4) — nothing asserts they agree today,
   and that is a prerequisite for a second backend existing at all.
9. **The declared-gap registry** (`H2_BACKEND.md` §9) — the honest D's need a machine-readable home:
   Postgres 4, SQLite 29, H2 49.

**Then backends, in this order:**

10. **Postgres first** if the goal is portability — 98.5% reachable, 4 D's, boots in 7s with no
    Docker, and it exercises the seam hardest at the lowest cost.
11. **SQLite** — already at compile scope; its value is that it forces the carrier question (§3) and
    the `normalize` question (§5.1) honestly, because it has neither arrays nor types.
12. **H2 keeps its priority** — and the reachability table in §1 is the wrong instrument for
    deciding that. H2 scores worst on percentage-reachable, but it is the backend with **2,538
    corpus tests** behind it, and `CARRIER_REDESIGN.md` shows 1,250 of them wall on carriers
    legend-lite chose, not on H2 limits. Percentage-reachable measures the *engine*; the corpus
    measures **our lowering**. H2 is where the evidence is, so it stays first — this study's
    contribution is that the other three should shape the redesign's *target*, not that they should
    displace H2 in the queue.

---

## 9. What NOT to do

- **Don't generalise H2's *wall*, but don't dismiss H2's *evidence* either** (§2.1). H2 is the only
  backend lacking correlated explosion, so its relations-only shape must not be imposed on the three
  that have it. It is also the backend with 2,538 corpus tests, so it stays the primary source of
  truth about our lowering. Both are true; the reachability table alone will mislead you.
- **Don't adopt native array types as the collection carrier** (§3), most of all on Postgres, where
  they look most convincing.
- **Don't leave session configuration in connection strings** (§4). One MariaDB flag is the
  difference between correct and uniformly wrong.
- **Don't add a passthrough for DuckDB functions before closing the one that already exists** (§5.2).
- **Don't accept a portability sweep that only checks for exceptions** (§6). Every finding that
  matters here executes cleanly.
- **Don't read a vendor's presence in `DatabaseType` as evidence it is supported** (§7). DB2 is an
  enum member, a 196-line dialect and ~30 string-comparison tests, with no driver and no execution
  path anywhere. Sybase, Hive, Presto, SparkSQL, Athena, Aurora, BigQuery and Redshift are the same.
- **Don't reproduce natively what upstream already runs in a container** (§7.2). The no-Docker
  choice was right for these four backends and is the wrong default for Oracle, SqlServer,
  ClickHouse or Trino.
- **Don't trust documentation on any of this.** Execution refuted docs repeatedly: MariaDB *has*
  `JSON_TABLE`, MariaDB's default `sql_mode` does *not* include `ONLY_FULL_GROUP_BY`, SQLite 3.47
  *does* have FULL OUTER JOIN and math functions, and `sqlite-jdbc` ships 21 functions stock SQLite
  lacks.
