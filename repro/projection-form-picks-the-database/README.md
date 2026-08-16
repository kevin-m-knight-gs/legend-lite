# The projection spelling decides which database runs the query

One model, one mapping, one seed, one date, one connection declaring `type: DuckDB`. Two
spellings of `project`, two answers:

| query | result |
| --- | --- |
| `->project(~[doy:x\|$x.doy])` — relation form | **3** |
| `->project([x\|$x.doy], ['doy'])` — TDS form | **155** |

`dayOfYear(2024-06-03)` is 155. The relation form returns 3 because DuckDB lowers `dayOfYear`
to `day()` (F35); the TDS form returns the right answer because it is not running on DuckDB.

`dialect.pure` in this directory is the whole experiment — run both services and compare.

## The corroborating evidence

`dayOfYear` is a good dialect marker but it is one data point. The direct evidence is that TDS
queries fail with **H2** exceptions:

    org.h2.jdbc.JdbcSQLSyntaxErrorException: Syntax error in SQL statement
    "RANK() OVER (PARTITION BY root.G ORDER BY NULL[*])"; expected "ORDER BY"

from a model whose only connection is `RelationalDatabaseConnection ... type: DuckDB`. H2 is
never mentioned anywhere in the file.

## Why this matters more than either bug it exposes

A test suite exists to check behaviour against the database the system will actually use. If
the projection spelling silently selects a different one, then:

- a TDS-form suite passes on H2 while production runs DuckDB, and every dialect-specific
  defect — F35, F37, F38, F39 — is invisible to it;
- the same query written the other way gives a different answer, with nothing in the model,
  the runtime or the connection to say why;
- coverage measured in "services that pass" overstates what has been checked, by an amount
  nobody can see.

This corpus was lucky. 185 of its 189 service queries use the relation form, so its DuckDB
findings are real. The four that do not are

    surface::PostValidatedService     73-service-surface.pure
    stress::F37_SubstringPure         77-substring-paths.pure
    stress::M1_TradeCanonical         94-fanout-services.pure
    stress::M2_CanonicalWithEnum      94-fanout-services.pure

and of those, `F37_SubstringPure` is deliberate — it is the in-memory half of F37 and is
*supposed* not to reach SQL.

## Reproduce

    python3 scripts/corpus/probe_tds.py

`concatenate` and `olapGroupBy` fail there with H2 syntax errors, which is how this was found:
a DuckDB-only corpus should not be able to produce an H2 diagnostic.
