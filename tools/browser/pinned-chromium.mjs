// Point Playwright at the Chromium Bazel fetched (//tools/browser:chromium_headless_shell), never
// at a cache in $HOME. Import this BEFORE 'playwright': Playwright reads PLAYWRIGHT_BROWSERS_PATH
// when its registry module loads, and ES modules evaluate in import order.
//
// PINNED_CHROMIUM is the executable's rlocationpath (browser_test sets it), e.g.
//   chromium_headless_shell_mac_arm64/chromium_headless_shell-1243/chrome-headless-shell-mac-arm64/chrome-headless-shell
// Its grandparent's parent is the browsers path; the revision directory between them is the name
// Playwright's registry derives from the locked playwright-core's browsers.json, so a pin that
// drifts from the lock fails at launch ("Executable doesn't exist") instead of running another
// browser.
import { existsSync, realpathSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';

const pinned = process.env.PINNED_CHROMIUM;
// Bazel gives a test HOME = TEST_TMPDIR but passes the host's TMPDIR through on macOS
// (/var/folders/...), where Playwright would put the browser profile and artifacts: keep them in
// the test's own directory. os.tmpdir() reads TMPDIR on every call, and on Windows TEMP and TMP instead
// (CI, 2026-10-04: the profile went to the runner's AppData\Local\Temp).
if (process.env.TEST_TMPDIR) {
  process.env.TMPDIR = process.env.TEST_TMPDIR;
  process.env.TEMP = process.env.TEST_TMPDIR;
  process.env.TMP = process.env.TEST_TMPDIR;
}
if (pinned) {
  const runfiles = process.env.JS_BINARY__RUNFILES ?? process.env.RUNFILES_DIR
    ?? resolve(process.cwd(), '..');
  const exe = join(runfiles, pinned);
  if (!existsSync(exe)) throw new Error(`the pinned Chromium is not in runfiles: ${exe}`);
  let browsers = dirname(dirname(dirname(exe)));
  if (process.platform === 'win32') {
    // Windows starts a process only by a path under MAX_PATH (260): CreateProcess reported a 277-character
    // runfiles path as ENOENT (CI, 2026-10-04) though the file was there. In a runfiles tree the FILES are
    // symlinks (the directories are real): the executable's link points into the archive Bazel fetched,
    // under the output base's external/, a far shorter path. Its browsers path is three levels up.
    const real = realpathSync.native(exe);
    browsers = dirname(dirname(dirname(real)));
    if (real.length >= 260) {
      throw new Error(`the pinned Chromium's path is ${real.length} characters, and Windows starts nothing`
        + ` past 259: ${real} (a shorter --output_user_root shortens it)`);
    }
    console.error(`pinned chromium on Windows: ${real} (${real.length} characters)`);
  }
  process.env.PLAYWRIGHT_BROWSERS_PATH = browsers;
  // Playwright's own guard against a browser without its system libraries is a host `ldd`
  // walk keyed to Ubuntu/Debian package names; the test's own launch is the real check.
  process.env.PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS ??= '1';
  console.error(`pinned chromium: ${exe} (browsers path ${browsers})`);
} else if (process.env.TEST_SRCDIR) {
  throw new Error('a browser test without PINNED_CHROMIUM: use browser_test (//tools/browser:defs.bzl)');
}

// NO EXTENSION FROM DUCKDB'S SITE: every page's DuckDB-WASM loads its extensions from the site's own vendor/
// (engine-client's ownExtensions; MODULE.bazel pins them), so a test whose page still fetched one from
// extensions.duckdb.org would pass only while that site is up (the Compiler Rewrite line's finding, 2026-10-13).
// Every launch of the test's Playwright makes that host unresolvable, so such a fetch fails here as it would behind
// a firewall. A Chromium flag, not Bazel's block-network tag: that tag works only under a sandbox, so Windows would
// run unguarded, and the flag holds on all three platforms. The Playwright guarded is the one the entry script
// resolves beside itself -- the instance it imports (Playwright's ESM entry re-exports its CJS one) -- and a harness
// that loads Playwright from elsewhere calls `hermetic` on the one it uses (site/verify.mjs).
// //datacube:browser_guard_test fails when a launch stops being guarded (a Playwright upgrade, or this file changed).
export const HERMETIC_ARGS = ['--host-resolver-rules=MAP extensions.duckdb.org ~NOTFOUND'];

/**
 * BEHIND A PROXY (2026-10-25, a cloud session: its ~/.bazelrc passes HTTPS_PROXY and NO_PROXY to every test): Chromium
 * hands a proxied request's host to the proxy unresolved, so the resolver's rule above never met extensions.duckdb.org
 * -- //datacube:browser_guard_test met the proxy's certificate instead (ERR_CERT_AUTHORITY_INVALID), and a Chromium
 * that trusts that certificate downloaded the extension. The proxy the environment names is given as Chromium's own
 * flag, each scheme to its own as the environment has it, with that host (and NO_PROXY's) bypassing it: so it is
 * resolved here, and refused; every other host still goes through the proxy. No proxy named: no flag.
 */
export function proxyArgs(env = process.env) {
  const https = env.HTTPS_PROXY || env.https_proxy;
  const http = env.HTTP_PROXY || env.http_proxy;
  const all = env.ALL_PROXY || env.all_proxy;
  const schemes = [...(https ? [`https=${https}`] : []), ...(http ? [`http=${http}`] : [])];
  const server = schemes.length > 0 ? schemes.join(';') : all;
  if (!server) return [];
  const bypass = (env.NO_PROXY || env.no_proxy || '').split(',').map((h) => h.trim()).filter(Boolean);
  return [`--proxy-server=${server}`, `--proxy-bypass-list=${['extensions.duckdb.org', ...bypass].join(';')}`];
}

/** Guards every launch of `chromium` (Playwright's BrowserType) with HERMETIC_ARGS, behind a proxy too; answers it. */
export function hermetic(chromium) {
  if (chromium.__hermetic) return chromium;
  const guard = [...HERMETIC_ARGS, ...proxyArgs()];
  const launch = chromium.launch.bind(chromium);
  chromium.launch = (options = {}) => launch({ ...options, args: [...(options.args ?? []), ...guard] });
  const persistent = chromium.launchPersistentContext.bind(chromium);
  chromium.launchPersistentContext = (dir, options = {}) =>
    persistent(dir, { ...options, args: [...(options.args ?? []), ...guard] });
  chromium.__hermetic = true;
  return chromium;
}

if (process.env.TEST_SRCDIR && process.argv[1]) {
  let playwright;
  try {
    playwright = createRequire(process.argv[1])('playwright');
  } catch (e) {
    // none beside the entry script: the harness guards the Playwright it loads itself (`hermetic`)
    if (e?.code !== 'MODULE_NOT_FOUND') throw e;
  }
  if (playwright) hermetic(playwright.chromium);
}
