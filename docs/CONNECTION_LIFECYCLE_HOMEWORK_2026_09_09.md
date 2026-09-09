# Connection lifecycle — homework, 2026-09-09

Trigger: the first Windows CI run left ONE error after the portability fixes —
`QueryServiceDirectTest.teardown` cannot delete its DuckDB file because a
connection still holds it. POSIX allows unlinking an open file, so this has
passed silently on Linux and macOS since the module was written.

This document is the homework BEFORE any change. Every claim below carries how
it was established. Nothing here is inferred from a sample.

---

## 0. Method, and a correction

A first pass at this question produced three claims that were **wrong in
method** and are corrected here:

| earlier claim | how it was reached | verdict |
|---|---|---|
| "nothing closes a connection in the server package" | one grep, truncated at 8 results | direction right, evidence invalid |
| "every request leaks" | read 1 of 5 `resolve` call sites | **overstated** — see §2 |
| "the handle store has no eviction" | grep against `server/HandleStore.java`, which **does not exist** | right answer, absent evidence — the file is `cache/HandleStore.java` |

Reading a failed grep as evidence of absent behaviour is the specific error to
avoid. Every fact below states its evidence, and the ones that matter most were
settled by **running code**, not by reading it.

---

## 1. The connection universe (exhaustive)

`grep -rn "DriverManager.getConnection" core/src/main` — complete, untruncated.
**Two** origins in main, nine call sites:

| origin | sites | lifetime |
|---|---|---|
| `server/ConnectionResolver` | 6 | §2 |
| `exec/SystemDatabase` | 3 | held per engine-session, closed by a `java.lang.ref.Cleaner` when the owner is collected |

Tests open their own connections at 170 sites across 128 files. Those are
**caller-owned** and pass the connection into the `execute(…, Connection)`
overloads; they are out of scope for this question.

`testdatagen` never opens a connection — it takes one as a parameter
(`TestDataGenerationNatives`, `TestDataGenerator`). Same for the harness
`ReplayOracle`. Verified by reading their signatures.

## 2. What actually leaks, and what does not

`ConnectionResolver.connect` (read in full, all branches) splits in two:

**Pooled** — `HandleStore.getOrOpen`, content-keyed on stores + definition + FQN:
- DuckDB, any spec that is not `LocalFile`
- SQLite, any spec that is not `LocalFile`

**Unpooled** — a fresh `DriverManager.getConnection` on EVERY call:
- DuckDB `LocalFile`
- SQLite `LocalFile`
- **H2, every spec** (including in-memory)
- Postgres

`QueryService` (read in full, 197 lines) resolves at 5 sites and closes at
none. So the unpooled branches leak one connection per call, and the pooled
ones do not leak: they are one connection per distinct content key, for the
life of the process.

**The pooled path is not a bug.** `cache/HandleStore` documents its own
no-eviction rule: *"evicting an in-memory connection would silently drop its
tables. Entries live for the process; distinct content keys are distinct
handles BY DESIGN."* A closed handle is replaced atomically via the `dead`
predicate (`Connection.isClosed`).

### The empirical blast radius

Static reading cannot classify 90 call sites with multiline arguments, so the
resolver was **instrumented** (branch taken + first non-resolver stack frame)
and the whole core suite run. Instrumentation reverted immediately after; the
suite's own `ObservabilityGuardrailTest` correctly failed the ad-hoc env flag,
which is the only reason that run was red.

Result — the ENTIRE core suite reaches `resolve` **17 times**, from **two**
methods:

| branch | count | caller |
|---|---:|---|
| UNPOOLED DuckDB LocalFile | 8 | `QueryService.executeSql:148` |
| POOLED DuckDB InMemory | 7 | `QueryService.executeSql:148` |
| UNPOOLED DuckDB LocalFile | 1 | `QueryService.executeWireJson:122` |
| POOLED DuckDB InMemory | 1 | `QueryService.executeWireJson:122` |

The other three auto-resolving overloads (3-arg `execute`, 5-arg `execute`,
4-arg `stream`) are called **nowhere** in the suite. SQLite, H2 and Postgres
never reach the unpooled branches in tests at all. In production the same two
methods are the only ones used: `/engine/execute` → `executeWireJson:160`,
`/engine/sql` → `executeSql:234` (`LegendHttpServer`, read directly). `/lsp`
and `/engine/diagram` do not resolve connections.

**So the leak is real but narrow: it is the unpooled branches, reached through
two entry points.** It is unbounded in a long-running server, because those two
endpoints are the interactive flow.

## 3. The constraint any fix must satisfy

`ConnectionIsolationTest` (read in full) is the pinned spec, and it is not
subtle:

```
qs.executeSql(MODEL_A, "CREATE TABLE LEAK_T (ID INTEGER)", "test::RT");
qs.executeSql(MODEL_A, "INSERT INTO LEAK_T VALUES (42)",   "test::RT");
assertDoesNotThrow(() -> qs.executeSql(MODEL_A,       "SELECT * FROM LEAK_T", …));
assertDoesNotThrow(() -> qs.executeSql(MODEL_A_PRIME, "SELECT * FROM LEAK_T", …));
```

Tables created in one auto-resolve call MUST be visible in a later, separate
one — including from a different source text with the same stores, which is the
interactive `/engine/sql` seeds → `/engine/execute` reads flow. A different
store declaration must NOT see them (the D100 cross-model data leak).

**Consequence: "always close", applied to the pooled path, destroys the
in-memory database and breaks this test.** Any design that hands callers a
uniform always-close contract must keep something else holding the database
alive.

## 4. What the engine does — and why it is not a complete spec here

Read directly in `legend-engine` at the pinned commit:

- `DataSourceSpecification` builds a **HikariCP** pool per connection key.
  There is no unpooled path. `getConnectionUsingIdentity` returns a pooled
  connection.
- `SQLResult.close()` drops temp tables, then closes the statement AND the
  connection through one closing function. The **result owns the connection**,
  not the request, which is what keeps streaming correct.
- Because a pooled connection is a proxy, `close()` means *return to pool*.
  Calling it is always correct and always required — one contract, no split.

**But the engine does not have our persistence feature.**
`LocalH2DataSourceSpecification.getConnection` **re-runs the plan's test-data
setup SQLs on every acquisition**. The engine's state is declared in the plan
and re-established per connection; it never accumulates across requests. That
is precisely why an always-close contract is safe for it.

We accumulate state in the database across separate HTTP calls, deliberately,
and §3 pins it. **So the engine's contract cannot be ported wholesale.** It is
the right answer to a question we are not asking. Adopt its *shape* (one
contract, close means release) only if something owns the database's lifetime
independently of any one connection.

## 5. Precedents already in this tree

Three mechanisms, all ours, all working:

1. **`cache/HandleStore`** — process-lifetime, content-keyed, dead-handle
   replacement, no eviction by design. Used by the pooled resolver branches.
2. **`exec/SystemDatabase`** — connections held per engine-session, closed by a
   `Cleaner` when the owning graph becomes unreachable.
3. **`rcorpus/DuckWorkspaces`** (harness) — ONE long-lived DuckDB instance;
   each workspace is `ATTACH ':memory:' AS __ws_N` plus a duplicated connection
   with `USE`; the returned connection is a **close-intercepting proxy** whose
   `close()` DETACHes the catalog. A leak ceiling turns a missed close into a
   loud failure. This is Hikari's contract, hand-built, and it already assumes
   callers close.

The corpus harness is **disjoint** from the server path: no reference to
`ConnectionResolver` or `QueryService` anywhere under `rcorpus`
(grep, exhaustive). Its per-package session sharing and seed ledger live in the
workspace catalog, not in connection identity. **A server-side change cannot
disturb corpus sharing or seeding.**

## 6. Runtime facts established by experiment, not reading

- **Two in-process JDBC connections to the same DuckDB file share state.**
  Written with connection A, read back `7` through an independent connection B
  opened via `DriverManager` on the same path, same JVM. This is why the
  current per-call reopening "works", and it means content-keyed pooling of
  file-backed connections is viable rather than a lock conflict.
- H2 in-memory URLs already carry `DB_CLOSE_DELAY=-1`, so the H2 database
  survives closing its last connection. H2 leaks connections but not state.

## 7. Options, weighed against the evidence

**A. Route the unpooled branches through `HandleStore` too.**
One connection per distinct (stores + definition), process-lifetime, nothing
closed. Kills the leak by making the leaking paths pooled, keeps §3 intact
untouched, and extends the persistence feature to file-backed databases, which
is arguably what a user posting `/engine/sql` then `/engine/execute` against a
file DB already expects. §6 shows DuckDB tolerates it. **Cost:** file handles
stay open for the process, so `QueryServiceDirectTest` still cannot delete its
file — the test needs a way to release, which today does not exist. **Risk:**
Postgres is remote; holding a connection for the process life ignores idle
timeouts and server-side limits, which is exactly what Hikari exists to manage.

**B. A lease/proxy contract, harness-shaped.**
`resolve` returns a proxy whose `close()` releases rather than destroys —
no-op for pooled handles, real close for unpooled ones. One uniform caller
rule, §3 preserved, and the pattern is already proven in `DuckWorkspaces`.
**Cost:** changes the signature of a server API and touches all five call
sites. **Benefit:** it is the only option that gives the tests a release point.

**C. Close only the unpooled connections inside `QueryService`.**
Smallest diff. **Rejected:** it re-encodes the pooled/unpooled split at the
call site, which is the knowledge callers must not need, and it is the exact
shape that produced this bug.

**D. Do nothing to the resolver; fix the test.**
Defensible only if the leak is judged acceptable. It is not: the two leaking
entry points are the interactive server flow, and growth is unbounded.

## 8. Recommendation

**B, with A's keying.** Every branch content-keyed through `HandleStore`, and
`resolve` returning a release-on-close proxy so there is ONE caller contract.
Pooled handles ignore the close (§3 preserved); unpooled ones are released.
Postgres, being remote, should not be process-lifetime pooled by hand — it
either stays unpooled-and-released or gets a real pool, and that is a separate
decision from this fix.

## 9. Still unknown — to settle before writing code

1. Whether any Postgres user flow exists today (no test reaches that branch;
   the branch may be dead).
2. Whether `/engine/execute` and `/engine/sql` are ever called concurrently for
   the same content key, which decides whether a released proxy can be handed
   out twice.
3. What `QueryServiceDirectTest` should assert once a release point exists —
   deleting the file is a test-hygiene goal, not a product requirement.
