# VERIFY-PATH ARM AUDIT (2026-08-28)

User charge: "how many hacks/fallbacks/shortcuts/compensations do we
already have in our asserts — would the pk/u_type probe be yet
another?" Scope: the SQL-ASSERT verify path — every decision arm an
sql-text assert can flow through, from routing to verdict.
`EngineTestExecutor.sqlTextVerify / evalSideText / h2Upgrade` +
`H2Verify` (the replay oracle). The VALUE-assert path (PureAsserts,
wire equality) has its own audited registers and is out of scope
here.

Classification key (the audit-rederivation question applied per arm —
"does it re-derive at consumption what construction knew?"):

- **POLICY** — comparison semantics a two-engine referee must define.
  Legitimate; the arm IS the spec.
- **DISPATCH** — structural routing on typed/compiled facts. Neutral
  surface area: each is a place to be wrong, but nothing is
  re-derived.
- **DECLINE** — a named, counted refusal to verify. Honest by
  construction (visible residue), but each one is coverage lost.
- **COMP** — compensation: the referee re-derives at consumption a
  fact that construction (the lowerer / the engine generator / the
  recorder) knew structurally. The smell class.
- **SNIFF** — dispatch on an error message or value shape. The worst
  class; ErrorShapeGuardrailTest exists to kill these elsewhere.

## The table

Routing (EngineTestExecutor):

| # | arm | where | class | re-derived fact (COMP/SNIFF only) |
|---|---|---|---|---|
| 1 | plan-text asserts routed to literal plan compare | :2249 | DISPATCH | |
| 2 | 3-arg h2Compatible → verify against the NEW golden | :2257 | DISPATCH | |
| 3 | mixed side (sql text AND value reads) → unsupported | :2269 | DECLINE | |
| 4 | sql-producer content routing (containsSqlProducer) | :2160,:2269 | DISPATCH | walks the arg tree to find what the RESOLVER already knows is a producer call — borderline COMP, tolerated: routing must precede evaluation |
| 5 | predicate over sql text: evaluate for real; diverged = recorded | :2173-2200 | POLICY | |
| 6 | predicate wall / mixed → named advisory | :2199 | DECLINE | |
| 7 | evalSideText: side evaluates AS WRITTEN, non-string re-evaluates via sqlRemoveFormatting FQN | :1328 | POLICY | outcome-driven, corpus's own definition — the slice-3 replacement of the old terminal surgery |
| 8 | evalSideText failure → counted decline | :1364 | DECLINE | |

sqlTextVerify verdict arms:

| # | arm | where | class | re-derived fact |
|---|---|---|---|---|
| 9 | both-ours (no golden literal): two OUR-side texts compare | :1241 | POLICY | |
| 10 | byte-match → execute OUR text on H2, rows vs DuckDB rows | :1255 | POLICY | |
| 11 | match + unverifiable → match-noreplay advisory | :1270 | DECLINE | |
| 12 | text diff → golden executes on H2, rows referee (rescue) | :1283 | POLICY | |
| 13 | diff + unverifiable → diff-noreplay + cause | :1296 | DECLINE | |
| 14 | no generator → golden-only replay tail | :1300 | DECLINE-or-POLICY | |
| 15 | h2Upgrade: foldString golden pick + rootExecVar walk | :1396-1410 | COMP | re-finds the exec variable / golden literal the DISPATCHER already identified when it classified the assert |
| 16 | h2Upgrade early declines (ready/recording/arity/no-golden/no-var) | :1390,:1408 | DECLINE | |
| 17 | h2Upgrade catch-all → counted decline | :1455 | DECLINE | |
| 18 | per-key/per-column enum decode derivation (mappingFqnOf + decodeOf via ExecCallFinder) | H2Verify :857+ | COMP | re-walks the assert to find the exec call's MAPPING and re-derives the decode the COMPILED MODEL owns |

H2Verify oracle:

| # | arm | where | class | re-derived fact |
|---|---|---|---|---|
| 19 | session-direct (H2 backend) vs seed replay routing | :443 | DISPATCH | |
| 20 | mirror (incremental family session) vs fresh replay + poison | :293 | DISPATCH | perf machinery; poison = honest cache of a dead mirror |
| 21 | Collection/Scalar frames → non-tabular decline (PARKED) | :275,:473 | DECLINE | parked on the set-vs-row adjudication |
| 22 | **case-collision retry: catch "Duplicate column name" → rerun on ENGINE_CASED** | :330 | **SNIFF** | the fact "goldens are engine-cased text" is STATIC — known before any error fires |
| 23 | Tabular positional vs Graph label-mapped compare | :496 | DISPATCH | |
| 24 | **bookkeepingAlias regex (pk_$i(_$j), u_type, from_z/thru_z/in_z/out_z, k_*)** | :520 | **COMP** | which columns are assembly bookkeeping — the ENGINE GENERATOR knew; the referee re-derives BY NAME PATTERN |
| 25 | **frame-side businessDate/processingDate echo drop** | :646 | **COMP** | which json keys are context echo — OUR LOWERER knew (it emitted them) |
| 26 | **empty-frame row-count verdict incl. all-NULL-row drop** | :619 | **COMP** | exists only because the compared artifact (json) lost the row set |
| 27 | graph nesting / duplicate alias / key-set mismatch declines | :554,:612,:650 | DECLINE | all three exist only because the compared artifact is post-assembly json |
| 28 | per-key enum decode application + underivable decline | :574 | COMP | see #18 |
| 29 | temporal decode of json text, type-driven by golden JDBC type | :666 area | COMP | the json carrier lost the type; the FRAME knew it |
| 30 | golden-side enum decode application (tabular) | :726 | COMP | see #18 |
| 31 | column-arity decline (tabular) | :744 | DECLINE | |
| 32 | order-insensitive multiset compare (both paths) | :770s | POLICY | engine discards order by design; [ord] counted |
| 33 | row-cardinality skew → named decline | :790 | DECLINE | parked on the SAME set-vs-row adjudication as #21 |
| 34 | norm(): integral-exact / 10-digit float floor / microsecond temporal floor / temporal spellings | :928 | POLICY | cross-engine representation limits, each with a measured receipt |

## Counts

POLICY 8 · DISPATCH 6 · DECLINE 11 · **COMP 8 · SNIFF 1**.

The pattern is exact: **7 of the 9 smell-class arms (#24-29 + #27's
three declines) exist for one reason — the referee compares the
POST-ASSEMBLY artifact (the instance json) against the engine's
PRE-ASSEMBLY rows.** The artifact has discarded columns (pk, u_type,
periods), types (temporals as text), and the row set itself
(empty/nested cases). Every one of those arms re-creates a fact the
flat rows still carried. The 8th COMP (enum decode #18/28/30) and the
1 SNIFF (#22) have independent one-line cures.

## Consolidation design (net: arms go DOWN, verification goes UP)

**Seam A — compare at the pre-assembly rows.** The lowerer already
builds the flat select (engine-aliased: pk_$i, u_type, from_z, k_*)
and then splices the json aggregation on top (StreamingGraphRoot /
the graph egress). Expose that stage to the referee: for a
Graph-frame assert, the oracle executes OUR FLAT SELECT on the
session (DuckDB — same engine that produced the frame) and compares
its rows to the golden's rows over the golden's COMPLETE column list
— pk and discriminator included, no exclusions except nothing.
Implementation shape: the executor records the pre-assembly
SqlSelect (or its rendered text) on the Graph result the same way
the activity log rides the result envelope — a compile-time fact
riding the artifact (the instrument-state tenet), not a harness
re-derivation.

DELETED by Seam A: #24 bookkeepingAlias, #25 context-echo drop,
#26 empty-frame arm, #27 all three structural declines, #29 temporal
json decode, and goldenGraphCompare wholesale (the label-mapped
compare collapses into goldenRowsCompare by-label alignment).
KEPT: #23 becomes "Graph frames fetch their recorded flat stage" —
one dispatch, no special compare.

**Rule B — goldens always execute on the engine's session.** The
golden is the engine's own text; ENGINE_CASED (H2Defaults verbatim)
is its only correct home. Replay seeds into an ENGINE_CASED session
unconditionally for the golden side; the CASE_INSENSITIVE session
remains what it always was — OUR DuckDB-parity mirror. Deletes the
SNIFF retry (#22): no error inspection, one rule. Perf: keep the
incremental-mirror idiom by opening the family mirror ENGINE_CASED
alongside (same ledger, same poison discipline) — measured against
the 12-minute chain budget before landing; fresh-replay-per-assert
is the fallback if the second mirror is cheap enough not to matter.

**Cure C — enum decode rides the frame.** The executor KNOWS the
mapping when it executes (it resolved it); stamp the per-column /
per-property decode (or just the mapping FQN) on the result envelope
at execution time. Deletes the ExecCallFinder re-walk (#15's twin,
#18): the referee reads a fact instead of re-deriving it.

**Explicitly NOT in scope:** the set-vs-row conformance leg (#21,
#33 stay parked declines until that adjudication) and the equality
policy (untouched per standing directive).

## Acceptance

- Arm count: 34 → ~24 (COMP 8 → ≤2, SNIFF 1 → 0); the census table
  above re-audited in the landing record.
- exec-passing GROWS (keys tail 6 + case-replay residue revisited +
  full-column verification upgrades existing passes in place);
  UNABLE-TO-EXEC shrinks or holds; zero test regressions; disagree
  pin untouched.
- Full-column compare = pk/u_type/period columns VERIFIED for every
  graph assert — closing the identity-verification gap flagged by
  the user, with no reserved-key leakage into user-visible json.
- Chain stays under the 12-minute budget (measure the ENGINE_CASED
  mirror before adopting it).
