// PRESETS: the calculations a chart (and the grid) offers by name, as the calculated columns DataCube already has
// (docs/DATACUBE_CHARTS_DESIGN_2026_10_09.md §3.2). Nothing here computes a value: each preset is a `DerivedColumn`
// -- an expression over the row, or a window -- that the planner plans and the database computes, exactly as a column
// typed into the column editor is. The chart keeps a preset as its WORDS (`grain: 'month'`,
// `showAs: { kind: 'runningTotal' }`) and expands it here when its query is built, so Python writes the same words and
// never a copy of this (one-platform rule 1); the grid's header menu adds the same columns to the cube.
//
//   grain      a date taken to its period: the compiler's firstDayOfWeek / Month / Quarter / Year, or the day itself
//   bins       a number taken to its bin: floor(v / width) * width, or `count` bins over the column's range
//   show as    over the chart's rows, after grouping: a running total, a share of the total, the change from the row
//              before (absolute or as a ratio), a moving average, a rank
//   stats      a measure's average, minimum or maximum over the chart's rows: a reference line's value

import {
  divide,
  fn,
  lambda,
  lit,
  minus,
  plus,
  property,
  times,
  variable,
  type Lambda,
  type ValueSpecification,
} from '../../pure-protocol/src/index.ts';
import type { Bins, LayerStat, ShowAs, ShowAsKind, TimeGrain } from './chart-spec.ts';
import type { DerivedColumn, FilterNode, SortSpec } from './snapshot.ts';

const x = (): ValueSpecification => variable('x');
const col = (name: string): ValueSpecification => property(x(), name);
/** A column's value, `$x.<name>->toOne()`: a column read from a frame may be empty, and arithmetic takes one value. */
const one = (name: string): ValueSpecification => fn('toOne', col(name));
const row = (body: ValueSpecification): Lambda => lambda(['x'], body);
const number = (n: number): ValueSpecification => (Number.isInteger(n) ? lit.integer(n) : lit.float(n));

// ---------------------------------------------------------------- grain

/** The compiler's function taking a date to its period's first day; `day` of a date is the date itself. */
const GRAIN_FUNCTION: Readonly<Record<TimeGrain, string>> = {
  day: 'datePart',
  week: 'firstDayOfWeek',
  month: 'firstDayOfMonth',
  quarter: 'firstDayOfQuarter',
  year: 'firstDayOfYear',
};

/** Words for a grain, as a menu says them. */
export const GRAIN_LABELS: Readonly<Record<TimeGrain, string>> = {
  day: 'Day', week: 'Week', month: 'Month', quarter: 'Quarter', year: 'Year',
};

/** The column a grain makes of `column`: its name. */
export function grainName(column: string, grain: TimeGrain): string {
  return `${column}__${grain}`;
}

/**
 * A date column taken to its period, a row-stage dimension: `$x.d->toOne()->firstDayOfMonth()`; by day, its date part
 * (a DateTime's day; a StrictDate's itself).
 */
export function grainColumn(column: string, grain: TimeGrain, name = grainName(column, grain)): DerivedColumn {
  return { name, lambda: row(fn(GRAIN_FUNCTION[grain], one(column))), kind: 'dimension' };
}

/** A day, `YYYY-MM-DD`, moved on by one period of `grain` (the end of the period it starts, exclusive). */
export function nextPeriod(day: string, grain: TimeGrain): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(day);
  if (!m) throw new Error(`not a day: ${day}`);
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  switch (grain) {
    case 'day': d.setUTCDate(d.getUTCDate() + 1); break;
    case 'week': d.setUTCDate(d.getUTCDate() + 7); break;
    case 'month': d.setUTCMonth(d.getUTCMonth() + 1); break;
    case 'quarter': d.setUTCMonth(d.getUTCMonth() + 3); break;
    case 'year': d.setUTCFullYear(d.getUTCFullYear() + 1); break;
  }
  return d.toISOString().slice(0, 10);
}

// ---------------------------------------------------------------- bins

/** The column bins make of `column`: its name. */
export function binName(column: string): string {
  return `${column}__bin`;
}

/**
 * A number column taken to its bin's lower edge, a row-stage dimension. `width`: `floor($x.v / w) * w`. `count`: that
 * many bins over the column's range -- its minimum and maximum are windows over every row (before the grid's filter,
 * so the bins stay put as the filter changes), the edge `min + min(floor((v - min) / w), count - 1) * w` with
 * `w = (max - min) / count`: the database's arithmetic, every step.
 */
export function binColumns(column: string, bins: Bins): DerivedColumn[] {
  const name = binName(column);
  if (bins.width !== undefined) {
    if (!(bins.width > 0)) throw new Error('a bin is wider than nothing');
    const w = number(bins.width);
    return [{ name, lambda: row(times(fn('floor', divide(one(column), w)), w)), kind: 'dimension' }];
  }
  const count = Math.floor(bins.count ?? 0);
  if (!(count >= 1)) throw new Error('bins: a width, or a count of at least one');
  const lo = `${name}__min`;
  const hi = `${name}__max`;
  const whole = { partition: [] as string[], order: [] as SortSpec[], frame: 'partition' as const };
  const width = divide(minus(one(hi), one(lo)), lit.integer(count));
  const index = fn('floor', divide(minus(one(column), one(lo)), width));
  const clamped = fn('if', fn('greaterThanEqual', index, lit.integer(count)),
    lambda([], lit.integer(count - 1)), lambda([], index));
  const edge = fn('if', fn('equal', one(hi), one(lo)), lambda([], one(lo)), lambda([], plus(one(lo), times(clamped, width))));
  return [
    { name: lo, window: { ...whole, fn: 'min', column }, kind: 'measure' },
    { name: hi, window: { ...whole, fn: 'max', column }, kind: 'measure' },
    { name, lambda: row(edge), kind: 'dimension' },
  ];
}

// ---------------------------------------------------------------- show as

/** Words for each show-as, as a menu says them. */
export const SHOW_AS_LABELS: Readonly<Record<ShowAsKind, string>> = {
  runningTotal: 'Running total',
  percentOfTotal: '% of total',
  difference: 'Difference from previous',
  percentChange: '% change from previous',
  movingAverage: 'Moving average',
  rank: 'Rank',
};

/** Whether a show-as is a ratio (a share, a change): it reads as a percentage. */
export function isRatio(kind: ShowAsKind): boolean {
  return kind === 'percentOfTotal' || kind === 'percentChange';
}

/** The name a measure shown as `kind` has: `runningTotal_sum_notional`. */
export function showAsName(kind: ShowAsKind, base: string): string {
  return `${kind}_${base}`;
}

/**
 * A measure shown as something computed from it, as GROUP-LEVEL columns over the chart's rows: `base` is the
 * measure's column after grouping, `along` the order the rows run in (the column across, ascending), `within` the
 * columns that restart it (a split: each series its own running total). The last column returned is the one plotted,
 * `name`; any it reads are named after it (`<name>__total`, `<name>__previous`).
 */
export function showAsColumns(
  show: ShowAs,
  base: string,
  along: readonly string[],
  within: readonly string[],
  name = showAsName(show.kind, base),
): DerivedColumn[] {
  const order: SortSpec[] = along.map((column) => ({ column, direction: 'asc' }));
  const partition = [...within];
  switch (show.kind) {
    case 'runningTotal':
      return [{ name, window: { fn: 'sum', column: base, partition, order, frame: 'running' } }];
    case 'movingAverage':
      return [{ name, window: { fn: 'average', column: base, partition, order, frame: { lastRows: Math.max(1, Math.floor(show.rows ?? 3)) } } }];
    case 'rank':
      return [{ name, window: { fn: 'rank', partition, order: [{ column: base, direction: 'desc' }] } }];
    case 'percentOfTotal': {
      const total = `${name}__total`;
      return [
        { name: total, window: { fn: 'sum', column: base, partition: [], order: [], frame: 'partition' } },
        { name, lambda: row(divide(one(base), one(total))) },
      ];
    }
    case 'difference':
    case 'percentChange': {
      const before = `${name}__previous`;
      // the first row has no row before: its change is empty (SQL's NULL through toOne), never a guess
      const change = minus(one(base), one(before));
      return [
        { name: before, window: { fn: 'lag', column: base, partition, order } },
        { name, lambda: row(show.kind === 'difference' ? change : divide(change, one(before))) },
      ];
    }
  }
}

// ---------------------------------------------------------------- stats

/** The name a measure's statistic over the chart's rows has: `average_of_sum_notional`. */
export function statName(stat: LayerStat, measure: string): string {
  return `${stat}_of_${measure}`;
}

/** A measure's average, minimum or maximum over every row of the chart: a group-level window over all of them. */
export function statColumn(stat: LayerStat, measure: string): DerivedColumn {
  return { name: statName(stat, measure), window: { fn: stat, column: measure, partition: [], order: [], frame: 'partition' } };
}

// ---------------------------------------------------------------- a clicked period or bin

/**
 * A mark's value across, as conditions on the cube's own column: a period (a grain) is from its first day to the
 * next's; a bin from its edge to `upper`, the next bin's edge (the last bin has none: from its edge up); anything else
 * is the value itself.
 */
export function rangeConditions(
  column: string,
  type: string | undefined,
  value: string | number | boolean | null,
  how: { readonly grain?: TimeGrain; readonly bin?: boolean; readonly upper?: number },
): FilterNode[] {
  if (value === null) return [{ kind: 'condition', column, operator: 'isEmpty' }];
  if (how.grain !== undefined) {
    const day = String(value).slice(0, 10);
    const literal = (d: string): string => (type !== undefined && /StrictDate$/.test(type) ? d : `${d}T00:00:00`);
    return [
      { kind: 'condition', column, operator: 'greaterThanEqual', value: literal(day) },
      { kind: 'condition', column, operator: 'lessThan', value: literal(nextPeriod(day, how.grain)) },
    ];
  }
  if (how.bin === true && typeof value === 'number') {
    return [
      { kind: 'condition', column, operator: 'greaterThanEqual', value },
      ...(how.upper !== undefined ? [{ kind: 'condition', column, operator: 'lessThan', value: how.upper } as const] : []),
    ];
  }
  return [{ kind: 'condition', column, operator: 'equal', value }];
}
