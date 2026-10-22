// The headless bundle's entry: what Python's host loads into V8 (docs/DATACUBE_HEADLESS_PYTHON_DESIGN_2026_10_11.md
// §3), built as one script whose exports are the global `legendLiteHeadless`. Its first two imports put the web layer
// in place before any module that needs it as it loads.

import './install-web.ts';
import './install-url.ts';

import { REQUEST_KINDS, type PlannerWorker } from '../../../engine-client/src/legend/planner-protocol.ts';
import { hostCalls, hostDatabase, hostWorker, type HostDatabase } from './host.ts';
import {
  cubeLeaves, gridApply, gridBase, gridCalculate, gridChart, gridClass, gridCube, gridExport, gridFilter, gridOpened,
  gridRelation, gridSetUp, gridSnapPull, gridView, snapRefused,
} from './grid.ts';
import { forget, ingest, landed, mount, remoteAccess } from './data-in.ts';
import { classDuplicate, pageModelInto, pageModelNames } from './model.ts';
import { layoutBands, layoutTiles } from './layout.ts';
import {
  gridSnapped, nameGiven, pageApply, pageFilter, pageNew, pageOpenedLive, pageRead, pageReads, pageRebase, pageSaved,
  pageWithCube, pageWritten,
} from './page.ts';
import { joinCube, joinQuestions } from './join.ts';

/** The request kinds this bundle's planner sends: the host checks, before anything runs, that it answers each (§7). */
export { REQUEST_KINDS };

/** A grid, headless (§4): its view, its rows as a relation, its rows as a class's (B4), its export (D), a chart of it
 *  as DataCube makes and judges one (chart-spec.ts chartFrom and chartProblems: written once, which Python asks), and
 *  its cube over a frame as DataCube makes one, new or rebuilt over the frame as it is now (cube-document.ts
 *  rebuilt), and a calculated column added to it, its filter set as the screen's editors do, and the grid edited as the
 *  screen edits one (gridApply: grid-commands.ts, the commands its panels, menu and drag zones build). What a cube reads
 *  (cubeLeaves), its base over the host's frames (gridBase) and a saved cube opened over them as it is now (gridOpened):
 *  a frame's, or a join's of frames (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §18.3). */
export {
  cubeLeaves, gridApply, gridBase, gridCalculate, gridChart, gridClass, gridCube, gridExport, gridFilter, gridOpened,
  gridRelation, gridSnapPull, gridView,
};

/** Why a snap past its host's row limit is refused: the tab's sentence, which Python's engine says too (Z16). And a new
 *  grid's query as the commands that make it, in a person's order (gridSetUp: Python's `page.grid(...)`, Z8). */
export { gridSetUp, snapRefused };

/** Data in (docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md §4.3): a file read into the host's database by upload.ts, its
 *  table dropped again, and a remote file mounted as a view (its extensions and keys put in place first, where the host
 *  checks what it names). */
export { forget, ingest, landed, mount, remoteAccess };

/** The page's model as DataCube names it, and a class's key's facts (B4): what Python asks beside the generic library.
 *  (The model a page keeps for the cubes it still holds is the page commands' rule: pageApply.) */
export { classDuplicate, pageModelInto, pageModelNames };

/** A sheet's layout read by DataCube's own rules (layout/bands.ts): its tiles, and bands of tiles as the layout
 *  picker's shape -- what Python's pages ask, so they walk no layout by rules of their own (a layout is edited by page
 *  commands: pageApply). */
export { layoutBands, layoutTiles };

/** A page document read as DataCube reads it (its own version, every rule held) and written as DataCube writes
 *  it: what Python's pages ask when one is loaded or read back (pageRead), and saved or written as a file, refused as
 *  the screen's Save refuses it (pageSaved). And a new page as DataCube starts one: what Python's ll.Page() asks. And a
 *  page edited by commands (page-commands.ts): what Python's page edits send. */
export { pageApply, pageNew, pageOpenedLive, pageRead, pageSaved };
/** A grid's edit on a page: the page with its cube as the edit left it, its charts following it (V1, V2). */
export { pageWithCube };
/** The page's keeper (Python's: docs/DATACUBE_PAGE_KEEPER_2026_10_20.md) asks DataCube what it never reads itself: a
 *  page written whole -- a restore, an undo's or a redo's, or a page loaded -- read as DataCube reads a page
 *  (pageWritten), what a page reads (pageReads), and a writer's edits applied to the page as it is now (pageRebase:
 *  page-rebase.ts, the one rule for it; docs/DATACUBE_ONE_API_PLAN_2026_10_21.md S4). */
export { pageReads, pageRebase, pageWritten };

/** A page filter on a class's property, made as the screen's Add a Filter makes one (a setPageFilter command, for
 *  pageApply): what Python's page.filter asks. */
export { pageFilter };

/** A grid's Snap as the page command that says it (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §18.8): what its base
 *  query's pull is (gridSnapPull, above) and, its host having copied it, the copy written down as the screen writes
 *  it -- what Python's grid.snap() asks, so the page's record of a copy is DataCube's. */
export { gridSnapped };

/** A page's, a tile's or a sheet's name as a person gives it, by DataCube's one rule: what Python's names ask. */
export { nameGiven };

/** A join of two of a page's cubes over the host's tables (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §17.4): the join's
 *  grid's cube document, for the page command addGrid, and what the join will do before it is made -- the tab's Join…,
 *  with no screen. */
export { joinCube, joinQuestions };

/** What DataCube's code runs on in this host: its planner's worker, and the database. */
export interface Headless {
  readonly worker: () => PlannerWorker;
  readonly database: HostDatabase;
}

/** The planner's worker and the database, over the host's calls (`legendLiteHost`, defined before the bundle loads). */
export function start(): Headless {
  const calls = hostCalls();
  return { worker: () => hostWorker(calls), database: hostDatabase(calls) };
}
