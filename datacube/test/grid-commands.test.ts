// A GRID EDIT AS A VALUE (src/grid-commands.ts; docs/DATACUBE_COMMANDS_STAGE1_2026_10_18.md): each command gives what
// the screen's own edit gives (grid-edits.ts, config.ts), so building a command changes no edit; a command read from
// JSON (Python's) is the same command; and a kind or a field DataCube does not know is refused in words, never passed
// over.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { element } from '../../pure-protocol/src/index.ts';
import { DEFAULT_CONFIGURATION, withConfiguration } from '../src/config.ts';
import {
  GRID_COMMAND_KINDS, applyGridCommand, applyGridCommands, readGridCommand, type GridCommand,
} from '../src/grid-commands.ts';
import {
  addSort, clickSort, clickTreeSort, measureAsColumn, setColumn, setGrouping, setPivot, setSorts, type GridDraft,
} from '../src/grid-edits.ts';
import { CubeRefusal, pivotTotalColumn, type CubeSnapshot, type DerivedColumn } from '../src/snapshot.ts';
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
    // a header click's first direction is its column's type's: a number from the largest, text from A, a date the latest
    ['clickSort on a number', { kind: 'clickSort', column: 'notional', chained: false }, clickSort(d, 'notional', 'desc', false)],
    ['clickSort on text', { kind: 'clickSort', column: 'desk', chained: true }, clickSort(d, 'desk', 'asc', true)],
    ['clickSort on a date', { kind: 'clickSort', column: 'traded', chained: false }, clickSort(d, 'traded', 'desc', false)],
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

describe('a command read from JSON is the same command, and what DataCube does not know is refused', () => {
  const every: GridCommand[] = [
    { kind: 'group', rows: ['region'] },
    { kind: 'pivot', columns: [] },
    { kind: 'sorts', sorts: [{ column: 'qty', direction: 'desc' }] },
    { kind: 'addSort', column: 'qty', direction: 'asc' },
    { kind: 'clickSort', column: 'qty', chained: true },
    { kind: 'clickTreeSort', chained: false },
    { kind: 'column', name: 'qty', change: { kind: 'dimension', aggregate: null, weight: null } },
    { kind: 'configure', patch: { columns: { qty: { heatmap: null } } } },
    { kind: 'filter', filter: null },
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
    refused(() => readGridCommand({ kind: 'clickSort', column: 'qty' }), /chained or not/);
    refused(() => readGridCommand({ kind: 'column', name: 'qty', change: { aggregateFn: 'max' } }),
      /a column's properties are kind, aggregate and weight, not aggregateFn/);
    refused(() => readGridCommand({ kind: 'column', name: 'qty', change: { aggregate: 'mean' } }), /"mean" is not one of DataCube's aggregates/);
    refused(() => readGridCommand({ kind: 'addFilter', condition: { kind: 'and', children: [] } }), /one condition/);
    refused(() => readGridCommand(['group']), /a grid's command is \{kind, \.\.\.\}/);
  });
});
