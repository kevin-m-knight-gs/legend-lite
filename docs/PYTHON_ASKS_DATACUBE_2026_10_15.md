# Python asks DataCube: one rulebook, never a copy (2026-10-15)

**The rule (the user, 2026-10-15, on the chart rules found written twice: "one rulebook for sure").** What DataCube
decides -- what it draws, how it reads and writes a page, its defaults, its names -- is written once, in DataCube's
code, and Python asks it: through the headless bundle (`datacube/src/headless/entry.ts`, run in V8 by
`python/legend_lite/_headless.py`), or from a generated artifact. Python turns its own arguments into DataCube's keys
and nothing more. Logic is never repeated, and a table of shared cases is a test of the asking, never the glue
between two copies. Nor is a vocabulary list: the last ones Python repeated (its chart words, the chart's and the
page's versions and kind, held equal by `datacube/test/python-vocabulary.test.ts`) went on 2026-10-16 with the test --
DataCube's reader refuses a word it does not know, naming the words it takes, and a new page is DataCube's own
(headless `pageNew`). The round's audit found four more, gone the same day: the export kinds (DataCube's
`gridExport` judges the kind, `export-render.ts EXPORT_KINDS`), a new sheet's layout (headless `layoutNew`, the
screen's own `bands.ts NEW_SHEET`), a relative date's words (`gridFilter` judges them) and a class column's roles (the
compiler's `CreateClass` refuses another, by name).

**What makes it possible: one package, two things** (the user, same day). `pip install legend-lite` is the compiler,
on Python's standard library alone. `pip install 'legend-lite[datacube]'` is DataCube with everything it needs --
duckdb, pyarrow and mini-racer's V8 -- and every DataCube module asks for it first (`legend_lite/_extra.py`), so V8 is
always there when DataCube is: there is never a case for a copy "in case V8 is missing".

## Done, with PR neema2/legend-lite#42

- **A chart's rules, its reader and its defaults** (`page.py _drawable`, `_spec`/`_measure`/`_slots`'s judgements --
  a measure's or a facet's keys among them, now the reader's, which refuses any key a chart has no place for -- the
  grid's first measure, the grain-goes-with-its-column rule in `Chart.plot`): now DataCube's `readChart`, `chartFrom`
  (its new chart changed by `editChart`, the panel's form's own change by hand), `followCube` and `chartProblems`,
  asked through `gridChart`, against the grid as DataCube opens and types it.
- **A removed grid's model** (`Page.remove`): a grid made a class and then removed left its table's name in the page's
  model, which DataCube's reader refuses -- the page would never open again. Now DataCube's `keptFor`, asked through
  `pageModelKept`.

## The other twins (the audit of 2026-10-15): done on `claude/epic-rubin-ay7upl`, landing as one PR

Each moved behind a call to DataCube's code or a generated artifact, a commit each, every behaviour change reviewed by
the DataCube + Python line before CI (the BUILD edits by the Compiler Rewrite line). In the order they landed:

- **4, type families.** `TypeFacts.java` writes Python's copy of the type table (`python/legend_lite/_type_facts.py`,
  diff-tested); `_types.py` reads it -- `plain_type` and the predicates refuse an unknown name -- and no other module
  spells a type's name (test_frames' `TypeNamesAreReadHere`). A server's `Int`/`BigInt` column is now an Integer.
- **10, the planner protocol's side lists -- no twins at all** (2026-10-15). They existed because a page on Python
  carried the tab's worker requests over HTTP (`planner.json`), which needed an allowlist and a handle translation on
  both sides. The user's step back retired that: a page asks Python on legend-engine's own routes (`pure/v1`) and the
  host's compute (`plan-and-run.json`, and its dry run `plan.json`; the one-app design §15.10), so neither list exists,
  in Python or TypeScript. What stays is no twin either (the user, 2026-10-16): `planner-answer.ts`'s switch and
  `_planner.KINDS` are each host's binding to the same `Boundary` functions -- glue, not a rule -- held alike by the
  start-up check (`_headless.check_interface`). One dispatcher in the compiler was built, measured and dropped: one
  generic request put a JSON envelope around every model's text. Follow-up: shrink the protocol toward legend-engine's
  own `pure/v1` API.
- **8, the snap's limit -- Python's own** (2026-10-15): a fact of the database a copy lands in (this machine's, the
  tab's browser memory), so the two need not agree; no pin.
- **3, layout editing.** `headless/layout.ts` over `bands.ts` `add`, `remove`, `arrange` and the new `stackTiles`
  (built from `drop`); Python's band editors deleted. Found on the way: `arrange` took any preset name on a page of
  one place or none -- it now checks first.
- **1, reading and writing a page.** `headless/page.ts pageRead` (`readPage` then `pageToJson`): Python loads, reads
  back, lays out, saves and shows a page through DataCube's reader -- any version it reads, migrated; an empty page
  refused; two cubes of one id refused (`readPage`, new). `_check` and `_problems` deleted.
- **2 and 1's reopened cube.** `rawRows` and `rebuilt` moved from `app/sources.ts` into `cube-document.ts`, with
  `frameSource`; `headless/grid.ts gridCube` makes a grid's cube over a frame as DataCube does, new (capped beside
  others) or rebuilt over the frame as it is now (a gone column left out of everything, said as a warning).
- **5, a calculated column.** `gridCalculate`: the column editor's own steps (`column-draft.ts`, no DOM, which the
  editor calls too: a new column's draft, a measure unless told, as the editor starts one; `nameProblem`; the
  planner's parse; the cube with the draft), the cube compiled as a refresh sends it. `_step`, `_pure_name` deleted.
  DataCube now refuses what Python's own typing passed: a StrictTime column on this plane (its query does not lower;
  below).
- **6, a filter.** `filter-operators.ts` (no DOM): the editor's operator table, now exhaustive over `FilterOperator`, and
  its draft tree and conversion (`toFilterNode`), which the editor calls too; `gridFilter` builds Python's argument
  into the editor's draft tree by each operator's operand (each named, none by default) and offers -- its values on the
  editor's `stored` path, exact, as a reopened filter's -- converts it as the editor does (a group of one that one, an
  empty group none), refusing a condition the editor would leave out, and compiles it. Python keeps only the shape of
  its argument and its values' JSON.
- **7, a class's sample rows.** `sample-rows.ts sampleOf` (out of `model-actions.ts`), run by `gridClass` on the host's
  database; Python's four value writers deleted (the formats are pinned in test_model). (A review had Python plan the
  sample on its session's plane and hand DataCube the rows, `classSample`, for a session planned by a server; with one
  plane, 2026-10-15, that went back.)

What is left, and why:

- **Item 1's name rules -- done** (2026-10-15, the user: "blank, one rule please"): `page-names.ts givenName`, a page's
  name and a tile's title not blank, a sheet's blank none of its own; the screen's renames, the reader and Python
  (headless `nameGiven`) ask it.
- **Item 8's rest** (`engine.py _warehouse_snap`'s preflight and receipt): left to the DataCube + Python line's plan for
  SQL handling, which replaces both snap recipes, the tab's and Python's, with one plan both hosts run.
- **Item 9 is gone, not done** (2026-10-15): `upstream.py`, the copy it named, was removed with the remote planner (the
  user: a host plans in process, `docs/ONE_PLATFORM_PROGRAM_2026_10_12.md` §1.1), and with it the `BIT`-as-`TinyInt`
  label it left in Python's rows and the server-plane difference items 5 and 6 had (PARK-30, closed by the removal).
- **The smaller ones, each looked at again (2026-10-15):**
  - `model.py` ↔ `model-changes/src`, the model-writing orchestration (held by five recorded cases, replayed by both):
    to become one Java operation in the SDLC module, "edit a project", that works with a real legend-sdlc too (the
    user approved it on that condition; the Studio / SDLC line's own PR, recorded in the program doc's B4). Its one
    Python helper, `_json.protocol` (a request's JSON written byte for byte as `toJson` writes it), goes with it.
  - `warehouse.py` ↔ `warehouse.ts`: held by the SQL API corpus; the DataCube + Python line's plan for SQL handling.
  - `frames._NAME` is Pure's own identifier rule, which Python needs as the writer of its frames' model: no copy of
    DataCube's. Its one inconsistency is fixed (a file's table name read lowercased, so `True` was refused). It is a
    copy of the compiler's `ModelChanges.isIdentifier`, kept and named so (the user, 2026-10-16: moving the check into
    the model writer, `Boundary.tableModel`, was weighed and not taken). DataCube holds a third copy for its naming
    dialogs (`model-names.ts isPureName`): a follow-up, not in PR neema2/legend-lite#47, could have them ask the worker.
  - `_next` ids (page.py) are no copy of `cube-page.ts`'s: Python never reuses an id a handle once had.
- **The vocabulary test's pins, each retired with the copy it held equal** (`datacube/test/python-vocabulary.test.ts`,
  deleted 2026-10-16; never loosened):
  - `BAND_HEIGHT`, `MAX_COLUMNS`, `SCREEN_BANDS`: with page.py's band editors (item 3; `headless/layout.ts`).
  - `CUBE_KIND`, `CUBE_VERSION`: with its cube writer (item 2; `gridCube`).
  - `FILTER_OPERATORS`: with its filter rules (item 6; `gridFilter`).
  - The engine's planner kinds and `MAX_SNAP_ROWS` (items 10 and 8, 2026-10-15): no twins at all -- a page asks a
    Python host on legend-engine's own routes and the host's compute, never by the tab's worker kinds, and the snap
    limit is Python's own.
  - `AGGREGATES`, `DIRECTIONS` (2026-10-16): with its measure and sort rules (`gridQuery`).
  - The chart words (`MARKS`, `MEASURE_MARKS`, `AXES`, `SHOW_AS`, `GRAINS`, `LAYERS`, `STATS`), `CHART_VERSION`,
    `PAGE_KIND` and `PAGE_VERSION` (2026-10-16): Python keeps none of them -- DataCube's chart reader judges each word
    (`gridChart`), a chart's version is the one DataCube starts it from, and a new page is DataCube's own (`pageNew`,
    over `page-document.ts startingViews`, whose first grid the same `bands.ts add` places as a page started on the
    screen). With the last pin gone, the test went.

## A finding for DataCube (its line designs the fix)

**A calculated column kept at the editor's starting Level is summed, whatever its type** (found 2026-10-15, item 5): the
column editor starts a new column as a `measure`, and a column declared a measure was summed when the cube grouped -- so
a boolean or a text column a person added and grouped by was refused, `sum(Boolean)`. **Fixed 2026-10-15** (the
DataCube + Python line chose the default aggregate over the editor's starting Level, which a person or a saved page can
still set): one rule, `query.ts defaultAggregate` -- `sum` for a numeric column not declared a dimension, `unique` for
any other -- asked by every query builder (the grouped aggregates, a child group's measure, the detail cube's, a pivot's
spread), and `CubeController.compile` types the cube first, as a refresh does, so its check compiles the aggregate the
run sends. Python's columns are measures unless told, as the editor's are.

**A grouped grid left measures out without a word** (found 2026-10-16, the round the user asked for: "fix the bad
stuff"; "lets fix datacube where we find bugs"). With no pivot, the level query keyed its measures by column, so a
column's second measure replaced its first (a sum and an average of one column: the average alone) and a measure of a
grouping column (a count of its rows) was skipped. **Fixed:** `query.ts groupedMeasures`, every measure under its own
name, the one list the level query writes and the group stage's calculated columns are offered (`calc.ts
columnsInScope`, which offered the measures alone and so left out the columns the query aggregates). With every measure
kept, a measure's name follows the calculated column's rule (`calc.ts takenNames`, `measureNameProblem`): its own
column's while that column is no key, or a name nothing else has, the queries' reserved `__root__` and `__leafCount`
included -- refused where a level would write two columns of one name, in DataCube's words on the screen and in Python:
grouped, every measure; pivoted, a measure kept out of the pivot (written beside the keys), while a measure spread
under each pivot value writes `<value>__|__<name>` and so clashes only with another measure's name (the round's audit
found the first version refusing those too, 2026-10-16). The charts' stand-in for the bug, a copy of a column (`__chart_<name>`) for each measure of it
past the first, went with it. Found beside it, and fixed: a grouped level sorted by a column a measure renamed (a sort
on `notional` beside `total` of it) asked the planner for a column its groupBy did not keep, so every grouped level of
the grid was refused; it now sorts by what it writes, as a pivot's level did, the detail rows by the rest. A cube saved
before the rule -- old Python's `grid.group('region').measure('region', 'count')`, whose count the grouped level had
dropped without a word, or a sum of a text column, which old Python checked no type of -- opens without each measure
the rule or its column's type refuses (`offers.ts measureOfferProblem`), judged in order as the level judges them, and
says so (`cube-document.ts openCube`, as it leaves out a measure of a column that is gone): the screen's note, Python's
warning when it loads the page. No panel shows a measure to fix, so refusing it at every level would lock the page. A
page Python loads takes each cube as DataCube opens it whenever opening changed it (`page.py _reopened`, at load and
when a frame is served again), so no later call on the grid sends the measure DataCube left out -- held in Python's copy
alone, it was refused beside the next measure added, at a call that never named it.

**The query tests check printed text only** (found 2026-10-16, with the bug above): `query.test.ts` pinned a grouped
level's query sorting by a column its groupBy did not keep -- the very query the planner refuses -- and nothing compiled
it. A follow-up, not in PR neema2/legend-lite#47: compile each pinned level through the planner stand `calc.test.ts`
already uses.

**A set measure silently overrides the column's Aggregation** (found 2026-10-16, written up for the DataCube line to
design): `config.ts` never reads `snapshot.measures`, and the level query takes a column's set measure over its Column
Properties > Aggregation -- so on a page Python made, changing that column's Aggregation in the panel does nothing, and
says nothing. True before the fix above; with two measures of one column and one dropdown it is easier to see. How the
panel shows a column's measures is the DataCube line's to decide. A known limit that comes from the same gap (recorded
2026-10-16, not fixed): old Python's `grid.pivot('desk').measure('notional', 'sum', name='pnl')` is legal (a spread
measure clashes with no column), but a person who takes the pivot off on the screen meets the grouped column rule, and
every level refuses: "'pnl' is already a column". The refusal names the measure, and putting the pivot back undoes it;
renaming the measure is Python's alone until the panel shows measures.

**A class's sample writes a NaN or an infinity as `null`** (found 2026-10-15, the audit of item 7): `sample-rows.ts
sampleOf` keeps a double as the executor gives it, and the sample goes to the route as JSON, which has neither, so
`JSON.stringify` writes `null` -- the window's Create a Class and, since item 7, Python's alike. Python's own writer
refused them before. A silent `null` is a default (AGENTS.md invariant 4). **Fixed 2026-10-16:** `sampleOf` refuses the
sample, naming its column and row, before anything is written -- on the screen and in Python, in one rule. (The round's
audit found the window's Create a Class still writing its sample by a copy of its own, `create-class.ts`; it asks
`sampleOf` now, as Create a Data Space and Python's `gridClass` do.)

## A finding for the compiler line

No Python frame, and no tab's table read the same way, has a `StrictTime` column: the compiler's catalog reads a
database's `TIME` as stored text (`DuckDb.java`'s catalog rules map it to `OTHER`; found 2026-10-15, building a
time-of-day column for the chart cases from pandas and from Arrow). Nor does a calculated column give one on this
plane: a cube with `%10:30:00` compiles to a relation type (compile-only), but its query does not lower --
"IllegalStateException: no SQL type for Pure primitive STRICT_TIME at the lowering boundary" -- so DataCube's column
editor refuses it, and since item 5 so does Python (found 2026-10-15, when Python's calculated columns began to be
checked by DataCube's own compile). Python's chart cases reach a StrictTime column as a page saved where one is lowered
holds it, loaded, so every case is still asked; a time of day in a source and in the lowering is the compiler line's to
decide.
