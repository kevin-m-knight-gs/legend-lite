// THE PAGE'S MODEL (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §15.3): where the page's classes are -- a project in the
// model home (the page's own SDLC by default, the one Studio keeps its projects in), made quietly at the first class,
// named after the page, with a workspace of DataCube's -- and which grid is which class. The page document keeps it
// (`PageModelRef`). Making the project, reading it and saving to it are the generic library's
// (model-changes/, docs/ONE_PLATFORM_PROGRAM_2026_10_12.md B4); this is only the page's record.

import type { NewProject } from '../../model-changes/src/project.ts';
import { modelKept, modelMade, modelPinned, type PageCommand } from './page-commands.ts';

/** Where the page's model is, as the page document keeps it. */
export interface PageModelRef {
  /** The project, `groupId:artifactId`. */
  readonly project: string;
  /** DataCube's workspace in it. */
  readonly workspace: string;
  /** The package the page's classes are made in. */
  readonly package: string;
  /** Each grid's tables in the project's store, by the grid's cube id: the names its leaves' tables take in this tab
   *  (a file's grid one, a join one per leaf, in its leaves' order), kept, since the store's mapping reads them by those
   *  names (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §18.3). */
  readonly tables: Readonly<Record<string, readonly string[]>>;
  /** Each grid's class, by the grid's cube id: the class its rows were made (a page saved before it had none). */
  readonly classes?: Readonly<Record<string, string>>;
  /**
   * The revision the page is pinned to (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §6, §16.1): the one its last model
   * action saved, where its class grids are read; none while nothing of the project is saved (its first save failed).
   */
  readonly revision?: string;
}

/** The group a page's project is made in. */
export const PAGE_GROUP = 'datacube';
/** DataCube's workspace in a page's project. */
export const PAGE_WORKSPACE = 'datacube';

/** A page's project, as the library makes it: in DataCube's group, named after the page. */
export function pageProject(pageName: string): NewProject {
  return { name: pageName, group: PAGE_GROUP, description: 'The classes of a DataCube page (Create a Class…)' };
}

/**
 * WHAT A MODEL ACTION CHANGED OF THE PAGE'S MODEL, AS THE PAGE COMMANDS THAT MAKE THAT CHANGE (page-commands.ts
 * modelInto, modelKeep, modelPin): what the screen tells for Create a Class…, an Association, a Data Space -- the
 * commands Python's model actions apply -- so a page's other editor follows it, never reads it whole.
 */
export function modelCommands(before: PageModelRef | undefined, after: PageModelRef): PageCommand[] {
  const out: PageCommand[] = [];
  if (before === undefined) out.push({ kind: 'modelInto', project: after.project, workspace: after.workspace, package: after.package });
  const cubes = new Set([...Object.keys(after.tables), ...Object.keys(after.classes ?? {})]);
  for (const cube of cubes) {
    const tables = after.tables[cube];
    const cls = after.classes?.[cube];
    const tablesChanged = tables !== undefined && JSON.stringify(before?.tables[cube]) !== JSON.stringify(tables);
    const classChanged = cls !== undefined && before?.classes?.[cube] !== cls;
    if (tablesChanged || classChanged) {
      out.push({ kind: 'modelKeep', cube, tables: tablesChanged ? [...tables!] : null, class: classChanged ? cls! : null });
    }
  }
  if (after.revision !== undefined && after.revision !== before?.revision) out.push({ kind: 'modelPin', revision: after.revision });
  return out;
}

export class PageModel {
  #ref: PageModelRef | undefined;

  constructor(ref?: PageModelRef) {
    this.#ref = ref;
  }

  /** Where the page's model is, once it has one. */
  get ref(): PageModelRef | undefined {
    return this.#ref;
  }

  /**
   * Where the next model action writes, as the library takes it (model-changes `Into`): the page's project, or one to
   * make named after the page (`name`), its classes in `pkg` -- the page's from the moment it is made (`made`), so a
   * workspace or a save failing after leaves it the page's, never a project the next class makes again beside it.
   */
  into(name: string, pkg: string): { readonly project: string | NewProject; readonly workspace: string; readonly package: string;
    readonly made: (project: string) => void; } {
    const ref = this.#ref;
    return {
      project: ref?.project ?? pageProject(name),
      workspace: ref?.workspace ?? PAGE_WORKSPACE,
      package: ref?.package ?? pkg,
      made: (project) => {
        this.#ref = modelMade(undefined, project, PAGE_WORKSPACE, pkg);
      },
    };
  }

  /** The page is now pinned to `revision`: the one its last model action saved. */
  pin(revision: string): void {
    this.#ref = modelPinned(this.#ref, revision);
  }

  /** The page's model now keeps `cls` as the grid `cube`'s class. */
  keepClass(cube: string, cls: string): void {
    this.#ref = modelKept(this.#ref, cube, null, cls);
  }

  /** The page's model now keeps `tables` (its leaves', in their order) for the grid `cube`. */
  keepTables(cube: string, tables: readonly string[]): void {
    this.#ref = modelKept(this.#ref, cube, tables, null);
  }
}
