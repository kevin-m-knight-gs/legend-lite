# One keeper per page: homework before the build (2026-10-20)

Why: the user asked that Python and an open tab never get out of step on a page they both edit
(docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §18.8's real gaps all came from two copies of one page drifting). The plan
proposed: the host keeps the page's one document; every write from either editor is versioned; a write against an old
version is re-applied as commands, or refused. Before building it, two questions: (1) which of the tab's changes are
commands already, and (2) where the line between the shared page and each editor's own view state falls. This note
answers both from the code as it is (claude/datacube-query-base at 1a388acb4) and says what changes in the plan.

## 1. Every way the tab changes the page

The tab already produces commands for most edits -- grid edits as `GridCommand`s (app.ts `onCommand`), structural
page edits as `PageCommand`s (page/cube-page.ts `onPageEdit`) -- but nothing sends them anywhere: the live report
(datacube-app.ts `liveChanged`) posts the whole document, 500 ms after a change settles.

| Change in the tab | Where | A command today? | Kind |
|---|---|---|---|
| Group, pivot, sort (menus, zones, header clicks), hide, heatmap, Column Properties, measures, add a calculated column | app.ts `#apply` | yes (`GridCommand`) | shared |
| Filter window's Apply | app.ts `#query(withFilter)` | no -- `filter` exists, unused here | shared: make it one |
| Calculated columns editor: edit, rename (renames carried through), remove | app.ts `#setCalc` (`#change`) | no -- `calculate` covers only adding | shared: new command |
| Settings window, a host's `applyConfiguration` | app.ts `#configure` | no -- `configure` exists, unused here | shared: make it one |
| Use a dimension | app.ts `useDimension` (`#query`) | no | shared: make it one |
| Chart click on a grid's chart (not a class's): the grid's filter and the chart's `selection` | cube-page.ts `#select` + app.ts `refilter` | no | shared: new command |
| Chart click on a class grid's chart | page-app.ts `onPick` -> `setPageFilter` | yes | shared |
| Add / remove / rename a tile, a sheet; move to sheet; add a chart; a chart's spec by hand | cube-page.ts `#tell` | yes (`PageCommand`) | shared |
| Layout drag; Undo Layout / Redo Layout | cube-page.ts `#tellLayout` | yes (`layout`, the sheet whole) | shared |
| Page filters set / removed | page-app.ts `#applyFilterCommand` | yes | shared |
| Page rename (name box) | datacube-app.ts `renamePage` | no -- `renamePage` exists, unused here | shared: make it one |
| Page-level Undo / Redo | page-app.ts `#move` | no -- the page put back whole | shared: whole document |
| Create a Class / Data Space / Association: the page's model | datacube-app.ts `current.model` | no | shared: whole document |
| Snap / Go Live | the plane toggle, then the page's state | no (the copy is in the document) | shared: the keeper's own operation |
| A following chart regrouped with its grid | cube-page.ts (followCube) | no -- derived | derived (see 2) |
| Expand / collapse a row, Collapse All | app.ts `#change(tree)` | no | each editor's own (see 2) |
| Sheet shown, scroll, cell selection, a chart's editing grid, windows | screen only | not in the document | each editor's own |
| Settings saved for every grid (Settings > defaults) | `onSettingsChanged` | not the page's | the user's, not the page's |

Of about twenty kinds of change, eleven are commands already; six could be with modest work (four have their command
kind already); two are whole documents by nature (page Undo/Redo, the page's model); one is the keeper's own (Snap);
one is derived; the rest are no part of the shared page.

Python's side, for the same question: every edit is a command except four, which serve the page whole today --
the page's model (page.py `_into`/`_keep`, which also change `_doc['model']` in place), a cube reopened over a frame
that changed (`_reopened`), a grid edit whose cube was reopened with notes, and the first change after `read()`.

## 2. The line: what the keeper versions

Today "changed since saved" compares `pageDefinitionText`: each cube's query and configuration, the views, sheets,
model and filters -- not the name (renaming is saving), not the open rows, not the copies. That is not the keeper's
line: a Snap changes nothing it compares, and the keeper must version it.

| Field | Keeper | Why |
|---|---|---|
| `name` | versioned | a rename on either side is the page's |
| cube `source`, `query` (columns, calculated columns, filter, rows, pivot and its values, sorts, totals, row limit) | versioned | what the grid is |
| cube `configuration` (formats, kinds, aggregates, hidden, blurred, pinned, widths, title bar...) | versioned | saved with the page, set by Python's `configure` too; a width drag is an edit |
| cube `tree` (open rows, totals shown) | stored, not versioned | where each person has looked: kept for a save or a reload, last write wins, never a conflict or a reason to reopen |
| views: grid (id, cube, title), chart (id, cube, title, spec) | versioned | |
| a chart's `selection` | versioned | it is half of a filter that is versioned (its grid's), so the two move together |
| a following chart's spec | versioned, and derived | both editors must derive it by the one rule on every write (followCube), or the page ping-pongs between a stale and a fresh spec: Python's grid edits do not regroup a following chart's stored spec today |
| `sheets` (names, layouts) | versioned | |
| `model` (project, workspace, package, tables, classes, revision) | versioned | |
| `filters` | versioned | |
| `copies` | versioned, and the keeper's | what each grid reads; the keeper keeps a copy while some page it keeps reads it |
| `unknown` (fields a newer writer added) | versioned | carried |

The rule is a function of its own -- the document less each cube's `tree` -- not `pageDefinitionText`, which stays
"changed since saved".

## 3. What changes in the plan

1. **The keeper's comparison is its own** (section 2), so a Snap bumps the version and an expanded row does not.
2. **Derived state is derived by every writer.** A following chart's spec is regrouped by the one rule whenever its
   grid changes, on Python's side too (headless gridApply returns it), so both editors write the same document.
3. **Python writes through one function.** `_doc['model']` is changed in place today; under the keeper every write
   is one versioned write, or it bypasses the version.
4. **Convert the six to commands early.** Each one left is a whole-document write, which, against a concurrent change
   in Python, is refused and reopened rather than re-applied -- the edit is lost. The filter window, the settings
   window and the page rename already have their command kinds; the calculated columns editor, a dimension and a
   chart's click need theirs. Page Undo/Redo and the page's model stay whole documents: rare enough to accept a
   refusal under a concurrent change.
5. **Wire what exists.** Both kinds of command are produced already and consumed by nothing in the app: the keeper's
   client is that missing consumer, not new command plumbing.

## 4. The convergence test

Not spiked yet (planned as the build's first step). What exists makes it two halves and one seam: Python's half
drives the keeper with Python's edits and the tab's writes made by headless DataCube (the same functions the tab
runs); the tab's half drives the real page app (page-app.test.ts runs PageApp and CubeApp in a simulated DOM) against
a keeper of its own; the browser harness (datacube/test/python-engine) checks the seam on a few paths. Each checks,
after every step, that both editors hold the same document (less `tree`) and that the copies held are exactly those
the kept pages read.

## 5. Order

The design note's build, with these changes folded in: (a) the keeper and its comparison, Python writing through it;
(b) the tab's commands sent to it, whole documents as the fallback; (c) the six converted, following charts derived
by every writer; (d) Snap as the keeper's operation, the ownership code deleted; (e) `ll.show(df)` as a page of one
grid; (f) the two Snap fixes (a snapped grid opened over its copy everywhere; the copy typed by the live query); then
an independent audit.
