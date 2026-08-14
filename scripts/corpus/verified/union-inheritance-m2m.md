# Verified: Operation mappings, mapping inheritance, M2M, AggregationAware

Every snippet below was run through **real legend-engine 4.138.2** (the
`engine-runner` classpath), parse **and** compile:

```
J=$HOME/jdk/jdk-21.0.11+10/Contents/Home
R=/Users/neemsandv/legend/legend-lite/tools/engine-runner
$J/bin/java -cp "$R/target/classes:$(cat $R/cp.txt)" perf.ParseMain <file> --compile
```

`[compiles]` in the runner output means parse **and** compile succeeded.
`[parse-only: …]` means the grammar accepted it but the compiler rejected it.

Full standalone files: `scripts/corpus/verified/union-inheritance-m2m/*.pure`.
**All 12 files report `[compiles]`.** Nothing in this document is
parse-only — the parse-only cases are recorded in the *Negatives* section at
the bottom, and those are deliberately-wrong spellings, not snippets to copy.

---

## 0. The complete set of Operation types legend-engine registers

There are **exactly four**, and the mapping is by **exact fully-qualified
function name** — there is **no short form** and there is **no
`intersection`**. Authoritative source:
`legend-engine-core/legend-engine-core-base/legend-engine-core-language-pure/legend-engine-language-pure-grammar/src/main/java/org/finos/legend/engine/language/pure/grammar/from/mapping/OperationClassMappingParseTreeWalker.java:41-46`
(mirrored inverse in
`.../legend-engine-protocol-pure/.../mapping/OperationClassMapping.java:27-31`,
enum in `.../mapping/MappingOperation.java`):

| Grammar function path | `MappingOperation` enum | Argument shape |
|---|---|---|
| `meta::pure::router::operations::union_OperationSetImplementation_1__SetImplementation_MANY_` | `STORE_UNION` | `(id, id, …)` |
| `meta::pure::router::operations::special_union_OperationSetImplementation_1__SetImplementation_MANY_` | `ROUTER_UNION` | `(id, id, …)` |
| `meta::pure::router::operations::inheritance_OperationSetImplementation_1__SetImplementation_MANY_` | `INHERITANCE` | `()` — no arguments |
| `meta::pure::router::operations::merge_OperationSetImplementation_1__SetImplementation_MANY_` | `MERGE` | `([id, id, …], <lambda>)` |

Grammar (`.../antlr4/mapping/operationClassMapping/OperationClassMappingParserGrammar.g4`):

```antlr
operationClassMapping:  functionPath (parameters | mergeParameters) (SEMI_COLON)? EOF ;
parameters:             PAREN_OPEN (identifier (COMMA identifier)*)? PAREN_CLOSE ;
mergeParameters:        PAREN_OPEN setParameter COMMA validationLambda PAREN_CLOSE ;
setParameter:           BRACKET_OPEN (identifier (COMMA identifier)*)? BRACKET_CLOSE ;
```

`functionPath` is any `qualifiedName`, so **any** name parses; the walker then
does `funcToOps.get(text)` and silently stores `null` for an unknown name.
That is why every misspelling below is parse-only rather than a parse error.

---

## 1. Operation `union` — legs in DIFFERENT tables

File: `union-inheritance-m2m/operation-union.pure` — **compiles**.
Upstream source: `legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-emit/src/test/resources/relational-emit-models/relational-store-union/mapping/customerMapping.pure`.

```pure
###Mapping
Mapping demo::CustomerStoreUnionMapping
(
  demo::union::Customer[euCustomer]: Relational
  {
    ~primaryKey
    (
      [demo::store::CustomerDB]EuCustomerTable.id
    )
    ~mainTable [demo::store::CustomerDB]EuCustomerTable
    name: [demo::store::CustomerDB]EuCustomerTable.name,
    region: [demo::store::CustomerDB]EuCustomerTable.region
  }

  demo::union::Customer[usCustomer]: Relational
  {
    ~primaryKey
    (
      [demo::store::CustomerDB]UsCustomerTable.id
    )
    ~mainTable [demo::store::CustomerDB]UsCustomerTable
    name: [demo::store::CustomerDB]UsCustomerTable.name,
    region: [demo::store::CustomerDB]UsCustomerTable.region
  }

  *demo::union::Customer: Operation
  {
    meta::pure::router::operations::union_OperationSetImplementation_1__SetImplementation_MANY_(euCustomer, usCustomer)
  }
)
```

**Gotcha**: the legs must be **non-root** (no `*`) and the `Operation` element
must carry the `*` — otherwise two roots compete for the same class.

---

## 2. Operation `special_union` (ROUTER_UNION) + a leg that is EMPTY + explicit root set ID

File: `union-inheritance-m2m/operation-special-union-empty-leg.pure` — **compiles**.
Upstream source for `special_union`: `.../relational-emit-models/relational-router-union/mapping/productMapping.pure`.

```pure
###Mapping
Mapping demo::ProductRouterUnionMapping
(
  demo::union::Product[listedProduct]: Relational
  {
    ~primaryKey
    (
      [demo::store::ProductDB]ListedProductTable.id
    )
    ~mainTable [demo::store::ProductDB]ListedProductTable
    code: [demo::store::ProductDB]ListedProductTable.code,
    description: [demo::store::ProductDB]ListedProductTable.description
  }

  // EMPTY leg — ~mainTable and ~primaryKey only, zero property mappings
  demo::union::Product[otcProduct]: Relational
  {
    ~primaryKey
    (
      [demo::store::ProductDB]OtcProductTable.id
    )
    ~mainTable [demo::store::ProductDB]OtcProductTable
  }

  // Non-root set IDs above; the Operation is the root and carries its OWN set ID
  *demo::union::Product[productRoot]: Operation
  {
    meta::pure::router::operations::special_union_OperationSetImplementation_1__SetImplementation_MANY_(listedProduct, otcProduct)
  }
)
```

**Gotcha**: an empty leg is legal — a `Relational` class mapping needs only
`~mainTable`. `~primaryKey` is optional too: §1 with every `~primaryKey (…)`
block deleted still reports `[compiles]` (the table's declared PK is used). The
root `Operation` element may also carry a set ID (`*Class[rootId]`), which is
what downstream routing/`extends` references.

---

## 3. Union whose legs live in DIFFERENT Databases

File: `union-inheritance-m2m/operation-union-cross-database.pure` — **compiles**
(same body as §1, but `EuCustomerTable` sits in `demo::store::EuDB` and
`UsCustomerTable` in `demo::store::UsDB`).

```pure
  demo::union::Customer[euCustomer]: Relational
  {
    ~primaryKey ( [demo::store::EuDB]EuCustomerTable.id )
    ~mainTable [demo::store::EuDB]EuCustomerTable
    name: [demo::store::EuDB]EuCustomerTable.name,
    region: [demo::store::EuDB]EuCustomerTable.region
  }
  demo::union::Customer[usCustomer]: Relational
  {
    ~primaryKey ( [demo::store::UsDB]UsCustomerTable.id )
    ~mainTable [demo::store::UsDB]UsCustomerTable
    name: [demo::store::UsDB]UsCustomerTable.name,
    region: [demo::store::UsDB]UsCustomerTable.region
  }
  *demo::union::Customer: Operation
  {
    meta::pure::router::operations::union_OperationSetImplementation_1__SetImplementation_MANY_(euCustomer, usCustomer)
  }
```

**Gotcha**: the *compiler* does not enforce one store per union — cross-store
legs compile with plain `union_` (STORE_UNION) too. Splitting across stores is
what `special_union_` (ROUTER_UNION) is semantically for; both spellings were
verified to compile against two Databases.

---

## 4. Operation `merge` — set list in brackets + validation lambda

File: `union-inheritance-m2m/operation-merge.pure` — **compiles**.
Upstream source: `legend-engine-language-pure-grammar/src/test/java/.../roundtrip/TestMappingGrammarRoundtrip.java#testMergeModelMapping`.

```pure
###Mapping
Mapping demo::MergeMapping
(
  demo::merge::Person[p1]: Pure
  {
    ~src demo::merge::SourcePersonWithFirstName
    id: $src.id,
    firstName: $src.sourceFirstName
  }

  demo::merge::Person[p2]: Pure
  {
    ~src demo::merge::SourcePersonWithLastName
    id: $src.id,
    lastName: $src.sourceLastName
  }

  *demo::merge::Person: Operation
  {
    meta::pure::router::operations::merge_OperationSetImplementation_1__SetImplementation_MANY_([p1,p2],{s1: demo::merge::SourcePersonWithFirstName[1], s2: demo::merge::SourcePersonWithLastName[1]|$s1.id == $s2.id})
  }
)
```

**Gotcha**: `merge` is the ONLY operation whose set list is bracketed
(`[p1,p2]`) — the lambda parameters are the **source** classes of each leg, one
per leg, in leg order. The merged properties must be `[0..1]` on the target
class (each leg only supplies some of them).

The degenerate lambda form also compiles
(`union-inheritance-m2m/operation-merge-trivial-lambda.pure`, from
`legend-engine-xts-service/.../multiParamM2MServiceMerge.pure`):

```pure
    meta::pure::router::operations::merge_OperationSetImplementation_1__SetImplementation_MANY_([s1,s2],{|true})
```

---

## 5. Operation `inheritance` — zero arguments

File: `union-inheritance-m2m/operation-inheritance.pure` — **compiles**.
Upstream source: `.../relational-emit-models/relational-operation-mapping/mapping/vehicleMapping.pure`.

```pure
###Mapping
Mapping demo::VehicleOperationMapping
(
  *demo::vehicle::Car[car]: Relational
  {
    ~primaryKey ([demo::store::VehicleDB]CarTable.id)
    ~mainTable [demo::store::VehicleDB]CarTable
    registration: [demo::store::VehicleDB]CarTable.registration,
    doors: [demo::store::VehicleDB]CarTable.doors
  }

  *demo::vehicle::Truck[truck]: Relational
  {
    ~primaryKey ([demo::store::VehicleDB]TruckTable.id)
    ~mainTable [demo::store::VehicleDB]TruckTable
    registration: [demo::store::VehicleDB]TruckTable.registration,
    payloadTonnes: [demo::store::VehicleDB]TruckTable.payloadTonnes
  }

  *demo::vehicle::Vehicle: Operation
  {
    meta::pure::router::operations::inheritance_OperationSetImplementation_1__SetImplementation_MANY_()
  }
)
```

**Gotcha**: `inheritance` takes **empty parens** — legs are discovered from the
class hierarchy, not listed. Unlike `union`, the subtype legs here ARE roots
(`*Car`, `*Truck`), because they are roots for their *own* classes.

---

## 6. Mapping `extends [parentSetId]` — class-mapping inheritance chain

File: `union-inheritance-m2m/mapping-extends.pure` — **compiles**.
Upstream source: `core_relational/relational/tests/mapping/extends/testExtendsWithGroupBy.pure`,
grammar rule `mappingElement: (STAR)? qualifiedName (BRACKET_OPEN mappingElementId BRACKET_CLOSE)? (EXTENDS BRACKET_OPEN superClassMappingId BRACKET_CLOSE)? COLON parserName …`.

```pure
###Mapping
Mapping demo::ExtendMapping
(
  *demo::extend::A[a]: Relational
  {
    ~primaryKey ([demo::store::ExtendDB]ABC.id)
    ~mainTable [demo::store::ExtendDB]ABC
    id: [demo::store::ExtendDB]ABC.id,
    aName: [demo::store::ExtendDB]ABC.aName
  }

  *demo::extend::B[b] extends [a]: Relational
  {
    bName: [demo::store::ExtendDB]ABC.bName
  }

  *demo::extend::C[c] extends [b]: Relational
  {
    cName: [demo::store::ExtendDB]ABC.cName
  }
)
```

**Gotcha**: the child must NOT restate `~mainTable` — upstream
`RelationalInstanceSetImplementationProcessor` rejects it with *"Cannot specify
main table explicitly for extended mapping"*; the parent's table is inherited,
so `extends` is same-table only. `extends [x]` refers to a **set ID**, not a
class name, and the brackets around the ID are mandatory.

---

## 7. Mapping `include` — plain, `include mapping` keyword, and store substitution

File: `union-inheritance-m2m/mapping-include-substitution.pure` — **compiles**
(all three forms in one file).
Grammar: `includeMapping: (INCLUDETYPE|INCLUDE) qualifiedName (BRACKET_OPEN (storeSubPath (COMMA storeSubPath)*)? BRACKET_CLOSE)?`
and `storeSubPath: sourceStore ARROW targetStore`.

```pure
###Mapping
Mapping demo::PersonBaseMapping
(
  *demo::inc::Person[person]: Relational
  {
    ~primaryKey ([demo::store::DevDB]PersonTable.id)
    ~mainTable [demo::store::DevDB]PersonTable
    id: [demo::store::DevDB]PersonTable.id,
    name: [demo::store::DevDB]PersonTable.name
  }
)

###Mapping
Mapping demo::PersonPlainIncludeMapping
(
  include demo::PersonBaseMapping
)

###Mapping
Mapping demo::PersonIncludeMappingKeywordMapping
(
  include mapping demo::PersonBaseMapping
)

###Mapping
Mapping demo::PersonSubstitutedMapping
(
  include demo::PersonBaseMapping[demo::store::DevDB->demo::store::ProdDB]
)
```

**Gotcha**: `include X` and `include mapping X` are BOTH legal and mean the same
thing (`INCLUDE` vs `INCLUDETYPE` token); the composer re-emits `include X`.
All `include` clauses must come **before** any mapping element — the grammar is
`(includeMapping)* (mappingElement)*`. Verified by run: moving the `include`
below a class mapping is a hard **parse** error, `Unexpected token 'demo'.
Valid alternatives: ['extends', '[', ':']` (the parser has already committed to
reading `include` as a class-mapping element name). Substitution takes a
comma-separated list:
`[db::A->db::X, db::B->db::X]`.

---

## 8. `extends` reaching a set ID that arrives via an `include` WITH substitution

File: `union-inheritance-m2m/mapping-extends-across-include.pure` — **compiles**.
Upstream source: `core_relational/relational/tests/mapping/extends/testExtendsWithStoreSubstitution.pure`.

```pure
###Mapping
Mapping demo::AMapping
(
  demo::xsub::A[a]: Relational
  {
    ~primaryKey ([demo::store::Db1]ABC.id)
    ~mainTable [demo::store::Db1]ABC
    id: [demo::store::Db1]ABC.id,
    aName: [demo::store::Db1]ABC.aName
  }
)

###Mapping
Mapping demo::BMapping
(
  include demo::AMapping[demo::store::Db1->demo::store::Db2]

  demo::xsub::B[b] extends [a]: Relational
  {
    bName: [demo::store::Db2]ABC.bName
  }
)
```

**Gotcha**: the child's columns must be qualified with the **substituted**
store (`Db2`), not the one the parent literally wrote (`Db1`) — substitution has
already rewritten the parent by the time the child is resolved. Both databases
must declare the same table/columns.

---

## 9. M2M (`Pure`) — chain (M2M whose `~src` is itself M2M-mapped) + `~filter` + enum transformer

File: `union-inheritance-m2m/m2m-chain-enum-filter.pure` — **compiles**.
Grammar: `.../antlr4/mapping/pureInstanceClassMapping/PureInstanceClassMappingParserGrammar.g4`,
`propertyMapping: … STAR? COLON (ENUMERATION_MAPPING identifier COLON)? combinedExpression`.

```pure
###Mapping
Mapping demo::M2MMapping
(
  demo::m2m::StaffMember[staffMember]: Pure
  {
    ~src demo::m2m::Employee
    ~filter $src.dept != 'Retired'
    fullName: $src.firstName + ' ' + $src.lastName,
    dept: $src.dept,
    type: EnumerationMapping TypeMapping: $src.rawType
  }

  // chain: this M2M's ~src (StaffMember) is itself the target of an M2M above
  *demo::m2m::StaffCard[staffCard]: Pure
  {
    ~src demo::m2m::StaffMember
    displayName: $src.fullName->toUpper(),
    department: $src.dept
  }

  demo::m2m::EmployeeType: EnumerationMapping TypeMapping
  {
    CONTRACT: ['FTC', 'FTO'],
    FULL_TIME: ['A']
  }
)
```

**Gotcha**: `~src` and `~filter` carry **no trailing comma** and no semicolon
(deliberate legacy inconsistency, called out in the grammar comment); property
mappings are comma-separated with no trailing comma. The enum transformer is
`prop: EnumerationMapping <enumMappingId>: <expr>` — two colons, and
`<enumMappingId>` is the **name after `EnumerationMapping`** in the enumeration
mapping element, not the enumeration's path. The source expression feeding an
enum transformer must be the raw source-side value (a `String` here), not an
already-typed enum.

---

## 10. M2M union — non-root legs, root set IDs, and target set IDs on property mappings

File: `union-inheritance-m2m/m2m-union-set-ids.pure` — **compiles**.
Upstream source: `TestMappingGrammarRoundtrip.java#testUnionModelMapping`.

```pure
###Mapping
Mapping demo::UnionModelMapping
(
  *demo::mu::Person[personRoot]: Operation
  {
    meta::pure::router::operations::union_OperationSetImplementation_1__SetImplementation_MANY_(p1,p2)
  }

  *demo::mu::Firm[firmRoot]: Operation
  {
    meta::pure::router::operations::union_OperationSetImplementation_1__SetImplementation_MANY_(f1,f2)
  }

  demo::mu::Firm[f1]: Pure { ~src demo::mu::S_Firm  legalName: 'f1 / ' + $src.name }
  demo::mu::Firm[f2]: Pure { ~src demo::mu::S_Firm  legalName: 'f2 / ' + $src.name }

  demo::mu::Person[p1]: Pure
  {
    ~src demo::mu::S_Person
    ~filter $src.fullName->startsWith('Johny')
    firstName: $src.fullName->substring(0, $src.fullName->indexOf(' ')),
    lastName: $src.fullName->substring($src.fullName->indexOf(' ') + 1, $src.fullName->length()),
    firm[f1]: $src.firm
  }

  demo::mu::Person[p2]: Pure
  {
    ~src demo::mu::S_Person
    ~filter $src.fullName->startsWith('_')
    firstName: 'N/A',
    lastName: 'N/A',
    firm[f2]: $src.firm
  }
)
```

**Gotcha**: `firm[f1]:` is the **target** set ID — it pins which leg of the
`Firm` union this leg of `Person` navigates into, so each union leg stays
internally consistent. `Operation` elements may be declared before the legs
they name; the compiler resolves set IDs mapping-wide, not top-to-bottom.

---

## 11. `AggregationAware` — `Views` / `~modelOperation` / `~aggregateMapping` / `~mainMapping`

File: `union-inheritance-m2m/aggregation-aware.pure` — **compiles**.
Upstream source: `.../relational-emit-models/relational-aggregation-aware/mapping/saleMapping.pure`.

```pure
###Mapping
Mapping demo::SaleAggregationAwareMapping
(
  *demo::agg::Sale: AggregationAware
  {
    Views:
    [
      (
        ~modelOperation:
        {
          ~canAggregate true,
          ~groupByFunctions
          (
            $this.salesPerson
          ),
          ~aggregateValues
          (
            ( ~mapFn: $this.revenue, ~aggregateFn: $mapped->sum() ),
            ( ~mapFn: $this.discount, ~aggregateFn: $mapped->sum() )
          )
        },
        ~aggregateMapping: Relational
        {
          ~primaryKey
          (
            [demo::store::AggDB]SalesByPersonTable.salesPerson
          )
          ~mainTable [demo::store::AggDB]SalesByPersonTable
          salesPerson: [demo::store::AggDB]SalesByPersonTable.salesPerson,
          revenue: [demo::store::AggDB]SalesByPersonTable.revenue,
          discount: [demo::store::AggDB]SalesByPersonTable.discount
        }
      ),
      (
        ~modelOperation:
        {
          ~canAggregate false,
          ~groupByFunctions
          (
            $this.region
          ),
          ~aggregateValues
          (
            ( ~mapFn: $this.revenue, ~aggregateFn: $mapped->sum() )
          )
        },
        ~aggregateMapping: Relational
        {
          ~primaryKey
          (
            [demo::store::AggDB]SalesByRegionTable.region
          )
          ~mainTable [demo::store::AggDB]SalesByRegionTable
          region: [demo::store::AggDB]SalesByRegionTable.region,
          revenue: [demo::store::AggDB]SalesByRegionTable.revenue
        }
      )
    ],
    ~mainMapping: Relational
    {
      ~primaryKey
      (
        [demo::store::AggDB]SalesTable.id
      )
      ~mainTable [demo::store::AggDB]SalesTable
      salesPerson: [demo::store::AggDB]SalesTable.salesPerson,
      region: [demo::store::AggDB]SalesTable.region,
      revenue: [demo::store::AggDB]SalesTable.revenue,
      discount: [demo::store::AggDB]SalesTable.discount
    }
  }
)
```

**Gotcha**: the keyword is `~aggregateMapping` (per view), **not**
`~aggregateViews`; `Views:` is capitalised and takes a `[ ( … ), ( … ) ]` list
of parenthesised pairs; `Views` and `~mainMapping` are separated by a **comma**;
the two magic variables are `$this` (inside `~groupByFunctions` / `~mapFn`) and
`$mapped` (inside `~aggregateFn`). Every column an aggregate view exposes must
also be mapped in `~mainMapping`.

---

## Negatives — spellings that PARSE but do NOT compile

All four were run; all four print `[parse-only: …]`.

| Spelling tried | Result |
|---|---|
| `union(euCustomer, usCustomer)` (short form) | `Cannot invoke "String.startsWith(String)" because "id" is null` |
| `meta::pure::router::operations::union(euCustomer, usCustomer)` (no signature suffix) | same NPE |
| `meta::pure::router::operations::intersection_OperationSetImplementation_1__SetImplementation_MANY_(…)` | same NPE — **`intersection` is not a registered operation** |
| `union_…_(euCustomer, nope)` (unknown leg id) | `Can't find class mapping 'nope' in mapping 'demo::CustomerStoreUnionMapping'` |

The identical NPE for the first three is the tell: the walker does
`funcToOps.get(functionPath)` and stores `null`, then the compiler
dereferences it. **There is no short form for any Operation — the exact
`…_OperationSetImplementation_1__SetImplementation_MANY_` FQN is required.**

One surprising positive: `merge_…_(euCustomer, usCustomer)` — merge written
with the *union* argument shape, i.e. no brackets and no lambda — **compiles**.
It takes the `parameters` grammar branch and produces a plain
`OperationClassMapping` with `operation = MERGE` and no `validationFunction`.
Do not use it; it is a merge with no merge predicate.
