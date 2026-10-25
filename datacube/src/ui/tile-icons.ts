// A TILE'S STATE, AS AN ICON LEFT OF ITS TITLE (the user, 2026-10-25; docs/DATACUBE_UI_REVIEW_2026_10_24.md §1a): every
// tile -- a grid, a chart, a KPI, a text box -- says its state in the same place, quiet for the usual one and accented
// for an exception, so a page of ordinary tiles is calm and the one that is different stands out. Drawn, not typed: an
// emoji keeps its own colours, and the accent is the stylesheet's (app.css .dc-tile-state[data-state]).
//
// The button carries its state's word too, hidden from sight but not from a screen reader or a test: "Snapped",
// "Pinned". What a click does is its owner's (a grid's snap menu, a chart's states, a text box's editor).

/** The states a tile's icon says. */
export type TileState = 'live' | 'snapped' | 'dynamic' | 'pinned' | 'frozen' | 'detached' | 'viewing' | 'editing';

const NS = 'http://www.w3.org/2000/svg';

/** Each state's drawing, on a 16-unit square, stroked in the current colour (a filled dot for Live). */
const DRAWN: Readonly<Record<TileState, readonly string[]>> = {
  // a dot: live rows, which may move
  live: ['M8 5.5a2.5 2.5 0 1 1 0 5a2.5 2.5 0 1 1 0-5z'],
  // a camera: a copy taken
  snapped: ['M2.5 5.5h2.5l1.2-1.8h3.6L11 5.5h2.5v7h-11z', 'M8 7a2 2 0 1 1 0 4a2 2 0 1 1 0-4z'],
  // two links: it follows its grid
  dynamic: ['M7 9.5 9 7.5', 'M6 7.5 4.5 9a1.8 1.8 0 0 0 2.5 2.5L8.5 10', 'M10 8.5 11.5 7A1.8 1.8 0 0 0 9 4.5L7.5 6'],
  // a pin: its own grouping kept
  pinned: ['M6 2.5h4l-.5 3.5 2 2v1h-7v-1l2-2z', 'M8 9v4.5'],
  // a snowflake: kept as it was
  frozen: ['M8 2v12', 'M2.8 5l10.4 6', 'M2.8 11l10.4-6', 'M6.5 2.8 8 4l1.5-1.2', 'M6.5 13.2 8 12l1.5 1.2'],
  // the links apart: its grid is gone
  detached: ['M6 7.5 4.5 9a1.8 1.8 0 0 0 2.5 2.5L8.5 10', 'M10 8.5 11.5 7A1.8 1.8 0 0 0 9 4.5L7.5 6', 'M3 3l2 2', 'M11 11l2 2'],
  // a pencil: words to edit (quiet), and being edited (accented)
  viewing: ['M10.5 2.8l2.7 2.7L6 12.7H3.3V10z', 'M9 4.3l2.7 2.7'],
  editing: ['M10.5 2.8l2.7 2.7L6 12.7H3.3V10z', 'M9 4.3l2.7 2.7'],
};

/** The icon of a state. */
export function stateIcon(doc: Document, state: TileState): SVGSVGElement {
  const svg = doc.createElementNS(NS, 'svg');
  svg.setAttribute('class', 'dc-tile-state-icon');
  svg.setAttribute('viewBox', '0 0 16 16');
  svg.setAttribute('width', '14');
  svg.setAttribute('height', '14');
  svg.setAttribute('aria-hidden', 'true');
  for (const d of DRAWN[state]) {
    const path = doc.createElementNS(NS, 'path');
    path.setAttribute('d', d);
    path.setAttribute('fill', state === 'live' ? 'currentColor' : 'none');
    path.setAttribute('stroke', 'currentColor');
    path.setAttribute('stroke-width', '1.4');
    path.setAttribute('stroke-linecap', 'round');
    path.setAttribute('stroke-linejoin', 'round');
    svg.append(path);
  }
  return svg;
}

/** A tile's state button, painted by `paintState`. */
export function stateButton(doc: Document): HTMLButtonElement {
  const button = doc.createElement('button');
  button.type = 'button';
  button.className = 'dc-tile-state';
  return button;
}

/**
 * The button says `state`: its icon, its word (`word`, for a screen reader and a test), and on hover `tip`. `menu`:
 * a click opens a menu of the states it can change to; without, a click does what its owner says, or nothing.
 */
export function paintState(
  button: HTMLButtonElement, state: TileState, word: string, tip: string, how: { readonly menu?: boolean; readonly off?: boolean } = {},
): void {
  const doc = button.ownerDocument;
  button.dataset['state'] = state;
  const said = doc.createElement('span');
  said.className = 'dc-tile-state-word';
  said.textContent = word;
  button.replaceChildren(stateIcon(doc, state), said);
  button.title = tip;
  if (how.menu) button.setAttribute('aria-haspopup', 'menu');
  else button.removeAttribute('aria-haspopup');
  if (how.off) button.setAttribute('aria-disabled', 'true');
  else button.removeAttribute('aria-disabled');
}
