# THE OPEN REGISTER

ONE list of every open item, with its source and size. Maintenance
rule (part of every slice's definition of done): a row moves to the
CLOSED section IN THE SAME COMMIT that closes it; new deferrals add a
row in the same commit that defers them. "What's unfinished?" must
always be a thirty-second read of this file.

Size classes: S (< half a gate cycle), M (one to a few slices),
L (a program leg).

## 1. Verdict work (CANONICAL_FORM_SPEC / STAMP_DISCIPLINE_PROGRAM)

| # | Item | Size | Notes |
|---|---|---|---|
| V7 | Corpus-lane cutover + harness arm DELETION | L | decoded-golden-text + grid problems are corpus-only; LAST, after PCT lane proves the system. PREREQUISITES V10a/V10b below. |

| V8 | R3 tolerance census: 2-ULP + the 21 cross-engine float rows | M | retire or declare; both counted today. Candidate retirement design (2026-08-22, user-briefed): same-arithmetic H2 referee — byte-differing Double pairs re-compare EXACTLY on H2 (the goldens' own libm); tolerance dies |
| V9 | Grid byte cutover closing slice (after V4/V8) | S | ledger says: ORDER BY + policy + GridCompare arm deletion, no emission |
| V12 | Single round trip per assert (user design 2026-08-22): side-tagged UNION ALL — per-side TYPED value columns NULL-padded (no promotion erasure), per-side canon columns, ORDER BY side+canon; literals INLINE in the same statement (never host-folded — testEmptyChar proved literal emission needs exercising); tunnel gains a rung (fused→split→bare→fold). GO/NO-GO: measure query.exec share via TimingLedger first | M |
| V13 | WHOLE-FUNCTION fusion (user insight 2026-08-22: assert = the verdict OVERLAY, the graphFetch→serialize species — 4th overlay after graph/PCT-wire/snapshot): let IS WITH (materialized CTE = evaluate-once let semantics, also dissolves within-test F13 identity), verdict table out, typed list() evidence columns for the referee. HAZARD: eager evaluation vs pure first-failure sequencing → fusion-gradient tunnel. SEQUENCED AFTER V7 (perturbs the golden-text lane, like prepared statements). This IS the legend-sql thesis in miniature | L |

## 1b. VERDICT RULE AUDIT (docs/VERDICT_RULE_AUDIT_2026_08_22.md — every rule vs engine source)

| # | Item | Size |
|---|---|---|
| X6 | 2-ULP reclassified: compensates IEEE-double carrier vs engine's exact-decimal floats — R3 decides declared-policy vs decimal carriage | R3 |

## 2. Audit findings still open

| # | Item | Source | Size |
|---|---|---|---|
| A1 | 1==1.0 / indexOf base / substring base — PER-LANE adjudication w/ engine witnesses | COMPILER_SHORTCUT_AUDIT §4 | M (rides PCT burn bucket 4) |
| A2 | Parser invention census (53 skew + 42 crash rows) | DEEP_AUDIT_HANDOFF | M |
| A3 | Parser lenient→strict flip (LAST, after A2) | DEEP_AUDIT_HANDOFF:95 | M |
| A4 | Foundations Phase 3 de-duplication | FOUNDATIONS_PLAN §4 | M |
| A5 | u_map__ name sniff → explicit flag | deep-audit tier-2, AWAITS RATIFICATION | S |
| A6 | agg_N collision scan | deep-audit tier-2, AWAITS RATIFICATION | S |
| A7 | Raw-SQL literal-aware rewriting | deep-audit tier-2 (merges into prepared-statements leg) | M |
| A8 | static-final/ThreadLocal guard visibility | deep-audit tier-2, AWAITS RATIFICATION | S |
| A9 | missing-[1] on ^new | deep-audit tier-2, AWAITS RATIFICATION | S |
| A10 | nlq/server hardening (incl. uncached-connection closing). FLAKE WITNESS 2026-08-22: DiagramServiceTest.httpEndpointReturnsErrorForMissingCode 404-vs-400 once under the full G1 suite, 3/3 green standalone — port/leaked-server contention class | deep-audit tier-2, AWAITS RATIFICATION | M |

## 2b. CONTRACT PROGRAM (re-framed 2026-08-23 from the typed-IR program, user step-back: labels are NORMATIVE CONTRACTS, not descriptions to verify — stamp says what it means, label RECORDS how it travels [written at CONSTRUCTION by the emitter that knows], wire metadata PROVES delivery [rides with the data, no round trip]. Dialects CONFORM to contracts [the SqlFn.ROUND precedent extended to types]; contracts derive from PURE SEMANTICS [never blind wire-adoption: HUGEINT sums protect testLargePlus — adopt; value-changing casts forbidden]. The static judgment RETIRES as an inference engine; its rules relocate into typed builders incrementally. Each wire divergence adjudicates one of three ways: ADOPT (reality into the contract) / CONFORM (cast-or-normalize at the dialect boundary, value-preserving only) / FIX-EMITTER (record the representation choice). Nullability: construction-carried (builders know), guarded by VALUE-level decode tripwire (metadata is unknown-happy; DuckDB spells all-NULL columns INTEGER — recorded wire-census caveat))

| # | Item | Size |
|---|---|---|
| T1 | Slice 1 LANDED (static census, now the ARCHAEOLOGY record): SqlTyping bottom-up judgment (PARTIAL — null = no rule, never a guess) + SqlTypeCensus at the Executor choke point (declared OutputCol vs computed, classified). FIRST CENSUS (PCT lane): 13,204 agree / 364 mismatch / 808 untyped; mismatch tail = the KNOWN carrier conventions (TIMESTAMP<>VARCHAR precision convention 231, DOUBLE<>VARCHAR print-form carrier 20, Decimal width-widening ~13) + a handful needing eyes (DOUBLE<>Decimal 2). Untyped top: NullLit 123 (admissible-by-design), UNNEST 89, Case 66, LIST_MIN/MAX 82, ADD_INTERVAL 37, correlated Columns 36, numeric promotion | done |
| T2 | WIRE CENSUS LANDED (probeWire at executePrepared: label vs ResultSetMetaData, per dialect, classified+witnessed, failure-isolated). First reading (PCT std): 3,037 agree / 517 diverge / 0 unknown — NEW wire facts: DuckDB narrows int literals to INTEGER (199, value-safe), UBIGINT exists in our wire (5), all-NULL columns spell INTEGER in metadata (~82 — the padding class in wire form). Judgment-coverage growth RETIRED as a goal; rules relocate to builders | done
| T3 | ADMISSIBILITY relation: adjudicate each mismatch class as admissible-carrier (temporal VARCHAR, print-form, width-widening) or LIE; conform-by-emission at the lying seams (the 3 decline survivors' fix rides here — letFn to_json boxing, map row-shape, byte-carrier label). ADJUDICATION BURN LANDED 2026-08-23: `delivers(label, meta)` is the delivery relation (exact match after normalize / value-subset integer chain BIGINT←INT/SMALLINT/TINYINT / DECIMAL(p,s) same-scale narrower p / registered carrier conventions via admissibleWire). PCT diverge 1,778→79, corpus 7,492→181 — ceilings PINNED shrink-only (ChannelB suites ≤80 diverge/≤110 adopt-pending; corpus runner ≤181/≤130). Residue = THREE named families, witnesses attached: (1) hash() UBIGINT under BIGINT label ×5 (testHashCode — CONFORM: signed cast at the hash emission); (2) percentile/quantile returns input type BIGINT under DOUBLE ×3 (testPercentile — RE-ADJUDICATED against engine source: signature is `percentile(Number[*],Float[1]):Number[0..1]`, so discrete percentile over Integers IS an Integer and a DOUBLE cast would be value-changing/WRONG — belongs to family 3, the DOUBLE label is the erasure); (3) Number-erasure decimal delivery (zScore/max/median/DECIMAL(18,6) store reads — abstract-Number slots, rides the builder slice). ADOPT-PENDING bucket = integer aggregates delivering HUGEINT (108 PCT/130 corpus): contract WIDENS at construction per the testLargePlus rule, queued. int-or-null bucket (177/83) = all-NULL metadata ambiguity, resolves at the T4 decode tripwire | M |
| T4 | FLIP label authority: OutputCol computed from the judgment, stamp-vs-computed divergence alarm pinned 0, census counters become the permanent verifier. ADJUDICATED 2026-08-23 (user challenge killed a fixer pass): OutputCol.nullable today MEANS multiplicity-echo (PureSql.nullable is its only writer), NOT wire nullability — the engine has no such flag at all (nullability lives in pure multiplicity; union pads/subtype markers are unlabeled plumbing its Java assembly handles). The ~6.5k null-under-required-multiplicity rows are the flip's measured RE-LABEL backlog, not bugs; the meaning changes WHOLESALE at the flip (one meaning, one owner) — never per-site (mixed-meaning flags are worse than either meaning; the rejected NullPadLabels pass was the builder+fixer anti-pattern) | L |

## 3. Recorded engineering follow-ups (each noted in code/doc at its site)

| # | Item | Size |
|---|---|---|
| F1 | GraphEmission:2714 nested-nav TypedLimit (D6a family) | S |
| F2 | unwrapElemRefs Exists/ScalarSubquery pre-existing hole | S |
| F3 | CarrierStrategies CompactList strategy for H2 (145 loud h2-replay declines) | M |
| F4 | Scoped-run seeding artifact (-Drcorpus.only fails aggregationAware at HEAD) | M |
| F5 | Per-family corpus seeding (#112) | M |
| F6 | Derived-property identical-signature dup rejection | S |
| F7 | Dup-FQN coverage: services/connections/mappings namespaces | S |
| F8 | {target} + foreign-db join-ref validation (D6b skipped conservatively) | S |
| F9 | Invariant-3 register burn-down: wrap 21 write-once tables immutable | M |
| F13b | Identity v2 residue: (a) the ARRAY-shaped keyless side (one witness — a [*] side whose plan projects an array-of-struct cell; the identity canon's struct_extract fails at bind and rides the canon-exec decline tunnel, counted); (b) lambda-minted ctors (site id cannot distinguish per-element evaluations — declined by the v1 exclusion scan, zero PCT witnesses today); (c) inlined-function-body ctors with SUBSTITUTED args rebuild per side (α-substitution) — a `let p = makeP('a')` shape would re-mint per side; zero witnesses (G9 TRUE-WIRE-BUG=0 pins it), fix = one inline pass per assert statement | S/M |
| F11 | Effectful-assert byte coverage: the containsEffect gate routes effectful assert statements to body inlining (host verdicts only) — the gate stands on statement-orchestration grounds (V11 adjudication at the gate site), so claiming these needs the side path to learn sequential effect execution; V7-territory sizing | S/M |
| F15 | Reference-adapter parser ingress: ExecuteLegendLiteQuery's SIX source-extraction regexes + reEscapeStringLiterals are a SHADOW PARSER (standing tenet violation, predates parser parity 6489/0) — parse PCT source with THE parser, splice from the AST, delete the patterns | S/M |
| F16 | Adapter kind-consolidation: toCoreInstance re-decides kind narrowing the Executor codec now owns (X-audit) — adapter receives kind-faithful values and ONLY boxes; the declared-type consult arms decay as F10/stamp fidelity lands. remapErrorMessage dies with the error-composition leg | S (rides F10) |
| F10 | Variant-aware byte canon — V1 LANDED 2026-08-23 (the LITERAL CHANNEL): Any-involving pairs byte-compare in pure's own literal spellings (six disjoint forms carry kind in the bytes), dispatched on the JSON carrier's runtime type IN THE DATABASE (anyJsonCanon: json_type CASE); typed sides append a literal candidate (guarded by column kind); Any-stamped plain columns render the COLUMN's literal (wire fact); trees mark U+0001 and the verdict DECLINES on sight; the canon-exec tunnel gained the MIDDLE RUNG (drop-literal re-wrap — a lying stamp never demotes bare byte verdicts); mixed-numeric gate exempts JSON-carried sides (no promotion). Declines 13 -> 3 (agree 1645); Pair-of-Pairs claimed by SUBSTITUTION-AWARE EqualityKeys (instantiation-keyed cycle guard). REMAINING RESIDUE = 3 stamp-circularity wire lies (tunnel-counted): map ManyToMany struct-array (F13b a), letFn raw-VARCHAR-under-JSON-stamp, mixedSort Number-stamped mixed (engine-frontier test, fails both channels anyway). F10 PROPER still open: kind-tagged variant carrier (temporals/Decimals erase to JSON strings/doubles — engine equal('2014-01-01', %2014-01-01) FALSE is undecidable on this wire, host referee equally blind), carrier-owned decode, retires the +0000/D-suffix canon strips; fix the three stamp lies by conform-by-emission at the Any output seam | M |

## 4. Parked BY THE RATIFIED ARC ORDER (sequenced, not debt)

| # | Item | Size |
|---|---|---|
| P1 | Decoupled-PCT completion burn — RE-MEASURED 2026-08-23: the walls hide ZERO PCT tests (the '65 hidden' figure died with the relation wall burn, and the residual '3 essential hidden' was a grep counting COMMENT mentions in surveyor.pure — channel B discovers the on-disk truth in all five families, 1,118, MORE than channel A's configured 1,109: relation qualifier config filters ~7, grammar/unclassified ±1 enumeration edges unchased; B-only rows currently IMPUTE channel A's verdict in the diff — an A-ABSENT bucket would make the census exact). The burn's real payoff: (a) the m4-grammar differential (A5, 183 rows pinned ≤226 — six parse constructs: value-parameterized types, unit literals, @-multiplicity/@-relation-type annotations, raw ^instance graphs); (b) the plain <<test.Test>> universe those files carry (~62 unit tests in the six parse-walled files alone — the P2 test-corpus territory); (c) the reflection families (Multiplicity/ValueSpecification/PackageableElement model walls). Plus the still-live: instance-universe 13, date-error 5, big-number 4, A1's 3, prim-ext 2; frontier-12 stays pinned | L |
| P2 | ###Data execution → test-corpus branch unlock (DEFERRED_TEST_EXECUTION.md; census first) | L |
| P3 | Corpus burn-to-zero resume (2,347/2,575 — 228 left) | L |
| P4 | Prepared statements (LAST — perturbs the golden-SQL text lane; absorbs A7) | L |

## 5. Declared leniencies (LIVE POLICY, counted — revisited at V7/V8)

- Corpus temporal golden compares are INSTANT-based (goldenEqualScalar,
  H2Verify.norm) — the engine's two-subsecond-spellings adjudication.
- 2-ULP Double×Double dialect-arithmetic policy — USER-RATIFIED
  2026-08-22 as declared+counted (cross-libm last-ULP drift on
  transcendentals: H2/Java-minted goldens vs DuckDB acos/log/tan; no
  emission fix exists). Lives in TWO counted places: the host lattice
  arm (LL_TOL_COUNT instrument) and the byte-verdict policy arm
  (sqlUlpPolicy census); the golden seam falls through to the lattice
  rather than judging value-differing numeric pairs itself. GridCompare
  sig-digit cell tolerance (the 21 rows) unchanged. R3/V8 owns
  retirement (H2 same-arithmetic referee design).
- Float canon non-finite pass-through (witness-free edge,
  referee-guarded); the DECIMAL(38,18) unfold is DEAD (V10c textual
  exponent unfold).
- Latent Float×Decimal integral tension (host true / byte false) —
  zero witnesses, documented in R0 §3.
- STRING_AGG input-order contract (Render precedent, not a guarantee).
- Engine-frontier 12 (the engine's own relational executor fails them
  too) — pinned, burn if the engine moves.

## CLOSED

- RELATION WALL BURN CLOSED (2026-08-23, the 61-test discovery gap):
  over.pure (68 window PCT tests) and pctQualifiers.pure compiled —
  the '?' schema-algebra column wildcard classifies as the anonymous
  TypeVar (the InferenceKernel UNKNOWN_COLUMN_TYPE convention), and
  Profile self-stereotypes/tags parse in platform lanes and DROP
  faithfully (the engine protocol Profile has no applied-annotation
  field; the ENGINE GRAMMAR has no such slot — verified in
  DomainParserGrammar — so the LEGEND dialect refuses verbatim,
  refusesLiteExtensions-gated). Relation discovery 287 -> 355 (MORE
  than channel A's own 348 — its qualifier config filters ~7); 66 of
  68 new tests passed OUT OF THE BOX; the two failures were ONE
  renderer bug — the aggregate-ORDER-BY hoist dropped declared null
  placement (pure DESC NULLS FIRST sank to backend default;
  AggOrderNullPlacementTest pins it). Relation walls 23 -> 20, suite
  100% at the expanded universe (355/355, TRUE-WIRE-BUG 0). Channel B
  totals: 1,082 pass / 1,118 discovered.
- F13c CLOSED (2026-08-23, eq/equal identity IN CONDITIONS — user-driven:
  "do we pass the eq tests?"): the in-SQL eq/equal/contains/in arm
  family (InstanceEquality, identity lane only) compiles the ENGINE
  equality relation from the verdict layer's OWN canon — ONE owner. eq
  = __id compare (identity for keyed AND keyless; NO static classifier
  fold — a supertype-stamped alias of the same instance stays TRUE);
  equal/== = canonical-render compare (key tree / identity), static
  cross-class folds FALSE; contains/in = canonical membership
  (list_transform to canon texts — engine contains() is equal() per
  element). Identity layouts now cover ALL model classes (eq needs
  identity on keyed classes too; platform carriers excluded — their
  ctors short-circuit the layout). Assert-CONDITION sides join the
  identity lane (evalValue identity flag; boolean egress keeps every
  other lane blind). ENGINE-VERIFIED shadow rule: EqualityKeys now
  dedupes by ALL declared property names (_Class.collectEqualityKey-
  Properties = simple properties filtered by stereotype) — an un-keyed
  subclass redeclaration REMOVES the super's key (witness
  OtherBottomClass). Lowerer split at the file guardrail:
  InstanceEquality + InstanceProjection extracted (3684 -> 3378).
  RESULT: testEq/testEqualNonPrimitive PASS as B-FIXES-A (channel A
  EXCLUDES them — identity unobservable on its value wire; ours rides
  as data), grammar 130 -> 132; contains/in NonPrimitive regressions
  caught by the suites and claimed the same way. Declines 13 unchanged,
  disagree 0.
- F13 CLOSED (2026-08-22, synthetic instance identity): keyless-class
  equality is engine-true IDENTITY in BOTH channels, carried as DATA —
  the verdict lane's identity layout appends __id to keyless classes
  (ClassLayouts.layoutOf(.., withIdentity), RIDER LANE ONLY: golden-SQL
  text lanes and corpus value lanes keep the plain layout, zero
  perturbation), minted deterministically per construction-site NODE
  (InstanceIds in the ExecEnv — both sides share the minter; a copy
  site mints a NEW id). The byte canon renders {_type,_id}; the host
  lattice compares wire maps that now CARRY the id (content
  fabrication for keyless pairs is dead); eq()'s non-primitive wall
  opens for id-bearing wires (eq = id equality — ids unique per site).
  LOAD-BEARING FIX: UserCallInliner's TypedNativeCall/TypedEval/
  TypedLet/TypedLambda arms rebuilt unchanged subtrees, breaking the
  file's own "untouched subtrees keep identity" contract — sameRefs
  identity-preservation restored it (side-e/side-a now reach the same
  ctor NODE). Guards: keyless-ctor-under-lambda declines (counted, v1
  exclusion), identityless-instance-wire declines (a __id-less
  instance map never byte-judges; Map carriers exempt — mapEquals is
  F12's rule). PCT-lane declines 19 -> 13 (agree 1564 -> 1570,
  disagree 0). ATTRIBUTION CORRECTED same day (measure-before-claiming
  trip): F13 moved ZERO test-level outcomes — pre/post Essential FAIL
  sets are byte-identical at 292/21/10/4; the AGREE-PASS/WIRE-BUG pins
  (288/11 -> 292/10) were banking a PRE-EXISTING measured state left
  unratcheted by earlier slices, not an F13 effect. What F13 moved is
  the JUDGING: 6 keyless pairs from host-referee decline to DB byte
  verdict, engine-true. Ceilings BANKED (30/35/35/45 -> 15). Residue →
  F13b.
- F12 CLOSED (2026-08-22, the Map canon): mapEquals byte-decidable —
  entry texts [kLeaf, vLeaf] per key (map_extract pairs), SORTED (the
  engine's order-insensitive rule becomes byte comparison), JSON-framed
  with the carrier fqn; leaf kinds from the MAP layout's static types.
  Host referee already mapEquals-shaped (wireTree key-set + per-key).
  Declines 35→25; the 3 Pair unclaimable-leaf rows went with it.
- F14 OPENED AND CLOSED SAME DAY (2026-08-22, user chain of catches):
  the "unSQLable NUL string" was never a value-domain fact — DuckDB
  VARCHAR holds NUL fine (chr(0) concatenates/compares exactly,
  user-verified empirically); the failure was OUR StringLit renderer
  embedding the raw byte into statement text and killing the SQL
  LEXER. Fixed at the spelling: stringLit splices chr(0) between
  quoted segments. The BLOB-carrier design drafted in between is
  RETIRED unneeded; "Tier A" lost its best member to a renderer bug.
- X5 CLOSED (2026-08-22, equality.Key — DB-first per user directive):
  our Pair/List declarations now CARRY the engine's <<equality.Key>>
  stereotypes (root cause of the instance declines — the parser had
  preserved property stereotypes all along, compilation dropped them;
  Property.Stored gained the flag); EqualityKeys resolves the key tree
  from the model (hierarchy walk, keyless/cyclic poison → null);
  keyed-instance BYTE CANON in the DB — JSON framing with OUR canon
  strings as values (user ruling: JSON is the framing, never the
  spelling), '_type' carries the classifier in the bytes, kind-tagged
  leaves ('i:8' vs 'd:8'), Pair struct + List bare-array carriers,
  list_transform for to-many keys; host referee restricts both sides
  to the key tree (the engine's own relation) before judging. PLUS the
  Nil/empty claim: a Nil-stamped side is the EMPTY value, kind-gate
  vacuous, and EVERY empty form canons '[]' (unification kills the
  null-vs-'[]' latent hazard). PCT-lane declines 97→35 (agree
  1486→1548, disagree 0); ceilings BANKED (100 → 5/30/35/35/45).
  Residue named: Map (mapEquals — own rule, claimable later), Any wire
  trees, genuinely keyless classes (engine-FALSE territory), 3 Pair
  unclaimable-leaf shapes, mixed-identity (F10), NUL. The 2 PCT
  eq/equal NonPrimitive exclusions adjudicate in the decoupled-PCT
  burn (task #18) — eq is IDENTITY, not keyed equality.
- V11 CLOSED (2026-08-22, user-ratified twice — "collapse the renderer
  into the original query like m2m JSON"): the canon rides the side
  query itself (CanonicalRenderSql.wrapWithCanon → `SELECT value,
  canon(value) FROM (plan) side`, CanonRider carries the harvested
  texts out of the ONE execution) — prepCanon/runCanon and the
  double-execution soundness obligation DELETED (StatementExecutor
  eval-ledger BANKED DOWN 2728→2326; total verdict-system surface
  −355). Unrefined Number sides project one candidate column per fine
  kind; runtime value kinds SELECT (never evaluate). Collection framing
  ('[', ', ', ']') moved to the verdict layer over DB-computed element
  texts + DB-computed canonical order. LiteralFold yields to a
  canon-riding side (the DB must compute the canon), surviving only as
  the last-resort value source for unSQLable literals (NUL-bearing
  strings), counted decline. The canon-exec decline tunnel re-executes
  BARE on wrapped-query failure — a canon column can never poison the
  value fetch (witness: mixed-identity VARCHAR carrier, F10). All five
  ChannelB suites green: discovery 287/137, disagree 0, declines 97
  ≤ 100; corpus green, h2-exec 320+632 unchanged.
- V1 sql-verdict disagreement alarm pinned (all five ChannelB suites +
  corpus runner) — closed in the V1–V5 slice (32eb39ac) and this one
- V2 evalCanon broad catch adjudicated (error-shape register; split
  into prepCanon/runCanon tunnels this slice)
- V3 ArchUnit host-verdict reachability rule (32eb39ac)
- V4 assertSameElements byte cutover (32eb39ac)
- V5 assertEq onto the canon machinery (32eb39ac)
- V6 decline burn round 1: 207→97 (NUMBER plan-refinement,
  PrecisionDecimal=Decimal, enums claimed; PAIR RULES for pure's
  non-transitive numeric equality; mixed-kind-collection gate;
  zeros-unify amendment) — eead1066
- V10a CLOSED: goldenEqualScalar now compares by THE ENGINE CONVENTION
  (fromSQLTimestamp nine-digit normalization + exact record equality —
  STRICTER than the retired instant compare: date-only never equals a
  midnight datetime); H2Verify.norm re-justified as DERIVED (both
  sides of that seam are DB reads = nine-digit by convention)
- V10c CLOSED, all three derived: STRING_AGG order = DuckDB's
  documented preserve_insertion_order default; double-execution
  soundness = the upstream containsEffect gate; the DECIMAL(38,18)
  unfold REPLACED by a complete textual exponent shift (any finite
  double prints fixed-point exactly; dual-render conformance battery
  pins DB text == host text incl. 1e-30 and 1e300)
- V10b PROBED AND CLOSED (2026-08-22): the spec tree's temporal-
  computation inputs are StrictDate / YearMonth / second+subsecond
  datetimes — ZERO hour/minute-datetime witnesses; the witnessed
  partial-DATE precisions ride dedicated lowering rules (DateShifts),
  not the padded-timestamp path. The root-only scalarRoot swap covers
  the whole witnessed domain — 921c80c3+1
- X1–X4 CLOSED (2026-08-22, VERDICT_RULE_AUDIT execution): all
  cross-kind grants DELETED — the lattice is engine-exact
  (EqualityUtilities: same-primitive-kind only, Decimal scale-sensitive
  equals, BigInteger-widened integral equality); canonical Decimal
  render REVERSED to scale-preserving; kind-class value-mode replaced
  by runtime-kind refinement from fetched values (pure's own Number
  dispatch — the plan-refinement was circular). Deleting the grants
  exposed SIX real wire bugs, all fixed at emission: round/divide
  constant-scale DECIMAL casts + toDecimal input-kind scale
  (DecimalKindRules), scale≤0 DecimalLit root-only cast (RootLiterals —
  a blanket renderer cast measurably truncated VALUES columns),
  longValue() BigInteger overflow (the X1 grant had MASKED it),
  INTEGER-declared BigDecimal decode guard (Executor). Golden seam
  quarantine: goldenEqualScalar compares numerics BY VALUE (golden text
  carries no kind/scale) and falls through to the lattice on value
  difference so the declared 2-ULP policy still judges cross-libm
  drift. Dual-render conformance battery pins DB canon == host canon.
- V6b the 97 survivors DECLARED (class instances + wire-tree
  containers per spec §4, + unrefinable Numbers) and CEILING-pinned
  (sqlDeclined ≤ 100, shrink-only, all five ChannelB suites) — the
  PCT-lane verdict system is COMPLETE: four families byte-decided,
  disagreement pinned 0, declines pinned and declared — this commit
