# Verified Relational Property-Mapping Syntax

Every snippet below was extracted from legend-engine's own sources (grammar, round-trip
tests, `core_relational` `.pure` test resources) and then **run** through:

```
J=$HOME/jdk/jdk-21.0.11+10/Contents/Home
R=/Users/neemsandv/legend/legend-lite/tools/engine-runner
$J/bin/java -cp "$R/target/classes:$(cat $R/cp.txt)" perf.ParseMain <file> --compile
```

Full standalone files (Class + Database + Mapping) live in
`scripts/corpus/verified/property-mappings/*.pure`. **All 12 print `ok … [compiles]`.**

Authoritative grammar:
`legend-engine-xt-relationalStore-grammar/src/main/antlr4/.../RelationalParserGrammar.g4`
(rules `classMapping`, `propertyMapping`, `propertyMappingWithScope`, `joinOperation`,
`mappingFilter`, `localMappingProperty`, `embeddedPropertyMapping`).

---

## 0. The grammar rules that govern everything

```
classMapping:              mappingFilter? DISTINCT_CMD? mappingGroupBy? mappingPrimaryKey? mappingMainTable?
                           (propertyMapping (COMMA propertyMapping)*)?
mappingFilter:             '~filter' databasePointer (joinSequence '|' databasePointer)? identifier
propertyMapping:           singlePropertyMapping | propertyMappingWithScope
propertyMappingWithScope:  'scope' '(' databasePointer mappingScopeInfo? ')' '(' singlePropertyMapping (',' ...)* ')'
singlePropertyMapping:     ('+' identifier localMappingProperty | identifier sourceAndTargetMappingId?)
                           (relationalPropertyMapping | embeddedPropertyMapping | inlineEmbeddedPropertyMapping)
relationalPropertyMapping: ':' (enumTransformer | bindingTransformer)? operation
joinOperation:             databasePointer? joinSequence ('|' (booleanOperation | tableAliasColumnOperation))?
joinSequence:              ('(' identifier ')')? joinPointer ('>' joinPointerFull)*
```

---

## 1. Join-chain property mapping (2-hop and 3-hop)

File: `property-mappings/01-join-chains.pure` — **compiles**

```pure
*test::Person: Relational
{
  ~mainTable [test::db]sales.PERSON_TBL
  firstName: [test::db]sales.PERSON_TBL.FIRSTNAME,
  firmName:  [test::db]@Person_Firm | sales.FIRM_TBL.LEGAL_NAME,
  firmCity:  [test::db]@Person_Firm > @Firm_Addr | sales.ADDR_TBL.CITY,
  firmCountry: [test::db]@Person_Firm > [test::db]@Firm_Addr > [test::db]@Addr_Country | [test::db]sales.COUNTRY_TBL.NAME
}
```

**Gotchas:** hops are chained with `>`, and the final column comes after a single `|`.
The `[db]` pointer is required on the *first* join and optional on every later hop and on
the trailing column (it defaults to the pointer already in scope). A join sequence with no
`| column` at all (`firm: [db]@Firm_Person`) is the association form and is also valid.
**A join type on the FIRST hop is a compile error** — see §3.

---

## 2. DynaFunction property mappings

File: `property-mappings/02-dynafunctions.pure` — **compiles** (63 distinct dyna functions at top level)

```pure
*test::Employee: Relational
{
  ~mainTable [test::db]EMP_TBL
  fullName:    concat([test::db]EMP_TBL.FIRSTNAME, ' ', [test::db]EMP_TBL.LASTNAME),
  upperName:   toUpper([test::db]EMP_TBL.FIRSTNAME),
  lowerName:   toLower([test::db]EMP_TBL.FIRSTNAME),
  initials:    substring([test::db]EMP_TBL.FIRSTNAME, 1, 2),
  trimmedName: trim([test::db]EMP_TBL.FIRSTNAME),
  nickName:    coalesce([test::db]EMP_TBL.NICKNAME, [test::db]EMP_TBL.FIRSTNAME, 'unknown'),
  totalComp:   plus([test::db]EMP_TBL.BASE, [test::db]EMP_TBL.BONUS),
  netComp:     minus([test::db]EMP_TBL.BASE, [test::db]EMP_TBL.TAX),
  bandLabel:   if(equal([test::db]EMP_TBL.BAND, 1), 'JUNIOR', 'SENIOR'),
  gradeLabel:  case(equal([test::db]EMP_TBL.BAND, 1), 'ONE',
                    equal([test::db]EMP_TBL.BAND, 2), 'TWO',
                    sqlNull()),
  hiredYear:   year([test::db]EMP_TBL.HIRE_DATE),
  compAsString: toString([test::db]EMP_TBL.BASE),
  nameGuid:    generateGuid()
}
```

**Gotchas:**
- `upper` / `lower` **do not exist** — the names are `toUpper` / `toLower`. (The rosetta doc
  `docs/rosetta/property-mappings.md` is wrong on this.) Same for `toString`, `toDecimal`,
  `toFloat`, `toTimestamp`.
- Comparison/boolean operators have both an infix form (`a = b`, `a and b`, `a is null`)
  and a functional form (`equal(a,b)`, `and(a,b)`, `isNull(a)`) — the composer always
  emits the infix form for `Join`/`Filter` and the functional form inside `case`/`if`.
- `sqlNull()` is the null literal; `case(...)` takes `cond, val, cond, val, …, else`.
- **The compiler does NOT validate dyna function names.** `totallyBogusFn([db]T.C)`
  compiles fine. Names are resolved only at SQL-generation time against the active
  `DbExtension`'s `dynaFnToSql` registry, so a wrong name fails at query time, not compile
  time. The registry is the real source of truth — see §12.
- `plus`/`minus`/`times`/`divide` are the arithmetic names (`add`/`sub` also exist but are
  the n-ary/dialect variants).

---

## 3. DynaFunction over a join chain

File: `property-mappings/03-dynafunction-over-join.pure` — **compiles**

```pure
*test::Person: Relational
{
  ~mainTable [test::db]sales.PERSON_TBL

  // function wraps the whole join navigation
  firmNameUpper: toUpper([test::db]@Person_Firm | sales.FIRM_TBL.LEGAL_NAME),

  // mixes a local column with a 2-hop navigation
  firmNameCity: concat([test::db]@Person_Firm | sales.FIRM_TBL.LEGAL_NAME, ' - ',
                       [test::db]@Person_Firm > @Firm_Addr | sales.ADDR_TBL.CITY),

  // function on the RIGHT of the pipe: navigate first, then transform
  countryTrim: [test::db]@Person_Firm > @Firm_Addr | trim([test::db]sales.ADDR_TBL.COUNTRY),

  // join type is legal from the SECOND hop onwards
  firmLabel: if(equal([test::db]@Person_Firm > (OUTER) [test::db]@Firm_Addr | [test::db]sales.ADDR_TBL.COUNTRY, 'US'),
                'DOMESTIC', 'FOREIGN')
}
```

**Gotchas:** both nestings are valid — `f(@J | T.col)` and `@J | f(T.col)` — and they mean
different things (transform after join vs. transform of the joined column; the latter binds
the function to the joined table's alias).
**Parse-only, does NOT compile:** a join type on the first hop of a *property mapping*:

```pure
firmLabel: if(equal([test::db](INNER) @Person_Firm | [test::db]sales.FIRM_TBL.REGION_CODE, 'US'), ...)
// -> "Do not support specifying join type for the first join in the classMapping."
```

This is upstream behaviour, not a legend-lite gap:
`HelperRelationalBuilder.java:1578` throws exactly that message, and
`TestRelationalCompilationFromGrammar.java:2255` asserts it.
Join types are `(INNER)` / `(OUTER)`.

---

## 4. `scope(...)` blocks

File: `property-mappings/04-scope-blocks.pure` — **compiles**

```pure
*test::Person: Relational
{
  ~mainTable [test::db]sales.PERSON_TBL

  // full scope: database pointer + schema.table -> bare column names inside
  scope([test::db]sales.PERSON_TBL)
  (
    firstName: FIRSTNAME,
    lastName: LASTNAME,
    category: if(equal(substring(STATE_CODE, 1, 2), 'US'), 'DOMESTIC', sqlNull()),
    firmName: @Person_Firm | FIRM_TBL.LEGAL_NAME
  ),

  // db-pointer-only scope: schema/table still written out inside
  scope([test::db])
  (
    city: ADDR_TBL.CITY,
    country: @Person_Addr | ADDR_TBL.COUNTRY
  )
}
```

Upstream also uses the table-without-schema form with a space:
`scope([myDB] dataTable) ( concatResult : concat(string1, string2), … )`
(`core_relational/.../mapping/sqlFunction/testSqlFunctionsInMapping.pure:765`).

**Gotchas:**
- A scope block is a *sibling* of ordinary property mappings — it is separated from them by
  a comma, and the mappings **inside** it are comma-separated too. Two parenthesised groups
  back to back: `scope(<target>)` then `( … )`.
- The scope's schema applies to **every** bare table reference inside, including the target
  of a join navigation. With `scope([db]sales.PERSON_TBL)`, writing `@J | FIRM_TBL.LEGAL_NAME`
  fails with *"Can't find table 'FIRM_TBL' in schema 'sales'"* unless `FIRM_TBL` is also in
  `sales`.
- Scope is pure sugar: the composer flattens it back to fully-qualified property mappings on
  round-trip (`TestRelationalGrammarRoundtrip#testRelationalMappingScope`).

---

## 5. Class directives — required order (and how a View differs)

File: `property-mappings/05-class-directives.pure` — **compiles**

**Class mapping order** (`classMapping` rule) — all optional, but this sequence is fixed:

```
~filter  →  ~distinct  →  ~groupBy  →  ~primaryKey  →  ~mainTable  →  property mappings
```

```pure
*test::Person: Relational
{
  ~filter [test::db]ActiveFilter
  ~distinct
  ~primaryKey
  (
    [test::db]sales.PERSON_TBL.ID
  )
  ~mainTable [test::db]sales.PERSON_TBL
  firstName: [test::db]sales.PERSON_TBL.FIRSTNAME,
  lastName: [test::db]sales.PERSON_TBL.LASTNAME
}

*test::FirmSummary: Relational
{
  ~groupBy
  (
    [test::db]sales.PERSON_TBL.FIRM_ID
  )
  ~primaryKey
  (
    [test::db]sales.PERSON_TBL.FIRM_ID
  )
  ~mainTable [test::db]sales.PERSON_TBL
  firmId: [test::db]sales.PERSON_TBL.FIRM_ID,
  headCount: count([test::db]sales.PERSON_TBL.ID)
}
```

**View order** (`view` rule) — **`~groupBy` and `~distinct` are swapped**:

```
~filter  →  ~groupBy  →  ~distinct  →  column mappings
```

```pure
View CITY_VIEW
(
  ~filter ActiveFilter
  ~groupBy (sales.PERSON_TBL.CITY)
  ~distinct
  city: sales.PERSON_TBL.CITY PRIMARY KEY,
  personCount: count(sales.PERSON_TBL.ID)
)
```

**Gotchas:**
- Order violations are **PARSER** errors, not compilation errors. Verified: `~mainTable`
  before `~filter` in a class mapping → `Unexpected token '~filter'`; `~distinct` before
  `~groupBy` in a View → `Unexpected token '~groupBy'`.
- Directives are **not** comma-separated, and there is no comma between the last directive
  and the first property mapping.
- `~groupBy` / `~primaryKey` take parentheses and a comma-separated operation list;
  `~distinct` takes no argument; `~mainTable` and `~filter` take no parentheses.
- Inside a View a column may carry a trailing `PRIMARY KEY`; a View has no `~primaryKey`
  directive and no `~mainTable`. Views are then mapped by a class mapping exactly like a
  table (`~mainTable [db]sales.CITY_VIEW`).

---

## 6. `~filter` via a join

File: `property-mappings/06-filter-via-join.pure` — **compiles**

```pure
// plain named filter
*test::ActivePerson: Relational
{
  ~filter [test::db]ActiveFilter
  ~mainTable [test::db]sales.PERSON_TBL
  firstName: [test::db]sales.PERSON_TBL.FIRSTNAME
}

// filter reached through a join
*test::USPerson: Relational
{
  ~filter [test::db]@Person_Firm | [test::db]USFirmFilter
  ~mainTable [test::db]sales.PERSON_TBL
  firstName: [test::db]sales.PERSON_TBL.FIRSTNAME
}

// multi-hop, with an explicit join type on the FIRST hop (legal here)
*test::USCityPerson: Relational
{
  ~filter [test::db] (INNER) @Person_Firm > (INNER) @Firm_Addr | [test::db]NycFilter
  ~mainTable [test::db]sales.PERSON_TBL
  firstName: [test::db]sales.PERSON_TBL.FIRSTNAME
}
```

**Gotchas:**
- The **pipe is mandatory**. `~filter [db]@J [db]Filter` (the form claimed by
  `docs/rosetta/class-directives.md`) is a **parser error** — verified.
- The filter name needs its **own** `[db]` pointer after the pipe; the first `[db]` belongs
  to the join sequence.
- Unlike a property mapping, `~filter` **does** accept a join type on the first hop —
  `~filter [dbInc] (INNER) @Firm_Person | [dbInc] FirmXFilter` is used all over
  `core_relational/.../mapping/classMappingFilterWithInnerJoin/testRelationalSetUp.pure`.

---

## 7. `EnumerationMapping` — standalone block and transformer

File: `property-mappings/07-enumeration-mappings.pure` — **compiles**

```pure
Mapping test::M
(
  // default id (= enum path with '::' replaced by '_')
  test::EmployeeType: EnumerationMapping
  {
    CONTRACT: ['FTC', 'CTR'],
    FULL_TIME: ['FTE']
  }

  // explicit id, integer source values
  test::YesNo: EnumerationMapping YesNoMapping
  {
    YES: [1],
    NO: [0]
  }

  // multi-value collapse; a single source value needs no brackets
  test::TradeStatus: EnumerationMapping TradeStatusMapping
  {
    PENDING: ['P', 'PEND', 'PENDING'],
    CONFIRMED: ['C', 'CONF'],
    SETTLED: 'S',
    CANCELLED: ['X', 'CANC', 'CANCEL']
  }

  *test::Employee: Relational
  {
    ~mainTable [test::db]EMP_TBL
    name: [test::db]EMP_TBL.NAME,
    type:   EnumerationMapping test_EmployeeType: [test::db]EMP_TBL.TYPE_CODE,
    active: EnumerationMapping YesNoMapping:      [test::db]EMP_TBL.ACTIVE,
    status: EnumerationMapping TradeStatusMapping:[test::db]EMP_TBL.STATUS_CODE,
    joinedType: EnumerationMapping test_EmployeeType: [test::db]@Emp_Firm | FIRM_TBL.TYPE_CODE
  }
)
```

**Gotchas:**
- The transformer takes the **enumeration-mapping ID**, which the grammar types as a bare
  `identifier` — **not** a qualified path. `EnumerationMapping test::EmployeeType:` is a
  **parser error** (`Unexpected token '::'`), contradicting
  `docs/rosetta/enum-mappings.md`.
- When the standalone block omits an id, the id is the enum's path with `::` → `_`
  (`test::EmployeeType` → `test_EmployeeType`). Confirmed both empirically and in
  `HelperMappingBuilder.getEnumerationMappingId`:
  `return em.id != null ? em.id : em.enumeration.path.replaceAll("::", "_");`
- All source values in one EnumerationMapping must be the same kind — mixing strings and
  integers raises *"Only one type of source value (integer, string or an enum) is allowed"*
  (`MappingValidator.java:157`).
- `EnumerationMapping` blocks sit at mapping level and are **not** comma-separated from
  class mappings.
- The transformer composes with a join navigation and with dyna functions
  (`cusipRegion: EnumerationMapping RegionMapping: case(or(equal(@J | T.region, 'Y'), …), …)`
  — `TestRelationalGrammarRoundtrip#testEnumDynaFunctionWithJoin`).

---

## 8. Local (mapping-only) properties

File: `property-mappings/08-local-properties.pure` — **compiles**

```pure
*test::Person: Relational
{
  ~mainTable [test::db]PERSON_TBL
  firstName: [test::db]PERSON_TBL.FIRSTNAME,

  +lastName: String[1]: [test::db]PERSON_TBL.LASTNAME,
  +age: Integer[0..1]: [test::db]PERSON_TBL.AGE,
  +salary: Float[1]: [test::db]PERSON_TBL.SALARY,
  +aliases: String[*]: [test::db]PERSON_TBL.NICKNAME,
  +exactlySeven: Integer[7]: [test::db]PERSON_TBL.AGE,
  +displayName: String[1]: concat([test::db]PERSON_TBL.FIRSTNAME, ' ', [test::db]PERSON_TBL.LASTNAME),
  +employerName: String[1]: [test::db]@Person_Firm | FIRM_TBL.LEGAL_NAME,
  +employer: test::Firm[1]: [test::db]@Person_Firm
}
```

**Gotchas:** shape is `+name : Type[mult] : operation` — **two** colons. The type is a
`qualifiedName` so a user class works too (verified: `+employer: test::Firm[1]: [db]@J`).
Multiplicity
accepts `[1]`, `[*]`, `[0..1]`, `[7]`, `[1..*]`. The property must **not** exist on the
class — that is the point of the `+`. Right-hand side is a full `operation`, so dyna
functions and join chains are allowed.

---

## 9. Set IDs, root marker, and `extends`

File: `property-mappings/09-set-ids-extends.pure` — **compiles**

```pure
// '*' marks the ROOT set for the class; '[id]' names the set
*test::Person[person_a]: Relational
{
  ~mainTable [test::db]PERSON_A
  firstName: [test::db]PERSON_A.FIRSTNAME,
  lastName: [test::db]PERSON_A.LASTNAME
}

// additional non-root set for the same class
test::Person[person_b]: Relational
{
  ~mainTable [test::db]PERSON_B
  firstName: [test::db]PERSON_B.FIRSTNAME,
  lastName: [test::db]PERSON_B.LASTNAME
}

// inherit another set's property mappings
test::Person[person_b_ext] extends [person_b]: Relational
{
  ~mainTable [test::db]PERSON_B
  firstName: [test::db]PERSON_B.FIRSTNAME
}

*test::Firm[firm_root]: Relational
{
  ~mainTable [test::db]FIRM_TBL
  legalName: [test::db]FIRM_TBL.LEGAL_NAME,
  employees[person_a]: [test::db]@FirmA_Person
}
```

**Gotchas:** `extends [parentId]` goes **between** the id bracket and the `:` —
`Class[id] extends [parentId]: Relational`. Exactly one set per class may carry `*`.
When the id is omitted the implicit id is the class path with `::` → `_`
(`HelperMappingBuilder.getClassMappingId`), which is why `*models::Firm[model_Firm]` and
`*models::Firm` behave the same downstream.

---

## 10. Binding transformer

File: `property-mappings/10-binding-transformer.pure` — **compiles**

```pure
###ExternalFormat
Binding simple::TestBinding
{
  contentType: 'application/json';
  modelIncludes: [
    simple::Firm
  ];
}

###Mapping
Mapping simple::simpleRelationalMappingInc
(
  *simple::Person: Relational
  {
    ~mainTable [simple::dbInc]personTable
    firstName: [simple::dbInc]personTable.FIRSTNAME,
    firm: Binding simple::TestBinding: [simple::dbInc]personTable.FIRM,
    manager: Binding simple::TestBinding: [simple::dbInc]@personSelfJoin | [simple::dbInc]personTable.FIRM_JSON
  }
)
```

**Gotchas:**
- Unlike `EnumerationMapping`, `Binding` takes a **qualifiedName** — `Binding a::b::C:` is
  correct. A space before the second colon (`Binding test::binding : [db]T.col`) is what the
  composer emits and is equally valid.
- Three compilation constraints, all asserted upstream in
  `TestRelationalCompilationFromGrammar`:
  1. the property's type must be **complex** — a `String`/`Integer` property gives
     *"Binding transformer can be used with complex properties only"*;
  2. the property's class must appear in the binding's `modelIncludes` — otherwise
     *"Class: X should be included in modelUnit for binding: Y"*;
  3. the source column should be `SEMISTRUCTURED` or `JSON`.
- Works over a join navigation and over `extractFromSemiStructured(...)`.

---

## 11. Property mapping with explicit source/target ids

File: `property-mappings/11-source-target-ids.pure` — **compiles**

```pure
*test::Person[person_a]: Relational
{
  ~mainTable [test::db]PERSON_A
  firstName: [test::db]PERSON_A.FIRSTNAME,
  firm[firm_root]: [test::db]@Firm_PersonA          // one id = TARGET set
}

test::Firm_Person: Relational
{
  AssociationMapping
  (
    otherFirm[person_a, firm_root]: [test::db]@Firm_PersonA,      // [sourceId, targetId]
    otherEmployees[firm_root, person_a]: [test::db]@Firm_PersonA
  )
}
```

**Gotchas:** `sourceAndTargetMappingId: '[' sourceId (',' targetId)? ']'`. With **one** id
inside a class mapping it is read as the *target* set. Both ids are the normal case in an
`AssociationMapping`. The composer emits no space after the comma
(`prop1[prop2_source,prop1_source]`), but a space parses fine. Do not confuse this bracket
with the class-mapping id bracket — this one sits on the *property* name.

---

## 12 (bonus). Embedded / Inline / Otherwise

File: `property-mappings/12-embedded.pure` — **compiles**. Not in the requested list, but
the corpus has zero of these and they are the strongest "not 1:1" construct there is.

```pure
*test::Person: Relational
{
  ~mainTable [test::db]PERSON_TBL
  firstName: [test::db]PERSON_TBL.FIRSTNAME,

  address
  (
    ~primaryKey
    (
      [test::db]PERSON_TBL.ID
    )
    line1: [test::db]PERSON_TBL.ADDR_LINE1,
    city: [test::db]PERSON_TBL.ADDR_CITY,
    country
    (
      name: [test::db]PERSON_TBL.ADDR_COUNTRY
    )
  ),

  billing
  (
    line1: [test::db]PERSON_TBL.ADDR_LINE1,
    city: [test::db]PERSON_TBL.ADDR_CITY
  ) Otherwise ([addr_set]: [test::db]@Person_Addr),

  employer() Inline[firm_set]
}
```

**Gotchas:** an embedded mapping has **no colon** after the property name — the `(` follows
directly. `~primaryKey` may be the first clause inside an embedded block (and nowhere else
inside one). `Otherwise` comes after the closing paren and its payload is
`[targetSetId]: [db]joinSequence`. `Inline` requires the literal empty parens:
`prop() Inline[setId]`.

---

## Dyna function registry (legend-engine)

There is **no compile-time check** on dyna function names in a property mapping — the
grammar accepts `identifier '(' args ')'` for anything. Validity is decided at SQL
generation, when `DbExtension.dynaFuncDispatch` looks the name up in the `dynaFnToSql`
table for the target dialect.

Source of truth:
- dialect-independent (111 names):
  `core_relational/relational/sqlQueryToString/extensionDefaults.pure`,
  function `getDynaFunctionToSqlDefault`
- H2 additions (74 names, 10 overriding a default):
  `core_relational/relational/sqlQueryToString/dbSpecific/h2/h2Extension2_1_214.pure`
- special-cased in `dbExtension.pure#processDynaFunction`, so valid but absent from the
  tables above: **`case`**, **`not`**, `extractFromSemiStructured`

**Union available against H2 (176 names + `case` + `not`):**

```
abs acos add adjust and ascii asin atan atan2 average averageRank booland boolor case cast
cbrt ceiling char coalesce concat contains convertDate convertDateTime convertTimeZone
convertVarchar128 corr cos cosh cot count covarPopulation covarSample cumulativeDistribution
currentUserId dateDiff datePart dayOfMonth dayOfWeek dayOfWeekNumber dayOfYear decodeBase64
denseRank distinct divide divideRound encodeBase64 endsWith equal exists exp
extractFromSemiStructured first firstDayOfMonth firstDayOfQuarter firstDayOfThisMonth
firstDayOfThisQuarter firstDayOfThisYear firstDayOfWeek firstDayOfYear firstHourOfDay
firstMillisecondOfSecond firstMinuteOfHour firstSecondOfMinute floor formatDate generateGuid
greaterThan greaterThanEqual greatest group hour if in indexOf isAlphaNumeric isDistinct
isEmpty isNotEmpty isNotNull isNull isNumeric jaroWinklerSimilarity joinStrings lag last lead
least left length lessThan lessThanEqual levenshteinDistance log log10 lpad ltrim matches max
md5 median min minus minute mod mode month monthName monthNumber mostRecentDayOfWeek not
notEqual notEqualAnsi now nth ntile nullSafeEqual nullSafeNotEqual objectReferenceIn or
parseDate parseDecimal parseFloat parseInteger parseJson percentile percentRank plus position
pow previousDayOfWeek quarter quarterNumber rank rem repeatString replace reverseString right
round rowNumber rpad rtrim second sha1 sha256 sign sin sinh size splitPart sqlFalse sqlNull
sqlTrue sqrt startsWith stdDevPopulation stdDevSample sub substring sum tan tanh times today
toDecimal toFloat toLower toOne toString toTimestamp toUpper trim variance variancePopulation
varianceSample variantTo weekOfYear year
```

Names that are **NOT** dyna functions despite appearing in the rosetta docs or in intuition:
`upper`, `lower`, `substr`, `nvl`, `ifnull`, `len`, `strlen`, `date_part`, `to_char`.

The canonical upstream example of dyna functions inside a mapping is
`core_relational/relational/tests/mapping/sqlFunction/testSqlFunctionsInMapping.pure:761-841`.
