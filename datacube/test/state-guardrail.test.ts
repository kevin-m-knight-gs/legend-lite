// Leg B's guardrail (docs/DATACUBE_LEG_B_STATE_OWNER_2026_09_28.md): the cube's state -- its
// snapshot, configuration, open groups and history -- has ONE owner, `CubeStateOwner`
// (src/cube-state.ts). Before it, the app and the controller each held a copy, each wrote its
// own, and a refusal put back one of them (P2-100). A "cache for convenience" is how a second
// owner comes back, so this holds, mechanically, that nothing else keeps or assigns it.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { Sources } from '../../tools/js/runfiles.mts';
import { sites } from './one-api/scan.ts';

// the sources this reads, as the BUILD target declares them (Bazel workplan P3-29)
const SOURCES = new Sources('SOURCES', 'datacube');

const OWNER = 'src/state-owner.ts';
/** The two that held copies: the app and the controller. */
const HOLDERS = ['src/app.ts', 'src/cube.ts'];
/** The cube's state, by the names the holders used for it. */
const STATE = '(snapshot|config|configuration|tree|history|lastState|past|future|view|treeRows)';

const code = (file: string): { line: string; at: number }[] =>
  SOURCES.read(file).split('\n').map((line, i) => ({ line, at: i + 1 }))
    .filter(({ line }) => {
      const t = line.trim();
      return !(t.startsWith('//') || t.startsWith('*') || t.startsWith('/*'));
    });

describe('one owner of the cube\'s state', () => {
  it('the app and the controller declare no field of it (a getter reading the owner is fine)', () => {
    const field = new RegExp(`^\\s*(readonly\\s+)?#${STATE}\\b\\s*[:=;]`);
    const bad = HOLDERS.flatMap((file) => code(file)
      .filter(({ line }) => field.test(line)).map(({ line, at }) => `${file}:${at}: ${line.trim()}`));
    assert.deepEqual(bad, []);
  });

  it('the app and the controller assign none of it', () => {
    // The editors' DRAFTS (the Filter window's condition tree, the Properties draft) and the Ad
    // Hoc session are not the cube's state; B4 and B5 bring them under the same rules.
    const assign = new RegExp(`this\\.#${STATE}\\s*=(?!=)`);
    const bad = HOLDERS.flatMap((file) => code(file)
      .filter(({ line }) => assign.test(line)).map(({ line, at }) => `${file}:${at}: ${line.trim()}`));
    assert.deepEqual(bad, []);
  });

  it('there is one history: the owner\'s', () => {
    const bad = SOURCES.under('src', '.ts').filter((f) => f !== OWNER)
      .flatMap((file) => code(file)
        .filter(({ line }) => /new UndoStack\b|class \w*History\b/.test(line))
        .map(({ line, at }) => `${file}:${at}: ${line.trim()}`));
    assert.deepEqual(bad, []);
  });

  it('scans what it claims to', () => {
    // a guardrail that scans nothing passes for the wrong reason
    for (const file of HOLDERS) assert.ok(code(file).length > 100, file);
    assert.ok(code(OWNER).some(({ line }) => /this\.#committed = /.test(line)), 'the owner does assign it');
  });
});

// S3's guardrail (docs/DATACUBE_ONE_API_PLAN_2026_10_21.md S3, "the board produced by the commands"): a page's state has
// one owner too -- its PageState, changed by the commands' one step (page-commands.ts applyToState) or kept as its owners
// have it (keep: a grid's own edits) -- and the board (page/cube-page.ts) is a view of it: its structure changes only
// where it is put at a page (sync, restore) or draws what no page holds (an editing grid, a tile a gesture made). A
// gesture that moves a tile or a sheet itself is a second owner coming back: each place is pinned here, with why.

/** Every place the board's structure changes, by function: anything else is a gesture changing the page itself. */
const BOARD = /(\bboard\.(?:add|remove|setLayout)\(|#newSheet\(|#sheets\.(?:splice|push|sort)\(|#pageLayouts\.(?:set|delete)\(|chart\.conditions = |sheet\.name = |\.name = s\.name)/g;
const BOARD_SITES: Record<string, string> = {
  'constructor:#newSheet(': "a page's first sheet",
  'constructor:board.setLayout(': "a cube alone's own grid, the whole board",
  'constructor:#pageLayouts.set(': 'its layout, the page\'s',
  '#newSheet:#newSheet(': '(its own header)',
  '#newSheet:board.setLayout(': "a new sheet's board at bands.ts NEW_SHEET",
  '#newSheet:#pageLayouts.set(': "a new sheet's layout (bands.ts NEW_SHEET)",
  '#newSheet:#sheets.push(': 'a sheet made: by sync, setSheets, or the constructor',
  '#place:board.add(': 'a tile on its board: by sync, restore, a placeholder, an editing grid, a chart with no spec yet',
  '#take:board.remove(': 'a tile off its board: by sync (#drop), an editing grid closed, a chart with no spec yet',
  'sync:#newSheet(': 'the board put at a page',
  'sync:chart.conditions = ': 'the board put at a page',
  'sync:board.remove(': 'the board put at a page: a tile onto its sheet',
  'sync:board.add(': 'the board put at a page: a tile onto its sheet',
  'sync:#sheets.splice(': 'the board put at a page',
  'sync:#pageLayouts.delete(': 'the board put at a page',
  'sync:.name = s.name': 'the board put at a page',
  'sync:#sheets.sort(': 'the board put at a page',
  'sync:board.setLayout(': 'the board put at a page',
  'sync:#pageLayouts.set(': 'the board put at a page',
  'moveToSheet:board.remove(': "an editing grid moved: no page holds it",
  'moveToSheet:board.add(': "an editing grid moved: no page holds it",
  'setSheets:#newSheet(': 'a saved page put back',
  'setSheets:sheet.name = ': 'a saved page put back',
  'setSheets:board.remove(': 'a saved page put back',
  'setSheets:board.add(': 'a saved page put back',
  'setSheets:#sheets.splice(': 'a saved page put back',
  'setSheets:#pageLayouts.delete(': 'a saved page put back',
  'setSheets:#sheets.sort(': 'a saved page put back',
  'restore:board.setLayout(': 'a saved page put back',
  'restore:#pageLayouts.set(': 'a saved page put back',
  '#reconcileGrid:chart.conditions = ': "the screen's reconcile (G8's note): the same rule as the page's, kept by keep",
  '#dropGrid:chart.conditions = ': 'a frozen chart left detached as sync takes its grid off',
  '#closeEditor:board.setLayout(': "an editing grid closed: the board back at the page's layout (P7)",
  'undoLayout:board.setLayout(': 'a layout undone by hand, as a drag: told as its layout, applied, synced',
  'redoLayout:board.setLayout(': 'a layout redone by hand, as a drag: told as its layout, applied, synced',
};

/** Every place a page's state changes, by its owner: the commands' step is page-commands.ts's (applyToState). */
const PAGE_STATE = /(this\.#state(?:\.(?:keep|begin|undo|redo)\(| = )|\bstate\.(?:keep|begin)\()/g;
const STATE_SITES: Record<string, Record<string, string>> = {
  'src/page/page-app.ts': {
    'constructor:this.#state = ': 'a page made',
    'clear:this.#state = ': 'a blank page',
    'restore:this.#state = ': 'a saved page put back',
    'follow:this.#state = ': "another editor's commands, applied by the rules: the page they made, whole",
    'setTitle:this.#state.keep(': "the page's name, its host's (no step)",
    'onChange:this.#state.keep(': "what its owners change (a grid's own edits), no step",
    '#move:this.#state.undo(': 'Undo (N2, S4)',
    '#move:this.#state.redo(': 'Redo (N2, S4)',
    '#move:this.#state.keep(': 'the screen put at a step: its grids made again write their own cubes',
    '#applyFilterCommand:this.#state.keep(': 'the page as its owners have it, before the filter command is applied',
  },
  'src/app.ts': {
    '#pageChanged:state.keep(': "a cube alone's board: what its owners change, no step",
  },
};

describe("one owner of a page's state; the board a view of it (S3)", () => {
  it("the board's structure changes only where it is put at a page, or draws what no page holds", () => {
    const found = sites(SOURCES.read('src/page/cube-page.ts'), BOARD);
    assert.deepEqual([...found.keys()].filter((k) => BOARD_SITES[k] === undefined), [],
      'a new place the board changes itself: a gesture tells its commands, the owner applies them, sync puts the board there');
    assert.deepEqual(Object.keys(BOARD_SITES).filter((k) => !found.has(k)), [], 'pinned places that are gone: take them off');
  });

  it("a page's state changes by the commands' one step, or is kept as its owners have it", () => {
    for (const [file, allowed] of Object.entries(STATE_SITES)) {
      const found = sites(SOURCES.read(file), PAGE_STATE);
      assert.deepEqual([...found.keys()].filter((k) => allowed[k] === undefined), [], `${file}: a new place a page's state changes`);
      assert.deepEqual(Object.keys(allowed).filter((k) => !found.has(k)), [], `${file}: pinned places that are gone`);
    }
    // a step begun anywhere but the commands' one step is an edit with no command
    const begun = SOURCES.under('src', '.ts').filter((f) => f !== 'src/page-commands.ts' && f !== 'src/page-state.ts')
      .filter((f) => /\.begin\((?:label|edit\.label|['`])/.test(SOURCES.read(f)) && /PageState|#state|state\.begin/.test(SOURCES.read(f)));
    assert.deepEqual(begun, [], 'a page step begun outside applyToState');
  });
});
