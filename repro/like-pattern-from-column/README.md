# `startsWith`/`endsWith`/`contains` are false for every row when the pattern is a column

    startsWith(S, P)   with S = 'alpha', P = 'a'   ->   false

`'alpha'` starts with `'a'`. The query compiles, runs, raises nothing, and answers `false`.

## The four rows

`core/src/test/resources/stress/79-null-boolean.pure` runs each predicate twice — once with a
literal pattern and once with the pattern taken from a column — over four rows:

| row | S | P | `startsWith(S,'a')` | `startsWith(S,P)` |
| --- | --- | --- | --- | --- |
| R1 | NULL | NULL | null | null |
| R2 | `alpha` | NULL | **true** | false |
| R3 | NULL | `a` | null | null |
| R4 | `alpha` | `a` | **true** | **false** ← |

The literal column is correct throughout. Row 4 is the one that matters: both operands are
present, both are non-NULL, and they match. It is not a NULL-handling question.

## Cause

`startsWith` lowers through `likePattern('%s%%')`, whose parameters are prepared by

    function transformLikeParamsDefault(params: String[2]):String[*]
    {
       let likeExpression = $params->at(1)->removeQuotes()->escapeLikeExprDefault();
       ...
    }

The second parameter is assumed to be a string *literal*: its quotes are stripped and the
remainder is interpolated into the quoted LIKE pattern. Given a column, the rendered column
reference itself is interpolated, producing

    "root".S like 'root.P%'

— a comparison against the seven-character text `root.P`, which matches nothing. That single
explanation accounts for all four rows: false wherever S is non-NULL, and NULL wherever S is
NULL, because `NULL like 'anything'` is NULL.

## Why this is the dangerous shape

Nothing fails. There is no type error, no unsupported-function refusal, no exception. A
predicate that should sometimes be true is simply always false, which a downstream `filter`
turns into an empty result set — a plausible answer that no assertion on shape or row count
would question. It survives whatever tests exist because a test written by observing the
engine records `false` as expected.

Three functions share the transform, so `startsWith`, `endsWith` and `contains` are all
affected, and the same helper is used by other dialects — this repro pins DuckDB only because
that is what the corpus runs.

## Suggested fix

`transformLikeParamsDefault` needs to distinguish a literal from an expression, and for an
expression build the pattern with concatenation in SQL rather than by string interpolation:

    S like P || '%'          -- instead of   S like 'root.P%'
