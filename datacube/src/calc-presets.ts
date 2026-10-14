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
  collection,
  divide,
  enumValue,
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
import type { DerivedColumn, FilterNode, SortSpec, WindowSpec } from './snapshot.ts';

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

/** One period of each grain, as the compiler's `adjust` takes it: how many of which DurationUnit. */
const GRAIN_STEP: Readonly<Record<TimeGrain, readonly [number, string]>> = {
  day: [1, 'DAYS'], week: [7, 'DAYS'], month: [1, 'MONTHS'], quarter: [3, 'MONTHS'], year: [1, 'YEARS'],
};

/** The column that names each row's period's END: its name. */
export function upperName(axis: string): string {
  return `${axis}__upper`;
}

/**
 * The end of each row's period, exclusive -- the next period's first day: `adjust(<its first day>, 1, MONTHS)`. The
 * DATABASE's date arithmetic, so a click on a month filters to the month the database meant (the browser compares
 * with it, never computes it).
 */
export function grainEndColumn(column: string, grain: TimeGrain, name: string): DerivedColumn {
  const [n, unit] = GRAIN_STEP[grain];
  const start = fn(GRAIN_FUNCTION[grain], one(column));
  return {
    name: upperName(name),
    lambda: row(fn('adjust', start, lit.integer(n), enumValue('meta::pure::functions::date::DurationUnit', unit))),
    kind: 'measure',
  };
}

// ---------------------------------------------------------------- bins

/** The column bins make of `column`: its name. */
export function binName(column: string): string {
  return `${column}__bin`;
}

/**
 * A number column taken to its bin's lower edge, a row-stage dimension. `width`: `floor($x.v / w) * w` (`floorOf`: an edge
 * value in its own bin). `count`: that
 * many bins over the column's range -- its minimum and maximum are windows over every row (before the grid's filter,
 * so the bins stay put as the filter changes), the edge `min + min(floor((v - min) / w), count - 1) * w` with
 * `w = (max - min) / count`: the database's arithmetic, every step.
 */
export function binColumns(column: string, bins: Bins): DerivedColumn[] {
  const name = binName(column);
  if (bins.width !== undefined) {
    if (!(bins.width > 0)) throw new Error('a bin is wider than nothing');
    const w = number(bins.width);
    const edge = times(floorOf(divide(one(column), w)), w);
    return [
      { name, lambda: row(edge), kind: 'dimension' },
      // the bin's upper edge, exclusive: the database's, so a click or a brush filters to the bin it meant
      { name: upperName(name), lambda: row(plus(edge, w)), kind: 'measure' },
    ];
  }
  const count = Math.floor(bins.count ?? 0);
  if (!(count >= 1)) throw new Error('bins: a width, or a count of at least one');
  const lo = `${name}__min`;
  const hi = `${name}__max`;
  const whole = { partition: [] as string[], order: [] as SortSpec[], frame: 'partition' as const };
  const width = divide(minus(one(hi), one(lo)), lit.integer(count));
  const index = floorOf(divide(minus(one(column), one(lo)), width));
  const clamped = fn('if', fn('greaterThanEqual', index, lit.integer(count)),
    lambda([], lit.integer(count - 1)), lambda([], index));
  const edge = fn('if', fn('equal', one(hi), one(lo)), lambda([], one(lo)), lambda([], plus(one(lo), times(clamped, width))));
  // the upper edge, exclusive: the next bin's edge -- for the maximum (in the last bin, unclamped) one bin past it, so
  // `< upper` keeps it; a bin's rows agree on it but for that one, and the chart takes their maximum
  const upper = fn('if', fn('equal', one(hi), one(lo)), lambda([], plus(one(lo), lit.integer(1))),
    lambda([], plus(one(lo), times(plus(index, lit.integer(1)), width))));
  return [
    { name: lo, window: { ...whole, fn: 'min', column }, kind: 'measure' },
    { name: hi, window: { ...whole, fn: 'max', column }, kind: 'measure' },
    { name, lambda: row(edge), kind: 'dimension' },
    { name: upperName(name), lambda: row(upper), kind: 'measure' },
  ];
}

/**
 * A quotient's whole part, the database's: `floor(round(q, 9))`. A float quotient lands just under a whole number it
 * means -- 0.15 / 0.05 is 2.9999999999999996 -- and its floor would put an edge value in the bin below; rounded to nine
 * places first, it is 3.
 */
function floorOf(quotient: ValueSpecification): ValueSpecification {
  return fn('floor', fn('round', quotient, lit.integer(9)));
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
        { name: total, window: wholeOf('sum', base) },
        { name, lambda: row(divide(one(base), one(total))) },
      ];
    }
    case 'difference':
    case 'percentChange': {
      const before = `${name}__previous`;
      // the first row has no row before: its change is empty (SQL's NULL through toOne), never a guess; and a change
      // from nothing has no ratio -- empty too, where a database would refuse a division by zero
      const change = minus(one(base), one(before));
      const ratio = fn('if', fn('equal', one(before), lit.integer(0)), lambda([], collection([])),
        lambda([], divide(change, one(before))));
      return [
        { name: before, window: { fn: 'lag', column: base, partition, order } },
        { name, lambda: row(show.kind === 'difference' ? change : ratio) },
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
  return { name: statName(stat, measure), window: wholeOf(stat, measure) };
}

/**
 * A figure over EVERY row of the chart -- a total, an average -- as a window: the whole partition, in the order of the
 * column it reads. That order is said, not left out: a group-level window with no order takes the level's (query.ts
 * extendDerived), which may be the very column being computed (a chart sorted by its share of the total).
 */
function wholeOf(fn: 'sum' | LayerStat, column: string): WindowSpec {
  return { fn, column, partition: [], order: [{ column, direction: 'asc' }], frame: 'partition' };
}

// ---------------------------------------------------------------- a clicked period or bin

/**
 * A period or a bin as conditions on the cube's own column: from its start to `upper`, the database's end of it
 * (exclusive: the next period's first day, the next bin's edge). A literal is written by the column's type: a day for
 * a StrictDate, a timestamp for a DateTime, the number for a number -- another type has no range here, and is refused.
 */
export function rangeConditions(
  column: string,
  type: string | undefined,
  from: string | number | boolean,
  upper: string | number | boolean,
): FilterNode[] {
  const literal = (v: string | number | boolean): string | number | boolean => {
    if (typeof v !== 'string') return v;
    if (type !== undefined && /StrictDate$/.test(type)) return v.slice(0, 10);
    if (type !== undefined && /(DateTime|Timestamp)$/.test(type)) return v.length <= 10 ? `${v}T00:00:00` : v;
    throw new Error(`a range of '${column}' (${type ?? 'untyped'}) has no literal for ${JSON.stringify(v)}`);
  };
  return [
    { kind: 'condition', column, operator: 'greaterThanEqual', value: literal(from) },
    { kind: 'condition', column, operator: 'lessThan', value: literal(upper) },
  ];
}
