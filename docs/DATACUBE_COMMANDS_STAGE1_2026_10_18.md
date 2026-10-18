# DataCube commands, stage 1: the grid half

This is the command program's stage 1 (`docs/DATACUBE_COMMAND_API_HOMEWORK_2026_10_17.md` §9), the grid half first.
It is built on branch `claude/datacube-commands`, stacked on stage 1a's tests (`claude/datacube-command-tests`), with no
PR until the user asks (the user, 2026-10-18).

## The goal

Every edit of a grid becomes a value, a `GridCommand`. One pure function applies it:
`applyGridCommand(draft, command)`.

- **The screen** builds the command from what a person did: a header click, a menu entry, a drag zone, Column
  Properties, the filter editor, the column editor.
- **Python** builds the same command from its call: `grid.group(...)`, `grid.sort(...)`, `grid.column(...)`,
  `grid.configure(...)`, `grid.filter(...)`, `grid.calculate(...)`.
- **One function decides.** Neither side holds a rule of its own. The command is the edit, so the screen and Python
  cannot do one edit two ways.

## The vocabulary

The vocabulary is the edits `grid-edits.ts` already holds, named as a person names them. Each command is plain JSON, so
Python sends it and the stage-3 live channel can carry it.

| Command | What it is | Applied by |
|---|---|---|
| `group {rows}` | the row groups, whole | `setGrouping` |
| `pivot {columns}` | the pivot, whole | `setPivot` |
| `sorts {sorts}` | the sorts, whole (the Sorts panel) | `setSorts` |
| `addSort {column, direction}` | a sort added last (the menu's Add, Python's `sort`) | `addSort` |
| `clickSort {column, chained}` | a header click; its first direction is read from the column's type | `clickSort` |
| `clickTreeSort {chained}` | a click on the tree column's header | `clickTreeSort` |
| `column {name, kind?, aggregate?, weight?}` | Column Properties | `setColumn` |
| `configure {patch}` | settings, an appearance, a column's presentation | `withConfiguration` |
| `filter {filter}` | the filter, whole (the filter editor, Python's `filter`) | the snapshot's filter |
| `addFilter {condition}` | one condition added to the filter (a cell's menu) | and-ed in, as the menu does |
| `calculate {column}` | a calculated column added, its lambda already parsed | `draftCandidate`'s lists |
| `measure {measure}` | Python's `measure`: an Aggregation, or a copy of the column under a new name | `measureAsColumn` |

### Rules

- **Plain data.** A command is data and nothing else. Anything that needs the compiler happens before the command is
  built, on both sides. A calculated column's expression is parsed first, so `calculate` carries the lambda, and
  `apply` stays pure and synchronous.
- **Refusals.** `apply` refuses in words (`CubeRefusal`), as grid-edits does today. Neither side checks anything
  before it.
- **Reading JSON.** `readGridCommand(json)` reads a command and refuses an unknown kind or field in words. Python
  sends JSON, and a misspelt key must never pass unnoticed.

## Where it lands

- **The module:** `datacube/src/grid-commands.ts`, with no DOM. It holds `GridCommand`, `applyGridCommand` and
  `readGridCommand`.
- **Headless:** `gridApply(spec, commands)` replaces `gridEdit`. It applies a list of commands, compiles the grid as
  Apply compiles it, and writes the cube document. `gridCalculate` and `gridFilter` become steps that build a command:
  they parse, and they turn Python's filter into the editor's normal form.
- **Python:** each `Grid` method builds its commands and sends them through one `_apply`. `page.grid(...)` sends its
  starting edits as one list.
- **The screen:**
  - the edit handlers in `app.ts` (`#edit`, the header clicks, the drag zones, the menu's sort, pivot, filter, heatmap
    and hide entries, the column editor's add) build commands and pass them to one `#apply`;
  - the Properties window's Apply goes through `column` and `configure` commands;
  - an `onCommand` option tells a host each command applied (the parity test, and the live channel later).

## How it is tested

- **Unit tests** in `grid-commands.test.ts`:
  - each command gives exactly what its grid-edits function gives;
  - `readGridCommand` refuses an unknown kind and a misspelt field, each in words.
- **The parity file is unchanged** (`datacube/test/fixtures/parity/parity.json`). Its 29 documents must still match on
  both sides, so moving to commands changes no edit.
- **Commands are compared too.** Each case action that is one edit on both sides (a header click, an added sort, an
  Aggregation, a heatmap, a hidden column, a cell's filter, a calculated column) also holds the command both sides
  must build. The screen's comes from `onCommand`, and Python's from its `_apply`. A case action that is two steps on
  the screen and one call in Python (`group region, desk`: two menu entries, one `group` call) is held to the document
  alone.
- **The round trips** generate commands instead of calling grid-edits directly.

## Not in stage 1's grid half

- **The page half:** sheets, tiles, charts, titles, layout. It waits on the page's parity, which compares views first,
  so chart edits get their tests before their commands (`docs/DATACUBE_COVERAGE_2026_10_18.md`).
- **One page state and page-level undo:** stage 2.
- **The live channel:** stage 3.
