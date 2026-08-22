# `graphFetch` of a sub-object whose set is mapped in an INCLUDED mapping

    Mapping down::M ( include up::M   ... down::Root ... )

    |down::Root.all()->graphFetch(#{ down::Root { rootId, midByProperty { midName } } }#)
                     ->serialize(#{ down::Root { rootId, midByProperty { midName } } }#)

fails before the query runs:

    Error initializing test suite session for 'S_IncludedSubObjectProperty_suite'
    Caused by: Execution error at
      (resource:/core_relational/relational/graphFetch/relationalGraphFetch.pure
       line:557 column:68),
      "Cast exception: RelationalPropertyMapping cannot be cast to XStorePropertyMapping"

`down::Root` is mapped in `down::M`; `up::Mid` is mapped in `up::M`, which `down::M`
includes. `midByProperty` is an ordinary `RelationalPropertyMapping` over an ordinary join
between two tables in one database — nothing about it is cross-store. `XStorePropertyMapping`
is what Legend uses when a property's two ends live in DIFFERENT stores, so the graphFetch
path has concluded that an included mapping means another store.

## It is the include, and nothing else

Seven cases over one model, each in its own file and its own JVM — the failure is at
INITIALISATION, so it takes the whole file down and two cases in one file would report one
result between them.

| case | root's mapping | sub-object's set from | |
| --- | --- | --- | --- |
| `SameMappingSubObject` | `up::M` | `up::M` | initialises |
| `SameMappingSubObjectAssoc` | `up::M` | `up::M` | initialises |
| `IncludingMappingNoSubObject` | `down::M` | — | initialises |
| `IncludedRootAndSubObject` | `down::M` | `up::M` (root too) | initialises |
| `IncludedSubObjectProperty` | `down::M` | `up::M` | **INIT-ERROR** |
| `IncludedSubObjectAssoc` | `down::M` | `up::M` | **INIT-ERROR** |
| `IncludedSubObjectTwoHops` | `down::M` | `up::M` | **INIT-ERROR** |

The last row of the passing half is the one that pins it. `IncludedRootAndSubObject` fetches
the same tree over the same join through the same including mapping — the only difference is
that its ROOT is also in `up::M`. So it is not the include, not the edge style, not the hop
count, and not graphFetch in general: it is an edge that LEAVES the including mapping and
lands in a mapping that mapping includes.

That also explains why a large corpus never hit it. ~150 domain mappings included side by
side into one `stress::AllMapping` are SIBLINGS: no include relation holds between any two of
them, and graph fetches across them are fine. The nesting only appeared when a project that
itself includes another project's mapping was linked in.

## The same edge PROJECTS correctly

    |fee_core::FeeSchedule.all()->project(~[ ..., bucketLadderName: x|$x.bucket.ladder.name ])

passes, reading TWO hops across the same include boundary and returning the right rows. So
the mapping is resolvable and the join lowers correctly; only the tree form fails.

## Cost

It is fatal at test-suite initialisation rather than at execution, so it cannot be
quarantined — a quarantine excuses a failure and this one never reports one. Every other
service sharing the JVM batch reports nothing, which surfaces as a wall of `MISSING` naming
no cause.

Reproduce with `scripts/corpus/probe_graphfetch_included_mapping.py`.
