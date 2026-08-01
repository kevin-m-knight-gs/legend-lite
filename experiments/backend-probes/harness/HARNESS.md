# Backend probe harness — READ BEFORE PROBING

Already built. **Do NOT run `mvn`** — it is compiled and the classpath is resolved.

## Run

```bash
cd /private/tmp/claude-502/-Users-neemsandv/9d0bca0a-c404-43ee-9bc6-4ed2e759ec31/scratchpad/probe
export JAVA_HOME=~/jdk/jdk-21.0.11+10/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
CP="target/classes:$(cat cp.txt)"

java -cp "$CP" Probe <backend> <yourprobes.tsv> <yourout.tsv> 2>&1 | grep -v SLF4J
```

`<backend>` = `duckdb` | `sqlite` | `h2` | `postgres` | `mariadb`

- **duckdb 1.5.0.0** — the incumbent. Run it to get the reference value for every probe.
- **sqlite 3.47.1.0** — pure Java, instant.
- **h2 2.4.240** — cross-check only; another session owns H2. Do not write H2 docs.
- **postgres** — real PostgreSQL 18.4 via zonky embedded-postgres, boots in ~7s. No Docker needed.
- **mariadb** — real MariaDB 11.4.5 via MariaDB4j, boots in ~7s. No Docker needed. Already
  repaired on this machine (`mariadb-fix.sh` documents why: the macOS-ARM64 artifact is not
  self-contained). If it ever fails with `Library not loaded: .../libpcre2-8.0.dylib`, run
  `bash mariadb-fix.sh` and retry. **Note its `sql_mode`** — MariaDB 11 defaults include
  `STRICT_TRANS_TABLES` and `ONLY_FULL_GROUP_BY`; probe the session's actual `sql_mode` and say so,
  because it changes what is legal and is a configuration decision legend-lite would have to own.

**Use your own filenames** (`probes-<yourslice>.tsv`, `out-<backend>-<yourslice>.tsv`) — several
agents share this directory concurrently. Never edit `Probe.java`, `pom.xml`, or `cp.txt`.

## Probe file format

TSV, one probe per line. `#` comments and blank lines skipped.

```
ID<TAB>CATEGORY<TAB>SQL
```

## Output format

```
ID<TAB>CATEGORY<TAB>STATUS<TAB>VALUE_OR_ERROR
```

`STATUS=OK` → executed; VALUE is the first cell of the first row rendered as
`JavaType:value` (or `<null>` / `<norows>`). `STATUS=ERR` → VALUE is the error message.

## Fixture — present on every backend, identical rows

```sql
dept(id INTEGER, name VARCHAR(50))                                   -- 3 rows: 1 Eng, 2 Sales, 3 Empty
emp (id, name, dept_id, sal DOUBLE PRECISION, hired DATE, mgr)        -- 5 rows; id=5 'eve' has NULLs
proj(id, emp_id, name, budget DECIMAL(12,2))                          -- 3 rows
```

`emp` row 5 (`eve`) carries NULL `dept_id`/`sal`/`hired` deliberately — use it to probe null
semantics. `dept` row 3 (`Empty`) has no employees — use it for empty-collection and anti-join
semantics.

**SQLite caveat:** SQLite has no DATE literal and no date type; its `hired` column holds TEXT
`'2020-01-15'`. That is a genuine finding, already recorded — do not treat it as a harness bug.

## METHOD — this is the part that matters

**Execution is the only evidence.** Documentation and your own memory are *claims to verify*. Where
docs and execution disagree, execution wins. This mirrors `docs/H2_BACKEND.md`, whose capability map
came from running ~200 probes against a real jar.

**Probe MULTIPLE CANDIDATE SPELLINGS per construct.** This is how the bucket is determined honestly:

| Bucket | Meaning | How you prove it |
|---|---|---|
| **A** native | works with the same spelling DuckDB uses | DuckDB spelling returns OK *and the same value* |
| **C** rendering override | works, but only under a different name/syntax | some candidate returns OK with the right value |
| **B** rewrite pass | needs a composite expression / different query shape | you construct one and it returns the right value |
| **D** impossible | no spelling and no construction works on any version | every candidate ERRs, and you say why |

So a single `SqlFn` should produce several probe rows: `LEVENSHTEIN.c1`, `LEVENSHTEIN.c2`, …

**Value equality is as important as OK/ERR.** A statement that parses on both backends but returns a
*different value* is a silent wrong answer — the worst defect class. Always compare your backend's
value against the DuckDB value for the same probe ID. Explicitly hunt for these:
rounding mode (banker's vs half-up), integer vs float division, NULL handling in `GREATEST`/`LEAST`
and in aggregates, string index base, empty-vs-null string, sort order of NULLs, and boolean
representation returned by the driver.

**Report the JDBC Java type too.** The `JavaType:` prefix in the output is load-bearing — a backend
returning `Integer` where DuckDB returns `Boolean`, or `byte[]` where DuckDB returns a JSON node,
is a `SqlDialect.normalize` row that must be written. H2 returning `byte[]` for JSON is the
precedent.
