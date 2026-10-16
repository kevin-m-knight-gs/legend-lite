// What Python's ll.Page writes (python/legend_lite/page.py; docs/DATACUBE_PYTHON_PAGES_DESIGN_2026_10_09.md), in
// DataCube's own words: its aggregates, sort directions and charts, the page's version, each as DataCube's sources
// spell them -- so a name added here is one Python offers too, and Python never writes one DataCube does not read
// (where it places a tile, what a grid's cube holds and what its filter takes, DataCube's own code says, headless). It
// reads Python's side as text (the two halves are in two languages), and DataCube's types as text, its values as they
// are.
//
// Pins retired, not loosened (2026-10-15, the twins program, PR neema2/legend-lite#47): each went with the Python copy
// it held equal, which now asks DataCube's own code headless instead -- BAND_HEIGHT, MAX_COLUMNS and SCREEN_BANDS with
// page.py's band editors (item 3, headless/layout.ts); CUBE_KIND and CUBE_VERSION with its cube writer (item 2,
// gridCube); FILTER_OPERATORS with its filter rules (item 6, gridFilter). The page's own kind (PAGE_KIND) has no pin:
// every page Python saves, writes or shows passes DataCube's reader (headless/page.ts), which refuses another kind.
// And the engine's planner kinds and snap limit (items 10 and 8), retired 2026-10-15 as no twins at all: a page asks a
// Python host on legend-engine's own routes and the host's compute (plan-and-run, plan), never by the tab's worker kinds,
// and the snap limit is Python's own, a fact of the database its copy lands in.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { Sources } from '../../tools/js/runfiles.mts';

// the sources this reads, as the BUILD target declares them (_SCANNED)
const SOURCES = new Sources('SOURCES', 'datacube');
const PAGE = '../python/legend_lite/page.py';

/** The quoted names of a TypeScript union type, `export type Name = 'a' | 'b' ...;`. */
function union(file: string, name: string): string[] {
  const text = SOURCES.read(file);
  const at = text.indexOf(`export type ${name} =`);
  assert.ok(at >= 0, `${file} declares ${name}`);
  const body = text.slice(at, text.indexOf(';', at));
  return [...body.matchAll(/'([A-Za-z]+)'/g)].map((m) => m[1]!).sort();
}

/** The quoted names of a Python constant in `file`: `NAME = frozenset({'a', 'b', ...})` or `NAME = ('a', 'b', ...)`. */
function python(name: string, file = PAGE): string[] {
  const text = SOURCES.read(file);
  const found = new RegExp(`^${name} = (frozenset\\(\\{|\\()`, 'm').exec(text);
  assert.ok(found, `${file} declares ${name}`);
  const body = text.slice(found.index, text.indexOf(found[1] === '(' ? ')' : '})', found.index));
  return [...body.matchAll(/'([A-Za-z][A-Za-z0-9]*)'/g)].map((m) => m[1]!).sort();
}

/** A number's digits as either language may write them, `_` between groups (`10_000_000`). */
const digits = (text: string | undefined): number => Number(text?.replaceAll('_', ''));

/** A numeric constant: `export const NAME = 0.5;` in TypeScript, `NAME = 0.5` in Python (page.py unless `file`). */
const tsNumber = (file: string, name: string): number =>
  digits(new RegExp(`export const ${name} = ([\\d_.]+);`).exec(SOURCES.read(file))?.[1]);
const pyNumber = (name: string, file = PAGE): number =>
  digits(new RegExp(`^${name} = ([\\d_.]+)$`, 'm').exec(SOURCES.read(file))?.[1]);

describe('Python writes pages in DataCube\'s own words', () => {
  it('its aggregates, sort directions and charts are the grid\'s and the chart\'s', () => {
    assert.deepEqual(python('AGGREGATES'), union('src/snapshot.ts', 'AggregateFn'));
    assert.deepEqual(python('DIRECTIONS'), union('src/snapshot.ts', 'SortDirection'));
    assert.deepEqual(python('MARKS'), union('src/chart-spec.ts', 'ChartMark'));
  });

  it('its chart slots (the spec\'s version 2) are the chart\'s words: marks, axes, show-as, grains, layers, stats', () => {
    assert.deepEqual(python('MEASURE_MARKS'), union('src/chart-spec.ts', 'MeasureMark'));
    assert.deepEqual(python('AXES'), union('src/chart-spec.ts', 'MeasureAxis'));
    assert.deepEqual(python('SHOW_AS'), union('src/chart-spec.ts', 'ShowAsKind'));
    assert.deepEqual(python('GRAINS'), union('src/chart-spec.ts', 'TimeGrain'));
    assert.deepEqual(python('LAYERS'), union('src/chart-spec.ts', 'ChartLayerKind'));
    assert.deepEqual(python('STATS'), union('src/chart-spec.ts', 'LayerStat'));
    assert.equal(pyNumber('CHART_VERSION'), tsNumber('src/chart-spec.ts', 'CHART_VERSION'));
  });

  it('its page version is the document\'s (a grid\'s cube document is DataCube\'s own: headless gridCube)', () => {
    assert.equal(pyNumber('PAGE_VERSION'), tsNumber('src/page-document.ts', 'PAGE_VERSION'));
  });
});
