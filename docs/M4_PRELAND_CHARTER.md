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

## 2. THE THREE RESIDUAL ROWS (diagnose before landing)

The branch's final chain was green EXCEPT: postprocessor −1,
tests/query −2 (park note). Diagnosis method: cherry-view the branch
(NO merge), run G4 scoped (`-Drcorpus.only=...` on those families)
on a THROWAWAY worktree of the branch, name the three tests and their
failure modes. They are the branch's known unfinished edges — each
becomes either a pre-land slice or a recorded M4 acceptance item.

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

## 4b. T4 ATTEMPT 2 HOMEWORK (do AFTER M4 lands, BEFORE any code —
## attempt 1 failed on exactly the surfaces this list enumerates)

1. **Locate the seam**: find every code site where a mapped property
   is paired with its physical column (the resolver/mapping
   machinery); confirm the pairing is UNIQUE there (no name lookup);
   count the sites. The conform cast (Cast.conform — plumbing already
   banked) emits at those sites only, gated on the CONCRETE stamp
   (Float/String; abstract Number/Any never — castErasure referee).
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
