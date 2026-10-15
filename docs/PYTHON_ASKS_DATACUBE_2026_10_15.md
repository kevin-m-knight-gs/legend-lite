# Python asks DataCube: one rulebook, never a copy (2026-10-15)

**The rule (the user, 2026-10-15, on the chart rules found written twice: "one rulebook for sure").** What DataCube
decides -- what it draws, how it reads and writes a page, its defaults, its names -- is written once, in DataCube's
code, and Python asks it: through the headless bundle (`datacube/src/headless/entry.ts`, run in V8 by
`python/legend_lite/_headless.py`), or from a generated artifact. Python turns its own arguments into DataCube's keys
and nothing more. A pure vocabulary list Python repeats is allowed only where `datacube/test/python-vocabulary.test.ts`
holds it equal to DataCube's; logic is never repeated, and a table of shared cases is a test of the asking, never the
glue between two copies.

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

## The next program: the other twins (the audit of 2026-10-15)

Each to move behind a call to DataCube's code, its own landing, with the DataCube + Python line. In the audit's order
of harm:

1. **Reading and writing a page** (`page.py _check`, the shape half of `_problems`, `remove`, the name setter, `_update`
   ↔ `page-document.ts readPage`/`writePageOf`/`pageContent`, `layout/bands.ts problems`). Python refuses any version
   but 4 where DataCube migrates 1 to 3, and checks no model, sheet name or chart title. Plan: a headless `readPage` and
   `pageContent`, so Python reads (and migrates) as DataCube does and writes what DataCube would.
2. **A new grid's defaults** (`Page.grid`'s cube document ↔ `app/sources.ts rawRows`/`rebuilt`, `writeCube`:
   `maxRows` when capped).
3. **Layout editing** (`page.py _split`, `_without`, `_replaced`, `_place`, `_bands_without`, `_band_height`,
   `Sheet.arrange`, `Sheet.layout`, `Page.stack` ↔ `layout/bands.ts split`, `without`, `replaced`, `add`, `remove`,
   `bandHeight`, `arrange`). Pure functions: the bundle can export them as they are.
4. **Type families** (`GridView.typed`, `to_pandas`, plain names by `split('::')[-1]` ↔ `engine-client/src/types.ts`
   over the generated `TYPE_FACTS`): `Timestamp`, `BigInt`, `Numeric`, `Double`, `LatestDate` misread today. Plan: the
   fact table's generator writes Python's copy too (a generated artifact, diff-tested), or the bundle tags each column.
5. **Typing a calculated column** (`Grid.calculate`, `_step`, `_pure_name` ↔ `query.ts extendDerived`,
   `calc.ts columnRef`). Plan: a headless call running `sourceColumns` and `extendDerived`.
6. **A filter's arity and values** (`_NO_VALUE`, `_LIST`, `_COLUMN`, `_value` ↔ `query.ts filterNode`, `snapshot.ts`
   `FilterValue`). An operator taking a list or two values would be forced into Python's words and refused there.
7. **A class's sample rows as text** (`_sample_rows`, `_sample_value`, `_time_text`, `_js_number` ↔
   `model-actions.ts sampleOf`, `values.ts`). Plan: `gridClass` reads the sample on the headless connection.
8. **The snap's limit and receipt** (`engine.py _warehouse_snap`, `_MAX_SNAP_ROWS` ↔ `snap.ts MAX_SNAP_ROWS`,
   `preflight`, `receipt.ts`). At least: the vocabulary test reads `engine.py` too.
9. **A server's plans** (`upstream.py` ↔ `planner.ts serverPlan`, `engine-client/src/legend/engine.ts`): interim by its
   own words, to move into the native library.
10. **The planner protocol's side lists** (`engine.py _PAGE_KINDS`, `_planner.py HELD` ↔ `python-host.ts`'s
    `'handle' in body`).

Smaller, none checked mechanically: `model.py` ↔ `model-changes/src` (five replayed cases), `warehouse.py` ↔
`warehouse.ts` (the SQL API corpus), `_json.protocol` ↔ pure-protocol `toJson` (key order, float spelling),
`frames._NAME` ↔ `model-names.ts isPureName`, `_next` ids ↔ `cube-page.ts`.

## A finding for the compiler line

No Python frame, and no tab's table read the same way, has a `StrictTime` column: the compiler's catalog reads a
database's `TIME` as stored text (`DuckDb.java`'s catalog rules map it to `OTHER`; found 2026-10-15, building a
time-of-day column for the chart cases from pandas and from Arrow). Python's chart cases reach one through a calculated
column instead (`%10:30:00`), so every case is asked from Python too; a source carrying a time of day is the compiler
line's to decide.
