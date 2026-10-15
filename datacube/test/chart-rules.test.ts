// DataCube's chart rules, as cases (test/fixtures/chart-rules.json): each a chart over the fixture's columns, refused or
// not. The rules are written once, here in DataCube, and each case goes the way a chart Python makes goes
// (headless/grid.ts gridChart): made as DataCube's own new chart changed by hand (chart-spec.ts chartFrom, editChart),
// read as a page reads it, regrouped as the panel keeps it (followCube), then judged (chartProblems).
// python/tests/test_page.py holds Python to the same answers, which Python gets by asking this code, headless
// (legend_lite/page.py _made) -- never by rules of its own (the review of 2d24022).

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

import { element } from '../../pure-protocol/src/index.ts';
import {
  chartFrom, chartProblems, defaultChart, DEFAULT_OPTIONS, editChart, followCube, NO_CHART, TIME_LIMIT, type ChartSpec,
} from '../src/chart-spec.ts';
import type { CubeSnapshot } from '../src/snapshot.ts';
import { runfileFromEnv } from '../../tools/js/runfiles.mts';

interface Rules {
  readonly columns: readonly { readonly name: string; readonly type: string }[];
  readonly cases: readonly { readonly case: string; readonly refused: boolean; readonly spec: ChartSpec }[];
}

const rules = JSON.parse(readFileSync(runfileFromEnv('CHART_RULES'), 'utf8')) as Rules;

const CUBE: CubeSnapshot = {
  source: { query: element('sales') },
  columns: rules.columns.map((c) => ({ name: c.name, type: c.type })),
  derived: [],
  rows: [],
  pivotOn: [],
  measures: [],
  sorts: [],
  epoch: 1,
};

/** What a chart Python makes is told, as gridChart judges it: its problems, the reader's among them. */
function problemsOf(given: Record<string, unknown>, s: CubeSnapshot = CUBE, before?: ChartSpec): string[] {
  const made = chartFrom(given, s, before);
  if (typeof made === 'string') return [made];
  return chartProblems(followCube(made, s), s);
}

describe('DataCube\'s chart rules, case by case (Python asks the same code)', () => {
  for (const c of rules.cases) {
    it(`${c.case}: ${c.refused ? 'refused' : 'drawn'}`, () => {
      const problems = problemsOf(c.spec as unknown as Record<string, unknown>);
      if (c.refused) assert.ok(problems.length > 0, 'refused');
      else assert.deepEqual(problems, []);
    });
  }
});

describe('a chart DataCube makes of what it is told (chartFrom: Python\'s grid.chart, Chart.plot and Chart.options)', () => {
  const made = (given: Record<string, unknown>, before?: ChartSpec, s: CubeSnapshot = CUBE): ChartSpec => {
    const spec = chartFrom(given, s, before);
    if (typeof spec === 'string') throw new Error(spec);
    return spec;
  };
  const GROUPED: CubeSnapshot = { ...CUBE, rows: ['region', 'channel'] };

  it('what is not told is its own new chart\'s: a date across runs in time order, and long; a category leads with the largest', () => {
    const byDay = made({ mark: 'line', x: 'day' });
    assert.deepEqual(byDay.options.sort, { by: 'x', direction: 'asc' });
    assert.equal(byDay.options.limit, TIME_LIMIT);
    assert.deepEqual(made({ mark: 'bar', x: 'region' }).options, DEFAULT_OPTIONS);
    // told options win, the rest are the new chart's
    assert.deepEqual(made({ mark: 'line', x: 'day', options: { limit: 10 } }).options.sort, { by: 'x', direction: 'asc' });
    assert.equal(made({ mark: 'line', x: 'day', options: { limit: 10 } }).options.limit, 10);
    // its grouping, untold, is the new chart's: the grid's first row group across, its second split
    const pie = made({ mark: 'pie' }, undefined, GROUPED);
    assert.equal(pie.x, 'region');
    assert.equal(pie.split, 'channel');
  });

  it('its measure, untold, is the new chart\'s: a fraction first, summed; a count when the cube has no number', () => {
    assert.deepEqual(made({ mark: 'bar', x: 'region' }).y, [{ column: 'revenue', fn: 'sum' }]);
    const words: CubeSnapshot = { ...CUBE, columns: [{ name: 'region', type: 'String' }] };
    assert.deepEqual(made({ mark: 'bar', x: 'region' }, undefined, words).y, [{ column: 'region', fn: 'count' }]);
    // a cube with no column to put across has no new chart, as the panel has none
    assert.equal(chartFrom({ mark: 'kpi' }, { ...CUBE, columns: [{ name: 'revenue', type: 'Float' }] }), NO_CHART);
  });

  it('a kind takes what it needs and leaves what it has no place for, as the form\'s change of kind does', () => {
    const gauge = made({ mark: 'gauge', options: { target: 5 } }, undefined, GROUPED);
    assert.equal(gauge.x, undefined, 'a gauge has nothing across');
    assert.equal(gauge.split, undefined, 'nor a split');
    assert.equal(made({ mark: 'lollipop' }, undefined, GROUPED).split, undefined, 'a kind of one series has no split');
    // a Pareto runs from the largest, on a date too: the kind's order, not the date's
    const pareto = made({ mark: 'pareto', x: 'day' });
    assert.deepEqual(pareto.options.sort, { by: 'y', direction: 'desc' });
    assert.deepEqual(problemsOf({ mark: 'pareto', x: 'day' }), []);
    // what was told stays told, and is judged: a gauge told a column across is refused, never quietly changed
    assert.match(problemsOf({ mark: 'gauge', x: 'region', options: { target: 5 } }).join(' '), /no column across/);
  });

  it('a hand on what the grid\'s grouping sets freezes it, as the panel\'s does; frozen told is kept', () => {
    assert.equal(made({ mark: 'pie' }, undefined, GROUPED).frozen, undefined,
      'told nothing of its grouping, it follows');
    assert.equal(made({ mark: 'bar', x: 'channel' }, undefined, GROUPED).frozen, true);
    assert.equal(made({ mark: 'bar', split: 'region' }, undefined, GROUPED).frozen, true);
    assert.equal(made({ mark: 'bar', x: 'channel', frozen: null }, undefined, GROUPED).frozen, undefined, 'told live');
    // a KPI follows its measure, not its trend; a scatter plots rows, and follows nothing
    assert.equal(made({ mark: 'kpi', x: 'day' }).frozen, undefined);
    assert.equal(made({ mark: 'kpi', y: [{ column: 'cost', fn: 'sum' }] }).frozen, true);
    assert.equal(made({ mark: 'scatter', x: 'revenue', y: [{ column: 'cost', fn: 'sum' }] }).frozen, undefined);
    // a dumbbell keeps its own two values as the grid regroups: told them, it still follows
    const ends = [{ column: 'cost', fn: 'sum' }, { column: 'revenue', fn: 'sum' }];
    assert.equal(made({ mark: 'dumbbell', y: ends }, undefined, GROUPED).frozen, undefined);
  });

  it('an edit keeps what it is not told; a new column across takes its own order; the grain goes with the old one', () => {
    const before = made({ mark: 'line', x: 'day', grain: 'month', split: 'channel', options: { labels: true } });
    const relabelled = made({ options: { legend: 'none' } }, before);
    assert.equal(relabelled.grain, 'month');
    assert.equal(relabelled.options.labels, true, 'the options it had stay');
    assert.equal(relabelled.options.legend, 'none');
    const moved = made({ x: 'region' }, before);
    assert.equal(moved.grain, undefined, 'the grain went with its column');
    assert.deepEqual(moved.options.sort, { by: 'y', direction: 'desc' }, 'a category leads with the largest');
    assert.equal(moved.split, 'channel');
  });

  it('null takes a slot or an option away; a chart with nothing to plot is refused', () => {
    const before = made({ mark: 'line', x: 'day', split: 'channel', options: { labels: true, zoom: true } });
    assert.equal(made({ split: null }, before).split, undefined);
    assert.equal(made({ options: { zoom: null } }, before).options.zoom, undefined);
    assert.match(String(chartFrom({ y: null }, CUBE, before)), /plots nothing/);
  });

  it('a word it does not know is refused by name, as a page refuses it', () => {
    assert.match(String(chartFrom({ mark: 'bar', x: 'region', options: { stack: 'stacked100' } }, CUBE)), /stack/);
    assert.match(String(chartFrom({ mark: 'barchart', x: 'region' }, CUBE)), /kind/);
    assert.match(String(chartFrom({ mark: 'bar', options: 'wide' }, CUBE)), /options/);
  });
});

describe('a change by hand (editChart: the charts\' form, and chartFrom)', () => {
  const GROUPED: CubeSnapshot = { ...CUBE, rows: ['region', 'channel'] };
  const start = defaultChart(GROUPED)!;

  it('the column across moved to a date takes the time order, as the grid regrouping it does', () => {
    const byDay = editChart(start, { x: 'day' }, GROUPED);
    assert.deepEqual(byDay.options.sort, { by: 'x', direction: 'asc' });
    assert.equal(byDay.options.limit, TIME_LIMIT);
    assert.equal(byDay.frozen, true, 'and freezes the live chart');
    assert.deepEqual(byDay.options.sort, followCube(start, { ...GROUPED, rows: ['day'] }).options.sort);
  });

  it('a live chart the grid has not moved stays the same chart (the form is not painted again for nothing)', () => {
    assert.equal(followCube(start, GROUPED), start);
  });
});
