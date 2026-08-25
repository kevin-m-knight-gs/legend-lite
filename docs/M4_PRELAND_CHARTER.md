# M4 PRE-LAND CHARTER — the demand census + pre-flight (2026-08-25)

**User ruling (2026-08-25): NO re-land until the homework exists with
receipts.** Every failure this arc (the T4 cast placement included)
came from applying a correct idea at an UNVERIFIED surface; every win
came census-first. This charter is the census's method and the
pre-flight work list, written for a fresh session. Context: the typed
IR is DONE (TYPED_SQL_IR.md — M1..M3 + judge deleted + label flip,
main at c4eb78f0); the hetero-LITERAL claim is parked at
`wip/slice3-claim-on-untyped-ir` (c06743fc); M4 = its re-land needing
ZERO of its four compensations.

## 0. What the branch VERIFIED vs GUESSED (read 2026-08-25, receipts)

VERIFIED on the branch: the spelling grammar (six disjoint kinds, one
owner: LiteralSpelling) with byte-decidability witnesses
(AnyLiteralByteDecidabilityTest — real wrong-answer fixes:
date==its-print, Decimal==Float); equality-by-emission + print on its
witnessed scope; end-to-end carrier machinery (final branch chain
green except THREE rows).

GUESSED / PUNTED (the census closes these):
1. **Arithmetic over mixed lists** — never built; whether the referee
   even demands it is UNKNOWN.
2. **Cross-kind sort order** — TYPED_SQL_IR §7 explicitly buckets it
   as not-fixed; pure's ordering across kinds never verified.
3. **The 3 residual rows** from the branch's final chain
   (postprocessor −1, tests/query −2) — named, never diagnosed.
4. **`eq(1, 1.0)` — RESOLVED AT THE SOURCE (2026-08-25), with
   receipts:**
   - INTERPRETED runtime (EqualityUtilities.eq:67-80): primitives are
     equal iff the primitive TYPE NAMES match AND the values match —
     cross-kind is FALSE before values are examined; same-kind
     compares by {@code getName()} — the value's CANONICAL STRING.
   - COMPILED runtime (CompiledSupport.eq:969-1011): class mismatch →
     false (Long vs Double → false); BigDecimal-vs-Double is
     HARDCODED false (matches the branch's Decimal==Float fix); the
     same-Number fallback is literally
     {@code left.toString().equals(right.toString())}.
   - {@code equal} (what {@code ==} uses) DELEGATES its primitive
     case to eq (EqualityUtilities.equal:102-105) — all flavors
     agree.
   VERDICT: byte-equality-of-canonical-spellings IS pure's own
   primitive-equality mechanism, not an approximation of it.
   Verified in passing: cross-PRECISION dates unequal
   (assertFalse(eq(%2014, %2014-01-01)) — eq.pure:54).

   **NEW EDGES the source read surfaced (census items, receipts
   attached):**
   a. **-0.0**: CompiledSupport.eq NORMALIZES -0.0 to 0.0 before
      comparing (lines 1008-1009) — pure says -0.0 == 0.0 is TRUE,
      but the spellings byte-differ. Fix shape: LiteralSpelling
      canonicalizes -0.0 → 0.0 at emission (one owner). MUST land
      with the claim or byte-equality gives a wrong answer here.
   b. **Canonical-form parity**: byte-equality holds only if OUR
      spellings match pure's canonical name forms at every
      magnitude — verify LiteralSpelling against pure's Float/
      Decimal name forms for exponent-range values (Java
      Double.toString gives "1.5E10" — does pure's name and do WE
      agree?), trailing zeros, and Decimal scale spelling.

## 1. THE DEMAND CENSUS (half-day, read-only, no code)

Enumerate what the REFEREE demands of mixed-kind/Any values — not
what the branch chose to witness. Method:

a. **Find the traffic**: grep the corpus + PCT sources for
   mixed-kind literal collections and Any-typed positions:
   - collections mixing numeric spellings with strings/dates
     (`\[[^]]*\d+\.[0-9][^]]*'` etc. — iterate patterns);
   - `Any\[` typed params/properties; `->cast(@Any)`;
   - the branch's own touched tests (its diff's test files +
     F10_CARRIER_DESIGN.md witnesses) as the seed list.
b. **Classify the operation** applied at each site: equality /
   print-toString / sort-sortBy / dedup-distinct / membership-in /
   groupBy key / join key / ARITHMETIC (+,-,*,avg,sum...) / range
   (<,>) / format / serialization.
c. **Output**: a table `operation -> demanding tests -> covered by
   the branch design? -> gap`. Every uncovered demanded operation
   becomes a NAMED pre-land slice (built as system capability, never
   a workaround — the pins structurally resist type-lying hacks
   anyway: mismatch==0 and the wire census go red on them).
d. **Oracle semantic receipts** (verify, never assume):
   - eq/equal/== over (Integer, Float, Decimal) cross-kind — §0.4;
   - pure's compare()/sort across MIXED kinds (the cross-kind sort
     bucket) — read legend-pure's compare native;
   - if arithmetic over mixed Number[*] is demanded: pure's
     promotion semantics for it.

## 1R. CENSUS RESULTS (2026-08-25 — receipts; method: §1a-d executed
## over PURE-PCT platform tree + engine PCT dirs + relational corpus,
## patterns iterated int/float, num/string, temporal-in-collection,
## decimal-in-collection, Any[, cast(@Any); every named site checked
## for its <<PCT.test>> stereotype and against the CURRENT exclusion
## lists)

**Headline: every demanded operation is enumerable, and MAIN ALREADY
PASSES ALL OF IT** — none of the mixed-kind tests below appears in any
expectedFailures list (Standard's list is EMPTY; Essential/Grammar
lists hold only instance-identity/0-based/reflection families). The
re-land's obligation is PRESERVATION, plus the two named gap slices.

| operation | demanding tests (adapter lane) | covered by branch design? | gap |
|---|---|---|---|
| arithmetic over mixed `Number[*]` (fold plus/minus/times; sum, average, median, mode, corr, covar, stdDev) | grammar testPlusNumber `[15,13,2.0,1,1.0]->plus()==32.0`, testDecimalPlus `[1.0d,2.0,3]->plus()==6.0d`, testDecimalMinus, testDecimalTimes `[19.905d,17774]`; standard aggregators (sum:34, average:52 `[5d,1.0,2,8,3]==3.8`, median:58, mode:71, corr:40, covar) | YES — structurally avoided: the claim's own gate reads "Number-LUB mixes keep variant BY CONTRACT (arithmetic must type)"; these ride the existing numeric lanes (Scalars promotion + Decimal fold, witnesses in Scalars.java:249/277/306) | none; re-land MUST carry the Number-LUB gate onto the typed IR (Typer type makes it a clean read) |
| cross-kind sort | testMixedSortNoComparator `[342,5.0,-2.0,171,1]->sort()` — the ONLY adapter-lane cross-kind sort, and it is Number-only. The hetero num/string orderings (`removeDuplicates` tests' `[1,2,3,'1','3']`) apply `->sort()` OUTSIDE `$f->eval` — platform-side, never through the adapter | YES (comparable channel orders, identity channel keeps kinds) | none. ORACLE RECEIPT (both runtimes agree): interpreted Compare.java:84-155 and compiled CompiledSupport.compareInt:550 + PRIMITIVE_CLASS_COMPARISON_ORDER:140 — kind-CLASS ladder Number < Date < Boolean < String < non-primitive(by type path); numbers compare BY EXACT VALUE across kinds (BigDecimal; NaN/Inf via double), dates via PureDate.compareTo, cross-kind equal values (3 vs 3.0) tie → stability decides. TYPED_SQL_IR §7's "cross-kind sort not-fixed" bucket has NO adapter-lane demand beyond the numeric mix |
| dedup over hetero (default + eq/equal fn-refs) | testRemoveDuplicatesPrimitiveStandardFunctionMixedTypes, ...Explicit (`[1,2,'1','3',1,3,'3',2]`) | YES — carrier + dedup consumer arms (landed dormant on main) | verify live at re-land sweep |
| dedup with TWO-PARAM lambda comparator | testRemoveDuplicatesPrimitiveNonStandardFunction `{x,y\|$x->toString()==$y->toString()}` | branch used the comparator FQN registry = compensation #3 | **§3.2 slice** (construction-site binding) |
| membership over hetero incl Bool+temporal | testContainsPrimitive (col=`[1,2,5,2,'a',true,%2014-02-01,'c']`, positive AND negative probes), standard in.pure:30 same col, in.pure:71 `1d->in([1d,2d])` | YES — needle-wrap consumer arms landed | none |
| collection equality w/ mixed sides | testConcatenateMixedType `[1,2,3,'a','b']` | YES — equalityEmission arm (MixedEncoding) | none |
| print / format over Any args | format.pure %s/%d/%f/%t/%r tests (`['fox',3]`...), toString | YES — branch printForm (spelling→PRINT projection, one recipe) | none |
| least/greatest/max/min mixed numerics, WINNER KEEPS ITS KIND | standard least:78-80 (`least([4.23,7.345,1.0d,3,4])==1.0d`), greatest:77 (`greatest([1.23,2])==2`), max:100, min:100 | YES on main (kind-preserving lanes; DuckDBIntegrationTest:6351-6497 witnesses) | none |
| Any-cast collection feeding in() | corpus testIn.pure:55/65/89/102 `['John']->cast(@Any)` | conformance-cast lane | **§3.3 slice** decides no-re-wrap |
| grid-extraction asserts (rows.values vs mixed literal lists) | corpus tds/functions families (bulk of 1,100 corpus hits) | YES — carrier rule (grid keeps rows) + rowMajorCellList conform-by-emission | none (adjudicated 2026-08-24) |
| serialization | variant PCT toVariant/fromJson float lists | Variant lane untouched by the claim | none |

**§0.4 edge receipts (both CLOSED as census items):**
- **(a) -0.0**: ALREADY LANDED at the one owner — floatCanon's
  zeros-unify (LiteralSpelling.java:149, regex `-?0+(\.0+)?` → '0.0')
  and the carrier routes through it (MixedEncoding.elementLiteral →
  LiteralSpelling.literal for FLOAT). Compiled pure's print ALSO
  normalizes (-0.0 hits the `value == 0.0d` guard →
  "0.0", CompiledSupport.primitiveToString(double):1273). No fix
  needed; the claim inherits it.
- **(b) canonical-form parity**: pure's Float name form is
  DecimalFormat("0.0"), ENGLISH, maxFrac 340 (CompiledSupport:130-137)
  = PLAIN decimal, ≥1 fraction digit, never exponent — exactly what
  floatCanon produces (exponentUnfold is total; the `huge` arm appends
  ".0"). PROBED 2026-08-25 (java DecimalFormat vs duckdb CAST AS
  VARCHAR → unfold): byte-match at 1e20, 1.5e10, 1e-5, 1.5e-10,
  123.456, -0.0, 1e15, 1e16, MAX_DOUBLE, -2.5e-7, 2^53+1. ONE
  divergence class: SUBNORMALS (4.9e-324: DuckDB shortest-repr "5e-324"
  vs Java "4.9e-324" — different shortest-repr tie-break). No referee
  traffic reaches subnormals; recorded as counted residue, NOT a slice.
  Decimal: ours strips D and rides the wire scale; pure toPlainString
  preserves BigDecimal scale — parity holds where wire scale = pure
  scale (DECIMAL-under-DOUBLE store reads stay the T4 non-goal).

**Census verdict on §0's GUESSED list:** (1) mixed-list arithmetic IS
demanded but never meets the carrier (Number-LUB gate) — not a claim
capability; (2) cross-kind sort demand is numeric-only in the adapter
lane — the §7 bucket stays unbuilt with a recorded oracle ladder;
(3) the three residual rows remain §2's job; (4) eq resolved (§0.4).
No NEW pre-land slice emerged: the gap column names only §3.2 and
§3.3, already chartered.

## 2. THE THREE RESIDUAL ROWS (diagnose before landing)

The branch's final chain was green EXCEPT: postprocessor −1,
tests/query −2 (park note). Diagnosis method: cherry-view the branch
(NO merge), run G4 scoped (`-Drcorpus.only=...` on those families)
on a THROWAWAY worktree of the branch, name the three tests and their
failure modes. They are the branch's known unfinished edges — each
becomes either a pre-land slice or a recorded M4 acceptance item.

## 2R. RESIDUAL-ROW DIAGNOSIS (2026-08-25 — DONE; throwaway worktree
## of c06743fc, scoped G4 `-Drcorpus.only=postprocessor,tests/query`,
## reproduced the park note EXACTLY: postprocessor/tests 22<23,
## tests/query 77<79; every other family failure cross-checked
## pre-existing in main's ledger)

THE THREE ROWS, one shared failure mode:
1. `meta::relational::tests::postProcessor::testSqlRealiasViews`
2. `meta::relational::tests::query::view::testViewAll`
3. `meta::relational::tests::query::view::testViewSimpleFilter`

All three assert `$result.values->map(p|[$p.<string-prop>,
$p.<float-prop>]->makeString(','))` — got `'Account 1',100.0` where
`Account 1,100.0` was expected. MECHANISM: the per-row collection
`[$p.name, $p.pnl]` (String+Float property reads) is Any-LUB with
every element spellable → the claim carries it as Array(LITERAL);
`makeString` is a PRINT consumer the branch's printForm recipe never
covered (it wired pureToString's Any arm + format slots only), so the
string cell keeps its quotes in the joined text.

DISPOSITION: M4 ACCEPTANCE ITEM, not a pre-land slice — the cure is a
typed-IR capability read at the consumer (makeString's lowering site
sees element type LITERAL → applies LiteralSpelling.printForm, the
exact "one recipe" the doc promises), which only exists once the claim
is live. At the re-land: enumerate ALL join-text print consumers
(makeString/joinStrings family), route each through printForm on
LITERAL elements, and these three rows are the named witnesses —
the first green sweep must show all three back to PASS.

## 3. PRE-FLIGHT GAP SLICES (found by the 2026-08-25 branch-vs-today
## mapping — each its own gated slice, BEFORE the re-land)

1. **ERROR-branch rule**: an `error()` call RAISES — it never yields
   a value — so it is bottom-like in branch families. Today's
   uniform() skips Bottom but poisons on ERROR branches (the branch
   patched the old judge for this; the old judge is deleted). Add the
   ERROR skip to the ONE shared uniform()/caseType in SqlTyping.
   Witness shape: checked-extract CASE (error-guard + LIST_GET over
   Array(LITERAL)) must type LITERAL.
2. **Two-parameter comparator binding**: `Lambda.bind` handles
   single-param lambdas; comparator lambdas ((T,T)->Boolean over ONE
   list: sort, removeDuplicates) bind BOTH params to the element
   type. Extend the attachment door AT THE LOWERING SITES of those
   natives (the site knows its own convention — this replaces the
   branch's FQN registry with construction-site knowledge). Fold
   stays excluded (its second param is the ACCUMULATOR).
3. **The no-re-wrap decision**: an Any-conformance over a
   LITERAL-typed value emits NO carrier cast — the LITERAL label IS a
   self-describing Any carrier ("labels distinguish carriers, casts
   never re-carrier" — the branch's own CastPolicy comment). Decide
   at CastPolicy's variant arm, gated on `value.type()` (the typed IR
   makes this a clean read; on the branch it needed the judge).
4. **PCT assert hook** (from the slice-0 audit): G6/G7's JVMs measure
   the census invariants but assert nothing. Close before the
   landing so M4's new-class review happens on fully pinned lanes.

## 4. LANDING ACCEPTANCE (unchanged from TYPED_SQL_IR §4 + census
## caveat)

- The claim re-lands needing ZERO of its four compensations
  (LambdaWire, per-arg judge loop, comparator FQN registry, judge
  patches). If ANY need survives, it becomes a typed-IR capability
  slice FIRST; the branch stays unmerged until zero.
- The first green sweep's LITERAL census rows are REVIEWED, every
  new mismatch class adjudicated (the Any-lane surface only becomes
  measurable when the claim is live — §7 census caveat).
- Retires WITH the landing: the last scalarRoot LITERAL label arm,
  the collection-carrier admissibility rows (L ← Array(L)), and the
  Array-carrier vocabulary question; T4 attempt 2 (property-read
  pairing — three referee verdicts + banked plumbing in
  TYPED_SQL_IR.md) follows the landing, informed by its carrier rule.
- Every pin holds; new ratchet moves only with written justification.

## 4R. LANDING RECORD (2026-08-25 — M4 EXECUTED)

Every acceptance item met:
- **ZERO of the four compensations survive.** LambdaWire → the
  LambdaBinding unary+comparator binding conventions at the ONE
  arg-lowering site (a NEW typed-IR capability, built when the first
  green-sweep review surfaced the frozen-dispatch class: lambda bodies
  lower BEFORE consumer sites bind, so the param wire must be in scope
  AT body lowering — witnesses testPctRemoveDuplicatesBy,
  testRemoveDuplicatesPrimitiveNonStandardFunction/…Explicit, all
  cured, none waved through). Per-arg judge loop → stored type facts.
  Comparator FQN registry → COMPARATOR_NATIVES beside the rule table
  (signature keys, the table's own dispatch identity) + Dedup's §3.2
  stamps. Judge patches → §3.1's uniform() ERROR skip.
- **The three §2R rows PASS** (elementText's printForm lane); both
  families back at their baselines; corpus scoreboard byte-identical.
- **Census review**: corpus lane mismatch 0, adopt-pending 0,
  diverge 55<=56, untyped 717→424; pct lane untyped 813→808 (banked),
  one new class = wire-delivered LITERAL <- VARCHAR (the registered
  carrier pair, by design). decodeAnyPrecision probe flipped to exact
  equality (Decimal healed through Any); shortcut-audit row updated.
- **Retirements**: admissible L <- Array(L) row DELETED (measured:
  mismatch stayed 0 without it). The scalarRoot LITERAL-label arm
  STAYS with a measurement: it is the claim's label seam reading the
  STORED fact (not a re-derivation); full retirement belongs to the
  T4 label-at-construction program.
- Ratchet moves, each with written justification: ArrayLit 39→41
  (the claim's designed emission + format's print array); pct-lane
  untyped ceiling 813→808 (banked down).

## 4A. POST-LANDING ADVERSARIAL AUDIT (2026-08-25 — probe battery +
## pre-M4 worktree attribution runs; user-ordered)

CLEAN under probing: mixed-Number arithmetic exact (plus 32.0, minus
-4.0D, times 353791.470D, average 3.8), mixed-Number sort order +
kind preservation (min keeps Decimal, max keeps Float), mixed
Date/DateTime sort in true time order with precisions kept, hetero
temporal identity byte-exact incl. subseconds (.123 vs .124
discriminate), the equalityEmission adversarial shapes (hetero vs
homogeneous list = false, no crash; at(0)==literal lanes correct).

**FINDING (M4 regression, REFEREE-SILENT, fixed same day):**
`contains(col, value, comparator)` over a hetero LITERAL-carried list
crashed (Malformed JSON on a spelling) — pre-M4 worktree run returned
the correct answer, so attribution is certain. Root cause was TWO
mistakes: contains was missing from COMPARATOR_NATIVES, and the naive
membership fix was WRONG anyway — contains' convention is
eval($value, $x), needle FIRST, so a both-element stamp is a lie for
param 0; the kind-honest pin (eq('1', 1) inside the comparator) caught
the raw needle's TEXT colliding with a spelling (answered true, pure
says false). CURE = membership + the rule's comparator-form needle
wrap (spell AND mark the needle by its static kind when the
collection is carried — the marked needle makes the param-0 stamp
honest and byte-comparison kind-faithful). Roster now = the
exhaustive (T,T)->_ same-element sweep of both oracle trees:
removeDuplicates, sort, contains. Excluded WITH reasons: fold
(accumulator), relation join/asOfJoin (two relations), removeAll
(never lowered), and min/max — their comparator is STRUCTURALLY
RECOGNIZED at the rule (Comparators pattern-match; the body never
lowers as a body), and a precautionary stamp broke the recognizer's
structural equality — gate-caught same day (G9 chB-std
testMax/testMin), removed with this written reason. Lesson: stamps
belong ONLY where bodies lower as bodies. Witness: ComparatorConventionTest (3 pins,
red = 2 crashes + 1 wrong answer without the fix).

**ATTRIBUTED PRE-EXISTING, deferred BY DECISION (2026-08-25):**
all-temporal mixed-PRECISION identity — `[%D-Feb10T00:00,
%D-Feb10]->removeDuplicates()` answers 1 where pure says 2 (StrictDate
vs DateTime-midnight collapse through TIMESTAMP promotion). Identical
result on the pre-M4 worktree; the LUB is Date (primitive), so the
Any-carrier never fires. This is the reference DuckDB adapter's own
LEDGERED family ("mixed-Date element identity through SQL type
promotion" — the Essential exclusions charter), no referee test
demands it, and a fix is a TWO-CHANNEL design leg (identity vs the
ordering/aggregation channel the temporal lane must keep — the exact
Number-LUB trade), touching the milestoning families. DISPOSITION:
a named future temporal-identity leg, not a patch; ranked by
tests-per-design when it comes up.

## 4Z. THE TRUE-ZERO LEDGERS (user-ratified 2026-08-25 — "explained
## zero" is not zero; ALL FOUR burn to REAL zero = "make the type
## system bulletproof"; each may run as its own program)

1. **Rulebook residue: 159** (of the 517 label lies, the rows the two
   admissible coercion arms FORGIVE — excused lies, each a potential
   silent wrong answer). Zero = the arms DELETE (T4 attempt 2's
   acceptance, unchanged).
2. **Wire divergence: 55** (label vs the database's own metadata —
   each row is a bug or an unregistered carriage). 48 die with T4
   attempt 2; 3 need witnesses; 4 are the unexplained residue of the
   "5 die at JSON/M4" prediction (only 1 died) — they join §4b item
   4's tracing.
3. **Untyped roots: 424 corpus / 808 pct lane** (honest UNKNOWNs —
   not lies but BLIND SPOTS: type-driven capabilities silently don't
   apply there). ADOPTED to the burn (this ruling): zero = no blind
   spots. Decomposition: ScalarSubquery 340 = ONE family (the groupBy
   wrap's declared outputs — first slice), then Case 20, LIST_CONCAT
   18, DATE_TRUNC_DAY 14, PLUS 12, UNNEST 11, WindowCall 7, Column 2.
   Both lanes count; ceilings ratchet down per slice.

4. **Nullability backlog: 6,472** (bottom-mult-backlog —
   "null-under-required-multiplicity": a computed value IS or CAN BE
   the NULL value while the declared column promises always-present.
   The whether-absent dimension; kinds ledgers #1-#3 cannot see it).
   ADOPTED to the burn (user ruling 2026-08-25): its own program,
   method to be chartered census-first like this one — classify the
   6,472 by cause (honest [0..1] sources under [1] labels vs
   label-side nullability never computed vs genuine emitter gaps),
   then per-class: fix the label, fix the emission, or register the
   carriage. The census already samples witnesses per class.

5. **Excuse arms: 7 → 0** (user-ratified 2026-08-25 — the FORGIVENESS
   RULES themselves, counted in SqlTyping.admissible): 2 die at T4
   attempt 2 (the #1 ledger's own arms — DOUBLE<-BIGINT/INT/Decimal,
   VARCHAR<-BIGINT); 3 die at the carrier-types leg
   (TIMESTAMP/DATE<-VARCHAR partial-temporal, DOUBLE<-VARCHAR
   Number-identity, VARCHAR<-JSON serialize-as-text — each becomes a
   modeled logical carrier type + dialect physical pair, LITERAL
   pattern); the remaining 2 (TIMESTAMP<-DATE, Decimal widening) are
   SUBSUMPTION THEOREMS, not excuses — they RE-HOME (same ruling)
   into an explicitly named subtyping relation ("widens"), each edge
   pinned by a boundary-value LOSSLESS round-trip witness (the
   removed INTEGER<-BIGINT narrowing row is the negative precedent:
   narrowing is a hope, never a theorem). Wire-side integer/decimal
   chains in delivers() re-frame the same way — lattice + dialect
   delivery model, zero excuses. END STATE: admissible() is EMPTY;
   what stands is two NAMED, per-edge-proven relations (subtyping
   lattice, dialect delivery) — nothing forgiven, everything proven
   or modeled.

Sequencing: T4 attempt 2 FIRST (kills #1 whole + 48 of #2 at one
seam + 2 of #5's arms), then the ScalarSubquery family (#3's big
bite), then #3 tails, the carrier-types leg (#5's next 3 arms + the
widens re-home), then the #4 nullability program (own charter,
census-first).

**CARRIER TYPES, NOT CARRIER EXCUSES (user-raised + ratified
2026-08-25; queued design leg):** a backend's different IMPLEMENTATION
of the same logical value is DIALECT knowledge to model, never an
admissibility excuse. The LITERAL carrier already does this right
(logical SqlType + CarrierStrategies physicalization per backend);
the two LEGACY carriers predate it and got blanket rows instead —
partial-precision temporal text (TIMESTAMP/DATE <- VARCHAR) and
Number-identity spelling text (DOUBLE <- VARCHAR). Those rows are
WEAK GUARDS: any accidental string under a temporal/number label is
excused today, deliberate or not. The leg: give each a logical wire
type + dialect physical pair (the LITERAL pattern), DELETE its
admissibility row — accidents become caught mismatches again.
Segregation ruling with it: the wire census's delivery relation
(logical -> physical per dialect) is the DIALECT MODEL, correct and
permanent (the DB only speaks physical); `admissible` itself drains
toward provable widenings ONLY — the end-state excuse list is
widenings or empty. Aligns with the single-compiler/dialect-
strategies tenet and the standalone-SQL-library vision.

QUEUED SMALL DEBT (audit follow-on, witnessed by the G9 min/max
trip): Comparators.select recognizes comparator shapes on the
LOWERED SQL tree via equals() — stamp-sensitive by construction;
its sibling (Comparators.direction, sort) reads the TYPED tree and
was immune. Migrate min/max recognition to the typed level; the
recognizer itself is correct architecture (capability-boundary
classification of arbitrary user code; the reference adapter refuses
these outright).

## 4b. T4 ATTEMPT 2 HOMEWORK (do AFTER M4 lands, BEFORE any code —
## attempt 1 failed on exactly the surfaces this list enumerates)

1. **Locate the seam**: find every code site where a mapped property
   is paired with its physical column (the resolver/mapping
   machinery); confirm the pairing is UNIQUE there (no name lookup);
   count the sites. The conform cast (Cast.conform — plumbing already
   banked) emits at those sites only, gated on the CONCRETE stamp
   (Float/String; abstract Number/Any never — castErasure referee).
   **ITEM 1 DONE (2026-08-25, read-only receipts):** THE SEAM =
   MappingNormalizer's property-mapping switch (~:2555 — each
   PropertyMapping.Column/Expression/EnumeratedColumn row IS the
   unique property↔column pair, the mapping model itself, no name
   lookup; the existing wire-coercion wrappers coerceColumnToDeclared
   + coerceToDeclaredNumeric ALREADY live at exactly this spot) +
   FOUR mirror call sites in UnionSynthesis (:974/:1011/:1178/:1479 —
   per-union-branch re-emission, verdict-3's danger zone, item 2's
   first check) → ONE Typer funnel (castAsDeclared →
   TypedCast(wire=true), Typer ~:1265) → ONE consumer
   (CastPolicy.lower reads the flag). Attempt 2 builds where the
   wire-coercion machinery already stands; the pairing is unique BY
   CONSTRUCTION — failure mode #2 (name collisions) structurally
   cannot recur here. ALSO ADDED to item 4's tracing: the 4 wire
   rows whose predicted M4 death did not materialize (§4Z #2).
2. **Enumerate the perturbable consumers** and check EACH against a
   cast appearing at the read: union branch-projection identity (the
   merge reorder), groupBy keys, join conditions, DISTINCT, sort
   keys, resolveInto substitution.
3. **Write the text-channel map FIRST** (learned the hard way):
   golden-TEXT channels (EngineStyle renderers — conform casts ELIDE
   there) vs EXECUTION-text channels (h2-exec floor 320, advisory 309,
   sqldiff 257, adv-pass 303 — conform casts genuinely appear; each
   move needs written justification) vs the class-plan lane
   (wireForm — casts must NOT emit at all).
4. **Trace the 159 target rows** (admissible VARCHAR<-BIGINT 97 +
   DOUBLE<-Decimal 48 + DOUBLE<-BIGINT 14) to their seam: which
   read-site does each witnessed test family flow through? No code
   until every family has a named site.
5. Acceptance: the two coercion arms in SqlTyping.admissible DRAIN to
   agree and DELETE; the 48x wire DOUBLE<>DECIMAL rows drain; pins
   move only downward or with written justification.

## 4bR. T4 ATTEMPT 2 HOMEWORK RESULTS (2026-08-25 — items 2-4 DONE,
## read-only; receipts = full G4 sweep on clean main 37fd2e3f, census
## dump with witnesses; every family has a named site BEFORE any code)

**G4 baseline receipts (this sweep, 136s, all green):** rulebook 159
CONFIRMED live (97 VARCHAR<-BIGINT + 48 DOUBLE<-Decimal(18,6) + 14
DOUBLE<-BIGINT); wire diverge 55 fully decomposed (48 DOUBLE<>
DECIMAL(18,6) + 3 HUGEINT<>DOUBLE + 2 JSON<>VARCHAR + 2 JSON<>BIGINT);
EVERY execution-text pin at EXACT ceiling — h2-exec text-matched 320
(floor 320), advisory 309/309, sqldiff-pass 257/257, adv-pass 303/303,
rescued 614/614, 0-asserts 27/27 — zero headroom, every move must be
predicted and justified.

### Item 2 — perturbable consumers, each checked against a cast at the read

New casts appear ONLY at concrete-numeric-declared properties over
kind-mismatched numeric columns (the Slice-A arm). Per consumer:

1. **UnionSynthesis mirrors (:974/:1011/:1178/:1479 — verdict-3's
   danger zone): safe BY CONSTRUCTION for the mirrors' own coercions**
   — coerceToDeclaredNumeric decides on (declared prop, owner) ONLY,
   physical-blind, applied to EVERY thread uniformly; branch TYPE is
   normalized even where branch TEXT differs (each thread's
   castAsDeclared converts to the declared kind at CastPolicy).
   Member-side asymmetry (cast in member A's ctor field, bare read in
   B's) enters via pp.fields() <- translatePmToField (:2220 -> :2563)
   and is then re-normalized by the mirrors' uniform outer coercion —
   the exact opposite of attempt 1's one-branch cast. Residual check
   at build: union families whose members disagree on column kind
   (the mirrors' own comment names the case) witness in the first
   sweep.
2. **groupBy keys**: key and projection are the SAME SqlExpr
   (buildGroupBy) — a cast rides both or neither; no pairing split.
3. **join conditions**: mapping-join conds lower in the CORRELATION
   channel (TypedFilter stamp, verbatim equality) — reads carry casts
   symmetrically. comparisonWireOperand (CastPolicy:239) is the
   EXISTING referee-pinned unwrap (wire cast unwraps toward a literal
   speaking the SOURCE type — testInWithDynaFunction golden, bare
   ID = 4); it keys on tc.wire(), not the target kind, so Slice-A
   casts inherit the convention automatically.
4. **DISTINCT/dedup**: lowers over one projection source; a cast
   changes the expr uniformly — no cross-site identity to break.
5. **sort keys**: Sorts:36 via Fold.resolveInto; a Cast is
   scalarInlineable and substitutes as ONE expr (the same object —
   no transport seam). Comparators.direction reads the TYPED tree
   (§4A: immune).
6. **resolveInto substitution** (Fold:451): same as 5 — whole-expr
   substitution, transport-free.
7. **Comparators.select (min/max recognizer — §4A queued debt)**:
   equals() on the LOWERED tree, cast-sensitive (the G9 trip's
   mechanism). A conform cast inside a min/max comparator body
   (Float-declared property over DECIMAL) can perturb recognition —
   named build-time check (G9 chB-std testMax/testMin); the queued
   typed-level migration is the cure if tripped, never a stamp strip.
8. **cellRootUnwrapWire (Lowerer:1369 <- CastPolicy:269)** — the
   existing REVERSE consumer: STRING-target-only, referee-calibrated
   (4a60b246: tree.pure pins the RAW Long under String[1] over an INT
   column; boolean.pure pins Boolean conversion). Slice-A numeric
   casts do NOT match its String guard and SURVIVE at cell roots —
   intended (item 4's getFloat receipt) — which is exactly why the
   48-family's exec text moves (item 3).
9. **Class-plan lane**: TypedProject.wireForm is TRANSPORTED BUT
   UNCONSUMED today (attempt 1's conformProjections consumer deleted
   with the revert — verified by grep). The 12 plan tests are
   protected by EngineTextBoundary elision (StatementExecutor:527 is
   the ONE enter() site; CastPolicy.lower:50 returns the bare value
   for wire casts). Verify in the first chain; if a plan test ever
   sees a cast, the wireForm READ gets built then, as a named
   capability.

### Item 3 — the text-channel map (written BEFORE code)

- **Golden-TEXT (casts ELIDE, byte-parity)**: every funnel behind
  EngineTextBoundary.enter — StatementExecutor:527
  (toSQLString/planToString/EngineStyle renderers, class-plan lane
  included). castAsDeclared already elides there. Predicted moves:
  NONE. Any golden-text diff at the sweep = a bug in the slice, not
  an adjudication.
- **EXECUTION-text (casts genuinely appear)**: all pins at exact
  ceiling (receipts above). Predicted moves at Slice A: the
  48-family (mapping::dataType::testSimpleTypeMapping/…Nulls/
  …Project) and the 14-family (aggregationAware objectGroupBy)
  gain CAST(… AS DOUBLE) in exec text; any of them currently in
  the 320 text-matched set demotes to rescued — h2-exec floor may
  move DOWN and rescued/sqldiff/advisory ceilings UP by exactly
  those tests, measured per test, written justification in the
  ratchet commit. No other family may move.
- **Census pins**: mismatch==0 HOLDS (reconciliation adopts by
  construction); wire-diverge 55 -> 7 predicted (ceiling 56 ratchets
  to 7); adopt-pending==0 holds; untyped 424 unaffected; canonical
  <=27 expected to hold (casts are value-identical; the 21
  float-arithmetic rows re-measure).
- **PCT lane**: ChannelB counters are CUMULATIVE PER JVM — measure
  lanes WHOLE; pct untyped ceiling 808 banked.

### Item 4 — the 159 rows + the 4 unexplained wire rows, traced

- **48x DOUBLE<-Decimal(18,6) == the 48 wire DOUBLE<>DECIMAL rows —
  the SAME columns** (witnesses u_map__decimalAsFloat := t0.dec,
  u_map__numericAsFloat := t0.n; family mapping::dataType::
  testSimpleTypeMapping/…Nulls/…Project, scalar-map u_map path).
  NAMED SITE: MappingNormalizer.coerceColumnToDeclared's
  numeric-over-numeric arm (:2430, typeAsDeclared today) via
  translatePmToField's Column arm (:2563). ORACLE RECEIPT that
  conversion is the engine's own semantics for CONCRETE Float: the
  engine test reads getFloat('decimalAsFloat') and asserts the FLOAT
  1.234 equal to the cell — pure's Decimal==Float is hardcoded FALSE
  (§0.4), so the value must be a double by assert time; our
  decode-by-label conversion is the host-side arm T4 lowers to SQL.
- **14x DOUBLE<-BIGINT** (witness Total Price Max := Reducer; family
  aggregationAware::testRewrite::objectGroupBy): Float-declared
  property over an INTEGER column read RAW under MAX (identity-
  preserving reducer) -> BIGINT wire under the DOUBLE label. SAME
  read-site as the 48; the conform cast types the reducer input
  DOUBLE and the probed promotion follows.
- **97x VARCHAR<-BIGINT** (witness id := t0.ID,
  projection::filter::in::testInWithDynaFunction): String-declared
  over INTEGER. The cast IS already emitted at the seam (:2416) and
  REMOVED at the two referee-pinned consumers — cellRootUnwrapWire
  (raw cell, tree.pure) and comparisonWireOperand (bare predicate,
  the testInWithDynaFunction golden). These rows are NOT
  cure-by-emission: the referee DEMANDS the raw wire. Drain = LABEL
  ADOPTS WIRE (delete the arm; reconciliation adopts BIGINT — the
  engine's own plan metadata pins resultColumns=INT, adoption IS the
  engine contract), accepted only behind the named witness battery
  (tree 11-cell, boolean.pure, testInWithDynaFunction) in a full
  sweep.
- **The 4 unexplained wire rows (§4Z #2), now NAMED**: 2x
  JSON<>VARCHAR = functions::fetchDbMetaData::
  testFetchDbColumnsMetaData; 2x JSON<>BIGINT =
  ddl::dropAndCreateTable. Variant-lane JSON labels over scalar
  wires — NOT coercion-arm traffic, they do not die at T4;
  adjudicate at the T4 sweep's wire review (fix the label at
  construction or register the carriage). The 3 "need witnesses"
  rows = 3x HUGEINT<>DOUBLE, groupBy::testReprocessGroupByAlias
  (sum-promotion label over a DOUBLE wire) — same review.

### Slice order (from the tracing)

A. Concrete-numeric conform at the ONE arm (coerceColumnToDeclared
   :2430): declared in {Float, Integer, Decimal} (CONCRETE only —
   Number keeps typeAsDeclared, the castErasure referee) emits
   castAsDeclared. Measure: admissible 48+14 -> 0, wire 55 -> 7,
   exec-text moves exactly the two named families. Then the DOUBLE
   coercion arm in SqlTyping.admissible DELETES (traffic zero).
B. VARCHAR<-BIGINT arm DELETES behind the witness battery (labels
   adopt the wire at the unwrapped sites; mismatch==0 by
   construction). The kept consumed-position String casts already
   agree (they compute VARCHAR).

## 5. SESSION TRAPS ROSTER (2026-08-24/25 learnings — read first)

- Corpus/ChannelB roots are -D SYSTEM PROPERTIES; use
  tools/allgates.sh (env conversion) — hand runs silently referee the
  stale $HOME checkout.
- Tree FROZEN during chains (PX.1); flush all writes first.
- G8's `-am clean` wipes core/target — read timing-ledger.txt (and
  anything else in target/) right after G4.
- ChannelB census counters are CUMULATIVE PER JVM: measure lanes
  WHOLE (`-Dtest='ChannelB*'`), never per-suite; per-suite deltas
  mislead.
- Guardrails: 250-line methods / 3500-line files — split at seams
  (manyPropertyMap precedent), never squeeze.
- Provenance flags (Cast.conform, TypedProject.wireForm) must be
  TRANSPORTED by every rebuild site — grep all reconstruction sites
  when adding one (four were missed first time).
- Shared box: G1 server-test flake (301-HTML from a foreign
  listener; 8/8 standalone) — rerun before diagnosing; G8 runs
  ~250s vs its 63s pin in every chain (pre-existing growth, own
  decomposition queued).
- Assert failures can ABORT later asserts in the same runner —
  a "green" pin after a fixed one is unexamined until a full clean
  run (the conform6 lesson).
- Doc-only pushes: verify `git diff --stat` is docs-only; code
  pushes always behind the full chain.
