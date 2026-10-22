# One API, for real: the plan (2026-10-21)

**Goal (the user's tenet).** DataCube's logic is TypeScript APIs. The TS UI and headless Python call exactly the same
ones. Every change to a saved page, made in the browser or in Python, is a command, applied by one function. Python
keeps no DataCube rule of its own. Both sides can do the same things to a page.

**Evidence.** docs/DATACUBE_ONE_COMMAND_API_AUDIT_2026_10_21.md (four read-only audits checked against the code). Every
item below has its `file:line` there.

**Decisions** (the user, 2026-10-21):
- **D1** Open…, open in place and New ▸ Blank Page on a page Python serves open a new page and leave the served one
  alone.
- **D2** Empty pages are allowed on both sides.
- **D3** Every kind of source on both sides: Python reads, edits, saves and adds file, remote file, warehouse,
  saved-query and class grids, as the tab does.
- **D4** Python asks the TypeScript `model-changes/` library headless; `model.py` goes.

**Decisions** (the user, 2026-10-22):
- **D5** A model action (Create a Class, a Data Space, an Association) is not undoable: what it writes into its project
  cannot be undone, so the page's record of it (`modelInto`, `modelKeep`, `modelPin`) is applied by the one function in
  the browser as it is in Python, but is no undo step. The browser's half is done with S5's Z4-Z7, where the model
  actions move out of their dialogs. Registering S1's audit finding 10 as an item waits on the user's dated sign-off (it
  grows a ledger).
- **D6** S6 builds what a Python user would do; anything else is a named exception, with its reason. Every Python
  ability calls the same TypeScript function the browser's gesture calls; where the browser's logic still sits in screen
  code, it moves out into that function first.
- **D7** One **Show Grid** on a chart replaces Open in grid and its Update: a chart following its grid -- that grid
  shown, the page unchanged; a frozen or detached chart -- one step, undoable: a grid of the chart's own grouping and
  query added, the chart following it, shown. The chart looks the same after; it follows its new grid from then on (a
  frozen chart no longer takes its old grid's filter). The temporary editing grid and its Update go.
- **D8** Python's chart API: `chart.select(value, split=...)` (a mark named by its value across, and its series'),
  `chart.select(None)`, `chart.selection`; `chart.drill(value)`, `chart.drill_up(levels=1)`, `chart.drilled` (the
  browser's own `drillDown` and `drillUp`); `chart.grid()` -- the chart's grid, the same function as the browser's Show
  Grid: the grid a following chart follows, or, for a frozen or detached chart, one made first (a method, as
  `page.grid()` and `grid.chart()` are: the read-only `chart.grid` property it replaces goes). Explicit in Python, no
  toggle: a click's toggling is the screen's; a range across an axis not at first.
- **D9** Every ledger's growth waits on the user's dated sign-off, the growth this plan itself calls for included (rule
  1 as written: no standing approval).
- **D10** Signed off: the S1 raises of `L8_KINDS` (13 -> 15 for `select` and `refilter`, K7; 12 -> 13 for
  `setChartCube`, K11) and S4's of `L9_KEEPER` (6 -> 8, the restore writes N1 and N2); and S1's audit finding 10
  registered as P15 (`MARKERS` 9 -> 10). Each is fixed for real, not only listed: K7 and K11 in S6 (D7, D8), P15 in S5
  with Z4-Z7 (D5).

## 0. Why the nine attempts before this one did not finish

This is roughly the tenth program aimed at "Python and the browser the same": the headless design (10-11), the
one-app design (10-11), the twins program (10-15), one grid edit path (10-17), the command homework (10-17), command
stages 1-3 (10-18), page state (10-18), live commands (10-18), the page keeper (10-20). Each did real work. Each left
gaps the audit found, for the same six reasons:

1. **They fixed lists, not mechanisms.** Each inventoried what it saw that day and fixed it. Nothing failed when a new
   path appeared: `#change` stayed callable from anywhere in `app.ts`; `applyConfiguration` kept a rule of its own; the
   Show As presets (added later) send `calculate` commands no reader accepts, and no test noticed.
2. **They designed twins in for local reasons.** Page state (stage 2) chose "the screen makes the edit, then says so"
   so the screen's bundle would not carry `page-commands.ts` (`page-state.ts:5-8`). That one choice is root cause R1.
3. **They accepted "rare enough" exceptions.** Page undo, the page's model, Snap from the screen: each was left a
   whole-page write, and the exceptions multiplied.
4. **"Done" was declared per step, not against a measured end state.** The task "screen Snap/Go Live as page
   commands" was closed by a commit that built *following* Python's snaps and made the screen's Snap page state
   (534915c21); the screen still sends no `snap` command. The coverage report was "a
   report, not a gate" (`DATACUBE_COVERAGE_2026_10_18.md`).
5. **Python parity was nobody's test.** Capability gaps (page filters, sheet removal, calculated-column edits) were
   never a failing row anywhere.
6. **Old paths lived beside new ones "for now"**: `applyConfiguration` beside `configure`, the board's removal rules
   beside `withoutTile`, the host's whole page beside the commands it carries.

## 1. Rules of this program (what makes it different)

1. **Ledgers before code.** Step 0 puts every known gap into shrink-only ledger tests in the build (§3). A new gap
   turns the build red. An entry leaves a ledger only with the fix and its test. A ledger grows only with the user's
   dated sign-off.
2. **Done is measured.** The program is done when every ledger is empty (except the named exceptions in §2) and a
   fresh audit (step 7) finds nothing the ledgers missed.
3. **No designed-in twins.** A size or speed concern is met by raising a budget with a dated note, or by loading
   lazily, never by a second implementation.
4. **No step closes half-built.** Each step's acceptance is checked item by item by an independent reviewer against the
   register (§4). "Follows but does not send" is not done.
5. **The old path dies in the same step as the new one is born.** No two paths side by side.
6. **Every new command kind ships whole:** its apply rule, its JSON reader and writer, its rebase (renaming) arm, a UI
   producer, a Python builder, a parity row. The capability matrix test (ledger L8) enforces it, so a feature added
   after this program cannot reopen the gap.
7. **Every fix has a test that fails without it.** Every commit names its step and item ids, and updates the register
   in this file in the same commit.
8. **Gates.** The DataCube and Python suites green before every push. At the end of each step: the full local set,
   one CI run, an independent audit of the step, then an "As built" section appended here.
9. **Scope is held.** Anything found that is not in the register is added to it (with its ledger entry) before it is
   fixed; nothing is fixed silently, nothing found is dropped.

## 2. The end state

| | What is true | Measured by |
|---|---|---|
| E1 | Every change to saved grid or page state, on either side, is a `GridCommand` or `PageCommand`, applied by `applyGridCommands` / `applyPageCommand`. | L1, L2, L3, L4 empty |
| E2 | The screen's page and grids are *produced by* those functions; no screen code changes saved state another way. | L1, L2 empty; page-state guard |
| E3 | The host's page is what the commands make, not what a writer sent. | L9 |
| E4 | Derived state (a following chart, a selection, the tree's totals, column types) is computed by one TypeScript rule on every path. | L7 empty |
| E5 | Python holds no DataCube rule: its page document only ever comes from a headless answer; `model.py` is gone. | L5, L6 empty |
| E6 | One rule for every job both sides do. | L10 empty |
| E7 | Every command kind has a UI producer and a Python builder (or a recorded "by nature" reason); every source kind works on both sides. | L8 empty |

**Named exceptions** (allowed at the end; the user confirms them with this plan):
- **N1** A page is *born* whole: `Page()`, `Page.load`, opening a saved page.
- **N2** Undo and redo (grid and page) write the page whole as a *restore*, taken only at the current version. They
  become commands only if the proper live-editing model (out of scope, §8) is built.
- **N3** The snap row limit stays a value per host (the user and the DataCube + Python line, 2026-10-15); its refusal
  sentence comes from one TypeScript function.

## 3. The ledgers (step 0)

Each is a test in the build, listing today's gaps by item id. It fails on a gap not listed, and on a listed entry that
no longer exists (so the list can only shrink, and stays true).

| Ledger | Holds | How it detects |
|---|---|---|
| **L1** grid state changes | every call in `datacube/src/app.ts` that changes the cube's state other than `#applyCommands`; every call of the command path (the allowed ones' text pinned); every caller across the app of the grid's public ways in (`state`, `change`, `refilter`, `applyGridCommands`, `followSnap`, the controller); every caller of each listed gap; Snap's emits and controller calls, and the host's page writes, counted only in a function that tells no command | static, by enclosing function (indentation scope), as `state-guardrail.test.ts` reads sources |
| **L2** gestures | a catalogue of gestures on the real PageApp + CubeApp (jsdom) and the tab's live channel; each made on a quiet page; the commands told, as the host read them, replayed on the page before by the shared functions (as headless gridApply applies a grid's), give the page the screen wrote; no change written whole; a view-only gesture writes nothing; every command kind told by some gesture or listed | behavioural; ledger = each failing check with how it fails (the host's words, the parts of the page that differ) |
| **L3** wire | every command a gesture tells is read by headless pageWritten as it was sent | part of L2's run |
| **L4** Python whole writes | every `_unnoted()` call in `python/legend_lite/page.py`, and every assignment of the write's flags (`_unserved`, `_dirty`) | static, by Python's own parser |
| **L5** Python funnel | every write into a page's document -- whole, under it, through a handle's part (`_view`, `_cube`, `_query`) or a local name bound to one; deletes, `setattr`, `__dict__` -- each allowed one's right-hand side pinned (a headless answer) | static, by Python's own parser |
| **L6** Python copies | the Python functions in audit §5, by name; fails when one is gone but still listed | static (a register: new copies are caught by L5 and review) |
| **L7** convergence | at S0: derived state Python leaves as it was (V1, V2, G3, asserted as today) and every gesture's replay check (the screen against the shared functions); S2 first extends the convergence soak (Python, headless and the tab over one vocabulary, charts, selections and tree included), its divergences listed | behavioural |
| **L8** capability matrix | every command kind x {its kind list, both readers, the rebase's renaming, a screen producer, a Python builder}; every source kind x {Python reads it, Python adds it}; headless exports Python never asks; the capabilities with no kind of their own (K1, K3-K6, K9-K12) listed as unchecked until S6 agrees their names | static over the kinds' union types (a per-kind parity row is not detectable: the parity tests are by action) |
| **L9** host writes | what the keeper takes whole, through the engine's own write (H1-H3); who writes the keeper at all (page.py, engine.py, datacube.py) | behavioural, and static by Python's parser |
| **L10** same rule | one parity test per behaviour difference (audit §6); ledger = rows still differing | behavioural |

Every ledger's size is pinned (`CEILINGS`): a fix lowers it; it grows only with a dated note. The register is pinned too:
every item it has held is in it, open or closed.

Acceptance of step 0: every ledger green with today's entries, each entry naming an item id from §4; adding a dummy
`#change` call, a dummy `_unnoted()` and a dummy untold gesture each turns its ledger red (checked once, not committed).
No product change. Also: the program's line in `docs/IN_FLIGHT.md`.

## 4. The register

Every item, its step, its ledger. `[ ]` open, `[x]` closed (with the commit). Evidence: the audit, by section.

### Grid edits that are no command (audit §2, grid)
- [x] **G1** expand / collapse a row → `open` command — closed S1: `{kind:'open', path, open}` (set, never toggled; a path
  deeper than the row groups opens nothing), the chevron's handler builds it, Python `grid.expand` / `grid.collapse`; the
  gesture "a group opened" is told, read and replays
- [x] **G2** Collapse All → `collapseAll` — closed S1: the menu builds it, Python `grid.collapse_all`; the gesture "every
  group collapsed" is told, read and replays
- [x] **G3** Properties' tree effect (totals, expand level) → the tree rule inside `configure` — closed S2: `configure`
  moves the tree by the settings it sets (grid-commands.ts `treed`); Properties Apply is its commands alone, and
  Python's configure moves the tree as Properties does (grid-commands.test.ts, test_page.py); a setting given again
  as it is moves nothing (F3)
- [x] **G4** calculated-columns editor: edit and rename, references carried → `editCalculated`; the rename rule moved
  into `grid-commands.ts` from `snapshot.ts:803` + `config.ts:886-899` — closed S1: `{kind:'editCalculated', name, level,
  column}`; the two lists by one rule (snapshot.ts `withCalculated`, the editor's candidate and the commands alike), the
  settings carried by the command alone; Edit Column's OK builds it, Python `grid.edit_calculated` (headless
  gridCalculate, `replacing`); the gesture "a calculated column renamed in its Edit Column window" is told, read, replays
- [x] **G5** delete a calculated column (written twice today) → `removeCalculated` — closed S1: the editor's Delete and
  the header menu's both build it, Python `grid.remove_calculated`; the gesture "a calculated column deleted in its Edit
  Column window" passes
- [x] **G6** Filters window → `filter` — closed S1: its OK and Apply build `filter` after the compile check; the gesture
  "a filter changed in the Filters window" passes (and `filter` is told by a gesture now)
- [x] **G7** chart click on a grid's chart → grid `refilter {remove, add}` + page `select {chart, conditions}`; the
  rule moved out of `app.ts:2741` and `cube-page.ts:1462` — closed S1: the refilter rule is grid-commands.ts's, the
  chart's selection page-commands.ts's; a click tells both (a chart removed tells its grid's refilter, removeTile its
  page part); the gesture "a chart's mark clicked" is told, read, replays; Python's chart selection is K7 (S6)
- [x] **G8** restore heals a filter missing a chart's selection → the document kept consistent by G7's rule — closed S3:
  the board heals nothing (CubePage.sync puts it at the page and takes no rule of its own); a step of the page's history
  that brings back a chart with a selection its grid's filter lost meanwhile (the grids keep their own history) has it
  put back by that grid's own `refilter` command, told -- the owner's, by one rule (page-commands.ts
  `selectionRefilters`; page-app.ts #move; cube-page-shell.test.ts, a chart removed while it filters, undone). Not
  done, and said in As built (S3): the chart panel still regroups and the screen still reconciles a selection by the
  same rules on their own (followCube, the strict `withoutConditions`), against the grid's state -- a cube alone over
  rows its host names no source for has no cube in its page's state to follow (the S2 audit's 7)
- [x] **G9** use a dimension → `group` — closed S1: `#useDimension` (no longer public) builds `group`
- [x] **G10** `applyConfiguration` → deleted; its tests use `configure` — closed S1: a host edits a grid by
  `CubeApp.edit(commands, label)` (the screen's own path, told); the gesture "a grid's settings set by its host" passes
- [x] **G11** public `change()` / `state` → removed or read-only — closed S1: `change()` and the `controller` getter gone;
  `state` is `CubeStateView` (busy, historyDepth); a refresh and a debug print are `refresh()` and `printQuery()`; L1
  pins that neither the owner nor the controller is handed out again
- [x] **G12** grid undo / redo → N2 restore write — closed S4: an undo or a redo that lands (the owner's `moved`) is told
  as `onRestore`, and the page's channel writes the page whole, a restore its keeper takes only at the version it was
  made at (live-channel.test.ts, a real page: a grid's undo written whole, a stale one refused; test_one_api.py, a
  restore behind the page refused); its L1 entries are item-less N2 entries
- [x] **G13** an edit retried after signing in again → its commands kept and told — closed S1: a refused change's
  commands are kept (app.ts `#refusedCommands`) and told when it is run again and lands (cube-transactions.test.ts)
- [x] **G14** a grid made while the page bar is folded → `showTitleBar` in the `addGrid` document — closed S0: not a gap (the
  `addGrid` document carries the title bar as the grid was made; the gesture "a grid added while the page bar is folded"
  passes told, wire and replays)
- [x] **G15** standalone New ▸ Source cap rule (unreachable in the app) → deleted or shared — closed S1: shared, one
  rule (cube-document.ts `freshConfiguration`, capped by `gridCapped`); L1 pins no second spelling

### Telling exactly what landed (audit §2, after the grid table; each a marker until S1 writes its failing behavioural test first)
- [x] **T1** a command refused on the committed state but kept on the pending one — closed S1: judged on the state on
  screen; one the committed state cannot take is told after the change in flight lands (cube-transactions.test.ts)
- [x] **T2** a presentation command told before a running transaction's — closed S1: told at once (the committed
  state's own transition) and again after the transaction's when it commits, the order that state took them in
- [x] **T3** a change made on top of an undo in flight — closed S1: the owner's `committed` says `moved`; a transition
  an undo or a redo is part of tells no command, so the grid is written whole (G12's named exception)
- [x] **T4** Python's commands and the user's mixed during a follow — closed S1: another editor's commands
  (applyGridCommands) never enter what is told; the person's are told as they land

### Commands the host cannot read (audit §4)
- [x] **C1** window, child-aggregate and explode columns in the `calculate` JSON reader and writer — S1, L3 (closed S1:
  `calculatedFrom` reads every column kind strictly; grid-commands.test.ts round-trips each; the Show As gesture's wire passes)

### Page edits that are no command (audit §2, page)
- [x] **P1** page name box → `renamePage` — closed S1: page-app.ts `rename` tells it (no step of the history); the host's
  renamePage calls it; the gesture "the page renamed in its name box" is told, read, replays
- [x] **P2** Save / Save As under a name → `renamePage` — closed S1: saveTo renames by `page.rename` (L1 holds the host's
  code: no host write of the name outside a function that tells)
- [x] **P3** Snap / Go Live in the tab → `snap` / `live` — closed S1: a grid that moves its rows itself (its toggle,
  snapRows) says so (`moved`); its page tells `snap {grid, copy}` or `live {grid}`, no step of its history; two gestures
  (snapped, live again) are told, read, replay
- [x] **P4** Create a Class / Association / Data Space → model page commands (`modelInto`, `modelPin`, `modelKeep`)
  applied by `page-model.ts`'s functions — closed S1: `modelMade`, `modelPinned`, `modelKept` are the rules, in page-commands.ts
  beside the commands they apply (PageModel uses them; kept there, a lazy chunk, so the page loads none of them at start); the tab tells `modelCommands(before, after)` after each model action (page-commands.test.ts)
- [x] **P5** page undo / redo → N2 restore write — closed S4 as G12: PageApp's `#move` tells `onRestore` (the gesture
  ledger's undo and redo gestures write a restore and nothing else)
- [x] **P6** chart options told before the chart re-follows → falls out of S2 — closed S2: `setChart`'s rule keeps a live
  chart followed (followedPage), so the spec told before the panel follows replays to the one it keeps
  (page-commands.test.ts)
- [x] **P7** Open in grid resets the chart's band shares after a resize → an editor tile never touches the saved layout — closed
  S3: the board keeps each sheet's layout as the page has it apart from its own drawing (cube-page.ts `#pageLayouts`, set
  only when the board is put at a page: `sync`, `restore`, a new sheet), and writes that; the editing grid, placed
  beside its chart, shares its band evenly on the board alone, and closed, the board is put back at the page's shares
  (cube-page-shell.test.ts: a band shared 0.7/0.3, Open in grid, closed -- fails both ways without it). Corrected by the
  S3 audit (F9): an editing grid sharing its chart's band gave its share to a neighbour when a gesture was made while it
  was open; it is drawn in a band of its own below its chart's, and an arrangement made meanwhile is told as `arrange`
  of the page's tiles
- [x] **P8** Update a detached chart from its editing grid → `setChartCube` — closed S1: `setChartCube {chart, document}`
  (the chart reads `<chart>-query`, the cube it read before let go when unread), told with its setChart as one step; the
  gesture "a detached chart updated from its editing grid" is told, read, replays; Python's is K11 (S6)
- [x] **P9** restore drops views it cannot open → kept in the document as unavailable tiles, never dropped — closed S1:
  a cube whose source was not opened is kept as saved (page-app `#unopened`), its views placeholder tiles that say why
  (cube-page `placeUnavailable`), written back in their places; removed as removeTile removes a view, undone back
  (one-api-gestures.test.ts P9)
- [x] **P10** Open…, in place, Blank Page on a served page → a new page (D1) — closed S4: on a page another editor serves,
  each puts a page of the tab's own on screen and leaves the served one as it is -- its channel stopped, its moves no
  longer followed, said once, a blank page on its card (datacube-app.ts `leaveServed`; the browser harness: Blank Page
  says so, the tab asks Python's versions no more, and Python's change to its page is Python's alone; jsdom,
  datacube-app-live.test.ts: only Blank Page leaves it, and a follow on its way lands nothing). Open… and open in place
  call the same `leaveServed`; neither has a test of its own
- [x] **P11** the tab reopens cubes over frames as they are now → `rebuilt {cube, document}` page command made by one
  side (with Y2) — closed S1: whichever side opens a cube again over its frames tells it, once, by one rule
  (cube-document.ts `cubeTaken`): the tab opening a host's page tells `rebuilt` for each cube it opened as another
  (page-commands.ts `rebuiltCommands`, a snapped one not; test/python-engine: a frame changed in place, and nothing
  told when nothing changed), Python for each it opens (Y2); the tab follows a `rebuilt` from Python by opening the
  page again (live-follow.ts). After the S1 audit, `rebuilt` carries the source as it is now (`{cube, source}`, not the
  document) and is applied by one rule to the cube the page holds then (cube-document.ts `cubeOpenedAgain`), so made
  again on top of another writer's edit of the grid it keeps that edit
- [ ] **P12** `ll.show(df)` page unkept; a frame's column change loses grid edits → a kept page of one grid — S5, L10
- [ ] **P15** a model action's page record (`modelInto`, `modelKeep`, `modelPin`) is set outside the tab's page state and
  told by its difference, not applied as commands by the one function -- S1's audit finding 10, registered 2026-10-22
  (D10) → applied as commands, no undo step (D5) — S5, with Z4-Z7, MARKERS
- [x] **P13** charts on a cube alone (embedded CubeApp) tell nothing → the same state and commands — closed S3: a cube
  alone's board keeps a page state (page-state.ts) and applies each edit by the same one step as a page of its own
  (page-commands.ts applyToState), then puts the board at what it makes (CubePage.sync), and tells the commands
  (CubeAppOptions.onPageCommand); the rules come with the board's module, never with a grid alone
  (cube-page-shell.test.ts: added, clicked, a second, removed -- each replays to the board shown). The S3 audit's F6-F8,
  F12: its state kept as its grid's own changes leave it, its ids held across its board's lifetimes, its empty board
  said once the step has done; what it saves is its state less what a cube alone cannot save (its added grids)
- [x] **P14** stack and arrange gestures told as a whole `layout` → told as `stack` / `arrange` — closed S3: the board
  says what the hand did (band-board.ts `LayoutMove`): a lone tile let go on another's middle is told as `stack {tiles:
  [onto, tile]}` (stackTiles stacks as a drop does; a tab dragged out of its stack is not one), a preset picked as
  `arrange {sheet, preset, order}`; any other change, or one moving an editing grid, is the sheet's layout
  (band-board.test.ts; the gestures "a chart stacked on its grid by dragging", "a sheet arranged", and "a grid dragged
  to the left of another", which still tells `layout`)

### Python writes that are no command (audit §2, Python)
- [x] **Y1** `_into` / `_keep` → P4's model commands — closed S1: Python's model actions apply `modelInto`, `modelPin`,
  `modelKeep` through pageApply (test_model.py)
- [x] **Y2** `_reopened` → `rebuilt`; the take-or-not rule moved to TypeScript — closed S1: headless gridOpened says
  `taken` (cubeTaken) and `_reopened` applies `rebuilt` through pageApply (test_page.py)
- [x] **Y3** `_kept` with notes → `rebuilt` — closed S1: a grid edit opens its cube again first (`_current`: its
  `rebuilt`), so `_kept` notes the edit's commands alone (test_page.py, a Live frame changed in place)
- [x] **Y6** `serve_page` works out reads in Python → `pageReads`, or deleted — closed S5: deleted, with the engine's
  `close_page` -- test-only hooks in product code, no product caller; the engine's tests serve a page through the keeper
  themselves (test_engine.py `Page.serve_page`)

### Derived state (audit §3)
- [x] **V1** a following chart re-followed when its grid changes, by one rule on every path — closed S2: page-commands.ts
  `followedPage` (followCube over the cube as its document holds it), applied by `rebuilt`, `setChartCube`, `setChart`,
  the rebase after a grid's edit and headless `pageWithCube` (Python's grid edits); the gesture "a grid with a following
  chart ungrouped" replays (page-commands.test.ts, test_page.py). The screen's chart panel still follows by the same
  `followCube` on its own, against the grid's state on screen (G8's note; As built S3; the S2 audit's 7)
- [x] **V2** a chart's selection reconciled with its grid's filter, on every path — closed S2: the same rule takes off a
  selection its cube's filter no longer holds, as the screen's reconcile does (page-commands.test.ts, test_page.py),
  judged when that filter moves (F1), a chart's removal among what moves it (F2)
- [x] **V4** group-level column types written the same by both sides (spike first: type at compile, or stop storing
  run-derived types) — closed S2: the spike found typing at compile incomplete (a pivoted level's columns need the
  pivot's values, which only a query gives), so no side writes the type a run learned: `writeCube` leaves it out, the
  screen after a run and a host never run write the same cube (cube-document.test.ts); the corpus regenerated
- [x] **V5** expand level and the groups closed under it saved and read back — closed S2: `treeDocument` writes
  `expandTo` and `closed` (when it has a level), `readTree` reads them in words, `treeOf` and `openCube` open with them
  (`TreeState.saved`; regrouped, the closed paths go and the level stays)

### The host (audit root cause R2)
- [x] **H1** the keeper applies a write's commands and keeps what they make — closed S4: a write is its base version
  and its edits; the keeper has DataCube apply them to the page as it is (headless `pageRebase`: at the base nothing to
  combine) and keeps what they make; the tab sends no page, Python's page writes its commands the same way
  (keeper.py `settle`; test_one_api.py, test_keeper.py)
- [x] **H2** the no-command path deleted: the tab's `#told/#seen` guess and `edits: null`, the keeper's refusal for
  it, `pageWritten`'s null — closed S4: the one write that is no commands is a restore (N1, N2: keeper.py `restore`,
  headless `pageWritten(text)`); the tab writes its edits, or an undo's page whole, and nothing else
  (live-channel.test.ts, one-api-gestures.test.ts: a change no command made writes nothing)
- [x] **H3** the ids a page has held come from the applied commands (an id given and removed within one write is never
  given again) — closed S4: the keeper holds what the commands gave out, and an open page reads them with the page;
  and a page filter two writers made under one id: the keeper keeps the ids each version held, and the rebase is told
  the ones a write knew (`rebaseEdits` `known`), so a filter the writer made is given out afresh, never put in the
  other's place (page-rebase.test.ts, test_one_api.py)

### Python's copies of TypeScript rules (audit §5)
- [x] **Z1** `_with_cube` — closed S2 (early): a grid's edit is kept by headless `pageWithCube` (the cube spliced and
  its charts following it by DataCube's rule: V1, V2); `_with_cube` deleted
- [x] **Z2** `_ids` — closed S5: deleted; a page's ids are DataCube's (pageReads), as its keeper keeps them with each
  write -- every id it has held (`keeper.held`) at its start and each pull, the ids it holds now (`keeper.now`) for the
  handles it forgets, a loaded page's from pageReads -- a page filter's id among them, which Python's own left out
- [x] **Z3** `_into` / `_keep` (with Y1) — closed S1: where a model action writes is TS's (headless `pageModelInto`:
  PageModel.into); `_keep` is gone, `_into` is `_model_action`, glue that asks and applies
- [ ] **Z4** `_class` action — S5, L6
- [ ] **Z5** `_facts` association uniqueness — S5, L6
- [ ] **Z6** Create a Data Space orchestration — S5, L6
- [ ] **Z7** `model.py` (D4) — S5, L6
- [x] **Z8** `Page.grid`'s command order → TypeScript builds a grid's commands — closed S5: headless `gridSetUp`
  builds them in a person's order (as `draftCommands` orders an editor's) and refuses a part it cannot read, in words;
  Python's `page.grid(...)` applies what it gives (test_page.py `test_a_grid_in_one_call_is_the_commands_datacube_orders_and_reads`)
- [x] **Z9** join input names and placement → `inputOf`, placed near its first input — closed S5: page-commands.ts
  `joinInputOf(page, grid)` reads a grid as a join does, under its tile's given title else its source's name -- the
  tab's Join… (join-flow.ts, over the page it would save) and headless `joinCube` (over the host's page and the two
  grids' ids) ask it; `joinCube` gives the addGrid command, placed beside its first input as the tab places it, which
  Python's `page.join` applies (page-commands.test.ts, test_headless.py Joins, test_page.py
  `test_placed_beside_its_first_input_and_named_as_its_grids_show`)
- [x] **Z10** take-a-reopened-cube rule (with Y2) — closed S1: cube-document.ts `cubeTaken`
- [x] **Z11** command-or-whole rule in `_kept` (with Y3) — closed S1: gone; an edit is its commands
- [ ] **Z12** filter value conversion and pre-checks → the TypeScript reader — S5, L6
- [x] **Z13** stack needs two tiles (duplicate check) — closed S5: Python sends the stack and DataCube refuses it in its
  own words (page-commands.ts `stack`; test_page.py)
- [ ] **Z14** frames-only rule (D3) — S5, L6
- [ ] **Z15** a snapped grid's base and refusal → exported from headless — S5, L6
- [x] **Z16** snap refusal sentence from one TypeScript function (N3 keeps the values) — closed S5: snap.ts
  `snapRefusal(rows, limit)`, the preflight's, which Python's engine says through headless `snapRefused` with its own
  limit (snap.test.ts; test_engine.py `test_the_snap_limit_in_the_tabs_words`)
- [x] **Z17** copy ownership: one model (the keeper's), `HostSnaps` adopt / handOver and `_claimed` gone; keeper
  known limit 6 closed — closed S5: a copy is its maker's until a page the keeper keeps reads it, the keeper's from then
  on (keeper.py `has_read`, `letting_go`): it goes when no page reads it as it is, nor at a version a page open on it
  shows or any since -- each open page says the version it shows as it asks, follows and writes (`follower`, `at`:
  engine.py `_followed`, python-host.ts `PageFollower`; a hidden tab says so once a minute), silent for five minutes it
  keeps none. The grid lets go of whichever copy it reads and the host decides (engine.py `_release_copy`); the tab's
  `adopt` is `follow`, its `handOver`/`adopted` and the engine's `_claimed` are deleted (test_keeper.py, test_engine.py
  `test_a_copy_lives_until_every_page_open_on_it_moved_past_it`, plane.test.ts, live-follow.test.ts,
  datacube-app-live.test.ts)
- [x] **Z18** the saved-page record — closed S5: page-document.ts `savedRecord(id, name, page)`, the Save window's,
  which Python's `save()` asks through headless `pageRecord` (page-document.test.ts, test_page.py)
- [ ] **Z19** headless exports Python never calls (`pageFilter`, `joinQuestions`) used by S6's API, stale comments
  fixed — S6, L8

### Same job, different rule (audit §6)
- [ ] **B1** page filters reach Python's `view()`, `to_pandas()`, `export()` (headless through `gridLens`) — S5, L10
- [x] **B2** a tile renamed to its made title stores no title: the rule inside `renameTile` — closed S1: renameTile drops a
  grid's title equal to `gridTitle(id)`; the screen sends what was typed (page-commands.test.ts)
- [ ] **B3** one default page name — S5, L10
- [x] **B4** saving under a name renames the page, on both sides — closed S5: the record names the page's content as
  it is saved (`savedRecord`), and Python, as the tab does, renames the page once it is kept (renamePage) (test_page.py)
- [x] **B5** one page-file rule — closed S5: page-document.ts `pageFile`, Export ▸ Page File's, which Python's
  `save(path)` asks through headless `pageFile`: DataCube's one-line text with a newer writer's fields kept (the tab's
  export now writes the page as the tab keeps it, as Save does), refused in one wording when there is nothing on it or
  its classes are kept in a project (page-document.test.ts, test_page.py)
- [x] **B6** empty pages allowed on both sides (D2) — closed S5: Python shows a page of no grid yet and takes a shown
  page's last grid off, followed empty as the tab's blank page is (`_showable` and `remove`'s refusal deleted); neither
  side saves one (B14) (test_page.py `test_an_empty_page_is_shown_and_never_saved`, test_notebook.py)
- [ ] **B7** sources on a Python page (D3, with X1-X5) — S5, L10
- [x] **B8** one rule for a detached chart going live — closed S5: DataCube's `setChart` refuses a detached chart
  (no grid shows its cube) told to follow, in its words, for Python as for the screen, whose pill is off for it
  (page-commands.ts; page-commands.test.ts, test_one_api.py `OneRule`)
- [ ] **B9** one new-page skeleton (`pageNew` for the tab's Blank Page too) — S5, L10
- [ ] **B10** a frame's column change keeps grid edits everywhere (with P12) — S5, L10
- [ ] **B11** Join on both sides over every source kind — S5, L10
- [x] **B12** ids held (with H3) — closed S4: one held set -- the keeper's, from the applied commands, which the tab
  reads with the page and its state holds (`PageApp.restore`'s `held`) and Python takes with it -- so neither side gives
  out an id the keeper has said it held (test_one_api.py, page-app.test.ts, datacube-app-live.test.ts: F23). A write
  that leaves the page as it was holds its ids with no new version; an editor that then gives one out has it given out
  afresh by the keeper
- [x] **B13** one saved record per page: the record's id kept in the page document, so both sides save to it — closed
  S5: page-document.ts `record` (read, written, kept by every command, never in a page file), set by the `savedAs` page
  command -- no step of the history -- which the tab's Save and Python's `save()` tell; `savedRecord` saves over it
  (Save As: a new one); the tab's `current.cubeId` and Python's `_saved` deleted (page-document.test.ts; test_one_api.py
  `OneRule`)
- [x] **B14** one "nothing to save" wording — closed S5: page-document.ts `NOTHING_TO_SAVE`, the screen's Save and
  Page File's, which Python's `save` and `to_json` say through headless `pageSaved`; Python's own wording and the
  reader's `PageNoCubeError` (its one use) deleted (page-document.test.ts, test_page.py)

### Every source on both sides (D3)
- [ ] **X1** headless `gridBase` for file, remote file, warehouse table, saved query, class (the planning code the
  browser uses) — S5, L8
- [ ] **X2** Python reads, edits and saves a page over any source — S5, L8
- [ ] **X3** the keeper keeps the file and remote tables a page reads, as it keeps copies (`pageReads` says them) —
  S5, L9
- [ ] **X4** Python adds such grids: `page.grid(file=…)`, remote, warehouse, saved query, class — S6, L8
- [ ] **X5** saved queries and classes through Python's project routes — S5, L8

### Capability parity (audit §7; Python's API, names reviewed with the user before S6)
- [ ] **K1** sorts: replace, clear one, clear all — S6, L8
- [ ] **K2** add one condition to a filter — S6, L8
- [x] **K3** calculated columns: edit, rename, remove — closed S1 with G4/G5: Python `grid.edit_calculated` (rename=) and
  `grid.remove_calculated`, test_page.py
- [ ] **K4** group-level, window, child-aggregate, explode columns; the Show As and period presets — S6, L8
- [x] **K5** expand, collapse, collapse all — closed S1 with G1/G2: Python `grid.expand`, `grid.collapse`,
  `grid.collapse_all`, test_page.py
- [ ] **K6** dimensions — S6, L8
- [ ] **K7** a chart's selection; page filters — S6, L8 (D8: `select`, `selection`)
- [ ] **K8** sheets: reorder, remove — S6, L8
- [ ] **K9** drill a chart, and back up — S6, L8 (D8: `drill`, `drill_up`, `drilled`)
- [ ] **K10** Copy of Grid — S6, L8
- [ ] **K11** a chart's Open in grid and Update — S6, L8 (D7: one Show Grid replaces them; D8: `chart.grid()`)
- [ ] **K12** open a saved page by id — S6, L8

### Found by the audits (rule 9: each added here, fixed with a test that fails without it, closed in that commit)
- [x] **F1** a chart's selection taken off by a grid edit made while its click's refilter was in flight (the page's
  `select` is told at once, the grid's `refilter` when it commits): made again on top (`rebaseEdits`), or a host's
  `setChart` meanwhile, dropped it, the filter then holding a condition no chart owns — closed S2: `followedPage` judges
  a selection only when its cube's filter moved (page-commands.test.ts) — the S2 audit's 1
- [x] **F2** a chart removed (or its sheet) took its selection off its cube's filter without judging that cube's other
  charts by it — closed S2: `withoutTile` follows the cube after (page-commands.test.ts) — the S2 audit's 5
- [x] **F3** `configure` giving `initialExpandToLevel` again as it is reopened the groups closed under it (Python's
  configure names every setting it is given; the screen's Properties sends only what changed) — closed S2: the tree
  moves only when a setting that decides it changes (grid-commands.test.ts) — the S2 audit's 4
- [x] **F4** Python's handles given out afresh in a chain (sheet-2 -> sheet-3, sheet-3 -> sheet-4) swapped ids and one
  left the page's handles — closed S2: every moving handle taken off before any is put back (test_page.py) — the S2
  audit's 6
- [x] **F5** a tile or sheet Python made inside a call an open page wrote under was handled under the id the write took,
  not the one the page gave it afresh — closed S2 (a147d256c): `Page._apply_page` answers with the ids the commit gave
  (test_page.py, the sheet made while the open page made one) — found by the keeper's soak at 60 seeds
- [x] **F6** a cube alone's page state went stale as its grid's own changes landed (a view, a selection its filter lost),
  and the next edit of its board brought back a selection the filter no longer held — closed S3: the board's state kept
  as each view lands and each presentation change (app.ts `#keepBoard`; cube-page-shell.test.ts F6) — the S3 audit's 1
- [x] **F7** a cube alone's last chart removed threw (its board taken down while the step that removed the chart was
  still being drawn) — closed S3: the board says it is empty once `sync` has done (cube-page.ts `#afterRemove`;
  cube-page-shell.test.ts F7, F8, a listener's error caught) — the S3 audit's 2
- [x] **F8** a cube alone gave an id out again after its board was emptied — closed S3: the ids its board has held are
  kept when it goes (app.ts `#boardHeld`; cube-page-shell.test.ts F7, F8) — the S3 audit's 3
- [x] **F9** a layout gesture made while an editing grid was open gave the editor's share to a neighbour; an
  arrangement made then was told as a whole `layout` — closed S3: the editing grid is drawn in a band of its own below
  its chart's (cube-page.ts `#drawn`), and an arrangement is told as `arrange` of the page's tiles
  (cube-page-shell.test.ts F9, two: the same page with the editor open or not) — the S3 audit's 4
- [x] **F10** an edit made while `follow()` waited for another editor's snap was lost from the page's state (the state
  set after the wait) — closed S3: the state is the commands' page before the grids read their rows (page-app.test.ts
  F10) — the S3 audit's 5
- [x] **F11** a detached chart's Update changed the board itself (its kept query, the chart's link) before telling,
  and undone left the page mixed -- the spec back, the query not — closed S3: Update tells `setChartCube` and
  `setChart`, the grid it made waiting; `sync` has the chart read the query the step holds (the Update's, one kept, or
  one made again from the step's document: an Update again, undone); a page state's reachable ids hold its cubes
  (cube-page-shell.test.ts F11) — the S3 audit's 6
- [x] **F12** a cube alone's told commands replayed to another page than it saves — closed S3, declared: they replay to
  the board it shows; what it saves is that less what a cube alone cannot save (its added grids and the charts that
  read them: a cube alone saves one cube) (cube-page-shell.test.ts F12, pinned) — the S3 audit's 7
- [x] **F13** Go Live refused for a grid whose cube the page cannot hold (its snap applied, its copy dropped) — closed
  S3: snap and live are told only for a grid whose cube the page holds; a cube alone's grids have their own documents
  (cube-page.ts `documentOf`; page-app.test.ts F13) — the S3 audit's 8
- [x] **F14** a cube alone's added grid renamed to its id's number was put back to its count's name — closed S3: one
  rule names a grid with no title of its own, its id's number (page-ids.ts gridTitle), on a cube alone's board too
  (cube-page-shell.test.ts F14) — the S3 audit's 9
- [x] **F15** undoing an edit unrelated to a chart put back a selection its grid's own filter edit had taken off — closed
  S3, declared under N2: an undo puts the page at a whole step, so a grid's own change made since is not carried into
  the steps; it goes with the proper live-editing model (§8: undo as inverse commands). Not new since S2 -- the S3
  audit's 10
- [x] **F16** a chart with no spec yet moved to the bottom of its sheet at each step; a drag in progress ended at
  another editor's step that left its sheet as it was — closed S3: drawn beside its grid (`#drawn`); a sheet is drawn
  again only when it moves (cube-page-shell.test.ts F16, two) — the S3 audit's 11. A chart with no spec yet whose id
  another editor's step gives a chart of its own is shown as theirs (its id was never told): left, an edge with no
  path to it in practice
- [x] **F17** a grid whose addition was refused threw after letting it go — closed S3: let go, its owner told, nothing
  thrown (cube-page-shell.test.ts F17) — the S3 audit's 12
- [x] **F18** a chart's selection was dropped when its grid's filter, read back from another writer, held the
  selection's condition with its keys in another order (and a refilter could not take that condition off): two filter
  nodes alike but for the order of their keys were told apart — closed S4: nodes are compared with their keys in order
  (snapshot.ts `sameValue`; page-commands.test.ts F18) — found by S4's keeper soak
- [x] **F19** the tab left the page Python serves when its start opened nothing -- an empty page (`ll.Page` shown before
  its first grid), a first read that failed -- and so followed it no more — closed S4: only New ▸ Blank Page leaves it
  (datacube-app.ts `blankPage`'s `asked`; datacube-app-live.test.ts) — the S4 audit's 1
- [x] **F20** a grid over a source the host cannot base its edits on (a file, a warehouse table, a saved query, a class)
  was taken onto a page Python serves, and its every edit then refused — closed S4: one rule of what a page on the host
  holds (headless grid.ts `onHost`), which the keeper asks of each cube a write adds or moves, refusing it as it is added
  (page-rebase.test.ts, test_one_api.py) — the S4 audit's 2. Such grids come to a page on Python with S5 (B7, X1-X5)
- [x] **F21** a page filter no command made under its keeper (a page loaded or served whole), set again by a writer,
  became a second filter — closed S4: what a page reads names its page filters' ids too (page-rebase.ts `pageReads`), so
  the ids a write knew carry them (page-rebase.test.ts) — the S4 audit's 3
- [x] **F22** a follow on its way when the page was left threw, or put Python's page back over the tab's own with no
  channel — closed S4: the follow writes through the channel it began with and lands nothing once the page is left, and
  Blank Page overtakes an open on its way (datacube-app-live.test.ts, three) — the S4 audit's 4
- [x] **F23** B12's tab half had no test, and its Python test could not fail — closed S4: each has one that fails without
  it (page-app.test.ts, datacube-app-live.test.ts, test_one_api.py); a write that leaves the page as it was holds its
  ids with no new version, which neither editor hears of -- one either gives out is given out afresh by the keeper (said,
  in As built) — the S4 audit's 5
- [x] **F24** a write made more than STEPS_KEPT versions behind is refused, where before S4 it was made again on top —
  closed S4, declared: the keeper keeps the ids each version held only as far back as it keeps its steps, and a write
  from further back cannot be told what it knew (test_keeper.py, pinned) — the S4 audit's 6
- [x] **F25** an undo or a redo that lands while the tab follows the other editor is not written; edits made while a
  write is on its way are dropped when the keeper makes another page of it — closed S4: the dropped edits are said
  (live-channel.ts; live-channel.test.ts); the undo while following declared (the channel takes nothing done while
  following for its own; pinned) — the S4 audit's 7, not new since the keeper's step 2
- [x] **F26** comments and docs stale since S4 (the app's, python-host.ts's, the headless entry's, the keeper's As
  built) — closed S4 — the S4 audit's 8
- [x] **F27** a failure of the engine's while it took an open page's write was said as no such page (a KeyError) or as a
  refusal (any other) — closed S4: said as a failure, 500, naming it (engine.py `_page_said`; test_one_api.py) — found
  reviewing S4's own code

## 5. The steps

Each step: the full local set green, one CI run, an independent audit against its items, "As built" appended here.

### S0. Guards (no product change)
The ten ledgers of §3, each listing today's entries by item id; the IN_FLIGHT line. Size: about 1k lines of tests.

### S1. Every edit a command
Items: G1 G2 G4-G11 G13-G15, T1-T4, C1, P1-P4 P8 P9 P11, Y1-Y3, Z3 Z10 Z11, B2.

- **The grid's draft gains its tree.** `GridDraft` (`grid-edits.ts:21`) is `{snapshot, config}` today; it becomes
  `{snapshot, config, tree}` with the saved tree (open paths, totals, expand level, closed groups). CubeApp's
  `#applyCommands` passes its tree; headless `applied` (`headless/grid.ts:440`) applies to the saved tree instead of
  keeping it.
- **New grid commands:** `open {path, open}`, `collapseAll`, `editCalculated {level, name, column}`,
  `removeCalculated {level, name}`, `refilter {remove, add}`. Each with its rule in `grid-commands.ts`, reader, writer,
  parity row.
- **`calculate` reads and writes every column kind** (window, child aggregate, explode), so the host reads what the
  screen sends and Python can build them.
- **New page commands:** `select {chart, conditions}`, `modelInto`, `modelPin`, `modelKeep` (applied by
  `page-model.ts`'s `into` / `pin` / `keepTables` / `keepClass`), `setChartCube {chart, document}`,
  `rebuilt {cube, document}`. Each with its rule in `page-commands.ts`, its `renamedCommand` arm, reader, writer.
- **The screen tells what it does:** the name box and Save tell `renamePage`; Snap and Go Live tell `snap` / `live`;
  the model actions tell the model commands; the Filters window, a dimension and a chart click go through `#apply`;
  `retryRefused` tells the refused change's commands; CubeApp tells exactly the commands of each committed transition,
  in commit order, with who made them (T1-T4). `applyConfiguration` and public `change()` deleted.
- **Python:** `_into` / `_keep` become model commands through `pageApply`; a reopened cube becomes `rebuilt` through
  `pageApply`, with whether to take it decided in TypeScript (headless `gridReopened`).
- **Restore keeps what it cannot open** as an unavailable tile (P9), so no document loses a view silently.

Size: about 3k lines. Risk: the tree in the draft changes how open-group changes move through the state owner (today a
presentation change); keep them presentation changes, pinned by the existing undo-coverage test.

### S2. Derived state by one rule
Items: G3, V1 V2 V4 V5, P6.

- **`followedPage(page, cube)`** (pure TypeScript): after a cube changes, each live chart of it re-followed
  (`followCube`) and each chart's selection reconciled. Called by `applyPageCommand` for every command that changes a
  cube, by headless after a grid edit on a page (a new `pageGridApplied`, which Python's `Grid._apply` calls instead of
  `gridApply` + `_with_cube`), by `rebaseEdits`, and by the screen: a chart panel draws the followed spec from the page
  rather than following on its own.
- **The tree follows `configure`** (totals, expand level) inside the command's rule.
- **Expand level and closed groups saved** by `treeDocument` and read back.
- **Group-level column types:** a spike decides between typing at compile on both sides and not storing run-derived
  types; then one function.

Size: about 1.2k lines. Risk: chart following (the B1/B2 bugs lived here); the chart corpus and L7's seeds guard it.

### S3. The board produced by the commands
Items: P7 P13 P14, and the board's copies of command rules (tile and sheet removal, a chart's selection on removal,
sheet move, tile placement, ids held, `follow()` dropping `held` and `cubes`).

- Every gesture on `CubePage` builds its command; `PageApp` applies it to its state (`applyPageCommands`); the board is
  put at the result by `CubePage.sync` (already what undo, redo and follow use). The board's own mutation code is
  deleted; it keeps the gestures, the previews and the drawing.
- Stack and arrange gestures tell `stack` / `arrange`. An editing grid never touches the saved layout. `follow()`
  keeps `to.held` and `to.cubes`. The embedded CubeApp's charts use the same state.
- `page-state.ts`'s header rewritten (the screen applies commands); the bundle budget raised with a dated note naming
  this step (the screen now carries `page-commands.ts`).
- Guard: a page-state guard test (as `state-guardrail.test.ts`): no page state changes in `cube-page.ts` /
  `page-app.ts` except through the apply.

Size: about 2.5k lines changed, a net deletion. Risk: view state across `sync` (scroll, the shown sheet, an open chart
panel, its editing grid, a maximised tile, a stack's front tab, focus). Each gets a test that it survives an edit; the
screenshot tour runs at the end of the step.

### S4. The host applies the commands
Items: H1-H3, G12 P5 (N2), P10 (D1), B12.

- A write is its base version and its commands. The keeper applies them through headless DataCube (the same apply
  `pageRebase` runs; at the current version there is nothing to combine) and keeps what they make. The tab no longer
  sends its page.
- One other write: **restore** (N1, N2), a whole page taken only at the current version.
- Deleted: the tab's `#told/#seen` and `edits: null`, the keeper's refusal of a no-command write, `pageWritten`'s null,
  the tab-sent page.
- D1: Open…, in place and Blank Page on a served page open a new page.
- Measured: the time of one write on a 60-grid page, recorded here with a budget set from it.

Size: about 1k lines, about 400 deleted.

### S5. Python's copies gone; one rule each; every source
Items: Z1 Z2 Z4-Z9 Z12-Z18, Y6, B1 B3-B11 B13 B14, X1-X3 X5, P12.

- **Spikes first:** (a) `model-changes/` running headless, which needs one more host call (HTTP to the SDLC and the
  model writer, answered by Python); (b) a warehouse table, a saved query and a class as a Python grid's base.
- Every §5 copy replaced by its TypeScript function asked headless; where the function lives inside a dialog, its
  logic is moved out first, so the dialog and Python call the same one (`createClassOf`, `createDataSpaceOf`, the
  association check, `inputOf`).
- One rule for each §6 row, in TypeScript, both sides calling it.
- D3: `gridBase` for every source kind; Python's frames-only rule deleted; `pageReads` says the file and remote tables
  a page reads and the keeper keeps them like copies.
- `ll.show(df)` becomes a kept page of one grid (P12). Copy ownership becomes the keeper's alone (Z17).

Size: about 3k lines; `model.py` and Python's rule code deleted. Risk: the two spikes; if either finds a blocker, it is
brought to the user before the step goes on.

### S6. Capability parity
Items: K1-K12, X4, Z19. A Python method for what a Python user would do, calling the browser's own function (D6), the
rest named exceptions; the names agreed with the user before building (the charts': D7, D8). Size: about 1.5k lines.

### S7. The final audit
The four audits run again, fresh, with one extra instruction: find what the ledgers miss. Anything found goes into the
register and a ledger, and is fixed. Done when every ledger is empty but N1-N3 and the audit finds nothing new.

## 6. How it is tested

- **Unit:** every new rule in `grid-commands.ts` / `page-commands.ts` / `page-rebase.ts`, including refusals in words.
- **Round trip:** every command kind through JSON and back (extends `page-commands.test.ts` "every kind").
- **Parity:** Python's builder and the screen's produce the same command for the same edit (extends the stage-1
  parity tests).
- **Gestures (L2):** the real PageApp and CubeApp in jsdom; every gesture's told commands replay to the page it made.
- **Convergence (L7):** seeded runs of the whole vocabulary through Python, headless and the tab; same page after each
  step, charts, selections and tree included.
- **Live seam:** the browser harness (`datacube/test/python-engine`) on one path per command family.
- **Mutation:** the scratch mutation harness over each step's new rules (as in keeper step 1b); a surviving mutant is
  a missing test.

## 7. Size and order

About 12k lines changed, a few thousand deleted, in eight steps, done in order: S0, S1, S2, S3, S4, S5, S6, S7. S0
first because it is what makes this attempt different: from its first commit, a new gap fails the build. S5 is the
largest; S3 the riskiest.

## 8. Out of scope (said, so it is not mistaken for done)

- **The proper live-editing model** (each view holding confirmed and pending edits, re-applying its own with
  `rebaseEdits`, no reopen on a collision; undo as inverse commands). This plan is its foundation; it is a separate
  decision after S7.
- **Graph mode, chart batches, the backlog PR** (task 79), **§18 steps 5-7** (blocked on the user's A4 decision).

## 9. Tracking

- The register (§4) is the checklist; the ledgers are the truth it mirrors.
- Each commit: `One API S<n> <ids>: <what>`; the register updated in the same commit.
- Each step ends with "As built (S<n>)" here: what was built, what the audit found, the ledger counts before and after.
- `docs/IN_FLIGHT.md` carries the program's line from S0 to S7.

## As built (S0, 2026-10-21)

**Built** (commits 5eb17fc42, d598bf146, and the audit's round): `datacube/test/one-api-ledger.test.ts` (L1, L8, the
markers, C1, V5, the register), `datacube/test/one-api/scan.ts`, `datacube/test/one-api-gestures.test.ts` (L2, L3:
33 gestures and a control), `python/tests/test_one_api.py` (L4-L7, L9, L10), the BUILD wiring (the plan and Python's
sources copied to the output tree for the JavaScript test), the program's line in `docs/IN_FLIGHT.md`. No product code.

**What the gestures found on the real page.** Every page and grid gesture driven passes told, wire and replays except the
listed ones: the name box (P1), page undo (P5), a chart's mark (G7), a detached chart's Update (P8, written whole -- worse
than the audit said), a group opened (G1), Collapse All (G2), `applyConfiguration` (G10), Show As (C1, refused by the
host's reader), a following chart regrouped (V1: the chart's `split` differs). Opening a frozen chart in a grid writes
nothing, as a view change should. G14 is no gap (closed above). Every command kind is told by some gesture, but those the
screen does not make (`renamePage`, `stack`, `arrange`, `snap`, `live`: P1, P2, P14, P3) and two not driven yet (`measures`
by nature; `filter`, the Filters window being G6).

**Checked red.** Round one: a stray `#change`, a stray `_unnoted()`, a kind nobody makes, an untold gesture. Round two, the
audit's own escapes: a new caller of `refilter` on the board, a new action reaching a listed gap, a Python write through a
tile's view, a kind no reader reads, an item taken out of the register unclosed, a ledger entry added past its ceiling.
Each turned its ledger red with a message naming it; each was taken out again.

**The independent audit** (23 findings) and what became of each:
- Fixed: the grid's public ways in and their callers across the app (1); every caller of each listed gap (2); more
  gestures (sheets renamed, moved, deleted; arranging; page filters on a class grid; sorts by menu and click, the tree
  column's sort; hide; Column Properties; a filter from a cell; groups opened and collapsed; Open in grid) and every
  command kind told or listed (3); the gaps' names held to the gestures', the gesture list only grows (4); every ledger's
  size pinned (5); Python's funnel through handles, aliases, deletes, `setattr`, `__dict__`, and the write's flags (6);
  the kind lists, both readers and the rebase's renaming checked per kind (7); host writes and Snap counted only where no
  command is told, so the fix is what takes them off, P2 by its own entries (9); replays of the commands as the host read
  them (13); a quiet page before each gesture, and a control (14); failures pinned by how they fail (12); producers read
  only in the modules that make commands, Python's docstrings left out, headless functions cut at their brace (16); the
  source kinds' keys and refusals, `PYTHON_ADDS` pinned (17); each allowed Python assignment's right-hand side pinned (18);
  every call of the command path, the allowed ones' text pinned (19); Snap's controller calls (20); C1's three column
  kinds, V5's closed groups, H2's tab halves, H1 through the engine (21); the register pinned whole (23); a comment's code
  after `*/` read (low).
- Said rather than faked: the nine capabilities with no kind of their own are listed as unchecked until S6 agrees their
  names (8); G12/P5 close in S4 by the restore write and a stale-restore test (10); the gesture harness stands in for the
  host's rename, P1's check on the host's own code being L1's (11); T1-T4, G13, P6 and P7 stay markers until S1/S3 write
  their failing behavioural tests first (15); L7 at S0 is three Python assertions and the gestures' replays, S2 extends the
  convergence soak first (22); markers pin wording and L6 names by function: re-pinned when moved (low).
- Not done: a per-kind parity row (the parity tests are by action, not by kind: no cell can be read from them).

**Gates.** `//datacube/... //python/...`: green (four environment reruns: three tests the disk filled under, `verify_app`
as the container's unprivileged user). CI: not run -- the gate runs on a pull request, a push to main or a dispatch; this
branch has no pull request and the session's GitHub access may not dispatch (403). Waiting on the user: a draft pull
request (CI on every push) or a dispatch of `gate.yml` on `claude/datacube-query-base`.

## As built (S1, 2026-10-21)

**Built** (commits 6ef92b374 through this step's audit round): every S1 item closed in the register above, G8 moved to
S3 (a page step does not restore the grids' documents, so the restore heal waits for the board produced by the
commands). New grid commands `refilter`, `editCalculated`, `removeCalculated`, `open`, `collapseAll`; new page commands
`select`, `setChartCube`, `modelInto`, `modelPin`, `modelKeep`, `rebuilt`; `calculate` reads every column kind (C1);
the grid's draft carries its tree; CubeApp tells the commands of each transition of its committed state in the order
it took them (T1-T4), a retry by the refused change's commands (G13); `applyConfiguration`, public `change()`, the
`controller` getter gone and `state` a view of what it reads (G10, G11); restore keeps what it cannot open (P9);
Python's model actions and reopened cubes are commands (Y1-Y3); the page's model rules live in page-commands.ts, so the
page does not load page-model.ts at start (P4).

**Deviations from the plan's wording, each kept on purpose.** `removeCalculated` has no level (a calculated column's name
is one across both levels: `nameProblem`). `gridReopened` became `gridOpened`'s `taken` and `opened`. "With who made them"
(T4) is another editor's commands never entering what CubeApp tells, rather than a label on each. `GridDraft.tree` is
optional (an editor's draft holds no open groups; `configure` moves a tree only where there is one). `rebuilt` carries
a source, not a document (the audit's 1, below).

**The independent audit** (15 findings) and what became of each:
- Fixed, each with a test that fails without it: (1, high) a `rebuilt` made again on top of another writer's grid
  edit overwrote it -- now applied by one rule over the source (`cubeOpenedAgain`; page-commands.test.ts, test_page.py
  with the open page's regroup); (2, high) the person's grid edits told while the tab followed the other editor were
  dropped -- the channel keeps a grid's (live-channel.test.ts); (3) another editor's commands that changed nothing were
  told back (cube-transactions.test.ts); (4) Python's edit of a calculated column reset its kind and level -- it starts
  from the column as it is, the editor's own rule moved to column-draft.ts `existingDraft` (test_page.py); (5) removing
  a chart with a selection took its condition off twice on the host, and was written whole -- the screen tells the
  selection cleared and the removal as one edit, said once (a gesture with the condition on the grid by hand, which
  fails both ways without the fix); (6) a snapped cube told as `rebuilt` -- `rebuiltCommands` leaves it out
  (page-commands.test.ts); (8, 9) a retry replaced the commands of a change in flight and ran the refused state, losing
  what was made since -- it applies the refused change's commands to the grid as it is then, or, an undo part of it,
  runs it again as asked (the owner's `refused` says `moved`; cube-transactions.test.ts); (11) a kept cube's snap record
  dropped on write (one-api-gestures.test.ts P9); (13) `state` handed out the owner itself (cube-transactions.test.ts);
  (14) no guard against a cube told back and forth -- a page loaded over frames as they were tells no `rebuilt`
  (test_page.py), and the browser reopened with nothing changed writes nothing (test/python-engine); (15) two stale
  comments.
- Said rather than fixed, waiting on the user: (7) `L8_KINDS` was raised twice in S1 (13 -> 15 for `select` and
  `refilter`, 12 -> 13 for `setChartCube`) with dated notes but without the user's sign-off that rule 1 asks for; those
  kinds have no Python builder yet (K7, K11: S6, whose names the user agrees first). (10) The tab's model actions still
  change the model and then tell what changed (`modelCommands` of before and after) rather than applying commands first;
  Python applies `modelPin` and `modelKeep` always, the tab only when they changed -- the same page either way. Not yet a
  register item: registering it grows a ledger, which also needs the user's sign-off.
- Noted: (12) a presentation command told twice (T2) is right because every presentation command today sets values
  (`configure`); nothing pins that. G9 and G15 are held by static pins, no behavioural test.

**Gates.** `//datacube/... //python:engine_test //python:model_test`: green at P11 and at G13/T1-T4 (191 of 191 the
second time), `verify_app` as the container's unprivileged user green; the audit round's targeted tests green, its
full run below. CI: not run -- still waiting on the user (a draft pull request, or a dispatch of `gate.yml` on
`claude/datacube-query-base`).

## As built (S2, 2026-10-21)

**Built** (a9ec10e7f, b659ca010, a147d256c, and this step's audit round): every S2 item closed in the register --
`configure` moves the grid's tree by the settings that decide it (G3); a page's charts follow their cube by one rule,
`followedPage`, wherever a cube changes -- `setChart`, `setChartCube`, `rebuilt`, a writer's grid edits made again on
top, Python's grid edits (headless `pageWithCube`) -- live charts regrouped, a selection its filter no longer holds
taken off (V1, V2, P6); the tree's expand level and the groups closed under it saved and read back (V5); no side writes
the type a run learned for a group-level column (V4). Z1 closed with V1 (Python's `_with_cube` deleted). The keeper's
soak now builds a page with a following chart; at 60 seeds it found F5, fixed with its test.

**Deviations from the plan's wording, each kept on purpose.** `pageGridApplied` became `pageWithCube` (the grid's edit
is applied first by `gridApply`, as before; the page part is one call). V4's spike chose "stop storing run-derived
types": typing at compile cannot type a pivoted level's columns (they need the pivot's values, which only a query
gives). The screen's chart panel still follows by the same `followCube` on its own, and the screen's reconcile still
judges a selection, both against the grid's state on screen and told by no command -- the S2 bullet "a chart panel
draws the followed spec from the page" is carried to S3 inside G8 (the board produced by the commands).

**The independent audit** (10 findings, and one outside S2) and what became of each:
- Fixed, each with a test that fails without it, each registered (rule 9) and closed: (1, medium) a selection told
  before its click's refilter lands was dropped by an edit in between -- F1; (4) a configure giving the expand level
  again reopened closed groups -- F3; (5) a chart's removal took its selection off the filter without judging the
  cube's other charts -- F2; (6) Python's chained renames of handles -- F4; (8) the soak's handle bug fixed with no
  register item -- F5.
- Fixed as S2's own: (2, medium) the soak did not hold S2's claims -- it now makes chart clicks (a `select` and its
  grid's `refilter`, written together) and tree edits on both sides (`open`, `collapseAll`, the expand level), and
  checks each settled page against an oracle: every cube's page a fixed point of following (headless `pageWithCube`),
  every chart's selection on its grid's filter; (3) `rebuilt` and `setChartCube` following had no test -- now each has
  one that fails without it (page-commands.test.ts); (10) nits: G3's note named the wrong test file, `followedPage`'s
  doc comment had orphaned `cubeOf`'s, `treeOf` had no product caller (`openCube` now opens a saved tree through it),
  `readTree` dropped closed groups under no level (now refused in words), `pageWithCube` threw where its siblings
  answer `{problems}`, `Page._given` shared a name with a module function (`_regiven`), the round-trip seeds never set
  an expand level (they do, and fail without the writer's `closed`).
- Registered rather than fixed: (7) the screen's own following and reconcile -- folded into G8 (S3); the outside
  finding (a page filter both writers make under one id is replaced, not given afresh: the rebase has no write's base)
  -- folded into H3 (S4, with H1's base version).
- Noted: (9) pages saved before V5 open at level 0 whatever their `initialExpandToLevel` (no reader of older pages: the
  user's standing rule), and before G3 a Properties Apply re-synced the tree whatever changed; Python's copy of an old
  cube keeps a learned `groupDerived[].type` until that cube's next edit -- harmless, every reader learns it again.

**Ledgers.** S2 closed G3 V1 V2 V4 V5 P6 Z1 and shrank MARKERS 17 -> 16 (V4's marker); no ledger grew. The register
gained F1-F5, each closed in the commit that fixed it.

**Gates.** `//datacube/... //python:engine_test //python:model_test` (191 of 191) and `verify_app` as the container's
unprivileged user green at a147d256c; the keeper's soak at 60 seeds green (196 tests); the audit round's run below. CI:
not run -- still waiting on the user (a draft pull request, or a dispatch of `gate.yml` on `claude/datacube-query-base`).

## As built (S3, 2026-10-21)

**Built** (ae126e5fe, c328449ba, e47b3dbe5, 3c595311f, 90fb56efc, 6f89fd1be; the audit round 02622bf0d, 85d80333c): every
gesture on the board tells its commands and changes nothing of the page itself; its owner -- `PageApp`, or a cube
alone's `CubeApp` -- applies them to its `PageState` by the one step (page-commands.ts `applyToState`) and puts the board
at the page they make (`CubePage.sync`), which is also what undo, redo and another editor's commands use. A tile a
gesture makes (a grid's engine, a chart's panel) waits, pending, until its command places it; refused, it is let go.
Ids come from the state (`takenIds`) and what is on the board. Stack and arrange gestures tell `stack` / `arrange`
(P14); an editing grid is the board's alone and never touches the page's layout (P7); a cube alone's charts keep a page
state of their own and tell the same commands (P13); the board heals no selection -- a step that brings back a chart
with a selection its grid's filter lost has it put back by that grid's own `refilter` (G8). `follow()` keeps the
commands' page whole, its held ids and cubes. What is looked at survives an edit: the sheet shown, a stack's front tab,
focus, an open chart panel and its editing grid, Move to Sheet keeping the sheet shown. A guard
(state-guardrail.test.ts, S3) pins every place the board's structure changes and every place a page's state changes,
with why and how many times.

**Deviations from the plan's wording, each kept on purpose.** G8 is closed for the board only: the chart panel still
follows its cube by `followCube` on its own, and the screen still reconciles a selection against its grid's filter, both
told by no command -- a cube alone over rows its host names no source for has no cube in its page's state to follow
(the S2 audit's 7). The editing grid opens in a band of its own below its chart's, not beside it, so a gesture made
while it is open changes the page as it would with it closed (the audit's 4). A cube alone saves one cube: its added
grids, and the charts that read them, are in its state and its told commands but not in what it saves (F12, declared).
An undo puts the page at a whole step (N2), so a grid's own change made since is not carried into the steps (F15,
declared; the proper live-editing model of §8). The bundle budgets were raised with dated notes (the screen carries
page-commands.ts; the audit round's fixes).

**The independent audit** (15 findings) and what became of each:
- Fixed, each with a test that fails without it (run against the step's head), registered (rule 9) and closed: (1,
  high) a cube alone's state went stale as its grid's own changes landed -- F6; (2, high) a cube alone's last chart
  removed threw -- F7; (3) a cube alone gave an id out again after its board went -- F8; (4) a gesture with an editing
  grid open moved the page's shares, and an arrangement was told as a layout -- F9; (5) an edit made while `follow()`
  waited for a snap was lost -- F10; (6) a detached chart's Update was a board-side copy of `setChartCube`'s rule, and
  undone left the page mixed -- F11; (8) Go Live refused for a grid whose cube the page cannot hold -- F13; (9) a cube
  alone's added grid renamed to its id's number was put back -- F14; (11) a chart with no spec yet moved at each step,
  and a drag in progress ended at another editor's step -- F16; (12) a refused grid threw -- F17.
- Declared, with a test that pins what is true: (7) a cube alone's saved page is its state less its added grids --
  F12; (10) an undo of an unrelated step puts back a selection a grid's own edit took off -- F15 (not new since S2).
- Fixed as S3's own: (13) the guard pinned only where, not how many times, and missed the kept grids, the charts'
  links, the chart and grid maps, renames and spec sets -- now it pins each, with a count; (14) stale or overclaiming
  docs -- `sync`'s, page-app.ts's header, `onPageEdit`'s, the export (now the page's layout, each stack's front as
  shown), P7's and P13's register lines; (15) claims with no failing test -- `follow` keeping the ids its commands held
  and their cubes, a refused gesture letting go, a cube alone's ids, its last tile, P7 under a gesture: each has one.

**Ledgers.** S3 closed P7 P13 P14 G8 and the board's rule copies; L1_ROUTES 5, L8_KINDS 11, MARKERS 14; no ledger grew.
The register gained F6-F17, each closed in the commit that fixed it (F12 and F15 declared).

**Gates.** `//datacube/... //python:engine_test //python:model_test` (191 of 191) and `verify_app` as the container's
unprivileged user green at 85d80333c; the screenshot tour green (17 of 17), its editing grid below its chart. CI: not run -- still waiting on the user (a draft
pull request, or a dispatch of `gate.yml` on `claude/datacube-query-base`).

## As built (S4, 2026-10-21)

**Built** (the step's commits after 68ce957dc): a write to a page Python keeps is the version it was made at and the
edits it made there, each a command; the keeper (keeper.py `settle`) has DataCube apply them to the page as it is now --
headless `pageRebase`, nothing to combine at the writer's version, made again on top past it -- and keeps what they
make, or refuses them in DataCube's words, nothing kept (H1). Python's own page writes the same way (page.py `_commit`),
so the two editors' writes meet one rule. The one write that is no commands is a restore -- a page loaded (N1), an open
page's undo or redo (N2: `onRestore`, told by the owner's `moved`) -- kept only at the version it was made at (keeper.py
`restore`, headless `pageWritten(text)`); the tab's `#told/#seen` guess, `edits: null`, the keeper's refusal of a
no-command write and `pageWritten`'s null are deleted (H2, G12, P5). The keeper keeps the ids each version held and
tells the rebase the ones a write knew (`rebaseEdits` `known`): a page filter two writers made under one id is given out
afresh, never put in the other's place (H3). One held set: the keeper's, read by the tab with the page (`page.json`
`held`; `PageApp.restore`'s `held`), so neither side gives out an id the other has held (B12). On a page Python serves,
Open…, open in place and Blank Page put a page of the tab's own on screen and leave Python's as it is, said once -- a
blank page on its card (D1, P10). The keeper's soak (60 seeds) found F18, fixed with its test.

**Measured** (the step's last bullet: one write on a page of 60 grids, in the container, `LEGEND_LITE_WRITE_COST=1`): a
page edit 0.13s, a grid edit (its grid planned again) 0.36s -- each Python's call applied twice over (its own, then the
keeper's `settle`), growing with the page. The budget (test_keeper.py `WriteCost`): 1.5s and 3s, about ten times that,
for CI's slower runners.

**Deviations from the plan's wording, each kept on purpose.** Python's page applies its own commands as its call runs
(its handles read the page they make) and the keeper applies them again as it settles: one rule run twice, no rule
copied -- the measured cost is that; left for the final audit to judge. The engine's `serve_page` -- a page the host
replaces whole, which only tests serve (the engine's, and the browser harness's) -- is still a write of a page whole; it
is Y6, open, S5's. `L9_KEEPER` grew 6 -> 8 with a dated note: its two new entries are the restore writes S4's own bullet
names (N1, N2), both allowed, but rule 1 counts any growth as a ledger growing, so it waits on the user's sign-off as
S1's finding 7 does. The engine page's bundle budget was raised 500 bytes with a dated note.

**The independent audit** (8 findings; one more found reviewing S4's own code) and what became of each:
- Fixed, each with a test that fails without it (run against the step's head), registered (rule 9) and closed: (1, high)
  the tab left the page Python serves when its start opened nothing -- an empty page, a failed first read -- and
  followed it no more -- F19; (2, high) a grid over a source the host cannot base its edits on was taken onto a page
  Python serves and its every edit then refused -- F20: one rule of what a page on the host holds (headless `onHost`),
  asked by `gridBase`, `gridOpened` and the keeper of each cube a write adds or moves, which refuses it as it is added.
  The tab still offers New ▸ Data Source… on such a page; the grid it adds is shown and then taken off, the refusal
  said, until S5 brings every source to a page on Python (B7, X1-X5) -- a copy of the rule in the tab would be a twin;
  (3) a page filter no command made under its keeper, set again, became a second -- F21; (4) a follow on its way when
  the page was left threw, or put Python's page back with no channel -- F22; (5) B12 untested on the tab, its Python
  test unable to fail -- F23; (7) edits made while a write was on its way dropped silently when the keeper made another
  page of it -- now said -- F25; and the engine said a failure of its own while taking a write as no such page or as a
  refusal -- F27, found reviewing S4's own code.
- Declared, with a test that pins what is true: (6) a write from further back than the keeper keeps (64 versions) is
  refused -- F24; (7) an undo or a redo that lands while the tab follows the other editor is not written -- F25.
- Fixed as S4's own: (8) stale comments and docs -- F26; As built's claims made exact (B12's, P10's: Open… and open in
  place call the same `leaveServed` and have no test of their own).
- Checked and left: F18 changed `withoutConditions` to compare data, not text (`sameValue`). The other places that
  compare document data as text (the chart click's toggle, draftCommands and settingsPatch, the board's spec, selection
  and layout checks, band-board's layout, chart-spec's showAs) each compare values written in one key order -- one
  reader's, or the same code's (a chart's mark conditions are built in the order the filter reader writes them) -- so
  key order does not differ there; a cube document's filter, kept as read, against a command's, read by the filter
  reader, is the case F18 was.

**Ledgers.** S4 closed G12 P5 P10 H1 H2 H3 B12; MARKERS 9; the B7 marker's count 3 -> 2 (the rule in one place,
`onHost`). The register gained F18-F27, each closed in its fix (F24 declared, F25 in part).

**Gates.** `//datacube/... //python:engine_test //python:model_test` (191 of 191 at the step: the full run found
live-follow's page answer still said without the ids held, fixed in the step; 192 of 192 after the audit round, its
jsdom test of the app on a served page added) and `verify_app` as the container's unprivileged user green, each time;
the keeper's soak at 60 seeds and the screenshot tour (17 of 17) green at the step. Each audit fix's test was run
against the code without it and failed. CI: not run -- still waiting on the user (a draft pull request, or a dispatch of
`gate.yml` on `claude/datacube-query-base`).

**Next.** S5.
