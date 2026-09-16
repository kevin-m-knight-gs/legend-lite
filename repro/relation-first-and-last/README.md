# `first()` returns the whole relation; `last()` generates invalid SQL

Two of the relation family's ordinal operations, two different failures.

## `first()` — a silent no-op

    ->project(~[g, v])->sort([~v->ascending(), ~g->ascending()])->first()

over four rows returns **four rows**. No error, no warning; the operation is simply not
applied. `core/src/test/resources/stress/80-relation-first.pure` asserts the one row that
`first` means and fails.

This is the worst shape a query defect can take. The result is well-formed, correctly typed
and correctly ordered — it is the right answer to a different question. `first()` is
typically written precisely to avoid materialising a large result, so the failure mode is
that the guard against a million rows returns a million rows.

## `last()` — invalid SQL when the columns have different types

    ->project(~[g, v])->sort([...])->last()

    Binder Error: Cannot create a list of types VARCHAR and INTEGER -
    an explicit cast is required
    LINE 2: select (cast(list_valu...

The lowering builds a SQL list out of the row's columns, which only works if every column
shares a type. Any relation mixing a string and a number — that is, almost any relation —
fails. The error comes from the database and mentions neither `last` nor the query.

## Why these were not found earlier

Neither operation appeared anywhere in this corpus before the relation probe. The density
scoreboard counted "relation operations" as one construct, and `project` satisfied it, so
sixteen operations and seven window functions were reported as covered on the strength of the
one that was.

## Reproduce

    python3 scripts/corpus/probe_relation.py

Both are listed in the probe's KNOWN_BAD with the finding that explains them. They are kept in
the case list rather than deleted — a probe that drops what it cannot pass stops being a
measurement.
