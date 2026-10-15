// DataCube's chart rules, as cases (test/fixtures/chart-rules.json): each a chart over the fixture's columns, refused or
// not. The rules are written once, here in DataCube: a chart read as a page reads it (chart-spec.ts readChart), then
// judged (chartProblems). python/tests/test_page.py holds Python to the same answers, which Python gets by asking this
// code, headless (legend_lite/page.py _made) -- never by rules of its own (the review of 2d24022).

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

import { element } from '../../pure-protocol/src/index.ts';
import { chartFrom, chartProblems, DEFAULT_OPTIONS, readChart, TIME_LIMIT, type ChartSpec } from '../src/chart-spec.ts';
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

describe('DataCube\'s chart rules, case by case (Python asks the same code)', () => {
  for (const c of rules.cases) {
    it(`${c.case}: ${c.refused ? 'refused' : 'drawn'}`, () => {
      // read as a page reads it (a word it does not know refused by name; the options it leaves out its defaults)
      const read = readChart(c.spec);
      const problems = typeof read === 'string' ? [read] : chartProblems(read, CUBE);
      if (c.refused) assert.ok(problems.length > 0, 'refused');
      else assert.deepEqual(problems, []);
    });
  }
});

describe('a chart DataCube makes of what it is told (chartFrom: Python\'s grid.chart, Chart.plot and Chart.options)', () => {
  const made = (given: Record<string, unknown>, before?: ChartSpec): ChartSpec => {
    const spec = chartFrom(given, CUBE, before);
    if (typeof spec === 'string') throw new Error(spec);
    return spec;
  };

  it('what is not told is its own new chart\'s: a date across runs in time order, and long; a category leads with the largest', () => {
    const byDay = made({ mark: 'line', x: 'day' });
    assert.deepEqual(byDay.options.sort, { by: 'x', direction: 'asc' });
    assert.equal(byDay.options.limit, TIME_LIMIT);
    assert.deepEqual(made({ mark: 'bar', x: 'region' }).options, DEFAULT_OPTIONS);
    // told options win, the rest are the new chart's
    assert.deepEqual(made({ mark: 'line', x: 'day', options: { limit: 10 } }).options.sort, { by: 'x', direction: 'asc' });
    assert.equal(made({ mark: 'line', x: 'day', options: { limit: 10 } }).options.limit, 10);
  });

  it('its measure, untold, is the new chart\'s: a fraction first, summed; a count when the cube has no number', () => {
    assert.deepEqual(made({ mark: 'bar', x: 'region' }).y, [{ column: 'revenue', fn: 'sum' }]);
    const words: CubeSnapshot = { ...CUBE, columns: [{ name: 'region', type: 'String' }] };
    const counted = chartFrom({ mark: 'bar', x: 'region' }, words);
    assert.ok(typeof counted !== 'string');
    assert.deepEqual(counted.y, [{ column: 'region', fn: 'count' }]);
  });

  it('an edit keeps what it is not told; a grain or bins go with the column they were chosen for; null takes a slot away', () => {
    const before = made({ mark: 'line', x: 'day', grain: 'month', split: 'channel', options: { labels: true } });
    const relabelled = made({ options: { legend: 'none' } }, before);
    assert.equal(relabelled.grain, 'month');
    assert.equal(relabelled.options.labels, true, 'the options it had stay');
    assert.equal(relabelled.options.legend, 'none');
    const moved = made({ x: 'region' }, before);
    assert.equal(moved.grain, undefined, 'the grain went with its column');
    assert.equal(moved.split, 'channel');
    assert.equal(made({ split: null }, before).split, undefined);
    assert.deepEqual(made({ y: null }, { ...before, y: [{ column: 'cost', fn: 'max' }] }).y, [{ column: 'revenue', fn: 'sum' }],
      'y told as null is the new chart\'s measure again');
  });

  it('a word it does not know is refused by name, as a page refuses it', () => {
    assert.match(String(chartFrom({ mark: 'bar', x: 'region', options: { stack: 'stacked100' } }, CUBE)), /stack/);
    assert.match(String(chartFrom({ mark: 'barchart', x: 'region' }, CUBE)), /kind/);
  });
});
