# Verified Relational Store Syntax

Every snippet below was extracted from a `.pure` file in `scripts/corpus/verified/store/`
and run through:

```
J=$HOME/jdk/jdk-21.0.11+10/Contents/Home
R=/Users/neemsandv/legend/legend-lite/tools/engine-runner
$J/bin/java -cp "$R/target/classes:$(cat $R/cp.txt)" perf.ParseMain <file> --compile
```

Result for all 8 files: `ok ... [compiles]`.

Syntax was copied from legend-engine sources, principally:

- `legend-engine-xts-relationalStore/.../legend-engine-xt-relationalStore-grammar/src/main/antlr4/.../RelationalParserGrammar.g4` (clause ordering)
- `.../legend-engine-xt-relationalStore-grammar/src/test/java/.../TestRelationalGrammarRoundtrip.java` (column types, milestoning, views, operations)
- `.../legend-engine-xt-relationalStore-grammar/src/main/java/.../RelationalParseTreeWalker.java` (type parameter arity)
- `core_relational/relational/tests/relationalSetUp.pure` (views with `~groupBy` / join columns)
- `core_relational/relational/tests/mapping/multigrain/testMultiGrainTableMappings.pure` (MultiGrainFilter)
- `core_relational/relational/router/tests/testRoutingWithInclude.pure` (cross-database join via `include`)
- `legend-engine-xt-relationalStore-emit/src/test/resources/relational-emit-models/` (bitemporal, dyna-function views, inline views, filters)

| File | Features |
|---|---|
| `store/column-types.pure` | 2, 3 |
| `store/schema-view.pure` | 1 |
| `store/joins.pure` | 4, 5, 6, 7, 8 |
| `store/cross-db.pure` | 9 |
| `store/views.pure` | 10 |
| `store/tabular-function.pure` | 11 |
| `store/filters.pure` | 12 |
| `store/milestoning.pure` | 13 |

---

## 1. `Schema` containing Tables and Views (schema-qualified references from a mapping)

**File:** `store/schema-view.pure` — **parsed and compiled**

```pure
###Relational
Database store::schema::SchemaDB
(
  Schema trading
  (
    Table TradeTable
    (
      ID INTEGER PRIMARY KEY,
      PRODUCT VARCHAR(200),
      QTY INTEGER,
      PRICE DECIMAL(18, 4),
      TRADER_ID INTEGER,
      STATUS VARCHAR(10)
    )

    View TradeView
    (
      ~filter ActiveTradesFilter
      tradeId: trading.TradeTable.ID PRIMARY KEY,
      product: trading.TradeTable.PRODUCT,
      totalValue: multiply(trading.TradeTable.QTY, trading.TradeTable.PRICE)
    )
  )

  Schema reference
  (
    Table TraderTable
    (
      ID INTEGER PRIMARY KEY,
      NAME VARCHAR(200)
    )
  )

  Join Trade_Trader(trading.TradeTable.TRADER_ID = reference.TraderTable.ID)

  Filter ActiveTradesFilter(trading.TradeTable.STATUS = 'ACTIVE')
)

###Mapping
Mapping store::schema::SchemaMapping
(
  *store::schema::Trade: Relational
  {
    ~mainTable [store::schema::SchemaDB]trading.TradeTable
    product: [store::schema::SchemaDB]trading.TradeTable.PRODUCT,
    traderName: [store::schema::SchemaDB]@Trade_Trader | reference.TraderTable.NAME
  }

  *store::schema::TradeSummary: Relational
  {
    ~mainTable [store::schema::SchemaDB]trading.TradeView
    product: [store::schema::SchemaDB]trading.TradeView.product,
    totalValue: [store::schema::SchemaDB]trading.TradeView.totalValue
  }
)
```

**Gotcha:** a `Schema` may contain only `Table`, `View`, `TabularFunction` — no `Join` and no `Filter`;
those live at database level and must reference schema members fully qualified (`trading.TradeTable.ID`),
including from *inside* a view declared in that same schema.

---

## 2. Composite PRIMARY KEY (multi-column)

**File:** `store/column-types.pure` — **parsed and compiled**

```pure
###Relational
Database store::types::TypesDB
(
  Table CompositeKey
  (
    REGION VARCHAR(20) PRIMARY KEY,
    BOOK_ID INTEGER PRIMARY KEY,
    AS_OF DATE PRIMARY KEY,
    VALUE DECIMAL(18, 4) NOT NULL
  )
)

###Mapping
Mapping store::types::TypesMapping
(
  *store::types::Position: Relational
  {
    ~primaryKey
    (
      [store::types::TypesDB]CompositeKey.REGION,
      [store::types::TypesDB]CompositeKey.BOOK_ID,
      [store::types::TypesDB]CompositeKey.AS_OF
    )
    ~mainTable [store::types::TypesDB]CompositeKey
    region: [store::types::TypesDB]CompositeKey.REGION,
    bookId: [store::types::TypesDB]CompositeKey.BOOK_ID,
    value: [store::types::TypesDB]CompositeKey.VALUE
  }
)
```

**Gotcha:** there is no table-level `PRIMARY KEY (A, B)` clause — composite keys are expressed by
repeating the per-column `PRIMARY KEY` modifier. `PRIMARY KEY (A, B)` is rejected at parse time
("Unexpected token 'PRIMARY KEY'"). Also, `PRIMARY KEY` and `NOT NULL` are mutually exclusive on one
column (`ID INTEGER PRIMARY KEY NOT NULL` → "Unexpected token").

---

## 3. Columns of every supported type, including NOT NULL

**File:** `store/column-types.pure` — **parsed and compiled**

```pure
###Relational
Database store::types::TypesDB
(
  Table AllTypes
  (
    C_VARCHAR VARCHAR(200) PRIMARY KEY,
    C_CHAR CHAR(32) PRIMARY KEY,
    C_INTEGER INTEGER,
    C_INT INT,
    C_BIGINT BIGINT NOT NULL,
    C_SMALLINT SMALLINT,
    C_TINYINT TINYINT,
    C_FLOAT FLOAT,
    C_DOUBLE DOUBLE,
    C_REAL REAL,
    C_DECIMAL DECIMAL(32, 23),
    C_NUMERIC NUMERIC(32, 23),
    C_DATE DATE,
    C_TIMESTAMP TIMESTAMP,
    C_BIT BIT,
    C_BINARY BINARY(1),
    C_VARBINARY VARBINARY(1) NOT NULL,
    C_SEMISTRUCTURED SEMISTRUCTURED,
    C_JSON JSON,
    C_ARRAY ARRAY,
    C_OTHER OTHER
  )
)
```

**Gotcha:** parameter arity is enforced at parse time, not compile time —
`VARCHAR`/`CHAR`/`BINARY`/`VARBINARY` require exactly 1, `DECIMAL`/`NUMERIC` exactly 2, and every other
type (including `SEMISTRUCTURED`, `JSON`, `BIT`, `DATE`, `TIMESTAMP`) rejects any parameter at all
(`DECIMAL(10)` → "Column data type DECIMAL requires 2 parameters (precision, scale)";
`SEMISTRUCTURED(100)` → "does not expect any parameters"). `INT` normalises to `INTEGER` and `ARRAY`
normalises to `OTHER` on round-trip.

---

## 4. Join on MULTIPLE columns (`and`)

**File:** `store/joins.pure` — **parsed and compiled**

```pure
Join Person_Firm_Multi(PersonTable.FIRM_ID = FirmTable.ID and PersonTable.LASTNAME = FirmTable.REGION)
```

**Gotcha:** `and` chains flat with no parentheses needed; every conjunct must still resolve to columns of
the same two tables — a join whose operands only ever touch one table fails compilation with
"The system can only find one table in the join. Please use the '{target}' notation".

---

## 5. Join with NON-EQUALITY (`>`, `<`, `>=`, `<=`, `<>`, `!=`, `is null`, `is not null`)

**File:** `store/joins.pure` — **parsed and compiled**

```pure
Join Person_Firm_Range(PersonTable.RANK > FirmTable.MIN_RANK and PersonTable.RANK < FirmTable.MAX_RANK)
Join Person_Firm_GtEq(PersonTable.RANK >= FirmTable.MIN_RANK and PersonTable.LASTNAME <> FirmTable.LEGALNAME)
Join Person_Firm_LtEq(PersonTable.RANK <= FirmTable.MAX_RANK and PersonTable.LASTNAME != FirmTable.LEGALNAME)
Join Person_Firm_Nulls(PersonTable.FIRM_ID = FirmTable.ID and PersonTable.END_DATE is null and PersonTable.START_DATE is not null)
```

**Gotcha:** `is null` / `is not null` are *postfix* self-operators (grammar rule `atomicSelfOperator`), so
they take no right operand; they cannot stand alone as the whole join condition because that leaves only
one table visible — pair them with an equi-join conjunct. Both `<>` and `!=` parse (they map to
`notEqualAnsi` and `notEqual` respectively).

---

## 6. Join with `or`

**File:** `store/joins.pure` — **parsed and compiled**

```pure
Join Person_Firm_Or(PersonTable.FIRM_ID = FirmTable.ID or PersonTable.LASTNAME = FirmTable.LEGALNAME)
Join Person_Firm_AndOr(PersonTable.FIRM_ID = FirmTable.ID and (PersonTable.RANK > FirmTable.MIN_RANK or PersonTable.RANK < FirmTable.MAX_RANK))
```

**Gotcha:** `and` and `or` have *equal* precedence in this grammar (`booleanOperationRight: booleanOperator operation`,
right-associative), so a mixed `A and B or C` binds as `A and (B or C)` — the composer even re-emits it
with the parentheses added. Always parenthesise the `or` group when you mean it.

---

## 7. Join using a DYNAFUNCTION on one or both sides

**File:** `store/joins.pure` — **parsed and compiled**

```pure
Join Person_Firm_Concat(concat(PersonTable.PREFIX, PersonTable.CODE) = FirmTable.KEY)
Join Person_Firm_UpperLower(upper(PersonTable.LASTNAME) = upper(FirmTable.LEGALNAME))
Join Person_Firm_Lower(lower(PersonTable.LASTNAME) = lower(FirmTable.LEGALNAME))
Join Person_Firm_Substring(substring(PersonTable.CODE, 1, 3) = substring(FirmTable.KEY, 1, 3))
```

**Gotcha:** dynafunctions are just `identifier(args...)` in the grammar — the parser does not check the
name or arity, only the compiler resolves it against the registered DynaFunction set; literals (`1`, `3`,
`'P-'`) are legal arguments alongside column references.

---

## 8. Self-join using `{target}`

**File:** `store/joins.pure` — **parsed and compiled**

```pure
Join Org_Parent(OrgTable.PARENT_ID = {target}.ID)
Join Org_Parent_Multi(OrgTable.PARENT_ID = {target}.ID and OrgTable.REGION = {target}.REGION)
```

Referenced from a mapping, including chained onto itself:

```pure
parentName: [store::joins::JoinsDB]@Org_Parent | OrgTable.NAME,
grandParentName: [store::joins::JoinsDB]@Org_Parent > @Org_Parent | OrgTable.NAME
```

**Gotcha:** `{target}` carries no table name — the "other" side is inferred to be the same table as the
non-`{target}` operand, and the `{target}` side is what the join *lands on*. A join chain (`@A > @B`)
uses `>` as the separator and a single `|` before the final column.

---

## 9. Join across two DIFFERENT Databases, and `include otherDb`

**File:** `store/cross-db.pure` — **parsed and compiled**

```pure
###Relational
Database store::crossdb::PersonDB
(
  Table personTable (ID INTEGER PRIMARY KEY, FIRSTNAME VARCHAR(100), LASTNAME VARCHAR(100), FIRMID INTEGER)
)

###Relational
Database store::crossdb::FirmDB
(
  Table firmTable (ID INTEGER PRIMARY KEY, LEGALNAME VARCHAR(100))
)

// (a) canonical form: a third database includes both, then joins over the merged namespace
###Relational
Database store::crossdb::MainDB
(
  include store::crossdb::PersonDB
  include store::crossdb::FirmDB

  Join Firm_Person([store::crossdb::MainDB]personTable.FIRMID = [store::crossdb::MainDB]firmTable.ID)
)

// (b) same join, pointers aimed at the included databases directly
###Relational
Database store::crossdb::DirectDB
(
  include store::crossdb::PersonDB
  include store::crossdb::FirmDB

  Join Firm_Person_Direct([store::crossdb::PersonDB]personTable.FIRMID = [store::crossdb::FirmDB]firmTable.ID)
)

// (c) no include at all -- also compiles
###Relational
Database store::crossdb::NoIncludeDB
(
  Join Firm_Person_NoInclude([store::crossdb::PersonDB]personTable.FIRMID = [store::crossdb::FirmDB]firmTable.ID)
)

###Mapping
Mapping store::crossdb::CrossMapping
(
  *store::crossdb::Person: Relational
  {
    ~mainTable [store::crossdb::MainDB]personTable
    firstName: [store::crossdb::MainDB]personTable.FIRSTNAME,
    firmName: [store::crossdb::MainDB]@Firm_Person | firmTable.LEGALNAME
  }
)
```

**Gotcha (surprise):** `include` is *not* required to declare a cross-database join — form (c) compiles
with no includes at all, because a `[db]` pointer resolves globally. `include` is what lets you write the
table *unqualified* (`personTable` instead of `[store::crossdb::PersonDB]personTable`) and what makes the
included tables addressable from a mapping's `~mainTable`. Note `include` clauses must come first inside
the `Database` body (grammar puts `(include | includeStore)*` before all element rules).

---

## 10. `View` with `~filter`, `~groupBy`, `~distinct`, join-based columns and dynafunction columns

**File:** `store/views.pure` — **parsed and compiled**

```pure
###Relational
Database store::views::ViewsDB
(
  Table orderTable (ID INTEGER PRIMARY KEY, accountID INTEGER, quantity INTEGER, price DECIMAL(18, 4), prodName VARCHAR(100))
  Table orderPnlTable (ORDER_ID INTEGER PRIMARY KEY, pnl DOUBLE, supportContact VARCHAR(100))
  Table accountTable (ID INTEGER PRIMARY KEY, name VARCHAR(200))

  Join OrderPnlTable_Order(orderPnlTable.ORDER_ID = orderTable.ID)
  Join Order_Account(orderTable.accountID = accountTable.ID)

  Filter NonNegativePnlFilter(orderPnlTable.pnl > 0)

  // ~filter + ~distinct + join column + join-chain column
  View orderPnlView
  (
    ~filter NonNegativePnlFilter
    ~distinct
    ORDER_ID: orderPnlTable.ORDER_ID PRIMARY KEY,
    pnl: orderPnlTable.pnl,
    accountId: @OrderPnlTable_Order > @Order_Account | accountTable.ID,
    supportContact: orderPnlTable.supportContact
  )

  // ~groupBy with an aggregate over a join
  View accountOrderPnlView
  (
    ~groupBy (orderTable.accountID)
    accountId: orderTable.accountID PRIMARY KEY,
    orderPnl: sum(@OrderPnlTable_Order | orderPnlTable.pnl)
  )

  // dynafunction columns
  View orderComputedView
  (
    id: orderTable.ID PRIMARY KEY,
    label: concat('ORD-', orderTable.prodName),
    upperName: upper(orderTable.prodName),
    lowerName: lower(orderTable.prodName),
    shortName: substring(orderTable.prodName, 1, 3),
    lineTotal: multiply(orderTable.quantity, orderTable.price)
  )

  // ~filter reached through a join, plus ~groupBy and ~distinct together
  View orderFilteredThroughJoinView
  (
    ~filter [store::views::ViewsDB]@OrderPnlTable_Order | [store::views::ViewsDB]NonNegativePnlFilter
    ~groupBy (orderTable.accountID)
    ~distinct
    accountId: orderTable.accountID PRIMARY KEY,
    qty: sum(orderTable.quantity)
  )
)
```

**Gotcha:** the three view directives are **strictly ordered** — `~filter`, then `~groupBy`, then
`~distinct`, then columns; any other order is a parse error (`~distinct` before `~groupBy` →
"Unexpected token '~groupBy'"). The join form of `~filter` needs a database pointer on *both* sides of
the `|` (`~filter [db]@Join | [db]FilterName`); the plain form needs neither.

---

## 11. `TabularFunction`

**File:** `store/tabular-function.pure` — **parsed and compiled**

```pure
###Relational
Database store::tabfunc::TabFuncDB
(
  TabularFunction personFunction
  (
    ID INTEGER,
    FIRSTNAME VARCHAR(200),
    LASTNAME VARCHAR(200),
    AGE INTEGER
  )

  Schema fnSchema
  (
    TabularFunction firmFunction
    (
      ID INTEGER PRIMARY KEY,
      LEGALNAME VARCHAR(200) NOT NULL
    )
  )
)

###Mapping
Mapping store::tabfunc::TabFuncMapping
(
  *store::tabfunc::PersonFromFn: Relational
  {
    ~primaryKey
    (
      [store::tabfunc::TabFuncDB]personFunction.ID
    )
    ~mainTable [store::tabfunc::TabFuncDB]personFunction
    firstName: [store::tabfunc::TabFuncDB]personFunction.FIRSTNAME,
    lastName: [store::tabfunc::TabFuncDB]personFunction.LASTNAME
  }

  *store::tabfunc::FirmFromFn: Relational
  {
    ~mainTable [store::tabfunc::TabFuncDB]fnSchema.firmFunction
    legalName: [store::tabfunc::TabFuncDB]fnSchema.firmFunction.LEGALNAME
  }
)
```

**Gotcha (surprise):** despite the name there is **no argument list** in the grammar
(`tabularFunction: TABULAR_FUNC relationalIdentifier PAREN_OPEN (columnDefinition ...)? PAREN_CLOSE`) —
the parenthesised list is the *result* column schema, using the identical `columnDefinition` rule as
`Table` (so `PRIMARY KEY` / `NOT NULL` are accepted). It behaves as a table everywhere in a mapping,
including as `~mainTable`, and can be schema-qualified. If no column carries `PRIMARY KEY` the mapping
needs an explicit `~primaryKey (...)`.

---

## 12. `Filter` and `MultiGrainFilter`, and how a mapping references each

**File:** `store/filters.pure` — **parsed and compiled**

```pure
###Relational
Database store::filters::FilterDB
(
  Table PERSON_FIRM_DENORM (OID INTEGER PRIMARY KEY, DLEVEL VARCHAR(2), PERSON_FIRSTNAME VARCHAR(200), PERSON_FIRM_OID INTEGER, FIRM_LEGALNAME VARCHAR(200), STATUS VARCHAR(10))
  Table EMPLOYEE (ID INTEGER PRIMARY KEY, NAME VARCHAR(200), DEPT_ID INTEGER, ACTIVE VARCHAR(1))
  Table DEPARTMENT (ID INTEGER PRIMARY KEY, CITY VARCHAR(50))

  Join Emp_Dept(EMPLOYEE.DEPT_ID = DEPARTMENT.ID)

  Filter ActiveFilter(EMPLOYEE.ACTIVE = 'Y')
  Filter NycDeptFilter(DEPARTMENT.CITY = 'NYC')

  MultiGrainFilter personGrain(PERSON_FIRM_DENORM.DLEVEL = 'P')
  MultiGrainFilter firmGrain(PERSON_FIRM_DENORM.DLEVEL = 'F')
)

###Mapping
Mapping store::filters::FilterMapping
(
  // plain Filter
  *store::filters::Employee: Relational
  {
    ~filter [store::filters::FilterDB]ActiveFilter
    ~mainTable [store::filters::FilterDB]EMPLOYEE
    name: [store::filters::FilterDB]EMPLOYEE.NAME
  }

  // Filter reached through a join, with an explicit join type
  *store::filters::NycEmployee: Relational
  {
    ~filter [store::filters::FilterDB] (INNER) @Emp_Dept | [store::filters::FilterDB]NycDeptFilter
    ~mainTable [store::filters::FilterDB]EMPLOYEE
    name: [store::filters::FilterDB]EMPLOYEE.NAME
  }

  // a MultiGrainFilter is referenced with EXACTLY the same ~filter syntax
  *store::filters::Person: Relational
  {
    ~filter [store::filters::FilterDB]personGrain
    ~mainTable [store::filters::FilterDB]PERSON_FIRM_DENORM
    firstName: [store::filters::FilterDB]PERSON_FIRM_DENORM.PERSON_FIRSTNAME
  }

  *store::filters::Firm: Relational
  {
    ~filter [store::filters::FilterDB]firmGrain
    ~mainTable [store::filters::FilterDB]PERSON_FIRM_DENORM
    legalName: [store::filters::FilterDB]PERSON_FIRM_DENORM.FIRM_LEGALNAME
  }
)
```

**Gotcha:** there is **no separate mapping keyword for a MultiGrainFilter** — a mapping references both
kinds with `~filter [db]Name`, and the only difference is which keyword declared it (`MultiGrainFilter`
vs `Filter`). The distinction is consumed later, by the planner: `pureToSQLQuery.pure` special-cases
`instanceOf(MultiGrainFilter)` when deciding whether a join to a primary key can be elided. Inside a
class mapping the clause order is fixed: `~filter`, `~distinct`, `~groupBy`, `~primaryKey`, `~mainTable`,
then property mappings.

---

## 13. Milestoning: business FROM/THRU, business SNAPSHOT, processing IN/OUT, processing SNAPSHOT, bitemporal

**File:** `store/milestoning.pure` — **parsed and compiled**

```pure
###Relational
Database store::milestoning::MilestonedDB
(
  // business FROM/THRU -- minimal
  Table ProductBusFromThru
  (
    milestoning
    (
      business(BUS_FROM = FROM_Z, BUS_THRU = THRU_Z)
    )
    ID INTEGER PRIMARY KEY,
    NAME VARCHAR(100) NOT NULL,
    FROM_Z DATE PRIMARY KEY,
    THRU_Z DATE NOT NULL
  )

  // + INFINITY_DATE
  Table ProductBusFromThruInfinity
  (
    milestoning
    (
      business(BUS_FROM = FROM_Z, BUS_THRU = THRU_Z, INFINITY_DATE = %9999-12-30T19:00:00.0000)
    )
    ID INTEGER PRIMARY KEY, NAME VARCHAR(100), FROM_Z DATE PRIMARY KEY, THRU_Z DATE
  )

  // + THRU_IS_INCLUSIVE (true and false both accepted) + INFINITY_DATE
  Table ProductBusThruInclusiveTrue
  (
    milestoning
    (
      business(BUS_FROM = FROM_Z, BUS_THRU = THRU_Z, THRU_IS_INCLUSIVE = true, INFINITY_DATE = %9999-12-30T19:00:00.0000)
    )
    ID INTEGER PRIMARY KEY, NAME VARCHAR(100), FROM_Z DATE PRIMARY KEY, THRU_Z DATE
  )

  // business SNAPSHOT
  Table ProductBusSnapshot
  (
    milestoning
    (
      business(BUS_SNAPSHOT_DATE = BUSINESS_DATE)
    )
    ID INTEGER PRIMARY KEY, NAME VARCHAR(100), BUSINESS_DATE DATE PRIMARY KEY
  )

  // processing IN/OUT -- minimal
  Table ProductProcInOut
  (
    milestoning
    (
      processing(PROCESSING_IN = IN_Z, PROCESSING_OUT = OUT_Z)
    )
    ID INTEGER PRIMARY KEY, NAME VARCHAR(100), IN_Z DATE PRIMARY KEY, OUT_Z DATE NOT NULL
  )

  // + OUT_IS_INCLUSIVE + INFINITY_DATE
  Table ProductProcOutInclusiveFalse
  (
    milestoning
    (
      processing(PROCESSING_IN = IN_Z, PROCESSING_OUT = OUT_Z, OUT_IS_INCLUSIVE = false, INFINITY_DATE = %9999-12-30T19:00:00.0000)
    )
    ID INTEGER PRIMARY KEY, NAME VARCHAR(100), IN_Z DATE PRIMARY KEY, OUT_Z DATE
  )

  // processing SNAPSHOT
  Table ProductProcSnapshot
  (
    milestoning
    (
      processing(PROCESSING_SNAPSHOT_DATE = PROCESSING_DATE)
    )
    ID INTEGER PRIMARY KEY, NAME VARCHAR(100), PROCESSING_DATE DATE PRIMARY KEY
  )

  // bitemporal -- business AND processing on one table, comma-separated
  Table ProductBiTemporal
  (
    milestoning
    (
      business(BUS_FROM = FROM_Z, BUS_THRU = THRU_Z),
      processing(PROCESSING_IN = IN_Z, PROCESSING_OUT = OUT_Z)
    )
    ID INTEGER PRIMARY KEY,
    NAME VARCHAR(100) NOT NULL,
    FROM_Z DATE PRIMARY KEY,
    THRU_Z DATE NOT NULL,
    IN_Z DATE PRIMARY KEY,
    OUT_Z DATE NOT NULL
  )

  // bitemporal, both sides fully specified
  Table ProductBiTemporalFull
  (
    milestoning
    (
      business(BUS_FROM = FROM_Z, BUS_THRU = THRU_Z, THRU_IS_INCLUSIVE = true, INFINITY_DATE = %9999-12-30T19:00:00.0000),
      processing(PROCESSING_IN = IN_Z, PROCESSING_OUT = OUT_Z, OUT_IS_INCLUSIVE = false, INFINITY_DATE = %9999-12-30T19:00:00.0000)
    )
    ID INTEGER PRIMARY KEY, NAME VARCHAR(100),
    FROM_Z DATE PRIMARY KEY, THRU_Z DATE, IN_Z DATE PRIMARY KEY, OUT_Z DATE
  )
)

###Pure
Class <<temporal.businesstemporal>> store::milestoning::BusProduct { name: String[1]; }
Class <<temporal.processingtemporal>> store::milestoning::ProcProduct { name: String[1]; }
Class <<temporal.bitemporal>> store::milestoning::BiProduct { name: String[1]; }

###Mapping
Mapping store::milestoning::MilestonedMapping
(
  *store::milestoning::BusProduct: Relational
  {
    ~mainTable [store::milestoning::MilestonedDB]ProductBusFromThru
    name: [store::milestoning::MilestonedDB]ProductBusFromThru.NAME
  }
  *store::milestoning::BiProduct: Relational
  {
    ~mainTable [store::milestoning::MilestonedDB]ProductBiTemporal
    name: [store::milestoning::MilestonedDB]ProductBiTemporal.NAME
  }
)
```

**Gotcha:** the `milestoning ( ... )` block must be the **first** thing inside the table body, before any
column — putting it after the columns is a parse error. Argument order inside each `business(...)` /
`processing(...)` is also fixed: `FROM` then `THRU` (resp. `IN` then `OUT`), then the optional
`THRU_IS_INCLUSIVE`/`OUT_IS_INCLUSIVE`, then the optional `INFINITY_DATE`. The referenced columns must
exist on the same table (a bad name gives a *compile* error, "Milestone column 'X' not found on table
definition", while the file still parses). `INFINITY_DATE` accepts either a DateTime literal
(`%9999-12-30T19:00:00.0000`) or a StrictDate literal (`%9999-12-31`). Bitemporal is just the two
entries comma-separated in one block. An empty `milestoning ( )` block is legal. Snapshot-milestoned
tables map to the same `<<temporal.businesstemporal>>` / `<<temporal.processingtemporal>>` stereotypes as
the from/thru and in/out forms.

---

## Nothing failed to verify

All 13 requested features parse **and** compile. The only constructs that would not compile were the
deliberate negative probes used to establish the gotchas above:

| Probe | Result |
|---|---|
| `milestoning ( ... )` placed after the columns | parse error: "Unexpected token" |
| table-level `PRIMARY KEY (A, B)` | parse error: "Unexpected token 'PRIMARY KEY'" |
| `ID INTEGER PRIMARY KEY NOT NULL` on one column | parse error: "Unexpected token" |
| `~distinct` before `~filter` / before `~groupBy` in a `View` | parse error: "Unexpected token" |
| `SEMISTRUCTURED(100)` | parse error: "does not expect any parameters" |
| `DECIMAL(10)` | parse error: "requires 2 parameters (precision, scale)" |
| `VARCHAR` with no size | parse error: "requires 1 parameter (size)" |
| `Join J(T.V is not null)` — one table only | parses, fails compile: "The system can only find one table in the join. Please use the '{target}' notation in order to define a directed self join." |
| `business(BUS_FROM = NOPE_FROM, ...)` naming a missing column | parses, fails compile: "Milestone column 'NOPE_FROM' not found on table definition" |
