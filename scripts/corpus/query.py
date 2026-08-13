"""
Reads ###Service elements into query specs the oracle can evaluate.

Only the fragment of the language the stress services actually use is parsed:

    <root>.all()
      [ ->filter({v| <pred>}) ]
      ->project(~[ <col> | <alias>:v|$v.<path> , ... ])
      [ ->sort(~<alias>-><asc|desc>ending()) ]
      [ ->limit(<n>) ]

  <pred> := $v.<path> <op> <literal> [ && <pred> ]     op in == != < <= > >=

Deliberately narrow. A parser that accepted more would let a query into the corpus that
the oracle cannot predict, and the expectation would silently become whatever the engine
produced — which is the failure mode this whole exercise exists to remove. Anything
unrecognised raises.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

from model import STRESS


@dataclass
class Pred:
    path: list[str]
    op: str
    value: object


@dataclass
class Proj:
    alias: str
    path: list[str]
    agg: str | None = None      # None -> a column; 'count' -> over the landed SET
    args: list = field(default_factory=list)   # arguments to a QUALIFIED property


@dataclass
class Spec:
    name: str            # stress::Q0_...
    pattern: str
    doc: str
    root: str            # trading::Trade
    projections: list[Proj] = field(default_factory=list)
    filters: list[Pred] = field(default_factory=list)
    sort: tuple[str, bool] | None = None    # (alias, descending)
    limit: int | None = None
    # L2 mapping invariance: the same query emitted against a second mapping/runtime.
    # None means the corpus default (stress::AllMapping / stress::RT).
    mapping: str | None = None
    runtime: str | None = None
    # The identifiedConnection id inside that runtime — what the testSuite's
    # `data: [ connections: [ <key>: ... ] ]` is matched against.
    connection: str | None = None

    @property
    def short(self) -> str:
        return self.name.split("::")[-1]


_SERVICE = re.compile(r"^Service\s+([\w:]+)\s*$")
_PATTERN = re.compile(r"pattern:\s*'([^']*)'")
_DOC = re.compile(r"documentation:\s*'((?:[^']|'')*)'")
_ROOT = re.compile(r"query:\s*\|([\w:]+)\.all\(\)")
_ALIASED = re.compile(r"^(\w+)\s*:\s*(\w+)\s*\|\s*\$\2\.(.+)$")
_AGGED = re.compile(r"^(\w+)\s*:\s*(\w+)\s*\|\s*\$\2\.(.+?)->(count)\(\)$")
_FILTER = re.compile(r"->filter\(\s*\{\s*(\w+)\s*\|(.*?)\}\s*\)", re.S)
_SORT = re.compile(r"->sort\(\s*~(\w+)->(\w+)\(\)\s*\)")
_LIMIT = re.compile(r"->limit\(\s*(\d+)\s*\)")
_COND = re.compile(r"\$(\w+)\.([\w.]+)\s*(==|!=|<=|>=|<|>)\s*(.+)")


def _literal(tok: str):
    tok = tok.strip()
    if tok.startswith("'") and tok.endswith("'"):
        return tok[1:-1]
    if tok in ("true", "false"):
        return tok == "true"
    if re.fullmatch(r"-?\d+", tok):
        return int(tok)
    if re.fullmatch(r"-?\d*\.\d+", tok):
        return float(tok)
    raise ValueError(f"unhandled literal {tok!r}")


def _projections(body: str) -> list[Proj]:
    """Split the ~[...] body on top-level commas and classify each entry."""
    out, depth, cur = [], 0, []
    for ch in body:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            out.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    if "".join(cur).strip():
        out.append("".join(cur))

    projs = []
    for raw in out:
        e = " ".join(raw.split())
        if not e:
            continue
        m = _AGGED.match(e)
        if m:
            projs.append(Proj(m.group(1), m.group(3).split("."), m.group(4)))
            continue
        m = _ALIASED.match(e)
        if m:
            path, args = m.group(3), []
            call = re.fullmatch(r"(.+?)\(([^)]*)\)", path)
            if call:
                path = call.group(1)
                args = [_literal(a) for a in call.group(2).split(",") if a.strip()]
            projs.append(Proj(m.group(1), path.split("."), None, args))
        elif re.fullmatch(r"\w+", e):
            projs.append(Proj(e, [e]))
        else:
            raise ValueError(f"unhandled projection entry {e!r}")
    return projs


def _project_body(q: str) -> str:
    """Extract the ~[ ... ] body of the project() call by bracket matching."""
    i = q.index("->project(")
    j = q.index("~[", i) + 2
    depth, k = 1, j
    while depth:
        if q[k] == "[":
            depth += 1
        elif q[k] == "]":
            depth -= 1
            if depth == 0:
                break
        k += 1
    return q[j:k]


def _preds(var: str, body: str) -> list[Pred]:
    out = []
    for part in body.split("&&"):
        part = part.strip().strip("()").strip()
        if not part:
            continue
        m = _COND.match(part)
        if not m:
            raise ValueError(f"unhandled filter condition {part!r}")
        if m.group(1) != var:
            raise ValueError(f"filter var {m.group(1)!r} != lambda var {var!r}")
        out.append(Pred(m.group(2).split("."), m.group(3), _literal(m.group(4))))
    return out


def parse(text: str) -> list[Spec]:
    specs, cur, buf = [], None, []
    for line in text.splitlines():
        m = _SERVICE.match(line)
        if m:
            if cur:
                specs.append(_finish(cur, "\n".join(buf)))
            cur, buf = m.group(1), []
            continue
        if cur:
            buf.append(line)
    if cur:
        specs.append(_finish(cur, "\n".join(buf)))
    return specs


def _finish(name: str, body: str) -> Spec:
    pat = _PATTERN.search(body)
    doc = _DOC.search(body)
    root = _ROOT.search(body)
    if not (pat and root):
        raise ValueError(f"service {name}: missing pattern or root")
    s = Spec(name, pat.group(1), doc.group(1) if doc else "", root.group(1))
    s.projections = _projections(_project_body(body))
    f = _FILTER.search(body)
    if f:
        s.filters = _preds(f.group(1), f.group(2))
    so = _SORT.search(body)
    if so:
        d = so.group(2)
        if d not in ("ascending", "descending"):
            raise ValueError(f"unhandled sort direction {d}")
        s.sort = (so.group(1), d == "descending")
    li = _LIMIT.search(body)
    if li:
        s.limit = int(li.group(1))
    if not s.projections:
        raise ValueError(f"service {name}: no projections")
    aliases = [p.alias for p in s.projections]
    if len(set(aliases)) != len(aliases):
        dupes = sorted({a for a in aliases if aliases.count(a) > 1})
        raise ValueError(f"service {name}: duplicate aliases {dupes}")
    if s.sort and s.sort[0] not in aliases:
        raise ValueError(f"service {name}: sorts on unprojected {s.sort[0]!r}")
    return s


def load() -> list[Spec]:
    return parse((STRESS / "92-services.pure").read_text())


if __name__ == "__main__":
    import model

    c = model.load()
    specs = load()
    print(f"{len(specs)} services\n")
    total = bad = fanout = 0
    for s in specs:
        errs = []
        for p in s.projections:
            total += 1
            try:
                if c.resolve_derived(s.root, p.path) is None:
                    c.resolve(s.root, p.path)
                if c.to_many_on(s.root, p.path):
                    fanout += 1
            except KeyError as e:
                errs.append(f"{p.alias}: {e}")
        for f in s.filters:
            try:
                c.resolve(s.root, f.path)
            except KeyError as e:
                errs.append(f"filter {'.'.join(f.path)}: {e}")
        bad += len(errs)
        flags = []
        if s.filters:
            flags.append(f"filter x{len(s.filters)}")
        if s.sort:
            flags.append("sort" + ("v" if s.sort[1] else "^"))
        if s.limit:
            flags.append(f"limit {s.limit}")
        print(f"  {s.short:<34} root={s.root:<28} cols={len(s.projections):>2} "
              f"{' '.join(flags):<22} {'OK' if not errs else 'UNRESOLVED ' + str(len(errs))}")
        for e in errs[:4]:
            print("       !", e)
    print(f"\n{total} projection paths, {bad} unresolved, {fanout} cross a to-many hop")
