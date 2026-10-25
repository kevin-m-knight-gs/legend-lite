// A TIMESERIES' QUERIES (docs/DATACUBE_TIMESERIES_DESIGN_2026_10_25.md §4): each series of a timeseries tile
// (timeseries-spec.ts) as one query of its grid's source, which the planner plans and the DATABASE computes -- every
// return, volatility, z-score and drawdown a window over the dates or an expression over the row, as every
// calculated column of DataCube's is (calc-presets.ts). Nothing here computes a value; it writes what to compute.
//
//   1. the grid's source, its row-level calculated columns, its filter (the chart's: chart-spec.ts chartFilter) and
//      what the series matches (close[AAPL])
//   2. grouped by period (the tile's frequency: a date's day, week, month...) -- and by the split, one line per value --
//      each column the series reads taken by its period's aggregate: the latest row's value by default (maxBy over the
//      day's number, `dateDiff` from 1970: maxBy orders by a number)
//   3. the dates every column the series reads has (gs-quant's `intersect`), the split's first MAX_SPLIT_LINES values
//   4. each function of the expression, innermost first: its columns over the dates, every window partitioned by the
//      split and ordered by the period; a rolling figure drawn once its window is full (gs-quant's ramp)
//   5. an index or a change from the start reads the value at the FIRST DATE SHOWN -- the anchor the viewer passes
//      (where its zoom starts), found by the database: the first period at or after it that has a value
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
import { isDay } from '../../engine-client/src/types.ts';
import {
  inlined, isBand, MAX_SPLIT_LINES, paramOf, PERIODS_PER_YEAR, RANGE_STEP, TS_POINT_LIMIT, TS_RANGES, unitOf,
  type TimeseriesSpec, type TsAggregate, type TsExpr, type TsMatch, type TsRangeWord, type TsSeries, type TsUnit,
} from './timeseries-spec.ts';
import type { TimeGrain } from './chart-spec.ts';

/** The columns a series' query answers with. */
export const TS_DATE = '__ts_date';
export const TS_VALUE = '__ts_value';
export const TS_LOWER = '__ts_lower';
export const TS_UPPER = '__ts_upper';
export const TS_SPLIT = '__ts_split';
/** How many of the grid's rows each period's value was taken from: more than one, for a series of no instrument, is said. */
export const TS_ROWS = '__ts_rows';

/** The columns the bounds query answers with: the latest and earliest dates, and each range button's start. */
export const TS_LATEST = '__ts_latest';
export const TS_EARLIEST = '__ts_earliest';
export const rangeStartName = (w: TsRangeWord): string => `__ts_from_${w}`;

/** The statistics a series' statistics query answers with, by line, in the table's order. */
export const TS_STATS = [
  'count', 'from', 'to', 'first', 'last', 'change', 'pct', 'low', 'lowAt', 'high', 'highAt', 'mean', 'std', 'vol', 'mdd',
  'cagr', 'sharpe',
] as const;
export type TsStat = (typeof TS_STATS)[number];
export const statName = (s: TsStat): string => `__ts_stat_${s}`;

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
  readonly band: boolean;
  readonly split?: string;
  /** Whether a period's value may be taken from several rows (a series of no instrument): its rows are counted. */
  readonly counted: boolean;
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
  readonly ord: string;
  readonly value: string;
  readonly lower?: string;
  readonly upper?: string;
  readonly split?: string;
  readonly unit: TsUnit;
  readonly rows?: string;
}

/** The type the compiler gave a column of the grid (its source's or a row-level calculated column's). */
function typeIn(s: CubeSnapshot, column: string): string | undefined {
  return columnType(s, column);
}

/** The compiler's function taking a date to its period's first day. */
const GRAIN_FUNCTION: Readonly<Record<Exclude<TimeGrain, 'day'>, string>> = {
  week: 'firstDayOfWeek', month: 'firstDayOfMonth', quarter: 'firstDayOfQuarter', year: 'firstDayOfYear',
};

/** A column's matches as one condition on a row. */
function matched(s: CubeSnapshot, ts: TimeseriesSpec, where: readonly TsMatch[]): ValueSpecification | undefined {
  if (where.length === 0) return undefined;
  const terms = where.map((m) => {
    const c = m.column ?? ts.key;
    if (c === undefined) throw new CubeRefusal('a value is matched with no column: choose the column naming the instruments');
    return fn('equal', col(c), literalNode(m.value, typeIn(s, c)));
  });
  return and(...terms);
}

/** Each column the series reads, once: the column, how a period takes it, and what it matches. */
interface Base {
  readonly key: string;
  readonly column: string;
  readonly fn: TsAggregate;
  readonly where: readonly TsMatch[];
}

function basesOf(e: TsExpr): Base[] {
  const out = new Map<string, Base>();
  const walk = (n: TsExpr): void => {
    if (n.kind === 'column') {
      const b: Base = { column: n.column, fn: n.fn ?? 'last', where: n.where ?? [], key: '' };
      const key = JSON.stringify([b.column, b.fn, b.where]);
      if (!out.has(key)) out.set(key, { ...b, key });
    } else if (n.kind === 'binary') {
      walk(n.left);
      walk(n.right);
    } else if (n.kind === 'call') n.args.forEach(walk);
  };
  walk(e);
  return [...out.values()];
}

const baseKey = (n: Extract<TsExpr, { kind: 'column' }>): string => JSON.stringify([n.column, n.fn ?? 'last', n.where ?? []]);

/** A series' query written in steps: the relation so far, and a name for each column it adds. */
class Writer {
  rel: Relation;
  #n = 0;
  readonly part: string;
  readonly period: string;
  readonly ord: string;
  /** Each base's column in the grouped relation, by its key. */
  readonly bases = new Map<string, string>();
  readonly ts: TimeseriesSpec;
  readonly perYear: number;
  readonly anchor: string | undefined;
  readonly periodType: string;
  #inView: string | undefined;

  constructor(rel: Relation, o: {
    part: string; period: string; ord: string; ts: TimeseriesSpec; anchor: string | undefined; periodType: string;
  }) {
    this.rel = rel;
    this.part = o.part;
    this.period = o.period;
    this.ord = o.ord;
    this.ts = o.ts;
    this.perYear = PERIODS_PER_YEAR[o.ts.grain ?? 'day'];
    this.anchor = o.anchor;
    this.periodType = o.periodType;
  }

  fresh(what: string): string {
    return `__ts_${what}_${this.#n++}`;
  }

  /** `->extend(~[name: x|body])`. */
  row(what: string, body: ValueSpecification): string {
    const name = this.fresh(what);
    this.rel = this.rel.extend([derive(name, row(body))]);
    return name;
  }

  /** The window every function of the series runs over: its split's partition, the periods in order, a frame. */
  #over(frame: 'running' | 'all' | number | null): AppliedFunction {
    const args: ValueSpecification[] = [
      { _type: 'classInstance', type: 'colSpecArray', value: { colSpecs: [{ name: this.part }] } },
      collection([asc(this.period)]),
    ];
    if (frame === 'running') args.push(fn('rows', fn('unbounded'), lit.integer(0)));
    else if (frame === 'all') args.push(fn('rows', fn('unbounded'), fn('unbounded')));
    else if (frame !== null) args.push(fn('rows', lit.integer(-(Math.max(1, frame) - 1) || 0), lit.integer(0)));
    return fn('over', ...args);
  }

  /** An aggregate over a frame of the periods: `extend(over(...), ~[name:{p,w,r|<read>}:y|$y-><reduce>()])`. */
  window(what: string, reduce: string, read: ValueSpecification, frame: 'running' | 'all' | number): string {
    const name = this.fresh(what);
    this.rel = this.rel.extend([agg(name, lambda(['p', 'w', 'r'], read), lambda(['y'], fn(reduce, variable('y'))))], this.#over(frame));
    return name;
  }

  /** A column `n` periods back: `$p->lag($r, n).<column>`. */
  lag(column: string, n: number): string {
    const name = this.fresh('lag');
    const call = fn('lag', variable('p'), variable('r'), ...(n !== 1 ? [lit.integer(n)] : []));
    this.rel = this.rel.extend([derive(name, lambda(['p', 'w', 'r'], property(call, column)))], this.#over(null));
    return name;
  }

  /** A column's value on a frame's rows, as a window reads it. */
  static read(column: string): ValueSpecification {
    return property(variable('r'), column);
  }

  /** `w` periods of `column` (0: from the start): its frame. */
  static frame(w: number): 'running' | number {
    return w === 0 ? 'running' : w;
  }

  /** A rolling figure drawn once its window is full: empty while fewer than `w` of `input`'s values are in it. */
  ramped(value: string, input: string, w: number): string {
    if (w <= 1) return value;
    const count = this.window('n', 'count', Writer.read(input), w);
    return this.row('ramped', when(fn('lessThan', one(count), lit.integer(w)), none(), col(value)));
  }

  /** `a / b`, empty where b is zero. */
  ratio(what: string, a: ValueSpecification, b: string): string {
    return this.row(what, when(fn('equal', one(b), lit.integer(0)), none(), fn('divide', a, one(b))));
  }

  /**
   * THE VALUE AT THE FIRST DATE SHOWN, beside every row: the first period at or after the anchor (the viewer's left
   * edge; none, the first period) where `column` has a value -- the least such period, the database's -- and the value
   * there, each a window over the whole partition.
   */
  anchored(column: string): string {
    if (this.#inView === undefined) {
      this.#inView = this.anchor === undefined ? '' : this.row('inview',
        fn('greaterThanEqual', one(this.period), literalNode(this.anchor, this.periodType)));
    }
    const shown = this.#inView === '' ? fn('isNotEmpty', col(column)) : and(one(this.#inView), fn('isNotEmpty', col(column)));
    const mark = this.row('anchor_at', when(shown, col(this.period), none()));
    const at = this.window('anchor_period', 'min', Writer.read(mark), 'all');
    const value = this.row('anchor_mark', when(fn('equal', col(this.period), col(at)), col(column), none()));
    return this.window('anchor', 'max', Writer.read(value), 'all');
  }

  /** Returns over `obs` periods, of a type (gs-quant's econometrics.returns). */
  returns(a: string, obs: number, type: string): string {
    const p = this.lag(a, obs);
    switch (type) {
      case 'log':
        return this.row('ret', when(or(fn('lessThanEqual', one(a), lit.integer(0)), fn('lessThanEqual', one(p), lit.integer(0))), none(),
          op('minus', fn('log', one(a)), fn('log', one(p)))));
      case 'absolute':
        return this.row('ret', op('minus', one(a), one(p)));
      default:
        return this.row('ret', when(fn('equal', one(p), lit.integer(0)), none(), op('minus', fn('divide', one(a), one(p)), lit.integer(1))));
    }
  }

  /** The square root of the periods in a year, as the database takes it. */
  annual(): ValueSpecification {
    return fn('sqrt', lit.integer(this.perYear));
  }

  /** A sample standard deviation over `w` periods (0 from the start), drawn once its window is full. */
  std(a: string, w: number): string {
    return this.ramped(this.window('std', 'stdDevSample', Writer.read(a), Writer.frame(w)), a, Math.max(w, 2));
  }

  /** Simple returns, for the functions gs-quant reads them for (a correlation's, a beta's, a Sharpe ratio's). */
  simple(a: string): string {
    return this.returns(a, 1, 'simple');
  }
}

/** The value a node computes: its column's name, a number, or two columns (a band). */
type Value = { readonly column: string } | { readonly number: number } | { readonly lower: string; readonly upper: string };

function asOperand(v: Value): ValueSpecification {
  if ('number' in v) return Number.isInteger(v.number) ? lit.integer(v.number) : float(v.number);
  if ('column' in v) return one(v.column);
  throw new CubeRefusal('a band is drawn, never read');
}

function columnOf(w: Writer, v: Value, what: string): string {
  if ('column' in v) return v.column;
  if ('number' in v) return w.row(what, asOperand(v));
  throw new CubeRefusal('a band is drawn, never read');
}

/** A node of the expression, written: its own columns after those of what it reads. */
function write(w: Writer, e: TsExpr): Value {
  switch (e.kind) {
    case 'number':
      return { number: e.value };
    case 'ref':
      throw new Error('a series is read inlined (timeseries-spec.ts inlined)');
    case 'column': {
      const c = w.bases.get(baseKey(e));
      if (c === undefined) throw new Error(`no grouped column for ${e.column}`);
      return { column: c };
    }
    case 'binary': {
      const l = write(w, e.left);
      const r = write(w, e.right);
      if ('number' in l && 'number' in r) {
        // two numbers: the database's arithmetic still, over every row
        return { column: w.row('num', op(e.op === '+' ? 'plus' : e.op === '-' ? 'minus' : e.op === '*' ? 'times' : 'divide', asOperand(l), asOperand(r))) };
      }
      if (e.op === '/') {
        const denominator = columnOf(w, r, 'num');
        return { column: w.ratio('div', asOperand(l), denominator) };
      }
      return { column: w.row('op', op(e.op === '+' ? 'plus' : e.op === '-' ? 'minus' : 'times', asOperand(l), asOperand(r))) };
    }
    case 'call':
      return call(w, e);
  }
}

function call(w: Writer, e: Extract<TsExpr, { kind: 'call' }>): Value {
  const arg = (i: number): string => columnOf(w, write(w, e.args[i]!), 'arg');
  const n = (name: string): number => paramOf(e, name) as number;
  const word = (name: string): string => paramOf(e, name) as string;
  const R = Writer.read;
  switch (e.fn) {
    case 'returns':
      return { column: w.returns(arg(0), n('obs'), word('type')) };
    case 'prices': {
      const a = arg(0);
      const initial = float(n('initial'));
      const type = word('type');
      if (type === 'absolute') return { column: w.row('px', op('plus', initial, one(w.window('cum', 'plus', R(a), 'running')))) };
      const growth = type === 'log' ? a : w.row('lg', when(fn('lessThanEqual', one(a), lit.integer(-1)), none(), fn('log', op('plus', lit.integer(1), one(a)))));
      const cum = w.window('cum', 'plus', R(growth), 'running');
      return { column: w.row('px', op('times', initial, fn('exp', one(cum)))) };
    }
    case 'diff': {
      const a = arg(0);
      return { column: w.row('diff', op('minus', one(a), one(w.lag(a, n('obs'))))) };
    }
    case 'lag':
      return { column: w.lag(arg(0), n('obs')) };
    case 'annualize':
      return { column: w.row('ann', op('times', one(arg(0)), w.annual())) };
    case 'index':
    case 'rebase': {
      const a = arg(0);
      const base = w.anchored(a);
      const scale = float(n(e.fn === 'index' ? 'initial' : 'base'));
      return { column: w.row('idx', when(fn('equal', one(base), lit.integer(0)), none(), op('times', fn('divide', one(a), one(base)), scale))) };
    }
    case 'change': {
      const a = arg(0);
      return { column: w.row('chg', op('minus', one(a), one(w.anchored(a)))) };
    }
    case 'percent_change': {
      const a = arg(0);
      const base = w.anchored(a);
      return { column: w.row('pchg', when(fn('equal', one(base), lit.integer(0)), none(), op('minus', fn('divide', one(a), one(base)), lit.integer(1)))) };
    }
    case 'mean':
    case 'sum':
    case 'min':
    case 'max': {
      const a = arg(0);
      const size = n('w');
      const reduce = e.fn === 'mean' ? 'average' : e.fn === 'sum' ? 'plus' : e.fn;
      return { column: w.ramped(w.window(e.fn, reduce, R(a), Writer.frame(size)), a, size) };
    }
    case 'range': {
      const a = arg(0);
      const size = n('w');
      const hi = w.window('max', 'max', R(a), Writer.frame(size));
      const lo = w.window('min', 'min', R(a), Writer.frame(size));
      return { column: w.ramped(w.row('rng', op('minus', one(hi), one(lo))), a, size) };
    }
    case 'std':
      return { column: w.std(arg(0), n('w')) };
    case 'var': {
      const a = arg(0);
      const size = n('w');
      return { column: w.ramped(w.window('var', 'varianceSample', R(a), Writer.frame(size)), a, Math.max(size, 2)) };
    }
    case 'zscores': {
      const a = arg(0);
      const size = n('w');
      const frame = size === 0 ? 'all' : size;
      const mean = w.window('mean', 'average', R(a), frame);
      const sd = w.window('std', 'stdDevSample', R(a), frame);
      const z = w.ratio('z', op('minus', one(a), one(mean)), sd);
      return { column: size === 0 ? z : w.ramped(z, a, size) };
    }
    case 'winsorize': {
      const a = arg(0);
      const limit = float(n('limit'));
      const mean = w.window('mean', 'average', R(a), 'all');
      const sd = w.window('std', 'stdDevSample', R(a), 'all');
      const hi = w.row('hi', op('plus', one(mean), op('times', limit, one(sd))));
      const lo = w.row('lo', op('minus', one(mean), op('times', limit, one(sd))));
      return { column: w.row('wins', when(fn('greaterThan', one(a), one(hi)), col(hi), when(fn('lessThan', one(a), one(lo)), col(lo), col(a)))) };
    }
    case 'volatility': {
      const a = arg(0);
      const size = n('w');
      const r = w.returns(a, 1, word('type'));
      const sd = w.std(r, size);
      return { column: w.row('vol', op('times', op('times', one(sd), w.annual()), lit.integer(100))) };
    }
    case 'drawdown': {
      const a = arg(0);
      const peak = w.window('peak', 'max', R(a), Writer.frame(n('w')));
      return { column: w.row('dd', when(fn('equal', one(peak), lit.integer(0)), none(), op('minus', fn('divide', one(a), one(peak)), lit.integer(1)))) };
    }
    case 'max_drawdown': {
      const a = arg(0);
      const size = n('w');
      const peak = w.window('peak', 'max', R(a), Writer.frame(size));
      const dd = w.row('dd', when(fn('equal', one(peak), lit.integer(0)), none(), op('minus', fn('divide', one(a), one(peak)), lit.integer(1))));
      return { column: w.ramped(w.window('mdd', 'min', R(dd), Writer.frame(size)), a, size) };
    }
    case 'sharpe_ratio': {
      const a = arg(0);
      const size = n('w');
      const r = w.simple(a);
      const mean = w.window('mean', 'average', R(r), Writer.frame(size));
      const sd = w.std(r, size);
      return { column: w.ramped(w.row('sharpe', when(fn('equal', one(sd), lit.integer(0)), none(),
        op('times', fn('divide', one(mean), one(sd)), w.annual()))), r, size) };
    }
    case 'correlation':
    case 'beta':
    case 'cov': {
      const a = arg(0);
      const b = arg(1);
      const size = n('w');
      const frame = Writer.frame(size);
      const [ra, rb] = e.fn === 'cov' ? [a, b] : [w.simple(a), w.simple(b)];
      const pair = fn('rowMapper', R(ra), R(rb));
      let v: string;
      if (e.fn === 'correlation') v = w.window('corr', 'corr', pair, frame);
      else if (e.fn === 'cov') v = w.window('cov', 'covarSample', pair, frame);
      else {
        const cov = w.window('cov', 'covarSample', pair, frame);
        const variance = w.window('var', 'varianceSample', R(rb), frame);
        v = w.ratio('beta', one(cov), variance);
      }
      return { column: w.ramped(v, ra, Math.max(size, 2)) };
    }
    case 'bollinger_bands': {
      const a = arg(0);
      const size = n('w');
      const k = float(n('k'));
      const mean = w.ramped(w.window('mean', 'average', R(a), Writer.frame(size)), a, size);
      const sd = w.std(a, size);
      return {
        lower: w.row('lower', op('minus', one(mean), op('times', k, one(sd)))),
        upper: w.row('upper', op('plus', one(mean), op('times', k, one(sd)))),
      };
    }
    case 'rsi': {
      const a = arg(0);
      const size = n('w');
      const d = w.row('d', op('minus', one(a), one(w.lag(a, 1))));
      const gain = w.row('gain', when(fn('isEmpty', col(d)), none(), when(fn('greaterThan', one(d), lit.integer(0)), one(d), float(0))));
      const loss = w.row('loss', when(fn('isEmpty', col(d)), none(), when(fn('lessThan', one(d), lit.integer(0)), op('minus', float(0), one(d)), float(0))));
      const up = w.window('up', 'average', R(gain), Writer.frame(size));
      const down = w.window('down', 'average', R(loss), Writer.frame(size));
      const rsi = w.row('rsi', when(fn('equal', one(down), lit.integer(0)), float(100),
        op('minus', float(100), fn('divide', float(100), op('plus', lit.integer(1), fn('divide', one(up), one(down)))))));
      return { column: w.ramped(rsi, gain, size) };
    }
    case 'log': {
      const a = arg(0);
      return { column: w.row('ln', when(fn('lessThanEqual', one(a), lit.integer(0)), none(), fn('log', one(a)))) };
    }
    case 'exp':
      return { column: w.row('exp', fn('exp', one(arg(0)))) };
    case 'sqrt': {
      const a = arg(0);
      return { column: w.row('sqrt', when(fn('lessThan', one(a), lit.integer(0)), none(), fn('sqrt', one(a)))) };
    }
    case 'abs':
      return { column: w.row('abs', fn('abs', one(arg(0)))) };
    case 'power':
      return { column: w.row('pow', fn('pow', one(arg(0)), float(n('y')))) };
    case 'floor': {
      const a = arg(0);
      const v = float(n('value'));
      return { column: w.row('floor', when(fn('lessThan', one(a), v), v, one(a))) };
    }
    case 'ceil': {
      const a = arg(0);
      const v = float(n('value'));
      return { column: w.row('ceil', when(fn('greaterThan', one(a), v), v, one(a))) };
    }
  }
}

/** A base's aggregate over a period's rows (`when`: only the rows it matches; `ord`: the row's number in time). */
function baseAggregate(name: string, b: Base, ord: string, cond: ValueSpecification | undefined): ColSpec {
  const only = (v: ValueSpecification): ValueSpecification => (cond === undefined ? v : when(cond, v, none()));
  const y = variable('y');
  switch (b.fn) {
    case 'last':
    case 'first':
      return agg(name, row(fn('rowMapper', only(col(b.column)), only(col(ord)))), lambda(['y'], fn(b.fn === 'last' ? 'maxBy' : 'minBy', y)));
    case 'average':
    case 'sum':
    case 'min':
    case 'max':
      return agg(name, row(only(col(b.column))), lambda(['y'], fn(b.fn, y)));
  }
}

/** The period a date is taken to at the tile's frequency, and the type the period has. */
function periodOf(ts: TimeseriesSpec, dateType: string | undefined): { readonly body: ValueSpecification | null; readonly type: string } {
  const grain = ts.grain;
  if (grain === undefined || (grain === 'day' && isDay(dateType))) return { body: null, type: dateType ?? 'StrictDate' };
  if (grain === 'day') return { body: fn('datePart', one(ts.date)), type: 'StrictDate' };
  return { body: fn(GRAIN_FUNCTION[grain], one(ts.date)), type: 'StrictDate' };
}

/**
 * A SERIES' RELATION, through its expression's columns: steps 1 to 5 (the file's header). `filter`: the grid's filter as
 * the chart reads it (chartFilter); `anchor`: the first date shown, where an index starts.
 */
function seriesRelation(s: CubeSnapshot, ts: TimeseriesSpec, series: TsSeries, filter: FilterNode | undefined,
  anchor: string | undefined): Built {
  const expr = inlined(series.expr, ts.series);
  const bases = basesOf(expr);
  if (bases.length === 0) throw new CubeRefusal(`series ${series.id} reads no column of the grid`);
  const typeOf = (c: string): string | undefined => typeIn(s, c);
  const dateType = typeOf(ts.date);
  let rel = sourceWithDerived(s);
  // 1. the grid's filter, the series' matches where every column it reads has the same, a date on every row
  const shared = bases.every((b) => JSON.stringify(b.where) === JSON.stringify(bases[0]!.where)) ? bases[0]!.where : null;
  const conditions: ValueSpecification[] = [fn('isNotEmpty', col(ts.date))];
  if (filter) conditions.push(filterNode(filter, 'x', typeOf));
  const sharedCondition = shared !== null ? matched(s, ts, shared) : undefined;
  if (sharedCondition) conditions.push(sharedCondition);
  rel = rel.filter(row(and(...conditions)));
  // 2. each row's period and its number in time; grouped by the split and the period
  const { body: periodBody, type: periodType } = periodOf(ts, dateType);
  const period = periodBody === null ? ts.date : '__ts_period';
  const ordUnit = isDay(dateType) ? 'DAYS' : 'SECONDS';
  const ord = '__ts_ord';
  rel = rel.extend([
    ...(periodBody !== null ? [derive(period, row(periodBody))] : []),
    derive(ord, row(fn('dateDiff', literalNode('1970-01-01', 'StrictDate'), one(ts.date), enumValue(DURATION, ordUnit)))),
  ]);
  const split = series.split;
  const part = split ?? `${WINDOW_ALL_PREFIX}ts`;
  const byName = new Map<string, string>();
  const aggs: ColSpec[] = bases.map((b, i) => {
    const name = `__ts_base_${i}`;
    byName.set(b.key, name);
    return baseAggregate(name, b, ord, shared !== null ? undefined : matched(s, ts, b.where));
  });
  aggs.push(agg(ord, row(col(ord)), lambda(['y'], fn('max', variable('y')))));
  // a period of a series of no instrument may hold several rows: counted, so the drawing can say so
  const counted = split === undefined && bases.some((b) => (b.fn === 'last' || b.fn === 'first') && b.where.length === 0)
    && ts.key !== undefined;
  if (counted) aggs.push(agg(TS_ROWS, row(lit.integer(1)), lambda(['y'], fn('count', variable('y')))));
  rel = rel.groupBy([...(split !== undefined ? [split] : []), period], aggs);
  if (split === undefined) rel = rel.extend([derive(part, row(lit.string('all')))]);
  // 3. the dates every column has; a split's first lines
  rel = rel.filter(row(and(...[...byName.values()].map(notEmpty))));
  if (split !== undefined) {
    const rank = `${WINDOW_ALL_PREFIX}ts_line`;
    rel = rel.extend([derive(rank, lambda(['p', 'w', 'r'], fn('denseRank', variable('p'), variable('w'), variable('r'))))],
      fn('over', collection([asc(split)])));
    rel = rel.filter(row(fn('lessThanEqual', col(rank), lit.integer(MAX_SPLIT_LINES))));
  }
  // 4 and 5. the expression, innermost first
  const w = new Writer(rel, { part, period, ord, ts, anchor, periodType });
  for (const [key, name] of byName) w.bases.set(key, name);
  const top = write(w, expr);
  let unit = unitOf(expr);
  let built: { value: string; lower?: string; upper?: string };
  if ('lower' in top) {
    built = { value: top.upper, lower: top.lower, upper: top.upper };
  } else {
    let value = columnOf(w, top, 'value');
    // compare: every LEVEL from one start -- a return, a volatility, a z-score drawn as it is
    const how = ts.view?.compare;
    if (how !== undefined && (unit === 'same' || unit === 'index')) {
      const base = w.anchored(value);
      value = how === 'rebase'
        ? w.row('cmp', when(fn('equal', one(base), lit.integer(0)), none(), op('times', fn('divide', one(value), one(base)), lit.integer(100))))
        : w.row('cmp', when(fn('equal', one(base), lit.integer(0)), none(), op('minus', fn('divide', one(value), one(base)), lit.integer(1))));
      unit = how === 'rebase' ? 'index' : 'ratio';
    }
    built = { value };
  }
  return {
    rel: w.rel, period, ord, ...built, ...(split !== undefined ? { split } : {}), unit, ...(counted ? { rows: TS_ROWS } : {}),
  };
}

/** The window a series is drawn over: its grid's filter as the chart reads it. */
export interface TsSource {
  readonly snapshot: CubeSnapshot;
  readonly filter: FilterNode | undefined;
}

/**
 * A SERIES' QUERY: its periods (and split values), latest first, each with its value -- or a band's lower and upper
 * -- at most TS_POINT_LIMIT of them.
 */
export function seriesQuery(src: TsSource, ts: TimeseriesSpec, series: TsSeries, view: TsWindow = {}): TsQuery {
  const b = seriesRelation(src.snapshot, ts, series, src.filter, view.from);
  const out: ColSpec[] = [
    derive(TS_DATE, row(col(b.period))),
    derive(TS_VALUE, row(col(b.value))),
    ...(b.lower !== undefined ? [derive(TS_LOWER, row(col(b.lower)))] : []),
    ...(b.upper !== undefined ? [derive(TS_UPPER, row(col(b.upper)))] : []),
    ...(b.split !== undefined ? [derive(TS_SPLIT, row(col(b.split)))] : []),
  ];
  const kept = [...out.map((c) => c.name), ...(b.rows !== undefined ? [b.rows] : [])];
  const rel = b.rel.extend(out).select(kept).sort([desc(TS_DATE)]).limit(TS_POINT_LIMIT + 1);
  return {
    series: series.id,
    query: rel.lambda(),
    unit: b.unit,
    band: b.lower !== undefined,
    ...(b.split !== undefined ? { split: b.split } : {}),
    counted: b.rows !== undefined,
  };
}

/**
 * THE DATES A TILE SPANS, and where each range button starts: the latest and the earliest date of the grid's rows (its
 * filter as the chart reads it), and each period back from the latest -- `adjust(latest, -1, YEARS)`, the year's first
 * day for YTD -- the DATABASE's date arithmetic, which the viewer only places its zoom at.
 */
export function boundsQuery(src: TsSource, ts: TimeseriesSpec): Lambda {
  const s = src.snapshot;
  const typeOf = (c: string): string | undefined => typeIn(s, c);
  const all = `${WINDOW_ALL_PREFIX}ts_bounds`;
  let rel = sourceWithDerived(s);
  const conditions: ValueSpecification[] = [fn('isNotEmpty', col(ts.date))];
  if (src.filter) conditions.push(filterNode(src.filter, 'x', typeOf));
  rel = rel.filter(row(and(...conditions)))
    .extend([derive(all, row(lit.string('all')))])
    .groupBy([all], [
      agg(TS_LATEST, row(col(ts.date)), lambda(['y'], fn('max', variable('y')))),
      agg(TS_EARLIEST, row(col(ts.date)), lambda(['y'], fn('min', variable('y')))),
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
 * A SERIES' STATISTICS over the dates shown (`view`: from, to; either open), per line: how many, the first and the last
 * date, the first and the last value and the change between them, the least and the most (and when), the mean and the
 * standard deviation; for a level, its annualized volatility (of its log returns, in percent), its deepest drawdown, its
 * compound annual growth and its Sharpe ratio (a risk-free rate of zero) -- every one the database's, over what is shown.
 */
export function statsQuery(src: TsSource, ts: TimeseriesSpec, series: TsSeries, view: TsWindow = {}): TsQuery {
  const b = seriesRelation(src.snapshot, ts, series, src.filter, view.from);
  const w = new Writer(b.rel, {
    part: b.split ?? `${WINDOW_ALL_PREFIX}ts`, period: b.period, ord: b.ord, ts, anchor: undefined,
    periodType: periodOf(ts, typeIn(src.snapshot, ts.date)).type,
  });
  const v = b.value;
  const dType = periodOf(ts, typeIn(src.snapshot, ts.date)).type;
  const shown: ValueSpecification[] = [fn('isNotEmpty', col(v))];
  if (view.from !== undefined) shown.push(fn('greaterThanEqual', one(b.period), literalNode(view.from, dType)));
  if (view.to !== undefined) shown.push(fn('lessThanEqual', one(b.period), literalNode(view.to, dType)));
  w.rel = w.rel.filter(row(and(...shown)));
  const level = b.unit === 'same' || b.unit === 'index';
  const rLog = level ? w.returns(v, 1, 'log') : undefined;
  const rSimple = level ? w.simple(v) : undefined;
  const peak = level ? w.window('peak', 'max', Writer.read(v), 'running') : undefined;
  const dd = peak !== undefined
    ? w.row('dd', when(fn('lessThanEqual', one(peak), lit.integer(0)), none(), op('minus', fn('divide', one(v), one(peak)), lit.integer(1))))
    : undefined;
  const y = (f: string): Lambda => lambda(['y'], fn(f, variable('y')));
  const S = statName;
  const aggs: ColSpec[] = [
    agg(S('count'), row(col(v)), y('count')),
    agg(S('from'), row(col(b.period)), y('min')),
    agg(S('to'), row(col(b.period)), y('max')),
    agg(S('first'), row(fn('rowMapper', col(v), col(b.ord))), y('minBy')),
    agg(S('last'), row(fn('rowMapper', col(v), col(b.ord))), y('maxBy')),
    agg(S('low'), row(col(v)), y('min')),
    agg(S('lowAt'), row(fn('rowMapper', col(b.period), col(v))), y('minBy')),
    agg(S('high'), row(col(v)), y('max')),
    agg(S('highAt'), row(fn('rowMapper', col(b.period), col(v))), y('maxBy')),
    agg(S('mean'), row(col(v)), y('average')),
    agg(S('std'), row(col(v)), y('stdDevSample')),
    ...(rLog !== undefined ? [agg('__ts_stat_logsd', row(col(rLog)), y('stdDevSample'))] : []),
    ...(rSimple !== undefined ? [
      agg('__ts_stat_rmean', row(col(rSimple)), y('average')),
      agg('__ts_stat_rsd', row(col(rSimple)), y('stdDevSample')),
    ] : []),
    ...(dd !== undefined ? [agg(S('mdd'), row(col(dd)), y('min'))] : []),
  ];
  const keys = b.split !== undefined ? [b.split] : [w.part];
  let rel = w.rel.groupBy(keys, aggs);
  const first = one(S('first'));
  const last = one(S('last'));
  const derived: ColSpec[] = [
    derive(S('change'), row(op('minus', last, first))),
    derive(S('pct'), row(when(fn('equal', first, lit.integer(0)), none(), op('minus', fn('divide', last, first), lit.integer(1))))),
  ];
  if (level) {
    const days = fn('dateDiff', one(S('from')), one(S('to')), enumValue(DURATION, 'DAYS'));
    const perYear = fn('sqrt', lit.integer(w.perYear));
    derived.push(
      derive(S('vol'), row(op('times', op('times', one('__ts_stat_logsd'), perYear), lit.integer(100)))),
      derive(S('cagr'), row(when(or(fn('lessThanEqual', days, lit.integer(0)), fn('lessThanEqual', first, lit.integer(0)),
        fn('lessThanEqual', last, lit.integer(0))), none(),
      op('minus', fn('pow', fn('divide', last, first), fn('divide', float(365.25), days)), lit.integer(1))))),
      derive(S('sharpe'), row(when(fn('equal', one('__ts_stat_rsd'), lit.integer(0)), none(),
        op('times', fn('divide', one('__ts_stat_rmean'), one('__ts_stat_rsd')), perYear)))),
    );
  }
  rel = rel.extend(derived);
  const kept = [
    ...(b.split !== undefined ? [b.split] : []),
    ...TS_STATS.filter((st) => level || !['vol', 'mdd', 'cagr', 'sharpe'].includes(st)).map(S),
  ];
  if (b.split !== undefined) rel = rel.extend([derive(TS_SPLIT, row(col(b.split)))]);
  rel = rel.select([...(b.split !== undefined ? [TS_SPLIT] : []), ...kept.filter((k) => k !== b.split)]);
  return {
    series: series.id,
    query: rel.lambda(),
    unit: b.unit,
    band: false,
    ...(b.split !== undefined ? { split: b.split } : {}),
    counted: false,
  };
}

/** Whether a series' expression reads the first date shown (an index, a change from the start, compare's rebasing). */
export function readsAnchor(ts: TimeseriesSpec, series: TsSeries): boolean {
  const walk = (e: TsExpr): boolean => e.kind === 'call'
    ? ['index', 'rebase', 'change', 'percent_change'].includes(e.fn) || e.args.some(walk)
    : e.kind === 'binary' ? walk(e.left) || walk(e.right) : false;
  let expr: TsExpr;
  try {
    expr = inlined(series.expr, ts.series);
  } catch {
    return false;
  }
  const unit = unitOf(expr);
  return walk(expr) || (ts.view?.compare !== undefined && !isBand(expr) && (unit === 'same' || unit === 'index'));
}

/** The type of the period column of a tile: what a date the viewer passes back is written as. */
export function periodTypeOf(s: CubeSnapshot, ts: TimeseriesSpec): string {
  return periodOf(ts, typeIn(s, ts.date)).type;
}
