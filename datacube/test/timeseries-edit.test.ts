// A TIMESERIES TILE CHANGED (src/timeseries-edit.ts; the review's A2, docs/TIMESERIES_REVIEW_2026_10_25.md): every change
// its editors make and Python's Timeseries methods make, by one rule -- each held here, its refusals in its words.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { editTimeseries, firstPlotOf, firstTimeseries, TsEditRefusal, type TsGridColumns } from '../src/timeseries-edit.ts';
import { parseExpr } from '../src/timeseries-expr.ts';
import type { TimeseriesSpec, TsSeries } from '../src/timeseries-spec.ts';

const S = (id: string, expr: string, rest: Omit<TsSeries, 'id' | 'expr'> = {}): TsSeries => ({ id, expr: parseExpr(expr), ...rest });

const SPEC: TimeseriesSpec = {
  sources: [{ alias: 'px', cube: 'prices', date: 'day', key: 'symbol' }, { alias: 'rates', cube: 'rates', date: 'obs', key: 'tenor' }],
  series: [S('A', 'close[SPX]'), S('B', 'returns($A)'), S('C', 'rates:yield[10Y]')],
};

const PRICES: TsGridColumns = {
  cube: 'prices2', title: 'More prices',
  columns: [{ name: 'symbol', type: 'String' }, { name: 'day', type: 'StrictDate' }, { name: 'close', type: 'Float' }], rows: ['symbol'],
};

const refused = (f: () => unknown, words: RegExp): void => {
  assert.throws(f, (e: unknown) => e instanceof TsEditRefusal && words.test(e.message));
};

describe('a timeseries tile changed by its one rule', () => {
  it('a series plotted: added with the next letter, or one it has replaced in place, kept hidden unless told', () => {
    const added = editTimeseries(SPEC, { kind: 'plot', series: { expr: parseExpr('mean($A, 5)') } });
    assert.equal(added.series.at(-1)!.id, 'D');
    const hidden = editTimeseries(SPEC, { kind: 'hide', id: 'A', hidden: true });
    const edited = editTimeseries(hidden, { kind: 'plot', replace: true, series: { id: 'A', expr: parseExpr('close[NDX]'), name: 'Nasdaq' } });
    assert.deepEqual(edited.series[0], { id: 'A', expr: parseExpr('close[NDX]'), name: 'Nasdaq', hidden: true });
    refused(() => editTimeseries(SPEC, { kind: 'plot', replace: true, series: { id: 'Q', expr: parseExpr('close') } }), /there is no series Q/);
  });

  it('a series another reads is not taken off; one no other reads is', () => {
    refused(() => editTimeseries(SPEC, { kind: 'unplot', id: 'A' }), /series B reads \$A: take it off first/);
    assert.deepEqual(editTimeseries(SPEC, { kind: 'unplot', id: 'B' }).series.map((s) => s.id), ['A', 'C']);
    refused(() => editTimeseries(SPEC, { kind: 'unplot', id: 'Z' }), /there is no series Z/);
  });

  // the review's probe: the first source let go while a column of no alias reads it re-pointed that series at the next
  it('a source a series reads -- by its alias, or by a column of no alias -- is not let go; nor the only one', () => {
    refused(() => editTimeseries(SPEC, { kind: 'unread', alias: 'px' }), /series A, B read source px/);
    refused(() => editTimeseries(SPEC, { kind: 'unread', alias: 'rates' }), /series C reads source rates/);
    const one = editTimeseries({ ...SPEC, series: [S('A', 'close')] }, { kind: 'unread', alias: 'rates' });
    assert.deepEqual(one.sources.map((s) => s.alias), ['px']);
    refused(() => editTimeseries(one, { kind: 'unread', alias: 'px' }), /the only grid the tile reads/);
  });

  it('a source renamed: every expression naming it follows; a taken alias or a non-word refused', () => {
    const renamed = editTimeseries(SPEC, { kind: 'source', alias: 'rates', rename: 'curve' });
    assert.equal(renamed.sources[1]!.alias, 'curve');
    assert.deepEqual(renamed.series[2]!.expr, parseExpr('curve:yield[10Y]'));
    assert.deepEqual(renamed.series[0]!.expr, SPEC.series[0]!.expr, 'a column of no alias is the first source\'s still');
    refused(() => editTimeseries(SPEC, { kind: 'source', alias: 'rates', rename: 'px' }), /source px is the tile's already/);
    refused(() => editTimeseries(SPEC, { kind: 'source', alias: 'rates', rename: '2y' }), /is no alias/);
    const noKey = editTimeseries(SPEC, { kind: 'source', alias: 'px', key: null, date: 'when' });
    assert.deepEqual(noKey.sources[0], { alias: 'px', cube: 'prices', date: 'when' });
  });

  it('another grid read by DataCube\'s rule over its columns, called after its title; read twice, refused', () => {
    const more = editTimeseries(SPEC, { kind: 'read', grid: PRICES });
    assert.deepEqual(more.sources.at(-1), { alias: 'more', cube: 'prices2', date: 'day', key: 'symbol' });
    refused(() => editTimeseries(more, { kind: 'read', grid: PRICES }), /More prices is read already/);
    refused(() => editTimeseries(SPEC, { kind: 'read', grid: { ...PRICES, cube: 'x', columns: [{ name: 'close', type: 'Float' }] } }), /has no date column/);
  });

  it('the view, a pane, the frequency, the range, the annotations and the slots told whole', () => {
    const viewed = editTimeseries(SPEC, { kind: 'view', view: { compare: 'rebase', stats: true } });
    assert.deepEqual(editTimeseries(viewed, { kind: 'view', view: { compare: null } }).view, { stats: true });
    assert.equal(editTimeseries(editTimeseries(viewed, { kind: 'view', view: { compare: null, stats: null } }), { kind: 'grain', grain: null }).view, undefined);
    const logged = editTimeseries(SPEC, { kind: 'pane', index: 1, log: true, height: 2 });
    assert.deepEqual(logged.panes, [{}, { log: true, height: 2 }]);
    assert.deepEqual(editTimeseries(logged, { kind: 'pane', index: 1, log: false }).panes, [{}, { height: 2 }]);
    refused(() => editTimeseries(SPEC, { kind: 'pane', index: 9 }), /there is no pane 9/);
    assert.equal(editTimeseries(SPEC, { kind: 'grain', grain: 'week' }).grain, 'week');
    assert.deepEqual(editTimeseries(SPEC, { kind: 'range', range: { last: '1y' } }).range, { last: '1y' });
    const marked = editTimeseries(SPEC, { kind: 'annotate', annotation: { kind: 'level', value: 70, pane: 1 } });
    assert.equal(editTimeseries(marked, { kind: 'unannotate', index: 0 }).annotations, undefined);
    refused(() => editTimeseries(marked, { kind: 'unannotate', index: 3 }), /there is no annotation 4/);
    const slots = editTimeseries({ ...SPEC, view: { stats: true } }, { kind: 'slots', slots: { view: { compare: 'percent' }, grain: 'month', range: null } });
    assert.deepEqual([slots.view, slots.grain, slots.range], [{ compare: 'percent' }, 'month', undefined]);
  });

  // the review's A3: Create ▸ Timeseries and Python's first tile, one rule and one refusal
  it('a first tile of a grid, and a first plot of a source told by its grid: one rule, refused in one set of words', () => {
    const made = firstTimeseries(PRICES);
    assert.ok(typeof made !== 'string');
    assert.deepEqual(made.series, [{ id: 'A', expr: parseExpr('close'), split: 'symbol' }]);
    const dates: TsGridColumns = { ...PRICES, columns: PRICES.columns.filter((c) => c.name !== 'close') };
    const words = firstTimeseries(dates);
    assert.equal(typeof words, 'string');
    assert.equal(firstPlotOf(dates, made.sources[0]!), words);
  });
});
