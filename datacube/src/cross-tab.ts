// A CROSS TAB'S CHECK (docs/DATACUBE_FLOW_MODE_2026_10_24.md FL3, FL8): a Cross Tab whose columns are fixed by its
// pinned values (FL2: query.ts crossTabRows) gives no column to a value its rows gain since -- its rows are counted in
// its total, in no column of their own -- so the values its rows hold that its pin lacks are asked of the database
// (query.ts unpinnedValuesLambda) and said, never dropped in silence: on its box, in a run, and by Python. A box whose
// every value has a column passes. One set of words for the canvas and Python (headless gridFailing).
//
// PURE: no DOM, no planner.

import type { ResultTable } from '../../engine-client/src/result.ts';
import { MAX_PIVOT_VALUES } from './query.ts';
import { rowColumns, type CubeSnapshot, type FilterValue } from './snapshot.ts';
import { cellFilterValue } from './value-filter.ts';

/** A pivot's value in words: an empty one "(empty)". */
export const valueWords = (v: FilterValue | null): string => (v === null ? '(empty)' : typeof v === 'object' ? JSON.stringify(v) : String(v));

/** What a pinned Cross Tab checks, in words: "every region has a column". */
export const lackingCheck = (on: string): string => `every ${on} has a column`;

/**
 * A PIVOT'S VALUES AS ITS QUESTION ANSWERED THEM (`answered`: query.ts pivotValuesLambda's rows -- Load values -- or
 * unpinnedValuesLambda's, those its pin lacks), the one reading the screen's Cross Tab and Python's (headless) share
 * (the review of 2026-10-26): each a filter value of its column's type (value-filter.ts cellFilterValue), and whether
 * there were more than a pivot shows (its question asks one past MAX_PIVOT_VALUES). An answer without its column is
 * refused, never read as none.
 */
export function pivotValuesOf(s: Pick<CubeSnapshot, 'pivotOn' | 'columns' | 'derived'>, answered: ResultTable):
  { readonly values: readonly (FilterValue | null)[]; readonly more: boolean } {
  const on = s.pivotOn[0];
  const column = answered.columns.find((c) => c.name === on);
  if (on === undefined || column === undefined) throw new Error(`its values' answer has no column ${on ?? '(none pivoted)'}`);
  const type = rowColumns(s).find((c) => c.name === on)?.type;
  return { values: column.values.map((v) => cellFilterValue(v, type)), more: column.values.length > MAX_PIVOT_VALUES };
}

/** Load values refused, its rows holding more values than a pivot shows (pivotValuesOf's `more`), in words. */
export const tooManyValues = (on: string): string =>
  `its rows hold more than ${MAX_PIVOT_VALUES} values of ${on}, more columns than a pivot shows: filter its rows first`;

/** Its values with no column, in words: "region APAC, LATAM have no column of their own: Load values again to add
 *  them" (the first `max` named; past what its question counts, MAX_PIVOT_VALUES, at least as many). */
export function lackingWords(on: string, values: readonly (FilterValue | null)[], max = 8): string {
  const shown = values.slice(0, max).map(valueWords).join(', ');
  const rest = values.length > MAX_PIVOT_VALUES ? ` and at least ${values.length - max} more`
    : values.length > max ? ` and ${values.length - max} more` : '';
  const one = values.length === 1;
  return `${on} ${shown}${rest} ${one ? 'has no column of its own' : 'have no column of their own'}: Load values again to add ${one ? 'it' : 'them'}`;
}
