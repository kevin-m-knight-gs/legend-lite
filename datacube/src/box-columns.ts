// WHAT A FLOW'S BOX HANDS ON (docs/DATACUBE_FLOW_MODE_2026_10_24.md: a box's rows are the columns it shows, as Alteryx's
// tools hand on their fields): its rows' columns but those its configuration hides (a Select's, Unique's row number) and
// a Summarize's dimensions it is not grouped by (snapshot.ts ungroupedDimensions), each under the name it is given (its
// display name: a Select's rename, the relation's `rename` for what reads it). One rule for what the box after it reads
// (live-inputs.ts gridInput), the columns its settings say, its Profile and its Output.
//
// PURE: no DOM, no planner.

import { ungroupedDimensions, type CubeShape } from './snapshot.ts';

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

/** The columns a box renames for what reads it: each kept column given a display name of its own, from it to that. */
export function boxRenamed(query: Query, configuration: Readonly<Record<string, unknown>>): { readonly from: string; readonly to: string }[] {
  const omitted = new Set(boxOmitted(query, configuration));
  return Object.entries(settingsOf(configuration)).flatMap(([from, c]) => {
    const to = c !== null && typeof c === 'object' ? c['displayName'] : undefined;
    return typeof to === 'string' && to.trim() !== '' && to !== from && !omitted.has(from) ? [{ from, to }] : [];
  });
}

/** The columns a box hands on of `columns` (its rows' columns, in order), each under the name it hands it on by. */
export function handedOn(columns: readonly string[], query: Query, configuration: Readonly<Record<string, unknown>>):
  { readonly name: string; readonly as: string }[] {
  const omitted = new Set(boxOmitted(query, configuration));
  const renamed = new Map(boxRenamed(query, configuration).map((r) => [r.from, r.to] as const));
  return columns.filter((c) => !omitted.has(c)).map((name) => ({ name, as: renamed.get(name) ?? name }));
}
