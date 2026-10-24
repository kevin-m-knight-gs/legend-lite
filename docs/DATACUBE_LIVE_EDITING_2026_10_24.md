# Live editing: the single writer's client side (D19), 2026-10-24

**The decision (the user, 2026-10-24).** D19 is built in its full form, not D23's smaller one: the page keeper's client
side made bulletproof to the standard that live shared editing of one page by several tabs would work, with no new
mechanism beyond what the keeper already promises. The bar is the proof: two real tabs and Python editing one page at
random, undo in the mix, converge and keep every edit's intent -- a gate, not a report (the one API plan's §0, reasons 3
and 4). What the bar does NOT ask for is said in §9.

**Where it fits.** docs/DATACUBE_ONE_API_PLAN_2026_10_21.md (the plan) D19, §8's "proper live-editing model", N2;
docs/DATACUBE_PAGE_KEEPER_2026_10_20.md (the keeper) §8-9. The plan's register gains items W1-W18 (§7), each closed
by the commit that builds it, with the test that fails without it. Every claim below has its `file:line` in §1,
checked against the code at `819a2e81` (main, 2026-10-24).

## 0. Why the full form, in one paragraph

The keeper is already a concurrent-writer server: `settle` is version-based optimistic concurrency with the rebase
done server-side by DataCube's one rule (`rebaseEdits`), N followers by id, 64 versions of steps kept, colliding ids
renamed. Python, a tab and a notebook widget are already three writers on one page. The tab's client breaks that
promise in three ways, each a bug a single notebook user meets today: a combined write reopens the page whole (every
grid rebuilt and queried again, the view state lost); an edit made in the 500 ms settle window before that reopen is
dropped unsaid (H-11); and undo is a whole-page snapshot the keeper refuses whenever the other editor has moved the
page since (N2) -- run a cell while the tab is open and the next Ctrl+Z is refused and the screen reset. D23's
smaller form fixes the first two and keeps the third. The third is the one thing that makes shared editing fail, and
it is the plan's own §0 reason 3 ("they accepted rare-enough exceptions") written down as N2.

## 1. Homework: the code as it is

**The keeper and the wire.**
- `python/legend_lite/keeper.py` `settle` (191-221): every write is `combine` (headless `pageRebase`), at the writer's
  version with nothing to combine, behind it made again on top; `Settled(version, combined, renamed)` (73-80).
  `restore` (223-230) is the one write of a page whole, kept only at the version it was made at. `changes` (232-243)
  gives the edits since a version, or None when a version since was moved by no command (born, restored) or is past
  `STEPS_KEPT`.
- `python/legend_lite/engine.py` `_page_said` (1659-1712): POST `page.json` reads `{edits}` or `{restore}`; `restore`
  goes through headless `pageWritten`, `frames.unadmitted`, `keeper.restore`; answers `{version, combined, renamed}`
  (200), 409 for every `Refused`, 422 only on the restore path, 500 otherwise. GET `_page` (1632-1657): `{version,
  page, held}` or, with `since`, `{version, commands}`.
- `datacube/src/services/python-host.ts` `livePage` (652-708): `report` reads `version` and `combined` only -- never
  `renamed` -- and throws on any `!answer.ok`; `datacube/src/app/services.ts` `LivePage.report` (297-317),
  `LiveWrite = {edits} | {restore}` (292-294).
- Python has no undo or redo (page.py: no `undo`, `redo`, `history` but in docstrings); `Page._commit` (1335-1368)
  restores only on `Page.load` (N1, `_unserved is None`, set by `_unnoted` alone, 1999-2001); after a combined write
  it takes the keeper's page whole (`_renamed`, `_version = 0`, `_pull`).

**The tab's channel.**
- `datacube/src/app/live-channel.ts`: `#edits` since the last write (29); `restored()` (57-59) makes the next write
  the page whole; `#write` (103-140) on `combined` sets `shown = undefined` and reopens the page, saying what it drops
  (the edits made while the write was on its way); on a refusal, the same reopen with the keeper's words.
- `datacube/src/app/datacube-app.ts` `openLive` (1404-1440): `editor.read()`, `openSaved` whole, then
  `channel.opened(version)`, which clears every pending edit (live-channel.ts 79-83). `followLive` (1449-1497): the
  commands since `shown` applied by `applyLive` inside `following()`; pending edits are not cleared and are written at
  the new version. The poll (1512-1564) chooses `followBy`: commands, or `openLive` when a frame moved with the page,
  or the frames alone (`frameAgain`, then `refresh()` of each grid over the frame -- a refresh with no reopen exists,
  1541-1553).
- Save (`saveTo`, 886-911) saves `pageNow(name)`: the screen's page (H-5).

**The page.**
- `datacube/src/page-commands.ts` `applyPageCommand` (357-584): every arm's effect is in §3's table. `freshId`
  (237-243) REFUSES a given id the page has held ("an id the page has held already"); `rebaseEdits` alone strips such
  an id and gives a fresh one (page-rebase.ts 148-183). `layout` is refused unless its tiles are exactly the sheet's
  (523-527); `arrange` with an `order` is not checked (bands.ts 624-676).
- `datacube/src/page-state.ts`: steps are whole drafts (`#past`, `#future`); `reachable` (76-80) is every id in
  them, which page-app.ts `recoverable` (1002-1006) and sources.ts `dropUnread` (414-437) read to keep a removed
  grid's maker and tables while an undo can bring it back (`#gone`, 185, 1081-1088).
- `datacube/src/page/page-app.ts` `#edit` (933-946): apply (`applyToState`), tell (`onPageCommand`), sync -- a page
  command is told synchronously at gesture time. `#move` (1044-1066): the step's draft put on screen by `#syncTo`,
  `onRestore` told, the name, record and model kept across (F52, F74), a brought-back chart's selection put on its
  grid by that grid's own `refilter` (G8, `selectionRefilters`). `follow` (1013-1042): applies another editor's
  commands, then `this.#state = new PageState(made)` -- the page's history starts again. `restore` (783-846) rebuilds
  the board whole.
- `datacube/src/page/cube-page.ts` `sync` (549-734): sheets, tiles added (a maker required, 612), removed, moved,
  titles, specs, selections, which grid a chart reads, layouts, order -- keeping view state (tests at
  cube-page-shell.test.ts 1262-1334); `to.cubes` is never read: a changed cube of an existing grid is not applied.
- The chart click (`#select`, 1679-1692): the grid's `refilter` runs first and is told when its query lands (app.ts
  1094-1097, 1116-1119); the page's `select` is told at once (1692). Two schedules (F97).

**The grid.**
- `datacube/src/cube-state.ts` `CubeState = {snapshot, configuration, tree}` (22-26); the snapshot carries folded
  copies of the configuration (`applyToSnapshot`, config.ts 692-745: a column's kind from the configuration when set,
  else the snapshot's own kept).
- `datacube/src/grid-commands.ts` `draftCommands` (205-236) diffs rows, pivot, sorts, a column's kind, aggregate and
  weight, and the configuration key by key; NOT the filter, the calculated columns, the tree, the learned types.
  `configure` refuses a cleared `kind` (325-327). `calculate` appends (137-141); `editCalculated` keeps the place
  when the level stays, else appends (142-153); `open(path, true)` opens the ancestors, `open(path, false)` closes the
  descendants (tree.ts 218-245); `collapseAll` resets `expandTo` to 0 and empties `closed` with the configuration's
  `initialExpandToLevel` untouched (tree.ts 248-250), and a `configure` giving the same level moves nothing (351-358):
  no command puts a collapsed tree back.
- `datacube/src/state-owner.ts` `#land` (380-389) is the one place the committed state moves with the history; it
  has `from` (the committed state before) and `state` (after); the events carry `moved: boolean` only (65-75).
  `src/app.ts` `#followCommands` (1090-1129): on a moved commit, `#untold` is dropped and `onRestore` told (T3: a
  transition an undo is part of tells no command). The other editor's commands (`applyGridCommands`, 1024-1034) are
  never told and clear the grid's history.
- The keeper applies ONE grid command per `gridApply` (page-rebase.ts 163), each an open and a compile
  (headless/grid.ts 875-898, 998-1011).

**Tests and ledgers.**
- `datacube/test/one-api-gestures.test.ts`: `judged` (396-433) allows a whole-page write only for a gesture marked
  `restores` (five: a page edit undone, undone and redone, a grid edit undone, a presentation edit undone, 565-609);
  the editor stand-in answers `{version: at+1, combined: false}` (42-52).
- `datacube/test/live-channel.test.ts`: `Keeper` (317-352), a TypeScript keeper over the real `rebaseEdits` (its grid
  applier handles `group` alone), used by one real `PageApp`; the undo tests pin restore writes (422-497).
- `python/tests/test_keeper.py` `Converges` (759-838): one `OpenPage` (DataCube headless) and Python, 8 seeds x 30
  steps, four interleavings, an `Intent` model checked after every step; no undo, never two open pages.
- `python/tests/test_one_api.py` `L9_KEEPER` (269-276): six writers, `engine.py:Engine._page_said:restore` the N2
  one; `CEILINGS` (52). `datacube/test/one-api-ledger.test.ts`: `L1_GRID` `#undo`/`#redo` "N2" (123-124); `L11`
  (512-589): a rule function a screen file calls is exported headless and asked by Python, or listed in
  `SCREEN_OWN_MAKERS` (`selectionRefilters` is); the register test (1076-1087), `ITEM` `[GTCPYVHZBXKA]`.
- `datacube/test/state-guardrail.test.ts` pins the page state's sites (`#move:this.#state.undo(`, `follow:this.#state
  = `, ...) and the board's (`BOARD_SITES`).
- `//datacube:python_engine_test` (BUILD 1104-1148; test/python-engine/python-engine.mjs): a real Python engine and
  a real Chromium; one tab follows one Python page (556-796); never two editing tabs.

## 2. The model

Three editors' kinds of change meet one function on both sides. After D19 there is no other kind.

```
an edit of mine      gesture -> commands -> applied here -> told -> written at `shown`
another's edit       the keeper's steps since `shown` -> applied here (applyLive)
my undo / redo       the step's INVERSE commands (or its forward ones) -> applied here -> told -> written
my screen converging the keeper's steps and my own rebased ones, replayed over the page at `shown`
```

The tab holds **confirmed** (the page as the keeper has it at `shown`), **sent** (the edits of the write on its way)
and **pending** (the edits made since). The screen shows confirmed + sent + pending at every moment, by construction:
page commands are applied as they are told, grid commands are told as they land, and `pageNow` reads each grid's
committed state (app.ts 3090-3103).

**A write answered at the version it was made at** (`combined: false`): confirmed = the page as it was sent, shown =
the version. Nothing on screen moves.

**A write combined** (the keeper made another page of it: it moved meanwhile, or an id was given out afresh): the
answer carries what the tab needs to converge without a read and without a reopen -- `since` (the other editors'
steps between `shown` and the version before mine), `edits` (mine as the keeper applied them: rebased, renamed),
`renamed`, and the keeper's page's `hash`. The tab, inside `following()` so nothing it does counts as its own:
1. puts the screen back at confirmed: the board by `#syncTo(confirmed, screen)` (view state kept, a tile my sent edit
   removed made again from `#gone`), each grid whose cube differs by `applyGridCommands(gridBetween(now, confirmed))`,
   the state `keep`'d at confirmed, and every id the page gave out since `shown` -- the sent edits' and the pending
   ones' -- given back (`PageState.ungive`): the keeper never held them under those names;
2. applies `since` by `applyLive`, as another editor's (its ids honoured: none is held here now);
3. applies `edits` by `applyLive` as its own: the grids record them as steps said, never told (app.ts `said`, F91);
   the page's history keeps its steps, renamed (§4);
4. shown = version, confirmed = the page on screen, which must now hash to the keeper's (§6);
5. applies pending again through the ordinary edit path, each renamed by `renamed` (`renamedCommand`; a grid's
   envelope by its tile), so each is told again and written next; one that no longer applies (its tile gone) is
   dropped and said once in the status line.

**A write refused** (409: an edit does not apply where the page now is; the host refuses what it makes; from further
back than the keeper keeps): the same steps with no `edits` -- the sent batch is not the page's and is said with the
keeper's words -- over `since` read by `changes(shown)`; when `changes` answers the page whole (past 64 versions),
the one remaining reopen, said.

**A follow** (the other editor moved: the poll says a version past `shown`): first the channel's pending edits are
written (`flush`: the debounce cut short, the write awaited, converging if combined), and the screen is waited for
to be quiet (no grid's change in flight: a click's query landed and told), THEN the steps since are applied. So a
follow never meets an un-rebased edit on screen, and another editor's removal never lands under a gesture still
running (F90): the gesture lands and is told first; written behind the removal, the keeper refuses it in words and
the screen converges as above -- "two editors at once, the later one told".

**One schedule for telling** (F97). A gesture's commands are told together, when all of them have landed: page
commands commit at once and are told at once; a grid's are told when its query lands; a gesture of both -- a chart
click (`select` and the grid's `refilter`), an undo that brings back a selected chart (`addChart`, `select` and the
grid's `refilter` by G8's rule) -- tells its page commands when its grid commands have landed, through `#edit`'s
companions. The keeper never holds a selected chart whose grid's filter lacks the selection between two writes.

**Save** on a served page writes first (`flush`), then saves the page on screen, which is the keeper's (H-5).

**Reopen** survives for exactly two causes, each said: a `rebuilt` (the host re-read a cube over its source as it is
now: no follower applies one, live-follow.ts 65-66; and the frame's model changed) and a write from further back than
the keeper keeps. A frame that moved WITH the page is followed by commands, then every grid over it refreshed, as a
frame that moved alone is today (W16); `followBy`'s `open` arm goes. Before any reopen, pending edits are written or
refused-and-said: no edit is dropped unsaid (H-11).

## 3. Undo produced by commands

Undo and redo become edits like any other, through the same functions on both sides, so N2 -- the whole-page restore
-- goes. Two total diff functions make it so.

**`gridBetween(from, to): GridCommand[]`** (a new module, `src/grid-between.ts`; `draftCommands` becomes its core):
the commands that take a grid from one state to another, over the SAVED form (the cube document's query without the
learned types, its configuration, its tree), in dependency order: calculated columns added (so they can be grouped),
column kinds, groups, pivot, filter, the calculated columns edited, the configuration patch, sorts, the tree, and
calculated columns removed last. Two vocabulary changes make it total:
- `calculate` and `editCalculated` gain an optional `before` (the name of the calculated column of that level the
  column goes before; absent: last), so a column removed from the middle comes back in its place and one moved
  across levels goes back to its place.
- a new grid command **`tree {open, showTotals, expandTo?, closed?}`**: the tree set whole, in the saved tree's own
  shape (cube-document.ts 217-224). `open` and `collapseAll` stay as the gestures' deltas; a collapsed tree is put
  back by `tree`, which nothing else can do.
What the saved form has that no command can set -- a column's kind or pivot exclusion CLEARED (configure refuses
`kind: null`; the snapshot keeps its folded kind) -- `gridBetween` sets instead to the folded value `to` shows:
the same grid, the setting written down. So the property is semantic, not textual: `apply(from, gridBetween(from,
to))` equals `to` by `stateKey` after both are written and read back (cube-round-trip.test.ts's `unlearned`), over
seeded random edit sequences through every kind (the generator of cube-round-trip.test.ts, moved to a shared test
module and completed: sorts, clearSort, addFilter, refilter, editCalculated, removeCalculated, group-level calculate,
open, collapseAll). Exactness between the two editors follows from both applying the same commands (§6).

**`pageBetween(from, to, scope): {commands, stands}`** (`src/page-between.ts`): the page commands that take a page
from one to another, in an order every arm accepts: sheets added; charts removed, then grids removed; grids added
(their cube document; a frozen chart left detached relinked by `followGrid`); charts added, re-pointed
(`followGrid`), their spec (`setChart`) and selection (`select`) and title (`renameTile`); tiles moved
(`moveTile`); page filters removed and set; each sheet's `layout` whole (after the tile sets agree, so the arm's
check passes); sheets removed, renamed, reordered; copies (`live`, `snap`). A tile the diff adds carries no id --
`freshId` refuses a held one -- and `stands` says which tile of `to` each added one stands for. `scope`: `history`
(the page's structure: not the name, the saved record or the model, kept across an undo as today, F52, F74, D5;
not the cubes' contents, which are the grids' own steps) or `whole` (everything but a model gone or a source
changed, which it refuses: §2's reopen). Property: `apply(from, pageBetween(from, to))` equals `to` up to the fresh
ids, over the saved-page corpus and seeded edits (page-rebase.test.ts's generator widened to every kind).

**The page's history is commands** (`page-state.ts`): a step is `{label, forward, inverse, brings}` -- the commands
as applied, their inverse by `pageBetween(after, before, 'history')` computed when the step is made, and for each
`addGrid` of the inverse the old tile it brings back (`stands`). `undo` applies the inverse to the page as it is NOW
and returns the commands applied (fresh ids given); `redo` the forward ones; both move the stacks only when the
commands applied (one refused -- its tile taken off by another editor since -- is a dead step: dropped, said, the
stacks moved past it). `PageApp.#move` applies, tells (W9's schedule), syncs: the same `#edit`. A tile brought back
is a new tile under a fresh id (B12: ids never reused), made from its old maker and start: `brings` pairs the fresh
id with the old one for `#remake`; `reachable` is every `brings` across the history. The keeper's `renamed` renames
every step's commands (`renamedCommand`, page-rebase.ts 55-102), so a redo after a renamed undo names the tile the
keeper knows. `onRestore` goes.

**The grid's undo is produced by commands too** (`state-owner.ts`): `StateRules` gains `toward(from, to): S`, the
state reached from `from` by this kind's own edits toward `to` -- the cube's: `apply(from, gridBetween(from, to))`
-- and `#history` moves to `toward(committed, target)` in place of `target`, so the screen's state after an undo is
what the commands make, as the keeper's will be. The move's `committed` and `presentation` events carry `from`
(the committed state before `#land`); CubeApp tells `gridBetween(from, committed)` where it told `onRestore` (T3
ends: a transition an undo is part of tells its commands; `#untold` is dropped as today, the diff covers a change
made on top of the move). The in-flight rules stay (undo cancels the person's change in flight; two presses, two
steps). A refused move retried re-runs the same `toward` state.

**History across another editor's change (W13).** The page's history survives: `follow` keeps the state's steps
(`keep`, no `new PageState`); an undo past the other editor's change applies my inverse to the page as it is, a dead
step dropped and said. The grid's history still starts again when another editor edits THAT grid (app.ts 1032,
kept): a grid is the unit of a person's own steps, and nothing lies between them but their own -- today's behaviour,
declared (§9).

**The named semantic.** An undo puts back what its step changed, as the step saw it: a `group` back to the rows
before, a `layout` back to the arrangement before. Another editor's change to the same aspect since is put back
with it -- the person sees it and can redo. This is set semantics, the one `rebaseEdits` already has for two
writers' edits of one aspect (last writer wins), and the one every coarse-grained collaborative editor has (a
property set back). A step whose target is gone is dead, never guessed at.

## 4. The keeper's side

- `Settled` gains `since` (the other editors' steps between the writer's version and the version before its own, or
  None when one was moved by no command or is no longer kept), `edits` (the writer's as applied) and `hash`; the
  engine's 200 carries them (`since`, `edits` only when combined). `restore` and its branch in `_page_said`,
  headless `pageWritten`, `LiveWrite.restore` and `LiveChannel.restored` are deleted: the one write of a page whole
  left is Python's `Page.load` (N1), `L9_KEEPER` 6 -> 5 with a dated note; N2 leaves the plan's named exceptions.
- `rebaseEdits` applies a grid's consecutive commands in ONE `applyGrid` call (one open and compile per run, not per
  command): the same reduce, the same refusal of the whole, each edit still listed as applied. An undo told as a few
  commands costs one compile.
- Every version keeps the SHA-256 of its page's text (`pageToJson`, DataCube's one writer of a page), from the
  `combine` answer's text, from `pageNew`'s at birth, and from a new headless `pageText` at `Page.load`.

## 5. Python

- `grid.calculate(..., before=None)` and `grid.edit_calculated(..., before=None)` carry the new field (the generated
  builders regenerate from `GRID_COMMAND_FIELDS`, D17).
- `grid.tree(open=[...], totals=None, expand_to=None, closed=())` builds the `tree` kind (L8: every kind made by
  both sides). **A name put to the user (D6, D21):** proposed here, built under it, renamed on the user's word.
- Python gets no undo: not asked, a Python user re-runs a cell (§9).
- `python/tests/test_page.py`'s `Live` tests write edits where they wrote restores (eleven tests).

## 6. Proof

- **Unit.** `gridBetween` and `pageBetween` property tests (§3); `tree`, `before` through JSON and back and in the
  parity rows; `PageState` with command steps (dead steps, renames, `brings`); `StateOwner.toward`; `rebaseEdits`
  batching (the same page, fewer calls, a refusal still whole); the channel alone (converge on combined, on refused;
  flush before follow; pending renamed; nothing dropped unsaid).
- **Gestures (L2).** one-api-gestures.test.ts: the five `restores` gestures become ordinary told gestures (`judged`'s
  restore arm goes); new gestures: a chart click undone, a removed grid undone (a new tile, its maker's), an undo
  past the other editor's page change, a dead step.
- **Convergence, TypeScript.** `live-channel.test.ts` grows the two-editor soak: TWO real `PageApp`s (jsdom, real
  `CubeApp`s over the fixture engine) on one TypeScript keeper (`Keeper`, now answering `since`, `edits`, `renamed`,
  `hash`, its grid applier the real `applyGridCommands` over the cube document), interleaved at random with Python's
  stand-in writes, undo and redo in the mix, each tab's writes delayed at random so writes combine and get refused;
  after every quiet point all three hold one page (text), and an `Intent` model (as test_keeper.py's) holds what
  every landed edit said. The seeds run in CI; a failure names its seed.
- **Convergence, Python.** test_keeper.py `Converges`: the `OpenPage` learns undo and redo (headless `pageBetween`,
  `gridBetween`: the same functions the tab uses), and a second `OpenPage` joins the mix; the two-thread test runs
  two open pages.
- **The live seam.** python-engine.mjs: two tabs on one `ll.Page` (and the widget), edits and undos on each, Python
  between; convergence by `py-page-read` and the DOM; every write's `hash` check passing (the browser harness fails
  on a status line saying otherwise).
- **At run time (W17).** The tab compares its page's hash with the keeper's on every write's answer; a mismatch is
  the one message that must never show -- it reopens the page as the keeper has it and says "the page here differed
  from its keeper's: opened again" -- and the soaks assert it never shows. The tenet "the two editors never hold two
  pages" is checked at every write, not only in tests.
- **Mutation.** The scratch mutation harness over the two diffs, the channel's converge and the owner's `toward`, as
  in the keeper's step 1b; a surviving mutant is a missing test.

## 7. Items (the register's W)

- **W1** `gridBetween` total over the saved form; `tree`; `before` on `calculate` / `editCalculated`.
- **W2** `pageBetween` total over the page, `history` and `whole` scopes, `stands`.
- **W3** the grid's undo produced by commands (`toward`), told as `gridBetween(from, committed)`; T3 ends.
- **W4** the page's history is commands: `forward`, `inverse`, `brings`; dead steps; renames follow.
- **W5** the restore write deleted end to end; `L9_KEEPER` 6 -> 5; N2 leaves the exceptions; the `restores`
  gestures become told gestures.
- **W6** the write's answer carries `since`, `edits`, `renamed`, `hash`; `Settled` likewise.
- **W7** the tab converges on a combined write without a reopen: confirmed, sent and pending; ids given back;
  `since` then `edits` replayed; pending re-applied renamed, dropped ones said.
- **W8** a refused write converges the same way, the batch said.
- **W9** one telling schedule: a gesture's page commands told when its grid commands land (F97); the chart click; an
  undo's selection refilters.
- **W10** a follow writes pending first and waits for the screen to be quiet (F90).
- **W11** Save on a served page writes first (H-5).
- **W12** no edit dropped unsaid before any reopen (H-11); the two reopen causes said.
- **W13** the page's history survives another editor's change; the grid's starts again (declared).
- **W14** `rebaseEdits` applies a grid's run of commands in one call.
- **W15** the two-editor soak, TypeScript and Python, and two tabs in the browser harness, as gates.
- **W16** a frame moved with the page: followed by commands, then refreshed; `followBy`'s `open` goes.
- **W17** the hash check at every write; the message that must never show.
- **W18** H-13 closed by W5 (an undo judged by every rule a write is, its ids held).

Ledgers that move, each with a dated note: `L9_KEEPER` 6 -> 5 (shrink); `L1_GRID`'s `#undo` / `#redo` entries'
reasons (the owner's moves are told); `BOARD_SITES` / `STATE_SITES` as the sites change; `ITEM` gains `W`; `ALL`
gains W1-W18 as each closes; `SCREEN_OWN_MAKERS` gains `pageBetween` (an undo's inverse is the screen's own: Python
has no history); the gestures ledger's `restores` goes; the screen bundle's budget if the diffs push it over (said
in As built with the number, for the user's sign-off, D9).

## 8. Order

Four slices, each landing green on the full local set before the next, each with its tests first:
1. **W1, W2** -- the two diffs, pure, property-tested; the vocabulary (`tree`, `before`) through readers, writers,
   parity and Python's builders.
2. **W3, W4, W5, W18** -- undo as commands on both owners; the restore write deleted; ledgers moved.
3. **W6, W7, W8, W9, W10, W11, W12, W13, W14, W16, W17** -- the keeper's answer; the client's converge; the schedule;
   the follow's order; Save; the hash.
4. **W15** -- the soaks as gates; an independent audit against the items; As built.

## 9. Out of scope, said

- Presence, cursors, who-is-editing, a conflict UI: the proof is two tabs converging with no edit lost, not a
  collaboration product.
- CRDTs or OT: a page is small, its edits coarse, one keeper per page; optimistic concurrency with the rebase done
  by one rule server-side is the right model here and is kept.
- A keeper outside Python: two tabs on a page of a tab's own have no shared host; a keeper in the warehouse server
  or the cube store is a separate decision. The proof needs none: two tabs on one `ll.Page`.
- Python undo (§5).
- A copy let go cannot be snapped back: an undo of Go Live after the keeper let the copy go is refused in words, Snap
  again the way back (the keeper's Z17 rule keeps a copy while a page at a version that reads it is open, which the
  undo window does not extend).
- Transforming a grid's own history against another editor's change to that grid: its history starts again (W13).
