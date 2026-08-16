# The regexp functions advertise arities their lowering cannot accept

    regexpLike(S, 'ph')          Execution error: The system is trying to get an element
                                 at offset 2 where the collection is of size 2
    regexpLike(S, 'ph', 'i')     works

Both overloads are in `getSupportedFunctions()`:

    regexpLike_String_1__String_1__Boolean_1_
    regexpLike_String_1__String_1__RegexpParameter_$1_MANY$__Boolean_1_

The lowering is typed to one of them:

    dynaFnToSql('regexpCount',   ... transform={p:String[3]|$p->transformRegexpCount()}),
    dynaFnToSql('regexpIndexOf', ... transform={p:String[4]|$p->transformRegexpIndexOf()}),
    dynaFnToSql('regexpExtract', ... transform={p:String[5]|$p->transformRegexpExtract()}),

so the two-argument form — the one anybody would write first, and the one the registry lists
first — reaches a transform that indexes past its own argument list.

## The arity each one actually needs

| call | result |
| --- | --- |
| `matches(S, p)` | works |
| `regexpLike(S, p, 'i')` | works; two arguments fail |
| `regexpCount(S, p, 'i')` | works; two arguments fail |
| `regexpIndexOf(S, p, 1, 'i')` | works; two and three fail |
| `regexpExtract(S, p, 1, 1, 'i')` | works; two and three fail |
| `regexpReplace(S, p, r)` | **no arity found that works** — three and four both fail |

## Why the diagnostic is the problem

    The system is trying to get an element at offset 2 where the collection is of size 2

is an internal bounds error. It names no function, no argument and no overload, and it
describes the engine's own list handling to somebody who wrote a query. The information the
author needs — "this overload has no lowering; pass the flags argument" — is present in the
transform's type signature and is never surfaced.

## Reproduce

    python3 scripts/corpus/probe_functions.py

The family is probed at the working arity, except `regexpReplace`, which is left at its
natural spelling so the failure is recorded against the function rather than hidden by
choosing a call that happens to pass.
