// A TIMESERIES TILE AS CODE (the user, 2026-10-26: "an option that shows how exactly to build that chart
// programmatically from TS and python"): the tile as the program that builds it -- in Python, the sheet's `timeseries`
// (python/legend_lite/page.py), which asks DataCube's own reader (headless timeseriesMade) and applies the page command
// the screen's Create ▸ Timeseries sends; in TypeScript, that command itself (page-commands.ts addTimeseries) applied
// by the one function every change to a page goes through (applyPageCommands). Each written from the tile's spec, its
// expressions written to read back as themselves (timeseries-expr.ts exprSource), so the program builds the tile it
// was written of -- the tests run both and read back the spec they made.
//
// One rule for the viewer's Code window and Python's Timeseries.code (headless timeseriesCode).
//
// PURE: no DOM, no planner.

import { layoutTiles, type PageDocument } from './page-document.ts';
import { gridTitle } from './page-ids.ts';
import { CubeRefusal } from './snapshot.ts';
import { exprSource, nextSeriesId } from './timeseries-expr.ts';
import type { TimeseriesSpec, TsSeries, TsSource } from './timeseries-spec.ts';

/** The languages a tile's code is written in. */
export type TsCodeLanguage = 'python' | 'typescript';
export const TS_CODE_LANGUAGES: readonly TsCodeLanguage[] = ['python', 'typescript'];

/** Where a tile is on its page and what its sources' grids are there, as its code names them. */
export interface TsCodeOf {
  /** Its title, as its tile says it (null: the tile's own default). */
  readonly title: string | null;
  /** The sheet it is on, by its id: the program adds it there, a band of its own at the bottom. */
  readonly sheet: string;
  /** Each source's grid on the page, by the cube the source reads: its tile's id and its title. A cube no grid shows
   *  (its grid removed, kept for the tile) has none: its source is written as the tile keeps it. */
  readonly grids: Readonly<Record<string, { readonly tile: string; readonly title: string } | undefined>>;
}

/** A tile's settings besides its sources and series, in the order a program tells them. */
const SLOTS = ['grain', 'align', 'range', 'panes', 'annotations', 'view'] as const;

/** A series' keys besides its id and its expression, in the order a program tells them. */
const SERIES_KEYS = ['name', 'split', 'style', 'pane', 'axis', 'hidden'] as const;

/** A tile of a page document and where it is there, as its code names it: its sheet, its title, its sources' grids. */
export function codeOfPage(page: PageDocument, tile: string): { readonly spec: TimeseriesSpec; readonly of: TsCodeOf } {
  const view = page.views.find((v) => v.id === tile);
  if (!view || view.kind !== 'timeseries') throw new CubeRefusal(`'${tile}' is no timeseries tile of the page`);
  const sheet = page.sheets.find((s) => layoutTiles(s.layout).includes(tile));
  if (!sheet) throw new CubeRefusal(`'${tile}' is on no sheet of the page`);
  const grids: Record<string, { readonly tile: string; readonly title: string }> = {};
  for (const s of view.spec.sources) {
    const g = page.views.find((v) => v.kind === 'grid' && v.cube === s.cube);
    if (g !== undefined) grids[s.cube] = { tile: g.id, title: g.title ?? gridTitle(g.id) };
  }
  return { spec: view.spec, of: { title: view.title ?? null, sheet: sheet.id, grids } };
}

/** THE PROGRAM THAT BUILDS A TILE: `spec` on the sheet and over the grids `of` says, in `language`. */
export function tileCode(spec: TimeseriesSpec, of: TsCodeOf, language: TsCodeLanguage): string {
  switch (language) {
    case 'python':
      return pythonCode(spec, of);
    case 'typescript':
      return typescriptCode(spec, of);
  }
}

// ------------------------------------------------------------------ Python

/** A value as Python writes it: a dict, a list, a str, a number, True, False, None. */
function py(v: unknown): string {
  if (v === null || v === undefined) return 'None';
  if (v === true) return 'True';
  if (v === false) return 'False';
  if (typeof v === 'number') {
    if (!Number.isFinite(v)) throw new Error(`${v} is no number a page holds`);
    return String(v);
  }
  if (typeof v === 'string') return `'${v.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/\n/g, '\\n')}'`;
  if (Array.isArray(v)) return `[${v.map(py).join(', ')}]`;
  if (typeof v === 'object') return `{${Object.entries(v as Record<string, unknown>).map(([k, x]) => `${py(k)}: ${py(x)}`).join(', ')}}`;
  throw new Error(`a ${typeof v} is no value a page holds`);
}

/** Python's words no name may be. */
const PY_KEYWORDS = new Set(['False', 'None', 'True', 'and', 'as', 'assert', 'async', 'await', 'break', 'class', 'continue', 'def', 'del',
  'elif', 'else', 'except', 'finally', 'for', 'from', 'global', 'if', 'import', 'in', 'is', 'lambda', 'nonlocal', 'not', 'or', 'pass',
  'raise', 'return', 'try', 'while', 'with', 'yield']);

/** A grid's variable, named after its title: lower case, words joined by `_`, none the program's own names. */
function pyName(title: string, taken: Set<string>): string {
  const words = title.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
  const base = words === '' ? 'grid' : /^[0-9]/.test(words) ? `grid_${words}` : PY_KEYWORDS.has(words) ? `${words}_grid` : words;
  let name = base;
  for (let n = 2; taken.has(name); n++) name = `${base}_${n}`;
  taken.add(name);
  return name;
}

/** Each series as `page.timeseries` takes it: its expression alone, or a mapping of it and how it is drawn -- its id
 *  told where it is not the one the reader would give it (the next free letter, nextSeriesId). */
function pySeries(series: readonly TsSeries[]): string[] {
  return series.map((s, i) => {
    const natural = nextSeriesId(series.slice(0, i)) === s.id;
    const rest = SERIES_KEYS.filter((k) => s[k] !== undefined);
    const expr = exprSource(s.expr);
    if (natural && rest.length === 0) return py(expr);
    const told: Record<string, unknown> = { ...(natural ? {} : { id: s.id }), expr };
    for (const k of rest) told[k] = s[k];
    return py(told);
  });
}

function pythonCode(spec: TimeseriesSpec, of: TsCodeOf): string {
  const taken = new Set(['ll', 'page', 'sheet', 'tile', 'q']);
  const grids = new Map<string, string>();
  const lines: string[] = [];
  for (const s of spec.sources) {
    const g = of.grids[s.cube];
    if (g === undefined || grids.has(s.cube)) continue;
    const name = pyName(g.title, taken);
    grids.set(s.cube, name);
    lines.push(`${name} = next(g for g in page.grids if g.id == ${py(g.tile)})  # the grid "${g.title.replace(/\n/g, ' ')}"`);
  }
  // each source a mapping: its alias, its grid (else, its grid removed, its cube as the tile keeps it), its date and key
  const source = (s: TsSource): string => {
    const grid = grids.get(s.cube);
    const told = grid === undefined ? { cube: s.cube, date: s.date, key: s.key ?? null, ...(s.kept !== undefined ? { kept: s.kept } : {}) }
      : { date: s.date, key: s.key ?? null };
    const fields = Object.entries(told).map(([k, v]) => `${py(k)}: ${py(v)}`);
    return `{'alias': ${py(s.alias)}, ${grid === undefined ? '' : `'grid': ${grid}, `}${fields.join(', ')}}`;
  };
  const slots = SLOTS.filter((k) => spec[k] !== undefined).map((k) => `    ${k}=${py(spec[k])},`);
  return [
    `# ${of.title ?? 'A timeseries tile'}, built in Python: the sheet's timeseries, as the screen's Create ▸ Timeseries makes it --`,
    '# its settings judged by DataCube\'s own reader, the tile added by the page command the screen sends. Every figure it',
    '# draws is the database\'s, by the queries tile.queries() lists.',
    'import legend_lite as ll',
    '',
    'page = ll.Page.load(\'page.json\')  # this page\'s document as Save writes it (or ll.Page.open(its saved id))',
    `sheet = next(s for s in page.sheets if s.id == ${py(of.sheet)})`,
    ...lines,
    '',
    'tile = sheet.timeseries(',
    `    [${spec.sources.map(source).join(', ')}],`,
    '    [',
    ...pySeries(spec.series).map((s) => `        ${s},`),
    '    ],',
    ...(of.title !== null ? [`    title=${py(of.title)},`] : []),
    ...slots,
    ')',
    '',
    ...(spec.series.length > 0 ? [`print(tile.frame(${py(spec.series[0]!.id)}))  # series ${spec.series[0]!.id}'s points, every one the database's`] : []),
    'print(tile.statistics())  # the statistics panel\'s',
    'for q in tile.queries():  # every query it asks the database: its Pure and its SQL',
    '    print(q[\'what\'], q.get(\'pure\'), q.get(\'sql\'), sep=\'\\n\')',
    '',
  ].join('\n');
}

// ------------------------------------------------------------------ TypeScript

const IDENTIFIER = /^[A-Za-z_$][A-Za-z0-9_$]*$/;

/** A value as TypeScript writes it: an object, an array, a string, a number, true, false, null. */
function ts(v: unknown): string {
  if (v === null) return 'null';
  if (typeof v === 'boolean') return String(v);
  if (typeof v === 'number') {
    if (!Number.isFinite(v)) throw new Error(`${v} is no number a page holds`);
    return String(v);
  }
  if (typeof v === 'string') return `'${v.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/\n/g, '\\n')}'`;
  if (Array.isArray(v)) return `[${v.map(ts).join(', ')}]`;
  if (typeof v === 'object') {
    const fields = Object.entries(v as Record<string, unknown>).filter(([, x]) => x !== undefined).map(([k, x]) => `${IDENTIFIER.test(k) ? k : ts(k)}: ${ts(x)}`);
    return fields.length === 0 ? '{}' : `{ ${fields.join(', ')} }`;
  }
  throw new Error(`a ${typeof v} is no value a page holds`);
}

/** A series as its spec holds it, its expression read by the catalog's parser. */
function tsSeries(s: TsSeries): string {
  const rest = SERIES_KEYS.filter((k) => s[k] !== undefined).map((k) => `, ${k}: ${ts(s[k])}`).join('');
  return `{ id: ${ts(s.id)}, expr: parseExpr(${ts(exprSource(s.expr))})${rest} }`;
}

function typescriptCode(spec: TimeseriesSpec, of: TsCodeOf): string {
  const slots = SLOTS.filter((k) => spec[k] !== undefined).map((k) => `  ${k}: ${ts(spec[k])},`);
  return [
    `// ${of.title ?? 'A timeseries tile'}, built in TypeScript: its spec, and the page command the screen's Create ▸ Timeseries`,
    '// sends and Python\'s sheet.timeseries applies -- every change to a page is a command, applied by one function',
    '// (page-commands.ts). Its queries are written by the one function the screen\'s every round writes them by',
    '// (timeseries-query.ts askQuery); the paths are from the repository\'s root.',
    'import { applyPageCommands, idsOf, type PageCommand } from \'./datacube/src/page-commands.ts\';',
    'import type { PageDocument } from \'./datacube/src/page-document.ts\';',
    'import { parseExpr } from \'./datacube/src/timeseries-expr.ts\';',
    'import { tileAsks } from \'./datacube/src/timeseries-query.ts\';',
    'import type { TimeseriesSpec } from \'./datacube/src/timeseries-spec.ts\';',
    '',
    'export const spec: TimeseriesSpec = {',
    `  sources: [${spec.sources.map((s) => ts(s)).join(', ')}],`,
    '  series: [',
    ...spec.series.map((s) => `    ${tsSeries(s)},`),
    '  ],',
    ...slots,
    '};',
    '',
    '/** `page` with the tile added on its sheet, a band of its own at the bottom: the screen\'s own command, applied. */',
    'export function withTile(page: PageDocument): PageDocument {',
    `  const add: PageCommand = { kind: 'addTimeseries', sheet: ${ts(of.sheet)}, near: null, spec, title: ${ts(of.title)}, columns: null };`,
    '  return applyPageCommands({ page, held: idsOf(page) }, [add]).draft.page;',
    '}',
    '',
    '/** Every question it asks the database, as its Query window lists them. */',
    'export const questions: string[] = tileAsks(spec).map((a) => a.what);',
    '',
  ].join('\n');
}
