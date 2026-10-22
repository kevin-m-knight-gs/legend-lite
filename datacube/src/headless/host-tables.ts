// WHAT A HOST SERVES A PAGE'S CUBES OVER, headless (docs/DATACUBE_ONE_API_PLAN_2026_10_21.md B7, X1, X3, D14): the
// tables of its database a cube's leaves are read from -- its frames, the files it read in, the remote files it mounted
// -- and the one rule of where each leaf is read, which every grid call's base, a join's tables and the page keeper's
// rebase ask. No DOM, no window.

import { leavesOf, type CubeSource, type JoinSource, type LeafSource } from '../cube-document.ts';
import { CubeRefusal } from '../snapshot.ts';

/** A frame as its host serves it (Python's Frames table): its model, its runtime and the accessor that reads it. */
export interface HostFrame {
  readonly model: string;
  readonly runtime: string;
  readonly accessor: string;
}

/** A grid's base on the host, as every grid call is given it (GridSpec without its cube): its model and runtime, and the
 *  relation it reads -- Pure text (a frame's accessor) or a lambda as the protocol writes it. */
export type HostBase = { readonly model: string; readonly runtime: string } & ({ readonly source: string } | { readonly relation: string });

/** The file a host read in as a table, as a cube records it: a file's name, size and bytes' hash; a remote file's URL. */
export type HostLeaf =
  | { readonly _type: 'file'; readonly name: string; readonly size: number; readonly sha256: string }
  | { readonly _type: 'remoteFile'; readonly name: string; readonly url: string };

/** A table of the host's database a page's file was read into, or a remote file is read through (Python's Frames'
 *  files): its model and runtime, the relation that reads it as DataCube read it in (headless ingest's and mount's
 *  `source`, as the protocol writes one), its table, and the file it is. */
export interface HostFile {
  readonly model: string;
  readonly runtime: string;
  readonly source: unknown;
  readonly table: string;
  readonly leaf: HostLeaf;
}

/**
 * WHAT A HOST SERVES A PAGE'S CUBES OVER (every grid call's `host`; docs/DATACUBE_ONE_API_PLAN_2026_10_21.md X1, X3):
 * its frames by name, the files it read in by their bytes' SHA-256, the remote files it mounted by URL.
 */
export interface HostTables {
  readonly frames: Readonly<Record<string, HostFrame>>;
  readonly files: Readonly<Record<string, HostFile>>;
  readonly remotes: Readonly<Record<string, HostFile>>;
}

/** Where a cube's leaf is read on its host (leafOnHost): its base, the table it reads, and -- a file's or remote
 *  file's -- the file it is read as now. */
export interface LeafOnHost {
  readonly base: HostBase;
  readonly table: string;
  readonly leaf?: HostLeaf;
}

/**
 * WHERE A CUBE'S LEAF IS READ ON ITS HOST, the one rule (docs/DATACUBE_ONE_API_PLAN_2026_10_21.md B7, X1, D14): a
 * frame the host serves, by name; a file -- as the page opens, the file given for it under its name (`given`: a file's
 * name, the hash of the file given for it), else the one it read in of the same bytes; a remote file it mounted, by URL.
 * Any other leaf is not read here, said in words.
 */
export function leafOnHost(leaf: LeafSource | HostLeaf, host: HostTables, given: Readonly<Record<string, string>> = {}): LeafOnHost {
  switch (leaf._type) {
    case 'frame': {
      const frame = host.frames[leaf.name];
      if (frame === undefined) throw new CubeRefusal(`the frame ${leaf.name} is not served`);
      return { base: { model: frame.model, runtime: frame.runtime, source: frame.accessor }, table: leaf.name };
    }
    case 'file': {
      const file = (given[leaf.name] !== undefined ? host.files[given[leaf.name]!] : undefined) ?? host.files[leaf.sha256];
      if (file === undefined) throw new CubeRefusal(`the file ${leaf.name} is not read here: the page is given its file as it opens`);
      return { base: fileBase(file), table: file.table, leaf: file.leaf };
    }
    case 'remoteFile': {
      const remote = host.remotes[leaf.url];
      if (remote === undefined) throw new CubeRefusal(`the remote file ${leaf.url} is not mounted here`);
      return { base: fileBase(remote), table: remote.table, leaf: remote.leaf };
    }
    case 'savedQuery':
    case 'warehouseTable':
    case 'class':
      throw new CubeRefusal(`${leaf.name} is not read here: a page on this host reads frames, files and remote files`);
  }
}

/** A file's or remote file's base: the relation DataCube read it in by, as a lambda. */
function fileBase(file: HostFile): HostBase {
  return { model: file.model, runtime: file.runtime, relation: JSON.stringify({ _type: 'lambda', parameters: [], body: [file.source] }) };
}

/**
 * WHAT A PAGE ON THE HOST CAN HOLD, the one rule (docs/DATACUBE_ONE_API_PLAN_2026_10_21.md B7, X1): a cube over a leaf
 * the host reads (leafOnHost), or over a join of such leaves (the join, and the table of the host's database each leaf
 * reads, in order) -- else refused in words. Every grid call's base asks it (gridBase, gridOpened), and so does the
 * keeper of each cube a write adds or moves to another source (headless page.ts pageRebase: the S4 audit's 2).
 */
export function onHost(source: CubeSource, host: HostTables, given: Readonly<Record<string, string>> = {}):
  { readonly leaf: LeafOnHost } | { readonly join: JoinSource; readonly tables: string[] } {
  if (source._type !== 'join') return { leaf: leafOnHost(source, host, given) };
  return { join: source, tables: leavesOf(source).map((leaf) => leafOnHost(leaf, host, given).table) };
}

/** The table of the host's database each leaf of `source` reads, in its order (cube-document.ts leavesOf): a join's
 *  inputs' tables, as a join over the host's tables is opened (headless join.ts). */
export function leafTables(source: CubeSource, host: HostTables, given: Readonly<Record<string, string>> = {}): string[] {
  return leavesOf(source).map((leaf) => leafOnHost(leaf, host, given).table);
}
