// A CLASS'S SAMPLE ROWS: its first rows as Create a Class writes them into its project (model-actions.ts), each value
// as the grid's executor gave it, a bigint as text. Moved out of model-actions.ts, which opens the windows, so code with
// no screen writes them too: Python's classes, headless -- their rows planned and run by Python, on the plane its
// session chose, and written here (headless/grid.ts classSample; docs/PYTHON_ASKS_DATACUBE_2026_10_15.md, item 7).

import type { Lambda } from '../../pure-protocol/src/index.ts';
import type { Scalar } from '../../engine-client/src/result.ts';
import type { PlanAndRunHost } from './runner.ts';

/** A class's sample: its columns' names and its rows, each a value per column. */
export interface SampleRows {
  readonly columns: string[];
  readonly rows: (string | number | boolean | null)[][];
}

/** Rows as the route's sample takes them: each value as the executor gave it, a bigint as text. */
export function sampleRows(rows: {
  readonly columns: readonly { readonly name: string; readonly values: readonly Scalar[] }[];
  readonly rowCount: number;
}): SampleRows {
  return {
    columns: rows.columns.map((c) => c.name),
    rows: [...Array(rows.rowCount).keys()].map((r) => rows.columns.map((c) => {
      const v = c.values[r] ?? null;
      return typeof v === 'bigint' ? String(v) : v;
    })),
  };
}

/** A class's first rows as the route's sample takes them, planned and run where its grid's are. */
export async function sampleOf(host: PlanAndRunHost, sample: Lambda): Promise<SampleRows> {
  return sampleRows((await host.planAndRun(sample, 0)).rows);
}
