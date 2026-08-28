# Lambda-classifier slice audit (2026-08-28, post-4ec7c2cb)

Adversarial audit of the §4V slice (m3-true function-value
classifiers). Question per mechanism: is it ARCHITECTURAL (the model
carries the fact; consumers read it) or BESPOKE (a consumer re-derives
or compensates)? Every claim carries a receipt from code.

## Receipts

**R1 — mint census (130 `new TypedLambda(` sites in main).**
57 pass-through (`x.info()` — stamp-preserving rewrites), 71 fresh
FunctionType infos (constructor normalizes to `LambdaFunction<ft>`),
1 explicit carrier (the eta arm's `ConcreteFunctionDefinition<ft>`),
and **exactly one body-typed mint: `DriverPkAppend.java:144`** —
`new ExprType(src.type(), src.multiplicity())` stamps the COLUMN's
type (Integer/String) on a lambda node. This is the sole producer the
five tolerant readers exist to serve, and it matches the slice's
failure evidence exactly (validation/milestoning ride DriverPkAppend;
the throwers said "non-function classifier: Integer/String").

**R2 — positional argument pairing is sound today.** The kernel's
widened generic arms (raw-subtype + pairwise argument unify) assume a
subclass's type arguments align positionally with its superclass's.
Registry scan: only THREE parameterized-extends-parameterized
generalizations exist, all identity-argument function carriers
(`FunctionDefinition<F> extends Function<F>` etc.). The violating
shape (`X<T> extends Y<String>`) would fail CONSERVATIVELY
(wrong-reject, never wrong-accept). Unpinned invariant — see F3.

**R3 — reader conversions are behavior-equivalent.** 20 former CASTS
→ strict `functionType()` (they threw ClassCastException before; same
loudness, better message). 14 former instanceof GUARDS → tolerant
`functionTypeOf` (fallback semantics preserved — restored after the
first sweep caught five of them converted strict). Zero remaining
direct `instanceof`/cast reads of a lambda's stamp outside the reader.

**R4 — three registered signatures WEAKEN real engine spellings**
(found by grepping carrier-with-structural-arg spellings: 0
registered, 4 in comments documenting the real pure):
- `meta::pure::router::execute` — real: `f:FunctionDefinition<{->T[y]}>[1]`
  (router_entry.pure:20/:47); ours registers `Function<{->T[m]}>`.
- `preval` — real: `f:FunctionDefinition<T>[1]` (preeval.pure:53/:58);
  ours registers `Function<{->T[*]}>`.
- `concatenateTemporalTdsQueries` — real:
  `lfs:LambdaFunction<{->TabularDataSet[1]}>[*]` (milestoning.pure:753);
  ours registers `Function<{->T[*]}>[*]`.
These predate the slice (bare-Ft stamps could not meet nominal
formals). **That excuse died with §4V** — this is the same
"support the correct signatures" class the execute<T|m> ruling banned.

**R5 — kernel conformance quadrants.** Formal × actual:
- structural formal (bare Ft / `Function<{sig}>`) × any carrier
  actual → both unwrap, structural check. Pre-slice behavior ✓.
- nominal-carrier formal (`FunctionDefinition<Any>`) × carrier actual
  → carrier kept, lattice judges (LF/CFD ≤ FD ✓, Function ✗). NEW ✓.
- nominal-carrier formal × BARE-Ft actual → reject (a bare Ft is this
  platform's artifact for "some function, classifier unknown";
  conservative) ✓.
- `Function<Any>` formal × lambda literal → now ACCEPTS via
  self-type + lattice (pre-slice: candidate rejected at the arity
  gate). Engine-true improvement (every lambda IS a Function).
- **HOLE (latent): carrier formal WITH structural arg** — e.g.
  `LambdaFunction<{sig}>` — `unwrapFunction` erases the formal's
  NOMINAL constraint, so a `ConcreteFunctionDefinition<sig>` or
  Function-carrier actual with a matching signature would be accepted
  where engine demands a LambdaFunction. Latent only because R4's
  weakenings mean no such formal is registered; fixing R4 makes it
  LIVE. Fix belongs with F2.

**R6 — classifier drift through re-minting rewriters.**
`Substitution.rowLambda/identityLambda` (and peers) rebuild the info
from a fresh FunctionType → the constructor re-classifies as
`LambdaFunction` even when the input was an eta
`ConcreteFunctionDefinition`. No consumer dispatches on LF-vs-CFD
today, so no behavior change — but the reflection leg will care.

**R7 — the eta value-lie.** The eta-expanded reference's CLASSIFIER
is honest (`ConcreteFunctionDefinition<ft>`), but its VALUE is a
synthetic wrapper whose body is a call to the function. When the
`expressionSequence` reflection leg lands (the pkOfFunc 43), reading
`$f.expressionSequence` off an eta wrapper would reflect the CALL
EXPRESSION, not the referenced function's real body — engine reflects
the real body. The reflection leg must carry function IDENTITY (keep
the reference a reference), not read through the eta value.

## Verdicts

ARCHITECTURAL (keep): constructor-owned normalization (the node owns
its classifier — no mint site can produce a bare stamp); the single
reader pair; conformance in the kernel's one lattice (both halves
agree); demangle by exact raw simple name.

BESPOKE RESIDUE (burn): the five tolerant readers exist only to
tolerate ONE dishonest mint (R1). The weakened signatures (R4) are
recorded shortcuts. The unwrap-erasure hole (R5) is the kernel
re-deriving "structural is enough" at consumption when construction
knew the nominal constraint.

## Fix slices (ranked)

- **F1** — DriverPkAppend mints a real `{row[1]->col[m]}` FunctionType;
  flip the five tolerant readers STRICT. Falsifier: full sweep must be
  census-byte-stable (any surviving body-typed producer throws loudly).
- **F2** — re-spell the R4 signatures engine-verbatim
  (FunctionDefinition/LambdaFunction params and returns) AND complete
  the kernel: when BOTH sides are carriers, the nominal lattice check
  precedes the structural unwrap (closes R5's hole in the same slice —
  the spellings are its witnesses). Witnesses: router execute accepts
  a lambda (LF ≤ FD), concatenateTemporalTdsQueries accepts a lambda,
  a `Function<{…}>`-typed variable is REJECTED by both.
- **F3** — governance pin: every parameterized generalization in the
  registry is identity-argument (guards R2's soundness assumption the
  way the FQN-partition test guards naming).
- **F4** (rides the reflection leg, NOT now): reference identity
  survives to the value level (R6+R7) — refs stop eta-erasing where
  reflection needs them.

## Fix-slice landing record (2026-08-28, same day)

F1+F2+F3 EXECUTED. The falsifier sweep (strict readers, full corpus)
confirmed R1: after DriverPkAppend mints a real `{row->col}`
FunctionType, NOTHING throws — it was the sole body-typed producer;
all seven tolerant readers are now STRICT (Lowerer ×2,
Fold.isManyScalarCol, Fold.leafResultType, Pivots, AssociationJoins
×2). Census exactly 3,161/9/2,071 end-to-end.

**R8 (found DURING the fix): `meta::pure::mapping::execute` is an
INVENTED FQN.** Four receipts: (a) no .pure declaration in either
checkout; (b) no .java reference to the FQN in either checkout (the
package-name hits are unrelated — RoutedValueSpecification, service
DSL); (c) structurally, a real-pure native REQUIRES a .pure
declaration even when Java-implemented — no declaration, no function;
(d) the POSITIVE proof of what bare `execute` really resolves to:
real pure's AUTO-IMPORT list (legend-pure m3.pure, the Import
instances around :195-210) includes `meta::pure::router` — engine
tests need no explicit import to reach `router::execute`. The
invention was a plausible early guess (bare call + Result living in
meta::pure::mapping) that violates the FQN-partition doctrine
(platform inventions belong in the Pure.Lite namespace; real packages
stay verbatim). The registration stays as a legacy alias, spelled
IDENTICALLY to router's verbatim signature — the overload machinery's
duplicate-signature tolerance makes the two FQNs interchangeable
(first wins deterministically). Deleting the alias is its own leg
(harness isExecuteFqn + explicit-FQN spellings).

Three iterations the falsifier forced, each a receipt:
1. `<T|y>` verbatim mult-var name made router's shape unequal to the
   alias's under the tie-break's equality — ALPHA-RENAMED to `m`
   (bound-variable names are not signature semantics).
2. The alias and router spelled differently = a LOUD 2-native tie on
   every bare `execute` whose value is carrier-typed (11 tests) —
   closed by R8 (identical spellings).
3. `concatenateTemporalTdsQueries` re-spelled with the engine's
   `TabularDataSet` interior schema-ERASED the row type and broke six
   downstream sorts/groupBys — the platform types rows statically
   where the engine's TDS is late-bound, so the interior stays the
   DECLARED generic-`T` deviation; the CARRIER (LambdaFunction,
   nominal, witnessed) is the part R4 actually demanded.

F2's kernel completion (nominal gate before structural unwrap) and
F3's governance pin (identity-argument generalizations) landed with
witnesses: router/alias execute + preval accept lambdas, reject
Function-typed variables; concatenateTemporalTdsQueries rejects a
Function-carrier with a MATCHING signature (the R5 hole, closed).
