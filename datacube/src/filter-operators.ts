// A FILTER'S OPERATORS: each one's operand -- none, one value, a list, another column -- with DataCube's own label for
// it, and which a column of a type takes (offers.ts). Moved out of the filter editor (ui/filter-editor.ts, which says
// them as before) so code with no screen reads them too: Python's filters are built and checked by them, headless
// (headless/grid.ts gridFilter; docs/PYTHON_ASKS_DATACUBE_2026_10_15.md, item 6).

import { takesOperator } from './offers.ts';
import type { FilterOperator } from './snapshot.ts';

/** What kind of value input an operator needs. */
export type OperandKind = 'none' | 'single' | 'list' | 'column';

/**
 * Operator arity, with DataCube's own label for each, so a user
 * moving between the two products reads the same words.
 */
export const OPERATORS = [
  { op: 'equal', label: '=', operand: 'single' },
  { op: 'notEqual', label: '!=', operand: 'single' },
  { op: 'lessThan', label: '<', operand: 'single' },
  { op: 'lessThanEqual', label: '<=', operand: 'single' },
  { op: 'greaterThan', label: '>', operand: 'single' },
  { op: 'greaterThanEqual', label: '>=', operand: 'single' },
  { op: 'isEmpty', label: 'is null', operand: 'none' },
  { op: 'isNotEmpty', label: 'is not null', operand: 'none' },
  { op: 'in', label: 'in', operand: 'list' },
  { op: 'notIn', label: 'not in', operand: 'list' },
  { op: 'contains', label: 'contains', operand: 'single' },
  { op: 'notContains', label: 'does not contain', operand: 'single' },
  { op: 'startsWith', label: 'starts with', operand: 'single' },
  { op: 'notStartsWith', label: 'does not start with', operand: 'single' },
  { op: 'endsWith', label: 'ends with', operand: 'single' },
  { op: 'notEndsWith', label: 'does not end with', operand: 'single' },
  { op: 'equalCaseInsensitive', label: '= (case-insensitive)', operand: 'single' },
  { op: 'notEqualCaseInsensitive', label: '!= (case-insensitive)', operand: 'single' },
  { op: 'containsCaseInsensitive', label: 'contains (case-insensitive)', operand: 'single' },
  { op: 'startsWithCaseInsensitive', label: 'starts with (case-insensitive)', operand: 'single' },
  { op: 'endsWithCaseInsensitive', label: 'ends with (case-insensitive)', operand: 'single' },
  { op: 'inCaseInsensitive', label: 'in (case-insensitive)', operand: 'list' },
  { op: 'notInCaseInsensitive', label: 'not in (case-insensitive)', operand: 'list' },
  { op: 'equalColumn', label: '= value in column', operand: 'column' },
  { op: 'equalCaseInsensitiveColumn', label: '= (case-insensitive) value in column', operand: 'column' },
  { op: 'notEqualColumn', label: '!= value in column', operand: 'column' },
  { op: 'notEqualCaseInsensitiveColumn', label: '!= (case-insensitive) value in column', operand: 'column' },
  { op: 'lessThanColumn', label: '< value in column', operand: 'column' },
  { op: 'lessThanEqualColumn', label: '<= value in column', operand: 'column' },
  { op: 'greaterThanColumn', label: '> value in column', operand: 'column' },
  { op: 'greaterThanEqualColumn', label: '>= value in column', operand: 'column' },
] as const satisfies ReadonlyArray<{
  readonly op: FilterOperator; readonly label: string; readonly operand: OperandKind;
}>;

// every operator is in the table: one added to FilterOperator and not here, or here and not there, does not compile --
// so an operator's operand is the table's, never a default
type Same<A, B> = [A] extends [B] ? ([B] extends [A] ? true : false) : false;
const everyOperatorListed: Same<(typeof OPERATORS)[number]['op'], FilterOperator> = true;
void everyOperatorListed;

const OPERAND_BY_OP = Object.fromEntries(OPERATORS.map((o) => [o.op, o.operand])) as
  Record<FilterOperator, OperandKind>;

export function operandKind(op: FilterOperator): OperandKind {
  return OPERAND_BY_OP[op];
}


export function operatorsFor(type: string | undefined): FilterOperator[] {
  if (type === undefined) return OPERATORS.map((o) => o.op);
  return OPERATORS.filter((o) => takesOperator(o.op, type)).map((o) => o.op);
}

export function isCompatible(op: FilterOperator, type: string | undefined): boolean {
  return type === undefined || takesOperator(op, type);
}
