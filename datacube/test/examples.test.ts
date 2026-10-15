// The example pages (docs/DATACUBE_CHARTS_DESIGN_2026_10_09.md §3.3), read back as a saved page is read: each opens,
// every view sits on a sheet, and every chart on it is one its cube can draw. (chart-queries.test.ts plans and runs each
// chart's query on the sample itself.)

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { EXAMPLE_PAGES } from '../src/examples/catalog.ts';
import { EXAMPLE_CUBE, SALES_SPECS } from '../src/examples/charts-dashboard.ts';
import { chartProblems } from '../src/chart-spec.ts';
import { pageToJson, readPage } from '../src/page-document.ts';
import { tiles } from '../src/layout/bands.ts';
import { SALES_COLUMNS } from '../src/samples.ts';

describe('the example pages', () => {
  for (const example of EXAMPLE_PAGES) {
    it(`${example.label}: opens as a saved page, every view on a sheet, every chart drawable`, async () => {
      const page = readPage(pageToJson(await example.build()));
      assert.ok(page.sheets.length >= 2, 'a sheet per family, and the grid');
      const placed = page.sheets.flatMap((s) => tiles(s.layout));
      assert.deepEqual([...placed].sort(), page.views.map((v) => v.id).sort(), 'every view once, on one sheet');
      const charts = page.views.filter((v) => v.kind === 'chart');
      assert.ok(charts.length >= 20, `simple to complex: ${charts.length} charts`);
      for (const v of charts) {
        if (v.kind !== 'chart') continue;
        assert.deepEqual(chartProblems(v.spec, EXAMPLE_CUBE), [], `${v.title}`);
      }
      // the sample it is drawn from regenerates on opening
      const source = page.cubes[0]!.cube.source;
      assert.ok(source._type === 'file' && source.sample?.id === 'sales');
    });
  }

  it('the sample\'s columns are the page\'s, in order', () => {
    assert.deepEqual(SALES_SPECS.map((c) => c.name), [...SALES_COLUMNS]);
  });
});
