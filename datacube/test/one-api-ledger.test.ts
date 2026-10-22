// THE ONE API LEDGERS, STATIC HALF (docs/DATACUBE_ONE_API_PLAN_2026_10_21.md §3). Every known place where the tenet --
// every change to a saved page is a command, applied by one function, and the browser and Python do the same -- does not
// hold yet is listed here by its item in the plan's register (§4), and only there. A new one fails the build; a listed
// one that is gone fails it too, until its item is closed and its entry taken off; and each ledger's size is pinned, so
// growing one is an edit of its ceiling, made only with a dated note (the plan's §1.1, AGENTS.md's guard-file rule).
// The register itself is held to every ledger (the last test): an open item is in some ledger, a closed one in none,
// and no item leaves the register without being closed.
//
//   L1  what changes a grid's state; who reaches the grid's public ways in (its callers across the app); who calls each
//       listed gap; the screen's Snap; the host's own writes of the page -- a write counted only where its function
//       tells no command, so the fix (a told command) is what takes it off
//   L8  every command kind against the screen, Python, the kind lists, both readers, the rebase's renaming; every
//       source kind against what Python reads and adds; the headless exports Python never asks
//   L10 (the browser's half) where the two sides keep different rules, and the gaps no scan reaches: each pinned by the
//       code that is the gap (a marker), so fixing it fails here until its item is closed
//
// The behavioural half is one-api-gestures.test.ts (L2, L3); Python's is python/tests/test_one_api.py (L4-L7, L9, L10).

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { Sources } from '../../tools/js/runfiles.mts';
import { DEFAULT_CONFIGURATION } from '../src/config.ts';
import { configurationOf, cubeToJson, readCube, treeOf, writeCube } from '../src/cube-document.ts';
import type { CubeSnapshot } from '../src/snapshot.ts';
import { TreeState } from '../src/tree.ts';
import {
  codeLines, exportedFunctions, functionBody, heldTo, listed, pythonCode, sites, unionKinds, unionTypes,
} from './one-api/scan.ts';

const SOURCES = new Sources('SOURCES', 'datacube');

const PLAN = '../docs/DATACUBE_ONE_API_PLAN_2026_10_21.md';
const PY = '../python/legend_lite';

/** A gap, by the register's items; `count` where one place holds it more than once. An entry with no item is allowed
 *  for good, with `why` (the command path itself, its plumbing, following another editor, a named exception N1-N3). */
interface Entry {
  readonly items: readonly string[];
  readonly count?: number;
  readonly why?: string;
}

/** Each ledger's size, pinned: it shrinks with each fix (lower it then); it grows only with a dated note here. */
// 2026-10-22 (docs/DATACUBE_ONE_API_PLAN_2026_10_21.md D10, the user's sign-off): MARKERS 9 -> 10 -- S1's audit finding
// 10 registered as P15, pinned by its code
const CEILINGS: Record<string, number> = {
  L1_GRID: 5, L1_THEN: 3, L1_ROUTES: 5, L1_REACH: 9, L1_PLANE: 3, L1_HOST: 12,
  L8_KINDS: 11, L8_SOURCES: 5, L8_UNASKED: 2, MARKERS: 6, UNCHECKED: 7,
};

function pinned(name: string, size: number): void {
  assert.equal(size, CEILINGS[name], `${name} holds ${size} entries, its ceiling says ${CEILINGS[name]}: lower the ceiling `
    + 'with the fix that shrank it; raise it only with a dated note naming the task');
}

// -- L1 -----------------------------------------------------------------------------------------------------------

const PLUMBING = 'the one way a change reaches the state owner: no gap of its own';

/** Every call in the grid (app.ts) that changes its state, by where it is made. */
const GRID_CALLS = /this\.(#change|#owner\.change|#owner\.undo|#owner\.redo|#owner\.retryRefused)\(/g;
const L1_GRID: Record<string, Entry> = {
  '#change:#owner.change': { items: [], why: PLUMBING },
  '#applyCommands:#change': { items: [], why: 'the command path: applyGridCommands, told by onCommand' },
  '#undo:#owner.undo': { items: [], why: 'N2: an undo is told as a restore (onRestore), the page written whole at its version' },
  '#redo:#owner.redo': { items: [], why: 'N2: as an undo' },
  // a retry stays a retry: the refused change run again, its commands kept and told when it lands
  // (cube-transactions.test.ts G13)
  'submit:#owner.retryRefused': { items: [], why: "the refused change run again: its commands told (#refusedCommands)" },
};

/** Every call of the command path, by where it is made: the ones with a change of their own beside the commands. */
const GRID_THEN = /this\.(#applyCommands)\(/g;
const L1_THEN: Record<string, Entry> = {
  '#apply:#applyCommands': { items: [], why: 'the screen\'s edits: commands alone' },
  'applyGridCommands:#applyCommands': { items: [], why: "another editor's commands: never told back (pinned below)" },
  '#applyDraft:#applyCommands': { items: [], why: "Properties Apply: the draft's commands alone, the tree moved by configure's rule" },
};
/** The allowed calls' exact text: a change beside the commands added there is a new gap, not a quiet edit. */
const THEN_TEXT: Record<string, string> = {
  '#apply:#applyCommands': 'const { refused, outcome } = await this.#applyCommands(commands, label);',
  'applyGridCommands:#applyCommands': "const { refused, outcome } = await this.#applyCommands(commands, label, 'theirs');",
  '#applyDraft:#applyCommands': "const { refused, outcome } = await this.#applyCommands(commands, 'Properties');",
};

/** The grid's public ways in that change its state or copies, called from anywhere in the app but the grid itself. */
const ROUTES = /\.((?:state\.(?:change|undo|redo|retryRefused|refresh))|change|refilter|applyGridCommands|followSnap|followLive|controller\.\w+)\(/g;
const ROUTE_FILES = (f: string): boolean => f.startsWith('src/') && f.endsWith('.ts') && f !== 'src/app.ts'
  && f !== 'src/state-owner.ts' && !f.startsWith('src/adhoc/') && !f.startsWith('src/headless/');
const L1_ROUTES: Record<string, Entry> = {
  'src/page/cube-page.ts:#select:refilter': { items: [], why: "a chart's mark on its grid's filter: the grid's refilter command, told beside the page's select" },
  'src/page/page-app.ts:follow:followSnap': { items: [], why: "following another editor's snap" },
  'src/page/page-app.ts:follow:followLive': { items: [], why: "following another editor's going live" },
  'src/app/datacube-app.ts:openSaved:followSnap': { items: [], why: 'N1: a saved page opened, its copies as it says' },
  'src/app/datacube-app.ts:applyGrid:applyGridCommands': { items: [], why: "another editor's commands, applied" },
};

/** Every call of a listed gap, by where it is called from: a new gesture reaching an old gap is a new entry. */
const GAP_CALLS_HOST = /(?<![\w.#]|function )(inPlace|openSaved|blankPage|openFrame)\(/g;
const L1_REACH: Record<string, Entry> = {
  'datacube-app.ts:openInPlace:inPlace': { items: [], why: "N1, D1: a page of this tab's own -- on a page another editor serves, that one left as it is" },
  'datacube-app.ts:openFrame:inPlace': { items: ['P12'] },
  'datacube-app.ts:open:openSaved': { items: [], why: "N1, D1: a page of this tab's own -- on a page another editor serves, that one left as it is", count: 2 },
  'datacube-app.ts:openText:openSaved': { items: [], why: "N1, D1: a page of this tab's own -- on a page another editor serves, that one left as it is" },
  'datacube-app.ts:examplesList:openSaved': { items: [], why: "N1, D1: a page of this tab's own -- on a page another editor serves, that one left as it is" },
  'datacube-app.ts:openDataCube:openSaved': { items: [], why: "N1, D1: a page of this tab's own -- on a page another editor serves, that one left as it is" },
  'datacube-app.ts:openDataCube:blankPage': { items: ['B9'], count: 2 },
  'datacube-app.ts:openDataCube:openFrame': { items: ['P12'] },
  'datacube-app.ts:followed:openFrame': { items: ['P12', 'B10'], count: 2 },
};

/** What a function tells: any command, by any way the app tells one. A write in a function that tells one is no gap. */
// (the page's rename and a grid's own move of its rows are told by the page: page-app.ts rename, cube-page.ts on 'moved')
const TELLS = /kind: '(?:renamePage|snap|live|modelInto|modelPin|modelKeep|rebuilt|setChartCube|select)'|channel\??\.edit\(|onPageCommand\??\.\(|#tell\(|#apply\(|page\.rename\(|#emit\('moved'\)/;
const quiet = (body: string): boolean => !TELLS.test(body);

/** Where the grid says the rows it reads moved, or moves them (a snap, going live): the page's copies change. */
const GRID_PLANE = /this\.(#emit\('plane'\)|#controller\.(?:snapAndRun|goLive|follow|forget)\()/g;
const L1_PLANE: Record<string, Entry> = {
  "#planeMoved:#emit('plane')": { items: [], why: "following another editor's snap or going live" },
  'followSnap:#controller.follow(': { items: [], why: "following another editor's snap" },
  'followLive:#controller.forget(': { items: [], why: "following another editor's going live" },
};

/** The host's own writes of the page (app/datacube-app.ts) in a function that tells no command. */
const HOST_CALLS = /(current\s*=\s*\{|page\.setTitle\(|page\.clear\(\)|page\.restore\()/g;
const L1_HOST: Record<string, Entry> = {
  'inPlace:page.clear()': { items: [], why: "N1, D1: a page of this tab's own -- on a page another editor serves, that one left as it is" },
  'inPlace:current = {': { items: [], why: "N1, D1: a page of this tab's own -- on a page another editor serves, that one left as it is", count: 2 },
  'inPlace:page.setTitle(': { items: [], why: "N1, D1: a page of this tab's own -- on a page another editor serves, that one left as it is" },
  'openSaved:page.restore(': { items: [], why: "N1, D1: a page of this tab's own -- on a page another editor serves, that one left as it is" },
  'openSaved:current = {': { items: [], why: "N1, D1: a page of this tab's own -- on a page another editor serves, that one left as it is", count: 2 },
  'openSaved:page.setTitle(': { items: [], why: "N1, D1: a page of this tab's own -- on a page another editor serves, that one left as it is" },
  'blankPage:page.clear()': { items: ['B9'] },
  'blankPage:current = {': { items: ['B9'] },
  'blankPage:page.setTitle(': { items: ['B9'] },
  'openFrame:current = {': { items: ['P12', 'B10'] },
  'openFrame:page.setTitle(': { items: ['P12', 'B10'] },
  'followLive:current = {': { items: [], why: "following another editor's page: its name as it now is", count: 2 },
};

describe('L1: every change of a grid, a copy or the page made other than by command is listed', () => {
  const app = SOURCES.read('src/app.ts');
  const host = SOURCES.read('src/app/datacube-app.ts');

  it('the grid', () => {
    assert.deepEqual(heldTo(sites(app, GRID_CALLS), L1_GRID, 'app.ts'), []);
    pinned('L1_GRID', Object.keys(L1_GRID).length);
  });

  it('the command path, and the allowed calls of it as they are written', () => {
    assert.deepEqual(heldTo(sites(app, GRID_THEN), L1_THEN, 'app.ts'), []);
    for (const [key, text] of Object.entries(THEN_TEXT)) {
      assert.ok(app.includes(text), `${key} is no longer written "${text}": a change beside the commands is a new gap`);
    }
    pinned('L1_THEN', Object.keys(L1_THEN).length);
  });

  it("the grid's public ways in, and who calls them", () => {
    const found = new Map<string, number>();
    for (const f of SOURCES.under('src', '.ts').filter(ROUTE_FILES)) {
      for (const [key, n] of sites(SOURCES.read(f), ROUTES)) found.set(`${f}:${key}`, n);
    }
    // the owner and the controller handed out at all (each a way to change the state with no command): G11 closed
    // them, the state shown only as what it reads -- handing either out again is a new gap
    if (/get state\(\): CubeStateOwner\b/.test(app)) found.set('src/app.ts:get state()', 1);
    if (/get controller\(\)/.test(app)) found.set('src/app.ts:get controller()', 1);
    assert.ok(app.includes("export type CubeStateView = Pick<CubeStateOwner, 'busy' | 'historyDepth'>;"),
      'the state a grid shows is what it reads (busy, historyDepth): a way to change it there is a new gap (G11)');
    assert.deepEqual(heldTo(found, L1_ROUTES, 'routes'), []);
    pinned('L1_ROUTES', Object.keys(L1_ROUTES).length);
  });

  it("a new grid's configuration, made by one rule (G15 closed)", () => {
    // cube-document.ts freshConfiguration: titled by its source, capped by gridCapped -- a second spelling (a grid's
    // configuration titled by a source's label) is a new rule; an example's own configured grid is none
    const spelled = SOURCES.under('src', '.ts').filter((f) => f !== 'src/cube-document.ts'
      && /reportTitle:\s*[\w.]*\blabel\b/.test(SOURCES.read(f)));
    assert.deepEqual(spelled, [], "a new grid's configuration spelled outside freshConfiguration (cube-document.ts)");
  });

  it('who calls each listed gap', () => {
    const found = new Map<string, number>();
    for (const [key, n] of sites(host, GAP_CALLS_HOST)) found.set(`datacube-app.ts:${key}`, n);
    assert.deepEqual(heldTo(found, L1_REACH, 'callers'), []);
    pinned('L1_REACH', Object.keys(L1_REACH).length);
  });

  it('the rows a grid reads (Snap, Go Live), where no command is told', () => {
    assert.deepEqual(heldTo(sites(app, GRID_PLANE, quiet), L1_PLANE, 'app.ts'), []);
    pinned('L1_PLANE', Object.keys(L1_PLANE).length);
  });

  it("the host's own writes of the page, where no command is told", () => {
    assert.deepEqual(heldTo(sites(host, HOST_CALLS, quiet), L1_HOST, 'datacube-app.ts'), []);
    pinned('L1_HOST', Object.keys(L1_HOST).length);
  });
});

// -- L8 -----------------------------------------------------------------------------------------------------------

/** A command kind's cells: made by the screen, by Python (itself, or by a headless function it asks). */
type Cell = 'screen' | 'python';
/** The missing cells of each kind, by item; `nature` where the kind is one side's by its nature (reviewed at S7). */
const L8_KINDS: Record<string, Partial<Record<Cell, Entry>>> = {
  clickSort: { python: { items: [], why: "nature: a header click is the screen's; Python sorts by `sorts` and `addSort`" } },
  clickTreeSort: { python: { items: [], why: "nature: the tree column's click is the screen's" } },
  addFilter: { python: { items: ['K2'] } },
  measures: { screen: { items: [], why: "nature: Python's measure under another name; the screen's panels set columns" } },
  moveSheet: { python: { items: ['K8'] } },
  removeSheet: { python: { items: ['K8'] } },
  setPageFilter: { python: { items: ['K7'] } },
  // 2026-10-21, One API S1 G7: a chart's selection became the page's select and its grid's refilter -- Python's chart
  // selection is K7's (S6), so both kinds come in listed (L8_KINDS 13 -> 15; signed off by the user, 2026-10-22: D10)
  select: { python: { items: ['K7'] } },
  refilter: { python: { items: ['K7'] } },
  // 2026-10-21, One API S1 P8: a detached chart's Update tells its query as setChartCube -- Python's Open in grid and
  // Update are K11's (S6), so the kind comes in listed (L8_KINDS 12 -> 13; signed off by the user, 2026-10-22: D10)
  setChartCube: { python: { items: ['K11'] } },
  removePageFilter: { python: { items: ['K7'] } },
};

/** Each source kind's missing cells: Python reads a page over it (headless gridBase), Python adds a grid over it. */
const L8_SOURCES: Record<string, { readonly read?: Entry; readonly add?: Entry }> = {
  file: { read: { items: ['X1', 'X2', 'B7'] }, add: { items: ['X4'] } },
  remoteFile: { read: { items: ['X1', 'X2', 'B7'] }, add: { items: ['X4'] } },
  warehouseTable: { read: { items: ['X1', 'X2', 'B7'] }, add: { items: ['X4'] } },
  savedQuery: { read: { items: ['X1', 'X5'] }, add: { items: ['X4'] } },
  class: { read: { items: ['X1', 'X5'] }, add: { items: ['X4'] } },
};
/** The headless functions by which Python adds a grid over a source kind today -- pinned: a new entry is S5/S6's,
 *  with a test that adds such a grid from Python. */
const PYTHON_ADDS: Record<string, string> = { frame: 'gridCube', join: 'joinCube' };

/** Headless exports Python never asks (`call('<name>'`): each one Python should. */
const L8_UNASKED: Record<string, Entry> = {
  pageFilter: { items: ['Z19'] },
  joinQuestions: { items: ['Z19'] },
};

/**
 * WHAT IS NOT CHECKED YET, SAID: what the screen does that Python's API cannot, with no command kind of its own to
 * hold it by (the commands exist). S6 agrees each method's name with the user and writes its test first; until then
 * these are register entries no test holds -- listed so, and pinned, never mistaken for checked.
 */
const UNCHECKED_UNTIL_S6: Record<string, string> = {
  K1: 'sorts replaced, one cleared, all cleared, after a grid is made',
  K4: 'group-level, window, child-aggregate and explode columns; the Show As and period presets',
  K6: 'a dimension used',
  K9: 'a chart drilled, and back up',
  K10: 'Copy of Grid',
  K11: "a chart's Open in grid and Update",
  K12: 'a saved page opened by its id',
};

describe('L8: every command kind and source kind, against who makes and reads it', () => {
  const grid = SOURCES.read('src/grid-commands.ts');
  const page = SOURCES.read('src/page-commands.ts');
  const gridKinds = unionKinds(grid, 'GridCommand');
  const pageKinds = unionKinds(page, 'PageCommand');
  const python = SOURCES.under(PY, '.py').map((f) => pythonCode(SOURCES.read(f))).join('\n');
  const exported = SOURCES.under('src/headless', '.ts').flatMap((f) => exportedFunctions(SOURCES.read(f)));
  const asked = (name: string): boolean => python.includes(`'${name}'`);
  /** The modules that make commands: each one naming the command types or telling page edits (a new one is read too). */
  const producers = SOURCES.under('src', '.ts')
    .filter((f) => !f.startsWith('src/headless/') && !['src/grid-commands.ts', 'src/page-commands.ts', 'src/page-rebase.ts'].includes(f))
    .filter((f) => /\b(?:GridCommand|PageCommand)\b|#tell\(/.test(SOURCES.read(f)));
  // a trailing comment is no producer
  const screen = producers.map((f) => codeLines(SOURCES.read(f)).map((l) => l.replace(/\s\/\/\s.*$/, '')).join('\n')).join('\n')
    // the Properties window's commands are built by draftCommands, the screen's
    + grid.slice(grid.indexOf('export function draftCommands'), grid.indexOf('function settingsPatch'))
    // and what a page opened again tells of its cubes opened over their frames (rebuiltCommands, the screen's)
    + functionBody(page, 'rebuiltCommands');
  const made = (text: string, kind: string): boolean => new RegExp(`(?<!readonly )kind: '${kind}'`).test(text);

  it('each command kind is in its list, read by its reader, renamed by the rebase', () => {
    assert.deepEqual(listed(grid, 'GRID_COMMAND_KINDS'), gridKinds, 'GRID_COMMAND_KINDS is every grid command kind');
    assert.deepEqual(listed(page, 'PAGE_COMMAND_KINDS'), pageKinds, 'PAGE_COMMAND_KINDS is every page command kind');
    const gridReader = functionBody(grid, 'readGridCommand');
    const pageReader = functionBody(page, 'readPageCommand');
    const renaming = functionBody(SOURCES.read('src/page-rebase.ts'), 'renamedCommand');
    assert.deepEqual(gridKinds.filter((k) => !gridReader.includes(`case '${k}'`)), [], 'grid kinds readGridCommand cannot read');
    assert.deepEqual(pageKinds.filter((k) => !pageReader.includes(`case '${k}'`)), [], 'page kinds readPageCommand cannot read');
    assert.deepEqual(pageKinds.filter((k) => !renaming.includes(`case '${k}'`)), [], 'page kinds the rebase does not rename');
  });

  it('each command kind is made by the screen and by Python, or listed', () => {
    const problems: string[] = [];
    for (const kind of [...gridKinds, ...pageKinds]) {
      const has: Record<Cell, boolean> = {
        screen: made(screen, kind),
        python: new RegExp(`'kind': '${kind}'`).test(python) || exported.some((e) => made(e.body, kind) && asked(e.name)),
      };
      for (const cell of ['screen', 'python'] as const) {
        const entry = L8_KINDS[kind]?.[cell];
        if (!has[cell] && !entry) problems.push(`${kind}: no ${cell} makes it -- make one, or list it with its item`);
        if (has[cell] && entry) problems.push(`${kind}: the ${cell} makes it now -- close its item, take it off`);
      }
    }
    for (const kind of Object.keys(L8_KINDS)) if (![...gridKinds, ...pageKinds].includes(kind)) problems.push(`${kind}: no such kind`);
    assert.deepEqual(problems, []);
    pinned('L8_KINDS', Object.keys(L8_KINDS).length);
  });

  it('each source kind is read and added by Python, or listed', () => {
    const kinds = unionTypes(SOURCES.read('src/cube-document.ts'), 'CubeSource');
    // what a page on the host holds is one rule, which gridBase, gridOpened and the keeper ask (headless grid.ts onHost)
    const base = exported.find((e) => e.name === 'onHost')!.body.split('\n');
    /** A kind the host holds: its own branch (no refusal on its line), or the one a refusal of every other lets by. */
    const reads = (kind: string): boolean => base.some((line) => line.includes(`_type === '${kind}'`) && !line.includes('throw'))
      || base.some((line) => line.includes(`_type !== '${kind}'`) && line.includes('throw'));
    const problems: string[] = [];
    for (const kind of kinds) {
      const read = reads(kind);
      const add = PYTHON_ADDS[kind] !== undefined && asked(PYTHON_ADDS[kind]);
      const entry = L8_SOURCES[kind];
      if (read === (entry?.read !== undefined)) problems.push(`${kind}: Python ${read ? 'reads it now -- close' : 'cannot read it -- list'} its item`);
      if (add === (entry?.add !== undefined)) problems.push(`${kind}: Python ${add ? 'adds it now -- close' : 'cannot add it -- list'} its item`);
    }
    for (const kind of Object.keys(L8_SOURCES)) if (!kinds.includes(kind)) problems.push(`${kind}: no such source kind`);
    assert.deepEqual(problems, []);
    assert.deepEqual(PYTHON_ADDS, { frame: 'gridCube', join: 'joinCube' }, 'a new way for Python to add a grid comes with its test');
    pinned('L8_SOURCES', Object.keys(L8_SOURCES).length);
  });

  it("each headless export Python never asks is listed", () => {
    const entry = SOURCES.read('src/headless/entry.ts');
    const names = [...entry.matchAll(/^export \{([^}]*)\};/gm)].flatMap((m) => m[1]!.split(',').map((n) => n.trim()))
      .filter((n) => n.length > 0 && n !== 'REQUEST_KINDS' && !n.startsWith('host'));
    const unasked = new Map(names.filter((n) => !asked(n)).map((n) => [`${n}`, 1]));
    assert.deepEqual(heldTo(unasked, L8_UNASKED, 'headless'), []);
    pinned('L8_UNASKED', Object.keys(L8_UNASKED).length);
  });

  it('what is not checked yet is said, and only shrinks', () => {
    pinned('UNCHECKED', Object.keys(UNCHECKED_UNTIL_S6).length);
  });
});

// -- markers: the gaps no scan reaches, each pinned by the code that is it ------------------------------------------

/** `text` in `file` (`count` times) while its items are open: the fix takes it away, and this fails until they close.
 *  `absent`: the gap is that the text is NOT there. `within`: only the block that starts with it is read. */
interface Marker {
  readonly file: string;
  readonly text: string;
  readonly items: readonly string[];
  readonly count?: number;
  readonly absent?: true;
  readonly within?: string;
}
const MARKERS: readonly Marker[] = [
  // the grid
  // telling exactly what landed: placeholders until S1 writes each one's failing behavioural test first
  // the page
  // the board a cube alone shows its charts on is given no way to tell its page's edits
  // derived state, and what the keeper keeps
  { file: 'src/page-rebase.ts', text: 'export function pageReads(page: PageDocument): { frames: string[]; copies: string[]; ids: string[] } {', items: ['X3'] },
  // the browser's half of the same jobs done by different rules
  { file: 'src/headless/grid.ts', text: 'gridLens(', items: ['B1'], absent: true },
  { file: 'src/app/datacube-app.ts', text: "current.name ?? (page.suggestedName || 'page')", items: ['B3'], count: 5 },
  { file: 'src/headless/grid.ts', text: 'a page on Python reads frames', items: ['B7'], count: 2 },
  { file: 'src/app/datacube-app.ts', text: 'if (localEngine) joinGrid = (cube) => {', items: ['B11'] },
  // a model action's page record set outside the page state and told by its difference, not applied as commands (P15:
  // S5, with the model actions' move out of their dialogs, D5)
  { file: 'src/app/datacube-app.ts', text: 'for (const command of modelCommands(was, model)) channel?.edit({ page: command });', items: ['P15'], count: 2 },
];

describe('the gaps no scan reaches, pinned by their code', () => {
  for (const m of MARKERS) {
    it(`${m.items.join(' ')}: ${m.file}`, () => {
      let text = SOURCES.read(m.file);
      if (m.within !== undefined) {
        const at = text.indexOf(m.within);
        assert.ok(at >= 0, `${m.file} no longer holds the block "${m.within}": pin it again`);
        text = text.slice(at, text.indexOf('\n    });', at));
      }
      const found = text.split(m.text).length - 1;
      if (m.absent) assert.equal(found, 0, `${m.text} is in ${m.file} now: close ${m.items.join(', ')} and take this off`);
      else assert.equal(found, m.count ?? 1, `${m.file} holds "${m.text}" ${found} times, not ${m.count ?? 1}: fixed? close `
        + `${m.items.join(', ')} and take this off; moved? pin it again`);
    });
  }
  it('their number', () => pinned('MARKERS', MARKERS.length));
});

// -- a grid saved and opened again opens its rows as it was told to (closed S2: V5) --------------------------------

describe('a grid saved and opened again opens its rows as it was told to', () => {
  it('the saved tree keeps its expand level and the groups closed under it, and opens with them', () => {
    const tree = TreeState.empty(false).withExpandTo(1).setOpen(['EMEA'], false);
    const doc = writeCube({
      name: 'trades', source: { _type: 'frame', name: 't', columns: [{ name: 'region', type: 'String' }] },
      snapshot: { source: { query: { _type: 'var', name: 'x' } }, columns: [{ name: 'region', type: 'String' }], derived: [],
        rows: ['region'], pivotOn: [], sorts: [], epoch: 0 } as unknown as CubeSnapshot,
      configuration: { ...DEFAULT_CONFIGURATION, initialExpandToLevel: 1 },
      tree,
    });
    assert.equal(configurationOf(doc).initialExpandToLevel, 1);
    assert.deepEqual(doc.tree, { open: [], showTotals: false, expandTo: 1, closed: [['EMEA']] });
    // the screen opens a saved grid with the tree the document keeps (app.ts: options.tree, as given)
    const back = treeOf(readCube(JSON.parse(cubeToJson(doc))));
    assert.equal(back.key, tree.key, 'opened again: the expand level, and EMEA closed under it');
    assert.equal(back.isOpen(['AMER'], 2), true);
    assert.equal(back.isOpen(['EMEA'], 2), false);
  });
});

// -- the register, held to every ledger ------------------------------------------------------------------------------

/** The files that hold the ledgers, each naming items as string literals (a letter and a number, quoted). */
const LEDGERS = ['test/one-api-ledger.test.ts', 'test/one-api-gestures.test.ts', '../python/tests/test_one_api.py'];
const ITEM = /'([GTCPYVHZBXK]\d{1,2})'/g;
/** Every item the register has ever held: none leaves it but closed. */
const ALL = ('G1 G2 G3 G4 G5 G6 G7 G8 G9 G10 G11 G12 G13 G14 G15 T1 T2 T3 T4 C1 P1 P2 P3 P4 P5 P6 P7 P8 P9 P10 P11 P12 '
  + 'P13 P14 Y1 Y2 Y3 Y6 V1 V2 V4 V5 H1 H2 H3 Z1 Z2 Z3 Z4 Z5 Z6 Z7 Z8 Z9 Z10 Z11 Z12 Z13 Z14 Z15 Z16 Z17 Z18 Z19 B1 B2 '
  + 'B3 B4 B5 B6 B7 B8 B9 B10 B11 B12 B13 B14 X1 X2 X3 X4 X5 K1 K2 K3 K4 K5 K6 K7 K8 K9 K10 K11 K12 '
  // 2026-10-21, One API S2's, S3's and S4's audit rounds and S4's soak: what each found, added by rule 9 and closed with
  // each fix (no ledger grows)
  + 'F1 F2 F3 F4 F5 F6 F7 F8 F9 F10 F11 F12 F13 F14 F15 F16 F17 F18 F19 F20 F21 F22 F23 F24 F25 F26 F27 '
  // 2026-10-22, D10: S1's audit finding 10, registered with the user's sign-off
  + 'P15').split(' ');

describe("the plan's register and the ledgers say the same", () => {
  it('every open item is in a ledger, every item a ledger names is open, and none leaves the register unclosed', () => {
    const plan = SOURCES.read(PLAN);
    const open = new Set([...plan.matchAll(/^- \[ \] \*\*([A-Z]\d{1,2})\*\*/gm)].map((m) => m[1]!));
    const closed = new Set([...plan.matchAll(/^- \[x\] \*\*([A-Z]\d{1,2})\*\*/gm)].map((m) => m[1]!));
    const named = new Set(LEDGERS.flatMap((f) => [...SOURCES.read(f).matchAll(ITEM)].map((m) => m[1]!)));
    for (const k of Object.keys(UNCHECKED_UNTIL_S6)) named.add(k);
    assert.deepEqual([...open, ...closed].sort(), [...ALL].sort(), 'the register holds every item it ever held, each open or closed');
    assert.deepEqual([...open].filter((i) => !named.has(i)).sort(), [], 'open items no ledger holds');
    assert.deepEqual([...named].filter((i) => !open.has(i)).sort(), [], 'items a ledger holds that are not open');
    assert.deepEqual([...closed].filter((i) => named.has(i)).sort(), [], 'closed items still in a ledger');
  });
});
