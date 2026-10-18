# DataCube commands, stage 3: commands as the live channel between Python and an open page

The command program's stage 3 (`docs/DATACUBE_COMMAND_API_HOMEWORK_2026_10_17.md` §9: "commands as the live channel
between Python and an open page, instead of re-serving the whole document and rebuilding every grid"), phase 3 of the
roadmap run, on branch `claude/datacube-live-commands`, stacked on phase 2.

## Today

- **Python to the page:** every change Python makes re-serves the whole page document (engine `serve_page`, its
  version moving); the open page sees the version move and opens the whole page again (`openLive`): every grid made
  again and queried again, the sheet shown kept. What was changed in DataCube meanwhile is replaced.
- **The page to Python:** the open page reports its whole document after its changes (`report`), which
  `page.read()` takes.

## Stage 3

- **Each serve carries the commands that moved the page** from the version before: Python's page commands
  (page-commands.ts) and grid commands (grid-commands.ts, each with its grid's id), in the order they were made, as
  Python already records them. A change Python made by no command (a page loaded, a page read back) carries none.
- **The engine keeps them by version** (`serve_page(name, document, commands)`), and answers an open page at version
  `v` asking what moved since (`page.json?page=K&since=v`): the commands, when every version since `v` has them; else
  the page, as today.
- **The open page applies them** to the page as it is open: page commands to its page state (page-state.ts) and the
  screen put at it (stage 2's `sync`), a grid added made by the host's maker for its cube document; grid commands to
  their grids (`CubeApp.applyGridCommands`, the screen's own `#apply`). No grid is made again, none queried again but
  those a command changes; what was changed in DataCube meanwhile is kept.
- **When they do not apply** (a tile Python names was closed in DataCube, an id DataCube gave meanwhile), the page is
  opened again as Python has it -- today's behaviour, the fallback in words: "opened again as Python has it".
- Python's change is the other editor's, not a step of the page's own history: the page's undo starts again from it
  (an undo past it would take Python's change back without Python knowing).

## Wire

- `page.json?page=K&since=v` -> `{"version": n, "commands": [{"page": <PageCommand>} | {"grid": "<id>", "command":
  <GridCommand>}, ...]}` or `{"version": n, "page": <document>}`.
- `LivePage.changes(since)` in the services; Python's notebook widget answers the same request over its channel.

## Not in this phase

- The reverse channel (the screen's told commands sent to Python instead of its whole document): next.
