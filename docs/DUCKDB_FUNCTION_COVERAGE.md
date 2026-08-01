# Implementing "every DuckDB function" — design

> **Question asked:** how do we implement EVERY DuckDB function in legend-lite?
>
> **Companions:** `H2_BACKEND.md` (§9's declared-gap registry is the mechanism this doc reuses;
> its capability map is the numeric evidence that other backends are weaker), `TENETS.md`,
> `AGENTS.md` (invariants 3, 3a, 4), `FUNCTION_REGISTRY.md` (the Pure-side registry proposal).

**Evidence standard.** Every DuckDB count below was produced by **executing a query against a real
`duckdb_jdbc-1.5.0.0` in-process database** (`SELECT version()` → `v1.5.0`) and is tagged with the
query ID that produced it. The raw catalog dump — 2,940 rows, 13 columns — is at
`scratchpad/duckdb-functions.tsv`. Every legend-lite count was produced by a `grep`/`awk` over
`core/src/main/java`, tagged `[src]`, with the file named. Where a number in an existing doc
disagrees with a number measured today, today's measurement wins and the divergence is called out.
Claims sourced any other way are marked **UNVERIFIED**.

---

## 1. Verdict

**"Every DuckDB function" is the wrong goal, and the number that makes it wrong is not 938 — it is
164 × N.**

- **DuckDB 1.5.0 exposes 938 distinct function names / 2,940 overloads** (Q2). At the default
  extension set — only 5 of 26 extensions are loaded (Q5).
- **legend-lite reaches 163 of them today** (G6) — 17.4% of the catalog — through **164 `SqlFn`
  constants** (`[src]` SqlFn.java) plus a **stringly-typed aggregate/window channel** that is not
  `SqlFn` at all.
- **`SqlFn` must not grow.** It is a semantic vocabulary rendered by an **exhaustive javac-enforced
  switch in every dialect** (`AnsiSqlRenderer.call`, no `default ->` for the classified arms). Its
  cost is **|SqlFn| × N backends**, and N is going up: H2, SQLite, Postgres, MariaDB, Snowflake and
  Databricks are all being planned or probed. Widening it to the DuckDB-relevant surface would add
  **~295 constants → ~1,770 new render arms across 6 planned backends**, and `H2_BACKEND.md`'s
  measured capability map says **26% of today's much smaller vocabulary is already structurally
  impossible on H2** (43 D of 163). A tier selected *because DuckDB has it* would approach a 100%
  D-rate. **Reject (a).**
- **The escape hatch the question reaches for already exists — undeclared, unaudited, and in
  violation of a written invariant.** `SqlAgg.Reducer`/`RankingFn`/`ValueFn` carry a **raw
  `String fn`** that `AnsiSqlRenderer:621-622, 667` renders **verbatim, with no dialect mapping, no
  exhaustiveness check and no capability wall**. `Windows.WindowFn` declares the field literally as
  `String sqlName`. AGENTS.md §3a forbids this by name (§4.2). **Fix this before adding any new
  passthrough — otherwise the new one inherits a precedent that is already leaking.**
- **The right shape is tiering plus a declared-gap registry**, not passthrough and not widening:
  a portable `SqlFn` core that stays roughly its current size, and a **DuckDB-only extension tier**
  whose non-portability is *declared in machine-readable data* rather than discovered at runtime on
  the fifth backend. §6.
- **The catalog can generate the registry's skeleton but not its contract.** Name, arity, parameter
  types, return type and description are machine-readable (2,064 of 2,940 rows carry a description
  — Q6). Null semantics, Pure type mapping, and pinned behaviours like banker's rounding are not,
  and are exactly where legend-lite's existing bugs live. §5.4.
- **The defensible target is ~490 names, not 938** (§5) — and legend-lite already covers **131 of
  the 459 documented ones** (J2), 28.5%.

---

## 2. The census — DuckDB 1.5.0, default extension set

### 2.1 Totals

| function_type | overloads | distinct names | Query |
|---|---|---|---|
| `scalar` | 1,458 | 611 | Q1 |
| `aggregate` | 1,177 | 88 | Q1 |
| `macro` | 131 | 118 | Q1 |
| `table` | 125 | 85 | Q1 |
| `pragma` | 45 | 45 | Q1 |
| `table_macro` | 4 | 4 | Q1 |
| **TOTAL** | **2,940** | **938** | **Q2** |

> Per-type distinct names sum to 951, not 938: **13 names are registered under more than one
> `function_type`** (Q23) — `range`, `repeat` and `generate_series` are both `scalar` and `table`;
> `histogram` is both `aggregate` and `table_macro`; `version` is both `pragma` and `scalar`.

**Overloads are not functions.** The 2,940 is dominated by type-parametric expansion:

| Function | overloads | Query |
|---|---|---|
| `min_by`, `max_by`, `argmax`, `argmin`, `arg_min`, `arg_max`, `arg_min_nulls_last`, `arg_max_nulls_last` | **92 each** | F21 |
| `arg_min_null`, `arg_max_null` | 91 each | F21 |
| `+` | 45 | F22 |
| `-` | 39 | F22 |

Ten aggregate names account for **916 of the 1,177 aggregate overloads (78%)**. Any plan that
counts overloads is counting DuckDB's type matrix, not its vocabulary.

### 2.2 Structure of the surface

| Fact | Value | Query |
|---|---|---|
| Schemas | `main` 2,879 / 894 names · `pg_catalog` 61 / 48 names | Q4 |
| `internal = true` | **2,940 — all of them.** On a fresh in-memory DB the flag partitions nothing | Q3 |
| Rows carrying a description | 2,064 of 2,940 (70.2%) | Q6 |
| Extensions known / loaded | 26 known, **5 loaded** (`core_functions`, `icu`, `jemalloc`, `json`, `parquet` — all `STATICALLY_LINKED`); 21 `NOT_INSTALLED` | Q5, Q5b |
| Settings | 151 | Q12 |
| Keywords | 489 | Q13 |
| Varargs | 43 scalar + 4 table overloads | Q15 |
| Scalar arity | 0-arg 48 · 1-arg 687 · 2-arg 629 · 3-arg 74 · 4-arg 15 · 5-arg 2 · 6-arg 2 · 7-arg 1 | F20 |

**The 938 is a floor, not a ceiling.** 21 of 26 extensions are not installed — `spatial`, `fts`,
`inet`, `excel`, `vss`, `iceberg`, `delta` and the scanners each add functions on load. The 8
`st_*` names visible today (F13) are stubs, not the ~300-function spatial surface. **"Every DuckDB
function" is not even a fixed target**; it is a function of which extensions are installed, which
is a per-deployment decision legend-lite does not control.

---

## 3. What legend-lite covers today

### 3.1 The two vocabularies — and only one of them is `SqlFn`

| Vocabulary | Size | Typed? | Dialect-mapped? | javac-exhaustive? | Source `[src]` |
|---|---|---|---|---|---|
| `SqlFn` (scalar) | **164** constants | enum | yes (`Spellings` + coded arms) | **yes** | `sql/SqlFn.java` |
| `SqlAgg.Reducer` | 20 real SQL names + 2 sentinels | **raw `String`** | **no** | **no** | `lowering/Aggregates.java` |
| `Windows.WindowFn` | 13 window fns + 2 window aggregates | **raw `String sqlName`** | **no** | **no** | `lowering/Windows.java` |

`SqlFn`'s 164 split by rendering mechanism `[src]`:

| Mechanism | Count | Where |
|---|---|---|
| Pure spelling row — data | **77** | `dialect/Spellings.java` (`m.put` count) |
| Infix operator | **11** | `AnsiSqlRenderer.INFIX` |
| Coded arm (shape logic, composite expansion, dialect hook) | **76** | `AnsiSqlRenderer.call` + `DuckDb` overrides |

> **Correction to `H2_BACKEND.md` §2**, which records 163 `SqlFn` constants / 75 coded arms. Measured
> today: **164 / 76**. One constant has been added since that doc was written.

### 3.2 Coverage, measured by name

Every DuckDB function name legend-lite can emit was extracted from `Spellings.java` (77), the coded
arms of `AnsiSqlRenderer.java` and `DuckDb.java`, `Aggregates.java` and `Windows.java` — **164
candidate names** — and each was looked up in `duckdb_functions()`.

| | Count | Query |
|---|---|---|
| Emitted names that **are** real catalog functions | **163** | G6 |
| Emitted names **not** in the catalog | **1** — `coalesce`, which DuckDB implements at the parser, not as a catalog function | G6 |
| …by catalog `function_type` | `scalar` 112 · `aggregate` 34 · `macro` 13 · `table` 2 · dual-typed 2 | G6 |

**163 of 938 distinct catalog names = 17.4%.**

### 3.3 The Pure-side surface — the other denominator

| | Count | Source `[src]` |
|---|---|---|
| `native function` declarations (overloads) | **684** | `builtin/Pure.java` |
| Distinct Pure native **names** | **388** | `builtin/Pure.java` |
| …by package | math 110 · date 103 · relation 94 · collection 82 · string 62 · boolean 62 · lang 12 · flow 6 · variant 5 · asserts 5 · multiplicity 4 · meta 4 · io 4 · hash 2 · runtime 1 | `builtin/Pure.java` |
| Scalar lowering rules | **127** `RULES.put` entries | `lowering/Scalars.java` |
| Pure→SQL aggregate mappings | **29** (onto 20 SQL names + 2 sentinels) | `lowering/Aggregates.java` |

**The two surfaces are not in a 1:1 relationship and should never be forced into one.** 388 Pure
names compress onto 164 `SqlFn` constants because `SqlFn` is *semantic* — `EXTRACT` carries the
part name as a literal argument and covers `year`/`month`/`dayOfWeek`/… as one constant. That
compression is the whole value of the vocabulary and is precisely what a generated 1:1 registry
would destroy.

### 3.4 The path a user has today, and the one they do not

| Path | Exists? | Evidence `[src]` |
|---|---|---|
| Call a Pure native that maps to a `SqlFn` | yes | `Scalars.RULES` (127) |
| Call a Pure native with no lowering rule | **no — loud** | `Scalars.java:46` "An unregistered overload is a loud error naming the signature"; `:64` `throw new IllegalStateException("no catalog overloads for '" + pureName + "'")` |
| Reference an unknown function at all | **no — loud** | `FunctionCompiler.java:158` `site + " binds to unknown function '" + fqn + "'"` |
| Call an arbitrary DuckDB function from a query expression | **no** | no `RawFn`/`RawCall` variant in `SqlExpr`'s `permits` clause (`sql/SqlExpr.java:12-23`) |
| Run arbitrary SQL text | only at the **setup/statement** boundary | `executeInDb` K-native; `RawSql.splitStatements` splits a blob into statements; `exec/RawSqlBoundary` adapts statement TEXT per dialect. **Neither is expression-level.** |

> **`RawSql.java` is not the escape hatch the question presumes.** It is a 65-line, string-aware
> statement splitter for the `executeInDb` corpus boundary. It has no bearing on expression
> rendering.

**The no-fallback tenet is currently held on the scalar path, and only there.**

---

## 4. The design space

### 4.1 (a) Widen `SqlFn` — **reject**

`SqlFn`'s class comment states the constraint: *"every dialect renders ALL of them (an exhaustive
switch, enforced by javac — adding a semantic function without a spelling in every dialect is a
compile error)."* That is the guarantee. It is also the cost function.

| | Today | After adding the documented DuckDB surface |
|---|---|---|
| `SqlFn` constants | 164 | ~459 relevant (J1) → **~600 after merge, +~295** |
| Concrete render surfaces `[src]` | 4 (`AnsiSqlRenderer`, `DuckDb`, `EngineStyleH2`, `EngineStyleDB2`) | 4 |
| Planned backends | H2, SQLite, Postgres, MariaDB, Snowflake, Databricks | same 6 |
| **New render arms owed** | — | **295 × 6 = 1,770** |

And the H2 evidence says almost all of them would be `throw`. `H2_BACKEND.md` §2's measured
capability map over today's `SqlFn`:

| Bucket | `SqlFn` count | Share |
|---|---|---|
| A native | 74 | 45% |
| C rendering override | 46 | 28% |
| D **structurally impossible** | **43** | **26%** |

A 26% impossibility rate on a vocabulary that was *designed to be portable*. The DuckDB extension
tier would be selected on the opposite criterion — *DuckDB has this function* — so its D-rate on H2
trends to 100%. Each of those 1,770 arms is a hand-written `throw` in a switch a human must
maintain. **This converts javac's exhaustiveness guarantee from an asset into a tax**, and it is the
single change most likely to stop the N-backend program.

### 4.2 (b) A `RawFn(name, args)` passthrough — **the mechanism already exists, and it is leaking**

`AnsiSqlRenderer.java:622-633`:

```java
protected String reducer(SqlAgg.Reducer r) {
    ...
    return r.fn() + "(" + (r.distinct() ? "DISTINCT " : "") + args + order + ")";
}
```

and `:621-622`:

```java
case SqlAgg.RankingFn r -> r.fn() + "(" + list(r.args()) + ")";
case SqlAgg.ValueFn v -> v.fn() + "(" + list(v.args()) + ")";
```

`SqlAgg.java:22, 38, 42` declare `Reducer(String fn, …)`, `RankingFn(String fn, …)`,
`ValueFn(String fn, …)`. `Windows.java:23` declares `record WindowFn(String sqlName, Kind kind)`.

**This is a verbatim `RawFn(name, args)`, in production, on the aggregate and window paths.**
AGENTS.md invariant 3a forbids it by name:

> *"No MIR record has a `String` field encoding a SQL operation. That means no `String name`, no
> `String funcName`, no `String op`, no `String sqlName`."*

Assessed against the tenets:

| Tenet | Verdict |
|---|---|
| javac exhaustiveness (invariant 3) | **not held** — a new aggregate name is a `Map.put`, not a compile error |
| One owner per behavior | **not held** — the *lowering* owns the SQL spelling, which invariant 2 forbids ("no SQL function names in the Lowerer") |
| Loud walls over wrong rows | **not held** — `QUANTILE_CONT` renders verbatim on any dialect that does not override `reducer()`. Only `EngineStyleH2` overrides it (`:881`, `:923`) `[src]`. There is no wall; there is a wrong function name |
| NO FALLBACKS | held in the narrow sense (nothing defaults), violated in spirit (nothing checks) |

**So the answer to "should we add a passthrough" is: one is already here, it is undeclared, and it
should be closed before a second is opened.** Adding `RawFn` to `SqlExpr` on the scalar side would
take the one path where the no-fallback tenet currently *does* hold and give it the aggregate path's
properties. A DuckDB-only `RawFn` that raises on other dialects is defensible **only** as the
bottom layer of §4.3's tier, where the non-portability is declared data rather than an accident of
which class overrode which method.

**There is a second, smaller instance of the same leak, and it is benign — note the difference.**
`ADD_INTERVAL` and `TIME_BUCKET` carry the interval-unit *function name* as a `StringLit` argument
(`lowering/DateShifts.java:63-66` produces `"to_years"`/`"to_months"`/`"to_weeks"`/`"to_days"`;
`AnsiSqlRenderer:453` renders it bare). `LIST_AGG` carries the aggregate name the same way
(`DuckDb.java:159`). These are **closed enumerations that every dialect re-maps** —
`EngineStyleH2:1030-1033` maps them to `YEAR`/`MONTH`/`WEEK`/`DAY`, `EngineStyleDB2:119-121` to
`YEARS`/`MONTHS`/`DAYS`. A closed set that each dialect translates is a part-name literal, which the
IR already sanctions for `EXTRACT`. An open set that each dialect passes through is a passthrough.
**The distinction to write into any registry contract is exactly that: closed-and-translated is
fine; open-and-verbatim is not.**

### 4.3 (c) Tiering + a declared-gap registry — **accept**

Two tiers, differing in what they promise, not in how they render:

| | **Core (portable)** | **Extension (DuckDB-only)** |
|---|---|---|
| Vocabulary | `SqlFn`, ~164 constants | a registry table, DuckDB-only |
| Growth rule | a constant is added **only** when ≥2 backends can render it | anything in `duckdb_functions()` |
| Rendering | exhaustive switch, javac-enforced | one generic arm reading the registry row |
| Cost to a new backend | 164 arms | **zero** — a new backend declares the whole tier unsupported in one line |
| Failure on another backend | compile error if unrendered | **loud runtime wall, pre-declared in the registry** |
| Portability claim | yes | **explicitly none, machine-readably** |

The mechanism to reuse is `H2_BACKEND.md` §9's declared-gap registry, copied from the engine's
`SqlDialect.expectedSqlDialectTestErrors` (H2 has 512 entries; 6,717 across all dialects). Its
semantics are what makes tiering honest rather than a skip list:

> A named error the manifest **expects** is a **passing** test. A named error whose **text changed**
> is a **failure**. An **unexpected pass** is *also* a failure.

Applied here: `duckdb_only_fn('list_cosine_similarity')` on Postgres is a **pass** if it raises the
registered wall, and a **failure** if it raises a different one *or if it silently succeeds*. That
last clause is what stops the tier from quietly becoming the fallback sink — the failure mode
`H2_BACKEND.md` §4.2 and §13 warn about twice.

**This satisfies "loud walls over wrong rows" and "one owner per behavior" in a way (b) alone does
not:** the owner of `list_cosine_similarity`'s non-portability is a registry row, not the absence of
a method override.

### 4.4 (d) Auto-generation from the catalog — **partial, and know the line**

| Generable from `duckdb_functions()` | Not generable |
|---|---|
| Function name (938 — Q2) | **Pure type mapping.** `BIGINT`→`Integer` vs `Number`, `DOUBLE`→`Float`, `DECIMAL`→`Decimal`; the catalog says `ANY` for 44 scalar overloads (Q14) and 41 return `ANY[]` |
| Arity, incl. varargs (F20, Q15) | **Null semantics.** Nothing in the catalog says `GREATEST` skips NULLs — H2_BACKEND.md §5.2 records this as a measured divergence |
| Parameter and return types (2,940 rows) | **Rounding contract.** `SqlFn.ROUND` is pinned HALF-EVEN by PCT; DuckDB's `round` is not, which is why `ROUND_EVEN` and `ROUND_HALF_UP` are separate constants (`SqlFn.java:42, 70`) |
| Description for 2,064 of 2,940 rows (Q6) | **Empty-collection semantics.** `exists([])=false`, `forAll([])=true` — `SqlFn.java:29-30` and `DuckDb.listPredicate` |
| Overload tables, and a **test corpus**: one arity/type-valid call per overload | **Index base.** `SUBSTRING`/`STRPOS` are 1-based in the IR and shifted at lowering (`SqlFn.java:46`) |
| Alias detection — **70 description-groups covering 172 names, i.e. 102 redundant aliases** (F11) | **Which of a pair is canonical.** The catalog says `argmax` and `arg_max` share a description; it does not say which to emit |

**The line:** the catalog generates the **signature**; a human writes the **contract**. Every one of
legend-lite's recorded function bugs — banker's rounding, `INT_DIVIDE` rendering `//`, `GREATEST`
null-skipping — lives on the contract side. A generator that produced 938 registry rows would
produce 938 rows with the contract field empty, and the empty field is the entire risk.

**What generation is unambiguously worth doing:** the **test corpus** and the **drift gate**. Both
are pure catalog data, and the second is the thing that actually decays — DuckDB renames and
deprecates between minor versions, and today nothing in the repo would notice.

### 4.5 (e) Is "every function" the right goal — no. See §5.

---

## 5. The filter — how many functions would a Pure user ever want?

Applied cumulatively to **distinct names**, each step with its query.

| # | Filter | Removes | Remaining | Query |
|---|---|---|---|---|
| L0 | all `duckdb_functions()` | — | **938** | Q2 |
| L1 | drop `table`, `table_macro`, `pragma` — file readers (`read_csv`, `read_parquet`) and introspection (28 `duckdb_*` names, F8); these are *sources* and *settings*, not expressions | 125 | **813** | F2 |
| L2 | drop `pg_catalog` — 48 Postgres wire-compat shims (`has_table_privilege`, `pg_get_viewdef`, `obj_description`, `pg_sleep`) | 48 | 765 | F3, F3b |
| L3 | drop `__`-prefixed internals — 19 compression codecs (`__internal_compress_string_hugeint`) | 19 | 746 | F4 |
| L4 | drop operator-symbol names — 30 (`+`, `-`, `~~`, `@>`, `<->`, `!__postfix`); already IR-level as `SqlFn.PLUS`/`TIMES`/… or unreachable from Pure syntax | 30 | 716 | F5 |
| L5 | drop undocumented ICU internals — **133 `icu_*` names, of which 133 carry no description** (i.e. all of them) | 133 | 583 | G1b, G1c |
| L6 | require a description — the catalog's own signal for "user-facing" | 124 | **459** | J1 / F17 |
| L7 | *(strict variant)* also require every parameter **and** the return type to be Pure-mappable | 188 | **271** | G3 + G4 |

**Take 459 as the answer, not 271.** L7 is too aggressive on one axis: it drops the entire
`list_`/`array_`/`map_`/`struct_` family (118 names — G5) because those signatures mention `ANY[]`
and `MAP`, and legend-lite **already implements 33 of the functions L7 discards** (J4) — the whole
list-lambda tier that `SqlFn.LIST_*` covers. A filter that excludes shipped functionality is
measuring the wrong thing. L7's value is as a *lower bound* on the trivially-typeable subset.

Symmetrically, 459 slightly *under*-counts: 33 names legend-lite legitimately emits are outside it
(J4), because DuckDB ships `row_number`, `rank`, `lag`, `lead`, `json_object` and `to_json` with an
empty description. **The honest target is ~490 names — barely half the headline 938.**

### 5.1 Coverage against the real target

| Denominator | legend-lite covers | % | Query |
|---|---|---|---|
| All 938 catalog names | 163 | 17.4% | G6 |
| **459 documented, user-facing scalar+aggregate (L6)** | **131** | **28.5%** | J2 |
| 271 strict-typed subset (L7) | 98 | 36.2% | H3 |

The 328 missing from L6 (J3), by family: **other 164 · list/array 3 · bit 5 · regexp 1** on the L7
subset (H6). Inspecting the missing list (H7) shows what is actually there: alias duplicates
(`argmax`/`argmin`/`datediff`/`datepart`/`datesub`/`lcase`/`instr`/`editdist3`), calendar-part
conveniences legend-lite already reaches through `EXTRACT` (`day`, `month`, `hour`, `minute`,
`dayofweek`, `isoyear`, `century`, `decade`, `millennium`, `era`), and genuinely new capability
(`damerau_levenshtein`, `jaccard`, `hamming`, `bar`, `hex`, `bin`, `gcd`, `lcm`, `isnan`, `isinf`,
`nfc_normalize`, `approx_quantile`, `entropy`, `kurtosis`, `mad`).

**That last group — call it 60–80 names — is the only part of the 938 that represents capability
legend-lite does not have in some form.** It is a burndown list, not an architecture problem.

---

## 6. Recommendation

**Do not implement every DuckDB function. Implement a generated *registry* of them, ship a small
declared extension tier, and spend the actual effort on the ~70 functions that are new capability.**

Sequenced, cheapest and most load-bearing first.

1. **Close the aggregate/window passthrough (§4.2).** Convert `SqlAgg.Reducer.fn`,
   `RankingFn.fn`, `ValueFn.fn` and `Windows.WindowFn.sqlName` from `String` to an enum
   (`SqlAggFn`, ~35 constants — 20 aggregates + 15 window fns, `[src]`), rendered through a
   `Spellings`-style data row per dialect. **This is a correctness fix independent of this
   project** and it is a prerequisite: it restores invariant 3a, gives H2/SQLite/Postgres a place to
   spell `QUANTILE_CONT`, and is the difference between "we chose a passthrough" and "we have one by
   accident." Small: 35 constants, one new switch, four dialects.
2. **Generate the catalog registry as build-time data, not as `SqlFn`.** A checked-in TSV/JSON of
   `duckdb_functions()` filtered to L6 (459 rows), regenerated by a test. Consumers: NLQ grounding,
   LSP completion, and (3).
3. **Add the drift gate.** A test asserting every DuckDB name legend-lite emits still exists in
   `duckdb_functions()` with a compatible arity. **This is available today and would already have
   found one thing** — `coalesce` is emitted but is not a catalog function (G6); benign, but nothing
   currently distinguishes benign from a rename.
4. **Adopt the declared-gap registry** (`H2_BACKEND.md` §9) — needed for H2 regardless, and it is
   the container the extension tier lives in. Do this *before* the tier, not after.
5. **Then, and only then, add the DuckDB-only extension tier** (§4.3): one `SqlExpr` variant reading
   a registry row, one generic render arm on `DuckDb`, one pre-declared wall everywhere else, and
   the registry entry as the single owner of the non-portability claim. Contract sentence to write
   into the seam, mirroring `H2_BACKEND.md` §8's: **an extension-tier call may name any function in
   the generated registry; it may never be the reason a *core* `SqlFn` was not added.**
6. **Burn down the ~70 genuinely-new functions** (§5.1) into `SqlFn` **on the two-backend rule** —
   a constant enters the portable core only when a second backend can render it. `damerau_levenshtein`
   and `jaccard` probably never qualify; `gcd`, `lcm`, `isnan`, `isinf`, `hex` qualify immediately.

**What this buys:** the answer to "can I call `list_cosine_similarity`?" becomes *yes, from the
declared DuckDB tier, and here is the registry row saying it will raise on Postgres* — without any
other backend owing a single new render arm.

---

## 7. What NOT to do

- **Don't widen `SqlFn` toward the catalog** (§4.1). 295 constants × 6 planned backends = 1,770 new
  render arms, and H2's measured 26% impossibility rate on the *portable* vocabulary says nearly all
  of them are `throw`s a human must hand-write. This is the change most likely to end the N-backend
  program.
- **Don't add a scalar `RawFn` before closing the aggregate one** (§4.2). Today exactly one of the
  two paths honours the no-fallback tenet. Opening a second passthrough while the first is
  undeclared converts an accident into a design.
- **Don't count overloads.** 2,940 vs 938 is a 3.1× inflation from DuckDB's type matrix; ten
  aggregate names alone are 916 overloads (F21). Any roadmap denominated in overloads is measuring
  DuckDB's internals.
- **Don't treat 938 as a fixed target.** 21 of 26 extensions are not installed (Q5); loading
  `spatial` or `fts` moves the number. Pin the registry to a stated extension set or the goal is
  undefined.
- **Don't auto-generate contracts** (§4.4). A generator emits 938 rows with the semantics field
  blank, and every recorded function bug in this repo — banker's rounding, `INT_DIVIDE` as `//`,
  `GREATEST` null-skipping — is a blank semantics field.
- **Don't let the extension tier absorb core work.** The engine's `PlatformRoutingStrategy` is the
  cautionary case named twice in `H2_BACKEND.md` (§4.2, §8): the escape hatch becomes the sink for
  everything the SQL layer could not do. The "unexpected pass is also a failure" rule (§4.3) is the
  specific guard — do not adopt the tier without it.
- **Don't use `internal` as a filter.** It is `true` for all 2,940 rows on a fresh database (Q3).
  It partitions nothing.

---

## Appendix — query ledger

All against `duckdb_jdbc-1.5.0.0`, `jdbc:duckdb:` in-memory, `SELECT version()` → `v1.5.0`.
Raw dump: `scratchpad/duckdb-functions.tsv` (2,940 rows).

| ID | Query |
|---|---|
| Q1 | `SELECT function_type, count(*), count(DISTINCT function_name) FROM duckdb_functions() GROUP BY 1` |
| Q2 | `SELECT count(*), count(DISTINCT function_name) FROM duckdb_functions()` |
| Q3 | `SELECT internal, function_type, count(*), count(DISTINCT function_name) FROM duckdb_functions() GROUP BY 1,2` |
| Q4 | `SELECT database_name, schema_name, count(*), count(DISTINCT function_name) FROM duckdb_functions() GROUP BY 1,2` |
| Q5 / Q5b | `SELECT extension_name, loaded, installed, install_mode FROM duckdb_extensions()` |
| Q6 | `SELECT (description IS NOT NULL AND description <> ''), count(*) FROM duckdb_functions() GROUP BY 1` |
| Q12 | `SELECT count(*) FROM duckdb_settings()` |
| Q13 | `SELECT count(*) FROM duckdb_keywords()` |
| Q14 | `SELECT return_type, count(*) FROM duckdb_functions() WHERE function_type='scalar' GROUP BY 1 ORDER BY 2 DESC` |
| Q15 | `SELECT function_type, varargs IS NOT NULL, count(*) FROM duckdb_functions() GROUP BY 1,2` |
| Q23 / F23 | `… GROUP BY function_name HAVING count(DISTINCT function_type) > 1` |
| F2 | `… WHERE function_type IN ('scalar','aggregate','macro')` |
| F3 / F3b | `… WHERE schema_name='pg_catalog'` |
| F4 | `… WHERE function_name LIKE '\_\_%' ESCAPE '\'` |
| F5 | `… WHERE NOT regexp_full_match(function_name, '^[a-z_][a-z0-9_]*$')` |
| F8 | `… WHERE function_name LIKE 'duckdb\_%' ESCAPE '\'` |
| F11 | description-groups with >1 distinct name (alias detection) |
| F13 | scalar names bucketed by `list_`/`array_`/`map_`/`struct_`/`union_`/`json`/`st_` prefix |
| F17 | scalar+aggregate, `main`, no `__`, identifier-named, described → scalar 387 / aggregate 72 |
| F20 | `SELECT len(parameter_types), count(*) … WHERE function_type='scalar' GROUP BY 1` |
| F21 / F22 | `SELECT function_name, count(*) … GROUP BY 1 ORDER BY 2 DESC` |
| G1b / G1c | `… WHERE function_name LIKE 'icu\_%' ESCAPE '\'`, split by description presence |
| G3 / G4 | F17 + every parameter type and the return type in the Pure-mappable set |
| G5 | scalar names in the `list_`/`array_`/`map_`/`struct_`/`union_`/`json` families |
| G6 | each of legend-lite's 164 emitted names looked up via `SELECT string_agg(DISTINCT function_type,'/') FROM duckdb_functions() WHERE lower(function_name)=?` |
| H1–H7 | set algebra: L7 candidate set ∩ / − legend-lite's emitted names |
| J1–J4 | set algebra: L6 candidate set (459) ∩ / − legend-lite's emitted names |

**`[src]` commands** (all over `/Users/neemsandv/legend/legend-lite`):

| Count | Command |
|---|---|
| `SqlFn` = 164 | `sed -n '/^public enum SqlFn/,/^}/p' core/…/sql/SqlFn.java \| grep -vE '^\s*(//\|/\*\|\*)' \| sed '1d;$d' \| tr ',' '\n' \| grep -E '^[A-Z][A-Z_0-9]*$' \| sort -u \| wc -l` |
| `Spellings` = 77 | `grep -oE 'm\.put\(SqlFn\.[A-Z_0-9]+, "[^"]+"\)' dialect/Spellings.java \| sort -u \| wc -l` |
| Pure overloads = 684 | `grep -c 'public static final NativeFunctionDefinition' builtin/Pure.java` |
| Pure names = 388 | `grep -oE 'native function [a-zA-Z:_]+::([a-zA-Z_][a-zA-Z_0-9]*)' builtin/Pure.java \| sed -E 's/.*:://' \| sort -u \| wc -l` |
| `Scalars.RULES` = 127 | `grep -c 'RULES.put' lowering/Scalars.java` |
| SQL aggregate names = 22 | `grep -oE 'family\("[A-Z_]+"' lowering/Aggregates.java \| sort -u \| wc -l` (incl. 2 sentinels) |
| Pure→SQL agg mappings = 29 | `grep -cE 'family\("[A-Z_]+", "[a-zA-Z]+"\)' lowering/Aggregates.java` |
