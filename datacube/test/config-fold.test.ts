// The configuration folded into the grid's query and read back (src/config.ts applyToSnapshot, fromSnapshot): the pivot
// total, a pivot key's sort, the row cap, a column kept out of the pivot, grouped columns kept beside the tree -- each
// set, then CLEARED, and the query following both ways; a host's patch clearing what it is told; and what the grid is
// handed (toColumnLayout, renderFormats, leafFormats) for the settings the other tests leave out.
//
// No planner: everything here is the configuration and the snapshot, as data. A state the owner commits is a FOLDED one
// (state-owner.ts: `rules.fold(update(base))`), so a change is folded into a query that already carries the last fold --
// which is where a setting cleared and left behind shows.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  DEFAULT_CONFIGURATION,
  applyToSnapshot,
  columnConfig,
  fromSnapshot,
  leafFormats,
  renameColumnConfig,
  renderFormats,
  resolvedWidths,
  toColumnLayout,
  toFormats,
  withAppearance,
  withColumn,
  withConfiguration,
  withSettings,
  type CubeConfiguration,
  type Patch,
} from '../src/config.ts';
import { excludedFromPivot, type CubeSnapshot } from '../src/snapshot.ts';
import { element } from '../../pure-protocol/src/index.ts';

/** A cube pivoted on year, grouped by region: notional and qty its measures. */
const CUBE: CubeSnapshot = {
  source: { query: element('trades') },
  columns: [
    { name: 'region', type: 'String' },
    { name: 'year', type: 'Integer', kind: 'dimension' },
    { name: 'notional', type: 'Float' },
    { name: 'qty', type: 'Integer' },
  ],
  derived: [],
  rows: ['region'],
  pivotOn: ['year'],
  sorts: [],
  epoch: 1,
};

/**
 * A host's patch as it arrives from JSON, null already read as undefined: a nested part (an appearance, a column) is a
 * patch too, which the type of the whole cannot say -- headless grid.ts casts it the same way.
 */
const fromJson = (patch: unknown): Patch<CubeConfiguration> => patch as Patch<CubeConfiguration>;

/** The query a state commits: the configuration folded in (cube-state.ts fold). */
const committed = (config: CubeConfiguration, snapshot: CubeSnapshot = CUBE): CubeSnapshot =>
  applyToSnapshot(snapshot, config);

describe('the pivot total folds into the query and reads back', () => {
  it('a new cube totals on the right, each measure by its own aggregate', () => {
    assert.deepEqual(committed(DEFAULT_CONFIGURATION).pivotTotal, { placement: 'right' });
  });

  it("a measure's total takes the aggregate set for it, on the edge chosen", () => {
    const config = withColumn(withSettings(DEFAULT_CONFIGURATION, { pivotStatisticColumnPlacement: 'left' }),
      'notional', { pivotStatisticColumnFunction: 'max' });
    assert.deepEqual(committed(config).pivotTotal, { placement: 'left', functions: { notional: 'max' } });
  });

  it('switched off, the total leaves the query -- not kept from the fold before', () => {
    // Upstream reads a specification that does not mention a total as none: unset placement is "no total".
    const off = withConfiguration(DEFAULT_CONFIGURATION, { pivotStatisticColumnPlacement: undefined });
    const s = applyToSnapshot(committed(DEFAULT_CONFIGURATION), off);
    assert.equal('pivotTotal' in s, false);
  });

  it("reads back from the query: where it sits and each measure's aggregate", () => {
    const config = fromSnapshot({ ...CUBE, pivotTotal: { placement: 'left', functions: { notional: 'average', qty: 'max' } } });
    assert.equal(config.pivotStatisticColumnPlacement, 'left');
    assert.equal(columnConfig(config, 'notional').pivotStatisticColumnFunction, 'average');
    assert.equal(columnConfig(config, 'qty').pivotStatisticColumnFunction, 'max');
  });

  it('a query without one leaves the setting it is read over alone', () => {
    // documented: most snapshots predate the field, so "no total in the query" is not "switch it off"
    const { pivotStatisticColumnPlacement: _p, ...off } = DEFAULT_CONFIGURATION;
    assert.equal(fromSnapshot(CUBE, off).pivotStatisticColumnPlacement, undefined);
    const left = withSettings(DEFAULT_CONFIGURATION, { pivotStatisticColumnPlacement: 'left' });
    assert.equal(fromSnapshot(CUBE, left).pivotStatisticColumnPlacement, 'left');
  });

  it('folded and read back over the defaults, it is the same total', () => {
    const config = withColumn(withSettings(DEFAULT_CONFIGURATION, { pivotStatisticColumnPlacement: 'left' }),
      'qty', { pivotStatisticColumnFunction: 'min' });
    const back = fromSnapshot(committed(config));
    assert.equal(back.pivotStatisticColumnPlacement, 'left');
    // year's kind is the source's own, read back with it
    assert.deepEqual(back.columns, { year: { kind: 'dimension' }, qty: { pivotStatisticColumnFunction: 'min' } });
  });
});

describe("a pivot key's sort (Horizontal Pivots: the order of its values across the header)", () => {
  it('descending folds into the query; ascending is the default and is not written', () => {
    assert.deepEqual(committed(withColumn(DEFAULT_CONFIGURATION, 'year', { pivotSortDirection: 'desc' })).pivotSort,
      { year: 'desc' });
    assert.equal('pivotSort' in committed(withColumn(DEFAULT_CONFIGURATION, 'year', { pivotSortDirection: 'asc' })), false);
  });

  it('cleared, it leaves a query that had it', () => {
    const desc = withColumn(DEFAULT_CONFIGURATION, 'year', { pivotSortDirection: 'desc' });
    const cleared = withColumn(desc, 'year', { pivotSortDirection: undefined });
    assert.equal('pivotSort' in applyToSnapshot(committed(desc), cleared), false);
  });

  it('reads back from the query', () => {
    const config = fromSnapshot({ ...CUBE, pivotSort: { year: 'desc' } });
    assert.equal(columnConfig(config, 'year').pivotSortDirection, 'desc');
    assert.deepEqual(fromSnapshot(committed(config)).columns, { year: { kind: 'dimension', pivotSortDirection: 'desc' } });
  });
});

describe('the row cap (General Properties > Row Limit)', () => {
  it('set, the query is capped; cleared, a query that was capped is not', () => {
    // UNSET means no limit, as upstream (DataCubeSnapshot.limit starts undefined)
    const capped = withSettings(DEFAULT_CONFIGURATION, { maxRows: 250 });
    assert.equal(committed(capped).maxRows, 250);
    const uncapped = withConfiguration(capped, { maxRows: undefined });
    assert.equal('maxRows' in applyToSnapshot(committed(capped), uncapped), false);
  });

  it("reads back from the query, over the cap of the configuration it is read over", () => {
    assert.equal(fromSnapshot({ ...CUBE, maxRows: 40 }, withSettings(DEFAULT_CONFIGURATION, { maxRows: 1000 })).maxRows, 40);
  });
});

describe('a column kept out of the pivot (Column Properties > Exclude from horizontal pivot)', () => {
  it('ticked, the query carries the measure through the pivot unspread', () => {
    const s = committed(withColumn(DEFAULT_CONFIGURATION, 'qty', { excludedFromPivot: true }));
    assert.deepEqual([...excludedFromPivot(s)], ['qty']);
    assert.deepEqual(s.columns.find((c) => c.name === 'qty'), { name: 'qty', type: 'Integer', excludedFromPivot: true });
  });

  it('reads back from the query', () => {
    const config = fromSnapshot({ ...CUBE, columns: CUBE.columns.map((c) => (c.name === 'qty' ? { ...c, excludedFromPivot: true } : c)) });
    assert.equal(columnConfig(config, 'qty').excludedFromPivot, true);
  });

  it("the header menu's Include (excludedFromPivot: false) spreads it again", () => {
    const out = withColumn(DEFAULT_CONFIGURATION, 'qty', { excludedFromPivot: true });
    const back = withColumn(out, 'qty', { excludedFromPivot: false });
    assert.deepEqual([...excludedFromPivot(applyToSnapshot(committed(out), back))], []);
  });

  it('unticked in Column Properties -- which CLEARS the setting -- spreads it again too', () => {
    // panel-column.ts writes `excludedFromPivot: v ? true : undefined`: unticked is cleared, not false. The query the
    // fold is applied to carries the last fold's `true`, and a cleared setting must take it back out -- as a cleared
    // Aggregation does (withoutAggregate: "clearing Aggregation in the panel must clear it on the snapshot too").
    const out = withColumn(DEFAULT_CONFIGURATION, 'qty', { excludedFromPivot: true });
    const unticked = withColumn(out, 'qty', { excludedFromPivot: undefined });
    assert.equal(columnConfig(unticked, 'qty').excludedFromPivot, undefined, 'the panel shows it unticked');
    assert.deepEqual([...excludedFromPivot(applyToSnapshot(committed(out), unticked))], [],
      'and the query spreads it across the pivot again');
  });
});

describe('Show grouped columns (General Properties): a row dimension kept beside the tree', () => {
  it('ticked, the query keeps them and the grid is told', () => {
    const on = withSettings(DEFAULT_CONFIGURATION, { showGroupedColumns: true });
    assert.equal(committed(on).keepGroupedColumns, true);
    assert.equal(toColumnLayout(on).keepGrouped, true);
    assert.equal('keepGroupedColumns' in committed(DEFAULT_CONFIGURATION), false, 'off by default, as upstream');
  });

  it('unticked, the query stops keeping them', () => {
    // treeview.ts builds the kept columns from `snapshot.keepGroupedColumns === true`: a query still carrying it after
    // the box is unticked keeps showing them, while the columns panel (reading the configuration) stops listing them.
    const on = withSettings(DEFAULT_CONFIGURATION, { showGroupedColumns: true });
    const off = withSettings(on, { showGroupedColumns: false });
    assert.notEqual(applyToSnapshot(committed(on), off).keepGroupedColumns, true);
  });
});

describe('Column Properties > Aggregation, read back from the query', () => {
  it('an aggregate that takes no weight reads back with no parameters', () => {
    const config = fromSnapshot({ ...CUBE, columns: CUBE.columns.map((c) => (c.name === 'notional' ? { ...c, aggregate: 'max' as const } : c)) });
    assert.deepEqual(columnConfig(config, 'notional'), { aggregateFn: 'max' });
  });

  it('a weight rides only with the weighted average: kept on another, the query would ignore it', () => {
    const config = withColumn(DEFAULT_CONFIGURATION, 'notional', { aggregateFn: 'max', aggregationParameters: ['qty'] });
    assert.deepEqual(committed(config).columns.find((c) => c.name === 'notional'),
      { name: 'notional', type: 'Float', aggregate: 'max' });
  });

  it("a calculated column's Aggregation reads back as a source column's does", () => {
    const s: CubeSnapshot = { ...CUBE, derived: [{ name: 'uplift', type: 'Float', kind: 'measure', aggregate: 'wavg', aggregateWeight: 'qty' }] };
    assert.deepEqual(columnConfig(fromSnapshot(s), 'uplift'), { aggregateFn: 'wavg', aggregationParameters: ['qty'] });
  });
});

describe("clearing a setting, as a host's patch does (null in JSON, undefined in the patch)", () => {
  it('an appearance setting cleared leaves no trace, and every other one stays', () => {
    const c = withConfiguration(DEFAULT_CONFIGURATION, fromJson({ appearance: { negativeForeground: undefined } }));
    assert.equal('negativeForeground' in c.appearance, false);
    const { negativeForeground: _n, ...rest } = DEFAULT_CONFIGURATION.appearance;
    assert.deepEqual(c.appearance, rest);
    assert.deepEqual(withAppearance(c, { negativeForeground: '#ff0000' }).appearance, { ...rest, negativeForeground: '#ff0000' });
  });

  it('neither the appearance nor the columns are cleared whole', () => {
    const configured = withColumn(DEFAULT_CONFIGURATION, 'qty', { hidden: true });
    const c = withConfiguration(configured, { appearance: undefined, columns: undefined });
    assert.deepEqual(c.appearance, DEFAULT_CONFIGURATION.appearance);
    assert.deepEqual(c.columns, { qty: { hidden: true } });
  });

  it('a setting the configuration REQUIRES is never left unset by a clear (it would read as off)', () => {
    // CubeConfiguration types these as required: every reader takes them as set (app.ts reads `c.showTitleBar`,
    // applyToSnapshot `config.showLeafCount && ...`). Cleared and left unset, each reads as false -- the title bar, the
    // drag bar, the leaf count and the truncation warning switch OFF, though their product default is on -- and a save
    // writes the null, so no default ever reaches that cube again.
    for (const key of ['showTitleBar', 'showDragZones', 'showLeafCount', 'showTruncationWarning'] as const) {
      const c = withConfiguration(DEFAULT_CONFIGURATION, { [key]: undefined });
      assert.equal(typeof c[key], 'boolean', `${key} cleared is still a setting`);
    }
  });
});

describe('what the grid is handed for the settings the other tests leave out (toColumnLayout)', () => {
  it('Fixed with no width typed yet: fixed, and no width invented', () => {
    const config = withColumn(DEFAULT_CONFIGURATION, 'qty', { widthMode: 'fixed' });
    assert.deepEqual(resolvedWidths(columnConfig(config, 'qty')), {});
    const layout = toColumnLayout(config);
    assert.deepEqual(layout.fixed, ['qty']);
    assert.equal(layout.widths, undefined);
  });

  it("the column pivot's headers measure-first, when asked", () => {
    assert.equal(toColumnLayout(withSettings(DEFAULT_CONFIGURATION, { pivotMeasuresFirst: true })).measuresFirst, true);
    assert.equal(toColumnLayout(DEFAULT_CONFIGURATION).measuresFirst, undefined);
  });

  it("the total's header, its name cleared, reads Total; the total switched off, no total column", () => {
    const unnamed = withConfiguration(DEFAULT_CONFIGURATION, { pivotStatisticColumnName: undefined });
    assert.deepEqual(toColumnLayout(unnamed).pivotTotal, { label: 'Total', placement: 'right' });
    const named = withSettings(DEFAULT_CONFIGURATION, { pivotStatisticColumnName: 'All years', pivotStatisticColumnPlacement: 'left' });
    assert.deepEqual(toColumnLayout(named).pivotTotal, { label: 'All years', placement: 'left' });
    const off = withConfiguration(DEFAULT_CONFIGURATION, { pivotStatisticColumnPlacement: undefined });
    assert.equal(toColumnLayout(off).pivotTotal, undefined);
  });
});

describe('renderFormats: what the screen RENDERS, against what a cube saves (toFormats)', () => {
  const s: CubeSnapshot = {
    ...CUBE,
    derived: [
      { name: 'bucket', type: 'Float', kind: 'dimension' },
      // typed only once its first compile lands: nothing is guessed for it before
      { name: 'pending' },
    ],
    groupDerived: [{ name: 'margin', type: 'Float' }],
  };
  const money = { kind: 'currency' as const, currency: 'USD', maximumFractionDigits: 0 };
  const config = withColumn(DEFAULT_CONFIGURATION, 'notional', { format: money });

  it("every numeric column's type default, by its kind, under what the user set", () => {
    assert.deepEqual(renderFormats(config, s), {
      // a dimension is a label: no thousands separators, no parentheses
      year: { kind: 'auto', decimals: 0, displayCommas: false },
      // the user's own bounds win over the Float default's fixed places; the rest of the default stays
      notional: { kind: 'currency', currency: 'USD', maximumFractionDigits: 0, negativeParens: true },
      qty: { kind: 'auto', decimals: 0, negativeParens: true },
      bucket: { kind: 'auto', displayCommas: false },
      // a group-stage column is post-aggregation: a measure's default
      margin: { kind: 'auto', decimals: 2, negativeParens: true },
    });
  });

  it('a cube with no group-stage columns: its source columns\' defaults alone', () => {
    assert.deepEqual(Object.keys(renderFormats(DEFAULT_CONFIGURATION, CUBE)), ['year', 'notional', 'qty']);
  });

  it('a save holds only what the user set, never a default', () => {
    assert.deepEqual(toFormats(config), { notional: money });
  });
});

describe("leafFormats: a dimension leaf takes its own settings, never a measure's", () => {
  it('a numeric dimension its label default, a text one nothing unless set', () => {
    const config = withColumn(DEFAULT_CONFIGURATION, 'region', { format: { kind: 'text', fontCase: 'uppercase' } });
    const out = leafFormats(config, [
      { name: 'year', path: ['year'], isDimension: true, type: 'Integer', kind: 'dimension' },
      { name: 'region', path: ['region'], isDimension: true, type: 'String' },
      { name: 'desk', path: ['desk'], isDimension: true, type: 'String' },
    ]);
    assert.deepEqual(out['year'], { kind: 'auto', decimals: 0, displayCommas: false });
    assert.deepEqual(out['region'], { kind: 'text', fontCase: 'uppercase' });
    assert.equal(out['desk'], undefined, 'it renders by its type alone');
  });
});

describe('renaming a column nobody configured', () => {
  it('moves nothing, and writes no order the configuration did not have', () => {
    const r = renameColumnConfig(DEFAULT_CONFIGURATION, 'qty', 'quantity');
    assert.equal(r.columns, DEFAULT_CONFIGURATION.columns);
    assert.equal('columnOrder' in r, false);
  });
});
