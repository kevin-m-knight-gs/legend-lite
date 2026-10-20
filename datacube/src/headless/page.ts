// A PAGE DOCUMENT READ AND WRITTEN AS DATACUBE READS AND WRITES ONE, HEADLESS (docs/PYTHON_ASKS_DATACUBE_2026_10_15.md,
// item 1): what Python's pages ask of page-document.ts when one is loaded, read back from the open page, given a sheet's
// layout, saved or written out -- a page of DataCube's own version, every one of its rules held
// (readPage), written as DataCube writes it (pageToJson) -- so Python checks and writes no page by rules of its own.

import { CubeDocumentError, readCube } from '../cube-document.ts';
import {
  gridCopyOf, openedLive, PageDocumentError, PageNoCubeError, pageToJson, readPage, startingViews, writePageOf,
  type PageDocument,
} from '../page-document.ts';
import { givenName, type NamedPart } from '../page-names.ts';
import { applyPageCommands, readPageCommand } from '../page-commands.ts';
import { CubeRefusal } from '../snapshot.ts';
import { toFilterNode } from '../filter-operators.ts';
import { draftFrom, type GivenFilter } from './grid.ts';
import { fromJson, toJson } from '../../../pure-protocol/src/index.ts';
import { defaultLabel } from '../../../engine-client/src/snap.ts';

/**
 * A page document (JSON text) as DataCube reads it and would write it again. JSON: `{page}`, the page's text as
 * DataCube writes it (its numbers exact), or `{problems}`, why DataCube does not read it, in its words.
 */
export function pageRead(text: string): string {
  try {
    return JSON.stringify({ page: pageToJson(readPage(text)) });
  } catch (e) {
    if (!(e instanceof PageDocumentError) && !(e instanceof CubeDocumentError)) throw e;
    // a page with no cube said as one (PageNoCubeError), so Python adds what makes one rather than guessing it
    return JSON.stringify({ problems: [e.message], ...(e instanceof PageNoCubeError ? { noCube: true } : {}) });
  }
}

/**
 * A NEW PAGE, as DataCube starts one (page-document.ts startingViews, written by writePageOf): named `name` by DataCube's
 * one rule (givenName), no cube yet, its one sheet an empty board -- so Python writes no page skeleton of its own. JSON:
 * `{page}`, its text as DataCube writes it, or `{problems}`, why the name was refused, in DataCube's words. A page with
 * no cube is not one DataCube opens (readPage's PageNoCubeError): Python's first grid makes it one.
 */
export function pageNew(name: string): string {
  const given = givenName('page', name);
  if ('refused' in given) return JSON.stringify({ problems: [given.refused] });
  if (given.name === undefined) throw new Error("a page's name is never left to what it shows");
  return JSON.stringify({ page: pageToJson(writePageOf({ name: given.name, cubes: [], views: startingViews() })) });
}

const PARTS: readonly NamedPart[] = ['page', 'tile', 'sheet'];

/**
 * A NAME PYTHON GIVES a page, a tile or a sheet, by DataCube's one rule (page-names.ts givenName, which the screen's
 * renames ask too) -- so Python names nothing by a rule of its own. JSON: `{name}` (null: a sheet's blank, none of its
 * own) or `{problems}`, why it was refused, in DataCube's words.
 */
export function nameGiven(part: string, text: string): string {
  if (!PARTS.includes(part as NamedPart)) throw new Error(`a name is a page's, a tile's or a sheet's, not a ${part}'s`);
  const given = givenName(part as NamedPart, text);
  return JSON.stringify('refused' in given ? { problems: [given.refused] } : { name: given.name ?? null });
}

/**
 * A PAGE EDITED BY COMMANDS (page-commands.ts: the one function the screen's page edits are judged by --
 * docs/DATACUBE_COMMANDS_PAGE_2026_10_18.md): `page`, its document as Python holds it (JSON, its numbers exact),
 * `held`, every tile and sheet id it has held (a JSON list), and `commands`, a JSON list, each read by readPageCommand
 * (an unknown kind or field refused in words). Applied in order, the first refusal stopping them. JSON:
 * `{page, held, commands}` -- the page as DataCube writes it, the ids held now, the commands as applied (an id a
 * command left out allocated, so Python names its new tile by DataCube's rule) -- or `{problems}`, in DataCube's words.
 */
export function pageApply(page: string, held: string, commands: string): string {
  let read;
  try {
    const doc = heldPage(page);
    // a grid's cube read from the commands' exact numbers (a calculated column's 1.10 kept), as the page's cubes are
    const exact = fromJson(commands) as unknown[];
    read = (JSON.parse(commands) as unknown[]).map((c, i) => readPageCommand((c as { kind?: unknown } | null)?.kind === 'addGrid'
      ? { ...(c as object), document: (exact[i] as Record<string, unknown>)['document'] } : c));
    const done = applyPageCommands({ page: doc, held: JSON.parse(held) as string[] }, read);
    return `{"page":${JSON.stringify(pageToJson(done.draft.page))},"held":${JSON.stringify(done.draft.held)},`
      + `"commands":[${done.applied.map((c) => toJson(c)).join(',')}]}`;
  } catch (e) {
    if (!(e instanceof CubeRefusal) && !(e instanceof CubeDocumentError)) throw e;
    return JSON.stringify({ problems: [e.message] });
  }
}

/**
 * A PAGE FILTER AS PYTHON ASKS FOR ONE (Page.filter; docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §16.2): `given`,
 * `{class, filter, id?}` -- the class by its path or its name, one a grid of the page reads; the condition as Python's
 * grid filter hands one in (grid.ts draftFrom: the filter editor's operands, each operator one its property's type is
 * offered), over the class's properties as the page's grids over it read them (their class sources' columns) -- made the
 * setPageFilter command the screen's Add a Filter makes, for pageApply. JSON: `{command}`, or `{problems}`, in DataCube's
 * words.
 */
export function pageFilter(page: string, given: string): string {
  const doc = heldPage(page);
  const asked = JSON.parse(given) as { class: string; filter: GivenFilter; id?: string };
  const sources = doc.cubes.map((c) => c.cube.source).flatMap((s) => (s._type === 'class' ? [s] : []));
  const over = sources.filter((s) => s.class === asked.class || s.name === asked.class);
  const classes = [...new Set(over.map((s) => s.class))];
  if (classes.length !== 1) {
    const all = [...new Set(sources.map((s) => s.class))];
    return JSON.stringify({ problems: [classes.length === 0
      ? `no grid of the page reads a class ${JSON.stringify(asked.class)}${all.length ? ` (its grids read ${all.join(', ')})` : ' (none reads a class)'}`
      : `${asked.class} names ${classes.join(' and ')}: give the class's path`] });
  }
  const types = new Map<string, string | undefined>(over.flatMap((s) => s.columns.map((c) => [c.name, c.type] as const)));
  const draft = draftFrom(asked.filter, types);
  if (typeof draft === 'string') return JSON.stringify({ problems: [draft] });
  const condition = toFilterNode(draft, (c) => types.get(c));
  if (condition === null) return JSON.stringify({ problems: ['a page filter lets something through: give it a condition'] });
  return `{"command":${toJson({ kind: 'setPageFilter', ...(asked.id !== undefined ? { id: asked.id } : {}), class: classes[0]!, condition, from: null })}}`;
}

/**
 * A page as Python holds it, read as the page commands take it: written by DataCube's writers (pageNew, pageRead,
 * gridApply), so its parts are read as they are -- a new page has no cube yet, which readPage refuses -- but each cube
 * by its own reader (readCube: a calculated column's numbers exact, every other number plain, as DataCube holds a
 * cube), and the fields a newer writer added kept aside, as the page's own reader keeps them (`unknown`).
 */
function heldPage(text: string): PageDocument {
  const { cubes, ...rest } = JSON.parse(text) as Record<string, unknown> & { cubes: { id: string }[] };
  const exact = (fromJson(text) as { cubes: { id: string; cube: unknown }[] }).cubes;
  const { kind, version, name, views, sheets, model, filters, copies, ...unknown } = rest;
  return {
    kind, version, name, views, sheets, ...(model !== undefined ? { model } : {}), ...(filters !== undefined ? { filters } : {}),
    // the copies its snapped grids read (§18.8): a field of the page, which its snap and live commands edit
    ...(copies !== undefined ? { copies } : {}),
    cubes: cubes.map((c, i) => ({ id: c.id, cube: readCube(exact[i]!.cube) })),
    ...(Object.keys(unknown).length > 0 ? { unknown } : {}),
  } as unknown as PageDocument;
}

/**
 * A SNAP AS ITS COMMAND (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §18.8): grid `grid` reads the copy its host made
 * (`made`: the host's answer -- its table, its rows, and the pull's receipt when it ran elsewhere), taken now, written
 * down as the screen's Snap writes it (page-document.ts gridCopyOf). JSON: the page command, for pageApply.
 */
export function gridSnapped(grid: string, made: string): string {
  const answer = JSON.parse(made) as {
    table: string; rowCount: number;
    pulledBy?: { plane: 'tab' | 'warehouse' | 'engine'; where: string; as?: string; statementId?: string; serverRows?: number };
  };
  const takenAt = new Date();
  const copy = gridCopyOf({
    table: answer.table, rowCount: answer.rowCount, takenAt, label: defaultLabel(takenAt),
    ...(answer.pulledBy ? { pulledBy: answer.pulledBy } : {}),
  });
  return JSON.stringify({ kind: 'snap', grid, copy });
}

/**
 * A SAVED PAGE AS IT OPENS (page-document.ts openedLive, §18.8): a page saved with snapped grids opens live, each said.
 * `text`: a page DataCube reads (pageRead). JSON: `{page, notes}`, the page as its document's text.
 */
export function pageOpenedLive(text: string): string {
  const opened = openedLive(readPage(text));
  return JSON.stringify({ page: pageToJson(opened.page), notes: opened.notes });
}
