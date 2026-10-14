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
