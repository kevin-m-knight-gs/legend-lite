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
import { CubeRefusal, rowColumns, type CubeSnapshot, type DerivedColumn, type FilterNode } from '../src/snapshot.ts';
import { readUnique, uniqueCommands, uniqueOf } from '../src/unique.ts';
import { correlationLambda, correlationsOf, profileLambda, profileOf } from '../src/profile.ts';
import { imputeCommands, imputedOf, readImputation } from '../src/impute.ts';
import { dateToolCommands, dateToolOf, readDateTool } from '../src/date-tools.ts';
import { multiFieldOf } from '../src/multi-field.ts';
import { rangesWords, readRanges, readRowSample, rowSampleCommands, rowSampleOf, sampleCommands } from '../src/row-sample.ts';
import { readTranspose, transposeCommands, transposeOf } from '../src/transpose.ts';
import { readSelection, retypesOf, selectCommands, selectionOf } from '../src/select-columns.ts';
import { handedOn } from '../src/box-columns.ts';
import { readSummary, summarizeCommands } from '../src/summarize.ts';
import { unmatchedOn } from '../src/join-how.ts';
import { findReplaceCommands, findReplaceOf, readFindReplace } from '../src/find-replace.ts';
import { countRecordsCommands, countRecordsOf, readCountRecords } from '../src/count-records.ts';
import { summaryActions, summaryOf } from '../src/grid-edits.ts';
import { applyGridCommands } from '../src/grid-commands.ts';
import type { GridDraft } from '../src/grid-edits.ts';
import { multiFieldCommands, multiFieldText, readMultiField } from '../src/multi-field-columns.ts';
import { TreeState } from '../src/tree.ts';
import { accessor, collection, fn, lambda, lit, property, variable } from '../../pure-protocol/src/index.ts';
import { textToolOf } from '../src/text-tools.ts';
import { readTextTool, textToolColumns, textToolProblem } from '../src/text-tool-columns.ts';
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
    // into rows: one column, exploded over split -- a row a part
    const rowsOf = textToolColumns({ kind: 'split', column: 'book', delimiter: ',', parts: 1, name: 'part', rows: true });
    assert.deepEqual(rowsOf.map((p) => [p.name, p.unnest]), [['part', true]]);
    assert.equal(body(rowsOf[0]!), JSON.stringify(fn('split', fn('toOne', property(variable('x'), 'book')), lit.string(','))));
    assert.deepEqual(said(rowsOf), { tool: 'textToColumns', also: [], says: "book split on ',' to rows" });
    assert.deepEqual(readTextTool({ kind: 'split', column: 'book', delimiter: ',', name: 'part', rows: true }),
      { kind: 'split', column: 'book', delimiter: ',', parts: 1, name: 'part', rows: true });
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

describe("Sample's other ways (row-sample.ts): a row number and a filter, as Unique's", () => {
  const typed = { ...rawRows(accessor('local::DB', 'trades'), COLUMNS) };
  const columnsOf = (c: readonly unknown[]) => (c as { kind: string; column?: DerivedColumn }[]).filter((x) => x.kind === 'calculate').map((x) => x.column!);

  it('numbers its rows in an order, per group, and filters on the number: first, last, all but the first, one of every', () => {
    const first = rowSampleCommands(typed, { kind: 'first', n: 2, by: 'notional', groups: ['region'] });
    assert.deepEqual(columnsOf(first)[0]!.window, { fn: 'rowNumber', partition: ['region'], order: [{ column: 'notional', direction: 'asc' }] });
    assert.deepEqual(first.find((c) => c.kind === 'addFilter'), { kind: 'addFilter', condition: { kind: 'condition', column: 'SampleRow', operator: 'lessThanEqual', value: 2 } });
    assert.deepEqual(first.at(-1), { kind: 'configure', patch: { columns: { SampleRow: { hidden: true } } } });
    const last = rowSampleCommands(typed, { kind: 'last', n: 1, by: 'notional', groups: ['region'] });
    assert.equal(columnsOf(last)[0]!.window?.order[0]?.direction, 'desc');
    const skip = rowSampleCommands(typed, { kind: 'skip', n: 10, by: 'notional', groups: [] });
    assert.equal((skip.find((c) => c.kind === 'addFilter') as { condition: { operator: string } }).condition.operator, 'greaterThan');
    const every = rowSampleCommands(typed, { kind: 'every', n: 3, by: 'notional', groups: [] });
    const [row, rem] = columnsOf(every);
    assert.equal(JSON.stringify(rem!.lambda?.body[0]), JSON.stringify(
      fn('mod', fn('toOne', property(variable('x'), 'SampleRow')), lit.integer(3))));
    assert.equal(row!.window?.fn, 'rowNumber');
  });

  it('is read back off its query, a Sample box saying what it keeps; its columns none of a Formula or a Select', () => {
    const says = (commands: ReturnType<typeof rowSampleCommands>) => {
      const derived = columnsOf(commands);
      const filter = (commands.find((c) => c.kind === 'addFilter') as { condition: FilterNode }).condition;
      const hidden = Object.fromEntries(derived.map((d) => [d.name, { hidden: true }]));
      return boxSays(cube(rows('grid-1'), (q) => ({ ...q, derived, filter }), { columns: hidden }));
    };
    assert.deepEqual(says(rowSampleCommands(typed, { kind: 'first', n: 2, by: 'notional', groups: ['region'] })),
      { tool: 'sample', also: [], says: 'first 2 of each region by notional' });
    assert.deepEqual(says(rowSampleCommands(typed, { kind: 'every', n: 3, by: 'notional', groups: [] })),
      { tool: 'sample', also: [], says: 'one of every 3 by notional' });
    assert.deepEqual(says(rowSampleCommands(typed, { kind: 'skip', n: 10, by: 'notional', groups: [] })),
      { tool: 'sample', also: [], says: 'all but the first 10 by notional' });
    const every = rowSampleCommands(typed, { kind: 'every', n: 3, by: 'notional', groups: ['region'] });
    const read = rowSampleOf({ derived: columnsOf(every), filter: (every.find((c) => c.kind === 'addFilter') as { condition: FilterNode }).condition });
    assert.deepEqual(read, { kind: 'every', n: 3, by: 'notional', groups: ['region'], columns: ['SampleRow', 'SampleEvery'] });
  });

  it('keeps the rows at some places (Select Records): its ranges or-ed over the number, read back and said; refused in words', () => {
    assert.deepEqual(readRanges('1-10, 20, 30+, -5'), [{ from: 1, to: 10 }, { from: 20, to: 20 }, { from: 30 }, { from: 1, to: 5 }]);
    assert.equal(rangesWords(readRanges(' 1-10,20 , 30+')), '1-10, 20, 30+');
    refused(() => readRanges('ten'), /'ten' is no range of rows/);
    refused(() => readRanges(' , '), /name the rows it keeps/);
    const t = { kind: 'records' as const, n: 3, by: 'notional', groups: [], ranges: readRanges('1-10, 20, 30+') };
    const commands = rowSampleCommands(typed, t);
    const filter = (commands.find((c) => c.kind === 'refilter') as { readonly add: readonly FilterNode[] }).add[0]!;
    assert.deepEqual(filter, { kind: 'or', children: [
      { kind: 'and', children: [{ kind: 'condition', column: 'SampleRow', operator: 'greaterThanEqual', value: 1 },
        { kind: 'condition', column: 'SampleRow', operator: 'lessThanEqual', value: 10 }] },
      { kind: 'condition', column: 'SampleRow', operator: 'equal', value: 20 },
      { kind: 'condition', column: 'SampleRow', operator: 'greaterThanEqual', value: 30 },
    ] });
    const derived = columnsOf(commands);
    assert.deepEqual(rowSampleOf({ derived, filter }), { ...t, columns: ['SampleRow'] });
    assert.deepEqual(boxSays(cube(rows('grid-1'), (q) => ({ ...q, derived, filter }), { columns: { SampleRow: { hidden: true } } })),
      { tool: 'sample', also: [], says: 'rows 1-10, 20, 30+ by notional' });
    // one row alone: an equal, still a Select Records (a remainder's 1 is one of every N's)
    const one = rowSampleCommands(typed, { ...t, n: 1, ranges: [{ from: 7, to: 7 }] });
    assert.equal(rowSampleOf({ derived: columnsOf(one), filter: (one.find((c) => c.kind === 'addFilter') as { condition: FilterNode }).condition })?.kind, 'records');
    refused(() => rowSampleCommands(typed, { ...t, ranges: [] }), /name the rows it keeps/);
    refused(() => rowSampleCommands(typed, { ...t, ranges: [{ from: 5, to: 2 }] }), /5-2 is no range of rows/);
    assert.deepEqual(readRowSample({ kind: 'sample', mode: 'records', ranges: '2-3', by: 'notional' }),
      { kind: 'records', n: 1, by: 'notional', groups: [], ranges: [{ from: 2, to: 3 }] });
    refused(() => readRowSample({ kind: 'sample', mode: 'first', n: 2, by: 'notional', ranges: '1' }), /only a Select Records/);
  });

  it('keeps a percent at random, the same rows for a seed: its chance the hash of a row number in a total order', () => {
    const t = { kind: 'random' as const, n: 25, by: '', groups: [], seed: 7 };
    const commands = rowSampleCommands(typed, t);
    const [numbered, chance] = columnsOf(commands);
    assert.deepEqual(numbered!.window?.order, [{ column: 'region', direction: 'asc' }, { column: 'notional', direction: 'asc' }], 'every column that sorts');
    assert.equal(JSON.stringify(chance!.lambda?.body[0]), JSON.stringify(fn('mod', fn('abs', fn('hashCode',
      collection([fn('toOne', property(variable('x'), 'SampleRow')), lit.integer(7)]))), lit.integer(100))));
    const filter = (commands.find((c) => c.kind === 'addFilter') as { condition: FilterNode }).condition;
    assert.deepEqual(filter, { kind: 'condition', column: 'SampleChance', operator: 'lessThan', value: 25 });
    assert.deepEqual(rowSampleOf({ derived: columnsOf(commands), filter }), { ...t, columns: ['SampleRow', 'SampleChance'] });
    assert.deepEqual(boxSays(cube(rows('grid-1'), (q) => ({ ...q, derived: columnsOf(commands), filter }))).says, '25% at random, seed 7');
    refused(() => rowSampleCommands(typed, { ...t, n: 100 }), /a whole percent of the rows, from 1 to 99/);
    refused(() => rowSampleCommands(typed, { ...t, groups: ['region'] }), /a random sample is of all the rows/);
    assert.deepEqual(readRowSample({ kind: 'sample', mode: 'random', n: 10, seed: 3 }), { kind: 'random', n: 10, by: '', groups: [], seed: 3 });
  });

  it("goes from the sample it has to the one asked: a Top N for the first of all, a row sample's columns made again", () => {
    let draft: GridDraft = { snapshot: typed, config: DEFAULT_CONFIGURATION };
    const step = (t: Parameters<typeof sampleCommands>[1]): CubeSnapshot => {
      draft = applyGridCommands(draft, sampleCommands(draft.snapshot, t));
      return draft.snapshot;
    };
    assert.equal(rowSampleOf(step({ kind: 'first', n: 2, by: 'notional', groups: ['region'] }))?.kind, 'first');
    // to one of every 3: the old number off, the new one under the same name
    const every = step({ kind: 'every', n: 3, by: 'notional', groups: [] });
    assert.deepEqual(every.derived.map((d) => d.name), ['SampleRow', 'SampleEvery']);
    assert.equal(rowSampleOf(every)?.kind, 'every');
    // to the first 5 of all the rows: its Top N, the row sample gone
    const top = step({ kind: 'first', n: 5, by: 'notional', groups: [] });
    assert.deepEqual([top.derived.length, top.filter, top.topN], [0, undefined, { by: 'notional', n: 5, bottom: true }]);
    assert.deepEqual(sampleCommands(top, null), [{ kind: 'topN', topN: null }]);
  });

  it('refuses in words what it cannot sample', () => {
    assert.throws(() => rowSampleCommands(typed, { kind: 'every', n: 1, by: 'notional', groups: [] }), /one row of every 1 is every row/);
    assert.throws(() => rowSampleCommands(typed, { kind: 'skip', n: 0, by: 'notional', groups: [] }), /a whole number from 1/);
    assert.throws(() => rowSampleCommands(typed, { kind: 'first', n: 2, by: 'nope', groups: [] }), /no column 'nope'/);
    assert.throws(() => rowSampleCommands(typed, { kind: 'first', n: 2, by: 'notional', groups: [] }), /its Top N/);
    assert.deepEqual(readRowSample({ kind: 'sample', mode: 'skip', n: 3, by: 'notional' }), { kind: 'skip', n: 3, by: 'notional', groups: [] });
    assert.equal(readRowSample({ kind: 'sample', mode: null }), null);
    assert.throws(() => readRowSample({ kind: 'sample', mode: 'shuffle', n: 3, by: 'notional' }), /its mode is first, last, skip, every, records, random/);
  });
});

describe("Select (select-columns.ts): the columns a box keeps and the names it hands them on by", () => {
  const typed = { ...rawRows(accessor('local::DB', 'trades'), [...COLUMNS, { name: 'desk', type: 'String' }]) };

  it('hides what it leaves out and names what it renames, every other setting cleared; the box says both', () => {
    const [c] = selectCommands(typed, { keep: ['region', 'notional'], rename: { region: 'area' } }) as unknown as [{ patch: { columns: object } }];
    assert.deepEqual(c.patch.columns, {
      region: { hidden: null, displayName: 'area' }, notional: { hidden: null, displayName: null }, desk: { hidden: true, displayName: null },
    });
    const box = cube(rows('grid-1'), (q) => ({ ...q, columns: typed.columns }), { columns: { region: { displayName: 'area' }, desk: { hidden: true } } });
    assert.deepEqual(boxSays(box), { tool: 'select', also: [], says: 'region → area; 1 of 3 columns left out' });
  });

  it("retypes a column: a conversion of it named for it, the column hidden and the conversion handed on by its name", () => {
    const coded = { ...rawRows(accessor('local::DB', 'trades'), [...COLUMNS, { name: 'code', type: 'String' }]) };
    const commands = selectCommands(coded, { keep: ['region', 'code'], rename: {}, retype: { code: 'Integer' } });
    const [calc, conf] = commands as unknown as [{ kind: string; column: DerivedColumn }, { patch: { columns: Record<string, unknown> } }];
    assert.equal(calc.column.name, 'code__Integer');
    assert.equal(JSON.stringify(calc.column.lambda?.body[0]), JSON.stringify(fn('parseInteger', fn('toOne', property(variable('x'), 'code')))));
    assert.equal(calc.column.kind, 'measure');
    assert.deepEqual(conf.patch.columns['code'], { hidden: true, displayName: null });
    assert.deepEqual(conf.patch.columns['code__Integer'], { hidden: null, displayName: 'code' });
    // read back: its retype, its column kept under its own name, and the box says it
    const derived = [calc.column];
    const config = { columns: { code: { hidden: true }, code__Integer: { displayName: 'code' }, notional: { hidden: true } } };
    assert.deepEqual(retypesOf(derived), [{ name: 'code__Integer', column: 'code', to: 'Integer' }]);
    const row = selectionOf({ ...coded, derived }, config).find((c) => c.name === 'code');
    assert.deepEqual([row?.kept, row?.as, row?.retype], [true, 'code', 'Integer']);
    const box = cube(rows('grid-1'), (q) => ({ ...q, columns: coded.columns, derived }), config);
    assert.deepEqual(boxSays(box), { tool: 'select', also: [], says: 'code as Integer; 1 of 3 columns left out' });
    // set again without it: the conversion taken off, the column shown again
    const again = selectCommands({ ...coded, derived }, { keep: ['region', 'code'], rename: {} });
    assert.deepEqual(again[0], { kind: 'removeCalculated', name: 'code__Integer' });
    assert.throws(() => selectCommands(coded, { keep: ['notional'], rename: {}, retype: { notional: 'Boolean' } }),
      /'notional' \(Float\) cannot be made Boolean: it can be made String, Integer, Decimal/);
  });

  it("hands its columns on in its order -- its window's rows', a conversion in its column's place -- said on its box", () => {
    const coded = { ...rawRows(accessor('local::DB', 'trades'), [...COLUMNS, { name: 'code', type: 'String' }]) };
    const applied = (t: Parameters<typeof selectCommands>[1]) => {
      const commands = selectCommands(coded, t);
      const draft = applyGridCommands({ snapshot: coded, config: DEFAULT_CONFIGURATION }, commands);
      const config = draft.config as unknown as Record<string, unknown>;
      const box = cube(rows('grid-1'), (q) => ({ ...q, columns: draft.snapshot.columns, derived: draft.snapshot.derived }), config);
      return { commands, s: draft.snapshot, config, box };
    };
    const moved = applied({ keep: ['region', 'notional', 'code'], rename: {}, retype: { code: 'Integer' }, order: ['code', 'region', 'notional'] });
    assert.deepEqual((moved.commands.at(-1) as unknown as { patch: { columnOrder: string[] } }).patch.columnOrder, ['code__Integer', 'region', 'notional']);
    assert.deepEqual(handedOn(rowColumns(moved.s).map((c) => c.name), moved.s, moved.config).map((c) => c.as), ['code', 'region', 'notional']);
    assert.deepEqual(selectionOf(moved.s, moved.config).map((c) => c.name), ['code', 'region', 'notional'], 'its window: its rows in its order');
    assert.equal(boxSays(moved.box).says, 'code as Integer; columns reordered');
    // a retype alone keeps its column's place -- here between the two -- and says no order of its own
    const kept = applied({ keep: ['region', 'notional', 'code'], rename: {}, retype: { code: 'Integer' }, order: ['region', 'code', 'notional'] });
    assert.deepEqual(handedOn(rowColumns(kept.s).map((c) => c.name), kept.s, kept.config).map((c) => c.as), ['region', 'code', 'notional']);
    assert.equal(boxSays(kept.box).says, 'code as Integer; columns reordered', 'moved between them: an order of its own');
    const inPlace = { ...rawRows(accessor('local::DB', 'trades'), [COLUMNS[0]!, { name: 'code', type: 'String' }, COLUMNS[1]!]) };
    const draft = applyGridCommands({ snapshot: inPlace, config: DEFAULT_CONFIGURATION },
      selectCommands(inPlace, { keep: ['region', 'code', 'notional'], rename: {}, retype: { code: 'Integer' }, order: ['region', 'code', 'notional'] }));
    const config = draft.config as unknown as Record<string, unknown>;
    assert.deepEqual(handedOn(rowColumns(draft.snapshot).map((c) => c.name), draft.snapshot, config).map((c) => c.as), ['region', 'code', 'notional'],
      'its conversion where its column was');
    assert.equal(boxSays(cube(rows('grid-1'), (q) => ({ ...q, columns: draft.snapshot.columns, derived: draft.snapshot.derived }), config)).says,
      'code as Integer', 'in its place: no order of its own said');
    refused(() => selectCommands(coded, { keep: ['region'], rename: {}, order: ['region', 'region'] }), /names 'region' twice/);
    refused(() => selectCommands(coded, { keep: ['region'], rename: {}, order: ['nope'] }), /its order names no column 'nope'/);
    assert.deepEqual(readSelection({ kind: 'selectColumns', keep: ['a'], order: ['a'] }), { keep: ['a'], rename: {}, order: ['a'] });
  });

  it('refuses in words what it cannot hand on', () => {
    assert.throws(() => selectCommands(typed, { keep: [], rename: {} }), /keep a column/);
    assert.throws(() => selectCommands(typed, { keep: ['nope'], rename: {} }), /no column 'nope'/);
    assert.throws(() => selectCommands(typed, { keep: ['region', 'desk'], rename: { region: 'desk' } }), /'region' and 'desk' would both be handed on as 'desk'/);
    assert.throws(() => selectCommands(typed, { keep: ['region'], rename: { region: ' ' } }), /renamed to nothing/);
    assert.deepEqual(readSelection({ kind: 'selectColumns', keep: ['a'] }), { keep: ['a'], rename: {} });
    assert.throws(() => readSelection({ kind: 'selectColumns', keep: ['a'], rename: { a: 1 } }), /its renames are \{column: new name\}/);
    assert.deepEqual(readSelection({ kind: 'selectColumns', keep: ['a'], retype: { a: 'Float' } }), { keep: ['a'], rename: {}, retype: { a: 'Float' } });
    assert.throws(() => readSelection({ kind: 'selectColumns', keep: ['a'], retype: { a: 'Money' } }), /its retypes are/);
  });
});

describe('Summarize (summarize.ts): a row a group, each column grouped by, aggregated or left out -- one step', () => {
  const typed = { ...rawRows(accessor('local::DB', 'trades'), [...COLUMNS, { name: 'desk', type: 'String' }]) };

  it('groups, aggregates and hides what it leaves out; read back as it was set', () => {
    const commands = summarizeCommands(typed, { columns: [{ name: 'region', action: 'group' }, { name: 'notional', action: 'average' }] });
    assert.deepEqual(commands, [
      { kind: 'group', rows: ['region'] },
      { kind: 'column', name: 'notional', change: { aggregate: 'average', weight: null } },
      { kind: 'configure', patch: { columns: { region: { hidden: null }, notional: { hidden: null }, desk: { hidden: true } } } },
    ]);
    const done = applyGridCommands({ snapshot: typed, config: DEFAULT_CONFIGURATION }, commands);
    const read = summaryOf(done.snapshot, done.config as unknown as Record<string, unknown>);
    assert.deepEqual(read.map((c) => [c.name, c.action]), [['region', 'group'], ['notional', 'average'], ['desk', undefined]]);
    // what it leaves out its own: said as a Summarize alone, no Select beside it
    const box = cube(rows('grid-1'), (q) => ({ ...q, columns: typed.columns, rows: ['region'] }), { columns: { desk: { hidden: true } } });
    assert.deepEqual(boxSays(box), { tool: 'summarize', also: [], says: 'by region' });
    // set again: its grouping taken off first, a measure grouped by made a dimension before it is
    const again = summarizeCommands(done.snapshot, { columns: [{ name: 'desk', action: 'group' }, { name: 'notional', action: 'group' }] });
    assert.deepEqual(again.slice(0, 3), [
      { kind: 'group', rows: [] }, { kind: 'column', name: 'notional', change: { kind: 'dimension' } }, { kind: 'group', rows: ['desk', 'notional'] },
    ]);
  });

  it("offers a column its aggregates, and reads a fresh grid's measures as aggregated as they say", () => {
    assert.equal(summaryActions('Float')[0], 'group');
    assert.ok(summaryActions('Float').includes('sum') && !summaryActions('String').includes('sum'));
    assert.ok(summaryActions('Float').includes('wavg') && summaryActions('Float').includes('distinctCount'), 'aggregates of two columns too');
    const fresh = summaryOf(typed, {});
    assert.deepEqual(fresh.map((c) => [c.name, c.action]), [['region', undefined], ['notional', 'sum'], ['desk', undefined]]);
    assert.deepEqual(summaryOf(typed, { columns: { notional: { hidden: true } } })[1]?.action, undefined);
  });

  it('refuses in words what it cannot summarise', () => {
    refused(() => summarizeCommands(typed, { columns: [{ name: 'notional', action: 'sum' }] }), /group by a column/);
    refused(() => summarizeCommands(typed, { columns: [{ name: 'nope', action: 'group' }] }), /no column 'nope'/);
    refused(() => summarizeCommands(typed, { columns: [{ name: 'region', action: 'group' }, { name: 'region', action: 'count' }] }), /one thing with each column/);
    // an aggregate of two columns: its second named, its weight set; none named, refused
    assert.deepEqual(summarizeCommands(typed, { columns: [{ name: 'region', action: 'group' }, { name: 'notional', action: 'wavg', second: 'notional' }] })
      .find((c) => c.kind === 'column' && c.name === 'notional'), { kind: 'column', name: 'notional', change: { aggregate: 'wavg', weight: 'notional' } });
    refused(() => summarizeCommands(typed, { columns: [{ name: 'region', action: 'group' }, { name: 'notional', action: 'wavg' }] }), /weighted average reads its weight column/);
    refused(() => summarizeCommands(typed, { columns: [{ name: 'region', action: 'group' }, { name: 'notional', action: 'sum', second: 'x' }] }), /reads no second column/);
    assert.deepEqual(readSummary({ kind: 'summarize', group: ['desk'], aggregate: { notional: { fn: 'wavg', by: 'qty' } } }).columns[1],
      { name: 'notional', action: 'wavg', second: 'qty' });
    // an aggregate its column is not offered: refused as it is applied, in Column Properties' words
    refused(() => applyGridCommands({ snapshot: typed, config: DEFAULT_CONFIGURATION },
      summarizeCommands(typed, { columns: [{ name: 'region', action: 'group' }, { name: 'desk', action: 'sum' }] })), /'sum' is not offered on desk, a String column/);
    assert.deepEqual(readSummary({ kind: 'summarize', group: ['desk'], aggregate: { notional: 'average' } }),
      { columns: [{ name: 'desk', action: 'group' }, { name: 'notional', action: 'average' }] });
    refused(() => readSummary({ kind: 'summarize', group: 'desk' }), /a list of column names/);
    refused(() => readSummary({ kind: 'summarize', group: [], by: [] }), /takes group and aggregate, not by/);
  });
});

describe('Find Replace (find-replace.ts): a column replaced by the column of theirs its Join found, in place', () => {
  const joined = { ...rawRows(accessor('local::DB', 'trades'), [...COLUMNS, { name: 'their_region', type: 'String' }, { name: 'name', type: 'String' }]) };

  it('replaces where theirs has a value, hands it on by its own name, leaves theirs out; read back; set again', () => {
    const commands = findReplaceCommands(joined, { column: 'region', with: 'name' });
    const [calc, hide, place] = commands as unknown as [{ column: DerivedColumn }, { patch: { columns: object } }, { patch: { columns: object } }];
    assert.equal(calc.column.name, 'region_replaced');
    assert.equal(JSON.stringify(calc.column.lambda?.body[0]), JSON.stringify(fn('coalesce', property(variable('x'), 'name'), property(variable('x'), 'region'))));
    assert.deepEqual(hide.patch.columns, { name: { hidden: true } });
    assert.deepEqual(place.patch.columns, { region: { hidden: true }, region_replaced: { displayName: 'region' } });
    assert.deepEqual(findReplaceOf(calc.column), { column: 'region', with: 'name' });
    // a Join box replacing its column so: a Find Replace box, said so
    const join: CubeSource = {
      _type: 'join', name: 'trades ⋈ names', left: { grid: 'grid-2' }, right: { grid: 'grid-3' }, columns: joined.columns,
      how: { form: 'join', kind: 'left', on: { rows: [{ left: 'region', op: '==', right: 'region' }] }, prefix: 'their_', keep: null },
    };
    const box = cube(join, (q) => ({ ...q, columns: joined.columns, derived: [calc.column] }),
      { columns: { name: { hidden: true }, region: { hidden: true }, region_replaced: { displayName: 'region' } } });
    assert.deepEqual(boxSays(box), { tool: 'findReplace', also: [], says: 'region replaced by name' });
    assert.equal(boxSays(cube(join)).tool, 'join', 'a Join replacing nothing is a Join');
    // set again: the replacing before taken off, its columns shown again, then the new one
    const again = findReplaceCommands({ ...joined, derived: [calc.column] }, { column: 'region', with: 'their_region' });
    assert.deepEqual(again.slice(0, 2), [
      { kind: 'removeCalculated', name: 'region_replaced' }, { kind: 'configure', patch: { columns: { region: { hidden: null }, name: { hidden: null } } } },
    ]);
  });

  it('refuses in words what it cannot replace', () => {
    refused(() => findReplaceCommands(joined, { column: 'nope', with: 'name' }), /no column 'nope'/);
    refused(() => findReplaceCommands(joined, { column: 'region', with: 'region' }), /not by itself/);
    assert.deepEqual(readFindReplace({ kind: 'findReplace', column: 'region', with: 'name' }), { column: 'region', with: 'name' });
    refused(() => readFindReplace({ kind: 'findReplace', column: 'region' }), /names its column and the column it is replaced with/);
    refused(() => readFindReplace({ kind: 'findReplace', column: 'a', with: 'b', by: 1 }), /takes column and with, not by/);
  });
});

describe('Count Records (count-records.ts): one row, its count -- a column of one value grouped by, a 1 summed', () => {
  const typed = { ...rawRows(accessor('local::DB', 'trades'), COLUMNS) };

  it('groups by its column of one value, sums its 1s, hides the rest; read back and said; refused in words', () => {
    const commands = countRecordsCommands({ ...typed, rows: ['region'] });
    assert.deepEqual(commands.map((c) => c.kind), ['group', 'calculate', 'calculate', 'group', 'column', 'configure']);
    const done = applyGridCommands({ snapshot: typed, config: DEFAULT_CONFIGURATION }, countRecordsCommands(typed));
    assert.deepEqual(countRecordsOf(done.snapshot), { name: 'Count', all: 'CountAll' });
    const config = done.config as unknown as Record<string, unknown>;
    assert.deepEqual(handedOn(rowColumns(done.snapshot).map((c) => c.name), done.snapshot, config).map((c) => c.as), ['Count'], 'its count alone');
    const box = cube(rows('grid-1'), (q) => ({ ...q, derived: done.snapshot.derived, rows: done.snapshot.rows }), config);
    assert.deepEqual(boxSays(box), { tool: 'countRecords', also: [], says: 'its rows counted: Count' });
    refused(() => countRecordsCommands(done.snapshot), /counts its rows already/);
    refused(() => countRecordsCommands(typed, 'region'), /'region' is already a column/);
    assert.deepEqual(readCountRecords({ kind: 'countRecords' }), { name: 'Count' });
    refused(() => readCountRecords({ kind: 'countRecords', by: 1 }), /takes name, not by/);
  });
});

describe('Transpose (transpose.ts): columns made rows, a Name and a Value each, the keys beside them', () => {
  const typed = { ...rawRows(accessor('local::DB', 'trades'), [...COLUMNS, { name: 'pnl', type: 'Float' }, { name: 'desk', type: 'String' }]) };
  const made = (t: Partial<Parameters<typeof transposeCommands>[1]> = {}) =>
    transposeCommands(typed, { columns: ['notional', 'pnl'], keys: ['region'], name: 'Name', value: 'Value', ...t });

  it('explodes a list of the names, chooses each row\'s value by its name, and hides all but the keys', () => {
    const [name, value, hide] = made() as unknown as [{ column: DerivedColumn }, { column: DerivedColumn }, { patch: { columns: object } }];
    assert.equal(name.column.unnest, true);
    assert.deepEqual((name.column.lambda?.body[0] as unknown as { values: unknown[] }).values, [lit.string('notional'), lit.string('pnl')]);
    const x = variable('x');
    assert.equal(JSON.stringify(value.column.lambda?.body[0]), JSON.stringify(
      fn('if', fn('equal', property(x, 'Name'), lit.string('notional')), lambda([], property(x, 'notional')), lambda([], property(x, 'pnl')))));
    assert.equal(value.column.kind, 'measure', 'numbers summed when grouped');
    assert.deepEqual(Object.keys(hide.patch.columns), ['notional', 'pnl', 'desk']);
  });

  it('is read back off its columns, its hidden columns its own: a Transpose box', () => {
    const commands = made() as unknown as { column?: DerivedColumn; patch?: { columns: Record<string, unknown> } }[];
    const derived = commands.flatMap((c) => (c.column ? [c.column] : []));
    const box = cube(rows('grid-1'), (q) => ({ ...q, derived }), { columns: commands[2]!.patch!.columns });
    assert.deepEqual(boxSays(box), { tool: 'transpose', also: [], says: 'notional, pnl to rows' });
    assert.deepEqual(transposeOf(derived), { name: 'Name', value: 'Value', columns: ['notional', 'pnl'] });
    // one column: its value is that column
    const one = (transposeCommands(typed, { columns: ['pnl'], keys: [], name: 'Name', value: 'Value' }) as unknown as { column?: DerivedColumn }[])
      .flatMap((c) => (c.column ? [c.column] : []));
    assert.deepEqual(transposeOf(one)?.columns, ['pnl']);
  });

  it('refuses in words what it cannot transpose', () => {
    assert.throws(() => made({ columns: [] }), /pick a column to make rows of/);
    assert.throws(() => made({ columns: ['notional', 'desk'] }), /its columns hold numbers and text: one Value holds one kind/);
    assert.throws(() => made({ keys: ['notional'] }), /'notional' is a key/);
    assert.throws(() => made({ name: 'region' }), /'region' is already a column/);
    assert.throws(() => made({ value: 'Name' }), /two names/);
    assert.deepEqual(readTranspose({ kind: 'transpose', columns: ['a'] }), { columns: ['a'], keys: [], name: 'Name', value: 'Value' });
    assert.throws(() => readTranspose({ kind: 'transpose', columns: 'a' }), /its columns are a list of column names/);
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

  it('in place: the filled column handed on by its column\'s name, the column hidden, and the box says so', () => {
    const commands = imputeCommands(typed, { columns: ['qty'], with: { kind: 'value', value: 0 }, inPlace: true });
    assert.deepEqual(commands.at(-1), { kind: 'configure', patch: { columns: { qty: { hidden: true }, qty_filled: { displayName: 'qty' } } } });
    const [filled] = commands as unknown as [{ column: DerivedColumn }];
    const box = cube(rows('grid-1'), (q) => ({ ...q, columns: typed.columns, derived: [filled.column] }),
      { columns: { qty: { hidden: true }, qty_filled: { displayName: 'qty' } } });
    assert.deepEqual(boxSays(box), { tool: 'impute', also: [], says: "qty's empties → 0 in place" });
    assert.deepEqual(readImputation({ kind: 'impute', columns: ['qty'], with: 0, inPlace: true }).inPlace, true);
  });

  it('refuses in words what it cannot fill', () => {
    assert.throws(() => imputeCommands(typed, { columns: [], with: { kind: 'average' } }), /pick a column to fill/);
    assert.throws(() => imputeCommands(typed, { columns: ['region'], with: { kind: 'average' } }), /only a number's empties are filled/);
    assert.throws(() => imputeCommands(typed, { columns: ['qty'], with: { kind: 'value', value: 0.5 } }), /holds whole numbers/);
    assert.deepEqual(readImputation({ kind: 'impute', columns: ['qty'], with: 3 }), { columns: ['qty'], with: { kind: 'value', value: 3 } });
    assert.throws(() => readImputation({ kind: 'impute', columns: ['qty'], with: 'median' }), /not "median"/);
  });
});

describe('Multi-Field Formula (multi-field.ts): one expression over several columns, a column each', () => {
  const typed = { ...rawRows(accessor('local::DB', 'trades'), [...COLUMNS, { name: 'desk', type: 'String' }, { name: 'qty', type: 'Integer', kind: 'measure' as const },
    { name: 'price', type: 'Float' }]) };
  const v = variable('v');
  // $v->trim(), and $v * 2 + $x.qty: the value, and the row's other column
  const trimmed = lambda(['v'], fn('trim', fn('toOne', v)));
  const doubled = lambda(['v'], fn('plus', fn('times', fn('toOne', v), lit.integer(2)), property(variable('x'), 'qty')));
  const made = (columns: string[], l = trimmed, suffix = '_new') =>
    (multiFieldCommands(typed, { columns, lambda: l, suffix }) as unknown as { column: DerivedColumn }[]).map((c) => c.column);

  it("makes a column of each, $v its column and $x the row; its kind its column's", () => {
    const [a, b] = made(['region', 'desk']);
    assert.deepEqual([a!.name, b!.name], ['region_new', 'desk_new']);
    assert.equal(JSON.stringify(b!.lambda), JSON.stringify(lambda(['x'], fn('trim', fn('toOne', property(variable('x'), 'desk'))))));
    assert.equal(a!.kind, 'dimension');
    const [q] = made(['qty'], doubled, '_x2');
    assert.equal(q!.kind, 'measure');
    assert.equal(JSON.stringify(q!.lambda?.body[0]), JSON.stringify(
      fn('plus', fn('times', fn('toOne', property(variable('x'), 'qty')), lit.integer(2)), property(variable('x'), 'qty'))));
    assert.equal(multiFieldText('$v->trim()'), 'v|$v->trim()');
    assert.equal(multiFieldText(' w | $w '), 'w | $w');
  });

  it('is read back off its columns: two or more of one shape, each over its own; a lone one a Formula', () => {
    const columns = made(['region', 'desk'], lambda(['v'], fn('length', fn('toOne', v))));
    const box = cube(rows('grid-1'), (q) => ({ ...q, derived: columns }));
    assert.deepEqual(boxSays(box), { tool: 'multiField', also: [], says: 'over region, desk' });
    // a shape a tool of its own makes is that tool's: a trim, Data Cleansing's
    assert.equal(boxSays(cube(rows('grid-1'), (q) => ({ ...q, derived: made(['region', 'desk']) }))).tool, 'cleanse');
    assert.deepEqual(multiFieldOf(columns).get('desk_new'), { over: 'desk', all: ['region', 'desk'] });
    // one over $x.qty besides its own value: the column it is over is the one its name says
    const twice = made(['notional', 'price'], doubled);
    assert.deepEqual([...multiFieldOf(twice).values()].map((m) => m.over), ['notional', 'price']);
    assert.equal(boxSays(cube(rows('grid-1'), (q) => ({ ...q, derived: made(['desk'], doubled) }))).tool, 'formula');
    // two formulas of two shapes, side by side: Formulas
    assert.equal(multiFieldOf([...made(['desk']), ...made(['qty'], doubled)]).size, 0);
  });

  it('refuses in words what it cannot make', () => {
    assert.throws(() => made([]), /pick a column/);
    assert.throws(() => made(['nope']), /the grid has no column 'nope'/);
    assert.throws(() => made(['region'], lambda(['x'], variable('x'))), /its value is \$v/);
    assert.throws(() => made(['region'], lambda(['v'], fn('map', v, lambda(['x'], variable('x'))))), /names its parameter x/);
    assert.throws(() => made(['region'], trimmed, ' '), /suffix/);
    const taken = { ...typed, derived: made(['region']) };
    assert.throws(() => multiFieldCommands(taken, { columns: ['region'], lambda: trimmed, suffix: '_new' }), /'region_new' is already a column/);
    assert.deepEqual(readMultiField({ kind: 'multiField', columns: ['a'], expression: '$v' }), { columns: ['a'], expression: '$v', suffix: '_new' });
    assert.throws(() => readMultiField({ kind: 'multiField', columns: ['a'], expression: '' }), /its expression is over each column's value/);
  });
});

describe("the DateTime's part and text read (date-tools.ts): a column each, read back off its expression", () => {
  const said = (derived: DerivedColumn[]) => boxSays(cube(rows('grid-1'), (q) => ({ ...q, derived })));
  const col = (t: Parameters<typeof dateToolCommands>[0]) => (dateToolCommands(t)[0] as unknown as { column: DerivedColumn }).column;

  it("takes a date's part, by the compiler's own function; reads text by a format, a day's or a date-time's", () => {
    const year = col({ kind: 'datePart', column: 'trade_date', part: 'year', name: 'y' });
    assert.equal(JSON.stringify(year.lambda?.body[0]), JSON.stringify(fn('year', property(variable('x'), 'trade_date'))));
    const day = col({ kind: 'datePart', column: 'trade_date', part: 'day', name: 'd' });
    assert.match(JSON.stringify(day.lambda?.body[0]), /"function":"dayOfMonth".*"function":"toOne"/);
    const read = col({ kind: 'dateParse', column: 'booked', format: 'dd/MM/yyyy', time: false, name: 'b' });
    assert.match(JSON.stringify(read.lambda?.body[0]), /meta::legend::lite::convertDateFormat/);
    assert.deepEqual(said([year, read]), { tool: 'datetime', also: [], says: 'year of trade_date, booked read as dd/MM/yyyy' });
    assert.equal(dateToolOf(col({ kind: 'dateParse', column: 'at', format: 'yyyy-MM-dd HH:mm:ss', time: true, name: 'a' }))?.says,
      'at read as yyyy-MM-dd HH:mm:ss');
  });

  it('refuses in words what it cannot make', () => {
    assert.throws(() => dateToolCommands({ kind: 'datePart', column: 'd', part: 'year', name: ' ' }), /its column needs a name/);
    assert.throws(() => readDateTool({ kind: 'datePart', column: 'd', part: 'century', name: 'c' }), /a part is year, month, day, week, hour/);
    assert.throws(() => readDateTool({ kind: 'dateParse', column: 'd', format: '', time: false, name: 'c' }), /it reads by a format/);
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

  it("asks each pair of number columns' correlation, and reads them back a matrix: each with itself 1, empty where none", () => {
    const asked = JSON.stringify(correlationLambda(accessor('local::DB', 'trades'), ['a', 'b', 'c']));
    for (const p of ['r0_1', 'r0_2', 'r1_2']) assert.ok(asked.includes(`"${p}"`), `asks ${p}`);
    assert.ok(asked.includes('"corr"') && asked.includes('"rowMapper"'), 'by corr over rows mapped a pair each');
    const col = (name: string, v: unknown) => ({ name, type: 'Float', values: [v] });
    const table = { rowCount: 1, epoch: 0, elapsedMs: 0, columns: [col('r0_1', 0.5), col('r0_2', -1), col('r1_2', null)] } as never;
    assert.deepEqual(correlationsOf(table, ['a', 'b', 'c']), { columns: ['a', 'b', 'c'], r: [[1, 0.5, -1], [0.5, 1, null], [-1, null, 1]] });
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
      /a live input's port is a Filter's 'false' or a Join's 'left' or 'right', not "true"/);
    assert.deepEqual(renamedCommand({ kind: 'setInput', grid: 'grid-4', side: 'input', input: { grid: 'grid-3', port: 'false' } }, new Map([['grid-3', 'grid-9']])),
      { kind: 'setInput', grid: 'grid-4', side: 'input', input: { grid: 'grid-9', port: 'false' } });
    assert.deepEqual(boxInputs(d.page.cubes.find((c) => c.id === 'grid-4')!.cube), [{ side: 'input', grid: 'grid-3', port: 'false' }]);
  });

  it("reads a Join's L and R (§3.3): each side's own rows, those the other's pair with none; refused where none can be", () => {
    const join: CubeSource = {
      _type: 'join', name: 'trades ⋈ trades', left: { grid: 'grid-3' }, right: { grid: 'grid-4' }, columns: COLUMNS,
      how: { form: 'join', kind: 'inner', on: { rows: [{ left: 'notional', op: '<', right: 'notional' }] }, prefix: 'their_', keep: null },
    };
    const d = apply(FLOW(), addGrid(cube(join), 'sheet-2', 'grid-3'),
      addGrid(cube({ ...rows('grid-5'), input: { grid: 'grid-5', port: 'right' } }), 'sheet-2', 'grid-5'));
    const live = sourceOf(d.page, 'grid-6');
    assert.deepEqual(liveGrids(live), ['grid-5']);
    // resolved: the Sort's rows (its Right), each the Filter's rows (its Left) pair with none -- the comparison turned round
    const read = resolvedSource(d.page, live, 'grid-6');
    const input = read._type === 'rows' && !isLive(read.input) ? read.input : undefined;
    assert.deepEqual(input?.query.sorts, [{ column: 'notional', direction: 'desc' }], "its Right's own query");
    assert.deepEqual(input?.unmatched?.map((u) => [u.by.query.filter?.kind, u.on]), [['condition', { rows: [{ left: 'notional', op: '>', right: 'notional' }] }]]);
    assert.deepEqual(liveGrids(read), [], 'a copy with no link left in it');
    // frozen when the Join goes: a copy of its R, written and read back
    const gone = apply(d, { kind: 'removeTile', tile: 'grid-5' }).page;
    const frozen = sourceOf(gone, 'grid-6');
    assert.equal(frozen._type === 'rows' && !isLive(frozen.input) ? frozen.input.unmatched?.length : undefined, 1);
    assert.equal(pageToJson(readPage(JSON.parse(pageToJson(gone)))), pageToJson(gone), 'written and read back the same');
    // a join set so that its R pairs nothing is refused while a box reads it, in its words
    refused(() => apply(d, { kind: 'setJoin', grid: 'grid-5', how: { form: 'append' } }), /grid-6: a Union keeps every row of both/);
    refused(() => apply(FLOW(), { kind: 'setInput', grid: 'grid-4', side: 'input', input: { grid: 'grid-3', port: 'left' } }),
      /'grid-3' is no join: it has no Left and Right unmatched/);
    assert.deepEqual(readPageCommand({ kind: 'setInput', grid: 'grid-6', side: 'input', input: { grid: 'grid-5', port: 'left' } }),
      { kind: 'setInput', grid: 'grid-6', side: 'input', input: { grid: 'grid-5', port: 'left' } });
  });

  it("says what pairs a side's row with none (join-how.ts unmatchedOn): the join's own, turned round for its Right", () => {
    const rowsOn = { rows: [{ left: 'a', op: '<=' as const, right: 'b' }, { left: 'n', op: '~' as const, right: 'm', within: 1 }] };
    const how = { form: 'join', kind: 'left', on: rowsOn, prefix: 'their_', keep: null } as const;
    assert.deepEqual(unmatchedOn(how, 'left'), rowsOn);
    assert.deepEqual(unmatchedOn(how, 'right'), { rows: [{ left: 'b', op: '>=', right: 'a' }, { left: 'm', op: '~', right: 'n', within: 1 }] });
    // a semi-join's Pure condition names both sides by their own names: its two rows named the other way
    const pure = lambda(['x', 'y'], fn('equal', property(variable('x'), 'a'), property(variable('y'), 'b')));
    const turned = unmatchedOn({ form: 'semi', on: { lambda: pure } }, 'right');
    assert.deepEqual('lambda' in turned ? turned.lambda.parameters.map((p) => p.name) : undefined, ['y', 'x']);
    refused(() => unmatchedOn({ form: 'append' }, 'left'), /a Union keeps every row of both/);
    refused(() => unmatchedOn({ ...how, on: { lambda: pure } }, 'left'), /its condition is Pure/);
    refused(() => unmatchedOn({ form: 'asOf', match: { left: 'a', op: '>=', right: 'b' }, on: { rows: [] }, prefix: 'p_', keep: null }, 'left'),
      /an as-of join's rows of mine with no nearest row/);
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
    // every row with every row -- matching only, on no condition -- is an Append Fields box (join-how.ts isEveryPair)
    const pairs = apply(d, { kind: 'setJoin', grid: 'grid-5', how: { form: 'join', kind: 'inner', on: { rows: [] }, prefix: 'their_', keep: null } }).page;
    assert.deepEqual(boxSays(pairs.cubes.find((c) => c.id === 'grid-5')!.cube), { tool: 'appendFields', also: [], says: 'each row with every row of theirs' });
    // a join on similar text is a Fuzzy Match box (join-how.ts isFuzzy)
    const fuzzy = apply(d, { kind: 'setJoin', grid: 'grid-5', how: { form: 'join', kind: 'inner', on: { rows: [{ left: 'region', op: '~', right: 'region', within: 1 }] }, prefix: 'their_', keep: null } }).page;
    assert.deepEqual(boxSays(fuzzy.cubes.find((c) => c.id === 'grid-5')!.cube), { tool: 'fuzzyMatch', also: [], says: 'matching rows only, on region ≈ region (within 1)' });
    const allOfMine = apply(d, { kind: 'setJoin', grid: 'grid-5', how: { form: 'join', kind: 'left', on: { rows: [] }, prefix: 'their_', keep: null } }).page;
    assert.equal(boxSays(allOfMine.cubes.find((c) => c.id === 'grid-5')!.cube).tool, 'join', 'all of mine on no condition stays a Join');
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
