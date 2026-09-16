# Most collection functions do not work over a to-many end

`scripts/corpus/probe_collection.py` projects one column per collection function over
`$x.kids`, for three parents owning 3, 2 and 0 children. Three of twenty-three work.

| outcome | functions |
| --- | --- |
| correct | `isEmpty`, `isNotEmpty`, `exists` |
| silently not applied | `first`, `last`, `sort`, `sortBy`, `reverse` |
| internal node-validation failure | `distinct`, `removeDuplicates`, `take`, `drop`, `slice`, `size`, `filter`, `init`, `tail`, `add` |
| rejected with a message | `in`/`contains`, `concatenate` |
| wrong over an EMPTY collection | `count`, `map`, `joinStrings` |

## The four failure shapes

**Silently not applied.** `$x.kids->map(c|$c.v)->first()` returns one row per CHILD instead of
one per parent — six rows where three were asked for. Same defect as F41, and here it changes
the cardinality of the result rather than its content, so every value in the answer is a real
value and nothing looks wrong.

**Internal node validation.** Ten functions fail with

    NODE VALIDATION ERROR: positionBeforeLastApplyJoinTreeNode
    root
    DOESN'T CONTAIN:
    root

The message states that `root` does not contain `root`. Whatever the invariant is, this text
cannot help anyone: it names no function, no property and no mapping, and its one concrete
claim is a contradiction.

**Rejected with a real message.** `contains` gives *"Parameter to IN operation isn't a
literal!"* and `concatenate` gives *"Cannot cast a collection of size 2 to multiplicity [1]"*.
These are at least diagnosable.

**Wrong over an empty collection.** `count` returns 1 where the collection is empty, and `map`
and `joinStrings` return NULL where the identity value is 0 and `''`. This is F6, which the
corpus already quarantines across six services — the probe reaching it independently is a
check that the probe works.

## Why the seed has an empty parent

P3 owns no children. Every aggregate-shaped collection function has a different answer over an
empty collection than over a populated one, and F6 exists only there. Three well-populated
parents would have found none of it.

## Reproduce

    python3 scripts/corpus/probe_collection.py
