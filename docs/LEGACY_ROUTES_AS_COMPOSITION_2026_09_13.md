# Legacy routes as composition — the worked example (2026-09-13)

## START HERE (fresh session, 2026-09-14)

**State of main.** `aa68dc289` + docs: step 1 (the several-route `legacyNavigate` primitive:
`route(...)`, the checker, `TypedNavigate.routes`, `ClassSources.routedUnionSource`) and step 2a
(ONE target lookup `ClassSources.navTarget(source, class, step, head)`; the eleven head-string
sites re-pointed; rebuilds keep routes). Corpus DuckDB 108 / H2 444 EXACT, chain and CI green.
Nothing emits routes on main yet; the union body is unchanged.

**Parked.** Branch `wip/composition-2b` (pushed): every non-root set bound, route lists emitted,
chains inside routes, nested reads, twenty more navigate-step sites on the one lookup. 228 LOST —
the union body's own lifted navigations still speak the published keys. Read §8.6 before
touching it. The stack's first-cut query-side builder (lifts from the members' steps) is
`docs/notes/composition-union-source-first-cut.java.txt`.

**The goals, in order** (§9): rows equal to the engine; output a person would write; the same
pipeline as every other function; facts not guesses; bespoke code shrinking every batch; loud on
the unknown; measured performance.

**The architecture (decided, §9):** a union is a STACK of its members' functions; the LAW: any
operation on a stack is the operation per arm, stacked; `Operation` is only the binding's kind
tag; the lowering of a navigation over a stack is a five-shape pass (uniform / non-uniform
equalities / push-into-arm for chains / the Snowflake bridge union / general) that must not be
lost, and every arm's own primary key must survive (the bridge joins on it).

**The next slice, in order — no code before the first two are written and read:**
1. INVENTORY (by grep, never memory): everything the union body precomputes today
   (`UnionSynthesis`: threads, published keys, lifts incl. inverse-association ends, per-ordinal
   key threads, chains, same-table merge, aggregation-aware / `~func` / `Pure` members) and every
   query-side reader of it (the 61 `sources.get` sites, `widenConcatenateForKeys`,
   `UNION_SCAN`, `UnionHeads`, `mixedUnionSource`, `ImportDataFlow`), each classified: an
   instance of the law, an input to a lowering shape, or no reader. With its corpus judges.
2. DESIGN PAGE: how a demand for a navigation on a stack flows into the arms on the query side
   (the capability), how each inventory item becomes it, the witness for what the corpus lacks
   (mixed members).
3. ONE BUILD, judged per item by rows; then the deletions (§7, §8.3) as their readers vanish.

**Rules that were paid for this week.** An optional parameter threaded through call sites is
the hack pattern — change the signature or the lookup, never patch sites. An inventory that
misses the plain class lookups is not an inventory. Two blind probes → stop, write the handoff,
park on a branch. Rows first; the SQL text is the engine's, never ours to chase. Every batch
deletes more than it adds or it is not done. The `routeFunction` loss, the `[[], []]`
aggregation-aware arms and the pinned-single-to-subclass route (§8.6) are open questions to
answer in the inventory, not to patch.

---

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

### 8.6 Step 2b/2c — PARKED 2026-09-14 (branch `wip/composition-2b`; main stays at 2a)

What was built (all on the branch, none on main): every non-root set bound (union members too);
routed navigations emit route lists (single hop, shared-prefix last hop, per-arm chains with
their mids inside the route; pinned single routes as lists of one, `UnionSynthesis.PINNED_SINGLE`);
the checker reads `$t.slot.col` paths; the routed builder re-roots a route's rows onto the
member's pipeline and reads key paths; graph fetch takes the routed union; twenty MORE
navigate-step sites moved onto the one lookup (§8.1's table missed every site that resolves a
step's target through a plain `sources.get(mapping, class)` — 61 such calls exist; the twenty
that pair with a step are in AssociationJoins ×4, CorrelatedSubselects ×2, DottedExists,
GraphEmission ×5, NavExistsMaterial, NavMaterializer, NavProvenance, StoreResolver ×2,
TemporalFrame).

What the rows said. First the plain STACK (2c: the union function = its members' functions
concatenated, the query side building the union from the members' sources with the members'
navigations lifted above it): 337 LOST. The stack requires the query side to rebuild everything
the normalizer's union body precomputes about its members — their navigations, the
inverse-association ones (single-hop pairs never become Join PMs), per-ordinal identity threads,
operation members (flattened to leaves), aggregation-aware and `~func` members, the same-table
merge. That is the union machinery relocated, not removed — reverted; the stack is B6 work and
needs a union-source builder that composes lifts from members' steps (a first cut existed on
the branch's history and was dropped with it). Then 2b alone with the union body kept: 228 LOST,
dominated (108) by "a navigation join over this union demands key column X, which NO union
member carries": the union body's OWN lifted navigations still speak the published link keys
(`a1_b`) while their targets, resolved through the one lookup, now answer with routed unions
keyed `__route<i>_<k>` when the member functions' steps carry routes. The two spellings meet in
one predicate and cannot both hold.

The finding that decides the next design: **the union's lifts must become route lists too**, and
until every navigation is a route list the two key vocabularies coexist. So 2c' (replacing 2c):
the normalizer emits each union's lifted navigation as a several-route `legacyNavigate` whose
routes are its MEMBERS' routes (each member's own PMs name their target functions), with the
source side per member as the threads project it today; the inbound key publication and the
navigator-side key names then have no reader and go (2d). Single-hop association pairs whose
source set is a union member must inject as Join PMs (today only multi-hop and union-target
groups do) so the lift can compose them. Order: 2c' on the branch, judged by the same families;
then 2d; the stack stays B6.

Also found on the way: `routeFunction` (the engine's router platform function) lost 4 rows —
not yet explained; the aggregation-aware union members project no scalar columns (`[[], []]`);
`RootSubTypeWithSubtypeLevelPropertyUnionMapping` routes read a column the pinned subclass
set's rows do not carry (the pinned-single-to-subclass special case in `emitJoinChain` and the
new route list disagree).

## 9. The architecture, restated against the goals (2026-09-14)

**The goals, in order** (USER 2026-09-14): rows equal to the engine, always; the generated
code reads like something a person would write; the same pipeline as every other function;
facts stamped where known, nothing guessed; bespoke mapping code shrinking every batch; loud on
the unknown; measured performance, no cliffs by accident.

**The model.** A union of mappings of one class IS a stack of the members' functions —
`Operation { concatenate(p1, p2) }` — because that is what Pure's union means: a list of objects
from both, each object still belonging to the mapping it came from. **The law:** any operation on
a stack is the operation on each arm, stacked — navigation, filtering, identity, graph fetch, a
union inside a union. `Operation` stays the KIND TAG on the binding ("this class's function is a
composition"); there is no union operator users call, and no pre-built union object with helper
columns on the query side. What the query side lacks today, and what the next slice builds, is
the law applied: a demand for a navigation on a stack flows into each arm, each arm answers with
its own step, the answers stack. Everything the union body precomputes today becomes an instance
of that law or has no reader (§8.6's list).

**The lowering optimization we MUST NOT lose** (USER 2026-09-14). The law says "one join per
arm"; the engine joins ONCE above the union, and we measured why (§6 B3 of the homework doc, one
union family, DuckDB): the merged single-key join **0.8 ms** (hash), a coalesced key **1.5 ms**
(hash), an OR of per-arm equalities **8.9 ms** (nested loop). So the lowering of a navigation
whose SOURCE is a stack is a compiler pass with five shapes, chosen by the arms'
conditions and the DIALECT (single compiler, dialect strategies: shape 4 is Snowflake's default
form, shapes 1–3 the others') and nothing else:

1. **Uniform** — every arm's condition has the same shape (target reads erased, source reads
   kept: `$s.fk == $t.?` on both arms): ONE join above the stack on ONE key column, each arm
   projecting its own read under that one name (NULL where an arm has none); a single equality,
   hash-joinable. This is today's routed form (`UnionTargetLeanJoinTest`: one join, no OR, no
   coalesce).
2. **Non-uniform equalities** — the arms' conditions differ but each is an equality (or a
   conjunction of equalities) against the same source expression: ONE join above the stack with
   per-arm key columns (NULL elsewhere) and `coalesce` over them where the left side is shared,
   else an OR of the equalities — the engine's `unionalias_N ... on (a or b)` shape; still one
   join, still row-equal (`ResolveUnionChainTest`'s trap row: a key an arm lacks is NULL there
   and never matches).
3. **Chained, non-uniform — the engine's other trick, push-into-arm** (USER 2026-09-14; the
   B3.2 receipts: `testUnionWithChainedJoinsAcross3SetsV4`, `unionOfViews2`). When the arms are
   reached through chains of different lengths, the engine does NOT put the middle tables on the
   navigator's side and does not OR over whole chains: it roots each arm at the chain's middle
   table, joins the rest of the chain INSIDE the arm, and projects the column the navigator's
   FIRST hop reads as that arm's key (`from A as "root" left outer join Y1 … left outer join G`,
   key `fk0_1`; the next arm `from B left outer join C left outer join Y2`, key `fk0_2`). The
   union join is then shape 2: one join above the stack on per-arm keys. Two middle tables can
   never multiply against each other because each lives in its own arm. In our form this is a
   route whose rows are the member's table joined with its mids and whose condition is the first
   hop (`route(A$p1(), #>{db.T1}# -> join(~addr: #>{db.ADDRESS}#, …), {f, x | $f.ID ==
   $x.addr.FIRM_ID})`) — the route list already lowers it this way, and the same rule applies to
   a stack's own navigations when the arms' steps are chains.
4. **The bridge union — the engine's `REMOVE_UNION_OR_JOINS`** (USER 2026-09-14; engine
   `pureToSQLQuery_union.pure` `addChildByAttemptingToRemoveUnionOrJoin`, on by DEFAULT for
   Snowflake and by `GenerationFeaturesConfig.enabled` elsewhere). No OR at all: a BRIDGE
   `UNION ALL` with one leg per (source set, target set) pair, each leg computing the matching
   PRIMARY-KEY pairs through that pair's own join (`union_gen_source_pk_<i>` /
   `union_gen_target_pk_<i>`); then `source ⋈ bridge` on the source's pk and `bridge ⋈ target`
   on the target's pk — two plain (null-safe) equalities. Requires primary keys on both sides
   (the engine checks compatibility per table / union-all) — so **every arm's own primary key
   must survive the stack**: per-arm identity is not union machinery, it is what this shape
   joins on. Receipts: `testChainedUnions`, `testProjectThroughAsso`,
   `testProjectThroughAssoWithJoinInMapping`, `testUnionWithSinglePropertyMapping` run each query
   twice (plain and with the feature) and assert identical rows; our lane parses the config
   (`Protocol.PGenerationFeaturesConfig`) but implements no rewrite — the four pass by rows on
   DuckDB (the text assertion on `union_gen_source_pk_0` is not ours to judge).
5. **General** — a condition that is not an equality (an inequality, a composite predicate the
   arms spell differently): the law literally — the join inside each arm, results stacked.
   Row-equal by construction; the slow shape, accepted only when 1–4 do not apply.

The chooser reads the typed conditions the arms already carry (the same shape rule
`NavigateChecker.legacyRoutes` uses today for route lists); it never reads the mapping. The
routed navigate INTO a union is the same pass seen from the other side (the routes' conditions
are the arms' conditions), which is why the route list already lowers as shape 1 or 2.
Measurement rides every batch that touches this pass: the three families above on both lanes,
rows first, then the DuckDB timings of the union families against the numbers here.

## 10. Inventory — what the union body precomputes and who reads it (2026-09-14, main `d24e005da`)

Taken by grep over main at step 2a, never from memory. Every line reference is main. The
parked branch's last corpus logs (job tmp `corpus-duckdb.log` 06:48 = the 226-LOST run,
`corpus-h2.log` = 446 LOST) were read for the three open questions and for which families
each item breaks; nothing on the branch was touched.

**The three labels.** LAW = an instance of "the operation on a stack is the operation per arm,
stacked": the item is something each arm already knows about itself, and the stack only has to
carry it through. SHAPE n = an input to the lowering pass of §9 (shape 1–5): the item is how a
navigation over a stack is made fast, not what it means. NO READER = nothing reads it once
navigations are route lists; it dies in 2d. FACT = a fact about the mapping text (membership,
kinds), not about the union body; it stays as a stamped fact.

**Counts, so the design page has denominators.** `UnionSynthesis` 3,287 lines. 61 plain-class
lookups (`sources.get`) in 13 resolver files. 16 widening call sites over 3 entry points.
10 reader sites of the merged-scan marker. 3 query-side union builders (the normalizer's body,
`UnionHeads`, `mixedUnionSource`) plus step 1's `routedUnionSource`. Register: 89 `union::` rows
DuckDB / 88 H2, 8 `specialUnion`, 37 `extend::`, 21 `modelJoin::advanced`, 28 metamodel
`executionPlan::tests`, 114 `aggregationAware`, 3 `milestoning::union`.

### 10.1 What the union body precomputes (`UnionSynthesis`, main)

| # | item | where (main) | label | judged by |
|---|---|---|---|---|
| A1 | **The arm list and its order.** Members by set id across includes; a `Pure` member makes the union "mixed" (A12); a `~func` member is allowed (A13); a member must map the class or a subclass. Inheritance ops enumerate the engine's leaf-most mapped subclasses (`getMappedLeafTypes`), one enumeration shared with route classification so ordinals align. | `synthUnion` 284–386; `synthInheritance` 396–424; `inheritanceMembers` / `collectInheritanceMembers` 555–667 | LAW (the stack's arms, in the engine's ordinal order; the order is read by A11's names, A12's arm order and graph fetch) | every `union::` family; `specialUnion` ×8; inheritance families (`mapping::inheritance`, `extend::`, `subType`: 20 rows lost on the branch); the metamodel (`executionPlan::tests` ×28) |
| A2 | **Association pair entries land on their member.** `[srcSet, tgtSet]` association entries become routed Join PMs on the owning MEMBER inside the union body, through the extends chain, deduped by `pmIdentity`. The member's own standalone function never gets them: `AssociationSynthesis` 173–186 skips injection when the owner is a union member ("land on their member set at union synthesis instead"). | `synthUnion` 339–384; `withPairEntries` 429–470; `AssociationSynthesis.injectMultiHopAssociationPMs` 81–199 | LAW — each arm's own navigations include the pairs whose source set it is. Input to 2b's "every set gets a function": the per-set function must carry them or the stack cannot compose them. | `extend::testExtendsForPropertyMappingWithUnion` (result2 joins only the routed thread), `association::inheritence::testBuilderRoutingOfAggFunctionParameters`, `testGetAllFilterWithAssociation`, `union::optimized::testSimpleQueryFromAssociationMapping*` ×2, multipleChainedJoins V4 (included pair entries) |
| A3 | **Shared scalar columns.** The union of the members' scalar properties in first-appearance order; a member lacking one projects a typed NULL; numeric/date coercion to the declared kind, `String` cast, `trustOne` alignment to `[1]`. | 910–926; `threadOf` 1161–1191 | LAW — the per-arm projection of the class's scalar properties. Step 1's routed builder (`ClassSources` 452–457) and the first cut (note lines 105–110) already do exactly this from the arm's bindings. | `union::partial::*` ×6 (`testUnionPartial` goldens: TDSNull reads), `testProjectMappingWithSameColumnsNames`, every union family |
| A4 | **Embedded properties distributed per sub-field.** Each member's `^Inner(...)` ctor leaves become `emb__<path>__<sub>` thread columns (typed NULL where a member lacks the sub); the union root recomposes the ctor; class-typed subs recompose as union-level reads served by A6; members disagreeing on a path's ctor class recompose as the DECLARED class; unprojectable leaves poison the top property. | 927–952; `collectEmbeddedDistribution` 1497–1586; `addEmbeddedThreadCols` 1591–1625; `rebuildEmbCtor` 1693–1719 | LAW — an arm's ctor bindings are the arm's; a stack builder reads them per arm (the routed builder does not do this yet: it reads only scalar bindings). | `testProjectEmbeddedMappingUnionWithSameColumnsNames(Deep)` ×2 (+`extend::` twins), `union::partial::testPartialUnionAtNestedPropertyWithManyPropertyMappings` ×3, `inheritanceWithEmbedded` (Car/Bicycle map only `mechanic(...)`), `vehicleOwner` Inline[person]/Inline[airline] |
| A5 | **Subtype dispatch columns and the membership witness.** Every member whose class is a strict subclass projects its scalar props under `stc_<Sub>___<prop>` (own thread reads, others NULL), plus a `$member` witness (TRUE / NULL) when some member does not conform; embedded subtype leaves flatten to `stc_<Sub>___<prop>__<leaf>`. The resolver exposes them as pseudo-bindings by column name. | `subTypeDispatchProps` 681–751; `addSubTypeDispatchCols` 757–808; `addStcEmbeddedLeaf` 813–842; readers `ClassSources` 1000–1007, `CastNav` 49, `ElementReferences` 128–190, `CastReRoot` | LAW — an arm knows its class; a cast over a stack is a per-arm filter and a per-arm read. Today it is a NAMED-COLUMN protocol (`ClassMapping.subTypeColumn`, `memberWitness`) that the query side reads by string; it survives 2b/2c as is and becomes a typed per-arm fact in B6. | `inheritance::*`, `subType::testSubTypeMappingValidWhenMappedExplicitly`, `specialUnion` ×8, the metamodel's `->cast(@Member)` roots (`RelationalOperationElement`, `SetImplementation`), `testInheritanceMultipleLevel` (batch 108) |
| A6 | **Lifted navigations.** Every class-typed Join PM of every member (embedded descent included; subtype-only PMs under their `stc_` key) becomes ONE `legacyNavigate` ABOVE the concatenate: per entry the join's last hop translated over (member table, landing table); source reads member-suffixed `<col>_<i>` and projected by that member's thread (NULL elsewhere); the OR of the entries. Three sub-rules: (a) MERGED form (`liftTargetMerged`: one route per source member covering every target member, all reading the same target columns) keeps raw target reads and coalesces same-source members into one disjunct (`mergeSameSource`, `coalesceReads`); (b) the PAIRED form always builds and rides as `pairedCondition` for graph children (the engine's graph executor pairs strictly; its relational path cross-matches); (c) `singleSetTargetCollapse`: a NON-union target reached by every member through the SAME join into distinct private sets routes to the LAST member's binding only (inclusive golden `null as prodFk_1`). The target side reads the published link keys of A8. A property whose entries are unresolvable is not lifted (poison, loud at demand). | `collectNavLifts` 2600–2845; `scanJoinPms` 2493–2598; `liftTargetMerged` 2310–2365; `singleSetTargetCollapse` 2377–2401; emission 1065–1077; `recomposeUnionRoot` 1367–1393; readers of the paired form `GraphEmission` 1183, 1776, 2576 | LAW — each arm's own navigate step (the emitter already emits it per set today: the lift re-derives it from the PMs). Sub-rules (a) and (b) are SHAPE 2 (one join above the stack on per-arm keys, coalesced where the left side is shared); (c) is an engine ROUTING rule the chooser must keep as a rule about which arms answer, not a union-body fact. | every routed union family; `partiallyMilestoning` trio (the cross-match golden, rows 2×2); `milestoning::union` hybrid ×3 (inclusive: rows [2]); `union::sqlQueryMerging` ×7 (`fk_0=fk_0 OR fk_1=fk_1`); `testUnion` (`FirmID_0 = ID_0 OR …`); `VarReferenceWithUnion`; graph `rootLevel SameStore` (product=null); branch: 49 `mapping::union` rows lost, `a1_b` ×6 and `x0_y` ×3 (`ResolveUnionChainTest` trap row) |
| A7 | **Inverse-association ends whose source set is a union member.** Single-hop association pair groups stay on the standalone predicate path (`legacyAssocPredicate`) and are NOT Join PMs on the member, so A6's scan never sees them; only multi-hop, routed-union-target, inheritance-target and non-anchorable groups inject as Join PMs. A member that is also a union member is skipped even then (A2). | `AssociationSynthesis` 112–186 (the rule); `synthesizeAssociationMapping` (the predicate function) | LAW — an arm's navigation, spelled today as an association binding's predicate instead of a step on the arm. Input to 2c': the stack cannot compose what is not on the arm; either the single-hop pair becomes a per-arm step, or the association binding's predicate is composed per arm (§8.6's finding). | `optimized::testSimpleQueryFromAssociationMappingOptimized(Half)`, `association::*`, `testUnionWithExistsFilter`; branch: `graphFetch` ×2 "property 'address' of class Person is not mapped" |
| A8 | **Published link keys (B3.1b).** Every relational set in every mapping's closure publishes, before any synthesis, the columns the routes INTO it read, under names spelled by the navigating set and property (`linkKeyName(navigatingIdentity, prop, shape, pos)`); `navigatingIdentity` is the set id, or the operation's class when all its members route alike (`routeSignature`); `routeShapes` / `shapeIndex` decide when two routes share one name. The threads project the names (own column / typed NULL, one order); the navigator spells the same names (`JoinChainEmission` 544–612); the includer compares publications to decide re-binding (A16); the mixed-union arms read the fact (`linkKeyOnArm`). Extends chains inherit the parent's keys. | `publishLinkKeys` 2896–2929; `collectInboundRouteKeys` 2942–3023; `registerInboundGroup` / `registerInboundEntry` 3028–3144; names 1774–2063; thread projection 1195–1251; driver `MappingNormalizer` 167–207; fact `linkKeys` (`MappingLedger` 42, `MappingDefinition` 91, `PureModelContext` 332); readers `ClassSources` 719–737, `MappingNormalizer` 869–898 | NO READER once (i) every navigation INTO a union is a route list (2b) AND (ii) the union's own lifts (A6) are route lists (2c'). The branch measured what happens with (i) alone: 139 rows asked a union body for `__route0_0` while the body spoke `a1_b` / `set1_firm` / `x0_y` / `firm_set1_employees` — the two vocabularies meet in one predicate and cannot both hold. | `UnionTargetLeanJoinTest` (one key per arm, one equality), `ResolveUnionTest` (asserts the link key), `RoutedChainKeyTest`, `ResolveUnionChainTest`; every union family |
| A9 | **Chains.** OUTBOUND: a member's chained Join PM puts its mids INSIDE its own thread (`liftMidSteps`, `JOIN_SLOT` wraps 1272–1293) and projects the last mid's columns as source keys spelled `col__prop_ord` (thread-internal; B3 audit deferral 4). INBOUND (push-into-arm, B3.2): a per-arm chained route into a member pushes the mids into THAT member's thread (`inboundArmSteps`) and publishes the FIRST mid's column under the link-key name; `uniformChainedRoutes` (shared with the emitter) decides shared-prefix (navigator emits the prefix, last hop is the route) versus per-arm; a second chain into one member under one name is dropped silently (`dup`, audit finding 2). | `liftMidSteps` 2403–2444; `LiftChain` 2457; `addChainedLiftCols` 1439–1465; `chainKeyNull` 1469; `inboundArmSteps` 2262–2303; `registerInboundEntry` 3076–3116; `uniformChainedRoutes` 2218–2242; `routeKeyCondition` / `hopCondition` 1966–2020 | Outbound: LAW (the arm's own chained step) lowered by SHAPE 3 when arms' chains differ. Inbound: NO READER — the mids belong to the ROUTE (`route(#>{T1}# -> join(~mid …), first-hop cond)`, §5), not to the arm; the branch's three `route condition reads through 'nl__employees__inb__…', which is not a joined sub-row of the route's rows` rows are exactly the inbound alias living in the wrong place. | `union::multipleChainedJoins::*` ×17 DuckDB (15 `testUnionWithChainedJoinsAcross…` + 2 view chains), `unionMappingWithJoinSequenceInProperty` ×2 (+`extend::`), `testUnionOfViewsWithFilterInQualifiedPropertyAndNonOverlappingJoinSequnece`, `ResolveUnionChainTest`, `RoutedChainKeyTest` |
| A10 | **The same-table merge.** (a) Filtered members over ONE physical table with agreeing navigation slots become ONE thread: the unfiltered scan restricted to rows some member claims, every column a CASE over the members' values gated by their filters, wrapped in the `unionScan` marker (B3). (b) Filter-free members over one table with no groupBy/distinct/sourceUrl collapse to ONE relational set with the identically-mapped base props hoisted (`synthSameTableInheritance`). (c) On the navigator side, a same-table inheritance target reached through one join drops its routes and reads the physical column plainly (`sameTableInheritanceMerge`). | scan groups 1003–1064; `mergedScan` 1301–1354; `ScanSource` / `FilteredScan` 1091–1147; marker 1057; `synthSameTableInheritance` 484–547; `sharedInheritanceTable` 528–547; `sameTableInheritanceMerge` 2026–2047; `JoinChainEmission` 437–442 | SHAPE — a lowering optimization of a stack whose arms share a root scan (the engine's single-table-hierarchy idiom; the H2 planner hang on the metamodel's 21-kind datatype hierarchy is the receipt). It must become a pass over such a stack, with the arms' route keys projected INSIDE the merged scan per arm (gated like every column): the branch's 114 `__route0_0` rows are merged scans (the metamodel's nodes and activities: `stc_…`, `res_activities`, `id__pk_metamodel_activities`) that did not project the route's key. | the metamodel: `executionPlan::tests` ×28, `typeInference` (20 rows lost on the branch), `aggregationAware` ×114 (17 lost on the branch through the ACTIVITIES union — see 10.4), `alloy::connections` (8 lost), `tds` (6), `modelJoins` (6); inheritance families; `UnionTargetLeanJoinTest` covers only the two-table case |
| A11 | **Per-arm primary keys and the shared table key.** Every member's primary key (declared `~primaryKey` on the main table, else the table's PRIMARY KEY) is projected as `<col>_<ordinal>` (NULL in other threads) and recorded as the fact `unionKeyThreads` (`KeyThread(name, kind)`). Members sharing one main table with one PK column project it ONCE, ungated, as `<col>__pk_<table>`. | `recordKeyThreads` 3160–3185; `memberPrimaryKey` 3190–3214; `ownSharedKeys` 2856–2877; `sharedKeyName` 3218; thread 1252–1271; fact `MappingLedger` 38 → `PureModelContext` 339 | SHAPE 4 input (the bridge union joins on each side's primary key: every arm's own key must survive the stack) and the IDENTITY readers below. Not navigator knowledge; not union machinery. | readers: `ImportDataFlow` (10.2 B6), `CastReRoot` 89 (`startsWith(col + "__pk")` — audit deferral 6), primary-key pseudo-bindings `ClassSources` 1008–1023, `ElementReferences` 95/112, `RelationalRootForm` 220, `DriverPkAppend` 32; judged by the metamodel families (cast re-root under a flatten: `FunctionParametersValidationNode.functionParameters`) and by §9's four bridge-union receipts (`testChainedUnions`, `testProjectThroughAsso*`, `testUnionWithSinglePropertyMapping`) |
| A12 | **Mixed unions (a `Pure` member).** The normalizer withholds the function and records `mixedUnions`; the resolver builds the arms itself: relational members first, Pure members after (engine batch order), scalar props by each arm's bindings (a missing binding is loud, no NULL arms), per-member child routes from the arms' own steps (`mixedMemberRoutes`: the routed set hint + the step's equal-pairs) as `k__<prop>__<ord>_<k>` key columns (audit deferral 5), and a keyed CHILD union per class-typed property (`mixedChildMaterial`) whose arms read the link-key fact (`linkKeyOnArm`). The set-pin fact is kept for mixed unions on purpose (`SetDispatch` 77–86). | `synthUnion` 305–318; `MappingNormalizer` 300–357 (per-set bindings only for mixed members); `ClassSources.mixedUnionSource` 247–339, `mixedArmOrder` 539–555, `mixedMemberRoutes` 612–650, `mixedChildMaterial` 745–865, `linkKeyOnArm` 719–737; `ClassSources.build` 907–919 | LAW — this IS "demand flows into the arms", built once for the mixed case only: arms from the members' own sources, a navigation over the stack composed from the arms' own steps. It is the prototype of the one query-side builder, not a special case to keep beside it. | NO corpus judge: `XStore` rows in either register = 0; the three `XStore::inMemoryAndRelational` tests sit on both fail rosters. Witness W1 (10.5). |
| A13 | **`~func` (Relation) members.** The member's parts are the inlined relation body and its column reads; no main table, so no navigation lift, no inbound key (a route into it "has no physical key table"), no primary-key thread. | `synthMemberUnion` 851–867; `registerInboundEntry` 3066–3069; `recordKeyThreads` 3166–3169; `collectNavLifts` 2609–2611 | LAW — an arm whose function is the user's relation function; a route into it reads its columns on its own rows, which is what a route already does (`route(rows, cond)` needs no key table). | union OF `~func` members IS in the corpus: `modelJoin::advanced` ×21 (`modelJoinUnionSetup.pure`: `personFT`/`personCT` are `Relation` sets under a union; `testUnionWithExistsFilter`, `testMixedMapping*`); a route INTO a `~func` member is not (§7 census: every routed member is `Relational`) → witness W2 |
| A14 | **"Aggregation-aware members".** Not an item. No engine fixture puts an `AggregationAware` set inside an Operation union (grep of every relational test `.pure`: none). See 10.4 for what the branch's 17 `aggregationAware` rows were. | — | — | — |
| A15 | **The navigator side of the same machinery.** Route classification per property (`classifyUnionRoutes`: union/inheritance member ordinals; root/sole routes = the un-routed navigation; a SINGLE route to a non-root set = the set-pin dispatch; poisons "MIXED root-set and union-member routes" and "MULTI-route dispatch outside union members is a roadmap feature"; the property is DROPPED from the synthesis on poison); the pinned-single-to-subclass rewrite (10.4); the routed emission (`routedNavigation`: one `legacyNavigate`, the OR over routes reading A8's names, per-arm chains reading the first hop); the B3.3 drop rule (`classTypedButUnmapped`, `droppedRoutedProps`). | `classifyUnionRoutes` 174–274; `JoinChainEmission` 292–325, 429–451, 486–613, 737–752; `MappingNormalizer` 2238 | Replaced by the route-list emitter (2b). Its poisons become routes: a route to a non-member, non-root set is a route to that set's function; mixed root+member routes are just routes; "not mapped in this closure" is a route that fails at demand under the queried mapping (§5, no drop rule). | `union::testUnionToUnionJoinSequenceWithMultipleChildrenInUnionSourceTree`, `ResolveUnionTest.partialRouteSuffixedKey` / `coverageNeverMatchesUnroutedMember`, `MappedInClosureTest`, `projection::qualifier::testFilterInQualifierWithFilterInMapping*` ×2 |
| A16 | **The include re-synthesis block.** An included class is re-bound under the querying mapping when (1) a routed target gains an operation here, (2) an included union's members gain THIS mapping's link keys, (3) a class-typed Join PM dropped under the defining closure has a target set here. Only sole definers; the local binding shadows the included one. | `MappingNormalizer.resynthesizeIncluded` 787–863; criteria 869–960; `MappingLedger.everyPublication` 47 | NO READER — the disease this design cures: a route names a function resolved under the QUERIED mapping (`findBindingByFunction`, own bindings then includes), so nothing needs regenerating. Criterion (3) is R6 and stays true by construction: under the queried mapping the route resolves or fails at demand. | `MappedInClosureTest`, `projection::qualifier::testFilterInQualifierWithFilterInMapping*` ×2, the `inheritanceMain` / `inheritanceMappingDB` split (`roadVehicles[map1]/[map2]`), `extend::` ×37, multipleChainedJoins V4 (included `y2`/`y3`) |
| A17 | **Stamped facts.** `routedTargetSets` (property → sole non-root set of a multi-set NON-union target; mixed unions kept) and `routedSets` (the same over the surface, association ends and `Otherwise` fallbacks included) behind `routedTargetSetOf`; `unionMembers` → `unionMemberClasses`; `routedTargetClasses` → `routedTargetClass`; `mixedUnions`; `unionKeyThreads`; `linkKeys`; `poisons` → `mappingPoison`. | `SetDispatch` 34–87; `MappingFacts` 34–163; `MappingLedger.facts` 96–102; `PureModelContext` 258–349 | set pins: NO READER (a pinned single route is a route list of one; readers `ClassSources` 91 (the route-less path of `navTarget`), 619, `GraphEmission` 1130). `unionMemberClasses` / `routedTargetClass`: FACT, stay (casts: `AssociationJoins` 684, `ElementReferences` 141/164). `mixedUnions`: dies with A12. `unionKeyThreads`: stays (A11). `linkKeys`: dies with A8. `mappingPoison`: stays, shrinks with A15. | `ElementReferences` casts: inheritance families, the metamodel; `GraphEmission` set hint: graph fetch families (`graphFetch::tests::subType::*` on H2) |

Two driver facts that belong to this list: union MEMBERS get no binding of their own unless
the union is mixed (`MappingNormalizer` 309–357, the member exclusion 2b drops), and a union's
composing function binds today as `ClassBinding.Pure` (non-Relational fallthrough at 397, 857) —
there is no `Operation` kind tag on the binding (`ClassBinding` permits `Relational`, `Pure`
only: `MappingDefinition` 179).

### 10.2 The query-side readers

**B1 — the 61 plain-class lookups (`sources.get`), by what they fetch.**

| group | sites | label |
|---|---|---|
| (a) a navigate step's TARGET, fetched by class: `AssociationJoins` 178 (step by alias), 241 (nav-demand callback), 867/868 (association predicate's pinned set), 953 (tail step); `CorrelatedSubselects` 525, 1522; `DottedExists` 167; `GraphEmission` 688, 770, 1133/1136 (graph child with the set hint), 1766, 2571; `NavExistsMaterial` 149; `NavMaterializer` 656/691/701 (sub-hop), 783; `NavProvenance` 137, 173; `StoreResolver` 665, 799, 884, 1186 (flatten pre-hop target), 1853; `TemporalFrame` 999, 1087, 1702, 1748 | 30 lines | LAW — each is a demand for a navigation over a source; through the one lookup (`navTarget(source, class, step, head)`) it receives the routed union, i.e. the input to the shape pass. The branch moved twenty sites; the ones it did not are named after this table. |
| (b) a SUBTYPE or CAST source (an arm read through its own class): `CastNav` 49; `CastReRoot` 58; `GraphEmission` 1957/1958 (cast's target set), 3130; `NavExistsMaterial` 161; `NavMaterializer` 320, 936, 1089; `AssociationJoins` 138 (a chain's stop class), 250, 1036; `CorrelatedSubselects` 534, 2013; `DottedExists` 146; `NavProvenance` 48/49 | 17 | LAW / IDENTITY — readers of A5 and A11; they stay, reading an arm's own source |
| (c) a ROOT (the query's class, an aggregation-aware root, a graph root, a constructed scope): `StoreResolver` 591, 2756, 3432; `GraphEmission` 1144, 1244, 1281, 1298, 1456, 2108; `ObjectReferenceDecode` 131 | 10 | FACT — mapping dispatch, not union knowledge; the root of a union class is the stack |
| (d) a union's MEMBERS: `AssociationJoins` 697 (`memberAssocKeyReads`: each member's own step's source reads, widened through the union projection) | 1 | LAW — the law applied by hand: the query side already reads the arms' own steps here |
| (e) nested union target widened for downstream hops: `NestedUnionKeys` 34 | 1 | LAW — a stack's arms project what a later hop reads |
| (f) model-to-model binding-shape probes: `CorrelatedSubselects` 2196, 2242 | 2 | unrelated to unions |

Sum 61 (30 + 17 + 10 + 1 + 1 + 2). `ClassSources`' own `get(...)` delegations (184, 197, 272,
501, 759, 801) are the builders, not readers, and are not in the 61. Read-only diff of the parked
branch for `StoreResolver`: it moved 665 and 799 onto the one lookup and left 884, 1186 and 1853
(the materialize callbacks and the flatten pre-hop target) on the plain lookup; the same diff
for the other eight files of group (a) is a one-command check the design page should record
before it counts sites.

**B2 — the widening family.** `Pipelines.widenConcatenateForKeys` 1199–1256 (flatten the
concatenate, re-add each missing key to EVERY member reading its own column, a typed NULL of a
sibling's kind where a member lacks it, LOUD when no member carries it — the branch's verdict
text, 139 rows), `widenConcatenateBelow` 1172, `widenForCondition` 1262, `widenUnionMember`
1303–1369, `widenPipeForJoinKeys` 1872; consumers `NavMaterializer` 258, 960, 1067;
`NavExistsMaterial` 174; `ChainedExists` 125, 142; `GraphEmission` 691, 822, 1349, 2577;
`Substitution` 3285; `DottedExists` 178; `Pipelines` 644; `AssociationJoins` 234, 469–477, 1019;
`StoreResolver` 2003; `NestedUnionKeys` 55. Label: NO READER once the arms project their route
keys (step 1's builder already does: `ClassSources` 458–465). The one rule inside it worth
keeping is the per-arm NULL of a sibling's kind, which is the stack's projection rule (A3) and
lives in the builder, never as a post-hoc widening. Judges: `union::partial`, every union family,
`testUnionWithExistsFilter` (ChainedExists), the exists families.

**B3 — the merged-scan marker `unionScan`.** `Pure.java` 433/897, `NativeFn` 653,
`RelationPredicates` 44–47 (identity for the lowering), `Pipelines` 843 (walk), 1178/1213/
1305/1333/1346 (widen through it), 1839 `isUnionScan`, 1854 `containsConcatenate` ("is this a
union body"), `TemporalFrame` 1782/1861 (temporal filters through it). Label: SHAPE — the
structural mark of A10; stays while A10 stays. The question it answers for readers ("is this
source a stack") already has a typed home (`ClassSource.UNION_SET_ID`, `ClassSource` 55: every
query-side builder sets it — `ClassSources` 335/480/862, `UnionHeads` 126 — and nothing reads it
yet); B6 moves the question there.

**B4 — `UnionHeads`** (411 lines): the `#uN` heads of a query-side `concatenate` of navigation
chains; one member per branch (hop 0 through `navTarget` at 286, a second hop inside the
member), the members aligned by key NAME (a key a member lacks projects NULL; same-named keys
share), the head's condition the OR of the branches' — the same alignment as step 1's routed
builder, a different trigger. Label: LAW applied to a query-side stack; stays in 2b; one builder
with `routedUnionSource` in B6. Judge: `projection::function::concatenate::testConcatenateInQualifierWithComplexReturnType`
(the `unionalias_0.ID = root.FIRMID or … ADDRESSID` golden, cross matches included).

**B5 — `mixedUnionSource` and the child union:** A12.

**B6 — `ImportDataFlow`.** `compiler/spec/ImportDataFlow.java` 51 reads `unionKeyThreads`;
`Typer` 2072–2092 widens the execute's result type; `ExecuteChainAssembly` 502–505 and
`ImportDataFlowAppend` append the threads where the frame executes. Label: reader of A11 (stays).
NO corpus judge: its one test, `pureToSqlQuery::testImportDataFlow`, sits on the fail roster
behind the `routeFunction` wall; no unit test names it (grep: only the JDBC and architecture
censuses). Witness W3.

**B7 — the set-pin facts and the graph set hint:** A17; `GraphEmission` 1130–1138 is the one
graph-fetch site still routing by head string (2a left it "until 2b emits routes").

**B8 — the paired predicate for graph children:** `GraphEmission` 1183, 1776, 2576 read
`TypedNavigate.pairedPredicate` (A6 sub-rule b). Label: SHAPE 2's strict-pairing variant, chosen
by the consumer (graph) not the mapping; the chooser must keep it as a per-consumer choice.

### 10.3 What has no reader today (found on the way)

- `UnionRoute` / `classifyUnionRoutes` poison "MULTI-route dispatch outside union members is a
  roadmap feature" (241): with route lists it is a plain route list.
- `LiftMidStep` inbound aliases `nl__<prop>__inb__<join>` (2295): readable only inside the
  thread that made them; the branch proved nothing else may read them.
- `sameTableInheritanceMerge` (2026): a navigator-side special case of A10 that disappears when
  the merge is a pass over the target stack (the route then reads the physical column because
  the merged arm projects it).

### 10.4 The three open questions of §8.6, answered from the logs (not patched)

1. **"`routeFunction` lost 4 rows."** Not a loss. In both lanes' last logs the four rows are
   `pureToSqlQuery::addDriverTablePkForProject`, `routing::multipleexpressions::testPlatformExpressionDependencyOnAFromExpression`
   and `…2`, `routing::testRoutingOfSimpleQualifiedProperty`: all four are FAIL rows already on
   the committed fail rosters (`unknown function 'routeFunction'`), none is a LOST row (zero LOST
   lines mention routing in either lane). 34 engine functions call `routeFunction`; the only
   test-named ones in the DuckDB lane are those four plus `testImportDataFlow`, all rostered.
   The note was a misread of the failure-family bucket. Nothing to design for.
2. **"The aggregation-aware union members project no scalar columns (`[[], []]`)."** The text
   `[[], []]` appears in neither lane's last log. The 17 `aggregationAware` rows lost on the
   branch (`testRewrite::objectGroupBy::*`) all fail with `demands key column '__route0_0',
   which NO union member carries (members' rows: [[stc_…RelationalActivity___sql, …,
   stc_…AggregationAwareActivity___rewrittenQuery, …$member, res_activities,
   id__pk_metamodel_activities]])`: the union is the METAMODEL's activities union
   (`RelationalActivity` / `AggregationAwareActivity` over `metamodel_activities`, one merged
   same-table thread) that the aggregation-aware tests read to print their routing activity
   (`AggAwareActivities`). It is item A10, reached through a route list: the merged scan did not
   project the route's key. No corpus mapping has an `AggregationAware` set as a union member.
3. **The pinned-single-to-subclass route.** `JoinChainEmission` 292–325: when a property has
   exactly ONE route entry, the declared target class has no main table of its own, and the
   routed set's class is a strict subclass, the navigation lands on THAT class and the route is
   removed from `unionRoutes` (keys unsuffixed). The named fixture,
   `graphFetch::tests::subType::RootSubTypeWithSubtypeLevelPropertyUnionMapping` (`Street[street]
   extends [a]` carrying `coordinate[CoordinateSet1]` / `coordinate[CoordinateSet2]` into a
   `Coordinate` union), is `test.AlloyOnly`: it is in neither register; its `…Checked` twin fails
   on H2 for an unrelated dialect reason (`nested checked defects reached a dialect without list
   lambdas`) and is on the H2 roster; the DuckDB log never mentions it. So the corpus does NOT
   judge this rewrite through that fixture. Where the rewrite fires with a judge is the
   metamodel: a hierarchy root without a relational set (`RelationalOperationElement`,
   `SetImplementation`) reached by one pinned route to a subclass set — judged by
   `executionPlan::tests` ×28. Under route lists this rewrite is the general rule (a route names
   its set's function, and a subclass set's function is what it is); the disagreement the branch
   saw must be re-measured on those 28 and on witness W4, not on the AlloyOnly fixture.

### 10.5 Witnesses the corpus cannot supply (named, not written)

- **W1 — mixed union (A12):** a hand-written mapping with one `Relational` and one `Pure`
  member of one class and a class-typed property routed per member; rows through the child.
- **W2 — a route INTO a `~func` member (A13):** a union of one table set and one `Relation` set
  with a navigator routing into both; the route reads a column of the function's rows.
- **W3 — `importDataFlow` over a union (A11/B6):** a class query over a two-member union with
  `RelationalExecutionContext(importDataFlow=true)` asserting the `<pk>_0` / `<pk>_1` columns.
- **W4 — the single pinned route to a subclass set (10.4 item 3):** the `RootSubTypeWith
  SubtypeLevelPropertyUnionMapping` shape on DuckDB, rows asserted (the fixture's own six rows).
- **W5 — route keys inside a merged same-table scan (A10):** `UnionTargetLeanJoinTest` covers
  two tables; a two-filter one-table union with a routed navigator is missing.

### 10.6 The judge table for one build, by item

DuckDB register families: `union::` 89 (of which `multipleChainedJoins` 17, `sqlQueryMerging`
8, `partial` 6, `specialUnion` 8, `optimized` 4, `extend` 11), `extend::` 37, `modelJoin::advanced`
21, `executionPlan::tests` 28, `aggregationAware` 114, `milestoning::union` 3, `projection::
function::concatenate` 1. H2: `union::` 88, `modelJoin::advanced` 21. Unit: `RoutedNavigateTest`,
`UnionTargetLeanJoinTest`, `ResolveUnionTest` ×6, `ResolveUnionChainTest`, `RoutedChainKeyTest`,
`MappedInClosureTest` ×2, `UnionJoinMappedPropertyTest`, `InheritanceIntegrationTest` ×5, the
probe tests `ResolveUnion{V4,SelfJoin,MultiHop,OuterDate,Jtc}ProbeTest`, `ResolveGraphUnionProbeTest`.
The branch's 226 DuckDB losses by family, as the map of what the build touches: `mapping::*`
120 (union 49, inheritance/extend/subType 20, the rest merged-scan metamodel reads), `typeInference`
20, `milestoning` 19, `aggregationAware` 17, `executionPlan::tests` 15, `alloy::connections` 8,
`tds` 6, `modelJoins` 6, `testDataGeneration` 5, `functions` 3, `projection` 2, `graphFetch` 4,
`query` 1.

## 11. Design page — a demand on a stack flows into the arms (2026-09-14; USER: "write it down and do it end to end")

### 11.0 The four measures (reported with every batch, beside the rows)

The program these batches serve: the compile step holds every fact, so the normalizer is one
plain mapping-to-function transform; one function per set; navigation expanded on the query
side under the queried mapping; the execution shape chosen in the lowering; every quiet miss
loud; one implementation per question. Rows staying at 108 / 444 while the normalizer grows is
not progress. So every batch reports, next to LOST / GAINED on both lanes:

| measure | main today | target | how counted |
|---|---|---|---|
| M1 union-synthesis lines | 3,287 (`UnionSynthesis.java`) | under 700 (route classification, the member enumeration, the same-table set collapse, the concatenate emitter, the key-thread fact, the chain-walk helpers) | `wc -l` |
| M2 query-side union builders | 4 (the normalizer's body, `routedUnionSource`, `UnionHeads`, `mixedUnionSource`) | 1 (the stack builder; `UnionHeads` and the mixed builder fold into it in B6) | count of places that build a `TypedConcatenate` of class arms |
| M3 navigator knowledge computed in the normalizer | 30 named functions (grep of §10 A8, A9-inbound, A15, A16, A17) | 0 (a route names a function and a join; the query side composes) | the same grep |
| M4 quiet arms on the union path | 26 (`return; //`, `continue; //`, `putIfAbsent`, swallowed catch in `UnionSynthesis`) | 0 outside a receipted "the miss is the answer" | the same grep |

### 11.1 The capability, in one paragraph

A class bound as `Operation { f }` has a function whose body is `m1() -> concatenate(m2()) …`,
each call a set's own function. The resolver's `ClassSources.build` sees a body that ends in a
`concatenate` of user calls instead of a `map` terminal and builds the class source as a STACK:
it resolves each call to its set's binding (own bindings, then includes), takes each arm's
`ClassSource` as it is (a relational set, a `~func` set, a model-to-model set, a nested stack —
the builder does not care where an arm came from), and applies the law: the stack's row is the
class's scalar properties by each arm's bindings (a typed NULL where an arm has none), the
embedded ctors' leaves per arm and recomposed above, the subtype-dispatch columns and witness
per arm from the arm's class, and every arm's own primary key under `<pk>_<i>`; the stack's
navigations are its arms' navigate steps composed into ONE step above the concatenate per
property (the first cut's `liftOf`), whose routes are the arms' routes and whose source reads
are columns each arm projects from its own row. A demand for a navigation on the stack therefore
flows into the arms and the answers stack. The lowering of that step is the five-shape pass:
routes of one shape share a key (shape 1), different shapes keep their keys and OR (shape 2), a
route whose rows carry its mids is push-into-arm (shape 3), the bridge union is the dialect's
choice (shape 4), anything else is per-arm (shape 5). Arms that are filtered scans of ONE table
with agreeing slots merge into one arm before projection (the same-table pass, A10), with the
route keys read inside the merged arm gated like every other column.

### 11.2 Every §10 item, decided

| item | becomes |
|---|---|
| A1 arm list | the calls in the union function, in the normalizer's member order (inheritance ops enumerate leaves as today) |
| A2 pair entries | injected onto the SOURCE SET's own record before synthesis (the `continue` at `AssociationSynthesis` 173–186 goes), so the member's own function carries them; the union body's copy has no reader |
| A3 scalar columns | the stack's projection per arm (arm binding or typed NULL) |
| A4 embedded | per arm: the binding's `TypedNewInstance` leaves become `emb__` columns; the stack binds the recomposed ctor over them (the union body's rule, ported to typed arms) |
| A5 subtype columns | per arm from the arm's class: `stc_` columns and the `$member` witness, same names (the named-column protocol stays until B6) |
| A6 lifts | the composed step above the stack. Three rules kept as lift rules, computed from the arms' steps: (a) a condition that reads only the target's projected PROPERTIES reads them directly on the target stack (the engine's merge-by-name cross-match; the paired variant stays for graph children); (b) keys are shared across arms only when the arms' route SETS agree, else per arm (the union-to-union trap row); (c) a non-union target reached by every arm through the same join into distinct private sets routes to the LAST arm's binding |
| A7 inverse ends | covered by A2: once the pair is on the member's record, the member's function has the step and the lift composes it |
| A8 published keys | deleted; a route's key is minted by the checker from the condition's target reads |
| A9 chains | outbound: the arm's own chained step, lifted (shape 3 when arms differ); inbound: the route's rows carry the mids (`route(rows -> join(mids), first-hop cond)`, the branch's `routeList`) |
| A10 same-table merge | a pass inside the stack builder over arms of the form `filter(scan(T) [with slots], pred)`: one arm, CASE-gated columns, the OR of the filters; the `unionScan` marker is not needed (the merged arm is a plain projection) |
| A11 per-arm primary keys | projected by the stack from each arm's binding; `unionKeyThreads` stays a normalizer fact over the members' bindings (store facts, not navigator knowledge); the shared `<col>__pk_<table>` stays until B6 (CastReRoot) |
| A12 mixed | untouched in this build: no judge (W1); folds into the stack builder in B6 |
| A13 `~func` arms | just arms |
| A15 navigator side | route lists: every routed property emits one `legacyNavigate` with `[route(setFn(), rows, cond), …]` (the branch's emitter); a pinned single route is a list of one; a root route beside member routes is a route whose target is the class extent; the "roadmap" poison and the drop rule go (a class-typed PM always ends in a navigate; an unmapped target is loud at demand under the queried mapping) |
| A16 re-synthesis | deleted with its three criteria |
| A17 facts | `routedTargetSets`, `routedSets`, `linkKeys`, `mixedUnions` (kept only for A12) die; `unionMembers`, `routedTargetClasses`, `unionKeyThreads`, `poisons` stay |
| B2 widening | made LOUD on a stack during the build; deleted when no corpus row reaches it |
| B3 marker | deleted with A10's rewrite |
| B4 `UnionHeads` | untouched (B6) |
| B6 `importDataFlow` | untouched (fact stays); W3 named |

### 11.3 The legs (each: compile, corpus both lanes, chain, record, push, CI watched)

- **Leg 1 — every set is a function (neutral).** Drop the member exclusion (`MappingNormalizer` 312): union members bind by set id like every non-root set. Inject association pair entries onto the source set's own record (A2). Named rows: none change — 0 LOST, 0 GAINED both lanes; the pair-entry witnesses (`extend::testExtendsForPropertyMappingWithUnion`, `union::optimized::*`) stay green.
- **Leg 2 — the cutover (one landing).** (i) `ClassBinding.Operation` and the union function as a concatenate of the members' calls; (ii) the stack builder in `ClassSources` with A3–A6, A9, A10, A11; (iii) the route-list emitter with no drop rule; (iv) the deletions of every NO READER item, the widening made loud then removed. Named rows that turn on it: the branch's 226 (10.6's family map) must all be back to green — `mapping::union` 49, `typeInference` 20, `milestoning` 19, `aggregationAware` 17, `executionPlan::tests` 15, `alloy::connections` 8, `tds` 6, `modelJoins` 6, `testDataGeneration` 5, `functions` 3, `graphFetch` 4, `projection` 2, `query` 1 — and the two R6 rows `projection::qualifier::testFilterInQualifierWithFilterInMapping*` stay green without the re-synthesis block. Judges per item as in 10.1. Work order inside the leg: stack of scalar arms first (A1, A3, A11), then A4/A5, then the lifts (A6, A9), then the merge (A10), then the emitter switch, then deletions; the corpus subset `-Drcorpus.only` on the union and metamodel families between items, both lanes before the chain.
- **Leg 3 — B6.** `UnionHeads` and the mixed builder onto the stack builder (W1 first), `CastReRoot`'s typed key, the `Operation` binding's own witnesses (W2–W5).

**Related.** `docs/MAPPING_CLEAN_SHEET.md` (§2, §3, §4.2, Layer 5, E6);
`docs/NORMALIZER_CLEAN_SHEET_HOMEWORK_2026_09_13.md` §6 (B3.1b design, B3.2 receipts, the
B3 arc audit's deferrals 1, 4, 5 — all closed by this design).
