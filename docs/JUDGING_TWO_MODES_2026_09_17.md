# Judging in two modes — the design (2026-09-17)

**Decision (user, 2026-09-17):** the test harness judges in ONE of two modes per run, never
mixed within a verdict. HOST mode: a small Java comparator, complete on its own, the
reference. DATABASE mode: every comparison a SQL predicate over canonical spellings, the
assert functions as platform natives, a test function as one statement — the product goal.
A differential gate runs both over the same corpus and requires the same verdict per
assertion. This replaces today's mixed verdict (the byte channel when it claims a pair, the
Java lattice otherwise, a census of their disagreements).

Companion documents: docs/NUMERIC_CHARTER_2026_09_17.md (the three numeric rules, the
compiled reference), docs/JUDGE_INVENTORY_2026_09_17.md (every judge that exists today),
docs/NUMERIC_ENVELOPE_CENSUS_2026_09_17.md (the measurements that led here).

## 1. The one rule both modes depend on — kind is decided in the query

Every result column carries the kind the model declared. At the OUTERMOST select of every
plan, a column declared Float whose value the database computed in another kind is cast to
DOUBLE; Decimal stays the database's decimal; Integer stays an integer. The JSON builder and
the text builder inside a query apply the same rule at their two sites, keyed by the
DECLARED type — never by whether the tree happens to know the wire type. Inside the query the
database's own arithmetic stands: bare literals, the engine's spellings (`avg(1.0 * x)`,
division's operand cast — Pure's `divide` returns Float).

This is the product's rule (numeric charter Rules 1 and 2, compiled reference: a compiled
Pure Float IS `java.lang.Double`). It is the precondition for BOTH modes: after it, no judge
decides what a Float is, because every value arrives with its declared kind. Every failed
attempt of 2026-09-17 was a judge deciding kind from the carrier.

## 2. HOST mode — the reference

One Java class decides every equality. It receives two values that already carry their
kinds and answers the engine's own rules, literally:

- same primitive kind, then equal by value: Integer exact; Decimal by numeric compare;
  Double exact, with ONE declared leniency for values the database computed through its C
  math library (two units in the last place of the larger magnitude), COUNTED every time it
  fires; String, Boolean, dates exact; enums by name;
- collections: ordered or multiset as the assertion says (the engine's `assertSameElements`
  = sort then ordered);
- JSON documents: parsed, numbers by value within kind, null ≡ missing, the root array as a
  multiset where the result carries no order (the engine's service-test comparator);
- class instances: by key tree where keys exist, by construction identity otherwise; maps by
  sorted entries.

A shape it cannot compare FAILS the test as UNJUDGED with the shape named. Host mode never
runs SQL to judge, never converts a value, never consults a wire type. It is what the engine
runs (its assert seam and its service-test comparator are Java), so it can be proven against
the equality-worlds fixture and the engine's published manifests. Target size: about a
hundred lines of decision; the rest is today's code deleted.

## 3. DATABASE mode — the product

Every comparison is a SQL predicate over the canonical spelling of each side (the byte canon:
a Float spells its Float form, a Decimal its scale-preserving form with its kind mark, an
Integer bare, temporals prefixed, strings quoted — six mutually disjoint spellings; instances
by key tree or construction identity). The assert functions are platform natives lowered to
SQL: `assertEquals`, `assertSameElements`, `assertEq`, `assertEqWithinTolerance`,
`assertJsonStringsEqual`, `assertContains`, `assertSize`, `assertEmpty`, `assertInstanceOf`,
`assertIs`, `assertError` — each a boolean over spellings raising with the engine's message on
failure. A test function lowers whole: lets as common table expressions, queries as
subqueries, asserts as guards, ONE statement. The same declared leniency for library-computed
doubles is a SQL predicate, counted. A shape the canon cannot spell FAILS as UNJUDGED with the
shape named; no Java verdict fills in. Database mode never judges in Java.

The service-test corpus goes through the same door: `EqualToJson` is one more assert native;
the expected document is a literal, our result is already built as JSON by the query, and the
comparison runs in the database with the engine's rules. The multiset comparison of nested
documents is the hard part of that native and gets its own design leg.

## 4. The differential gate

A permanent gate runs the corpus lanes and the stress lanes in BOTH modes and requires the
same verdict per assertion. A disagreement is a bug in one mode; host mode is the reference
because it is small and mirrors the engine. Unjudged shapes are listed per mode and driven
to zero; a shape unjudged in database mode but judged in host mode is a work item, never a
fallback.

## 5. Never mixed — enforced

A guardrail test pins: (a) exactly one Java class decides equality (host mode's comparator);
(b) no verdict path consults both modes; (c) the mode is a run-level switch, never a
per-assertion choice. Today's mixed machinery is deleted as each mode becomes complete: the
byte-verdict-of-record with host fallback (`finish()`), the dual-verdict census, the
ten-digit replay canon, the guarded conversion helper, the threaded declared-kind flag.

## 6. Order of work

1. **The root rule (§1) in the product** with the emission changes already proven
   (bare literals, no read-time Decimal→Float cast, `avg(1.0 * x)`), the JSON and text sites
   keyed by declared type, and the PCT adapter converting by the declared return type.
   Judge: both stress lanes (expect DuckDB 41 → 20, H2 +12, nothing new) and the chain, with
   today's mixed judges — the census §9 rows are expected to clear because their cause was
   kind-by-carrier. CI green before push.
2. **Host mode complete and small**: the one comparator; every lane routed through it in host
   mode; unjudged-as-fail; the deletions of §5 that host mode makes safe. Judge: the chain in
   host mode equals today's rosters, every difference explained by §1 or §2.
3. **Database mode complete**: the canon claims every shape or declines as unjudged; the
   assert natives, one family per leg; the single-statement test function. Judge: the chain in
   database mode; the differential gate against host mode.
4. **The differential gate as a permanent lane**, and the service-test `EqualToJson` native.

Each step: one chain per run, rows named before the edit, red means read not patch, CI green
before the next push (docs/GATES.md carries the record).

## Step 1 — LANDED 2026-09-17 (the kind decided once, in SQL, at the root)

**What landed.** Every query root whose declared type is Float converts to DOUBLE once, at
the outermost select (`Fold.declaredKindEnvelope`: scalar and map roots, relation roots by
declared column type; a literal collection element-wise, because H2 explodes an UNNEST into
a UNION and a cast around it has no placement). Float literals render BARE and their IR fact
says what the wire is (`SqlExpr.FloatLit` types itself DECIMAL of the digits' precision), so
the envelope sees a DECIMAL and converts it. A Number-DECLARED native result has ONE owner
for its kind, the typer (`NumberKinds.refine`: the reference's operand-kind join — any
Decimal → Decimal, all Integer → Integer, else Float); the envelope is its one consumer.
The DOUBLE facts are the platform's; every dialect delivers them on its wire (H2 casts its
DECFLOAT average to DOUBLE itself: `H2AvgDelivers`).

**What it cost, and where it was paid.**

- Corpus DuckDB: 108 fail, EXACT — three sql-text rows were never runnable (the shared
  fixture and an on-demand fixture both create an order table that DuckDB folds to one
  name) and passed at HEAD only by text match; the runner now runs an on-demand fixture on
  its own connection in an attached aside catalog, the session's tables first on the search
  path (`TestObserver.isolateFixture`, `DuckWorkspaces`); the H2 mirror follows as a schema
  through the seed ledger; the H2 lane uses a schema aside on its session. Five datetime
  plan-text rows reach the plan referee through a helper function for the first time
  (`SqlTextVerdicts.lookThrough`: the platform's own inliner, parameters bound to the call's
  arguments) and are judged by rows. Eleven helper-shaped plan asserts are COUNTED as
  text-decided for the first time (ceilings re-pinned with the reason). Four m2m2r plan
  tests join the unordered-chain register (their chains have no sort).
- Corpus H2: 440 → 430 fail — twelve group-by/average rows gained (the DOUBLE average),
  two lost to H2's own rendering of a DOUBLE under a Float declaration inside a string or
  JSON (`68` for `68.0`; a CASE over DOUBLE and INT read as DECFLOAT) — H2 lane, quick wins
  only by ruling; rows named in the roster.
- Stress: DuckDB 4,689 → 4,700 (floor moved); H2 4,612 → 4,622 (floor moved).
- Rejected on the way, each with its reason in the transcript: mimicking the engine PCT
  adapter's JSON channel; a per-function DOUBLE cast in the lowering; eviction of a seeded
  store from the memo (thrash: 616 → 3,409 setup runs); per-store catalogs routed per query
  inside the product executor; an unconditional root cast with a dialect elision pass
  (broke the DB2 text goldens and the TDS text of a DECIMAL wire under a Float root, which
  the engine keeps as `72.40`).

**Next.** Steps 2–4 as written above: the host-mode comparator, the database-mode canon and
the assert natives, the differential gate.

## Step 2 — the plan (written 2026-09-17, before any edit)

**The one class.** `com.legend.exec.Equality` — the host-mode judge, the engine's
`EqualityUtilities` rules (interpreted runtime) and its two JSON comparators, in one place.
It receives VALUES PAIRED WITH THEIR DECLARED KIND (`Equality.Typed(value, Type)`): the
caller (the corpus referee, the service-test runner) already knows each side's declared Pure
type — the root type of the assert's arguments, a collection's element type, a grid's column
types. An unrefined `Number` declaration takes the carrier's runtime kind, as the engine's
runtime does. Entry points, each returning `null` for equal or the first-difference
narrative, never a boolean the caller re-interprets:

- `scalar(Typed e, Typed a)` — same kind, then by value: Integer exact; Decimal by the
  engine's assert-seam `getValue().equals` (SCALE-SENSITIVE — `3.0D ≠ 3.00D`, the
  equality-worlds fixture's World 1; §2 above said "numeric compare" and was wrong); Float
  exact, then the ONE declared leniency (2 ULP of the larger magnitude,
  Double×Double, finite), COUNTED through `Leniency`; String/Boolean exact; dates by the
  temporal literal; enums by name; class instances by key tree (the caller restricts to keys
  today — `restrictToKeys` stays the caller's), maps by sorted entries.
- `ordered(List<Typed>, List<Typed>)` / `sameElements(...)` (sort, then ordered — the
  engine's `assertSameElements`).
- `grid(rows, columns kinds, ordered|multiset)` — TDS rows; cells through `scalar`.
- `pureJson(e, a)` — `assertJsonStringsEqual`: the Pure JSON model's equality (a JSONNumber's
  kind is part of its value: `68` ≠ `68.0`).
- `serviceJson(e, a, unorderedRoot)` — `EqualToJson`: the engine's `JsonNodeComparator`
  (numbers by `decimalValue` compare, kind-blind; null ≡ missing; root array as a multiset
  where the result carries no order), plus the referee's 2-ULP policy through `Leniency`.
- Any other shape → `Unjudged(shape)`: the assert FAILS naming the shape.

**The switch.** `-Dlegend.judge.mode=host|database` (run-level; default UNSET = today's
mixed verdict-of-record until step 3 lands). In host mode `AssertVerdicts.finish` takes the
host verdict only; the byte channel still runs as a CENSUS (`CanonicalDivergence`) and never
decides. A per-assertion choice is impossible by construction: the mode is read once.

**Routed through it (and what that deletes):** `AssertVerdicts` ASSERT_EQUALS /
ASSERT_SAME_ELEMENTS / ASSERT_EQ / tdsRowValuesVerdict / ASSERT_JSON_STRINGS_EQUAL;
`ServiceTestRunner` EqualToJson. Deleted once routed: `PureAsserts.equalScalar` (the
carrier lattice) and the threaded `floatDeclared` flag and its overloads;
`TdsCompare.rowTupleMultiset`'s inner scalar compare and `ulpOnlyCellDrift` (the leniency
has one home); `JsonCompare.wireTree/document/documentUnorderedRoot` (moved, not copied);
`TestAssertions.diff`'s numeric leaf. `PureAsserts` keeps only what is not equality
(assertSize, assertInstanceOf, repr). OUT OF SCOPE, said so: `H2Verify.norm` (B1) is the
SQL-text replay's cross-engine tolerance — database against database, not host against
golden — it stays with the replay arm and is step 3's; A7 `assertEqWithinTolerance` keeps its
own arithmetic (a tolerance assert is not equality).

**The guardrail (§5a).** One test: `Math.ulp` and the leniency counter appear in
`Equality.java` only; every equality entry (`Equality.*`) is called only from
`AssertVerdicts` and `ServiceTestRunner`; `PureAsserts.equalScalar` no longer exists.

**Judge.** The chain twice: default mode (today's rosters, EXACT — nothing moved) and
`legend.judge.mode=host` (rosters EXACT or every difference explained by §1/§2 and pinned
in this doc). Ledger: `Equality.java` is a NEW evaluator row (THE judge, justified here);
`PureAsserts`, `AssertVerdicts`, `TdsCompare`, `JsonCompare`, `TestAssertions` shrink.
Two legs: (2a) the class, the switch, the routing — both chains; (2b) the deletions and the
guardrail — both chains. No emission changes in either.

## Step 2a — LANDED 2026-09-17 (the class, the switch, the routing)

`com.legend.exec.Equality` decides every host equality: scalars by DECLARED kind (a side's
values paired with its argument's type; Number / class / none take the carrier's runtime
kind), Integer exact, Decimal by the engine's scale-sensitive equals, Float exact then the
ONE counted 2-ULP leniency (`Equality.ulpFirings`), dates by the literal, ordered lists and
same-elements (sort, then ordered), row multisets, structural instances / maps, and the two
JSON rules (`pureJson` for `assertJsonStringsEqual`, `serviceJson` for `EqualToJson`). The
carrier lattices in `PureAsserts`, `TdsCompare`, `JsonCompare`, `TestAssertions` are
DELEGATIONS now (PureAsserts 336 → 242 lines, JsonCompare 110 → 25, TdsCompare 444 → 425;
Equality 417); `-Dlegend.judge.mode=host` makes `AssertVerdicts.finish` take the host verdict
only (the byte channel reports as a census); unset stays today's mixed verdict.

**Judged.** Default mode: corpus DuckDB 108 EXACT, H2 430 EXACT, stress 4,700 / 4,622, PCT
lanes and Channel B green (no dual-verdict disagreement). HOST mode: DuckDB 108 EXACT;
H2 428 — two rows PASS in host mode that fail in mixed mode
(`mapping::boolean::testProject`, `projection::filter::in::testInWithDynaFunction`: "grid
canonical renders differ (host lattice agreed)" — the H2 grid canon's TEXT differs while the
values are equal; §1 says the byte channel is the product's judge and it is wrong here, a
step-3 item). Two things the host judge EXPOSED on the way, both fixed in the product's
typer, not in the judge: `NumberKinds.refine` was refining a GENERIC native whose type
variable resolved to Number (`sort` over a mixed list — now the signature's declared return
type decides), and refining SELECTORS (`max(1.23, 2)` is the Integer 2 — now only the
arithmetic natives plus/minus/times/rem/abs/sum join). Both are engine facts the mixed judge
had hidden behind the byte canon.

**Next, 2b:** the deletions (`PureAsserts.equalScalar` and the float-declared overloads, the
second ULP site `TdsCompare.ulpOnlyCellDrift`, `JsonCompare` as a class), grid cells by
COLUMN kind, and the §5a guardrail.

## Step 2b — LANDED 2026-09-18 (the deletions, grid cells by column kind, the guardrail)

**Deleted, not delegated.** `PureAsserts` no longer has an equality API: `equalScalar`, the
`floatDeclared` overloads and the carrier lattice are gone (242 → 229 lines; it keeps
assertSize / assertInstanceOf / repr and one private `equal` that calls Equality).
`TdsCompare.rowTupleMultiset`, `ulpOnlyCellDrift`, `rowEquals`, `rowsPositional` are gone
(425 → 366); the leniency-only test the verdict needs is `Equality.differByLeniencyOnly`.
`JsonCompare` is deleted as a class (its one test became `EqualityJsonUnorderedRootTest`);
`TestAssertions.equalToJson` is `Equality.serviceJson`. `Math.ulp` has ONE home.

**Grid cells by column kind.** `Equality.grid(cells, columnKinds)` pairs every cell with its
column's declared Pure type (the `Tabular` column's `pureType`), cycling per row; both the
verdict (`AssertVerdicts` tdsRowValues arm, `TdsCompare.grids`) and the message go through it.
This exposed a question the mixed judge had hidden: `mapping::tree`'s
`Account.number : String[1]` is mapped to `accountTable.id INT`, the grid column says STRING,
the cell is the Integer 11 and the test asserts `[11, 'OrgName3']`. The product is RIGHT and
so is the test: the engine's boundary (`dataTypeTransformer`,
core_relational `execution_relational_execute.pure:299-320`) converts a wire cell only under a
NUMERIC declaration (Float/Number `* 1.0`, Decimal `toDecimal`) — dates and Booleans arrive
typed — and EVERY other declaration (String, class, none) is the identity: the cell keeps the
wire's kind. A declaration is not a cast. So `Equality.effectiveKind` is that rule, cited in
its javadoc: a Float/Decimal/Integer declaration over a numeric carrier decides the kind;
anything else takes the carrier's kind. (Reverting to "declared kind always" would have made
the judge stricter than the engine and failed two engine-green rows — the harness-side patch
the user rejected on 2026-09-18 was a "numeric family" gate with a wrong story, "metadata the
judge does not trust"; the metadata is trusted, the engine simply does not convert Strings.)

**The §5a guardrail** (`VerdictChannelRegisterTest`): `Math.ulp` appears in `Equality.java`
only; every `Equality.*` reference lives in a CLOSED set of callers (AssertVerdicts,
PureAsserts, TdsCompare, TestAssertions, and Equality's own three tests) — a new caller
fails the test by name. (The plan said "AssertVerdicts and ServiceTestRunner"; the runner
reaches Equality through TestAssertions, which is the set's member.)

**Judged.** Register tests green (ledger: Equality 445, PureAsserts 229, TdsCompare 366,
AssertVerdicts 1840; JsonCompare row removed with the file). Default mode: DuckDB 108 EXACT,
H2 430 EXACT. Host mode: DuckDB 108 EXACT; H2 428 — the same two rows as step 2a
(`mapping::boolean::testProject`, `filter::in::testInWithDynaFunction`: the H2 grid canon's
TEXT differs while the values are equal — step 3's byte channel). No emission change.

## Step 2 closed — HOST is the only verdict of record (2026-09-18)

The plan (§2 "the switch") kept the mixed verdict as the unset default "until step 3
lands". Host mode was complete after 2b and matched every roster, so the mixed default had
nothing left to protect; the user ruled: host only, now. `JudgeMode` has one value (HOST;
DATABASE is step 3's and is still not selectable), `AssertVerdicts.finish` takes the host
verdict and nothing else, the byte channel's verdict reaches only the census probe, and the
five "byte-verdict: canonical renders differ" failure messages are deleted (a message for a
judgment nobody makes). AssertVerdicts 1840 → 1822 lines.

**Judged.** Chain green, quiet parallel wall ≈ 4m24s. Rosters: DuckDB 108 EXACT unchanged;
H2 fail roster 430 → 428 (the two rows above PASS — host mode's verdict is the engine's;
the H2 grid canon text that failed them is step 3's first named database-mode bug); the H2
unordered register gains `filter::in::testInWithDynaFunction` (it passes through the
order-lenient retry on H2 exactly as on DuckDB, where the register already listed it — it
was absent from the H2 register only because it used to fail outright there). Stress
4,700 / 20, PCT lanes and Channel B unchanged.

**Step 3's homework is written before any edit:** docs/DATABASE_MODE_HOMEWORK_2026_09_18.md
(the execution model as it is, the corpus measured — 2,687 of 2,767 test functions use
`let`, 2,339 lets are `execute` frames and those are the only CTE population — the prior
designs and what each decided, eight decisions proposed, five legs, the traps). Nothing in
step 3 starts until §3 of that doc is ratified.
