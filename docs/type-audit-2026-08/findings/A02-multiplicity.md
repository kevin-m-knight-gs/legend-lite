# A02-multiplicity — adversarial audit of the two Multiplicity types and the multiplicity algebra

Scope: `core/src/main/java/com/legend/compiler/element/type/Multiplicity.java` (205 lines),
`core/src/main/java/com/legend/protocol/Multiplicity.java` (148 lines), every conversion site,
every algebraic operation, and end-to-end cardinality soundness through
`/home/user/probe/probe.sh` (DuckDB).

All probes live in `/tmp/a02/` (`Alg.java`, `RoundTrip.java`, `Diverge.java`, `model*.pure`,
`ddl*.sql`). Every "Actual output" below is pasted verbatim from a run.

---

## FINDINGS

### [UNSOUND] `Multiplicity.product` overflows `int` — a two-hop navigation is stamped `[0..0]` / `[0..1]` and the DB returns rows

`compiler/element/type/Multiplicity.java:118-119`:

```java
                            ? null : a.upper() * b.upper();
            return new Bounded(a.lower() * b.lower(), upper);
```

Both bound products are plain `int` multiplications with no overflow guard. Property
navigation composes through this (`Typer.java:2899` -> `Typer.java:2946` -> `Multiplicity.product`),
so any model whose declared bounds multiply past `2^31-1` gets a silently WRAPPED stamp.

**Repro A (silent wrong value under a `[0..0]` stamp)** — `/tmp/a02/model8.pure` declares
`addresses: Address[0..65536]` and `tags: Tag[0..65536]`; `/tmp/a02/ddl8b.sql` has 1 person,
1 address, 1 tag.

```
$ echo 'model::Person.all()->toOne().addresses.tags.label' > /tmp/a02/q.pure
$ /home/user/probe/probe.sh /tmp/a02/model8.pure /tmp/a02/q.pure test::TestRuntime /tmp/a02/ddl8b.sql
[G] type=String mult=[0]
[PLAN] rootType=Relation<(u_map__label:String[0])> mult=[0]
[PLAN] shape=SCALAR
[EXEC] shape=Scalar returnType=Relation<(u_map__label:String[0])> ...
[EXEC-ROW] String(home) |
```

The compiler claims the expression can hold **zero** values (`[0..0]`, `65536*65536 mod 2^32 == 0`)
and the runtime hands back `String(home)`. No error anywhere.

**Repro B (same model, 3 rows) — the `[0..0]` stamp routes to SCALAR and the executor ICEs**
(`/tmp/a02/ddl8.sql`: 2 addresses, 3 tags):

```
[G] type=String mult=[0]
[PLAN] shape=SCALAR
[EXEC-ERROR] java.lang.IllegalStateException: scalar-shaped result returned more than one row ? the to-one contract was not enforced upstream
```

**Repro C (a `[0..1]` to-one claim manufactured from two unbounded-ish hops)** —
`/tmp/a02/model4.pure` with `[0..2147483647]` on both hops:

```
$ echo 'model::A.all()->toOne().bs.cs' > /tmp/a02/q.pure
[G] type=model::C mult=[0..1]        # (2^31-1)^2 mod 2^32 == 1
```

**Repro D (negative product -> raw `IllegalArgumentException` escaping as an ICE)** —
`/tmp/a02/model6.pure` with `bs: B[50000]` and `cs: C[50000]`:

```
[G-ERROR] java.lang.IllegalArgumentException: lower must be >= 0, got -1794967296
[PLAN-ERROR] java.lang.IllegalArgumentException: lower must be >= 0, got -1794967296
[EXEC-ERROR] java.lang.IllegalArgumentException: lower must be >= 0, got -1794967296
```

Direct algebra probe (`/home/user/probe/jrun.sh /tmp/a02/Alg.java`):

```
--- product overflow probe ---
  [100000] . [100000] = [1410065408]   (exact 10000000000)
  [50000] . [50000] THREW IllegalArgumentException: lower must be >= 0, got -1794967296
  [46341] . [46341] THREW IllegalArgumentException: lower must be >= 0, got -2147479015
  [2] . [2000000000] THREW IllegalArgumentException: lower must be >= 0, got -294967296
  [65536] . [65536] = [0]   (exact 4294967296)
  [2..100000] . [2..100000] = [4..1410065408]
  [0..2147483647] . [0..2147483647] = [0..1]
```

Three hops of `[0..1300]` (`1300^3 = 2_197_000_000`) also overflow negative. The parser accepts
bounds up to `Integer.MAX_VALUE` (`TokenStreamCursor.java:318-326 consumeBoundedInt`), so every one
of these is reachable from ordinary model text.

**Why it matters**: this is the single worst finding — a wrapped stamp is *not* a widening, it is an
arbitrary claim. `[0..0]` and `[0..1]` are both to-one under `isMany()`, so the whole downstream
(ResultShape, Stamps, the SQL carrier choice) is driven by a fabricated cardinality.

---

### [UNSOUND] A `[1]`-typed property mapped to a NULLable column delivers `null` — on every egress lane

`/tmp/a02/model1.pure` adds `primaryAddrId: Integer[1]` mapped to the nullable
`T_PERSON.PRIMARY_ADDR_ID`; `/tmp/a02/ddl1.sql` has one row with `NULL`.

**Tabular lane:**
```
$ echo 'model::Person.all()->project(~[nm:p|$p.firstName, pa:p|$p.primaryAddrId])'
[G] type=Relation<(nm:String[1], pa:Integer[1])> mult=[1]
[PLAN] SELECT t0.FIRST_NAME AS nm, t0.PRIMARY_ADDR_ID AS pa FROM T_PERSON AS t0
[EXEC-COL] pa : Integer [INTEGER] mult=[1]
[EXEC-ROW] String(John) | Integer(1) |
[EXEC-ROW] String(Jane) | null |
[EXEC-ROW] String(Bob) | Integer(4) |
```

**Scalar lane:**
```
$ echo "model::Person.all()->filter(p|\$p.firstName=='Jane')->toOne().primaryAddrId"
[G] type=Integer mult=[1]
[PLAN] shape=SCALAR
[EXEC-ROW] null |
```

**Graph/serialize lane:**
```
[EXEC-JSON] [{"firstName":"John","primaryAddrId":1},{"firstName":"Jane","primaryAddrId":null},{"firstName":"Bob","primaryAddrId":4}]
```

**Collection lane — a hard internal error instead:**
```
$ echo 'model::Person.all()->map(p|$p.primaryAddrId)'
[G] type=Integer mult=[*]
[EXEC-ERROR] java.lang.IllegalStateException: NULL cell reached COLLECTION egress ? the lowerer owns
             the null-drop (COMPILER_SHORTCUT_AUDIT ?5); a NULL here is a lowering defect, never an empty
```

**Mechanism.** `MappingNormalizer.buildNewInstanceToOne` (`MappingNormalizer.java:3361-3396`) wraps
`[1]`-declared property values in `Pure.Lite.TRUST_ONE`, and `Scalars.java:485-488` lowers trustOne
as pure identity:

```java
        for (String f : Pure.nativeKeysAt(Pure.Lite.TRUST_ONE)) {
            RULES.put(f, (n, args) -> args.get(0));
        }
```

Nothing ever checks the wire. `PureSql.nullable` (`lowering/PureSql.java:241-243`) does stamp the
MIR `OutputCol` as NOT NULL for a `[1]` column, and `SqlTypeCensus` has a
`"null-under-required-multiplicity["` bucket (`exec/SqlTypeCensus.java:468`) — but that bucket only
fires for a statically-`Bottom` projection expression, never for a plain nullable column read, and
it only *counts*. The `Executor` SCALAR arm (`Executor.java:301-308`) checks **zero rows** only, not
a row holding NULL, so `one row holding NULL` sails straight through under `[1]`.

**Why it matters**: the top-prize category. Every consumer of a `[1]`-typed cell (Java decode,
JSON serialize, any downstream `+`/`length()`) sees a value the type says cannot exist.

---

### [UNSOUND / DOC-LIE] User-written `->toOne()` over an empty `[0..1]` returns `null` — the code comment claims it is checked

`lowering/Scalars.java:434-437` states:

```
        // USER toOne is CHECKED on BOTH bounds (multiplicity audit
        // slice 3): pure raises 'Cannot cast a collection of size N to
        // multiplicity [1]' for N != 1 — the old default arm DELETED
        // the call ...
        // ... The carrier follows the operand's STAMP: many = list-checked,
        // [0..1] = null-checked scalar, [1..1] = already exactly one (identity).
```

The `[0..1]` arm is **not** null-checked; it falls to the default `return args.get(0);` at
`Scalars.java:481`.

```
$ echo "model::Person.all()->project(~[a:p|\$p.nickName->toOne()])"     # nickName: String[0..1], NICK is NULL for 2 rows
[G] type=Relation<(a:String[1])> mult=[1]
[PLAN] SELECT t0.NICK AS a
[EXEC-COL] a : String [STRING] mult=[1]
[EXEC-ROW] String(Johnny) |
[EXEC-ROW] null |
[EXEC-ROW] null |

$ echo "model::Person.all()->filter(p|\$p.firstName=='Jane')->toOne().nickName->toOne()"
[G] type=String mult=[1]
[PLAN] shape=SCALAR
[EXEC-ROW] null |
```

Real Pure raises `Cannot cast a collection of size 0 to multiplicity [1]` here. Also
`->toOne()->length()` yields `null` under `Integer[1]`.

---

### [UNSOUND] The declared UPPER bound is never enforced at any egress — `[2]` happily returns 3 values

`Executor.java:370-391` (COLLECTION arm) checks only `!anyRow && lower >= 1` and
`values.size() < lower`. There is no upper-bound check in any arm, nor in the graph lane.

`/tmp/a02/model10.pure` declares `addresses: Address[2]` and `tags: Tag[1..*]`;
`/tmp/a02/ddl10.sql` gives John 3 addresses, address A1 one tag, A2/A3 none, and Zoe no addresses.

```
$ echo 'model::Person.all()->toOne().addresses.street'
[G] type=String mult=[2]
[EXEC-ROW] String(A3) |
[EXEC-ROW] String(A2) |
[EXEC-ROW] String(A1) |            <-- 3 values under a [2] claim, no error

$ echo "model::Person.all()->filter(p|\$p.firstName=='John')->toOne().addresses->size()"
[G] type=Integer mult=[1]
[EXEC-ROW] Long(3) |               <-- size() of a [2]-declared collection is 3
```

Graph lane, same model — `[2]` holding 0 and `[1..*]` holding 0:

```
[EXEC-JSON] [{"firstName":"John","addresses":[{"street":"A1","tags":[{"label":"home"}]},
             {"street":"A2","tags":[]},{"street":"A3","tags":[]}]},
             {"firstName":"Zoe","addresses":[]}]
```

`"tags":[]` under `Tag[1..*]` and `"addresses":[]` under `Address[2]`. (The *lower* bound IS checked
on the COLLECTION/SCALAR lanes — `[2]` with 0 rows and `[1..*]` with 0 rows both raise — so the
asymmetry is: lower checked on value lanes only, upper checked nowhere.)

---

### [UNSOUND] A `[1]`-typed navigation across a join returns 0 rows (LEFT JOIN null) or fans out to N rows

**0-case.** `/tmp/a02/model1.pure` has `Association Person_Address { person: Person[1]; addresses: Address[*]; }`.
`/tmp/a02/ddl2.sql` adds an orphan address (`PERSON_ID = 99`).

```
$ echo "model::Address.all()->project(~[st:a|\$a.street, who:a|\$a.person.firstName])"
[G] type=Relation<(st:String[1], who:String[1])> mult=[1]
[PLAN] SELECT t0.STREET AS st, t1.FIRST_NAME AS who
       FROM T_ADDRESS AS t0
       LEFT OUTER JOIN T_PERSON AS t1 ON t1.ID = t0.PERSON_ID
[EXEC-ROW] String(Orphan Way) | null |
```

`who` is `String[1]` twice over (`firstName` is `String[1]` on a NOT NULL column, `person` is
`Person[1]`), the compiler emits a LEFT OUTER JOIN, and the null comes back.

**2+-case.** `/tmp/a02/model2.pure` keeps `person: Person[1]` but joins on a non-unique column
(`T_PERSON.GRP = T_ADDRESS.GRP`); `/tmp/a02/ddl3.sql` has 2 persons in group 7 and 1 address in group 7.

```
[G] type=Relation<(st:String[1], who:String[1])> mult=[1]
[EXEC-ROW] String(123 Main St) | String(John) |
[EXEC-ROW] String(123 Main St) | String(Jane) |     <-- Address.all() has 2 rows; the project has 3
[EXEC-ROW] String(999 Pine Lane) | String(Bob) |

$ model::Address.all()->size()                       -> Long(2)
$ model::Address.all()->map(a|$a.person)->size()     -> Long(3)     # [*] . [1] must preserve size
```

The SCALAR lane at least notices (`IllegalStateException: scalar-shaped result returned more than
one row — the to-one contract was not enforced upstream`); the TABULAR and aggregate lanes are silent.

---

### [UNSOUND] `sum()` / `average()` over an empty collection are stamped `[1]` and return `null`

```
$ model::Person.all()->filter(p|$p.age > 200)->map(p|$p.age)->sum()
[G] type=Integer mult=[1]
[PLAN] shape=SCALAR
[EXEC-ROW] null |

$ ...->average()
[G] type=Float mult=[1]
[EXEC-ROW] null |
```

Pure's `sum([])` is `0` (and both signatures are `[1]`); SQL's `SUM`/`AVG` over no rows is `NULL`, and
the SCALAR egress check (`Executor.java:301`) does not fire because the aggregate query returns ONE
row holding NULL. `max()`/`min()` are correctly `[0..1]` (verified sound, below).

---

### [CRASH/ICE] `[2..1]`-style inverted bounds are guarded only on class properties; three other declaration sites throw a raw `IllegalArgumentException`

`compiler/element/ModelIntegrity.java:216-226` `requireValidBounds` exists exactly to stop this
("`[2..1]` previously surfaced lazily as a bare IllegalArgumentException"), but it is only called at
`ModelIntegrity.java:113` (stored property), `:118` (derived property) and `:122` (derived-property
parameter). `protocol.Multiplicity.Concrete` deliberately has no `upper >= lower` invariant
(`protocol/Multiplicity.java:71-73`) while `compiler...Multiplicity.Bounded` does
(`Multiplicity.java:150-153`), so `Multiplicity.from` (`Multiplicity.java:55-60`) is a **partial**
function and every unguarded site is an ICE.

```
### class property [2..1] (guarded):
[G-ERROR] com.legend.error.ModelException: [2:1] property 'n' of model::P: invalid multiplicity ? upper (1) must be >= lower (2)

### nav across an ASSOCIATION END declared [2..1]:      (Association model::PQ { p: P[1]; q: Q[2..1]; })
[G-ERROR] java.lang.IllegalArgumentException: upper (1) must be >= lower (2)

### call a function whose PARAMETER is [2..1]:          (function model::f(x: String[2..1]): String[1])
[G-ERROR] java.lang.IllegalArgumentException: upper (1) must be >= lower (2)

### call a function whose RETURN is [3..2]:             (function model::g(x: String[1]): String[3..2])
[G-ERROR] java.lang.IllegalArgumentException: upper (2) must be >= lower (3)
```

Brute-force conversion sweep (`/tmp/a02/Alg.java`) — 10 of the 25 `Concrete(lo,up)` pairs in `0..4`
are protocol-representable and compiler-unrepresentable:

```
FAIL[from-concrete-THROWS] [1..0] -> IllegalArgumentException: upper (0) must be >= lower (1)
FAIL[from-concrete-THROWS] [2..0] ... [2..1] ... [3..0] ... [3..1] ... [3..2] ...
FAIL[from-concrete-THROWS] [4..0] ... [4..1] ... [4..2] ... [4..3] ...
protocol->compiler conversion failures: 10
```

**Answer to "is the mapping total and injective?"** — `Multiplicity.from` is **injective but NOT
total**. There is no reverse conversion function at all (compiler -> protocol); the only backward
path is `Bounded.text()` re-parsed by `ProtocolEmitter.parseMultArg` (`ProtocolEmitter.java:3037`),
and both `ProtocolEmitter.multiplicity` (`:3256`) and `Protocol.mangleMult` (`:2676`) throw
`UnsupportedOperationException` on a `Var`. `[*]` and `[0..*]` are the SAME value in both types
(`Concrete(0,null)` / `Bounded(0,null)`); an unbounded LOWER is representable in neither (`int`).

---

### [CRASH/ICE] An unbound multiplicity VARIABLE on a class property is accepted, survives to the exec boundary on one lane and ICEs on the others

There is no scope check that a `[m]` names a declared multiplicity parameter. `Class model::P { n: String[m]; }`
compiles; so does `String[Integer]` (a Var named "Integer"). The class javadoc
(`Multiplicity.java:29-32`) states: *"Post-G layers (lowering, exec) must never see a `Var` at all"*.

```
### project a Var-multiplicity property (model9 = model8 + varProp: String[m], mapped to FIRST_NAME):
[G] type=Relation<(a:String[m])> mult=[1]
[PLAN] SELECT t0.FIRST_NAME AS a FROM T_PERSON AS t0
[EXEC-COL] a : String [STRING] mult=[m]           <-- a Var reached the EXEC boundary
[EXEC-ROW] String(John) |

### the same property on the scalar lane:
[PLAN-ERROR] java.lang.IllegalStateException: unresolved multiplicity variable reached lowering: Var[name=m]

### an if() over it:
[G-ERROR] java.lang.IllegalStateException: cannot union multiplicities [m] and [1] (an unresolved variable met a different bound)
```

A user typo (`String[l]` for `String[1]`, `String[O]` for `String[0..1]`) lands here.
`InferenceKernel.unifyMult` (`InferenceKernel.java:700-719`) also silently skips ALL checking when
the actual is a `Var` (the guard is `actual instanceof Multiplicity.Bounded ab`), which is why
`$x.varProp->toOne()` type-checks to `String[1]` with no complaint.

---

### [CRASH/ICE] `if(true, |[], |[])` trips an internal stamp-invariant assertion

The `[0..0]` stamp (`[]` types as `Nil[0..0]`, `Typer.java:2477-2490`) is `isMany()==false`, so the
lowering treats it as a ONE stamp while the SQL shape is a list:

```
$ echo 'if(true, |[], |[])'
[G] type=meta::pure::metamodel::type::Nil mult=[0]
[PLAN-ERROR] java.lang.IllegalStateException: MULTIPLICITY-STAMP INVARIANT VIOLATED (stamp program,
   docs/STAMP_DISCIPLINE_PROGRAM.md): ONE-STAMP/LIST-SHAPE mult=[0..0] sql=Case node=TypedIf test=<unattributed>
```

---

### [INCONSISTENCY] Six live spellings of "is this stamp many / to-one?", disagreeing on `[0..0]` and on `Var`

`Multiplicity.java:22-33` claims `isMany` is *"THE single implementation (an audit found five
divergent copies)"*; `lowering/Stamps.java:9-18` claims *"One principle, one reader: rules ask THESE
predicates and never touch Multiplicity internals again"*. Both are false — `grep -rn` for the
ad-hoc `upper()`-poking spellings in `core/src/main/java` returns **53** hits.

`/home/user/probe/jrun.sh /tmp/a02/Diverge.java` (each row is the exact boolean expression
transcribed from the cited line):

```
PREDICATE                                       [0]     [0..1]  [1]     [0..2]  [2]     [1..*]  [*]     [m]
Multiplicity.isMany            (Multiplicity.java:35)false   false   false   true    true    true    true    false
Bounded.isToOne                (Multiplicity.java:175)false   true    true    false   false   false   false   false
Stamps.many                    (Stamps.java:54) false   false   false   true    true    true    true    true
Stamps.toOne                   (Stamps.java:37) false   true    true    false   false   false   false   false
Stamps.atMostOne               (Stamps.java:47) true    true    true    false   false   false   false   false
StatementExecutor 'many'       (StatementExecutor.java:1757)true    false   false   true    true    true    true    true
CanonicalRenderSql 'many'      (CanonicalRenderSql.java:258)false   false   false   true    true    true    true    THROW
StampCensus.scalarStamp        (StampCensus.java:63)true    true    true    false   false   false   false   THROW
ResultShape.isMany             (ResultShape.java:73)false   false   false   true    true    true    true    THROW

--- the two holes, spelled out ---
[0]  isMany=false  isToOne=false   -> NEITHER many NOR to-one; the to-one/many dichotomy has a hole
[m]  Multiplicity.isMany=false   Stamps.many(transcribed)=true   -> same question, opposite answers
```

`[0..0]`: `Multiplicity.isMany` says NOT many, `StatementExecutor.java:1757` says many.
`Var`: `Multiplicity.isMany` says NOT many, `Stamps.many` and `StatementExecutor` say many, three
others throw. The `[0..0]` hole is exactly what turns the `product` overflow above into a SCALAR
shape for a 3-row result, and what fires the `if(true,|[],|[])` ICE.

Brute-force check confirming the hole is only at `[0..0]` (`/tmp/a02/Alg.java`, lattice lower 0..4 x
upper {0..4, unbounded}, 20 elements, all 20 checked):

```
FAIL[isMany-xor-isToOne] [0] isMany=false isToOne=false
isMany/isToOne failures: 1
```

---

### [SILENT FALLBACK] A `Var`-multiplicity element inside a collection literal is silently counted as exactly one

`compiler/spec/Typer.java:2479-2487`:

```java
            if (e.info().multiplicity() instanceof Multiplicity.Bounded b) {
                lo += b.lower();
                hi = hi == null || b.upper() == null ? null : hi + b.upper();
            } else {
                lo += 1;
                hi = hi == null ? null : hi + 1;
            }
```

The comment above it calls this "a non-Bounded element stamp cannot reach a literal (checker
invariant) and falls back to 1..1 for that slot". It is reachable:

```
$ echo "model::Person.all()->map(p|[\$p.varProp, 'a'])"      # varProp: String[m]
      TypedCollection :: String[2]
```

`[m]` was counted as `[1..1]`, giving `String[2]`. If `m` binds to `[*]` the truth is `[1..*]`, so
BOTH bounds are wrong; and since the upper bound is never enforced at egress (finding above), it
stays silent.

---

### [INFORMATION LOSS] `project(...)` silently rewrites any many column to `[0..1]`, including `[2]` and `[1..*]`

`compiler/spec/ProjectChecker.java:76-94` `clampTdsCells` maps every `isMany()` column to
`Multiplicity.Bounded.ZERO_ONE`. For a genuinely `[*]` column that is the row-explosion contract,
but for `[2]` / `[1..*]` it silently drops a declared LOWER bound:

```
$ model::Person.all()->project(~[p:x|$x.pair])           # pair: Integer[2]
[G] type=Relation<(p:Integer[0..1])> mult=[1]

$ model::Person.all()->project(~[a:p|$p.addresses.street])   # Address[*] . String[1]
[G] type=Relation<(a:String[0..1])> mult=[1]
```

The `[2]` case is a widening (safe direction) but the engine rejects a non-to-one project column
outright; here it is accepted and re-stamped with no diagnostic. (In the `[2]` model the error only
surfaces later, from the *mapping* checker: `property 'pair' of 'model::Person' declares multiplicity
Bounded[lower=2, upper=2] but the value has Bounded[lower=1, upper=1]`.)

---

### [INFORMATION LOSS] `exec.Column.multiplicity` is dropped at four construction sites; nothing downstream consumes it

`exec/Column.java:18-22`:

```java
    /** Pre-F5.2 arity — multiplicity unknown at this construction site
     * (scalar envelopes, pivot-rebuilt schemas). */
    public Column(String name, Type pureType) {
        this(name, pureType, null);
    }
```

Exhaustive list of the 2-arg (multiplicity-dropping) call sites — `grep -rn "new Column("` over
`core/src/main/java` gives 13 hits: 6 construct `exec.Column` (3 of them 3-arg, listed as sound below)
and 7 are the unrelated `SqlExpr.Column` (`sql/SqlExpr.java:326,334,344,357,358`) / `Type.Column`
(`compiler/element/type/Type.java:442,512`). The multiplicity-dropping ones:

| site | lane | effect |
|---|---|---|
| `exec/Executor.java:766` | dynamic PIVOT columns | multiplicity `null` |
| `exec/ExecutionResult.java:84` | `Scalar.columns()` | multiplicity `null` |
| `exec/ExecutionResult.java:107` | `Collection.columns()` | multiplicity `null` |
| `exec/ExecutionResult.java:144` | `Graph.columns()` | multiplicity `null` |

The three tabular sites (`Executor.java:737, 746, 921`) DO carry it. Live pivot repro:

```
$ echo "#>{store::PersonDatabase.T_PERSON}#->select(~[LAST_NAME, AGE_VAL])->pivot(~[LAST_NAME], ~[s:x|\$x.AGE_VAL:y|\$y->sum()])"
[G] typeRepr=... dynamicColumns=[Column[name=s, type=INTEGER, multiplicity=Bounded[lower=1, upper=1]]]
[EXEC-COL] 'Jones__|__s' : Integer [INTEGER] mult=null
[EXEC-COL] 'Smith__|__s' : Integer [INTEGER] mult=null
[EXEC-ROW] BigInteger(45) | BigInteger(58) |
```

The pivot column *type* is inherited from the aggregate template but the template's
`Bounded[1,1]` is not.

**What consumers do with `null`:** nothing — `grep -rn "\.multiplicity()"` over
`core/src/main/java` shows **no** reader of `exec.Column.multiplicity()` anywhere in main; the field
is output-only (the probe harness prints it). So the concrete damage is that the public
`ExecutionResult` API cannot distinguish a `[1]` scalar from a `[0..1]` scalar — which, combined with
the null-under-`[1]` finding above, leaves an API consumer with no way to know whether a `null`
scalar is legal. No crash, no defaulting, but an unrecoverable distinction loss at the K boundary.

---

### [INFORMATION LOSS] JSON-protocol local-property bounds are `long` on the wire and narrowed with a raw `(int)` cast

`model/MappingFromProtocol.java:605-610`:

```java
                    new com.legend.protocol.Multiplicity.Concrete(
                            (int) lp.lowerBound(),
                            lp.upperBound() == null ? null
                                    : Integer.valueOf(lp.upperBound().intValue())),
```

`Protocol.PLocalProp` (`protocol/Protocol.java:703-705`) carries `long lowerBound` /
`@Nullable Long upperBound` — matching the engine's 64-bit protocol — while
`protocol.Multiplicity.Concrete` is `int`-only. A wire value of `4294967296` silently becomes `0`;
`3000000000` becomes `-1294967296` and then the `Concrete` ctor's `lowerBound >= 0` guard throws.
Narrowing without a range check at a protocol ingress.

---

### [DOC-LIE] `MappingNormalizer`'s "the residual null-check is toOne's runtime semantics"

`normalizer/MappingNormalizer.java:3333-3336`:

```
     * pure must spell {@code ->toOne()} to bind such a value to a
     * {@code [1]} property. The synthesized body says the same thing
     * explicitly: the MAPPING is the assertion that the read is to-one,
     * and the residual null-check is {@code toOne}'s runtime semantics.
```

The emission at `:3395` is `Pure.Lite.TRUST_ONE`, not `toOne`, and `builtin/Pure.java:1161-1163`
says of trustOne: *"types like toOne, lowers as IDENTITY — no runtime guard"*. There is no residual
null-check; see the `[1]`-null finding. Ranked as a doc lie because the defect itself is already
reported above.

---

### [DEAD/MINOR] `ProtocolEmitter.parseMultArg` reports an out-of-range integer as a "multiplicity PARAMETER"

`protocol/ProtocolEmitter.java:3042-3055` catches `NumberFormatException` from
`Integer.parseInt` and rethrows `"ProtocolEmitter has no rule for a multiplicity PARAMETER 'X'"`.
A generic multiplicity argument like `Result<T|99999999999999>` (accepted verbatim as text by
`TokenStreamCursor.parseMultiplicityArgumentText`, `:1118-1147`, which does no range check at all,
unlike the bracketed `parseMultiplicity` which routes through `consumeBoundedInt`) therefore produces
a message naming the wrong cause. Also: `parseMultiplicityArgumentText` accepts `2..1`, so the
unbracketed generic-argument grammar has no bound sanity either.

---

### [MINOR] A lambda's DECLARED parameter multiplicity is silently discarded unless the signature slot is `Any`

`compiler/spec/Typer.java:2074-2083`: the source annotation only overrides the signature when
`paramType` is `meta::pure::metamodel::type::Any`. Otherwise the declared annotation is dropped with
no conformance check:

```
### map lambda param annotated model::P[1] :   [G] type=String mult=[*]
### map lambda param annotated model::P[0..1] : [G] type=String mult=[*]
### map lambda param annotated model::P[*] :   [G] type=String mult=[*]
### map lambda param annotated model::P[3] :   [G] type=String mult=[*]
### map lambda param annotated String[1] :     [G] type=String mult=[*]
### map lambda param annotated Integer[1] :    [G] type=String mult=[*]
```

`map`'s signature is `{T[1]->V[m]}`; every one of these should be a conformance error (real Pure
rejects `{x: P[*] | ...}` in a `T[1]` slot). The signature wins, so the *stamp* stays safe — but the
declaration is accepted and ignored. Same shape at `MatchChecker.java:288-296`
(`if (param.multiplicity() == null) return true;`) and at `NewChecker.java:50-59` / `:112-125`, where
the subsumption check is skipped whenever either side is not `Bounded`.

---

## VERIFIED SOUND

Everything below was RUN, not read.

**The bounded algebra itself is exactly right** (`/tmp/a02/Alg.java`, exhaustive over the 20-element
lattice: lower `0..4` x upper `{0..4, unbounded}` — all 400 pairs and all 8000 triples, no sampling):

```
lattice size = 20 : [[0], [0..1], [0..2], [0..3], [0..4], [*], [1], [1..2], [1..3], [1..4], [1..*],
                     [2], [2..3], [2..4], [2..*], [3], [3..4], [3..*], [4], [4..*]]
union commutativity failures: 0
union idempotence failures: 0
union associativity failures: 0
union upper-bound-soundness failures: 0      (no operand cardinality is ever excluded by the union)
union convex-hull failures: 0                (union is exactly min-lower / max-upper, unbounded absorbing)
product commutativity failures: 0
product associativity failures: 0
product identity failures: 0                 ([1] is a two-sided identity)
product denotational-soundness failures: 0   (every reachable n-hop total is admitted)
product exact-hull failures: 0               (product is exactly [a*c .. b*d], with the [0..0] annihilator)
monotonicity failures: 0                     (widening either operand widens union AND product)
text() round-trip failures: 0
```

- `[0..0]` annihilation beats unbounded absorption in both argument orders:
  `[0..0].[*] = [0]` and `[*].[0..0] = [0]`.
- `union` is imprecise-but-sound in the expected interval way: `union([0..0],[2..3]) = [0..3]`
  admits `1`, which neither operand does. That is convex-hull semantics, not a bug.
- `Var` handling in `union`/`product` is LOUD exactly as documented: `union(m,m)=[m]`,
  `union(m,n)` / `union(m,[1])` throw; `product(m,[1])=[m]`, `product([1],m)=[m]`,
  `product(m,n)` / `product(m,m)` / `product(m,[0])` throw. Same-name-different-instance `Var`s
  unify (`union(Var("m"), new Var("m")) = [m]`).

**Parser round-trip** (`/tmp/a02/RoundTrip.java`, every form I could find in the grammar, compiled
through the real `Compiler.compileModel` and re-parsed from `text()`): all 23 valid forms STABLE.
`[0..*]`->`[*]`, `[2..2]`->`[2]`, `[1..1]`->`[1]`, `[0..0]`->`[0]`, `[00]`->`[0]`, `[01]`->`[1]`,
`[ 1 ]`->`[1]` all normalize and re-parse to the same value. `protocol.Concrete.toString()` and
`compiler.Bounded.text()` agree on every point of a 7x8 grid (`divergences: 0`). `[m]`/`[k]`/`[_x]`
survive as `Var` and round-trip. Malformed forms `[*..*] [..1] [1..] [] [-1] [1.5] [1..2..3]` are all
clean `ParseException`s with sensible messages; `[1..0]` on a class property is a clean
`ModelException`.

**Egress checks that DO fire** (all pasted from runs against DuckDB):
- `->toOne()` over 0 rows: `IllegalStateException: Cannot cast a collection of size 0 to multiplicity [1]`.
- `->at(99)`: same message. `->toOneMany()` over 0 rows: `... to multiplicity [1..*]`.
- `[2]` navigation over 0 rows: `... to multiplicity [2]`.
- `[]->toOne()`: raised IN the database (`SQLException: Invalid Input Error: Cannot cast a collection
  of size 0 to multiplicity [1]`) — the static-empty arm at `Scalars.java:456-462` works.
- SCALAR root with >1 row: `scalar-shaped result returned more than one row`.
- `NULL` cell at a non-variant COLLECTION egress: raises rather than silently dropping.

**Checks that are correct:**
- `IfChecker`'s condition multiplicity: `if(<Boolean[*]>, |1, |2)` ->
  `TypeInferenceException: if condition must be Boolean[1], got multiplicity Bounded[lower=0, upper=null]`.
- `IfChecker` result multiplicity is the union of the branches, and an else-less `if` is made optional
  (`IfChecker.java:74-77, 124-127`) — correct, not the hardcoded `[1]` the comment says it replaced.
- `NewChecker` subsumption: `^model::Address(street=$p.nickName, ...)` with `nickName: String[0..1]`
  ->  `property 'street' of 'model::Address' declares multiplicity Bounded[lower=1, upper=1] but the
  value has Bounded[lower=0, upper=1]`. Both the `^new` and the `^$var` copy path implement it.
- Mapping-side property multiplicity conformance fires (`property 'pair' ... declares multiplicity
  Bounded[lower=2, upper=2] but the value has Bounded[lower=1, upper=1]`).
- `max()`/`min()` over a possibly-empty collection are correctly `[0..1]` (only `sum`/`average` lie).
- `->first()` is `[0..1]`, `->last()` is `[0..1]`, `->at(i)` is `[1]`, `->size()` is `Integer[1]` —
  all as Pure declares.
- Mapping a `[0..1]` property over `[*]` drops the empties rather than emitting nulls
  (`Person.all()->map(p|$p.nickName)` -> 1 row from 3 persons), which is the correct
  "collections hold no empties" behaviour.
- `Type.Column` (`Type.java:604-609`) requires a non-null multiplicity — the drop is only in
  `exec.Column`.
- `Multiplicity.from` is the ONLY protocol->compiler conversion point
  (`TypeClassifier.multiplicity`, `TypeClassifier.java:145-147`, merely delegates); exhaustive grep
  for `Multiplicity.from` returns 11 call sites, all funnelling here.
- `union`/`product` have exactly 7 call sites in main (`MatchChecker:171,261`, `IfChecker:75,113`,
  `InferenceKernel:687`, `Typer:2947`), i.e. the single-owner claim for the *arithmetic* is true even
  though the single-owner claim for `isMany` is not.

---

## NOT COVERED

- **`->fold`, `->sort`, `->distinct`, `->take`, `->slice` multiplicity end-to-end**: `take`/`slice`
  over a scalar collection are not lowerable in this build
  (`NotImplementedException: scalar lowering not yet implemented for TypedLimit/TypedSlice`), so I
  could not observe a runtime cardinality for them.
- **`->pivot` beyond the exec-boundary column shape**: a class-rooted pivot is
  `NotImplementedException: class query under TypedPivot is not resolvable yet`; I reached the pivot
  lane only through a raw table read (`#>{...}#`), which was enough for the `mult=null` finding but
  not for a multiplicity-soundness probe on pivoted cells.
- **`UserCallInliner` (G1/G2) multiplicity substitution** was read only for its `Multiplicity.from`
  call sites; I did not build inlining-specific repros.
- **Milestoning / temporal, m2m (`PureInstanceSetImplementation`), union/`XStore` mappings**: these
  each have their own multiplicity paths (`XStorePureEnds`, `UnionSynthesis` — 8 `TRUST_ONE`
  emissions between them) that I did not exercise; the `TRUST_ONE`-is-identity finding almost
  certainly applies there too but I have no pasted output for it, so I did not claim it.
- **Dialects other than DuckDB** (sqlite/h2): all execution evidence here is DuckDB.
- **The `long`->`int` narrowing at `MappingFromProtocol.java:607`** is cited from source only; I did
  not build a JSON-protocol ingress repro.
- I did NOT run `mvn` or the JUnit suite (per the brief), and I modified nothing under
  `/home/user/legend-lite`.
