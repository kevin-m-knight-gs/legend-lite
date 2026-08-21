# HOST-LOGIC DEEP AUDIT — 2026-08-20

**The ruling that commissioned this** (user, verbatim): "the point … is to push
as much logic to the database as makes sense … we have our tenets, stop using
crutch of what legend-engine does to justify our laziness — we have to do a
full deep sweep and deep audit of all the places we do logic/formatting in
java and understand why (was it laziness or necessity)."

Standing consequence: **legend-engine is the SEMANTIC oracle only.** "Engine
does it host-side" is never an admissible architecture argument. Every
Java-side value-logic/formatting arm must carry a necessity proof on OUR
tenets, or be scheduled for eviction.

## The two discriminators

- **D1 — World-2 consistency**: if the same comparison/format ran through SQL
  (in a filter/select) on this platform, would it agree with the host arm?
  Disagreement = the arm is masking a wire/emission defect from the census.
- **D2 — emission test**: is the arm absorbing a wire value that lowering
  should have emitted differently? (The parseDate TIMESTAMPTZ leak is the
  type specimen: the fix was `timezone('UTC', …)` at EMISSION, and the
  absorbing verdict arm was deleted.)

Classification vocabulary:
- **NECESSITY-DEFINITIONAL** — SQL cannot express it *by definition*
  (reference identity, compile-time facts, decoding a host exception).
- **NECESSITY-DECODE** — inverting what the JDBC driver physically handed
  back, bijectively, no policy choice (Timestamp→LocalDateTime).
- **ADAPTER** — converting to a FOREIGN runtime's object model (channel A's
  interpreter CoreInstances). Necessary while the adapter exists; shrinks
  with Phase 5.
- **SPEC-VERDICT** — pure semantics re-implemented host-side. Passing D1
  today does not make it architecture-correct: it is a SECOND (in fact
  THIRD) implementation and the standing eviction target.
- **LAZINESS** — fails D1 or D2. Fix at emission/lowering, delete the arm.

## Headline finding

**The verdict channel is a third implementation of pure value semantics.**
Interpreter (upstream) = 1. Our SQL lowering (equality ladders, sort, print
forms — `Render.tdsCell/dateTimeText/strictDateText`, `pctTds`) = 2.
`PureAsserts.equalScalar/sorted/repr` + `GridCompare` = 3. The
[[harness-platformization]] charter already names this class of debt for the
corpus harness; this audit extends the same verdict to the K-arm's comparison
core.

**The tenet-true endgame**: for DB-expressible operands, the K-arm should
COMPILE the comparison through the platform —
`assert(equal(e, a))` lowers to ONE SQL boolean via the platform's own
equality (which already implements pure kind-strictness in World 2);
`assertSameElements` = platform `sort` + `equal` in SQL; failure messages
compose from DB-RENDERED reprs (the `Render` spelling family — the same owner
as the PCT wire), fetched only on failure. Host arms remain ONLY for the
definitional impossibles. Values then never cross the wire for comparison at
all — the carrier-artifact policies (TDSNull sentinel, string-carrier bridge,
integral×Decimal) mostly evaporate because the crossing they patched no
longer happens.

Known deltas that become EMISSION rules (not host arms) under that design:
- pure `equal(1, 1.0)` is FALSE (kind-strict) where raw SQL `1 = 1.0` is
  true → the typed compiler statically knows both kinds; cross-kind equality
  folds to constant false at COMPILE time (no runtime cost).
- NaN: DuckDB `'NaN' = 'NaN'` is TRUE; pure says false → equality emission
  guards float compares with `NOT isnan(x)` where the static kind is Float.
- The 2-ULP corpus tolerance is a DECLARED TEST POLICY (H2-libm vs DuckDB
  drift), not equality semantics — it stays host-side but corpus-scoped,
  with its census instrument.

## Per-surface adjudication

### core/exec/PureAsserts.java (298-pinned + uncommitted)
| Arm | Class | Verdict |
|---|---|---|
| `equal`/`equalScalar` ordered equality, integral×Decimal, Decimal compareTo, float kind gate, nonFinite | SPEC-VERDICT | Passes D1 today; **eviction target** — compile through platform equality (headline). |
| TDSNull sentinel | SPEC-VERDICT (corpus ledger artifact) | Channel-A expected-text artifact; dies for channel B under compile-through; stays corpus-scoped. |
| 2-ULP double leniency | DECLARED TOLERANCE | Fails D1 *by design* (SQL is exact). Keep ONLY with its census instrument re-read; corpus-scoped, never product. |
| temporal string-carrier bridge | SPEC-VERDICT over a DESIGNED carrier | Partial-precision dates ARE strings in both worlds (pinned) — D1 passes. Eviction target with compile-through. |
| `temporalEquals` offset parsing (2026-08-20 restructure) | SPEC-VERDICT | Instant equality, naive≡UTC — matches `PureDateLiteral`'s GMT-normalize rule. D1 passes ONLY because parseDate emission now normalizes (this audit's first fix). |
| ~~`utcLocal` both-temporal arm~~ | **LAZINESS** | DELETED same day — absorbed the TIMESTAMPTZ emission leak (D2 failure). Type specimen. |
| `repr`/`reprSide`/`joined` | SPEC-VERDICT (formatting) | **Second spelling owner** beside Render's SQL forms — the tdsCell/pctCell drift disease, one audit from recurring. Eviction target: failure messages compose from DB-rendered reprs. |
| `sorted`/`typeRank`/`withinRank` | SPEC-VERDICT | Third implementation of pure total order (platform sort exists in SQL). Eviction target via compile-through. |
| `assertInstanceOf` + `carrierTypeName` | NECESSITY-DECODE (borderline) | Runtime kind read off the carrier. Alternative: `typeof()` in SQL; either way one decode table. Low priority. |
| `assertEq` non-primitive refusal | NECESSITY-DEFINITIONAL | Identity unobservable on a wire; refusal is the honest arm. |

### core/AssertVerdicts.java (398-pinned)
| Arm | Class | Verdict |
|---|---|---|
| K-arm routing, `side()` (args execute in DB) | orchestration | The chartered shape — args DO execute in the DB. Keep. |
| quantified `map(f\|assert(...))` vectorize-in-SQL | already tenet-true | Predicate computes in SQL; host judges booleans. The MODEL for the headline redesign. |
| `isVerdict`/`typeIdentityOf`/`instanceOrigin` (slice 11) | NECESSITY-DEFINITIONAL | Identity + compile-time type facts; not DB-expressible. Keep; canonical-spelling table is compiler metadata, not value formatting. |
| `tabular`/`cells` grid fetch for verdicts | SPEC-VERDICT plumbing | Dies with GridCompare's compile-through (below). |

### core/exec/GridCompare.java (295-pinned)
| Arm | Class | Verdict |
|---|---|---|
| `grids` ordered/multiset row compare | SPEC-VERDICT | **Eviction candidate**: SQL-native compare — ordered = zip by row_number and count mismatches; multiset = `(a EXCEPT ALL b) UNION ALL (b EXCEPT ALL a)` empty. One boolean fetch. |
| `tdsEquivalent` numeric/temporal deltas | SPEC-VERDICT | Same: `abs(x-y) <= delta` per cell IS a SQL expression. Eviction candidate. |
| `renderedText`/`lineEquals`/`cellEquals` | SPEC-VERDICT + TOLERANCE | Compares platform-RENDERED text vs corpus expected text with a hand-rolled sig-digit float tolerance. The tolerance heuristic (sig>=10 → 0.5×10^-dp …) is burn-pressure policy, corpus-scoped; keep behind its instrument, census overdue. Text framing compare itself is verification-consumes-two-sides. |
| `ordLeniency` instrument | measurement | Keep. |

### core/exec/JsonCompare.java (70-pinned)
One walker, two documented leaf rules (wire vs document). Verification
consumes two sides. **Clean** — no formatting, no policy beyond the two leaf
rules. Keep; folds into compile-through where operands are relational.

### core/exec/Executor.java decode cluster (`fetch`, `decodeAny`, `jsonUnescape`, struct unwrap)
NECESSITY-DECODE with written contracts: BC-era Timestamp carrier, JsonNode
text, variant-carrier unescape delegating to the ONE table (sql/Json), Any-root
number kinds (documented divergence from strict JSON bridge). Each arm inverts
an emission THIS platform made. **Keep — this IS the boundary.** Watch: any
NEW arm here needs its producing emission named in the same commit.

### pct/…/ExecuteLegendLiteQuery.java (850-pinned)
ADAPTER (channel A only): regex ingress + CoreInstance egress
(`toCoreInstance`, `toPureDateInstance`, `structToInstance`,
`stripTrailingZeros`, `remapErrorMessage`). Exists to feed the REFERENCE
interpreter's object model — a foreign API, definitionally host-side. Not the
product surface (channel B compiles test bodies whole and never enters here).
Phase 5 (adapter split) is the standing shrink program; no new arms.

### StatementExecutor.java (2724-pinned)
Orchestration + absorption watch. `evalValue` executes IN the DB. The
DeferredTdsString probe-bridge composes '#TDS' text IN SQL (6d honored). No
value formatting found beyond routing. Keep, shrink-only.

### LiteralFold.java (60) / resolver/LiteralFolds.java
Differential-pinned ConstantExecutionNode mirror (engine's own constant
path). Compile-time constant folding is COMPILER work, not runtime host
evaluation — in-tenet (types drive construction). Keep.

### Metamodel channel (MetamodelWalk 1307, PlanText 750, MetamodelSteps 195, AggAwareActivities 225)
Model constants + engine-parity TEXT composition through single-owner
spellings; `ArchitectureTest.theInterpreterPerformsNoJdbc` makes "no DB value
enters" mechanical. Compilation-class text, not value formatting. Keep.

### Corpus harness (test scope): EngineTestExecutor (3075), H2Verify (640)
The ratified [[harness-platformization]] program's subject — the harness is
the acknowledged third implementation for CORPUS verification. This audit
adds: its decode/format arms get the same D1/D2 review as PureAsserts when
that program resumes; no new arms meanwhile.

### Render.java / PctRender (SQL-side print forms)
`tdsCell`/`dateTimeText`/`strictDateText`/`pctTds` — value→text lives in the
DATABASE. **This is the model** the host `repr()` should defer to.

## Fix queue (ordered)

1. **DONE in tree (this audit's first specimens)**: parseDate zone emission
   → `timezone('UTC', CAST(x AS TIMESTAMPTZ))` (naive-UTC carrier honored);
   `utcLocal` verdict arm deleted; `repr` offset arm reverted to its
   witnessed form. Decimal literal-list product/reduce → binary DECIMAL
   chains at EMISSION (`decimalChain` — probed exact).
2. **+0000-strip archaeology — CLOSED CLEAN (2026-08-20)**: the producers
   are SQL EMISSION sites (Scalars:2591 CONCAT/STRFTIME + :3425
   dateLiteralPrint) implementing pure's own toString spec ("DateTime
   normalized to +0000"), composed IN the database. The temporalEquals
   strip is decode of that designed print carrier — D1 passes; not a leak.
3. **Compile-through equality (the headline leg)**: K-arm lowers
   `equal(e,a)`/`eq` через platform equality to ONE SQL boolean where both
   operands are DB-expressible; static cross-kind folds to constant false at
   compile time; float emission gains the `NOT isnan` guard; messages fetch
   DB-rendered reprs on failure only. PureAsserts equal/sorted/repr arms
   shrink to the definitional residue. Ledger rows SHRINK.
4. **Grid compile-through**: GridCompare.grids/tdsEquivalent → SQL
   EXCEPT/row_number/abs-delta forms; one boolean fetch.
5. **Tolerance census re-read**: run the sweep with LL_TOL_COUNT +
   LL_ORD_COUNT, publish the counts, re-ratify or shrink both policies.
6. **assertInstanceOf via `typeof()`** (optional, low): kind read in SQL.

Rows 3–4 are the redesign; they retire the bulk of SPEC-VERDICT code and are
the honest answer to "why is there value logic in the asserts."

## The guard that keeps it fixed

**World-2 paired probes**: every surviving host equality/normalization arm
gets a test that runs the same comparison through SQL and asserts agreement
(disagreement = the arm is masking an emission defect). New PureAsserts/
GridCompare arms without a paired probe fail review by construction. To be
added as `VerdictWorld2ConsistencyTest` alongside the existing registers.
