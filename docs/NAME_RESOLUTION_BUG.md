# Name resolution: unimported elements bind silently — the bug, the repro, the fix

> **STATUS: FIXED 2026-08-02** (task #110). §7 executed end to end:
>
> - **Step 0 (measured):** instrumented S1–S3; the full corpus sweep showed
>   **22 unique scan-bindings** (1,335 total hits — relationalDB 791, myDB 44,
>   bare class names in constraint/milestoning contexts).
> - **Step 1 (classified):** every corpus binding traced to OUR qualification
>   gaps, not corpus authoring — chiefly §2.4(b): no implicit own-package
>   import.
> - **Steps 2–3 (fixed):** `resolveNameMulti` precedence is now explicit
>   user imports → user wildcards + OWN PACKAGE → prelude FALLBACK (gated by
>   `Scope.prelude`; the raw `resolve(model, knownFqns)` entry keeps it off so
>   bare primitives pass through). The prelude no longer overwrites explicit
>   user imports (it lived in the same last-wins map). The
>   `resolveMappedClassName` positional special case is DELETED — subsumed by
>   the wildcard/own-package tier. Residual census after the fix: **zero
>   corpus scan-bindings**.
> - **Step 4 (deleted + pinned):** the S1–S3 bare-name arms are gone —
>   `findDatabase`/`findClass`/`findJoin` are exact-FQN-only (S4–S6 inherit;
>   S7's exact-first ordering is mooted — a bare name can no longer
>   exact-hit). The §2 repro is pinned failing-before/passing-after in
>   `core NameResolutionContractTest`, plus own-package, user-shadows-prelude,
>   and prelude-fallback pins.
> - **Blast radius (actual):** corpus **ZERO** (2180 exact, per-test
>   byte-stable; one SHAPE wall message moved a step deeper) — the §8
>   ordering (fix resolution BEFORE deleting the fallback) is why. The §3.1
>   ≥19 estimate materialized instead as ~130 failures in OUR OWN engine
>   integration tests whose hand-written models were invalid Pure (no
>   imports, bare `[PersonDatabase]`-style refs held up by the scan) — all
>   fixed by adding the missing imports / qualifying the queries, never by
>   relaxing the resolver. The 2072→1593 reorder collapse recorded in
>   Runner.java did NOT recur: own-package visibility was the missing piece.
> - **§8 (compile the corpus once): DONE (2026-08-02).** The runner
>   registers every family BEFORE the first test (two-phase
>   register/run), builds ONE strict-parse global model (543 sources,
>   ~9.4k elements, zero parse walls, zero duplicate FQNs, 5 element
>   walls), and hands each test an allocation-cheap per-test
>   RuntimeDefinition/ConnectionDefinition EXECUTION OVERLAY
>   (`PureModelContext.withExecutionOverlay`) instead of a module
>   recompile; the runner's throwaway validate-parse died with it
>   (`parseSources` wall-sink overload). DDL stays module-scoped
>   (`ddlScopeDbs`) because corpus table names collide across families.
>   Full sweep 2180 EXACT / h2 2148 EXACT; wall-clock ~185s. Two latent
>   collisions the always-visible corpus surfaced, fixed engine-true:
>   scoring/unification disagreement on `[]`-as-Nil (InferenceKernel),
>   and call-position candidates now UNION the prelude natives instead
>   of tiering them away (real pure has no user/platform tiering for
>   function matching — legend-pure's `schema(db,name)` coexists with
>   core_relational's `relation::schema(rel)`); `createDbConfig` joined
>   the platform-owned set (its corpus body evals the engine's dialect
>   registry). Library sources (m2m tree, pureToSQLQuery) now join the
>   global model like the engine's own graph.

> **Severity: correctness. Production path. Silent wrong SQL.**
>
> A reference that fails import qualification is resolved by scanning the **entire model** for any
> FQN ending in `::<name>`. A unique hit is bound with no error — **including when the referring
> file never imported that package.** Real Pure rejects such a model at compile time; legend-lite
> compiles it and emits SQL against the wrong store.
>
> **Companions:** `SIMPLE_NAME_AUDIT.md` (the full 9-site audit and classification),
> `CORPUS_SWEEP_PERF.md` (how this was stumbled into), `TENETS.md:51`, `AGENTS.md`.

---

## 1. The defect in one paragraph

`NameResolver` **correctly** qualifies bracketed store refs and class refs through the referring
element's own `ImportScope`. That main line is sound and was verified by probe. But
`resolveName(name, scope)` **returns the name unchanged** when no wildcard package yields a known
FQN. That unqualified string then reaches `ModelBuilder.findDatabase` / `findClass` / `findJoin`,
which fall back to a **global suffix scan** (`qualifiedName().endsWith("::" + name)`) and bind a
unique match. The referring file's imports are never consulted on that path — the lookup signature
`findDatabase(String fqn)` **takes no `ImportScope` and structurally cannot consult them.**

---

## 2. Reproduction

Runnable against `core/target/classes`. Compiles clean today and prints wrong SQL.

```java
import com.legend.Compiler;
import java.util.*;

public class FqnBug {
    public static void main(String[] a) throws Exception {
        // The ONLY myDB in the model lives in pkg::B.
        String f1 = "Database pkg::B::myDB ( Table T (ID INTEGER, NAME VARCHAR(50)) )";

        // This file imports pkg::A::* — which defines NOTHING — and references [myDB].
        // Correct Pure semantics: UNRESOLVABLE. pkg::B was never imported here.
        String f2 = "import pkg::A::*;\n"
                  + "Class model::Person { name: String[1]; }\n"
                  + "Mapping my::M ( *model::Person: Relational "
                  + "{ ~mainTable [myDB] T name: T.NAME } )";

        var ctx = Compiler.compileModel(List.of(
                new Compiler.ModelSource("f1.pure", f1),
                new Compiler.ModelSource("f2.pure", f2)));

        System.out.println(ctx.findDatabase("myDB")
                .map(d -> d.qualifiedName()).orElse("<unresolved>"));
    }
}
```

**Observed:**

```
findDatabase("myDB")          -> pkg::B::myDB      <-- bound, never imported
findDatabase("pkg::A::myDB")  -> <unresolved>
```

**End-to-end on the production API** (independently reproduced during the audit): a model where
`pkgA::otherDB` *is* imported and `pkgB::myDB` is *not*, with a mapping writing `import pkgA::*;`
and `[myDB]S.T_A`, **compiles clean** and `Compiler.compile` emits:

```sql
SELECT t0.NAME AS n FROM "S"."T_A" AS t0
```

— the schema of the unimported store. The same probe showed
`findJoinDefinition("myDB","J_SELF")` binding the unimported db's join, and `findClass("Firm")`
binding `pkgB::Firm`.

**What correct behaviour is:** compile-time failure. `pkgA` has no `myDB`; the file never imported
`pkgB`.

---

## 3. Why the test suite cannot catch this

**Structural, not accidental.** The corpus runner compiles each test family as an **isolated
module** (`Runner.moduleContextFor`). In an isolated module there is normally exactly **one**
candidate for any given simple name — so the global scan finds it, binds it, and is *usually right
by construction*.

The failure mode requires a model containing a same-named element the file did not import. Isolated
modules manufacture the opposite condition on **every single test**.

> **Therefore: `2148/2538` passing is not evidence of correct name resolution.** It is evidence that
> the suite never places the resolver in the situation where it goes wrong.

### 3.1 The corollary that reframes an earlier experiment

`Runner.java` records a reverted experiment: widening the compile unit via transitive closure dropped
passes **1219 → 1200**, and the comment reads that as foreign families *"poisoning resolution"*.

**Better reading: the ambiguity guard was working.** Widening the model gave those 19 references a
*second* candidate, so `findDatabase`/`findClass` returned `Optional.empty()` instead of a unique
wrong hit — converting **19 silent wrong bindings into honest failures.**

Those 19 tests were passing on wrongly-bound elements. Reverting the widening did not fix them; it
re-hid them. **This number is the best available estimate of the blast radius** (§5) and it is a
lower bound, because it only counts references that acquired a *second* candidate under that
particular widening.

---

## 4. The 9 sites

From `SIMPLE_NAME_AUDIT.md`. All are `endsWith("::" + name)` scans over the whole model.

| Tier | Site | Notes |
|---|---|---|
| **Primary** | `ModelBuilder.findDatabase` `:769-792` | reproduced §2 |
| **Primary** | `ModelBuilder.findClass` `:543-562` | reproduced §2 |
| **Primary** | `ModelBuilder.findJoin` | reproduced §2 |
| Inherited | `findFilter`, `findView`, `findType` | `findType`'s own javadoc says **"FQN-only"** — it is not |
| Low | 3 further sites | see the audit |

**Exact-FQN-only and therefore clean:** the other 12 of 21 `ModelBuilder` lookups, including
`findMapping`, `findAssociation`, `findFunction`. **`findPrimitiveExtension:335-340` explicitly
rejects the suffix-match pattern in its own javadoc** — the hazard was already understood in at
least one place.

**Note on where to look:** the dangerous pattern is `endsWith("::"` (23 code lines), **not**
`lastIndexOf("::")` — of those 23 sites, 15 are benign display/construction and one is dead. 17 of
the `endsWith` lines are platform-function tail-matching, a defect already fixed in three places
(`PlatformTypes:5`, `NameResolver:425`, `CoreFn:160`) and left standing elsewhere.

**Invariants violated:** `TENETS.md:51` — *"name-resolve: eager, **total**"*. A fallback that
resolves by global scan is neither. And `ModelContext.findType`'s documented "FQN-only" contract.

---

## 5. Blast radius — what breaks when this is fixed

Fixing this **will fail tests that currently pass**, because some of them pass by accidental
binding. Known and estimated:

| | Count | Confidence |
|---|---:|---|
| Corpus tests demonstrably passing on accidental bindings | **≥19** | measured, and a **lower bound** (§3.1) |
| Corpus tests total | 2,538 | — |
| Simple names shared by >1 class in the corpus | 168 | measured |
| `Database` simple names shared by >1 db (`myDB`×18, `db`×13) | 8 | measured |

**Do not guess the true number — measure it first.** §7 step 0.

---

## 6. Remediation options

### Option A — hard-fail on unqualified names *(recommended)*

Delete the global-scan fallback. An unqualified name that import qualification could not resolve
becomes a compile error naming the reference and the scope that failed.

- **For:** matches Pure. Satisfies `TENETS.md:51` (total) and "loud walls over wrong rows". Makes
  the class of bug unrepresentable rather than merely unlikely.
- **Against:** maximum immediate breakage (§5). Every accidental binding becomes a hard failure at
  once.

### Option B — keep the fallback, make it loud

Retain the scan but emit a declared wall / diagnostic whenever it fires, and fail if the unique
match's package is not in the referring element's import scope.

- **For:** the guard becomes real (it checks *imports*, not just *count*). Incremental.
- **Against:** two resolution paths for one behaviour — the "one owner per behavior" rule in
  `AGENTS.md` argues against keeping it permanently. Acceptable as a **transition**, not a
  destination.

### Option C — fix `resolveName` so qualification always fires

Attack the upstream cause: `resolveName` returning the name unchanged. If every reference site is
guaranteed to arrive qualified, the fallback is dead code and can be deleted safely.

- **For:** addresses the root, not the symptom. Likely the *correct* long-term shape.
- **Against:** needs an audit of every path into `resolveName` to prove totality.

**Recommendation: B as a short-lived transition to measure and burn down, then A.** C is what A
turns into once the last caller is proven total.

---

## 7. Sequencing

**Step 0 — measure the blast radius before changing anything.**
Instrument the fallback: log every time `findDatabase`/`findClass`/`findJoin` binds via the global
scan, recording the reference, the referring element, and whether the match's package was in scope.
Run the corpus sweep. The output is the exact list of accidental bindings and its length is the real
number for §5. **This is a read-only measurement and should happen first.**

**Step 1 —** classify that list: which are genuine test-authoring bugs in the corpus (a reference
that really is unimported) vs. gaps in our qualification (a reference Pure *would* resolve but
`resolveName` failed to). The second group is Option C work and is *our* bug, not the corpus's.

**Step 2 —** land Option B: the scope-aware guard plus a declared wall. Suite goes red by the §5
count; that redness is the honest baseline.

**Step 3 —** burn the list down.

**Step 4 —** delete the fallback (Option A/C) and add the §2 repro as a **failing-before /
passing-after** regression test. This bug must not be re-introducible.

---

## 8. The end goal: compile the corpus once

**Yes — and the reason is correctness, not speed.**

A single global compile is the **forcing function** for correct resolution. Today's isolated modules
are what let the global-scan fallback be accidentally right; put all 540 files in one model and every
reference must resolve through its own imports or fail. The bug class becomes **unrepresentable
instead of merely unlikely**, and the test suite regains the ability to see it.

**The corpus supports it.** Measured: **zero duplicate FQNs** across all element kinds — 2,275
classes, 533 mappings, 135 databases, 113 associations, 34 enums. There is no fundamental obstacle;
the 168 shared *simple* names are exactly what correct import-scoped resolution is supposed to
handle, and what the current fallback cannot.

**Be honest about the performance payoff, which is smaller than it looks.** Measured wall-clock
breakdown of a 299.6 s instrumented sweep:

| Phase | Time | Share |
|---|---:|---:|
| seeding (`replaySeeds`) | 201.8 s | 67% |
| module compilation | 45.0 s | 15% |
| test bodies | 27.0 s | 9% |

Compile-once addresses the **15%**, not the 67%. It is worth ~45 s. **It is not the fix for a slow
sweep** — seeding is — and it should be justified on correctness, with the time as a bonus.

**Ordering matters:** global compile cannot land before the resolution fix. Merging the model gives
every ambiguous reference a second candidate, so the current guard fires and turns accidental
bindings into mass failures — the 1219→1200 effect at full scale. Fix resolution first (§7), then
merging becomes safe *and* becomes the regression barrier that keeps it fixed.

---

## 9. What NOT to do

- **Don't treat the ambiguity guard as a safety property.** It covers only the ≥2-candidate case;
  the 1-candidate case is the common one and is unguarded (§1).
- **Don't cite corpus pass rates as evidence of correct resolution** (§3). The suite is structurally
  blind to this.
- **Don't chase `lastIndexOf("::")`** — 15 of 23 are benign. The pattern is `endsWith("::"` (§4).
- **Don't flip to a global compile first** (§8). It will produce mass failures that look like a
  regression and are actually 1219→1200 at scale.
- **Don't fix the 19 known cases and declare victory** — 19 is a *lower bound* from one particular
  widening (§3.1). Measure the real list at §7 step 0.
