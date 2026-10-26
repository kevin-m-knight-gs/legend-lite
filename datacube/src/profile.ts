// A BOX'S PROFILE (docs/DATACUBE_FLOW_MODE_2026_10_24.md M5: Alteryx's Browse profile, its Field Summary): each column of
// the rows a box gives -- how many of them have a value, how many different values, the smallest and the largest --
// asked of the database as one query over those rows, a row of aggregates (`profileLambda`), and read back off its one
// row (`profileOf`). Nothing is counted here: the database counts. Asked when the profile is shown, never before.
//
// PURE: no DOM, no planner.

import { agg, derive, fn, from, lambda, lit, property, variable, type ColSpec, type Lambda, type ValueSpecification } from '../../pure-protocol/src/index.ts';
import { familyOf } from '../../engine-client/src/types.ts';
import type { ResultTable, Scalar } from '../../engine-client/src/result.ts';

/** A column of the rows profiled: its name and its type, as the compiler typed it. */
export interface ProfiledColumn {
  readonly name: string;
  readonly type: string;
}

/** What the profile says of a column. `smallest` and `largest` are absent for a column with no order (a Boolean, JSON). */
export interface ColumnProfile {
  readonly name: string;
  readonly type: string;
  /** How many rows have a value in it. */
  readonly filled: number;
  /** How many different values it has. */
  readonly distinct: number;
  readonly smallest?: Scalar;
  readonly largest?: Scalar;
}

/** The profile of a box's rows: how many, each column's, and how its number columns move together. */
export interface Profile {
  readonly rows: number;
  readonly columns: readonly ColumnProfile[];
  readonly correlations?: Correlations;
}

/**
 * HOW A BOX'S NUMBER COLUMNS MOVE TOGETHER (Alteryx's Pearson Correlation): each pair's Pearson correlation, from -1 to 1,
 * the database's `corr` -- a matrix, each column with itself 1, empty where a column does not vary.
 */
export interface Correlations {
  readonly columns: readonly string[];
  readonly r: readonly (readonly (number | null)[])[];
}

/** How many number columns a profile correlates at most: the first of them, so its question stays one row of a few. */
export const CORRELATED = 10;

const ROWS = '__profile_rows__';
const KEY = '__profile_key__';
/** Each aggregate's column: the column's place, and what it is (`c3_filled`), never its name, which may be anything. */
const named = (i: number, what: 'filled' | 'distinct' | 'smallest' | 'largest'): string => `c${i}_${what}`;

/** Whether a column's values have an order its smallest and largest are taken in. */
const ordered = (type: string): boolean => {
  const family = familyOf(type);
  return family === 'numeric' || family === 'text' || family === 'temporal';
};

/**
 * THE PROFILE'S QUESTION over `rows` (a relation: the box's rows, query.ts classLambda's): one row -- every row under
 * one key, aggregated -- its count of rows, and for each column its values counted (`count`, which counts the values
 * there are), its different values counted (`distinct`, then `count`), and, where its values have an order, its `min`
 * and `max`.
 */
export function profileLambda(rows: ValueSpecification, columns: readonly ProfiledColumn[]): Lambda {
  const x = (c: string): ValueSpecification => property(variable('x'), c);
  const y = variable('y');
  const specs: ColSpec[] = [agg(ROWS, lambda(['x'], lit.integer(1)), lambda(['y'], fn('count', y)))];
  columns.forEach((c, i) => {
    specs.push(agg(named(i, 'filled'), lambda(['x'], x(c.name)), lambda(['y'], fn('count', y))));
    specs.push(agg(named(i, 'distinct'), lambda(['x'], x(c.name)), lambda(['y'], fn('count', fn('distinct', y)))));
    if (ordered(c.type)) {
      specs.push(agg(named(i, 'smallest'), lambda(['x'], x(c.name)), lambda(['y'], fn('min', y))));
      specs.push(agg(named(i, 'largest'), lambda(['x'], x(c.name)), lambda(['y'], fn('max', y))));
    }
  });
  return from(rows)
    .extend([derive(KEY, lambda(['x'], lit.string('all')))])
    .groupBy([KEY], specs)
    .lambda();
}

/** Each pair's correlation's column: the two columns' places, never their names. */
const pair = (i: number, j: number): string => `r${i}_${j}`;

/**
 * THE CORRELATIONS' QUESTION over `rows`: one row -- every row under one key -- each pair of `columns` (numbers, by name)
 * mapped a row each and reduced by `corr`, as a grid's paired measure is (query.ts aggregateSpec).
 */
export function correlationLambda(rows: ValueSpecification, columns: readonly string[]): Lambda {
  const x = (c: string): ValueSpecification => property(variable('x'), c);
  const specs: ColSpec[] = [];
  for (let i = 0; i < columns.length; i++) {
    for (let j = i + 1; j < columns.length; j++) {
      specs.push(agg(pair(i, j), lambda(['x'], fn('rowMapper', x(columns[i]!), x(columns[j]!))), lambda(['y'], fn('corr', variable('y')))));
    }
  }
  return from(rows)
    .extend([derive(KEY, lambda(['x'], lit.string('all')))])
    .groupBy([KEY], specs)
    .lambda();
}

/** The correlations read back off their question's one row (`table`), each column named as given (`names`). */
export function correlationsOf(table: ResultTable, names: readonly string[]): Correlations {
  const value = (name: string): number | null => {
    const c = table.columns.find((col) => col.name === name);
    if (c === undefined) throw new Error(`the correlations' answer has no column ${name}`);
    const v = table.rowCount === 0 ? null : c.values[0] ?? null;
    return v === null || v === undefined || !Number.isFinite(Number(v)) ? null : Number(v);
  };
  return {
    columns: names,
    r: names.map((_, i) => names.map((__, j) => (i === j ? 1 : value(i < j ? pair(i, j) : pair(j, i))))),
  };
}

/** The question that types the rows profiled: none of them (`limit(0)`), its columns typed by the plan. */
export function columnsLambda(rows: ValueSpecification): Lambda {
  return from(rows).limit(0).lambda();
}

/** The profile read back off its question's one row (`table`): refused in words when the answer is not that row. */
export function profileOf(table: ResultTable, columns: readonly ProfiledColumn[]): Profile {
  const value = (name: string): Scalar | undefined => {
    const c = table.columns.find((col) => col.name === name);
    if (c === undefined) throw new Error(`the profile's answer has no column ${name}`);
    return c.values[0] ?? null;
  };
  const count = (name: string): number => Number(value(name) ?? 0);
  // no row at all (a box with no rows): every count none
  const empty = table.rowCount === 0;
  return {
    rows: empty ? 0 : count(ROWS),
    columns: columns.map((c, i) => ({
      name: c.name,
      type: c.type,
      filled: empty ? 0 : count(named(i, 'filled')),
      distinct: empty ? 0 : count(named(i, 'distinct')),
      ...(ordered(c.type) && !empty ? { smallest: value(named(i, 'smallest')) ?? null, largest: value(named(i, 'largest')) ?? null } : {}),
    })),
  };
}
