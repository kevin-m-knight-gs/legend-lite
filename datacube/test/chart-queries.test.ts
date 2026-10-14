// Chart queries, PLANNED AND RUN: the query a chart asks (chart-spec.ts) through legend-lite's own planner, on a real
// DuckDB, its numbers read back. The unit tests (chart-echarts.test.ts) say what the query is shaped like; these say the
// planner takes it and the database answers what the chart means -- a group-level column, a preset, a reference line
// (docs/DATACUBE_CHARTS_DESIGN_2026_10_09.md §3.1, §3.2).

import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { openDatabase } from '#test-host';

import { DuckDbEngine } from '../../engine-client/src/duckdb.ts';
import type { ResultTable } from '../../engine-client/src/result.ts';
import { chartQuery, DEFAULT_OPTIONS, measureName, type ChartSpec } from '../src/chart-spec.ts';
import type { CubeSnapshot } from '../src/snapshot.ts';
import { ingestFile, type DuckDbFiles } from '../src/upload.ts';
import { plannerFor } from './catalog-builder.ts';
import { row } from './lite-compiler.ts';
import type { LitePlanner } from '../src/lite-planner.ts';
import { from } from '../../pure-protocol/src/index.ts';

// Six sales: two regions, three months -- small enough to check every number by hand.
const CSV = [
  'region,sold,revenue,cost',
  'EMEA,2024-01-15,100,60',
  'EMEA,2024-02-10,200,150',
  'EMEA,2024-03-05,300,200',
  'AMER,2024-01-20,400,100',
  'AMER,2024-02-25,50,40',
  'AMER,2024-03-30,150,90',
].join('\n');

const writer = plannerFor('', 'local::RT');
let engine: DuckDbEngine;
let planner: LitePlanner;
let cube: CubeSnapshot;

before(async () => {
  const db = await openDatabase();
  engine = new DuckDbEngine(db.connection);
  const files: DuckDbFiles = db.files;
  const r = await ingestFile(engine, files, new File([CSV], 'sales.csv'), writer);
  planner = plannerFor(r.model, r.runtime);
  cube = {
    source: { query: r.source },
    columns: [
      { name: 'region', type: 'String' },
      { name: 'sold', type: 'StrictDate' },
      { name: 'revenue', type: 'Integer', kind: 'measure' },
      { name: 'cost', type: 'Integer', kind: 'measure' },
    ],
    derived: [],
    rows: ['region'],
    pivotOn: [],
    measures: [
      { name: 'revenue', column: 'revenue', fn: 'sum' },
      { name: 'cost', column: 'cost', fn: 'sum' },
    ],
    sorts: [],
    epoch: 1,
  };
});

after(async () => {
  await engine?.close();
});

/** The chart's rows, as the planner plans its query and the database answers it. */
async function rows(spec: ChartSpec, s: CubeSnapshot = cube): Promise<ResultTable> {
  const { query } = chartQuery(s, spec);
  return engine.execute(await planner.plan(query), 0);
}

/** A column of the answer, by name. */
function values(t: ResultTable, name: string): unknown[] {
  const c = t.columns.find((x) => x.name === name);
  assert.ok(c, `the answer has a column '${name}' (it has ${t.columns.map((x) => x.name).join(', ')})`);
  return c.values.map((v) => (typeof v === 'bigint' ? Number(v) : v));
}

const BY_REGION: ChartSpec = {
  version: 2,
  mark: 'bar',
  x: 'region',
  y: [{ column: 'revenue', fn: 'sum' }],
  // in the order across, so each answer is read in a known order
  options: { ...DEFAULT_OPTIONS, sort: { by: 'x', direction: 'asc' } },
};

describe('a group-level column, planned and run', () => {
  it('the cube\'s margin is the ratio of the groups\' sums, not an average of the rows\' ratios', async () => {
    const margin = await row('($x.revenue - $x.cost) / $x.revenue');
    const s: CubeSnapshot = { ...cube, groupDerived: [{ name: 'margin', lambda: margin, type: 'Float' }] };
    const t = await rows({ ...BY_REGION, y: [{ column: 'margin', fn: 'value' }] }, s);
    assert.deepEqual(values(t, 'region'), ['AMER', 'EMEA']);
    // AMER: (600 - 230) / 600; EMEA: (600 - 410) / 600
    assert.deepEqual(values(t, 'margin').map((v) => Number(v).toFixed(4)), [(370 / 600).toFixed(4), (190 / 600).toFixed(4)]);
  });

  it('beside an aggregated measure, each named as the chart reads it', async () => {
    const margin = await row('$x.revenue - $x.cost');
    const s: CubeSnapshot = { ...cube, groupDerived: [{ name: 'profit', lambda: margin, type: 'Integer' }] };
    const spec: ChartSpec = { ...BY_REGION, y: [{ column: 'revenue', fn: 'max' }, { column: 'profit', fn: 'value', mark: 'line' }] };
    const t = await rows(spec, s);
    assert.deepEqual(values(t, measureName(spec.y[0]!)), [400, 300]);
    assert.deepEqual(values(t, 'profit'), [370, 190]);
  });
});

describe('presets, planned and run (docs/DATACUBE_CHARTS_DESIGN_2026_10_09.md §3.2)', () => {
  const BY_MONTH: ChartSpec = { ...BY_REGION, x: 'sold', grain: 'month' };

  it('a date by month: the compiler\'s firstDayOfMonth, one row per month', async () => {
    const t = await rows(BY_MONTH);
    assert.deepEqual(values(t, 'sold__month').map((v) => String(v).slice(0, 10)), ['2024-01-01', '2024-02-01', '2024-03-01']);
    assert.deepEqual(values(t, 'sum_revenue'), [500, 250, 450]);
  });

  it('by quarter and by year: one row each here', async () => {
    for (const grain of ['quarter', 'year'] as const) {
      const t = await rows({ ...BY_MONTH, grain });
      assert.deepEqual(values(t, `sold__${grain}`).map((v) => String(v).slice(0, 10)), ['2024-01-01'], grain);
      assert.deepEqual(values(t, 'sum_revenue'), [1200], grain);
    }
  });

  it('a number by bins of a width: floor(v / w) * w', async () => {
    const t = await rows({ ...BY_REGION, x: 'revenue', bins: { width: 200 }, y: [{ column: 'cost', fn: 'count' }] });
    // 50, 100, 150 -> 0; 200, 300 -> 200; 400 -> 400
    assert.deepEqual(values(t, 'revenue__bin'), [0, 200, 400]);
    assert.deepEqual(values(t, 'count'), [3, 2, 1]);
  });

  it('a number by a count of bins over its range: the database finds the range and each edge', async () => {
    const t = await rows({ ...BY_REGION, x: 'revenue', bins: { count: 2 }, y: [{ column: 'cost', fn: 'count' }] });
    // the range 50..400, two bins 175 wide: [50, 225) holds 50, 100, 150, 200; [225, 400] holds 300, 400 (the maximum
    // in the last bin, not one of its own)
    assert.deepEqual(values(t, 'revenue__bin').map(Number), [50, 225]);
    assert.deepEqual(values(t, 'count'), [4, 2]);
  });

  it('a running total along the months, restarting per region', async () => {
    const t = await rows({ ...BY_MONTH, split: 'region', y: [{ column: 'revenue', fn: 'sum', showAs: { kind: 'runningTotal' } }],
      options: { ...BY_MONTH.options, sort: { by: 'x', direction: 'asc' } } });
    const got = values(t, 'region').map((r, i) => `${String(r)} ${String(values(t, 'sold__month')[i]).slice(0, 7)} ${String(values(t, 'runningTotal_sum_revenue')[i])}`);
    assert.deepEqual(got.sort(), [
      'AMER 2024-01 400', 'AMER 2024-02 450', 'AMER 2024-03 600',
      'EMEA 2024-01 100', 'EMEA 2024-02 300', 'EMEA 2024-03 600',
    ]);
  });

  it('% of total, the change from the month before (absolute and as a ratio), a moving average, a rank', async () => {
    const shown = async (kind: 'percentOfTotal' | 'difference' | 'percentChange' | 'movingAverage' | 'rank') => {
      const t = await rows({ ...BY_MONTH, y: [{ column: 'revenue', fn: 'sum', showAs: kind === 'movingAverage' ? { kind, rows: 2 } : { kind } }] });
      return values(t, `${kind}_sum_revenue`).map((v) => (v === null ? null : Number(Number(v).toFixed(4))));
    };
    // the months' sums: 500, 250, 450 of 1200
    assert.deepEqual(await shown('percentOfTotal'), [500 / 1200, 250 / 1200, 450 / 1200].map((v) => Number(v.toFixed(4))));
    assert.deepEqual(await shown('difference'), [null, -250, 200]);
    assert.deepEqual(await shown('percentChange'), [null, -0.5, 0.8]);
    assert.deepEqual(await shown('movingAverage'), [500, 375, 350]);
    assert.deepEqual(await shown('rank'), [1, 3, 2]);
  });
});

describe('the presets in the grid, planned and run: along its own rows', () => {
  it('a show-as over the grid\'s level is a group-level window in the order the level shows', async () => {
    const { showAsColumns } = await import('../src/calc-presets.ts');
    const { levelLambda } = await import('../src/query.ts');
    const s: CubeSnapshot = {
      ...cube,
      rows: ['region'],
      sorts: [{ column: 'region', direction: 'asc' }],
      groupDerived: [
        ...showAsColumns({ kind: 'runningTotal' }, 'revenue', [], [], 'revenue_runningTotal'),
        ...showAsColumns({ kind: 'percentOfTotal' }, 'revenue', [], [], 'revenue_share'),
      ],
    };
    const t = await engine.execute(await planner.plan(levelLambda(s, { level: 1, parent: [] })), 0);
    assert.deepEqual(values(t, 'region'), ['AMER', 'EMEA']);
    assert.deepEqual(values(t, 'revenue_runningTotal'), [600, 1200]);
    assert.deepEqual(values(t, 'revenue_share').map(Number), [0.5, 0.5]);
  });

  it('a date by its period is a row-stage column the grid can group by', async () => {
    const { grainColumn } = await import('../src/calc-presets.ts');
    const { levelLambda } = await import('../src/query.ts');
    const s: CubeSnapshot = {
      ...cube,
      derived: [grainColumn('sold', 'quarter', 'sold_quarter')],
      rows: ['sold_quarter'],
      sorts: [{ column: 'sold_quarter', direction: 'asc' }],
    };
    const t = await engine.execute(await planner.plan(levelLambda(s, { level: 1, parent: [] })), 0);
    assert.deepEqual(values(t, 'sold_quarter').map((v) => String(v).slice(0, 10)), ['2024-01-01']);
    assert.deepEqual(values(t, 'revenue'), [1200]);
  });
});

describe('reference lines, planned and run: the database\'s statistics over the chart\'s rows', () => {
  it('the average, maximum and minimum of a measure over every row the chart draws', async () => {
    const t = await rows({ ...BY_REGION, x: 'sold', grain: 'month', layers: [{ kind: 'stat', stat: 'average' }, { kind: 'extremes' }] });
    // the months' sums 500, 250, 450: average 400, max 500, min 250 -- the same on every row
    assert.deepEqual(values(t, 'average_of_sum_revenue').map(Number), [400, 400, 400]);
    assert.deepEqual(values(t, 'max_of_sum_revenue').map(Number), [500, 500, 500]);
    assert.deepEqual(values(t, 'min_of_sum_revenue').map(Number), [250, 250, 250]);
  });

  it('a hover\'s and a label\'s measures beside the plotted one, each its own aggregate of one column', async () => {
    const t = await rows({ ...BY_REGION, tooltip: [{ column: 'revenue', fn: 'max' }], label: { column: 'cost', fn: 'sum' } });
    assert.deepEqual(values(t, 'sum_revenue'), [600, 600]);
    assert.deepEqual(values(t, 'max_revenue'), [400, 300]);
    assert.deepEqual(values(t, 'sum_cost'), [230, 410]);
  });
});

describe('the example page\'s charts, planned and run on its own sample (docs/DATACUBE_CHARTS_DESIGN_2026_10_09.md §3.3)', () => {
  it('the sample reads as the page declares it, and every chart\'s query plans and answers', async () => {
    const { chartsDashboardPage, EXAMPLE_CUBE, EXAMPLE_ROWS, SALES_SPECS } = await import('../src/examples/charts-dashboard.ts');
    const { salesCsv } = await import('../src/samples.ts');
    const db = await openDatabase();
    const local = new DuckDbEngine(db.connection);
    try {
      const r = await ingestFile(local, db.files, new File([salesCsv(EXAMPLE_ROWS)], 'sample-sales.csv'), writer);
      const sales = plannerFor(r.model, r.runtime);
      // the page's column types are the compiler's reading of the file
      const all = await local.execute(await sales.plan(from(r.source).limit(1).lambda()), 0);
      assert.deepEqual(all.columns.map((c) => `${c.name}:${c.type}`), SALES_SPECS.map((c) => `${c.name}:${c.type}`));
      const cube: CubeSnapshot = { ...EXAMPLE_CUBE, source: { query: r.source } };
      const page = await chartsDashboardPage();
      for (const v of page.views) {
        if (v.kind !== 'chart') continue;
        const { query } = chartQuery(cube, v.spec);
        const t = await local.execute(await sales.plan(query), 0);
        assert.ok(t.rowCount > 0, `${v.title}: rows`);
      }
    } finally {
      await local.close();
    }
  });
});
