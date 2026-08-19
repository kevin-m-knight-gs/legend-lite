# Two defects in aggregates over a `{target}` self-join

A self-join relates a table to itself through a condition rather than a foreign key:

    Join T_Above(T.GRP = {target}.GRP and T.RNK < {target}.RNK)

It is how you express "the next pillar out the curve", "this trader's reports", "orders ahead
of this one in the book". Six rows in three groups are enough to show both problems.

## F51 — `isEmpty()` duplicates the source row

    ->project(~[id: x|$x.id, noneAbove: x|$x.above->isEmpty()])

returns **seven** rows for six inputs. R1 has two rows above it and comes back twice; R3 has
none and comes back once. The pattern is one row per joined row, with a floor of one from the
outer join — an aggregation that was never applied.

Every boolean is correct. That is what makes it hard to see: the failure presents as
duplicate rows, so it reads like a data problem rather than a query one, and on a fan-out of
one it does not present at all.

Two controls narrow it:

* `->count()` over the identical end returns **six** rows. So it is `isEmpty`, not aggregates.
* `->isEmpty()` over a to-many to a **different** table returns **six** rows. So it is the
  self-join, not `isEmpty`.

It is not the inequality either: the same duplication happens over
`T.GRP = {target}.GRP and T.ID <> {target}.ID`, which is an equality self-join.

## F52 — both ends of the association return the same set

    Association ineq::Above { below: ineq::P[*]; above: ineq::P[*]; }

`above` and `below` are opposite directions of one inequality. Asked together:

| row | `above->isEmpty()` | `below->isEmpty()` | correct `below` |
| --- | --- | --- | --- |
| R1 (rank 1) | false | false | **true** |
| R3 (rank 3) | true | true | **false** |

`below` is answering with `above`'s set. The condition is written from one side —
`T.RNK < {target}.RNK` says *the target is higher* — and the reverse end means the opposite,
which requires swapping which row plays `{target}`.

Nothing in the model distinguishes the two ends: same owner, same target, same join. This
corpus's oracle cannot infer it either, and says so — `oracle.SELF_JOIN_REVERSE` is a
declared list, on the same footing as `XSTORE_LINKS`, precisely because guessing would return
a well-formed set from the wrong direction.

## Also visible here: F6

`count()` over the empty half returns **1**, not 0 — R3, R5 and R6 each report one row above
them when they have none. Already reported; noted because it appears in the same probe and
is why `CV3_PillarNeighbours` filters out the longest pillar rather than asking about it.

## Reproduce

    python3 scripts/corpus/probe_ineq_aggregate.py

      FAIL    IneqIsEmpty          6 rows expected, 7 returned
      FAIL    IneqCount            6 rows expected, 6 returned     (F6: values, not rows)
      FAIL    EqIsEmpty                                            (F51 without an inequality)
      PASS    ChildIsEmpty                                         (the control)
      FAIL    TwoIneqIsEmpty                                       (F52)

In the corpus proper: `stress::CV3_PillarNeighbours` covers the construct in the form that
works, `stress::CV6_PillarEmptiness` pins F51 and `stress::CV7_PillarsShorter` pins F52.
