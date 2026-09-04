# The World Map — what is Java, what is Pure, what is input

> Ratified with the user 2026-09-03 after the tier-1 recursion work and the
> option-2 homework (`docs/OPTION2_HOMEWORK_2026_09_03.md`). This is the
> conceptual map every design decision about "Java vs Pure" is made on. The
> adjudication clause is `docs/TENET_CHARTER.md` Clause 6; `AGENTS.md` points
> here. Change this document only as a reviewed design decision.

## 1. What legend-lite is

A compiler from Pure to SQL, plus an orchestrator that drives a database. The
platform's own semantics are Java. The platform has **no runtime dependency on
legend-pure or legend-engine**: their `.pure` files are specification and test
input — read, verified against, never loaded, never executed.

Two tenets already govern this and are unchanged:

* **Java orchestrates, the database executes.** The query compiler executes no
  values (`docs/TENET_CHARTER.md` C1–C5).
* **Reference checkouts are spec, never runtime** (`AGENTS.md`).

What the corpus burn-down (2026-09) exposed is that "reimplement whatever we
need from legend-pure/engine in Java" was the right rule for two kinds of Pure
code and the wrong rule for a third. This document names the three kinds and
the rules that follow.

## 2. The three kinds of Pure code

| kind | examples | where its MEANING lives | how the platform treats it |
|---|---|---|---|
| **1. Natives** | `toLower`, `filter`, `newMap`, `groupBy`, `at` | Java, as ONE SQL lowering rule per native (what `toLower` means is SQL `lower`) | never a Pure body, never a Java evaluation |
| **2. Platform semantics** | routing, mapping resolution, milestoning, aggregation-aware rewrites, post-processors, plan generation, SQL generation itself (`pureToSqlQuery.pure`, `sqlQueryToString`) | Java, designed from the engine's Pure as SPEC | never run; the engine's implementation is read, not ported line by line |
| **3. Programs** | a user's M2M transform, a test helper, `wrapH2Boolean`, `toPostgresModel`, `getNames()->at(0)` | the program's own text | INPUT to the compiler, regardless of who wrote it |

Kind 3 is the new one. Engine developers authored some of these programs, but
they are indistinguishable from user code: they use natives and platform
semantics to build values. **A program is never ported to Java.** `MetamodelWalk`
(905 lines) was `toPostgresModel` ported to Java — the anti-pattern the old
rule failed to name — and is deleted by the toPostgresModel leg.

### The deletion test (kind 2 vs kind 3)

*If this function vanished, would any ordinary user query stop compiling to
SQL?*

* Yes → platform semantics (kind 2). Own it in Java, from the spec.
* No → a program (kind 3). Compile it.

`pureToSqlQuery` vanishing means no SQL: kind 2. `toPostgresModel` vanishing
breaks only its own tests: kind 3. Facts the compiler itself produces about a
model or a plan (primary-key inference, lineage scans, activities, plan nodes)
are kind 2 — Java-stamped, exposed as ROWS in the system store
(`docs/METAMODEL_AS_RELATIONS_HOMEWORK_2026_09_02.md`); programs over those rows
are kind 3.

## 3. The prelude — the one thing the platform ships that looks like Pure

The platform ships **declarations only**: class and enum shapes (with their
`<<equality.Key>>` and defaults) and native function signatures — the
language's type surface. They are copied from the spec with receipts and pinned
by tests (`NativeFunctionTest`, the native catalog, signature verification
against the real `.pure`). They contain no bodies. This is not "Pure code in
the platform"; it is the surface the compiler accepts.

**One narrow exception: Pure QUERIES over the platform's own system tables.**
The metamodel navigation functions and the enumeration-mapping lookups in
`SystemMetamodel` are views over seeded rows, compiled by the same compiler as
user queries. The test: no control flow on a computed value, no recursion, no
effects — if a proposed body needs an `if` on a computed value, it is a program,
not a view, and does not belong in the platform.

## 4. The compiler compares; the database computes

The unroll (`UserCallInliner` + `LiteralUnroll`) lets programs over spelled
literals compile: a recursive function applied to a literal tree unrolls while
its argument strictly descends; `match`/`if`/`map`/`filter` act on the literal
before their bodies are rewritten. What keeps this from being an interpreter is
one rule:

**STRUCTURAL (the compiler may decide — the answer is visible in the text):**
which `match` arm a literal's class picks; a spelled field; an unspelled field
is the class's default/empty; `instanceOf`/`cast` on a literal's class; list
shape over spelled lists (`at`, `first`, `last`, `tail`, `init`, `slice`,
`size`, `isEmpty`, `reverse`, `concatenate`, `zip`, `fold`, `map`, `filter`
applied per element); identity of two spelled scalars of the SAME kind
(`'a' == 'a'`, `x in ['case','if']`, enum == enum, spelled-integer compare such
as `size() == 1`); a spelled enum's name; `newMap`/`get`/`pair`/`groupBy`/
`keyValues` over spelled pairs; `enumValues`; `assert(true, …)` is a no-op;
short-circuit `and`/`or` on a spelled boolean.

**COMPUTED (the database's — a SQL scalar in the value):** anything that
produces a NEW value: `toLower`, `toUpper`, `trim`, `startsWith`, `endsWith`,
`contains`, `replace`, string `+`, arithmetic, `joinStrings`, `format`,
`toString` on numbers and dates, cross-kind equality (`1 == 1.0`).

A decision that hangs on a computed value stays undecided: both outcomes are
carried and the database picks. The residual forms, cheapest first:

| residual | example | SQL |
|---|---|---|
| scalar | `if(x->startsWith('"'), \|…, \|x)` | `CASE` of VARCHAR |
| list | `if(schema == 'default', \|[], \|[schema])->concatenate(name)` | `CASE` of VARCHAR[] |
| shape | `if(cond, \|^DynaFunction(castBoolean…), \|$d)` — same class both sides | `CASE` of two structs (same layout ⇒ same type; polymorphic fields are JSON) |
| conditional membership | `[e1,e2]->filter(x \| computed(x))->isNotEmpty()` | each element kept under its own condition; list SQL over the guarded list |

Recursion terminates regardless of which branch runs: the descent measure is the
literal argument's size.

**Guard:** the fold set of `LiteralUnroll` is pinned to compare-only operations
(`LiteralUnrollLedgerTest`); a Java fold that produces a new scalar value is a
tenet violation, not an optimization.

## 5. Rules of the road

1. **Engine and pure checkouts are spec.** Never loaded, never executed.
   Signatures and class shapes verified against them with receipts.
2. **The platform ships a prelude of declarations only.** Shapes with keys and
   defaults, native signatures. No bodies. Pinned so they cannot drift.
   *Amended 2026-09-04 (option S, `docs/DECLARATIONS_HOMEWORK_2026_09_04.md`):
   behavior is curated, shapes are data.* Native signatures are REGISTERED
   (we own their meaning). Library SHAPES are GENERATED from the spec files
   by demand (`core/src/main/java/com/legend/builtin/Prelude.java`,
   `PreludeGeneratorTest`) — the corpus's type positions, the platform's own
   Java names and the admitted program libraries (`Corpus.LIBRARY_FILES`)
   decide what is pulled, with the closure of what those declarations name;
   constraints are not loaded. By hand stay only: the m3 bootstrap (m3.pure
   is a graph file — `tools/m3shape.py` is the receipt), the primitives, the
   platform carriers, and the system-store-coupled shapes named in
   `Pure.java`. Trigger for the next step (verbatim `.pure` files loaded per
   build instead of a generated Java file): a shipped legend-lite without
   the checkouts, or a second module needing its own prelude selection.
3. **Platform Pure is limited to views over the system tables.** No control
   flow on computed values, no recursion, compiled by the same compiler.
4. **Every native means one SQL lowering rule.** A native that Java evaluates
   instead of lowers is a bug — inside the unroll too.
5. **A program that walls is compiler work.** A missing structural fold, a
   missing residual form, a missing lowering, a loading rule that did not admit
   the file. Never Java that knows the program's name (the frozen
   string-dispatch count, `ObservabilityGuardrailTest`, guards the last part).
6. **Residue is named, one line each, and never a port.** Recursion over
   row-backed trees (tier 2: a recursive CTE, when a second witness pays for
   it), effects, IO, reflection over the live graph.

## 6. The decision procedure when a corpus test walls

1. Is the missing thing a **native**? → one Java lowering rule to SQL.
2. Is it **platform semantics** (the deletion test says yes)? → design it in
   Java from the spec; never run or port the engine's Pure.
3. Is it a **program**? → it is input. Admit its file to the model (the corpus
   loader's job), and fix the compiler: fold, residual, lowering.
4. Is it a **declaration** (class, enum, native signature)? → the prelude, with
   its keys and defaults, with a receipt against the real `.pure`.
5. Is it **residue** (rule 6)? → name it; do not port it.

## 7. Applied to the work in flight (2026-09-03)

* debugPrint (9) and toPostgresModel (21) are programs. The natives that shadow
  `newState`/`convertElement`/`convertSelectSqlQuery`, the native
  `ModelConversionState` class, `MetamodelWalk`'s conversion arms,
  `MetamodelSteps` and `StatementExecutor.constructNode/constructOp` are
  deleted by the toPostgresModel leg.
* The ≈ 60 SQL-node and relational-metamodel classes and enums the program
  references go into the prelude WITH their equality keys (the engine files
  carry 143 + 22 keys; ours carried 4).
* The nine Java string folds added on 2026-09-03 violate rule 4; they are
  replaced by the conditional-membership and shape residuals and deleted.
* `testConvertJoinTreeNode` and `testConvertSelectSQLQuery` recurse over the
  mapping's join-tree ROWS: rule-6 residue (tier 2) until a second witness.
