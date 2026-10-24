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
import { applyPageCommands, followedPage } from '../page-commands.ts';
import type { PageDocument } from '../page-document.ts';
import type { GridMaker, PageApp } from '../page/page-app.ts';
import { sameValue, type CubeSnapshot } from '../snapshot.ts';
import { treeOf } from '../cube-document.ts';
import { applyLive, type LiveTarget } from './live-follow.ts';
import type { Converge, Converged } from './live-channel.ts';
import type { LiveCommand } from './services.ts';

/** The page commands that make a tile, by kind (page-rebase.ts MAKERS). */
const MAKERS = new Set(['addSheet', 'addGrid', 'addChart']);

/** A grid on the page, as the converge needs it: its state, and its commands applied as another editor's or its own. */
export interface ConvergeGrid {
  /** Held through the converge: a person's edit refused meanwhile (CubeApp.hold). */
  hold(): void;
  release(): void;
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
  // the page and its grids held through steps 1-4: a gesture meanwhile does nothing on the page and is refused on a
  // grid, said (PageApp.hold, CubeApp.hold; the D19 audit's 5 and its third round's 1); a grid made again by a step
  // is held as it appears
  page.hold();
  const held = new Set<ConvergeGrid>();
  const holdGrids = (): void => {
    for (const id of page.grids) {
      const grid = deps.grid(id);
      if (grid !== undefined && !held.has(grid)) {
        grid.hold();
        held.add(grid);
      }
    }
  };
  holdGrids();
  try {
    return await converging(on, deps, holdGrids);
  } finally {
    for (const grid of held) grid.release();
    page.release();
  }
}

/** How long a grid's change in flight is waited for before the page is hashed (the channel's QUIET_AT_MOST). */
const QUIET_AT_MOST = 10_000;

/** The id a command that makes a tile gives out, if any. */
const madeId = (c: LiveCommand): string | undefined => ('page' in c && MAKERS.has(c.page.kind) ? (c.page as { readonly id?: string }).id : undefined);

async function converging(on: Converge, deps: ConvergeDeps, holdGrids: () => void): Promise<Converged> {
  const { page } = deps;
  // 0. a grid this screen is making (pending) under an id the keeper's steps give out to another editor's tile (both
  //    planned the same next id over the same page), or that the keeper gave one of this screen's sent makers: moved to
  //    a fresh id first, so the other's tile is opened as theirs and this one made again under its new id (the D19
  //    audit's fourth round, 2); the pending commands are renamed by it too (`renamed`, back to the channel)
  const given = new Set([...on.since, ...on.edits].flatMap((c) => { const id = madeId(c); return id === undefined ? [] : [id]; }).concat(Object.values(on.renamed)));
  const colliding = on.pending.flatMap((c) => { const id = madeId(c); return id !== undefined && given.has(id) && 'page' in c && c.page.kind === 'addGrid' ? [id] : []; });
  const renamed = colliding.length === 0 ? {} : page.renamePending(colliding, [...given]);
  page.renamedBy(on.renamed);
  // each grid as it is now: one made again by a step (let go by the put-back, remade by its own maker) is a new grid
  // whose history starts again, its pending edits then steps of it (the D19 audit's third round, 3)
  const was = new Map(page.grids.map((id) => [id, deps.grid(id)] as const));
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
  // a cube as its grid holds it: less its name, which is the page's (page-document.ts writePageOf: "the page's name is
  // each cube's too") -- so a page renamed by another editor is no change of any grid's, and restarts no history (W13;
  // the fourth round's test, 2026-10-24: a rename made every grid whole)
  const gridsOwn = (c: CubeDocument | undefined): Omit<CubeDocument, 'name'> | undefined => {
    if (c === undefined) return undefined;
    const { name: _page, ...own } = c;
    return own;
  };
  const sameCube = (a: CubeDocument | undefined, b: CubeDocument | undefined): boolean => sameValue(gridsOwn(a), gridsOwn(b));
  if (pageSteps.length > 0) {
    try {
      const after = applyPageCommands({ page: on.confirmed, held: on.held }, pageSteps).draft.page;
      for (const id of touched) if (!sameCube(cubeIn(on.confirmed, id), cubeIn(after, id))) whole.add(id);
    } catch {
      for (const id of touched) whole.add(id);
    }
  }
  // ... and those whose cube a page step of the REFUSED batch changed on this screen when the batch was made (a chart's
  // removal takes its selection off its grid's filter: refused with its batch -- the keeper out of reach, another edit
  // of the batch not applying -- the grid shows the removal's effect still, and no grid command names it), found the
  // same way, over every grid here; a refused step that does not apply there: every grid here (the faulted soak,
  // 2026-10-24: seed 10, a chart's removal the keeper never saw)
  const refusedSteps = on.refused.flatMap((e) => ('page' in e ? [e.page] : []));
  if (refusedSteps.length > 0) {
    const here = new Set([...page.grids, ...touched]);
    try {
      const after = applyPageCommands({ page: on.confirmed, held: on.held }, refusedSteps).draft.page;
      for (const id of here) if (!sameCube(cubeIn(on.confirmed, id), cubeIn(after, id))) whole.add(id);
    } catch {
      for (const id of here) whole.add(id);
    }
    for (const id of whole) touched.add(id);
  }
  // ... and those whose cube a page step PENDING here changed (made on top of the sent page, after the write went: a
  // selected chart removed while a write was on its way): found the same way, over the sent page; only this screen's
  // edits changed them, so they join `touched` -- put back at the sent cube after the steps, the pending step made
  // again on it (the D19 audit's fourth round, 1: left alone, the grid lacked the condition the confirmed cube holds)
  const pendingSteps = on.pending.flatMap((e) => ('page' in e ? [e.page] : []));
  if (pendingSteps.length > 0) {
    const base = on.sent ?? on.confirmed;
    const here = new Set([...page.grids, ...touched]);
    try {
      const after = applyPageCommands({ page: base, held: on.held }, pendingSteps).draft.page;
      for (const id of here) if (!sameCube(cubeIn(base, id), cubeIn(after, id))) touched.add(id);
    } catch {
      for (const id of here) touched.add(id);
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
  let back = on.confirmed;
  if (ownCubes.size > 0) {
    // the sent cube in place of the confirmed one, and the charts over it following it (their spec regrouped as the cube
    // is, a selection its filter no longer holds taken off: page-commands.ts followedPage, the one rule a grid's change
    // moves its charts by) -- so the state agrees with the screen, where the charts followed the gesture as it was made
    // (the three-tab soak, 2026-10-24: seed 17, the charts' x in the state still the confirmed grouping)
    back = { ...back, cubes: back.cubes.map((c) => (ownCubes.has(c.id) ? { id: c.id, cube: ownCubes.get(c.id)! } : c)) };
    for (const cube of ownCubes.keys()) back = followedPage(back, cube, on.confirmed);
  }
  // a grid of the keeper's page this page cannot make again (its maker moved to the id an undo brought it back under,
  // its removal written and not landed) is opened from its cube, as another editor's is (the three-tab soak, seed 25)
  const makers = new Map<string, GridMaker>();
  for (const view of back.views) {
    if (view.kind !== 'grid' || page.grids.includes(view.id) || page.knows(view.id)) continue;
    const cube = back.cubes.find((c) => c.id === view.cube)?.cube;
    const opened = cube === undefined ? undefined : await deps.open(cube, view.id, false);
    if (opened !== undefined) makers.set(view.id, opened.make);
  }
  page.putBack(back, on.held, makers, on.refused.flatMap((e) => ('page' in e ? [e.page] : [])));
  // the grids the put-back made again, held before anything is awaited (the fourth round, 5)
  holdGrids();
  for (const id of whole) await putBack(id, false);
  const target = (isOwn: boolean): LiveTarget => ({
    grids: () => page.grids,
    cubes: () => page.cubes.length,
    // the grids a step adds are held as they appear (the fourth round, 5); this screen's own steps made again as its
    // own (the fourth round, 6)
    follow: async (commands, makers: ReadonlyMap<string, GridMaker>) => {
      await page.follow(commands, makers, isOwn);
      holdGrids();
    },
    applyGrid: async (grid, commands) => deps.grid(grid)?.applyGridCommands(commands, isOwn ? 'made again' : 'Python', isOwn) ?? `no grid ${grid} here`,
    // a grid added here, made again under the id the keeper gave it, is made by its own maker (PageApp.knows), never
    // opened again from its source; another editor's is opened as a saved page's are -- always, even under an id this
    // page once gave out (a refused or undone addition's, kept for an undo): the keeper never held that one (the
    // faulted soak, 2026-10-24: seed 27, another editor's grid made over this page's source)
    open: async (document, grid, several) => (isOwn && page.knows(grid)
      ? { make: () => { throw new Error(`${grid} is made again by its own maker`); } }
      : deps.open(document, grid, several)),
    frameVersion: deps.frameVersion,
  });
  // 2. the other editors' steps; 3. this screen's, as the keeper applied them (a grid's put back at its sent cube
  //    shows them still, so only a restarted grid's are made again)
  await applyLive(on.since, target(false));
  await applyLive(on.edits.filter((e) => !('grid' in e) || whole.has(e.grid)), target(true));
  holdGrids();
  const remade = new Set([...own].filter((id) => deps.grid(id) !== undefined && deps.grid(id) !== was.get(id)));
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
  return { page: now, restarted: new Set([...whole, ...remade].filter((id) => touched.has(id))), renamed };
}

/** A cube document as a grid's draft, over the grid's source (what a run alone adds, the column types, is no part). */
function draftOf(cube: CubeDocument, like: CubeSnapshot): { snapshot: CubeSnapshot; config: ReturnType<typeof configurationOf>; tree: ReturnType<typeof treeOf> } {
  return { snapshot: { ...cube.query, source: like.source, epoch: 0 } as CubeSnapshot, config: configurationOf(cube), tree: treeOf(cube) };
}
