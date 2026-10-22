// DATACUBE'S APP ON A PAGE ANOTHER EDITOR SERVES (src/app/datacube-app.ts, Python's `ll.Page`): the app as a tab opens
// it -- over a stand-in editor that says the page, its moves and the writes it takes -- following the page from its
// start: a page of no grid yet followed as Python adds to it, a first read that failed tried again at its next move (the
// S4 audit's 1: only New ▸ Blank Page leaves the page, D1), and a follow on its way when the page is left landing
// nothing (the S4 audit's 4).

import assert from 'node:assert/strict';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { JSDOM } from 'jsdom';

import { openDataCube } from '../src/app/datacube-app.ts';
import type { LivePage } from '../src/app/services.ts';
import { pageNew } from '../src/headless/page.ts';
import { readPage, type PageDocument } from '../src/page-document.ts';
import { corpus } from './fixtures/pages/make.ts';
import { GateEngine, StubPlanner } from './cube-fixture.ts';

/** A promise held until the test settles it. */
function held<T>(): { promise: Promise<T>; resolve: (v: T) => void } {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((res) => { resolve = res; });
  return { promise, resolve };
}

const settle = (): Promise<void> => new Promise((done) => { setTimeout(done, 0); });

/** Python's page before its first grid (`ll.Page('Q3')` shown): a page of no grid, as pageNew makes one. */
const EMPTY: PageDocument = readPage(JSON.parse((JSON.parse(pageNew('Q3')) as { page: string }).page), { empty: true });

/** The editor Python is, as the tab sees it: what it is asked, and what it answers, the test's to say. */
class Editor implements LivePage {
  reads = 0;
  readonly changesAsked: number[] = [];
  /** The answer to the next read: the page at version 1, or a failure. */
  read: LivePage['read'] = async () => {
    this.reads += 1;
    return { version: 1, page: EMPTY, held: ['sheet-1'] };
  };
  changes: LivePage['changes'] = async (since) => {
    this.changesAsked.push(since);
    return { version: since + 1, commands: [] };
  };
  report: LivePage['report'] = async (at) => ({ version: at, combined: false });
}

let dom: JSDOM;

beforeEach(() => {
  dom = new JSDOM('<!doctype html><div id="host"></div><div id="status"></div><div id="windows"></div>', { pretendToBeVisual: true });
  const g = globalThis as unknown as Record<string, unknown>;
  g['window'] = dom.window;
  g['document'] = dom.window.document;
  g['requestAnimationFrame'] = (cb: FrameRequestCallback) => {
    cb(0);
    return 1;
  };
  g['cancelAnimationFrame'] = () => {};
});

afterEach(() => {
  dom.window.close();
});

/** The app opened on `editor`'s page, as a tab opens it: what it watches through, and its page's text. */
async function opened(editor: Editor, frame?: (name: string) => Promise<unknown>):
  Promise<{ moved: (version: number) => void; unwatched: () => boolean; text: () => string; shown: () => number | undefined }> {
  const watchers: ((now: { version: number; frames: Record<string, number> } | string) => void)[] = [];
  let unwatched = false;
  let shown: () => number | undefined = () => { throw new Error('the app said no version on screen to its editor'); };
  const services = {
    engine: {
      plane: { word: 'engine', planes: [], choose() {} },
      label: 'engine',
      reading: {},
      // a frame's rows, planned and run here over the fixture's planner and engine: what opening a page's grid asks
      rows: () => ({ planner: new StubPlanner(), engine: new GateEngine() }),
      // a source to pick: the page offers New ▸ Blank Page; the host's frames, as the test says them
      database: { ingestFile: async () => { throw new Error('no file here'); }, ...(frame ? { frame } : {}) },
    },
    live: {
      page: editor,
      watch: (fn: (now: { version: number; frames: Record<string, number> } | string) => void, onScreen?: () => number | undefined) => {
        watchers.push(fn);
        if (onScreen) shown = onScreen;
        return () => { unwatched = true; };
      },
    },
    browser: {
      download() {}, writeClipboard: async () => {}, readSettings: () => undefined, writeSettings() {},
      title: { base: '', set() {} },
    },
  };
  const doc = dom.window.document;
  await openDataCube({
    services: services as never,
    host: doc.getElementById('host')!,
    status: doc.getElementById('status')!,
    windows: doc.getElementById('windows')!,
    start: { kind: 'live' },
    taken: [],
    settled: { show() {} },
  } as never);
  return {
    moved: (version) => { for (const w of watchers) w({ version, frames: {} }); },
    unwatched: () => unwatched,
    text: () => (doc.body.textContent ?? '').replace(/\s+/g, ' '),
    shown: () => shown(),
  };
}

/** New ▸ Blank Page, from the page's menu. */
function blankPage(): void {
  const doc = dom.window.document;
  (doc.querySelector('.dc-page-bar .dc-titlebar-menu') as HTMLElement).click();
  const item = (label: string): HTMLElement => {
    const items = [...doc.querySelectorAll<HTMLElement>('.dc-menu [role="menuitem"]')];
    const found = items.find((i) => i.querySelector('.dc-menu-label')?.textContent === label);
    assert.ok(found, `no entry '${label}': ${items.map((i) => i.textContent).join(' | ')}`);
    return found;
  };
  const opener = item('New');
  opener.dispatchEvent(new dom.window.MouseEvent('mouseenter', { bubbles: true }));
  opener.click();
  item('Blank Page').click();
}

describe("the app on a page Python serves, from its start (the S4 audit's 1)", () => {
  it('a page of no grid yet is followed as Python moves it: only New ▸ Blank Page leaves it', async () => {
    const editor = new Editor();
    const app = await opened(editor);
    assert.equal(app.unwatched(), false, 'still watched: a start that opened nothing leaves nothing');
    assert.doesNotMatch(app.text(), /a page of this tab's own/);
    app.moved(2);
    await settle();
    assert.deepEqual(editor.changesAsked, [1], 'its move followed, by the commands since the version shown');
  });

  it('the editor is told, as it is asked, the version on screen: the copies it reads stay until it moves past them (Z17)', async () => {
    const editor = new Editor();
    const app = await opened(editor);
    assert.equal(app.shown(), 1, 'opened at version 1');
    app.moved(2);
    await settle();
    assert.equal(app.shown(), 2, 'followed to version 2');
    blankPage();
    assert.equal(app.shown(), undefined, 'left: no page of the editor\'s on screen');
  });

  it('a first read that failed is tried again at the next move', async () => {
    const editor = new Editor();
    const read = editor.read;
    editor.read = async () => {
      editor.reads += 1;
      throw new Error('the engine is not answering yet');
    };
    const app = await opened(editor);
    assert.equal(editor.reads, 1);
    assert.equal(app.unwatched(), false);
    editor.read = read;
    app.moved(1);
    await settle();
    assert.equal(editor.reads, 2, 'read again');
  });
});

describe("New ▸ Blank Page while a follow is on its way (the S4 audit's 4)", () => {
  it('its commands, answered once the page is left, land nothing: the page is the tab\'s own, said', async () => {
    const editor = new Editor();
    const asked = held<Awaited<ReturnType<LivePage['changes']>>>();
    editor.changes = (since) => {
      editor.changesAsked.push(since);
      return asked.promise;
    };
    const app = await opened(editor);
    app.moved(2);
    await settle();
    assert.deepEqual(editor.changesAsked, [1], 'asked what moved it');
    blankPage();
    assert.equal(app.unwatched(), true, 'left: watched no more');
    // Python added a sheet: its command is Python's page's, never the tab's own
    asked.resolve({ version: 2, commands: [{ page: { kind: 'addSheet', id: 'sheet-2', name: 'Added in Python' } }] });
    await settle();
    await settle();
    assert.match(app.text(), /a page of this tab's own: the page Python serves is left as it was/);
    assert.doesNotMatch(app.text(), /not following the page|Added in Python/);
    assert.equal(editor.reads, 1, 'the page Python serves never opened again over the tab\'s own');
  });

  it('the page Python serves, read once the page is left, is not opened over the tab\'s own', async () => {
    const editor = new Editor();
    const app = await opened(editor);
    // Python's move says its page (no commands since): it is read again, and the read is on its way
    const reading = held<Awaited<ReturnType<LivePage['read']>>>();
    editor.changes = async (since) => {
      editor.changesAsked.push(since);
      return { version: 2, page: EMPTY, held: ['sheet-1'] };
    };
    editor.read = () => {
      editor.reads += 1;
      return reading.promise;
    };
    app.moved(2);
    await settle();
    assert.equal(editor.reads, 2, 'read again');
    blankPage();
    // the page as Python has it now: a grid over a frame this tab cannot open -- opening it would say so
    const trades = (await corpus())['trades.page.json']!;
    reading.resolve({ version: 2, page: trades, held: [] });
    await settle();
    await settle();
    assert.match(app.text(), /a page of this tab's own: the page Python serves is left as it was/);
    assert.doesNotMatch(app.text(), /not following the page/);
  });

  it('the page Python serves, opening its grids once the page is left, lands nothing over the tab\'s own', async () => {
    const editor = new Editor();
    const framed = held<unknown>();
    const app = await opened(editor, () => framed.promise);
    // Python's move says its page, a grid over a frame: opening it waits for the frame
    const trades = (await corpus())['trades.page.json']!;
    editor.changes = async (since) => {
      editor.changesAsked.push(since);
      return { version: 2, page: trades, held: [] };
    };
    editor.read = async () => {
      editor.reads += 1;
      return { version: 2, page: trades, held: [] };
    };
    app.moved(2);
    await settle();
    blankPage();
    framed.resolve({ version: 0, model: '', runtime: '', source: { _type: 'packageableElementPtr', fullPath: 'trades' } });
    await settle();
    await settle();
    assert.doesNotMatch(app.text(), /Positions/, "Python's page never put over the tab's own");
    assert.match(app.text(), /a page of this tab's own: the page Python serves is left as it was/);
  });
});

describe("the ids the page has held, read with it (B12: the S4 audit's 5)", () => {
  it('none is given out by a command made here', async () => {
    const editor = new Editor();
    const trades = (await corpus())['trades.page.json']!;
    // the page as Python's keeper has it: every id it has held, sheet-9 among them (made and taken off in Python)
    editor.read = async () => {
      editor.reads += 1;
      return { version: 1, page: trades, held: ['chart', 'chart-2', 'grid', 'grid-2', 'sheet-1', 'sheet-2', 'sheet-9'] };
    };
    const written: unknown[] = [];
    editor.report = async (at, write) => {
      written.push(write);
      return { version: at + 1, combined: false };
    };
    await opened(editor, async () => ({ version: 0, model: '', runtime: '', source: { _type: 'packageableElementPtr', fullPath: 'trades' } }));
    await settle();
    (dom.window.document.querySelector('.dc-sheet-add') as HTMLElement).click();
    // written once the change settles (live-channel.ts WRITE_AFTER)
    await new Promise((done) => { setTimeout(done, 700); });
    const added = written.flatMap((w) => ((w as { edits?: { page?: { kind: string; id?: string } }[] }).edits ?? []))
      .flatMap((e) => (e.page?.kind === 'addSheet' ? [e.page.id] : []));
    assert.deepEqual(added, ['sheet-10'], 'past every id the page has held: sheet-3 would be one Python holds as sheet-9 is not');
  });
});

