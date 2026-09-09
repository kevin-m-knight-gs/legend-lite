# Phase 3 — the demand cut: the prelude becomes platform vocabulary only (homework, 2026-09-08)

Status: HOMEWORK, written after batch 153 under the ratified order (`PRELUDE_MODULE_HOMEWORK` §6a: phase 3 before phase 2).
Read `PRELUDE_MODULE_HOMEWORK` §2 (T1 sharpened: vocabulary by USE, never provenance; T2: the graph is what programs declare
or bring in BY FILE) first. Decide §4 before code.

## 1. What phase 3 does, in one paragraph

Today the generator puts a class into the prelude for two reasons: the platform's Java uses it, or the corpus names it. The
second reason is the one T2 forbids. Phase 3 keeps only the first. The 253 engine classes the corpus alone names, and the
71 the closure pulled in behind them, leave `prelude.pure`; the corpus tests still need them, so they enter the corpus tests'
GRAPH the way any program's library does — by file. The prelude that remains is legend-pure's platform packages plus the
engine-declared interface classes the platform's Java constructs or names (a native signature, the system metamodel), plus
their closure — fewer than the 59 first counted (§2).

## 2. Measured (2026-09-08, from `docs/PRELUDE_MODULE_CENSUS_2026_09_08.tsv` and a Java-use scan)

**The 59 Java-demanded engine classes — CORRECTED after the user's pushback ("usage is not itself a reason").** A first
scan counted any FQN in Java code; re-reading every site by the ratified test (construct / native signature / system
metamodel — a comparison against a constant, or a read of a field of a value a PROGRAM built, is NOT use):

| kind of use | classes | verdict |
|---|---|---|
| named in a native signature | 29 | vocabulary |
| named by the system metamodel's Pure source (and not above) | ~12 | vocabulary |
| CONSTRUCTED by Java as the platform's own output (`new EnumValue("…JoinKind", …)`, `TypedNewInstance(RelationalCSVTable…)`, the JSON checker's key-value / array nodes, the desugar's default `RelationalExecutionContext`) and not above | ~8 | vocabulary |
| dispatch-only or read-only (`TdsOlapRank`, `BasicColumnSpecification`, `ColumnSpecification`, `TDSColumn`, the connection post-processors and mappers, `JsonModelConnection`, `ModelChainConnection`, the H2 datasource specification, the JSON leaf kinds) | ~14 | NOT vocabulary — leave for the graph unless the closure of a vocabulary declaration names them (`TDSColumn` ← `TDSRow`) |
| TO CHECK in 3a: `RelationalDatabaseConnection` and the datasource / post-processor classes — lite's OWN grammar (`###Connection`) compiles into instances of them; if the platform's grammar produces the value, the platform constructs it | ~6 | decided by the check |

**Mechanism consequence.** A text scan of `src/main/java` cannot tell "constructs" from "compares". Phase 3a replaces it: the
generator's Java demand = Pure.java native signatures + the system metamodel's source + an EXPLICIT list in `PlatformTypes`
of the classes Java constructs, each entry a receipt naming the site (read from source, as `handDeclaredFqns` reads
Pure.java). The list is the vocabulary; the review is the test.

**The 324 classes that leave (253 corpus-demanded + 71 closure) come from 64 engine files.** Two kinds:

| kind | files | classes | examples |
|---|---|---|---|
| DECLARATION files — no functions at all | ~25 | ~215 | the SQL protocol metamodel (93 + 26 declarations, 88 wanted), the protocol template `m3.pure` (33), `router/store/metamodel.pure`, `executionPlan.pure`, `core_service/metamodel.pure`, `_extra.pure`, `testable.pure`, `data.pure` |
| MIXED files — declarations beside engine-internal functions | ~39 | ~109 | `tds.pure` (17 decls, 58 fns), `sqlQueryToString`'s files (14 decls, 75 fns), `executionPlan_generation.pure` (6 / 29), `router/store/cluster.pure` (9 / 25), `mft.pure` (24 / 8), `toJSON.pure` (2 / 45), `mappingExtension.pure` (4 / 40), `constraintsExtension.pure` (2 / 32) |

The declaration files are trivially admissible. The mixed files are the decision.

## 3. Mechanics (what changes where)

1. **Generator (`PreludeGeneratorTest`)**: demand = Pure.java native signatures + the system metamodel's source + the explicit
   constructed-vocabulary list in `PlatformTypes` (each entry a receipt naming the constructing site); the text scan of
   `src/main/java` and the corpus / library scan are deleted from the generator; the closure is unchanged (declarations only,
   §9a). `-Dprelude.census=1` shows the new prelude.
2. **Corpus loader (`Corpus`)**: the 64 files enter the corpus tests' graph by name. HOW is §4 D1.
3. **`Compiler.withoutPreludeShadows`**: unchanged; its receipt list shrinks to the platform-vocabulary classes the engine tree
   also declares (§4 D2).
4. **Census**: the 18 engine-body rows of `SPEC_BODY_CENSUS` §10.2 follow their classes to the graph; what they become there is
   measured, not predicted.
5. **Pins**: `PreludeGeneratorTest` parity, the census pin (shrink-only 22), the hand-class count, `PlatformTypesDriftTest` —
   all re-pinned to the measured numbers, each with the reason.

Slices, lanes EXACT between them: **3a** the vocabulary list and the generator computing BOTH demands, reporting the
difference in census mode — `prelude.pure` unchanged (a smaller prelude before the graph admits the classes would break
every test that needs them). **3b** per file family — the family's files admitted (declarations only) AND dropped
from the prelude in the same batch, the declaration files first (SQL protocol, protocol template, plan/router/service
metamodels), then the mixed files one family at a time. **3c** the census re-measured and the
receipts re-listed.

## 4. Decisions (yours)

**D1 — How a class in a MIXED engine file enters the graph. USER 2026-09-08: "Agree on class/enum for engine references" —
(i) RATIFIED.** Three ways were:

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

## 6. Landed

**Batch 154 (phase 3a + 3b-1, 2026-09-08): the vocabulary list and legend-pure's platform packages WHOLE.** `PlatformTypes.
CONSTRUCTED_VOCABULARY` (nine entries, each a receipt naming the constructing site; `constructedVocabulary()` for the generator).
The generator's closure walk is a reusable `Spec.close(seed)`; census mode computes the T1 demand beside today's and writes
`target/prelude-t1-diff.tsv`. MEASURED before the widening: today 569, T1 357 — keep 316, leave 253 (every corpus-only engine
class, the dispatch-only ones among them), enter 41 (legend-pure platform classes never demanded). LANDED: the 41 enter —
`prelude.pure` 569 → 610 declarations (577 classes, 33 enums); one m3 bootstrap hand shape added (`ValueSpecificationContext`,
m3.pure:1804 — three platform mapping/store contexts extend it; hand count 84 → 85); pass counts UNCHANGED on every gate
(DuckDB 2442/108/14/11, H2 1990/565/14/6, channel B 314/13, 355, 137, 95, 204, PCT 1110/0), census pin 22 unchanged (the 41
carry no body that fails). USER on D1: "the most simple thing that makes sense and still sticks to our tenets" — declarations-only
admission, one small loader mechanism; D2 agreed. NEXT: batch 155 = phase 3b-2, the engine cut: the 253 leave the prelude and
their 64 files enter the corpus graph by name, declarations only.

**Batch 155 (phase 3b-2, 2026-09-08): the engine cut.** The generator's demand is T1 alone — legend-pure's platform packages
whole + the vocabulary (native signatures, the system metamodel's source, `PlatformTypes.CONSTRUCTED_VOCABULARY`) + closure;
the corpus and library text scan and the `src/main/java` scan are gone from the generator (the corpus tree, the program
libraries and the shape files are read only for the T4 receipts). `prelude.pure` 610 → **357 declarations (343 classes, 14
enums)**. The 253 leaving classes' 64 engine files are `Corpus.SHAPE_FILES`; `MinimalCorpus.withShapes` parses each and merges
its classes and enums into the corpus graph (first definition wins; each element keeps its section's imports; functions never
enter) — D1 as ratified, one mechanism of ~40 lines. Pass counts UNCHANGED on every gate (DuckDB 2442/108/14/11, H2
1990/565/14/6, channel B 314/13, 355, 137, 95, 204, PCT 1110/0; G1 4377). Census **22 → 19**: `Service`'s constraint,
`MultiExecutionContext.allContexts` and `RelationElementAccessorExtension`'s constraint left with their classes; the 19 that
remain are bodies of classes that ARE vocabulary (`DbConfig`/`SQLResult` by signature, `SchemaState`/`Extension`/the
external-format descriptors by closure) — vocabulary work, not prelude leakage. T4 receipts: 61 → **119**, the stable list
D2 named — vocabulary classes the graph also declares (the corpus tree and, now, the shape files: `SQLExecutionNode`, the plan
nodes, `DbConfig`, the JSON tree…); pinned only by the parity test for now. One unit test moved (`NameResolverTest`: the
sql-protocol `Table` is graph material, so under its wildcard the bare name is unresolved). Phase 3 is complete; NEXT: phase 2
(the hand shapes out of `Pure.java`), then the 19 rows as vocabulary.

**Batch 156 (2026-09-08): the vocabulary rule simplified — "just take everything".** USER, after phase 3: "did we
over-engineer? … for the 59 should we just take everything instead of the declared-vs-used whitelist" — yes. The curated
`PlatformTypes.CONSTRUCTED_VOCABULARY` (nine receipts, one batch old) is DELETED; Java demand is the MECHANICAL rule: every
spec class or enum the platform's Java NAMES in a code line (comment lines never count) — signature, constructed instance,
dispatch constant alike; the receipt is a grep. The generator's T1 diff census (permanently keep-all since 155) is deleted
too. `prelude.pure` 357 → 373 (359 classes, 14 enums: the ~14 dispatch-only classes and their closure return). Pass counts
UNCHANGED on every gate; census 19. How to think about it (USER's question, answered): `Pure.java` = what the RUNTIME
implements (native signatures + the bootstrap handful Java needs before any source is read); `prelude.pure` = what the
LANGUAGE declares (every platform class/enum, verbatim, generated — legend-pure's platform packages whole + every engine class
our Java names + closure); the graph = what PROGRAMS declare (by file: programs whole, shapes declarations-only).
