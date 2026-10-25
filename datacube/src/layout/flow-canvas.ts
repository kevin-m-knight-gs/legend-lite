// A FLOW SHEET DRAWN (docs/DATACUBE_FLOW_MODE_2026_10_24.md §3.4; layout/flow-board.ts, which fetches this the first
// time a flow sheet is made): the palette on the left, the canvas of boxes and the lines between them, and below it the
// results pane -- the grid of the box shown, a full DataCube.
//
// What it draws is the board's (FlowBoardState): where each box is, what each is called, which is shown; what each box
// does and what it reads, from its document (flow-boxes.ts boxSays, live-inputs.ts liveGrids). What the hand does is
// told, never applied here: a box let go somewhere else is its place told (onMove), a click shows it (onSelect), a tool
// picked or dropped adds a box of it (onTool) -- each the caller's page command, and the board put at what it makes.
//
// SMOOTH, as the band board is: a dragged box follows the pointer (a transform), its lines with it, and its place is
// told once, on drop. A gesture owns its pointer to the end: let go (applied), Escape, a lost capture (undone).
//
// THE KEYBOARD: Tab moves among the palette's tools and the boxes; Enter shows a box, the arrows move it (Shift: further),
// Delete takes it off, F2 renames it; Enter on a tool adds a box of it after the box shown. A box's inputs are set in its
// settings too (Input, a join's Left and Right): a box of the flow, or a frozen copy.
//
// LINES (docs/DATACUBE_FLOW_MODE_2026_10_24.md M2): drawn from a box's output port onto another box, a line links it (a
// join's left or right by where it is let go: its top half, its bottom); a line clicked is selected, and Delete (or its
// ×) takes it off -- the box reading it then reads a copy of what it read (frozen); a tool dropped on a line goes between
// the two boxes.

import { BOX_HEIGHT, BOX_WIDTH, MARGIN, type Flow } from './flow.ts';
import type { FlowBoardState } from './flow-board.ts';
import type { LineEnd } from './flow-board.ts';
import type { InputSide } from '../cube-document.ts';
import { boxInputs, boxSays, FLOW_TOOLS, PALETTE, readersOf, TOOL_EDITORS, type BoxInput, type BoxSays, type FlowTool } from '../flow-boxes.ts';
import { gridTitle } from '../page-ids.ts';

/** How far a pressed box or tool moves before it is dragged rather than clicked, in pixels. */
const DRAG_PX = 4;
/** An arrow key's step for a box, and Shift's. */
const STEP = 8;
const BIG_STEP = 32;
/** The results pane's least height, and the canvas's, in pixels. */
const LEAST_PANE = 120;
/** The settings pane's least width, and the canvas's beside it, in pixels. */
const LEAST_SETTINGS = 320;
const LEAST_CANVAS = 240;

const SVG = 'http://www.w3.org/2000/svg';

/** Where a side's port is on a box's left edge, from its top: a join's left above its right, any other box's in the
 *  middle (as Alteryx's Join has its L and R anchors). */
const portY = (side: InputSide): number => (side === 'left' ? BOX_HEIGHT * 0.3 : side === 'right' ? BOX_HEIGHT * 0.7 : BOX_HEIGHT / 2);

/** A side's name, as a port and the settings say it. */
const SIDE_WORDS: Readonly<Record<InputSide, string>> = { input: 'Input', left: 'Left', right: 'Right' };

/** A line's key: the box it goes into, and its side. */
const lineKey = (end: LineEnd): string => `${end.tile}:${end.side}`;

/** Each tool's icon, as strokes in a 16-unit square. */
const ICONS: Readonly<Record<FlowTool, string>> = {
  input: 'M3 4c0-1.1 2.2-2 5-2s5 .9 5 2v8c0 1.1-2.2 2-5 2s-5-.9-5-2zM3 4c0 1.1 2.2 2 5 2s5-.9 5-2M3 8c0 1.1 2.2 2 5 2s5-.9 5-2',
  join: 'M6 3.5a4.5 4.5 0 1 0 0 9a4.5 4.5 0 1 0 0-9M10 3.5a4.5 4.5 0 1 0 0 9a4.5 4.5 0 1 0 0-9',
  filter: 'M2 3h12l-4.5 5.5V13l-3 1.5V8.5z',
  formula: 'M10.5 2.5c-2 0-2.5 1-2.8 3L6.8 11c-.3 2-.8 3-2.8 3M5 6.5h5.5M10 9l3.5 4M13.5 9L10 13',
  select: 'M2 3h3v3H2zM7.5 4.5H14M2 10h3v3H2zM7.5 11.5H14M2.8 4.5l.7.7 1-1.4',
  sort: 'M4 2v12M1.5 11.5L4 14l2.5-2.5M9 4h6M9 8h4M9 12h2',
  summarize: 'M12.5 3H3.5l4.5 5-4.5 5h9',
  browse: 'M1 8s2.5-5 7-5 7 5 7 5-2.5 5-7 5-7-5-7-5zM8 6a2 2 0 1 0 0 4a2 2 0 1 0 0-4',
};

/** A tool's icon, drawn. */
function icon(doc: Document, tool: FlowTool): SVGSVGElement {
  const svg = doc.createElementNS(SVG, 'svg');
  svg.setAttribute('viewBox', '0 0 16 16');
  svg.setAttribute('aria-hidden', 'true');
  const path = doc.createElementNS(SVG, 'path');
  path.setAttribute('d', ICONS[tool]);
  svg.append(path);
  return svg;
}

/** A box as drawn: its element and its parts, kept from paint to paint (a box focused stays focused). */
interface Drawn {
  readonly root: HTMLElement;
  readonly icon: HTMLElement;
  readonly name: HTMLElement;
  readonly says: HTMLElement;
  readonly remove: HTMLButtonElement;
  /** What it was drawn as, to draw it again only when that changes. */
  key: string;
  renaming: boolean;
}

export class FlowCanvas {
  readonly #doc: Document;
  readonly #host: HTMLElement;
  readonly #board: FlowBoardState;
  readonly #palette: HTMLElement;
  readonly #canvas: HTMLElement;
  readonly #plane: HTMLElement;
  readonly #lines: SVGSVGElement;
  readonly #hint: HTMLElement;
  readonly #split: HTMLElement;
  readonly #settings: HTMLElement;
  readonly #settingsTitle: HTMLElement;
  readonly #settingsEmpty: HTMLElement;
  readonly #side: HTMLElement;
  readonly #results: HTMLElement;
  readonly #head: HTMLElement;
  readonly #headTitle: HTMLElement;
  readonly #headActions: HTMLElement;
  readonly #body: HTMLElement;
  readonly #empty: HTMLElement;
  readonly #boxes = new Map<string, Drawn>();
  /** A box being dragged: where it is drawn while it is. */
  #dragged: { readonly id: string; x: number; y: number } | undefined;
  /** The gesture in flight: how to end it. */
  #gesture: ((apply: boolean) => void) | undefined;
  /** The box a dragged tool is over, marked. */
  #target: string | undefined;
  /** The results pane's height, as the divider left it (the screen's, not the page's). */
  #paneHeight: number | undefined;
  /** What the settings pane's placeholder was drawn as. */
  #settingsKey = '';
  /** The box clicked last: the settings pane's while no box is shown (one whose source was not opened). */
  #clicked: string | undefined;
  /** The line selected (clicked), which Delete takes off; and the one a dragged tool is over. */
  #line: LineEnd | undefined;
  #lineTarget: LineEnd | undefined;
  /** The line being drawn from a box's output, as the pointer has it (the plane's units), and the side it would go into. */
  #wire: { readonly from: string; x: number; y: number; to?: LineEnd } | undefined;
  /** A selected line's ×, at its middle. */
  readonly #unlink: HTMLButtonElement;
  /** The settings' inputs: a box's Input (a join's Left and Right), each a box of the flow or a frozen copy. */
  readonly #inputs: HTMLElement;
  #inputsKey = '';

  constructor(host: HTMLElement, board: FlowBoardState) {
    this.#host = host;
    this.#doc = host.ownerDocument;
    this.#board = board;
    const doc = this.#doc;
    const el = (tag: string, cls: string): HTMLElement => {
      const e = doc.createElement(tag);
      e.className = cls;
      return e;
    };
    this.#palette = el('div', 'dc-flow-palette');
    this.#palette.setAttribute('role', 'toolbar');
    this.#palette.setAttribute('aria-label', 'Tools');
    this.#palette.setAttribute('aria-orientation', 'vertical');
    for (const tool of PALETTE) this.#palette.append(this.#tool(tool));
    const main = el('div', 'dc-flow-main');
    this.#canvas = el('div', 'dc-flow-canvas');
    this.#canvas.tabIndex = -1;
    this.#plane = el('div', 'dc-flow-plane');
    this.#lines = doc.createElementNS(SVG, 'svg');
    this.#lines.setAttribute('class', 'dc-flow-lines');
    this.#lines.setAttribute('aria-hidden', 'true');
    this.#hint = el('div', 'dc-flow-hint');
    this.#hint.textContent = 'Drag an Input onto the canvas to start, then add tools after it: each box is one step, and its rows show below.';
    this.#unlink = doc.createElement('button');
    this.#unlink.type = 'button';
    this.#unlink.className = 'dc-flow-unlink';
    this.#unlink.textContent = '×';
    this.#unlink.title = 'Disconnect: the box it goes into keeps the rows it has now, frozen';
    this.#unlink.setAttribute('aria-label', 'Disconnect this line');
    this.#unlink.hidden = true;
    this.#unlink.addEventListener('pointerdown', (e) => e.stopPropagation());
    this.#unlink.addEventListener('click', () => this.#takeOffLine());
    this.#plane.append(this.#lines, this.#unlink);
    this.#canvas.append(this.#plane, this.#hint);
    // THE SETTINGS PANE (§3.4, Alteryx's Configuration window): the shown box's grid docks the window that sets it here;
    // with none docked, what the box does and how to set it
    this.#side = el('div', 'dc-flow-side');
    this.#side.setAttribute('role', 'separator');
    this.#side.setAttribute('aria-orientation', 'vertical');
    this.#side.setAttribute('aria-label', 'Resize the settings');
    this.#side.tabIndex = 0;
    this.#settings = el('section', 'dc-flow-settings');
    this.#settings.setAttribute('aria-label', 'Settings');
    const settingsHead = el('div', 'dc-flow-settings-head');
    this.#settingsTitle = el('div', 'dc-flow-settings-title');
    settingsHead.append(this.#settingsTitle);
    this.#settingsEmpty = el('div', 'dc-flow-settings-empty');
    this.#inputs = el('div', 'dc-flow-inputs');
    this.#settings.append(settingsHead, this.#inputs, board.dock, this.#settingsEmpty);
    const top = el('div', 'dc-flow-top');
    top.append(this.#canvas, this.#side, this.#settings);
    this.#split = el('div', 'dc-flow-split');
    this.#split.setAttribute('role', 'separator');
    this.#split.setAttribute('aria-orientation', 'horizontal');
    this.#split.setAttribute('aria-label', 'Resize the results');
    this.#split.tabIndex = 0;
    this.#results = el('section', 'dc-flow-results');
    this.#results.setAttribute('aria-label', 'Results');
    this.#head = el('div', 'dc-flow-results-head');
    this.#headTitle = el('div', 'dc-flow-results-title');
    this.#headActions = el('div', 'dc-flow-results-actions');
    this.#head.append(this.#headTitle, this.#headActions);
    this.#body = el('div', 'dc-flow-results-body');
    this.#empty = el('div', 'dc-flow-results-empty');
    this.#results.append(this.#head, this.#body);
    main.append(top, this.#split, this.#results);
    host.prepend(this.#palette, main);
    this.#canvas.addEventListener('keydown', (e) => this.#onCanvasKey(e));
    // A FILE DROPPED from the desktop: an Input over it, as the picker opens one
    const files = (e: DragEvent): boolean => this.#board.editing && (e.dataTransfer?.types ?? []).includes('Files');
    this.#canvas.addEventListener('dragover', (e) => {
      if (!files(e)) return;
      e.preventDefault();
      this.#canvas.classList.add('dc-flow-dropping');
    });
    this.#canvas.addEventListener('dragleave', (e) => {
      if (e.target === this.#canvas) this.#canvas.classList.remove('dc-flow-dropping');
    });
    this.#canvas.addEventListener('drop', (e) => {
      this.#canvas.classList.remove('dc-flow-dropping');
      if (!files(e)) return;
      e.preventDefault();
      const file = e.dataTransfer?.files[0];
      if (file !== undefined) this.#board.options.onFile?.(file);
    });
    this.#canvas.addEventListener('pointerdown', (e) => {
      // a press on the canvas itself, not a box: focus there, so Ctrl+Z reaches the page -- and any line let go
      if (e.target === this.#canvas || e.target === this.#plane || e.target === this.#lines) {
        this.#canvas.focus({ preventScroll: true });
        this.#selectLine(undefined);
      }
    });
    this.#split.addEventListener('pointerdown', (e) => this.#startSplit(e));
    this.#side.addEventListener('pointerdown', (e) => this.#startSide(e));
    this.#side.addEventListener('keydown', (e) => {
      if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
      e.preventDefault();
      this.#setSide(this.#settings.offsetWidth + (e.key === 'ArrowLeft' ? BIG_STEP : -BIG_STEP));
    });
    this.#split.addEventListener('keydown', (e) => {
      if (e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return;
      e.preventDefault();
      this.#setPane((this.#paneHeight ?? this.#results.offsetHeight) + (e.key === 'ArrowUp' ? BIG_STEP : -BIG_STEP));
    });
  }

  /** The board drawn as it is now. */
  paint(): void {
    const board = this.#board;
    const layout = this.#placed(board.layout);
    const editing = board.editing;
    this.#host.classList.toggle('dc-flow-view', !editing);
    this.#palette.hidden = !editing;
    // the boxes: each one's element kept, drawn again where it changed
    const ids = new Set(layout.boxes.map((b) => b.tile));
    for (const [id, drawn] of [...this.#boxes]) {
      if (ids.has(id)) continue;
      drawn.root.remove();
      this.#boxes.delete(id);
    }
    for (const box of layout.boxes) {
      const drawn = this.#boxes.get(box.tile) ?? this.#make(box.tile);
      this.#paintBox(box.tile, drawn);
      drawn.root.style.transform = `translate(${box.x}px, ${box.y}px)`;
    }
    this.#paintLines(layout);
    // the plane as large as its boxes, and the canvas's room past them
    const right = Math.max(0, ...layout.boxes.map((b) => b.x + BOX_WIDTH)) + MARGIN;
    const bottom = Math.max(0, ...layout.boxes.map((b) => b.y + BOX_HEIGHT)) + MARGIN;
    this.#plane.style.width = `${right}px`;
    this.#plane.style.height = `${bottom}px`;
    this.#hint.hidden = layout.boxes.length > 0 || !editing;
    this.#paintResults();
    this.#paintSettings();
  }

  /** A box scrolled into view. */
  reveal(id: string): void {
    const box = this.#placed(this.#board.layout).boxes.find((b) => b.tile === id);
    if (!box) return;
    const c = this.#canvas;
    if (box.x < c.scrollLeft) c.scrollLeft = Math.max(0, box.x - MARGIN);
    else if (box.x + BOX_WIDTH > c.scrollLeft + c.clientWidth) c.scrollLeft = box.x + BOX_WIDTH + MARGIN - c.clientWidth;
    if (box.y < c.scrollTop) c.scrollTop = Math.max(0, box.y - MARGIN);
    else if (box.y + BOX_HEIGHT > c.scrollTop + c.clientHeight) c.scrollTop = box.y + BOX_HEIGHT + MARGIN - c.clientHeight;
  }

  dispose(): void {
    this.#gesture?.(false);
    this.#palette.remove();
    this.#canvas.parentElement?.remove();
  }

  /** The flow as drawn: a box being dragged where the pointer has it. */
  #placed(flow: Flow): Flow {
    const d = this.#dragged;
    return d === undefined ? flow : { boxes: flow.boxes.map((b) => (b.tile === d.id ? { tile: d.id, x: d.x, y: d.y } : b)) };
  }

  // -- the palette --------------------------------------------------------------------------------------------------

  #tool(tool: FlowTool): HTMLButtonElement {
    const doc = this.#doc;
    const button = doc.createElement('button');
    button.type = 'button';
    button.className = 'dc-flow-tool';
    button.dataset['tool'] = tool;
    button.dataset['group'] = FLOW_TOOLS[tool].group;
    const glyph = doc.createElement('span');
    glyph.className = 'dc-flow-icon';
    glyph.append(icon(doc, tool));
    const name = doc.createElement('span');
    name.className = 'dc-flow-tool-name';
    name.textContent = FLOW_TOOLS[tool].name;
    button.append(glyph, name);
    button.title = `${FLOW_TOOLS[tool].name}: ${FLOW_TOOLS[tool].does}. Click to add one after the box shown, or drag it onto a box.`;
    // a press: a click adds one after the box shown; dragged, after the box it is let go on
    button.addEventListener('pointerdown', (e) => this.#startTool(tool, button, e));
    button.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter' && e.key !== ' ') return;
      e.preventDefault();
      this.#board.options.onTool?.(tool, this.#board.shown, TOOL_EDITORS[tool]);
    });
    return button;
  }

  #startTool(tool: FlowTool, button: HTMLElement, e: PointerEvent): void {
    if (e.button !== 0 || !this.#board.editing) return;
    e.preventDefault();
    const start = { x: e.clientX, y: e.clientY };
    let ghost: HTMLElement | undefined;
    this.#own(button, e, (ev) => {
      if (ghost === undefined && Math.hypot(ev.clientX - start.x, ev.clientY - start.y) < DRAG_PX) return;
      if (ghost === undefined) {
        ghost = button.cloneNode(true) as HTMLElement;
        ghost.classList.add('dc-flow-ghost');
        this.#doc.body.append(ghost);
      }
      ghost.style.transform = `translate(${ev.clientX + 8}px, ${ev.clientY + 8}px)`;
      const box = this.#boxAt(ev.clientX, ev.clientY);
      this.#mark(box);
      // over a line, not a box: the tool goes between the boxes it links (an Input reads none: never between)
      this.#markLine(box === undefined && tool !== 'input' ? this.#lineAt(ev.clientX, ev.clientY) : undefined);
    }, (apply, ev) => {
      ghost?.remove();
      const over = ev ? this.#boxAt(ev.clientX, ev.clientY) : undefined;
      const line = this.#lineTarget;
      this.#mark(undefined);
      this.#markLine(undefined);
      if (!apply) return;
      const editor = TOOL_EDITORS[tool];
      if (ghost === undefined) {
        button.focus({ preventScroll: true });
        this.#board.options.onTool?.(tool, this.#board.shown, editor);
        return;
      }
      // let go on a box: after it; on a line: between its boxes; on the canvas: after none; anywhere else: nothing
      if (over !== undefined) this.#board.options.onTool?.(tool, over, editor);
      else if (line !== undefined) this.#board.options.onTool?.(tool, this.#fromOf(line), editor, line);
      else if (ev && this.#onCanvas(ev.clientX, ev.clientY)) this.#board.options.onTool?.(tool, undefined, editor);
    });
  }

  /** The box under a point of the window, if one is. */
  #boxAt(x: number, y: number): string | undefined {
    for (const [id, drawn] of this.#boxes) {
      const r = drawn.root.getBoundingClientRect();
      if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return id;
    }
    return undefined;
  }

  #onCanvas(x: number, y: number): boolean {
    const r = this.#canvas.getBoundingClientRect();
    return x >= r.left && x <= r.right && y >= r.top && y <= r.bottom;
  }

  #mark(id: string | undefined): void {
    if (this.#target === id) return;
    if (this.#target !== undefined) this.#boxes.get(this.#target)?.root.classList.remove('dc-flow-target');
    this.#target = id;
    if (id !== undefined) this.#boxes.get(id)?.root.classList.add('dc-flow-target');
  }

  // -- the boxes ----------------------------------------------------------------------------------------------------

  #make(id: string): Drawn {
    const doc = this.#doc;
    const root = doc.createElement('div');
    root.className = 'dc-flow-box';
    root.dataset['tile'] = id;
    root.tabIndex = 0;
    root.setAttribute('role', 'button');
    const port = (cls: string, label?: string): HTMLElement => {
      const p = doc.createElement('span');
      p.className = `dc-flow-port ${cls}`;
      if (label !== undefined) p.textContent = label;
      return p;
    };
    // its inputs: one, or a join's left and right (CSS shows the ones its tool has)
    const into = port('dc-flow-port-in');
    const left = port('dc-flow-port-in dc-flow-port-left', 'L');
    const right = port('dc-flow-port-in dc-flow-port-right', 'R');
    // its output: pressed and dragged onto another box, a line linking them
    const out = port('dc-flow-port-out');
    out.title = 'Drag onto another box to link it here';
    out.addEventListener('pointerdown', (e) => this.#startWire(id, out, e));
    const glyph = doc.createElement('span');
    glyph.className = 'dc-flow-icon';
    const text = doc.createElement('div');
    text.className = 'dc-flow-text';
    const name = doc.createElement('div');
    name.className = 'dc-flow-name';
    const says = doc.createElement('div');
    says.className = 'dc-flow-says';
    text.append(name, says);
    const remove = doc.createElement('button');
    remove.type = 'button';
    remove.className = 'dc-flow-x';
    remove.textContent = '×';
    remove.addEventListener('pointerdown', (e) => e.stopPropagation());
    remove.addEventListener('click', (e) => {
      e.stopPropagation();
      this.#board.options.onRemove?.(id);
    });
    root.append(into, left, right, glyph, text, remove, out);
    const drawn: Drawn = { root, icon: glyph, name, says, remove, key: '', renaming: false };
    root.addEventListener('pointerdown', (e) => this.#startBox(id, drawn, e));
    root.addEventListener('keydown', (e) => this.#onBoxKey(id, drawn, e));
    name.addEventListener('dblclick', (e) => {
      e.stopPropagation();
      this.#rename(id, drawn);
    });
    this.#boxes.set(id, drawn);
    this.#plane.append(root);
    return drawn;
  }

  /** The columns a box hands on, as its document says them: its query's columns and calculated columns, but those it
   *  hides (a Select's) -- read, never run for. */
  #columnsOf(id: string): string[] {
    const doc = this.#board.options.documentOf?.(id);
    if (doc === undefined) return [];
    const settings = doc.configuration['columns'];
    const hidden = (name: string): boolean => {
      if (settings === null || typeof settings !== 'object') return false;
      const c = (settings as Record<string, unknown>)[name];
      return c !== null && typeof c === 'object' && (c as Record<string, unknown>)['hidden'] === true;
    };
    return [...doc.query.columns.map((c) => c.name), ...doc.query.derived.map((d) => d.name)].filter((n) => !hidden(n));
  }

  /** A box shown, and its settings with it: the window of its grid's that sets what it does (TOOL_EDITORS). */
  #select(id: string): void {
    this.#clicked = id;
    const said = this.#said(id);
    this.#board.options.onSelect?.(id, said ? TOOL_EDITORS[said.tool] : undefined);
  }

  /** What a box does, as it is drawn: its document's (boxSays) -- or, added as a tool and doing nothing yet, that tool,
   *  not set yet. */
  #said(id: string): BoxSays | undefined {
    const doc = this.#board.options.documentOf?.(id);
    if (doc === undefined) return undefined;
    const said = boxSays(doc);
    const added = this.#board.addedAs(id);
    return said.tool === 'browse' && added !== undefined ? { tool: added, also: [], says: 'not set yet' } : said;
  }

  /** What a box is called on the canvas: the name a person gave it, else its tool's (a flow never says "grid"). */
  #nameOf(id: string, tool: FlowTool): string {
    const title = this.#board.title(id);
    return title === undefined || title === gridTitle(id) ? FLOW_TOOLS[tool].name : title;
  }

  #paintBox(id: string, drawn: Drawn): void {
    const said = this.#said(id);
    const tool = said?.tool ?? 'browse';
    const name = this.#nameOf(id, tool);
    const says = said === undefined ? '' : said.also.length > 0
      ? `${said.says} · ${said.also.map((t) => FLOW_TOOLS[t].name).join(' · ')}` : said.says;
    const shown = this.#board.shown === id;
    const refused = this.#board.refusal(id);
    const columns = this.#columnsOf(id);
    // a side read from a copy (FL6, a line taken off): frozen, said so
    const frozen = this.#inputsOf(id).flatMap((i) => ('frozen' in i ? [i.frozen] : []));
    const key = JSON.stringify([tool, name, says, shown, this.#board.editing, refused, columns, frozen]);
    if (key === drawn.key) return;
    drawn.key = key;
    drawn.root.dataset['tool'] = tool;
    drawn.root.dataset['group'] = FLOW_TOOLS[tool].group;
    drawn.root.classList.toggle('dc-flow-shown', shown);
    drawn.root.classList.toggle('dc-flow-refused', refused !== undefined);
    drawn.root.classList.toggle('dc-flow-frozen', frozen.length > 0);
    drawn.root.setAttribute('aria-pressed', String(shown));
    const frozenWords = frozen.length > 0 ? `Frozen: reads a copy of what ${frozen.join(' and ')} was` : undefined;
    drawn.root.setAttribute('aria-label', `${name}: ${refused ?? says}${frozenWords !== undefined ? `. ${frozenWords}` : ''}`);
    // what it hands on: its columns, as its document says them (no query run for it)
    drawn.root.title = refused ?? [frozenWords, columns.length > 0 ? `${columns.length} columns out: ${columns.join(', ')}` : undefined]
      .filter((t) => t !== undefined).join('\n');
    drawn.icon.replaceChildren(icon(this.#doc, tool));
    if (!drawn.renaming) drawn.name.textContent = name;
    drawn.says.textContent = says;
    drawn.says.title = says;
    drawn.remove.hidden = !this.#board.editing;
    drawn.remove.title = `Remove ${name}`;
    drawn.remove.setAttribute('aria-label', `Remove ${name}`);
  }

  #startBox(id: string, drawn: Drawn, e: PointerEvent): void {
    if (e.button !== 0 || drawn.renaming) return;
    const box = this.#board.layout.boxes.find((b) => b.tile === id);
    if (!box) return;
    const start = { x: e.clientX, y: e.clientY };
    let moved = false;
    // a locked page moves nothing: a press only shows the box
    const editing = this.#board.editing;
    this.#own(drawn.root, e, (ev) => {
      const dx = ev.clientX - start.x;
      const dy = ev.clientY - start.y;
      if (!editing || (!moved && Math.hypot(dx, dy) < DRAG_PX)) return;
      moved = true;
      drawn.root.classList.add('dc-flow-dragging');
      this.#dragged = { id, x: Math.max(0, box.x + dx), y: Math.max(0, box.y + dy) };
      drawn.root.style.transform = `translate(${this.#dragged.x}px, ${this.#dragged.y}px)`;
      this.#paintLines(this.#placed(this.#board.layout));
    }, (apply) => {
      const at = this.#dragged;
      this.#dragged = undefined;
      drawn.root.classList.remove('dc-flow-dragging');
      if (!moved) {
        if (apply) this.#select(id);
        return;
      }
      if (apply && at !== undefined) this.#board.options.onMove?.(id, Math.round(at.x), Math.round(at.y));
      // drawn where the page has it: moved, once its command places it; else back where it was
      this.paint();
    });
  }

  #onBoxKey(id: string, drawn: Drawn, e: KeyboardEvent): void {
    if (drawn.renaming || e.target !== drawn.root) return;
    const o = this.#board.options;
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      this.#select(id);
      return;
    }
    if (!this.#board.editing) return;
    if (e.key === 'Delete' || e.key === 'Backspace') {
      e.preventDefault();
      o.onRemove?.(id);
      return;
    }
    if (e.key === 'F2') {
      e.preventDefault();
      this.#rename(id, drawn);
      return;
    }
    const step = e.shiftKey ? BIG_STEP : STEP;
    const by: Readonly<Record<string, readonly [number, number]>> = {
      ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step],
    };
    const move = by[e.key];
    const box = this.#board.layout.boxes.find((b) => b.tile === id);
    if (move === undefined || box === undefined) return;
    e.preventDefault();
    o.onMove?.(id, Math.max(0, box.x + move[0]), Math.max(0, box.y + move[1]));
  }

  #onCanvasKey(e: KeyboardEvent): void {
    // a line selected: Delete takes it off, Escape lets it go
    if (this.#line !== undefined && (e.key === 'Delete' || e.key === 'Backspace')) {
      e.preventDefault();
      this.#takeOffLine();
      return;
    }
    if (this.#line !== undefined && e.key === 'Escape') {
      this.#selectLine(undefined);
      return;
    }
    if (!(e.ctrlKey || e.metaKey) || e.key.toLowerCase() !== 'z') return;
    const target = e.target as HTMLElement | null;
    // a field (a box being renamed) keeps its own undo
    if (target?.tagName === 'INPUT') return;
    e.preventDefault();
    this.#board.options.onUndo?.(e.shiftKey);
  }

  /** A box renamed in place: its name a field until Enter (or leaving it) says it, or Escape keeps the old one. */
  #rename(id: string, drawn: Drawn): void {
    if (!this.#board.editing || drawn.renaming) return;
    drawn.renaming = true;
    const field = this.#doc.createElement('input');
    field.className = 'dc-flow-rename';
    const was = drawn.name.textContent ?? '';
    field.value = was;
    field.setAttribute('aria-label', 'Box name');
    drawn.name.replaceChildren(field);
    field.focus();
    field.select();
    let done = false;
    const finish = (apply: boolean): void => {
      if (done) return;
      done = true;
      drawn.renaming = false;
      drawn.key = '';
      // the name as typed, trimmed: a blank one, or the one it had, renames nothing
      const name = field.value.trim();
      this.paint();
      drawn.root.focus({ preventScroll: true });
      if (apply && name !== '' && name !== was) this.#board.options.onRename?.(id, name);
    };
    field.addEventListener('keydown', (e) => {
      e.stopPropagation();
      if (e.key === 'Enter') finish(true);
      else if (e.key === 'Escape') finish(false);
    });
    field.addEventListener('blur', () => finish(true));
    field.addEventListener('pointerdown', (e) => e.stopPropagation());
  }

  // -- the lines ----------------------------------------------------------------------------------------------------

  /** Each box's inputs, from its document (flow-boxes.ts boxInputs): none for one with no document. */
  #inputsOf(id: string): BoxInput[] {
    const doc = this.#board.options.documentOf?.(id);
    return doc === undefined ? [] : boxInputs(doc);
  }

  /** The box a line comes out of. */
  #fromOf(line: LineEnd): string | undefined {
    const i = this.#inputsOf(line.tile).find((x) => x.side === line.side);
    return i !== undefined && 'grid' in i ? i.grid : undefined;
  }

  /**
   * A line into each box from each box it reads live: out of its right side, into the left of the box reading it (a
   * join's left above its right) -- each with a wider stroke beneath it that a pointer finds, and the line being drawn
   * from a box's output.
   */
  #paintLines(flow: Flow): void {
    const at = new Map(flow.boxes.map((b) => [b.tile, b]));
    const curve = (x1: number, y1: number, x2: number, y2: number): string => {
      const bend = Math.max(32, Math.abs(x2 - x1) / 2);
      return `M${x1} ${y1}C${x1 + bend} ${y1} ${x2 - bend} ${y2} ${x2} ${y2}`;
    };
    const lines: { readonly end: LineEnd; readonly d: string; readonly mid: { x: number; y: number } }[] = [];
    for (const box of flow.boxes) {
      for (const input of this.#inputsOf(box.tile)) {
        if (!('grid' in input)) continue;
        const a = at.get(input.grid);
        if (!a) continue;
        const x1 = a.x + BOX_WIDTH;
        const y1 = a.y + BOX_HEIGHT / 2;
        const x2 = box.x;
        const y2 = box.y + portY(input.side);
        lines.push({ end: { tile: box.tile, side: input.side }, d: curve(x1, y1, x2, y2), mid: { x: (x1 + x2) / 2, y: (y1 + y2) / 2 } });
      }
    }
    const w = this.#wire;
    const wire = w === undefined ? undefined : (() => {
      const a = at.get(w.from);
      return a === undefined ? undefined : curve(a.x + BOX_WIDTH, a.y + BOX_HEIGHT / 2, w.x, w.y);
    })();
    // a line selected or marked that is gone (its box taken off, linked elsewhere) is let go
    const keys = new Set(lines.map((l) => lineKey(l.end)));
    if (this.#line !== undefined && !keys.has(lineKey(this.#line))) this.#line = undefined;
    const selected = this.#line === undefined ? undefined : lineKey(this.#line);
    const target = this.#lineTarget === undefined ? undefined : lineKey(this.#lineTarget);
    const key = JSON.stringify([lines.map((l) => [lineKey(l.end), l.d]), wire, selected, target, this.#board.editing]);
    if (this.#lines.dataset['key'] !== key) {
      this.#lines.dataset['key'] = key;
      const path = (d: string, cls: string): SVGPathElement => {
        const p = this.#doc.createElementNS(SVG, 'path');
        p.setAttribute('d', d);
        p.setAttribute('class', cls);
        return p;
      };
      this.#lines.replaceChildren(...lines.flatMap((l) => {
        const k = lineKey(l.end);
        const state = k === selected ? ' dc-flow-line-selected' : k === target ? ' dc-flow-line-target' : '';
        const hit = path(l.d, 'dc-flow-hit');
        hit.dataset['tile'] = l.end.tile;
        hit.dataset['side'] = l.end.side;
        if (this.#board.editing) hit.addEventListener('pointerdown', (e) => this.#pressLine(l.end, e));
        return [hit, path(l.d, `dc-flow-line${state}`)];
      }), ...(wire === undefined ? [] : [path(wire, 'dc-flow-line dc-flow-wire')]));
    }
    // the selected line's ×, at its middle
    const mid = selected === undefined ? undefined : lines.find((l) => lineKey(l.end) === selected)?.mid;
    this.#unlink.hidden = mid === undefined || !this.#board.editing;
    if (mid !== undefined) this.#unlink.style.transform = `translate(${mid.x - 9}px, ${mid.y - 9}px)`;
  }

  /** A line pressed: selected (its × shown, Delete takes it off). */
  #pressLine(end: LineEnd, e: PointerEvent): void {
    if (e.button !== 0) return;
    e.preventDefault();
    e.stopPropagation();
    this.#canvas.focus({ preventScroll: true });
    this.#selectLine(end);
  }

  #selectLine(end: LineEnd | undefined): void {
    if ((end === undefined ? undefined : lineKey(end)) === (this.#line === undefined ? undefined : lineKey(this.#line))) return;
    this.#line = end;
    this.#paintLines(this.#placed(this.#board.layout));
  }

  /** The selected line taken off: the box it goes into frozen on that side (onFreeze). */
  #takeOffLine(): void {
    const line = this.#line;
    if (line === undefined || !this.#board.editing) return;
    this.#line = undefined;
    this.#paintLines(this.#placed(this.#board.layout));
    this.#board.options.onFreeze?.(line.tile, line.side);
  }

  /** The line under a point of the window, if one is (its wider stroke found). */
  #lineAt(x: number, y: number): LineEnd | undefined {
    for (const hit of this.#doc.elementsFromPoint(x, y)) {
      if (!(hit instanceof SVGPathElement) || !hit.classList.contains('dc-flow-hit')) continue;
      const tile = hit.dataset['tile'];
      const side = hit.dataset['side'] as InputSide | undefined;
      if (tile !== undefined && side !== undefined) return { tile, side };
    }
    return undefined;
  }

  #markLine(end: LineEnd | undefined): void {
    if ((end === undefined ? undefined : lineKey(end)) === (this.#lineTarget === undefined ? undefined : lineKey(this.#lineTarget))) return;
    this.#lineTarget = end;
    this.#paintLines(this.#placed(this.#board.layout));
  }

  /** Where a line let go at a point of the window goes in: the box under it that reads another's rows -- a join's left
   *  or right by the half it is let go on -- never `from` itself, nor a box that reads `from` already on that side. */
  #endAt(from: string, x: number, y: number): LineEnd | undefined {
    const tile = this.#boxAt(x, y);
    if (tile === undefined || tile === from) return undefined;
    const inputs = this.#inputsOf(tile);
    if (inputs.length === 0) return undefined;
    const r = this.#boxes.get(tile)!.root.getBoundingClientRect();
    const side: InputSide = inputs.length === 1 ? inputs[0]!.side : (y - r.top < r.height / 2 ? 'left' : 'right');
    const now = inputs.find((i) => i.side === side);
    if (now !== undefined && 'grid' in now && now.grid === from) return undefined;
    return { tile, side };
  }

  /** A line drawn from box `from`'s output: it follows the pointer, and let go on a box that reads another's rows, links
   *  it (onConnect). */
  #startWire(from: string, port: HTMLElement, e: PointerEvent): void {
    if (e.button !== 0 || !this.#board.editing) return;
    e.preventDefault();
    e.stopPropagation();
    this.#selectLine(undefined);
    const plane = (ev: PointerEvent): { x: number; y: number } => {
      const r = this.#plane.getBoundingClientRect();
      return { x: ev.clientX - r.left, y: ev.clientY - r.top };
    };
    this.#wire = { from, ...plane(e) };
    this.#host.classList.add('dc-flow-wiring');
    this.#own(port, e, (ev) => {
      const w = this.#wire;
      if (w === undefined) return;
      const to = this.#endAt(from, ev.clientX, ev.clientY);
      this.#mark(to?.tile);
      // drawn to the port it would go into, else to the pointer
      const box = to === undefined ? undefined : this.#placed(this.#board.layout).boxes.find((b) => b.tile === to.tile);
      Object.assign(w, box !== undefined && to !== undefined ? { x: box.x, y: box.y + portY(to.side), to } : { ...plane(ev), to: undefined });
      this.#paintLines(this.#placed(this.#board.layout));
    }, (apply) => {
      const to = this.#wire?.to;
      this.#wire = undefined;
      this.#host.classList.remove('dc-flow-wiring');
      this.#mark(undefined);
      this.#paintLines(this.#placed(this.#board.layout));
      if (apply && to !== undefined) this.#board.options.onConnect?.(to.tile, to.side, from);
    });
  }

  // -- the results pane ---------------------------------------------------------------------------------------------

  #paintResults(): void {
    const id = this.#board.shown;
    const tile = id === undefined ? undefined : this.#board.tile(id);
    if (tile === undefined) {
      const boxes = this.#board.layout.boxes.length;
      this.#empty.textContent = this.#board.noted
        ?? (boxes === 0 ? 'No rows yet: a flow starts with an Input.' : 'Click a box to see its rows here.');
      if (this.#body.firstChild !== this.#empty || this.#body.childNodes.length !== 1) this.#body.replaceChildren(this.#empty);
      this.#headTitle.textContent = 'Results';
      this.#headActions.replaceChildren();
      return;
    }
    // the box's grid placed once, as the band board places a tile: never moved while it is shown
    if (this.#body.firstChild !== tile.element || this.#body.childNodes.length !== 1) this.#body.replaceChildren(tile.element);
    this.#headTitle.textContent = this.#nameOf(id!, this.#said(id!)?.tool ?? 'browse');
    const actions = tile.actions ?? [];
    if (actions.some((a, i) => this.#headActions.children[i] !== a) || this.#headActions.children.length !== actions.length) {
      this.#headActions.replaceChildren(...actions);
    }
  }

  /**
   * THE SETTINGS' INPUTS, docked window or none (M2): what the box reads on each side -- a box of the flow (none that
   * reads it: a box reading its own rows), or the copy it reads, frozen -- each changed there as a line drawn to it would.
   */
  #paintInputs(id: string | undefined): void {
    const inputs = id === undefined ? [] : this.#inputsOf(id);
    const boxes = this.#board.layout.boxes.map((b) => b.tile);
    const readers = id === undefined || inputs.length === 0 ? new Set<string>()
      : readersOf(id, boxes, (b) => this.#board.options.documentOf?.(b));
    const offered = boxes.filter((b) => b !== id && !readers.has(b)).map((b) => ({ id: b, name: this.#nameOf(b, this.#said(b)?.tool ?? 'browse') }));
    const key = JSON.stringify([id, inputs, offered, this.#board.editing]);
    if (key === this.#inputsKey) return;
    this.#inputsKey = key;
    this.#inputs.hidden = inputs.length === 0;
    const d = this.#doc;
    this.#inputs.replaceChildren(...inputs.map((input) => {
      const row = d.createElement('label');
      row.className = 'dc-flow-input';
      const words = d.createElement('span');
      words.className = 'dc-flow-input-side';
      words.textContent = SIDE_WORDS[input.side];
      const pick = d.createElement('select');
      pick.className = 'dc-select';
      pick.disabled = !this.#board.editing;
      const option = (label: string, value: string, on: boolean): HTMLOptionElement => {
        const o = d.createElement('option');
        o.textContent = label;
        o.value = value;
        o.selected = on;
        return o;
      };
      for (const o of offered) pick.append(option(o.name, `grid:${o.id}`, 'grid' in input && input.grid === o.id));
      // a copy: what it reads when frozen -- or, live, Freeze: a copy of what it reads now
      pick.append(option('frozen' in input ? `A frozen copy of ${input.frozen}` : 'Freeze: a copy of its rows now', 'frozen', 'frozen' in input));
      pick.addEventListener('change', () => {
        const v = pick.value;
        if (v === 'frozen') this.#board.options.onFreeze?.(id!, input.side);
        else this.#board.options.onConnect?.(id!, input.side, v.slice('grid:'.length));
      });
      row.append(words, pick);
      return row;
    }));
  }

  /** The settings pane with nothing docked: the shown box's tool and what it is set to, and how to set it. */
  #paintSettings(): void {
    const clicked = this.#clicked !== undefined && this.#board.layout.boxes.some((b) => b.tile === this.#clicked) ? this.#clicked : undefined;
    const id = this.#board.shown ?? clicked;
    this.#paintInputs(id);
    const said = id === undefined ? undefined : this.#said(id);
    const name = id === undefined ? undefined : this.#nameOf(id, said?.tool ?? 'browse');
    const editor = said ? TOOL_EDITORS[said.tool] : undefined;
    const columns = id === undefined ? [] : this.#columnsOf(id);
    const refused = id === undefined ? undefined : this.#board.refusal(id);
    const key = JSON.stringify([id, name, said, editor !== undefined, columns, refused, this.#board.noted]);
    if (key === this.#settingsKey) return;
    this.#settingsKey = key;
    this.#settingsTitle.textContent = name === undefined ? 'Settings' : `Settings · ${name}`;
    const d = this.#doc;
    const parts: (string | HTMLElement)[] = [];
    if (refused !== undefined) {
      const why = d.createElement('p');
      why.className = 'dc-flow-settings-refused';
      why.textContent = refused;
      parts.push(why);
    }
    if (id === undefined || said === undefined) parts.push('Click a box to set what it does.');
    else {
      const what = d.createElement('p');
      what.textContent = `${FLOW_TOOLS[said.tool].name}: ${FLOW_TOOLS[said.tool].does}.`;
      const now = d.createElement('p');
      now.className = 'dc-flow-settings-now';
      now.textContent = said.says;
      parts.push(what, now);
      if (columns.length > 0) {
        const out = d.createElement('p');
        out.className = 'dc-flow-settings-columns';
        out.textContent = `${columns.length} columns out: ${columns.join(', ')}`;
        parts.push(out);
      }
      if (editor !== undefined) {
        const open = d.createElement('button');
        open.type = 'button';
        open.className = 'dc-picker-button dc-primary';
        open.textContent = 'Open its settings';
        open.addEventListener('click', () => this.#board.options.onSelect?.(id, editor));
        parts.push(open);
      } else if (said.tool === 'browse') {
        const tip = d.createElement('p');
        tip.textContent = 'It passes its input\'s rows on: drop a tool on it to add a step after it.';
        parts.push(tip);
      }
    }
    this.#settingsEmpty.replaceChildren(...parts);
  }

  #startSide(e: PointerEvent): void {
    if (e.button !== 0) return;
    e.preventDefault();
    const start = e.clientX;
    const from = this.#settings.offsetWidth;
    this.#own(this.#side, e, (ev) => this.#setSide(from - (ev.clientX - start)), (apply) => {
      if (!apply) this.#setSide(from);
    });
  }

  /** The settings pane's width, kept between its least and the canvas's least beside it. */
  #setSide(width: number): void {
    const room = this.#settings.parentElement?.clientWidth ?? 0;
    const most = room > 0 ? Math.max(LEAST_SETTINGS, room - LEAST_CANVAS) : width;
    this.#host.style.setProperty('--dc-flow-settings', `${Math.round(Math.min(most, Math.max(LEAST_SETTINGS, width)))}px`);
  }

  #startSplit(e: PointerEvent): void {
    if (e.button !== 0) return;
    e.preventDefault();
    const start = e.clientY;
    const from = this.#results.offsetHeight;
    this.#own(this.#split, e, (ev) => this.#setPane(from - (ev.clientY - start)), (apply) => {
      if (!apply) this.#setPane(from);
    });
  }

  /** The results pane's height, kept between the canvas's least and the pane's. */
  #setPane(height: number): void {
    const room = this.#host.clientHeight;
    const most = room > 0 ? Math.max(LEAST_PANE, room - LEAST_PANE) : height;
    this.#paneHeight = Math.round(Math.min(most, Math.max(LEAST_PANE, height)));
    this.#host.style.setProperty('--dc-flow-results', `${this.#paneHeight}px`);
  }

  /**
   * A gesture owning its pointer to its end: `move` at each move, `finish` once -- applied when let go, undone on
   * Escape, a cancelled pointer or a lost capture.
   */
  #own(el: HTMLElement, e: PointerEvent, move: (ev: PointerEvent) => void, finish: (apply: boolean, ev?: PointerEvent) => void): void {
    this.#gesture?.(false);
    try {
      el.setPointerCapture(e.pointerId);
    } catch {
      // a pointer the element cannot capture (a test's): followed all the same
    }
    const view = this.#doc.defaultView;
    const onMove = (ev: PointerEvent): void => move(ev);
    let ended = false;
    const end = (apply: boolean, ev?: PointerEvent): void => {
      if (ended) return;
      ended = true;
      this.#gesture = undefined;
      el.removeEventListener('pointermove', onMove);
      el.removeEventListener('pointerup', onUp);
      el.removeEventListener('pointercancel', onCancel);
      el.removeEventListener('lostpointercapture', onCancel);
      view?.removeEventListener('keydown', onKey, true);
      finish(apply, ev);
    };
    const onUp = (ev: PointerEvent): void => end(true, ev);
    const onCancel = (): void => end(false);
    const onKey = (ev: KeyboardEvent): void => {
      if (ev.key !== 'Escape') return;
      ev.stopPropagation();
      end(false);
    };
    el.addEventListener('pointermove', onMove);
    el.addEventListener('pointerup', onUp);
    el.addEventListener('pointercancel', onCancel);
    el.addEventListener('lostpointercapture', onCancel);
    view?.addEventListener('keydown', onKey, true);
    this.#gesture = (apply) => end(apply);
  }
}
