# tools/iq-oracle: SQL Anywhere 17's answers for the `#IQ{...}#` island

The `#IQ{...}#` island (docs/IN_FLIGHT.md, the tenth line) is judged against an oracle. No SAP IQ server can run
here, so the oracle is SQL Anywhere 17. IQ uses SQL Anywhere as its front end and catalog store, and two databases
approximate IQ's settings. This tool runs every parity case on SQL Anywhere and snaps the answers into
`core/src/test/resources/iq-parity/iq-answers.jsonl`.

**CI never runs SQL Anywhere.** The IQ parity lanes judge each case against the committed answers file, and they
fail a case that has no answer (UNSNAPPED), whose answer predates its SQL (STALE), or an answer whose case is gone
(GONE). Only a person runs the live check (`--check`), locally on a Mac with SQL Anywhere installed: after editing
cases or the schema, after changing this tool, and before landing a change to the answers file.

## Prerequisites

- **SQL Anywhere 17, x86_64**, at `/Applications/SQLAnywhere17`. The answers were snapped on 17.0.11.7254, and each
  answer records the build it came from. Each run sources `System/bin64/sa_config.sh`; `SA_CONFIG` overrides that
  path.
- **Rosetta 2** on Apple silicon. SQL Anywhere's client library (`libdbcapi_r.dylib`) is x86_64 only, so the client
  must run as an x86_64 process.
- **The system's universal Python 3**, `/Library/Developer/CommandLineTools/usr/bin/python3`, from the Xcode Command
  Line Tools. `snap.sh` runs it as `arch -x86_64 … -I`, which is isolated (no site packages, no `PYTHON*`
  variables). `snap.py` needs only the standard library and runs on Python 3.9. `IQ_ORACLE_PYTHON` overrides the
  path; the interpreter must be able to run as x86_64.

## Commands

```sh
tools/iq-oracle/snap.sh --write               # run every case, rewrite iq-answers.jsonl
tools/iq-oracle/snap.sh --check               # run every case, compare with iq-answers.jsonl
tools/iq-oracle/snap.sh --check --only 'lite/basics/*'   # a glob on the key (lite/<file>/<id>) or the bare id
tools/iq-oracle/snap.sh --write --only where_string_equality_and_case
bazel run //tools/iq-oracle:snap -- --check   # the same, through Bazel (it runs the checkout's own snap.py)
```

`--verbose` logs the setup. `--repo DIR` points the tool at another checkout's `iq-parity` directory and its
`tools/iq-oracle/known_crashers.txt`.

- **`--write`** writes the answers for the selected cases. It keeps the other cases' committed answers and drops any
  answer whose case is gone or skipped. The output is sorted and byte-stable: writing twice gives the same file.
- **`--check`** reports one line per finding, then exits 1 if there was any:

| Finding | Meaning |
|---|---|
| `DRIFT` | SQL Anywhere's answer now differs from the committed one. The line names the fields that differ (`rows`, `columns`, `error`, `sa_build`, `db`). `DRIFT meta` means the databases, server flags or profiles changed. |
| `UNSNAPPED` | A case (or one of its profiles) has no committed answer. |
| `STALE` | The committed answer's `sqlSha` no longer matches the case's SQL, its profile, its `decision` and `expect`, its `params`, its `settings`, or `schema.yaml`. |
| `GONE` | A committed answer has no live case: the case was deleted, renamed, or is now skipped. |
| `CRASH` | The case took the server down twice. List it in `known_crashers.txt` or mark it `sa_defect`. |
| `FAILED` | The tool could not snap the case, for example because the timestamp re-fetch came back with a different shape. |

A run takes about ten seconds; most of that is SQL Anywhere starting its TCP listener. Exit codes:

| Code | Meaning |
|---|---|
| 0 | clean |
| 1 | findings |
| 2 | a bad file or setup, such as an unknown profile, a case key the reader doesn't know, a statement that isn't a SELECT, or a database that reports unexpected creation settings |

## What a run does

1. **Creates two databases afresh** in a temporary directory (and one more for each combination of database
   properties the cases' settings need: [Settings](#settings)).
   - `lite_iq.db`, the IQ-like store: `dbinit -c -b -z ISO_BINENG`, then `PUBLIC.precision = 126` and
     `PUBLIC.scale = 38`. This gives a case-sensitive, blank-padded database with a binary ISO-8859-1 collation and
     IQ's sizes for an unqualified NUMERIC.
   - `lite_sa.db`, plain `dbinit`: SQL Anywhere's defaults.

   `dbinit` picks its default collation from the locale, so the tool pins `en_US.UTF-8`, which gives UTF8BIN. Each
   database's creation settings are read back and checked: collation, charset, case sensitivity and blank padding.
   They are recorded in the answers file's first line.
2. **Starts its own server**:
   - The engine is named `iq_oracle_<pid>`.
   - It listens on 127.0.0.1 at a free port and ignores broadcasts.
   - `-sf all` disables every secured feature: no file access, shell, mail, web or external calls.

   Every connection names this server and this port, so the tool never reaches another server. It uses TCP rather
   than shared memory because a shared-memory client waits forever on a server that died, so a crash would look
   like a slow query.
3. **Seeds both databases** from `schema.yaml`.
4. **Runs each case** on one connection per profile.
   - The profile's options are set as temporary options, and each one is read back to confirm it took.
   - `request_timeout` is 15 seconds.
   - Only the case's SQL runs. It must be a single `SELECT` or `WITH` (or start with a parenthesis) and must contain
     no `;` anywhere but after each of the `SET` statements it may begin with. Each case is followed by a `ROLLBACK`.
   - A case with `settings`, or whose SQL begins with `SET` statements, runs on a connection of its own instead
     ([Settings](#settings)).
5. **Recovers from a crash.** When the connection is lost (SQLCODE -101, -308, -85, -100, -832 or -1068, or the
   server process exits), the tool rebuilds the server and databases and retries the case once. A second loss is
   reported as a CRASH.
6. **Cleans up.** It stops the server and deletes the directory on exit, including on Ctrl-C.

## Profiles

A case names the client profile it runs under. The profile chooses the database and the options a client of that
kind would have after connecting:

| Profile | Database | Options set after connecting |
|---|---|---|
| `iq16-default` | `lite_iq` | none: the database's defaults, as an embedded-SQL or dbcapi client sees them |
| `iq16-odbc` | `lite_iq` | what IQ's ODBC driver sets: `date_order` ymd, `date_format` yyyy-mm-dd, `time_format` hh:nn:ss, `timestamp_format` yyyy-mm-dd hh:nn:ss.ssssss, `timestamp_with_time_zone_format` with the offset. The isolation level the driver also passes is the application's choice, so it stays at the default. |
| `iq16-tds` | `lite_iq` | what a TDS login gets from the login procedure: `ansinull` Off, `tsql_variables` On, `ansi_blanks` On, `chained` Off, `quoted_identifier` Off, `allow_nulls_by_default` Off, `on_tsql_error` Continue, `isolation_level` 1, `date_format` YYYY-MM-DD, `timestamp_format` YYYY-MM-DD HH:NN:SS.SSS, `time_format` HH:NN:SS.SSS, `date_order` MDY, `escape_character` Off |
| `sqla17-default` | `lite_sa` | none |
| `sqla17-tds` | `lite_sa` | the TDS set above |

The exact values are in `PROFILES` in `snap.py` and in the `meta` line of the answers file.

## Settings

IQ island, 2026-10-14 (the options framework, docs/IQ_SQL_STUDY_2026_10_13.md §9.17). A case's `settings` are IQ's
settings by IQ's own names, each given at a level, as Pure's tagged values will give them: `database.<name>=<value>` (a
Database element's: a database property, an option's PUBLIC value, the clients' `CommProtocol`) and
`query.<name>=<value>` (the enclosing function's: the connection's temporary value), separated by commas, a value quoted
(`''` a quote in it) or bare. The Java harness gives them to `IqSettings.resolve` and translates the case's SQL under
the result. This tool runs such a case, and a case whose SQL begins with `SET` statements, on a connection of its own:

1. **The database** comes from the database properties given for the database, over the profile's base:
   `CaseSensitive`, `BlankPadding` (`On`/`Off`), `Collation` (`ISO_BINENG` or `UTF8BIN`) or `CharSet`
   (`ISO_8859-1:1987` or `UTF-8`, which picks the other). The base's own combination is `lite_iq` or `lite_sa`; any
   other is a database of its own, `lite_<iq|sa>_<cs|ci>_<bp|nobp>_<latin1|utf8>`, made by `dbinit` with `-c`, `-b`
   and `-z <collation>` (with IQ 16's PUBLIC precision 126 and scale 38 on the IQ base), seeded like the others, and
   checked for the properties it must report. The tool makes every database the live cases need, whichever cases a run
   selects, and lists each in the answers file's `meta` line. `AccentSensitive` other than `Off` is refused: the
   databases are binary-collated.
2. **The login** is the profile's, or the database's `CommProtocol`'s: `'TDS'` a TDS login's options, `'CmdSeq'` none.
3. **Every other setting**: a database-level option `SET OPTION PUBLIC.name = 'value'`, a query-level one (a database
   property's too, which is then SQL Anywhere's error) `SET TEMPORARY OPTION name = 'value'`, after the login's. The
   first error is the case's answer.
4. **The case's variables**, then **its `SET` statements**, each run on the connection in turn (the case's SQL cut at
   its statement separators), then its `SELECT`. The first error is the answer.
5. **The answer's DATE and TIMESTAMP columns** are fetched again at `YYYY-MM-DD` and six places wherever the
   connection's own `date_format` or `timestamp_format`, read from it, shows them otherwise.
6. **Every option the case set is restored**: the catalog's `SYSOPTIONS` (the user's and PUBLIC's values, and any
   option a `SET OPTION PUBLIC.x` made) as it was before the case, a temporary PUBLIC value reset.

The `sqlSha` of a case with settings has them as one more part, `settings=<text>`, after its params'.

## The files

### `iq-parity/schema.yaml`

The tables every case reads by bare name. The YAML is the subset `SqlParity.Yaml` reads, so the Java harness reads it
too:

```yaml
tables:
  - name: t
    columns:
      - {name: id, type: integer}
      - {name: nm, type: 'numeric(10,2)'}
    rows:
      - [1, '12345678.99']
      - [2, null]
```

- **Types** use IQ's names: `smallint`, `integer`, `bigint`, `tinyint`, `numeric(p,s)`, `decimal(p,s)`, `double`,
  `real`, `float`, `char(n)`, `varchar(n)`, `date`, `timestamp`, `bit`. Quote a type that contains a comma.
- **Values:**
  - Integers and bits are plain.
  - Every other value is quoted text, and the column's type reads it. A decimal or a double therefore keeps exactly
    the digits written. A plain decimal is refused.
  - `null` is NULL.
- **Columns** all allow NULL.
- **Text** must fit ISO-8859-1, `lite_iq`'s charset.

### `iq-parity/lite/*.yaml`

The cases. A `tests:` list of one-line maps:

```yaml
tests:
  - id: where_string_equality_and_case
    sql: "SELECT id, vc FROM t WHERE vc = 'hello' ORDER BY id"
    profile: iq16-default, sqla17-default
```

| Key | Meaning |
|---|---|
| `id` | Letters, digits, `_`, `.` or `-`. The case's key is `lite/<file path without .yaml>/<id>`, matching `SqlParity.key`. |
| `sql` | The island's body as a user writes it, on one line. In double quotes, a backslash in the SQL is written `\\`. |
| `profile` | One profile or a comma-separated list. Default: `iq16-default`. Each profile gets its own answer. |
| `engine` | `iq`, `sa` or `auto`, for the translator. The oracle runs the same either way. |
| `skip` | A reason. The case is not run and gets no answer. |
| `sa_defect` | A reason SQL Anywhere's answer is wrong or unusable here. Treated like `skip`. |
| `decision` | `D-xx`: the case is judged by that provisional decision rather than by SQL Anywhere. The oracle still snaps SQL Anywhere's answer as evidence. |
| `expect` | With `decision`: the decided rows as one line of JSON, a list of rows of text cells or null, e.g. `[["1"], [null]]`. |
| `settings` | IQ island, 2026-10-14: IQ's settings by IQ's names, one line of `database.<name>=<value>, query.<name>=<value>, ...`; see [Settings](#settings). |
| `params` | The values of the case's host variables (`@name`, `:name`), one line: `name = value; ...`, each value an integer (`5`), a float (`1.5`), a decimal (`0.50D`), a `'string'` (no quote or backslash inside), a date (`%2026-03-04`), a timestamp (`%2026-03-04T05:06:07.123456`), or a NULL of a type (`null:Integer`, `null:Float`, `null:String`, `null:StrictDate`, `null:DateTime`, `null:Decimal(38,2)`), as Pure writes them; names distinct without case. The Java harness lets each before the island (the variables the island reads; a NULL is an empty value, `[]->cast(@Integer)`). This tool makes each a real SQL Anywhere variable on the case's connection before it runs (`CREATE VARIABLE @name` of `BIGINT`, `DOUBLE`, `NUMERIC(38,s)`, `LONG VARCHAR`, `DATE` or `TIMESTAMP`, then `SET @name =` the value, a NULL left unset) and drops it after; the case's SQL runs as written but for `:name`, written `@name` (SQL Anywhere reads `:name` in dynamic SQL only as a placeholder a client binds: -188 with none). Under a TDS profile (`tsql_variables` On, where `@name` too is such a placeholder) the case runs with `tsql_variables` Off. IQ island, 2026-10-14 (review 6's m1 and M1): a column or an alias of a variable's name is read before the variable, never before a placeholder, so a case whose SQL names one spelled like a param (`"@lo"`, `[@lo]`) is rejected where it writes `:lo`, or where it runs under a TDS profile; rewrite it, or skip it (`skip:`). A value its type cannot hold is the case's answer: SQL Anywhere's error. IQ island, 2026-10-14 (review 5's M5): the tool wrote each value into the SQL as a constant before, which SQL Anywhere reads otherwise in measured places (`x = @v` two-valued under ansinull Off, a name matched without case, a select-list alias before a variable, `TOP @n`). The answers file's `meta` line says so. |
| `nondeterministic` | A reason: the case tests a refusal or an error, so its SQL may leave the rows or their order to the plan. The Java harness's determinism lint (`IqParity.determinism`, IQ island, 2026-10-14, review 7) fails any other case whose SQL has a TOP, FIRST or START AT without an ORDER BY that reaches a unique column of its source (each table's `id`), or a LIST without its own ORDER BY (but of a GROUP BY key or a constant); a case that opts out must not PASS, and one with nothing to opt out of may not. This tool snaps it as any case (its answer may be one plan's). |

Any other key is an error, so a misspelt key fails loudly.

### `iq-parity/iq-answers.jsonl`

One JSON object per line, sorted by key then profile, UTF-8, compact. The first line is `{"meta": …}`: the tool's
format version, the server flags, the pinned locale, each database's `dbinit` flags, PUBLIC options and measured
creation settings, and each profile's database and options. A Java reader skips the line without an `"id"`.

Every other line is one answer:

```json
{"id":"lite/basics/where_string_equality_and_case","profile":"iq16-default","db":"lite_iq",
 "sa_build":"17.0.11.7254","sqlSha":"…","columns":[["id","integer",0,0],["vc","varchar",20,0]],
 "rows":[["2","hello"]]}
```

or, when SQL Anywhere refuses the statement:

```json
{"id":…,"profile":…,"db":…,"sa_build":…,"sqlSha":…,"error":{"sqlcode":-143,"sqlstate":"52003","message":"Column 'nope' not found"}}
```

**`sqlSha`** is `sha256(utf8(sql) || 0x00 || utf8(profile) || 0x00 || utf8(decision) || 0x00 || utf8(expect) || 0x00 ||
bytes of schema.yaml)`, in lowercase hex (format 2; `decision` and `expect` are empty for a case SQL Anywhere judges;
a case with `params` has them as one more part, `utf8(params) || 0x00`, after `expect`'s, so editing them makes its
answer STALE too; a case with `settings` one more, `utf8("settings=" settings) || 0x00`, after those).
`sql`, `decision` and `expect` are the YAML scalars after unquoting. So editing a decided case's rows makes its answer
STALE, as editing its SQL does.

**`columns`** is one `[name, type, precision, scale]` per column, as SQL Anywhere describes the result.
- `name` is the column's name as SQL Anywhere derives it, including the expression text for an unnamed column.
- `type` is SQL Anywhere's native type: `integer`, `smallint`, `bigint`, `numeric`, `double`, `char`, `varchar`,
  `date`, `timestamp`, `bit`, and so on.
- For `numeric`, precision and scale are the declared ones. For `char`/`varchar`, precision is the length in
  characters (the client uses the database's own charset). For `date`/`timestamp`, precision is the length of the
  text.

**`rows`** keeps SQL Anywhere's row order, with every cell as text or `null`:

| Type | How a cell is written |
|---|---|
| integers, bits | decimal digits |
| numeric | SQL Anywhere's own text, with no leading zero before the point: `.01`, `-.50` |
| double | the shortest text that reads back as the same double (`1.5`, `0.0`, `-0.1`, `1e+300`; `NaN`, `Infinity`) |
| date | in the profile's `date_format` (every profile has year-month-day) |
| timestamp | always `YYYY-MM-DD HH:NN:SS.SSSSSS`. SQL Anywhere sends a timestamp to the client as text in `timestamp_format`, which shows three fractional digits by default. So a TIMESTAMP column is fetched a second time at six digits, and only that column's cells are taken from the second fetch. |
| char, varchar | as stored, with SQL Anywhere's trailing blanks |
| binary | `0x` and hex |

## Known crashers

`known_crashers.txt` lists case keys whose SQL crashes SQL Anywhere 17. They are skipped under every profile. Prefer
`sa_defect: <reason>` on the case itself; the list exists for cases generated in bulk.

## What the oracle cannot say

SQL Anywhere is not IQ. It judges only behaviour the two share, and the island's `engine` flag says which rules a
query follows. Where the IQ engine is documented to differ, a case names its decision (`decision: D-xx`) instead of
trusting SQL Anywhere. One example: SQL Anywhere returns a CHAR(n) value unpadded even in a blank-padding database,
while IQ pads CHAR(n) to n.
