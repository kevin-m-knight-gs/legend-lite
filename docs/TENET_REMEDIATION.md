# Tenet Remediation — "Java orchestrates, the database executes"

> **Companions:** `docs/ARCHITECTURE_REMEDIATION.md` fixes *shape*.
> `docs/CORRECTNESS_REMEDIATION.md` fixes *answers*. This one fixes *who does the work*.
> Separate queue, separate lifecycle. Start at **V0** — it is four paragraphs of doc text
> and it is what makes every other item in this file reviewable.

**Source:** eight-agent tenet-conformance audit run at `16ee3358`, re-verified against
`b9746692`. Every agent classified each site as **VIOLATION** (Java computes a value the
database should have) / **COMPENSATION** (Java repairs or replaces a platform capability) /
**BOUNDARY** (a crossing that must exist somewhere, done here) / **ORCHESTRATION** (not a
value computation at all).

**Confidence column:** `VERIFIED@<sha>` = read at that commit this cycle. `LIKELY` = derived
from a verified adjacent fact, not independently re-read. Treat `LIKELY` as a lead, not a bug.

---

## 1. Verdict, and the meta-finding

**The tenet holds in the query compiler, including in the places it was hardest to hold.**
`Scalars.java` is ~3,451 lines of `SqlExpr → SqlExpr` that never returns a Java value, and it
composes even Pure's recursive `toString` print-forms in SQL. `StoreResolver` has **no
`Connection` field anywhere** — nested resolution is typed→typed and its product is a subquery
inside the one statement. Graph fetch builds the object graph *in SQL* at every depth with no
host-side fallback path; `GraphEmission` never touches a value. Cross-store lowers to one plan
where the real engine brokers object-by-object across two executors. A plain mapped class query
issues exactly **one** statement.

Two of eight auditors returned **no VIOLATION and no COMPENSATION rows at all** for their
subsystem, having looked for both.

**The tenet fails at the edges it was never enforced on** — ingress (CSV/JSON), egress (decode,
rendering), and the test-data generator — and it fails in three repeating shapes, catalogued in
§4.

### 1.1 The meta-finding: five self-claims falsified, every one flattering

This is the same shape as `CORRECTNESS_REMEDIATION.md` §1 (every scoreboard bucket mislabeled,
always in the favourable direction). It is listed first because it is the cheapest thing to fix
and because these headers are currently **load-bearing for reviewers** — an auditor who trusts
them stops looking.

| Claim | Reality | Confidence |
|---|---|---|
| `docs/ENGINEERING_LOG.md:123-125` — the harness *"contains ZERO evaluation compensation (audit-verified)"* | True of the main assert path. Globally false: four compensations, one of them concealing a wrong-answer bug (§3 V1.5) | `VERIFIED@16ee3358` |
| `RawSqlBoundary.java:20-21` CONTRACT — *"text-level translation of corpus-authored statements only"* | **3 of 4 call sites** feed it SQL that Java itself generated | see §4.1 |
| `Json.java:11` — *"THE minimal JSON reader (one for the platform)"* | Four hand-written JSON parsers exist, one of them **inside the type checker** | `VERIFIED@16ee3358` |
| `Executor.java:146-153` — value-consulting heuristics *"were audited out: the kind must travel FROM SQL, never be guessed after it"* | The midnight heuristic decides StrictDate-vs-DateTime by asking whether the time is 00:00 | `VERIFIED@16ee3358` |
| `TestDataGenerator.java:43-45` — lists `hashStrings` among the LOUD walls | `:793` applies `hashString` to a value read from a `ResultSet` | `VERIFIED@16ee3358` |

The **code comments are unusually honest** — nearly every finding in this document was reachable
because someone wrote down what they did and why, including `Ddl.java:14-18`, whose candour is
what exposed `RawSqlBoundary`'s contract as stale. The problem is confined to the *invariant
headers*, which assert a settled discipline and have not been re-checked since they were written.

**V0.1 — Correct all five. One commit, no code change.** Each becomes a statement of what is
true plus a pointer to the item in this file that would make the original claim true again.

### 1.2 What landed during this audit: a second execution channel

Between `16ee3358` and `b9746692`, six commits added **~1,036 lines of host-side evaluator** —
`exec/HostEval.java` (+878) and `exec/DbMetaData.java` (+158) — dispatched from
`StatementExecutor.java:179` → `:438-460` (`hostChannel`). By gate 2 it executes **recursive
corpus Pure functions host-side**, with `TypedUserCall` call frames on the natural Java stack,
and language arms for `TypedIf`, `TypedFilter`, `TypedSlice`, `and`/`or`/`not`/`eq`/`equal`, `in`,
`isEmpty`, `slice`, `toLower`, `indexOf`, `instanceOf`, lazy let-bound variables, and property
reads over `HostInstance`.

Tenet #1's first sentence is *"No host interpreter."* Read literally, this is one.

**Read fairly, most of it is not a violation — and that is exactly the problem.** The channel
evaluates over the **SQL metamodel** (`DynaFunction`, `Literal`, `Alias`,
`FreeMarkerOperationHolder`, `VarPlaceHolder`) and `DatabaseMetaData`, serving
`sqlQueryToString`/`dbSpecific`/`debugPrint` and the `fetchDb*MetaData` family — all four signatures
taken from real legend-pure (`platform_store_relational/functions.pure:34-41`). That is model-space,
not data-space, and by §2's V0.2 clause it belongs in Java. It is disciplined: every unhandled shape
is loud, `TypedMatchRuntime` *"walls loudly"* outside the host channel, the constructible classes are
a **curated set** (widening it to "any native class" stole 21 passing constructions from the K path
and was reverted), and each gate ran the full sweep with family deltas empty.

**So the code is defensible and the tenet does not say so.** That is not a pedantic gap. It is
1,036 lines of new execution machinery whose legitimacy currently rests on a distinction no
document makes — which is why V0.2 is promoted to the top of the queue.

**Three things in it do warrant action:**

- **The `executeInDb` READ path crossed from model-space into data-space** (`b223661d`). At
  `16ee3358` reading an `executeInDb` binding **refused loudly** rather than materialize a host-side
  `ResultSet`. Now `DbMetaData.query:87-97` runs **arbitrary corpus SQL** on a second in-memory H2
  and materializes the grid via `rs.getObject(i)` (`:126-157`), and `HostEval:376-382,384-507`
  computes over those rows in Java — `fold`, `map`, `concatenate`, `at`, `first`, `size`, `eq`, `in`,
  `slice`. Rows that crossed a `ResultSet`, combined and filtered in Java. By the §6 rubric that is
  Q1-yes → Q4-yes → **VIOLATION**, and it replaced a loud wall to get there. This is the one arm
  that needs re-siting rather than authorizing.
- **The dispatch predicate is load-bearing and has already collapsed once.** Gate 2 records
  `2096 → 408` mid-gate — a **1,688-test collapse** — because the pre-inliner `hostChannel` guard
  stole every corpus family's setup statements onto the H2 replay target. The gate caught it and the
  fix is in. But a channel whose selection predicate can silently divert 80% of the corpus deserves
  an ArchUnit-level invariant, not just a passing sweep.
- **The growth policy is the actual risk.** Gate 2's own words: *"Language arms grown per wall, each
  loud."* That is how the channel went from 4 metadata natives to ~25 language constructs in two
  commits. §9 argues legend-lite can afford a stricter tenet **because it is allowed to fail** — an
  interpreter that grows an arm every time it hits a wall is precisely the mechanism by which that
  affordance is spent, one loud wall at a time. The engine's `Library.java` (1,849 lines) and
  `PureDate.java` (2,097 lines) are what this looks like at maturity.

**V0.6 — Write the host channel's charter into the tenet** alongside V0.2: what it may evaluate
(model-space), what it may never evaluate (anything that crossed a `ResultSet`), and what the bar is
for adding an arm. Without it, "grown per wall" has no stopping condition.

---

## 2. V0 — The tenet text itself (do this first)

Three cases arose in every audit where reviewers could not apply the tenet as written, because
the text is silent on the distinction that decides them. This is not pedantry: the largest
unlabelled surface in the tree (§2.1) is convicted by a literal reading and exonerated by the
intended one, and there is no way to tell which from the tenet.

### V0.2 — Model vs data

`MetamodelWalk.java` (1,293 lines, in `exec/`) is self-declared *"HOST-side evaluation of
store-METAMODEL navigations"* and evaluates genuine Pure expressions in Java —
`db.schemas->view(...)->columnMappings->inferRelationalType()` — with ~20 handle record types
dispatched from `StatementExecutor`. Read literally, tenet #1 convicts it. But the database
structurally *cannot* answer: the store metamodel is compiler data, not tables.

> **Add:** *"Value evaluation" means evaluation over **data**. Evaluation over the **model**
> (M3 / store-metamodel reflection) is compilation and belongs in Java — provided it is a
> declared, closed surface with loud walls on every unrecognized shape.*

`MetamodelWalk` already satisfies the proviso (*"Every unrecognized shape returns null… never a
silent wrong answer"*). Every auditor tripped on it, which is the definition of a spec defect.

**This is no longer only a documentation gap.** §1.2's host channel — 1,036 lines added during this
audit, and growing per wall — stands or falls on exactly this clause. Until it is written, there is
no principled answer to "may `HostEval` have an arm for *X*?", and the honest reviewer's only
options are to convict working code or to wave it through. Write the clause **before** the next arm
lands.

### V0.3 — Compile time vs run time

`StaticFold.java` (562 lines) implements `plus`, `filter`, `sortBy`, `if`, `makeString`,
`removeDuplicates`, `indexOf`, `concatenate`, `removeAll` — precisely the vocabulary that would
be a violation at runtime. It is justified: it implements the
`<<functionType.NormalizeRequiredFunction>>` stereotype, which is **real engine doctrine**
(verified in `legend-engine/.../core_dataquality/generation/dataquality_relation_helper.pure:83`
and `.../core_external_format_json/functions/functions.pure:18`), and you cannot write a
projection list without evaluating the expression that computes the projection list. But the
tenet says evaluation "ALWAYS" lowers to SQL, with no time qualifier, so the text does not
sanction it.

> **Add:** *Evaluation that produces the **plan** is compilation. Evaluation that produces the
> **answer** is forbidden. Fold only what the plan cannot be written without, or what the parser
> desugared — never to save the database work.*

The project's instinct is already correct here and is recorded as a negative precedent at
`RelationPredicates.java:33-37`: a value-multiplicity fold *"broke three correlated-count pins…
**Documented residual, not silently folded.**"*

### V0.4 — Materialization

*"Never a host object graph"* is stated about `Result`, but `ExecutionResult.Tabular` holds every
row with no ceiling. Two readings: (i) "object graph" means a **navigable, typed** graph, so a
flat row list is fine; (ii) any full materialization is one. The code takes (i);
`ARCHITECTURE_REMEDIATION.md:358-361` takes (ii). They imply different fixes — nothing, versus a
streaming `Tabular` plus an `ExecPolicy`. The tenet must say which. See §5 V3.3.

### V0.5 — "Orchestration handle" names three different things

(1) `Result`/`ExecFrame` — a *query awaiting composition*. (2) Connection/Runtime/ConnectionStore
values — a *name for the ambient environment*, evaluating to `Scalar(null, T)` under the
type-driven handle rule. (3) `executeInDb`'s `ResultSet` — an *opaque token*. Only (1) matches
the slogan; (2) and (3) are "typed nothing," a different and also-legitimate idea. The
type-driven handle list is exactly the kind of list that grows silently, and the tenet gives no
criterion for adding to it.

---

## 3. V1 — Violations, ranked

Ranked by *(is the answer silently wrong)* × *(is the capability structurally absent)*, not by
line count.

| # | Site | What Java computes | The database alternative | Confidence |
|---|---|---|---|---|
| **V1.1** | `testdatagen/TestDataGenerator.java:790-799` | `rs.getString(i)` → **Java SHA-256** → character scrub, over real table rows | `sha256()`, `replace()`, `substr()` — all native | `VERIFIED@16ee3358` |
| **V1.2** | `exec/Executor.java:371-409` | A host-side **UNNEST**: iterates a many-valued cell and emits one `Row` per element | `UNNEST` / `LIST_TRANSFORM` in the plan | `VERIFIED@16ee3358` |
| **V1.3** | `exec/JsonSourceFrame.java:75` + `:114` | Parses the `data:application/json` payload in Java, then `String.valueOf(v)` **flattens every typed value back to text**, which `Scalars.tdsCell` re-parses | `DuckDb.java:100-101` already does `unnest(CAST(… AS JSON[]))` — same module | `VERIFIED@16ee3358` |
| **V1.4** | `exec/CsvSeed.java:33,60,83,95,126` | Hand-splits CSV; types each literal from a **regex on the token's text shape while holding the resolved column type**; `:126` falls through to a silent `VARCHAR` | `read_csv_auto` / `COPY … FROM`, or a typed `PreparedStatement` | `VERIFIED@16ee3358` |
| **V1.5** | `harness/TestBody.java:2462-2507` + `:3014`,`:3120` | Strips `->toCSV()`/`->toString()` off the query, executes only the receiver, renders in Java — **concealing that the platform mis-compiles the same expression** | Register both as platform lowerings | `VERIFIED@b9746692` — see §3.1 |
| **V1.6** | `harness/TestBody.java:1572-1614` | Executes a four-operator Pure pipeline — `sortBy`, `map`, string `plus`, `joinStrings` — in Java | Model the census as a relation so the reads lower | `VERIFIED@16ee3358` |
| **V1.7** | `exec/Executor.java:159-167` | Recovers a numeric **kind** by re-parsing the DB's own print form (`endsWith("D")`) | Carry the kind as a typed sibling column | `VERIFIED@16ee3358` |
| **V1.8** | `exec/Executor.java:190-260` | Hand-rolled JSON scalar decode and `\u` unescape on DB output | `json_type()` — **already spelled** at `Spellings.java:56` — and `->>` | `VERIFIED@16ee3358` |
| **V1.9** | `exec/Executor.java:477-501` | `pureOfSqlType(String)` — dispatch on the JDBC type **name**, with `startsWith("DECIMAL")` | `getColumnType()` int codes; a sibling already uses them | `VERIFIED@16ee3358` |
| **V1.10** | `exec/Executor.java` midnight heuristic | Decides StrictDate-vs-DateTime by asking whether the value's time is 00:00 — under the docstring that says such heuristics were audited out | Root cause is `PureSql.java:64` mapping both `DATE_TIME` and `DATE` to `TIMESTAMP` | `VERIFIED@16ee3358` |
| **V1.11** | `compiler/spec/TdsChecker.java:213-355` | A 143-line **fourth** JSON parser, inside the type checker | Delegate to `Json.parse` (§3.2) | `VERIFIED@16ee3358` |
| **V1.12** | `testdatagen/TestDataGenerator.java:751-804` → `TestDataGenForm.java:393-402` | DB rows → Java strings → CSV text → spliced back into the AST as a `CString` → re-seeded as INSERTs → queried. A closed **DB → Java → DB** round trip | Faithful port of engine `generateTestData` semantics; the CSV *is* the product | `VERIFIED@16ee3358` |
| **V1.13** | `engine/exec/Row.java:163-182` | Text-parses a `STRUCT(a VARCHAR, …)` type **name** to recover field names | `struct_keys()`; `core`'s `Executor.unwrap` reads it from the typed layout | `VERIFIED@16ee3358` |
| **V1.14** | `lowering/EngineStyleH2.java:261-271` | Timezone arithmetic in Java, baked into the emitted literal | `SqlFn.TIMEZONE` exists and is used at `Scalars.java:3270` | `VERIFIED@16ee3358` |

**V1.1 is the purest breach in the repository** — Java computing a value from data the database
was already holding, using functions the database already has, in a file whose own header lists
that operation among its loud walls.

**V1.4 is a regression against this project's own remediation.** `ARCHITECTURE_REMEDIATION.md`
T3.1 removed exactly this silent-`VARCHAR` anti-pattern; `TestDataGenerator.java:1000` and
`Ddl.java:91-98` both carry the fix and its comment. The sweep hit two of three sites. In
`CsvSeed` the fallthrough silently swallows `BYTE`, `STRICT_TIME`, `LATEST_DATE`, and **every
`ClassType` including Variant** — which `Ddl.spell:88` maps to `JSON`.

### 3.1 V1.5 in full — the platform has no result-serialization surface, and lies about it

This is the most important single finding in the audit, because it is tenet #2's cardinal sin
(harness compensation) **concealing** a tenet #3 violation (a silent wrong answer). Two distinct
gaps hide behind one strip.

**Gap A — `toCSV` does not exist.** Exhaustive search finds it nowhere in
`core/src/main/java/com/legend/` outside `com.legend.harness`, nowhere in `engine/src/main/java`,
and in no `.pure` resource. It is absent from the native signature table (`builtin/Pure.java`),
from `Scalars`' rule registry, and from `DuckDBDialect.renderFunction`. Reaching the Typer it
throws `unknown function 'toCSV'`.

**Gap B — relation `toString` is accepted and silently mis-lowered.** The only `toString` native
is `Pure.java:1816`, `toString(any: Any[1]): String[1]`. `Any` is an **unchecked top type in both
halves of the inference kernel** — `InferenceKernel.java:75` accepts anything in unification and
`:935` scores it 0. A relation value has multiplicity `[1]`, so `Relation<…>[1]` conforms to
`Any[1]` and **compiles**. It then lowers through the general relation-in-scalar-position arm at
`Lowerer.java:2755-2791`, which flattens a multi-column relation into a row-major cell-list scalar
subquery, and `Scalars.pureToString` (`:1909-1927`) falls through every arm to `Scalars.java:2662`:

```java
return new SqlExpr.Cast(x, PureSql.type(Type.Primitive.STRING));
```

**Confirmed empirically at `b9746692`,** by compiling main's `core/src/main/java` and running the
compiler and lowerer — note that the repo's checked-in `target/classes` is stale and gives a
*different, wrong* answer, so this must be reproduced from a fresh build.
`#>{db.T}#->select(~[ID, AGE])->toString()` type-checks as
`meta::pure::functions::string::toString → String[1]` and emits:

```sql
SELECT CAST((SELECT flatten(LIST([to_json(t1.ID), to_json(t1.AGE)]))
             FROM ( SELECT t0.ID, t0.AGE FROM T_PERSON AS t0 ) AS t1) AS VARCHAR) AS value
```

A JSON-variant cell flatten — never `#TDS\n   name,age\n…\n#`. The guard at `PureSql.java:122-123`
(*"a relation is a SOURCE, not a scalar SQL type"*) never fires, because this path never calls
`PureSql.type()` on the relation type.

Multiplicity does still bite one shape: `Person.all()->toString()` is correctly rejected
(*"expected at most one value, got many"*). Only relation-**valued** `[1]` receivers slip through —
**which is exactly the shape the corpus writes.**

**The harness strip is what keeps this invisible.** `TestBody.java:2459-2472` intercepts the tail
whenever the receiver evaluates to `Tabular`, so the mis-lowering has never been exercised through
the corpus. Corroboration: `#TDS` is emitted exactly **once** in all of `main/` —
`TestBody.java:3107`. Everywhere else in the platform it is *input* syntax only.
`ExecutionResult` has **no renderer of any kind**.

**Aggravating: the strip also weakens the comparison.** At `:2447-2456` the harness keeps the
`Tabular` and returns a `CSVJOIN:` marker specifically so the compare can apply multiset row order
and numeric tolerance — the comment cites row order and float ULPs. An
`assertEquals('<literal csv>', …->toCSV())` that the corpus wrote as **string equality** is
silently downgraded to a **structural grid comparison with tolerance**. Two independent weakenings
stacked on one compensation.

**Fix.** Register `toCSV` and a `RelationType`-receiver `toString` as platform lowerings — the
machinery exists (`Lowerer.java:2674-2688` already flattens the same shape; `Scalars.java:2645-2660`
builds the Pair render by nested `CONCAT`; a CSV render is `string_agg` over row-wise `concat_ws`).
**Narrow the `toString(Any[1])` overload so a `RelationType` argument cannot silently match the
scalar cast** — this half is required even if rendering stays in the harness forever. Then delete
the format knowledge from `csvCell`/`csvText`/`tdsStringEquals` and keep only the comparison
policy. *Keeping the structural comparison does not require keeping the renderer* — that is the
conflation to avoid.

There is a correct RFC4180 writer in the tree already — `engine/.../serial/CsvSerializer.java` —
but it sits behind THE WALL (`ArchitectureTest.java:49-56`), so `core` cannot reach it.

### 3.2 V1.11 in full — four JSON parsers, and the wrong one decides the type

`TdsChecker`'s parser is called from exactly one place and its output is a **`Type`**, not a
value — the value itself is never parsed in Java (`Scalars.tdsCell:2886-2890` emits
`CAST('<json>' AS JSON)` and lets DuckDB parse). By the rubric's own test that is legitimate
compile-time work, and `json_valid()` is **not** the right fix: reaching for a database to
type-check a literal would invert the compiler.

The violation is **duplication and drift**, and it is sharper than it looks:

- The javadoc claims *"Strict RFC-8259"* and it is not. `jsonNumber:326` accepts leading zeros;
  `jsonString:299` treats `\` as "skip two characters," accepting `\q` and every other illegal
  escape where RFC-8259 permits exactly eight.
- **The parser that decides the type is not the parser that reads the value.** Java's lenient
  parser says Variant; DuckDB's strict parser then receives `CAST('…' AS JSON)` and can reject it.
  The two acceptance sets differ, and the disagreement surfaces as a runtime cast failure on a
  type decision Java already committed to. A single parser cannot disagree with itself.

**Fix — one line:** `try { Json.parse(v); return VARIANT; } catch (…) { return STRING; }`, deleting
143 lines and collapsing the acceptance sets. Ledger the residual: `Json` is itself lenient
relative to DuckDB, so the disagreement narrows but does not vanish.

### 3.3 Added on main during this audit

All `VERIFIED@b9746692`. §1.2 covers the host channel as a whole; these are the individually
actionable sites.

| # | Site | Finding |
|---|---|---|
| **V1.15** | `exec/Ddl.java:79-104` `createTableStatementText` (new) | **Emits invalid SQL for space-containing column names, then gets silently mangled further.** It quotes only H2 *reserved words*, but corpus columns carry spaces — which is precisely why the older `Ddl.createTable:47-49` quotes everything. Observed at main: `Create Table T(…,Previous Fiscal Week Year VARCHAR(200) NULL,"order" INT NULL,…)` → `quoteCreateColumns` turns it into `…, "Previous" Fiscal Week Year VARCHAR(200) NULL, …`. Rewriting rather than failing. It is also a case where Java-**generated** text now matches `CREATE_HEAD` and is regex-rewritten — the exact thing §4.1's contract forbids |
| **V1.16** | `exec/DbMetaData.java:87-97`,`:126-157` + `exec/HostEval.java:376-382`,`:384-507` | The `executeInDb` READ path materializes arbitrary corpus SQL into `List<List<Object>>` and computes over it in Java. **Replaced a loud refusal** (§1.2) |
| **V1.17** | `exec/DbMetaData.java:102-124` `replay` | Java-synthesized `ALTER … SET NOT NULL` / `ADD PRIMARY KEY` executed on real H2 with **errors silently swallowed** — logged only under `LL_TMP_DEBUG`. A tenet #3 violation regardless of the tenet #1 question |
| **V1.18** | `engine/…/rcorpus/Runner.java:923-945` `unknownTypePull` | **Regex-parses `e.getMessage()`** — `"[Uu]nknown (?:type\|class)[:]? '([\w:]+)'"` — to drive a compile-retry loop at `:871-918`. Exception text is now load-bearing control flow; any reworded error silently disables the retry |
| **V1.19** | `exec/HostEval.java:209-213`,`:218-249` + `harness/TestBody.java:3184-3189` | `HostInstance.toString()` renders `^Class{props}` host-side and `toString` is `String.valueOf(v)` (`:492-495`) — the same rendering-has-no-platform-owner gap as V1.5. `hostEquals` is a **second structural equality implementation**, wired into `TestBody.wireEquals` |
| **V1.20** | `harness/TestBody.java:804-829` `assertContainsCheck` | New host-side membership test over `ResultSet`-crossed values via `wireEquals`. Consistent with declared comparator policy; noted so it is counted |

**`Lowerer.java`'s +95 is clean on this axis** — union-frame aliasing, distinct-over-concatenate
desugar, variant array literals for `Number` LUB. All SQL-IR level, no new host-side reads or
rendering.

---

## 4. V2 — The systemic patterns

Individual sites in §3 are symptoms. These three are the shapes that generate them.

### 4.1 V2.1 — Java writes SQL, then regex-rewrites its own output

`RawSqlBoundary`'s CONTRACT (`:17-25`, byte-identical at both refs) says *"text-level translation
of corpus-authored statements only."* Three of its four call sites feed it Java-generated SQL:

| Call site | SQL origin |
|---|---|
| `StatementExecutor.java:2657` (`executeInDb`) | corpus-authored **and** Java-generated — `CsvSeed.sqls` via the effectful-map arm, DDL-generator strings via `:2420-2428`/`:2479-2503` |
| `StatementExecutor.java:2721` | `Ddl.dropTableStatementText` — Java-generated |
| `StatementExecutor.java:2723` | `Ddl.createTableStatementText` — Java-generated |
| `Runner.java:1507` | `moduleDdl` → `Ddl.createTable` — Java-generated |

The narrower clause in the same header — *"never against platform-GENERATED SQL, which is
IR-rendered"* — is **true**; `dialect.render(plan)` output goes straight to `Executor.execute` and
never enters this class. So the header is a stale over-claim rather than a lie, and `Ddl.java:15-18`
openly documents the flow as intentional (*"ONE adaptation path for hand-written and model-derived
DDL alike"*). The two sentences contradict each other and one of them has to go.

*Changed on main:* `ddlStatementString` now produces the new engine-text forms
(`Ddl.createTableStatementText`/`dropTableStatementText`, `Ddl.java:79-104`) rather than
`Ddl.createTable`. Same violation, new producer — and the new producer has V1.15's quoting bug.

Two consequences make it more than a comment bug.

**The loop is self-inflicted.** `Ddl.spell:74` deliberately emits H2 `FLOAT` **so that**
`quoteCreateColumns:159` can rewrite it to `DOUBLE`. Java writes SQL wrong on purpose so a regex
can fix it.

**It contaminates the H2 advisory channel.** `RawSqlBoundary.record` captures every statement
*pre*-translation, and `TestBody.java:997-1003` documents that stream as *"H2-flavored BY
DEFINITION"* before `H2Verify.verify` replays it verbatim on real H2. The stream carries
`CREATE OR REPLACE TABLE` with DuckDB-native types from `CsvSeed`; a failed replay degrades to
`ADVISORY_MARKER` (`:1010-1013`, `:1038-1043`) — so row-verification **silently downgrades to a
hollow pass** rather than erroring. A tenet #3 violation reached through a tenet #1 one.

*Partially mitigated on main, and instructively so.* Commit `06ef5ae6` added a metadata-only side
channel (`META_RECORDER`, `RawSqlBoundary.java:54-59`) whose javadoc names this exact downgrade
risk — its first cut *"polluted the shared H2Verify stream and downgraded row-verified
testDayOfWeek to advisory — the gate caught it."* So the new PK/schema `ALTER`s stay out of the
replay stream. **The `CsvSeed` contamination is untouched**, and the fact that the same failure was
independently rediscovered and fixed for one producer while persisting for another is the argument
for fixing the producer rather than adding side channels.

**Nothing enforces the one-rewriter rule.** No test references `RawSqlBoundary` outside `Runner`;
neither `ArchitectureTest` nor `CodeShapeGuardrailTest` guards it; `docs/RUNNABILITY_PLAN.md:65`
still lists R0 as a plan item. **The duplicate it exists to prevent has already appeared**, at
`DuckDBDialect.java:110`, which regexes an already-rendered SQL expression string.

**The model to converge on is in-tree.** `SqlPostProcessors.java:149-211` rewrites the **IR**, not
text — rebuilding `SqlQuery` structurally and throwing `NotImplementedException` on unrecognized
hooks. `RawSqlBoundary:23-25` says everything should converge on exactly this; it is already built
and working.

**V2.1 fix, in order:** (a) add the ArchUnit rule that makes R0 real; (b) emit `Ddl`'s DDL correctly
the first time and delete the `FLOAT`→`DOUBLE` rewrite; (c) make `CsvSeed` emit `Ddl`'s spelling so
one adaptation path covers both — this also fixes V1.4's quoting bypass and collapses the two
ingress routes; (d) retire `RawSql.splitStatements`, which mis-splits on `--`, `/* */`, and
`"`-quoted identifiers containing `;`, and exists solely to feed the rewriter.

### 4.2 V2.2 — The tenet applied maximally, at a cost nobody chose

Every value round-trips through the database, **including string literals**, because `executeTyped`
has no constant arm before the Lowerer. `executeInDb('<insert text>', $c)` therefore costs two
statements: `SELECT '<the insert text>'` to evaluate the constant, then the insert.

| Shape | Statements | Intrinsic? |
|---|---|---|
| Plain mapped class query | **1** | Intrinsic — the good case |
| `createTablesAndFillDb()` (standard corpus setup) | **431, of which 214 touch no table** | Artifact |
| Corpus body, setup + 3 assertions | ≈ T+441, vs the engine's ≈ T+218 | ~223 excess is artifact |
| K execute-lets, A asserts | eager run fires **O(K² + AK)** times | Artifact |

This is the tenet honoured *harder than necessary*, which is a different failure mode from
violating it — and it is defensible as doctrine. **The finding is that nothing in the code says it
is a deliberate trade.** Make it one: either add a constant arm, or write down why there isn't one.

Compounding it, **`buildFrame`'s eager run is dead weight.** `ExecFrame(TypedSpec chain, boolean
relationRooted, ExecutionResult result)` (`StatementExecutor.java:1671-1673`) materializes rows at
the `let` (`:128`, eager run `:1882-1898`), but `.result()` has exactly **one reader in the entire
file** — `:325`, the result-position `execute(...)` arm, a different call site that consumes its own
run immediately. On the let-binding path the field is *written and never read*; every downstream
read splices `chain()` and re-executes. When the read is bare `$r.values` the second statement is
**byte-identical SQL**.

The code knows: one branch away, the inline `execute(…).values` path passes `eager=false` with the
comment *"no separate eager run (it would execute twice)."* `TestBody` then multiplies it by
re-prepending the whole `execStmts` prefix for each side of each assert.

**V2.2 fix:** the let arm already holds the result — either consume it, or pass `eager=false` and
explicitly give up the "a broken pipeline surfaces at the `let`" property that motivated it
(audit 16 F1). Do not leave it computing and discarding.

### 4.3 V2.3 — N+1 seeding, in four places

`CsvSeed.java:79-103` builds **one `INSERT … VALUES (…)` per CSV row**. So does
`TestBody.java:2584-2592`, and `TestDataGenerator.java:920-932` does it **twice per table**
(expected and actual sides). A multi-row `VALUES` is trivially expressible; `COPY … FROM` is better
still. `H2Verify.java:108-121` replays every recorded seed statement individually **per verified
assert** rather than per test.

The **effectful `map` arm** (`StatementExecutor.java:2547-2580`, loop at `:2567-2578`) is the same
shape at the language level: it materializes the collection, then calls `executeTyped` once per
element, keeping only the **last** result and discarding the rest. Five of six reachable shapes
iterate a Java-produced collection. The fix is a HIR fold into one bound statement —
`executeInDb` already splits a `;`-joined blob ~90 lines away, so the receiving end exists.

`StatementExecutor`'s `;`-split loop and `Runner.moduleDdl`'s per-table `CREATE` are **not** in
this bucket — JDBC will not take a multi-statement blob, and DDL is DDL. Genuine BOUNDARY.

**Credit:** `TestDataGenerator.fetchRoot`/`fetchChild` is the one place N+1 would have been most
tempting and it was **avoided** — each fetch materializes into `CREATE TEMPORARY TABLE … AS`, and
children join against the *parent's temp table* in the database. Parent rows never enter Java.
Dedup across fetches is a DB-side `UNION`; `compareCsv` diffs with `EXCEPT` both ways. Do not
regress this while fixing V1.12, which lives in the same file.

---

## 5. V3 — Structural

### V3.1 — `com.legend.harness` ships in the production jar

4,766 lines in `core/src/main/java` (`TestBody` alone is 3,375) with **zero production consumers** —
every reference outside the package is a test source. Two concrete costs:

- **`H2Verify` ships a class core cannot support.** Its entire purpose is verifying against H2, and
  `core/pom.xml` declares **no H2 dependency at all** — not even test-scoped. It compiles only
  because the driver is referenced by string (`Class.forName("org.h2.Driver")`) and degrades via
  `ready()`.
- **Three ArchUnit invariants were widened to carve it out.** In `ArchitectureTest.java`,
  `com.legend.harness` appears **only in exemption clauses** — `:129-142` (4c), `:151-169` (4d,
  explicitly permitting it to construct the quarantined `EngineStyleH2`/`EngineStyleDB2`), and
  `:344-360` (6i). **No rule constrains what the harness may do.** Tenet #2 calls harness
  compensation the cardinal sin, and it is enforced by audit, not by the compiler — which is why
  §1.1's first row went stale undetected.

Moving it to `src/test/java` (or a test-fixtures module) deletes all three carve-outs and makes the
cardinal sin structurally unreachable.

### V3.2 — `expandHelperCalls` is a second β-reduction, and it is capture-unsafe

The pass is **correctly retained** — the removal experiment (`519dc6c8`) regressed a test, the
commit message correctly declares the original execute-visibility rationale obsolete, and both
surviving purposes (mapping discovery, assert visibility) survive scrutiny: `UserCallInliner` throws
on a non-let intermediate statement, and `assertEquals` would fail typing regardless. Asserts are
harness vocabulary by design. **ORCHESTRATION, not compensation.**

The defect is the *mechanism*. `Runner.java:509-514` binds each callee parameter by its **own name**
with no freshening and splices the callee body verbatim, so callee `let`s land in the same flat map
`TestBody` maintains — a caller `let` sharing a name with a callee parameter silently wins or loses
by order. The platform's inliner is explicit that this is unacceptable (`UserCallInliner.java:44-48`:
every binder is renamed to a fresh `_i<N>` **"unconditionally"**). The harness copy also caps
recursion at depth 3 where the platform's is loud on cycles.

**Fix:** keep the pass, α-freshen the bound names. A bug fix, not an architectural change.

### V3.3 — Streaming: the one axis where legend-lite is *weaker* than the engine

The engine's `RelationalResult` holds a live `ResultSet` and its serializers walk `rs.next()`
straight to the socket; materialization happens only at named points and is bounded by
`org.finos.legend.engine.realizedRelationalResultRowLimit`. legend-lite **always drains** —
`Executor.java:105-116` and `:364-410` into `ArrayList`s, with no `setFetchSize`, `setMaxRows`, or
`setQueryTimeout` anywhere in `core/src/main`.

On this axis "never a host object graph" is currently weaker than the system it reimplements. It is
defensible **only because nothing computes over the buffer** — and that is a property of today's
code, not of the type. Resolve V0.4 first; the answer determines whether this is a no-op or an
`ExecPolicy`.

### V3.4 — No transaction control anywhere

Zero `setAutoCommit`, `commit()`, `rollback()`, or `BEGIN` across `core`, `engine`, and `harness`.
Every one of those ~431 setup statements is its own autocommit transaction, so **a failed seed leaves
the database half-populated** — which is precisely why the `rawSqlFailureSink` ledger and the
`emptinessUnverifiable` guard had to be invented. Those guards are good engineering compensating for
a missing primitive; worth knowing that is what they are. Survivable today: one connection per test,
single-threaded, in-memory DuckDB.

---

## 6. The rubric — a standing reviewer artifact

Apply in order; first match wins. Every question is answerable by reading the site plus one hop.
This exists so that the next audit does not re-litigate §2.

> **Q0 — Is it on the execution path?** Reachable only from a plan-text / golden / lineage / IDE
> surface (`plan/`, `resolver/RelationalRootForm`, `lineage/`, `ide/`)? → **ORCHESTRATION.** Test:
> does any `Compiler.execute*` call reach it? Require a declaration in the header, as
> `PlanEnumForm:22-23` and `RelationalRootForm:29-31` both have.
>
> **Q1 — Provenance.** Did any bit this site consumes cross a `ResultSet`? No → **Q2**
> (compile-time). Yes → **Q4** (runtime).
>
> **Q2 — Does the plan depend on the computed value?** Would the SQL be un-writable without it — a
> column list, a limit bound, a schema, a set of DDL statements? Yes → **ORCHESTRATION**
> (`StaticFold`, `ConstBounds`, `TdsChecker.inferredType`, `ResultShape`, `CsvSeed`'s DDL, `Ddl`).
> No → **Q3**.
>
> **Q3 — Un-desugaring, or arithmetic the source asked for?** Recovering a notation the parser
> created (`minus(1)`→`-1`), or folding an artifact the lowerer itself introduced (0-based→1-based
> `+1`) → **ORCHESTRATION.** A fold over operands the **user wrote** → **VIOLATION**, unless a named
> comment justifies it *and* the fold provably agrees with the database on width, scale, and NULL.
>
> **Q4 — Does it combine, compare, filter, aggregate, join, dedupe, sort, or arithmetically
> transform cells?** Yes → **VIOLATION**, no exceptions. Sole carve-out: comparing two sides that
> **both** executed through the pipeline (the declared `assert*` boundary, `TestBody:42-45`). If only
> one side is DB-produced, it is a violation. No → **Q5**.
>
> **Q5 — Is it re-typing or decoding a single cell?** → **BOUNDARY**, and it must pass all three:
> (a) the encoding is self-describing, or the decode is the exact inverse of an encoding a lowering
> chose; (b) it consults the cell's **kind**, never its **magnitude**; (c) an unrecognized shape
> throws. Fail any → **VIOLATION.** No → **Q6**.
>
> **Q6 — Is it rendering to text as a terminal step?** Decision-free → **BOUNDARY.** Decides order,
> pins a header, or tolerates precision drift → **COMPENSATION** (policy); legal only if declared in
> a design doc and only in `harness/`. No → **Q7**.
>
> **Q7 — Is it enforcing a contract by throwing** (cardinality, arity, plan/schema agreement)? →
> **ORCHESTRATION**, provided the message names the **upstream layer at fault**, not the symptom.
> No → **Q8**.
>
> **Q8 — Ownership.** Another module already owns this behavior → **COMPENSATION**, regardless of
> correctness. *No* module owns it and this site exists because the query was rewritten to avoid the
> platform — a stripped tail, an intercepted call, a name-matched arm → **COMPENSATION.** Else →
> **ORCHESTRATION.**

**Severity:** VIOLATION > COMPENSATION > BOUNDARY-with-defect > BOUNDARY > ORCHESTRATION.

**Tie-breakers, when two reviewers still disagree:**

- **The deletion test.** Delete the site and let the platform answer. Same result → it was
  compensation all along; this is how `f4f65817` proved itself (*"Pure deletion (−431 lines),
  corpus-identical"*). A loud error → the platform has a real gap and the site is a stopgap that must
  be **named** as one. A `NotImplementedException` outranks a working host implementation.
- **The discovery test.** Would replacing this with the platform path *find* cases it currently
  misses? `01dc8f54` found **+173** tests the regex had silently skipped; `ee5b630e` found +24. A
  host implementation that is shape-insensitive is silently wrong, not merely redundant.
- **The typed-fact test.** Does the site ask the **value** a question the **type** could answer?
  `relationRooted` as a resolve-probe was the smell; as "the chain root's type" it is a fact.
- **The second-backend test.** Would another dialect need this logic? If yes and it lives outside
  `sql/dialect/` or a declared boundary class, it is misplaced even when not a tenet violation.

**Worked example, for calibration.** `TestDataGenerator.java:793` applies `hashString(v)` to a
`ResultSet` value while the class header lists `hashStrings` among the LOUD walls. Q0 no; Q1 yes;
Q4 — it arithmetically transforms a cell → **VIOLATION**. The same file's `compareCsv` does the
entire comparison in SQL → **ORCHESTRATION**. Two sites, one file, opposite verdicts.

---

## 7. Do NOT spend effort here — hypotheses this audit refuted

Recorded so the next pass does not re-open them. Each was specifically suspected and specifically
disproved.

- **`InnerDemand.inQueryReads` executing queries during resolution.** It never executes. Its
  `rawResolver` is `chain -> resolveNode(chain, context)`, a pure typed→typed Phase-H re-entry, and
  `StoreResolver` has **no `Connection` field anywhere**. Its product is a resolved relation node
  substituted into the enclosing query, so an `in`/`contains`/`tdsContains` over a class extent
  becomes a **subquery inside the one statement**. The tenet working as designed. (The swallowed
  `RuntimeException` at `:501-503` is a real observability defect — a resolver bug degrades silently
  to "not an in-query read" — but that is a correctness smell, not a boundary violation.)
- **`ClassSources`' and `SubQueryLift`'s nested `StoreResolver`.** Compile-time, typed in, typed out,
  fresh instance to avoid state leakage.
- **Compile-time constant folding as host evaluation.** The lowering **compiles, it does not
  evaluate**: folds are guarded on the already-lowered `SqlExpr` being a literal. See V0.3.
- **Graph fetch as a host-assembly risk.** Refuted emphatically — the DB builds the graph,
  unconditionally, at every depth. `CheckedEnvelope.java` is 69 lines and **every one emits SQL**:
  constraints become `CASE WHEN NOT <pred> THEN json_object(…)`, the defects array is
  `list_filter(ARRAY[…], x -> x IS NOT NULL)`. Data quality, computed by the database.
- **A pk-collapse symptom ("3 objects where 6 expected").** No such symptom exists anywhere in the
  corpus or docs. The real recorded symptom runs the **other** way — 4 objects where 3 were expected,
  a phantom all-null object from a LEFT join — and it was fixed **in SQL**, by re-stamping the hop's
  join `INNER`. Real legend-engine handles the same case with a host-side reader skip.
- **`RelationalRootForm` un-building the graph into a host shape.** It does produce a flat
  host-shaped form, but the product is a `TypedProject` **spec**, not a result, and both call sites
  confirm it is fenced off the execution path — it exists only to compare golden text against a
  system that *does* do host assembly.
- **A "stacked" CSV tolerance.** `csvRowEquals` is `max(0.5·10⁻ᵈᵖ, |ev|·1e-11)` and the half-ULP term
  applies **only** at ≥10 significant digits; the `dp=0 → ±0.5` bug was already fixed. *(But see
  §8's caveat — a different leniency does exist there.)*
- **`H2Verify` as compensation.** It is a **second database executing**, and it *upgrades* asserts
  from advisory to row-verified. Two real defects in its normalizer, below.

---

## 8. Preserve these

Things that look like violations, are not, and would be easy to "fix" into something worse.

- **`Scalars.java` is the tenet, implemented.** `SqlExpr → SqlExpr` rules keyed on resolved overload
  identity; unregistered overloads fail loudly; never returns a Java value. `:2374` states the rule
  exactly, and `:2617-2663` honours it in the hardest case by composing Pure's recursive `toString`
  print-forms entirely in SQL.
- **`Scalars.java:1954-1968` is the pattern to copy** — the decimal-suffix strip runs in Java *only*
  for a `SqlExpr.StringLit`; the runtime path emits `SqlFn.RTRIM`. Compile-time folding and runtime
  delegation, cleanly separated.
- **`Ddl.spell:62-99`** — exhaustive over the sealed type with explicit throws, *"so a new variant is
  a compile error here, not a runtime surprise (T3.1)."* The direct rebuke to `CsvSeed.ddlType`.
- **The one-directional wire bridges.** `TestBody:3149-3152` and `:3202-3204` both refuse the
  symmetric grant, stating that it would mask a real typing bug. The single strongest discipline
  signal in the harness.
- **The refusals to hollow-pass.** `emptinessUnverifiable` across four assert spellings; mixed
  golden-SQL/value asserts returning `UNSUPPORTED_MARKER`; `runPerDriverLoop` walling a multi-driver
  golden rather than verifying the H2 subset.
- **`LL_TOL_COUNT`** — the harness instruments comparisons that pass *only* because of a tolerance. A
  harness that counts its own leniency is one that intends to remove it.
- **Connection/handle discipline.** Connection-typed expressions short-circuit to an opaque handle,
  and the arms **throw loudly rather than silently dropping effects nested in their arguments**. Same
  in the `print` arm and the effectful-argument guard.
- **`print` is a no-op that never evaluates its argument** — a documented deliberate divergence from
  the engine, chosen because printing would force a `ResultSet` host-side. Stricter than the system
  being reimplemented.
- **`Executor` refuses to sniff types**, and a scalar-shaped result returning a second row **throws**
  rather than reading row 0 — with a message that accuses upstream: *"the to-one contract was not
  enforced upstream."* Audit 20b found this arm silently returning row 0; that was the worst outcome
  in the taxonomy and it is now correct.
- **`PostProcessBoundary` + `SqlPostProcessors`** — IR rewriting, structurally, with
  `NotImplementedException` on unrecognized hooks. The target design for V2.1.
- **The compiler has no filesystem.** Zero `java.nio.file` / `getResourceAsStream` in
  `core/src/main`; source arrives as `String`.
- **The host channel's own discipline** (§1.2), whatever the charter concludes: every unhandled shape
  loud; `TypedMatchRuntime` walls outside the channel; the constructible class set kept **curated**
  after widening it to "any native class" stole 21 passing constructions from the K path; each gate
  run against the full sweep with family deltas empty; and the signatures taken from real legend-pure
  rather than invented. The two failures it hit — the 1,688-test dispatch collapse and the H2-stream
  pollution — were both **caught by the gate and fixed in the same commit**. That is the protocol
  working.

**Two defects inside otherwise-good machinery** — fix in place, do not remove the machinery:

- **`H2Verify.norm:189-201`** applies `new BigDecimal(v.toString()).round(new MathContext(10))` to
  **every** `Number`, not just decimals. Precision-10 `HALF_UP` means a 14-digit integer identifier
  collapses: `12345678901234` and `12345678901299` both normalize to `"12345678900000"` and compare
  **equal**. The stated rationale justifies a *decimal* tolerance, not an integer one. Everything
  else falls to `v.toString()`, so VARCHAR `'1'` equals INTEGER `1`.
- **`csvRowEquals:2928-2931`** compares string-first then falls back to `Double.parseDouble` on
  **both** sides, so VARCHAR `'007'` equals INTEGER `7` — a cross-kind collapse the primary
  `wireEquals` path explicitly refuses at `:3162-3165`. Narrow, but it means an assert gets a *weaker*
  comparator whenever it happens to be spelled with `toCSV`.

**Dead code, safe to delete:** `TestBody.csvText:2981-3001` (never called; `csvEquals` re-implements
it inline) and `constantStrings:635-673` (mutually recursive with `constantString`, reachable from
nothing).

---

## 9. Where legend-lite is deliberately stricter than the engine — and why it can afford to be

Worth stating because it reframes every item above.

The real engine's line is a Pure predicate, `meta::relational::contract::supports(f, state)`. True →
the expression becomes SQL. False → `PlatformRoutingStrategy` takes it, and **the engine generates
Java source at plan time**, JIT-compiles it at execution, and runs it against a runtime library —
`Library.java` (1,849 lines) and `PureDate.java` (2,097 lines: a complete host-side Pure date
implementation).

**That is the whole difference.** When SQL cannot express something, the engine's answer is *generate
Java*; legend-lite's is *throw a named error* — 339 `NotImplementedException` sites. **legend-lite can
afford a stricter tenet because it is allowed to fail.**

Where that buys real wins: graph fetch (SQL `json_object` vs an in-heap graph with identity dedup,
batching and five cache classes); cross-store (one SQL plan vs a Java hash-join over heap objects);
enum decode (SQL `CASE` vs host-side decode, with the engine's form kept only as a plan-text
rewrite); wire coercion (a SQL `CAST` vs a runtime converter); `print`; test-data generation
(`EXCEPT` in DuckDB vs comparing materialized objects); and setup bodies (compiled, with even the SQL
string literal computed by the database).

The engine's host-side territories that legend-lite deliberately does not have: graph-fetch object
assembly, cross-store stitching, M2M entirely, external formats entirely, and `preeval.pure` — which
folds constants by **running the Pure interpreter**.

---

## 10. Sequencing

**V0 first, in one commit, and with urgency it did not have a week ago.** §1.1's five header
corrections plus §2's clauses — **V0.2 and V0.6 above all**, because §1.2's host channel is live,
growing per wall, and currently unadjudicable. Three of the eight auditors spent effort on questions
§2 answers, and the next arm to land will spend more. No code changes.

**Then, by falling severity:**

1. **V1.5's overload narrowing** — `toString(Any[1])` must not accept a `RelationType`. A latent
   wrong-answer with no test covering it, confirmed by execution at `b9746692`, and the fix is
   independent of where rendering ultimately lives.
2. **V1.15** — the new `createTableStatementText` emits invalid SQL for the space-containing column
   names the corpus actually has, and `quoteCreateColumns` mangles rather than fails. Newest, and
   cheapest to fix while the commit is fresh.
3. **V1.1, V1.4** — the two sites that contradict this project's own completed remediations (T3.1).
4. **V1.16, V1.17** — the host channel's data-space arm and its silently-swallowed replay errors.
   These are the parts of §1.2 that V0.2 will *not* authorize, so they need re-siting either way.
5. **V2.1(a)** — the ArchUnit rule for R0, plus an equivalent invariant on the `hostChannel` dispatch
   predicate, which has already collapsed the sweep once. Cheap, and it stops both patterns from
   spreading further than `DuckDBDialect.java:110`.
6. **V1.3, V1.11** — both are deletions in favour of code that already exists and already works.
7. **V2.2's `buildFrame`** — one line of plumbing; decide consume-or-`eager=false` explicitly.
8. **V1.7–V1.10, V1.13, V1.14** — the decode/re-derivation cluster at the JDBC seam. Related enough to
   batch; V1.10's root cause (`PureSql.java:64`) may resolve several.
9. **V1.18** — un-couple the retry loop from exception text before someone rewords an error message.
10. **V0.4 → V3.3** — resolve the materialization clause, then act on streaming or explicitly don't.
11. **V3.1, V3.2, V2.1(b–d), V2.3, V1.2, V1.6, V1.12, V1.19, V1.20** — structural, larger, or
    lower-severity.

**Do not batch V1.5 with a rendering redesign.** The narrowing is a bug fix; where `toCSV` ultimately
lives is a design decision, and coupling them will stall the bug fix behind the debate.

**Do not treat §1.2 as a cleanup item.** Most of that code is defensible and some of it is good. The
deliverable is the *charter* — after which most of it is authorized, two arms move, and the next one
has a bar to clear.
