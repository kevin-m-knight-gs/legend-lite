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
//      steps said, told nothing (they were told); a grid added here made again by its own maker under the id the keeper
//      gave it; then each grid only this screen's edits changed put back at its SENT cube, its history kept, and every
//      grid's change in flight waited for;
//   4. the page on screen -- the keeper's, with nothing pending on it -- which the channel checks against the keeper's
//      hash (W17), before the pending edits are made again (a restarted grid's as new steps, the others' as no step).
// The page is held through steps 1-4 (PageApp.hold): a gesture made meanwhile does nothing, neither applied nor told,
// rather than land on a board that is being put back and replayed (the D19 audit's 5).
// Throws when a command does not apply here: the page is then opened again, said. No DOM of its own: the app hands it
// the page and its grids, so the rule is tested with real pages alone (test/live-channel.test.ts).

import { configurationOf, type CubeDocument } from '../cube-document.ts';
import { gridBetween } from '../grid-between.ts';
import type { GridCommand } from '../grid-commands.ts';
import { applyPageCommands } from '../page-commands.ts';
import type { PageDocument } from '../page-document.ts';
import type { GridMaker, PageApp } from '../page/page-app.ts';
import { sameValue, type CubeSnapshot } from '../snapshot.ts';
import { treeOf } from '../cube-document.ts';
import { applyLive, type LiveTarget } from './live-follow.ts';
import type { Converge, Converged } from './live-channel.ts';

/** A grid on the page, as the converge needs it: its state, and its commands applied as another editor's or its own. */
export interface ConvergeGrid {
  /** A change of the grid in flight (its query not answered yet). */
  readonly busy: boolean;
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
  // the page held through steps 1-4: a gesture meanwhile does nothing (PageApp.hold; the D19 audit's 5)
  page.hold();
  try {
    return await converging(on, deps);
  } finally {
    page.release();
  }
}

/** How long a grid's change in flight is waited for before the page is hashed (the channel's QUIET_AT_MOST). */
const QUIET_AT_MOST = 10_000;

async function converging(on: Converge, deps: ConvergeDeps): Promise<Converged> {
  const { page } = deps;
  page.renamedBy(on.renamed);
  // the grids whose history starts again (W13), put back at the confirmed cube before the steps: those another editor's
  // steps touch, the refused batch names, a rename touches -- and those whose cube a PAGE step of `since` changes (a
  // chart's removal takes its selection off its grid's filter: page-commands.ts unselected), found by applying the page
  // steps to the confirmed page, purely; a page step refused there: every touched grid (the D19 audit's second round, 1).
  // Every other grid an edit of this screen's changed (sent, or pending) is put back at its SENT cube after the steps,
  // its history kept.
  const whole = new Set<string>();
  for (const e of [...on.since, ...on.refused]) if ('grid' in e) whole.add(e.grid);
  for (const [planned, given] of Object.entries(on.renamed)) whole.add(planned).add(given);
  const touched = new Set(whole);
  for (const e of [...on.edits, ...on.pending]) if ('grid' in e) touched.add(e.grid);
  const pageSteps = on.since.flatMap((e) => ('page' in e ? [e.page] : []));
  const cubeIn = (p: PageDocument, id: string): CubeDocument | undefined => {
    const view = p.views.find((v) => v.id === id);
    return view === undefined ? undefined : p.cubes.find((c) => c.id === view.cube)?.cube;
  };
  if (pageSteps.length > 0) {
    try {
      const after = applyPageCommands({ page: on.confirmed, held: on.held }, pageSteps).draft.page;
      for (const id of touched) if (!sameValue(cubeIn(on.confirmed, id), cubeIn(after, id))) whole.add(id);
    } catch {
      for (const id of touched) whole.add(id);
    }
  }
  // the grids only this screen's edits changed: put back at the sent cube (a follow sent nothing: the confirmed one)
  const own = [...touched].filter((id) => !whole.has(id));
  // the cube each touched grid is put back at: the confirmed page's, or -- a grid only this screen's edits changed --
  // the sent page's, which the page's own state takes too (the state and the screen agree on every grid's cube)
  const cubeAt = (id: string): CubeDocument | undefined => cubeIn(whole.has(id) || on.sent === undefined ? on.confirmed : on.sent, id);
  const ownCubes = new Map<string, CubeDocument>();
  for (const id of own) {
    const view = on.confirmed.views.find((v) => v.id === id);
    const cube = cubeAt(id);
    if (view !== undefined && cube !== undefined) ownCubes.set(view.cube, cube);
  }
  const putBack = async (id: string, keepHistory: boolean): Promise<void> => {
    const grid = deps.grid(id);
    const held = cubeAt(id);
    if (grid === undefined || held === undefined) return;
    const back = gridBetween({ snapshot: grid.snapshot, config: grid.configuration, tree: grid.tree }, draftOf(held, grid.snapshot));
    if (back.length === 0) return;
    const refused = await grid.applyGridCommands(back, 'its keeper', false, keepHistory);
    if (refused !== null) throw new Error(`${id} could not be put back at its keeper's: ${refused}`);
  };
  // 1. the board back at the keeper's page at the version shown; each grid whose history starts again back at its
  //    confirmed cube
  page.putBack(ownCubes.size === 0 ? on.confirmed : { ...on.confirmed, cubes: on.confirmed.cubes.map((c) => (ownCubes.has(c.id) ? { id: c.id, cube: ownCubes.get(c.id)! } : c)) }, on.held);
  for (const id of whole) await putBack(id, false);
  const target = (isOwn: boolean): LiveTarget => ({
    grids: () => page.grids,
    cubes: () => page.cubes.length,
    follow: (commands, makers: ReadonlyMap<string, GridMaker>) => page.follow(commands, makers),
    applyGrid: async (grid, commands) => deps.grid(grid)?.applyGridCommands(commands, isOwn ? 'made again' : 'Python', isOwn) ?? `no grid ${grid} here`,
    // a grid added here, made again under the id the keeper gave it, is made by its own maker (PageApp.knows), never
    // opened again from its source; another editor's is opened as a saved page's are
    open: async (document, grid, several) => (page.knows(grid)
      ? { make: () => { throw new Error(`${grid} is made again by its own maker`); } }
      : deps.open(document, grid, several)),
    frameVersion: deps.frameVersion,
  });
  // 2. the other editors' steps; 3. this screen's, as the keeper applied them (a grid's put back at its sent cube
  //    shows them still, so only a restarted grid's are made again)
  await applyLive(on.since, target(false));
  await applyLive(on.edits.filter((e) => !('grid' in e) || whole.has(e.grid)), target(true));
  // 3b. each grid only this screen's edits changed -- on the board still, or made again by step 3 from where it was,
  //     pending edits and all (the D19 audit's second round, 4) -- back at its sent cube, its history kept
  for (const id of own) await putBack(id, true);
  await page.ready();
  // 3c. every grid's change in flight landed (a chart's removal refilters its grid, asynchronously) before the page is
  //     read: the hash is of the page as it will be, never of a state passing (the D19 audit's second round, 2)
  const until = Date.now() + QUIET_AT_MOST;
  while (page.grids.some((id) => deps.grid(id)?.busy === true) && Date.now() < until) await new Promise((r) => setTimeout(r, 10));
  // 4. the page on screen now
  const now = deps.pageNow();
  if (now === undefined) throw new Error('the page cannot say what it is');
  return { page: now, restarted: whole };
}

/** A cube document as a grid's draft, over the grid's source (what a run alone adds, the column types, is no part). */
function draftOf(cube: CubeDocument, like: CubeSnapshot): { snapshot: CubeSnapshot; config: ReturnType<typeof configurationOf>; tree: ReturnType<typeof treeOf> } {
  return { snapshot: { ...cube.query, source: like.source, epoch: 0 } as CubeSnapshot, config: configurationOf(cube), tree: treeOf(cube) };
}
