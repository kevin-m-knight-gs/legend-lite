# Legacy routes as composition — the worked example (2026-09-13)

**Status.** Design, agreed in conversation 2026-09-13 after the B3 arc audit. Not built.
Replaces the plan's B3.5 idea and the "generate under the includer and compare" attempt
(never committed; reverted from the working tree after the census below). The next slice builds this; the measurements it needs are in §7.

**The one-sentence problem.** A union learns who navigates to it (it scans the mapping for
routes into its members and publishes a key named after each navigator), so an included
mapping's union has to be regenerated for every mapping that adds a navigator. That is the
same disease B3.1b cured on the navigator's side (Firm naming Person's sets), in the other
direction, and it is the whole reason the include re-synthesis block exists.

**The one-sentence fix.** The navigator composes the target set's own relation function
per route, through `legacyNavigate`, with the join written as the author wrote it; the
union is a plain stack; an include is a call. Nobody publishes, nobody scans, nothing is
regenerated.

Every piece of syntax below is taken from `docs/MAPPING_CLEAN_SHEET.md`, the engine's
grammar tests (`~func`), or the native signatures in `Pure.java`. Two items were open and are
answered in §6.

---

## 1. The legacy DSL, as an author writes it today

### Store

```
###Relational
Database acme::db
(
  Table T1 (ID INTEGER PRIMARY KEY, NAME VARCHAR(100), FIRM_ID INTEGER, DEPT_ID INTEGER)
  Table T2 (ID INTEGER PRIMARY KEY, NAME VARCHAR(100), FID INTEGER, DID INTEGER)
  Table FIRM (ID INTEGER PRIMARY KEY, NAME VARCHAR(100))
  Table DEPT (ID INTEGER PRIMARY KEY, NAME VARCHAR(100))
  Table ADDRESS (ID INTEGER PRIMARY KEY, FIRM_ID INTEGER, PERSON_ID INTEGER)

  Join Firm_Person1 (FIRM.ID = T1.FIRM_ID)
  Join Firm_Person2 (FIRM.ID = T2.FID)
  Join Dept_Person1 (DEPT.ID = T1.DEPT_ID)
  Join Dept_Person2 (DEPT.ID = T2.DID)
  Join Firm_Address (FIRM.ID = ADDRESS.FIRM_ID)
  Join Address_Person1 (ADDRESS.PERSON_ID = T1.ID)
)
```

### Classes

```
###Pure
Class acme::Person { name: String[1]; }
Class acme::Firm { id: Integer[1]; name: String[1]; employees: acme::Person[*]; }
Class acme::Department { id: Integer[1]; name: String[1]; staff: acme::Person[*]; }
```

### Mapping A — Person twice, unioned; Firm reaches Person through one join per set

```
###Mapping
Mapping acme::A
(
  *acme::Person: Operation
  {
    meta::pure::router::operations::union_OperationSetImplementation_1__SetImplementation_MANY_(p1, p2)
  }
  acme::Person[p1]: Relational
  {
    ~mainTable [acme::db]T1
    name: [acme::db]T1.NAME
  }
  acme::Person[p2]: Relational
  {
    ~mainTable [acme::db]T2
    name: [acme::db]T2.NAME
  }
  acme::Firm: Relational
  {
    ~mainTable [acme::db]FIRM
    id: [acme::db]FIRM.ID,
    name: [acme::db]FIRM.NAME,
    employees[p1]: [acme::db]@Firm_Person1,
    employees[p2]: [acme::db]@Firm_Person2
  }
)
```

### Mapping B — includes A, adds Department, whose `staff` reaches into A's Person sets

```
Mapping acme::B
(
  include acme::A

  acme::Department: Relational
  {
    ~mainTable [acme::db]DEPT
    id: [acme::db]DEPT.ID,
    name: [acme::db]DEPT.NAME,
    staff[p1]: [acme::db]@Dept_Person1,
    staff[p2]: [acme::db]@Dept_Person2
  }
)
```

Where the links live: on Firm and on Department, and each names a column of Person's
tables. Everything else follows from that.

---

## 2. Mapping A, hand-typed in the function form

**Step 1 — one relation function per set** (the `~func` idea: the set's rows as a Relation,
before anything becomes an object; return types spelled as the clean-sheet doc spells them).

```
function acme::funcs::p1Rows(): Relation<(ID:Integer, NAME:String, FIRM_ID:Integer, DEPT_ID:Integer)>[1] = {|
  #>{acme::db.T1}#
}

function acme::funcs::p2Rows(): Relation<(ID:Integer, NAME:String, FID:Integer, DID:Integer)>[1] = {|
  #>{acme::db.T2}#
}
```

A set's filter goes here: `#>{acme::db.T1}# -> filter(r | $r.STATUS == 'ACTIVE')`.

**Step 2 — one projection per set.** Rows become Person instances. The author puts the link
key on the target as a mapping-local property (`+`, doc §4.2, Layer 5): in the clean form
the target owns its link.

```
function acme::funcs::p1Mapping(): acme::Person[*] = {|
  acme::funcs::p1Rows()
    -> map(r | ^acme::Person(name = $r.NAME->toOne(), +firmFk = $r.FIRM_ID))
}

function acme::funcs::p2Mapping(): acme::Person[*] = {|
  acme::funcs::p2Rows()
    -> map(r | ^acme::Person(name = $r.NAME->toOne(), +firmFk = $r.FID))
}
```

**Step 3 — Person is the two stacked** (doc E6; Pure's
`concatenate<T>(set1:T[*], set2:T[*]):T[*]`).

```
function acme::funcs::personMapping(): acme::Person[*] = {|
  acme::funcs::p1Mapping() -> concatenate(acme::funcs::p2Mapping())
}
```

**Step 4 — Firm navigates in model terms** (post-map `navigate`, doc §3.3). The predicate
sees the Firm instance and the Person candidate; no table of Person's appears.

```
function acme::funcs::firmMapping(): acme::Firm[*] = {|
  #>{acme::db.FIRM}#
    -> map(r | ^acme::Firm(id = $r.ID->toOne(), name = $r.NAME->toOne()))
    -> navigate(~employees: acme::Person.all(), {f, p | $p.firmFk == $f.id})
}
```

`acme::Person.all()` resolves through whatever mapping the query runs with.

**Step 5 — the binding table.**

```
Mapping acme::A
(
  *acme::Person:     Operation  { acme::funcs::personMapping },
   acme::Person[p1]: Relational { acme::funcs::p1Mapping },
   acme::Person[p2]: Relational { acme::funcs::p2Mapping },
  *acme::Firm:       Relational { acme::funcs::firmMapping }
)
```

Against §1: the union's key was born on the target side, by the author, once. Firm scans
nothing.

---

## 3. Mapping B, hand-typed, and what it shows

B includes A and adds Department. Department is B's own class, so its pipeline starts from
DEPT; what B borrows from A is Person, and that is the call. In the clean form B cannot reach
into T1 or T2 and cannot add a local to A's Person functions, so the author has exactly two
honest choices:

- A's Person already exposes a department key (public, or local) and B uses it:

```
function acme::funcs::departmentMapping(): acme::Department[*] = {|
  #>{acme::db.DEPT}#
    -> map(r | ^acme::Department(id = $r.ID->toOne(), name = $r.NAME->toOne()))
    -> navigate(~staff: acme::Person.all(), {d, p | $p.deptFk == $d.id})
}

Mapping acme::B
(
  include acme::A,
  *acme::Department: Relational { acme::funcs::departmentMapping }
)
```

- or A does not expose it, and B maps Person itself with the key it needs.

There is no third way. The clean form forbids what §1's Mapping B did (reaching into another
mapping's tables). One sentence the clean-sheet doc still owes: whether a `+local` declared
in A is visible to a function bound in B after `include` (today it only says other mappings
cannot reach a class's locals through `Class.all()`).

Where a B function does start from an A function: when B redefines a class A maps (legacy
`extends`). Then B's Firm is A's Firm function widened, not a fresh scan of FIRM. That
replaces today's extends flattening and is its own slice.

---

## 4. A route through a middle table, hand-typed

The doc's preferred shape (Layer 5, multi-hop): model the middle and split into two
single-hop links; traversal is then ordinary and no function holds a middle row for
somebody else.

```
Class acme::Address { id: Integer[1]; firm: acme::Firm[1]; person: acme::Person[1]; }

function acme::funcs::addressMapping(): acme::Address[*] = {|
  #>{acme::db.ADDRESS}#
    -> map(r | ^acme::Address(id = $r.ID->toOne(), +firmFk = $r.FIRM_ID, +personFk = $r.PERSON_ID))
    -> navigate(~firm:   acme::Firm.all(),   {a, f | $a.firmFk == $f.id})
    -> navigate(~person: acme::Person.all(), {a, p | $a.personFk == $p.id})
}
```

---

## 5. What the normalizer generates from §1 so it reads like §2

The legacy author put the link on the navigator. The translation cannot invent `+firmFk` on
Person's sets without scanning Firm, and that scan is the coupling that breaks includes. So
the generated form keeps the link where the author put it and uses the one sanctioned legacy
escape, `legacyNavigate`, whose four-argument signature is (Pure.java):

```
legacyNavigate<S,C,T,Z>(
  rel:     Relation<S>[1],
  target:  FuncColSpec<{->C[*]}, Z>[1],            -- ~alias: <class extent thunk>
  tgtRows: Relation<T>[1],                          -- the target's rows the condition reads
  cond:    Function<{S[1], T[1] -> Boolean[1]}>[1]  -- source row, target row
): Relation<S+Z>[1]
```

It widens the source rows with a slot of target instances, materialized through the target's
mapping, using a condition over the source row and the target's rows. Emitted only by the
normalizer.

**Per set — the same function a set gets today**, one per set, the resolver splitting it at
its `map` terminal into the relation part and the projection (that split already exists:
`ClassSources` loads a binding "split at the `map(row|^Class(...))` terminal"):

```
function acme::A$p1(): acme::Person[*] = {|
  #>{acme::db.T1}# -> map(r | ^acme::Person(name = $r.NAME->toOne()))
}
function acme::A$p2(): acme::Person[*] = {|
  #>{acme::db.T2}# -> map(r | ^acme::Person(name = $r.NAME->toOne()))
}
function acme::A$Person(): acme::Person[*] = {|
  acme::A$p1() -> concatenate(acme::A$p2())
}
```

The union is a stack and nothing else: no key columns, no scan.

**Per property — ONE navigate step carrying every route the author wrote** (decided: a route
list). Each route names its target rows and its join expression exactly as written, over
both rows; its target SET rides the stamped per-route pin, never the Pure. Matches from any
route fill the slot; a source row with no match through any route is kept once. Today's
four-argument form is the one-route case of this.

```
function acme::A$Firm(): acme::Firm[*] = {|
  #>{acme::db.FIRM}#
    -> legacyNavigate(~employees: getAll(acme::Person),
         [ route(#>{acme::db.T1}#, {f, r | $f.ID == $r.FIRM_ID}),
           route(#>{acme::db.T2}#, {f, r | $f.ID == $r.FID}) ])
    -> map(r | ^acme::Firm(id = $r.ID->toOne(), name = $r.NAME->toOne(), employees = $r.employees))
}
```

A composite or non-equality join goes into the route's lambda unchanged.

**Mapping B, generated.** Department composes the same sets with its own joins. A's functions
are not touched, copied, regenerated or compared.

```
function acme::B$Department(): acme::Department[*] = {|
  #>{acme::db.DEPT}#
    -> legacyNavigate(~staff: getAll(acme::Person),
         [ route(#>{acme::db.T1}#, {d, r | $d.ID == $r.DEPT_ID}),
           route(#>{acme::db.T2}#, {d, r | $d.ID == $r.DID}) ])
    -> map(r | ^acme::Department(id = $r.ID->toOne(), name = $r.NAME->toOne(), staff = $r.staff))
}

Mapping acme::B
(
  include acme::A,
  *acme::Department: Relational { acme::B$Department }
)
```

No Person entry, no Firm entry in B: under B, `acme::Person.all()` and `acme::Firm.all()`
resolve through the include to A's functions.

**The chain, generated.** The middle table joins into that route's rows, on the navigator's
side of the call; the last join's expression is the condition. The middle rows sit inside one
route, so a second route with its own middle table cannot multiply against them — the rows
the engine produces by rooting the arm at the middle table (B3.2's receipts), reached by
composition.

```
    -> legacyNavigate(~employees: getAll(acme::Person),
         [ route(#>{acme::db.T1}# -> join(~addr: #>{acme::db.ADDRESS}#, {r, a | $r.ID == $a.PERSON_ID}),
                 {f, x | $f.ID == $x.addr.FIRM_ID}) ])
```

**A class this mapping does not map.** A's Product with `synonyms: @Product_Synonym`
becomes a route whose target set is Synonym's in the queried mapping. Under A there is none
and the query fails plainly at demand; under B, which maps Synonym, it resolves. No drop rule.

---

## 6. The two questions that were open, answered from the code

**How a route names "set p1 as it exists in the queried mapping".** Not in Pure. The
generated call says `getAll(acme::Person)`; the set pin is a fact stamped on the compiled
mapping (`MappingDefinition.routedTargetSets`), and `ClassSources.getForNav` uses it to pick
the set-discriminated binding under the mapping being queried, so an include with a store
substitution resolves to the includer's copy of `p1`. Today the pin is per navigation head
("the head's sole routed set"); with several routes per property it becomes per route. A
keying change in a fact, not a new mechanism, and never a set id inside generated Pure.

**The `[*]` slot.** `NavigateChecker.legacy` types the pipeline `legacyNavigate` slot as ONE
instance per output row (`Form.PRE_MAP`): it multiplies rows, one per match. Hence one step
per property carrying all its routes (§5), never one step per route: two steps on the same
rows would cross-multiply route 1's matches with route 2's, and stacking two widened
relations would duplicate every source row that matches nothing.

---

## 7. What this deletes, and what to measure before building

**Deletes.** The union's link-key publication and the scan of navigators behind it
(`publishLinkKeys`, `collectInboundRouteKeys`); the key naming rules (`navigatingIdentity`,
`routeSignature`, `routeShapes`, `linkKeyName`); the include re-synthesis block and every
criterion it ever had; the push-into-arm chain machinery (`inboundArmSteps`, `LiftChain`,
`chainsSink`) and the union's lifted-navigation threads; the B3.3 drop rule
(`classTypedButUnmapped`); the union widening on the query side that served published keys
(`widenUnionMember`, `widenForCondition` consumers). The union driver keeps: the stack, the
shared-table merge of same-table members, the own primary-key threads.

**Homework done 2026-09-13 (census of the DuckDB lane, probe removed).** 275 routed
navigations across 87 mappings: 129 with one route, 93 with two, 12 with three, 12 with four,
23 with five, 6 with twenty-one (the metamodel hierarchy); 206 target a union, 69 a single set
reached by a set-pinned route; 77 have a chained route (58 with a shared prefix, 19 per-arm);
every routed member is `Relational` — no `Pure` or `~func` member is routed to in the corpus,
so the mixed cases have no corpus judge and need a witness. Judges of the build: every
`union::` family (`multipleChainedJoins` ×20, `unionMappingWithJoinSequenceInProperty`,
`unionOfViews*`, `extend::*`), `inheritance::*`, the metamodel mapping, `UnionTargetLeanJoinTest`,
`ResolveUnionChainTest`, `RoutedChainKeyTest`, `MappedInClosureTest`.

**No timing to take before building.** The several-route navigate's simplest lowering IS the
SQL we emit today (the members' pipelines stacked with their route conditions' columns, one
join with the routes OR-ed or coalesced; a chain's middle table inside its member's branch).
The seam it plugs into is `NavMaterializer.navTargetMaterialized` → `ClassSources.getForNav`
(the set-pinned binding under the queried mapping); the union-body facts the resolver reads
there (`UNION_SCAN`, key threads) are what the new lowering builds from the routes instead of
reading from the union. The corpus after the build is the row measurement: 0 LOST both lanes.

**Two decisions — DECIDED (USER 2026-09-13: "agree with both").** (1) the several-route
navigate is a ROUTE LIST, each entry its rows and its condition, typed entry by entry;
(2) a union's composing function binds under the engine's own kind, `Operation { f }`.
The reasoning as it was put:
1. *The several-route overload's spelling.* Each route has its own row type, so one `T` cannot
   type a list of `(rows, cond)` pairs. Options: (a) a route list `[route(rows1, cond1),
   route(rows2, cond2)]` typed per element by a Typer arm; (b) the four-argument form repeated
   with the SAME alias, the repeat meaning "another route into that slot". Recommendation: (a)
   — explicit, and the list is exactly what the DSL author wrote; (b) hides a rule in alias
   reuse.
2. *The kind tag of a union's composing function in the clean-sheet binding table.* §2 step 5
   writes `Relational { acme::funcs::personMapping }`; the clean-sheet doc's E6 names no tag.
   Options: `Relational` (its body composes relation-derived functions) or a mirror of the
   engine's `Operation { f }`. The legacy DSL's `Operation` kind is real (35 uses in the
   engine's `testUnion.pure`); the clean-sheet doc owes one line either way.

**Step 1 LANDED 2026-09-13** (the primitive alone, nothing emitted): `Pure.Lite.ROUTE` and the
3-argument `legacyNavigate` overload; `NavigateChecker.legacyRoutes` types each route on its own
rows, mints union-row keys `__route<shape>_<k>` (routes of one shape — target reads erased, source
reads kept, positions ignored — share keys, so their disjuncts collapse to one equality; different
shapes keep their own and OR), and builds the node's predicate over (source row, union row);
`TypedNavigate.routes` carries the routes; `ClassSources.routedUnionSource` builds the routed
union (one arm per route over the set its FUNCTION names, resolved under the queried mapping via
`findBindingByFunction`; the class's scalar properties by the set's bindings, the keys own-read or
typed NULL), memoized per step so materialization, substitution and predicates read one source;
the root navigation and the sub-hop resolver hand it to the unchanged navigate walk. Witness
`RoutedNavigateTest`: a hand-written function-form mapping (two Person sets as named functions,
Firm's `employees` as one `legacyNavigate` with two `route(...)`s); same-shape routes → rows
`1|A 1|D 2|B 2|C`, one keyed union join, one equality, no OR; different shapes → their own keys,
an OR of two. Corpus 108 / 444 EXACT (neutral). INTERNAL_DESUGAR 16 → 17.

**Then** step 2, one decided batch: the several-route `legacyNavigate` overload and its typing, the
per-route set pin, the emitter change (one step per property, routes as written), the union
driver reduced to a stack, the deletions above; 0 LOST on both lanes; chain; record.

## 8. The resolver side, end to end (inventory 2026-09-13, after step 1)

Step 1 was designed against one resolver path and the emitter switch immediately hit eight
more. This section is the plan that was missing, written from an inventory (grep, not memory).
Nothing below is built; the working tree is at step 1.

### 8.1 What the resolver does with a navigate step today

A navigate step is `TypedNavigate(source, alias, target = getAll(C), predicate over (source row,
target rows))`. The resolver never asks the STEP for its target; it asks for the CLASS at a HEAD
STRING, and the head string is looked up in two stamped facts:

- `ClassSources.getForNav(mapping, class, head, scope)` → `ctx.routedTargetSetOf(mapping, head)`
  (the facts `routedTargetSets` from `SetDispatch` and `routedSets` from `MappingFacts`: property →
  the sole set id its routes name) → `get(mapping, class, setId)` → the set's binding, else the
  class's root binding (a union's function). Graph fetch does the same through a set hint
  (`GraphEmission:1130`).
- The union's FUNCTION is what a union target resolves to, and its body publishes the keys the
  navigator's predicate reads. So the resolver WIDENS union bodies for the keys a predicate
  demands: `widenConcatenateForKeys` (15 uses in 6 files), `widenForCondition` (13 uses in 6
  files), `widenUnionMember`; and it walks the `UNION_SCAN` marker (10 uses) that names a merged
  same-table scan as a union body.
- Three union builders exist on the query side: the normalizer's union function (threads with
  keys), `UnionHeads` (concatenated navigation chains: branches aligned by key, OR-joined), and
  `ClassSources.mixedUnionSource` (a union with a `Pure` member: arms built in the resolver from
  the `mixedUnionMembers` and `linkKeys` facts, `linkKeyOnArm`).

The eleven target-fetch sites (each holds the step, or its source and the alias):

| file | line | site | has the step? |
|---|---|---|---|
| StoreResolver | 1736 | root navigation (`navMats`) | yes (`nav`) |
| StoreResolver | 1772 | root substitution material | yes |
| StoreResolver | 2275 | extra identity join | yes (`navSteps.get(alias)`) |
| StoreResolver | 2521 | correlated nav heads | yes |
| NavMaterializer | 121 | inside `navTargetMaterialized` (`getForNav`) | no — the callers do |
| NavMaterializer | 483 | element reroutes | yes (`tNavSteps.get(alias)`) |
| NavMaterializer | 685 | sub-hop (`subPipeFor`) | source + alias |
| NavMaterializer | 944 | extra sub identities | yes (`step`) |
| NavExistsMaterial | 100 | exists material | source + head |
| ChainedExists | 67, 76 | chained exists (mid, leaf) | yes (`midNav`, `leafNav`) |
| AssociationJoins | 944 | deeper tails under an association | source + alias |
| UnionHeads | 287 | concatenated navigation branch | yes (`nav`) |
| NavProvenance | 102 | provenance through a head | source + alias |

Eleven `new TypedNavigate(...)` rebuild sites (5 in `NavigateChecker`, `SlotOrder:125`,
`NavProvenance:69`, `Pipelines:1188`, `StoreResolver:548/676/681`) use the 8-argument form and
DROP `routes` — a latent step-1 hazard the moment routes are emitted.

Facts the resolver reads about unions and routes, and their consumers:
`routedTargetSetOf` (ClassSources, GraphEmission) · `unionMemberClasses` (AssociationJoins,
ElementReferences — membership for casts) · `routedTargetClass` (ElementReferences — cast by
route) · `linkKeys` and `mixedUnionMembers` (ClassSources — the mixed-union arms) ·
`unionKeyThreads` (ImportDataFlow — the union's own primary-key threads for the execute option) ·
`mappingPoison` (loud messages).

### 8.2 The target of a step is the step's own answer

ONE lookup: `ClassSources.navTarget(ClassSource source, TypedNavigate step)` — mapping and scope
from the source; a step with routes → `routedUnionSource(routes)` (step 1's builder, memoized per
step); a step without → the class through the existing dispatch. `navTargetMaterialized` takes the
resolved TARGET SOURCE from its caller instead of (class, head); the `given` parameter of step 1 is
that, made the only path. Every site in the table passes the step it holds, or looks it up in its
source pipeline by alias (`Pipelines.navSteps`). No site does its own routing; no head string
reaches `ClassSources`. The eleven rebuild sites use `withChildren` or the 9-argument form, so a
rebuilt step keeps its routes (a `TypedSpecChildrenTest`-style pin: no 8-argument construction of
a node that has routes).

### 8.3 What dies on the query side, and what stays

| machinery | after composition | why |
|---|---|---|
| set-pin facts `routedTargetSets`, `routedSets`, `routedTargetSetOf` | DIE | a routed step names its sets' functions; a single pinned route is a route list of one |
| union-key widening: `widenConcatenateForKeys`, `widenUnionMember`, `widenForCondition` and their consumers | DIE | the routed source projects its own keys; the union function has none |
| `UNION_SCAN` marker, `isUnionScan` walks | STAY while the merged same-table scan stays (an optimization of the union function, not navigator knowledge); revisit in B6 | |
| `mixedUnionSource`, `mixedMemberRoutes`, `linkKeyOnArm`, facts `mixedUnions`, `linkKeys` | DIE | a mixed union is a stack of member functions like any other; the navigator composes them (no corpus judge — a witness) |
| `UnionHeads` | STAYS in step 2; unify with `routedUnionSource` in B6 | same alignment, different trigger (a concatenate of chains, not routes) |
| `unionKeyThreads` + ImportDataFlow | STAY | the union's OWN identity threads, not navigator knowledge |
| `unionMemberClasses`, `routedTargetClass` (casts) | STAY | facts about the mapping text (membership), not about who navigates |
| `CastReRoot` `__pk` string read | B6 | typed key fact |

### 8.4 Order of steps (each: compile, witness, both lanes 0 LOST, chain, record)

- **2a — the resolver first, nothing emitted.** `navTarget(source, step)`; the eleven sites
  re-pointed as a SIGNATURE change; `navTargetMaterialized(targetSource, ...)`; `given` gone;
  the rebuild sites carry routes; `RoutedNavigateTest` gains cases that go through the exists,
  chained-exists and association paths. Corpus neutral by construction.
- **2b — the normalizer emits.** Every set gets a function and a binding (union members too:
  drop the member exclusion in the driver, `SynthFqn.mappingClassSet`); routed navigations emit
  route lists (single hop and shared-prefix last hop at the navigator's landing; a per-arm chain's
  mids joined INSIDE the route — the checker learns nested reads `$t.mid.col` and the routed source
  re-roots the route's rows onto the member's pipeline); pinned single routes become route lists
  of one, so the set-pin facts stop being consulted. Judges: the census's 275 routed navigations.
- **2c — the union is a stack.** The union function becomes the members' functions concatenated;
  threads, published keys, lifts, inbound chains gone; each member's function carries its own
  navigations. Judges: every union family; the metamodel mapping.
- **2d — deletions.** Normalizer: `publishLinkKeys`, `collectInboundRouteKeys`,
  `navigatingIdentity`/`routeSignature`/`routeShapes`/`linkKeyName`, `inboundArmSteps`/`LiftChain`/
  `chainsSink`, the include re-synthesis block and its three criteria, `classTypedButUnmapped`, the
  facts `linkKeys`/`mixedUnions`/`routedSets`/`routedTargetSets`. Resolver: §8.3's DIE rows. Tests
  re-pointed to the routed spelling (`UnionTargetLeanJoinTest`, `ResolveUnionTest`,
  `RoutedChainKeyTest`, `ResolveUnionChainTest`).
- **2e (B6).** `UnionHeads` onto the routed builder; `CastReRoot`'s typed key; the `UNION_SCAN`
  decision.

**2a LANDED 2026-09-14.** `ClassSources.navTarget(source, class, step, head)` is the one lookup;
`stepOf(source, alias)` finds a step by alias; `navTargetMaterialized(temporal, target, ...)`
receives the resolved target and resolves nothing (the step-1 `given` parameter and the
`routedTarget` helper are gone); the eleven sites of §8.1's table call it; the six resolver
rebuild sites use `withSource` / `withPredicate` / `withSourceAndPredicate` (routes kept). Graph
fetch's set hint (`GraphEmission:1130`) is untouched until 2b emits routes. Corpus neutral;
`RoutedNavigateTest` covers the exists path.

### 8.5 Known unknowns, measured at the step that reaches them

Per-arm chains' nested reads (19 in the census); same-table inheritance targets (N arms over one
table versus today's merged scan — rows equal, SQL heavier; keep `sameTableInheritanceMerge` as an
emitter optimization until measured); store substitution under includes (the includer carries its
own copies — confirm the copies exist before routes name functions); the mixed-member witness.

**Related.** `docs/MAPPING_CLEAN_SHEET.md` (§2, §3, §4.2, Layer 5, E6);
`docs/NORMALIZER_CLEAN_SHEET_HOMEWORK_2026_09_13.md` §6 (B3.1b design, B3.2 receipts, the
B3 arc audit's deferrals 1, 4, 5 — all closed by this design).
