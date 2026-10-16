// A PAGE ON PYTHON, PLANNED BY PYTHON (docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md §15): the page's runner asks Python
// alone -- its plan-and-run -- with the token, and reads the plan Python answers as the tab reads its own planner's. A
// Python host plans in process, on its one plane (the program's §1.1, the user's ruling of 2026-10-15): one that says
// another is refused, never followed.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { col, element, fn, lambda, type Lambda } from '../../pure-protocol/src/index.ts';
import { askedOffers, pythonRunner, type PythonLink } from '../src/services/python-host.ts';
import type { CubeSnapshot } from '../src/snapshot.ts';

const MODEL = '###Pure\nClass x::T {}';
const RUNTIME = 'x::R';
const QUERY: Lambda = lambda([], fn('select', fn('from', element('x::DB')), col('x', 'desk')));

/** Python, as the page sees it: each call caught, and answered as `answer` says. */
function python(answer: (path: string, body: string) => Response): { link: PythonLink; calls: { path: string; body: string; auth: string | null }[] } {
  const calls: { path: string; body: string; auth: string | null }[] = [];
  const fetch = (async (url: string, init?: RequestInit) => {
    const path = new URL(url).pathname;
    const body = String(init?.body ?? '');
    calls.push({ path, body, auth: (init?.headers as Record<string, string> | undefined)?.['Authorization'] ?? null });
    return answer(path, body);
  }) as typeof globalThis.fetch;
  return { link: { baseUrl: 'http://127.0.0.1:9', fetch, authorization: 'Bearer t0ken' }, calls };
}

/** A JSON answer, as much of a Response as the page reads (V8 inside Python, the headless lane, has no Response). */
const json = (body: unknown, status = 200): Response => {
  const text = JSON.stringify(body);
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name: string) => (name.toLowerCase() === 'content-type' ? 'application/json' : null) },
    text: async () => text,
    json: async () => JSON.parse(text) as unknown,
  } as unknown as Response;
};

/** What a Python host says it offers (services.json), planning on `word`. */
const offers = (word: string) => {
  const plane = { word, label: `Plan ${word}` };
  return { files: true, remote: false, pages: { where: '/home/me/pages', user: 'local' }, plane, planes: [plane] };
};

describe('a page on Python, planned by Python', () => {
  it("sends Python the query in one plan-and-run, with the token, and reads Python's own planner's refusal", async () => {
    const { link, calls } = python(() => json({ plan: 'ERR\nCOMPILATION\nno such class' }));
    await assert.rejects(pythonRunner(link, MODEL, RUNTIME).run(QUERY, { epoch: 1 } as CubeSnapshot), /no such class/);
    assert.equal(calls.length, 1);
    assert.equal(calls[0]!.path, '/plan-and-run.json');
    assert.equal(calls[0]!.auth, 'Bearer t0ken');
  });

  it('takes the one plane a Python host plans on, its own', async () => {
    const { link } = python(() => json(offers('local')));
    assert.equal((await askedOffers(link)).plane.word, 'local');
  });

  it('refuses a Python host that says it plans elsewhere: no plane but its own is followed', async () => {
    for (const word of ['remote', 'engine']) {
      const { link } = python(() => json(offers(word)));
      await assert.rejects(askedOffers(link), new RegExp(`plans on its own planner, in process: it named "${word}"`), word);
    }
  });
});
