# DataCube's timeseries viewer: many series from many grids, every figure the database's (design, 2026-10-25)

**Status: agreed with the user, 2026-10-25** (§2.1); being built on the branch. `datacube/` is the DataCube + Python line's (`docs/IN_FLIGHT.md`); this work is on the session branch
`claude/timeseries-viewer-datacube-tsm0nb`. It builds on the charts design (`docs/DATACUBE_CHARTS_DESIGN_2026_10_09.md`:
"the database computes, the chart draws"; the presets as calculated columns, §3.2) and the pages design
(`docs/DATACUBE_PAGES_DESIGN_2026_10_09.md`: every tile equal, bands, sheets).

## 0. The ask

The user, 2026-10-25: "design and implement the world's most expert historical timeseries 'viewer' as part of legend
lite datacube ... a new visualization but more powerful 'tile type' in Datacube that can plot anything timeseries
including comparing multiple time series at the same time with every option that people expect from financial time
series plot/viewer ... backed by datacube grid (or many grids potentially for comparison?) ... one inspiration might be
to look at the Goldman Sachs gs-quant library ... the client side API to 'plottool pro'".

Then, on the design: "I def want this to be built at the beginning to have multiple time series so it does not feel
like a bolt on later -- it can be phased if you think that's right but needs to be designed correctly upfront"; on how
series are queried: "definitely one query, can be snapped into duckdb wasm as a grid snap too if from different sources
and then later maybe as cross store join but that can come later".

## 1. What was read

**gs-quant** (github.com/goldmansachs/gs-quant, cloned 2026-10-25). It has no user interface; what it has is the
vocabulary PlotTool Pro speaks. Every function of `gs_quant.timeseries` marked `@plot_function` (`helper.py`:
"Indicates that fn should be exported to plottool as a pure function") is a word of PlotTool's expression language;
`@plot_measure` marks an asset's measures (`SPX.spot()`), `PTPDataset.plot()` writes PlotTool's own expression
(`Dataset("ID").field()`), and the analytics `DataGrid`'s processors each have a `get_plot_expression` ("used to go from
grid to plottool") -- a grid opened as a plot, which is what this tile is. What a PlotTool user expects, by module:

- `econometrics`: `returns` (simple, logarithmic, absolute over `obs`), `prices` (returns compounded back), `index`
  (divided by the first value), `change`, `annualize`, `volatility` (the annualized sample standard deviation of
  returns, in percent: 20% is 20.0), `correlation` and `beta` (of returns, rolling), `max_drawdown`, `sharpe_ratio`;
- `statistics`: rolling `min_`, `max_`, `range_`, `mean`, `median`, `sum_`, `std`, `var`, `cov`, `zscores`,
  `winsorize`, `percentiles`;
- `technicals`: `moving_average`, `bollinger_bands`, `relative_strength_index`, `exponential_moving_average`, `macd`,
  `exponential_volatility`, `seasonally_adjusted`, `trend`;
- `analysis`: `diff`, `lag`, `first`, `last`, `count`, `smooth_spikes`; `algebra`: arithmetic, `log`, `exp`, `sqrt`,
  `abs_`, `power`, `floor`, `ceil`, `filter_`, `if_`, `weighted_sum`; `datetime`: `align`, `interpolate`, `bucketize`
  (resampling: a frequency and an aggregate), `day`/`month`/`year`.

Two of its conventions matter everywhere. A WINDOW is `Window(w, r)`: `w` observations (or a calendar period, `'1m'`),
and a ramp `r` (default `w`) of first values left out, so a rolling figure is drawn once its window is full. And series
are ALIGNED before they are combined: `correlation` and `weighted_sum` take the dates every series has (`intersect`).

The rest of what a professional expects is common to Bloomberg's GP and COMP, TradingView and Highcharts Stock: range
buttons (1M ... 10Y, YTD, Max), a navigator to zoom and pan, a crosshair across stacked panes, a compare mode (every line
from 100, or its % change, at the left edge), each line's last value tagged on its axis, two value axes, a log scale,
annotations (levels, events, shaded periods), statistics over what is shown, and export.

## 2. Decisions

### 2.1 Decided (the user, 2026-10-25)

- **D1 Many series, many grids, from the start.** A tile reads any number of grids (its SOURCES, §3); the first
  landing compares series from several grids. Phasing is of what is built, never of the model: nothing in §3 is added
  later.
- **D2 One query per series.** Each series is one query, run where its grid runs. A series that COMBINES grids (a ratio,
  a spread, a correlation, a beta of two grids' series) is still one query: each grid's rows grouped to the tile's
  frequency, joined on the period, the windows over the joined rows. Never a tile-wide query joining every grid
  (series on different calendars would be forced onto one), never two queries combined in the browser (the browser
  would compute -- and a second copy of every window would live in TypeScript, out of the planner's reach).
- **D3 Grids on other stores are snapped.** A combination reads both grids in one database, the tab's DuckDB. A grid
  over another store (a warehouse, a remote engine) is snapped into it first -- the grid's own Snap, its copy then a
  table of the tab (`page-document.ts GridCopy`) -- and the combination reads the copy. A join across stores is later.
- **D4 No results cache.** The viewer keeps the rows its last answer gave; a change of the drawing alone (a colour, a
  pane, an annotation, a line hidden) redraws from them, and every other change asks the database again, as every
  DataCube chart does when a view lands on its grid. A zoom asks again only for the series that read the first date
  shown (an index, a change from the start, compare mode) and for the statistics. (A cache of answers by their query's
  text, kept a few seconds, was proposed and withdrawn: freshness by a clock is a guess, and the drawing's own changes
  never needed the database.)

- **D5 Pure for everything, snapped or not** (the user: "make sure even when we snap still uses platform/pure queries
  for everything not some new mechanism"). Every figure a tile draws is a Pure query planned by the platform's planner:
  a series of one grid through that grid's runner (live or snapped, the grid's own plane decides what its source reads,
  `cube.ts` `sourceFor`); a combination over one model of its sources' tables, a snapped grid's copy declared in that
  model as any table of the tab is (`join-open.ts`, infer.ts `tablesModel`). No SQL is written here, and nothing is
  computed in the browser.
- **D6 A tile kind of its own, with its API** (the user: "Yes new chart types with API") -- a fourth page view beside
  `grid`, `chart` and `text`: `timeseries`. A chart is, by definition, a view of ONE grid's query regrouped
  (`chart-spec.ts`'s header); a tile over several grids is not one, and making it a chart kind would be the bolt-on D1
  rules out. Its two page commands (§7) are made by Python in the same landing, as the one-API ledger requires
  (`test/one-api-ledger.test.ts` L8).

### 2.2 Proposed

- **P2 What is built first** (§8): phase 1 is the whole model, the viewer, sources from any number of grids, combinations
  across grids in the tab's DuckDB (the probe of §5.4 passed), the page commands and Python.

## 3. A tile: its spec

Plain JSON, ours, versioned with the page (a reader that meets a word it does not know refuses it by name, as a chart's
does). An example -- an equity index against a bond yield, their rolling correlation under them, a recession shaded:

```json
{
  "sources": [
    { "alias": "px", "cube": "cube-1", "date": "day", "key": "symbol" },
    { "alias": "rates", "cube": "cube-2", "date": "obs_date", "key": "tenor" }
  ],
  "grain": "day",
  "series": [
    { "id": "A", "expr": "<px:close[SPX]>", "style": { "color": 0 } },
    { "id": "B", "expr": "<rates:yield[10Y]>", "axis": "right" },
    { "id": "C", "expr": "<correlation($A, $B, 63)>", "pane": 1, "name": "SPX vs 10Y, 3m corr" }
  ],
  "range": { "last": "5y" },
  "panes": [{ "height": 3 }, { "height": 1 }],
  "annotations": [{ "kind": "period", "from": "2020-02-19", "to": "2020-04-07", "label": "COVID" }],
  "view": { "compare": "rebase", "stats": true }
}
```

(`<...>` stands for the expression's tree, which the spec keeps; §4 is its text.)

- **sources**: the grids the tile reads, each by its cube on the page, with its own date column and instrument column
  (two grids rarely name them alike) and a short alias the expressions name it by. A tile of one source leaves the
  alias off its expressions.
- **series**: what is drawn, in the legend's order, each an expression (§4); its id a capital letter (`$A`); a name;
  a split (one line per value of a column, `close` by `symbol`: every instrument); a style (line, area, bars, steps,
  dots; a palette slot, a width, a dash); a pane (0 the main one, up to 3 under it); an axis (left, right; else by
  unit: a second unit takes the right); hidden.
- **grain**: the tile's one frequency (day, week, month, quarter, year): each series' values taken to it, so series of
  different grids share one time axis. Absent, the rows' own dates.
- **range**: the window shown first -- a period back from the latest date (`1m` ... `10y`, `ytd`, `max`) or two dates.
- **panes**, **annotations** (a level on a pane, an event's date, a shaded period, each labelled), **view** (compare:
  `rebase` or `percent`; legend, last values, navigator, statistics, crosshair: each on or off).

## 4. The expression language

PlotTool's style over gs-quant's names (`timeseries-spec.ts parseExpr`, printed back by `exprText` -- the spec keeps the
tree, so what was typed and what is saved never drift):

| Text | Means |
|---|---|
| `close` | the grid's column along the date, each period's latest value |
| `close[AAPL]` | its rows whose instrument column is AAPL; `close[venue=NYSE, AAPL]` with another match |
| `volume[AAPL].sum` | each period's rows summed (last, first, average, sum, min, max) |
| `rates:yield[10Y]` | a column of the source aliased `rates` |
| `$A` | series A's expression |
| `returns(close[AAPL], 1, log)`, `volatility($A, 63)`, `zscores($A, w=252)` | a function: its series, then its parameters by place or by name |
| `close[AAPL] / close[MSFT]`, `($A + $B) / 2`, `100 * returns($A)` | arithmetic |

**The functions** (`TS_FUNCTIONS`, each with gs-quant's name, its own words, its unit, and where it differs):
returns, prices, diff, lag, annualize; index, rebase, change, percent_change (from the first date shown); mean
(moving_average, sma), sum, min, max, range, std, var, zscores, winsorize; volatility (vol), drawdown, max_drawdown,
sharpe_ratio; correlation (corr), beta, cov; bollinger_bands, rsi; log, exp, sqrt, abs, power, floor, ceil. A window
`w` is observations, 0 from the start (gs-quant's `Window(None, 0)`: running); a rolling figure is drawn once its
window is full (the ramp). Annualizing is by the tile's frequency: 252 days, 52 weeks, 12 months, 4 quarters
(gs-quant reads it off the spacing of the dates).

**Where it differs from gs-quant, said in the catalog**: `volatility`'s window defaults to 22 (gs-quant: the whole
series); `sharpe_ratio` takes a risk-free rate of zero and the arithmetic mean of simple returns (gs-quant: excess
returns over a currency's rate, compounded); `rsi` averages its gains and losses simply (Cutler's; gs-quant smooths
them, Wilder's); a window is a number of observations, not a calendar period.

**Not built, refused in these words when typed** (`NOT_BUILT`): exponential averages, MACD, exponential volatility
and deviation, smoothed averages (each a recursion, every value from the one before, which a database's windows have
not); a rolling median and percentile rank (windows the planner does not write yet); a seasonal decomposition and its
trend (a model fitted to the series); excess returns (a risk-free series: divide by one of your own).

## 5. The queries

### 5.1 A series of one source (`timeseries-query.ts seriesQuery`)

One query of its grid, run by the grid's own runner:

1. the grid's source and its row-level calculated columns, its filter (as the tile reads it: following the grid, or
   frozen), and what the series matches (`close[AAPL]`);
2. grouped by the period -- and the split -- each column the series reads taken by its period's aggregate: the latest
   row's by default (`maxBy`, ordered by the day's number: `dateDiff` from 1970, since `maxBy` orders by a number);
3. only the dates every column it reads has (intersect); a split's first 20 values;
4. each function, innermost first: a window over the periods partitioned by the split (a frame of the last `w`, or
   running), or an expression over the row -- a return over `lag`, a deviation by `stdDevSample`, a correlation by
   `corr` over `rowMapper`, a band's edges and its width;
5. an index or a change from the start reads the value at the FIRST DATE SHOWN: the viewer passes where its zoom starts,
   and the database finds the first period at or after it with a value (a `min` window), and the value there;
6. the period, the split and the value -- a band's lower, upper and width -- latest first, at most 100,000 points.

Every series reads all its history: the window shown is the viewer's, so a rolling figure at the left edge has its
history.

### 5.2 A series combining sources

The same steps, with step 2 per source -- each source's rows (its own date, instrument and filter) grouped to the
tile's frequency -- and the grouped sources joined on the period (inner: the dates both have, gs-quant's intersect)
before step 3. Planned over ONE model of every source's tables, as DataCube's Join opens a join of two grids
(`join-open.ts openInputs`, generalized from two inputs to the series' sources), and run on the tab's DuckDB. A source's
tables are where its rows are now: a grid over the tab's files reads its files; a snapped grid its copy (D3); a source
anywhere else is refused for the combination, in words that offer its grid's Snap -- it is still plotted on its own.

### 5.3 The tile's bounds and statistics

- **Bounds** (`boundsQuery`): each source's latest and earliest date, and where each range button starts --
  `adjust(latest, -1, YEARS)`, the year's first day for YTD: the database's date arithmetic, the viewer only places its
  zoom there.
- **Statistics** (`statsQuery`), per line, over the dates shown: count, first and last date and value, the change and
  % change, the least and the most and when, the mean, the standard deviation; for a level its annualized volatility
  (of log returns), deepest drawdown, compound annual growth and Sharpe ratio -- each the database's, over what is shown.

### 5.4 Proven so far

`datacube/test/timeseries-queries.test.ts` plans every function through legend-lite's planner and runs it on DuckDB,
held to gs-quant's definitions written out in the test as plain arithmetic (29 cases: returns of each type, rolling
statistics with their ramp, z-scores, volatility daily and monthly, drawdowns, correlation, beta, covariance, Bollinger
bands, RSI, prices from returns, a ratio of two instruments on the dates both have, an index from the first date shown
and from a date with no value, compare mode, bounds, statistics). A probe of §5.2 (2026-10-25, not kept): two tables of
the tab's DuckDB with different date and instrument columns, opened over one model by `openInputs`, each grouped by its
own date, joined on the period, a rolling correlation over the joined rows -- planned and run, the joined dates exactly
the shared ones.

## 6. The viewer

```
┌ 1M 3M 6M YTD 1Y 2Y 3Y 5Y 10Y Max │ from..to │ Daily ▾ │ Compare ▾ │ Log │ Stats │ + Series │ ⋯ ┐
│ ● SPX 4,783.45 +2.1%   ● US 10Y 4.02%   ● SPX vs 10Y, 3m corr -0.42                       │  the legend
│                                                                                           │
│   main pane: lines, bands, areas; the latest value tagged at each line's end              │
│ ─────────────────────────────────────────────────────────────────────────────────────── │
│   pane 1: an indicator, bars of volume                                                    │
│ [▁▂▃▅▇ navigator: every date, its window dragged to zoom and pan ▇▅▃▂▁]                   │
├ statistics: a row per line over the dates shown ─────────────────────────────────────────┤
```

- **Range buttons** set the window shown (saved with the page); a zoom or a pan of the navigator, the mouse wheel or a
  drag is the person's own look (not saved), and re-asks only what reads the first date shown (D4).
- **Compare**: every level from 100, or its % change, at the first date shown -- moving with the zoom; a return, a
  volatility or a z-score is drawn as it is.
- **The crosshair** crosses every pane at one date, a hover listing every line's value there, written as the grid
  writes its column (a level) or by the figure's unit (a return as a percentage, a volatility in percent).
- **The legend** names each line with its latest value and its change over the window (the statistics query's);
  a click hides or shows it, a hover brings it forward, a double-click edits it.
- **The series editor**: the expression, read as typed with its error said where it is; pickers for the source, the
  column, the instrument (the column's values, the database's), the split; the functions by family, applied around
  the expression; its style, pane, axis and name.
- **Settings**: each source's date and instrument column; the toggles; annotations (a double-click on the chart puts an
  event at that date); export as a picture and as CSV (the rows the database answered, long form).
- Drawn by `timeseries-option.ts` (pure, tested in node) and `timeseries-render.ts` (ECharts: its own chunk, fetched
  with the first timeseries tile; the panes share one time axis, one crosshair, one zoom).

## 7. On the page

- **The page document**: a view `{ id, kind: 'timeseries', title?, spec }`, the spec's sources naming the cubes it reads
  -- so the page knows every cube a tile reads, as it knows a chart's one. No version step: a reader that does not know
  the kind refuses it by name, as the text tile came in (2026-10-24).
- **Commands** (`page-commands.ts`): `addTimeseries` (on a sheet, a band of its own) and `setTimeseries` (the spec
  whole, as `setChart`); `renameTile`, `moveTile`, `removeTile` and the layout's as for any tile. The rebase
  (`page-rebase.ts`) renames the cubes a tile's sources name, as it renames a chart's. Removing a grid a tile reads
  keeps that grid's cube for the tile, its source DETACHED (said on the tile), as a chart whose grid goes keeps a copy of
  its query; the cube is let go when nothing reads it.
- **Following**: each source follows its grid's filter and the selections clicked on the page, or is frozen with the
  filter it had (per source).
- **Python** (`python/legend_lite/page.py`): `page.timeseries(sources={'px': prices, 'rates': rates}, series=[...])`, a
  `Timeseries` tile whose `series`, `range`, `view` and `annotations` it sets; every word judged by DataCube's reader,
  asked headless -- Python keeps no list of its own (the charts' rule, PR #47).
- **Export**: the page's HTML and PDF take the tile as drawn (its picture), as a chart's.

## 8. Phases

1. **The tile** -- everything in §3 to §7: sources from any number of grids; series of one source and combinations
   across sources in the tab's DuckDB; the viewer whole; the page commands, the rebase, a removed grid; Python; an
   example page over a markets sample (daily prices and volumes of ten instruments, a yield curve's tenors, an FX rate:
   three grids). Each step a commit, held by its tests (§9).
2. **Sources elsewhere**: a combination offering the grid's Snap when a source is on another store (D3), and reading
   the copy; per-source frozen filters.
3. **Later**: a join across stores (D3); OHLC candles; events read from a grid's column (a calendar of releases) as
   annotations; regression of one series on another (a scatter with its fit: the database's slope and intercept from
   `covarPopulation` and `variancePopulation`); page filters reaching a tile's sources by class.

## 9. Tests

The queries planned and run (§5.4, growing with each function and with §5.2's combinations); the spec's reader, its
refusals and the expression language's round trip (parse, print, parse); the drawing (`timeseries-option.ts`, in node);
the viewer's DOM (jsdom, the renderer stood in for); the page commands, the rebase and a removed grid; Python's
`page.timeseries`; the example page drawn in Chromium.

## 10. What is built so far (on the branch)

- `timeseries-spec.ts`: the spec of §3 with the date and instrument columns at the tile's top (one source); the catalog,
  the reader, the expression language, a first tile of a grid. **Changes**: the date and the instrument move into
  `sources`, and the parser reads `alias:`.
- `timeseries-query.ts`: §5.1 and §5.3, tested (§5.4). **Changes**: §5.2's per-source grouping and the join.
- `timeseries-option.ts`, `timeseries-render.ts`: §6's drawing, not yet tested or wired.
