"""
Makes the hier:: feature mappings EXECUTE, rather than merely compile.

61-inheritance.pure and 62-mapping-features.pure brought the mapping/store taxonomy to
35 of 35 -- but a construct that only compiles proves the front end accepts it, not that the
engine computes anything correct with it. The dynafunction mappings in particular were
worth nothing until something checked what `concat` over a NULL actually returns.

So this emits the runtime, the seeded data and the services for the hier:: classes whose
every property the ORACLE can resolve independently:

    hier::Instrument    5 plain columns -- the inheritance hierarchy's root set
    hier::IssuerLabel   1 column + THREE dynafunctions (concat, toUpper, toLower)

Deliberately NOT hier::Issuer or hier::InstrumentReach. Their `profile` (a Binding
transformer) and `countryName` (a two-hop join chain) are in `c.unparsed` -- the reader has
no rule for either, so the oracle cannot compute an expectation, and a service over them
would have to take its expected values from the engine under test. That is the one thing
this corpus refuses to do, so those two stay compile-only until the reader models them.

The dynafunction service is the point of the exercise. Every expanded table carries a NULL
by construction (property A2), so `concat(LEGAL_NAME, CONTACT_EMAIL)` will meet one -- and
the oracle asserts NULL, not the other argument and not the empty string, from an
implementation written against what concat MEANS rather than against what the engine
returned.
"""
from __future__ import annotations

import model
from query import Proj, Spec

# Each hier:: store has its own runtime, and each runtime its own connection ID. Two
# identifiedConnections sharing an id inside one runtime is rejected outright:
# "Runtime connection with ID 'environment' has already been specified".
# store -> (mapping, runtime, connection id, data element). One data element per STORE:
# a runtime connects one store, and an element carrying another store's tables fails at
# session setup with an error that names the table rather than the packaging.
BINDINGS = {
    "hier::IssuerDB": ("hier::IssuerMapping", "hier::IssuerRT", "issuerEnv",
                       "hier::IssuerData"),
    "hier::HierDB": ("hier::HierMapping", "hier::HierRT", "hierEnv",
                     "hier::InstrumentData"),
}
CONN = "hier::HierConn"

# Classes whose every property the oracle can resolve. Anything with an entry in
# c.unparsed is excluded by construction rather than by a hand-written list, so a reader
# that learns to model join chains automatically widens this.
def executable(c: model.Corpus) -> list[str]:
    """Classes with at least two ORACLE-RESOLVABLE properties.

    Blocking a whole class because ONE property is unmodelled threw away the rest:
    hier::InstrumentReach has a resolvable two-hop join chain and one dyna-over-chain the
    reader cannot model, and blocking on the second meant the first was never executed.
    Unresolvable properties are dropped from the projection instead.
    """
    out = []
    for cls in sorted(c.main_table):
        if not cls.startswith("hier::"):
            continue
        if len(resolvable(c, cls)) >= 2:
            out.append(cls)
    return out


def resolvable(c: model.Corpus, cls: str) -> list[str]:
    """Property PATHS of `cls` the oracle can compute: a plain column, a dynafunction it
    implements, a join chain, or a sub-property reached through an EMBEDDED hop.

    Embedded paths are returned dotted (`contact.email`) and split into a real projection
    path by the caller -- projecting the embedded property itself is meaningless in a TDS,
    only its leaves have values.
    """
    blocked = {p for k, p in c.unparsed if k == cls}
    props = (set(c.columns.get(cls, {}))
             | {p for k, p in c.chains if k == cls}
             # A dynafunction over a chain is recorded ONLY in c.dyna now --
             # it used to be in both, and reading only c.chains would drop it.
             | {p for k, p in c.dyna if k == cls})
    for (owner, prop), child in c.embedded.items():
        if owner == cls:
            # An embedded child's leaves are COLUMNS; a Binding-backed child's are JSON
            # KEYS, which live on the class rather than in the mapping.
            # Binding-backed leaves are deliberately NOT folded in here. They diverge from
            # the oracle (F27) and mixing them into a general service would take the
            # embedded and dynafunction coverage down with them, so they get a service of
            # their own via binding_paths().
            if child not in c.json_backed:
                props |= {f"{prop}.{sub}" for sub in c.columns.get(child, {})}
    return sorted(props - blocked)


def runtime_text() -> str:
    # Every runtime connects EVERY hier:: store, with a distinct connection id per store.
    # A mapping here can span stores -- InstrumentReach joins HIER_INSTRUMENT in HierDB to
    # HIER_ISSUER in IssuerDB -- and a runtime carrying only the class's own store fails at
    # SQL time with "Table with name HIER_INSTRUMENT does not exist", which reads as missing
    # data rather than as a missing connection.
    conns = ",\n        ".join(f"{db}: [ {cid}: {CONN} ]"
                               for db, (_m, _r, cid, _d) in sorted(BINDINGS.items()))
    runtimes = "\n\n".join(
        f"Runtime {rt}\n{{\n    mappings:\n    [\n        {mp}\n    ];\n"
        f"    connections:\n    [\n        {conns}\n    ];\n}}"
        for db, (mp, rt, cid, _dt) in sorted(BINDINGS.items()))
    return f"""// GENERATED by scripts/corpus/hier.py -- do not edit by hand.
//
// Connection, runtimes and seeded data for the hier:: feature domain. Its tables live in
// stores separate from store::DB, and test data is bound to a CONNECTION, so they need
// their own -- and each runtime needs its own connection ID, because two
// identifiedConnections sharing one inside a runtime is rejected outright.
###Connection
RelationalDatabaseConnection {CONN}
{{
    type: DuckDB;
    specification: DuckDB {{ }};
    auth: Test;
}}


###Runtime
{runtimes}
"""


def binding_paths(c: model.Corpus, cls: str, boolean: bool) -> list[str]:
    """Dotted paths reached through a BINDING transformer -- JSON keys, not columns.

    Split by whether the leaf is a BOOLEAN, because that is where F27 divides. Every other
    type comes back as its raw JSON token rendered as a string -- a String arrives with its
    quotes, an Integer arrives as "7" rather than 7 -- so a service projecting one can only
    ever be quarantined. A boolean's token is already a boolean literal and survives, which
    is the only way this feature has a PASSING service at all.

    Two services rather than one: a single service mixing them would fail on the string
    leaves and take the boolean's evidence down with it, leaving the Binding transformer
    with no demonstration that it works anywhere.
    """
    out = []
    for (owner, prop), child in c.embedded.items():
        if owner != cls or child not in c.json_backed:
            continue
        kl = c.classes.get(child)
        if kl is None:
            continue
        out += [f"{prop}.{sub}" for sub, pr in kl.props.items()
                if (pr.type == "Boolean") == boolean]
    return sorted(out)


def specs(c: model.Corpus) -> list[Spec]:
    """One service per oracle-resolvable hier:: class."""
    out = []
    for n, cls in enumerate(executable(c)):
        cols = c.columns.get(cls, {})
        table = c.tables.get(c.main_table.get(cls, ""))
        if table is None or not table.pk:
            continue
        ident = next((p for p, col in cols.items() if col == table.pk[0]), None)
        if ident is None:
            continue

        usable = resolvable(c, cls)
        dyn = [p for p in usable if (cls, p) in c.dyna]
        # A dyna-over-chain is in BOTH maps by construction, so it must be counted once --
        # projecting it twice is rejected with "The relation contains duplicates".
        chain = [p for p in usable if (cls, p) in c.chains and p not in dyn]
        plain = [p for p in usable if p != ident and p not in dyn and p not in chain]
        short = cls.split("::")[-1]

        note = (f"{len(dyn)} of the projected columns are DYNAFUNCTIONS "
                f"({', '.join(c.dyna[(cls, p)][0] for p in dyn)}); the oracle evaluates "
                f"each independently of the engine." if dyn else
                "Plain columns over the inheritance hierarchy's root set.")

        # Named from the CLASS, not from a loop index. Index-based names renumber every
        # service the moment the class list changes -- which silently invalidated the
        # quarantine entry for the cross-database chain, since it keys on the name.
        spec = Spec(f"stress::H_{short}", f"/stress/h_{short.lower()}",
                    f"Executes the hier:: feature mapping for {cls}. {note} "
                    f"Generated by scripts/corpus/hier.py -- the class list is derived from "
                    f"what the ORACLE can resolve, so a property the reader cannot model "
                    f"(a join chain, a Binding transformer) excludes its class automatically "
                    f"rather than being remembered in a list here.", cls)
        spec.projections = ([Proj(ident, [ident])]
                            + [Proj(p.replace(".", "_"), p.split("."))
                               for p in plain + dyn + chain])
        spec.sort = (ident, False)
        # Route by the mapping that DECLARED the class, not by its table's database -- a
        # mapping may span stores, and hier::InstrumentReach is declared in IssuerMapping
        # with a main table in HierDB.
        mapping = c.declared_in.get(cls)
        binding = next((b for b in BINDINGS.values() if b[0] == mapping), None)
        if binding is None:
            continue
        _m, runtime, conn, data = binding
        spec.mapping = mapping
        spec.runtime = runtime
        spec.connection = conn
        spec.data_element = data
        # Test data is bound to a CONNECTION, so a cross-store query needs every store's
        # element listed, not just its own.
        spec.extra_data = [(cid, dt) for _db, (_m, _r, cid, dt) in sorted(BINDINGS.items())
                           if cid != conn]
        out.append(spec)

        # Separate services for the Binding-backed leaves, so F27 isolates to the half it
        # affects. `Bool` carries the boolean leaves and PASSES -- it is the only evidence
        # that a Binding transformer works at all -- while `Binding` carries the rest and is
        # quarantined.
        for boolean, suffix, note in (
                (False, "Binding",
                 "Every non-boolean leaf comes back as its raw JSON TOKEN rendered as a "
                 "string (F27): a String arrives with its quotes, an Integer as \"7\" "
                 "rather than 7. Quarantined."),
                (True, "BindingBool",
                 "The BOOLEAN leaves, which the engine reads back correctly -- a boolean's "
                 "JSON token is already a boolean literal. Kept apart from the leaves F27 "
                 "affects so the feature has a passing demonstration rather than only a "
                 "pinned defect.")):
            bpaths = binding_paths(c, cls, boolean)
            if not bpaths:
                continue
            b = Spec(f"stress::H_{short}{suffix}",
                     f"/stress/h_{short.lower()}_{suffix.lower()}",
                     f"Binding transformer over a JSON column on {cls}. {note}", cls)
            b.projections = ([Proj(ident, [ident])]
                             + [Proj(p.replace(".", "_"), p.split(".")) for p in bpaths])
            b.sort = (ident, False)
            b.mapping, b.runtime = spec.mapping, spec.runtime
            b.connection, b.data_element = spec.connection, spec.data_element
            b.extra_data = spec.extra_data
            out.append(b)
    return out


if __name__ == "__main__":
    import flat

    c = model.load()
    tables = flat.all_tables(c)
    print("oracle-resolvable hier:: classes:", executable(c))
    print("blocked (reader cannot model a property):",
          sorted({cls for cls, _ in c.unparsed}))
    for s in specs(c):
        d = sum(1 for p in s.projections if (s.root, p.alias) in c.dyna)
        print(f"  {s.name.split('::')[-1]:<24}{len(s.projections)} cols, {d} dynafunction(s)")
