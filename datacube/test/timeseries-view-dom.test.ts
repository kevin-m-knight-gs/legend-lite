// THE TIMESERIES VIEWER ON A SCREEN (ui/timeseries-view.ts; docs/DATACUBE_TIMESERIES_DESIGN_2026_10_25.md §6): the
// viewer in a DOM over a grid of the markets sample read into a real DuckDB, its every query planned by legend-lite's
// planner -- only the drawing is stood in for (the renderer, which ECharts' canvas needs). What it shows is held to the
// rows themselves: the legend's latest value, the statistics' first and last, the line drawn; what the person does is
// held to the spec it tells (the page's setTimeseries), and to what it asks the database again (D4: a hidden line asks
// nothing; a range asks only what reads the first date shown, and the statistics).

import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { JSDOM } from 'jsdom';
import { openDatabase } from '#test-host';

import { DuckDbEngine } from '../../engine-client/src/duckdb.ts';
import type { ResultTable } from '../../engine-client/src/result.ts';
import type { EChartsOption } from 'echarts';
import { marketsCsv } from '../src/samples.ts';
import type { CubeSnapshot } from '../src/snapshot.ts';
import type { MountedTimeseries } from '../src/timeseries-render.ts';
import { parseExpr } from '../src/timeseries-expr.ts';
import type { TimeseriesSpec } from '../src/timeseries-spec.ts';
import { ingestFile } from '../src/upload.ts';
import { TimeseriesView, type TsGrid } from '../src/ui/timeseries-view.ts';
import { MARKET_SPECS } from '../src/examples/markets.ts';
import { plannerFor } from './catalog-builder.ts';

const CSV = marketsCsv(2000);
const ROWS = CSV.trim().split('\n').slice(1).map((l) => l.split(','));
/** The rows of one instrument, oldest first: its days and its closes. */
const of = (symbol: string): { days: string[]; closes: number[] } => {
  const mine = ROWS.filter((r) => r[0] === symbol);
  return { days: mine.map((r) => r[2]!), closes: mine.map((r) => Number(r[6])) };
};

let engine: DuckDbEngine;
let grid: TsGrid;
let runs = 0;

before(async () => {
  const db = await openDatabase();
  engine = new DuckDbEngine(db.connection);
  const writer = plannerFor('', 'local::RT');
  const r = await ingestFile(engine, db.files, new File([CSV], 'sample-markets.csv'), writer);
  const planner = plannerFor(r.model, r.runtime);
  const snapshot: CubeSnapshot = {
    source: { query: r.source }, columns: [...MARKET_SPECS], derived: [], rows: [], pivotOn: [], sorts: [], epoch: 1,
  };
  grid = {
    cube: 'prices', title: 'Prices', snapshot, filter: undefined,
    run: async (query): Promise<ResultTable> => {
      runs += 1;
      return engine.execute(await planner.plan(query), 0);
    },
    format: (value) => (typeof value === 'number' ? value.toFixed(2) : String(value)),
  };
});

after(async () => {
  await engine?.close();
});

/** A viewer over the grid in a fresh DOM: what it drew, and the specs it told. */
function viewer(spec: TimeseriesSpec): { view: TimeseriesView; root: HTMLElement; drawn: EChartsOption[]; told: TimeseriesSpec[]; zooms: unknown[] } {
  const dom = new JSDOM('<!doctype html><div id="t" style="height:600px"></div>');
  const root = dom.window.document.getElementById('t') as unknown as HTMLElement;
  const drawn: EChartsOption[] = [];
  const told: TimeseriesSpec[] = [];
  const zooms: unknown[] = [];
  const mount = (): MountedTimeseries => ({
    show: (option) => { drawn.push(option); },
    zoomTo: (from, to) => { zooms.push({ from, to }); },
    zoomed: () => ({}),
    highlight: () => {},
    height: () => 480,
    picture: () => null,
    pngUrl: () => null,
    dispose: () => {},
  });
  const view = new TimeseriesView(root, {
    grid: (cube) => (cube === 'prices' ? grid : undefined),
    grids: () => [grid],
    together: () => Promise.reject(new Error('one grid here')),
    setSpec: (s) => { told.push(s); },
    mount,
  }, spec);
  return { view, root, drawn, told, zooms };
}

/** Until `done` holds, the viewer's queries answered meanwhile (bounded). */
async function until(done: () => boolean, what: string): Promise<void> {
  for (let i = 0; i < 400 && !done(); i++) await new Promise((r) => setTimeout(r, 10));
  assert.ok(done(), `the viewer did not show ${what}`);
}

const SPEC: TimeseriesSpec = {
  sources: [{ alias: 'px', cube: 'prices', date: 'date', key: 'symbol' }],
  series: [
    { id: 'A', expr: parseExpr('close[SPX]'), name: 'S&P 500' },
    { id: 'B', expr: parseExpr('volatility($A, 21)'), name: '21d vol', pane: 1 },
  ],
  view: { stats: true },
};

const chips = (root: HTMLElement): string[] => [...root.querySelectorAll('.dc-ts-chip')].map((c) => c.textContent ?? '');
const statRows = (root: HTMLElement): string[][] => [...root.querySelectorAll('.dc-ts-table tr')].slice(1)
  .map((tr) => [...tr.querySelectorAll('td')].map((td) => td.textContent ?? ''));

describe('the timeseries viewer over a grid of the markets sample', () => {
  it('draws each line the database answered, its latest value in the legend and its statistics in the table', async () => {
    const { root, drawn, view } = viewer(SPEC);
    try {
      await until(() => statRows(root).length === 2 && drawn.length > 0, 'its lines and their statistics');
      const spx = of('SPX');
      const last = spx.closes.at(-1)!;
      // the legend: each line named, its latest value as the grid writes its column
      const legend = chips(root);
      assert.ok(legend[0]!.startsWith('S&P 500') && legend[0]!.includes(last.toFixed(2)), `the latest close in the legend: ${legend[0]}`);
      assert.ok(legend[1]!.startsWith('21d vol') && legend[1]!.includes('%'), `a volatility in percent: ${legend[1]}`);
      // the drawing: the line's every point, oldest first, a day each
      const series = (drawn.at(-1)!.series as { id: string; data: [string, number | null][] }[]);
      const a = series.find((x) => x.id === 'A')!;
      assert.deepEqual(a.data.map((p) => p[0].slice(0, 10)), spx.days);
      assert.deepEqual(a.data.map((p) => p[1]), spx.closes);
      // the statistics: the first and the last close, over every date (no range: Max)
      const [first, lastCell] = statRows(root)[0]!.slice(1, 3);
      assert.deepEqual([first, lastCell], [spx.closes[0]!.toFixed(2), last.toFixed(2)]);
      // the band of panes: the volatility in a pane of its own, under the main one
      assert.equal((drawn.at(-1)!.grid as unknown[]).length, 2);
    } finally {
      view.dispose();
    }
  });

  it('a range told as the page\'s spec, the zoom moved to it; a line hidden is drawn again from the rows it has', async () => {
    const { root, drawn, told, zooms, view } = viewer(SPEC);
    try {
      await until(() => statRows(root).length === 2, 'its statistics');
      const year = [...root.querySelectorAll<HTMLButtonElement>('.dc-ts-range')].find((b) => b.textContent === '1Y')!;
      year.click();
      assert.deepEqual(told.at(-1)!.range, { last: '1y' });
      assert.equal(zooms.length, 1, 'the zoom moved to the range');
      // the buttons painted again, the range's pressed
      const pressed = (): string[] => [...root.querySelectorAll('.dc-ts-range[aria-pressed="true"]')].map((b) => b.textContent ?? '');
      await until(() => pressed().join() === '1Y', 'its range pressed');
      // a line hidden by its chip: told, and drawn from the rows it has -- nothing asked again
      await new Promise((r) => setTimeout(r, 100));
      const asked = runs;
      const drew = drawn.length;
      (root.querySelector('.dc-ts-chip') as HTMLElement).click();
      assert.equal(told.at(-1)!.series[0]!.hidden, true);
      await until(() => drawn.length > drew, 'the line hidden');
      assert.equal(runs, asked, 'a line hidden asks the database nothing');
      const ids = (drawn.at(-1)!.series as { id: string }[]).map((x) => x.id);
      assert.ok(!ids.includes('A'), 'the hidden line is not drawn');
    } finally {
      view.dispose();
    }
  });

  it('the series editor reads an expression as it is typed, says where it cannot, and plots it', async () => {
    const { root, told, view } = viewer(SPEC);
    try {
      await until(() => statRows(root).length === 2, 'its statistics');
      [...root.querySelectorAll<HTMLButtonElement>('.dc-ts-bar button')].find((b) => b.textContent === '+ Series')!.click();
      const pop = root.querySelector('.dc-ts-pop') as HTMLElement;
      assert.equal(pop.hidden, false, 'the editor is open');
      const expr = pop.querySelector('.dc-ts-expr') as HTMLInputElement;
      const typed = (text: string): string => {
        expr.value = text;
        expr.dispatchEvent(new expr.ownerDocument.defaultView!.Event('input'));
        return pop.querySelector('.dc-ts-error')?.textContent ?? '';
      };
      assert.match(typed('vola($A)'), /there is no function vola/);
      assert.match(typed('macd($A)'), /recursion/);
      assert.equal(typed('mean($A, 50)'), '');
      [...pop.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent === 'OK')!.click();
      const added = told.at(-1)!.series.at(-1)!;
      assert.equal(added.id, 'C');
      assert.deepEqual(added.expr, parseExpr('mean($A, 50)'));
      assert.equal(pop.hidden, true, 'the editor closed');
    } finally {
      view.dispose();
    }
  });
});
