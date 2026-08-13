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
    emitted = [(n, r) for n, r in tables.items() if n not in c.views]
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


def test_suite(spec: Spec, expected: list[dict], note: str) -> str:
    """The testSuites block for one service.

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
              {DATA_ELEMENT}
            }}#
        ]
      ]
      tests:
      [
        // {note}
        expected_rows:
        {{
          {'' if spec.graph else 'serializationFormat: PURE_TDSOBJECT;'}
          asserts:
          [
            matchesOracle:
              EqualToJson
              #{{
                expected:
                  ExternalFormat
                  #{{
                    contentType: 'application/json';
                    data: '{payload}';
                  }}#;
              }}#
          ]
        }}
      ]
    }}
  ]"""


VAR = "x"


def _literal(v) -> str:
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, str):
        return f"'{_pure_str(v)}'"
    return repr(v)


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
    arg = ""
    if spec.as_of == "latest":
        arg = "%latest"
    elif spec.as_of:
        arg = "%" + spec.as_of
    return (f"    query: |{spec.root}.all({arg})\n"
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
    arg = ""
    if spec.as_of == "latest":
        arg = "%latest"
    elif spec.as_of:
        arg = "%" + spec.as_of
    lines = [f"    query: |{spec.root}.all({arg})"]
    if spec.filters:
        conds = " && ".join(
            f"(${VAR}.{'.'.join(f.path)} {f.op} {_literal(f.value)})" for f in spec.filters)
        lines.append(f"        ->filter({{{VAR}|{conds}}})")
    cols = [f"{p.alias}:{VAR}|${VAR}.{'.'.join(p.path)}"
            + (f"({', '.join(_literal(a) for a in p.args)})" if p.args else "")
            + (f"->{p.agg}()" if p.agg else "")
            for p in spec.projections]
    lines.append("        ->project(~[")
    for i, col in enumerate(cols):
        lines.append(f"            {col}" + ("," if i < len(cols) - 1 else ""))
    lines.append("        ])")
    if spec.sort:
        alias, desc = spec.sort
        lines.append(f"        ->sort(~{alias}->{'descending' if desc else 'ascending'}())")
    if spec.limit is not None:
        lines.append(f"        ->limit({spec.limit})")
    return "\n".join(lines) + ";"


def service(spec: Spec, expected: list[dict], note: str) -> str:
    return f"""Service {spec.name}
{{
    pattern: '{spec.pattern}';
    documentation: '{_pure_str(spec.doc)}';
    execution: Single
    {{
{graph_text(spec) if spec.graph else query_text(spec)}
        mapping: {spec.mapping or MAPPING};
        runtime: {spec.runtime or RUNTIME};
    }}
{test_suite(spec, expected, note)}
}}"""
