# One way to edit a grid: the screen's, for the screen and Python alike (2026-10-17)

**Status:** agreed with the user 2026-10-17 ("in #47, before merge"; "yes, as proposed"). Lands in PR
neema2/legend-lite#47 (branch `claude/epic-rubin-ay7upl`), reviewed by the DataCube + Python line before push.

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

Headless `gridEdit` replaces `gridQuery`. It turns Python's call into the module's edits, then compiles the grid as
`#applyDraft` does, and answers the query, configuration and open rows Python's page document keeps:

| Python | The module |
|---|---|
| `grid.group(*rows)`, `grid.pivot(*columns)` | `setGrouping`, `setPivot` |
| `grid.sort(column, direction)` | `setSorts`: the column's sort replaced, added last (the menu's Add Ascending) |
| `grid.column(name, kind=, aggregate=, weight=)` (new) | `setColumn`, as Column Properties sets it |
| `grid.measure(column, fn, weight=)` | `setColumn(column, {aggregate: fn, weight})` |
| `grid.measure(column, fn, name=...)`, or of a grouped column | a calculated column `name` reading `column`, by the column editor's own steps (`column-draft.ts`), then `setColumn(name, ...)` |
| `Page.grid(rows=, columns=, measures=, sort=)` | the same, in one call |

`gridQuery`'s own parsing and checks go (`columnsFrom`, `measuresFrom`, `sortsFrom`, `queryFrom`,
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
  today, and plots its value column at the grid's Aggregation of that column (`followCube`). Changing a column's
  Aggregation, on the screen or from Python, redraws the chart that plots it. A kind of several values keeps its own;
  a KPI or a gauge follows the first measure column at its Aggregation. A hand on what the grid sets freezes the chart
  (`freezesOn`), as today.
- **A frozen chart** keeps its own grouping and pairs, whatever the grid does.
- **A new chart's default value** is its column at that column's Aggregation (`firstMeasure`), not always `sum`.
- **Open in Grid** (`page/cube-page.ts #openEditor`) turns the chart's pairs into the editing grid's Column
  Properties by `measuresAsColumns`: a pair's column its Aggregation, a second pair of a column a calculated column
  named for it. **Update** regroups the chart by the follow rule once and freezes it again, as today: its values read
  back from those columns' Aggregation, so a pair changed in the grid reaches the chart.

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
- **The browser app.**
  - A saved cube's measures are converted on open, said in its notes.
  - Open in Grid writes Column Properties and calculated columns: the grid it opens shows its aggregates in Column
    Properties.
  - A new chart's default value takes its column's Aggregation, and a following chart plots its column at the
    grid's Aggregation (it plotted the grid's first measure, which no panel showed).
  - Update from Open in Grid carries the editing grid's aggregates back to the chart.

## 5. Steps (one commit each)

1. `grid-edits.ts`, and the screen calling it: no change on the screen.
2. `measures` off the grid's type: the builder's own input, worked out per query; `measuresAsColumns` for
   `openCube`'s conversion and Open in Grid; the example page's cube; the follow rule (`followCube`, `firstMeasure`,
   Update).
3. Python onto the module: headless `gridEdit`, `page.py`'s `group`, `pivot`, `sort`, `measure`, `column` and
   `Page.grid`; `gridQuery`'s own checks deleted.
4. Docs and the record: `docs/PYTHON_ASKS_DATACUBE_2026_10_15.md`, `docs/IN_FLIGHT.md`, the PR description.

Then the audit, the full local set, the DataCube + Python line's review, one push, CI.
