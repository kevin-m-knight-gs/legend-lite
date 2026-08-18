# DuckDB syntax probes (moved out of the test tree)

Phase-8 zero-assertion item (FOUNDATIONS_PLAN §9): these two files ran
under surefire as "tests" but assert nothing — they are println probes
of DuckDB behavior (UNNEST-in-SELECT expansion with unequal array
sizes; variant/JSON load shapes). A test that cannot fail is not a
test; as recorded probes they keep their evidentiary value here, next
to the rest of the backend-probe evidence. They are plain Java files —
compile and run by hand against a DuckDB JDBC jar when re-probing.
