// A PAGE EDIT AS A VALUE (src/page-commands.ts; docs/DATACUBE_COMMANDS_PAGE_2026_10_18.md): each command gives the
// page the screen's rules give it -- ids after every one held, a new chart titled by its number, a removed grid taking
// its following charts and leaving a frozen one detached, a chart's selection taken off its grid's filter, a move onto
// a tile's own sheet doing nothing -- and a command read from JSON is the same command, or refused in words.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { cubeOpenedAgain, type CubeDocument, type CubeSource, type FrameSource } from '../src/cube-document.ts';
import { tiles } from '../src/layout/bands.ts';
import {
  PAGE_COMMAND_KINDS, applyPageCommand, applyPageCommands, chartTitle, gridTitle, idsOf, nextPageId, readPageCommand,
  followedPage, rebuiltCommands,
  type PageCommand, type PageDraft,
} from '../src/page-commands.ts';
import { gridCopyOf, openedLive, readPage, type ChartView, type PageDocument } from '../src/page-document.ts';
import { CubeRefusal, type FilterNode } from '../src/snapshot.ts';
import { modelCommands, type PageModelRef } from '../src/page-model.ts';
import { rebaseEdits, withCube } from '../src/page-rebase.ts';
import { corpus } from './fixtures/pages/make.ts';

// the saved-page corpus's frame page: grids grid and grid-2, chart (following grid) stacked with chart-2 (frozen, of
// grid-2) on sheet-1 beside grid; grid-2 alone on sheet-2
const trades = (await corpus())['trades.page.json']!;
const draft = (page: PageDocument = trades): PageDraft => ({ page, held: idsOf(page) });
const apply = (command: PageCommand, d: PageDraft = draft()): PageDraft => applyPageCommand(d, command).draft;
const refused = (f: () => unknown, words: RegExp): void => {
  assert.throws(f, (e: unknown) => e instanceof CubeRefusal && words.test(e.message), String(words));
};
const sheet = (page: PageDocument, id: string) => page.sheets.find((s) => s.id === id)!;
const view = (page: PageDocument, id: string) => page.views.find((v) => v.id === id);
// a copy as a host's Snap writes it down (page-document.ts gridCopyOf)
const SNAP = gridCopyOf({ table: 'snap', rowCount: 3, takenAt: new Date('2026-10-20T14:02:00Z'), label: 'Snap 14:02' });

describe('ids and titles: one allocator, after every id the page has held', () => {
  it('a new id follows the largest of its kind held, and a removed one is not given out again', () => {
    assert.equal(nextPageId(idsOf(trades), 'chart'), 'chart-3');
    assert.equal(nextPageId(idsOf(trades), 'grid'), 'grid-3');
    assert.equal(nextPageId(idsOf(trades), 'sheet'), 'sheet-3');
    const gone = apply({ kind: 'removeTile', tile: 'chart-2' });
    assert.equal(nextPageId(gone.held, 'chart'), 'chart-3', 'chart-2 was held: never given out again');
    assert.equal(chartTitle('chart-12'), 'Chart 12');
  });

  it("a cube kept for a detached chart has no view, and its id is taken all the same (the phase 1 audit's 2)", () => {
    // grid-2 removed: chart-2, frozen, keeps its cube with no grid view
    const detached = apply({ kind: 'removeTile', tile: 'grid-2' }).page;
    assert.ok(idsOf(detached).includes('grid-2'), "the kept cube's id");
    const added = (d: PageDraft, id?: string): string => (applyPageCommand(d, {
      kind: 'addGrid', ...(id !== undefined ? { id } : {}), document: trades.cubes[0]!.cube, sheet: 'sheet-1', near: null, title: null, columns: null,
    }).command as { id: string }).id;
    assert.equal(added(draft(detached)), 'grid-3');
    // a host whose held left it out gets no second grid-2 either, nor may it name one
    const forgetful: PageDraft = { page: detached, held: detached.views.map((v) => v.id) };
    assert.equal(added(forgetful), 'grid-3');
    refused(() => added(forgetful, 'grid-2'), /'grid-2' is an id the page has held already/);
  });

  it('a command that makes a tile carries the id it was given, as applied (what a live channel replays)', () => {
    const { draft: d, applied } = applyPageCommands(draft(), [
      { kind: 'addSheet', name: 'More' },
      { kind: 'addChart', near: 'grid-2', spec: (view(trades, 'chart') as ChartView).spec, title: null, columns: null },
    ]);
    assert.deepEqual(applied.map((c) => ('id' in c ? c.id : undefined)), ['sheet-3', 'chart-3']);
    // replayed, they give the same page; an id the page has held is refused
    assert.deepEqual(applyPageCommands(draft(), applied).draft, d);
    refused(() => apply({ kind: 'addSheet', id: 'sheet-2', name: null }), /'sheet-2' is an id the page has held already/);
  });
});

describe('each command gives the page the screen\'s rules give it', () => {
  it('a new chart goes beside its grid, titled by its number, of its grid\'s cube', () => {
    const spec = (view(trades, 'chart') as ChartView).spec;
    const page = apply({ kind: 'addChart', near: 'grid-2', spec, title: null, columns: null }).page;
    assert.deepEqual(view(page, 'chart-3'), { id: 'chart-3', kind: 'chart', cube: 'grid-2', title: 'Chart 3', spec });
    assert.deepEqual(tiles(sheet(page, 'sheet-2').layout), ['grid-2', 'chart-3']);
    refused(() => apply({ kind: 'addChart', near: 'chart', spec, title: null, columns: null }), /a chart is of a grid/);
  });

  it('a removed grid takes its following charts; a frozen one stays, detached, its cube kept for it', () => {
    const page = apply({ kind: 'removeTile', tile: 'grid' }).page;
    assert.deepEqual(page.views.map((v) => v.id), ['grid-2', 'chart-2'], 'chart followed grid: gone with it');
    assert.deepEqual(page.cubes.map((c) => c.id), ['grid-2']);
    assert.deepEqual(tiles(sheet(page, 'sheet-1').layout), ['chart-2']);
    const detached = apply({ kind: 'removeTile', tile: 'grid-2' }).page;
    assert.deepEqual(detached.views.map((v) => v.id), ['grid', 'chart', 'chart-2'], 'chart-2 frozen: it stays');
    assert.deepEqual(detached.cubes.map((c) => c.id), ['grid', 'grid-2'], 'its cube kept for the detached chart');
  });

  it('a chart removed takes its selection off its grid\'s filter (the screen\'s cleanup Python missed)', () => {
    const pick: FilterNode = { kind: 'condition', column: 'region', operator: 'equal', value: 'EMEA' };
    const cube = trades.cubes.find((c) => c.id === 'grid')!;
    const had = cube.cube.query.filter!;
    const selected: PageDocument = {
      ...trades,
      cubes: trades.cubes.map((c) => (c.id === 'grid' ? { ...c, cube: { ...c.cube, query: { ...c.cube.query, filter: { kind: 'and', children: [had, pick] } } } } : c)),
      views: trades.views.map((v) => (v.id === 'chart' ? { ...v, selection: [pick] } : v)),
    };
    const page = apply({ kind: 'removeTile', tile: 'chart' }, draft(selected)).page;
    assert.deepEqual(page.cubes.find((c) => c.id === 'grid')?.cube.query.filter, had);
  });

  it('a tile moved onto another sheet goes to its bottom; onto its own, nothing', () => {
    assert.deepEqual(apply({ kind: 'moveTile', tile: 'grid', sheet: 'sheet-1', columns: null }).page, trades);
    const page = apply({ kind: 'moveTile', tile: 'chart', sheet: 'sheet-2', columns: null }).page;
    assert.deepEqual(tiles(sheet(page, 'sheet-2').layout), ['grid-2', 'chart']);
    assert.deepEqual(tiles(sheet(page, 'sheet-1').layout), ['grid', 'chart-2']);
  });

  it('sheets: added, named, moved, removed with their tiles; the last one stays', () => {
    let d = apply({ kind: 'addSheet', name: '  Extra  ' });
    assert.deepEqual(sheet(d.page, 'sheet-3'), { id: 'sheet-3', name: 'Extra', layout: { kind: 'bands', fit: true, bands: [] } });
    d = apply({ kind: 'renameSheet', sheet: 'sheet-1', name: '' }, d);
    assert.equal('name' in sheet(d.page, 'sheet-1'), false, "a blank: named after its first grid");
    d = apply({ kind: 'moveSheet', sheet: 'sheet-3', to: 0 }, d);
    assert.deepEqual(d.page.sheets.map((s) => s.id), ['sheet-3', 'sheet-1', 'sheet-2']);
    const gone = apply({ kind: 'removeSheet', sheet: 'sheet-1' }, d).page;
    assert.deepEqual(gone.sheets.map((s) => s.id), ['sheet-3', 'sheet-2']);
    // grid went with sheet-1, and its following chart; chart-2, frozen, of grid-2, went with sheet-1 too
    assert.deepEqual(gone.views.map((v) => v.id), ['grid-2']);
    let one = draft();
    one = apply({ kind: 'removeSheet', sheet: 'sheet-2' }, one);
    refused(() => apply({ kind: 'removeSheet', sheet: 'sheet-1' }, one), /a page has one sheet at least/);
  });

  it('titles by the one rule (givenName): a chart keeps one, a grid\'s comes off, a blank is refused', () => {
    assert.equal(view(apply({ kind: 'renameTile', tile: 'chart', title: ' Qty ' }).page, 'chart')?.title, 'Qty');
    assert.equal('title' in view(apply({ kind: 'renameTile', tile: 'grid', title: null }).page, 'grid')!, false);
    refused(() => apply({ kind: 'renameTile', tile: 'chart', title: null }), /a chart has a title/);
    refused(() => apply({ kind: 'renameTile', tile: 'grid', title: '  ' }), /a tile's name is not blank/);
    // a grid given back the title it was made with keeps none of its own (B2): the screen sends what was typed
    const titled = apply({ kind: 'renameTile', tile: 'grid', title: 'Mine' }).page;
    assert.equal(view(titled, 'grid')?.title, 'Mine');
    const back = apply({ kind: 'renameTile', tile: 'grid', title: ` ${gridTitle('grid')} ` }, { page: titled, held: idsOf(titled) });
    assert.equal('title' in view(back.page, 'grid')!, false);
    // a chart's selection: set, and cleared by none
    const emea = { kind: 'condition', column: 'region', operator: 'equal', value: 'EMEA' } as const;
    const selected = apply({ kind: 'select', chart: 'chart', conditions: [emea] }).page;
    assert.deepEqual((view(selected, 'chart') as ChartView).selection, [emea]);
    const none = apply({ kind: 'select', chart: 'chart', conditions: [] }, { page: selected, held: idsOf(selected) });
    assert.equal('selection' in view(none.page, 'chart')!, false);
    refused(() => apply({ kind: 'select', chart: 'grid', conditions: [] }), /'grid' is a grid, not a chart/);
    // a chart's own query (P8): only a detached chart's; the cube it read before let go when nothing reads it
    refused(() => apply({ kind: 'setChartCube', chart: 'chart', document: trades.cubes[0]!.cube }), /reads its grid/);
  });

  it("the page's name is each cube's too (writePageOf's rule), written by no rule of its own", () => {
    const page = apply({ kind: 'renamePage', name: 'Desk view' }).page;
    assert.equal(page.name, 'Desk view');
    assert.deepEqual(page.cubes.map((c) => c.cube.name), ['Desk view', 'Desk view']);
  });

  it('stack, arrange and a whole layout, by bands.ts, each refused in its words', () => {
    const stacked = apply({ kind: 'stack', tiles: ['grid', 'chart'] }).page;
    assert.deepEqual(tiles(sheet(stacked, 'sheet-1').layout), ['grid', 'chart', 'chart-2']);
    refused(() => apply({ kind: 'stack', tiles: ['grid', 'grid-2'] }), /on one sheet/);
    const side = apply({ kind: 'arrange', sheet: 'sheet-1', preset: 'stacked', order: null }).page;
    assert.equal(sheet(side, 'sheet-1').layout.bands.length, 2);
    refused(() => apply({ kind: 'layout', sheet: 'sheet-2', layout: sheet(trades, 'sheet-1').layout }), /holds its tiles, each once/);
    // (the phase 1 audit's 1) read as a saved page's sheet is: its shape refused in the page reader's words, never kept
    const odd = (layout: unknown): PageCommand => ({ kind: 'layout', sheet: 'sheet-2', layout: layout as PageCommand & never });
    refused(() => apply(odd({ kind: 'bands', fit: 'yes', bands: [{ height: 1, node: { tile: 'grid-2' } }] })), /the layout's fit is not true or false/);
    refused(() => apply(odd({ kind: 'bands', fit: true })), /the layout's bands are not a list/);
    refused(() => apply(odd({ kind: 'bands', fit: true, bands: [{ height: '1', node: { tile: 'grid-2' } }] })), /band 1 is not a height and its tiles/);
    refused(() => apply(odd({ kind: 'bands', fit: true, bands: [{ height: 1, node: { tiles: 'grid-2' } }] })), /band 1 is not a tile, a stack or a split/);
    refused(() => apply(odd({ kind: 'bands', fit: true, bands: [{ height: 1, node: { tile: 'nope' } }] })), /a tile shows no view of this page \(nope\)/);
    // a node's stray keys are not kept: the page's reader rebuilds it
    const kept = apply(odd({ kind: 'bands', fit: true, bands: [{ height: 1, node: { tile: 'grid-2', extra: 1 } }] })).page;
    assert.deepEqual(sheet(kept, 'sheet-2').layout, sheet(trades, 'sheet-2').layout);
    const same = apply({ kind: 'layout', sheet: 'sheet-1', layout: sheet(side, 'sheet-1').layout }, draft()).page;
    assert.deepEqual(sheet(same, 'sheet-1').layout, sheet(side, 'sheet-1').layout);
  });
});

describe('snapped or live is page state: a snap and a go-live are page commands (design §18.8)', () => {
  const cube = (id: string) => view(trades, id)!.cube;

  it('a snap says the copy its grid reads, by its cube; another snap replaces it; live takes it off', () => {
    const snapped = apply({ kind: 'snap', grid: 'grid', copy: SNAP });
    assert.deepEqual(snapped.page.copies, { [cube('grid')]: SNAP });
    const again = { ...SNAP, table: 'snap_2' };
    assert.deepEqual(apply({ kind: 'snap', grid: 'grid', copy: again }, snapped).page.copies, { [cube('grid')]: again },
      'one copy a grid: the one it read before is its host\'s to let go');
    const both = apply({ kind: 'snap', grid: 'grid-2', copy: again }, snapped);
    const live = apply({ kind: 'live', grid: 'grid' }, both);
    assert.deepEqual(live.page.copies, { [cube('grid-2')]: again });
    assert.equal(apply({ kind: 'live', grid: 'grid-2' }, live).page.copies, undefined, 'none, no field');
    // the page's views, sheets and cubes are as they were: where its rows are is no step of what it shows
    assert.deepEqual({ ...snapped.page, copies: undefined }, { ...trades, copies: undefined });
  });

  it('a chart snaps with its grid; a grid live already, or none, is refused in words', () => {
    refused(() => apply({ kind: 'snap', grid: 'chart', copy: SNAP }), /'chart' is a chart: its grid snaps/);
    refused(() => apply({ kind: 'live', grid: 'grid' }), /'grid' reads its live rows already/);
    refused(() => apply({ kind: 'snap', grid: 'grid-9', copy: SNAP }), /grid-9/);
  });

  it('a grid removed takes its copy off the page; a frozen chart kept for its cube keeps it', () => {
    const snapped = apply({ kind: 'snap', grid: 'grid-2', copy: SNAP });
    // grid-2's chart is frozen: its cube stays for it, and the copy it reads with it
    assert.deepEqual(apply({ kind: 'removeTile', tile: 'grid-2' }, snapped).page.copies, { [cube('grid-2')]: SNAP });
    const onGrid = apply({ kind: 'snap', grid: 'grid', copy: SNAP });
    assert.equal(apply({ kind: 'removeTile', tile: 'grid' }, onGrid).page.copies, undefined);
  });

  it('the page writes its copies and reads them strictly; a saved page opens live, each snapped grid said', () => {
    const page = apply({ kind: 'snap', grid: 'grid', copy: { ...SNAP, pulledBy: { plane: 'warehouse', where: 'acme', as: 'ana', serverRows: 3 } } }).page;
    assert.deepEqual(readPage(JSON.parse(JSON.stringify(page))), page);
    const raw = JSON.parse(JSON.stringify(page)) as Record<string, unknown>;
    const bad = (copies: unknown, words: RegExp) => assert.throws(() => readPage({ ...raw, copies }), words);
    bad({ nope: SNAP }, /holds one for nope, which is no cube of the page/);
    bad({}, /'copies' is empty/);
    bad({ [cube('grid')]: { ...SNAP, rows: 3 } }, /a copy has no rows/);
    bad({ [cube('grid')]: { ...SNAP, takenAt: 'yesterday' } }, /says when it was taken, as an instant/);
    bad({ [cube('grid')]: { ...SNAP, pulledBy: { plane: 'moon', where: '' } } }, /ran in the tab, a warehouse or an engine/);
    const opened = openedLive(page);
    assert.equal(opened.page.copies, undefined);
    assert.deepEqual(opened.notes, [`${trades.cubes.find((c) => c.id === cube('grid'))!.cube.source.name} was snapped `
      + '(Snap 14:02) when the page was saved: it opens live, as a snap lasts only as long as the session that made it']);
    assert.deepEqual(openedLive(trades), { page: trades, notes: [] });
  });
});

describe('a page command read from JSON is the same command, and what DataCube does not know is refused', () => {
  const spec = (view(trades, 'chart') as ChartView).spec;
  const every: PageCommand[] = [
    { kind: 'renamePage', name: 'P' },
    { kind: 'addSheet', name: null },
    { kind: 'renameSheet', sheet: 'sheet-1', name: 'S' },
    { kind: 'moveSheet', sheet: 'sheet-1', to: 1 },
    { kind: 'removeSheet', sheet: 'sheet-2' },
    { kind: 'renameTile', tile: 'grid', title: null },
    { kind: 'addGrid', id: 'grid-3', document: trades.cubes[0]!.cube, sheet: 'sheet-1', near: 'grid', title: null, columns: 2 },
    { kind: 'addChart', near: 'grid', spec, title: 'T', columns: null },
    { kind: 'setChart', chart: 'chart', spec },
    { kind: 'setChartCube', chart: 'chart', document: trades.cubes[0]!.cube },
    { kind: 'select', chart: 'chart', conditions: [{ kind: 'condition', column: 'region', operator: 'equal', value: 'EMEA' }] },
    { kind: 'moveTile', tile: 'chart', sheet: 'sheet-2', columns: null },
    { kind: 'stack', tiles: ['grid', 'chart'] },
    { kind: 'removeTile', tile: 'chart' },
    { kind: 'arrange', sheet: 'sheet-1', preset: 'rows:1-1', order: ['grid', 'chart'] },
    { kind: 'layout', sheet: 'sheet-2', layout: sheet(trades, 'sheet-2').layout },
    // a page filter's two (applied over a page of class grids: test/page-filters.test.ts)
    { kind: 'setPageFilter', id: 'filter-1', class: 'trades::domain::Trade', condition: { kind: 'condition', column: 'region', operator: 'equal', value: 'EMEA' }, from: null },
    { kind: 'removePageFilter', filter: 'filter-1' },
    { kind: 'snap', grid: 'grid', copy: SNAP },
    { kind: 'live', grid: 'grid' },
    { kind: 'modelInto', project: 'datacube:q3', workspace: 'datacube', package: 'q3' },
    { kind: 'modelPin', revision: 'r2' },
    { kind: 'modelKeep', cube: 'grid', tables: ['t_trades'], class: null },
    { kind: 'rebuilt', cube: 'grid', source: trades.cubes[0]!.cube.source },
  ];

  it('every kind, through JSON and back', () => {
    assert.deepEqual([...new Set(every.map((c) => c.kind))].sort(), [...PAGE_COMMAND_KINDS].sort(), 'every kind is here');
    for (const c of every) assert.deepEqual(readPageCommand(JSON.parse(JSON.stringify(c))), c, c.kind);
  });

  it('an unknown kind, a misspelt field and a field of the wrong type, each in words', () => {
    refused(() => readPageCommand({ kind: 'addTab', name: null }), /'addTab' is not a page's command/);
    refused(() => readPageCommand({ kind: 'removeTile', tiles: 'x' }), /a 'removeTile' command takes tile, not tiles/);
    refused(() => readPageCommand({ kind: 'moveTile', tile: 'x', sheet: 'sheet-1', columns: 0 }), /columns is a whole number/);
    refused(() => readPageCommand({ kind: 'renameTile', tile: 'x', title: 3 }), /a tile's title is text/);
    // a copy, by the page's own reader
    refused(() => readPageCommand({ kind: 'snap', grid: 'grid', copy: { ...SNAP, rowCount: -1 } }), /a copy says its rows/);
    // a grid's cube, read by its own reader (the phase 1 audit's 3)
    refused(() => readPageCommand({ kind: 'addGrid', document: { bogus: 1 }, sheet: 'sheet-1', near: null, title: null, columns: null }),
      /cannot read saved cube/);
    // a grid whose source the page cannot write down is the screen's own: never one Python sends (the phase 2 audit's 9)
    refused(() => readPageCommand({ kind: 'addGrid', document: null, sheet: 'sheet-1', near: null, title: null, columns: null }),
      /a grid's cube is its document/);
  });
});

describe("the page's model, by its commands (page-model.ts: P4)", () => {
  // the corpus page with no model of its own yet
  const start: PageDocument = (({ model: _none, ...rest }) => rest)(trades);
  const draftOf = (p: PageDocument): PageDraft => ({ page: p, held: idsOf(p) });

  it('a model action told as the commands of what it changed: applied, they make the model it made', () => {
    const cube = start.cubes[0]!.id;
    const made: PageModelRef = { project: 'datacube:q3', workspace: 'datacube', package: 'q3', tables: { [cube]: ['t_trades'] },
      classes: { [cube]: 'q3::domain::Trade' }, revision: 'r1' };
    const commands = modelCommands(undefined, made);
    assert.deepEqual(commands.map((c) => c.kind), ['modelInto', 'modelKeep', 'modelPin']);
    const after = applyPageCommands(draftOf(start), commands).draft.page;
    assert.deepEqual(after.model, made);
    // the next action: what it changed alone
    const next: PageModelRef = { ...made, revision: 'r2' };
    assert.deepEqual(modelCommands(made, next), [{ kind: 'modelPin', revision: 'r2' }]);
    assert.deepEqual(modelCommands(made, made), []);
  });

  it('refused in words: a second model, a pin or a keep with none, a cube the page has not', () => {
    const with1 = applyPageCommand(draftOf(start), { kind: 'modelInto', project: 'p', workspace: 'w', package: 'k' }).draft;
    refused(() => applyPageCommand(with1, { kind: 'modelInto', project: 'q', workspace: 'w', package: 'k' }), /a page has one model/);
    refused(() => applyPageCommand(draftOf(start), { kind: 'modelPin', revision: 'r' }), /the page has no model yet/);
    refused(() => applyPageCommand(with1, { kind: 'modelKeep', cube: 'nope', tables: [], class: null }), /'nope' is no grid's cube/);
  });
});

describe("a page's charts following their cubes, by one rule wherever a cube changes (followedPage: S2)", () => {
  const draftOf = (p: PageDocument): PageDraft => ({ page: p, held: idsOf(p) });
  const chartOf = (p: PageDocument, id: string): ChartView => p.views.find((v) => v.id === id) as ChartView;
  const cube = trades.cubes[0]!;

  it('a live chart told a spec of another grouping is kept regrouped as its grid is, as the panel keeps it (P6)', () => {
    const live = chartOf(trades, 'chart');
    // told across desk (the panel tells before it follows): kept across region, the grid's first row group
    const told = { ...live.spec, x: 'desk' };
    const after = applyPageCommand(draftOf(trades), { kind: 'setChart', chart: 'chart', spec: told }).draft.page;
    assert.equal(chartOf(after, 'chart').spec.x, 'region');
    // a frozen chart keeps what it was told
    const frozen = applyPageCommand(draftOf(trades), { kind: 'setChart', chart: 'chart-2', spec: { ...chartOf(trades, 'chart-2').spec, x: 'desk' } }).draft.page;
    assert.equal(chartOf(frozen, 'chart-2').spec.x, 'desk');
  });

  it("a grid's edit regroups its live charts and takes off a selection its filter no longer holds (V1, V2)", () => {
    const mark: FilterNode = { kind: 'condition', column: 'region', operator: 'equal', value: 'EMEA' };
    const selected: PageDocument = {
      ...trades,
      views: trades.views.map((v) => (v.id === 'chart' ? { ...v, selection: [mark] } : v)),
      cubes: trades.cubes.map((c) => (c.id === cube.id ? { ...c, cube: { ...c.cube, query: { ...c.cube.query, filter: mark } } } : c)),
    };
    assert.deepEqual(followedPage(selected, cube.id), selected, 'nothing moved: the same page');
    // regrouped by desk alone and the filter cleared, as a grid's edit leaves its cube
    const edited = withCube(selected, cube.id, { ...cube.cube, query: { ...cube.cube.query, rows: ['desk'] } });
    const after = followedPage(edited, cube.id);
    assert.equal(chartOf(after, 'chart').spec.x, 'desk', 'the live chart regrouped');
    assert.equal(chartOf(after, 'chart').selection, undefined, 'its selection gone with the filter that held it');
    assert.equal(chartOf(after, 'chart-2').spec.x, 'region', 'the frozen one as it was');
  });
});

describe("a grid's cube opened again over its source as it is now, by its command (rebuilt: P11)", () => {
  const draftOf = (p: PageDocument): PageDraft => ({ page: p, held: idsOf(p) });
  const cube = trades.cubes[0]!;
  const view = trades.views.find((v) => v.kind === 'grid' && v.cube === cube.id)!;
  // its frame a column more, and one gone, as a host opens it again over the frame as it is now
  const frame = cube.cube.source as FrameSource;
  const now: FrameSource = { ...frame, columns: [...frame.columns.filter((c) => c.name !== 'qty'), { name: 'book', type: 'String' }] };
  const held = (p: PageDocument): CubeDocument => p.cubes.find((c) => c.id === cube.id)!.cube;

  it('the cube held opened over the source as it is now: a new column hidden, a gone one left out, nothing else moved', () => {
    const after = applyPageCommand(draftOf(trades), { kind: 'rebuilt', cube: cube.id, source: now }).draft.page;
    assert.deepEqual(held(after).source, now);
    assert.deepEqual(held(after).query.columns.map((c) => c.name), now.columns.map((c) => c.name));
    assert.equal((held(after).configuration as { columns?: Record<string, { hidden?: boolean }> }).columns?.['book']?.hidden, true);
    assert.deepEqual({ ...after, cubes: trades.cubes }, trades, 'the rest of the page as it was');
    // the rule is cube-document.ts cubeOpenedAgain's, whoever applies it
    assert.deepEqual(held(after), cubeOpenedAgain(cube.cube, now).document);
  });

  it("made again on top of another writer's edit of the grid, that edit kept (the S1 audit's 1)", async () => {
    // the other writer regrouped the grid meanwhile; the host's rebuilt, made against the page before, made again on it
    const regrouped = { ...trades, cubes: trades.cubes.map((c) => (c.id === cube.id ? { ...c, cube: { ...c.cube, query: { ...c.cube.query, rows: ['desk'] } } } : c)) };
    const done = await rebaseEdits(draftOf(regrouped), [{ page: { kind: 'rebuilt', cube: cube.id, source: now } }],
      async () => { throw new Error('no grid edit here'); });
    assert.deepEqual(held(done.page).query.rows, ['desk'], "the other writer's grouping kept");
    assert.deepEqual(held(done.page).source, now, 'and the cube opened over the frame as it is now');
  });

  it('a page opened again tells each cube it opened as another, but a snapped one (the S1 audit\'s 6)', () => {
    const opened = { ...trades, cubes: trades.cubes.map((c) => (c.id === cube.id ? { ...c, cube: { ...c.cube, source: now } } : c)) };
    assert.deepEqual(rebuiltCommands(trades, opened, [cube.id]), [{ kind: 'rebuilt', cube: cube.id, source: now }]);
    assert.deepEqual(rebuiltCommands(trades, opened, []), [], 'none opened as another: nothing told');
    // snapped, it reads its copy: no frame opens it again
    const snapped = applyPageCommand(draftOf(trades), { kind: 'snap', grid: view.id, copy: SNAP }).draft.page;
    assert.deepEqual(rebuiltCommands(snapped, opened, [cube.id]), []);
  });

  it('refused in words: no such cube, a snapped grid (its copy does not move), another source, a source no host opens again', () => {
    refused(() => applyPageCommand(draftOf(trades), { kind: 'rebuilt', cube: 'nope', source: now }), /'nope' is no grid's cube/);
    const snapped = applyPageCommand(draftOf(trades), { kind: 'snap', grid: view.id, copy: SNAP }).draft;
    refused(() => applyPageCommand(snapped, { kind: 'rebuilt', cube: cube.id, source: now }), /reads its snap/);
    refused(() => applyPageCommand(draftOf(trades), { kind: 'rebuilt', cube: cube.id, source: { ...now, name: 'elsewhere' } }),
      /a cube rebuilt reads the same source/);
    assert.throws(() => cubeOpenedAgain(cube.cube, { _type: 'savedQuery', name: 'q' } as unknown as CubeSource),
      /its host opens again a frame's cube or a join's of frames/);
  });
});
