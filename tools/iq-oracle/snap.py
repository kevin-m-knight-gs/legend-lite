"""The #IQ{...}# island's oracle: SQL Anywhere 17's answers for the iq-parity cases, snapped (README.md).

  snap.sh --write [--only GLOB]   run the cases, write core/src/test/resources/iq-parity/iq-answers.jsonl
  snap.sh --check [--only GLOB]   run the cases, report DRIFT, UNSNAPPED, STALE and GONE; exit 1 on any

Run it through snap.sh: SQL Anywhere's client library (dbcapi) is x86_64 only, so this runs on an x86_64 Python
(Rosetta on Apple silicon) with SQL Anywhere's environment sourced. Standard library only.

Each run starts a private server (iq_oracle_<pid>, on 127.0.0.1 at a free port, every secured feature off) over two
databases made afresh in a temporary directory, seeds both from schema.yaml, runs each case's SELECT under its
profile's options, and stops the server and deletes the directory at exit. It never connects to any other server.
"""

import argparse
import atexit
import ctypes as C
import fnmatch
import hashlib
import json
import math
import os
import re
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import time

# ------------------------------------------------------------------ where things are

HERE = os.path.dirname(os.path.abspath(__file__))
PARITY = os.path.join("core", "src", "test", "resources", "iq-parity")
CRASHERS = os.path.join("tools", "iq-oracle", "known_crashers.txt")

# ------------------------------------------------------------------ the databases, the server and the profiles

PASSWORD = "sqlsql"  # the private databases' DBA password; nothing else ever connects to them

# name -> (dbinit flags, PUBLIC options set once after creation, the collation and charset it must report)
DATABASES = {
    # the local stand-in for an IQ catalog store: case-sensitive, blank-padded, binary Latin-1 collation, and IQ's
    # numeric defaults for an unqualified NUMERIC
    "lite_iq": (["-c", "-b", "-z", "ISO_BINENG"], [("precision", "126"), ("scale", "38")],
                {"Collation": "ISO_BINENG", "CharSet": "ISO_8859-1:1987", "CaseSensitive": "On", "BlankPadding": "On"}),
    # a SQL Anywhere 17 database at dbinit's defaults (snap.sh pins the locale, which picks the default collation)
    "lite_sa": ([], [],
                {"Collation": "UTF8BIN", "CharSet": "UTF-8", "CaseSensitive": "Off", "BlankPadding": "Off"}),
}
DB_PROPERTIES = ["Collation", "CharSet", "CaseSensitive", "BlankPadding", "NcharCollation", "NcharCharSet"]
CODECS = {"ISO_8859-1:1987": "latin-1", "UTF-8": "utf-8"}

# TCP on the loopback address only, at a free port (a client on shared memory waits forever on a server that died, so
# a crash could not be told from a slow query), no answer to broadcasts, and no file, shell, mail, web or external call
SERVER_FLAGS = ["-x", "tcpip(MyIP=127.0.0.1;Port={port})", "-sb", "0", "-sf", "all"]
# dbinit's default collation follows the locale (C gives ISO1LATIN1, a UTF-8 locale UTF8BIN): pinned for both tools
LOCALE = "en_US.UTF-8"

# the options a TDS login sets (sp_tsql_environment, run by sp_login_environment for a TDS connection)
TDS = [
    ("ansinull", "Off"), ("tsql_variables", "On"), ("ansi_blanks", "On"), ("chained", "Off"),
    ("quoted_identifier", "Off"), ("allow_nulls_by_default", "Off"), ("on_tsql_error", "Continue"),
    ("isolation_level", "1"), ("date_format", "YYYY-MM-DD"), ("timestamp_format", "YYYY-MM-DD HH:NN:SS.SSS"),
    ("time_format", "HH:NN:SS.SSS"), ("date_order", "MDY"), ("escape_character", "Off"),
]
# the options the IQ ODBC driver sets at connect (the private spec's OPTIONS.md section 5); the isolation level it
# also sets is the application's own choice, so it stays at the database's default here
ODBC = [
    ("date_order", "ymd"), ("date_format", "yyyy-mm-dd"), ("time_format", "hh:nn:ss"),
    ("timestamp_format", "yyyy-mm-dd hh:nn:ss.ssssss"),
    ("timestamp_with_time_zone_format", "yyyy-mm-dd hh:nn:ss.ssssss +hh:nn"),
]
PROFILES = {
    "iq16-default": ("lite_iq", []),
    "iq16-odbc": ("lite_iq", ODBC),
    "iq16-tds": ("lite_iq", TDS),
    "sqla17-default": ("lite_sa", []),
    "sqla17-tds": ("lite_sa", TDS),
}
DEFAULT_PROFILE = "iq16-default"
ENGINES = {"iq", "sa", "auto"}
CASE_KEYS = {"id", "sql", "profile", "engine", "skip", "sa_defect", "decision", "expect"}

REQUEST_TIMEOUT = "15"  # seconds, per statement, on every case connection
TS6 = "YYYY-MM-DD HH:NN:SS.SSSSSS"  # how a TIMESTAMP column's cells are snapped, whatever the profile's format
MAX_ROWS = 10000

# ------------------------------------------------------------------ the YAML subset the harness reads
# The same rules as SqlParity.Yaml (core/src/test/java/com/legend/testcases/SqlParity.java), so the Java harness and
# this tool read every file alike: a `tests:` list of one-line `key: scalar` maps; schema.yaml's tables with one-line
# flow maps for columns and flow lists for rows; plain, 'single' and "double" quoted scalars.

_CASE_START = re.compile(r"(\s*)- ([a-z_]+):\s?(.*)")
_KEY = re.compile(r"(\s*)([a-z_]+):\s?(.*)")
_INT = re.compile(r"[-+]?[0-9]+")
_DEC = re.compile(r"[-+]?([0-9]+\.[0-9]*|\.[0-9]+)([eE][-+]?[0-9]+)?")


class Bad(Exception):
    """A file or a setup this tool will not guess about."""


def yaml_tests(text, where):
    tests, current = [], None
    for n, raw in enumerate(text.split("\n"), 1):
        line = raw.replace("\r", "")
        if not line.strip() or line.lstrip().startswith("#") or line in ("tests:", "tests: []"):
            continue
        m = _CASE_START.fullmatch(line)
        if m:
            current = {}
            tests.append(current)
            current[m.group(2)] = scalar(m.group(3), where, n)
            continue
        m = _KEY.fullmatch(line)
        if m and current is not None and m.group(1):
            if m.group(2) in current:
                raise Bad("%s:%d: the key %s twice in one case" % (where, n, m.group(2)))
            current[m.group(2)] = scalar(m.group(3), where, n)
            continue
        raise Bad("%s:%d: not the YAML this reader reads: %s" % (where, n, line))
    return tests


def yaml_schema(text, where):
    tables, table, section = [], None, ""
    for n, raw in enumerate(text.split("\n"), 1):
        s = raw.replace("\r", "").strip()
        if not s or s.startswith("#") or s in ("schema:", "tables:"):
            continue
        if s.startswith("- name:"):
            table = {"name": scalar(s[len("- name:"):].strip(), where, n), "columns": [], "rows": []}
            tables.append(table)
            section = ""
        elif s in ("columns:", "rows:"):
            section = s
        elif s == "rows: []":
            section = ""
        elif s.startswith("- {") and section == "columns:" and table is not None:
            col = {}
            for item in _flow_items(s[2:], "{", "}", where, n):
                colon = item.index(":")
                col[item[:colon].strip()] = scalar(item[colon + 1:].strip(), where, n)
            table["columns"].append(col)
        elif s.startswith("- [") and section == "rows:" and table is not None:
            table["rows"].append([scalar(i.strip(), where, n) for i in _flow_items(s[2:], "[", "]", where, n)])
        else:
            raise Bad("%s:%d: not the YAML this reader reads: %s" % (where, n, s))
    return tables


def _flow_items(s, open_, close, where, n):
    if s[0] != open_ or s[-1] != close:
        raise Bad("%s:%d: not a one-line flow collection: %s" % (where, n, s))
    items, item, quote, i = [], [], None, 1
    while i < len(s) - 1:
        c = s[i]
        if quote:
            item.append(c)
            if c == quote:
                if quote == "'" and i + 1 < len(s) - 1 and s[i + 1] == "'":
                    i += 1
                    item.append(s[i])
                elif quote == '"' and s[i - 1] == "\\":
                    pass
                else:
                    quote = None
        elif c in "\"'":
            quote = c
            item.append(c)
        elif c == ",":
            items.append("".join(item))
            item = []
        else:
            item.append(c)
        i += 1
    if "".join(item).strip():
        items.append("".join(item))
    return items


def scalar(s, where, n):
    if s.startswith('"'):
        return _double_quoted(s, where, n)
    if s.startswith("'"):
        end = s.rfind("'")
        rest = s[end + 1:].strip()
        if end <= 0 or (rest and not rest.startswith("#")):
            raise Bad("%s:%d: an unterminated quoted scalar: %s" % (where, n, s))
        return s[1:end].replace("''", "'")
    plain = s
    if " #" in plain:
        plain = plain[:plain.index(" #")]
    plain = plain.strip()
    if plain in ("", "null", "~"):
        return None
    if plain in ("true", "false"):
        return plain == "true"
    if _INT.fullmatch(plain):
        return int(plain)
    if _DEC.fullmatch(plain):
        return float(plain)
    return plain


_ESCAPES = {"\\": "\\", '"': '"', "/": "/", "n": "\n", "t": "\t", "r": "\r", "0": "\0"}


def _double_quoted(s, where, n):
    out, i = [], 1
    while i < len(s):
        c = s[i]
        if c == '"':
            rest = s[i + 1:].strip()
            if rest and not rest.startswith("#"):
                raise Bad("%s:%d: text after a quoted scalar: %s" % (where, n, s))
            return "".join(out)
        if c == "\\":
            e = s[i + 1] if i + 1 < len(s) else ""
            if e in _ESCAPES:
                out.append(_ESCAPES[e])
            elif e == "u":
                out.append(chr(int(s[i + 2:i + 6], 16)))
                i += 4
            else:
                raise Bad("%s:%d: an escape this reader does not know: \\%s" % (where, n, e))
            i += 2
            continue
        out.append(c)
        i += 1
    raise Bad("%s:%d: an unterminated quoted scalar: %s" % (where, n, s))


# ------------------------------------------------------------------ the schema and the cases

_TYPE = re.compile(r"(smallint|integer|int|bigint|tinyint|double|real|float|date|timestamp|bit"
                   r"|(numeric|decimal)\(\d+,\s*\d+\)|(char|varchar)\(\d+\))", re.I)
_NAME = re.compile(r"[a-z][a-z0-9_]*")
_ID = re.compile(r"[A-Za-z0-9_.-]+")


def read_schema(path):
    with open(path, "rb") as f:
        raw = f.read()
    tables = yaml_schema(raw.decode("utf-8"), "schema.yaml")
    if not tables:
        raise Bad("schema.yaml: no tables")
    seen = set()
    for t in tables:
        if not isinstance(t["name"], str) or not _NAME.fullmatch(t["name"]) or t["name"] in seen:
            raise Bad("schema.yaml: a table name must be a new lower-case identifier: %r" % (t["name"],))
        seen.add(t["name"])
        names = [c.get("name") for c in t["columns"]]
        for c in t["columns"]:
            if set(c) != {"name", "type"} or not isinstance(c["name"], str) or not _NAME.fullmatch(c["name"]) \
                    or not isinstance(c["type"], str) or not _TYPE.fullmatch(c["type"]):
                raise Bad("schema.yaml: table %s: a column is {name: lower-case identifier, type: an IQ type}: %r"
                          % (t["name"], c))
        if not names or len(set(names)) != len(names):
            raise Bad("schema.yaml: table %s: no columns, or one named twice" % t["name"])
        for r in t["rows"]:
            if len(r) != len(names):
                raise Bad("schema.yaml: table %s: the row %r has %d cells for %d columns"
                          % (t["name"], r, len(r), len(names)))
            for c, v in zip(t["columns"], r):
                literal(c["type"], v, t["name"])  # every cell must be a value of its column's type
    return raw, tables


def literal(sql_type, v, table):
    """A cell of schema.yaml as an SQL literal for an INSERT: integers and bits plain, every other value quoted text
    that the INSERT converts to the column's type (so a decimal or a double keeps exactly the digits written)."""
    if v is None:
        return "NULL"
    base = sql_type.split("(")[0].lower()
    if isinstance(v, bool) or isinstance(v, float):
        raise Bad("schema.yaml: table %s: %r: write booleans as 0/1 and non-integers as quoted text" % (table, v))
    if base in ("smallint", "integer", "int", "bigint", "tinyint", "bit"):
        if not isinstance(v, int):
            raise Bad("schema.yaml: table %s: %r is not an integer for %s" % (table, v, sql_type))
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"


def read_cases(lite_dir):
    files = []
    for root, _, names in os.walk(lite_dir):
        files += [os.path.join(root, f) for f in names if f.endswith(".yaml")]
    files.sort(key=lambda p: os.path.relpath(p, lite_dir).replace(os.sep, "/"))
    cases, keys = [], set()
    for path in files:
        rel = "lite/" + os.path.relpath(path, lite_dir).replace(os.sep, "/")
        with open(path, encoding="utf-8") as f:
            tests = yaml_tests(f.read(), rel)
        for m in tests:
            where = "%s: case %r" % (rel, m.get("id"))
            unknown = set(m) - CASE_KEYS
            if unknown:
                raise Bad("%s: unknown keys %s (known: %s)" % (where, sorted(unknown), sorted(CASE_KEYS)))
            if not isinstance(m.get("id"), str) or not _ID.fullmatch(m["id"]) or not isinstance(m.get("sql"), str):
                raise Bad("%s: a case needs an id ([A-Za-z0-9_.-]) and its sql as text" % where)
            key = rel[:-len(".yaml")] + "/" + m["id"]
            if key in keys:
                raise Bad("%s: the id twice" % where)
            keys.add(key)
            profiles = [p.strip() for p in str(m.get("profile") or DEFAULT_PROFILE).split(",")]
            for p in profiles:
                if p not in PROFILES:
                    raise Bad("%s: unknown profile %r (known: %s)" % (where, p, ", ".join(PROFILES)))
            if len(set(profiles)) != len(profiles):
                raise Bad("%s: a profile named twice" % where)
            if m.get("engine") is not None and m["engine"] not in ENGINES:
                raise Bad("%s: engine is one of %s" % (where, sorted(ENGINES)))
            if m.get("decision") is not None and not re.fullmatch(r"D-[A-Z]+[0-9]+[a-z]?", str(m["decision"])):
                raise Bad("%s: decision names one decision, D-<letters><number>" % where)
            if m.get("expect") is not None:
                check_expect(m["expect"], where)
            skip = m.get("skip") or (("sa_defect: " + str(m["sa_defect"])) if m.get("sa_defect") else None)
            cases.append({"key": key, "id": m["id"], "sql": m["sql"], "profiles": profiles,
                          "skip": None if skip is None else str(skip)})
    return cases


def check_expect(expect, where):
    try:
        rows = json.loads(expect) if isinstance(expect, str) else None
    except ValueError:
        rows = None
    if not isinstance(rows, list) or not all(isinstance(r, list) and all(c is None or isinstance(c, str) for c in r)
                                             for r in rows):
        raise Bad("%s: expect is one line of JSON, a list of rows of text cells or null: %r" % (where, expect))


def single_select(sql):
    """Only a single SELECT or WITH (or a parenthesised one) ever runs, and nothing with a statement separator."""
    s = sql
    while True:
        s = s.lstrip()
        if s.startswith("--") or s.startswith("//"):
            s = s[s.index("\n") + 1:] if "\n" in s else ""
        elif s.startswith("/*"):
            s = s[s.index("*/") + 2:] if "*/" in s else ""
        else:
            break
    return ";" not in sql and re.match(r"(SELECT|WITH)\b|\(", s, re.I) is not None


def sql_sha(sql, profile, schema_bytes):
    return hashlib.sha256(sql.encode("utf-8") + b"\0" + profile.encode("utf-8") + b"\0" + schema_bytes).hexdigest()


def known_crashers(path):
    if not os.path.exists(path):
        return set()
    with open(path, encoding="utf-8") as f:
        return {l.split("#")[0].strip() for l in f if l.split("#")[0].strip()}


# ------------------------------------------------------------------ dbcapi, through ctypes (API version 2)

class DataValue(C.Structure):
    _fields_ = [("buffer", C.POINTER(C.c_char)), ("buffer_size", C.c_size_t),
                ("length", C.POINTER(C.c_size_t)), ("type", C.c_int), ("is_null", C.POINTER(C.c_int))]


class ColumnInfo(C.Structure):
    _fields_ = [("name", C.c_char_p), ("type", C.c_int), ("native_type", C.c_int),
                ("precision", C.c_ushort), ("scale", C.c_ushort), ("max_size", C.c_size_t), ("nullable", C.c_int)]


_SIGNATURES = [
    ("sqlany_init", C.c_int, [C.c_char_p, C.c_uint, C.POINTER(C.c_uint)]),
    ("sqlany_new_connection", C.c_void_p, []),
    ("sqlany_free_connection", None, [C.c_void_p]),
    ("sqlany_connect", C.c_int, [C.c_void_p, C.c_char_p]),
    ("sqlany_disconnect", C.c_int, [C.c_void_p]),
    ("sqlany_execute_direct", C.c_void_p, [C.c_void_p, C.c_char_p]),
    ("sqlany_execute_immediate", C.c_int, [C.c_void_p, C.c_char_p]),
    ("sqlany_error", C.c_int, [C.c_void_p, C.c_char_p, C.c_size_t]),
    ("sqlany_sqlstate", C.c_size_t, [C.c_void_p, C.c_char_p, C.c_size_t]),
    ("sqlany_num_cols", C.c_int, [C.c_void_p]),
    ("sqlany_fetch_next", C.c_int, [C.c_void_p]),
    ("sqlany_get_column", C.c_int, [C.c_void_p, C.c_uint, C.POINTER(DataValue)]),
    ("sqlany_get_column_info", C.c_int, [C.c_void_p, C.c_uint, C.POINTER(ColumnInfo)]),
    ("sqlany_free_stmt", None, [C.c_void_p]),
]

NATIVE = {384: "date", 388: "time", 392: "timestamp", 448: "varchar", 452: "char", 456: "long varchar",
          460: "string", 480: "double", 482: "real", 484: "numeric", 496: "integer", 500: "smallint",
          524: "binary", 528: "long binary", 604: "tinyint", 608: "bigint", 612: "unsigned int",
          616: "unsigned smallint", 620: "unsigned bigint", 624: "bit", 628: "nstring", 632: "nchar",
          636: "nvarchar", 640: "long nvarchar"}
INT_FORMATS = {4: "<q", 5: "<Q", 6: "<i", 7: "<I", 8: "<h", 9: "<H", 10: "<b", 11: "<B"}
# the SQLCODEs that mean the connection or the server is gone: the server crashed on the statement
LOST = {-101, -308, -85, -100, -832, -1068}


class Lost(Exception):
    """The server went away while running a statement."""


class Dbcapi:
    def __init__(self, sqlany17):
        self.api = C.CDLL(os.path.join(sqlany17, "lib64", "libdbcapi_r.dylib"))
        for name, res, args in _SIGNATURES:
            f = getattr(self.api, name)
            f.restype, f.argtypes = res, args
        version = C.c_uint()
        if not self.api.sqlany_init(b"iq_oracle", 2, C.byref(version)):
            raise Bad("sqlany_init refused API version 2")


class Connection:
    def __init__(self, dbcapi, connstr, codec):
        self.api, self.codec = dbcapi.api, codec
        self.conn = self.api.sqlany_new_connection()
        if not self.api.sqlany_connect(self.conn, connstr.encode("ascii")):
            e = self.error()
            self.api.sqlany_free_connection(self.conn)
            self.conn = None
            raise ConnectionError(e["message"])

    def close(self):
        if self.conn:
            self.api.sqlany_disconnect(self.conn)
            self.api.sqlany_free_connection(self.conn)
            self.conn = None

    def error(self):
        buf, st = C.create_string_buffer(1024), C.create_string_buffer(8)
        code = self.api.sqlany_error(self.conn, buf, len(buf))
        self.api.sqlany_sqlstate(self.conn, st, len(st))
        return {"sqlcode": code, "sqlstate": st.value.decode("ascii", "replace"),
                "message": buf.value.decode(self.codec, "replace")}

    def execute(self, sql):
        """A statement with no result set; raises on an error."""
        if not self.api.sqlany_execute_immediate(self.conn, sql.encode(self.codec)):
            e = self.error()
            if e["sqlcode"] in LOST:
                raise Lost(e["message"])
            raise Bad("%s: %s (SQLCODE %d)" % (sql, e["message"], e["sqlcode"]))

    def value(self, sql):
        r = self.query(sql)
        if "error" in r or len(r["rows"]) != 1:
            raise Bad("%s: %r" % (sql, r))
        return r["rows"][0][0]

    def query(self, sql):
        """{columns, rows} with every cell as text (None for NULL), or {error}."""
        stmt = self.api.sqlany_execute_direct(self.conn, sql.encode(self.codec))
        if not stmt:
            return self._failed()
        try:
            columns = []
            for i in range(self.api.sqlany_num_cols(stmt)):
                ci = ColumnInfo()
                if not self.api.sqlany_get_column_info(stmt, i, C.byref(ci)):
                    raise Bad("sqlany_get_column_info failed for column %d of %s" % (i, sql))
                columns.append([ci.name.decode(self.codec) if ci.name else "",
                                NATIVE.get(ci.native_type & ~1, "native_%d" % ci.native_type), ci.precision, ci.scale])
            rows = []
            while True:
                if not self.api.sqlany_fetch_next(stmt):
                    e = self.error()
                    if e["sqlcode"] not in (0, 100):
                        return self._failed(e)
                    break
                if len(rows) == MAX_ROWS:
                    raise Bad("more than %d rows: %s" % (MAX_ROWS, sql))
                rows.append([self._cell(stmt, i) for i in range(len(columns))])
            return {"columns": columns, "rows": rows}
        finally:
            self.api.sqlany_free_stmt(stmt)

    def _failed(self, e=None):
        e = e or self.error()
        if e["sqlcode"] in LOST:
            raise Lost(e["message"])
        return {"error": e}

    def _cell(self, stmt, i):
        v = DataValue()
        if not self.api.sqlany_get_column(stmt, i, C.byref(v)):
            e = self.error()
            if e["sqlcode"] in LOST:
                raise Lost(e["message"])
            raise Bad("sqlany_get_column failed: %r" % e)
        if v.is_null and v.is_null[0]:
            return None
        raw = C.string_at(v.buffer, v.length[0] if v.length else 0)
        if v.type == 1:  # A_BINARY
            return "0x" + raw.hex()
        if v.type == 2:  # A_STRING: numerics, dates, timestamps and strings, as SQL Anywhere spells them
            return raw.decode(self.codec)
        if v.type == 3:  # A_DOUBLE
            return double_text(struct.unpack("<d", raw[:8])[0])
        if v.type == 12:  # A_FLOAT
            return float_text(struct.unpack("<f", raw[:4])[0])
        if v.type in INT_FORMATS:
            fmt = INT_FORMATS[v.type]
            return str(struct.unpack(fmt, raw[:struct.calcsize(fmt)])[0])
        raise Bad("a cell of dbcapi type %d" % v.type)


def double_text(x):
    """The shortest text that reads back as the same double (Python's repr), with Java's names for the non-finite."""
    if math.isnan(x):
        return "NaN"
    if math.isinf(x):
        return "Infinity" if x > 0 else "-Infinity"
    return repr(x)


def float_text(x):
    if math.isnan(x) or math.isinf(x):
        return double_text(x)
    for digits in range(1, 10):
        t = "%.*g" % (digits, x)
        if struct.unpack("<f", struct.pack("<f", float(t)))[0] == x:
            return repr(float(t))
    return repr(x)


# ------------------------------------------------------------------ the private server

class Oracle:
    """A private SQL Anywhere server over the two seeded databases, alive between start() and stop()."""

    def __init__(self, sqlany17, schema, verbose):
        self.sqlany17, self.schema, self.verbose = sqlany17, schema, verbose
        self.name = "iq_oracle_%d" % os.getpid()
        self.dir = self.proc = self.port = None
        self.dbcapi = Dbcapi(sqlany17)
        self.facts = {}

    def log(self, msg):
        if self.verbose:
            print("  [oracle] " + msg, file=sys.stderr)

    def tool(self, name):
        return os.path.join(self.sqlany17, "bin64", name)

    def start(self):
        self.dir = tempfile.mkdtemp(prefix="iq-oracle-")
        env = dict(os.environ, LANG=LOCALE, LC_ALL=LOCALE)
        for db, (flags, _, _) in DATABASES.items():
            self.log("dbinit %s %s" % (" ".join(flags), db))
            subprocess.run([self.tool("dbinit"), "-q"] + flags + ["-dba", "dba," + PASSWORD,
                                                                    os.path.join(self.dir, db + ".db")],
                           check=True, stdout=subprocess.DEVNULL, stdin=subprocess.DEVNULL, env=env)
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            self.port = s.getsockname()[1]
        cmd = [self.tool("dbsrv17"), "-n", self.name] + [f.format(port=self.port) for f in SERVER_FLAGS] \
            + ["-o", os.path.join(self.dir, "server.log")] + [os.path.join(self.dir, db + ".db") for db in DATABASES]
        self.log(" ".join(cmd))
        self.proc = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                     stderr=subprocess.DEVNULL, env=env)
        deadline = time.time() + 60
        while True:
            if self.proc.poll() is not None:
                raise Bad("the server exited at start (%s): %s" % (self.proc.returncode, self.server_log()))
            try:
                self.connect(next(iter(DATABASES)), "utf-8").close()
                break
            except ConnectionError:
                if time.time() > deadline:
                    raise Bad("the server did not accept connections in 60 s: " + self.server_log())
                time.sleep(0.5)
        for db in DATABASES:
            self.facts[db] = self.setup(db)

    def server_log(self):
        try:
            with open(os.path.join(self.dir, "server.log"), errors="replace") as f:
                return f.read()[-2000:]
        except OSError:
            return "(no server log)"

    def connect(self, db, codec, charset=None):
        connstr = "UID=dba;PWD=%s;ServerName=%s;DatabaseName=%s;AutoStart=NO;CharSet=%s;%s" % (
            PASSWORD, self.name, db, charset or "UTF-8", self.links())
        return Connection(self.dbcapi, connstr, codec)

    def links(self):
        # this server's address only: never a broadcast, so never another server
        return "LINKS=tcpip(Host=127.0.0.1;Port=%d;DoBroadcast=NONE)" % self.port

    def setup(self, db):
        """Sets the database's PUBLIC options, checks its creation settings, and seeds it."""
        flags, public, expect = DATABASES[db]
        c = self.connect(db, "utf-8")
        try:
            props = {p: c.value("SELECT DB_PROPERTY('%s')" % p) for p in DB_PROPERTIES}
            for k, v in expect.items():
                if props[k] != v:
                    raise Bad("%s reports %s = %s, not %s (is snap.sh pinning the locale?)" % (db, k, props[k], v))
            for k, v in public:
                c.execute("SET OPTION PUBLIC.%s = '%s'" % (k, v))
            c.execute("COMMIT")
            build = c.value("SELECT PROPERTY('ProductVersion')")
        finally:
            c.close()
        codec = CODECS.get(props["CharSet"])
        if codec is None:
            raise Bad("%s: no Python codec for the charset %s" % (db, props["CharSet"]))
        c = self.connect(db, codec, props["CharSet"])
        try:
            # the seed's string literals are taken as written: no backslash escapes
            c.execute("SET TEMPORARY OPTION escape_character = 'Off'")
            for t in self.schema:
                cols = ", ".join("%s %s NULL" % (col["name"], col["type"]) for col in t["columns"])
                c.execute("CREATE TABLE %s (%s)" % (t["name"], cols))
                names = ", ".join(col["name"] for col in t["columns"])
                for r in t["rows"]:
                    for v in r:
                        if isinstance(v, str):
                            try:
                                v.encode(codec)
                            except UnicodeEncodeError:
                                raise Bad("schema.yaml: %r cannot be stored in %s (%s)" % (v, db, props["CharSet"]))
                    values = ", ".join(literal(col["type"], v, t["name"]) for col, v in zip(t["columns"], r))
                    c.execute("INSERT INTO %s (%s) VALUES (%s)" % (t["name"], names, values))
                count = c.value("SELECT COUNT(*) FROM %s" % t["name"])
                if count != str(len(t["rows"])):
                    raise Bad("%s.%s holds %s rows, not %d" % (db, t["name"], count, len(t["rows"])))
            c.execute("COMMIT")
        finally:
            c.close()
        self.log("%s seeded (%s, %s)" % (db, props["Collation"], props["CharSet"]))
        return {"build": build, "codec": codec, "charset": props["CharSet"],
                "meta": {"dbinit": " ".join(["dbinit"] + flags), "public": dict(public),
                         "properties": {p: props[p] for p in DB_PROPERTIES}}}

    def session(self, profile):
        """A connection to the profile's database with the profile's options set (each checked as set)."""
        db, options = PROFILES[profile]
        f = self.facts[db]
        c = self.connect(db, f["codec"], f["charset"])
        try:
            for k, v in [("request_timeout", REQUEST_TIMEOUT)] + options:
                c.execute("SET TEMPORARY OPTION %s = '%s'" % (k, v))
                now = c.value("SELECT CONNECTION_PROPERTY('%s')" % k)
                if now is None or now.lower() != v.lower():
                    raise Bad("%s: the option %s reads %r after being set to %r" % (profile, k, now, v))
        except BaseException:
            c.close()
            raise
        return c

    def alive(self):
        return self.proc is not None and self.proc.poll() is None

    def stop(self):
        if self.proc is not None:
            if self.proc.poll() is None:
                subprocess.run([self.tool("dbstop"), "-y", "-q", "-c",
                                "UID=dba;PWD=%s;ServerName=%s;AutoStart=NO;%s" % (PASSWORD, self.name, self.links())],
                               stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                try:
                    self.proc.wait(30)
                except subprocess.TimeoutExpired:
                    self.proc.terminate()
                    try:
                        self.proc.wait(10)
                    except subprocess.TimeoutExpired:
                        self.proc.kill()
                        self.proc.wait()
            self.proc = None
        if self.dir:
            shutil.rmtree(self.dir, ignore_errors=True)
            self.dir = None


# ------------------------------------------------------------------ running the cases

def answer(conn, profile, sql):
    """SQL Anywhere's answer: its columns and rows as text, or its error. A TIMESTAMP column's cells are fetched
    again at six fractional digits when the profile's own format shows fewer (SQL Anywhere hands a timestamp to the
    client as text in timestamp_format), every other cell is the first run's."""
    r = conn.query(sql)
    if "error" in r:
        return {"error": r["error"]}
    stamps = [i for i, col in enumerate(r["columns"]) if col[1] == "timestamp"]
    fmt = dict(PROFILES[profile][1]).get("timestamp_format")
    if stamps and (fmt or "").upper() != TS6:
        conn.execute("SET TEMPORARY OPTION timestamp_format = '%s'" % TS6)
        try:
            again = conn.query(sql)
        finally:
            conn.execute("SET TEMPORARY OPTION timestamp_format = %s" % ("'%s'" % fmt if fmt else ""))
        # the same rows of the same columns (a timestamp's precision is its text's length, so it changes)
        if "error" in again or len(again["rows"]) != len(r["rows"]) \
                or [c[:2] for c in again["columns"]] != [c[:2] for c in r["columns"]]:
            raise Bad("the timestamp re-fetch did not return the same shape: %r" % (again.get("error"),))
        for i in stamps:
            r["columns"][i] = again["columns"][i]
        for row, row6 in zip(r["rows"], again["rows"]):
            for i in stamps:
                row[i] = row6[i]
    return {"columns": r["columns"], "rows": r["rows"]}


def run_cases(oracle, cases, schema_bytes, report):
    """Every (case, profile) answer, as the dict written to iq-answers.jsonl; crashes and failures go to report."""
    out = {}
    by_profile = {}
    for c in cases:
        for p in c["profiles"]:
            by_profile.setdefault(p, []).append(c)
    for profile in PROFILES:
        todo = by_profile.get(profile, [])
        if not todo:
            continue
        db = PROFILES[profile][0]
        conn = oracle.session(profile)
        try:
            for c in todo:
                tries = 0
                while True:
                    tries += 1
                    try:
                        a = answer(conn, profile, c["sql"])
                        if "error" in a and not oracle.alive():
                            raise Lost(a["error"]["message"])
                        conn.execute("ROLLBACK")
                        break
                    except Lost as e:
                        conn.close()
                        print("  server lost on %s [%s]: %s; restarting" % (c["key"], profile, e), file=sys.stderr)
                        oracle.stop()
                        oracle.start()
                        conn = oracle.session(profile)
                        if tries == 2:
                            report.append(("CRASH", c["key"], profile,
                                           "crashes SQL Anywhere twice: add it to tools/iq-oracle/known_crashers.txt"
                                           " or give it sa_defect"))
                            a = None
                            break
                    except Bad as e:
                        report.append(("FAILED", c["key"], profile, str(e)))
                        a = None
                        break
                if a is None:
                    continue
                line = {"id": c["key"], "profile": profile, "db": db, "sa_build": oracle.facts[db]["build"],
                        "sqlSha": sql_sha(c["sql"], profile, schema_bytes)}
                line.update(a)
                out[(c["key"], profile)] = line
        finally:
            conn.close()
    return out


def meta(oracle):
    return {"meta": {
        "tool": "tools/iq-oracle/snap.py",
        "format": 1,
        "server": " ".join(["dbsrv17"] + [f.format(port="<free>") for f in SERVER_FLAGS]),
        "locale": LOCALE,
        "databases": {db: oracle.facts[db]["meta"] for db in DATABASES},
        "session": {"request_timeout": REQUEST_TIMEOUT, "charset": "the database's own"},
        "profiles": {p: {"db": db, "options": dict(opts)} for p, (db, opts) in PROFILES.items()},
        "timestamps": TS6,
    }}


def dump(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def read_answers(path):
    head, answers = None, {}
    if not os.path.exists(path):
        return head, answers
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            if not line.strip():
                continue
            o = json.loads(line)
            if "meta" in o and head is None and not answers:
                head = o
                continue
            k = (o["id"], o["profile"])
            if k in answers:
                raise Bad("%s:%d: %s [%s] answered twice" % (path, n, k[0], k[1]))
            answers[k] = o
    return head, answers


def write_answers(path, head, answers):
    lines = [dump(head)] + [dump(answers[k]) for k in sorted(answers)]
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    os.replace(tmp, path)


def differences(old, new):
    keys = [k for k in ("db", "sa_build", "columns", "rows", "error") if old.get(k) != new.get(k)]
    return ", ".join(keys)


# ------------------------------------------------------------------ main

def main(argv):
    ap = argparse.ArgumentParser(prog="snap.sh", description="SQL Anywhere 17's answers for the iq-parity cases.")
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true", help="snap the answers into iq-answers.jsonl")
    mode.add_argument("--check", action="store_true", help="re-run the cases and compare with iq-answers.jsonl")
    ap.add_argument("--only", metavar="GLOB", help="only the cases whose key (lite/<file>/<id>) or id matches")
    ap.add_argument("--repo", help="the legend-lite checkout (default: this file's)")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args(argv)

    repo = args.repo or os.environ.get("BUILD_WORKSPACE_DIRECTORY") or os.path.dirname(os.path.dirname(HERE))
    parity = os.path.join(repo, PARITY)
    answers_path = os.path.join(parity, "iq-answers.jsonl")
    sqlany17 = os.environ.get("SQLANY17")
    if not sqlany17:
        raise Bad("SQLANY17 is not set: run through tools/iq-oracle/snap.sh")

    schema_bytes, schema = read_schema(os.path.join(parity, "schema.yaml"))
    cases = read_cases(os.path.join(parity, "lite"))
    crashers = known_crashers(os.path.join(repo, CRASHERS))

    def selected(c):
        return not args.only or fnmatch.fnmatchcase(c["key"], args.only) or fnmatch.fnmatchcase(c["id"], args.only)

    live, skipped = [], []
    for c in cases:
        if c["skip"] is None and c["key"] in crashers:
            c["skip"] = "known to crash SQL Anywhere 17 (known_crashers.txt)"
        if c["skip"] is None and not single_select(c["sql"]):
            raise Bad("%s: not a single SELECT or WITH without ';'" % c["key"])
        (skipped if c["skip"] is not None else live).append(c)
    expected = {(c["key"], p): c for c in live for p in c["profiles"]}
    run = [c for c in live if selected(c)]
    head, committed = read_answers(answers_path)

    oracle = Oracle(sqlany17, schema, args.verbose)
    atexit.register(oracle.stop)
    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, lambda s, f: sys.exit(128 + s))
    t0 = time.time()
    oracle.start()
    report = []
    fresh = run_cases(oracle, run, schema_bytes, report)
    new_head = meta(oracle)
    oracle.stop()
    n_profiles = sum(len(c["profiles"]) for c in run)
    print("ran %d cases (%d answers) in %.1f s; %d skipped" % (len(run), n_profiles, time.time() - t0, len(skipped)))

    in_scope = {k for k in expected if selected(expected[k])}
    if args.write:
        merged = {k: v for k, v in committed.items() if k in expected and k not in in_scope}
        merged.update(fresh)
        gone = sorted(k for k in committed if k not in expected)
        write_answers(answers_path, new_head, merged)
        print("wrote %d answers to %s%s" % (len(merged), os.path.relpath(answers_path, repo),
                                             "; dropped %d gone" % len(gone) if gone else ""))
    else:
        if head != new_head:
            report.append(("DRIFT", "meta", "-", "the databases, server or profiles changed"))
        for k in sorted(in_scope):
            old, new = committed.get(k), fresh.get(k)
            if old is None:
                report.append(("UNSNAPPED", k[0], k[1], "no answer in iq-answers.jsonl"))
            elif old.get("sqlSha") != sql_sha(expected[k]["sql"], k[1], schema_bytes):
                report.append(("STALE", k[0], k[1], "the sql, profile or schema.yaml changed since the snap"))
            elif new is not None and old != new:
                report.append(("DRIFT", k[0], k[1], differences(old, new)))
        for k in sorted(committed):
            if k not in expected and (not args.only or fnmatch.fnmatchcase(k[0], args.only)
                                      or fnmatch.fnmatchcase(k[0].rsplit("/", 1)[-1], args.only)):
                report.append(("GONE", k[0], k[1], "an answer without a case (or its case is skipped)"))
    for kind, key, profile, why in report:
        print("%-9s %s [%s]: %s" % (kind, key, profile, why))
    print("%d problems" % len(report) if report else "clean")
    return 1 if report else 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Bad as e:
        print("snap: " + str(e), file=sys.stderr)
        sys.exit(2)
