# Foundations program — post-close deep audit (2026-08-17)

User-directed, after the §11 close (`1754aebe`) and the first resumed burn batch
(cluster 60, `1732d552`). Method: the same census discipline that caught the
F3.1c/d/e gap in the mid-program audit — every claim re-verified by FRESH
EXECUTION or FRESH GREP at HEAD, never by re-reading the item list.

## 1. The executable truth — PCT and corpus, actually re-run

| Surface | Command re-run at HEAD | Result |
|---|---|---|
| **PCT** | `mvn -pl core install` then `cd pct && mvn clean test` | **1110/1110, 0 failures 0 errors 0 skips** (Essential 327, Standard 204, Relation-DuckDB 348, Unclassified 94, Grammar 136, discipline 1) |
| **Corpus (DuckDB, G4)** | full sweep, exclusive | **2337/2575**, scoreboard consistent, full chain GREEN |
| **Corpus (h2, G5)** | full sweep | **2283/2575**, the SAME 6 named `already exists` seed collisions (F7.1's one tolerated gap), 0 statement-tolerance firings |
| Soft-pass reconciliation | committed line | 2337 = **1412 clean + 925 soft** (union of sqldiff 247 / advisory 293 / 0-asserts 27 / text-rescued 613) — sums |
| Verdict coverage | mechanical join: scoreboard red rows × (diagnoses.csv ∪ BURNDOWN_EXPLANATIONS ∪ F6.1 family entries) | **238/238 covered, 0 uncovered** |

Scope caveat that stays true (recorded, not falsified): PCT's 1109 is 1,109 of the
1,203 upstream tests — the Variant (88) and Quant (6) scopes are not wired (§9).

## 2. Deletion claims — all verified deleted by grep at HEAD

| Claim | Census result |
|---|---|
| F4.3 harness CSV/TDS renderer + strips (csvText/csvCell/csvEquals/csvJoinedEquals/csvRowEquals/tdsStringEquals/harnessTdsText/csvProbe) | 0 occurrences |
| F6.1 fabrications (randomUUID, activities fold) | 0 in main (the only `executionTraceID` is PlanNode's engine-constant template + a doc comment) |
| F6.2 u_map__ null strip | gone |
| F6.3 coerceTemporal | exactly ONE caller — `Eval.flatten`'s byte[] JSON-carrier branch |
| F6.5 `String.valueOf(x).equals(String.valueOf(y))` | 0 |
| F7.1 rawSqlFailureSink | 0 occurrences repo-wide |
| F7.2 CSV token-shape regex | 0 |
| A5 MessageDigest in testdatagen | 0 |
| A20 double-encoded `data` | 0 |
| F5.3B overlay / null-scan | comment-only mentions (historical documentation of the deletion) |

## 3. One-owner / guard claims

- JSON read: `sql/Json` + the two DOCUMENTED exemptions in its header (server/Json
  fail-fast HTTP reader by policy; verified present and accurate).
- Escapes: parser layers delegate DOWN (`TokenStreamCursor.unescapeBody` →
  `Escapes.unescapeJavaLike`; IslandScan/ElementParser route through it).
- Substitution `SourceSubst`, ASOR `AsorRef`(+SnapshotEnvelope) — in main, sole owners.
- F7.8 landing intact: 23 `orElseThrow` sites + 10 through the documented
  `MissProbe.knownMiss` funnel.
- Concealment counters all live: `M1_RESCUED` printed on sweeps, H2Verify decline
  census registry-gated (shrink asserts fire on scoped runs), `LL_TOL`/ord-leniency
  instrumentation present (8 sites).
- Falsified self-claims: ENGINEERING_LOG's zero-compensation line is struck through;
  `sql/Json` header accurate; `Column.java` corrected at the close.
- nlq compile validation live (`Compiler.compileQuery` in the retry loop).

## 4. Findings (2, both small — FIXED IN THIS COMMIT)

1. **F7.7 escapees in the same file it fixed**: `walkFilter` still dispatched on
   `endsWith("instanceOf")` and `endsWith("equal")`. Fixed to exact FQNs
   (`meta::pure::functions::meta::instanceOf`, `meta::pure::functions::boolean::equal`,
   verified against the registered signatures).
2. **§9.2 metric-table precision**: "rendering: one owner" overstated — the PCT wire
   formatter (`formatAsTds`/`formatValue`) is a DELIBERATE host-side survivor until
   the §9 F4.4 Lowerer-root-mode leg. The closing table now says so.

## 5. Non-findings worth recording

- The F1.3b funnel proof was a land-time deliberate-violation probe; the permanent
  guard is the ArchUnit rule + reflection ban — matches the claim's wording.
- The burn's first resumed batch itself exercised the guards: cluster 61's draft
  tripped FOUR of them (regression assert, code-shape, default-literal, native-pin)
  and was reverted per §0.3 rule 3 — the foundation behaved exactly as built.

**Verdict: the program's claims hold under fresh execution and fresh census.
PCT and corpus passes are real; the two audit findings were precision defects,
not concealments, and are fixed.**
