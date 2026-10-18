// A GRID EDIT AS A VALUE (src/grid-commands.ts; docs/DATACUBE_COMMANDS_STAGE1_2026_10_18.md): each command gives what
// the screen's own edit gives (grid-edits.ts, config.ts), so building a command changes no edit; a command read from
// JSON (Python's) is the same command; and a kind or a field DataCube does not know is refused in words, never passed
// over.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { PIVOT_SEPARATOR } from '../../engine-client/src/generated/lite-facts.ts';
import { element } from '../../pure-protocol/src/index.ts';
import { DEFAULT_CONFIGURATION, withColumn, withConfiguration } from '../src/config.ts';
import {
  GRID_COMMAND_KINDS, applyGridCommand, applyGridCommands, draftCommands, readGridCommand, type GridCommand,
} from '../src/grid-commands.ts';
import {
  addSort, clickSort, clickTreeSort, measureAsColumn, setColumn, setGrouping, setPivot, setSorts, spreadMeasureNames,
  type GridDraft,
} from '../src/grid-edits.ts';
import { CubeRefusal, pivotTotalColumn, type CubeSnapshot, type DerivedColumn } from '../src/snapshot.ts';
import { TREE_COLUMN } from '../src/treeview.ts';
import { stand } from './fake-planner.ts';

const CUBE: CubeSnapshot = {
  source: { query: element('t') },
  columns: [
    { name: 'region', type: 'String' },
    { name: 'desk', type: 'String' },
    { name: 'traded', type: 'StrictDate' },
    { name: 'notional', type: 'Float' },
    { name: 'qty', type: 'Integer' },
  ],
  derived: [],
  rows: ['region'],
  pivotOn: [],
  sorts: [],
  epoch: 1,
};
const draft = (s: Partial<CubeSnapshot> = {}): GridDraft => ({ snapshot: { ...CUBE, ...s }, config: DEFAULT_CONFIGURATION });

const refused = (f: () => unknown, words: RegExp): void => {
  assert.throws(f, (e: unknown) => e instanceof CubeRefusal && words.test(e.message), String(words));
};

const double: DerivedColumn = { name: 'double', lambda: stand('$x.qty * 2'), kind: 'measure' };

describe('each command gives what the screen\'s own edit gives', () => {
  const d = draft();
  const cases: [string, GridCommand, GridDraft][] = [
    ['group', { kind: 'group', rows: ['region', 'desk'] }, setGrouping(d, ['region', 'desk'])],
    ['pivot', { kind: 'pivot', columns: ['desk'] }, setPivot(d, ['desk'])],
    ['sorts', { kind: 'sorts', sorts: [{ column: 'qty', direction: 'asc' }] }, setSorts(d, [{ column: 'qty', direction: 'asc' }])],
    ['addSort', { kind: 'addSort', column: 'notional', direction: 'desc' }, addSort(d, 'notional', 'desc')],
    // a header click starts where the screen says, from the type the compiler gave the header: a number from the
    // largest, text from A -- and a column of text the grid counts is a number on the screen, though not in the query
    ['clickSort on a number', { kind: 'clickSort', column: 'notional', first: 'desc', chained: false }, clickSort(d, 'notional', 'desc', false)],
    ['clickSort on text', { kind: 'clickSort', column: 'desk', first: 'asc', chained: true }, clickSort(d, 'desk', 'asc', true)],
    ['clickSort on counted text', { kind: 'clickSort', column: 'desk', first: 'desc', chained: false }, clickSort(d, 'desk', 'desc', false)],
    ['clickTreeSort', { kind: 'clickTreeSort', chained: false }, clickTreeSort(d, false)],
    ['column', { kind: 'column', name: 'qty', change: { aggregate: 'average' } }, setColumn(d, 'qty', { aggregate: 'average' })],
    ['configure', { kind: 'configure', patch: { reportTitle: 'Trades', columns: { qty: { hidden: true } } } },
      { ...d, config: withConfiguration(d.config, { reportTitle: 'Trades', columns: { qty: { hidden: true } } }) }],
    ['measures', { kind: 'measures', measures: [{ name: 'qty', column: 'qty', fn: 'max' }] },
      measureAsColumn(d, { name: 'qty', column: 'qty', fn: 'max' }).draft],
  ];
  for (const [name, command, want] of cases) {
    it(name, () => {
      assert.deepEqual(applyGridCommand(d, command), want);
    });
  }

  it('configure: null clears a setting; a column\'s Aggregation goes by Column Properties\' rule (setColumn)', () => {
    const titled = applyGridCommand(d, { kind: 'configure', patch: { reportTitle: 'Trades' } });
    assert.equal(applyGridCommand(titled, { kind: 'configure', patch: { reportTitle: null } }).config.reportTitle, undefined);
    assert.deepEqual(applyGridCommand(d, { kind: 'configure', patch: { columns: { qty: { aggregateFn: 'max' } } } }),
      setColumn(d, 'qty', { aggregate: 'max' }));
  });

  it('configure refuses a setting DataCube has not, a column the grid has not, and a total\'s own Aggregation', () => {
    refused(() => applyGridCommand(d, { kind: 'configure', patch: { maxRow: 5 } }), /'maxRow' is not a grid's setting/);
    refused(() => applyGridCommand(d, { kind: 'configure', patch: { columns: { nope: { hidden: true } } } }),
      /'nope' is not a column of the grid/);
    const total = pivotTotalColumn('notional');
    refused(() => applyGridCommand(d, { kind: 'configure', patch: { columns: { [total]: { aggregateFn: 'max' } } } }),
      /is the pivot total of 'notional'/);
  });

  it('configure sets a pivot column\'s and the tree column\'s presentation (the menu hides them), never a kind or Aggregation', () => {
    const pivoted = setPivot(d, ['desk']);
    const header = `FX${PIVOT_SEPARATOR}notional`;
    const hide = (name: string): GridCommand => ({ kind: 'configure', patch: { columns: { [name]: { hidden: true } } } });
    assert.deepEqual(applyGridCommand(pivoted, hide(header)).config.columns[header], { hidden: true });
    refused(() => applyGridCommand(pivoted, { kind: 'configure', patch: { columns: { [header]: { aggregateFn: 'max' } } } }),
      /is a pivot column of 'notional'/);
    // not pivoted, no pivot's header is a column of the grid
    refused(() => applyGridCommand(d, hide(header)), /is not a column of the grid/);
    assert.deepEqual(applyGridCommand(d, hide(TREE_COLUMN)).config.columns[TREE_COLUMN], { hidden: true });
    refused(() => applyGridCommand(d, { kind: 'configure', patch: { columns: { [TREE_COLUMN]: { kind: 'measure' } } } }),
      /the grid's tree column: it has no kind/);
  });

  it('configure sets the presentation of the count a pivot of no measure fills with, and its total\'s (the menu\'s heatmap, hide)', () => {
    const dims = setPivot(draft({ columns: [{ name: 'region', type: 'String' }, { name: 'desk', type: 'String' }] }), ['desk']);
    assert.deepEqual(spreadMeasureNames(dims), ['count'], 'a pivot of no measure fills with a count');
    const heatmap = { from: '#ffffff', to: '#ff8a65' };
    assert.deepEqual(applyGridCommand(dims, { kind: 'configure', patch: { columns: { count: { heatmap } } } }).config.columns['count'],
      { heatmap });
    const total = pivotTotalColumn('count');
    assert.deepEqual(applyGridCommand(dims, { kind: 'configure', patch: { columns: { [total]: { hidden: true } } } }).config.columns[total],
      { hidden: true });
    refused(() => applyGridCommand(dims, { kind: 'configure', patch: { columns: { count: { aggregateFn: 'max' } } } }),
      /the count a pivot of no measure fills with: it has no kind/);
  });

  it('filter sets the filter whole, and null takes it off', () => {
    const one = { kind: 'condition' as const, column: 'region', operator: 'equal' as const, value: 'EMEA' };
    const on = applyGridCommand(d, { kind: 'filter', filter: one });
    assert.deepEqual(on.snapshot.filter, one);
    assert.equal('filter' in applyGridCommand(on, { kind: 'filter', filter: null }).snapshot, false);
  });

  it('addFilter and-s a condition with the filter the grid has, as a cell\'s menu does', () => {
    const a = { kind: 'condition' as const, column: 'region', operator: 'equal' as const, value: 'EMEA' };
    const b = { kind: 'condition' as const, column: 'desk', operator: 'equal' as const, value: 'FX' };
    const c = { kind: 'condition' as const, column: 'qty', operator: 'lessThan' as const, value: 3 };
    const one = applyGridCommand(d, { kind: 'addFilter', condition: a });
    assert.deepEqual(one.snapshot.filter, a);
    const two = applyGridCommand(one, { kind: 'addFilter', condition: b });
    assert.deepEqual(two.snapshot.filter, { kind: 'and', children: [a, b] });
    assert.deepEqual(applyGridCommand(two, { kind: 'addFilter', condition: c }).snapshot.filter, { kind: 'and', children: [a, b, c] });
  });

  it('calculate adds the column at its level, both lists written as the column editor writes them', () => {
    const row = applyGridCommand(d, { kind: 'calculate', level: 'row', column: double });
    assert.deepEqual(row.snapshot.derived, [double]);
    assert.deepEqual(row.snapshot.groupDerived, []);
    const group = applyGridCommand(d, { kind: 'calculate', level: 'group', column: { ...double, name: 'g' } });
    assert.deepEqual(group.snapshot.derived, []);
    assert.deepEqual(group.snapshot.groupDerived, [{ ...double, name: 'g' }]);
    // a name the grid has is refused, in the editor's words
    refused(() => applyGridCommand(d, { kind: 'calculate', level: 'row', column: { ...double, name: 'qty' } }), /qty/);
  });

  it('a list applies in order and stops at the first refusal', () => {
    const done = applyGridCommands(d, [{ kind: 'pivot', columns: ['desk'] }, { kind: 'addSort', column: 'qty', direction: 'asc' }]);
    assert.deepEqual(done.snapshot.pivotOn, ['desk']);
    assert.deepEqual(done.snapshot.sorts, [{ column: 'qty', direction: 'asc' }]);
    refused(() => applyGridCommands(d, [{ kind: 'group', rows: ['nope'] }, { kind: 'pivot', columns: ['desk'] }]), /nope/);
  });
});

describe("an editor's draft as commands (the Properties window's Apply: draftCommands)", () => {
  const d = draft();
  const patched = (x: GridDraft, patch: Parameters<typeof withConfiguration>[1]): GridDraft =>
    ({ ...x, config: withConfiguration(x.config, patch) });

  it('builds nothing for a draft unchanged', () => {
    assert.deepEqual(draftCommands(d, d), []);
  });

  it('builds only what the draft changed, and lands it on the grid as it is now: another window\'s edit stays', () => {
    const edited = patched(setGrouping(d, ['region', 'desk']), { reportTitle: 'Trades', appearance: { fontSize: 13 } });
    const commands = draftCommands(d, edited);
    assert.deepEqual(commands, [
      { kind: 'group', rows: ['region', 'desk'] },
      { kind: 'configure', patch: { reportTitle: 'Trades', appearance: { fontSize: 13 } } },
    ]);
    // meanwhile: a row limit set, a column pinned, a sort clicked -- each kept, as the three-way merge kept them
    const now = clickSort(patched(d, { maxRows: 50, columns: { qty: { pinned: 'left' } } }), 'qty', 'desc', false);
    const landed = applyGridCommands(now, commands);
    assert.deepEqual(landed.snapshot.rows, ['region', 'desk']);
    assert.deepEqual(landed.snapshot.sorts, [{ column: 'qty', direction: 'desc' }]);
    assert.equal(landed.config.maxRows, 50);
    assert.equal(landed.config.columns['qty']?.pinned, 'left');
    assert.equal(landed.config.appearance.fontSize, 13);
  });

  it('a column made a dimension comes before the grouping on it; one ungrouped, before it is made a measure', () => {
    const grouped = setGrouping(setColumn(d, 'qty', { kind: 'dimension' }), ['region', 'qty']);
    assert.deepEqual(draftCommands(d, grouped), [
      { kind: 'column', name: 'qty', change: { kind: 'dimension' } },
      { kind: 'group', rows: ['region', 'qty'] },
    ]);
    assert.deepEqual(applyGridCommands(d, draftCommands(d, grouped)), grouped);
    const back = setColumn(setGrouping(grouped, ['region']), 'qty', { kind: 'measure' });
    assert.deepEqual(draftCommands(grouped, back), [
      { kind: 'group', rows: ['region'] },
      { kind: 'column', name: 'qty', change: { kind: 'measure' } },
    ]);
    assert.deepEqual(applyGridCommands(grouped, draftCommands(grouped, back)), back);
  });

  it("Column Properties is the column command alone -- what setColumn sets with it (a kind's pivot exclusion) is not said again", () => {
    assert.deepEqual(draftCommands(d, setColumn(d, 'desk', { kind: 'measure' })),
      [{ kind: 'column', name: 'desk', change: { kind: 'measure' } }]);
    const wavg = setColumn(setColumn(d, 'notional', { aggregate: 'wavg' }), 'notional', { weight: 'qty' });
    assert.deepEqual(draftCommands(d, wavg), [{ kind: 'column', name: 'notional', change: { aggregate: 'wavg', weight: 'qty' } }]);
    // and cleared, as the panel's "none" clears them
    assert.deepEqual(draftCommands(wavg, d), [{ kind: 'column', name: 'notional', change: { aggregate: null, weight: null } }]);
  });

  it('a setting cleared is null in the patch, an appearance and a column key by key, the sorts last', () => {
    const was = patched(d, { reportTitle: 'Trades', columns: { qty: { width: 90, hidden: true } } });
    const cleared = patched(was, { reportTitle: undefined });
    const edited = setSorts({ ...cleared, config: withColumn(cleared.config, 'qty', { width: undefined }) },
      [{ column: 'qty', direction: 'asc' }]);
    assert.deepEqual(draftCommands(was, edited), [
      { kind: 'configure', patch: { reportTitle: null, columns: { qty: { width: null } } } },
      { kind: 'sorts', sorts: [{ column: 'qty', direction: 'asc' }] },
    ]);
    assert.deepEqual(applyGridCommands(was, draftCommands(was, edited)), edited);
  });
});

describe('a command read from JSON is the same command, and what DataCube does not know is refused', () => {
  const every: GridCommand[] = [
    { kind: 'group', rows: ['region'] },
    { kind: 'pivot', columns: [] },
    { kind: 'sorts', sorts: [{ column: 'qty', direction: 'desc' }] },
    { kind: 'addSort', column: 'qty', direction: 'asc' },
    { kind: 'clickSort', column: 'qty', first: 'desc', chained: true },
    { kind: 'clickTreeSort', chained: false },
    { kind: 'column', name: 'qty', change: { kind: 'dimension', aggregate: null, weight: null } },
    { kind: 'configure', patch: { columns: { qty: { heatmap: null } } } },
    { kind: 'filter', filter: null },
    { kind: 'filter', filter: { kind: 'and', children: [
      { kind: 'condition', column: 'region', operator: 'in', value: ['EMEA', 'APAC'] },
      { kind: 'not', child: { kind: 'condition', column: 'qty', operator: 'greaterThanColumn', rightColumn: 'notional' } },
      { kind: 'condition', column: 'desk', operator: 'isEmpty' },
    ] } },
    { kind: 'addFilter', condition: { kind: 'condition', column: 'region', operator: 'equal', value: 'EMEA' } },
    { kind: 'calculate', level: 'row', column: double },
    { kind: 'measures', measures: [{ name: 'w', column: 'notional', fn: 'wavg', weight: 'qty' }] },
  ];

  it('every kind, through JSON and back', () => {
    assert.deepEqual([...new Set(every.map((c) => c.kind))].sort(), [...GRID_COMMAND_KINDS].sort(), 'every kind is here');
    for (const c of every) assert.deepEqual(readGridCommand(JSON.parse(JSON.stringify(c))), c, c.kind);
  });

  it('an unknown kind, a misspelt field and a field of the wrong type, each in words', () => {
    refused(() => readGridCommand({ kind: 'regroup', rows: [] }), /'regroup' is not a grid's command/);
    refused(() => readGridCommand({ kind: 'group', row: ['region'] }), /a 'group' command takes rows, not row/);
    refused(() => readGridCommand({ kind: 'addSort', column: 'qty', direction: 'up' }), /a sort is 'asc' or 'desc'/);
    refused(() => readGridCommand({ kind: 'clickSort', column: 'qty', first: 'desc' }), /chained or not/);
    refused(() => readGridCommand({ kind: 'clickSort', column: 'qty', chained: false }), /the direction it starts with/);
    refused(() => readGridCommand({ kind: 'column', name: 'qty', change: { aggregateFn: 'max' } }),
      /a column's properties are kind, aggregate and weight, not aggregateFn/);
    refused(() => readGridCommand({ kind: 'column', name: 'qty', change: { aggregate: 'mean' } }), /"mean" is not one of DataCube's aggregates/);
    refused(() => readGridCommand({ kind: 'addFilter', condition: { kind: 'and', children: [] } }), /one condition/);
    // a filter, field by field: a field misspelt, an operator DataCube has not, an operand the operator does not take
    const condition = (c: Record<string, unknown>): unknown => ({ kind: 'filter', filter: { kind: 'condition', ...c } });
    refused(() => readGridCommand(condition({ colum: 'region', operator: 'equal', value: 'EMEA' })),
      /a condition takes column, operator, value, rightColumn, not colum/);
    refused(() => readGridCommand(condition({ column: 'region', operator: 'bogus', value: 'EMEA' })), /a condition's operator is one of/);
    refused(() => readGridCommand(condition({ column: 'region', operator: 'equal' })), /'equal' takes a value/);
    refused(() => readGridCommand(condition({ column: 'region', operator: 'in', value: 'EMEA' })), /'in' takes a list of values/);
    refused(() => readGridCommand(condition({ column: 'region', operator: 'equal', value: { at: 1 } })), /a filter's value is/);
    refused(() => readGridCommand({ kind: 'filter', filter: { kind: 'xor', children: [] } }), /a filter is a condition, an and/);
    // a calculated column, a formula's fields: its lambda the protocol's, its kind one of DataCube's, its name trimmed
    const calc = (c: Record<string, unknown>): unknown => ({ kind: 'calculate', level: 'row', column: c });
    refused(() => readGridCommand(calc({ name: 'x', lambda: 42 })), /lambda is the protocol's/);
    refused(() => readGridCommand(calc({ name: 'x', lambda: double.lambda, kind: 'bogus' })), /a column's kind is/);
    refused(() => readGridCommand(calc({ name: 'x', lambda: double.lambda, window: {} })),
      /a calculated column from JSON takes name, lambda, kind, type, not window/);
    const read = readGridCommand(calc({ name: '  pad  ', lambda: double.lambda }));
    assert.equal(read.kind === 'calculate' ? read.column.name : undefined, 'pad');
    refused(() => readGridCommand(['group']), /a grid's command is \{kind, \.\.\.\}/);
  });
});
