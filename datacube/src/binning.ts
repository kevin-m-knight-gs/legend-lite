// A FLOW'S BINNING (docs/DATACUBE_FLOW_MODE_2026_10_24.md M3, Alteryx's Multi-Field Binning, its equal intervals): each
// of several number columns put into a number of bins of equal width over its own range -- the grid's own bins preset
// (grid-presets.ts, calc-presets.ts binColumns), the database finding each column's smallest and largest across every
// row and placing each value: a column of each value's bin, its lower edge (`notional_bins`), beside the columns it is
// made of; the range and the upper edge it reads hidden. Read back off its columns (`binsOf`): rebuilt by the rule that
// made them and compared whole, never guessed at.
//
// One rule for the box's window and Python's grid.binning (headless gridPreset).
//
// PURE: no DOM, no planner.

import { toJson } from '../../pure-protocol/src/index.ts';
import { familyOf } from '../../engine-client/src/types.ts';
import { binColumns, upperName } from './calc-presets.ts';
import type { GridCommand } from './grid-commands.ts';
import { presetCommands } from './grid-presets.ts';
import { CubeRefusal, rowColumns, type CubeSnapshot, type DerivedColumn } from './snapshot.ts';

/** Binning, as its window sets it: its columns, and how many bins each is put in. */
export interface Binning {
  readonly columns: readonly string[];
  readonly bins: number;
}

/** The most bins a column is put in: a bin a value of a few. */
export const MAX_BINS = 1000;

/** A bin column's helpers: its range's ends, read across every row, and its upper edge -- hidden. */
const helpers = (name: string): string[] => [`${name}__min`, `${name}__max`, upperName(name)];

/**
 * The commands that put a grid's columns into bins (`s`: the grid as it is): for each column the bins preset's columns
 * (its range's ends, the bin, its upper edge) under a name no column of the grid has, the range and the upper edge
 * hidden -- one step. Refused in words: no column, a column the grid has not or that is no number, a column twice, a
 * count of bins no whole number from 1 to MAX_BINS.
 */
export function binningCommands(s: CubeSnapshot, b: Binning): GridCommand[] {
  if (b.columns.length === 0) throw new CubeRefusal('pick a number column to put into bins');
  if (!Number.isInteger(b.bins) || b.bins < 1 || b.bins > MAX_BINS) {
    throw new CubeRefusal(`a count of bins is a whole number from 1 to ${MAX_BINS}, not ${b.bins}`);
  }
  const types = new Map(rowColumns(s).map((c) => [c.name, c.type] as const));
  if (new Set(b.columns).size !== b.columns.length) throw new CubeRefusal('a column is put into bins once');
  const made: GridCommand[] = [];
  const hidden: Record<string, { readonly hidden: true }> = {};
  let now = s;
  for (const c of b.columns) {
    if (!types.has(c)) throw new CubeRefusal(`the grid has no column '${c}'`);
    const type = types.get(c);
    if (familyOf(type) !== 'numeric') throw new CubeRefusal(`'${c}' is ${type ?? 'not typed yet'}: only a number is put into bins`);
    const commands = presetCommands(now, { kind: 'bins', column: c, bins: { count: b.bins } });
    const columns = commands.flatMap((x) => (x.kind === 'calculate' ? [x.column] : []));
    // each next column's names judged against those this one takes
    now = { ...now, derived: [...now.derived, ...columns] };
    made.push(...commands);
    const bin = columns.find((d) => d.lambda !== undefined && d.kind === 'dimension');
    for (const h of bin === undefined ? [] : helpers(bin.name)) hidden[h] = { hidden: true };
  }
  return [...made, { kind: 'configure', patch: { columns: hidden } }];
}

/** The integer literals of a lambda's body: a count of bins is among them (and the count less one). */
function integers(v: unknown, found: Set<number>): Set<number> {
  if (v === null || typeof v !== 'object') return found;
  if (Array.isArray(v)) {
    for (const x of v) integers(x, found);
    return found;
  }
  const o = v as { _type?: string; value?: unknown };
  if (o._type === 'integer' && typeof o.value === 'number') found.add(o.value);
  for (const x of Object.values(o)) integers(x, found);
  return found;
}

/**
 * A grid's columns put into bins, read off them: each bin column's name, the column it is of and its count of bins --
 * the four columns the bins preset makes, rebuilt and compared whole. None for any other columns.
 */
export function binsOf(derived: readonly DerivedColumn[]): Map<string, { readonly column: string; readonly bins: number }> {
  const out = new Map<string, { readonly column: string; readonly bins: number }>();
  const by = new Map(derived.map((d) => [d.name, d] as const));
  for (const d of derived) {
    if (d.lambda === undefined || d.kind !== 'dimension') continue;
    const lo = by.get(`${d.name}__min`)?.window;
    if (lo?.fn !== 'min' || lo.column === undefined) continue;
    const column = lo.column;
    for (const n of [...integers(d.lambda, new Set())].map((k) => [k, k + 1]).flat()) {
      if (n < 1 || n > MAX_BINS) continue;
      const rebuilt = binColumns(column, { count: n }, d.name);
      // as declared: a type the compiler gave a column since is no part of what made it
      const declared = (c: DerivedColumn): string => { const { type: _learned, ...rest } = c; return toJson(rest as never); };
      if (rebuilt.every((r) => { const had = by.get(r.name); return had !== undefined && declared(had) === declared(r); })) {
        out.set(d.name, { column, bins: n });
        break;
      }
    }
  }
  return out;
}

/** The columns a Binning makes besides its bins: each bin's range and upper edge. */
export const binHelpers = (bins: ReadonlyMap<string, unknown>): string[] => [...bins.keys()].flatMap(helpers);

/** A Binning read from JSON (Python's): `{kind: 'binning', columns, bins}` -- or refused in words. */
export function readBinning(raw: unknown): Binning {
  const o = raw !== null && typeof raw === 'object' && !Array.isArray(raw) ? raw as Record<string, unknown> : undefined;
  if (o === undefined || o['kind'] !== 'binning' || !Array.isArray(o['columns']) || !o['columns'].every((c) => typeof c === 'string')
    || typeof o['bins'] !== 'number') {
    throw new CubeRefusal(`a binning is {kind: 'binning', columns: [...], bins: n}, not ${JSON.stringify(raw)}`);
  }
  const odd = Object.keys(o).filter((k) => !['kind', 'columns', 'bins'].includes(k));
  if (odd.length > 0) throw new CubeRefusal(`a binning takes no ${odd.join(', ')}`);
  return { columns: o['columns'] as string[], bins: o['bins'] };
}
