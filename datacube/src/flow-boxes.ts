// WHAT A FLOW'S BOX DOES, SAID (docs/DATACUBE_FLOW_MODE_2026_10_24.md FL5, §3.2): a box is a grid that does one thing,
// and what it does is what its grid's query does -- read off its cube document, never stored beside it, so a box says
// what Python's page and the screen's editors made of it alike. The canvas draws a box by these words: its tool's icon
// and name, and a line of what it is set to (as Alteryx's annotation under a tool says it).
//
// PURE: no DOM, no planner. The tools a person adds from the palette are here too, by the words the canvas shows.

import { inputCube, inputOn, inputSides, isLive, type CubeDocument, type InputSide, type JoinInput } from './cube-document.ts';
import { conditionWords } from './page-filters.ts';
import type { EditorTab } from './ui/editor.ts';

/** A tool of the flow's palette, and what a box is drawn as. */
export type FlowTool = 'input' | 'join' | 'filter' | 'formula' | 'select' | 'sort' | 'summarize' | 'browse';

/** Each tool's name, its group in the palette (Alteryx's: In/Out, Preparation, Join, Transform), and what it does. */
export const FLOW_TOOLS: Readonly<Record<FlowTool, { readonly name: string; readonly group: 'in' | 'prepare' | 'join' | 'transform'; readonly does: string }>> = {
  input: { name: 'Input', group: 'in', does: 'rows from a file, a table, a class or a saved query' },
  join: { name: 'Join', group: 'join', does: 'two inputs joined on their keys' },
  filter: { name: 'Filter', group: 'prepare', does: 'the rows that meet its conditions' },
  formula: { name: 'Formula', group: 'prepare', does: 'columns calculated from each row' },
  select: { name: 'Select', group: 'prepare', does: 'the columns it keeps' },
  sort: { name: 'Sort', group: 'prepare', does: 'its rows in the order it sorts them' },
  summarize: { name: 'Summarize', group: 'transform', does: 'a row per group, its measures beside its keys' },
  browse: { name: 'Browse', group: 'prepare', does: 'its input\'s rows, as they come' },
};

/** The tools a person adds after a box, in the palette's order. */
export const PALETTE: readonly FlowTool[] = ['input', 'filter', 'formula', 'select', 'sort', 'summarize'];

/**
 * WHERE A TOOL IS SET (§3.4: the settings pane, made of DataCube's own editors): the grid's window that sets what the
 * tool does -- its filter, a calculated column, or a section of its Properties: the columns it shows, its sorts, its
 * row groups. An Input and a Browse set nothing of their own.
 */
export type StepEditor =
  | { readonly window: 'filters' }
  | { readonly window: 'column' }
  | { readonly window: 'properties'; readonly tab: EditorTab };

export const TOOL_EDITORS: Readonly<Partial<Record<FlowTool, StepEditor>>> = {
  filter: { window: 'filters' },
  formula: { window: 'column' },
  select: { window: 'properties', tab: 'Columns' },
  sort: { window: 'properties', tab: 'Sorts' },
  summarize: { window: 'properties', tab: 'Vertical Pivots' },
};

/** What a box does, as the canvas says it: its tool (the first its query applies), any others it applies besides,
 *  and a line of what it is set to. */
export interface BoxSays {
  readonly tool: FlowTool;
  readonly also: readonly FlowTool[];
  readonly says: string;
}

/**
 * WHAT A BOX DOES, from its cube document: a box over no other grid's rows is an Input (a join's, a Join); over another
 * grid's rows, each step its query takes, in the order a grid takes them (calculated columns, the filter, the grouping,
 * the sorts; the columns it hides beside them) -- none, a Browse.
 */
export function boxSays(cube: CubeDocument): BoxSays {
  const source = cube.source;
  if (source._type === 'join') return { tool: 'join', also: [], says: source.name };
  if (source._type !== 'rows') return { tool: 'input', also: [], says: source.name };
  const q = cube.query;
  const steps: { tool: FlowTool; says: string }[] = [];
  if (q.derived.length > 0) steps.push({ tool: 'formula', says: q.derived.map((d) => d.name).join(', ') });
  if (q.filter !== undefined) steps.push({ tool: 'filter', says: conditionWords(q.filter) });
  if (q.rows.length > 0) steps.push({ tool: 'summarize', says: `by ${q.rows.join(', ')}` });
  if (q.sorts.length > 0) steps.push({ tool: 'sort', says: q.sorts.map((s) => `${s.column} ${s.direction === 'desc' ? '↓' : '↑'}`).join(', ') });
  const hidden = hiddenColumns(cube);
  if (hidden.length > 0) steps.push({ tool: 'select', says: `${hidden.length} of ${q.columns.length} columns left out` });
  const first = steps[0];
  if (first === undefined) return { tool: 'browse', also: [], says: 'every row' };
  return { tool: first.tool, also: steps.slice(1).map((s) => s.tool), says: first.says };
}

/** The columns a cube's configuration hides (Column Properties' Hide): what a Select box leaves out. */
function hiddenColumns(cube: CubeDocument): string[] {
  const columns = cube.configuration['columns'];
  if (columns === null || typeof columns !== 'object' || Array.isArray(columns)) return [];
  return Object.entries(columns as Record<string, unknown>)
    .filter(([, c]) => c !== null && typeof c === 'object' && (c as Record<string, unknown>)['hidden'] === true)
    .map(([name]) => name);
}

/** AN INPUT OF A BOX (docs/DATACUBE_FLOW_MODE_2026_10_24.md M2): the side of its source that reads another grid's rows --
 *  a box's one input, a join's left and right -- and the box it reads there live, or the name of the copy it reads
 *  (frozen: FL6). The line into it, and its settings' Input. */
export type BoxInput =
  | { readonly side: InputSide; readonly grid: string }
  | { readonly side: InputSide; readonly frozen: string };

/** A box's inputs, from its cube document: none for an Input (it reads a source of its own). */
export function boxInputs(cube: CubeDocument): BoxInput[] {
  return inputSides(cube.source).map((side) => {
    const input = inputOn(cube.source, side);
    return isLive(input) ? { side, grid: input.grid } : { side, frozen: copyName(input) };
  });
}

/** What a frozen copy is called on a flow: the box it copies, as the canvas named it -- its title, else its tool's
 *  name (a copy of a Filter is "Filter", never the file under it); an Input's, its source's. */
function copyName(input: JoinInput): string {
  if (input.name !== input.source.name) return input.name;
  const tool = boxSays(inputCube(input)).tool;
  return tool === 'input' ? input.name : FLOW_TOOLS[tool].name;
}

/**
 * THE BOXES THAT READ `id`, through others too (`documentOf`: each box's document; `boxes`, the flow's): what `id` can
 * never be linked to, a box reading its own rows.
 */
export function readersOf(id: string, boxes: readonly string[], documentOf: (id: string) => CubeDocument | undefined): Set<string> {
  const readers = new Set<string>();
  const reads = new Map(boxes.map((b) => {
    const doc = documentOf(b);
    return [b, doc === undefined ? [] : boxInputs(doc).flatMap((i) => ('grid' in i ? [i.grid] : []))] as const;
  }));
  for (let grown = true; grown;) {
    grown = false;
    for (const [b, inputs] of reads) {
      if (readers.has(b) || !inputs.some((g) => g === id || readers.has(g))) continue;
      readers.add(b);
      grown = true;
    }
  }
  return readers;
}
