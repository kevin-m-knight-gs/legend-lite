// THE TAB'S SIDE OF THE PAGE KEEPER (docs/DATACUBE_PAGE_KEEPER_2026_10_20.md; docs/DATACUBE_LIVE_EDITING_2026_10_24.md
// §2): a page another editor serves (Python's `ll.Page`), open here. One copy of the page is kept by the host; this
// screen writes it against the version it is at, with the edits it made there, each a command
// (docs/DATACUBE_ONE_API_PLAN_2026_10_21.md S4) -- an undo or a redo the commands between (W5) -- and the host applies
// them by DataCube's one rule to the page as it has it. The screen holds three things: CONFIRMED, the page as the
// keeper has it at the version shown; SENT, the edits of the write on its way; PENDING, the edits made since. A write
// answered at its version moves `shown` and nothing on screen. A write the keeper made another page of (it had moved
// on meanwhile, or an id was given out afresh) has the screen CONVERGE without a read and without a reopen (W7): back
// at confirmed, the other editors' steps since (`since`) applied as theirs, its own as the keeper applied them
// (`edits`, renamed) as its own, then its pending edits made again, renamed, so they are told and written next. A
// refused write converges the same way over what moved the page since (W8), the refusal said. The keeper's hash is
// checked at every answer (W17): a mismatch is the one message that must never show, and opens the page again. A follow
// writes what is pending first (`flush`) and waits for the screen to be quiet (F90). Writes and follows run one at a
// time, in order.

import type { PageDocument } from '../page-document.ts';
import { pageHash } from '../page-hash.ts';
import { renamedCommand } from '../page-rebase.ts';
import type { LiveCommand, LivePage, LiveWrite } from './services.ts';

/** How long a change waits to settle before it is written (a drag, a typed name: one write, ms). */
export const WRITE_AFTER = 500;
/** How long a write waits for a grid's change in flight before looking again (ms). */
const QUIET_AFTER = 25;
/** How long a flush waits for the screen to be quiet at most (ms): a query that never answers does not hold a follow
 *  for ever. */
const QUIET_AT_MOST = 10_000;

/** The message that must never show (W17): the two editors held two pages. */
export const HASH_MISMATCH = 'the page here differed from its keeper\'s: opened again as its keeper has it';

/** What the screen converges on (§2): the page as the keeper had it at the version shown, with the ids it held; the
 *  other editors' steps since; the screen's own sent edits as the keeper applied them; each id given out afresh. */
export interface Converge {
  readonly confirmed: PageDocument;
  readonly held: readonly string[];
  readonly since: readonly LiveCommand[];
  readonly edits: readonly LiveCommand[];
  readonly renamed: Readonly<Record<string, string>>;
}

/** The page converged: the page on screen then (before any pending edit is made again), and the grids whose state the
 *  keeper's steps changed (reverted and moved: their pending edits are made again; the others' are told again only). */
export interface Converged {
  readonly page: PageDocument;
  readonly touched: ReadonlySet<string>;
}

/** The screen a channel writes from and converges. */
export interface LiveScreen {
  /** The page as the screen holds it now -- a page of no grid too -- or undefined: nothing it can say yet. */
  page(): PageDocument | undefined;
  /** A grid's change is in flight (its query not answered yet): a write waits for it, so a gesture's commands are written
   *  together once they have all landed (F97), and a follow meets no gesture still running (F90). */
  busy(): boolean;
  /** The screen converged on its keeper's page (§2, steps 1-4), inside `following`: throws when it cannot (a command
   *  refused here), and the page is then opened again. */
  converge(on: Converge): Promise<Converged>;
  /** Pending edits made again (step 5 of a converge), renamed as the keeper renamed the ids: a page's, and a grid's whose
   *  state the keeper's steps changed (`touched`), through the ordinary edit path, so each is told again and written next
   *  (one that no longer applies is dropped and said); one on a grid the steps left alone is told again only (`edit`):
   *  the grid shows it still. */
  reapply(pending: readonly LiveCommand[], touched: ReadonlySet<string>): Promise<void>;
  /** The page as its keeper has it, opened again whole, and why, said: the two causes that remain (a `rebuilt`, a write
   *  from further back than the keeper keeps), a converge that could not be made, or the hash mismatch. */
  reopen(why: string): Promise<void>;
  /** Said in the status line: a write refused (its words), pending edits dropped. */
  say(text: string): void;
}

export class LiveChannel {
  /** The version of the page on screen, which its writes name: undefined while none is (opening it, following it,
   *  converging). */
  shown: number | undefined;
  /** The page as the keeper has it at `shown`, and the ids it held then: what the screen converges from. */
  #confirmed: { readonly page: PageDocument; readonly held: readonly string[] } | undefined;
  /** The edits made on screen since it was last written, as applied there (pending). */
  #edits: LiveCommand[] = [];
  /** How deep in following the other editor: what the screen does meanwhile is that editor's, not its own. */
  #following = 0;
  #queue: Promise<void> = Promise.resolve();
  #timer: ReturnType<typeof setTimeout> | undefined;
  #stopped = false;
  /** A hash mismatch was met and the page opened again: a second, before a write hashes alike, is said once more and
   *  opens nothing (the page as the keeper has it is on screen already: no reopen loop). */
  #mismatched = false;
  readonly #editor: LivePage;
  readonly #screen: LiveScreen;
  readonly #after: number;

  constructor(editor: LivePage, screen: LiveScreen, after = WRITE_AFTER) {
    this.#editor = editor;
    this.#screen = screen;
    this.#after = after;
  }

  /** An edit made on screen (a grid's command, a page's), as applied there: written with the next write. While following
   *  the other editor, a page's command told is that editor's (the page put at its commands); a grid's is the person's
   *  own -- a grid tells none of another editor's (CubeApp.applyGridCommands) -- and is kept (the S1 audit's 2). */
  edit(command: LiveCommand): void {
    if (this.#following > 0 && 'page' in command) return;
    this.#edits.push(command);
  }

  /** The screen changed: written once it settles. */
  changed(): void {
    if (this.shown === undefined || this.#stopped) return;
    clearTimeout(this.#timer);
    this.#timer = setTimeout(() => { void this.enqueue(() => this.#write()); }, this.#after);
  }

  /** The other editor followed by `work`: nothing the screen does meanwhile is its own. */
  async following<T>(work: () => Promise<T>): Promise<T> {
    this.#following += 1;
    try {
      return await work();
    } finally {
      this.#following -= 1;
    }
  }

  /** The page opened whole at `version` (as it starts, or opened again), as its keeper has it (`page`, `held`: the
   *  screen's own page when not given): nothing of the screen's own pending. */
  opened(version: number, page?: PageDocument, held: readonly string[] = []): void {
    this.shown = version;
    this.#edits = [];
    const confirmed = page ?? this.#screen.page();
    this.#confirmed = confirmed === undefined ? undefined : { page: confirmed, held };
  }

  /** The page as the keeper has it at `shown`, as the screen knows it (undefined: none opened yet). */
  get confirmed(): PageDocument | undefined {
    return this.#confirmed?.page;
  }

  /** The edits made on screen that are not written yet. */
  get pending(): readonly LiveCommand[] {
    return this.#edits;
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

  /** What is pending written now, the screen waited for to be quiet first (a gesture in flight lands and is told), and
   *  the write awaited -- converged when the keeper made another page of it. Before a follow (F90) and a Save (H-5).
   *  `inQueue`: the caller runs in the channel's queue already (a follow): written there and then, never queued behind
   *  itself. */
  flush(inQueue = false): Promise<void> {
    clearTimeout(this.#timer);
    const work = async (): Promise<void> => {
      await this.#quiet();
      await this.#write(true);
    };
    return inQueue ? work() : this.enqueue(work);
  }

  /** The screen quiet: no grid's change in flight, waited for up to QUIET_AT_MOST. */
  async #quiet(): Promise<void> {
    const until = Date.now() + QUIET_AT_MOST;
    while (this.#screen.busy() && Date.now() < until && !this.#stopped) await new Promise((r) => setTimeout(r, QUIET_AFTER));
  }

  async #write(flushing = false): Promise<void> {
    const at = this.shown;
    if (at === undefined || this.#stopped) return;
    // nothing made on screen since it was last written or opened: nothing to write
    if (this.#edits.length === 0) return;
    // a gesture in flight (a grid's query not answered yet): its commands are written together once they have landed
    // (F97) -- the write waits for it, unless flushing, which waited already
    if (!flushing && this.#screen.busy()) {
      this.changed();
      return;
    }
    const write: LiveWrite = { edits: this.#edits };
    this.#edits = [];
    const sentPage = this.#screen.page();
    let said: Awaited<ReturnType<LivePage['report']>>;
    try {
      said = await this.#editor.report(at, write);
    } catch (e) {
      // refused -- an edit that does not apply where the page now is, the host refusing what it makes, a write from
      // further back than the keeper keeps -- or the editor gone: the sent batch is not the page's, said, and the screen
      // converges on the keeper's page by what moved it since (W8)
      if (this.#stopped || this.shown !== at) return;
      await this.#refused(at, e instanceof Error ? e.message : String(e));
      return;
    }
    if (this.shown !== at || this.#stopped) return;
    if (!said.combined) {
      // at its version: the page as it was sent is the keeper's
      this.shown = said.version;
      const held = this.#confirmed?.held ?? [];
      if (sentPage !== undefined) this.#confirmed = { page: sentPage, held: grown(held, makerIds(write.edits)) };
      await this.#checked(sentPage, said.hash);
      return;
    }
    if (said.since === null || said.since === undefined || said.edits === undefined) {
      // a version between was moved by no command, or is no longer kept: the one remaining reopen of this kind, said
      await this.#reopen('the page moved further than its keeper keeps the steps of');
      return;
    }
    await this.#converge(said.version, said.hash, { since: said.since, edits: said.edits, renamed: said.renamed });
  }

  /** A write refused: the screen converges on the keeper's page by what moved it since the version shown (W8), the
   *  refusal said; the page whole (past what the keeper keeps) opens it again. */
  async #refused(at: number, why: string): Promise<void> {
    this.#screen.say(`not kept: ${why} -- the page is as its keeper has it`);
    let moved: Awaited<ReturnType<LivePage['changes']>>;
    try {
      moved = await this.#editor.changes(at);
    } catch (e) {
      await this.#reopen(e instanceof Error ? e.message : String(e));
      return;
    }
    if (this.#stopped || this.shown !== at) return;
    if (!('commands' in moved)) {
      await this.#reopen('the page moved further than its keeper keeps the steps of');
      return;
    }
    await this.#converge(moved.version, moved.hash, { since: moved.commands, edits: [], renamed: {} });
  }

  /** The screen converged on the keeper's page at `version` (§2): steps 1-4 by the screen, inside following; then its
   *  hash checked against the keeper's (W17); then the pending edits made again, renamed (step 5). */
  async #converge(version: number, hash: string, on: Omit<Converge, 'confirmed' | 'held'>): Promise<void> {
    const confirmed = this.#confirmed;
    if (confirmed === undefined) {
      await this.#reopen('the page it converges from is not known here');
      return;
    }
    const pending = this.#edits;
    this.#edits = [];
    this.shown = undefined;
    let converged: Converged;
    try {
      converged = await this.following(() => this.#screen.converge({ confirmed: confirmed.page, held: confirmed.held, ...on }));
    } catch (e) {
      await this.#reopen(`${e instanceof Error ? e.message : String(e)}${dropped(pending)}`);
      return;
    }
    if (this.#stopped) return;
    this.#confirmed = {
      page: converged.page,
      held: grown(confirmed.held, [...makerIds(on.since), ...makerIds(on.edits), ...Object.values(on.renamed)]),
    };
    this.shown = version;
    if (!(await this.#checked(converged.page, hash, pending))) return;
    // its pending edits made again on the page as the keeper has it, each renamed as the keeper renamed the ids its
    // sent edits gave out (the screen's: told again, so they are written next)
    const map = new Map(Object.entries(on.renamed));
    const again = pending.map((p): LiveCommand => ('page' in p ? { page: renamedCommand(p.page, map) } : { grid: map.get(p.grid) ?? p.grid, command: p.command }));
    if (again.length > 0) await this.#screen.reapply(again, converged.touched);
    if (this.#edits.length > 0) this.changed();
  }

  /** The page on screen hashes to the keeper's (W17): true; else the one message that must never show, and the page
   *  opened again. */
  async #checked(page: PageDocument | undefined, hash: string, pending: readonly LiveCommand[] = []): Promise<boolean> {
    if (page === undefined) return true;
    const mine = pageHash(page);
    if (mine === hash) {
      this.#mismatched = false;
      return true;
    }
    if (this.#mismatched) {
      // opened again already and still differing: said, and the page kept as it is (the keeper's, as opened)
      this.#screen.say(HASH_MISMATCH);
      return true;
    }
    this.#mismatched = true;
    await this.#reopen(`${HASH_MISMATCH}${dropped(pending)}`);
    return false;
  }

  async #reopen(why: string): Promise<void> {
    const pending = this.#edits;
    this.#edits = [];
    this.shown = undefined;
    await this.#screen.reopen(`${why}${dropped(pending)}`);
  }
}

/** Said with a reopen or a refusal: the pending edits it dropped, if any (H-11: no edit is dropped unsaid). */
function dropped(pending: readonly LiveCommand[]): string {
  return pending.length === 0 ? '' : `; ${pending.length} change${pending.length === 1 ? '' : 's'} made here meanwhile ${pending.length === 1 ? 'was' : 'were'} not kept`;
}

/** The ids the commands gave out (their makers': a sheet's, a grid's, a chart's, a page filter's). */
function makerIds(commands: readonly LiveCommand[]): string[] {
  return commands.flatMap((c) => {
    if (!('page' in c)) return [];
    const id = (c.page as { readonly id?: string }).id;
    return id === undefined ? [] : [id];
  });
}

function grown(held: readonly string[], more: readonly string[]): readonly string[] {
  const had = new Set(held);
  const added = more.filter((id) => !had.has(id));
  return added.length === 0 ? held : [...held, ...added];
}
