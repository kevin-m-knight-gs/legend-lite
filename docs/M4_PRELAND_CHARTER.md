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
4. **`eq(1, 1.0)`** — the byte-equality design ASSUMES cross-kind
   numeric inequality. FIRST CENSUS RECEIPT (2026-08-25): pure's own
   PCT eq tests NEVER test the cross-kind case
   (legend-pure .../boolean/equality/eq.pure — testEqInteger and
   testEqFloat are same-kind only), and eq's doc says "numeric
   equality" (hinting TRUE is possible). Pure has THREE flavors
   (eq / equal / ==) that may answer differently. UNRESOLVED —
   verify against the real oracle (the eq/equal NATIVE
   implementations in legend-pure Java, or run the interpreter)
   before trusting byte-equality anywhere.
   Verified in passing: cross-PRECISION dates are unequal
   (assertFalse(eq(%2014, %2014-01-01)) — eq.pure:54) ✓ supports
   spelling disjointness for dates.

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
