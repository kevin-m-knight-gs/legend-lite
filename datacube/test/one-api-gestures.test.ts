// THE ONE API LEDGERS, BEHAVIOURAL HALF (docs/DATACUBE_ONE_API_PLAN_2026_10_21.md §3: L2, L3). The real page -- PageApp,
// its grids CubeApps, its charts -- wired to the tab's live channel (src/app/live-channel.ts) as DataCube's app wires a
// page another editor serves; each gesture made as a person makes it, and what the channel then writes held to the
// tenet:
//
//   told    the change was told as commands: the channel did not have to write the page whole (L2)
//   replays the commands told, applied to the page before by the shared functions (rebaseEdits: applyPageCommand, and a
//           grid's applyGridCommands), make the page the screen wrote (L2)
//   wire    the host reads them: headless pageWritten, what Python's keeper reads a write with, takes them as sent (L3)
//
// Each gesture's failing checks are listed by the plan's items, and only those: a gesture failing a check it is not
// listed for is a new gap; one listed that now passes is a gap closed -- close its item, take it off. The static half
// is one-api-ledger.test.ts; Python's is python/tests/test_one_api.py.

import assert from 'node:assert/strict';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { JSDOM } from 'jsdom';
import { getInstanceByDom } from 'echarts/core';

import { CubeApp } from '../src/app.ts';
import { DEFAULT_CONFIGURATION } from '../src/config.ts';
import { configurationOf, treeOf, writeCube, type FileSource } from '../src/cube-document.ts';
import { applyGridCommands } from '../src/grid-commands.ts';
import { LiveChannel } from '../src/app/live-channel.ts';
import type { LiveCommand, LivePage } from '../src/app/services.ts';
import { idsOf } from '../src/page-commands.ts';
import { pageToJson, type PageDocument } from '../src/page-document.ts';
import { rebaseEdits, type GridApplier } from '../src/page-rebase.ts';
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

/** The page as the tab writes it (datacube-app.ts pageNow: its name as it is now, a page of no grid too). */
const onScreen = (): PageDocument => pg().document(pg().title || 'Q3', undefined, true)!;

function newPage(extra: Partial<PageAppOptions> = {}): PageApp {
  page = new PageApp({
    host, title: 'Q3', confirm: () => true, empty: (slot) => { slot.textContent = 'empty'; },
    onChange: () => channel?.changed(),
    onPageCommand: (command) => channel?.edit({ page: command }),
    // the host keeps the name, and says the page changed (datacube-app.ts renamePage: setTitle, then onCubeView, which
    // tells the live channel)
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

async function drawn(): Promise<void> {
  await new Promise((r) => setTimeout(r, 200));
  await settle();
}

function gridMenuItem(gridTile: string, text: string): HTMLElement {
  escape();
  (tile(gridTile).querySelector('.dc-tile-menu') as HTMLElement).click();
  const item = menuItems().find((i) => label(i) === text);
  assert.ok(item, `${gridTile}'s menu has ${text}`);
  return item;
}

function chartMenu(chart: string): HTMLElement[] {
  escape();
  (tile(chart).querySelector('.dc-chart-tile') as HTMLElement)
    .dispatchEvent(new dom.window.MouseEvent('contextmenu', { bubbles: true, cancelable: true }));
  return menuItems();
}

/** A submenu's entry: `text` under `parent` in the menu open now. */
function nested(parent: string, text: string): HTMLElement {
  const item = [...dom.window.document.querySelectorAll<HTMLElement>('.dc-menu .dc-menu-item')]
    .find((el) => label(el) === text && label(el.parentElement!.closest('.dc-menu-item') as HTMLElement) === parent);
  assert.ok(item, `${parent} > ${text}`);
  return item;
}

async function chartOf(gridTile: string): Promise<string> {
  const charts = (): string[] => [...host.querySelectorAll<HTMLElement>('[data-tile^="chart-"]')].map((t) => t.dataset['tile']!);
  const before = new Set(charts());
  (tile(gridTile).querySelector('.dc-row .dc-cell') as HTMLElement)
    .dispatchEvent(new dom.window.MouseEvent('contextmenu', { bubbles: true, cancelable: true }));
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
  const item = chartMenu(chart).find((i) => label(i) === 'Open in grid');
  assert.ok(item && item.getAttribute('aria-disabled') !== 'true', `${chart} offers Open in grid`);
  item.click();
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
  assert.ok(th, `${id} shows a ${column} header`);
  th.dispatchEvent(new dom.window.MouseEvent('contextmenu', { bubbles: true, cancelable: true }));
  await settle();
}

// -- the checks -----------------------------------------------------------------------------------------------------

/** A grid's commands applied to its saved cube as the shared function applies them, folded as the screen's state is
 *  written (writeCube) -- no planner: what a run alone adds (column types) is no command's. */
const applyGrid: GridApplier = async (_page, cube, _id, commands) => {
  const snapshot = { ...cube.query, source: SNAPSHOT.source, epoch: 0 } as CubeSnapshot;
  const d = applyGridCommands({ snapshot, config: configurationOf(cube) }, commands);
  return writeCube({
    name: cube.name, source: cube.source, snapshot: d.snapshot, configuration: d.config, tree: treeOf(cube),
    ...(cube.unknown ? { unknown: cube.unknown } : {}),
  });
};

type Check = 'told' | 'replays' | 'wire';

/** What a gesture made the channel write, held to the three checks: each failing one, in words. */
async function judged(before: PageDocument, made: readonly Write[]): Promise<Partial<Record<Check, string>>> {
  assert.ok(made.length > 0, 'the gesture changed the page (the channel wrote)');
  const failed: Partial<Record<Check, string>> = {};
  let was = before;
  for (const w of made) {
    if (w.edits === null) {
      failed.told = 'written whole: a change no command was told for';
      was = w.page;
      continue;
    }
    const said = JSON.parse(pageWritten(pageToJson(w.page), `[${w.edits.map((e) => toJson(e)).join(',')}]`)) as
      { problems?: string[]; edits?: unknown[] };
    if (said.problems) failed.wire = `the host refuses it: ${said.problems.join('; ')}`;
    else if (JSON.stringify(said.edits) !== JSON.stringify(JSON.parse(`[${w.edits.map((e) => toJson(e)).join(',')}]`))) {
      failed.wire = 'the host reads other commands than were sent';
    }
    try {
      const replayed = (await rebaseEdits({ page: was, held: idsOf(was) }, w.edits, applyGrid)).page;
      if (pageToJson(replayed) !== pageToJson(w.page)) failed.replays = 'its commands, applied, make another page than the screen wrote';
    } catch (e) {
      failed.replays = `its commands do not apply to the page before: ${e instanceof Error ? e.message : String(e)}`;
    }
    was = w.page;
  }
  return failed;
}

/** A gesture: what it needs set up (on a page whose channel is not open yet), then made, as a person makes it. */
interface Gesture {
  readonly name: string;
  readonly setUp: () => Promise<Record<string, string>>;
  readonly make: (ids: Record<string, string>) => Promise<void> | void;
}

const GESTURES: readonly Gesture[] = [
  { name: 'a grid added', setUp: async () => twoGrids(), make: async ({ a }) => { pg().addGrid(over('desks.csv'), { near: a! }); } },
  { name: 'a sheet added', setUp: twoGrids, make: () => (host.querySelector('.dc-sheet-add') as HTMLElement).click() },
  { name: 'a tile renamed', setUp: twoGrids, make: ({ b }) => rename(b!, 'Orders') },
  { name: 'a chart added', setUp: twoGrids, make: async ({ a }) => { await chartOf(a!); } },
  {
    name: 'a chart frozen',
    setUp: async () => { const g = await twoGrids(); return { ...g, chart: await chartOf(g.a) }; },
    make: ({ chart }) => togglePill(chart!),
  },
  {
    name: 'a chart removed',
    setUp: async () => { const g = await twoGrids(); return { ...g, chart: await chartOf(g.a) }; },
    make: ({ chart }) => removeTile(chart!),
  },
  { name: 'a grid removed', setUp: twoGrids, make: ({ b }) => removeTile(b!) },
  {
    name: 'a grid moved to another sheet',
    setUp: async () => { const g = await twoGrids(); (host.querySelector('.dc-sheet-add') as HTMLElement).click(); await settle(); return g; },
    make: ({ b }) => gridMenuItem(b!, 'Sheet 2').click(),
  },
  {
    name: 'a grid copied',
    setUp: twoGrids,
    make: ({ a }) => gridMenuItem(a!, 'Copy of Grid').click(),
  },
  { name: 'a grid ungrouped', setUp: twoGrids, make: ({ a }) => ungroup(a!, 'desk') },
  {
    name: 'a grid with a following chart ungrouped',
    setUp: async () => { const g = await twoGrids(); return { ...g, chart: await chartOf(g.a) }; },
    make: ({ a }) => ungroup(a!, 'desk'),
  },
  {
    name: 'a measure shown as a running total (Show As)',
    setUp: twoGrids,
    make: async ({ a }) => { await headerMenu(a!, 'notional'); nested('Show notional As', 'Running total').click(); },
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
    name: "a chart's mark clicked",
    setUp: async () => { const g = await twoGrids(); const chart = await chartOf(g.a); await drawn(); return { ...g, chart }; },
    make: ({ chart }) => clickMark(chart!, 0, 0),
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
  {
    name: 'a page edit undone',
    setUp: async () => { const g = await twoGrids(); (host.querySelector('.dc-sheet-add') as HTMLElement).click(); await settle(); return g; },
    make: () => pg().undo(),
  },
  {
    name: "a grid's settings applied by its host (applyConfiguration)",
    setUp: twoGrids,
    make: async ({ a }) => { await cubes.get(a!)!.applyConfiguration({ maxRows: 7 }); },
  },
];

/** Each gesture's checks that fail today, by the plan's items: the only failures allowed, until each is closed. */
const L2_GAPS: Record<string, Partial<Record<Check, readonly string[]>>> = {
  'a grid with a following chart ungrouped': { replays: ['V1'] },
  'a measure shown as a running total (Show As)': { wire: ['C1'] },
  'the page renamed in its name box': { told: ['P1'] },
  "a chart's mark clicked": { told: ['G7'] },
  'a detached chart updated from its editing grid': { told: ['P8'] },
  'a page edit undone': { told: ['P5'] },
  "a grid's settings applied by its host (applyConfiguration)": { told: ['G10'] },
};

describe('L2, L3: every gesture told as commands that replay to the page it made, and that the host reads', () => {
  for (const g of GESTURES) {
    it(g.name, async () => {
      const ids = await g.setUp();
      await drawn();
      channel = new LiveChannel(editor, { page: onScreen, reopen: async () => {} }, 0);
      channel.opened(1);
      const before = onScreen();
      const n = writes.length;
      await g.make(ids);
      await drawn();
      await channel.idle();
      await drawn();
      const failed = await judged(before, writes.slice(n));
      const listed = L2_GAPS[g.name] ?? {};
      for (const check of ['told', 'replays', 'wire'] as const) {
        if (failed[check] !== undefined) {
          assert.ok(listed[check], `${g.name}: ${check} fails -- ${failed[check]} -- a new gap: fix it, or list it with its item`);
        } else {
          assert.ok(!listed[check], `${g.name}: ${check} passes now -- close ${listed[check]?.join(', ')} and take it off`);
        }
      }
    });
  }
});
