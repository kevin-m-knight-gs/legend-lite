# Embedded Property Mappings & Association Mappings — VERIFIED syntax

Every snippet below was copied from legend-engine sources (paths cited per feature), assembled
into a standalone `.pure` file (Classes + Association + Database + Mapping), and run through:

```
J=$HOME/jdk/jdk-21.0.11+10/Contents/Home
R=/Users/neemsandv/legend/legend-lite/tools/engine-runner
$J/bin/java -cp "$R/target/classes:$(cat $R/cp.txt)" perf.ParseMain <file> --compile
```

against legend-engine **4.138.2**. Full files live in `embedded-associations/`.
Result: **13/13 parse AND compile.** Nothing here is invented.

| # | File | Parse | Compile |
|---|------|-------|---------|
| 1 | `01-embedded-one-level.pure` | ok | compiles |
| 2 | `02-embedded-nested.pure` | ok | compiles |
| 3 | `03-embedded-otherwise.pure` | ok | compiles |
| 3b | `12-embedded-otherwise-nested.pure` | ok | compiles |
| 4 | `04-embedded-inline.pure` | ok | compiles |
| 4b | `13-embedded-inline-nested.pure` | ok | compiles |
| 5 | `05-association-explicit-ids.pure` | ok | compiles |
| 6 | `06-association-join-chain.pure` | ok | compiles |
| 7 | `07-association-many-many-bridge.pure` | ok | compiles |
| 8 | `08-association-different-set-ids.pure` | ok | compiles |
| 9 | `09-association-xstore.pure` | ok | compiles |
| 10 | `10-association-with-included-mapping.pure` | ok | compiles |
| 11 | `11-association-end-on-embedded-set.pure` | ok | compiles |

---

## Corrections to the guesses in the task brief

Three of the spellings assumed in the request are **not** the real grammar:

1. `prop() { sub: [db]T.col }` — wrong. Embedded blocks use **parentheses**, and the property
   name is **not** followed by `()`: `prop ( sub: [db]T.col )`. The `prop()` form with empty
   parens belongs to `Inline` only.
2. `Assoc: Relational { end1[srcId, tgtId]: @J }` — wrong. The `AssociationMapping ( ... )`
   wrapper is **mandatory** inside the `Relational { }` body. Without it the text still
   *parses* (the grammar reads it as an ordinary class mapping) and then fails to compile
   with ``Can't find class 'demo::Employment'`` — a silent trap.
3. XStore `~src` / `~target` — half wrong. `~src` belongs to the **`Pure` (M2M) class
   mappings**, not to the XStore block. There is **no `~target` token anywhere in
   legend-engine** (`grep -rn "~target" --include=*.java --include=*.pure` → 0 hits).
   XStore ends use `$this` / `$that`.

---

## 1. Embedded property mapping, one level

Source: `legend-engine-xts-relationalStore/.../grammar/src/test/java/.../compiler/test/TestEmbeddedRelationalCompilationFromGrammar.java#testEmbeddedMapping`

```pure
###Mapping
Mapping demo::EmbeddedOneLevel
(
  *demo::Person[person]: Relational
  {
    ~primaryKey
    (
      [demo::db]personTable.id
    )
    ~mainTable [demo::db]personTable
    name: [demo::db]personTable.name,
    address
    (
      line1: [demo::db]personTable.addressLine1
    )
  }
)
```

Gotcha: the embedded block is `prop ( ... )` — parentheses, no colon, no `()` after the
property name; and the block is a normal comma-separated member of the class mapping body.

Parse: ok. Compile: ok.

---

## 2. Embedded nested (three levels)

Source: same test file (two levels) + `TestRelationalGrammarRoundtrip#testEmbeddedRelationalMapping`
(which nests `testProp2 ( ... )` three deep), so depth is unbounded.

```pure
###Mapping
Mapping demo::EmbeddedNested
(
  *demo::Person[person]: Relational
  {
    ~primaryKey ([demo::db]employeeFirmDenormTable.id)
    ~mainTable [demo::db]employeeFirmDenormTable
    name: [demo::db]employeeFirmDenormTable.name,
    firm
    (
      ~primaryKey ([demo::db]employeeFirmDenormTable.legalName)
      legalName: [demo::db]employeeFirmDenormTable.legalName,
      address
      (
        ~primaryKey ([demo::db]employeeFirmDenormTable.address)
        line1: [demo::db]employeeFirmDenormTable.address,
        geo
        (
          country: [demo::db]employeeFirmDenormTable.country
        )
      )
    )
  }
)
```

Gotcha: `~primaryKey ( ... )` may be repeated at each embedded level and takes **no trailing
comma** — the next property follows it directly on the next line.

Parse: ok. Compile: ok.

---

## 3. `Otherwise` embedded

Source: `TestEmbeddedRelationalCompilationFromGrammar#embeddedMappingsWithOtherwise` (line 346):
`") Otherwise ( [firm1]:[db]@PersonFirmJoin) \n"`

```pure
###Mapping
Mapping demo::EmbeddedOtherwise
(
  demo::Firm[firm1]: Relational
  {
    legalName: [demo::db]FirmInfoTable.name,
    otherInformation: [demo::db]FirmInfoTable.other
  }

  demo::Person[alias1]: Relational
  {
    name: [demo::db]employeeFirmDenormTable.name,
    firm
    (
      ~primaryKey ([demo::db]employeeFirmDenormTable.legalName)
      legalName: [demo::db]employeeFirmDenormTable.legalName
    ) Otherwise ( [firm1]: [demo::db]@PersonFirmJoin )
  }
)
```

Gotcha: exact shape is `) Otherwise ( [targetSetId]: [store]@Join )` — the `Otherwise` payload
is **parenthesised**, the set id is in **square brackets before the colon**, and the join is a
store-qualified `@Join`. Whitespace is free: `Otherwise([firm1]:[demo::db]@PersonFirmJoin)`
compiles identically (verified). The referenced set id must be a real class mapping of the
property's type in the same mapping.

Parse: ok. Compile: ok.

### 3b. `Otherwise` on a nested embedded property

`12-embedded-otherwise-nested.pure` — `Otherwise` hangs off the inner `address ( ... )` of a
`firm ( ... )` embedded block; the fallback set supplies `street`, absent from the denorm table.

```pure
    firm
    (
      legalName: [demo::db]T.LN,
      address
      (
        name: [demo::db]T.AN
      ) Otherwise ( [addr1]: [demo::db]@T_Addr )
    )
```

Parse: ok. Compile: ok.

---

## 4. `Inline` embedded

Source: `core_relational/relational/tests/mapping/association/testAssociationEmbedded.pure:113`
(`firm() Inline[f1]`) and `TestRelationalGrammarRoundtrip#testEmbeddedRelationalMapping`
(`something() Inline[TEST_Id]`).

```pure
###Mapping
Mapping demo::EmbeddedInline
(
  demo::Person[p]: Relational
  {
    scope([demo::db]PERSON_FIRM_DENORM)
    (
      firstName: PERSON_FIRSTNAME,
      firm() Inline[f1]
    )
  }

  demo::Firm[f1]: Relational
  {
    scope([demo::db]PERSON_FIRM_DENORM)
    (
      legalName: FIRM_LEGALNAME
    )
  }
)
```

Gotcha: `prop() Inline[setId]` — **empty parens are required** and there is no colon; this is
the one embedded form that uses `()`. The referenced set must map the property's type, and in
practice must read the *same* table as the parent (the columns are spliced in, not joined).

Parse: ok. Compile: ok.

### 4b. `Inline` nested inside an embedded block

`13-embedded-inline-nested.pure` — `address() Inline[addr1]` sits inside `firm ( ... )`.

```pure
      firm
      (
        legalName: LN,
        address() Inline[addr1]
      )
```

Parse: ok. Compile: ok.

---

## 5. Association mapping, explicit source/target set ids

Source: `core_relational/relational/tests/mapping/association/testAssociationMapping.pure:112-116`
(`associationMappingWithIds`); same shape in
`legend-engine-xt-relationalStore-emit/src/test/resources/relational-emit-models/relational-association-implementation/mapping/associationMapping.pure`.

```pure
###Mapping
Mapping demo::AssociationExplicitIds
(
  demo::Person[per1]: Relational
  {
    firstName: [demo::db]personTable.FIRSTNAME
  }

  demo::Firm[fir1]: Relational
  {
    legalName: [demo::db]firmTable.LEGALNAME
  }

  demo::Employment: Relational
  {
    AssociationMapping
    (
      employees[fir1, per1]: [demo::db]@Firm_Person,
      firm[per1, fir1]: [demo::db]@Firm_Person
    )
  }
)
```

Gotcha: the `AssociationMapping ( ... )` wrapper is mandatory — omitting it parses as a class
mapping and dies at compile with ``Can't find class '<association path>'``. Order in
`prop[a, b]` is `[sourceSetId, targetSetId]`, i.e. the set you navigate **from** then **to**,
so the two ends carry the same pair reversed. Both ids are optional when each class has a
single set implementation (`employees: [demo::db]@Firm_Person` alone also compiles).

Parse: ok. Compile: ok.

---

## 6. Association end using a JOIN CHAIN

Source: `core_relational/.../tests/mapping/union/testUnionBiTemporalSelfJoinDuplicateColumn.pure:384`
(`owner[selectAccount, selectPerson]: [biTemporalDB]@Account_To_AccountRole > [biTemporalDB]@AccountRole_To_Party`)
and `testAssociationEmbedded.pure:167`.

```pure
  demo::Employment: Relational
  {
    AssociationMapping
    (
      // fully qualified on every hop
      firm[per1, fir1]: [demo::db]@Person_Contract > [demo::db]@Contract_Firm,
      // store qualifier omitted after the first hop -- also legal
      employees[fir1, per1]: [demo::db]@Contract_Firm > @Person_Contract
    )
  }
```

Gotcha: hops are chained with `>` and must be **contiguous** (each join's target table is the
next join's source). The store qualifier is required on the **first** join and optional after;
dropping it from the first hop parses but fails to compile with
``Can't resolve from 'null' path`` (verified).

Parse: ok. Compile: ok.

---

## 7. Association across a MANY-to-MANY bridge table

Source: `core_relational/relational/tests/mapping/join/advancedRelationalSetUp.pure:278,285`
(`chainedJoins`: `firm : [db]@Person_FirmPersonBridge > @Firm_FirmPersonBridge` /
`employees : [db]@Firm_FirmPersonBridge > @Person_FirmPersonBridge`), and lines 301/307 for the
`(INNER)` variant. Lifted here from the class mappings into an `AssociationMapping` block.

```pure
###Pure
Association demo::FirmPerson
{
  employees: demo::Person[*];
  firms: demo::Firm[*];
}

###Mapping
  demo::FirmPerson: Relational
  {
    AssociationMapping
    (
      firms[per1, fir1]: [demo::db]@Person_FirmPersonBridge > @Firm_FirmPersonBridge,
      employees[fir1, per1]: [demo::db]@Firm_FirmPersonBridge > (INNER) @Person_FirmPersonBridge
    )
  }
```

Gotcha: both ends are `[*]` and both are two-hop chains through the same link table, mirrored;
the link table itself is never a mapped class. A join's inner/outer kind is written **between**
the `>` and the next `@Join` as `> (INNER)` / `> (OUTER)`, and applies to the hop that follows.

Parse: ok. Compile: ok.

---

## 8. Association with DIFFERENT set ids on each end

Source: `testAssociationEmbedded.pure:165-168` (same property `organizations` mapped from two
different source sets) and `testAssociationMapping.pure:199-227` (`associationMappingWithDifferentRoot`,
`ceo[rf, o]` where `o` is a second `Person` set).

```pure
###Mapping
Mapping demo::AssociationDifferentSetIds
(
  // two set implementations for one class => exactly one must be marked root with '*'
  *demo::Person[per_main]: Relational
  {
    firstName: [demo::db]personTable.FIRSTNAME
  }

  demo::Person[per_archive]: Relational
  {
    firstName: [demo::db]archivedPersonTable.FIRSTNAME
  }

  *demo::Firm[fir1]: Relational
  {
    legalName: [demo::db]firmTable.LEGALNAME
  }

  demo::Employment: Relational
  {
    AssociationMapping
    (
      firm[per_main, fir1]: [demo::db]@Firm_Person,
      employees[fir1, per_archive]: [demo::db]@Firm_ArchivedPerson
    )
  }
)
```

Gotcha: as soon as a class has two set implementations, one must be marked root with `*` or
compilation fails with ``Class 'demo::Person' is mapped by 2 set implementations and has 0
roots`` (hit and fixed during verification). The two ends then need not be mirror images —
here `Person -> Firm` starts at `per_main` while `Firm -> employees` lands on `per_archive`,
via a different join.

Parse: ok. Compile: ok.

---

## 9. XStore association mapping

Source: `legend-engine-language-pure-grammar/src/test/java/.../roundtrip/TestMappingGrammarRoundtrip.java:820`
(`testCrossStoreAssociationMapping`).

```pure
###Mapping
Mapping demo::CrossStoreMapping
(
  demo::Person[p]: Pure
  {
    ~src demo::Person
    +firmId: Integer[1]: 1,
    name: $src.name
  }

  demo::Firm[f]: Pure
  {
    ~src demo::Firm
    id: $src.id,
    legalName: $src.legalName
  }

  demo::Firm_Person: XStore
  {
    employer[p, f]: $this.firmId == $that.id,
    employees[f, p]: $this.id == $that.firmId
  }
)
```

Gotcha: `XStore { }` takes **no** `AssociationMapping ( ... )` wrapper (unlike `Relational`),
each end is `prop[sourceSetId, targetSetId]: <boolean expression>` with the ids optional
(verified: dropping both still compiles), the predicate uses `$this`/`$that` — **`~target` does
not exist** — and `~src <Class>` is a member of the `Pure` class mappings, not of the XStore
block. `+firmId: Integer[1]: 1` is a *local mapping property*: it is not declared on
`demo::Person` and exists only to give the predicate a join key.

Parse: ok. Compile: ok.

---

## 10. Association mapping in a mapping that INCLUDES another

Source: `testAssociationMapping.pure:160-163` (`associationMappingWithIncludes`), rendered in the
Legend text grammar's `include mapping <path>` form
(`TestMappingGrammarRoundtrip.java:51`; the store-substitution variant is
`include mapping test::includedRelationalMapping[dbInc->db]`).

```pure
###Mapping
Mapping demo::BaseMapping
(
  demo::Person[per1]: Relational
  {
    firstName: [demo::db]personTable.FIRSTNAME
  }

  demo::Firm[fir1]: Relational
  {
    legalName: [demo::db]firmTable.LEGALNAME
  }
)

Mapping demo::AssociationWithIncludes
(
  include mapping demo::BaseMapping

  demo::Employment: Relational
  {
    AssociationMapping
    (
      employees[fir1, per1]: [demo::db]@Firm_Person,
      firm[per1, fir1]: [demo::db]@Firm_Person
    )
  }
)
```

Gotcha: the keyword is `include mapping <path>` (the bare `include <path>` form appears only in
legacy `core_relational` `.pure` sources, not in the Legend text grammar), it must come first in
the mapping body, and `per1`/`fir1` are resolved out of the included mapping — the including
mapping maps no classes at all.

Parse: ok. Compile: ok.

---

## 11. Bonus: association end whose SOURCE is an EMBEDDED set

Source: `testAssociationEmbedded.pure:163-168`:
`location[f1_address,loc]: [myDB]@Firm_Address_location > @Address_location`.

```pure
###Mapping
Mapping demo::AssociationOnEmbeddedSet
(
  demo::Firm[f1]: Relational
  {
    scope([demo::myDB]PERSON_FIRM_DENORM)
    (
      legalName: FIRM_LEGALNAME,
      address
      (
        name: FIRM_ADDRESS_NAME
      )
    )
  }

  demo::Location[loc]: Relational
  {
    place: [demo::myDB]LOCATIONS.PLACE
  }

  demo::AddressLocation: Relational
  {
    AssociationMapping
    (
      location[f1_address, loc]: [demo::myDB]@Firm_Address_location > @Address_location
    )
  }
)
```

Gotcha: the embedded set has an **auto-generated id** you must spell by hand —
`<parentSetId>_<propertyName>` when the parent class mapping has an explicit id (`f1_address`),
or `<class path with :: replaced by _>_<propertyName>` when it does not (upstream:
`meta_relational_tests_model_simple_Person_firm`). Mapping only **one** end of the association
is legal.

---

## Negative results worth keeping

| Attempt | Verdict |
|---------|---------|
| `Assoc: Relational { firm[per1, fir1]: [db]@J }` (no `AssociationMapping` wrapper) | parses, **does not compile**: `Can't find class 'demo::Employment'` |
| Join chain with no store on the first hop: `firm[per1, fir1]: @J1 > @J2` | parses, **does not compile**: `Can't resolve from 'null' path` |
| Two set implementations for one class, neither marked `*` | parses, **does not compile**: `Class 'demo::Person' is mapped by 2 set implementations and has 0 roots` |
| `Otherwise([firm1]:[demo::db]@PersonFirmJoin)` (no spaces) | parses and compiles — whitespace is free |
| XStore ends with the `[src, tgt]` ids dropped | parses and compiles |
