// A DATE TAKEN TO ITS PERIOD, SET (docs/DATACUBE_FLOW_MODE_2026_10_24.md M3: a flow's DateTime box): the grid's own
// period preset (grid-presets.ts `grain`: a date's day, week, month, quarter or year, the period's first day) said as a
// tool is -- a date column, and its period -- in a window its grid docks where its options dock it. Fetched with the
// first DateTime.

import { GRAIN_LABELS } from '../calc-presets.ts';
import type { TimeGrain } from '../chart-spec.ts';

export interface DateFormOptions {
  /** The date columns it can take to a period, by name. */
  readonly columns: readonly string[];
  /** A column of `column` taken to `grain`, added. */
  apply(column: string, grain: TimeGrain): Promise<string | null>;
  close(): void;
}

/** The form, drawn into `host`. */
export function dateForm(host: HTMLElement, o: DateFormOptions): void {
  const doc = host.ownerDocument;
  const el = <K extends keyof HTMLElementTagNameMap>(tag: K, cls: string, parent: Element, text?: string): HTMLElementTagNameMap[K] => {
    const e = doc.createElement(tag);
    e.className = cls;
    if (text !== undefined) e.textContent = text;
    parent.append(e);
    return e;
  };
  const body = el('div', 'dc-sample', host);
  if (o.columns.length === 0) {
    el('p', 'dc-sample-hint', body, 'Its input has no date column to take to a period.');
    const foot = el('div', 'dc-sample-foot', body);
    el('span', 'dc-save-spacer', foot);
    const close = el('button', 'dc-picker-button', foot, 'Close');
    close.type = 'button';
    close.addEventListener('click', () => o.close());
    return;
  }
  const line = el('div', 'dc-sample-line', body);
  el('span', 'dc-sample-word', line, 'Take');
  const column = el('select', 'dc-select dc-date-column', line);
  column.setAttribute('aria-label', 'The date column');
  for (const c of o.columns) {
    const opt = el('option', '', column, c);
    opt.value = c;
  }
  el('span', 'dc-sample-word', line, 'to its');
  const grain = el('select', 'dc-select dc-date-grain', line);
  grain.setAttribute('aria-label', 'The period');
  for (const [g, label] of Object.entries(GRAIN_LABELS)) {
    const opt = el('option', '', grain, label.toLowerCase());
    opt.value = g;
  }
  grain.value = 'month';
  el('p', 'dc-sample-hint', body, 'A new column: each date taken to its period\'s first day (a day, its date).');
  const status = el('p', 'dc-sample-status', body);
  status.setAttribute('role', 'status');
  const foot = el('div', 'dc-sample-foot', body);
  el('span', 'dc-save-spacer', foot);
  const cancel = el('button', 'dc-picker-button dc-quiet', foot, 'Cancel');
  cancel.type = 'button';
  const ok = el('button', 'dc-picker-button dc-primary', foot, 'OK');
  ok.type = 'button';
  cancel.addEventListener('click', () => o.close());
  ok.addEventListener('click', () => void (async () => {
    const refused = await o.apply(column.value, grain.value as TimeGrain);
    if (refused === null) o.close();
    else status.textContent = refused;
  })());
}
