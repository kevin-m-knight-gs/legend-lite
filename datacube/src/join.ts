// JOINING TWO GRIDS' RESULTS (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §17): how two inputs combine -- a join on any
// condition (four kinds; every pair when it names none), an as-of join, my rows that have a match or have none, the
// rows of both appended -- read strictly as a join source keeps it (readJoinHow), what it becomes as one relation
// (joinRelation), the names it gives their columns (theirNames), and the queries the Join dialog runs before it is
// applied (joinSummary). No DOM, no planner: tested alone, and planned on DuckDB (test/join-open.test.ts).

import {
  ProtocolError, agg, and, cast, collection, derive, eq, fn, from, ge, gt, isIdentifier, lambda, le, lit, lt, ne, property,
  readLambda, type, variable, type JoinKind, type Lambda, type Relation, type ValueSpecification,
} from '../../pure-protocol/src/index.ts';
import { rowCountLambda } from './query.ts';
import { CubeRefusal } from './snapshot.ts';

/** A join's kind: matching rows only, all of mine, all of theirs, everything. */
export type JoinKindWord = 'inner' | 'left' | 'right' | 'full';
export const JOIN_KINDS: readonly JoinKindWord[] = ['inner', 'left', 'right', 'full'];

/** A row's comparison. */
export type JoinOp = '==' | '!=' | '<' | '<=' | '>' | '>=';
export const JOIN_OPS: readonly JoinOp[] = ['==', '!=', '<', '<=', '>', '>='];

/** Which way an as-of join's nearest row is: at or before mine (`>=`), strictly before (`>`), at or after, after. */
export type AsOfOp = '>=' | '<=' | '>' | '<';
export const AS_OF_OPS: readonly AsOfOp[] = ['>=', '<=', '>', '<'];

/** A row of a condition: my column, a comparison, their column -- each by the name its own input gives it. */
export interface JoinRow {
  readonly left: string;
  readonly op: JoinOp;
  readonly right: string;
}

/**
 * What pairs a row of mine with one of theirs: rows, ANDed (none: every pair), or a Pure `{a, b | ...}`, parsed first.
 * In a join or an as-of join a Pure condition names their columns as they come out of it (a name mine has, prefixed:
 * theirNames); in a semi- or anti-join, by their own names.
 */
export type JoinCondition = { readonly rows: readonly JoinRow[] } | { readonly lambda: Lambda };

/** How two inputs combine (§17.2). */
export type JoinHow =
  | {
    readonly form: 'join'; readonly kind: JoinKindWord; readonly on: JoinCondition; readonly prefix: string;
    /** Their columns brought over, in their order; null: all. */
    readonly keep: readonly string[] | null;
  }
  | {
    readonly form: 'asOf'; readonly match: JoinRow & { readonly op: AsOfOp }; readonly on: JoinCondition;
    readonly prefix: string; readonly keep: readonly string[] | null;
  }
  /** My rows that have a match (semi), or that have none (anti): my columns only, each row once. */
  | { readonly form: 'semi' | 'anti'; readonly on: JoinCondition }
  /** The rows of both, columns matched by name: a column one side lacks is empty on its rows. */
  | { readonly form: 'append' };

export type JoinForm = JoinHow['form'];
export const JOIN_FORMS: readonly JoinForm[] = ['join', 'asOf', 'semi', 'anti', 'append'];

/** An input as the join reads it: what it is called, its relation, and its columns as the compiler typed them. */
export interface JoinSide {
  readonly name: string;
  readonly relation: ValueSpecification;
  readonly columns: readonly { readonly name: string; readonly type: string }[];
}

/** The prefix a new join puts on their names that mine has. */
export const DEFAULT_PREFIX = 'their_';

// ---------------------------------------------------------------- reading, as a join source keeps it

function object(raw: unknown, what: string, allowed: readonly string[]): Record<string, unknown> {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) throw new CubeRefusal(`${what} is an object, not ${JSON.stringify(raw)}`);
  const o = raw as Record<string, unknown>;
  const odd = Object.keys(o).filter((k) => !allowed.includes(k));
  if (odd.length > 0) throw new CubeRefusal(`${what} takes ${allowed.join(', ')}, not ${odd.join(', ')}`);
  return o;
}

function name(raw: unknown, what: string): string {
  if (typeof raw !== 'string' || raw === '') throw new CubeRefusal(`${what} names a column, not ${JSON.stringify(raw)}`);
  return raw;
}

function readRow<Op extends string>(raw: unknown, what: string, ops: readonly Op[]): { left: string; op: Op; right: string } {
  const o = object(raw, what, ['left', 'op', 'right']);
  if (typeof o['op'] !== 'string' || !(ops as readonly string[]).includes(o['op'])) {
    throw new CubeRefusal(`${what}'s comparison is one of ${ops.join(' ')}, not ${JSON.stringify(o['op'])}`);
  }
  return { left: name(o['left'], `${what}'s left`), op: o['op'] as Op, right: name(o['right'], `${what}'s right`) };
}

function readCondition(raw: unknown, what: string): JoinCondition {
  const o = object(raw, what, ['rows', 'lambda']);
  if ('rows' in o === 'lambda' in o) throw new CubeRefusal(`${what} is rows or a lambda, one of them`);
  if ('rows' in o) {
    if (!Array.isArray(o['rows'])) throw new CubeRefusal(`${what}'s rows are a list`);
    return { rows: o['rows'].map((r, i) => readRow(r, `${what}'s row ${i + 1}`, JOIN_OPS)) };
  }
  let read: Lambda;
  try {
    read = readLambda(o['lambda']);
  } catch (e) {
    if (e instanceof ProtocolError) throw new CubeRefusal(`${what}'s lambda: ${e.message}`);
    throw e;
  }
  const params = read.parameters.map((p) => p.name);
  if (params.length !== 2 || params[0] === params[1]) {
    throw new CubeRefusal(`${what}'s lambda takes two rows, mine and theirs, by two names: {a, b | ...}`);
  }
  return { lambda: read };
}

function readKeep(raw: unknown): readonly string[] | null {
  if (raw === null) return null;
  if (!Array.isArray(raw)) throw new CubeRefusal(`a join's kept columns are a list, or null for all, not ${JSON.stringify(raw)}`);
  const keep = raw.map((c) => name(c, 'a kept column'));
  const twice = keep.find((c, i) => keep.indexOf(c) !== i);
  if (twice !== undefined) throw new CubeRefusal(`a join keeps ${twice} twice`);
  return keep;
}

function readPrefix(raw: unknown): string {
  if (typeof raw !== 'string' || !isIdentifier(raw)) throw new CubeRefusal(`a join's prefix starts a column's name (c_), not ${JSON.stringify(raw)}`);
  return raw;
}

/** How two inputs combine, read field by field as a join source keeps it, or why not (CubeRefusal). */
export function readJoinHow(raw: unknown): JoinHow {
  const form = (raw as { form?: unknown } | null)?.form;
  switch (form) {
    case 'join': {
      const o = object(raw, 'a join', ['form', 'kind', 'on', 'prefix', 'keep']);
      if (typeof o['kind'] !== 'string' || !(JOIN_KINDS as readonly string[]).includes(o['kind'])) {
        throw new CubeRefusal(`a join's kind is one of ${JOIN_KINDS.join(', ')}, not ${JSON.stringify(o['kind'])}`);
      }
      return {
        form, kind: o['kind'] as JoinKindWord, on: readCondition(o['on'], "a join's condition"), prefix: readPrefix(o['prefix']),
        keep: readKeep(o['keep']),
      };
    }
    case 'asOf': {
      const o = object(raw, 'an as-of join', ['form', 'match', 'on', 'prefix', 'keep']);
      return {
        form, match: readRow(o['match'], "an as-of join's match", AS_OF_OPS), on: readCondition(o['on'], "an as-of join's condition"),
        prefix: readPrefix(o['prefix']), keep: readKeep(o['keep']),
      };
    }
    case 'semi':
    case 'anti': {
      const o = object(raw, `a ${form}-join`, ['form', 'on']);
      return { form, on: readCondition(o['on'], `a ${form}-join's condition`) };
    }
    case 'append':
      object(raw, 'an append', ['form']);
      return { form };
    default:
      throw new CubeRefusal(`a join's form is one of ${JOIN_FORMS.join(', ')}, not ${JSON.stringify(form)}`);
  }
}

// ---------------------------------------------------------------- what it becomes

const KIND: Readonly<Record<JoinKindWord, JoinKind>> = { inner: 'INNER', left: 'LEFT', right: 'RIGHT', full: 'FULL' };

/**
 * Their columns' names as a join or an as-of join brings them out: each one mine has, prefixed. Refused when the
 * prefixed name is one either side has already (a prefix to choose again).
 */
export function theirNames(mine: readonly string[], theirs: readonly string[], prefix: string): ReadonlyMap<string, string> {
  const taken = new Set(mine);
  const out = new Map<string, string>();
  for (const c of theirs) {
    if (!taken.has(c)) {
      out.set(c, c);
      continue;
    }
    const renamed = `${prefix}${c}`;
    if (taken.has(renamed) || theirs.includes(renamed)) {
      throw new CubeRefusal(`their ${c} would be ${renamed}, a name the join has already: choose another prefix`);
    }
    out.set(c, renamed);
  }
  return out;
}

function has(side: JoinSide, column: string): void {
  if (!side.columns.some((c) => c.name === column)) throw new CubeRefusal(`${side.name} has no column '${column}'`);
}

/** A row as a term over `$a` (mine) and `$b` (theirs, named `right`): an equal key that is empty matches no row. */
function rowTerm(row: JoinRow, right: string): ValueSpecification {
  const a = property(variable('a'), row.left);
  const b = property(variable('b'), right);
  switch (row.op) {
    // Pure's == matches an empty with an empty: a key matches only when it has a value (§17.2)
    case '==': return and(fn('isNotEmpty', a), eq(a, b));
    case '!=': return ne(a, b);
    case '<': return lt(a, b);
    case '<=': return le(a, b);
    case '>': return gt(a, b);
    case '>=': return ge(a, b);
  }
}

/** A condition as a lambda of two rows, their columns read through `named` (their name → the name it has here). */
function conditionLambda(on: JoinCondition, left: JoinSide, right: JoinSide, named: (c: string) => string): Lambda {
  if ('lambda' in on) return on.lambda;
  for (const row of on.rows) {
    has(left, row.left);
    has(right, row.right);
  }
  return lambda(['a', 'b'], on.rows.length === 0 ? lit.boolean(true) : and(...on.rows.map((r) => rowTerm(r, named(r.right)))));
}

/** Their relation with each name mine has renamed (theirNames). */
function renamed(right: JoinSide, names: ReadonlyMap<string, string>): Relation {
  let rel = from(right.relation);
  for (const [was, now] of names) if (was !== now) rel = rel.rename(was, now);
  return rel;
}

/** A semi- or anti-join: my rows that have (or have not) a row of theirs the condition pairs mine with. */
function semi(left: JoinSide, theirs: Relation, condition: Lambda, keep: 'isNotEmpty' | 'isEmpty'): Relation {
  const [a, b] = condition.parameters.map((p) => p.name) as [string, string];
  return from(left.relation).filter(lambda([a], fn(keep, theirs.filter(lambda([b], ...condition.body)).node)));
}

/** The as-of join's match, as a lambda of two rows. */
function matchLambda(how: Extract<JoinHow, { form: 'asOf' }>, left: JoinSide, right: JoinSide, named: (c: string) => string): Lambda {
  has(left, how.match.left);
  has(right, how.match.right);
  return lambda(['a', 'b'], rowTerm(how.match, named(how.match.right)));
}

/** Their kept columns, in their order, as they come out; all of them when `keep` is null. */
function kept(right: JoinSide, keep: readonly string[] | null, names: ReadonlyMap<string, string>): string[] {
  if (keep !== null) keep.forEach((c) => has(right, c));
  return right.columns.filter((c) => keep === null || keep.includes(c.name)).map((c) => names.get(c.name)!);
}

/**
 * WHAT A JOIN BECOMES, as one relation (§17.2): their names mine has renamed with the prefix, then the join or as-of
 * join, then their columns not kept selected away; a semi- or anti-join a filter of mine by theirs; an append each side
 * extended by the columns it lacks, in one order, concatenated. A column either input lacks, or an append's column of
 * two types, is refused by name.
 */
export function joinRelation(left: JoinSide, right: JoinSide, how: JoinHow): ValueSpecification {
  const mine = left.columns.map((c) => c.name);
  switch (how.form) {
    case 'join':
    case 'asOf': {
      const names = theirNames(mine, right.columns.map((c) => c.name), how.prefix);
      const named = (c: string): string => names.get(c) ?? c;
      const theirs = renamed(right, names);
      const on = how.form === 'asOf' && 'rows' in how.on && how.on.rows.length === 0
        ? undefined : conditionLambda(how.on, left, right, named);
      const joined = how.form === 'join'
        ? from(left.relation).join(theirs, KIND[how.kind], on!)
        : from(left.relation).asOfJoin(theirs, matchLambda(how, left, right, named), on);
      const brought = kept(right, how.keep, names);
      return (brought.length < right.columns.length ? joined.select([...mine, ...brought]) : joined).node;
    }
    case 'semi':
    case 'anti':
      return semi(left, from(right.relation), conditionLambda(how.on, left, right, (c) => c), how.form === 'semi' ? 'isNotEmpty' : 'isEmpty').node;
    case 'append':
      return appended(left, right).node;
  }
}

/** The columns an append brings out: mine in my order, then theirs I lack; a name of two types refused. */
export function appendColumns(left: JoinSide, right: JoinSide): { readonly name: string; readonly type: string }[] {
  const theirs = new Map(right.columns.map((c) => [c.name, c.type]));
  for (const c of left.columns) {
    const t = theirs.get(c.name);
    if (t !== undefined && t !== c.type) throw new CubeRefusal(`${c.name} is ${c.type} in ${left.name} and ${t} in ${right.name}: an append matches a column by its name and its type`);
  }
  const mine = new Set(left.columns.map((c) => c.name));
  return [...left.columns, ...right.columns.filter((c) => !mine.has(c.name))].map((c) => ({ name: c.name, type: c.type }));
}

function appended(left: JoinSide, right: JoinSide): Relation {
  const all = appendColumns(left, right);
  const order = all.map((c) => c.name);
  // a side's rows have no value in a column only the other has: `[]` of the column's type
  const side = (s: JoinSide): Relation => {
    const own = new Set(s.columns.map((c) => c.name));
    const lacking = all.filter((c) => !own.has(c.name));
    let rel = from(s.relation);
    if (lacking.length > 0) rel = rel.extend(lacking.map((c) => derive(c.name, lambda(['x'], cast(collection([]), type(c.type))))));
    const now = [...s.columns.map((c) => c.name), ...lacking.map((c) => c.name)];
    return now.every((c, i) => c === order[i]) ? rel : rel.select(order);
  };
  return side(left).concatenate(side(right));
}

/** The columns a join brings out, by name, in order: what the dialog lists before the compiler types it. */
export function joinedNames(left: JoinSide, right: JoinSide, how: JoinHow): string[] {
  const mine = left.columns.map((c) => c.name);
  switch (how.form) {
    case 'join':
    case 'asOf':
      return [...mine, ...kept(right, how.keep, theirNames(mine, right.columns.map((c) => c.name), how.prefix))];
    case 'semi':
    case 'anti':
      return mine;
    case 'append':
      return appendColumns(left, right).map((c) => c.name);
  }
}

// ---------------------------------------------------------------- what it will do, before it is applied

/** The columns a summary's repeated-keys query answers in. */
export const REPEATED_KEYS = 'keys';
export const MOST_REPEATS = 'most';

/**
 * THE JOIN DIALOG'S QUESTIONS (§17.5), each a query the compiler plans: my rows; how many of them find a match (a join,
 * an as-of join, a semi- or anti-join); the rows after a join (more than mine: a key repeats on their side); for a join
 * on equal keys alone, how many of their keys repeat and the most times one does; an append's rows of theirs. Each
 * count answers in one row's `TOTAL_ROWS_COLUMN` (query.ts rowCountLambda); repeated keys in `keys` and `most`.
 */
export interface JoinSummaryQueries {
  readonly mine: Lambda;
  readonly matched?: Lambda;
  readonly joined?: Lambda;
  readonly repeated?: Lambda;
  readonly theirs?: Lambda;
}

export function joinSummary(left: JoinSide, right: JoinSide, how: JoinHow): JoinSummaryQueries {
  const mine = rowCountLambda(from(left.relation));
  switch (how.form) {
    case 'join':
    case 'asOf': {
      const names = theirNames(left.columns.map((c) => c.name), right.columns.map((c) => c.name), how.prefix);
      const named = (c: string): string => names.get(c) ?? c;
      const theirs = renamed(right, names);
      const empty = 'rows' in how.on && how.on.rows.length === 0;
      // an as-of join's match is part of what pairs a row: matched is "has a nearest row"
      const pairs = how.form === 'join'
        ? conditionLambda(how.on, left, right, named)
        : empty ? matchLambda(how, left, right, named) : both(matchLambda(how, left, right, named), conditionLambda(how.on, left, right, named));
      const matched = rowCountLambda(semi(left, theirs, pairs, 'isNotEmpty'));
      if (how.form === 'asOf') return { mine, matched };
      const joined = rowCountLambda(from(joinRelation(left, right, how)));
      const keys = 'rows' in how.on && how.on.rows.length > 0 && how.on.rows.every((r) => r.op === '==')
        ? [...new Set(how.on.rows.map((r) => r.right))] : [];
      return { mine, matched, joined, ...(keys.length > 0 ? { repeated: repeatedKeys(right, keys) } : {}) };
    }
    case 'semi':
    case 'anti':
      return { mine, matched: rowCountLambda(semi(left, from(right.relation), conditionLambda(how.on, left, right, (c) => c), 'isNotEmpty')) };
    case 'append':
      return { mine, theirs: rowCountLambda(from(right.relation)) };
  }
}

/** Two conditions over the same two rows, as one: the second's names read as the first's. */
function both(first: Lambda, second: Lambda): Lambda {
  const [a, b] = first.parameters.map((p) => p.name) as [string, string];
  const [c, d] = second.parameters.map((p) => p.name) as [string, string];
  if (a !== c || b !== d) {
    throw new CubeRefusal(`an as-of join's condition names its rows ${a} and ${b}, as its match does: {${a}, ${b} | ...}`);
  }
  return lambda([a, b], and(...first.body, ...second.body));
}

/** Their keys (each with a value: an empty one matches nothing) held by more than one row: how many, and the most rows one has. */
function repeatedKeys(right: JoinSide, keys: readonly string[]): Lambda {
  const counted = '__n__';
  const one = '__one__';
  return from(right.relation)
    .filter(lambda(['x'], and(...keys.map((k) => fn('isNotEmpty', property(variable('x'), k))))))
    .groupBy([...keys], [agg(counted, lambda(['x'], lit.integer(1)), lambda(['y'], fn('count', variable('y'))))])
    .filter(lambda(['x'], gt(property(variable('x'), counted), lit.integer(1))))
    .extend([derive(one, lambda(['x'], lit.string('1')))])
    .groupBy([one], [
      agg(REPEATED_KEYS, lambda(['x'], lit.integer(1)), lambda(['y'], fn('count', variable('y')))),
      agg(MOST_REPEATS, lambda(['x'], property(variable('x'), counted)), lambda(['y'], fn('max', variable('y')))),
    ])
    .lambda();
}

// ---------------------------------------------------------------- in words

const KIND_WORDS: Readonly<Record<JoinKindWord, string>> = {
  inner: 'matching rows only', left: 'all of mine', right: 'all of theirs', full: 'everything',
};

/** A row in words: `cust = id`. */
export function rowWords(row: JoinRow): string {
  return `${row.left} ${row.op === '==' ? '=' : row.op} ${row.right}`;
}

/** A join's grid's title: `trades ⋈ customers`, `trades + more`, `trades with customers`, `trades without customers`. */
export function joinTitle(how: JoinHow, left: string, right: string): string {
  switch (how.form) {
    case 'join': return `${left} ⋈ ${right}`;
    case 'asOf': return `${left} ⋈ ${right} as of`;
    case 'semi': return `${left} with ${right}`;
    case 'anti': return `${left} without ${right}`;
    case 'append': return `${left} + ${right}`;
  }
}

/** What a join does, in words: `all of mine, on cust = id`; `every pair`; `nearest at = at, on sym = sym`. */
export function joinWords(how: JoinHow): string {
  const on = (c: JoinCondition): string => ('lambda' in c ? 'a condition in Pure'
    : c.rows.length === 0 ? 'every pair' : c.rows.map(rowWords).join(' and '));
  switch (how.form) {
    case 'join': return `${KIND_WORDS[how.kind]}, on ${on(how.on)}`;
    case 'asOf': return `nearest ${rowWords(how.match)}${'rows' in how.on && how.on.rows.length === 0 ? '' : `, on ${on(how.on)}`}`;
    case 'semi': return `my rows with a match, on ${on(how.on)}`;
    case 'anti': return `my rows with no match, on ${on(how.on)}`;
    case 'append': return 'the rows of both, columns by name';
  }
}

/**
 * Keys to suggest, each with why: a name both inputs have, or their name's `_id` among mine (`customers` →
 * `customer_id`, `customers_id`). The rows a new join starts with when exactly one is found.
 */
export function suggestedKeys(left: JoinSide, right: JoinSide): { readonly row: JoinRow; readonly why: string }[] {
  const mine = new Map(left.columns.map((c) => [c.name.toLowerCase(), c]));
  const out: { row: JoinRow; why: string }[] = [];
  for (const c of right.columns) {
    const same = mine.get(c.name.toLowerCase());
    if (same && same.type === c.type) out.push({ row: { left: same.name, op: '==', right: c.name }, why: 'the same name' });
  }
  const base = right.name.toLowerCase().replace(/[^a-z0-9_]/g, '_');
  const singular = base.endsWith('s') ? base.slice(0, -1) : base;
  const id = right.columns.find((c) => c.name.toLowerCase() === 'id');
  if (id) {
    for (const n of new Set([`${singular}_id`, `${base}_id`])) {
      const m = mine.get(n);
      if (m && !out.some((o) => o.row.left === m.name && o.row.right === id.name)) {
        out.push({ row: { left: m.name, op: '==', right: id.name }, why: `${right.name}'s id` });
      }
    }
  }
  return out;
}
