# DataCube command API: the homework (2026-10-17)

**Status:** the input to the next program. The user, 2026-10-17: "fix the stuff, get this PR out and then do the full
reshape with commands and everything else". PR neema2/legend-lite#47 lands first, with §6's fixes; the command API is
its own program after it (§9).

Read-only surveys of branch `claude/epic-rubin-ay7upl` at baf0bfd: every action on one grid, every page-level action,
every public Python method, and the state model, run in parallel. Items marked **checked** were confirmed in the code
by hand; the rest are the surveys' findings, each with file:line references in the session's record, not yet
re-checked.

## 1. The question and the answer

**The question:** can DataCube have one typed command API, `apply(page, command) → page | refusal`, where every screen
action builds a command and Python builds the same commands?

**The answer: yes, it's feasible, and nothing in PR neema2/legend-lite#47 stands in its way.** The command API is a real program, about
20 files and 5–8k changed lines, plus tests. It splits cleanly into stages.

## 2. The numbers

| Area | State-changing actions | Already in a DOM-free function | Logic inline in a handler (must be extracted) | Hard (I/O, live values, a live object) | Other |
|---|---|---|---|---|---|
| One grid | 119 | 65 | 46 | 3 (Snap/Live, Ad Hoc mode, sign-in) | 5 (undo, redo, reload; 2 view-only) |
| Page | 48 (of 62 listed) | 18 (8 already reached by Python) | 14 | 16 | 14 view/derived/read |

59 of the 119 grid actions only edit an editor's draft (Properties, Filters, Column, Settings). They reach the cube
through just 4 commits: Properties Apply, Filters Apply, Column OK, and Column Delete.

## 3. What already fits

- **The grid's undo already records states, not closures.**
  - The state owner (state-owner.ts) already runs a pure update, then an async check (compile and run), then commit or
    refuse.
  - `s => apply(s, cmd)` drops straight in.
  - A rule's refusal becomes a return value. That replaces today's try/catch-and-capture in `#edit`.
- **`grid-edits.ts` is the start of `apply`'s grid half**, and headless `gridEdit` is already a batch command.
- **Python already holds the page as exactly one document** (`Page._doc`), which is the shape `apply` wants.
- **The document formats don't need to change.** Today's measures-list migration correctly lives in the open step
  (openCube), not in an edit.

## 4. What's big, ranked

1. **The screen has no single page state.**
   - The page is spread across:
     - PageApp: the name;
     - CubePage: sheets, charts, ids and titles;
     - each sheet's BandBoard: the layout and tile titles;
     - each ChartPanel: the chart spec;
     - the host closure in datacube-app.ts: the saved identity and the model.
   - The page document is only assembled at save time.
   - A page-level `apply` needs one page state that these render from. This is the largest single step.
2. **60 actions have their rules inline in handlers** (46 on a grid, 14 on the page) and must move into DOM-free
   functions.
3. **Undo is three separate histories:**
   - each grid's;
   - each sheet's layout (50 steps);
   - nothing for charts, sheets, tiles, titles or page structure.

   Actions that cross scopes change a grid and the page without being atomic: a chart click that filters its grid,
   removing a grid, Open in Grid's Update.
4. **Engine facts live in committed state.** Compiler types land in the snapshot, and the edit rules read them. That's
   workable as long as types count as part of the state.
5. **Duplicated representations `apply` must keep in step:**
   - configuration vs its copy folded into the query (the query wins on open);
   - `showTotals` vs `showRootAggregation`;
   - a chart's selection vs its grid's filter;
   - a live chart's saved spec vs its grid.
6. **Ids and placement are allocated differently.**
   - Ids: Python's `_next` vs the screen's counters.
   - Placement: the screen sizes from the viewport and uses the shown sheet; Python uses 4 columns and the first sheet.
   - Commands must carry ids and the sheet explicitly.

## 5. What stays outside `apply`

These are effects that produce a value which a command then applies:
- opening sources and uploads;
- Save, Open and share;
- model actions (Create a Class, association, data space), which write to SDLC;
- Snap/Live, sign-in, Ad Hoc mode;
- auto-size (widths measured on screen);
- chart click and brush (the command carries the resulting conditions).

Undo and redo are operations around `apply`, not inside it. View state (selection, scroll, the shown sheet, the
layout lock, maximise) is not a command.

## 6. Does PR neema2/legend-lite#47 lock anything in?

**Mostly no.**
- The headless entry points and their JSON are internal (Python's public methods wrap them), so swapping them for
  one `apply` later is invisible to Python users.
- The document formats are unchanged.
- Python's public API changes very little against `main`:
  - **added:** `Grid.column()` and `Page.grid(dimensions=)`;
  - **changed meaning:** `Grid.measure()` (now a column's Aggregation, or a calculated column) and `Grid.sort()` (now
    replaces that column's sort).
- Most public methods map one-to-one onto commands.

**Before merge, fix what #47 itself introduced or made worse:**

| # | Problem | Why it's #47's | Fix |
|---|---|---|---|
| 1 | The Query app didn't type-check (`measures: []`) | baf0bfd | **Done:** cbcd4d6 |
| 2 | `gridEdit` drops open groups on a regroup; the screen keeps them (**checked**) | gridEdit is new; the rule came from round 2 | **Done:** da1f546, kept as the screen keeps them |
| 3 | `Grid.column(aggregate=None)` can't clear an Aggregation or weight; the screen can | `column()` is new | **Done:** da1f546, a "keep" default, as `chart()` uses, so None clears |
| 4 | `Grid.configure` is a raw dictionary write (**checked**). After any `gridEdit`, which now writes the folded query, a `configure({'maxRows': …})` is overridden on open, and `columns` is replaced wholesale | configure is older, but #47 made the loss likely | **Done:** da1f546, through gridEdit's `configuration` part, by `config.ts withConfiguration` (moved out of app.ts `applyConfiguration`, which calls it), then folded |

**Known differences to record as follow-ups, not fix in #47:**
- `gridEdit` only compiles. The screen runs the change, so a pivot past 500 values is refused on screen but accepted
  by Python until its view runs (**checked**).
- A live chart's saved spec goes stale after a Python grid edit until a chart method touches it. The screen
  re-follows on every view (**checked**). A page-level `apply` fixes this properly.

## 7. Screen bugs found along the way (already on main; not #47's)

- Unticking **Exclude from horizontal pivot** in Column Properties does nothing: it writes `undefined`, and the query
  keeps the exclusion. The menu's Include works. (**checked**, panel-column.ts:312)
- **View ▸ Dimensions** skips the grouping rules (app.ts:2997 calls `useDimension` through `#query`, not `setGrouping`).
  (**checked**)
- The pivoted measure's parent checkbox makes N changes and N undo steps for one click (columns-panel.ts:368-372).
- Heatmap default colours are written twice (app.ts:2178, panel-column.ts:538).
- Filter ▸ Add Filter has no compile check before it runs; the Filters window has one.
- The "a column is on one axis only" rule lives only in pivot-panel.ts, so menu and Python edits can put a column on
  both axes.
- Removing a grid clears a frozen chart's chip, but the detached copy of the grid keeps those conditions in its filter.
- The save dialog's blank-name rule differs from `givenName`.
- A header's click refuses a sort of a pivot total, while the header menu sorts by it.
- `setSorts` takes a pivot's header while pivoted, as the screen offers it; a header a pivoted level does not produce
  is left out of that level's query without a word (round 2's Python refused it; the screen never did).

## 8. Python still holds copies of rules (the twins program's leftovers)

These remain because the screen's side of each rule is still inline in a DOM class, so there is no shared function to
call yet. Extracting them is the command program's first stage.

- **Id allocation and titles:** `_next` vs the screen's counters; "Chart N" titles; Page.sheet()'s id.
- **Page.remove's cascade.** It also misses the screen's two selection cleanups, and refuses to remove a page's last
  grid, which the screen allows.
- **Moving tiles:** Sheet.add and Tile.move_to (a same-sheet move goes to the bottom; the screen does nothing).
- **Placement:** always a bottom band with 4 columns; the screen goes beside the asking grid, sized by the viewport.
- **Page.name** writes each cube's name itself; the screen does it when it writes the document.
- **Copies of DataCube functions:**
  - `_tiles` / `_leaves` copy bands.ts's tiles()/leaves();
  - `_keep` re-implements keepTable/keepClass;
  - `create_data_space` matches class paths by their last segment, while the screen builds the full path.
- **Grid.calculate** calls `nameProblem` without the pivot's column names, so a clash with a pivot header passes.
- **Grid.chart** refuses a chart that has problems; the screen keeps it and shows the words.
- **Page(name)** defaults to 'Untitled page'; the screen keeps a new page unnamed.

## 9. A staged plan

**Stage 0, in neema2/legend-lite#47 before merge (small):**
- items 2–4 of §6 (done: da1f546);
- the PR description rewritten plainly: what the API is, the one behaviour change (a grid's measures list gone, saved
  ones converted on open) at the top, and the leftovers of §8 named as such, not "done".

Then review, the full local set, CI, merge.

**Stage 1, the command type and a pure page `apply` (about 6–10 files):**
- A `Command` union and `apply(PageState, Command)`.
- Its grid half is grid-edits.ts plus the extracted handler rules; its page half takes over §8's rules.
- Headless gets one `apply` entry; Python's methods become wrappers that only build commands.
- The screen builds the same command values and applies them onto today's holders.
- Each step's test: the screen's action and Python's call produce the identical command.

**Stage 2, one page state on the screen (large):**
- A page state owner that CubePage, BandBoard, ChartPanel and PageApp render from.
- Page-level undo, with combined entries for actions that change a grid and the page together.

**Stage 3, optional:** commands as the live channel between Python and an open page, instead of re-serving the whole
document and rebuilding every grid.

## 10. Cost of the Python bridge, for the record

A typical sequence makes about 10–12 synchronous calls:
`Page()`, `page.grid(rows=, measures=, filter=, sort=)`, `.group()`, `.sort()`, `.chart()`, `view()`, `save()`.

Each grid call:
- reopens the grid from the document;
- retypes it;
- re-reads every live frame;
- compiles.

A shown page re-serves the whole document on every change. Batching commands into one call (open, type and compile
once) is the natural fix, and gridEdit already works that way.
