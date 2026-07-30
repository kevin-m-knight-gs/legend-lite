# Audit Program — exhaustive branch, exception, and nullability audits

> **Companions:** `ARCHITECTURE_REMEDIATION.md` (shape), `CORRECTNESS_REMEDIATION.md` (answers),
> `TENET_REMEDIATION.md` (who does the work). Those three are *findings*. This one is the **plan for
> the next generation of audits** — what to run, in what order, with what taxonomy, and why.

Three audits, deliberately kept separate: **every `null`**, **every `try`**, **every `if`** in
`core/`. Plus the residual smell classes in §7 and the protocol in §6 that all of them share.

---

## 1. Why these three, and why not one

Five audits have run on this codebase. The pattern in what worked is unambiguous:

| Round | Organized by | Result |
|---|---|---|
| 1 | Quality dimensions (fallbacks, coupling, special-casing) | **Failed** — too broad, leaned on the repo's own docs, produced doc-drift |
| 2 | Concepts (aggregates, variant, temporal, columns) | Better, still **micro** — "M2M has one owner, Variant is smattered" |
| 3 | **Pipeline stages** (11 of them, each read entirely) | **Worked** — found the macro layer, drove corpus 1,258 → 2,074 |
| 4 | Correctness / corpus coverage | Worked — found that every scoreboard bucket was mislabeled, always flatteringly |
| 5 | Tenet conformance (8 agents) | Worked — found five falsified invariant headers and 20 ranked violations |

**Rounds 1 and 2 were *themes*. Round 3 was an *enumeration*.** A theme has no denominator: you can
never say it's complete, so agents pattern-match until they run out of budget and you get a list of
instances. An enumeration has a denominator: eleven stages, read each one, done.

`null` / `try` / `if` are enumerations over syntactic categories. That is why they will work where a
generic "code smells" sweep would repeat round 1.

**And they must stay three.** The temptation is to unify them as "silent decision points" — a
sentinel null, a swallowed catch, and a no-`else` fall-through really are the same *failure*. That
unification is a theme, and it reintroduces exactly the defect above. Three reasons to keep them
apart:

- **Countable denominators.** *"1,840 `try` blocks, 1,840 classified, 61 flagged"* is a sentence you
  can write. No theme permits it. §6.1 makes it mandatory.
- **Different reading tasks.** A `try` audit reads catch *bodies* and asks what happens to the
  exception. An `if` audit reads *conditions* and asks why the branch exists. A `null` audit reads
  *signatures and dataflow*. Merging them makes every agent context-switch three ways per file, which
  is how passes go shallow.
- **Separate docs get executed.** Empirical, not theoretical: `ARCHITECTURE_REMEDIATION` and
  `CORRECTNESS_REMEDIATION` are separate and the parallel session executed both. One combined doc is
  the one nobody finishes.

---

## 2. The sequencing criterion

The burndown is at **2,128/2,538 (83.8%)** and moving — it advanced twice while this document was
being written. The natural question is whether to audit now or wait for 2,538. That framing is wrong.
The criterion is:

> **Does this corrupt the instrument we are steering with?**

The corpus scoreboard decides what gets fixed next. Anything that makes it lie must be fixed *before*
several hundred more measurements are taken with it. Anything that does not can wait for a quiet
moment.

Applied:

| Defect | Corrupts the instrument? | When |
|---|---|---|
| `ADVISORY_MARKER` silent downgrade on failed H2 replay | **Yes** — a failure scores as a pass | Now |
| `DbMetaData.replay` swallowing errors except under `LL_TMP_DEBUG` | **Yes** | Now |
| 300-char diagnostic truncation (blocked 10 FAILs from diagnosis) | **Yes** | Now (`CORRECTNESS` C0) |
| Relation `toString` emitting a wrong `CAST` | **Yes** — tests get written against a broken surface | Now (`TENET` V1.5) |
| A hardcoded `if` keyed to one corpus family | **Yes** — makes a test pass that shouldn't | Now, that subset only |
| Long method, mutable private field, unclean null in a helper | No | Later |

### 2.1 Two facts that should lower your estimate of the cost of auditing now

**Staleness is lower than intuition suggests, and there is hard evidence.** Main moved **20 commits**
during the tenet audit. All **10** headline claims were re-verified afterward and **10 of 10
survived**, most byte-identical. Line numbers shifted; findings did not. In a compiler, structural
defects are durable — it is the *corpus numbers* that go stale, not the defects.

**The burndown manufactures these smells, so waiting multiplies the work rather than deferring it.**
`CsvSeed`'s silent `VARCHAR` is a T3.1 regression introduced under feature pressure — the sweep fixed
two of three sites. `HostEval` went from 4 metadata natives to ~25 language arms in **two commits**,
by explicit policy (*"language arms grown per wall"*). `createTableStatementText`'s quoting bug landed
in the last 20 commits. And a special case added at 83% is **load-bearing by 100%** — later tests get
built on top of it, so removing it stops being free.

The precedent is this repo's own: `ARCHITECTURE_REMEDIATION` was written at **49.6%**, mid-burndown,
and T0–T3 were executed *while* burning down (1,258 → 2,074). T0 was restoring the gates — which is
what made every measurement after it trustworthy. The audit did not compete with the burndown; it is
what made the burndown honest.

### 2.2 Schedule

1. **Audit N (`null`) — now.** It is a gate, not an audit; see §3.
2. **Audit T (`try`) — now.** Cheap, low staleness, and it protects the scoreboard directly.
3. **Audit I-slice (`if` with no `else` that doesn't throw) — now.** Small, and it is where a branch
   fakes a pass.
4. **Audit I-full (every `if`) — at 100%.** The only genuine "do it twice," and it isn't duplicated
   work: the slice asks *"does this branch fake a pass?"*, the full census asks *"why does this branch
   exist?"* Different questions.

---

## 3. Audit N — every `null`

**This is not an audit. It is a gate.** Every other item in this document produces findings that go
stale; this one produces a build-time check that runs on every commit forever. Sequence it first for
that reason alone — and because it pre-solves parts of T and I (a catch that returns null and a
provably-redundant `!= null` both become compiler output rather than agent findings).

### 3.1 What Java offers, and what we're taking

There is no non-null reference type in the language — every reference is nullable, and a draft JEP
under Valhalla would change that but has not shipped and should not be planned around. Five
mechanisms exist:

| Mechanism | When | Enforced |
|---|---|---|
| `Objects.requireNonNull` | Boundary assertions | Runtime, fail-fast |
| Nullness annotations + a checker | **The general answer** | **Build time** |
| Checker Framework | When you want proof, not heuristics | Build time, higher friction |
| `Optional<T>` | Return values only — never fields or parameters | Type system |
| Sealed interface + record variant | When null means *"a different kind of thing"* | Type system |

#### DECISION (adopted) — our own annotations, NullAway as a build-time plugin

**Declare the annotations ourselves.** `com.legend.Nullable`, `com.legend.NonNull`,
`com.legend.NullMarked` — about 30 lines total, `@Retention(CLASS)`, `@Target(TYPE_USE)` for the first
two and `PACKAGE`/`TYPE` for the third. **No JSpecify dependency**, and no external import ever
appears in production source. JSpecify's real contribution is its *specification* of edge-case
semantics for generics and wildcards, not its code; a records-and-sealed-interfaces codebase with few
generics does not reach those edges.

**Use NullAway (over Error Prone) as the checker, configured to recognize our annotations** via
`-XepOpt:NullAway:CustomNullableAnnotations`, alongside the required `-XepOpt:NullAway:AnnotatedPackages`.
Verify current option names against NullAway's docs when wiring — the capability is long-standing, the
spelling has moved across versions.

**Why this is not a dependency in the sense we care about.** Error Prone and NullAway go in
`maven-compiler-plugin`'s `<annotationProcessorPaths>`, **not** in `<dependencies>`. That means: not on
the compile classpath, not on the runtime classpath, not in the shipped jar, not propagated to
consumers of `legend-lite-core`. It is build tooling in the same category as surefire — a *smaller*
footprint than JUnit, which at least occupies the test classpath. Production dependencies stay exactly
what they are today: two JDBC drivers.

**Why not write the dataflow ourselves.** Flow-sensitivity — making `if (x != null) { x.foo(); }`
legal — requires a control-flow graph over Java's full statement grammar (try/finally, labeled break,
switch expressions, pattern matching, lambdas) plus fixpoint iteration for loops plus a model of which
JDK methods return null. NullAway itself does not write this; it vendors Checker Framework's dataflow
engine. That is the correct read on how hard it is.

**The cost we are accepting, stated plainly: JDK coupling.** Error Prone reaches into javac internals,
so JDK 16+ needs a block of `--add-exports jdk.compiler/com.sun.tools.javac.*` flags in the compiler
configuration. It is documented and stable, but a JDK bump can break the build — and this machine runs
a hand-installed JDK 21 under `~/jdk`. Treat the flag block as load-bearing build configuration, and
expect to touch it on any JDK upgrade. Also disable Error Prone's default check set and enable only
NullAway, or compile time grows for checks we did not ask for.

#### Division of labour with the guardrail test

NullAway owns **nullness**, because that is the part needing dataflow. `CodeShapeGuardrailTest` owns
the syntactic tenet rules NullAway cannot express — and that split is why both exist:

| Rule | Owner |
|---|---|
| Nullness: sentinel returns, field init, parameter/return contracts | **NullAway** |
| `default ->` returning a value where a sealed switch should throw | Guardrail test |
| A `catch` block that returns a value (Audit T's degrade class) | Guardrail test |
| `endsWith` on FQN strings — tenet #4, currently unenforced | Guardrail test |
| Regex over `e.getMessage()` | Guardrail test |
| Text rewriting of platform-generated SQL (the R0 rule) | ArchUnit |

`CodeShapeGuardrailTest` is already the right vehicle for its half: 276 lines, walks `src/main/java`
with `Files.walk`, reads source via `readAllLines`, and already carries named exemptions with written
reasons. Zero new dependencies for that half.

### 3.2 Current state (measured at `b9746692`)

- **Zero** null-safety tooling — no NullAway, JSpecify, Error Prone, Checker Framework, JSR-305, or
  JetBrains annotations in any `pom.xml`.
- **Zero** `@Nullable` / `@NonNull` / `@NullMarked` annotations anywhere in `core/src/main/java`.
- **14 `package-info.java` files**, one per pipeline phase — exactly the granularity `@NullMarked`
  works at.
- `Objects.requireNonNull` in **88 of 368** core files.

That last number is the finding in miniature: the discipline exists, it is applied to under a quarter
of the codebase, and **nothing enforces it**. Same shape as every other defect this program targets —
a practice asserted without a gate.

### 3.3 The taxonomy (fix before the sweep)

| Class | Meaning | Verdict |
|---|---|---|
| **Domain** | SQL NULL — a real value in the problem space | Legitimate, and already reified: `SqlExpr.NullLit`, `HostEval.SQLNull` |
| **Adapter-boundary** | `rs.getObject()`, `Map.get`, `Matcher.group`, `System.getenv` | Legitimate for **one line**, inside the converting method. Must never escape |
| **Optionality** | A field that may be absent (`child.groupBy() != null`) | Style, with teeth — the check at `MappingNormalizer:755` is already dead, meaning the contract drifted unnoticed |
| **Sentinel** | `return null` meaning *"I didn't recognize this shape"* | **The finding.** Tenet #3 says throw; `NotImplementedException` has 339 sites |

### 3.4 The rule this yields

> Java `null` may appear only inside a method that converts an external value into a domain type. It
> may never appear in a **field**, a **return type**, or a **parameter** of anything in
> `com.legend`.

Which is precisely what `@NullMarked` + NullAway enforces, package by package.

**The codebase already agrees with this rule in the two places it thought hardest.** SQL NULL is not
Java null here — it is `SqlExpr.NullLit`, a sealed variant. And when `HostEval` needed empty cells to
stay index-addressable, it introduced `SQLNull` rather than reach for null (*"SQLNull keeps null cells
POSITIONAL — `at(N)` indexing depends on it"*). Twice, when someone reasoned about absence explicitly,
they made it a type. **Java null survives where nobody made a decision** — which is why null in a
signature is a reliable detector for an undecided case.

The technique is also already in use for a neighbouring problem: `Ddl.spell` is exhaustive over a
sealed type with explicit throws, *"so a new variant is a compile error here, not a runtime surprise
(T3.1)."* Nullness annotations are that same move applied to absence instead of to variants.

### 3.5 Adoption order — cheapest proof first

`com.legend.sql` → `lowering` → `resolver` / `normalizer` → `compiler/spec` → **`exec` and `harness`
last**. The ADT packages should be near-silent, which proves the toolchain at almost zero cost. `exec`
will be loudest — which is exactly where three independent audits already located the problems. **The
friction is the signal.**

### 3.6 Honest counterarguments

- *"JDBC hands you null."* True — `rs.getObject()` returns null for SQL NULL and you cannot refuse it.
  But you can refuse to **propagate** it: wrap at the seam, one line in `Executor.cell()`. `SQLNull`
  proves the pattern is understood.
- *"`Optional` allocates."* Irrelevant here — legend-lite is bound on JDBC round-trips (431 statements
  for one corpus setup). A sealed variant allocates no more than the nullable field it replaces.
- *"A record with 8 fields, 3 optional, would need 8 variants."* **This one is real.** `Optional<T>`
  fields or a nested options record is the pragmatic answer there. Still not Java null.

---

## 4. Audit T — every `try`

Catch bodies are infrastructure rather than feature code, so staleness is minimal: a swallowed catch
found today is still swallowed at 100%.

### 4.1 The taxonomy (fix before the sweep)

Denominator classes: **rethrow** · **translate** (wrap into a phase-tagged
`LegendCompileException`) · **cleanup** (close/release, rethrows) · **control-flow** (see the caveat
below).

Finding classes: **swallow** (empty, or log-only) · **degrade** (produces a value, a default, or a
weaker verdict).

### 4.2 What to check

- A catch that **returns a default** instead of rethrowing — the degrade case, and the one that
  silently changes an answer.
- `catch (Exception)` / `catch (RuntimeException)` breadth where one specific throw is expected.
- A `try` wrapped around a **block** where only one call can actually throw — the other statements are
  silently covered.
- **Exception identity or message driving control flow.** `Runner.unknownTypePull:923-945` regexes
  `e.getMessage()` against `"[Uu]nknown (?:type|class)[:]? '([\w:]+)'"` to drive a compile-retry loop.
  Any reworded error silently disables the retry.
- `finally` blocks that can discard an in-flight exception.
- Catches whose recovery path is only observable under a debug env var.

### 4.3 The caveat — exception-as-control-flow is not automatically wrong

`TENET_REMEDIATION` §3.2 *recommends* `try { Json.parse(v); return VARIANT; } catch (…) { return
STRING; }` as the fix for a 143-line duplicated parser. That is a parse-attempt used as a type test,
and it is correct: the exception makes a **decision**, it does not fabricate an **answer**. The smell
is a catch that produces a value, not one that selects a branch. The taxonomy must hold that line or
it will convict the right code.

### 4.4 Evidence this will yield

Four silent-degradation paths were found *incidentally*, by audits that were not looking for them:

- `InnerDemand:501-503` swallows a `RuntimeException`, so a resolver bug degrades silently into "not
  an in-query read" — and the fallback is a *different, possibly wrong* lowering.
- `DbMetaData.replay:102-124` executes Java-synthesized `ALTER` statements on real H2 and swallows
  errors, logging only under `LL_TMP_DEBUG`.
- `TestBody:1010-1013`, `:1038-1043` — a catch converts a failed H2 replay into `ADVISORY_MARKER`,
  which **scores as a pass**.
- `H2Verify` degrades via `ready()` when the driver is absent, and `core` declares no H2 dependency at
  all.

A category with a four-for-four incidental hit rate is worth enumerating deliberately.

---

## 5. Audit I — every `if`

This closes the round-1 question that is still unanswered: *"do we have a lot of hardcoded `if`
statements that do special things just to pass tests?"* Rounds 1–3 all answered it obliquely, because
a special case that exists to pass one test has **no architectural signature** — no re-derivation
smell, no ownership violation, no coupling. It is one line that looks reasonable. You cannot sample
for it, which makes exhaustive enumeration the correct shape rather than an excessive one.

### 5.1 I-slice, now — branches that can fake a pass

Scope: an `if` with **no `else`**, whose fall-through **produces a different answer** rather than
throwing. That is tenet #3's exact shape, and it is where this session's worst defect lived:
`Scalars.pureToString` exhausts every arm and lands on `new SqlExpr.Cast(x, VARCHAR)`, so
`<relation>->toString()` compiles to a wrong-valued cast. Three thematic audits walked past it; a
branch audit is built to catch it.

Related confirmed instances, all of which look defended and are not:

- `Fold.filterSlot:236-247` guards on the **predicate** referencing a window column rather than on the
  select **carrying** a window — the sibling `groupByFolds:301-305` guards correctly.
- `Scalars.isFullPrecisionDate:3372-3375` is **inverted** for `Type.Primitive.DATE`.
- `PureSql:122-123`'s *"a relation is a SOURCE, not a scalar SQL type"* guard **can never fire** on the
  path that needs it.
- `MappingNormalizer:755`'s null-check is **dead**.

### 5.2 I-full, at 100% — every `if`, honestly every

Roughly 8–12k sites across ~368 files. Run as a pipeline so exhaustiveness is real rather than
rhetorical:

1. **Mechanical extract** — every `if`, with file, line, enclosing method, condition text, and whether
   an `else` exists.
2. **Cheap triage** — a one-line verdict on **every** site. This is the pass that makes the audit
   exhaustive.
3. **Deep read** — full context on the survivors, expected to be a few hundred.
4. **Adversarial verify** — anything claimed as *hardcode* gets independent refutation attempts, per
   `TENET_REMEDIATION` §6's tie-breakers. A wrong hardcode accusation costs more trust than a missed
   one.

**Triage keeps a site when the condition:** contains a **string literal** (FQN, test name, table or
column name) · contains a **number that isn't 0/1/-1** · has **no `else` and no throw** · has a body
that is `return <constant>` · has a comment within 3 lines naming a test, audit, or corpus family ·
joins ≥3 clauses with `&&`/`||` · nests ≥3 deep · is an `instanceof` chain where a sealed switch exists
· asks a **value** what the **type** could answer · is keyed to one dialect or backend.

### 5.3 Taxonomy (fix before the sweep)

Denominator: **guard** (null/bounds/precondition) · **dispatch** (routing on a type or kind) ·
**desugar** (notation the parser created) · **optimization** (same answer, less work).

Findings: **special-case** (a named shape handled off the main mechanism — legitimate only with a
recorded reason) · **hardcode** (exists to make a specific test or corpus family pass).

---

## 6. Protocol — applies to all three

### 6.1 Report a denominator, always

Every audit states *N found, M classified, K flagged*. This is a direct response to
`CORRECTNESS_REMEDIATION` §1, whose meta-finding was that every scoreboard bucket was mislabeled and
always in the favourable direction. Without a denominator you cannot distinguish coverage from
cherry-picking — and an audit that reports only its hits reads as complete when it isn't.

### 6.2 Fix the taxonomy before the sweep, not during

Otherwise agents invent their own categories and nothing aggregates. §3.3, §4.1, and §5.3 are the
taxonomies; they are inputs, not outputs.

### 6.3 Docs and comments are claims, not evidence

Round 1 failed on this. A comment is a hypothesis to test against the code. Five invariant headers
were falsified in the tenet audit — *every one flattering* — so a header asserting a settled
discipline is a **lead**, not a fact.

### 6.4 Every finding carries file:line and a confidence marker

`VERIFIED@<sha>` = read at that commit this cycle. `LIKELY` = derived from a verified adjacent fact.
Line numbers drift and findings don't (§2.1), so cite the sha.

### 6.5 Prefer a gate to a finding

A finding is fixed once; a gate holds forever. `CsvSeed` regressed T3.1 precisely because the fix was
hand-applied to three sites and nothing checked the third. Convert wherever possible:

| Category | Mechanizable as |
|---|---|
| Nullability | Our own annotations + NullAway as a build-time plugin (§3.1) |
| Swallowed exceptions | `CodeShapeGuardrailTest` (catch-that-returns) |
| Sealed-switch fall-through | Already free in Java 21 — exhaustive switches are compiler-checked |
| `endsWith` FQN matching | ArchUnit (tenet #4 already bans it; the rule doesn't exist) |
| Regex over exception messages | ArchUnit / Error Prone |
| Text rewriting of generated SQL | ArchUnit — the R0 rule `RUNNABILITY_PLAN.md:65` still lists as a plan item |
| `hostChannel` dispatch scope | ArchUnit — it has already collapsed the sweep once (2,096 → 408) |

`if`-hardcoding is the one category that resists mechanization entirely, which is the argument for
spending agent time there rather than on the others.

---

## 7. Residual smell classes — the thematic layer, ranked by demonstrated yield

Keep these as probes *within* the three audits rather than as a separate sweep. Each has already
produced a real defect here, which is the only evidence that justifies grepping for it again.

**A. Claim-vs-code drift.** Five for five. Probe the grammatical forms — *"THE one X"*, *"ZERO Y"*,
*"audited out"*, *"never Z"*, *"(audit-verified)"*, any `CONTRACT:` header — then read the call sites,
not the neighbours. Include docs asserting a remediation is **complete**.

**B. Second owners.** Grep the *concept*, not the name, and count distinct implementations. Found so
far: β-reduction ×2 (one capture-unsafe), JSON parsers ×4, structural equality ×2, CSV rendering ×2,
order-derivation ×2. Highest yield across the harness/platform seam, which tenet #2 names as the
cardinal sin and nothing enforces.

**C. Silent wrong answers.** `default ->` returning a value where a sealed switch should throw;
degradation paths that read as a pass; one-directional comparator bridges; diagnostic truncation.

**D. Computed and discarded.** Fields written and never read (`ExecFrame.result()` — one reader in the
file, at a different call site); eager work whose result is dropped; a stage re-deriving what upstream
knew. Cheap probe: for each expensive call, find the reader.

**E. Stringly-typed control flow.** Dispatch on JDBC type *name* with `startsWith("DECIMAL")`; regex
over `e.getMessage()`; recovering a numeric kind from a print form via `endsWith("D")`; Java
text-rewriting SQL Java itself generated.

**F. Guards that don't guard.** §5.1's four instances. Invisible to every other method, because the
code *looks* defended.

**G. Growth without a stopping condition.** *"Arms grown per wall"*; exemption lists that only grow —
three ArchUnit carve-outs for `com.legend.harness`, the type-driven handle rule, ban lists with no
admission criterion. Not a bug today; the mechanism by which one arrives.

**H. Magic numbers appearing exactly once.** `300` (diagnostic truncation), `MathContext(10)`
(collapses 14-digit integers), recursion depth `3`, 2-ULP, `1e-11`. Two of those five are already
confirmed defects.

**I. Dead code.** Mechanical — use a reachability pass, not agents. `TestBody.csvText` and
`constantStrings` are already identified.

### 7.1 Mutable state and void methods

Real here, but **don't sweep it generically — chase the symptom you already have.**

`ENGINEERING_LOG:53-54` records `testConcatenateClassAgg` as a known flapper that flips PASS/FAIL
across sweeps, documented as *"not a signal."* A test that flips without code changes is
order-dependent by definition, and that is a standing, unexplained admission of hidden state. Working
backward from it has a guaranteed real finding at the end; a generic mutable-field census may not.

Supporting evidence that the class is real: `ClassSources:798` constructs *"a fresh resolver instance
to avoid state leakage"* — an admission in a comment. `EngineTextBoundary` uses a **thread-scoped flag
to change what the lowerer emits**, i.e. ambient mutable state steering compilation output.
`RawSqlBoundary.record`/`recordMeta`, the `rawSqlFailureSink` ledger, `TestBody`'s growing `execStmts`
prefix and flat `lets` map — and `expandHelperCalls`' capture bug **is** a flat-mutable-namespace bug,
not a β-reduction bug.

**For void methods, sharpen to one question with a crisp answer:** *does any phase class in the
pipeline have a mutable field?* The pipeline's stated contract is typed-in / typed-out, and the tenet
audit already proved `StoreResolver` holds no `Connection`. Whether it holds any mutable state at all
is mechanical, one pass, and directly tests a claim the architecture makes about itself.

---

## 8. Do not include these

Guardrailed already, or pure noise. False alarms cost trust, and three audits have been spent
establishing that trust.

- **Method length, file length** — `CodeShapeGuardrailTest` already enforces ≤3,500 lines and ≤250
  methods.
- **Cyclomatic complexity, parameter counts, naming, comment density** — no demonstrated correlation
  with any defect found here. `Scalars.java` is 3,451 lines of dispatch table and is the single best
  artifact in the repository.
- **Generic duplicated-code detection** — superseded by §7.B, which counts *concept* owners rather
  than token similarity. Token-level clone detection would flag the sealed-switch arms that are the
  correct design.
- **`Optional` in fields or parameters** — the recommendation in §3 is deliberately narrow. Don't let
  an over-eager sweep convert nullable fields into `Optional` fields, which are themselves nullable.
