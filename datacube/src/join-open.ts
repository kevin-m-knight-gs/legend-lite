// OPENING A JOIN (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §17.3, §17.4): a join source made one relation over one
// model. Its leaves -- the inputs that read a table straight, depth first -- are tables of the host's database; one model
// declares them all (the writer's several tables, infer.ts tablesModel); each input is its cube's rows at their finest
// level over its source (query.ts classLambda), a join inside an input opened the same way; then the two are joined
// (join.ts joinRelation), and the compiler types what comes out. What drifted since it was saved is said. No DOM: the
// tab's sources (app/sources.ts) and a test on Node's DuckDB (test/join-open.test.ts) call it alike.

import type { ValueSpecification } from '../../pure-protocol/src/index.ts';
import { CUBE_KIND, CUBE_VERSION, openCube, type CubeDocument, type CubeSource, type JoinInput, type JoinSource } from './cube-document.ts';
import type { CatalogColumn, TableModels } from './infer.ts';
import { joinRelation, type JoinHow, type JoinSide } from './join.ts';
import { classLambda } from './query.ts';
import { sourceColumns, type RelationTyper } from './source-columns.ts';
import { CubeRefusal, type ColumnSpec } from './snapshot.ts';

/** The sources a join reads a table of the host's database for, depth first: its left input's, then its right's. A source
 *  that is no join is its own one leaf. */
export function joinLeaves(source: CubeSource): CubeSource[] {
  return source._type === 'join' ? [...joinLeaves(source.left.source), ...joinLeaves(source.right.source)] : [source];
}

/**
 * Why a leaf cannot be read where a join is (§17.3: one model, one connection), or undefined: a file or a remote file,
 * each a table of the host's own database. Anything else is read somewhere else.
 */
export function leafRefusal(leaf: CubeSource): string | undefined {
  const where: Record<Exclude<CubeSource['_type'], 'file' | 'remoteFile' | 'join'>, string> = {
    savedQuery: 'a saved query, read through its project',
    warehouseTable: 'a warehouse table, read live on the warehouse',
    frame: "a dataframe, read on Python's database",
    class: "a class, read through its project's mapping",
  };
  if (leaf._type === 'file' || leaf._type === 'remoteFile' || leaf._type === 'join') return undefined;
  return `a join reads both its inputs in one place, this page's own database (files, remote files); ${leaf.name} is ${where[leaf._type]}`;
}

/** What opening a join needs of its host. */
export interface JoinOpening {
  /** The table of the host's database each leaf reads, in joinLeaves' order (a table read twice, named twice). */
  readonly tables: readonly string[];
  /** The host's model writer. */
  readonly models: TableModels;
  /** A table's catalog rows (upload.ts catalogColumns, on the host's database). */
  readonly catalog: (table: string) => Promise<CatalogColumn[]>;
  /** The host database's type, as a connection names it (`DuckDB`). */
  readonly databaseType: string;
  /** What types a relation over a model: a planner over it. */
  readonly typer: (model: string, runtime: string) => RelationTyper;
}

/** A join's two inputs opened over one model: what the Join dialog asks of, before it joins them. */
export interface OpenedInputs {
  readonly model: string;
  readonly runtime: string;
  readonly typer: RelationTyper;
  readonly left: JoinSide;
  readonly right: JoinSide;
  /** What drifted in an input since it was joined, in words, each with the input's name. */
  readonly notes: readonly string[];
}

/** A join opened: its model, its relation, its columns as the compiler types them, its inputs, what drifted. */
export interface OpenedJoin extends OpenedInputs {
  readonly relation: ValueSpecification;
  readonly columns: ColumnSpec[];
  /** How it joins as opened: a kept column of theirs gone since is left out (and said). */
  readonly how: JoinHow;
}

/** An input as a cube document, for openCube: its configuration the product's, nothing open. */
function asCube(input: JoinInput): CubeDocument {
  return {
    kind: CUBE_KIND, version: CUBE_VERSION, name: input.name, source: input.source, query: input.query,
    configuration: {}, tree: { open: [], showTotals: false },
  };
}

/** A join's how with their kept columns that are gone left out, each said; the rest is joinRelation's to judge. */
function reconciled(how: JoinHow, right: JoinSide, notes: string[]): JoinHow {
  if ((how.form !== 'join' && how.form !== 'asOf') || how.keep === null) return how;
  const theirs = new Set(right.columns.map((c) => c.name));
  const gone = how.keep.filter((c) => !theirs.has(c));
  if (gone.length === 0) return how;
  notes.push(`left out ${right.name}'s ${gone.join(', ')} (no longer in it)`);
  return { ...how, keep: how.keep.filter((c) => theirs.has(c)) };
}

/**
 * THE TWO INPUTS OF A JOIN over one model: each leaf's table of the host's database declared once (a table read twice is
 * one table), each input its cube's rows at their finest level over its source -- refused in words when a leaf is read
 * elsewhere (leafRefusal) or an input is pivoted (its columns are its values).
 */
export async function openInputs(left: JoinInput, right: JoinInput, o: JoinOpening): Promise<OpenedInputs> {
  const leaves = [...joinLeaves(left.source), ...joinLeaves(right.source)];
  for (const leaf of leaves) {
    const why = leafRefusal(leaf);
    if (why !== undefined) throw new CubeRefusal(why);
  }
  if (o.tables.length !== leaves.length) {
    throw new Error(`a join of ${leaves.length} tables was given ${o.tables.length} (${o.tables.join(', ')})`);
  }
  const distinct = [...new Set(o.tables)];
  const written = await o.models.tablesModel(await Promise.all(distinct.map(async (table) => ({
    table,
    // as each one's own model reads it: an upload rewritten at ingest converts, a remote file's view cannot
    convertible: leaves[o.tables.indexOf(table)]!._type === 'file',
    columns: await o.catalog(table),
  }))), o.databaseType);
  const relationOf = new Map(distinct.map((t, i) => [t, written.tables[i]!.source]));
  const typer = o.typer(written.model, written.runtime);
  const notes: string[] = [];
  let next = 0;

  const relation = async (s: CubeSource): Promise<ValueSpecification> => {
    if (s._type !== 'join') return relationOf.get(o.tables[next++]!)!;
    const l = await side(s.left);
    const r = await side(s.right);
    return joinRelation(l, r, reconciled(s.how, r, notes));
  };
  const side = async (input: JoinInput): Promise<JoinSide> => {
    const over = await relation(input.source);
    const opened = openCube(asCube(input), { query: over }, await sourceColumns(typer, over));
    for (const n of opened.notes) notes.push(`${input.name}: ${n}`);
    if (opened.snapshot.pivotOn.length > 0) {
      throw new CubeRefusal(`${input.name} is pivoted: its columns are its values, so it joins as no fixed columns; take its pivot off first`);
    }
    const rows = classLambda(opened.snapshot).body[0]!;
    const columns = (await sourceColumns(typer, rows)).map((c) => ({ name: c.name, type: c.type }));
    return { name: input.name, relation: rows, columns };
  };

  const l = await side(left);
  const r = await side(right);
  return { model: written.model, runtime: written.runtime, typer, left: l, right: r, notes };
}

/** A JOIN SOURCE OPENED (§17.4): its inputs over one model (openInputs), joined, typed. */
export async function openJoin(source: JoinSource, o: JoinOpening): Promise<OpenedJoin> {
  const inputs = await openInputs(source.left, source.right, o);
  const notes = [...inputs.notes];
  const how = reconciled(source.how, inputs.right, notes);
  const relation = joinRelation(inputs.left, inputs.right, how);
  const columns = await sourceColumns(inputs.typer, relation);
  return { ...inputs, notes, how, relation, columns };
}
