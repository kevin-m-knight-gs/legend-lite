// THE ONE API LEDGERS, BEHAVIOURAL HALF (docs/DATACUBE_ONE_API_PLAN_2026_10_21.md §3: L2, L3). The real page -- PageApp,
// its grids CubeApps, its charts -- wired to the tab's live channel (src/app/live-channel.ts) as DataCube's app wires a
// page another editor serves; each gesture made as a person makes it, on a page that is quiet first, and what the
// channel then writes held to the tenet:
//
//   told    the change was told as commands: the channel did not have to write the page whole (L2)
//   wire    the host reads them: headless pageWritten, what Python's keeper reads a write with, takes them as sent (L3)
//   replays the commands as the host read them, applied to the page before by the shared functions (rebaseEdits:
//           applyPageCommand, and a grid's applyGridCommands), make the page the screen wrote (L2)
//
// Each gesture's failing checks are listed by the plan's items, with what fails (the host's words, the parts of the page
// that differ), and only those: a gesture failing a check it is not listed for, or failing it in another way, is a new
// gap; one listed that now passes is a gap closed -- close its item, take it off. And every command kind is told by
// some gesture here, or listed as told by none: a new kind is driven here before it ships. The static half is
// one-api-ledger.test.ts; Python's is python/tests/test_one_api.py.

import assert from 'node:assert/strict';
import { after, afterEach, beforeEach, describe, it } from 'node:test';
import { JSDOM } from 'jsdom';
import { getInstanceByDom } from 'echarts/core';

import { CubeApp } from '../src/app.ts';
import { applyToSnapshot, DEFAULT_CONFIGURATION } from '../src/config.ts';
import { fold } from '../src/cube-state.ts';
import { configurationOf, treeOf, writeCube, type FileSource } from '../src/cube-document.ts';
import { applyGridCommands, GRID_COMMAND_KINDS, readGridCommand } from '../src/grid-commands.ts';
import { LiveChannel } from '../src/app/live-channel.ts';
import type { LiveCommand, LivePage } from '../src/app/services.ts';
import { idsOf, PAGE_COMMAND_KINDS, readPageCommand } from '../src/page-commands.ts';
import { pageToJson, readPage, type PageDocument } from '../src/page-document.ts';
import { rebaseEdits, type GridApplier, type PageEdit } from '../src/page-rebase.ts';
import { PageApp, type GridMaker, type PageAppOptions } from '../src/page/page-app.ts';
import { pageWritten } from '../src/headless/page.ts';
import type { CubeSnapshot } from '../src/snapshot.ts';
import { toJson } from '../../pure-protocol/src/index.ts';
import { GateEngine, SNAPSHOT, StubPlanner, settle } from './cube-fixture.ts';

// -- the page, as DataCube's app wires one another editor serves ----------------------------------------------------

let dom: JSDOM;
let host: HTMLElement;
let engine: GateEngine;
let page: PageApp | undefined;
let channel: LiveChannel | undefined;
const cubes = new Map<string, CubeApp>();

/** What the channel wrote, each write as the host was sent it. */
interface Write { readonly at: number; readonly page: PageDocument; readonly edits: readonly LiveCommand[] | null }
const writes: Write[] = [];
/** Every command kind some gesture told, across the run. */
const told = new Set<string>();

/** The page's other editor (Python's host): each write recorded and taken. */
const editor: LivePage = {
  read: () => { throw new Error('not opened here'); },
  changes: () => { throw new Error('not followed here'); },
  report: async (at, written, edits) => {
    writes.push({ at, page: written, edits });
    return { version: at + 1, combined: false };
  },
};

const pg = (): PageApp => {
  assert.ok(page, 'the test made a page');
  return page;
};

beforeEach(() => {
  dom = new JSDOM('<!doctype html><div id="p"></div>');
  const g = globalThis as unknown as Record<string, unknown>;
  g['window'] = dom.window;
  g['document'] = dom.window.document;
  g['requestAnimationFrame'] = (cb: FrameRequestCallback) => { cb(0); return 1; };
  g['cancelAnimationFrame'] = () => {};
  host = dom.window.document.getElementById('p') as unknown as HTMLElement;
  const inert: ProxyHandler<object> = {
    get: (_t, key) => (key === 'canvas' ? dom.window.document.createElement('canvas') : key === 'measureText' ? () => ({ width: 0 }) : () => undefined),
    set: () => true,
  };
  (dom.window.HTMLCanvasElement.prototype as unknown as { getContext: () => unknown }).getContext = () => new Proxy({}, inert);
  engine = new GateEngine();
  writes.length = 0;
  cubes.clear();
  channel = undefined;
});

afterEach(async () => {
  channel?.stop();
  await settle();
  page?.dispose();
  page = undefined;
});

const file = (name: string): FileSource => ({
  _type: 'file', name, format: 'csv', size: 10, sha256: name.padEnd(64, '0').slice(0, 64),
  columns: SNAPSHOT.columns.map((c) => ({ name: c.name, type: c.type })),
});

/** A grid over `name`, as DataCube's app makes one on a page another editor serves: its commands told to the channel. */
const over = (name: string): GridMaker => (gridHost, spawned, start) => {
  const made = new CubeApp(gridHost, start?.snapshot ?? SNAPSHOT, {
    engine, planner: new StubPlanner(), compact: true, cubeSource: file(name), sourceLabel: name,
    configuration: start?.configuration ?? { ...DEFAULT_CONFIGURATION, reportTitle: name },
    ...(page ? { windowHost: page.root } : {}), ...spawned,
    onCommand: (command) => { if (spawned.id !== undefined) channel?.edit({ grid: spawned.id, command }); },
  });
  if (spawned.id !== undefined) cubes.set(spawned.id, made);
  return made;
};

/** A grid over the class Trade (a page filter is on a class a grid reads): its source a class, its rows the fixture's. */
const TRADE = 'trades::domain::Trade';
const overClass = (): GridMaker => (gridHost, spawned, start) => {
  const made = new CubeApp(gridHost, start?.snapshot ?? SNAPSHOT, {
    engine, planner: new StubPlanner(), compact: true, sourceLabel: 'Trade',
    cubeSource: {
      _type: 'class', name: 'Trade', class: TRADE, project: 'datacube:trades', workspace: 'datacube', revision: '3',
      mapping: 'trades::mapping::Model', runtime: 'trades::runtime::Browser',
      columns: SNAPSHOT.columns.map((c) => ({ name: c.name, type: c.type })),
    },
    configuration: start?.configuration ?? { ...DEFAULT_CONFIGURATION, reportTitle: 'Trade' },
    ...(page ? { windowHost: page.root } : {}), ...spawned,
    onCommand: (command) => { if (spawned.id !== undefined) channel?.edit({ grid: spawned.id, command }); },
  });
  if (spawned.id !== undefined) cubes.set(spawned.id, made);
  return made;
};

/** The page as the tab writes it (datacube-app.ts pageNow: its name as it is now, a page of no grid too). */
const onScreen = (): PageDocument => pg().document(pg().title || 'Q3', undefined, true)!;

/**
 * The page, wired as datacube-app.ts wires a page another editor serves. One part is the host's own and stood in for
 * here: renaming (its renamePage keeps the name, sets the title and says the page changed). P1's check on the host's
 * own code is the static half's (L1: renamePage tells no command); this gesture holds what the channel does with it.
 */
function newPage(extra: Partial<PageAppOptions> = {}): PageApp {
  page = new PageApp({
    host, title: 'Q3', confirm: () => true, empty: (slot) => { slot.textContent = 'empty'; },
    onChange: () => channel?.changed(),
    onPageCommand: (command) => channel?.edit({ page: command }),
    onRename: (name) => {
      pg().setTitle(name);
      channel?.changed();
    },
    ...extra,
  });
  return page;
}

/** A page of two grids, `a` over trades, `b` over orders beside it. */
async function twoGrids(): Promise<{ a: string; b: string }> {
  newPage();
  const a = pg().addGrid(over('trades.csv'));
  const b = pg().addGrid(over('orders.csv'), { near: a });
  await settle();
  return { a, b };
}

// -- the screen, as a person works it (cube-page-shell.test.ts's gestures) --------------------------------------------

const tile = (id: string): HTMLElement => host.querySelector(`[data-tile="${id}"]`) as HTMLElement;
const label = (item: Element): string => item.querySelector(':scope > .dc-menu-label')?.textContent ?? '';
const menuItems = (): HTMLElement[] => [...dom.window.document.querySelectorAll<HTMLElement>('.dc-menu [role^="menuitem"]')];
const escape = (): void => { dom.window.document.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Escape' })); };
const contextMenu = (el: Element): void => { el.dispatchEvent(new dom.window.MouseEvent('contextmenu', { bubbles: true, cancelable: true })); };

async function drawn(): Promise<void> {
  await new Promise((r) => setTimeout(r, 200));
  await settle();
}

/** An entry of the menu open now, by what it says. */
function entry(text: string): HTMLElement {
  const item = menuItems().find((i) => label(i) === text && i.getAttribute('aria-disabled') !== 'true');
  assert.ok(item, `the menu offers ${text} (it offers ${menuItems().filter((i) => i.getAttribute('aria-disabled') !== 'true').map(label).join(' | ')})`);
  return item;
}

/** A submenu's entry: `text` under `parent` in the menu open now (the first under it, given none). */
function nested(parent: string, text?: string): HTMLElement {
  const item = [...dom.window.document.querySelectorAll<HTMLElement>('.dc-menu .dc-menu-item')]
    .find((el) => {
      const under = el.parentElement?.closest('.dc-menu-item');
      return (text === undefined || label(el) === text) && under !== null && under !== undefined && label(under) === parent;
    });
  assert.ok(item, `${parent} > ${text ?? 'its first entry'}`);
  return item;
}

function gridMenuItem(gridTile: string, text: string): HTMLElement {
  escape();
  (tile(gridTile).querySelector('.dc-tile-menu') as HTMLElement).click();
  return entry(text);
}

function chartMenu(chart: string): void {
  escape();
  contextMenu(tile(chart).querySelector('.dc-chart-tile') as HTMLElement);
}

function tabOf(text: string): HTMLElement {
  return [...host.querySelectorAll<HTMLElement>('.dc-sheet-tab')].find((t) => (t.querySelector('.dc-sheet-label')?.textContent ?? '') === text)!;
}

function tabMenu(text: string, item: string): void {
  escape();
  contextMenu(tabOf(text));
  entry(item).click();
}

async function chartOf(gridTile: string): Promise<string> {
  const charts = (): string[] => [...host.querySelectorAll<HTMLElement>('[data-tile^="chart-"]')].map((t) => t.dataset['tile']!);
  const before = new Set(charts());
  contextMenu(tile(gridTile).querySelector('.dc-row .dc-cell') as HTMLElement);
  await settle();
  nested('Insert', 'Visualization').click();
  await settle();
  const chart = charts().find((id) => !before.has(id));
  assert.ok(chart, 'a chart on the page');
  return chart;
}

async function togglePill(chart: string): Promise<void> {
  (tile(chart).querySelector('.dc-tile-actions .dc-titlebar-toggle') as HTMLElement).click();
  await settle();
}

async function removeTile(id: string): Promise<void> {
  tile(id).querySelector<HTMLButtonElement>('.dc-tile-remove')!.click();
  await settle();
}

async function ungroup(id: string, column: string): Promise<void> {
  (tile(id).querySelector(`.dc-zone-rows .dc-chip[data-column="${column}"] .dc-chip-remove`) as HTMLElement).click();
  await settle();
}

function rename(id: string, name: string): void {
  tile(id).querySelector('.dc-tile-head')!.dispatchEvent(new dom.window.MouseEvent('dblclick', { bubbles: true }));
  const field = tile(id).querySelector<HTMLInputElement>('.dc-tile-rename');
  assert.ok(field, `${id}'s rename field`);
  field.value = name;
  field.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Enter' }));
}

async function openInGrid(chart: string): Promise<void> {
  chartMenu(chart);
  entry('Open in grid').click();
  await settle();
}

async function update(chart: string): Promise<void> {
  const button = [...tile(`edit-${chart}`).querySelectorAll<HTMLButtonElement>('.dc-tile-actions > button')]
    .find((x) => x.textContent?.startsWith('Update'));
  assert.ok(button, `the editing grid of ${chart} has its Update`);
  button.click();
  await settle();
}

function clickMark(chart: string, series: number, index: number): void {
  const drawnChart = getInstanceByDom(tile(chart).querySelector('.dc-chartpanel-chart') as HTMLElement);
  assert.ok(drawnChart, 'the chart is drawn');
  (drawnChart as unknown as { trigger(name: string, params: unknown): void }).trigger('click', { seriesIndex: series, dataIndex: index });
}

/** A header's right-click menu, opened on `column` in grid `id`. */
async function headerMenu(id: string, column: string): Promise<void> {
  escape();
  const th = tile(id).querySelector<HTMLElement>(`.dc-th[data-column="${column}"]`);
  assert.ok(th, `${id} shows a ${column} header (it shows ${[...tile(id).querySelectorAll<HTMLElement>('.dc-th')]
    .map((h) => h.dataset['column']).join(', ')})`);
  contextMenu(th);
  await settle();
}

/** A group's chevron clicked: the first group row that has one. */
function chevron(id: string): void {
  const el = tile(id).querySelector<HTMLElement>('.dc-row .dc-chevron:not(.dc-chevron-empty)');
  assert.ok(el, `${id} shows a group to open`);
  el.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
}

/** A grid's Column Properties for `column`, its Aggregation set to `fn`, and OK. */
async function setAggregation(at: string, column: string, fn: string): Promise<void> {
  await headerMenu(at, column);
  entry('Properties...').click();
  await settle();
  const win = dom.window.document.querySelector<HTMLElement>('[data-window="Properties"]')!;
  const select = [...win.querySelectorAll('.dc-field')].find((f) => f.querySelector('.dc-field-label')?.textContent === 'Aggregation:')!
    .querySelector('select') as HTMLSelectElement;
  select.value = fn;
  select.dispatchEvent(new dom.window.Event('change'));
  [...win.querySelectorAll<HTMLButtonElement>('.dc-editor-footer button')].find((b) => b.textContent === 'OK')!.click();
  await settle();
}

// -- the checks -----------------------------------------------------------------------------------------------------

/** A grid's commands applied to its saved cube as headless gridApply applies them (headless/grid.ts applied: the
 *  commands, the settings laid on the query, folded as the state lands, written as the screen writes it) -- no planner:
 *  what a run alone adds (column types) is no command's. */
const applyGrid: GridApplier = async (_page, cube, _id, commands) => {
  const snapshot = { ...cube.query, source: SNAPSHOT.source, epoch: 0 } as CubeSnapshot;
  const d = applyGridCommands({ snapshot, config: configurationOf(cube), tree: treeOf(cube) }, commands);
  const landed = fold({ snapshot: applyToSnapshot(d.snapshot, d.config), configuration: d.config, tree: d.tree });
  return writeCube({
    name: cube.name, source: cube.source, snapshot: landed.snapshot, configuration: landed.configuration, tree: landed.tree,
    ...(cube.unknown ? { unknown: cube.unknown } : {}),
  });
};

/** Where two JSON values differ: each path to a differing leaf (an array index as a number), at most `depth` deep. */
function differing(a: unknown, b: unknown, path = '', depth = 9): string[] {
  if (JSON.stringify(a) === JSON.stringify(b)) return [];
  const objects = typeof a === 'object' && a !== null && typeof b === 'object' && b !== null;
  if (!objects || depth === 0) return [path || '(the page)'];
  const keys = [...new Set([...Object.keys(a as object), ...Object.keys(b as object)])];
  return keys.flatMap((k) => differing((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k], path ? `${path}.${k}` : k, depth - 1));
}

type Check = 'told' | 'wire' | 'replays';
/** How a check failed: in words, and -- a replay -- the parts of the page that differ. */
interface Failed { readonly words: string; readonly paths?: readonly string[] }

/** What a gesture made the channel write, held to the three checks: each failing one, how. */
async function judged(before: PageDocument, made: readonly Write[]): Promise<Partial<Record<Check, Failed>>> {
  assert.ok(made.length > 0, 'the gesture changed the page (the channel wrote)');
  const failed: Partial<Record<Check, Failed>> = {};
  let was = before;
  for (const w of made) {
    if (w.edits === null) {
      failed.told = { words: 'written whole: a change no command was told for' };
      was = w.page;
      continue;
    }
    for (const e of w.edits) told.add('page' in e ? e.page.kind : e.command.kind);
    const sent = `[${w.edits.map((e) => toJson(e)).join(',')}]`;
    const said = JSON.parse(pageWritten(pageToJson(w.page), sent)) as { problems?: string[]; edits?: Record<string, unknown>[] };
    if (said.problems) {
      failed.wire = { words: said.problems.join('; ') };
      was = w.page;
      continue;
    }
    if (JSON.stringify(said.edits) !== JSON.stringify(JSON.parse(sent))) failed.wire = { words: 'the host reads other commands than were sent' };
    // the commands as the host read them, applied as the host applies them
    const read: PageEdit[] = said.edits!.map((e) => ('page' in e ? { page: readPageCommand(e['page']) }
      : { grid: e['grid'] as string, command: readGridCommand(e['command']) }));
    try {
      const replayed = (await rebaseEdits({ page: was, held: idsOf(was) }, read, applyGrid)).page;
      const paths = differing(JSON.parse(pageToJson(replayed)), JSON.parse(pageToJson(w.page)));
      if (paths.length > 0) failed.replays = { words: 'its commands, applied, make another page than the screen wrote', paths };
    } catch (e) {
      failed.replays = { words: `its commands do not apply to the page before: ${e instanceof Error ? e.message : String(e)}` };
    }
    was = w.page;
  }
  return failed;
}

/** A gesture: what it needs set up (on a page whose channel is not open yet), then made, as a person makes it. */
interface Gesture {
  readonly name: string;
  /** A change of the view alone (no part of the saved page): it must write nothing. */
  readonly quiet?: true;
  readonly setUp: () => Promise<Record<string, string>>;
  readonly make: (ids: Record<string, string>) => Promise<void> | void;
}

const withChart = async (): Promise<Record<string, string>> => { const g = await twoGrids(); return { ...g, chart: await chartOf(g.a) }; };
/** A page of a grid over the class Trade. */
const classGrid = async (): Promise<Record<string, string>> => {
  newPage();
  const a = pg().addGrid(overClass());
  await settle();
  return { a };
};
const EMEA = { kind: 'condition', column: 'region', operator: 'equal', value: 'EMEA' } as const;
/** Two grids, the first ungrouped to its rows (each cell a value of its column). */
const flat = async (): Promise<Record<string, string>> => {
  const g = await twoGrids();
  await ungroup(g.a, 'desk');
  await ungroup(g.a, 'region');
  await settle();
  return g;
};
/** Two grids, the first with a calculated column: notional shown as a running total (Show As). */
const shownAs = async (): Promise<Record<string, string>> => {
  const g = await twoGrids();
  await headerMenu(g.a, 'notional');
  nested('Show notional As', 'Running total').click();
  await drawn();
  return g;
};
/** The calculated column shownAs made. */
const RUNNING = 'notional_runningTotal';
/** A flat grid filtered from a cell: desk = its first value. */
const filtered = async (): Promise<Record<string, string>> => {
  const g = await flat();
  escape();
  contextMenu(tile(g.a!).querySelector('.dc-row .dc-cell[data-column="desk"]') as HTMLElement);
  await settle();
  nested('Filter').click();
  await settle();
  return g;
};
const withSheet = async (): Promise<Record<string, string>> => {
  const g = await twoGrids();
  (host.querySelector('.dc-sheet-add') as HTMLElement).click();
  await settle();
  return g;
};

const GESTURES: readonly Gesture[] = [
  // the page
  { name: 'a grid added', setUp: twoGrids, make: async ({ a }) => { pg().addGrid(over('desks.csv'), { near: a! }); } },
  { name: 'a sheet added', setUp: twoGrids, make: () => (host.querySelector('.dc-sheet-add') as HTMLElement).click() },
  {
    name: 'a sheet renamed',
    setUp: withSheet,
    make: () => {
      tabOf('Sheet 2').dispatchEvent(new dom.window.MouseEvent('dblclick', { bubbles: true }));
      const field = host.querySelector<HTMLInputElement>('.dc-sheet-tab input')!;
      field.value = 'Charts';
      field.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Enter' }));
    },
  },
  { name: 'a sheet moved', setUp: withSheet, make: () => tabMenu('Sheet 2', 'Move Left') },
  { name: 'a sheet deleted', setUp: withSheet, make: () => tabMenu('Sheet 2', 'Delete') },
  { name: 'a tile renamed', setUp: twoGrids, make: ({ b }) => rename(b!, 'Orders') },
  { name: 'a chart added', setUp: twoGrids, make: async ({ a }) => { await chartOf(a!); } },
  { name: 'a chart frozen', setUp: withChart, make: ({ chart }) => togglePill(chart!) },
  { name: 'a chart removed', setUp: withChart, make: ({ chart }) => removeTile(chart!) },
  { name: 'a grid removed', setUp: twoGrids, make: ({ b }) => removeTile(b!) },
  { name: 'a grid moved to another sheet', setUp: withSheet, make: ({ b }) => gridMenuItem(b!, 'Sheet 2').click() },
  { name: 'a grid copied', setUp: twoGrids, make: ({ a }) => gridMenuItem(a!, 'Copy of Grid').click() },
  {
    name: 'a sheet arranged',
    setUp: twoGrids,
    make: async () => {
      escape();
      (host.querySelector('.dc-page-bar .dc-titlebar-menu') as HTMLElement).click();
      entry('Arrange…').click();
      await settle();
      host.querySelector<HTMLButtonElement>('.dc-layout-picker [data-preset="stacked"]')!.click();
    },
  },
  { name: 'a page filter set', setUp: classGrid, make: async () => { await pg().setFilter({ class: TRADE, condition: EMEA }); } },
  {
    name: 'a page filter taken off',
    setUp: async () => {
      const g = await classGrid();
      await pg().setFilter({ class: TRADE, condition: EMEA });
      return { ...g, filter: onScreen().filters![0]!.id };
    },
    make: async ({ filter }) => { await pg().removeFilter(filter!); },
  },
  {
    name: 'the page renamed in its name box',
    setUp: twoGrids,
    make: () => {
      const box = host.querySelector<HTMLElement>('.dc-page-name')!;
      box.click();
      const field = box.querySelector('input')!;
      field.value = 'Q4 review';
      field.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Enter' }));
    },
  },
  {
    name: 'a page edit undone',
    setUp: withSheet,
    make: () => pg().undo(),
  },
  // charts
  {
    name: "a chart's mark clicked",
    setUp: async () => { const ids = await withChart(); await drawn(); return ids; },
    make: ({ chart }) => clickMark(chart!, 0, 0),
  },
  {
    // its editing grid is the view's, never saved (P7, its band shares reset after a resize, is a marker: jsdom drags none)
    name: 'a frozen chart opened in a grid',
    quiet: true,
    setUp: async () => { const ids = await withChart(); await togglePill(ids['chart']!); return ids; },
    make: ({ chart }) => openInGrid(chart!),
  },
  {
    name: 'a detached chart updated from its editing grid',
    setUp: async () => {
      const g = await twoGrids();
      const chart = await chartOf(g.b);
      await togglePill(chart);
      await removeTile(g.b);
      await openInGrid(chart);
      return { ...g, chart };
    },
    make: ({ chart }) => update(chart!),
  },
  { name: 'a grid with a following chart ungrouped', setUp: withChart, make: ({ a }) => ungroup(a!, 'desk') },
  // grids
  { name: 'a grid ungrouped', setUp: twoGrids, make: ({ a }) => ungroup(a!, 'desk') },
  { name: 'a column sorted from its header menu', setUp: twoGrids, make: async ({ a }) => { await headerMenu(a!, 'notional'); entry('Ascending').click(); } },
  {
    name: 'a sort added from its header menu',
    setUp: async () => { const g = await twoGrids(); await headerMenu(g.a, 'notional'); entry('Ascending').click(); await settle(); return g; },
    make: async ({ a }) => { await headerMenu(a!, 'notional'); entry('Add Descending').click(); },
  },
  {
    name: 'a header clicked to sort',
    setUp: twoGrids,
    make: ({ a }) => tile(a!).querySelector<HTMLElement>('.dc-th[data-column="notional"]')!.click(),
  },
  {
    name: 'the tree column clicked to sort the groups',
    setUp: twoGrids,
    make: ({ a }) => tile(a!).querySelector<HTMLElement>('.dc-th[data-column="__tree"]')!.click(),
  },
  { name: 'a column hidden', setUp: flat, make: async ({ a }) => { await headerMenu(a!, 'desk'); entry('Hide desk').click(); } },
  { name: "a column's Aggregation set in Column Properties", setUp: twoGrids, make: ({ a }) => setAggregation(a!, 'notional', 'max') },
  {
    name: 'a filter added from a cell',
    setUp: flat,
    make: async ({ a }) => {
      escape();
      const cell = tile(a!).querySelector('.dc-row .dc-cell[data-column="desk"]');
      assert.ok(cell, `a desk cell (rows: ${[...tile(a!).querySelectorAll('.dc-row')].map((r) => [...r.querySelectorAll('.dc-cell')].map((c) => (c as HTMLElement).dataset['column']).join(',')).join(' / ')})`);
      contextMenu(cell);
      await settle();
      nested('Filter').click();
    },
  },
  {
    name: 'a measure shown as a running total (Show As)',
    setUp: twoGrids,
    make: async ({ a }) => { await headerMenu(a!, 'notional'); nested('Show notional As', 'Running total').click(); },
  },
  {
    name: 'a calculated column renamed in its Edit Column window',
    setUp: shownAs,
    make: async ({ a }) => {
      // the menu's Edit Column (the fixture's engine answers its own columns only: no header shows the new one)
      cubes.get(a!)!.openColumnEditor({ edit: RUNNING });
      await settle();
      const name = host.ownerDocument.querySelector<HTMLInputElement>('.dc-calc-input-name');
      assert.ok(name, 'the Edit Column window');
      name.value = 'running';
      name.dispatchEvent(new dom.window.Event('input'));
      // the editor compiles what is typed after its pause (upstream's 500 ms), and offers OK once it has
      await new Promise((r) => setTimeout(r, 700));
      await settle();
      const ok = host.ownerDocument.querySelector<HTMLButtonElement>('.dc-calc-ok');
      assert.ok(ok && !ok.disabled, `OK offered (${host.ownerDocument.querySelector('.dc-calc-problem')?.textContent ?? ''})`);
      ok.click();
    },
  },
  {
    name: 'a calculated column deleted in its Edit Column window',
    setUp: shownAs,
    make: async ({ a }) => {
      cubes.get(a!)!.openColumnEditor({ edit: RUNNING });
      await settle();
      const remove = host.ownerDocument.querySelector<HTMLButtonElement>('.dc-calc-delete');
      assert.ok(remove, 'the Edit Column window, its Delete');
      remove.click();
    },
  },
  {
    name: 'a filter changed in the Filters window',
    setUp: filtered,
    make: async ({ a }) => {
      cubes.get(a!)!.openFilters();
      await settle();
      host.ownerDocument.querySelector<HTMLButtonElement>('.dc-filter-not')!.click();
      await settle();
      host.ownerDocument.querySelector<HTMLButtonElement>('.dc-filter-ok')!.click();
    },
  },
  { name: 'a group opened', setUp: twoGrids, make: ({ a }) => chevron(a!) },
  {
    name: 'every group collapsed',
    setUp: async () => { const g = await twoGrids(); chevron(g.a); await settle(); return g; },
    make: ({ a }) => {
      escape();
      contextMenu(tile(a!).querySelector('.dc-row .dc-cell') as HTMLElement);
      entry('Collapse All').click();
    },
  },
  {
    name: 'a grid added while the page bar is folded',
    setUp: async () => { const g = await twoGrids(); pg().setBarFolded(true); await settle(); return g; },
    make: ({ a }) => { pg().addGrid(over('desks.csv'), { near: a! }); },
  },
  {
    name: "a grid's settings set by its host (edit: configure)",
    setUp: twoGrids,
    make: async ({ a }) => { await cubes.get(a!)!.edit([{ kind: 'configure', patch: { maxRows: 7 } }], 'settings'); },
  },
];

/** A gap a gesture shows: its items, and how it fails (the host's words; the parts of the page that differ). */
interface Gap { readonly items: readonly string[]; readonly words?: RegExp; readonly paths?: readonly string[] }
/** Each gesture's checks that fail today, by the plan's items: the only failures allowed, until each is closed. */
const L2_GAPS: Record<string, Partial<Record<Check, Gap>>> = {
  'the page renamed in its name box': { told: { items: ['P1'] } },
  'a page edit undone': { told: { items: ['P5'] } },
  "a chart's mark clicked": { told: { items: ['G7'] } },
  'a detached chart updated from its editing grid': { told: { items: ['P8'] } },
  'a grid with a following chart ungrouped': { replays: { items: ['V1'], paths: ['views.2.spec.split'] } },
};

/** The command kinds no gesture here tells, each by its item or why: the screen makes none, or none is driven yet. */
const UNTOLD: Record<string, { readonly items: readonly string[]; readonly why?: string }> = {
  renamePage: { items: ['P1', 'P2'] },
  stack: { items: ['P14'] },
  arrange: { items: ['P14'] },
  snap: { items: ['P3'] },
  live: { items: ['P3'] },
  measures: { items: [], why: "nature: Python's measure under another name; the screen's panels set columns" },
};
/** No gesture leaves: the list only grows (a gesture taken off is a check taken off). */
const GESTURES_AT_LEAST = 36;

describe('L2, L3: every gesture told as commands that replay to the page it made, and that the host reads', () => {
  it('the gestures and their gaps are one list', () => {
    const names = GESTURES.map((g) => g.name);
    assert.equal(new Set(names).size, names.length, 'each gesture named once');
    assert.ok(names.length >= GESTURES_AT_LEAST, `${names.length} gestures: none is taken off (at least ${GESTURES_AT_LEAST})`);
    assert.deepEqual(Object.keys(L2_GAPS).filter((n) => !names.includes(n)), [], 'gaps listed for no gesture');
  });

  it('a page left alone writes nothing (the control)', async () => {
    await twoGrids();
    await drawn();
    channel = new LiveChannel(editor, { page: onScreen, reopen: async () => {} }, 0);
    channel.opened(1);
    await drawn();
    await channel.idle();
    assert.equal(writes.length, 0, 'nothing written: no gesture made');
  });

  for (const g of GESTURES) {
    it(g.name, async () => {
      const ids = await g.setUp();
      await drawn();
      channel = new LiveChannel(editor, { page: onScreen, reopen: async () => {} }, 0);
      channel.opened(1);
      const before = onScreen();
      // quiet first: what the set-up started has settled, and nothing is written before the gesture
      await drawn();
      await channel.idle();
      assert.equal(writes.length, 0, `${g.name}: the page was not quiet before the gesture (a write came from its set-up)`);
      assert.equal(pageToJson(onScreen()), pageToJson(before), `${g.name}: the page changed before the gesture`);
      await g.make(ids);
      await drawn();
      await channel.idle();
      await drawn();
      if (g.quiet) {
        assert.equal(writes.length, 0, `${g.name}: a change of the view alone wrote the page`);
        return;
      }
      const failed = await judged(before, writes);
      const listed = L2_GAPS[g.name] ?? {};
      for (const check of ['told', 'wire', 'replays'] as const) {
        const f = failed[check];
        const gap = listed[check];
        if (f === undefined) {
          assert.ok(!gap, `${g.name}: ${check} passes now -- close ${gap?.items.join(', ')} and take it off`);
          continue;
        }
        assert.ok(gap, `${g.name}: ${check} fails -- ${f.words}${f.paths ? ` (${f.paths.join(', ')})` : ''} -- a new gap: fix it, or list it with its item`);
        if (gap.words) assert.match(f.words, gap.words, `${g.name}: ${check} fails another way than ${gap.items.join(', ')}: ${f.words}`);
        if (gap.paths) assert.deepEqual(f.paths, gap.paths, `${g.name}: ${check} differs in other parts of the page than ${gap.items.join(', ')}'s`);
      }
    });
  }

  after(() => {
    const kinds = [...GRID_COMMAND_KINDS, ...PAGE_COMMAND_KINDS];
    const problems = [
      ...kinds.filter((k) => !told.has(k) && UNTOLD[k] === undefined).map((k) => `${k}: no gesture tells it -- drive one here, or list it`),
      ...Object.keys(UNTOLD).filter((k) => told.has(k)).map((k) => `${k}: a gesture tells it now -- take it off the list`),
      ...Object.keys(UNTOLD).filter((k) => !(kinds as readonly string[]).includes(k)).map((k) => `${k}: no such kind`),
    ];
    assert.deepEqual(problems, [], 'every command kind is told by some gesture, or listed');
  });
});

describe('P9: a saved page opened keeps what it cannot open', () => {
  it('a view whose grid cannot be opened is dropped from the page -- P9 open', async () => {
    const { a } = await twoGrids();
    const saved = readPage(pageToJson(onScreen()));
    page!.dispose();
    newPage();
    // only the first grid's source can be opened: the second's maker is missing
    const kept = saved.views.find((v) => v.id === a) as { readonly cube: string };
    pg().restore(saved, new Map([[kept.cube, over('trades.csv')]]));
    await settle();
    assert.ok(saved.views.length > onScreen().views.length, 'P9 open: the view it could not open is gone from the page');
  });
});
