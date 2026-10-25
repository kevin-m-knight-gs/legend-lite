// A SAMPLE BY ROW NUMBER (docs/DATACUBE_FLOW_MODE_2026_10_24.md M3: a flow's Sample box, Alteryx's Sample -- its N per
// group, Skip first N and 1 of every N): what the grid's Top N cannot say, said as Unique says a key's first row -- a
// row number in an order (a window column, `rowNumber`, restarting for each group when there are groups), a filter on
// it, and the number hidden: its first or last N of each group (`<= n`, numbered rising or falling), every row after
// the first N (`> n`), or one row of every N (a remainder of the number, hidden too: `mod($x.row, n) == 1`). The
// first or last N of all the rows is the grid's Top N (ui/sample-form.ts), which keeps rows that tie. Or, Alteryx's
// Select Records, the rows at some places (`1-10, 20, 30+`): a filter on the number of each range, or-ed. No value is
// computed here: the database numbers the rows. What a box says is read back off its query (`rowSampleOf`).
//
// One rule for the box's window and Python's grid.sample (headless gridPreset).
//
// PURE: no DOM, no planner.

import { fn, lambda, lit, property, variable } from '../../pure-protocol/src/index.ts';
import type { GridCommand } from './grid-commands.ts';
import { CubeRefusal, rowColumns, type CubeSnapshot, type DerivedColumn, type FilterNode } from './snapshot.ts';

/** How it samples: the first or last `n` of each group, every row after the first `n`, one row of every `n`, or the rows
 *  at some places (`ranges`). */
export type RowSampleKind = 'first' | 'last' | 'skip' | 'every' | 'records';
export const ROW_SAMPLE_KINDS: readonly RowSampleKind[] = ['first', 'last', 'skip', 'every', 'records'];

/** The rows from one place to another (its first row 1), or from it on: `20` is from 20 to 20, `30+` from 30 on. */
export interface RecordRange {
  readonly from: number;
  readonly to?: number;
}

/** A sample by row number, as its window and Python set it: its rows numbered in the order of `by`, within `groups`. */
export interface RowSample {
  readonly kind: RowSampleKind;
  readonly n: number;
  readonly by: string;
  readonly groups: readonly string[];
  /** The places a Select Records keeps; no other kind's. */
  readonly ranges?: readonly RecordRange[];
}

/** Ranges as they are written: `1-10, 20, 30+`, `-5` the first 5 -- refused in words. */
export function readRanges(text: string): RecordRange[] {
  const parts = text.split(',').map((p) => p.trim()).filter((p) => p !== '');
  if (parts.length === 0) throw new CubeRefusal('name the rows it keeps: 1-10, 20, 30+');
  return parts.map((p) => {
    const m = /^(\d*)\s*(-|\+)?\s*(\d*)$/.exec(p);
    const [from, sign, to] = m === null ? [] : [m[1]!, m[2], m[3]!];
    if (m !== null && sign === undefined && from !== '' && to === '') return { from: Number(from), to: Number(from) };
    if (m !== null && sign === '+' && from !== '' && to === '') return { from: Number(from) };
    if (m !== null && sign === '-' && (from !== '' || to !== '') && to !== '') return { from: from === '' ? 1 : Number(from), to: Number(to) };
    throw new CubeRefusal(`'${p}' is no range of rows: write 1-10, 20, 30+ or -5`);
  });
}

/** Ranges written as they are read: `1-10, 20, 30+`. */
export function rangesWords(ranges: readonly RecordRange[]): string {
  return ranges.map((r) => (r.to === undefined ? `${r.from}+` : r.to === r.from ? `${r.from}` : `${r.from}-${r.to}`)).join(', ');
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
  if (t.kind === 'records') {
    if (t.ranges === undefined || t.ranges.length === 0) return 'name the rows it keeps: 1-10, 20, 30+';
    const odd = t.ranges.find((r) => !Number.isInteger(r.from) || r.from < 1 || (r.to !== undefined && (!Number.isInteger(r.to) || r.to < r.from)));
    if (odd !== undefined) return `${rangesWords([odd])} is no range of rows: each from row 1, each to a row at or after its first`;
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
  if (t.kind === 'records') {
    // each range a condition on the number -- one row, from one on, between two -- any of them keeping a row
    const of = (r: RecordRange): FilterNode => (r.to === r.from ? { kind: 'condition', column: row, operator: 'equal', value: r.from }
      : r.to === undefined ? { kind: 'condition', column: row, operator: 'greaterThanEqual', value: r.from }
        : { kind: 'and', children: [{ kind: 'condition', column: row, operator: 'greaterThanEqual', value: r.from },
          { kind: 'condition', column: row, operator: 'lessThanEqual', value: r.to }] });
    const each = t.ranges!.map(of);
    condition = each.length === 1 ? each[0]! : { kind: 'or', children: each };
  } else if (t.kind === 'every') {
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
  // one condition added as the screen adds one; a Select Records' ranges, a tree, and-ed in the same way (refilter)
  const filtered: GridCommand = condition.kind === 'condition' ? { kind: 'addFilter', condition } : { kind: 'refilter', remove: [], add: [condition] };
  return [...commands, filtered, { kind: 'configure', patch: { columns: hidden } }];
}

/**
 * A grid made a row sample, read off its query: its settings and its columns (the row number, any remainder) -- when its
 * filter is the sample's condition alone, over a row number in one order (and for one of every N, its remainder).
 */
export function rowSampleOf(query: { readonly derived: readonly DerivedColumn[]; readonly filter?: FilterNode | undefined }):
  (RowSample & { readonly columns: readonly string[] }) | undefined {
  const f = query.filter;
  const numberOf = (name: string): { readonly w: NonNullable<DerivedColumn['window']> } | undefined => {
    const w = query.derived.find((d) => d.name === name)?.window;
    return w?.fn === 'rowNumber' && w.order.length === 1 ? { w } : undefined;
  };
  const sample = (kind: RowSampleKind, n: number, w: NonNullable<DerivedColumn['window']>, columns: string[]) =>
    ({ kind, n, by: w.order[0]!.column, groups: w.partition, columns });
  // Select Records: its ranges, each over one row number numbered rising, any of them (an `or` of them, or one)
  const records = f === undefined ? undefined : recordsOf(f);
  const counted = records === undefined ? undefined : numberOf(records.column);
  if (records !== undefined && counted !== undefined) {
    return counted.w.order[0]!.direction === 'desc' ? undefined
      : { ...sample('records', records.ranges.length, counted.w, [records.column]), ranges: records.ranges };
  }
  if (f?.kind !== 'condition' || typeof f.value !== 'number') return undefined;
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

/** A Select Records' filter read back: its ranges and the column they are of -- an equal (one row), a `>=` (from one
 *  on), a `>=` and a `<=` (between two), or an `or` of them, all of one column; undefined for any other filter. The
 *  first N alone (`<=`) and all but the first N (`>`) are the other kinds'. */
function recordsOf(f: FilterNode): { readonly column: string; readonly ranges: RecordRange[] } | undefined {
  const one = (n: FilterNode): { column: string; range: RecordRange } | undefined => {
    if (n.kind === 'condition' && typeof n.value === 'number' && n.operator === 'equal') return { column: n.column, range: { from: n.value, to: n.value } };
    if (n.kind === 'condition' && typeof n.value === 'number' && n.operator === 'greaterThanEqual') return { column: n.column, range: { from: n.value } };
    if (n.kind !== 'and' || n.children.length !== 2) return undefined;
    const [a, b] = n.children as [FilterNode, FilterNode];
    return a.kind === 'condition' && b.kind === 'condition' && a.column === b.column && a.operator === 'greaterThanEqual'
      && b.operator === 'lessThanEqual' && typeof a.value === 'number' && typeof b.value === 'number'
      ? { column: a.column, range: { from: a.value, to: b.value } } : undefined;
  };
  const each = (f.kind === 'or' ? f.children : [f]).map(one);
  const column = each[0]?.column;
  if (column === undefined || each.some((e) => e === undefined || e.column !== column)) return undefined;
  // one row alone, a remainder's 1, is one of every N's: its column says which (a row number is a Select Records')
  return { column, ranges: each.map((e) => e!.range) };
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
    case 'records': return `rows ${rangesWords(t.ranges ?? [])}${of} by ${t.by}`;
  }
}

/** A sample read from JSON (Python's): `{kind: 'sample', mode, n, by, groups?}`, or `{kind: 'sample', mode: null}` for
 *  every row again (null) -- or refused in words. */
export function readRowSample(raw: unknown): RowSample | null {
  const o = raw !== null && typeof raw === 'object' && !Array.isArray(raw) ? raw as Record<string, unknown> : undefined;
  if (o === undefined || o['kind'] !== 'sample') throw new CubeRefusal(`a sample is {kind: 'sample', mode, n, by, groups}, not ${JSON.stringify(raw)}`);
  const odd = Object.keys(o).filter((k) => !['kind', 'mode', 'n', 'by', 'groups', 'ranges'].includes(k));
  if (odd.length > 0) throw new CubeRefusal(`a sample takes mode, n, by, groups and ranges, not ${odd.join(', ')}`);
  const mode = o['mode'];
  if (mode === null) {
    if (Object.keys(o).some((k) => k !== 'kind' && k !== 'mode')) throw new CubeRefusal('every row again takes nothing more');
    return null;
  }
  if (!(ROW_SAMPLE_KINDS as readonly unknown[]).includes(mode)) throw new CubeRefusal(`its mode is ${ROW_SAMPLE_KINDS.join(', ')}, not ${JSON.stringify(mode)}`);
  // Select Records: its ranges written as text (1-10, 20, 30+), no n
  const ranges = o['ranges'];
  if (mode === 'records' || ranges !== undefined) {
    if (mode !== 'records') throw new CubeRefusal(`only a Select Records ('records') keeps ranges of rows, not '${String(mode)}'`);
    if (typeof ranges !== 'string') throw new CubeRefusal(`its ranges are written as text, 1-10, 20, 30+, not ${JSON.stringify(ranges)}`);
    const by = o['by'];
    if (typeof by !== 'string') throw new CubeRefusal(`its by is a column's name, not ${JSON.stringify(by)}`);
    const groups = o['groups'] ?? [];
    if (!Array.isArray(groups) || !groups.every((g): g is string => typeof g === 'string')) {
      throw new CubeRefusal(`its groups are a list of column names, not ${JSON.stringify(groups)}`);
    }
    const read = readRanges(ranges);
    return { kind: 'records', n: read.length, by, groups, ranges: read };
  }
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
