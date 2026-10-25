// A TIMESERIES TILE, HEADLESS (docs/DATACUBE_TIMESERIES_DESIGN_2026_10_25.md §7): the spec of what Python tells it,
// made by DataCube's own rules -- its expressions read by DataCube's parser, a source's columns by DataCube's defaults,
// the whole read by the tile's one reader -- so Python's page.timeseries keeps no rule of its own (the charts' rule:
// headless gridChart). The page command that adds or sets it is Python's generated builder's, applied by pageApply.

import { readPage, type PageDocument } from '../page-document.ts';
import { rowColumns } from '../snapshot.ts';
import {
  aliasFor, columnRef, firstValue, nextSeriesId, parseExpr, readTimeseries, sourceOf, TsSyntaxError, type TimeseriesSpec, type TsColumn, type TsExpr,
  type TsSeries, type TsSource,
} from '../timeseries-spec.ts';

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

/** A grid of the page by its tile's id: its cube's id and its columns as the cube's query types them. */
function gridOf(page: PageDocument, grid: string): { readonly cube: string; readonly title: string; readonly columns: TsColumn[]; readonly rows: readonly string[] } {
  const view = page.views.find((v) => v.id === grid);
  if (!view || view.kind !== 'grid') throw new Problem(`'${grid}' is no grid of the page`);
  const held = page.cubes.find((c) => c.id === view.cube);
  if (!held) throw new Problem(`'${grid}' shows no cube the page holds`);
  const columns = rowColumns(held.cube.query).map((c) => ({ name: c.name, type: c.type }));
  return { cube: view.cube, title: view.title ?? held.cube.name, columns, rows: held.cube.query.rows };
}

class Problem extends Error {}

/** A first series of a source told by its grid: its price-like value (firstValue), a line per instrument. */
function firstSeries(doc: PageDocument, told: unknown, source: TsSource | undefined): unknown[] {
  if (!isRecord(told) || typeof told['grid'] !== 'string' || source === undefined) return [];
  const value = firstValue(gridOf(doc, told['grid']).columns, source);
  return value === undefined ? [] : [{ expr: columnRef(value), ...(source.key !== undefined ? { split: source.key } : {}) }];
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
    const rawSeries = told['series'] ?? firstSeries(doc, rawSources[0], sources[0]);
    if (!Array.isArray(rawSeries)) throw new Problem("a timeseries' series are a list");
    const series: TsSeries[] = [];
    for (const [i, raw] of rawSeries.entries()) {
      const s = typeof raw === 'string' ? { expr: raw } : raw;
      if (!isRecord(s)) throw new Problem(`series ${i + 1} is not an expression or an object`);
      const id = typeof s['id'] === 'string' ? s['id'] : nextSeriesId(series);
      series.push({ ...s, id, expr: exprOf(s['expr'], `series ${id}`) } as unknown as TsSeries);
    }
    const spec = readTimeseries({ ...told, sources, series });
    if (typeof spec === 'string') throw new Problem(spec);
    return JSON.stringify({ spec: spec satisfies TimeseriesSpec });
  } catch (e) {
    if (e instanceof Problem) return JSON.stringify({ problems: [e.message] });
    throw e;
  }
}
