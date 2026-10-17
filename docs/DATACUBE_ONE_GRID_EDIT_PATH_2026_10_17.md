# One way to edit a grid: the screen's, for the screen and Python alike (2026-10-17)

**Status:** agreed with the user 2026-10-17 ("in #47, before merge"; "yes, as proposed"). Built in PR
neema2/legend-lite#47 (branch `claude/epic-rubin-ay7upl`): step 1 in 57f365d, steps 2 and 3 together in baf0bfd (Python's
calls need `gridEdit`), the Query app's grid in cbcd4d6, and three fixes the command-API homework found in da1f546
(§3.3, §4). Reviewed by the DataCube + Python line before push. What it leaves for later is in
`docs/DATACUBE_COMMAND_API_HOMEWORK_2026_10_17.md`.

## 1. Why

#47 says every Python copy of DataCube's logic goes through DataCube's own code. Round 2 (2026-10-16) did not do that
for a grid's grouping, pivot, measures and sorts: it moved Python's rules into a new TypeScript path, headless
`gridQuery`, that only Python calls. The screen edits a grid another way, and the two already disagree:

- **Two models of a grid's aggregates.** The screen sets each column's Aggregation in Column Properties
  (`configuration.columns[c].aggregateFn`, its weight in `aggregationParameters`), folded into the query by
  `config.ts applyToSnapshot`. Python writes a separate list, `snapshot.measures`, that no panel shows or edits. The
  screen's own Open in Grid (`page/cube-page.ts #openEditor`) writes that list too.
- **Grouping.** The screen groups and pivots by dimensions only (`ui/panel-kit.ts groupableColumns`, the header
  menu's `groupable`); Python groups by any column.
- **Counts.** Aggregation offers a column its own aggregates (`ui/panel-column.ts aggregatesFor`: `offers.ts
  takesAggregate`); Python also takes a count of any column (`offers.ts measureOfferProblem`).

The user, 2026-10-17: one implementation; check how our screen does it, and do it the screen's way if that is right.

## 2. How the screen edits a grid (read 2026-10-17)

- **Columns.** Each column's kind (dimension or measure) and Aggregation live in the configuration. A grouped level
  aggregates every shown column that is not a key by its Aggregation, else by its kind's default
  (`query.ts defaultMeasure`, `defaultAggregate`), under the column's own name. A second aggregate of one column is a
  calculated column with an Aggregation of its own.
- **Grouping and pivots.** The Vertical and Horizontal Pivots panels (`ui/editor.ts`) and the header menu
  (`ui/menu.ts applyMenuAction`) set `rows` and `pivotOn` from `groupableColumns`.
- **Sorts.** The Sorts panel offers the cube's columns (`ui/panel-kit.ts allColumns`); the header menu sorts by the
  header clicked, a pivot's spread header too.
- **Apply.** The editor's draft is compiled whole before it lands, and refused if it does not compile
  (`app.ts #applyDraft`: `CubeController.compile` over `applyToSnapshot(snapshot, configuration)`).

That model is right: one setting per column, shown and edited in one place, saved in the configuration the screen
already writes. It is the one model from now on.

## 3. The design

### 3.1 A grid's aggregates are its Column Properties

- A grid never carries `measures`. Its aggregates are its columns' Aggregation; a second aggregate of a column is a
  calculated column (row stage, kind measure) reading it, with its own Aggregation.
- **No tile stores a list of aggregates** (the user, 2026-10-17: "isn't that just keeping the second impl?"). The
  field comes off the grid's type, `CubeSnapshot`, so the compiler refuses a grid that carries one. The query builder
  takes the aggregates a query asks for as its own input, worked out for that query from the tile's model: a grid's
  from its Column Properties (`query.ts groupedMeasures`, `defaultMeasure`, as today), a chart's from its value pairs
  (`chart-spec.ts chartSnapshot`; a chart's model is its pairs, edited by its form and `chartFrom`, one path since
  #42), and `app.ts`'s one-off JSON row count from that count. Each tile has one model and one way to edit it; the
  builder is shared.

### 3.2 One module of grid edits: `datacube/src/grid-edits.ts` (no DOM)

Each takes the grid's snapshot and configuration and answers the new pair, or why not, in the screen's words:

- **Offers**, moved here unchanged: `groupableColumns` (from `ui/panel-kit.ts`), `allColumns` (the same), and
  `aggregatesFor` (from `ui/panel-column.ts`).
- `setGrouping(rows)`, `setPivot(pivotOn)`: each name a groupable column, once.
- `setSorts(sorts)`: each a column the cube has (`allColumns`) or, pivoted, a spread header (its last part a column
  the pivot spreads), with a direction.
- `setColumn(name, {kind?, aggregate?, weight?})`: the kind one of `COLUMN_KINDS`; the aggregate one Aggregation
  offers for the column's type; a weight only on `wavg`, and a column.

The screen calls it: the two Pivots panels and the Sorts panel (`ui/editor.ts`), the header menu's sort and pivot
actions (`ui/menu.ts`), the drag zones and a header's click-to-sort (`app.ts`, whose own copy of the dimension rule,
`#kindOf`, now asks `columnKind`), Column Properties' kind and Aggregation (`ui/panel-column.ts`). An edit judges what
it adds: a group or a sort the grid has is kept or taken away as it is, as the screen's menus only offer what may be
added. The screen's offers already keep a person inside these rules; the rules now have one home.

Two DataCube bugs found writing it down, fixed: Column Properties set a calculated column's kind and a group-level
column's Aggregation, and no query read either -- the grid groups by a calculated column's kind as its editor declares
it, and aggregates source and row-level columns only. The panel now disables both, saying why ("Set in its editor",
"Computed after grouping"), and the module refuses them.

### 3.3 Python asks it

Headless `gridEdit` replaces `gridQuery`. It opens the grid on the configuration the screen opens on (`fromSnapshot`,
as `app.ts`'s constructor), turns Python's call into the module's edits -- in the order configuration, columns, rows,
pivot, measures, sorts -- then folds and compiles the grid as `#applyDraft` does, and answers the whole cube document
as DataCube writes it (`{cube, notes}`: what opening it over its frame changed, which Python warns), which Python's page
keeps. The open groups stay, as the screen's edits keep them. A part it does not know is refused. Unlike `#applyDraft`,
it does not move the tree with the configuration (`withTotals`, `withExpandTo`), so `showRootAggregation` set from
Python does not change the view -- as before this round; recorded in the homework doc.

| Python | The module |
|---|---|
| `grid.group(*rows)`, `grid.pivot(*columns)` | `setGrouping`, `setPivot` (unpivoted, a sort of a pivot's header goes with its headers) |
| `grid.sort(column, direction)` | `addSort`, the header menu's Add Ascending and Add Descending: the column's sort replaced, added last; one it has already changes nothing |
| `grid.column(name, kind=, aggregate=, weight=)` (new) | `setColumn`, as Column Properties sets it; None clears an Aggregation or a weight |
| `grid.measure(column, fn, weight=)` | `measureAsColumn`: `setColumn(column, {aggregate: fn, weight})` -- a measure's, as the panel refuses a dimension's (only converting a saved list or a chart's values makes a dimension a measure) |
| `grid.measure(column, fn, name=...)`, or of a grouped column | `measureAsColumn`: a calculated column `name` reading `column`, by the column editor's own steps (`column-draft.ts`), with that Aggregation |
| `grid.configure(settings)` | `config.ts withConfiguration`, the rule `CubeApp.applyConfiguration` applies a host's patch by: the appearance and each column's merged, None clears; a column's `kind`, `aggregateFn` and `aggregationParameters` by `setColumn` (a kind is set, never cleared) |
| `Page.grid(rows=, columns=, measures=, sort=, dimensions=)` | the same, in one call (`dimensions`: `setColumn(name, {kind: 'dimension'})` first) |

`gridQuery`'s own parsing and checks went (`columnsFrom`, `measuresFrom`, `sortsFrom`, `queryFrom`,
`producedColumns`), with `offers.ts measureOfferProblem`.

### 3.4 Measures saved before this: converted on open

One function, `measuresAsColumns`, turns a list of measures into Column Properties and calculated columns. `openCube`
uses it on a saved cube (the screen's and Python's alike), and Open in Grid uses it on a chart's measures. In order:

- a measure of a column that is gone: left out, as today;
- named as its own column, the column no key and not yet taken, its aggregate one Aggregation offers: that column's
  Aggregation;
- any other offered: a calculated column of its name reading the column, kind measure, with that Aggregation (left
  out, said, when its name is taken);
- not offered (a count or a sum of a text column): left out, said; "Show leaf count" counts a group's rows.

Each is said in a note, and `changed` is set, so Python's page takes the converted cube (`page.py _reopened`). Two
views differ from the old list's: a measure renamed now shows beside its column, as a calculated column does; and a
pivot spreads every measure column not kept out of it, as the screen's pivot always has.

### 3.5 Charts

The chart's model is unchanged: its grouping and its `(column, aggregate)` pairs. One follow rule reads the grid's
Column Properties where it read the grid's measures:

- **A following chart** (`live`: not frozen, not of rows, not drawing the grid as shown) takes the grid's grouping, as
  today, and plots each value at the grid's Aggregation of the column that carries it (`followCube`): the value's own
  column at its aggregate, else a calculated copy of that column at it (`grid-edits.ts copiedColumn` -- so a saved
  page's min and max of one column, converted into the column and a copy, stay two values), else its own column, whose
  Aggregation it takes. Changing a column's Aggregation redraws a grouped grid's chart that plots it; with nothing
  grouped, a chart is left as it is. A kind of several values (dumbbell, range, radar, parallel) keeps its own values;
  a KPI or a gauge keeps its own column and takes that column's Aggregation. A hand on what the grid sets freezes the
  chart (`freezesOn`), as today.
- **A frozen chart** keeps its own grouping and pairs, whatever the grid does.
- **A new chart's default value** is its column at that column's Aggregation (`firstMeasure`), not always `sum`.
- **Open in Grid** (`page/cube-page.ts #openEditor`) turns the chart's pairs into the editing grid's Column
  Properties by `measuresAsColumns`, each (column, aggregate) pair once: a pair's column its Aggregation, a second pair
  of a column a calculated column named for it; the draft is folded before the grid opens it, so the source grid's
  own Aggregations do not replace the chart's. **Update** regroups the chart by the follow rule once and freezes it
  again, as today: each value read back from the column that carries its pair (two values of one pair alike), so a
  pair changed in the grid reaches the chart -- except a kind of several values, which keeps its own.

## 4. Behaviour changes

- **Python.**
  - Grouping or pivoting by a column that is not a dimension is refused; `grid.column(name, kind='dimension')` makes
    it one, as Column Properties does.
  - An aggregate is one Aggregation offers for the column's type: a count of a text column is refused
    ("Show leaf count" counts a group's rows).
  - `grid.measure` with no grouping sets the column's Aggregation and shows the rows as they are, as the screen does;
    it no longer makes a one-row total.
  - A measure is a column's Aggregation, shown in Column Properties; a named one is a calculated column. A saved page's
    measures are converted on load, with a warning.
  - `grid.sort(column, ...)` replaces that column's sort rather than adding a second.
  - `grid.configure` sets settings as DataCube sets a host's: each column's merged into what it has (it replaced the
    column map), None clears one, and they are folded into the query -- so a row cap set after an edit holds (it was
    overridden on open by the query's copy).
  - A grid's open groups stay when it is regrouped, as on the screen (Python let them go).
  - A chart told to follow its grid and told a value at another aggregate than the grid's Aggregation is refused.
- **The browser app.**
  - A saved cube's measures are converted on open, said in its notes, and folded, so the grid opens on them; a sort of
    a measure left out goes too, said.
  - A grid that carries a measures list (a JavaScript host's, through `CubeApp.change`) is refused by its controller,
    never read.
  - Open in Grid writes Column Properties and calculated columns: the grid it opens shows its aggregates in Column
    Properties.
  - A new chart's default value takes its column's Aggregation, and a following chart plots its column at the
    grid's Aggregation (it plotted the grid's first measure, which no panel showed).
  - Update from Open in Grid carries the editing grid's aggregates back to the chart.
  - Removing the horizontal pivot drops a sort of a pivot's header (the flat grid had no such column, and its query
    was refused).

## 5. Steps

1. `grid-edits.ts`, and the screen calling it: no change on the screen (57f365d).
2. and 3., in one commit since Python's calls need `gridEdit` (baf0bfd): `measures` off the grid's type, the builder's
   own input worked out per query; `measuresAsColumns` for `openCube`'s conversion and Open in Grid; the example
   page's cube; the follow rule (`followCube`, `followedValue`, Update); headless `gridEdit`; `page.py`'s `group`,
   `pivot`, `sort`, `measure`, `column` and `Page.grid`; `gridQuery`'s own checks deleted.
4. The Query app's grid (cbcd4d6), and the homework's three fixes (da1f546): open groups kept, `column()` clears,
   `configure` by `withConfiguration`.
5. Docs and the record: this file, `docs/DATACUBE_COMMAND_API_HOMEWORK_2026_10_17.md`,
   `docs/PYTHON_ASKS_DATACUBE_2026_10_15.md`, `docs/IN_FLIGHT.md`, the PR description.

Then the DataCube + Python line's review, the full local set, one push, CI.
