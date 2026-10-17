// The saved cube (src/cube-document.ts): written whole, read whole, versioned, unknown
// fields kept, defaults not written down, and reconciled with its file on open.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  CUBE_KIND,
  CUBE_VERSION,
  CubeDocumentError,
  cubeToJson,
  derivedReads,
  difference,
  fileSource,
  gridCapped,
  merge,
  openCube,
  readCube,
  rebuilt,
  sha256,
  writeCube,
  type CubeDocument,
  type FileSource,
  type QuerySource,
  type FrameSource,
  type RemoteSource,
  type WarehouseSource,
} from '../src/cube-document.ts';
import { DEFAULT_CONFIGURATION, DEFAULT_MAX_ROWS, fromSnapshot, type CubeConfiguration } from '../src/config.ts';
import type { ColumnSpec, CubeSnapshot, Measure } from '../src/snapshot.ts';
import { TreeState } from '../src/tree.ts';
import { accessor, col, lambda, lit, times } from '../../pure-protocol/src/index.ts';

const SOURCE: FileSource = {
  _type: 'file', name: 'trades.csv', format: 'csv', size: 120, sha256: 'ab'.repeat(32),
  columns: [
    { name: 'region', type: 'String' }, { name: 'desk', type: 'String' },
    { name: 'notional', type: 'Float' }, { name: 'qty', type: 'Integer' },
  ],
};
const RELATION = { query: accessor('local::DB', 'trades') };

/**
 * EVERY field a snapshot has, set: `Required<>` makes the compiler refuse this fixture the
 * day a field is added, so a new field cannot be forgotten by the reader (the old saved
 * view's allow-list dropped five, P2-71).
 */
const EVERY: Required<CubeSnapshot> = {
  source: RELATION,
  columns: [
    { name: 'region', type: 'String', kind: 'dimension' },
    { name: 'desk', type: 'String' },
    { name: 'notional', type: 'Float', aggregate: 'average' },
    { name: 'qty', type: 'Integer', excludedFromPivot: true },
  ],
  derived: [{
    name: 'scaled', type: 'Decimal',
    lambda: lambda(['x'], times(col('x', 'notional'), lit.decimal('12.30'))),
  }],
  groupDerived: [{ name: 'doubled', lambda: lambda(['x'], times(col('x', 'notional'), lit.integer(2))) }],
  filter: {
    kind: 'and',
    children: [
      { kind: 'condition', column: 'region', operator: 'equal', value: 'EMEA' },
      { kind: 'not', child: { kind: 'condition', column: 'qty', operator: 'greaterThan', value: 3 } },
    ],
  },
  rows: ['region'],
  pivotOn: ['desk'],
  pivotValues: ['Rates', 'Credit'],
  pivotSort: { desk: 'desc' },
  pivotTotal: { placement: 'left', functions: { notional: 'max' } },
  keepGroupedColumns: true,
  leafCount: true,
  childCount: true,
  sorts: [{ column: 'region', direction: 'desc' }],
  window: { offset: 50, limit: 100 },
  treeColumnSort: 'desc',
  maxRows: 777,
  epoch: 42,
};

const { pivotStatisticColumnPlacement: _total, ...WITHOUT_TOTAL } = DEFAULT_CONFIGURATION;
const CONFIG: CubeConfiguration = {
  ...WITHOUT_TOTAL,
  reportTitle: 'Q3 review',
  maxRows: 777,
  columns: { notional: { format: { kind: 'number', decimals: 3 } }, desk: { hidden: true } },
};

const doc = () => writeCube({
  name: 'Q3 review', source: SOURCE, snapshot: EVERY, configuration: CONFIG,
  tree: TreeState.fromPaths([['EMEA'], [null]], true),
});

describe('writing and reading a cube', () => {
  it('reads back every field of the cube, exactly, through JSON text', () => {
    const back = readCube(cubeToJson(doc()));
    const { source: _s, epoch: _e, window: _w, ...query } = EVERY;
    assert.deepEqual(JSON.parse(cubeToJson(back)).query, JSON.parse(cubeToJson(doc())).query);
    assert.deepEqual(Object.keys(back.query).sort(), Object.keys(query).sort());
    // the decimal literal keeps its digits
    assert.match(cubeToJson(back), /"12\.30"|12\.30/);
    assert.equal(back.name, 'Q3 review');
    assert.deepEqual(back.source, SOURCE);
    assert.deepEqual(back.tree, { open: [['EMEA'], [null]], showTotals: true });
  });

  it('writes no data, no relation, and no runtime state', () => {
    const d = doc();
    assert.equal('source' in d.query, false);
    assert.equal('epoch' in d.query, false);
    assert.equal('window' in d.query, false, 'where the grid was scrolled is not the cube');
  });

  it('writes only what differs from the product defaults', () => {
    const c = doc().configuration;
    assert.equal(c['reportTitle'], 'Q3 review');
    assert.equal(c['maxRows'], 777);
    assert.equal(c['showTitleBar'], undefined, 'a default is not written down');
    assert.equal(c['pivotStatisticColumnPlacement'], null, 'an unset default is written as null');
    assert.deepEqual(merge(DEFAULT_CONFIGURATION, c), JSON.parse(JSON.stringify(CONFIG)));
  });

  it('keeps fields it does not know, and writes them back', () => {
    const text = cubeToJson(doc()).replace('{', '{"futureSetting":{"a":1},');
    const back = readCube(text);
    assert.deepEqual(back.unknown, { futureSetting: { a: 1 } });
    assert.match(cubeToJson(back), /"futureSetting":\{"a":1\}/);
  });

  it('refuses what it cannot read, saying why', () => {
    assert.throws(() => readCube('{ not json'), /not valid JSON/);
    assert.throws(() => readCube('{"kind":"other"}'), /not a saved cube/);
    assert.throws(() => readCube('{"query":"select(~[a])","source":{}}'), /legacy DataCube specification/);
    assert.throws(() => readCube(cubeToJson({ ...doc(), version: CUBE_VERSION + 1 })), /newer version/);
    const noSource = JSON.parse(cubeToJson(doc()));
    noSource.source = { _type: 'pointer' };
    assert.throws(() => readCube(noSource), CubeDocumentError);
    assert.equal(doc().kind, CUBE_KIND);
  });
});

describe('a cube over a saved query', () => {
  // the query itself, as a share link carries it (query-store sharedPart): no store identity
  const QUERY_SOURCE: QuerySource = {
    _type: 'savedQuery',
    name: 'Sells',
    query: {
      name: 'Sells', groupId: 'demo', artifactId: 'trading', versionId: '0.0.0',
      executionContext: { _type: 'dataSpaceExecutionContext', dataSpacePath: 'demo::trading::TradingDataSpace', executionKey: 'Production' },
      content: "|demo::trading::Trade.all()->project(~[side: x|$x.side])",
      defaultParameterValues: [],
    },
    columns: [{ name: 'side', type: 'String' }],
  };
  const over = () => writeCube({
    name: 'Sells by side', source: QUERY_SOURCE, snapshot: EVERY, configuration: CONFIG, tree: TreeState.fromPaths([]),
  });

  it('writes the query as its source, and reads it back exactly', () => {
    const back = readCube(cubeToJson(over()));
    assert.deepEqual(back.source, QUERY_SOURCE);
    assert.doesNotMatch(JSON.stringify(JSON.parse(cubeToJson(over())).source), /"id"|"owner"|"lastUpdatedAt"|"version"/, 'the store identity is not the query');
  });

  it('refuses an incomplete saved query source, naming what is missing', () => {
    const noContent = JSON.parse(cubeToJson(over()));
    delete noContent.source.query.content;
    assert.throws(() => readCube(noContent), /the saved query source has no content/);
    const noColumns = JSON.parse(cubeToJson(over()));
    delete noColumns.source.columns;
    assert.throws(() => readCube(noColumns), /saved query source is incomplete/);
  });
});

describe('a cube over a warehouse table, or a remote file: where it is, never how to get in', () => {
  const TABLE: WarehouseSource = {
    _type: 'warehouseTable', name: 'sales.orders', warehouse: 'https://warehouse.example.com', catalog: 'shop',
    schema: 'sales', table: 'orders', columns: [{ name: 'region', type: 'String' }],
  };
  const REMOTE: RemoteSource = {
    _type: 'remoteFile', name: 'trades.parquet', url: 'https://data.example.com/trades.parquet', columns: [{ name: 'qty', type: 'Integer' }],
  };
  const over = (source: WarehouseSource | RemoteSource) => writeCube({
    name: 'Orders', source, snapshot: EVERY, configuration: CONFIG, tree: TreeState.fromPaths([]),
  });

  it('writes each down and reads it back exactly', () => {
    assert.deepEqual(readCube(cubeToJson(over(TABLE))).source, TABLE);
    assert.deepEqual(readCube(cubeToJson(over(REMOTE))).source, REMOTE);
  });

  it('reads a warehouse table saved before catalogs were named as main, the only catalog then', () => {
    const before = JSON.parse(cubeToJson(over(TABLE)));
    delete before.source.catalog;
    assert.equal((readCube(before).source as WarehouseSource).catalog, 'main');
    const empty = JSON.parse(cubeToJson(over(TABLE)));
    empty.source.catalog = '';
    assert.throws(() => readCube(empty), /empty catalog/);
  });

  it('refuses one missing a part, or one carrying a credential', () => {
    const noTable = JSON.parse(cubeToJson(over(TABLE)));
    delete noTable.source.table;
    assert.throws(() => readCube(noTable), /the warehouse table source has no table/);
    const withToken = JSON.parse(cubeToJson(over(TABLE)));
    withToken.source.token = 'abc';
    assert.throws(() => readCube(withToken), /carries a credential/);
    const withSecret = JSON.parse(cubeToJson(over(REMOTE)));
    withSecret.source.secretAccessKey = 'shh';
    assert.throws(() => readCube(withSecret), /carries a credential/);
    const noUrl = JSON.parse(cubeToJson(over(REMOTE)));
    delete noUrl.source.url;
    assert.throws(() => readCube(noUrl), /the remote file source has no url/);
  });
});

describe('a cube over a dataframe: by its name on the engine that serves it, never its rows', () => {
  const FRAME: FrameSource = { _type: 'frame', name: 'trades', columns: [{ name: 'qty', type: 'Integer' }] };
  const over = (source: FrameSource) => writeCube({
    name: 'Trades', source, snapshot: EVERY, configuration: CONFIG, tree: TreeState.fromPaths([]),
  });

  it('writes it down and reads it back exactly', () => {
    assert.deepEqual(readCube(cubeToJson(over(FRAME))).source, FRAME);
  });

  it('refuses one whose name is not a frame\'s (a plain identifier), or with no columns', () => {
    const named = JSON.parse(cubeToJson(over(FRAME)));
    named.source.name = 'trades; drop';
    assert.throws(() => readCube(named), /the frame source has no name/);
    const noColumns = JSON.parse(cubeToJson(over(FRAME)));
    delete noColumns.source.columns;
    assert.throws(() => readCube(noColumns), /the frame source has no columns/);
  });
});

describe('opening a cube over its file as it is now', () => {
  const now = (cols: [string, string][]): ColumnSpec[] => cols.map(([name, type]) => ({ name, type }));

  it('opens unchanged when the file holds what it held', () => {
    const opened = openCube(readCube(cubeToJson(doc())), RELATION,
      now([['region', 'String'], ['desk', 'String'], ['notional', 'Float'], ['qty', 'Integer']]));
    assert.deepEqual(opened.notes, []);
    assert.equal(opened.changed, false);
    assert.deepEqual(opened.snapshot.rows, ['region']);
    assert.equal(opened.snapshot.columns.find((c) => c.name === 'notional')?.aggregate, 'average',
      'what the cube set on a column is kept');
    assert.equal(opened.configuration.maxRows, 777);
    assert.equal(opened.tree.isOpen(['EMEA']), true);
  });

  it('hides a new column and says so', () => {
    const opened = openCube(doc(), RELATION,
      now([['region', 'String'], ['desk', 'String'], ['notional', 'Float'], ['qty', 'Integer'], ['book', 'String']]));
    assert.equal(opened.configuration.columns['book']?.hidden, true);
    assert.match(opened.notes.join('\n'), /1 new column .*book/);
    assert.equal(opened.changed, false);
  });

  it('leaves out every part that uses a column that is gone, naming each', () => {
    const opened = openCube(doc(), RELATION,
      now([['region', 'String'], ['desk', 'String'], ['qty', 'Integer']]));
    const text = opened.notes.join('\n');
    assert.match(text, /no longer in the file: notional/);
    assert.match(text, /left out calculated column scaled \(it uses notional\)/);
    assert.match(text, /left out calculated column doubled/);
    assert.equal(opened.changed, true);
    assert.deepEqual(opened.snapshot.derived, []);
    assert.deepEqual(opened.snapshot.rows, ['region'], 'what does not use it stays');
    assert.ok(opened.snapshot.filter, 'the filter on region and qty stays');
  });

  it('drops a filter condition on a gone column and keeps the rest', () => {
    const opened = openCube(doc(), RELATION,
      now([['region', 'String'], ['desk', 'String'], ['notional', 'Float']]));
    assert.deepEqual(opened.snapshot.filter,
      { kind: 'condition', column: 'region', operator: 'equal', value: 'EMEA' });
    assert.match(opened.notes.join('\n'), /left out the filter on qty/);
  });

  it('reports a changed type and takes the compiler\'s', () => {
    const opened = openCube(doc(), RELATION,
      now([['region', 'String'], ['desk', 'String'], ['notional', 'Decimal'], ['qty', 'Integer']]));
    assert.match(opened.notes.join('\n'), /notional is now Decimal \(it was Float\)/);
    assert.equal(opened.snapshot.columns.find((c) => c.name === 'notional')?.type, 'Decimal');
  });

  it('closes the open rows when the grouping changed', () => {
    const opened = openCube(doc(), RELATION, now([['desk', 'String'], ['notional', 'Float'], ['qty', 'Integer']]));
    assert.deepEqual(opened.snapshot.rows, []);
    assert.equal(opened.tree.isOpen(['EMEA']), false);
  });

  // A CUBE SAVED WITH A MEASURES LIST (before 2026-10-17; a grid's aggregates are its Column Properties now,
  // docs/DATACUBE_ONE_GRID_EDIT_PATH_2026_10_17.md): opened, each measure is made its column's Aggregation or a calculated
  // column of its own (grid-edits.ts measuresAsColumns), said; one that cannot be is left out, said -- never refused at
  // every level, and never kept as a list no panel shows.
  const legacy = (measures: readonly Measure[], rows: readonly string[] = ['region']): CubeDocument => {
    const written = writeCube({
      name: 'old', source: SOURCE,
      snapshot: { source: RELATION, columns: SOURCE.columns, derived: [], rows, pivotOn: [], sorts: [], epoch: 1 },
      configuration: DEFAULT_CONFIGURATION, tree: TreeState.fromPaths([['EMEA']], false),
    });
    return readCube(cubeToJson({ ...written, query: { ...written.query, measures } }));
  };
  const today = now([['region', 'String'], ['desk', 'String'], ['notional', 'Float'], ['qty', 'Integer']]);

  it('opens a saved measures list as Column Properties: its own column\'s Aggregation, else a calculated column', () => {
    const opened = openCube(legacy([
      { name: 'notional', column: 'notional', fn: 'average' },
      { name: 'top_qty', column: 'qty', fn: 'max' },
      { name: 'w', column: 'notional', fn: 'wavg', weight: 'qty' },
    ]), RELATION, today);
    assert.deepEqual(opened.notes, [
      "measure notional is now notional's Aggregation, average",
      'measure top_qty is now a calculated column of qty, its Aggregation max',
      'measure w is now a calculated column of notional, its Aggregation wavg',
    ]);
    assert.equal(opened.changed, true, 'the cube held is not the one saved: Python\'s page takes it');
    assert.equal('measures' in opened.snapshot, false);
    assert.equal(opened.configuration.columns['notional']?.aggregateFn, 'average');
    assert.deepEqual(opened.snapshot.derived.map((d) => [d.name, d.kind]), [['top_qty', 'measure'], ['w', 'measure']]);
    assert.equal(opened.configuration.columns['top_qty']?.aggregateFn, 'max');
    assert.deepEqual(opened.configuration.columns['w'], { aggregateFn: 'wavg', aggregationParameters: ['qty'] });
    assert.equal(opened.tree.isOpen(['EMEA']), true, 'the grouping is the one saved: its open rows stay');
    // written again, no list: the cube document a grid writes carries none
    const again = writeCube({ name: 'old', source: SOURCE, snapshot: opened.snapshot, configuration: opened.configuration,
      tree: opened.tree });
    assert.equal('measures' in again.query, false);
  });

  it('leaves out, saying why, a saved measure the screen\'s model cannot hold', () => {
    // old Python's grid.group('region').measure('region', 'count'), a second measure of a name another has, a sum of
    // text, and a measure of a column the source no longer has
    const opened = openCube(legacy([
      { name: 'region', column: 'region', fn: 'count' },
      { name: 'notional', column: 'notional', fn: 'sum' },
      { name: 'notional', column: 'qty', fn: 'max' },
      { name: 'names', column: 'desk', fn: 'sum' },
      { name: 'gone', column: 'pnl', fn: 'sum' },
    ]), RELATION, today);
    assert.deepEqual(opened.notes, [
      "left out measure region: 'count' is not offered on region, a String column: min, max, joinStrings, unique "
        + "(Show leaf count counts a group's rows)",
      "measure notional is now notional's Aggregation, sum",
      "left out measure notional: 'notional' is already a column",
      "left out measure names: 'sum' is not offered on desk, a String column: min, max, joinStrings, unique",
      "left out measure gone: 'pnl' is not a column of the grid (desk, notional, qty, region)",
    ]);
    assert.equal(opened.changed, true);
    assert.deepEqual(opened.snapshot.derived, []);
  });

  it('folds what it converted, so an opener that reads the query back keeps it (fromSnapshot: app.ts, the headless grid)', () => {
    const saved = legacy([{ name: 'notional', column: 'notional', fn: 'average' }, { name: 'desk', column: 'desk', fn: 'max' }], []);
    // saved from the screen with notional's Aggregation sum and desk a dimension: folded into its query, as a save folds them
    const columns = saved.query.columns.map((c) => (c.name === 'notional' ? { ...c, aggregate: 'sum' as const }
      : c.name === 'desk' ? { ...c, kind: 'dimension' as const } : c));
    const opened = openCube({ ...saved, query: { ...saved.query, columns } }, RELATION, today);
    const read = fromSnapshot(opened.snapshot, opened.configuration);
    assert.equal(read.columns['notional']?.aggregateFn, 'average', 'the converted Aggregation, not the saved query\'s sum');
    assert.deepEqual([read.columns['desk']?.kind, read.columns['desk']?.aggregateFn], ['measure', 'max']);
  });

  it('leaves out a sort of a measure it left out, saying so: the grid would sort by a column it has not', () => {
    const saved = legacy([{ name: 'trades', column: 'desk', fn: 'count' }], []);
    const sorts = [{ column: 'trades', direction: 'desc' as const }, { column: 'qty', direction: 'asc' as const }];
    const opened = openCube({ ...saved, query: { ...saved.query, sorts } }, RELATION, today);
    assert.deepEqual(opened.snapshot.sorts, [{ column: 'qty', direction: 'asc' }]);
    assert.ok(opened.notes.includes('left out sorting by trades'), opened.notes.join('; '));
  });

  it('opens a cube saved with an empty measures list as it was', () => {
    const opened = openCube(legacy([]), RELATION, today);
    assert.deepEqual(opened.notes, []);
    assert.equal(opened.changed, false);
  });
});

describe('the pieces', () => {
  it('knows which columns a calculated column reads', () => {
    assert.deepEqual([...derivedReads(EVERY.derived[0]!)], ['notional']);
    assert.deepEqual([...derivedReads({ name: 'r', window: { fn: 'sum', column: 'qty', partition: ['region'], order: [{ column: 'desk', direction: 'asc' }] } })].sort(),
      ['desk', 'qty', 'region']);
  });

  it('fingerprints a file by its bytes', async () => {
    assert.equal(await sha256(new TextEncoder().encode('abc').buffer as ArrayBuffer),
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
    const src = await fileSource({ name: 'a.csv', size: 3, arrayBuffer: async () => new TextEncoder().encode('abc').buffer as ArrayBuffer },
      'csv', [{ name: 'a', type: 'String' }], { id: 'trades', rows: 10 });
    assert.equal(src.sha256.slice(0, 8), 'ba7816bf');
    assert.deepEqual(src.sample, { id: 'trades', rows: 10 });
  });

  it('differences and merges are inverses', () => {
    const base = { a: 1, b: { c: 2, d: 3 }, e: [1, 2], f: 'x' };
    const value = { a: 1, b: { c: 5, d: 3 }, e: [1, 2, 3] };
    const d = difference(value, base);
    assert.deepEqual(d, { b: { c: 5 }, e: [1, 2, 3], f: null });
    assert.deepEqual(merge(base, d), value);
  });
});

describe("a new grid is capped when it is not its page's only grid (gridCapped, counting it)", () => {
  const fresh = (gridsOnPage: number) =>
    rebuilt(undefined, accessor('db', 'T'), [{ name: 'a', type: 'Integer' }], 'T', gridCapped(gridsOnPage)).configuration;

  it("a page's only grid is not capped: a new page, a source opened in place, a reopened page of one", () => {
    assert.equal(gridCapped(1), false);
    assert.equal(fresh(1).maxRows, undefined);
  });

  it('the first grid added beside it is capped at the row limit, as is every grid of a reopened page of several', () => {
    assert.equal(gridCapped(2), true);
    assert.equal(gridCapped(3), true);
    assert.equal(fresh(2).maxRows, DEFAULT_MAX_ROWS);
  });
});
