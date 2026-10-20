// A saved cube: OUR document, versioned (docs/DATACUBE_SAVE_SHARE_2026_09_28.md).
//
// The user's ruling (2026-09-28): a saved cube is the format WE think is right, and a
// legacy DataCube specification is read by a one-way translator later (milestone 2), never
// by bending this one. So this document is the cube's own definition:
//
//   source         where the rows come from, by identity (step 1: a file, by fingerprint)
//   query          the cube: columns, calculated columns, grouping, pivot, filter, sorts,
//                  limits -- read WHOLE, never through an allow-list (the
//                  old saved view dropped every field its reader did not list: P2-71)
//   configuration  only what differs from the product's defaults, so a default changed
//                  later reaches every cube that never touched it
//   tree           the open rows, as typed paths
//
// Three rules, from the saved-view format this replaces:
//  - VERSIONED: a document of another version is refused with a message, a newer one to be
//    upgraded to, an older one as no longer read (the user, 2026-10-17: nothing older is read
//    forward).
//  - UNKNOWN FIELDS ARE KEPT: a field this reader does not know is written back verbatim,
//    so an older client never silently deletes a newer one's settings.
//  - A document that cannot be read fails LOUDLY, saying what was wrong.
//
// Opening a cube RECONCILES it with what its source holds now (the user's ruling on schema
// drift): a new column is available but hidden; a part that uses a column that is gone is
// left out and NAMED; a changed type is reported. Nothing is refused for drift alone.

import {
  ExactNumber,
  ProtocolError,
  fromJson,
  isIdentifier,
  readLambda,
  toJson as protocolJson,
  type ValueSpecification,
} from '../../pure-protocol/src/index.ts';
import { DEFAULT_CONFIGURATION, DEFAULT_MAX_ROWS, REQUIRED_SETTINGS, type CubeConfiguration } from './config.ts';
import {
  CubeRefusal,
  type AggregateFn,
  type ColumnSpec,
  type CubeSnapshot,
  type DerivedColumn,
  type FilterNode,
  type SourceRef,
} from './snapshot.ts';
import { TreeState, parsePathKey, type RowPath } from './tree.ts';
import { readJoinHow, type JoinHow } from './join-how.ts';
import type { UploadFormat } from './upload.ts';
import type { SharedQuery } from '../../query-store/src/share.ts';

/** What this document is, so a file of anything else is refused by name. */
export const CUBE_KIND = 'datacube.cube';
/** Bumped whenever the shape changes in a way a reader must handle. */
/**
 * 2 (2026-10-17): a grid carries no measures list -- its aggregates are its columns' Aggregation, Column Properties
 * (docs/DATACUBE_ONE_GRID_EDIT_PATH_2026_10_17.md). A version 1 cube is not read.
 */
export const CUBE_VERSION = 2;

/**
 * A file the cube was built over, by IDENTITY: the model is derived from the file itself
 * (DuckDB's catalog), so the file is what a reopened cube needs back. The hash tells the
 * same file from a different one with the same name; the columns are what it held.
 */
export interface FileSource {
  readonly _type: 'file';
  readonly name: string;
  readonly format: UploadFormat;
  readonly size: number;
  /** Lower-case hex SHA-256 of the file's bytes. */
  readonly sha256: string;
  readonly columns: readonly { readonly name: string; readonly type: string }[];
  /**
   * A file this product GENERATED (a sample, from a fixed seed): opening rebuilds it, in any
   * browser, and the hash confirms the rebuild is the same data.
   */
  readonly sample?: { readonly id: string; readonly rows: number };
}

/**
 * A SAVED QUERY the cube was built over, by WHAT IT IS -- not where it is stored: its record as a
 * share link carries it (query-store `sharedPart`: project, execution context, content, saved
 * parameter values). Reopening reads it again through the page's model for its project, so the
 * cube opens wherever that project is known, with or without the store it was saved in.
 */
export interface QuerySource {
  readonly _type: 'savedQuery';
  /** The query's name: what a person calls this source. */
  readonly name: string;
  readonly query: SharedQuery;
  /** What it answered when the cube was saved. */
  readonly columns: readonly { readonly name: string; readonly type: string }[];
}

/**
 * A WAREHOUSE TABLE the cube was built over, by where it is: the warehouse's address, the table,
 * the columns it had. Never a sign-in: whoever reopens it signs in as themselves, and the
 * warehouse's grants decide what they may read.
 */
export interface WarehouseSource {
  readonly _type: 'warehouseTable';
  /** `schema.table`: what a person calls this source. */
  readonly name: string;
  /** The warehouse's address, as its sign-in names it. */
  readonly warehouse: string;
  /**
   * The warehouse catalog the table is in (a warehouse serves several: its own DuckDB ones, attached
   * Postgres ones). A document saved before catalogs were named (2026-10-02) has none, and reads as
   * `main`: the only catalog DataCube opened then.
   */
  readonly catalog: string;
  readonly schema: string;
  readonly table: string;
  readonly columns: readonly { readonly name: string; readonly type: string }[];
}

/**
 * A REMOTE FILE the cube was built over (Parquet, CSV or Iceberg by URL), by where it is. Never
 * its keys: a private bucket asks for them again when the cube is reopened.
 */
export interface RemoteSource {
  readonly _type: 'remoteFile';
  /** The file's name, the URL's last part: what a person calls this source. */
  readonly name: string;
  readonly url: string;
  readonly columns: readonly { readonly name: string; readonly type: string }[];
}

/**
 * A DATAFRAME the cube was built over, by its name on the engine that serves it (Python's `ll.Page`;
 * docs/DATACUBE_PYTHON_PAGES_DESIGN_2026_10_09.md): reopened over the frame of that name, by whichever engine serves the
 * page. Never its rows: they are the engine's.
 */
export interface FrameSource {
  readonly _type: 'frame';
  /** The frame's table name on its engine (a plain identifier). */
  readonly name: string;
  readonly columns: readonly { readonly name: string; readonly type: string }[];
}

/**
 * A CLASS of a project in the model home the cube reads (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §16.1: a dashboard's
 * tile), by what it is: the class, the project and the exact revision it is read at (the pin), and the mapping and
 * runtime it runs through -- reopened from that project wherever the model home has it, never a copy of the model.
 */
export interface ClassSource {
  readonly _type: 'class';
  /** The class's name: what a person calls this source. */
  readonly name: string;
  /** The class's path. */
  readonly class: string;
  /** The project (`groupId:artifactId`), the workspace and the revision its files are read at. */
  readonly project: string;
  readonly workspace: string;
  readonly revision: string;
  /** The mapping and the runtime its rows are read through (Create a Class's: the model's, the place's). */
  readonly mapping: string;
  readonly runtime: string;
  /** Its properties as the grid's columns, each under its own name. */
  readonly columns: readonly { readonly name: string; readonly type: string }[];
}

/**
 * A JOIN of two inputs (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §17): each a cube -- a source and its saved query --
 * read as its rows at its finest level (query.ts classLambda, what Create a Class reads), and how they combine
 * (join.ts JoinHow). Kept by value, so a saved join reopens alone: an input is what its grid was when it was joined.
 */
export interface JoinSource {
  readonly _type: 'join';
  /** What a person calls it: `trades ⋈ customers` (join.ts joinTitle). */
  readonly name: string;
  readonly left: JoinInput;
  readonly right: JoinInput;
  readonly how: JoinHow;
  /** What it answered when the cube was saved. */
  readonly columns: readonly { readonly name: string; readonly type: string }[];
}

/** An input of a join: a grid as it was joined -- what it was called, its source, its saved query. */
export interface JoinInput {
  readonly name: string;
  readonly source: CubeSource;
  readonly query: SavedQuery;
}

/** Where a cube's rows come from: a file, a saved query, a warehouse table, a remote file, a dataframe, a class, a join. */
export type CubeSource = FileSource | QuerySource | WarehouseSource | RemoteSource | FrameSource | ClassSource | JoinSource;

/** A source that is no join: where rows are read from (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §18.1). */
export type LeafSource = Exclude<CubeSource, JoinSource>;

/**
 * WHAT A GRID READS (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §18.3): its source's leaves -- a join's, its inputs' in
 * turn, left's then right's; any other source, itself. Every question of what a grid reads (its files, its frames, the
 * tables a join of it reads) is asked of these, never of its source's kind.
 */
export function leavesOf(source: CubeSource): LeafSource[] {
  return source._type === 'join' ? [...leavesOf(source.left.source), ...leavesOf(source.right.source)] : [source];
}

/**
 * The cube's definition: a snapshot without its runtime state (source relation, epoch, row window).
 */
export type SavedQuery = Omit<CubeSnapshot, 'source' | 'epoch' | 'window'>;

export interface CubeDocument {
  readonly kind: typeof CUBE_KIND;
  readonly version: number;
  readonly name: string;
  readonly source: CubeSource;
  readonly query: SavedQuery;
  /** Differences from the product defaults; `null` means "unset" where a default is set. */
  readonly configuration: Readonly<Record<string, unknown>>;
  readonly tree: { readonly open: readonly RowPath[]; readonly showTotals: boolean };
  /** Top-level fields a newer writer added, kept verbatim. */
  readonly unknown?: Readonly<Record<string, unknown>>;
}

export class CubeDocumentError extends Error {
  constructor(detail: string) {
    super(`cannot read saved cube: ${detail}`);
    this.name = 'CubeDocumentError';
  }
}

const KNOWN = new Set(['kind', 'version', 'name', 'source', 'query', 'configuration', 'tree']);

// ---------------------------------------------------------------- writing

export interface WriteOptions {
  readonly name: string;
  readonly source: CubeSource;
  readonly snapshot: CubeSnapshot;
  readonly configuration: CubeConfiguration;
  readonly tree: TreeState;
  /** Fields carried from the document this cube was opened from. */
  readonly unknown?: Readonly<Record<string, unknown>>;
}

export function writeCube(o: WriteOptions): CubeDocument {
  const { source: _relation, epoch: _epoch, window: _window, ...query } = o.snapshot;
  return {
    kind: CUBE_KIND,
    version: CUBE_VERSION,
    name: o.name,
    source: o.source,
    query,
    configuration: (difference(o.configuration, DEFAULT_CONFIGURATION) ?? {}) as Record<string, unknown>,
    tree: treeDocument(o.tree),
    ...(o.unknown && Object.keys(o.unknown).length > 0 ? { unknown: o.unknown } : {}),
  };
}

/** The open rows as a cube document holds them: typed paths, and whether totals show. */
export function treeDocument(tree: TreeState): CubeDocument['tree'] {
  return { open: tree.openPaths.map(parsePathKey), showTotals: tree.showTotals };
}

/**
 * The document as JSON text. Written by the protocol library: a calculated column's
 * lambda carries exact numbers (a decimal's `12.30`, an integer past 2^53) that
 * JSON.stringify cannot write.
 */
export function cubeToJson(doc: CubeDocument): string {
  const { unknown, ...rest } = doc;
  return protocolJson({ ...unknown, ...rest });
}

/**
 * What makes two cubes "the same" for "changed since saved": the definition and the settings.
 * Not the name (renaming is saving), not the open rows (looking is not changing).
 */
export function definitionText(doc: CubeDocument): string {
  return protocolJson({ query: doc.query, configuration: doc.configuration });
}

// ---------------------------------------------------------------- reading

export function readCube(input: string | unknown): CubeDocument {
  let raw: unknown = input;
  if (typeof input === 'string') {
    try {
      raw = fromJson(input);
    } catch (e) {
      throw new CubeDocumentError(`not valid JSON (${e instanceof Error ? e.message : String(e)})`);
    }
  }
  if (!isObject(raw)) throw new CubeDocumentError('expected an object');
  if (raw['kind'] !== CUBE_KIND) {
    // Said by name: a legacy DataCube specification (upstream's saved cube) is opened by the
    // translator of milestone 2, not read as one of ours.
    if (typeof raw['query'] === 'string' && 'source' in raw) {
      throw new CubeDocumentError('this is a legacy DataCube specification; opening those is not built yet');
    }
    throw new CubeDocumentError(`not a saved cube (kind ${JSON.stringify(plain(raw['kind']) ?? null)})`);
  }
  const version = numberOf(raw['version']);
  if (version === undefined || version < 1) throw new CubeDocumentError('it has no version');
  if (version > CUBE_VERSION) {
    throw new CubeDocumentError(`written by a newer version (${version} > ${CUBE_VERSION}); upgrade to open it`);
  }
  if (version < CUBE_VERSION) {
    throw new CubeDocumentError(`saved by an older version (${version} < ${CUBE_VERSION}), before a grid's aggregates were `
      + "its columns' Aggregation: it is no longer read");
  }
  if (typeof raw['name'] !== 'string') throw new CubeDocumentError("missing 'name'");

  const unknown: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(raw)) if (!KNOWN.has(k)) unknown[k] = plain(v);

  const configuration = plain(raw['configuration'] ?? {});
  if (!isObject(configuration)) throw new CubeDocumentError("'configuration' is not an object");
  // a setting every grid has is never unset: no writer writes its null (a cleared one is its default again)
  const unset = Object.keys(REQUIRED_SETTINGS).find((k) => configuration[k] === null);
  if (unset !== undefined) throw new CubeDocumentError(`'configuration.${unset}' is null: every grid has that setting`);

  return {
    kind: CUBE_KIND,
    version: CUBE_VERSION,
    name: raw['name'],
    source: readSource(raw['source']),
    query: readQuery(raw['query']),
    configuration,
    tree: readTree(raw['tree']),
    ...(Object.keys(unknown).length > 0 ? { unknown } : {}),
  };
}

function readSource(raw: unknown): CubeSource {
  // a join's condition may be a lambda, read exactly (its numbers as the protocol's): read before made plain
  if (isObject(raw) && raw['_type'] === 'join') return readJoinSource(raw);
  const s = plain(raw);
  if (!isObject(s)) throw new CubeDocumentError("missing 'source'");
  if (s['_type'] === 'savedQuery') return readQuerySource(s);
  if (s['_type'] === 'warehouseTable') return readWarehouseSource(s);
  if (s['_type'] === 'remoteFile') return readRemoteSource(s);
  if (s['_type'] === 'frame') return readFrameSource(s);
  if (s['_type'] === 'class') return readClassSource(s);
  if (s['_type'] !== 'file') {
    throw new CubeDocumentError(`a source of kind ${JSON.stringify(s['_type'] ?? null)} is not supported yet`);
  }
  const columns = s['columns'];
  if (typeof s['name'] !== 'string' || typeof s['format'] !== 'string'
    || typeof s['size'] !== 'number' || typeof s['sha256'] !== 'string'
    || !Array.isArray(columns)
    || !columns.every((c) => isObject(c) && typeof c['name'] === 'string' && typeof c['type'] === 'string')) {
    throw new CubeDocumentError('the file source is incomplete (name, format, size, sha256, columns)');
  }
  const sample = s['sample'];
  if (sample !== undefined
    && !(isObject(sample) && typeof sample['id'] === 'string' && typeof sample['rows'] === 'number')) {
    throw new CubeDocumentError('the sample source is incomplete (id, rows)');
  }
  if (!['csv', 'parquet', 'json'].includes(s['format'])) {
    throw new CubeDocumentError(`an unknown file format ${JSON.stringify(s['format'])}`);
  }
  return s as unknown as FileSource;
}

/** The columns a source had: each a name and a type. */
function hasColumns(s: Record<string, unknown>): boolean {
  const columns = s['columns'];
  return Array.isArray(columns)
    && columns.every((c) => isObject(c) && typeof c['name'] === 'string' && typeof c['type'] === 'string');
}

function readWarehouseSource(s: Record<string, unknown>): WarehouseSource {
  for (const f of ['name', 'warehouse', 'schema', 'table']) {
    if (typeof s[f] !== 'string' || s[f] === '') throw new CubeDocumentError(`the warehouse table source has no ${f}`);
  }
  if (!hasColumns(s)) throw new CubeDocumentError('the warehouse table source has no columns');
  if ('password' in s || 'token' in s) throw new CubeDocumentError('the warehouse table source carries a credential: it is refused');
  if (typeof s['catalog'] !== 'string' || s['catalog'] === '') {
    throw new CubeDocumentError('the warehouse table source has no catalog');
  }
  return s as unknown as WarehouseSource;
}

function readRemoteSource(s: Record<string, unknown>): RemoteSource {
  for (const f of ['name', 'url']) {
    if (typeof s[f] !== 'string' || s[f] === '') throw new CubeDocumentError(`the remote file source has no ${f}`);
  }
  if (!hasColumns(s)) throw new CubeDocumentError('the remote file source has no columns');
  if ('secretAccessKey' in s || 'secret' in s) throw new CubeDocumentError('the remote file source carries a credential: it is refused');
  return s as unknown as RemoteSource;
}

function readFrameSource(s: Record<string, unknown>): FrameSource {
  if (typeof s['name'] !== 'string' || !isIdentifier(s['name'])) {
    throw new CubeDocumentError('the frame source has no name (a plain identifier: letters, digits, \'_\', not true or false)');
  }
  if (!hasColumns(s)) throw new CubeDocumentError('the frame source has no columns');
  return s as unknown as FrameSource;
}

function readClassSource(s: Record<string, unknown>): ClassSource {
  for (const f of ['name', 'class', 'project', 'workspace', 'revision', 'mapping', 'runtime']) {
    if (typeof s[f] !== 'string' || s[f] === '') throw new CubeDocumentError(`the class source has no ${f}`);
  }
  if (!hasColumns(s)) throw new CubeDocumentError('the class source has no columns');
  // a column of a class source is a property, read as `$x.p`: a name the grammar reads bare
  const bad = (s['columns'] as { name: string }[]).find((c) => !isIdentifier(c.name));
  if (bad !== undefined) throw new CubeDocumentError(`the class source's column '${bad.name}' is no property's name`);
  return s as unknown as ClassSource;
}

function readJoinSource(s: Record<string, unknown>): JoinSource {
  if (typeof s['name'] !== 'string' || s['name'] === '') throw new CubeDocumentError('the join source has no name');
  const columns = plain(s['columns']);
  if (!hasColumns({ columns })) throw new CubeDocumentError('the join source has no columns');
  const input = (which: 'left' | 'right'): JoinInput => {
    const i = s[which];
    if (!isObject(i)) throw new CubeDocumentError(`the join source has no ${which} input`);
    if (typeof i['name'] !== 'string' || i['name'] === '') throw new CubeDocumentError(`the join source's ${which} input has no name`);
    return { name: i['name'], source: readSource(i['source']), query: readQuery(i['query']) };
  };
  let how: JoinHow;
  try {
    how = readJoinHow(s['how']);
  } catch (e) {
    if (e instanceof CubeRefusal) throw new CubeDocumentError(`the join source: ${e.message}`);
    throw e;
  }
  return { _type: 'join', name: s['name'], left: input('left'), right: input('right'), how, columns: columns as JoinSource['columns'] };
}

function readQuerySource(s: Record<string, unknown>): QuerySource {
  const q = s['query'];
  const columns = s['columns'];
  if (typeof s['name'] !== 'string' || !isObject(q) || !Array.isArray(columns)
    || !columns.every((c) => isObject(c) && typeof c['name'] === 'string' && typeof c['type'] === 'string')) {
    throw new CubeDocumentError('the saved query source is incomplete (name, query, columns)');
  }
  for (const f of ['name', 'groupId', 'artifactId', 'versionId', 'content']) {
    if (typeof q[f] !== 'string' || q[f] === '') throw new CubeDocumentError(`the saved query source has no ${f}`);
  }
  return s as unknown as QuerySource;
}

/**
 * The cube, read WHOLE: every field as plain JSON, then the calculated columns' lambdas read
 * exactly. No allow-list -- a field this version does not know is carried, not dropped.
 */
function readQuery(raw: unknown): SavedQuery {
  if (!isObject(raw)) throw new CubeDocumentError("missing 'query'");
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(raw)) {
    if (k === 'derived' || k === 'groupDerived') continue;
    out[k] = plain(v);
  }
  for (const k of ['columns', 'rows', 'pivotOn', 'sorts']) {
    if (!Array.isArray(out[k])) throw new CubeDocumentError(`'query.${k}' is not a list`);
  }
  if ('measures' in out) {
    throw new CubeDocumentError("'query.measures': a grid carries no measures list, its aggregates are its columns' Aggregation");
  }
  out['derived'] = calculated(raw['derived'], 'derived');
  if (raw['groupDerived'] !== undefined) out['groupDerived'] = calculated(raw['groupDerived'], 'groupDerived');
  return out as unknown as SavedQuery;
}

/** Calculated columns, each lambda read exactly (its numbers as the protocol's exact numbers). */
function calculated(raw: unknown, field: string): DerivedColumn[] {
  if (raw === undefined) return [];
  if (!Array.isArray(raw)) throw new CubeDocumentError(`'query.${field}' is not a list`);
  return raw.map((item) => {
    if (!isObject(item)) throw new CubeDocumentError(`an entry of 'query.${field}' is not an object`);
    const { lambda, ...rest } = item;
    const fields = plain(rest) as Omit<DerivedColumn, 'lambda'>;
    if (lambda === undefined) return fields;
    try {
      return { ...fields, lambda: readLambda(lambda) };
    } catch (e) {
      // the protocol's refusal, said as the document's: a page that cannot be read fails as one, in its words
      if (!(e instanceof ProtocolError)) throw e;
      throw new CubeDocumentError(`calculated column ${JSON.stringify(fields.name ?? null)}'s lambda: ${e.message}`);
    }
  });
}

function readTree(raw: unknown): CubeDocument['tree'] {
  const t = plain(raw ?? { open: [], showTotals: false });
  if (!isObject(t) || !Array.isArray(t['open'])) throw new CubeDocumentError("'tree' has no open paths");
  const open = t['open'].map((p) => {
    if (!Array.isArray(p) || !p.every((k) => k === null || typeof k === 'string')) {
      throw new CubeDocumentError(`not a row path: ${JSON.stringify(p)}`);
    }
    return p as RowPath;
  });
  return { open, showTotals: t['showTotals'] === true };
}

// ---------------------------------------------------------------- opening

export interface OpenedCube {
  readonly snapshot: CubeSnapshot;
  readonly configuration: CubeConfiguration;
  readonly tree: TreeState;
  /** What drifted, in words, for the person opening it. Empty when nothing did. */
  readonly notes: readonly string[];
  /** True when a part of the cube was left out: the cube on screen is not the one saved. */
  readonly changed: boolean;
}

/**
 * The cube over its source AS IT IS NOW. `relation` is the re-ingested source's relation and
 * `columns` its columns as the COMPILER types them (sourceColumns): the saved column list
 * is only a record of what the file held, used to say what changed.
 */
export function openCube(
  doc: CubeDocument,
  relation: SourceRef,
  columns: readonly ColumnSpec[],
): OpenedCube {
  const notes: string[] = [];
  const q = doc.query;
  const saved = new Map(q.columns.map((c) => [c.name, c]));
  const now = new Map(columns.map((c) => [c.name, c]));
  let configuration = merge(DEFAULT_CONFIGURATION, doc.configuration) as CubeConfiguration;

  // New columns: available, not shown.
  const added = columns.filter((c) => !saved.has(c.name)).map((c) => c.name);
  if (added.length > 0) {
    notes.push(`${plural(added.length, 'new column')} since this cube was saved, hidden: ${added.join(', ')}`);
    const cols = { ...configuration.columns };
    for (const name of added) cols[name] = { ...cols[name], hidden: true };
    configuration = { ...configuration, columns: cols };
  }
  // Retyped columns: the compiler's type wins; said out loud.
  for (const c of columns) {
    const was = saved.get(c.name);
    if (was && was.type !== c.type) notes.push(`${c.name} is now ${c.type} (it was ${was.type})`);
  }
  // Gone columns, and every calculated column that reads one (in order: one may read another).
  const gone = new Set(q.columns.filter((c) => !now.has(c.name)).map((c) => c.name));
  if (gone.size > 0) notes.push(`${plural(gone.size, 'column')} no longer in the file: ${[...gone].join(', ')}`);
  const keepDerived = (list: readonly DerivedColumn[]): DerivedColumn[] => list.filter((d) => {
    const lost = [...derivedReads(d)].filter((n) => gone.has(n));
    if (lost.length === 0) return true;
    notes.push(`left out calculated column ${d.name} (it uses ${lost.join(', ')})`);
    gone.add(d.name);
    return false;
  });
  const kept = keepDerived(q.derived);
  const keptGroup = q.groupDerived === undefined ? undefined : keepDerived(q.groupDerived);

  // A weighted average weighted by a gone column: its Aggregation left out (the column takes its kind's own) and
  // named -- in the configuration and on the query both, as fromSnapshot reads the query's back.
  const weightGone = new Map<string, string>();
  for (const [name, c] of Object.entries(configuration.columns)) {
    const weight = c.aggregationParameters?.[0];
    if (c.aggregateFn === 'wavg' && weight !== undefined && gone.has(weight) && !gone.has(name)) weightGone.set(name, weight);
  }
  for (const c of [...q.columns, ...kept, ...(keptGroup ?? [])]) {
    const weight = c.aggregateWeight;
    if (c.aggregate === 'wavg' && weight !== undefined && gone.has(weight) && !gone.has(c.name)) weightGone.set(c.name, weight);
  }
  if (weightGone.size > 0) {
    const cols = { ...configuration.columns };
    for (const [name, weight] of weightGone) {
      notes.push(`left out ${name}'s weighted average (it is weighted by ${weight})`);
      const { aggregateFn: _fn, aggregationParameters: _weight, ...rest } = cols[name] ?? {};
      cols[name] = rest;
    }
    configuration = { ...configuration, columns: cols };
  }
  const unweighted = <T extends { name: string; aggregate?: AggregateFn; aggregateWeight?: string }>(c: T): T => {
    if (!weightGone.has(c.name)) return c;
    const { aggregate: _a, aggregateWeight: _w, ...rest } = c;
    return rest as T;
  };
  const derived = kept.map(unweighted);
  const groupDerived = keptGroup?.map(unweighted);

  const using = (what: string) => (name: string): boolean => {
    if (!gone.has(name)) return true;
    notes.push(`left out ${what} ${name}`);
    return false;
  };
  const rows = q.rows.filter(using('grouping by'));
  const pivotOn = q.pivotOn.filter(using('pivoting on'));
  const sorts = q.sorts.filter((s) => using('sorting by')(s.column));
  const filter = q.filter === undefined ? undefined : pruneFilter(q.filter, gone, notes);

  // The source columns: the compiler's, carrying what the cube had set on each.
  const specs: ColumnSpec[] = columns.map((c) => {
    const was = saved.get(c.name);
    if (!was) return c;
    const { name: _n, type: _t, ...settings } = unweighted(was);
    return { ...c, ...settings, name: c.name, type: c.type };
  });

  const shapeChanged = groupingChanged(q, { rows, pivotOn });
  const {
    columns: _c, derived: _d, groupDerived: _g, rows: _r, pivotOn: _p, sorts: _s,
    filter: _f, pivotValues, ...rest
  } = q;
  const snapshot: CubeSnapshot = {
    ...rest,
    source: relation,
    columns: specs,
    derived,
    ...(groupDerived !== undefined ? { groupDerived } : {}),
    rows,
    pivotOn,
    // pinned pivot values belong to the keys they were pinned on
    ...(pivotValues !== undefined && pivotOn.length === q.pivotOn.length ? { pivotValues } : {}),
    sorts,
    ...(filter !== undefined ? { filter } : {}),
    epoch: 0,
  };
  // The open rows are paths through the saved grouping; a different grouping has none.
  const tree = TreeState.fromPaths(shapeChanged ? [] : doc.tree.open, doc.tree.showTotals);
  // changed: a part left out -- the cube held is not the one saved
  const changed = notes.some((n) => n.startsWith('left out'));
  return { snapshot, configuration, tree, notes, changed };
}

/**
 * Whether a cube's grouping changed -- its row groups or its pivot, by name and order. Its open rows are paths through
 * the grouping it had, so a changed one has none: a cube reopened over a source that lost a grouping column (openCube).
 */
export function groupingChanged(
  was: Pick<CubeSnapshot, 'rows' | 'pivotOn'>, now: Pick<CubeSnapshot, 'rows' | 'pivotOn'>,
): boolean {
  const same = (a: readonly string[], b: readonly string[]) => a.length === b.length && a.every((n, i) => n === b[i]);
  return !same(was.rows, now.rows) || !same(was.pivotOn, now.pivotOn);
}

/** A source's rows as they are: grouped by nothing, up to the row cap (a person builds the cube up). */
export function rawRows(query: ValueSpecification, columns: CubeSnapshot['columns']): CubeSnapshot {
  return { source: { query }, columns, derived: [], rows: [], pivotOn: [], sorts: [], epoch: 1 };
}

/**
 * WHETHER A NEW GRID'S ROWS ARE CAPPED at the row limit: when its page holds more than one grid, counting it
 * (`gridsOnPage`) -- a page's only grid shows its rows, one beside others its first rows (the user, 2026-10-01: "showing
 * the max rows configured (1000 default)"). The one rule: a source added from the picker, a saved page reopened, a
 * host's grid made headless (headless/grid.ts gridCube) each ask it.
 */
export function gridCapped(gridsOnPage: number): boolean {
  return gridsOnPage > 1;
}

/**
 * A saved cube rebuilt over a source (`relation`, its `columns` as the compiler types them now) -- its view, its
 * configuration, the groups it had open, what it had that the source no longer has (`changed`: a part left out, as
 * openCube says) -- or a fresh one: its rows as they are, grouped by nothing; `capped` (gridCapped: a grid beside
 * others), up to the row limit. Moved here from app/sources.ts (2026-10-15) so a host's own pages make their grids by it
 * too, headless (docs/PYTHON_ASKS_DATACUBE_2026_10_15.md, item 2).
 */
export function rebuilt(saved: CubeDocument | undefined, relation: ValueSpecification, columns: CubeSnapshot['columns'],
  label: string, capped: boolean,
): {
  snapshot: CubeSnapshot; configuration: CubeConfiguration; tree?: TreeState; notes: readonly string[]; changed: boolean;
} {
  if (!saved) {
    return {
      snapshot: rawRows(relation, columns),
      configuration: { ...DEFAULT_CONFIGURATION, reportTitle: label, ...(capped ? { maxRows: DEFAULT_MAX_ROWS } : {}) },
      notes: [],
      changed: false,
    };
  }
  const cube = openCube(saved, { query: relation }, columns);
  return {
    snapshot: cube.snapshot, configuration: cube.configuration, ...(cube.tree ? { tree: cube.tree } : {}),
    notes: cube.notes, changed: cube.changed,
  };
}

/** The source and calculated columns a calculated column reads. */
export function derivedReads(d: DerivedColumn): Set<string> {
  const out = new Set<string>();
  if (d.lambda) {
    const params = new Set(d.lambda.parameters.map((p) => p.name));
    walk(d.lambda.body, (node) => {
      if (node['_type'] === 'property' && typeof node['property'] === 'string') {
        const receiver = (node['parameters'] as unknown[] | undefined)?.[0];
        if (isObject(receiver) && receiver['_type'] === 'var' && params.has(receiver['name'] as string)) {
          out.add(node['property']);
        }
      }
    });
  }
  if (d.window) {
    if (d.window.column !== undefined) out.add(d.window.column);
    d.window.partition.forEach((c) => out.add(c));
    d.window.order.forEach((s) => out.add(s.column));
  }
  if (d.childAggregate) out.add(d.childAggregate.of);
  return out;
}

function walk(node: unknown, visit: (n: Record<string, unknown>) => void): void {
  if (Array.isArray(node)) {
    node.forEach((n) => walk(n, visit));
  } else if (isObject(node) && !(node instanceof ExactNumber)) {
    visit(node);
    Object.values(node).forEach((v) => walk(v, visit));
  }
}

/** A filter with every condition on a gone column left out (and named), or undefined if none remain. */
function pruneFilter(node: FilterNode, gone: ReadonlySet<string>, notes: string[]): FilterNode | undefined {
  switch (node.kind) {
    case 'condition': {
      const lost = [node.column, node.rightColumn].filter((n): n is string => n !== undefined && gone.has(n));
      if (lost.length === 0) return node;
      notes.push(`left out the filter on ${lost.join(', ')}`);
      return undefined;
    }
    case 'not': {
      const child = pruneFilter(node.child, gone, notes);
      return child === undefined ? undefined : { kind: 'not', child };
    }
    default: {
      const children = node.children
        .map((c) => pruneFilter(c, gone, notes))
        .filter((c): c is FilterNode => c !== undefined);
      if (children.length === 0) return undefined;
      return children.length === 1 ? children[0] : { kind: node.kind, children };
    }
  }
}

// ---------------------------------------------------------------- the file source

/** A dataframe's source as a page records it (Python's ll.Page): its name on the engine that serves it, and its columns
 *  as the compiler typed them when the cube was opened over it. */
export function frameSource(
  name: string, columns: readonly { readonly name: string; readonly type: string }[],
): FrameSource {
  return { _type: 'frame', name, columns: columns.map((c) => ({ name: c.name, type: c.type })) };
}

/** A file's identity: its name, format, size and SHA-256, and the columns it held. */
export async function fileSource(
  file: { readonly name: string; readonly size: number; arrayBuffer(): Promise<ArrayBuffer> },
  format: UploadFormat,
  columns: readonly ColumnSpec[],
  sample?: { readonly id: string; readonly rows: number },
): Promise<FileSource> {
  return {
    _type: 'file',
    name: file.name,
    format,
    size: file.size,
    sha256: await sha256(await file.arrayBuffer()),
    columns: columns.map((c) => ({ name: c.name, type: c.type })),
    ...(sample ? { sample } : {}),
  };
}

export async function sha256(bytes: ArrayBuffer): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** The saved configuration over the product defaults. */
export function configurationOf(doc: CubeDocument): CubeConfiguration {
  return merge(DEFAULT_CONFIGURATION, doc.configuration) as CubeConfiguration;
}

/** The open rows a document describes. */
export function treeOf(doc: CubeDocument): TreeState {
  return TreeState.fromPaths(doc.tree.open, doc.tree.showTotals);
}

// ---------------------------------------------------------------- differences

/**
 * What `value` changes of `base`, recursively: plain objects by key, anything else whole.
 * A key the base sets and the value does not is recorded as null ("unset"). Undefined when
 * nothing differs.
 */
export function difference(value: unknown, base: unknown): unknown {
  if (sameJson(value, base)) return undefined;
  if (isPlain(value) && isPlain(base)) {
    const out: Record<string, unknown> = {};
    for (const k of new Set([...Object.keys(value), ...Object.keys(base)])) {
      const v = value[k];
      if (v === undefined) {
        if (base[k] !== undefined) out[k] = null;
        continue;
      }
      const d = base[k] === undefined ? v : difference(v, base[k]);
      if (d !== undefined) out[k] = d;
    }
    return Object.keys(out).length > 0 ? out : undefined;
  }
  return value;
}

/** `base` with `patch` laid over it: the inverse of {@link difference}. */
export function merge(base: unknown, patch: unknown): unknown {
  if (!isPlain(patch)) return patch;
  const out: Record<string, unknown> = isPlain(base) ? { ...base } : {};
  for (const [k, v] of Object.entries(patch)) {
    if (v === null) delete out[k];
    else out[k] = merge(out[k], v);
  }
  return out;
}

function sameJson(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((x, i) => sameJson(x, b[i]));
  if (isPlain(a) && isPlain(b)) {
    const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
    return [...keys].every((k) => sameJson(a[k], b[k]));
  }
  return false;
}

// ---------------------------------------------------------------- helpers

function isObject(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

function isPlain(v: unknown): v is Record<string, unknown> {
  return isObject(v) && !(v instanceof ExactNumber);
}

function numberOf(v: unknown): number | undefined {
  if (v instanceof ExactNumber) return Number(v.text);
  return typeof v === 'number' ? v : undefined;
}

/** A value outside the trees as plain JSON: its exact numbers as numbers. */
function plain(v: unknown): unknown {
  if (v instanceof ExactNumber) return Number(v.text);
  if (Array.isArray(v)) return v.map(plain);
  if (isObject(v)) return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, plain(x)]));
  return v;
}

function plural(n: number, word: string): string {
  return `${n} ${word}${n === 1 ? '' : 's'}`;
}
