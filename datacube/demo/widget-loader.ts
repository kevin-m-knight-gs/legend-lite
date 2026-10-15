// A NOTEBOOK'S CUBE, ITS SCRIPT: anywidget's module for `legend_lite.notebook.DataCube`
// (docs/DATACUBE_PYTHON_SHOW_DESIGN_2026_10_08.md, "In a notebook"). Small on purpose: anywidget sends a widget's
// script with every widget and imports it afresh each time, and DataCube is about 1.3 MB. So this loader carries two
// things only:
//   - the widget's channel as a fetch: each call the cube makes travels to the kernel as a message, `{kind: 'call',
//     id, method, path, query, body}`, and its answer comes back as `{kind: 'answer', id, status, type, headers}` with
//     the body as one binary buffer (Arrow stays binary). No HTTP, no port: the notebook's own authenticated channel,
//     so the cube works wherever the notebook does (this machine, a remote JupyterHub, VS Code, Colab);
//   - DataCube's module (widget.ts: `widget.js`, and its styles `widget.css`), fetched over that channel ONCE per
//     notebook page, kept by its hash (the widget's `_module`), and reused by every later cube on the page; its styles
//     adopted by the page and by the shadow root a cube is in (marimo's). Its lazy chunks (`widget-chunks/`: a chart's
//     code and ECharts, the saved pages' windows) are fetched the same way when the cube first needs each, over the
//     channel of any cube still open on the page.

/** The part of anywidget's model a cube uses (anywidget's AFM: https://anywidget.dev/en/afm/). */
export interface WidgetModel {
  get(name: string): unknown;
  on(event: string, callback: (...args: never[]) => void): void;
  off(event: string, callback: (...args: never[]) => void): void;
  send(content: unknown, callbacks?: unknown, buffers?: ArrayBuffer[] | ArrayBufferView[]): void;
}

/** DataCube's module (widget.ts): a cube in `el`, its calls over `fetch`; what it returns takes it down. */
export interface CubeModule {
  render(model: WidgetModel, el: HTMLElement, fetch: typeof globalThis.fetch): () => void;
}

/** What the cube's calls go under: a path the channel carries (`kernel/cube.json?...`), never a URL a browser fetches. */
export const BASE = 'kernel';

interface Answer {
  readonly kind: 'answer';
  readonly id: string;
  readonly status: number;
  readonly type: string;
  readonly headers: Readonly<Record<string, string>>;
}

const isAnswer = (m: unknown): m is Answer => typeof m === 'object' && m !== null && (m as { kind?: unknown }).kind === 'answer';

/** A call cut short because its view was taken down (its channel closed), not because the kernel refused it. */
export class ChannelClosed extends Error {
  constructor() {
    super('the cube was closed');
    this.name = 'ChannelClosed';
  }
}

/**
 * The widget's channel as a fetch, for one view of the widget. Every view of a model hears every answer, so a call's id
 * names its view (a random prefix) as well as its number; an answer to another view's call is not this one's.
 */
export function channel(model: WidgetModel): { readonly fetch: typeof globalThis.fetch; close(): void } {
  const view = Math.random().toString(36).slice(2);
  let next = 0;
  const waiting = new Map<string, { resolve(r: Response): void; reject(e: unknown): void }>();
  // a message's buffers, as anywidget's front end hands them: views of the ArrayBuffers its binary frames were read into
  const answered = (message: unknown, buffers?: readonly DataView<ArrayBuffer>[]): void => {
    if (!isAnswer(message)) return;
    const call = waiting.get(message.id);
    if (call === undefined) return;
    waiting.delete(message.id);
    // a status that has no body (a 204) is answered with none: a Response refuses one, even empty
    const bodiless = [101, 103, 204, 205, 304].includes(message.status);
    call.resolve(new Response(bodiless ? null : buffers?.[0] ?? null, {
      status: message.status,
      headers: { ...message.headers, 'Content-Type': message.type },
    }));
  };
  model.on('msg:custom', answered as (...args: never[]) => void);
  const fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => new Promise((resolve, reject) => {
    const url = String(input);
    if (!url.startsWith(`${BASE}/`)) {
      reject(new TypeError(`the widget's channel carries the engine's calls only, not ${url}`));
      return;
    }
    if (init?.body !== undefined && init.body !== null && typeof init.body !== 'string') {
      reject(new TypeError('a call over the widget\'s channel carries its body as text'));
      return;
    }
    const signal = init?.signal ?? undefined;
    if (signal?.aborted) {
      reject(signal.reason);
      return;
    }
    const rest = url.slice(BASE.length);
    const q = rest.indexOf('?');
    const id = `${view}:${next++}`;
    waiting.set(id, { resolve, reject });
    // a call given up on is forgotten here; the kernel's answer, when it comes, finds no one waiting
    signal?.addEventListener('abort', () => { waiting.delete(id); reject(signal.reason); }, { once: true });
    model.send({
      kind: 'call',
      id,
      method: init?.method ?? 'GET',
      path: q < 0 ? rest : rest.slice(0, q),
      query: q < 0 ? '' : rest.slice(q + 1),
      body: init?.body ?? null,
    });
  });
  return {
    fetch,
    close() {
      model.off('msg:custom', answered as (...args: never[]) => void);
      for (const call of waiting.values()) call.reject(new ChannelClosed());
      waiting.clear();
    },
  };
}

/** Where a page keeps DataCube's module, by its hash: one load per page, whichever of its notebooks asks. */
const LOADED = Symbol.for('legend-lite.datacube.module');
/** What a chunk's dynamic import calls instead (its text rewritten, below): `(hash, name)` to the chunk's module. */
const CHUNK = Symbol.for('legend-lite.datacube.chunk');
/** Where the module's lazy chunks are, beside it (datacube/BUILD.bazel's `widget`, `--chunk-names`). */
const CHUNKS = 'widget-chunks/';

/** DataCube's module, and its styles as one sheet a page or a shadow root adopts. */
export interface Loaded {
  readonly module: CubeModule;
  readonly styles: CSSStyleSheet;
}

/** One module's files on this page: the channels open to fetch them through (each view's, while it is open), each file
 *  made a module's URL once, and the module itself. */
interface Page {
  readonly channels: Set<typeof globalThis.fetch>;
  readonly files: Map<string, Promise<string>>;
  module?: Promise<Loaded> | undefined;
}

function pageOf(hash: string): Page {
  const store = globalThis as typeof globalThis & {
    [LOADED]?: Map<string, Page>;
    [CHUNK]?: (hash: string, name: string) => Promise<unknown>;
  };
  // a chunk's import, from whichever module asks: its file fetched over a channel of a cube still open on the page
  store[CHUNK] ??= (h, name) => moduleUrl(pageOf(h), h, name, []).then((url) => import(/* @vite-ignore */ url));
  const pages = (store[LOADED] ??= new Map());
  let page = pages.get(hash);
  if (page === undefined) {
    page = { channels: new Set(), files: new Map() };
    pages.set(hash, page);
  }
  return page;
}

/** DataCube's module, fetched over `fetch` the first time this page asks for this hash, and its lazy chunks when it
 *  first asks for each (a chart's code, the first time a chart draws) over the channel of any of its cubes still open.
 *  `fetch` is kept until `close` is called. A load that fails is forgotten, so the next cube tries again. */
export function loaded(hash: string, fetch: typeof globalThis.fetch): { readonly module: Promise<Loaded>; close(): void } {
  const page = pageOf(hash);
  page.channels.add(fetch);
  let module = page.module;
  if (module === undefined) {
    const made = load(page, hash);
    page.module = module = made;
    made.catch(() => { if (page.module === made) page.module = undefined; });
  }
  return { module, close: () => { page.channels.delete(fetch); } };
}

async function file(fetch: typeof globalThis.fetch, name: string): Promise<string> {
  const answer = await fetch(`${BASE}/${name}`);
  if (!answer.ok) throw new Error(`the kernel did not give DataCube's ${name}: ${answer.status} ${await answer.text()}`);
  return answer.text();
}

/** How long a channel has to answer for a file before the next open one is asked: a cube of another notebook on the
 *  page may be open over a kernel that no longer answers (stopped, restarted). */
const ANSWER_MS = 30_000;

/** A file over a channel still open: one closed while it answered is passed for the next, and so is one that does not
 *  answer in time; every open channel is asked once, newest first (the cube asking is the newest open, most often). */
async function fetched(page: Page, name: string): Promise<string> {
  const tried = new Set<typeof globalThis.fetch>();
  for (;;) {
    const fetch = [...page.channels].reverse().find((f) => !tried.has(f));
    if (fetch === undefined) {
      if (tried.size === 0) throw new ChannelClosed();
      throw new Error(`no cube open on this page answered for DataCube's ${name}`);
    }
    tried.add(fetch);
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      return await Promise.race([
        file(fetch, name),
        new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new TimeoutError()), ANSWER_MS); }),
      ]);
    } catch (e) {
      if (e instanceof ChannelClosed) page.channels.delete(fetch);
      else if (!(e instanceof TimeoutError)) throw e;
    } finally {
      clearTimeout(timer);
    }
  }
}

/** A channel that did not answer in time. */
class TimeoutError extends Error {}

/** A module's specifiers, as esbuild writes them: a static import (`from"./x.js"`, `import"./x.js"`), a dynamic one
 *  (`import("./x.js")`). Only those naming a chunk are touched. */
const SPECIFIER = /(\bimport\s*\(\s*|\bfrom\s*|\bimport\s*)(["'])(\.{1,2}\/[^"'\n]+?\.js)\2/g;

/**
 * What a file of the module imports, as the loader reads it: each relative specifier resolved against the file's own
 * place, and whether it is a dynamic `import()`. test/bundle-budget.test.ts holds this to esbuild's own record of each
 * output's imports (its metafile), so a form the pattern misses -- or a match that is not an import -- fails there, not
 * in a notebook.
 */
export function specifiersOf(text: string, name: string): { readonly target: string; readonly dynamic: boolean }[] {
  return [...text.matchAll(SPECIFIER)].map(([, how, , specifier]) => ({ target: resolved(name, specifier!), dynamic: how!.includes('(') }));
}

function resolved(from: string, specifier: string): string {
  const parts = from.split('/').slice(0, -1);
  for (const part of specifier.split('/')) {
    if (part === '..') parts.pop();
    else if (part !== '.') parts.push(part);
  }
  return parts.join('/');
}

/** A file of the module as a module's URL: a module imported from a blob URL resolves no relative specifier, so its
 *  chunks' are rewritten -- a static import to that chunk's own URL (made first), a dynamic one to a call of `CHUNK`. */
function moduleUrl(page: Page, hash: string, name: string, importers: readonly string[]): Promise<string> {
  let url = page.files.get(name);
  if (url === undefined) {
    const made = (async () => {
      const text = await fetched(page, name);
      const statics = new Map<string, string>();
      for (const { target, dynamic } of specifiersOf(text, name)) {
        if (!target.startsWith(CHUNKS) || dynamic || statics.has(target)) continue;
        if (importers.includes(target) || target === name) throw new Error(`DataCube's ${name} and ${target} import each other`);
        statics.set(target, await moduleUrl(page, hash, target, [...importers, name]));
      }
      const script = text.replace(SPECIFIER, (all, how: string, quote: string, specifier: string) => {
        const target = resolved(name, specifier);
        if (!target.startsWith(CHUNKS)) return all;
        return how.includes('(')
          ? `globalThis[Symbol.for(${JSON.stringify(CHUNK.description)})](${JSON.stringify(hash)},${JSON.stringify(target)}`
          : `${how}${quote}${statics.get(target)}${quote}`;
      });
      return URL.createObjectURL(new Blob([script], { type: 'text/javascript' }));
    })();
    page.files.set(name, url = made);
    made.catch(() => { if (page.files.get(name) === made) page.files.delete(name); });
  }
  return url;
}

async function load(page: Page, hash: string): Promise<Loaded> {
  const [url, css] = await Promise.all([moduleUrl(page, hash, 'widget.js', []), fetched(page, 'widget.css')]);
  const module = (await import(/* @vite-ignore */ url)) as CubeModule;
  const styles = new CSSStyleSheet();
  styles.replaceSync(css);
  return { module, styles };
}

/**
 * DataCube's styles where the cube is: the page itself (the menus and dialogs DataCube opens on the page's body) and,
 * when the cube is inside a shadow root -- marimo puts each widget in one -- that root as well, which a page's styles
 * never reach. One sheet for the page and the module, adopted once by each root. Every selector is scoped to DataCube's
 * own classes, so nothing else changes (the design, "Loading DataCube into the notebook page once").
 */
function styled(styles: CSSStyleSheet, el: HTMLElement): void {
  for (const root of new Set([document, el.getRootNode()])) {
    if ((root instanceof Document || root instanceof ShadowRoot) && !root.adoptedStyleSheets.includes(styles)) {
      root.adoptedStyleSheets = [...root.adoptedStyleSheets, styles];
    }
  }
}

/** anywidget's entry: the cube in `el` once DataCube's module is here; what it returns takes the view down. */
export default {
  render({ model, el }: { model: WidgetModel; el: HTMLElement }): () => void {
    const link = channel(model);
    let gone = false;
    let takeDown: (() => void) | undefined;
    let kept: { close(): void } | undefined;
    const open = (): void => {
      kept?.close();
      const load = kept = loaded(String(model.get('_module')), link.fetch);
      load.module.then(({ module, styles }) => {
        if (gone) return;
        styled(styles, el);
        takeDown = module.render(model, el, link.fetch);
      }, (e: unknown) => {
        if (gone) return;
        // the load another view started, cut short when that view was taken down (its cell run again, its output
        // cleared): this view loads it over its own channel (the audit of step 7, S2). Any other failure is said
        if (e instanceof ChannelClosed) open();
        else el.textContent = `DataCube did not load: ${e instanceof Error ? e.message : String(e)}`;
      });
    };
    open();
    return () => {
      gone = true;
      takeDown?.();
      kept?.close();
      link.close();
    };
  },
};
