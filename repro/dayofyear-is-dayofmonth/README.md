# `dayOfYear` returns the day of the month on DuckDB

`dayOfYear(2024-06-03)` returns **3**. The correct answer is **155**
(31 + 29 + 31 + 30 + 31 + 3, in a leap year).

## Cause

`duckdbExtension.pure`, two entries fourteen lines apart:

    218:  dynaFnToSql('dayOfMonth',  $allStates,  ^ToSql(format='day(%s)')),
    221:  dynaFnToSql('dayOfYear',   $allStates,  ^ToSql(format='day(%s)')),

Byte-identical. DuckDB's `day(...)` is day-of-month; the function wanted is `dayofyear(...)`.

Every other dialect in the tree is correct, which is the strongest evidence this is a slip
rather than a decision:

| dialect | lowering |
| --- | --- |
| Postgres, Redshift | `date_part('doy', %s)` |
| ClickHouse | `toDayOfYear(%s)` |
| SQL Server | `datepart(dayofyear, %s)` |
| Databricks, MemSQL | `dayofyear(%s)` |
| Spanner | `extract(dayofyear from %s)` |
| Sybase | `datepart(DAYOFYEAR, %s)` |
| **DuckDB** | **`day(%s)`** |

## Suggested fix

    dynaFnToSql('dayOfYear',  $allStates,  ^ToSql(format='dayofyear(%s)')),

## Why it survived

This defect cannot announce itself. Both functions return an `Integer`, so nothing fails to
compile and nothing fails to type-check. For any date in the first twelve days of a month
both answers are small plausible numbers, and a test written on such a date asserting the
engine's own output would record `3` as correct and pin the bug in place permanently.

It is reachable only by computing the expected value from the input date *independently* of
the engine. That is the whole reason this corpus keeps its own evaluator rather than
capturing engine output as the expectation: an expectation read from the thing under test
agrees with it by construction, including where it is wrong.

## Reproduce

    python3 scripts/corpus/probe_functions.py

which reports, among 79 functions that agree:

    1 DISAGREEMENTS
      dayOfYear    engine=3    oracle=155
