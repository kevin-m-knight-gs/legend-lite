// A GRID EDIT AS A VALUE (docs/DATACUBE_COMMANDS_STAGE1_2026_10_18.md; the command program's stage 1): each edit of a
// grid a person or a program makes is a GridCommand -- plain JSON, so Python sends it and a live channel can carry it --
// and one pure function applies it, by grid-edits.ts and config.ts, the screen's own rules. The screen builds the
// command from a click, Python from a call; neither holds a rule of its own, so the two cannot make one edit two ways.
// No DOM, no planner: what needs the compiler (a calculated column's parse) is done before the command is built.

import { nameProblem } from './calc.ts';
import {
  APPEARANCE_SETTINGS, COLUMN_SETTINGS, CUBE_SETTINGS, columnConfig, withConfiguration, type CubeConfiguration, type Patch,
} from './config.ts';
import {
  addSort, allColumns, clickSort, clickTreeSort, measureAsColumn, pivotHeaderMeasure, setColumn, setGrouping, setPivot,
  setSorts, spreadMeasureNames, type ColumnChange, type GridDraft,
} from './grid-edits.ts';
import type { Lambda } from '../../pure-protocol/src/index.ts';
import { operandKind } from './filter-operators.ts';
import {
  AGGREGATE_FNS, COLUMN_KINDS, CubeRefusal, FILTER_OPERATORS, SORT_DIRECTIONS, isJsonValue, isPivotTotalColumn,
  isRelativeDate, pivotTotalColumn,
  type AggregateFn, type ColumnKind, type DerivedColumn, type FilterNode, type FilterOperator, type FilterValue,
  type Measure, type SortDirection, type SortSpec,
} from './snapshot.ts';
import { TREE_COLUMN } from './treeview.ts';

/** Settings by their document's own keys, each cleared by null (a value JSON can carry, as undefined is not). */
export type SettingsPatch = Readonly<Record<string, unknown>>;

/** One edit of a grid. */
export type GridCommand =
  /** The row groups, whole (the Rows zone, the menu's Vertical Pivot, Python's group). */
  | { readonly kind: 'group'; readonly rows: readonly string[] }
  /** The pivot, whole (the Columns zone, the menu's Horizontal Pivot, Python's pivot). */
  | { readonly kind: 'pivot'; readonly columns: readonly string[] }
  /** The sorts, whole (the Sorts panel, the menu's Ascending, Descending and Clear). */
  | { readonly kind: 'sorts'; readonly sorts: readonly SortSpec[] }
  /** A sort added last (the menu's Add Ascending and Add Descending, Python's sort). */
  | { readonly kind: 'addSort'; readonly column: string; readonly direction: SortDirection }
  /**
   * A header click: alone, or chained after the other sorts. `first` is the direction a click on it starts with, which
   * the type the compiler gave the header decides (grid-edits.ts firstSortDirection): a pivot's header, or a count of
   * text, is a number though no column of the query is -- so the screen, which has the view, says it.
   */
  | { readonly kind: 'clickSort'; readonly column: string; readonly first: SortDirection; readonly chained: boolean }
  /** A click on the tree column's header. */
  | { readonly kind: 'clickTreeSort'; readonly chained: boolean }
  /** Column Properties: a column's kind, Aggregation, weighted average's weight (null clears the last two). */
  | { readonly kind: 'column'; readonly name: string; readonly change: ColumnChange }
  /** Settings, an appearance, a column's presentation, in the saved document's keys (null clears one). */
  | { readonly kind: 'configure'; readonly patch: SettingsPatch }
  /** The filter, whole (the filter editor, Python's filter); null takes it off. */
  | { readonly kind: 'filter'; readonly filter: FilterNode | null }
  /** One condition added to the filter (a cell's menu): and-ed with what it had. */
  | { readonly kind: 'addFilter'; readonly condition: FilterNode }
  /** A calculated column added at its level, its lambda already parsed (the column editor, Python's calculate). */
  | { readonly kind: 'calculate'; readonly level: 'row' | 'group'; readonly column: DerivedColumn }
  /** Measures as columns (Python's measure and grid(measures=...)): an Aggregation, or a copy under its own name. */
  | { readonly kind: 'measures'; readonly measures: readonly Measure[] };

export const GRID_COMMAND_KINDS = [
  'group', 'pivot', 'sorts', 'addSort', 'clickSort', 'clickTreeSort', 'column', 'configure', 'filter', 'addFilter',
  'calculate', 'measures',
] as const satisfies readonly GridCommand['kind'][];

/** A command applied: the grid as the screen's rules leave it, or a refusal in their words (CubeRefusal). */
export function applyGridCommand<D extends GridDraft>(draft: D, command: GridCommand): D {
  switch (command.kind) {
    case 'group':
      return setGrouping(draft, command.rows);
    case 'pivot':
      return setPivot(draft, command.columns);
    case 'sorts':
      return setSorts(draft, command.sorts);
    case 'addSort':
      return addSort(draft, command.column, command.direction);
    case 'clickSort':
      return clickSort(draft, command.column, command.first, command.chained);
    case 'clickTreeSort':
      return clickTreeSort(draft, command.chained);
    case 'column':
      return setColumn(draft, command.name, command.change);
    case 'configure':
      return configured(draft, command.patch);
    case 'filter': {
      const { filter: _was, ...rest } = draft.snapshot;
      return { ...draft, snapshot: command.filter === null ? rest : { ...rest, filter: command.filter } };
    }
    case 'addFilter': {
      // ADDS to the filter rather than replacing it, which is what "Add Filter" says and what makes the entry usable
      // twice in a row: the new condition joins the existing tree with AND; an OR is the filter editor's job
      const was = draft.snapshot.filter;
      const filter: FilterNode = was
        ? was.kind === 'and' ? { kind: 'and', children: [...was.children, command.condition] }
          : { kind: 'and', children: [was, command.condition] }
        : command.condition;
      return { ...draft, snapshot: { ...draft.snapshot, filter } };
    }
    case 'calculate': {
      const problem = nameProblem(draft.snapshot, command.level, command.column.name);
      if (problem !== null) throw new CubeRefusal(problem);
      // as the column editor's Apply sets the cube's two lists (app.ts #setCalc): both written, each whole
      const row = [...draft.snapshot.derived, ...(command.level === 'row' ? [command.column] : [])];
      const group = [...(draft.snapshot.groupDerived ?? []), ...(command.level === 'group' ? [command.column] : [])];
      return { ...draft, snapshot: { ...draft.snapshot, derived: row, groupDerived: group } };
    }
    case 'measures': {
      // each as Python's measure() asks it, refused in words (a converting open is lenient; a command is not): a column
      // a measure of the list already aggregates is taken, so another of it is a copy under its own name
      let d = draft;
      const taken = new Set<string>();
      for (const m of command.measures) {
        const one = measureAsColumn(d, m, taken);
        if (one.made.column === m.column) taken.add(m.column);
        d = one.draft;
      }
      return d;
    }
  }
}

/** Commands applied in order: the first refusal stops them, in its words. */
export function applyGridCommands<D extends GridDraft>(draft: D, commands: readonly GridCommand[]): D {
  return commands.reduce<D>((d, c) => applyGridCommand(d, c), draft);
}

// ---------------------------------------------------------------- an editor's draft, as commands

/**
 * THE COMMANDS AN EDITOR'S DRAFT MAKES (the Properties window's Apply, app.ts #applyDraft): what changed between `base`,
 * the grid the window opened on, and `edited`, its draft -- so, applied to the grid as it is NOW, they land only what the
 * window changed, and an edit another window made meanwhile stays. In the order a person makes them: a column made a
 * dimension (it can then be grouped); the row groups and the pivot; the other kinds, Aggregations and weights (a column
 * ungrouped can then be a measure); the settings the draft changed beyond what those set (null clears one); the sorts
 * last (one may name a pivot's header the settings decide). A list is set whole; the settings key by key -- the grid's,
 * its appearance's, each column's. Refused, in the screen's words, when the draft is not one its rules reach.
 */
export function draftCommands(base: GridDraft, edited: GridDraft): GridCommand[] {
  const same = (a: unknown, b: unknown): boolean => JSON.stringify(a) === JSON.stringify(b);
  // Column Properties: a kind, an Aggregation, a weight -- by the column command, its rules setColumn's
  const early: GridCommand[] = [];
  const late: GridCommand[] = [];
  for (const name of new Set([...Object.keys(base.config.columns), ...Object.keys(edited.config.columns)])) {
    const was = columnConfig(base.config, name);
    const now = columnConfig(edited.config, name);
    const change: ColumnChange = {
      // a kind is set, never cleared: one cleared is left to configure, which refuses it in words
      ...(now.kind !== undefined && now.kind !== was.kind ? { kind: now.kind } : {}),
      ...(!same(now.aggregateFn, was.aggregateFn) ? { aggregate: now.aggregateFn ?? null } : {}),
      ...(!same(now.aggregationParameters, was.aggregationParameters) ? { weight: now.aggregationParameters?.[0] ?? null } : {}),
    };
    if (Object.keys(change).length === 0) continue;
    (change.kind === 'dimension' ? early : late).push({ kind: 'column', name, change });
  }
  const structure: GridCommand[] = [
    ...early,
    ...(same(base.snapshot.rows, edited.snapshot.rows) ? [] : [{ kind: 'group', rows: [...edited.snapshot.rows] } as const]),
    ...(same(base.snapshot.pivotOn, edited.snapshot.pivotOn) ? [] : [{ kind: 'pivot', columns: [...edited.snapshot.pivotOn] } as const]),
    ...late,
  ];
  // the settings beyond those: what the draft has that the grid it opened on, so edited, has not
  const patch = settingsPatch(applyGridCommands(base, structure).config, edited.config);
  return [
    ...structure,
    ...(patch === undefined ? [] : [{ kind: 'configure', patch } as const]),
    ...(same(base.snapshot.sorts, edited.snapshot.sorts) ? []
      : [{ kind: 'sorts', sorts: edited.snapshot.sorts.map((x) => ({ column: x.column, direction: x.direction })) } as const]),
  ];
}

/** What `to` sets that `from` has not, as a configure command's patch (null clears a setting), or undefined: nothing. */
function settingsPatch(from: CubeConfiguration, to: CubeConfiguration): SettingsPatch | undefined {
  const same = (a: unknown, b: unknown): boolean => JSON.stringify(a) === JSON.stringify(b);
  const changed = (a: object, b: object): Record<string, unknown> | undefined => {
    const was = a as Record<string, unknown>;
    const now = b as Record<string, unknown>;
    const out: Record<string, unknown> = {};
    for (const key of new Set([...Object.keys(was), ...Object.keys(now)])) {
      if (!same(was[key], now[key])) out[key] = now[key] === undefined ? null : now[key];
    }
    return Object.keys(out).length > 0 ? out : undefined;
  };
  const { columns: fromColumns, appearance: fromAppearance, ...fromSettings } = from;
  const { columns: toColumns, appearance: toAppearance, ...toSettings } = to;
  const settings = changed(fromSettings, toSettings);
  const appearance = changed(fromAppearance, toAppearance);
  const columns: Record<string, unknown> = {};
  for (const name of new Set([...Object.keys(fromColumns), ...Object.keys(toColumns)])) {
    const one = changed(columnConfig(from, name), columnConfig(to, name));
    if (one !== undefined) columns[name] = one;
  }
  const patch = {
    ...settings,
    ...(appearance !== undefined ? { appearance } : {}),
    ...(Object.keys(columns).length > 0 ? { columns } : {}),
  };
  return Object.keys(patch).length > 0 ? patch : undefined;
}

// ---------------------------------------------------------------- configure

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

/**
 * Settings patched as a host patches them (config.ts withConfiguration, CubeApp.applyConfiguration's rule), in the
 * saved document's own keys: null clears a setting, among the settings, in the appearance and in a column's. A column's
 * kind, Aggregation and weight go by Column Properties' own rules (setColumn), so what the panel refuses is refused here
 * too: a kind is set, never cleared, and whether a measure is kept out of the pivot is true or false. A name DataCube
 * does not have (a setting misspelt, a column the grid has not) is refused, never kept where nothing reads it.
 */
function configured<D extends GridDraft>(draft: D, given: SettingsPatch): D {
  if (!isObject(given)) throw new CubeRefusal("a grid's configuration is its settings by name, in its document's own keys");
  const cleared = (o: Record<string, unknown>): Record<string, unknown> =>
    Object.fromEntries(Object.entries(o).map(([k, v]) => [k, v === null ? undefined : v]));
  const patched = (d: D, patch: Record<string, unknown>): D =>
    ({ ...d, config: withConfiguration(d.config, patch as Patch<CubeConfiguration>) });
  const { columns, appearance, ...settings } = given;
  const known = (keys: readonly string[], settings: object, what: string): void => {
    const odd = keys.find((k) => !Object.hasOwn(settings, k));
    if (odd !== undefined) throw new CubeRefusal(`'${odd}' is not ${what} (${Object.keys(settings).join(', ')})`);
  };
  known(Object.keys(settings), CUBE_SETTINGS, "a grid's setting");
  if (appearance !== undefined && !isObject(appearance)) {
    throw new CubeRefusal("a grid's appearance is its settings by name, each cleared by null, not cleared whole");
  }
  if (isObject(appearance)) known(Object.keys(appearance), APPEARANCE_SETTINGS, 'an appearance setting');
  let d = patched(draft, { ...cleared(settings), ...(isObject(appearance) ? { appearance: cleared(appearance) } : {}) });
  if (columns === undefined) return d;
  if (!isObject(columns)) throw new CubeRefusal("a grid's columns' settings are by name: {name: {setting: value}}");
  for (const [name, c] of Object.entries(columns)) {
    const named = allColumns(d);
    // a pivot total is a column of its own (its heatmap, width, hiding: the screen's menu offers them), of a measure
    // column the grid has or of the count a pivot of no measure fills with; so is that count (its heatmap, which its
    // pivot's columns show), a pivot value's column while the grid pivots (its hiding), and the tree column (its width,
    // hiding) -- each its presentation alone: a header's kind and Aggregation are its measure's
    const own = named.includes(name);
    const spread = spreadMeasureNames(d);
    const filled = !own && spread.includes(name);
    const total = !own && isPivotTotalColumn(name) ? name.slice(pivotTotalColumn('').length) : undefined;
    const header = !own && !filled && total === undefined ? pivotHeaderMeasure(d, name) : undefined;
    const tree = !own && name === TREE_COLUMN;
    const totalled = total !== undefined && (named.includes(total) || spread.includes(total));
    if (!own && !filled && !totalled && header === undefined && !tree) {
      throw new CubeRefusal(`'${name}' is not a column of the grid (${named.join(', ')})`);
    }
    if (!isObject(c)) throw new CubeRefusal(`a column's settings are an object, not ${JSON.stringify(c)}`);
    known(Object.keys(c), COLUMN_SETTINGS, "a column's setting");
    const { kind, aggregateFn, aggregationParameters, excludedFromPivot, ...presentation } = c;
    if (!own && [kind, aggregateFn, aggregationParameters, excludedFromPivot].some((x) => x !== undefined)) {
      const of = total ?? header;
      throw new CubeRefusal(of !== undefined
        ? `${name} is ${total !== undefined ? 'the pivot total' : 'a pivot column'} of '${of}': its kind, Aggregation `
          + `and pivot exclusion are '${of}''s, set on '${of}'`
        : `${name} is ${tree ? "the grid's tree column" : 'the count a pivot of no measure fills with'}: it has no kind, `
          + 'Aggregation or pivot exclusion');
    }
    if (kind === null) {
      throw new CubeRefusal(`${name}'s kind is set, never cleared: 'dimension' or 'measure' (Column Properties > Column Kind)`);
    }
    if (excludedFromPivot !== undefined && typeof excludedFromPivot !== 'boolean') {
      throw new CubeRefusal(`${name}'s excludedFromPivot is true or false, not ${JSON.stringify(excludedFromPivot)}`);
    }
    if (kind !== undefined || aggregateFn !== undefined || aggregationParameters !== undefined) {
      d = setColumn(d, name, {
        ...(kind !== undefined ? { kind: kindFrom(kind) } : {}),
        ...(aggregateFn !== undefined ? { aggregate: aggregateFn === null ? null : aggregateFrom(aggregateFn) } : {}),
        ...(aggregationParameters !== undefined ? { weight: weightFrom(aggregationParameters, name) } : {}),
      });
    }
    d = patched(d, { columns: { [name]: { ...cleared(presentation), ...(excludedFromPivot !== undefined ? { excludedFromPivot } : {}) } } });
  }
  return d;
}

// ---------------------------------------------------------------- reading a command from JSON

/** A field as text, or why not. */
function textFrom(v: unknown, what: string): string {
  if (typeof v !== 'string') throw new CubeRefusal(`${what} is text, not ${JSON.stringify(v)}`);
  return v;
}

/** A list of the grid's columns (a part named `what`), or why not: the edit judges the names. */
function namesFrom(given: unknown, what: string): string[] {
  if (!Array.isArray(given) || !given.every((n) => typeof n === 'string')) {
    throw new CubeRefusal(`${what} is a list of the grid's columns, not ${JSON.stringify(given)}`);
  }
  return given;
}

/** One of DataCube's aggregates, or why not. */
function aggregateFrom(fn: unknown): AggregateFn {
  if (typeof fn !== 'string' || !AGGREGATE_FNS.includes(fn as AggregateFn)) {
    throw new CubeRefusal(`${JSON.stringify(fn)} is not one of DataCube's aggregates: ${AGGREGATE_FNS.join(', ')}`);
  }
  return fn as AggregateFn;
}

/** A column's kind, or why not. */
function kindFrom(kind: unknown): ColumnKind {
  if (typeof kind !== 'string' || !COLUMN_KINDS.includes(kind as ColumnKind)) {
    throw new CubeRefusal(`a column's kind is ${COLUMN_KINDS.map((k) => `'${k}'`).join(' or ')}, not ${JSON.stringify(kind)}`);
  }
  return kind as ColumnKind;
}

/** A weighted average's parameters as the document keeps them, `[weight column]` -- or null, cleared -- or why not. */
function weightFrom(given: unknown, name: string): string | null {
  if (given === null) return null;
  if (!Array.isArray(given) || given.length !== 1 || typeof given[0] !== 'string') {
    throw new CubeRefusal(`${name}'s aggregationParameters is [its weight column], not ${JSON.stringify(given)}`);
  }
  return given[0];
}

/** A sort direction, or why not. */
function directionFrom(v: unknown): SortDirection {
  if (typeof v !== 'string' || !SORT_DIRECTIONS.includes(v as SortDirection)) {
    // in setSorts' own words, as Python has always been told
    throw new CubeRefusal(`a sort is ${SORT_DIRECTIONS.map((d) => `'${d}'`).join(' or ')}, not '${String(v)}'`);
  }
  return v as SortDirection;
}

/** A command's fields, exactly: a field it does not take is refused, never passed over. */
function fields(o: Record<string, unknown>, kind: string, allowed: readonly string[], what = `a '${kind}' command`): void {
  const odd = Object.keys(o).filter((k) => k !== 'kind' && !allowed.includes(k));
  if (odd.length > 0) throw new CubeRefusal(`${what} takes ${allowed.join(', ') || 'nothing more'}, not ${odd.join(', ')}`);
}

/** A filter value, or why not: text, a number, true or false, a date relative to today or now, or a JSON document. */
function filterValueFrom(v: unknown): FilterValue {
  if (typeof v === 'string' || typeof v === 'boolean' || (typeof v === 'number' && Number.isFinite(v))
    || isRelativeDate(v) || isJsonValue(v)) return v;
  throw new CubeRefusal(`a filter's value is text, a number, true or false, {relative} or {json}, not ${JSON.stringify(v)}`);
}

/**
 * A filter read field by field -- a condition (its operator one of DataCube's, its operand the one the operator takes:
 * none, a value, a list, another column), a group of them, a not -- or why not. What it names (a column the grid has)
 * is the apply's to judge.
 */
function filterFrom(v: unknown): FilterNode {
  if (!isObject(v)) throw new CubeRefusal(`a filter is a condition, a group or a not, not ${JSON.stringify(v)}`);
  switch (v['kind']) {
    case 'condition': {
      fields(v, 'condition', ['column', 'operator', 'value', 'rightColumn'], 'a condition');
      const operator = v['operator'];
      if (typeof operator !== 'string' || !FILTER_OPERATORS.includes(operator as FilterOperator)) {
        throw new CubeRefusal(`a condition's operator is one of ${FILTER_OPERATORS.join(', ')}, not ${JSON.stringify(operator)}`);
      }
      const op = operator as FilterOperator;
      const takes = operandKind(op);
      const { value, rightColumn } = v;
      const fits = takes === 'none' ? value === undefined && rightColumn === undefined
        : takes === 'single' ? value !== undefined && !Array.isArray(value) && rightColumn === undefined
          : takes === 'list' ? Array.isArray(value) && rightColumn === undefined
            : rightColumn !== undefined && value === undefined;
      if (!fits) {
        const operand = { none: 'no operand', single: 'a value', list: 'a list of values', column: 'another column (rightColumn)' };
        throw new CubeRefusal(`'${op}' takes ${operand[takes]}`);
      }
      return {
        kind: 'condition', column: textFrom(v['column'], "a condition's column"), operator: op,
        ...(value !== undefined ? { value: Array.isArray(value) ? value.map(filterValueFrom) : filterValueFrom(value) } : {}),
        ...(rightColumn !== undefined ? { rightColumn: textFrom(rightColumn, "a condition's other column") } : {}),
      };
    }
    case 'and':
    case 'or': {
      fields(v, v['kind'], ['children'], 'a group');
      if (!Array.isArray(v['children'])) throw new CubeRefusal("a group's children are a list of filters");
      return { kind: v['kind'], children: v['children'].map(filterFrom) };
    }
    case 'not':
      fields(v, 'not', ['child'], 'a not');
      return { kind: 'not', child: filterFrom(v['child']) };
    default:
      throw new CubeRefusal(`a filter is a condition, an and, an or or a not, not ${JSON.stringify(v['kind'])}`);
  }
}

/**
 * A calculated column read from JSON: a formula's -- its name (trimmed, as the editor takes one), its lambda (the
 * protocol's, its body the planner's to judge), its kind -- or why not. A window or child-aggregate column is the
 * screen's column editor's; none is read from JSON yet.
 */
function calculatedFrom(v: unknown): DerivedColumn {
  if (!isObject(v)) throw new CubeRefusal(`a calculated column is {name, lambda, kind}, not ${JSON.stringify(v)}`);
  fields(v, 'column', ['name', 'lambda', 'kind', 'type'], 'a calculated column from JSON');
  const lambda = v['lambda'];
  if (!isObject(lambda) || lambda['_type'] !== 'lambda' || !Array.isArray(lambda['body'])) {
    throw new CubeRefusal(`a calculated column's lambda is the protocol's {_type: 'lambda', body, parameters}, not ${JSON.stringify(lambda)}`);
  }
  return {
    name: textFrom(v['name'], "a calculated column's name").trim(),
    lambda: lambda as unknown as Lambda,
    ...(v['kind'] !== undefined ? { kind: kindFrom(v['kind']) } : {}),
    ...(v['type'] !== undefined ? { type: textFrom(v['type'], "a calculated column's type") } : {}),
  };
}

/**
 * A command read from JSON (Python's, a live channel's): its kind one this module applies, its fields exactly those
 * the kind takes, each of its type -- or refused in words. What the fields NAME (a column the grid has, a setting
 * DataCube knows) is the apply's to judge, as it judges the screen's.
 */
export function readGridCommand(given: unknown): GridCommand {
  if (!isObject(given) || typeof given['kind'] !== 'string') {
    throw new CubeRefusal(`a grid's command is {kind, ...}, its kind one of ${GRID_COMMAND_KINDS.join(', ')}, not ${JSON.stringify(given)}`);
  }
  const o = given;
  const kind = o['kind'] as string;
  switch (kind) {
    case 'group':
      fields(o, kind, ['rows']);
      return { kind, rows: namesFrom(o['rows'], 'the row groups') };
    case 'pivot':
      fields(o, kind, ['columns']);
      return { kind, columns: namesFrom(o['columns'], 'the pivot') };
    case 'sorts': {
      fields(o, kind, ['sorts']);
      if (!Array.isArray(o['sorts'])) throw new CubeRefusal('the sorts are a list');
      return { kind, sorts: o['sorts'].map((x) => {
        if (!isObject(x)) throw new CubeRefusal(`a sort is {column, direction}, not ${JSON.stringify(x)}`);
        return { column: textFrom(x['column'], 'a sort'), direction: directionFrom(x['direction']) };
      }) };
    }
    case 'addSort':
      fields(o, kind, ['column', 'direction']);
      return { kind, column: textFrom(o['column'], 'a sort'), direction: directionFrom(o['direction']) };
    case 'clickSort':
      fields(o, kind, ['column', 'first', 'chained']);
      if (typeof o['chained'] !== 'boolean') throw new CubeRefusal('a header click is chained or not: true or false');
      if (o['first'] === undefined) throw new CubeRefusal("a header click says the direction it starts with: first, 'asc' or 'desc'");
      return { kind, column: textFrom(o['column'], 'a header click'), first: directionFrom(o['first']), chained: o['chained'] };
    case 'clickTreeSort':
      fields(o, kind, ['chained']);
      if (typeof o['chained'] !== 'boolean') throw new CubeRefusal('a tree header click is chained or not: true or false');
      return { kind, chained: o['chained'] };
    case 'column': {
      fields(o, kind, ['name', 'change']);
      const change = o['change'];
      if (!isObject(change)) throw new CubeRefusal(`a column's properties are {kind, aggregate, weight}, not ${JSON.stringify(change)}`);
      const { kind: k, aggregate, weight, ...other } = change;
      const unknown = Object.keys(other);
      if (unknown.length > 0) throw new CubeRefusal(`a column's properties are kind, aggregate and weight, not ${unknown.join(', ')}`);
      // null clears an Aggregation or a weight, as the panel's "none" does
      return { kind, name: textFrom(o['name'], "a column's name"), change: {
        ...(k !== undefined ? { kind: kindFrom(k) } : {}),
        ...(aggregate !== undefined ? { aggregate: aggregate === null ? null : aggregateFrom(aggregate) } : {}),
        ...(weight !== undefined ? { weight: weight === null ? null : textFrom(weight, "a column's weight") } : {}),
      } };
    }
    case 'configure':
      fields(o, kind, ['patch']);
      if (!isObject(o['patch'])) throw new CubeRefusal("a grid's configuration is its settings by name, in its document's own keys");
      return { kind, patch: o['patch'] };
    case 'filter':
      fields(o, kind, ['filter']);
      return { kind, filter: o['filter'] === null ? null : filterFrom(o['filter']) };
    case 'addFilter':
      fields(o, kind, ['condition']);
      if (!isObject(o['condition']) || o['condition']['kind'] !== 'condition') {
        throw new CubeRefusal(`a filter added is one condition, not ${JSON.stringify(o['condition'])}`);
      }
      return { kind, condition: filterFrom(o['condition']) };
    case 'calculate':
      fields(o, kind, ['level', 'column']);
      if (o['level'] !== 'row' && o['level'] !== 'group') throw new CubeRefusal(`a calculated column's level is 'row' or 'group', not ${JSON.stringify(o['level'])}`);
      return { kind, level: o['level'], column: calculatedFrom(o['column']) };
    case 'measures': {
      fields(o, kind, ['measures']);
      if (!Array.isArray(o['measures'])) throw new CubeRefusal('the measures are a list');
      return { kind, measures: o['measures'].map((m) => {
        if (!isObject(m)) throw new CubeRefusal(`a measure is {name, column, fn[, weight]}, not ${JSON.stringify(m)}`);
        return {
          name: textFrom(m['name'], "a measure's name").trim(),
          column: textFrom(m['column'], "a measure's column"),
          fn: aggregateFrom(m['fn']),
          ...(m['weight'] !== undefined ? { weight: textFrom(m['weight'], "a measure's weight") } : {}),
        };
      }) };
    }
    default:
      throw new CubeRefusal(`'${kind}' is not a grid's command: ${GRID_COMMAND_KINDS.join(', ')}`);
  }
}
