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
- `measures` stays an input of the query builder, for the queries a grid is not: a chart's (a chart plots
  `(column, aggregate)` pairs, its own model, `chart-spec.ts`) and one-off queries (`app.ts`'s JSON row count).
- `writeCube` refuses a grid snapshot that carries measures (AGENTS.md invariant 4: a grid with two models is a bug,
  never saved).

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
actions (`ui/menu.ts`), Column Properties' kind and Aggregation (`ui/panel-column.ts`). The screen's offers already
keep a person inside these rules, so nothing changes on the screen; the rules now have one home.

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

The chart's model is unchanged. Two readers of a grid's measures read its Column Properties instead:
`chart-spec.ts firstMeasure` (a new chart's default value: its column's Aggregation, not always `sum`) and
`followCube` (a following chart follows the grid's grouping; its values stay its own).

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
  - A new chart's default value takes its column's Aggregation.

## 5. Steps (one commit each)

1. `grid-edits.ts`, and the screen calling it: no change on the screen.
2. `measuresAsColumns`: `openCube`'s conversion, Open in Grid, the example page's cube, the charts' two readers, and
   `writeCube`'s refusal.
3. Python onto the module: headless `gridEdit`, `page.py`'s `group`, `pivot`, `sort`, `measure`, `column` and
   `Page.grid`; `gridQuery`'s own checks deleted.
4. Docs and the record: `docs/PYTHON_ASKS_DATACUBE_2026_10_15.md`, `docs/IN_FLIGHT.md`, the PR description.

Then the audit, the full local set, the DataCube + Python line's review, one push, CI.
