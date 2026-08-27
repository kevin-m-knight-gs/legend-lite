# TRIAGE — dispositions for the 105-defect MASTER table

Date: 2026-08-26. Evidence base: `MASTER.md` (105 distinct defects, both
falsifiers landed). This table assigns each defect ONE disposition; it does
not re-verify claims — rows whose disposition depends on an unchecked oracle
fact say NEEDS-PROBE rather than guessing (measure-before-claiming).

## Disposition vocabulary

| Disposition | Meaning |
|---|---|
| **FIXED** | landed on main with a receipt (commit + witness) |
| **ADJUDICATED** | deliberate behaviour with a recorded ruling (engine-parity / pure-faithful / declared divergence) — receipt named |
| **CHARTERED** | owned by a named program/leg that already exists; the leg's landing closes the row |
| **REAL-FIX (H/M/L)** | accepted as a defect to fix; rank = silent-wrong-answer reach vs effort |
| **NEEDS-PROBE** | disposition depends on an oracle/engine fact not yet checked — probe before fixing |
| **NEEDS-RULING** | product-scope decision only the user can make |

A row can carry a split (e.g. "part FIXED, part REAL-FIX") — the split is
stated explicitly.

## Named legs referenced below

- **E3 nullability-inference leg** — class-mapped lane labels adopt the relation
  paradigm's DDL-derivation rule; fueled by the [1]-over-nullable census
  (520 pinned, fc2fe6bd) and the 925 wire-breach census. Design receipts:
  the three-lane map (memory + session 2026-08-26).
- **Kernel-hardening leg** — the inference kernel's missing rejections
  (bounds, occurs check, freshening, Any escape).
- **Decimal-precision leg** — the dead `PrecisionDecimal` algebra wired into
  typing/lowering; one owner for (p,s) arithmetic.
- **Carrier-types leg** — declared Pure type ⇒ one wire carrier discipline
  (typed-IR program item #5; JSON-under-VARCHAR row already ledgered).
- **Signature-oracle leg** — `Pure.java` catalog reconciled against the real
  legend-pure/legend-engine checkouts (memory: verify-signatures-against-real-legend-pure).
- **Dialect-levels leg** — PLATFORM / LEGEND / LEGEND_LITE split (memory:
  three-dialect-levels); strict diagnostic flips LAST.
- **Error-discipline leg** — raw JDK exceptions → typed walls; phase-carrying
  envelope (extends the foundations program's honesty phases).
- **Graph-lane leg** — serialize/graphFetch emission correctness family.
- **Namespace-guards leg** — synthesized namespaces ($-sigil, u_map__, pk_)
  made non-user-writable; native-FQN hijack closed.

---

## S1 — CRITICAL

| ID | Disposition | Note |
|---|---|---|
| D01 | split: ADJUDICATED + CHARTERED + NEEDS-PROBE | Numeric declared-vs-column identity is the ENGINE'S rule (SetImplTransformers passes numerics untouched — audit-19 F7 adjudication, receipted on the `typeAsDeclared` arm). The pairing seam is now instrumented (DeclaredCoercions + [1]-census, fc2fe6bd). Residue to probe: the `org.duckdb.JsonNode` leak and the 2 silent-data-loss cells from A18's matrix. |
| D02 | ADJUDICATED (standing) + NEEDS-PROBE edge | `cast(@T)` converting is the standing deliberate adjudication (intake record 2026-08-26; conformance-cast provenance ledger). The widening-then-narrowing no-op (`1->cast(@Number)->cast(@Float)`) is a distinct claim — probe against engine relational behaviour before accepting. |
| D03 | CHARTERED | `trustedColumn`/late-bound Any = the fixed-schema metadata leg (metadata grids downgraded to modelable — §4bZ-U re-adjudication). Mixed-row-cell Any = carrier-types leg. |
| D04 | CHARTERED (kernel-hardening) | Generic return-type unification binds anything. V2 correction noted: monomorphic returns ARE checked (late). |
| D05 | REAL-FIX (H) | Capture-unsafe β-substitution, two sites. Silent wrong answer from ordinary lambdas. Use the falsifier's corrected repro (V24's published repro is WRONG — §6a). |
| D06 | split: ADJUDICATED + REAL-FIX (M) | `contains`/`indexOf` fold semantics = adjudicated pure-faithful (BurnLaneTest); `1==1.0` folding is pinned in the declared-divergences ledger (EqualityWorldsConformanceTest:90). REAL: silent int-overflow in folded `plus` (unfolded lane raises via DB) and static-multiplicity `size()` fold — fold must match the unfolded lane. |
| D07 | REAL-FIX (H, small) | `first(set, count)` drops `count`. One rule-table registration bug. |
| D08 | REAL-FIX (M) | n-ary concatenate skips per-element re-check; 2-arg form is correct — align. |
| D09 | REAL-FIX (H) | `match` ignores generic args + relation columns; `MatchFold` re-implements the rule `TypedMatchRuntime` exists to prevent (single-owner tenet). |
| D10 | REAL-FIX (H, small) | Unresolvable table qualifier silently reads MAIN table. Make the null-base arm loud (RelOpTranslator:110 — also D74's worst offender). |
| D11 | REAL-FIX (M) | RawSqlBoundary regex rewrites inside string literals. Needs a literal-aware scanner, not regexes. |
| D12 | REAL-FIX (M, small) | prefixedUnion exact-name vs quote-insensitive identity — use `sameColumn` (one owner). |
| D13 | CHARTERED (pivot leg) + REAL-FIX (M) | Pivot types are compile-time knowable (demand-driven-stamp leg burns the 36). The `lastIndexOf` template match + NULL-key row drop are real fixes inside that leg. |
| D14 | REAL-FIX (H) | Graph JSON envelope destroys Decimals via DOUBLE re-encode; streaming path is correct — converge on it (graph-lane leg). |
| D15 | NEEDS-RULING | SQLite has NO dialect object and inherits DuckDB spellings. Ruling: support SQLite properly (a dialect class) or WALL it loudly as unsupported. Walling is a small fix; supporting is a leg. |
| D16 | REAL-FIX (M) | H2 decode: Julian date drift pre-1582, BigDecimal→double narrowing, booleanShaped default. Three point fixes in Executor/H2. |
| D17 | REAL-FIX (M) | Subclass may override property with disjoint type / narrowed mult unchecked — ModelIntegrity must consult supers. |
| D18 | REAL-FIX (M) | 413/431 native FQNs silently user-overridable (namespace-guards leg; governance test exists for internal natives — extend the partition). |
| D19 | REAL-FIX (M, small) | `$` is user-writable in identifiers, breaking the synth-FQN reservation (namespace-guards leg). |
| D20 | split: ADJUDICATED-part + NEEDS-PROBE | Equality lanes went through the EQUALITY-0 program (excuse-arms burn; four lanes pinned). Enum-vs-String and Integer-vs-String TRUE at the typed level needs a probe against pure's `eq` (pure says false) — likely REAL but verify which lane leaks. |
| D21 | CHARTERED (decimal-precision leg) | Precision-blind literal typing + dead D-suffix guard. |
| D22 | CHARTERED (decimal-precision) + REAL-FIX (M, small) | The hard-coded `DECIMAL(5,2)` in the numeric-over-string arm is a point fix now; scale reconciliation belongs to the leg. |
| D23 | NEEDS-PROBE → likely REAL-FIX (M) | Join-type annotations parsed and ignored (all hops LEFT). Probe the engine's per-hop join-type semantics first, then implement to parity. |
| D24 | REAL-FIX (M) | `includeType` emits the static class, not the runtime class (graph-lane leg). |
| D25 | REAL-FIX (M, small) | `at(2^32+1)` truncates to int and reads a different column — exact-arithmetic family (same class as D32, now fixed; batch with D77). |
| D26 | REAL-FIX (M, small) | Overload dispatch by `endsWith` — the BANNED idiom (exact-FQN rule). Replace with a real mangler. |
| D27 | REAL-FIX (M) | Empty-collection identities in scalar lane but not aggregate lane. Also an E3-adjacent mechanism (empty-group aggregate NULLs are in the 925). Fix = one identity owner both lanes. |
| D28 | NEEDS-PROBE | `==` deliberately omits null guards (comment says so). Probe what the ENGINE emits for `==` over nullable operands before ruling — 3VL leak may be engine parity. |
| D29 | CHARTERED (kernel-hardening) | The Any escape hatch in compatibleRebind. |
| D30 | CHARTERED (carrier-types leg) | Enum identity collapses to bare strings on every carrier path. |
| D76 | CHARTERED (decimal-precision leg) | NameResolver drops (p,s) — `Decimal(18,4)` uncompilable. High-priority row INSIDE the leg (natural money declaration broken). |
| D77 | REAL-FIX (M, small) | Protocol long→int narrowing, no range check — exact-arithmetic family; batch with D25. |
| D78 | NEEDS-PROBE (harness-adjacent) | Six disagreeing Pure→SQL DDL mappings. Partly test-infrastructure; census the live consumers before consolidating (single-owner tenet applies). |
| D79 | NEEDS-PROBE (harness-adjacent) | generateTestData round-trip lossy. Probe engine's generateTestData contract first. |
| D91 | REAL-FIX — FOLD INTO F10 SLICE 4 | Structural equality erases class identity; `equality.Key` ignored on execute path. This IS carrier semantics (InstanceEquality exists on the verdict lane; slice 4 arms it on the execute path). |
| D92 | split: FIXED-part + CHARTERED | `times()` integer kind FIXED (Part-1, e2d456cf). The [1]-NULL returns = D31d (E3/signature-oracle). hasDay→Integer, hash→Long, quarter→number = carrier-types leg rows. |
| D93 | split: ADJUDICATED-part + NEEDS-PROBE | NULL-thru open-ended rows: ENGINE-PARITY adjudicated (engine goldens spell bare `thru_z > asOf` — receipt found 2026-08-26). Still open to probe: temporal class over a table with NO milestoning block (silently unfiltered vs engine), and the snapshot-column default. |
| D100 | REAL-FIX (H) — QUEUED next batch | HandleStore key omits model/store identity; cross-caller data leak. Queue item 4. |
| D101 | REAL-FIX (H) — QUEUED next batch | sort emits no ASC null clause; DuckDB-only by luck. Emit pure's null-order explicitly (window lane already does). Queue item 4. |
| D102 | split: FIXED-half + REAL-FIX (M) | Defect-predicate half FIXED (32fcde0c, oracle-receipted "Unable to evaluate" arm + witness). OPEN: the inheritance-Operation route (`Base.all()` drops the base extent; UnionSynthesis strict-subclass walk + single-member shortcut) — graph/union real fix. |

## S2 — HIGH

| ID | Disposition | Note |
|---|---|---|
| D31 | CHARTERED (E3 + enforcement-geography adjudication) with named sub-splits | The family row. (l) `^Class()` missing [1] = FIXED (Part-1 NewValidator, e2d456cf). (e)/(f) toOne: value-lane RAISES (lane ruling fa348c14); row-lane flow = ADJUDICATED engine parity (engine does not enforce on store-read data — enforcement geography receipts). (a)(b)(c)(j)(k) nullability manufacture = E3 burns by construction (DDL-derived labels + join/pad provenance). (d) aggregate `[1]`-over-empty-group signatures = signature-oracle leg (see D81's narrowings). (g)(h)(i) mult-check gaps = kernel-hardening. Upper-bound over store-read ([2]→3) = engine-parity ADJUDICATED (same geography); value-lane upper bound = follow-the-type-system, needs its own witness when touched. |
| D32 | FIXED | 32fcde0c: exact arithmetic; algebra pins. |
| D33 | REAL-FIX (M) | clampTdsCells re-stamps `[2]`/`[1..*]` as `[0..1]`; extend doesn't clamp — one rule, one owner. |
| D34 | CHARTERED (kernel-hardening) | Row-typed column accepted in project/extend. |
| D35 | CHARTERED (kernel-hardening) | `if` over relations fabricates union-of-columns type. |
| D36 | CHARTERED (kernel-hardening) | Unbounded tyvars, silent widening, no occurs check, no freshening — the leg's core. |
| D37 | REAL-FIX (H, small) | `extends Nil` = subtype of everything. Reject Nil as a declared supertype (one guard). |
| D38 | CHARTERED (kernel-hardening) | extends-list/generic-head never classified. |
| D39 | REAL-FIX (M, small) | Free mult var `[m]` on class property accepted — parser/integrity scope check; contradicts the Multiplicity doctrine comment. |
| D40 | CHARTERED (kernel-hardening) | Lambda result mult lower bound lenient; Nil body skips. |
| D41 | CHARTERED (carrier-types leg) | Integer width/carrier zoo — the leg's headline row. The off-by-one `bitLength() < 63` gate is a point fix inside it. |
| D42 | CHARTERED (decimal-precision leg) | commonSupertype returns second operand. |
| D43 | CHARTERED (decimal-precision leg) | Dead PrecisionDecimal algebra — the leg's core. |
| D44 | CHARTERED (decimal-precision leg) | #TDS `:Decimal` = (38,0) lie. |
| D45 | REAL-FIX (M) | Root types lie on serialize/object-map/graph-nav; ResultShape prefix test on user-writable name (also namespace-guards). |
| D46 | REAL-FIX (M, small) | graphFetch tree root class never resolved; serialize can emit unfetched properties. |
| D47 | CHARTERED (dialect-levels leg) | Dialect guessed; two surfaces disagree; silent DuckDB fallback. The leg's motivating row. |
| D48 | REAL-FIX (M) | User type annotations parsed then discarded (colspec, lambda params). Honor or reject loudly — silent discard is the worst option. |
| D49 | NEEDS-RULING | Store identity as a type-level concept (cross-store join walls only at connection routing). Product decision. |
| D81 | CHARTERED (signature-oracle leg) | 183/721 diverge vs real Legend; 5 return-mult narrowings license [1]-NULL. The leg = reconcile against the local checkouts, corpus-gated; the 5 narrowings + `datePart`/`extend`/`over` rows first. |
| D82 | CHARTERED (signature-oracle leg) | The 8+ tdsVocab string desugars join the catalog or get pinned signatures. |
| D83 | CHARTERED (foundations/error-discipline) | Decline tunnel swallows soundness walls — foundations program already instruments declines (56); the fix = decline classifier must not catch egress guards. |
| D84 | REAL-FIX (M, small) | effectMemo writes `false` on cycle — classify in-progress as effectful (conservative) or two-phase. |
| D85 | CHARTERED (foundations/null-policy) | Type-valued fallbacks (String default, [1] default, VARCHAR probe) — the no-fallbacks program owns; A21's 10 violations are its worklist. |
| D94 | split: CHARTERED (E3) + slice-4 note | Struct.Field has no nullability/bound — the E3 label dimension reaches the struct layer; the [1]-vs-[*] diamond layout row belongs to the F10 carrier work. |
| D95 | REAL-FIX (L) | Lineage/validation re-derive without typed HIR; PkInference simple-name dispatch = banned idiom (batch with D26). |
| D103 | REAL-FIX (L) | SPI ElementSink validates nothing; registry/lexer desync. Parser drop-in program's seam — add the contract there. |

## S3 — MEDIUM

| ID | Disposition | Note |
|---|---|---|
| D50 | CHARTERED (error-discipline leg) | Raw JDK exceptions from record ctors/literals/index reads. The leg's worklist; note V1's phase correction (fires lazily at G). |
| D51 | CHARTERED (carrier-types leg) | StrictTime/Byte have no carrier; declared-but-unreferenced VARBINARY kills plans. Wall-or-carry decided in the leg. |
| D52 | REAL-FIX (M, small) | Zero-column relation accepted → `SELECT *` → egress ICE; Select/Distinct already guard — align Project. |
| D53 | CHARTERED (error-discipline leg) | 112 type-check-then-internal-exception natives. The leg's census IS this row. |
| D54 | REAL-FIX (L) | SOE on deep nesting — depth budget at the parser (thresholds are JVM-dependent, fix is the budget not the number). |
| D55 | REAL-FIX (M) | 7 bad-SQL shapes from checked plans (unparenthesised set branches = 234 of the 431 fuzz hits; `drop(0)` kills the session). Parenthesise + guard LIMIT/OFFSET — mostly renderer point fixes. |
| D56 | REAL-FIX (L, small) | groupBy→filter→size scalar-scope ICE. |
| D57 | REAL-FIX (L, small) | instanceOf never consults isSubtype. |
| D58 | REAL-FIX (L, small) | INTERNAL_DESUGAR guard on bare-name branch only — apply to FQN branch. |
| D59 | REAL-FIX (L) | Nil-argument overload ambiguity (763 cases) + wrong function named in diagnostic. |
| D60 | CHARTERED (dialect-levels leg) | No capability gate pre-render; SQLite vocabulary borrowed (see D15 ruling). |
| D61 | REAL-FIX (M) | Platform's own JSON wire unreadable by its own readers (Infinity/NaN, >long ints). Fix the readers + spell non-finite per wire contract. |
| D62 | REAL-FIX (M, small) | objectReference builds JSON by concatenation, no escaping; embedded hard-coded H2 descriptor. |
| D63 | REAL-FIX (M, small) | Rounding-necessary ArithmeticException from union over Integer-declared DECIMAL; IOOBE from at()/MetamodelSteps — clean walls exist on sibling paths, align. |
| D86 | REAL-FIX (M, small) | Interval unit rides as StringLit arg-0 — the banned string-channel; typed `IntervalUnit` enum (grammar-first-nodes tenet). |
| D96 | CHARTERED (error-discipline leg) | 72% internal throws, monotone degrade, NotImplementedException not a LegendCompileException — the leg's charter row. |
| D97 | REAL-FIX (L, small) | PlanText.pureName missing PrecisionDecimal/LatestDate/StrictTime arms. |
| D98 | REAL-FIX (L, small) | pk_0/driver-PK synth names user-writable (namespace-guards leg, batch with D19). |
| D105 | NEEDS-RULING (security-flavoured) | HTTP surface: raw leaks, HTTP 200 for failures, SQL/catalog in errors. Server surface priority is a product call; the info-leak half should not wait long. |

## S4 — LOW

| ID | Disposition | Note |
|---|---|---|
| D64 | REAL-FIX (M) | Column-identity inconsistencies (rename no-op on quoted; UNION vs DIFFERENCE identity; `__\|__` user-writable). One identity owner (`sameColumn`) everywhere. |
| D65 | REAL-FIX (L) | Remaining hand-rolled stamp/subtype/LUB copies — the Multiplicity-owner program's unfinished sweep (algebra owner exists; migrate the 6 isMany spellings + 6 ladders onto owners). |
| D66 | REAL-FIX (L) | Dropped metadata at phase boundaries (exec.Column.multiplicity write-only; signatureKey embeds SourceInfo). Batch of point fixes. |
| D67 | ADJUDICATED-latent (both auditors: unreachable from surface syntax) | HIR self-validation — revisit if a surface route appears; do not build speculative validators (YAGNI per audit-rederivation doctrine). |
| D68 | CHARTERED (dialect-levels leg, standing F-item) | carryThrough + tolerated-mute is the RECORDED debt ("carryThrough lives beside admissible() pending the dialect-levels leg", guest-list record). MISMATCH-unreachable claim: note the census now reads the tree post-reconciliation BY DESIGN (labels are the contract); the instrument's blindness both ways (with D89) is the leg's acceptance criterion. |
| D69 | REAL-FIX (L) | Wire information loss (CSV NULL≡empty, four DateTime spellings). Wire-contract cleanup; coordinate with D61. |
| D70 | REAL-FIX (L) | Ignored config/modality flags — either honor or wall loudly, flag by flag. |
| D87 | split: ADJUDICATED-part + NEEDS-PROBE | DATE_TRUNC→TIMESTAMP fact is CORRECT on the 1.5.0 reference jar (probed; the 1.4.4 CLI returns DATE — recorded version-skew trap; the audit likely probed the wrong jar). Probe the remaining three (OrderedListAgg VARCHAR, EPOCH_SECONDS, IntLit BIGINT-vs-INTEGER) on 1.5.0 before touching. |
| D88 | REAL-FIX (L, small) | SqlRewriter drops Lambda TypeFact; Reducer accepts all 41 Fn — small transport/typing hardening, latent today. |
| D89 | REAL-FIX (M, small) | Census normalizeMeta missing DOUBLE PRECISION arm → false H2 divergences. Instrument correctness — fix promptly (it feeds pinned ledgers). |
| D90 | REAL-FIX (M, small) | execute() gates withEngineExistsJoinForm differently from plan/streaming — one pipeline, align (with D80). |
| D80 | REAL-FIX (M) | executeWire/executeStreaming drop dynamic-pivot columns; staticize belongs in the shared lowerQuery front. |
| D99 | REAL-FIX (M) — TEST-INFRA LEG | The META row: 0.06% of tests relate declared type to delivered carrier. Fix = a carrier-assertion lane (declared pureType/mult vs decoded Java class) added to the standing harness — pairs with the carrier-types leg; also un-pin the 3 tests that pin wrong behaviour as each underlying row lands. |
| D104 | NEEDS-RULING | Public API exposes no usable type info (graph columns=[], executeSql discards ResultSet, LSP no hover). Server-surface investment is a product call. |

## S5 — DOC

| ID | Disposition | Note |
|---|---|---|
| D71 | CHARTERED (signature-oracle leg) | Self-golden catalog — the leg replaces the self-comparison with the checkout-diff oracle (A30's harness is the prototype). |
| D72 | REAL-FIX (L, small) | Type.java's three false claims; the 7 missing PrecisionDecimal arms join the decimal-precision leg's worklist. |
| D73 | REAL-FIX (L, small) | AGENTS.md §3a corrections (36 variants, Struct, string channels list) — doc truth restored with the D86 fix. |
| D74 | CHARTERED (foundations/null-policy) | The no-fallbacks census (4,237 rows) — the standing program's measurement; A21/A18 rankings are its worklist order. |
| D75 | REAL-FIX (L) | ~20 false javadoc claims — fix each WITH its underlying defect row (never as a standalone doc pass; the comment must describe landed behaviour). |

---

## Summary counts

| Disposition | Rows (splits counted by primary) |
|---|---|
| FIXED (receipted) | D32, D102-half, D92-part, D31l — landed 2026-08-26 |
| ADJUDICATED (receipted rulings) | D01-core, D02, D06-part, D20-part, D31e/f-rowlane, D67, D87-part, D93-part |
| CHARTERED (named legs) | ~30 rows across E3, kernel-hardening, decimal-precision, carrier-types, signature-oracle, dialect-levels, error-discipline, foundations, pivot, graph |
| REAL-FIX H | D05, D07, D09, D10, D14, D37, D100, D101 |
| REAL-FIX M | ~25 rows (see tables) |
| REAL-FIX L | ~15 rows |
| NEEDS-PROBE | D02-edge, D20-part, D23, D28, D78, D79, D87-rest, D93-rest, D01-residue |
| NEEDS-RULING | D15 (SQLite), D49 (store identity), D104/D105 (server surface + leak half) |

## Proposed execution order (post-triage)

1. **Queued batch (ratified)**: D100 + D101.
2. **F10 slice 4 folds in**: D91 (+ D94's layout row).
3. **Small-batch A (silent-wrong-answer, low effort)**: D07, D10, D37, D12,
   D25+D77 (exact-arithmetic family), D84, D89, D90, D22-point, D52.
4. **E3** (D31's nullability lanes, D94 label dimension, D27's aggregate-lane
   mechanism) — ordering vs slice 4 = open user ruling.
5. **Probes** (read-only, batchable any time): D23, D28, D87-rest, D93-rest,
   D20-part, D02-edge.
6. Legs by yield thereafter: signature-oracle (licenses D31d + D81 + D71),
   kernel-hardening, decimal-precision, carrier-types, error-discipline,
   dialect-levels (already sequenced last per standing directive: strict flip LAST).
