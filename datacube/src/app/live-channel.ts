// THE TAB'S SIDE OF THE PAGE KEEPER (docs/DATACUBE_PAGE_KEEPER_2026_10_20.md): a page another editor serves (Python's
// `ll.Page`), open here. One copy of the page is kept by the host; this screen writes it against the version it is at,
// with the edits it made there -- the host keeps what it wrote, or makes its edits again on top of what moved the page
// meanwhile (DataCube's pageRebase), and the screen then opens the page as the host made it -- and follows the other
// editor's moves. Writes and follows run one at a time, in order: a move told while a write is on its way is followed
// once the write has said its version, never taken for the other editor's.

import { pageToJson, type PageDocument } from '../page-document.ts';
import type { LiveCommand, LivePage } from './services.ts';

/** How long a change waits to settle before it is written (a drag, a typed name: one write, ms). */
export const WRITE_AFTER = 500;

/** The screen a channel writes from and opens into. */
export interface LiveScreen {
  /** The page as the screen holds it now -- a page of no grid too -- or undefined: nothing it can say yet. */
  page(): PageDocument | undefined;
  /** The page as the host has it, opened again whole: a write the host made again on top, which the screen then opens
   *  as the host made it. */
  reopen(): Promise<void>;
}

export class LiveChannel {
  /** The version of the page on screen, which its writes name: undefined while none is (opening it, following it). */
  shown: number | undefined;
  /** The edits made on screen since it was last written, as applied there -- or null: a change no command made (a
   *  write of the page whole, which the host takes only at the version it was made against). */
  #edits: LiveCommand[] | null = [];
  /** How many edits were told since the screen's last change was seen: a change seen with none told is no command's. */
  #told = 0;
  /** The page's text as it was last seen. */
  #seen: string | undefined;
  /** How deep in following the other editor: what the screen does meanwhile is that editor's, not its own. */
  #following = 0;
  #queue: Promise<void> = Promise.resolve();
  #timer: ReturnType<typeof setTimeout> | undefined;
  #stopped = false;
  readonly #editor: LivePage;
  readonly #screen: LiveScreen;
  readonly #after: number;

  constructor(editor: LivePage, screen: LiveScreen, after = WRITE_AFTER) {
    this.#editor = editor;
    this.#screen = screen;
    this.#after = after;
  }

  /** An edit made on screen (a grid's command, a page's), as applied there: written with the page. */
  edit(command: LiveCommand): void {
    if (this.#following > 0) return;
    this.#edits?.push(command);
    this.#told += 1;
  }

  /** The screen changed: seen (a change no edit was told for makes the next write the page whole), and written once
   *  it settles. */
  changed(): void {
    if (this.#following === 0) this.#see();
    if (this.shown === undefined || this.#stopped) return;
    clearTimeout(this.#timer);
    this.#timer = setTimeout(() => { void this.enqueue(() => this.#write()); }, this.#after);
  }

  /** The other editor followed by `work`: nothing the screen does meanwhile is its own; what it shows after is seen. */
  async following<T>(work: () => Promise<T>): Promise<T> {
    this.#following += 1;
    try {
      return await work();
    } finally {
      this.#following -= 1;
      if (this.#following === 0) this.#seen = this.#text();
    }
  }

  /** The page opened whole at `version` (as it starts, or opened again): nothing of the screen's own pending. */
  opened(version: number): void {
    this.shown = version;
    this.#edits = [];
    this.#told = 0;
    this.#seen = this.#text();
  }

  /** Work in the channel's one queue, after every write and follow before it; its failure is its caller's. */
  enqueue(work: () => Promise<void>): Promise<void> {
    const run = this.#queue.then(work);
    this.#queue = run.catch(() => undefined);
    return run;
  }

  /** Nothing more written (the other editor gone, the app taken down). */
  stop(): void {
    this.#stopped = true;
    clearTimeout(this.#timer);
  }

  /** Every write and follow queued so far, done. */
  idle(): Promise<void> {
    return this.#queue;
  }

  #text(): string | undefined {
    const page = this.#screen.page();
    return page === undefined ? undefined : pageToJson(page);
  }

  #see(): void {
    const text = this.#text();
    if (text === this.#seen) return;
    if (this.#told === 0 && this.#seen !== undefined) this.#edits = null;
    this.#seen = text;
    this.#told = 0;
  }

  async #write(): Promise<void> {
    const at = this.shown;
    if (at === undefined || this.#stopped) return;
    const edits = this.#edits;
    // nothing made on screen since it was last written or opened: nothing to write (a write of no edits against a
    // version the page moved on from would only have the page opened again)
    if (edits !== null && edits.length === 0) return;
    const page = this.#screen.page();
    if (page === undefined) return;
    this.#edits = [];
    this.#told = 0;
    this.#seen = pageToJson(page);
    let said: { readonly version: number; readonly combined: boolean };
    try {
      said = await this.#editor.report(at, page, edits);
    } catch {
      // refused (the page moved on and the edits no longer apply there; a change no command made, written against a
      // version moved on from) or the editor gone: what is on screen is written whole next, once the page is followed
      this.#edits = null;
      return;
    }
    if (this.shown !== at || this.#stopped) return;
    if (said.combined) {
      // made again on top of what moved the page meanwhile: the page as the host made it
      this.shown = undefined;
      await this.#screen.reopen();
      return;
    }
    this.shown = said.version;
  }
}
