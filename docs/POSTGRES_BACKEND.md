# Embedded Postgres as a real backend — design

> **Question asked:** add Postgres as a real execution backend, on the same terms `H2_BACKEND.md`
> asked for H2, using an **embedded** Postgres so it runs in CI with no Docker and no external
> service. There is no golden-text half: the engine's corpus has no Postgres goldens, so **row
> equality is the only contract**.
>
> **Companions:** `H2_BACKEND.md` (the method), `SQLITE_BACKEND.md`, `MARIADB_BACKEND.md`,
> `BACKEND_PORTABILITY.md` (the cross-cutting architecture — read that for the carrier decision).

**Evidence standard.** The capability map was produced by **executing 2,400+ probe statements
against a real PostgreSQL 18.4 server**, every probe also run against DuckDB 1.5.0.0 as the reference
value, through one shared harness so the outputs are comparable cell-by-cell. **Value equality was
checked, not just parse success.** Where documentation and execution disagreed, execution won.

---

## 1. Verdict

**The strongest backend measured, and the cheapest to stand up. Adopt it as the second real backend
ahead of H2 if the goal is portability rather than golden-text compatibility.**

- **263 of 267 constructs are reachable — 98.5%**, against H2's measured 80% and SQLite's 89%.
  **Four D's total**, and one of those disappears under the JSON carrier.
- **Embedded Postgres is genuinely free of infrastructure.** `io.zonky.test:embedded-postgres:2.2.2`
  with `embedded-postgres-binaries-darwin-arm64v8:18.4.0` boots a **real PostgreSQL 18.4 server in
  ~7 seconds**, including `initdb`, with **no Docker** — verified on this machine, which has no
  Docker daemon running and no local Postgres installed. §9.
- **H2's fatal wall does not exist here.** Postgres *invented* `LATERAL`. Every correlated form
  works, plus `JSON_TABLE` (Postgres 17+). §3.
- **The dangerous findings are not capability gaps — they are two functions that execute fine and
  silently delete rows or return the opposite answer.** §4 is the operative section.
- **Postgres's native `ARRAY` type is a trap, not an optimisation.** It mimics DuckDB lists exactly
  until you nest, then produces three distinct silent wrong answers. §5. This is the most
  counter-intuitive result in the whole study.

---

## 2. The capability map — 267 constructs

| Slice | A native | C rendering | B rewrite | D impossible | total |
|---|---:|---:|---:|---:|---:|
| Strings / regex / encoding | 25 | 4 | 10 | 2 | 41 |
| Math / bitwise / arithmetic / null | 48 | 7 | 5 | **0** | 60 |
| Temporal | 12 | 10 | 3 | **0** | 25 |
| Collections / maps / variant | 6 | 17 | 26 | 1 | 50 |
| Query shapes / types / aggregates | 64 | 18 | 8 | 1 | 91 |
| **Total** | **155** | **56** | **52** | **4** | **267** |
| | 58% | 21% | 19% | **1.5%** | |

**Reachable (A+C+B) = 263/267 = 98.5%** — and **264/267 (98.9%)** under the JSON carrier.

> **Denominator caveat.** 267 is this project's slicing, not H2_BACKEND.md's 256. The two partition
> the surface differently. Percentages are comparable in spirit; raw counts are not like-for-like.

**Zero D's in math and temporal.** Every aggregate and window function is reachable. Postgres has
every join kind natively.

---

## 3. Correlated explosion — every form works

| Form | Result |
|---|---|
| `FROM t, LATERAL unnest(t.arr) u(v)` | OK |
| `CROSS JOIN LATERAL` / `LEFT JOIN LATERAL … ON TRUE` | OK |
| implicit comma-LATERAL (keyword omitted) | OK |
| correlated TF inside a scalar subquery | OK |
| correlated `generate_series(1, t.n)` | OK |
| select-list `unnest`, including **parallel/ragged** (short arm NULL-padded) | OK |
| `JSON_TABLE(…)` (Postgres 17+) | OK |

`LEFT LATERAL` semantics verified on a real aggregated array column: inner join 3 rows, left join 6 —
empty-collection rows survive. So the whole `LIST_*` family, `UNNEST`, `VARIANT_ELEMENTS`,
`RANGE_FN`, `CROSS_LATERAL` and `LEFT_LATERAL` are reachable. **Nothing in the collections slice is a
D for H2's reason.**

---

## 4. The operative section — two silent wrong answers on the plainest paths

### 4.1 `regexp_matches` in a projection deletes every row

`regexp_matches` is **set-returning** on Postgres. Placed in a select list it acts as an implicit
filter:

```
                                    rows in emp   SELECT id, regexp_matches(name,'[0-9]') FROM emp
DuckDB                                    5                        5 rows
Postgres                                  5                        0 rows      <-- silently gone
```

No error. Five rows in, zero out. **Render `regexp_match` (singular) or a boolean `~` form; never
`regexp_matches` in a projection.**

### 4.2 `~` means the opposite thing

| | DuckDB | Postgres |
|---|---|---|
| `'abc123' ~ 'bc1'` | **false** — `~` is *full* match | **true** — `~` is *partial* match |

Both execute, both return a boolean, and they disagree. `REGEXP_FULL_MATCH` must render as an
anchored pattern on Postgres, not as `~`.

### 4.3 `ROUND_HALF_UP` silently becomes banker's rounding

`AnsiSqlRenderer.java:441-444` renders the two rounding SqlFns differently:

```java
case ROUND          -> roundHalfEven(a);   // coded composite — contract honoured by construction
case ROUND_HALF_UP  -> fn("ROUND", a);     // bare SQL ROUND
```

with the comment *"plain SQL ROUND (half away from zero) says exactly that."*
**That assumption is false on Postgres.** Measured:

| | `round(100.5)` | `round(2.5)` |
|---|---|---|
| DuckDB `double precision` | `101` | `3` — half away from zero |
| SQLite | `101` | `3` — half away from zero |
| **Postgres `double precision`** | **`100`** | **`2`** — **half-EVEN** |
| Postgres `numeric` | `101` | `3` — half away from zero |

So on Postgres the mode is chosen by the **argument's type**, and for a `DOUBLE` column
`ROUND_HALF_UP` delivers the exact opposite of its name. Pure's divide-with-scale is `BigDecimal
HALF_UP`, so this is a wrong-value defect, not a cosmetic one.

**Fix: render `round(CAST(x AS numeric)[, n])`** for `ROUND_HALF_UP`. That also repairs a second
defect — **`round(double, int)` does not exist on Postgres at all**, so 2-argument `ROUND_HALF_UP`
over a `DOUBLE` column is a hard failure today.

Note the trap in test design: a rounding test that checks only `2.5` on DuckDB confirms the *current*
behaviour and still misses this, because the divergence is a property of the backend and the
argument type, not of the tie value.

### 4.4 The rest of the silent set

| | DuckDB | Postgres |
|---|---|---|
| `7/2` | `3.5` | `3` (the renderer's forced-float form already neutralises this, but its `1.0` literal is `numeric` → returns `BigDecimal`) |
| `MOD`/`REM` over `DOUBLE` | works | **no `%` or `mod()` for `double precision` at all** — errors |
| `1 << 40` | `1099511627776` | **`256`** — shift counts masked mod operand width |
| `substr('abcdef',-3)` | `'def'` (negative wrap) | `'abcdef'` |
| `encode(…,'base64')` | plain | **line-wraps at 76 chars** |
| `levenshtein('héllo','hello')` | `2` (byte-based) | `1` (char-based) |
| `regexp_substr` no-match | `''` | `NULL` |
| `date_trunc('century', …)` | `2000` | `2001` |
| `date_part('second', …)` | `30.123456` | `30` |
| fractional-second rounding | truncates | **rounds** (`59.9999999` → next minute) |
| default NULL sort order | LAST/LAST | **LAST/FIRST** |
| identifiers | full | **silently truncated at 63 bytes** (collision verified) |

---

## 5. The `ARRAY` trap — the most counter-intuitive result

Postgres has a real array type, an identical `ARRAY[1,2,3]` literal, and the *same* `java.sql.Array`
read-back as DuckDB. It looks like a free win. It is free until `List<List<T>>`:

| | Result |
|---|---|
| `ARRAY[ARRAY[1,2],ARRAY[3]]` | **ERROR** — arrays are *rectangular*, not jagged |
| `array_agg(a)` over rows with different lengths | **ERROR** — `cannot accumulate arrays of different dimensionality` |
| `cardinality(ARRAY[ARRAY[1,2],ARRAY[3,4]])` | **4**, not 2 — `LIST_LENGTH` silently wrong |
| `arr[1]` on a 2-D array | **NULL** |
| `unnest` on a 2-D array | flattens **all** levels |

**Pure's `List<List<T>>` is unrepresentable in the native-array carrier.** Three of those five are
silent wrong answers, not errors.

If native arrays are used at all, the type system must be able to say "this collection is nested" and
fall back to `jsonb` — **two carriers on one backend**, strictly worse than one. The recommendation
is JSON as the declared carrier with native arrays permitted only as a flat, depth-1 optimisation.
See `BACKEND_PORTABILITY.md`.

*Free win, unrelated:* `ARRAY[…]` is accepted by **both** DuckDB and Postgres, while DuckDB's `[…]`
is a Postgres syntax error. A one-line renderer change makes array literals portable at zero cost.

---

## 6. Timezone — the corpus pin is insufficient

`H2_BACKEND.md` §7 records that the corpus pins `timeZone='GMT'` and every temporal expectation
depends on it. **On Postgres that pin does not do what it appears to.**

- The JVM here is `America/New_York`.
- pgjdbc returns `timestamptz` as a **zone-erased `java.sql.Timestamp` rendered in the JVM zone**.
- **`SET TIME ZONE 'GMT'` does not change this** — it only changes the server's *text* rendering.

> **The harness must pin `TZ=UTC` at the JVM level, or render `AT TIME ZONE 'UTC'` explicitly.**
> Setting the session GUC alone is a false sense of safety, and every temporal row-equality
> comparison rides on it.

Also measured: pgjdbc returns **`Date:0000-01-01` for `DATE '10000-01-01'`** — years beyond 9999 do
not round-trip.

---

## 7. Where Postgres actually stops — all four D's

| | Gap | Note |
|---|---|---|
| **P1** | 2 string D's | the residue of the regex/encoding slice |
| **P2** | `List<List<T>>` in the native-array carrier | **→ 0 under the JSON carrier** (§5) |
| **P3** | 1 shape D | composite/wide-numeric residue |
| **P4** | `IGNORE NULLS`, aggregate `ORDER BY` inside `OVER`, `DISTINCT` in a windowed aggregate | refused identically by SQLite — the only D-class window rows on either |

`QUALIFY` is absent; `QualifyToSubselect` already closes it.

**Interval frames work but only in the singular quoted form:** `INTERVAL '1' DAY` ✔, `'1' DAYS` ✘,
`1 DAY` ✘. **The renderer emits `DurationUnit` plurals today — that is a live C row.** Postgres also
refuses `RANGE n PRECEDING` on a date column; rewrite to epoch days.

`date_diff` needs care: the obvious `age()` construction is **wrong on 9 of 15 edge cases**
(`month` from 2020-01-31 to 2020-02-01 gives 0; DuckDB gives 1). Boundary-count constructions are
exact on 15/15. The trap is `week` — DuckDB's is plain `(b−a)/7`, **not** a Monday-aligned count.

---

## 8. `SqlDialect.normalize` — four families, three non-obvious

| `SqlType` | JDBC returns | canonical |
|---|---|---|
| `DATE` | **`java.sql.Date`** — not `LocalDate` | date |
| `JSON` | **`PGobject`** | JSON |
| `TIMESTAMPTZ` | `Timestamp`, **offset dropped** (§6) | instant |
| `SUM(BIGINT)` | **`BigDecimal`** (DuckDB: `BigInteger`) | integer |

That last row is the `normalize` problem in miniature: **one semantic function, three Java types
across three drivers, none of them wrong.** `H2_BACKEND.md` §8 predicted this is where the deferred
`ValueCodec` step materialises. It is.

*Cosmetic but real:* `json_build_object` inserts whitespace, so Postgres JSON envelopes are **not
byte-comparable** with DuckDB's even when semantically equal.

---

## 9. Standing it up — the dependency picture

```xml
<dependency><groupId>io.zonky.test</groupId>
            <artifactId>embedded-postgres</artifactId><version>2.2.2</version></dependency>
<dependency><groupId>io.zonky.test.postgres</groupId>
            <artifactId>embedded-postgres-binaries-darwin-arm64v8</artifactId><version>18.4.0</version></dependency>
<dependency><groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId><version>42.7.4</version></dependency>
```

Real server, real `initdb`, **~7s boot**, no Docker, no root, no external service. Per-platform binary
artifacts exist for linux/windows/macos — CI needs the matching classifier.

Unlike H2 (`H2_BACKEND.md` §11), there is **no incoherent existing dependency to untangle** — Postgres
is not currently on any classpath. It is a clean addition at test scope, promotable to compile scope
on the same rationale as duckdb and sqlite if the renderer ships.

---

## 10. Sequencing

1. **Fix `regexp_matches` and `~`** (§4.1, §4.2). These are wrong-row defects, independent of whether
   Postgres ever ships — they are latent in any dialect that reuses those spellings.
2. **Render `round(CAST(x AS numeric))`** (§4.3), and fix the 2-arg `DOUBLE` failure.
3. **Pin `TZ=UTC` in the harness** (§6) — before any temporal row-equality gate, or the gate lies.
4. **Author `Lexicon.POSTGRES` / `TypeNames.POSTGRES` / `Spellings.POSTGRES`.** The T3.2 seam exists;
   adding a row cannot break DuckDB.
5. **Render `ARRAY[…]` instead of `[…]`** — free, and correct on DuckDB too.
6. **Populate `SqlDialect.normalize`** for the four families (§8).
7. **Fix the `DurationUnit` plural** in interval frames (§7).
8. **Reconcile dialect and connection** (H2_BACKEND.md H5.4).
9. **The declared-gap registry** (H2_BACKEND.md §9) — only 4 D's to declare, the smallest of any
   backend.
10. **Portability sweep** `-Drcorpus.backend=postgres`.

---

## 11. What NOT to do

- **Don't use `regexp_matches` in a projection** — it deletes rows silently (§4.1).
- **Don't render `REGEXP_FULL_MATCH` as `~`** — inverted semantics (§4.2).
- **Don't adopt the native `ARRAY` type as the collection carrier** (§5). It is the most convincing
  wrong turn available on this backend.
- **Don't trust `SET TIME ZONE 'GMT'`** to make timestamps reproducible (§6).
- **Don't build `date_diff` on `age()`** — wrong on 9 of 15 edge cases (§7).
- **Don't assume byte-comparable JSON envelopes** — `json_build_object` adds whitespace (§8).
