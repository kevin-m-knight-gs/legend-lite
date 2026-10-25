// A FLOW SHEET (docs/DATACUBE_FLOW_MODE_2026_10_24.md FL4, FL5, FL6): its layout's rules (layout/flow.ts), what a box
// says it does (flow-boxes.ts), and its page commands -- a flow added, a box placed after the box it reads, moved, taken
// off (what read it live frozen to a copy), what only a board takes refused in words -- read and written whole, the
// rebase's renames, the page read back, and the cubes a page opens none of as it opens (page-document.ts boxCubes).

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { cubeOpenedAgain, isLive, rawRows, writeCube, type CubeDocument, type CubeSource, type FileSource, type RowsSource } from '../src/cube-document.ts';
import { DEFAULT_CONFIGURATION } from '../src/config.ts';
import { boxInputs, boxSays, readersOf } from '../src/flow-boxes.ts';
import { add, NEW_SHEET } from '../src/layout/bands.ts';
import {
  BOX_HEIGHT, BOX_WIDTH, EMPTY_FLOW, FlowRefusal, GAP_X, GAP_Y, MARGIN, arranged, flowProblems, framedBy, moveBox, placeAfter, removeBox,
} from '../src/layout/flow.ts';
import { liveGrids, resolvedSource } from '../src/live-inputs.ts';
import { applyPageCommand, idsOf, readPageCommand, type PageCommand, type PageDraft } from '../src/page-commands.ts';
import {
  boxCubes, layoutTiles, pageToJson, readPage, unnamedLabel, writePageOf, PageDocumentError, type PageDocument,
} from '../src/page-document.ts';
import { renamedCommand, rearranged } from '../src/page-rebase.ts';
import { tidyCommands } from '../src/page-gestures.ts';
import { applyPlanned, pageBetween } from '../src/page-between.ts';
import { CubeRefusal, type DerivedColumn, type FilterNode } from '../src/snapshot.ts';
import { readUnique, uniqueCommands, uniqueOf } from '../src/unique.ts';
import { profileLambda, profileOf } from '../src/profile.ts';
import { imputeCommands, imputedOf, readImputation } from '../src/impute.ts';
import { TreeState } from '../src/tree.ts';
import { accessor, fn, lambda, lit, property, variable } from '../../pure-protocol/src/index.ts';
import { textToolOf } from '../src/text-tools.ts';
import { textToolColumns, textToolProblem } from '../src/text-tool-columns.ts';
import { grainColumn, grainOf } from '../src/calc-presets.ts';
import { pastedCells, tableCsv, tableProblem } from '../src/ui/text-input-dialog.ts';

const COLUMNS = [{ name: 'region', type: 'String' }, { name: 'notional', type: 'Float' }];
const FILE: FileSource = {
  _type: 'file', name: 'trades.csv', format: 'csv', size: 120, sha256: 'ab'.repeat(32), columns: COLUMNS,
};
const rows = (grid: string): RowsSource => ({ _type: 'rows', name: 'trades.csv', input: { grid }, columns: COLUMNS });

/** A cube over `source`, its query changed by `query` (a box's one step). */
function cube(source: CubeSource, query: (q: CubeDocument['query']) => CubeDocument['query'] = (q) => q,
  configuration: Readonly<Record<string, unknown>> = {}): CubeDocument {
  const written = writeCube({
    name: 'flow', source, snapshot: rawRows(accessor('local::DB', 'trades'), COLUMNS),
    configuration: DEFAULT_CONFIGURATION, tree: TreeState.fromPaths([], false),
  });
  return { ...written, query: query(written.query), configuration: { ...written.configuration, ...configuration } };
}

/** A page of one grid over the file, on a board. */
const BOARD = (): PageDocument => writePageOf({
  name: 'flow', cubes: [{ id: 'grid-1', cube: cube(FILE) }],
  views: { views: [{ id: 'grid-1', kind: 'grid', cube: 'grid-1' }], sheets: [{ id: 'sheet-1', layout: { kind: 'bands', ...add(NEW_SHEET, 'grid-1') } }] },
});

const draft = (page: PageDocument): PageDraft => ({ page, held: idsOf(page) });
const apply = (d: PageDraft, ...commands: PageCommand[]): PageDraft => commands.reduce((at, c) => applyPageCommand(at, c).draft, d);
const refused = (f: () => unknown, words: RegExp): void => {
  assert.throws(f, (e: unknown) => e instanceof CubeRefusal && words.test(e.message), String(words));
};
const addGrid = (document: CubeDocument, sheet: string, near: string | null): PageCommand =>
  ({ kind: 'addGrid', document, sheet, near, title: null, columns: null });

/** BOARD, then a flow: an Input (grid-2), a Filter after it (grid-3), and a Sort after the Input too (grid-4). */
function FLOW(): PageDraft {
  return apply(draft(BOARD()),
    { kind: 'addFlow', name: null },
    addGrid(cube(FILE), 'sheet-2', null),
    addGrid(cube(rows('grid-2'), (q) => ({ ...q, filter: { kind: 'condition', column: 'notional', operator: 'greaterThan', value: 100 } })), 'sheet-2', 'grid-2'),
    addGrid(cube(rows('grid-2'), (q) => ({ ...q, sorts: [{ column: 'notional', direction: 'desc' }] })), 'sheet-2', 'grid-2'));
}
const flowOf = (page: PageDocument, sheet = 'sheet-2') => {
  const layout = page.sheets.find((s) => s.id === sheet)!.layout;
  if (layout.kind !== 'flow') throw new Error(`${sheet} is no flow`);
  return layout;
};
const at = (page: PageDocument, tile: string) => flowOf(page).boxes.find((b) => b.tile === tile);

describe("a flow's layout (layout/flow.ts)", () => {
  it('places a box after the box it reads, the next column on its row, else the first free row there', () => {
    const one = placeAfter(EMPTY_FLOW, 'a');
    assert.deepEqual(one.boxes, [{ tile: 'a', x: MARGIN, y: MARGIN }]);
    const two = placeAfter(one, 'b', 'a');
    assert.deepEqual(two.boxes[1], { tile: 'b', x: MARGIN + BOX_WIDTH + GAP_X, y: MARGIN });
    const three = placeAfter(two, 'c', 'a');
    assert.deepEqual(three.boxes[2], { tile: 'c', x: MARGIN + BOX_WIDTH + GAP_X, y: MARGIN + BOX_HEIGHT + GAP_Y }, 'below the one taken');
    // after none: a row of its own below every box
    assert.deepEqual(placeAfter(three, 'd').boxes[3], { tile: 'd', x: MARGIN, y: MARGIN + 2 * (BOX_HEIGHT + GAP_Y) });
    assert.throws(() => placeAfter(three, 'a'), (e: unknown) => e instanceof FlowRefusal && /'a' is on the canvas already/.test(e.message));
  });

  it('moves a box to whole units, never off the canvas; takes one off', () => {
    const flow = placeAfter(placeAfter(EMPTY_FLOW, 'a'), 'b', 'a');
    assert.deepEqual(moveBox(flow, 'b', 300.4, 99.6).boxes[1], { tile: 'b', x: 300, y: 100 });
    assert.throws(() => moveBox(flow, 'b', -1, 0), /neither below zero, not \(-1, 0\)/);
    assert.throws(() => moveBox(flow, 'b', Number.NaN, 0), /two numbers/);
    assert.throws(() => moveBox(flow, 'z', 0, 0), /'z' is no box of this flow/);
    assert.deepEqual(removeBox(flow, 'a').boxes.map((b) => b.tile), ['b']);
    assert.deepEqual(removeBox(flow, 'z'), flow, 'a box it does not have: as it is');
  });

  it('tidies a flow: each box a column after the boxes it reads, level with them, else as it was down the canvas', () => {
    const reads: Record<string, string[]> = { input: [], other: [], filter: ['input'], join: ['filter', 'other'], sort: ['input'] };
    const messy = { boxes: [
      { tile: 'join', x: 10, y: 500 }, { tile: 'input', x: 700, y: 30 }, { tile: 'sort', x: 5, y: 5 },
      { tile: 'filter', x: 300, y: 300 }, { tile: 'other', x: 0, y: 900 },
    ] };
    const tidy = arranged(messy, (t) => reads[t] ?? []);
    const at = (t: string) => tidy.boxes.find((b) => b.tile === t);
    const col = (n: number) => MARGIN + n * (BOX_WIDTH + GAP_X);
    const row = (n: number) => MARGIN + n * (BOX_HEIGHT + GAP_Y);
    assert.deepEqual(tidy.boxes.map((b) => b.tile), messy.boxes.map((b) => b.tile), 'the boxes in the order they came');
    assert.deepEqual([at('input'), at('other')], [{ tile: 'input', x: col(0), y: row(0) }, { tile: 'other', x: col(0), y: row(1) }]);
    // the Sort was higher up the canvas than the Filter: first in their column
    assert.deepEqual([at('sort'), at('filter')], [{ tile: 'sort', x: col(1), y: row(0) }, { tile: 'filter', x: col(1), y: row(1) }]);
    assert.deepEqual(at('join'), { tile: 'join', x: col(2), y: row(1) }, 'level with the boxes it reads');
    assert.deepEqual(arranged(tidy, (t) => reads[t] ?? []), tidy, 'tidy already: as it is');
  });

  it('says what is wrong with a flow read from somewhere', () => {
    assert.deepEqual(flowProblems({ boxes: [{ tile: 'a', x: 1, y: 2 }] }), []);
    assert.deepEqual(flowProblems({ boxes: [{ tile: 'a', x: 1, y: 2 }, { tile: 'a', x: 1.5, y: -2 }] }), [
      "'a' is on the canvas twice",
      "'a' is at (1.5, -2): a box's place is two whole numbers, neither below zero",
    ]);
  });
});

describe('what a box says it does (flow-boxes.ts boxSays): its query, never a field beside it', () => {
  it('a box over a source of its own is an Input; over another grid\'s rows, each step its query takes', () => {
    assert.deepEqual(boxSays(cube(FILE)), { tool: 'input', also: [], says: 'trades.csv' });
    assert.deepEqual(boxSays(cube(rows('grid-1'))), { tool: 'browse', also: [], says: 'every row' });
    const filtered = boxSays(cube(rows('grid-1'), (q) => ({ ...q, filter: { kind: 'condition', column: 'notional', operator: 'greaterThan', value: 100 } })));
    assert.equal(filtered.tool, 'filter');
    assert.match(filtered.says, /notional .* 100/);
    // the order a grid takes its steps in: its calculated columns before its sorts
    const both = boxSays(cube(rows('grid-1'), (q) => ({
      ...q, sorts: [{ column: 'notional', direction: 'desc' }], derived: [{ name: 'big', type: 'Boolean' } as never],
    })));
    assert.deepEqual(both, { tool: 'formula', also: ['sort'], says: 'big' });
    assert.deepEqual(boxSays(cube(rows('grid-1'), (q) => ({ ...q, rows: ['region'] }))), { tool: 'summarize', also: [], says: 'by region' });
    assert.deepEqual(boxSays(cube(rows('grid-1'), (q) => q, { columns: { region: { hidden: true } } })),
      { tool: 'select', also: [], says: '1 of 2 columns left out' });
  });

  it('a window column says the tool that makes one so; a column pivot, a Cross Tab', () => {
    const win = (name: string, window: Record<string, unknown>) => ({ name, window: { partition: [], order: [], ...window } } as never);
    const said = (derived: never[]) => boxSays(cube(rows('grid-1'), (q) => ({ ...q, derived })));
    assert.deepEqual(said([win('RecordID', { fn: 'rowNumber' })]), { tool: 'recordId', also: [], says: 'RecordID' });
    assert.deepEqual(said([win('Previous', { fn: 'lag', column: 'notional', offset: 1 })]), { tool: 'multiRow', also: [], says: 'Previous' });
    assert.deepEqual(said([win('Tile', { fn: 'ntile', buckets: 4 })]), { tool: 'tile', also: [], says: 'Tile' });
    assert.deepEqual(said([win('RunningTotal', { fn: 'sum', column: 'notional', frame: 'running' }), win('Total', { fn: 'sum', column: 'notional', frame: 'partition' })]),
      { tool: 'runningTotal', also: ['formula'], says: 'RunningTotal' });
    // the columns of one tool together, in the order they come
    assert.deepEqual(said([win('a', { fn: 'rowNumber' }), win('b', { fn: 'rowNumber' })]), { tool: 'recordId', also: [], says: 'a, b' });
    assert.deepEqual(boxSays(cube(rows('grid-1'), (q) => ({ ...q, rows: ['region'], pivotOn: ['desk'] }))),
      { tool: 'crosstab', also: [], says: 'desk across, by region' });
    // a Top N, as a Sample says it: its first (smallest) or last rows by a column
    assert.deepEqual(boxSays(cube(rows('grid-1'), (q) => ({ ...q, topN: { by: 'notional', n: 5, bottom: true } }))),
      { tool: 'sample', also: [], says: 'first 5 by notional' });
  });

  it("a date taken to its period says a DateTime, by its expression's shape: the preset's or one typed so", () => {
    const said = (derived: DerivedColumn[]) => boxSays(cube(rows('grid-1'), (q) => ({ ...q, derived })));
    assert.deepEqual(said([grainColumn('trade_date', 'month', 'trade_date_month')]), { tool: 'datetime', also: [], says: 'trade_date by month' });
    assert.deepEqual(said([grainColumn('trade_date', 'day', 'd'), grainColumn('settle', 'year', 'y')]),
      { tool: 'datetime', also: [], says: 'trade_date by day, settle by year' });
    // the same function over anything but the row's own column, a Formula's
    const other = { name: 'odd', lambda: lambda(['x'], fn('firstDayOfMonth', fn('toOne', property(variable('y'), 'trade_date')))) };
    assert.deepEqual(said([other]), { tool: 'formula', also: [], says: 'odd' });
    assert.equal(grainOf(grainColumn('trade_date', 'quarter', 'q'))?.grain, 'quarter');
  });

  it("a box's inputs, live or a copy named as the box it copies; the boxes that read it, through others", () => {
    assert.deepEqual(boxInputs(cube(FILE)), []);
    assert.deepEqual(boxInputs(cube(rows('grid-2'))), [{ side: 'input', grid: 'grid-2' }]);
    const { page } = apply(FLOW(), { kind: 'removeTile', tile: 'grid-2' }, { kind: 'setInput', grid: 'grid-4', side: 'input', input: { grid: 'grid-3' } });
    // grid-3 read a copy of the Input; grid-4 reads grid-3 live, then frozen: a copy of the Filter
    const documentOf = (id: string) => page.cubes.find((c) => c.id === id)?.cube;
    assert.deepEqual(boxInputs(documentOf('grid-3')!), [{ side: 'input', frozen: 'trades.csv' }]);
    const frozen = apply({ page, held: idsOf(page) }, { kind: 'setInput', grid: 'grid-4', side: 'input', input: null }).page;
    assert.deepEqual(boxInputs(frozen.cubes.find((c) => c.id === 'grid-4')!.cube), [{ side: 'input', frozen: 'Filter' }]);
    const chain = apply(FLOW(), { kind: 'setInput', grid: 'grid-4', side: 'input', input: { grid: 'grid-3' } }).page;
    const of = (id: string) => chain.cubes.find((c) => c.id === id)?.cube;
    assert.deepEqual([...readersOf('grid-2', ['grid-2', 'grid-3', 'grid-4'], of)].sort(), ['grid-3', 'grid-4']);
    assert.deepEqual([...readersOf('grid-4', ['grid-2', 'grid-3', 'grid-4'], of)], []);
  });
});

describe('the text tools (text-tools.ts): calculated columns, read back off their expressions', () => {
  const said = (derived: DerivedColumn[]) => boxSays(cube(rows('grid-1'), (q) => ({ ...q, derived })));
  const body = (d: DerivedColumn) => JSON.stringify(d.lambda?.body[0]);

  it('Text To Columns: a column a part, counted from 1 by name and from 0 by splitPart; the box says the split once', () => {
    const parts = textToolColumns({ kind: 'split', column: 'book', delimiter: ' ', parts: 3, name: 'book' });
    assert.deepEqual(parts.map((p) => p.name), ['book_1', 'book_2', 'book_3']);
    assert.equal(body(parts[2]!), JSON.stringify(fn('splitPart', property(variable('x'), 'book'), lit.string(' '), lit.integer(2))));
    assert.deepEqual(said(parts), { tool: 'textToColumns', also: [], says: "book split on ' '" });
  });

  it('RegEx: a match, a replacement or the first match, each over the column itself', () => {
    const one = (mode: 'match' | 'replace' | 'extract') => textToolColumns({ kind: 'regex', column: 'desk', pattern: '^[A-Z]', mode, replacement: '?', name: 'd' })[0]!;
    assert.deepEqual(said([one('match')]), { tool: 'regex', also: [], says: 'desk matches /^[A-Z]/' });
    assert.deepEqual(said([one('replace')]), { tool: 'regex', also: [], says: '/^[A-Z]/ replaced in desk' });
    assert.deepEqual(said([one('extract')]), { tool: 'regex', also: [], says: '/^[A-Z]/ of desk' });
    assert.match(body(one('extract')), /"function":"first".*"function":"regexpExtract"/);
  });

  it("Data Cleansing: a chain of its own functions over each column, in its order; a Formula's other functions none of it", () => {
    const clean = textToolColumns({ kind: 'cleanse', columns: ['region', 'desk'], cleansing: { blanks: true, punctuation: true, trim: true, letterCase: 'lower' } });
    assert.deepEqual(clean.map((c) => c.name), ['region_clean', 'desk_clean']);
    assert.match(body(clean[0]!), /^\{"_type":"func","function":"toLower","parameters":\[\{"_type":"func","function":"trim"/);
    assert.deepEqual(said(clean), { tool: 'cleanse', also: [], says: 'region cleansed, desk cleansed' });
    // a replacement of its own pattern is a cleansing; of any other, a RegEx
    const spaces = textToolColumns({ kind: 'cleanse', columns: ['desk'], cleansing: { spaces: true } });
    assert.equal(textToolOf(spaces[0]!)?.tool, 'cleanse');
    // a function no text tool makes, a Formula's
    const other = { name: 'n', lambda: lambda(['x'], fn('length', fn('toOne', property(variable('x'), 'desk')))) };
    assert.equal(textToolOf(other), undefined);
    assert.deepEqual(said([other]), { tool: 'formula', also: [], says: 'n' });
  });

  it('refuses settings that make no column, in words', () => {
    assert.equal(textToolProblem({ kind: 'split', column: 'a', delimiter: '', parts: 2, name: 'a' }), 'it splits on a delimiter: type one');
    assert.equal(textToolProblem({ kind: 'split', column: 'a', delimiter: ',', parts: 0, name: 'a' }), 'it makes from 1 to 50 columns');
    assert.equal(textToolProblem({ kind: 'regex', column: 'a', pattern: '', mode: 'match', name: 'a' }), 'it needs a pattern');
    assert.equal(textToolProblem({ kind: 'cleanse', columns: ['a'], cleansing: {} }), 'pick what to do to them');
    assert.throws(() => textToolColumns({ kind: 'cleanse', columns: [], cleansing: { trim: true } }), /pick a column to cleanse/);
  });
});

describe('Unique (unique.ts): a row number per key, its first kept, the number hidden -- read back as one step', () => {
  const snapshot = rawRows(accessor('local::DB', 'trades'), COLUMNS);
  const made = (keys: string[], by?: { column: string; direction: 'asc' | 'desc' }) => uniqueCommands(snapshot, { keys, ...(by ? { by } : {}) });

  it('makes its row number, its filter and its column hidden, ordered by the first key unless given an order', () => {
    const [calc, filter, configure] = made(['region']);
    assert.deepEqual(calc, { kind: 'calculate', level: 'row', column: {
      name: 'UniqueRow', window: { fn: 'rowNumber', partition: ['region'], order: [{ column: 'region', direction: 'asc' }] },
    } });
    assert.deepEqual(filter, { kind: 'addFilter', condition: { kind: 'condition', column: 'UniqueRow', operator: 'equal', value: 1 } });
    assert.deepEqual(configure, { kind: 'configure', patch: { columns: { UniqueRow: { hidden: true } } } });
    const ordered = made(['region'], { column: 'notional', direction: 'desc' })[0] as { column: DerivedColumn };
    assert.deepEqual(ordered.column.window?.order, [{ column: 'notional', direction: 'desc' }]);
  });

  it('a box made so says Unique, by its keys: its number neither a Record ID nor its filter a Filter, nor hidden a Select', () => {
    const [calc, filter] = made(['region', 'notional']) as unknown as [{ column: DerivedColumn }, { condition: FilterNode }];
    const box = cube(rows('grid-1'), (q) => ({ ...q, derived: [calc.column], filter: filter.condition }), { columns: { UniqueRow: { hidden: true } } });
    assert.deepEqual(boxSays(box), { tool: 'unique', also: [], says: 'by region, notional' });
    assert.deepEqual(uniqueOf(box.query)?.keys, ['region', 'notional']);
    // another filter beside it: no Unique, as its words would hide the other
    assert.equal(uniqueOf({ derived: [calc.column], filter: { kind: 'and', children: [filter.condition, filter.condition] } }), undefined);
  });

  it('refuses a key it cannot make, in words; a name taken moves on', () => {
    assert.throws(() => made([]), /a key is at least one column/);
    assert.throws(() => made(['nope']), /the grid has no column 'nope'/);
    assert.throws(() => made(['region', 'region']), /a key names each column once/);
    const taken = { ...snapshot, derived: [{ name: 'UniqueRow', window: { fn: 'rowNumber', partition: [], order: [] } }] } as never;
    assert.equal((uniqueCommands(taken, { keys: ['region'] })[0] as { column: DerivedColumn }).column.name, 'UniqueRow_2');
    assert.deepEqual(readUnique({ kind: 'unique', keys: ['region'], by: 'notional' }), { keys: ['region'], by: { column: 'notional', direction: 'asc' } });
    assert.throws(() => readUnique({ kind: 'unique', keys: 'region' }), /its keys are a list of column names/);
  });
});

describe("Imputation (impute.ts): a number's empties filled, a value or the column's own across every row", () => {
  const typed = { ...rawRows(accessor('local::DB', 'trades'), [...COLUMNS, { name: 'qty', type: 'Integer' }]) };

  it("fills with a value -- a whole number's with a whole number -- or over a window column of its average, hidden", () => {
    const [byValue] = imputeCommands(typed, { columns: ['qty'], with: { kind: 'value', value: 0 } }) as unknown as [{ column: DerivedColumn }];
    assert.equal(byValue.column.name, 'qty_filled');
    assert.equal(JSON.stringify(byValue.column.lambda?.body[0]), JSON.stringify(fn('coalesce', property(variable('x'), 'qty'), lit.integer(0))));
    const avg = imputeCommands(typed, { columns: ['notional'], with: { kind: 'average' } });
    assert.deepEqual(avg.map((c) => c.kind), ['calculate', 'calculate', 'configure']);
    const [helper, filled] = avg as unknown as [{ column: DerivedColumn }, { column: DerivedColumn }];
    assert.deepEqual(helper.column.window, { fn: 'average', column: 'notional', partition: [], order: [], frame: 'partition' });
    // a box made so says Imputation, its window column none of its words, none of a Select's
    const box = cube(rows('grid-1'), (q) => ({ ...q, derived: [helper.column, filled.column] }), { columns: { notional__average: { hidden: true } } });
    assert.deepEqual(boxSays(box), { tool: 'impute', also: [], says: "notional's empties → its average" });
    assert.deepEqual(imputedOf(filled.column, [helper.column, filled.column]), { column: 'notional', with: { kind: 'average' }, helper: 'notional__average' });
  });

  it('refuses in words what it cannot fill', () => {
    assert.throws(() => imputeCommands(typed, { columns: [], with: { kind: 'average' } }), /pick a column to fill/);
    assert.throws(() => imputeCommands(typed, { columns: ['region'], with: { kind: 'average' } }), /only a number's empties are filled/);
    assert.throws(() => imputeCommands(typed, { columns: ['qty'], with: { kind: 'value', value: 0.5 } }), /holds whole numbers/);
    assert.deepEqual(readImputation({ kind: 'impute', columns: ['qty'], with: 3 }), { columns: ['qty'], with: { kind: 'value', value: 3 } });
    assert.throws(() => readImputation({ kind: 'impute', columns: ['qty'], with: 'median' }), /not "median"/);
  });
});

describe("a box's Profile (profile.ts): one row of aggregates asked of the database, read back a column each", () => {
  const columns = [{ name: 'region', type: 'String' }, { name: 'settled', type: 'Boolean' }];

  it("asks each column's values and different values counted, and an ordered column's smallest and largest", () => {
    const text = JSON.stringify(profileLambda(property(variable('r'), 'rows'), columns));
    assert.match(text, /"function":"groupBy"/);
    for (const name of ['c0_filled', 'c0_distinct', 'c0_smallest', 'c0_largest', 'c1_filled', 'c1_distinct']) assert.ok(text.includes(`"${name}"`), name);
    assert.ok(!text.includes('"c1_smallest"'), 'a Boolean has no smallest');
    assert.match(text, /"function":"distinct"/);
  });

  it('reads its one row back; a box with no rows, every count none', () => {
    const col = (name: string, v: unknown) => ({ name, type: 'Integer', values: [v] });
    const table = { rowCount: 1, epoch: 0, elapsedMs: 0, columns: [
      col('__profile_rows__', 5), col('c0_filled', 4), col('c0_distinct', 3), col('c0_smallest', 'AMER'), col('c0_largest', 'EMEA'),
      col('c1_filled', 5), col('c1_distinct', 2),
    ] } as never;
    assert.deepEqual(profileOf(table, columns), { rows: 5, columns: [
      { name: 'region', type: 'String', filled: 4, distinct: 3, smallest: 'AMER', largest: 'EMEA' },
      { name: 'settled', type: 'Boolean', filled: 5, distinct: 2 },
    ] });
    assert.deepEqual(profileOf({ rowCount: 0, epoch: 0, elapsedMs: 0, columns: [] } as never, columns).columns[0],
      { name: 'region', type: 'String', filled: 0, distinct: 0 });
  });
});

describe("a Text Input's table (ui/text-input-dialog.ts): a CSV file's text, as a file dropped on the canvas is", () => {
  it('writes its head row and a line a row, quoting a value only where it must', () => {
    assert.equal(tableCsv({ name: 't', columns: ['region', 'target'], rows: [['EMEA', '10'], ['AMER', '20']] }), 'region,target\nEMEA,10\nAMER,20\n');
    assert.equal(tableCsv({ name: 't', columns: ['a', 'b'], rows: [['x, y', 'say "hi"'], [' pad', 'two\nlines']] }),
      'a,b\n"x, y","say ""hi"""\n" pad","two\nlines"\n');
  });

  it('says what keeps it from being a table: a name, a column named once each, a row with a value', () => {
    const ok = { name: 'targets', columns: ['region', 'target'], rows: [['EMEA', '10']] };
    assert.equal(tableProblem(ok), undefined);
    assert.equal(tableProblem({ ...ok, name: ' ' }), 'it needs a name');
    assert.equal(tableProblem({ ...ok, columns: ['region', ''] }), 'column 2 needs a name');
    assert.equal(tableProblem({ ...ok, columns: ['region', 'region'] }), "'region' names two columns");
    assert.equal(tableProblem({ ...ok, rows: [['', '']] }), 'it needs a row with a value');
  });

  it("reads a block of cells as a sheet copies it: a line a row, tabs between values, the last line's break none", () => {
    assert.deepEqual(pastedCells('a\tb\r\n1\t2\r\n'), [['a', 'b'], ['1', '2']]);
    assert.deepEqual(pastedCells('one'), [['one']]);
    assert.deepEqual(pastedCells('x\t\ty\n'), [['x', '', 'y']]);
  });
});

describe("a flow sheet's page commands", () => {
  it('adds a flow, its boxes each after the box it reads, as the palette adds them', () => {
    const { page } = FLOW();
    assert.deepEqual(page.sheets.map((s) => [s.id, s.layout.kind]), [['sheet-1', 'bands'], ['sheet-2', 'flow']]);
    assert.deepEqual(at(page, 'grid-2'), { tile: 'grid-2', x: MARGIN, y: MARGIN });
    assert.deepEqual(at(page, 'grid-3'), { tile: 'grid-3', x: MARGIN + BOX_WIDTH + GAP_X, y: MARGIN });
    assert.deepEqual(at(page, 'grid-4'), { tile: 'grid-4', x: MARGIN + BOX_WIDTH + GAP_X, y: MARGIN + BOX_HEIGHT + GAP_Y });
    assert.equal(unnamedLabel(page, page.sheets[1]!), 'Flow 2', 'a flow is called a flow until someone names it');
    // read back whole: the page's own rules hold it
    assert.equal(pageToJson(readPage(JSON.parse(pageToJson(page)))), pageToJson(page));
  });

  it('places a box by hand: a flow\'s alone', () => {
    const d = FLOW();
    const moved = apply(d, { kind: 'placeBox', tile: 'grid-3', x: 500.4, y: 12 }).page;
    assert.deepEqual(at(moved, 'grid-3'), { tile: 'grid-3', x: 500, y: 12 });
    refused(() => apply(d, { kind: 'placeBox', tile: 'grid-1', x: 0, y: 0 }), /a tile of a board, placed by its layout: a box is a flow sheet's/);
    refused(() => apply(d, { kind: 'placeBox', tile: 'grid-3', x: -4, y: 0 }), /neither below zero/);
    refused(() => apply(d, { kind: 'placeBox', tile: 'grid-9', x: 0, y: 0 }), /grid-9/);
  });

  it('takes a text tile as a comment, placed as a box is, and left where it is by Tidy Up (M7)', () => {
    const d = apply(FLOW(), { kind: 'addText', id: 'text-1', sheet: 'sheet-2', text: 'Trades over 100 only', title: null, columns: null },
      { kind: 'placeBox', tile: 'text-1', x: 600, y: 300 });
    assert.deepEqual(at(d.page, 'text-1'), { tile: 'text-1', x: 600, y: 300 });
    readPage(JSON.parse(pageToJson(d.page)));
    const tidy = tidyCommands(d.page, 'sheet-2');
    const after = tidy.length === 0 ? d.page : apply(d, ...tidy).page;
    assert.deepEqual(at(after, 'text-1'), { tile: 'text-1', x: 600, y: 300 }, 'a comment stays where it was put');
  });

  it('takes a framed comment as a container: its frame kept by a move, folded, taken off; a grid never framed (M7)', () => {
    const frame = { w: 600, h: 200 };
    const d = apply(FLOW(), { kind: 'addText', id: 'text-1', sheet: 'sheet-2', text: 'Prep', title: null, columns: null },
      { kind: 'placeBox', tile: 'text-1', x: 20, y: 10, frame });
    assert.deepEqual(at(d.page, 'text-1'), { tile: 'text-1', x: 20, y: 10, frame });
    // moved, its frame kept; folded; a comment again
    assert.deepEqual(at(apply(d, { kind: 'placeBox', tile: 'text-1', x: 30, y: 10 }).page, 'text-1'), { tile: 'text-1', x: 30, y: 10, frame });
    assert.deepEqual(at(apply(d, { kind: 'placeBox', tile: 'text-1', x: 20, y: 10, frame: { ...frame, collapsed: true } }).page, 'text-1')?.frame,
      { ...frame, collapsed: true });
    assert.deepEqual(at(apply(d, { kind: 'placeBox', tile: 'text-1', x: 20, y: 10, frame: null }).page, 'text-1'), { tile: 'text-1', x: 20, y: 10 });
    // saved and read again with its frame; a frame too small, or on a grid, refused
    assert.deepEqual(at(readPage(JSON.parse(pageToJson(d.page))), 'text-1')?.frame, frame);
    refused(() => apply(d, { kind: 'placeBox', tile: 'text-1', x: 0, y: 0, frame: { w: 50, h: 50 } }), /a frame is at least 200 by 100/);
    refused(() => apply(d, { kind: 'placeBox', tile: 'grid-2', x: 0, y: 0, frame }), /a container is a comment/);
    assert.throws(() => readPage(JSON.parse(pageToJson(d.page).replace('"tile":"grid-2","x":40,"y":40', '"tile":"grid-2","x":40,"y":40,"frame":{"w":300,"h":200}'))),
      (e: unknown) => e instanceof PageDocumentError && /a container is a comment, not a grid/.test(e.message));
    // the boxes in it: those whose whole place is inside it, below its head
    const flow = flowOf(d.page);
    assert.deepEqual(framedBy(flow, 'text-1').sort(), flow.boxes.filter((b) => b.tile !== 'text-1' && b.x >= 20 && b.y >= 40 && b.x + BOX_WIDTH <= 620 && b.y + BOX_HEIGHT <= 210)
      .map((b) => b.tile).sort());
    assert.deepEqual(readPageCommand({ kind: 'placeBox', tile: 'text-1', x: 1, y: 2, frame: { w: 300, h: 200, collapsed: true } }),
      { kind: 'placeBox', tile: 'text-1', x: 1, y: 2, frame: { w: 300, h: 200, collapsed: true } });
  });

  it('refuses in words what only a board takes: a chart, a stack, an arrangement, a tile from a board', () => {
    const d = FLOW();
    refused(() => apply(d, { kind: 'stack', tiles: ['grid-2', 'grid-3'] }), /a flow sheet's boxes are not stacked/);
    refused(() => apply(d, { kind: 'arrange', sheet: 'sheet-2', preset: 'grid', order: null }), /a flow sheet's boxes are placed on its canvas/);
    refused(() => apply(d, { kind: 'moveTile', tile: 'grid-1', sheet: 'sheet-2', columns: null }), /a flow sheet holds boxes, each a grid that does one step: a grid stays on a board/);
    refused(() => apply(d, { kind: 'moveTile', tile: 'grid-3', sheet: 'sheet-1', columns: null }), /'grid-3' is a box of a flow: it moves to another flow sheet/);
    // a box between flows: placed on the other's canvas
    const two = apply(d, { kind: 'addFlow', id: 'sheet-5', name: 'Second' }, { kind: 'moveTile', tile: 'grid-4', sheet: 'sheet-5', columns: null }).page;
    assert.deepEqual(layoutTiles(two.sheets.find((s) => s.id === 'sheet-5')!.layout), ['grid-4']);
    assert.deepEqual(layoutTiles(flowOf(two)), ['grid-2', 'grid-3']);
  });

  it('a layout told whole is its own kind: a flow\'s boxes for a flow, a board\'s bands for a board', () => {
    const d = FLOW();
    const flow = flowOf(d.page);
    const told = apply(d, { kind: 'layout', sheet: 'sheet-2', layout: { kind: 'flow', boxes: [...flow.boxes].reverse() } }).page;
    assert.deepEqual(layoutTiles(flowOf(told)), ['grid-4', 'grid-3', 'grid-2']);
    refused(() => apply(d, { kind: 'layout', sheet: 'sheet-2', layout: { kind: 'bands', ...add(NEW_SHEET, 'grid-2') } }),
      /'sheet-2' is a flow sheet: its layout is boxes/);
    refused(() => apply(d, { kind: 'layout', sheet: 'sheet-2', layout: { kind: 'flow', boxes: flow.boxes.slice(1) } }),
      /a sheet's layout holds its tiles, each once/);
  });

  it('a box taken off leaves every box that read it live reading a copy of what it was (FL6)', () => {
    const { page } = apply(FLOW(), { kind: 'removeTile', tile: 'grid-2' });
    assert.deepEqual(layoutTiles(flowOf(page)), ['grid-3', 'grid-4']);
    for (const id of ['grid-3', 'grid-4']) {
      const source = page.cubes.find((c) => c.id === id)!.cube.source;
      assert.deepEqual(liveGrids(source), [], `${id} reads no box live`);
      if (source._type !== 'rows' || 'grid' in source.input) throw new Error(`${id} is not frozen`);
      assert.equal(source.input.name, 'trades.csv');
      assert.deepEqual(source.input.source, FILE);
    }
    // the page read back: no link left to a grid it does not have
    readPage(JSON.parse(pageToJson(page)));
  });

  it('reads and writes its commands, refused in words', () => {
    for (const c of [{ kind: 'addFlow', id: 'sheet-3', name: 'Prep' }, { kind: 'addFlow', name: null }, { kind: 'placeBox', tile: 'grid-2', x: 10, y: 20 }]) {
      assert.deepEqual(readPageCommand(JSON.parse(JSON.stringify(c))), c);
    }
    assert.throws(() => readPageCommand({ kind: 'placeBox', tile: 'grid-2', x: '10', y: 20 }), /a box's place is two numbers, not \("10", 20\)/);
    assert.throws(() => readPageCommand({ kind: 'placeBox', tile: 'grid-2', x: 1, y: 2, z: 3 }), /z/);
  });

  it("follows the page's renames, and a layout told before a box came keeps it where adding put it", () => {
    const renamed = new Map([['grid-3', 'grid-7'], ['sheet-2', 'sheet-9']]);
    assert.deepEqual(renamedCommand({ kind: 'placeBox', tile: 'grid-3', x: 1, y: 2 }, renamed), { kind: 'placeBox', tile: 'grid-7', x: 1, y: 2 });
    assert.deepEqual(renamedCommand({ kind: 'addFlow', id: 'sheet-2', name: null }, renamed), { kind: 'addFlow', id: 'sheet-9', name: null });
    const { page } = FLOW();
    const stale: PageCommand = { kind: 'layout', sheet: 'sheet-2', layout: { kind: 'flow', boxes: [{ tile: 'grid-2', x: 400, y: 400 }, { tile: 'gone', x: 0, y: 0 }] } };
    const now = rearranged(stale, page);
    if (now.kind !== 'layout' || now.layout.kind !== 'flow') throw new Error('not a flow layout');
    assert.deepEqual(now.layout.boxes.find((b) => b.tile === 'grid-2'), { tile: 'grid-2', x: 400, y: 400 });
    assert.deepEqual(now.layout.boxes.map((b) => b.tile).sort(), ['grid-2', 'grid-3', 'grid-4']);
  });
});

describe("a box's input set (setInput: docs/DATACUBE_FLOW_MODE_2026_10_24.md M2)", () => {
  const sourceOf = (page: PageDocument, id: string): CubeSource => page.cubes.find((c) => c.id === id)!.cube.source;

  it('links a box to another of the page, live; freezes it to a copy of what its input is now', () => {
    const d = FLOW();
    // the Sort after the Filter, not the Input: a line drawn from the Filter's output
    const relinked = apply(d, { kind: 'setInput', grid: 'grid-4', side: 'input', input: { grid: 'grid-3' } });
    assert.deepEqual(liveGrids(sourceOf(relinked.page, 'grid-4')), ['grid-3']);
    // its query as it is: only what it reads moved
    assert.deepEqual(relinked.page.cubes.find((c) => c.id === 'grid-4')!.cube.query, d.page.cubes.find((c) => c.id === 'grid-4')!.cube.query);
    const frozen = apply(relinked, { kind: 'setInput', grid: 'grid-4', side: 'input', input: null }).page;
    const s = sourceOf(frozen, 'grid-4');
    if (s._type !== 'rows' || isLive(s.input)) throw new Error('not frozen');
    assert.equal(s.input.source._type, 'rows', "a copy of the Filter: its source the Filter's, resolved");
    assert.deepEqual(liveGrids(s), []);
    readPage(JSON.parse(pageToJson(frozen)));
  });

  it('refuses in words: a cycle, a grid the page has not, a side the source has not, the same input, a board grid frozen twice', () => {
    const d = FLOW();
    refused(() => apply(d, { kind: 'setInput', grid: 'grid-2', side: 'input', input: { grid: 'grid-3' } }),
      /trades\.csv reads no other grid: it has no input/);
    const chain = apply(d, { kind: 'setInput', grid: 'grid-4', side: 'input', input: { grid: 'grid-3' } });
    refused(() => apply(chain, { kind: 'setInput', grid: 'grid-3', side: 'input', input: { grid: 'grid-4' } }), /reads its own rows/);
    refused(() => apply(d, { kind: 'setInput', grid: 'grid-3', side: 'input', input: { grid: 'grid-3' } }), /reads its own rows/);
    refused(() => apply(d, { kind: 'setInput', grid: 'grid-3', side: 'input', input: { grid: 'grid-9' } }), /'grid-9' is no grid of this page/);
    refused(() => apply(d, { kind: 'setInput', grid: 'grid-3', side: 'left', input: { grid: 'grid-1' } }), /its one input is its input, not its left/);
    refused(() => apply(d, { kind: 'setInput', grid: 'grid-3', side: 'input', input: { grid: 'grid-2' } }), /reads 'grid-2' already/);
    const frozen = apply(d, { kind: 'setInput', grid: 'grid-3', side: 'input', input: null });
    refused(() => apply(frozen, { kind: 'setInput', grid: 'grid-3', side: 'input', input: null }), /reads a copy already: it is frozen/);
  });

  it("sets a join's left and right, each live or a copy", () => {
    const join = (left: { grid: string }, right: { grid: string }): CubeSource => ({
      _type: 'join', name: 'trades ⋈ trades', left, right, columns: COLUMNS,
      how: { form: 'join', kind: 'inner', on: { rows: [{ left: 'region', op: '==', right: 'region' }] }, prefix: 'their_', keep: null },
    });
    const d = apply(FLOW(), addGrid(cube(join({ grid: 'grid-3' }, { grid: 'grid-4' })), 'sheet-2', 'grid-3'));
    assert.deepEqual(liveGrids(sourceOf(d.page, 'grid-5')), ['grid-3', 'grid-4']);
    const right = apply(d, { kind: 'setInput', grid: 'grid-5', side: 'right', input: { grid: 'grid-2' } }).page;
    assert.deepEqual(liveGrids(sourceOf(right, 'grid-5')), ['grid-3', 'grid-2']);
    refused(() => apply(d, { kind: 'setInput', grid: 'grid-5', side: 'input', input: { grid: 'grid-2' } }), /is a join: its inputs are its left and its right/);
    // a box of the join's inputs taken off: the join reads a copy of it, its other input still live (FL6)
    const gone = apply(d, { kind: 'removeTile', tile: 'grid-4' }).page;
    assert.deepEqual(liveGrids(sourceOf(gone, 'grid-5')), ['grid-3']);
    readPage(JSON.parse(pageToJson(gone)));
  });

  it('a box opened again passes on the columns its input gained, as a saved grid over a file does not', () => {
    const wider = [...COLUMNS, { name: 'desk', type: 'String' }];
    const box = cubeOpenedAgain(cube(rows('grid-2')), { ...rows('grid-2'), columns: wider });
    assert.deepEqual(box.notes, [], 'nothing hidden, nothing to say');
    assert.notEqual((box.document.configuration['columns'] as Record<string, { hidden?: boolean }> | undefined)?.['desk']?.hidden, true);
    const filed = cubeOpenedAgain(cube(FILE), { ...FILE, columns: wider });
    assert.deepEqual(filed.notes, ['1 new column since this cube was saved, hidden: desk']);
  });

  it("reads a Filter's False port (FL7): its input's rows its condition does not hold for; refused with no filter", () => {
    const d = apply(FLOW(), { kind: 'setInput', grid: 'grid-4', side: 'input', input: { grid: 'grid-3', port: 'false' } });
    const live = sourceOf(d.page, 'grid-4');
    assert.deepEqual(live._type === 'rows' ? live.input : undefined, { grid: 'grid-3', port: 'false' });
    assert.deepEqual(liveGrids(live), ['grid-3']);
    // resolved: the Filter's query, its condition turned round
    const read = resolvedSource(d.page, live, 'grid-4');
    const filter = read._type === 'rows' && !isLive(read.input) ? read.input.query.filter : undefined;
    assert.deepEqual(filter, { kind: 'not', child: { kind: 'condition', column: 'notional', operator: 'greaterThan', value: 100 } });
    // frozen when the Filter goes: a copy of its False, not its rows
    const gone = apply(d, { kind: 'removeTile', tile: 'grid-3' }).page;
    const frozen = sourceOf(gone, 'grid-4');
    assert.equal(frozen._type === 'rows' && !isLive(frozen.input) ? frozen.input.query.filter?.kind : undefined, 'not');
    // a box with no filter has no False
    refused(() => apply(FLOW(), { kind: 'setInput', grid: 'grid-4', side: 'input', input: { grid: 'grid-2', port: 'false' } }),
      /'grid-2' has no filter: its False port leaves no row out/);
    assert.deepEqual(readPageCommand({ kind: 'setInput', grid: 'grid-4', side: 'input', input: { grid: 'grid-3', port: 'false' } }),
      { kind: 'setInput', grid: 'grid-4', side: 'input', input: { grid: 'grid-3', port: 'false' } });
    assert.throws(() => readPageCommand({ kind: 'setInput', grid: 'grid-4', side: 'input', input: { grid: 'grid-3', port: 'true' } }),
      /a live input's port is a Filter's 'false', not "true"/);
    assert.deepEqual(renamedCommand({ kind: 'setInput', grid: 'grid-4', side: 'input', input: { grid: 'grid-3', port: 'false' } }, new Map([['grid-3', 'grid-9']])),
      { kind: 'setInput', grid: 'grid-4', side: 'input', input: { grid: 'grid-9', port: 'false' } });
    assert.deepEqual(boxInputs(d.page.cubes.find((c) => c.id === 'grid-4')!.cube), [{ side: 'input', grid: 'grid-3', port: 'false' }]);
  });

  it("sets a Join box's join again (setJoin): its inputs as they are, undone by the commands between", () => {
    const join: CubeSource = {
      _type: 'join', name: 'trades ⋈ trades', left: { grid: 'grid-3' }, right: { grid: 'grid-4' }, columns: COLUMNS,
      how: { form: 'join', kind: 'inner', on: { rows: [{ left: 'region', op: '==', right: 'region' }] }, prefix: 'their_', keep: null },
    };
    const d = apply(FLOW(), addGrid(cube(join), 'sheet-2', 'grid-3'));
    const union = apply(d, { kind: 'setJoin', grid: 'grid-5', how: { form: 'append' } }).page;
    const now = sourceOf(union, 'grid-5');
    assert.ok(now._type === 'join' && now.how.form === 'append');
    assert.deepEqual(liveGrids(now), ['grid-3', 'grid-4'], 'its inputs as they are');
    assert.equal(boxSays(union.cubes.find((c) => c.id === 'grid-5')!.cube).tool, 'union', 'an Append is a Union box');
    assert.equal(boxSays(d.page.cubes.find((c) => c.id === 'grid-5')!.cube).tool, 'join');
    refused(() => apply({ page: union, held: idsOf(union) }, { kind: 'setJoin', grid: 'grid-5', how: { form: 'append' } }), /joins so already/);
    refused(() => apply(d, { kind: 'setJoin', grid: 'grid-3', how: { form: 'append' } }), /is no join/);
    const back = applyPlanned({ page: union, held: idsOf(union) }, pageBetween(union, d.page, 'history'), false).draft.page;
    assert.deepEqual(sourceOf(back, 'grid-5'), sourceOf(d.page, 'grid-5'), 'undone: its join as it was');
  });

  it('reads and writes it, follows renames, and is the undo of a removal that froze its readers', () => {
    for (const c of [
      { kind: 'setInput', grid: 'grid-3', side: 'input', input: { grid: 'grid-2' } },
      { kind: 'setInput', grid: 'grid-5', side: 'right', input: null },
    ]) assert.deepEqual(readPageCommand(JSON.parse(JSON.stringify(c))), c);
    assert.throws(() => readPageCommand({ kind: 'setInput', grid: 'grid-3', side: 'top', input: null }), /its input, or a join's left or right, not "top"/);
    assert.throws(() => readPageCommand({ kind: 'setInput', grid: 'grid-3', side: 'input', input: { grid: 'grid-2', x: 1 } }), /a live input is its grid alone, and its port, not x/);
    const renamed = new Map([['grid-2', 'grid-8'], ['grid-3', 'grid-7']]);
    assert.deepEqual(renamedCommand({ kind: 'setInput', grid: 'grid-3', side: 'input', input: { grid: 'grid-2' } }, renamed),
      { kind: 'setInput', grid: 'grid-7', side: 'input', input: { grid: 'grid-8' } });
    // a box taken off, and back: its readers live on it again, by the commands between the two pages
    const before = FLOW().page;
    const after = apply(FLOW(), { kind: 'removeTile', tile: 'grid-2' }).page;
    const back = pageBetween(after, before, 'history');
    assert.ok(back.some((c) => c.kind === 'setInput'), 'a setInput among the undo');
    const undone = applyPlanned({ page: after, held: idsOf(after) }, back, false).draft.page;
    for (const id of ['grid-3', 'grid-4']) assert.deepEqual(liveGrids(sourceOf(undone, id)), ['grid-2'], `${id} live on the Input again`);
  });

  it('undoes a swap of two boxes, each frozen first where setting it at once would read its own rows', () => {
    const d = apply(FLOW(), { kind: 'setInput', grid: 'grid-4', side: 'input', input: { grid: 'grid-3' } });
    // grid-2 → grid-3 → grid-4, then grid-2 → grid-4 → grid-3
    const swapped = apply(d, { kind: 'setInput', grid: 'grid-4', side: 'input', input: { grid: 'grid-2' } },
      { kind: 'setInput', grid: 'grid-3', side: 'input', input: { grid: 'grid-4' } });
    for (const [from, to] of [[d.page, swapped.page], [swapped.page, d.page]] as const) {
      const made = applyPlanned({ page: from, held: idsOf(from) }, pageBetween(from, to, 'history'), false).draft.page;
      for (const id of ['grid-3', 'grid-4']) assert.deepEqual(sourceOf(made, id), sourceOf(to, id), id);
    }
  });

  it("a flow sheet's removal undone makes a flow sheet, its boxes where they were", () => {
    const before = FLOW().page;
    const after = apply(FLOW(), { kind: 'removeSheet', sheet: 'sheet-2' }).page;
    const back = pageBetween(after, before, 'history');
    assert.ok(back.some((c) => c.kind === 'addFlow'), 'an addFlow among the undo');
    const undone = applyPlanned({ page: after, held: idsOf(after) }, back, false).draft.page;
    assert.deepEqual(flowOf(undone), flowOf(before));
  });
});

describe('a flow page read, and what it opens as it opens', () => {
  it('refuses a flow\'s tile that is no grid, and a box placed twice or off the canvas', () => {
    const page = FLOW().page;
    const raw = JSON.parse(pageToJson(page)) as { sheets: { layout: { boxes?: unknown[] } }[]; views: unknown[] };
    const withBoxes = (boxes: unknown[]): unknown => ({ ...raw, sheets: raw.sheets.map((s, i) => (i === 1 ? { ...s, layout: { ...s.layout, boxes } } : s)) });
    const box = (tile: string, x = 0, y = 0): unknown => ({ tile, x, y });
    assert.throws(() => readPage(withBoxes([box('grid-2'), box('grid-3'), box('grid-4'), box('grid-4', 9, 9)])),
      (e: unknown) => e instanceof PageDocumentError && /twice/.test(e.message));
    assert.throws(() => readPage(withBoxes([box('grid-2', -1), box('grid-3'), box('grid-4')])), /neither below zero/);
  });

  it('opens none of a flow\'s boxes as it opens: each is a document until it is shown (boxCubes)', () => {
    const { page } = FLOW();
    assert.deepEqual([...boxCubes(page)].sort(), ['grid-2', 'grid-3', 'grid-4']);
    assert.deepEqual([...boxCubes(BOARD())], [], 'a board\'s grids open as the page opens');
  });
});
