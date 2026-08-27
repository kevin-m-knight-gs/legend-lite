# TYPE SYSTEM END-TO-END AUDIT — pure → typed IR → SQL → wire → decode → verdict (2026-08-26)

User-ordered ("really deep audit"), run directly, census-first. Method:
the ratified audit question at every seam — *does consumption re-derive
what construction knew?* — plus the SqlUnion lesson generalized: *does
every rebuild transport ALL FOUR OutputCol dimensions (name, type,
nullable, tolerated)?* Every verdict carries a code receipt; every
claim that could be measured was measured on the full corpus sweep.

## The seam table

| # | stage | seams | verdict | receipt |
|---|---|---|---|---|
| A | pure → typed IR | Typer/checkers/ExprType; schemaView; multiplicity | STANDING-VERIFIED (not re-derived here) | 1,005 type-logic tests + PCT 1,115 + corpus regression gate, all green 2× this week |
| B | typed IR → SQL facts (birth) | 9-site funnel: SqlTyping.typed (owner), rule table typeOf (constructor-driven), Column.of doors ×3, LambdaBinding ×2, SqlTyping 256/864 | SOUND | untyped=0 EQUALITY on all four lanes is the completeness tripwire; facts-follow-contract / emissions-follow-fact rule recorded 3× |
| C | frame construction (OutputCol) | 36 sites / 13 files, classified: 25 synthetic BORN (fine); pure-schema BORN = Lowerer.outputsOf + value frame (nullable = multiplicity, one authority); RECONCILED = SqlSelect ctor + SqlUnion ctor (landed §4bZ-V D); pass-through rebuilds re-enter the ctors (idempotent) | **3 FINDINGS** | see findings 1–2 below |
| D | judged relations | subsumes (round-trip witnesses), carryThrough (tag-gated), delivers (wire side, consults subsumes — B2 parity) | SOUND, one owner each | SqlTyping 147/157/212/270; census 167/349/505 |
| E | render / dialect | single compiler + dialect strategies; marker casts render-transparent (TEMPORAL_TEXT elision) | STANDING-VERIFIED | advisory-SQL ceiling 312 EXACT; G8 byte parity 6,489/6,489 |
| F | wire | probeWire: TOTAL adjudication since §4bZ-V D — agree / tolerated / delivered / diverge (EQ-0) / unknown (EQ-0) / int-null-empty (value-settled) + the NEW converse tripwire | **FINDING 3 (measured)** | null-breach census below |
| G | decode | fetch (driver-object-kind), unwrap (label-driven doors: LITERAL, TEMPORAL_TEXT, Struct, Array), decodeAny (variant contract; sniffing deleted 08-24), DecodeShapes (plan-shape only) | CLEAN | "convert, never sniff" package doctrine; F10 slice-3 audit note at Executor.decodeAny |
| H | host values → verdicts | CanonicalForm, PureAsserts, ChannelB dual-verdict | STANDING-VERIFIED | canon pin 27; DUAL-VERDICT disagree=0 alarm; sql-verdict disagree=0 |

## Findings

**1. Three latent tag-dropping rebuilds (FIXED this audit).**
ExistsJoinForm:122 (rename rebuild), Render:445 (pivot presentation),
Render:673 (hoist carry) used the 3-arg OutputCol ctor — dropping
`tolerated` exactly as SqlUnion's ctor did before §4bZ-V D. Zero
traffic today (all pins green), i.e. LATENT, cured structurally by
4-arg transport. The adoption arm's 3-arg at SqlTyping:163 is
CORRECT by design: adoption means the pair changed, so the stale tag
must not survive (recorded, not fixed).

**2. TypeFact carries no nullability — the expression channel cannot
transport it.** [RESOLVED 2026-08-27 — §E3 M-N1..M-N3 (TYPED_SQL_IR.md
landing records): option (a)+(c) landed — Typed(type, nullable,
tolerated) computed at construction, probed per-SqlFn arms, DDL/join-pad
frame authorities, GROUP-BY slot refinement, labels adopt slot truth.] Nullable moves only through output-list copies;
a Column re-read over a nullable child slot re-derives its frame's
nullable from the pure multiplicity ([1] → false). Structural, not a
site bug: no per-site fix exists without either (a) a nullable
dimension on TypeFact.Typed, (b) reconcileLabels threading the
from-tree to consult child slots, or (c) NULL-propagation rules per
SqlFn (SUM over empty group → nullable; EQUAL over nullable operand →
nullable; COUNT → never). This is a NULLABILITY-INFERENCE LEG — a
real design program, not a patch.

**3. The converse breach census (finding 2 made empirical).**
The new tripwire watches every nullable=false column and counts wire
NULL sightings at statement settle. Corpus lane measured: **925**
(638 VARCHAR + 124 BIGINT + 122 DOUBLE + 34 BOOLEAN + 7 HUGEINT);
pct lane **49** (46 HUGEINT empty-group sums + 3 DOUBLE float aggregates).
Witness shapes: outer-join slots (columnValueDifference count_1/2),
empty-group aggregates (SUM over Case[DOUBLE,Bottom]), subtype-pad-fed
sums (stc_* wheelCountSum), boolean exprs over nullable reads
(EQUAL under milestoning), upper-frame re-reads (syn := t4.agg_0).
ADJUDICATION: the VALUES are correct (the engine produces the same
NULLs — LEFT-join and empty-group semantics); the LABEL under-declares
— label-honesty debt, the 6,472's sibling through the expression
channel. [BURNED 2026-08-27: 925 -> 841 (M-N2 pad weakening) -> 0
(M-N3 flip); pct 49 -> 0. Both lanes pinned EQUALITY-0 — a wire NULL
under a never-null label is thereafter a compiler bug, always loud.]

## Fix slices

| slice | state | content |
|---|---|---|
| E1 four-dim transport | **LANDED this audit** | the three 3-arg rebuilds harden to 4-arg |
| E2 breach census + pins | **LANDED this audit** | converse tripwire (probeWire watch + fetch null-mark + settle), null-breach= in summary, ceiling pins both lanes |
| E3 nullability-inference leg | CHARTERED, not started | findings 2+3's burn: pick (a)/(b)/(c) above with the flag's first consumer; the 925-row census is the fuel and the acceptance |
| E4 T4 tails | CHARTERED (next arc) | tolerated-origin 108 adjudication against the skew census (registry + adoption + arm shrink); scalarRoot LITERAL-label arm retirement |
| E5 comparator typed-level | CHARTERED (G4 debt) | Comparators.select recognition moves from lowered-SqlExpr pattern-match to TypedLambda (stamps perturb shape equality — the G9 trip) |
| E6 temporal identity | CHARTERED (G1 debt) | mixed-precision dedup + the 287-row temporal skew class |

## What the audit did NOT find

No label lie reaches execution unverified: every kind dimension
(type, tolerance, delivery) is now EQUALITY- or ceiling-pinned on
every lane, unknowns are structurally impossible (EQ-0), and the one
unverified dimension (nullability) is now measured with named
witnesses on both lanes. Decode is label-driven with zero value
sniffing. The two query-node constructors both reconcile. The audit's
verdict: the type system's honesty surface is COMPLETE as instruments;
the remaining debt is one chartered inference leg and two chartered
recognition/identity legs, all fueled by pinned censuses.
