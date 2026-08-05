# Architecture audit — 12 feature areas (2026-08-05)

Companion to [`CORPUS_STUDY_2026_08_ALL.md`](CORPUS_STUDY_2026_08_ALL.md).

> **New here? Start with [`CORPUS_BURNDOWN_HANDOFF.md`](CORPUS_BURNDOWN_HANDOFF.md).**

**The question asked:** for the features that *pass*, did we build the right design — or
did we fix point tests with point solutions?

Twelve independent agents, one per feature area, each instructed to census the concepts in
the code, compare against legend-engine's own vocabulary, hunt for branches keyed on corpus
shapes, and name the shapes that would break. Docs were banned as evidence.

---

## §1 — Verdicts

| area | verdict |
|---|---|
| Milestoning | sound core, accreted edges |
| GraphFetch | sound core, accreted edges |
| Mapping composition | sound core, accreted edges |
| Union / set implementations | sound core, accreted edges (subtype dispatch alone: point-solutions accreted) |
| Filters & null semantics | sound core, accreted edges + one layer inversion |
| Joins & navigation | sound core, accreted edges + one doctrinal error at the centre |
| SQL generation & dialects | sound core, accreted edges (engine-text layer: point-solutions accreted) |
| Relation API & TDS | sound core, accreted edges |
| Plan generation | sound core, accreted edges **around a missing centre** |
| Type system & inference | sound core (kernel), wrong resolver, unsound generics |
| Aggregation | sound core, accreted edges |
| **Lineage & test-data generation** | **point-solutions accreted** |

Eleven of twelve share a verdict. That consistency is itself a finding: no area is
incoherent, and none is finished.

---

## §2 — The unifying diagnosis

Six agents converged on this independently, without seeing each other's work:

> **Where legend-lite lacks a first-class concept, it encodes the concept into a NAME, and
> then re-parses that name at every consumer.**

| missing noun | encoded as | independent decoders |
|---|---|---:|
| subtype narrowing | `stc_<Fqn>___<prop>` | 4 |
| embedded set identity | `emb__path__sub` | 4 |
| join tree node | prefix concatenation (`b_` + `c_` + `pk`) | 5 key spaces |
| aggregate demand scope | head-name string | shared with `#fN`/`#cN`/`#dN` |
| window desugar column | `<name>__wpN` | 1, collides with user columns |

Two have proven decode bugs: a package name containing `_` decodes to the wrong class, and
`prop#p` is minted outside the class whose javadoc claims to be *"the ONE owner of the
`'#'`-suffix convention"* — and is unparseable by it.

**A second form of the same disease:** one engine concept split into two divergent
implementations behind boolean flags. One `RelationTree` became two trees behind `tdgMode`,
`perWebChildren`, and a `tdsRoots`/`buildRoots` split — each flag calibrated against a
different golden. The concept is missing, so its variants became configuration.

### Three secondary patterns, each in 6+ areas

**A rule keyed on a proxy for the real property.**

| proxy | what it stands for |
|---|---|
| `FILTER_POS` (syntactic position) | is either operand optional (multiplicity) |
| projection-vs-filter position | does this consumer tolerate row multiplication |
| `body.size() > 1` | does the body bind a `let` |
| `columns().size() == 1` | is this a cell collection |
| score **sum** across parameters | lexicographic rank per parameter |
| `p.name().indexOf('.') >= 0` | `propertyPath->isEmpty()` |

Each proxy is right on the corpus and wrong in general.

**Two mechanisms for one concept — and the correct one is already in the tree.** Found
~16 times; the single most reliable predictor of a cheap fix:

- `GraphEmission.java:3054-3071` narrows subtypes correctly; the TDS path does string surgery
- `SqlFn.IS_DISTINCT` exists and renders; equality hardcodes DB2's OR-expansion
- `Aggregates.java:65-70` arity-scopes correctly *with a comment explaining why*, fourteen
  lines below `family(SUM, "plus")` which doesn't
- `PlanText.scalarTypeBlock` renders the right thing 50 lines from the `typeBlock` that doesn't
- `foldProjectionCopies:723` uses the oriented condition; its sibling
  `foldExtraSubIdentities:646` uses the raw one
- `Ddl.createTableStatementText` already emits `setUpDataSQLs`' golden text character for character
- `Lowerer.stripQuotes` is declared and never called

**The wall shares the gate with what it guards.** The aggregation demand scan's two loud
walls take the same `userVar` gate that just failed — structurally incapable of catching
what they exist to catch. Corpus-confirmed: a silently eaten `sum()`.

---

## §3 — Per-area headlines

**Milestoning.** Class doc claims "the ONE propagation rule"; there are two, dispatched by
whether an explicit date exists. Zero corpus name-pins. Two hypothesized gaps dissolved
into exact engine parity.

**GraphFetch.** A user `sortBy` under a graphFetch terminal is **silently discarded** —
proven by byte-identical emitted SQL with and without it. The checked envelope hard-codes
`"path": []` and walks only the root class's hierarchy.

**Mapping composition.** No `EmbeddedRelationalInstanceSetImplementation` → four
independent copies of the "drill into `^Inner(...)`" loop, disagreeing on depth, Otherwise
semantics, and owner class. Cleanest corpus-shaping proof in the sweep: `Inline[setId]` in
an *included* mapping fails, and the only test exercising it is `<<test.ToFix>>` —
*"our reach ends exactly where the enabled corpus ends."*

**Union / subtype.** Verdict on `subType`-as-column-name: **"an accident, not an
architecture."** The engine replaces the *set*; we serialize `(castTarget, property)` into
a string and reconstruct meaning by string surgery in four places. `GenericTypeReflection`
returns the base class where the engine returns concrete leaves — contradicting its own
javadoc, which claims it "stays loud."

**Filters & null semantics.** A layer inversion: null compensation is a hardcoded SQL
expansion at the *lowering* layer, keyed on lowered-node shape plus a ThreadLocal, where
the engine has a semantic MIR node keyed on Pure multiplicity and expanded by the dialect.
Violates our own written layer contract. Also: the engine's `IsolationStrategy` — a named,
three-valued, documented concept — has **no counterpart**, and the runner parses
`forcedIsolation` and then discards it, so three tests of three strategies run as one test
three times.

**Joins & navigation.** **Join type is not a property of a join in our IR.** Every
mapping-implied join is a literal `"LEFT"`; the mapping language's `(INNER)` is parsed and
dropped. The consequence is self-documented at `NavMaterializer.java:181-188` — demand is
*withheld* so the wrong join never materializes, with a named test's row count as the
justification. Join elision has no cardinality guard.

**SQL generation & dialects.** Two layers built to different standards. The execution
dialect is a coherent design — every `H2` override cites a probe against the real jar. The
engine-text layer is a *decompiler* with ~14 shape recognizers that fold DuckDB-shaped IR
back toward engine text. `AnsiSqlRenderer` is DuckDB with an ANSI name — its own source
says so. Dialects compose by *subclassing the previous dialect*; the engine overlays a
table onto a default. `EngineStyleDB2` and `Composite` emit `legend_h2_extension_split_part`.

**Relation API & TDS.** Validated by legend-pure's own PCT suite at **348/348** — an
externally authored corpus you cannot overfit to by accident. The defect class is one
decision: `Relation<T>` is erased to `T`, so a relation and one of its rows are the same
type, and every site needing the distinction guesses from syntax. Corpus-confirmed:
`$tds.rows->at(0).values->at(1)` returns row 1 of a 1-row relation.

**Plan generation.** Accreted edges *around a missing centre*: the plan is not a value.
Three parallel representations of one plan that contradict each other. `resultColumns` is
re-derived at print time by walking the rendered FROM-tree back to store definitions —
~180 lines of archaeology carrying six of PlanText's ten walls, reconstructing what the
lowering already knew. And 13 of its 30 FAILs are **one SQL token** with the entire
envelope byte-identical.

**Type system.** The unification kernel plus schema algebra is a genuine generalization —
`rename`/`extend`/`select`/`groupBy`/`over`/`pivot` all get their schemas from one
evaluator with zero per-operator code. But overload resolution is structurally weaker than
real Pure's and cannot be patched into correctness: scores are **summed** across parameters
where the engine is **lexicographic per index**, deferred slots contribute nothing rather
than ranking, and there is no rollback. Two-line repro: **the program's meaning depends on
the order two overloads appear in the source file.** Separately, generic supertype
arguments are destroyed — `Class X extends Pair<String, TableAlias>` yields a raw type
variable as a *value*.

**Aggregation.** Emission is a real design; the demand scan in front of it is a shape
enumeration, and it is the sole entrance. Five notions of "aggregate demand" that disagree.
`family(SUM, "plus")` registers arity-blind, so all five binary `plus(a,b)` overloads become
reducers: `sum() + 1` **walls** while `- 1` and `* 2` work. Invisible because the corpus
writes `*10` and `/` on sub-aggregates and never `+`.

**Lineage & test-data gen** — the only `point-solutions accreted`. Contains the sweep's
worst artifact:

```java
if (runtimeVariant && (!tdsRooted(...) || a[1].contains("joinleft_"))) { advisory++; continue; }
```

`a[1]` **is the expected answer string.** The decision whether to check the answer is made
by grepping the answer. Twenty-one of forty "passing" `scanRelations` tests pass with half
their asserted content unchecked — and the suppressed half is *structurally* different, not
just differently labelled. Separately, 20 of 40 passes are half-verified (a static assert
verifies, its runtime twin goes silently advisory).

---

## §4 — Evidence against corpus-pinning

Stated for balance, because it changes what the remediation should be:

- **348/348** on legend-pure's own PCT relation suite — externally authored.
- **Zero** control-flow branches keyed on corpus test names, across four independently
  swept areas (relation, filters, joins, aggregation). Test names appear only in comments,
  as witnesses — the legitimate use.
- `CalendarAgg`'s 92/92 is earned: structural detection (`startsWith(PKG) && args==4`), no
  name lists, loud default, shared helpers factored.

**legend-lite does not pin on test identity. It pins on shape enumeration** — subtler,
harder to grep for, and precisely what a corpus-driven build produces.

---

## §5 — Recommended sequence

Ordered so that each step is falsifiable and none manufactures a false green.

1. **Null-safe equality → MIR node keyed on multiplicity, spelled by the dialect.** Low
   risk; the node exists and renders. Deletes the `FILTER_POS` ThreadLocal rather than
   rehousing it. Largest corpus yield in the study.
2. **Arity-aware reducer registration.** Mechanical; makes the `ANY_VALUE` hole visible as
   what it is.
3. **Rebind `userVar` at every lambda boundary in the agg demand scan.** ~15 lines; closes
   the worst silent-wrong-rows class *and* makes the walls total.
4. **Overload resolution: grade, don't score; search, don't commit.** Port real Pure's
   `TypeMatch`/`FunctionMatch` lexicographic comparison and its rollback loop
   (`FunctionExpressionProcessor.java:131-231`). Do it alone, on its own commit, with a
   full sweep — it changes the winner for every multi-overload call.
5. **Give a join a kind** — after (6), or the row-count failures move rather than resolve.
6. **Cardinality-based join elision** instead of a construct whitelist. High risk, high
   value; expect FAIL to rise before it falls.
7. **Type-variable freshening** — free, unblocks polymorphic function pointers (579 corpus
   occurrences).
8. **One `RelationTree`; retire `perWebChildren` in the same commit as the view fix.**
9. **Delete the lineage advisory arms first**, accept ~40/49 → ~19/49, then build the
   plan-derived tree. The other order manufactures the same false green.

**Do not** attempt (4) and (6) together. **Do** land the Tier-1 one-liners from the corpus
study first — they are free and several are verified.
