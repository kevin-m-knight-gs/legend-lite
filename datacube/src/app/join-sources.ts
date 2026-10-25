// A JOIN'S GRID IN THE TAB (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §17.3, §17.4): a join source opened over the
// tables its leaves read -- the joined grids' own, or, a saved join reopened, each file read again (a table read for a
// grid on the page or beside it taken, a sample rebuilt, else the file asked for) and each remote file mounted -- and
// the grid made of it. What it needs of the page's sources is its host (app/sources.ts); fetched with the first join, so
// a page with no join loads none of it (test/bundle-budget.test.ts).

import {
  inputCube, isLive, leavesOf, rebuilt, sha256, type CubeDocument, type CubeInput, type CubeSource, type FileSource, type JoinInput,
  type JoinSource,
  type LeafSource, type RowsSource,
} from '../cube-document.ts';
import type { ValueSpecification } from '../../../pure-protocol/src/index.ts';
import type { ColumnSpec, CubeSnapshot } from '../snapshot.ts';
import type { FileHandle } from '../file-handles.ts';
import { TAB_TABLES, inputRows, openJoin, openRows, type JoinOpening } from '../join-open.ts';
import type { HeldCopy } from '../app.ts';
import type { SnapTarget } from '../cube.ts';
import type { RelationTyper } from '../source-columns.ts';
import { catalogColumns, tableNameOf } from '../upload.ts';
import type { DatabaseService, GridRows, LocalEngine, Services } from './services.ts';
import type { GridPlace, Opened, SourcesOf } from './sources.ts';

/** What opening a join needs of the page's sources. */
export interface JoinSourcesHost {
  readonly local: LocalEngine;
  readonly database: DatabaseService;
  readonly services: Services;
  /**
   * The table a file is read into already, and that file: a grid's on the page, one read for a grid beside it as a page
   * opens, or one read for another join (`joinRead`).
   */
  readFile(sha256: string): { readonly table: string; readonly file: File; readonly handle?: FileHandle } | undefined;
  /** A saved cube's file got back (sources.ts fileFor): rebuilt, from the handle `page` kept, or asked for. */
  fileFor(src: FileSource, cube: string, page: string | undefined): Promise<{ file: File; handle?: FileHandle } | undefined>;
  /** A table name of the host's database no grid holds, taken; given back with `untake`, or taken as read with `take`. */
  freshTable(base: string): string;
  take(table: string): void;
  untake(table: string): void;
  /** Whether a grid on the page holds the table `table` (a name a page's model keeps is taken only when free). */
  isTaken(table: string): boolean;
  /**
   * The files read in for joins not made yet, by table: the file, where it was picked, its hash, and how many of those
   * joins read it -- held once a grid of one is made (sources.ts madeGrid: then among the page's files, its handle kept
   * when the page is saved), dropped when the last of them is let go unmade.
   */
  readonly joinRead: Map<string, JoinRead>;
  /** When each table a file was read into was read, by the table (sources.ts): a join of copies held here says it. */
  readonly readAt: Map<string, Date>;
  /** The tab's own Snap (sources.ts snapInto): a copy of a grid's base query in this tab's database. */
  snapTarget(): SnapTarget;
  typer(rows: GridRows): RelationTyper;
  letGo(rows: GridRows): void;
  readonly gridMaker: SourcesOf['gridMaker'];
  rowsPlace(rows: GridRows): Pick<GridPlace, 'planner' | 'engine' | 'runner'>;
  /**
   * A grid's source opened as a grid over it opens (sources.ts reopened: a warehouse signed in to, a class at its pin),
   * its maker never made -- its start: the grid's snapshot over its base, and the place it is read through. What a box
   * after it is opened over (openedRows), when its chain reads no table of the tab's.
   */
  openedStart(doc: CubeDocument, several: boolean): Promise<GridStartOpened | undefined>;
}

/** A source opened for a grid that is never made (JoinSourcesHost.openedStart): what opening it said, its snapshot over
 *  its base, and its place. */
export interface GridStartOpened {
  readonly opened: Opened;
  readonly snapshot: CubeSnapshot;
  readonly place: GridPlace;
}

/** A file read in for joins not made yet (JoinSourcesHost.joinRead). */
export interface JoinRead {
  readonly file: File;
  readonly handle?: FileHandle;
  readonly sha256: string;
  /** A file this product generated (cube-document.ts FileSource.sample): no file the person opened. */
  readonly sample: boolean;
  users: number;
}

/**
 * A join's leaf read again, for a saved join reopened: its table, whether this join is one of the joins holding it
 * (`joinRead`), and -- a file given that is not the one the join was saved over -- that, said.
 */
async function leafTable(h: JoinSourcesHost, leaf: LeafSource, name: string, page: string | undefined,
  kept: string | undefined): Promise<{ table: string; held?: boolean; note?: string } | undefined> {
  /** The name the page's model keeps for this leaf's table (its project's store reads it so), when no grid holds it; else
   *  a fresh one. */
  const asked = (base: string): string => {
    if (kept !== undefined && !h.isTaken(kept)) {
      h.take(kept);
      return kept;
    }
    return h.freshTable(base);
  };
  if (leaf._type === 'remoteFile') {
    if (!h.database.mountRemote) throw new Error('this page reads no remote file');
    const view = asked('remote');
    await h.database.mountRemote(leaf.url, undefined, view);
    return { table: view };
  }
  if (leaf._type !== 'file') throw new Error(`a join reads files and remote files here; ${leaf.name} is neither`);
  // the same file read already: its table, read once (one read for a join counts this one among its users) -- under the
  // name the page's model keeps for this leaf, with none kept, or when a grid on screen holds that name (it cannot be
  // taken: said, as below)
  const already = h.readFile(leaf.sha256);
  if (already !== undefined && (kept === undefined || already.table === kept || h.isTaken(kept))) {
    const renamed = kept !== undefined && already.table !== kept
      ? { note: `${already.file.name} is read as ${already.table} in this tab, not ${kept} as the page's classes read it` } : {};
    const read = h.joinRead.get(already.table);
    if (!read) return { table: already.table, ...renamed };
    read.users += 1;
    return { table: already.table, held: true, ...renamed };
  }
  // read under the name kept for it -- the file given already (read under another name: a grid's own beside it), never
  // asked for twice -- else rebuilt, read from where the page kept it, or asked for by its name
  const got = already ?? await h.fileFor(leaf, name, page);
  if (!got) return undefined;
  const given = await sha256(await got.file.arrayBuffer());
  const note = given === leaf.sha256 ? undefined : `${got.file.name} is not the file "${name}" joined (its contents differ)`;
  if (!h.database.ingestFile) throw new Error('this page takes no file');
  const into = asked(tableNameOf(got.file.name));
  const opened = await h.database.ingestFile(got.file, { table: into }).catch((e: unknown) => {
    h.untake(into);
    throw e;
  });
  if (opened.table !== into) {
    h.untake(into);
    h.take(opened.table);
  }
  h.readAt.set(opened.table, new Date());
  h.joinRead.set(opened.table, { file: got.file, ...(got.handle ? { handle: got.handle } : {}), sha256: leaf.sha256, sample: leaf.sample !== undefined, users: 1 });
  // a name the page's classes read it by that it could not take: said, as a file's grid says it
  const renamed = kept !== undefined && opened.table !== kept
    ? `${got.file.name} is read as ${opened.table} in this tab, not ${kept} as the page's classes read it` : undefined;
  const said = [note, renamed].filter((n): n is string => n !== undefined);
  return { table: opened.table, held: true, ...(said.length > 0 ? { note: said.join('; ') } : {}) };
}

/**
 * A join of copies held in the tab, as its plane says it: its files by name, read in by the latest of their read times
 * (its row count is the grid's own to say). A table read at no time the tab knows is a bug of the tab's, refused.
 */
function heldCopyOf(leaves: readonly LeafSource[], tables: readonly string[], readAt: ReadonlyMap<string, Date>): HeldCopy {
  const times = tables.map((t) => {
    const at = readAt.get(t);
    if (at === undefined) throw new Error(`the table ${t} was read in at no time this tab knows`);
    return at.getTime();
  });
  return { label: leaves.map((l) => l.name).join(', '), takenAt: new Date(Math.max(...times)) };
}

/** How a grid over the tab's tables is opened (sources.ts openedJoin's, openedRows's). */
type OpenedHow = {
  readonly saved?: CubeDocument; readonly capped?: boolean; readonly tables?: readonly string[]; readonly page?: string;
  readonly kept?: readonly string[];
};

/** A JOIN FOR A GRID (sources.ts openedJoin's): its model, its relation, its grid; undefined when a file was not given. */
export async function openedJoin(h: JoinSourcesHost, source: JoinSource, how: OpenedHow = {}): Promise<Opened | undefined> {
  return openedOver(h, source, source.name, (o) => openJoin(source, o),
    // the join as it opened: a kept column of theirs gone since is left out of it, as the notes say
    (opened) => ({ ...source, how: opened.how, columns: opened.columns.map((c) => ({ name: c.name, type: c.type })) }), how);
}

/**
 * ANOTHER GRID'S ROWS FOR A GRID (docs/DATACUBE_FLOW_MODE_2026_10_24.md §3.1; sources.ts openedRows's): over the tab's
 * tables its chain reads, read as a join's are -- each file once, a table read for a grid on the page taken -- its
 * input's rows over its input's source in turn (join-open.ts openRows). `resolved`, its source with its link resolved
 * against its page; `source`, as the page keeps it (its link), what the grid writes down, its columns as they opened.
 */
export async function openedRows(h: JoinSourcesHost, source: RowsSource, resolved: RowsSource, how: OpenedHow = {}):
  Promise<Opened | undefined> {
  if (leavesOf(resolved).every((l) => (TAB_TABLES as readonly string[]).includes(l._type))) {
    return openedOver(h, resolved, source.name, (o) => openRows(resolved, o),
      (opened) => ({ ...source, columns: opened.columns.map((c) => ({ name: c.name, type: c.type })) }), how);
  }
  return openedAfterStart(h, source, resolved, how);
}

/**
 * ANOTHER GRID'S ROWS OVER A SOURCE READ ELSEWHERE (a warehouse table, a class, a saved query: no table of the tab's):
 * its chain's first source opened as a grid over it opens (JoinSourcesHost.openedStart), that grid never made; then each
 * box of the chain its input's rows over the one before (join-open.ts inputRows), typed through the place the first is
 * read through, which the grid made is read through too.
 */
async function openedAfterStart(h: JoinSourcesHost, source: RowsSource, resolved: RowsSource, how: OpenedHow):
  Promise<Opened | undefined> {
  // the chain, its first input first
  const inputs: JoinInput[] = [];
  for (let s: CubeSource = resolved; s._type === 'rows';) {
    const input: CubeInput = s.input;
    if (isLive(input)) throw new Error(`${s.name} reads grid '${input.grid}' live: resolve it against its page first`);
    inputs.unshift(input);
    s = input.source;
  }
  const start = await h.openedStart(inputCube(inputs[0]!), true);
  if (!start) return undefined;
  try {
    const typer = start.place.runner ?? start.place.planner;
    if (typer === undefined) throw new Error(`${source.name}: the grid it starts from is read through no planner`);
    const notes = [...start.opened.notes];
    let relation = start.snapshot.source.query;
    let columns: ColumnSpec[] = [];
    for (const input of inputs) {
      const side = await inputRows(input, relation, typer, notes);
      relation = side.relation;
      columns = side.columns.map((c) => ({ name: c.name, type: c.type }));
    }
    const r = rebuilt(how.saved, relation, columns, source.name, how.capped === true);
    // read through what its first source is read through: its planner and engine, live on a warehouse, a copy held here
    const { planner, engine, runner, live, signInAgain, heldCopy, tables } = start.place;
    return {
      label: source.name,
      notes: [...notes, ...r.notes],
      ...(start.opened.discard ? { discard: start.opened.discard } : {}),
      make: h.gridMaker(r.snapshot, r.configuration, [], {
        ...(planner ? { planner } : {}), ...(engine ? { engine } : {}), ...(runner ? { runner } : {}), ...(live ? { live } : {}),
        ...(signInAgain ? { signInAgain } : {}), ...(heldCopy ? { heldCopy } : {}), ...(tables ? { tables } : {}),
        cubeSource: { ...source, columns }, reads: resolved, label: source.name, ...(r.tree ? { tree: r.tree } : {}),
        foldPanel: how.capped === true,
      }),
    };
  } catch (e) {
    await start.opened.discard?.();
    throw e;
  }
}

/**
 * A GRID OVER THE TAB'S TABLES (a join, another grid's rows): `over`'s leaves each a table of the tab's database -- given
 * (`how.tables`: the grids' own), or, a saved grid reopened, each file read again (a table read for a grid on the page
 * or beside it taken, a sample rebuilt, else asked for) and each remote file mounted -- `open` typing what is read over
 * one model of them, `written` what the grid writes down as its source; undefined when a file was not given.
 */
async function openedOver<T extends { readonly relation: ValueSpecification; readonly columns: ColumnSpec[]; readonly notes: readonly string[] }>(
  h: JoinSourcesHost, over: CubeSource, name: string, open: (o: JoinOpening) => Promise<T>, written: (opened: T) => CubeSource,
  how: OpenedHow): Promise<Opened | undefined> {
  const { local } = h;
  /** The tables read in for joins that this one holds. */
  const holding: string[] = [];
  const said: string[] = [];
  /** Its hold on them let go: one no other join holds, and no grid made of one does, is dropped. */
  const forget = async (): Promise<void> => {
    for (const t of holding.splice(0)) {
      const read = h.joinRead.get(t);
      if (!read) continue;
      read.users -= 1;
      if (read.users > 0) continue;
      h.joinRead.delete(t);
      h.readAt.delete(t);
      await h.database.forgetFile?.(read.file.name, t).catch(() => {});
      h.untake(t);
    }
  };
  let rows: GridRows | undefined;
  try {
    let tables = how.tables;
    if (tables === undefined) {
      const read: string[] = [];
      for (const [i, leaf] of leavesOf(over).entries()) {
        const got = await leafTable(h, leaf, how.saved?.name ?? name, how.page, how.kept?.[i]);
        if (!got) {
          await forget();
          return undefined;
        }
        if (got.held) holding.push(got.table);
        if (got.note !== undefined) said.push(got.note);
        read.push(got.table);
      }
      tables = read;
    }
    const opened = await open({
      here: TAB_TABLES,
      tables,
      models: local.tables,
      catalog: (table) => catalogColumns(local.engine, local.tables, table),
      databaseType: local.engine.databaseType,
      // the grid's own rows over the join's model: what types it is what plans it
      typer: (model, runtime) => {
        rows = h.services.engine.rows(model, runtime);
        return h.typer(rows);
      },
    });
    const grid = rows!;
    const cubeSource = written(opened);
    const r = rebuilt(how.saved, opened.relation, opened.columns, name, how.capped === true);
    // where its rows are (§18.3): a remote file is read where it lives, so the join is live and the tab's Snap offered;
    // every leaf a file read into the tab, it is a copy held here from the start, as a file's grid is
    const leaves = leavesOf(over);
    const plane: Pick<GridPlace, 'heldCopy' | 'snapTarget'> = leaves.some((l) => l._type === 'remoteFile')
      ? { snapTarget: h.snapTarget() }
      : { heldCopy: heldCopyOf(leaves, tables, h.readAt) };
    return {
      label: name,
      notes: [...said, ...opened.notes, ...r.notes],
      discard: async () => {
        h.letGo(grid);
        await forget();
      },
      make: h.gridMaker(r.snapshot, r.configuration, [], {
        // what it reads, its links resolved (a box's chain): its leaves are what the page holds for it
        ...h.rowsPlace(grid), ...plane, cubeSource, reads: over, label: name, ...(r.tree ? { tree: r.tree } : {}), tables,
        foldPanel: how.capped === true,
      }),
    };
  } catch (e) {
    if (rows) h.letGo(rows);
    await forget();
    throw e;
  }
}
