import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { PIVOT_SEPARATOR } from '../../engine-client/src/generated/lite-facts.ts';
import { DEFAULT_CONFIGURATION, columnConfig, withColumn } from '../src/config.ts';
import {
  allColumns, columnKind, groupableColumns, measureAsColumn, measuresAsColumns, offeredAggregates, setColumn,
  setGrouping, setPivot, setSorts, type GridDraft,
} from '../src/grid-edits.ts';
import { AGGREGATE_FNS, CubeRefusal, pivotTotalColumn, type CubeSnapshot } from '../src/snapshot.ts';
import { element } from '../../pure-protocol/src/index.ts';
import { stand } from './fake-planner.ts';

// ONE WAY TO EDIT A GRID (docs/DATACUBE_ONE_GRID_EDIT_PATH_2026_10_17.md): the screen's offers, as one module the
// screen's panels, its header menu, its drag zones and Python's calls (headless gridEdit) all call.

const CUBE: CubeSnapshot = {
  source: { query: element('t') },
  columns: [
    { name: 'region', type: 'String' },
    { name: 'desk', type: 'String' },
    { name: 'year', type: 'Integer' },
    { name: 'notional', type: 'Float' },
    { name: 'qty', type: 'Integer' },
  ],
  derived: [
    { name: 'big', lambda: stand('$x.notional > 1'), kind: 'dimension', type: 'Boolean' },
    { name: 'fee', lambda: stand('$x.notional * 2'), kind: 'measure', type: 'Float' },
  ],
  groupDerived: [{ name: 'share', lambda: stand('$x.notional / 2'), type: 'Float' }],
  rows: ['region'],
  pivotOn: [],
  sorts: [],
  epoch: 1,
};

const draft = (s: Partial<CubeSnapshot> = {}, config = DEFAULT_CONFIGURATION): GridDraft =>
  ({ snapshot: { ...CUBE, ...s }, config });

const refused = (f: () => unknown, words: RegExp): void => {
  assert.throws(f, (e: unknown) => e instanceof CubeRefusal && words.test(e.message));
};

describe('the offers', () => {
  it('groups by dimensions: a source column by its kind as set, a calculated one by its editor\'s', () => {
    assert.deepEqual(groupableColumns(draft()), ['region', 'desk', 'big']);
    // Column Properties makes an integer a dimension
    const year = withColumn(DEFAULT_CONFIGURATION, 'year', { kind: 'dimension' });
    assert.deepEqual(groupableColumns(draft({}, year)), ['region', 'desk', 'year', 'big']);
    // a calculated column's kind set in Column Properties is no query's: its editor's stands
    const fee = withColumn(DEFAULT_CONFIGURATION, 'fee', { kind: 'dimension' });
    assert.equal(columnKind(draft({}, fee), 'fee'), 'measure');
    assert.equal(columnKind(draft(), 'share'), undefined);
  });

  it('lists every column, its calculated ones too', () => {
    assert.deepEqual(allColumns(draft()), ['region', 'desk', 'year', 'notional', 'qty', 'big', 'fee', 'share']);
  });

  it('offers a column the aggregates of its own family, every one before it is typed', () => {
    assert.ok(offeredAggregates('Float').includes('sum'));
    assert.ok(!offeredAggregates('String').includes('sum'));
    // a count is an Integer: a text column is not offered one (a group's rows are counted by "Show leaf count")
    assert.ok(!offeredAggregates('String').includes('count'));
    assert.deepEqual(offeredAggregates(undefined), [...AGGREGATE_FNS]);
  });
});

describe('grouping and pivoting', () => {
  it('sets dimensions, outermost first', () => {
    assert.deepEqual(setGrouping(draft(), ['region', 'desk']).snapshot.rows, ['region', 'desk']);
    assert.deepEqual(setPivot(draft(), ['big']).snapshot.pivotOn, ['big']);
  });

  it('refuses a measure, a column the grid has not, and one named twice, in the screen\'s words', () => {
    refused(() => setGrouping(draft(), ['notional']), /'notional' is a measure, and the row groups take dimensions/);
    refused(() => setPivot(draft(), ['nope']), /'nope' is not a column the pivot can take: .*region/);
    refused(() => setGrouping(draft(), ['desk', 'desk']), /the row groups name 'desk' twice/);
    // a group-level column exists only after grouping
    refused(() => setGrouping(draft(), ['share']), /'share' is not a column the row groups can take/);
  });

  it('keeps or takes away a group the grid has, whatever it is: the offers judge what is added', () => {
    // a saved cube can hold a key its offers would not add now (old Python grouped by any column)
    const held = draft({ rows: ['qty', 'region'] });
    assert.deepEqual(setGrouping(held, ['qty']).snapshot.rows, ['qty']);
    assert.deepEqual(setGrouping(held, ['qty', 'region', 'desk']).snapshot.rows, ['qty', 'region', 'desk']);
  });
});

describe('sorting', () => {
  it('sorts by a column the grid has, a calculated one too', () => {
    const s = setSorts(draft(), [{ column: 'notional', direction: 'desc' }, { column: 'share', direction: 'asc' }]);
    assert.deepEqual(s.snapshot.sorts, [{ column: 'notional', direction: 'desc' }, { column: 'share', direction: 'asc' }]);
  });

  it('pivoted, sorts by a pivot\'s header -- its values, then a column it spreads -- and a pivot total', () => {
    const pivoted = draft({ pivotOn: ['desk'] });
    const header = `FX${PIVOT_SEPARATOR}notional`;
    assert.deepEqual(setSorts(pivoted, [{ column: header, direction: 'asc' }]).snapshot.sorts,
      [{ column: header, direction: 'asc' }]);
    assert.deepEqual(setSorts(pivoted, [{ column: pivotTotalColumn('notional'), direction: 'desc' }]).snapshot.sorts,
      [{ column: pivotTotalColumn('notional'), direction: 'desc' }]);
    // not pivoted, a header is no column of the grid's
    refused(() => setSorts(draft(), [{ column: header, direction: 'asc' }]), /which the grid does not have/);
  });

  it('unpivoted, lets a pivot header\'s sort go with its headers, and keeps a column\'s', () => {
    const sorts = [{ column: `FX${PIVOT_SEPARATOR}notional`, direction: 'asc' as const },
      { column: 'notional', direction: 'desc' as const }];
    const pivoted = draft({ pivotOn: ['desk'], sorts });
    assert.deepEqual(setPivot(pivoted, ['region']).snapshot.sorts, sorts);
    assert.deepEqual(setPivot(pivoted, []).snapshot.sorts, [{ column: 'notional', direction: 'desc' }]);
  });

  it('refuses a column the grid has not, one named twice and a direction of no sort', () => {
    refused(() => setSorts(draft(), [{ column: 'nope', direction: 'asc' }]),
      /the sort names 'nope', which the grid does not have: one of its columns \(big, desk/);
    refused(() => setSorts(draft(), [{ column: 'desk', direction: 'asc' }, { column: 'desk', direction: 'desc' }]),
      /the sorts name 'desk' twice/);
    refused(() => setSorts(draft(), [{ column: 'desk', direction: 'up' as 'asc' }]), /a sort is 'asc' or 'desc', not 'up'/);
  });

  it('keeps a sort the grid has, and changes its direction, whatever it names', () => {
    const held = draft({ sorts: [{ column: `FX${PIVOT_SEPARATOR}notional`, direction: 'asc' }] });
    const flipped = setSorts(held, [{ column: `FX${PIVOT_SEPARATOR}notional`, direction: 'desc' }]);
    assert.deepEqual(flipped.snapshot.sorts, [{ column: `FX${PIVOT_SEPARATOR}notional`, direction: 'desc' }]);
  });
});

describe('a column\'s Column Properties', () => {
  it('sets a source column\'s kind, the pivot\'s exclusion with it, as the panel does', () => {
    const year = setColumn(draft(), 'year', { kind: 'dimension' });
    assert.deepEqual(columnConfig(year.config, 'year'), { kind: 'dimension', excludedFromPivot: true });
    const back = setColumn(year, 'year', { kind: 'measure' });
    assert.equal(columnConfig(back.config, 'year').excludedFromPivot, false);
  });

  it('refuses a kind the grid could not read: a calculated column\'s, a grouped column\'s, a word of no kind', () => {
    refused(() => setColumn(draft(), 'fee', { kind: 'dimension' }), /'fee' is a calculated column: its kind is set in its editor/);
    refused(() => setColumn(draft(), 'region', { kind: 'measure' }), /'region' is grouped or pivoted on/);
    refused(() => setColumn(draft(), 'qty', { kind: 'level' as 'measure' }), /a column's kind is 'dimension' or 'measure'/);
  });

  it('sets a measure\'s Aggregation, one offered for its type, and clears it', () => {
    const avg = setColumn(draft(), 'notional', { aggregate: 'average' });
    assert.equal(columnConfig(avg.config, 'notional').aggregateFn, 'average');
    // a row-level calculated measure is aggregated like any other
    assert.equal(columnConfig(setColumn(draft(), 'fee', { aggregate: 'max' }).config, 'fee').aggregateFn, 'max');
    const cleared = setColumn(avg, 'notional', { aggregate: null });
    assert.equal(columnConfig(cleared.config, 'notional').aggregateFn, undefined);
  });

  it('refuses an Aggregation of a dimension, of a group-level column, and one its type is not offered', () => {
    refused(() => setColumn(draft(), 'desk', { aggregate: 'joinStrings' }), /'desk' is a dimension, and only a measure takes an Aggregation/);
    refused(() => setColumn(draft(), 'share', { aggregate: 'sum' }), /'share' is a group-level column, computed after grouping/);
    // Column Properties makes desk a measure: then it is offered its own aggregates, never a sum
    const measure = setColumn(draft({ rows: [] }), 'desk', { kind: 'measure' });
    refused(() => setColumn(measure, 'desk', { aggregate: 'sum' }), /'sum' is not offered on desk, a String column/);
    assert.equal(columnConfig(setColumn(measure, 'desk', { aggregate: 'joinStrings' }).config, 'desk').aggregateFn,
      'joinStrings');
  });

  it('keeps the Aggregation a column has, offered or not: a cube saved elsewhere can carry one', () => {
    const held = withColumn(DEFAULT_CONFIGURATION, 'qty', { aggregateFn: 'joinStrings' });
    assert.equal(columnConfig(setColumn(draft({}, held), 'qty', { aggregate: 'joinStrings' }).config, 'qty').aggregateFn,
      'joinStrings');
  });

  it('takes a weight for a weighted average only, a column of the grid\'s', () => {
    const wavg = setColumn(draft(), 'notional', { aggregate: 'wavg', weight: 'qty' });
    assert.deepEqual(columnConfig(wavg.config, 'notional'), { aggregateFn: 'wavg', aggregationParameters: ['qty'] });
    refused(() => setColumn(draft(), 'notional', { weight: 'qty' }), /only a weighted average \(wavg\) takes a weight/);
    refused(() => setColumn(wavg, 'notional', { weight: 'nope' }), /notional's weight names 'nope', not a column/);
    const unweighted = setColumn(wavg, 'notional', { weight: null });
    assert.equal(columnConfig(unweighted.config, 'notional').aggregationParameters, undefined);
  });

  it('refuses a column the grid has not', () => {
    refused(() => setColumn(draft(), 'nope', { aggregate: 'sum' }), /'nope' is not a column of the grid/);
  });
});

describe('a measure as the screen\'s model (Python\'s grid.measure, a saved measures list, Open in Grid)', () => {
  it('is its own column\'s Aggregation, named as its column and the column no key', () => {
    const one = measureAsColumn(draft(), { name: 'notional', column: 'notional', fn: 'average' });
    assert.equal(columnConfig(one.draft.config, 'notional').aggregateFn, 'average');
    assert.deepEqual(one.made, { measure: { name: 'notional', column: 'notional', fn: 'average' }, column: 'notional' });
    assert.equal(one.draft.snapshot.derived.length, CUBE.derived.length, 'no column made');
    // a dimension made a measure first, as Column Properties would
    const desk = measureAsColumn(draft({ rows: [] }), { name: 'desk', column: 'desk', fn: 'joinStrings' });
    assert.deepEqual(columnConfig(desk.draft.config, 'desk'),
      { kind: 'measure', excludedFromPivot: false, aggregateFn: 'joinStrings' });
    assert.match(desk.note, /desk is now a measure/);
  });

  it('is a calculated column of its own name when named for another, or of a grouped column', () => {
    const named = measureAsColumn(draft(), { name: 'top', column: 'notional', fn: 'max' });
    const top = named.draft.snapshot.derived.at(-1);
    assert.deepEqual([top?.name, top?.kind, top?.type], ['top', 'measure', 'Float']);
    assert.match(JSON.stringify(top?.lambda), /"property":"notional"/);
    assert.equal(columnConfig(named.draft.config, 'top').aggregateFn, 'max');
    assert.equal(named.made.column, 'top');
    // a weighted average with its weight
    const w = measureAsColumn(draft(), { name: 'w', column: 'notional', fn: 'wavg', weight: 'qty' });
    assert.deepEqual(columnConfig(w.draft.config, 'w'), { aggregateFn: 'wavg', aggregationParameters: ['qty'] });
  });

  it('refuses, in the screen\'s words: a column the grid has not, a name taken, an aggregate its type is not offered', () => {
    refused(() => measureAsColumn(draft(), { name: 'n', column: 'nope', fn: 'sum' }), /'nope' is not a column of the grid/);
    refused(() => measureAsColumn(draft(), { name: 'desk', column: 'qty', fn: 'sum' }), /'desk' is already a column/);
    // region is grouped: its measure is a column of its own, and a count of text is not offered
    refused(() => measureAsColumn(draft(), { name: 'region', column: 'region', fn: 'count' }),
      /'count' is not offered on region, a String column: min, max, joinStrings, unique \(Show leaf count/);
    refused(() => measureAsColumn(draft(), { name: 'n', column: 'notional', fn: 'sum', weight: 'qty' }),
      /only a weighted average \(wavg\) takes a weight/);
  });

  it('makes a list in order, a column taken once, and leaves out what it cannot hold, saying why', () => {
    const made = measuresAsColumns(draft(), [
      { name: 'notional', column: 'notional', fn: 'sum' },
      { name: 'notional', column: 'notional', fn: 'max' },
      { name: 'peak', column: 'notional', fn: 'max' },
    ]);
    assert.deepEqual(made.made.map((m) => m.column), ['notional', 'peak']);
    assert.deepEqual(made.notes, [
      "measure notional is now notional's Aggregation, sum",
      "left out measure notional: 'notional' is already a column",
      'measure peak is now a calculated column of notional, its Aggregation max',
    ]);
  });
});
