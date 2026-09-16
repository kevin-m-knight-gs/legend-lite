# The normalizer package re-cut — handoff for a fresh session (2026-09-16)

**Status: NOT STARTED. Nothing here is agreed work yet** — it is written down so it can be
picked up cold, in order, without re-deriving the evidence.

**Where it comes from.** The mapping-normalizer audit of 2026-09-15
(`docs/MAPPING_NORMALIZER_AUDIT_2026_09_15.md`, section 12 and
`findings/16-architecture-review.md`) closed with a design section, FIXLIST **P7**. Every
correctness, one-owner, hygiene and test row of that audit has since been burned down —
eleven legs, records in `docs/GATES.md` under "Audit fix A2…A11", every one with both corpus
lanes EXACT and the chain green. P7 is what is left, and it was explicitly NOT agreed, so it
waits here.

---

## 0. Read this first

- **Behaviour must not change.** Every item below is a shape change. That means the corpus
  can only tell you that you broke nothing; it can never tell you the work was good. Do not
  expect a row to turn green. The verdict of a re-cut leg is: lanes EXACT, chain green, and
  the file-level acceptance test in §2 actually satisfied.
- **Do NOT split by line count.** The package is in its current shape because a previous
  split moved blocks when a file hit a size ceiling. The proof is arithmetic: audit fix A7
  removed **123 unused imports** from four normalizer files — a decomposed file does not
  inherit its parent's imports. Cutting by size again reproduces exactly this.
- **The size ceiling is already handled.** `CodeShapeGuardrailTest` has an EMPTY file
  allowlist since A7; `MappingNormalizer.java` (2,842 lines) sits under the general ceiling
  with no exception. There is no deadline pressure to split anything.
- **Current baseline** (measure again before starting; these are 2026-09-16):

| | |
|---|---|
| normalizer package | 10,825 lines, 28 files |
| `MappingNormalizer.java` | 2,842 |
| `JoinChainEmission.java` | 1,062 |
| `UnionSynthesis.java` | 960 |

---

## 1. Why re-cut at all

The package answers several different questions in one file, and the seams between those
questions are invisible: helpers are `static`, the shared state travels as parameters, and
call direction runs both ways between the pieces that were split out. Concretely, the audit's
"noun test" (does this file name one thing?) fails on the big file, and one construction is
literally written three times (§2.1).

The payoff is not lines. It is that a reader can find the one place a decision is made — the
same property every one-owner leg of the burndown was buying.

---

## 2. The extractions, in value order

Each is a separate leg: extract, run both lanes, run the chain, record in `docs/GATES.md`,
commit named files, push, watch CI. None needs a new test to prove a behaviour (there is no
new behaviour); each needs the **acceptance test** below.

> **Acceptance test for a real extraction (the audit's, and it is a good one):** the new
> file's import block is NOT the parent's, and the calls do not go both ways. If the new file
> imports what the parent imports, or the parent calls in while the new file calls back, a
> block was moved, not a joint cut. Revert and think again.

### 2.1 `ClassBindingBuilder` — the binding construction, written three times

`MappingNormalizer.java:311`, `:377` and `:495` each build a `ClassBinding` by the same
three-way shape (Relational / Operation / Pure), with the same arguments in the same order.
The first two are the non-root and root arms of one loop, 66 lines apart, and differ only in
the `root` flag and whether aggregate-view facts ride along; the third is the function-form
door. One builder taking (class mapping, function FQN, root flag, ledger, declared keys)
replaces all three.

**Why first:** highest duplication, zero behavioural surface, and it makes the next two
extractions legible.

### 2.2 `MainTable` — one noun, five methods plus two overloads elsewhere

In `MappingNormalizer.java`: `inferMainTable` (`:1384`), `inferMainTableQuiet` (`:1433`),
`hasMainTable` (`:2298`), `mainTableDefOf` (`:2328`), `mainTableOf` (`:2360`). The view side
already lives elsewhere — `ViewRelation.inferViewMainTable` (`:440`, `:445`, two overloads) —
and that split is itself the argument: two files answer "which table backs this set?".

### 2.3 `RowProjection` — the constructed-object terminus

`translatePmToField` (`:1966`), the three embedded materializers `materializeEmbedded`,
`materializeOtherwiseEmbedded`, `materializeInlineEmbedded`, and `buildNewInstance` (`:2713`)
/ `buildNewInstanceToOne` (`:2755`) plus the `CtorField` record: everything that turns a row
into the `^Target(...)` instance.

**Do not sweep in the filter family.** `applyFilter`, `applyDirectFilter` and
`applyJoinMediatedFilter` sit inside the same line range but belong to pipeline construction,
not projection. Cutting by line range instead of by job is the exact mistake this document
exists to prevent.

### 2.4 The "Low-level helpers" banner — NOT what the audit said

The audit called this block `PureSpecBuilder`, "the 130-line low-level helpers" that
"construct Pure expressions and know nothing about mappings". **That is wrong for today's
code, and it was checked on 2026-09-16.** The banner at `MappingNormalizer.java:2485` opens a
block of exactly six functions:

| function | what it is |
|---|---|
| `resolveViewRefsInJoin` (two overloads) | view-reference resolution inside a join condition |
| `viewChainReaches` | view-reference reachability |
| `requireNonViewTarget` | a view-target wall |
| `determineTargetTable` | which table a join condition lands on |
| `containsTargetColumnRef` | a `{target}` marker probe |

Four of the six are VIEW-reference work and belong with `ViewRelation.java`, which already
owns that subject; the other two are join-target determination and belong with
`JoinChainEmission.java` or a small `JoinTarget` file. There is no Pure-expression builder
here to extract. Take the banner as the seam it marks, not as the file name the audit gave
it.

### 2.5 `BuildMode` — the strict/tolerant sentinel

Strict versus tolerant is carried as `wallSink == null` (8 references, `:182`, `:188`, `:204`
and the driver's arms). The policy is real and documented (B4: the driver alone applies
strict/module); it deserves a name rather than a null check on a collecting parameter.

### 2.6 The constraint every extraction meets: the shared `Pipeline`

`Pipeline` carries **12 mutable fields** (the accumulating expression, the slot registries,
the routed-property table, the inline stack, the ledger) and **26 sites** in the package take
a `Pipeline` parameter. Anything that mutates it cannot be extracted into a file the parent
merely calls, because the mutation is the return value.

So for each extraction, decide FIRST which of the three it is:

1. **Pure of the pipeline** (`MainTable`, most of `RowProjection`): extract freely.
2. **Reads the pipeline, returns a value** (`determineTargetTable`): extract, take the
   pipeline as a parameter, one-way calls.
3. **Mutates the pipeline** (the hop emitters): extraction here is a REFACTOR OF THE STATE,
   not a file move. Either leave it, or change `Pipeline` to return a new instance — a
   separate decision, and out of scope for a first pass.

If an extraction turns out to be kind 3 halfway through, stop and revert rather than
threading the mutable object through a new seam.

---

## 3. Two deeper items under the same heading

### 3.1 Reshape `NormalizationFacts` (audit P7-2)

`MappingDefinition.java:71-74`: `mixedUnions`, `unionKeyThreads`, `unionMembers` and
`routedTargetClasses` are separate maps keyed by the same class FQN, three of them describing
ONE union. Group them by subject, push per-binding facts onto the binding itself, and give
the remaining maps typed keys — the same move `PoisonKey` made in audit fix A6 (commit
`eb9835e21`), which is the template: a sealed key made a whole class of "written but
unreadable" bugs impossible to reintroduce.

**Risk:** low-moderate. The facts ride the compiled artifact and are read by the resolver and
the metamodel seeds; a key change is mechanical but touches several readers.

### 3.2 Make the earlier phase total, then delete Phase E's resolvers (audit P7-3)

The normalizer still resolves some names itself, leniently, where Phase D would throw:
the wildcard association end (`AssociationSynthesis.resolveAssociation`), the unqualified
store ref (`StoreSubstitutionRewrite.qualifyStoreRefs`), the signature-mangle path, and the
lenient `findDatabase`. With those gone, an ArchUnit rule can ban the normalizer from
reaching into `compiler.spec`, `resolver`, `lowering`, `exec` and `sql`.

**This is a project, not a leg.** The audit names the hard part and it is real: downstream
code keys on UNRESOLVED spellings, so making Phase D total is a contract change, not a
refactor. Do not start it inside a re-cut arc. The fallback ledger (`FallbackLedgerTest`,
audit fix A10b) already states these are out of the normalizer's empty-answer funnel and are
Phase-D debt, so they cannot rot quietly while this waits.

### 3.3 Also recorded, not part of the re-cut

`findings/08-lineage-rediscovery.md`: `lineage/ScanRelations.java` (~2,800 lines) re-reads the
raw parse surface with its own include walker and main-table inference, reading ZERO stamped
facts, and it feeds what the user SEES in an execution plan. Eight of the facts it re-derives
are already on the binding. The genuinely missing ones — per-set property-mapping shape, the
ordered join-name list, the class-mapping filter — are the real gap in the stamped-facts
design. Sizeable; independent of the re-cut; worth its own decision.

---

## 4. Guards that will fire, and what they mean

Expect these during a re-cut. None is noise; each is the repo telling you something.

| guard | what trips it |
|---|---|
| `CodeShapeGuardrailTest.deadPrivateMethodsOnlyShrink` | a helper left behind after its callers moved |
| `CodeShapeGuardrailTest` file ceiling | a new file over the general limit — cut smaller, do not add an allowlist row (the allowlist is empty on purpose) |
| `ShadowWalkerCensusTest` | a moved call site changes a walker's per-file count — ratchet the row in the SAME commit |
| `FallbackLedgerTest` | a moved `MissProbe` site changes the per-file census, or a bare `orElse(null)` appears |
| `ParkedWorkLedgerTest` | you touched code an anchor watches (`docs/PARKED_WORK_LEDGER.md`) |
| `OwnCorpusParityTest` | any new test model joins the own corpus — re-pin `MIN_MATCHED` |
| `ArchitectureTest` | a new file in the wrong package, or a dependency direction the rules ban |

Run the parity test ALONE before the chain (`mvn -o -q -pl parser-equivalence test
-Dtest=OwnCorpusParityTest`) and re-pin; otherwise gate 8 eats a full chain cycle.

---

## 5. Suggested order and cost

| leg | item | risk |
|---|---|---|
| 1 | `ClassBindingBuilder` (§2.1) | low |
| 2 | `MainTable` (§2.2) | low |
| 3 | `RowProjection` (§2.3) | low-moderate |
| 4 | the "Low-level helpers" banner: view refs to `ViewRelation`, join-target to `JoinChainEmission` (§2.4) | low-moderate |
| 5 | `BuildMode` (§2.5) | low |
| 6 | `NormalizationFacts` reshape (§3.1) | moderate |
| — | anything that MUTATES `Pipeline` (§2.6 kind 3) | not a file move — do not start it as one |
| — | Phase-D totality (§3.2) | a project; decide separately |

Legs 1 and 2 are the ones I would take first if only two were done: the duplication dies and
one noun gets a home.

---

## 6. How to run one leg (the whole loop, verbatim)

Nothing here is new discipline; it is `docs/GATES.md`'s chain, written out so a cold session
does not have to assemble it.

```bash
cd ~/legend/legend-lite
. tools/oracle-roots.sh                 # resolves $R1/$R2 and FAILS on pin drift

# 1. after each edit: compile, then the unit witnesses of what you touched
mvn -o -q -pl core test -Dtest='MappingNormalizerTest,OneIndexTest' \
    -Dsurefire.failIfNoSpecifiedTests=false

# 2. both corpus lanes — the row verdict (install core FIRST or you test a stale jar)
mvn -o -q -pl core install -DskipTests
for b in duckdb h2; do
  mvn -q -o -pl spec test -Dtest=MinimalCorpusTest -Dsurefire.excludedGroups= \
      -Drcorpus.backend=$b "$R1" "$R2"
done
# expect: roster duckdb EXACT: 108 fail of 2613 / roster h2 EXACT: 444 of 2613

# 3. the parity floor ALONE (or gate 8 eats a chain cycle), then re-pin MIN_MATCHED
mvn -o -q -pl parser-equivalence test -Dtest=OwnCorpusParityTest \
    -Dsurefire.failIfNoSpecifiedTests=false "$R1" "$R2"

# 4. the full chain, ONCE, in the background; the tree is FROZEN until it reports
GATES_LOG=/tmp/recut/gates.log GATES_PARALLEL=1 caffeinate -dims tools/allgates.sh
```

Then: append a record to `docs/GATES.md` (why, what landed, rows, chain with per-gate times,
batch size), `git add` the NAMED files (never `-A`; and in zsh pipe a file list through
`xargs git add` — an unquoted variable is one pathspec and stages nothing), commit, push, and
watch CI with `tools/ci-watch.sh <full sha>`. A docs-only push starts no run: the gate
workflow ignores markdown.

**Single-test iteration** while debugging a lane failure:
`-Drcorpus.test=<substring>`, and `LEGEND_LITE_DUMP_SQL=1` to see the emitted SQL.

---

## 7. Pointers

- `docs/MAPPING_NORMALIZER_AUDIT_2026_09_15.md` — the audit, with a correction box on P0-0.
- `docs/mapping-normalizer-audit-2026-09-15/findings/FIXLIST.md` — every row, with the four
  corrections the burndown wrote back into it (P0-6, P3-4, P4-2, and the `DynaFnArms` row).
- `docs/mapping-normalizer-audit-2026-09-15/findings/16-architecture-review.md` — the
  package decomposition argument and the one-owner table.
- `docs/PARKED_WORK_LEDGER.md` — PARK-1..4, the debts with anchors.
- `docs/GATES.md` — "Audit fix A2…A11", each with rows, per-gate times and what was refuted.
- `docs/LEGACY_ROUTES_AS_COMPOSITION_2026_09_13.md` — the arc the audit was auditing.

**A warning worth repeating.** Four audit findings were wrong and the corpus is what proved
it. If a P7 item's stated cause looks obviously right, build it anyway and let the lanes
judge — and if they refuse it, record the refutation next to the claim instead of quietly
dropping it.
