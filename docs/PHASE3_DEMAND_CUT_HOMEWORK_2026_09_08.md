# Phase 3 — the demand cut: the prelude becomes platform vocabulary only (homework, 2026-09-08)

Status: HOMEWORK, written after batch 153 under the ratified order (`PRELUDE_MODULE_HOMEWORK` §6a: phase 3 before phase 2).
Read `PRELUDE_MODULE_HOMEWORK` §2 (T1 sharpened: vocabulary by USE, never provenance; T2: the graph is what programs declare
or bring in BY FILE) first. Decide §4 before code.

## 1. What phase 3 does, in one paragraph

Today the generator puts a class into the prelude for two reasons: the platform's Java uses it, or the corpus names it. The
second reason is the one T2 forbids. Phase 3 keeps only the first. The 253 engine classes the corpus alone names, and the
71 the closure pulled in behind them, leave `prelude.pure`; the corpus tests still need them, so they enter the corpus tests'
GRAPH the way any program's library does — by file. The prelude that remains is legend-pure's platform packages plus the 59
engine-declared interface classes the platform's Java constructs, reads, or names in a native signature, plus their closure.

## 2. Measured (2026-09-08, from `docs/PRELUDE_MODULE_CENSUS_2026_09_08.tsv` and a Java-use scan)

**The 59 Java-demanded engine classes — every one is real use, none is a comment mention:**

| use | classes |
|---|---|
| named in a native signature (Pure.java) | 29 (13 signature only, 12 signature + code, 3 signature + system metamodel, 1 all three) |
| constructed / read / dispatched in Java code | 34 |
| named by the system metamodel's Pure source | 15 |

So the "tighten Java demand to construct / read / signature" step changes nothing today; it becomes the generator's rule
(comments and Javadoc are excluded from the scan) so that it stays true.

**The 324 classes that leave (253 corpus-demanded + 71 closure) come from 64 engine files.** Two kinds:

| kind | files | classes | examples |
|---|---|---|---|
| DECLARATION files — no functions at all | ~25 | ~215 | the SQL protocol metamodel (93 + 26 declarations, 88 wanted), the protocol template `m3.pure` (33), `router/store/metamodel.pure`, `executionPlan.pure`, `core_service/metamodel.pure`, `_extra.pure`, `testable.pure`, `data.pure` |
| MIXED files — declarations beside engine-internal functions | ~39 | ~109 | `tds.pure` (17 decls, 58 fns), `sqlQueryToString`'s files (14 decls, 75 fns), `executionPlan_generation.pure` (6 / 29), `router/store/cluster.pure` (9 / 25), `mft.pure` (24 / 8), `toJSON.pure` (2 / 45), `mappingExtension.pure` (4 / 40), `constraintsExtension.pure` (2 / 32) |

The declaration files are trivially admissible. The mixed files are the decision.

## 3. Mechanics (what changes where)

1. **Generator (`PreludeGeneratorTest`)**: demand = Java use only — Pure.java signatures, the system metamodel's source, FQN
   literals in `src/main/java` CODE (comment lines excluded); the corpus and library scan is deleted from the generator; the
   closure is unchanged (declarations only, §9a). `-Dprelude.census=1` shows the new prelude (expected ≈ 143 + closure).
2. **Corpus loader (`Corpus`)**: the 64 files enter the corpus tests' graph by name. HOW is §4 D1.
3. **`Compiler.withoutPreludeShadows`**: unchanged; its receipt list shrinks to the platform-vocabulary classes the engine tree
   also declares (§4 D2).
4. **Census**: the 18 engine-body rows of `SPEC_BODY_CENSUS` §10.2 follow their classes to the graph; what they become there is
   measured, not predicted.
5. **Pins**: `PreludeGeneratorTest` parity, the census pin (shrink-only 22), the hand-class count, `PlatformTypesDriftTest` —
   all re-pinned to the measured numbers, each with the reason.

Slices, lanes EXACT between them: **3a** the generator's demand rule and the census of what leaves (no loader change: the
prelude shrinks only after 3b admits). **3b** admissions by file family — the declaration files first (SQL protocol, protocol
template, plan/router/service metamodels), then the mixed files one family at a time. **3c** the census re-measured and the
receipts re-listed.

## 4. Decisions (yours)

**D1 — How a class in a MIXED engine file enters the graph.** Three ways:

- (i) **Declarations-only admission.** A named file contributes its classes and enums; its functions do not enter. Receipt:
  the corpus needs the SHAPES (it constructs and reads them); the functions beside them are the engine's own machinery —
  plan generation, the SQL printer, routing — which this platform implements in Java or walls by name (WORLD_MAP question 4).
  Taking those functions in would rebuild batch 147's "24 admitted engine files". One small mechanism: the loader keeps
  Class/Enum elements of a named file. RECOMMENDED.
- (ii) **Whole-file admission**, functions included, poisoned until called. Honest to "by file", but every corpus graph then
  carries ~700 engine-internal functions in its overload sets; natives own their names (batch 147 rule 2), so most are inert,
  but each one that out-ranks a platform function by specificity is a lane surprise, and the census would then have to type
  them. Not recommended.
- (iii) **Strict reading of the design's per-file rule** (a file is admitted only if every function in it is a program): the
  mixed files are not admitted, and the tests that need their classes wall. That loses passes for the sake of a rule about
  PROGRAMS applied to SHAPES. Not recommended.

**D2 — T4's end state is a stable list, not zero.** The homework said the receipt list "burns to zero in phase 3". It cannot:
`SQLExecutionNode` and its kind are platform vocabulary (Java constructs them) AND the engine tree declares them, so the graph's
copy will always exist and always yield. The honest end state: the receipts are exactly the platform-vocabulary classes the
spec also declares, listed, pinned shrink-only, and each one dissolves only when its Java use does. Ratify the rewording.

**D3 — Where the admissions live.** Beside `Corpus.LIBRARY_FILES` (programs, whole files) a second named list for
declaration admissions (`Corpus.SHAPE_FILES`, file → classes and enums). Two lists, two meanings, both by file. Mechanical;
listed so it is not invented silently.

**D4 — The 18 census rows.** Under (i) their bodies still cannot find the engine functions they call — those functions are
not admitted — so they stay red, now as GRAPH walls rather than prelude rows, and the census pin drops from 22 toward 4 (the
vocabulary names and the typer gap). That is the honest outcome the closure decision (§9a) predicted; confirm it is wanted.

## 5. Expected lane effect

The 324 classes are parsed from the same spec text either way; a class moving from the boot layer to the graph changes its
container, not its shape. Movement, if any, comes from (a) a lite test naming one of the 324 bare (batch 153 made that an
error already, so none should), (b) `TypeClassifier.classDef`'s catalog-first order — irrelevant, they were never catalog —
and (c) under D1 (ii) only, engine functions joining overload sets. Under (i) the prediction is pass counts unchanged.
