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
import { DEFAULT_CONFIGURATION, applyToSnapshot, fromSnapshot, withConfiguration, type CubeConfiguration } from '../src/config.ts';
import { cubeToJson, frameSource, openCube, readCube, writeCube } from '../src/cube-document.ts';
import { fold, queryKey, stateKey, type CubeState } from '../src/cube-state.ts';
import {
  addSort, allColumns, clickSort, clickTreeSort, firstSortDirection, groupableColumns, headerSorts, offeredAggregates,
  setColumn, setGrouping, setPivot, type GridDraft,
} from '../src/grid-edits.ts';
import {
  CubeRefusal, pivotTotalColumn, rowColumns, totalOrderSorts, type CubeSnapshot, type SortDirection,
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
      ];
      const [name, patch] = pick(patches);
      return [`setting ${name}`, (x) => ({ ...x, config: withConfiguration(x.config, patch) })];
    }],
  ];
  return pick(choices)[1]();
}

/** A grid after `STEPS` random edits from `seed`, and the edits made (the refused ones marked). */
function editedGrid(seed: number): { draft: GridDraft; steps: string[] } {
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
  return { draft: d, steps };
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
  const configuration: CubeConfiguration = fromSnapshot(cube.snapshot, cube.configuration);
  return { state: fold({ snapshot: cube.snapshot, configuration, tree: cube.tree }), notes: cube.notes, changed: cube.changed };
}

const why = (seed: number, steps: readonly string[]): string => `seed ${seed}: ${steps.join(' -> ')}`;

describe('a grid edited at random comes back as it was (written, read, opened again)', () => {
  it(`${SEEDS} seeds of ${STEPS} edits: the same queries, the same settings, the same document`, () => {
    for (let seed = 1; seed <= SEEDS; seed += 1) {
      const { draft, steps } = editedGrid(seed);
      const state = stateOf(draft);
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
  it('the configuration read back from the folded query changes nothing a query reads (fromSnapshot after applyToSnapshot)', () => {
    for (let seed = 1; seed <= SEEDS; seed += 1) {
      const { draft, steps } = editedGrid(seed);
      const folded = applyToSnapshot(draft.snapshot, draft.config);
      const readBack = fromSnapshot(folded, draft.config);
      assert.deepEqual(JSON.parse(queryKey(fold({ snapshot: folded, configuration: readBack, tree: TreeState.empty() }))),
        JSON.parse(queryKey(stateOf(draft))), why(seed, steps));
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
