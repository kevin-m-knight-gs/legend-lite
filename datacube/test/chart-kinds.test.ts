// Batch 3's kinds (docs/DATACUBE_CHARTS_DESIGN_2026_10_09.md §3): what each refuses, what its query asks the database
// for (a waterfall's starts and ends, a Pareto's cumulative share, a two-ring pie's inner ring -- never added up in
// the page), and each drawn as SVG in node, as the page draws it on canvas.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { element } from '../../pure-protocol/src/index.ts';
import { chartProblems, chartSnapshot, drillLevels, followCube, type ChartSpec } from '../src/chart-spec.ts';
import { chartOption, type ChartDrawing, type GridShown } from '../src/chart-option.ts';
import { facetOption, gridKindOption, kindOption } from '../src/chart-kinds.ts';
import { paretoName, ringTotalName, waterfallNames } from '../src/calc-presets.ts';
import * as echarts from 'echarts/core';
import { SVGRenderer } from 'echarts/renderers';
import '../src/chart-render.ts';
import '../src/chart-render-kinds.ts';
import type { EChartsOption } from 'echarts';
import type { ResultTable } from '../../engine-client/src/result.ts';
import type { CubeSnapshot } from '../src/snapshot.ts';

echarts.use([SVGRenderer]);

function svg(drawing: ChartDrawing, width = 640, height = 400): string {
  const chart = echarts.init(null, null, { renderer: 'svg', ssr: true, width, height });
  try {
    chart.setOption({ ...(drawing.option as EChartsOption), animation: false });
    return chart.renderToSVGString();
  } finally {
    chart.dispose();
  }
}

const CUBE: CubeSnapshot = {
  source: { query: element('sales') },
  columns: [
    { name: 'region', type: 'String' },
    { name: 'channel', type: 'String' },
    { name: 'day', type: 'StrictDate' },
    { name: 'revenue', type: 'Float' },
    { name: 'cost', type: 'Float' },
    { name: 'profit', type: 'Float' },
  ],
  derived: [],
  rows: ['region', 'channel'],
  pivotOn: [],
  measures: [{ name: 'revenue', column: 'revenue', fn: 'sum' }],
  sorts: [],
  epoch: 1,
};

const OPTIONS: ChartSpec['options'] = {
  orientation: 'vertical', stack: 'none', sort: { by: 'y', direction: 'desc' }, limit: 50, labels: true, legend: 'top',
};
const REVENUE_SUM = { column: 'revenue', fn: 'sum' } as const;
const of = (mark: ChartSpec['mark'], more: Partial<ChartSpec> = {}): ChartSpec =>
  ({ version: 2, mark, x: 'region', y: [{ column: 'revenue', fn: 'sum' }], options: OPTIONS, ...more });

function table(columns: { name: string; type: string; values: (string | number | null)[] }[]): ResultTable {
  return { columns, rowCount: columns[0]?.values.length ?? 0, epoch: 1, elapsedMs: 0 };
}

const REGIONS = ['EMEA', 'APAC', 'AMER', 'LATAM'];
const BY_REGION = table([
  { name: 'region', type: 'String', values: REGIONS },
  { name: 'sum_revenue', type: 'Float', values: [400, 300, 200, -50] },
  { name: 'sum_cost', type: 'Float', values: [250, 180, 150, 40] },
  { name: 'sum_profit', type: 'Float', values: [150, 120, 50, -90] },
]);

describe('batch 3\'s kinds refuse what they cannot draw, by name', () => {
  const problems = (spec: ChartSpec) => chartProblems(spec, CUBE).join(' ');
  it('a flow and a stream need their split; a kind of one series has none', () => {
    assert.match(problems(of('sankey')), /where each flow goes/);
    assert.match(problems(of('chord')), /where each flow goes/);
    assert.match(problems(of('stream', { x: 'day' })), /layers/);
    assert.match(problems(of('funnel', { split: 'channel' })), /one series: it has no split/);
    assert.match(problems(of('waterfall', { split: 'channel' })), /one series/);
  });
  it('a dumbbell and a range plot two values; a radar three or more; parallel two or more', () => {
    assert.match(problems(of('dumbbell')), /two values/);
    assert.match(problems(of('range')), /two values/);
    assert.match(problems(of('radar', { y: [{ column: 'revenue', fn: 'sum' }, { column: 'cost', fn: 'sum' }] })), /three values/);
    assert.match(problems(of('parallel')), /two values or more/);
    assert.deepEqual(chartProblems(of('dumbbell', { y: [{ column: 'cost', fn: 'sum' }, { column: 'revenue', fn: 'sum' }] }), CUBE), []);
  });
  it('a Pareto runs from the largest; a gauge needs a target and nothing across; a calendar and a stream need dates', () => {
    assert.match(problems(of('pareto', { options: { ...OPTIONS, sort: { by: 'x', direction: 'asc' } } })), /order by value, largest first/);
    assert.deepEqual(chartProblems(of('pareto'), CUBE), []);
    const { x: _x, ...gauge } = of('gauge');
    assert.match(problems(gauge), /needs a target/);
    assert.deepEqual(chartProblems({ ...gauge, options: { ...OPTIONS, target: 1000 } }, CUBE), []);
    assert.match(problems({ ...of('gauge'), options: { ...OPTIONS, target: 1000 } }), /no column across/);
    assert.match(problems(of('calendar')), /not one/);
    assert.deepEqual(chartProblems(of('calendar', { x: 'day' }), CUBE), []);
    assert.match(problems(of('calendar', { x: 'day', grain: 'month' })), /its grain is the day/);
  });
  it('layers, a label slot and hover measures only where they are drawn', () => {
    assert.match(problems(of('funnel', { layers: [{ kind: 'line', value: 1 }] })), /A funnel draws no reference lines/);
    assert.match(problems(of('rose', { label: { column: 'cost', fn: 'sum' } })), /writes no other measure/);
    assert.match(problems(of('sankey', { split: 'channel', tooltip: [{ column: 'cost', fn: 'sum' }] })), /lists no further measures/);
    assert.deepEqual(chartProblems(of('lollipop', { tooltip: [{ column: 'cost', fn: 'sum' }] }), CUBE), []);
  });
  it('drills from a kind of categories; follows the cube except a chart of the grid as shown', () => {
    assert.deepEqual(drillLevels(of('lollipop'), CUBE), ['region', 'channel']);
    assert.equal(drillLevels(of('sankey', { split: 'channel' }), CUBE), null);
    const sun = of('sunburst', { x: 'channel', frozen: false });
    assert.equal(followCube(sun, CUBE), sun);
  });
});

describe('the database computes each kind\'s own figures', () => {
  it('a waterfall\'s end is a running total along the chart\'s own order, its start that end less its value', () => {
    const s = chartSnapshot(CUBE, of('waterfall'));
    const { start, end } = waterfallNames('sum_revenue');
    const running = s.groupDerived?.find((d) => d.name === end);
    assert.deepEqual(running?.window, {
      fn: 'sum', column: 'sum_revenue', partition: [], frame: 'running',
      order: [{ column: 'sum_revenue', direction: 'desc' }, { column: 'region', direction: 'asc' }],
    });
    assert.ok(s.groupDerived?.some((d) => d.name === start && d.lambda !== undefined));
    // the rows come in that very order: ties broken by the category
    assert.deepEqual(s.sorts, [{ column: 'sum_revenue', direction: 'desc' }, { column: 'region', direction: 'asc' }]);
  });
  it('a Pareto\'s share is the running total from the largest over the whole; a two-ring pie\'s inner ring a total per category', () => {
    const p = chartSnapshot(CUBE, of('pareto'));
    assert.ok(p.groupDerived?.some((d) => d.name === paretoName('sum_revenue')));
    const ring = chartSnapshot(CUBE, of('pie', { split: 'channel' })).groupDerived?.find((d) => d.name === ringTotalName('sum_revenue'));
    assert.deepEqual(ring?.window?.partition, ['region']);
  });
  it('a gauge\'s query groups by nothing: one row', () => {
    const s = chartSnapshot(CUBE, { ...of('gauge'), options: { ...OPTIONS, target: 1000 } });
    assert.deepEqual(s.rows, []);
  });
});

describe('each kind is drawn', () => {
  it('a lollipop: a stem and a dot per category, each labelled as the grid writes it', () => {
    const d = kindOption(of('lollipop'), BY_REGION);
    const out = svg(d);
    assert.ok(out.includes('EMEA') && out.includes('LATAM'));
    assert.deepEqual(d.keyAt(1, 2), { region: 'AMER' });
  });

  it('a dumbbell and a range: two values per category', () => {
    const two = [{ column: 'cost', fn: 'sum' as const }, { column: 'revenue', fn: 'sum' as const }];
    for (const mark of ['dumbbell', 'range'] as const) {
      const out = svg(kindOption(of(mark, { y: two }), BY_REGION));
      assert.ok(out.includes('APAC'), mark);
      assert.ok(/<path|<rect/.test(out), `${mark} draws its spans`);
    }
    const horizontal = svg(kindOption(of('dumbbell', { y: two, options: { ...OPTIONS, orientation: 'horizontal' } }), BY_REGION));
    assert.ok(horizontal.includes('EMEA'));
  });

  it('a waterfall: each bar from the database\'s start to its end, then the total -- the last end, read', () => {
    const { start, end } = waterfallNames('sum_revenue');
    const rows = table([
      { name: 'region', type: 'String', values: REGIONS },
      { name: 'sum_revenue', type: 'Float', values: [400, 300, 200, -50] },
      { name: start, type: 'Float', values: [0, 400, 700, 900] },
      { name: end, type: 'Float', values: [400, 700, 900, 850] },
    ]);
    const d = kindOption(of('waterfall'), rows);
    const out = svg(d);
    assert.ok(out.includes('Total'));
    const series = (d.option as { series: { data: { value: unknown[] }[] }[] }).series[0]!;
    assert.deepEqual(series.data.at(-1)!.value, [4, 0, 850], 'the total runs from zero to the last end');
    assert.equal(d.keyAt(0, 4), null, 'the total is no category');
  });

  it('a Pareto: the bars and the database\'s cumulative share on a second axis, the 80% line drawn', () => {
    const rows = table([
      { name: 'region', type: 'String', values: REGIONS },
      { name: 'sum_revenue', type: 'Float', values: [400, 300, 200, 100] },
      { name: paretoName('sum_revenue'), type: 'Float', values: [0.4, 0.7, 0.9, 1] },
    ]);
    const out = svg(kindOption(of('pareto'), rows));
    assert.ok(out.includes('80%') && out.includes('Cumulative share'));
  });

  it('a funnel and a rose: a stage, a petal per category', () => {
    assert.ok(svg(kindOption(of('funnel'), BY_REGION)).includes('APAC'));
    assert.ok(svg(kindOption(of('rose'), BY_REGION)).includes('AMER'));
  });

  it('a polar bar and radial bars', () => {
    assert.ok(svg(kindOption(of('polar'), BY_REGION)).includes('EMEA'));
    assert.ok(svg(kindOption(of('polar', { options: { ...OPTIONS, orientation: 'horizontal' } }), BY_REGION)).includes('EMEA'));
  });

  it('a radar: a shape per category, a spoke per value; parallel: a line per category through an axis per value', () => {
    const three = [{ column: 'revenue', fn: 'sum' as const }, { column: 'cost', fn: 'sum' as const }, { column: 'profit', fn: 'sum' as const }];
    assert.ok(svg(kindOption(of('radar', { y: three }), BY_REGION)).includes('sum of cost'));
    assert.ok(svg(kindOption(of('parallel', { y: three }), BY_REGION)).includes('sum of profit'));
  });

  it('a Sankey and a chord: a flow per row, from the column across to the split; no width, no flow', () => {
    const flows = table([
      { name: 'region', type: 'String', values: ['EMEA', 'EMEA', 'APAC', 'APAC'] },
      { name: 'channel', type: 'String', values: ['Online', 'Retail', 'Online', 'Retail'] },
      { name: 'sum_revenue', type: 'Float', values: [100, 60, 80, 0] },
    ]);
    const sankey = kindOption(of('sankey', { split: 'channel' }), flows);
    assert.ok(svg(sankey).includes('Online'));
    assert.match(sankey.notes.join(), /1 flow not shown/);
    assert.deepEqual(sankey.keyAt(0, 0, { key: { region: 'EMEA', channel: 'Online' } }), { region: 'EMEA', channel: 'Online' });
    assert.ok(svg(kindOption(of('chord', { split: 'channel' }), flows)).includes('Retail'));
  });

  it('a streamgraph: layers along time', () => {
    const rows = table([
      { name: 'day', type: 'StrictDate', values: ['2024-01-01', '2024-01-01', '2024-02-01', '2024-02-01'] },
      { name: 'channel', type: 'String', values: ['Online', 'Retail', 'Online', 'Retail'] },
      { name: 'sum_revenue', type: 'Float', values: [10, 20, 15, 25] },
    ]);
    assert.ok(svg(kindOption(of('stream', { x: 'day', split: 'channel' }), rows)).length > 1000);
  });

  it('a calendar: a cell per day, a calendar per year, the latest three', () => {
    const rows = table([
      { name: 'day', type: 'StrictDate', values: ['2021-03-01', '2022-03-01', '2023-03-01', '2024-03-01', '2024-03-02'] },
      { name: 'sum_revenue', type: 'Float', values: [1, 2, 3, 4, 5] },
    ]);
    const d = kindOption(of('calendar', { x: 'day' }), rows);
    assert.match(d.notes.join(), /latest 3 of 4 years/);
    const out = svg(d, 800, 600);
    assert.ok(out.includes('2024') && !out.includes('2021'));
    assert.deepEqual(d.keyAt(0, 0, { ci: 3 }), { day: '2024-03-01' });
  });

  it('a gauge: the value against its target, as a dial or a ring; past the target, said', () => {
    const one = table([{ name: 'sum_revenue', type: 'Float', values: [1200] }]);
    const { x: _x, ...spec } = of('gauge');
    const dial = kindOption({ ...spec, options: { ...OPTIONS, target: 1000 } }, one, undefined, (v) => String(v));
    assert.ok(svg(dial).includes('1200'));
    assert.match(dial.notes.join(), /past the target/);
    const ring = kindOption({ ...spec, options: { ...OPTIONS, target: 2000, ring: true } }, one, undefined, (v) => String(v));
    assert.ok(svg(ring).includes('of 2000'));
  });
});

describe('a pie in two rings, and half a ring', () => {
  it('the inner ring the database\'s totals, the outer the split\'s slices beside their category', () => {
    const rows = table([
      { name: 'region', type: 'String', values: ['EMEA', 'EMEA', 'APAC'] },
      { name: 'channel', type: 'String', values: ['Online', 'Retail', 'Online'] },
      { name: 'sum_revenue', type: 'Float', values: [100, 60, 80] },
      { name: ringTotalName('sum_revenue'), type: 'Float', values: [160, 160, 80] },
    ]);
    const d = chartOption(of('pie', { split: 'channel' }), rows);
    const series = (d.option as { series: { data: { value: number; name: string }[] }[] }).series;
    assert.deepEqual(series[0]!.data.map((x) => x.value), [160, 80]);
    assert.deepEqual(series[1]!.data.map((x) => x.name), ['EMEA · Online', 'EMEA · Retail', 'APAC · Online']);
    assert.ok(svg(d).includes('EMEA'));
    assert.ok(svg(chartOption(of('pie', { options: { ...OPTIONS, half: true } }), BY_REGION)).includes('EMEA'));
  });
});

describe('the grid as shown, as a sunburst and a tree', () => {
  const shown: GridShown = {
    rows: table([
      { name: '__tree', type: 'String', values: ['Total', 'EMEA', 'Online', 'Retail', 'APAC'] },
      // a number column of the rows, empty on a group's row: never what the rings are sized by
      { name: 'order_id', type: 'Integer', values: [null, null, null, null, null] },
      { name: 'revenue', type: 'Float', values: [240, 160, 100, 60, 80] },
    ]),
    tree: [
      { path: [], level: 0, isGroup: true, expanded: true, isTotal: true },
      { path: ['EMEA'], level: 1, isGroup: true, expanded: true, isTotal: false },
      { path: ['EMEA', 'Online'], level: 2, isGroup: true, expanded: false, isTotal: false },
      { path: ['EMEA', 'Retail'], level: 2, isGroup: true, expanded: false, isTotal: false },
      { path: ['APAC'], level: 1, isGroup: true, expanded: false, isTotal: false },
    ],
    leaves: [{ name: '__tree', type: 'String' }, { name: 'order_id', type: 'Integer' }, { name: 'revenue', type: 'Float', measure: true }],
    levels: ['region', 'channel'],
  };
  it('each group\'s ring says its own total, the grid\'s', () => {
    const d = gridKindOption('sunburst', shown);
    const data = (d.option as { series: { data: { name: string; value?: number; children?: unknown[] }[] }[] }).series[0]!.data;
    assert.deepEqual(data.map((n) => [n.name, n.value, n.children?.length ?? 0]), [['EMEA', 160, 2], ['APAC', 80, 0]]);
    assert.ok(svg(d).includes('EMEA'));
    assert.deepEqual(d.keyAt(0, 0, { key: { region: 'EMEA', channel: 'Online' } }), { region: 'EMEA', channel: 'Online' });
  });
  it('a tree: a node per group, its rows its branches', () => {
    assert.ok(svg(gridKindOption('tree', shown), 800, 500).includes('Online'));
  });
});

describe('a bubble: a scatter sized by a third column of its rows', () => {
  it('each point [x, y, size], sized between the least and the most the rows say; a point with no size, said', () => {
    const rows = table([
      { name: 'cost', type: 'Float', values: [10, 20, 30, 40] },
      { name: 'revenue', type: 'Float', values: [15, 30, 50, 60] },
      { name: 'profit', type: 'Float', values: [5, 10, null, 20] },
    ]);
    const spec = of('scatter', { x: 'cost', y: [{ column: 'revenue', fn: 'sum' }], size: { column: 'profit', fn: 'sum' } });
    assert.deepEqual(chartProblems(spec, CUBE), []);
    const d = chartOption(spec, rows);
    const option = d.option as { series: { data: number[][] }[]; visualMap: { min: number; max: number; dimension: number } };
    assert.deepEqual(option.series[0]!.data, [[10, 15, 5], [20, 30, 10], [40, 60, 20]]);
    assert.deepEqual([option.visualMap.dimension, option.visualMap.min, option.visualMap.max], [2, 5, 20]);
    assert.match(d.notes.join(), /1 point not shown: no profit to size by/);
    assert.ok(svg(d).length > 1000);
  });
});

describe('small multiples: a chart per value, on shared axes', () => {
  const rows = table([
    { name: 'region', type: 'String', values: ['EMEA', 'APAC', 'EMEA', 'APAC', 'EMEA'] },
    { name: 'channel', type: 'String', values: ['Online', 'Online', 'Retail', 'Retail', 'Partner'] },
    { name: 'sum_revenue', type: 'Float', values: [100, 80, 60, 300, 40] },
  ]);
  it('the query groups by the facet, panel by panel, each a whole of its own', () => {
    const spec = of('bar', { x: 'region', facet: { column: 'channel' }, layers: [{ kind: 'stat', stat: 'average' }], y: [{ ...REVENUE_SUM, showAs: { kind: 'percentOfTotal' } }] });
    const s = chartSnapshot(CUBE, spec);
    assert.deepEqual(s.rows, ['region', 'channel']);
    assert.deepEqual(s.sorts[0], { column: 'channel', direction: 'asc' }, 'panel by panel');
    assert.ok(s.groupDerived?.every((d) => !d.window || d.window.fn === 'rank' || d.window.partition.includes('channel')), 'each window within its panel');
  });
  it('a grid per panel, its axes renumbered, the value axes shared: each runs to the most any panel shows', () => {
    const d = facetOption(of('bar', { x: 'region', facet: { column: 'channel' } }), rows);
    const o = d.option as { grid: unknown[]; yAxis: { max?: number; gridIndex: number }[]; series: { xAxisIndex: number; yAxisIndex: number }[]; title: { text: string }[] };
    assert.equal(o.grid.length, 3);
    assert.deepEqual(o.title.map((t) => t.text), ['Online', 'Retail', 'Partner']);
    assert.deepEqual(o.yAxis.map((a) => [a.gridIndex, a.max]), [[0, 300], [1, 300], [2, 300]]);
    assert.deepEqual(o.series.map((s) => [s.xAxisIndex, s.yAxisIndex]), [[0, 0], [1, 1], [2, 2]]);
    // a click: the panel's own mark, and its facet's value
    assert.deepEqual(d.keyAt(1, 1), { region: 'APAC', channel: 'Retail' });
    assert.ok(svg(d, 900, 500).includes('Partner'));
  });
  it('a matrix of rows and columns; a stack keeps each panel\'s own scale, said', () => {
    const grid = table([
      { name: 'day', type: 'StrictDate', values: ['2024-01-01', '2024-01-01', '2024-02-01', '2024-02-01'] },
      { name: 'region', type: 'String', values: ['EMEA', 'APAC', 'EMEA', 'APAC'] },
      { name: 'channel', type: 'String', values: ['Online', 'Retail', 'Retail', 'Online'] },
      { name: 'sum_revenue', type: 'Float', values: [1, 2, 3, 4] },
    ]);
    const d = facetOption(of('line', { x: 'day', facet: { row: 'region', column: 'channel' }, options: { ...OPTIONS, sort: { by: 'x', direction: 'asc' } } }), grid);
    assert.equal((d.option as { grid: unknown[] }).grid.length, 4);
    const stacked = facetOption(of('bar', { x: 'region', facet: { column: 'channel' }, options: { ...OPTIONS, stack: 'stacked' } }), rows);
    assert.match(stacked.notes.join(), /own scale/);
    // a series keeps its colour in every panel, whatever order each panel met it in
    const split = facetOption(of('bar', { x: 'day', split: 'channel', facet: { column: 'region' } }), grid);
    const byName = new Map<string, Set<string>>();
    for (const s of (split.option as { series: { name: string; itemStyle: { color: string } }[] }).series) {
      byName.set(s.name, (byName.get(s.name) ?? new Set()).add(s.itemStyle.color));
    }
    assert.ok([...byName.values()].every((c) => c.size === 1), 'one colour per series name');
    assert.equal(new Set([...byName.values()].map((c) => [...c][0])).size, byName.size, 'and each its own');
    // and its place: each panel's series in one order, and a click on one still says its own split's value
    const merged = (split.option as { series: { name: string; xAxisIndex: number }[] }).series;
    const panels = [...new Set(merged.map((x) => x.xAxisIndex))].map((a) => merged.filter((x) => x.xAxisIndex === a).map((x) => x.name));
    const common = panels.map((p) => p.filter((n) => panels.every((q) => q.includes(n))));
    assert.ok(common.every((p) => JSON.stringify(p) === JSON.stringify(common[0])), JSON.stringify(panels));
    merged.forEach((x, si) => {
      const key = split.keyAt(si, 0);
      if (key) assert.equal(key['channel'], x.name, `series ${si}`);
    });
  });
});
