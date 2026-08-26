# REMEDIATION PLAN — legend-lite type system
**Author:** R1-remediation (remediation engineer, not an auditor)
**Inputs:** `CONFIRMED.md` V1–V35 (orchestrator-reproduced), `findings/A01…A27`, `report/narrative.md`.
No `V1-falsifier.md` / `V2-falsifier.md` existed at the time of writing; no `MASTER.md` existed.
**Nothing under `/home/user/legend-lite` was modified.** Every code quote below was read from the
file at the cited line; every claim that a defect still reproduces was re-run through
`/home/user/probe/probe.sh` before it was written down.

---

## 0. THE ONE-PARAGRAPH DIAGNOSIS

The narrative's thesis is right and this plan is built on it: **legend-lite computes types with real
rigour and then does not enforce them.** But the repair is *not* "add checks everywhere". Reading the
code changes the shape of the problem in three ways that matter for sequencing:

1. **Several of the missing checks are not missing — they are present and then deliberately
   discarded.** V26 is the clearest case: `MappingNormalizer.coerceColumnToDeclared` *does* compare
   the declared property kind against the physical column kind and *does* emit a coercion — and then
   `Lowerer.java:1393` unconditionally strips it back off. The elision is correct for one caller
   (the engine-text funnel) and leaks into every other. That is a one-line fix, not a new subsystem.
2. **The largest measured family is a multiplicity-algebra bug, not a mapping bug.** 960 of A20's
   1,010 unsound results are `NULL_IN_ONE`, and they decompose into exactly three rules that are
   each individually wrong: `LEFT JOIN` copies the right side's lower bounds verbatim, SQL
   aggregates over empty groups return NULL under a `[1]` stamp, and `toOne()` lowers to nothing.
   Fixing those three rules is worth more than every other fix on this list combined.
3. **A handful of "defects" are adjudicated design decisions with written receipts** (`cast`
   converting, `typeAsDeclared` being type-only, `toOne` flowing in the relational lane). Those need
   a *decision*, not a patch, and §5 says which ones I think should stand.

Ordering below is by (harm × reachability) / effort, with the A20 fuzz share used as the
reachability measure wherever one exists.

---

## 1. RANKED FIX LIST (one line each)

| # | Fix | Site | Finding | Effort | Why here |
|---|-----|------|---------|--------|----------|
| F1 | `LEFT/RIGHT/FULL` join must weaken the null-extended side's column lower bounds to 0 | `compiler/spec/JoinChecker.java:70-78` | A20 | S | the single most common relational shape; part of the 960-hit `NULL_IN_ONE` family; pure type fix, no SQL change |
| F2 | Aggregates must deliver their declared empty-input value (`sum→COALESCE(…,0)`, `joinStrings→COALESCE(…,'')`) or be re-declared `[0..1]` | `lowering/Aggregates.java:30-59`, `builtin/Pure.java:2187-2189` | V19 | M | rest of the `NULL_IN_ONE` family; `max` already right, `sum` wrong — an inconsistency that regenerates |
| F3 | The mapping's WIRE coercion must stop being stripped on the **execution** path | `lowering/Lowerer.java:1393` | V26a | **XS (1 line)** | the check already exists and is thrown away; highest value/effort ratio on the list |
| F4 | `Decimal(p,s)` on a property must classify to `Type.PrecisionDecimal`, not `Decimal<>` | `compiler/element/TypeClassifier.java:100-107` | V33 | S | one degenerate type defeats *all 14* decimal-handling sites at once (V14/V32/V13) |
| F5 | `->toOne()` must assert in object space, not vanish | `lowering/Scalars.java:443-487` | V20 | M | Pure's only runtime cardinality assertion; 5 independent fuzz shrinks land here |
| F6 | A generic user function's declared return must be checked against **rigid** type variables | `compiler/spec/SpecCompiler.java:129-165` | V18 | M | cleanest unsoundness proof in the corpus; today the check is vacuous for *every* generic function |
| F7 | `Multiplicity.product` must not multiply in `int` | `compiler/element/type/Multiplicity.java:110-122` | V5 | **XS (4 lines)** | silent `[0]`/`[0..1]` wraparound + 3 distinct ICEs, from ordinary model text |
| F8 | `==` with one optional operand must take the same `IS NOT NULL` guard `>` already takes | `lowering/NullSemantics.java:133-152` | V1 | **XS (1 line)** | `>` and `==` disagree inside one projection; the machinery is already written |
| F9 | A numeric declared/column kind mismatch must convert or refuse — not rename | `normalizer/MappingNormalizer.java:2438-2456`, `lowering/Scalars.java:674-676` | V26b | M | the other half of the mapping root cause; needs a decision (§5 argues which way) |
| F10 | Cross-kind `==` must be `false`, not a database coercion | `lowering/Scalars.java:110-133,154-156` | V11 | S | `1 == '1' → true` is wrong under every semantics, incl. this repo's own |
| F11 | Set operations must parenthesise branches carrying `ORDER BY`/`LIMIT`/`OFFSET` | `sql/dialect/AnsiSqlRenderer.java:115-121` | A20 | **XS (5 lines)** | 234 of 431 `BAD_SQL` hits (54%); `sort→concatenate` is an ordinary pipeline |
| F12 | `^Class()` must reject a missing required (`[1]`/`[1..*]`) property | `compiler/spec/NewChecker.java:66-131` | V27 | M | real blast radius on synthesized ctors — needs the provenance flag in the spec |
| F13 | `extends …type::Nil` must be rejected; the bottom arm must leave the recursion | `compiler/element/ModelContext.java:232-256` | V10 | S | three lines of user Pure collapse the whole subtype relation |
| F14 | `plan()` must render the runtime's **declared** dialect, as `execute()` does | `Compiler.java:539-548` | V28 | S | `plan()` is the documented plan-inspection surface and it emits unrunnable SQL |
| F15 | A user definition colliding with a registered native must not join the overload set; delete the invented `first(set,count)` | `compiler/element/FunctionCompiler.java` `functionsAt`, `builtin/Pure.java:1345` + `lowering/Scalars.java:1407-1411` | V23, V25, V35 | S | one isolation hole and one invented overload whose lowering silently drops an argument |
| F16 | `cast(@Any)->cast(@T)` must not be a bare passthrough (the V2 half only) | `lowering/CastPolicy.java:47-98` | V2 | S | V3's converting-cast half is an adjudicated decision — see §5 |

**Structural recommendation (§3):** an **egress conformance check** at `ExecutionResult`
construction. By A20's own taxonomy it would have caught **1,010 / 1,010 (100%)** of the unsound
fuzz results — because A20's mutation-tested oracle *is* that check, already written.

---

## 2. FIX SPECS

Notation: *Site* = the exact method to change with its current lines quoted. *Change* = minimal
prose + sketch. *Blast radius* = what else reads the path (grepped). *Regression test* = a concrete
assertion, in the style the repo already uses.

---

### F1 — `LEFT`/`RIGHT`/`FULL` join must weaken the null-extended side's lower bounds

**Site.** The join's output schema is the signature's `T+V` algebra, resolved kind-blind:

`core/src/main/java/com/legend/compiler/spec/InferenceKernel.java:812-826`
```java
case UNION -> {
    List<Type.Column> cols = new ArrayList<>(lr.columns());
    if (right instanceof Type.RelationType rr) {
        for (Type.Column c : rr.columns()) {
            if (lr.columns().stream().anyMatch(e -> sameColumn(e.name(), c.name()))) {
                throw new SchemaInvariantException("the column '" + c.name()
                        + "' already exists in the relation " + lr.typeName());
            }
            cols.add(c);                       // <-- multiplicity copied VERBATIM
        }
    }
    return new Type.RelationType(cols, lr.dynamicColumns());
}
```
The kernel cannot know the `JoinKind` — it is resolving a signature. The place that *does* know is
`core/src/main/java/com/legend/compiler/spec/JoinChecker.java:70-78`:
```java
Application a = t.checkGeneric(af, env);
if (a.args().size() != 4 || !(a.args().get(2) instanceof TypedEnumValue kind)
        || !(a.args().get(3) instanceof TypedLambda cond)) {
    throw new TypeInferenceException(
            "join expects (rel1, rel2, JoinKind, {t,v|cond} [, 'prefix'])");
}
return new TypedJoin(a.args().get(0), a.args().get(1), kind, cond,
        Optional.empty(), null, a.out(), true /* USER lambda */);
```
`a.out()` is the verbatim union. Reproduced (A20, 8 distinct shrink seeds):
`Relation<(c0:String[1], c1:String[1])>` where every `c1` row is `NULL`.

**Change.** Post-process `a.out()` in `JoinChecker` (and in `withPrefix`, which computes the
prefixed union bespoke) by the join kind: the side that can be null-extended has *every* column's
lower bound dropped to 0, upper bound untouched.

```java
// JoinChecker
private static Type outerWeakened(Type out, String kind,
                                  int leftWidth) {
    Type.RelationType rt = Type.relationSchema(out);
    if (rt == null) return out;
    boolean weakenRight = kind.equals("LEFT")  || kind.equals("FULL");
    boolean weakenLeft  = kind.equals("RIGHT") || kind.equals("FULL");
    List<Type.Column> cols = new ArrayList<>(rt.columns().size());
    for (int i = 0; i < rt.columns().size(); i++) {
        Type.Column c = rt.columns().get(i);
        boolean weaken = i < leftWidth ? weakenLeft : weakenRight;
        cols.add(weaken ? new Type.Column(c.name(), c.type(), optional(c.multiplicity())) : c);
    }
    // re-wrap: relationSchema() unwraps Relation<schema>; there is no rebuild
    // helper today, so spell the wrapper (Type.java:381-386 is the inverse).
    return new Type.GenericType(com.legend.builtin.Pure.RELATION.qualifiedName(),
            List.of(new Type.RelationType(cols, rt.dynamicColumns())));
}

/** [n..m] -> [0..m]; a variable multiplicity is left alone (loud downstream, as today). */
private static Multiplicity optional(Multiplicity m) {
    return m instanceof Multiplicity.Bounded b && b.lower() > 0
            ? new Multiplicity.Bounded(0, b.upper()) : m;
}
```
`leftWidth` is `Type.relationSchema(a.args().get(0).info().type()).columns().size()` — the union
appends the right side after the left, so position is a sound discriminator (the kernel throws on
name collision, so there is no interleaving).

**Blast radius.** `TypedJoin` is read by 21 production files (`StatementExecutor`,
`lowering/{Lowerer,CollectionLanes,NullSemantics}`,
`resolver/{DriverPkAppend,TemporalFrame,ChainedExists,CorrelatedSubselects,NavMaterializer,FlattenOps,AssociationJoins,…}`).
None of them re-derives the schema — they read `info()`. The change is therefore **type-only**: no
SQL text moves, so `SqlTextRatchetTest` and every SQL golden are untouched. What *does* move is
declared-type text in any test that spells a join result type. Grep for tests to update:
`grep -rn "join(" core/src/test --include=*.java | grep -i "Relation<"`.
The resolver's *synthesized* joins (`AssociationJoins`, `NavMaterializer`) construct `TypedJoin`
directly rather than through `JoinChecker` — they must take the same helper, or association
navigation across a `[0..1]` end stays over-declared. That is the one place this fix can be
half-applied and look done.

**Regression test.** In the join test class:
```java
@Test
void leftJoinWeakensTheRightSidesLowerBounds() {
    var t = Compiler.compileQuery(MODEL,
        "fz::Widget.all()->project(~[c0:w|$w.name])"
      + "->join(fz::Part.all()->project(~[c1:z|$z.partName]), JoinKind.LEFT,"
      + "       {l, rr | $l.c0 == $rr.c1})");
    var rt = Type.relationSchema(t.info().type());
    assertEquals(Multiplicity.Bounded.ONE,      rt.columns().get(0).multiplicity()); // left keeps [1]
    assertEquals(Multiplicity.Bounded.ZERO_ONE, rt.columns().get(1).multiplicity()); // right weakened
}
```

---

### F2 — Aggregates must honour their declared empty-input value

**Site.** `core/src/main/java/com/legend/builtin/Pure.java:2187-2189`
```java
SUM__FLOAT_MANY   = ... sum(numbers:Float[*]):Float[1];
SUM__INTEGER_MANY = ... sum(numbers:Integer[*]):Integer[1];
SUM__NUMBER_MANY  = ... sum(numbers:Number[*]):Number[1];
```
and `core/src/main/java/com/legend/lowering/Aggregates.java:29-59`
```java
family(SqlAgg.Fn.SUM, "sum");
family(SqlAgg.Fn.SUM, "plus");
...
family(SqlAgg.Fn.STRING_AGG, "joinStrings");
family(SqlAgg.Fn.STDDEV_SAMP, "stdDevSample");
family(SqlAgg.Fn.VAR_SAMP, "variance");
```
The reducer map goes straight from the Pure name to the bare SQL aggregate. `SUM()` of an empty or
all-NULL group is `NULL`; `sum` is declared `[1]`. `max` is declared `[0..1]`
(`Pure.java:1886-1892`) — so the two rules disagree for byte-identical inputs (V19, A20 Repro A).

**The key observation is that the `[1]` declarations are *not* wrong.** Real Pure's `sum([])` is
`0` and `joinStrings([])` is `''`. The lowering, not the signature, is what breaks the contract.

**Change.** Two buckets, decided per function by what real Pure returns on empty input:

*(a) has a total identity — emit it.* In the aggregate-expression builder (`Lowerer.java:1124`
`Aggregates.reducerFor(...)` call site), wrap the reducer:
```java
private static SqlExpr withEmptyIdentity(SqlAgg.Fn fn, SqlExpr agg, Type declared) {
    return switch (fn) {
        case SUM        -> SqlExpr.Call.of(SqlFn.COALESCE, agg, zeroOf(declared));
        case STRING_AGG -> SqlExpr.Call.of(SqlFn.COALESCE, agg, new SqlExpr.StringLit(""));
        case COUNT      -> agg;                       // COUNT is already total
        default         -> agg;                       // bucket (b)
    };
}
```
*(b) genuinely partial — re-declare `[0..1]`.* `average`, `stdDevSample`, `stdDev`,
`stdDevPopulation`, `varianceSample`, `variancePopulation`, `variance`, `median`, `mode`,
`percentile`, `corr`, `covarSample`, `covarPopulation` are NULL for an empty (and, for the *sample*
statistics, a one-element) input by definition. Change their return multiplicity to `[0..1]` in
`Pure.java`, matching `max`/`min`, which are already right. V35 shows `percentile` is *already*
inconsistent with itself across its two overloads (`Number[1]` vs `Number[0..1]`) — this fix closes
that too.

**Blast radius.** `Aggregates.reducerFor/reducerOrNull` has 6 call sites
(`Scalars:2602`, `RelationPredicates:76`, `Lowerer:1109,1112,1124,1301`). Bucket (a) changes emitted
SQL text — `SqlTextRatchetTest` does not pin SQL text, but the **corpus goldens under
`scripts/corpus/` and `core/src/test/java/com/legend/rcorpus/` do**, and every `groupBy` golden with
a `sum` will gain a `COALESCE`. That is the real cost of this fix and it should be paid in one
mechanical golden re-bless, not spread out. Bucket (b) is type-only and changes any test that
spells an aggregate column's declared multiplicity.

**Regression test.**
```java
@Test
void sumOverAnEmptyGroupDeliversZeroNotNull() {          // bucket (a)
    var r = exec("fz::Widget.all()->project(~[c0:w|$w.qty])->limit(0)"
               + "->groupBy(~[], ~[c1:v|$v.c0:y|$y->sum()])");
    assertEquals(Multiplicity.Bounded.ONE, r.columns().get(0).multiplicity());
    assertEquals(0L, r.rows().get(0).cells().get(0));     // NOT null
}

@Test
void sampleStatisticsAreOptional() {                     // bucket (b)
    assertEquals(Multiplicity.Bounded.ZERO_ONE,
        Pure.nativeAt("meta::pure::functions::math::stdDevSample").returnMultiplicity());
}
```

---

### F3 — the mapping's WIRE coercion must stop being stripped on the execution path

**This is the highest value-per-line fix on the list.** V26 is filed as "the relational mapping
performs NO property-type / column-type check". It performs one. It is then deleted.

**Site.** `normalizer/MappingNormalizer.java:2389-2470` (`coerceColumnToDeclared`) already computes
the mismatch and emits a coercion:
```java
if ("String".equals(declared) || "Boolean".equals(declared)) {
    return new AppliedFunction(Pure.Lite.CAST_AS_DECLARED, List.of(read,
            new TypeAnnotation.Named(new TypeExpression.NameRef(declared))));
}
```
`Typer.java:1265-1281` types that as a WIRE-flagged `TypedCast`. `CastPolicy.lower` correctly
suppresses it **only** inside the engine-text funnel:

`lowering/CastPolicy.java:47-53`
```java
static SqlExpr lower(TypedCast c, SqlExpr value, boolean isMany) {
    if (c.wire() && EngineTextBoundary.active()) {
        return value;
    }
```
But a *second*, **unguarded** stripper runs first, at every projected cell root:

`lowering/Lowerer.java:1392-1397`
```java
TypedSpec cellRoot = last(c.fn());
TypedSpec body = CastPolicy.cellRootUnwrapWire(cellRoot);
// a STRIPPED wire cast leaves the bare mismatched read —
// it takes the same engine-compat tag the typeAsDeclared door applies
boolean unwrapped = body != cellRoot;
```
`lowering/CastPolicy.java:269-274`
```java
static TypedSpec cellRootUnwrapWire(TypedSpec b) {
    if (b instanceof TypedCast tc && tc.wire()
            && tc.target() == Type.Primitive.STRING) {
        return cellRootUnwrapWire(tc.source());
    }
```
`EngineTextBoundary.enter()` is called from **exactly one place** in production —
`StatementExecutor.java:527`, the `toSQLString`/`planToString` funnel (verified by repo-wide grep).
So the `cellRootUnwrapWire` elision, whose javadoc justifies itself entirely in terms of engine
goldens ("the goldens never spell wire casts"), fires on the **execution** path too.

Re-verified today with the V26 fixture (`/home/user/audit/mine/mm.pure`):
```
[PLAN]     SELECT t0.NUM AS s, t0.DEC AS i FROM T_ITEM AS t0
[EXEC-COL] s : String [STRING] mult=[1]
[EXEC-ROW] Integer(42) | BigDecimal(123.45) |
```

**Change.** One line — give the second stripper the same gate the first one has.
```java
// Lowerer.java:1393
TypedSpec body = EngineTextBoundary.active()
        ? CastPolicy.cellRootUnwrapWire(cellRoot)
        : cellRoot;
```
(Cleaner still: move the gate *inside* `cellRootUnwrapWire` so there is one owner of the rule, and
have it return `b` unchanged when the boundary is inactive. Then `Lowerer:1396`'s `unwrapped` flag
and the `SqlTyping.tolerateRead` tolerance it triggers also correctly stop firing on the execution
path, which is what lets the egress check of §3 see the mismatch at all.)

**Blast radius.** `cellRootUnwrapWire` has exactly one production caller (`Lowerer:1393`) — grepped.
No test names it (`grep -rl "castAsDeclared\|typeAsDeclared\|cellRootUnwrapWire" core/src/test/`
returns only `native-catalog.txt`). What changes is **executed SQL for models whose declared property
kind differs from the physical column kind** — a `String[1]` property over an INTEGER column will now
render `CAST(t0.NUM AS VARCHAR)`. Engine-text goldens are unaffected *by construction*, because the
boundary is active there. The corpus rows most likely to move are the multigrain/tree families the
javadoc names (`tree.pure` asserts a Long under a String-declared property) — those rows are asserting
the *bug*, and each needs a per-row verdict, not a blanket re-bless.

**Regression test.** This one deserves to be a parity test, because the whole defect is one lane
disagreeing with another:
```java
@Test
void aStringPropertyOverAnIntegerColumnDeliversAString() {
    var r = Compiler.execute(MM_MODEL, "model::Item.all()->project(~[s:x|$x.s])", "test::R", duck);
    assertEquals(Type.Primitive.STRING, r.columns().get(0).pureType());
    assertInstanceOf(String.class, r.rows().get(0).cells().get(0));   // was java.lang.Integer
}

@Test
void theEngineTextFunnelStillReadsTheCoercionBare() {
    assertFalse(Compiler.plan(MM_MODEL, Q, "test::R").sql().toUpperCase().contains("CAST("));
}
```

---

### F4 — `Decimal(p,s)` on a property must not degenerate to `Decimal<>`

**Site.** The parser produces the right thing. `parser/TokenStreamCursor.java:737-742`:
```java
if (peek() == TokenType.PAREN_OPEN) {
    List<ValueSpecification> tvv = parseTypeVariableValues();
    return new TypeExpression.Generic(name, List.of(), List.of(), tvv,
            spanOf(startTok, pos() - 1));
}
```
`Decimal(18,4)` becomes `Generic{name="…::Decimal", arguments=[], typeVariableValues=[18, 4]}`.
The classifier then **throws the values away**:

`compiler/element/TypeClassifier.java:100-107`
```java
case TypeExpression.Generic g -> {
    List<Type> args = new ArrayList<>(g.arguments().size());
    for (TypeExpression arg : g.arguments()) {
        args.add(classify(arg, typeParams));
    }
    yield new Type.GenericType(g.name(), args);      // typeVariableValues() never read
}
```
Result (V33): `GenericType[rawFqn=…::Decimal, arguments=[]]` — a type that is neither
`Type.Primitive.DECIMAL` nor `Type.PrecisionDecimal`, so **all 14** decimal-handling sites miss it,
including the 9 that V14 confirms are otherwise correct.

**Change.** Recognise the precise-primitive spellings, and make an unrecognised one **loud** rather
than silently lossy:
```java
case TypeExpression.Generic g when !g.typeVariableValues().isEmpty() -> {
    Type base = findType(g.name()).orElseThrow(...);
    yield switch (base) {
        case Type.Primitive p when p == Type.Primitive.DECIMAL
                || p == Type.Primitive.NUMBER ->
            new Type.PrecisionDecimal(intArg(g, 0), intArg(g, 1));
        case Type.Primitive p when p == Type.Primitive.STRING ->
            base;                       // Varchar(n)/Char(n): width is not a Pure distinction
        default -> throw new ModelException(Phase.MODEL,
            "type variable values are not supported on '" + g.name() + "'");
    };
}
```
`intArg` reads a `CInteger` and throws on anything else. `Type.PrecisionDecimal`'s own constructor
already validates `0 <= scale <= precision` (`Type.java`), so bad literals fail cleanly.

**Blast radius.** `TypeClassifier.classify` is *the* single classifier — `FunctionCompiler.compile`
(parameters + return), `ClassCompiler` (properties), `PureModelContext.findProperty` (association
ends). The change is **additive**: today no `Generic` with non-empty `typeVariableValues` produces a
usable type, so nothing can be relying on the current behaviour except by accident. The one thing to
check is `parseTypeVariableValues`' other users — `Varchar(200)`, `V('ok')`, `Res<String>(1,'a')` —
which is why the sketch has an explicit `String` arm and a loud default rather than a fall-through.
Downstream, this fix gives `Type.PrecisionDecimal` real callers for the first time, which is the
precondition for un-deadening V13's arithmetic (and therefore for V32).

**Regression test.**
```java
@Test
void aDeclaredPrecisionDecimalPropertyKeepsItsPrecisionAndScale() {
    var ctx = Compiler.compileModel("Class model::Money { amount: Decimal(18,4)[1]; }");
    assertEquals(new Type.PrecisionDecimal(18, 4),
                 ctx.findProperty("model::Money", "amount").orElseThrow().type());
}

@Test
void anUnsupportedTypeVariableValueIsLoud() {
    assertThrows(ModelException.class, () ->
        Compiler.compileModel("Class model::X { b: Boolean(3)[1]; }"));
}
```

---

### F5 — `->toOne()` must assert in object space

**Site.** `lowering/Scalars.java:443-487`. The rule is carefully written and then falls through:
```java
for (String f : Pure.nativeKeysAt("toOne")) {
    RULES.put(f, (n, args) -> {
        ...
        if (m.upper() != null && m.upper() == 0) {          // static [] -> raises. Correct.
            return SqlExpr.Call.of(SqlFn.ERROR, new SqlExpr.StringLit(
                    "Cannot cast a collection of size 0 to multiplicity [1]"));
        }
        if (m.isMany() && CollectionLanes.valueLane(n.args().get(0)) && ...) {
            return new SqlExpr.CheckedOne(new SqlExpr.CompactList(args.get(0)));   // list lane. Correct.
        }
        // Everything else — [0..1] scalar reads AND many-stamped ROW-LANE
        // collections — is the engine's relational lane ... Flow
        return args.get(0);                                  // <-- the assertion vanishes
    });
}
```
V20 and A20 (seeds 1100017, 300027, 900006, 200032, 400010) both land exactly here.

**Change.** The `[0..1]` scalar-read arm is the one worth changing, and it is expressible in SQL
without a subquery:
```java
if (m.lower() == 0 && Integer.valueOf(1).equals(m.upper())) {
    return new SqlExpr.Case(
        List.of(new SqlExpr.Case.When(SqlExpr.Call.of(SqlFn.IS_NULL, args.get(0)),
                SqlExpr.Call.of(SqlFn.ERROR, new SqlExpr.StringLit(
                    "Cannot cast a collection of size 0 to multiplicity [1]")))),
        args.get(0));
}
```
The `error(...)` spelling is the one the relation-space path **already** emits (`|[]->toOne()`
renders `SELECT error('Cannot cast a collection of size 0 to multiplicity [1]')` — V20's own
evidence), so this is wiring an existing guard to a second entry point, not inventing a rule.

The remaining arm — many-stamped **row-lane** collections — cannot be guarded in SQL without a
correlated count. Leave it flowing, and let the egress check of §3 catch it. **Say so in the
comment**: today's comment claims the engine's `processNoOp` as justification for both arms; after
this fix only the row-lane arm is justified that way.

**Blast radius.** `isToOneCall` is consulted at 20 production sites
(`resolver/InnerDemand` ×5, `resolver/{SubQueryLift,ScalarValueReads,DriverPkAppend}`,
`lowering/{Lowerer:426,CastPolicy:243,275,ConstBounds,RelationPredicates}`,
`plan/RelationalMapperRenames`, `StatementExecutor:2818`, `AssertVerdicts:405`) — all of them
*recognise* the wrapper structurally and are unaffected by what it lowers to. `Lite.TRUST_ONE`, the
synthesized conformance spelling, is a **separate** rule (`Scalars.java:487-491`) and stays an
identity — that provenance split is already in place and is what makes this fix safe. SQL text moves
for every user-written `toOne()` over an optional read; corpus goldens with `->toOne()` need a
re-bless.

**Regression test.**
```java
@Test
void toOneOverANullColumnRaisesRatherThanDeliveringNull() {
    var ex = assertThrows(SQLException.class, () ->
        exec("fz::Widget.all()->project(~[c0:w|$w.note->toOne()])"));
    assertTrue(ex.getMessage().contains("Cannot cast a collection of size 0"));
}
```

---

### F6 — a generic user function's declared return must be checked under rigid type variables

**Site.** `compiler/spec/SpecCompiler.java:145-160` *does* check:
```java
if (i == last) {
    try {
        typer.requireConforms(stmt.info(), declaredReturn);
    } catch (TypeInferenceException e) {
        throw new TypeInferenceException("declares return type " + ... );
    }
}
```
and `compiler/spec/Typer.java:3165-3171` shows why the check is vacuous:
```java
void requireConforms(ExprType actual, ExprType expected) {
    kernel.unify(expected.type(), actual.type(), new Bindings());
    kernel.unifyMult(expected.multiplicity(), actual.multiplicity(), actual.type(), new Bindings());
}
```
`expected.type()` for `function my::bad<T>(x: T[1]): T[1]` is `Type.TypeVar("T")`. `unify` hits
`case Type.TypeVar v -> bindOrCheckTypeVar(v, actual, b)` (`InferenceKernel.java:82`) with a
**throwaway** `new Bindings()`, so `T := String` is bound and immediately discarded. Every generic
function's body passes, whatever it returns (V18).

**Change.** Skolemise the function's own type parameters before checking. A type parameter in a
*definition* is universally quantified — the body must work for *all* `T`, so `T` must be treated as
a rigid constant, not a solvable variable.
```java
// SpecCompiler.check(TypedFunction fn)
Bindings rigid = new Bindings();
for (String tp : fn.typeParameters()) {
    rigid.bindType(tp, Type.skolem(tp));         // new: a nominal that unifies only with itself
}
Env scope = Env.empty();
for (TypedParameter p : fn.parameters()) {
    scope = scope.with(p.name(),
        new ExprType(typer.kernel().resolve(p.type(), rigid), p.multiplicity()));
}
ExprType declaredReturn = new ExprType(
    typer.kernel().resolve(fn.returnType(), rigid), fn.returnMultiplicity());
```
`Type.skolem(name)` is a new one-line record (`record Skolem(String name) implements Type`) whose
only unification rule is identity. It never escapes `SpecCompiler.check` — the call site keeps using
the ordinary `TypeVar` machinery, so overload resolution and `UserCallInliner` are untouched.

**Blast radius.** This is the fix on this list with the widest *unknown* blast radius, because
today's check is vacuous and nobody knows what the corpus's generic functions actually return. Do
not land it as a hard error on day one. Land it as a **ledger**: collect the violations, register
them shrink-only in the style of `JavaEvalLedgerTest`, and flip to `throw` when the register reaches
zero. Enumerate the exposure first with:
`grep -rn "^function .*<" scripts/corpus pct/src core/src/test --include=*.pure`.

**Regression test.**
```java
@Test
void aGenericFunctionsBodyMustSatisfyItsDeclaredReturn() {
    var e = assertThrows(TypeInferenceException.class, () ->
        Compiler.compileQuery("function my::bad<T>(x: T[1]): T[1] { 'hello' }", "|my::bad(1)"));
    assertTrue(e.getMessage().contains("declares return type"));
}
```

---

### F7 — `Multiplicity.product` must not multiply in `int`

**Site.** `compiler/element/type/Multiplicity.java:110-122`
```java
static Multiplicity product(Multiplicity outer, Multiplicity inner) {
    if (outer instanceof Bounded a && inner instanceof Bounded b) {
        boolean zero = (a.upper() != null && a.upper() == 0)
                || (b.upper() != null && b.upper() == 0);
        Integer upper = zero ? Integer.valueOf(0)
                : a.upper() == null || b.upper() == null
                        ? null : a.upper() * b.upper();      // <-- int overflow
        return new Bounded(a.lower() * b.lower(), upper);    // <-- int overflow
    }
```
V5's measured outputs: `[0..65536] . [0..65536] = [0]` (claims always-empty for ~4.3e9 possible
values), `[0..2147483647] . [0..2147483647] = [0..1]` (claims at-most-one), plus three distinct
`IllegalArgumentException`s escaping from the `Bounded` constructor. Reachable from ordinary model
text: `Class model::A { bs: model::B[0..65536]; }` then a two-hop projection.

**Change.** Multiply in `long` and saturate to unbounded. `[*]` is the correct join for "more than
`Integer.MAX_VALUE` possible values": it is sound (a weaker bound is always safe) and it is exactly
what the lattice means.
```java
long lo = (long) a.lower() * b.lower();
Integer upper = zero ? Integer.valueOf(0)
        : a.upper() == null || b.upper() == null ? null
        : sat((long) a.upper() * b.upper());
return new Bounded(lo > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) lo, upper);

/** null == unbounded: a product that exceeds the representable range widens to [*]. */
private static @Nullable Integer sat(long v) {
    return v > Integer.MAX_VALUE ? null : (int) v;
}
```
(Saturating `lower` at `Integer.MAX_VALUE` rather than widening is deliberate: raising a lower bound
is the *unsound* direction, so it must be clamped, not dropped. In practice a lower bound that large
is already nonsense, and the clamp keeps the constructor's `upper >= lower` invariant intact because
`upper` is `null` there.)

**Blast radius.** `Multiplicity.product` has exactly **one** production caller —
`compiler/spec/Typer.java:2946-2948` (`compose`, navigation-path composition), which the javadoc
already declares the single owner. Grepped repo-wide: every other `.product(` hit is the corpus's
`$o.product(%date)` milestoned property, unrelated. This is a ~4-line change with a one-file radius.

**Regression test.** Exhaustive, in the style A02 already used:
```java
@Test
void productIsTotalOverTheRepresentableRange() {
    int[] bs = {0, 1, 2, 46340, 46341, 65536, 1073741824, Integer.MAX_VALUE};
    for (int x : bs) for (int y : bs) {
        var p = Multiplicity.product(new Bounded(0, x), new Bounded(0, y));  // must not throw
        var q = (Bounded) p;
        assertTrue(q.upper() == null || (long) q.upper() >= Math.min((long) x * y, Integer.MAX_VALUE),
                   "[0.." + x + "] . [0.." + y + "] under-declared as " + p.text());
    }
}
```
The `assertTrue` is the load-bearing half: `[0..65536] . [0..65536] = [0]` fails it today.

---

### F8 — `==` with one optional operand must take the `IS NOT NULL` guard `>` already takes

**Site.** The guard exists and is applied to the ordering comparisons only.
`lowering/NullSemantics.java:36-53`
```java
static SqlExpr optionalOperandGuards(TypedNativeCall n, List<SqlExpr> loweredArgs, SqlExpr cmp) {
    List<SqlExpr> conj = new ArrayList<>();
    for (int i = 0; i < loweredArgs.size(); i++) {
        if (isOptional(n.args().get(i).info().multiplicity()) && !isSqlLiteral(loweredArgs.get(i))) {
            conj.add(SqlExpr.Call.of(SqlFn.IS_NOT_NULL, loweredArgs.get(i)));
        }
    }
    if (conj.isEmpty()) return cmp;
    conj.add(cmp);
    return new SqlExpr.Group(new SqlExpr.Call(SqlFn.AND, conj));
}
```
`lowering/Scalars.java:171-181` calls it for `lessThan`/`lessThanEqual`/`greaterThan`/`greaterThanEqual`.
Equality does not — `lowering/Scalars.java:154-156` ends with
```java
return NullSemantics.equalNullArms(n, cargs);
```
and `NullSemantics.java:133-152` only produces a null-safe form when **both** operands are plain
columns *and* both are optional:
```java
if (!VERBATIM_EQ.get() && ops.size() == 2
        && ops.get(0) instanceof SqlExpr.Column && ops.get(1) instanceof SqlExpr.Column
        && isOptional(n.args().get(0).info().multiplicity())
        && isOptional(n.args().get(1).info().multiplicity())) {
    return new SqlExpr.Call(SqlFn.NULL_SAFE_EQUAL, ops);
}
return new SqlExpr.Call(SqlFn.EQUAL, ops);          // <-- column-vs-literal falls here
```
V1 is exactly the `column == literal` case: `SELECT t0.QTY = 10 AS eq` alongside a correctly guarded
`(t0.QTY IS NOT NULL AND t0.QTY > 3) AS gt`, in one projection.

**Change.** One line — the fallthrough takes the guard.
```java
// NullSemantics.java:149
return NullSemantics.optionalOperandGuards(n, ops, new SqlExpr.Call(SqlFn.EQUAL, ops));
```
This is semantically right for Pure as well as for the declared type: `equal([], 10)` is `false`, and
`false` is what the guard produces. The both-optional-columns `NULL_SAFE_EQUAL` arm above it must
stay — `empty == empty` is `true` in Pure, and the guard would wrongly make it `false`.

**Blast radius.** `equalNullArms` has one caller (`Scalars.java:155`). `optionalOperandGuards` is
already used by four comparison families, so its rendering (`SqlExpr.Group`, flattened by
`Fold.mergeAnd` when merged into a larger and-chain) is proven. SQL text changes only for equalities
where at least one operand is a non-literal `[0..1]` expression — corpus goldens under
`testConsistencyWithNulls`-style families are the ones to inspect, and those already spell explicit
null arms for `!=`, so the shapes will be familiar.

**Regression test.**
```java
@Test
void equalityWithAnOptionalOperandIsFalseNotNull() {
    var r = exec("model::Item.all()->project(~[eq:r|$r.qty==10, gt:r|$r.qty>3])");   // qty is NULL
    assertEquals(Boolean.FALSE, r.rows().get(0).cells().get(0));   // was null
    assertEquals(Boolean.FALSE, r.rows().get(0).cells().get(1));   // unchanged
}
```

---

### F9 — a numeric declared/column kind mismatch must convert or refuse, not rename

**Site.** `normalizer/MappingNormalizer.java:2438-2456`
```java
Set<String> numeric = Set.of("Float", "Decimal", "Integer", "Number");
if (numeric.contains(declared) && numeric.contains(colKind)) {
    if ("Float".equals(declared) && "Decimal".equals(colKind)) {
        return new AppliedFunction(Pure.Lite.CAST_AS_DECLARED, ...);   // converts
    }
    return new AppliedFunction(Pure.Lite.TYPE_AS_DECLARED, ...);       // renames only
}
```
and `lowering/Scalars.java:674-676`
```java
for (String f : Pure.nativeKeysAt("meta::legend::lite::typeAsDeclared")) {
    RULES.put(f, (n, args) -> com.legend.sql.SqlTyping.tolerateRead(args.get(0)));
}
```
`typeAsDeclared` re-stamps the Pure type and emits **no SQL** — and `tolerateRead` additionally marks
the column's `TypeFact` as *tolerated*, which suppresses the type-fact reconciliation that would
otherwise notice. That is the V26 row `i : Integer[1] -> BigDecimal(123.45)`: an `Integer`-declared
property over a `DECIMAL(10,2)` column delivering a non-integral value.

**Change — and this one needs a decision, not just a patch.** The code's justification is engine
parity ("SetImplTransformers passes numerics through untouched"), and it is honestly cited. But
engine parity here means *reproducing an engine bug*, and the repo's stated top priority is
soundness. Three options, in my order of preference:

1. **Refuse at model-compile time.** `Integer` declared over a `DECIMAL(p,s)` column with `s > 0` is
   a mapping the model author got wrong; there is no value the database can return that satisfies
   the declaration. Throw a `ModelException` in `MappingNormalizer.validatePmNames`' neighbourhood
   (`MappingNormalizer.java:2531-2549`, which already walls PM/property mismatches) naming the
   property, the column and both kinds. **This is the honest fix**: it moves the error from run time
   to model-compile time and costs nothing at execution.
2. **Convert in SQL** (`CAST(t0.DEC AS BIGINT)`), the same way the `Float`-over-`Decimal` arm above
   already does. Sound with respect to the declared type; silently lossy with respect to the data.
3. **Keep the rename, and rely on §3's egress check** to make it loud in tests. This is the
   status quo plus detection.

I recommend (1) for `Integer`-declared-over-fractional and (2) for the lossless widenings
(`Integer` column under a `Decimal`/`Number`/`Float` declaration), and *removing* the
`tolerateRead` suppression either way — a tolerance that hides a genuine mismatch is exactly the
"silent defaulting" the repo forbids.

**Blast radius.** `TYPE_AS_DECLARED` is emitted from two normalizer sites
(`MappingNormalizer.java:2384` for join-terminal columns, `:2455` for plain columns) and consumed at
`Typer.java:1265` and `Scalars.java:674`. Option (1) will fail models the corpus currently compiles —
enumerate them first with a dry run that logs instead of throwing, and register the survivors in a
ledger before flipping. This is the fix on the list most likely to need a staged landing.

**Regression test.**
```java
@Test
void anIntegerPropertyOverAFractionalDecimalColumnIsRejected() {
    var e = assertThrows(ModelException.class, () -> Compiler.compileModel(MM_MODEL));
    assertTrue(e.getMessage().contains("i")
            && e.getMessage().contains("Integer") && e.getMessage().contains("DECIMAL"));
}
```

---

### F10 — cross-kind `==` must be `false`, not a database coercion

**Site.** `builtin/Pure.java:1292` declares `equal(left:Any[*], right:Any[*]):Boolean[1]` — which is
**faithful to real legend-pure** and should not change. The defect is in the lowering: the two
operands' static types are known and disjoint, and the rule emits a bare SQL `=` anyway.
`lowering/Scalars.java:154-156`. The machinery to decide disjointness already exists twice over:
`CastPolicy.crossKindRaise` (`CastPolicy.java:181-206`) computes primitive-family disjointness, and
the equality rule *already* has this exact arm for dates (`Scalars.java:124-131`):
```java
// A Date is never equal to a NON-date kind — the string carrier must not
// leak into '2014'=='2014' being true (audit). Any stays dynamic.
if (!PlatformTypes.isAny(other)) {
    return new SqlExpr.BoolLit(false);
}
```
V11's measured outputs: `1 == '1' -> Boolean(true)`, `true == 1 -> Boolean(true)`,
`'a' == 1 -> java.sql.SQLException: Could not convert string 'a' to INT32`.

**Change.** Generalise the date arm to all concrete primitive families, before the operands are
lowered:
```java
Type lt = n.args().get(0).info().type(), rt = n.args().get(1).info().type();
if (CastPolicy.familyOf(lt) != null && CastPolicy.familyOf(rt) != null
        && CastPolicy.familyOf(lt) != CastPolicy.familyOf(rt)) {
    return new SqlExpr.BoolLit(false);        // Pure: values of different types are never equal
}
```
`familyOf` must be package-visible (it is `private static` today, `CastPolicy.java:208`).
**Do not** reuse `crossKindRaise` here: its TEXT↔NUMERIC/TEMPORAL "contract" carve-out is a *cast*
rule, and it is wrong for equality — `1 == '1'` must be `false`, not a conversion.

Optionally also reject it at Phase G. I would not: real Pure's `equal` really is `Any[*] × Any[*]`,
and a compile error would diverge from upstream. Returning `false` is both sound and upstream-faithful.

**Blast radius.** One rule in `Scalars`. SQL text changes only for comparisons the compiler can prove
disjoint — which today either return `true` wrongly or throw a raw JDBC error, so no correct golden
can depend on the old behaviour. `Type.Primitive.Family` is the existing lattice (`Type.java`), so no
new classification is introduced.

**Regression test.**
```java
@Test
void valuesOfDifferentKindsAreNeverEqual() {
    assertEquals(Boolean.FALSE, scalar("|1 == '1'"));
    assertEquals(Boolean.FALSE, scalar("|true == 1"));
    assertEquals(Boolean.FALSE, scalar("|'a' == 1"));       // was a raw SQLException
    assertEquals(Boolean.TRUE,  scalar("|1 == 1"));
}
```

---

### F11 — set operations must parenthesise branches carrying `ORDER BY`/`LIMIT`/`OFFSET`

**Site.** `sql/dialect/AnsiSqlRenderer.java:115-121`
```java
case SqlUnion u -> {
    String op = u.all() ? "UNION ALL" : "UNION";
    for (int i = 0; i < u.branches().size(); i++) {
        if (i > 0) { nl(sb, depth).append(op); nl(sb, depth); }
        query(sb, u.branches().get(i), depth);
    }
}
```
Each branch is written inline. A branch with `ORDER BY`/`LIMIT`/`OFFSET` produces SQL no database
parses. **234 of 431 `BAD_SQL` hits (54%)** in A20's primary campaign, from ordinary pipelines
(`sort→concatenate`, `limit→concatenate`).

**Change.**
```java
case SqlUnion u -> {
    String op = u.all() ? "UNION ALL" : "UNION";
    for (int i = 0; i < u.branches().size(); i++) {
        if (i > 0) { nl(sb, depth).append(op); nl(sb, depth); }
        SqlQuery b = u.branches().get(i);
        boolean wrap = b instanceof SqlSelect s
                && (!s.orderBy().isEmpty() || s.limit() != null || s.offset() != null);
        if (wrap) { sb.append('('); query(sb, b, depth + 1); sb.append(')'); }
        else       { query(sb, b, depth); }
    }
}
```
The parenthesised form is standard SQL and accepted by DuckDB, SQLite and H2 alike.

**Blast radius.** `SqlUnion` appears at 57 sites across `com.legend` — but only the renderer decides
text, and `AnsiSqlRenderer` is the single base every dialect derives from (`ArchitectureTest`'s
one-path invariant). Every golden containing a `UNION` after a sorted/limited branch changes — and
every one of those goldens is currently pinning SQL that **does not run**, so each is a bug fixed,
not a regression.

**Regression test.** This belongs in `SqlTextRatchetTest`'s neighbourhood as an *executability*
assertion, not a text assertion:
```java
@Test
void everySetOperationBranchWithAClauseIsParenthesised() throws Exception {
    String sql = Compiler.plan(MODEL,
        "fz::Widget.all()->project(~[c0:w|$w.name])->sort([ascending(~c0)])"
      + "->concatenate(fz::Widget.all()->project(~[c0:w|$w.name]))", "test::R").sql();
    try (var st = duck.createStatement()) { st.execute(sql); }   // must not throw
}
```

---

### F12 — `^Class()` must reject a missing required property

**Site.** `compiler/spec/NewChecker.java:66-131`. The checker iterates `ni.properties()` — the
**supplied** keys — and validates each one thoroughly (type via `kernel.unify`, multiplicity via full
subsumption at `:113-127`). Nothing iterates the class's *declared* properties, so an absent key is
never noticed (V27): `^model::Person()` type-checks and `$p.firstName` is typed `String[1]` and
delivers `null`.

**Change.** After the supplied-key loop, close the other direction:
```java
for (Property p : t.model().declaredPropertiesDeep(ni.className())) {
    if (p instanceof Property.Stored s
            && s.multiplicity() instanceof Multiplicity.Bounded b && b.lower() > 0
            && !properties.containsKey(p.name())) {
        throw new TypeInferenceException("class '" + ni.className()
            + "' requires property '" + p.name() + "' (" + b + ") in ^" + ni.className() + "(…)");
    }
}
```
Derived and qualified properties are excluded by the `Property.Stored` guard; association ends are
excluded because they are resolved at lookup time and never appear as declared stored properties
(`PureModelContext.findProperty`'s third leg).

**Blast radius — this is the real cost and it must be sized before landing.** `^Class(...)` is
constructed by the compiler itself: `MappingNormalizer.buildNewInstanceToOne` is called from
`:967`, `:1007`, `:1030` and from `materializeEmbedded`/`materializeInlineEmbedded`/
`materializeOtherwiseEmbedded`, and a relational class mapping legitimately maps only *some* of a
class's properties. A blanket check breaks every partially-mapped model.

The fix must therefore be **provenance-gated**: `NewInstance` needs a `userWritten` flag (or the
synthesized path needs to route through a distinct internal node), and the required-property check
fires only for user-written constructions. That is the honest design, it is what real Pure does
(its `NewValidator` runs on source `^`, not on internal instantiation), and it is why this fix sits
at #12 rather than #3 despite being a clean unsoundness.

**Regression test.**
```java
@Test
void constructionMustSupplyEveryRequiredProperty() {
    var e = assertThrows(TypeInferenceException.class, () -> Compiler.compileQuery(
        "Class model::Person { firstName: String[1]; age: Integer[1]; }",
        "{| let p = ^model::Person(); $p.firstName; }"));
    assertTrue(e.getMessage().contains("firstName"));
}

@Test
void aPartiallyMappedClassStillCompiles() {          // the guard on the guard
    assertDoesNotThrow(() -> Compiler.plan(PARTIAL_MAPPING_MODEL, Q, "test::R"));
}
```

---

### F13 — `extends …type::Nil` must be rejected; the bottom arm must leave the recursion

**Site.** `compiler/element/ModelContext.java:232-256`
```java
private boolean isSubtype(String childFqn, String parentFqn, Set<String> visited) {
    if (childFqn.equals(parentFqn)) return true;
    // Nil is the BOTTOM type — a subtype of every type ...
    if (childFqn.equals(PlatformTypes.NIL)) return true;      // <-- fires INSIDE the recursion
    if (!visited.add(childFqn)) return false;
    Optional<TypedClass> child = findClass(childFqn);
    if (child.isEmpty()) return false;
    for (String superFqn : child.get().superClassFqns()) {
        if (isSubtype(superFqn, parentFqn, visited)) return true;   // <-- walks INTO the Nil arm
    }
    return false;
}
```
`Class model::Evil extends meta::pure::metamodel::type::Nil` therefore makes `Evil` a subtype of
everything — including `no::such::Type`, a type that does not exist (V10).

**Change.** Two independent guards; land both.

*(a) Reject the declaration.* Real Pure's `Nil` is uninhabited and not extensible. In
`compiler/element/ClassCompiler` (or `ModelIntegrity`, which already owns cross-element wellformedness):
```java
if (superClassFqns.contains(PlatformTypes.NIL)) {
    throw new ModelException(Phase.MODEL,
        "'" + fqn + "' cannot extend " + PlatformTypes.NIL + " — Nil is the uninhabited bottom type");
}
```

*(b) Hoist the bottom arm out of the recursion,* so that even if a `Nil` supertype arrives some other
way (protocol JSON, a synthesized element) it cannot generalise. The public entry keeps the arm; the
private walk drops it:
```java
default boolean isSubtype(String childFqn, String parentFqn) {
    if (childFqn.equals(PlatformTypes.NIL)) {
        return findClass(parentFqn).isPresent()
            || PlatformTypes.isKnownType(parentFqn);   // and the RHS must be a real type
    }
    return isSubtype(childFqn, parentFqn, new HashSet<>());
}
```
The `parentFqn` existence check is the second half of V10 and is worth having on its own: today
`isSubtype(anything, "no::such::Type")` can answer `true`.

**Blast radius.** `isSubtype` is the subtyping primitive — `InferenceKernel.unify`'s `ClassType` arm
(`InferenceKernel.java:107-112`) and overload scoring both call it, and the javadoc notes those two
halves must agree. Change (b) makes `isSubtype` *stricter* only for `Nil`-rooted chains, which A03's
exhaustive 42,875-triple check shows are not exercised by any legitimate model. Change (a) is a new
model-compile error; grep the corpus for `extends .*Nil` before landing (expected: zero hits).

**Regression test.**
```java
@Test
void nilCannotBeExtendedAndDoesNotGeneralise() {
    assertThrows(ModelException.class, () -> Compiler.compileModel(
        "Class model::Evil extends meta::pure::metamodel::type::Nil { z: String[1]; }"));
    var ctx = Compiler.compileModel(PLAIN_MODEL);
    assertFalse(ctx.isSubtype("meta::pure::metamodel::type::Nil", "no::such::Type"));
}
```

---

### F14 — `plan()` must render the runtime's declared dialect

**Site.** `Compiler.java:539-548`
```java
switch (e.getValue()) {
    case DuckDB, SQLite -> distinct.add(e.getValue().name());
    // H2 rides the ANSI-flavored DuckDB renderer: the corpus
    // executes H2-typed connections on the session's DuckDB, and
    // every emission H2 sees is the ANSI subset.
    case H2 -> distinct.add("DuckDB");
```
versus the connection-aware overload at `Compiler.java:474-508`, which reads
`connection.getMetaData().getDatabaseProductName()` and returns a real H2 dialect. V28: for a runtime
declared `type: H2`, `Compiler.plan(...).sql()` emits `starts_with(t0.NAME, 'a')`, which the H2
session it was planned for rejects with `Function "STARTS_WITH" not found`, while
`Compiler.execute(...)` on the same model/query/runtime renders correct H2 and returns `true`.

**Change.** The declared type must select the declared dialect:
```java
case H2 -> distinct.add("H2");
...
return switch (theOne) {
    case "H2"     -> new com.legend.sql.dialect.H2Modern();
    case "SQLite" -> new AnsiSqlRenderer(Lexicon.SQLITE, TypeNames.ANSI, Spellings.DUCKDB);
    default       -> new com.legend.sql.dialect.DuckDb();
};
```
The comment's premise — "the corpus executes H2-typed connections on the session's DuckDB" — is a
statement about the *corpus harness*, not about `plan()`. The harness already passes a real
`Connection` to `execute()`, so the connection-aware overload keeps serving it correctly. What breaks
is the corpus's **plan-text goldens** for H2-declared runtimes, and the honest reading is that those
goldens currently pin SQL that the declared database cannot run.

If the corpus contract must be preserved, the alternative is to make the choice explicit rather than
implicit: add `Compiler.plan(model, query, runtimeFqn, DatabaseType target)` and have the corpus pass
`DuckDB` deliberately, leaving the 3-arg overload honest.

**Blast radius.** `dialectOf(ctx, runtimeFqn)` (the 2-arg overload) is the plan-only path; grep its
callers before landing. All H2-declared plan-text goldens move. `H2Modern` vs `H2` selection at
`Compiler.java:504-508` is version-driven and only available with a live connection — for the
plan-only path, pick `H2Modern` and document that plan text targets the modern profile.

**Regression test.** A parity test, because this is a two-lane disagreement:
```java
@Test
void planEmitsSqlTheDeclaredRuntimeCanRun() throws Exception {
    String sql = Compiler.plan(H2_MODEL, Q, "test::R").sql();
    try (var c = DriverManager.getConnection("jdbc:h2:mem:parity"); var st = c.createStatement()) {
        st.execute(DDL);
        st.execute(sql);                 // was: Function "STARTS_WITH" not found
    }
}
```

---

### F15 — a user definition must not shadow a registered native; delete the invented `first(set,count)`

**Two defects, one owner.**

**Site (a) — redefinition.** `compiler/element/FunctionCompiler.functionsAt` merges native and user
overloads, and the platform-owned gate covers only ~15 FQNs:
```java
if (!PlatformTypes.isPlatformOwnedFunction(fqn)) {
    addModelOverloads(all, model, fqn);
} else if (!model.findFunction(fqn).isEmpty() && SUPPRESSED_ONCE.add(fqn)) {
    System.err.println("[legend-lite] platform-owned function '" + fqn + "': ...");
}
```
`PlatformTypes.isPlatformOwnedFunction` (`PlatformTypes.java`) lists only the DDL/plan/execute
natives. Everything else — including `meta::pure::functions::collection::first` — accepts a user
override, and V23 shows the user's overload winning outright, taking the *type* with it:
`|[1,2,3]->first()` becomes `String[1]` and returns `'HIJACKED'`.

**Change (a).** Any FQN the native catalog registers is platform-owned. The existing PCT carve-out in
`addModelOverloads` (`<<PCT.function>>` redefinitions already lose to the native) is exactly the
right precedent — generalise it:
```java
boolean nativeOwned = !Pure.nativeFunctionsAt(fqn).isEmpty();
if (nativeOwned) {
    for (Function def : model.findFunction(fqn)) {
        if (arityCollides(def, Pure.nativeFunctionsAt(fqn))) {
            throw new ModelException(Phase.MODEL, "'" + fqn + "/" + def.parameters().size()
                + "' redefines a platform native; platform functions cannot be overridden");
        }
    }
}
```
A throw is better than the stderr-once suppression here: silent suppression is itself a "silent
defaulting", and a model author who redefines `first` wants to know. Keep the `CORE_FUNCTION_PACKAGES`
bare-name courtesy for FQNs the catalog does **not** register — that path is what lets the corpus call
`uniqueValueOnly(...)` bare and it is unaffected.

**Site (b) — the invented overload.** `builtin/Pure.java:1344-1345`
```java
FIRST__T_MANY            = ... first<T>(set:T[*]):T[0..1];
FIRST__T_MANY__INTEGER_1 = ... first<T>(set:T[*], count:Integer[1]):T[*];   // <-- no such function upstream
```
V35 verified against `finos/legend-pure`
(`platform/pure/grammar/functions/collection/slice/first.pure:24`): the 1-arg form is the **only**
declaration. And `lowering/Scalars.java:1407-1411` shows why the invention is worse than useless:
```java
for (String f : Pure.nativeKeysAt("first")) {
    RULES.put(f, (n, args) -> isToOne(n.args().get(0)) ? args.get(0)
            : new SqlExpr.Call(SqlFn.LIST_GET, List.of(args.get(0), new SqlExpr.IntLit(1))));
}
```
The rule is registered by **name**, so both arities land on it and `args.get(1)` — the count — is
never read. `[1,2,3]->first(2)` returns one element (V25); the correct sibling `take(2)` emits
`array_slice(…,1,2)`.

**Change (b).** Delete `FIRST__T_MANY__INTEGER_1` and its `native-catalog.txt` line. If a corpus row
needs the 2-arg spelling, alias it to `take` in the normalizer rather than keeping an invented native
with a wrong lowering. While there: `nativeKeysAt(name)` registration is arity-blind by design, so
add an assertion in `Scalars`' static initialiser that a rule registered for a bare name covers every
registered arity — that is the mechanism that let this slip.

Also from V35, same class, same file: `limit` carries both `size:Integer[1]` and `size:Integer[0..1]`
overloads (`Pure.java:1824,1828`); the `[0..1]` form lets `limit([])` compile and the `LIMIT` clause
vanish. Delete it.

**Blast radius.** (a) is a new model-compile error — enumerate the corpus's redefinitions first
(`grep -rn "^function meta::pure::" scripts/corpus pct --include=*.pure`) and expect PCT sources to
appear; those already have the `<<PCT.function>>` marker and are handled. (b) is a catalog deletion:
`NativeFunctionTest.catalogMatchesTheGoldenFile` will fail until the golden is regenerated — which is
V8's point, and §4 proposes replacing that self-golden entirely.

**Regression test.**
```java
@Test
void aUserModelCannotRedefineAPlatformNative() {
    assertThrows(ModelException.class, () -> Compiler.compileModel(
        "function meta::pure::functions::collection::first(c: Integer[*]): String[1] { 'HIJACKED' }"));
}

@Test
void thereIsExactlyOneFirstOverload() {
    assertEquals(1, Pure.nativeFunctionsAt("meta::pure::functions::collection::first").size());
}
```

---

### F16 — `cast(@Any)->cast(@T)` must not be a bare passthrough

**Site.** `lowering/CastPolicy.java:55-98`. For a source of type `Any` (a `ClassType`, not a
primitive) the converting arm cannot fire, because `isSqlPrimitive(src)` is false:
```java
Type src = c.source().info().type();
if (isSqlPrimitive(c.target()) && isSqlPrimitive(src)
        && !isWidening(src, c.target())
        && !PureSql.type(src).equals(PureSql.type(c.target()))) {
    ...
}
return value;                     // <-- Any -> Integer lands here: bare passthrough
```
and `crossKindRaise` returns `null` for `Any` (`familyOf` is null for a `ClassType`,
`CastPolicy.java:208-213`). So V2's `$r.name->cast(@Any)->cast(@Integer)` renders `SELECT t0.NAME AS x`
under a declared `Integer[1]`, delivering a `java.lang.String`.

**Change.** A downcast from `Any` to a concrete primitive is Pure's *checked* downcast, and in
relational land there is no runtime type tag to check against — but there **is** a static one: the
lowered expression carries a `SqlType` in its `TypeFact`. Refuse when they disagree:
```java
if (PlatformTypes.isAny(src) && isSqlPrimitive(c.target())
        && value.type() instanceof TypeFact.Typed vt
        && !vt.type().equals(PureSql.type(c.target()))) {
    return SqlExpr.Call.of(SqlFn.ERROR, new SqlExpr.StringLit(
        "Cast exception: " + vt.type() + " cannot be cast to " + c.target().typeName()));
}
```
This reuses the exact raise `crossKindRaise` already emits for the primitive-to-primitive case, so
the diagnostic wording stays single-owner. Where the `TypeFact` is unknown, the egress check of §3 is
the backstop.

**The V3 half — `cast` converting rather than asserting — I am NOT proposing be fixed as filed.**
See §5.

**Blast radius.** `CastPolicy.lower` is reached from `Lowerer.java:1100` and `:1249`. The new arm
fires only where the source is statically `Any` *and* the SQL type is known *and* it disagrees —
i.e. exactly the currently-broken cases. `to(@T)`/`toMany(@T)` share the node and are unaffected
(their targets are not primitives in the `Any`-source shape).

**Regression test.**
```java
@Test
void castThroughAnyStillChecks() {
    var ex = assertThrows(SQLException.class, () ->
        exec("model::Item.all()->project(~[x:r|$r.name->cast(@Any)->cast(@Integer)])"));
    assertTrue(ex.getMessage().contains("cannot be cast to"));
}
```

---

### Cheap extras (each < 20 lines, worth batching into one commit)

| Finding | Site | Change |
|---|---|---|
| V15 `project(~[])` types 0 columns, emits `SELECT *`, ICEs | `compiler/spec/ProjectChecker` | Reject `~[]` the way `SelectChecker.java:24` and `DistinctChecker.java:43` already do — `"project(~[]) names no columns"`. Both siblings guard it; `project` is the odd one out. |
| V31 malformed time literal escapes as `IllegalStateException` | the `%hh:mm:ss` literal path | Throw a `ParseException` with position, exactly as the parallel `%2020-01-01T25:00:00` date form already does. The discipline exists; the time path does not use it. |
| V22 `BOOLEAN` rejected as a column datatype | `RelationalDataType.fromName` / the `###Relational` grammar | `BOOLEAN` → `RelationalDataType.Bit()`. A Pure `Boolean[1]` property otherwise has no natural physical column type on any backend. |
| A20 unbounded `<T>` on `minus`/`plus`/`times`/`abs` | `builtin/Pure.java` | `Type.TypeVar` cannot carry a bound (`record TypeVar(String name)`), so `-aString` type-checks as `String[1]` — 169 of 431 `BAD_SQL` hits. Minimal fix without adding bounded type variables: replace the `<T>` collection folds with concrete `Number`/`Integer`/`Float`/`Decimal` overloads, matching the binary forms, which are already correctly rejected. |
| A20 `StackOverflowError` from 6 frames | parser, name resolver, typer, resolver, lowerer | One shared `RecursionGuard` (an `int depth` field + a `LegendCompileException` at, say, 500) threaded through the five recursive entry points named in A20's table. A `java.lang.Error` escaping is invisible to any caller that catches `RuntimeException`. |
| A20 `sort(...)->drop(0)` emits `OFFSET 0` and crashes DuckDB | `lowering` limit/offset | Elide `OFFSET 0`. One condition; it invalidates the whole connection today. |

---

## 3. THE ONE STRUCTURAL CHANGE — an egress conformance check

**Proposal.** Validate every returned cell against its declared column type *and* declared
multiplicity at the point where `ExecutionResult` is constructed. Fail in tests; be configurable in
production.

### Why this one

Every fix in §2 repairs one rule. This repairs the *absence of a feedback loop*. The narrative's
measurement is the argument: 4,171 tests, a real corpus scoreboard, ~300 openly ledgered rows — and
**a 10.1% unsoundness rate**, because the suite's assertions are aimed at SQL text and row equality
and essentially nothing asserts that a returned value satisfies the Pure type the compiler promised.
Adding checks to individual rules does not close that gap; the next rule will reopen it.

The strongest evidence that this is the right check is that **it has already been written and
mutation-tested**. A20's oracle (`/home/user/audit/fuzz/Fuzz.java`) is exactly this: a
declared-Pure-type → admissible-Java-carrier table derived from the repo's *own* decode contract
(`Executor.unwrap:641-679` "the wire's temporal type is `PureDateLiteral`, FULL STOP";
`PureAsserts.kindOf:203-207`; `PureSql.java:129` making `String` the enum carrier), plus twelve
checks, every one of which `OracleSelfTest` proves fires on a deliberately violating input. It is not
speculative machinery — it is a component that exists, works, and lives in the wrong repository.

### Where it lives

New class `com.legend.exec.ResultConformance` (~150 lines), called from the four
`ExecutionResult` construction sites in `Executor`:

| call site | today |
|---|---|
| `Executor.java:324` | `yield new ExecutionResult.Scalar(v, rootType.type());` |
| `Executor.java:392` | `yield new ExecutionResult.Collection(values, rootType.type());` |
| `Executor.java:705` | `return new ExecutionResult.Tabular(columns, rows, rootType.type());` |
| `Executor.java:394` | `case GRAPH -> new ExecutionResult.Graph(...)` |

plus `shapeRow` (`Executor.java:777+`) so the streaming path is covered by the same code rather than a
parallel copy. The shape is already right for this: `ExecutionResult` is a sealed quartet and every
variant exposes `columns()` and `rows()`, and `exec/Column.java` already carries
`(String name, Type pureType, @Nullable Multiplicity multiplicity)`.

`Executor` **already hosts two checks of exactly this kind**, which is the best possible sign that
this is the natural home and not a new layer:
```java
// Executor.java:769-770
throw new IllegalStateException("result has " + n + " columns but the typed"
        + " schema has " + schema.columns().size() + " — plan/schema mismatch");

// Executor.java:793-800
throw new IllegalStateException("a many-valued cell reached a scalar TDS slot ('"
        + columns.get(i - 1).name() + "') — the lowering must explode scalar streams in SQL (E2)");
```
`ResultConformance` generalises these two into a table-driven, complete set.

### What it asserts

Per result: `COL_COUNT`, `COL_ORDER`, `COL_TYPE_DECL`, `SHAPE_DIVERGE`, `ROW_ARITY`, `ROWCOUNT_MULT`.
Per cell: `JAVA_CLASS` (carrier admissible for the declared Pure type), `NULL_IN_ONE` (a null under a
lower-bound ≥ 1 column), `DECIMAL_SCALE` / `DECIMAL_PRECISION` (against a declared
`Type.PrecisionDecimal` — this is the check F4 makes *possible*, since today a declared
`Decimal(18,4)` arrives as `Decimal<>` and there is nothing to check against), `INT_RANGE`.

```java
public final class ResultConformance {
    public enum Mode { OFF, WARN, THROW }
    private static final Mode MODE = Mode.valueOf(
        System.getProperty("legend.lite.conformance", "OFF"));   // tests set THROW

    public static <R extends ExecutionResult> R check(R r) {
        if (MODE == Mode.OFF) return r;
        List<String> v = new ArrayList<>();
        for (int c = 0; c < r.columns().size(); c++) {
            Column col = r.columns().get(c);
            for (int i = 0; i < r.rows().size(); i++) {
                Object cell = r.rows().get(i).cells().get(c);
                if (cell == null) { requireOptional(col, i, v); continue; }
                requireAdmissibleCarrier(col, cell, i, v);       // JAVA_CLASS
                requireDecimalFits(col, cell, i, v);             // DECIMAL_SCALE/PRECISION
                requireIntRange(col, cell, i, v);                // INT_RANGE
            }
        }
        if (!v.isEmpty() && MODE == Mode.THROW) {
            throw new ConformanceException(String.join("\n", v));
        }
        if (!v.isEmpty()) { LOG.warn(...); }
        return r;
    }
}
```
The admissibility table is not a matter of taste — it is transcribed from the three sites named
above, so the check and the decoder cannot drift.

`ConformanceException` should be a `LegendCompileException` subtype in the *internal-invariant*
bucket (`error/LegendCompileException.java:16-18`), because a conformance failure is by definition a
compiler bug, not a user error.

### What fraction of the 1,010 it would have caught

A20's primary campaign (12,800 generated / 10,462 compiled / 10,030 executed) reports:

| oracle signature | n | caught by an egress check? |
|---|---:|---|
| `UNSOUND :: NULL_IN_ONE` | 960 | **yes** — a null cell under a lower-bound ≥ 1 column |
| `UNSOUND :: JAVA_CLASS` | 48 | **yes** — carrier vs declared Pure type |
| `UNSOUND :: DECIMAL_SCALE` | 2 | **yes** — once F4 makes the declared `(p,s)` reach the column |
| **total UNSOUND** | **1,010** | **1,010 (100%)** |

100% is not a boast; it is a tautology with a useful shape. A20's `UNSOUND` bucket is *defined* by
this oracle, so promoting the oracle catches all of it **by construction** — and that is precisely
the point being made: the check is not hypothetical, its recall is measured, and its precision was
established by mutation-testing every arm.

Against the whole generated corpus the honest figure is **1,010 / 12,800 generated (7.9%)** and
**1,010 / 1,610 total failures (63%)**.

### What it would NOT catch — stated plainly

* **The 431 `BAD_SQL` hits (3.4% of generated).** The database rejects the SQL, so no result is ever
  constructed and the check never runs. These need typing on the *SQL* side (F11 alone is 234 of
  them, the unbounded-`<T>` fold is another 169).
* **The 168 `NOT_IMPL` and 1 `ICE`.** Same reason.
* **Wrong-but-well-typed values.** This is the important limitation and it is a big class:
  * **V24** (variable capture) — `Integer(7)` under `Integer[1]`. Perfectly conformant, wrong answer.
  * **V29** (`match` dispatching on the static type) — `Integer(20)` under `Integer[1]`. Conformant.
  * **V3** (`cast` rounding `2.7` to `3`) — `Long(3)` under `Integer[1]`. Conformant.
  * **V25** (`first(set,2)` returning one element) — the declared type is `T[*]`, and one element
    satisfies `[*]`. Conformant.
  * **V23** (native hijack) — the *type* follows the hijack, so `'HIJACKED'` sits under `String[1]`.
    Conformant.
  * **V20's row-lane arm**, where `toOne()` over a many-valued row-lane read delivers *a* value: the
    cell is non-null and well-typed, and only a cardinality count would notice.
* **Anything that never executes**: V28 (`plan()` dialect divergence), V8/V35 (catalog divergence),
  V13 (dead code), V22 (parse), V34 (element collision), V10 (`isSubtype` collapse, which surfaces
  only as a downstream type error), V33 pre-F4 (there is no declared `(p,s)` to check against).
* **Column *name* violations** are caught only partially: V16's `b : (a:String[1])[1]` delivered as
  `b_a : String[1]` trips `COL_ORDER`/`COL_COUNT`, but a same-arity rename would not.

So: this check converts a large class of **silent** unsoundness into **loud** failure. It does not
make the compiler correct, and it cannot see a wrong value wearing the right type. It is a
regression net, and its value is that it makes every fix in §2 *stay* fixed.

### Landing it

1. Land `ResultConformance` with `MODE=OFF` by default and `THROW` in the test JVM
   (`-Dlegend.lite.conformance=THROW` in the surefire config).
2. Run the full suite. Every failure is either a real defect (fix it, or file it) or an oracle
   inaccuracy (fix the table — the table is a claim about the decode contract and must be right).
3. Register the residue in an `EgressConformanceLedgerTest` (§4 G1), shrink-only.
4. Port `Fuzz.java` into `core/src/test` as a seeded, bounded, deterministic property test
   (a few hundred queries at a fixed seed, not 12,800) that asserts zero unregistered violations.

---

## 4. THE MINIMUM SET OF GUARD TESTS

The repo already has the right instincts — `SqlTextRatchetTest`, `JavaEvalLedgerTest`,
`TenetRatchetTest`, `CarrierPurityRatchetTest`, `RawSqlLedgerTest`, `ArchitectureTest`,
`PctDisciplineTest`. Almost every guard below is an **extension** of one of them, not new machinery.
That matters: a new guard mechanism nobody recognises is a guard nobody maintains.

### G1 — `EgressConformanceLedgerTest` *(new file, existing pattern)*
**Pattern:** `JavaEvalLedgerTest` / `TenetRatchetTest` — a registered, per-key, shrink-only count with
a written justification per row.
**Content:** query-family → known conformance-violation count, seeded from A20's taxonomy. Growth
fails the build; shrink requires lowering the pin.
**Why new:** this is the only genuinely new *category* of guard proposed, and it is the one the
structural change needs to be maintainable.

### G2 — `ArchitectureTest`, three new ArchUnit rules *(extend)*
```java
// (a) The engine-text elision has exactly one owner and one gate.
noClasses().that().resideOutsideOfPackage("com.legend.lowering..")
    .should().callMethod(CastPolicy.class, "cellRootUnwrapWire", TypedSpec.class)

// (b) Every ExecutionResult is constructed inside exec/ — so ResultConformance
//     cannot be bypassed by a new egress path.
noClasses().that().resideOutsideOfPackage("com.legend.exec..")
    .should().callConstructor(ExecutionResult.Tabular.class, ...)

// (c) Multiplicity arithmetic has one owner (pins F7's single-caller property).
noClasses().that().resideOutsideOfPackage("com.legend.compiler..")
    .should().callMethod(Multiplicity.class, "product", ...)
```
(b) is the structural guard that keeps §3 from rotting: today a fifth construction site could be added
and nobody would notice.

### G3 — `SqlTextRatchetTest`, one new register *(extend)*
That test already walks production sources for SQL-shaped string literals with a per-file pin. Add a
second register in the same file for **declared-`[1]` natives whose SQL lowering can return NULL** —
the F2 family. Seed it with today's set (`sum`, `joinStrings`, `average`, `stdDevSample`, `variance`,
`percentile`, `mod`), argue each row, and shrink to zero as F2 lands. A new entry means someone
registered an aggregate without deciding its empty-input value — which is exactly how V19 happened.

### G4 — `TypeSpellingParityTest`, precise-primitive round trip *(extend)*
That test already pins the two `RelationalDataType` spelling tables against each other. Add the
*Pure-side* leg: for every `RelationalDataType` in its `shared` list, assert that the Pure type a
declared property of the corresponding spelling classifies to round-trips —
`Decimal(18,6)` → `Type.PrecisionDecimal(18,6)` → `RelationalDataType.Decimal(18,6)`. This is the
guard that would have caught V33, and it belongs in the file that already owns type spellings.

### G5 — `PlanExecuteDialectParityTest` *(new, ~30 lines)*
For every runtime in the test fixtures: `Compiler.plan(model, q, rt).sql()` must be accepted by the
same session `Compiler.execute(model, q, rt, conn)` uses. Asserts *executability*, not text — so it
survives dialect work. This is V28's guard and there is no existing test that could host it, because
no existing test crosses the plan/execute seam.

### G6 — `NativeFunctionTest.catalogMatchesTheGoldenFile` must stop being a self-golden *(replace)*
This is V8, and V35 shows what it costs: 183 of 721 signatures (25.4%) diverge from real FINOS
Legend, and the current guard **cannot see any of it** — it renders `Pure.all()` and compares it to a
file generated from `Pure.all()`, and its renderer additionally drops generic multiplicity arguments
and relation-column multiplicities.
**Replacement:** vendor a checked-in extract of the real `.pure` `native function` declarations
(V35's methodology: 24,172 extracted declarations from `finos/legend-pure` + `finos/legend-engine` at
a pinned SHA) into `core/src/test/resources/upstream-natives.txt`, and assert per-signature
equality with a registered, shrink-only divergence ledger for the 183. That converts an unverifiable
claim into a measured, shrinking number — and it is the guard that would have prevented the invented
`first(set,count)` (F15b) and the inconsistent `percentile` overloads (F2b).

### G7 — `MultiplicityAlgebraTest` *(new, ~25 lines)*
The exhaustive property test in F7, plus A02's already-run laws (union commutative/associative/
idempotent and a true convex hull; product associative with `[1]` as identity; both monotone) over a
bound set that **includes** `46341`, `65536`, `1073741824`, `Integer.MAX_VALUE`. A02's sweep found
the algebra correct over 400 pairs and 8,000 triples — it just never used a bound large enough to
overflow. Pin the large bounds.

### G8 — `PctDisciplineTest`-style source guard for cast fall-throughs *(extend the pattern)*
`PctDisciplineTest` pins "zero sort/dedupe/tolerance spellings in this module; stays zero" by regex
over sources. The same shape applies to `CastPolicy`: register the exact set of cast shapes that
lower to a bare `return value;` identity, with a justification each. A new identity arm — which is
how V2 exists — has to be argued. This is cheaper and more durable than trying to prove cast
completeness by types.

### What I am deliberately **not** proposing
A general "assert the Phase-G type equals the executed type" test. V9 already established the three
compile seams agree on root type across ten queries, and A20 found only *one* `PLAN_EXEC_TYPE_DIVERGE`
family. The seams are not where the problem is; the values are. Guard the values.

---

## 5. DO NOT FIX — or do not fix as filed

An honest deliverable has to include the items where the cure is worse than the disease, or where the
finding as written does not survive reading the code. Six of those.

### 5.1 V24 (let-inliner capture) — **the finding does not survive reading the code**

CONFIRMED.md files this as a beta-reduction capture bug: "Two alpha-equivalent programs differing
only in the inner lambda's binder NAME" give 10 and 7.

They are not alpha-equivalent. In
```
|[10]->map({x| let y = $x; [7]->map(y|$y)->toOne();})
```
the inner lambda binds `y`, and `$y` in its body refers to *that* binder under ordinary lexical
scoping. `7` is the lexically correct answer. Renaming the first program's binder `z` → `y` is not an
alpha-rename — it captures a free occurrence, which is exactly what alpha-renaming is defined to
avoid. Legend-lite is not producing a wrong answer here; it is producing the right answer for a
program the author probably did not mean.

I also tested the direction where a capture *would* be a genuine bug — a query-level `let` colliding
with a lambda binder — and legend-lite handles it correctly:
```
$ probe: |let y = 3; [7]->map(y|$y)->toOne();
[PLAN] SELECT list_extract(list_transform([7], _i0 -> _i0), 1) AS value
[EXEC-ROW] Integer(7)
```
`UserCallInliner.lambda` (`compiler/spec/UserCallInliner.java:529-585`) α-renames the binder to
`_i0`, and `SourceSubst.substitute`'s `LambdaFunction` arm
(`compiler/spec/SourceSubst.java:102-127`) correctly removes shadowed names from the substitution
environment. The hygiene machinery is present and working.

**What is actually wrong** is smaller and different: legend-lite *accepts* the shadowing declaration.
Real Pure rejects redefining an in-scope variable. So the fix is a **shadowing rejection** in
`LetChecker`/`Typer`'s lambda-parameter binding — "variable 'y' is already defined in this scope" —
not an alpha-renaming fix. That is ~10 lines and removes the whole ambiguity class. Rank it low: the
harm is confusion, not a wrong answer.

### 5.2 V3 (`cast` converts rather than asserts) — **do not fix as filed**

`CastPolicy.java:70-76` documents this as a deliberate, adjudicated divergence:
> DELIBERATE divergence: pure's cast never converts; the corpus contract (engine-lite lineage) is
> SQL-style conversion, so a NARROWING cast converts here.

Making `cast` a pure assertion would break the corpus contract wholesale, and the corpus is the
repo's primary correctness signal. The right move is to **split the operator**, not to flip it:
`castAsDeclared`/`convert` keeps the SQL-conversion semantics for the mapping and corpus lanes (it
already exists — `Pure.Lite.CAST_AS_DECLARED`), and user-written `cast(@T)` becomes the assertion.
That is a real project, not a fix, and it should be scheduled as one. Meanwhile land F16 (the `Any`
half, which is unambiguously a hole, not a contract) and add the missing diagnostic: today
`|'42abc'->cast(@Integer)` escapes as a raw `java.sql.SQLException: Conversion Error`, which should
be a `LegendCompileException`-family error whatever the semantics end up being.

### 5.3 V29 (`match` dispatches on the static type) — **fix differently**

Real Pure's `match` dispatches on the *runtime* type. A relational cell has no runtime type tag, so
runtime dispatch cannot be implemented in SQL without materialising a discriminator column — a large,
invasive change with a permanent cost on every query. Do not build it.

Instead, **reject what cannot be dispatched**: `MatchChecker` should refuse a `match` whose arms are
not statically discriminable given the input's static type — i.e. when more than one arm statically
conforms. `|'x'->cast(@Any)->match([s:String[1]|10, a:Any[1]|20])` becomes a clean compile error
naming both arms, instead of silently selecting the wider one. Same soundness outcome, a fraction of
the cost, and it matches `TypedMatchRuntime`'s own stated purpose.

### 5.4 V13 (`PrecisionDecimal` arithmetic is dead code) — **do not delete, and do not chase parity**

The temptation is to delete ~200 lines of unreferenced precision algebra. Don't: A20's exhaustive
check found it **matches a from-scratch Spark `DecimalPrecision` reference over all 608,400 ordered
`(p,s)×(p,s)` pairs for every operator, zero mismatches**. It is correct code with no callers, and F4
gives it callers. Wire `plus`/`minus`/`times`/`dividedBy` into the arithmetic result typing (which is
V32's fix) and the dead code becomes the fix.

Equally: do not chase full MS-SQL/Hive/Spark precision parity. Use the four operators for result
typing and stop there.

### 5.5 V21 (SQLite corrupts Decimal) — **do not fix in the decoder**

`DECIMAL(38,9)` holding `12345678901234567.123456789` comes back as
`BigDecimal(12345678901234567.123456789)` on DuckDB and `Long(12345678901234568)` on SQLite. The
instinct is a decoder special-case. Resist it: SQLite genuinely has no decimal type — its storage
classes are INTEGER, REAL, TEXT, BLOB, NULL — so any decoder repair would be reconstructing precision
the database never stored.

Two honest options, both better than a decoder patch: **refuse** a `Decimal`-typed column on a SQLite
runtime at plan time with a clear message, or **route decimals through TEXT** on that dialect
(`Lexicon.SQLITE` already exists as the extension point, `Compiler.java:559-566`). Pick one and
document it. The current state — same static type, silently different values per backend — is the
only unacceptable option.

### 5.6 V34 (quoted FQN segments unquote into the path) — **guard, don't rework**

`Class test::'A::B'` and `Class test::A::B` collide into one element. When both are present the
duplicate detector catches it (benign); the risk is the one-sided case. Reworking quoted-identifier
handling through the name resolver is a wide change for a narrow, hypothetical harm. Cheaper and
sufficient: **reject a quoted FQN segment containing `::`** at parse time. Two lines, closes the hole,
touches nothing else.

### 5.7 Also not worth it right now

* **V7 / V4 (Integer and Decimal carrier switching by literal magnitude/scale).** Real, and genuinely
  untidy — one declared Pure type with three Java carriers (V19 shows `Integer` arriving as `Long`,
  `BigInteger` and `null`). But the repo's own decode contract *already* declares
  `Byte|Short|Integer|Long|BigInteger` all admissible for Pure `Integer`
  (`PureAsserts.kindOf:203-207`), so this is a *stability* complaint, not an unsoundness one. Fixing
  it means picking one carrier per Pure type and normalising at `Executor.unwrap` — worth doing, but
  after §3 exists, because §3 is what will tell you which call sites actually depend on the current
  spread.
* **V30's 87 "no scalar lowering registered" natives.** Do not write 87 lowering rules. Write the
  *cross-check*: a test that walks every registered native, type-checks a synthetic call at Phase G,
  and asserts that a lowering rule exists — turning 112 latent internal exceptions into one
  enumerated, shrink-only ledger. That is one test versus 87 features, and it makes the gap visible
  and shrinking instead of invisible and fixed.
* **V16 (a ROW-typed extend column).** The declared type `b : (a:String[1])[1]` and the delivered
  `b_a : String[1]` disagree, but nested row columns are a corner of the relation API with a real
  design question behind them (does legend-lite support nested relation columns at all?). Answer the
  design question first; §3 will keep it loud in the meantime.

---

## 6. SUGGESTED SEQUENCING

**Wave 1 — one commit, no semantic risk, immediate payoff.**
F7 (`product` overflow), F8 (`==` guard), F11 (union parens), F3 (wire-cast elision gate),
plus the cheap extras. Four one-to-five-line changes; F11 alone removes 54% of the `BAD_SQL` class
and F3 removes the mapping root cause.

**Wave 2 — the structural change.**
§3's `ResultConformance` at `MODE=OFF`, `THROW` in tests, plus G1's ledger seeded from whatever the
suite reports. Land this *before* wave 3, so wave 3's fixes are measured rather than asserted.

**Wave 3 — the multiplicity truth.**
F1 (join), F2 (aggregates), F5 (`toOne`). Together these are the 960-hit `NULL_IN_ONE` family. Do
them as a group: they share golden re-blesses and they share the ledger rows they retire.

**Wave 4 — the type-fidelity group.**
F4 (`Decimal(p,s)`), then V32's arithmetic wiring (which F4 unblocks), then F9 (the mapping numeric
decision), F10, F13, F14, F15, F16.

**Wave 5 — the staged ones.**
F6 (rigid return check) and F12 (`^Class()` required properties), each behind a ledger until its
register reaches zero. These are the two with unknown corpus exposure; landing them last means
landing them with §3 already measuring the result.
