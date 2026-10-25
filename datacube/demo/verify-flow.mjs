// A FLOW AS A PERSON BUILDS ONE (docs/DATACUBE_FLOW_MODE_2026_10_24.md M1, its "done when"): Create ▸ Flow, then each
// box by drag and drop alone -- an Input dragged onto the canvas, a Filter onto the Input, a Formula onto the Filter, a
// Summarize onto the Formula -- each set in the settings pane by the grid's own window docked there, each previewed in
// the results pane; a box moved and the move undone; the flow saved, the tab reloaded and the flow opened again (no box
// opened until it is shown, the last box's rows as before); and shared, the link opening the same flow in a fresh tab.
//
//   bazel test //datacube:verify_flow_test        bazel run //datacube:verify_flow (by hand)

// first: points Playwright at the Chromium Bazel fetched (as a browser_test; a no-op under bazel run)
import '../../tools/browser/pinned-chromium.mjs';
import { chromium } from 'playwright';
import { serve, siteRoot } from './harness.mjs';

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
  const from = await page.locator(`.dc-flow-tool[data-tool="${tool}"]`).boundingBox();
  const onto = await page.locator(target === 'canvas' ? '.dc-flow-canvas' : `.dc-flow-box[data-tile="${target}"]`).boundingBox();
  if (!from || !onto) throw new Error(`nothing to drag ${tool} from, or onto ${target}`);
  const to = target === 'canvas' ? { x: onto.x + onto.width / 2, y: onto.y + onto.height * 0.75 } : { x: onto.x + onto.width / 2, y: onto.y + onto.height / 2 };
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
  await page.mouse.down();
  await page.mouse.move(to.x, to.y, { steps: 12 });
  await page.mouse.up();
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
  check(await page.locator('.dc-flow-lines path').count() === 1, 'a line from the Input into the Filter');

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
  check(await page.locator('.dc-flow-lines path').count() === 3, 'a line into each box after the first');

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

  // A BOX MOVED BY HAND, and the move undone
  const placeOf = () => page.evaluate((id) => {
    const flow = window.__dataPage.document('x').sheets.find((s) => s.layout.kind === 'flow');
    return flow.layout.boxes.find((b) => b.tile === id);
  }, summary.id);
  const was = await placeOf();
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
} catch (e) {
  bad(`threw: ${e.stack ?? e}`);
} finally {
  check(errors.length === 0, `no page errors${errors.length ? `: ${errors.join(' | ')}` : ''}`);
  check(alerts.length === 0, `no alerts${alerts.length ? `: ${alerts.join(' | ')}` : ''}`);
  await browser.close();
  await close();
}
process.exit(failed ? 1 : 0);
