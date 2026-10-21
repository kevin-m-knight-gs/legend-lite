// THE TAB'S SIDE OF THE PAGE KEEPER (src/app/live-channel.ts; docs/DATACUBE_PAGE_KEEPER_2026_10_20.md): the edits
// made on screen written with the page, as applied there; a change no edit was told for written whole; what the screen
// does while following the other editor never taken for its own; writes and follows one at a time, in order -- a
// follow told while a write is on its way waits for it, and sees the version it made; a write the keeper made again on
// top opening the page as the keeper made it; a refused one leaving what is on screen to be written whole. And a real
// page (PageApp, its grids CubeApps over a gated engine) writing to a keeper that makes its edits again on top of the
// other editor's by DataCube's one rule (page-rebase.ts): both editors' changes on the page it ends with.

import assert from 'node:assert/strict';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { JSDOM } from 'jsdom';

import { CubeApp } from '../src/app.ts';
import { DEFAULT_CONFIGURATION } from '../src/config.ts';
import type { CubeDocument, FileSource } from '../src/cube-document.ts';
import { LiveChannel, type LiveScreen } from '../src/app/live-channel.ts';
import type { LiveCommand, LivePage } from '../src/app/services.ts';
import { applyPageCommands, idsOf, type PageCommand } from '../src/page-commands.ts';
import { PageApp, type GridMaker } from '../src/page/page-app.ts';
import type { PageDocument } from '../src/page-document.ts';
import { rebaseEdits, type GridApplier } from '../src/page-rebase.ts';
import { CubeRefusal } from '../src/snapshot.ts';
import { GateEngine, SNAPSHOT, StubPlanner, settle } from './cube-fixture.ts';
import { corpus } from './fixtures/pages/make.ts';

const trades = (await corpus())['trades.page.json']!;
const renamed = (page: PageDocument, name: string): PageDocument => ({ ...page, name });

/** A promise held until the test settles it. */
function held<T>(): { promise: Promise<T>; resolve: (v: T) => void; reject: (e: unknown) => void } {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

type Answer = { readonly version: number; readonly combined: boolean };

/** An editor (the keeper's host) that records each write and answers it as the test says. */
class Editor implements LivePage {
  readonly writes: { at: number; page: PageDocument; edits: readonly LiveCommand[] | null }[] = [];
  answers: (() => Promise<Answer>)[] = [];
  read(): never { throw new Error('not read here'); }
  changes(): never { throw new Error('not followed here'); }
  report(at: number, page: PageDocument, edits: readonly LiveCommand[] | null): Promise<Answer> {
    this.writes.push({ at, page, edits });
    const answer = this.answers.shift();
    return answer ? answer() : Promise.resolve({ version: at + 1, combined: false });
  }
}

/** A screen showing `page`, which the test changes; what reopening it did. */
function screen(start: PageDocument): LiveScreen & { now: PageDocument | undefined; reopened: number } {
  const s = {
    now: start as PageDocument | undefined,
    reopened: 0,
    page: () => s.now,
    reopen: async () => { s.reopened += 1; },
  };
  return s;
}

/** Every timer due by now fired, and the channel's queue drained. */
async function written(channel: LiveChannel): Promise<void> {
  await new Promise((r) => setTimeout(r, 5));
  await channel.idle();
}

describe('the channel', () => {
  it('writes the edits made on screen with the page, as applied there, and then has none pending', async () => {
    const editor = new Editor();
    const s = screen(trades);
    const channel = new LiveChannel(editor, s, 0);
    channel.opened(1);
    channel.edit({ page: { kind: 'renamePage', name: 'Here' } });
    s.now = renamed(trades, 'Here');
    channel.changed();
    await written(channel);
    assert.deepEqual(editor.writes.map((w) => [w.at, w.edits]), [[1, [{ page: { kind: 'renamePage', name: 'Here' } }]]]);
    assert.equal(channel.shown, 2);
    channel.edit({ grid: 'grid', command: { kind: 'group', rows: ['desk'] } });
    s.now = renamed(trades, 'Here, grouped');
    channel.changed();
    await written(channel);
    assert.deepEqual(editor.writes[1]!.edits, [{ grid: 'grid', command: { kind: 'group', rows: ['desk'] } }], 'only the new edit');
  });

  it('writes a change no edit was told for whole', async () => {
    const editor = new Editor();
    const s = screen(trades);
    const channel = new LiveChannel(editor, s, 0);
    channel.opened(1);
    s.now = renamed(trades, 'No command');
    channel.changed();
    await written(channel);
    assert.equal(editor.writes[0]!.edits, null);
  });

  it('takes nothing the screen does while following the other editor for its own', async () => {
    const editor = new Editor();
    const s = screen(trades);
    const channel = new LiveChannel(editor, s, 0);
    channel.opened(1);
    await channel.following(async () => {
      channel.shown = undefined;
      // the other editor's command, applied: the screen tells it, and changes
      channel.edit({ page: { kind: 'renamePage', name: 'Python' } });
      s.now = renamed(trades, 'Python');
      channel.changed();
    });
    channel.shown = 2;
    channel.changed();
    await written(channel);
    assert.equal(editor.writes.length, 0, 'nothing of its own to write -- Python\'s change never written back as the screen\'s');
    // and an edit of its own after: written alone
    channel.edit({ page: { kind: 'renamePage', name: 'Mine' } });
    s.now = renamed(trades, 'Mine');
    channel.changed();
    await written(channel);
    assert.deepEqual(editor.writes.map((w) => w.edits), [[{ page: { kind: 'renamePage', name: 'Mine' } }]]);
  });

  // (the browser harness: such a write, made behind a page Python had moved, had the page opened again in place of
  // following Python's command)
  it('writes nothing when nothing was made on screen since it opened', async () => {
    const editor = new Editor();
    const s = screen(trades);
    const channel = new LiveChannel(editor, s, 0);
    channel.opened(1);
    // the page as it opened, changed again by nothing (a view landing, a restore settling)
    channel.changed();
    channel.changed();
    await written(channel);
    assert.equal(editor.writes.length, 0);
  });

  it('runs a follow told while a write is on its way after it, at the version the write made', async () => {
    const editor = new Editor();
    const answer = held<Answer>();
    editor.answers.push(() => answer.promise);
    const s = screen(trades);
    const channel = new LiveChannel(editor, s, 0);
    channel.opened(1);
    channel.edit({ page: { kind: 'renamePage', name: 'Here' } });
    s.now = renamed(trades, 'Here');
    channel.changed();
    await new Promise((r) => setTimeout(r, 5));
    assert.equal(editor.writes.length, 1, 'the write is on its way');
    const started: (number | undefined)[] = [];
    // the version notice of that very write arrives first
    const follow = channel.enqueue(async () => { started.push(channel.shown); });
    await new Promise((r) => setTimeout(r, 5));
    assert.deepEqual(started, [], 'not while the write is on its way');
    answer.resolve({ version: 2, combined: false });
    await follow;
    assert.deepEqual(started, [2], 'after it, at the version it made: its own write is never taken for the other editor\'s');
  });

  it('opens the page as the keeper made it when its edits were made again on top', async () => {
    const editor = new Editor();
    editor.answers.push(async () => ({ version: 3, combined: true }));
    const s = screen(trades);
    const channel = new LiveChannel(editor, s, 0);
    channel.opened(1);
    channel.edit({ page: { kind: 'renamePage', name: 'Here' } });
    s.now = renamed(trades, 'Here');
    channel.changed();
    await written(channel);
    assert.equal(s.reopened, 1);
    assert.equal(channel.shown, undefined, 'at no version until the page is opened again');
  });

  it('writes what is on screen whole after a refused write', async () => {
    const editor = new Editor();
    editor.answers.push(async () => { throw new Error('409'); });
    const s = screen(trades);
    const channel = new LiveChannel(editor, s, 0);
    channel.opened(1);
    channel.edit({ page: { kind: 'renamePage', name: 'Here' } });
    s.now = renamed(trades, 'Here');
    channel.changed();
    await written(channel);
    s.now = renamed(trades, 'Here again');
    channel.edit({ page: { kind: 'renamePage', name: 'Here again' } });
    channel.changed();
    await written(channel);
    assert.equal(editor.writes[1]!.edits, null, 'the refused edit lives on in the page: written whole');
  });

  it('writes nothing once stopped, nor while at no version', async () => {
    const editor = new Editor();
    const s = screen(trades);
    const channel = new LiveChannel(editor, s, 0);
    channel.changed();
    await written(channel);
    channel.opened(1);
    channel.stop();
    s.now = renamed(trades, 'Too late');
    channel.changed();
    await written(channel);
    assert.equal(editor.writes.length, 0);
  });
});

// -- a real page and its keeper ----------------------------------------------------------------------------------------

let dom: JSDOM;
let host: HTMLElement;
let engine: GateEngine;

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
});

let page: PageApp | undefined;
afterEach(async () => {
  await settle();
  page?.dispose();
  page = undefined;
});

const file = (name: string): FileSource => ({
  _type: 'file', name, format: 'csv', size: 10, sha256: name.padEnd(64, '0').slice(0, 64),
  columns: SNAPSHOT.columns.map((c) => ({ name: c.name, type: c.type })),
});

/** A grid's command as the keeper's host applies one -- here a group, setting its rows (the host's grid base is no
 *  part of what this test holds to). */
const applyGrid: GridApplier = async (_page, cube, _id, commands) => {
  let next: CubeDocument = cube;
  for (const c of commands) {
    if (c.kind !== 'group') throw new CubeRefusal(`this keeper makes groups only again, not ${c.kind}`);
    next = { ...next, query: { ...next.query, rows: [...c.rows] } };
  }
  return next;
};

/** A keeper as Python's is (keeper.py settle): a write kept as written at its version, else its edits made again on
 *  top by DataCube's rule -- a change by no command then refused. */
class Keeper implements LivePage {
  version = 1;
  held: string[];
  /** The edits each write carried. */
  readonly written: (readonly LiveCommand[] | null)[] = [];
  page: PageDocument;
  constructor(page: PageDocument) {
    this.page = page;
    this.held = idsOf(page);
  }
  read(): never { throw new Error('opened by the test'); }
  changes(): never { throw new Error('followed by the test'); }
  /** Another editor (Python) writes commands it made at the version now. */
  pythonWrites(commands: readonly PageCommand[]): void {
    const done = applyPageCommands({ page: this.page, held: this.held }, commands);
    this.page = done.draft.page;
    this.held = [...done.draft.held];
    this.version += 1;
  }
  async report(at: number, written: PageDocument, edits: readonly LiveCommand[] | null): Promise<Answer> {
    this.written.push(edits);
    if (at === this.version) {
      this.page = written;
      this.held = [...new Set([...this.held, ...idsOf(written)])];
      this.version += 1;
      return { version: this.version, combined: false };
    }
    if (edits === null) throw new Error('409: the page moved on, and a change by no command is not made again');
    const done = await rebaseEdits({ page: this.page, held: this.held }, edits, applyGrid);
    this.page = done.page;
    this.held = [...done.held];
    this.version += 1;
    return { version: this.version, combined: true };
  }
}

describe('a real page and its keeper', () => {
  it('writes its grids\' and its own edits as commands, which the keeper makes again on top of the other editor\'s', async () => {
    const cubes = new Map<string, CubeApp>();
    let channel: LiveChannel | undefined;
    const over = (name: string): GridMaker => (gridHost, spawned, start) => {
      const made = new CubeApp(gridHost, start?.snapshot ?? SNAPSHOT, {
        engine, planner: new StubPlanner(), compact: true, cubeSource: file(name), sourceLabel: name,
        configuration: start?.configuration ?? { ...DEFAULT_CONFIGURATION, reportTitle: name },
        ...spawned,
        // as DataCube's app wires a grid on a page another editor serves
        onCommand: (command) => { if (spawned.id !== undefined) channel?.edit({ grid: spawned.id, command }); },
      });
      if (spawned.id !== undefined) cubes.set(spawned.id, made);
      return made;
    };
    page = new PageApp({
      host, title: 'Q3', empty: (slot) => { slot.textContent = 'empty'; },
      onChange: () => channel?.changed(),
      onPageCommand: (command) => channel?.edit({ page: command }),
    });
    const a = page.addGrid(over('trades.csv'));
    page.addGrid(over('desks.csv'));
    await settle();
    const keeper = new Keeper(page.document('Q3')!);
    const makers = new Map<string, GridMaker>();
    channel = new LiveChannel(keeper, {
      page: () => page!.document('Q3', undefined, true),
      reopen: async () => {
        // the page as the keeper made it, opened again whole (datacube-app.ts openLive)
        for (const v of keeper.page.views) if (v.kind === 'grid' && !makers.has(v.id)) makers.set(v.id, over(`${v.id}.csv`));
        await channel!.following(async () => {
          page!.restore(keeper.page, makers);
          await page!.ready();
          await settle();
        });
        channel!.opened(keeper.version);
      },
    }, 0);
    channel.opened(keeper.version);
    // the other editor writes first: a sheet of its own, so the edits made here next are made against a version the
    // page has moved on from
    keeper.pythonWrites([{ kind: 'addSheet', name: 'From Python' }]);

    // edits on screen: a grid regrouped (as its own edits are applied), a grid added to the page
    assert.equal(await cubes.get(a)!.edit([{ kind: 'group', rows: ['desk'] }], 'Group'), null);
    const added = page.addGrid(over('more.csv'));
    await settle();
    await written(channel);
    await settle();

    // its edits were commands, not the page whole -- the grid's group as its grid made it, the grid added as the page
    // made it -- and the keeper made them again on top
    const told = keeper.written[0]!;
    assert.ok(told !== null, 'written as commands');
    assert.deepEqual(told.map((e) => ('grid' in e ? `${e.grid}:${e.command.kind}` : e.page.kind)), [`${a}:group`, 'addGrid']);
    assert.equal(keeper.version, 3, 'kept, made again on top of the other editor\'s');
    const kinds = (keeper.page.views.filter((v) => v.kind === 'grid').length);
    assert.equal(kinds, 3, 'the grid added here is on the page');
    assert.ok(keeper.page.sheets.some((s) => s.name === 'From Python'), "the other editor's sheet is on the page");
    const grouped = keeper.page.cubes.find((c) => c.id === a)!.cube.query.rows;
    assert.deepEqual(grouped, ['desk'], 'the grid regrouped here is regrouped on the page');
    // and the page on screen is the keeper's
    const now = page.document('Q3', undefined, true)!;
    assert.deepEqual(now.sheets.map((s) => s.name ?? null), keeper.page.sheets.map((s) => s.name ?? null));
    assert.deepEqual(now.views.map((v) => v.id).sort(), keeper.page.views.map((v) => v.id).sort());
    assert.ok(now.views.some((v) => v.id === added), 'the grid added here, under its id');
  });
});
