// A SAMPLE, SET (docs/DATACUBE_FLOW_MODE_2026_10_24.md M3: a flow's Sample box, Alteryx's First N and Last N): the
// grid's own Top N (snapshot.ts TopN, grid-gestures.ts filter.topN) said as a sample is -- its first rows, or its last,
// in the order of a column -- in a window its grid docks where its options dock it. Fetched with the first Sample.

import type { TopN } from '../snapshot.ts';

export interface SampleFormOptions {
  /** The columns it can be ordered by. */
  readonly columns: readonly string[];
  /** The Top N the grid has now, if any: where the form starts. */
  readonly now: TopN | undefined;
  /** Its rows kept: the first `n` by `column` (its smallest), or its last (`last`: its largest). */
  apply(column: string, n: number, last: boolean): Promise<string | null>;
  /** Every row kept again (the Top N taken off). */
  clear(): Promise<string | null>;
  close(): void;
}

/** The form, drawn into `host`. */
export function sampleForm(host: HTMLElement, o: SampleFormOptions): void {
  const doc = host.ownerDocument;
  const el = <K extends keyof HTMLElementTagNameMap>(tag: K, cls: string, parent: Element, text?: string): HTMLElementTagNameMap[K] => {
    const e = doc.createElement(tag);
    e.className = cls;
    if (text !== undefined) e.textContent = text;
    parent.append(e);
    return e;
  };
  const body = el('div', 'dc-sample', host);
  const line = el('div', 'dc-sample-line', body);
  el('span', 'dc-sample-word', line, 'Keep the');
  const which = el('select', 'dc-select dc-sample-which', line);
  which.setAttribute('aria-label', 'First or last');
  for (const [value, label] of [['first', 'first'], ['last', 'last']] as const) {
    const opt = el('option', '', which, label);
    opt.value = value;
  }
  const n = el('input', 'dc-picker-input dc-sample-n', line);
  n.type = 'number';
  n.min = '1';
  n.step = '1';
  n.setAttribute('aria-label', 'How many rows');
  el('span', 'dc-sample-word', line, 'rows, in the order of');
  const by = el('select', 'dc-select dc-sample-by', line);
  by.setAttribute('aria-label', 'The column it is ordered by');
  for (const c of o.columns) {
    const opt = el('option', '', by, c);
    opt.value = c;
  }
  el('p', 'dc-sample-hint', body, 'The first rows are its smallest by that column, the last its largest; rows that tie are kept.');
  const status = el('p', 'dc-sample-status', body);
  status.setAttribute('role', 'status');
  n.value = String(o.now?.n ?? 10);
  which.value = o.now?.bottom === true || o.now === undefined ? 'first' : 'last';
  if (o.now !== undefined && o.columns.includes(o.now.by)) by.value = o.now.by;

  const foot = el('div', 'dc-sample-foot', body);
  const clear = el('button', 'dc-picker-button dc-quiet', foot, 'Keep every row');
  clear.type = 'button';
  clear.disabled = o.now === undefined;
  el('span', 'dc-save-spacer', foot);
  const cancel = el('button', 'dc-picker-button dc-quiet', foot, 'Cancel');
  cancel.type = 'button';
  const ok = el('button', 'dc-picker-button dc-primary', foot, 'OK');
  ok.type = 'button';
  const done = async (made: Promise<string | null>): Promise<void> => {
    const refused = await made;
    if (refused === null) o.close();
    else status.textContent = refused;
  };
  const check = (): void => {
    const count = Number(n.value);
    ok.disabled = !Number.isInteger(count) || count < 1 || by.value === '';
  };
  n.addEventListener('input', check);
  by.addEventListener('change', check);
  check();
  cancel.addEventListener('click', () => o.close());
  clear.addEventListener('click', () => void done(o.clear()));
  ok.addEventListener('click', () => void done(o.apply(by.value, Number(n.value), which.value === 'last')));
  n.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !ok.disabled) ok.click();
  });
}
