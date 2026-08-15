"""
Emits the portable .pure artefacts: a ###Data element and the 12 services with testSuites.

Everything written here is plain Legend grammar. No part of it is specific to a runner, so
legend-engine executes it through TestableRunner and legend-lite can parse the identical
bytes — which is the portability claim, and parity.py checks it rather than asserting it.

Three details are load-bearing and were established from working examples in
legend-engine rather than guessed:

  * The connection key inside `data: [ connections: [ ... ] ]` is matched against the
    runtime's identifiedConnection **id** when the runtime uses the
    `connections: [ store: [ id: conn ] ]` shape. stress::RT does, and its id is
    `environment` — NOT the connection's element path `store::Conn`. Using the path
    silently seeds nothing and every test returns zero rows.
    (TestRuntimeBuilder.java)

  * `serializationFormat: PURE_TDSOBJECT;` produces `[{"col":val},...]`, which is the
    shape the oracle emits. The default (`DEFAULT`/`PURE`) instead produces
    `{"columns":[...],"rows":[{"values":[...]}]}`. Omitting the line is therefore a
    silent format mismatch, not an error. (TDSResult.getSerializer)

  * The classic `###Data` Relational form carries no store qualifier and no trailing
    semicolon after `}#`. The store-qualified form with `;` belongs to the newer
    `Relation #{...}#` grammar and does not parse here.

Also fixed here, because it is a real defect the old corpus could not see: all 12 services
declared `mapping: stress::RT`, pointing the mapping slot at a *Runtime*. Nothing
resolved that reference, so it survived. It becomes `stress::AllMapping`.
"""
from __future__ import annotations

import json

from model import Corpus
from query import Spec

DATA_ELEMENT = "stress::TestData"
EXTERNAL_DATA = "external::EntityData"
EXTERNAL_TABLES = {"EXT_LEGAL_ENTITY"}
# The hier:: feature domain has its own stores and therefore its own connection, so its
# tables cannot travel in the main ###Data element -- test data is keyed by CONNECTION.
# Derived from the model rather than listed, so a new hier table is routed correctly the
# moment it is declared instead of silently landing in the wrong element.
def hier_tables(c) -> set:
    return {n for n, t in c.tables.items()
            if getattr(t, "database", "").startswith("hier::") and n not in c.views}

CONNECTION_KEY = "environment"      # the identifiedConnection id in stress::RT
MAPPING = "stress::AllMapping"
RUNTIME = "stress::RT"
SCHEMA = "default"                  # tables are declared without a Schema block


def _pure_str(s: str) -> str:
    """Escape for a single-quoted Pure string literal."""
    return s.replace("\\", "\\\\").replace("'", "\\'")


def _csv_cell(v) -> str:
    if v is None:
        return ""                     # an empty CSV field is NULL — proven, not assumed
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, float):
        return repr(v)
    return str(v)


def data_element(c: Corpus, tables: dict[str, list[dict]]) -> str:
    """One ###Data element holding every seeded table.

    Every column DECLARED on the table is emitted, not merely those the seed populates,
    so a column the seed omits is unambiguously NULL rather than absent-and-unspecified.
    """
    out = ["###Data", f"Data {DATA_ELEMENT}", "{", "  Relational", "  #{"]
    emitted = [(n, r) for n, r in tables.items()
               if n not in c.views and n not in EXTERNAL_TABLES
               and n not in hier_tables(c)]
    for i, (name, rows) in enumerate(emitted):
        cols = list(c.tables[name].columns)
        lines = [",".join(cols)]
        lines += [",".join(_csv_cell(r.get(col)) for col in cols) for r in rows]
        out.append(f"    {SCHEMA}.{name}:")
        body = [f"      '{_pure_str(l)}\\n'" for l in lines]
        out.append(" +\n".join(body) + ";")
        if i < len(emitted) - 1:
            out.append("")
    out += ["  }#", "}"]
    return "\n".join(out)


def _tests(spec: Spec, payload: str) -> str:
    """One test per execution key, or a single unkeyed test."""
    fmt = "" if spec.graph else "          serializationFormat: PURE_TDSOBJECT;\n"
    keys = [k for k, _, _ in spec.multi] or [None]
    out = []
    for k in keys:
        keyline = f"          keys:\n          [\n            '{k}'\n          ];\n" if k else ""
        out.append(f"        {('expected_rows_' + k) if k else 'expected_rows'}:\n"
                   f"        {{\n{fmt}{keyline}          asserts:\n          [\n"
                   f"            matchesOracle:\n              EqualToJson\n"
                   f"              #{{\n                expected:\n"
                   f"                  ExternalFormat\n                  #{{\n"
                   f"                    contentType: 'application/json';\n"
                   f"                    data: '{payload}';\n                  }}#;\n"
                   f"              }}#\n          ]\n        }}")
    # Test elements are COMMA-separated. Without the comma the parser reports the
    # failure at `asserts` inside the FIRST test -- "Valid alternatives: [',', ']']" --
    # which points nowhere near the missing separator.
    return ",\n".join(out)


def hier_data_element(c: Corpus, tables: dict[str, list[dict]],
                      database: str, element: str) -> str:
    """A ###Data element for ONE hier:: store.

    One element per STORE, not one for the domain. Test data is bound to a connection and a
    runtime connects a single store here, so an element carrying another store's tables is
    rejected at session setup with `Table "X" not found in Schema "default" in Database(s) Y`
    -- an error that names the table rather than the packaging, and so reads as a missing
    table rather than a misrouted one.
    """
    out = ["###Data", f"Data {element}", "{", "  Relational", "  #{"]
    names = sorted(n for n in hier_tables(c)
                   if tables.get(n) and getattr(c.tables[n], "database", "") == database)
    for i, name in enumerate(names):
        cols = list(c.tables[name].columns)
        lines = [",".join(cols)]
        lines += [",".join(_csv_cell(r.get(col)) for col in cols) for r in tables[name]]
        body = "\\n' +\n      '".join(lines)
        out.append(f"    {SCHEMA}.{name}:")
        out.append(f"      '{body}\\n'" + (";" if i == len(names) - 1 else ";"))
    out += ["  }#", "}"]
    return "\n".join(out)


def external_data_element(c: Corpus, tables: dict[str, list[dict]]) -> str:
    """A SECOND ###Data element for the second store.

    Test data is keyed by CONNECTION, and the two stores have different connections, so
    one element cannot seed both — the tables of external::EntityDB have to travel
    separately or the cross-store side is simply empty.
    """
    out = ["###Data", f"Data {EXTERNAL_DATA}", "{", "  Relational", "  #{"]
    for name in sorted(EXTERNAL_TABLES):
        cols = list(c.tables[name].columns)
        lines = [",".join(cols)]
        lines += [",".join(_csv_cell(r.get(col)) for col in cols) for r in tables[name]]
        out.append(f"    {SCHEMA}.{name}:")
        out.append(" +\n".join(f"      '{_pure_str(l)}\\n'" for l in lines) + ";")
    out += ["  }#", "}"]
    return "\n".join(out)


def test_suite(spec: Spec, expected: list[dict], note: str) -> str:
    """The testSuites block.

    A graph-fetch result is not a bare array: `serialize()` wraps it as
    `{"builder":{"_type":"json"},"values":[...]}`. Determined by running one and reading
    the actual, not assumed — an expectation written as a bare array fails with the two
    sides looking identical for the first 300 characters.
    """
    payload = expected if spec.graph is None else {
        "builder": {"_type": "json"}, "values": expected}
    payload = _pure_str(json.dumps(payload, separators=(",", ":")))
    return f"""  testSuites:
  [
    {spec.short}_suite:
    {{
      data:
      [
        connections:
        [
          {spec.connection or CONNECTION_KEY}:
            Reference
            #{{
              {spec.data_element or DATA_ELEMENT}
            }}#{_extra_connections(spec)}
        ]
      ]
      tests:
      [
        // {note}
{_tests(spec, payload)}
      ]
    }}
  ]"""


def _extra_connections(spec: Spec) -> str:
    """Additional (connection id, data element) pairs — a cross-store service needs one
    per store, since test data is bound to a connection and not to a runtime."""
    return "".join(f",\n          {cid}:\n            Reference\n            #{{\n"
                   f"              {elem}\n            }}#"
                   for cid, elem in spec.extra_data)


VAR = "x"


def _literal(v) -> str:
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, str):
        return f"'{_pure_str(v)}'"
    return repr(v)


def _as_of(spec: Spec) -> str:
    """A temporal root takes one date, or TWO for a bitemporal class: all(%B, %P)."""
    if spec.as_of is None:
        return ""
    dates = spec.as_of if isinstance(spec.as_of, list) else [spec.as_of]
    return ", ".join("%latest" if d == "latest" else "%" + d for d in dates)


def _tree(node: dict, indent: str) -> str:
    parts = []
    for name, sub in node.items():
        if sub is None:
            parts.append(f"{indent}{name}")
        else:
            parts.append(f"{indent}{name}\n{indent}{{\n"
                         + _tree(sub, indent + "  ") + f"\n{indent}}}")
    return ",\n".join(parts)


def graph_text(spec: Spec) -> str:
    """`->graphFetch(#{...}#)->serialize(#{...}#)` — the tree is written twice, and the
    two must agree or the serializer emits a shape the fetch never populated."""
    tree = ("#{\n  " + spec.root + "\n  {\n" + _tree(spec.graph, "    ")
            + "\n  }\n}#")
    return (f"    query: |{spec.root}.all({_as_of(spec)})\n"
            f"        ->graphFetch({tree})\n"
            f"        ->serialize({tree});")


def query_text(spec: Spec) -> str:
    """Render the query from the parsed spec.

    The queries are regenerated rather than preserved because the originals were not
    valid Legend. Every one projected bare column names — `~[tradeId, instrName:t|...]` —
    against a Class source. A Class has no columns, so the bare form only means anything
    over a Relation. legend-engine rejects an all-bare array with a clean "Can't find a
    match for function 'project(Trade[*],ColSpecArray...)'", and crashes on the MIXED
    array with a raw ClassCastException out of processColSpecArray, which inspects only
    the first element to choose the array kind and then casts the rest to it.

    The canonical form is a FuncColSpec per column, `alias:x|$x.path`, which is what the
    bare names always meant here. Column aliases are unchanged, so the expectation JSON
    keys are identical.
    """
    lines = [f"    query: |{spec.root}.all({_as_of(spec)})"]
    if spec.filters:
        conds = " && ".join(
            f"(${VAR}.{'.'.join(f.path)} {f.op} {_literal(f.value)})" for f in spec.filters)
        lines.append(f"        ->filter({{{VAR}|{conds}}})")
    def expr(p):
        if p.func:
            inner = ", ".join(_literal(a) for a in p.args)
            return f"${VAR}->{p.func}({inner})"
        return (f"${VAR}.{'.'.join(p.path)}"
                + (f"({', '.join(_literal(a) for a in p.args)})" if p.args else "")
                + (f"->{p.agg}()" if p.agg else ""))

    if spec.paradigm == "tds" and spec.group_by:
        # A legacy TDS groupBy operates on OBJECTS, straight off all() -- there is no
        # project() in front of it. Projecting first yields a TDS and the groupBy then
        # indexes past the end of it ("Index: 3 Size: 2"). This is also the only shape the
        # AggregationAware rewrite recognises.
        keys = ", ".join(f"{VAR}|${VAR}.{k}" for k in spec.group_by)
        aggs = ", ".join(f"agg({VAR}|${VAR}.{src}, y|$y->{fn}())"
                         for _, src, fn in spec.aggs)
        names = ", ".join(f"'{k}'" for k in spec.group_by)
        names += ", " + ", ".join(f"'{n}'" for n, _, _ in spec.aggs)
        lines.append(f"        ->groupBy(\n            [{keys}],\n"
                     f"            [{aggs}],\n            [{names}]\n        )")
    elif spec.paradigm == "tds":
        # Legacy TDS: the lambdas and the column names travel in two parallel lists.
        lams = [f"{VAR}|{expr(p)}" for p in spec.projections]
        names = [f"'{_pure_str(p.alias)}'" for p in spec.projections]
        lines.append("        ->project(")
        lines.append("            [")
        for i, l in enumerate(lams):
            lines.append(f"                {l}" + ("," if i < len(lams) - 1 else ""))
        lines.append("            ],")
        lines.append("            [" + ", ".join(names) + "]")
        lines.append("        )")
    else:
        cols = [f"{p.alias}:{VAR}|{expr(p)}" for p in spec.projections]
        lines.append("        ->project(~[")
        for i, col in enumerate(cols):
            lines.append(f"            {col}" + ("," if i < len(cols) - 1 else ""))
        lines.append("        ])")
    if spec.group_by and spec.paradigm != "tds":
        keys = ", ".join(spec.group_by)
        # The aggregate list is BRACKETED. The bare `~name: ... : ...` form parses only
        # with a SINGLE aggregate; with two it builds a colSpec whose function is null and
        # fails with "colSpec.function1 is null".
        parts = ",\n            ".join(
            f"{name}: {VAR}|${VAR}.{src} : agg|$agg->{fn}()"
            for name, src, fn in spec.aggs)
        lines.append(f"        ->groupBy(~[{keys}], ~[\n            {parts}\n        ])")
    if spec.sort:
        alias, desc = spec.sort
        lines.append(f"        ->sort(~{alias}->{'descending' if desc else 'ascending'}())")
    if spec.limit is not None:
        lines.append(f"        ->limit({spec.limit})")
    return "\n".join(lines) + ";"


def _multi_execution(spec: Spec) -> str:
    """`execution: Multi` — one query, several (mapping, runtime) bindings chosen by key.

    Expressing an invariance THIS way is stronger than two services: there is literally
    one query text, so the two runs cannot drift apart by editing.
    """
    body = [graph_text(spec) if spec.graph else query_text(spec),
            f"        key: '{spec.multi_key}';"]
    for key, mapping, runtime in spec.multi:
        body.append(f"        executions['{key}']:\n        {{\n"
                    f"            mapping: {mapping};\n"
                    f"            runtime: {runtime};\n        }}")
    return "\n".join(body)


def service(spec: Spec, expected: list[dict], note: str) -> str:
    if spec.multi:
        exec_block = f"    execution: Multi\n    {{\n{_multi_execution(spec)}\n    }}"
    else:
        exec_block = (f"    execution: Single\n    {{\n"
                      f"{graph_text(spec) if spec.graph else query_text(spec)}\n"
                      f"        mapping: {spec.mapping or MAPPING};\n"
                      f"        runtime: {spec.runtime or RUNTIME};\n    }}")
    return f"""Service {spec.name}
{{
    pattern: '{spec.pattern}';
    documentation: '{_pure_str(spec.doc)}';
{exec_block}
{test_suite(spec, expected, note)}
}}"""
