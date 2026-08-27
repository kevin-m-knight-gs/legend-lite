# The adapter necessity census — F10 slice 4 / BUCKET 3 (2026-08-27)

**The ratified question** (PROGRAM_MAP BUCKET 3): is
`ExecuteLegendLiteQuery.java` + `pct_adapter.pure` the MINIMUM set to
run reference PCT without compensating for the platform?

**Method**: the necessity-proof census — the same audit form that
killed the host-logic arms. Every arm proves itself against our tenets
(witness) or dies (death warrant), deletions land in the same slice
that proves them dead. Clause 2b governs what moves INTO the platform
(platform natives in Java OK, ONE owner, engine pure as spec). Audit
finding #A7 bounds scope: the regex model-injection family is Channel
A's alone. No adapter hedges — a shrink-pin bumped UP is the tell.

**Verdict vocabulary**: BOUNDARY (a genuine crossing Mode B needs —
keeps, with the receipt), NAMED STOPGAP (load-bearing compensation
whose burn needs a platform capability first — keeps, named, with its
retirement leg), ADJUDICATED (a prior slice already judged it — keeps,
receipt cited), DEAD (zero traffic proven — deletes this slice),
MEASURE (probe first, then one of the above).

---

## 0. The stale-input correction (recorded FIRST, per the
two-stale-reads lesson)

`docs/PCT_AUDIT.md` was measured 2026-08-03 at `d229b694`. The tree
has moved ~700 commits since, and **most of the audit's §7 "free
wins" were already executed** by the F5.x adapter program and the M4/
§4bZ arcs. What the audit calls open vs what the tree says today:

| Audit item | Audit verdict | State on main TODAY |
|---|---|---|
| C6 `remapErrorMessage` (shift-error answer-key remap) | delete, cost 0 | **ALREADY DELETED** (deep-audit H4). What remains under the same name is a *different, smaller* arm: a DuckDB transport-prefix strip (`Invalid Input Error:` / `Out of Range Error:` / `Conversion Error:`), self-described as kept deliberately for interval tests. → MEASURE (row J6). The register F16 note "remapErrorMessage dies with the error-composition leg" is STALE twice over — the arm it named is gone. |
| C7 null-scan multiplicity | dead while C15 fires | **ALREADY DELETED** (F5.3 Stage B). |
| C8 `pureTypeName` SQL-name sniff | fix first, 87 tests | **ALREADY EXECUTED** (F5.1: `Column.pureType()` names the header). The audit's §4.4 item (1) is done. |
| C15 answer-key header overlay | plumb multiplicity, delete | **ALREADY DELETED** (F5.3 Stage B: "the WIRE header IS the header"; `buildTypedHeader` is gone from pct_adapter.pure). PCT sees the platform's own column types, multiplicities, names. |
| C9/C14 `formatAsTds`/`formatValue`/pivot escapes | platform print-form owner | **ALREADY DELETED** (E1, JAVA_EVICTION_PLAN: PCT-TDS wire text renders IN THE PLAN — Lowerer PCT-TDS root mode; the adapter hands one Scalar String over verbatim). |
| C12 silent list fallback | make it throw | **ALREADY LOUD** (F5.5). |
| C13 hardcoded-Pair default ×2 | make it throw | **ALREADY DELETED** (F5.6: `createClassInstance` deleted by probe; `classInstance` survives with loud walls). |
| C1 five verbatim corpus functions | goes with transport | **ALREADY REPLACED** (F5.7: `extractFunctionDefinitions` slices definitions from the interpreter's OWN source registry — no verbatim fork). |
| C4 `inlineFunctionLiterals` regex | real parser gap, 2 | **ALREADY REPLACED** structurally: the pure-side `ConcreteFunctionDefinition` arm rebuilds a REAL typed lambda via `dynamicNew` (invention census D2 receipt — the print-only form's parse arm is deleted). |
| C10 root cause `PureSql:64` DATE==DATE_TIME==TIMESTAMP | fix | **ALREADY FIXED** (F5.4: `STRICT_DATE -> SqlType.Scalar.DATE`; only abstract Date/DateTime ride TIMESTAMP). The adapter's declared-type narrowing arm survives the fix → decay candidate (row J8a). |
| C18 Integer→Float widening | pct-native fixed in core | **ALREADY DELETED** (audit 2026-08-18 finding G: full suite proved it dead). The Decimal→Float RELABEL beside it is ADJUDICATED (row P7). |
| Audit §7.4 "put PCT in CI" | cheap | gate.yml aside, the standing gate chain runs G6/G7/G9 on every batch — the PCT ratchet is enforced locally by tools/allgates.sh; PctCensusGate pins the lanes. |

**Lesson applied**: the prompt-level claim "six cost ZERO, execute the
verdicts" was measured against a tree seven hundred commits old. The
census below is against TODAY's files, and every kept row cites its
receipt.

---

## 1. Census — ExecuteLegendLiteQuery.java (1,104 lines)

Row ids are stable (J* = Java side, P* = pure side). "Traffic" is
filled by the slice's instrumented measure run (§3); verdicts marked
MEASURE finalize on those numbers.

| # | Site | What it does | Verdict | Receipt / death warrant |
|---|---|---|---|---|
| J1 | `PURE_MODEL` | the fixed Doy model/mapping/connection/runtime scaffold Mode B queries execute against | **BOUNDARY** | Mode B needs a runtime FQN to hand QueryService; no corpus source is pasted (C1's function copies died at F5.7). |
| J2 | `reEscapeStringLiterals` | re-escapes control chars the interpreter resolved before printing | **MEASURE → NAMED STOPGAP or DEAD** | Audit E9 measured 0 on 2026-08-03, but the arm was since REWRITTEN with a pass-through for pre-escaped sequences ("shredding pivot names" comment) — someone found it load-bearing after the audit. Gap A family (text transport): if live, it dies with the transport leg, not by deletion today. |
| J3 | regex model injection: `extractClassMetadata` / `extractClassRecursive` / `extractEnumDefinitions` (~200 lines) | rebuilds referenced classes/enums from the interpreter's M3 graph, re-printed as grammar | **NAMED STOPGAP (Gap A)** | Audit E10: 45 tests, fails LOUDLY. #A7: this family is Channel A's alone — Channel B never touches it. Retirement leg = structured transport (audit §4.3) or Mode A; NOT this slice. |
| J4 | `extractFunctionDefinitions` (F5.7) | slices `::tests::` support-function definitions from the interpreter's own source registry | **NAMED STOPGAP (Gap A)** | Same transport gap as J3, but already compensation-minimal: definition text comes from the reference's own registry, never a fork. |
| J5 | error source-info walk (execute's catch) | reports the TEST's own call-site line/column, skipping adapter frames | **BOUNDARY** | assertError checks positions; the adapter frame is not the test's position. No answer substituted. |
| J6 | `remapErrorMessage` (prefix strip) | strips `(Invalid Input\|Out of Range\|Conversion) Error: ` transport prefixes | **MEASURE** | Self-claims interval tests need the bare native text. If deletion moves PCT rows → LOAD-BEARING compensation: its burn belongs to the error-shape leg (Bucket 2, OUT of this slice) — record witness + Clause 2b note, keep. If zero → delete. |
| J7 | `handleScalar` Map-flatten / List-elements arms | flattens Map to [k1,v1,...], converts List elements; pure side rebuilds | **BOUNDARY (transport shape)** | The wire has no Map/List value carrier; the DECLARED type gates the Map arm (comment: JDBC STRUCTs also arrive as java.util.Map). Rebuild is split with P5/P6 — see the P5/P6 rows for the pure side's half. |
| J8 | `toCoreInstance` kind arms — the F16 family. Sub-rows: | | | Register F16 predicts decay: "adapter receives kind-faithful values and ONLY boxes; declared-type consult arms decay as F10 lands". |
| J8a | `PureDateLiteral.strictDatePart()` narrowing on declared STRICT_DATE | narrows a time-bearing temporal to its day | **MEASURE** | C10's root cause is FIXED (F5.4: STRICT_DATE rides SQL DATE) — a STRICT_DATE-declared value should now ARRIVE day-precise. Expected DEAD. |
| J8b | `BigDecimal` → Decimal on declared DECIMAL/NUMBER, else Float | kind from declared type | **MEASURE** | The wire's DECIMAL columns deliver BigDecimal for genuine Decimals AND (historically) for Float-declared reads. F10/T4 stamps may have killed the Float half. |
| J8c | `Double` → Decimal/PrecisionDecimal on declared kind, else Float | kind from declared type | **MEASURE** | Same family. `stripTrailingZeros` is this arm's helper (PrecisionDecimal print scale). |
| J8d | `Float`/bare-`Number` → Float fallback arms | java.lang.Float and the `Number n` catch-all | **MEASURE** | The `Number` catch-all is a silent default (AGENTS.md 4 smell): if zero traffic → THROW (loud wall), never a quiet Float. |
| J8e | `LocalTime` → StrictTime | raw driver time | **BOUNDARY (ledgered)** | StrictTime has NO SQL carrier (PureSql: null; F10 non-goal). The arm is the wire's only StrictTime crossing. Expected near-zero traffic but kept as the ledgered carrier gap. |
| J8f | String → canonical `Type` instance on declared metamodel Type | resolves type NAME to the one true instance | **BOUNDARY** | assertIs compares identity; a name is the only wire form. |
| J8g | String → enum value on declared EnumType | canonical enum-value instance by name | **BOUNDARY** | Same identity argument (C20's Java half). |
| J8h | String date-shaped reparse on declared Date-UNION | parses preserving written precision | **ADJUDICATED** (audit finding M, 2026-08-18, comment in situ) | Reading precision from text here is VALUE DECODING, not kind classification: on the Date union, precision IS part of the value. C11-as-audited (any string, regex kind guess) died earlier; the surviving arm is union-gated. MEASURE traffic for the record. |
| J8i | temporal-typed non-date-text → throw | loud wall | **KEEP (wall)** | F5.5 receipt. |
| J8j | `UUID` → String on declared STRING | canonical text | **BOUNDARY** | Adjudicated channel (F5.5 comment). |
| J8k | `DuckDBStruct` → `classInstance` | class instance from a struct's field map | **BOUNDARY-with-defect → CONSOLIDATE** | TWO struct→instance builders survive (audit §6.3's finding in a new guise): `structToInstance` (java.util.Map route, property types via generics + metamodel) and `classInstance` (DuckDBStruct route, property types by POSITION `idx` into type arguments — a Pair-shaped assumption on arbitrary classes). One behaviour, one owner: consolidate `classInstance` into `structToInstance` once traffic shows which shapes cross (measure first — the positional idx is a latent wrong-type channel for any non-Pair class with >0 type args). |
| J8l | `Map` (non-class-typed) → throw; multi-element List in scalar → throw; terminal no-typed-conversion → throw | loud walls | **KEEP (walls)** | F5.5/F5.6 receipts — the census's own instrument: new wire shapes fail by name. |
| J9 | `structToInstance` + `classPropertyTypeOf` / `genericTypeOf` / `propertyTypeOf` (~150 lines) | the value→CoreInstance bijection for class instances | **BOUNDARY (irreducible while Mode B exists)** | Audit §6.1: the interpreter consumes CoreInstances; something must build them. Types come from declared generics + the interpreter metamodel — no sniffing. |
| J10 | `createTDSResult` | wraps the plan-rendered TDS string for the pure side | **BOUNDARY** | The string was rendered IN THE PLAN (E1); this only boxes it. |
| J11 | H2 backend branch + `SET TimeZone` pinning | session config per backend | **BOUNDARY (orchestration)** | Portability sweep contract; witnessed zone-shift comment. |

## 2. Census — pct_adapter.pure (316 lines)

| # | Site | What it does | Verdict | Receipt / death warrant |
|---|---|---|---|---|
| P1 | `legendLiteGrammarExtension` / `objectToGrammar` / `CapturedInstance` | prints TDS + captured instances as grammar | **NAMED STOPGAP (Gap A)** | Exists because the transport is text. Dies with the transport leg. |
| P2 | `getSimpleTypeName` | collapses enums/unknowns to 'String' | **DEAD** | ZERO callers in the file (its consumer `buildTypedHeader` died at F5.3 Stage B). C22's death warrant: delete + G6 proves. |
| P3 | `substituteOpenVariables` / `substituteInExpression` | β-substitution of captured closure variables over the M3 AST | **NAMED STOPGAP (Gap A, C21)** | Text transport cannot carry closures. The ConcreteFunctionDefinition arm carries the invention-census D2 receipt (real typed lambda via dynamicNew). Not capture-safe — ledgered; dies with the transport leg. |
| P4 | adapter body: empty-result arm consulting declared Map | `[]` vs empty Map on declared return | **BOUNDARY (transport shape)** | An empty wire result has no kind; the declared type is the only source. Same argument as J7. |
| P5 | `wrapPctMap` — 3-combo hardcoded type table | rebuilds Map k/v with per-combo casts | **BOUNDARY-with-defect (C16), ledgered** | pure's static generics force an enumeration (newMap derives Map<U,V> from the pairs' STATIC types — a dynamic cast-to-declared does not exist in the language). Defect: the fallback combo builds Map<Any,Any> SILENTLY, which a caller's cast rejects loudly downstream — acceptable wall, but the enumeration is overfit by construction. Ledger row, revisit only if a 4th combo appears (it will fail loudly at the caller's cast). |
| P6 | `wrapPctList` — 5-type enumeration | rebuilds List<T> with per-type casts | **BOUNDARY-with-defect (C17), ledgered** | Same language constraint, same loud-downstream argument. |
| P7 | Decimal→Float relabel on declared Float | moves the TYPE LABEL only | **ADJUDICATED** (audit 2026-08-18 finding G, comment in situ) | The platform returns the decimal-EXACT value (testBigFloatAbs); only the label moves. The Integer→Float widening beside it is already deleted by full-suite proof. |
| P8 | String→`parseDate` on declared Date | dates travel as print-form strings | **BOUNDARY (wire date convention)** | Pair of J8h — the wire's declared temporal decode. |
| P9 | String→`extractEnumValue` on declared Enumeration | enum by name | **BOUNDARY (C20)** | |
| P10 | TDSResult→`stringToTDS` | parses the plan-rendered TDS text | **BOUNDARY** | F5.3 Stage B receipt in situ: the WIRE header IS the header. |

## 3. The measure run — EXECUTED 2026-08-27

Per-arm print probes; G6 full DuckDB lane (1115/0 green, the whole-JVM
composition) + G7 h2 Relation lane (348/1F/22E — exactly its ledgered
ceilings); core installed first; instrumentation reverted with the
batch. Measured traffic:

| Probe | G6 (full DuckDB) | G7 (h2 Relation) | Verdict finalized |
|---|---:|---:|---|
| J2 reEscape input-changing calls | **0** | 0 | **DEAD — deleted** (the serializer hands the expression over parse-ready) |
| J6 prefix strip | **18** | 0 | **LOAD-BEARING — kept, witnessed**; burn = Bucket 2 error-shape leg (Clause 2b: the platform error channel must own transport prefixes first) |
| J8a strictDate narrowing (value actually changed) | **1** | 0 | **CURED AT EMISSION + deleted**: the witness was `parseDate('2014-02-27')` — Typer refines the literal to StrictDate but the rule cast everything to TIMESTAMP. The Scalars parseDate rule now casts to DATE when the node's own stamp is StrictDate (conform-by-emission, the rule reads the stamp — no re-sniff); the adapter narrowing arm deleted. |
| J8b BigDecimal under declared Decimal/Number | 23 | 0 | **BOUNDARY — kept** (the DECIMAL wire's genuine deliveries) |
| J8b BigDecimal under PrecisionDecimal | 21 | 0 | **BOUNDARY — kept** |
| J8b BigDecimal→Float fallthrough | **0** | 0 | **DEAD — now a loud wall** (Float contracts arrive as DOUBLE) |
| J8c Double→Decimal / →PrecisionDecimal consults | **0** | 0 | **DEAD — deleted** (+ `stripTrailingZeros` helper died with it); Double is always Float |
| J8d Float32 arm / Number catch-all | **0** | 0 | **DEAD — deleted**; unlisted numeric carriers hit the terminal wall by name |
| J8e LocalTime | **0** | 0 | **DEAD — deleted** (StrictTime has no SQL carrier; nothing crosses; returns with its carrier leg if ever built) |
| J8h date-union text reparse | **0** | 0 | **DEAD — deleted** (temporals cross as PureDateLiteral; a temporal-typed String is now the F5.5 wall). The audit-M adjudication is moot — the arm it defended has no traffic. |
| J8j UUID | **0** | 0 | **DEAD — deleted** |
| J8k DuckDBStruct → classInstance | **0** | 0 | **DEAD — deleted WITH `classInstance`**: the two-owners finding resolves by deletion; `structToInstance` is the ONE struct→instance owner. (classInstance's positional type-arg indexing was a latent wrong-type channel — gone with it.) |
| J9 Map → structToInstance | 45 | 0 | **BOUNDARY — kept** (the live bijection route) |

P2 `getSimpleTypeName` deleted the same batch (zero callers — its
consumer died at F5.3 Stage B).

**Discipline notes**: counters are per-JVM cumulative — only
whole-lane numbers counted; the h2 lane gates only the Relation suite,
so a Float32-family h2 delivery outside Relation would surface in the
advisory portability sweep as a NAMED dialect wire divergence (a
finding, not a silent absorb).

## 4. Slice-4 deliverables beyond the two files

| Deliverable | State | Disposition |
|---|---|---|
| DOUBLE←VARCHAR "NUMBER-slot identity carrier" admissibility row retires | **ALREADY RETIRED** | Row added at 77af0f37 (T3), deleted at 3c353706 (the label flip); the admissible() relation itself deleted at d4acdeca (§4bZ-V B4). No row in today's `SqlTypeCensus.delivers` mentions it. Recorded here as the receipt; nothing to do. |
| Computed-mixed collections: carrier claim or named ledger row | **NAMED LEDGER ROW (this doc)** | `AssertVerdicts.mixedNumericKinds` residual guard: only a COMPUTED mixed collection (concatenated/derived — no carrier claim) can reach the decline; decline ceilings pinned 0 both lanes, so zero witnesses is machine-proven every chain. THE LEDGER ROW: a first firing is a named work item — extend the LITERAL carrier to the computed shape at that witness, never pre-build (no adapter hedges applies to platform hedges too). |
| Wire pins bank DOWN | PctCensusGate: every pin already EQUALITY-0 except `MAX_INT_NULL_EMPTY=219` (ceiling, shape-driven); G7 ceilings fail<=1/err<=22. | Bank any floor the slice's runs prove lower, same-commit justified. A pin the deletions force UP is the no-hedges tell — stop and re-adjudicate. |
| D91 fold-in | **LANDED 2026-08-27** (this slice's fold-in batch) | `EqualityKeys.resolve` now rides EVERY lane (StatementExecutor wires `withInstanceKeys` unconditionally; identity minting stays verdict-lane-only); `InstanceEquality`'s KEYED `equal()`/`contains()`/`in` claim on plain layouts (the key canon reads key fields, no `__id` needed). The identity pieces — `eq`, keyless `equal`, the static cross-class FALSE fold — stay verdict-lane-gated (a value-lane supertype alias could be the same runtime class; keyless equality IS identity and needs the minted id — named residue, §5). Witnesses: `InstanceIdentityTest.keyedEqualInValuePosition` (same key + different non-key content = TRUE, where the structural rule said FALSE) + `keyedMembershipInValuePosition`. |
| D94 layout row ([1]-vs-[*] diamond) | **LANDED 2026-08-27** | `ClassLayouts.collect` keeps the FIRST super's declaration on a diamond duplicate (multiplicity included) — the same extends-order rule `findProperty` resolves by; the type-conflict wall unchanged. Witnesses: `ClassLayoutsDiamondTest` (layout ≡ findProperty; the A28 repro's `^m::D(w=1).w` reads back a SCALAR). |

## 5. Residue (named, not burned here)

- **Keyless-instance `equal()` on the execute path** stays the generic
  structural rule (pure semantics = identity; identity needs `__id`,
  which rides only the verdict lane by design — golden-SQL text lanes
  stay unperturbed). Named residue of D91; burns if/when the identity
  layout joins the execute lane deliberately.
- **J3/J4/P1/P3 (Gap A)**: the text transport family — retirement is
  the structured hand-off or Mode A (PCT_AUDIT §4.3/§5), a program of
  its own, bounded by #A7 to Channel A.
- **P5/P6 enumerations**: language-constrained; loud at the caller's
  cast on any unlisted combo.

## 5b. THE DERIVED MINIMUM — the rewrite spec (2026-08-27, supersedes
the arm-by-arm framing above as the slice's governing analysis)

User redirect (2026-08-27): don't trim the audited arms — derive from
scratch what the MINIMUM adapter is now that the platform has real
types end-to-end. No prior audit trusted; every claim below read from
today's sources or measured on the full lanes.

### The two surfaces, read from source

**(a) What the framework requires** (`pct_core.pure`, legend-pure
5.88 checkout): exactly ONE stereotyped function,
`<<PCT.adapter>> <X|o>(f:Function<{->X[o]}>[1]):X[o]`. The In-Memory
adapter is `$f->eval()` — one line. Everything beyond that line exists
only because our executor is out-of-process from the interpreter.

**(b) What the platform delivers** (read from `Executor.fetch/unwrap`,
`LiteralText.parse`, `ExecutionResult`, `Column`): a CLOSED egress
vocabulary — `null`, `Boolean`, `Long`/`BigInteger` (integral
re-narrowing happens AT THE FETCH SEAM, `toBigIntegerExact` under
integral labels), `BigDecimal` (DECIMAL contracts), `Double`,
`String`, `PureDateLiteral` (ALL temporals — "FULL STOP", hardened
2026-08-21), `LinkedHashMap` (structs, layout-keyed, recursive),
`List` (arrays, recursive). `LiteralText.parse` returns inside the
same set. Shape comes from the `ExecutionResult` VARIANT; types from
`Column.pureType` (the object, per its own javadoc contract).

### The derived minimum, by direction

**Outbound (result → CoreInstance)**: one arm per vocabulary entry,
keyed on the VALUE's class (kind-faithful by construction), plus three
identity RESOLUTIONS that a value wire cannot avoid (enum name → the
canonical enum instance; type name → the canonical Type instance;
struct map → class instance via `structToInstance`). Declared-type
reads are ASSERTIONS (walls), never choices. The post-cargo Java map
IS this minimum — confirmed by derivation, not just by deletion.

**Inbound (M3 function → execution)**: the pure side runs ON the
reference interpreter, where M3 reflection is native — upstream's own
relational adapter does ALL its reprocessing in pure
(`pct_relational.pure` reprocess: FunctionExpression/InstanceValue/
LambdaFunction/VariableExpression match arms; `^X(...)` exposes its
class at `genericType.rawType`; `cast(@X)` carries Class-as-value in
`InstanceValue.values`). Therefore the five Java DISCOVERY regexes
(`INSTANCE_CLASS/TYPE_REF/ENUM_REF/PARAM_TYPE/BARE_REF/FQN_TOKEN`)
are NOT minimal: discovery belongs in a ~50-line pure AST collector
(the `substituteInExpression` walk skeleton, collecting `.func` refs,
`genericType.rawType`s, Class-as-value nodes, enum classifiers, lambda
param types), handed to the native as `dependents: String[*]`. Java
keeps the PRECISE half it already has: M3-recursive extraction +
source-registry slicing keyed on the given roots. Registration cost:
one signature + one key
(`executeLegendLiteQuery_String_1__String_MANY__Any_MANY_`).

### The pure side, measured (G6 full lane, 1115/0, 2026-08-27)

| Arm | Traffic | Verdict |
|---|---:|---|
| P5 Map flatten-rebuild (`wrapPctMap`) | 9 | **BOUNDARY** — the interpreter's Map is interpreter-internal state; `pair()/newMap()` is its sanctioned constructor. Keyed on declared Map — acceptable at the EMPTY/shape seam only. All 9 in the three named combos. |
| P6 multi-value List wrap (`wrapPctList`) | 3 | **BOUNDARY (for now)** — all traffic in named combos. Candidate: move List construction to Java keyed on the PLATFORM's returnType (Java has dynamic generics via `genericTypeOf`), deleting the enumeration; sequenced after R1. |
| P7 Decimal→Float relabel | 1 | **ADJUDICATED, one witness** — testBigFloatAbs (`abs(-123456789123456789.99)`): the platform keeps the value decimal-exact by contract; the label moves. Stays until the T4 builder leg gives Float labels to decimal-exact carriers. |
| P4 empty-result declared-Map consult | **0** | DEAD — delete |
| P6b scalar List wrap | **0** | DEAD — delete |
| P8 String→parseDate on declared Date | **0** | DEAD — dates never cross as text (PureDateLiteral owns them); delete |
| P9 enum extract at the scalar seam | **0** | DEAD — a DUPLICATE of the Java-side enum resolution (J8g owns it); delete |
| P5/P6 `Any` fallback combos | **0** | never fire — make LOUD |

### The rewrite slices

- **R2 (this batch)**: delete the four dead pure arms + loud
  fallbacks; the adapter function body shrinks to: substitute →
  print → execute → (TDS | scalar with the two witnessed consults |
  collection with Map/List rebuild).
- **R1 (next)**: the inbound discovery rewrite. Transition
  differential: the pure collector's root set and the regex set BOTH
  feed one lane run, any difference logged by name
  (`[discover-diff]`); regexes delete on a measured-equal (or
  strictly-better) result. The census method applied to the rewrite
  itself.
- **After R1+R2** the files ARE the derived minimum for a text
  transport: pure ≈ contract fn + β-subst + collector + printer +
  Map/List rebuild (~230 lines); Java ≈ scaffold + QueryService call +
  closed-vocabulary bijection + M3 extraction-by-FQN + error crossing
  (~750 lines, of which ~260 is extraction that dies wholesale if the
  transport ever goes structural — Gap A remains the only path below
  that floor).

## 5c. THE TRUTHFULNESS BURN (user directive 2026-08-27: "burn this
all down … no cheating, no compensation, no shortcuts")

The §5b residue plus every purist-nitpick, each with its truthful
mechanism — never a quieter respelling of the same trick. One gated
batch per row; rows close with receipts here.

| # | Item | The truthful mechanism | State |
|---|---|---|---|
| B1 | `PURE_MODEL` scaffold (Doy model/mapping/runtime text in the harness) | The platform executes a STORELESS expression against a bare connection (no model, no runtime) — a product capability (REPL/standalone-SQL vision), not a test hook. Experiment first: does `execute("", expr, null-runtime, conn)` already work? | **EXECUTED 2026-08-27**: probed both backends — it already works; the scaffold's only live duty was the `type: H2;` DIALECT flip, which `Compiler.dialectOf`'s connection seam (JDBC product metadata) already supersedes. Scaffold deleted; runtime = null; injection-only model. One expected-failure pin became MORE honest (getAll now fails "class query requires an execution context" instead of citing a phantom TestRuntime). Both lanes at baseline. |
| B2 | Result SHAPE consults the TEST's declared type (`wrapPctList`/`wrapPctMap`, the multi-value dispatch) | The PLATFORM's own `ExecutionResult.returnType()` decides the shape: Java builds the `List` instance itself (it has dynamic generics — no combo table), and the Map crossing went one better than designed: Java constructs the interpreter's OWN `MapCoreInstance` (the exact class `newMap` builds) stamped with the platform's `Map<K,V>` — no marker class, no pure-side rebuild at all. `functionReturnType()` is gone from every SHAPE decision. | **EXECUTED 2026-08-27**: wrapPctList/wrapPctMap + the whole multi-value dispatch deleted; first cut's `newMap`-over-marker-pairs FAILED honestly (interpreted newMap types statically → Map<Any,Any> cast rejections, 6 witnesses) and forced the better design. Full lane 1115/0; h2 at ledger. |
| B3 | Decoration strip regexes in `sliceFunction` (`<<...>>`, `{doc…}`) | Verbatim injection: our parser already parses stereotypes/tagged values (the parity program); the question is compile-side profile resolution. If the PCT profile registers as a known platform profile (it IS real upstream pure), sliced source injects UNMODIFIED. Fallback: a string-literal-aware char walk (no regex, no corruption class). | OPEN |
| B4 | `::tests::` path filter on function injection | The principled criterion: inject exactly the functions OUR platform does not know (native catalog + prelude lookup), whatever their package — resolution-driven, no name sniffing. (The path filter also silently protected against shadowing platform functions with reference impls — the resolution criterion keeps that property explicitly.) | **EXECUTED 2026-08-27** — research REVERSED the framing: `::tests::` is UPSTREAM'S OWN fixture-package taxonomy (test-support functions live under tests packages in the pct corpus layout; platform surface never does), so the criterion is a published convention, not name sniffing — now documented at both sites, plus a LOUD no-shadowing wall in ModelPacker (a fixture colliding with a lite-native name refuses injection instead of silently replacing our semantics). The naive 'inject anything lite doesn't know' alternative was rejected as a Clause 2b breach (it would import reference impls of platform functions through test injection). |
| B5 | Error-frame filter by source-name (`contains("core_legend_lite_pct")`) | Positive criterion: the TEST function's own source id (from `$f`) identifies the frame to report — assert what it IS, not what it isn't. | **EXECUTED 2026-08-27** — the reported frame is the first from a source DIFFERENT from the adapter's own (read off the top frame at entry): assert what it IS, no name pattern. |
| B6 | `SET TimeZone='UTC'` in the adapter | Session initialization is DIALECT-OWNED, one owner platform-side (audit the corpus lane's current handling first — no second owner). | **EXECUTED 2026-08-27** — first cut (`initSession(Connection)` ON the dialect) was REFUSED by the architecture gate: F1.3 funnels java.sql away from the dialect package — the guardrail caught a layering breach in the burn itself. Landed shape: the dialect states the FACT (`sessionSetup(): List<String>` — DuckDb pins UTC per the naive-UTC temporal contract; H2 deliberately none, zone-funnel witness kept) and the exec funnel EXECUTES it at `Compiler.dialectOf`'s connection seam. The adapter's inline SET deleted; DuckWorkspaces' copy re-documented as warmup. |
| B7 | J6 error-prefix strip (18 witnesses) | The research verdict re-framed the arm: 14 of the 18 witnesses are messages OUR OWN LOWERING raises (pure's exact text via SqlFn.ERROR guards) that DuckDB wraps in its transport envelope — the strip was unwrapping our own letter. But it was envelope-BLIND (the H4 class-erasure weakness), and the platform's Channel-B assertError carried a SECOND, broader copy of the same strip (`"* Error: "` regex). The truthful mechanism: PROVENANCE — the renderer marks raised messages with a U+001F sentinel at both ends (`error(chr(31)||msg||chr(31))`; H2 SIGNAL likewise), and ONE owner (`exec.RaisedErrors.unwrap`, registered in the exec class register) extracts between sentinels at the `Executor.execute` funnel. Native errors pass through WHOLE — class, envelope and all. | **EXECUTED 2026-08-27**: the adapter's `remapErrorMessage` and `AssertErrorNative.decode`+PREFIX both DELETED (two owners → one); the seam's spec lives in AssertErrorNativeTest (sentinel extraction + native-pass-through pinned). Blast radius measured: exactly the 4 BigNumber expected-failure pins, repinned to the RAW enveloped text — now byte-matching upstream's own DuckDB manifest spellings for the same tests (parity receipt in-file). Channel B unchanged (PASS=297 — no row ever depended on the broad strip). Both PCT lanes green. |
| B8 | P7 Decimal→Float relabel (1 witness) | Real pure's Float literals ARE decimal-exact (testBigFloatAbs asserts the digits): the truthful fix is label fidelity — the typer labels the literal Float while the carrier stays decimal-exact, the census registers the DECIMAL←Float pair, the adapter keys on the PLATFORM label. The relabel arm then dies. | OPEN |
| B9 | `substituteOpenVariables` "not capture-safe" (audit C21) | PROOF, not code: the substitution replaces variables with CLOSED VALUES (captured lambdas are closed terms) — closed-term substitution cannot capture. Written argument lands in the file; the nitpick dies by proof or, if the proof fails on a witness, by α-freshening. | **CLOSED BY PROOF 2026-08-27** (written in-file): the substitution inserts only VALUES — closed terms (captured lambdas are closures) — and closed-term substitution cannot capture; the interpreter's unbound-variable error is the tripwire if the premise ever breaks, alpha-freshening the fallback. |
| B10 | `objectToGrammar` unguarded on cyclic instances | The collector's chain-seen guard, same mechanism, printer-side. | **EXECUTED 2026-08-27** — objectToGrammar carries the chain-seen guard; a cyclic instance graph FAILS LOUDLY ('no grammar print form') instead of overflowing the stack. |
| B11 | Class extraction degrades type parameters to Any | Bounded honestly: emit the parameter list when the source class is generic (extraction reads M3 typeParameters), or wall LOUDLY on generic user classes instead of degrading silently. | OPEN |
| — | Structure: split `ExecuteLegendLiteQuery` → `PctExecuteNative` / `ModelPacker` / `ValueBridge` | The file seams become the architecture (permanent bijection / transport-contingent packer / thin entry). Pulled FORWARD by user directive so the remaining burns land in the final layout. | **EXECUTED 2026-08-27** (user-directed): verbatim method moves along the derived seams — `PctExecuteNative` (131 stripped lines: orchestration + error crossing), `ModelPacker` (250: inbound, dies wholesale at Gap A — a future file delete), `ValueBridge` (353: the permanent bijection). Eval-ledger re-seeded at MEASURED counts (734 total vs the old single-file 850 pin — the burns show in the ledger); JDBC census re-registered; pure-side native name unchanged (the wire contract). Lane 1115/0. |

OUT of even this campaign: Gap A itself (the structural transport —
the packer's existence is the honest cost of a text wire, and B3/B11
make its content verbatim-faithful); Variant/ScenarioQuant wiring
(scope growth, not truthfulness).

## 6. Landing record

- **2026-08-27 — the fold-in batch (D91 + D94)**: instance equality's
  KEYED half armed on every lane (one owner, the verdict layer's own
  canon — see §4's row for scope and residue); the diamond layout
  adopts findProperty's extends-order rule. Both witnessed; core suite
  grows two witness tests (InstanceIdentityTest +2,
  ClassLayoutsDiamondTest new).
- **2026-08-27 — R1 (semantic discovery — the regexes die)**: the
  pure-side `collectRoots` M3 walk (userTypeRoot / typeRoots /
  instanceRoots / lambdaRoots, the substituteInExpression vocabulary +
  upstream reprocess's arms) now supplies dependency roots to the
  native (`executeLegendLiteQuery(String[1], String[*])`); Java's
  `injectionFromRoots` resolves them in the M3 graph and dispatches on
  what each element IS. All five discovery regexes + extractClassMetadata
  + the regex halves of the enum/function extractors DELETED.
  **Differential receipts** (census method applied to the rewrite):
  run 1 found the collector's two real bugs before they could land —
  23 stack overflows on CYCLIC captured-instance graphs (cured:
  chain-seen cycle guard; the regex never saw cycles because text is
  finite) and the CapturedInstance marker leaking via the wrapper's
  own genericType (cured: own-namespace filter). Run 2: the walk
  found every element the regexes found; the ONLY regex-only rows
  were `^Pair(...)` sites wrongly injecting a shadow copy of the
  native Pair — the walk's native-class filter refuses it, and all
  four affected tests pass on the real Pair (a correctness
  improvement the flip lands silently). Flip lane: 1115/0.
  pct_adapter.pure pin 320 → 410 with written justification (the
  collector is ANTI-compensation: the pair shrinks net — Java lost
  ~150 lines of pattern-guessing).
- **2026-08-27 — R2 (pure-side derived minimum)**: P4/P6b/P8/P9
  deleted by measurement (§5b table), both `Any` fallback combos made
  LOUD (`fail` with the offending type), probes removed. The adapter
  body now reads the declared type at exactly three seams: Map
  rebuild, List rebuild, the one-witness Decimal relabel. Channel B
  Essential floor banked 295 → 297 (held across three post-change
  runs).
- **2026-08-27 — the cargo batch** (census §3's verdicts executed in
  one slice): nine dead arms deleted (J2, J8a, J8b-fallthrough, J8c
  ×2, J8d ×2, J8e, J8h, J8j, J8k+classInstance, P2), one platform
  capability landed (parseDate StrictDate-stamped literals cast to
  DATE — Scalars rule reads the node's own refined stamp), J6
  witnessed and kept with its Clause 2b note. Adapter:
  ExecuteLegendLiteQuery 1,104 → ~880 lines; pct_adapter.pure 316 →
  ~300. Gates: full chain (results recorded below on green).
