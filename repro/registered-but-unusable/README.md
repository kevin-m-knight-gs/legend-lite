# Five functions are in `getSupportedFunctions()` and cannot be executed

`getSupportedFunctions()` in `pureToSQLQuery.pure` is the map the engine consults before
reporting *"No SQL translation exists for the PURE function"*. It reads as the authoritative
list of what a relational query may contain. Probing every entry from a real property mapping
finds five that are listed and still fail.

## Three different ways to be listed and unusable

    previousDayOfWeek     [unsupported-api] The function '...' (state: [Select, false])
    mostRecentDayOfWeek   is not supported yet
    between
    parseBoolean

    eq                    dyna function [eq] is not registered in
                          meta::relational::functions::sqlQueryToString::DynaFunctionRegistry

The first four route to a handler that refuses. `eq` is different and worse: it is present in
`getSupportedFunctions()` and absent from the `DynaFunctionRegistry` that the lowering
consults, so the engine holds two registries that disagree about the same function.

`between` and `parseBoolean` are ordinary functions. A real query is likelier to contain
either than most of the 292 names in the registry.

## Why an over-reporting registry matters beyond these five

The refusal arrives *after* a query is built, planned and dispatched. Anything that consults
the map to decide in advance whether a query is expressible — a planner choosing between
relational and in-memory execution, an editor disabling unavailable functions, a generator
building queries from the registry — is told yes and finds out later.

## A separate trap in the same area

`adjust` and `dateDiff` are supported, but their `DurationUnit` argument cannot be written
the obvious way. Inside a relational property mapping `X.Y` is a table-and-column reference,
so `DurationUnit.DAYS` is read as a table:

    Can't find table 'DurationUnit' in schema 'default' and database 'DB'

and the fully qualified `meta::pure::functions::date::DurationUnit.DAYS` is a parse error. The
spelling that works is a plain string literal, `'DAYS'`, because the lowering strips quotes
off the argument. Nothing states this; it is visible only in the transform's source. The same
applies to `formatDate`, whose second argument must be `'ISO8601'` or
`'ISO8601_NanoSecondPrecision'` — a format pattern like `'yyyy-MM-dd'` is rejected at
execution with *"Unsupported DateFormat"*, in every dialect that implements it.

## Reproduce

    python3 scripts/corpus/probe_functions.py

One column per registered function; the probe drops whatever the engine names and retries, so
each refusal identifies itself.
