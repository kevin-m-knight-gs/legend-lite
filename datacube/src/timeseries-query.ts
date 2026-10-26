// A TIMESERIES' QUERIES (docs/DATACUBE_TIMESERIES_DESIGN_2026_10_25.md §5): each series of a timeseries tile
// (timeseries-spec.ts) as ONE query (D2) -- of its grid, or, a series combining grids, of their tables over one model --
// which the planner plans and the DATABASE computes: every return, volatility, z-score and drawdown a window over the
// dates or an expression over the row, as every calculated column of DataCube's is (calc-presets.ts). Nothing here
// computes a value; it writes what to compute, in Pure (D5).
//
//   1. each source the series reads: its grid's rows -- their source, row-level calculated columns and filter (as the
//      tile reads it) -- and what the series matches of them (close[AAPL])
//   2. each grouped by its period (its date at the tile's frequency: a day, a week, a month...) -- the first by the
//      split too, one line per value -- each column the series reads taken by its period's aggregate: the latest row's
//      value by default (maxBy over the day's number, `dateDiff` from 1970: maxBy orders by a number); the groups of
//      several sources joined on the period
//   3. the dates every column the series reads has (gs-quant's `intersect`), the split's first MAX_SPLIT_LINES values
//   4. each function of the expression, innermost first: its columns over the dates, every window partitioned by the
//      split and ordered by the period; a rolling figure drawn once its window is full (gs-quant's ramp)
//   5. an index or a change from the start reads the value AS OF THE FIRST DATE SHOWN -- the anchor the viewer passes
//      (where its zoom starts), found by the database: the latest period at or before it that has a value, else the
//      first after it
//   6. the period, the split and the value(s), latest first, at most TS_POINT_LIMIT
//
// Every series reads ALL its history (step 5's anchor aside): the window shown is the viewer's, zoomed and panned over
// what came back, so a rolling figure at the window's left edge has its history, as a person expects it to.

import {
  agg, and, asc, collection, derive, desc, enumValue, fn, lambda, lit, or, property, variable,
  type AppliedFunction, type ColSpec, type Lambda, type Relation, type ValueSpecification,
} from '../../pure-protocol/src/index.ts';
import { filterNode, literalNode, sourceWithDerived } from './query.ts';
import { columnType, CubeRefusal, type CubeSnapshot, type FilterNode } from './snapshot.ts';
import { WINDOW_ALL_PREFIX } from './grid/columns.ts';
import type { ResultTable } from '../../engine-client/src/result.ts';
import { UI_LOCALE } from '../../engine-client/src/locale.ts';
import { isDay, isTemporal, isTimeOfDay } from '../../engine-client/src/types.ts';
import {
  filterRead,
  MAX_SPLIT_LINES, PERIODS_PER_YEAR, RANGE_STEP, tenorOf, TS_POINT_LIMIT, TS_RANGES, type TimeseriesSpec, type TsAggregate,
  type TsAlign, type TsExpr, type TsMatch, type TsOperator, type TsRange, type TsRangeWord, type TsSeries, type TsSource, type TsTenor,
  isConditionOp, TS_CURVE_DATES, TS_HIST_BINS, TS_SEASON_YEARS, TS_SIGNATURES, type TsFunctionName,
} from './timeseries-spec.ts';
import { inlined, isBand, isCandles, isCondition, levelOf, TsInlineError, paramOf, seriesName, sourceAlias, sourcesOf, splitName, unitOf, type TsUnit } from './timeseries-expr.ts';
import type { TimeGrain } from './chart-spec.ts';

/** The columns a series' query answers with. */
export const TS_DATE = '__ts_date';
export const TS_VALUE = '__ts_value';
export const TS_LOWER = '__ts_lower';
export const TS_UPPER = '__ts_upper';
/** A band's width, upper less lower: the database's, so the drawing stacks the band's fill on its lower edge. */
export const TS_WIDTH = '__ts_width';
/** A candle's open, high and low (its close is TS_VALUE): the period's first open, highest high, lowest low. */
export const TS_OPEN = '__ts_open';
export const TS_HIGH = '__ts_high';
export const TS_LOW = '__ts_low';
export const TS_SPLIT = '__ts_split';
/** A condition's run each date where it holds is in: its first date and its last (none where it does not hold). */
export const TS_FROM = '__ts_from';
export const TS_TO = '__ts_to';
/** How many of the grid's rows each period's value was one of (rows at its latest time): more than one is said. */
export const TS_ROWS = '__ts_rows';
/** The periods a year a series of a tile of no frequency was annualized by, as the database inferred them from its dates
 *  (none: no frequency, said). */
export const TS_PER_YEAR = '__ts_per_year';

/** The columns the bounds query answers with: the latest and earliest dates, and each range button's start. */
export const TS_LATEST = '__ts_latest';
export const TS_EARLIEST = '__ts_earliest';
export const rangeStartName = (w: TsRangeWord): string => `__ts_from_${w}`;

/** The statistics a series' statistics query answers with, by line, in the table's order. */
export const TS_STATS = [
  'count', 'from', 'to', 'first', 'last', 'change', 'pct', 'low', 'lowAt', 'high', 'highAt', 'mean', 'std', 'vol', 'mdd',
  'cagr', 'sharpe',
  // a level's figures of its returns' risk (the expert statistics, 2026-10-26)
  'sortino', 'calmar', 'skew', 'kurtosis', 'hit', 'best', 'worst', 'var', 'es',
] as const;
/** The statistics only a level has: of its returns, its drawdown and its growth. */
const LEVEL_STATS: readonly TsStat[] = ['vol', 'mdd', 'cagr', 'sharpe', 'sortino', 'calmar', 'skew', 'kurtosis', 'hit', 'best', 'worst', 'var', 'es'];
/** The share of returns a historical value at risk is the quantile of (95%: the worst 5%). */
const VAR_LEVEL = 0.05;
export type TsStat = (typeof TS_STATS)[number];
export const statName = (s: TsStat): string => `__ts_stat_${s}`;
/** The periods a year a level's statistics were annualized by, a tile of no frequency's: the database's, of its dates. */
export const TS_STAT_PER_YEAR = '__ts_stat_per_year';
/** A level's least over the dates shown, before compare: at or below zero, none of its returns' figures (the viewer says
 *  so). */
export const TS_STAT_LEVEL_LOW = '__ts_stat_level_low';

const DURATION = 'meta::pure::functions::date::DurationUnit';
const x = (): ValueSpecification => variable('x');
const col = (n: string): ValueSpecification => property(x(), n);
/** A column's value, `$x.<n>->toOne()`: arithmetic takes one value (an empty one is SQL's null there). */
const one = (n: string): ValueSpecification => fn('toOne', col(n));
const row = (body: ValueSpecification): Lambda => lambda(['x'], body);
const float = (n: number): ValueSpecification => lit.float(n);
const none = (): ValueSpecification => collection([]);
const when = (c: ValueSpecification, then: ValueSpecification, otherwise: ValueSpecification): ValueSpecification =>
  fn('if', c, lambda([], then), lambda([], otherwise));
const notEmpty = (n: string): ValueSpecification => fn('isNotEmpty', col(n));
const op = (name: string, a: ValueSpecification, b: ValueSpecification): ValueSpecification =>
  name === 'divide' ? fn('divide', a, b) : fn(name, collection([a, b]));

/** What a series' query is: the query, the unit its values are in, whether it draws a band, its split. */
export interface TsQuery {
  readonly series: string;
  readonly query: Lambda;
  readonly unit: TsUnit;
  /** Whether its figures are a level (timeseries-expr.ts levelOf): what a log scale and compare may take. */
  readonly level: boolean;
  readonly band: boolean;
  /** It draws a candle per period (ohlc): its answer's TS_OPEN, TS_HIGH, TS_LOW and its close, TS_VALUE. */
  readonly candles?: boolean;
  /** It is a condition, drawn as the periods it holds: its answer's TS_FROM and TS_TO, each date's run's (TS_TO the
   *  line's next date after the run, or its last). */
  readonly shade?: boolean;
  readonly split?: string;
  /** Whether a period's value may be taken from several rows (a series of no instrument): its rows are counted. */
  readonly counted: boolean;
  /** The sources it reads, by alias: one, run where that grid runs; more, a combination over one model of their tables. */
  readonly sources: readonly string[];
}

/** Where the viewer is: the first date shown (an index's base), and for statistics the last. */
export interface TsWindow {
  readonly from?: string;
  readonly to?: string;
}

/** A series' relation as written so far, and what its columns are. */
interface Built {
  readonly rel: Relation;
  readonly period: string;
  readonly periodType: string;
  readonly ord: string;
  /** The sources it reads, by alias, in order. */
  readonly sources: readonly string[];
  readonly value: string;
  readonly lower?: string;
  readonly upper?: string;
  readonly width?: string;
  /** A candle's open, high and low; its close the value. */
  readonly open?: string;
  readonly high?: string;
  readonly low?: string;
  readonly split?: string;
  readonly unit: TsUnit;
  /** Whether its figures are a level (levelOf): what compare rebases, and the statistics read a volatility of. */
  readonly level: boolean;
  /** A level's own value, before compare took it from its start: what its returns' figures are of (a % change from its
   *  start starts at zero -- the third review's 5: its volatility blanked as "at or below zero"). */
  readonly levelValue?: string;
  /** Each period's date: its value's, the period's last observation. */
  readonly at: string;
  readonly rows?: string;
  /** The periods a year it was annualized by, as the database inferred them (a tile of no frequency). */
  readonly perYear?: string;
  /** A condition's run each date where it holds is in: its first date and its last. */
  readonly shadeFrom?: string;
  readonly shadeTo?: string;
  /** What wrote it: a query over it (its statistics) goes on writing, so a column it adds is named after the series' own. */
  readonly writer: Writer;
}

/** The type the compiler gave a column of a grid (its source's or a row-level calculated column's). */
function typeIn(s: CubeSnapshot, column: string): string | undefined {
  return columnType(s, column);
}

/**
 * A date the viewer passes (where its zoom starts or ends: an ISO day), as a literal of the period's type: a day as it
 * is, a date-time at that day's start (an end, its last second, said by its caller).
 */
function dateLiteral(value: string, type: string): ValueSpecification {
  return literalNode(!isDay(type) && /^\d{4}-\d{2}-\d{2}$/.test(value) ? `${value}T00:00:00` : value, type);
}

/** The compiler's function taking a date to its period's first day. */
const GRAIN_FUNCTION: Readonly<Record<Exclude<TimeGrain, 'day'>, string>> = {
  week: 'firstDayOfWeek', month: 'firstDayOfMonth', quarter: 'firstDayOfQuarter', year: 'firstDayOfYear',
};

/**
 * A GRID A SERIES READS, as its query is written over it: its rows (its snapshot -- its source, its row-level calculated
 * columns) and its filter as the tile reads it. Of a series of one grid, the grid's own snapshot (its plane decides what
 * its source reads: live, or its snapped copy); of a combination, each grid's snapshot with its source the relation of
 * its tables over the one model the combination is planned over (D5: Pure for everything, snapped or not).
 */
export interface TsInput {
  readonly snapshot: CubeSnapshot;
  /** The grid's filter as it is now: a frozen tile's source reads the one it keeps instead (timeseries-spec.ts
   *  filterRead, asked here for every query, so neither host decides it). */
  readonly filter: FilterNode | undefined;
}

/** Each source's input, by its alias. */
export type TsInputs = ReadonlyMap<string, TsInput>;

/** A column's matches as one condition on a row (a match of no column, its source's instrument column). */
function matched(s: CubeSnapshot, key: string | undefined, where: readonly TsMatch[]): ValueSpecification | undefined {
  if (where.length === 0) return undefined;
  const terms = where.map((m) => {
    const c = m.column ?? key;
    if (c === undefined) throw new CubeRefusal('a value is matched with no column: choose the column naming the instruments');
    return fn('equal', col(c), literalNode(m.value, typeIn(s, c)));
  });
  return and(...terms);
}

/** Each column the series reads, once: its source, the column, how a period takes it, and what it matches. */
interface Base {
  readonly key: string;
  readonly alias: string;
  readonly column: string;
  readonly fn: TsAggregate;
  readonly where: readonly TsMatch[];
}

const baseKey = (ts: TimeseriesSpec, n: Extract<TsExpr, { kind: 'column' }>): string =>
  JSON.stringify([sourceAlias(ts, n) ?? '', n.column, n.fn ?? 'last', n.where ?? []]);


/** The column of a part's period (every window's order, the key parts are joined on), its number in time, and its date. */
const PERIOD = '__ts_p0';
const ORD = '__ts_ord';
const AT = '__ts_at';

/**
 * What every part of one series' query shares: the tile, the frequency its periods are of (the tile's; none inside a
 * candle at a frequency, whose series are read on their sources' own dates), the source it reads first (its split's),
 * where the window shown starts, its periods' type (the first part's: another's of another type is refused), and its
 * names -- one count, so a column a part adds is named apart from every other part's it may be joined with.
 */
interface Shared {
  readonly ts: TimeseriesSpec;
  readonly grain: TimeGrain | undefined;
  readonly series: TsSeries;
  readonly inputs: TsInputs;
  readonly split: string | undefined;
  readonly first: string;
  readonly anchor: string | undefined;
  periodType: string | undefined;
  readonly names: { n: number };
}

/** A fresh name of the series' query. */
const freshName = (sh: Shared, what: string): string => `__ts_${what}_${sh.names.n++}`;

/** A window or a lag as a function takes it: a whole number of observations, or a calendar period (`3m`, gs-quant's
 *  tenor). */
type Span = number | TsTenor;
/** A window's frame: running from the start, every period, the last n observations, or a calendar period. */
type Frame = 'running' | 'all' | 'rest' | number | TsTenor;
const isTenor = (f: Frame): f is TsTenor => typeof f === 'object';
/** A span of at least `k` observations (a deviation needs two); a calendar period as it is. */
const atLeast = (s: Span, k: number): Span => (typeof s === 'number' ? Math.max(s, k) : s);
/** `adjust(<date>, -n, <unit>)`: a calendar period before a date, the database's date arithmetic. */
const back = (date: ValueSpecification, t: TsTenor): ValueSpecification => fn('adjust', date, lit.integer(-t.n), enumValue(DURATION, t.unit));

/** A frame's read (`$r.c`, `rowMapper($r.a, $r.b)`) as a row of a self-join reads it: each `$r.c` the joined row's column
 *  `c` is under (`$x.<names[c]>`). */
function readOver(read: ValueSpecification, names: ReadonlyMap<string, string>): ValueSpecification {
  switch (read._type) {
    case 'property': {
      const on = read.parameters[0];
      if (read.parameters.length === 1 && on?._type === 'var' && on.name === 'r') {
        const name = names.get(read.property);
        if (name === undefined) throw new Error(`a window reads ${read.property}, which its self-join does not carry`);
        return property(x(), name);
      }
      return { ...read, parameters: read.parameters.map((v) => readOver(v, names)) };
    }
    case 'func':
      return { ...read, parameters: read.parameters.map((v) => readOver(v, names)) };
    case 'collection':
      return { ...read, values: read.values.map((v) => readOver(v, names)) };
    default:
      return read;
  }
}

/** The columns a frame's read reads of its rows (`$r.c`), each once. */
function rowColumnsOf(read: ValueSpecification): string[] {
  const out = new Set<string>();
  const walk = (v: ValueSpecification): void => {
    if (v._type === 'property') {
      const on = v.parameters[0];
      if (v.parameters.length === 1 && on?._type === 'var' && on.name === 'r') out.add(v.property);
      else v.parameters.forEach(walk);
    } else if (v._type === 'func') v.parameters.forEach(walk);
    else if (v._type === 'collection') v.values.forEach(walk);
  };
  walk(read);
  return [...out];
}

/**
 * A PART OF A SERIES' QUERY, written in steps: a relation of one row per line and period -- a column's own rows (its
 * source's, matched), a function over a part, two parts joined -- each date a part has its own (the review's Q1: a
 * function of one operand never runs over another operand's dates), and a name for each column it adds.
 */
class Writer {
  rel: Relation;
  /** The column its windows are partitioned by: the series' split (a line per value), else one line. */
  readonly part: string;
  /** Whether it is a line per value of the split, or one line every line of the split joins (`close[SPY]`). */
  readonly split: boolean;
  /** Its periods' date: each one's last observation -- the date its value is of, where the window shown is compared. */
  readonly at: string;
  /** Whether its number in time counts seconds (a date-time source's), else days. */
  readonly seconds: boolean;
  readonly shared: Shared;
  /** The columns it carries for the drawing to read, through every join: the rows each period's value was one of, the
   *  periods a year it was annualized by (none: no frequency the dates' spacing is). */
  readonly rows: string[];
  readonly years: string[];
  #asOf: string | undefined;
  #perYear: string | undefined;

  constructor(rel: Relation, o: {
    part: string; split: boolean; at: string; seconds: boolean; rows: readonly string[]; years: readonly string[];
  }, shared: Shared) {
    this.rel = rel;
    this.part = o.part;
    this.split = o.split;
    this.at = o.at;
    this.seconds = o.seconds;
    this.rows = [...o.rows];
    this.years = [...o.years];
    this.shared = shared;
  }

  get ts(): TimeseriesSpec {
    return this.shared.ts;
  }

  fresh(what: string): string {
    return freshName(this.shared, what);
  }

  /** `->extend(~[name: x|body])`. */
  row(what: string, body: ValueSpecification): string {
    const name = this.fresh(what);
    this.rel = this.rel.extend([derive(name, row(body))]);
    return name;
  }

  /** The window every function of the part runs over: its line's partition (and `by`'s, a block of it), the periods
   *  in order, a frame. */
  #over(frame: 'running' | 'all' | 'rest' | number | null, by?: string): AppliedFunction {
    const args: ValueSpecification[] = [
      { _type: 'classInstance', type: 'colSpecArray', value: { colSpecs: [{ name: this.part }, ...(by !== undefined ? [{ name: by }] : [])] } },
      collection([asc(PERIOD)]),
    ];
    if (frame === 'running') args.push(fn('rows', fn('unbounded'), lit.integer(0)));
    else if (frame === 'all') args.push(fn('rows', fn('unbounded'), fn('unbounded')));
    else if (frame === 'rest') args.push(fn('rows', lit.integer(0), fn('unbounded')));
    else if (frame !== null) args.push(fn('rows', lit.integer(-(Math.max(1, frame) - 1) || 0), lit.integer(0)));
    return fn('over', ...args);
  }

  /** An aggregate over a frame of the periods: `extend(over(...), ~[name:{p,w,r|<read>}:y|$y-><reduce>()])` -- of a
   *  calendar period, over the periods whose dates are within it (#calendar). */
  window(what: string, reduce: string, read: ValueSpecification, frame: Frame, by?: string): string {
    if (isTenor(frame)) {
      if (by !== undefined) throw new Error('a calendar period\'s window is of its line alone');
      return this.#calendar(what, reduce, read, frame);
    }
    const name = this.fresh(what);
    this.rel = this.rel.extend([agg(name, lambda(['p', 'w', 'r'], read), lambda(['y'], fn(reduce, variable('y'))))], this.#over(frame, by));
    return name;
  }

  /**
   * A WINDOW OF A CALENDAR PERIOD (gs-quant's `Window('3m')`, helper.rolling_offset: the dates after one that far back,
   * to this one): its line's periods joined to every one of its own whose date is in (adjust(date, -n, unit), date], the
   * read aggregated over each period's, and joined back by the period -- a self-join, the database's, since a window
   * frame counts rows, never dates.
   */
  #calendar(what: string, reduce: string, read: ValueSpecification, t: TsTenor): string {
    const name = this.fresh(what);
    const from = this.fresh('cal_from');
    const read_ = rowColumnsOf(read);
    const names = new Map(read_.map((c) => [c, this.fresh('cal_r')] as const));
    const rp = this.fresh('cal_rp');
    const ra = this.fresh('cal_ra');
    let right = this.rel.select([this.part, this.at, ...read_.filter((c) => c !== this.part && c !== this.at)]);
    // a read of the part or the date itself (a count of dates) reads the joined row's own
    right = right.rename(this.part, rp).rename(this.at, ra);
    const as = new Map(names);
    if (read_.includes(this.part)) as.set(this.part, rp);
    if (read_.includes(this.at)) as.set(this.at, ra);
    for (const c of read_) if (c !== this.part && c !== this.at) right = right.rename(c, names.get(c)!);
    const left = this.rel.select([this.part, PERIOD, this.at]).extend([derive(from, row(back(one(this.at), t)))]);
    const inWindow = lambda(['l', 'r'], and(
      fn('equal', property(variable('l'), this.part), property(variable('r'), rp)),
      fn('greaterThan', property(variable('r'), ra), property(variable('l'), from)),
      fn('lessThanEqual', property(variable('r'), ra), property(variable('l'), this.at)),
    ));
    const gp = this.fresh('cal_gp');
    const gper = this.fresh('cal_gper');
    const grouped = left.join(right, 'INNER', inWindow)
      .groupBy([this.part, PERIOD], [agg(name, row(readOver(read, as)), lambda(['y'], fn(reduce, variable('y'))))])
      .rename(this.part, gp).rename(PERIOD, gper);
    this.rel = this.rel.join(grouped, 'LEFT', lambda(['l', 'r'], and(
      fn('equal', property(variable('l'), this.part), property(variable('r'), gp)),
      fn('equal', property(variable('l'), PERIOD), property(variable('r'), gper)),
    )));
    return name;
  }

  /**
   * A FIGURE OF EACH PERIOD'S WINDOW BY ITS ROWS (a self-join, the database's: what a window frame's aggregate cannot
   * say -- the period's own value against each of its window's, a percentile any database takes): each period of the
   * line joined to every period of its window -- the last w (0 every one to it), or a calendar period's dates (a figure
   * of every one is ofLine's: one aggregate a line) -- `figures` aggregated per period, each reading the window's value and the period's own, and
   * joined back by the period. Their names, in order.
   */
  ofWindow(x: string, frame: 'running' | number | TsTenor,
    figures: (own: ValueSpecification, other: ValueSpecification) => readonly { readonly what: string; readonly read: ValueSpecification; readonly reduce: Lambda }[]): string[] {
    const rn = this.fresh('wj_rn');
    this.rel = this.rel.extend([derive(rn, lambda(['p', 'w', 'r'], fn('rowNumber', variable('p'), variable('r'))))], this.#over(null));
    const from = this.fresh('wj_from');
    const rp = this.fresh('wj_rp');
    const ra = this.fresh('wj_ra');
    const rr = this.fresh('wj_rr');
    const rx = this.fresh('wj_rx');
    let left = this.rel.select([this.part, PERIOD, this.at, rn, ...(x !== this.at ? [x] : [])]);
    if (isTenor(frame)) left = left.extend([derive(from, row(back(one(this.at), frame)))]);
    const right = this.rel.select([this.part, this.at, rn, ...(x !== this.at ? [x] : [])])
      .rename(this.part, rp).rename(this.at, ra).rename(rn, rr).rename(x, rx);
    const L = (c: string): ValueSpecification => property(variable('l'), c);
    const Rr = (c: string): ValueSpecification => property(variable('r'), c);
    const inWindow: ValueSpecification[] = [fn('equal', L(this.part), Rr(rp))];
    if (isTenor(frame)) inWindow.push(fn('greaterThan', Rr(ra), L(from)), fn('lessThanEqual', Rr(ra), L(this.at)));
    else if (frame === 'running') inWindow.push(fn('lessThanEqual', Rr(rr), L(rn)));
    else inWindow.push(fn('greaterThan', Rr(rr), op('minus', L(rn), lit.integer(frame))), fn('lessThanEqual', Rr(rr), L(rn)));
    const made = figures(col(x), col(rx)).map((f) => ({ ...f, name: this.fresh(f.what) }));
    const gp = this.fresh('wj_gp');
    const gper = this.fresh('wj_gper');
    const grouped = left.join(right, 'INNER', lambda(['l', 'r'], and(...inWindow)))
      .groupBy([this.part, PERIOD], made.map((f) => agg(f.name, row(f.read), f.reduce)))
      .rename(this.part, gp).rename(PERIOD, gper);
    this.rel = this.rel.join(grouped, 'LEFT', lambda(['l', 'r'], and(
      fn('equal', L(this.part), Rr(gp)), fn('equal', L(PERIOD), Rr(gper)))));
    return made.map((f) => f.name);
  }

  /** A FIGURE OF ITS LINE'S EVERY VALUE, beside each of its rows: the line's values aggregated, grouped by its line, and
   *  joined back by it -- one aggregate a line. */
  ofLine(x: string, what: string, reduce: Lambda): string {
    const name = this.fresh(what);
    const gp = this.fresh('ol_gp');
    const grouped = this.rel.groupBy([this.part], [agg(name, row(col(x)), reduce)]).rename(this.part, gp);
    this.rel = this.rel.join(grouped, 'LEFT', lambda(['l', 'r'], fn('equal', property(variable('l'), this.part), property(variable('r'), gp))));
    return name;
  }

  /** A column `n` periods back: `$p->lag($r, n).<column>` -- or a calendar period back, as of that date (#valueBack);
   *  `by`: among the line's rows of the same value of that column. */
  lag(column: string, n: Span, by?: string): string {
    if (typeof n !== 'number') {
      if (by !== undefined) throw new Error('a calendar period back is of its line alone');
      return this.#valueBack(column, n);
    }
    const name = this.fresh('lag');
    const call = fn('lag', variable('p'), variable('r'), ...(n !== 1 ? [lit.integer(n)] : []));
    this.rel = this.rel.extend([derive(name, lambda(['p', 'w', 'r'], property(call, column)))], this.#over(null, by));
    return name;
  }

  /** A column the next period on: `$p->lead($r).<column>` (none at the line's last). */
  lead(column: string, n = 1): string {
    const name = this.fresh('lead');
    const call = fn('lead', variable('p'), variable('r'), ...(n !== 1 ? [lit.integer(n)] : []));
    this.rel = this.rel.extend([derive(name, lambda(['p', 'w', 'r'], property(call, column)))], this.#over(null));
    return name;
  }

  /** Each row's place in its line, 1 its first: `$p->rowNumber($r)`. */
  rowNumber(): string {
    const name = this.fresh('rn');
    this.rel = this.rel.extend([derive(name, lambda(['p', 'w', 'r'], fn('rowNumber', variable('p'), variable('r'))))], this.#over(null));
    return name;
  }

  /** An aggregate over the `k` rows either side of each (a centred window: a classical decomposition's moving average). */
  around(what: string, reduce: string, read: ValueSpecification, k: number): string {
    const name = this.fresh(what);
    const over = fn('over', { _type: 'classInstance', type: 'colSpecArray', value: { colSpecs: [{ name: this.part }] } },
      collection([asc(PERIOD)]), fn('rows', lit.integer(-k), lit.integer(k)));
    this.rel = this.rel.extend([agg(name, lambda(['p', 'w', 'r'], read), lambda(['y'], fn(reduce, variable('y'))))], over);
    return name;
  }

  /**
   * THE PRODUCT of `g`'s values over a frame, an empty one skipped (pandas' prod): 0 where one is 0, its sign from the
   * count of those below 0, its magnitude e to the sum of the logs of the others -- the database's (the review's Q8).
   */
  product(g: string, frame: Frame): string {
    const R = Writer.read;
    const zeros = this.window('zeros', 'count', R(this.row('z', when(fn('equal', one(g), lit.integer(0)), lit.integer(1), none()))), frame);
    const negatives = this.window('negs', 'count', R(this.row('neg', when(fn('lessThan', one(g), lit.integer(0)), lit.integer(1), none()))), frame);
    const sum = this.window('lgs', 'plus', R(this.row('lg', when(fn('equal', one(g), lit.integer(0)), none(), fn('log', fn('abs', one(g)))))), frame);
    const logs = when(fn('isEmpty', col(sum)), float(0), one(sum));
    return this.row('prod', when(fn('greaterThan', one(zeros), lit.integer(0)), float(0),
      op('times', fn('pow', float(-1), one(negatives)), fn('exp', logs))));
  }

  /** A column's value at its line's first period with one (or its last), beside every row: windows over the line. */
  edgeValue(column: string, edge: 'first' | 'last'): string {
    const R = Writer.read;
    const at = this.window(`${edge}_p`, edge === 'first' ? 'min' : 'max', R(this.row('ep', when(fn('isNotEmpty', col(column)), col(PERIOD), none()))), 'all');
    return this.window(`${edge}_v`, 'max', R(this.row('ev', when(fn('equal', col(PERIOD), col(at)), col(column), none()))), 'all');
  }

  /**
   * A COLUMN AS OF A CALENDAR PERIOD BACK (gs-quant's lag by a tenor, as of): of its line's periods where it has a
   * value, the latest whose date is at or before adjust(date, -n, unit) -- an as-of join, the database's; none before
   * the line's first date that far back.
   */
  #valueBack(column: string, t: TsTenor): string {
    const target = this.fresh('back_at');
    const lp = this.fresh('back_part');
    const la = this.fresh('back_date');
    const value = this.fresh('back');
    const right = this.rel.filter(row(fn('isNotEmpty', col(column)))).select([this.part, this.at, column])
      .rename(this.part, lp).rename(this.at, la).rename(column, value);
    this.rel = this.rel.extend([derive(target, row(back(one(this.at), t)))]).asOfJoin(right,
      lambda(['l', 'r'], fn('greaterThanEqual', property(variable('l'), target), property(variable('r'), la))),
      lambda(['l', 'r'], fn('equal', property(variable('l'), this.part), property(variable('r'), lp))));
    return value;
  }

  /** Each value's place among its line's values from the `at`-th on (0 there): the count of them to it, less one, less
   *  `at`; none where it has none, or before the `at`-th. */
  #places(x: string, at: number): string {
    const n = this.window('ewm_n', 'count', Writer.read(x), 'running');
    const k = op('minus', one(n), lit.integer(1 + at));
    return this.row('ewm_k', when(or(fn('isEmpty', col(x)), fn('lessThan', k, lit.integer(0))), none(), k));
  }

  /**
   * AN EXPONENTIALLY WEIGHTED AVERAGE, pandas' `ewm(alpha = 1 - decay, adjust=False).mean()` (gs-quant's
   * exponential_moving_average): y0 = x0, y = decay·y[-1] + (1 - decay)·x -- a recursion, which no window frame says
   * (a frame's reducer is an aggregate, never a fold), written as its CLOSED FORM, the database's: y_t = (1 - decay)·
   * Σ decay^(t-i)·x'_i, x'_0 = x0 / (1 - decay) (so the first weighs decay^t). Summed in blocks of W values, W the
   * fewest with decay^W under 2^-60 (so decay^-pos, pos < W, stays under 2^60 in a double): within a block a running
   * sum of x'·decay^-pos, times decay^pos; the block before carried in whole, its last value times decay^(pos + 1) (its
   * block's total, by windows), the blocks before it weighing under 2^-60 of it -- below a double's precision, so the
   * value is the recursion's to its last bits. `seed`: from the value of `seed.value` at the `seed.at`-th value (a
   * Wilder average's first, a mean of w), the series' own from there; rows of none have none.
   */
  ewm(x: string, decay: ValueSpecification, seed?: { readonly at: number; readonly value: string }): string {
    const R = Writer.read;
    const a = op('minus', float(1), decay);
    const k = this.#places(x, seed?.at ?? 0);
    const block = fn('ceiling', fn('divide', op('times', float(-60), fn('log', float(2))), fn('log', decay)));
    const blk = this.row('ewm_blk', fn('floor', fn('divide', one(k), block)));
    const pos = this.row('ewm_pos', op('minus', one(k), op('times', one(blk), block)));
    const start = seed !== undefined ? one(seed.value) : one(x);
    const xs = this.row('ewm_x', when(fn('equal', one(k), lit.integer(0)), fn('divide', start, a), col(x)));
    const u = this.row('ewm_u', op('times', one(xs), fn('pow', decay, op('minus', lit.integer(0), one(pos)))));
    const sum = this.fresh('ewm_s');
    this.rel = this.rel.extend([agg(sum, lambda(['p', 'w', 'r'], R(u)), lambda(['y'], fn('plus', variable('y'))))], this.#over('running', blk));
    const inBlock = this.row('ewm_p', op('times', op('times', a, fn('pow', decay, one(pos))), one(sum)));
    // the block before's last value, beside each of this block's (its whole weight, carried in): (1 - decay)·decay^(W-1)
    // times its block's total -- each block's a window over it, the block before's read at this block's first value as
    // the value before it (a lag over the line's values), and spread over the block -- windows, never the query joined
    // to itself (the E9 review's 6: each average had doubled the query, five nested ones 311k characters)
    const total = this.window('ewm_t', 'plus', R(u), 'all', blk);
    const valued = this.row('ewm_has', when(fn('isEmpty', col(k)), lit.integer(0), lit.integer(1)));
    const before = this.lag(total, 1, valued);
    const atFirst = this.row('ewm_c0', when(fn('equal', one(pos), lit.integer(0)), col(before), none()));
    const carried = this.window('ewm_c', 'max', R(atFirst), 'all', blk);
    const bv = op('times', op('times', a, fn('pow', decay, op('minus', block, lit.integer(1)))), one(carried));
    return this.row('ewm', when(fn('isEmpty', col(k)), none(), when(fn('isEmpty', col(carried)), col(inBlock),
      op('plus', one(inBlock), op('times', fn('pow', decay, op('plus', one(pos), lit.integer(1))), bv)))));
  }

  /**
   * AN EXPONENTIALLY WEIGHTED STANDARD DEVIATION, pandas' `ewm(alpha = 1 - decay, adjust=False).std()` (gs-quant's
   * exponential_std): the weighted mean square less the squared weighted mean (of each value less the line's first, so
   * nothing large is subtracted from itself), over 1 less the sum of the squared weights (pandas' bias correction:
   * decay^2t + (1 - decay)²·(1 - decay^2t) / (1 - decay²) of the t-th value) -- none at the first, whose weights are
   * one.
   */
  ewmStd(x: string, decay: ValueSpecification): string {
    const k = this.#places(x, 0);
    const first = this.window('ewm_first', 'max', Writer.read(this.row('ewm_f', when(fn('equal', one(k), lit.integer(0)), col(x), none()))), 'all');
    const d = this.row('ewm_d', op('minus', one(x), one(first)));
    const d2 = this.row('ewm_d2', op('times', one(d), one(d)));
    const m1 = this.ewm(d, decay);
    const m2 = this.ewm(d2, decay);
    const a = op('minus', float(1), decay);
    const twice = fn('pow', decay, op('times', lit.integer(2), one(k)));
    const squares = op('plus', twice, fn('divide', op('times', op('times', a, a), op('minus', float(1), twice)), op('minus', float(1), op('times', decay, decay))));
    const denominator = this.row('ewm_den', op('minus', float(1), squares));
    const variance = this.row('ewm_var', op('minus', one(m2), op('times', one(m1), one(m1))));
    // a mean square a hair under the squared mean (a constant series, rounded) is a variance of zero
    return this.row('ewm_std', when(or(fn('isEmpty', col(k)), fn('lessThanEqual', one(denominator), lit.integer(0))), none(),
      fn('sqrt', fn('divide', when(fn('lessThan', one(variance), lit.integer(0)), float(0), one(variance)), one(denominator)))));
  }

  /**
   * WILDER'S SMOOTHED AVERAGE over w (gs-quant's technicals.smoothed_moving_average): its first the mean of the w values
   * ending at the w-th after the first (gs-quant's ramp of w), then each (w - 1) parts the one before and one part the
   * value -- an exponential average of decay (w - 1) / w from that mean.
   */
  smoothed(x: string, w_: number): string {
    // of one: each value itself, from the first after the first (gs-quant's ramp of 1 drops the first -- the E9 review's
    // nit: it had been kept)
    if (w_ <= 1) {
      const k = this.#places(x, 1);
      return this.row('smma', when(fn('isEmpty', col(k)), none(), col(x)));
    }
    const mean = this.window('smma_seed', 'average', Writer.read(x), w_);
    return this.ewm(x, fn('divide', lit.integer(w_ - 1), lit.integer(w_)), { at: w_, value: mean });
  }

  /** A column's value on a frame's rows, as a window reads it. */
  static read(column: string): ValueSpecification {
    return property(variable('r'), column);
  }

  /** `w` periods of `column` (0: from the start), or a calendar period: its frame. */
  static frame(w: Span): 'running' | number | TsTenor {
    return w === 0 ? 'running' : w;
  }

  /**
   * A rolling figure drawn once its window holds `need` of `input`'s values: empty before (gs-quant's ramp), counted
   * over the figure's own frame -- the last `w`, or from the start (w 0). Of a calendar period, from `input`'s first date
   * that period on (gs-quant's apply_ramp of a DateOffset: x.loc[x.index[0] + r:]).
   */
  ramped(value: string, input: string, need: Span, frame: 'running' | number | TsTenor = need): string {
    if (typeof need !== 'number') {
      const first = this.window('ramp_first', 'min', Writer.read(this.row('ramp_at', when(fn('isNotEmpty', col(input)), col(this.at), none()))), 'all');
      return this.row('ramped', when(fn('lessThan', one(this.at), fn('adjust', one(first), lit.integer(need.n), enumValue(DURATION, need.unit))), none(), col(value)));
    }
    if (need <= 1) return value;
    const count = this.window('n', 'count', Writer.read(input), frame);
    return this.row('ramped', when(fn('lessThan', one(count), lit.integer(need)), none(), col(value)));
  }

  /** `a / b`, empty where b is zero. */
  ratio(what: string, a: ValueSpecification, b: string): string {
    return this.row(what, when(fn('equal', one(b), lit.integer(0)), none(), fn('divide', a, one(b))));
  }

  /**
   * THE VALUE AS OF THE FIRST DATE SHOWN, beside every row: of the periods where `column` has a value, the latest whose
   * value is of a date at or before the anchor (the viewer's left edge) -- a year's change from the last close before
   * it, a YTD from the year before's last -- or, the series starting after the anchor (or no anchor), its first; the
   * period the database's, and the value there, each a window over the whole partition. A week or a month is compared
   * by the date its value is of, its last observation (the review's Q6; the second audit's S3: a range starting just
   * after a month's last observation lost that month's move).
   */
  anchored(column: string): string {
    const anchor = this.shared.anchor;
    const has = fn('isNotEmpty', col(column));
    const first = this.window('anchor_first', 'min', Writer.read(this.row('anchor_any', when(has, col(PERIOD), none()))), 'all');
    if (anchor === undefined) return this.#valueAt(column, first);
    if (this.#asOf === undefined) {
      this.#asOf = this.row('asof', fn('lessThanEqual', one(this.at), dateLiteral(anchor, this.shared.periodType!)));
    }
    const before = this.window('anchor_before', 'max', Writer.read(this.row('anchor_asof', when(and(one(this.#asOf), has), col(PERIOD), none()))), 'all');
    return this.#valueAt(column, this.row('anchor_period', when(fn('isEmpty', col(before)), col(first), col(before))));
  }

  /** `column`'s value at the period `at` names, beside every row of the partition. */
  #valueAt(column: string, at: string): string {
    const value = this.row('anchor_mark', when(fn('equal', col(PERIOD), col(at)), col(column), none()));
    return this.window('anchor', 'max', Writer.read(value), 'all');
  }

  /** Returns over `obs` periods (or a calendar period back, as of its date), of a type (gs-quant's econometrics.returns). */
  returns(a: string, obs: Span, type: string): string {
    const p = this.lag(a, obs);
    switch (type) {
      case 'log':
        return this.row('ret', when(or(fn('lessThanEqual', one(a), lit.integer(0)), fn('lessThanEqual', one(p), lit.integer(0))), none(),
          op('minus', fn('log', one(a)), fn('log', one(p)))));
      case 'absolute':
        return this.row('ret', op('minus', one(a), one(p)));
      case 'simple':
        return this.row('ret', when(fn('equal', one(p), lit.integer(0)), none(), op('minus', fn('divide', one(a), one(p)), lit.integer(1))));
      default:
        throw new CubeRefusal(`there is no ${JSON.stringify(type)} return (simple, log or absolute)`);
    }
  }

  /**
   * THE PERIODS A YEAR it is annualized by: the tile's frequency's (252 days, 52 weeks, 12 months...); with none, the one
   * the database infers from the part's own dates -- the average days between its periods, as gs-quant's
   * econometrics._get_annualization_factor buckets it (under 2.1 days: 252; 6 to 8: 52; 14 to 17: 26; 25 to 35: 12; 85
   * to 97: 4; 360 to 386: 1; anything else, or dates within a day: none, said by the drawing) -- the owner, 2026-10-25,
   * the review's Q3: monthly levels annualized by 252.
   */
  perYear(): ValueSpecification {
    const grain = this.shared.grain;
    if (grain !== undefined) return lit.integer(PERIODS_PER_YEAR[grain]);
    if (this.#perYear === undefined) {
      const hi = this.window('ord_hi', 'max', Writer.read(ORD), 'all');
      const lo = this.window('ord_lo', 'min', Writer.read(ORD), 'all');
      const n = this.window('ord_n', 'count', Writer.read(ORD), 'all');
      const span = this.seconds ? fn('divide', op('minus', one(hi), one(lo)), lit.integer(86400)) : op('minus', one(hi), one(lo));
      const gap = this.row('gap', when(fn('lessThan', one(n), lit.integer(2)), none(), fn('divide', span, op('minus', one(n), lit.integer(1)))));
      const g = one(gap);
      const within = (from: number, to: number): ValueSpecification =>
        and(fn('greaterThanEqual', g, float(from)), fn('lessThan', g, float(to)));
      const buckets: readonly [number, number, number][] = [[1, 2.1, 252], [6, 8, 52], [14, 17, 26], [25, 35, 12], [85, 97, 4], [360, 386, 1]];
      const factor = buckets.reduceRight<ValueSpecification>((otherwise, [from, to, n]) => when(within(from, to), lit.integer(n), otherwise), none());
      this.#perYear = this.row('per_year', factor);
      this.years.push(this.#perYear);
    }
    return one(this.#perYear);
  }

  /** The square root of the periods in a year, as the database takes it. */
  annual(): ValueSpecification {
    return fn('sqrt', this.perYear());
  }

  /** A sample standard deviation over `w` periods (0 from the start, or a calendar period), drawn once its window holds
   *  two. */
  std(a: string, w: Span, since = a): string {
    return this.ramped(this.window('std', 'stdDevSample', Writer.read(a), Writer.frame(w)), typeof w === 'number' ? a : since, atLeast(w, 2), Writer.frame(w));
  }

  /** Simple returns, for the functions gs-quant reads them for (a correlation's, a beta's, a Sharpe ratio's). */
  simple(a: string): string {
    return this.returns(a, 1, 'simple');
  }
}

/** The value a part computes: its column's name, two columns (a band), or four (a candle). */
type Value = { readonly column: string }
  | { readonly lower: string; readonly upper: string; readonly width: string }
  | { readonly open: string; readonly high: string; readonly low: string; readonly close: string };

/** A node of the expression, written: a part and the value it computes over it -- or a constant, numbers alone, the
 *  database's arithmetic wherever it is read. */
type Node = { readonly w: Writer; readonly v: Value } | { readonly constant: ValueSpecification };

/** What only a drawing reads, refused where an expression reads it. */
function unread(v: Value): never {
  throw new CubeRefusal('lower' in v ? 'a band is drawn, never read' : 'candles are drawn, never read');
}

const columnOfValue = (v: Value): string => ('column' in v ? v.column : unread(v));

/** A node as an operand of the row's arithmetic. */
function asOperand(n: Node): ValueSpecification {
  return 'constant' in n ? n.constant : one(columnOfValue(n.v));
}

/** The columns a value is, every one carried through a join. */
const valueColumns = (v: Value): string[] =>
  'column' in v ? [v.column] : 'lower' in v ? [v.lower, v.upper, v.width] : [v.open, v.high, v.low, v.close];

/** How a candle's four series take each period's rows: its first open, its highest high, its lowest low, its last close. */
const CANDLE: readonly TsAggregate[] = ['first', 'max', 'min', 'last'];

/** How a series of each role a function reads (its signature's `reads`) is taken of a period's rows: a high its most, a
 *  low its least, a close or a price its last, a volume or a dividend their total -- a candle's way (CANDLE). */
const ROLE_TAKEN: Readonly<Record<string, TsAggregate>> = { high: 'max', low: 'min', close: 'last', price: 'last', volume: 'sum', dividends: 'sum' };

/** How each series a function reads is taken of a period's rows, by its role; undefined where its series have none (a
 *  series and its benchmark: each its last, as any column). */
function roleTaken(name: TsFunctionName): readonly TsAggregate[] | undefined {
  const reads = TS_SIGNATURES[name].reads;
  if (reads === undefined || !reads.some((r) => r in ROLE_TAKEN)) return undefined;
  return reads.map((r) => {
    const t = ROLE_TAKEN[r];
    if (t === undefined) throw new Error(`${name} reads a ${r}, which no period's rows are taken by`);
    return t;
  });
}

/** An expression with each candle's (ohlc's) plain columns taken by their date as a candle's are (CANDLE) -- unless one
 *  says how itself (`open[AAPL].first`) -- so a day of several rows is its first open, highest high, lowest low and
 *  last close; a period of the tile's frequency then takes its dates' the same way (`candle`). */
function candled(e: TsExpr): TsExpr {
  switch (e.kind) {
    case 'call': {
      const args = e.args.map(candled);
      const taken = e.fn === 'ohlc' ? CANDLE : roleTaken(e.fn);
      if (taken === undefined) return { ...e, args };
      return { ...e, args: args.map((a, i) => (a.kind === 'column' && a.fn === undefined && taken[i] !== undefined ? { ...a, fn: taken[i] } : a)) };
    }
    case 'binary':
      return { ...e, left: candled(e.left), right: candled(e.right) };
    default:
      return e;
  }
}

/** Whether a column's matches pick one value of the split's column (`close[SPY]`, the split by symbol): one line, joined
 *  by every line of the split, not a line of its own (the review's Q5: matched inside each line, every other line went). */
function matchesSplit(b: Base, src: TsSource, split: string): boolean {
  // or its instrument: `close / close[SPY]` split by sector -- SPY's one line divides every sector's (the second audit's
  // S2: split by SPY's own sector, every other sector's line went)
  return b.where.some((m) => { const c = m.column ?? src.key; return c === split || (src.key !== undefined && c === src.key); });
}

/** A part's periods' type, the series' (the first part's): another's of another type is refused -- under a frequency
 *  the tile has, a candle's series read on their own dates (`candle`). */
function periodTyped(sh: Shared, type: string, alias: string): void {
  if (sh.periodType === undefined) sh.periodType = type;
  else if (type !== sh.periodType) {
    throw new CubeRefusal(sh.grain === undefined && sh.ts.grain !== undefined
      ? `series ${sh.series.id}'s candle reads ${sh.first}'s dates and ${alias}'s, of another type, on their own dates: read its series from grids of one date type`
      : `series ${sh.series.id} combines ${sh.first}'s dates with ${alias}'s, of another type: give the tile a frequency (Daily) to read both by day`);
  }
}

/**
 * A COLUMN'S OWN PART (steps 1 and 2): its source's rows -- the grid's filter as the tile reads it, the column's matches,
 * a date on every row -- each row's period at the tile's frequency, grouped by it (and by the split, a column of the
 * source the series reads first, unless it matches one value of it), the column taken by its period's aggregate, the
 * periods it has a value in alone (the review's Q1). A `last` or `first` counts the rows tied at the latest (earliest)
 * time, so the drawing says when one was taken of several (the review's Q7).
 */
function columnPart(sh: Shared, n: Extract<TsExpr, { kind: 'column' }>): Node {
  const alias = sourceAlias(sh.ts, n);
  if (alias === undefined) throw new CubeRefusal(`${n.column} names no source of the tile`);
  const b: Base = { key: baseKey(sh.ts, n), alias, column: n.column, fn: n.fn ?? 'last', where: n.where ?? [] };
  const { src, input } = sourceNamed(sh.ts, sh.inputs, alias);
  const s = input.snapshot;
  const typeOf = (c: string): string | undefined => typeIn(s, c);
  const dateType = typeOf(src.date);
  const conditions: ValueSpecification[] = [fn('isNotEmpty', col(src.date))];
  const filter = filterRead(src, input.filter);
  if (filter) conditions.push(filterNode(filter, 'x', typeOf));
  const match = matched(s, src.key, b.where);
  if (match) conditions.push(match);
  let rel = sourceWithDerived(s).filter(row(and(...conditions)));
  const { body, type, at } = periodOf(sh.grain, src.date, dateType);
  periodTyped(sh, type, alias);
  const seconds = !isDay(dateType);
  const ordRow = `__ts_or`;
  const atRow = `__ts_ar`;
  rel = rel.extend([
    derive(PERIOD, row(body)),
    derive(ordRow, row(fn('dateDiff', literalNode('1970-01-01', 'StrictDate'), one(src.date), enumValue(DURATION, seconds ? 'SECONDS' : 'DAYS')))),
    derive(atRow, row(at)),
  ]);
  const split = sh.split !== undefined && alias === sh.first && !matchesSplit(b, src, sh.split);
  const keys = split ? [sh.split!, PERIOD] : [PERIOD];
  const names = { fresh: (what: string): string => freshName(sh, what) };
  const value = names.fresh('base');
  const aggs: ColSpec[] = [
    baseAggregate(value, b, ordRow),
    agg(ORD, row(col(ordRow)), lambda(['y'], fn('max', variable('y')))),
    agg(AT, row(col(atRow)), lambda(['y'], fn('max', variable('y')))),
  ];
  const rows: string[] = [];
  if (b.fn === 'last' || b.fn === 'first') {
    // the rows at the time a `last` takes (a `first`'s earliest): more than one, and which was taken is the database's
    const tie = names.fresh('tie');
    rel = rel.extend([agg(tie, lambda(['p', 'w', 'r'], Writer.read(ordRow)), lambda(['y'], fn(b.fn === 'last' ? 'max' : 'min', variable('y'))))],
      fn('over', { _type: 'classInstance', type: 'colSpecArray', value: { colSpecs: keys.map((k) => ({ name: k })) } },
        collection([asc(ordRow)]), fn('rows', fn('unbounded'), fn('unbounded'))));
    const count = names.fresh('rows');
    aggs.push(agg(count, row(when(fn('equal', col(ordRow), col(tie)), lit.integer(1), none())), lambda(['y'], fn('count', variable('y')))));
    rows.push(count);
  }
  rel = rel.groupBy(keys, aggs);
  const part = split ? sh.split! : `${WINDOW_ALL_PREFIX}ts`;
  if (!split) rel = rel.extend([derive(part, row(lit.string('all')))]);
  rel = rel.filter(row(notEmpty(value)));
  return { w: new Writer(rel, { part, split, at: AT, seconds, rows, years: [] }, sh), v: { column: value } };
}

/**
 * PARTS JOINED on the period -- and on their line, both a line per value of the split -- each part's value computed
 * over its own dates first, at the operator or the function that reads them together (the review's Q1). `how` (the
 * tile's `align`, gs-quant's Interpolate): `intersect`, the dates every one has (gs-quant's intersect); else every date
 * any has, each part put onto them (#aligned). A line of the split joined with one line (`close[SPY]`) is that line's
 * per value; each period dated at its latest part's last observation.
 */
function joined(sh: Shared, nodes: readonly { readonly w: Writer; readonly v: Value }[], how: TsAlign = 'intersect'): Writer {
  // the parts of a line per value first: theirs is the line the join is partitioned by
  // every date any has: merged in turn, each read once (#aligned)
  if (how !== 'intersect') return aligned(sh, nodes.map((n) => ({ w: n.w, values: valueColumns(n.v) })), how).w;
  const order = [...nodes].sort((a, b) => Number(b.w.split) - Number(a.w.split));
  let left = order[0]!.w;
  let carried = valueColumns(order[0]!.v);
  for (const right of order.slice(1)) {
    const r = right.w;
    const fresh = (what: string): string => freshName(sh, what);
    const p = fresh('jp');
    const a = fresh('ja');
    const line = left.split && r.split ? fresh('jl') : undefined;
    let rel = r.rel.rename(PERIOD, p).rename(r.at, a);
    if (line !== undefined) rel = rel.rename(r.part, line);
    rel = rel.select([p, a, ...(line !== undefined ? [line] : []), ...valueColumns(right.v), ...r.rows, ...r.years]);
    const on = [fn('equal', property(variable('l'), PERIOD), property(variable('r'), p)),
      ...(line !== undefined ? [fn('equal', property(variable('l'), left.part), property(variable('r'), line))] : [])];
    let both = left.rel.join(rel, 'INNER', lambda(['l', 'r'], and(...on)));
    const at = fresh('at');
    both = both.extend([derive(at, row(when(fn('greaterThanEqual', one(left.at), one(a)), col(left.at), col(a))))]);
    left = new Writer(both, {
      part: left.part, split: left.split, at, seconds: left.seconds,
      rows: [...left.rows, ...r.rows], years: [...left.years, ...r.years],
    }, sh);
    carried = [...carried, ...valueColumns(right.v)];
  }
  return left;
}

/** A part as an alignment puts it onto dates: its writer, the value columns it fills, and those it carries as they are
 *  (a part's has-a-row marks, through the merges after). */
interface Aligned {
  readonly w: Writer;
  readonly values: readonly string[];
  readonly kept?: readonly string[];
}

/** Two parts merged (merged): their writer -- every date either has, by line -- and, for each as it was given, the
 *  column that is 1 where it had a row and its own ORD there. */
interface Merged {
  readonly w: Writer;
  readonly has: readonly [string, string];
  readonly ords: readonly [string, string];
}

/**
 * THE LINES OF THE SPLIT, read off the first source -- its rows as the tile reads them (a date on each, the grid's
 * filter) -- the line names a part of one line is put under (`close[SPY]` beside `close`): a leaf of the query, however
 * deep the parts merged, so a merge of it reads each part once (the Phase 1 review's 1).
 */
function splitLines(sh: Shared): Relation {
  const split = sh.split;
  if (split === undefined) throw new Error('a part of one line is put under the lines of a split: the series has none');
  const { src, input } = sourceNamed(sh.ts, sh.inputs, sh.first);
  const s = input.snapshot;
  const conditions: ValueSpecification[] = [fn('isNotEmpty', col(src.date))];
  const filter = filterRead(src, input.filter);
  if (filter) conditions.push(filterNode(filter, 'x', (c) => typeIn(s, c)));
  return sourceWithDerived(s).filter(row(and(...conditions))).select([split]).distinct([split]);
}

/**
 * TWO PARTS MERGED, every date either has (the next phases' 1; rewritten for the Phase 1 review's 1, 2026-10-26): a
 * FULL join on their line and their period -- each part read ONCE, so a nesting of them grows linearly (an as-of join
 * of each and a spine of both read each three times: a basket of six instruments under step was 2 MB of SQL) -- each
 * period dated at its latest part's last observation. A part of one line beside a split's goes under every line of it
 * (splitLines), the lines the split has not dropped. Each part's values, carried columns and kept columns keep their
 * names; none where it has no row.
 */
function merged(sh: Shared, a: Aligned, b: Aligned): Merged {
  // the split's part first: its lines are the merge's
  const swap = b.w.split && !a.w.split;
  const [L, R] = swap ? [b, a] : [a, b];
  const fresh = (what: string): string => freshName(sh, what);
  const part = L.w.part;
  const side = (s: Aligned, under: boolean): { rel: Relation; p: string; per: string; at: string; ord: string; has: string } => {
    const w = s.w;
    const own = [...s.values, ...(s.kept ?? []), ...w.rows, ...w.years];
    let rel = w.rel;
    let p = w.part;
    if (under) {
      // a part of one line under every line of the split, by a key both carry
      const k = fresh('al_k');
      const k2 = fresh('al_k');
      rel = splitLines(sh).extend([derive(k, row(lit.integer(1)))])
        .join(rel.select([PERIOD, w.at, ORD, ...own]).extend([derive(k2, row(lit.integer(1)))]), 'INNER',
          lambda(['l', 'r'], fn('equal', property(variable('l'), k), property(variable('r'), k2))));
      p = part;
    }
    const names = { p: fresh('al_p'), per: fresh('al_per'), at: fresh('al_at'), ord: fresh('al_ord'), has: fresh('al_has') };
    rel = rel.select([p, PERIOD, w.at, ORD, ...own]).rename(p, names.p).rename(PERIOD, names.per).rename(w.at, names.at).rename(ORD, names.ord)
      .extend([derive(names.has, row(lit.integer(1)))]);
    return { rel, ...names };
  };
  const l = side(L, false);
  const r = side(R, L.w.split !== R.w.split);
  const on = lambda(['l', 'r'], and(fn('equal', property(variable('l'), l.p), property(variable('r'), r.p)),
    fn('equal', property(variable('l'), l.per), property(variable('r'), r.per))));
  const either = (x: string, y: string): ValueSpecification => when(fn('isEmpty', col(x)), col(y), col(x));
  const latest = (x: string, y: string): ValueSpecification =>
    when(fn('isEmpty', col(x)), col(y), when(fn('isEmpty', col(y)), col(x), when(fn('greaterThanEqual', one(x), one(y)), col(x), col(y))));
  const at = fresh('al_at');
  const keep = (s: Aligned): string[] => [...s.values, ...(s.kept ?? []), ...s.w.rows, ...s.w.years];
  const rel = l.rel.join(r.rel, 'FULL', on)
    .extend([derive(part, row(either(l.p, r.p))), derive(PERIOD, row(either(l.per, r.per))), derive(at, row(latest(l.at, r.at))),
      derive(ORD, row(latest(l.ord, r.ord)))])
    .select([part, PERIOD, at, ORD, l.ord, r.ord, l.has, r.has, ...keep(L), ...keep(R)]);
  const w = new Writer(rel, {
    part, split: L.w.split, at, seconds: L.w.seconds, rows: [...L.w.rows, ...R.w.rows], years: [...L.w.years, ...R.w.years],
  }, sh);
  if (L.w.split !== R.w.split) {
    // the lines the split has (a line of the source it never reads dropped): the database's QUALIFY, a window's
    const line = w.window('al_line', 'max', Writer.read(l.has), 'all');
    w.rel = w.rel.filter(row(fn('isNotEmpty', col(line))));
  }
  return swap ? { w, has: [r.has, l.has], ords: [r.ord, l.ord] } : { w, has: [l.has, r.has], ords: [l.ord, r.ord] };
}

/**
 * A PART'S VALUES PUT ONTO THE MERGED DATES, each keeping its name (#merged's columns: `ord`, its own ORD where it has a
 * row): `nan`, none where it has none (as merged); `zero`, 0 there; `step`, each date its last value at or before it --
 * before its first, its first (gs-quant's ffill then bfill); `time`, linear in time between its values before and after
 * (none outside them: pandas' limit_area='inside'). Its last and next valued periods its own windows (a running maximum
 * of the periods it has a value in, and the value there) -- over the one relation, nothing read again.
 */
function fill(w: Writer, values: readonly string[], ord: string, how: Exclude<TsAlign, 'intersect'>): void {
  if (how === 'nan' || values.length === 0) return;
  const fresh = (what: string): string => w.fresh(what);
  const R = Writer.read;
  // each column replaced under its own name: the merged one kept under another
  const replace = (c: string, body: (raw: string) => ValueSpecification): void => {
    const raw = fresh('al_raw');
    w.rel = w.rel.rename(c, raw);
    w.rel = w.rel.extend([derive(c, row(body(raw)))]);
  };
  if (how === 'zero') {
    for (const v of values) replace(v, (raw) => when(fn('isEmpty', col(raw)), lit.integer(0), col(raw)));
    return;
  }
  const valued = w.row('al_vp', when(fn('isNotEmpty', col(values[0]!)), col(PERIOD), none()));
  const lastP = w.window('al_lastp', 'max', R(valued), 'running');
  const at = (p: string, c: string): string => w.window('al_v', 'max', R(w.row('al_at', when(fn('equal', col(PERIOD), col(p)), col(c), none()))), 'all', p);
  // its carried columns (the rows a value was one of, its periods a year) where it has a row alone: a date filled took
  // none of its rows -- each merge filling every one before it grew the query faster than its parts
  if (how === 'step') {
    const firstP = w.window('al_firstp', 'min', R(valued), 'all');
    const made = values.map((v) => [v, at(lastP, v), at(firstP, v)] as const);
    for (const [v, last, first] of made) replace(v, () => when(fn('isEmpty', col(last)), col(first), col(last)));
  } else {
    const nextP = w.window('al_nextp', 'min', R(valued), 'rest');
    const t0 = at(lastP, ord);
    const t1 = at(nextP, ord);
    const made = values.map((v) => [v, at(lastP, v), at(nextP, v)] as const);
    for (const [v, a, b] of made) {
      // inside its values alone; at one of its own dates, its own value
      const between = op('plus', one(a), fn('divide', op('times', op('minus', one(b), one(a)), op('minus', one(ORD), one(t0))), op('minus', one(t1), one(t0))));
      replace(v, () => when(or(fn('isEmpty', col(a)), fn('isEmpty', col(b))), none(), when(fn('equal', one(t0), one(t1)), col(a), between)));
    }
  }
}

/**
 * EVERY DATE ANY PART HAS (gs-quant's `align` but intersect): the parts merged in turn (#merged: each read once), each
 * part's values put onto the dates by `how` (#fill) -- and, for each part, the column that is 1 where it had a row (an
 * if's flags, an append's x: what keeps a date).
 */
function aligned(sh: Shared, parts: readonly Aligned[], how: Exclude<TsAlign, 'intersect'>):
  { readonly w: Writer; readonly has: readonly string[]; readonly ords: readonly string[] } {
  if (parts.length < 2) throw new Error('an alignment is of two parts or more');
  let acc: Aligned = parts[0]!;
  const has: string[] = [];
  const ords: string[] = [];
  for (const next of parts.slice(1)) {
    const m = merged(sh, acc, next);
    fill(m.w, acc.values, m.ords[0], how);
    fill(m.w, next.values, m.ords[1], how);
    if (has.length === 0) {
      has.push(m.has[0]);
      ords.push(m.ords[0]);
    }
    has.push(m.has[1]);
    ords.push(m.ords[1]);
    // the parts merged before: their has-a-row marks and their own ORDs carried, as they are
    acc = { w: m.w, values: [...acc.values, ...next.values], kept: [...has, ...ords] };
  }
  return { w: acc.w, has, ords };
}

const OPS: Readonly<Record<'+' | '-' | '*' | '/', string>> = { '+': 'plus', '-': 'minus', '*': 'times', '/': 'divide' };

/** A condition's test as the database's: a comparison of the two sides, or two conditions joined (each true unless 0). */
function conditionTest(o: TsOperator, l: ValueSpecification, r: ValueSpecification): ValueSpecification {
  const holds = (x: ValueSpecification): ValueSpecification => fn('not', fn('equal', x, lit.integer(0)));
  switch (o) {
    case '<': return fn('lessThan', l, r);
    case '<=': return fn('lessThanEqual', l, r);
    case '>': return fn('greaterThan', l, r);
    case '>=': return fn('greaterThanEqual', l, r);
    case '==': return fn('equal', l, r);
    case '!=': return fn('not', fn('equal', l, r));
    case 'and': return and(holds(l), holds(r));
    case 'or': return or(holds(l), holds(r));
    case '+': case '-': case '*': case '/':
      throw new Error(`${o} is no condition`);
  }
}

/** A node of the expression, written: its own columns after those of what it reads. */
function write(sh: Shared, e: TsExpr): Node {
  switch (e.kind) {
    case 'number':
      return { constant: Number.isInteger(e.value) ? lit.integer(e.value) : float(e.value) };
    case 'ref':
      throw new Error('a series is read inlined (timeseries-spec.ts inlined)');
    case 'column':
      return columnPart(sh, e);
    case 'binary': {
      const l = write(sh, e.left);
      const r = write(sh, e.right);
      // a condition: 1 where it holds, 0 where not, none where a side has none (the expert functions, 2026-10-26)
      if (isConditionOp(e.op)) {
        if ('constant' in l && 'constant' in r) return { constant: when(conditionTest(e.op, l.constant, r.constant), lit.integer(1), lit.integer(0)) };
        const wc = 'constant' in l ? (r as Exclude<Node, { constant: unknown }>).w
          : 'constant' in r ? l.w : joined(sh, [l, r as Exclude<Node, { constant: unknown }>], sh.ts.align);
        const missing = [l, r].flatMap((x) => ('constant' in x ? [] : [fn('isEmpty', col(columnOfValue(x.v)))]));
        return { w: wc, v: { column: wc.row('cond', when(missing.length === 1 ? missing[0]! : or(...missing), none(),
          when(conditionTest(e.op, asOperand(l), asOperand(r)), lit.integer(1), lit.integer(0)))) } };
      }
      if ('constant' in l && 'constant' in r) return { constant: op(OPS[e.op as '+' | '-' | '*' | '/'], l.constant, r.constant) };
      const w = 'constant' in l ? (r as Exclude<Node, { constant: unknown }>).w
        : 'constant' in r ? l.w : joined(sh, [l, r as Exclude<Node, { constant: unknown }>], sh.ts.align);
      if (e.op === '/') {
        const denominator = 'constant' in r ? w.row('num', r.constant) : columnOfValue(r.v);
        return { w, v: { column: w.ratio('div', asOperand(l), denominator) } };
      }
      return { w, v: { column: w.row('op', op(OPS[e.op as '+' | '-' | '*' | '/'], asOperand(l), asOperand(r))) } };
    }
    case 'call':
      return call(sh, e);
  }
}

/** A function's series argument, written: a part (a function reads a series, never a number alone). */
function seriesArg(sh: Shared, e: Extract<TsExpr, { kind: 'call' }>, i: number): { readonly w: Writer; readonly v: Value } {
  const n = write(sh, e.args[i]!);
  if ('constant' in n) throw new CubeRefusal(`${e.fn} reads a series: its ${ordinal(i + 1)} argument is a number`);
  return n;
}

/** A place in words: first, second ... tenth, then 11th, 21st. */
function ordinal(n: number): string {
  const words = ['first', 'second', 'third', 'fourth', 'fifth', 'sixth', 'seventh', 'eighth', 'ninth', 'tenth'];
  if (n >= 1 && n <= words.length) return words[n - 1]!;
  const tens = n % 100;
  const suffix = tens >= 11 && tens <= 13 ? 'th' : ({ 1: 'st', 2: 'nd', 3: 'rd' } as Record<number, string>)[n % 10] ?? 'th';
  return `${n}${suffix}`;
}

/**
 * A CANDLE (ohlc): its four series joined on their dates. At a frequency, each is computed on its sources' own dates
 * first -- its functions over them, its arithmetic on each one -- and each period then takes the first open, the
 * highest high, the lowest low and the last close of them, the database's (the review's N4: each column was taken by
 * the period first, so a candle of `close / 2` weekly was the week's last, four times over, flat).
 */
function candle(sh: Shared, e: Extract<TsExpr, { kind: 'call' }>): Node {
  const r = rolled(sh, e, CANDLE);
  const [open, high, low, close] = r.columns as [string, string, string, string];
  return { w: r.w, v: { open, high, low, close } };
}

/**
 * A FUNCTION'S SERIES AT THE TILE'S FREQUENCY, EACH BY ITS ROLE (a candle's; the Phase 4 review's 1: a weekly ATR read
 * each week's last high and low, an OBV its last day's volume): under a frequency, its first series -- one a role in
 * `taken` -- each read on its own dates, joined, then each period of the frequency taken by its role (an open its
 * first, a high its most, a low its least, a close its last, a volume its total), and each of `sums` (a row of the
 * series on their own dates: a price times its volume) its total over the period. Of no frequency, the series as they
 * are, `sums` each a row of them.
 */
function rolled(sh: Shared, e: Extract<TsExpr, { kind: 'call' }>, taken: readonly TsAggregate[],
  sums: readonly ((columns: readonly string[]) => ValueSpecification)[] = []): { readonly w: Writer; readonly columns: readonly string[]; readonly sums: readonly string[] } {
  const own: Shared = sh.grain === undefined ? sh : { ...sh, grain: undefined, periodType: undefined };
  const parts = taken.map((_, i) => seriesArg(own, e, i));
  const inner = joined(own, parts);
  const columns = parts.map((p) => columnOfValue(p.v));
  const made = sums.map((f) => inner.row('rs', f(columns)));
  if (sh.grain === undefined) return { w: inner, columns, sums: made };
  // each period of the tile's frequency, of the dates all have
  const fine = own.periodType!;
  const coarse = periodOf(sh.grain, PERIOD, fine);
  periodTyped(sh, coarse.type, sh.first);
  const p = freshName(sh, 'cp');
  const a = freshName(sh, 'ca');
  const rel = inner.rel
    .filter(row(and(...columns.map((c) => fn('isNotEmpty', col(c))))))
    .extend([derive(p, row(coarse.body)), derive(a, row(periodOf(sh.grain, inner.at, fine).at))]);
  const y = (f: string): Lambda => lambda(['y'], fn(f, variable('y')));
  const by = (t: TsAggregate, c: string, name: string): ColSpec => (t === 'last' || t === 'first'
    ? agg(name, row(fn('rowMapper', col(c), col(ORD))), y(t === 'last' ? 'maxBy' : 'minBy'))
    : agg(name, row(col(c)), y(t)));
  const out = columns.map(() => freshName(sh, 'rv'));
  const totals = made.map(() => freshName(sh, 'rsum'));
  // the most rows a date's value took one of, and the periods a year a function inside annualized by, carried
  const rows = inner.rows.map((c) => [c, freshName(sh, 'rows')] as const);
  const years = inner.years.map((c) => [c, freshName(sh, 'py')] as const);
  const grouped = rel.groupBy([inner.part, p], [
    ...columns.map((c, i) => by(taken[i]!, c, out[i]!)),
    ...made.map((c, i) => by('sum', c, totals[i]!)),
    agg(AT, row(col(a)), y('max')),
    ...[...rows, ...years].map(([from, to]) => agg(to, row(col(from)), y('max'))),
    agg(ORD, row(col(ORD)), y('max')),
  ]).rename(p, PERIOD);
  const w = new Writer(grouped, {
    part: inner.part, split: inner.split, at: AT, seconds: inner.seconds, rows: rows.map(([, c]) => c), years: years.map(([, c]) => c),
  }, sh);
  return { w, columns: out, sums: totals };
}

/** A part, a written argument that is no number. */
type Part = Exclude<Node, { constant: unknown }>;

/** Of written arguments, the parts (no number), each a series. */
const partsOf = (nodes: readonly Node[]): Part[] => nodes.filter((n): n is Part => !('constant' in n));

/**
 * GS-QUANT'S REMAINING FUNCTIONS of several series, or of a series and a number (the next phases' 1, 2026-10-26):
 * if, compare, union, append, prepend, excess_returns, weighted_sum -- or undefined, the function another's.
 */
function otherCalls(sh: Shared, e: Extract<TsExpr, { kind: 'call' }>): Node | undefined {
  const written = (): Node[] => e.args.map((a) => write(sh, a));
  switch (e.fn) {
    case 'if': {
      // x where the flags hold (not 0), y where not, each a series or a number -- on the flags' dates, the branch taken
      // none where it has none, whatever the tile's align (gs-quant's if_: no method; the Phase 1 review's 3)
      const [flags, x, y] = written() as [Node, Node, Node];
      if ('constant' in flags) throw new CubeRefusal('if reads a series of flags first: its first argument is a number');
      const parts = partsOf([flags, x, y]);
      let w = flags.w;
      if (parts.length > 1) {
        const al = aligned(sh, parts.map((n) => ({ w: n.w, values: valueColumns(n.v) })), 'nan');
        w = al.w;
        // the flags are the first part: their dates alone
        w.rel = w.rel.filter(row(fn('isNotEmpty', col(al.has[0]!))));
      }
      const f = columnOfValue(flags.v);
      const branch = (n: Node): ValueSpecification => ('constant' in n ? n.constant : col(columnOfValue(n.v)));
      return { w, v: { column: w.row('if', when(fn('isEmpty', col(f)), none(),
        when(fn('not', fn('equal', one(f), lit.integer(0))), branch(x), branch(y)))) } };
    }
    case 'compare': {
      const [x, y] = written() as [Node, Node];
      if ('constant' in x && 'constant' in y) {
        return { constant: when(fn('greaterThan', x.constant, y.constant), lit.integer(1), when(fn('lessThan', x.constant, y.constant), lit.integer(-1), lit.integer(0))) };
      }
      const parts = partsOf([x, y]);
      const w = parts.length === 1 ? parts[0]!.w : joined(sh, parts, sh.ts.align);
      // gs-quant's (x > y) * 1 + (x < y) * -1: beside none, neither holds -- 0 (the Phase 1 review's 6)
      return { w, v: { column: w.row('cmp',
        when(fn('greaterThan', asOperand(x), asOperand(y)), lit.integer(1), when(fn('lessThan', asOperand(x), asOperand(y)), lit.integer(-1), lit.integer(0)))) } };
    }
    case 'union':
    case 'append':
    case 'prepend': {
      // every date either has (union: x where it has a value, else y); gs-quant's dates for a splice -- x's every row to
      // its last, then y's after it (append); x's before y's first row, then y's every row (prepend) -- the other's in
      // between none of them (the Phase 1 review's 2: kept as empty rows, they broke every function after it)
      const x = seriesArg(sh, e, 0);
      const y = seriesArg(sh, e, 1);
      const a = columnOfValue(x.v);
      const b = columnOfValue(y.v);
      const al = aligned(sh, [{ w: x.w, values: [a] }, { w: y.w, values: [b] }], 'nan');
      const w = al.w;
      const [hx, hy] = al.has as [string, string];
      if (e.fn === 'union') return { w, v: { column: w.row('union', when(fn('isNotEmpty', col(a)), col(a), col(b))) } };
      const R = Writer.read;
      const periodOf_ = (has: string): string => w.row('sp_p', when(fn('isNotEmpty', col(has)), col(PERIOD), none()));
      if (e.fn === 'append') {
        const last = w.window('x_last', 'max', R(periodOf_(hx)), 'all');
        w.rel = w.rel.filter(row(or(fn('isNotEmpty', col(hx)),
          and(fn('isNotEmpty', col(hy)), or(fn('isEmpty', col(last)), fn('greaterThan', col(PERIOD), col(last)))))));
        return { w, v: { column: w.row('append', when(fn('isNotEmpty', col(hx)), col(a), col(b))) } };
      }
      const first = w.window('y_first', 'min', R(periodOf_(hy)), 'all');
      w.rel = w.rel.filter(row(or(fn('isNotEmpty', col(hy)),
        and(fn('isNotEmpty', col(hx)), or(fn('isEmpty', col(first)), fn('lessThan', col(PERIOD), col(first)))))));
      return { w, v: { column: w.row('prepend', when(fn('isNotEmpty', col(hy)), col(b), col(a))) } };
    }
    case 'excess_returns': {
      const x = seriesArg(sh, e, 0);
      const rate = e.args[1]!;
      if (rate.kind === 'number') {
        // over a rate: x less the rate accrued on it day by day, actual/360 -- gs-quant's er_j = er_j-1 + x_j - x_j-1 (1 +
        // rate f_j), summed: x_j - rate * the sum of x_k-1 f_k to date
        const w = x.w;
        const a = columnOfValue(x.v);
        const days = w.seconds ? fn('divide', op('minus', one(ORD), one(w.lag(ORD, 1))), lit.integer(86400)) : op('minus', one(ORD), one(w.lag(ORD, 1)));
        const accrued = w.row('accr', op('times', one(w.lag(a, 1)), fn('divide', days, lit.integer(360))));
        const total = w.window('accrued', 'plus', Writer.read(accrued), 'running');
        return { w, v: { column: w.row('er', op('minus', one(a), op('times', float(rate.value), when(fn('isEmpty', col(total)), float(0), one(total))))) } };
      }
      // over a cash index: on the dates both have, x's first value times the product of (1 + x's return - y's)
      const b = seriesArg(sh, e, 1);
      const w = joined(sh, [x, b]);
      const a = columnOfValue(x.v);
      const g = w.row('erg', op('minus', op('plus', lit.integer(1), one(w.simple(a))), one(w.simple(columnOfValue(b.v)))));
      return { w, v: { column: w.row('er', when(fn('isEmpty', col(a)), none(), op('times', one(w.edgeValue(a, 'first')), one(w.product(g, 'running'))))) } };
    }
    case 'weighted_sum': {
      // each series and its weight, on the dates all have: their weighted sum over the weights' (gs-quant's intersect)
      const series = e.args.filter((_, i) => i % 2 === 0).map((_, i) => seriesArg(sh, e, i * 2));
      const weights = e.args.filter((_, i) => i % 2 === 1).map((x) => (x as Extract<TsExpr, { kind: 'number' }>).value);
      const w = joined(sh, series);
      // their sum the database's too (the file's rule: nothing here computes a value -- the Phase 1 review's 8)
      const total = weights.map((x) => float(x)).reduce((t, x) => op('plus', t, x));
      const sum = series.map((p, i) => op('times', float(weights[i]!), one(columnOfValue(p.v))))
        .reduce((t, x) => op('plus', t, x));
      return { w, v: { column: w.row('wsum', fn('divide', sum, total)) } };
    }
    default:
      return undefined;
  }
}

/** A window or a lag a function is told: a whole number of observations, or a calendar period (the spec's reader holds
 *  it to one). */
function spanOf(e: Extract<TsExpr, { kind: 'call' }>, name: string): Span {
  const v = paramOf(e, name);
  if (typeof v === 'number') return v;
  const t = tenorOf(v);
  if (t === undefined) throw new CubeRefusal(`${e.fn}'s ${name} ${JSON.stringify(v)} is neither a whole number nor a calendar period (3m, 1y)`);
  return t;
}

/**
 * THE ANALYSTS' PACKS (the next phases' 4, 2026-10-26) of several series: a candle's columns' technicals (Wilder's true
 * range, Lane's stochastic, Keltner's and Donchian's channels, Granville's on-balance volume, a volume-weighted price),
 * a series against a benchmark, a total return, a basket, a pair's lead -- or undefined, the function another's. Each
 * series on its own dates first (a benchmark's returns each on its own, as a correlation's), then joined on the dates
 * all have; every figure the database's.
 */
function analystCalls(sh: Shared, e: Extract<TsExpr, { kind: 'call' }>): Node | undefined {
  const n = (name: string): number => paramOf(e, name) as number;
  const R = Writer.read;
  /** Each period's true range: the most of the high less the low, the high less the close before and the close before
   *  less the low -- the first, its high less its low. */
  const trueRange = (w: Writer, h: string, l: string, c: string): string => {
    const pc = w.lag(c, 1);
    const hl = op('minus', one(h), one(l));
    return w.row('tr', when(fn('isEmpty', col(pc)), hl,
      fn('max', fn('max', hl, fn('abs', op('minus', one(h), one(pc)))), fn('abs', op('minus', one(pc), one(l))))));
  };
  /** Two series' simple returns, each on its own dates, on the dates both have one (a pair's, a benchmark's). */
  const returnsOf = (k = 0): { readonly w: Writer; readonly a: string; readonly b: string } => {
    const x = seriesArg(sh, e, 0);
    const y = seriesArg(sh, e, 1);
    const ra = x.w.simple(columnOfValue(x.v));
    const r0 = y.w.simple(columnOfValue(y.v));
    // the second's k observations before (a lag), or after (a lead), on its own dates
    const rb = k === 0 ? r0 : k > 0 ? y.w.lag(r0, k) : y.w.lead(r0, -k);
    const w = joined(sh, [{ w: x.w, v: { column: ra } }, { w: y.w, v: { column: rb } }]);
    return { w, a: w.row('pa', when(fn('isEmpty', col(rb)), none(), col(ra))), b: w.row('pb', when(fn('isEmpty', col(ra)), none(), col(rb))) };
  };
  switch (e.fn) {
    case 'atr':
    case 'stochastic':
    case 'keltner_channels': {
      // each period's highest high, lowest low and last close, at the tile's frequency (rolled)
      const { w, columns } = rolled(sh, e, roleTaken(e.fn)!);
      const [hi, lo, cl] = columns as [string, string, string];
      if (e.fn === 'atr') return { w, v: { column: w.smoothed(trueRange(w, hi, lo, cl), n('w')) } };
      if (e.fn === 'stochastic') {
        const size = n('w');
        const hh = w.window('hh', 'max', R(hi), size);
        const ll = w.window('ll', 'min', R(lo), size);
        const k = w.row('stoch', when(fn('equal', one(hh), one(ll)), none(),
          op('times', lit.integer(100), fn('divide', op('minus', one(cl), one(ll)), op('minus', one(hh), one(ll))))));
        return { w, v: { column: w.ramped(k, cl, size) } };
      }
      // the close's exponential average of span w (its weight of the one before (w - 1) / (w + 1), the database's), k
      // average true ranges either side
      const span = n('w');
      const mid = span <= 1 ? cl : w.ewm(cl, fn('divide', lit.integer(span - 1), lit.integer(span + 1)));
      const atr = w.smoothed(trueRange(w, hi, lo, cl), n('a'));
      const k = float(n('k'));
      // drawn once the average holds w closes (the Phase 4 review's 7: an EMA 20 of 11 drawn)
      const lower = w.ramped(w.row('lower', op('minus', one(mid), op('times', k, one(atr)))), cl, span, 'running');
      const upper = w.ramped(w.row('upper', op('plus', one(mid), op('times', k, one(atr)))), cl, span, 'running');
      return { w, v: { lower, upper, width: w.row('width', op('minus', one(upper), one(lower))) } };
    }
    case 'donchian_channels': {
      const { w, columns } = rolled(sh, e, roleTaken(e.fn)!);
      const [hi, lo] = columns as [string, string];
      const size = spanOf(e, 'w');
      const frame = Writer.frame(size);
      const upper = w.ramped(w.window('dc_hi', 'max', R(hi), frame), hi, size, frame);
      const lower = w.ramped(w.window('dc_lo', 'min', R(lo), frame), lo, size, frame);
      return { w, v: { lower, upper, width: w.row('width', op('minus', one(upper), one(lower))) } };
    }
    case 'obv': {
      // each period's last close and its whole volume
      const { w, columns } = rolled(sh, e, roleTaken(e.fn)!);
      const [cl, vol] = columns as [string, string];
      const d = w.row('d', op('minus', one(cl), one(w.lag(cl, 1))));
      const signed = w.row('obv_v', when(fn('isEmpty', col(d)), float(0), when(fn('greaterThan', one(d), lit.integer(0)), one(vol),
        when(fn('lessThan', one(d), lit.integer(0)), op('minus', float(0), one(vol)), float(0)))));
      return { w, v: { column: w.window('obv', 'plus', R(signed), 'running') } };
    }
    case 'vwap': {
      // each price times its volume on its own dates, totalled a period (at a frequency, a week's every day's)
      const { w, columns, sums } = rolled(sh, e, roleTaken(e.fn)!, [([p, v]) => op('times', one(p!), one(v!))]);
      const [px, vol] = columns as [string, string];
      const size = spanOf(e, 'w');
      const frame = Writer.frame(size);
      const traded = w.window('pv_s', 'plus', R(sums[0]!), frame);
      const volume = w.window('v_s', 'plus', R(vol), frame);
      return { w, v: { column: w.ramped(w.ratio('vwap', one(traded), volume), px, size, frame) } };
    }
    case 'tracking_error':
    case 'information_ratio':
    case 'up_capture':
    case 'down_capture': {
      const { w, a, b } = returnsOf();
      const size = spanOf(e, 'w');
      const frame = Writer.frame(size);
      if (e.fn === 'up_capture' || e.fn === 'down_capture') {
        // the first's mean return on the periods the benchmark rose (fell) over the benchmark's mean then
        const then = (c: string): string => w.row('cap', when(fn(e.fn === 'up_capture' ? 'greaterThan' : 'lessThan', one(b), lit.integer(0)), col(c), none()));
        const ma = w.window('cap_a', 'average', R(then(a)), frame);
        const mb = w.window('cap_b', 'average', R(then(b)), frame);
        return { w, v: { column: w.ramped(w.ratio('capture', one(ma), mb), a, size, frame) } };
      }
      const active = w.row('act', op('minus', one(a), one(b)));
      const sd = w.std(active, size);
      if (e.fn === 'tracking_error') return { w, v: { column: w.row('te', op('times', op('times', one(sd), w.annual()), lit.integer(100))) } };
      const mean = w.window('act_m', 'average', R(active), frame);
      return { w, v: { column: w.row('ir', when(fn('equal', one(sd), lit.integer(0)), none(), op('times', fn('divide', one(mean), one(sd)), w.annual()))) } };
    }
    case 'lead_lag': {
      const { w, a, b } = returnsOf(n('lag'));
      const size = spanOf(e, 'w');
      const frame = Writer.frame(size);
      return { w, v: { column: w.ramped(w.window('corr', 'corr', fn('rowMapper', R(a), R(b)), frame), a, atLeast(size, 2), frame) } };
    }
    case 'total_return': {
      const x = seriesArg(sh, e, 0);
      const px = columnOfValue(x.v);
      const told = e.args[1]!;
      let w: Writer;
      let g: string;
      if (told.kind === 'number') {
        // a yield a year, accrued day by day: each period's price over the one before, and the yield over the days since
        w = x.w;
        const days = w.seconds ? fn('divide', op('minus', one(ORD), one(w.lag(ORD, 1))), lit.integer(86400)) : op('minus', one(ORD), one(w.lag(ORD, 1)));
        g = w.row('trg', op('plus', fn('divide', one(px), one(w.lag(px, 1))), op('times', float(told.value), fn('divide', days, lit.integer(365)))));
      } else {
        // dividends on their dates: (price + the dividends since the price before) / the price before, on the price's
        // dates -- a dividend of a date the price has none of paid at its next: their running total over every date
        // either has, then the dates of a price alone, kept by a filter reading that total (a filter of a plain column
        // folds under its select's windows, Fold.filterSlot; one reading a window is the database's QUALIFY, after them)
        const d = seriesArg(sh, e, 1);
        const dv = columnOfValue(d.v);
        const al = aligned(sh, [{ w: x.w, values: [px] }, { w: d.w, values: [dv] }], 'nan');
        w = al.w;
        const paid = w.window('tr_paid', 'plus', R(w.row('tr_dv', when(fn('isEmpty', col(dv)), float(0), col(dv)))), 'running');
        w.rel = w.rel.filter(row(and(fn('isNotEmpty', col(paid)), fn('isNotEmpty', col(px)))));
        g = w.row('trg', fn('divide', op('plus', one(px), op('minus', one(paid), one(w.lag(paid, 1)))), one(w.lag(px, 1))));
      }
      // its first value, then each period's growth compounded (the first's none: none before it)
      return { w, v: { column: w.row('tr', when(fn('isEmpty', col(px)), none(), op('times', one(w.edgeValue(px, 'first')), one(w.product(g, 'running'))))) } };
    }
    case 'basket':
      return basket(sh, e);
    default:
      return undefined;
  }
}

/**
 * A BASKET (gs-quant's backtesting.basket_series, its costs none): its series held in weights from 100, on the dates
 * all have -- units fixed between rebalances, set at each to the basket's then in its weights. Each date's rebalance
 * period: its own (daily); the weeks, or the months, from the first date (a rebalance the first date on or after each,
 * gs-quant's `min(cal[cal >= date])`; a month's day kept, or its last where it has none, as relativedelta's). Within a
 * period, 1 + Σ w (price / its price at the period's first date - 1); into it, the period before's growth to its first
 * date; the basket 100 times those growths compounded, times its growth within -- every figure the database's.
 */
function basket(sh: Shared, e: Extract<TsExpr, { kind: 'call' }>): Node {
  const series = e.args.filter((_, i) => i % 2 === 0).map((_, i) => seriesArg(sh, e, i * 2));
  const weights = e.args.filter((_, i) => i % 2 === 1).map((x) => (x as Extract<TsExpr, { kind: 'number' }>).value);
  const w = joined(sh, series);
  const R = Writer.read;
  const prices = series.map((p) => columnOfValue(p.v));
  const rebalance = paramOf(e, 'rebalance') as string;
  const at = one(w.at);
  const at0 = one(w.edgeValue(w.at, 'first'));
  let period: ValueSpecification;
  if (rebalance === 'daily') period = col(ORD);
  else if (rebalance === 'weekly') {
    const ord0 = one(w.edgeValue(ORD, 'first'));
    const days = w.seconds ? fn('divide', op('minus', one(ORD), ord0), lit.integer(86400)) : op('minus', one(ORD), ord0);
    period = fn('floor', fn('divide', days, lit.integer(7)));
  } else if (rebalance === 'monthly') {
    // the months from the first date's, less one where the date is before its month's rebalance: the first date that
    // many months on, the database's (its day kept, or its month's last where it has none -- relativedelta's)
    const months = one(w.row('bk_m', op('plus', op('times', op('minus', fn('year', at), fn('year', at0)), lit.integer(12)),
      op('minus', fn('monthNumber', at), fn('monthNumber', at0)))));
    const due = fn('adjust', at0, months, enumValue(DURATION, 'MONTHS'));
    period = op('minus', months, when(fn('lessThan', at, due), lit.integer(1), lit.integer(0)));
  } else {
    throw new CubeRefusal(`a basket rebalances daily, weekly or monthly, not ${JSON.stringify(rebalance)}`);
  }
  const k = w.row('bk_k', period);
  const first = w.window('bk_f', 'min', R(ORD), 'all', k);
  const atFirst = fn('equal', one(ORD), one(first));
  const bases = prices.map((c) => w.window('bk_b', 'max', R(w.row('bk_bv', when(atFirst, col(c), none()))), 'all', k));
  const growth = (over: readonly string[]): ValueSpecification =>
    op('plus', lit.integer(1), weights.map((x, i) => op('times', float(x), op('minus', fn('divide', one(prices[i]!), one(over[i]!)), lit.integer(1)))).reduce((t, x) => op('plus', t, x)));
  const within = w.row('bk_in', growth(bases));
  // into a period, at its first date: each price over the period before's base (the row before carries it)
  const before = bases.map((b) => w.lag(b, 1));
  const into = w.row('bk_g', when(and(atFirst, fn('isNotEmpty', col(before[0]!))), growth(before), none()));
  // none from a price of none on, as gs-quant's NaN carries through its units (the Phase 4 review's 8: taken as flat)
  const gaps = w.window('bk_gaps', 'count', R(w.row('bk_gap', when(or(...prices.map((c) => fn('isEmpty', col(c)))), lit.integer(1), none()))), 'running');
  return { w, v: { column: w.row('basket', when(fn('greaterThan', one(gaps), lit.integer(0)), none(),
    op('times', float(100), op('times', one(w.product(into, 'running')), one(within))))) } };
}

/** A cycle in the observations of a tile's frequency (a classical decomposition's period): undefined, none whole. */
const CYCLES: Readonly<Partial<Record<TimeGrain, Readonly<Partial<Record<string, number>>>>>> = {
  day: { week: 5, month: 21, quarter: 63, year: 252 },
  week: { month: 4, quarter: 13, year: 52 },
  month: { quarter: 3, year: 12 },
  quarter: { year: 4 },
};

function call(sh: Shared, e: Extract<TsExpr, { kind: 'call' }>): Node {
  const n = (name: string): number => paramOf(e, name) as number;
  // a window or a lag: a whole number of observations, or a calendar period (the spec's reader holds it to one)
  const span = (name: string): Span => spanOf(e, name);
  const word = (name: string): string => paramOf(e, name) as string;
  const R = Writer.read;
  // the pairs and the candle: each series on its own dates, then joined
  if (e.fn === 'correlation' || e.fn === 'beta' || e.fn === 'cov' || e.fn === 'alpha' || e.fn === 'r_squared'
    || e.fn === 'hedge_ratio' || e.fn === 'residual') {
    const a = seriesArg(sh, e, 0);
    const b = seriesArg(sh, e, 1);
    // gs-quant's correlation and beta read each series' returns, taken on its own dates, then aligned (an alpha and an
    // R² too); cov, a hedge ratio and a residual the series as they are (a regression of the levels)
    const levels = e.fn === 'cov' || e.fn === 'hedge_ratio' || e.fn === 'residual';
    const ra = levels ? columnOfValue(a.v) : a.w.simple(columnOfValue(a.v));
    const rb = levels ? columnOfValue(b.v) : b.w.simple(columnOfValue(b.v));
    const w = joined(sh, [{ w: a.w, v: { column: ra } }, { w: b.w, v: { column: rb } }]);
    const size = span('w');
    const frame = Writer.frame(size);
    // each side on the window's pairs, the dates both have a value (a lag's, a ramped mean's, a first return's leading
    // empties): a mean, a variance and the ramp of the same sample as the covariance's (the E9 review's 1: an intercept
    // of two samples, its sign wrong)
    const pa = w.row('pa', when(fn('isEmpty', col(rb)), none(), col(ra)));
    const pb = w.row('pb', when(fn('isEmpty', col(ra)), none(), col(rb)));
    const pair = fn('rowMapper', R(pa), R(pb));
    let v: string;
    if (e.fn === 'correlation') v = w.window('corr', 'corr', pair, frame);
    else if (e.fn === 'r_squared') {
      const corr = w.window('corr', 'corr', pair, frame);
      v = w.row('r2', op('times', one(corr), one(corr)));
    } else if (e.fn === 'cov') v = w.window('cov', 'covarSample', pair, frame);
    else {
      // the slope of the first on the second over the window (least squares): their covariance over its variance
      const cov = w.window('cov', 'covarSample', pair, frame);
      const variance = w.window('var', 'varianceSample', R(pb), frame);
      const slope = w.ratio(e.fn === 'beta' ? 'beta' : 'slope', one(cov), variance);
      if (e.fn === 'beta' || e.fn === 'hedge_ratio') v = slope;
      else {
        // its intercept, the first's mean less the slope times the second's, over the same window's pairs
        const ma = w.window('mean', 'average', R(pa), frame);
        const mb = w.window('mean', 'average', R(pb), frame);
        const intercept = op('minus', one(ma), op('times', one(slope), one(mb)));
        v = e.fn === 'alpha'
          ? w.row('alpha', op('times', intercept, w.perYear()))
          : w.row('resid', op('minus', one(ra), op('plus', intercept, op('times', one(slope), one(rb)))));
      }
    }
    return { w, v: { column: w.ramped(v, pa, atLeast(size, 2), frame) } };
  }
  if (e.fn === 'ohlc') return candle(sh, e);
  const special = otherCalls(sh, e) ?? analystCalls(sh, e);
  if (special !== undefined) return special;
  if (e.fn === 'interpolate') {
    // x onto y's dates (gs-quant's datetime.interpolate; the Phase 1 review's 5): every date either has merged, then
    // y's alone kept -- the dates both have (intersect), none where x has none (nan), 0 there (zero); step, as gs-quant's
    // walks the dates asked: the first x's value as of it (its first, before it starts), each after its own value, else
    // the one before among them -- an x date between two asked never read
    const x = seriesArg(sh, e, 0);
    const y = seriesArg(sh, e, 1);
    const xs = valueColumns(x.v);
    const al = aligned(sh, [{ w: x.w, values: xs }, { w: y.w, values: [] }], 'nan');
    const [hx, hy] = al.has as [string, string];
    const method = word('method') as 'intersect' | 'nan' | 'zero' | 'step';
    const m = al.w;
    const w = new Writer(m.rel, { part: m.part, split: m.split, at: m.at, seconds: x.w.seconds, rows: x.w.rows, years: x.w.years }, sh);
    // x's value as of each date of either, before y's alone are kept: a step's first
    const asOf = method === 'step' ? xs.map((c) => w.row('ip_asof', col(c))) : [];
    if (method === 'step') fill(w, asOf, al.ords[0]!, 'step');
    const firstY = method === 'step' ? w.window('ip_first', 'min', Writer.read(w.row('ip_yp', when(fn('isNotEmpty', col(hy)), col(PERIOD), none()))), 'all') : undefined;
    // y's dates kept after the windows above read every date: a filter of a plain column folds under its select's windows
    // (the engine's relational rule, Fold.filterSlot), one reading a window is the database's QUALIFY, after them --
    // y's first date, which every line of y has
    const asked = firstY ?? w.window('ip_first', 'min', Writer.read(w.row('ip_yp', when(fn('isNotEmpty', col(hy)), col(PERIOD), none()))), 'all');
    w.rel = w.rel.filter(row(and(fn('isNotEmpty', col(asked)), fn('isNotEmpty', col(hy)), ...(method === 'intersect' ? [fn('isNotEmpty', col(hx))] : []))));
    if (method === 'zero') fill(w, xs, al.ords[0]!, 'zero');
    if (method === 'step') {
      // its own value where it has one; at the first date asked, its value as of it; then each carried to the next
      xs.forEach((c, i) => {
        const raw = w.fresh('ip_raw');
        w.rel = w.rel.rename(c, raw);
        w.rel = w.rel.extend([derive(c, row(when(fn('isNotEmpty', col(raw)), col(raw),
          when(fn('equal', col(PERIOD), col(firstY!)), col(asOf[i]!), none()))))]);
      });
      fill(w, xs, al.ords[0]!, 'step');
    }
    return { w, v: x.v };
  }
  const x = seriesArg(sh, e, 0);
  const w = x.w;
  const a = columnOfValue(x.v);
  const done = (column: string): Node => ({ w, v: { column } });
  switch (e.fn) {
    case 'returns':
      return done(w.returns(a, span('obs'), word('type')));
    case 'prices': {
      const initial = float(n('initial'));
      const type = word('type');
      // initial at the first date, and every other from there: gs-quant's product (and sum) over the returns to date
      // skips an empty one, as pandas' prod does -- a return of none (the first, which has no date before it) leaves the
      // level where it was (the second audit's S5: the first date was none)
      const summed = (c: string): ValueSpecification => {
        const total = w.window('cum', 'plus', R(c), 'running');
        return when(fn('isEmpty', col(total)), float(0), one(total));
      };
      const at = (v: ValueSpecification): string => w.row('px', v);
      if (type === 'absolute') return done(at(op('plus', initial, summed(a))));
      if (type === 'log') return done(at(op('times', initial, fn('exp', summed(a)))));
      // initial times the product of (1 + r), gs-quant's: a factor of 0 (a return of -100%) zero from there on, each
      // factor below 0 turning the sign -- the magnitude the sum of the logs of the others (the review's Q8)
      const g = w.row('g', op('plus', lit.integer(1), one(a)));
      return done(at(op('times', initial, one(w.product(g, 'running')))));
    }
    case 'diff':
      return done(w.row('diff', op('minus', one(a), one(w.lag(a, span('obs'))))));
    case 'lag':
      return done(w.lag(a, span('obs')));
    case 'annualize':
      return done(w.row('ann', op('times', one(a), w.annual())));
    case 'index':
    case 'rebase': {
      const base = w.anchored(a);
      const scale = float(n(e.fn === 'index' ? 'initial' : 'base'));
      return done(w.row('idx', when(fn('equal', one(base), lit.integer(0)), none(), op('times', fn('divide', one(a), one(base)), scale))));
    }
    case 'change':
      return done(w.row('chg', op('minus', one(a), one(w.anchored(a)))));
    case 'percent_change': {
      const base = w.anchored(a);
      return done(w.row('pchg', when(fn('equal', one(base), lit.integer(0)), none(), op('minus', fn('divide', one(a), one(base)), lit.integer(1)))));
    }
    case 'mean':
    case 'sum':
    case 'min':
    case 'max': {
      const size = span('w');
      const reduce = e.fn === 'mean' ? 'average' : e.fn === 'sum' ? 'plus' : e.fn;
      return done(w.ramped(w.window(e.fn, reduce, R(a), Writer.frame(size)), a, size, Writer.frame(size)));
    }
    case 'range': {
      const size = span('w');
      const hi = w.window('max', 'max', R(a), Writer.frame(size));
      const lo = w.window('min', 'min', R(a), Writer.frame(size));
      return done(w.ramped(w.row('rng', op('minus', one(hi), one(lo))), a, size, Writer.frame(size)));
    }
    case 'std':
      return done(w.std(a, span('w')));
    case 'var': {
      const size = span('w');
      return done(w.ramped(w.window('var', 'varianceSample', R(a), Writer.frame(size)), a, atLeast(size, 2), Writer.frame(size)));
    }
    case 'zscores': {
      const size = span('w');
      const frame = size === 0 ? 'all' : size;
      const mean = w.window('mean', 'average', R(a), frame);
      const sd = w.window('std', 'stdDevSample', R(a), frame);
      const z = w.ratio('z', op('minus', one(a), one(mean)), sd);
      return done(size === 0 ? z : w.ramped(z, a, size));
    }
    case 'winsorize': {
      const limit = float(n('limit'));
      const mean = w.window('mean', 'average', R(a), 'all');
      const sd = w.window('std', 'stdDevSample', R(a), 'all');
      const hi = w.row('hi', op('plus', one(mean), op('times', limit, one(sd))));
      const lo = w.row('lo', op('minus', one(mean), op('times', limit, one(sd))));
      return done(w.row('wins', when(fn('greaterThan', one(a), one(hi)), col(hi), when(fn('lessThan', one(a), one(lo)), col(lo), col(a)))));
    }
    case 'volatility': {
      const r = w.returns(a, 1, word('type'));
      // over a calendar period, from the first price's date that period on: gs-quant's returns keep the first price's
      // date (its return none), which its ramp counts from (the E9 review's nit: from the first return's, a date late)
      const sd = w.std(r, span('w'), a);
      return done(w.row('vol', op('times', op('times', one(sd), w.annual()), lit.integer(100))));
    }
    case 'drawdown': {
      const size = span('w');
      const peak = w.window('peak', 'max', R(a), Writer.frame(size));
      const dd = w.row('dd', when(fn('equal', one(peak), lit.integer(0)), none(), op('minus', fn('divide', one(a), one(peak)), lit.integer(1))));
      return done(size === 0 ? dd : w.ramped(dd, a, size));
    }
    case 'max_drawdown': {
      const size = span('w');
      const peak = w.window('peak', 'max', R(a), Writer.frame(size));
      const dd = w.row('dd', when(fn('equal', one(peak), lit.integer(0)), none(), op('minus', fn('divide', one(a), one(peak)), lit.integer(1))));
      return done(w.ramped(w.window('mdd', 'min', R(dd), Writer.frame(size)), a, size));
    }
    case 'sharpe_ratio': {
      const size = span('w');
      const r = w.simple(a);
      const mean = w.window('mean', 'average', R(r), Writer.frame(size));
      const sd = w.std(r, size, a);
      return done(w.ramped(w.row('sharpe', when(fn('equal', one(sd), lit.integer(0)), none(),
        op('times', fn('divide', one(mean), one(sd)), w.annual()))), typeof size === 'number' ? r : a, size, Writer.frame(size)));
    }
    case 'bollinger_bands': {
      const size = span('w');
      const k = float(n('k'));
      const mean = w.ramped(w.window('mean', 'average', R(a), Writer.frame(size)), a, size, Writer.frame(size));
      const sd = w.std(a, size);
      const lower = w.row('lower', op('minus', one(mean), op('times', k, one(sd))));
      const upper = w.row('upper', op('plus', one(mean), op('times', k, one(sd))));
      return { w, v: { lower, upper, width: w.row('width', op('minus', one(upper), one(lower))) } };
    }
    case 'rsi': {
      // gs-quant's (technicals.relative_strength_index): each change's gain and loss, each Wilder's smoothed average over
      // w of them (its first the mean of the w changes after the first, as gs-quant's ramp has it)
      const size = n('w');
      const d = w.row('d', op('minus', one(a), one(w.lag(a, 1))));
      const gain = w.row('gain', when(fn('isEmpty', col(d)), none(), when(fn('greaterThan', one(d), lit.integer(0)), one(d), float(0))));
      const loss = w.row('loss', when(fn('isEmpty', col(d)), none(), when(fn('lessThan', one(d), lit.integer(0)), op('minus', float(0), one(d)), float(0))));
      const up = w.smoothed(gain, size);
      const down = w.smoothed(loss, size);
      return done(w.row('rsi', when(fn('isEmpty', col(up)), none(), when(fn('equal', one(down), lit.integer(0)), float(100),
        op('minus', float(100), fn('divide', float(100), op('plus', lit.integer(1), fn('divide', one(up), one(down)))))))));
    }
    case 'smoothed_moving_average':
      return done(w.smoothed(a, n('w')));
    case 'dickey_fuller':
    case 'half_life': {
      // Δx = a + b·x[-1] over the window, least squares (the next phases' 4): b their covariance over x[-1]'s variance --
      // its t-statistic Dickey and Fuller's (statsmodels' adfuller of no lags, a constant), its half-life −ln 2 / ln(1 + b)
      const size = span('w');
      // w differences (w + 1 values): a fit of a constant and a slope over under 3 has no deviation left to judge it by
      if (typeof size === 'number' && size > 0 && size < 3) throw new CubeRefusal(`${e.fn} over ${size} differences has no deviation left: w is 0 (every one to date) or at least 3`);
      const frame = Writer.frame(size);
      const xl = w.lag(a, 1);
      const dx = w.row('df_dx', op('minus', one(a), one(xl)));
      const pl = w.row('df_l', when(fn('isEmpty', col(dx)), none(), col(xl)));
      const pd = w.row('df_d', when(fn('isEmpty', col(xl)), none(), col(dx)));
      const vl = w.window('df_vl', 'varianceSample', R(pl), frame);
      const b = w.ratio('df_b', one(w.window('df_c', 'covarSample', fn('rowMapper', R(pd), R(pl)), frame)), vl);
      if (e.fn === 'half_life') {
        // none where it does not revert (b at or above 0), or overshoots past its mean (b at or below -1)
        const hl = w.row('hl', when(or(fn('isEmpty', col(b)), fn('greaterThanEqual', one(b), lit.integer(0)), fn('lessThanEqual', one(b), lit.integer(-1))), none(),
          fn('divide', op('minus', float(0), fn('log', float(2))), fn('log', op('plus', lit.integer(1), one(b))))));
        return done(w.ramped(hl, pl, atLeast(size, 3), frame));
      }
      // b's standard error: √((var Δx − b² var x[-1]) / ((n − 2) var x[-1])), none of a perfect fit
      const vd = w.window('df_vd', 'varianceSample', R(pd), frame);
      const count = w.window('df_n', 'count', R(pl), frame);
      const resid = w.row('df_r', op('minus', one(vd), op('times', op('times', one(b), one(b)), one(vl))));
      const se = w.row('df_se', when(or(fn('isEmpty', col(b)), fn('lessThan', one(count), lit.integer(3)), fn('lessThanEqual', one(resid), lit.integer(0))), none(),
        fn('sqrt', fn('divide', one(resid), op('times', op('minus', one(count), lit.integer(2)), one(vl))))));
      return done(w.ramped(w.ratio('df_t', one(b), se), pl, atLeast(size, 3), frame));
    }
    case 'percentiles': {
      // scipy's percentileofscore(window, value, kind='mean'), gs-quant's: the share of the window below the value, those
      // equal to it counted half, in percent
      const size = span('w');
      const y = (f: string): Lambda => lambda(['y'], fn(f, variable('y')));
      const [below, upTo, count] = w.ofWindow(a, Writer.frame(size), (own, other) => [
        { what: 'pr_lt', read: when(fn('lessThan', other, own), lit.integer(1), none()), reduce: y('count') },
        { what: 'pr_le', read: when(fn('lessThanEqual', other, own), lit.integer(1), none()), reduce: y('count') },
        { what: 'pr_n', read: other, reduce: y('count') },
      ]);
      const rank = w.row('pr', when(or(fn('isEmpty', col(a)), fn('equal', one(count!), lit.integer(0))), none(),
        op('times', fn('divide', op('plus', one(below!), one(upTo!)), op('times', lit.integer(2), one(count!))), lit.integer(100))));
      return done(size === 0 ? rank : w.ramped(rank, a, size, Writer.frame(size)));
    }
    case 'median':
    case 'percentile': {
      // pandas' quantile (the database's continuous percentile, between the two nearest): a median its 50th
      const size = span('w');
      // the planner takes the fraction as a literal alone (an aggregate's argument): gs-quant's n of 100 spelled as one
      const q = float(e.fn === 'median' ? 0.5 : n('n') / 100);
      const reduce = lambda(['y'], fn('percentile', variable('y'), q, lit.boolean(true), lit.boolean(true)));
      // of every value (percentile's w 0): one aggregate a line, never each row joined to every other (the E9 review's 5:
      // 4,000 rows took 4.5 s)
      if (e.fn === 'percentile' && size === 0) return done(w.ofLine(a, 'pct', reduce));
      const [v] = w.ofWindow(a, Writer.frame(size), (_own, other) => [{ what: 'pct', read: other, reduce }]);
      return done(size === 0 ? v! : w.ramped(v!, a, size, Writer.frame(size)));
    }
    case 'exponential_moving_average':
      return done(n('beta') === 0 ? a : w.ewm(a, float(n('beta'))));
    case 'exponential_std':
      return done(w.ewmStd(a, float(n('beta'))));
    case 'exponential_volatility': {
      // gs-quant's: annualize(exponential_std(returns(x), beta)) * 100
      const sd = w.ewmStd(w.returns(a, 1, 'simple'), float(n('beta')));
      return done(w.row('evol', op('times', op('times', one(sd), w.annual()), lit.integer(100))));
    }
    case 'exponential_spread_volatility': {
      // gs-quant's: annualize(exponential_std(diff(x, 1), beta)), in its own units
      const sd = w.ewmStd(w.row('d', op('minus', one(a), one(w.lag(a, 1)))), float(n('beta')));
      return done(w.row('esvol', op('times', one(sd), w.annual())));
    }
    case 'macd': {
      // gs-quant's: ewm(span=m) - ewm(span=n), itself ewm(span=s) -- a span k a weight 2 / (k + 1) on each new value,
      // the one before (k - 1) / (k + 1); a span of 1 none (each value as it is)
      const span = (k: number, x_: string): string => (k === 1 ? x_ : w.ewm(x_, fn('divide', lit.integer(k - 1), lit.integer(k + 1))));
      const diff = w.row('macd', op('minus', one(span(n('m'), a)), one(span(n('n'), a))));
      return done(span(n('s'), diff));
    }
    case 'log':
      return done(w.row('ln', when(fn('lessThanEqual', one(a), lit.integer(0)), none(), fn('log', one(a)))));
    case 'exp':
      return done(w.row('exp', fn('exp', one(a))));
    case 'sqrt':
      return done(w.row('sqrt', when(fn('lessThan', one(a), lit.integer(0)), none(), fn('sqrt', one(a)))));
    case 'abs':
      return done(w.row('abs', fn('abs', one(a))));
    case 'power':
      return done(w.row('pow', fn('pow', one(a), float(n('y')))));
    case 'floor': {
      const v = float(n('value'));
      return done(w.row('floor', when(fn('lessThan', one(a), v), v, one(a))));
    }
    case 'ceil': {
      const v = float(n('value'));
      return done(w.row('ceil', when(fn('greaterThan', one(a), v), v, one(a))));
    }
    // gs-quant's remaining functions of one series (the next phases' 1, 2026-10-26)
    case 'not':
      return done(w.row('not', when(fn('isEmpty', col(a)), none(), when(fn('equal', one(a), lit.integer(0)), lit.integer(1), lit.integer(0)))));
    case 'filter': {
      // gs-quant's filter_: the values the operator matches removed (empty: the dates of none) -- the rows dropped,
      // after every figure before it is computed over them all (a filter of a window's figure is the database's
      // QUALIFY), so a figure after it reads the dates left
      const v = float(n('value'));
      const matches: Readonly<Record<string, (x: ValueSpecification) => ValueSpecification>> = {
        less_than: (x) => fn('lessThan', x, v), greater_than: (x) => fn('greaterThan', x, v), l_equals: (x) => fn('lessThanEqual', x, v),
        g_equals: (x) => fn('greaterThanEqual', x, v), equals: (x) => fn('equal', x, v), not_equals: (x) => fn('not', fn('equal', x, v)),
      };
      const how = word('operator');
      // a comparison with none never holds, so none is kept -- but by not_equals, where it does (NaN != v): the values
      // equal to it alone kept (the Phase 1 review's 6)
      const kept = how === 'empty' ? fn('isNotEmpty', col(a))
        : how === 'not_equals' ? fn('equal', one(a), v)
          : or(fn('isEmpty', col(a)), fn('not', matches[how]!(one(a))));
      w.rel = w.rel.filter(row(kept));
      return done(a);
    }
    case 'product': {
      const size = span('w');
      const frame = Writer.frame(size);
      const p = w.product(a, frame);
      return done(w.row('prod_v', when(fn('isEmpty', col(a)), none(), col(size === 0 ? p : w.ramped(p, a, size, frame)))));
    }
    case 'geometrically_aggregate': {
      const g = w.row('ga', op('plus', lit.integer(1), one(a)));
      return done(w.row('geo', when(fn('isEmpty', col(a)), none(), op('minus', one(w.product(g, 'running')), lit.integer(1)))));
    }
    case 'smooth_spikes': {
      // gs-quant's: a value beyond both neighbours (by 1 + threshold times, or threshold beyond) their mean -- beside none
      // no spike (a comparison with NaN never holds: kept, the Phase 1 review's 6); its first and last rows dropped (none)
      const t = n('threshold');
      const prev = w.lag(a, 1);
      const next = w.lead(a, 1);
      const [x, p, q] = [one(a), one(prev), one(next)];
      const by = word('type') === 'absolute' ? float(t) : op('plus', lit.integer(1), float(t));
      const beyond = word('type') === 'absolute'
        ? (big: ValueSpecification, small: ValueSpecification): ValueSpecification => fn('greaterThan', big, op('plus', small, by))
        : (big: ValueSpecification, small: ValueSpecification): ValueSpecification => fn('greaterThan', big, op('times', small, by));
      const spike = and(fn('isNotEmpty', col(prev)), fn('isNotEmpty', col(next)), fn('isNotEmpty', col(a)),
        or(and(beyond(x, p), beyond(x, q)), and(beyond(p, x), beyond(q, x))));
      const rn = w.rowNumber();
      const rows = w.window('sm_n', 'count', R(rn), 'all');
      return done(w.row('smooth', when(or(fn('equal', one(rn), lit.integer(1)), fn('equal', one(rn), one(rows))), none(),
        when(spike, fn('divide', op('plus', p, q), lit.integer(2)), x))));
    }
    case 'vol_swap_volatility': {
      // gs-quant's: the root mean square of log returns (a mean of zero) over the window, times the root of 252, in percent
      const size = span('w');
      const frame = Writer.frame(size);
      const r = w.returns(a, 1, 'log');
      const ms = w.window('ms', 'average', R(w.row('r2', op('times', one(r), one(r)))), frame);
      const vol = w.row('vs', op('times', op('times', fn('sqrt', one(ms)), fn('sqrt', lit.integer(252))), lit.integer(100)));
      return done(size === 0 ? vol : w.ramped(vol, a, size, frame));
    }
    case 'consecutive': {
      // a run of values above 0 (below), counted to each date; 0 where it breaks -- each break begins a group
      const holds = word('direction') === 'negative' ? fn('lessThan', one(a), lit.integer(0)) : fn('greaterThan', one(a), lit.integer(0));
      const yes = w.row('cs_yes', when(and(fn('isNotEmpty', col(a)), holds), lit.integer(1), lit.integer(0)));
      const group = w.window('cs_grp', 'plus', R(w.row('cs_no', op('minus', lit.integer(1), one(yes)))), 'running');
      return done(w.window('cs', 'plus', R(yes), 'running', group));
    }
    case 'trend':
    case 'seasonally_adjusted':
      return done(decomposed(sh, w, a, e.fn, word('method'), word('freq')));
    case 'first':
    case 'last':
      // on every date it has, those of none too (gs-quant's fills its index: the Phase 1 review's 6)
      return done(w.edgeValue(a, e.fn));
    case 'count':
      return done(w.window('count', 'count', R(a), 'running'));
    case 'day':
    case 'month':
    case 'year':
    case 'quarter':
    case 'weekday': {
      const part: Readonly<Record<string, ValueSpecification>> = {
        day: fn('dayOfMonth', one(w.at)), month: fn('monthNumber', one(w.at)), year: fn('year', one(w.at)), quarter: fn('quarterNumber', one(w.at)),
        // pandas' 0 for a Monday: the database's day of a week begun on Monday, less one
        weekday: op('minus', fn('dayOfWeekNumber', one(w.at), enumValue('meta::pure::functions::date::DayOfWeek', 'Monday')), lit.integer(1)),
      };
      // of every date it has, those of none too (gs-quant's: of its index)
      return done(w.row(e.fn, part[e.fn]!));
    }
    case 'if':
    case 'compare':
    case 'union':
    case 'append':
    case 'prepend':
    case 'excess_returns':
    case 'weighted_sum':
      throw new Error(`${e.fn} is written by otherCalls`);
    case 'atr':
    case 'stochastic':
    case 'keltner_channels':
    case 'donchian_channels':
    case 'obv':
    case 'vwap':
    case 'tracking_error':
    case 'information_ratio':
    case 'up_capture':
    case 'down_capture':
    case 'total_return':
    case 'basket':
    case 'lead_lag':
      throw new Error(`${e.fn} is written by analystCalls`);
  }
}

/**
 * A CLASSICAL DECOMPOSITION (gs-quant's technicals.trend and seasonally_adjusted, statsmodels' seasonal_decompose):
 * over a cycle of p observations of the tile's frequency, its trend a moving average centred over p (of p + 1, the ends
 * halved, for an even p), none where the window is not whole; each place in the cycle's (its row's number, mod p) mean of
 * the detrended values (x less the trend, or over it), centred (less their mean, or over it): the season. Adjusted, x
 * less its season (over it), where the trend is -- every step the database's.
 */
function decomposed(sh: Shared, w: Writer, a: string, what: 'trend' | 'seasonally_adjusted', method: string, freq: string): string {
  const grain = sh.grain;
  const p = grain === undefined ? undefined : CYCLES[grain]?.[freq];
  if (grain === undefined) throw new CubeRefusal(`${what} counts its cycle in the tile's periods: give the tile a frequency (Monthly)`);
  if (p === undefined) throw new CubeRefusal(`${what}: a ${freq} is no cycle of ${grain === 'day' ? 'days' : `${grain}s`} -- choose a longer one, or another frequency`);
  const R = Writer.read;
  const k = Math.floor(p / 2);
  const odd = p % 2 === 1;
  const n = w.around('dc_n', 'count', R(a), k);
  const sum = w.around('dc_s', 'plus', R(a), k);
  // two whole cycles or none: under them gs-quant refuses (statsmodels cannot centre a season; the Phase 1 review's 4)
  const rn = w.rowNumber();
  const rows = w.window('dc_rows', 'count', R(rn), 'all');
  const centred = odd ? fn('divide', one(sum), lit.integer(p))
    : fn('divide', op('minus', one(sum), op('times', float(0.5), op('plus', one(w.lag(a, k)), one(w.lead(a, k))))), lit.integer(p));
  const trend = w.row('dc_trend', when(or(fn('lessThan', one(rows), lit.integer(2 * p)), fn('lessThan', one(n), lit.integer(odd ? p : p + 1))), none(), centred));
  if (what === 'trend') return trend;
  const multiplicative = method === 'multiplicative';
  const detrended = w.row('dc_d', multiplicative ? when(fn('equal', one(trend), lit.integer(0)), none(), fn('divide', one(a), one(trend)))
    : op('minus', one(a), one(trend)));
  // its place in the cycle: the calendar's at a frequency of months, quarters or weeks -- a missing one never moving the
  // places after it, as gs-quant's calendar filled first keeps them (the Phase 1 review's 4) -- its row's at days, a
  // cycle of business days
  const index = grain === 'month' ? op('plus', op('times', fn('year', one(PERIOD)), lit.integer(12)), op('minus', fn('monthNumber', one(PERIOD)), lit.integer(1)))
    : grain === 'quarter' ? op('plus', op('times', fn('year', one(PERIOD)), lit.integer(4)), op('minus', fn('quarterNumber', one(PERIOD)), lit.integer(1)))
      : grain === 'week' ? fn('floor', fn('divide', fn('dateDiff', literalNode('1970-01-05', 'StrictDate'), one(PERIOD), enumValue(DURATION, 'DAYS')), lit.integer(7)))
        : op('minus', one(rn), lit.integer(1));
  const place = w.row('dc_i', index);
  const at = w.row('dc_pos', op('minus', one(place), op('times', fn('floor', fn('divide', one(place), lit.integer(p))), lit.integer(p))));
  const placeMean = w.window('dc_pm', 'average', R(detrended), 'all', at);
  const placeRows = w.window('dc_pr', 'count', R(rn), 'all', at);
  // the places' mean: each row's share of its place's mean, summed over the line, over p
  const overall = w.window('dc_all', 'plus', R(w.row('dc_share', fn('divide', one(placeMean), one(placeRows)))), 'all');
  const mean = fn('divide', one(overall), lit.integer(p));
  const season = w.row('dc_season', multiplicative ? fn('divide', one(placeMean), mean) : op('minus', one(placeMean), mean));
  return w.row('dc_sa', when(fn('isEmpty', col(trend)), none(),
    multiplicative ? fn('divide', one(a), one(season)) : op('minus', one(a), one(season))));
}

/** A base's aggregate over a period's rows (`ord`: the row's number in time, which `last` and `first` order by). */
function baseAggregate(name: string, b: Base, ord: string): ColSpec {
  const y = variable('y');
  switch (b.fn) {
    case 'last':
    case 'first':
      return agg(name, row(fn('rowMapper', col(b.column), col(ord))), lambda(['y'], fn(b.fn === 'last' ? 'maxBy' : 'minBy', y)));
    case 'average':
    case 'sum':
    case 'min':
    case 'max':
      return agg(name, row(col(b.column)), lambda(['y'], fn(b.fn, y)));
  }
}

/**
 * A source's date taken to the tile's frequency: its period (the day, the week's first day...), the type it has, and the
 * date a row's value is of (`at`: its own day, a date-time's at a frequency) -- each period's latest, the date its value
 * is of. A date of no type the compiler gave is refused (the review's G2: guessed a StrictDate).
 */
function periodOf(grain: TimeGrain | undefined, date: string, dateType: string | undefined): {
  readonly body: ValueSpecification; readonly type: string; readonly at: ValueSpecification;
} {
  if (dateType === undefined) throw new CubeRefusal(`${date} is no column of its grid`);
  if (!isTemporal(dateType) || isTimeOfDay(dateType)) throw new CubeRefusal(`${date} is a ${dateType}, not a date: a timeseries is read along a date`);
  if (grain === undefined || (grain === 'day' && isDay(dateType))) return { body: col(date), type: dateType, at: col(date) };
  const day = isDay(dateType) ? col(date) : fn('datePart', one(date));
  if (grain === 'day') return { body: day, type: 'StrictDate', at: day };
  return { body: fn(GRAIN_FUNCTION[grain], one(date)), type: 'StrictDate', at: day };
}

/** A source of the tile by its alias, and its input, or a refusal naming it. */
function sourceNamed(ts: TimeseriesSpec, inputs: TsInputs, alias: string): { readonly src: TsSource; readonly input: TsInput } {
  const src = ts.sources.find((x) => x.alias === alias);
  if (!src) throw new CubeRefusal(`there is no source ${alias}`);
  const input = inputs.get(alias);
  if (!input) throw new CubeRefusal(`source ${alias}'s grid is not open`);
  return { src, input };
}

/** The type of a source's period at the tile's frequency: what a date the viewer passes back is written as. */
export function periodType(ts: TimeseriesSpec, input: TsInput, src: TsSource): string {
  return periodOf(ts.grain, src.date, typeIn(input.snapshot, src.date)).type;
}

/**
 * A SERIES' RELATION: its expression written part by part (steps 1 to 5, the file's header) -- each column over its own
 * source's rows and dates, each function over its operand's part, the parts joined on the period where an operator or a
 * function reads them together -- and a split's first MAX_SPLIT_LINES lines. `anchor`: the first date shown, where an
 * index starts.
 */
function seriesRelation(inputs: TsInputs, ts: TimeseriesSpec, series: TsSeries, anchor: string | undefined, names: { n: number } = { n: 0 }): Built {
  const expr = candled(inlined(series.expr, ts.series));
  const read = sourcesOf(ts, expr);
  if (read.length === 0) throw new CubeRefusal(`series ${series.id} reads no column of a grid`);
  // `names`: a query of two series' relations names their columns apart (a scatter's)
  const sh: Shared = { ts, grain: ts.grain, series, inputs, split: series.split, first: read[0]!, anchor, periodType: undefined, names };
  const top = write(sh, expr);
  if ('constant' in top) throw new CubeRefusal(`series ${series.id} reads no column of a grid`);
  const w = top.w;
  const split = series.split;
  if (split !== undefined && !w.split) {
    throw new CubeRefusal(`series ${series.id} is split by ${split}, but reads one ${split}'s rows alone: a line per ${split} needs a column of every one`);
  }
  if (split !== undefined) {
    const rank = `${WINDOW_ALL_PREFIX}ts_line`;
    w.rel = w.rel.extend([derive(rank, lambda(['p', 'w', 'r'], fn('denseRank', variable('p'), variable('w'), variable('r'))))],
      fn('over', collection([asc(split)])));
    w.rel = w.rel.filter(row(fn('lessThanEqual', col(rank), lit.integer(MAX_SPLIT_LINES))));
  }
  let unit = unitOf(expr);
  const level = levelOf(expr);
  let built: { value: string; levelValue?: string; lower?: string; upper?: string; width?: string; open?: string; high?: string; low?: string };
  const v = top.v;
  if ('lower' in v) {
    built = { value: v.upper, lower: v.lower, upper: v.upper, width: v.width };
  } else if ('open' in v) {
    // a candle drawn as the rows are: compare moves no candle (its four prices would need one base each)
    built = { value: v.close, open: v.open, high: v.high, low: v.low };
  } else {
    let value = v.column;
    const own = value;
    // compare: every LEVEL from one start -- a return, a difference, a deviation drawn as it is (the review's Q2) --
    // its change over its start's size, as the statistics' % change is: a start below zero rising is a rise (the
    // second audit's S1: a yield of -1 rising to -0.5 was a fall of 50%); of a start above zero, x / x0 as gs-quant's
    const how = ts.view?.compare;
    if (how !== undefined && level) {
      const base = w.anchored(value);
      const change = w.row('cmp_chg', when(fn('equal', one(base), lit.integer(0)), none(),
        fn('divide', op('minus', one(value), one(base)), fn('abs', one(base)))));
      value = how === 'rebase' ? w.row('cmp', op('times', op('plus', lit.integer(1), one(change)), lit.integer(100))) : change;
      unit = how === 'rebase' ? 'index' : 'ratio';
    }
    built = { value, ...(value !== own ? { levelValue: own } : {}) };
  }
  // a CONDITION's runs (gaps and islands, the database's): each change of it begins a run, numbered by a running count
  // of them; each date where it holds carries its run's first date and where its shading ends -- the line's next date
  // after it, from which it no longer holds (its own last at the line's end): what the drawing shades, a run of one date
  // a period wide (the E9 review's 4: to its last date, a run of one had no width and every run was a period short)
  let shade: { readonly shadeFrom: string; readonly shadeTo: string } | undefined;
  if (isCondition(expr)) {
    const flag = built.value;
    const prev = w.lag(flag, 1);
    const change = w.row('sh_chg', when(or(fn('isEmpty', col(prev)), fn('not', fn('equal', one(flag), one(prev)))), lit.integer(1), lit.integer(0)));
    const runs = w.window('sh_run', 'plus', Writer.read(change), 'running');
    const first = w.window('sh_first', 'min', Writer.read(w.at), 'all', runs);
    const after = w.lead(w.at);
    const end = w.row('sh_end', when(fn('isEmpty', col(after)), col(w.at), col(after)));
    const last = w.window('sh_last', 'max', Writer.read(end), 'all', runs);
    const holds = fn('equal', one(flag), lit.integer(1));
    shade = { shadeFrom: w.row('sh_from', when(holds, col(first), none())), shadeTo: w.row('sh_to', when(holds, col(last), none())) };
  }
  // the most rows any of its columns took one of, at a period: more than one, and the drawing says so
  const rows = w.rows.length === 0 ? undefined : w.rows.length === 1 ? w.rows[0]!
    // the most of them by the database's max of two (GREATEST): `when(c > most, c, most)` wrote `most` twice, so the
    // query doubled with every part read (eight parts: each first part's count 130 times -- the Phase 1 review's 1)
    : w.row('rows', w.rows.slice(1).reduce<ValueSpecification>((most, c) => fn('max', one(c), most), one(w.rows[0]!)));
  // the periods a year it was annualized by: of a combination, none when any part's is none (the second audit's S4: a
  // part of no frequency, every value none, and the first part's 252 said nothing was wrong)
  const perYear = w.years.length <= 1 ? w.years[0]
    : w.row('per_year_all', when(or(...w.years.map((c) => fn('isEmpty', col(c)))), none(), one(w.years[0]!)));
  return {
    rel: w.rel, period: PERIOD, ord: ORD, at: w.at, periodType: sh.periodType!, ...built, ...(shade ?? {}), ...(split !== undefined ? { split } : {}),
    unit, level, ...(rows !== undefined ? { rows } : {}), ...(perYear !== undefined ? { perYear } : {}), sources: read, writer: w,
  };
}

/** The sources a series reads, by alias, in the order it reads them: one, run where its grid runs; more, a combination. */
export function seriesSources(ts: TimeseriesSpec, series: TsSeries): string[] {
  return sourcesOf(ts, inlined(series.expr, ts.series));
}

/**
 * A SERIES' QUERY: its periods (and split values), latest first, each with its value -- or a band's lower and upper
 * -- at most TS_POINT_LIMIT of them. `view.from`: the first date shown, an index's base (readsAnchor). `clip`: the dates
 * its answer keeps (a host reading a window: Python's Timeseries.frame) -- kept by the database after every value is
 * computed over every date, so a window's first values have their history (a 63-day volatility its 63 days); absent,
 * every date (the viewer's, which zooms over them).
 */
export function seriesQuery(inputs: TsInputs, ts: TimeseriesSpec, series: TsSeries, view: TsWindow = {}, clip: TsWindow = {},
  limit: number | null = TS_POINT_LIMIT + 1): TsQuery {
  const b = seriesRelation(inputs, ts, series, view.from);
  const out: ColSpec[] = [
    derive(TS_DATE, row(col(b.at))),
    derive(TS_VALUE, row(col(b.value))),
    ...(b.lower !== undefined ? [derive(TS_LOWER, row(col(b.lower)))] : []),
    ...(b.upper !== undefined ? [derive(TS_UPPER, row(col(b.upper)))] : []),
    ...(b.width !== undefined ? [derive(TS_WIDTH, row(col(b.width)))] : []),
    ...(b.open !== undefined ? [derive(TS_OPEN, row(col(b.open))), derive(TS_HIGH, row(col(b.high!))), derive(TS_LOW, row(col(b.low!)))] : []),
    ...(b.shadeFrom !== undefined ? [derive(TS_FROM, row(col(b.shadeFrom))), derive(TS_TO, row(col(b.shadeTo!)))] : []),
    ...(b.split !== undefined ? [derive(TS_SPLIT, row(col(b.split)))] : []),
    ...(b.rows !== undefined ? [derive(TS_ROWS, row(col(b.rows)))] : []),
    ...(b.perYear !== undefined ? [derive(TS_PER_YEAR, row(col(b.perYear)))] : []),
  ];
  let rel = b.rel.extend(out);
  const within: ValueSpecification[] = [];
  if (clip.from !== undefined) within.push(fn('greaterThanEqual', one(b.at), dateLiteral(clip.from, b.periodType)));
  if (clip.to !== undefined) within.push(fn('lessThanEqual', one(b.at), dateLiteral(`${clip.to}${isDay(b.periodType) ? '' : 'T23:59:59'}`, b.periodType)));
  if (within.length > 0) rel = rel.filter(row(and(...within)));
  rel = rel.select(out.map((c) => c.name)).sort([desc(TS_DATE)]);
  if (limit !== null) rel = rel.limit(limit);
  return {
    series: series.id,
    query: rel.lambda(),
    unit: b.unit,
    band: b.lower !== undefined,
    ...(b.open !== undefined ? { candles: true } : {}),
    ...(b.shadeFrom !== undefined ? { shade: true } : {}),
    ...(b.split !== undefined ? { split: b.split } : {}),
    level: b.level,
    counted: b.rows !== undefined,
    sources: b.sources,
  };
}

/**
 * THE DATES A SOURCE SPANS, and where each range button starts: the latest and the earliest date of its grid's rows (its
 * filter as the tile reads it), and each period back from the latest -- `adjust(latest, -1, YEARS)`, the year's first
 * day for YTD -- the DATABASE's date arithmetic, which the viewer only places its zoom at (the source with the latest
 * date of all, found by comparing).
 */
export function boundsQuery(input: TsInput, src: TsSource): Lambda {
  const s = input.snapshot;
  const typeOf = (c: string): string | undefined => typeIn(s, c);
  const all = `${WINDOW_ALL_PREFIX}ts_bounds`;
  let rel = sourceWithDerived(s);
  const conditions: ValueSpecification[] = [fn('isNotEmpty', col(src.date))];
  const filter = filterRead(src, input.filter);
  if (filter) conditions.push(filterNode(filter, 'x', typeOf));
  rel = rel.filter(row(and(...conditions)))
    .extend([derive(all, row(lit.string('all')))])
    .groupBy([all], [
      agg(TS_LATEST, row(col(src.date)), lambda(['y'], fn('max', variable('y')))),
      agg(TS_EARLIEST, row(col(src.date)), lambda(['y'], fn('min', variable('y')))),
    ]);
  const starts: ColSpec[] = TS_RANGES.filter((r) => r.word !== 'max').map((r) => {
    const step = RANGE_STEP[r.word];
    const body = step === undefined
      ? fn('firstDayOfYear', one(TS_LATEST))
      : fn('adjust', one(TS_LATEST), lit.integer(-step[0]), enumValue(DURATION, step[1]));
    return derive(rangeStartName(r.word), row(body));
  });
  return rel.extend(starts).select([TS_LATEST, TS_EARLIEST, ...starts.map((c) => c.name)]).lambda();
}

/**
 * WHY A LEVEL'S FIGURES ARE BLANK (the second audit's P-2b and S1: a "—" said nothing), from its statistics' own answer --
 * its least over the dates shown (`low`, before compare took it from its start), its periods a year (`perYear`) and its
 * growth (`cagr`): at or below zero, none of its returns' figures; no frequency, no volatility or Sharpe ratio; under a
 * year, its growth not annualized. Each read off the database's answer, compared, never computed; undefined where its
 * answer has no such column. The one rule the viewer's statistics panel and Python's statistics say it by (the E9
 * review's 10: Python's had said nothing).
 */
export function statBlanks(name: string, f: { readonly low: number | null | undefined; readonly perYear: number | null | undefined; readonly cagr: number | null | undefined }): string[] {
  if (f.low !== undefined && f.low !== null && f.low <= 0) {
    return [`${name} is at or below zero over these dates: it has no volatility, drawdown, growth or Sharpe ratio, nor any figure of its returns' risk.`];
  }
  const out: string[] = [];
  if (f.perYear === null) out.push(`${name}: its dates are no frequency to annualize its volatility and Sharpe ratio by: choose the tile's frequency.`);
  // the database's growth blank above zero: these dates span under a year (the review's N3)
  if (f.low !== undefined && f.low !== null && f.cagr === null) {
    out.push(`${name}: these dates span under a year, so its growth is not annualized: its % change is its growth.`);
  }
  return out;
}

/**
 * THE DATES A TILE'S RANGE SHOWS (its window, a zoom aside): a range of the last of a period from the source with the
 * latest date of all -- found by comparing the dates the database gave in each source's bounds (boundsQuery), its start
 * for the range the database's -- or its own dates; every one for Max, no range, or a tile of no source of dates. The one
 * rule the viewer's window and Python's tables read (the E9 review's 7: Python's had read every date of a 1Y tile).
 */
export function rangeWindow(range: TsRange | undefined, bounds: Iterable<ResultTable>): TsWindow {
  if (!range) return {};
  if ('last' in range) {
    if (range.last === 'max') return {};
    // the bounds query writes both columns: one missing is a bug, never the whole range shown in its place (the second
    // audit's G notes); a source of no dates has no latest
    const value = (t: ResultTable, name: string): unknown => {
      const c = t.columns.find((x) => x.name === name);
      if (!c) throw new Error(`the tile's bounds answered with no ${name}`);
      return c.values[0] ?? null;
    };
    let best: ResultTable | undefined;
    let latest = '';
    for (const t of bounds) {
      const v = value(t, TS_LATEST);
      if (v === null) continue;
      const at = String(v);
      if (at > latest) {
        latest = at;
        best = t;
      }
    }
    if (!best) return {};
    const from = value(best, rangeStartName(range.last));
    return from === null ? {} : { from: String(from).slice(0, 10) };
  }
  return { ...(range.from !== undefined ? { from: range.from } : {}), ...(range.to !== undefined ? { to: range.to } : {}) };
}

/** A series' relation cut to the dates shown (`view`: from, to; either open): each period's value's date, its last
 *  observation (the review's Q6). */
function onlyShown(b: Built, view: TsWindow): void {
  const shown: ValueSpecification[] = [fn('isNotEmpty', col(b.value))];
  if (view.from !== undefined) shown.push(fn('greaterThanEqual', one(b.at), dateLiteral(view.from, b.periodType)));
  if (view.to !== undefined) shown.push(fn('lessThanEqual', one(b.at), dateLiteral(`${view.to}${isDay(b.periodType) ? '' : 'T23:59:59'}`, b.periodType)));
  b.writer.rel = b.writer.rel.filter(row(and(...shown)));
}

/** The columns a series' drawdowns query answers with, a row per drawdown: its peak's date, its trough's and its
 *  recovery's (the next date at or above the peak; none, not recovered), its depth (-0.2 for 20%), the days from the
 *  peak to the trough and from the trough to the recovery, and its rank among the line's (1 the deepest). */
export const TS_DD_PEAK = '__ts_dd_peak';
export const TS_DD_TROUGH = '__ts_dd_trough';
export const TS_DD_RECOVERED = '__ts_dd_recovered';
export const TS_DD_DEPTH = '__ts_dd_depth';
export const TS_DD_DECLINE = '__ts_dd_decline';
export const TS_DD_RECOVERY = '__ts_dd_recovery';
export const TS_DD_RANK = '__ts_dd_rank';
/** The most drawdowns a line's table lists: its deepest. */
export const MAX_DRAWDOWNS = 5;

/**
 * A LEVEL'S DEEPEST DRAWDOWNS over the dates shown, per line (the expert tables, 2026-10-26): each a run from a peak --
 * a date at or above every one before it shown -- to the next such date, its recovery (none: it has not recovered),
 * its trough the least between; the deepest MAX_DRAWDOWNS of the line's, deepest first. Every date, depth and length
 * the database's: the peaks a running maximum, each run numbered by a running count of them, grouped, the next run's
 * peak its recovery (a lead), ranked. Of the level itself, compared or not; a level at or below zero has none.
 */
export function drawdownsQuery(inputs: TsInputs, ts: TimeseriesSpec, series: TsSeries, view: TsWindow = {}): TsQuery {
  const b = seriesRelation(inputs, ts, series, view.from);
  if (!b.level) throw new CubeRefusal(`series ${series.id} is no level: a return, a difference or a deviation has no drawdowns`);
  const w = b.writer;
  onlyShown(b, view);
  const lv = b.levelValue ?? b.value;
  const R = Writer.read;
  const peak = w.window('dd_peak', 'max', R(lv), 'running');
  const atPeak = fn('greaterThanEqual', one(lv), one(peak));
  const top = w.row('dd_top', when(atPeak, lit.integer(1), lit.integer(0)));
  const run = w.window('dd_run', 'plus', R(top), 'running');
  const y = (f: string): Lambda => lambda(['y'], fn(f, variable('y')));
  const key = b.split ?? w.part;
  const at = b.at;
  // each date's next peak: the first date after it at or above every one before it -- its run's recovery. A window
  // over the dates after each, before the runs are grouped: a filter after a window's extend is the window's own input
  // (legend-engine's folding of a plain predicate into the window's WHERE, core lowering/Fold.java filterSlot), so no
  // run is filtered before this is read
  const peakAt = w.row('dd_peak_at', when(atPeak, col(at), none()));
  const next = w.fresh('dd_next');
  w.rel = w.rel.extend([agg(next, lambda(['p', 'w', 'r'], Writer.read(peakAt)), y('min'))],
    fn('over', { _type: 'classInstance', type: 'colSpecArray', value: { colSpecs: [{ name: w.part }] } }, collection([asc(PERIOD)]),
      fn('rows', lit.integer(1), fn('unbounded'))));
  const peakV = w.fresh('dd_peak_v');
  const low = w.fresh('dd_low');
  const lowest = w.fresh('dd_level_low');
  let rel = w.rel.groupBy([key, run], [
    agg(TS_DD_PEAK, row(col(at)), y('min')),
    agg(peakV, row(col(lv)), y('max')),
    agg(low, row(col(lv)), y('min')),
    agg(TS_DD_TROUGH, row(fn('rowMapper', col(at), col(lv))), y('minBy')),
    agg(TS_DD_RECOVERED, row(col(next)), y('min')),
  ]);
  // the line's least level shown, beside each run: at or below zero, no drawdowns (a yield's ratios mean nothing)
  const lk = w.fresh('dd_lk');
  rel = rel.join(w.rel.groupBy([key], [agg(lowest, row(col(lv)), y('min'))]).rename(key, lk), 'LEFT',
    lambda(['l', 'r'], fn('equal', property(variable('l'), key), property(variable('r'), lk))));
  const over = (order: AppliedFunction): AppliedFunction =>
    fn('over', { _type: 'classInstance', type: 'colSpecArray', value: { colSpecs: [{ name: key }] } }, collection([order]));
  rel = rel
    .extend([derive(TS_DD_DEPTH, row(when(fn('lessThanEqual', one(lowest), lit.integer(0)), none(), op('minus', fn('divide', one(low), one(peakV)), lit.integer(1)))))])
    .filter(row(fn('lessThan', one(TS_DD_DEPTH), lit.integer(0))))
    .extend([derive(TS_DD_RANK, lambda(['p', 'w', 'r'], fn('rowNumber', variable('p'), variable('r'))))], over(asc(TS_DD_DEPTH)))
    .filter(row(fn('lessThanEqual', col(TS_DD_RANK), lit.integer(MAX_DRAWDOWNS))))
    .extend([
      derive(TS_DD_DECLINE, row(fn('dateDiff', one(TS_DD_PEAK), one(TS_DD_TROUGH), enumValue(DURATION, 'DAYS')))),
      derive(TS_DD_RECOVERY, row(when(fn('isEmpty', col(TS_DD_RECOVERED)), none(), fn('dateDiff', one(TS_DD_TROUGH), one(TS_DD_RECOVERED), enumValue(DURATION, 'DAYS'))))),
    ]);
  const out = [TS_DD_PEAK, TS_DD_TROUGH, TS_DD_RECOVERED, TS_DD_DEPTH, TS_DD_DECLINE, TS_DD_RECOVERY, TS_DD_RANK];
  if (b.split !== undefined) rel = rel.extend([derive(TS_SPLIT, row(col(b.split)))]);
  rel = rel.select([...(b.split !== undefined ? [TS_SPLIT] : []), ...out]).sort([...(b.split !== undefined ? [asc(TS_SPLIT)] : []), asc(TS_DD_RANK)]);
  return {
    series: series.id, query: rel.lambda(), unit: b.unit, band: false, ...(b.split !== undefined ? { split: b.split } : {}),
    level: true, counted: false, sources: b.sources,
  };
}

/** The columns a correlations query answers with, a row per pair of lines: each line's split value (none: the series'
 *  one line), their correlation over the dates both have shown, and how many dates that is. */
export const TS_CORR_A = '__ts_corr_a';
export const TS_CORR_B = '__ts_corr_b';
export const TS_CORR = '__ts_corr';
export const TS_CORR_N = '__ts_corr_n';

/**
 * THE CORRELATIONS OF TWO SERIES' LINES over the dates shown (the expert tables, 2026-10-26: the statistics panel's
 * Correlations tab): each line of `a` with each of `b` -- of one split series, each pair of its lines once -- their
 * correlation over the periods both have: a level's simple returns (gs-quant's correlation reads returns), anything
 * else as it is; each side cut to the dates shown, then its returns, then the two joined on the period and every pair
 * of lines aggregated -- the database's. A series with itself: each line with itself too (the matrix's diagonal).
 */
export function correlationsQuery(inputs: TsInputs, ts: TimeseriesSpec, a: TsSeries, b: TsSeries, view: TsWindow = {}): TsQuery {
  const names = { n: 0 };
  let periodType: string | undefined;
  const side = (s: TsSeries): { readonly w: Writer; readonly v: string; readonly sources: readonly string[] } => {
    const expr = candled(inlined(s.expr, ts.series));
    if (isBand(expr) || isCandles(expr) || isCondition(expr)) throw new CubeRefusal(`series ${s.id} is drawn, never correlated: a band, candles or a condition`);
    const read = sourcesOf(ts, expr);
    if (read.length === 0) throw new CubeRefusal(`series ${s.id} reads no column of a grid`);
    const sh: Shared = { ts, grain: ts.grain, series: s, inputs, split: s.split, first: read[0]!, anchor: view.from, periodType: undefined, names };
    const node = write(sh, expr);
    if ('constant' in node) throw new CubeRefusal(`series ${s.id} reads no column of a grid`);
    if (s.split !== undefined && !node.w.split) {
      throw new CubeRefusal(`series ${s.id} is split by ${s.split}, but reads one ${s.split}'s rows alone: a line per ${s.split} needs a column of every one`);
    }
    if (periodType === undefined) periodType = sh.periodType;
    else if (sh.periodType !== periodType) throw new CubeRefusal(`series ${a.id} and ${b.id} are of dates of two types: give the tile a frequency (Daily) to read both by day`);
    const w = node.w;
    const own = columnOfValue(node.v);
    const shown: ValueSpecification[] = [fn('isNotEmpty', col(own))];
    if (view.from !== undefined) shown.push(fn('greaterThanEqual', one(w.at), dateLiteral(view.from, sh.periodType!)));
    if (view.to !== undefined) shown.push(fn('lessThanEqual', one(w.at), dateLiteral(`${view.to}${isDay(sh.periodType!) ? '' : 'T23:59:59'}`, sh.periodType!)));
    w.rel = w.rel.filter(row(and(...shown)));
    return { w, v: levelOf(expr) ? w.simple(own) : own, sources: read };
  };
  const A = side(a);
  const la = names.n++;
  const pa = `__ts_corr_pa_${la}`;
  const va = `__ts_corr_va_${la}`;
  const left = A.w.rel.select([A.w.part, PERIOD, A.v]).rename(A.w.part, pa).rename(A.v, va);
  let pairs: Relation;
  let sources = A.sources;
  const bp = `__ts_corr_pb_${names.n}`;
  const bper = `__ts_corr_per_${names.n}`;
  const vb = `__ts_corr_vb_${names.n++}`;
  const on = (also: ValueSpecification[]): Lambda =>
    lambda(['l', 'r'], and(fn('equal', property(variable('l'), PERIOD), property(variable('r'), bper)), ...also));
  if (a.id === b.id) {
    // a series' lines against each other, each pair once (by their values' order), each with itself too: its diagonal
    // the database's, none where it has under two values or no variance (the E9 review's 13: a 1 written in its place)
    const right = A.w.rel.select([A.w.part, PERIOD, A.v]).rename(A.w.part, bp).rename(PERIOD, bper).rename(A.v, vb);
    pairs = left.join(right, 'INNER', on([fn('lessThanEqual', property(variable('l'), pa), property(variable('r'), bp))]));
  } else {
    const B = side(b);
    sources = [...new Set([...A.sources, ...B.sources])];
    const right = B.w.rel.select([B.w.part, PERIOD, B.v]).rename(B.w.part, bp).rename(PERIOD, bper).rename(B.v, vb);
    pairs = left.join(right, 'INNER', on([]));
  }
  const y = (f: string): Lambda => lambda(['y'], fn(f, variable('y')));
  // each side on the dates both have (the E9 review's 11: a first return of the other's none counted)
  const onBoth = (x: string, other: string): ValueSpecification => when(fn('isEmpty', col(other)), none(), col(x));
  const raw = `__ts_corr_raw_${names.n}`;
  const varA = `__ts_corr_var_a_${names.n}`;
  const varB = `__ts_corr_var_b_${names.n++}`;
  let rel = pairs.groupBy([pa, bp], [
    agg(raw, row(fn('rowMapper', col(va), col(vb))), y('corr')),
    agg(TS_CORR_N, row(onBoth(va, vb)), y('count')),
    agg(varA, row(onBoth(va, vb)), y('varianceSample')),
    agg(varB, row(onBoth(vb, va)), y('varianceSample')),
  ]);
  // none where a side does not vary, as on every database (DuckDB's corr says NaN there, Postgres' none): a line with
  // itself 1 where it varies (the E9 review's 13)
  rel = rel.extend([derive(TS_CORR, row(when(or(fn('isEmpty', col(varA)), fn('isEmpty', col(varB)),
    fn('equal', col(varA), lit.integer(0)), fn('equal', col(varB), lit.integer(0))), none(), col(raw))))]);
  // each line by its split value: a series of one line has none to say
  const lines = [...(a.split !== undefined ? [derive(TS_CORR_A, row(col(pa)))] : []), ...(b.split !== undefined ? [derive(TS_CORR_B, row(col(bp)))] : [])];
  if (lines.length > 0) rel = rel.extend(lines);
  rel = rel.select([...(a.split !== undefined ? [TS_CORR_A] : []), ...(b.split !== undefined ? [TS_CORR_B] : []), TS_CORR, TS_CORR_N]);
  return { series: a.id, query: rel.lambda(), unit: 'number', band: false, level: false, counted: false, sources };
}

// -- the views of a tile (the next phases' 3, 2026-10-26: a scatter, seasonality, a histogram, a curve, the calendar):
// each a query of its series' relations, every figure the database's --

/** The tile without its compare: a view that takes its own starts (a seasonality's years) or none (a calendar). */
function uncompared(ts: TimeseriesSpec): TimeseriesSpec {
  if (ts.view?.compare === undefined) return ts;
  const { compare: _taken, ...view } = ts.view;
  return { ...ts, view };
}

/** A window over every row of `rel` by a key every row has (`k`, a constant): the views' figures of the whole. */
const overAll = (k: string): AppliedFunction => fn('over', { _type: 'classInstance', type: 'colSpecArray', value: { colSpecs: [{ name: k }] } },
  collection([asc(PERIOD)]), fn('rows', fn('unbounded'), fn('unbounded')));

/** A series a view draws, as it is read: one line (a view of a split series' lines is a curve's), no band, no candles,
 *  no condition -- refused in words, by the view's name. */
function viewLine(inputs: TsInputs, ts: TimeseriesSpec, s: TsSeries, what: string, names: { n: number }, anchor?: string): Built {
  const expr = inlined(s.expr, ts.series);
  if (isBand(expr) || isCandles(expr) || isCondition(expr)) throw new CubeRefusal(`a ${what} draws a series' values: series ${s.id} is a band, candles or a condition`);
  if (s.split !== undefined) throw new CubeRefusal(`a ${what} is of one line: series ${s.id} is a line per ${s.split} -- read one ${s.split} (close[SPX])`);
  return seriesRelation(inputs, ts, s, anchor, names);
}

/** The columns a scatter answers with, a row per date both series have shown (its date, x and y), and on every row the
 *  regression of y on x over them: its slope, its intercept, R², the correlation, how many dates, and the fit at the
 *  least and the most x (the line drawn). */
export const TS_SC_X = '__ts_sc_x';
export const TS_SC_Y = '__ts_sc_y';
export const TS_SC_SLOPE = '__ts_sc_slope';
export const TS_SC_INTERCEPT = '__ts_sc_intercept';
export const TS_SC_R2 = '__ts_sc_r2';
export const TS_SC_CORR = '__ts_sc_corr';
export const TS_SC_N = '__ts_sc_n';
export const TS_SC_X_LOW = '__ts_sc_x_low';
export const TS_SC_X_HIGH = '__ts_sc_x_high';
export const TS_SC_FIT_LOW = '__ts_sc_fit_low';
export const TS_SC_FIT_HIGH = '__ts_sc_fit_high';

/**
 * A SCATTER OF TWO SERIES (Bloomberg's HRA, Macrobond's scatter): a point a date both have shown, y against x, each as
 * the series draws it -- and y's regression on x over those dates, ordinary least squares (the slope their sample
 * covariance over x's variance; the intercept through their means), its R² the correlation squared -- the database's.
 * None of the regression where x does not vary, or under two dates.
 */
export function scatterQuery(inputs: TsInputs, ts: TimeseriesSpec, x: TsSeries, y: TsSeries, view: TsWindow = {}): TsQuery {
  const names = { n: 0 };
  const X = viewLine(inputs, ts, x, 'scatter', names, view.from);
  const Y = viewLine(inputs, ts, y, 'scatter', names, view.from);
  if (X.periodType !== Y.periodType) throw new CubeRefusal(`series ${x.id} and ${y.id} are of dates of two types: give the tile a frequency (Daily) to read both by day`);
  onlyShown(X, view);
  onlyShown(Y, view);
  const fresh = (what: string): string => `__ts_sc_${what}_${names.n++}`;
  const yp = fresh('p');
  const left = X.writer.rel.select([PERIOD, X.at, X.value]).rename(X.at, TS_DATE).rename(X.value, TS_SC_X);
  const right = Y.writer.rel.select([PERIOD, Y.value]).rename(PERIOD, yp).rename(Y.value, TS_SC_Y);
  const k = fresh('k');
  let rel = left.join(right, 'INNER', lambda(['l', 'r'], fn('equal', property(variable('l'), PERIOD), property(variable('r'), yp))))
    .extend([derive(k, row(lit.integer(1)))]);
  const R = Writer.read;
  const all = (what: string, reduce: string, read: ValueSpecification): string => {
    const name = fresh(what);
    rel = rel.extend([agg(name, lambda(['p', 'w', 'r'], read), lambda(['y'], fn(reduce, variable('y'))))], overAll(k));
    return name;
  };
  const pair = fn('rowMapper', R(TS_SC_Y), R(TS_SC_X));
  const n = all('n', 'count', R(TS_SC_X));
  const cov = all('cov', 'covarSample', pair);
  const varX = all('vx', 'varianceSample', R(TS_SC_X));
  const varY = all('vy', 'varianceSample', R(TS_SC_Y));
  const mx = all('mx', 'average', R(TS_SC_X));
  const my = all('my', 'average', R(TS_SC_Y));
  const lo = all('lo', 'min', R(TS_SC_X));
  const hi = all('hi', 'max', R(TS_SC_X));
  const flat = or(fn('lessThan', one(n), lit.integer(2)), fn('isEmpty', col(varX)), fn('equal', one(varX), lit.integer(0)));
  rel = rel.extend([
    derive(TS_SC_N, row(col(n))),
    derive(TS_SC_SLOPE, row(when(flat, none(), fn('divide', one(cov), one(varX))))),
    derive(TS_SC_X_LOW, row(col(lo))),
    derive(TS_SC_X_HIGH, row(col(hi))),
  ]).extend([derive(TS_SC_INTERCEPT, row(when(fn('isEmpty', col(TS_SC_SLOPE)), none(), op('minus', one(my), op('times', one(TS_SC_SLOPE), one(mx))))))])
    .extend([
      derive(TS_SC_CORR, row(when(or(flat, fn('isEmpty', col(varY)), fn('equal', one(varY), lit.integer(0))), none(),
        fn('divide', one(cov), fn('sqrt', op('times', one(varX), one(varY))))))),
      derive(TS_SC_FIT_LOW, row(when(fn('isEmpty', col(TS_SC_SLOPE)), none(), op('plus', one(TS_SC_INTERCEPT), op('times', one(TS_SC_SLOPE), one(TS_SC_X_LOW)))))),
      derive(TS_SC_FIT_HIGH, row(when(fn('isEmpty', col(TS_SC_SLOPE)), none(), op('plus', one(TS_SC_INTERCEPT), op('times', one(TS_SC_SLOPE), one(TS_SC_X_HIGH)))))),
    ])
    .extend([derive(TS_SC_R2, row(when(fn('isEmpty', col(TS_SC_CORR)), none(), op('times', one(TS_SC_CORR), one(TS_SC_CORR)))))]);
  rel = rel.select([TS_DATE, TS_SC_X, TS_SC_Y, TS_SC_N, TS_SC_SLOPE, TS_SC_INTERCEPT, TS_SC_CORR, TS_SC_R2, TS_SC_X_LOW, TS_SC_X_HIGH, TS_SC_FIT_LOW, TS_SC_FIT_HIGH])
    .sort([asc(TS_DATE)]);
  return { series: y.id, query: rel.lambda(), unit: Y.unit, band: false, level: false, counted: false, sources: [...new Set([...X.sources, ...Y.sources])] };
}

/** The columns a seasonality answers with, a row per date of its last years: the date's year, its place in the year (a
 *  day of the year, a week, a month or a quarter by the tile's frequency), its value; and, at its place, the least, the
 *  most and the mean of the years before the latest -- with the latest year itself. */
export const TS_SEA_YEAR = '__ts_sea_year';
export const TS_SEA_PLACE = '__ts_sea_place';
export const TS_SEA_LOW = '__ts_sea_low';
export const TS_SEA_HIGH = '__ts_sea_high';
export const TS_SEA_MEAN = '__ts_sea_mean';
export const TS_SEA_LATEST = '__ts_sea_latest';
/** The years before's range at the place, its most less its least: what the drawing shades over its least. */
export const TS_SEA_SPAN = '__ts_sea_span';

/**
 * SEASONALITY (the commodities desk's chart, Bloomberg's SEAG): each of the last `years` years and the latest along its
 * calendar -- its place in the year by the tile's frequency -- and at each place the least, the most and the mean of the
 * years before the latest: the range the latest is read against. Compare (the view's) takes each year from its own start
 * (rebase 100, or its % change): the periods overlaid from their starts. The dates shown are its years', not the range's.
 */
export function seasonalityQuery(inputs: TsInputs, ts: TimeseriesSpec, s: TsSeries, years = ts.view?.years ?? TS_SEASON_YEARS): TsQuery {
  if (ts.grain === 'year') throw new CubeRefusal('a seasonality is of the places in a year: at a yearly frequency a year has one -- read it monthly, weekly or daily');
  const compare = ts.view?.compare;
  // the series as it is, each year taken from its own start below -- never from the window's
  const plain = uncompared(ts);
  const b = viewLine(inputs, plain, s, 'seasonality', { n: 0 });
  const w = b.writer;
  const R = Writer.read;
  w.rel = w.rel.filter(row(fn('isNotEmpty', col(b.value))));
  const at = one(b.at);
  const year = w.row('sea_y', fn('year', at));
  const grain = ts.grain;
  // a daily series (or one of no frequency) by the week of the year, each year its week's last value: a day of the year
  // held the years that traded that day alone -- weekends and holidays apart year to year, a leap year a day off (the
  // Phase 3 review's 2)
  const place = w.row('sea_pl', grain === 'month' ? fn('monthNumber', at) : grain === 'quarter' ? fn('quarterNumber', at)
    : op('plus', fn('floor', fn('divide', op('minus', fn('dayOfYear', at), lit.integer(1)), lit.integer(7))), lit.integer(1)));
  let value = b.value;
  if (compare !== undefined && b.level) {
    // each year from its first value: 100 there (rebase), or its change from there over its size (percent)
    const firstP = w.window('sea_fp', 'min', R(PERIOD), 'all', year);
    const first = w.window('sea_fv', 'max', R(w.row('sea_f', when(fn('equal', col(PERIOD), col(firstP)), col(b.value), none()))), 'all', year);
    const change = w.row('sea_chg', when(fn('equal', one(first), lit.integer(0)), none(), fn('divide', op('minus', one(b.value), one(first)), fn('abs', one(first)))));
    value = compare === 'rebase' ? w.row('sea_cmp', op('times', op('plus', lit.integer(1), one(change)), lit.integer(100))) : change;
  }
  if (grain === undefined || grain === 'day') {
    // each year's week its last value: the database's QUALIFY of its row of the week's latest number in time
    const yk = w.row('sea_yk', op('plus', op('times', one(year), lit.integer(100)), one(place)));
    const last = w.window('sea_wl', 'max', R(ORD), 'all', yk);
    w.rel = w.rel.filter(row(fn('equal', one(ORD), one(last))));
  }
  const latest = w.window('sea_l', 'max', R(year), 'all');
  // its last years: a window's figure, kept by the database's QUALIFY (after the windows above)
  w.rel = w.rel.filter(row(fn('greaterThanEqual', one(year), op('minus', one(latest), lit.integer(years)))));
  const y = (f: string): Lambda => lambda(['y'], fn(f, variable('y')));
  const pk = w.fresh('sea_pk');
  const before = w.rel.filter(row(fn('lessThan', one(year), one(latest))))
    .groupBy([place], [agg(TS_SEA_LOW, row(col(value)), y('min')), agg(TS_SEA_HIGH, row(col(value)), y('max')), agg(TS_SEA_MEAN, row(col(value)), y('average'))])
    .rename(place, pk);
  const rel = w.rel.join(before, 'LEFT', lambda(['l', 'r'], fn('equal', property(variable('l'), place), property(variable('r'), pk))))
    .extend([derive(TS_DATE, row(col(b.at))), derive(TS_VALUE, row(col(value))), derive(TS_SEA_YEAR, row(col(year))), derive(TS_SEA_PLACE, row(col(place))),
      derive(TS_SEA_LATEST, row(col(latest))), derive(TS_SEA_SPAN, row(op('minus', one(TS_SEA_HIGH), one(TS_SEA_LOW))))])
    .select([TS_DATE, TS_SEA_YEAR, TS_SEA_PLACE, TS_VALUE, TS_SEA_LOW, TS_SEA_HIGH, TS_SEA_SPAN, TS_SEA_MEAN, TS_SEA_LATEST])
    .sort([asc(TS_SEA_YEAR), asc(TS_DATE)]);
  const unit = compare === 'rebase' && b.level ? 'index' : compare === 'percent' && b.level ? 'ratio' : b.unit;
  return { series: s.id, query: rel.lambda(), unit, band: false, level: b.level, counted: false, sources: b.sources };
}

/** The columns a histogram answers with, a row per bin a value fell in: its edges, how many fell in it, how many a
 *  normal of the values' mean and deviation would put there; and on every row the values' 5th percentile, mean,
 *  deviation and count. */
export const TS_HIST_LOW = '__ts_hist_low';
export const TS_HIST_HIGH = '__ts_hist_high';
/** A bin's middle: where its bar and its normal's count are drawn. */
export const TS_HIST_MID = '__ts_hist_mid';
export const TS_HIST_COUNT = '__ts_hist_count';
export const TS_HIST_NORMAL = '__ts_hist_normal';
export const TS_HIST_P5 = '__ts_hist_p5';
export const TS_HIST_MEAN = '__ts_hist_mean';
export const TS_HIST_SD = '__ts_hist_sd';
export const TS_HIST_N = '__ts_hist_n';

/**
 * A HISTOGRAM OF A SERIES' VALUES over the dates shown: `bins` of one width from its least to its most (the most in
 * the last), each counted; beside each, the count a normal of the same mean and sample deviation would give it (n times
 * the width times its density at the bin's middle); and the values' 5th percentile -- a return's historical value at
 * risk, marked -- every figure the database's.
 */
export function histogramQuery(inputs: TsInputs, ts: TimeseriesSpec, s: TsSeries, view: TsWindow = {}, bins = ts.view?.bins ?? TS_HIST_BINS): TsQuery {
  const b = viewLine(inputs, ts, s, 'histogram', { n: 0 }, view.from);
  const w = b.writer;
  onlyShown(b, view);
  const R = Writer.read;
  const v = b.value;
  const lo = w.window('h_lo', 'min', R(v), 'all');
  const hi = w.window('h_hi', 'max', R(v), 'all');
  const mean = w.window('h_m', 'average', R(v), 'all');
  const sd = w.window('h_sd', 'stdDevSample', R(v), 'all');
  const n = w.window('h_n', 'count', R(v), 'all');
  const width = w.row('h_w', fn('divide', op('minus', one(hi), one(lo)), float(bins)));
  const raw = w.row('h_raw', when(fn('equal', one(width), lit.integer(0)), lit.integer(0), fn('floor', fn('divide', op('minus', one(v), one(lo)), one(width)))));
  const bin = w.row('h_bin', when(fn('greaterThanEqual', one(raw), lit.integer(bins)), lit.integer(bins - 1), col(raw)));
  const y = (f: string): Lambda => lambda(['y'], fn(f, variable('y')));
  const key = w.part;
  const rn = w.rowNumber();
  // the 5th percentile of the values: the database's (continuous, as pandas' quantile), one aggregate, joined back
  const qk = w.fresh('h_qk');
  const quantile = w.rel.groupBy([key], [agg(TS_HIST_P5, row(col(v)), lambda(['y'], fn('percentile', variable('y'), float(VAR_LEVEL), lit.boolean(true), lit.boolean(true))))])
    .rename(key, qk);
  const [cl, cw, cm, cs, cn] = ['h_cl', 'h_cw', 'h_cm', 'h_cs', 'h_cn'].map((x) => w.fresh(x)) as [string, string, string, string, string];
  // the line's figures, once
  const line = w.rel.groupBy([key], [
    agg(cl, row(col(lo)), y('max')), agg(cw, row(col(width)), y('max')), agg(cm, row(col(mean)), y('max')), agg(cs, row(col(sd)), y('max')), agg(cn, row(col(n)), y('max')),
  ]).join(quantile, 'LEFT', lambda(['l', 'r'], fn('equal', property(variable('l'), key), property(variable('r'), qk))));
  // every bin, one no value fell in counted 0 (the Phase 3 review's 6: the empty ones had no row, their normal never
  // drawn): their numbers 0 to bins - 1 the first ten values' rows each with each, numbered -- a hundred, the most bins
  const [tk, tr, uk, ur, bn] = ['h_tk', 'h_tr', 'h_uk', 'h_ur', 'h_bn'].map((x) => w.fresh(x)) as [string, string, string, string, string];
  const ten = w.rel.filter(row(fn('lessThanEqual', one(rn), lit.integer(10)))).select([key, rn]);
  const numbers = ten.rename(key, tk).rename(rn, tr)
    .join(ten.rename(key, uk).rename(rn, ur), 'INNER', lambda(['l', 'r'], fn('equal', property(variable('l'), tk), property(variable('r'), uk))))
    .extend([derive(bn, lambda(['p', 'w', 'r'], op('minus', fn('rowNumber', variable('p'), variable('r')), lit.integer(1))))],
      fn('over', { _type: 'classInstance', type: 'colSpecArray', value: { colSpecs: [{ name: tk }] } }, collection([asc(tr), asc(ur)])))
    .filter(row(fn('lessThan', one(bn), lit.integer(bins))))
    .select([tk, bn]);
  const [ck, cb, cc] = ['h_ck', 'h_cb', 'h_cc'].map((x) => w.fresh(x)) as [string, string, string];
  const counted = w.rel.groupBy([key, bin], [agg(cc, row(col(v)), y('count'))]).rename(key, ck).rename(bin, cb);
  let rel = numbers.join(line, 'INNER', lambda(['l', 'r'], fn('equal', property(variable('l'), tk), property(variable('r'), key))))
    .join(counted, 'LEFT', lambda(['l', 'r'], and(fn('equal', property(variable('l'), tk), property(variable('r'), ck)),
      fn('equal', property(variable('l'), bn), property(variable('r'), cb)))))
    // values all one: one bin, of no width
    .filter(row(or(fn('equal', one(bn), lit.integer(0)), fn('greaterThan', one(cw), lit.integer(0)))));
  const middle = op('plus', one(TS_HIST_LOW), fn('divide', one(cw), lit.integer(2)));
  const z = fn('divide', op('minus', middle, one(cm)), one(cs));
  rel = rel.extend([
    derive(TS_HIST_LOW, row(op('plus', one(cl), op('times', one(bn), one(cw))))),
    derive(TS_HIST_COUNT, row(when(fn('isEmpty', col(cc)), lit.integer(0), col(cc)))),
    derive(TS_HIST_MEAN, row(col(cm))), derive(TS_HIST_SD, row(col(cs))), derive(TS_HIST_N, row(col(cn))),
  ]).extend([
    derive(TS_HIST_HIGH, row(op('plus', one(TS_HIST_LOW), one(cw)))),
    derive(TS_HIST_MID, row(middle)),
    // the normal's count: n * width * exp(-z² / 2) / (sd * sqrt(2 pi)); none where the values do not vary
    derive(TS_HIST_NORMAL, row(when(or(fn('isEmpty', col(cs)), fn('equal', one(cs), lit.integer(0))), none(),
      fn('divide', op('times', op('times', one(cn), one(cw)), fn('exp', op('times', float(-0.5), op('times', z, z)))),
        op('times', one(cs), fn('sqrt', float(2 * Math.PI))))))),
  ]).select([TS_HIST_LOW, TS_HIST_MID, TS_HIST_HIGH, TS_HIST_COUNT, TS_HIST_NORMAL, TS_HIST_P5, TS_HIST_MEAN, TS_HIST_SD, TS_HIST_N])
    .sort([asc(TS_HIST_LOW)]);
  return { series: s.id, query: rel.lambda(), unit: b.unit, band: false, level: b.level, counted: false, sources: b.sources };
}

/** The columns a curve answers with, a row per line (its split value, TS_SPLIT) and for each date asked (its place
 *  among them, `k`): the date as the database found it (the latest date, a period back from it, a day), the line's
 *  value as of then -- its last at or before -- and that value's own date. */
export const curveColumns = (k: number): { readonly asked: string; readonly value: string; readonly date: string } =>
  ({ asked: `__ts_curve_asked_${k}`, value: `__ts_curve_value_${k}`, date: `__ts_curve_date_${k}` });

/**
 * A CURVE (a yield curve, a futures strip): a split series' lines across its key -- its tenors -- as of each date asked:
 * `latest`, the latest date any line has; a period back from it (`1m`, `1y`); a day. Each line its last value at or
 * before the date, and the date it is of: one aggregate a line, every date asked beside the others -- the database's.
 */
export function curveQuery(inputs: TsInputs, ts: TimeseriesSpec, s: TsSeries, dates: readonly string[] = ts.view?.dates ?? TS_CURVE_DATES): TsQuery {
  if (s.split === undefined) throw new CubeRefusal(`a curve is of a line per value of a column (tenors): series ${s.id} is one line -- split it`);
  const expr = inlined(s.expr, ts.series);
  if (isBand(expr) || isCandles(expr) || isCondition(expr)) throw new CubeRefusal(`a curve draws a series' values: series ${s.id} is a band, candles or a condition`);
  // its lines as they are: the tile's compare is the time view's, never each line from its own first value (the Phase 3
  // review's 7)
  const b = seriesRelation(inputs, uncompared(ts), s, undefined);
  const w = b.writer;
  const split = b.split!;
  w.rel = w.rel.filter(row(fn('isNotEmpty', col(b.value))));
  const k = w.fresh('cv_k');
  const latest = w.fresh('cv_latest');
  w.rel = w.rel.extend([derive(k, row(lit.integer(1)))])
    .extend([agg(latest, lambda(['p', 'w', 'r'], Writer.read(b.at)), lambda(['y'], fn('max', variable('y'))))], overAll(k));
  const y = (f: string): Lambda => lambda(['y'], fn(f, variable('y')));
  const aggs = dates.flatMap((d, i) => {
    const t = tenorOf(d);
    // a day asked is the whole of it, on a source of date-times too (the Phase 3 review's 10: its own observations left out)
    const target = w.row('cv_t', d === 'latest' ? col(latest) : t !== undefined ? back(one(latest), t)
      : dateLiteral(isDay(b.periodType) ? d : `${d}T23:59:59`, b.periodType));
    const before = fn('lessThanEqual', one(b.at), one(target));
    const c = curveColumns(i);
    // each line's last value at or before it: the latest by its number in time (ORD: a maxBy's key is a number)
    return [
      agg(c.asked, row(col(target)), y('max')),
      agg(c.date, row(when(before, col(b.at), none())), y('max')),
      agg(c.value, row(fn('rowMapper', col(b.value), when(before, col(ORD), none()))), y('maxBy')),
    ];
  });
  const rel = w.rel.groupBy([split], aggs).extend([derive(TS_SPLIT, row(col(split)))])
    .select([TS_SPLIT, ...dates.flatMap((_, i) => { const c = curveColumns(i); return [c.asked, c.value, c.date]; })])
    .sort([asc(TS_SPLIT)]);
  return { series: s.id, query: rel.lambda(), unit: b.unit, band: false, level: b.level, split, counted: false, sources: b.sources };
}

/** A tenor as a curve's key spells it (`3M`, `10Y`, `1W`): its span in days, to order by (never drawn). */
const KEY_TENOR = /^(\d+(?:\.\d+)?)\s*([DWMY])$/i;
const TENOR_DAYS: Readonly<Record<string, number>> = { D: 1, W: 7, M: 30.4375, Y: 365.25 };
/** A money market's words for its shortest tenors, by their days: overnight, tom-next, spot-next. */
const KEY_WORDS: Readonly<Record<string, number>> = { ON: 1, 'O/N': 1, TN: 2, 'T/N': 2, SN: 3, 'S/N': 3 };

/**
 * A CURVE'S KEYS IN ORDER -- the one rule the screen's curve and Python's read (headless timeseriesCurveOrder): its
 * tenors (`3M`, `2Y`, `10Y`; a money market's `ON`, `TN`, `SN`) by their span, as a yield curve is read (3M before 2Y),
 * then any other key by its text, its numbers as numbers (2 before 10) -- a key no tenor never putting the tenors in
 * text order (the Phase 3 review's 8). The places of `keys`, in that order.
 */
export function curveOrder(keys: readonly string[]): number[] {
  const spans = keys.map((k) => {
    const word = KEY_WORDS[k.trim().toUpperCase()];
    if (word !== undefined) return word;
    const m = KEY_TENOR.exec(k.trim());
    return m ? Number(m[1]) * TENOR_DAYS[m[2]!.toUpperCase()]! : undefined;
  });
  return keys.map((_, i) => i).sort((a, b) => {
    const [x, y] = [spans[a], spans[b]];
    if (x !== undefined && y !== undefined && x !== y) return x - y;
    if ((x === undefined) !== (y === undefined)) return x === undefined ? 1 : -1;
    return keys[a]!.localeCompare(keys[b]!, UI_LOCALE, { numeric: true });
  });
}

/** The columns a calendar answers with, a row per month a line has: its year, its month, its return (from the month
 *  before's last level to its own), and its year's (from the year before's last to its own). */
export const TS_CAL_YEAR = '__ts_cal_year';
export const TS_CAL_MONTH = '__ts_cal_month';
export const TS_CAL_RETURN = '__ts_cal_return';
export const TS_CAL_YEAR_RETURN = '__ts_cal_year_return';
/** The line's least month-end level, on every row: a return from one at or below zero is none. */
export const TS_CAL_LOW = '__ts_cal_low';

/**
 * A LEVEL'S CALENDAR OF RETURNS (the monthly returns table every fund sheet has): each month's simple return, from the
 * month before's last level to its own last, and each year's, from the year before's last to its own -- the first month
 * and the first year none (no level before them). Of every date the line has, each line apart -- the database's.
 */
export function calendarQuery(inputs: TsInputs, ts: TimeseriesSpec, s: TsSeries): TsQuery {
  const expr = inlined(s.expr, ts.series);
  if (isBand(expr) || isCandles(expr) || isCondition(expr)) throw new CubeRefusal(`a calendar of returns is of a level: series ${s.id} is a band, candles or a condition`);
  // months of a level read at least monthly: a quarterly tile's quarters were written under months (the Phase 3 review's 1)
  if (ts.grain === 'quarter' || ts.grain === 'year') {
    throw new CubeRefusal(`a calendar of months reads a level at least monthly: this tile is ${ts.grain === 'quarter' ? 'quarterly' : 'yearly'} -- read it monthly, weekly or daily`);
  }
  const b = seriesRelation(inputs, uncompared(ts), s, undefined);
  if (!b.level) throw new CubeRefusal(`series ${s.id} is no level: a return, a difference or a deviation has no calendar of returns`);
  const w = b.writer;
  const key = b.split ?? w.part;
  w.rel = w.rel.filter(row(fn('isNotEmpty', col(b.value))));
  const yearOf = w.row('cal_y', fn('year', one(b.at)));
  const monthOf = w.row('cal_m', fn('monthNumber', one(b.at)));
  const y = (f: string): Lambda => lambda(['y'], fn(f, variable('y')));
  const last = w.fresh('cal_last');
  // each month's last level, the latest by its number in time (ORD: a maxBy's key is a number); each month a number (its
  // year's twelve after the year before's): the month before is its number less one, never the last month present --
  // over a missing month, none, not two months as one (the Phase 3 review's 1: a lag of the months present)
  const idx = w.fresh('cal_i');
  const months = w.rel.groupBy([key, yearOf, monthOf], [agg(last, row(fn('rowMapper', col(b.value), col(ORD))), y('maxBy'))])
    .extend([derive(idx, row(op('plus', op('times', one(yearOf), lit.integer(12)), one(monthOf))))]);
  const prev = w.fresh('cal_prev');
  const ret = (now: string, before: string): ValueSpecification =>
    when(or(fn('isEmpty', col(before)), fn('lessThanEqual', one(before), lit.integer(0))), none(), op('minus', fn('divide', one(now), one(before)), lit.integer(1)));
  const [pk, pi] = [w.fresh('cal_pk'), w.fresh('cal_pi')];
  const monthly = months.join(months.select([key, idx, last]).rename(key, pk).rename(idx, pi).rename(last, prev), 'LEFT',
    lambda(['l', 'r'], and(fn('equal', property(variable('l'), key), property(variable('r'), pk)),
      fn('equal', property(variable('l'), idx), op('plus', property(variable('r'), pi), lit.integer(1))))))
    .extend([derive(TS_CAL_RETURN, row(ret(last, prev)))]);
  // each year's last level, its last month's; the year before's by its number less one
  const yl = w.fresh('cal_yl');
  const yp = w.fresh('cal_yp');
  const [yk, yy, qk, qy] = [w.fresh('cal_yk'), w.fresh('cal_yy'), w.fresh('cal_qk'), w.fresh('cal_qy')];
  const years = months.groupBy([key, yearOf], [agg(yl, row(fn('rowMapper', col(last), col(idx))), y('maxBy'))]);
  const yearly = years.join(years.rename(key, qk).rename(yearOf, qy).rename(yl, yp), 'LEFT',
    lambda(['l', 'r'], and(fn('equal', property(variable('l'), key), property(variable('r'), qk)),
      fn('equal', property(variable('l'), yearOf), op('plus', property(variable('r'), qy), lit.integer(1))))))
    .extend([derive(TS_CAL_YEAR_RETURN, row(ret(yl, yp)))]).select([key, yearOf, TS_CAL_YEAR_RETURN]).rename(key, yk).rename(yearOf, yy);
  let rel = monthly.join(yearly, 'LEFT', lambda(['l', 'r'], and(fn('equal', property(variable('l'), key), property(variable('r'), yk)),
    fn('equal', property(variable('l'), yearOf), property(variable('r'), yy)))))
    .extend([derive(TS_CAL_YEAR, row(col(yearOf))), derive(TS_CAL_MONTH, row(col(monthOf)))])
    // the line's least month-end level: a return from one at or below zero is none, said so (the Phase 3 review's 9)
    .extend([agg(TS_CAL_LOW, lambda(['p', 'w', 'r'], property(variable('r'), last)), lambda(['y'], fn('min', variable('y'))))],
      fn('over', { _type: 'classInstance', type: 'colSpecArray', value: { colSpecs: [{ name: key }] } }, collection([asc(idx)]), fn('rows', fn('unbounded'), fn('unbounded'))));
  if (b.split !== undefined) rel = rel.extend([derive(TS_SPLIT, row(col(b.split)))]);
  rel = rel.select([...(b.split !== undefined ? [TS_SPLIT] : []), TS_CAL_YEAR, TS_CAL_MONTH, TS_CAL_RETURN, TS_CAL_YEAR_RETURN, TS_CAL_LOW])
    .sort([...(b.split !== undefined ? [asc(TS_SPLIT)] : []), asc(TS_CAL_YEAR), asc(TS_CAL_MONTH)]);
  return { series: s.id, query: rel.lambda(), unit: 'ratio', band: false, level: false, counted: false, sources: b.sources, ...(b.split !== undefined ? { split: b.split } : {}) };
}

/** The series a tile's views can draw: of one line drawn on an axis (a scatter's, a seasonality's, a histogram's), and
 *  split (a curve's) -- no band, no condition, no candles. Settings' pickers offer these alone. */
export function viewChoices(ts: TimeseriesSpec): { readonly oneLine: readonly string[]; readonly split: readonly string[] } {
  const drawable = tableSeries(ts).statistics.filter((id) => !isCandles(inlined(ts.series.find((x) => x.id === id)!.expr, ts.series)));
  const splitOf = (id: string): boolean => ts.series.find((x) => x.id === id)!.split !== undefined;
  return { oneLine: drawable.filter((id) => !splitOf(id)), split: drawable.filter(splitOf) };
}

/**
 * THE SERIES EACH VIEW DRAWS -- the one rule the screen and Python's views read: the view's `of` where it fits the view
 * (a scatter two series of one line, x then y -- one told, x, the next its y; a seasonality and a histogram one; a
 * curve a split series), else the tile's first it can draw; the calendar every level's but candles'. One `of` serves
 * every mode, so a mode never takes one it cannot draw (the Phase 3 review's 4: a scatter's x handed to a curve).
 * None where the tile has none to draw: said by the view.
 */
export function viewSeries(ts: TimeseriesSpec): {
  readonly scatter?: readonly [string, string]; readonly one?: string; readonly curve?: string; readonly calendar: readonly string[];
} {
  const { oneLine, split } = viewChoices(ts);
  const drawable = new Set([...oneLine, ...split]);
  const of = ts.view?.of ?? [];
  const x = of[0] !== undefined && oneLine.includes(of[0]) ? of[0] : undefined;
  const y = of[1] !== undefined && oneLine.includes(of[1]) ? of[1] : undefined;
  const pairOf: readonly [string, string] | undefined = x !== undefined && y !== undefined ? [x, y]
    : x !== undefined && oneLine.some((id) => id !== x) ? [x, oneLine.find((id) => id !== x)!]
      : oneLine.length >= 2 ? [oneLine[0]!, oneLine[1]!] : undefined;
  const oneOf = x ?? oneLine[0];
  const curveOf = of[0] !== undefined && split.includes(of[0]) ? of[0] : split[0];
  return {
    ...(pairOf !== undefined ? { scatter: pairOf } : {}), ...(oneOf !== undefined ? { one: oneOf } : {}), ...(curveOf !== undefined ? { curve: curveOf } : {}),
    // every level's but a candle's (its four prices no one level)
    calendar: tableSeries(ts).drawdowns.filter((id) => drawable.has(id)),
  };
}

/** The tables of a tile's series (the statistics panel's tabs, and Python's Timeseries tables), and its views'. */
export type TsTable = 'statistics' | 'drawdowns' | 'correlations' | 'scatter' | 'seasonality' | 'histogram' | 'curve' | 'calendar';

/**
 * THE SERIES EACH OF A TILE'S TABLES READS -- the one rule the screen's tabs and Python's tables read: the statistics,
 * every series drawn on an axis (no band, no condition: drawn, never figures of their own); the drawdowns, every
 * level's; the correlations, each pair of those drawn on an axis but candles, once, a split series with itself (its
 * lines against each other). A series that cannot be read (it reads itself, or one there is not) has none: it is said
 * where it is drawn.
 */
export function tableSeries(ts: TimeseriesSpec): { readonly statistics: readonly string[]; readonly drawdowns: readonly string[]; readonly correlations: readonly (readonly [string, string])[] } {
  const read = ts.series.flatMap((s) => {
    try {
      return [{ s, e: inlined(s.expr, ts.series) }];
    } catch (e) {
      if (e instanceof TsInlineError) return [];
      throw e;
    }
  });
  const onAxis = read.filter(({ e }) => !isBand(e) && !isCondition(e));
  const paired = onAxis.filter(({ e }) => !isCandles(e));
  return {
    statistics: onAxis.map(({ s }) => s.id),
    drawdowns: onAxis.filter(({ e }) => levelOf(e)).map(({ s }) => s.id),
    correlations: paired.flatMap(({ s }, i) => paired.slice(i).map(({ s: t }) => [s.id, t.id] as const)),
  };
}

/** Where an ask's answer is shown: the chart itself, or one of the statistics panel's tabs. */
export type TsServes = 'chart' | 'statistics' | 'drawdowns' | 'correlations' | 'calendar';

/** The words of where an answer is shown, for a person reading the queries. */
export const TS_SERVES_WORDS: Readonly<Record<TsServes, string>> = {
  chart: 'the chart', statistics: 'the Summary and Risk tabs', drawdowns: 'the Drawdowns tab',
  correlations: 'the Correlations tab', calendar: 'the Calendar tab',
};

/**
 * ONE QUESTION A TILE ASKS THE DATABASE: a source's bounds (its dates: the range buttons' starts), a series' points, or
 * one of its tables or views -- of a series (`series`), of a pair (`with`), or of a source (`source`) -- in words
 * (`what`), and where its answer is shown (`serves`).
 */
export interface TsAsk {
  readonly kind: 'bounds' | 'series' | TsTable;
  readonly series?: string;
  readonly with?: string;
  readonly source?: string;
  readonly what: string;
  readonly serves: TsServes;
}

/** What an ask is of: what its query is built from, without its words. */
export type TsAskOf = Pick<TsAsk, 'kind' | 'series' | 'with' | 'source'>;

/** An ask's key: what it is of, the same for every round that asks it. */
export const askKey = (a: TsAskOf): string =>
  JSON.stringify([a.kind, a.series ?? null, a.with ?? null, a.source ?? null]);

/**
 * EVERY QUESTION A TILE ASKS THE DATABASE -- the one rule the screen's Query window and Python's Timeseries.queries
 * read (the user, 2026-10-26: "show all the pure queries used to create a timeseries chart"): to draw the chart, each
 * source's bounds, each series' points (a hidden one's too: its chip says it) and the view's (a mode's); for the
 * statistics panel, each line's statistics, each level's drawdowns, each pair's correlation and each level's calendar
 * of returns -- each by the rule its own drawing reads (tableSeries, viewSeries). Each ask's query is askQuery's.
 */
export function tileAsks(ts: TimeseriesSpec): TsAsk[] {
  const named = (id: string): string => {
    const s = ts.series.find((x) => x.id === id);
    return s === undefined ? id : `${id} (${seriesName(s)})`;
  };
  const tables = tableSeries(ts);
  const views = viewSeries(ts);
  const asks: TsAsk[] = [
    ...ts.sources.map((s): TsAsk => ({
      kind: 'bounds', source: s.alias, serves: 'chart',
      what: `Source ${s.alias}'s dates: its latest and earliest, where each range button starts`,
    })),
    ...ts.series.map((s): TsAsk => ({
      kind: 'series', series: s.id, serves: 'chart', what: `Series ${named(s.id)}: its points${s.hidden === true ? ' (hidden)' : ''}`,
    })),
  ];
  const mode = ts.view?.mode;
  if (mode === 'scatter' && views.scatter !== undefined) {
    asks.push({ kind: 'scatter', series: views.scatter[0], with: views.scatter[1], serves: 'chart', what: `The scatter: ${named(views.scatter[0])} across, ${named(views.scatter[1])} up, and the fit` });
  } else if ((mode === 'seasonality' || mode === 'histogram') && views.one !== undefined) {
    asks.push({ kind: mode, series: views.one, serves: 'chart', what: `The ${mode} of ${named(views.one)}` });
  } else if (mode === 'curve' && views.curve !== undefined) {
    asks.push({ kind: 'curve', series: views.curve, serves: 'chart', what: `The curve of ${named(views.curve)} across its lines` });
  }
  asks.push(
    ...tables.statistics.map((id): TsAsk => ({ kind: 'statistics', series: id, serves: 'statistics', what: `Series ${named(id)}: its statistics over the dates shown` })),
    ...tables.drawdowns.map((id): TsAsk => ({ kind: 'drawdowns', series: id, serves: 'drawdowns', what: `Series ${named(id)}: its deepest drawdowns over the dates shown` })),
    ...tables.correlations.map(([a, b]): TsAsk => ({
      kind: 'correlations', series: a, with: b, serves: 'correlations',
      what: a !== b ? `The correlation of ${named(a)} and ${named(b)}`
        : ts.series.find((x) => x.id === a)?.split !== undefined ? `Series ${named(a)}: its lines' correlations with each other` : `Series ${named(a)}: its correlation with itself`,
    })),
    ...views.calendar.map((id): TsAsk => ({ kind: 'calendar', series: id, serves: 'calendar', what: `Series ${named(id)}: its returns each month and each year` })),
  );
  return asks;
}

/** The sources an ask reads, by alias: its series', and its pair's too. */
export function askSources(ts: TimeseriesSpec, ask: TsAskOf): string[] {
  if (ask.kind === 'bounds') return [ask.source!];
  const of = (id: string | undefined): TsSeries[] => {
    if (id === undefined) return [];
    const s = ts.series.find((x) => x.id === id);
    if (!s) throw new Error(`the tile has no series ${id}`);
    return [s];
  };
  const read = [...new Set([...of(ask.series), ...of(ask.with)].flatMap((s) => seriesSources(ts, s)))];
  if (read.length === 0) throw new CubeRefusal(`series ${ask.series ?? ''} reads no column of a grid`);
  return read;
}

/**
 * AN ASK'S QUERY, as the screen asks it -- the one function the viewer's every round and Python's Timeseries.queries
 * build each query by: a series' points from the first date shown where it reads one (an index's base, compare's), every
 * date kept, the drawing's limit; a table or a view over the dates shown (`window`), where it reads them. A source's
 * bounds are boundsQuery's, of its one grid.
 */
export function askQuery(inputs: TsInputs, ts: TimeseriesSpec, ask: TsAskOf, window: TsWindow): TsQuery {
  const named = (id: string | undefined): TsSeries => {
    const s = id === undefined ? undefined : ts.series.find((x) => x.id === id);
    if (!s) throw new Error(`the tile has no series ${JSON.stringify(id ?? null)}`);
    return s;
  };
  const s = named(ask.series);
  switch (ask.kind) {
    case 'bounds':
      throw new Error('a source\'s bounds are boundsQuery\'s, of its one grid');
    case 'series':
      return seriesQuery(inputs, ts, s, readsAnchor(ts, s) && window.from ? { from: window.from } : {});
    case 'statistics':
      return statsQuery(inputs, ts, s, window);
    case 'drawdowns':
      return drawdownsQuery(inputs, ts, s, window);
    case 'correlations':
      return correlationsQuery(inputs, ts, s, named(ask.with), window);
    case 'scatter':
      return scatterQuery(inputs, ts, s, named(ask.with), window);
    case 'seasonality':
      return seasonalityQuery(inputs, ts, s);
    case 'histogram':
      return histogramQuery(inputs, ts, s, window);
    case 'curve':
      return curveQuery(inputs, ts, s);
    case 'calendar':
      return calendarQuery(inputs, ts, s);
  }
}

/**
 * A SERIES' STATISTICS over the dates shown (`view`: from, to; either open), per line: how many, the first and the last
 * date, the first and the last value and the change between them, the least and the most (and when), the mean and the
 * standard deviation; for a level, its annualized volatility (of its log returns, in percent), its deepest drawdown, its
 * compound annual growth and its Sharpe ratio (a risk-free rate of zero) -- every one the database's, over what is shown.
 */
export function statsQuery(inputs: TsInputs, ts: TimeseriesSpec, series: TsSeries, view: TsWindow = {}): TsQuery {
  const b = seriesRelation(inputs, ts, series, view.from);
  // the series' own writer: a writer of its own would name a lag of the statistics as the series named its own
  const w = b.writer;
  const v = b.value;
  // a level's volatility and Sharpe ratio, a difference's or a deviation's none (the review's Q2); annualized by the
  // periods a year of every date the series has (a tile of no frequency: inferred, before the window shown is cut)
  const level = b.level;
  const inferred = level && ts.grain === undefined;
  const perYear = level ? w.perYear() : undefined;
  const yearly = inferred ? w.row('stat_py', perYear!) : undefined;
  onlyShown(b, view);
  // a level's figures of its returns, its drawdown and its growth: of the level itself, compared or not
  const lv = b.levelValue ?? v;
  const rLog = level ? w.returns(lv, 1, 'log') : undefined;
  const rSimple = level ? w.simple(lv) : undefined;
  const peak = level ? w.window('peak', 'max', Writer.read(lv), 'running') : undefined;
  const dd = peak !== undefined
    ? w.row('dd', when(fn('lessThanEqual', one(peak), lit.integer(0)), none(), op('minus', fn('divide', one(lv), one(peak)), lit.integer(1))))
    : undefined;
  const y = (f: string): Lambda => lambda(['y'], fn(f, variable('y')));
  const S = statName;
  const keys = b.split !== undefined ? [b.split] : [w.part];
  // a level's returns' risk over what is shown: their moments about their mean (skew, kurtosis), their downside (Sortino),
  // their share above zero, their worst 5% (a historical value at risk, the quantile the database interpolates, and the
  // average of the returns at or below it: an expected shortfall)
  let risk: { readonly d2: string; readonly d3: string; readonly d4: string; readonly down: string; readonly up: string; readonly tail: string; readonly q: string } | undefined;
  if (rSimple !== undefined) {
    const mean = w.window('stat_rm', 'average', Writer.read(rSimple), 'all');
    const dev = w.row('stat_dev', op('minus', one(rSimple), one(mean)));
    const d2 = w.row('stat_d2', op('times', one(dev), one(dev)));
    const d3 = w.row('stat_d3', op('times', one(d2), one(dev)));
    const d4 = w.row('stat_d4', op('times', one(d2), one(d2)));
    const has = (x: ValueSpecification): ValueSpecification => when(fn('isEmpty', col(rSimple)), none(), x);
    const down = w.row('stat_down', has(when(fn('lessThan', one(rSimple), lit.integer(0)), op('times', one(rSimple), one(rSimple)), float(0))));
    const up = w.row('stat_up', has(when(fn('greaterThan', one(rSimple), lit.integer(0)), float(1), float(0))));
    const q = w.fresh('stat_q');
    const qk = w.fresh('stat_qk');
    const quantile = w.rel.groupBy(keys, [agg(q, row(col(rSimple)),
      lambda(['y'], fn('percentile', variable('y'), float(VAR_LEVEL), lit.boolean(true), lit.boolean(true))))]).rename(keys[0]!, qk);
    w.rel = w.rel.join(quantile, 'LEFT', lambda(['l', 'r'], fn('equal', property(variable('l'), keys[0]!), property(variable('r'), qk))));
    const tail = w.row('stat_tail', when(fn('lessThanEqual', one(rSimple), one(q)), col(rSimple), none()));
    risk = { d2, d3, d4, down, up, tail, q };
  }
  // a candle's least and most are its lows' and highs' (the review's Q9), a line's its values'
  const lows = b.low ?? v;
  const highs = b.high ?? v;
  const aggs: ColSpec[] = [
    agg(S('count'), row(col(v)), y('count')),
    agg(S('from'), row(col(b.at)), y('min')),
    agg(S('to'), row(col(b.at)), y('max')),
    agg(S('first'), row(fn('rowMapper', col(v), col(b.ord))), y('minBy')),
    agg(S('last'), row(fn('rowMapper', col(v), col(b.ord))), y('maxBy')),
    agg(S('low'), row(col(lows)), y('min')),
    agg(S('lowAt'), row(fn('rowMapper', col(b.at), col(lows))), y('minBy')),
    agg(S('high'), row(col(highs)), y('max')),
    agg(S('highAt'), row(fn('rowMapper', col(b.at), col(highs))), y('maxBy')),
    agg(S('mean'), row(col(v)), y('average')),
    agg(S('std'), row(col(v)), y('stdDevSample')),
    ...(rLog !== undefined ? [agg('__ts_stat_logsd', row(col(rLog)), y('stdDevSample'))] : []),
    ...(rSimple !== undefined ? [
      agg('__ts_stat_rmean', row(col(rSimple)), y('average')),
      agg('__ts_stat_rsd', row(col(rSimple)), y('stdDevSample')),
    ] : []),
    ...(dd !== undefined ? [agg('__ts_stat_mdd_all', row(col(dd)), y('min'))] : []),
    ...(yearly !== undefined ? [agg(TS_STAT_PER_YEAR, row(col(yearly)), y('max'))] : []),
    ...(level ? [
      agg(TS_STAT_LEVEL_LOW, row(col(lv)), y('min')),
      agg('__ts_stat_lfirst', row(fn('rowMapper', col(lv), col(b.ord))), y('minBy')),
      agg('__ts_stat_llast', row(fn('rowMapper', col(lv), col(b.ord))), y('maxBy')),
    ] : []),
    ...(risk !== undefined && rSimple !== undefined ? [
      agg('__ts_stat_rn', row(col(rSimple)), y('count')),
      agg('__ts_stat_m2', row(col(risk.d2)), y('average')),
      agg('__ts_stat_m3', row(col(risk.d3)), y('average')),
      agg('__ts_stat_m4', row(col(risk.d4)), y('average')),
      agg('__ts_stat_down2', row(col(risk.down)), y('average')),
      agg('__ts_stat_hit_all', row(col(risk.up)), y('average')),
      agg('__ts_stat_best_all', row(col(rSimple)), y('max')),
      agg('__ts_stat_worst_all', row(col(rSimple)), y('min')),
      agg('__ts_stat_q', row(col(risk.q)), y('max')),
      agg('__ts_stat_es_all', row(col(risk.tail)), y('average')),
    ] : []),
  ];
  let rel = w.rel.groupBy(keys, aggs);
  const first = one(S('first'));
  const last = one(S('last'));
  const derived: ColSpec[] = [
    derive(S('change'), row(op('minus', last, first))),
    // the change over its start's size: a start below zero rising is a rise (the review's Q11)
    derive(S('pct'), row(when(fn('equal', first, lit.integer(0)), none(), fn('divide', op('minus', last, first), fn('abs', first))))),
  ];
  if (level) {
    const days = fn('dateDiff', one(S('from')), one(S('to')), enumValue(DURATION, 'DAYS'));
    const perYear = fn('sqrt', yearly !== undefined ? one(TS_STAT_PER_YEAR) : w.perYear());
    // a level's figures of its returns, its drawdown and its growth, over a window it is above zero in throughout:
    // gone to zero or below (a yield), none of them -- never one of the part above zero, never a sign turned (the
    // second audit's S1), and the viewer says why (its statistics' blanks)
    const above = (x: ValueSpecification): ValueSpecification => when(fn('lessThanEqual', one(TS_STAT_LEVEL_LOW), lit.integer(0)), none(), x);
    const lfirst = one('__ts_stat_lfirst');
    const llast = one('__ts_stat_llast');
    derived.push(
      derive(S('vol'), row(above(op('times', op('times', one('__ts_stat_logsd'), perYear), lit.integer(100))))),
      derive(S('mdd'), row(above(one('__ts_stat_mdd_all')))),
      // compound annual growth over a calendar year or more, the database's own date arithmetic deciding (a leap
      // year's 366 days a year): under a year, none -- a return of under a year is never annualized (GIPS; the
      // review's N3: 8,507% over four days), its % change its growth, and the viewer says so
      derive(S('cagr'), row(when(or(fn('lessThan', one(S('to')), fn('adjust', one(S('from')), lit.integer(1), enumValue(DURATION, 'YEARS'))),
        fn('lessThanEqual', one(TS_STAT_LEVEL_LOW), lit.integer(0))), none(),
        op('minus', fn('pow', fn('divide', llast, lfirst), fn('divide', float(365.25), days)), lit.integer(1))))),
      derive(S('sharpe'), row(above(when(fn('equal', one('__ts_stat_rsd'), lit.integer(0)), none(),
        op('times', fn('divide', one('__ts_stat_rmean'), one('__ts_stat_rsd')), perYear))))),
    );
    // its returns' risk (pandas' definitions, as gs-quant reads a series): skew, the adjusted Fisher-Pearson coefficient
    // (pandas' skew); kurtosis, the excess one, bias-corrected (pandas' kurt); Sortino, the mean return over the root mean
    // square of the returns below zero, annualized; the share of returns above zero; the best and the worst; the 5%
    // quantile and the average at or below it -- none of a level at or below zero, as its other figures
    const n = one('__ts_stat_rn');
    const m2 = one('__ts_stat_m2');
    const m3 = one('__ts_stat_m3');
    const m4 = one('__ts_stat_m4');
    const minus = (a: ValueSpecification, k: number): ValueSpecification => op('minus', a, lit.integer(k));
    const plus = (a: ValueSpecification, k: number): ValueSpecification => op('plus', a, lit.integer(k));
    derived.push(
      derive(S('skew'), row(above(when(or(fn('lessThanEqual', n, lit.integer(2)), fn('equal', m2, lit.integer(0))), none(),
        fn('divide', op('times', fn('divide', m3, fn('pow', m2, float(1.5))), fn('sqrt', op('times', n, minus(n, 1)))), minus(n, 2)))))),
      derive(S('kurtosis'), row(above(when(or(fn('lessThanEqual', n, lit.integer(3)), fn('equal', m2, lit.integer(0))), none(),
        fn('divide',
          op('minus', fn('divide', op('times', op('times', plus(n, 1), minus(n, 1)), m4), op('times', m2, m2)), op('times', lit.integer(3), op('times', minus(n, 1), minus(n, 1)))),
          op('times', minus(n, 2), minus(n, 3))))))),
      derive(S('sortino'), row(above(when(fn('equal', one('__ts_stat_down2'), lit.integer(0)), none(),
        op('times', fn('divide', one('__ts_stat_rmean'), fn('sqrt', one('__ts_stat_down2'))), perYear))))),
      derive(S('hit'), row(above(one('__ts_stat_hit_all')))),
      derive(S('best'), row(above(one('__ts_stat_best_all')))),
      derive(S('worst'), row(above(one('__ts_stat_worst_all')))),
      derive(S('var'), row(above(one('__ts_stat_q')))),
      derive(S('es'), row(above(one('__ts_stat_es_all')))),
    );
  }
  rel = rel.extend(derived);
  // Calmar: its growth over its deepest drawdown's depth -- of a growth (a year or more) and a drawdown below zero
  if (level) {
    rel = rel.extend([derive(S('calmar'), row(when(or(fn('isEmpty', col(S('cagr'))), fn('greaterThanEqual', one(S('mdd')), lit.integer(0))), none(),
      fn('divide', one(S('cagr')), fn('abs', one(S('mdd')))))))]);
  }
  const kept = [...TS_STATS.filter((st) => level || !LEVEL_STATS.includes(st)).map(S),
    ...(yearly !== undefined ? [TS_STAT_PER_YEAR] : []), ...(level ? [TS_STAT_LEVEL_LOW] : [])];
  if (b.split !== undefined) rel = rel.extend([derive(TS_SPLIT, row(col(b.split)))]);
  rel = rel.select([...(b.split !== undefined ? [TS_SPLIT] : []), ...kept]);
  return {
    series: series.id,
    query: rel.lambda(),
    unit: b.unit,
    band: false,
    ...(b.split !== undefined ? { split: b.split } : {}),
    level: b.level,
    counted: false,
    sources: b.sources,
  };
}

/**
 * WHAT A SERIES' ROWS SAY A READER SHOULD KNOW, from its query's answer -- the one rule the viewer's drawing and Python's
 * frame say them by (the second audit's P-3: Python's frame dropped them): a period's value one of several rows at its
 * latest time, the database's to pick (the review's Q7); a line whose dates are no frequency to annualize by, by its
 * name (the review's Q3; the second audit's P-2a). `table`: its answer's columns, its counted rows and its periods a year
 * among them (a host may send those alone); `first`: the source it reads first.
 */
export function seriesNotes(ts: TimeseriesSpec, s: TsSeries, table: ResultTable, first: string | undefined): string[] {
  const notes: string[] = [];
  const column = (name: string): readonly unknown[] | undefined => table.columns.find((c) => c.name === name)?.values;
  const rows = column(TS_ROWS);
  if (rows && rows.some((n) => n !== null && Number(n) > 1)) {
    const key = ts.sources.find((x) => x.alias === first)?.key;
    notes.push(`${seriesName(s)}: several rows share a date's latest time, and one was taken -- match one (a column's [...])`
      + `${key !== undefined && s.split === undefined ? ` or split it by ${key}` : ''}, so each date has one.`);
  }
  const perYear = column(TS_PER_YEAR);
  if (perYear && perYear.length > 0) {
    const splits = column(TS_SPLIT);
    const lines = new Map<string, { split: unknown; annualized: boolean }>();
    perYear.forEach((n, i) => {
      const split = splits ? splits[i] ?? null : undefined;
      const k = split === undefined ? '' : JSON.stringify(split);
      const line = lines.get(k) ?? { split, annualized: false };
      if (n !== null) line.annualized = true;
      lines.set(k, line);
    });
    const none = [...lines.values()].filter((l) => !l.annualized).map((l) => splitName(s, l.split))
      .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
    if (none.length > 0) {
      notes.push(`${none.join(', ')}: ${none.length === 1 ? 'its' : 'their'} dates are no frequency it can be annualized by (daily, weekly, monthly, quarterly, yearly): choose the tile's frequency.`);
    }
  }
  return notes;
}

/** Whether a series' expression reads the first date shown (an index, a change from the start, compare's rebasing). */
export function readsAnchor(ts: TimeseriesSpec, series: TsSeries): boolean {
  const walk = (e: TsExpr): boolean => e.kind === 'call'
    ? ['index', 'rebase', 'change', 'percent_change'].includes(e.fn) || e.args.some(walk)
    : e.kind === 'binary' ? walk(e.left) || walk(e.right) : false;
  // a series that cannot be inlined (one reading itself) is refused here, as where it is drawn
  const expr = inlined(series.expr, ts.series);
  return walk(expr) || (ts.view?.compare !== undefined && !isBand(expr) && !isCandles(expr) && levelOf(expr));
}

/** The most values the instrument picker lists. */
export const KEY_VALUES_LIMIT = 2000;

/**
 * THE VALUES OF A COLUMN, to pick an instrument from (close[...]): each once, in order, at most KEY_VALUES_LIMIT, from
 * the grid's rows as the chart reads them -- the database's distinct values, as the grid's Filter Values window asks
 * them.
 */
export function keyValuesQuery(input: TsInput, src: TsSource, column: string): Lambda {
  const s = input.snapshot;
  const typeOf = (c: string): string | undefined => typeIn(s, c);
  let rel = sourceWithDerived(s);
  const conditions: ValueSpecification[] = [fn('isNotEmpty', col(column))];
  const filter = filterRead(src, input.filter);
  if (filter) conditions.push(filterNode(filter, 'x', typeOf));
  rel = rel.filter(row(and(...conditions)))
    .groupBy([column], [agg('__ts_n', row(lit.integer(1)), lambda(['y'], fn('count', variable('y'))))])
    .sort([asc(column)])
    .limit(KEY_VALUES_LIMIT);
  return rel.select([column]).lambda();
}
