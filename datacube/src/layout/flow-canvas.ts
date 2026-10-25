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
// Delete takes it off, F2 renames it, Ctrl+D duplicates it; Enter on a tool adds a box of it after the box shown, and the
// palette's find field narrows its tools (Enter there adds the first). A box right-clicked offers all of it; Tidy Up, at
// the canvas's corner, places every box a column after the boxes it reads. A box's inputs are set in its
// settings too (Input, a join's Left and Right): a box of the flow, or a frozen copy.
//
// LINES (docs/DATACUBE_FLOW_MODE_2026_10_24.md M2): drawn from a box's output port onto another box, a line links it (a
// join's left or right by where it is let go: its top half, its bottom); a line clicked is selected, and Delete (or its
// ×) takes it off -- the box reading it then reads a copy of what it read (frozen); a tool dropped on a line goes between
// the two boxes.

import { BOX_HEIGHT, BOX_WIDTH, MARGIN, type Flow } from './flow.ts';
import type { FlowBoardState } from './flow-board.ts';
import type { FlowPoint, LineEnd, RunLog } from './flow-board.ts';
import type { InputPort, InputSide } from '../cube-document.ts';
import {
  boxInputs, boxSays, FLOW_TOOLS, PALETTE, readersOf, TOOL_EDITORS, type BoxInput, type BoxSays, type FlowGroup, type FlowTool,
} from '../flow-boxes.ts';
import { UI_LOCALE } from '../../../engine-client/src/locale.ts';
import { gridTitle } from '../page-ids.ts';
import type { MenuItem } from '../ui/menu.ts';
import { MenuView } from '../ui/menu-view.ts';

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

/** The zoom levels the canvas steps through (−, +, Ctrl+wheel), 1 its own size. */
const ZOOMS = [0.5, 0.67, 0.8, 0.9, 1, 1.1, 1.25, 1.5] as const;

/** Each palette group's heading. */
const GROUP_WORDS: Readonly<Record<FlowGroup, string>> = {
  in: 'In', prepare: 'Prepare', join: 'Join', parse: 'Parse', transform: 'Transform', docs: 'Notes',
};

/** Where a box's output is on its right edge, from its top: a Filter's True above its False (Alteryx's T and F), any
 *  other box's in the middle. */
const outY = (filter: boolean, port: InputPort | undefined): number => (!filter ? BOX_HEIGHT / 2 : port === 'false' ? BOX_HEIGHT * 0.7 : BOX_HEIGHT * 0.3);

/** The two outputs' names: a Filter's True and False, a Unique's Unique and Duplicates. */
const PORT_WORDS = { filter: ['True', 'False'], unique: ['Unique', 'Duplicates'] } as const;

/** A side's name, as a port and the settings say it. */
const SIDE_WORDS: Readonly<Record<InputSide, string>> = { input: 'Input', left: 'Left', right: 'Right' };

/** A line's key: the box it goes into, and its side. */
const lineKey = (end: LineEnd): string => `${end.tile}:${end.side}`;

/** Each tool's icon, as strokes in a 16-unit square. */
const ICONS: Readonly<Record<FlowTool, string>> = {
  input: 'M3 4c0-1.1 2.2-2 5-2s5 .9 5 2v8c0 1.1-2.2 2-5 2s-5-.9-5-2zM3 4c0 1.1 2.2 2 5 2s5-.9 5-2M3 8c0 1.1 2.2 2 5 2s5-.9 5-2',
  textInput: 'M2 2.5h12v11H2zM2 6h12M6.5 2.5v11M8.5 9.5h4M8.5 11.5h2.5',
  cleanse: 'M10 2l4 4-6.5 6.5H3.5V8.5zM8 4l4 4M2 14.5h12',
  textToColumns: 'M2 4.5h12M2 8h4M10 8h4M2 11.5h4M10 11.5h4M8 6v8',
  regex: 'M4 2.5v6M1.5 4l5 3M6.5 4l-5 3M10 2.5l-2 11M14 2.5l-2 11M8.5 9.5H14',
  join: 'M6 3.5a4.5 4.5 0 1 0 0 9a4.5 4.5 0 1 0 0-9M10 3.5a4.5 4.5 0 1 0 0 9a4.5 4.5 0 1 0 0-9',
  union: 'M2.5 3h11v4h-11zM2.5 9h11v4h-11z',
  recordId: 'M6 2.5L4.5 13.5M11.5 2.5L10 13.5M2.5 6h11M2 10.5h11',
  unique: 'M3 3.5h4v4H3zM9 3.5h4v4H9zM3 10h4M9 10h4M3 12.5h4M9 12.5h4',
  sample: 'M2.5 3h11M2.5 6.5h11M2.5 10h4M2.5 13h4M10 10.5l1.5 1.5 2.5-3',
  multiRow: 'M2 3.5h8M2 8h8M2 12.5h8M12.5 5.5l2 2.5-2 2.5',
  tile: 'M2.5 2.5h4.5v4.5H2.5zM9 2.5h4.5v4.5H9zM2.5 9h4.5v4.5H2.5zM9 9h4.5v4.5H9z',
  datetime: 'M2.5 3.5h11v10h-11zM2.5 6.5h11M5.5 2v3M10.5 2v3M5 9h1.5M7.5 9H9M10 9h1.5M5 11h1.5M7.5 11H9',
  runningTotal: 'M2 13l4-4.5 3 2.5 5-6.5M10.5 4.5H14V8',
  crosstab: 'M2 2.5h12v11H2zM2 6.5h12M6 2.5v11',
  comment: 'M2.5 2.5h11v8h-6l-3 3v-3h-2zM5 5.5h6M5 8h4',
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
  /** How many rows it gave when its grid ran last, while its chain is as it was. */
  readonly count: HTMLElement;
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
  /** The line being drawn from a box's output (its port), as the pointer has it (the plane's units), and the side it
   *  would go into. */
  #wire: { readonly from: string; readonly port?: InputPort; x: number; y: number; to?: LineEnd } | undefined;
  /** A selected line's ×, at its middle. */
  readonly #unlink: HTMLButtonElement;
  /** The settings' inputs: a box's Input (a join's Left and Right), each a box of the flow or a frozen copy. */
  readonly #inputs: HTMLElement;
  #inputsKey = '';
  /** Each comment drawn, as a note: its card, by its tile's id. */
  readonly #notes = new Map<string, HTMLElement>();
  /** A box's menu (a right-click), made the first time, and what its entry picked does. */
  #menu: MenuView | undefined;
  #menuAct: ((item: MenuItem) => void) | undefined;
  /** The canvas's own tools, at its corner: Run, Tidy Up. */
  readonly #toolbar: HTMLElement;
  readonly #run: HTMLButtonElement;
  readonly #tidy: HTMLButtonElement;
  readonly #undo: HTMLButtonElement;
  readonly #redo: HTMLButtonElement;
  /** Where the pointer is over the canvas, in the window's units: where a paste goes. */
  #pointer: { x: number; y: number } | undefined;
  /** The canvas's zoom (the screen's, never the page's): its boxes drawn at this size, and the level said between − and +. */
  #zoom = 1;
  readonly #zoomLevel: HTMLButtonElement;
  /** The last run's log, at the canvas's corner: drawn for the log it was drawn for, closed until the next run. */
  readonly #logPanel: HTMLElement;
  #logDrawn: RunLog | undefined;
  #logClosed: RunLog | undefined;
  /** The palette's find field, and its tools by name. */
  readonly #find: HTMLInputElement;
  readonly #tools = new Map<FlowTool, HTMLButtonElement>();

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
    // FIND: its tools narrowed to those whose name or words hold what is typed; Enter adds the first after the box shown
    this.#find = doc.createElement('input');
    this.#find.type = 'search';
    this.#find.className = 'dc-flow-find';
    this.#find.placeholder = 'Find';
    this.#find.setAttribute('aria-label', 'Find a tool');
    this.#find.addEventListener('input', () => this.#narrow());
    this.#find.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && this.#find.value !== '') {
        e.stopPropagation();
        this.#find.value = '';
        this.#narrow();
        return;
      }
      if (e.key !== 'Enter') return;
      e.preventDefault();
      const first = PALETTE.find((t) => !this.#tools.get(t)!.hidden);
      if (first !== undefined) this.#board.options.onTool?.(first, this.#board.shown, TOOL_EDITORS[first]);
    });
    this.#palette.append(this.#find);
    // its tools by Alteryx's groups, each under its heading
    let group: string | undefined;
    for (const tool of PALETTE) {
      const g = FLOW_TOOLS[tool].group;
      if (g !== group) {
        group = g;
        const heading = el('div', 'dc-flow-group');
        heading.textContent = GROUP_WORDS[g];
        heading.dataset['group'] = g;
        heading.setAttribute('aria-hidden', 'true');
        this.#palette.append(heading);
      }
      const button = this.#tool(tool);
      this.#tools.set(tool, button);
      this.#palette.append(button);
    }
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
    // the canvas on its stage, its own tools at its corner, over it as it scrolls
    const stage = el('div', 'dc-flow-stage');
    this.#toolbar = el('div', 'dc-flow-toolbar');
    const tool = (cls: string, text: string, title: string, act: () => void): HTMLButtonElement => {
      const b = doc.createElement('button');
      b.type = 'button';
      b.className = `dc-flow-act ${cls}`;
      b.textContent = text;
      b.title = title;
      b.addEventListener('click', act);
      return b;
    };
    this.#run = tool('dc-flow-run', '▶ Run', 'Run every box, left to right, and count the rows each gives', () => this.#board.options.onRun?.());
    this.#tidy = tool('dc-flow-tidy', 'Tidy Up', 'Place every box a column after the boxes it reads, level with them', () => this.#board.options.onTidy?.());
    // the page's own Undo and Redo, each saying what it would undo or redo
    this.#undo = tool('dc-flow-undo', '↶', 'Undo (Ctrl+Z)', () => this.#board.options.onUndo?.(false));
    this.#redo = tool('dc-flow-redo', '↷', 'Redo (Ctrl+Shift+Z)', () => this.#board.options.onUndo?.(true));
    this.#undo.setAttribute('aria-label', 'Undo');
    this.#redo.setAttribute('aria-label', 'Redo');
    // ZOOM: − and + a level each, the level itself back to its own size, Fit all of it in view; Ctrl+wheel too
    const zoomOut = tool('dc-flow-zoom', '−', 'Zoom out (Ctrl+wheel)', () => this.#zoomBy(-1));
    this.#zoomLevel = tool('dc-flow-zoom dc-flow-zoom-level', '100%', 'Its own size', () => this.#setZoom(1));
    const zoomIn = tool('dc-flow-zoom', '+', 'Zoom in (Ctrl+wheel)', () => this.#zoomBy(1));
    const fit = tool('dc-flow-zoom', 'Fit', 'Every box in view', () => this.#fit());
    zoomOut.setAttribute('aria-label', 'Zoom out');
    zoomIn.setAttribute('aria-label', 'Zoom in');
    this.#toolbar.append(this.#run, this.#tidy, this.#undo, this.#redo, zoomOut, this.#zoomLevel, zoomIn, fit);
    // what Undo and Redo would do read again as the pointer comes to them
    this.#toolbar.addEventListener('pointerenter', () => this.#paintHistory());
    this.#canvas.addEventListener('wheel', (e) => {
      if (!(e.ctrlKey || e.metaKey)) return;
      e.preventDefault();
      this.#zoomBy(e.deltaY < 0 ? 1 : -1, { x: e.clientX, y: e.clientY });
    }, { passive: false });
    this.#logPanel = el('section', 'dc-flow-log');
    this.#logPanel.setAttribute('aria-label', 'Run log');
    this.#logPanel.hidden = true;
    stage.append(this.#canvas, this.#toolbar, this.#logPanel);
    const top = el('div', 'dc-flow-top');
    top.append(stage, this.#side, this.#settings);
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
    // where the pointer is over the canvas: where Ctrl+V pastes
    this.#canvas.addEventListener('pointermove', (e) => { this.#pointer = { x: e.clientX, y: e.clientY }; });
    this.#canvas.addEventListener('pointerleave', () => { this.#pointer = undefined; });
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
    // the boxes: each one's element kept, drawn again where it changed -- and its comments, each a note (M7)
    const ids = new Set(layout.boxes.filter((b) => board.noteOf(b.tile) === undefined).map((b) => b.tile));
    const notes = new Set(layout.boxes.filter((b) => board.noteOf(b.tile) !== undefined).map((b) => b.tile));
    for (const [id, drawn] of [...this.#boxes]) {
      if (ids.has(id)) continue;
      drawn.root.remove();
      this.#boxes.delete(id);
    }
    for (const [id, note] of [...this.#notes]) {
      if (notes.has(id)) continue;
      note.remove();
      this.#notes.delete(id);
    }
    for (const box of layout.boxes) {
      const tile = board.noteOf(box.tile);
      if (tile !== undefined) {
        const note = this.#notes.get(box.tile) ?? this.#makeNote(box.tile);
        // its words' element, its owner's, in its body: placed once
        const body = note.querySelector<HTMLElement>('.dc-flow-note-body')!;
        if (body.firstChild !== tile.element) body.replaceChildren(tile.element);
        note.style.transform = `translate(${box.x}px, ${box.y}px)`;
        note.querySelector<HTMLElement>('.dc-flow-note-x')!.hidden = !editing;
        continue;
      }
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
    // Run on a locked page too: it changes nothing of the page's
    const running = board.running;
    const history = this.#paintHistory();
    // an empty canvas has no tools but Undo and Redo, while there is something to undo or redo
    this.#toolbar.hidden = layout.boxes.length === 0 && history.undo === undefined && history.redo === undefined;
    this.#tidy.hidden = !editing || layout.boxes.length < 2;
    this.#run.disabled = running !== undefined;
    this.#run.textContent = running === undefined ? '▶ Run' : `Running ${Math.min(running.done + 1, running.of)} of ${running.of}…`;
    this.#paintLog();
    this.#paintResults();
    this.#paintSettings();
  }

  /** Undo and Redo as the page has them now: each on while it has a step, its title the step's name; neither on a
   *  locked page. */
  #paintHistory(): { readonly undo?: string | undefined; readonly redo?: string | undefined } {
    const editing = this.#board.editing;
    const history = editing ? this.#board.options.history?.() ?? {} : {};
    for (const [button, label, word, keys] of [
      [this.#undo, history.undo, 'Undo', 'Ctrl+Z'], [this.#redo, history.redo, 'Redo', 'Ctrl+Shift+Z'],
    ] as const) {
      button.hidden = !editing;
      button.disabled = label === undefined;
      button.title = label === undefined ? `Nothing to ${word.toLowerCase()}` : `${word} ${label} (${keys})`;
    }
    return history;
  }

  /** A box scrolled into view (the canvas scrolls in its zoomed size). */
  reveal(id: string): void {
    const box = this.#placed(this.#board.layout).boxes.find((b) => b.tile === id);
    if (!box) return;
    const c = this.#canvas;
    const z = this.#zoom;
    const [x, y, w, h, m] = [box.x * z, box.y * z, BOX_WIDTH * z, BOX_HEIGHT * z, MARGIN * z];
    if (x < c.scrollLeft) c.scrollLeft = Math.max(0, x - m);
    else if (x + w > c.scrollLeft + c.clientWidth) c.scrollLeft = x + w + m - c.clientWidth;
    if (y < c.scrollTop) c.scrollTop = Math.max(0, y - m);
    else if (y + h > c.scrollTop + c.clientHeight) c.scrollTop = y + h + m - c.clientHeight;
  }

  /** The zoom a level up or down (`by`), kept at the point `at` of the window when given (a wheel's pointer). */
  #zoomBy(by: 1 | -1, at?: { readonly x: number; readonly y: number }): void {
    const i = ZOOMS.findIndex((z) => z >= this.#zoom - 1e-9);
    const next = ZOOMS[Math.max(0, Math.min(ZOOMS.length - 1, (i < 0 ? ZOOMS.length - 1 : i) + by))]!;
    this.#setZoom(next, at);
  }

  /** The canvas drawn at `zoom`, the point `at` of the window (else its top left) where it was. */
  #setZoom(zoom: number, at?: { readonly x: number; readonly y: number }): void {
    const was = this.#zoom;
    if (Math.abs(zoom - was) < 1e-9) return;
    const c = this.#canvas;
    const r = c.getBoundingClientRect();
    const px = at === undefined ? 0 : at.x - r.left;
    const py = at === undefined ? 0 : at.y - r.top;
    const planeX = (c.scrollLeft + px) / was;
    const planeY = (c.scrollTop + py) / was;
    this.#zoom = zoom;
    this.#plane.style.setProperty('zoom', String(zoom));
    this.#zoomLevel.textContent = `${Math.round(zoom * 100)}%`;
    c.scrollLeft = Math.max(0, planeX * zoom - px);
    c.scrollTop = Math.max(0, planeY * zoom - py);
  }

  /** Every box in view: the largest zoom, its own size at most, that shows them all. */
  #fit(): void {
    const boxes = this.#board.layout.boxes;
    if (boxes.length === 0) return;
    const w = Math.max(...boxes.map((b) => b.x + BOX_WIDTH)) + MARGIN;
    const h = Math.max(...boxes.map((b) => b.y + BOX_HEIGHT)) + MARGIN;
    const c = this.#canvas;
    const fits = Math.min(1.5, c.clientWidth / w, c.clientHeight / h);
    this.#setZoom(Math.max(0.25, Math.min(1, fits)));
    c.scrollLeft = 0;
    c.scrollTop = 0;
  }

  dispose(): void {
    this.#gesture?.(false);
    this.#menu?.close();
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
      else if (line !== undefined) {
        const from = this.#fromOf(line);
        this.#board.options.onTool?.(tool, from?.grid, editor, line, from?.port);
      }
      else if (ev && this.#onCanvas(ev.clientX, ev.clientY)) this.#board.options.onTool?.(tool, undefined, editor, undefined, undefined, this.#pointAt(ev));
    });
  }

  /** The palette narrowed to the tools whose name or words hold what the find field holds (a heading kept with its own). */
  #narrow(): void {
    const q = this.#find.value.trim().toLowerCase();
    for (const [tool, button] of this.#tools) {
      const t = FLOW_TOOLS[tool];
      button.hidden = q !== '' && !`${t.name} ${t.does}`.toLowerCase().includes(q);
    }
    for (const heading of this.#palette.querySelectorAll<HTMLElement>('.dc-flow-group')) {
      heading.hidden = PALETTE.every((t) => FLOW_TOOLS[t].group !== heading.dataset['group'] || this.#tools.get(t)!.hidden);
    }
  }

  /**
   * THE RUN'S LOG (M6), at the canvas's bottom corner: when it ran and how long it took, then each box -- its name, its
   * rows, its time, or why it was refused, red -- each a button that shows it. Closed by its ×, until the next run.
   */
  #paintLog(): void {
    const log = this.#board.log;
    const shown = log !== undefined && log !== this.#logClosed && this.#board.running === undefined;
    this.#logPanel.hidden = !shown;
    if (!shown || log === this.#logDrawn) return;
    this.#logDrawn = log;
    const d = this.#doc;
    const el = (tag: string, cls: string, text?: string): HTMLElement => {
      const e = d.createElement(tag);
      e.className = cls;
      if (text !== undefined) e.textContent = text;
      return e;
    };
    const head = el('div', 'dc-flow-log-head');
    const refused = log.boxes.filter((b) => b.refused !== undefined).length;
    const seconds = (ms: number): string => (ms < 1000 ? `${ms} ms` : `${(ms / 1000).toLocaleString(UI_LOCALE, { maximumFractionDigits: 1 })} s`);
    head.append(el('span', 'dc-flow-log-title', `Ran ${log.boxes.length} ${log.boxes.length === 1 ? 'box' : 'boxes'} in ${seconds(log.ms)}`
      + (refused > 0 ? `, ${refused} refused` : '')));
    const close = el('button', 'dc-flow-log-close', '×') as HTMLButtonElement;
    close.type = 'button';
    close.setAttribute('aria-label', 'Close the run log');
    close.addEventListener('click', () => {
      this.#logClosed = log;
      this.#paintLog();
    });
    head.append(close);
    const list = el('ol', 'dc-flow-log-list');
    for (const b of log.boxes) {
      const item = el('li', `dc-flow-log-item${b.refused !== undefined ? ' dc-flow-log-refused' : ''}`);
      const go = el('button', 'dc-flow-log-box') as HTMLButtonElement;
      go.type = 'button';
      const name = this.#board.layout.boxes.some((x) => x.tile === b.id) ? this.#nameOf(b.id, this.#said(b.id)?.tool ?? 'browse') : b.id;
      go.append(el('span', 'dc-flow-log-name', name),
        el('span', 'dc-flow-log-rows', b.refused ?? (b.rows === undefined ? '' : `${b.rows.toLocaleString(UI_LOCALE)} ${b.rows === 1 ? 'row' : 'rows'}`)),
        el('span', 'dc-flow-log-ms', seconds(b.ms)));
      go.title = b.refused ?? `Show ${name}`;
      go.addEventListener('click', () => {
        if (this.#board.layout.boxes.some((x) => x.tile === b.id)) this.#select(b.id);
      });
      item.append(go);
      list.append(item);
    }
    this.#logPanel.replaceChildren(head, list);
  }

  /** A box's place to be made at a point of the window: its middle there, in the canvas's own units. */
  #pointAt(ev: { readonly clientX: number; readonly clientY: number }): FlowPoint {
    const r = this.#plane.getBoundingClientRect();
    const x = (ev.clientX - r.left) / this.#zoom - BOX_WIDTH / 2;
    const y = (ev.clientY - r.top) / this.#zoom - BOX_HEIGHT / 2;
    return { x: Math.max(0, Math.round(x)), y: Math.max(0, Math.round(y)) };
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
    // its output: pressed and dragged onto another box, a line linking them -- a Filter's True, and its False below it
    const out = port('dc-flow-port-out', 'T');
    out.title = 'Drag onto another box to link it here';
    out.addEventListener('pointerdown', (e) => this.#startWire(id, out, e));
    const outFalse = port('dc-flow-port-out dc-flow-port-false', 'F');
    outFalse.title = 'Its False: the rows its condition leaves out. Drag onto another box to link it here';
    outFalse.addEventListener('pointerdown', (e) => this.#startWire(id, outFalse, e, 'false'));
    const glyph = doc.createElement('span');
    glyph.className = 'dc-flow-icon';
    const text = doc.createElement('div');
    text.className = 'dc-flow-text';
    const name = doc.createElement('div');
    name.className = 'dc-flow-name';
    const says = doc.createElement('div');
    says.className = 'dc-flow-says';
    text.append(name, says);
    const count = doc.createElement('span');
    count.className = 'dc-flow-count';
    const remove = doc.createElement('button');
    remove.type = 'button';
    remove.className = 'dc-flow-x';
    remove.textContent = '×';
    remove.addEventListener('pointerdown', (e) => e.stopPropagation());
    remove.addEventListener('click', (e) => {
      e.stopPropagation();
      this.#board.options.onRemove?.(id);
    });
    root.append(into, left, right, glyph, text, count, remove, out, outFalse);
    const drawn: Drawn = { root, icon: glyph, name, says, count, remove, key: '', renaming: false };
    root.addEventListener('pointerdown', (e) => this.#startBox(id, drawn, e));
    root.addEventListener('keydown', (e) => this.#onBoxKey(id, drawn, e));
    // right-clicked: what can be done to it, each the gesture it is besides (a click, F2, Ctrl+D, Delete)
    root.addEventListener('contextmenu', (e) => {
      if (drawn.renaming) return;
      e.preventDefault();
      this.#boxMenu(id, drawn, e.clientX, e.clientY);
    });
    name.addEventListener('dblclick', (e) => {
      e.stopPropagation();
      this.#rename(id, drawn);
    });
    this.#boxes.set(id, drawn);
    this.#plane.append(root);
    return drawn;
  }

  /**
   * A COMMENT AS A NOTE (M7): a card on the canvas, its words in its body (its text tile's own element: a double-click
   * edits them in place, as on a board), its head what it is moved by, its × what takes it off; Delete on it too.
   */
  #makeNote(id: string): HTMLElement {
    const doc = this.#doc;
    const root = doc.createElement('div');
    root.className = 'dc-flow-note';
    root.dataset['tile'] = id;
    root.tabIndex = 0;
    root.setAttribute('role', 'note');
    const head = doc.createElement('div');
    head.className = 'dc-flow-note-head';
    head.title = 'Drag to move the comment; double-click its words to edit them';
    const remove = doc.createElement('button');
    remove.type = 'button';
    remove.className = 'dc-flow-note-x';
    remove.textContent = '×';
    remove.setAttribute('aria-label', 'Remove the comment');
    remove.addEventListener('pointerdown', (e) => e.stopPropagation());
    remove.addEventListener('click', () => this.#board.options.onRemove?.(id));
    head.append(remove);
    const body = doc.createElement('div');
    body.className = 'dc-flow-note-body';
    root.append(head, body);
    head.addEventListener('pointerdown', (e) => this.#startNote(id, root, e));
    root.addEventListener('keydown', (e) => {
      if (e.target !== root || !this.#board.editing) return;
      if (this.#copied(id, e)) return;
      if (e.key === 'Delete' || e.key === 'Backspace') {
        e.preventDefault();
        this.#board.options.onRemove?.(id);
      }
    });
    this.#notes.set(id, root);
    this.#plane.append(root);
    return root;
  }

  /** A note dragged by its head: it follows the pointer, and its place is told once, let go (onMove). */
  #startNote(id: string, root: HTMLElement, e: PointerEvent): void {
    if (e.button !== 0 || !this.#board.editing) return;
    const at = this.#board.layout.boxes.find((b) => b.tile === id);
    if (at === undefined) return;
    e.preventDefault();
    const start = { x: e.clientX, y: e.clientY };
    let to = { x: at.x, y: at.y };
    this.#own(root, e, (ev) => {
      to = { x: Math.max(0, at.x + (ev.clientX - start.x) / this.#zoom), y: Math.max(0, at.y + (ev.clientY - start.y) / this.#zoom) };
      root.classList.add('dc-flow-dragging');
      root.style.transform = `translate(${to.x}px, ${to.y}px)`;
    }, (apply) => {
      root.classList.remove('dc-flow-dragging');
      if (apply && (to.x !== at.x || to.y !== at.y)) this.#board.options.onMove?.(id, Math.round(to.x), Math.round(to.y));
      this.paint();
    });
  }

  /** A BOX'S MENU (a right-click): shown, its settings, renamed, duplicated, an input frozen, taken off -- each the same
   *  as its gesture elsewhere on the canvas; a locked page's, shown alone. */
  #boxMenu(id: string, drawn: Drawn, x: number, y: number): void {
    const o = this.#board.options;
    const said = this.#said(id);
    const editor = said ? TOOL_EDITORS[said.tool] : undefined;
    const editing = this.#board.editing;
    const live = this.#inputsOf(id).filter((i) => 'grid' in i);
    const items: MenuItem[] = [
      { id: 'tile.box.show', label: 'Show its rows' },
      ...(editing ? [
        { id: 'tile.box.settings', label: 'Open its settings', ...(editor === undefined ? { disabled: true } : {}) } as const,
        { id: 'tile.box.rename', label: 'Rename (F2)' } as const,
        { id: 'tile.box.duplicate', label: 'Duplicate (Ctrl+D)' } as const,
        ...live.map((i) => ({ id: `tile.box.freeze.${i.side}` as const, label: `Freeze its ${SIDE_WORDS[i.side]}: a copy of its rows now` })),
        { id: 'tile.box.remove', label: 'Remove (Delete)' } as const,
      ] : []),
    ];
    this.#menu ??= new MenuView(this.#doc, { onSelect: (item) => this.#menuAct?.(item) });
    this.#menuAct = (item) => {
      const act = item.id ?? '';
      if (act === 'tile.box.show') this.#select(id);
      else if (act === 'tile.box.settings' && editor !== undefined) o.onSelect?.(id, editor);
      else if (act === 'tile.box.rename') this.#rename(id, drawn);
      else if (act === 'tile.box.duplicate') o.onDuplicate?.(id);
      else if (act === 'tile.box.remove') o.onRemove?.(id);
      else if (act.startsWith('tile.box.freeze.')) o.onFreeze?.(id, act.slice('tile.box.freeze.'.length) as InputSide);
    };
    this.#menu.show([{ label: '', items }], x, y);
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

  /** A box shown, and its settings with it: the window of its grid's that sets what it does (TOOL_EDITORS) -- but the
   *  Join window, a window over the page, opened only when asked (its settings' button). */
  #select(id: string): void {
    this.#clicked = id;
    const said = this.#said(id);
    const editor = said ? TOOL_EDITORS[said.tool] : undefined;
    this.#board.options.onSelect?.(id, editor?.window === 'join' ? undefined : editor);
  }

  /** What a box does, as it is drawn: its document's (boxSays) -- or, added as a tool and doing nothing yet, that tool,
   *  not set yet. */
  #said(id: string): BoxSays | undefined {
    const doc = this.#board.options.documentOf?.(id);
    if (doc === undefined) return undefined;
    const said = boxSays(doc);
    const added = this.#board.addedAs(id);
    return said.tool === 'browse' && added !== undefined && added !== 'browse' ? { tool: added, also: [], says: 'not set yet' } : said;
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
    const counted = this.#board.count(id);
    const key = JSON.stringify([tool, name, says, shown, this.#board.editing, refused, columns, frozen, counted]);
    if (key === drawn.key) return;
    drawn.key = key;
    drawn.root.dataset['tool'] = tool;
    drawn.root.dataset['group'] = FLOW_TOOLS[tool].group;
    // its two outputs, where it has two: a Filter's T and F, a Unique's U and D
    const [outTrue, outFalse] = drawn.root.querySelectorAll<HTMLElement>(':scope > .dc-flow-port-out');
    if (outTrue !== undefined && outFalse !== undefined) {
      const unique = tool === 'unique';
      outTrue.textContent = unique ? 'U' : 'T';
      outFalse.textContent = unique ? 'D' : 'F';
      outFalse.title = unique ? 'Its Duplicates: every row after its key\'s first. Drag onto another box to link it here'
        : 'Its False: the rows its condition leaves out. Drag onto another box to link it here';
    }
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
    // its rows when its grid ran last (Run, or shown), while its chain is as it was then
    drawn.count.hidden = counted === undefined;
    drawn.count.textContent = counted === undefined ? '' : counted.toLocaleString(UI_LOCALE);
    drawn.count.title = counted === undefined ? '' : `${counted.toLocaleString(UI_LOCALE)} ${counted === 1 ? 'row' : 'rows'} when it ran last`;
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
      // the pointer's way across the window, in the canvas's own units at its zoom
      const dx = (ev.clientX - start.x) / this.#zoom;
      const dy = (ev.clientY - start.y) / this.#zoom;
      if (!editing || (!moved && Math.hypot(dx, dy) * this.#zoom < DRAG_PX)) return;
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
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'd') {
      e.preventDefault();
      o.onDuplicate?.(id);
      return;
    }
    if (this.#copied(id, e)) return;
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
    if (!(e.ctrlKey || e.metaKey)) return;
    const target = e.target as HTMLElement | null;
    // a field (a box being renamed, a note's words) keeps its own undo and its own clipboard
    if (target?.tagName === 'INPUT' || target?.tagName === 'TEXTAREA') return;
    const key = e.key.toLowerCase();
    if (key === 'v' && this.#board.editing && this.#board.options.onPaste) {
      e.preventDefault();
      this.#board.options.onPaste(this.#pointer !== undefined ? this.#pointAt({ clientX: this.#pointer.x, clientY: this.#pointer.y }) : undefined);
      return;
    }
    if (key !== 'z') return;
    e.preventDefault();
    this.#board.options.onUndo?.(e.shiftKey);
  }

  /** Ctrl+C on a box or a note, or Ctrl+X (and it taken off): told, and true. */
  #copied(id: string, e: KeyboardEvent): boolean {
    const key = e.key.toLowerCase();
    if (!(e.ctrlKey || e.metaKey) || (key !== 'c' && key !== 'x')) return false;
    if (key === 'x' && !this.#board.editing) return false;
    e.preventDefault();
    // its own: the canvas's Ctrl+V is the canvas's
    e.stopPropagation();
    this.#board.options.onCopy?.(id, key === 'x');
    return true;
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

  /** The box a line comes out of, and its port. */
  #fromOf(line: LineEnd): { readonly grid: string; readonly port?: InputPort } | undefined {
    const i = this.#inputsOf(line.tile).find((x) => x.side === line.side);
    return i !== undefined && 'grid' in i ? { grid: i.grid, ...(i.port !== undefined ? { port: i.port } : {}) } : undefined;
  }

  /** Whether a box is drawn with two outputs: a Filter's True and False, a Unique's U and D (its filter's, turned round). */
  #isFilter(id: string): boolean {
    const tool = this.#said(id)?.tool;
    return tool === 'filter' || tool === 'unique';
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
        const y1 = a.y + outY(this.#isFilter(input.grid), input.port);
        const x2 = box.x;
        const y2 = box.y + portY(input.side);
        lines.push({ end: { tile: box.tile, side: input.side }, d: curve(x1, y1, x2, y2), mid: { x: (x1 + x2) / 2, y: (y1 + y2) / 2 } });
      }
    }
    const w = this.#wire;
    const wire = w === undefined ? undefined : (() => {
      const a = at.get(w.from);
      return a === undefined ? undefined : curve(a.x + BOX_WIDTH, a.y + outY(this.#isFilter(w.from), w.port), w.x, w.y);
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
  #endAt(from: string, x: number, y: number, port?: InputPort): LineEnd | undefined {
    const tile = this.#boxAt(x, y);
    if (tile === undefined || tile === from) return undefined;
    const inputs = this.#inputsOf(tile);
    if (inputs.length === 0) return undefined;
    const r = this.#boxes.get(tile)!.root.getBoundingClientRect();
    const side: InputSide = inputs.length === 1 ? inputs[0]!.side : (y - r.top < r.height / 2 ? 'left' : 'right');
    const now = inputs.find((i) => i.side === side);
    if (now !== undefined && 'grid' in now && now.grid === from && now.port === port) return undefined;
    return { tile, side };
  }

  /** A line drawn from box `from`'s output (`which`: its False): it follows the pointer, and let go on a box that reads
   *  another's rows, links it (onConnect); let go on the canvas, a box of its rows there (a Browse). */
  #startWire(from: string, port: HTMLElement, e: PointerEvent, which?: InputPort): void {
    if (e.button !== 0 || !this.#board.editing) return;
    e.preventDefault();
    e.stopPropagation();
    this.#selectLine(undefined);
    const plane = (ev: PointerEvent): { x: number; y: number } => {
      const r = this.#plane.getBoundingClientRect();
      return { x: (ev.clientX - r.left) / this.#zoom, y: (ev.clientY - r.top) / this.#zoom };
    };
    this.#wire = { from, ...(which !== undefined ? { port: which } : {}), ...plane(e) };
    this.#host.classList.add('dc-flow-wiring');
    this.#own(port, e, (ev) => {
      const w = this.#wire;
      if (w === undefined) return;
      const to = this.#endAt(from, ev.clientX, ev.clientY, which);
      this.#mark(to?.tile);
      // drawn to the port it would go into, else to the pointer
      const box = to === undefined ? undefined : this.#placed(this.#board.layout).boxes.find((b) => b.tile === to.tile);
      Object.assign(w, box !== undefined && to !== undefined ? { x: box.x, y: box.y + portY(to.side), to } : { ...plane(ev), to: undefined });
      this.#paintLines(this.#placed(this.#board.layout));
    }, (apply, ev) => {
      const to = this.#wire?.to;
      this.#wire = undefined;
      this.#host.classList.remove('dc-flow-wiring');
      this.#mark(undefined);
      this.#paintLines(this.#placed(this.#board.layout));
      if (!apply) return;
      if (to !== undefined) this.#board.options.onConnect?.(to.tile, to.side, from, which);
      // let go on the canvas, away from any box: a box of the rows it came out with
      else if (ev !== undefined && this.#boxAt(ev.clientX, ev.clientY) === undefined && this.#onCanvas(ev.clientX, ev.clientY)) {
        this.#board.options.onTool?.('browse', from, undefined, undefined, which, this.#pointAt(ev));
      }
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
    // each box of the flow, and a Filter's False besides
    const offered = boxes.filter((b) => b !== id && !readers.has(b)).flatMap((b) => {
      const name = this.#nameOf(b, this.#said(b)?.tool ?? 'browse');
      if (!this.#isFilter(b)) return [{ id: b, name }];
      const [yes, no] = PORT_WORDS[this.#said(b)?.tool === 'unique' ? 'unique' : 'filter'];
      return [{ id: b, name: `${name} (${yes})` }, { id: b, name: `${name} (${no})`, port: 'false' as const }];
    });
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
      for (const o of offered) {
        const port = 'port' in o ? o.port : undefined;
        pick.append(option(o.name, `grid:${o.id}${port !== undefined ? `:${port}` : ''}`, 'grid' in input && input.grid === o.id && input.port === port));
      }
      // a copy: what it reads when frozen -- or, live, Freeze: a copy of what it reads now
      pick.append(option('frozen' in input ? `A frozen copy of ${input.frozen}` : 'Freeze: a copy of its rows now', 'frozen', 'frozen' in input));
      pick.addEventListener('change', () => {
        const v = pick.value;
        if (v === 'frozen') this.#board.options.onFreeze?.(id!, input.side);
        else {
          const [grid, port] = v.slice('grid:'.length).split(':');
          this.#board.options.onConnect?.(id!, input.side, grid!, port === 'false' ? 'false' : undefined);
        }
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
