# Backend probe evidence

The raw evidence behind `SQLITE_BACKEND.md`, `POSTGRES_BACKEND.md`, `MARIADB_BACKEND.md`,
`BACKEND_PORTABILITY.md` and `DUCKDB_FUNCTION_COVERAGE.md`.

Those documents assert capability buckets and silent-divergence findings on the strength of
**executed probes, not documentation**. This directory is what makes that claim auditable: the
harness, the probe corpora, the raw per-backend results, and the detailed per-slice analyses the
summary docs compress.

**Measured 2026-07-31 / 2026-08-01.** 3,702 probe statements · 12,188 result rows · 5 engines.

---

## What is here

| | |
|---|---|
| `harness/` | a **standalone Maven project** — `src/main/java/Probe.java` (the runner), `pom.xml`, `HARNESS.md` (fixture + method), `mariadb-fix.sh`. Deliberately *not* a module of the root pom: it pulls five JDBC drivers plus two embedded-database binary distributions, and nothing in `core/` should depend on that. |
| `corpora/` | 28 probe files, 3,702 statements. TSV: `ID⇥CATEGORY⇥SQL` |
| `results/` | 81 result files, 12,188 rows. TSV: `ID⇥CATEGORY⇥STATUS⇥VALUE_OR_ERROR` |
| `fragments/` | the 8 per-slice analyses (3,312 lines) — **more detailed than the summary docs**, with the exact working construction for every `B` bucket |
| `duckdb-census/` | the DuckDB 1.5.0 catalog dump (2,941 rows) plus the census tooling |
| `databricks/` | `DbxTest.java` — standalone Databricks connectivity test, see §Databricks below |

**Read `fragments/` before implementing a dialect.** The summary docs compress each slice into a
paragraph; the fragment carries the per-function table and the SQL that was proven to work.

---

## Engines probed

| Backend | Version | How |
|---|---|---|
| DuckDB | 1.5.0.0 | `org.duckdb:duckdb_jdbc` — the **reference value for every probe** |
| SQLite | 3.47.1 | `org.xerial:sqlite-jdbc:3.47.1.0` |
| PostgreSQL | 18.4 | `io.zonky.test:embedded-postgres:2.2.2` — real server, ~7s boot, no Docker |
| MariaDB | 11.4.5 | `ch.vorburger.mariaDB4j:mariaDB4j:3.3.1` — real server, ~7s boot, no Docker |
| H2 | 2.4.240 | cross-check only; H2 is owned by `H2_BACKEND.md` and its own workstream |

No Docker, no external service, no root. Both embedded servers download a real binary and run it.

---

## Re-running

```bash
cd experiments/backend-probes/harness
export JAVA_HOME=~/jdk/jdk-21.0.11+10/Contents/Home
export PATH="$JAVA_HOME/bin:$HOME/jdk/apache-maven-3.9.9/bin:$PATH"
mvn -q compile dependency:build-classpath -Dmdep.outputFile=cp.txt

CP="target/classes:$(cat cp.txt)"
java -cp "$CP" Probe postgres ../corpora/probes-shapes.tsv /tmp/out.tsv
```

`<backend>` = `duckdb` | `sqlite` | `h2` | `postgres` | `mariadb`.

To reproduce a published finding, run the same corpus on two backends and diff column 4 — that is
exactly how every "silent value divergence" row in the docs was found:

```bash
for b in duckdb postgres; do java -cp "$CP" Probe $b ../corpora/probes-math.tsv /tmp/$b.tsv; done
paste /tmp/duckdb.tsv /tmp/postgres.tsv | awk -F'\t' '$4!=$8 {print $1": "$4"  vs  "$8}'
```

**macOS ARM64 + MariaDB:** the `mariaDB4j-db-macos-arm64` artifact is not self-contained — `mariadbd`
links a Homebrew `libpcre2-8.0.dylib` that need not exist. See `harness/mariadb-fix.sh` for the fix
and why `DYLD_FALLBACK_LIBRARY_PATH` does *not* work. Linux CI is unaffected.

---

## The method, in one paragraph

Every probe runs on **every** backend, so results are comparable cell-by-cell. The runner records the
first cell of the first row rendered as `JavaType:value`, or the error message. Two consequences,
both deliberate:

- **Values are compared, not just parse success.** Nearly every finding that matters is a statement
  that executes cleanly and returns the *wrong answer* — `regexp_matches` deleting rows on Postgres,
  `CAST('2020-01-15' AS DATE)` → `Integer:2020` on SQLite, `SELECT "name"` returning the string
  `'name'` on MariaDB. A sweep that only checks for exceptions finds none of them.
- **The Java type is recorded.** `SUM` over an integer column returns `BigInteger` on DuckDB, `Long`
  or `BigDecimal` on Postgres, `BigDecimal` on MariaDB and `Integer` on SQLite. That table *is* the
  `SqlDialect.normalize` specification.

Bucket definitions (**A** native · **C** rendering override · **B** rewrite pass · **D** impossible)
follow `H2_BACKEND.md` §2. A `D` was only recorded after several candidate spellings and
constructions were tried and failed; the fragments record what was attempted.

---

## Caveats

- **Denominators differ between studies** — 267 constructs for SQLite/Postgres, 207 for MariaDB, 256
  in `H2_BACKEND.md`. The partitions overlap imperfectly. Percentages are comparable in spirit;
  **the counts are not subtractable.**
- **Results are point-in-time**, tied to the exact driver and engine versions above. `sqlite-jdbc`
  in particular is *not* stock SQLite — it compiles in `SQLITE_ENABLE_MATH_FUNCTIONS` and registers
  ~21 `extension-functions.c` functions, so a different SQLite build will not reproduce every row.
- **Session configuration changes the answers.** The MariaDB results were taken at the default
  `sql_mode`; `ANSI_QUOTES` alone changes whether quoted identifiers work at all. `harness/HARNESS.md`
  and `MARIADB_BACKEND.md` §4 record what must be pinned.
- **`CLOUD_BACKENDS.md` has no evidence here** — it is desk research against vendor pages, not
  execution, and says so. See §Databricks for the one test that would start closing that gap.

---

## Databricks — the open question

`CLOUD_BACKENDS.md` concludes that Databricks Free Edition (which replaced the retired Community
Edition on 2026-01-01) is perpetual, needs no credit card, and includes a SQL warehouse — but **no
vendor page states whether JDBC is permitted on it.** That claim is inference, and it is marked
UNVERIFIED.

Half of it *is* verified by execution: `com.databricks:databricks-jdbc:3.4.2` resolves from Maven
Central, is Apache-2.0 with no click-through, and loads and registers in a JVM (driver 3.4, accepts
`jdbc:databricks://`). What is missing is an account.

```bash
cd experiments/backend-probes/databricks
curl -O https://repo1.maven.org/maven2/com/databricks/databricks-jdbc/3.4.2/databricks-jdbc-3.4.2.jar
javac -cp databricks-jdbc-3.4.2.jar DbxTest.java

java -cp .:databricks-jdbc-3.4.2.jar DbxTest              # offline checks only
java -cp .:databricks-jdbc-3.4.2.jar DbxTest <host> <httpPath> <token>
```

With credentials it runs a small probe set (`SELECT 1`, `current_version()`, CREATE/INSERT/SELECT,
`explode`, rounding) and prints per-statement OK/ERR — enough to settle both whether JDBC is allowed
and whether DML works on the free tier.
