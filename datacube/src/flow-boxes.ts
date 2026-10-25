// WHAT A FLOW'S BOX DOES, SAID (docs/DATACUBE_FLOW_MODE_2026_10_24.md FL5, §3.2): a box is a grid that does one thing,
// and what it does is what its grid's query does -- read off its cube document, never stored beside it, so a box says
// what Python's page and the screen's editors made of it alike. The canvas draws a box by these words: its tool's icon
// and name, and a line of what it is set to (as Alteryx's annotation under a tool says it).
//
// PURE: no DOM, no planner. The tools a person adds from the palette are here too, by the words the canvas shows.

import {
  inputCube, inputOn, inputSides, isLive, type CubeDocument, type InputPort, type InputSide, type JoinInput,
} from './cube-document.ts';
import { grainOf } from './calc-presets.ts';
import { textToolOf, type TextTool } from './text-tools.ts';
import { joinWords } from './join-words.ts';
import { conditionWords } from './page-filters.ts';
import type { DerivedColumn } from './snapshot.ts';
import type { ColumnEditorStart } from './ui/column-editor.ts';
import type { EditorTab } from './ui/editor.ts';

/** A tool of the flow's palette, and what a box is drawn as. */
export type FlowTool = 'input' | 'textInput' | 'join' | 'union' | 'filter' | 'formula' | 'select' | 'sort' | 'sample' | 'recordId' | 'multiRow' | 'tile'
  | 'datetime' | 'cleanse' | 'textToColumns' | 'regex' | 'runningTotal' | 'summarize' | 'crosstab' | 'browse' | 'comment';

/** Each tool's name, its group in the palette (Alteryx's: In/Out, Preparation, Join, Transform), and what it does. */
export const FLOW_TOOLS: Readonly<Record<FlowTool, { readonly name: string; readonly group: FlowGroup; readonly does: string }>> = {
  input: { name: 'Input', group: 'in', does: 'rows from a file, a table, a class or a saved query' },
  textInput: { name: 'Text Input', group: 'in', does: 'a small table of your own, typed in or pasted from a sheet' },
  join: { name: 'Join', group: 'join', does: 'two inputs joined on their keys: Left above, Right below' },
  union: { name: 'Union', group: 'join', does: 'the rows of two inputs, one under the other, columns by name' },
  filter: { name: 'Filter', group: 'prepare', does: 'the rows that meet its conditions' },
  formula: { name: 'Formula', group: 'prepare', does: 'columns calculated from each row' },
  select: { name: 'Select', group: 'prepare', does: 'the columns it keeps' },
  sort: { name: 'Sort', group: 'prepare', does: 'its rows in the order it sorts them' },
  sample: { name: 'Sample', group: 'prepare', does: 'its first or its last rows, in the order of a column' },
  recordId: { name: 'Record ID', group: 'prepare', does: 'a number for each row, in an order it is given' },
  multiRow: { name: 'Multi-Row', group: 'prepare', does: 'a column of the row before or after each one' },
  tile: { name: 'Tile', group: 'prepare', does: 'each row put in one of a number of equal buckets, by an order' },
  datetime: { name: 'DateTime', group: 'prepare', does: 'a date taken to its day, week, month, quarter or year' },
  cleanse: { name: 'Data Cleansing', group: 'prepare', does: 'text tidied: blanks, punctuation, spaces, its case' },
  textToColumns: { name: 'Text To Columns', group: 'parse', does: 'a text column split on a delimiter, a column a part' },
  regex: { name: 'RegEx', group: 'parse', does: 'a pattern over text: whether it matches, its matches replaced or taken out' },
  runningTotal: { name: 'Running Total', group: 'transform', does: 'a column\'s total so far, row by row' },
  summarize: { name: 'Summarize', group: 'transform', does: 'a row per group, its measures beside its keys' },
  crosstab: { name: 'Cross Tab', group: 'transform', does: 'a column\'s values made columns across, a row per group' },
  browse: { name: 'Browse', group: 'prepare', does: 'its input\'s rows, as they come' },
  comment: { name: 'Comment', group: 'docs', does: 'a note on the canvas, in words of your own' },
};

/** A palette group (Alteryx's: In/Out, Preparation, Join, Parse, Transform, Documentation). */
export type FlowGroup = 'in' | 'prepare' | 'join' | 'parse' | 'transform' | 'docs';

/** The tools a person adds after a box, in the palette's order. */
export const PALETTE: readonly FlowTool[] = [
  'input', 'textInput', 'filter', 'formula', 'select', 'sort', 'sample', 'recordId', 'multiRow', 'tile', 'datetime', 'cleanse', 'join',
  'union', 'textToColumns', 'regex', 'summarize', 'crosstab', 'runningTotal', 'comment',
];

/**
 * WHERE A TOOL IS SET (§3.4: the settings pane, made of DataCube's own editors): the grid's window that sets what the
 * tool does -- its filter, a calculated column, or a section of its Properties: the columns it shows, its sorts, its
 * row groups. An Input and a Browse set nothing of their own.
 */
export type StepEditor =
  | { readonly window: 'filters' }
  /** The calculated column's editor -- a new column, or (`start`) one that starts as a window column of the tool's. */
  | { readonly window: 'column'; readonly start?: ColumnEditorStart }
  | { readonly window: 'properties'; readonly tab: EditorTab }
  /** The Sample window (ui/sample-form.ts): the grid's Top N, said as its first or last rows by a column. */
  | { readonly window: 'sample' }
  /** The DateTime window (ui/date-form.ts): a date column taken to its period, the grid's own period preset. */
  | { readonly window: 'datetime' }
  /** A text tool's window (ui/text-tool-form.ts): Text To Columns, RegEx or Data Cleansing (text-tools.ts). */
  | { readonly window: 'text'; readonly kind: TextTool['kind'] }
  /** The Join window (ui/join-dialog.ts), over the box's two inputs: a Join's, a Union's (its Append). Opened when asked,
   *  never by a click on the box -- a window over the page, not docked. */
  | { readonly window: 'join' };

export const TOOL_EDITORS: Readonly<Partial<Record<FlowTool, StepEditor>>> = {
  filter: { window: 'filters' },
  formula: { window: 'column' },
  select: { window: 'properties', tab: 'Columns' },
  sort: { window: 'properties', tab: 'Sorts' },
  summarize: { window: 'properties', tab: 'Vertical Pivots' },
  crosstab: { window: 'properties', tab: 'Horizontal Pivots' },
  sample: { window: 'sample' },
  datetime: { window: 'datetime' },
  cleanse: { window: 'text', kind: 'cleanse' },
  textToColumns: { window: 'text', kind: 'split' },
  regex: { window: 'text', kind: 'regex' },
  // a window column, started as the tool's (snapshot.ts WindowSpec): the person picks its order, its column
  recordId: { window: 'column', start: { name: 'RecordID', window: { fn: 'rowNumber', partition: [], order: [] } } },
  multiRow: { window: 'column', start: { name: 'Previous', window: { fn: 'lag', partition: [], order: [], offset: 1 } } },
  tile: { window: 'column', start: { name: 'Tile', window: { fn: 'ntile', partition: [], order: [], buckets: 4 } } },
  runningTotal: { window: 'column', start: { name: 'RunningTotal', window: { fn: 'sum', partition: [], order: [], frame: 'running' } } },
  join: { window: 'join' },
  union: { window: 'join' },
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
  // a join's form says it: an Append is a Union, any other a Join (join-how.ts joinWords)
  if (source._type === 'join') return { tool: source.how.form === 'append' ? 'union' : 'join', also: [], says: joinWords(source.how) };
  if (source._type !== 'rows') return { tool: 'input', also: [], says: source.name };
  const q = cube.query;
  const steps: { tool: FlowTool; says: string }[] = [];
  // its calculated columns, each by the tool that makes one so -- the ones of a tool together, in the order they come
  for (const d of q.derived) {
    const tool = columnTool(d);
    const last = steps.at(-1);
    const said = columnWords(d);
    // a tool's columns said once each way: a split's parts are one split
    if (last?.tool === tool) last.says = last.says.split(', ').includes(said) ? last.says : `${last.says}, ${said}`;
    else steps.push({ tool, says: said });
  }
  if (q.filter !== undefined) steps.push({ tool: 'filter', says: conditionWords(q.filter) });
  if (q.topN !== undefined) steps.push({ tool: 'sample', says: `${q.topN.bottom === true ? 'first' : 'last'} ${q.topN.n} by ${q.topN.by}` });
  if (q.pivotOn.length > 0) {
    steps.push({ tool: 'crosstab', says: `${q.pivotOn.join(', ')} across${q.rows.length > 0 ? `, by ${q.rows.join(', ')}` : ''}` });
  } else if (q.rows.length > 0) steps.push({ tool: 'summarize', says: `by ${q.rows.join(', ')}` });
  if (q.sorts.length > 0) steps.push({ tool: 'sort', says: q.sorts.map((s) => `${s.column} ${s.direction === 'desc' ? '↓' : '↑'}`).join(', ') });
  const hidden = hiddenColumns(cube);
  if (hidden.length > 0) steps.push({ tool: 'select', says: `${hidden.length} of ${q.columns.length} columns left out` });
  const first = steps[0];
  if (first === undefined) return { tool: 'browse', also: [], says: 'every row' };
  return { tool: first.tool, also: steps.slice(1).map((s) => s.tool), says: first.says };
}

/** The tool a calculated column is made by: a window column's by its function (a row number, the row before or after,
 *  a bucket, a running total), any other a Formula's. */
function columnTool(d: DerivedColumn): FlowTool {
  const w = d.window;
  if (w === undefined) return grainOf(d) !== undefined ? 'datetime' : textToolOf(d)?.tool ?? 'formula';
  if (w.fn === 'rowNumber') return 'recordId';
  if (w.fn === 'lag' || w.fn === 'lead') return 'multiRow';
  if (w.fn === 'ntile') return 'tile';
  return w.frame === 'running' ? 'runningTotal' : 'formula';
}

/** A calculated column, as its box says it: its name -- a period's, what it takes to what (`trade_date by month`); a
 *  text tool's, what it does to which (text-tools.ts textToolOf: `desk split on ','`). */
function columnWords(d: DerivedColumn): string {
  const grain = grainOf(d);
  if (grain !== undefined) return `${grain.column} by ${grain.grain}`;
  return textToolOf(d)?.says ?? d.name;
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
  | { readonly side: InputSide; readonly grid: string; readonly port?: InputPort }
  | { readonly side: InputSide; readonly frozen: string };

/** A box's inputs, from its cube document: none for an Input (it reads a source of its own). */
export function boxInputs(cube: CubeDocument): BoxInput[] {
  return inputSides(cube.source).map((side) => {
    const input = inputOn(cube.source, side);
    return isLive(input) ? { side, grid: input.grid, ...(input.port !== undefined ? { port: input.port } : {}) } : { side, frozen: copyName(input) };
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
