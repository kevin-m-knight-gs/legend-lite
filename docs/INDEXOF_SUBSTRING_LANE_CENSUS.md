# indexOf/substring — the two-base divergence: full census (2026-08-28)

Evidence package for reopening OPEN_REGISTER A1 (adjudicated
irreducible 2026-08-23, "do NOT re-attempt"). This census does not
overturn that ruling — it explains WHY the reverted draft had to fail,
and shows a DIFFERENT seam whose corpus blast radius is zero by
enumeration. Reopening is a user decision; nothing here is built.

## 1. The divergence (all verified from source)

Pure string semantics ARE `java.lang.String` semantics — three facets:

| facet | pure (the spec) | SQL |
|---|---|---|
| base | 0-based | 1-based |
| `substring` 3rd arg | END index, exclusive | LENGTH |
| `indexOf` miss | −1 | 0 |

- **Both engine Java lanes implement the spec verbatim**:
  `CompiledSupport.java:1531/1546` (`str.indexOf(toFind)`,
  `str.substring(start, end)`); interpreted `IndexOfString.java` /
  `SubString.java` identical. The native-adapter Essential manifest
  (`pct_essential_native.json`) has **zero exclusions** — the Java
  reference passes all 327, including our 7 rows. The PCT asserts are
  not wishes: they are the shipped Java-lane production contract.
- **The engine SQL lane is a verbatim passthrough**:
  `dynaFnToSql('indexOf', … 'LOCATE(%s)')` (h2Extension2_1_214:231),
  `'substring%s'` (:261) — no adjustment, and the engine's own corpus
  goldens byte-pin the unadjusted text AND its 1-based values
  (`testSqlFunctionsInMapping.pure:590`: `select locate(…)`, rows
  `[12,12]`).
- The engine never reconciled; production users rely on BOTH (a
  relational service and an m2m service in one codebase see different
  bases for the same source line). Hyrum's law finished the job.

## 2. Why the 2026-08-23 draft broke passing rcorpus tests

The corpus pins SQL semantics in TWO places; a seam drawn at
"pure-function vs mapping-DSL dynafunction" only protects the first:

- **Bucket A — mapping DSL**: `indexOfResult: indexOf('String
  Random','o')` in a Relational block (values [12,12] pinned).
- **Bucket B — THE KILLER**: real pure `substring` inside query
  lambdas, AUTHORED IN SQL SEMANTICS:
  `testWithFunction.pure:198` — `$a.name->substring(9,1)` extracts the
  digit of 'Account 1' (start 9 is 1-based, `1` is a LENGTH; the call
  is INVALID under pure semantics — end 1 < start 9). Rows AND
  byte-exact SQL text asserted. `:102` pins `substring(1,5) == 'John'`
  the same way. Production pure inside store queries EXPECTS SQL
  semantics because it only ever executes as SQL.

In OUR pipeline the two buckets are indistinguishable downstream
anyway: `RelOpTranslator.java:531` translates mapping-DSL dynafunctions
into ordinary pure `AppliedFunction`s — so any pure-fn-scoped flip hits
bucket B (and A) and fails the pinned rows + sql-text goldens. That is
exactly "corpus tests we already pass started failing."

- **Bucket C — value space in the corpus**: exactly ONE site in the
  whole core_relational test universe (`planExecutionTestUtility.pure
  :132` `sortCsv`, Java-style CSV splitting) — and it has ZERO
  consumers. Dead. **The corpus contains no live value-space
  indexOf/substring.**

## 3. Our side (the emission census)

- ONE emission owner: `lowering/Scalars.java` RULES —
  - `substring` (all arities): verbatim `SUBSTRING(args)` (:1378).
  - string `indexOf` 2-arg: verbatim `STRPOS` (:1436).
  - string `indexOf` 3-arg: OUR OWN suffix-search composition in
    1-based convention (:1409) — the engine has NO translation for
    this overload (DuckDB manifest: "No SQL translation exists").
  - **collection `indexOf`: ALREADY SPEC-CORRECT** — `COALESCE(
    LIST_POSITION,0) − 1` = 0-based, −1 miss (:1397). Divergent bases
    per overload already live in the one table.
- `Scalars.lower(call, args)` receives NO lane context — the lane fact
  must ride the NODE. The seam implementation is therefore a resolver-
  side callee rewrite (engine-spelling twins) inside SQL-lane
  subtrees; `Lowerer.java` (3464/3500, frozen) is untouched.
- `sql/dialect/SubstringClamp.java`: DuckDB start<1 → 1 (H2 tolerates
  0 natively). Consequence: every `substring(0, n)` shape is
  ACCIDENTALLY pure-correct today (at start 0, pure's end-index ≡
  SQL's length). This is why `removeDuplicatesBy` (key
  `substring(0,1)`) and `substring.pure`'s start-0 asserts pass now —
  and why they KEEP passing under spec emission (values identical).
- **The stdlib channel is CLOSED**: platform/engine helper functions
  that compose substring (`toUpperFirstCharacter`, `left`, `right`,
  `pad`…) are registry NATIVES with dedicated emissions
  (`SqlFn.UC_FIRST` etc., Scalars:483) — their parsed pure bodies drop
  (platform-owned rule). No hidden inlining path exists.
- OUR OWN test pins (6 files): only ONE pins 1-based STRING behavior —
  `ExtendCheckerTest.testIndexOf` (`#TDS…#->extend(~pos: x|$x.str->
  indexOf('o'))` asserts 5, comment says 1-based deliberately) — a
  RELATION-paradigm context, which stays SQL-lane under the seam
  (correct, unchanged). The rest are collection-indexOf (already
  0-based) or start-0 coincidence shapes.
- The 2 sort PCT rows run fully today and fail on ORDER only (key
  `substring(1,2)`); the whole expression is a pure-collection sort —
  value space — so the seam covers them with no sort-machinery change.

## 4. THE SEAM (the census's product)

The engine's lane rule, transposed: **the semantics follow the TYPE of
data the expression computes over.**

- Store-anchored subtrees (class-mapped queries — everything the
  resolver routes) and Relation-typed pipelines (`#TDS`/table
  relation ops): **SQL semantics** — today's emission, verbatim,
  UNTOUCHED (buckets A + B + the relation lane the 355-suite pins).
- Bare value expressions (scalars, `T[*]` pure collections): **spec
  semantics** — flip 4 overload emissions (string indexOf ×2,
  substring ×2). `POSITION(...) − 1` yields the −1 miss for free;
  `substring(start+1, end−start)` handles both remaining facets.

Statically decidable at every node from info we already carry (store
anchors + Relation-typed sources vs plain multiplicities).

**Blast radius under this seam, enumerated:** corpus = 0 (buckets A/B
untouched, bucket C dead); reference parity = matches the Java lanes
where the engine's own DuckDB adapter fails (B-FIXES-A, the
established category — 9 precedent rows); PCT gains up to 7 Essential
rows (316→323 candidate); our own pins = 0 moved.

## 5. Open items (the design leg's §2, if chartered)

1. **The composite case** — a value-space `let` whose result feeds a
   store/relation lambda (engine: router `inScopeVars` evaluate
   Java-side, splice as constants). No live corpus shape exists
   (bucket C empty), but OUR `queryLets`/`SeedableLets`/inliner path
   must be audited so provenance survives inlining; needs its own
   witnesses + engine-source grounding.
2. Engine ground truth for the NEW relation lane's string translation
   (assumed verbatim like the store lane; corroborated indirectly by
   the DuckDB adapters, not yet read from the relation-to-SQL source).
3. The 3-arg indexOf convention (ours, not the engine's) gets a
   spec-semantics twin in value space; its store-lane spelling stays.
4. OPEN_REGISTER A1 says "do NOT re-attempt" — reopening requires an
   explicit user ruling superseding 2026-08-23, with this census as
   the distinguishing evidence (the failed draft's seam ≠ this seam).
