# Simple-name matching where an FQN is required — audit

> **Question asked:** does any code path in `core/src/main/java` match a Pure element by its
> *simple* (unqualified) name where the language requires resolution through the referring
> element's **import scope**? The trigger: the relational corpus defines 18 `Database` elements
> named `myDB`, 13 named `db`, 8 named `MFTtestDB`, each in a different package.
>
> **Companions:** `AGENTS.md` §5 (FQN-carrying references), `docs/TENETS.md` (name-resolve =
> "Knowledge, eager, **total**"), `docs/AUDIT_23_SPECIAL_CASING.md` (method).

**Evidence standard.** Every finding below was produced by **reading the named lines**, and the
three HIGH findings were then **reproduced by executing the production compiler API**
(`Compiler.parseSources` → `Compiler.buildModel` → `Compiler.compile`) against purpose-built
two-package models on `core/target/classes`, JDK 21. Probe transcripts are quoted verbatim in §2.
Grep located candidates; grep never classified one. Where a comment in the source and the observed
behaviour disagreed, **execution won** — §3 records two such cases.

**What was NOT verified** is listed in §8. No test suite was run (shared machine); nothing in this
audit rests on "the corpus passes", and §7 explains why that would have been worthless anyway.

---

## 1. Verdict

**The store-reference path is correct in its main line and silently wrong in its fallback.**

- `NameResolver` **does** qualify bracketed store references `[myDB]` through the file's own
  import scope. `resolveTableReference`, `resolvePropertyMapping` (all five db-carrying arms),
  `resolveJoinChainElement`, `resolveFilterPointer`, `resolveRelOp(ColumnRef/JoinNavigation)`
  and `resolveStoreSubstitution` each route their database string through `resolveName(…, scope)`.
  The per-element scope machinery is real and wired: `Compiler.parseSources` records a per-element
  `ImportScope` and `NameResolver.resolve` uses the element's own scope, not a merged one.
  **Verified by probe: `[myDB]` under `import pkgA::*` with both `pkgA::myDB` and `pkgB::myDB`
  in the model resolves to `pkgA::myDB`.** The headline collision scenario is handled.

- **The defect is what happens when that qualification does not fire.** `resolveName` returns the
  name *unchanged* when no wildcard package yields a known FQN. That bare string then reaches
  `ModelBuilder.findDatabase`, which **scans the entire model for any database whose FQN ends in
  `::myDB`**. One match = bound, no error, no diagnostic — **including when the referring file
  never imported that package.**

- **This is a silent wrong answer on the production path, reproduced end to end.** A mapping that
  writes `import pkgA::*;` and `[myDB]`, in a model where `pkgA` has no `myDB` but the
  never-imported `pkgB::myDB` does, **compiles clean and emits SQL against `"S"."T_A"` — the
  schema of the unimported store.** Real Pure fails this model at compile time. See §2.

- **The ambiguity guard is not a safety property.** `findDatabase`/`findClass`/`findJoin` each
  return `Optional.empty()` on ≥2 candidates. That covers only the two-in-the-model case. The
  one-in-the-model case — the *common* one, and the one the corpus's isolated-module compilation
  manufactures on every single test — sails through unguarded. **A site is SUSPECT if it can bind
  a name to an element the referring element's `ImportScope` does not make visible.** All three
  sites fail that test, guard or no guard.

- **`findJoinDefinition` is worse than `findDatabase`**, because its bare-name arm (`findJoin`
  :935-952) searches *every* same-simple-named database for one that declares the join, then
  recurses into include closures. It reaches further into the model on less evidence.

- **The scale of the by-name problem is much smaller than the trigger suggested, and located
  elsewhere.** Of the **23** `lastIndexOf("::")` sites in `core/src/main/java`, **15 are BENIGN**
  presentation or construction — the three dangerous sites are `endsWith("::" + name)` scans, not
  `lastIndexOf` ones. Chasing the `lastIndexOf` count would have found nothing.

- **A partial mitigation already exists and is documented as such**
  (`StoreSubstitutionRewrite.qualifyStoreRefs`, :214-266). It is deliberately conditional, which
  leaves the model carrying **two spellings of the same store** — a second-order hazard, §5.

**Severity: HIGH, production-reachable, currently invisible to the test suite.**

---

## 2. The store-reference finding, in full

### 2.1 The code

`core/src/main/java/com/legend/compiler/ModelBuilder.java:769-793`

```java
public Optional<DatabaseDefinition> findDatabase(@com.legend.Nullable String fqn) {
    if (fqn == null) return Optional.empty();
    DatabaseDefinition exact = idGet(databases, symbols.resolveId(fqn));
    if (exact != null) return Optional.of(exact);          // exact FQN — correct
    // A BARE store name ([PersonDatabase] T_EMPLOYEE) resolves by simple
    // name when UNIQUE across the model — the engine's lenient store
    // reference; ambiguity stays a miss (the caller's error names the ref).
    if (!fqn.contains("::")) {
        DatabaseDefinition found = null;
        for (DatabaseDefinition db : databases) {
            if (db != null && db.qualifiedName().endsWith("::" + fqn)) {
                if (found != null) return Optional.empty();   // ≥2 → miss
                found = db;
            }
        }
        return Optional.ofNullable(found);                    // ==1 → BIND, unguarded
    }
    return Optional.empty();
}
```

The comment asserts parity with "the engine's lenient store reference". That claim is not
substantiated anywhere in the repo, and real Legend resolves `[myDB]` through the section's
imports — the premise of this audit. Treat the comment as a hypothesis, not a citation.

`findClass` (:539-563) and `findJoin` (:903-954) carry structurally identical arms.
`ModelContext.findDatabase` (:177) and `findJoinDefinition` (:184) name their parameter `dbFqn`
and `PureModelContext` (:324, :330) delegates straight through — **the `dbFqn` parameter name is
a promise the type system does not keep**, and callers cannot tell a bare name from an FQN.

### 2.2 The probe (verbatim)

Model: `pkgA::otherDB` (imported, no `myDB`), `pkgB::myDB` (**never imported**, table in schema
`S`, join `J_SELF`), `pkgB::Firm` (**never imported**), mapping file opens `import pkgA::*;` and
writes `scope([myDB]S.T_A)`. Six separate `ModelSource`s so per-file import scopes are honoured.

```
--- [myDB] under `import pkgA::*` (pkgA has NO myDB):
    property mapping -> Column[propertyName=name, database=myDB, table=S.T_A, column=NAME
    ctx.findDatabase("myDB") -> pkgB::myDB
    ctx.findJoinDefinition("myDB","J_SELF") -> J_SELF
    ctx.findClass("Firm")  [never imported] -> pkgB::Firm
    ctx.findMapping("M")   [exact-only]    -> <empty>

--- end-to-end SQL:
SELECT t0.NAME AS n
FROM "S"."T_A" AS t0
```

Line 1 shows `resolveName` correctly declining to qualify (`pkgA::myDB` is not a known FQN) and
leaving `database=myDB` bare. Lines 2-4 show three separate lookups binding **elements the file
never imported**. The last block is the payload: **valid SQL, no warning, against the wrong store.**
`findMapping` returning `<empty>` is the control — it has no bare-name arm and behaves correctly.

### 2.3 Behaviour matrix

| Model | `import` | Resolver output | Lookup result | Real Pure | legend-lite |
|---|---|---|---|---|---|
| `pkgA::myDB` only | `pkgA::*` | `pkgA::myDB` | exact | binds | **binds — correct** |
| `pkgA::myDB` + `pkgB::myDB` | `pkgA::*` | `pkgA::myDB` | exact | binds `pkgA` | **binds `pkgA` — correct** |
| `pkgA::myDB` only | *(none)* | `myDB` (bare) | unique scan | **error** | binds `pkgA::myDB` — lenient |
| `pkgA::myDB` + `pkgB::myDB` | *(none)* | `myDB` (bare) | ≥2 → empty | **error** | `TypeInferenceException: unknown table 'T_A' in database 'myDB'` — **loud, acceptable** |
| `pkgB::myDB` only | `pkgA::*` | `myDB` (bare) | unique scan | **error** | **binds `pkgB::myDB` — SILENT WRONG** |

Row 5 is the defect. Row 4 is the failure the recorded 1219→1200 corpus experiment produced:
widening the compile unit turns silent-wrong into loud-miss. **The pass-count drop was the
ambiguity guard working, not a regression** — it converted 19 latent wrong answers into 19 honest
failures.

### 2.4 Why `resolveName` declines

`NameResolver.resolveNameMulti:451-489` qualifies a bare name only when
`scope.knownFqns().contains(pkg + "::" + name)` for some wildcard. It returns the bare name
unchanged when **(a)** the file has no import covering the store's package, **(b)** the store is a
sibling in the mapping's own package (legend-lite injects no implicit same-package import), or
**(c)** the store lives outside the compile unit. In all three cases the bare string escapes into
`findDatabase`, and the global scan decides.

---

## 3. Every bare-name global-search site

**Criterion: can this site bind a name to an element the referring element's `ImportScope` does
not make visible?** Ambiguity guards are *recorded*, never *credited*.

| # | Site | What it scans | Ambiguity guard | Class |
|---|---|---|---|---|
| S1 | `compiler/ModelBuilder.java:780-791` `findDatabase` | every `DatabaseDefinition` in the model | yes (:784) | **SUSPECT — HIGH** |
| S2 | `compiler/ModelBuilder.java:550-561` `findClass` | every `ClassDefinition` in the model | yes (:554) | **SUSPECT — HIGH** |
| S3 | `compiler/ModelBuilder.java:935-952` `findJoin` | every same-named db, then its include closure | yes (:945) | **SUSPECT — HIGH** |
| S4 | `compiler/ModelBuilder.java:879` `findFilter` | inherits S1 for the include walk | via S1 | **SUSPECT — inherited** |
| S5 | `compiler/ModelBuilder.java:982` `findView` | inherits S1 for the include walk | via S1 | **SUSPECT — inherited** |
| S6 | `element/TypeClassifier.java:43-47` `findType` | inherits S2 | via S2 | **SUSPECT — inherited** |
| S7 | `validation/ValidateDesugar.java:405` | `findClassDefinition(bare)` is tried **before** the import loop at :409 | none | **SUSPECT — ordering** |
| S8 | `normalizer/MappingNormalizer.java:2391` | `findClass(bare)` as a branch predicate | via S2 | **SUSPECT — low** |
| S9 | `compiler/spec/Typer.java:2396` | `findDatabase(ev.fullPath())` — `fullPath` may be bare | via S1 | **SUSPECT — low** |

**Confirmed NOT global-search** (exact-FQN only — the correct shape; verified by reading each):
`findMapping` :799, `findLegacyMapping` :808, `findAssociation` :566, `findEnum` :692,
`findProfile` :697, `findService` :821, `findRuntime` :826, `findConnection` :834,
`findFunction` :842, `findPrimitiveExtension` :341 (whose javadoc at :335-340 *explicitly names
and rejects* the suffix-match pattern — the one place in the tree that got this right on purpose),
`hasDatabaseExact` :1061.

**So: 3 primary offenders, 3 inherited, 3 low — out of 21 public lookups.** The other 12 are clean.

### 3.1 Scoped-probe sites — NOT global search, listed to forestall re-reporting

These construct `pkg + "::" + name` from a **bounded** package list and probe by exact FQN. They
cannot reach an arbitrary package, so they pass the criterion.

| Site | Bound | Class |
|---|---|---|
| `NameResolver.java:471, :484` | the file's own wildcards | BENIGN — this *is* the correct mechanism |
| `normalizer/StoreSubstitutionRewrite.java:247` | the mapping's own wildcards | BENIGN (mitigation, §5) |
| `normalizer/AssociationSynthesis.java:317-327` | own wildcards, then own package | SUSPECT — low (§4) |
| `element/FunctionCompiler.java:43-47` | curated `CORE_FUNCTION_PACKAGES` under `meta::pure::` | BENIGN |
| `compiler/spec/Typer.java:2554-2557` | curated prelude packages | BENIGN |
| `builtin/Pure.java:765-773, :875-878` | the closed platform catalog | BENIGN |
| `harness/H2Verify.java:384-392`, `TestDataGenForm.java:461-470`, `LineageRelationsForm.java:154` | the test's own wildcards | BENIGN (harness) |

---

## 4. The 23 `lastIndexOf("::")` sites

The trigger named these. **They are, with few exceptions, not the problem.**

| # | Site | Intent | Class |
|---|---|---|---|
| 1 | `lowering/Lowerer.java:752` | JSON graph type key; FQN when `fullyQualifiedTypePath` | BENIGN — display |
| 2 | `lowering/Lowerer.java:2345` → used :2549 | type name into a `StringLit` | BENIGN — display |
| 3 | `lowering/Lowerer.java:2692` | class ref in scalar position → `'STR_Person'` | BENIGN — display |
| 4 | `harness/TestBody.java:2365` | harness dispatch on Pure fn simple name | SUSPECT — harness only (§6) |
| 5 | `parser/ElementParser.java:708` | `~enforcementLevel` `Error`/`Warn` label | BENIGN — display |
| 6-7 | `lineage/ScanRelations.java:1738, :1740` | `typeMatches` written-vs-resolved spelling | SUSPECT — lineage only |
| 8 | `model/ImportScope.java:72` | splits an import decl into simple→FQN | BENIGN — construction |
| 9 | `parser/SpecParser.java:1955` | `"Relation".equals(simple)` grammar arm | SUSPECT — low |
| 10 | `parser/SpecParser.java:2414` | splits `db::DB.schema.TABLE` at the first dot after the path | BENIGN — construction |
| 11 | `compiler/spec/CoreFn.java:167` | FQN→bare **only after** confirming a catalog native (:165-166) | BENIGN — guarded |
| 12 | `normalizer/ModelNormalizer.java:218` | private `simpleName`, **no call sites** | BENIGN — dead |
| 13 | `compiler/spec/Typer.java:696` | window-fn `rank`/`denseRank` dispatch | SUSPECT — low |
| 14 | `compiler/spec/Typer.java:2110` | column type name string | BENIGN — display |
| 15 | `normalizer/AssociationSynthesis.java:324` | same-package fallback FQN construction | SUSPECT — low |
| 16 | `compiler/spec/StaticFold.java:558` → :177, :233 | `TypeToken` / column type strings | BENIGN — display |
| 17 | `normalizer/MappingNormalizer.java:3324` | `PRIMITIVE_TYPE_NAMES.contains(simple)` (:3357) | SUSPECT — low |
| 18 | `builtin/Pure.java:766` | bare index over the **closed platform catalog** | BENIGN — closed set |
| 19 | `compiler/NameResolver.java:260` | prelude collision index, platform FQNs only | BENIGN — closed set |
| 20 | `element/type/Type.java:118` | `Primitive.typeName()` | BENIGN — display |
| 21 | `element/type/Type.java:309` | `GenericType.typeName()` | BENIGN — display |
| 22 | `compiler/spec/SpecCompiler.java:206` → :155-156 | error message text | BENIGN — display |
| 23 | `compiler/spec/FoldChecker.java:97` | `"add"` pattern dispatch | SUSPECT — low |

**15 BENIGN, 8 SUSPECT (all low or non-production).** Note #12: `ModelNormalizer.simpleName` has
zero call sites — dead code, flagged for deletion, not a risk.

### 4.1 Correction to the brief's counts

The brief expected "5 known" `endsWith(` sites on names. The actual sweep found **23 code lines**
matching `endsWith("::` in `core/src/main/java`. `split("::")` has **zero** occurrences — that
pattern does not exist in this tree.

Seventeen of the 23 are **platform-function tail matching**: `qualifiedName().endsWith("::toOne")`,
`::pair`, `::plus`, `::equal`, `::and`, `::sql`, `::execute`, `::paginated`, `::parseJSON`,
`::SQLNull`, `::DynaFunction` — in `StatementExecutor` (:1067, :1321, :2164),
`SqlPostProcessors` (:86, :133, :135), `RelationalMapperRenames` (:262, :264),
`AssociationJoins` (:674, :680), `TypedFrom` (:183), `Typer` (:539), `TestBody` (×5).
A user function `my::pkg::toOne` would hijack each. This is a **coherent SUSPECT class of its own**
(§6), and `element/type/PlatformTypes.java:5` already records that `endsWith("::List")` *was* a
shipped bug — the lesson was learned for types and not carried over to functions.

The remaining six: `ModelBuilder` (×3 — the S1-S3 arms, §3), `PlanText` (:340, :368 —
enum-mapping lookup **within one mapping's own** `enumerationMappings`, exact-first at :334, so
SUSPECT-low), and `ImportScope` (:66, `endsWith("::*")`) which is BENIGN — grammar, not a name.

---

## 5. Second-order hazard: two spellings of one store

`StoreSubstitutionRewrite.qualifyStoreRefs` (:214-266) is a deliberate mitigation for exactly this
audit's subject — its javadoc names the corpus family it fixed. But :237-242 restricts the rewrite:

> *"rewrite ONLY the SHADOWED cases … When they agree the raw spelling stays — downstream
> machinery keyed on it is untouched (the propertyLevel family regressed wholesale under
> unconditional qualification)."*

Consequence: **a compiled model can carry both `myDB` and `pkgA::myDB` for the same physical
store.** Downstream code compares store references by **string equality**:

| Site | Comparison | Failure if spellings differ |
|---|---|---|
| `resolver/ClassSources.java:1108` `sameRootTable` | `ra.store().equals(rb.store())` | two pipelines on one table judged *different* → missed fusion, spurious join |
| `resolver/ClassSources.java:1090-1093` | substitution original/replacement vs `ra.store()` | `include m[db->MyDb]` substitution silently not applied |

Classified **SUSPECT — medium**, mechanism understood, **not reproduced** (constructing a model
that triggers the mixed-spelling state needs a `propertyLevel`-shaped include chain; out of budget).
The regression cited at :241 is evidence the coupling is real and load-bearing.

---

## 6. The platform-function tail-match class

Seventeen sites decide "is this call `toOne` / `pair` / `equal` / `sql`?" by FQN suffix. A user
function whose last segment collides is misread as the platform native.

Two places in the tree already defend against this and should be the model for the rest:
- `CoreFn.java:160-168` — strips to bare **only after** `Pure.nativeFunctionsAt(parseName)`
  confirms a catalog native. Its comment names the pin: `meta::pure::custom::map`.
- `NameResolver.java:422-430` — `normalizePlatformFunction` was **reduced to identity**, comment
  at :425-427: *"the old blind prefix-strip silently CAPTURED user functions whose last segment
  collided with a native."*
- `harness/TestBody.java:2371-2372` — *"harness-vocab gate — a user function named 'sql' must not
  demote a whole assert to advisory."*

So the defect is **known, named, and fixed in three places** — and left standing in seventeen
others. Severity is low individually (colliding with `meta::pure::functions::…::toOne` is
unlikely) but the class is uniform and the fix is mechanical.

---

## 7. Why the test suite cannot see any of this

`engine/src/test/java/com/gs/legend/rcorpus/Runner.java`, `pullUnresolvedMappingStores`, registers
each database under **both** its FQN and its bare simple name
(`q.substring(q.lastIndexOf("::") + 2)`). Combined with per-family isolated-module compilation,
every corpus run presents the compiler with **exactly one candidate per simple name**. That is
precisely the input on which the unguarded arm of §2.3 row 3 and row 5 is indistinguishable from
correct behaviour.

**A green corpus is not evidence about this defect, in either direction.** The 1219→1200 drop when
the compile unit widened is the only signal the suite has ever produced, and it points at the bug.

Any regression test for this must be a **hand-built multi-package model**, not a corpus family.
The Probe 3 shape in §2.2 — one imported package without the name, one unimported package with it —
is the minimal case and should be the test.

---

## 8. Compliance with the stated rules, and what was not verified

### 8.1 Rules

| Source | Rule | Compliance |
|---|---|---|
| `docs/TENETS.md:51` | Name-resolve (imports → FQN) is **"Knowledge / Eager, total"** | **VIOLATED.** Resolution is not total: `resolveNameMulti` returns names unresolved, and a later layer guesses. |
| `AGENTS.md:24, :57` | `NameResolver` owns "AST → AST (imports → FQN)"; consulting the model is **out of scope for it** and by implication nobody else re-does it | **VIOLATED in spirit.** `ModelBuilder` performs a second, import-blind resolution pass. |
| `AGENTS.md:195-214` | references carry **FQN strings**; structural access goes through `ModelContext.findClass(fqn)` | **Partially violated.** The channel is respected; the *contract on the string* is not — `findDatabase(String dbFqn)` accepts a bare name and resolves it. |
| `ModelContext.java:125-128` | `findType` documented **"FQN-only"** | **False as documented.** It routes through `TypeClassifier:43` → `findClass`, which is not FQN-only. |

`AGENTS.md` and `TENETS.md` state no rule that *names* simple-name matching as a prohibited
pattern. Given that three separate call sites independently rediscovered it as a bug
(`PlatformTypes:5`, `NameResolver:425`, `ModelBuilder:335`), **the rule should be written down.**

### 8.2 Not verified

- **The engine tree (`engine/src/main/java`, 341 files) was surveyed, not audited.** Its
  `ModelContext` has no `findDatabase`; its `ImportScope.resolve` (:67-103) implements the
  known-types + ambiguity-throw algorithm correctly, but `resolveSimple` (:112-127) **takes the
  first wildcard import blindly** with no known-types check — a distinct and arguably worse
  defect. Its call sites were not traced. Treated as out of scope per the brief's framing of
  `core` as primary.
- **§5 mixed-spelling hazard is reasoned, not reproduced.**
- The claim that real Legend Engine errors (rather than resolving leniently) on an unimported
  store reference is taken from the brief's corpus-source citation. **Not independently verified
  against engine source** — no engine checkout was consulted.
- Whether any *current* corpus family actually depends on the lenient bare-name arm. If some do,
  removing S1-S3 outright will fail them; §9 assumes it must be staged.

---

## 9. SUSPECT ranking — collision likelihood × silence of failure

| Rank | Site | Collision likelihood | Failure mode | Score |
|---|---|---|---|---|
| **1** | `ModelBuilder.findDatabase:780` (S1) | **very high** — 18× `myDB`, 13× `db`, 8× `MFTtestDB` | **silent**: SQL against the wrong store (§2.2) | **critical** |
| **2** | `ModelBuilder.findJoin:935` (S3) | **very high** — join names collide harder than db names | **silent**: wrong ON condition, wrong rows | **critical** |
| **3** | `ModelBuilder.findClass:550` (S2) | **high** — `Person`/`Firm` repeat across corpus packages | **silent**: wrong class shape, wrong columns | **critical** |
| 4 | `ClassSources.java:1108` (§5) | medium — needs mixed spellings | **silent**: missed fusion / spurious join | high |
| 5 | `ValidateDesugar.java:405` (S7) | medium | silent: constraints from the wrong class | medium |
| 6 | `findFilter:879` / `findView:982` (S4, S5) | medium (inherited) | silent: wrong filter/view via include walk | medium |
| 7 | platform-fn tail match ×17 (§6) | low — needs a user fn named `toOne`/`pair`/… | silent: wrong lowering | medium |
| 8 | `MappingNormalizer:3324` (#17) | low — needs a user class `x::Integer` | silent: wrong primitive coercion | low |
| 9 | `Typer:696`, `FoldChecker:97`, `SpecParser:1955` | low | usually loud (shape guards follow) | low |
| 10 | `AssociationSynthesis:324` (#15) | low | silent: same-package sibling beyond imports | low |
| 11 | `ScanRelations:1738`, `TestBody:2365` | n/a | lineage / harness only, never in emitted SQL | informational |

**Silence, not frequency, is what makes 1-3 critical.** A loud miss is a bug report; a silently
substituted store is a wrong answer that ships.

---

## 10. What NOT to do

- **Do not "fix" this by deleting the bare-name arms of `findDatabase`/`findClass`/`findJoin`
  in one commit.** The comments at `ModelBuilder:777-779` and `StoreSubstitutionRewrite:237-242`
  both record real families that regressed under stricter qualification. Make resolution total at
  `NameResolver` **first** — including the same-package sibling case — then remove the arms and
  observe what breaks. Order matters.

- **Do not treat the ambiguity guard as a fix, or add more of them.** The guard addresses the case
  that was never dangerous (two candidates → loud miss) and does nothing for the case that is
  (one candidate, wrong package → silent bind). **Widening ambiguity detection makes the failure
  louder without making it rarer.**

- **Do not add a regression test as a corpus family.** §7: isolated-module compilation
  manufactures the single-candidate condition that hides the bug. Only a hand-built
  multi-package model — one imported package *without* the name, one unimported package *with*
  it — can observe it.

- **Do not chase the `lastIndexOf("::")` count.** 15 of 23 are display strings and 1 is dead
  code. Deriving a simple name for an error message, a TDS column, a SQL alias, or a
  `->toString()` literal is correct and must stay. **The rule is about *lookup*, not derivation.**

- **Do not rename `findDatabase`'s parameter to `dbName` and call it documented.** The parameter
  name `dbFqn` is not the problem; accepting a non-FQN is. If leniency must survive the
  transition, give it a **separate, explicitly named entry point**
  (`findDatabaseLenient(String nameOrFqn)`) so every caller that wants it says so, and the
  FQN-only path can be type-checked and audited.

- **Do not assume `engine/` shares the fix.** Its `ImportScope.resolveSimple` (:112-127) takes the
  first wildcard with no known-types check — a *different* defect that a `core` fix will not touch.

- **Do not cite "the corpus passes" in the PR that fixes this.** It did not fail before; it will
  not prove anything after.
