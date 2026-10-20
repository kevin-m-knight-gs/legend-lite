// JOIN… IN THE TAB (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §17.5): a grid joined to another of the page -- each a
// grid's result as it is now, read over one model of the tables they read (join-open.ts), the window asking and showing
// (ui/join-dialog.ts), the join made a grid beside the first by the page's own addGrid. Fetched with the first Join…: a
// page with no join loads none of it (test/bundle-budget.test.ts).

import { gridCapped, type JoinInput, type JoinSource } from '../cube-document.ts';
import { JoinInputs, TAB_TABLES, openInputs, summaryCounts, type OpenedInputs } from '../join-open.ts';
import { joinSummary, joinTitle } from '../join.ts';
import type { PageApp } from '../page/page-app.ts';
import { joinDialog } from '../ui/join-dialog.ts';
import { catalogColumns } from '../upload.ts';
import type { GridRows, LocalEngine, Services } from './services.ts';
import type { PageSources } from './sources.ts';

export interface JoinFlowOf {
  /** The grid joined, by its tile's id. */
  readonly cube: string;
  readonly page: PageApp;
  readonly sources: PageSources;
  readonly services: Services & { readonly engine: { readonly local: LocalEngine } };
  /** Whether the page on screen is still the one the join began on (another opened meanwhile: no grid of this one). */
  readonly stillShown: () => boolean;
  /** Said to the person beside the page: what opening the join left out. */
  readonly say: (text: string) => void;
}

/** A grid of the page as a join reads it -- its result as it is now, under the name it shows, and the tables it reads. */
function inputOf(o: JoinFlowOf, id: string): { readonly input: JoinInput; readonly tables: readonly string[] } | undefined {
  const place = o.sources.place(id);
  const doc = o.page.grid(id)?.cubeDocument(o.page.title);
  if (!place?.tables || !doc) return undefined;
  // the title a person gave its tile, else what it reads
  const name = o.page.givenTitle(id) ?? place.label ?? doc.source.name;
  return { input: { name, source: doc.source, query: doc.query }, tables: place.tables };
}

/** Join… on grid `cube`: the window, and -- applied -- the join's grid beside it. Refused in words before the window opens. */
export async function joinFlow(o: JoinFlowOf): Promise<void> {
  const { page, sources, services } = o;
  const local = services.engine.local;
  const mine = inputOf(o, o.cube);
  if (!mine) throw new Error('this grid reads no table of this tab\'s database');
  const others = new Map(page.grids.filter((id) => id !== o.cube).flatMap((id) => {
    const i = inputOf(o, id);
    return i ? [[id, i] as const] : [];
  }));
  if (others.size === 0) {
    throw new Error('a join needs another grid on the page over a file or a remote file: add one first (New ▸ Data Source…)');
  }
  // each grid's inputs with mine over one model, opened once while the window is open, their planners let go when it
  // closes -- after any question still being answered over them (join-open.ts JoinInputs)
  const opened = new JoinInputs<{ readonly inputs: OpenedInputs; readonly rows: GridRows; dispose(): void }>(async (their) => {
    const other = others.get(their)!;
    let rows: GridRows | undefined;
    try {
      const inputs = await openInputs(mine.input, other.input, {
        here: TAB_TABLES,
        tables: [...mine.tables, ...other.tables],
        models: local.tables,
        catalog: (table) => catalogColumns(local.engine, local.tables, table),
        databaseType: local.engine.databaseType,
        typer: (model, runtime) => {
          rows = services.engine.rows(model, runtime);
          return rows.planner!;
        },
      });
      const held = rows!;
      return { inputs, rows: held, dispose: () => held.planner?.dispose?.() };
    } catch (e) {
      rows?.planner?.dispose?.();
      throw e;
    }
  });
  try {
    await joinDialog(document, {
      mine: mine.input.name,
      others: [...others].map(([id, i]) => ({ id, name: i.input.name })),
      inputs: async (their) => (await opened.get(their)).inputs,
      parse: (text) => local.planner.parse(text),
      // over their grid's own planner, held until it is answered
      summary: (their, how) => opened.use(their, ({ inputs, rows }) => summaryCounts(joinSummary(inputs.left, inputs.right, how),
        async (query) => rows.engine!.run((await rows.planner!.plan(query)).sql, 0))),
      apply: async (their, how) => {
        const other = others.get(their)!;
        const source: JoinSource = {
          _type: 'join', name: joinTitle(how, mine.input.name, other.input.name), left: mine.input, right: other.input, how, columns: [],
        };
        const opened = await sources.openedJoin(source, {
          capped: gridCapped(page.cubes.length + 1), tables: [...mine.tables, ...other.tables],
        });
        if (!opened) return;
        if (!o.stillShown()) {
          await opened.discard?.();
          return;
        }
        page.addGrid(opened.make, { near: o.cube });
        if (opened.notes.length > 0) o.say(opened.notes.join('; '));
      },
    });
  } finally {
    await opened.close();
  }
}
