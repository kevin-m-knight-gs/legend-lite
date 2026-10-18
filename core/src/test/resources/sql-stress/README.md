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
- `run.sh` runs them, one JVM per query (`com.legend.testcases.SqlStressOnPostgres`); its header says how.
- `baseline.tsv` is the run on main 6c3a6c9 (2026-10-13), before any fix; `after.tsv` the run on this branch with its
  fixes (2026-10-14, over main with #37), with three more queries: nested casts 10, 30 and 100 deep.

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

## After the fixes

Still no answer other than Postgres's. Eleven queries that timed out or overflowed now pass; none got worse.

| Shape | Before | After | The fix |
|---|---|---|---|
| joins of 20 / 50 tables | 36 s / past 60 s to plan | 0.45 s / 0.9 s | `JoinChecker` types a join's inputs again only when its condition reads a column by name |
| FROM subqueries nested 50 | past 60 s | 0.44 s | the same |
| monsters | up to 12.5 s | 0.4 to 0.6 s | the same |
| `+` chains of 10 / 100 terms | 96 KB of SQL / past 60 s | 253 / 973 characters, 0.36 s | a strict operation's NULL guard is merged into the next one's, not re-tested |
| OR of 500 / 2,000 terms | StackOverflowError | 18 KB / 75 KB of SQL, PASS | AND and OR chains read without recursion, combined as a balanced tree |
| casts nested 10 / 30 / 100 (new) | 10: 9 MB of SQL, 11 s; 20: past 60 s (measured before the fix) | 327 characters at every depth | an integer an enclosed cast holds to a range is not tested again; the default name read once a level |

Not fixed here:

| Shape | What happens | What it needs |
|---|---|---|
| CTE chains reusing earlier CTEs | 10: 27 KB of SQL, Postgres's lane past 60 s; 20: killed at the runner's 200 s limit; 40: 3.2 MB of SQL | a relation-valued `let` lowered to a `WITH` (the typer, G½ and the lowering) |
| 500-deep CASE, `+` chain, parentheses | StackOverflowError on a default (1 MB) stack | depth: the parser and the name resolver and typer recurse per level |
| a scalar subquery without FROM | refused | not supported yet |

