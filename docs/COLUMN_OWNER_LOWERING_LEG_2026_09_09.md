# Column.owner : Relation — the lowering leg (homework, batch 164, 2026-09-09)

## The witness (5 corpus tests, both lanes)
`meta::pure::lineage::scanColumns::test::{testAssociationMapping, testEmbeddedMapping, testQualifier, testSubType, testView}`:
```
$f->map(t | $t.column.owner->cast(@Table).name->toOne() + '.' + $t.column.name + ' <' + $t.context + '>')
```
`ColumnWithContext.column` → `Column.owner` → `->cast(@Table)` → `.name`. Failure at lowering:
`extend/project columns [u_map__value] reference names unresolvable even after isolation [col='u_map__value' ref='column_owner'] over []`.

## What happened, in order
1. Hand `Column.owner : Table[0..1]`, mapping `owner[tbl]`: the cast is a no-op (already Table); the navigation is one join
   (ColumnToOwnerTable) to the `tbl` set (filter kind='Table'); `.name` reads the joined row. Green for months.
2. Spec `Column.owner : Relation[0..1]` (relational.pure:219), mapping unchanged (`owner[tbl]`): the declared class `Relation`
   is UNMAPPED and has mapped strict subclasses (Table, View) → batch 140's implicit Inheritance op fires (ImplicitInheritance
   rule b) → an inclusive-union body for Relation is synthesized (`MetamodelMapping$class$Relation`) and FAILS TO TYPE:
   "property 'columns' of Relation: expected RelationalOperationElement, got String" — the union threads the join-mapped
   class-typed `columns` (Table maps it by join; View did not) as a scalar String. Mapping `View.columns` by the same join
   did not change it. FINDING A (UnionSynthesis): a class-typed property mapped by JOIN on a member is threaded as a scalar
   in the synthesized inclusive union — real for any user mapping of that shape.
3. An EXPLICIT `Relation[rel]` class mapping (filter kind='Table' or 'View', `columns` by the Table join) + `owner[rel]`:
   no union (the declared class is mapped), the census types clean (19), but the five tests lose: `->cast(@Table)` on the
   navigated `rel` instance must re-root to the `tbl` set — same table, tighter kind filter — and the lowering does not
   carry the owner hop's alias (`column_owner`) into the map's computed column. FINDING B (the leg): CAST-RE-ROOT of a
   NAVIGATION target between two sets over one kind-filtered hierarchy table.

## The design (to decide before code)
The store keeps one table per hierarchy with a `kind` column (the H2 decision, batches 9–10). A cast from a superclass
set to a subclass set over that table is therefore a PREDICATE on the same row (`kind = 'Table'`), never a new join — the
re-root is `AND kind = …` on the already-joined alias, and the alias the navigation produced (`column_owner`) stays the
one the cast's `.name` reads. Where: the resolver's cast re-rooting (`CastReRoot`, which today serves roots) extended to
navigation targets whose source and target sets share a main table; the lowering then has the alias.
Fallback if the two sets do NOT share a table (a user mapping): a join by primary key, as the engine does.

## Order
Its own batch, after leg B (the mapping family does not touch this). Until then Column stays by hand with the receipt in
Pure.java; `Database` landed (batch 164, part 1).
