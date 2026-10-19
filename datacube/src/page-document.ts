// A saved page: a cube and the views arranged around it, as one document.
//
// The page WRAPS the cube's own document, by value (cube-document.ts is unchanged,
// and a page holds a cube the way a folder holds a file): the cube is saved and
// reopened exactly as a cube alone is, source reconciled and all. Around it the page
// keeps what only a page has:
//
//   views    what is shown: the cube's grid, and each chart of it -- its title, its
//            spec (plain JSON, ours: chart-spec.ts), frozen or live, and the mark it
//            is filtering the cube to (the conditions are in the cube's filter; the
//            view names them, so the chart can take them off again)
//   sheets   where each view sits: the page's sheets, in order (docs/DATACUBE_PAGES_DESIGN_2026_10_09.md
//            §7.3), each a whole screen with its own bands (layout/bands.ts) -- each band a tree
//            of rows and columns with shares, down to the views' tiles -- and whether it fits
//            its window or scrolls; every view on one sheet.
//
// ONE thing is saved, always the page (user ruling, 2026-09-28): a cube with no charts
// is a page of its grid alone; a cube saved bare before that still opens, as a page of
// its grid (`readSaved`). The same three rules as the cube's document: VERSIONED (a page of
// another version is refused by name: no older one is read, the user, 2026-10-17), UNKNOWN
// FIELDS KEPT (written back verbatim), and a page that cannot be read fails LOUDLY.
//
// A page holds a cube per grid (a page of its own) or one (a cube alone's page); every view
// names its cube.

import { ExactNumber, fromJson, toJson as protocolJson } from '../../pure-protocol/src/index.ts';
import { readChart, type ChartSpec } from './chart-spec.ts';
import { CUBE_KIND, cubeToJson, definitionText, readCube, type CubeDocument } from './cube-document.ts';
import { type Bands, type Node, NEW_SHEET, add as addTile, problems, tiles as tilesOf } from './layout/bands.ts';
import type { PageModelRef } from './page-model.ts';
import { givenName, type NamedPart } from './page-names.ts';
import type { FilterNode } from './snapshot.ts';

export const PAGE_KIND = 'datacube.page';
/**
 * 4: the page's model, where its classes are (2026-10-10, docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §15.3). 3: sheets,
 * each its own bands (2026-10-09, docs/DATACUBE_PAGES_DESIGN_2026_10_09.md §7.3). 2: one layout, as bands. 1: tiles on
 * a 12-column grid. Only 4 is read.
 */
export const PAGE_VERSION = 4;
/** The sheet a page of one sheet has: a new page's, and a cube alone's. */
export const FIRST_SHEET = 'sheet-1';

export interface GridView {
  readonly id: string;
  readonly kind: 'grid';
  /** The cube it shows (an id in `PageDocument.cubes`). */
  readonly cube: string;
  readonly title?: string;
}

export interface ChartView {
  readonly id: string;
  readonly kind: 'chart';
  readonly cube: string;
  readonly title: string;
  readonly spec: ChartSpec;
  /** The conditions this chart's last click put on its cube's filter, if any. */
  readonly selection?: readonly FilterNode[];
}

export type PageView = GridView | ChartView;

/** Where the views sit: the page's bands (layout/bands.ts), each tile a view's id. */
export interface PageLayout extends Bands {
  readonly kind: 'bands';
}

/** A sheet: a whole screen of the page's views, laid out as its bands. */
export interface PageSheet {
  readonly id: string;
  /** Its name, when someone gave it one (else it is named after what it shows). */
  readonly name?: string;
  readonly layout: PageLayout;
}

/** What a cube app shows around its cube: its views, and the sheets they are laid out on (each view on one). */
export interface PageViews {
  readonly views: readonly PageView[];
  readonly sheets: readonly PageSheet[];
}

/** A page of one sheet, laid out as `layout`. */
export const oneSheet = (layout: PageLayout): readonly PageSheet[] => [{ id: FIRST_SHEET, layout }];

/**
 * THE VIEWS A PAGE STARTS WITH, on its one sheet: a new sheet's empty board (bands.ts NEW_SHEET), or with a grid
 * (`grid`: its view's id and its cube's) added to it as any first tile is (bands.ts add: the whole board). The one
 * writer of a starting page: a cube app's page of its grid alone (app.ts pageViews), a cube saved before pages opened as
 * a page (app/datacube-app.ts), and Python's new page (headless page.ts pageNew), whose first grid the same add places.
 */
export function startingViews(grid?: { readonly id: string; readonly cube: string }): PageViews {
  return {
    views: grid ? [{ id: grid.id, kind: 'grid', cube: grid.cube }] : [],
    sheets: oneSheet({ kind: 'bands', ...(grid ? addTile(NEW_SHEET, grid.id) : NEW_SHEET) }),
  };
}

export interface PageDocument extends PageViews {
  readonly kind: typeof PAGE_KIND;
  readonly version: number;
  readonly name: string;
  readonly cubes: readonly { readonly id: string; readonly cube: CubeDocument }[];
  /** Where the page's classes are (Create a Class…): its project in the model home; none until its first class. */
  readonly model?: PageModelRef;
  /** Top-level fields a newer writer added, kept verbatim. */
  readonly unknown?: Readonly<Record<string, unknown>>;
}

/** Whether a saved name holds by givenName's rule (page-names.ts): text, and for a page or a tile not blank. */
const named = (part: NamedPart, value: unknown): value is string =>
  typeof value === 'string' && !('refused' in givenName(part, value));

export class PageDocumentError extends Error {
  constructor(detail: string) {
    super(`cannot read saved page: ${detail}`);
    this.name = 'PageDocumentError';
  }
}

/** A page with no cube: DataCube opens none -- its own refusal, by name, so a host can say what makes one. */
export class PageNoCubeError extends PageDocumentError {
  constructor() {
    super('it has no cube');
    this.name = 'PageNoCubeError';
  }
}

const KNOWN = new Set(['kind', 'version', 'name', 'cubes', 'views', 'sheets', 'model']);

/** The one cube of a step-1 page. */
export const PAGE_CUBE = 'cube';

// ---------------------------------------------------------------- writing

export function writePage(o: {
  readonly name: string;
  readonly cube: CubeDocument;
  readonly views: PageViews;
  readonly model?: PageModelRef;
  readonly unknown?: Readonly<Record<string, unknown>>;
}): PageDocument {
  return {
    kind: PAGE_KIND,
    version: PAGE_VERSION,
    name: o.name,
    // the page's name is the cube's too: one name, whichever is opened
    cubes: [{ id: PAGE_CUBE, cube: { ...o.cube, name: o.name } }],
    views: o.views.views,
    sheets: o.views.sheets,
    ...(o.model ? { model: keptFor(o.model, [{ id: PAGE_CUBE }]) } : {}),
    ...(o.unknown && Object.keys(o.unknown).length > 0 ? { unknown: o.unknown } : {}),
  };
}

/**
 * A PAGE OF SEVERAL CUBES (a page of its own: one cube per grid, each over its own source, and one per grid kept off
 * the board for a detached chart). Each view names its cube by id; a cube no grid view names is a detached chart's.
 */
export function writePageOf(o: {
  readonly name: string;
  readonly cubes: readonly { readonly id: string; readonly cube: CubeDocument }[];
  readonly views: PageViews;
  readonly model?: PageModelRef;
  readonly unknown?: Readonly<Record<string, unknown>>;
}): PageDocument {
  return {
    kind: PAGE_KIND,
    version: PAGE_VERSION,
    name: o.name,
    // the page's name is each cube's too: one name, whichever is opened
    cubes: o.cubes.map((c) => ({ id: c.id, cube: { ...c.cube, name: o.name } })),
    views: o.views.views,
    sheets: o.views.sheets,
    ...(o.model ? { model: keptFor(o.model, o.cubes) } : {}),
    ...(o.unknown && Object.keys(o.unknown).length > 0 ? { unknown: o.unknown } : {}),
  };
}

/**
 * The page's model as the page writes it: its tables and classes kept for the cubes the page holds, and only those --
 * a grid removed since its class was made takes its table's name and its class with it, as the reader holds every
 * kept name to a cube of the page (the audit's B1: a page written otherwise would never open again). Python's page asks
 * it too, through the page command that removes a tile (page-commands.ts removeTile, headless pageApply): the one rule.
 */
export function keptFor(model: PageModelRef, cubes: readonly { readonly id: string }[]): PageModelRef {
  const ids = new Set(cubes.map((c) => c.id));
  const tables: Record<string, string> = {};
  for (const [cube, table] of Object.entries(model.tables)) if (ids.has(cube)) tables[cube] = table;
  const classes: Record<string, string> = {};
  for (const [cube, cls] of Object.entries(model.classes ?? {})) if (ids.has(cube)) classes[cube] = cls;
  // its classes likewise: none kept, none written -- the model's own would keep the class of a grid removed, which the
  // reader refuses as it refuses a table's
  const { classes: _was, ...rest } = model;
  return { ...rest, tables, ...(Object.keys(classes).length > 0 ? { classes } : {}) };
}

/** The page as a JSON-ready object: each cube as its own document writes itself. */
export function pageContent(page: PageDocument): Record<string, unknown> {
  const { unknown, cubes, ...rest } = page;
  return {
    ...unknown,
    ...rest,
    cubes: cubes.map((c) => ({ id: c.id, cube: fromJson(cubeToJson(c.cube)) })),
  };
}

/** The page as JSON text (exact numbers kept, as the cube's own document keeps them). */
export function pageToJson(page: PageDocument): string {
  return protocolJson(pageContent(page));
}

/**
 * What makes two pages "the same" for "changed since saved": each cube's definition, the
 * views and the sheets (their names, order and layouts). Not the name, not the open rows.
 */
export function pageDefinitionText(page: PageDocument): string {
  // the protocol's writer puts keys in one order: a page read back compares equal
  return protocolJson({
    cubes: page.cubes.map((c) => definitionText(c.cube)),
    views: page.views,
    sheets: page.sheets,
    // its classes' project: made, or a grid's table kept, the page has changed
    ...(page.model ? { model: page.model } : {}),
  });
}

// ---------------------------------------------------------------- reading

/** A saved document of either kind: a cube alone, or a page around one. */
export type SavedDocument =
  | { readonly kind: 'cube'; readonly cube: CubeDocument }
  | { readonly kind: 'page'; readonly page: PageDocument };

/** Read a saved cube or page, by its kind. */
export function readSaved(input: string | unknown): SavedDocument {
  const raw = typeof input === 'string' ? parse(input) : input;
  if (isObject(raw) && raw['kind'] === PAGE_KIND) return { kind: 'page', page: readPage(raw) };
  return { kind: 'cube', cube: readCube(raw) };
}

export function readPage(input: string | unknown): PageDocument {
  const raw = typeof input === 'string' ? parse(input) : input;
  if (!isObject(raw)) throw new PageDocumentError('expected an object');
  if (raw['kind'] !== PAGE_KIND) {
    throw new PageDocumentError(`not a saved page (kind ${JSON.stringify(plain(raw['kind']) ?? null)})`);
  }
  const version = plain(raw['version']);
  if (typeof version !== 'number' || version < 1) throw new PageDocumentError('it has no version');
  if (version > PAGE_VERSION) {
    throw new PageDocumentError(`written by a newer version (${version} > ${PAGE_VERSION}); upgrade to open it`);
  }
  if (version < PAGE_VERSION) {
    throw new PageDocumentError(`saved by an older version (${version} < ${PAGE_VERSION}): it is no longer read`);
  }
  if (typeof raw['name'] !== 'string') throw new PageDocumentError("missing 'name'");
  if (!named('page', raw['name'])) throw new PageDocumentError("the page's name is blank");

  const cubesRaw = raw['cubes'];
  if (!Array.isArray(cubesRaw) || cubesRaw.length === 0) throw new PageNoCubeError();
  const cubes = cubesRaw.map((c, i) => {
    if (!isObject(c) || typeof c['id'] !== 'string' || !isObject(c['cube'])) {
      throw new PageDocumentError(`cube ${i + 1} is not an id and a cube`);
    }
    if (c['cube']['kind'] !== CUBE_KIND) throw new PageDocumentError(`cube ${c['id']} is not a saved cube`);
    return { id: c['id'], cube: readCube(c['cube']) };
  });
  const ids = new Set(cubes.map((c) => c.id));
  // a view names its cube by id, and the model keeps a table per cube's id: two cubes of one id are one too many
  if (ids.size !== cubes.length) throw new PageDocumentError('two cubes share an id');

  const viewsRaw = plain(raw['views']);
  if (!Array.isArray(viewsRaw)) throw new PageDocumentError("'views' is not a list");
  const views = viewsRaw.map((v) => readView(v, ids));
  const viewIds = new Set(views.map((v) => v.id));
  if (viewIds.size !== views.length) throw new PageDocumentError('two views share an id');

  const sheets = readSheets(plain(raw['sheets']), viewIds);
  // every view has its place, on one sheet: a view the layout leaves out would be put somewhere on opening, and the
  // page read as changed before anyone touched it
  const placed = sheets.flatMap((sheet) => tilesOf(sheet.layout));
  const twice = placed.filter((id, i) => placed.indexOf(id) !== i);
  if (twice.length > 0) throw new PageDocumentError(`${[...new Set(twice)].join(', ')} on two sheets`);
  const missing = [...viewIds].filter((id) => !placed.includes(id));
  if (missing.length > 0) throw new PageDocumentError(`no sheet has a place for ${missing.join(', ')}`);

  const unknown: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(raw)) if (!KNOWN.has(k)) unknown[k] = plain(v);
  const model = raw['model'] === undefined ? undefined : readModel(plain(raw['model']), ids);

  return {
    kind: PAGE_KIND,
    version: PAGE_VERSION,
    name: raw['name'],
    cubes,
    views,
    sheets,
    ...(model ? { model } : {}),
    ...(Object.keys(unknown).length > 0 ? { unknown } : {}),
  };
}

/** The page's model: its project, its workspace, its package, and each grid's table (`ids`: the page's cubes). */
function readModel(raw: unknown, ids: ReadonlySet<string>): PageModelRef {
  if (!isObject(raw)) throw new PageDocumentError("'model' is not an object");
  const { project, workspace, package: pkg, tables, classes, revision } = raw;
  for (const [field, value] of [['project', project], ['workspace', workspace], ['package', pkg]] as const) {
    if (typeof value !== 'string' || value === '') throw new PageDocumentError(`'model' has no ${field}`);
  }
  if (!isObject(tables)) throw new PageDocumentError("'model' has no tables");
  const kept: Record<string, string> = {};
  for (const [cube, table] of Object.entries(tables)) {
    if (!ids.has(cube)) throw new PageDocumentError(`'model' keeps a table for ${cube}, which is no cube of the page`);
    if (typeof table !== 'string' || table === '') throw new PageDocumentError(`'model' keeps no table name for ${cube}`);
    kept[cube] = table;
  }
  const made: Record<string, string> = {};
  if (classes !== undefined) {
    if (!isObject(classes)) throw new PageDocumentError("'model' has classes that are not an object");
    for (const [cube, cls] of Object.entries(classes)) {
      if (!ids.has(cube)) throw new PageDocumentError(`'model' keeps a class for ${cube}, which is no cube of the page`);
      if (typeof cls !== 'string' || cls === '') throw new PageDocumentError(`'model' keeps no class name for ${cube}`);
      made[cube] = cls;
    }
  }
  // the pin: none while nothing of the project is saved (its first save failed)
  if (revision !== undefined && (typeof revision !== 'string' || revision === '')) {
    throw new PageDocumentError("'model' has a revision that is no revision's id");
  }
  return {
    project: project as string, workspace: workspace as string, package: pkg as string, tables: kept,
    ...(Object.keys(made).length > 0 ? { classes: made } : {}),
    ...(revision !== undefined ? { revision } : {}),
  };
}

function readSheets(raw: unknown, views: ReadonlySet<string>): readonly PageSheet[] {
  if (!Array.isArray(raw) || raw.length === 0) throw new PageDocumentError("'sheets' is not a list of sheets");
  const ids = new Set<string>();
  return raw.map((sheet, i) => {
    if (!isObject(sheet) || typeof sheet['id'] !== 'string' || sheet['id'].trim() === '') throw new PageDocumentError(`sheet ${i + 1} has no id`);
    const id = sheet['id'];
    if (ids.has(id)) throw new PageDocumentError(`two sheets are ${id}`);
    ids.add(id);
    const name = sheet['name'];
    if (name !== undefined && (typeof name !== 'string' || name.trim() === '')) {
      throw new PageDocumentError(`sheet ${id}'s name is not a name`);
    }
    return {
      id,
      ...(typeof name === 'string' ? { name } : {}),
      layout: readLayout(sheet['layout'], views, `sheet ${id}'s layout`),
    };
  });
}

function readView(v: unknown, cubes: ReadonlySet<string>): PageView {
  if (!isObject(v) || typeof v['id'] !== 'string') throw new PageDocumentError('a view has no id');
  const id = v['id'];
  if (typeof v['cube'] !== 'string' || !cubes.has(v['cube'])) {
    throw new PageDocumentError(`view ${id} shows no cube of this page`);
  }
  if (v['kind'] === 'grid') {
    if (v['title'] !== undefined && !named('tile', v['title'])) {
      throw new PageDocumentError(`grid ${id}'s title is blank`);
    }
    return {
      id,
      kind: 'grid',
      cube: v['cube'],
      ...(typeof v['title'] === 'string' ? { title: v['title'] } : {}),
    };
  }
  if (v['kind'] !== 'chart') throw new PageDocumentError(`view ${id} is of an unknown kind ${JSON.stringify(v['kind'] ?? null)}`);
  if (typeof v['title'] !== 'string') throw new PageDocumentError(`chart ${id} has no title`);
  if (!named('tile', v['title'])) throw new PageDocumentError(`chart ${id}'s title is blank`);
  // its version (chart-spec.ts CHART_VERSION), and every word of the spec's slots one this DataCube knows
  const spec = readChart(v['spec']);
  if (typeof spec === 'string') throw new PageDocumentError(`chart ${id} has no chart it can draw: ${spec}`);
  const selection = v['selection'];
  if (selection !== undefined && !Array.isArray(selection)) {
    throw new PageDocumentError(`chart ${id}'s selection is not a list`);
  }
  return {
    id,
    kind: 'chart',
    cube: v['cube'],
    title: v['title'],
    spec,
    ...(selection && selection.length > 0 ? { selection: selection as FilterNode[] } : {}),
  };
}

/**
 * A layout of bands (`what` names it in what is said): its tiles, each a view of this page, the bands' rules kept. A
 * page's reader reads each sheet's by it, and a page command's layout is read by it too (page-commands.ts `layout`).
 */
export function readLayout(l: unknown, views: ReadonlySet<string>, what: string): PageLayout {
  if (!isObject(l)) throw new PageDocumentError(`${what} is not a layout`);
  if (l['kind'] !== 'bands') throw new PageDocumentError(`${what} is not a layout of bands`);
  if (typeof l['fit'] !== 'boolean') throw new PageDocumentError(`${what}'s fit is not true or false`);
  if (!Array.isArray(l['bands'])) throw new PageDocumentError(`${what}'s bands are not a list`);
  const bands = l['bands'].map((b, i) => {
    if (!isObject(b) || typeof b['height'] !== 'number') throw new PageDocumentError(`band ${i + 1} is not a height and its tiles`);
    return { height: b['height'], node: readNode(b['node'], views, `band ${i + 1}`) };
  });
  const layout: Bands = { fit: l['fit'], bands };
  const wrong = problems(layout);
  if (wrong.length > 0) throw new PageDocumentError(`${what} cannot be laid out: ${wrong.join('; ')}`);
  return { kind: 'bands', ...layout };
}

function readNode(n: unknown, views: ReadonlySet<string>, where: string): Node {
  if (isObject(n) && typeof n['tile'] === 'string') {
    if (!views.has(n['tile'])) throw new PageDocumentError(`a tile shows no view of this page (${n['tile']})`);
    return { tile: n['tile'] };
  }
  // a stack of tiles (the design's §7.4): its tiles in their tabs' order; it reopens on its first tab
  if (isObject(n) && Array.isArray(n['stack'])) {
    const stack = n['stack'].map((t) => {
      if (typeof t !== 'string' || !views.has(t)) throw new PageDocumentError(`${where}'s stack holds no view of this page (${JSON.stringify(t)})`);
      return t;
    });
    return { stack };
  }
  if (!isObject(n) || (n['split'] !== 'row' && n['split'] !== 'column') || !Array.isArray(n['parts'])) {
    throw new PageDocumentError(`${where} is not a tile, a stack or a split of parts`);
  }
  return {
    split: n['split'],
    parts: n['parts'].map((part, i) => {
      if (!isObject(part) || typeof part['size'] !== 'number') throw new PageDocumentError(`${where}, part ${i + 1}, has no size`);
      return { node: readNode(part['node'], views, `${where}, part ${i + 1}`), size: part['size'] };
    }),
  };
}

function parse(text: string): unknown {
  try {
    return fromJson(text);
  } catch (e) {
    throw new PageDocumentError(`not valid JSON (${e instanceof Error ? e.message : String(e)})`);
  }
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v) && !(v instanceof ExactNumber);
}

function plain(v: unknown): unknown {
  if (v instanceof ExactNumber) return Number(v.text);
  if (Array.isArray(v)) return v.map(plain);
  if (isObject(v)) return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, plain(x)]));
  return v;
}
