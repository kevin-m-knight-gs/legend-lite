// DataCube's chart rules, one description (test/fixtures/chart-rules.json): each case a chart over the fixture's
// columns, refused or not. This holds chart-spec.ts chartProblems to it; python/tests/test_page.py holds Python's
// page.py _drawable to the same cases, so a rule added on one side and not the other fails one of the two (the DataCube +
// Python line's review of batch 3).

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

import { element } from '../../pure-protocol/src/index.ts';
import { chartProblems, DEFAULT_OPTIONS, type ChartSpec } from '../src/chart-spec.ts';
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

describe('the chart rules DataCube and Python share', () => {
  for (const c of rules.cases) {
    it(`${c.case}: ${c.refused ? 'refused' : 'drawn'}`, () => {
      // a saved spec leaves out the options it does not set: the reader gives them their defaults
      const problems = chartProblems({ ...c.spec, options: { ...DEFAULT_OPTIONS, ...c.spec.options } }, CUBE);
      if (c.refused) assert.ok(problems.length > 0, 'refused');
      else assert.deepEqual(problems, []);
    });
  }
});
