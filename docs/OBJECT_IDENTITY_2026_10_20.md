# Object identity in the product (2026-10-20)

**Status: design, for the user's approval.** The user's ruling of 2026-10-20: "lets fix identity properly that's a huge
product fix/win". This document says what identity is in Pure, what the product does today (measured), and how the
product gives every object its identity on every lane.

## 1. Pure's rule

- Every evaluation of `^C(...)` or `^$x(...)` makes a **new object**. `let a = ^C(...)` names one object; `$a`
  read twice is that one object twice.
- `eq(a, b)` is **identity**, always.
- `equal(a, b)` / `==`: a class with `<<equality.Key>>` properties compares **its keys alone**; a class without keys
  compares **identity**.
- Everything built on `equal` follows it: `contains`, `in`, `removeDuplicates`, `distinct`, `indexOf`, `union`,
  `intersection`, `difference` on objects.

## 2. What the product does today (measured on DuckDB, the plan route, 2026-10-20)

| Expression | Pure | Product |
|---|---|---|
| `let a = ^P(n=1); let b = ^P(n=1); eq($a, $b)` | false | **true** |
| `let a = ^P(n=1); let b = ^P(n=1); $a == $b` (no keys) | false | **true** |
| `[1, 2]->map(i \| ^P(n=1))->removeDuplicates()->size()` | 2 | **1** |
| `let a = ^P(n=1); [$a, $a, ^P(n=1)]->removeDuplicates()->size()` | 2 | **1** |
| `let a = ^P(n=1); [^P(n=1)]->contains($a)` | false | **true** |
| `[^Side(id='a', n=1), ^Side(id='a', n=2)]->removeDuplicates()->size()` (key `id`) | 1 | **2** |
| `[^Side(id='a', n=1)]->contains(^Side(id='a', n=2))` | true | true |
| `^Side(id='a', n=1) == ^Side(id='a', n=2)` | true | true (fixed earlier today: `withInstanceKeys` armed on the plan path) |

Why: an object is a struct of its properties and nothing else. The identity machinery exists
(`ClassLayouts.SYNTHETIC_ID`, `InstanceEquality`, `exec.InstanceIds`) but only the legacy executor's assertion judge
turns it on, and where it is off the equality arms **fall back to comparing the structs** (`InstanceEquality`: "value-lane
keyless shapes keep the generic rule"). That fallback is the bug (AGENTS.md invariant 4).

Even where it is on, the id is minted **per construction site, never per evaluation** (`InstanceIds`): every object one
`^P(...)` builds inside a `map` gets the same id, so they count as one object. Channel B passes the PCT's identity tests
only because those tests build each object at its own `let`.

## 3. The design

**3.1 Every object carries its identity.** Every class struct carries `__id` (and `__type`, its own class) on every lane:
the identity layouts (`ClassLayouts.layoutOf(ctx, t, true)`) become the only layouts. `__canon` stays where it is used.
The plan path, the legacy executor and the judges lower with the same layouts.

**3.2 The id is minted where the object is built, once per evaluation.** An object's id is its construction site's id
followed by the position of each iteration around the site, outermost first:

- a site evaluated once (a query's top level, a `let`): `'i7'`;
- a site inside a list lambda (`map`, `filter`, `fold`, `exists`, ...): `'i7#2'`, the 2 the element's position, from the
  lambda's second parameter, which the MIR and every dialect already have (`SqlExpr.Lambda` with two parameters:
  DuckDB's `list_transform`, Trino's `zip_with` over the positions, Postgres's `WITH ORDINALITY`, ClickHouse's
  `arrayEnumerate`, MariaDB's, SQLite's and MongoDB's own forms);
- nested lambdas: `'i7#2.3'`.

The site id is a plan-time constant from the node (the inliner's splicing keeps a `let`-bound constructor one node, so
`$a` twice is one object); the positions are the database's. The id is deterministic: a value the SQL reads twice
(a spliced `let`, a subquery written twice) has the same id in both readings, which a database-generated `uuid()` would
not give (the reason `InstanceIds` gave for site ids, kept).

**3.3 Every operation built on equality reads it.** `InstanceEquality` owns the relation and loses its fallback:
`eq` compares `__id`; `equal`/`==` compares a keyed class's keys and a keyless class's `__id`; a static classifier
mismatch is false. `contains`, `in`, `removeDuplicates`, `distinct`, `indexOf`, `union`, `intersection` and `difference`
on objects go through it (today `removeDuplicates` and `distinct` compare whole structs). A shape it cannot claim is
refused by name, never compared as a struct.

**3.4 No output shows the synthetic fields.** The text carrier already skips them (`LiteralSpelling.synthetic`); the JSON,
CSV and TDS writers and the typed rows must too (a gate: every output of an object, every lane).

## 4. Open questions for the user

1. **An object built per row of a relation** (`$rel->map(r | ^P(name = $r.name))`): a row has no position that survives
   the relation being read twice. Proposal: the row's position in the relation's own order (`row_number()` over its sort,
   or over its source's order where it has none), and an identity comparison across two readings of an unordered
   relation refused by name. Alternative: refuse identity on such objects by name until a user needs it.
2. **Objects read from a store** (`Person.all()`): legend-engine gives each row's object the mapping's primary key as its
   key information (`getHiddenPayload().pk`). Proposal: a later phase, measured against the engine first; until then
   identity on a store-read keyless object is refused by name where it is compared, not compared as a struct.

## 5. Order of work

1. The layouts carry `__id`/`__type` on every lane; outputs skip them; nothing reads them yet (a pure no-op on answers,
   gated by both corpus lanes and every PCT lane).
2. The id per evaluation (site plus positions).
3. `InstanceEquality` claims every operation, its fallback deleted; refusals by name for 4.1 and 4.2 until decided.
4. Tests: the table in §2 as core tests on every dialect; channel B's identity tests on the plan route (step 10).
5. Register rows (SEMANTICS_REGISTER) for anything that stays different from Pure.

The PCT's channel A identity tests stay pinned as a harness limit: the reference interpreter's own objects never cross the
wire; channel B judges the product's identity.
