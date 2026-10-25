// A TIMESERIES' QUERIES, PLANNED AND RUN (docs/DATACUBE_TIMESERIES_DESIGN_2026_10_25.md §4): each series of a
// timeseries tile through legend-lite's own planner, on a real DuckDB, its numbers read back and held to gs-quant's
// definitions -- written here, in the test, as plain arithmetic over the same prices (the reference a person can check
// by hand), never by the code under test.

import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { openDatabase } from '#test-host';

import { DuckDbEngine } from '../../engine-client/src/duckdb.ts';
import type { ResultTable } from '../../engine-client/src/result.ts';
import type { CubeSnapshot } from '../src/snapshot.ts';
import { ingestFile, type DuckDbFiles } from '../src/upload.ts';
import { plannerFor } from './catalog-builder.ts';
import type { LitePlanner } from '../src/lite-planner.ts';
import { parseExpr, type TimeseriesSpec, type TsSeries } from '../src/timeseries-spec.ts';
import {
  boundsQuery, rangeStartName, seriesQuery, seriesSources, statName, statsQuery, TS_DATE, TS_EARLIEST, TS_LATEST, TS_LOWER,
  TS_ROWS, TS_SPLIT, TS_UPPER, TS_VALUE, type TsInput, type TsInputs, type TsWindow,
} from '../src/timeseries-query.ts';
import { TAB_TABLES, openSources, type JoinOpening } from '../src/join-open.ts';
import type { FileSource } from '../src/cube-document.ts';
import { catalogColumns } from '../src/upload.ts';

// -- the prices: two instruments over 40 weekdays from 2024-01-01, and a third trading on fewer days --

function weekdays(n: number): string[] {
  const out: string[] = [];
  for (let d = new Date(Date.UTC(2024, 0, 1)); out.length < n; d = new Date(d.getTime() + 86400000)) {
    if (d.getUTCDay() !== 0 && d.getUTCDay() !== 6) out.push(d.toISOString().slice(0, 10));
  }
  return out;
}

const DAYS = weekdays(40);
const round = (v: number): number => Math.round(v * 100) / 100;
const AAA = DAYS.map((_, i) => round(100 * (1 + 0.03 * Math.sin(i / 2) + 0.004 * i)));
const BBB = DAYS.map((_, i) => round(50 * (1 + 0.02 * Math.cos(i / 1.7) + 0.002 * i + 0.01 * Math.sin(i / 3))));
// CCC trades on every third day only
const CCC = DAYS.map((_, i) => round(20 + i * 0.1 + (i % 4) * 0.3));
const VOL = DAYS.map((_, i) => 1000 + 37 * i);

const CSV = [
  'symbol,day,close,volume',
  ...DAYS.flatMap((d, i) => [
    `AAA,${d},${AAA[i]},${VOL[i]}`,
    `BBB,${d},${BBB[i]},${VOL[i]! * 2}`,
    ...(i % 3 === 0 ? [`CCC,${d},${CCC[i]},${VOL[i]! * 3}`] : []),
  ]),
].join('\n');

const writer = plannerFor('', 'local::RT');
let engine: DuckDbEngine;
let planner: LitePlanner;
let input: TsInput;
let inputs: TsInputs;

before(async () => {
  const db = await openDatabase();
  engine = new DuckDbEngine(db.connection);
  const files: DuckDbFiles = db.files;
  const r = await ingestFile(engine, files, new File([CSV], 'prices.csv'), writer);
  planner = plannerFor(r.model, r.runtime);
  const snapshot: CubeSnapshot = {
    source: { query: r.source },
    columns: [
      { name: 'symbol', type: 'String' },
      { name: 'day', type: 'StrictDate' },
      { name: 'close', type: 'Float' },
      { name: 'volume', type: 'Integer', kind: 'measure' },
    ],
    derived: [],
    rows: ['symbol'],
    pivotOn: [],
    sorts: [],
    epoch: 1,
  };
  input = { snapshot, filter: undefined };
  inputs = new Map([['px', input]]);
});

after(async () => {
  await engine?.close();
});

// -- the references: gs-quant's definitions, as arithmetic --

type Line = (number | null)[];
const lagged = (x: Line, n: number): Line => x.map((_, i) => (i >= n ? x[i - n]! : null));
const zip = (a: Line, b: Line, f: (p: number, q: number) => number | null): Line =>
  a.map((v, i) => (v === null || b[i] === null ? null : f(v, b[i]!)));
const simple = (x: Line): Line => zip(x, lagged(x, 1), (v, p) => v / p - 1);
/** The last `w` values ending at each one, once all w are there (gs-quant's ramp). */
const rolling = (x: Line, w: number, f: (vals: number[]) => number | null): Line => x.map((_, i) => {
  if (i < w - 1) return null;
  const vals = x.slice(i - w + 1, i + 1);
  if (vals.some((v) => v === null)) return null;
  return f(vals as number[]);
});
const avg = (v: number[]): number => v.reduce((a, b) => a + b, 0) / v.length;
const sd = (v: number[]): number => {
  const m = avg(v);
  return Math.sqrt(v.reduce((a, b) => a + (b - m) ** 2, 0) / (v.length - 1));
};
const cov = (a: number[], b: number[]): number => {
  const ma = avg(a);
  const mb = avg(b);
  return a.reduce((s, v, i) => s + (v - ma) * (b[i]! - mb), 0) / (a.length - 1);
};
const pairs = (a: Line, b: Line, w: number, f: (p: number[], q: number[]) => number): Line => a.map((_, i) => {
  if (i < w - 1) return null;
  const p = a.slice(i - w + 1, i + 1);
  const q = b.slice(i - w + 1, i + 1);
  if ([...p, ...q].some((v) => v === null)) return null;
  return f(p as number[], q as number[]);
});

// -- running a series --

const TS: TimeseriesSpec = { sources: [{ alias: 'px', cube: 'prices', date: 'day', key: 'symbol' }], series: [] };

function spec(exprs: Record<string, string>, more: Partial<TimeseriesSpec> = {}, split?: string): TimeseriesSpec {
  return {
    ...TS,
    ...more,
    series: Object.entries(exprs).map(([id, text]): TsSeries => ({ id, expr: parseExpr(text), ...(split ? { split } : {}) })),
  };
}

async function run(query: { query: unknown }): Promise<ResultTable> {
  return engine.execute(await planner.plan(query.query as Parameters<LitePlanner['plan']>[0]), 0);
}

function values(t: ResultTable, name: string): unknown[] {
  const c = t.columns.find((x) => x.name === name);
  assert.ok(c, `the answer has a column '${name}' (it has ${t.columns.map((x) => x.name).join(', ')})`);
  return c.values.map((v) => (typeof v === 'bigint' ? Number(v) : v));
}

const num = (v: unknown): number | null => (v === null || v === undefined ? null : Number(v));

/** A series' line, oldest first (the query answers latest first), one per split value. */
async function line(ts: TimeseriesSpec, id = 'A', view: TsWindow = {}): Promise<{ dates: string[]; values: Line; lower?: Line; upper?: Line; split?: unknown[] }> {
  const series = ts.series.find((s) => s.id === id)!;
  const q = seriesQuery(inputs, ts, series, view);
  const t = await run(q);
  const dates = values(t, TS_DATE).map((d) => String(d).slice(0, 10)).reverse();
  const out = { dates, values: values(t, TS_VALUE).map(num).reverse() };
  return {
    ...out,
    ...(q.band ? { lower: values(t, TS_LOWER).map(num).reverse(), upper: values(t, TS_UPPER).map(num).reverse() } : {}),
    ...(q.split !== undefined ? { split: values(t, TS_SPLIT).reverse() } : {}),
  };
}

function close(got: Line, want: Line, what: string, tolerance = 1e-9): void {
  assert.equal(got.length, want.length, `${what}: ${got.length} points, not ${want.length}`);
  for (let i = 0; i < want.length; i++) {
    const g = got[i] ?? null;
    const w = want[i] ?? null;
    if (w === null || g === null) {
      assert.equal(g, w, `${what} at ${i}: ${g} where ${w} was meant`);
      continue;
    }
    assert.ok(Math.abs(g - w) <= tolerance * Math.max(1, Math.abs(w)), `${what} at ${i}: ${g}, not ${w}`);
  }
}

describe('a column along the dates', () => {
  it('close[AAA]: one point a day, its price', async () => {
    const l = await line(spec({ A: 'close[AAA]' }));
    assert.deepEqual(l.dates, DAYS);
    close(l.values, AAA, 'close[AAA]');
  });

  it('split by symbol: a line per instrument, each its own', async () => {
    const l = await line(spec({ A: 'close' }, {}, 'symbol'));
    const of = (sym: string): Line => l.values.filter((_, i) => l.split![i] === sym);
    close(of('AAA'), AAA, 'AAA');
    close(of('BBB'), BBB, 'BBB');
    close(of('CCC'), CCC.filter((_, i) => i % 3 === 0), 'CCC');
  });

  it('a ratio of two instruments, on the dates both have (gs-quant\'s intersect)', async () => {
    const l = await line(spec({ A: 'close[AAA] / close[CCC]' }));
    assert.deepEqual(l.dates, DAYS.filter((_, i) => i % 3 === 0));
    close(l.values, AAA.map((v, i) => v / CCC[i]!).filter((_, i) => i % 3 === 0), 'AAA / CCC');
  });

  it('arithmetic with numbers: a spread, a scale', async () => {
    const l = await line(spec({ A: '(close[AAA] - close[BBB]) * 2 + 1' }));
    close(l.values, AAA.map((v, i) => (v - BBB[i]!) * 2 + 1), 'spread');
  });

  it('a monthly frequency: each month\'s last price, and its volume summed', async () => {
    const months = [...new Set(DAYS.map((d) => d.slice(0, 7)))];
    const last = months.map((m) => AAA[DAYS.map((d) => d.slice(0, 7)).lastIndexOf(m)]!);
    const l = await line(spec({ A: 'close[AAA]' }, { grain: 'month' }));
    assert.deepEqual(l.dates, months.map((m) => `${m}-01`));
    close(l.values, last, 'month-end');
    const v = await line(spec({ A: 'volume[AAA].sum' }, { grain: 'month' }));
    close(v.values, months.map((m) => DAYS.reduce((s, d, i) => s + (d.startsWith(m) ? VOL[i]! : 0), 0)), 'volume');
    const f = await line(spec({ A: 'close[AAA].first' }, { grain: 'month' }));
    close(f.values, months.map((m) => AAA[DAYS.findIndex((d) => d.startsWith(m))]!), 'month-start');
  });

  it('the grid\'s filter narrows every series', async () => {
    const filtered: TsInputs = new Map([['px', { ...input, filter: { kind: 'condition', column: 'day', operator: 'greaterThanEqual', value: DAYS[30]! } }]]);
    const series = spec({ A: 'close[AAA]' }).series[0]!;
    const t = await run(seriesQuery(filtered, spec({ A: 'close[AAA]' }), series));
    assert.equal(t.rowCount, 10);
  });
});

describe('gs-quant\'s functions, as the database computes them', () => {
  it('returns: simple, log and absolute, over one and over five observations', async () => {
    close((await line(spec({ A: 'returns(close[AAA])' }))).values, simple(AAA), 'simple');
    close((await line(spec({ A: 'returns(close[AAA], 5)' }))).values, zip(AAA, lagged(AAA, 5), (v, p) => v / p - 1), 'simple 5');
    close((await line(spec({ A: 'returns(close[AAA], 1, log)' }))).values, zip(AAA, lagged(AAA, 1), (v, p) => Math.log(v) - Math.log(p)), 'log');
    close((await line(spec({ A: 'returns(close[AAA], type=absolute)' }))).values, zip(AAA, lagged(AAA, 1), (v, p) => v - p), 'absolute');
  });

  it('diff and lag', async () => {
    close((await line(spec({ A: 'diff(close[AAA], 2)' }))).values, zip(AAA, lagged(AAA, 2), (v, p) => v - p), 'diff');
    close((await line(spec({ A: 'lag(close[AAA], 3)' }))).values, lagged(AAA, 3), 'lag');
  });

  it('rolling mean, sum, min, max, range, standard deviation and variance: drawn once the window is full', async () => {
    close((await line(spec({ A: 'mean(close[AAA], 5)' }))).values, rolling(AAA, 5, avg), 'mean');
    close((await line(spec({ A: 'moving_average(close[AAA], 5)' }))).values, rolling(AAA, 5, avg), 'moving_average');
    close((await line(spec({ A: 'sum(close[AAA], 4)' }))).values, rolling(AAA, 4, (v) => v.reduce((a, b) => a + b, 0)), 'sum');
    close((await line(spec({ A: 'min(close[AAA], 4)' }))).values, rolling(AAA, 4, (v) => Math.min(...v)), 'min');
    close((await line(spec({ A: 'max(close[AAA], 4)' }))).values, rolling(AAA, 4, (v) => Math.max(...v)), 'max');
    close((await line(spec({ A: 'range(close[AAA], 4)' }))).values, rolling(AAA, 4, (v) => Math.max(...v) - Math.min(...v)), 'range');
    close((await line(spec({ A: 'std(close[AAA], 6)' }))).values, rolling(AAA, 6, sd), 'std', 1e-7);
    close((await line(spec({ A: 'var(close[AAA], 6)' }))).values, rolling(AAA, 6, (v) => sd(v) ** 2), 'var', 1e-7);
  });

  it('from the start (w=0): a running total, a running mean', async () => {
    let acc = 0;
    close((await line(spec({ A: 'sum(close[AAA])' }))).values, AAA.map((v) => (acc += v)), 'cumsum', 1e-7);
    close((await line(spec({ A: 'mean(close[AAA], 0)' }))).values, AAA.map((_, i) => avg(AAA.slice(0, i + 1))), 'expanding mean', 1e-7);
  });

  it('z-scores over a window, and over every observation', async () => {
    const want = AAA.map((v, i) => (i < 9 ? null : (v - avg(AAA.slice(i - 9, i + 1))) / sd(AAA.slice(i - 9, i + 1))));
    close((await line(spec({ A: 'zscores(close[AAA], 10)' }))).values, want, 'rolling z', 1e-7);
    const m = avg(AAA);
    const s = sd(AAA);
    close((await line(spec({ A: 'zscores(close[AAA])' }))).values, AAA.map((v) => (v - m) / s), 'z', 1e-7);
  });

  it('volatility: the annualized standard deviation of returns, in percent (gs-quant: 20% is 20)', async () => {
    const r = simple(AAA);
    const want = r.map((_, i) => (i < 10 ? null : sd(r.slice(i - 9, i + 1) as number[]) * Math.sqrt(252) * 100));
    close((await line(spec({ A: 'volatility(close[AAA], 10)' }))).values, want, 'vol', 1e-7);
    close((await line(spec({ A: 'vol(close[AAA], 10)' }))).values, want, 'vol (alias)', 1e-7);
    const lr = zip(AAA, lagged(AAA, 1), (v, p) => Math.log(v / p));
    const logWant = lr.map((_, i) => (i < 10 ? null : sd(lr.slice(i - 9, i + 1) as number[]) * Math.sqrt(252) * 100));
    close((await line(spec({ A: 'volatility(close[AAA], 10, log)' }))).values, logWant, 'log vol', 1e-7);
  });

  it('volatility at a monthly frequency annualizes by twelve', async () => {
    const months = [...new Set(DAYS.map((d) => d.slice(0, 7)))];
    const last = months.map((m) => AAA[DAYS.map((d) => d.slice(0, 7)).lastIndexOf(m)]!);
    const r = simple(last);
    const want = r.map((_, i) => (i < 2 ? null : sd(r.slice(i - 1, i + 1) as number[]) * Math.sqrt(12) * 100));
    close((await line(spec({ A: 'volatility(close[AAA], 2)' }, { grain: 'month' }))).values, want, 'monthly vol', 1e-7);
  });

  it('drawdown from the running peak, and the deepest one over a window', async () => {
    let peak = -Infinity;
    const dd = AAA.map((v) => {
      peak = Math.max(peak, v);
      return v / peak - 1;
    });
    close((await line(spec({ A: 'drawdown(close[AAA])' }))).values, dd, 'drawdown', 1e-9);
    let worst = Infinity;
    close((await line(spec({ A: 'max_drawdown(close[AAA])' }))).values, dd.map((d) => (worst = Math.min(worst, d))), 'max drawdown', 1e-9);
    const rolledPeak = AAA.map((_, i) => Math.max(...AAA.slice(Math.max(0, i - 4), i + 1)));
    const rolledDd = AAA.map((v, i) => v / rolledPeak[i]! - 1);
    close((await line(spec({ A: 'max_drawdown(close[AAA], 5)' }))).values,
      rolledDd.map((_, i) => (i < 4 ? null : Math.min(...rolledDd.slice(i - 4, i + 1)))), 'rolling max drawdown', 1e-9);
  });

  it('correlation, beta and covariance of two instruments\' returns', async () => {
    const ra = simple(AAA);
    const rb = simple(BBB);
    const corr = pairs(ra, rb, 8, (p, q) => cov(p, q) / (sd(p) * sd(q)));
    close((await line(spec({ A: 'correlation(close[AAA], close[BBB], 8)' }))).values, corr, 'corr', 1e-7);
    const beta = pairs(ra, rb, 8, (p, q) => cov(p, q) / sd(q) ** 2);
    close((await line(spec({ A: 'beta(close[AAA], close[BBB], 8)' }))).values, beta, 'beta', 1e-7);
    const c = pairs(AAA, BBB, 8, (p, q) => cov(p, q));
    close((await line(spec({ A: 'cov(close[AAA], close[BBB], 8)' }))).values, c, 'cov', 1e-7);
  });

  it('a series read by another: $A', async () => {
    const ts = spec({ A: 'close[AAA]', B: 'returns($A)', C: 'volatility($A, 10) - volatility(close[BBB], 10)' });
    close((await line(ts, 'B')).values, simple(AAA), '$A');
    const ra = simple(AAA);
    const rb = simple(BBB);
    const want = ra.map((_, i) => (i < 10 ? null : (sd(ra.slice(i - 9, i + 1) as number[]) - sd(rb.slice(i - 9, i + 1) as number[])) * Math.sqrt(252) * 100));
    close((await line(ts, 'C')).values, want, 'vol spread', 1e-7);
  });

  it('Bollinger bands: k deviations either side of the moving average', async () => {
    const l = await line(spec({ A: 'bollinger_bands(close[AAA], 5, 2)' }));
    close(l.lower!, rolling(AAA, 5, (v) => avg(v) - 2 * sd(v)), 'lower', 1e-7);
    close(l.upper!, rolling(AAA, 5, (v) => avg(v) + 2 * sd(v)), 'upper', 1e-7);
  });

  it('RSI of simple averages of the gains and the losses', async () => {
    const d = zip(AAA, lagged(AAA, 1), (v, p) => v - p);
    const want = d.map((_, i) => {
      if (i < 6) return null;
      const win = d.slice(i - 5, i + 1) as number[];
      const up = avg(win.map((v) => Math.max(v, 0)));
      const down = avg(win.map((v) => Math.max(-v, 0)));
      return down === 0 ? 100 : 100 - 100 / (1 + up / down);
    });
    close((await line(spec({ A: 'rsi(close[AAA], 6)' }))).values, want, 'rsi', 1e-7);
  });

  it('prices from returns compound them back', async () => {
    const r = simple(AAA);
    let level = 1;
    const want = r.map((v) => (v === null ? null : (level *= 1 + v)));
    close((await line(spec({ A: 'prices(returns(close[AAA]))' }))).values, want, 'prices', 1e-7);
  });

  it('math: log, sqrt, abs, power, a floor and a cap', async () => {
    close((await line(spec({ A: 'log(close[AAA])' }))).values, AAA.map(Math.log), 'log', 1e-9);
    close((await line(spec({ A: 'sqrt(close[AAA])' }))).values, AAA.map(Math.sqrt), 'sqrt', 1e-9);
    close((await line(spec({ A: 'abs(close[AAA] - 105)' }))).values, AAA.map((v) => Math.abs(v - 105)), 'abs', 1e-9);
    close((await line(spec({ A: 'power(close[AAA], 2)' }))).values, AAA.map((v) => v * v), 'power', 1e-9);
    close((await line(spec({ A: 'floor(close[AAA], 104)' }))).values, AAA.map((v) => Math.max(v, 104)), 'floor', 1e-9);
    close((await line(spec({ A: 'ceil(close[AAA], 104)' }))).values, AAA.map((v) => Math.min(v, 104)), 'ceil', 1e-9);
  });
});

describe('from the first date shown', () => {
  it('rebase: 100 at the first date, or at the first date shown', async () => {
    const ts = spec({ A: 'rebase(close[AAA])' });
    close((await line(ts)).values, AAA.map((v) => (v / AAA[0]!) * 100), 'from the start', 1e-9);
    close((await line(ts, 'A', { from: DAYS[10]! })).values, AAA.map((v) => (v / AAA[10]!) * 100), 'from day 10', 1e-9);
  });

  it('the first date shown with no value takes the next one that has it', async () => {
    const ts = spec({ A: 'index(close[CCC], 1)' });
    // CCC trades on days 0, 3, 6, ...: the view from day 4 starts at day 6
    const l = await line(ts, 'A', { from: DAYS[4]! });
    const ccc = CCC.filter((_, i) => i % 3 === 0);
    close(l.values, ccc.map((v) => v / CCC[6]!), 'from day 6', 1e-9);
  });

  it('change and percent change from the start', async () => {
    close((await line(spec({ A: 'change(close[AAA])' }), 'A', { from: DAYS[5]! })).values, AAA.map((v) => v - AAA[5]!), 'change', 1e-9);
    close((await line(spec({ A: 'percent_change(close[AAA])' }), 'A', { from: DAYS[5]! })).values, AAA.map((v) => v / AAA[5]! - 1), '%', 1e-9);
  });

  it('compare: every level rebased, a return left as it is', async () => {
    const ts = spec({ A: 'close', B: 'returns(close)' }, { view: { compare: 'rebase' } }, 'symbol');
    const l = await line(ts, 'A', { from: DAYS[2]! });
    close(l.values.filter((_, i) => l.split![i] === 'BBB'), BBB.map((v) => (v / BBB[2]!) * 100), 'BBB rebased', 1e-9);
    const r = await line(ts, 'B', { from: DAYS[2]! });
    close(r.values.filter((_, i) => r.split![i] === 'AAA'), simple(AAA), 'a return stays');
    const pct = await line({ ...ts, view: { compare: 'percent' } }, 'A');
    close(pct.values.filter((_, i) => pct.split![i] === 'AAA'), AAA.map((v) => v / AAA[0]! - 1), 'percent', 1e-9);
  });
});

describe('a series of no instrument says how many rows each date had', () => {
  it('close with no [AAA] and no split: each date three rows or two, counted', async () => {
    const q = seriesQuery(inputs, spec({ A: 'close' }), spec({ A: 'close' }).series[0]!);
    assert.equal(q.counted, true);
    const t = await run(q);
    const rows = values(t, TS_ROWS).map(Number);
    assert.ok(rows.every((n) => n === 2 || n === 3), rows.join(','));
  });
});

describe('the tile\'s bounds: its latest date and where each range button starts, the database\'s', () => {
  it('the latest, the earliest, a month and a year back, the year\'s start', async () => {
    const t = await run({ query: boundsQuery(input, TS.sources[0]!) });
    const at = (n: string): string => String(values(t, n)[0]).slice(0, 10);
    assert.equal(at(TS_LATEST), DAYS[DAYS.length - 1]);
    assert.equal(at(TS_EARLIEST), DAYS[0]);
    assert.equal(at(rangeStartName('1m')), '2024-01-23');
    assert.equal(at(rangeStartName('1y')), '2023-02-23');
    assert.equal(at(rangeStartName('ytd')), '2024-01-01');
  });
});

describe('a series\' statistics over the dates shown', () => {
  it('first, last, change, extremes and when, mean, deviation, volatility, drawdown, growth and Sharpe', async () => {
    const ts = spec({ A: 'close[AAA]' });
    const view = { from: DAYS[5]!, to: DAYS[34]! };
    const t = await run(statsQuery(inputs, ts, ts.series[0]!, view));
    const v = (s: Parameters<typeof statName>[0]): unknown => values(t, statName(s))[0];
    const shown = AAA.slice(5, 35);
    assert.equal(Number(v('count')), 30);
    assert.equal(String(v('from')).slice(0, 10), DAYS[5]);
    assert.equal(String(v('to')).slice(0, 10), DAYS[34]);
    close([num(v('first')), num(v('last'))], [shown[0]!, shown[29]!], 'ends');
    close([num(v('change'))], [shown[29]! - shown[0]!], 'change', 1e-9);
    close([num(v('pct'))], [shown[29]! / shown[0]! - 1], 'pct', 1e-9);
    close([num(v('low')), num(v('high'))], [Math.min(...shown), Math.max(...shown)], 'extremes');
    assert.equal(String(v('highAt')).slice(0, 10), DAYS[5 + shown.indexOf(Math.max(...shown))]);
    close([num(v('mean')), num(v('std'))], [avg(shown), sd(shown)], 'moments', 1e-9);
    const lr = shown.slice(1).map((x, i) => Math.log(x / shown[i]!));
    close([num(v('vol'))], [sd(lr) * Math.sqrt(252) * 100], 'vol', 1e-7);
    let peak = -Infinity;
    close([num(v('mdd'))], [Math.min(...shown.map((x) => {
      peak = Math.max(peak, x);
      return x / peak - 1;
    }))], 'mdd', 1e-9);
    const days = (Date.parse(DAYS[34]!) - Date.parse(DAYS[5]!)) / 86400000;
    close([num(v('cagr'))], [(shown[29]! / shown[0]!) ** (365.25 / days) - 1], 'cagr', 1e-7);
    const r = shown.slice(1).map((x, i) => x / shown[i]! - 1);
    close([num(v('sharpe'))], [(avg(r) / sd(r)) * Math.sqrt(252)], 'sharpe', 1e-7);
  });

  it('a return\'s statistics leave out what only a level has', async () => {
    const ts = spec({ A: 'returns(close[AAA])' });
    const t = await run(statsQuery(inputs, ts, ts.series[0]!));
    assert.ok(!t.columns.some((c) => c.name === statName('vol')), 'no volatility of a return');
    assert.equal(Number(values(t, statName('count'))[0]), 39);
  });

  it('per line of a split series', async () => {
    const ts = spec({ A: 'close' }, {}, 'symbol');
    const t = await run(statsQuery(inputs, ts, ts.series[0]!));
    assert.deepEqual(values(t, TS_SPLIT).map(String).sort(), ['AAA', 'BBB', 'CCC']);
  });
});

// -- a series combining grids (D2): their tables over one model, each grouped by its own date, joined on the period --

describe('a series combining two grids, planned over one model of their tables (§5.2)', () => {
  // prices by weekday; a rates grid of its own, its own date and instrument columns, on fewer days and one more
  const RATE_DAYS = DAYS.filter((_, i) => i % 2 === 0);
  const TEN = RATE_DAYS.map((_, i) => round(4 + 0.05 * Math.sin(i) - 0.002 * i));
  const TWO = RATE_DAYS.map((_, i) => round(4.5 - 0.004 * i));
  let joint: TsInputs;
  let jointPlanner: LitePlanner;
  const file = (name: string, columns: [string, string][]): FileSource => ({
    _type: 'file', name: `${name}.csv`, format: 'csv', size: 1, sha256: name.padEnd(64, '0'),
    columns: columns.map(([n, type]) => ({ name: n, type })),
  });
  const PX = file('px_t', [['symbol', 'String'], ['day', 'StrictDate'], ['close', 'Float']]);
  const RATES = file('rates_t', [['tenor', 'String'], ['obs_date', 'StrictDate'], ['yield', 'Float']]);
  const COMBINED: TimeseriesSpec = {
    sources: [
      { alias: 'px', cube: 'grid-1', date: 'day', key: 'symbol' },
      { alias: 'rates', cube: 'grid-2', date: 'obs_date', key: 'tenor' },
    ],
    series: [],
  };
  const combined = (exprs: Record<string, string>, split?: string): TimeseriesSpec => ({
    ...COMBINED,
    series: Object.entries(exprs).map(([id, text]): TsSeries => ({ id, expr: parseExpr(text), ...(split ? { split } : {}) })),
  });
  const joinedLine = async (ts: TimeseriesSpec, id = 'A'): Promise<{ dates: string[]; values: Line; split?: unknown[] }> => {
    const series = ts.series.find((x) => x.id === id)!;
    const q = seriesQuery(joint, ts, series);
    const t = await engine.execute(await jointPlanner.plan(q.query), 0);
    return {
      dates: values(t, TS_DATE).map((d) => String(d).slice(0, 10)).reverse(),
      values: values(t, TS_VALUE).map(num).reverse(),
      ...(q.split !== undefined ? { split: values(t, TS_SPLIT).reverse() } : {}),
    };
  };

  before(async () => {
    await engine.run('CREATE TABLE px_t (symbol VARCHAR, day DATE, close DOUBLE)', 0);
    await engine.run(`INSERT INTO px_t VALUES ${DAYS.flatMap((d, i) => [`('AAA', '${d}', ${AAA[i]})`, `('BBB', '${d}', ${BBB[i]})`]).join(', ')}`, 0);
    await engine.run('CREATE TABLE rates_t (tenor VARCHAR, obs_date DATE, yield DOUBLE)', 0);
    await engine.run(`INSERT INTO rates_t VALUES ${RATE_DAYS.flatMap((d, i) => [`('10Y', '${d}', ${TEN[i]})`, `('2Y', '${d}', ${TWO[i]})`]).join(', ')}, ('10Y', '2024-12-31', 9)`, 0);
    const models = plannerFor('', 'local::RT');
    const opening: JoinOpening = {
      here: TAB_TABLES, tables: ['px_t', 'rates_t'], models, databaseType: 'DuckDB',
      catalog: (table) => catalogColumns(engine, models, table),
      typer: (model, runtime) => plannerFor(model, runtime),
    };
    const opened = await openSources([PX, RATES], opening);
    jointPlanner = plannerFor(opened.model, opened.runtime);
    const grid = (query: (typeof opened.relations)[number], f: FileSource): TsInput => ({
      snapshot: {
        source: { query }, columns: f.columns.map((c) => ({ name: c.name, type: c.type })), derived: [], rows: [], pivotOn: [], sorts: [], epoch: 1,
      },
      filter: undefined,
    });
    joint = new Map([['px', grid(opened.relations[0]!, PX)], ['rates', grid(opened.relations[1]!, RATES)]]);
  });

  it('reads both sources, the first first', () => {
    const ts = combined({ A: 'px:close[AAA] / rates:yield[10Y]', B: 'rates:yield[2Y]' });
    assert.deepEqual(seriesSources(ts, ts.series[0]!), ['px', 'rates']);
    assert.deepEqual(seriesSources(ts, ts.series[1]!), ['rates']);
  });

  it('a ratio across grids, on the dates both have', async () => {
    const l = await joinedLine(combined({ A: 'px:close[AAA] / rates:yield[10Y]' }));
    assert.deepEqual(l.dates, RATE_DAYS);
    close(l.values, RATE_DAYS.map((d, i) => AAA[DAYS.indexOf(d)]! / TEN[i]!), 'AAA / 10Y', 1e-9);
  });

  it('a series of the second grid alone, over the joint model, is its own', async () => {
    const l = await joinedLine(combined({ A: 'rates:yield[2Y]' }));
    assert.deepEqual(l.dates, RATE_DAYS);
    close(l.values, TWO, '2Y');
  });

  it('a rolling correlation of two grids\' returns, after the join', async () => {
    const a = RATE_DAYS.map((d) => AAA[DAYS.indexOf(d)]!);
    const corr = pairs(simple(a), simple(TEN), 5, (p, q) => cov(p, q) / (sd(p) * sd(q)));
    close((await joinedLine(combined({ A: 'correlation(px:close[AAA], rates:yield[10Y], 5)' }))).values, corr, 'corr', 1e-7);
  });

  it('split by the first grid\'s instrument, each line against the second grid\'s one series', async () => {
    const l = await joinedLine(combined({ A: 'px:close - rates:yield[10Y]' }, 'symbol'));
    const of = (sym: string): Line => l.values.filter((_, i) => l.split![i] === sym);
    close(of('AAA'), RATE_DAYS.map((d, i) => AAA[DAYS.indexOf(d)]! - TEN[i]!), 'AAA - 10Y', 1e-9);
    close(of('BBB'), RATE_DAYS.map((d, i) => BBB[DAYS.indexOf(d)]! - TEN[i]!), 'BBB - 10Y', 1e-9);
  });

  it('at a monthly frequency, each grid\'s month-end, joined by month', async () => {
    const months = [...new Set(RATE_DAYS.map((d) => d.slice(0, 7)))];
    const lastOf = (dates: readonly string[], vals: readonly number[], m: string): number =>
      vals[dates.map((d) => d.slice(0, 7)).lastIndexOf(m)]!;
    const l = await joinedLine({ ...combined({ A: 'px:close[BBB] / rates:yield[10Y]' }), grain: 'month' });
    assert.deepEqual(l.dates, months.map((m) => `${m}-01`));
    close(l.values, months.map((m) => lastOf(DAYS, BBB, m) / lastOf(RATE_DAYS, TEN, m)), 'monthly ratio', 1e-9);
  });
});
