# DataCube commands, stage 1: the page half

The command program's stage 1 (`docs/DATACUBE_COMMAND_API_HOMEWORK_2026_10_17.md` §9), its page half, after the grid
half (`docs/DATACUBE_COMMANDS_STAGE1_2026_10_18.md`). Phase 1 of the roadmap run
(`docs/DATACUBE_ROADMAP_RUN_2026_10_18.md`), on branch `claude/datacube-page-commands`.

## The goal

Every edit of a page's structure becomes a value, a `PageCommand`, applied by one pure function,
`applyPageCommand(draft, command)`, over the page's document and every id it has held. It takes over the page rules
Python still copies (the homework's §8):
- **ids and titles**: one allocator (`nextPageId`: after every id the page has held, so none is given out again), and
  a new chart's title from its id ("Chart 3");
- **removing a tile**: a grid takes the charts following it; a frozen one stays, detached, its selection taken off;
  a chart takes its selection off its grid's filter (the screen's two cleanups, which Python missed); a cube stays
  while a view reads it; the page's model keeps the tables and classes of the cubes it still holds (`keptFor`);
- **moving a tile**: onto another sheet, at its bottom; onto its own sheet, nothing (the screen's rule; Python moved
  it to the bottom);
- **placing a tile**: beside the grid it was asked from while that band has room, as many side by side as the
  screen's width allows (`columns`, which the screen says and a host with no screen leaves to the most, 4);
- **names**: by `givenName`, the one rule; the page's name is each cube's too (`writePageOf`'s rule), so a rename
  writes no cube's name by a rule of its own.

## The vocabulary

| Command | What it is |
|---|---|
| `renamePage {name}` | the page's name |
| `addSheet {id?, name}` | a sheet after the last |
| `renameSheet {sheet, name}` | a sheet's own name; null, named after its first grid |
| `moveSheet {sheet, to}` | a sheet to another place in the order |
| `removeSheet {sheet}` | a sheet and its tiles; the last sheet is refused |
| `renameTile {tile, title}` | a tile's title; null takes a grid's off (a chart has one) |
| `addGrid {id?, document, sheet, near, title, columns}` | a grid of its own cube (the document, made first) |
| `addChart {id?, cube, spec, near, title, columns}` | a chart of a grid, beside it |
| `setChart {chart, spec}` | a chart's spec, made first by `editChart` |
| `moveTile {tile, sheet, columns}` | a tile onto another sheet |
| `stack {tiles}` | tiles in one place, as tabs |
| `removeTile {tile}` | a tile off the page, with what goes with it |
| `arrange {sheet, preset, order}` | a sheet laid out as one of the layout picker's shapes |
| `layout {sheet, layout}` | a sheet's layout, whole (a drag, a resize, a drop on a tab) |

An id left out of a command that makes a tile or a sheet is allocated by `apply`, and the command as applied carries
it: what a host records, and what a live channel would carry, replays exactly.

## Where it lands

- `datacube/src/page-commands.ts`: the commands, `applyPageCommand`, `nextPageId`, `readPageCommand`.
- Headless `pageApply(page, held, commands)`: Python's page edits, each a command; the page and the commands as
  applied come back.
- Python: `Page.sheet`, `Page.stack`, `Page.remove`, `Sheet.add`/`Tile.move_to`, `Tile.title`, `Sheet.name`,
  `Page.name`, `Sheet.layout`/`arrange`/`layout_from`, `Grid.chart`, `Chart.plot`/`options` and `Page.grid`'s placing
  build commands. `_next`, the remove cascade, and the copies of `bands.ts`'s walkers go.
- The screen (`page/cube-page.ts`): its ids from `nextPageId`, its chart titles by the same rule, and an
  `onPageCommand` option telling a host each page edit as a command. Applying them onto one page state is stage 2.

## How it is tested

- Unit tests of every command and rule (`page-commands.test.ts`).
- **The screen replays**: what the screen reports, applied by `applyPageCommand` to the page it started as, is the page
  it shows (`views()`) -- so the screen and the function cannot drift before stage 2 makes the function the screen's.
- Python's existing page tests, and its page commands recorded.

## Not in this phase

- **One page state on the screen, and page-level undo:** stage 2.
- **The cube app's own page** (a cube with charts beside it) titles its added grids by count ("Grid 2" for the first),
  where a page of its own titles them by id; Python makes only pages of their own. Left as it is.
- **Python's refusal to empty a shown page** stays: a page with no cube is one DataCube does not read, and Python
  serves a shown page again on every change. The screen's page is not read again, so it may empty.
