// THE TIMESERIES OVER THE TRADING MODEL'S CLASSES (docs/DATACUBE_TIMESERIES_DESIGN_2026_10_25.md §11, Phase 6; the user,
// 2026-10-27: "start all the charts from Class models projected to Relation ... the same Stress data model"): the
// markets sample's days in the stress corpus's own tables (stress-markets.ts), read through its classes by
// stress::AllMapping -- `marketdata::MarketQuote.all()->project(~[ticker: x|$x.instrument.ticker, ...])` -- and each
// series of a tile over them held to the same series over the sample's one table: the class is a source as a table
// is, every figure the database's. The one trading model is the Deephaven line's text (//scripts/corpus:trading_model).

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, before, describe, it } from 'node:test';
import { openDatabase } from '#test-host';

import { DuckDbEngine } from '../../engine-client/src/duckdb.ts';
import { runfileFromEnv } from '../../tools/js/runfiles.mts';
import { derive, element, fn, lambda, lit, projectObjects, property, variable, type ValueSpecification } from '../../pure-protocol/src/index.ts';
import type { LitePlanner } from '../src/lite-planner.ts';
import { marketsCsv } from '../src/samples.ts';
import type { CubeSnapshot } from '../src/snapshot.ts';
import { STRESS_MARKET_COLUMNS, STRESS_MARKET_TABLES, stressMarketsData } from '../src/stress-markets.ts';
import { parseExpr } from '../src/timeseries-expr.ts';
import { seriesQuery, TS_DATE, TS_SPLIT, TS_VALUE, type TsInputs } from '../src/timeseries-query.ts';
import type { TimeseriesSpec } from '../src/timeseries-spec.ts';
import { ingestFile, type DuckDbFiles } from '../src/upload.ts';
import { plannerFor } from './catalog-builder.ts';

const MODEL = readFileSync(runfileFromEnv('TRADING_MODEL'), 'utf8');
const writer = plannerFor('', 'local::RT');

/** The model a page holds: the trading model and the markets sample's rows as its Data (a project's own). */
const PROJECT = `${MODEL}\n${stressMarketsData()}`;

/** The table's database (the markets sample read as one file) and the project's own (its Data's), each its own. */
let engine: DuckDbEngine;
let projectEngine: DuckDbEngine;
let files: DuckDbFiles;
let corpus: LitePlanner;
let table: LitePlanner;
let classes: TsInputs;
let referenced: TsInputs;
let rows: TsInputs;
/** How long the trading model took to be held, and its first query to plan: what a page waits for. */
const measured: Record<string, number> = {};

/** `$x.a.b...`: a path of properties from the row. */
const path = (...names: string[]): ValueSpecification => names.reduce<ValueSpecification>((v, n) => property(v, n), variable('x'));

/** A quote a day through the corpus's classes: its instrument's ticker, its time, its close. */
const QUOTES = projectObjects(fn('getAll', element('marketdata::MarketQuote')), [
  derive('ticker', lambda(['x'], path('instrument', 'ticker'))),
  derive('quoteTime', lambda(['x'], path('quoteTime'))),
  derive('closePrice', lambda(['x'], path('closePrice'))),
]).node;

/** A quote a day with its instrument's reference data, through the associations: its sector's name and its listing's
 *  exchange's (cross::Instrument_Sector, cross::Instrument_Exchange), and the day's volume. */
const REFERENCED = projectObjects(fn('getAll', element('marketdata::MarketQuote')), [
  derive('ticker', lambda(['x'], path('instrument', 'ticker'))),
  derive('sector', lambda(['x'], path('instrument', 'sector', 'name'))),
  derive('exchange', lambda(['x'], path('instrument', 'exchange', 'name'))),
  derive('quoteTime', lambda(['x'], path('quoteTime'))),
  derive('closePrice', lambda(['x'], path('closePrice'))),
  derive('volume', lambda(['x'], path('volume'))),
]).node;

const snapshotOf = (query: ValueSpecification, columns: CubeSnapshot['columns']): CubeSnapshot => ({
  source: { query }, columns, derived: [], rows: [], pivotOn: [], sorts: [], epoch: 1,
});

before(async () => {
  const db = await openDatabase();
  engine = new DuckDbEngine(db.connection);
  files = db.files;
  const flat = await ingestFile(engine, files, new File([marketsCsv()], 'prices.csv'), writer, { table: 'prices' });
  table = plannerFor(flat.model, flat.runtime);
  // the project's rows, its Data element's, in a database of its own as a page makes it (C5, project-data.ts): each
  // table made by the store's declaration
  let t0 = performance.now();
  corpus = plannerFor(PROJECT, 'stress::RT');
  projectEngine = new DuckDbEngine((await openDatabase()).connection);
  const project = await corpus.projectData(PROJECT, projectEngine.databaseType);
  for (const sql of [...project.open, ...project.use, ...project.fill]) await projectEngine.run(sql, 0);
  measured['the model held and its Data filled (ms)'] = performance.now() - t0;
  classes = new Map([['px', { snapshot: snapshotOf(QUOTES, [
    { name: 'ticker', type: 'String' }, { name: 'quoteTime', type: 'DateTime' }, { name: 'closePrice', type: 'Float' },
  ]), filter: undefined }]]);
  referenced = new Map([['px', { snapshot: snapshotOf(REFERENCED, [
    { name: 'ticker', type: 'String' }, { name: 'sector', type: 'String' }, { name: 'exchange', type: 'String' },
    { name: 'quoteTime', type: 'DateTime' }, { name: 'closePrice', type: 'Float' }, { name: 'volume', type: 'Float' },
  ]), filter: undefined }]]);
  rows = new Map([['px', { snapshot: snapshotOf(flat.source, [
    { name: 'symbol', type: 'String' }, { name: 'asset_class', type: 'String' }, { name: 'date', type: 'StrictDate' },
    { name: 'open', type: 'Float' }, { name: 'high', type: 'Float' }, { name: 'low', type: 'Float' },
    { name: 'close', type: 'Float' }, { name: 'volume', type: 'Integer' },
  ]), filter: undefined }]]);
  t0 = performance.now();
  await corpus.plan(lambda([], fn('limit', QUOTES, lit.integer(1))));
  measured['a first query planned (ms)'] = performance.now() - t0;
  t0 = performance.now();
  await corpus.plan(lambda([], fn('limit', QUOTES, lit.integer(2))));
  measured['a query planned after (ms)'] = performance.now() - t0;
});

after(async () => {
  // the planning cost of a page over the trading model, said where the run is read
  console.log(`trading model: ${(MODEL.length / 1e6).toFixed(2)} MB, its Data ${((PROJECT.length - MODEL.length) / 1e6).toFixed(2)} MB; `
    + Object.entries(measured).map(([k, v]) => `${k} ${v.toFixed(0)}`).join('; '));
  await engine?.close();
  await projectEngine?.close();
});

type Over = 'classes' | 'referenced' | 'rows';
type Line = { dates: string[]; values: (number | null)[] };

/** A tile of one series over the class (`classes`, `referenced`: with its reference data) or the table (`rows`),
 *  daily, a line per value of `split` when it is given. */
const spec = (text: string, over: Over, split?: string): TimeseriesSpec => ({
  sources: [over === 'rows'
    ? { alias: 'px', cube: 'prices', date: 'date', key: 'symbol' }
    : { alias: 'px', cube: 'quotes', date: 'quoteTime', key: 'ticker' }],
  series: [{ id: 'A', expr: parseExpr(over === 'rows' ? text : text.replaceAll('close[', 'closePrice[')), ...(split !== undefined ? { split } : {}) }],
  grain: 'day',
});

/** The series' lines, each by its split's value ('' when it has none), earliest first, as the database answers them. */
async function lines(text: string, over: Over, split?: string): Promise<Map<string, Line>> {
  const ts = spec(text, over, split);
  const planner = over === 'rows' ? table : corpus;
  const q = seriesQuery(over === 'rows' ? rows : over === 'classes' ? classes : referenced, ts, ts.series[0]!);
  const t = await (over === 'rows' ? engine : projectEngine).execute(await planner.plan(q.query), 0);
  const col = (name: string): unknown[] => t.columns.find((c) => c.name === name)!.values as unknown[];
  const dates = col(TS_DATE);
  const values = col(TS_VALUE);
  const keys = split !== undefined ? col(TS_SPLIT).map(String) : dates.map(() => '');
  const out = new Map<string, Line>();
  dates.forEach((d, i) => {
    const l = out.get(keys[i]!) ?? { dates: [], values: [] };
    out.set(keys[i]!, l);
    l.dates.unshift(String(d).slice(0, 10));
    l.values.unshift(values[i] === null ? null : Number(values[i]));
  });
  return out;
}

async function line(text: string, over: Over): Promise<Line> {
  return (await lines(text, over)).get('')!;
}

/** Two lines the same: the same dates, each value within a part in 10⁹ (a sum's order is the database's). */
function same(over: Line, flat: Line, what: string): void {
  assert.ok(flat.values.filter((v) => v !== null).length > 100, `${what}: the sample has the figures`);
  assert.deepEqual(over.dates, flat.dates, `${what}: the same dates`);
  over.values.forEach((v, i) => {
    const w = flat.values[i]!;
    if (v === null || w === null) assert.equal(v, w, `${what} ${over.dates[i]}: ${v} where ${w}`);
    else assert.ok(Math.abs(v - w) <= 1e-9 * Math.max(1, Math.abs(w)), `${what} ${over.dates[i]}: ${v}, not ${w}`);
  });
}

describe('the trading model\'s tables, as its store declares them', () => {
  it('each table the generator writes has the store\'s columns, in its order', () => {
    // the corpus's one store: its tables after its name (a linked project's store may name a table so too)
    const store = MODEL.slice(MODEL.indexOf('Database store::DB'));
    for (const t of STRESS_MARKET_TABLES) {
      const declared = new RegExp(`Table ${t} \\(([^\\n]*)\\)\\n`).exec(store);
      assert.ok(declared, `the store declares ${t}`);
      const names = declared[1]!.split(/,\s*/).map((c) => c.trim().split(/\s+/)[0]);
      assert.deepEqual([...STRESS_MARKET_COLUMNS[t]], names, `${t}'s columns`);
    }
  });
});

describe('a timeseries over MarketQuote through stress::AllMapping is the timeseries over the table', () => {
  for (const text of ['close[AAPL]', 'volatility(close[AAPL], 22)', 'correlation(close[AAPL], close[MSFT], 63)',
    'returns(close[NVDA], 5)', 'beta(close[JPM], close[SPX], 2m)']) {
    it(text, async () => {
      const [over, flat] = await Promise.all([line(text, 'classes'), line(text, 'rows')]);
      same(over, flat, text);
    });
  }
});

// D5: the reference data a quote reaches through its instrument -- its sector, its exchange -- as a series' matches and
// its split; the flat table has neither, so each is held to the instruments it names, added or one by one.
describe('a series matched and split by the reference data the associations reach', () => {
  it('close[sector="Energy"] is XOM\'s close: the one instrument of its sector', async () => {
    same(await line('close[sector="Energy"]', 'referenced'), await line('close[XOM]', 'rows'), 'Energy');
  });

  it('a sector\'s volume summed is its instruments\' volumes added', async () => {
    same(await line('volume[sector="Information Technology"].sum', 'referenced'),
      await line('volume[AAPL].sum + volume[MSFT].sum + volume[NVDA].sum', 'rows'), 'IT volume');
  });

  it('split by ticker within a sector: a line per instrument of it, each its own returns', async () => {
    const over = await lines('returns(close[sector="Information Technology"], 1)', 'referenced', 'ticker');
    assert.deepEqual([...over.keys()].sort(), ['AAPL', 'MSFT', 'NVDA'], 'the sector\'s instruments');
    for (const t of over.keys()) same(over.get(t)!, await line(`returns(close[${t}], 1)`, 'rows'), t);
  });

  it('split by exchange: a line per listing venue, each the volume of the instruments listed there', async () => {
    const over = await lines('volume.sum', 'referenced', 'exchange');
    assert.deepEqual([...over.keys()].sort(), ['Cboe Options Exchange', 'NYSE Arca', 'Nasdaq', 'New York Stock Exchange', 'Off exchange'], 'the exchanges listing them');
    same(over.get('New York Stock Exchange')!, await line('volume[JPM].sum + volume[XOM].sum', 'rows'), 'NYSE');
    same(over.get('Nasdaq')!, await line('volume[NDX].sum + volume[AAPL].sum + volume[MSFT].sum + volume[NVDA].sum + volume[TLT].sum', 'rows'), 'Nasdaq');
  });
});
