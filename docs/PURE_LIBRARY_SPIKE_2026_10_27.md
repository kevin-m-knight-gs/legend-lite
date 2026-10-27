# The Pure library spike (2026-10-27)

The user, 2026-10-27: "Should we try to implement all the chart functions and see if everything holds? Then do the
without/except function?" -- and, of the order proposed: "Start it". Before building the primitive that drops a
function's working columns, one function of each family of the timeseries tile's functions was written as a Pure
function generic over any table (docs/SEMANTICS_REGISTER.md S104) and held to the numbers today's writer
(`datacube/src/timeseries-query.ts`) gives for the same expression, over the prices of `timeseries-queries.test.ts`, on
DuckDB, through lite's planner. The test is `datacube/test/pure-library-spike.test.ts` (`//datacube:pure_library_spike_test`).

## What holds

Every family the tile's ~100 functions fall into says itself as a Pure function, and every one matched today's writer
on every date (to 1e-9 relative):

| Family | Its functions | The representative | Held |
|---|---|---|---|
| One window, ramped | mean, sum, min, max, std, var, range, count | `datacube::ts::mean` (shipped) | yes |
| A row of a lag | returns, diff, lag, annualize, prices; row arithmetic: log, exp, sqrt, abs, power, floor, ceil, not, filter, compare, if, date parts | `datacube::ts::*Returns` (shipped) | yes |
| Multi-step windows | zscores, drawdown, max_drawdown, sharpe, winsorize, vol_swap, smooth_spikes, product, geometrically_aggregate, volatility | `spike::zscores` | yes |
| Several outputs | bollinger_bands, keltner, donchian, ohlc | `spike::bollinger` (lower, upper, width) | yes |
| A base over the whole line | index, rebase, change, percent_change, first, last | `spike::index` | yes |
| Two columns of an aligned table | correlation, beta, cov, alpha, r_squared, hedge_ratio, residual, tracking_error, information_ratio, up/down capture, excess_returns, union, append, prepend, interpolate, lead_lag; atr, stochastic, keltner (three); obv, vwap, total_return | `spike::correlation` | yes |
| Two tables of different columns, aligned | a tile's sources aligned on their dates (intersect; step, time, nan and zero fill by windows) | `spike::pairOf`, generic over two schemas | yes |
| A self-join over each window | percentiles, percentile, median, rolling percentile | `spike::percentiles` (join, group, join back) | yes |
| A calendar period | any window of `3m`; returns, lag over a tenor | `spike::meanOver` (self-join on `adjust` dates), `spike::lagBack` (as-of join) | yes |
| A group by the line | the statistics, drawdowns, calendar, histogram, seasonality and curve tables | `spike::maxDrawdownOf` (its working columns go with the group) | yes |
| A partition by a column of its own | consecutive, shading runs, a decomposition's cycle places | `spike::consecutive`, over a library call | yes |
| The deepest closed forms | exponential_moving_average, exponential_std/volatility, macd, rsi, smoothed_moving_average | `spike::ema`: fifteen steps, partitions by its own columns | yes |

Also found: a word told as a parameter (`'simple'`, `'log'`) is compared when the call is expanded, so one function of a
type keeps one branch and its SQL carries no other (`spike::returnsOf`); and the SQL a library function plans to is of
the writer's size (the exponential average: 5,682 characters, the writer's 6,733). A function body of several statements
(`let`) ends each statement after the first with `;`, the last too (legend's grammar).

## What is missing

1. **Dropping a function's working columns** (GAP 1, pinned). `select`/`project` keep columns by name, and a function
   generic over the table cannot name the caller's: so every multi-step function's answer carries its `ts_*` columns.
   The primitive: `without(~[a, b]): Relation<T-Z>` (the schema algebra `rename` already declares), typed at each call
   into the `select` of every other column -- an explicit column list in SQL, every dialect's, no new SQL record.
2. **Hygienic working names** (GAP 2, pinned). A function nested in its own argument collides on its own working
   names (`zscores(zscores(x))`: `ts_m` twice), and a caller's column of such a name would too. The expansion already
   renames a body's lambda binders fresh; it should rename the columns a body makes and drops the same way.
3. **The declared return type** (GAP 3, pinned). An expanded function's declared return (`Relation<T+O>`) is not held
   to its body, which leaks a column unnoticed. With 1, it can be: each call's expansion checked against it.
4. **Many series** (weighted_sum, basket). No Pure function iterates over a list of columns; a function generic over N
   series is not one function. The natural Pure shape is a long table -- a row per member and date, its weight beside
   it -- which a basket groups by the date; or a function of fixed arity. A design choice for the conversion.
5. **A column passed as a parameter, read in a row** (no primitive needed now). A body renames the column it reads to
   a name of its own and back; so the column read may be neither the line nor the date (refused by name if it is).
6. **Frames** (a design choice). `rows(1 - $w, 0)` is a count's frame; from the start (`w=0`) and a calendar period are
   other functions, or the frame a parameter (`_Window<T>` parameters work: `UserFunctionIntegrationTest$RelationGeneric`).

Not missing: self-joins, as-of joins, group-bys, functions over two tables, partitions by a function's own columns, a
library function over another's answer, and static words.

## What stays DataCube's

The tile's shaping, not gs-quant's functions: a source's periods at the tile's frequency (a week's last observation),
its split into lines, the view's anchor and window, a candle's period bucketing, and which queries a tile asks
(`tileAsks`). Each could become a library function later; none is a gs-quant function.

## The next step

Build 1-3 together (the drop, hygiene, the return check), each with its register row and tests; decide 4 and 6; then
convert every function, each held to today's numbers by `timeseries-queries.test.ts` as the shipped five were. Building
1 regenerates the native catalog (`Pure.java`), which needs the legend-engine and legend-pure source archives.
