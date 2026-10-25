// SUMMARIZE (docs/DATACUBE_FLOW_MODE_2026_10_24.md M1, Alteryx's Summarize): each column of a box's input grouped by,
// aggregated -- summed, averaged, counted, its smallest or largest, ... -- or left out, said as what a grid already
// does: its row groups, each column's kind and Aggregation (Column Properties' rules, grid-edits.ts setColumn, which
// judges each aggregate against its column's offers), and the columns it leaves out hidden. Its rows are a row a group,
// its keys and its aggregates (snapshot.ts ungroupedDimensions); the database groups and aggregates. One step of
// commands; what it is now is read back off the grid (grid-edits.ts `summaryOf`), never stored beside it.
//
// One rule for the box's window and Python's grid.summarize (headless gridPreset). It reads the snapshot alone: a
// module of the grid's edits imported here splits the startup chunk that holds them in two (+1,300 bytes, measured).
//
// PURE: no DOM, no planner.

import { AGGREGATE_FNS, CubeRefusal, TWO_COLUMN_AGGREGATES, rowColumns, type CubeSnapshot } from './snapshot.ts';
import type { GridCommand } from './grid-commands.ts';
import type { SummaryAction } from './grid-edits.ts';

export type { SummaryAction } from './grid-edits.ts';

/** A Summarize, as its window and Python set it: its columns, each grouped by or aggregated; any other left out. */
export interface Summary {
  readonly columns: readonly { readonly name: string; readonly action: SummaryAction }[];
}

/**
 * The commands that make a grid a Summarize's (`s`: the grid as it is): its grouping taken off, each column's kind made
 * what it is used as (a group a dimension, an aggregate a measure: Column Properties' rule), its grouping put on, each
 * aggregate set, and every column it does not use hidden -- one step. Refused in words: no group (a summary of every
 * row is its Profile), a column the grid has not, a calculated column asked to be what its editor did not make it, an
 * aggregate of two columns -- and an aggregate not offered on its column as it is applied (grid-edits.ts setColumn).
 */
export function summarizeCommands(s: CubeSnapshot, t: Summary): GridCommand[] {
  const columns = rowColumns(s);
  const byName = new Map(columns.map((c) => [c.name, c] as const));
  const odd = t.columns.map((c) => c.name).filter((n) => !byName.has(n));
  if (odd.length > 0) throw new CubeRefusal(`the grid has no column ${odd.map((c) => `'${c}'`).join(', ')}`);
  if (new Set(t.columns.map((c) => c.name)).size !== t.columns.length) throw new CubeRefusal('a Summarize does one thing with each column');
  const groups = t.columns.filter((c) => c.action === 'group').map((c) => c.name);
  if (groups.length === 0) throw new CubeRefusal('group by a column: a row of each group, its aggregates beside it (a summary of every row is its Profile)');
  const commands: GridCommand[] = s.rows.length > 0 ? [{ kind: 'group', rows: [] }] : [];
  const aggregates: GridCommand[] = [];
  for (const { name, action } of t.columns) {
    const c = byName.get(name)!;
    const want = action === 'group' ? 'dimension' : 'measure';
    if (action !== 'group') {
      // an aggregate of one column -- whether its column is offered it, setColumn's to judge as it is applied
      if (!(AGGREGATE_FNS as readonly string[]).includes(action)) {
        throw new CubeRefusal(`'${String(action)}' is no aggregate: ${AGGREGATE_FNS.filter((fn) => !TWO_COLUMN_AGGREGATES.includes(fn)).join(', ')}`);
      }
      if (TWO_COLUMN_AGGREGATES.includes(action)) throw new CubeRefusal(`'${action}' reads a second column: Column Properties sets it`);
      aggregates.push({ kind: 'column', name, change: { aggregate: action } });
    }
    if (c.kind === want) continue;
    // a calculated column's kind is its editor's (Edit Column); a source column's, Column Properties'
    if (c.derived) throw new CubeRefusal(`'${name}' is a calculated ${c.kind}: ${action === 'group' ? 'grouping by it' : 'aggregating it'} makes it a ${want}, which its editor sets (Edit Column)`);
    commands.push({ kind: 'column', name, change: { kind: want } });
  }
  commands.push({ kind: 'group', rows: groups }, ...aggregates);
  const used = new Set(t.columns.map((c) => c.name));
  commands.push({ kind: 'configure', patch: { columns: Object.fromEntries(columns.map((c) => [c.name, { hidden: used.has(c.name) ? null : true }])) } });
  return commands;
}

/** A Summarize read from JSON (Python's): `{kind: 'summarize', group: [...], aggregate?: {column: fn}}` -- or refused in
 *  words. */
export function readSummary(raw: unknown): Summary {
  const o = raw !== null && typeof raw === 'object' && !Array.isArray(raw) ? raw as Record<string, unknown> : undefined;
  if (o === undefined || o['kind'] !== 'summarize') throw new CubeRefusal(`a summarize is {kind: 'summarize', group, aggregate}, not ${JSON.stringify(raw)}`);
  const odd = Object.keys(o).filter((k) => !['kind', 'group', 'aggregate'].includes(k));
  if (odd.length > 0) throw new CubeRefusal(`a summarize takes group and aggregate, not ${odd.join(', ')}`);
  const group = o['group'];
  if (!Array.isArray(group) || !group.every((c): c is string => typeof c === 'string')) {
    throw new CubeRefusal(`what it groups by is a list of column names, not ${JSON.stringify(group)}`);
  }
  const aggregate = o['aggregate'] ?? {};
  if (aggregate === null || typeof aggregate !== 'object' || Array.isArray(aggregate) || !Object.values(aggregate).every((v) => typeof v === 'string')) {
    throw new CubeRefusal(`its aggregates are {column: ${AGGREGATE_FNS.filter((fn) => !TWO_COLUMN_AGGREGATES.includes(fn)).join(' | ')}}, not ${JSON.stringify(aggregate)}`);
  }
  return { columns: [...group.map((name) => ({ name, action: 'group' as const })),
    ...Object.entries(aggregate as Record<string, string>).map(([name, fn]) => ({ name, action: fn as SummaryAction }))] };
}
