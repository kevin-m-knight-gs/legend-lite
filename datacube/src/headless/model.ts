// THE PAGE'S MODEL, AS DATACUBE NAMES IT, HEADLESS (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md B4): what Python's
// `grid.create_class()`, `page.create_association()` and `page.create_data_space()` ask of DataCube's own code beside
// the generic library (legend_lite/model.py) -- the names the windows offer before a person changes them, the project a
// page's classes go in, and the query that finds a class's key held twice over its rows -- so Python writes none of
// DataCube's rules again.

import { fn, toJson } from '../../../pure-protocol/src/index.ts';
import { suggestedDataSpaceName, suggestedPackage } from '../model-names.ts';
import { keptFor } from '../page-document.ts';
import { PAGE_WORKSPACE, pageProject, type PageModelRef } from '../page-model.ts';
import { duplicateLambda } from '../query.ts';

/** A page's model as the windows name it before its first class: its package, its data space, its project to make
 *  (page-model.ts `pageProject`) and DataCube's workspace in it. JSON. */
export function pageModelNames(pageName: string): string {
  return JSON.stringify({
    package: suggestedPackage(pageName), dataSpace: suggestedDataSpaceName(pageName), project: pageProject(pageName),
    workspace: PAGE_WORKSPACE,
  });
}

/**
 * The query that finds `column`'s first value held twice over a class's rows, read through its relation function
 * (`fn`, its mapping's `~func`) over the project -- the fact Create an Association… presets how many of an end from
 * (model-actions.ts `unique`: no row, and the key is unique). JSON, as the protocol writes it.
 */
export function classDuplicate(relationFunction: string, column: string): string {
  return toJson(duplicateLambda(fn(relationFunction), column));
}

/**
 * A page's model as the page writes it, for the cubes it still holds (page-document.ts `keptFor`): a grid removed takes
 * its table's name and its class with it, as DataCube's reader holds every kept name to a cube of the page -- what
 * Python's `page.remove(grid)` asks, so a page written in Python opens. JSON.
 */
export function pageModelKept(model: string, cubes: string): string {
  return JSON.stringify(keptFor(JSON.parse(model) as PageModelRef, (JSON.parse(cubes) as string[]).map((id) => ({ id }))));
}
