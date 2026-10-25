// A SUMMARIZE, SET (docs/DATACUBE_FLOW_MODE_2026_10_24.md M1, Alteryx's Summarize): each column of the box's input
// grouped by, aggregated by an action -- a sum, an average, a count, its smallest or largest, ... -- or left out, laid
// out as Alteryx's Summarize lays it (a row a column: its name, its type, its action) -- summarize.ts's rule -- in a
// window its grid docks where its options dock it. Fetched with the first.

import type { AggregateFn } from '../snapshot.ts';
import type { Summary, SummaryAction } from '../summarize.ts';

export interface SummarizeFormOptions {
  /** Its columns, in order, as its Summarize has them now (grid-edits.ts summaryOf). */
  readonly columns: readonly { readonly name: string; readonly type: string; readonly action?: SummaryAction; readonly actions: readonly SummaryAction[] }[];
  /** An aggregate in DataCube's words, as Column Properties' Aggregation says it (ui/panel-column.ts AGGREGATES). */
  readonly label: (fn: AggregateFn) => string;
  /** Its rows summarised: null when they were, else why not (the form stays, saying it). */
  apply(summary: Summary): Promise<string | null>;
  close(): void;
}

/** The form, drawn into `host`. */
export function summarizeForm(host: HTMLElement, o: SummarizeFormOptions): void {
  const doc = host.ownerDocument;
  const el = <K extends keyof HTMLElementTagNameMap>(tag: K, cls: string, parent: Element, text?: string): HTMLElementTagNameMap[K] => {
    const e = doc.createElement(tag);
    e.className = cls;
    if (text !== undefined) e.textContent = text;
    parent.append(e);
    return e;
  };
  // an action in words, as its choice says it
  const said = (a: SummaryAction): string => (a === 'group' ? 'Group by' : o.label(a).replace(/^./, (c) => c.toUpperCase()));
  const body = el('div', 'dc-sample dc-summarize', host);
  const find = el('input', 'dc-picker-input dc-select-find', body);
  find.type = 'search';
  find.placeholder = 'Find a column';
  find.setAttribute('aria-label', 'Find a column');
  const head = el('div', 'dc-summarize-row dc-select-head', body);
  for (const h of ['Column', 'Type', 'Action']) el('span', '', head, h);
  const list = el('div', 'dc-select-list', body);
  const rows = o.columns.map((c) => {
    const row = el('div', 'dc-summarize-row', list);
    row.dataset['column'] = c.name;
    el('span', 'dc-select-name', row, c.name);
    el('span', 'dc-select-type', row, c.type);
    const action = el('select', 'dc-select dc-summarize-action', row);
    action.setAttribute('aria-label', `What to do with ${c.name}`);
    el('option', '', action, '— left out').value = '';
    for (const a of c.actions) el('option', '', action, said(a)).value = a;
    action.value = c.action ?? '';
    return { name: c.name, row, action };
  });
  const summary = el('p', 'dc-sample-hint', body);
  const status = el('p', 'dc-sample-status', body);
  status.setAttribute('role', 'status');
  const foot = el('div', 'dc-sample-foot', body);
  el('span', 'dc-save-spacer', foot);
  const cancel = el('button', 'dc-picker-button dc-quiet', foot, 'Cancel');
  cancel.type = 'button';
  cancel.addEventListener('click', () => o.close());
  const ok = el('button', 'dc-picker-button dc-primary', foot, 'OK');
  ok.type = 'button';
  const chosen = (): Summary => ({
    columns: rows.filter((r) => r.action.value !== '').map((r) => ({ name: r.name, action: r.action.value as SummaryAction })),
  });
  const check = (): void => {
    const t = chosen().columns;
    const groups = t.filter((c) => c.action === 'group').map((c) => c.name);
    const aggregated = t.length - groups.length;
    for (const r of rows) r.row.classList.toggle('dc-select-out', r.action.value === '');
    summary.textContent = groups.length === 0 ? 'Group by a column: a row of each group, its aggregates beside it.'
      : `A row of each ${groups.join(', ')}${aggregated > 0 ? `, ${aggregated} aggregated beside it` : ''}; the other columns left out.`;
    ok.disabled = groups.length === 0;
    ok.title = groups.length === 0 ? 'Not yet: group by a column' : '';
  };
  for (const r of rows) r.action.addEventListener('change', check);
  find.addEventListener('input', () => {
    const q = find.value.trim().toLowerCase();
    for (const r of rows) r.row.hidden = q !== '' && !r.name.toLowerCase().includes(q);
  });
  check();
  ok.addEventListener('click', () => void (async () => {
    status.textContent = '';
    const refused = await o.apply(chosen());
    if (refused === null) o.close();
    else status.textContent = refused;
  })());
}
