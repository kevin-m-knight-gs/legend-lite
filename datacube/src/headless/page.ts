// A PAGE DOCUMENT READ AND WRITTEN AS DATACUBE READS AND WRITES ONE, HEADLESS (docs/PYTHON_ASKS_DATACUBE_2026_10_15.md,
// item 1): what Python's pages ask of page-document.ts when one is loaded, read back from the open page, given a sheet's
// layout, saved or written out -- a page of DataCube's own version, every one of its rules held
// (readPage), written as DataCube writes it (pageToJson) -- so Python checks and writes no page by rules of its own.

import { CubeDocumentError, cubeToJson, readCube } from '../cube-document.ts';
import {
  gridCopyOf, NOTHING_TO_SAVE, openedLive, PageDocumentError, pageFile as fileOf, pageToJson, readPage, savedRecord,
  startingViews, writePageOf, type PageDocument,
} from '../page-document.ts';
import { givenName, type NamedPart } from '../page-names.ts';
import { applyPageCommands, EXACT_FIELD, followedPage, readPageCommand, type PageCommand } from '../page-commands.ts';
import { readGridCommand, type GridCommand } from '../grid-commands.ts';
import { pageReads as readsOf, rebaseEdits, withCube, type PageEdit } from '../page-rebase.ts';
import { CubeRefusal, sameValue } from '../snapshot.ts';
import { toFilterNode } from '../filter-operators.ts';
import { baseOnHost, draftFrom, gridApply, onHost, type GivenFilter, type HeldCopy, type HostFrame } from './grid.ts';
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
    return JSON.stringify({ problems: [e.message] });
  }
}

/**
 * A PAGE DOCUMENT (JSON text) AS DATACUBE SAVES IT, or writes it as a page file -- what Python's `save` and `to_json`
 * ask: read as an open page is (a page of no grid too, as one shown may be: D2) and refused as the screen's Save and
 * Export ▸ Page File refuse it, in their words, when there is nothing on it (NOTHING_TO_SAVE, B14). JSON: `{page}`, the
 * page's text as DataCube writes it, or `{problems}`, in DataCube's words.
 */
export function pageSaved(text: string): string {
  try {
    const page = readPage(text, { empty: true });
    return JSON.stringify(page.cubes.length === 0 ? { problems: [NOTHING_TO_SAVE] } : { page: pageToJson(page) });
  } catch (e) {
    if (!(e instanceof PageDocumentError) && !(e instanceof CubeDocumentError)) throw e;
    return JSON.stringify({ problems: [e.message] });
  }
}

/**
 * A PAGE DOCUMENT (JSON text) AS A PAGE FILE -- what Python's `save(path)` writes -- by DataCube's one rule, Export ▸
 * Page File's (page-document.ts pageFile, B5). JSON: `{file}`, the file's text, or `{problems}`, why it is none.
 */
export function pageFile(text: string): string {
  try {
    const made = fileOf(readPage(text, { empty: true }));
    return JSON.stringify('refused' in made ? { problems: [made.refused] } : { file: made.file });
  } catch (e) {
    if (!(e instanceof PageDocumentError) && !(e instanceof CubeDocumentError)) throw e;
    return JSON.stringify({ problems: [e.message] });
  }
}

/**
 * A PAGE DOCUMENT (JSON text) SAVED UNDER `name` AS THE PAGE STORE KEEPS IT -- what Python's `save()` sends the store
 * -- by the Save window's one rule (page-document.ts savedRecord, Z18, B4 and B13): over the record the page was last
 * saved as, whichever editor saved it, else a new one under `fresh`. JSON: `{record, id, name}`, the record's text (its
 * numbers exact), its id and the name it is saved under, or `{problems}`, why it is not saved.
 */
export function pageRecord(fresh: string, name: string, text: string): string {
  try {
    const page = readPage(text, { empty: true });
    if (page.cubes.length === 0) return JSON.stringify({ problems: [NOTHING_TO_SAVE] });
    const made = savedRecord(name, page, fresh);
    return 'refused' in made ? JSON.stringify({ problems: [made.refused] })
      : `{"record":${JSON.stringify(toJson(made))},"id":${JSON.stringify(made.id)},"name":${JSON.stringify(made.name)}}`;
  } catch (e) {
    if (!(e instanceof PageDocumentError) && !(e instanceof CubeDocumentError)) throw e;
    return JSON.stringify({ problems: [e.message] });
  }
}

/**
 * A NEW PAGE, as DataCube starts one (page-document.ts startingViews, written by writePageOf): named `name` by DataCube's
 * one rule (givenName), no cube yet, its one sheet an empty board -- so Python writes no page skeleton of its own. JSON:
 * `{page}`, its text as DataCube writes it, or `{problems}`, why the name was refused, in DataCube's words. A page with
 * no cube is shown and followed (D2), never saved (pageSaved): Python's first grid makes it one DataCube saves.
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
  return applied(page, held, commands);
}

/**
 * A PAGE WITH ITS CUBE `cube` AS A GRID'S EDIT LEFT IT (`document`, gridApply's), and its charts following it -- each live
 * chart regrouped, each selection its filter no longer holds taken off -- by the page's own rule (page-commands.ts
 * followedPage: docs/DATACUBE_ONE_API_PLAN_2026_10_21.md V1, V2): what Python's grid edits keep. JSON: `{page}`, the
 * page as DataCube writes it, or `{problems}`, in DataCube's words.
 */
export function pageWithCube(page: string, cube: string, document: string): string {
  try {
    const doc = heldPage(page);
    if (!doc.cubes.some((c) => c.id === cube)) throw new CubeRefusal(`'${cube}' is no grid's cube of the page`);
    return JSON.stringify({ page: pageToJson(followedPage(withCube(doc, cube, readCube(fromJson(document))), cube, doc)) });
  } catch (e) {
    if (!(e instanceof CubeRefusal) && !(e instanceof CubeDocumentError)) throw e;
    return JSON.stringify({ problems: [e.message] });
  }
}

function applied(page: string, held: string, commands: string): string {
  let read;
  try {
    const doc = heldPage(page);
    // a grid's cube read from the commands' exact numbers (a calculated column's 1.10 kept), as the page's cubes are
    const exact = fromJson(commands) as unknown[];
    read = (JSON.parse(commands) as unknown[]).map((c, i) => readPageCommand(exactly(c, exact[i])));
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
/** A page command as read plainly, the field it carries a cube's document or source in taken from its exact reading
 *  (its numbers as the protocol's, as the page's cubes are read: page-commands.ts EXACT_FIELD). */
function exactly(plainly: unknown, exact: unknown): unknown {
  const c = plainly as { kind?: unknown } | null;
  const field = typeof c?.kind === 'string' ? EXACT_FIELD[c.kind as keyof typeof EXACT_FIELD] : undefined;
  return field === undefined ? plainly : { ...(plainly as object), [field]: (exact as Record<string, unknown>)[field] };
}

function heldPage(text: string): PageDocument {
  const { cubes, ...rest } = JSON.parse(text) as Record<string, unknown> & { cubes: { id: string }[] };
  const exact = (fromJson(text) as { cubes: { id: string; cube: unknown }[] }).cubes;
  const { kind, version, name, views, sheets, model, filters, copies, record, ...unknown } = rest;
  return {
    kind, version, name, views, sheets, ...(model !== undefined ? { model } : {}), ...(filters !== undefined ? { filters } : {}),
    // the copies its snapped grids read (§18.8): a field of the page, which its snap and live commands edit
    ...(copies !== undefined ? { copies } : {}),
    // the saved page it is (B13): a field of the page, which the savedAs command edits
    ...(record !== undefined ? { record } : {}),
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

/** A writer's edits read from JSON, each by its own reader: a page command's (a grid's cube with its exact numbers, as
 *  pageApply reads one), or a grid's by readGridCommand -- as the page's keeper reads a write (pageRebase). */
export function readEdits(text: string): PageEdit[] {
  const exact = fromJson(text) as unknown[];
  const asked = JSON.parse(text) as unknown;
  if (!Array.isArray(asked)) throw new CubeRefusal("a writer's edits are a list");
  return asked.map((e, i) => {
    const edit = e as { page?: unknown; grid?: unknown; command?: unknown } | null;
    if (edit !== null && typeof edit === 'object' && 'page' in edit) {
      const c = edit.page as { kind?: unknown } | null;
      const command: PageCommand = readPageCommand(exactly(c, (exact[i] as { page: unknown }).page));
      return { page: command };
    }
    if (edit !== null && typeof edit === 'object' && typeof edit.grid === 'string') {
      const command: GridCommand = readGridCommand(edit.command);
      return { grid: edit.grid, command };
    }
    throw new CubeRefusal(`an edit is {page: <a page command>} or {grid, command}, not ${JSON.stringify(e)}`);
  });
}

/** What a page reads, as its keeper takes it (page-rebase.ts pageReads). */
const readsJson = (page: PageDocument): string => JSON.stringify(readsOf(page));

/**
 * WHAT A PAGE READS (page-rebase.ts pageReads), for its keeper, which never reads a page itself: `page` as Python holds
 * it. JSON: `{frames, copies, ids}` -- the frames its cubes read, the copies its snapped grids read (their tables),
 * every id it holds.
 */
export function pageReads(page: string): string {
  return readsJson(heldPage(page));
}

/**
 * A PAGE WRITTEN WHOLE -- an open page's undo or redo (a restore, docs/DATACUBE_ONE_API_PLAN_2026_10_21.md N2, which its
 * keeper takes only at the version it was made at), the one write that is no commands -- read as DataCube reads a page
 * (readPage): a page of no grid too, as a page written live may be (docs/DATACUBE_PAGE_KEEPER_2026_10_20.md, known limit
 * 5). JSON: `{page, reads}` -- the page as DataCube writes it (its numbers exact), what it reads (pageReads) -- or
 * `{problems}`, why DataCube does not read it, in its words.
 */
export function pageWritten(text: string): string {
  try {
    const page = readPage(text, { empty: true });
    return `{"page":${JSON.stringify(pageToJson(page))},"reads":${readsJson(page)}}`;
  } catch (e) {
    if (!(e instanceof PageDocumentError) && !(e instanceof CubeDocumentError)) throw e;
    return JSON.stringify({ problems: [e.message] });
  }
}

/**
 * A WRITER'S EDITS APPLIED TO THE PAGE AS IT IS NOW (page-rebase.ts rebaseEdits), for the page's keeper, which keeps what
 * a write's commands make (docs/DATACUBE_ONE_API_PLAN_2026_10_21.md S4, H1) -- at the version they were made at there is
 * nothing to combine; at one the page has moved on from, they are made again on top: `page` and `held` as the keeper has
 * them, `edits` as the writer made them (JSON lists), `known` every id the page had held at the version they were made at
 * (a JSON list: a page filter the writer made is told from one it set, H3), `frames` the frames the host serves (grid.ts
 * HostFrame, by name) and `copies` the copies it holds (by table), over which a grid's edit is applied as gridApply
 * applies one -- a snapped grid's over its copy, as the writer applied it. JSON: `{page, held, edits, renamed, reads}`
 * -- the page they make (as DataCube writes it), the ids it has held, the edits as applied there, each id given out
 * afresh (the writer's name -> the page's), and what the page reads -- or `{problems}`: one does not apply, or a cube
 * they add is over a source the host cannot hold (grid.ts onHost), in DataCube's words, and nothing was applied.
 */
export async function pageRebase(page: string, held: string, edits: string, known: string,
  frames: Readonly<Record<string, HostFrame>>, copies: Readonly<Record<string, HeldCopy>>): Promise<string> {
  try {
    const now = heldPage(page);
    const done = await rebaseEdits({ page: now, held: JSON.parse(held) as string[] }, readEdits(edits),
      async (at, cube, id, commands) => {
        const text = cubeToJson(cube);
        // a snapped grid's edit over its copy, as the writer applied it (baseOnHost: the one rule)
        const base = await baseOnHost(text, (at.copies ?? {})[id], frames, copies);
        const said = JSON.parse(await gridApply({ cube: text, ...base } as Parameters<typeof gridApply>[0],
          `[${commands.map((c) => toJson(c)).join(',')}]`)) as { cube?: string; problems?: string[] };
        if (said.problems) throw new CubeRefusal(said.problems.join('; '));
        return readCube(fromJson(said.cube!));
      }, JSON.parse(known) as string[]);
    // what the host holds its cubes over (grid.ts onHost): a cube the write adds, or moves to another source, over one the
    // host cannot base its grid's edits on is refused now, in words -- never kept to refuse its first edit (the S4 audit's
    // 2). A snapped grid reads its copy.
    const was = new Map(now.cubes.map((c) => [c.id, c.cube.source]));
    for (const c of done.page.cubes) {
      if ((done.page.copies ?? {})[c.id] !== undefined) continue;
      if (!sameValue(was.get(c.id), c.cube.source)) onHost(c.cube.source, frames);
    }
    return `{"page":${JSON.stringify(pageToJson(done.page))},"held":${JSON.stringify(done.held)},`
      + `"edits":[${done.edits.map((e) => toJson(e)).join(',')}],"renamed":${JSON.stringify(done.renamed)},`
      + `"reads":${readsJson(done.page)}}`;
  } catch (e) {
    if (!(e instanceof CubeRefusal) && !(e instanceof CubeDocumentError) && !(e instanceof PageDocumentError)) throw e;
    return JSON.stringify({ problems: [e.message] });
  }
}
