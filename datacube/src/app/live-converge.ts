// THE SCREEN CONVERGED ON ITS KEEPER'S PAGE (docs/DATACUBE_LIVE_EDITING_2026_10_24.md §2, W7, W8): a write the keeper
// made another page of -- it had moved on meanwhile, or an id was given out afresh -- or one it refused, and the screen
// made the keeper's page without a read and without a reopen. Inside the channel's `following` (live-channel.ts):
//   1. the keeper's renames followed (the grids' makers, the page's history); the board put back at the page as the
//      keeper had it at the version shown, the ids given out here since given back (PageApp.putBack); each grid the
//      keeper's steps touch, or the refused batch names, or whose id was given out afresh, put back at its cube then,
//      by its own commands (grid-between.ts: another editor edited this grid, and its history starts again, W13); each
//      grid only this screen's own edits changed (sent, or pending) put back at its SENT cube -- what the keeper holds
//      for it -- its history kept (the audit's 4);
//   2. the other editors' steps since, applied as theirs (live-follow.ts applyLive);
//   3. this screen's sent edits as the keeper applied them (made again on top, renamed), as its own: a grid's recorded as
//      steps said, told nothing (they were told) -- those of a grid put back at its sent cube are on it still; a grid
//      added here made again by its own maker under the id the keeper gave it;
//   4. the page on screen -- the keeper's, with nothing pending on it -- which the channel checks against the keeper's
//      hash (W17), before the pending edits are made again.
// Throws when a command does not apply here: the page is then opened again, said. No DOM of its own: the app hands it
// the page and its grids, so the rule is tested with real pages alone (test/live-channel.test.ts).

import { configurationOf, type CubeDocument } from '../cube-document.ts';
import { gridBetween } from '../grid-between.ts';
import type { GridCommand } from '../grid-commands.ts';
import type { PageDocument } from '../page-document.ts';
import type { GridMaker, PageApp } from '../page/page-app.ts';
import type { CubeSnapshot } from '../snapshot.ts';
import { treeOf } from '../cube-document.ts';
import { applyLive, type LiveTarget } from './live-follow.ts';
import type { Converge, Converged } from './live-channel.ts';

/** A grid on the page, as the converge needs it: its state, and its commands applied as another editor's or its own. */
export interface ConvergeGrid {
  readonly snapshot: CubeSnapshot;
  readonly configuration: ReturnType<typeof configurationOf>;
  readonly tree: ReturnType<typeof treeOf>;
  /** `own`: a step of its history, told nothing; `keepHistory`: its keeper's put-back of a grid no other editor touched. */
  applyGridCommands(commands: readonly GridCommand[], label: string, own?: boolean, keepHistory?: boolean): Promise<string | null>;
}

/** What the converge works on: the page, its grids by tile, how a grid another editor added is opened, the frames. */
export interface ConvergeDeps {
  readonly page: PageApp;
  grid(id: string): ConvergeGrid | undefined;
  /** The page as the screen writes it now. */
  pageNow(): PageDocument | undefined;
  open: LiveTarget['open'];
  frameVersion: LiveTarget['frameVersion'];
}

export async function convergeOn(on: Converge, deps: ConvergeDeps): Promise<Converged> {
  const { page } = deps;
  page.renamedBy(on.renamed);
  // the grids another editor's steps touch, the refused batch names, or whose id was given out afresh: back at the
  // confirmed cube, their history starting again (W13); every other grid an edit of this screen's changed (sent, or
  // pending): back at its sent cube, which is what the keeper holds for it, its history kept
  const whole = new Set<string>();
  for (const e of [...on.since, ...on.refused]) if ('grid' in e) whole.add(e.grid);
  for (const [planned, given] of Object.entries(on.renamed)) whole.add(planned).add(given);
  const touched = new Set(whole);
  for (const e of [...on.edits, ...on.pending]) if ('grid' in e) touched.add(e.grid);
  // the cube each touched grid is put back at: the confirmed page's, or -- a grid only this screen's edits changed --
  // the sent page's, which the page's own state takes too (the state and the screen agree on every grid's cube)
  const cubeAt = (id: string): CubeDocument | undefined => {
    const at = whole.has(id) || on.sent === undefined ? on.confirmed : on.sent;
    const view = at.views.find((v) => v.id === id);
    return view === undefined ? undefined : at.cubes.find((c) => c.id === view.cube)?.cube;
  };
  const ownCubes = new Map<string, CubeDocument>();
  for (const id of touched) {
    if (whole.has(id) || on.sent === undefined) continue;
    const view = on.confirmed.views.find((v) => v.id === id);
    const cube = cubeAt(id);
    if (view !== undefined && cube !== undefined) ownCubes.set(view.cube, cube);
  }
  // 1. the board back at the keeper's page at the version shown; each grid touched back at its cube then
  page.putBack(ownCubes.size === 0 ? on.confirmed : { ...on.confirmed, cubes: on.confirmed.cubes.map((c) => (ownCubes.has(c.id) ? { id: c.id, cube: ownCubes.get(c.id)! } : c)) }, on.held);
  for (const id of touched) {
    const grid = deps.grid(id);
    const held = cubeAt(id);
    if (grid === undefined || held === undefined) continue;
    const back = gridBetween({ snapshot: grid.snapshot, config: grid.configuration, tree: grid.tree }, draftOf(held, grid.snapshot));
    if (back.length === 0) continue;
    const refused = await grid.applyGridCommands(back, 'its keeper', false, !whole.has(id));
    if (refused !== null) throw new Error(`${id} could not be put back at its keeper's: ${refused}`);
  }
  const target = (own: boolean): LiveTarget => ({
    grids: () => page.grids,
    cubes: () => page.cubes.length,
    follow: (commands, makers: ReadonlyMap<string, GridMaker>) => page.follow(commands, makers),
    applyGrid: async (grid, commands) => deps.grid(grid)?.applyGridCommands(commands, own ? 'made again' : 'Python', own) ?? `no grid ${grid} here`,
    // a grid added here, made again under the id the keeper gave it, is made by its own maker (PageApp.knows), never
    // opened again from its source; another editor's is opened as a saved page's are
    open: async (document, grid, several) => (page.knows(grid)
      ? { make: () => { throw new Error(`${grid} is made again by its own maker`); } }
      : deps.open(document, grid, several)),
    frameVersion: deps.frameVersion,
  });
  // 2. the other editors' steps; 3. this screen's, as the keeper applied them (a grid's put back at its sent cube
  //    shows them still)
  await applyLive(on.since, target(false));
  await applyLive(on.edits.filter((e) => !('grid' in e) || whole.has(e.grid) || on.sent === undefined), target(true));
  await page.ready();
  // 4. the page on screen now
  const now = deps.pageNow();
  if (now === undefined) throw new Error('the page cannot say what it is');
  return { page: now };
}

/** A cube document as a grid's draft, over the grid's source (what a run alone adds, the column types, is no part). */
function draftOf(cube: CubeDocument, like: CubeSnapshot): { snapshot: CubeSnapshot; config: ReturnType<typeof configurationOf>; tree: ReturnType<typeof treeOf> } {
  return { snapshot: { ...cube.query, source: like.source, epoch: 0 } as CubeSnapshot, config: configurationOf(cube), tree: treeOf(cube) };
}
