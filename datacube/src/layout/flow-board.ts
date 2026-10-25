// A FLOW SHEET'S BOARD (docs/DATACUBE_FLOW_MODE_2026_10_24.md FL4, §3.4): the boxes of a flow on a canvas, and below it
// the results pane, which holds the grid of the box shown -- one at a time, as Alteryx's Results window shows the tool
// selected. Every other box is a document, not a running grid (M1's design, 1 and 3): drawn from its cube document as the
// page has it, with no grid made for it until it is shown.
//
// This is the board's state, kept as a band board keeps its (layout/band-board.ts): which box is where (layout/flow.ts),
// what each is called, which is shown and what its grid's element is. The drawing -- the canvas, its lines, the palette,
// dragging a box -- is fetched the first time a flow sheet is made (layout/flow-canvas.ts), so a page with no flow never
// downloads it; until it comes, nothing is drawn, and once it has, it draws the board as it is then.
//
// What a box DOES is its document's (flow-boxes.ts boxSays); what a gesture DOES is its page command's: the board only
// says what the hand did (`FlowBoardOptions`), and is put at the page the command makes (page/cube-page.ts sync).

import type { BandTile } from './band-board.ts';
import { EMPTY_FLOW, placeAfter, removeBox, type Flow } from './flow.ts';
import { isLive, type CubeDocument, type CubeInput, type InputSide } from '../cube-document.ts';
import { toJson } from '../../../pure-protocol/src/index.ts';
import type { FlowTool, StepEditor } from '../flow-boxes.ts';
import { lazily } from '../lazily.ts';

export interface FlowBoardOptions {
  /** A box let go at (`x`, `y`) by hand -- its drag, or its arrow keys: the caller tells its place (placeBox). */
  readonly onMove?: (id: string, x: number, y: number) => void;
  /** A box clicked, or Enter on it: the caller shows its rows (its grid made, `add`) -- and its settings, `editor` (the
   *  window of its grid's that sets what it does: flow-boxes.ts TOOL_EDITORS), docked in the board's settings pane. */
  readonly onSelect?: (id: string, editor: StepEditor | undefined) => void;
  /** A box's × or Delete on it: the caller takes it off the page. */
  readonly onRemove?: (id: string) => void;
  /** A box renamed in place. */
  readonly onRename?: (id: string, title: string) => void;
  /** A tool of the palette picked -- clicked, after the box shown, or dropped on a box, after it; dropped on the canvas,
   *  after none; dropped on a line, after the box it comes out of and `into` the box it goes into, on that side, which
   *  reads the new box from then on: the caller adds a box of it. */
  readonly onTool?: (tool: FlowTool, after: string | undefined, editor: StepEditor | undefined, into?: LineEnd) => void;
  /** A line drawn by hand from box `from`'s output into box `tile`'s input on `side` (docs/DATACUBE_FLOW_MODE_2026_10_24.md
   *  M2): the caller links it (setInput, live). */
  readonly onConnect?: (tile: string, side: InputSide, from: string) => void;
  /** A line into box `tile` on `side` taken off, or its settings' Freeze: the caller freezes it -- it reads a copy of what
   *  it read (setInput, a copy: FL6). */
  readonly onFreeze?: (tile: string, side: InputSide) => void;
  /** Ctrl+Z (Shift for redo) on the canvas: the page's own undo. */
  readonly onUndo?: (redo: boolean) => void;
  /** A file dropped onto the canvas (from the desktop): the caller adds an Input box over it. */
  readonly onFile?: (file: File) => void;
  /** Tidy Up (the canvas's button): the caller places every box by the flow's own rule (page-gestures.ts tidyCommands). */
  readonly onTidy?: () => void;
  /** Run (the canvas's button): the caller shows each box in turn, so each is counted (`counted`). */
  readonly onRun?: () => void;
  /** Duplicate (Ctrl+D on a box, or its menu): the caller adds a copy of it, reading what it reads. */
  readonly onDuplicate?: (id: string) => void;
  /** Each box's cube document as the page has it: what it does, and the boxes it reads (the lines into it). */
  readonly documentOf?: (id: string) => CubeDocument | undefined;
}

/** Where a line goes in: the box, and the side of it (a join's left or right, any other box's one input). */
export interface LineEnd {
  readonly tile: string;
  readonly side: InputSide;
}

/** A box as the board holds it: what it is called, and -- shown -- its grid's tile. */
interface Held {
  title: string;
  tile?: BandTile;
}

/** What the drawing reads of the board, and tells it (layout/flow-canvas.ts). */
export interface FlowBoardState {
  readonly layout: Flow;
  readonly shown: string | undefined;
  /** What the results pane says while no box is shown, when it says more than how to show one (a box not opened). */
  readonly noted: string | undefined;
  readonly editing: boolean;
  readonly options: FlowBoardOptions;
  /** The settings pane's body, where the shown box's grid docks the window that sets it. */
  readonly dock: HTMLElement;
  title(id: string): string | undefined;
  /** The tool a box was added as, while its grid does nothing yet (`addedAs`). */
  addedAs(id: string): FlowTool | undefined;
  /** Why a box's grid could not be made the last time it was shown (`refuse`), if it could not. */
  refusal(id: string): string | undefined;
  /** How many rows a box gave the last time its grid ran, while its chain is as it was then (`counted`). */
  count(id: string): number | undefined;
  /** A run in progress (Run): how many boxes are done, of how many. */
  readonly running: { readonly done: number; readonly of: number } | undefined;
  tile(id: string): BandTile | undefined;
}

/** The drawing, once fetched. */
interface Canvas {
  paint(): void;
  reveal(id: string): void;
  dispose(): void;
}

export class FlowBoard implements FlowBoardState {
  readonly #host: HTMLElement;
  /** What it says to a screen reader: its own, so it is said before the drawing has come. */
  readonly #live: HTMLElement;
  /** THE SETTINGS PANE's body (§3.4): where the shown box's grid docks the window that sets it (CubeAppOptions.dock) --
   *  made with the board, so a grid made before the drawing has come docks there all the same. */
  readonly dock: HTMLElement;
  readonly options: FlowBoardOptions;
  readonly #boxes = new Map<string, Held>();
  /** Each box added from the palette, as the tool it was added as: drawn so while its grid does nothing yet (a Filter
   *  with no condition is a Filter to set, not a Browse) -- the screen's, never the page's. */
  readonly #addedAs = new Map<string, FlowTool>();
  /** Why each box shown last could not be: its source not opened, refused in words (drawn red until it is shown). */
  readonly #refused = new Map<string, string>();
  /** Each box's rows the last time its grid ran (docs/DATACUBE_FLOW_MODE_2026_10_24.md M5, FL7: counted after a run, not
   *  kept live), and its chain then: a count of a chain that has changed since is no count of it. The screen's. */
  readonly #counts = new Map<string, { readonly n: number; readonly chain: string }>();
  #running: { readonly done: number; readonly of: number } | undefined;
  #layout: Flow = EMPTY_FLOW;
  #shown: string | undefined;
  #noted: string | undefined;
  #editing = true;
  #canvas: Canvas | undefined;
  #disposed = false;

  constructor(host: HTMLElement, options: FlowBoardOptions = {}) {
    this.#host = host;
    this.options = options;
    host.classList.add('dc-flow');
    this.#live = host.ownerDocument.createElement('div');
    this.#live.className = 'dc-board-live';
    this.#live.setAttribute('aria-live', 'polite');
    this.#live.setAttribute('role', 'status');
    this.dock = host.ownerDocument.createElement('div');
    this.dock.className = 'dc-flow-dock';
    host.append(this.#live);
    void lazily(() => import('./flow-canvas.ts')).then(({ FlowCanvas }) => {
      if (this.#disposed) return;
      this.#canvas = new FlowCanvas(host, this);
      this.#canvas.paint();
    });
  }

  /** Once the drawing has come and drawn the board (a test's, and a harness's, wait). */
  async drawn(): Promise<void> {
    await lazily(() => import('./flow-canvas.ts'));
  }

  /** Where each box is, as the page has it -- with any box a gesture made that its command has not placed yet. */
  get layout(): Flow {
    return this.#layout;
  }

  setLayout(flow: Flow): void {
    this.#layout = flow;
    this.#paint();
  }

  /** How many boxes are on it. */
  get size(): number {
    return this.#boxes.size;
  }

  /** A flow is no board of bands: a tile added has no columns to take. */
  get columns(): undefined {
    return undefined;
  }

  /** The box whose grid is shown (in the results pane), if one is. */
  get shown(): string | undefined {
    return this.#shown;
  }

  get editing(): boolean {
    return this.#editing;
  }

  get noted(): string | undefined {
    return this.#noted;
  }

  /** What the results pane says until a box is shown (a box whose source was not opened, and why), said as well. */
  note(text: string): void {
    this.#noted = text;
    this.say(text);
    this.#paint();
  }

  title(id: string): string | undefined {
    return this.#boxes.get(id)?.title;
  }

  addedAs(id: string): FlowTool | undefined {
    return this.#addedAs.get(id);
  }

  refusal(id: string): string | undefined {
    return this.#refused.get(id);
  }

  /** A box whose grid could not be made, and why -- or, `why` undefined, one that was made since. */
  refuse(id: string, why: string | undefined): void {
    if (why === undefined) this.#refused.delete(id);
    else this.#refused.set(id, why);
    this.#paint();
  }

  count(id: string): number | undefined {
    const c = this.#counts.get(id);
    return c !== undefined && c.chain === this.#chain(id) ? c.n : undefined;
  }

  /** A box's grid ran and gave `n` rows: its count, for its chain as it is now. */
  counted(id: string, n: number): void {
    const was = this.#counts.get(id);
    const chain = this.#chain(id);
    if (was?.n === n && was.chain === chain) return;
    this.#counts.set(id, { n, chain });
    this.#paint();
  }

  get running(): { readonly done: number; readonly of: number } | undefined {
    return this.#running;
  }

  setRunning(running: { readonly done: number; readonly of: number } | undefined): void {
    this.#running = running;
    this.#paint();
  }

  /** A box's chain as its documents say it: its source and query, each live input in it the chain of the box it reads --
   *  what its count is a count of. */
  #chain(id: string, seen: ReadonlySet<string> = new Set()): string {
    const doc = this.options.documentOf?.(id);
    if (doc === undefined || seen.has(id)) return '';
    const through = new Set([...seen, id]);
    const input = (i: CubeInput): string => (isLive(i) ? `<${this.#chain(i.grid, through)}>` : toJson(i));
    const s = doc.source;
    const source = s._type === 'rows' ? `rows ${input(s.input)}` : s._type === 'join'
      ? `join ${input(s.left)} ${input(s.right)} ${toJson(s.how)}` : toJson(s);
    return `${source}|${toJson(doc.query)}`;
  }

  /** A box added from the palette as `tool`: drawn as one until its grid does something. */
  addAs(id: string, tool: FlowTool): void {
    this.#addedAs.set(id, tool);
    this.#paint();
  }

  /** The grid's tile of a box shown. */
  tile(id: string): BandTile | undefined {
    return this.#boxes.get(id)?.tile;
  }

  /** A BOX SHOWN: its grid's tile in the results pane -- the box it is, or a new one after `near`. One is shown at a
   *  time: the box shown before is closed by its owner first (its grid is the owner's to let go). */
  add(tile: BandTile, near?: string): void {
    if (this.#shown !== undefined && this.#shown !== tile.id) throw new Error(`box ${this.#shown} is shown: it is closed before ${tile.id} is`);
    this.#hold(tile.id, tile.title, near).tile = tile;
    this.#shown = tile.id;
    this.#noted = undefined;
    this.#refused.delete(tile.id);
    this.#paint();
  }

  /** A box with no grid made for it: drawn from its document. */
  addBox(id: string, title: string, near?: string): void {
    this.#hold(id, title, near);
    this.#paint();
  }

  #hold(id: string, title: string, near?: string): Held {
    let held = this.#boxes.get(id);
    if (held === undefined) {
      held = { title };
      this.#boxes.set(id, held);
    }
    held.title = title;
    if (!this.#layout.boxes.some((b) => b.tile === id)) this.#layout = placeAfter(this.#layout, id, near);
    return held;
  }

  /** A box shown, closed: its grid's element taken out of the results pane, the box kept. */
  close(id: string): void {
    const held = this.#boxes.get(id);
    if (held === undefined) return;
    held.tile?.element.remove();
    delete held.tile;
    if (this.#shown === id) this.#shown = undefined;
    this.#paint();
  }

  /** A box off the canvas (its grid's element taken out, not destroyed). */
  remove(id: string): void {
    this.close(id);
    this.#boxes.delete(id);
    this.#addedAs.delete(id);
    this.#refused.delete(id);
    this.#counts.delete(id);
    this.#layout = removeBox(this.#layout, id);
    this.#paint();
  }

  rename(id: string, title: string): void {
    const held = this.#boxes.get(id);
    if (held === undefined) return;
    held.title = title;
    this.#paint();
  }

  setEditing(editing: boolean): void {
    this.#editing = editing;
    this.#paint();
  }

  /** What changed that the board draws from its documents (a box's settings, its inputs): drawn again. */
  repaint(): void {
    this.#paint();
  }

  /** Said to a screen reader. */
  say(text: string): void {
    this.#live.textContent = text;
  }

  /** A box scrolled into view. */
  reveal(id: string): void {
    this.#canvas?.reveal(id);
  }

  dispose(): void {
    this.#disposed = true;
    for (const held of this.#boxes.values()) held.tile?.element.remove();
    this.#boxes.clear();
    this.#canvas?.dispose();
    this.#canvas = undefined;
    this.#host.replaceChildren();
    this.#host.classList.remove('dc-flow');
  }

  #paint(): void {
    this.#canvas?.paint();
  }
}
