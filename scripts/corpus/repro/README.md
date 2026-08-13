# Minimized reproductions

Each directory is the smallest model that reproduces a finding in
`docs/UPSTREAM_FINDINGS.md`. Run from the repo root:

    cd tools/engine-runner && mvn -o compile -q
    CP=target/classes:$(cat cp.txt)

    # F1 -- mixed ColSpec/FuncColSpec array
    D=../../scripts/corpus/repro/mixed-colspec
    java -cp $CP perf.TestableMain $D/base.pure $D/svc_bare.pure    # clean diagnostic
    java -cp $CP perf.TestableMain $D/base.pure $D/svc_func.pure    # compiles
    java -cp $CP perf.TestableMain $D/base.pure $D/svc_mixed.pure   # ClassCastException

    # F2 -- empty string cannot survive ###Data CSV
    D=../../scripts/corpus/repro/empty-string
    java -cp $CP perf.TestableMain $D/base.pure $D/probe_empty.pure --testable=demo::S_empty
    # the assertion is deliberately '[]' so the report prints the actual payload:
    #   [ { "fn":"bare-empty","ln":null }, { "fn":"quoted-empty","ln":null }, ... ]

    # F6 -- count() over an empty to-many returns 1
    D=../../scripts/corpus/repro/count-empty
    java -cp $CP perf.TestableMain $D/model.pure --testable=demo::CountEmpty
    # HasNone.employeeCount comes back 1; it must be 0.

    # F8 -- derived property evaluated on the absent-association padding row
    D=../../scripts/corpus/repro/derived-on-absent
    java -cp $CP perf.TestableMain $D/model.pure --testable=demo::DerivedOnAbsent
    # S3 has no trade at all, yet "settled" comes back false rather than null --
    # indistinguishable from S2, whose trade exists but is unsettled.

    # Constraints are not enforced in relational projection (established behaviour)
    D=../../scripts/corpus/repro/constraint-violation
    java -cp $CP perf.TestableMain $D/model.pure --testable=demo::ConstraintViolation
    # T2 (-50.0) and T3 (0.0) both violate quantityIsPositive and both come back.

    # F9 -- a table named ORDER generates unquoted DDL
    D=../../scripts/corpus/repro/reserved-word-table
    java -cp $CP perf.TestableMain $D/model.pure --testable=demo::ReservedWordTable

    # F10 -- graph fetch raises where a projection returns null. Same model, same data;
    # only the query differs between these two.
    java -cp $CP perf.TestableMain ../../scripts/corpus/repro/unmapped-enum/model.pure \
         --testable=demo::UnmappedEnum          # side comes back null
    java -cp $CP perf.TestableMain ../../scripts/corpus/repro/enum-graphfetch/model.pure \
         --testable=demo::UnmappedEnumGraphFetch # raises

    # F11 -- Relation projection rejected over a ModelChainConnection; F12 -- the
    # EnumerationMapping is not applied through one. Same model, four services.
    D=../../scripts/corpus/repro/m2m-relation
    java -cp $CP perf.TestableMain $D/base.pure $D/svc_relation.pure   --testable=dest::S_relation    # fails
    java -cp $CP perf.TestableMain $D/base.pure $D/svc_legacy.pure     --testable=dest::S_legacy      # works
    java -cp $CP perf.TestableMain $D/base.pure $D/svc_graphfetch.pure --testable=dest::S_graphfetch  # works
    java -cp $CP perf.TestableMain $D/base.pure $D/svc_probe.pure --testable=src::S_relTds  # side = BUY
    java -cp $CP perf.TestableMain $D/base.pure $D/svc_probe.pure --testable=dest::S_m2mTds # side = B

    # F13 -- Otherwise takes opposite branches on the two execution paths
    D=../../scripts/corpus/repro/otherwise
    java -cp $CP perf.TestableMain $D/model.pure --testable=demo::OtherwiseProbe
    #   Ada -> "Cached Inc"     Grace -> null            (never falls back)
    java -cp $CP perf.TestableMain $D/model.pure $D/graph.pure --testable=demo::OtherwiseGraph
    #   Ada -> "Real Firm Ltd"  Grace -> "Real Firm Ltd" (never uses the cache)

    # F14 -- groupBy on an enum-mapped column groups by the SOURCE CODE. Both engines.
    D=../../scripts/corpus/repro/groupby-enum
    java -cp $CP perf.TestableMain $D/model.pure --testable=demo::GroupByEnum
    # two rows both keyed "BUY" (100.0 and 200.0) instead of one BUY=300.0

    # F15 -- XStore navigation: unsupported in a projection, works in graph fetch
    D=../../scripts/corpus/repro/xstore
    java -cp $CP perf.TestableMain $D/model.pure --testable=ab::XStoreProbe   # Match failure
    java -cp $CP perf.TestableMain $D/model.pure $D/graph.pure --testable=ab::XStoreGraph  # works
