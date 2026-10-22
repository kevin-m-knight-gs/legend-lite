// A PAGE'S GESTURES AS THEIR COMMANDS (src/page-gestures.ts; docs/DATACUBE_ONE_API_PLAN_2026_10_21.md S8): what a
// gesture on a page's board means, written outside the screen -- a layout told as the page saves it, a hand move as what
// the hand did, a sheet renamed and moved by the page's rules, Show Grid's steps (the one rule Python's chart.grid()
// asks, headless pageGesture).

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { NEW_SHEET, add, tiles, type Bands } from '../src/layout/bands.ts';
import {
  PAGE_GESTURES, isPageGesture, layoutCommand, moveSheetCommand, moveToSheetCommands, movedCommand, pageCommand,
  renameSheetCommand, savedLayout, showGridCommands,
} from '../src/page-gestures.ts';
import { CubeRefusal } from '../src/snapshot.ts';
import type { ChartSpec } from '../src/chart-spec.ts';

// a sheet of three tiles side by side, the middle one an editing grid the page does not save
const BOARD: Bands = ['g1', 'edit', 'c1'].reduce<Bands>((at, id, i) => add(at, id, i === 0 ? undefined : 'g1'), NEW_SHEET);
const SAVED: ReadonlySet<string> = new Set(['g1', 'c1']);
const SPEC = { mark: 'bar' } as unknown as ChartSpec;

describe("a layout as the page saves it", () => {
  it('its saved tiles alone, the others\' places closed', () => {
    assert.deepEqual(tiles(BOARD), ['g1', 'edit', 'c1']);
    const saved = savedLayout(BOARD, SAVED);
    assert.equal(saved.kind, 'bands');
    assert.deepEqual(tiles(saved), ['g1', 'c1']);
    assert.deepEqual(layoutCommand('s1', BOARD, SAVED), { kind: 'layout', sheet: 's1', layout: saved });
  });
});

describe('a hand move, told as what the hand did', () => {
  it('a stack of two saved tiles is a stack; one with an editing grid in it is the layout whole', () => {
    assert.deepEqual(movedCommand('s1', BOARD, { kind: 'stack', tiles: ['g1', 'c1'] }, BOARD, SAVED), { kind: 'stack', tiles: ['g1', 'c1'] });
    assert.equal(movedCommand('s1', BOARD, { kind: 'stack', tiles: ['g1', 'edit'] }, BOARD, SAVED).kind, 'layout');
  });

  it("a preset picked arranges the page's tiles: the one it was picked on first, the rest in reading order", () => {
    assert.deepEqual(movedCommand('s1', BOARD, { kind: 'arrange', preset: 'stacked', first: 'c1' }, BOARD, SAVED),
      { kind: 'arrange', sheet: 's1', preset: 'stacked', order: ['c1', 'g1'] });
    // picked on the editing grid, or on none: the page's own order
    assert.deepEqual(movedCommand('s1', BOARD, { kind: 'arrange', preset: 'stacked', first: 'edit' }, BOARD, SAVED),
      { kind: 'arrange', sheet: 's1', preset: 'stacked', order: null });
  });

  it('any other move is the layout whole, as the page saves it', () => {
    assert.deepEqual(movedCommand('s1', BOARD, undefined, BOARD, SAVED), layoutCommand('s1', BOARD, SAVED));
  });
});

describe("a sheet's name and place", () => {
  it('renamed by the names\' one rule: trimmed; a blank is none of its own; the name it has, nothing', () => {
    assert.deepEqual(renameSheetCommand('s1', '  Risk ', undefined), { kind: 'renameSheet', sheet: 's1', name: 'Risk' });
    assert.deepEqual(renameSheetCommand('s1', '   ', 'Risk'), { kind: 'renameSheet', sheet: 's1', name: null });
    assert.equal(renameSheetCommand('s1', 'Risk', 'Risk'), null);
    assert.equal(renameSheetCommand('s1', undefined, undefined), null);
  });

  it("moved within the order's ends; where it is, nothing", () => {
    assert.deepEqual(moveSheetCommand('s1', 0, 9, 3), { kind: 'moveSheet', sheet: 's1', to: 2 });
    assert.deepEqual(moveSheetCommand('s3', 2, -4, 3), { kind: 'moveSheet', sheet: 's3', to: 0 });
    assert.equal(moveSheetCommand('s2', 1, 1, 3), null);
  });

  it('a tile moved to a new sheet makes the sheet first, in the same step', () => {
    assert.deepEqual(moveToSheetCommands('g1', 's9', true, 2),
      [{ kind: 'addSheet', id: 's9', name: null }, { kind: 'moveTile', tile: 'g1', sheet: 's9', columns: 2 }]);
    assert.deepEqual(moveToSheetCommands('g1', 's2', false, null), [{ kind: 'moveTile', tile: 'g1', sheet: 's2', columns: null }]);
  });
});

describe("Show Grid's steps, the one rule the screen's and Python's ask", () => {
  it('the chart follows the new grid, then draws as it was drawn', () => {
    assert.deepEqual(showGridCommands('c1', 'g2', SPEC), [{ kind: 'followGrid', chart: 'c1', grid: 'g2' }, { kind: 'setChart', chart: 'c1', spec: SPEC }]);
  });

  it('asked by name, its arguments read and refused in words', () => {
    assert.equal(isPageGesture('chart.showGrid'), true);
    assert.equal(isPageGesture('sheet.layout'), false, "a layout drawn is the board's own");
    assert.deepEqual(PAGE_GESTURES['chart.showGrid']({ chart: 'c1', grid: 'g2', spec: SPEC }), showGridCommands('c1', 'g2', SPEC));
    assert.throws(() => PAGE_GESTURES['chart.showGrid']({ chart: 'c1', spec: SPEC }),
      (e: unknown) => e instanceof CubeRefusal && /needs grid/.test(e.message));
  });
});

describe('a command of the person\'s input alone', () => {
  it('is its kind and its fields, as Python\'s generated builder writes it', () => {
    assert.deepEqual(pageCommand('renameTile', { tile: 'g1', title: 'Book' }), { kind: 'renameTile', tile: 'g1', title: 'Book' });
  });
});
