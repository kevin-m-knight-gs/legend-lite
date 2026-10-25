// A TIMESERIES TILE, HEADLESS (docs/DATACUBE_TIMESERIES_DESIGN_2026_10_25.md §7): the spec of what Python tells it,
// made by DataCube's own rules -- its expressions read by DataCube's parser, a source's columns by DataCube's defaults,
// the whole read by the tile's one reader -- so Python's page.timeseries keeps no rule of its own (the charts' rule:
// headless gridChart). The page command that adds or sets it is Python's generated builder's, applied by pageApply.

import { fromJson, toJson } from '../../../pure-protocol/src/index.ts';
import type { CubeSource } from '../cube-document.ts';
import { readPage, type PageDocument } from '../page-document.ts';
import { gridTitle } from '../page-ids.ts';
import { rowColumns, type CubeSnapshot } from '../snapshot.ts';
import {
  readsAnchor, seriesQuery, seriesSources, TS_DATE, TS_LOWER, TS_SPLIT, TS_UPPER, TS_VALUE, type TsInputs, type TsWindow,
} from '../timeseries-query.ts';
import { leafTables, type HostTables } from './host-tables.ts';
import { sourcesOpenedOn } from './join.ts';
import { readTimeseries, type TimeseriesSpec, type TsExpr, type TsSeries, type TsSource } from '../timeseries-spec.ts';
import {
  aliasFor, firstPlot, nextSeriesId, parseExpr, sourceOf, TsSyntaxError, type TsColumn
} from '../timeseries-expr.ts';

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

/** A grid of the page by its tile's id: its cube's id and its columns as the cube's query types them. */
function gridOf(page: PageDocument, grid: string): { readonly cube: string; readonly title: string; readonly columns: TsColumn[]; readonly rows: readonly string[] } {
  const view = page.views.find((v) => v.id === grid);
  if (!view || view.kind !== 'grid') throw new Problem(`'${grid}' is no grid of the page`);
  const held = page.cubes.find((c) => c.id === view.cube);
  if (!held) throw new Problem(`'${grid}' shows no cube the page holds`);
  const columns = rowColumns(held.cube.query).map((c) => ({ name: c.name, type: c.type }));
  // titled as the page names its tile (page-ids.ts gridTitle): a source's alias is called after it, as on the screen
  return { cube: view.cube, title: view.title ?? gridTitle(view.id), columns, rows: held.cube.query.rows };
}

class Problem extends Error {}

/** A first plot of a source told by its grid, as the screen's Create ▸ Timeseries makes one (timeseries-expr.ts
 *  firstPlot: its price-like value, a line per instrument, a price's rebased). */
function firstOf(doc: PageDocument, told: unknown, source: TsSource | undefined): Pick<TimeseriesSpec, 'series' | 'view'> | undefined {
  if (!isRecord(told) || typeof told['grid'] !== 'string' || source === undefined) return undefined;
  return firstPlot(gridOf(doc, told['grid']).columns, source);
}

/** An expression as Python gives it: its text, read by DataCube's parser (refused where it is, in DataCube's words), or a tree. */
function exprOf(e: unknown, what: string): TsExpr {
  if (typeof e !== 'string') return e as TsExpr;
  try {
    return parseExpr(e);
  } catch (err) {
    if (err instanceof TsSyntaxError) throw new Problem(`${what}: ${err.message} (at ${err.at + 1}: ${JSON.stringify(e)})`);
    throw err;
  }
}

/**
 * A TIMESERIES' SPEC AS PYTHON TELLS IT: `given` (JSON) is a spec's own slots, but that each source may name its grid by
 * its tile (`grid`, its cube then the grid's) and leave out its alias, its date and its instrument column (DataCube's
 * rule takes them from the grid's columns: timeseries-spec.ts sourceOf, aliasFor), and each series may give its
 * expression as text (`volatility(px:close[SPX], 63)`, read by DataCube's parser) and leave out its id (the next letter).
 * `page`: the page as Python holds it. JSON: `{spec}` -- read by the tile's one reader -- or `{problems}`, in DataCube's
 * words.
 */
export function timeseriesMade(page: string, given: string): string {
  try {
    const doc = readPage(JSON.parse(page));
    const told: unknown = JSON.parse(given);
    if (!isRecord(told)) throw new Problem('a timeseries is an object of its sources and its series');
    const rawSources = told['sources'];
    if (!Array.isArray(rawSources) || rawSources.length === 0) throw new Problem('a timeseries reads one grid at least (sources)');
    const sources: TsSource[] = [];
    for (const [i, raw] of rawSources.entries()) {
      if (!isRecord(raw)) throw new Problem(`source ${i + 1} is not an object`);
      const { grid, ...rest } = raw;
      if (grid === undefined) {
        sources.push(rest as unknown as TsSource);
        continue;
      }
      if (typeof grid !== 'string') throw new Problem(`source ${i + 1}'s grid is not a tile's id`);
      const g = gridOf(doc, grid);
      const alias = typeof rest['alias'] === 'string' ? rest['alias'] : aliasFor(g.title, sources.map((s) => s.alias));
      const made = sourceOf(g.columns, g.rows, g.cube, alias);
      const date = typeof rest['date'] === 'string' ? rest['date'] : made?.date;
      if (date === undefined) throw new Problem(`${grid} has no date column to read its series along: say its date`);
      const key = typeof rest['key'] === 'string' ? rest['key'] : rest['key'] === null ? undefined : made?.key;
      const extra = Object.keys(rest).filter((k) => !['alias', 'date', 'key'].includes(k));
      sources.push({ alias, cube: g.cube, date, ...(key !== undefined ? { key } : {}), ...Object.fromEntries(extra.map((k) => [k, rest[k]])) } as TsSource);
    }
    // no series told: DataCube's first of its first grid (defaultTimeseries' rule)
    const first = told['series'] === undefined ? firstOf(doc, rawSources[0], sources[0]) : undefined;
    const rawSeries = told['series'] ?? first?.series ?? [];
    if (!Array.isArray(rawSeries)) throw new Problem("a timeseries' series are a list");
    const series: TsSeries[] = [];
    for (const [i, raw] of rawSeries.entries()) {
      const s = typeof raw === 'string' ? { expr: raw } : raw;
      if (!isRecord(s)) throw new Problem(`series ${i + 1} is not an expression or an object`);
      const id = typeof s['id'] === 'string' ? s['id'] : nextSeriesId(series);
      series.push({ ...s, id, expr: exprOf(s['expr'], `series ${id}`) } as unknown as TsSeries);
    }
    const spec = readTimeseries({ ...told, sources, series, ...(told['view'] === undefined && first?.view ? { view: first.view } : {}) });
    if (typeof spec === 'string') throw new Problem(spec);
    return JSON.stringify({ spec: spec satisfies TimeseriesSpec });
  } catch (e) {
    if (e instanceof Problem) return JSON.stringify({ problems: [e.message] });
    throw e;
  }
}

/** What a host asks of a series' query (timeseriesQuery): its page, the tile and the series, the window it is read
 *  over, and what the host serves (host-tables.ts HostTables). */
export interface SeriesAsked {
  readonly page: unknown;
  readonly tile: string;
  readonly series: string;
  /** The first date it is read from (an index's base, compare's), and the last; absent, every date. */
  readonly window?: TsWindow;
  readonly host: HostTables;
}

/**
 * A SERIES' QUERY, HEADLESS (docs/DATACUBE_TIMESERIES_DESIGN_2026_10_25.md §5): a timeseries tile's series as its viewer
 * asks for it -- timeseries-query.ts seriesQuery over its sources' grids as the page has them (each its source, its
 * calculated columns and its filter), the sources opened over one model of the host's tables they read (join-open.ts
 * openSources, as the tab reads several grids together; one grid read the same way) -- for a host to plan and run where
 * the rows are (Python's Timeseries.frame: the database's every figure, as the screen's). JSON: `{model, runtime,
 * query, columns, unit, band, split, name}` -- the query a lambda as the protocol writes it, `columns` the names of its
 * answer's (timeseries-query.ts: `date`, `value`, a split's `split`, a band's `lower` and `upper`), its rows latest
 * first -- or `{problems}`, in DataCube's words.
 */
export async function timeseriesQuery(spec: string): Promise<string> {
  try {
    const asked = fromJson(spec) as unknown as SeriesAsked;
    const page = readPage(asked.page, { empty: true });
    const view = page.views.find((v) => v.id === asked.tile);
    if (!view || view.kind !== 'timeseries') throw new Problem(`'${asked.tile}' is no timeseries tile of the page`);
    const ts = view.spec;
    const series = ts.series.find((x) => x.id === asked.series);
    if (!series) throw new Problem(`${asked.tile} has no series ${asked.series} (it has ${ts.series.map((x) => x.id).join(', ')})`);
    let aliases: string[];
    try {
      aliases = seriesSources(ts, series);
    } catch (e) {
      throw new Problem(`series ${series.id}: ${e instanceof Error ? e.message : String(e)}`);
    }
    const sources: CubeSource[] = [];
    const tables: string[] = [];
    const saved: { readonly alias: string; readonly query: PageDocument['cubes'][number]['cube']['query'] }[] = [];
    for (const alias of aliases) {
      const src = ts.sources.find((x) => x.alias === alias)!;
      const cube = page.cubes.find((c) => c.id === src.cube);
      if (!cube || !page.views.some((v) => v.kind === 'grid' && v.cube === src.cube)) {
        throw new Problem(`source ${alias}'s grid is not on the page (it was removed)`);
      }
      sources.push(cube.cube.source);
      tables.push(...await leafTables(cube.cube.source, asked.host));
      saved.push({ alias, query: cube.cube.query });
    }
    const opened = await sourcesOpenedOn(sources, tables);
    // each grid as the page has it -- its calculated columns, its filter -- over its relation of the model
    const inputs: TsInputs = new Map(saved.map(({ alias, query }, i) => {
      const snapshot: CubeSnapshot = { ...query, source: { query: opened.relations[i]! }, epoch: 0 };
      return [alias, { snapshot, filter: query.filter }] as const;
    }));
    const window = asked.window ?? {};
    // its first date its base where it reads one (an index, compare), and the window's dates alone kept -- by the database
    const q = seriesQuery(inputs, ts, series, readsAnchor(ts, series) && window.from !== undefined ? { from: window.from } : {}, window);
    return `{"model":${JSON.stringify(opened.model)},"runtime":${JSON.stringify(opened.runtime)},"query":${toJson(q.query)},`
      + `"columns":${JSON.stringify({ date: TS_DATE, value: TS_VALUE, split: TS_SPLIT, lower: TS_LOWER, upper: TS_UPPER })},`
      + `"unit":${JSON.stringify(q.unit)},"band":${JSON.stringify(q.band)},"split":${JSON.stringify(q.split ?? null)},`
      + `"name":${JSON.stringify(series.name ?? null)}}`;
  } catch (e) {
    if (e instanceof Problem) return JSON.stringify({ problems: [e.message] });
    throw e;
  }
}
