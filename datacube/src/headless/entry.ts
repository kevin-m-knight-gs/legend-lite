// The headless bundle's entry: what Python's host loads into V8 (docs/DATACUBE_HEADLESS_PYTHON_DESIGN_2026_10_11.md
// §3), built as one script whose exports are the global `legendLiteHeadless`. Its first two imports put the web layer
// in place before any module that needs it as it loads.

import './install-web.ts';
import './install-url.ts';

import { REQUEST_KINDS, type PlannerWorker } from '../../../engine-client/src/legend/planner-protocol.ts';
import { hostCalls, hostDatabase, hostWorker, type HostDatabase } from './host.ts';
import {
  gridApply, gridCalculate, gridChart, gridClass, gridCube, gridExport, gridFilter, gridRelation, gridView,
} from './grid.ts';
import { forget, ingest, mount, remoteAccess } from './data-in.ts';
import { classDuplicate, pageModelNames } from './model.ts';
import { layoutBands, layoutTiles } from './layout.ts';
import { nameGiven, pageApply, pageFilter, pageNew, pageRead } from './page.ts';

/** The request kinds this bundle's planner sends: the host checks, before anything runs, that it answers each (§7). */
export { REQUEST_KINDS };

/** A grid, headless (§4): its view, its rows as a relation, its rows as a class's (B4), its export (D), a chart of it
 *  as DataCube makes and judges one (chart-spec.ts chartFrom and chartProblems: written once, which Python asks), and
 *  its cube over a frame as DataCube makes one, new or rebuilt over the frame as it is now (cube-document.ts
 *  rebuilt), and a calculated column added to it, its filter set as the screen's editors do, and the grid edited as the
 *  screen edits one (gridApply: grid-commands.ts, the commands its panels, menu and drag zones build). */
export { gridApply, gridCalculate, gridChart, gridClass, gridCube, gridExport, gridFilter, gridRelation, gridView };

/** Data in (docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md §4.3): a file read into the host's database by upload.ts, its
 *  table dropped again, and a remote file mounted as a view (its extensions and keys put in place first, where the host
 *  checks what it names). */
export { forget, ingest, mount, remoteAccess };

/** The page's model as DataCube names it, and a class's key's facts (B4): what Python asks beside the generic library.
 *  (The model a page keeps for the cubes it still holds is the page commands' rule: pageApply.) */
export { classDuplicate, pageModelNames };

/** A sheet's layout read by DataCube's own rules (layout/bands.ts): its tiles, and bands of tiles as the layout
 *  picker's shape -- what Python's pages ask, so they walk no layout by rules of their own (a layout is edited by page
 *  commands: pageApply). */
export { layoutBands, layoutTiles };

/** A page document read as DataCube reads it (its own version, every rule held) and written as DataCube writes
 *  it: what Python's pages ask when one is loaded, read back, laid out or saved. And a new page as DataCube starts one:
 *  what Python's ll.Page() asks. And a page edited by commands (page-commands.ts): what Python's page edits send. */
export { pageApply, pageNew, pageRead };

/** A page filter on a class's property, made as the screen's Add a Filter makes one (a setPageFilter command, for
 *  pageApply): what Python's page.filter asks. */
export { pageFilter };

/** A page's, a tile's or a sheet's name as a person gives it, by DataCube's one rule: what Python's names ask. */
export { nameGiven };

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
