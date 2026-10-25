// A SAMPLE BY ROW NUMBER (docs/DATACUBE_FLOW_MODE_2026_10_24.md M3: a flow's Sample box, Alteryx's Sample -- its N per
// group, Skip first N and 1 of every N): what the grid's Top N cannot say, said as Unique says a key's first row -- a
// row number in an order (a window column, `rowNumber`, restarting for each group when there are groups), a filter on
// it, and the number hidden: its first or last N of each group (`<= n`, numbered rising or falling), every row after
// the first N (`> n`), or one row of every N (a remainder of the number, hidden too: `mod($x.row, n) == 1`). The
// first or last N of all the rows is the grid's Top N (ui/sample-form.ts), which keeps rows that tie. No value is
// computed here: the database numbers the rows. What a box says is read back off its query (`rowSampleOf`).
//
// One rule for the box's window and Python's grid.sample (headless gridPreset).
//
// PURE: no DOM, no planner.

import { fn, lambda, lit, property, variable } from '../../pure-protocol/src/index.ts';
import type { GridCommand } from './grid-commands.ts';
import { CubeRefusal, rowColumns, type CubeSnapshot, type DerivedColumn, type FilterNode } from './snapshot.ts';

/** How it samples: the first or last `n` of each group, every row after the first `n`, or one row of every `n`. */
export type RowSampleKind = 'first' | 'last' | 'skip' | 'every';
export const ROW_SAMPLE_KINDS: readonly RowSampleKind[] = ['first', 'last', 'skip', 'every'];

/** A sample by row number, as its window and Python set it: its rows numbered in the order of `by`, within `groups`. */
export interface RowSample {
  readonly kind: RowSampleKind;
  readonly n: number;
  readonly by: string;
  readonly groups: readonly string[];
}

/** What its row number is called (else this and `_2`, `_3`... where the grid has the name), and its remainder. */
export const SAMPLE_COLUMN = 'SampleRow';
const EVERY_COLUMN = 'SampleEvery';

/** What is wrong with a row sample's settings, in words, if anything: the grid's columns are `s`'s. */
export function rowSampleProblem(s: CubeSnapshot, t: RowSample): string | undefined {
  if (!ROW_SAMPLE_KINDS.includes(t.kind)) return `a sample is ${ROW_SAMPLE_KINDS.join(', ')}, not ${JSON.stringify(t.kind)}`;
  if (!Number.isInteger(t.n) || t.n < 1) return `it counts rows by a whole number from 1, not ${t.n}`;
  if (t.kind === 'every' && t.n < 2) return 'one row of every 1 is every row: take a number from 2';
  const have = new Set(rowColumns(s).map((c) => c.name));
  const odd = [t.by, ...t.groups].filter((c) => !have.has(c));
  if (odd.length > 0) return `the grid has no column ${odd.map((c) => `'${c}'`).join(', ')}`;
  if (new Set(t.groups).size !== t.groups.length) return 'it groups by each column once';
  if ((t.kind === 'first' || t.kind === 'last') && t.groups.length === 0) {
    return 'the first or last rows of all of them are its Top N: give it a group, or use the grid\'s Top N';
  }
  return undefined;
}

/**
 * The commands that make a grid a row sample (`s`: the grid as it is): its row number (per group), any remainder, its
 * filter (and-ed with any the grid has), the numbers hidden. Refused in words (rowSampleProblem).
 */
export function rowSampleCommands(s: CubeSnapshot, t: RowSample): GridCommand[] {
  const problem = rowSampleProblem(s, t);
  if (problem !== undefined) throw new CubeRefusal(problem);
  const taken = new Set([...rowColumns(s).map((c) => c.name), ...(s.groupDerived ?? []).map((d) => d.name)]);
  const free = (base: string): string => {
    let name = base;
    for (let i = 2; taken.has(name); i += 1) name = `${base}_${i}`;
    taken.add(name);
    return name;
  };
  const row = free(SAMPLE_COLUMN);
  const numbered: DerivedColumn = {
    name: row, window: { fn: 'rowNumber', partition: [...t.groups], order: [{ column: t.by, direction: t.kind === 'last' ? 'desc' : 'asc' }] },
  };
  const commands: GridCommand[] = [{ kind: 'calculate', level: 'row', column: numbered }];
  const hidden: Record<string, { readonly hidden: true }> = { [row]: { hidden: true } };
  let condition: FilterNode;
  if (t.kind === 'every') {
    const every = free(EVERY_COLUMN);
    // the number's remainder by n: 1 for the first row and every nth after it (n is 2 or more)
    commands.push({ kind: 'calculate', level: 'row', column: {
      name: every, lambda: lambda(['x'], fn('mod', fn('toOne', property(variable('x'), row)), lit.integer(t.n))), kind: 'dimension',
    } });
    hidden[every] = { hidden: true };
    condition = { kind: 'condition', column: every, operator: 'equal', value: 1 };
  } else {
    condition = { kind: 'condition', column: row, operator: t.kind === 'skip' ? 'greaterThan' : 'lessThanEqual', value: t.n };
  }
  return [...commands, { kind: 'addFilter', condition }, { kind: 'configure', patch: { columns: hidden } }];
}

/**
 * A grid made a row sample, read off its query: its settings and its columns (the row number, any remainder) -- when its
 * filter is the sample's condition alone, over a row number in one order (and for one of every N, its remainder).
 */
export function rowSampleOf(query: { readonly derived: readonly DerivedColumn[]; readonly filter?: FilterNode | undefined }):
  (RowSample & { readonly columns: readonly string[] }) | undefined {
  const f = query.filter;
  if (f?.kind !== 'condition' || typeof f.value !== 'number') return undefined;
  const numberOf = (name: string): { readonly w: NonNullable<DerivedColumn['window']> } | undefined => {
    const w = query.derived.find((d) => d.name === name)?.window;
    return w?.fn === 'rowNumber' && w.order.length === 1 ? { w } : undefined;
  };
  const sample = (kind: RowSampleKind, n: number, w: NonNullable<DerivedColumn['window']>, columns: string[]) =>
    ({ kind, n, by: w.order[0]!.column, groups: w.partition, columns });
  if (f.operator === 'lessThanEqual' || f.operator === 'greaterThan') {
    const r = numberOf(f.column);
    if (r === undefined) return undefined;
    const desc = r.w.order[0]!.direction === 'desc';
    if (f.operator === 'greaterThan') return desc ? undefined : sample('skip', f.value, r.w, [f.column]);
    // the first of all the rows is the Top N's; a first of each group, a sample's
    return r.w.partition.length === 0 ? undefined : sample(desc ? 'last' : 'first', f.value, r.w, [f.column]);
  }
  if (f.operator !== 'equal' || f.value !== 1) return undefined;
  // one of every N: its remainder is mod(toOne($x.row), n) of a row number
  const d = query.derived.find((c) => c.name === f.column);
  const body = d?.lambda?.body;
  const param = d?.lambda?.parameters[0]?.name;
  if (body?.length !== 1 || param === undefined) return undefined;
  const call = body[0]!;
  if (call._type !== 'func' || call.function !== 'mod' || call.parameters.length !== 2) return undefined;
  const [one, by] = call.parameters;
  if (by?._type !== 'integer' || one?._type !== 'func' || one.function !== 'toOne') return undefined;
  const p = one.parameters[0];
  if (p?._type !== 'property' || p.parameters[0]?._type !== 'var' || (p.parameters[0] as { name: string }).name !== param) return undefined;
  const r = numberOf(p.property);
  return r === undefined || r.w.order[0]!.direction === 'desc' ? undefined : sample('every', Number(by.value), r.w, [p.property, f.column]);
}

/** The commands that take a row sample off (`was`: rowSampleOf's): its filter's condition, then its columns. */
export function rowSampleOffCommands(query: { readonly filter?: FilterNode | undefined }, was: { readonly columns: readonly string[] }): GridCommand[] {
  return [
    ...(query.filter === undefined ? [] : [{ kind: 'refilter', remove: [query.filter], add: [] } as const]),
    ...[...was.columns].reverse().map((name): GridCommand => ({ kind: 'removeCalculated', name })),
  ];
}

/**
 * A GRID SAMPLED AS ASKED, from whatever sample it has (`s`): the sample it has taken off (a Top N, a row sample), then
 * the one asked -- the first or last `n` of all its rows its Top N (which keeps rows that tie: the smallest first), any
 * other a row sample (rowSampleCommands) -- or none (`t` null): every row again. The window's OK and Keep every row, and
 * Python's grid.sample.
 */
export function sampleCommands(s: CubeSnapshot, t: RowSample | null): GridCommand[] {
  const was = rowSampleOf(s);
  const off: GridCommand[] = [
    ...(s.topN !== undefined ? [{ kind: 'topN', topN: null } as const] : []),
    ...(was === undefined ? [] : rowSampleOffCommands(s, was)),
  ];
  if (t === null) return off;
  if ((t.kind === 'first' || t.kind === 'last') && t.groups.length === 0) {
    if (!Number.isInteger(t.n) || t.n < 1) throw new CubeRefusal(`it counts rows by a whole number from 1, not ${t.n}`);
    if (!rowColumns(s).some((c) => c.name === t.by)) throw new CubeRefusal(`the grid has no column '${t.by}'`);
    return [...off, { kind: 'topN', topN: { by: t.by, n: t.n, ...(t.kind === 'first' ? { bottom: true } : {}) } }];
  }
  // its names judged as the grid will be once the old sample's columns are gone
  const without = was === undefined ? s : { ...s, derived: s.derived.filter((d) => !was.columns.includes(d.name)) };
  return [...off, ...rowSampleCommands(without, t)];
}

/** A row sample's words, as its box says it: `first 3 of each region by notional`, `one of every 10 by trade_id`. */
export function rowSampleWords(t: RowSample): string {
  const of = t.groups.length === 0 ? '' : ` of each ${t.groups.join(', ')}`;
  switch (t.kind) {
    case 'first': return `first ${t.n}${of} by ${t.by}`;
    case 'last': return `last ${t.n}${of} by ${t.by}`;
    case 'skip': return `all but the first ${t.n}${of} by ${t.by}`;
    case 'every': return `one of every ${t.n}${of} by ${t.by}`;
  }
}

/** A sample read from JSON (Python's): `{kind: 'sample', mode, n, by, groups?}`, or `{kind: 'sample', mode: null}` for
 *  every row again (null) -- or refused in words. */
export function readRowSample(raw: unknown): RowSample | null {
  const o = raw !== null && typeof raw === 'object' && !Array.isArray(raw) ? raw as Record<string, unknown> : undefined;
  if (o === undefined || o['kind'] !== 'sample') throw new CubeRefusal(`a sample is {kind: 'sample', mode, n, by, groups}, not ${JSON.stringify(raw)}`);
  const odd = Object.keys(o).filter((k) => !['kind', 'mode', 'n', 'by', 'groups'].includes(k));
  if (odd.length > 0) throw new CubeRefusal(`a sample takes mode, n, by and groups, not ${odd.join(', ')}`);
  const mode = o['mode'];
  if (mode === null) {
    if (Object.keys(o).some((k) => k !== 'kind' && k !== 'mode')) throw new CubeRefusal('every row again takes nothing more');
    return null;
  }
  if (!(ROW_SAMPLE_KINDS as readonly unknown[]).includes(mode)) throw new CubeRefusal(`its mode is ${ROW_SAMPLE_KINDS.join(', ')}, not ${JSON.stringify(mode)}`);
  const n = o['n'];
  if (typeof n !== 'number') throw new CubeRefusal(`its n is a number, not ${JSON.stringify(n)}`);
  const by = o['by'];
  if (typeof by !== 'string') throw new CubeRefusal(`its by is a column's name, not ${JSON.stringify(by)}`);
  const groups = o['groups'] ?? [];
  if (!Array.isArray(groups) || !groups.every((g): g is string => typeof g === 'string')) {
    throw new CubeRefusal(`its groups are a list of column names, not ${JSON.stringify(groups)}`);
  }
  return { kind: mode as RowSampleKind, n, by, groups };
}
