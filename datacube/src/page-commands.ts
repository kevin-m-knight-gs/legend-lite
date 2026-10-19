// A PAGE EDIT AS A VALUE (docs/DATACUBE_COMMANDS_PAGE_2026_10_18.md; the command program's stage 1, its page half): each
// edit of a page's structure -- its sheets, its tiles, their titles, their places -- is a PageCommand, plain JSON, and
// one pure function applies it to the page's document and the ids it has held. The rules the screen kept inline and
// Python copied (the homework's §8) are here once: ids and titles, what goes with a removed tile, where a moved or new
// tile goes, names. No DOM, no planner: what needs either (a cube's document, a chart's spec) is made before the
// command is built, as a grid command's calculated column is parsed first (grid-commands.ts).

import { readChart, type ChartSpec } from './chart-spec.ts';
import { CubeDocumentError, readCube, type CubeDocument } from './cube-document.ts';
import {
  MAX_COLUMNS, NEW_SHEET, NotALayout, add, arrange, asSaved, remove, stackTiles, tiles, type Bands, type Preset,
} from './layout/bands.ts';
import {
  PageDocumentError, keptFor, readLayout, writePageOf, type ChartView, type PageDocument, type PageLayout, type PageSheet,
  type PageView,
} from './page-document.ts';
import { givenName } from './page-names.ts';
import { chartTitle, nextPageId } from './page-ids.ts';
import { namedProperties, type PageFilter } from './page-filters.ts';
import { readFilterNode } from './grid-commands.ts';
import type { PageState } from './page-state.ts';
import { CubeRefusal, withoutConditions, type FilterNode } from './snapshot.ts';

/** A page as its commands edit it: its document, and every tile and sheet id it has held -- none is given out again,
 *  so a handle a host kept never meets a new tile under its old one's id. */
export interface PageDraft {
  readonly page: PageDocument;
  readonly held: readonly string[];
}

/** One edit of a page's structure. An id left out of one that makes a tile or a sheet is allocated (nextPageId). */
export type PageCommand =
  | { readonly kind: 'renamePage'; readonly name: string }
  | { readonly kind: 'addSheet'; readonly id?: string; readonly name: string | null }
  | { readonly kind: 'renameSheet'; readonly sheet: string; readonly name: string | null }
  | { readonly kind: 'moveSheet'; readonly sheet: string; readonly to: number }
  | { readonly kind: 'removeSheet'; readonly sheet: string }
  | { readonly kind: 'renameTile'; readonly tile: string; readonly title: string | null }
  /**
   * A grid of a cube of its own (its document made first), on `sheet` -- beside `near` while that band has room. Its
   * document null: a grid whose source the page cannot write down (the screen's, over rows it cannot name), its view
   * held with no cube -- a page that cannot be saved, as the screen's Save says.
   */
  | {
    readonly kind: 'addGrid'; readonly id?: string; readonly document: CubeDocument | null; readonly sheet: string;
    readonly near: string | null; readonly title: string | null; readonly columns: number | null;
  }
  /** A chart of grid `near`'s cube (its spec made first, chart-spec.ts chartFrom), beside that grid. */
  | {
    readonly kind: 'addChart'; readonly id?: string; readonly near: string; readonly spec: ChartSpec;
    readonly title: string | null; readonly columns: number | null;
  }
  /** A chart's spec, whole (made first by chart-spec.ts editChart, the options form's rule). */
  | { readonly kind: 'setChart'; readonly chart: string; readonly spec: ChartSpec }
  /** A tile onto another sheet, at its bottom; onto its own, nothing. `columns`: how many fit side by side there. */
  | { readonly kind: 'moveTile'; readonly tile: string; readonly sheet: string; readonly columns: number | null }
  | { readonly kind: 'stack'; readonly tiles: readonly string[] }
  | { readonly kind: 'removeTile'; readonly tile: string }
  | { readonly kind: 'arrange'; readonly sheet: string; readonly preset: string; readonly order: readonly string[] | null }
  /** A sheet's layout, whole: a drag, a resize, a drop on another tile's middle -- every tile of the sheet in it once. */
  | { readonly kind: 'layout'; readonly sheet: string; readonly layout: PageLayout }
  /**
   * A page filter (page-filters.ts, docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §16.2), set: the one of its id replaced,
   * else added -- its id allocated when left out. Its class one a grid of the page reads; `from`, the chart whose click
   * made it, or null.
   */
  | {
    readonly kind: 'setPageFilter'; readonly id?: string; readonly class: string; readonly condition: FilterNode;
    readonly from: string | null;
  }
  /** A page filter taken off. */
  | { readonly kind: 'removePageFilter'; readonly filter: string };

export const PAGE_COMMAND_KINDS = [
  'renamePage', 'addSheet', 'renameSheet', 'moveSheet', 'removeSheet', 'renameTile', 'addGrid', 'addChart', 'setChart',
  'moveTile', 'stack', 'removeTile', 'arrange', 'layout', 'setPageFilter', 'removePageFilter',
] as const satisfies readonly PageCommand['kind'][];

/** What a command applied gives: the page, and the command as applied (its id allocated, when it made something). */
export interface PageApplied {
  readonly draft: PageDraft;
  readonly command: PageCommand;
}

// ---------------------------------------------------------------- ids and titles (page-ids.ts: the screen's too)

export { chartTitle, gridTitle, nextPageId } from './page-ids.ts';

// ---------------------------------------------------------------- applying

const named = (part: 'page' | 'tile' | 'sheet', text: string): string | undefined => {
  const given = givenName(part, text);
  if ('refused' in given) throw new CubeRefusal(given.refused);
  return given.name;
};

const saved = (layout: Bands): PageLayout => ({ kind: 'bands', ...asSaved(layout) });

/** A layout op of bands.ts, its refusal in its words. */
function laid(op: () => Bands): PageLayout {
  try {
    return saved(op());
  } catch (e) {
    if (e instanceof NotALayout) throw new CubeRefusal(e.message);
    throw e;
  }
}

function sheetOf(page: PageDocument, tile: string): PageSheet | undefined {
  return page.sheets.find((s) => tiles(s.layout).includes(tile));
}

function sheetNamed(page: PageDocument, id: string): PageSheet {
  const sheet = page.sheets.find((s) => s.id === id);
  if (!sheet) throw new CubeRefusal(`'${id}' is not a sheet of the page (${page.sheets.map((s) => s.id).join(', ')})`);
  return sheet;
}

function viewNamed(page: PageDocument, id: string): PageView {
  const view = page.views.find((v) => v.id === id);
  if (!view) throw new CubeRefusal(`'${id}' is not a tile of the page`);
  return view;
}

const withSheet = (page: PageDocument, id: string, change: (s: PageSheet) => PageSheet): PageDocument =>
  ({ ...page, sheets: page.sheets.map((s) => (s.id === id ? change(s) : s)) });

/**
 * A fresh id for a command that makes one, or the one it names -- refused when the page has held it, or a cube of the
 * page has it (a cube kept for a detached chart has no view of its own: its id is taken all the same).
 */
function freshId(draft: PageDraft, given: string | undefined, kind: 'grid' | 'chart' | 'sheet' | 'filter'): string {
  const taken = [...draft.held, ...draft.page.cubes.map((c) => c.id)];
  if (given === undefined) return nextPageId(taken, kind);
  if (taken.includes(given)) throw new CubeRefusal(`'${given}' is an id the page has held already`);
  if (!new RegExp(`^${kind}-\\d+$`).test(given)) throw new CubeRefusal(`a ${kind}'s id is ${kind}-N, not '${given}'`);
  return given;
}

/** The page written again by its own writer (page-document.ts writePageOf): its name each cube's too, its model kept
 *  for the cubes it holds, a chart's click filter for the charts it holds. */
function rewritten(page: PageDocument, change: Partial<Pick<PageDocument, 'name' | 'cubes' | 'views' | 'sheets' | 'filters'>>): PageDocument {
  const next = { ...page, ...change };
  return writePageOf({
    name: next.name, cubes: next.cubes, views: { views: next.views, sheets: next.sheets },
    ...(next.model ? { model: next.model } : {}), ...(next.filters ? { filters: next.filters } : {}),
    ...(next.unknown ? { unknown: next.unknown } : {}),
  });
}

/**
 * A PAGE FILTER as `setPageFilter` sets it, or why not: its class one a grid of the page reads (a class source), every
 * property it names one that class's grids have, its chart one of the page's over that class.
 */
function pageFilter(page: PageDocument, id: string, command: Extract<PageCommand, { kind: 'setPageFilter' }>): PageFilter {
  const sources = page.cubes.map((c) => c.cube.source).filter((s) => s._type === 'class' && s.class === command.class);
  const name = command.class.slice(command.class.lastIndexOf(':') + 1);
  if (sources.length === 0) throw new CubeRefusal(`no grid of the page reads ${command.class}: a page filter is on a class a grid reads`);
  const has = new Set(sources.flatMap((s) => s.columns.map((c) => c.name)));
  const missing = [...namedProperties(command.condition)].filter((p) => !has.has(p));
  if (missing.length > 0) {
    throw new CubeRefusal(`${name} has no ${missing.join(', ')} (its grids show ${[...has].join(', ')})`);
  }
  if (command.from !== null) {
    const chart = page.views.find((v) => v.id === command.from);
    if (chart?.kind !== 'chart') throw new CubeRefusal(`'${command.from}' is no chart of the page`);
    const over = page.cubes.find((c) => c.id === chart.cube)?.cube.source;
    if (over?._type !== 'class' || over.class !== command.class) throw new CubeRefusal(`'${command.from}' is no chart of a grid over ${name}`);
  }
  return { id, class: command.class, condition: command.condition, ...(command.from !== null ? { from: command.from } : {}) };
}

/** A chart's selection taken off its cube's filter (the screen's #select(id, null)), and off the chart. */
function unselected(page: PageDocument, chart: ChartView): PageDocument {
  const conditions: readonly FilterNode[] = chart.selection ?? [];
  if (conditions.length === 0) return page;
  const cubes = page.cubes.map((c) => {
    if (c.id !== chart.cube) return c;
    const { filter, ...query } = c.cube.query;
    const left = withoutConditions(filter, conditions);
    return { ...c, cube: { ...c.cube, query: left ? { ...query, filter: left } : query } };
  });
  const { selection: _was, ...rest } = chart;
  return { ...page, cubes, views: page.views.map((v) => (v.id === chart.id ? rest : v)) };
}

/**
 * A TILE OFF THE PAGE, as the screen takes one: a grid takes the charts following it, and each frozen one stays,
 * detached -- reading the grid's cube as it was, kept for it -- with no selection of its own (the screen clears it and
 * leaves the kept cube's filter as the grid had it); a chart takes its selection off its grid's filter (the screen's
 * #select(id, null)). A cube stays while a view reads it; the page's model keeps what its cubes still need (keptFor).
 */
function withoutTile(page: PageDocument, tile: string): PageDocument {
  const view = viewNamed(page, tile);
  let next = page;
  const gone = new Set([tile]);
  if (view.kind === 'chart') {
    next = unselected(next, view);
  } else {
    for (const chart of next.views.filter((v): v is ChartView => v.kind === 'chart' && v.cube === view.cube)) {
      if (!chart.spec.frozen) {
        gone.add(chart.id);
      } else if (chart.selection) {
        const { selection: _cleared, ...detached } = chart;
        next = { ...next, views: next.views.map((v) => (v.id === chart.id ? detached : v)) };
      }
    }
  }
  const views = next.views.filter((v) => !gone.has(v.id));
  const cubes = next.cubes.filter((c) => views.some((v) => v.cube === c.id));
  const sheets = next.sheets.map((s) => {
    let layout: Bands = s.layout;
    for (const id of gone) if (tiles(layout).includes(id)) layout = remove(layout, id);
    return layout === s.layout ? s : { ...s, layout: saved(layout) };
  });
  return rewritten({ ...next, ...(next.model ? { model: keptFor(next.model, cubes) } : {}) }, { views, cubes, sheets });
}

/**
 * The views and cubes in the order the screen writes them (page/cube-page.ts views(), page-app.ts document()): the grids
 * in the sheets' reading order -- the order a reopened page adds them in -- then the charts, each in the order it came;
 * the cubes of the grids in their order, then those kept for detached charts. A page Python edits is written as the
 * screen writes it.
 */
function inScreenOrder(page: PageDocument): PageDocument {
  const reading = page.sheets.flatMap((s) => tiles(s.layout));
  const at = (id: string): number => (reading.includes(id) ? reading.indexOf(id) : reading.length);
  const grids = page.views.filter((v) => v.kind === 'grid');
  const views = [
    ...grids.map((v, i) => ({ v, i })).sort((x, y) => at(x.v.id) - at(y.v.id) || x.i - y.i).map((x) => x.v),
    ...page.views.filter((v) => v.kind === 'chart'),
  ];
  const gridOrder = views.filter((v) => v.kind === 'grid').map((v) => v.cube);
  const place = (id: string): number => (gridOrder.includes(id) ? gridOrder.indexOf(id) : gridOrder.length);
  const cubes = page.cubes.map((c, i) => ({ c, i })).sort((x, y) => place(x.c.id) - place(y.c.id) || x.i - y.i).map((x) => x.c);
  const same = views.every((v, i) => v === page.views[i]) && cubes.every((c, i) => c === page.cubes[i]);
  return same ? page : { ...page, views, cubes };
}

/** A command applied: the page as the screen's rules leave it, or a refusal in their words (CubeRefusal). */
export function applyPageCommand(draft: PageDraft, command: PageCommand): PageApplied {
  const page = draft.page;
  const done = (next: PageDocument, held: readonly string[] = draft.held, applied: PageCommand = command): PageApplied =>
    ({ draft: { page: inScreenOrder(next), held }, command: applied });
  switch (command.kind) {
    case 'renamePage': {
      const name = named('page', command.name);
      if (name === undefined) throw new Error("a page's name is never left to what it shows");
      return done(rewritten(page, { name }));
    }
    case 'addSheet': {
      const id = freshId(draft, command.id, 'sheet');
      const name = command.name === null ? undefined : named('sheet', command.name);
      const sheet: PageSheet = { id, ...(name !== undefined ? { name } : {}), layout: saved(NEW_SHEET) };
      return done({ ...page, sheets: [...page.sheets, sheet] }, [...draft.held, id], { ...command, id });
    }
    case 'renameSheet': {
      sheetNamed(page, command.sheet);
      const name = command.name === null ? undefined : named('sheet', command.name);
      return done(withSheet(page, command.sheet, ({ name: _was, ...s }) => (name !== undefined ? { ...s, name } : s)));
    }
    case 'moveSheet': {
      const sheet = sheetNamed(page, command.sheet);
      if (!Number.isInteger(command.to)) throw new CubeRefusal(`a sheet's place is a whole number, not ${command.to}`);
      const others = page.sheets.filter((s) => s !== sheet);
      const at = Math.max(0, Math.min(others.length, command.to));
      return done({ ...page, sheets: [...others.slice(0, at), sheet, ...others.slice(at)] });
    }
    case 'removeSheet': {
      const sheet = sheetNamed(page, command.sheet);
      if (page.sheets.length === 1) throw new CubeRefusal('a page has one sheet at least: its last is not removed');
      // its grids first: a chart of one, on this sheet or another, goes with it or stays detached
      let next = page;
      const on = (): string[] => tiles(sheetNamed(next, sheet.id).layout);
      for (const tile of on().filter((t) => viewNamed(next, t).kind === 'grid')) next = withoutTile(next, tile);
      for (const tile of on()) next = withoutTile(next, tile);
      return done(rewritten(next, { sheets: next.sheets.filter((s) => s.id !== sheet.id) }));
    }
    case 'renameTile': {
      const view = viewNamed(page, command.tile);
      if (command.title === null && view.kind === 'chart') throw new CubeRefusal('a chart has a title');
      const title = command.title === null ? undefined : named('tile', command.title);
      const views = page.views.map((v) => {
        if (v.id !== view.id) return v;
        if (title !== undefined) return { ...v, title };
        const { title: _was, ...rest } = v as PageView & { title?: string };
        return rest as PageView;
      });
      return done({ ...page, views });
    }
    case 'addGrid': {
      const id = freshId(draft, command.id, 'grid');
      const sheet = sheetNamed(page, command.sheet);
      const title = command.title === null ? undefined : named('tile', command.title);
      const near = command.near !== null && tiles(sheet.layout).includes(command.near) ? command.near : undefined;
      const view: PageView = { id, kind: 'grid', cube: id, ...(title !== undefined ? { title } : {}) };
      const placed = withSheet(page, sheet.id, (s) => ({ ...s, layout: laid(() => add(s.layout, id, near, command.columns ?? MAX_COLUMNS)) }));
      const cubes = command.document === null ? page.cubes : [...page.cubes, { id, cube: command.document }];
      return done(rewritten(placed, { cubes, views: [...page.views, view] }),
        [...draft.held, id], { ...command, id });
    }
    case 'addChart': {
      const grid = viewNamed(page, command.near);
      if (grid.kind !== 'grid') throw new CubeRefusal(`'${command.near}' is a chart: a chart is of a grid`);
      const spec = readChart(JSON.parse(JSON.stringify(command.spec)));
      if (typeof spec === 'string') throw new CubeRefusal(spec);
      const id = freshId(draft, command.id, 'chart');
      const title = command.title === null ? chartTitle(id) : named('tile', command.title) ?? chartTitle(id);
      const view: ChartView = { id, kind: 'chart', cube: grid.cube, title, spec };
      const sheet = sheetOf(page, grid.id);
      if (!sheet) throw new CubeRefusal(`'${grid.id}' is on no sheet`);
      const placed = withSheet(page, sheet.id, (s) => ({ ...s, layout: laid(() => add(s.layout, id, grid.id, command.columns ?? MAX_COLUMNS)) }));
      return done({ ...placed, views: [...page.views, view] }, [...draft.held, id], { ...command, id });
    }
    case 'setChart': {
      const view = viewNamed(page, command.chart);
      if (view.kind !== 'chart') throw new CubeRefusal(`'${command.chart}' is a grid, not a chart`);
      const spec = readChart(JSON.parse(JSON.stringify(command.spec)));
      if (typeof spec === 'string') throw new CubeRefusal(spec);
      return done({ ...page, views: page.views.map((v) => (v.id === view.id ? { ...view, spec } : v)) });
    }
    case 'moveTile': {
      viewNamed(page, command.tile);
      const from = sheetOf(page, command.tile);
      const to = sheetNamed(page, command.sheet);
      // onto its own sheet: nothing, as the screen's Move to Sheet offers only the others
      if (!from || from.id === to.id) return done(page);
      const out = withSheet(page, from.id, (s) => ({ ...s, layout: laid(() => remove(s.layout, command.tile)) }));
      return done(withSheet(out, to.id, (s) => ({ ...s, layout: laid(() => add(s.layout, command.tile, undefined, command.columns ?? MAX_COLUMNS)) })));
    }
    case 'stack': {
      if (new Set(command.tiles).size < 2) throw new CubeRefusal('a stack is two tiles or more');
      for (const t of command.tiles) viewNamed(page, t);
      const sheet = sheetOf(page, command.tiles[0]!);
      if (!sheet || command.tiles.some((t) => sheetOf(page, t) !== sheet)) {
        throw new CubeRefusal('the tiles of a stack are on one sheet (move them there first)');
      }
      return done(withSheet(page, sheet.id, (s) => ({ ...s, layout: laid(() => stackTiles(s.layout, command.tiles)) })));
    }
    case 'removeTile':
      return done(withoutTile(page, command.tile));
    case 'arrange': {
      const sheet = sheetNamed(page, command.sheet);
      const order = command.order;
      return done(withSheet(page, sheet.id, (s) => ({
        ...s, layout: laid(() => (order === null ? arrange(s.layout, command.preset as Preset) : arrange(s.layout, command.preset as Preset, order))),
      })));
    }
    case 'layout': {
      const sheet = sheetNamed(page, command.sheet);
      // read as a saved page's sheet is (page-document.ts readLayout): its shape, its tiles views of the page, its bands'
      // rules -- a layout the page's reader would refuse never reaches the page
      let read: PageLayout;
      try {
        read = readLayout(command.layout, new Set(page.views.map((v) => v.id)), 'the layout');
      } catch (e) {
        if (e instanceof PageDocumentError) throw new CubeRefusal(e.message);
        throw e;
      }
      const { kind: _kind, ...bands } = read;
      const had = [...tiles(sheet.layout)].sort();
      const has = [...tiles(bands)].sort();
      if (JSON.stringify(had) !== JSON.stringify(has)) {
        throw new CubeRefusal(`a sheet's layout holds its tiles, each once: ${had.join(', ') || 'none'}, not ${has.join(', ') || 'none'}`);
      }
      return done(withSheet(page, sheet.id, (s) => ({ ...s, layout: saved(bands) })));
    }
    case 'setPageFilter': {
      const filters = page.filters ?? [];
      const was = command.id !== undefined ? filters.find((f) => f.id === command.id) : undefined;
      // its own id again replaces it; another given is a fresh one, as a tile's is
      const id = was ? was.id : freshId(draft, command.id, 'filter');
      const filter = pageFilter(page, id, command);
      const next = was ? filters.map((f) => (f.id === id ? filter : f)) : [...filters, filter];
      return done(rewritten(page, { filters: next }), was ? draft.held : [...draft.held, id], { ...command, id });
    }
    case 'removePageFilter': {
      const filters = page.filters ?? [];
      if (!filters.some((f) => f.id === command.filter)) {
        throw new CubeRefusal(`'${command.filter}' is not a filter of the page${filters.length ? ` (${filters.map((f) => f.id).join(', ')})` : ''}`);
      }
      const left = filters.filter((f) => f.id !== command.filter);
      const { filters: _all, ...rest } = page;
      return done(rewritten(left.length > 0 ? { ...rest, filters: left } : rest, {}));
    }
  }
}

/**
 * Commands applied to a page's state (page-state.ts) as one step named `label` -- undo takes them all back -- the
 * commands as applied returned; a refusal changes nothing.
 */
export function applyToState(state: PageState, commands: readonly PageCommand[], label: string): PageCommand[] {
  const { draft, applied } = applyPageCommands(state.draft, commands);
  state.begin(label);
  state.keep(draft);
  return applied;
}

/** Commands applied in order, each as applied: the first refusal stops them, in its words. */
export function applyPageCommands(draft: PageDraft, commands: readonly PageCommand[]): { draft: PageDraft; applied: PageCommand[] } {
  let d = draft;
  const applied: PageCommand[] = [];
  for (const c of commands) {
    const one = applyPageCommand(d, c);
    d = one.draft;
    applied.push(one.command);
  }
  return { draft: d, applied };
}

/**
 * Every id a page holds now: its tiles', its sheets' and its cubes' -- a cube kept for a detached chart has no view, and
 * its id is taken (a draft's `held` starts from these, and grows).
 */
export function idsOf(page: PageDocument): string[] {
  return [...new Set([...page.views.map((v) => v.id), ...page.cubes.map((c) => c.id), ...page.sheets.map((s) => s.id)])];
}

// ---------------------------------------------------------------- reading a command from JSON

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

function fields(o: Record<string, unknown>, kind: string, allowed: readonly string[]): void {
  const odd = Object.keys(o).filter((k) => k !== 'kind' && !allowed.includes(k));
  if (odd.length > 0) throw new CubeRefusal(`a '${kind}' command takes ${allowed.join(', ')}, not ${odd.join(', ')}`);
}

function text(v: unknown, what: string): string {
  if (typeof v !== 'string') throw new CubeRefusal(`${what} is text, not ${JSON.stringify(v)}`);
  return v;
}

const textOrNull = (v: unknown, what: string): string | null => (v === null ? null : text(v, what));

const optionalId = (v: unknown, what: string): { id?: string } => (v === undefined ? {} : { id: text(v, what) });

/**
 * A grid's cube, read by its own reader (cube-document.ts readCube): what it refuses, refused in its words. Never null
 * from JSON: a grid whose source the page cannot write down is the screen's own, and a page holding one does not read
 * back (Python's page is always written down).
 */
function cubeOf(v: unknown): CubeDocument {
  if (!isObject(v)) throw new CubeRefusal("a grid's cube is its document");
  try {
    return readCube(v);
  } catch (e) {
    if (e instanceof CubeDocumentError) throw new CubeRefusal(e.message);
    throw e;
  }
}

function columnsFrom(v: unknown): number | null {
  if (v === null) return null;
  if (typeof v !== 'number' || !Number.isInteger(v) || v < 1) throw new CubeRefusal(`columns is a whole number, one or more, or null, not ${JSON.stringify(v)}`);
  return v;
}

/**
 * A page command read from JSON (Python's, a live channel's): its kind one this module applies, its fields exactly those
 * the kind takes, each of its type -- a grid's cube read by its own reader -- or refused in words. What a field NAMES (a
 * tile the page has, a chart's spec, a layout's tiles) is the apply's to judge.
 */
export function readPageCommand(given: unknown): PageCommand {
  if (!isObject(given) || typeof given['kind'] !== 'string') {
    throw new CubeRefusal(`a page's command is {kind, ...}, its kind one of ${PAGE_COMMAND_KINDS.join(', ')}, not ${JSON.stringify(given)}`);
  }
  const o = given;
  const kind = o['kind'] as string;
  switch (kind) {
    case 'renamePage':
      fields(o, kind, ['name']);
      return { kind, name: text(o['name'], "a page's name") };
    case 'addSheet':
      fields(o, kind, ['id', 'name']);
      return { kind, ...optionalId(o['id'], "a sheet's id"), name: textOrNull(o['name'], "a sheet's name") };
    case 'renameSheet':
      fields(o, kind, ['sheet', 'name']);
      return { kind, sheet: text(o['sheet'], 'a sheet'), name: textOrNull(o['name'], "a sheet's name") };
    case 'moveSheet':
      fields(o, kind, ['sheet', 'to']);
      if (typeof o['to'] !== 'number') throw new CubeRefusal(`a sheet's place is a number, not ${JSON.stringify(o['to'])}`);
      return { kind, sheet: text(o['sheet'], 'a sheet'), to: o['to'] };
    case 'removeSheet':
      fields(o, kind, ['sheet']);
      return { kind, sheet: text(o['sheet'], 'a sheet') };
    case 'renameTile':
      fields(o, kind, ['tile', 'title']);
      return { kind, tile: text(o['tile'], 'a tile'), title: textOrNull(o['title'], "a tile's title") };
    case 'addGrid':
      fields(o, kind, ['id', 'document', 'sheet', 'near', 'title', 'columns']);
      return {
        kind, ...optionalId(o['id'], "a grid's id"), document: cubeOf(o['document']),
        sheet: text(o['sheet'], 'a sheet'), near: textOrNull(o['near'], 'a tile'), title: textOrNull(o['title'], "a tile's title"),
        columns: columnsFrom(o['columns']),
      };
    case 'addChart':
      fields(o, kind, ['id', 'near', 'spec', 'title', 'columns']);
      if (!isObject(o['spec'])) throw new CubeRefusal("a chart's spec is an object");
      return {
        kind, ...optionalId(o['id'], "a chart's id"), near: text(o['near'], 'a grid'), spec: o['spec'] as unknown as ChartSpec,
        title: textOrNull(o['title'], "a tile's title"), columns: columnsFrom(o['columns']),
      };
    case 'setChart':
      fields(o, kind, ['chart', 'spec']);
      if (!isObject(o['spec'])) throw new CubeRefusal("a chart's spec is an object");
      return { kind, chart: text(o['chart'], 'a chart'), spec: o['spec'] as unknown as ChartSpec };
    case 'moveTile':
      fields(o, kind, ['tile', 'sheet', 'columns']);
      return { kind, tile: text(o['tile'], 'a tile'), sheet: text(o['sheet'], 'a sheet'), columns: columnsFrom(o['columns']) };
    case 'stack':
      fields(o, kind, ['tiles']);
      if (!Array.isArray(o['tiles'])) throw new CubeRefusal('a stack is a list of tiles');
      return { kind, tiles: o['tiles'].map((t) => text(t, 'a tile')) };
    case 'removeTile':
      fields(o, kind, ['tile']);
      return { kind, tile: text(o['tile'], 'a tile') };
    case 'arrange': {
      fields(o, kind, ['sheet', 'preset', 'order']);
      const order = o['order'];
      if (order !== null && !Array.isArray(order)) throw new CubeRefusal('an arrangement\'s order is a list of tiles, or null');
      return {
        kind, sheet: text(o['sheet'], 'a sheet'), preset: text(o['preset'], "the layout picker's shape"),
        order: order === null ? null : order.map((t) => text(t, 'a tile')),
      };
    }
    case 'layout':
      fields(o, kind, ['sheet', 'layout']);
      if (!isObject(o['layout'])) throw new CubeRefusal("a sheet's layout is {kind: 'bands', fit, bands}");
      return { kind, sheet: text(o['sheet'], 'a sheet'), layout: o['layout'] as unknown as PageLayout };
    case 'setPageFilter':
      fields(o, kind, ['id', 'class', 'condition', 'from']);
      return {
        kind, ...optionalId(o['id'], "a page filter's id"), class: text(o['class'], "a page filter's class"),
        condition: readFilterNode(o['condition']), from: textOrNull(o['from'], 'the chart a page filter came from'),
      };
    case 'removePageFilter':
      fields(o, kind, ['filter']);
      return { kind, filter: text(o['filter'], 'a page filter') };
    default:
      throw new CubeRefusal(`'${kind}' is not a page's command: ${PAGE_COMMAND_KINDS.join(', ')}`);
  }
}
