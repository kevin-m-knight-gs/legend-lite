// A KEEPER AS PYTHON'S IS, FOR THE TESTS (keeper.py settle; docs/DATACUBE_LIVE_EDITING_2026_10_24.md §4): a write's edits
// applied by DataCube's one rule (page-rebase.ts rebaseEdits) to the page as it is -- made again on top when it moved on
// since -- and what they make kept, each version's steps kept so a page open at an older one follows by them; the answer
// a write gets carries what the tab converges by (`since`, `edits`, `renamed`) and the page's hash (page-hash.ts), as
// Python's engine answers. A grid's commands are applied to its saved cube as headless gridApply applies them -- no
// planner: what a run alone adds (column types) is no command's.

import { applyToSnapshot } from '../src/config.ts';
import { configurationOf, treeOf, writeCube } from '../src/cube-document.ts';
import { fold } from '../src/cube-state.ts';
import { applyGridCommands } from '../src/grid-commands.ts';
import type { LiveCommand, LiveMoved, LivePage, LiveRead, LiveSettled, LiveWrite } from '../src/app/services.ts';
import { applyPageCommands, idsOf, type PageCommand } from '../src/page-commands.ts';
import type { PageDocument } from '../src/page-document.ts';
import { pageHash } from '../src/page-hash.ts';
import { rebaseEdits, type GridApplier } from '../src/page-rebase.ts';
import type { CubeSnapshot } from '../src/snapshot.ts';
import { SNAPSHOT } from './cube-fixture.ts';

/** A grid's commands applied to its saved cube as headless gridApply applies them (headless/grid.ts applied: the
 *  commands, the settings laid on the query, folded as the state lands, written as the screen writes it). */
export const applyGridToCube: GridApplier = async (_page, cube, _id, commands) => {
  const snapshot = { ...cube.query, source: SNAPSHOT.source, epoch: 0 } as CubeSnapshot;
  const d = applyGridCommands({ snapshot, config: configurationOf(cube), tree: treeOf(cube) }, commands);
  const landed = fold({ snapshot: applyToSnapshot(d.snapshot, d.config), configuration: d.config, tree: d.tree });
  return writeCube({
    name: cube.name, source: cube.source, snapshot: landed.snapshot, configuration: landed.configuration, tree: landed.tree,
    ...(cube.unknown ? { unknown: cube.unknown } : {}),
  });
};

export class FakeKeeper implements LivePage {
  version = 1;
  page: PageDocument;
  held: string[];
  /** Each write, as sent. */
  readonly written: LiveWrite[] = [];
  /** The edits that moved the page to each version (none for version 1, the page born whole). */
  readonly steps = new Map<number, readonly LiveCommand[]>();
  reads = 0;
  readonly changesAsked: number[] = [];
  /** A write held until the test lets it go (the keeper slow to answer): each pending write's release. */
  hold = false;
  readonly #held: (() => void)[] = [];
  /** How many versions back the keeper keeps its steps (keeper.py STEPS_KEPT). */
  keeps = 64;

  constructor(page: PageDocument, held: readonly string[] = idsOf(page)) {
    this.page = page;
    this.held = [...held];
  }

  async read(): Promise<LiveRead> {
    this.reads += 1;
    return { version: this.version, page: this.page, held: this.held, hash: pageHash(this.page) };
  }

  /** What moved the page since `since`: its steps when every version since kept them, else the page whole. */
  async changes(since: number): Promise<LiveMoved | LiveRead> {
    this.changesAsked.push(since);
    const moved = this.#since(since, this.version);
    if (moved === null) return this.read();
    return { version: this.version, commands: moved, hash: pageHash(this.page) };
  }

  /** The other editor (Python) writes commands it made at the version now. */
  pythonWrites(commands: readonly PageCommand[]): void {
    const done = applyPageCommands({ page: this.page, held: this.held }, commands);
    this.page = done.draft.page;
    this.held = [...done.draft.held];
    this.version += 1;
    this.steps.set(this.version, done.applied.map((c): LiveCommand => ({ page: c })));
  }

  /** The other editor writes a page whole (a page loaded: N1): no step for its version. */
  pythonLoads(page: PageDocument): void {
    this.page = page;
    this.held = [...new Set([...this.held, ...idsOf(page)])];
    this.version += 1;
  }

  /** Every write held is let go, and none is held from now on. */
  release(): void {
    this.hold = false;
    for (const go of this.#held.splice(0)) go();
  }

  async report(at: number, write: LiveWrite): Promise<LiveSettled> {
    this.written.push(write);
    if (this.hold) await new Promise<void>((r) => { this.#held.push(r); });
    if (at > this.version || this.version - at > this.keeps) throw new Error('409: the page changed where it is open, further than it keeps, while this change was made: make it again');
    const before = this.version;
    const done = await rebaseEdits({ page: this.page, held: this.held }, write.edits, applyGridToCube, this.#heldAt(at)).catch((e: unknown) => {
      throw new Error(`409: ${at !== this.version ? 'the page changed where it is open while this change was made: ' : ''}${e instanceof Error ? e.message : String(e)}`);
    });
    const combined = at !== this.version || Object.keys(done.renamed).length > 0;
    this.page = done.page;
    this.held = [...done.held];
    this.version += 1;
    this.steps.set(this.version, done.edits);
    const hash = pageHash(this.page);
    if (!combined) return { version: this.version, combined, renamed: done.renamed, hash };
    return { version: this.version, combined, renamed: done.renamed, hash, since: this.#since(at, before), edits: done.edits };
  }

  /** The steps from `from` (exclusive) to `to` (inclusive), or null when one is not kept. */
  #since(from: number, to: number): LiveCommand[] | null {
    const out: LiveCommand[] = [];
    for (let v = from + 1; v <= to; v += 1) {
      const step = this.steps.get(v);
      if (step === undefined) return null;
      out.push(...step);
    }
    return out;
  }

  /** The ids the page had held at `at`: those it holds now less the ones the steps since gave out (what a write made
   *  at `at` knew: H3). */
  #heldAt(at: number): string[] {
    const given = new Set<string>();
    for (let v = at + 1; v <= this.version; v += 1) {
      for (const c of this.steps.get(v) ?? []) {
        const id = 'page' in c ? (c.page as { readonly id?: string }).id : undefined;
        if (id !== undefined) given.add(id);
      }
    }
    return this.held.filter((id) => !given.has(id));
  }
}
