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
