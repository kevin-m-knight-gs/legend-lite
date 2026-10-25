// UNIQUE (docs/DATACUBE_FLOW_MODE_2026_10_24.md M3: a flow's Unique box, Alteryx's, with its U and D outputs): a row of
// each key -- the first, in an order -- said as what a grid already does: a row number restarting for each key
// (a window column, `rowNumber` partitioned by the key's columns), a filter keeping the rows numbered 1, and that
// column hidden. Its Unique output is the grid's rows; its Duplicates the filter's False port (FL7: the same query, the
// filter turned round) -- every row after a key's first. No value is computed here: the database numbers the rows.
//
// One rule for the box's window and Python's grid.unique (headless gridPreset). What a box says is read back off its
// query (`uniqueOf`), never stored beside it.
//
// PURE: no DOM, no planner.

import type { GridCommand } from './grid-commands.ts';
import { CubeRefusal, rowColumns, type CubeSnapshot, type DerivedColumn, type FilterNode, type SortSpec } from './snapshot.ts';

/** What Unique is set to: the columns a key is, and the order a key's first row is first in (its first column, rising,
 *  when none is given). */
export interface Unique {
  readonly keys: readonly string[];
  readonly by?: SortSpec;
}

/** What Unique's row number is called: this, else this and `_2`, `_3`... where the grid has a column of the name. */
export const UNIQUE_COLUMN = 'UniqueRow';

/**
 * The commands that make a grid Unique (`s`: the grid as it is): its row number per key, its filter (and-ed with any
 * filter the grid has), the number's column hidden. Refused in words: no key, a key or an order the grid has no column
 * of, a key twice.
 */
export function uniqueCommands(s: CubeSnapshot, u: Unique): GridCommand[] {
  const have = new Set(rowColumns(s).map((c) => c.name));
  if (u.keys.length === 0) throw new CubeRefusal('a key is at least one column');
  const odd = [...u.keys, ...(u.by !== undefined ? [u.by.column] : [])].filter((c) => !have.has(c));
  if (odd.length > 0) throw new CubeRefusal(`the grid has no column ${odd.map((c) => `'${c}'`).join(', ')}`);
  if (new Set(u.keys).size !== u.keys.length) throw new CubeRefusal('a key names each column once');
  const taken = new Set([...have, ...(s.groupDerived ?? []).map((d) => d.name)]);
  let name = UNIQUE_COLUMN;
  for (let i = 2; taken.has(name); i += 1) name = `${UNIQUE_COLUMN}_${i}`;
  const column: DerivedColumn = {
    name, window: { fn: 'rowNumber', partition: [...u.keys], order: [u.by ?? { column: u.keys[0]!, direction: 'asc' }] },
  };
  return [
    { kind: 'calculate', level: 'row', column },
    { kind: 'addFilter', condition: { kind: 'condition', column: name, operator: 'equal', value: 1 } },
    { kind: 'configure', patch: { columns: { [name]: { hidden: true } } } },
  ];
}

/** A grid made Unique, read off its query: its keys and its order, and its row number's column -- when its filter is
 *  that column's first row, alone, and the column is a row number per key. */
export function uniqueOf(query: { readonly derived: readonly DerivedColumn[]; readonly filter?: FilterNode | undefined }):
  { readonly keys: readonly string[]; readonly by: SortSpec; readonly column: string } | undefined {
  const f = query.filter;
  if (f?.kind !== 'condition' || f.operator !== 'equal' || f.value !== 1) return undefined;
  const d = query.derived.find((c) => c.name === f.column);
  const w = d?.window;
  if (w?.fn !== 'rowNumber' || w.partition.length === 0 || w.order.length !== 1) return undefined;
  return { keys: w.partition, by: w.order[0]!, column: f.column };
}

/** Unique read from JSON (Python's): `{kind: 'unique', keys, by?}`, `by` a column (rising) or `{column, direction}`. */
export function readUnique(raw: unknown): Unique {
  const o = raw !== null && typeof raw === 'object' && !Array.isArray(raw) ? raw as Record<string, unknown> : undefined;
  if (o === undefined || o['kind'] !== 'unique') throw new CubeRefusal(`a Unique is {kind: 'unique', keys, by?}, not ${JSON.stringify(raw)}`);
  const odd = Object.keys(o).filter((k) => !['kind', 'keys', 'by'].includes(k));
  if (odd.length > 0) throw new CubeRefusal(`a Unique takes keys and by, not ${odd.join(', ')}`);
  const keys = o['keys'];
  if (!Array.isArray(keys) || !keys.every((k): k is string => typeof k === 'string')) {
    throw new CubeRefusal(`its keys are a list of column names, not ${JSON.stringify(keys)}`);
  }
  const by = o['by'];
  if (by === undefined) return { keys };
  if (typeof by === 'string') return { keys, by: { column: by, direction: 'asc' } };
  const b = by !== null && typeof by === 'object' ? by as Record<string, unknown> : undefined;
  if (typeof b?.['column'] !== 'string' || (b['direction'] !== 'asc' && b['direction'] !== 'desc')) {
    throw new CubeRefusal(`its order is a column, or {column, direction: 'asc' | 'desc'}, not ${JSON.stringify(by)}`);
  }
  return { keys, by: { column: b['column'], direction: b['direction'] } };
}
