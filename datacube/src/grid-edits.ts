// ONE WAY TO EDIT A GRID (docs/DATACUBE_ONE_GRID_EDIT_PATH_2026_10_17.md): its row groups, its pivot, its sorts and a
// column's kind and Aggregation, by the screen's own offers -- what the Pivots and Sorts panels, the header menu, the
// drag zones and Column Properties offer a person -- so the screen and Python (headless gridEdit) edit a grid alike.
// No DOM. An edit the offers do not allow is refused in words (CubeRefusal): the screen's controls never ask one,
// Python's calls may.

import { PIVOT_SEPARATOR } from '../../engine-client/src/generated/lite-facts.ts';
import { lambda, property, variable } from '../../pure-protocol/src/index.ts';
import { nameProblem } from './calc.ts';
import { draftColumn, newDraft } from './column-draft.ts';
import { columnConfig, withColumn, type ColumnPatch, type CubeConfiguration } from './config.ts';
import { takesAggregate } from './offers.ts';
import {
  AGGREGATE_FNS, COLUMN_KINDS, CubeRefusal, SORT_DIRECTIONS, rowColumns, type AggregateFn, type ColumnKind,
  type CubeSnapshot, type DerivedColumn, type Measure, type SortSpec,
} from './snapshot.ts';

/** What an edit changes: the grid's query and its configuration (the editor's draft carries both). */
export interface GridDraft {
  readonly snapshot: CubeSnapshot;
  readonly config: CubeConfiguration;
}

const listed = (names: readonly string[]): string => (names.length === 0 ? 'none' : [...names].sort().join(', '));

/**
 * A column's kind as the grid groups by it: a calculated column's as its editor declared it, a source column's as
 * Column Properties sets it, else its type's default. Undefined for a column that does not exist before aggregation --
 * a group-level column, a pivot's generated one -- which is therefore neither groupable nor extendable.
 */
export function columnKind(draft: GridDraft, name: string): ColumnKind | undefined {
  const c = rowColumns(draft.snapshot).find((x) => x.name === name);
  if (!c) return undefined;
  return c.derived ? c.kind : columnConfig(draft.config, name).kind ?? c.kind;
}

/**
 * The columns a row group or a pivot may use: the dimensions, as DataCube's `enableRowGroup: kind === DIMENSION`.
 * Grouping by a notional makes one group per distinct amount, which is never what anyone meant.
 */
export function groupableColumns(draft: GridDraft): string[] {
  return rowColumns(draft.snapshot).map((c) => c.name).filter((n) => columnKind(draft, n) === 'dimension');
}

/** Every column the cube knows, its calculated ones too: what the Sorts panel offers and Column Properties lists. */
export function allColumns(draft: GridDraft): string[] {
  const s = draft.snapshot;
  return [...s.columns.map((c) => c.name), ...s.derived.map((d) => d.name), ...(s.groupDerived ?? []).map((d) => d.name)];
}

/**
 * The aggregates Aggregation offers a column of this type, as the compiler answers them (offers.ts takesAggregate):
 * each whose result is of the column's own family. An untyped column (a calculated one not yet typed): all, the
 * compiler judging it on Apply.
 */
export function offeredAggregates(type: string | undefined): AggregateFn[] {
  return type === undefined ? [...AGGREGATE_FNS] : AGGREGATE_FNS.filter((fn) => takesAggregate(fn, type));
}

/** `rows` as the grid's row groups, outermost first (the Vertical Pivots panel, the menu's Vertical Pivot, the Rows
 *  zone): each once, and each it did not have a dimension of the grid -- the screen offers dimensions to add, and a
 *  group it has is taken away whatever it is. */
export function setGrouping<D extends GridDraft>(draft: D, rows: readonly string[]): D {
  checkGroupable(draft, rows, draft.snapshot.rows, 'the row groups');
  return { ...draft, snapshot: { ...draft.snapshot, rows: [...rows] } };
}

/** `pivotOn` as the grid's pivot (the Horizontal Pivots panel, the menu's Horizontal Pivot, the Columns zone): each
 *  once, and each it did not have a dimension of the grid. Unpivoted, the grid has no pivot headers: a sort of one (the
 *  header menu's) goes with them, as the grid's rows would be sorted by a column they do not have. */
export function setPivot<D extends GridDraft>(draft: D, pivotOn: readonly string[]): D {
  checkGroupable(draft, pivotOn, draft.snapshot.pivotOn, 'the pivot');
  const columns = allColumns(draft);
  const sorts = pivotOn.length > 0 ? draft.snapshot.sorts : draft.snapshot.sorts.filter((x) => columns.includes(x.column));
  return { ...draft, snapshot: { ...draft.snapshot, pivotOn: [...pivotOn], sorts } };
}

function checkGroupable(draft: GridDraft, names: readonly string[], had: readonly string[], what: string): void {
  const offered = groupableColumns(draft);
  const seen = new Set<string>();
  for (const name of names) {
    if (seen.has(name)) throw new CubeRefusal(`${what} name '${name}' twice`);
    seen.add(name);
    if (had.includes(name) || offered.includes(name)) continue;
    throw new CubeRefusal(columnKind(draft, name) === 'measure'
      ? `'${name}' is a measure, and ${what} take dimensions: Column Properties > Column Kind makes it one`
      : `'${name}' is not a column ${what} can take: the grid's dimensions are ${listed(offered)}`);
  }
}

/**
 * `sorts` as the grid's sorts, in order, each once, ascending or descending: each it did not sort by a column the grid
 * has (the Sorts panel's), or, pivoted, a pivot's header -- its values, then a column it spreads, a pivot total's too
 * -- as the header menu sorts by the header clicked; a sort it has is kept or taken away as it is.
 */
export function setSorts<D extends GridDraft>(draft: D, sorts: readonly SortSpec[]): D {
  const columns = allColumns(draft);
  // a pivot's header ends with the column it spreads, under its own name (a grid's aggregates are its columns')
  const spread = new Set(columns);
  const pivoted = draft.snapshot.pivotOn.length > 0;
  const seen = new Set<string>();
  for (const { column, direction } of sorts) {
    if (!SORT_DIRECTIONS.includes(direction)) {
      throw new CubeRefusal(`a sort is ${SORT_DIRECTIONS.map((d) => `'${d}'`).join(' or ')}, not '${String(direction)}'`);
    }
    if (seen.has(column)) throw new CubeRefusal(`the sorts name '${column}' twice`);
    seen.add(column);
    if (draft.snapshot.sorts.some((x) => x.column === column)) continue;
    const at = column.lastIndexOf(PIVOT_SEPARATOR);
    const header = pivoted && at > 0 && spread.has(column.slice(at + PIVOT_SEPARATOR.length));
    if (!columns.includes(column) && !header) {
      throw new CubeRefusal(`the sort names '${column}', which the grid does not have: one of its columns `
        + `(${listed(columns)})${pivoted ? ", or a pivot's header" : ''}`);
    }
  }
  return { ...draft, snapshot: { ...draft.snapshot, sorts: sorts.map((s) => ({ column: s.column, direction: s.direction })) } };
}

/** What Column Properties sets of a column through this module: its kind, its Aggregation, and a weighted average's
 *  weight. A field told as null is cleared, as the panel's "none". */
export interface ColumnChange {
  readonly kind?: ColumnKind;
  readonly aggregate?: AggregateFn | null;
  readonly weight?: string | null;
}

/**
 * A column's kind, Aggregation or weight set as Column Properties sets them: the kind of a source column not grouped
 * or pivoted (the panel locks it then; a calculated column's is its editor's, which every query reads), a new kind
 * taking the pivot's exclusion with it (a dimension out, a measure in: upstream's setExcludedFromPivot(kind ===
 * DIMENSION)); the Aggregation of a measure the grouped level aggregates -- a source or a row-level calculated column,
 * never a group-level one, computed after grouping -- one offered for its type (or the one it has); a weight a column
 * of the grid's, for a weighted average only.
 */
export function setColumn<D extends GridDraft>(draft: D, name: string, change: ColumnChange): D {
  const columns = allColumns(draft);
  if (!columns.includes(name)) throw new CubeRefusal(`'${name}' is not a column of the grid (${listed(columns)})`);
  const s = draft.snapshot;
  let patch: ColumnPatch = {};
  if (change.kind !== undefined) {
    if (!COLUMN_KINDS.includes(change.kind)) {
      throw new CubeRefusal(`a column's kind is ${COLUMN_KINDS.map((k) => `'${k}'`).join(' or ')}, not '${String(change.kind)}'`);
    }
    if (!s.columns.some((c) => c.name === name)) {
      throw new CubeRefusal(`'${name}' is a calculated column: its kind is set in its editor (Edit Column)`);
    }
    if (s.rows.includes(name) || s.pivotOn.includes(name)) {
      throw new CubeRefusal(`'${name}' is grouped or pivoted on: its kind cannot change while it is`);
    }
    patch = { kind: change.kind, excludedFromPivot: change.kind === 'dimension' };
  }
  const config = withColumn(draft.config, name, patch);
  const now = { ...draft, config };
  const c = columnConfig(config, name);
  if (change.aggregate !== undefined && change.aggregate !== null) {
    const kind = columnKind(now, name);
    if (kind === undefined) {
      throw new CubeRefusal(`'${name}' is a group-level column, computed after grouping: it takes no Aggregation`);
    }
    if (kind !== 'measure') {
      throw new CubeRefusal(`'${name}' is a dimension, and only a measure takes an Aggregation: Column Properties > `
        + 'Column Kind makes it one');
    }
    const type = typeOf(s, name);
    if (change.aggregate !== c.aggregateFn && !offeredAggregates(type).includes(change.aggregate)) {
      throw new CubeRefusal(`'${change.aggregate}' is not offered on ${name}, a ${type} column: `
        + `${offeredAggregates(type).join(', ') || 'none'}`);
    }
  }
  const aggregate = change.aggregate === undefined ? c.aggregateFn : change.aggregate ?? undefined;
  if (change.weight !== undefined && change.weight !== null) {
    if (aggregate !== 'wavg') {
      throw new CubeRefusal(`only a weighted average (wavg) takes a weight: ${name}'s Aggregation is `
        + `${aggregate ?? 'its default'}`);
    }
    if (!columns.includes(change.weight)) {
      throw new CubeRefusal(`${name}'s weight names '${change.weight}', not a column of the grid (${listed(columns)})`);
    }
  }
  const value: ColumnPatch = {
    ...(change.aggregate !== undefined ? { aggregateFn: change.aggregate ?? undefined } : {}),
    ...(change.weight !== undefined
      ? { aggregationParameters: change.weight === null ? undefined : [change.weight] } : {}),
  };
  return { ...now, config: withColumn(config, name, value) };
}

/** A source or row-level calculated column's type as the compiler typed it; undefined while not yet typed. */
function typeOf(s: CubeSnapshot, name: string): string | undefined {
  return rowColumns(s).find((x) => x.name === name)?.type;
}

/** A measure made a column of the grid's: `column` carries it (its own, or a calculated column of its name). */
export interface MeasureMade {
  readonly measure: Measure;
  readonly column: string;
}

/**
 * ONE MEASURE AS THE SCREEN'S MODEL (docs/DATACUBE_ONE_GRID_EDIT_PATH_2026_10_17.md §3.3-3.4): named as its own column,
 * that column not a key and not in `taken`, that column's Aggregation (a dimension made a measure first, as Column
 * Properties would); any other a calculated column of its own name reading its column (the column editor's own steps:
 * column-draft.ts newDraft, draftColumn; its name by calc.ts nameProblem), with that Aggregation -- each by setColumn's
 * rules. Refused in their words (CubeRefusal): a column the grid has not, a name taken, an aggregate its column's type
 * is not offered (a count of text: "Show leaf count" counts a group's rows). Python's grid.measure(), a cube saved with
 * a measures list (openCube) and a chart's values opened in a grid (Open in Grid) are made the grid's by this one rule.
 */
export function measureAsColumn<D extends GridDraft>(draft: D, m: Measure, taken: ReadonlySet<string> = new Set()): {
  readonly draft: D;
  readonly made: MeasureMade;
  readonly note: string;
} {
  const change: ColumnChange = { aggregate: m.fn, ...(m.weight !== undefined ? { weight: m.weight } : {}) };
  const column = rowColumns(draft.snapshot).find((c) => c.name === m.column);
  if (!column) throw new CubeRefusal(`'${m.column}' is not a column of the grid (${listed(allColumns(draft))})`);
  // Aggregation's offer, judged by the column it reads (the calculated column a measure may become is of its type)
  if (column.type !== undefined && !offeredAggregates(column.type).includes(m.fn)) {
    throw new CubeRefusal(`'${m.fn}' is not offered on ${m.column}, a ${column.type} column: `
      + `${offeredAggregates(column.type).join(', ') || 'none'}${m.fn === 'count' ? " (Show leaf count counts a group's rows)" : ''}`);
  }
  const isKey = draft.snapshot.rows.includes(m.column) || draft.snapshot.pivotOn.includes(m.column);
  if (m.name === m.column && !isKey && !taken.has(m.column)) {
    const dimension = columnKind(draft, m.column) === 'dimension';
    const d = setColumn(dimension ? setColumn(draft, m.column, { kind: 'measure' }) : draft, m.column, change);
    return {
      draft: d, made: { measure: m, column: m.column },
      note: `measure ${m.name} is now ${m.column}'s Aggregation, ${m.fn}${dimension ? ` (${m.column} is now a measure)` : ''}`,
    };
  }
  const problem = nameProblem(draft.snapshot, 'row', m.name);
  if (problem !== null) throw new CubeRefusal(problem);
  const copy: DerivedColumn = {
    ...draftColumn(newDraft(m.name, '', 'measure'), lambda(['x'], property(variable('x'), m.column))),
    // typed as its column is, so its Aggregation is offered by its type now (the compiler types it again on refresh)
    ...(column.type !== undefined ? { type: column.type } : {}),
  };
  const d = setColumn({ ...draft, snapshot: { ...draft.snapshot, derived: [...draft.snapshot.derived, copy] } },
    copy.name, change);
  return {
    draft: d, made: { measure: m, column: copy.name },
    note: `measure ${m.name} is now a calculated column of ${m.column}, its Aggregation ${m.fn}`,
  };
}

/**
 * A LIST OF MEASURES AS THE SCREEN'S MODEL: each by measureAsColumn, in order, against what the ones before it made (a
 * column given an Aggregation once is taken: another measure of it is a calculated column). One it cannot be is left
 * out, said, the rest made: a cube saved with a measures list opens (cube-document.ts openCube), and Open in Grid makes
 * what it can of a chart's values.
 */
export function measuresAsColumns<D extends GridDraft>(draft: D, measures: readonly Measure[]): {
  readonly draft: D;
  readonly made: readonly MeasureMade[];
  readonly notes: readonly string[];
} {
  let d = draft;
  const made: MeasureMade[] = [];
  const notes: string[] = [];
  const taken = new Set<string>();
  for (const m of measures) {
    try {
      const one = measureAsColumn(d, m, taken);
      d = one.draft;
      if (one.made.column === m.column) taken.add(m.column);
      made.push(one.made);
      notes.push(one.note);
    } catch (e) {
      if (!(e instanceof CubeRefusal)) throw e;
      notes.push(`left out measure ${m.name}: ${e.message}`);
    }
  }
  return { draft: d, made, notes };
}
