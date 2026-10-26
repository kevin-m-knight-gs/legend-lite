// A TIMESERIES TILE AS THE CODE THAT BUILDS IT (src/timeseries-code.ts; the user, 2026-10-26: "an option that shows how
// exactly to build that chart programmatically from TS and python"): every tile of the timeseries gallery written as
// TypeScript, and the program imported and run -- the spec it writes is the tile's, its command adds the tile to the page
// as the screen's Create ▸ Timeseries does, on its sheet, and its questions are the Query window's. (Python's, run, is
// test_page.py's; not a headless test: it writes the program to a file and imports it.)

import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { describe, it } from 'node:test';

import { timeseriesGalleryPage } from '../src/examples/timeseries-gallery.ts';
import { layoutTiles, pageToJson, readPage, type PageDocument } from '../src/page-document.ts';
import { codeOfPage, tileCode } from '../src/timeseries-code.ts';
import { exprSource } from '../src/timeseries-expr.ts';
import { tileAsks } from '../src/timeseries-query.ts';
import type { TimeseriesSpec } from '../src/timeseries-spec.ts';

describe('a timeseries tile as the code that builds it', () => {
  it('the timeseries gallery as code: each tile\'s TypeScript, run, builds the tile it was written of', async () => {
    const page = readPage(pageToJson(await timeseriesGalleryPage()));
    const src = new URL('../src/', import.meta.url).href;
    const dir = mkdtempSync(join(process.env['TEST_TMPDIR'] ?? tmpdir(), 'ts-code-'));
    const all = page.views.flatMap((v) => (v.kind === 'timeseries' ? [v] : []));
    assert.ok(all.length >= 30);
    for (const v of all) {
      const { spec, of } = codeOfPage(page, v.id);
      const code = tileCode(spec, of, 'typescript');
      assert.ok(code.includes("kind: 'addTimeseries'"), v.id);
      const file = join(dir, `${v.id}.ts`);
      writeFileSync(file, code.replaceAll("from './datacube/src/", `from '${src}`));
      const made = await import(pathToFileURL(file).href) as { spec: TimeseriesSpec; withTile: (p: PageDocument) => PageDocument; questions: string[] };
      assert.deepEqual(made.spec, spec, `${v.id}: the spec its program writes is the tile's`);
      const added = made.withTile(page);
      const fresh = added.views.find((x) => !page.views.some((y) => y.id === x.id));
      assert.ok(fresh?.kind === 'timeseries', `${v.id}: a tile added`);
      assert.deepEqual(fresh.spec, spec, `${v.id}: the tile it adds`);
      assert.equal(fresh.title, v.title, `${v.id}: titled as it is`);
      const sheet = added.sheets.find((x) => layoutTiles(x.layout).includes(fresh.id));
      assert.equal(sheet?.id, of.sheet, `${v.id}: on its sheet`);
      assert.deepEqual(made.questions, tileAsks(spec).map((a) => a.what), `${v.id}: the Query window's questions`);
      // and its Python names its sheet, each grid it reads, and each series' expression as the catalog writes it
      const python = tileCode(spec, of, 'python');
      assert.ok(python.includes(`page.sheets if s.id == '${of.sheet}'`), v.id);
      for (const s of spec.series) assert.ok(python.includes(`'${exprSource(s.expr).replace(/'/g, "\\'")}'`), `${v.id} ${s.id}: ${exprSource(s.expr)}`);
    }
  });

});
