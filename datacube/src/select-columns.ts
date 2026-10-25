// SELECT (docs/DATACUBE_FLOW_MODE_2026_10_24.md M1, Alteryx's Select): the columns a box hands on and the names it hands
// them on by -- said as the grid's own settings: a column left out is hidden, a column renamed has a display name of its
// own, which a flow's box hands on as the relation's `rename` (box-columns.ts, join-open.ts inputRows). One command, a
// configure of its columns' settings; what a box says is read back off them (box-columns.ts boxRenamed, flow-boxes.ts).
//
// One rule for the box's window and Python's grid.select (headless gridPreset).
//
// PURE: no DOM, no planner.

import type { GridCommand } from './grid-commands.ts';
import { CubeRefusal, rowColumns, type CubeSnapshot } from './snapshot.ts';

/** A Select, as its window and Python set it: the columns kept, in no order of its own, and the new names of some. */
export interface Selection {
  readonly keep: readonly string[];
  readonly rename: Readonly<Record<string, string>>;
}

/**
 * The command that makes a grid's columns a Select's (`s`: the grid as it is): every column kept shown, every other
 * hidden, each renamed given its display name and every other's cleared. Refused in words: nothing kept, a column the
 * grid has not, a new name empty, or two columns handed on by one name.
 */
export function selectCommands(s: CubeSnapshot, t: Selection): GridCommand[] {
  const all = rowColumns(s).map((c) => c.name);
  const odd = [...t.keep, ...Object.keys(t.rename)].filter((c) => !all.includes(c));
  if (odd.length > 0) throw new CubeRefusal(`the grid has no column ${[...new Set(odd)].map((c) => `'${c}'`).join(', ')}`);
  if (t.keep.length === 0) throw new CubeRefusal('keep a column: a box hands on at least one');
  const named = (c: string): string => (t.rename[c] ?? c).trim();
  const empty = Object.keys(t.rename).find((c) => named(c) === '');
  if (empty !== undefined) throw new CubeRefusal(`'${empty}' is renamed to nothing: give it a name, or keep its own`);
  const out = new Map<string, string>();
  for (const c of all.filter((x) => t.keep.includes(x))) {
    const as = named(c);
    const was = out.get(as);
    if (was !== undefined) throw new CubeRefusal(`'${was}' and '${c}' would both be handed on as '${as}': name one otherwise`);
    out.set(as, c);
  }
  const columns = Object.fromEntries(all.map((c) => {
    const as = named(c);
    return [c, { hidden: t.keep.includes(c) ? null : true, displayName: as !== c && t.keep.includes(c) ? as : null }];
  }));
  return [{ kind: 'configure', patch: { columns } }];
}

/** A Select read from JSON (Python's): `{kind: 'selectColumns', keep, rename?}` (a chart's `select` is a page command's
 *  kind) -- or refused in words. */
export function readSelection(raw: unknown): Selection {
  const o = raw !== null && typeof raw === 'object' && !Array.isArray(raw) ? raw as Record<string, unknown> : undefined;
  if (o === undefined || o['kind'] !== 'selectColumns') {
    throw new CubeRefusal(`a select is {kind: 'selectColumns', keep, rename}, not ${JSON.stringify(raw)}`);
  }
  const odd = Object.keys(o).filter((k) => !['kind', 'keep', 'rename'].includes(k));
  if (odd.length > 0) throw new CubeRefusal(`a select takes keep and rename, not ${odd.join(', ')}`);
  const keep = o['keep'];
  if (!Array.isArray(keep) || !keep.every((c): c is string => typeof c === 'string')) {
    throw new CubeRefusal(`what it keeps is a list of column names, not ${JSON.stringify(keep)}`);
  }
  const rename = o['rename'] ?? {};
  if (rename === null || typeof rename !== 'object' || Array.isArray(rename) || !Object.values(rename).every((v) => typeof v === 'string')) {
    throw new CubeRefusal(`its renames are {column: new name}, not ${JSON.stringify(rename)}`);
  }
  return { keep, rename: rename as Record<string, string> };
}
