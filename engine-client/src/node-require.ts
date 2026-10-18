// A `require` rooted in this package, for code run in NODE (tests, benchmarks, harnesses) that loads DuckDB-WASM's
// Node build: `engineClientRequire('@duckdb/duckdb-wasm/blocking')`, and `.resolve(...)` for its dist/ files. It
// resolves this package's one copy, so no app keeps DuckDB-WASM in its own lock. A browser bundle never imports this
// file (the page takes DuckDB from duckdb-wasm.ts).

import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';

export const engineClientRequire = createRequire(import.meta.url);

/** What a Node test or harness uses of DuckDB-WASM's blocking database. */
export interface NodeDuckDb {
  connect(): unknown;
  registerFileBuffer(name: string, bytes: Uint8Array): void;
  registerFileText(name: string, text: string): void;
  copyFileToBuffer(name: string): Uint8Array;
}

/**
 * DuckDB-WASM's blocking database in Node, its extensions ONLY the pinned ones every site serves the tab
 * (//engine-client:duckdb_extensions, MODULE.bazel): nothing is fetched from extensions.duckdb.org. `extensions` is
 * those files, each laid out `<DuckDB's version>/<its build>/<name>.duckdb_extension.wasm`: node_test names them in
 * DUCKDB_EXTENSIONS for any test that takes DuckDB-WASM, and a test resolves them with runfilesFromEnv.
 *
 * Node has no XMLHttpRequest, and without one DuckDB's Node build fetches an extension it autoloads (ICU, which the
 * session's time zone loads; JSON; Parquet) from a worker thread and waits for it with no timeout: where the network
 * is closed or its TLS refused (a Bazel sandbox, a cloud container), a test hung until Bazel killed it. With one
 * defined, DuckDB asks it instead, so this installs one that answers a pinned file, 404 for an extension that is not
 * pinned (DuckDB's own load error), and throws for any other URL, which DuckDB reads as it read no XMLHttpRequest (a
 * remote file's read fails, as it always has in Node).
 */
export async function startNodeDuckDb(extensions: readonly string[]): Promise<NodeDuckDb> {
  answerExtensionRequests(extensions);
  const duckdb = engineClientRequire('@duckdb/duckdb-wasm/blocking');
  const dist = path.dirname(engineClientRequire.resolve('@duckdb/duckdb-wasm/blocking'));
  const db = await duckdb.createDuckDB(
    {
      mvp: { mainModule: path.join(dist, 'duckdb-mvp.wasm'), mainWorker: path.join(dist, 'duckdb-node-mvp.worker.cjs') },
      eh: { mainModule: path.join(dist, 'duckdb-eh.wasm'), mainWorker: path.join(dist, 'duckdb-node-eh.worker.cjs') },
    },
    new duckdb.VoidLogger(),
    duckdb.NODE_RUNTIME,
  );
  await db.instantiate();
  return db;
}

// `<version>/<build>/<name>.duckdb_extension.wasm`: where DuckDB-WASM asks a repository for an extension
const EXTENSION = /(?:^|\/)(v[^/]+\/wasm_[a-z]+\/[a-z0-9_]+\.duckdb_extension\.wasm)$/;

function answerExtensionRequests(extensions: readonly string[]): void {
  if (extensions.length === 0) throw new Error('no pinned DuckDB extensions given: the test takes //engine-client:duckdb_extensions');
  const pinned = new Map<string, string>();
  for (const file of extensions) {
    const key = EXTENSION.exec(file.replaceAll('\\', '/'))?.[1];
    if (key === undefined) throw new Error(`${file} is not laid out <version>/<build>/<name>.duckdb_extension.wasm`);
    pinned.set(key, file);
  }

  // the members DuckDB's Node build uses: a synchronous GET for an extension; HEAD and ranged GETs for a remote file
  class ExtensionRequest {
    responseType = '';
    status = 0;
    response = new ArrayBuffer(0);
    #url = '';

    open(_method: string, url: string): void {
      this.#url = url;
    }

    setRequestHeader(): void {}

    send(): void {
      const key = EXTENSION.exec(this.#url)?.[1];
      if (key === undefined) throw new Error(`DuckDB in Node makes no request: ${this.#url}`);
      const file = pinned.get(key);
      if (file === undefined) {
        this.status = 404;
        return;
      }
      const bytes = readFileSync(file);
      this.status = 200;
      this.response = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
    }

    getAllResponseHeaders(): string {
      return '';
    }
  }
  (globalThis as { XMLHttpRequest?: unknown }).XMLHttpRequest = ExtensionRequest;
}
