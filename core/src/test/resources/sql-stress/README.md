# The SQL stress corpus

Queries far larger than the parity corpus's (its largest: 3 joins, 7 tables, 4 SELECTs, parentheses 4 deep, about
480 characters), each scaled along one axis, plus combined monsters. Each runs raw on Postgres 16 (the answer), then
as a `#SQL{...}#` island on DuckDB and on Postgres: planned (typed, lowered, rendered), timed, the generated SQL
measured, and, when the plan is sane, run and judged by the parity rules (`SqlParity.outcome`).

- `gen.py` writes `queries.tsv` (`python3 gen.py queries.tsv`): joins of 2 to 50 tables of mixed kinds (inner, left,
  right, full) with filters in ON and WHERE, plain and aggregated; FROM subqueries nested to 50; IN and correlated
  EXISTS nested to 30; up to 50 scalar subqueries; CTE chains to 40 that reuse earlier CTEs; set-operation chains
  to 40; CASE, OR, `+` and parentheses 10 to 2,000 deep; select lists 500 wide; IN lists; 50 window functions over a
  join; monsters combining CTEs, a 8 to 16 table join, IN, EXISTS, a scalar subquery and a window.
- `run.sh` runs them, one JVM per query (`com.legend.testcases.SqlStress`); its header says how.
- `baseline.tsv` is the run on main 6c3a6c9 (2026-10-13), before any fix.

## What the baseline says

No query that finished gave an answer other than Postgres's. What does not finish:

| Shape | Planned | Why |
|---|---|---|
| joins of N tables | 10: 0.7 s, 15: 2.4 s, 20: 36 s, 30 and over: past 60 s | `JoinChecker.sideAgnosticTdsCond` types both inputs of every join, then `checkGeneric` types them again: the innermost input is typed 2^N times |
| CTE chains reusing earlier CTEs | SQL 0.8 KB (2), 4.9 KB (5), 27 KB (10, Postgres cannot run it in 60 s); 20: Postgres killed for memory; 40: past 60 s | a CTE's relation is copied into every reference |
| `+` chains | 10 terms: 96 KB of SQL; 100: past 60 s | `strict()` writes `CASE WHEN x IS NULL THEN NULL ELSE x + 1 END`, `x` twice, at every level |
| 500 deep CASE, OR, `+`, parentheses | StackOverflowError on a default (1 MB) thread stack | the translator recurses once per operator (`SqlToPure.tx`, `binary`) |
| a scalar subquery without FROM | refused | not supported yet |

Scaled fine: IN and EXISTS nested 30 deep, 50 scalar subqueries, 40 set operations, 50 window functions, 500 columns,
a 100-branch CASE.
