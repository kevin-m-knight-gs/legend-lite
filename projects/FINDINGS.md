# What the project graph found

A single flat namespace cannot have a cross-project defect, so none of this was visible
before. These are grouped by whether they are about the ENGINE, about DEPENDENCY structure,
or about what a graph does to a MODEL — the last group being the one a single project cannot
produce at all.

## Engine and grammar

Each of these cost one project a failed compile, and each is now in `CONTRACT.md`.

| what | detail |
| --- | --- |
| boolean literal in a `Filter` | `Filter X(T.IS_ACTIVE = true)` → `Unexpected token 'true'`. A null test works. |
| `Integer * Float` | types as `Number`, not a subtype of `Float`. Needs `->toFloat()`. NOT symmetrical with `/`, which widens on its own. |
| `~filter` through a join | needs the database pointer on BOTH sides: `~filter [db]@Join \| [db]FilterRows`. |
| property on a subclass | cannot be mapped on the parent's set. The error names the property, not the set it was wrongly written in. |
| `~distinct` without a key | needs an explicit `~primaryKey` on the column being deduplicated. Keyed on the table's own key it collapses nothing, silently. |
| `~groupBy` | takes column REFERENCES, not expressions. A banded value must be stamped on the row to be grouped on. |
| `sum()` in a view | is nullable, so aggregate totals are `Float[0..1]` and arithmetic over them needs `->orElse(0.0)`. |
| an empty group | produces NO ROW at all, not a zero. A completeness report has to read that case from the parent. |
| an association end declared twice | as an end AND as a class property is a duplicate-property failure. Two projects wrote it that way first, having copied one that maps its edges as plain properties instead. |
| a constraint over `[0..1]` | does not type-check. The "is it set" rule has to be a derived property using `->isEmpty()`. |

Two things reported as engine limits turned out not to be, and are worth recording as
corrections rather than quietly dropping:

* `||` compiles. One project reported it failing; the failure was elsewhere in the same
  expression.
* A wrapped join condition compiles. I told the projects it did not — I was wrong, and the
  one-line rule is house style because the CORPUS's own reader refuses one.

## Dependency structure

* **The include diamond closes.** Reaching one store or mapping by two routes — core-party
  arriving via both credit-core and collateral-core, core-instrument via both position-keeping
  and reference-data — resolves to one. It does not duplicate and does not error. Three
  separate projects verified this independently before relying on it, because no project had
  done it before and the failure mode would have been discovered thirty classes in.
* **Explicit set ids delete the default ones.** A project that writes
  `core_party::LegalEntity[cpLegalEntity]` no longer has `core_party_LegalEntity`, so every
  downstream reference must name the explicit id. This makes each project's MANIFEST
  load-bearing rather than documentation: an id is not derivable from the class name.
* **Depth is nearly free, size is not.** A project with an 8-project closure compiles in
  1706ms against 1255ms for one with no dependencies, and most of that gap is the extra
  source rather than the extra hop. Parse tracks bytes; compile does not — 4.5x the source
  costs 1.25x the compile.
* **A transitive dependency is visible and must not be named.** Everything in the closure is
  on the classpath, so referring to a project you did not declare COMPILES. Several projects
  reported deliberately not naming `core_types::CtCurrency` and using a String instead, for
  exactly this reason. `check.py` catches it by compiling each project against its declared
  closure and nothing more — which is the only reason the discipline is checkable at all.

## What a graph does to a model, which one project cannot show

* **Two projects independently modelled a netting set.** `exposure-agg` has `ExaNettingSet`
  as the measurement-side grouping key; `legal-netting` has `NetSet` as the closure of one
  master agreement. They meet on `nettingSetId` and neither restates the other's numbers, so
  both compile and both are defensible. Nothing in the graph forces a decision, and nothing
  would have raised the question inside a single project — the concept only splits once two
  teams own two halves of it. Recorded rather than resolved: picking an owner is a modelling
  decision, not a compile error.
* **A project adds properties to classes it does not own.** A cross-project association end
  puts a property on the dependency's class, so `trading::Trade` now carries `amendments` from
  trade-capture, `lifecycleAmendments` from trade-lifecycle and `executionBookings` from
  order-execution. Those names are a shared namespace nobody owns. Three projects reported
  checking the others' MANIFESTs before naming an end, which works only because they thought
  to look.
* **Two valid styles for the same edge, and they cannot be mixed.** A project may model a
  relationship as an `Association` with mapped ends, or as a plain property over a join in the
  set implementation. Both compile and both are used here. What does not compile is declaring
  one property BOTH ways — which is what happens when a project copies the shape of a
  dependency that chose the other style. Two projects hit this, and neither could have known
  without reading the other's source rather than its manifest.
* **The same column is two widths on either side of a join.** `INSTRUMENT_ID` is `VARCHAR(60)`
  in position-keeping and `VARCHAR(20)` in valuation-core; the join across them compiles.
  Whichever side is narrower is the one that truncates, and nothing says so at compile time.
