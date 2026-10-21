// THE ONE API LEDGERS' SOURCE SCANNING (docs/DATACUBE_ONE_API_PLAN_2026_10_21.md §3): where in a source a call is made
// -- the function, method or object property it is made in -- so a ledger names a gap by where it lives, not by a line
// number that moves with every edit. Plain text over the source: comments left out, as state-guardrail.test.ts reads it.

/** A source's lines, a comment line blanked (so a call named in a comment is no call). */
export function codeLines(text: string): string[] {
  return text.split('\n').map((line) => {
    const t = line.trim();
    return t.startsWith('//') || t.startsWith('*') || t.startsWith('/*') ? '' : line;
  });
}

const KEYWORDS = new Set(['if', 'for', 'while', 'switch', 'return', 'catch', 'function', 'await', 'void', 'new', 'typeof',
  'else', 'try', 'do', 'throw', 'case', 'super', 'import']);

/** A class member's header (two spaces in): `  async #name(`, `  get name(`, `  name<T>(`. */
const MEMBER = /^ {2}(?:static\s+)?(?:async\s+)?(?:get\s+|set\s+)?(#?[A-Za-z_$][\w$]*)\s*(?:<[^(]*>)?\(/;
/** An object property that is a function: `name: (`, `name: async (`, `name(...) {` deeper in. */
const PROPERTY = /^\s+(#?[A-Za-z_$][\w$]*)\s*:\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z_$][\w$]*)\s*=>/;
const METHOD = /^\s+(?:async\s+)?(#?[A-Za-z_$][\w$]*)\s*\([^)]*\)\s*(?::[^{]*)?\{\s*$/;
/** A function inside another: `const name = async (...) =>`, `function name(`. */
const INNER = /^\s+(?:(?:(?:const|let)\s+)?([A-Za-z_$][\w$]*)\s*=\s*(?:async\s*)?(?:\([^)]*\)?|[A-Za-z_$][\w$]*\s*=>)|(?:async\s+)?function\s+([A-Za-z_$][\w$]*))/;
/** The end of a signature written over several lines: its closing parenthesis, its return type, its body opened. */
const SIGNATURE_END = /\)\s*:\s*[^=]*\{\s*$/;
/** A top-level function or arrow constant. */
const TOP = /^(?:export\s+)?(?:async\s+)?(?:function\s+([A-Za-z_$][\w$]*)|const\s+([A-Za-z_$][\w$]*)\s*=)/;

const indent = (line: string): number => line.length - line.trimStart().length;

/** The header `line` opens, by name, or undefined: a function, a method, a function property, an inner function. */
function header(line: string): string | undefined {
  for (const re of [PROPERTY, METHOD, MEMBER]) {
    const m = re.exec(line);
    // a parameter of a signature written over several lines (`then: (s) => S = ..., x): R {`) is no property
    if (re === PROPERTY && SIGNATURE_END.test(line)) continue;
    if (m && !KEYWORDS.has(m[1]!)) return m[1]!;
  }
  const inner = INNER.exec(line);
  if (inner && (inner[2] !== undefined || /=>|\($/.test(line))) return (inner[1] ?? inner[2])!;
  const top = TOP.exec(line);
  return top ? (top[1] ?? top[2])! : undefined;
}

/**
 * The name of what the line at `index` is in: the nearest header above it that is less indented than every line
 * between (so a sibling -- a local arrow function defined just before the call -- is not taken for it).
 */
export function enclosing(lines: readonly string[], index: number): string {
  // a function written on one line, the call in its body: `rename = (name) => page.setTitle(name);`
  const own = header(lines[index]!);
  if (own !== undefined && /=>|\{\s*$/.test(lines[index]!)) return own;
  let least = indent(lines[index]!);
  for (let i = index - 1; i >= 0; i -= 1) {
    const line = lines[i]!;
    if (line.trim() === '') continue;
    const at = indent(line);
    if (at < least) {
      const name = header(line);
      if (name !== undefined) return name;
      // the close of a signature written over several lines (`): R {`): its header is at the same depth, above
      if (line.trim().startsWith(')')) {
        least = at + 1;
        continue;
      }
    }
    least = Math.min(least, at);
  }
  return '(top)';
}

/** Each call of `pattern` (a global regex; its first group, or the match, names the call) as `where:call`, counted. */
export function sites(text: string, pattern: RegExp): Map<string, number> {
  const lines = codeLines(text);
  const found = new Map<string, number>();
  lines.forEach((line, i) => {
    for (const m of line.matchAll(pattern)) {
      const key = `${enclosing(lines, i)}:${m[1] ?? m[0]}`;
      found.set(key, (found.get(key) ?? 0) + 1);
    }
  });
  return found;
}

/** The body of `export type <name> =`: its lines up to the next statement at the left margin. */
function unionBody(text: string, name: string): string {
  const start = text.indexOf(`export type ${name} =`);
  if (start < 0) throw new Error(`no union ${name}`);
  const lines = text.slice(start).split('\n');
  const end = lines.findIndex((line, i) => i > 0 && line.length > 0 && !/^\s/.test(line));
  return lines.slice(0, end < 0 ? undefined : end).join('\n');
}

/** The `kind`s a union of object types declares inline (`| { readonly kind: 'x'; ... }`). */
export function unionKinds(text: string, name: string): string[] {
  return [...new Set([...unionBody(text, name).matchAll(/readonly kind: '([A-Za-z]+)'/g)].map((m) => m[1]!))];
}

/** The `_type`s of a union of named interfaces (`= FileSource | QuerySource`), each read from its interface. */
export function unionTypes(text: string, name: string): string[] {
  const names = unionBody(text, name).replace(/^[^=]*=/, '').split('|').map((n) => n.replace(/[;\s]/g, '')).filter(Boolean);
  return names.map((n) => {
    const at = text.indexOf(`export interface ${n} {`);
    if (at < 0) throw new Error(`no interface ${n}`);
    const m = /readonly _type: '([A-Za-z]+)'/.exec(text.slice(at));
    if (!m) throw new Error(`${n} names no _type`);
    return m[1]!;
  });
}

/** A ledger held to what was found: every site found listed with its count, every one listed found -- in words. */
export function heldTo(found: ReadonlyMap<string, number>, ledger: Readonly<Record<string, { readonly count?: number }>>,
  what: string): string[] {
  const problems: string[] = [];
  for (const [key, n] of found) {
    const listed = ledger[key];
    if (listed === undefined) problems.push(`${what}: ${key} (${n}) is a new gap -- make it a command, or list it with its item`);
    else if ((listed.count ?? 1) !== n) problems.push(`${what}: ${key} is found ${n} times, listed ${listed.count ?? 1}`);
  }
  for (const key of Object.keys(ledger)) {
    if (!found.has(key)) problems.push(`${what}: ${key} is listed but gone -- close its item and take it off the ledger`);
  }
  return problems;
}
