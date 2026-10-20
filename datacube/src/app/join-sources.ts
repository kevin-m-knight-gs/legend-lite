// A JOIN'S GRID IN THE TAB (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §17.3, §17.4): a join source opened over the
// tables its leaves read -- the joined grids' own, or, a saved join reopened, each file read again (a table read for a
// grid on the page or beside it taken, a sample rebuilt, else the file asked for) and each remote file mounted -- and
// the grid made of it. What it needs of the page's sources is its host (app/sources.ts); fetched with the first join, so
// a page with no join loads none of it (test/bundle-budget.test.ts).

import { rebuilt, type CubeDocument, type CubeSource, type FileSource, type JoinSource } from '../cube-document.ts';
import type { FileHandle } from '../file-handles.ts';
import { TAB_TABLES, joinLeaves, openJoin } from '../join-open.ts';
import type { RelationTyper } from '../source-columns.ts';
import { catalogColumns, tableNameOf } from '../upload.ts';
import type { DatabaseService, GridRows, LocalEngine, Services } from './services.ts';
import type { GridPlace, Opened, SourcesOf } from './sources.ts';

/** What opening a join needs of the page's sources. */
export interface JoinSourcesHost {
  readonly local: LocalEngine;
  readonly database: DatabaseService;
  readonly services: Services;
  /** The table a file is read into already: a grid's on the page, or one read for a grid beside it as a page opens. */
  tableOfFile(sha256: string): string | undefined;
  /** A saved cube's file got back (sources.ts fileFor): rebuilt, from a kept handle, or asked for. */
  fileFor(src: FileSource, cube: string, keys: readonly string[]): Promise<{ file: File; handle?: FileHandle } | undefined>;
  /** A table name of the host's database no grid holds, taken; given back with `untake`, or taken as read with `take`. */
  freshTable(base: string): string;
  take(table: string): void;
  untake(table: string): void;
  /** The files read in for a join, by table: held once its grid is made (sources.ts madeGrid), dropped if none is. */
  readonly joinRead: Map<string, string>;
  typer(rows: GridRows): RelationTyper;
  letGo(rows: GridRows): void;
  readonly gridMaker: SourcesOf['gridMaker'];
  rowsPlace(rows: GridRows): Pick<GridPlace, 'planner' | 'engine' | 'runner'>;
}

/** A join's leaf read again, for a saved join reopened: its table, and whether it was read in for this join alone. */
async function leafTable(h: JoinSourcesHost, leaf: CubeSource, name: string, keys: readonly string[]): Promise<{ table: string; ingested?: string } | undefined> {
  if (leaf._type === 'remoteFile') {
    if (!h.database.mountRemote) throw new Error('this page reads no remote file');
    const view = h.freshTable('remote');
    await h.database.mountRemote(leaf.url, undefined, view);
    return { table: view };
  }
  if (leaf._type !== 'file') throw new Error(`a join reads files and remote files here; ${leaf.name} is neither`);
  // the same file read already: its table, read once
  const already = h.tableOfFile(leaf.sha256);
  if (already !== undefined) return { table: already };
  const got = await h.fileFor(leaf, name, keys);
  if (!got) return undefined;
  if (!h.database.ingestFile) throw new Error('this page takes no file');
  const asked = h.freshTable(tableNameOf(got.file.name));
  const opened = await h.database.ingestFile(got.file, { table: asked }).catch((e: unknown) => {
    h.untake(asked);
    throw e;
  });
  if (opened.table !== asked) {
    h.untake(asked);
    h.take(opened.table);
  }
  h.joinRead.set(opened.table, got.file.name);
  return { table: opened.table, ingested: opened.table };
}

/** A JOIN FOR A GRID (sources.ts openedJoin's): its model, its relation, its grid; undefined when a file was not given. */
export async function openedJoin(h: JoinSourcesHost, source: JoinSource, how: { readonly saved?: CubeDocument; readonly capped?: boolean;
  readonly tables?: readonly string[]; readonly keys?: readonly string[] } = {}): Promise<Opened | undefined> {
  const { local } = h;
  const ingested: string[] = [];
  /** The tables read for it alone, let go -- unless a grid made of it holds them by now. */
  const forget = async (): Promise<void> => {
    for (const t of ingested) {
      const fileName = h.joinRead.get(t);
      if (fileName === undefined) continue;
      h.joinRead.delete(t);
      await h.database.forgetFile?.(fileName, t).catch(() => {});
      h.untake(t);
    }
  };
  let rows: GridRows | undefined;
  try {
    let tables = how.tables;
    if (tables === undefined) {
      const read: string[] = [];
      for (const leaf of joinLeaves(source)) {
        const got = await leafTable(h, leaf, how.saved?.name ?? source.name, how.keys ?? []);
        if (!got) {
          await forget();
          return undefined;
        }
        if (got.ingested !== undefined) ingested.push(got.ingested);
        read.push(got.table);
      }
      tables = read;
    }
    const opened = await openJoin(source, {
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
    const columns = opened.columns.map((c) => ({ name: c.name, type: c.type }));
    // the join as it opened: a kept column of theirs gone since is left out of it, as the notes say
    const cubeSource: JoinSource = { ...source, how: opened.how, columns };
    const r = rebuilt(how.saved, opened.relation, opened.columns, source.name, how.capped === true);
    return {
      label: source.name,
      notes: [...opened.notes, ...r.notes],
      discard: async () => {
        h.letGo(grid);
        await forget();
      },
      make: h.gridMaker(r.snapshot, r.configuration, [], {
        ...h.rowsPlace(grid), cubeSource, label: source.name, ...(r.tree ? { tree: r.tree } : {}), tables, foldPanel: how.capped === true,
      }),
    };
  } catch (e) {
    if (rows) h.letGo(rows);
    await forget();
    throw e;
  }
}
