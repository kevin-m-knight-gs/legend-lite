// A FLOW SHEET'S LAYOUT (docs/DATACUBE_FLOW_MODE_2026_10_24.md FL4): boxes on a canvas, each a tile of the page -- a grid
// that does one thing (FL5) -- at a place, in canvas units (CSS pixels at 100%), its top-left corner. The lines between
// boxes are no part of it: each is a box's input, which its grid's source says (live-inputs.ts).
//
// PURE, as bands.ts is: no DOM, no state. Every function takes a flow and returns a new one, so where a box goes when it
// is added, moved or taken off is the page commands' (page-commands.ts) and testable in node, and the canvas only draws
// a flow and turns the pointer into these calls.
//
// The rules, enforced by construction and checked by `flowProblems`:
//   - every box is in the flow once;
//   - a place is two whole numbers, neither below zero.

/** A box on the canvas: its tile, and where its top-left corner is. */
export interface FlowBox {
  readonly tile: string;
  readonly x: number;
  readonly y: number;
}

/** A flow: its boxes, in the order they were placed. */
export interface Flow {
  readonly boxes: readonly FlowBox[];
}

/** A new flow sheet's: no box yet. */
export const EMPTY_FLOW: Flow = { boxes: [] };

/** A box's size on the canvas, and the room kept between boxes: what a box added beside another is placed by. */
export const BOX_WIDTH = 184;
export const BOX_HEIGHT = 60;
export const GAP_X = 64;
export const GAP_Y = 32;
/** Where the first box goes: the canvas's margin. */
export const MARGIN = 40;

/** The tiles of a flow, in the order they were placed. */
export function flowTiles(flow: Flow): string[] {
  return flow.boxes.map((b) => b.tile);
}

/** Whether two boxes' places overlap. */
const overlaps = (a: { readonly x: number; readonly y: number }, b: { readonly x: number; readonly y: number }): boolean =>
  Math.abs(a.x - b.x) < BOX_WIDTH && Math.abs(a.y - b.y) < BOX_HEIGHT;

/**
 * A BOX ADDED: after `near` when it is a box of the flow -- the next column, on its row, else the first free row below
 * it there, as a tool dropped on a box's output goes in Alteryx -- else on a row of its own below every box. Refused when
 * it is in the flow already.
 */
export function placeAfter(flow: Flow, tile: string, near?: string): Flow {
  if (flow.boxes.some((b) => b.tile === tile)) throw new FlowRefusal(`'${tile}' is on the canvas already`);
  const after = near === undefined ? undefined : flow.boxes.find((b) => b.tile === near);
  let place: { x: number; y: number };
  if (after !== undefined) {
    place = { x: after.x + BOX_WIDTH + GAP_X, y: after.y };
    while (flow.boxes.some((b) => overlaps(b, place))) place = { ...place, y: place.y + BOX_HEIGHT + GAP_Y };
  } else {
    const bottom = Math.max(MARGIN - BOX_HEIGHT - GAP_Y, ...flow.boxes.map((b) => b.y));
    place = { x: MARGIN, y: bottom + BOX_HEIGHT + GAP_Y };
  }
  return { boxes: [...flow.boxes, { tile, ...place }] };
}

/** A box moved to (`x`, `y`), rounded to whole units; refused when it is no box of the flow, or the place is off the canvas. */
export function moveBox(flow: Flow, tile: string, x: number, y: number): Flow {
  if (!flow.boxes.some((b) => b.tile === tile)) throw new FlowRefusal(`'${tile}' is no box of this flow`);
  if (!Number.isFinite(x) || !Number.isFinite(y) || x < 0 || y < 0) {
    throw new FlowRefusal(`a box's place is two numbers, neither below zero, not (${x}, ${y})`);
  }
  return { boxes: flow.boxes.map((b) => (b.tile === tile ? { tile, x: Math.round(x), y: Math.round(y) } : b)) };
}

/** A box taken off the canvas; a tile the flow does not have leaves it as it is. */
export function removeBox(flow: Flow, tile: string): Flow {
  return { boxes: flow.boxes.filter((b) => b.tile !== tile) };
}

/**
 * A FLOW TIDIED (Tidy Up, Alteryx's Arrange: docs/DATACUBE_FLOW_MODE_2026_10_24.md M7): each box in the column of how far
 * it is from the flow's start -- a box that reads none of the flow's first, a box one column after the furthest box it
 * reads -- and, down a column, in the order of the boxes it reads (as level with them as the column has room), else as it
 * was; every place on the grid of the flow's gaps. `reads`: the boxes a box reads live. The boxes in the order they came.
 */
export function arranged(flow: Flow, reads: (tile: string) => readonly string[]): Flow {
  const on = new Set(flow.boxes.map((b) => b.tile));
  const inputs = (tile: string): string[] => reads(tile).filter((r) => r !== tile && on.has(r));
  const depth = new Map<string, number>();
  const visiting = new Set<string>();
  const depthOf = (tile: string): number => {
    const known = depth.get(tile);
    if (known !== undefined) return known;
    // a cycle the page would refuse: its box where it starts
    if (visiting.has(tile)) return 0;
    visiting.add(tile);
    const read = inputs(tile);
    const d = read.length === 0 ? 0 : 1 + Math.max(...read.map(depthOf));
    visiting.delete(tile);
    depth.set(tile, d);
    return d;
  };
  for (const b of flow.boxes) depthOf(b.tile);
  const was = new Map(flow.boxes.map((b) => [b.tile, b] as const));
  const row = new Map<string, number>();
  const deepest = Math.max(0, ...depth.values());
  for (let d = 0; d <= deepest; d += 1) {
    // where each would like to be: level with the boxes it reads, else where it was
    const wanted = (tile: string): number => {
      const read = inputs(tile).filter((r) => row.has(r));
      return read.length === 0 ? Number.POSITIVE_INFINITY : read.reduce((sum, r) => sum + row.get(r)!, 0) / read.length;
    };
    const these = flow.boxes.map((b) => b.tile).filter((t) => depth.get(t) === d)
      .sort((a, b) => wanted(a) - wanted(b) || was.get(a)!.y - was.get(b)!.y || was.get(a)!.x - was.get(b)!.x);
    let next = 0;
    for (const tile of these) {
      const w = wanted(tile);
      const at = Number.isFinite(w) ? Math.max(next, Math.round(w)) : next;
      row.set(tile, at);
      next = at + 1;
    }
  }
  return {
    boxes: flow.boxes.map((b) => ({
      tile: b.tile, x: MARGIN + depth.get(b.tile)! * (BOX_WIDTH + GAP_X), y: MARGIN + row.get(b.tile)! * (BOX_HEIGHT + GAP_Y),
    })),
  };
}

/** What is wrong with a flow, in words (none: it is one). */
export function flowProblems(flow: Flow): string[] {
  const problems: string[] = [];
  const seen = new Set<string>();
  for (const b of flow.boxes) {
    if (seen.has(b.tile)) problems.push(`'${b.tile}' is on the canvas twice`);
    seen.add(b.tile);
    if (!Number.isInteger(b.x) || !Number.isInteger(b.y) || b.x < 0 || b.y < 0) {
      problems.push(`'${b.tile}' is at (${b.x}, ${b.y}): a box's place is two whole numbers, neither below zero`);
    }
  }
  return problems;
}

/** A flow's rule broken: said in words (a page command refuses it as a CubeRefusal, page-commands.ts `laid`). */
export class FlowRefusal extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'FlowRefusal';
  }
}
