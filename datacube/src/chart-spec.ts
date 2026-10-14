// A chart of a cube: what it shows, and the query that answers it.
//
// A chart is a VIEW of a cube, not a second engine. Its query is the
// cube's own snapshot -- same source, same filter, same calculated
// columns -- regrouped by the chart's encoding: x (and a split) become
// the row dimensions, each y a measure. So `levelLambda` builds it, and
// grouping, typing and filtering are the cube's, never re-implemented
// here. The database aggregates; the chart library only draws the rows
// it is handed (docs/DATACUBE_DASHBOARDS_DESIGN_2026_09_28.md §3.3, §6).
//
// The spec is OURS and plain JSON: it is what a saved cube will keep, so
// a chart never depends on the drawing library's version, and it holds
// no functions (a stored formatter would need eval to come back).

import { levelLambda, type LevelScope } from './query.ts';
import { binColumns, binName, grainColumn, grainName, rangeConditions, showAsColumns, showAsName, statColumn } from './calc-presets.ts';
import type { Scalar } from '../../engine-client/src/result.ts';
import {
  AGGREGATE_FNS,
  rowColumns,
  type AggregateFn,
  type CubeSnapshot,
  type DerivedColumn,
  type FilterNode,
  type Measure,
  type RowColumn,
} from './snapshot.ts';
import { defaultKind, isFractional, isNumeric, isTemporal } from '../../engine-client/src/types.ts';
import { lambda, property, variable, type Lambda } from '../../pure-protocol/src/index.ts';

export type ChartMark = 'bar' | 'line' | 'area' | 'scatter' | 'pie' | 'heatmap' | 'treemap' | 'kpi';

/**
 * The spec's version (docs/DATACUBE_CHARTS_DESIGN_2026_10_09.md §3.1). 2: a slot per role -- every field it adds is
 * optional, so a version 1 spec IS a version 2 spec and is read unchanged but for its number. 1: x, y, split, options.
 */
export const CHART_VERSION = 2;

/**
 * How a measure is aggregated: one of the grid's aggregates, or `value` -- a GROUP-LEVEL column (the cube's or the
 * chart's own), already one figure per group, plotted as it is.
 */
export type ChartAggregate = AggregateFn | 'value';

/** The kind of mark one measure is drawn with, in a chart that draws several (bar and line together). */
export type MeasureMark = 'bar' | 'line' | 'area';

/** A measure's value axis: the first (left) or the second (right). Absent: by its unit (§6). */
export type MeasureAxis = 'left' | 'right';

/**
 * A measure shown as something computed from it (§3.2's presets): each is a group-level calculated column the
 * planner already plans -- a window over the chart's rows, sometimes with an expression over it.
 */
export type ShowAsKind = 'runningTotal' | 'percentOfTotal' | 'difference' | 'percentChange' | 'movingAverage' | 'rank';

export interface ShowAs {
  readonly kind: ShowAsKind;
  /** A moving average's rows, this one included (default 3). */
  readonly rows?: number;
}

/** One measure the chart plots: a column and how its rows are aggregated. */
export interface ChartMeasure {
  readonly column: string;
  readonly fn: ChartAggregate;
  /** Its own kind of mark (bar, line, area): a combination chart. Absent: the chart's. */
  readonly mark?: MeasureMark;
  /** Its value axis. Absent: one axis, or the second for a measure whose unit differs (§6). */
  readonly axis?: MeasureAxis;
  /** Shown as a running total, a share of the total, a change, a moving average or a rank. */
  readonly showAs?: ShowAs;
}

/** A date across, taken to its period: the grain's first day (the compiler's `firstDayOf...`), or its year. */
export type TimeGrain = 'day' | 'week' | 'month' | 'quarter' | 'year';

/** A number across, taken to its bin: `width` wide, or `count` bins over the column's range. */
export interface Bins {
  readonly width?: number;
  readonly count?: number;
}

/** The statistic a reference line draws: the database's figure over the chart's rows (a window over them). */
export type LayerStat = 'average' | 'min' | 'max';

/**
 * What a chart draws over its marks (§3.1's layers):
 *  - `line`: a constant or a target, at `value`;
 *  - `stat`: the average, minimum or maximum of a measure over the chart's rows;
 *  - `band`: a shaded range, `from` to `to`, across (`x`, categories or dates) or up (`y`, values);
 *  - `threshold`: a measure's marks coloured by value -- below `value` in the bad colour;
 *  - `extremes`: a measure's highest and lowest marks pinned.
 * `measure` is the index of the measure it reads (default 0); `label` what it is called.
 */
export type ChartLayer =
  | { readonly kind: 'line'; readonly value: number; readonly measure?: number; readonly label?: string }
  | { readonly kind: 'stat'; readonly stat: LayerStat; readonly measure?: number; readonly label?: string }
  | {
    readonly kind: 'band'; readonly axis: 'x' | 'y'; readonly from: string | number; readonly to: string | number;
    readonly label?: string;
  }
  | { readonly kind: 'threshold'; readonly value: number; readonly measure?: number; readonly label?: string }
  | { readonly kind: 'extremes'; readonly measure?: number; readonly label?: string };

/** The layers' kinds, one word each (Python's LAYERS). */
export type ChartLayerKind = 'line' | 'stat' | 'band' | 'threshold' | 'extremes';

/** A column per small chart: small multiples on shared axes, by row, by column, or both. */
export interface ChartFacet {
  readonly row?: string;
  readonly column?: string;
}

/** A chart's own calculated column (§3.2 item 3): the column editor's own kind, at the row or the group stage. */
export interface ChartCalc {
  readonly stage: 'row' | 'group';
  readonly column: DerivedColumn;
}

/** How deep a chart has drilled down its grid's row groupings: the value clicked at each level so far. */
export interface ChartDrill {
  /** The groupings, outermost first: the grid's rows when the drill began. */
  readonly levels: readonly string[];
  /** One value per level drilled into; the chart shows the level after the last. */
  readonly path: readonly (string | number | boolean | null)[];
}

export interface ChartSpec {
  readonly version: 1 | 2;
  readonly mark: ChartMark;
  /** The category (bar, line, area, pie, heatmap columns), the x value (scatter), or a KPI's trend (optional). */
  readonly x?: string;
  /** A date across, by its period (§3.2). */
  readonly grain?: TimeGrain;
  /** A number across, by its bins (§3.2). */
  readonly bins?: Bins;
  /** What is plotted. Scatter reads the first as its y; heatmap and pie the first as the value. */
  readonly y: readonly ChartMeasure[];
  /** A second dimension -- the colour slot: one series per value (bar, line, area), the rows of a heatmap. */
  readonly split?: string;
  /** A measure sizing each mark (a bubble). */
  readonly size?: ChartMeasure;
  /** A measure written on each mark instead of its own value (revenue's bars labelled with their margin). */
  readonly label?: ChartMeasure;
  /** Further measures a hover lists, beside the plotted ones. */
  readonly tooltip?: readonly ChartMeasure[];
  /** Small multiples: a chart per value. */
  readonly facet?: ChartFacet;
  /** Reference lines, bands, thresholds, pinned extremes. */
  readonly layers?: readonly ChartLayer[];
  /** The chart's own calculated columns. */
  readonly calcs?: readonly ChartCalc[];
  /** Drilled down the grid's groupings. */
  readonly drill?: ChartDrill;
  readonly options: ChartOptions;
  /**
   * Frozen: the chart keeps its own grouping. Absent, it is LIVE -- it
   * follows the cube's pivots (`followCube`) as they change.
   */
  readonly frozen?: boolean;
}

export interface ChartOptions {
  readonly orientation: 'vertical' | 'horizontal';
  readonly stack: 'none' | 'stacked' | 'percent';
  /** Order of the categories: by the category itself, or by the (first) value. */
  readonly sort: { readonly by: 'x' | 'y'; readonly direction: 'asc' | 'desc' };
  /** The most rows the chart asks for (top N after the sort). */
  readonly limit: number;
  readonly labels: boolean;
  readonly legend: 'top' | 'bottom' | 'right' | 'none';
  /** A KPI's target: its value is said against it -- above or below, by how much. */
  readonly target?: number;
  /** Which way is good for a KPI against its target: above it (higher, the default) or below it. */
  readonly better?: 'higher' | 'lower';
  /** A line drawn as a smooth curve. */
  readonly smooth?: boolean;
  /** A line drawn as steps. */
  readonly step?: boolean;
  /** The value axis on a log scale. */
  readonly logAxis?: boolean;
  /** Ranges of the value axis left out (an axis break): a tall outlier no longer flattens the rest. */
  readonly breaks?: readonly { readonly from: number; readonly to: number }[];
  /** Where a mark's label sits: on it (inside) or beyond its end (outside, the default). */
  readonly labelPosition?: 'inside' | 'outside';
  /** The labels across, turned (degrees): long names that would overlap. */
  readonly labelRotate?: number;
  /** A slider to zoom and pan across (a long series). */
  readonly zoom?: boolean;
  /** Drag across (or around, on a scatter) to filter the grid to a range. */
  readonly brush?: boolean;
  /** Pattern fills as well as colours, for readers who cannot tell colours apart (ECharts' decal). */
  readonly decal?: boolean;
  /** Hovering a series dims the rest (default on); false keeps every series as it is. */
  readonly dim?: boolean;
  /** The toolbox: save as a picture, see the chart's data, download it. */
  readonly toolbox?: boolean;
}

// The words a saved spec may use, each list exhaustive by construction (a Record over its union): a page's reader
// refuses a word it does not know, and Python's ll.Page writes these words and no others (python-vocabulary.test.ts).
const AGGREGATE_WORDS: Readonly<Record<string, true>> = Object.fromEntries([...AGGREGATE_FNS, 'value'].map((a) => [a, true]));
const MEASURE_MARK_WORDS: Readonly<Record<MeasureMark, true>> = { bar: true, line: true, area: true };
const AXIS_WORDS: Readonly<Record<MeasureAxis, true>> = { left: true, right: true };
const SHOW_AS_WORDS: Readonly<Record<ShowAsKind, true>> = {
  runningTotal: true, percentOfTotal: true, difference: true, percentChange: true, movingAverage: true, rank: true,
};
const GRAIN_WORDS: Readonly<Record<TimeGrain, true>> = { day: true, week: true, month: true, quarter: true, year: true };
const LAYER_WORDS: Readonly<Record<ChartLayerKind, true>> = {
  line: true, stat: true, band: true, threshold: true, extremes: true,
};
const STAT_WORDS: Readonly<Record<LayerStat, true>> = { average: true, min: true, max: true };

export const SHOW_AS_KINDS = Object.keys(SHOW_AS_WORDS) as ShowAsKind[];
export const TIME_GRAINS = Object.keys(GRAIN_WORDS) as TimeGrain[];
export const LAYER_STATS = Object.keys(STAT_WORDS) as LayerStat[];

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const known = (words: Readonly<Record<string, true>>, v: unknown): boolean => typeof v === 'string' && Object.hasOwn(words, v);

/** What is wrong with a measure as saved, or null. */
function measureProblem(m: unknown, what: string): string | null {
  if (!isRecord(m) || typeof m['column'] !== 'string' || !known(AGGREGATE_WORDS, m['fn'])) {
    return `${what} is not a column and an aggregate`;
  }
  if (m['mark'] !== undefined && !known(MEASURE_MARK_WORDS, m['mark'])) return `${what}'s mark ${JSON.stringify(m['mark'])} is not bar, line or area`;
  if (m['axis'] !== undefined && !known(AXIS_WORDS, m['axis'])) return `${what}'s axis ${JSON.stringify(m['axis'])} is not left or right`;
  const showAs = m['showAs'];
  if (showAs !== undefined && (!isRecord(showAs) || !known(SHOW_AS_WORDS, showAs['kind']))) {
    return `${what} is shown as something this DataCube does not know (${JSON.stringify(showAs)})`;
  }
  return null;
}

/**
 * What stops a saved spec from being read, in words, or null: its version, its mark and every word of version 2's
 * slots (a newer DataCube's word is refused by name, never drawn as something else). `marks` are the kinds this
 * DataCube draws.
 */
export function specProblem(spec: unknown, marks: readonly string[]): string | null {
  if (!isRecord(spec)) return 'it is not a chart';
  if (spec['version'] !== 1 && spec['version'] !== CHART_VERSION) {
    return `its version ${JSON.stringify(spec['version'] ?? null)} is not one this DataCube reads (1 to ${CHART_VERSION})`;
  }
  if (!marks.includes(spec['mark'] as string)) return `its kind ${JSON.stringify(spec['mark'] ?? null)} is not one this DataCube draws`;
  if (!Array.isArray(spec['y'])) return 'it plots nothing (no y)';
  if (!isRecord(spec['options'])) return 'it has no options';
  for (const [i, m] of spec['y'].entries()) {
    const p = measureProblem(m, `measure ${i + 1}`);
    if (p) return p;
  }
  for (const slot of ['size', 'label'] as const) {
    if (spec[slot] === undefined) continue;
    const p = measureProblem(spec[slot], `its ${slot}`);
    if (p) return p;
  }
  if (spec['tooltip'] !== undefined) {
    if (!Array.isArray(spec['tooltip'])) return 'its tooltip is not a list';
    for (const [i, m] of spec['tooltip'].entries()) {
      const p = measureProblem(m, `tooltip measure ${i + 1}`);
      if (p) return p;
    }
  }
  if (spec['grain'] !== undefined && !known(GRAIN_WORDS, spec['grain'])) {
    return `its grain ${JSON.stringify(spec['grain'])} is not one of ${TIME_GRAINS.join(', ')}`;
  }
  const bins = spec['bins'];
  if (bins !== undefined && (!isRecord(bins) || (typeof bins['width'] !== 'number' && typeof bins['count'] !== 'number'))) {
    return 'its bins have neither a width nor a count';
  }
  const facet = spec['facet'];
  if (facet !== undefined && (!isRecord(facet) || (typeof facet['row'] !== 'string' && typeof facet['column'] !== 'string'))) {
    return 'its facet names neither a row nor a column';
  }
  const layers = spec['layers'];
  if (layers !== undefined) {
    if (!Array.isArray(layers)) return 'its layers are not a list';
    for (const [i, l] of layers.entries()) {
      if (!isRecord(l) || !known(LAYER_WORDS, l['kind'])) return `layer ${i + 1} is of a kind this DataCube does not know (${JSON.stringify(isRecord(l) ? l['kind'] ?? null : l)})`;
      if (l['kind'] === 'stat' && !known(STAT_WORDS, l['stat'])) return `layer ${i + 1}'s statistic ${JSON.stringify(l['stat'] ?? null)} is not one of ${LAYER_STATS.join(', ')}`;
      if ((l['kind'] === 'line' || l['kind'] === 'threshold') && typeof l['value'] !== 'number') return `layer ${i + 1} has no value`;
      if (l['kind'] === 'band' && (l['from'] === undefined || l['to'] === undefined || (l['axis'] !== 'x' && l['axis'] !== 'y'))) {
        return `layer ${i + 1} (a band) needs an axis, a from and a to`;
      }
    }
  }
  const calcs = spec['calcs'];
  if (calcs !== undefined) {
    if (!Array.isArray(calcs)) return 'its calculated columns are not a list';
    for (const [i, c] of calcs.entries()) {
      if (!isRecord(c) || (c['stage'] !== 'row' && c['stage'] !== 'group') || !isRecord(c['column'])
        || typeof c['column']['name'] !== 'string') {
        return `calculated column ${i + 1} has no stage (row or group) and column`;
      }
    }
  }
  const drill = spec['drill'];
  if (drill !== undefined && (!isRecord(drill) || !Array.isArray(drill['levels']) || !Array.isArray(drill['path'])
    || drill['path'].length >= drill['levels'].length)) {
    return 'its drill path is not a path down its levels';
  }
  return null;
}

export const CHART_MARKS: readonly { value: ChartMark; label: string }[] = [
  { value: 'bar', label: 'Bar' },
  { value: 'line', label: 'Line' },
  { value: 'area', label: 'Area' },
  { value: 'scatter', label: 'Scatter' },
  { value: 'pie', label: 'Pie' },
  { value: 'heatmap', label: 'Heatmap' },
  { value: 'treemap', label: 'Treemap' },
  { value: 'kpi', label: 'KPI' },
];

export const DEFAULT_CHART_LIMIT = 50;
/** A date across asks for this many points: a time series is long, and its slider and point thinning keep it quick. */
export const TIME_LIMIT = 2000;
/** A scatter plots rows, not groups, so it may ask for more of them. */
export const SCATTER_LIMIT = 2000;

/** A chart's options before anyone sets one: a saved chart that leaves one out has it (page-document.ts). */
export const DEFAULT_OPTIONS: ChartOptions = {
  orientation: 'vertical',
  stack: 'none',
  sort: { by: 'y', direction: 'desc' },
  limit: DEFAULT_CHART_LIMIT,
  labels: false,
  legend: 'top',
};

/** The name a measure's column has in the chart's result. */
export function measureName(m: ChartMeasure): string {
  const base = baseName(m);
  return m.showAs ? showAsName(m.showAs.kind, base) : base;
}

/** The name a measure has before any show-as: its aggregate's column, or a group-level column's own name. */
export function baseName(m: ChartMeasure): string {
  // a group-level column is already one figure per group: it keeps its name
  if (m.fn === 'value') return m.column;
  return m.fn === 'count' ? 'count' : `${m.fn}_${m.column}`;
}

/**
 * The column the chart groups by across: its grain's column (a date by month), its bins' column, or `x` itself. The
 * drawing reads the categories from it; a click is said back on `x` (`markConditions`).
 */
export function axisName(spec: ChartSpec): string | undefined {
  if (spec.x === undefined) return undefined;
  if (spec.grain !== undefined) return grainName(spec.x, spec.grain);
  if (spec.bins !== undefined) return binName(spec.x);
  return spec.x;
}

/**
 * WHAT A BRUSH TOOK, as conditions on the cube's own columns (batch 2 item 11): a range across is from its first value
 * to its last -- a period's to the next period's start, a bin's to the next bin's edge -- for a date or a number, and
 * the values themselves (`in`) for anything else; a scatter's rectangle is its two columns' ranges. A drilled chart's
 * path comes with it.
 */
export function brushConditions(
  drilled: ChartSpec,
  s: CubeSnapshot,
  took: { readonly kind: 'across'; readonly values: readonly Scalar[]; readonly upper?: Scalar }
    | { readonly kind: 'rect'; readonly x: readonly [number, number]; readonly y: readonly [number, number] },
): FilterNode[] {
  const { spec, conditions } = drilledView(drilled);
  const plain = (v: Scalar): string | number | boolean | null => (typeof v === 'bigint' ? v.toString() : v);
  if (took.kind === 'rect') {
    const y = spec.y[0]?.column;
    if (spec.x === undefined || y === undefined) return [];
    const range = (column: string, [a, b]: readonly [number, number]): FilterNode[] => [
      { kind: 'condition', column, operator: 'greaterThanEqual', value: Math.min(a, b) },
      { kind: 'condition', column, operator: 'lessThanEqual', value: Math.max(a, b) },
    ];
    return [...conditions, ...range(spec.x, took.x), ...range(y, took.y)];
  }
  const column = spec.x;
  const values = took.values.filter((v) => v !== null);
  const lo = values[0];
  const hi = values[values.length - 1];
  if (column === undefined || lo === undefined || hi === undefined) return [...conditions];
  const type = rowColumns(s).find((c) => c.name === column)?.type;
  if (spec.grain !== undefined) {
    const [from] = rangeConditions(column, type, plain(lo), { grain: spec.grain });
    const [, to] = rangeConditions(column, type, plain(hi), { grain: spec.grain });
    return [...conditions, from!, to!];
  }
  if (spec.bins !== undefined || isNumeric(type) || isTemporal(type)) {
    const up = typeof took.upper === 'bigint' ? Number(took.upper) : took.upper;
    return [
      ...conditions,
      { kind: 'condition', column, operator: 'greaterThanEqual', value: plain(lo)! },
      spec.bins !== undefined && typeof up === 'number'
        ? { kind: 'condition', column, operator: 'lessThan', value: up }
        : { kind: 'condition', column, operator: 'lessThanEqual', value: plain(hi)! },
    ];
  }
  return [...conditions, { kind: 'condition', column, operator: 'in', value: values.map((v) => plain(v)!) as string[] }];
}

/**
 * A DRILLED chart as it draws (batch 2 item 10): the level after the path across, and the path's values as conditions
 * on the chart's own query (never the cube's filter). The column across's grain or bins were chosen for the top level
 * and go with it. Undrilled, the spec itself and no conditions.
 */
export function drilledView(spec: ChartSpec): { readonly spec: ChartSpec; readonly conditions: readonly FilterNode[] } {
  const drill = spec.drill;
  if (!drill || drill.path.length === 0) return { spec, conditions: [] };
  const x = drill.levels[drill.path.length];
  if (x === undefined) return { spec, conditions: [] };
  const { grain: _g, bins: _b, drill: _d, ...rest } = spec;
  return {
    spec: { ...rest, x },
    conditions: drill.path.map((v, i): FilterNode => (v === null
      ? { kind: 'condition', column: drill.levels[i]!, operator: 'isEmpty' }
      : { kind: 'condition', column: drill.levels[i]!, operator: 'equal', value: v })),
  };
}

/**
 * The levels a chart drills through: the grid's own row groupings from the column across down (region, then desk) --
 * or, a column across that is not one of them, that column then the groupings -- never the split. Null when there is
 * nothing below.
 */
export function drillLevels(spec: ChartSpec, s: CubeSnapshot): readonly string[] | null {
  if (spec.drill) return spec.drill.levels;
  if (spec.x === undefined || spec.mark === 'scatter' || spec.mark === 'kpi' || spec.mark === 'treemap') return null;
  const at = s.rows.indexOf(spec.x);
  const levels = (at >= 0 ? s.rows.slice(at) : [spec.x, ...s.rows]).filter((c) => c !== spec.split);
  return levels.length > 1 ? levels : null;
}

/** The chart one level down from a clicked mark's value across, or null at the bottom. */
export function drillDown(spec: ChartSpec, s: CubeSnapshot, value: Scalar): ChartSpec | null {
  const levels = drillLevels(spec, s);
  const path = spec.drill?.path ?? [];
  if (!levels || path.length + 1 >= levels.length) return null;
  const v = typeof value === 'bigint' ? value.toString() : value;
  return { ...spec, drill: { levels, path: [...path, v] } };
}

/** The chart back up to `depth` values of its path (0: the top, undrilled). */
export function drillUp(spec: ChartSpec, depth: number): ChartSpec {
  if (!spec.drill) return spec;
  const { drill, ...rest } = spec;
  return depth <= 0 ? rest : { ...rest, drill: { ...drill, path: drill.path.slice(0, depth) } };
}

/** The row-stage columns the chart's axis needs: a grain's, or its bins'. */
function axisCalcs(spec: ChartSpec): DerivedColumn[] {
  if (spec.x === undefined || spec.mark === 'scatter') return [];
  if (spec.grain !== undefined) return [grainColumn(spec.x, spec.grain)];
  if (spec.bins !== undefined) return binColumns(spec.x, spec.bins);
  return [];
}

/**
 * A clicked mark as conditions on the cube's own columns: a period of a grain from its first day to the next's, a bin
 * from its edge to the next bin's (`upper`, the next category's edge, when there is one), anything else its value.
 */
export function markConditions(
  spec: ChartSpec,
  s: CubeSnapshot,
  key: Readonly<Record<string, Scalar>>,
  upper?: Scalar,
): FilterNode[] {
  const typeOf = new Map(rowColumns(s).map((c) => [c.name, c.type]));
  // a drilled chart's mark is its path and its own value: the grid narrows to both
  const { spec: view, conditions } = drilledView(spec);
  spec = view;
  return [...conditions, ...Object.entries(key).flatMap(([column, raw]): FilterNode[] => {
    // a big integer as its digits, as the context menu's value filters do
    const value = typeof raw === 'bigint' ? raw.toString() : raw;
    if (column === spec.x && (spec.grain !== undefined || spec.bins !== undefined)) {
      const up = typeof upper === 'bigint' ? Number(upper) : upper;
      return rangeConditions(column, typeOf.get(column), value, {
        ...(spec.grain !== undefined ? { grain: spec.grain } : { bin: true }),
        ...(typeof up === 'number' ? { upper: up } : {}),
      });
    }
    return value === null
      ? [{ kind: 'condition', column, operator: 'isEmpty' }]
      : [{ kind: 'condition', column, operator: 'equal', value }];
  })];
}

/**
 * The group-level columns a chart can plot as they are (`fn: 'value'`): the cube's (a margin, a share, a running
 * figure) and the chart's own group-stage calculations. A child aggregate is the grid's alone (it is computed from a
 * second query per level) and is not offered.
 */
export function groupLevelColumns(s: CubeSnapshot, spec?: ChartSpec): DerivedColumn[] {
  return [
    ...(s.groupDerived ?? []).filter((d) => !d.childAggregate),
    ...(spec?.calcs ?? []).filter((c) => c.stage === 'group').map((c) => c.column),
  ];
}

/** Every column name a calculated column reads: its expression's properties, or its window's columns. */
export function columnsRead(d: DerivedColumn): string[] {
  if (d.window) {
    return [
      ...(d.window.column !== undefined ? [d.window.column] : []),
      ...d.window.partition,
      ...d.window.order.map((o) => o.column),
    ];
  }
  if (d.childAggregate) return [d.childAggregate.of];
  const out = new Set<string>();
  const walk = (n: unknown): void => {
    if (Array.isArray(n)) { n.forEach(walk); return; }
    if (!n || typeof n !== 'object') return;
    const o = n as Record<string, unknown>;
    if (o['_type'] === 'property' && typeof o['property'] === 'string') out.add(o['property']);
    for (const v of Object.values(o)) walk(v);
  };
  walk(d.lambda);
  return [...out];
}

/** The dimensions a chart can use, and the columns it can measure. */
export function chartColumns(s: CubeSnapshot, spec?: ChartSpec): {
  dimensions: RowColumn[];
  measures: RowColumn[];
  /** Group-level columns, plotted as they are (`fn: 'value'`). */
  groupLevel: DerivedColumn[];
} {
  const all = [...rowColumns(s), ...chartRowCalcs(spec)];
  return {
    dimensions: all.filter((c) => c.kind === 'dimension'),
    measures: all.filter((c) => isNumeric(c.type)),
    groupLevel: groupLevelColumns(s, spec),
  };
}

/** A chart's own row-stage calculations, as the columns they add. */
function chartRowCalcs(spec: ChartSpec | undefined): RowColumn[] {
  return (spec?.calcs ?? []).filter((c) => c.stage === 'row').map((c): RowColumn => ({
    name: c.column.name,
    ...(c.column.type === undefined ? {} : { type: c.column.type }),
    kind: c.column.kind ?? defaultKind(c.column.type),
    derived: true,
  }));
}

/**
 * A first chart for a cube, from what the cube already says: its first row
 * dimension (else the first dimension) across, its first measure (else the
 * first number, summed) up, and its second row dimension as the split. A
 * date across is a line; anything else a bar.
 */
export function defaultChart(s: CubeSnapshot): ChartSpec | null {
  const { dimensions, measures } = chartColumns(s);
  const byName = new Map(rowColumns(s).map((c) => [c.name, c]));
  const x = s.rows[0] ?? dimensions[0]?.name;
  const split = s.rows[1];
  // the cube's own measure; else a column it treats as a measure, a fraction
  // first (money, a quantity) -- every number is a measure by default (D3),
  // and an id or a year summed is the worst first chart; else a count
  const configured = s.measures.find((m) => m.fn !== 'wavg');
  const asMeasures = measures.filter((c) => c.kind === 'measure');
  const measureKind = asMeasures.find((c) => isFractional(c.type)) ?? asMeasures[0];
  const y: ChartMeasure | undefined = configured
    ? { column: configured.column, fn: configured.fn }
    : measureKind ? { column: measureKind.name, fn: 'sum' } : undefined;
  if (x === undefined) return null;
  const temporal = isTemporal(byName.get(x)?.type);
  return {
    version: CHART_VERSION,
    mark: temporal ? 'line' : 'bar',
    x,
    y: y ? [y] : [{ column: x, fn: 'count' }],
    ...(split !== undefined ? { split } : {}),
    options: {
      ...DEFAULT_OPTIONS,
      // time runs left to right, and long; categories lead with the largest
      sort: temporal ? { by: 'x', direction: 'asc' } : DEFAULT_OPTIONS.sort,
      ...(temporal ? { limit: TIME_LIMIT } : {}),
    },
  };
}

/**
 * A live chart, regrouped the way the cube is now: its first row group
 * across; its second row group -- else its first column label -- as the
 * split; the cube's own measure plotted, when it has one. The mark and the
 * options are the chart's; the order follows what is across (a date runs
 * in time order, anything else leads with the largest). A frozen chart,
 * a scatter (it plots values, not groups) and a cube with nothing grouped
 * are left as they are.
 */
export function followCube(spec: ChartSpec, s: CubeSnapshot): ChartSpec {
  // a drilled chart stays where it was taken until it is taken back up
  if (spec.frozen || spec.mark === 'scatter' || (spec.drill?.path.length ?? 0) > 0) return spec;
  // a KPI follows the cube's measure; its trend's column is its own
  if (spec.mark === 'kpi') {
    const measure = s.measures.find((m) => m.fn !== 'wavg');
    if (!measure) return spec;
    const y = [{ column: measure.column, fn: measure.fn }];
    return JSON.stringify(y) === JSON.stringify(spec.y) ? spec : { ...spec, y };
  }
  const x = s.rows[0] ?? s.pivotOn[0];
  if (x === undefined) return spec;
  const split = s.rows[0] !== undefined ? (s.rows[1] ?? s.pivotOn[0]) : s.pivotOn[1];
  const configured = s.measures.find((m) => m.fn !== 'wavg');
  const y = configured ? [{ column: configured.column, fn: configured.fn }] : spec.y;
  // a grain or bins belong to the column they were chosen for: a new column across starts without them
  const { split: _old, grain: _g, bins: _b, ...rest } = spec;
  const kept = x === spec.x ? {
    ...(spec.grain !== undefined ? { grain: spec.grain } : {}),
    ...(spec.bins !== undefined ? { bins: spec.bins } : {}),
  } : {};
  const sort = x === spec.x ? spec.options.sort
    : isTemporal(rowColumns(s).find((c) => c.name === x)?.type)
      ? { by: 'x' as const, direction: 'asc' as const }
      : { by: 'y' as const, direction: 'desc' as const };
  // a date newly across asks for a time series' points, unless the chart was told how many
  const toTime = x !== spec.x && sort.by === 'x' && isTemporal(rowColumns(s).find((c) => c.name === x)?.type)
    && spec.options.limit === DEFAULT_CHART_LIMIT;
  const next: ChartSpec = {
    ...rest,
    x,
    ...kept,
    y,
    ...(split !== undefined ? { split } : {}),
    options: { ...spec.options, sort, ...(toTime ? { limit: TIME_LIMIT } : {}) },
  };
  return JSON.stringify(next) === JSON.stringify(spec) ? spec : next;
}

/** What stops a spec from being drawn, in words; empty when it can be. */
export function chartProblems(spec: ChartSpec, s: CubeSnapshot): string[] {
  const known = new Set([...rowColumns(s), ...chartRowCalcs(spec)].map((c) => c.name));
  const groupLevel = new Set(groupLevelColumns(s, spec).map((d) => d.name));
  const out: string[] = [];
  // a KPI's column across is its trend, and it may have none
  if (spec.x === undefined && spec.mark !== 'kpi') out.push('Pick a column across.');
  else if (spec.x !== undefined && !known.has(spec.x)) out.push(`The cube has no column '${spec.x}'.`);
  if (spec.y.length === 0) out.push('Pick something to plot.');
  for (const m of [...spec.y, ...(spec.tooltip ?? []), ...(spec.size ? [spec.size] : []), ...(spec.label ? [spec.label] : [])]) {
    if (m.fn === 'value') {
      if (spec.mark === 'scatter') out.push(`A scatter plots rows, not groups: '${m.column}' is a group-level column.`);
      else if (!groupLevel.has(m.column)) out.push(`The cube has no group-level column '${m.column}'.`);
    } else if (m.fn !== 'count' && !known.has(m.column)) {
      out.push(`The cube has no column '${m.column}'.`);
    }
  }
  if (spec.split !== undefined && !known.has(spec.split)) {
    out.push(`The cube has no column '${spec.split}'.`);
  }
  if (spec.mark === 'heatmap' && spec.split === undefined) {
    out.push('A heatmap needs a second column, for its rows.');
  }
  // a grain takes a date to its period, bins a number to its bin: on any other column they mean nothing
  const xType = [...rowColumns(s), ...chartRowCalcs(spec)].find((c) => c.name === spec.x)?.type;
  if (spec.grain !== undefined && spec.x !== undefined && known.has(spec.x) && !isTemporal(xType)) {
    out.push(`'${spec.x}' is not a date: it has no ${spec.grain}.`);
  }
  if (spec.bins !== undefined && spec.x !== undefined && known.has(spec.x) && !isNumeric(xType)) {
    out.push(`'${spec.x}' is not a number: it has no bins.`);
  }
  if (spec.grain !== undefined && spec.bins !== undefined) out.push('A column across has a grain or bins, not both.');
  if (spec.split !== undefined && spec.split === spec.x) {
    out.push('Split by a different column than the one across.');
  }
  return out;
}

/**
 * The cube's snapshot, regrouped for the chart.
 *
 * Kept from the cube: the source, the filter, the calculated columns.
 * Replaced: the grouping (x and split), the measures (the chart's), the
 * pivot (none), the sorts (the chart's order). Only the columns the chart
 * reads are kept, so a grouped query does not carry every other column
 * along as a "unique value".
 */
export function chartSnapshot(s: CubeSnapshot, drilled: ChartSpec): CubeSnapshot {
  // drilled, the level after the path across and the path as the chart's own conditions
  const { spec, conditions } = drilledView(drilled);
  const filter: FilterNode | undefined = conditions.length === 0 ? s.filter
    : { kind: 'and', children: [...(s.filter ? [s.filter] : []), ...conditions] };
  const scatter = spec.mark === 'scatter';
  const across = axisName(spec);
  const keys = [scatter ? spec.x : across, spec.split].filter((c): c is string => c !== undefined);
  // what the chart plots, and the measures a hover lists, a mark's size reads and its label says
  const extras = [...(spec.tooltip ?? []), ...(spec.size ? [spec.size] : []), ...(spec.label ? [spec.label] : [])];
  const plotted = [...spec.y, ...extras];
  const aggregated = plotted.filter((m): m is ChartMeasure & { fn: AggregateFn } => m.fn !== 'value');
  // a measure's name before any show-as: two measures shown two ways of one aggregate share it
  const measures: Measure[] = scatter ? [] : [...new Map(aggregated.map((m) => [baseName(m), {
    name: baseName(m),
    column: m.column,
    fn: m.fn,
  }] as const)).values()];
  // A GROUP-LEVEL COLUMN plotted as it is: kept, with every group-level column it reads (a share reads a window, a
  // window a measure) and every measure of the cube's they read, under the names they read them by -- the expression
  // was written against those names.
  const groupLevel = new Map(groupLevelColumns(s, spec).map((d) => [d.name, d]));
  const cubeMeasures = new Map(s.measures.map((m) => [m.name, m]));
  const keptGroup: DerivedColumn[] = [];
  const keptMeasures: Measure[] = [];
  const seen = new Set<string>(measures.map((m) => m.name));
  const need = (name: string): void => {
    if (seen.has(name)) return;
    seen.add(name);
    const d = groupLevel.get(name);
    if (d) {
      columnsRead(d).forEach(need);
      keptGroup.push(d);
      return;
    }
    const m = cubeMeasures.get(name);
    if (m) keptMeasures.push(m);
  };
  if (!scatter) plotted.filter((m) => m.fn === 'value').forEach((m) => need(m.column));
  // A SHOW-AS: group-level columns over the chart's rows, after what they read -- along the axis, restarting per series
  const shown: DerivedColumn[] = [];
  if (!scatter) {
    const named = new Set<string>();
    for (const m of plotted) {
      if (!m.showAs || named.has(measureName(m))) continue;
      named.add(measureName(m));
      shown.push(...showAsColumns(m.showAs, baseName(m), across !== undefined ? [across] : [],
        spec.split !== undefined ? [spec.split] : []));
    }
  }
  // A REFERENCE LINE'S VALUE is the database's: a measure's average, minimum or maximum over every row of the chart,
  // a group-level window over them all (calc-presets.ts statColumn); pinned extremes are the maximum and the minimum
  if (!scatter) {
    const named = new Set(shown.map((d) => d.name));
    const stat = (st: LayerStat, m: ChartMeasure | undefined): void => {
      if (!m) return;
      const d = statColumn(st, measureName(m));
      if (named.has(d.name)) return;
      named.add(d.name);
      shown.push(d);
    };
    for (const l of spec.layers ?? []) {
      if (l.kind === 'stat') stat(l.stat, spec.y[l.measure ?? 0]);
      if (l.kind === 'extremes') {
        stat('max', spec.y[l.measure ?? 0]);
        stat('min', spec.y[l.measure ?? 0]);
      }
    }
  }
  const rowCalcs = [...axisCalcs(spec), ...(spec.calcs ?? []).filter((c) => c.stage === 'row').map((c) => c.column)];
  // what the chart reads, and what the cube's filter reads (its literals are
  // written by their columns' types, so those columns must stay typed)
  const reads = new Set([
    ...keys,
    ...aggregated.map((m) => m.column),
    // a scatter plots rows: its columns as they are, a bubble's size and a hover's measures among them
    ...(scatter ? plotted.map((m) => m.column) : []),
    ...(spec.x !== undefined ? [spec.x] : []),
    ...keptMeasures.flatMap((m) => [m.column, ...(m.weight !== undefined ? [m.weight] : [])]),
    ...rowCalcs.flatMap(columnsRead),
    ...filterColumns(filter),
  ]);
  const first = spec.y[0];
  const sorts = scatter ? [] : spec.options.sort.by === 'y' && first
    ? [{ column: measureName(first), direction: spec.options.sort.direction }]
    : across !== undefined ? [{ column: across, direction: spec.options.sort.direction }] : [];
  const {
    pivotValues: _pv, pivotTotal: _pt, groupDerived: _gd, window: _w,
    leafCount: _lc, childCount: _cc, ...rest
  } = s as CubeSnapshot & Record<string, unknown>;
  // THE GRID AGGREGATES A COLUMN ONCE (query.ts `groupedAggs` keys its measures by column): a second measure of the
  // same column -- the chart's max beside the sum a margin reads -- reads a copy of it, `$x.<column>`, as a child
  // aggregate does
  const copies: DerivedColumn[] = [];
  const used = new Set<string>();
  const allMeasures = [...measures, ...keptMeasures].map((m): Measure => {
    if (!used.has(m.column)) {
      used.add(m.column);
      return m;
    }
    const copy = `__chart_${m.name}`;
    copies.push({ name: copy, lambda: lambda(['x'], property(variable('x'), m.column)), kind: 'measure' });
    return { ...m, column: copy };
  });
  return {
    ...(rest as CubeSnapshot),
    columns: s.columns.filter((c) => reads.has(c.name)),
    derived: [...s.derived, ...rowCalcs, ...copies],
    ...(filter ? { filter } : {}),
    rows: scatter ? [] : keys,
    pivotOn: [],
    measures: allMeasures,
    ...(keptGroup.length + shown.length > 0 ? { groupDerived: [...keptGroup, ...shown] } : {}),
    sorts,
  };
}

/** Every column a filter tree names. */
function filterColumns(node: FilterNode | undefined): string[] {
  if (!node) return [];
  switch (node.kind) {
    case 'condition':
      return [node.column, ...(node.rightColumn !== undefined ? [node.rightColumn] : [])];
    case 'not':
      return filterColumns(node.child);
    default:
      return node.children.flatMap(filterColumns);
  }
}

/** The most points a KPI's trend asks for: more than a year of days. */
export const KPI_TREND_LIMIT = 500;

/**
 * A KPI's queries: its value -- the measure over every row of the cube's query, its filter applied, no grouping:
 * one row -- and, given a column across, its trend: the measure by that column, its LATEST values first (a long series
 * shows where it is now), one past the most it draws, so a trend cut short is known to be.
 */
export function kpiQueries(s: CubeSnapshot, spec: ChartSpec): {
  readonly total: ReturnType<typeof chartQuery>;
  readonly trend?: ReturnType<typeof chartQuery>;
} {
  const { x, split: _split, ...value } = spec;
  const total = chartQuery(s, value);
  if (x === undefined) return { total };
  const trend = chartQuery(s, {
    ...value, x, mark: 'line', options: { ...spec.options, sort: { by: 'x', direction: 'desc' }, limit: KPI_TREND_LIMIT + 1 },
  });
  return { total, trend };
}

/** The chart's query and the scope it runs at. */
export function chartQuery(s: CubeSnapshot, spec: ChartSpec): { query: Lambda; snapshot: CubeSnapshot; scope: LevelScope } {
  const snapshot = chartSnapshot(s, spec);
  const limit = spec.mark === 'scatter' ? SCATTER_LIMIT : spec.options.limit;
  const scope: LevelScope = { level: snapshot.rows.length, parent: [], limit };
  return { query: levelLambda(snapshot, scope), snapshot, scope };
}
