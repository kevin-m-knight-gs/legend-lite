# A `REAL` column cannot be read; `FLOAT` and `DOUBLE` can

    Table T ( K VARCHAR(8) PRIMARY KEY, C_REAL REAL )

Any query touching that table fails before it runs:

    Execution error at (resource:/core_relational_duckdb/relational/typeConversion.pure
    line:59 column:12), "Match failure: RealObject instanceOf Real"

The failure is at table creation, not at projection, so a single `REAL` column takes down
every service that reads any table in the same file. The message names neither the column,
the table, nor the type as it was written.

## Thirteen types, one model each

`REAL` is the only one that fails. Its two near-synonyms do not:

| SQL type | result |
| --- | --- |
| `VARCHAR(20)` | passes |
| `CHAR(2)` | passes |
| `INTEGER` | passes |
| `SMALLINT` | passes |
| `BIGINT` | passes |
| `TINYINT` | passes |
| `DOUBLE` | passes |
| **`REAL`** | **Match failure: RealObject instanceOf Real** |
| `FLOAT` | passes |
| `DECIMAL(18,4)` | passes |
| `NUMERIC(20,2)` | passes |
| `DATE` | passes |
| `BIT` | passes |

One column per model, because a type that cannot be CREATED takes every other type in the
file with it — the first version of this probe declared all fourteen in one table and
reported nothing at all.

## Why this was not found earlier

Every one of the ~370 tables in this corpus used `VARCHAR`, `DOUBLE`, `INTEGER`, `DATE`,
`TIMESTAMP` or `BIT`. `REAL` is an ISO SQL type, accepted by the Legend grammar, and the
obvious declaration for a percentage; it had simply never been written.

## Workaround

`FLOAT` or `DOUBLE`. In DuckDB `REAL` and `FLOAT` are the same 4-byte type, so the
substitution costs nothing.

## Reproduce

    python3 scripts/corpus/probe_column_types.py
