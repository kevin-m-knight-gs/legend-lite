# DataCube's flow mode: a visual ETL tool as a mode of DataCube (2026-10-24)

**The goal (the user, 2026-10-24).** A visual ETL and data prep tool that feels like a slick, modern Alteryx, with as
many of Alteryx's features as possible. legend-lite compiles every step and pushes it down to the database, with a
DuckDB copy (a "cache") when wanted. It is a **mode of DataCube**: the same logic and the same API, drawn as a
pipeline (a DAG) instead of a grid. The strict UI/API split DataCube keeps holds here too.

**Where this comes from.** Two earlier plans already point here, and neither was built:

- `docs/BI_AND_ETL_PLAN_2026_09_29.md` track C: a pipeline is a Pure program, the canvas edits it, the database runs it,
  and every node's preview is a DataCube on that node.
- `docs/DATACUBE_MODEL_DESIGN_2026_10_10.md` §17.6: an Alteryx-like prep flow, in which "the boxes are the page's grids,
  and the lines are their join inputs. No second model."

This note puts the two together and records the user's decisions (§1). §3 to §5 are the design those decisions make.
Nothing in it is built yet.

---

## 1. Decisions (the user, 2026-10-24)

Each was put to the user with its options, pros, cons and cost; the user took the recommended option every time ("Agree
with all"). The options not taken are in §7, so the reasons are kept.

**What it is**

- **FL1. A mode of DataCube, not a second app.** The flow is another way of drawing the page document. Every gesture on
  the canvas is a `PageCommand` or `GridCommand`, applied by the one function, as every other editor's is
  (`docs/DATACUBE_ONE_API_PLAN_2026_10_21.md`). So undo, save, share and Python come with it, and the canvas builds no
  command itself: screen code builds none (One API S8, ledger L11).
- **FL4. The flow is a sheet of the page.** A sheet's layout is a tagged type (`PageLayout`, `kind: 'bands'`,
  `datacube/src/page-document.ts:67`); a flow sheet is `kind: 'flow'`, its boxes placed on a canvas. Prep sheets and
  dashboard sheets are sheets of one page, and a dashboard sheet reads a box through a link.
- **FL5. A box is a grid that does one thing.** A Filter box is a grid with only a filter; a Summarize box a grid with
  only a grouping and its measures. Boxes chained by links give any order. No second model of a query.

**How boxes connect**

- **FL6. An input is live or a frozen copy, chosen per input.** A live input reads another grid's output as it is now
  (`{grid, port}`); a frozen input is a copy of what that grid was, today's `JoinSource` input
  (`datacube/src/cube-document.ts:170`). New joins are live. Joins saved before this read as frozen copies, so no saved
  page changes or stops opening. A live input whose grid is removed becomes frozen, and this is said.
- **FL7. Tools with several outputs have ports**, as Alteryx's anchors are (§3.3). Each port is a query worked out from
  its box; an unconnected port runs nothing. A line's record count is shown after a run, or when asked for, not kept
  live.
- **FL9. A gesture in the results pane adds a box** after the selected one (filter by a value, sort, group), so every
  box keeps doing one thing.

**Typing and checks**

- **FL2. A Cross Tab's columns are fixed by a cast.** A Cross Tab box's output is typed statically: `pivot(...)` is
  followed by `->cast(@Relation<(...)>)`, the pattern core already knows (`compiler/spec/typed/TypedPivot.java`). The
  cast's columns come from **Load values**: the values query (`pivotValuesLambda`, `datacube/src/query.ts:345`) run
  once and written into the cast. The person can then edit the list. Boxes downstream are typed from the cast.
- **FL3. A value the cast lacks is said, never dropped in silence.** A run compares the values query with the cast and
  names every value the cast does not list. With a cast alone, such a value would get no column and nobody would know;
  that is a fallback, and invariant 4 refuses fallbacks.
- **FL8. Each check has a mode: warn or refuse.** The checks: a Cross Tab's values against its cast (FL3); a source
  whose columns changed; the data-quality checks (Alteryx's Test tool, track C5). The mode is set on each check, as
  dbt's `severity` is. A new check is made with **refuse**, written into the document: the document always says the
  mode, and a check read with none is refused, never defaulted. A **preview** always shows the rows and marks the box
  (amber for warn, red for refuse) with the values or columns named and the fix offered ("Add to the cast"). A **run**
  stops before it writes anything on a refusal, and goes on with the finding in its log on a warning. A third mode,
  **follow** (each run reloads the values, rewrites the cast and recompiles what is downstream), is for later.

**Data, databases and Pure**

- **FL10. A Cache box moves data between databases, visibly.** A join over two databases is refused with an offer to
  put a Cache box on one side: a box that copies its input into a chosen database, built on Snap (`snap` and `live`,
  the model design's §18.8). Nothing is copied without a box saying so. First target: the host's own DuckDB (the tab's,
  or Python's on a page Python serves).
- **FL11. The cache lasts as long as its session**, as a snap does today (§18.8, item 7) and as Alteryx's Cache and Run
  does. A reopened flow reads its sources again, and says so.
- **FL12. The page document is the source; Pure is imported, not edited in place.** Each box's Pure is shown and can be
  exported. **Import Pure** turns a lambda into boxes; whatever does not become boxes becomes a **Pure box**: a box
  whose query is a Pure expression over its inputs, checked by the compiler. The Pure box is also "filter by a Pure
  expression" (§17.6, item 6). Editing the Pure and having the canvas redraw (track C2's "both directions") is for
  later, if wanted.

**Scope and timing**

- **FL13. The first version writes no tables.** Its outputs are the Cache box, Snap and the grid's exports. F1 (`write`
  into a table: create, replace, append, merge by key) is built in core in parallel, as a slice of core's own line, not
  inside DataCube's. Output boxes come when it lands. *Since (2026-10-25): the writes line landed store writes on main
  (`docs/WRITES_DESIGN_2026_10_18.md` legs 2 and 7: `write`, `update`, `delete` and `merge` over a store table on
  DuckDB, H2 and Postgres, and write plans run by the model-free runner), so F1 is no longer waited for; Output boxes
  are the roadmap's M6 (§5).*
- **FL14. Transpose is composed, not a new native.** One `select` per chosen column (the keys, that column renamed to a
  name and a value), the selections concatenated. It runs on every database and needs no core change. The value
  columns must share a type; where they do not, the compiler refuses in its words, and a cast is offered.
- **FL15. No Python or R boxes in the first version.** They compute outside the database ("Java orchestrates, the
  database executes"). A Python box run by the page's Python host, as a named exception ruled under
  `docs/TENET_CHARTER.md`, can be asked for later.
- **FL16. The logic now, the canvas after D19.** The link and port commands (pure functions: apply rules, readers,
  rebase, Python builders), Transpose and core's F1 start now, beside D19. The canvas, a second editor of the page,
  waits for D19 (`docs/DATACUBE_ONE_API_PLAN_2026_10_21.md`: the client side of the single writer), so it is not built
  on the races between two editors that D19 fixes.

**Decisions (the user, 2026-10-25)**

- **FL17. The roadmap's order stands** (§5), and so does the reuse: each box's settings are DataCube's own editors.
- **FL18. The Filter window, sleeker, the same view.** As the Join window was rebuilt in the Create a Class window's
  style and the Properties window cleaned up, the Filter window is restyled the same way, keeping its tree of
  conditions and groups as it is: the same view, slightly sleeker. Its one editor (`datacube/src/ui/filter-editor.ts`)
  is the grid's Filter window and the flow's Filter box alike.
- **FL19. Union goes back into DataCube.** The flow's Union (M2) is built as DataCube's own, so a grid can union as it
  can join. DataCube has half of it already: the Join window's **Append** form (`join-how.ts`, `form: 'append'`) puts
  two grids' rows together, columns matched by name, a column one side lacks empty on its rows. What Union adds, to the
  grid and the flow both: a Union entry of its own beside Join, and any number of inputs. Proposed, as Alteryx's Union
  offers it: matching by position as well as by name.

**Decision (the user, 2026-10-25)**

- **FL20. Python's names for flows** (D6): `page.flow()` adds a Flow sheet; `flow.grid(...)` an Input box, with
  `page.grid`'s arguments; `flow.grid(input=box)` (and `page.grid(input=grid)`) a box after another, reading its rows
  live, as `page.grid(cls=..., query=..., warehouse=...)` names its sources; `box.place(x, y)` a box's place. Every
  tool's settings are the grid's own methods (`filter`, `calculate`, `sort`, `group`, ...): a box is a grid. *(The
  same day: `rows=` was proposed first and approved, then found taken -- `page.grid`'s `rows=` is its row groups -- and
  the user chose `input=`, Alteryx's word for what a tool reads.)*

---

## 2. What exists, and what the flow reuses

| The flow needs | What DataCube has today |
|---|---|
| Types on every box, as it is connected | The tab's planner (`//wasm:planner`), the same compiler as the server: every query's relation type, with no server |
| A preview of any box or port | A grid: the selected box's rows shown as a DataCube, as Alteryx's results pane shows them |
| Edits as data | `GridCommand` (`datacube/src/grid-commands.ts`) and `PageCommand` (`datacube/src/page-commands.ts`), one apply function each, a generated Python builder each (D17) |
| The tools' logic | The filter editor (`FilterNode`), calculated columns checked by the compiler (row and window), aggregates (`wavg` and `unique` included), sorts, joins (`join.ts`: any condition, as of, keep my rows with a match or with none, append), Expand and JSON fields |
| A cache | Snap: a grid's rows copied into a store and read from there (§18.8 of the model design: Snap is a command) |
| A second way of drawing the same state | Ad Hoc mode (`datacube/src/adhoc/mode.ts`): "turns a gesture into ONE session call", and "nothing here computes a number" |
| Sheets of one page | `PageSheet` and its tagged `PageLayout` (`datacube/src/page-document.ts:67`) |
| Output | The grid's exports, charts, KPIs and pages. Not a table write (FL13) |

---

## 3. The design

### 3.1 Live inputs: the one missing primitive

Today a join keeps its two inputs by value: "an input is what its grid was when it was joined". So the page is not a
graph: an edit to a grid upstream does not reach the grids that read it.

The flow adds the live input (FL6): the output of another grid, `{grid: 'grid-3'}`, or one of its ports,
`{grid: 'grid-3', port: 'false'}`. It fits the model design's §18 rule ("a grid is a query, not a table"): a live input
denotes the upstream grid's output query, and its leaves are that grid's leaves. Nothing else needs to know what kind
of source a grid has.

What comes with it:

- **A cycle is refused** by the page command that would make it.
- **Grids open in dependency order** (class grids already open last, §16.4).
- **A removed upstream grid** turns the live input into a frozen one, a copy of what that grid was, and this is said.
- **Freeze and unfreeze** are commands: freezing copies the upstream's source and query into the input; unfreezing an
  input whose grid is still on the page makes it live again.
- **A rename upstream** is followed downstream by the page's rebase (`datacube/src/page-rebase.ts`).
- **A box's output** is its rows at their finest level: `classLambda` (`datacube/src/query.ts:380`), what a join input
  already reads. A grouped grid outputs its groups, one row per group with its measures beside its keys: Alteryx's
  Summarize. A pivoted grid outputs its cast (FL2); `classLambda`'s refusal of a pivot is Create a Class's rule (a
  class's properties are fixed), not a link's.

### 3.2 One box, one tool

A grid applies its steps in a fixed order: calculated columns, then the filter, the grouping, group-level calculations,
the pivot and the sorts. Alteryx users expect one tool per box, in any order. Each tool box is therefore a grid that
does one thing (FL5): a filter after a summarize is two boxes. The canvas never shows the word "grid"; the results pane
is the grid. A grid stores only what differs from the defaults (`CubeDocument.configuration`), so a one-tool grid is a
small document.

Tools that are not yet a grid's feature (Union, Sample, Unique, rename, Transpose, the Pure box) each become a grid
feature or a source kind, one at a time, each as its own command kind (§3.5).

### 3.3 Ports

| Tool | Outputs | Each output's query |
|---|---|---|
| Filter | True, False | the filter; its negation |
| Join | L, J, R | left rows with no match; the join; right rows with no match (join.ts has both "no match" forms) |
| Unique | Unique, Duplicates | the first row of each key; the rest (a row number over the key) |
| Create Samples (later) | Estimation, Validation, Holdout | three random slices |

Inputs are anchors too, as in Alteryx: a Join's L and R are its two inputs; a Union takes any number. Each port's query
is worked out from its box: nothing is stored per port. A link that names no port reads the box's main output. Clicking
a port shows its rows in the results pane.

### 3.4 The flow sheet's own state

- **The screen:** the palette on the left, the canvas in the middle, the selected box's settings on the right (the
  editors DataCube has: filter, column, join, pivot), the results pane below.
- **A box's place on the canvas** is the sheet's layout, set by a page command (as a board sheet's `layout` is), so
  undo, save, share and Python treat it like any other edit. Auto-layout is a command too.
- **Annotations and containers** (Alteryx's Comment and Tool Container) are the sheet's state the same way.
- **The selected box** is the screen's, not the page's (as a selected cell is today).

### 3.5 New command kinds ship whole

Each new command kind (a live input, freeze and unfreeze, a box's place, a check's mode) ships with its apply rule, its
JSON reader and writer, its rebase arm, a UI producer, a Python builder and a parity row (One API rule 6). The ledger
L8 in `datacube/test/one-api-ledger.test.ts` enforces it: a kind the screen or Python does not make, or a source kind
Python cannot read and add, fails the build. Its lists (`L8_KINDS`, `L8_SOURCES`) are the gaps, so a kind shipped
whole adds nothing to them; an entry there is a gap left, which only the user's dated sign-off allows (D9).
*(Corrected 2026-10-25: this said every new kind raised `L8_KINDS`.)* A new source kind also joins `PYTHON_ADDS`,
the headless function by which Python adds a grid over it, with its test.

---

## 4. Alteryx's tools, one by one

How to read the status column:

- **Built**: DataCube does it on a grid today. The flow only draws it.
- **Step**: DataCube work only (a preset, a port, a command) over what the compiler already has. Where it names a Pure
  function, the function is in the platform's registry (`core/src/main/resources/com/legend/builtin/native-claims.tsv`)
  and DuckDB's dialect renders it; other databases are checked when the step is built, not assumed here.
- **Core**: needs a change in core first.
- **Later**: possible, not in the first slices.
- **Out**: refused, with the reason.

### In and out

| Tool | Status | How |
|---|---|---|
| Input Data | Built | A grid's sources: a file, a remote file, a warehouse table, a class, a saved query, a frame |
| Text Input | Built (2026-10-25) | A small table typed in or pasted from a sheet, written as a CSV file: an Input over it, as a file dropped on the canvas is. Pure's `#TDS` literal remains the way to keep one in a model |
| Browse | Built | The results pane: the selected box's or port's grid |
| Output Data (to a table) | Core, after v1 | `write` to a store table throws "not yet implemented" (`core/.../lowering/Lowerer.java:3295`): F1, built in core in parallel (FL13) |
| Output Data (to a file) | Built | The grid's exports, for the rows shown. The whole result streamed is A5 of the BI and ETL plan |
| Data Stream In / Out | Step | The Cache box (FL10), on Snap |
| Date Time Now | Built | `now()` and `today()` in a Formula |
| Directory | Out | It lists a file system, which is no relation a database holds |
| Dynamic Input | Later | A source chosen at run time; needs runs (F3) and parameters (F5) |

### Preparation

| Tool | Status | How |
|---|---|---|
| Filter (True, False) | Step | The grid's filter; the False port (§3.3) |
| Formula | Built | A calculated column, checked by the compiler as it is typed |
| Multi-Row Formula | Built | A window column: `lag`, `lead`, `first`, `last` |
| Multi-Field Formula | Step | One formula over several columns, made as one calculated column each |
| Select (choose, rename, retype) | Step | Choosing is the grid's shown columns. Renaming is a display name today, not the relation's `rename`; both as relation steps (§17.8). Retyping is `cast` |
| Sort | Built | The grid's sorts |
| Sample (first, last, every Nth, N per group) | Step | `limit`, `drop`, `slice`, and a row number for N per group |
| Random % Sample | Step | `random()` |
| Unique (Unique, Duplicates) | Step | `removeDuplicatesBy`; the Duplicates port (§3.3) |
| Record ID | Built | A window column: `rowNumber` |
| Tile | Built | A window column: `ntile` |
| Data Cleansing | Built (2026-10-25) | Calculated columns, a `<column>_clean` each: empties made blank (`coalesce`), punctuation out and repeated spaces one (`regexpReplace`), `trim`, `toUpper` / `toLower` (§17.6). In place, as Alteryx's, needs a relation `rename` |
| Imputation | Step | A preset: `coalesce` with the column's average, median or a value |
| Date Filter | Built | The filter, on a date column |
| Generate Rows | Later | Core has no relation that makes rows from a rule; needs a design |
| Auto Field | Out | Every column already has its type from the compiler; there is nothing to guess |
| Create Samples, Oversample, Multi-Field Binning | Later | `random()` and the bins preset are the parts |

### Join

| Tool | Status | How |
|---|---|---|
| Join (L, J, R) | Step | `join.ts`: inner, left, right, full, any condition, as of. The L and R ports are its "no match" forms (§3.3) |
| Join Multiple | Built, once linked | Joins chained by live inputs |
| Union | Built | A join's append |
| Append Fields | Step | Every row with every row: a join whose condition is always true |
| Find Replace | Step | A join, then `replace` |
| Fuzzy Match | Step | A join whose condition is `levenshteinDistance` or `jaroWinklerSimilarity` against a threshold |
| Make Group | Out for now | Groups made from pairs need a transitive closure, which one relation cannot say |

### Parse

| Tool | Status | How |
|---|---|---|
| Text To Columns (to columns) | Built (2026-10-25) | `splitPart`, one calculated column per part |
| Text To Columns (to rows) | Step | `split`, then Expand |
| RegEx | Built (2026-10-25) | `regexpLike` (Match), `regexpReplace` (Replace), `regexpExtract`'s first (Extract), one tool. Tokenize (each match a row) is Expand's, later |
| DateTime | Step | `parseDate` and `formatDate` in a Formula, offered as one tool |
| JSON Parse | Built | JSON fields (`datacube/src/ui/json-fields.ts`) and Expand |
| XML Parse | Out | No XML function in the platform |

### Transform

| Tool | Status | How |
|---|---|---|
| Summarize | Built | A grid's grouping and measures: sum, count, average, min, max, median, quartiles, standard deviation, variance, concatenate (`joinStrings`), weighted average, unique |
| Cross Tab | Step | The grid's pivot, typed by its cast, its values checked (FL2, FL3, FL8) |
| Transpose | Step | Composed: one `select` per column, concatenated (FL14) |
| Running Total | Built | A window column, running frame |
| Weighted Average | Built | `wavg` |
| Count Records | Built | `count` |
| Arrange, Make Columns | Later | Layout tools for reports; the pages do most of what they are for |

### Investigate and report

| Tool | Status | How |
|---|---|---|
| Field Summary, Basic Data Profile | Step | **Profile** (§17.6): per column, the count, empties, distinct values and top values |
| Frequency Table, Histogram, Scatterplot | Built | Charts on a box's grid: bars over bins, scatter, box plot |
| Pearson Correlation | Step | `corr` as an aggregate |
| Charts, Table, Layout, Render, Email | Built | Charts, KPIs, pages, exports and the email draft |

### Developer, apps, macros and the canvas

| Tool | Status | How |
|---|---|---|
| Comment, Tool Container | Step | The flow sheet's own state (§3.4) |
| Test | Later | Checks as boxes (not null, unique, in range, row count), compiled to SQL, each with its mode (FL8; track C5) |
| Block Until Done | Later | Run order, from runs and schedules (F3) |
| Message | Later | The run's log |
| Base 64 Encoder | Built | `encodeBase64` in a Formula |
| Dynamic Rename, Select, Replace | Later | |
| Interface tools (an analytic app's inputs) | Core | Query parameters: execute takes no parameter values yet (F5) |
| Standard macro | Later | A sub-flow saved as a Pure function with parameters, used as a box; needs a function with parameters as a source (§17.6, item 8) |
| Batch and iterative macros | Out for now | A loop is not one query; it would be orchestration |
| Python, R | Out in v1 | They compute outside the database (FL15) |
| Download, Run Command | Out | Not a database's work |

### Not ours

- **Spatial, predictive, time series, prescriptive, address and demographic tools:** a different product. A function
  the database offers (a spatial or statistical one) can be added as a native when asked for.
- **Connectors to SaaS applications:** out of scope, as the BI and ETL plan's §5 says.
- **The In-Database palette:** every tool here runs in the database, so there is no separate palette for it.

---

## 5. Roadmap: simple first, then built up (2026-10-25)

D19 landed on main (neema2/legend-lite#71), so FL16's wait is over and the canvas can start. The user's three rules
for all of it (2026-10-25):

1. **Reuse DataCube's logic and APIs**, so there is one set of rules, never a second.
2. **The API stays separate from the UI**: every gesture a command, applied by the one function, the same from Python.
3. **Close to Alteryx's experience, features and workflow**, but nicer, more modern and sleeker.

Every milestone ends usable: something a person does from start to finish, saved, shared, and the same from Python.

### M1. A first flow: an Input, five tools, Browse

- **What a person does:** adds a Flow sheet; drops a file onto it, or picks any DataCube source, as an Input; adds
  Filter, Formula, Select, Sort and Summarize after it; clicks any box to see its rows; saves, reopens and shares it.
- **The canvas:** boxes and lines. A tool is dragged from the palette onto the canvas, onto a box's output, or onto a
  line to insert it there. Boxes move, are deleted, and every step undoes and redoes.
- **Settings:** each box's in the pane on the right, made of DataCube's own editors: the filter editor (Filter), the
  column editor (Formula), the shown columns (Select), the sorts (Sort), the grouping and its measures (Summarize).
- **Results:** the selected box's rows below the canvas, as a full DataCube, live: there is no Run button. Only the
  shown box queries; every other box is a document, not a running grid (demand-driven work).
- **Types:** each box says its output columns as it is connected; a box the compiler refuses is red, in its words.
- **Underneath:** the `rows` source (a grid over another grid's rows, §3.1) with live inputs; the `flow` layout kind
  (FL4); a box with no running grid until it is shown.
- **Not yet:** joins, ports, writes.
- **Done when:** a browser harness builds Input, Filter, Formula and Summarize by drag and drop alone, previews each
  box, saves, reopens and shares the flow; Python builds the same flow and reads the same rows.

### M2. Combine: Join, Union and ports

- A tool dropped onto a line, inserted there (moved from M1: a link changed by a command, with Freeze and Unfreeze).

- Join with its L, J and R ports; Union of any number of inputs; Append Fields; Find Replace; Fuzzy Match.
- Ports (FL7): Filter's False, and Unique with its Duplicates.
- Live or frozen inputs, with Freeze and Unfreeze (FL6).
- **Nicer than Alteryx:** join keys suggested with their reasons, and what a join will do counted before it is
  applied (join.ts `suggestedKeys` and join-open.ts `summaryCounts`, both built).
- **Done when:** two inputs are joined, each port is browsed, and an edit upstream reaches the join and every box after
  it.

### M3. Prepare and parse

- Preparation: Sample, Random % Sample, Record ID, Tile, Multi-Row Formula, Running Total, Multi-Field Formula, Data
  Cleansing, Imputation, Date Filter.
- Parse: Text To Columns, RegEx, DateTime, JSON Parse.
- Most are presets of the calculated columns and window columns DataCube has (§4).
- **Done when:** each tool has a harness case over a fixture, its rows checked.

### M4. Reshape

- Cross Tab, with Load values, the cast, its check and the check's mode (FL2, FL3, FL8); Transpose, composed (FL14).
- The check modes on screen: amber and red boxes, Add to the cast.
- **Done when:** a Cross Tab whose data gains a value is marked in a preview and refused by a run in refuse mode.

### M5. Understand the data

- Profile on any box or port: the count, empties, distinct values and top values (§17.6).
- Record counts on lines after a run (FL7); results-pane gestures that add a box (FL9); a box's Pure and SQL shown;
  charts on any box.

### M6. Output and run

- **Output Data to a table:** create, replace, append and merge by key, on the store writes now on main (FL13's note).
- The Cache box (FL10); the grid's exports from any box.
- **Run:** the whole flow in order, with a run log; check modes enforced (FL8); a dry run (the plan and row counts,
  nothing written).
- **Done when:** a flow writes a table in one transaction, and a failed check writes nothing and says why.

### M7. Big flows

- Comments and containers that collapse, auto-layout, a minimap, search, a command palette, copy and paste of boxes,
  keyboard operation, accessible announcements.
- Import Pure and the Pure box (FL12).

### M8. Production (platform work)

- Schedules and run history (F3); parameters and analytic apps (F5); standard macros (a sub-flow as a function with
  parameters); Dynamic Input; FL15's Python box, if asked for.

### What makes it nicer than Alteryx, all the way through

- No Run to see data: every box previews live, computed in the database.
- Types and the compiler's errors live, as boxes are connected and settings typed.
- Every box is a full DataCube: pivot, chart or export at any step.
- Undo for everything, a share link for every flow, the flow as diffable Pure.
- The same flow from Python, by the same commands.
- One design language (`docs/DATACUBE_UI_REVIEW_2026_10_24.md`), dark mode, the keyboard first.

### M1's own steps, each a commit with its tests

1. **The `rows` source and live inputs in the cube document:** read and written; resolved against the page (a cycle
   refused, a missing grid said); its leaves; the order a page opens its grids in.
2. **Its base query** over the host's tables, on the tab and on Python's host (headless), and the L8 source rows.
3. **The `flow` layout kind** and its page commands: a box placed, moved and removed; every reader of a layout taught
   it.
4. **The canvas**, the palette, the settings pane and the results pane.
5. **The five tools' settings.**
6. **Python's methods**, by the names agreed (D6).
7. **The browser harness** and a tour step.

### M1's design, as the code shapes it (2026-10-25)

Read while building step 1, against main at `1e91768d`.

1. **A box is a document until it is shown.** M1 runs only the shown box. So the box before it is usually not an
   open grid, and a box's base cannot be taken from an open grid upstream. A box's base is worked out from its chain's
   first source down, one rule at every step: **an input's rows** (join-open.ts `inputRows`, the rule a join's input
   already followed, now shared) over the base of what that input reads. Python's host already works a base out this
   way, per source (headless host-tables.ts `onHost`, grid.ts `gridBase`), so there the rule is one more arm.
2. **The tab's openers split in two.** Each kind's opener in `datacube/src/app/sources.ts` today both opens a source and
   makes its grid. A box needs the first half alone: the base (its relation, its columns, the planner and plane it is
   read through) with no grid made. The opener becomes "open the base", then "make a grid over it"; every kind keeps
   its one opener.
3. **The page holds a box's document while it is not open.** The page today reads each grid's document off its open
   grid (page-app.ts `#seen`), and keeps the documents of the grids it could not open (`#withUnopened`). A box not
   shown is the second case by design: its document lives in the page's state and is edited by commands, and its grid
   is made when it is shown and let go when it is not.
4. **A link is resolved, never guessed.** Whatever asks what a grid reads (its leaves, its base) asks a source whose
   live inputs are resolved against the page first (live-inputs.ts `resolvedSource`); `leavesOf` refuses an unresolved
   one rather than answering for it. A page whose link names no grid of it, or goes round, is refused when read.
5. **The Join window's copy holds no link.** A grid joined is copied with its links resolved (page-commands.ts
   `joinInputOf`, now live-inputs.ts `gridInput`, the one rule for a grid as an input): a frozen input never changes.

**Built in step 1** (`datacube/src/cube-document.ts` `RowsSource`, `LiveInput`; `datacube/src/live-inputs.ts`): the
source written and read, refused by name when malformed; links resolved against the page, a cycle and a missing grid
refused in words; the order a page opens its grids in (`page-document.ts openingOrder`, now given the page's views);
the keeper's reads (`page-rebase.ts pageReads`); the save and share words. Tested by `datacube/test/live-inputs.test.ts`.

**Built in step 2, Python's host** (`datacube/src/headless/grid.ts`, `host-tables.ts`, `page.ts`; `python/legend_lite/page.py`):
a box's base worked out from its chain's first source (headless `baseOver`: a leaf's, a join's, or its input's rows over
its input's base, through what that base is read through); every base call given its page, so a link is resolved there
(`gridBase`, `gridBaseOn`, `gridOpened`, `cubeLeaves`, the keeper's `pageRebase`), and refused in words without it;
`rowsCube`, a box added after a grid, which Python's `page.grid(input=grid)` asks (FL20). The source kind is in the L8
ledger's reads and adds (`one-api-ledger.test.ts`, dated), with Python's test (`python/tests/test_page.py Flowed`: a
box's rows live, a box after a box and after a grouped one, refusals, saved and loaded, joined as a copy).

**Built in step 2, the tab** (`datacube/src/app/sources.ts`, `join-sources.ts`, `join-open.ts`): a box opened where its
page reads (`rowsWhere`) -- on a page Python serves, by Python, the page handed to it (`opened.json`'s `document` and
`id`); in the tab, over the tab's own tables as a join's are read (`openRows`: each file once, a table read for a grid
on the page taken, never asked for again), or, its chain over a source read elsewhere (a warehouse table, a class, a
saved query), over that first source opened as a grid over it opens, that grid never made (`openedAfterStart`, the
opener's start kept by its maker: point 2 above, without changing any opener). What a grid reads is kept as it opened
(`GridPlace.reads`). Checked in the running app: a page file with a box after its grid (notional over 1,000) opened
with 179,996 of the grid's 200,000 rows. The L12 host register lists the new branches (the `input` ability, its proofs
on both hosts). Not yet: an edit upstream re-reading the boxes after it in the tab (step 4, with the canvas).

**Built in step 3, the `flow` layout kind** (`datacube/src/layout/flow.ts`, `page-document.ts`, `page-commands.ts`,
`page-rebase.ts`, `headless/layout.ts`): a sheet's layout is a board's bands or a flow's boxes (`PageLayout`,
`layoutTiles`), read and written, its rules held (each box once, at whole places, neither below zero; a flow's tile a
grid). Two command kinds: `addFlow` (Create ▸ Flow) and `placeBox` (a box let go, its arrow keys). `addGrid` on a flow
puts the box after `near`, the box it reads (the next column on its row, else the first free row there: `placeAfter`).
What only a board takes is refused in words: a chart, a text tile, a stack, an arrangement, a band layout; `moveTile`
goes from board to board or flow to flow. A grid taken off leaves every grid that read it live reading a copy of what it
was (FL6, `withoutTile`). The rebase renames both kinds and keeps a flow told before a box came (`rearranged`). An
unnamed flow is "Flow n" (`sheetLabel`). Tested by `datacube/test/flow-sheet.test.ts`.

**Built in step 4, the canvas, the palette and the results pane** (`datacube/src/layout/flow-board.ts`, `flow-canvas.ts`,
`flow-boxes.ts`, `page/cube-page.ts`, `page/page-app.ts`, `app/datacube-app.ts`): a flow sheet's board is a `FlowBoard`,
its drawing fetched with the first flow (a page with no flow downloads none of it): the palette (Input, Filter, Formula,
Select, Sort, Summarize, by Alteryx's groups and colours), the canvas (boxes on a dot grid, a curved line into each box
from each box it reads), and the results pane below, the shown box's grid, a full DataCube. A box says what it does from
its document alone (`boxSays`: its tool, the steps it takes besides, what it is set to). **A box is a document until it
is shown** (point 3 above): a page opens none of a flow's boxes (`page-document.ts boxCubes`: the tab's Open…, the
keeper's page converged) and writes each box's cube as the page has it; a click (or Enter) shows one, its grid made by
the host over the page as it is now (`PageAppOptions.openBox`: `sources.reopened` with the page), the box shown before
closed with what its grid did kept (`CubePage #closeBox`). A box whose source a step changes while it is shown (a box it
read taken off) is shown again over it. A tool clicked adds a box after the box shown, dropped on a box after that box
(`onFlowTool`: an Input, a grid over a source picked now; any other tool, a grid over that box's rows, read live, over the
tables its grid reads). Moves, removals and renames are page commands, each a step of the page's history; which box is
shown is the screen's. Python: `page.flow()`, `sheet.grid(...)`, `sheet.flow`, `box.place(x, y)` (FL20), tested by
`python/tests/test_page.py Flows`. Tested by `datacube/test/flow-page.test.ts` (real grids on a page of its own), and
checked in the running app: Create ▸ Flow, an Input of the examples, a Filter after it, each box shown, a box dragged.

**Built in step 5, the five tools' settings** (`datacube/src/flow-boxes.ts` `TOOL_EDITORS`, `app.ts` `dock`,
`openStep`, `layout/flow-canvas.ts`): the settings pane beside the canvas (Alteryx's Configuration window) holds the
window of the shown box's grid that sets what it does, docked rather than floating (`CubeAppOptions.dock`: Properties,
the Filter window, a calculated column's editor, one at a time) -- a Filter's filter, a Formula's new calculated column,
a Select's shown columns (Properties ▸ Columns), a Sort's sorts (Properties ▸ Sorts), a Summarize's row groups
(Properties ▸ Row Groups). A tool added opens its window; a box clicked opens its own. With none docked, the pane says
what the box does and offers to set it. A box added as a tool is drawn as that tool, "not set yet", until its grid does
something (the screen's, never the page's). Found on the way: a grid's undo key JSON-stringified its snapshot, and a
grid over another grid's rows (or a join) carries its input's literals as the protocol's exact numbers, so its first
edit threw; the keys are written exactly now (`cube-state.ts`, `toJson`). Checked in the running app, both themes:
Input, Filter (notional over 500,000), Summarize by region after it, each box shown again with its settings.

**Built in step 7, the browser harness and a tour step** (`datacube/demo/verify-flow.mjs`, `//datacube:verify_flow_test`;
`demo/tour.mjs` step 17): Create ▸ Flow; an Input dragged onto the canvas (the examples' trades, 5,000 rows), a Filter
dragged onto it (notional over 500,000 set in the docked Filter window: 2,404 rows), a Formula onto the Filter (`fee`,
in the docked column editor: the same rows, the column in them), a Summarize onto the Formula (region, in Properties'
Row Groups: a row per region); each box shown again, its rows as before, only the shown box's grid made; a box moved by
hand and the move undone; the flow saved, the tab reloaded and the flow opened again from Open… (each box as it was,
none opened until shown, the last box's rows as before); and shared, the link opening the same flow in a fresh tab.
With the Python tests of step 3 (`test_page.py Flows`, `Flowed`), M1's "done when" holds. Found on the way: the column
editor's sections shrank below their content in a short window, the preview's heading over its own builder's buttons
(`app.css` `.dc-xc-main > *`).

**Built after step 7, the rest of M1's list** (`layout/flow-canvas.ts`, `flow-board.ts`, `app/datacube-app.ts`): a file
dropped from the desktop onto the canvas is an Input over it, opened as the picker opens a file it is given; a box
whose source was not opened the last time it was clicked is drawn red, why said on it and in the settings pane, until
it is shown; each box says the columns it hands on (on its tooltip, and in the settings pane), read from its document,
no query run for it. Tested by `flow-page.test.ts` and the harness (a file dropped, an Input over it, its rows).

**Moved to M2, and built there:** a tool dropped onto a line, inserted there (M2 step 2, below).

**Not yet, said:** a chart or a text box of a box's rows (refused, said: they go on a board sheet); a tool added from
the tab on a page Python serves (refused in words: Python adds one, `flow.grid(input=box)`); a grid on a board reading a
box that is shown re-read when that box is edited.

### M2's own steps (2026-10-25)

**Built in step 1, a box's input set** (`datacube/src/page-commands.ts` `setInput`, `cube-document.ts` `inputOn`,
`withInputOn`, `inputSides`, `readCubeInput`, `live-inputs.ts` `relinkedInput`, `page-between.ts` 2c, `page-rebase.ts`):
one page command sets what a grid reads on a side -- another grid's rows' one `input`, a join's `left` or `right` -- to
a grid of the page read live, a copy of what a grid was, or, `input: null`, a copy of what it reads live now (Freeze).
Its query is kept; what its input answers now is read when it opens again. Refused in words: a source with no such side,
the input it reads already, a box frozen twice, a snapped grid, and a link that would read its own rows (the page
reader's rule, live-inputs.ts `liveProblem`). A join's inputs may be live now (`JoinSource.left`, `right`: FL6's "new
joins are live"), resolved against the page wherever they are read (`frozenInput`, the tab's `openedJoin` and the
host's `gridOpened` given the page), the source keeping its links. The page's history covers a flow's lines: the
commands between two pages set each input that differs, so **an undo of a box's removal links the boxes it froze again**
(a swap of two boxes undone freezes each first where setting it at once would read its own rows). Found on the way: a
flow sheet's removal undone made a board (page-between.ts made every sheet by `addSheet`), its boxes' layout then
refused; it makes a flow now. Python: `grid.relink(box)` (a join's `side='left'`/`'right'`), `grid.freeze()`, and
`grid.input` / `grid.inputs`, the grids it reads live (`python/tests/test_page.py Flows`). Tested by
`datacube/test/flow-sheet.test.ts` and `page-commands.test.ts`.

**Built in step 2, a flow's lines** (`layout/flow-canvas.ts`, `flow-board.ts`, `flow-boxes.ts` `boxInputs`, `readersOf`;
`page/cube-page.ts`, `app/datacube-app.ts`): a line drawn by hand from a box's output port onto another box links it
(`Link Box`; a join's left or right by the half it is let go on, its L and R ports above and below); a line clicked is
selected, its × at its middle, and Delete (or the ×) takes it off -- the box it went into then reads a copy of what it
read, drawn frozen (a dashed input, ❄ before what it says; `Freeze Box`); a tool dropped onto a line goes between its two
boxes (`Insert Box`: the box added and the box after it reading it, one step, one undo). The settings pane says what the
shown box reads on each side -- a box of the flow (none that reads it), or the frozen copy, named as the box it copied
-- and sets it there too, the keyboard's way to every line. Tested by the gestures ledger (`a box frozen`, `a box linked
to another`), `flow-page.test.ts` and the harness: a line drawn and undone, a line taken off and linked again from the
settings, a Sort dropped on a line and the insert undone in one step (`//datacube:verify_flow_test`).

**Built in step 3, Join and Union boxes** (`app/join-flow.ts` `flowJoin`, `app/join-sources.ts` `openedInputsOf`,
`ui/join-dialog.ts`, `page-commands.ts` `setJoin`, `flow-boxes.ts`, `headless/join.ts`): Join and Union in the palette.
Dropped on a box (or clicked with one shown), the Join window opens over it -- its Left -- and the flow's other boxes,
one chosen as its Right, each read as it is now (its leaves' tables found or read in as a saved join's are: the boxes'
grids are not open); applied ("Add Join", "Add Union": a Union's window starts at Append), a box after the Left that
reads both live, drawn with its L and R ports and a line into each, saying its join in words (`join-how.ts joinWords`,
moved there from join.ts so a box says it without the join's chunk). An edit upstream reaches it: the Filter's
threshold raised, the Join's rows follow. Set again from its settings ("Open its settings": the window at its own join,
its condition, match, prefix and columns kept; "Apply"): one step, `setJoin` with `setInput` when its Right changed,
undone by one undo (page-between.ts 2c sets a join's how too). Python: `page.join(...)` of two boxes of a flow is live
(`live=None`: live on a flow, a copy on a board, as before), `grid.rejoin(how)`. **A box passes on the columns its
input gains** (`cube-document.ts reopened`): a grid reading another live shows a new column of its input rather than
hiding it as a saved grid over a changed file does -- found when a Join set from "with a match" back to "matching rows
only" hid their columns (Alteryx's *Unknown passes them by default; a Select hides by name). Tested by the harness (a
Join of the Filter and the Input on trade_id, its rows, an edit upstream, set again and undone, a Union of both),
`flow-sheet.test.ts`, `page-commands.test.ts` and Python's `Flows`.

### M3's own steps (2026-10-25)

**Built, the window tools and Cross Tab** (`flow-boxes.ts`, `ui/column-editor.ts` `ColumnEditorStart.window`, `app.ts`
`openStep`): Record ID, Multi-Row, Tile and Running Total are boxes whose one column is a window column, each started as
its tool's in the column editor docked in the settings pane (`rowNumber` named RecordID, `lag` by one named Previous,
`ntile` of four named Tile, a running `sum` named RunningTotal) -- the person picks its order and its column there, the
editor's own window form. A box says the tool that made its column (`boxSays`: a window column by its function; the
columns of one tool together, in their order). Cross Tab (M4's, here because it is the grid's own column pivot) opens
Properties at its Column Labels; a box with a column pivot says "desk across, by region". The palette is grouped under
headings (In, Prepare, Join, Transform). Tested by `flow-sheet.test.ts` and the harness (a Record ID ordered by trade_id,
its column in its rows; a Cross Tab's Properties docked at Column Labels).

**Built, Sample** (`ui/sample-form.ts`, `app.ts` `#openSample`): a box's first or last rows in the order of a column --
the grid's own Top N (`filter.topN`), said as a sample is ("Keep the first 5 rows, in the order of trade_id"; ties kept,
as the Top N keeps them), in a window of its own docked in the settings pane, fetched with the first. A box with a Top N
says "first 5 by trade_id". Tested by the harness (its five rows).

**Built, DateTime** (`ui/date-form.ts`, `app.ts` `#openDate`, `calc-presets.ts` `grainOf`): a date column taken to its
day, week, month, quarter or year -- the grid's own By Day/Week/... preset (`grid-presets.ts`, the one rule Python's
`grid.grain` asks), in a window of its own ("Date Period") docked in the settings pane, fetched with the first. A box
says what it takes to what ("trade_date by month"), read off its column's expression by its shape -- the preset's, or
the same expression typed into a Formula -- never by its name. Tested by `flow-sheet.test.ts` and the harness (its
column in its rows).

**Built, the text tools** (`text-tools.ts`, `ui/text-tool-form.ts`, `app.ts` `#openTextTool`): Text To Columns (a text
column split on a delimiter into its first N parts, `<name>_1`..`<name>_N`), RegEx (a pattern over a text column: Match,
a true/false column; Replace, each match replaced; Extract, its first match -- `first`, so a value with no match is
empty: `toOne` made DuckDB refuse the "FX" desk that has none, found by the harness) and Data Cleansing (text columns
tidied, a `<column>_clean` each: empties blank, punctuation out, repeated spaces one, the ends trimmed, the case
changed). Each is the calculated columns DataCube has, added in one step as the column editor's Add adds one, in a
window docked in the settings pane; Parse is a palette group of its own. A box says what it does from its columns'
expressions by their shape (`textToolOf`: "book split on ' '", "/^[A-Z][a-z]+/ of desk", "region cleansed") -- the same
expression typed into a Formula draws the same. Python asks the same rule (`text-tool-columns.ts` `textToolCommands`,
through headless `gridPreset`): `grid.split(column, delimiter, parts)`, `grid.regex(column, pattern, mode)`,
`grid.cleanse(columns, ...)`. A part a value has not got, and a pattern it does not match, are empty (`splitPart`'s
`String[0..1]`, `first` of the matches). Tested by `flow-sheet.test.ts` (the expressions, the words, the refusals),
Python's `test_text_tools_as_a_flow_box_adds_them` (the values DuckDB computes) and the harness (each tool set in its
window, its columns planned and run on DuckDB). Still to come in M3:
Unique, Imputation, the DateTime tool's parse and format.

### M7's first steps (2026-10-25)

**Built, the canvas's own tools** (`layout/flow.ts` `arranged`, `page-gestures.ts` `tidyCommands` and the `flow.tidy`
gesture, `layout/flow-canvas.ts`, `page/cube-page.ts`, `page/page-app.ts`): **Tidy Up**, at the canvas's corner, places
every box a column after the furthest box it reads and, down a column, level with the boxes it reads, else in the order
it was down the canvas -- one step, nothing when it is tidy already; Python's `flow.tidy()` asks the same rule (headless
`pageGesture`). **Duplicate** (Ctrl+D on a box, or its menu): a copy of its cube, reading what it reads, after the box it
reads first -- a document until shown. **A box's menu** (a right-click): Show its rows, Open its settings, Rename,
Duplicate, Freeze each live input, Remove -- each the same as its gesture elsewhere. **The palette's find field**: its
tools narrowed to the ones whose name or words hold what is typed, Enter adding the first after the box shown. Tested
by `flow-sheet.test.ts` (the rule), Python's `Flows` (`flow.tidy`) and the harness (a box pushed out of place and tidied,
the Formula duplicated from its menu, the palette narrowed to Join).

**Built, comments** (`page-document.ts`, `page-commands.ts` `addText`, `page-gestures.ts` `tidyCommands`,
`layout/flow-board.ts` `addNote`, `layout/flow-canvas.ts` `#makeNote`, `page/cube-page.ts` `#addComment`): a text tile on
a flow is a comment -- Alteryx's Comment tool, under Notes in the palette: dropped on the canvas it lands where it is let
go, its editor open there (the board's own text editor: Markdown, OK or Ctrl+Enter); drawn as a note card, moved by its
head, its words edited by a double-click, taken off by its × or Delete. Tidy Up and Run leave comments as they are. M1's
rule that a flow holds grids alone is a flow of grids and comments now (a chart still goes on a board). Python:
`flow.text(...)` adds one, `note.place(x, y)` moves it. Tested by `flow-sheet.test.ts`, Python's `Flows` and the
harness (a comment dropped on the canvas, its words typed, a text tile of the page).

**Built, zoom and a palette that fits** (`layout/flow-canvas.ts`): −, the level (back to its own size), + and Fit at the
canvas's corner, and Ctrl+wheel around the pointer -- the screen's, never the page's; a box dragged at a zoom moves by
the pointer's way across the window in the canvas's own units (110 pixels at 110%, 100 of the canvas). The palette lays
its tools two by two under their headings, so the whole of it fits a window's height. Tested by the harness.

**Built, Undo and Redo, copy and paste** (`layout/flow-canvas.ts` `#paintHistory`, `#copied`, `page/cube-page.ts`
`#copyBox`, `#pasteBox`): the page's own Undo and Redo on the canvas's toolbar, each titled with the step it would undo or
redo ("Undo Paste Box") and off while there is none -- the page's history, the same as Ctrl+Z's. Ctrl+C on a box keeps
its cube (reading what it reads), on a note its words; Ctrl+X cuts it (its removal, its own step, its readers frozen as
any removal freezes them); Ctrl+V on the canvas pastes it where the pointer is, else after the box it reads -- one step,
Paste Box (Paste Comment), on any flow of the page. A copy is the page's alone: a box reads grids of its own page, so a
paste on another page would read nothing (a page's whole flow carried between pages is M7's "flows from other pages").
Tested by the harness (a Formula copied and pasted at the pointer, reading what it reads; undone and redone by the
buttons).

**Built, Text Input** (`ui/text-input-dialog.ts`, `app/datacube-app.ts` `flowTool`): Alteryx's Text Input, under In in
the palette -- dropped on the canvas, a window of a small table: its columns named in its head row, a value a cell, Enter
down a row (a new one at the last), + for a column and a row, a column's × and a row's number to take them off; a block
of cells pasted from a sheet (tabs between values, a line a row) fills the table from the cell it is pasted in, growing
it to hold the block. Add Input writes it as a CSV file (`tableCsv`: a value quoted only where it must be) and adds an
Input over that file where the tool was let go -- one step, Add Text Input -- the same as a file dropped on the canvas,
so its types are the database's reading of its values and the page saves and shares it as it does any file. Tested by
`flow-sheet.test.ts` (its CSV, its refusals, a pasted block's cells) and the harness (a block pasted into its head row,
an Input of three rows). Still to come: setting it again from the box's settings (today a new Text Input replaces it).

### M5 and M6, first steps (2026-10-25)

**Built, record counts and Run** (`layout/flow-board.ts` `counted`, `count`, `layout/flow-canvas.ts`, `page/cube-page.ts`
`#runFlow`): a box's rows are counted as its grid's view lands (a flat grid's all of them, its total when the row cap
cut it; a grouped one's groups) and drawn beside its output, under the lines out of it, as Alteryx counts a connection
(FL7: after a run, never kept live) -- kept only while the box's chain is as it was then (its source and query, each
live input the chain of the box it reads): an edit up the chain drops the counts after it until they run again. **Run**,
at the canvas's corner beside Tidy Up, shows each box in turn, left to right, each counted, then the box shown before
shown again ("Running 3 of 9…" meanwhile). The screen's: no step of the page's. Tested by `flow-page.test.ts` (a count
dropped by an edit up its chain) and the harness (Run: each box counted -- the Input's 5,000, the Filter's, the Union's
both). **Its log** (`flow-board.ts` `RunLog`): when Run is done, a panel at the canvas's corner says how many boxes it
ran and how long it took, then each box -- its rows, its time, or why it was refused, red -- each a button that shows
it; closed by its ×, until the next run. Tested by the harness (a line a box). Still to come: Run on a page Python
serves counting by Python's engine without showing each box.

**Built, a Filter's False** (FL7; `cube-document.ts` `LiveInput.port`, `live-inputs.ts` `gridInput`'s port,
`layout/flow-canvas.ts`): a live input may name a box's port -- `{grid, port: 'false'}`, its False: the same query with
its filter turned round, the rows of its input its condition does not hold for (a row its condition is unknown for, a
null compared, is in neither, as SQL's NOT says). Resolved, frozen (a removal's copy of the port, not the rows), relinked
and diffed by the same rules as any input; refused in words for a box with no filter. A Filter box draws its True above
its False (T and F, as Alteryx's anchors); a line drawn from F links the box let go on to the False, and the settings'
Input offers each Filter's True and False. **A line let go on the canvas, away from every box, makes a Browse of the
rows it came out with, where it was let go** -- and a tool dropped on the canvas lands where it is let go too (a
`placeBox` in the same step). Python: `grid.relink(box, port='false')`. Tested by `flow-sheet.test.ts`, `live-inputs`,
Python's `Flows` and the harness (the Filter's F drawn onto the canvas: a Browse of the 4,497 rows it leaves out, made
where it was let go).

**Still open in M2:** ports with several outputs besides a Filter's (FL7: a Unique's Duplicates), a Union of more than
two inputs, Append Fields, Find Replace and Fuzzy Match; and the board's own Join… making live joins too (FL6's "new
joins are live": the flow's are; the board's still copies, as Python's `page.join` of two board grids).


---

## 6. Still open

1. **Whether legend-pure has an `unpivot`.** Nothing in this repository shows one, and no legend-pure checkout was in
   the session that wrote this. If it has one, Transpose uses it instead of being composed (FL14).
2. **Python's method names** for flows, put to the user before they are built (D6), at M1's step 6.
3. **An Output box in the tab:** the tab plans with its WebAssembly planner and runs on its own DuckDB; whether its
   runner takes a write plan's `Update` and `Sequence` nodes is to be read before M6.

---

## 7. The options not taken (2026-10-24)

Kept so a later reader knows why. Sizes: S days, M a week, L a few weeks, XL a month or more.

| Decision | Not taken | Why not |
|---|---|---|
| FL4 | A whole-page mode, like Ad Hoc | Dashboard grids and prep boxes in one picture; one-tool grids as tiles on dashboard sheets |
| FL4 | A separate flow document | A second open, save and share path and a second Python surface, against FL1 (L) |
| FL5 | Step records, the grid only previewing | A second model of a query beside the grid's: the duplicates the One API program removed (L-XL, and upkeep) |
| FL5 | Grids that absorb neighbouring tools | Boxes merge and split without being asked |
| FL6 | Live inputs everywhere, joins included | Saved joins change shape, and older documents are not read (the user, 2026-10-17) |
| FL6 | Live in flows, copies for the grid's Join… | Two kinds of input people must tell apart, with no choice between them |
| FL7 | One output per box | Unfamiliar to Alteryx users; settings repeated |
| FL7 | Counts kept live | One count query per line per refresh |
| FL8 | One mode per flow | Cannot be strict on one check and lenient on another |
| FL8 | Warn by default | A table can be written short, said only in a log |
| FL8 | Follow mode now | The SQL that runs differs from the plan reviewed; two queries a run (M); later |
| FL9 | A gesture edits the selected box | A box stops doing one thing |
| FL10 | Refusal alone | Alteryx users expect a way through |
| FL10 | An automatic copy of the smaller side | Data moved without anyone seeing it (M-L) |
| FL10 | DuckDB attaching the other database | DuckDB doing the other database's work; credentials (L) |
| FL11 | The cache saved with the page, or in the warehouse | Stale data and governed rows in a browser (M); the warehouse needs F2's grants (L) |
| FL12 | Pure read-only, no import | No escape hatch for what the palette cannot say |
| FL12 | Two-way Pure now | Any Pure turned back into boxes, layout kept across edits (XL); later, if wanted |
| FL13 | F1 before the first version | Holds the first version on core work (L) |
| FL13 | Files only, through DuckDB's `COPY` | No tables |
| FL14 | A new `unpivot` native | Not shown in Pure's relation API: a platform invention, a `SEMANTICS_REGISTER.md` row, against signatures verified from legend-pure |
| FL15 | A Python box now | A named exception to the tenet; only on pages Python serves; nothing pushes down through it |
| FL16 | Wait for D19 entirely | Slower, with nothing gained for the pure-function parts |
| FL16 | Everything now | The canvas built on the races D19 fixes |
