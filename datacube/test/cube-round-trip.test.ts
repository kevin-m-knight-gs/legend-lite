// ROUND TRIPS, AS PROPERTIES (docs/DATACUBE_COMMAND_API_HOMEWORK_2026_10_17.md §11, part 2): a grid edited at random
// through the functions the screen and Python both edit with (grid-edits.ts, config.ts's patches), then written, read
// and opened again, must come back as it was -- and the state a grid keeps twice must agree with itself. Every bug the
// audits of neema2/legend-lite#47 found lived between pieces like these: the configuration against its copy folded into
// the query, a saved document against what reopens.
//
// Seeded (mulberry32), so a failure names its seed and reproduces from it alone. A refused edit (CubeRefusal) is the
// screen's own refusal and is skipped; it must leave the draft as it was.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { element } from '../../pure-protocol/src/index.ts';
import { chartProblems, defaultChart, followCube } from '../src/chart-spec.ts';
import { DEFAULT_CONFIGURATION, withConfiguration } from '../src/config.ts';
import { cubeToJson, frameSource, openCube, readCube, writeCube } from '../src/cube-document.ts';
import { fold, openedState, queryKey, stateKey, type CubeState } from '../src/cube-state.ts';
import {
  addSort, allColumns, clickSort, clickTreeSort, firstSortDirection, groupableColumns, headerSorts, offeredAggregates,
  setColumn, setGrouping, setPivot, type GridDraft,
} from '../src/grid-edits.ts';
import {
  CubeRefusal, pivotTotalColumn, rowColumns, totalOrderSorts, type CubeSnapshot, type FilterNode, type SortDirection,
} from '../src/snapshot.ts';
import { TreeState } from '../src/tree.ts';
import { stand } from './fake-planner.ts';

const SEEDS = 300;
const STEPS = 12;
const TREE = '__tree';

/** A grid of every kind of column a person meets: text, numbers of two types, a date, calculated ones at each level. */
const CUBE: CubeSnapshot = {
  source: { query: element('t') },
  columns: [
    { name: 'region', type: 'String' },
    { name: 'desk', type: 'String' },
    { name: 'year', type: 'Integer' },
    { name: 'traded', type: 'StrictDate' },
    { name: 'notional', type: 'Float' },
    { name: 'qty', type: 'Integer' },
  ],
  derived: [
    { name: 'big', lambda: stand('$x.notional > 1'), kind: 'dimension', type: 'Boolean' },
    { name: 'fee', lambda: stand('$x.notional * 2'), kind: 'measure', type: 'Float' },
  ],
  groupDerived: [{ name: 'share', lambda: stand('$x.notional / 2'), type: 'Float' }],
  rows: [],
  pivotOn: [],
  sorts: [],
  epoch: 1,
};

/** A seeded generator: the same seed, the same grid. */
function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** One edit a person (or Python) could make, picked at random, and its name for a failure's message. */
function randomEdit(rand: () => number, d: GridDraft): [string, (d: GridDraft) => GridDraft] {
  const pick = <T>(xs: readonly T[]): T => xs[Math.floor(rand() * xs.length)] as T;
  const columns = allColumns(d);
  const type = (name: string): string | undefined => rowColumns(d.snapshot).find((c) => c.name === name)?.type;
  const sortable = [...columns, ...(d.snapshot.pivotOn.length > 0 ? [pivotTotalColumn('notional')] : [])];
  const choices: [string, () => [string, (d: GridDraft) => GridDraft]][] = [
    ['group', () => {
      const offered = groupableColumns(d);
      const rows = offered.filter(() => rand() < 0.35).slice(0, 2);
      return [`group ${rows.join(',')}`, (x) => setGrouping(x, rows)];
    }],
    ['pivot', () => {
      const offered = groupableColumns(d).filter((c) => !d.snapshot.rows.includes(c));
      const on = rand() < 0.4 ? [] : offered.filter(() => rand() < 0.3).slice(0, 1);
      return [`pivot ${on.join(',')}`, (x) => setPivot(x, on)];
    }],
    ['click a header', () => {
      const column = pick(sortable);
      const chained = rand() < 0.5;
      return [`click ${column}${chained ? ' chained' : ''}`, (x) => clickSort(x, column, firstSortDirection(type(column)), chained)];
    }],
    ['click the tree', () => {
      const chained = rand() < 0.5;
      return [`click the tree${chained ? ' chained' : ''}`, (x) => clickTreeSort(x, chained)];
    }],
    ['add a sort', () => {
      const column = pick(sortable);
      const direction: SortDirection = rand() < 0.5 ? 'asc' : 'desc';
      return [`add sort ${column} ${direction}`, (x) => addSort(x, column, direction)];
    }],
    ['an Aggregation', () => {
      const column = pick(columns);
      const fn = pick([...offeredAggregates(type(column)), null]);
      const weight = fn === 'wavg' ? { weight: 'qty' } : {};
      return [`aggregate ${column} ${String(fn)}`, (x) => setColumn(x, column, { aggregate: fn, ...weight })];
    }],
    ['a kind', () => {
      const column = pick(CUBE.columns.map((c) => c.name));
      const kind = rand() < 0.5 ? 'dimension' : 'measure';
      return [`kind ${column} ${kind}`, (x) => setColumn(x, column, { kind })];
    }],
    ['a filter', () => {
      const filters: FilterNode[] = [
        { kind: 'condition', column: 'region', operator: pick(['equal', 'notEqual'] as const), value: pick(['EMEA', 'APAC']) },
        { kind: 'condition', column: 'traded', operator: 'greaterThan', value: '2024-02-01' },
        { kind: 'condition', column: 'qty', operator: 'lessThanEqual', value: 5 },
      ];
      const filter = rand() < 0.2 ? undefined : rand() < 0.5 ? pick(filters)
        : { kind: 'and' as const, children: [pick(filters), pick(filters)] };
      return [`filter ${JSON.stringify(filter)}`, (x) => {
        const { filter: _was, ...rest } = x.snapshot;
        return { ...x, snapshot: filter === undefined ? rest : { ...rest, filter } };
      }];
    }],
    ['a calculated column', () => {
      // added, as the column editor adds one (a row-level measure), or the last one taken away
      const add = rand() < 0.6;
      const name = `calc${Math.floor(rand() * 3)}`;
      return [`${add ? 'add' : 'remove'} a calculated column`, (x) => {
        const derived = x.snapshot.derived.filter((d) => d.name !== name);
        return { ...x, snapshot: { ...x.snapshot, derived: add
          ? [...derived, { name, lambda: stand('$x.qty * 3'), kind: 'measure', type: 'Integer' }]
          : x.snapshot.derived.slice(0, -1) } };
      }];
    }],
    ['a setting', () => {
      const column = pick(columns);
      const patches: [string, Parameters<typeof withConfiguration>[1]][] = [
        ['heatmap', { columns: { [column]: { heatmap: { from: '#ffffff', to: '#ff8a65' } } } }],
        ['hidden', { columns: { [column]: { hidden: rand() < 0.7 } } }],
        ['width', { columns: { [column]: { width: 40 + Math.floor(rand() * 200) } } }],
        ['totals', { pivotStatisticColumnPlacement: pick(['left', 'right'] as const) }],
        ['no totals', { pivotStatisticColumnPlacement: undefined }],
        ['row limit', { maxRows: pick([10, 100, 5000]) }],
        ['tree order', { treeColumnSort: pick(['asc', 'desc'] as const) }],
        ['leaf count', { showLeafCount: rand() < 0.5 }],
        ['root row', { showRootAggregation: rand() < 0.5 }],
        ['total heatmap', { columns: { [pivotTotalColumn('notional')]: { heatmap: { from: '#ffffff', to: '#00ff00' } } } }],
        ['pivot exclusion', { columns: { [column]: { excludedFromPivot: rand() < 0.5 } } }],
        ['pivot sort', { columns: { [column]: { pivotSortDirection: pick(['asc', 'desc'] as const) } } }],
        ['leaf count mode', { leafCountMode: pick(['children', 'leaves'] as const) }],
        ['grouped columns kept', { showGroupedColumns: rand() < 0.5 }],
      ];
      const [name, patch] = pick(patches);
      return [`setting ${name}`, (x) => ({ ...x, config: withConfiguration(x.config, patch) })];
    }],
  ];
  return pick(choices)[1]();
}

/** A grid after `STEPS` random edits from `seed`, the edits made (the refused ones marked), and its open groups. */
function editedGrid(seed: number): { draft: GridDraft; steps: string[]; tree: TreeState } {
  const rand = mulberry32(seed);
  let d: GridDraft = { snapshot: CUBE, config: DEFAULT_CONFIGURATION };
  const steps: string[] = [];
  for (let i = 0; i < STEPS; i += 1) {
    const [name, edit] = randomEdit(rand, d);
    const before = JSON.stringify(d);
    try {
      d = edit(d);
      steps.push(name);
    } catch (e) {
      if (!(e instanceof CubeRefusal)) throw new Error(`seed ${seed}, step ${i} (${name}): ${String(e)}`);
      // the screen's own refusal: said, and the grid left as it was
      assert.equal(JSON.stringify(d), before, `seed ${seed}: a refused edit (${name}) changed the grid`);
      steps.push(`${name} (refused)`);
    }
  }
  // some groups open, as a person leaves a grid: a value of each grouping level, or none
  const open = d.snapshot.rows.length > 0 && rand() < 0.5 ? [['EMEA'], ...(d.snapshot.rows.length > 1 ? [['EMEA', 'Rates']] : [])] : [];
  return { draft: d, steps, tree: TreeState.fromPaths(open, rand() < 0.5) };
}

/** The grid as the screen's state owner holds it: its configuration folded into its query (cube-state.ts fold). */
function stateOf(d: GridDraft, tree = TreeState.empty()): CubeState {
  return fold({ snapshot: d.snapshot, configuration: d.config, tree });
}

/** A state written as the screen and Python write it, its text exact (cube-document.ts). */
function written(s: CubeState): string {
  return cubeToJson(writeCube({ name: 'round trip', source: frameSource('t', CUBE.columns), snapshot: s.snapshot,
    configuration: s.configuration, tree: s.tree }));
}

/** A document opened again over the same source, as the screen opens it: openCube, then the configuration read back
 *  from what the query carries (app.ts's constructor, headless/grid.ts opened). */
function reopened(text: string): { state: CubeState; notes: readonly string[]; changed: boolean } {
  const cube = openCube(readCube(text), { query: element('t') }, CUBE.columns);
  return { state: openedState(cube.snapshot, cube.configuration, cube.tree), notes: cube.notes, changed: cube.changed };
}

const why = (seed: number, steps: readonly string[]): string => `seed ${seed}: ${steps.join(' -> ')}`;

describe('a grid edited at random comes back as it was (written, read, opened again)', () => {
  it(`${SEEDS} seeds of ${STEPS} edits: the same queries, the same settings, the same document`, () => {
    for (let seed = 1; seed <= SEEDS; seed += 1) {
      const { draft, steps, tree } = editedGrid(seed);
      const state = stateOf(draft, tree);
      const text = written(state);
      const again = reopened(text);
      // a document it just wrote opens with nothing to say and nothing left out
      assert.deepEqual(again.notes, [], why(seed, steps));
      assert.equal(again.changed, false, why(seed, steps));
      // it runs the same queries (as content: a key's place in the text is the reader's)
      assert.deepEqual(JSON.parse(queryKey(again.state)), JSON.parse(queryKey(state)), why(seed, steps));
      // nothing a person set is lost: presentation too (as content: a key's place in the text is the reader's)
      assert.deepEqual(JSON.parse(stateKey(again.state)), JSON.parse(stateKey(state)), why(seed, steps));
      // and written again it is the same document, character for character
      assert.equal(written(again.state), text, why(seed, steps));
    }
  });
});

describe('the state a grid keeps twice agrees with itself', () => {
  it('what a document carries only in its query survives the open path (#47\'s first blocker: openedState reads it back)', () => {
    // a document whose configuration says nothing -- an older writer's, or one that left it behind -- and whose query
    // carries the grid's settings: opened as the screen opens it, each setting the query carries is kept, never folded
    // away with a configuration that does not name it (what fromSnapshot reads back: each column's kind, pivot
    // exclusion, Aggregation and weight, the row limit, the tree's order, a pivot total and the pivot sorts)
    const carried = (s: CubeSnapshot): unknown => ({
      columns: [...s.columns, ...s.derived].map((c) => ({ name: c.name, kind: c.kind, aggregate: c.aggregate,
        aggregateWeight: c.aggregateWeight, excludedFromPivot: 'excludedFromPivot' in c ? c.excludedFromPivot : undefined })),
      maxRows: s.maxRows, treeColumnSort: s.treeColumnSort, pivotSort: s.pivotSort ?? {},
      ...(s.pivotTotal ? { pivotTotal: s.pivotTotal } : {}),
    });
    for (let seed = 1; seed <= SEEDS; seed += 1) {
      const { draft, steps } = editedGrid(seed);
      const state = stateOf(draft);
      const text = written({ ...state, configuration: DEFAULT_CONFIGURATION });
      const again = reopened(text);
      const want = carried(state.snapshot) as { pivotTotal?: unknown };
      const got = carried(again.state.snapshot) as { pivotTotal?: unknown };
      // a pivot total the query carries is read back; one it does not carry is the configuration's to decide
      if (want.pivotTotal === undefined) delete got.pivotTotal;
      assert.deepEqual(got, want, why(seed, steps));
    }
  });

  it('opened again over a source with a column more, it says so, hides it, and keeps everything else', () => {
    const wider = [...CUBE.columns, { name: 'book', type: 'String' }];
    for (let seed = 1; seed <= SEEDS; seed += 1) {
      const { draft, steps, tree } = editedGrid(seed);
      const state = stateOf(draft, tree);
      const cube = openCube(readCube(written(state)), { query: element('t') }, wider);
      assert.deepEqual(cube.notes, ['1 new column since this cube was saved, hidden: book'], why(seed, steps));
      const again = openedState(cube.snapshot, cube.configuration, cube.tree);
      assert.equal(again.configuration.columns['book']?.hidden, true, why(seed, steps));
      const { book: _book, ...columns } = again.configuration.columns;
      assert.deepEqual(JSON.parse(JSON.stringify({ ...again.configuration, columns })), JSON.parse(JSON.stringify(state.configuration)),
        why(seed, steps));
    }
  });

  it('the headers mark only sorts the query applies, each once (headerSorts within totalOrderSorts)', () => {
    for (let seed = 1; seed <= SEEDS; seed += 1) {
      const { draft, steps } = editedGrid(seed);
      const s = stateOf(draft).snapshot;
      const leaves = [TREE, ...allColumns(draft), pivotTotalColumn('notional')];
      const marks = headerSorts(s, leaves, TREE);
      const applied = totalOrderSorts(s);
      for (const m of marks) {
        const column = m.column === TREE ? s.rows[0] : m.column;
        assert.ok(applied.some((a) => a.column === column && a.direction === m.direction),
          `${why(seed, steps)}: ${m.column} ${m.direction} marked, the query orders ${JSON.stringify(applied)}`);
      }
      assert.equal(new Set(marks.map((m) => m.column)).size, marks.length, why(seed, steps));
    }
  });

  it('a live chart, followed to the grid as it is now, is where following leaves it (followCube a fixed point)', () => {
    let charted = 0;
    for (let seed = 1; seed <= SEEDS; seed += 1) {
      const rand = mulberry32(seed * 7919);
      // a chart made of the grid as it was a few edits ago
      const start = editedGrid(seed);
      const chart = defaultChart(stateOf(start.draft).snapshot);
      if (chart === null) continue;
      charted += 1;
      let d = start.draft;
      for (let i = 0; i < 4; i += 1) {
        const [, edit] = randomEdit(rand, d);
        try {
          d = edit(d);
        } catch (e) {
          if (!(e instanceof CubeRefusal)) throw e;
        }
      }
      const s = stateOf(d).snapshot;
      const followed = followCube(chart, s);
      assert.deepEqual(followCube(followed, s), followed, `seed ${seed}: following again moved the chart`);
      // and judging it never throws: a chart the grid cannot draw is said in words
      assert.ok(Array.isArray(chartProblems(followed, s)));
    }
    assert.ok(charted > SEEDS / 4, `enough grids made a chart (${charted})`);
  });
});
