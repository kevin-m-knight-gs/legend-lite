# Audit: the compiler's two scale costs (branch `core/scale-costs`, 2026-10-15)

The independent audit agent's report on commit 013de83a1 over main 8d2e55a65. The commit was then amended with the
blocker, the should-fixes and the nits taken (the landed commit is named in GATES, "The compiler's two scale costs").
Read-only; the auditor read the commit through git (a judge chain had the working tree swapped) and ran no Bazel.
Quoted as delivered, its file list removed (worktree paths).

---

**Verdict: ready after fixes.** 1 blocker, 4 should-fix, 4 nits.

I read everything through git and ran no Bazel, as the brief asked. The blocker therefore comes from reading the
guard, not from running it.

### Blocker

**B1. The `//core:guardrails` lane will fail: the commit adds two mutable instance fields that are not allowlisted.**
- **Where:** `Lowerer.java:187` declares `private int correlatedReads;` and `Fold.java:546` declares `private int
  left;` (inside `Fold.Budget`).
- **The guard:** `CodeShapeGuardrailTest.mutableInstanceStateIsExplicit` (`CodeShapeGuardrailTest.java:604-626`).
  - The 4-space field (`correlatedReads`) matches `MUTABLE_FIELD` (lines 227-231).
  - The 8-space private field (`left`) matches `NESTED_MUTABLE_FIELD` (lines 236-238).
  - Keys are scoped to the file name, so they come out as `Lowerer.correlatedReads` and `Fold.left`. Neither is in
    `MUTABLE_FIELD_ALLOWLIST` (lines 52-210), and the commit does not touch the test.
- **The lane:** the test class is `@Tag("guardrail")`. It runs in `//core:guardrails` (`core/BUILD.bazel:855`,
  `--include-tag=guardrail`), which reads `_CORE_READS = glob(["src/**"])`.
- **Fix:** allowlist both entries, each with a dated reason. `UserCallInliner.spent` is the precedent for a budget
  counter. Then run `//core:guardrails`.

### Should-fix

**S1. Extend passes get an uncharged copy, so the budget is not Calcite's rule there, and an extend chain can still
grow exponentially.**
- **Mechanism:** with `keepExisting`, `tryComputedColumns` copies every base projection into the new select
  (`Lowerer.java:1473-1476`, `ps.addAll(starProjections(base))`). The first read of a computed column then writes it
  a second time, and the budget treats that write as free (`Fold.java:566`, `written.add(column)`).
- **Calcite's actual check:** `pushPastProjectUnlessBloat` refuses when merged > bottom + top + bloat. The
  pass-through copy already counts as the bottom expression's one move, so this read would be charged.
- **Example that escapes the budget:** an extend chain where each new column is the sum of the two before it
  (`~c: $r.a+$r.b`, `~d: $r.b+$r.c`, ...). Each pass reads two columns once each, so nothing is charged, and
  expression size grows like Fibonacci.
- **Smaller differences from Calcite:** Calcite charges nodes(e)-1 per extra reference (not nodes(e)), and it credits
  bottom expressions nobody reads.
- **Fix:** when `keepExisting`, pre-mark the kept computed projections' names as already written. Add a test that pins
  an extend chain.
- **Message:** "The rule is Calcite's" is stronger than the code; "Calcite-shaped" would be accurate.

**S2. A refused whole-variable read cannot be fixed by isolating, so it fails loudly where main folded.**
- **Mechanism:** `Lowerer.java:1753-1754` spends budget on a whole-variable read under the key `"<whole variable>"`.
  The isolated base is `starOf(...)`, which has no projections. Over it, `resolveOrThrow(select, null)` throws
  `UnfoldableRef("<whole variable>")` (around `Lowerer.java:1705-1711`).
- **Result:** the retry fails, and the read ends in `computedColumns`' `IllegalStateException` (`Lowerer.java:1436`)
  or in `predicateOrThrow` (`Lowerer.java:1644`).
- **When it bites:** only when a single-column select's computed expression is read whole past the budget. Rare, but
  it turns a fold into a loud failure.
- **Second effect:** the key differs from the projection's own name. If the same column is read both as `$x` and
  `$x.col`, it gets two free writes.
- **Fix:** skip the spend when `name == null`, i.e. `if (correlatedReads == 0 && name != null)`, or key by the
  resolved name and never refuse a read that isolation cannot cure.

**S3. The flatten exemption covers more than the correlated reads.**
- **Mechanism:** `Lowerer.java:650` wraps the whole `CollectionRelations.flatten(...)` call in `correlated(...)`.
  That includes all of `lo.scalar(cr.value(), ...)` (`CollectionRelations.java:52-61`).
- **Effect:** any fold pass lowered inside a flatten's collection value runs with `correlatedReads > 0` and spends no
  budget. This is no worse than main, but it is a hole in the new defence.
- **Fix:** wrap only the `outer.resolve` at `CollectionRelations.java:54` (needs a package-private `correlated`).

**S4. The size figures in the comments do not agree with the commit message.**
- `Fold.java:534` and the `ScaleCostsTest` class comment say "20 nested derived tables gave 53 MB of SQL".
- The commit message says "10 nested derived tables ... 7 MB, 15 ran out of memory after 168 s".
- At one doubling per level, 7 MB at 10 levels would be about 7 GB at 20, so the two cannot describe the same query
  shape. Say which shape each number measures, or cite one.

### Nits (each a short fix)

- **N1. Two doc comments are now orphaned.**
  - `Fold.java:529-534`: `scalarInlineable`'s doc now sits directly above `Budget`'s doc, so `scalarInlineable` has
    none.
  - `Lowerer.java:1721-1725`: `scopedResolver`'s doc now sits above `correlated`.
  - Fix: move the inserted blocks above those comments.
- **N2. `RenderCensus.java:78` tests `m.contains(StorelessRuntime.RUNTIME)`.** This also matches a mere reference
  (`->from(storeless::Runtime)`), a comment, or `storeless::RuntimeX`. Use `m.contains("Runtime " +
  StorelessRuntime.RUNTIME)`.
- **N3. The `ScaleCostsTest` bound `reads <= 64` would pass if `age` vanished from the output.** The derivation below
  gives exactly 64, so use `assertEquals(64, reads)`.
- **N4. `SqlSource.Join`'s five-argument constructor is public and unchecked** (a record's constructor cannot be made
  private). One doc line saying `outputs` must equal `delivered(left, right, kind)` would cover it.

### Checks made

**1. The `Join` record: no problems.**
- There are 48 `new SqlSource.Join(` sites (main and tests); I read each one and all take four arguments.
- No record pattern deconstructs `Join`, and nothing reflects over its components. `SpecChildrenContractsTest`
  reflects over SqlExpr, ValueSpecification and RelationalOperation, not SqlSource.
- **Equality and hashing:** `outputs` is a pure function of left, right and kind, so equals/hashCode behave as before.
  The one equality-keyed map is `VerdictSql`'s `HashMap<SqlQuery,String>`; MariaDb and SQLite use `IdentityHashMap`.
- **Printed form:** no test asserts `Join`'s toString; only a 2026-09-02 doc mentions it.
- **Immutability:** every SqlSource implementation is a record. SqlSelect computes its outputs at construction
  (`List.copyOf`), and Subselect delegates to `inner.outputs()`. No `outputs()` throws, so computing it eagerly is
  safe.
- **NullAway:** `@Nullable` is on both the record header and the delegating constructor, and calling the static
  `delivered` inside `this(...)` is legal.

**2. The budget and the retry loop.**
- `computedColumns` (`Lowerer.java:1429-1436`) makes two attempts, and the retry gets a fresh budget.
- Over the isolated base, `starOf` gives no projections, so `resolveIntoExact` goes to `sourceColumn`, which returns a
  stamped plain column. Those cost nothing, so the retry cannot be refused, except for whole-variable reads (S2).
- **`tryPredicate`'s callers:**
  - `filter` (`Lowerer.java:1546`) falls back to the unbudgeted `tryWindowPredicate`. That path is used only when a
    window column was read, and such a predicate already failed `tryPredicate` before this commit, so nothing
    changes. Otherwise it isolates and calls `predicateOrThrow`.
  - `whereLambda` (`Lowerer.java:3527`) behaves the same way.
- **Post-aggregation branch:** leaving it unbudgeted is right. It substitutes one level into HAVING, and nothing
  folds into a grouped select afterwards (`projectionFolds` / `groupByFolds`), so no chain can form.
- **Loops:** none possible; every retry is bounded at two attempts.

**3. Every route by which an enclosing resolver is called.** I found no route where a refusal lands in an inner
attempt whose retry cannot fix it.

- Lateral push (`Lowerer.java:622`): uses `resolveOrThrow(starOf(...))`, so reads are plain columns and nothing is
  spent.
- Flatten (`Lowerer.java:650`): wrapped in `correlated(...)` (too broadly, see S3).
- Graph-fetch row resolvers (`Lowerer.java:905/925/972/1102`): unbounded budget.
- Relation-predicate scope (push at `Lowerer.java:2898`): reached through `scopedResolver`'s outer loop (1768,
  wrapped) or `sideCondition`'s (2220, wrapped).
- Enclosing scalar (`Lowerer.java:3566`): deliberately not wrapped (see below).
- Subquery scopes (pushes at `Lowerer.java:3154` and `3231`): reached only through the wrapped loops.

(The auditor's table, reflowed as a list to fit 120 columns.)

The unwrapped `enclosingScalar` route is sound:
- It is called at `RelationPredicates.java:198`, before `searchedColumn` lowers anything.
- The scalar arm that pushes the scope (`Lowerer.java:2896-2903`) has only try/finally, so no attempt sits between
  the refusal and the pass that owns `columns`.
- The refusal therefore reaches that pass's own attempt (`Lowerer.java:1497` or `1670`), whose retry over the
  isolated base cures it.

**4. RenderCensus.**
- `//core` exports `:test` (`core/BUILD.bazel:242`) and `:model`, and its visibility includes `//tools/census`, so
  `StorelessRuntime` is reachable.
- `lowerResolved`'s runtime parameter is non-null.
- The declared database feeds only `ExecutionContext.rawLanguage` (the language of setup SQL and executeInDb text),
  never the render dialect. The census compares two commits under the same fixed choice, so DuckDB is acceptable. H2
  would be more faithful for raw text taken from engine cases, but that is not a defect.

**5. The doubling test.**
- Each level's expression has 2^(k+1)-1 nodes. Levels 2-6 pay 3, 7, 15, 31, 63.
- Level 7 would pay 127, more than 100, so it isolates. Levels 7-12 then rebuild up to 127 nodes over `q.x`.
- That leaves exactly 64 reads of `age`, so "at most 64" is the tight bound.
- The length check is safe: only DuckDB renders, so dialect spacing does not matter, and the author's own 10- and
  20-level figures (1,794 and 3,814 chars) are far below 6,000.
- `nest(9)` crosses the level-7 isolation, so the parity check is meaningful.
- Matching `age` is safe: EMP's columns are ID, firstName, LAST_NAME, dept, salary, bonus, age, hired, and the
  aliases are `q1`... and `x`.

**6. The commit message against the diff.**
- `Fold.sourceColumn` reads `src.outputs()` twice at `Fold.java:981` before choosing its case: confirmed.
- `tryProjectAll` (`Fold.java:1435`) is unbudgeted. It only narrows (each column written once), and the message
  makes no claim about it.
- The graph fetch is unbounded at `Lowerer.java:905` and `1102`, as the message says.
- The Calcite claim is overstated (S1), and the figures disagree (S4).

**7. Style.** Every added line is within 120 columns, there are no local paths, and the decision comments are dated
2026-10-15. No guard or ledger file is changed.

---

## Taken

- B1: `Fold.left` and `Lowerer.correlatedReads` are in the code-shape guard's allowlist, each with its dated
  reason; `//core:guardrails` green (the judges' evidence).
- S1: an extend's kept computed projections are the budget's first writes (`Fold.Budget.kept`, called by
  `tryComputedColumns` for every kept projection that is not a plain column), a further write costs the growth it
  brings (the expression's nodes less the read it replaces, Calcite's count), and `ScaleCostsTest` pins the extend
  chain the auditor described (twenty levels, each adding the two columns before it: 44 reads of the base column,
  all in the innermost select, the answer DuckDB's own at nine). The comments and GATES say "Calcite-shaped",
  and that Calcite's credit for an unread bottom expression is not taken.
- S2: a whole-variable read spends nothing (`name != null` in the guard), with the reason beside it.
- S3: `correlated` is package-private and `CollectionRelations.flatten` wraps only its outer read; the flatten arm in
  `Lowerer` is no longer wrapped.
- S4: `Fold.Budget`'s doc and the test's class comment name both shapes with their numbers (the tenth line's 20
  levels at 53 MB; this chain's 7 MB at 10 levels and out of memory at 15), citing `measurements.txt`.
- N1: the two inserted blocks moved above their neighbours' doc comments.
- N2: the census tool tests for the declaration (`"Runtime " + StorelessRuntime.RUNTIME`).
- N3: an exact count, with its derivation in a comment: four, not 64. The auditor's derivation took `x + x` as three
  nodes; it lowers null-safe (`CASE WHEN x IS NULL OR x IS NULL THEN NULL ELSE x + x END`), four reads of x in
  eleven nodes, so the budget isolates every second level and the base column is read by the first level alone.
- N4: the five-argument constructor's contract is in the record's constructor doc.
