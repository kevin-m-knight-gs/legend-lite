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

## 4bX. T4 ATTEMPT 2 — EXECUTED, WITH TWO REFEREE CORRECTIONS
## (2026-08-25; commits 1428c6eb + f70ffb34; every move measured)

**LANDED — the §4b item-5 acceptance, adjudicated against what the
referee actually said:**
- **The 48 DOUBLE<>DECIMAL wire rows DRAIN ✓** (wire diverge 55 -> 7,
  ceiling 56 -> 7): concrete-Float-over-DECIMAL conforms BY EMISSION
  at coerceColumnToDeclared (the ONE pairing arm). Zero exec-text
  moves (the u_map family's exec text was already divergent-rescued)
  — the §4bR channel-map prediction 'floor may move' turned out
  unnecessary; every pin held exactly.
- **The DOUBLE arm's Decimal limb DELETES ✓** (measured zero traffic
  first). admissible DOUBLE<-Decimal 48 -> 0; census agree +48.
- **Corrections the sweeps forced (both recorded on the arms):**
  1. Float-over-INTEGER is IDENTITY, not conversion — the
     validation-showcase golden prints the raw 'Quantity not in
     range: 1000000' (Float quantity over INT column, toString IN
     SQL). The first Slice-A build cast it and regressed
     validation/showcase 8 -> 6; narrowed to colKind==Decimal, green.
     The 14 DOUBLE<-BIGINT rows are NOT emission targets.
  2. The VARCHAR<-BIGINT arm CANNOT delete by adoption — pure ITSELF
     asserts the STRINGIFIED TDS cell (in.pure
     testInWithDynaFunction:202, [false, '4'] with id: String[1]
     over ID INT): the LABEL is the true contract; today's bridge is
     host-side decode-by-label (the compensation T4 exists to kill).
     Full-arm deletion was TRIED: rows stayed green but the wire
     census went red 7 -> 69 (42x label BIGINT <> meta VARCHAR, 20x
     DOUBLE <> BIGINT) — the census caught adoption re-labeling live
     stringified traffic. REVERTED same day; §4bR's 'drain by
     adoption' call for the 97 was WRONG — tree.pure (raw cells) and
     in.pure ('4' cells) sit in DIFFERENT DECODE LANES (GRAPH ctor
     vs TDS label), not one raw contract.

**QUEUED LEG — superseded same day by §4bY's engine-code census:
the 'TDS-lane stringification' reading below was WRONG.** (Kept for
the record; §4bY holds the corrected mechanism and the real leg.)
~~TDS-lane stringification BY EMISSION — String-declared-over-numeric
TDS project cells stringify IN SQL; the GRAPH lane keeps raw;
cellRootUnwrapWire re-scopes; host decode-by-label retires.~~

## 4bY. VAR/INT — THE TRUE MECHANISM (2026-08-25, user-ordered
## "read the code"; every claim below is an engine-code receipt)

The §4bX "GRAPH vs TDS decode lane" hypothesis is DEAD — both
witnesses are plain TDS projects of String[1]-over-INT-declared
columns, identical mappings (plain Column PMs). The engine's actual
mechanism, read from its source:

1. **The engine NEVER converts TDS cells by declared type.**
   meta::relational::mapping::transform (legend-pure
   platform_store_relational/functions.pure:218) is IDENTITY unless
   the PM carries an enum transformer. buildExecutionResultInTDS
   (relationalMappingExecution.pure:507-517) special-cases ONLY
   Boolean-typed paths ($a == 'true' || $a == true) and Decimal
   (toDecimal); everything else passes the raw wire value.
2. **The fetch is ResultSet-METADATA-keyed** (ResultSetValueHandlers:
   Types.INTEGER->LONG, Types.VARCHAR->STRING) — the physical wire
   decides the cell kind; PathInformation types play no part in the
   fetch.
3. **in.pure's '4' is FIXTURE SKEW, not conversion**: the setup
   executes `Create Table InteractionTable(id VARCHAR(200), ...)`
   (relationalSetUp.pure:1397) while the ###Relational store
   declares `ID INT`. The engine's wire genuinely IS VARCHAR — '4'
   is an identity read. tree.pure's accountTable is genuinely INT —
   raw 11 is the same identity. THE TWO GOLDENS NEVER CONTRADICTED.

**Consequence — the 97 VARCHAR<-BIGINT rows split into two
populations the type-pair arm cannot see:**
- **(a) Skew rows** (in.pure family): store declaration INT, actual
  fixture VARCHAR. The COMPUTED stamp (derived from the store
  declaration via findPhysicalColumn) lies about the real database;
  the VARCHAR label is truthful. Deleting the arm re-labeled these
  to BIGINT and the wire census went red (42x label BIGINT <> meta
  VARCHAR) — the census correctly caught the stamp's lie surfacing.
- **(b) Genuine-INT rows** (tree.pure family): declaration and
  fixture agree (INT). The engine asserts the RAW Long (tree.pure
  [11, ...]) — the VARCHAR label is the liar and label-adopts-wire
  is CORRECT. The deletion experiment CURED these (int-or-null
  83 -> 53 moved into agreement).
The 20x DOUBLE<>BIGINT reds from the same experiment are the same
structure under Float labels (the 14-row limb + relatives).

**THE REAL LEG — declaration-vs-fixture skew census, then split:**
the harness executes every setup CREATE TABLE through OUR OWN
platform, so the skew is STATICALLY knowable: census each
###Relational column kind against the setup-DDL column kind
(normalizer/harness-level instrument, fetchDb-adjacent). Then:
population (a) rows move to a NAMED fixture-skew registry (counted,
witnessed — engine test-data debt, not a coercion excuse);
population (b) rows adopt the wire at construction and BOTH arm
limbs DELETE. Host decode behavior needs no change — measured: both
populations' cells already follow the wire end-to-end (in.pure '4'
and tree.pure 11 both pass today WITHOUT conversion anywhere).
Ranked with the carrier-types leg per tests-per-design.

**§4Z ledger state after this arc:** #1 rulebook 159 -> 111 (97+14,
both receipted contract-vs-wire questions for the queued leg, no
longer unexamined excuses); #2 wire 55 -> 7 (3x HUGEINT<>DOUBLE
testReprocessGroupByAlias + 2x JSON<>VARCHAR fetchDbMetaData + 2x
JSON<>BIGINT dropAndCreateTable — all named, wire-review items);
#5 excuse arms: Decimal limb dead; remaining arms carry per-row
referee receipts. Next per §4Z sequencing: ScalarSubquery 340.

## 4bZ. THE FULL ENGINE HOMEWORK (2026-08-25, user-ordered — "they
## must have pinned this for a reason"; every claim receipted from
## engine/pure source + git archaeology + the new fixture-skew census)

**THE FIXTURE-SKEW CENSUS (new instrument, landed with this section):**
the Runner already tracks every executed CREATE TABLE
(familyLiveShapes); the census compares each one against the
###Relational declaration's column kinds (RelationalKinds.pureKindOf
vs the setup DDL's type tokens) at the noteExecutedDdl seam. Full
sweep: **469 family-column skew witnesses** — 287x StrictDate<-
DateTime, 105x Integer<-String, 56x Integer<-Float, 12x
Integer<-DateTime (bicycles in_z/out_z!), 6x String<-Integer, 3x
Float<-Integer. Sweep green, every existing pin unchanged (the
census is pure measurement).

**VERDICT ON "feature or sloppiness" — it SPLITS, and the engine's
repo distinguishes the two:**

1. **The numeric conversions are a DELIBERATE, TESTED FEATURE.**
   testDataTypeMapping's property names spell the intent
   (decimalAsFloat, numericAsFloat, floatAsDecimal). Mechanism,
   consistent across BOTH runtimes: DECIMAL/NUMERIC wires flatten to
   Float AT THE FETCH (interpreted ExecuteInDb.java:81 maps
   Types.DECIMAL->M3Paths.Float; compiled ResultSetValueHandlers'
   DECIMAL handler is getBigDecimal(i).doubleValue()), with the
   result boundary re-inflating Decimal-DECLARED slots via toDecimal
   (buildExecutionResultInTDS) — the flatten+re-inflate pairing is
   design, almost certainly dating from when Float was pure's only
   floating kind and Decimal was added later. OUR HOME: core, as
   SQL emission (Slice A) — correct and kept.
2. **The carry-through tolerance is PINNED BY INERTIA, not design.**
   Zero validation exists anywhere (engine RelationalValidator checks
   structure only — joins/subtypes; legend-pure has none; NO
   kind-compatibility matrix on either side); zero purpose-built
   tests (the mismatches ride incidentally inside tests about other
   features); ALL of it — fixtures, fetch rules, boundary arms —
   bottoms out in bulk migrations (engine #662 "Receive modules from
   legend-pure", pure #135 "Externalize Relational Platform Modules")
   predating open source; relationalSetUp.pure alone hand-writes 29
   executeInDb CREATE TABLEs never checked against the declarations.
   The asserts calcified around whatever the wire produced.
3. **The temporal skew (287) is the DECLARATIONS' fault, by the
   engine's own rules.** 88 of 90 distinct skewed temporal columns
   are milestoning _z columns: stores hand-declare from_z/thru_z
   DATE (relationalSetUp.pure:113-114) while fixtures create
   TIMESTAMP — and the engine's own pure->relational type map sends
   abstract Date -> Timestamp (pureToRelational.pure:53; StrictDate
   -> Date). Pure's date model confirms the user's hypothesis: the
   CORE runtime abstraction is the PureDate PRECISION LADDER
   (hasMonth/hasDay/hasHour...; concrete Year, YearMonth,
   StrictDate=day, DateWithHour..Subsecond, LatestDate — a
   milestoning sentinel INSIDE the type family); StrictDate/DateTime
   are carved-out precision views. TIMESTAMP is where the general
   Date concept lives — our F5.4 subsumption arm already models
   exactly this. Processing milestoning compares now() (DateTime);
   business milestoning's infinity sentinel is a StrictDate
   (%9999-12-31, milestoning.pure:573). This class joins the
   EXISTING temporal-identity leg, NOT the carry-through story.

**THE PLAN (user-aligned, brainstormed 2026-08-25):**
- Conversions stay CORE (emission — done).
- Carry-through re-homes to the LEGEND COMPAT LEVEL (three-dialect-
  levels architecture) as a NAMED tolerance relation, gated by
  PROVENANCE at the mapping seam (only reads that crossed a declared
  kind-mismatched property/column pairing are tolerated — kills the
  weak-guard problem of type-pair arms), with the skew census as
  per-row receipts. Core admissible() keeps draining toward
  widenings-or-empty (§4Z unchanged in spirit; the two arms retire
  from CORE by moving to the named compat surface, not by deletion
  tricks).
- LEGEND_LITE (product surface): kind-mismatched mappings become a
  DIAGNOSTIC, strict flip LAST (parser-program pattern) — upstream
  never designed the tolerance, so strictness on our surface
  contradicts nothing.
- The non-temporal fixture skew files UPSTREAM as test-data drift
  (it demonstrably protects no feature); the temporal class is
  excluded from the filing (it back-implies the declarations should
  be TIMESTAMP — engine's own map says so).
- Wire values need NO chasing: we execute the same fixtures, so our
  wires match the engine's automatically; the referee pins BOTH
  channels (cell values follow the WIRE — in.pure '4', tree.pure 11,
  showcase raw print; TDS schema follows the MODEL —
  testSimpleTypeMappingProject toJSON "type":"Integer"), so any
  design forcing one channel to impersonate the other fails real
  tests — measured twice this arc.

**§4bZ POST-LANDING AUDIT (2026-08-25, read-only probe battery run
while the landing chain executed; all green, pushed e63acade):**
- **No false positives from our own DDL**: Ddl's DuckDB
  physicalizations are kind-faithful (Float->DOUBLE, Bit->BOOLEAN
  stay in-kind; SemiStructured->JSON maps to null in fixtureKind and
  skips) — module-generated CREATEs cannot register as skew.
- **Census rows source-verified**: ordertable.quantity declared INT
  (relationalSetUp:112) / created FLOAT (:1469). The 12x
  Integer<-DateTime class is REAL and is the homework's hardest
  receipt yet: the engine's own ###Relational declares the Bicycles
  MILESTONING business-date columns `in_z INTEGER, out_z INTEGER`
  (businessDateMilestoningSetUp.pure:2374) while the fixture creates
  TIMESTAMP (:387) and the milestoning block compares them to a
  timestamp INFINITY_DATE — temporal milestoning compiles and runs
  over INTEGER-declared columns, zero validation, proven from a
  second independent angle.
- **Ledger arithmetic re-verified**: rulebook 111 = 97 VARCHAR<-
  BIGINT + 14 DOUBLE<-BIGINT (census classes); wire diverge 7;
  witness total 469.
- **KNOWN UNDERCOUNTS (recorded, never silent — both miss-only,
  neither can produce a wrong row):** (1) schema-qualified fixture
  CREATEs (~12 distinct in the engine tree, e.g.
  productSchema.productTable) are invisible — moduleColumnKinds keys
  bare table names; (2) columns literally named key/check/constraint
  etc. are skipped by parseCreateColumns' constraint-word filter.
  Close both if the census graduates from instrument to pin.

## 4bZ-R. THE GUEST LIST — EXECUTED (2026-08-25, pushed 8e25242a;
## chain green; user directive "let's do it")

Mechanism as landed: TypeFact.Typed.tolerated set at the TWO sites
that know a read crossed a declared property/column mismatch
(Scalars' typeAsDeclared rule + the Lowerer's cellRootUnwrapWire
strip site, via SqlTyping.tolerateRead — Column-door only, supplied-
leaf semantics); identity reducers transport the arg's fact; stamped
re-reads propagate (Column.of carries OutputCol.tolerated);
reconciliation keeps declared labels ONLY for tagged reads over the
named carryThrough pairs and stamps the slot; the wire census reads
the slot. The two blanket arms are GONE from admissible().

**AUDIT VERDICT (the sweep the design exists for):**
- ALL 111 arrived tagged — tolerated=111 (97 VARCHAR + 14 DOUBLE),
  equality-visible in every sweep summary, pinned <=111. The §4bZ
  "sampled, not proven" caveat is CLOSED: every row machine-proven a
  mapping quirk, per sweep, forever.
- The pardon's deletion EXPOSED 20 hidden rows: the CEILING/FLOOR/
  SIGN type rule described bare ceil() (probed DOUBLE) while OUR OWN
  renderer casts to BIGINT for every input (AnsiSqlRenderer, pure's
  Integer contract) — a rule-vs-emission lie the blanket arm had
  absorbed. Rule now types THE EMISSION (BIGINT); the 20 healed to
  wire-agree.
- Bonus attribution: int-or-null 83 -> 53 — 30 ambiguous wire rows
  resolved into wire-tolerated VARCHAR <- INTEGER (the fixture-skew
  wires). Wire diverge = 7 (the named residue), mismatch 0, untyped
  24, all soft ceilings/scoreboard byte-stable.

**Recorded loosenesses (audit, miss-only):** the wire-side tolerance
check is TAG-only (does not re-verify the kind pair — visible in the
class string, cannot hide a label lie); an all-NULL tolerated column
is indistinguishable from a skew wire (inherited int-or-null
ambiguity). Guardrail fallout: Lowerer hit 3511/3500 — variant-shape
trio split to VariantShapes (seam split, Comparators precedent).

**REMAINING for the full §4bZ plan:** the LEGEND_LITE strict
diagnostic (kind-mismatched mapping = warning on our product
surface, strict flip LAST) and the upstream test-data filing — both
still open; carryThrough itself now lives in SqlTyping beside
admissible() with the compat receipts (the "compat level" is the
NAMED RELATION + tag, not yet a separate dialect-level enforcement
point — acceptable current state, revisit at the dialect-levels leg).

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

## 4bZ-U. THE UNTYPED FLOOR, RE-ADJUDICATED (2026-08-25, user
## challenge — "we always know the TYPE of pivot columns, just not
## the names"; every mortality claim re-examined, most overturned)

State after the full burn + corrections: **corpus 4 / pct 108**, both
pinned. The user's challenge overturned most of the "mortal by
design" claims — the honest decomposition is DESIGN LEGS, not
mortality:

1. **Dynamic-pivot columns (36, the biggest chunk): NOT mortal.**
   Only the NAMES are runtime-discovered (data values become column
   names); each value column's TYPE is its aggregate's, knowable at
   compile time. Burns at the queued demand-driven-stamp leg (the
   phase-1c follow-up: the reader asks the pivot builder for the
   type, never the name).
2. **HASH (12): was never mortal — FIXED same day (6d4a49b9).** Our
   own renderer reinterprets to signed BIGINT (DuckDb.hashSigned);
   the "UBIGINT outside vocabulary" claim was the CEILING
   rule-vs-emission mistake REPEATED (bare-builtin probe instead of
   our emission). Second offense of the lesson class — probe THE
   EMISSION.
3. **error() (9): modelable — a RAISES/NEVER fact.** A raising
   expression yields no value and conforms to every slot; today it's
   a structural special-case in uniform() and UNKNOWN at roots. The
   leg: a fourth TypeFact variant (touches every exhaustive switch —
   half-day, queued).
4. **The ~63 member-driven remnants: SPLIT PENDING.** Two piles
   mixed: (a) missing lambda-binding doors — construction sites that
   hold the element type but never call the attachment door —
   mechanically burnable; (b) variant PAYLOAD shapes (what's inside
   a JSON value) — the only genuinely data-dependent unknowns in the
   whole system. The decomposition pass is the remaining work.
5. **Corpus 4**: 2 metadata-grid columns — DOWNGRADED from mortal to
   modelable (the JDBC spec FIXES the metadata result schema; a
   fixed-schema table-function model types them); 1 JSON-under-
   VARCHAR carrier chain (burns at the carrier-types leg); 1
   unstamped subagg lateral (pile (a) — mechanical).

**Corrected end-state claim:** the only truly unknowable types in
the system are variant payload shapes. Everything else is a named
leg: demand-driven pivot stamps (36) > lambda-binding sweep (part of
63+1) > Raises fact (9) > fixed-schema metadata model (2) > carrier
leg (1). True zero = those legs + per-row variant-payload receipts.

**§4bZ-U EXECUTION RECORD (2026-08-25 — the burn, one G1+G4-witnessed
slice per move; every ceiling move measured):**
- **Corpus untyped 4 -> 0, HARDENED TO EQUALITY** (RelationalCorpusRunner);
  wire diverge 4 -> 2 (the dropAndCreateTable late-bound residue),
  wire unknown 13 -> 2, mismatch 0 throughout.
- **pct untyped 108 -> 20** (PctCensusGate, unfiltered Grammar
  teardown); raises=9; adopt-pending 101 -> 64; diverge 78 -> 46.
- **Leg 1 (pivot stamps):** Fold.pivotColumn — a `<value>__|__<t>`
  read stamps its aggregate TEMPLATE's type; the stamp speaks the
  Reducer's EMISSION fact (SUM->HUGEINT, decimal SUM->DEC(38,s)),
  NOT Using.type (the MODEL channel PctTdsWrap decodes TDS headers
  from). The first cut stamped the pure contract and the wire census
  exposed 3 new DOUBLE<>DECIMAL diverges + 6 adopt-pendings — the
  CEILING rule-vs-emission mistake, THIRD sighting; amended same
  session. Burned 36 Column + 2 Reducer pct rows.
- **Leg 3 (RAISES):** TypeFact.Raises (yields no value, conforms to
  every slot); callType ERROR -> RAISES; uniform() reads the fact
  (structural skip deleted); census counts raises= visibly. Only ONE
  exhaustive TypeFact switch existed (the census) — the half-day
  budget was 30 minutes.
- **Leg 4 (fetchDb):** CatalogGrids.gridSchema — the catalog grids
  carry DECLARED JDBC-spec schemas (String/Integer[0..1] per the
  DatabaseMetaData contract); no late-bound wildcard, no LIMIT-0
  probe; .columnNames answers statically (Typer's declared-relation
  arm); resolveInto's raw-grid fallback stamps through the lookup
  door. Trap hit: the late-bound marker path had been the ONLY
  .columnNames server — a scoped corpus run caught the wall.
- **Leg 2 (binding-door sweep):** LambdaBinding.foldResolver
  (element via Column.param, accumulator via the INIT's fact — a
  type-changing fold stays honestly UNKNOWN), lowerFold moved to
  LambdaBinding (3,500-line guard); Comparators' _cx.x element
  stamp; minus-fold LIST_REDUCE param stamps; the collection-map
  element door (mapMapper/mapElemResolver); PureSql.typedList — the
  CONFORM-BY-EMISSION list door (UNKNOWN/Bottom list positions cast
  to Array(pure element); eligibility-checked, no broad catch) at
  zip/joinStrings/fold-source/fold-init (Concatenation keeps RAW
  init — the door there double-wrapped T[][], gate-caught);
  StructLit.Field.declared — the layout builders supply each field's
  slot type so an absent optional property's NULL still contributes
  (transported at every rebuild site incl. withChildren); the REM
  decimal rule (probed no-carry union shape: s=max, p=maxInt+s).
- **Two REAL BUGS the census smoked out (fixed):** (1) the
  scalar-typed collection egress emitted UNNEST(list_filter(scalar))
  — CANNOT BIND on DuckDB (binder receipt); now boxes [e] first
  (PureSql.asList; +3 advisory diffs, JUSTIFIED — rows verified).
  (2) CheckedOne/toOneMany carried the ELEMENT type over a
  list-shaped wire; atLeastOnly now transports the list's own fact
  (the boxing exposed it: DuckDBArray-vs-Number in
  AuditTier1PipelineTest).
- **THE 20 (receipts, full composition only — scoped Essential shows
  3):** 14x StructGet + 6x UNNEST in fold/instance chains:
  mixed-struct LIST_CONCAT (heterogeneous class LUB — a TRUE payload
  shape), FoldCall collection-accumulator (accIsList carrier's
  declared no-rule), chained LIST_REDUCE(LIST_TRANSFORM), and
  composition-dependent fold-channel struct chains. Named per-row in
  every sweep's witness dump; next session may burn the mechanical
  half via the fold-native lane.
- **Justified ceiling moves (same-commit justifications in the
  runner):** advisory SQL 309->312, sqldiff-pass 257->258, adv-pass
  303->304 (the [e] boxing's spelling on row-verified tests).
- **Traps for the roster:** probe with a FRESH connection per query
  (a failed DuckDB statement poisons the connection — every later
  probe reports 'unsuccessful or closed pending query result');
  scoped corpus/pct runs have DIFFERENT compositions than the full
  sweeps (the subagg test ERRORS scoped but passes full; Essential
  alone shows 3 untyped vs 20) — diagnose scoped, adjudicate full.

**SECOND WAVE (same day — user ruling "burn 2 and 3 to zero"; the
tree-receipt method: every remaining row's construction tree captured
before any fix):**
- **Corpus wire diverge 2 -> 0, HARDENED TO EQUALITY.** Ruling
  executed: a by-name FIELD read over a late-bound executeInDb frame
  DEMANDS the LIMIT-0 probe (RawGridSchema's widened gate), and the
  probe now carries the database's own column types
  (GridProbe.probeTypedColumns; unmapped SQL types fall back to the
  trusted-Any column). The bare .rows egress keeps P3-2's
  single-query path; ExecuteInDbProbeCountTest pins BOTH sides (the
  named-read pin flipped 0 -> 1 probe with the ruling recorded).
  JavaEvalLedger 48 -> 52 justified (schema plumbing beside the
  probe).
- **pct untyped 20 -> 1 -> 0, HARDENED TO EQUALITY** (2026-08-26;
  diverge 46 -> 45 rode along). The last row was testSimpleProject's
  EMPTY `values` collection: an empty literal types Nil, so the
  instance-projection lateral VARCHAR-guessed its element under a
  StructGet('val') — the element type now comes from the colspec
  BODY's own declared segment types (InstanceProjection.pathTypesOf;
  first cut read the empty literal's own info and re-guessed via
  Nil->VARCHAR — the LITERAL's type is never the DECLARED type).
  **LEDGER #3 IS AT TRUE ZERO ON BOTH LANES.**
  All 20 trees captured; mechanisms, each probed or receipt-driven:
  property reads over stamped params (the foldResolver/
  mapElemResolver struct-field arms — $p1.lastName was a plain
  qualified column); the accIsList fold rule (probed: the list-boxed
  lane delivers the accumulator's own array); mixed-CLASS concatenate
  conforms to the VARIANT carrier (probed: raw struct concat
  FIELD-UNIONS {name}++{place} into one smeared struct — pure keeps
  per-element kinds, testConcatenateTypeInference types the LUB
  superclass; one value one carrier, the hetero-literal doctrine);
  the dedup typedList door (empty removeDuplicates chains); the
  InstanceProjection elem stamp (a hardcoded-VARCHAR lateral). THE 1
  = StructGet('val') over an elem stamped by the many-column
  lateral's schema STRING FALLBACK (Lowerer manyCols arm,
  .orElse(STRING)) — a recorded guess, the next lead.
- **CENSUS-INTEGRITY FINDING:** channel-A pct suites NEVER set
  SqlTypeCensus.CONTEXT — every witness [test] tag on the pct lane is
  a ChannelB leftover on the shared thread (between_String tagged
  rows that test cannot build). Trees, not tags, for attribution;
  wiring channel-A contexts is a small open instrument fix.
- **§4bZ-V C homework banked:** DIVIDE probes ALL-DOUBLE on 1.5.0
  (the (1.0 * x)/y spelling — the rule is correct; the DOUBLE<>
  DECIMAL diverge family is NOT division), adopt-64 witnesses are
  windowed SUMs (star-frame reconciliation skip is the leading
  theory), method = class-keyed tree capture at the wire census.

## 4bZ-V. THE FULL-BURN INDEX (2026-08-25, user-ordered completeness
## audit — "don't miss/defer things"; THE one authoritative list of
## everything remaining on the burn to zero. An item leaves this list
## only by landing with receipts or by a user ruling. The audit that
## produced it found FOUR items living in census printouts with no
## charter owner — now owned below.)

**A. The untyped finish (next session; §4bZ-U holds the legs):**
A1 demand-driven pivot stamps (36 pct) · A2 lambda-binding sweep
(pile-a of the ~63 + corpus subagg lateral) · A3 the RAISES TypeFact
(9) · A4 fixed-schema metadata model (corpus 2 untyped AND — the
cross-reference the audit added — the corpus WIRE-diverge 4, same
tests, same cure; the wire ceiling burns 4 -> 0 with this leg) ·
A5 variant-payload receipts (pile-b — the only true unknowns).

**B. The carrier-types leg (§4Z #5, own charter-first session):**
three excuse arms become modeled logical carriers (partial-temporal
text, Number-identity text, JSON-as-text) + the widens re-home (2
subsumption arms with lossless round-trip witnesses) → admissible()
EMPTY. Carries the corpus concatenate-testAll untyped row. End state:
two named proven relations, nothing forgiven.

**C. PCT-LANE WIRE LEDGERS — NEWLY OWNED (the audit's main find):
never adjudicated.** Measured 2026-08-25: adopt-pending 94 (ceiling
101), diverge 54 (ceiling 78) — the corpus twins were burned to
EQUALITY-0 and 4-named; the pct twins got ceilings and silence.
Work: the same adjudication the corpus got (adopt/register/fix per
class, witness dump via the PctCensusGate decomposition), ceilings
to equality/named. Also G7's h2 lane (untyped 17, diverge 0) —
decompose the 17 once, name or burn.

**C EXECUTED (2026-08-26, 83f64f0b — diverge 45→0 and adopt-pending
64→0, BOTH EQUALITY pins; full 8-gate chain green ~10.1 min).** The
wire-tree capture method (witness CONTEXT tags are unreliable —
channel A never sets them; capture construction trees). The kills,
each probed on the 1.5.0 jar: star-tail label reconciliation (EVERY
remaining row lived in a star-bearing frame the old size gate
skipped wholesale; verified the tail-alignment invariant at all
builders — join-prefix frames tail-align with already-stamped
equal-type reads); Float/NUMBER TDS cells seed FloatLit not
DecimalLit (the DOUBLE<>DECIMAL head-column family — literal seeds
type whole VALUES columns); the DecimalLit fact/emission split
(scale-0 within long = d-suffixed pure DECIMAL → fact Decimal(p,0)
+ renderer CAST; beyond long = big pure INTEGER → HUGEINT; ≥128-bit
and >38-digit fractional → DOUBLE, probed; negative-scale normalize
— 1E+3 renders "1000" so precision counts RENDERED digits); GUID →
CAST(uuid() AS VARCHAR); repeatString VARCHAR-casts an untyped arg
(BLOB overload); descending continuous percentile = the NEGATION
identity -(qc(-v,p)) — the (1-p) transform diverged in float ULPs
(aggFlavor seam; the bivariate arm throws loudly if it would drop
the negation). Ascending-continuous pins repinned 2.1→2.2 etc.: the
old goldens froze the quantile_cont-over-DECIMAL truncation
artifact (probed both carriers). TRAP (3rd sighting, now a rule):
typing the DecimalLit FACT by magnitude alone flipped percentile's
mixed-kind carrier dispatch — facts follow the CONTRACT, emissions
follow the fact. TRAP (chain hygiene): hand-run G4/G6 MUST copy
allgates' spellings — `-pl core` + `-Dlegend.engine.root/
-Dlegend.pure.root` (a first witness run used `-pl engine` (deleted
module) + env-only roots and was killed). Remaining in C: the G7 h2
17-untyped decomposition (untouched).

**D. KINDS-CENSUS RESIDUE — NEWLY OWNED:** D1 int-or-null (corpus
53 / pct 222): DuckDB spells all-NULL columns INTEGER — needs VALUE
evidence (the decode-tripwire design) to split real-integer wires
from empty columns; unresolvable by type rules, needs its own small
leg. D2 wire unknown (corpus 13 / pct 8): metadata-unreadable or
shape-mismatched probes — never once examined; one witness dump
decides fix/register each.

**E. The nullability program (§4Z #4):** 6,472 — census-first
charter, after A (its census already samples witnesses per class).

**F. Guest-list completion (§4bZ-R):** LEGEND_LITE strict diagnostic
(mismatched mapping = warning, strict flip LAST) + dialect-level
scoping of carryThrough (the compat level as an enforcement point,
at the dialect-levels leg).

**G. Standing named debts (older, still real):** G1 temporal-identity
leg (mixed-precision dedup + the 287-row temporal skew class — §4A/
§4bZ) · G2 upstream fixture-skew filing (non-temporal classes; the
469-witness census is the evidence file) · G3 skew-census promotion
to a pinned ceiling + its two recorded undercounts (schema-qualified
creates, constraint-word columns) · G4 Comparators.select min/max
recognition to the typed level (the G9 stamp trip) · G5 gate-8
runtime decomposition (250s vs 63s pin) · G6 h2-exec standing lanes
(632 text-divergent-rescued, 145 unverifiable — the R2 canonical
program's fuel) + canonical pin 27 leniencies · G7 corpus burn-down
RESUME decision (paused 2333/2575 by user directive; resume queue in
memory).

Sequencing (recommended): A → C (mechanical adjudication) → B → D →
E; F/G slot opportunistically or by ruling.
