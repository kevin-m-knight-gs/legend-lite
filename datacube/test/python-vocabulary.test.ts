// What Python's ll.Page writes (python/legend_lite/page.py; docs/DATACUBE_PYTHON_PAGES_DESIGN_2026_10_09.md), in
// DataCube's own words: its filter operators, aggregates, sort directions and charts, the page's version, each as
// DataCube's sources spell them -- so a name added here is one Python offers too, and Python never writes one DataCube
// does not read (where it places a tile, and what a grid's cube holds, DataCube's own code says, headless). And what
// Python's engine answers a page with (python/legend_lite/engine.py, _planner.py): the planner protocol's own lists
// and the tab's snap limit (docs/PYTHON_ASKS_DATACUBE_2026_10_15.md, items 10 and 8). It reads Python's side as text
// (the two halves are in two languages), and DataCube's types as text, its values as they are.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { Sources } from '../../tools/js/runfiles.mts';
import { HELD_KINDS, PAGE_KINDS } from '../../engine-client/src/legend/planner-protocol.ts';
import { MAX_SNAP_ROWS } from '../../engine-client/src/snap.ts';

// the sources this reads, as the BUILD target declares them (_SCANNED)
const SOURCES = new Sources('SOURCES', 'datacube');
const PAGE = '../python/legend_lite/page.py';
const ENGINE = '../python/legend_lite/engine.py';
const PLANNER = '../python/legend_lite/_planner.py';

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
  it('its filter operators are the filter editor\'s', () => {
    const operators = union('src/snapshot.ts', 'FilterOperator');
    assert.ok(operators.length > 20, 'the scan found them');
    assert.deepEqual(python('FILTER_OPERATORS'), operators);
  });

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

describe('Python answers a page by DataCube\'s own lists and limits', () => {
  it('the kinds a page sends it are the protocol\'s PAGE_KINDS', () => {
    assert.ok(PAGE_KINDS.includes('pureV1'), 'the protocol lists them');
    assert.deepEqual(python('_PAGE_KINDS', ENGINE), [...PAGE_KINDS].sort());
  });

  it('the most rows its Snap copies is the tab\'s', () => {
    assert.ok(MAX_SNAP_ROWS > 0, 'snap.ts declares it');
    assert.equal(pyNumber('_MAX_SNAP_ROWS', ENGINE), MAX_SNAP_ROWS);
  });

  it('the kinds it plans against a model held are the protocol\'s HELD_KINDS', () => {
    assert.ok(HELD_KINDS.length > 0, 'the protocol lists them');
    assert.deepEqual(python('HELD', PLANNER), [...HELD_KINDS].sort());
  });
});
