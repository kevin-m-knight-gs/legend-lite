# JAVA EVICTION — the tenet's completion program

**Charter (2026-08-17, user-directed):** the foundations pause made the referee honest;
this program makes tenet #1 ("Java orchestrates, the DATABASE executes") **TRUE AND
MECHANICAL**. The deep audit's finding stands: the tenet never had a ratchet, so it was
negotiable under pressure — F4.4 reverted, HostEval reframed, A13/JsonSourceFrame filed.
This program builds the ratchet FIRST, then evicts in leverage order.

## 0. Rules (inherited from the foundations program — they worked)

- One leg per commit batch; full `tools/allgates.sh` chain per batch; tree frozen mid-chain.
- The corpus sweep is the REFEREE. Zero undeclared family deltas; declared deltas get
  verdicts in `docs/BURNDOWN_EXPLANATIONS.md` in the same commit.
- Revert, don't patch forward. Probe before building. Measure before claiming.
- The ledger (`JavaEvalLedgerTest`) is SHRINK-ONLY from the day it lands: a new
  Java-evaluation site fails the build; an evicted one forces the ledger row to shrink.

## 1. The boundary — what counts as "Java in the exec path"

**EVICT (must reach zero):** any Java code that COMPUTES a value or COMPOSES text that a
test assertion (or product consumer) observes as the result of executing a Pure
expression — evaluation, rendering, row shaping, realization of source data.

**The decision rule (sharpened at E3, user-ratified 2026-08-17):**
- Java that decides **values, rows, types, or cardinality at query time** → EVICT, no
  exceptions (semantic-drift risk; composition breakage — E2's filter bug was exactly
  this). E1, E2, E4.a–c live here.
- Java that turns **model text into typed constants at plan-build time** → COMPILATION,
  permitted — but the carrier into SQL must be TYPED AND LOSSLESS (the string grid was
  the F7.3 defect, not the parse). Do not stuff strings into the database just to select
  them back out: a `data:` literal is model text, not an external source; the DB-side
  read channel (`SourceUrl` + `read_json_objects`) is for data that genuinely lives
  outside the plan (`file:`/remote).
- Java that formats text **about** plans (metamodel TEXT) → the engine-parity census
  decides; PERMANENT is a legitimate verdict, not a failure.

**PERMANENT-ALLOWED (registered, each with a written justification):**
- **Egress decode** (`Executor.fetch/unwrap/latticeKind/decodeAny`): decoding a carrier the
  DATABASE produced, by declared type/carrier contract. No computation.
- **`LiteralFold`**: bare String/Boolean literal unwrap — the engine's own
  ConstantExecutionNode, differential-pinned (`ConstantPlanParityTest`).
- **Comparison layer** (wireEquals/hostEquals/TdsEquivalence/renderedTextEquals):
  VERIFICATION is the harness's job; it consumes two sides, never produces a result.
- **`JsonAssertCanon.sortByKey`**: re-creates the TEST'S OWN canonicalization idiom over a
  metamodel that never executes through SQL (revisit only if the JSON metamodel
  re-platforms).

## 2. Phase E0 — the ratchet (BUILT WITH THIS CHARTER)

`core/src/test/java/com/legend/JavaEvalLedgerTest.java`: every EVICT surface is a row
(file → explicit evaluator-method regex → pinned count, exact-match, shrink-only), and the
PERMANENT-ALLOWED register is spelled in the same file so the boundary is one artifact.
Definition of done for the program: **every EVICT row reaches zero and is deleted.**

Measured EVICT surface at charter time (~5.4k lines of Java evaluation):

| Row | Surface | Size |
|---|---|---|
| E4 | `exec/HostEval.java` — the interpreter | 928 lines |
| E4 | `exec/MetamodelWalk.java` — walk handles/nav | 1,603 lines |
| E4 | `MetamodelSteps.java` — walk vocabulary | 234 lines |
| E4 | `StatementExecutor` walk family (planWalk/walkProp/walkFilter/planModel/…) | ~25 methods |
| E4 | `plan/PlanText.java` + `AggAwareActivities.java` — metamodel TEXT composed in Java | 888 + 265 lines |
| E4 | `exec/Ddl.java` engine-TEXT generators (`*StatementText`, `setUpDataSqlsText*`) | 5 methods |
| E4 | `exec/DbMetaData.java` — metadata from a shadow replay | 161 lines |
| E2 | `Executor.shapeRow` many-valued row explosion (A13) | 1 branch |
| E3 | `resolver/JsonSourceFrame.java` — JSON parse → VALUES realization | 2 methods |
| E1 | PCT `ExecuteLegendLiteQuery.formatAsTds/formatValue/formatDate` — wire text in Java | ~500 lines |

## 2b. The codebase-wide classification (deep-audit sweep, 2026-08-17)

The same audit that widened the PCT rows swept the corpus harness and every remaining
package. The complete classification — nothing unclassified:

**NEW EVICT rows found by the sweep (all ledgered):**
- `server/serial/CsvSerializer` (113 lines) + `JsonSerializer` (50) — the PRODUCT HTTP
  serializers compose the observable result text in Java; **CsvSerializer re-spells the
  RFC-4180 escape that F4 declared spelled-once-in-SQL** (a real one-owner miss the
  foundations close did not catch).
- `exec/ResultJson` (137 lines) — the `/engine` JSON payload's values serialized in Java
  (the GRAPH path already rides DB `json_object`; the tabular path does not).
- `testdatagen/TestDataGenerator.renderCsv/headerCase` — A5/A6 moved hash+scrub into SQL;
  the row/comma/newline CSV ASSEMBLY is still Java text composition.

These form **E5 — product wire + testdatagen text** (effort M): route the product
serializers and the testdatagen CSV through the platform's own render vocabulary
(`lowering/Render.csv` exists; JSON via `json_object`/`Executor.stream`). At E5 build time
decide per surface whether engine parity justifies a PERMANENT registration instead (the
engine serializes its HTTP results in Java too) — but the RFC-4180 duplication is
indefensible either way: at minimum the escape delegates to the one owner.

**E5 LANDED 2026-08-17.** The product wire is PLAN-RENDERED (`Compiler.executeWire` →
`WireRender.wrap/rows` → `Render.csvWire`/`jsonWire`/`jsonWireRows`): CSV composes in SQL
through the ONE RFC-4180 owner (`escapeCsv`/`cell` — CRLF endings, header of escaped
names, zero rows = header only); JSON rides `json_object` per row (the DATABASE'S value
policy and RFC 8259 escaping), `string_agg` snapshot with the pinned `[]` empty form, or
per-row streaming where Java writes only array punctuation. `ResultJson` DELETED (the
Java JSON value policy died with it); `CsvSerializer`/`JsonSerializer` shrank to format
METADATA (id/contentType/streaming — `serialize` no longer exists on the registry
surface); the `/execute` envelope parses DB-built text (ingress) with columns as a typed
plan fact; the dead `/sql` SELECT branch removed (executeSql's contract is
statement-only). Testdatagen: the ROW TEXT is SQL (display casts + `'---null---'` +
comma joins in the projection, outer ORDER BY over the projected display columns);
`renderCsv` is dead — `csvEnvelope` assembles only catalog-metadata envelope lines.
`headerCase` re-registered PERMANENT (identifier-display casing over catalog names — the
decision rule's metadata-text class). Rows retired/pinned zero in the ledger.

**PERMANENT-ALLOWED (registered with justifications; no counts needed — their own guards
already pin them):**
- **Harness comparison layer**: `EngineTestExecutor` (compare/wireEquals/hostEquals/
  renderedTextEquals/Eval), `H2Verify` (norm/goldenRowsCompare/mirror), `TdsEquivalence`,
  `PlanAsserts`, `ConnEquality`, `JsonAssertCanon` — verification CONSUMES two sides,
  never produces a result; reorder/tolerance sites pinned by HarnessDisciplineTest and
  the LL_TOL/ord-leniency counters.
- **Harness ingress adaptation**: `SourceSubst` (+`ElqSplice`, CORPUS_FOLD,
  `TestDataGenForm.inlineReads`, `AssertLoopForm`/`RuntimeIfForm`, `bindParam`) —
  transforms TEST TEXT into platform inputs before execution; single-owner substitution
  (F3.2) with the A8 base-grammar pin.
- **Egress decode**: `Executor.fetch/unwrap/latticeKind/decodeAny`,
  `H2Verify.carrierList/coerceTemporal` (byte[]-branch sole caller) — decoding carriers
  the DATABASE produced, by declared contract.
- **`LiteralFold`** — ConstantExecutionNode parity, differential-pinned.
- **`RawSqlBoundary`** — INPUT-text translation of corpus-authored SQL, caller-set pinned
  by RawSqlLedgerTest.
- **`DynamicPivot`** — two-phase orchestration at the execution seam (reads pivot KEYS to
  BUILD SQL; F7.6 recorded it as the target design).
- **Compilation layers** (`parser/`, `compiler/`, `resolver/`, `normalizer/`,
  `lowering/`, `sql/`, dialects) — building SQL from Pure IS the product; Java
  orchestrates here by definition.
- **Metamodel emitters for product surfaces** (`DiagramService`, protocol emitters) —
  transform the PARSED MODEL, no execution involved.

**Verified by READ, not by name (the sweep's completeness notes):**
- `LineageForm`/`LineageRelationsForm` — recognize the engine's scanColumns/scanRelations
  TEST FORMS and route to the REAL analyzer (compile-time lineage; V1.6's Java pipeline is
  gone).
- `AssertLoopForm` — the source evaluates THROUGH THE PIPELINE (`EngineTestExecutor.eval`);
  values lift to literals and re-enter checkAssert. Ingress, not evaluation.
- `RuntimeIfForm` — the condition evaluates through the pipeline; branches re-enter the
  statement loop.
- `ReflectAsserts` — the VERDICT comes from the TYPER (F3.3); the host side only navigates
  the lambda literal's M3 structure to pose the typing question (E4.c-adjacent recognition,
  no value computed).
- `exec/` residue (`Row`, `QueryPlan` compile-only seam, `PostProcessBoundary`,
  `TimingLedger`, `ResultShape`, `Column`, `ExecutionResult`, `H2Settings`, `CsvSeed`
  SQL-building) — containers/orchestration.
- `rcorpus/Runner` + `RelationalCorpusRunner` — zero JDBC value reads; orchestration.
- `parser-equivalence` — zero execution surface (text/parse comparison vs the oracle).
- `nlq` — LLM orchestration + eval scoring of LLM output; no Pure-execution surface.

Every file in the repo is now one of: compilation/orchestration, a ledgered EVICT row, or
a registered PERMANENT row. Nothing unclassified.

## 3. The legs, in order

### E1 — PCT renderer → Lowerer ROOT MODE (F4.4 done right) · effort L

The recorded design from the reverted attempt: post-hoc plan rewriting is impossible
(~10 shape classes recorded in FOUNDATIONS_PLAN F4.4); the Lowerer grows a ROOT MODE
(`withStreamingGraphRoot` precedent) in which the PCT wire print (fixed-3-millis+0000
DateTimes, TDS text, cell forms — all measured and recorded) is EMITTED BY THE PLAN.
Blocked findings to honor: abstract-Date slots need `typeof()` reflection (OutputCol slot
claims measured unreliable); minimal-subsec forms demote deephaven columns to STRING.
**Scope correction (2026-08-17, user question caught the gap):** E1 covers the WHOLE
composition family, not just the TDS formatter — `createTDSResult`, `purePctName` (header
spelling incl. pivot quoting), `multText`, `stripTrailingZeros` (subsecond PRINT PRECISION
decided in Java), the print/scale decisions inside `toCoreInstance`, plus the two adaptation
sites: `remapErrorMessage` (H4 known weakness — the prefix strip erases the error CLASS; fix
or register PERMANENT with the H4 note as its justification) and `reEscapeStringLiterals`
(ingress text surgery on the interpreter-provided expression). The bare CoreInstance BRIDGE
(`handleScalar`/`handleCollection`/`structToInstance`/`classInstance` + type plumbing) is the
PCT framework's ADAPTER CONTRACT — scalar asserts evaluate in the interpreted runtime and
demand CoreInstances — so it survives E1 as PERMANENT-registered, but ONLY once every print
decision has moved out of it. The extension file is size-pinned whole (1,190 lines) so the
leg's progress is a number.

**Acceptance:** the composition family DELETED (name row → the adapter-contract residue,
re-pinned with justifications); the file's size pin drops to the adapter core; PCT 1109/1109;
PCT is orchestration-only.

**LANDED (2026-08-17):** the plan emits the PCT wire text. `Render.pctTds` (header = a
compile-time constant from the typed schema; cells = the pure print forms in SQL — fixed-3ms
'+0000' DateTimes, `floatRepr` floats, always-quoted Variant JSON, bare `null` via explicit
IS NULL dispatch since DuckDB's `concat` SKIPS nulls); `PctTdsWrap` composes the wrapper AFTER
`DynamicPivot.staticize` (post-hoc plan REWRITING stays impossible — this is shape-agnostic
subselect WRAPPING); pivot plans get a LIMIT-0 metadata probe (`PctProbe`, exec seam) for
concrete NAMES, while their TYPES ride structurally: `Pivot.Using` now carries its
lowering-typed result slot, and the decode is an `endsWith` against that closed minted-alias
vocabulary — loud wall on zero-or-many (the first draft's `lastIndexOf` name-parsing was the
exact anti-pattern this program kills, caught in review; the G7 H2 ledger had already caught
the backend-typed fallback spelling SUM columns Decimal-on-H2/Integer-on-DuckDB). ORDER BY
lifts onto the wrapper; keys the projection dropped carry out as hidden `_pct_ord`
projections. `formatAsTds`/`formatValue`/`purePctName` DELETED; the adapter keeps only the
option toggle and the verbatim hand-over. PCT 1110/1110 on DuckDB AND within the G7 H2
ledger; corpus scoreboard byte-identical; full chain GREEN.

### E2 — TDS-to-many slot + A13 row explosion → SQL · effort M

One design, both halves: a to-many project lambda emits the engine's
union-subselect/LEFT-join row explosion IN SQL (the engine rule is already documented at
the `shapeRow` branch), and OutputCol reconciles with the emitted slot.
**Acceptance:** the `shapeRow` explosion branch DELETED; `testConcatenateWithFilter`
flips green (+1, the one CSV-render residue); corpus zero-delta otherwise.

**LANDED 2026-08-17.** The explosion lowers in `Lowerer.tryComputedColumns`:
detection is CONCATENATE-ROOTED (`Fold.isManyScalarCol` — exact FQN
`meta::pure::functions::collection::concatenate`; the first draft keyed on typed
multiplicity alone and broke 28 association to-many navigations already exploded
by the join machinery). The shape is the shared two-layer lateral
(`Fold.lateralElem`: correlated list expr as inner local column, UNNEST over that
column — DuckDB rejects a correlated arg directly under select-list UNNEST), the
same skeleton E1's PctTdsWrap uses. One many-column per project; a second is the
engine's own wall. `shapeRow`'s explosion branch deleted after a probe showed
ZERO firings on the full referee; `arrayAsList` deleted; the Executor ledger row
is pinned 0. Gains beyond acceptance: `testConcatenateFlatWithOtherProperty`
also flips (2337 → 2339); 'sql-text side' H2 declines 56 → 57 (LEFT-LATERAL not
raw-H2-replayable, tied to the gained test). Carrier-purity: pre-dialect
`SqlFn.UNNEST` sites CONSOLIDATED 13 → 12 (pin tightened) — the collection-mapper
and instance-literal explosions now ride the same two helpers.

### E3 — JsonSourceFrame: evict the lossy string grid · effort M

*(Re-scoped mid-build, user-ratified: the original leg moved the payload parse INTO the
DB via SourceUrl — but a `data:` payload is model text, and shipping it into the database
as one giant string for the DB to cut apart fails the decision rule. The eviction target
is the UNTYPED STRING GRID, not the build-time split.)*

**Acceptance:** no JSON VALUE ever materializes in Java (`Json.parseAll`/`cellText`
deleted); the DATABASE does all value interpretation; the F7.3 walls (null-string
collision, structured-under-scalar) DISSOLVE with the grid; XStore tests green.

**LANDED 2026-08-17.** The frame is a one-Variant-column VALUES relation: Java does
SCISSORS ONLY — `objectTexts` cuts the payload into per-object TEXT spans lexically (a
string-aware top-level brace scan handling array / single-object / the engine's
concatenated row-stream; `JsonSourceFrameArrayTextTest` pins it) — and each span rides as
a raw-JSON Variant cell (`tdsCell`'s variant arm → `CAST('…' AS JSON)`), plus the hidden
`FRAME_ORDINAL` column. Every property binds as a typed extraction IN SQL over the
registered natives (`variant::navigation::get` + the `to(@T)` cast seam + `toOne`,
conform-by-emission; `toOne` erases value-wise so an absent key stays a NULL cell).
`cellText`, `classSource`, and `Json.parseAll` (its last consumer) are DELETED; the
ledger row pins zero. VALUES stays dialect-portable — nothing new falls to the H2
declines. Full DuckDB referee: scoreboard byte-identical at 2339 (corpus-neutral).

### E4 — HostEval re-platform · effort XL (its own phased arc)

The mountain: ~4.1k lines across the interpreter, walk handles, metamodel text, and the
metadata shadow. Phased by the F0.3 census families, one family per batch, each with an
instrument-first firing count:

- **E4.a store navigation** (`schema`/`table`/`view` handles) → the platform's OWN
  `information_schema` surface (the H2-backend vision doc already names it).
- **E4.b metadata natives** (`fetchDb*`, `DbMetaData`) → same information_schema surface;
  the shadow-H2 replay dies.
- **E4.c metamodel instances & walks** (constructNode/constructOp, plan walks) → struct
  values / grid→relation (the STRUCT design is landed platform vocabulary).
- **E4.d metamodel TEXT** (`PlanText`, `Ddl` *Text generators, `AggAwareActivities`) →
  either relation-valued reads the DB composes, or registered PERMANENT rows with
  engine-parity justification (the engine renders plan text host-side too — this
  sub-family may legitimately end as ALLOWED; the census decides, not the prose).
- **E4.e the interpreter core** (`eval`/`property`/fold/map/filter arms) — shrinks as
  a–d remove its demand; whatever remains at the end is either deleted dead or moved
  behind the harness-installed seam (the F0.3 consequence note).

**Acceptance per sub-leg:** the family's ledger rows shrink; corpus/PCT referee zero-delta
or §0.4-declared.

**E4 firing census (2026-08-17, `LL_HOST_ARM_COUNT` over the full DuckDB referee —
4,119 firings):** store-nav (E4.a) fires exactly TWICE (`schema` 1 + `table` 1);
metadata natives (E4.b) ~35 (`fetchDbColumns` 14, `fetchDbTables` 6, `fetchDbSchemas` 5,
`fetchDbPrimaryKeys` 5, `executeInDb` 5); everything else (~4,080) is the structural/
native vocabulary serving those chains plus the typeInference construction family
(`TypedNewInstance` 295, `TypedUserCall` 236, `TypedVariable` 864, …). The E4.e residue
is therefore bounded by the chains E4.a–d feed it.

**RATIFIED ADJUDICATION (census-backed; user-ratified 2026-08-17 — "engine-exact H2
is another lower target"):** the census partitions the remaining interpreter demand
into two channels, each with a ratified end-state:

1. **The DB-VALUE channel (EVICT, stands):** fetchDb grid-read chains and executeInDb
   READ chains — database-produced values folded/indexed in Java (`.rows->fold(
   concatenate(values->at(k)))`, `.value('NAME')`, emptiness reads). **E4.e = compile
   the small grid-read vocabulary (rows/values/columnNames/at/value/fold-concatenate/
   first/size/emptiness) over the E4.b catalog queries INTO SQL**, after which the
   structural arms lose their last DB-value demand.
2. **The METAMODEL-TEXT channel → ENGINE-EXACT TEXT IS A LOWER TARGET** (not a
   registered second speller — the user rejected wholesale PERMANENT): the platform's
   main lowering is ALREADY engine-text-exact by construction (the corpus's thousands
   of SQL-text goldens pass through the one Lowerer + renderer); the hand-written
   islands catch up to it. Concretely:
   - **DDL golden text** = the ENGINE_TEXT flavor of the ONE `Ddl.createTable`
     generator (batch 1 — landed below).
   - **Hand-built engine-metamodel trees** (`DynaFunction`/`Literal`/
     `TableAliasColumn`/`SelectSQLQuery`) = a CONVERTER onto our SqlExpr IR, rendered
     by the one renderer's engine-text target (a SECOND ENTRY POINT into the same IR;
     one-way; closed vocabulary with loud walls). MetamodelWalk's parallel
     mini-renderer dies. Engine quirks the goldens pin become engine-text dialect
     rules, adjudicated by the referee quirk by quirk.
   - **Residual register (small):** instance CONSTRUCTION evaluation (building typed
     constants from `^Class(...)` literals — compilation by the §1 rule) and the
     plan-text ENVELOPE wrapper. No SQL or DDL is spelled twice anywhere.

**E4.d batch 1 LANDED 2026-08-17 — the DDL flavor merge.** `Ddl.Flavor {H2_EXEC,
DUCK_EXEC, ENGINE_TEXT}`: one create-table generator (flavor dispatches column-name
quoting, separator, type spelling, nullability, PK clause), one drop spelling (the two
were byte-identical), one flavored type-spelling switch whose per-target deltas are the
only flavor lines. `dropTableStatementText`/`createTableStatementText`/`engineSpell`
DELETED; the ledger pins the dead names at zero. `setUpDataSqlsText*` now compose
through the one generator.

**E4.b LANDED 2026-08-17.** The shadow-H2 replay is DELETED: `fetchDbSchemas/Tables/
Columns` are catalog queries over the AMBIENT session's `information_schema` (F6.6's
rule — the database the raw writes actually seeded), identifier columns `upper()`'d IN
SQL (the H2 engine-parity spelling the goldens assert), `SQL_TYPE_NAME` mapped from
`data_type` in the projection. `fetchDbPrimaryKeys` is MODEL facts (the ambient DDL
deliberately omits PK constraints — milestoned re-seeds): literal rows from the
connection's store (the database reference found inside the connection argument's typed
tree, include-closure merged), existence-filtered against the LIVE catalog in SQL.
`replayStream` deleted; the DbMetaData EVICT row retired (residual file = catalog-query
orchestration + egress decode by contract). Grid values still flow into interpreter
fold/at chains — E4.e's residue, pinned by the HostEval rows.

## 4. Definition of done

1. Every EVICT ledger row at zero (row deleted from the test).
2. The PERMANENT-ALLOWED register is the complete residue, each row justified.
3. Corpus + PCT referees green through every leg; declared deltas verdicted.
4. Tenet #1 re-worded in AGENTS.md/TENET_CHARTER from aspiration to INVARIANT, citing the
   ledger as its enforcement.

Dependency order: **E0 → E1 → E2 → E3 → E5 → E4.a → E4.b → E4.c → E4.d → E4.e.**
(E5 slots after E3: it reuses E1's root-mode render vocabulary and is independent of E4.)
E1 first because it retires a whole harness (PCT) to orchestration-only and builds the
root-mode machinery E2 reuses; E4 last because a–d's vocabulary (information_schema,
struct values, root-mode rendering) is exactly what its arms need.
