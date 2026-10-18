// Leg B / B4: the editors work on LIVE state (docs/DATACUBE_LEG_B_STATE_OWNER_2026_09_28.md). Each
// case is an audit entry where a panel acted on a copy it captured when it was built, or an Apply
// recorded what it did not send.

import assert from 'node:assert/strict';
import { beforeEach, describe, it } from 'node:test';
import { JSDOM } from 'jsdom';

import { CubeEditor, draftFor, type CubeDraft, type EditorTab } from '../src/ui/editor.ts';
import type { CubeSnapshot } from '../src/snapshot.ts';
import { element } from '../../pure-protocol/src/index.ts';

const CUBE: CubeSnapshot = {
  source: { query: element('t') },
  columns: [
    { name: 'region', type: 'String' },
    { name: 'country', type: 'String' },
    { name: 'city', type: 'String' },
    { name: 'price', type: 'Float' },
  ],
  derived: [],
  rows: ['region'],
  pivotOn: [],
  sorts: [{ column: 'price', direction: 'desc' }],
  epoch: 1,
};

let dom: JSDOM;
let root: HTMLElement;

beforeEach(() => {
  dom = new JSDOM('<!doctype html><body><div id="r"></div></body>');
  root = dom.window.document.getElementById('r') as HTMLElement;
});

const go = (tab: EditorTab): void => {
  ([...root.querySelectorAll<HTMLElement>('.dc-editor-tab')].find((b) => b.textContent === tab)
    ?? assert.fail(`no tab ${tab}`)).click();
};
const dbl = (pane: 'available' | 'selected', column: string): void => {
  ([...root.querySelectorAll<HTMLElement>(`.dc-pane-${pane} .dc-selector-row`)]
    .find((r) => r.dataset['column'] === column) ?? assert.fail(`no ${pane} ${column}`))
    .dispatchEvent(new dom.window.MouseEvent('dblclick', { bubbles: true }));
};
const button = (within: string, text: string): HTMLButtonElement =>
  ([...root.querySelectorAll<HTMLButtonElement>(`${within} button`)].find((b) => b.textContent === text)
    ?? assert.fail(`no ${text} button`));

describe('the Properties editor applies what it SENT (P2-170)', () => {
  it('an edit made while an Apply runs is not recorded as applied; a second Apply meanwhile is ignored', async () => {
    const calls: { draft: CubeDraft; base: CubeDraft }[] = [];
    let land: (took: boolean) => void = () => {};
    const editor = new CubeEditor(root, draftFor(CUBE), {
      onApply: (draft, base) => {
        calls.push({ draft, base });
        return new Promise<boolean>((r) => { land = r; });
      },
      onClose: () => {},
    });
    go('Sorts');
    dbl('available', 'region');
    const first = editor.apply();
    void editor.apply();
    assert.equal(calls.length, 1, 'a second Apply while one runs is ignored');
    dbl('available', 'country'); // typed while the first runs
    land(true);
    await first;
    const next = editor.apply();
    land(true);
    await next;
    const sent = calls.at(-1) ?? assert.fail('no second apply');
    assert.deepEqual(sent.base.snapshot.sorts.map((x) => x.column), ['price', 'region'],
      'the base is what the first Apply SENT');
    assert.deepEqual(sent.draft.snapshot.sorts.map((x) => x.column), ['price', 'region', 'country'],
      'so the edit made meanwhile is an edit, and reaches the cube');
  });
});

describe('the Sorts tab reads the draft, not a copy (P2-171)', () => {
  it('a sort removed and added back shows the direction Apply will use', () => {
    new CubeEditor(root, draftFor(CUBE), { onApply: () => true, onClose: () => {} });
    go('Sorts');
    dbl('selected', 'price');
    dbl('available', 'price');
    const shown = root.querySelector<HTMLSelectElement>('.dc-pane-selected .dc-selector-row select')
      ?? assert.fail('no direction dropdown');
    assert.equal(shown.value, 'asc', 'added back as ascending, and shown so');
  });
});

describe('the Dimensions tab reads the draft, not a copy (P2-187)', () => {
  it('a hierarchy edited, then the dimension renamed or another added, keeps its columns', () => {
    const editor = new CubeEditor(root, draftFor(CUBE, undefined, [{ name: 'Geography', columns: [] }]),
      { onApply: () => true, onClose: () => {} });
    go('Dimensions');
    dbl('available', 'region');
    dbl('available', 'country');
    assert.equal(root.querySelector('.dc-dimension-count')?.textContent, '2', 'the count follows the edit');
    const name = root.querySelector<HTMLInputElement>('.dc-dimension-detail input') ?? assert.fail('no name');
    name.value = 'Geo';
    name.dispatchEvent(new dom.window.Event('change'));
    assert.deepEqual(editor.draft.dimensions, [{ name: 'Geo', columns: ['region', 'country'] }], 'renamed, columns kept');
    dbl('available', 'city');
    button('.dc-dimension-controls', 'Add').click();
    assert.deepEqual(editor.draft.dimensions, [
      { name: 'Geo', columns: ['region', 'country', 'city'] },
      { name: 'Dimension 1', columns: [] },
    ], 'added, the first one\'s columns kept');
  });
});
