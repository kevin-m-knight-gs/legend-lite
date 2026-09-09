# The connection lease — design, 2026-09-09

Companion to `CONNECTION_LIFECYCLE_HOMEWORK_2026_09_09.md`, which established
the defect. This document is the design and the remaining homework: every fact
it rests on, how that fact was established, what is still unverified, and what
would falsify it.

**Defect, one sentence.** `ConnectionResolver.resolve` returns connections under
two different ownership rules through one signature, `QueryService` closes none
of them, and the unpooled half therefore leaks one JDBC connection and its OS
file handles per call.

---

## 1. Evidence table

Every load-bearing claim, and how it was settled. "Read" means the whole method
or file, not a grep window.

| # | Claim | Method | Status |
|---|---|---|---|
| 1 | Two connection origins in main: `ConnectionResolver` (6 sites), `SystemDatabase` (3) | exhaustive grep, untruncated | verified |
| 2 | `connect` splits pooled / unpooled; 8 acquisition arms | read in full | verified |
| 3 | `QueryService` resolves at 5 sites, closes at 0 | read in full (197 lines) | verified |
| 4 | `resolve` has no caller outside `QueryService` | exhaustive grep, whole repo | verified |
| 5 | The whole core suite reaches `resolve` 17 times, from 2 methods only | **instrumented, suite run**, reverted after | verified empirically |
| 6 | The other 3 auto-resolving overloads are unused repo-wide | census (core) + read of the PCT call sites, which pass a connection; nlq does not reference `QueryService` | verified |
| 7 | Production reaches only those same 2 methods, via `/engine/sql` and `/engine/execute` | read `LegendHttpServer` | verified |
| 8 | Tables must persist across separate auto-resolve calls | read `ConnectionIsolationTest` in full | verified, pinned |
| 9 | `HandleStore` never evicts, by design | read in full | verified |
| 10 | The engine pools everything and its results close their own connections | read `DataSourceSpecification`, `SQLResult.close` | verified |
| 11 | The engine RE-SEEDS per acquisition, so it has no persistence feature | read `LocalH2DataSourceSpecification.getConnection` | verified |
| 12 | Both streaming paths drain fully before returning | read `streamWireRows` AND `streamGraph` in full | verified |
| 13 | `Compiler.executeStreaming` dispatches to exactly those two | read the dispatch | verified |
| 14 | No `ExecutionResult` variant holds a live cursor | read all **five** records (the javadoc says four) | verified |
| 15 | An H2 in-memory DB survives closing its last connection | **executed**: create, close, reopen, row present | verified empirically |
| 16 | Two in-process connections to one DuckDB file share state | **executed** | verified empirically |
| 17 | Closing a DuckDB connection releases the OS file handles and checkpoints the WAL | **executed**: `lsof` 2 → 0, delete succeeds, `.wal` gone | verified empirically (POSIX) |
| 18 | The Postgres branch is unreachable | read `CompilerFacadeTest`: compiling a Postgres model throws `NotImplementedException` before execution | verified |
| 19 | The HTTP server handles requests sequentially | read `LegendHttpServer:404`, `setExecutor(null)` | verified |

Claims 12–17 were asserted in conversation BEFORE being checked. Two of them
(14, 15) were checked only after an explicit challenge, and 15 is load-bearing:
had H2 not survived, this design would silently destroy user data between
requests. Recorded here so the sequence is not lost.

## 2. The eight arms, classified

`connect`, by database type and specification:

| # | type | spec | acquisition | ownership |
|---|---|---|---|---|
| 1 | DuckDB | `LocalFile` | `DriverManager`, fresh per call | **caller** |
| 2 | DuckDB | anything else | `HandleStore.getOrOpen` | **store** |
| 3 | SQLite | `LocalFile` | `DriverManager`, fresh per call | **caller** |
| 4 | SQLite | anything else | `HandleStore.getOrOpen` | **store** |
| 5 | H2 | `LocalFile` | `DriverManager`, fresh per call | **caller** |
| 6 | H2 | `StaticDatasource` | `DriverManager`, fresh per call | **caller** |
| 7 | H2 | `EmbeddedH2` / default (in-memory, `DB_CLOSE_DELAY=-1`) | `DriverManager`, fresh per call | **caller** (safe: fact 15) |
| 8 | Postgres | `StaticDatasource` | `DriverManager`, fresh per call | **caller** (unreachable: fact 18) |

Two arms are store-owned. Six are caller-owned and nobody is the caller.

**Why the file-backed arms need no cache.** A file-backed database persists
because it is a file. Only an in-memory database needs a connection held to keep
its tables, and those are exactly arms 2 and 4, which are already pooled. This
is the correction to §8 of the homework doc, which proposed pooling everything:
that would extend a persistence feature nobody asked for AND keep the file
handle open, which is the thing that breaks on Windows.

## 3. Design

`resolve` stops returning a bare `Connection`:

```java
/** A borrowed connection. close() RELEASES it — which for a store-owned
 *  handle means nothing, because HandleStore owns it for the process
 *  (evicting it would drop its tables: cache/HandleStore). Callers always
 *  close; the lease knows what closing means. */
static final class Lease implements AutoCloseable {
    private final Connection connection;
    private final boolean storeOwned;

    static Lease borrowed(Connection c) { return new Lease(c, true);  }
    static Lease owned(Connection c)    { return new Lease(c, false); }

    Connection connection() { return connection; }

    @Override public void close() throws SQLException {
        if (!storeOwned) {
            connection.close();
        }
    }
}
```

`connect` returns `Lease`. Its two store arms return `Lease.borrowed(...)`; the
six `DriverManager` arms return `Lease.owned(...)`. Each of the five call sites
in `QueryService` becomes:

```java
try (var lease = ConnectionResolver.resolve(pureSource, runtimeName)) {
    return execute(pureSource, query, runtimeName, lease.connection());
}
```

**Why a lease and not a proxy.** `DuckWorkspaces` intercepts `close` with a
dynamic proxy, which is right for a harness but puts reflection on every JDBC
call in the query path. The lease is type-safe, costs nothing at runtime, and
keeps the ownership decision inside the resolver, where callers cannot see it or
get it wrong. It is the engine's contract (fact 10) reached without the engine's
pool, which we cannot adopt because we persist and it re-seeds (fact 11).

**Honest scope.** This is not a one-line change. `connect` is a switch with
eight acquisition arms across four database types, and its return type changes;
every arm is touched. Plus one new nested class and five call sites.

## 4. What this fixes, and what it does not

Fixes: the unbounded leak on the two live entry points, and, as a side effect
rather than a goal, the Windows delete in `QueryServiceDirectTest`.

Does not change: pooled semantics (arms 2 and 4 keep persisting, fact 8 stays
green), the corpus harness (disjoint — no `rcorpus` reference to the resolver or
the query service), file-backed persistence (the file provides it), or anything
a caller-supplied-connection overload does.

## 5. Risks, stated rather than discovered later

1. **Three of the five call sites have no test coverage** (fact 6). They will be
   changed identically to the two that are covered, and nothing in the suite
   will check them. This is the largest residual risk and it is not reducible by
   care alone — it needs a test, or an explicit decision to leave dead API
   uncovered.
2. **Windows is inference until CI runs.** Fact 17 is POSIX evidence that the
   driver releases its handles on close; the JDBC path is the same on Windows,
   but only the runner proves it.
3. **Arm 7 rests on fact 15.** If an H2 URL ever loses `DB_CLOSE_DELAY=-1`,
   closing becomes destructive. The design should not silently depend on a URL
   substring — a test asserting H2 in-memory survives a close belongs with this
   change.
4. **Arms 6 and 8 are remote.** Closing per call is correct but unpooled remote
   connections are slow; if Postgres is ever implemented it wants a real pool.
   Out of scope, recorded.
5. **Concurrency is not made worse.** Requests are sequential today (fact 19),
   and store-owned connections are shared exactly as they are now. Caller-owned
   ones become per-call and closed, which is strictly safer than leaking them.

## 6. Test plan

- `ConnectionIsolationTest` must stay green untouched. It is the persistence
  pin (fact 8) and the single best falsifier of this design.
- `QueryServiceDirectTest` and `LegendHttpServerIntegrationTest` should pass
  with their temp files deleted. The HTTP one currently swallows its teardown
  `IOException` and prints a stack trace; that catch should go, or the test
  will keep hiding exactly this class of bug.
- New: an H2 in-memory close/reopen test, pinning fact 15 in the suite rather
  than in this document.
- New, if the dead overloads are kept: one test through an auto-resolving
  `execute` and one through `stream`, so the three uncovered sites stop being
  uncovered.
- Full chain under the CI envelope, then all three platforms.

## 7. What would falsify this

- `ConnectionIsolationTest` going red means the pooled/unpooled classification
  is wrong.
- A Windows delete still failing means something else holds the file, and the
  diagnosis in §1 fact 17 does not transfer.
- Any in-memory H2 data loss between requests means arm 7 is misclassified.

## 8. Alternatives rejected, with reasons

- **Pool everything** (homework §8): keeps file handles open for the process, so
  it does not fix the symptom, and extends persistence to file DBs unasked.
- **Close unpooled connections inside `QueryService`**: re-encodes the split at
  the call site, which is the knowledge callers must not need — the exact shape
  that produced this bug.
- **Port Hikari from the engine**: the engine re-seeds per acquisition and never
  persists (fact 11); adopting its contract wholesale breaks fact 8.
- **Convert the file-backed tests to in-memory**: deletes the only coverage of a
  real product path and makes this leak invisible again.
