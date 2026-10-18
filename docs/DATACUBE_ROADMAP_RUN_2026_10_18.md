# The DataCube roadmap run (2026-10-18)

The user, 2026-10-18: "run the whole roadmap in this session with each phase audited and stacked on next here, and do
not stop until whole roadmap is done; if you can't figure something out or don't know what to do, skip it, make a
note and we'll come back to it at the end."

This file is the run's ledger: the order, each phase's branch and state, and every item skipped with why. It is kept
on the newest branch of the stack and updated as each phase lands on its branch.

## How each phase is run

- **Its own branch, stacked on the one before**, pushed with no PR: CI is contended, and a branch with no PR runs no
  CI (the user, 2026-10-18). Nothing goes to `main`; nothing merges without the user.
- **A short design note first** where the phase has choices to make, then steps, one commit each, each with its
  tests (a fix with a test that fails without it).
- **Checked locally before the push:** the suites the phase touches (`//datacube/...` and `//python/...` for the
  DataCube line; `verify_app` as an unprivileged user).
- **Audited:** an independent reviewer that wrote none of it reads the phase's diff before the next phase starts;
  its findings are fixed, or answered here.

## The order

| # | Phase | Branch | State |
|---|---|---|---|
| — | #57: header sorting, pivot totals | `claude/epic-rubin-ay7upl` | merged |
| — | Command program stage 1a: the test foundation | `claude/datacube-command-tests` | done, audited |
| — | Command program stage 1: the grid half | `claude/datacube-commands` | done, audited |
| 1 | Command program stage 1: the page half (page parity tests first) | `claude/datacube-page-commands` | done, audited |
| 2 | Command program stage 2: one page state, page-level undo | `claude/datacube-page-state` | done, audited |
| 3 | Command program stage 3: the live channel between Python and an open page | `claude/datacube-live-commands` | done, audited |
| 4 | Graph mode: design note, then the G0 spike over main's Cypher island | | |
| 5 | Charts batch 4: the statistics aggregates and the kinds they unlock | | |
| 6 | Charts batch 5: maps | | |
| 7 | Charts batch 3's leftovers: Marimekko, waffle, Gantt | | |
| 8 | The other line's small follow-ups (the identifier rule once; the pinned queries compiled in a test; a Python refusal that drops keys) | | |
| 9 | Dashboards on classes (the other line's F1-F5): page filters and cross-filtering, joins when preparing data, the model view, promote and publish, measures in the model | | |

## Skipped, to come back to at the end

- **Previews (hosted per branch):** needs the user's Netlify account and the two repository secrets, and deleting the
  stale `preview` branch (the session's proxy refuses branch deletion).
- **"Get SQL out of clients":** its design waits on the user's go.
- **D5b (Snap's queries become Pure):** on the other DataCube line's own branch, and in the SQL plan's ground this
  line was told to leave alone (`engine.py` snap/frames/project-data, `_headless._query`, `warehouse.py`).

## Notes as the run goes

- **Phase 1, a choice for the user:** a chart with problems -- Python's `grid.chart(...)` refuses it in DataCube's
  words; the screen keeps it and shows the words on the chart (the homework's §8). One of the two should be the rule;
  left as it is until the user says which.
- **Phase 1, left to stage 2:** the screen tells `addChart`, `addGrid` and `setChart` once one page state holds a
  chart's spec and a grid's cube document as they are made.
- **Phase 1's audit** (an independent reviewer, six findings, each fixed with a test checked to fail without it): a
  `layout` command read as a saved page's sheet is (`readLayout`), so Python's `layout_from` refuses a bad shape in
  the page reader's words again; a cube kept for a detached chart has its id taken (`idsOf`, `freshId`); `addGrid`'s
  document read by `readCube`, from the commands' exact numbers; an editing grid's rename or move told as nothing; a
  grid given its made title back told as no title; the design note's `addChart` row. Answered, not changed: Python's
  refusal for a tile not on a sheet (`Sheet.layout`) and for a stack across sheets (`Page.stack`) are DataCube's words
  now, naming the tile's id and "move them there first" rather than Python's handle and `sheet.add(tile)`.
- **Phase 2** (`docs/DATACUBE_PAGE_STATE_2026_10_18.md`): one page state on a page of its own, Undo and Redo for every
  structural edit. Its audit: nine findings, eight fixed with a test each (checked to fail without it); one answered:
  an undo or a redo is told to a host following the page by commands as no command (no host follows it yet; the
  reverse channel will need it). Left to stage 2's next step (not built): one undo for a grid's own edits and the
  page's together.
- **Phase 3** (`docs/DATACUBE_LIVE_COMMANDS_2026_10_18.md`): Python serves each change as the commands it was, and an
  open page applies them rather than opening the whole page again. Its audit: eight findings, all fixed with a test
  each (checked to fail without it). Answered, not changed: two views of one page can still differ after both edit
  without reporting (each keeps its own edits under Python's; the first `page.read()` after one reports serves the
  page whole). Next, not built: the reverse channel -- the open page's edits sent to Python as commands rather than its
  whole document.
