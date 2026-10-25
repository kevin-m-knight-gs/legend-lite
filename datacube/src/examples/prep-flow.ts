// THE FLOW EXAMPLE (docs/DATACUBE_FLOW_MODE_2026_10_24.md: a flow sheet to open, rather than one to build first): the
// sales sample's orders and its countries, prepared on a canvas as Alteryx prepares them -- two Inputs in a container,
// the big orders filtered and joined live to their countries and a margin calculated in another, the result summarized
// by continent and written out as a CSV by an Output, the top order of each country kept by a Unique, and a comment
// saying where to start. Built by DataCube's own writers (cube-document.ts writeCube, join.ts theirNames, unique.ts's
// shape), as the other example pages are; it opens as a saved page does, each sample rebuilt and each box a document
// until it is shown. test/examples.test.ts reads it back.

import { element, fn, lambda, property, variable } from '../../../pure-protocol/src/index.ts';
import { DEFAULT_CONFIGURATION, type CubeConfiguration } from '../config.ts';
import { writeCube, type CubeDocument, type CubeSource, type JoinSource, type RowsSource } from '../cube-document.ts';
import { openedState } from '../cube-state.ts';
import { theirNames, type JoinHow } from '../join.ts';
import type { FlowBox } from '../layout/flow.ts';
import { writePageOf, type PageDocument, type PageView } from '../page-document.ts';
import { COUNTRIES_SAMPLE } from '../samples.ts';
import type { ColumnSpec, CubeSnapshot, DerivedColumn } from '../snapshot.ts';
import { TreeState } from '../tree.ts';
import { UNIQUE_COLUMN } from '../unique.ts';
import { EXAMPLE_ROWS, EXAMPLE_SAMPLE, SALES_SPECS } from './charts-dashboard.ts';
import { COUNTRY_SPECS, sampleSource } from './prep-joins.ts';

/** A box's rows as they come: no grouping, the columns given, its query's own steps over it. */
const flat = (name: string, columns: readonly ColumnSpec[], over: Partial<CubeSnapshot> = {}): CubeSnapshot => ({
  source: { query: element(name) }, columns, derived: [], rows: [], pivotOn: [], sorts: [], epoch: 1, ...over,
});

/** A box written as the screen holds one it opens (cube-state.ts openedState). */
function box(name: string, source: CubeSource, snapshot: CubeSnapshot, configuration: CubeConfiguration = DEFAULT_CONFIGURATION): CubeDocument {
  const state = openedState(snapshot, configuration, TreeState.fromPaths([], false));
  return writeCube({ name, source, snapshot: state.snapshot, configuration: state.configuration, tree: state.tree });
}

/** A box over another box's rows, read live: its input the grid of that id on the page. */
const rowsOf = (grid: string, name: string, columns: readonly ColumnSpec[]): RowsSource => ({
  _type: 'rows', name, input: { grid }, columns: columns.map((c) => ({ name: c.name, type: c.type })),
});

const x = variable('x');
/** Each order's margin: its profit over its revenue, row by row (a Formula box). */
const MARGIN: DerivedColumn = {
  name: 'margin', lambda: lambda(['x'], fn('divide', fn('toOne', property(x, 'profit')), fn('toOne', property(x, 'revenue')))),
  type: 'Float', kind: 'dimension',
};
/** Each order joined to its country by its ISO code, every order kept (a Join box, read live). */
const BY_CODE: JoinHow = { form: 'join', kind: 'left', on: { rows: [{ left: 'country_code', op: '==', right: 'code' }] }, prefix: 'their_', keep: null };

const MONEY = { format: { kind: 'currency', currency: 'USD', decimals: 0 } } as const;
const MONEYED: CubeConfiguration = { ...DEFAULT_CONFIGURATION, columns: { revenue: MONEY, cost: MONEY, profit: MONEY, target: MONEY } };

/** The page: one flow sheet, its boxes and its notes where a person would lay them out. */
export async function prepFlowPage(): Promise<PageDocument> {
  const salesFile = await sampleSource(EXAMPLE_SAMPLE, SALES_SPECS, EXAMPLE_ROWS);
  const countriesFile = await sampleSource(COUNTRIES_SAMPLE, COUNTRY_SPECS);
  const named = theirNames(SALES_SPECS.map((c) => c.name), COUNTRY_SPECS.map((c) => c.name), 'their_');
  const joined: readonly ColumnSpec[] = [...SALES_SPECS, ...COUNTRY_SPECS.map((c) => ({ ...c, name: named.get(c.name)! }))];
  const withMargin: readonly ColumnSpec[] = [...joined, { name: 'margin', type: 'Float', kind: 'dimension' }];
  const SUMMED = ['continent', 'units', 'revenue', 'cost', 'profit'];
  const summed = withMargin.filter((c) => SUMMED.includes(c.name));
  // a country's population is its country's, never summed over its orders
  const perOrder = joined.map((c) => (c.name === 'population_m' ? { ...c, kind: 'dimension' as const } : c));
  const join: JoinSource = {
    _type: 'join', name: 'Orders with their country', left: { grid: 'grid-3' }, right: { grid: 'grid-2' }, how: BY_CODE,
    columns: joined.map((c) => ({ name: c.name, type: c.type })),
  };
  const cubes: { id: string; cube: CubeDocument }[] = [
    { id: 'grid-1', cube: box('Orders', salesFile, flat(EXAMPLE_SAMPLE, SALES_SPECS), MONEYED) },
    { id: 'grid-2', cube: box('Countries', countriesFile, flat(COUNTRIES_SAMPLE, COUNTRY_SPECS)) },
    // the orders over $1,000
    { id: 'grid-3', cube: box('Big orders', rowsOf('grid-1', 'Orders', SALES_SPECS), flat('orders', SALES_SPECS, {
      filter: { kind: 'condition', column: 'revenue', operator: 'greaterThan', value: 1000 },
    }), MONEYED) },
    { id: 'grid-4', cube: box(join.name, join, flat('joined', perOrder), MONEYED) },
    { id: 'grid-5', cube: box('Margin', rowsOf('grid-4', join.name, perOrder), flat('margin', perOrder, { derived: [MARGIN] }), MONEYED) },
    // a row a continent, its money summed -- the other columns left out, so the Output after it writes what it shows
    { id: 'grid-6', cube: box('By continent', rowsOf('grid-5', 'Margin', withMargin), flat('by continent', withMargin, {
      rows: ['continent'], sorts: [{ column: 'revenue', direction: 'desc' }],
    }), { ...MONEYED, columns: { ...MONEYED.columns, ...Object.fromEntries(withMargin
      .filter((c) => !SUMMED.includes(c.name)).map((c) => [c.name, { hidden: true }])) } }) },
    // what the summary shows, its Output's input: the continent and its sums
    { id: 'grid-7', cube: box('Revenue by continent', rowsOf('grid-6', 'By continent', summed), flat('written', summed), MONEYED) },
    // the top order of each country: a row number per country by revenue, its first kept, the number hidden (Unique)
    { id: 'grid-8', cube: box('Top order of each country', rowsOf('grid-1', 'Orders', SALES_SPECS), flat('top', SALES_SPECS, {
      derived: [{ name: UNIQUE_COLUMN, window: { fn: 'rowNumber', partition: ['country'], order: [{ column: 'revenue', direction: 'desc' }] } }],
      filter: { kind: 'condition', column: UNIQUE_COLUMN, operator: 'equal', value: 1 },
    }), { ...MONEYED, columns: { ...MONEYED.columns, [UNIQUE_COLUMN]: { hidden: true } } }) },
  ];
  const grids: PageView[] = cubes.map((c) => ({ id: c.id, kind: 'grid', cube: c.id, title: c.cube.name }));
  const notes: PageView[] = [
    { id: 'text-1', kind: 'text', text: 'Inputs' },
    { id: 'text-2', kind: 'text', text: 'Prepare' },
    { id: 'text-4', kind: 'text', text: 'Report' },
    { id: 'text-3', kind: 'text', text: '**Start here.** Click a box to see its rows below, and its settings beside. Drop a tool from the left onto a box to add a step after it; drag from a box\'s right edge to another box to link them; ▶ Run counts every box and writes the Output.' },
  ];
  // three columns wide, so the whole of it is in sight beside the settings: its inputs, its steps, its report
  const boxes: FlowBox[] = [
    { tile: 'text-1', x: 20, y: 30, frame: { w: 224, h: 290 } },
    { tile: 'text-2', x: 268, y: 30, frame: { w: 472, h: 290 } },
    { tile: 'text-4', x: 268, y: 340, frame: { w: 472, h: 140 } },
    { tile: 'grid-1', x: 40, y: 80 },
    { tile: 'grid-2', x: 40, y: 230 },
    { tile: 'grid-3', x: 288, y: 80 },
    { tile: 'grid-4', x: 536, y: 80 },
    { tile: 'grid-5', x: 536, y: 230 },
    { tile: 'grid-6', x: 288, y: 390 },
    { tile: 'grid-7', x: 536, y: 390, output: { format: 'csv', name: 'revenue by continent.csv' } },
    { tile: 'grid-8', x: 40, y: 390 },
    { tile: 'text-3', x: 20, y: 520 },
  ];
  return writePageOf({
    name: 'A data flow',
    cubes,
    views: { views: [...grids, ...notes], sheets: [{ id: 'sheet-1', name: 'Sales prep', layout: { kind: 'flow', boxes } }] },
  });
}
