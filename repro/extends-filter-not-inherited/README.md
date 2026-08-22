# A subtype set that `extends` a filtered set does NOT inherit the parent's `~filter`

    Class xf::CommonStock extends xf::Equity { ... }

    xf::Equity[xfEquity] extends [xfBase]: Relational
    { ~filter [xf::DB]XfEquityRows      // INSTRUMENT_TYPE = 'EQUITY'
      shareClass: ... }

    xf::CommonStock[xfCommon] extends [xfEquity]: Relational
    { ~filter [xf::DB]XfCommonRows      // INSTRUMENT_SUBTYPE = 'COMMON'
      votingRights: ... }

`CommonStock.all()` applies `INSTRUMENT_SUBTYPE = 'COMMON'` and **not**
`INSTRUMENT_TYPE = 'EQUITY'`. The child's filter replaces the parent's rather than composing
with it.

## The invariant it breaks

Six rows, one of them a BOND whose subtype column happens to say `COMMON`:

| query | returns |
| --- | --- |
| `Instrument.all()` (root set, no filter) | all six |
| `Equity.all()` | `I-EQ-COMMON`, `I-EQ-PREF`, `I-TRAP-EQ-NOSUB` — correctly excludes the bond |
| `CommonStock.all()` | `I-EQ-COMMON`, **`I-TRAP-BOND-COMMON`** |

`CommonStock extends Equity` in the model, so every `CommonStock` is an `Equity`. But
`CommonStock.all()` returns a row that `Equity.all()` excludes — a subclass instance that is
not an instance of its superclass. Nothing warns; both queries succeed.

The second case is worse because the columns are unrelated:

    Filter XfCallRows(XF_INSTRUMENT.PUT_CALL = 'CALL')

    xf::CallOption[xfCall] extends [xfOption]: Relational { ~filter XfCallRows ... }

`CallOption.all()` returns `I-TRAP-SWAP-CALL` — a row whose `INSTRUMENT_TYPE` is `SWAP`. A
swap is returned as a call option because it carries a `PUT_CALL` value, and `Option.all()`
returns nothing at all.

## Why it matters more than it looks

This is the standard shape for an instrument master: one wide table, a discriminator column,
and a set per subtype told apart by `~filter`. Under the composing reading each child filter
says only what it adds, which is how they read — `CiCommonStockRows` is
`INSTRUMENT_SUBTYPE = 'COMMON'`, with no mention of the type, because the type is the
parent's business. Under the actual behaviour every child filter has to restate its whole
ancestry, and a filter that does not is silently wrong rather than rejected.

It only ever bites on rows where the discriminators disagree — which are exactly the rows a
seed built to be tidy will not contain.

## If this is by design

Then the design is that a `~filter` must be self-contained, and the case to answer is that
nothing enforces it: `extends [parentSet]` inherits the parent's main table and its property
mappings, so inheriting everything except the filter is a special case that neither the
grammar nor any diagnostic mentions.

Reproduce with `scripts/corpus/probe_extends_filter.py`.
