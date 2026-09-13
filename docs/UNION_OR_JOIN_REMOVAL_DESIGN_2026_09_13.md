# Union OR-join removal — design (2026-09-13)

Spec source: engine 4.145.0, `core_relational/relational/pureToSQLQuery/pureToSQLQuery_union.pure`
lines 870–1399 and `postprocessor/defaultPostProcessor/removeUnionOrJoinsPostProcessor.pure`.
Corpus rows behind it: testProjectThroughAsso, testProjectThroughAssoWithJoinInMapping,
testChainedUnions, testUnionWithSinglePropertyMapping, testUnionOnViewsMapping (DuckDB + H2 mirrors).
Their rows halves pass today; only the flagged half is missing.

## 1. What the engine does (plain terms)

A union mapping turns a class into a `UNION ALL` of its sets. Every leg carries every set's
primary-key columns (`pk_0_0`, `pk_0_1`, …), its own filled, the others `null`. A join from
that union to a target is spelled as an OR over the per-set conditions:

    left outer join (select … from PersonSet1 union all select … from PersonSet2) as "p"
      on ("p".FirmID_0 = "root".ID or "p".FirmID_1 = "root".ID)

The engine marks that OR as a `UnionOrJoin` (a DynaFunction `or` carrying
`sourceSetToTargetSetPairs`, `firstJoinInChain`, `lastJoinInChain`). OR-joins defeat hash
joins on warehouse engines, so a post-processor rewrites them, when switched on, into
equi-joins through a **bridge**:

    bridge = UNION ALL over (sourceSet, targetSet) pairs of
             select root.pk as "union_gen_source_pk_0", child.pk as "union_gen_target_pk_0"
             from <sourceLeg> root inner join <targetLeg> child on <that pair's join condition>
    source  ⋈(original join type) bridge on source.pk = bridge."union_gen_source_pk_i"
    bridge  ⋈ target                on bridge."union_gen_target_pk_i" = target.pk

The switch: on by default for Snowflake unless the connection's `GenerationFeaturesConfig`
disables `REMOVE_UNION_OR_JOINS`; otherwise on when that config enables it; never when the
execution context asks for the driver-table PK temp table.

### Compatibility (what qualifies)
- The join operation is a `UnionOrJoin`, optionally `and`-ed with target-only milestone filters,
  and mentions only the two aliases.
- A **table** side qualifies when its primary key is known: the source of the first join in a
  chain must be one unique source set with a resolvable PK; the target of the last join one
  unique target set with a PK; an intermediate table needs a declared primary key.
- A **union-all** side qualifies when sets and legs match one-to-one, every set is a root
  relational set with a PK, every leg is a plain table or view with no further joins. Missing
  PK columns are added to the legs as `"union_or_bridge_gen_pk_i_j"` (null in the other legs).

### Cases
1. Both sides compatible → the bridge above.
2. Source compatible, target not (2a: source union, target a subselect; 2b: source table,
   target union) → the target is rebuilt as a union-all whose every leg joins to the target
   and projects the source PKs plus the target's columns; it joins back to the source on PKs.
3. Otherwise the join is left as it is.

## 2. Product value for this platform
An optimizer pass for union mappings: equi-joins instead of OR-joins over null-padded key
columns. Opt-in exactly as the engine's (so the default SQL stays lean under the lean ruling);
the first real consumer of the connection-level feature carrier the platform does not read yet.

## 3. Where it lives here
- Post-processors are compiler passes (ruling): an IR pass over the typed SQL, after lowering.
- The resolver already emits the OR-join with the engine's spelling; it must also emit the
  **provenance** the pass needs (the set pairs, first/last-in-chain) — a semantic marker on the
  join predicate, never re-derived from SQL text.
- Primary keys come from the mapping model (`resolvePrimaryKey` exists in the system layer;
  table primary keys from the store).
- The feature gate reads the runtime's connection (a literal or an element) for
  `GenerationFeaturesConfig.enabled/disabled` — the missing carrier, shared with every other
  connection-level flag.

## 4. Verdict channel
The five tests assert (a) the flagged SQL contains `union_gen_source_pk_0` — keep the engine's
generated names for the bridge columns; (b) flagged rows equal unflagged rows. Rows judge;
the text assert is a marker only.

## 5. Steps and size
1. Connection feature carrier (reader over the runtime's connection; enabled/disabled names): ~60 lines.
2. Join provenance marker at the union-join emission in the resolver: ~40 lines.
3. The pass: qualification (join shape, PKs), case 1 bridge, cases 2a/2b, PK padding of legs: ~300 lines,
   as a dialect-independent IR rewrite gated by the flag.
4. Witnesses: unit tests over a two-set union mapping with the flag on, both cases; rows equal with/without.
5. Corpus: five DuckDB rows and their H2 mirrors. Chain, record, land.
Estimate: two to three sessions. Design first; nothing built until the marker shape is agreed.

## 6. Open questions
- Chained unions (union → union): first/last-in-chain semantics across two joins.
- Milestone filters in the join predicate (the bitemporal rows may align as a by-product).
- Whether our union legs already carry `pk_i_j` for every set (the text says yes: `"root".ID as "pk_0_0", null as "pk_0_1"`).

## 7. The lean alternative (USER question 2026-09-13: "can we not just push the join in before the union?")

Pushing the join into the legs is right for an INNER join with one uniform key, and optimizers
do it themselves. It is wrong in general for three reasons: an OUTER join pushed into legs
produces a null row per non-matching leg (a firm with employees only in set 2 gets a spurious
`(Firm, null)` from the set-1 leg; a firm with no employees appears twice); the rest of the join
tree, filters and aggregations over the root would have to be replicated per leg; and the per-set
join conditions may differ (a different column, expression or table per set — the reason the OR
exists at all).

What the platform SHOULD do under the lean ruling, before any bridge: when every set pair joins
through the SAME source expression, emit ONE merged key column in the union and ONE equality:

    left join (select …, FirmID from PersonSet1 union all select …, FirmID from PersonSet2) as p
      on p.FirmID = root.ID

A plain equi-join over one relation: outer semantics preserved, the database pushes it into the
legs on its own, leaner than both the OR and the bridge. The OR (and the bridge behind the flag)
remain for the non-uniform case. Primary-key columns stay per set regardless (two sets may carry
the same id for different objects; identity needs the set).

Order of work, revised: (1) the merged-key default for uniform conditions — our own lean form,
rows-judged, measured by the corpus (the union rows' unflagged text asserts will DIVERGE by
text, by ruling acceptable when rows match — but two of the five assert exact SQL, so those two
become rows-vs-text decisions); (2) the connection flag carrier; (3) the bridge pass for the
non-uniform case under the flag. (1) is the product win; (3) is the corpus win.

## 8. Text-risk census for step (1) (2026-09-13) — step 1a LANDED (GATES: Lean union join, step 1): coalesce form, 0 rows moved either way

295 union-mapping tests in the corpus; 67 assert SQL text only. All but two run on the
standard fixture helper (with or without a named database) whose before-package setups seed
their tables, so the referee brings the golden to rows and rows judge. The two exceptions are
the failing bitemporal rows whose asserts are SUBSTRING checks over our SQL text
(`contains('"lake_thru_0"')`, the `unionalias_N` nesting) — spelling contracts with no rows
leg by construction. Static text risk of the merged-key default: none. Residual: goldens the
mirror cannot execute fall back to text at run time — only a corpus run shows those.

## 9. Step 1b and the bridge's rows (2026-09-13)

Step 1a (landed) keeps the per-member suffixed keys and coalesces them in the condition.
Step 1b projects ONE shared key per route group (each member its own column under the
shared name; no null padding, no coalesce) — the union synthesis and the join-chain
emission must consult ONE grouping decision (the shared-table-key discipline's pattern),
and the strict member-paired predicate (graph children) keeps its suffixed columns only
when it rides.

Consequence for the five bridge rows: after 1a/1b the uniform case has no OR to remove,
so the engine's bridge only fires on non-uniform routes; the five corpus rows are UNIFORM
mappings run with the flag and assert the bridge's marker column. Decision owed when the
bridge leg starts: apply the bridge to uniform cases too under the flag (engine parity), or
treat the five as text contracts because the lean form is already the better plan.

## 10. Step 1b attempted and parked (2026-09-13) — the finding

Built as designed: one decision (`sharedRouteSuffixes`: single-hop routes of one property with
one raw condition, members on ≥2 distinct tables — the member table derived from the JOIN
definition, never a set-id lookup, since corpus members declare no `~mainTable`; the routing
class's main table declared-or-inferred) consulted by the union body's inbound-key
registration and the routed navigation. The condition side worked
(`on ("personset1_0".FirmID__employees = "root".ID)`). The union body did not: its key
registry is ONE projected name per physical column per member (`srcKeysByOrdinal`:
ordinal → physical column → name), and the REVERSE lift (Person→firm) already projects the same
physical column as `FirmID_0` for its own condition; the shared name overwrote it and the lift's
condition lost its column ("relation has no column 'FirmID_0'"). Two demands on one physical
column need two projected names — a registry of names-per-column, touching the projection
loop, recordKeyThreads, the lift and chain registrations. That is 1b's real cost; parked after
three fix cycles (the rule). Step 1a (coalesce) stays as the lean form; the non-uniform witness
(different key columns per member keep the OR) rides with it.

## 11. Measured plans (DuckDB, witness shape, 1,000 firms × 10,000 people in two tables)

| join condition | operator | time |
|---|---|---|
| `u.k0 = f.ID or u.k1 = f.ID` (engine form) | BLOCKWISE_NL_JOIN | 9.1 ms |
| `coalesce(u.k0, u.k1) = f.ID` (step 1a) | HASH_JOIN | 1.5 ms |
| `u.k = f.ID` (step 1b, merged column) | HASH_JOIN | 1.2 ms |

The plan benefit lives in the predicate: the OR forces a nested-loop join; the coalesce gets the
same hash join as the merged column. 1b is cosmetic (two null-padded columns fewer). DECISION
(USER 2026-09-13, "roll the whole thing back?"): keep 1a; 1b only if the key registry is ever
generalized to several names per column for another reason.

## 12. The bridge measured (DuckDB, same rows on all forms)

| form | joins | 1k firms / 20k people | 20k firms / 400k people |
|---|---|---|---|
| OR (engine default) | 1 BLOCKWISE_NL_JOIN | 12.7 ms | 2.77 s |
| coalesce (step 1a, ours) | 1 HASH_JOIN | 1.8 ms | 3.8–5.0 ms (3 runs) |
| merged column (step 1b, parked) | 1 HASH_JOIN | 1.2 ms | 3.2–3.3 ms (3 runs) |
| bridge (engine rewrite under the flag) | 4 HASH_JOIN | 2.2 ms | 10.5 ms |

On a hash-join engine the bridge is NOT the optimized form: it trades one nested-loop join for
four hash joins (source→bridge, bridge→target, plus the legs). Our coalesce keeps one hash
join. DECISION (USER 2026-09-13, "do the non-uniform one for all and pass the tests?"): no —
the five bridge rows are TEXT CONTRACTS (they assert the marker column under a flag whose
purpose the default already exceeds); the bridge stays a product option for NON-UNIFORM
routes only, where the OR (and its nested-loop plan) remains — with its own witness when
that leg starts, and a cheaper form to look for first (the OR there is over groups of
different expressions).
