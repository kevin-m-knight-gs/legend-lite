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
- [ ] **G3** Properties' tree effect (totals, expand level) → the tree rule inside `configure` — S2, L1 L7
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
- [ ] **G8** restore heals a filter missing a chart's selection → the document kept consistent by G7's rule — S3, L1 (moved
  from S1: a page step restores views and sheets, not the grids' documents, so the heal stays until S3 restores the
  board by commands; since S1 it goes through G7's rule, the grid's `refilter`, told)
- [x] **G9** use a dimension → `group` — closed S1: `#useDimension` (no longer public) builds `group`
- [x] **G10** `applyConfiguration` → deleted; its tests use `configure` — closed S1: a host edits a grid by
  `CubeApp.edit(commands, label)` (the screen's own path, told); the gesture "a grid's settings set by its host" passes
- [x] **G11** public `change()` / `state` → removed or read-only — closed S1: `change()` and the `controller` getter gone;
  `state` is `CubeStateView` (busy, historyDepth); a refresh and a debug print are `refresh()` and `printQuery()`; L1
  pins that neither the owner nor the controller is handed out again
- [ ] **G12** grid undo / redo → N2 restore write — S4, L9 (closed in S4: its L1 entries become item-less N2 entries, held by
  L9's test that a stale restore is refused)
- [ ] **G13** an edit retried after signing in again → its commands kept and told — S1, L1 (a retry stays a retry: S1 closes
  it by a behavioural test written first)
- [x] **G14** a grid made while the page bar is folded → `showTitleBar` in the `addGrid` document — closed S0: not a gap (the
  `addGrid` document carries the title bar as the grid was made; the gesture "a grid added while the page bar is folded"
  passes told, wire and replays)
- [x] **G15** standalone New ▸ Source cap rule (unreachable in the app) → deleted or shared — closed S1: shared, one
  rule (cube-document.ts `freshConfiguration`, capped by `gridCapped`); L1 pins no second spelling

### Telling exactly what landed (audit §2, after the grid table; each a marker until S1 writes its failing behavioural test first)
- [ ] **T1** a command refused on the committed state but kept on the pending one — S1, L2
- [ ] **T2** a presentation command told before a running transaction's — S1, L2
- [ ] **T3** a change made on top of an undo in flight — S1, L2
- [ ] **T4** Python's commands and the user's mixed during a follow — S1, L2

### Commands the host cannot read (audit §4)
- [x] **C1** window, child-aggregate and explode columns in the `calculate` JSON reader and writer — S1, L3 (closed S1:
  `calculatedFrom` reads every column kind strictly; grid-commands.test.ts round-trips each; the Show As gesture's wire passes)

### Page edits that are no command (audit §2, page)
- [ ] **P1** page name box → `renamePage` — S1, L2
- [ ] **P2** Save / Save As under a name → `renamePage` — S1, L2
- [ ] **P3** Snap / Go Live in the tab → `snap` / `live` — S1, L2
- [ ] **P4** Create a Class / Association / Data Space → model page commands (`modelInto`, `modelPin`, `modelKeep`)
  applied by `page-model.ts`'s functions — S1, L2
- [ ] **P5** page undo / redo → N2 restore write — S4, L9 (closed as G12)
- [ ] **P6** chart options told before the chart re-follows → falls out of S2 — S2, L2 L7
- [ ] **P7** Open in grid resets the chart's band shares after a resize → an editor tile never touches the saved layout — S3,
  marker (jsdom drags no resize; opening an editing grid on even shares writes nothing, a gesture checks that)
- [ ] **P8** Update a detached chart from its editing grid → `setChartCube` — S1, L2
- [ ] **P9** restore drops views it cannot open → kept in the document as unavailable tiles, never dropped — S1, L2
- [ ] **P10** Open…, in place, Blank Page on a served page → a new page (D1) — S4
- [ ] **P11** the tab reopens cubes over frames as they are now → `rebuilt {cube, document}` page command made by one
  side (with Y2) — S1, L2 L7
- [ ] **P12** `ll.show(df)` page unkept; a frame's column change loses grid edits → a kept page of one grid — S5, L10
- [ ] **P13** charts on a cube alone (embedded CubeApp) tell nothing → the same state and commands — S3, L2
- [ ] **P14** stack and arrange gestures told as a whole `layout` → told as `stack` / `arrange` — S3, L2

### Python writes that are no command (audit §2, Python)
- [ ] **Y1** `_into` / `_keep` → P4's model commands — S1, L4 L5
- [ ] **Y2** `_reopened` → `rebuilt`; the take-or-not rule moved to TypeScript — S1, L4
- [ ] **Y3** `_kept` with notes → `rebuilt` — S1, L4
- [ ] **Y6** `serve_page` works out reads in Python → `pageReads`, or deleted — S5, L5

### Derived state (audit §3)
- [ ] **V1** a following chart re-followed when its grid changes, by one rule on every path — S2, L7
- [ ] **V2** a chart's selection reconciled with its grid's filter, on every path — S2, L7
- [ ] **V4** group-level column types written the same by both sides (spike first: type at compile, or stop storing
  run-derived types) — S2, L7
- [ ] **V5** expand level and the groups closed under it saved and read back — S2, L7

### The host (audit root cause R2)
- [ ] **H1** the keeper applies a write's commands and keeps what they make — S4, L9
- [ ] **H2** the no-command path deleted: the tab's `#told/#seen` guess and `edits: null`, the keeper's refusal for
  it, `pageWritten`'s null — S4, L9
- [ ] **H3** the ids a page has held come from the applied commands (an id given and removed within one write is never
  given again) — S4, L9

### Python's copies of TypeScript rules (audit §5)
- [ ] **Z1** `_with_cube` — S5, L6
- [ ] **Z2** `_ids` — S5, L6
- [ ] **Z3** `_into` / `_keep` (with Y1) — S1, L6
- [ ] **Z4** `_class` action — S5, L6
- [ ] **Z5** `_facts` association uniqueness — S5, L6
- [ ] **Z6** Create a Data Space orchestration — S5, L6
- [ ] **Z7** `model.py` (D4) — S5, L6
- [ ] **Z8** `Page.grid`'s command order → TypeScript builds a grid's commands — S5, L6
- [ ] **Z9** join input names and placement → `inputOf`, placed near its first input — S5, L6 L10
- [ ] **Z10** take-a-reopened-cube rule (with Y2) — S1, L6
- [ ] **Z11** command-or-whole rule in `_kept` (with Y3) — S1, L6
- [ ] **Z12** filter value conversion and pre-checks → the TypeScript reader — S5, L6
- [ ] **Z13** stack needs two tiles (duplicate check) — S5, L6
- [ ] **Z14** frames-only rule (D3) — S5, L6
- [ ] **Z15** a snapped grid's base and refusal → exported from headless — S5, L6
- [ ] **Z16** snap refusal sentence from one TypeScript function (N3 keeps the values) — S5, L6
- [ ] **Z17** copy ownership: one model (the keeper's), `HostSnaps` adopt / handOver and `_claimed` gone; keeper
  known limit 6 closed — S5, L6
- [ ] **Z18** the saved-page record — S5, L6
- [ ] **Z19** headless exports Python never calls (`pageFilter`, `joinQuestions`) used by S6's API, stale comments
  fixed — S6, L8

### Same job, different rule (audit §6)
- [ ] **B1** page filters reach Python's `view()`, `to_pandas()`, `export()` (headless through `gridLens`) — S5, L10
- [x] **B2** a tile renamed to its made title stores no title: the rule inside `renameTile` — closed S1: renameTile drops a
  grid's title equal to `gridTitle(id)`; the screen sends what was typed (page-commands.test.ts)
- [ ] **B3** one default page name — S5, L10
- [ ] **B4** saving under a name renames the page, on both sides — S5, L10
- [ ] **B5** one page-file rule — S5, L10
- [ ] **B6** empty pages allowed on both sides (D2) — S5, L10
- [ ] **B7** sources on a Python page (D3, with X1-X5) — S5, L10
- [ ] **B8** one rule for a detached chart going live — S5, L10
- [ ] **B9** one new-page skeleton (`pageNew` for the tab's Blank Page too) — S5, L10
- [ ] **B10** a frame's column change keeps grid edits everywhere (with P12) — S5, L10
- [ ] **B11** Join on both sides over every source kind — S5, L10
- [ ] **B12** ids held (with H3) — S4, L10
- [ ] **B13** one saved record per page: the record's id kept in the page document, so both sides save to it — S5, L10
- [ ] **B14** one "nothing to save" wording — S5, L10

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
- [ ] **K7** a chart's selection; page filters — S6, L8
- [ ] **K8** sheets: reorder, remove — S6, L8
- [ ] **K9** drill a chart, and back up — S6, L8
- [ ] **K10** Copy of Grid — S6, L8
- [ ] **K11** a chart's Open in grid and Update — S6, L8
- [ ] **K12** open a saved page by id — S6, L8

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
Items: K1-K12, X4, Z19. A Python method for every command kind and source kind; the names agreed with the user before
building. Size: about 1.5k lines.

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
