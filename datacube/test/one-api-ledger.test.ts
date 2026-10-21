// THE ONE API LEDGERS, STATIC HALF (docs/DATACUBE_ONE_API_PLAN_2026_10_21.md §3). Every known place where the tenet --
// every change to a saved page is a command, applied by one function, and the browser and Python do the same -- does not
// hold yet is listed here by its item in the plan's register (§4), and only there. A new one fails the build; a listed
// one that is gone fails it too, until its item is closed and its entry taken off: the lists only shrink, and stay true.
// The register itself is held to every ledger (the last test): an open item is in some ledger, a closed one in none.
//
//   L1  what changes a grid's state, the screen's Snap, the host's own writes of the page -- other than by command
//   L8  every command kind and source kind against who makes it: the screen, Python, the host's reader
//   L10 (the browser's half) where the two sides keep different rules, and the other gaps no scan reaches: each pinned
//       by the code that is the gap (a marker), so fixing it fails here until its item is closed
//
// The behavioural half is one-api-gestures.test.ts (L2, L3); Python's is python/tests/test_one_api.py (L4-L7, L9, L10).

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { Sources } from '../../tools/js/runfiles.mts';
import { DEFAULT_CONFIGURATION } from '../src/config.ts';
import { configurationOf, treeOf, writeCube } from '../src/cube-document.ts';
import { readGridCommand } from '../src/grid-commands.ts';
import type { CubeSnapshot } from '../src/snapshot.ts';
import { TreeState } from '../src/tree.ts';
import { codeLines, heldTo, sites, unionKinds, unionTypes } from './one-api/scan.ts';

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

// -- L1 -----------------------------------------------------------------------------------------------------------

/** Every call in the grid (app.ts) that changes its state, by where it is made. */
const GRID_CALLS = /this\.(#change|#configure|#query|#owner\.change|#owner\.undo|#owner\.redo|#owner\.retryRefused)\(/g;
const PLUMBING = 'the one way a change reaches the state owner: no gap of its own';
const L1_GRID: Record<string, Entry> = {
  '#change:#owner.change': { items: [], why: PLUMBING },
  '#configure:#change': { items: [], why: PLUMBING },
  '#query:#change': { items: [], why: PLUMBING },
  '#applyCommands:#change': { items: [], why: 'the command path: applyGridCommands, told by onCommand' },
  'onToggleExpand:#change': { items: ['G1'] },
  '#onMenuAction:#change': { items: ['G2'] },
  '#setCalc:#change': { items: ['G4', 'G5'] },
  '#applyFilter:#query': { items: ['G6'] },
  'refilter:#query': { items: ['G7'] },
  'useDimension:#query': { items: ['G9'] },
  'applyConfiguration:#configure': { items: ['G10'] },
  'change:#change': { items: ['G11'] },
  '#undo:#owner.undo': { items: ['G12'], why: 'N2: undo writes the page whole, as a restore' },
  '#redo:#owner.redo': { items: ['G12'], why: 'N2' },
  'submit:#owner.retryRefused': { items: ['G13'] },
};

/** The command path given a change of its own beside the commands (`#applyCommands(commands, label, then)`). */
const GRID_THEN = /this\.(#applyCommands)\([^,()]+,[^,()]+,/g;
const L1_THEN: Record<string, Entry> = {
  'applyGridCommands:#applyCommands': { items: [], why: "another editor's commands: `then` changes nothing, nothing is recorded" },
  '#applyDraft:#applyCommands': { items: ['G3'] },
};

/** Where the grid says the rows it reads moved (a snap, going live): the page's copies change. */
const GRID_PLANE = /this\.(#emit)\('plane'\)/g;
const L1_PLANE: Record<string, Entry> = {
  'snapRows:#emit': { items: ['P3'] },
  'done:#emit': { items: ['P3'] },
  '#planeMoved:#emit': { items: [], why: "following another editor's snap or going live" },
};

/** The host's own writes of the page (app/datacube-app.ts): its name, its model, the page replaced, cleared, restored. */
const HOST_CALLS = /(current\s*=\s*\{|page\.setTitle\(|page\.clear\(\)|page\.restore\()/g;
const L1_HOST: Record<string, Entry> = {
  'renamePage:page.setTitle(': { items: ['P1'], count: 2 },
  'renamePage:current = {': { items: ['P1'] },
  'saveTo:current = {': { items: ['P2', 'B4'] },
  'saveTo:page.setTitle(': { items: ['P2', 'B4'] },
  'makeClass:current = {': { items: ['P4'] },
  'landed:current = {': { items: ['P4'] },
  'inPlace:page.clear()': { items: ['P10'] },
  'inPlace:current = {': { items: ['P10'], count: 2 },
  'inPlace:page.setTitle(': { items: ['P10'] },
  'openSaved:page.restore(': { items: ['P10'], why: 'N1 off a served page: a page born whole' },
  'openSaved:current = {': { items: ['P10'], count: 2 },
  'openSaved:page.setTitle(': { items: ['P10'] },
  'blankPage:page.clear()': { items: ['P10', 'B9'] },
  'blankPage:current = {': { items: ['P10', 'B9'] },
  'blankPage:page.setTitle(': { items: ['P10', 'B9'] },
  'openFrame:current = {': { items: ['P12', 'B10'] },
  'openFrame:page.setTitle(': { items: ['P12', 'B10'] },
  'followLive:current = {': { items: [], why: "following another editor's page: its name as it now is", count: 2 },
};

describe('L1: every change of a grid, a copy or the page made other than by command is listed', () => {
  const app = SOURCES.read('src/app.ts');
  it('the grid', () => {
    assert.deepEqual(heldTo(sites(app, GRID_CALLS), L1_GRID, 'app.ts'), []);
    assert.deepEqual(heldTo(sites(app, GRID_THEN), L1_THEN, 'app.ts'), []);
  });
  it('the rows a grid reads (Snap, Go Live)', () => {
    assert.deepEqual(heldTo(sites(app, GRID_PLANE), L1_PLANE, 'app.ts'), []);
  });
  it("the host's own writes of the page", () => {
    assert.deepEqual(heldTo(sites(SOURCES.read('src/app/datacube-app.ts'), HOST_CALLS), L1_HOST, 'datacube-app.ts'), []);
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
  renamePage: { screen: { items: ['P1', 'P2'] } },
  moveSheet: { python: { items: ['K8'] } },
  removeSheet: { python: { items: ['K8'] } },
  stack: { screen: { items: ['P14'] } },
  arrange: { screen: { items: ['P14'] } },
  setPageFilter: { python: { items: ['K7'] } },
  removePageFilter: { python: { items: ['K7'] } },
  snap: { screen: { items: ['P3'] } },
  live: { screen: { items: ['P3'] } },
};

/** Each source kind's missing cells: Python reads a page over it (headless gridBase), Python adds a grid over it. */
const L8_SOURCES: Record<string, { readonly read?: Entry; readonly add?: Entry }> = {
  file: { read: { items: ['X1', 'X2', 'B7'] }, add: { items: ['X4'] } },
  remoteFile: { read: { items: ['X1', 'X2', 'B7'] }, add: { items: ['X4'] } },
  warehouseTable: { read: { items: ['X1', 'X2', 'B7'] }, add: { items: ['X4'] } },
  savedQuery: { read: { items: ['X1', 'X5'] }, add: { items: ['X4'] } },
  class: { read: { items: ['X1', 'X5'] }, add: { items: ['X4'] } },
};
/** The headless functions by which Python adds a grid over a source kind today. */
const PYTHON_ADDS: Record<string, string> = { frame: 'gridCube', join: 'joinCube' };

/** What the screen can do to a page that Python's API cannot yet: no kind of its own (the commands exist), so held as
 *  a list here and closed by S6's methods, each with its test. */
const L8_CAPABILITIES: Record<string, string> = {
  K1: 'sorts replaced, one cleared, all cleared, after a grid is made',
  K3: 'a calculated column edited, renamed, removed',
  K4: 'group-level, window, child-aggregate and explode columns; the Show As and period presets',
  K5: 'rows expanded, collapsed, all collapsed',
  K6: 'a dimension used',
  K9: 'a chart drilled, and back up',
  K10: 'Copy of Grid',
  K11: "a chart's Open in grid and Update",
  K12: 'a saved page opened by its id',
};

/** Headless exports Python never asks (`call('<name>'`): each one Python should. */
const L8_UNASKED: Record<string, Entry> = {
  pageFilter: { items: ['Z19'] },
  joinQuestions: { items: ['Z19'] },
};

describe("L8: every command kind and source kind, against who makes it", () => {
  const read = (f: string): string => codeLines(SOURCES.read(f)).join('\n');
  // Python's own comments left out (a line of code may start with `*`: a list spread)
  const python = SOURCES.under(PY, '.py')
    .map((f) => SOURCES.read(f).split('\n').filter((line) => !line.trim().startsWith('#')).join('\n')).join('\n');
  const headless = SOURCES.under('src/headless', '.ts');
  /** The exported functions of the headless modules, each with its body. */
  const exported = headless.flatMap((f) => SOURCES.read(f).split(/\n(?=export (?:async )?function )/)
    .map((body) => ({ name: /^export (?:async )?function (\w+)/.exec(body)?.[1], body })))
    .filter((e): e is { name: string; body: string } => e.name !== undefined);
  const asked = (name: string): boolean => python.includes(`'${name}'`);
  const grid = SOURCES.read('src/grid-commands.ts');
  const screen = SOURCES.under('src', '.ts')
    .filter((f) => !f.startsWith('src/headless/') && !['src/grid-commands.ts', 'src/page-commands.ts', 'src/page-rebase.ts'].includes(f))
    .map(read).join('\n')
    // the Properties window's commands are built by draftCommands, the screen's
    + grid.slice(grid.indexOf('export function draftCommands'), grid.indexOf('function settingsPatch'));
  const made = (text: string, kind: string): boolean => new RegExp(`(?<!readonly )kind: '${kind}'`).test(text);

  it('each command kind is made by the screen and by Python, or listed', () => {
    const kinds = [...unionKinds(grid, 'GridCommand'), ...unionKinds(SOURCES.read('src/page-commands.ts'), 'PageCommand')];
    const problems: string[] = [];
    for (const kind of kinds) {
      const has: Record<Cell, boolean> = {
        screen: made(screen, kind),
        python: new RegExp(`'kind': '${kind}'`).test(python) || exported.some((e) => made(e.body, kind) && asked(e.name)),
      };
      for (const cell of ['screen', 'python'] as const) {
        const listed = L8_KINDS[kind]?.[cell];
        if (!has[cell] && !listed) problems.push(`${kind}: no ${cell} makes it -- make one, or list it with its item`);
        if (has[cell] && listed) problems.push(`${kind}: the ${cell} makes it now -- close its item, take it off`);
      }
    }
    for (const kind of Object.keys(L8_KINDS)) if (!kinds.includes(kind)) problems.push(`${kind}: no such kind`);
    assert.deepEqual(problems, []);
  });

  it('each source kind is read and added by Python, or listed', () => {
    const base = exported.find((e) => e.name === 'gridBase')!.body;
    const problems: string[] = [];
    for (const kind of unionTypes(SOURCES.read('src/cube-document.ts'), 'CubeSource')) {
      const read = kind === 'frame' || new RegExp(`_type (?:===|!==) '${kind}'`).test(base);
      const add = PYTHON_ADDS[kind] !== undefined && asked(PYTHON_ADDS[kind]);
      const listed = L8_SOURCES[kind];
      if (read === (listed?.read !== undefined)) problems.push(`${kind}: Python ${read ? 'reads it now -- close' : 'cannot read it -- list'} its item`);
      if (add === (listed?.add !== undefined)) problems.push(`${kind}: Python ${add ? 'adds it now -- close' : 'cannot add it -- list'} its item`);
    }
    assert.deepEqual(problems, []);
  });

  it("each headless export Python never asks is listed", () => {
    const entry = SOURCES.read('src/headless/entry.ts');
    const names = [...entry.matchAll(/^export \{([^}]*)\};/gm)].flatMap((m) => m[1]!.split(',').map((n) => n.trim()))
      .filter((n) => n.length > 0 && n !== 'REQUEST_KINDS' && !n.startsWith('host'));
    const unasked = new Map(names.filter((n) => !asked(n)).map((n) => [`${n}`, 1]));
    assert.deepEqual(heldTo(unasked, L8_UNASKED, 'headless'), []);
  });
});

// -- markers: the gaps no scan reaches, each pinned by the code that is it ------------------------------------------

/** `text` in `file` (`count` times) while its items are open: the fix takes it away, and this fails until they close.
 *  `absent`: the gap is that the text is NOT there. */
interface Marker {
  readonly file: string;
  readonly text: string;
  readonly items: readonly string[];
  readonly count?: number;
  readonly absent?: true;
}
const MARKERS: readonly Marker[] = [
  // the grid
  { file: 'src/page/cube-page.ts', text: "source.refilter([], view.selection, 'filter to chart mark')", items: ['G8'] },
  { file: 'src/page/cube-page.ts', text: "source.refilter([], selection, 'filter to chart mark')", items: ['G8'] },
  { file: 'src/app.ts', text: 'options.titleBarHidden ? { ...given, showTitleBar: false } : given', items: ['G14'] },
  { file: 'src/app.ts', text: '{ ...DEFAULT_CONFIGURATION, reportTitle: source.label, maxRows: DEFAULT_MAX_ROWS }', items: ['G15'] },
  // telling exactly what landed
  { file: 'src/state-owner.ts', text: 'state: this.#rules.fold(update(pending.state)),', items: ['T1'] },
  { file: 'src/app.ts', text: "case 'presentation':\n        if (applying) this.#tell(applying.commands);", items: ['T2'] },
  { file: 'src/state-owner.ts', text: 'const carriedMove = carried?.move ? { ...carried.move, on: carried.move.on ?? carried.state } : undefined;', items: ['T3'] },
  { file: 'src/app.ts', text: 'if (applying) this.#untold.push(...applying.commands);', items: ['T4'] },
  // the page
  { file: 'src/page/page-app.ts', text: "#move(way: 'undo' | 'redo'): void {", items: ['P5'] },
  { file: 'src/ui/chart-panel.ts', text: 'this.#options.onSpec?.();\n    if (redrawForm) this.#paintForm();\n    this.refresh();', items: ['P6'] },
  { file: 'src/page/cube-page.ts', text: 'this.#place({ id: tile, title: `Editing ${chartTitle}`, element: host, actions: [update] }, chartId);', items: ['P7'] },
  { file: 'src/page/page-app.ts', text: 'if (!make || tileOf.has(view.cube)) continue;', items: ['P9'] },
  { file: 'src/app/sources.ts', text: "if (src._type === 'frame') return openedFrame(src.name, doc, several);", items: ['P11'] },
  { file: 'src/page/cube-page.ts', text: 'if (this.#quiet > 0 || !this.#options.onPageEdit) return;', items: ['P13'] },
  // the browser's half of the same jobs done by different rules
  { file: 'src/headless/grid.ts', text: 'gridLens', items: ['B1'], absent: true },
  { file: 'src/page/cube-page.ts', text: 'title === this.#titles.get(tile) ? null : title', items: ['B2'] },
  { file: 'src/app/datacube-app.ts', text: "current.name ?? (page.suggestedName || 'page')", items: ['B3'], count: 5 },
  { file: 'src/app/datacube-app.ts', text: 'a page file cannot carry them yet.', items: ['B5'] },
  { file: 'src/headless/grid.ts', text: 'a page on Python reads frames', items: ['B7'], count: 3 },
  { file: 'src/page/cube-page.ts', text: 'pill.disabled = detached;', items: ['B8'] },
  { file: 'src/app/datacube-app.ts', text: 'if (localEngine) joinGrid = (cube) => {', items: ['B11'] },
  { file: 'src/app/datacube-app.ts', text: 'current.cubeId : crypto.randomUUID()', items: ['B13'] },
  { file: 'src/page/page-app.ts', text: 'There is nothing on this page to save: add a data source first.', items: ['B14'] },
  // the page's other gaps
  { file: 'src/page/cube-page.ts', text: "const own = `${chartId}-query`;", items: ['P8'] },
  // derived state, and what the keeper keeps
  { file: 'src/headless/grid.ts', text: 'return (await typeColumns(snapshot, runner)).snapshot;', items: ['V4'] },
  { file: 'src/page-rebase.ts', text: 'export function pageReads(page: PageDocument): { frames: string[]; copies: string[]; ids: string[] } {', items: ['X3'] },
];

describe('the gaps no scan reaches, pinned by their code', () => {
  for (const m of MARKERS) {
    it(`${m.items.join(' ')}: ${m.file}`, () => {
      const found = SOURCES.read(m.file).split(m.text).length - 1;
      if (m.absent) assert.equal(found, 0, `${m.text} is in ${m.file} now: close ${m.items.join(', ')} and take this off`);
      else assert.equal(found, m.count ?? 1, `${m.file} holds "${m.text}" ${found} times, not ${m.count ?? 1}: fixed? close `
        + `${m.items.join(', ')} and take this off; moved? pin it again`);
    });
  }
});

// -- C1: what the host reads ----------------------------------------------------------------------------------------

describe('C1: the host reads every command the screen sends', () => {
  it('a window column (Show As: a running total) is refused by the reader the host reads edits with -- C1 open', () => {
    assert.throws(() => readGridCommand({
      kind: 'calculate', level: 'row',
      column: { name: 'running', window: { fn: 'sum', column: 'notional', partition: [], order: [], frame: 'running' } },
    }), /a calculated column from JSON/);
  });
});

// -- V5: what a saved grid keeps of its open rows -----------------------------------------------------------------

/** The derived-state gaps held here by behaviour (asserted as they are: closing one fails its check). */
const DERIVED: Record<string, readonly string[]> = { 'the saved tree forgets the expand level': ['V5'] };

describe('V5: a grid saved and opened again opens its rows as it was told to', () => {
  it(`its expand level is the configuration's, but ${Object.keys(DERIVED)[0]} -- V5 open`, () => {
    const doc = writeCube({
      name: 'trades', source: { _type: 'frame', name: 't', columns: [{ name: 'region', type: 'String' }] },
      snapshot: { source: { query: { _type: 'var', name: 'x' } }, columns: [{ name: 'region', type: 'String' }], derived: [],
        rows: ['region'], pivotOn: [], sorts: [], epoch: 0 } as unknown as CubeSnapshot,
      configuration: { ...DEFAULT_CONFIGURATION, initialExpandToLevel: 1 },
      tree: TreeState.empty(false).withExpandTo(1),
    });
    assert.equal(configurationOf(doc).initialExpandToLevel, 1);
    // the screen opens a saved grid with the tree the document keeps (app.ts: options.tree, as given)
    assert.equal(treeOf(doc).expandTo, 0, 'V5 open: the saved tree forgets the expand level');
  });
});

// -- the register, held to every ledger ------------------------------------------------------------------------------

/** The files that hold the ledgers, each naming items as string literals ('G1'). */
const LEDGERS = ['test/one-api-ledger.test.ts', 'test/one-api-gestures.test.ts', '../python/tests/test_one_api.py'];
const ITEM = /'([GTCPYVHZBXK]\d{1,2})'/g;

describe("the plan's register and the ledgers say the same", () => {
  it('every open item is in a ledger, and every item a ledger names is open', () => {
    const plan = SOURCES.read(PLAN);
    const open = new Set([...plan.matchAll(/^- \[ \] \*\*([A-Z]\d{1,2})\*\*/gm)].map((m) => m[1]!));
    const closed = new Set([...plan.matchAll(/^- \[x\] \*\*([A-Z]\d{1,2})\*\*/gm)].map((m) => m[1]!));
    const named = new Set(LEDGERS.flatMap((f) => [...SOURCES.read(f).matchAll(ITEM)].map((m) => m[1]!)));
    for (const k of Object.keys(L8_CAPABILITIES)) named.add(k);
    assert.ok(open.size > 80, `the register lists its items (${open.size} found)`);
    assert.deepEqual([...open].filter((i) => !named.has(i)).sort(), [], 'open items no ledger holds');
    assert.deepEqual([...named].filter((i) => !open.has(i)).sort(), [], 'items a ledger holds that are not open');
    assert.deepEqual([...closed].filter((i) => named.has(i)).sort(), [], 'closed items still in a ledger');
  });
});
