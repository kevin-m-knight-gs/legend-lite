# An association cannot be navigated to a class mapped in a non-default schema

    meta::pure::router::store::routing::Void not supported!

`combo::C0` and `combo::Summary` are mapped in the same mapping, over the same database, and
joined by a plain foreign-key equality:

    Join Combo_Summary(COMBO_ROOT.ROOT_ID = analytics.COMBO_SUMMARY.ROOT_ID)

    Association combo::RootSummary
    {
       summary: combo::Summary[0..1];
       summarised: combo::C0[*];
    }

The only thing unusual about the target is that its table lives in the `analytics` schema
rather than `default`. A service projecting `rootId`, `summary.summaryId` fails during plan
generation with the message above — a router internal, naming no class, no association and no
schema.

## What is not the cause

- The join is a simple equality on a real column pair, not a general condition.
- The FK is seeded with real values (and, deliberately, one dangling and one NULL).
- The same class is queryable when it is the ROOT of a service — `CB_SchemaQualified` does
  exactly that and passes. It is reaching it by navigation that fails.

## Why it matters

A schema is how most warehouses separate a mart from its source tables, so "join from a table
in one schema to a table in another" is not an exotic shape. The failure arrives as a router
assertion rather than a diagnosable message, and only when a query navigates rather than
roots.

## Reproduce

The model is in the corpus — `64-combinations.pure` carries the join, the association and its
mapping. The service is not emitted, because a service whose plan cannot be generated fails
its whole batch. `scripts/corpus/combos.py:schema_reach_spec` builds it and is deliberately
not registered; call it to reproduce.
