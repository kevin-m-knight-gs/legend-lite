# `isEmpty` in an aggregate position generates invalid SQL

    ->groupBy(~[g], ~[ f0: x|$x.v : agg|$agg->isEmpty() ])

generates

    select "root".G as "g", "root".V is null as "f0" from T as "root" group by 1

and the database rejects it: `"root".V` appears in the select list neither inside an
aggregate nor in the GROUP BY.

`isEmpty` over a collection asks whether the collection has any elements — a genuine
aggregate, and the natural SQL is `count(...) = 0`. What it lowers to instead is the per-row
null test `V is null`, which is what `isEmpty` means for a single optional value. The
aggregate position is lost in translation, so the statement is malformed rather than wrong.

## Why the failure is hard to place

The error arrives from the JDBC driver, quoting SQL:

    Binder Error: column "V" must appear in the GROUP BY clause or be used in an
    aggregate function ... LINE 2: select "root".G as "g", "root".V is null as "f0"

Nothing in it names `isEmpty`, names the property, or points at the mapping. An author sees a
database complaining about a column they never wrote in a GROUP BY they never wrote.
`isNotEmpty` behaves identically.

## Not a general defect in these two functions

Both work in the position the corpus normally uses them — over a to-many end, where the
corpus runs them across 40 services and 105 relationship ends and they are correct. It is
specifically the aggregate position that mistranslates.

## Reproduce

    python3 scripts/corpus/probe_aggregates.py

The two are excluded from its case list with this finding as the reason; restore them to see
the failure.
