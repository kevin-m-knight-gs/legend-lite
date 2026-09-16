# A qualified property that CONCATENATES fabricates a value on a broken chain

    $x.target.label('BBG')

where `label(vendor: String[1]) { $vendor + '/' + $this.name + ':' + $this.targetId }`
and `$x.target` lands on nothing, returns

    "BBG/:"

— the property's own body evaluated with every `$this.` component empty. Not null, not an
error: a string in exactly the right shape, built out of a row that is not there.

## Four cases, one failure

Three rows: one whose to-one navigation lands, one pointing at a target that does not exist,
one pointing at nothing at all.

| projection through the broken chain | result |
| --- | --- |
| a PLAIN property, `$x.target.name` | `null` — correct |
| a DERIVED property, `$x.target.twiceSize` | `null` — correct |
| a QUALIFIED property doing ARITHMETIC, `$x.target.scaled(100.0)` | `null` — correct |
| a QUALIFIED property doing CONCATENATION, `$x.target.label('BBG')` | **`"BBG/:"`** |

So it is not qualified properties, and it is not broken chains. It is specifically a
qualified property whose body concatenates: the arithmetic one over the *same absent object*
returns null correctly, because `null * 100.0` is null while `'' + '/' + '' + ':' + ''` is a
perfectly good string.

## Why it matters more than it looks

Every other case here is null, which a caller notices. This one produces a plausible
identifier — a vendor ticker, a composite key, a display label — for a row that does not
exist. It is the shape of value most likely to be used as a lookup key downstream, and the
one least likely to be checked.

## How it was found

Not by looking for it. A generated service projected
`$curve.benchmarkSeries.tickerOn('BBG')` over eight yield curves, three of which have no
benchmark series, and the corpus's oracle expected null for those three. The engine returned
`"BBG/:"`. The generator only reached that chain at all because a linked project had just
been pulled into the executable corpus, changing which navigations were in range.

## Reproduce

    python3 scripts/corpus/probe_qualified_broken_chain.py

      PASS    PlainThroughChain
      PASS    DerivedThroughChain
      FAIL    QualifiedTextThroughChain   actual [... "BBG/:" ...]
      PASS    QualifiedMathThroughChain
