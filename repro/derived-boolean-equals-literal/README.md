# A derived Boolean compared to a boolean literal generates invalid SQL

    ->filter({x | $x.isFinal == false})

over a class whose `isFinal` is a derived property fails at execution with

    java.sql.SQLException: Parser Error: syntax error at or near "="

The error comes from the database. The query compiles, the plan generates, and the SQL the
plan carries is rejected by the SQL parser — so nothing between writing the query and running
it says anything is wrong.

## What works, and what does not

Seven variants over the same five-row model, one derived property each of Boolean, Float and
String:

| filter | result |
| --- | --- |
| `$x.isFinal == false` | **Parser Error** |
| `$x.isFinal == true` | **Parser Error** |
| `$x.isFinal` | passes |
| `!$x.isFinal` | passes |
| `$x.doubled > 0.0` (derived Float) | passes |
| `$x.tag == 'FINAL!'` (derived String) | passes |
| `$x.status == 'FINAL'` (the derivation written out at the call site) | passes |

So it is not derived properties in filters, and it is not `==` in filters. It is exactly the
combination: a derived **Boolean**, compared with `==` to a boolean **literal**. Both
directions fail, so it is not the literal `false` either.

## The workaround

`!$x.isFinal` and `$x.isFinal`. They mean the same thing and they lower correctly.

The two are not interchangeable in general — Pure's truthiness for an ABSENT boolean is not
the same question as `== false` — so the substitution is only safe on a required property.

## Why this was not found earlier

The corpus had a thousand services and had never filtered on a derived property. Derived
properties were projected constantly, and projecting one exercises a completely different
path: the expression lands in the SELECT list rather than the WHERE clause, and the SELECT
path is fine.

That gap is not exotic. Adding a derived boolean and then asking for the rows where it is not
set is the most ordinary thing a user does with one.

## Reproduce

    python3 scripts/corpus/probe_derived_filter.py

Expected output:

      ERROR   BoolEqFalse     3 row(s) expected   Parser Error: syntax error at or near
      ERROR   BoolEqTrue      2 row(s) expected   Parser Error: syntax error at or near
      PASS    BoolBare        2 row(s) expected
      PASS    BoolNot         3 row(s) expected
      PASS    FloatCompare    2 row(s) expected
      PASS    StringCompare   2 row(s) expected
      PASS    Underlying      2 row(s) expected

In the corpus proper, `stress::MD7_UnrevisedPrintsEq` pins the failing form and
`stress::MD3_UnrevisedPrints` is the same query written the way that works.
