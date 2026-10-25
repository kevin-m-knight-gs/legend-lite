// WHAT A FLOW'S BOX HANDS ON (docs/DATACUBE_FLOW_MODE_2026_10_24.md: a box's rows are the columns it shows, as Alteryx's
// tools hand on their fields): its rows' columns but those its configuration hides (a Select's, Unique's row number) and
// a Summarize's dimensions it is not grouped by (snapshot.ts ungroupedDimensions), each under the name it is given (its
// display name: a Select's rename, the relation's `rename` for what reads it). One rule for what the box after it reads
// (live-inputs.ts gridInput), the columns its settings say, its Profile and its Output.
//
// ITS ORDER (a Select's: Alteryx's fields moved up and down): its columns handed on in its configuration's column order,
// those it does not list after them in their own order (`boxOrder`, the relation's `select` in that order for what reads
// it).
//
// IN PLACE (Alteryx's "change existing fields": a Data Cleansing, a Multi-Field Formula, an Imputation): a column a tool
// makes of another handed on by that column's name, the column itself hidden -- the box after it reads `region`,
// cleansed. Said by the settings alone (`inPlaceCommand`), read back off them (`inPlaceOf`).
//
// PURE: no DOM, no planner.

import type { GridCommand } from './grid-commands.ts';
import { rowColumns, ungroupedDimensions, type CubeShape, type DerivedColumn } from './snapshot.ts';

type Query = Pick<CubeShape, 'columns' | 'derived' | 'rows' | 'pivotOn'>;

/** A grid's configured columns' settings, as its document keeps them: by name. */
function settingsOf(configuration: Readonly<Record<string, unknown>>): Readonly<Record<string, Readonly<Record<string, unknown>>>> {
  const columns = configuration['columns'];
  return columns !== null && typeof columns === 'object' && !Array.isArray(columns)
    ? columns as Record<string, Record<string, unknown>> : {};
}

/** The columns a box leaves out of its rows: the ones it hides, and a Summarize's dimensions it is not grouped by. */
export function boxOmitted(query: Query, configuration: Readonly<Record<string, unknown>>): string[] {
  const hidden = Object.entries(settingsOf(configuration))
    .filter(([, c]) => c !== null && typeof c === 'object' && c['hidden'] === true).map(([name]) => name);
  return [...new Set([...hidden, ...ungroupedDimensions(query)])];
}

/** `columns` in the order a box's configuration puts them (its column order: a Select's), those it does not list after
 *  them in their own order. */
function inOrder(columns: readonly string[], configuration: Readonly<Record<string, unknown>>): string[] {
  const order = configuration['columnOrder'];
  if (!Array.isArray(order) || order.length === 0) return [...columns];
  const at = new Map(order.map((c, i) => [c, i] as const));
  const rank = (c: string): number => at.get(c) ?? order.length;
  return [...columns].sort((a, b) => rank(a) - rank(b));
}

/** The order a box hands its columns on in, by their own names, where its column order makes it other than its rows'
 *  own; undefined where it does not. */
export function boxOrder(query: Query, configuration: Readonly<Record<string, unknown>>): string[] | undefined {
  const omitted = new Set(boxOmitted(query, configuration));
  const own = rowColumns(query).map((c) => c.name).filter((c) => !omitted.has(c));
  const ordered = inOrder(own, configuration);
  return ordered.some((c, i) => c !== own[i]) ? ordered : undefined;
}

/** The columns a box renames for what reads it: each kept column given a display name of its own, from it to that. */
export function boxRenamed(query: Query, configuration: Readonly<Record<string, unknown>>): { readonly from: string; readonly to: string }[] {
  const omitted = new Set(boxOmitted(query, configuration));
  return Object.entries(settingsOf(configuration)).flatMap(([from, c]) => {
    const to = c !== null && typeof c === 'object' ? c['displayName'] : undefined;
    return typeof to === 'string' && to.trim() !== '' && to !== from && !omitted.has(from) ? [{ from, to }] : [];
  });
}

/** The command that hands each column made (`made`) on in place of the one it is made of (`column`): that one hidden, the
 *  made one given its name. */
export function inPlaceCommand(pairs: readonly { readonly column: string; readonly made: string }[]): GridCommand {
  return { kind: 'configure', patch: { columns: Object.fromEntries(pairs.flatMap((p) => [
    [p.column, { hidden: true }], [p.made, { displayName: p.column }],
  ])) } };
}

/** The columns a box hands on in place of others: each calculated column given the name of a column it reads that is
 *  hidden. */
export function inPlaceOf(derived: readonly DerivedColumn[], configuration: Readonly<Record<string, unknown>>, reads: (d: DerivedColumn) => readonly string[]):
  { readonly column: string; readonly made: string }[] {
  const settings = settingsOf(configuration);
  return derived.flatMap((d) => {
    const name = settings[d.name]?.['displayName'];
    return typeof name === 'string' && settings[name]?.['hidden'] === true && reads(d).includes(name) ? [{ column: name, made: d.name }] : [];
  });
}

/** The columns a box hands on of `columns` (its rows' columns, in order), in its order, each under the name it hands it
 *  on by. */
export function handedOn(columns: readonly string[], query: Query, configuration: Readonly<Record<string, unknown>>):
  { readonly name: string; readonly as: string }[] {
  const omitted = new Set(boxOmitted(query, configuration));
  const renamed = new Map(boxRenamed(query, configuration).map((r) => [r.from, r.to] as const));
  return inOrder(columns.filter((c) => !omitted.has(c)), configuration).map((name) => ({ name, as: renamed.get(name) ?? name }));
}
