// A FLOW AS A PERSON BUILDS ONE (docs/DATACUBE_FLOW_MODE_2026_10_24.md M1, its "done when"): Create ▸ Flow, then each
// box by drag and drop alone -- an Input dragged onto the canvas, a Filter onto the Input, a Formula onto the Filter, a
// Summarize onto the Formula -- each set in the settings pane by the grid's own window docked there, each previewed in
// the results pane; a box moved and the move undone; the flow saved, the tab reloaded and the flow opened again (no box
// opened until it is shown, the last box's rows as before); shared, the link opening the same flow in a fresh tab; and a
// file from the desktop dropped onto the canvas, an Input over it. And its lines (M2): one drawn from a box's output onto
// another, and undone; one taken off, the box it went into frozen, and linked again from its settings; a tool dropped on
// a line, between its boxes, and undone in one step. And its Join and Union boxes (M2): each over two boxes, live -- an
// edit of the Filter reaches the Join -- and set again from its settings, undone in one step.
//
//   bazel test //datacube:verify_flow_test        bazel run //datacube:verify_flow (by hand)

// first: points Playwright at the Chromium Bazel fetched (as a browser_test; a no-op under bazel run)
import '../../tools/browser/pinned-chromium.mjs';
import { chromium } from 'playwright';
import { outPath, serve, siteRoot } from './harness.mjs';

let failed = false;
function bad(m) { console.log(`FAIL: ${m}`); failed = true; }
function check(ok, m) { if (ok) console.log(`ok: ${m}`); else bad(m); }

const { port, close } = await serve(siteRoot());
const browser = await chromium.launch();
// the clipboard: Share… writes the link there
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, permissions: ['clipboard-read', 'clipboard-write'] });
const page = await context.newPage();
const errors = [];
const alerts = [];
page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
page.on('dialog', async (d) => {
  // a page about to be left asks first (beforeunload) while it holds work; an open over unsaved changes is yes
  if (d.type() === 'beforeunload' || d.type() === 'confirm') { await d.accept(); return; }
  alerts.push(d.message());
  await d.dismiss();
});

const item = (label) => page.locator('.dc-menu .dc-menu-item', { has: page.locator(`:scope > .dc-menu-label:text-is(${JSON.stringify(label)})`) }).first();
/** The page's menu: `path`, each submenu pointed at, the last clicked. */
async function burger(...path) {
  await page.click('.dc-titlebar-menu');
  for (const label of path.slice(0, -1)) await item(label).hover();
  await item(path.at(-1)).click();
}
const boxes = (tab = page) => tab.locator('.dc-flow-box').evaluateAll((els) => els.map((e) => ({
  id: e.dataset.tile, tool: e.dataset.tool, says: e.querySelector('.dc-flow-says')?.textContent ?? '', shown: e.classList.contains('dc-flow-shown'),
})));
const boxCount = (n, tab = page) => tab.waitForFunction((k) => document.querySelectorAll('.dc-flow-box').length === k, n, { timeout: 60_000 });
/** The results pane's rows: its grid's status ("2,404 rows", or "the first 1,000 of 5,000 rows"), once it has some. */
async function resultRows(tab = page) {
  const text = await tab.waitForFunction(() => {
    const t = document.querySelector('.dc-flow-results')?.textContent ?? '';
    const m = /of ([\d,]+) rows/.exec(t) ?? /([\d,]+) rows ×/.exec(t);
    return m ? m[1] : false;
  }, undefined, { timeout: 60_000 }).then((h) => h.jsonValue());
  return Number(String(text).replace(/,/g, ''));
}
/** Until the shown box's grid has opened and gone quiet: its query landed, nothing changing for a moment. */
async function settled(tab = page) {
  await tab.waitForFunction(() => {
    const signal = window.__dataCubeSignal;
    const now = performance.now();
    const w = (window.__flowWatch ??= { changes: signal?.changes, since: now });
    if (!signal || signal.changes !== w.changes) {
      w.changes = signal?.changes;
      w.since = now;
      return false;
    }
    return now - w.since >= 500;
  }, null, { timeout: 60_000 });
  await tab.evaluate(() => { window.__flowWatch = undefined; });
}
/** A tool dragged from the palette and let go on `target` (a box's id, or the canvas), as a person drags one. */
async function drag(tool, target) {
  await page.locator(`.dc-flow-tool[data-tool="${tool}"]`).scrollIntoViewIfNeeded();
  const from = await page.locator(`.dc-flow-tool[data-tool="${tool}"]`).boundingBox();
  const onto = await page.locator(target === 'canvas' ? '.dc-flow-canvas' : `.dc-flow-box[data-tile="${target}"]`).boundingBox();
  if (!from || !onto) throw new Error(`nothing to drag ${tool} from, or onto ${target}`);
  const to = target === 'canvas' ? { x: onto.x + onto.width / 2, y: onto.y + onto.height * 0.75 } : { x: onto.x + onto.width / 2, y: onto.y + onto.height / 2 };
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
  await page.mouse.down();
  await page.mouse.move(to.x, to.y, { steps: 12 });
  await page.mouse.up();
}
/** The lines drawn (each beside the wider stroke a pointer finds). */
const lineCount = () => page.locator('.dc-flow-lines path.dc-flow-line').count();
/** What a box reads, as the page has it: each side's live grid, or 'copy'. */
const readsOf = (id) => page.evaluate((tile) => {
  const doc = window.__dataPage.document('x');
  const view = doc.views.find((v) => v.id === tile);
  const source = doc.cubes.find((c) => c.id === view.cube).cube.source;
  const side = (i) => ('grid' in i ? (i.port ? `${i.grid}:${i.port}` : i.grid) : 'copy');
  return source._type === 'rows' ? side(source.input) : source._type === 'join' ? [side(source.left), side(source.right)] : null;
}, id);
/** The middle of the line into `tile`, in the window's points. */
const lineMiddle = (tile) => page.evaluate((t) => {
  const hit = document.querySelector(`.dc-flow-lines path.dc-flow-hit[data-tile="${t}"]`);
  const p = hit.getPointAtLength(hit.getTotalLength() / 2);
  const r = document.querySelector('.dc-flow-plane').getBoundingClientRect();
  return { x: r.left + p.x, y: r.top + p.y };
}, tile);
/** A point of the window on the canvas, away from every box, port and line: where a line let go makes a box. */
const emptyCanvasPoint = () => page.evaluate(() => {
  const c = document.querySelector('.dc-flow-canvas').getBoundingClientRect();
  for (let y = c.bottom - 30; y > c.top + 60; y -= 24) {
    for (let x = c.left + 100; x < c.right - 100; x += 24) {
      const hit = document.elementsFromPoint(x, y);
      if (hit.some((e) => e.closest?.('.dc-flow-box, .dc-flow-note, .dc-flow-toolbar') || e.classList?.contains('dc-flow-hit'))) continue;
      return { x, y, canvasX: x - document.querySelector('.dc-flow-plane').getBoundingClientRect().left };
    }
  }
  throw new Error('no empty place on the canvas');
});
/** A box shown, its rows once they have come. */
async function show(id) {
  await page.locator(`.dc-flow-box[data-tile="${id}"]`).click();
  await page.waitForFunction((t) => document.querySelector(`.dc-flow-box[data-tile="${t}"]`)?.classList.contains('dc-flow-shown'), id);
  await settled();
  return resultRows();
}
/** The window docked in the settings pane, once it is there. */
const docked = (name) => page.locator(`.dc-flow-dock > .dc-app-overlay[data-window^="${name}"]`).waitFor({ timeout: 20_000 });
/** The box that is not one of `known`: the one just added. */
async function added(known) {
  const now = await boxes();
  const fresh = now.find((b) => !known.includes(b.id));
  if (!fresh) throw new Error(`no box added: ${JSON.stringify(now)}`);
  return fresh;
}

try {
  await page.goto(`http://127.0.0.1:${port}/demo/index.html`, { waitUntil: 'load', timeout: 120_000 });
  await page.waitForFunction(() => document.querySelectorAll('.dc-row').length > 0, undefined, { timeout: 120_000 });

  // CREATE ▸ FLOW: a sheet of its own, its palette and an empty canvas
  await burger('Create', 'Flow');
  await page.locator('.dc-flow-palette').waitFor({ timeout: 20_000 });
  const tabs = await page.locator('.dc-sheet-tab .dc-sheet-label').allTextContents();
  check(tabs.at(-1) === 'Flow 2', `a flow sheet, called a flow: ${tabs}`);
  check(await page.locator('.dc-flow-hint').isVisible(), 'an empty canvas says how to start');

  // AN INPUT, dragged onto the canvas: the source picker, an example of 5,000 rows
  await drag('input', 'canvas');
  await page.locator('.dc-picker').waitFor({ timeout: 10_000 });
  await page.click('.dc-picker-nav button:has-text("Examples")');
  await page.locator('.dc-picker-card').first().click();
  await page.fill('.dc-picker-rows', '5000');
  await page.click('.dc-picker-choice-actions button:has-text("Open")');
  await boxCount(1);
  await settled();
  const input = (await boxes())[0];
  check(input.tool === 'input' && input.shown, `an Input box, shown: ${JSON.stringify(input)}`);
  const all = await resultRows();
  check(all === 5000, `its rows in the results pane: ${all}`);

  // A FILTER, dragged onto the Input: a box after it, its Filter window docked, a condition set there
  await drag('filter', input.id);
  await boxCount(2);
  const filter = await added([input.id]);
  check(filter.tool === 'filter' && filter.says === 'not set yet', `a Filter box, to be set: ${JSON.stringify(filter)}`);
  await docked('Filters');
  await page.locator('.dc-flow-dock button', { hasText: 'Create New Filter' }).click();
  await page.locator('.dc-flow-dock .dc-filter-column').first().selectOption('notional');
  await page.locator('.dc-flow-dock .dc-filter-op').first().selectOption('greaterThan');
  const n = page.locator('.dc-flow-dock .dc-filter-number').first();
  await n.fill('500000');
  await n.press('Enter');
  await page.locator('.dc-flow-dock button', { hasText: /^OK$/ }).click();
  await settled();
  const filtered = await resultRows();
  const set = (await boxes()).find((b) => b.id === filter.id);
  check(/notional .* 500000/.test(set?.says ?? ''), `the Filter says what it keeps: ${set?.says}`);
  check(filtered > 0 && filtered < all, `its rows, the Input's filtered: ${filtered} of ${all}`);
  check(await lineCount() === 1, 'a line from the Input into the Filter');

  // A FORMULA, dragged onto the Filter: a calculated column, in its editor docked
  await drag('formula', filter.id);
  await boxCount(3);
  const formula = await added([input.id, filter.id]);
  await docked('column:');
  await page.fill('.dc-flow-dock .dc-calc-input-name', 'fee');
  await page.fill('.dc-flow-dock .dc-calc-input-expr', '$x.notional * 0.01');
  // a box's columns are its input's relation's, which may be empty: the editor asks what an empty one gives
  const empty = page.locator('.dc-flow-dock .dc-calc-fix-button[data-as="zero"]');
  await empty.waitFor({ timeout: 30_000 }).then(() => empty.click(), () => undefined);
  await page.waitForFunction(() => {
    const ok = document.querySelector('.dc-flow-dock .dc-calc-ok');
    return ok instanceof HTMLButtonElement && !ok.disabled;
  }, undefined, { timeout: 60_000 });
  await page.locator('.dc-flow-dock .dc-calc-ok').click();
  await settled();
  const formulaRows = await resultRows();
  const calc = (await boxes()).find((b) => b.id === formula.id);
  check(calc?.tool === 'formula' && calc.says === 'fee', `the Formula says its column: ${JSON.stringify(calc)}`);
  check(formulaRows === filtered, `a calculated column keeps its input's rows: ${formulaRows}`);
  check(await page.locator('.dc-flow-results .dc-th[data-column="fee"]').count() > 0, 'its column in its rows');

  // A SUMMARIZE, dragged onto the Formula: Properties docked at Row Groups, region grouped
  await drag('summarize', formula.id);
  await boxCount(4);
  const summary = await added([input.id, filter.id, formula.id]);
  await docked('Properties');
  const available = page.locator('.dc-flow-dock .dc-selector-box').first();
  await available.locator('.dc-selector-row', { hasText: /^region$/ }).first().click();
  await page.locator('.dc-flow-dock .dc-selector-move').first().click();
  await page.locator('.dc-flow-dock button', { hasText: /^OK$/ }).click();
  await settled();
  const groups = await resultRows();
  const summed = (await boxes()).find((b) => b.id === summary.id);
  check(summed?.tool === 'summarize' && summed.says === 'by region', `the Summarize says its groups: ${JSON.stringify(summed)}`);
  check(groups > 0 && groups < 10, `a row per region: ${groups}`);
  check(await lineCount() === 3, 'a line into each box after the first');

  // EACH BOX PREVIEWED, the one shown before closed: only the shown box has a grid
  const previews = {};
  for (const b of [input, filter, formula, summary]) {
    await page.locator(`.dc-flow-box[data-tile="${b.id}"]`).click();
    await page.waitForFunction((id) => document.querySelector(`.dc-flow-box[data-tile="${id}"]`)?.classList.contains('dc-flow-shown'), b.id);
    await settled();
    previews[b.id] = await resultRows();
  }
  const want = { [input.id]: all, [filter.id]: filtered, [formula.id]: formulaRows, [summary.id]: groups };
  check(JSON.stringify(previews) === JSON.stringify(want), `each box's rows, shown again: ${JSON.stringify(previews)}`);
  const open = await page.evaluate(() => window.__dataPage.grids);
  check(open.includes(summary.id) && ![input.id, filter.id, formula.id].some((id) => open.includes(id)),
    `one box's grid made, the shown one's: ${open}`);

  // A LINE DRAWN by hand: from the Input's output onto the Formula, which reads the Input from then on -- one step, undone
  // (the canvas scrolled back to its start: a box added was scrolled into view, the Input's port under the palette)
  await page.locator('.dc-flow-canvas').evaluate((c) => { c.scrollLeft = 0; c.scrollTop = 0; });
  const outPort = await page.locator(`.dc-flow-box[data-tile="${input.id}"] .dc-flow-port-out:not(.dc-flow-port-false)`).boundingBox();
  const onto = await page.locator(`.dc-flow-box[data-tile="${formula.id}"]`).boundingBox();
  await page.mouse.move(outPort.x + outPort.width / 2, outPort.y + outPort.height / 2);
  await page.mouse.down();
  await page.mouse.move(onto.x + onto.width / 2, onto.y + onto.height / 2, { steps: 12 });
  await page.mouse.up();
  check(await readsOf(formula.id) === input.id, `the Formula reads the Input now: ${await readsOf(formula.id)}`);
  check(await page.evaluate(() => window.__dataPage.undoLabel) === 'Link Box', 'one step: Link Box');
  check(await show(formula.id) === all, 'its rows the Input\'s, every one');
  check(await lineCount() === 3, 'still a line into each box after the first');
  await page.locator('.dc-flow-canvas').click({ position: { x: 5, y: 5 } });
  await page.keyboard.press('Control+z');
  check(await readsOf(formula.id) === filter.id, `undone: the Formula reads the Filter again: ${await readsOf(formula.id)}`);
  await settled();
  check(await resultRows() === filtered, 'its rows the Filter\'s again');

  // A LINE TAKEN OFF: clicked, then Delete -- the Formula reads a copy of what the Filter was (frozen), its rows the same
  const middle = await lineMiddle(formula.id);
  await page.mouse.click(middle.x, middle.y);
  // a level line's box has no height: attached, not "visible"
  await page.locator('.dc-flow-lines path.dc-flow-line-selected').waitFor({ state: 'attached', timeout: 5_000 });
  check(await page.locator('.dc-flow-unlink').isVisible(), 'a selected line shows its ×');
  await page.screenshot({ path: outPath('flow-line-selected.png') });
  await page.keyboard.press('Delete');
  check(await readsOf(formula.id) === 'copy', `taken off: the Formula reads a copy: ${await readsOf(formula.id)}`);
  const left = await lineCount();
  check(left === 2, `its line gone: ${left} lines`);
  check(await page.locator(`.dc-flow-box[data-tile="${formula.id}"].dc-flow-frozen`).count() === 1, 'the Formula drawn frozen');
  await page.screenshot({ path: outPath('flow-frozen.png') });
  await settled();
  check(await resultRows() === filtered, `frozen, its rows as they were: ${filtered}`);
  // linked again from its settings: Input, the Filter
  await page.locator('.dc-flow-inputs select').selectOption(`grid:${filter.id}`);
  check(await readsOf(formula.id) === filter.id, 'linked again from its settings: it reads the Filter');
  const back3 = await lineCount();
  check(back3 === 3, `its line back: ${back3} lines`);
  await settled();

  // A TOOL DROPPED ON A LINE: a Sort between the Filter and the Formula -- the Formula reads the Sort; one step, undone
  const on = await lineMiddle(formula.id);
  const sortTool = await page.locator('.dc-flow-tool[data-tool="sort"]').boundingBox();
  await page.mouse.move(sortTool.x + sortTool.width / 2, sortTool.y + sortTool.height / 2);
  await page.mouse.down();
  await page.mouse.move(on.x, on.y, { steps: 12 });
  check(await page.locator('.dc-flow-lines path.dc-flow-line-target').count() === 1, 'the line under the tool marked');
  await page.screenshot({ path: outPath('flow-insert.png') });
  await page.mouse.up();
  await boxCount(5);
  const sorted = await added([input.id, filter.id, formula.id, summary.id]);
  check(sorted.tool === 'sort', `a Sort box: ${JSON.stringify(sorted)}`);
  check(await readsOf(sorted.id) === filter.id && await readsOf(formula.id) === sorted.id,
    `between them: the Sort reads the Filter, the Formula the Sort: ${await readsOf(sorted.id)}, ${await readsOf(formula.id)}`);
  check(await page.evaluate(() => window.__dataPage.undoLabel) === 'Insert Box', 'one step: Insert Box');
  check(await lineCount() === 4, 'a line into each box after the first, the Sort\'s too');
  await settled();
  await page.locator('.dc-flow-canvas').click({ position: { x: 5, y: 5 } });
  await page.keyboard.press('Control+z');
  await boxCount(4);
  check(await readsOf(formula.id) === filter.id, `undone in one step: the Sort gone, the Formula reads the Filter: ${await readsOf(formula.id)}`);
  await settled();

  // A BOX MOVED BY HAND, and the move undone
  const placeOf = () => page.evaluate((id) => {
    const flow = window.__dataPage.document('x').sheets.find((s) => s.layout.kind === 'flow');
    return flow.layout.boxes.find((b) => b.tile === id);
  }, summary.id);
  const was = await placeOf();
  await page.locator(`.dc-flow-box[data-tile="${summary.id}"]`).scrollIntoViewIfNeeded();
  const card = await page.locator(`.dc-flow-box[data-tile="${summary.id}"]`).boundingBox();
  await page.mouse.move(card.x + 30, card.y + 20);
  await page.mouse.down();
  await page.mouse.move(card.x + 30, card.y + 200, { steps: 10 });
  await page.mouse.up();
  const moved = await placeOf();
  check(moved.y > was.y && moved.x === was.x, `moved down by hand: (${was.x}, ${was.y}) to (${moved.x}, ${moved.y})`);
  check(await page.evaluate(() => window.__dataPage.undoLabel) === 'Move Box', 'one step: Move Box');
  await page.locator('.dc-flow-canvas').click({ position: { x: 5, y: 5 } });
  await page.keyboard.press('Control+z');
  const back = await placeOf();
  check(back.x === was.x && back.y === was.y, `undone: back at (${back.x}, ${back.y})`);

  // SAVED, RELOADED, OPENED AGAIN: the flow as it was, no box opened until it is shown
  await burger('Save As…');
  await page.locator('.dc-save').waitFor({ timeout: 10_000 });
  await page.fill('.dc-save-input', 'My flow');
  await page.locator('.dc-save-foot .dc-primary').click();
  await page.locator('.dc-save').waitFor({ state: 'detached', timeout: 30_000 });
  await page.goto(`http://127.0.0.1:${port}/demo/index.html`, { waitUntil: 'load', timeout: 120_000 });
  await page.waitForFunction(() => document.querySelectorAll('.dc-row').length > 0, undefined, { timeout: 120_000 });
  await burger('Open…');
  const row = page.locator('#cubelib .dc-lib-row', { hasText: 'My flow' });
  await row.waitFor({ timeout: 10_000 });
  await row.locator('.dc-lib-button', { hasText: 'Open' }).click();
  await page.waitForFunction(() => /opened/.test(document.querySelector('#cubelib .dc-lib-message')?.textContent ?? ''), undefined, { timeout: 60_000 });
  await page.locator('#cubelib .dc-lib-close, #cubelib [aria-label="Close"]').first().click().catch(() => undefined);
  await page.locator('.dc-sheet-tab', { hasText: 'Flow 2' }).click();
  await boxCount(4);
  const reopened = await boxes();
  check(JSON.stringify(reopened.map((b) => [b.tool, b.says])) === JSON.stringify([['input', input.says], ['filter', set.says], ['formula', 'fee'], ['summarize', 'by region']]),
    `reopened, each box as it was: ${JSON.stringify(reopened.map((b) => [b.tool, b.says]))}`);
  const openNow = await page.evaluate(() => window.__dataPage.grids);
  check(!reopened.some((b) => openNow.includes(b.id)), `no box opened as the page opened: ${openNow}`);
  await page.locator(`.dc-flow-box[data-tile="${reopened[3].id}"]`).click();
  await settled();
  check(await resultRows() === groups, `the last box's rows, as before: ${groups}`);

  // SHARED: the link opens the same flow in a fresh tab
  await burger('Share…');
  await page.waitForFunction(() => /^Copied/.test(document.querySelector('#sharenote')?.textContent ?? ''), null, { timeout: 10_000 });
  const url = await page.evaluate(() => navigator.clipboard.readText());
  await page.click('#sharewin .hostwin-close');
  const tab = await context.newPage();
  tab.on('pageerror', (e) => errors.push(`(shared tab) pageerror: ${e.message}`));
  try {
    await tab.goto(url);
    await tab.waitForFunction(() => /opened/.test(document.querySelector('#cubelib .dc-lib-message')?.textContent ?? '')
      || document.querySelectorAll('.dc-sheet-tab').length > 1, undefined, { timeout: 120_000 });
    await tab.locator('.dc-sheet-tab', { hasText: 'Flow 2' }).click();
    await boxCount(4, tab);
    const shared = await boxes(tab);
    check(JSON.stringify(shared.map((b) => b.says)) === JSON.stringify(reopened.map((b) => b.says)), `shared, the same boxes: ${shared.map((b) => b.says)}`);
    await tab.locator(`.dc-flow-box[data-tile="${shared[3].id}"]`).click();
    await settled(tab);
    check(await resultRows(tab) === groups, 'the shared flow\'s last box, the same rows');
  } finally {
    await tab.close();
  }

  // A FILE FROM THE DESKTOP, dropped onto the canvas: an Input over it, its rows shown
  const csv = 'region,target\nEMEA,10\nAMER,20\nAPAC,30\nLATAM,40\n';
  const dt = await page.evaluateHandle((text) => {
    const d = new DataTransfer();
    d.items.add(new File([text], 'targets.csv', { type: 'text/csv' }));
    return d;
  }, csv);
  await page.dispatchEvent('.dc-flow-canvas', 'dragover', { dataTransfer: dt });
  await page.dispatchEvent('.dc-flow-canvas', 'drop', { dataTransfer: dt });
  await boxCount(5);
  await settled();
  const dropped = (await boxes()).at(-1);
  check(dropped?.tool === 'input' && dropped.says === 'targets.csv' && dropped.shown, `a file dropped, an Input over it: ${JSON.stringify(dropped)}`);
  check(await resultRows() === 4, 'its rows in the results pane');

  // A JOIN BOX, dragged onto the Filter: the Join window over it (its Left) and the Input (its Right), matching trades
  const joinWindow = page.locator('.dc-join');
  const known = (await boxes()).map((b) => b.id);
  await drag('join', filter.id);
  await joinWindow.waitFor({ timeout: 20_000 });
  check(await joinWindow.locator('.dc-picker-subtitle').textContent().then((t) => /another box of the flow/.test(t ?? '')), 'the Join window, over the flow\'s boxes');
  await joinWindow.locator('.dc-join-their').selectOption(input.id);
  await joinWindow.locator('.dc-join-kind[data-kind="inner"]').click();
  await page.waitForFunction(() => document.querySelectorAll('.dc-join .dc-join-left option').length > 1, undefined, { timeout: 60_000 });
  await joinWindow.locator('.dc-join-left').first().selectOption('trade_id');
  await joinWindow.locator('.dc-join-right').first().selectOption('trade_id');
  const addJoin = joinWindow.locator('.dc-save-foot .dc-primary');
  await page.waitForFunction(() => { const b = document.querySelector('.dc-join .dc-save-foot .dc-primary'); return b instanceof HTMLButtonElement && !b.disabled; }, undefined, { timeout: 60_000 });
  check(await addJoin.textContent() === 'Add Join', 'its button says what it does: Add Join');
  await addJoin.click();
  await joinWindow.waitFor({ state: 'detached', timeout: 60_000 });
  await boxCount(known.length + 1);
  const join = await added(known);
  check(join.tool === 'join' && join.shown, `a Join box, shown: ${JSON.stringify(join)}`);
  check(JSON.stringify(await readsOf(join.id)) === JSON.stringify([filter.id, input.id]), `it reads the Filter and the Input, live: ${JSON.stringify(await readsOf(join.id))}`);
  await settled();
  check(await resultRows() === filtered, `each of the Filter's trades matched once: ${filtered}`);
  check(await page.locator(`.dc-flow-lines path.dc-flow-hit[data-tile="${join.id}"]`).count() === 2, 'two lines into it, its Left and its Right');
  await page.screenshot({ path: outPath('flow-join.png') });

  // AN EDIT UPSTREAM REACHES IT: the Filter's threshold raised, the Join's rows fewer
  await show(filter.id);
  await page.locator(`.dc-flow-box[data-tile="${filter.id}"]`).click();
  await docked('Filters').catch(() => undefined);
  if (await page.locator('.dc-flow-dock .dc-filter-number').count() === 0) {
    await page.locator('.dc-flow-settings button', { hasText: 'Open its settings' }).click();
    await docked('Filters');
  }
  const threshold = page.locator('.dc-flow-dock .dc-filter-number').first();
  await threshold.fill('900000');
  await threshold.press('Enter');
  await page.locator('.dc-flow-dock button', { hasText: /^OK$/ }).click();
  await settled();
  const fewer = await resultRows();
  check(fewer < filtered, `the Filter keeps fewer: ${fewer}`);
  check(await show(join.id) === fewer, `the Join's rows follow it: ${fewer}`);

  // SET AGAIN from its settings: Mine with a match, one step, undone
  await page.locator('.dc-flow-settings button', { hasText: 'Open its settings' }).click();
  await joinWindow.waitFor({ timeout: 20_000 });
  check(await joinWindow.locator('.dc-join-kind[data-kind="inner"]').getAttribute('aria-checked') === 'true', 'it opens at its own join');
  await joinWindow.locator('.dc-join-kind[data-kind="semi"]').click();
  await page.waitForFunction(() => { const b = document.querySelector('.dc-join .dc-save-foot .dc-primary'); return b instanceof HTMLButtonElement && !b.disabled; }, undefined, { timeout: 60_000 });
  check(await joinWindow.locator('.dc-save-foot .dc-primary').textContent() === 'Apply', 'set again: Apply');
  await joinWindow.locator('.dc-save-foot .dc-primary').click();
  await joinWindow.waitFor({ state: 'detached', timeout: 60_000 });
  check(await page.evaluate(() => window.__dataPage.undoLabel) === 'Set Join', 'one step: Set Join');
  const semi = (await boxes()).find((b) => b.id === join.id);
  check(/with a match/.test(semi?.says ?? ''), `it says its join: ${semi?.says}`);
  await settled();
  check(await resultRows() === fewer, 'its rows: the Filter\'s with a match');
  await page.locator('.dc-flow-canvas').click({ position: { x: 5, y: 5 } });
  await page.keyboard.press('Control+z');
  const inner = (await boxes()).find((b) => b.id === join.id);
  check(/matching rows only/.test(inner?.says ?? ''), `undone: matching rows only again: ${inner?.says}`);

  // A UNION BOX, dragged onto the Filter: the window at Append, the Input under the Filter's rows
  const before = (await boxes()).map((b) => b.id);
  await drag('union', filter.id);
  await joinWindow.waitFor({ timeout: 20_000 });
  check(await joinWindow.locator('.dc-join-kind[data-kind="append"]').getAttribute('aria-checked') === 'true', 'a Union\'s window starts at Append');
  await joinWindow.locator('.dc-join-their').selectOption(input.id);
  await page.waitForFunction(() => { const b = document.querySelector('.dc-join .dc-save-foot .dc-primary'); return b instanceof HTMLButtonElement && !b.disabled; }, undefined, { timeout: 60_000 });
  await joinWindow.locator('.dc-save-foot .dc-primary').click();
  await joinWindow.waitFor({ state: 'detached', timeout: 60_000 });
  await boxCount(before.length + 1);
  const union = await added(before);
  check(union.tool === 'union', `a Union box: ${JSON.stringify(union)}`);
  await settled();
  check(await resultRows() === fewer + all, `the rows of both: ${fewer} + ${all}`);
  await page.screenshot({ path: outPath('flow-union.png') });

  // A RECORD ID (M3), dragged onto the Filter: the column editor docked at a row number, ordered by trade_id here
  const beforeId = (await boxes()).map((b) => b.id);
  await drag('recordId', filter.id);
  await boxCount(beforeId.length + 1);
  const numbered = await added(beforeId);
  check(numbered.tool === 'recordId', `a Record ID box: ${JSON.stringify(numbered)}`);
  await docked('column:');
  check(await page.locator('.dc-flow-dock .dc-calc-input-name').inputValue() === 'RecordID', 'its column named RecordID');
  check(await page.locator('.dc-flow-dock .dc-win-fn').inputValue() === 'rowNumber', 'a row number, to start');
  await page.locator('.dc-flow-dock .dc-win-order-add').click();
  await page.locator('.dc-flow-dock .dc-win-order-column').first().selectOption('trade_id');
  await page.waitForFunction(() => { const ok = document.querySelector('.dc-flow-dock .dc-calc-ok'); return ok instanceof HTMLButtonElement && !ok.disabled; }, undefined, { timeout: 60_000 });
  await page.locator('.dc-flow-dock .dc-calc-ok').click();
  await settled();
  const set1 = (await boxes()).find((b) => b.id === numbered.id);
  check(set1?.tool === 'recordId' && set1.says === 'RecordID', `it says its column: ${JSON.stringify(set1)}`);
  check(await resultRows() === fewer, 'its input\'s rows, each numbered');
  check(await page.locator('.dc-flow-results .dc-th[data-column="RecordID"]').count() > 0, 'its column in its rows');

  // A CROSS TAB, dragged onto the Filter: Properties docked at its column pivots
  const beforeTab = (await boxes()).map((b) => b.id);
  await drag('crosstab', filter.id);
  await boxCount(beforeTab.length + 1);
  const crossTab = await added(beforeTab);
  check(crossTab.tool === 'crosstab' && crossTab.says === 'not set yet', `a Cross Tab box, to be set: ${JSON.stringify(crossTab)}`);
  await docked('Properties');
  check(await page.locator('.dc-flow-dock [role="tab"][aria-selected="true"]').getAttribute('data-tab') === 'Horizontal Pivots',
    'Properties at its column labels (its column pivots)');
  await page.screenshot({ path: outPath('flow-tools.png') });

  // A FILTER'S FALSE (FL7): a line from its F port let go on the canvas -- a Browse of the rows its condition leaves out
  await page.locator('.dc-flow-canvas').evaluate((c) => { c.scrollLeft = 0; c.scrollTop = 0; });
  const beforeFalse = (await boxes()).map((b) => b.id);
  await page.locator(`.dc-flow-box[data-tile="${filter.id}"]`).scrollIntoViewIfNeeded();
  const fPort = await page.locator(`.dc-flow-box[data-tile="${filter.id}"] .dc-flow-port-false`).boundingBox();
  const freeSpot = await emptyCanvasPoint();
  await page.mouse.move(fPort.x + fPort.width / 2, fPort.y + fPort.height / 2);
  await page.mouse.down();
  await page.mouse.move(freeSpot.x, freeSpot.y, { steps: 12 });
  await page.mouse.up();
  await boxCount(beforeFalse.length + 1);
  const leftOut = await added(beforeFalse);
  check(leftOut.tool === 'browse' && await readsOf(leftOut.id) === `${filter.id}:false`, `a Browse of the Filter's False: ${JSON.stringify(leftOut)}, ${await readsOf(leftOut.id)}`);
  await settled();
  check(await resultRows() === all - fewer, `the rows it leaves out: ${all} - ${fewer}`);
  const leftAt = await page.evaluate((id) => window.__dataPage.document('x').sheets.find((s) => s.layout.kind === 'flow').layout.boxes.find((b) => b.tile === id), leftOut.id);
  check(Math.abs(leftAt.x + 92 - freeSpot.canvasX) <= 2, `made where its line was let go: (${leftAt.x}, ${leftAt.y}), its middle at ${freeSpot.canvasX}`);
  await page.screenshot({ path: outPath('flow-false.png') });

  // TIDY UP (M7): a box pushed out of place, then every box a column after the boxes it reads -- one step; then a box's
  // menu, Duplicate
  const pushed = page.locator(`.dc-flow-box[data-tile="${formula.id}"]`);
  await pushed.focus();
  for (let i = 0; i < 6; i += 1) await pushed.press('Shift+ArrowDown');
  await page.locator('.dc-flow-tidy').click();
  check(await page.evaluate(() => window.__dataPage.undoLabel) === 'Tidy Up', 'one step: Tidy Up');
  const tidied = await page.evaluate(() => window.__dataPage.document('x').sheets.find((s) => s.layout.kind === 'flow').layout.boxes);
  const xOf = (id) => tidied.find((b) => b.tile === id).x;
  check(xOf(input.id) < xOf(filter.id) && xOf(filter.id) < xOf(join.id) && xOf(filter.id) < xOf(formula.id),
    `each box after the boxes it reads: ${JSON.stringify(tidied.map((b) => [b.tile, b.x, b.y]))}`);
  await page.screenshot({ path: outPath('flow-tidied.png') });
  const beforeCopy = (await boxes()).map((b) => b.id);
  await page.locator(`.dc-flow-box[data-tile="${formula.id}"]`).click({ button: 'right' });
  const duplicate = page.locator('.dc-menu .dc-menu-item', { hasText: 'Duplicate' }).first();
  await duplicate.waitFor({ timeout: 5_000 });
  check(await page.locator('.dc-menu .dc-menu-item', { hasText: 'Freeze its Input' }).count() === 1, 'its menu offers to freeze its input');
  await duplicate.click();
  await boxCount(beforeCopy.length + 1);
  const copy = await added(beforeCopy);
  check(copy.tool === 'formula' && copy.says === 'fee', `a copy of the Formula: ${JSON.stringify(copy)}`);
  check(await readsOf(copy.id) === filter.id, 'reading what it reads');
  check(await page.evaluate(() => window.__dataPage.undoLabel) === 'Duplicate Box', 'one step: Duplicate Box');

  // RUN (M5, M6): each box shown in turn, its rows counted beside its output; an edit upstream drops the counts after it
  await page.locator('.dc-flow-run').click();
  await page.waitForFunction(() => /Run$/.test(document.querySelector('.dc-flow-run')?.textContent ?? ''), undefined, { timeout: 120_000 });
  const counts = await page.locator('.dc-flow-box').evaluateAll((els) => Object.fromEntries(els.map((e) => [e.dataset.tile, e.querySelector('.dc-flow-count')?.textContent ?? ''])));
  check(counts[input.id] === '5,000' && counts[filter.id] === fewer.toLocaleString() && counts[union.id] === (fewer + all).toLocaleString(),
    `each box counted: ${JSON.stringify(counts)}`);
  // its log (M6): every box, its rows and its time
  const logged = await page.locator('.dc-flow-log .dc-flow-log-item').count();
  check(await page.locator('.dc-flow-log').isVisible() && logged === Object.keys(counts).length,
    `the run's log, a line a box: ${logged} of ${Object.keys(counts).length}`);
  check(/^Ran \d+ boxes in /.test(await page.locator('.dc-flow-log-title').textContent() ?? ''), 'it says what it ran, and how long it took');
  await page.screenshot({ path: outPath('flow-run.png') });
  await page.locator('.dc-flow-log-close').click();
  check(!(await page.locator('.dc-flow-log').isVisible()), 'closed by its ×');

  // A SAMPLE (M3), dragged onto the Filter: the first five rows by trade_id, in its window docked
  const beforeSample = (await boxes()).map((b) => b.id);
  await drag('sample', filter.id);
  await boxCount(beforeSample.length + 1);
  const sample = await added(beforeSample);
  await docked('Sample');
  await page.locator('.dc-flow-dock .dc-sample-n').fill('5');
  await page.locator('.dc-flow-dock .dc-sample-by').selectOption('trade_id');
  await page.locator('.dc-flow-dock .dc-sample-foot .dc-primary').click();
  await settled();
  const sampled = (await boxes()).find((b) => b.id === sample.id);
  check(sampled?.tool === 'sample' && sampled.says === 'first 5 by trade_id', `a Sample box: ${JSON.stringify(sampled)}`);
  check(await resultRows() === 5, 'its first five rows');

  // A DATETIME (M3), dragged onto the Filter: its trade dates taken to their month, in its window docked
  const beforeDate = (await boxes()).map((b) => b.id);
  await drag('datetime', filter.id);
  await boxCount(beforeDate.length + 1);
  const dated = await added(beforeDate);
  await docked('Date Period');
  check(await page.locator('.dc-flow-dock .dc-date-column option').count() > 0, 'its window offers the date columns');
  await page.locator('.dc-flow-dock .dc-date-column').selectOption('trade_date');
  await page.locator('.dc-flow-dock .dc-date-grain').selectOption('month');
  await page.locator('.dc-flow-dock .dc-sample-foot .dc-primary').click();
  await settled();
  const monthly = (await boxes()).find((b) => b.id === dated.id);
  check(monthly?.tool === 'datetime' && monthly.says === 'trade_date by month', `a DateTime box: ${JSON.stringify(monthly)}`);
  check(await page.locator('.dc-flow-results .dc-th[data-column="trade_date_month"]').count() > 0, 'its column in its rows: trade_date_month');
  check(await resultRows() === fewer, 'its input\'s rows, each kept');

  // THE TEXT TOOLS (M3), each dragged onto the Filter, set in its window docked, its columns planned and run
  const textStep = async (tool, window, set, expect) => {
    const before = (await boxes()).map((b) => b.id);
    await drag(tool, filter.id);
    await boxCount(before.length + 1);
    const made = await added(before);
    await docked(window);
    await set(page.locator('.dc-flow-dock'));
    await page.locator('.dc-flow-dock .dc-sample-foot .dc-primary').click();
    await settled();
    const box = (await boxes()).find((b) => b.id === made.id);
    const refused = await page.locator('.dc-flow-dock .dc-sample-status').textContent().catch(() => '');
    check(box?.tool === tool && box.says === expect.says, `a ${window} box: ${JSON.stringify(box)}${refused ? `, refused: ${refused}` : ''}`);
    for (const c of expect.columns) {
      check(await page.locator(`.dc-flow-results .dc-th[data-column="${c}"]`).count() > 0, `its column ${c} in its rows`);
    }
    check(await resultRows() === fewer, `${window}: its input's rows, each kept`);
  };
  await textStep('textToColumns', 'Text To Columns', async (w) => {
    await w.locator('.dc-text-tool-column').selectOption('book');
    await w.locator('.dc-text-tool-delimiter').fill(' ');
  }, { says: "book split on ' '", columns: ['book_1', 'book_2'] });
  await textStep('regex', 'RegEx', async (w) => {
    await w.locator('.dc-text-tool-column').selectOption('desk');
    await w.locator('.dc-text-tool-pattern').fill('^[A-Z][a-z]+');
    await w.locator('.dc-text-tool-mode').selectOption('extract');
  }, { says: '/^[A-Z][a-z]+/ of desk', columns: ['desk_extracted'] });
  await textStep('cleanse', 'Data Cleansing', async (w) => {
    await w.locator('.dc-text-tool-check', { hasText: 'region' }).locator('input').check();
    await w.locator('.dc-text-tool-case').selectOption('lower');
  }, { says: 'region cleansed', columns: ['region_clean'] });

  // UNIQUE (M3), dragged onto the Input: a row of each region, its U output; a Browse off its D, the duplicates
  const beforeUnique = (await boxes()).map((b) => b.id);
  await drag('unique', input.id);
  await boxCount(beforeUnique.length + 1);
  const uniq = await added(beforeUnique);
  await docked('Unique');
  await page.locator('.dc-flow-dock .dc-unique input[value="region"]').check();
  await page.locator('.dc-flow-dock .dc-unique-by').selectOption('notional');
  await page.locator('.dc-flow-dock .dc-unique-direction').selectOption('desc');
  await page.locator('.dc-flow-dock .dc-sample-foot .dc-primary').click();
  await settled();
  const uniqueBox = (await boxes()).find((b) => b.id === uniq.id);
  check(uniqueBox?.tool === 'unique' && uniqueBox.says === 'by region', `a Unique box: ${JSON.stringify(uniqueBox)}`);
  check(await resultRows() === 3, 'a row of each region: 3');
  check(await page.locator('.dc-flow-results .dc-th[data-column="UniqueRow"]').count() === 0, 'its row number hidden');
  const ports = await page.locator(`.dc-flow-box[data-tile="${uniq.id}"] .dc-flow-port-out`).allTextContents();
  check(JSON.stringify(ports) === '["U","D"]', `its outputs U and D: ${ports}`);
  await page.locator('.dc-flow-canvas').evaluate((c) => { c.scrollLeft = 0; c.scrollTop = 0; });
  const beforeDupes = (await boxes()).map((b) => b.id);
  await page.locator(`.dc-flow-box[data-tile="${uniq.id}"]`).scrollIntoViewIfNeeded();
  const dPort = await page.locator(`.dc-flow-box[data-tile="${uniq.id}"] .dc-flow-port-false`).boundingBox();
  const dupeSpot = await emptyCanvasPoint();
  await page.mouse.move(dPort.x + dPort.width / 2, dPort.y + dPort.height / 2);
  await page.mouse.down();
  await page.mouse.move(dupeSpot.x, dupeSpot.y, { steps: 12 });
  await page.mouse.up();
  await boxCount(beforeDupes.length + 1);
  const dupes = await added(beforeDupes);
  check(await readsOf(dupes.id) === `${uniq.id}:false`, `a Browse of its Duplicates: ${await readsOf(dupes.id)}`);
  await settled();
  check(await resultRows() === all - 3, `its duplicates, every row after a region's first: ${all} - 3`);

  // ZOOM (M7): a level up, a box dragged as far across the window moves less on the canvas; back to its own size
  await page.locator('.dc-flow-zoom', { hasText: '+' }).click();
  check(await page.locator('.dc-flow-zoom-level').textContent() === '110%', 'zoomed in a level: 110%');
  await page.locator(`.dc-flow-box[data-tile="${input.id}"]`).scrollIntoViewIfNeeded();
  const zoomedBox = await page.locator(`.dc-flow-box[data-tile="${input.id}"]`).boundingBox();
  check(Math.abs(zoomedBox.width - 184 * 1.1) < 2, `a box drawn at its zoom: ${zoomedBox.width}px`);
  const beforeZoomMove = await page.evaluate((id) => window.__dataPage.document('x').sheets.find((s) => s.layout.kind === 'flow').layout.boxes.find((b) => b.tile === id), input.id);
  await page.mouse.move(zoomedBox.x + 30, zoomedBox.y + 20);
  await page.mouse.down();
  await page.mouse.move(zoomedBox.x + 30, zoomedBox.y + 20 + 110, { steps: 8 });
  await page.mouse.up();
  const afterZoomMove = await page.evaluate((id) => window.__dataPage.document('x').sheets.find((s) => s.layout.kind === 'flow').layout.boxes.find((b) => b.tile === id), input.id);
  check(Math.abs(afterZoomMove.y - beforeZoomMove.y - 100) <= 1, `moved 110px of the window, 100 of the canvas: ${beforeZoomMove.y} to ${afterZoomMove.y}`);
  await page.locator('.dc-flow-zoom-level').click();
  check(await page.locator('.dc-flow-zoom-level').textContent() === '100%', 'back to its own size');

  // A COMMENT (M7): the palette's Comment dropped on the canvas, its words typed in place -- a note where it was let go
  const notes = () => page.locator('.dc-flow-note').count();
  const spot = await emptyCanvasPoint();
  await page.locator('.dc-flow-tool[data-tool="comment"]').scrollIntoViewIfNeeded();
  const commentTool = await page.locator('.dc-flow-tool[data-tool="comment"]').boundingBox();
  await page.mouse.move(commentTool.x + commentTool.width / 2, commentTool.y + commentTool.height / 2);
  await page.mouse.down();
  await page.mouse.move(spot.x, spot.y, { steps: 12 });
  await page.mouse.up();
  await page.locator('.dc-flow-note textarea').waitFor({ timeout: 10_000 });
  check(await notes() === 1, 'a note on the canvas, its editor open');
  check(await page.evaluate(() => window.__dataPage.undoLabel) === 'Add Comment', 'one step: Add Comment');
  await page.locator('.dc-flow-note textarea').fill('Big trades only: notional over 900,000');
  await page.locator('.dc-flow-note .dc-text-bar button', { hasText: 'OK' }).click();
  await page.waitForFunction(() => /Big trades only/.test(document.querySelector('.dc-flow-note-body')?.textContent ?? ''), undefined, { timeout: 10_000 });
  const noteId = await page.locator('.dc-flow-note').getAttribute('data-tile');
  const written = await page.evaluate((id) => window.__dataPage.document('x').views.find((v) => v.id === id), noteId);
  check(written?.kind === 'text' && /Big trades only/.test(written.text), `a text tile of the page: ${JSON.stringify(written)}`);
  await page.screenshot({ path: outPath('flow-comment.png') });

  // COPY AND PASTE (M7): the Formula copied, pasted where the pointer is -- a box of its cube, reading what it reads
  const beforePaste = (await boxes()).map((b) => b.id);
  await page.locator(`.dc-flow-box[data-tile="${formula.id}"]`).focus();
  await page.keyboard.press('Control+c');
  const pasteAt = await emptyCanvasPoint();
  await page.mouse.move(pasteAt.x, pasteAt.y);
  await page.locator('.dc-flow-canvas').focus();
  await page.keyboard.press('Control+v');
  await boxCount(beforePaste.length + 1);
  const pasted = await added(beforePaste);
  check(pasted.tool === 'formula' && pasted.says === 'fee', `a copy of the Formula: ${JSON.stringify(pasted)}`);
  check(await readsOf(pasted.id) === await readsOf(formula.id), 'reading what it reads');
  check(await page.evaluate(() => window.__dataPage.undoLabel) === 'Paste Box', 'one step: Paste Box');
  const pastedAt = await page.evaluate((id) => window.__dataPage.document('x').sheets.find((s) => s.layout.kind === 'flow').layout.boxes.find((b) => b.tile === id), pasted.id);
  check(Math.abs(pastedAt.x + 92 - pasteAt.canvasX) <= 2, `placed where the pointer was: its middle at ${pastedAt.x + 92}, the pointer at ${pasteAt.canvasX}`);

  // UNDO AND REDO on the canvas's toolbar, each saying its step
  check(await page.locator('.dc-flow-undo').getAttribute('title') === 'Undo Paste Box (Ctrl+Z)', 'Undo says what it undoes');
  await page.locator('.dc-flow-undo').click();
  await boxCount(beforePaste.length);
  check(await page.locator('.dc-flow-redo').getAttribute('title') === 'Redo Paste Box (Ctrl+Shift+Z)', 'undone by its button; Redo says it');
  await page.locator('.dc-flow-redo').click();
  await boxCount(beforePaste.length + 1);
  check(await page.locator('.dc-flow-redo').isDisabled(), 'redone; nothing left to redo');

  // MANY BOXES AT ONCE (M7): the Formula and the Summarize picked together (a click, then Shift and a click), dragged
  // as one -- one step, Move Boxes; copied and pasted together, the pasted Summarize reading the pasted Formula; the
  // two taken off together, one step; every box picked by Ctrl+A, let go by Escape
  const layoutOf = () => page.evaluate(() => Object.fromEntries(window.__dataPage.document('x').sheets
    .find((s) => s.layout.kind === 'flow').layout.boxes.map((b) => [b.tile, [b.x, b.y]])));
  const picked = () => page.locator('.dc-flow-box.dc-flow-picked').evaluateAll((els) => els.map((e) => e.dataset.tile).sort());
  await page.locator('.dc-flow-canvas').evaluate((c) => { c.scrollLeft = 0; c.scrollTop = 0; });
  const sumBox = (await boxes()).find((b) => b.tool === 'summarize');
  await page.locator(`.dc-flow-box[data-tile="${formula.id}"]`).scrollIntoViewIfNeeded();
  await page.locator(`.dc-flow-box[data-tile="${formula.id}"] .dc-flow-name`).click();
  await page.locator(`.dc-flow-box[data-tile="${sumBox.id}"] .dc-flow-name`).click({ modifiers: ['Shift'] });
  check(JSON.stringify(await picked()) === JSON.stringify([formula.id, sumBox.id].sort()), `two boxes picked: ${await picked()}`);
  const placesBefore = await layoutOf();
  const fBox = await page.locator(`.dc-flow-box[data-tile="${formula.id}"]`).boundingBox();
  await page.mouse.move(fBox.x + 40, fBox.y + 12);
  await page.mouse.down();
  await page.mouse.move(fBox.x + 40, fBox.y + 12 + 60, { steps: 8 });
  await page.mouse.up();
  const placesAfter = await layoutOf();
  check(placesAfter[formula.id][1] - placesBefore[formula.id][1] === 60 && placesAfter[sumBox.id][1] - placesBefore[sumBox.id][1] === 60,
    `both moved down 60: ${placesBefore[formula.id]} -> ${placesAfter[formula.id]}, ${placesBefore[sumBox.id]} -> ${placesAfter[sumBox.id]}`);
  check(await page.evaluate(() => window.__dataPage.undoLabel) === 'Move Boxes', 'one step: Move Boxes');
  // copied together, pasted where the pointer is: the pasted Summarize reads the pasted Formula, it the Filter
  const beforeMany = (await boxes()).map((b) => b.id);
  await page.locator(`.dc-flow-box[data-tile="${formula.id}"]`).focus();
  await page.keyboard.press('Control+c');
  const manySpot = await emptyCanvasPoint();
  await page.mouse.move(manySpot.x, manySpot.y);
  await page.locator('.dc-flow-canvas').focus();
  await page.keyboard.press('Control+v');
  await boxCount(beforeMany.length + 2);
  const pastedTwo = (await boxes()).filter((b) => !beforeMany.includes(b.id));
  const pf = pastedTwo.find((b) => b.tool === 'formula');
  const ps = pastedTwo.find((b) => b.tool === 'summarize');
  check(pf !== undefined && ps !== undefined, `a Formula and a Summarize pasted: ${JSON.stringify(pastedTwo)}`);
  check(await readsOf(ps.id) === pf.id && await readsOf(pf.id) === await readsOf(formula.id),
    `the pasted Summarize reads the pasted Formula, it what the Formula reads: ${await readsOf(ps.id)}, ${await readsOf(pf.id)}`);
  check(await page.evaluate(() => window.__dataPage.undoLabel) === 'Paste 2 Boxes', 'one step: Paste 2 Boxes');
  const pastedPlaces = await layoutOf();
  check(pastedPlaces[ps.id][0] - pastedPlaces[pf.id][0] === placesAfter[sumBox.id][0] - placesAfter[formula.id][0],
    'placed as they were placed together');
  // what was pasted, picked together: taken off together, one step
  check(JSON.stringify(await picked()) === JSON.stringify([pf.id, ps.id].sort()), `the pasted boxes picked: ${await picked()}`);
  await page.locator(`.dc-flow-box[data-tile="${ps.id}"]`).focus();
  await page.keyboard.press('Delete');
  await boxCount(beforeMany.length);
  check(await page.evaluate(() => window.__dataPage.undoLabel) === 'Remove 2 Boxes', 'one step: Remove 2 Boxes');
  // Ctrl+A on the canvas picks every box; Escape lets them go
  await page.locator('.dc-flow-canvas').focus();
  await page.keyboard.press('Control+a');
  check((await picked()).length === beforeMany.length, `every box picked: ${(await picked()).length}`);
  await page.keyboard.press('Escape');
  check((await picked()).length === 0, 'Escape lets them go');
  // a band dragged from the canvas's empty corner round the Input picks it, and no box past the band
  await page.locator('.dc-flow-canvas').evaluate((c) => { c.scrollLeft = 0; c.scrollTop = 0; });
  const planeAt = await page.locator('.dc-flow-plane').boundingBox();
  const inputAt = await page.locator(`.dc-flow-box[data-tile="${input.id}"]`).boundingBox();
  await page.mouse.move(planeAt.x + 8, planeAt.y + 8);
  await page.mouse.down();
  await page.mouse.move(inputAt.x + 30, inputAt.y + 20, { steps: 10 });
  check(await page.locator('.dc-flow-band').count() === 1, 'its band drawn while it is dragged');
  await page.mouse.up();
  check((await picked()).includes(input.id) && !(await picked()).includes(formula.id), `a band round the Input picks it: ${await picked()}`);
  check(await page.locator('.dc-flow-band').count() === 0, 'its band gone, let go');
  await page.keyboard.press('Escape');
  check((await picked()).length === 0, 'Escape lets it go');

  // A TEXT INPUT (M7's In tools): dropped on the canvas, a table of its own pasted in from a sheet -- an Input over it
  const beforeText = (await boxes()).map((b) => b.id);
  const textSpot = await emptyCanvasPoint();
  await page.locator('.dc-flow-tool[data-tool="textInput"]').scrollIntoViewIfNeeded();
  const textTool = await page.locator('.dc-flow-tool[data-tool="textInput"]').boundingBox();
  await page.mouse.move(textTool.x + textTool.width / 2, textTool.y + textTool.height / 2);
  await page.mouse.down();
  await page.mouse.move(textSpot.x, textSpot.y, { steps: 12 });
  await page.mouse.up();
  const typedIn = page.locator('.dc-text-input');
  await typedIn.waitFor({ timeout: 10_000 });
  await typedIn.locator('.dc-text-input-name input').fill('fx rates');
  await typedIn.locator('.dc-text-input-head').first().evaluate((field) => {
    const data = new DataTransfer();
    data.setData('text/plain', 'ccy\trate\r\nEUR\t1.08\r\nGBP\t1.27\r\nJPY\t0.0067\r\n');
    field.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
  });
  const heads = await typedIn.locator('.dc-text-input-head').evaluateAll((els) => els.map((e) => e.value));
  check(JSON.stringify(heads) === '["ccy","rate"]', `the pasted block fills the table from its head row: ${heads}`);
  check(await typedIn.locator('tbody tr').count() === 3, 'a row a line pasted, the empty rows filled');
  await typedIn.locator('.dc-save-foot .dc-primary').click();
  await boxCount(beforeText.length + 1);
  const rates = await added(beforeText);
  check(rates.tool === 'input' && rates.says === 'fx rates.csv' && rates.shown, `an Input over it: ${JSON.stringify(rates)}`);
  check(await resultRows() === 3, 'its three rows');
  check(await page.evaluate(() => window.__dataPage.undoLabel) === 'Add Text Input', 'one step: Add Text Input');
  const ratesAt = await page.evaluate((id) => window.__dataPage.document('x').sheets.find((s) => s.layout.kind === 'flow').layout.boxes.find((b) => b.tile === id), rates.id);
  check(Math.abs(ratesAt.x + 92 - textSpot.canvasX) <= 2, `made where it was let go: its middle at ${ratesAt.x + 92}`);

  // THE PALETTE'S FIND: its tools narrowed to what is typed
  await page.fill('.dc-flow-find', 'join');
  const visible = await page.locator('.dc-flow-tool:visible').evaluateAll((els) => els.map((e) => e.dataset.tool));
  check(JSON.stringify(visible) === JSON.stringify(['join']), `found: ${visible}`);
  await page.fill('.dc-flow-find', '');
} catch (e) {
  bad(`threw: ${e.stack ?? e}`);
} finally {
  check(errors.length === 0, `no page errors${errors.length ? `: ${errors.join(' | ')}` : ''}`);
  check(alerts.length === 0, `no alerts${alerts.length ? `: ${alerts.join(' | ')}` : ''}`);
  await browser.close();
  await close();
}
process.exit(failed ? 1 : 0);
