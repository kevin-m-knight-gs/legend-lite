// A FLOW SHEET ON A PAGE OF ITS OWN (page/cube-page.ts, page/page-app.ts; docs/DATACUBE_FLOW_MODE_2026_10_24.md M1's
// design, 3): a flow's boxes are documents until one is shown -- a page opens none of their grids, and writes each box's
// cube as the page has it; a box shown has its grid made by the host over its cube as the page has it now, in the
// results pane, the box shown before closed with what its grid did kept; a box taken off leaves the boxes that read it
// reading a copy (FL6), undone as one step; Create ▸ Flow, the palette's tool told to the host, a box moved by the keyboard.
//
// Real grids (CubeApps over a fake engine: cube-fixture.ts), on a page of its own made as DataCube's app makes one.

import assert from 'node:assert/strict';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { JSDOM } from 'jsdom';

import { CubeApp } from '../src/app.ts';
import { DEFAULT_CONFIGURATION } from '../src/config.ts';
import { openCube, writeCube, type CubeDocument, type CubeSource, type FileSource, type RowsSource } from '../src/cube-document.ts';
import type { FlowTool } from '../src/flow-boxes.ts';
import { liveGrids } from '../src/live-inputs.ts';
import { PageApp, type GridMaker } from '../src/page/page-app.ts';
import { layoutTiles, pageToJson, readPage, writePageOf, type PageDocument } from '../src/page-document.ts';
import { TreeState } from '../src/tree.ts';

import { GateEngine, SNAPSHOT, StubPlanner, settle, shown } from './cube-fixture.ts';

let dom: JSDOM;
let host: HTMLElement;
let engine: GateEngine;
let page: PageApp | undefined;
/** Each box the host was asked to show, and the page it was asked over. */
let asked: { box: string; source: CubeSource; page: PageDocument }[];
let tools: { tool: FlowTool; after: string | undefined; sheet: string }[];

const pg = (): PageApp => {
  assert.ok(page, 'the test made a page');
  return page;
};

beforeEach(() => {
  dom = new JSDOM('<!doctype html><div id="p"></div>');
  const g = globalThis as unknown as Record<string, unknown>;
  g['window'] = dom.window;
  g['document'] = dom.window.document;
  g['requestAnimationFrame'] = (cb: FrameRequestCallback) => {
    cb(0);
    return 1;
  };
  g['cancelAnimationFrame'] = () => {};
  host = dom.window.document.getElementById('p') as unknown as HTMLElement;
  engine = new GateEngine();
  page = undefined;
  asked = [];
  tools = [];
});

afterEach(async () => {
  await settle();
  page?.dispose();
  page = undefined;
});

const COLUMNS = SNAPSHOT.columns.map((c) => ({ name: c.name, type: c.type }));
const FILE: FileSource = { _type: 'file', name: 'trades.csv', format: 'csv', size: 10, sha256: 'ab'.repeat(32), columns: COLUMNS };
const rows = (grid: string): RowsSource => ({ _type: 'rows', name: 'trades.csv', input: { grid }, columns: COLUMNS });

/** A grid over a saved cube, its source the cube's own (a box's: another grid's rows), as DataCube's app opens one. */
const overCube = (saved: CubeDocument): GridMaker => (gridHost, spawned, start) => {
  const opened = openCube(saved, SNAPSHOT.source, SNAPSHOT.columns);
  return new CubeApp(gridHost, start?.snapshot ?? opened.snapshot, {
    engine, planner: new StubPlanner(), compact: true, cubeSource: saved.source, sourceLabel: saved.source.name,
    configuration: start?.configuration ?? opened.configuration, ...spawned,
  });
};

const cube = (source: CubeSource, rowsOf: readonly string[] = []): CubeDocument => writeCube({
  name: 'flow', source, snapshot: { ...SNAPSHOT, rows: rowsOf }, configuration: DEFAULT_CONFIGURATION, tree: TreeState.fromPaths([], false),
});

/** A page: grid-1 over the file on a board; a flow of an Input (grid-2) and a box after it (grid-3). */
function FLOW_PAGE(): PageDocument {
  return writePageOf({
    name: 'flows',
    cubes: [{ id: 'grid-1', cube: cube(FILE) }, { id: 'grid-2', cube: cube(FILE) }, { id: 'grid-3', cube: cube(rows('grid-2')) }],
    views: {
      views: ['grid-1', 'grid-2', 'grid-3'].map((id) => ({ id, kind: 'grid' as const, cube: id })),
      sheets: [
        { id: 'sheet-1', layout: { kind: 'bands', fit: true, bands: [{ height: 1, node: { tile: 'grid-1' } }] } },
        { id: 'sheet-2', layout: { kind: 'flow', boxes: [{ tile: 'grid-2', x: 40, y: 40 }, { tile: 'grid-3', x: 288, y: 40 }] } },
      ],
    },
  });
}

function opened(doc: PageDocument = FLOW_PAGE()): PageApp {
  page = new PageApp({
    host, title: 'flows', empty: (slot) => { slot.textContent = 'Add a data source'; },
    openBox: async (box, document, now) => {
      asked.push({ box, source: document.source, page: now });
      return overCube(document);
    },
    onFlowTool: (tool, after, sheet) => { tools.push({ tool, after, sheet }); },
  });
  const back = readPage(JSON.parse(pageToJson(doc)));
  // the host opens a board's grids; a flow's boxes are documents (page-document.ts boxCubes): none asked for
  page.restore(back, new Map([['grid-1', overCube(back.cubes[0]!.cube)]]));
  return page;
}

const box = (id: string): HTMLElement => host.querySelector(`.dc-flow-box[data-tile="${id}"]`) as HTMLElement;
const key = (el: HTMLElement, k: string, extra: KeyboardEventInit = {}): void => {
  el.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: k, bubbles: true, ...extra }));
};
/** A box shown, as Enter on it shows it, once its grid is made. */
async function show(id: string): Promise<void> {
  key(box(id), 'Enter');
  for (let i = 0; i < 50 && !pg().grids.includes(id); i += 1) await settle();
  await pg().ready();
}
const written = (): PageDocument => {
  const doc = pg().document('flows');
  assert.ok(doc, 'the page can say what it is');
  return doc;
};
const cubeOf = (doc: PageDocument, id: string): CubeDocument => doc.cubes.find((c) => c.id === id)!.cube;

describe('a flow sheet on a page of its own', () => {
  it('opens no box: each is drawn from its document, and the page writes it as it was saved', async () => {
    opened();
    await shown(host, '.dc-flow-box[data-tile="grid-3"]');
    assert.deepEqual(pg().grids, ['grid-1'], "the board's grid alone is made");
    assert.deepEqual([...pg().boxes].sort(), ['grid-2', 'grid-3']);
    assert.deepEqual(asked, [], 'no box asked for');
    // drawn by what each does: an Input, and a box that browses its rows; the line between them
    assert.equal(box('grid-2').dataset['tool'], 'input');
    assert.equal(box('grid-3').dataset['tool'], 'browse');
    assert.equal(host.querySelectorAll('.dc-flow-lines path').length, 1, 'one line: grid-3 reads grid-2');
    // each box's cube as it was saved (an open grid writes its own as it opened it)
    const saved = readPage(JSON.parse(pageToJson(FLOW_PAGE())));
    for (const id of ['grid-2', 'grid-3']) assert.deepEqual(cubeOf(written(), id), cubeOf(saved, id), `${id} written as saved`);
    assert.deepEqual(written().sheets, saved.sheets);
    assert.deepEqual(pg().sheets, ['sheet-1', 'sheet-2']);
    assert.equal(pg().sheetLabel('sheet-2'), 'Flow 2');
  });

  it('shows a box: its grid made over its cube as the page has it now, the box shown before closed with what it did kept', async () => {
    opened();
    pg().showSheet('sheet-2');
    await shown(host, '.dc-flow-box[data-tile="grid-3"]');
    await show('grid-3');
    assert.deepEqual(asked.map((a) => a.box), ['grid-3']);
    assert.deepEqual(asked[0]!.source, rows('grid-2'), 'its own cube, its input live');
    assert.ok(asked[0]!.page.cubes.some((c) => c.id === 'grid-2'), 'over the page as it is, the box it reads in it');
    assert.deepEqual(pg().grids, ['grid-1', 'grid-3']);
    assert.ok(box('grid-3').classList.contains('dc-flow-shown'));
    assert.ok(host.querySelector('.dc-flow-results-body .dc-grid-tile'), 'its grid in the results pane');
    // what its grid does is the box's: grouped by desk, it is a Summarize
    assert.equal(await pg().grid('grid-3')!.edit([{ kind: 'group', rows: ['desk'] }], 'group'), null);
    await settle();
    assert.equal(box('grid-3').dataset['tool'], 'summarize');
    // another shown: this one closes, its cube as its grid left it, and the page writes it so
    await show('grid-2');
    assert.deepEqual(pg().grids, ['grid-1', 'grid-2']);
    assert.deepEqual(pg().boxes, ['grid-3']);
    assert.deepEqual(cubeOf(written(), 'grid-3').query.rows, ['desk']);
    assert.equal(box('grid-3').dataset['tool'], 'summarize', 'drawn from its document');
    assert.equal(pg().undoLabel, undefined, 'which box is shown is the screen\'s: no step of the page');
  });

  it('takes a box off: the boxes that read it read a copy of what it was (FL6), and an undo brings it back as a document', async () => {
    opened();
    pg().showSheet('sheet-2');
    await shown(host, '.dc-flow-box[data-tile="grid-2"]');
    key(box('grid-2'), 'Delete');
    await settle();
    assert.equal(box('grid-2'), null);
    const frozen = cubeOf(written(), 'grid-3').source;
    assert.deepEqual(liveGrids(frozen), [], 'no link to a box the page does not have');
    assert.equal(frozen._type === 'rows' && !('grid' in frozen.input) ? frozen.input.source._type : undefined, 'file');
    pg().undo();
    await settle();
    // brought back as a new tile (B12), where it was, a document still -- no grid made for it
    const back = written();
    const flow = back.sheets[1]!.layout;
    assert.deepEqual(flow.kind === 'flow' ? flow.boxes : undefined, [{ tile: 'grid-4', x: 40, y: 40 }, { tile: 'grid-3', x: 288, y: 40 }]);
    assert.deepEqual(cubeOf(back, 'grid-4'), cubeOf(FLOW_PAGE(), 'grid-2'));
    assert.ok(box('grid-4'));
    assert.deepEqual(pg().grids, ['grid-1']);
    // an undo is the page's structure (page-between.ts, history): the box after it reads the copy until Unfreeze (M2)
    assert.deepEqual(liveGrids(cubeOf(back, 'grid-3').source), []);
  });

  it('moves a box by its arrow keys, one step of the page each, and tells the host a tool picked', async () => {
    opened();
    pg().showSheet('sheet-2');
    await shown(host, '.dc-flow-box[data-tile="grid-3"]');
    key(box('grid-3'), 'ArrowDown', { shiftKey: true });
    const flow = written().sheets[1]!.layout;
    assert.deepEqual(flow.kind === 'flow' ? flow.boxes[1] : undefined, { tile: 'grid-3', x: 288, y: 72 });
    assert.equal(pg().undoLabel, 'Move Box');
    // a tool: after the box shown -- none yet -- told to the host, which makes the box
    const filter = host.querySelector<HTMLElement>('.dc-flow-tool[data-tool="filter"]')!;
    key(filter, 'Enter');
    assert.deepEqual(tools, [{ tool: 'filter', after: undefined, sheet: 'sheet-2' }]);
  });

  it('Create ▸ Flow adds an empty flow, shown: its canvas, not an empty sheet\'s card', async () => {
    page = new PageApp({ host, title: 'q', empty: (slot) => { slot.textContent = 'Add a data source'; } });
    const first = pg().addGrid(overCube(cube(FILE)));
    await pg().ready();
    const id = pg().addFlow();
    await shown(host, '.dc-flow-palette');
    assert.equal(pg().shownSheet, id);
    const flow = written().sheets.find((s) => s.id === id)!;
    assert.deepEqual(flow.layout, { kind: 'flow', boxes: [] });
    assert.ok(!host.querySelector<HTMLElement>('.dc-board-host')!.hidden, 'the canvas shows');
    assert.deepEqual(layoutTiles(written().sheets[0]!.layout), [first]);
  });
});
