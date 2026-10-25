// A FLOW SHEET (docs/DATACUBE_FLOW_MODE_2026_10_24.md FL4, FL5, FL6): its layout's rules (layout/flow.ts), what a box
// says it does (flow-boxes.ts), and its page commands -- a flow added, a box placed after the box it reads, moved, taken
// off (what read it live frozen to a copy), what only a board takes refused in words -- read and written whole, the
// rebase's renames, the page read back, and the cubes a page opens none of as it opens (page-document.ts boxCubes).

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { rawRows, writeCube, type CubeDocument, type CubeSource, type FileSource, type RowsSource } from '../src/cube-document.ts';
import { DEFAULT_CONFIGURATION } from '../src/config.ts';
import { boxSays } from '../src/flow-boxes.ts';
import { add, NEW_SHEET } from '../src/layout/bands.ts';
import {
  BOX_HEIGHT, BOX_WIDTH, EMPTY_FLOW, FlowRefusal, GAP_X, GAP_Y, MARGIN, flowProblems, moveBox, placeAfter, removeBox,
} from '../src/layout/flow.ts';
import { liveGrids } from '../src/live-inputs.ts';
import { applyPageCommand, idsOf, readPageCommand, type PageCommand, type PageDraft } from '../src/page-commands.ts';
import {
  boxCubes, layoutTiles, pageToJson, readPage, unnamedLabel, writePageOf, PageDocumentError, type PageDocument,
} from '../src/page-document.ts';
import { renamedCommand, rearranged } from '../src/page-rebase.ts';
import { CubeRefusal } from '../src/snapshot.ts';
import { TreeState } from '../src/tree.ts';
import { accessor } from '../../pure-protocol/src/index.ts';

const COLUMNS = [{ name: 'region', type: 'String' }, { name: 'notional', type: 'Float' }];
const FILE: FileSource = {
  _type: 'file', name: 'trades.csv', format: 'csv', size: 120, sha256: 'ab'.repeat(32), columns: COLUMNS,
};
const rows = (grid: string): RowsSource => ({ _type: 'rows', name: 'trades.csv', input: { grid }, columns: COLUMNS });

/** A cube over `source`, its query changed by `query` (a box's one step). */
function cube(source: CubeSource, query: (q: CubeDocument['query']) => CubeDocument['query'] = (q) => q,
  configuration: Readonly<Record<string, unknown>> = {}): CubeDocument {
  const written = writeCube({
    name: 'flow', source, snapshot: rawRows(accessor('local::DB', 'trades'), COLUMNS),
    configuration: DEFAULT_CONFIGURATION, tree: TreeState.fromPaths([], false),
  });
  return { ...written, query: query(written.query), configuration: { ...written.configuration, ...configuration } };
}

/** A page of one grid over the file, on a board. */
const BOARD = (): PageDocument => writePageOf({
  name: 'flow', cubes: [{ id: 'grid-1', cube: cube(FILE) }],
  views: { views: [{ id: 'grid-1', kind: 'grid', cube: 'grid-1' }], sheets: [{ id: 'sheet-1', layout: { kind: 'bands', ...add(NEW_SHEET, 'grid-1') } }] },
});

const draft = (page: PageDocument): PageDraft => ({ page, held: idsOf(page) });
const apply = (d: PageDraft, ...commands: PageCommand[]): PageDraft => commands.reduce((at, c) => applyPageCommand(at, c).draft, d);
const refused = (f: () => unknown, words: RegExp): void => {
  assert.throws(f, (e: unknown) => e instanceof CubeRefusal && words.test(e.message), String(words));
};
const addGrid = (document: CubeDocument, sheet: string, near: string | null): PageCommand =>
  ({ kind: 'addGrid', document, sheet, near, title: null, columns: null });

/** BOARD, then a flow: an Input (grid-2), a Filter after it (grid-3), and a Sort after the Input too (grid-4). */
function FLOW(): PageDraft {
  return apply(draft(BOARD()),
    { kind: 'addFlow', name: null },
    addGrid(cube(FILE), 'sheet-2', null),
    addGrid(cube(rows('grid-2'), (q) => ({ ...q, filter: { kind: 'condition', column: 'notional', operator: 'greaterThan', value: 100 } })), 'sheet-2', 'grid-2'),
    addGrid(cube(rows('grid-2'), (q) => ({ ...q, sorts: [{ column: 'notional', direction: 'desc' }] })), 'sheet-2', 'grid-2'));
}
const flowOf = (page: PageDocument, sheet = 'sheet-2') => {
  const layout = page.sheets.find((s) => s.id === sheet)!.layout;
  if (layout.kind !== 'flow') throw new Error(`${sheet} is no flow`);
  return layout;
};
const at = (page: PageDocument, tile: string) => flowOf(page).boxes.find((b) => b.tile === tile);

describe("a flow's layout (layout/flow.ts)", () => {
  it('places a box after the box it reads, the next column on its row, else the first free row there', () => {
    const one = placeAfter(EMPTY_FLOW, 'a');
    assert.deepEqual(one.boxes, [{ tile: 'a', x: MARGIN, y: MARGIN }]);
    const two = placeAfter(one, 'b', 'a');
    assert.deepEqual(two.boxes[1], { tile: 'b', x: MARGIN + BOX_WIDTH + GAP_X, y: MARGIN });
    const three = placeAfter(two, 'c', 'a');
    assert.deepEqual(three.boxes[2], { tile: 'c', x: MARGIN + BOX_WIDTH + GAP_X, y: MARGIN + BOX_HEIGHT + GAP_Y }, 'below the one taken');
    // after none: a row of its own below every box
    assert.deepEqual(placeAfter(three, 'd').boxes[3], { tile: 'd', x: MARGIN, y: MARGIN + 2 * (BOX_HEIGHT + GAP_Y) });
    assert.throws(() => placeAfter(three, 'a'), (e: unknown) => e instanceof FlowRefusal && /'a' is on the canvas already/.test(e.message));
  });

  it('moves a box to whole units, never off the canvas; takes one off', () => {
    const flow = placeAfter(placeAfter(EMPTY_FLOW, 'a'), 'b', 'a');
    assert.deepEqual(moveBox(flow, 'b', 300.4, 99.6).boxes[1], { tile: 'b', x: 300, y: 100 });
    assert.throws(() => moveBox(flow, 'b', -1, 0), /neither below zero, not \(-1, 0\)/);
    assert.throws(() => moveBox(flow, 'b', Number.NaN, 0), /two numbers/);
    assert.throws(() => moveBox(flow, 'z', 0, 0), /'z' is no box of this flow/);
    assert.deepEqual(removeBox(flow, 'a').boxes.map((b) => b.tile), ['b']);
    assert.deepEqual(removeBox(flow, 'z'), flow, 'a box it does not have: as it is');
  });

  it('says what is wrong with a flow read from somewhere', () => {
    assert.deepEqual(flowProblems({ boxes: [{ tile: 'a', x: 1, y: 2 }] }), []);
    assert.deepEqual(flowProblems({ boxes: [{ tile: 'a', x: 1, y: 2 }, { tile: 'a', x: 1.5, y: -2 }] }), [
      "'a' is on the canvas twice",
      "'a' is at (1.5, -2): a box's place is two whole numbers, neither below zero",
    ]);
  });
});

describe('what a box says it does (flow-boxes.ts boxSays): its query, never a field beside it', () => {
  it('a box over a source of its own is an Input; over another grid\'s rows, each step its query takes', () => {
    assert.deepEqual(boxSays(cube(FILE)), { tool: 'input', also: [], says: 'trades.csv' });
    assert.deepEqual(boxSays(cube(rows('grid-1'))), { tool: 'browse', also: [], says: 'every row' });
    const filtered = boxSays(cube(rows('grid-1'), (q) => ({ ...q, filter: { kind: 'condition', column: 'notional', operator: 'greaterThan', value: 100 } })));
    assert.equal(filtered.tool, 'filter');
    assert.match(filtered.says, /notional .* 100/);
    // the order a grid takes its steps in: its calculated columns before its sorts
    const both = boxSays(cube(rows('grid-1'), (q) => ({
      ...q, sorts: [{ column: 'notional', direction: 'desc' }], derived: [{ name: 'big', type: 'Boolean' } as never],
    })));
    assert.deepEqual(both, { tool: 'formula', also: ['sort'], says: 'big' });
    assert.deepEqual(boxSays(cube(rows('grid-1'), (q) => ({ ...q, rows: ['region'] }))), { tool: 'summarize', also: [], says: 'by region' });
    assert.deepEqual(boxSays(cube(rows('grid-1'), (q) => q, { columns: { region: { hidden: true } } })),
      { tool: 'select', also: [], says: '1 of 2 columns left out' });
  });
});

describe("a flow sheet's page commands", () => {
  it('adds a flow, its boxes each after the box it reads, as the palette adds them', () => {
    const { page } = FLOW();
    assert.deepEqual(page.sheets.map((s) => [s.id, s.layout.kind]), [['sheet-1', 'bands'], ['sheet-2', 'flow']]);
    assert.deepEqual(at(page, 'grid-2'), { tile: 'grid-2', x: MARGIN, y: MARGIN });
    assert.deepEqual(at(page, 'grid-3'), { tile: 'grid-3', x: MARGIN + BOX_WIDTH + GAP_X, y: MARGIN });
    assert.deepEqual(at(page, 'grid-4'), { tile: 'grid-4', x: MARGIN + BOX_WIDTH + GAP_X, y: MARGIN + BOX_HEIGHT + GAP_Y });
    assert.equal(unnamedLabel(page, page.sheets[1]!), 'Flow 2', 'a flow is called a flow until someone names it');
    // read back whole: the page's own rules hold it
    assert.equal(pageToJson(readPage(JSON.parse(pageToJson(page)))), pageToJson(page));
  });

  it('places a box by hand: a flow\'s alone', () => {
    const d = FLOW();
    const moved = apply(d, { kind: 'placeBox', tile: 'grid-3', x: 500.4, y: 12 }).page;
    assert.deepEqual(at(moved, 'grid-3'), { tile: 'grid-3', x: 500, y: 12 });
    refused(() => apply(d, { kind: 'placeBox', tile: 'grid-1', x: 0, y: 0 }), /a tile of a board, placed by its layout: a box is a flow sheet's/);
    refused(() => apply(d, { kind: 'placeBox', tile: 'grid-3', x: -4, y: 0 }), /neither below zero/);
    refused(() => apply(d, { kind: 'placeBox', tile: 'grid-9', x: 0, y: 0 }), /grid-9/);
  });

  it('refuses in words what only a board takes: a chart, a text tile, a stack, an arrangement, a tile from a board', () => {
    const d = FLOW();
    refused(() => apply(d, { kind: 'addText', sheet: 'sheet-2', text: 'hi', title: null, columns: null }), /a text tile goes on a board sheet/);
    refused(() => apply(d, { kind: 'stack', tiles: ['grid-2', 'grid-3'] }), /a flow sheet's boxes are not stacked/);
    refused(() => apply(d, { kind: 'arrange', sheet: 'sheet-2', preset: 'grid', order: null }), /a flow sheet's boxes are placed on its canvas/);
    refused(() => apply(d, { kind: 'moveTile', tile: 'grid-1', sheet: 'sheet-2', columns: null }), /a flow sheet holds boxes, each a grid that does one step: a grid stays on a board/);
    refused(() => apply(d, { kind: 'moveTile', tile: 'grid-3', sheet: 'sheet-1', columns: null }), /'grid-3' is a box of a flow: it moves to another flow sheet/);
    // a box between flows: placed on the other's canvas
    const two = apply(d, { kind: 'addFlow', id: 'sheet-5', name: 'Second' }, { kind: 'moveTile', tile: 'grid-4', sheet: 'sheet-5', columns: null }).page;
    assert.deepEqual(layoutTiles(two.sheets.find((s) => s.id === 'sheet-5')!.layout), ['grid-4']);
    assert.deepEqual(layoutTiles(flowOf(two)), ['grid-2', 'grid-3']);
  });

  it('a layout told whole is its own kind: a flow\'s boxes for a flow, a board\'s bands for a board', () => {
    const d = FLOW();
    const flow = flowOf(d.page);
    const told = apply(d, { kind: 'layout', sheet: 'sheet-2', layout: { kind: 'flow', boxes: [...flow.boxes].reverse() } }).page;
    assert.deepEqual(layoutTiles(flowOf(told)), ['grid-4', 'grid-3', 'grid-2']);
    refused(() => apply(d, { kind: 'layout', sheet: 'sheet-2', layout: { kind: 'bands', ...add(NEW_SHEET, 'grid-2') } }),
      /'sheet-2' is a flow sheet: its layout is boxes/);
    refused(() => apply(d, { kind: 'layout', sheet: 'sheet-2', layout: { kind: 'flow', boxes: flow.boxes.slice(1) } }),
      /a sheet's layout holds its tiles, each once/);
  });

  it('a box taken off leaves every box that read it live reading a copy of what it was (FL6)', () => {
    const { page } = apply(FLOW(), { kind: 'removeTile', tile: 'grid-2' });
    assert.deepEqual(layoutTiles(flowOf(page)), ['grid-3', 'grid-4']);
    for (const id of ['grid-3', 'grid-4']) {
      const source = page.cubes.find((c) => c.id === id)!.cube.source;
      assert.deepEqual(liveGrids(source), [], `${id} reads no box live`);
      if (source._type !== 'rows' || 'grid' in source.input) throw new Error(`${id} is not frozen`);
      assert.equal(source.input.name, 'trades.csv');
      assert.deepEqual(source.input.source, FILE);
    }
    // the page read back: no link left to a grid it does not have
    readPage(JSON.parse(pageToJson(page)));
  });

  it('reads and writes its commands, refused in words', () => {
    for (const c of [{ kind: 'addFlow', id: 'sheet-3', name: 'Prep' }, { kind: 'addFlow', name: null }, { kind: 'placeBox', tile: 'grid-2', x: 10, y: 20 }]) {
      assert.deepEqual(readPageCommand(JSON.parse(JSON.stringify(c))), c);
    }
    assert.throws(() => readPageCommand({ kind: 'placeBox', tile: 'grid-2', x: '10', y: 20 }), /a box's place is two numbers, not \("10", 20\)/);
    assert.throws(() => readPageCommand({ kind: 'placeBox', tile: 'grid-2', x: 1, y: 2, z: 3 }), /z/);
  });

  it("follows the page's renames, and a layout told before a box came keeps it where adding put it", () => {
    const renamed = new Map([['grid-3', 'grid-7'], ['sheet-2', 'sheet-9']]);
    assert.deepEqual(renamedCommand({ kind: 'placeBox', tile: 'grid-3', x: 1, y: 2 }, renamed), { kind: 'placeBox', tile: 'grid-7', x: 1, y: 2 });
    assert.deepEqual(renamedCommand({ kind: 'addFlow', id: 'sheet-2', name: null }, renamed), { kind: 'addFlow', id: 'sheet-9', name: null });
    const { page } = FLOW();
    const stale: PageCommand = { kind: 'layout', sheet: 'sheet-2', layout: { kind: 'flow', boxes: [{ tile: 'grid-2', x: 400, y: 400 }, { tile: 'gone', x: 0, y: 0 }] } };
    const now = rearranged(stale, page);
    if (now.kind !== 'layout' || now.layout.kind !== 'flow') throw new Error('not a flow layout');
    assert.deepEqual(now.layout.boxes.find((b) => b.tile === 'grid-2'), { tile: 'grid-2', x: 400, y: 400 });
    assert.deepEqual(now.layout.boxes.map((b) => b.tile).sort(), ['grid-2', 'grid-3', 'grid-4']);
  });
});

describe('a flow page read, and what it opens as it opens', () => {
  it('refuses a flow\'s tile that is no grid, and a box placed twice or off the canvas', () => {
    const page = FLOW().page;
    const raw = JSON.parse(pageToJson(page)) as { sheets: { layout: { boxes?: unknown[] } }[]; views: unknown[] };
    const withBoxes = (boxes: unknown[]): unknown => ({ ...raw, sheets: raw.sheets.map((s, i) => (i === 1 ? { ...s, layout: { ...s.layout, boxes } } : s)) });
    const box = (tile: string, x = 0, y = 0): unknown => ({ tile, x, y });
    assert.throws(() => readPage(withBoxes([box('grid-2'), box('grid-3'), box('grid-4'), box('grid-4', 9, 9)])),
      (e: unknown) => e instanceof PageDocumentError && /twice/.test(e.message));
    assert.throws(() => readPage(withBoxes([box('grid-2', -1), box('grid-3'), box('grid-4')])), /neither below zero/);
  });

  it('opens none of a flow\'s boxes as it opens: each is a document until it is shown (boxCubes)', () => {
    const { page } = FLOW();
    assert.deepEqual([...boxCubes(page)].sort(), ['grid-2', 'grid-3', 'grid-4']);
    assert.deepEqual([...boxCubes(BOARD())], [], 'a board\'s grids open as the page opens');
  });
});
