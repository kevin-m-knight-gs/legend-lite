# A33 — OBJECT-GRAPH LANE (graphFetch / serialize) — adversarial type audit

Scope read IN FULL: `resolver/GraphEmission.java` (3429), `exec/MetamodelWalk.java` (1580),
`resolver/JsonSourceFrame.java` (263), `lowering/InstanceProjection.java` (231),
`lowering/SnapshotEnvelope.java` (148), `lowering/CheckedEnvelope.java` (70),
`lowering/StreamingGraphRoot.java` (42), `compiler/element/ClassLayouts.java` (144).
Read for the emission rules they own: `resolver/ClassSources.java` (structure + binding table +
stc pseudo-bindings), `resolver/NavMaterializer.java` (slot prefixes; makes no JSON-value type
decision), plus the consumers `lowering/Lowerer.serializeGraph` (L811-1018), `lowering/Fold`
(`jsonDateWrap` L955, `leafResultType` L983), `sql/dialect/AnsiSqlRenderer` (`jsonObject` L473,
`jsonArrayAgg` L485), `exec/ExecutionResult` (L132), `sql/Json`, `server/Json`.

## ARCHITECTURE (established by reading, confirmed by running)

`GraphEmission` produces **no JSON**. It builds a `TypedSerializeGraph` HIR node (leaves =
`TypedFuncCol` lambdas over the row var, children = correlated sub-nodes). `Lowerer.serializeGraph`
turns that into SQL `json_object(...)` / `coalesce(to_json(list(... ORDER BY ...)),'[]')`, and
**the DATABASE writes every byte of the JSON**. Consequently:

* **All string/key escaping is DuckDB's** and is correct (verified below, incl. control chars,
  non-BMP, backslash, quote).
* **All number spelling is DuckDB's json_object**, which is *not* JSON-safe for IEEE specials or
  for big decimals — see F1/F2.
* **No type tag rides any value.** The only type information in the payload is (a) the `@type`
  string key when `includeType` is set, and (b) `'pkg::Enum.'` string prefixes when
  `includeEnumType` is set. Both are **compile-time constants** — see F3/F4.

Every repro below is `/home/user/probe/jrun.sh` against DuckDB with the model/DDL in
`/tmp/claude-0/-home-user-legend-lite/08c9472b-ec93-5963-a98b-42a9c76b294a/scratchpad/a33/`.

### The canonical model (`a33/model.pure`)
```
Enum model::Color { RED, GREEN, BLUE }
Class model::Edge {
  id:Integer[1]; sInt:Integer[1]; sFloat:Float[1]; sDecimal:Decimal[1]; sNumber:Number[1];
  sString:String[1]; sBool:Boolean[1]; sDate:Date[1]; sStrictDate:StrictDate[1];
  sDateTime:DateTime[1]; color:Color[1];
  optString:String[0..1]; optInt:Integer[0..1]; optFloat:Float[0..1]; optDate:DateTime[0..1];
}
Class model::Tag { tagName:String[1]; ord:Integer[1]; }
Association model::Edge_Tag { edge:Edge[1]; tags:Tag[*]; }
Class model::Base { bid:Integer[1]; bname:String[1]; }
Class model::Sub extends model::Base { extra:String[1]; }
```
plus `tagNames:String[*]` / `tagOrds:Integer[*]` (`a33/model_many.pure`) mapped
`[store::DB] @Edge_Tag | T_TAG.TAG_NAME`, and a nested to-one `a:Inner[1]` (`a33/mangle.pure`).

### THE RAW JSON (task 2) — `model::Edge.all()->graphFetch(#{...all 15 props...}#)->serialize(same)`
```
[{"id":1,"sInt":9223372036854775807,"sFloat":0.1,"sDecimal":12345678901234567890123456789012345678,"sNumber":1.5,"sString":"plain","sBool":true,"sDate":"2020-01-02","sStrictDate":"2021-03-04","sDateTime":"2022-05-06T07:08:09.123456000","color":"RED","optString":"opt","optInt":7,"optFloat":2.5,"optDate":"2023-01-01T00:00:00.000000000"},
 {"id":2,"sInt":-9223372036854775808,"sFloat":3.141592653589793,"sDecimal":-12345678901234567890123456789012345678,"sNumber":0.0,"sString":"","sBool":false,"sDate":"1900-12-31","sStrictDate":"0001-01-01","sDateTime":"9999-12-31T23:59:59.999999000","color":"GREEN","optString":null,"optInt":null,"optFloat":null,"optDate":null},
 {"id":3,"sInt":0,"sFloat":1e308,"sDecimal":0,"sNumber":1e-308,"sString":"quote:\" back:\\ nl:\n tab:\t nonbmp:<U+1F600> ctl:\u0001\u001F end","sBool":true,"sDate":"2020-02-29","sStrictDate":"2020-02-29","sDateTime":"2020-02-29T12:00:00.000000000","color":"BLUE","optString":"x","optInt":-1,"optFloat":-2.5,"optDate":"1970-01-01T00:00:00.000000000"},
 {"id":4,"sInt":1,"sFloat":Infinity,"sDecimal":1,"sNumber":NaN,"sString":"inf","sBool":true,"sDate":"2020-01-01","sStrictDate":"2020-01-01","sDateTime":"2020-01-01T00:00:00.000000000","color":"RED","optString":null,"optInt":null,"optFloat":-Infinity,"optDate":null},
 {"id":5,"sInt":null,"sFloat":null,"sDecimal":null,"sNumber":null,"sString":null,"sBool":null,"sDate":null,"sStrictDate":null,"sDateTime":null,"color":null,"optString":null,"optInt":null,"optFloat":null,"optDate":null},
 {"id":6,"sInt":1,"sFloat":1.0,"sDecimal":1,"sNumber":1.0,"sString":"s","sBool":true,"sDate":"2020-01-01","sStrictDate":"2020-01-01","sDateTime":"2020-01-01T00:00:00.000000000","color":null,"optString":null,"optInt":null,"optFloat":null,"optDate":null}]
[PARSE-FAIL sql.Json]    java.lang.NumberFormatException: For input string: "12345678901234567890123456789012345678"
[PARSE-FAIL server.Json] java.lang.NumberFormatException: For input string: "12345678901234567890123456789012345678"
```
(`<U+1F600>` is the literal 4-byte emoji; verified by codepoint dump — `a33/G2.java` printed `U+1F600`.
The `?` seen in a terminal is console encoding, not data loss.)

Row 4 = `Infinity`/`NaN`; row 5 = every column NULL; row 6 = `COLOR='PURPLE'` (not in the
EnumerationMapping).

---

## FINDINGS

### [UNSOUND / INVALID-JSON] Bare `Infinity`, `-Infinity`, `NaN` reach the graph JSON; BOTH platform JSON readers throw
**Confirms the orchestrator's forwarded report, and characterizes it.**

Which values do it: **any `Float`/`Number`-typed leaf whose DOUBLE cell holds an IEEE special.**
`Float`/`Number` map to `DOUBLE` and go into `json_object` *raw* (no wrap): `Lowerer.serializeGraph`
L821-825 emits `Fold.jsonDateWrap(envelopeScalar(leaf,…), Fold.leafResultType(leaf))`, and
`Fold.jsonDateWrap` (Fold.java:955-960) is identity for everything except `DATE_TIME`/`DATE`:
```java
if (t != Type.Primitive.DATE_TIME && t != Type.Primitive.DATE) { return e; }
```
`Integer`, `Decimal`, `String`, `Boolean`, `StrictDate` and the enum CASE all likewise go in raw.

Repro (`a33/q_inf.pure`, data row 4 `'Infinity'::DOUBLE / 'NaN'::DOUBLE / '-Infinity'::DOUBLE`):
```
$ jrun.sh G.java model.pure q_inf.pure test::R setup_inf.sql
[JSON] [{"id":1,"sFloat":0.1,"sNumber":1.5,"optFloat":2.5},{"id":2,...},{"id":3,...},
        {"id":4,"sFloat":Infinity,"sNumber":NaN,"optFloat":-Infinity}]
[PARSE-FAIL sql.Json]    java.lang.NumberFormatException: For input string: ""
[PARSE-FAIL server.Json] java.lang.IllegalArgumentException: Invalid number: expected digit, got 'I' at line 1 col 193
```
`Infinity`/`NaN`/`-Infinity` are not JSON (RFC 8259 has no such literals).
* `com.legend.sql.Json.num()` (Json.java:171-184) scans only `"+-0123456789.eE"`, so on `I`/`N` it
  builds the empty token and `Long.parseLong("")` throws.
* `com.legend.server.Json` number parser (Json.java:875-905) throws
  `Invalid number: expected digit, got 'I'`.

The tabular lane for the same cells returns honest Java values (`Double(Infinity)`, `Double(NaN)`,
`Double(-Infinity)`), so this is a graph-lane-only corruption.
`ExecutionResult.Graph`'s own javadoc (`ExecutionResult.java:131`) says *"the json IS a well-formed
JSON array built by the database"* — falsified (DOC-LIE rides along).

### [UNSOUND] A 38-digit `Decimal` is emitted as a bare JSON integer that BOTH platform JSON readers reject
`sDecimal: Decimal[1]` over `DECIMAL(38,0)` emits `12345678901234567890123456789012345678` bare.
That is technically legal JSON (arbitrary-precision numbers are allowed), but it is **not decodable
by this platform**: both readers parse an integer-shaped token with `Long.parseLong`.
* `sql/Json.java:180-183`:
  `return t.contains(".")||t.contains("e")||t.contains("E") ? new BigDecimal(t) : Long.parseLong(t);`
* `server/Json.java:904-905`: `if (isFloat) return Num.ofDecimal(new BigDecimal(num)); return Num.ofLong(Long.parseLong(num));`

Both throw `NumberFormatException: For input string: "12345678901234567890123456789012345678"`
(pasted above). The comment at `sql/Json.java:174-179` explicitly reasons about Decimal exactness —
and then loses every Decimal that does not fit a `long`. Round-trip is broken: the graph lane
cannot read back what the graph lane wrote. The tabular lane decodes the identical cell as an exact
`BigDecimal`.

### [UNSOUND] Required (`[1]`) properties serialize as JSON `null`, including required nested objects — silently, with no defect
The JSON claims to be an instance of the class; nothing enforces the declared multiplicity.

1. Scalars — row 5 above: every `[1]` property (`sInt`, `sString`, `sBool`, `sDate`, `sDateTime`, …)
   is `null`.
2. Nested `[1]` object (`a: Inner[1]` via a NULL / dangling FK), `a33/q_toone.pure` +
   `a33/mangle_setup2.sql`:
   `[{"id":1,"a":{"b":"NESTED-b"}},{"id":2,"a":null},{"id":3,"a":null}]`
3. Element of a `[*]` object array: `Tag.tagName: String[1]` with `TAG_NAME` NULL —
   `[{"id":1,"tags":[{"tagName":"alpha","ord":1},{"tagName":"be\"ta","ord":2},{"tagName":null,"ord":null}]}, …]`
4. `String[*]` collections contain `null` ELEMENTS (Pure has no null inside a collection):
   `[{"id":1,"tagNames":["alpha","be\"ta",null],"tagOrds":[1,2,null]}, …]`
   The primitive-array child is `json_group_array(t1.TAG_NAME)` with no null filter
   (`GraphEmission.primitiveArrayChild` L781-834; the tabular lane's `Fold.optionalScalarCell` →
   `CompactList` null-drop has no counterpart here).
5. JSON-model source (`JsonSourceFrame`): a `[1]` property whose key is ABSENT from every payload
   object emits `null`. `a33/json.pure` (`missing: String[1]`, key never present):
   `[{"fullName":"O'Brien Smith","age":30,"missing":null},{"fullName":"NoMissing Row","age":25,"missing":null}]`
   The site admits it: `JsonSourceFrame.java:212-217` — *"conform to the declared [1] BY EMISSION
   (toOne erases value-wise in SQL — an absent key stays a NULL cell)"*.

### [UNSOUND] `includeType` stamps the STATIC class on every object; a subclass instance is labelled as its superclass
`Lowerer.serializeGraph` L848-855:
```java
if (g.typeKeyName() != null && g.classFqn() != null && !g.bareValue()) {
    baseKv.add(new SqlExpr.StringLit(g.typeKeyName()));
    baseKv.add(new SqlExpr.StringLit(SnapshotEnvelope.typeName(g.classFqn(), g.fqTypePath())));
```
`g.classFqn()` is the ClassSource's class — the queried/static class, a compile-time constant.

**Separate tables** (`a33/poly4.pure`: `Sub[s]`→T_SUB, `Sub2[s2]`→T_SUB2, `*Base : Operation
{inheritance_…(s, s2)}`), `serialize(…, alloyConfig(true,true,false,false))`:
```
[SQL]  … json_object('@type', 'model::Base', 'bid', t2.bid, 'bname', t2.bname)
       ORDER BY t2.u_serial_ord__ DESC, t2."stc_model__Sub___$member" DESC,
                t2."stc_model__Sub2___$member" DESC …
[JSON] [{"@type":"model::Base","bid":2,"bname":"subRow"},
        {"@type":"model::Base","bid":3,"bname":"sub2Row"}]
```
Row 1 IS a `model::Sub`, row 2 IS a `model::Sub2`. The **membership witnesses that carry the truth
are in the same SELECT** (used as ORDER BY keys) and are simply not consulted.

`->subType(@X){…}` recovers the type for *exactly the subclasses the user enumerates* — every other
one is still mis-stamped (`a33/q_sub.pure`):
```
[JSON] [{"@type":"model::Sub","bid":2,"bname":"subRow","extra":"EXTRA-VAL"},
        {"@type":"model::Base","bid":3,"bname":"sub2Row"}]     <-- this is a model::Sub2
```

**Single table** (`a33/poly_same.pure`, Sub & Sub2 both on T_ALL) is worse: *every* row is stamped
`model::Base` although no concrete `Base` set exists at all —
`[{"@type":"model::Base","bid":1,"bname":"rowSub"},{"@type":"model::Base","bid":2,"bname":"rowSub2"}]`
— and the recovery route is **not implemented** for that mapping shape:
```
[PLAN-ERROR] com.legend.error.NotImplementedException: graph ->subType(@model::Sub):
             carrier column 'stc_model__Sub___extra' is not on the row (non-union subtype
             mapping) — not built yet
```
(`GraphEmission.subTypePatch`, L3115-3119). So for a single-table hierarchy the concrete class is
**unrecoverable** from the emitted JSON by any means.

Without `includeType` there is no type key at all, so nothing anywhere recovers the actual class;
`ExecutionResult.returnType()` reports the static `model::Base` in every case.

### [UNSOUND / SILENT FALLBACK] `includeEnumType` turns a NULL enum into the string `"pkg::Enum."`
`GraphEmission.enumPrefixed` (L3351-3374) wraps an enum leaf in `plus('model::Color.', <case>)`;
this renders as SQL `concat(...)`, and DuckDB `concat` **skips NULL** rather than propagating it.
The site knows: L3281 *"a NULL enum rides concat's null-skip — acceptable"*.

`a33/q_enumnull.pure`, `COLOR='PURPLE'` (not in the EnumerationMapping):
```
[JSON] [{"id":6,"color":"model::Color."}]
```
`"model::Color."` is neither `null` nor any member of the enumeration — a value that cannot be
decoded back to a Pure enum at all. Without `includeEnumType` the same row yields
`{"id":6,"color":null}` (see next finding).

### [SILENT FALLBACK] An enum source value outside the EnumerationMapping becomes `null` on a `[1]` enum property
```
[SQL]  'color', CASE WHEN t0.COLOR='RED' THEN 'RED'
                ELSE CASE WHEN t0.COLOR='GREEN' THEN 'GREEN'
                ELSE CASE WHEN t0.COLOR='BLUE' THEN 'BLUE' ELSE NULL END END END
[JSON] [{"id":6,"color":null}]
```
No error, no defect. In the JSON, "source value not in the mapping" and "genuinely absent" are the
same token. (The tabular lane is identical — `[COL] color : model::Color mult=[1]` / `[ROW] … null`
— so this is a shared mapping-lowering defect, but it is what the graph lane emits.)

### [UNSOUND] Enum identity is erased in the default envelope: `Color[1]` and `String[1]` are indistinguishable JSON
`"color":"RED"` is byte-identical to a `String` property holding `"RED"`. Nothing in the payload
distinguishes them; the declared type is not carried and the reader has no way to recover it unless
the caller both knows the tree and sets `includeEnumType` (which is itself unsound, above).

### [UNSOUND] The graph result's declared return type is the CLASS for a value that is a JSON String; Phase G and Phase H/K disagree
**Confirms the orchestrator's forwarded report.**

* Phase G types `serialize` from its registered signature as `String[1]`
  (`GraphFetchChecker.serialize`, and `Pure.SERIALIZE__…` returns
  `meta::pure::metamodel::type::String[1]`).
* Phase H replaces the root with `TypedSerializeGraph` and `QueryPlan.rootType()` /
  `ExecutionResult.returnType()` report the CLASS at `[*]`.

`a33/RT.java`, query `model::Edge.all()->graphFetch(#{Edge{id}}#)->serialize(#{Edge{id}}#)`:
```
[G]    class=TypedSerialize type=String mult=[1]
[PLAN] rootType=model::Edge mult=[*] shape=GRAPH
```
and at execution (`a33/G.java`):
```
[SHAPE]      com.legend.exec.ExecutionResult$Graph
[RETURNTYPE] typeName=model::Edge repr=ClassType[fqn=model::Edge]
[COL]        json : String mult=null
```
`ExecutionResult.Graph` (`ExecutionResult.java:132-150`) is **self-contradictory**: `returnType()`
= `model::Edge` (ClassType, and the interface javadoc at L18 says *"The Pure-level result type.
Never null."*), while `columns()` hard-codes `new Column("json", Type.Primitive.STRING)` and
`rows()` yields the single JSON string. The declared multiplicity is also lost (`mult=null`), and a
`[*]` type is claimed for a value carried as exactly one row/one cell.
Same for `graphFetchChecked(...)->serialize(...)`: `returnType()` = `model::Edge` for a value whose
shape is `{"defects":[…],"value":{…}}`, which is not an `Edge` at all.

### [UNSOUND] Scalar-position `serialize` picks the JSON's STRUCTURAL shape at RUNTIME from the row count
`SnapshotEnvelope.fold` (SnapshotEnvelope.java:35-52) rewrites the array aggregate to
`CASE WHEN COUNT(*)=1 THEN MIN(<obj>) ELSE <array> END`. Static type is `String[1]` regardless.

`a33/q_scal.pure` (`'PRE:' + …serialize(…)`), same query, different data:
```
1 matching row  -> [ROW] String(PRE:{"id":1,"sString":"plain"})                       <-- OBJECT
2 matching rows -> [ROW] String(PRE:[{"id":1,"sString":"plain"},{"id":2,"sString":""}])<-- ARRAY
0 matching rows -> [ROW] String(PRE:[])                                                <-- ARRAY
```
Root position, identical tree and identical single row, always the array:
```
[JSON] [{"id":1,"sString":"plain"}]
```
So the same `serialize` tree yields an object or an array depending on (a) syntactic position and
(b) the number of rows the database happened to find. Nothing in the type declares which.

### [CRASH] Any graphFetch/serialize on a declared SQLite connection dies with a raw `org.sqlite.SQLiteException`
`Compiler.dialectOf` (Compiler.java:559-567) gives SQLite the plain `AnsiSqlRenderer`, whose
`jsonArrayAgg` (AnsiSqlRenderer.java:485-500) renders **DuckDB-only** functions:
`coalesce(to_json(list(<obj> ORDER BY …)), '[]')`. `AnsiSqlRenderer.jsonObject` (L473) is
documented at L470 as *"DuckDB reference JSON-object constructor"*.

```
$ jrun.sh GH.java model_sq.pure q_sq.pure test::R setup_sq.sql "jdbc:sqlite::memory:"
[jdbc:sqlite::memory:-ERR] org.sqlite.SQLiteException: [SQLITE_ERROR] SQL error or missing
                          database (no such function: list)
```
The same model's TABULAR query on the same SQLite session succeeds:
`Tabular[columns=[…id…, …s…], rows=[Row[values=[1, plain]]], …]`.
A raw JDBC exception escapes to the caller instead of a `DialectCapability` wall.
DOC-LIE rides along: Compiler.java:559-560 — *"SQLite differs from the ANSI baseline ONLY lexically
— it is a Lexicon row, not a dialect subclass"* — false for the whole graph lane.
(H2 was checked and is fine: byte-identical JSON to DuckDB for the same row.)

### [SILENT FALLBACK] A real property whose name matches the synthetic subtype-column pattern is silently DROPPED from the implicit envelope
`GraphEmission.synthesizeScalarTree` L86-88:
```java
if (com.legend.model.ClassMapping.isSubTypeColumn(e.getKey())) { continue; }
```
`ClassMapping.isSubTypeColumn` (ClassMapping.java:62-64) is pure string surgery:
```java
return col.startsWith("stc_") && col.contains("___");
```
Any user property named `stc_<anything>___<anything>` matches. `a33/mangle.pure` declares
`stc_model__Outer___b: String[1]`, mapped to `T_OUT.STCCOL='STC-VAL'`.

Explicit tree — the property IS emitted:
```
[JSON] [{"id":1,"a_b":"SCALAR-a_b","stc_model__Outer___b":"STC-VAL","k__a__0_k":"K-VAL","__id":"ID-VAL","a":{"b":"NESTED-b"}}]
```
IMPLICIT envelope (`model::Outer.all()`) — the property and its value vanish, no error:
```
[SQL]  … json_object('id', t0.ID, 'a_b', t0.A_B, 'k__a__0_k', t0.KCOL, '__id', t0.IDCOL) …
[JSON] [{"id":1,"a_b":"SCALAR-a_b","k__a__0_k":"K-VAL","__id":"ID-VAL"}]
```
A mapped, declared `String[1]` property of the class is missing from the object the same code path
claims is a complete `model::Outer`. `ClassSources` also installs stc pseudo-bindings by column name
(`ClassSources.java:688-701`, `bindings.put(c.name(), …)`), so a same-named real property and a
synthesized dispatch column share one namespace with last-write-wins.
(`ClassMapping.classOfWitnessPrefix` L72-80 *documents* that this mangling is lossy — and then
`isSubTypeColumn`, the gate that actually drops data, is the un-guarded prefix test.)

### [UNSOUND / DATA LOSS] `Base.all()` under an `inheritance` Operation silently ignores the declared member sets and drops the base class's own extent
`UnionSynthesis.synthInheritance` (L486-513) never reads the operation's arguments; it calls
`inheritanceMembers` → `collectInheritanceMembers` (L736-786), which enumerates **strict subclasses
only** ("STRICTLY below base"), then `if (members.size() == 1) return synthRelational(members.get(0))`.

`a33/poly.pure` — `*model::Base : Operation { inheritance_…(b, s) }`, `Base[b]`→T_BASE (row
bid=1 'plainBase'), `Sub[s]`→T_SUB (row bid=2 'subRow'):
```
[SQL]  SELECT … json_object('bid', t0.BID, 'bname', t0.BNAME) … FROM T_SUB AS t0
[JSON] [{"bid":2,"bname":"subRow"}]
```
The `Base[b]` extent is gone — an entire object is missing from a `Base.all()` result, silently.
Replacing the args with `(nonexistent1, nonexistent2)` (`a33/poly3.pure`) produces **byte-identical**
output and no error, proving the declared member list is dead input. Swapping to `(s, b)` changes
nothing either. Both lanes are affected (tabular: `FROM T_SUB AS t0`, one row) — it is the extent
that is wrong, not the serializer.

### [SILENT FALLBACK] `graphFetchChecked` reports ZERO defects for an object that violates every constraint, because SQL NULL is not `false`
`CheckedEnvelope.wrap` (CheckedEnvelope.java:36-45) emits `CASE WHEN NOT <pred> THEN <defect> ELSE
NULL END`. A predicate that evaluates to SQL NULL makes `NOT NULL` = NULL, the WHEN is not true, and
**no defect is produced**.

Model `a33/model_con.pure`: `Class model::Edge [posInt: $this.sInt > 0, nonEmptyStr: $this.sString != '']`.
Row 5 (every column NULL):
```
[JSON] [{"defects":[],"value":{"id":5,"sString":null,"sInt":null}}]
```
Zero defects for an object whose `sInt: Integer[1]` and `sString: String[1]` are both absent and
whose `posInt` constraint is unsatisfied. The whole point of the checked envelope is data-quality
reporting; NULL-valued predicates pass silently.
(Working case for contrast, row 2 `sInt=Long.MIN`, `sString=''`: two defects reported correctly with
the engine's `id/externalId/message/enforcementLevel/ruleType/ruleDefinerPath/path` shape.)

### [INFORMATION LOSS / INCONSISTENCY] Graph vs tabular: the same cell, two different spellings (task 3)
Same model, same rows, `graphFetch/serialize` vs `project(~[…])`, DuckDB, side by side:

| prop | graph JSON | tabular value | verdict |
|---|---|---|---|
| `sInt` (Long.MAX/MIN) | `9223372036854775807` / `-9223372036854775808` | `Long(9223372036854775807)` / `Long(-9223372036854775808)` | same |
| `sDecimal` (38 digits) | `12345678901234567890123456789012345678` | `BigDecimal(1234…5678)` | **graph unreadable by both platform JSON readers** |
| `sFloat` = +Inf | `Infinity` | `Double(Infinity)` | **graph is invalid JSON** |
| `sNumber` = NaN | `NaN` | `Double(NaN)` | **graph is invalid JSON** |
| `sFloat` = 1e308 | `1e308` | `Double(1.0E308)` | same value, different spelling |
| `optFloat` = 5e-324 | `5e-324` | `Double(4.9E-324)` | same value, different spelling |
| `sStrictDate` = year 1 | `"0001-01-01"` | `StrictDate(1-01-01)` | **divergent: tabular is not zero-padded / not ISO-8601** |
| `sDateTime` | `"2022-05-06T07:08:09.123456000"` (9 digits, **no zone**) | `DateWithSubsecond(2022-05-06T07:08:09.123456+0000)` (6 digits, **+0000**) | **divergent spelling** |
| `optDate` = midnight | `"2023-01-01T00:00:00.000000000"` | `DateWithSecond(2023-01-01T00:00:00+0000)` | **divergent: graph erases the second-vs-subsecond precision distinction that the tabular lane materializes as a different value class** |
| `color` = 'RED' | `"RED"` (bare string) | `String(RED)` with column pure type `model::Color` | graph loses the enum type entirely; tabular at least declares it on the column |
| `color` = 'PURPLE' | `null` | `null` | same silent fallback |
| `sString` (quote/backslash/NL/tab/U+1F600/U+0001/U+001F) | `"quote:\" back:\\ nl:\n tab:\t nonbmp:<emoji> ctl:\u0001\u001F end"` | identical Java string | **sound both ways** |
| `sString` = `''` | `""` | `String()` | same |
| `-0.0` stored | `0.0` | `Double(0.0)` | sign of zero lost in DuckDB storage, identical both lanes (verified with raw JDBC: `jdbcDouble=0.0 json={"v":0.0}`) |
| `tagNames: String[*]` | one nested array per parent, nulls kept | 6 exploded rows, column widened to `String mult=[0..1]` | fundamentally different shapes |

The date-family divergence is by construction: `Fold.jsonDateWrap` (Fold.java:955-975) is the graph
lane's ONLY date formatter (`strftime('%Y-%m-%dT%H:%M:%S.%f') || '000'`), and it never runs in the
tabular lane, which decodes to `PureDateLiteral` subclasses with `+0000`.

### [INFORMATION LOSS] The emitted key ORDER does not follow the requested tree — all leaves are emitted before all children
`GraphEmission.buildGraphNode` (L205-207) accumulates `leaves` and `children` into two separate
lists; `Lowerer.serializeGraph` (L821-848) emits every leaf then every child.

Requested `#{ Edge { id, tags { tagName }, sString } }#` →
```
[JSON] [{"id":1,"sString":"plain","tags":[{"tagName":"alpha"},{"tagName":"be\"ta"}]}]
```
`sString` overtakes `tags`. Requested `#{ Outer { id, a_b, a { b }, stc…, k…, __id } }#` →
`{"id":…,"a_b":…,"stc…":…,"k…":…,"__id":…,"a":{…}}`. JSON object order is not semantic, but the
engine contract this file repeatedly cites is tree order, and any golden-text comparison breaks.

### [INFORMATION LOSS] `[*]` PRIMITIVE arrays get NO deterministic order; `[*]` OBJECT arrays do
Object child (`GraphEmission.buildGraphNode` adds `pkOrderKeys`, L458-490):
```
'tags', (SELECT coalesce(to_json(list(json_object(...) ORDER BY t1.ID ASC NULLS LAST)),'[]') …)
```
Primitive array child (`GraphEmission.primitiveArrayChild` L781-834 constructs
`new TypedSerializeGraph(childRel, childVar, List.of(leaf), List.of(), true, true, colRead.info())`
— the short ctor, so `orderKeys` is empty):
```
'tagNames', (SELECT coalesce(json_group_array(t1.TAG_NAME), '[]') AS result FROM T_TAG AS t1 WHERE …)
```
No ORDER BY. The row-order determinism the file's own comment calls the "engine graph contract"
(GraphEmission L347-352) is enforced for object arrays and not for primitive arrays. The JSON-model
root (`JsonSourceFrame`) is likewise unordered: `coalesce(json_group_array(json_object(…)),'[]')`.

### [CRASH] `store::DB.schemas->map(s|$s.name)` lowers a Database ELEMENT to a string literal and dies with a raw `SQLException`
This is the surface that reaches `MetamodelWalk` (see below). `Compiler.plan` types it
`String[*] shape=COLLECTION` and renders:
```
[SQL] SELECT UNNEST(list_filter(list_transform(struct_extract('DB', 'schemas'), s -> s.name), x -> x IS NOT NULL)) AS value
Exception in thread "main" java.sql.SQLException: Binder Error: No function matches the given name
  and argument types 'struct_extract(STRING_LITERAL, STRING_LITERAL)'.
```
The Database element became the string `'DB'`. `-verbose:class` confirms `MetamodelWalk` **is**
loaded for this query (so the walk is entered), then the fallback lowering produces nonsense and a
raw JDBC exception escapes.

### [CRASH, cross-lane] `Integer` arithmetic overflow escapes as a raw `java.sql.SQLException`
Derived leaf `doubled(){ $this.sInt * 2 }: Integer[1]` over `sInt = Long.MAX`:
```
[SQL] … json_object('id', t0.ID, 'doubled()', t0.S_INT * 2, …)
Exception in thread "main": java.sql.SQLException: Out of Range Error: Overflow in multiplication of INT64 (9223372036854775807 * 2)!
```
Identical in the tabular lane, so not a graph-specific divergence — recorded for completeness.

### [DEAD IN THIS LANE] `exec/MetamodelWalk.java` (1580 lines) executes ZERO paths during a normal graph query — and its runtime type decisions are lossy where it *does* run
**Empirical method** (lazy class loading is the observable): compile a probe, run
`java -verbose:class`, grep for the class. A class that is never loaded ran no code.

```
$ java -verbose:class -cp out:$CP G <model> <query> test::R <setup> | grep -c "com.legend"
q_all.pure  (graph, 15 primitives)          -> metamodel classes loaded = 0, total legend classes = 1031
q_tags.pure (graph, nested to-many)         -> 0, 1059
q_tab.pure  (tabular project)               -> 0, 1027
q_sub.pure  (graph + ->subType + @type)     -> 0,  972
q_chk.pure  (graphFetchChecked)             -> 0, 1082
```
Neither `MetamodelWalk` nor `MetamodelSteps` is among the ~1000 loaded classes in any of them.
Call-graph reason: `StatementExecutor.execute` calls `planWalk(preRoot, …)` (StatementExecutor.java:346)
for every statement, but `planWalk` (L1146-1174) only enters MetamodelWalk for a
`TypedPackageableRef`, a constructed `meta::relational::metamodel::…` instance, or a
`TypedNativeCall` head — a resolved graph root is a `TypedSerializeGraph`, so it returns null at the
first check. It IS reachable: `store::DB.schemas->map(…)` loads `MetamodelWalk`, `MetamodelWalk$Cm`,
`MetamodelWalk$$Lambda` (and then crashes, previous finding).

**Type decisions it makes at RUNTIME rather than compile time** (probe `a33/MMW.java`, run against
`a33/model.pure`; `S_STRING VARCHAR(4000)`, `OPT_STRING VARCHAR(200)`, `S_INT BIGINT`,
`S_FLOAT DOUBLE`, `S_DECIMAL DECIMAL(38,0)`):
```
  column S_STRING                            -> Varchar[size=4000]   sqlText=VARCHAR(4000)
  column S_INT (BIGINT)                      -> BigInt[]             sqlText=BIGINT
  concat(S_STRING, OPT_STRING)               -> Varchar[size=4200]   sqlText=VARCHAR(4200)
  concat(OPT_STRING, S_INT)                  -> Varchar[size=200]    sqlText=VARCHAR(200)   <-- BIGINT contributes 0
  plus(OPT_STRING, OPT_STRING)               -> Varchar[size=200]    sqlText=VARCHAR(200)   <-- MAX not SUM
  divide(S_INT, S_INT)                       -> BigInt[]             sqlText=BIGINT         <-- 1/2 is not a BIGINT
  divide(S_INT, S_DECIMAL)                   -> Decimal[38,0]        sqlText=DECIMAL(38, 0)
  times(S_INT, S_INT)                        -> BigInt[]             sqlText=BIGINT         <-- overflows
  case(pred, OPT_STRING, S_INT)              -> Varchar[size=200]    sqlText=VARCHAR(200)   <-- BIGINT branch discarded
  case(pred, S_FLOAT, S_INT)                 -> Double_[]            sqlText=DOUBLE
  literal ''                                 -> Varchar[size=0]      sqlText=VARCHAR(0)     <-- not a legal SQL type
  substring(S_STRING, 1, 2)                  -> Varchar[size=4000]   sqlText=VARCHAR(4000)
  unknown fn foo(S_STRING)                   -> null                 sqlText=null           (documented fall-through)
  sum(S_INT)                                 -> BigInt[]             sqlText=BIGINT         <-- SUM over BIGINT overflows
  count(S_STRING)                            -> Integer_[]           sqlText=INT
  case-insens column 't_edge'.'s_string'     -> Varchar[size=4000]                          <-- equalsIgnoreCase lookup
  nodeOf singleton-list collapse: NodeH[kind=FunctionCall, props={arguments=ONE, distinct=false}]
  nodeOf 2-element list:          NodeH[kind=FunctionCall, props={arguments=[A, B], distinct=false}]
  nodeOf empty list (key drops):  NodeH[kind=FunctionCall, props={distinct=false}]
```
Where the static type can disagree with the produced value:
1. `inferOp` `concat`/`group_concat` (MetamodelWalk.java:1265-1276) sums only Varchar/Char sizes; a
   non-text operand contributes **0**, so `concat(VARCHAR(200), BIGINT)` is typed VARCHAR(200) while
   the value can be 220 chars. Truncating disagreement.
2. `safe()` (L1454-1500) final arm `return a` — a `case`/`plus` mixing incompatible families silently
   takes the FIRST operand's type. `case(pred, VARCHAR(200), BIGINT)` → VARCHAR(200).
3. `plus` over two Varchars goes through `safe`, which takes MAX for two Varchars, not SUM
   (string `+` is concatenation): VARCHAR(200)+VARCHAR(200) typed VARCHAR(200).
4. `divide` (L1309-1316) widens positionally, so INT/INT is typed INT — a value of 0.5 disagrees.
5. `sum` (L1240-1246) promotes only float→double; `sum(BIGINT)` stays BIGINT and can overflow it.
6. `Literal String` (L1339-1340) → `Varchar(str.length())`; the empty literal yields `VARCHAR(0)`.
7. `nodeOf` (L120-140) collapses a **one-element list to its element** — a runtime multiplicity
   erasure: a 1-argument call and a scalar-valued property become structurally identical.
8. `prop(recv,"root")` (L1073-1083) decides "root-ness" at runtime from set COUNT ("a class's SOLE
   set is implicitly root").
9. `sqlJoinType(null)` (L457-464) defaults to `LEFT` — a defaulting decision, not an error.
10. `columnType` (L1524-1560) matches table AND column with `equalsIgnoreCase`, and strips any
    schema qualifier (`table.substring(lastIndexOf('.')+1)`) — two same-named columns differing only
    in case, or two same-named tables in different schemas, bind to whichever is found first.
11. `targetTable` (L495-506) picks the first table that is not the parent's, else `tables.get(0)` —
    ambiguous for a self-join.

### [DOC-LIE] `ExecutionResult.Graph`'s "well-formed JSON array" contract
`ExecutionResult.java:131` — *"Graph result: the json IS a well-formed JSON array built by the
database."* Falsified twice: `Infinity`/`NaN` make it not-JSON, and scalar-position `serialize` makes
it a bare OBJECT for a singleton (both pasted above). Also `rows()` (L146-148) substitutes `""` — not
`[]`, not JSON — when `json` is null.

---

## VERIFIED SOUND (specific, with the check that was run)

* **String escaping is complete and correct.** DuckDB's `json_object` escaped `"` → `\"`, `\` → `\\`,
  newline → `\n`, tab → `\t`, U+0001 → `\u0001`, U+001F → `\u001F`, and preserved the non-BMP
  U+1F600 as a real 4-byte character (verified by codepoint dump, `a33/G2.java`). Zero unescaped
  control characters in any run. The empty string emits `""`, not `null`.
* **JSON KEY escaping is correct**, including keys synthesized from qualifier call spellings.
  `label('say "hi" and \ back')` produced the key
  `"label('say \"hi\" and \\ back')"` and both readers parsed it.
* **Double round-tripping is faithful.** `0.30000000000000004`, `1.7976931348623157e308`, `5e-324`,
  `1e-7`, `1e21`, `1e308`, `1e-308` all emit shortest-round-trip forms that decode to the identical
  double. `Long.MIN`/`Long.MAX` are exact in both lanes.
* **`Integer`, `Boolean` are faithful and unambiguous** (`9223372036854775807`, `true`/`false`).
* **The three execution surfaces agree byte-for-byte.** `Compiler.execute`,
  `Compiler.executeStreaming`, `Compiler.executeWire(…, Format.JSON, …)` over the identical query
  and data produced **identical** JSON including the `Infinity` token, the 38-digit decimal, the
  escaped control characters and the date spelling (`a33/Surfaces.java`). The streaming plan differs
  structurally (`json_object(...) … ORDER BY t0.ID` per row vs `to_json(list(... ORDER BY ...))`) but
  the assembled text matches. `executeWire` returns `cols=[]` for GRAPH (documented at
  Compiler.java:411-431).
* **Zero / one / N objects at the root are consistent across all three surfaces**: `[]`,
  `[{...}]`, `[{...},{...}]`. No null-root JSON was reachable at the root; `returnType()` is the
  class in all three cases (see the finding above).
* **Nested to-many OBJECT children** emit correct nested arrays with a deterministic PK order:
  `[{"id":1,"tags":[{"tagName":"alpha","ord":1},{"tagName":"be\"ta","ord":2}]},{"id":3,"tags":[]}]`.
  An empty child is `[]`, never `null` (the `coalesce(...,'[]')` in `jsonArrayAgg`).
* **`[0..1]` optionals emit `null`** and are decodable; `removePropertiesWithNullValues` /
  `removePropertiesWithEmptySets` work — `alloyConfig(false,false,true,true)` produced
  `[{"id":2}]` via `json_merge_patch(json_object('id',…), json_object('optString',…), …)`
  (RFC 7386 merge drops null-valued keys), matching `SnapshotEnvelope.mergePatchObject`.
* **Date/DateTime physical-vs-declared mismatches are walled at compile time, loudly.**
  `StrictDate[1]` mapped to a `TIMESTAMP` column is REJECTED:
  `TypeInferenceException: property 'sdOverTs' of 'model::Edge': expected StrictDate, got DateTime`.
  `Date[1]` over `TIMESTAMP` and `DateTime[1]` over `DATE` are accepted and formatted correctly
  (`"2024-06-07T13:14:15.500000000"`, `"2025-08-09T00:00:00.000000000"`), with the abstract-`Date`
  case dispatching at RUNTIME in SQL (`CASE WHEN typeof(x)='DATE' THEN strftime(x,'%Y-%m-%d') ELSE
  <iso> END`) exactly as `Fold.jsonDateWrap` documents.
* **`graphFetchChecked` envelope shape is correct** when predicates are non-NULL: `{"defects":[…],
  "value":{…}}` with the engine's seven-field defect object, `enforcementLevel`/`ruleType`/
  `ruleDefinerPath` populated, and `"defects":[]` for a clean row.
* **Derived (qualified) properties** serialize under the engine's call-spelling key —
  `{"id":3,"doubled()":0,"label('pre-')":"pre-…"}` — matching `GraphEmission.callKey` (L3017-3060).
* **H2 and DuckDB produce byte-identical graph JSON** for the same row
  (`{"id":1,"sInt":9223372036854775807,"sFloat":0.1,…,"sDateTime":"2022-05-06T07:08:09.123456000",…}`).
* **No nested-object name-mangling collision at the JSON level.** A class with both `a_b: String[1]`
  and `a: Inner[1]` (Inner has `b`) emits `{"a_b":"SCALAR-a_b", …, "a":{"b":"NESTED-b"}}` — nested
  objects are real nested objects, never flattened, so no key can collide. In the tabular lane the
  same pair lowers to a plain join (`SELECT t0.ID AS id, t0.A_B AS ab, t1.B AS nested`) with no
  prefix flattening. `ClassLayouts` performs **no** name mangling at all (it is the SQL-struct field
  list, property names verbatim), and it is LOUD, not silent, on a `__id` collision
  (ClassLayouts.java:72-76) and on conflicting inherited redeclarations (L127-131).
  `AssociationJoins.prefixFor` (L806-817) / `chainedPrefix` (L787-802) do collision-check slot prefixes with an
  ordinal bump. Properties literally named `k__a__0_k` and `__id` survive both the explicit and the
  implicit envelope intact (only the `stc_*___*` pattern is dropped — see the finding).
* **Single-quote SQL escaping in the JSON-model source frame is correct**: a payload value
  `O'Brien` rendered as `'…"O''Brien"…'` and executed cleanly.
* **`ClassSources` / `NavMaterializer`**: read; they make no JSON-value type decision. `ClassSources`
  builds the binding table from the ctor keys and explicitly delegates type/multiplicity conformance
  to Phase G (ClassSources.java:675-677); `NavMaterializer` only composes join-slot prefixes.
* **`InstanceProjection`** is the instance-literal `project` lowering, not part of the graph lane;
  it is loud (`NotImplementedException`) on a collection-valued computed column
  (InstanceProjection.java:93-99) rather than silently putting a list in a cell, and it takes the
  element type from the array's own SQL type with the colspec's declared type as the empty-collection
  fallback (L142-152).

## NOT COVERED

* Milestoned/temporal graph children (`businessDate`/`processingDate` generated leaves,
  `<prop>AllVersions` sweeps, `TemporalFrame`) — a large sub-surface of `GraphEmission`
  (`synthesizeScalarTree` L108-131, `generatedDateLeaf`, `temporalTargetPipe` call sites,
  `childKey`'s resolved-date spelling). Not reached; would need a milestoned fixture.
* `includeObjectReference` / ASOR (`SnapshotEnvelope.asorWrap`, `GraphEmission.asorPrefix`,
  `AsorRef`) — the base64 objectReference channel. Not exercised.
* Embedded (`^Inner(...)`) graph children and `otherwise()` per-leaf FK dispatch
  (`GraphEmission.embeddedChild` L2010-2224), M2M/XStore whole-source children
  (`wholeSrcChild` L1909-1985), mixed-union children (`mixedUnionChild` L1803-1878) — reached only
  with mapping shapes I did not build; several have explicit `NotImplementedException` walls.
* `Byte`, `LatestDate`, `StrictTime` primitives — no store column type maps to them in the
  `DatabaseProtocolParser` table I read, so I could not seed values.
* SQLite graph beyond the first failure (the dialect is unusable for GRAPH at all).
* Whether `MetamodelWalk` is exercised by the JUnit corpus (I did not run `mvn`, per the brief);
  my claim is scoped to `Compiler.execute` / `plan` / `executeStreaming` / `executeWire` on the
  five query shapes listed.
