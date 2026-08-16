# `firstDayOfWeek` renders a StrictDate property as a DateTime

`core/src/test/resources/stress/78-firstday-types.pure` declares four properties as
`StrictDate[0..1]` and computes each from the same DATE column:

    "mon" : "2024-06-01"
    "qtr" : "2024-04-01"
    "yr"  : "2024-01-01"
    "wk"  : "2024-06-03T00:00:00.000000000+0000"

The seed date is a Monday, so `firstDayOfWeek` returns its input unchanged. The value is not
in question — only the rendering is.

## Why four columns

One column returning a timestamp would be readable as a convention: perhaps `StrictDate`
serializes with a time component in relational execution. Three siblings on the same row
rendering as bare dates removes that reading. The declared type is not deciding the shape of
the output; the SQL expression's own result type is.

## Cause

| function | lowering | DuckDB result type |
| --- | --- | --- |
| `firstDayOfMonth` | `date_trunc('month', %s)` | DATE |
| `firstDayOfQuarter` | `date_trunc('quarter', %s)` | DATE |
| `firstDayOfYear` | `date_trunc('year', %s)` | DATE |
| `firstDayOfWeek` | `date_add(%s, to_days(cast(-(isodow(%s)-1) as integer)))` | TIMESTAMP |

`date_add` promotes to TIMESTAMP, and the promotion travels out through a property that says
`StrictDate`.

## Suggested fix

Cast the result back, as the siblings' types imply:

    format='cast(date_add(%s, to_days(cast(-(isodow(%s)-1) as integer))) as date)'

## Related

F24 — the same DateTime serializes with a UTC offset through TDS projection and without one
through graph fetch. Both findings say serialization follows the execution path rather than
the declared type.
