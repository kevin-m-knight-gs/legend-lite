# A missing set id on an association end fails as "Void not supported!"

    meta::pure::router::store::routing::Void not supported!

## Reproduce

`settest.pure` in this directory is a two-class model: `Parent`, `Child`, one association, one
join. `Parent` is mapped with an explicit set id because it has a subtype:

    *st::Parent[base]: Relational { ... }

With the association ends unqualified the model compiles and the service fails at plan
generation:

    st::PC: Relational
    { AssociationMapping ( children: [st::DB]@P_C, parent: [st::DB]@P_C ) }

Naming the set ids makes it pass:

    st::PC: Relational
    { AssociationMapping ( children[base, st_Child]: [st::DB]@P_C,
                           parent[st_Child, base]: [st::DB]@P_C ) }

The default set id of an unnamed class mapping is its package path with `::` replaced by `_`.

## The finding

The requirement is arguably by design — with several sets over one table an unqualified end
IS ambiguous. The diagnostic is not. `Void not supported!` names no association, no class, no
set and no mapping, and its text bears no relation to what is wrong. The engine knows which
end it failed to resolve.

It matters because a class needs a set id exactly when it roots a subtype hierarchy, which is
how every product taxonomy is modelled — so the shape that requires the qualified spelling is
also the shape with the most associations hanging off it.

## A correction

This was first written up as a defect in navigating to a **schema-qualified** table. That was
wrong: the first failing case happened to involve a schema and I generalised from it without
testing the generalisation. Two later cases gave the identical error with no schema anywhere,
and this minimal model reproduces it with one set implementation, no subtypes and no schema.
The corpus now navigates a schema-qualified table in `CB_SchemaReach` without complaint.
