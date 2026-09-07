# The referee — where Java still judges

`core/src/test/java/com/legend/harness/`: `H2Verify` 1,256 · `ReplayOracle` 961 ·
`PlanReplay` 406 · `H2ExtensionFunctions` 69 = **2,692 LOC**. SPI:
`core/src/main/java/com/legend/exec/SqlReplayOracle.java` 161.

**Verdict: the referee judges.** 1,059 of 2,692 lines (39%) are comparison policy, of which
~196 are dead. §6f item 2 ("the referee TRANSLATES only… no Java compares values") has not
started.

The design is right and worth protecting: run the engine's own golden SQL on a real H2
2.1.214 with `H2Settings` copied verbatim, seeded from the corpus's own recorded statements,
and hold it to our DuckDB rows. **DIVERGED always fails, whatever the text said**
(`SqlTextVerdicts.java:948, 1290`) — no path around that inversion was found.

---

## 1. Every Java value-comparison site

(a) irreducible translation · (b) reducible to a SQL predicate · (c) policy hiding in a comparator

| # | file:line | compares | class |
|---|---|---|---|
| 1 | `H2Verify:726-728` `Collections.sort(…); sortedTheirs.equals(mine)` | graph frame vs golden, as sorted string multisets | **(b)** two-way `EXCEPT ALL` |
| 2 | `H2Verify:867-869` same idiom in `goldenRowsCompare` | tabular/value frame vs golden | **(b)** |
| 3 | `H2Verify:1176-1255` `norm(Object)` | every cell, both sides, rendered to String | **(c)** |
| 4 | `H2Verify:1204` `d.round(new MathContext(10))` | any non-integral number | **(c)** tolerance |
| 5 | `H2Verify:1190` `stripTrailingZeros().scale() <= 0` | integral/float classification | **(c)** |
| 6 | `H2Verify:1240` `ldt.getNano() / 1000 * 1000` | timestamps floored to µs | **(c)** tolerance |
| 7 | `H2Verify:1251-1253` `s.replaceAll("\\.?0+$","")` | timestamp-shaped Strings | **(c)** — and broken, §3 |
| 8 | `H2Verify:1218-1224` `PureDateLiteral.toInstantFloor()` | our temporal carrier vs H2's | **(a)** but inside the comparator |
| 9 | `H2Verify:629-630, 796-797` `dec.getOrDefault(cell, cell)` | enum raw code → name, per cell | **(b)** `LEFT JOIN` to a `VALUES` relation |
| 10 | `H2Verify:692-704` `seenFull.add(fullRows.get(i))` | golden rows collapsed by full-row identity | **(b)** `SELECT DISTINCT` gated on `extentSubset` |
| 11 | `H2Verify:800-802` `if (valueFrame && n == 1 && allNull) continue;` | golden NULL-row drop | **(b)** `WHERE c1 IS NOT NULL` |
| 12 | `H2Verify:561-577` empty-frame branch | 0-instance verdict | **(b)** |
| 13 | `H2Verify:812-830` `theirsCols[0] != tab.columns().size()` | column arity | **(c)** → *unverifiable*, not diverged |
| 14 | `H2Verify:551-555`, `600-606` `labelSet.containsAll(keys)` | key-set vs alias-set | **(c)** one-directional |
| 15 | `H2Verify:585-590` `keys.remove("businessDate"/"processingDate")` | frame keys dropped pre-compare | **(c)** |
| 16 | `H2Verify:452-460` `bookkeepingAlias(label)` regex | golden columns excluded | **(a)**/(c) hybrid |
| 17 | `H2Verify:947-971` `orderedVerdict` | **DEAD** (§4) | **(c)** |
| 18 | `H2Verify:895-918` `sortKeyIndexes` incl. `equalsIgnoreCase` | **DEAD** | **(c)** |
| 19 | `H2Verify:1063-1081` `multisetCompare` | TDG fetch rows | **(b)** |
| 20 | `H2Verify:1119-1127, 1141` `nameOrder` + `toLowerCase()` | column identity, case-insensitively | **(c)** inconsistent with #14 |
| 21 | `H2Verify:998-1009` `diffRows` | reporting only | **(a)** |
| 22 | `H2Verify:1165-1174` `firstDiff` | reporting only | **(a)** |
| 23 | `H2Verify:301-314` `enumPrecheck` | our type vs decode availability | **(a)** decline rule |
| 24 | `H2Verify:334-374` `instantInSelectList` | golden SQL text scan | **(a)** decline, fires *before* compare |
| 25 | `H2Verify:419-429` `PAGINATED.matcher(goldenSql).find()` **after** `d != null` | **(c)** — divergence rescue, §3 |
| 26 | `ReplayOracle:164-167` `flat(sql).equals(flat(ourSql))` | two SQL texts | **(a)** determinism receipt |
| 27 | `ReplayOracle:478-489` `.contains("order by")` / `.contains("tdg_")` | text gates | **(a)** decline rules |
| 28 | `ReplayOracle:667-668` `startsWith("select distinct")` | finding a population golden | **(a)** |
| 29 | `PlanReplay:104-116` `spell(...)` → `String.valueOf` splice | value realization in Java | §5 |
| 30 | `SqlTextVerdicts:924, 1296` `golden.equals(ours)` as the verdict of record on decline | **(c)** |

**LOC accounting in `H2Verify`:** flags 143-196 (54) · `carrierList` 105-141 (37) ·
`coerceTemporal` 242-280 (39) · `enumPrecheck` 294-314 (21) · instant scan 316-374 (59) ·
`compareFrame`+`PAGINATED` 376-436 (61) · `bookkeepingAlias` 438-460 (23) ·
`goldenGraphCompare` 462-743 (**282**) · `goldenRowsCompare` 745-883 (**139**) · ordered path
885-971 (87) · divergence/head/diffRows 973-1009 (37) · multiset/transcript/nameOrder/
rawRows/firstDiff 1057-1174 (118) · `norm` 1176-1255 (80) = **1,037 of 1,256**.
Plus `ReplayOracle` ~22. **Total 1,059 / 2,692 = 39%.**

---

## 2. The relaxation table

| relaxation | file:line | sound or convenient | what breaks if deleted |
|---|---|---|---|
| Float rounded to **10 significant digits** | `H2Verify:1204` | **sound in kind, wildly oversized in degree** — ≈1e-10 relative ≈ 10⁵–10⁶ ULP, not the "two-ulp" the plan claims; applies to exact DECIMAL sums too | doc estimates ~21 calendarAggregation rows; unmeasured for DECIMAL |
| Integral compared exactly | `H2Verify:1190-1192` | **sound**, and a real past-bug fix (blanket rounding made two epoch-millis equal) | nothing — this is the correct arm |
| Timestamps floored to µs | `H2Verify:1240` | **sound on DuckDB** (µs storage vs H2 ns); **convenient on the H2 lane** where both sides are H2 nanos | DuckDB: ns-seeded goldens. H2: nothing legitimate |
| Trailing-zero strip | `H2Verify:1251-1253` | **convenient AND broken** — §3 | nothing; deleting removes a false-divergence source |
| Single-column all-NULL golden rows dropped | `H2Verify:800-802` | **sound** — one-directional, gated `n == 1`, engine's own asserts cited | value-frame goldens preserving an empty collection's root row |
| All-NULL drop **only when our frame is empty** | `H2Verify:561-577` vs `619-645` | **convenient and asymmetric** — 3 real + 1 null golden vs our 3 → DIVERGED; same golden vs 0 → MATCH | would change verdicts one way only; today it can fail a passing test |
| Golden columns dropped by name regex | `H2Verify:452-460` | **mostly sound**, but `from_z`/`thru_z` are *also real user columns* in milestoning fixtures | milestoning goldens fail on plumbing columns |
| Frame `businessDate`/`processingDate` removed | `H2Verify:585-590` | **convenient** — those values are never checked | graph goldens on temporal classes decline |
| **Golden-only data aliases dropped** | `H2Verify:604-606` | **convenient — worst hole in the graph compare.** If our fetch *omits a property the golden selects*, its column drops and the rest compares: a missing property scores MATCH | association stitch keys and physical-name twins would fail |
| Golden full-row duplicate collapse | `H2Verify:692-704`, retried `715-719`, `731-737` | **sound as argued** (Pure guarantees each instance once on an extent subset; gated on the compile-time `extentSubset` fact, which *is* armed) but it is a **second-chance retry after the strict compare failed** | the `testQualifierQueryWithOr` fan-out class |
| Column arity mismatch → unverifiable | `H2Verify:826-829` | **convenient** — the check is unconditional; wrong column count is never a divergence | the driver-PK/order-key class. A per-column name exclusion would be sound; a bare count is not |
| **Paginated golden: divergence → decline** | `H2Verify:419-429` | **convenient and structurally wrong** — fires only when `d != null`, i.e. only after the compare failed. Even `limit 1` over a total order is caught | `testPaginatedByVendor`, which is real. Fix: decline *before* comparing, or project the tie keys |
| datediff-to-now → decline | `H2Verify:394-415` | **sound**, well-argued, fires before the compare | five sqlstring goldens flap run to run |
| Column labels lowercased | `H2Verify:1141`, `1092` | **convenient and inconsistent** — graph compare is case-sensitive (`:551,600`), TDG is case-insensitive, `sortKeyIndexes:906` uses `equalsIgnoreCase`. **Three identifier policies in one class** | H2 `NAME` vs DuckDB `name` skew in the TDG lane |

---

## 3. Two verified defects in the comparator

**The trailing-zero strip is broken.** `H2Verify:1249-1253` guards on a timestamp shape and
then runs `s.replaceAll("\\.?0+$", "")`. Executed:

| input | output |
|---|---|
| `2015-08-26 00:00:00.0` | `2015-08-26 00:00:00` ← the intended case |
| `2015-08-26 00:00:00` | `2015-08-26 00:00:` ← **mangled** |
| `2015-08-26 00:00:10` | `2015-08-26 00:00:1` ← **mangled** |

The two forms it exists to equate now normalize to *different* strings. It creates false
divergences. Reachability is asymmetric (one side a String, the other a Timestamp).

**The paginated rescue reads the answer before deciding.** `H2Verify:419-429`:

```java
if (d != null && PAGINATED.matcher(goldenSql).find()) {
```

`d` is the computed divergence. So a row divergence that already happened is re-classified
as a DECLINE, and `SqlTextVerdicts:1296` turns a decline into a PASS on byte-equal text.

---

## 4. ~196 lines of dead comparison policy

`ORDERED_QUERY`, `SORT_KEYS` and `FORCED_MECHANISM` are **never `set()` anywhere in the
repo** — their setters were `EngineTestExecutor.java:1656-1657`, deleted by batch 115.

- `sortKeyIndexes` (34) · `ordFallback` (9) · `keyTuple` (10) · `orderedVerdict` (31) ·
  the flags (33) = **117 lines unreachable**.
- **Row order is never a contract.** Every golden, including every `order by` golden, is
  judged as a multiset. The javadoc at `:848-857` ("an ORDERED query's row order is
  CONTRACT — compare IN ORDER") describes behaviour the code no longer has.
- `ordFallback()` — the "counted residue" instrument — never fires, so that census is
  silently zero by construction.
- `carrierList` (37) and `coerceTemporal` (39) are dead too: declaration and javadoc only.
- `HarnessDisciplineTest.java:87-97` pins `H2Verify.java → 10` sort sites and justifies two
  of them by "gated on the COMPILE-TIME sort-key derivation (`EngineTestExecutor.sortKeyCols
  → SORT_KEYS`)" — a guardrail certifying a gate wired to a constant `false`.

---

## 5. Plan replay: translator or second engine?

**Mostly a translator, with a real bulge.** The line "values never leave the database" is
mostly held: a multi-column allocation is materialized as an oracle table
(`ReplayOracle:743-766`) and the hole filled with `select * from <table>`.

Where it crosses:
- `PlanReplay:99-116` realizes an allocation's rows **into Java strings** and splices them
  textually. `row.putIfAbsent(label, cell)` keeps the **first** row's value per column and
  discards the rest; `scalars` keeps only column 0. A multi-row allocation used as
  `${name.col}` binds row 0 with no decline.
- `PlanReplay:320-381` re-implements five engine freemarker helpers in Java
  (`collectionSize`, `renderCollection`, `varPlaceHolderToString`,
  `optionalVarPlaceHolderOperationSelector`, `GMTtoTZ`) plus `?replace` (`:188-205`).
  `gmtToZone` (`:391-405`) re-derives a date format from the string's shape and does a real
  timezone conversion in `java.time`. Irreducible-ish — these are template functions, each
  carries the engine's published body as a comment, and `default ->` throws a named decline
  (`:378`) rather than guessing.
- `PlanReplay:42-46` parses the plan with one regex (`sql = (.*?)(?=\s+connection\s*=)`,
  DOTALL). Golden SQL containing the literal ` connection =` truncates silently. **Unverified**
  whether that occurs.

**Functional bug:** `verifyPlan0` materializes allocation tables through
`ReplayOracle.execute` (`:922-928`) → `onOracle:294-298`, which opens
`jdbc:h2:mem:advisory<N>` **without `DB_CLOSE_DELAY`** inside a try-with-resources. The
database dies when the block exits, so the allocation table is created in one throwaway DB
and read in another. **Plan replay only works while the family mirror is live**; off it
(fresh-replay path, or `mirrorSuspend(true)` for a CSV-seeding test) every plan golden
declines with a missing table.

---

## 6. The mirror

Seeded per package session (`MinimalCorpus:313-318`) with the engine's own `H2Settings`,
extension aliases installed (`ReplayOracle:75-82`), then the session's recorded raw
statement ledger replayed incrementally (`applyPendingSeeds:329-347`) with poison-on-failure.
The ledger records the **H2 spelling** while `adaptRaw`/`h2ToDuckDb` translates what DuckDB
executes — both databases receive the same corpus statement in their own dialect. Right shape.

**Risks, both directions:**

1. **Seed-cursor desync — can fail a passing test.** `MinimalCorpus:438-441` builds each
   test's recording as a copy of `seedLedger`; `:463-467` rebuilds it **filtering out
   query-kind statements**. `applyPendingSeeds` (`ReplayOracle:332`) is a bare index cursor
   (`while (mirror.applied < ledger.size())`) into a list whose length changes between tests.
   Every recorded `executeInDb('select …')` in the applied prefix shifts the list left by one,
   so the next call **skips exactly one seed**. 83 corpus files use `executeInDb`. Found by
   reading; not reproduced.
2. **Non-deterministic seeds.** `RawSqlBoundary:142-143` exists because corpus seeds spell
   `CURRENT_TIMESTAMP()`. The SUT evaluates at seed time, the mirror at verify time.
   `instantInSelectList` guards *goldens*; nothing guards *seeds*. **Unverified** how many.
3. **Missing extension functions.** The mirror registers **4** aliases
   (`H2ExtensionFunctions:62-63`); the engine registers **15**. Missing: `json_navigate`,
   `json_parse`, `hash_sha1`, `hash_sha256`, `flatten_array`, `split_part`, `edit_distance`,
   `jaro_winkler_similarity`, `convertTimeZone`, `lpad`, `rpad`. Corpus goldens reference
   **ten of the eleven**. Those can never row-verify; they decline to text.
4. **The dangerous direction is a mirror that cannot answer** — every such case funnels to
   DECLINED then `textEqual ? ok()`. An under-seeded mirror systematically converts row
   verdicts into text verdicts. The decline census makes it visible; nothing gates on it.

Done well: the poison discipline, "extras never advance the cursor" (`:376-390`), the
mark/rollback/truncate + mirror-detach protocol (`:234-247`, because "H2 cannot roll back"),
the ancestor-temp stage swap for self-join chains (`:562-575`).

---

## 7. Distance to "the database judges"

Most of the destination is already written: `docs/parked/InDbVerdict.java` (300 lines) does
the transfer-and-`EXCEPT ALL` correctly.

| class | SQL formulation | blocker |
|---|---|---|
| Row multiset equality (#1,2,19) | `count(*)` over `(golden EXCEPT ALL ours) UNION ALL (ours EXCEPT ALL golden)` — parked `InDbVerdict:93-96` | our side is `ExecutionResult.Tabular` (rows already in Java); needs the `prepareTyped`/`renderValue` split so the arm hands the oracle **our SQL** |
| Golden rows → a relation | `CREATE TABLE __golden_N(...)` typed from H2 metadata, filled by `DuckDBAppender` | none |
| Enum decode (#9) | `LEFT JOIN (VALUES …) d(code,name) ON g.cI = d.code` | none |
| Value-frame NULL drop (#11,12) | `WHERE NOT (c1 IS NULL)` both sides | none |
| pk-collapse (#10) | `SELECT DISTINCT` gated on `extentSubset` | none once the golden is a relation |
| Bookkeeping-alias drop (#16) | explicit projection `SELECT c1,c3,c5 FROM __golden_N` | none; the label→role decision is legitimately translation |
| Column arity (#13) | `SELECT * FROM (ours) LIMIT 0` metadata read | none. **Should become a divergence** for non-harness columns, not a decline |
| Timestamp µs floor (#6) | free on DuckDB (appender truncates); on H2 `CAST(x AS TIMESTAMP(6))` both sides | none — but must be *named*, not implicit |
| **Float tolerance (#4)** | `EXCEPT ALL` is exact. To keep a tolerance: full outer join on a rounded key, or normalize both sides (`printf('%.10e', x)`) | **the real decision.** The plan says the "two-ulp tolerance goes"; the actual tolerance is `MathContext(10)` ≈ 10⁵–10⁶ ULP. **Measure the flip list before deleting** |
| Ordered compare (#17,18) | `row_number() OVER ()` joined positionally | dead today; needs the golden's `ORDER BY` to survive subquery nesting **and** the sort-key derivation that died with `EngineTestExecutor` |
| Graph/JSON (#14,15, the 282-line `goldenGraphCompare`) | our JSON as a DB value, `unnest(from_json(...))` to the golden's row shape, same `EXCEPT ALL` | our graph result arrives as a Java `String`. **The one-directional key rule must become an explicit column list first**, or the hole at `:604-606` moves with it |
| TDG fetch-text (#19) | both sides as relations with a name-ordered explicit column list | ours on DuckDB, golden on H2 — same transfer needed. **Not in the design doc's scope** |
| Text-equality fallback (#30) | not a SQL question | `DECLINED → textEqual ? ok()` is the last place a Java `String.equals` decides a test. Gate it: a decline whose bucket is a **referee fault** should hard-FAIL |

---

## 8. Referee bugs that could mis-score, ranked

1. **Mirror seed-cursor desync** (§6.1) — passing test → FAIL or decline.
2. **`catch (RuntimeException) → declined`** at `ReplayOracle:209-214, 871-876, 907-912` —
   any referee crash becomes a text verdict, indistinguishable from a legitimate decline.
3. **Paginated post-hoc rescue** (§3) — real divergence → pass, evidence discarded.
4. **`norm` trailing-zero strip** (§3) — false divergence.
5. **Plan replay dies off the mirror** (§5) — silent decline of every plan golden on a
   private-session test; also O(full ledger) per allocation node.
6. **Graph compare drops golden-only columns unconditionally** (`:604-606`) — a missing
   property scores MATCH.
7. **Null-row asymmetry** (`:561-577` vs `619-645`).
8. **Three identifier policies in one class** (`:551/600` exact, `:1141/1092` lowercased,
   `:906` `equalsIgnoreCase`).
9. **Census double counting** — the 7-arg `verify` (`:603-613`) delegates to the 8-arg
   (`:635-646`), so one call increments both `verify X` and `verify8 X`; `verifyFetchChain0:150`
   likewise. The published outcome table reads a census with known double-counting arms.
10. `bucketOf` truncates to 70 chars (`:233`) — distinct declines merge into one bucket.
11. `LAST_DECLINE` write-only, never removed (`:201, 212`); its javadoc describes a reader
    that does not exist.
12. `tempSeeds` (`:788-819`) emits `CREATE LOCAL TEMPORARY TABLE` with a `DROP IF EXISTS`
    prefix but **no drop afterwards**; tables outlive the test on the family mirror.
13. `tempSeeds` `default -> "VARCHAR(1024)"` (`:799`) — unrecognized literal kind silently
    typed as string; `"integer"` detected by `t.kind().endsWith("integer")` (`:682`).

---

## 9. Code quality

1. **God method:** `goldenGraphCompare` `:476-743`, 268 lines, one `try`, six responsibilities.
2. **Exceptions as control flow:** `Unverifiable extends RuntimeException` (`:42-46`) thrown
   from ~25 sites as a normal outcome; with the three blanket catches, a genuine defect and a
   policy decline arrive at the same place with the same shape.
3. **Dead code with live guardrails** (§4).
4. **Mutable statics, not thread-safe:** `ReplayOracle.MIRROR` (non-volatile, `:65`),
   `ATTEMPT_GOLDENS` (`HashMap`, `:127`), `ATTEMPT_SQL_GOLDENS` (`ArrayList`, `:702`),
   `COUNTER`. Six ThreadLocals in `H2Verify`, three of them dead.
5. **Stringly-typed dispatch:** `t.kind().startsWith("population")` (`:664`),
   `.endsWith("integer")` (`:682`), `switch (t.kind())` (`:795`, swallowing `default ->`),
   `fn` string switch (`PlanReplay:321`), decline-reason prefixes as census keys.
6. **Wrapper-pair naming:** `verify`/`verify0`/`verify1`, `verifyPlan`/`verifyPlan0`,
   `verifyFetchTexts`/`verifyFetchTexts0`, `verifyFetchChain`/`verifyFetchChain0` — eight
   methods where four plus an aspect would do, and the pairing is what produced §8.9.
7. **Silent `catch (…) { }`:** `ReplayOracle:581, 587, 778`; `MinimalCorpus:331, 471`.
8. **Extraction artifacts:** `goldenRowsCompare` body indented 16 spaces (`:752-883`);
   `int[] theirsCols = {0}` (`:753`) a mutable box that never crosses a lambda; orphaned
   javadoc at `:105-107`, `:920-921`.
9. **Comments outweigh code and have drifted** — several blocks describe `EngineTestExecutor`
   (`:150-155, 176-178, 611-612, 848-857`).

---

## 10. What DONE means for the referee

In dependency order.

1. **Delete the dead first** — `ORDERED_QUERY`, `SORT_KEYS`, `FORCED_MECHANISM`,
   `sortKeyIndexes`, `orderedVerdict`, `keyTuple`, `ordFallback`, `carrierList`,
   `coerceTemporal`, `LAST_DECLINE`, `M1_*`, `GOLDEN_NANOS`. Check: `grep -c` = 1 each;
   `HarnessDisciplineTest`'s `H2Verify` count 10 → 6 and its comments no longer name
   `EngineTestExecutor`.
2. **Fix the four scoring bugs** with regression tests: seed-cursor desync, `norm` strip,
   plan-replay ephemerality, graph null-row asymmetry.
3. **Measure the float tolerance before deleting it.** Run the lane with `MathContext(10)` →
   exact and record the flip list, each classified engine-defect / our-defect / accepted.
   Until this exists, "the two-ulp tolerance goes" is not a plan.
4. **Turn rescues into pre-compare rules or divergences.** `PAGINATED` declines *before*
   comparing; arity mismatch names the harness-added columns and fails on anything else;
   `golden-stitch-keys-dropped` becomes an explicit per-column exclusion. Check: no code path
   reads a computed divergence and returns a non-failure.
5. **Referee faults FAIL, not decline.** Split DECLINED into a modeled gap and a FAULT
   (SQLException on our own seeding, `RuntimeException`, missing extension function). FAULT
   never falls back to text. Check: zero `catch (RuntimeException)` in the referee.
6. **Register all 15 engine extension functions**, semantics verbatim (including the engine's
   platform-charset `base64_decode`). Check: a test asserting the alias set equals the
   engine's `getLegendH2ExtensionSQLs`.
7. **Fix the census double counting** so the outcome table can be the acceptance instrument.
8. **Then item 4**: land `InDbVerdict` behind `verifyInDb`; delete `goldenRowsCompare`,
   `norm`, `multisetCompare`, `rawRows`, `transcriptRows`, `nameOrder`, `divergence`,
   `diffRows`, `firstDiff`. Check: `H2Verify` ≤ ~350 LOC; the only Java comparisons left are
   the decode relation, the decline text rules, and the fetch-transcript receipt.
9. **Then 6b**: graph verdict in the database, key-drop as an explicit projection.
10. **Then the TDG lane**, which the design doc does not cover — a third comparison
    implementation with a fourth identifier policy.
11. **Final gated invariant:** in `com.legend.harness`, zero calls to `equals`, `compareTo`,
    `Collections.sort`, `List.sort` or `Arrays.sort` on anything derived from a database
    value. `HarnessDisciplineTest`'s allowed count for `H2Verify.java` is **0**.
