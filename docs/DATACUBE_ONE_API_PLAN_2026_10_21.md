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
| **L1** grid state changes | every call in `datacube/src/app.ts` that changes the cube's state other than `#applyCommands` (`#change`, `#configure`, `#query`, `#owner.change`, public `change`, `applyConfiguration`, retry) | static, as `state-guardrail.test.ts` does |
| **L2** gestures | a catalogue of every gesture on the real PageApp + CubeApp (jsdom); for each: the commands told, replayed on the page before, give the page after, and no change happens with no command told | behavioural; ledger = gestures failing today |
| **L3** wire | every command a gesture tells survives its JSON writer and reader (what the host reads) | part of L2's run |
| **L4** Python whole writes | every `_unnoted()` call in `python/legend_lite/page.py` | static |
| **L5** Python funnel | every assignment to a Python page's document (`_doc =`, `_doc[...] =`) not taken from a headless answer | static |
| **L6** Python copies | the Python functions in audit §5, by name; fails when one is gone but still listed | static (a register: new copies are caught by L5 and review) |
| **L7** convergence | Python, headless and the tab run the same command vocabulary and must hold the same page, charts, selections and tree included; ledger = vocabulary items that diverge today | behavioural (extends `test_keeper.py` Converges and `live-channel.test.ts`) |
| **L8** capability matrix | every command kind and source kind x {UI producer, Python builder, JSON reader/writer, rebase arm, parity row} | static over the kinds' union types; ledger = missing cells |
| **L9** host writes | the kinds of write the keeper takes whole | Python test over `keeper.py` / `engine.py` |
| **L10** same rule | one parity test per behaviour difference (audit §6); ledger = rows still differing | behavioural |

Acceptance of step 0: every ledger green with today's entries, each entry naming an item id from §4; adding a dummy
`#change` call, a dummy `_unnoted()` and a dummy untold gesture each turns its ledger red (checked once, not committed).
No product change. Also: the program's line in `docs/IN_FLIGHT.md`.

## 4. The register

Every item, its step, its ledger. `[ ]` open, `[x]` closed (with the commit). Evidence: the audit, by section.

### Grid edits that are no command (audit §2, grid)
- [ ] **G1** expand / collapse a row → `open` command — S1, L1
- [ ] **G2** Collapse All → `collapseAll` — S1, L1
- [ ] **G3** Properties' tree effect (totals, expand level) → the tree rule inside `configure` — S2, L1 L7
- [ ] **G4** calculated-columns editor: edit and rename, references carried → `editCalculated`; the rename rule moved
  into `grid-commands.ts` from `snapshot.ts:803` + `config.ts:886-899` — S1, L1
- [ ] **G5** delete a calculated column (written twice today) → `removeCalculated` — S1, L1
- [ ] **G6** Filters window → `filter` — S1, L1
- [ ] **G7** chart click on a grid's chart → grid `refilter {remove, add}` + page `select {chart, conditions}`; the
  rule moved out of `app.ts:2741` and `cube-page.ts:1462` — S1, L1 L2
- [ ] **G8** restore heals a filter missing a chart's selection → the document kept consistent by G7's rule — S1, L2
- [ ] **G9** use a dimension → `group` — S1, L1
- [ ] **G10** `applyConfiguration` → deleted; its tests use `configure` — S1, L1
- [ ] **G11** public `change()` / `state` → removed or read-only — S1, L1
- [ ] **G12** grid undo / redo → N2 restore write — S4, L9
- [ ] **G13** an edit retried after signing in again → its commands kept and told — S1, L1
- [ ] **G14** a grid made while the page bar is folded → `showTitleBar` in the `addGrid` document — S1, L2
- [ ] **G15** standalone New ▸ Source cap rule (unreachable in the app) → deleted or shared — S1

### Telling exactly what landed (audit §2, after the grid table)
- [ ] **T1** a command refused on the committed state but kept on the pending one — S1, L2
- [ ] **T2** a presentation command told before a running transaction's — S1, L2
- [ ] **T3** a change made on top of an undo in flight — S1, L2
- [ ] **T4** Python's commands and the user's mixed during a follow — S1, L2

### Commands the host cannot read (audit §4)
- [ ] **C1** window, child-aggregate and explode columns in the `calculate` JSON reader and writer — S1, L3

### Page edits that are no command (audit §2, page)
- [ ] **P1** page name box → `renamePage` — S1, L2
- [ ] **P2** Save / Save As under a name → `renamePage` — S1, L2
- [ ] **P3** Snap / Go Live in the tab → `snap` / `live` — S1, L2
- [ ] **P4** Create a Class / Association / Data Space → model page commands (`modelInto`, `modelPin`, `modelKeep`)
  applied by `page-model.ts`'s functions — S1, L2
- [ ] **P5** page undo / redo → N2 restore write — S4, L9
- [ ] **P6** chart options told before the chart re-follows → falls out of S2 — S2, L2 L7
- [ ] **P7** Open in grid resets the chart's band shares → an editor tile never touches the saved layout — S3, L2
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
- [ ] **B2** a tile renamed to its made title stores no title: the rule inside `renameTile` — S1, L10
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
- [ ] **K3** calculated columns: edit, rename, remove — S6, L8
- [ ] **K4** group-level, window, child-aggregate, explode columns; the Show As and period presets — S6, L8
- [ ] **K5** expand, collapse, collapse all — S6, L8
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
