# A21 — Repo-wide census of type-related silent fallbacks, defaults and swallowed errors

**Scope executed:** every `.java` file under `core/src/main/java` — **543 files, 161,027 LOC**.
(The task brief said 897 files; 897 is the whole-repo count including `core/src/test/java`,
`nlq/`, `pct/`, `parser-equivalence/`, `tools/engine-runner/` and
`experiments/backend-probes/`. `find /home/user/legend-lite -name '*.java' | wc -l` = 897;
`find core/src/main/java -name '*.java' | wc -l` = 543. Test/tooling trees are excluded here
because they cannot produce a type in a user query.)

**Invariant under test** — `AGENTS.md` §4, *"the most-cited invariant in the codebase"*:

> ### 4. NO FALLBACKS. NO DEFAULTING. `[CONVENTION]`
> - If a type is unknown, **fail**. Do not guess, default, or fall back.
> - Every defaulting branch is a bug hiding behind a safety net.

**Verdict: the invariant is broadly upheld in the aggregate and broken in ten specific
places, five of which are provably UNSOUND** (the runtime value contradicts the
compile-time type). The bigger systemic finding is item 4: the compiler has almost no
user-facing error vocabulary downstream of the Typer — **840 of 1,168 `throw new` sites
(72%) use an internal exception type**, and 18 distinct internal-exception sites are
proven reachable from ordinary Pure query text.

## Method (all counts are mechanical, no eyeballing)

Scripts (kept in the scratchpad, all re-runnable):
`census.py` (lexer-lite that blanks comments + string/char/text-block literals while
preserving byte offsets, so no pattern ever fires inside a string or comment; catch bodies
extracted by brace matching), `census2.py` (per-site CSV + exception-hierarchy
classification), `census3.py` (extracts the actual *defaulted value expression* at every
fallback operator), `census4.py`/`census5.py` (the type-decision denominator and the
hardcoded-type-in-recovery-position set), `census6.py` (type-*producing* methods returning
null/empty/hardcoded).

Reachability was established **mechanically, not by reading**: eleven source files were
copied to `/tmp/a21/inst/src` (the repo itself was never modified), a
`com.legend.A21Trace.hit()` call was inserted at each candidate fallback, the copies were
compiled to `/tmp/a21/inst/classes` and placed **first** on the classpath. Every "PROVEN"
below means a trace line was printed by a real run. `/tmp/a21/irun.sh` is the instrumented
runner; `/home/user/probe/jrun.sh` is the stock one. **Every repro pasted below reproduces
on the STOCK build** unless it is explicitly labelled `[A21-HIT]` (instrumented).

Fuzz corpus: 247 auto-generated queries covering the whole `builtin/Pure.java` native
surface (463 signatures parsed, 247 reachable with scalar-only argument shapes), plus ~70
hand-written shapes. Fixture: `/tmp/a21/model.pure` + `/tmp/a21/ddl.sql`
(Person/Address over DuckDB, with `Integer`, `Float`, `Decimal(20,4)`, `StrictDate`
columns and one person with **no** address so `[0..1]` columns actually go NULL).

---

## 1. THE MECHANICAL CENSUS

Full per-site table: **`/home/user/audit/findings/A21-fallback-census.csv`** — 4,252 rows,
columns `kind, subkind, package, file, line, method, type_related, verdict, reachability,
detail, review_note, snippet`. `verdict` is populated for the 54 hand-reviewed
type-decision sites and blank for the rest.

### 1a. Totals by construct

| construct | count | type-related |
|---|---:|---:|
| `catch (` blocks | **140** | 57 |
| — (a) rethrow / wrap | 60 | 19 |
| — (b) log-and-continue | 5 | 1 |
| — (c) SWALLOWED (empty body) | 14 | 3 |
| — (c) SWALLOWED (no throw / no return / no log) | 25 | 15 |
| — (d) converted to a fallback value (`return` from the catch) | 36 | 19 |
| `.orElse(` | 186 | 127 |
| `.orElseGet(` | 17 | 16 |
| `.getOrDefault(` | 52 | 28 |
| `.isPresent() ? :` | 32 | 27 |
| `.isEmpty() ? :` | 151 | 61 |
| `!= null ? :` | 259 | 117 |
| `== null ?` | 572 | 257 |
| `Optional.ofNullable(` | 19 | 15 |
| `Objects.requireNonNullElse*(` | 0 | 0 |
| `instanceof X y ? :` | 210 | 136 |
| **`.orElseThrow(` (counted separately — this one is fine)** | **153** | 99 |
| `default ->` in a switch | **442** | 149 |
| `default:` in a switch | **0** | 0 |
| `PlatformTypes.ANY` reference | 10 | 10 |
| `Type.Primitive.*` reference | 409 | 409 |
| `new RelationType(List.of())` | 1 | 1 |
| `Type.RelationType.trustedColumn(` / `.lateBound(` call sites | 6 | 6 |
| `Multiplicity.Bounded.*` reference | **365** | 365 |
| `// TODO` | 4 | 1 |
| `// FIXME` | 0 | 0 |
| `// HACK` | 1 | 1 |
| `// XXX` | 0 | 0 |
| `// for now` | 2 | 1 |
| `…fallback…` in a comment | 112 | 45 |
| `…default…` in a comment | 263 | 99 |
| `…best effort…` in a comment | 9 | 4 |
| `…conservative…` in a comment | 13 | 7 |
| `…assume…` in a comment | 6 | 2 |
| `…not implemented…` in a comment | 1 | 0 |
| `throw new …` (all) | **1,168** | 661 |

**Census caveat, stated so the CSV can be trusted:** the CSV's `standin` rows total 420
(`Type.Primitive.*` 409 + `PlatformTypes.ANY` 10 + empty `RelationType` 1). They do **not**
include the 365 `Multiplicity.Bounded.*` references, because that scanner's pattern expects
the constant directly after `Multiplicity.` and every use in the tree is
`Multiplicity.Bounded.X` (`Multiplicity.X` bare: **0** occurrences). The 365 *are* counted
in the §5 denominator (`census4.py`'s pattern allows the optional `Bounded.` segment), and
they are all reviewed there as part of the 1,560. Row count note: the CSV shows 55 rows
carrying a verdict for 54 reviewed sites — `Lowerer.java:1413` legitimately appears twice
(once as an `orElse` operator row, once as a `Type.Primitive.*` stand-in row) and is one
site, not two.

Two observations that only a mechanical pass produces:

* **`default:` appears zero times** and `default ->` 442 times — the codebase is uniformly
  arrow-switch. AGENTS.md §"Common mistakes" #8 says *"`default ->` in a render method —
  add the missing arm"*; there are 442 of them repo-wide, 149 in type-related code.
* **`FIXME`/`XXX` are zero and `TODO` is 4.** The prose-debt markers the brief asked for
  essentially do not exist. The debt is instead carried in 112 `fallback` and 263 `default`
  mentions inside *javadoc that justifies the fallback*. That is a real signal: the
  fallbacks in this repo are **documented and deliberate**, not forgotten.

### 1b. Per-package (count, with `(type-related)` in parentheses)

| package | catch | fallback-op | type stand-in | flag comment | throw new | total |
|---|---|---|---|---|---|---|
| `com.legend.resolver` | 13 (10) | 393 (317) | 104 (104) | 49 (25) | 241 (176) | 800 |
| `com.legend.lowering` | 5 (5) | 214 (141) | 171 (171) | 45 (28) | 144 (98) | 579 |
| `com.legend.compiler.spec` | 16 (14) | 194 (138) | 65 (65) | 19 (14) | 203 (184) | 497 |
| `com.legend.normalizer` | 10 (4) | 265 (66) | 0 (0) | 39 (18) | 114 (30) | 428 |
| `com.legend` | 18 (10) | 121 (72) | 28 (28) | 10 (3) | 55 (37) | 232 |
| `com.legend.model` | 2 (0) | 132 (26) | 0 (0) | 35 (8) | 33 (5) | 202 |
| `com.legend.exec` | 10 (2) | 97 (37) | 17 (17) | 25 (12) | 28 (17) | 177 |
| `com.legend.sql.dialect` | 1 (0) | 76 (21) | 0 (0) | 21 (3) | 47 (12) | 145 |
| `com.legend.protocol` | 2 (1) | 70 (12) | 0 (0) | 28 (3) | 40 (10) | 140 |
| `com.legend.parser.section` | 3 (0) | 109 (8) | 0 (0) | 11 (0) | 15 (0) | 138 |
| `com.legend.parser` | 16 (2) | 53 (12) | 0 (0) | 41 (11) | 16 (5) | 126 |
| `com.legend.plan` | 3 (2) | 56 (36) | 14 (14) | 7 (4) | 31 (22) | 111 |
| `com.legend.lineage` | 10 (0) | 54 (15) | 0 (0) | 3 (2) | 35 (18) | 102 |
| `com.legend.compiler.element` | 4 (4) | 37 (22) | 17 (17) | 15 (11) | 22 (14) | 95 |
| `com.legend.sql` | 0 (0) | 54 (24) | 0 (0) | 14 (4) | 7 (2) | 75 |
| `com.legend.server` | 16 (2) | 17 (5) | 0 (0) | 6 (0) | 29 (3) | 68 |
| `com.legend.compiler` | 1 (0) | 41 (15) | 0 (0) | 7 (1) | 9 (4) | 58 |
| `com.legend.testdatagen` | 1 (1) | 32 (18) | 0 (0) | 1 (0) | 23 (11) | 57 |
| `com.legend.compiler.spec.typed` | 0 (0) | 41 (36) | 2 (2) | 9 (6) | 2 (2) | 54 |
| `com.legend.values` | 2 (0) | 3 (0) | 0 (0) | 1 (0) | 37 (0) | 43 |
| `com.legend.builtin` | 0 (0) | 6 (5) | 0 (0) | 11 (4) | 7 (0) | 24 |
| `com.legend.compiler.element.type` | 0 (0) | 6 (5) | 2 (2) | 3 (0) | 12 (9) | 23 |
| `com.legend.protocol.spec` | 0 (0) | 9 (0) | 0 (0) | 8 (3) | 2 (0) | 19 |
| `com.legend.validation` | 3 (0) | 8 (0) | 0 (0) | 1 (0) | 6 (0) | 18 |
| `com.legend.cache` | 4 (0) | 0 (0) | 0 (0) | 1 (0) | 4 (0) | 9 |
| `com.legend.ide` | 0 (0) | 3 (1) | 0 (0) | 0 (0) | 4 (2) | 7 |
| `com.legend.lexer` | 0 (0) | 2 (0) | 0 (0) | 0 (0) | 1 (0) | 3 |
| `com.legend.error` | 0 (0) | 0 (0) | 0 (0) | 1 (0) | 0 (0) | 1 |
| `com.legend.server.serial` | 0 (0) | 0 (0) | 0 (0) | 0 (0) | 1 (0) | 1 |
| **TOTAL** | **140 (57)** | **2093 (1032)** | **420 (420)** | **411 (160)** | **1168 (661)** | **4232** |

---

## 2. HAND-REVIEW OF EVERY TYPE-VALUED FALLBACK SITE

A site qualifies when the **value being defaulted is a `Type` / `Type.*` / `ExprType` /
`Multiplicity` / `SqlType` / `Type.Column` / `Type.RelationType`**. Found mechanically two
ways and unioned: (a) `census3.py` extracts the defaulted expression at every `orElse` /
`orElseGet` / `getOrDefault` / `default ->` / null-or-empty ternary and matches it against a
type-constructor grammar — 19 sites out of 1,711 fallback-value sites; (b) `census5.py`
finds every *hardcoded* type constant in a recovery position including `if (missing) return`
shapes the ternary regex misses — 18 sites; (c) `census6.py` finds every type-*producing*
method returning null/empty/hardcoded — 40 sites. Union after de-duplication and after
discarding regex false positives: **54 sites, all reviewed below.**

**Verdicts: 10 VIOLATION, 24 SUSPICIOUS, 20 LEGITIMATE.**

### 2a. VIOLATIONS (10)

#### V1–V2. `Lowerer.java:1409-1414` — a project column's element type falls back to `String`
```java
Type elemT = Type.schemaView(info.type()) instanceof Type.RelationType rt
        ? rt.columns().stream()
                .filter(cc -> cc.name().equals(c.name()))
                .findFirst().map(Type.Column::type)
                .orElse(Type.Primitive.STRING)      // V1
        : Type.Primitive.STRING;                    // V2
```
`elemT` is fed straight into `Fold.lateralElem(r.expr(), PureSql.type(elemT), …)` and into
`SqlExpr.Column.of(lat, "elem", PureSql.type(elemT))` — i.e. it becomes the **declared SQL
type of a MIR column**. Two separate defaults to `String`, neither derived from anything.
It is also a **model lookup + a type decision inside the Lowerer**, which AGENTS.md
"Common mistakes" #9 forbids outright.

**PROVEN REACHED** (instrumented run, `/tmp/a21/irun.sh` + `RawSql.java`):
```
##Q meta::relational::metamodel::execute::executeInDb('select A, B from T', $c, 0, 1000)
      .rows->extend(~[xs: r|$r.value('A')->concatenate($r.value('A'))])
[A21-HIT] Lowerer:1413 column-not-found STRING => STRING
[sql] SELECT t0.*, t1.elem AS xs
FROM (select A, B from T) AS t0
LEFT JOIN LATERAL ( SELECT UNNEST(t2.lst) AS elem FROM (
    SELECT list_concat(CASE WHEN t0.A IS NULL THEN [] ELSE [t0.A] END,
                       CASE WHEN t0.A IS NULL THEN [] ELSE [t0.A] END) AS lst ) AS t2 ) AS t1 ON TRUE
  [COL] xs : ClassType[fqn=meta::pure::metamodel::type::Any] mult=Bounded[lower=0, upper=1]
  [ROW] [Integer(1), String(x), Integer(1)]
```
The lateral's element type is stamped `String` in the MIR while the value flowing through
it is `INTEGER`. It is reached because the source is a **late-bound raw-SQL grid**, whose
declared schema has zero columns (`Type.RelationType.lateBound()`), so *every* column name
misses and *every* such column gets `String`. Blast radius is capped only because the
execution boundary re-stamps late-bound schemas afterwards — the wrong type is live in the
MIR but does not (here) reach the wire.

**A21Trace note:** the first instrumentation of this site produced a false positive
because `Optional.orElse(x)` evaluates `x` **eagerly**; it was rewritten to `orElseGet`
before the run above. Any auditor repeating this must do the same.

#### V3–V4. `DecimalKindRules.java:64-70` + `Typer.java:1814-1820` — `toDecimal` frontend and lowering disagree on the scale; the runtime obeys the lowering
```java
// DecimalKindRules.toDecimal, the `default ->` arm
default -> new SqlExpr.Cast(args.get(0),
        n.args().get(0).info().type() == Type.Primitive.INTEGER
                ? new SqlType.Decimal(38, 0)      // V3
                : new SqlType.Decimal(38, 18));   // V4
```
```java
// Typer.refineDecimalCarrier:1814-1820 — the FRONTEND stamp
if (out.type() == Type.Primitive.DECIMAL && DECIMAL_CARRIER_PRODUCERS.contains(chosen.qualifiedName())) {
    return new ExprType(new Type.PrecisionDecimal(38, 18), out.multiplicity());
}
```
`DecimalKindRules`' own javadoc says *"`toDecimal` keeps the VALUE's own scale — an INTEGER
input is a scale-0 Decimal, **never the blanket (38,18) fabrication**"*. `Typer:1818` is
exactly that blanket (38,18) fabrication, applied unconditionally. **The two layers
implement opposite rules and nothing reconciles them.**

**Repro — STOCK build** (`/home/user/probe/jrun.sh /tmp/a21/Dec.java`):
```
##Q model::Person.all()->project(~[d: p|$p.age->toDecimal()])
   [G declared] Relation<(d:Decimal(38,18)[1])>
   [J sql]      SELECT CAST(t0.AGE_VAL AS DECIMAL(38, 0)) AS d FROM T_PERSON AS t0
   [K coltype]  d -> PrecisionDecimal[precision=38, scale=18]
   [K cell]     java.math.BigDecimal(30)  BigDecimal scale=0 precision=2

##Q model::Person.all()->project(~[d: p|$p.addresses.amt->toOne()->toDecimal()])   // amt is Decimal(20,4)
   [G declared] Relation<(d:Decimal(38,18)[1])>
   [J sql]      SELECT CAST(t1.AMT AS DECIMAL(38, 18)) AS d …
   [K cell]     java.math.BigDecimal(12.340000000000000000)  BigDecimal scale=18 precision=20
```
**UNSOUND**: the compiler claims `Decimal(38,18)`; the value is a `BigDecimal` with
`scale=0`. And the `Decimal(20,4)` model column's declared precision/scale is destroyed and
replaced by a hardcoded (38,18) — a type read from nowhere in the model.

#### V5. `Typer.java:3100-3103` — a `@Relation<(c:T)>` column with no multiplicity silently becomes `[1]`, and the cast re-stamps a nullable column
```java
Multiplicity m = c.multiplicity() == null
        ? Multiplicity.Bounded.ONE : Multiplicity.from(c.multiplicity());
```
The javadoc admits it: *"multiplicity defaults to `[1]`"*.

**Repro — STOCK build** (`/home/user/probe/jrun.sh /tmp/a21/Cast.java`; the fixture has a
4th person with no address, so `addresses.zip` is genuinely NULL for that row):
```
T_PERSON rows = 4
##Q model::Person.all()->project(~[nm:p|$p.firstName, z:p|$p.addresses.zip])
   [G] Relation<(nm:String[1], z:Integer[0..1])>
   [COL] z : INTEGER mult=Bounded[lower=0, upper=1]
   [ROW] [Zoe, null]

##Q …->cast(@meta::pure::metamodel::relation::Relation<(nm:String, z:Integer)>)
   [G] Relation<(nm:String[1], z:Integer[1])>
   [J] SELECT t0.FIRST_NAME AS nm, t1.ZIP AS z FROM T_PERSON AS t0 LEFT OUTER JOIN T_ADDRESS AS t1 ON t0.ID = t1.PERSON_ID
   [COL] z : INTEGER mult=Bounded[lower=1, upper=1]
   [ROW] [Zoe, null]          <-- null under Integer[1]
```
**UNSOUND**: writing `(z:Integer)` instead of `(z:Integer[0..1])` — the natural spelling —
silently strengthens `[0..1]` to `[1]`, the SQL is unchanged, and a NULL is delivered under
a lower-bound-1 stamp. The cast is a pure re-stamp with no verification at all.

#### V6. `TdsChecker.java:169` — an explicitly annotated TDS `Decimal` column is typed `Decimal(38,0)`
```java
case "Decimal" -> new Type.PrecisionDecimal(Type.PrecisionDecimal.MAX_PRECISION, 0);
```
#### V7. `TdsChecker.java:224` — a `d`-suffixed TDS cell is typed `Decimal(38,0)` whatever its scale
```java
// pure DECIMAL-suffix cells (21d, 41.0d)
if (v.matches("[+-]?\\d+(\\.\\d+)?[dD]")) {
    return new Type.PrecisionDecimal(Type.PrecisionDecimal.MAX_PRECISION, 0);
}
```
**Repro — STOCK build** (`/home/user/probe/jrun.sh /tmp/a21/Tds.java`):
```
##Q #TDS \n   n, v \n   a, 41.5d \n   b, 2.25d \n #
   [G] Relation<(n:String[1], v:Decimal(38,0)[1])>
   [COL] v : PrecisionDecimal[precision=38, scale=0]
   [ROW] String(a) | BigDecimal(41.50){scale=2} |
   [ROW] String(b) | BigDecimal(2.25){scale=2} |

##Q #TDS \n   n:String, v:Decimal \n   a, 41.5 \n #
   [G] Relation<(n:String[1], v:Decimal(38,0)[1])>
   [ROW] String(a) | BigDecimal(41.5){scale=1} |
```
**UNSOUND, twice**: declared scale 0, delivered scale 2 and scale 1. Note the second case
is not even inference — the user *wrote* `v:Decimal` and got a scale-0 type.

#### V8. `TdsChecker.java:239` — a zone-suffixed TDS timestamp is typed the ABSTRACT `Date`, which then ICEs downstream
```java
if (v.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([+-]\\d{4}|Z)")) {
    return Type.Primitive.DATE;
}
```
This is the clearest example of the invariant's own rationale ("every defaulting branch is
a bug hiding behind a safety net"): the guessed abstract `Date` is a type no *value* can
have, and `Scalars.datePrecision:3479` explicitly refuses it.

**Repro — STOCK build**:
```
##Q #TDS \n   n, t \n   a, 2020-01-02T03:04:05+0000 \n #
   [G] Relation<(n:String[1], t:Date[1])>
   [ROW] String(a) | DateWithSecond(2020-01-02T03:04:05+0000) |

##Q #TDS \n   n, t \n   a, 2020-01-02T03:04:05+0000 \n #->project(~[h: r|$r.t->hasMinute()])
   [G] Relation<(h:Boolean[1])>
   [K-ERR] java.lang.IllegalStateException: a date-precision predicate over the abstract Date
           type is not statically decidable — declare the value StrictDate or DateTime
        at com.legend.lowering.Scalars.datePrecision(Scalars.java:3479)
```
A defaulting decision at phase G directly manufactures an ICE at phase I. The suggested
remedy in the message ("declare the value StrictDate or DateTime") is not actionable — the
user *cannot* annotate a TDS cell as `DateTime`; `TdsChecker.annotatedType:178` accepts
`DateTime`, but the inference path never reaches it for a zone-suffixed cell.

#### V9. `SqlTyping.java:1195-1201` — a >38-digit Decimal literal is RE-INFERRED to `DOUBLE`
```java
if (v.precision() > 38) {
    return T_DOUBLE;
}
```
**Corroborates the orchestrator's note and A10's finding.** Repro — STOCK build:
```
##Q model::Person.all()->project(~[big: p|123456789012345678901234567890123456789.5d])
  [G] Relation<(big:Decimal(38,1)[1])>
  [J] SELECT 123456789012345678901234567890123456789.5 AS big FROM T_PERSON AS t0
  [K] … {[1.2345678901234568E38]}
```
Declared `Decimal(38,1)`; delivered as a floating-point value with 17 significant digits.
The SQL layer overriding the frontend's own stamp is exactly the "two layers, two rules"
shape of V3/V4.

#### V10. `Lowerer.java:1414` (the ternary else arm of V1) — counted separately because it is a distinct branch with a distinct trigger (`Type.schemaView(info.type()) == null`, i.e. a non-relation `info`). **UNPROVEN** — I could not construct a `computedColumns` call whose `info.type()` is not relation-viewable; legacy `project([lambdas],[names])` and `cast(@TabularDataSet)` both still yield a `Relation<…>`. Reported as an unreachable-but-present fallback.

### 2b. SUSPICIOUS (24) — a type not derived from the model, but widening / gated / documented

| # | site | what it produces | reach |
|---|---|---|---|
| S1 | `Type.java:511-513` `trustedColumn()` | **THE TRUST-NAME RULE** — any name over a late-bound schema becomes `Any[0..1]` | PROVEN |
| S2 | `Type.java:440-444` `lateBound()` | a raw-SQL grid types as a relation with **zero** columns + one `Any[0..1]` `*` wildcard | PROVEN |
| S3 | `Typer.java:2932` | frontend consumer of S1 | PROVEN |
| S4 | `Fold.java:781` | lowering consumer of S1 | by construction |
| S5 | `GridProbe.java:54` | a JDBC column whose SQL type has **no** Pure mapping becomes `Any[0..1]` instead of failing | PROVEN |
| S6 | `Typer.java:2926` | quote-stripped column-name fallback — a read binds to a differently-quoted column and adopts *its* type | UNPROVEN |
| S7 | `Typer.java:1113` | TDS row cells: mixed element types collapse to `Any` instead of the kernel LUB | PROVEN |
| S8 | `NewChecker.java:147` | `^List()` with no `values` gets element type `Any` (vs `Nil` for `[]`) | PROVEN |
| S9 | `LayoutTypes.java:58` | a **recursive** class layout silently becomes `SqlType.Scalar.JSON` | PROVEN |
| S10 | `LayoutTypes.java:90` | a class with no layoutable properties silently becomes `SqlType.Scalar.JSON` | PROVEN |
| S11–S16 | `TdsChecker.java:213,216,220,227,253,255` | TDS column types **guessed from cell text** (Float/Integer/Float/Boolean/String/String) | PROVEN |
| S17–S18 | `Typer.java:1652,1657` | `parseDate` result type inferred by **regex over the literal string** | UNPROVEN |
| S19 | `PctTdsWrap.java:114` | every dynamic pivot column stamped `Multiplicity.Bounded.ONE` | UNPROVEN |
| S20 | `NormalizeFolds.java:52` | an else-less inlined `if` folds to an empty collection typed **`Boolean[0..1]`** — `Boolean` hardcoded, not the then-branch's type | UNPROVEN |
| S21 | `CastPolicy.java:143-147` | `Integer -> Decimal` cast hardcodes `SqlType.Decimal(38,0)` — same disagreement as V3 | UNPROVEN |
| S22 | `PureSql.java:75` | `case DECIMAL -> new SqlType.Decimal(38, 18)` — a `Decimal(20,4)` model column loses its declared scale at the SQL boundary | PROVEN |
| S23 | `SqlTyping.java:470` | `callType` `default -> UNKNOWN` | UNPROVEN |
| S24 | `SqlTyping.java:905` | `reducerType` `default -> UNKNOWN` | UNPROVEN |

The whole S1–S5 cluster is one design decision — the "trust-name rule" for raw-SQL grids
— and it is **documented, gated on `isLateBound()`, and widening** (`Any` is a supertype),
so it is not unsound. But it is unambiguously *"guessing a type that is not derived from
the model"*, which is what §4 forbids. Proof it is live (instrumented):
```
##Q executeInDb('select A, B, C, D, E, F, G from T', $c, 0, 1000).rows->project(~[q: r|$r.value('A')])
[A21-HIT] Typer:2932 trustedColumn Any[0..1] for 'A'
[A21-HIT] GridProbe:54 unmapped SQL type 'BLOB' -> trustedColumn Any[0..1] for F
[A21-HIT] GridProbe:54 unmapped SQL type 'INTERVAL' -> trustedColumn Any[0..1] for G
  [COL] q : ClassType[fqn=meta::pure::metamodel::type::Any] mult=Bounded[lower=1, upper=1]
  [ROW] [Integer(1)]
```
`GridProbe:54` is the sharpest of these: an unmapped **SQL** type is not "late-bound", it is
*known and unsupported* — the honest answer is to fail, not to call it `Any`.

**S7 is also an INCONSISTENCY** — two LUB rules for the same fact, proven side by side on
the stock build:
```
##Q …->project([p|$p.age, p|$p.addresses.lat->toOne()],['a','b']).rows->map(r|$r.values)
  [G] meta::pure::metamodel::type::Any[*]           <-- Typer:1113  (mixed ? Any : elem)
##Q …->project(~[x: p|[$p.age, $p.addresses.lat->toOne()]->at(0)])
  [G] Relation<(x:Number[1])>                        <-- Typer:2464  (kernel::commonSupertype)
```
`[Integer, Float]` is `Number` in one place and `Any` in the other. The Java carrier
changes with it: the `Integer`-typed path decodes to `java.lang.Integer`, the `Any` path to
`java.lang.Long` / `java.lang.Double`.

### 2c. LEGITIMATE (20) — checked and found correct

`Typer.java:2470` (empty collection ⇒ `Nil[0]`, the correct bottom-type rule, with the
right javadoc); `Typer.java:3112` `dateType()` (derived from the literal's precision);
`Typer.java:3157` `unknownColumn()` (uses the distinguished `?` `TypeVar`, later solved by
the ⊆/= constraints — a *placeholder*, not a silent concrete type);
`Typer.java:232` (lambda param multiplicity ⇒ `[1]` — **dead**: the surface grammar
requires the multiplicity, `{p: model::Person | …}` is `ParseException [1:x] Unexpected
token`, so the branch is unreachable from Pure text);
`MatchChecker.java:176,277`; `TdsChecker.java:185` (`orElseThrow(TypeInferenceException)`);
`Compiler.java:446-455` `wireSchema` (the documented scalar-root ⇒ one-column `value`
relation; the column type *is* `info.type()`); `Scalars.java:3464` (`date()` arity default
arm reads the 6th argument's type); `TemporalFrame.java:596`; `ScalarValueReads.java:50`
(widening to `[0..1]` — safe direction); `Substitution.java:1541`;
`GraphEmission.java:1084` (null then an immediate loud throw);
`InferenceKernel.java:556` `valueLub` (numeric×numeric ⇒ `Number`, else `Any` — the real
Pure covariance LUB); `FoldChecker.java:164`; `StoreCompiler.java:183` (every
`RelationalDataType` arm explicit, `Array`/`Object_` throw); `ExecutionResult.java:144`;
`PlanEnvelope.java:55` and `SeedSqlForms.java:62` (the `String` is the result type of plan
*text* / SQL *text*, a census false positive); `CsvSeed.java:61`.

---

## 2d. THE 140 `catch` BLOCKS — classification and the type-related review

Mechanical classification (brace-matched body, structural test on the body's statements):

| class | n | of which type-related |
|---|---:|---:|
| (a) rethrow / wrap (`throw` and no `return`) | 60 | 19 |
| (b) log-and-continue | 5 | 1 |
| (c) SWALLOWED — **empty body** | 14 | 3 |
| (c) SWALLOWED — no throw, no return, no log | 25 | 15 |
| (d) converted to a fallback value (`return` out of the catch) | 36 | 19 |

Caught exception types, most common: `NotImplementedException` 18, `RuntimeException` 16,
`NumberFormatException` 16, `java.sql.SQLException` 11, `TypeInferenceException` 9,
`IllegalArgumentException` 8, bare `Exception` 6, `Throwable` 2.

**The 57 type-related catches, reviewed. Findings:**

* **`Typer.java:1717-1729` `checkWithDeferred` — SWALLOWED `TypeInferenceException` in
  overload selection.** Every candidate is tried; each failure is stashed in
  `firstFailure` and the loop continues; only if *all* fail is the first rethrown. This is
  correct backtracking overload resolution, **LEGITIMATE**, and it is why the
  `catch (TypeInferenceException) { continue; }` at `Typer.java:1977` and `:2016`
  (`lambdaAritiesFit`) are also legitimate — they are pre-filters over the same candidate set.
  Note `SchemaInvariantException` is deliberately re-thrown first (`:1720`), which is the
  right discipline.
* **`Typer.java:2329-2340` `functionCandidates` — SWALLOWED `RuntimeException`** while
  unioning imported candidate FQNs; surfaced only if *nothing* is healthy. **SUSPICIOUS**:
  a `RuntimeException` here is not necessarily "this candidate is broken", it is any
  internal defect (NPE, ISE) — and because 72% of throws in this repo are internal (§4),
  this catch will swallow genuine compiler bugs as "not a candidate".
* **`FunctionCompiler.java:150` — DROP-AT-OVERLOAD.** The comment is admirably honest:
  *"a call whose engine-correct target is the broken overload can **silently re-dispatch to
  a healthy sibling**"*. **SUSPICIOUS — self-declared open defect** (tracked as task #56).
  This is a *silently wrong overload*, i.e. a wrong signature, i.e. a wrong type.
* **`Scalars.java:2785-2790` `datePrecisionOrUnknown` — `catch (IllegalStateException
  undecidable) { return null; }`.** This catches precisely the ISE thrown at
  `Scalars.java:3479` (V8's ICE). So the abstract-`Date` failure is swallowed on one path
  and escapes as an ICE on another. **SUSPICIOUS — INCONSISTENT**: the same undecidable
  condition is a silent `null` here and an internal crash there.
* **`Lowerer.java:1658` `foldOrIsolate`** — `catch (UnfoldableRef first) { try { … } catch
  (UnfoldableRef second) { throw new IllegalStateException(…) } }` — a retry, then a loud
  (but internal) throw. **LEGITIMATE** as control flow; the ISE is an ICE finding (§4).
* **`Compiler.java:688,698` / `ModelIntegrity.java:79,269` / `MappingNormalizer.java:313,
  326,398` / `ClassSources.java:722,910` — the "poison / wall" family.** These all record
  the failure in a `walls`/`poisons` map and continue, so a later *use* re-fails loudly.
  **LEGITIMATE** (they are deferral, not suppression) — I verified each writes to a sink
  that the use-site consults.
* **`CorrelatedSubselects.java:1913,1948`** (`catch (MappingResolutionException) { om = null; }`)
  and **`GraphEmission.java:2482`** (`aj = null`) — a *user-facing* mapping error is
  downgraded to a null and the caller takes a different route. **SUSPICIOUS**: unlike the
  poison family these do not record anything.
* **`StatementExecutor.java:2150` `catch (NotImplementedException) { return ga.classFqn(); }`**
  — a lowering wall becomes a *class FQN string*. **SUSPICIOUS**.
* **`SpecParser.java:958` `catch (IllegalArgumentException) { return new CTime(raw…) }`** —
  an out-of-range StrictTime literal (`%200:12:22`) is carried as raw text rather than
  rejected. Interacts with the `TypedCTime` hole in §4 (every StrictTime literal ICEs at
  lowering anyway).
* The 14 **empty** catches are all non-type-critical (`TimingLedger` diagnostics, LSP
  position parsing, temp-table cleanup, `EngineStyleH2` unknown-timezone spelling,
  `ScanRelations` "keep scanning"). The three flagged `type_related` by proximity
  (`StaticFold.java:235`, `UserCallInliner.java:151`, `TestDataGenerator.java:1689`) are on
  inspection all correct: `StaticFold` is a *probe* ("is this statically foldable?") whose
  failure legitimately means "no", `UserCallInliner:151` parses a namespace suffix, and the
  third is temp-table cleanup. **LEGITIMATE.**
* **`SeedableLets.java:42` `catch (RuntimeException notScalar)`** — a broad catch, but the
  comment states it is a *trial lowering probe* and is pinned by an `ErrorShapeGuardrail`
  test. **LEGITIMATE.**
* **`Executor.java:528-536`** — `cellRead` decodes a **variant-JSON cell at an `Any`
  root** by *content sniffing*: quoted ⇒ String, `true`/`false` ⇒ Boolean, `null` ⇒ null,
  else `Long.valueOf` (catch ⇒ fall through), else `Double.valueOf`
  (`catch (NumberFormatException ignored) { return s; }`). The runtime Java class of an
  `Any` cell is therefore decided by the **text shape of the cell**, not by any type. It is
  not unsound (everything returned conforms to `Any`), but it is a decode-side
  type decision made from data. **SUSPICIOUS.** The file's own comment concedes the risk:
  *"the '4'-as-Long witness showed sniffing mis-types raw text"*.

**No catch block in the 140 was found to swallow an exception and substitute a *`Type`
object*.** The type-valued defaults all live in the operator/`default ->` census, not in
catches.

---

## 3. TOP VIOLATIONS BY BLAST RADIUS — repros

All ten VIOLATIONs already carry their repro in §2a. Ranked by blast radius:

| rank | site | blast radius | escaping wrong type shown? |
|---|---|---|---|
| 1 | `Typer.java:1818` + `DecimalKindRules.java:68` | **every `toDecimal()` / `parseDecimal()` in the repo** | YES — `Decimal(38,18)` declared, `BigDecimal` scale 0 delivered |
| 2 | `Typer.java:3102` | **every `@Relation<(c:T)>` annotation/cast written without an explicit multiplicity** — the natural spelling | YES — NULL under `Integer[1]` |
| 3 | `DecimalKindRules.java:69` + `PureSql.java:75` | **every `Decimal` column and every `toDecimal` of a non-Integer** — the model's `Decimal(p,s)` is replaced by `(38,18)` | YES — `Decimal(20,4)` → `(38,18)` |
| 4 | `TdsChecker.java:169` + `:224` | **every `Decimal` column of every `#TDS` literal**, annotated or inferred | YES — scale 0 declared, scale 1/2 delivered |
| 5 | `SqlTyping.java:1201` | every Decimal literal with >38 significant digits | YES — `Decimal(38,1)` declared, `Double` delivered |
| 6 | `TdsChecker.java:239` | every zone-suffixed timestamp cell in a `#TDS` literal | YES — abstract `Date`, then an ICE |
| 7 | `Lowerer.java:1413` | every many-scalar (`concatenate`-rooted) project/extend column over a late-bound raw-SQL grid | YES — `String` in the MIR under an `INTEGER` value |
| 8 | `Typer.java:2932` / `Type.java:512` (S1/S3) | every by-name read over any raw-SQL grid | widening only (`Any`) |
| 9 | `GridProbe.java:54` | every JDBC column whose SQL type has no Pure mapping (BLOB, INTERVAL, …) | widening only (`Any`) |
| 10 | `Lowerer.java:1414` | **UNREACHABLE as far as I could drive it** — see below | n/a |

**Unreachable / not-driven sites (reported as the brief asks):**

* `Lowerer.java:1414` — needs `Type.schemaView(info.type()) == null` at a `computedColumns`
  call. Legacy `project([lambdas],[names])`, `->cast(@TabularDataSet)`, `->restrict`, and
  raw-grid `extend` all still present a relation-viewable `info`. Present but not driven.
* `Typer.java:232` — **provably dead from Pure source text**: the grammar requires a
  multiplicity on a typed lambda parameter.
  `model::Person.all()->filter({p: model::Person | $p.age > 1})` ⇒
  `com.legend.parser.ParseException` (compare `{p: model::Person[1] | …}`, which compiles).
  The default can only be reached by a protocol-level `Variable` built in Java.
* `Typer.java:2926` (quote-strip), `Typer.java:1652/1657` (parseDate regex),
  `PctTdsWrap.java:114`, `NormalizeFolds.java:52`, `CastPolicy.java:145`,
  `SqlTyping.java:470/905` — present, plausibly reachable, not driven within budget.

---

## 4. THE INTERNAL-EXCEPTION (ICE) CENSUS

**Classification is by real hierarchy, not by name.** Verified by grepping every
`class X extends Y` in the tree:

*User-facing* = extends `com.legend.error.LegendCompileException` (which carries a `Phase`
and an `element`): `ModelException`, `MappingResolutionException`, `ResolutionException`,
`TypeInferenceException`, `SchemaInvariantException`, `ParseException`.

*Internal* = everything else. **`NotImplementedException` is `extends RuntimeException`**
(`error/NotImplementedException.java:8`), **not** a `LegendCompileException` — it lives in
the `error` package but carries no phase and no element. **`DialectCapability` is
`extends IllegalStateException`** (`sql/dialect/DialectCapability.java:14`) — a
"this dialect can't do X" message delivered as an ISE. Also internal:
`UnfoldableRef`, `UnknownFqnException`, `UnsupportedConnectionShape`,
`UnsupportedElementShape`, `UnsupportedMappingShape`, `MissingDatabase`, `Wrapped`,
`CheckedCarrier`, `ProbeFailed`.

### 4a. Counts

**1,168 `throw new` sites: 840 INTERNAL (72%), 320 USER-FACING (27%), 8 other.**

| exception type | sites |
|---|---:|
| `NotImplementedException` (internal) | **383** |
| `IllegalStateException` | **283** |
| `IllegalArgumentException` | 92 |
| `DialectCapability` (⊂ `IllegalStateException`) | 25 |
| `UnsupportedOperationException` | 24 |
| `UnsupportedMappingShape` | 10 |
| `UnfoldableRef` | 8 |
| `UncheckedIOException` | 5 |
| `IndexOutOfBoundsException` | 2 |
| `UnsupportedElementShape` | 2 |
| `NullPointerException` | 1 |
| `ProbeFailed` / `CheckedCarrier` / `Wrapped` / `UnknownFqnException` / `UnsupportedConnectionShape` | 1 each |
| — user-facing: `TypeInferenceException` | 168 |
| — user-facing: `ModelException` | 83 |
| — user-facing: `MappingResolutionException` | 38 |
| — user-facing: `ParseException` | 23 |
| — user-facing: `SchemaInvariantException` | 6 |
| — user-facing: `ResolutionException` | 2 |

### 4b. Per package — the shape of the problem

| package | INTERNAL | USER | internal share |
|---|---:|---:|---:|
| `com.legend.resolver` | 204 | 37 | **85%** |
| `com.legend.lowering` | 138 | 4 | **97%** |
| `com.legend.normalizer` | 58 | 56 | 51% |
| `com.legend` | 49 | 3 | 94% |
| `com.legend.sql.dialect` | 47 | 0 | **100%** |
| `com.legend.protocol` | 40 | 0 | 100% |
| `com.legend.values` | 37 | 0 | 100% |
| `com.legend.lineage` | 35 | 0 | 100% |
| `com.legend.model` | 33 | 0 | 100% |
| `com.legend.plan` | 29 | 0 | 100% |
| `com.legend.server` | 29 | 0 | 100% |
| `com.legend.compiler.spec` | 28 | **175** | **14%** |
| `com.legend.exec` | 27 | 0 | 100% |
| `com.legend.testdatagen` | 23 | 0 | 100% |
| `com.legend.compiler.element.type` | 12 | 0 | 100% |
| `com.legend.builtin` / `com.legend.sql` | 7 / 7 | 0 / 0 | 100% |
| `com.legend.validation` | 6 | 0 | 100% |
| `com.legend.compiler.element` | 5 | 17 | 23% |
| `com.legend.parser.section` | 5 | 10 | 33% |
| `com.legend.compiler` / `com.legend.cache` / `com.legend.ide` | 4 each | 5 / 0 / 0 | — |
| `com.legend.parser` | 3 | 13 | 19% |
| everything else | ≤2 | 0 | — |

**This is the headline of §4.** `com.legend.compiler.spec` — the Typer — has a real
user-facing error vocabulary (175 `TypeInferenceException` vs 28 internal, 14% internal).
**Every phase after it does not.** The Lowerer is 97% internal, the resolver 85%, and
`sql.dialect`, `exec`, `plan`, `values`, `lineage`, `model` and `protocol` are 100%
internal. The moment a query passes the Typer, any subsequent failure is by construction an
internal error rather than a compile error, and the user sees a Java exception class name.

### 4c. Reachability — proven by fuzzing

Fuzz sweep: 247 auto-generated single-native queries over the whole `builtin/Pure` surface,
run through A-G / A-J / A-K (`/tmp/a21/Fuzz.java`). Result:

```
======== SUMMARY: 247 queries, 157 fully executed ========
---- USER-FACING errors (fine) ----
  x5  com.legend.compiler.spec.TypeInferenceException
---- INTERNAL exceptions escaping (ICE findings) ----
  x54  java.lang.IllegalStateException @ Scalars.lower:2608
  x13  java.sql.SQLException @ Executor.executePrepared:269
  x5   java.lang.IllegalStateException @ Lowerer.aggValue:1175
  x4   java.lang.IllegalStateException @ Executor.shapeRow:798
  x3   com.legend.error.NotImplementedException @ Scalars.strptimeOf:2540
  x2   java.sql.SQLException @ Executor.executePrepared:268
  x1   java.lang.IllegalStateException @ Lowerer.computedColumns:1357
  x1   java.lang.IllegalStateException @ Scalars.lambda$static$38:834
  x1   com.legend.error.NotImplementedException @ Scalars.lambda$static$121:2396
  x1   com.legend.error.NotImplementedException @ Scalars.lambda$static$26:690
```
**5 user-facing errors vs 85 internal exceptions** on well-typed input. Every one of these
90 queries passed phase G — the Typer accepted them — so they are, by the compiler's own
judgement, legal Pure.

**18 distinct internal-exception sites proven reachable from ordinary Pure text:**

| site | message | example input |
|---|---|---|
| `Scalars.java:2608` ISE | `no scalar lowering registered for resolved overload '…'` | any of 54 natives, e.g. `meta::pure::functions::date::calendar::cw($d,$s,$d,$n)` |
| `Scalars.java:3479` ISE | `a date-precision predicate over the abstract Date type is not statically decidable` | `#TDS\n n,t\n a, 2020-01-02T03:04:05+0000\n#->project(~[h: r\|$r.t->hasMinute()])` |
| `Scalars.java:834` ISE | `hasSubsecondWithAtLeastPrecision needs a literal precision` | `…hasSubsecondWithAtLeastPrecision($d, $p.age)` |
| `Scalars.java:2771` NIE | `toString over ClassType[fqn=model::Address] is not modeled` | `~[x: p\|^model::Address(…)->toString()]` |
| `Scalars.java:2540` NIE | `format dynafunctions need a LITERAL format string` | `meta::legend::lite::convertDateFormat($p.firstName, $p.firstName)` |
| `Scalars.java:2396` NIE | `convertTimeZone needs a LITERAL format string` | `convertTimeZoneFormat(%2020-01-02T03:04:05, $p.firstName, $p.firstName)` |
| `Scalars.java:690` NIE | `id() over a non-enum instance has no relation-land lowering` | `meta::pure::functions::meta::id('x')` |
| `Lowerer.java:1175` ISE | `aggregate reducer argument of kind TypedNativeCall is not supported (literals only)` | `meta::pure::functions::math::max($p.addresses.lat->toOne(), $p.addresses.lat->toOne())` |
| `Lowerer.java:1357` ISE | `extend/project columns […] reference names unresolvable even after isolation` | `~[c: p \| meta::legend::lite::avg([3, 3])]` |
| `Lowerer.java:3127` NIE | `scalar lowering not yet implemented for TypedCTime` | `%03:04:05` |
| `Executor.java:798` ISE | `a many-valued cell reached a scalar TDS slot ('c') — the lowering must explode scalar streams in SQL (E2)` | `~[c: p\|$p.firstName->split($p.firstName)]`, also `chunk`, `range`, `regexpExtract` |
| `Substitution.java:1426` NIE | `association property '$p.addresses' used other than as a navigation head` | `~[x: p\|$p.addresses->toOne()]` |
| `Substitution.java:1758` NIE | `object-space use of the instance variable '$p' other than property access` | `~[x: p\|$p->toString()]` |
| `Substitution.java:1794` NIE | (β-reduction hole) | `~[x: p\|[1,2]->map(v\|$v + 1)->size()]` |
| `Substitution.java:1905` NIE | `object-space expression node TypedCTime is not substitutable yet (H2 vocabulary)` | `~[t: p\|%03:04:05]` |
| `CorrelatedSubselects.java:2246` NIE | `aggregate 'size' over an expression containing a to-many navigation is not supported yet` | `~[x: p\|[$p.age, $p.addresses.lat->toOne()]->size()]` |
| `Executor.java:268` raw `java.sql.SQLException` | `Binder Error: …` — the lowering emitted SQL the dialect refuses | `round($p.addresses.amt->toOne(), $p.age)`; `percentile([3,3], $p.age)`; `$p.addresses.amt->toOne() + 1.0d` ⇒ `SUM(t1.AMT, 1.0)` (a 2-argument `SUM` — invalid SQL) |
| `Executor.java:269` raw `java.sql.SQLException` | `Conversion Error / Invalid Input Error` — runtime data errors surfaced as raw JDBC exceptions | `acos($p.age)`, `$p.firstName->toBoolean()` |

**Corroboration of the orchestrator's `TypedCTime` note — CONFIRMED and worse than stated.**
`Typer.java:178` mints `TypedCTime` for every `%HH:MM:SS` literal, `CollectionLanes.java:115`
knows about it — but the Lowerer's `scalarInner` switch has `case TypedCDate d ->
MatchFold.dateLit(d.value())` (`Lowerer.java:2364`) and **no `TypedCTime` arm at all**
(`grep -n "case TypedCTime" core/src/main/java/com/legend/lowering/*.java` returns only
`CollectionLanes.java:115`). Every StrictTime literal in a query is therefore an ICE, at one
of *two* sites depending on position (stock build):
```
##Q model::Person.all()->project(~[t: p|%03:04:05])
  [G] Relation<(t:StrictTime[1])> Bounded[lower=1, upper=1]
  [J-ICE] com.legend.error.NotImplementedException @ Substitution.rewrite:1905
          object-space expression node TypedCTime is not substitutable yet (H2 vocabulary)
##Q %03:04:05
  [G] StrictTime Bounded[lower=1, upper=1]
  [J-ICE] com.legend.error.NotImplementedException @ Lowerer.scalarValueTailArms:3127
          scalar lowering not yet implemented for TypedCTime
##Q model::Person.all()->filter(p|%03:04:05 == %03:04:05)
  [J-ICE] com.legend.error.NotImplementedException @ Substitution.rewrite:1905
```
`StrictTime` is a first-class `Type.Primitive` with a full `Pure` FQN and a working lexer,
parser and Typer — and **zero** working lowering. The type exists end-to-end at compile
time and is unusable at runtime.

### 4d. Other corroborations requested by the orchestrator

* **`Scalars.java` `equal`/`eq` omitting the null guards — CORROBORATED.** The code is at
  `Scalars.java:79-84`: *"equal takes NO optionalOperandGuards DELIBERATELY (T1.4)"*. This
  is A10's finding and I do not restate its repro; I confirm the code reads as A10 quotes
  it and that the deliberate omission is real, not an oversight.
* **`SqlTyping.decimalLitType` re-inferring to DOUBLE — CORROBORATED and promoted.** Exact
  line is `SqlTyping.java:1201` (`if (v.precision() > 38) return T_DOUBLE;`). See V9 for my
  own stock-build repro. I rank it a VIOLATION of §4 as well as an unsoundness, because
  it is the SQL layer *re-deciding* a type the frontend already stamped.
* **`CastPolicy.java` doing lattice checks — CORROBORATED.** `CastPolicy.java:129-136` calls
  `PlatformTypes.isAny(tc)` and throws a `ModelException` on a model class; `:143-147`
  branches on `c.source().info().type() == Type.Primitive.INTEGER` to pick
  `SqlType.Decimal(38,0)`; `castByPolicy:151-166` calls `crossKindRaise(src, target)`,
  `isSqlPrimitive`, `isWidening` — a full source/target lattice walk inside the lowering.
* **`Lowerer.java:2384/2588/2617` doing ModelContext lookups — CORROBORATED.** All three are
  `classLayout.apply(<a Type>)` calls (`Lowerer.java:2384`, `:2588`, `:2591`, `:2617`) — a
  `Function<Type, Optional<List<Type.Column>>>` that resolves a class's declared property
  layout, i.e. a model lookup keyed on a TYPE, used to *select the MIR shape*
  (`StructGet` vs `manyPropertyMap` vs `scalar`). `LayoutTypes` (S9/S10) is the same lookup
  with a JSON fallback bolted on.

---

## 5. THE HONEST HEADLINE NUMBER

**Denominator — what a "type-decision site" is, defined mechanically** (`census4.py`):
every expression **in value position** whose value is a `Type` / `Type.*` / `ExprType` /
`Multiplicity` / `SqlType` / `Type.Column` / `Type.RelationType`. Concretely: `new
Type.{ClassType,EnumType,TypeVar,GenericType,FunctionType,RelationType,SchemaAlgebra,
PrecisionDecimal,Column}(`, `new ExprType(`, `new Multiplicity.Bounded(`,
`Multiplicity.{ONE,ZERO_ONE,ZERO_MANY,ONE_MANY,PURE_ONE,ZERO}`, `Type.Primitive.*`,
`new SqlType.X(`, `SqlType.Scalar.X`, `Type.RelationType.{trustedColumn,lateBound}(`.
"Value position" excludes `case` labels, `==`/`!=`/`.equals(` comparisons, `import`s and
`instanceof` operands, which is what removes the 268 pure-dispatch uses.

```
raw type-expression occurrences                                        : 1828
  minus case labels / comparisons / imports / instanceof (268)
  = TYPE-DECISION SITES (value position)                               : 1560
```

**Numerator — sites that can silently produce a type not derived from the model:**

```
type-decision sites                                              1560   100.0%
  reviewed as candidate fallback/default/recovery positions         54     3.5%
    VIOLATION   (a bare §4 breach; 5 of them provably UNSOUND)       10     0.6%
    SUSPICIOUS  (non-model-derived but widening / gated / doc'd)     24     1.5%
    LEGITIMATE  (derived, or loud, or a correct bottom/LUB rule)     20     1.3%
  ---------------------------------------------------------------------------
  ==> CAN SILENTLY PRODUCE A TYPE NOT DERIVED FROM THE MODEL         34     2.2%
        of which PROVEN reachable by a real run                      24
        of which reachable by construction                            1
        of which present but not driven                               9
        of which PROVEN UNSOUND (runtime value violates the type)     5
```

### **34 of 1,560 type-decision sites (2.2%) can silently produce a type not derived from the model. 25 of those 34 are reachable. 5 produce a value that provably violates the compile-time type.**

The five proven unsound ones, with the one-line repro each:

1. `model::Person.all()->project(~[d: p|$p.age->toDecimal()])` ⇒ declared `Decimal(38,18)`, delivered `BigDecimal` **scale 0** — `Typer.java:1818` vs `DecimalKindRules.java:68`
2. `…->cast(@…Relation<(nm:String, z:Integer)>)` over a `[0..1]` column ⇒ **NULL under `Integer[1]`** — `Typer.java:3102`
3. `#TDS\n n, v\n a, 41.5d\n#` ⇒ declared `Decimal(38,0)`, delivered **scale 2** — `TdsChecker.java:224`
4. `#TDS\n n:String, v:Decimal\n a, 41.5\n#` ⇒ declared `Decimal(38,0)`, delivered **scale 1** — `TdsChecker.java:169`
5. `~[big: p|1234…9.5d]` (39 digits) ⇒ declared `Decimal(38,1)`, delivered **`Double`** — `SqlTyping.java:1201` (corroborates A05/A10)

**Fairness note on the 2.2%.** The invariant is stated absolutely ("every defaulting branch
is a bug"), so on its own terms the true score is 34 breaches, not 2.2% compliance. But the
census also shows the opposite of what an "invariant honoured only in the comments" repo
looks like: 153 `orElseThrow` sites, zero `default:` labels, zero `FIXME`, four `TODO`,
the great majority of the 1,560 type decisions made directly from a model lookup, an
annotation or a literal (census4's syntactic pass puts only 19 in a fallback position at
all; the hand review widened that to 54 candidates by adding `if (missing) return` and
type-producing-method shapes), and 112 `fallback` comments that overwhelmingly *justify a
gate* rather than apologise for a guess.
The failure mode here is not laziness — it is **two layers independently deciding the same
type and disagreeing** (V3/V4, V9, S7, S21/S22), which is exactly the class of bug §4 was
written to prevent and does not catch.

**The second headline, which is larger than the first:** **840 of 1,168 `throw new` sites
(72%) use an internal exception type**, and the split is by phase — 14% internal in the
Typer, 85% in the resolver, 97% in the Lowerer, 100% in `sql.dialect`, `exec`, `plan`,
`values`, `lineage`, `model`, `protocol`. On a 247-query sweep of the platform's own native
surface, **85 internal exceptions escaped versus 5 user-facing errors**, from 18 distinct
sites. Every one of those queries type-checked. `NotImplementedException`, at 383 sites the
most-thrown exception in the codebase, is `extends RuntimeException` — it is not part of the
user-facing error hierarchy at all.

---

## VERIFIED SOUND

Things I checked mechanically and found correct — coverage evidence, not filler:

* **`default:` (colon-form switch labels): zero occurrences** across all 543 files. The
  arrow-switch discipline is total.
* **`FIXME` 0, `XXX` 0, `TODO` 4, `HACK` 1** — the prose-debt surface the brief asked me to
  measure essentially does not exist.
* **`orElseThrow` (153 sites) outnumbers `orElseGet` (17) 9:1** and appears in 94 of the
  type-related sites — the loud form is the dominant idiom in type code.
* **`Objects.requireNonNullElse*` : zero occurrences.** No silent null-substitution helper
  is used anywhere.
* **Only 1 `new RelationType(List.of())` in the tree** (`Type.java:441`, `lateBound()`),
  and it is the documented late-bound wildcard — the census found no ad-hoc empty schemas.
* **Only 10 `PlatformTypes.ANY` references** repo-wide, all reviewed above.
* **No `catch` block anywhere substitutes a `Type` object for a caught exception** — all
  140 classified, all type-valued defaults live in operator/`default ->` positions.
* **`Typer.java:2464` collection LUB is correct** (`kernel::commonSupertype`, empty ⇒
  `Nil[0]`): `[1, 1.5]` ⇒ `Number`, `[1.5d, 2.25d]` ⇒ `Decimal(38,2)` (the Decimal-specific
  rule fires before `valueLub`), `['a', %2020-01-01]` ⇒ `Any`, `[$p.age, $p.age]` ⇒
  `Integer`. Only the TDS-row-cells twin (S7) disagrees.
* **`Typer.java:232`'s multiplicity default is provably dead** from Pure source text — the
  only "unreachable fallback" I could actually *prove* unreachable rather than merely fail
  to drive.
* **The poison/wall catch family** (`Compiler:688,698`, `ModelIntegrity:79,269`,
  `MappingNormalizer:313,326,398`, `ClassSources:722,910`) all write to a sink the use-site
  re-reads — verified per site; these are deferral, not suppression.
* **Ordinary relational typing is sound in every shape I ran**: `project`, `extend`,
  `groupBy`, `sort`, `limit`, `filter`, association navigation, quoted column names
  (`~['My Col': …]` round-trips through `groupBy` and `extend` correctly), legacy
  `project([lambdas],[names])`, `restrict`, `Float`/`StrictDate`/`String`/`Integer`
  columns — I read the declared type against the delivered Java class and multiplicity for
  every shape listed here and found no mismatch. (Scope caveat: 157 of the 247 generated
  fuzz queries executed end-to-end, but the fuzz driver only records the declared type and
  the delivered values; it does not itself assert conformance — the conformance checks were
  done by hand on the shapes above and on the five unsound repros, not on all 157.)
* **`Type.PrecisionDecimal`'s compact constructor does validate `scale ∈ [0, precision]`**
  (`Type.java:160-167`) — the scale-0/scale-2 unsoundness (V6/V7) is a *choice of scale*,
  not a broken invariant in the record itself.

## NOT COVERED

* **Scope**: `core/src/test/java` (~354 files) and the `nlq/`, `pct/`,
  `parser-equivalence/`, `tools/engine-runner/`, `experiments/` modules were **not**
  censused. They cannot type a user query. This is the 897-vs-543 gap in the brief.
* **9 of the 34 non-model-derived sites are UNPROVEN** (listed in §3): `Lowerer.java:1414`,
  `Typer.java:1652/1657/2926`, `PctTdsWrap.java:114`, `NormalizeFolds.java:52`,
  `CastPolicy.java:145`, `SqlTyping.java:470/905`. I found no input driving them within
  budget; I did not prove them dead either (only `Typer.java:232` is proven dead).
* **The 2,093 fallback *operators* were triaged, not all individually read.** 1,032 are
  proximity-flagged `type_related`; of those, `census3.py` extracted the actual defaulted
  expression for all 1,711 fallback-value sites and only 19 had a type-constructor value —
  those 19 plus the census5/census6 unions (54 total) were hand-read. The remaining
  type-*named*-context sites (301 where a `null`/empty is assigned to something whose name
  mentions type/column/schema/fqn) were bucketed by package and file but **not** read one by
  one; the dominant packages there are `resolver` (94) and `model` (40).
* **The full JUnit corpus was not run** — `junit-platform-launcher` is absent from the
  provided classpath and the brief forbids `mvn`. Running it under the instrumented
  classpath would have been the highest-yield reachability experiment available and would
  likely convert several UNPROVEN rows.
* **Only DuckDB was exercised.** SQLite and H2 dialects were not driven, so the 47 internal
  throws in `com.legend.sql.dialect` (100% internal, including the 25 `DialectCapability`
  ⊂ `IllegalStateException` sites) have **no** reachability data.
* **Multi-statement / `executeInDb` effect paths** (`StatementExecutor`, 49 internal
  throws) were only exercised through the single-read raw-grid arm.
* **Milestoning / temporal (`TemporalFrame`, 23 type-named null defaults), graph fetch,
  and mapping-inheritance** paths were not driven.
* The `SUM(t1.AMT, 1.0)` invalid-SQL emission found incidentally in §4c
  (`$p.addresses.amt->toOne() + 1.0d`) is a lowering bug outside this audit's scope; it is
  reported here so it is not lost, but not investigated.
