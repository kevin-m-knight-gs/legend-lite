# Adversarial audit — testing the claim "legend-lite is 100% Java-orchestrates / DB-executes"

> **Remediation status (2026-08-18, adopted onto main the same day):** verdict ACCEPTED;
> the honest restatement now stands in `AGENTS.md`, the eval ledger, and Charter Clause 3.
> Batch A landed: Tier 1 (B, D, E, F, N fixed; G adjudicated — the Integer→Float widening
> was DEAD compensation, deleted against a green 1110; the Decimal arm is an exactness
> RELABEL for the reference's decimal-exact Floats, reinstated with the argument in place),
> Tier 3 in full, Tier 2 items 6/7/10 (`JdbcSurfaceCensusTest`, `GuardCoverage` floors in
> every walker, `SqlTextRatchetTest`, `CorpusSoftCeilingTest`, TenetRatchet precondition +
> accessors, PctDiscipline `.pure` + adapter pin). Item 9's remaining regex list and §7's
> re-seed are deliberately deferred: guards catch drift, not adversaries. Tier 4 is
> REPLACED by the relation-typed `fetchDb` leg — GridReads/HostEval/`HostResultSet` are
> DELETED wholesale rather than taught MIR (Batch B).
>
> **Tier-1 remediation audit (2026-08-18, user-directed):** each fix re-verified
> adversarially, with regression pins that FAIL on the old code (`AuditTier1PipelineTest`,
> `DynamicPivotKeyLiteralTest`, `PureReprTest` — the full-corpus byte-match proves only
> non-regression; none of the fixed behaviors had prior coverage, which is why they were
> live).
> **B** — pipeline-pinned: a NULL grid cell through `->toString()` is EMPTY, never the text
> `"null"`; control chain still reads. Audit found two arms (ROWS, CELLS-stream) that
> silently IGNORED the peel — both now fall through to the wall instead.
> **D** — REAL keys pin to the float's printed value (the old widen spliced
> `3.140000104904175`); timestamp keys keep `:00` seconds and exact subseconds; the
> `DateLit` arm audited safe (`LocalDate.toString` is always ISO).
> **E** — the correct spec is the engine's `toRepresentation()` (platform pure), NOT this
> report's suggested `lit()` — `lit()` is the SQL speller and doubles quotes where Pure
> source backslash-escapes. Ported with the spec's replace order; NULL pk cells wall.
> **F** — the wall fires on a `[1..*]` shrink and a satisfied bound flows (pipeline pins).
> Residuals recorded: the SCALAR zero-row path is pre-existing behavior outside F's scope;
> and integer egress is driver-kinded (`Integer` vs `Long` by path — adjudicated looseness,
> all consumers compare numerically) — a candidate row for the carrier lane, noted so it
> cannot vanish into a passing test.
> **N** — no dedicated unit pin (a walk needs a full plan model); verified by the
> executionPlan family byte-match plus the read that `info().type()` is the element type by
> construction. Recorded as reasoning-plus-referee, not test-pinned.
> **G** — the interpreted `ToFloat` re-wraps the Decimal's exact `BigDecimal` (verified in
> legend-pure source): the relabel preserves the value. Suite totals confirmed; the commit
> touched no expected-failure registry, so the green deletion of the Integer branch is
> genuine.

> **Method.** Eight auditors, null hypothesis = *the claim is false*, rubric = `TENET_REMEDIATION.md`
> §6, adjudication authority = `docs/TENET_CHARTER.md`. One auditor was permitted to build and
> **ran 15 deliberate-violation probes against the guards**, reverting each. Tree verified clean
> at report time (`git status --untracked-files=all` empty).
>
> Prior rounds: `TENET_AUDIT_2026_08_16.md`, `FOUNDATIONS_PHASE12_AUDIT.md`.

---

## 1. Verdict

**The claim is FALSE as stated, and TRUE in the direction that matters most.**

The interpreter really was deleted. The RENDER phase really is SQL. The oldest breach really is
closed. That work is excellent and this report should not be read as diminishing it (§6).

But "100%" fails on three independent grounds, each sufficient alone:

1. **Named, live counterexamples exist** — value computation over `ResultSet`-derived cells, in
   production, today (§2).
2. **The guards cannot detect the residue.** 10 of 15 deliberate violations landed **GREEN**,
   including a Java `MAX`+`SUM` over database values inside `com.legend.lowering` (§3).
3. **The claim's own stated mechanism does not exist.** `AGENTS.md` promotes the tenet to
   "an INVARIANT… enforced mechanically, not culturally" and names `GridReads.preResolve` as the
   seam. **`grep -rn preResolve` returns zero hits in source** — while a test file records
   *"preResolve died with the interpreter."* The repo contradicts itself in one grep (§5).

**The honest claim** — which is a genuinely strong one — is: *"No host interpreter remains; the
query compiler executes no values; the egress boundary is 10 irreducible carriage sites plus a
named, shrinking residue."* That is defensible and mostly measurable. "100%" is not.

---

## 2. The counterexamples, ranked

Every row read at HEAD. **None is counted by any ratchet, ledger, or census.**

| # | Site | What Java computes | Verdict |
|---|---|---|---|
| **A** | `exec/GridReads.java:128-135` | For a chain ending at `.rows` (`Row[*]`), returns **one column's scalar values** standing in for a list of rows. Justified in-comment by *"bare rows reach only EMPTINESS asserts in the corpus"* — correct only for the consumers the corpus happens to contain | **VIOLATION** (C2.3, C2.4) |
| **B** | `exec/GridReads.java:153-155` | `String.valueOf(v)` over a DB cell for `->toString()` chains. Java's repr rules, and `String.valueOf(null)` fabricates the text `"null"` — the catalog grids project literal `NULL AS "REMARKS"` | **VIOLATION** (C2.5, C2.4) |
| **C** | `exec/GridReads.java:101-116` | Java flattens the 2-D grid to a row-major 1-D cell stream. The comment says so. C2.3 names *unnesting* explicitly. Landed in the same window as `a1bdf972` "shapeRow explosion deleted" — deleted in `Executor`, reappeared here | **VIOLATION** (C2.3) |
| **D** | `exec/DynamicPivot.java:90-112` | Splices pivot keys back into the plan, choosing literal kind by the cell's **runtime Java class** and rendering temporals with `LocalDateTime.toString()`. `SqlExpr.FloatLit` is `record FloatLit(double)`, so `3.14f` widens to `3.140000104904175` — **the regenerated `IN` literal cannot match the source row and the pivot column silently disappears** | **VIOLATION** (C2.2, C2.5) |
| **E** | `testdatagen/TestDataGenerator.java:1388-1404` | Renders cells into generated Pure source: `v instanceof String ? "'" + str + "'" : String.valueOf(v)`. **No `'`-escaping** — the correct renderer `lit()` is 77 lines earlier in the same file and does `s.replace("'","''")`. Non-Strings take the **JDBC driver's** date/decimal spelling | **VIOLATION** (C2.5, C2.2) |
| **F** | `exec/Executor.java:263-274` | Filters NULL cells out of every COLLECTION result — the returned row count depends on a Java-side null test. `ResultShape:40-47` shows the project's own correct pattern: gate on a **compile-time schema fact**. This arm gates on nothing | **VIOLATION** (C2.3, Q4 "no exceptions") |
| **G** | `pct_adapter.pure:259-265` | **The deleted PCT overlay, alive on the larger channel.** `if($rawT == Float && $single->instanceOf(Integer), \| toFloat())` — comment: *"the declared Float wins."* Fixed channel = 348 tests; this one ≈ **761** | **COMPENSATION** |
| **H** | `harness/ObjectRefs.java:102-203` | Decodes a DB-produced `objectReference`, remaps positional `pk$_i` keys by `Integer.parseInt(k.substring(4))`, **re-emits JSON by hand**, binds it as a `CString`. Invisible to the funnel because it consumes a project-typed `ExecutionResult` | **VIOLATION** (C2.1, C2.5) |
| **I** | `harness/JsonAssertCanon.java:83-112` | Java sorts DB-produced cells. `664cc546` ("kill the comparator cross-kind collapses") **did not kill this sort — it replaced `String.valueOf` sorting with a typed comparator and kept it** | **VIOLATION** (C2.3) |
| **J** | `harness/AssertLoopForm.java:33-81` | Lifts `ResultSet` values into the AST as `CInteger`/`CFloat` literals, then recompiles them into SQL. Charter Clause 4 admits **`{String, Boolean}` only** and says *"there are no site-local admission rules"* | **VIOLATION** (Clause 4) |
| **K** | `harness/EngineTestExecutor.java:298` | `carriers = tds ? 1L : av.size()` — a size assertion answered by a constant. **298 corpus occurrences of `assertSize($x.values, N)`, 113 of them `, 1)`.** No unit test exercises the branch | **COMPENSATION** (C2.4) |
| **L** | `server/LegendHttpServer.java:162-170` | `Json.parse(wire.json())` → `toCompact` re-renders the DB's bytes: `BigDecimal("1e+30").toString()` = `"1E+30"`, DuckDB emits `1e+30`. Plus `rowCount` is a **Java aggregate** with a fabricated `: 0` | **BOUNDARY-with-defect** (C1.5), **VIOLATION** (rowCount) |
| **M** | `pct/.../ExecuteLegendLiteQuery.java:545-554` | Picks the Pure classifier (`DateTime`/`StrictDate`/`Date`) from the cell text's **precision** — while the declared type is being tested one line above | **VIOLATION** (C2.2 verbatim) |
| **N** | `StatementExecutor.java:1815-1826` | Types a result collection by element runtime classes, `default → STRING`. `allMatch` over an **empty** list is `true`, so an empty collection types BOOLEAN | **VIOLATION** (C2.4) |

Plus a live escaping bug (**E**), a live wrong-answer (**D**), and 6 unconditional null-drops in
the PCT bridge (`:250, :268, :317, :331, :581, :665`) that were **re-commented, not changed**.

---

## 3. The guards: 10 of 15 probes landed GREEN

This is the decisive section. Empirical, with the suite re-run after each probe
(baseline 56/56 green, restored 56/56 green, tree clean).

| Probe | Result |
|---|---|
| `rs.getString` in `lowering` | **RED** — funnel + ratchet both fired ✅ |
| **Carrier in `com.legend.values` (JDK-only, legal); `exec` reads `getNString`; `lowering` computes MAX+SUM** | **GREEN** |
| 5 accessors the ratchet omits (`getNString/getURL/getClob/getRowId/getCharacterStream`) | **GREEN** |
| `org.sqlite` import in `lowering` | RED — but only by the allowlist rule; **the funnel passed** |
| **`org.sqlite.core.NativeDB.column_text` in `resolver/` → Java MAX** | **GREEN** — a driver-native DB value read with zero `java.sql` |
| New class in `com.legend.exec` hashing a live cell | **GREEN** — `exec` has no class-list pin |
| **`nlq/src/main` computing a mean over DB rows** | **GREEN** — no guard walks `nlq/` |
| Sort+distinct over `rs.getString` in `core/src/test` | **GREEN** |
| `default -> null` / `default -> 0` | **GREEN** — the rule's regex is literally `Pattern.compile("default -> \"")` |
| Dead **recursive** private method | **GREEN** — `uses` counts the self-call |
| Reflection inside `com.legend.server.Json` | **GREEN** — pardoned wholesale by name regex, uncounted |
| `Collections.max` / `PriorityQueue` / `stream().min` in harness **and** pct | **GREEN** both |

### 3.1 The cheapest green violation

Delete the **15 unused imports** in `HostEval.java` (a change any reviewer waves through) and
spend the budget on:

```java
public static Object gridMax(List<List<Object>> rows, int col) { … }   // Java MAX over DB values
```

`List<List<Object>>` is exactly what `GridReads` produces. Result: **178/181 lines, ledger GREEN,
suite 56/56 GREEN** — a Q4 violation *inside the file the ledger exists to police*. A line-count
pin cannot distinguish deleting a comment from adding an aggregation.

### 3.2 Measured uncounted surface

**88,309 lines** of core test Java outside `harness/rcorpus`, plus **2,941 lines of `nlq`
product code**, 8,868 parser-equivalence, 1,709 pct. Inside that surface: **89 sites matching
`HarnessDisciplineTest`'s own regex** and **124 matching `TenetRatchetTest`'s own ACCESSOR
regex** — none counted by anything.

---

## 4. Compliance theater — nine instances

Code moved or renamed to satisfy a rule while the behaviour stayed.

1. **The PCT overlay** — deleted from the tabular channel (348 tests), alive on the scalar
   channel (~761). `PCT_BURNDOWN.md:287` records it as a *fix* that moved Standard 50→38 errors.
2. **The harness move** — 17 files `src/main → src/test` as **R100 (byte-identical)**. Goal was
   legitimate; effect is that **7,564 lines became invisible to seven guards** scoped to
   `src/main`. Proof it bit: **F1.8's dead-method ratchet, landed two commits later, cannot see
   the four dead methods the plan wrote it to catch.**
3. **The JDBC guard names five classes** — the JDBC moved to a sixth (`GridReads`), unnamed.
4. **`MissProbe.knownMiss`** is literally `return o.orElse(null)` — a rename plus javadoc.
   `MappingNormalizer:1507` now has `orElseThrow(...)` followed by `if (cd == null) return false;`
   — **dead code left from the pattern it replaced**, in the exact function round 2 named.
5. **`ObjectRefs`** — the protocol *constant* moved to `src/main`; the string surgery stayed.
6. **`DbMetaData.HostResultSet`** — a `com.legend.exec` record carrying DB values past a
   bytecode rule by construction. At the commit declaring eviction closed it fed the interpreter
   directly **and the guard passed**.
7. **`Executor.java:372`** matches `"org.duckdb.JsonNode"` by class-name string, documented as
   *"so the executor needs no driver import."* Compliance-avoidance written down as policy.
8. **`PctTdsWrap:202-204`** — `default -> "VARCHAR"`, the audited `default -> "String"` shape,
   relocated to where it bypasses `pureOfSqlType`'s loud wall.
9. **`PctDisciplineTest` walks `Path.of("src")` then filters `.endsWith(".java")`** — so
   `pct_adapter.pure` is **inside the walked tree and excluded one line later**. The one guard
   visiting that directory cannot see the file holding finding **G**.

**The generating cause is not dishonesty — it is that nobody re-checks guard coverage after
moving code.** Scope is a silent parameter: `Path.of("src/main/java")` was correct when written
and became wrong the moment 7,564 lines walked out of it.

---

## 5. Claims that are false as written

| Claim | Reality |
|---|---|
| `AGENTS.md:17-26` — the tenet is "enforced mechanically… grids pre-fetch at `GridReads.preResolve`, the one JDBC pass" | **`preResolve` does not exist.** `GridReads.tryLower` issues **five** JDBC statements (`:88,:111,:122,:145,:458`). Cited as live in 4 places incl. the guard's own failure message |
| `TENET_CHARTER.md:63` — Clause 3, "No `ResultSet`-derived value may reach `HostEval.eval()`" | `eval()` was deleted. **A dead reference reading as a live guarantee.** Its mapped guard tests arm shapes — which the clause's next sentence explicitly disclaims |
| `JavaEvalLedgerTest:20` — "the EVICTION is COMPLETE: every value… is DATABASE-PRODUCED" | Mechanism is **12 hard-coded paths and no `Files.walk`**. 5 of 6 name-rows pin **deleted method names at 0**; one pins the English phrase `"two many-valued TDS cells"` while the test strips comments — **it can never be non-zero** |
| §11 — "all five metrics at target" | **2 of 5 FALSE.** "Falsified self-claims → 0": **≥8 live**, most created by the closing sessions. "Duplicates → one owner": **2 of 7** — identifier quoting has **three mutually inconsistent rules** (`AnsiSqlRenderer.ident` doubles, `Ddl.processColumnName` **strips** — lossy, `GridReads.q` doubles) |
| `Executor.java:18-22` — "column Pure types come from typed outputs (never from JDBC metadata)" | `:537` reads `getColumnTypeName` and derives a Pure type from it, landing in `Column.pureType` |
| `FOUNDATIONS_PLAN.md:16` — "Converting non-passing rows to PASS: **STOP**" | Burn-down resumed 2026-08-17; the pause doc was never told. Net corpus **−15**; the largest move (−9) appears in no commit subject |

**Transactions were never added** — zero `setAutoCommit`/`commit()`/`rollback()` repo-wide.
`rawSqlFailureSink` was deleted (measured firing zero times), but **`emptinessUnverifiable`
survived at 18 occurrences**, gated on a *runtime* fact and uncounted.

**`maxSoft` never landed.** Three of four soft-pass columns have no ceiling. The sub-counts
247/293/27/613 are **byte-frozen across 16 sampled commits while PASS moved ±15**.

---

## 6. What genuinely holds — this is substantial

Verified by reading and grep, not by doc:

- **The interpreter is genuinely deleted.** `HostEval` 894 → **181 lines**, no `java.sql`, no
  `eval`, no value handling — only the routing predicate. Round 2's 47 arms (18 dual-use) are
  gone, **not relocated** (`git log --diff-filter=D/-A` confirms no successor file, no test root,
  no `.pure`). The price was paid in the open: corpus 2339→2330, 9 tests declined, all recorded.
- **The RENDER phase is exemplary.** `Render.java`: 755 lines, **zero `String.format`**, zero
  rendering format-strings, **137** `SqlExpr`/`SqlFn` constructions. Float repr →
  `Scalars.floatRepr` (SQL `CASE` over `CAST(… AS HUGEINT)`/`DECIMAL(38,18)`/`RTRIM`). Dates →
  typed `DateFmt` part lists. NULL → SQL `CASE`, with a comment explaining why coalesce would be
  wrong. The only `yyyy-MM-dd` literals are an **admission gate that throws**.
- **Round 1's worked calibration example is closed.** `hashString` is now
  `substr(sha256("c"),1,5)` tiled with `repeat`/`right`, **in SQL**; the CSV scrub is SQL too.
- **The C5.2 contract is true for the first time** — `RawSqlBoundary.h2ToDuckDb` has exactly
  **2 callers**, both corpus-authored, pinned by exact equality.
- **The harness CSV/TDS renderer is gone** — `csvText`/`csvCell`/`csvEquals`/`tdsStringEquals`
  all return zero hits; header pinning became an **exact pin**; the cross-kind numeric collapse
  genuinely died; `coerceTemporal` is scoped to one caller.
- **The query compiler executes no values.** Zero `ResultSet`, zero real `java.sql`, zero
  `HostEval` across `lowering/ resolver/ compiler/ normalizer/ builtin/ sql/ lineage/ plan/
  protocol/ parser/ model/`. `StaticFold` decayed as reported but **cannot reach data**.
- **The tenet ratchet is real** for the spellings it lists — 14/14, zero slack, and probe 1a
  flipped it red instantly with a full site list.
- **Invariant 6h (the lowering allowlist) is the strongest rule in the tree** — it caught two
  probes the funnel missed. **Allowlists beat blacklists, demonstrated twice.**
- `LiteralFold` matches Clause 4 word-for-word; `exec/Column`'s "no `.sqlType()` consumer" is now
  true repo-wide; `QueryService` post-processes nothing; `nlq` cannot execute; ASOR and the escape
  table are genuinely single-owner; the 13 F6.1 honest reds were verdicted atomically; the
  reflection pardon **shrank from three classes to two**; E2's row explosion really is dead.

---

## 7. The 14 accessor sites: only 10 are irreducible

The ratchet proves **non-growth**, not irreducibility. Reading all 14:

**Irreducible (10):** `Executor:171,194,222,282,428,474` (plan-rendered wire text, driver-carrier
normalization, the one general cell read, JDBC `Array` unwrap); `TestDataGenerator:1122` (appends
a `_csv_line` SQL built, ordered by SQL); `:1180` (count from an `EXCEPT` diff — the DB computed
the answer, Java enforces the contract); `DbMetaData:296`; `DynamicPivot:85`.

**Not irreducible (4):**
- `TestDataGenerator:1394` — finding **E**, an unfixed violation occupying a slot.
- `TestDataGenerator:663` — a `System.err.println` of `COUNT(*)` behind `LL_TMP_DEBUG`, firing an
  **extra DB round-trip just to print**. Deletable.
- `TestDataGenerator:1182` — the same `getLong(1)` read twice. One local variable.
- `Executor:433` — `ldt.getYear() < 1` decides the carrier by reading **magnitude**, which C2.2
  forbids by name. The same shape as the midnight heuristic deleted "by proof"; the sibling survived.

**Re-seed to 10** after fixing E, deleting the debug read, and hoisting the double-read — then
`Executor:433` becomes the single named residual rather than being lost in a round number.

---

## 8. The second invariant nobody measures: one SQL producer

Distinct from the tenet, and currently unguarded. AGENTS.md invariants 2/3/3a say there is **one**
path from HIR to SQL: Lowerer → typed MIR → `dialect.render()`.

`GridReads.java` contains **zero** references to `SqlSelect`, `SqlExpr`, `SqlQuery`, `SqlSource`,
`Lowerer`, or `dialect.render` — and five string-concatenation SQL sites. Tree-wide, production
code outside `sql/dialect/` builds SQL as text in **7 files, 33 sites**:

| file | sites |
|---|---:|
| `testdatagen/TestDataGenerator.java` | 17 |
| `exec/GridReads.java` | 5 |
| `exec/DbMetaData.java` | 4 |
| `exec/CsvSeed.java` | 3 |
| `plan/PlanText.java` | 2 |
| `plan/InProtocol.java`, `server/LegendHttpServer.java` | 1 each |

So there are **two SQL producers**: the compiler, and a string-building shadow path the dialect
cannot retarget, `SqlPostProcessors` cannot rewrite, and no backend swap reaches.

Target is **not zero** — DDL, `information_schema` catalog queries, and corpus-authored SQL have
no MIR representation. Target is *enumerated and argued*, which is what a ratchet is for.

---

## 9. Fix plan

**Tier 1 — live wrong answers (days)**
1. `GridReads.cast` → `SqlExpr.Cast` (finding B). One line; the chain is already being lowered.
2. `DynamicPivot` float widening + temporal rendering (D) — a wrong `IN` literal silently drops a
   pivot column.
3. `TestDataGenerator:1394` → use `lit()`, 77 lines up (E).
4. Delete `pct_adapter.pure:259-265` and fix the producer — emit `CAST(AVG(x) AS DOUBLE)` when the
   plan's type is Float (G). **Expect reds; each names a real SQL-emission gap.**
5. `Executor:263-274` — gate the null-drop on the `ResultShape` compile-time fact, or delete (F).

**Tier 2 — make the guards able to see (days, highest leverage)**
6. **Give the eval ledger a walk** over every module and both source roots, with an explicit
   exempt list. Closes probes 1b/2/3b/4/5 at once.
7. **Every guard asserts its own coverage** — "I scanned N files, N must not drop." This single
   change catches six of the nine compliance-theater instances.
8. Convert `EVICT_SIZE` from line count to an evaluation-spelling occurrence count.
9. Ban `org.sqlite..` (one string); extend the ratchet's ACCESSOR pattern and drop its
   `java.sql`-string precondition; class-list-pin `com.legend.exec`/`server`/`testdatagen`; count
   the two reflection pardons; fix the dead-method rule to `uses − selfCalls + refs ≤ decls`;
   make `defaultLiteralFallbacksOnlyShrink` match `default -> (?!throw)` (**59** `default -> null`
   sites today against a pin of 5).
10. **Add the missing invariant:** no production class outside `sql/dialect/` may hold a SQL
    keyword in a string literal. Seed shrink-only at 33 (§8).

**Tier 3 — honesty (hours)**
11. Delete the `preResolve` sentence from `AGENTS.md` and the three other citations.
12. Re-scope Charter Clause 3 to what its guard actually pins, or make the provenance rule real.
13. Correct the §11 metric claims; tell `FOUNDATIONS_PLAN.md` the pause ended.
14. Add `maxSoft`; re-`@Disable` the two empty GAP tests; extend `PctDisciplineTest` to `.pure`.

**Tier 4 — the second SQL producer (weeks)**
15. Rewrite `GridReads` to emit MIR (smallest of the four; proves the pattern), then `DbMetaData`,
    `CsvSeed`, and `TestDataGenerator`'s 17 sites.
