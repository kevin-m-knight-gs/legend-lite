// LIVE INPUTS (docs/DATACUBE_FLOW_MODE_2026_10_24.md §3.1, FL6): a grid over another grid's rows reads that grid of its
// page as it is now. Here are the page's rules for them, with no DOM and no planner: what a source reads live, a source
// resolved against its page -- every live input the copy of what its grid is now, as the Join window copies a grid it
// joins (page-commands.ts joinInputOf) -- and the order a page's cubes open in, so a grid opens after the grids it reads.

import { isLive, type CubeInput, type CubeSource, type InputPort, type JoinInput } from './cube-document.ts';
import type { PageDocument } from './page-document.ts';
import { boxOmitted, boxRenamed } from './box-columns.ts';
import { CubeRefusal } from './snapshot.ts';

/** The grids `source` reads live, each once, in the order it reads them -- through its frozen inputs too. */
export function liveGrids(source: CubeSource): string[] {
  const grids: string[] = [];
  const visit = (s: CubeSource): void => {
    const input = (i: CubeInput): void => {
      if (!isLive(i)) visit(i.source);
      else if (!grids.includes(i.grid)) grids.push(i.grid);
    };
    if (s._type === 'rows') input(s.input);
    else if (s._type === 'join') {
      input(s.left);
      input(s.right);
    }
  };
  visit(source);
  return grids;
}

/**
 * A GRID OF THE PAGE AS AN INPUT, the one rule (the Join window's and a live input's): what it is called (its title,
 * else its source's name), its source -- resolved, so the copy holds no live input of its own -- and its query. Refused
 * in words when `grid` is no grid of the page, or reads its own rows (`through`: the grids read on the way to it).
 */
export function gridInput(page: PageDocument, grid: string, through: readonly string[] = [], port?: InputPort): JoinInput {
  const view = page.views.find((v) => v.id === grid);
  if (view === undefined) throw new CubeRefusal(`'${grid}' is no grid of this page`);
  if (view.kind !== 'grid') throw new CubeRefusal(`'${grid}' is a ${view.kind} tile, not a grid`);
  const cube = page.cubes.find((c) => c.id === view.cube);
  if (cube === undefined) throw new CubeRefusal(`'${grid}' shows no cube of the page`);
  if (through.includes(grid)) {
    throw new CubeRefusal(`'${grid}' reads its own rows (${[...through.slice(through.indexOf(grid)), grid].join(' → ')})`);
  }
  const query = port === undefined ? cube.cube.query : portQuery(grid, cube.cube.query, port);
  // a flow's box hands on the columns it shows, under the names it gives them (box-columns.ts); a board's grid, all
  const onFlow = page.sheets.some((s) => s.layout.kind === 'flow' && s.layout.boxes.some((b) => b.tile === grid));
  const omit = onFlow ? boxOmitted(cube.cube.query, cube.cube.configuration) : [];
  const rename = onFlow ? boxRenamed(cube.cube.query, cube.cube.configuration) : [];
  return {
    name: view.title ?? cube.cube.source.name, source: resolvedOn(page, cube.cube.source, [...through, grid]), query,
    ...(omit.length > 0 ? { omit } : {}), ...(rename.length > 0 ? { rename } : {}),
  };
}

/**
 * A GRID'S PORT AS ITS QUERY (FL7): its False, the same query with its filter turned round -- the rows of its input its
 * condition does not hold for (a row its condition is unknown for, a null compared, is in neither, as SQL's NOT says).
 * Refused in words when it has no filter: no row is left out.
 */
function portQuery(grid: string, query: JoinInput['query'], port: InputPort): JoinInput['query'] {
  if (port !== 'false') throw new Error(`no such port: ${String(port)}`);
  const filter = query.filter;
  if (filter === undefined) throw new CubeRefusal(`'${grid}' has no filter: its False port leaves no row out`);
  return { ...query, filter: filter.kind === 'not' ? filter.child : { kind: 'not', child: filter } };
}

/**
 * A SOURCE RESOLVED AGAINST ITS PAGE: every live input in it -- another grid's rows', a join's inputs' -- the copy of
 * what its grid is now (gridInput), so whatever asks what a grid reads (its leaves, its base query) asks a source with no
 * link left in it. `grid`, the grid whose source it is, if it is one of the page's: a source that reads it back is refused.
 */
export function resolvedSource(page: PageDocument, source: CubeSource, grid?: string): CubeSource {
  return resolvedOn(page, source, grid === undefined ? [] : [grid]);
}

function resolvedOn(page: PageDocument, source: CubeSource, through: readonly string[]): CubeSource {
  const input = (i: CubeInput): JoinInput => (isLive(i) ? gridInput(page, i.grid, through, i.port)
    : { ...i, source: resolvedOn(page, i.source, through) });
  switch (source._type) {
    case 'rows': return { ...source, input: input(source.input) };
    case 'join': return { ...source, left: input(source.left), right: input(source.right) };
    default: return source;
  }
}

/** A source with every grid its links name renamed (`n`: an id to the id it is now), as the page's ids are renamed
 *  (page-rebase.ts renamedCommand). */
export function relinked(source: CubeSource, n: (id: string) => string): CubeSource {
  const input = (i: CubeInput): CubeInput => relinkedInput(i, n);
  switch (source._type) {
    case 'rows': return { ...source, input: input(source.input) };
    case 'join': return { ...source, left: input(source.left), right: input(source.right) };
    default: return source;
  }
}

/** An input with every grid its links name renamed (relinked's rule, a page command's input: setInput). */
export function relinkedInput(input: CubeInput, n: (id: string) => string): CubeInput {
  return isLive(input) ? { ...input, grid: n(input.grid) } : { ...input, source: relinked(input.source, n) };
}

/**
 * A SOURCE WITH ITS LINKS TO `grid` FROZEN (FL6: a grid removed while others read it live): each live input naming it
 * replaced by `copy` of its port (none: its rows), what that grid was (gridInput) -- through its frozen inputs too; every
 * other link as it is.
 */
export function frozenFrom(source: CubeSource, grid: string, copy: (port: InputPort | undefined) => JoinInput): CubeSource {
  const input = (i: CubeInput): CubeInput => (isLive(i) ? (i.grid === grid ? copy(i.port) : i) : { ...i, source: frozenFrom(i.source, grid, copy) });
  switch (source._type) {
    case 'rows': return { ...source, input: input(source.input) };
    case 'join': return { ...source, left: input(source.left), right: input(source.right) };
    default: return source;
  }
}

/**
 * Whether a page's live inputs all hold: each names a grid of the page, and none reads its own rows -- the problem in
 * words, else undefined. A page read (page-document.ts readPage) is held to it.
 */
export function liveProblem(page: PageDocument): string | undefined {
  for (const view of page.views) {
    if (view.kind !== 'grid') continue;
    const cube = page.cubes.find((c) => c.id === view.cube);
    if (cube === undefined || liveGrids(cube.cube.source).length === 0) continue;
    try {
      resolvedSource(page, cube.cube.source, view.id);
    } catch (e) {
      if (e instanceof CubeRefusal) return `${view.id}: ${e.message}`;
      throw e;
    }
  }
  return undefined;
}

/**
 * `cubes` in an order that opens each grid after every grid it reads live (`gridOf`: the grid a cube is shown by), the
 * order they came in kept otherwise -- a page's other rules for its opening order (page-document.ts openingOrder) stand.
 * A cube that reads a grid not among them, or one in a cycle, keeps its place: what it reads is refused when it opens.
 */
export function afterWhatTheyRead<T extends { readonly cube: { readonly source: CubeSource } }>(
  cubes: readonly T[], gridOf: (cube: T) => string | undefined,
): T[] {
  const byGrid = new Map(cubes.flatMap((c) => {
    const g = gridOf(c);
    return g === undefined ? [] : [[g, c] as const];
  }));
  const placed = new Set<T>();
  const order: T[] = [];
  const place = (c: T, visiting: Set<T>): void => {
    if (placed.has(c) || visiting.has(c)) return;
    visiting.add(c);
    for (const g of liveGrids(c.cube.source)) {
      const read = byGrid.get(g);
      if (read !== undefined) place(read, visiting);
    }
    visiting.delete(c);
    if (!placed.has(c)) {
      placed.add(c);
      order.push(c);
    }
  };
  for (const c of cubes) place(c, new Set());
  return order;
}
