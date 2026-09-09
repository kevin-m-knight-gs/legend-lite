# Compile everything — the burn-down program (homework, 2026-09-09)

Status: HOMEWORK, written after batch 167 (the prelude-as-module program closed) at the user's question: "why do we
think we are done if there are still 19 things we cannot compile?" Read before any work on the census, the prelude
module, missing platform functions, or the post-processors. Companion: SYSTEM_PRELUDE_DESIGN §10 (the tenets),
PRELUDE_MODULE_HOMEWORK §9a (closure), PHASE3_DEMAND_CUT D4, SPEC_BODY_CENSUS §10.

## 1. The question, and the two rulings that are NOT in conflict

The user asked whether two ratified rulings conflict: (a) the prelude's closure follows DECLARATIONS only; (b) the
platform registers the functions it references. They do not, because they are about different things:

| ruling | what it governs | what it says |
|---|---|---|
| closure = option B (PRELUDE_MODULE_HOMEWORK §9a) | what the MODULE contains | declarations closed over declared types; bodies carried verbatim; functions NEVER in the prelude; a body resolves where it RUNS |
| T1 vocabulary (SYSTEM_PRELUDE_DESIGN §10) | which CLASSES enter | every spec class the platform's Java names, whole, verbatim |
| native rule (SYSTEM_PRELUDE_DESIGN §3) | which FUNCTIONS the platform implements | a spec `native function` = a Pure.java signature + one lowering or a NAMED wall |

There is no ruling "register every function a prelude body names". That phrase is a memory note of the assistant's,
not a decision; the ratified answer for the census rows was D4 (honest receipt). What the rules jointly imply:

- the module's DECLARATIONS are closed (the generator checks it);
- the module's BODIES may name functions the module does not carry — they type where they run;
- a NATIVE a body names must be registered (signature + lowering or named wall) — today's gap: `mutateAdd`;
- a PROGRAM function a body names (`function … { body }` in an engine file) enters the world by FILE, never by
  registration (T2: engine modules are programs).

So "compile everything" is a MEASUREMENT statement: every Pure body the platform can be asked to run, typed in the
world it runs in, with every failure NAMED in a bucket. Today's census measures the module's bodies at BOOT — a world
that, by the closure ruling, cannot contain the functions those bodies name. The pin asks the wrong world.

## 2. The populations (what "everything" is)

| population | today | measured by |
|---|---|---|
| P1 legend-pure's 9 platform packages (spec functions, derived bodies, constraints, PCT tests) | 0 failures of what loads; 6 files do NOT LOAD | `SpecBodyCensusTest` |
| P2 the prelude module's bodies (derived properties, constraints of the 480 declarations) | 19 red, at boot | `SpecBodyCensusTest` (pin 19) |
| P3 the engine files the corpus imports (`Corpus.LIBRARY_FILES` whole, `Corpus.SHAPE_FILES` declarations-only) | never censused as bodies; 21 of the 108 corpus failures are "unknown function" (13 names) | the lanes only |
| P4 the system metamodel's own Pure (SystemMetamodel.java) | typed every boot; census rows when it fails | `SpecBodyCensusTest` |

P1 is done modulo its load walls. P2 is the 19. P3 has no census. P4 is measured.

## 3. The buckets (by the spec's marking — never by our convenience)

Every failing body lands in exactly one bucket; each bucket is a shrink-only pin; DONE = every bucket's rows NAMED.

| bucket | meaning | what closes a row |
|---|---|---|
| B1 NATIVE-UNREGISTERED | the body names a spec `native function` absent from Pure.java | register: signature + lowering, or signature + named wall (the native rule) |
| B2 PROGRAM-NOT-LOADED | the body names an engine `function` with a body whose file is not in the measured world | measure where it runs (§6); if the file is in the corpus's world and still fails → B4 |
| B3 WALLED-BY-DECISION | the user walled the body's subject (the SQL printer / post-processors, ENGINE_MACHINERY_WALLS) | the post-processor design session (§8) decides: compiler pass, or permanent named wall |
| B4 TYPER/NORMALIZER GAP | the world is right and the body still does not type (e.g. `olap`: non-let intermediate statements in an inlined body) | a leg with the row as witness |
| B5 PARSER LOAD WALL | the file does not parse, so its bodies are not even counted | a parser leg per grammar form |
| B6 T4 SHADOW | a class the graph also declares; the prelude wins silently | burn: the graph's copy is the same text (delete the receipt) or a real divergence (a modeling error to fix) |

## 4. The 19 today, bucketed, with their end state

| rows | body | names | bucket | end state |
|---|---|---|---|---|
| 1 | `Extension.serializerExtension` | `mutateAdd` (spec: `native function`) | B1 | Pure.java signature + named wall (a mutation native; no SQL meaning) |
| 4 | `SchemaState.extend/join/rename/restrict` | `removeAll` (engine collectionExtension.pure, `function` with body) | B2 | types once the census loads that file; else B4 |
| 1 | `SchemaState.columnValueDifference` | `containsAll` (same file) | B2 | same |
| 2 | `SchemaState.groupBy/join` | `createSchemaState` (engine tdsSchema.pure — the class's OWN file) | B2 | same; the class's own file is the first file the census must load for its bodies |
| 2 | `ExternalFormat{From,To}PureDescriptor$constraint$configurationType` | `checkSuperType` (`<<access.private>>` in externalFormatContract.pure) | B2 | same; note `access.private` — the constraint lives in that file, so the private call is legal there |
| 1 | `Extension.fetchSerializerExtension` | `forgivingPathToElement` (engine metaExtension.pure) | B2 | same |
| 1 | `SchemaState.olap` | inliner: non-let intermediate statements | B4 | a normalizer leg (the census row is the witness) |
| 7 | `DbConfig` ×5, `DynaFunctionToSql.toSql`, `SQLResult.toSQLString` | the SQL printer (`sqlQueryToString`, `DynaFunctionRegistry`, `getLiteralProcessorForType`…) | B3 | the post-processor session (§8) |

MEASURED (batch 168, the census by running world): **B1 1** (`mutateAdd` — an ENGINE-declared native), **B2 5**
(`removeAll` ×3, `containsAll`, `forgivingPathToElement`: collectionExtension.pure / metaExtension.pure are in no loaded
world, the corpus's included — D2), **B2b 5** (`createSchemaState` ×3, `checkSuperType` ×2: DEFINED in the running world
and still unknown — the FINDING below), **B3 7**, **B4 1**. The boot-world number (19) stays as the boot fact.

**FINDING (batch 168): "bodies resolve where they run" has no mechanism.** `NameResolver` resolves a body's names against
the names KNOWN when it runs; the module's bodies are resolved once, at boot, before any program's files exist; an
unresolvable bare name passes through and fails at typing in every later world. So a module body can never see a
program function, however the world is built. Bucket **B2b NAME-FROZEN-AT-BOOT** names this. The leg — **step 1b, a
Compiler leg**: the boot resolution records each module body whose bare names passed through, with its section import
scope; `Compiler.buildModel` re-resolves exactly those bodies alongside the graph's names (the mirror image of
`NameResolver.resolveAlongside`, which resolves a graph alongside the boot names) and swaps them into the merged model.
Witness: the census's running-world pass, B2b 5 → 0 (or a named B4).

## 5. The other fronts under "compile everything" (owned by no program today)

| front | size | bucket | note |
|---|---|---|---|
| parser load walls in legend-pure | 6 files: cast.pure, toMultiplicity.pure, addColumns.pure, new.pure, m3.pure (`^Instance` at top level), precisePrimitives.pure | B5 | they HIDE rows: P1's 0 is a floor. First, because every other count is judged by it. |
| missing platform functions in the corpus | 13 names / 21 failures: `routeFunction` ×5, `toRelation::transform`, `contextHasFlag`, `compileLegendGrammar`, `transformPlan`, `dataTypeToCompatiblePureType`, `functionReturnType`, `isExecutionOptionPresent`, `newMultiValueMap`, `toJSONStringStream`, `byPassRouterInfo`, `header`, one test extension | B1 or B2 by marking — classify FIRST | today they are counted only as corpus failures |
| the post-processor walls | 5 FQNs in `UserCallInliner.ENGINE_MACHINERY_WALLS` | B3 | USER 2026-09-03: post-processors are COMPILER PASSES, never recursion in SQL — the design session is owed |
| the T4 receipt list | 135 names at the foot of prelude.pure | B6 | homework said "burns to zero in phase 3"; batch 155 re-labelled it "stable" — that was a quiet redefinition, undone here |
| platform-owned suppressions | `PlatformTypes.isPlatformOwnedFunction` (a handful) | named already | keep: the native IS the definition; list them in the ledger once |

## 6. The measurement change (the mechanism this program adds)

`SpecBodyCensusTest` today: parse legend-pure's 9 platform packages + the boot layer; type every body; the module's
bodies are typed in the BOOT world. Change: type each module body in the world it RUNS in —

1. For a module class from an ENGINE file, the world = boot + that engine file (declarations AND functions, loaded
   as a program by file, exactly as `Corpus.LIBRARY_FILES` loads a corpus file) + the files it imports transitively
   by section import. The census records, per row, the world it was typed in.
2. For a module class from LEGEND-PURE, the world = boot + the platform packages (today's world).
3. A row that fails in its running world is real: B1 (a native by marking — register), B4 (typer gap), or B3 (walled).
4. The report prints one line per bucket, and the pin is per bucket, shrink-only. LANDED batch 168 with the pins
   `B1 <= 1`, `B2 <= 5`, `B2b <= 5`, `B3 <= 7`, `B4 <= 1` (B5 = the load-wall pin 6; B6 not yet measured). CORRECTED:
   "B2 == 0 always" was wrong — a B2 row is a file no program loads (D2 decides), never the census's fault.

This is a census change, not a platform change: no Java arm, no registration, no reshaping. It is the same principle
as "bodies resolve where they run" applied to the measurement.

## 7. The order and the batches (each: pins → tools/allgates.sh once in the background → records → commit)

1. **The census by running world + the buckets** (§6): one batch; expected 19 → B1 1 / B2 0 / B3 7 / B4 ≥1 (the
   B2 rows either type or move to B4 with a named gap). No pass-count change.
1b. **Re-resolve module bodies at graph build** (B2b → 0): the Compiler leg the finding names; witness = the census.
2. **`mutateAdd` registered as a named wall** (B1 → 0): a Pure.java line; the ledger names it.
3. **The 13 corpus "unknown function" names classified by marking**: a table in this document (§5) with each name's
   spec declaration; then B1 legs (register + lower or wall) and B2 files (admit by file to the corpus's LIBRARY_FILES
   or SHAPE_FILES per T2). Expected: some of the 21 corpus failures pass; the rest become named.
4. **The six parser load walls** (B5): one grammar form per batch, each with the spec file as the witness. These
   come early because they hide rows — but after 1–3 because those are cheap and make the denominator honest first.
5. **The post-processor design session** (B3, §8): a decision, then one batch per pass.
6. **T4 burn** (B6): a census of the 135 (same text → delete the receipt; divergent → the modeling error named), then
   `Compiler.withoutPreludeShadows` shrinks to the real vocabulary the engine tree also declares.
7. **The B4 legs**, one per row, the census row as the witness.

## 8. Decisions the user owns (this document does not decide them)

- **D1 — the post-processor session.** The five walls are the engine's SQL post-processing machinery. The 2026-09-03
  ruling says they are compiler passes (replaceTables / nonExecutable / CTE as IR passes). Decide: which passes the
  platform implements as passes (then their Pure bodies are SPEC only, walled by decision with a receipt naming the
  pass), and which stay walled as engine machinery. Until decided, B3 = 7 rows + 5 FQNs, named.
- **D2 — "everything" includes the engine's PROGRAM bodies?** §6 types module bodies in their running world. It does
  NOT propose censusing every body of every engine file the corpus imports (P3 whole) — that is the harness plan's
  territory (END_TO_END phases 3–6) and would re-open the 253-file demand question. Confirm the boundary: P3 bodies
  are measured by the lanes (a failing test names its missing function), not by a census.
- **D3 — the finish line.** Proposed: zero UNNAMED failures — every bucket pinned, every row named, B2 == 0 always.
  Not "zero rows": B3 and B5 can carry decided rows indefinitely, with reasons.

## 9. What this document corrects in the record

- "The 19 census rows as vocabulary" (memory `system-prelude-tenets`, batches 155–167): WRONG phrasing — they are
  bodies measured in the wrong world, not vocabulary to register. Corrected in memory with this document.
- "The T4 list is stable" (batch 155): a redefinition; the list burns (B6).
- "The prelude-as-module program is complete" (batch 167): TRUE for the module MECHANISM (steps 1–5 of its homework);
  NOT a claim that everything compiles. This document is the program for that.
