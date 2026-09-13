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
