// ONE SITE, EVERY APP, ONE COPY OF THEIR WEBASSEMBLY (site/BUILD.bazel, dist).
//
// Each app's site is built to stand alone, its WebAssembly in its own ./vendor/ -- DuckDB and its extensions, the
// compiler, the SDLC, the saved stores' rules, the warehouse's binding. Served together from one origin, those would be
// three copies of the same files at three URLs: three times the site, and three downloads for a browser that opens all
// three apps. So here each app keeps its own small files (its fonts, its stylesheets) and the WebAssembly is copied
// once, to /vendor/; each app's config.json says where it is ("vendor": "../../vendor/" from <app>/demo/), as does
// DataCube's early hint for the compiler's module (its index.html). And it is a static site: what needs a server or is
// a test's (LEFT_OUT) stays in each app's own site, not here.
//
//   Usage: node make-dist.mjs <dist-dir> <sites-dir>   (<sites-dir>: datacube/, query/ and studio/, each as built)

import { cp as copyEntry, mkdir, readdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join, relative, resolve, sep } from 'node:path';

// copied as itself, never as the link it is in Bazel's tree (datacube/demo/make-dist.mjs says why)
const cp = (from, to) => copyEntry(from, to, { dereference: true });

if (process.argv.length !== 4) {
  console.error('usage: make-dist.mjs <dist-dir> <sites-dir>');
  process.exit(2);
}
const DIST = resolve(process.argv[2]);
const SITES = resolve(process.argv[3]);
// every file of the three sites, by its path under the sites' folder, '/'-separated whatever the platform's own is
const files = (await readdir(SITES, { recursive: true, withFileTypes: true }))
  .filter((e) => !e.isDirectory())
  .map((e) => relative(SITES, join(e.parentPath, e.name)).split(sep).join('/'));

const APPS = ['datacube', 'query', 'studio'];
/** The WebAssembly every app loads, by its path under an app's vendor/ (the same layout in all three). */
const SHARED = /^(classes\.wasm|wasm-gc-module-runtime\.js|duckdb-eh\.wasm|duckdb-browser-eh\.worker\.js|duckdb-extensions\/.*|sdlc\/.*|queries\/.*|warehouse\/.*)$/;
/** The app that brings the shared files: DataCube's vendor/ holds every one of them. */
const SOURCE = 'datacube';
/** Where an app's page (<app>/demo/) finds the shared folder. */
const SHARED_FROM_PAGE = '../../vendor/';

/**
 * What a static host has no use for (the user, 2026-10-15: the static site slimmed, no Python and no tests): DataCube's
 * pages that need a server -- the engine page (Python's engine serves it, from DataCube's own dist) and the remote
 * harness's -- its stress harness and the models only its harnesses read, a notebook's cube (the Python wheel carries its
 * own), and esbuild's records of the bundles (the budget tests read them). Each app's own site keeps them all. Every
 * pattern matches a file, or the layout changed and this says so: nothing is shipped or left out without a word.
 */
const LEFT_OUT = [
  /^datacube\/demo\/[^/]*-meta\.json$/,
  /^datacube\/demo\/(engine\.html|bundle-engine\.js|chunks-bundle-engine\/.*)$/,
  /^datacube\/demo\/(remote\.html|remote-bundle\.js|chunks-remote-bundle\/.*)$/,
  /^datacube\/demo\/(stress\.html|stress\.js|chunks-stress\/.*|torture\.pure|trades-h2\.pure)$/,
  /^datacube\/demo\/(widget[^/]*|widget-chunks\/.*)$/,
];
const leftOut = new Set();

// each config an app reads, and DataCube's early hint: pointed at the shared folder
const rewritten = new Set([
  'datacube/demo/config.json', 'datacube/demo/index.html',
  'query/demo/config.json', 'query/demo/config-server.json', 'query/demo/config-warehouse.json',
  'studio/demo/config.json', 'studio/demo/config-server.json',
]);

let shared = 0;
for (const file of files) {
  const unused = LEFT_OUT.find((p) => p.test(file));
  if (unused) {
    leftOut.add(unused);
    continue;
  }
  const app = APPS.find((a) => file.startsWith(`${a}/`));
  const vendor = app !== undefined ? file.slice(`${app}/demo/vendor/`.length) : '';
  const inVendor = app !== undefined && file.startsWith(`${app}/demo/vendor/`);
  if (inVendor && SHARED.test(vendor)) {
    // once, from the one app that brings them all
    if (app === SOURCE) {
      await mkdir(dirname(join(DIST, 'vendor', vendor)), { recursive: true });
      await cp(join(SITES, file), join(DIST, 'vendor', vendor));
      shared += 1;
    }
    continue;
  }
  const to = join(DIST, file);
  await mkdir(dirname(to), { recursive: true });
  if (!rewritten.has(file)) {
    await cp(join(SITES, file), to);
    continue;
  }
  const text = await readFile(join(SITES, file), 'utf8');
  const out = file.endsWith('.json') ? pointConfig(text, file) : text.replace("'./vendor/classes.wasm'", `'${SHARED_FROM_PAGE}classes.wasm'`);
  if (out === text) throw new Error(`${file}: nothing pointed at the shared vendor folder -- has its layout changed?`);
  await writeFile(to, out);
}
if (shared === 0) throw new Error(`no shared WebAssembly found under ${SOURCE}/demo/vendor/`);
const unmatched = LEFT_OUT.filter((p) => !leftOut.has(p));
if (unmatched.length > 0) throw new Error(`nothing left out matched ${unmatched.join(', ')}: has a site's layout changed?`);
console.log(`dist/: the apps, and ${shared} shared files in vendor/`);

/** A config with every vendor folder it names -- its own, its planner's, its model home's -- the shared one. */
function pointConfig(text, file) {
  const config = JSON.parse(text);
  const point = (o) => { if (o && typeof o === 'object' && typeof o.vendor === 'string') o.vendor = SHARED_FROM_PAGE; };
  point(config);
  point(config.planner);
  point(config.depot);
  // an app whose config names no vendor of its own reads ./vendor/ (page-config.ts, main.ts): it is told the shared one
  if (file !== 'studio/demo/config.json' && file !== 'studio/demo/config-server.json') config.vendor = SHARED_FROM_PAGE;
  return `${JSON.stringify(config, null, 2)}\n`;
}
