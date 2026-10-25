# Timeseries tile review (2026-10-25)

The independent audit of branch `claude/timeseries-viewer-datacube-tsm0nb` (the DataCube timeseries tile, its page
commands and Python API, the Markets and timeseries-gallery examples, the browser guard behind a proxy: `1e91768d..`
`ad2a6e40`, 55 files, +8.6k lines), run before its PR (the owner, 2026-10-25: "did we do an independent audit first?
Especially to make sure we held to our tenets everything through platform and everything with API"). Four reviewers
read the diff independently, each against the repository's own rules rather than the author's account of them, and
each probed what it found -- on the real planner and DuckDB-WASM, the Python engine's runfiles, and Chromium:

| Reviewer | Question | Judged by |
|---|---|---|
| P | Is every value the database's, through the platform's queries -- the snapped and Python paths too? | AGENTS.md's execution tenet and invariant 4; TENET_CHARTER C1-C5; WORLD_MAP (compare, never compute); TENETS (lazy work); the owner's words for this feature |
| A | Is the tile one API: the screen and Python making the same page commands? | DATACUBE_ONE_API_PLAN_2026_10_21 (rules, L1-L12), its audit, the headless Python design; the ledgers |
| Q | Are the numbers right? | each function's documented formula (`timeseries-expr.ts` WORDS `about`, `differs`), gs-quant; hand values on adversarial inputs |
| G | Are the repository's written rules and guards kept? | AGENTS.md whole (guard files, invariant 4, common mistakes); every guard the diff moved |

A finding is listed as confirmed when a probe showed it (a reviewer's, and where marked re-run by the author) or the
code shows it directly; the author re-ran Q1, Q2 and Q5 on the real planner and DuckDB, and read A1, A2 and P2 in the
code. Probes are in the session scratchpad (`audit/{platform,api,queries,guards}`).

## 1. Confirmed: blocking

| # | Finding | Evidence | Planned fix |
|---|---|---|---|
| P1 / A4 | **Python's `Timeseries.frame()` reads a snapped grid live**, while every other Python read reads its copy: one page answers two sets of rows. Page filters are not applied; a grid over a class, saved query or warehouse table is refused (a raw `CubeRefusal`) though the tab plots it | two independent probes: after `px.snap()` and `px.update(...)`, `px.to_pandas()` the copy, `tile.frame()` the live rows; `headless/timeseries.ts:148-163` reads `cube.cube.source`, never `gridBaseOn` | a single-grid series opened over `gridBaseOn`'s base (copy, lens); a combination's grids each over their copy (see decision 1) |
| P2 | **A grid's rows changed under the same query: the tile keeps its old answer** -- in effect the results cache the owner ruled out (D4). Python serving a frame again, a remote file re-read | probe (legend 105.00 after the rows doubled; 210.00 only with `refresh(true)`); `ui/timeseries-view.ts:236-237` returns while its input key is unchanged; charts always ask again (`ui/chart-panel.ts:162`) | asked again whenever the grid's view lands, as a chart is |
| A1 | **Undo of a step that brings back a grid and a timeseries reading it builds a wrong page** (refused with an editor's tag, as every live page has; silently the wrong grid otherwise) | two probes; `page-between.ts:168` writes the simulation's ids into `addTimeseries`, renamed again by `applyOne` (`setTimeseries` at :173 is right) | `spec: v.spec`; an L2 gesture: a sheet of a grid and its timeseries deleted, undone, with a tag |
| A2 | **The tile's editing rules are screen code**: `firstTimeseries`, an alias renamed with every expression following, Read another grid, the remove-source guard, the series editor's composition, panes, views, annotations -- all in `ui/timeseries-view.ts`, which headless cannot import. Python edits the spec dict by hand (`plot`, `unplot`, `options`); the charts' equivalent is `chart-spec.ts editChart`, shared | read; probe: Python removes the first source while an unqualified series reads it -- accepted, the series silently re-pointed at another grid; `one-api-ledger.test.ts:504`'s PASS_THROUGH entry ("nothing to decide") is untrue | `timeseries-edit.ts` (no screen's): `editTimeseries(spec, change, columns)` and the first-tile rule, called by the viewer and, headless, by Python's methods; the ledger entries corrected |
| Q1 | **Dates are intersected before any function runs**: each operand of `a - b` (or a two-series function) is computed over the dates both have, not its own -- returns, volatility, means over another instrument's calendar | re-run: AAA daily, CCC every other day, `returns(close[AAA]) - returns(close[CCC])` -0.11 and 0 where each on its own dates then intersected (gs-quant) gives -0.20 and -0.191; `timeseries-query.ts:672-679` | each operand's subtree written over its own rows, joined on the period at the operator or the two-series function |
| Q2 | **Compare mode and the level-only statistics take every `same`-unit result for a level**: `change`, `diff`, `std`, `var`, `range`, spreads | re-run: under compare rebase (`firstPlot`'s default for a split price grid) `change(close)` is null throughout, `diff(close)` drawn 100, -300, 600; `diff`'s statistics a volatility of 2011%, max drawdown -400% | "is a level" tracked apart from the unit; compare and the level statistics for levels alone |

## 2. Confirmed: should fix

| # | Finding | Evidence | Planned fix |
|---|---|---|---|
| Q3 | No frequency ("As the rows are", the default): every figure annualized by 252 -- monthly levels' volatility 29.72 where 6.49 | probe | see decision 2 |
| Q4 | `100 * returns($A)` (the design note's own example) drawn a hundred times too large; `volatility(x)/100` written 0.20% | probe; `timeseries-expr.ts:380` | a ratio times 100 a percent, a percent over 100 a ratio, either times another number a number |
| Q5 | A split series whose base matches on the split or key column (`close / close[SPY]`, `beta(close, close[SPY])`, split by symbol) keeps only SPY's line, the others gone without a word | re-run | that base its own relation joined on the period (falls out of Q1's fix) |
| Q6 | A week or month is dated at its start but valued at its last row: a range or anchor drops the period holding its start (1Y at month grain an 11-month change; YTD loses January) | probe | the anchor and the statistics' window compared with each period's last observation |
| Q7 | Several rows per line per day: which is "the latest" is arbitrary, and said only for the first source with no split or match | probe | every base's rows counted, the drawing saying so |
| P3 | A combination never reads a snapped grid's copy (the comments say it does): a snapped warehouse grid refused, a snapped file read live beside the same tile's single-grid series read from the copy | read | see decision 1 |
| P4 | Failures shown as absence: a refused bounds query makes 1Y show everything, refused statistics blank every cell, with no word (invariant 4; C2.4) | probes | each refusal in the banner or on its line, as a series' is |
| P5 | `frame()` silently cut at 100,000 points (the drawing's limit), its sentinel row returned | probe | see decision 3 |
| A3 | Create ▸ Timeseries and Python's first tile are two rules (a date and no number: the screen refuses, Python makes a tile of no series) | probe | one function (A2) |
| A5 / G2 | Python is not refused what the viewer cannot draw (a text date, a text value, a missing column or series); `timeseriesMade` swaps a wrong-typed `date`, `key`, `alias`, `id` for the default; `periodOf` guesses `StrictDate` for an unknown type | probes | both headless entries run `timeseriesProblems`; wrong types refused by name; `periodOf` refuses |
| A6 | The screen/Python parity gaps are stated nowhere; the series editor rebuilds `split` from the first source's key, so editing a Python-made series rewrites it | read | closed by A2 (every edit one function, both sides); what stays one side's is a parity row |
| A7 | The L12 `both:` proofs never run the code they vouch for: `tabSources`, `openTogether` and Python's `together` client have no test | read | an end-to-end test of a combined series on a page of each host |
| G3 | A log pane draws zero and negative values by leaving them out (`[1, 10]`), with no word | probe (ECharts SVG in node) | refused in `timeseriesProblems` for a non-level on a log axis |
| G4 | A saved timeseries reading a cube the page does not hold is read without complaint (a chart on it is refused); after a reopen "Undo brings it back" is false | probe | see decision 4 |
| G5 | No test draws a timeseries with real ECharts (the renderer, `canvasChart`, the candlestick-registration order whose bug the candles commit fixed); no harness opens the two example pages, though the design note says they are drawn in Chromium | read; node render probe | a node render test (as `chart-echarts`); both examples in verify-charts' example loop |
| G6 | `proxyArgs`: a proxy URL with credentials or a trailing slash is refused by Chromium's flag (every host then direct); a NO_PROXY entry matches exactly, not as a suffix; `ALL_PROXY` loses to `HTTPS_PROXY` (Chromium's own reading: the reverse). The guard itself holds (probed); CI sets no proxy | probes | userinfo and slash stripped, `x` as `x;*.x`, Chromium's precedence; a unit test |

## 3. Notes (registered; fixed where small)

- P6: `frame()` pivots and sorts in pandas (no value computed; the database could order oldest first). P7: the viewer
  cuts a date-time range start to its day; a Decimal is read as a double for the legend, statistics and CSV. P8: split
  lines ordered by a numeric-aware compare on the client, the database's 20-line cut by its own string order. P9: a
  combined series rewrites its joint model on every query (series, statistics, each zoom).
- Q8: `prices` skips a return of -100% (shows 1 after a total loss; gs-quant 0); its `differs` wording is wrong. Q9: a
  candle series' statistics Low and High are its closes'; `candled` takes first/max/min of direct columns only. Q10:
  undeclared divergences from gs-quant: default windows, the ramp one point earlier, correlation and beta's returns after
  the intersect (Q1's fix changes the last). Q11: `pct` of a negative start, CAGR over two days, `drawdown(x, w)` never
  ramped, `std`/`var`/`corr` at w=0 hiding a point beside a null. Q12: the point limit cuts a split's lines unevenly.
- A8: the L2 gesture catalogue holds two timeseries gestures; none for frequency, compare, log, stats, hide, the
  series editor, settings, annotations, remove, move, undo. A9: `_HEADLESS_LEFT_OUT`'s entry and the tab's L12 proof
  line undated. A10: Python's own small rules (`unplot`'s existence check, `frame`'s first-series default, the tile
  placed near the first grid, `_timeseries_sources` misreading an alias named `grid`).
- G7: silent catches and guesses (`safeInline`, `?? []` on the code's own columns, `?? 'close'`, the editor's
  `'close'`, `returns`' `default:` arm doubling as simple, `readsAnchor`'s catch, `relations[i]!` unchecked). G9: the
  hidden-sheet test never checks the tile asks once shown; `tickText` writes "-0%"; a dangling doc comment in
  `chart-render.ts`; a tile on a sheet not shown exports no picture.

## 4. Checked and holding

Every figure is written in Pure through the pure-protocol builders and planned by legend-lite's planner -- no SQL, no
direct engine call, no gap filling or join on the client (`timeseries-query.ts`; a grep of the diff). A series of
several grids is one query, each grid grouped by its period and inner-joined in Pure, planned over one model as the
existing Join builds one (`join-open.ts`). On the tab a single-grid series runs through its grid's own runner, the snap
rewrite included. Range starts, anchors, statistics, the legend's change are the database's; the client only compares
the dates it was given. Every gesture reaches the one command path (`setTimeseries`, `addTimeseries`, the generic
tile commands), both command kinds are whole (union, readers, rebase, `pageBetween`, Python's generated builders), and
Python changes the page only through them; expressions are parsed by DataCube's parser alone. The budgets moved are
dated, named and needed; the generated files match their tools; every path that changes the sheet shown draws its
tiles; the guard holds behind a proxy and CI is unchanged.

## 5. Decisions for the owner

1. **A combination over a snapped grid (P1, P3).** Build it now -- each snapped grid's copy table declared in the one
   model a combination is planned over, a grid in another store than the copies refused by name until the cross-store
   join -- or refuse a snapped grid in a combination by name until phase 2?
2. **No frequency (Q3).** Annualize by the periods a year the database infers from the dates' spacing (gs-quant's
   rule), refuse the annualized figures with no frequency, or make Daily the default frequency of a date column?
3. **`frame()` past the drawing's limit (P5).** No limit for Python (the window it asks for, all of it), or refused
   past it by name?
4. **A removed grid that a timeseries reads (G4).** Keep the removed grid's cube in the page while a tile reads it (as
   a frozen chart's is kept), or refuse such a page when it is read?
