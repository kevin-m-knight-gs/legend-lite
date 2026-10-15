"""THE LIVE-IQ BACKEND of the #IQ{...}# island's oracle (README.md, "The live IQ"): a real SAP IQ 17.1's answers (the
user's HANA Cloud data lake) for the iq17-* profiles, snapped into iq-answers.jsonl beside SQL Anywhere 17's.

  snap.sh --oracle iq --iq-host HOST:443 --iq-user HDLADMIN [--iq-ca PEM] --write|--check [--only GLOB]

IQ island, landing 4 (WP1), 2026-10-14: real IQ is the oracle for the iq17-* profiles. snap.py reads the cases and
writes the file; this module reaches the server:

- **The credential** comes from the macOS Keychain (service legend-lite-iq, account = the IQ user), read by
  `security find-generic-password -w` straight into this process's memory and into the driver's connection string.
  It is never printed, logged, written to a file or put on a command line.
- **The connection** is SQL Anywhere 17's dbcapi over TLS. SA 17's TLS library crashes on macOS's whole
  /etc/ssl/cert.pem, so it trusts a minimal CA chain: --iq-ca, or one built here from `openssl s_client -showcerts`
  (the server's intermediates, and the system root that signed the last one).
- **The tables** are the parity schema as IQ-store tables named ll_<table>, owned by the user, each re-seeded when it
  is not as schema.yaml says (its columns and rows, checked on every run); each case's table references are rewritten
  to them.
- **The statements**: HDLADMIN may not set request_timeout (-121), so the client cancels any statement after 60 s;
  a case matching tools/iq-oracle/iq_crashers.txt is never sent; only a SELECT (or WITH, or a parenthesized one), the
  profile's and the case's temporary options, the case's variables and its temporary SET statements are sent, besides
  the seed's DDL on ll_* tables. Nothing persistent (a user's or PUBLIC's option) is ever set.
- **What it cannot judge** is answered `not_judged` with the reason (the lanes count such a run SKIP): a database
  property or a PUBLIC option other than this server's, a SET statement at the user's or PUBLIC's level, an option
  HDLADMIN may not set (-121), a known crasher, a table reference the rewrite cannot make faithfully, a statement
  cancelled after 60 s.
"""

import atexit
import ctypes as C
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time

import snap as S  # snap.py, registered under its name by its main() (the readers, dbcapi, the answer format)

# ------------------------------------------------------------------ the server, the profiles

DATABASE = "hdl_iq17"  # the answers' and the meta line's name for the live server's database
KEYCHAIN_SERVICE = "legend-lite-iq"
TIMEOUT = 60.0  # seconds per statement, cancelled by the client (sqlany_cancel)
CRASHERS = os.path.join("tools", "iq-oracle", "iq_crashers.txt")
PREFIX = "ll_"
SYSTEM_ROOTS = "/etc/ssl/cert.pem"
# the database properties the iq17-* presets are (IqProfile's): this server must report them
PRESET_PROPERTIES = {"CaseSensitive": "On", "BlankPadding": "On", "Collation": "UTF8BIN", "CharSet": "UTF-8"}
PROPERTIES = ["CaseSensitive", "BlankPadding", "Collation", "CharSet", "AccentSensitive", "NcharCollation",
              "NcharCharSet"]
# the PUBLIC options the meta line records (the presets' precision and scale)
PUBLIC = ["precision", "scale"]
TDS_NOTE = ("dbcapi cannot log in over TDS: the TDS login's options (sp_tsql_environment's) are set as temporary"
            " options on a CmdSeq connection")
# the codes that mean an option HDLADMIN may not set here: the environment's restriction, not IQ's semantics
PERMISSION = {-121}


def oracle_profiles():
    return [p for p in S.PROFILES if S.oracle_of(p) == "iq"]


NOT_UTF8 = [0]  # how many text cells held bytes that are not UTF-8 (LiveConnection._text)
NOT_UTF8_NOTE = "a text cell held bytes that are not UTF-8, each shown as U+FFFD (lite's text never matches them)"


class TimedOut(Exception):
    """The client cancelled a statement after TIMEOUT seconds."""


# ------------------------------------------------------------------ the credential and the CA chain

def password(user):
    """The IQ user's password from the macOS Keychain, into this process's memory only."""
    try:
        p = subprocess.run(["/usr/bin/security", "find-generic-password", "-s", KEYCHAIN_SERVICE, "-a", user, "-w"],
                           stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                           timeout=120)
    except (OSError, subprocess.TimeoutExpired):
        raise S.Bad("could not run /usr/bin/security to read the Keychain item %s for %s" % (KEYCHAIN_SERVICE, user))
    if p.returncode != 0:
        raise S.Bad("no Keychain item %r for the account %s: store it once with\n  security add-generic-password -U -a"
                    " %s -s %s -w" % (KEYCHAIN_SERVICE, user, user, KEYCHAIN_SERVICE))
    out = p.stdout.decode("utf-8")
    return out[:-1] if out.endswith("\n") else out


_PEM = re.compile(r"-----BEGIN CERTIFICATE-----\s.+?-----END CERTIFICATE-----", re.S)


def _names(pem):
    p = subprocess.run(["openssl", "x509", "-noout", "-subject", "-issuer", "-nameopt", "RFC2253"],
                       input=pem.encode("ascii"), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=60)
    subject = issuer = None
    for line in p.stdout.decode("utf-8", "replace").splitlines():
        if line.startswith("subject="):
            subject = line[len("subject="):].strip()
        elif line.startswith("issuer="):
            issuer = line[len("issuer="):].strip()
    if subject is None or issuer is None:
        raise S.Bad("openssl could not read a certificate")
    return subject, issuer


def ca_chain(host, workdir):
    """A minimal CA file for host (HOST:PORT): the certificates the server sends after its own, and the system root
    that signed the last of them (unless it signed itself). SA 17's TLS library reads only such a short file."""
    name = host.rsplit(":", 1)[0]
    try:
        p = subprocess.run(["openssl", "s_client", "-showcerts", "-connect", host, "-servername", name],
                           stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        raise S.Bad("openssl s_client could not reach %s (give --iq-ca)" % host)
    sent = _PEM.findall(p.stdout.decode("utf-8", "replace"))
    if len(sent) < 2:
        raise S.Bad("%s sent no certificate chain (give --iq-ca)" % host)
    chain = sent[1:]
    subject, issuer = _names(chain[-1])
    if subject != issuer:
        try:
            with open(SYSTEM_ROOTS, encoding="utf-8", errors="replace") as f:
                roots = _PEM.findall(f.read())
        except OSError:
            raise S.Bad("no %s to find the root of %s's chain in (give --iq-ca)" % (SYSTEM_ROOTS, host))
        for pem in roots:
            if _names(pem)[0] == issuer:
                chain.append(pem)
                break
        else:
            raise S.Bad("no system root %s for %s's chain (give --iq-ca)" % (issuer, host))
    path = os.path.join(workdir, "ca.pem")
    with open(path, "w", encoding="ascii") as f:
        f.write("\n".join(chain) + "\n")
    return path


# ------------------------------------------------------------------ the connection

class LiveConnection(S.Connection):
    """A dbcapi connection to the live IQ whose every statement the client cancels after TIMEOUT seconds."""

    def __init__(self, dbcapi, connstr):
        self.api, self.codec = dbcapi.api, "utf-8"
        self.conn = self.api.sqlany_new_connection()
        if not self.api.sqlany_connect(self.conn, connstr.encode("utf-8")):
            e = self.error()
            self.api.sqlany_free_connection(self.conn)
            self.conn = None
            raise ConnectionError("SQLCODE %d: %s" % (e["sqlcode"], e["message"]))

    def _watched(self, f, sql):
        fired = []

        def cancel():
            fired.append(True)
            self.api.sqlany_cancel(self.conn)

        timer = threading.Timer(TIMEOUT, cancel)
        timer.daemon = True
        timer.start()
        try:
            out = f(sql)
        except (S.Lost, S.Bad):
            if fired:
                raise TimedOut(sql)
            raise
        finally:
            timer.cancel()
        if fired:
            raise TimedOut(sql)
        return out

    def _text(self, raw):
        """A text cell; bytes that are not UTF-8 (IQ answers some: CHAR(233) is the one byte 0xE9 in a UTF-8 database)
        each shown as U+FFFD, and the answer noted (NOT_UTF8)."""
        try:
            return raw.decode("utf-8")
        except UnicodeDecodeError:
            NOT_UTF8[0] += 1
            return raw.decode("utf-8", "replace")

    def execute(self, sql):
        return self._watched(super().execute, sql)

    def run(self, sql):
        return self._watched(super().run, sql)

    def query(self, sql):
        return self._watched(super().query, sql)


class Server:
    """The live IQ: its connection string (with the password, never shown) and the facts read from it."""

    def __init__(self, sqlany17, host, user, ca, verbose):
        self.host, self.user, self.verbose = host, user, verbose
        self.dbcapi = S.Dbcapi(sqlany17)
        f = self.dbcapi.api.sqlany_cancel
        f.restype, f.argtypes = None, [C.c_void_p]
        for part in (host, user, ca):
            if re.search(r"[;{}()]", part):
                raise S.Bad("the host, the user and the CA file's path may not hold ';', braces or parentheses")
        # the password goes from the Keychain into this string and nowhere else
        self._connstr = "UID=%s;PWD=%s;HOST=%s;ENC=TLS(tls_type=rsa;direct=yes;trusted_certificates=%s);CS=UTF-8;" \
                        "CON=legend_lite_iq_oracle" % (user, password(user), host, ca)
        self.facts = None

    def log(self, msg):
        if self.verbose:
            print("  [live iq] " + msg, file=sys.stderr)

    def connect(self):
        return LiveConnection(self.dbcapi, self._connstr)

    def connect_waiting(self, budget=300.0):
        """A connection, retried for up to budget seconds (a shared server may refuse connections a while after a
        statement took one down)."""
        deadline, wait = time.time() + budget, 5.0
        while True:
            try:
                return self.connect()
            except ConnectionError as e:
                if time.time() > deadline:
                    raise S.Bad("the live IQ refuses connections: %s" % e)
                print("  live IQ: cannot connect (%s); retrying in %d s" % (e, wait), file=sys.stderr)
                time.sleep(wait)
                wait = min(wait * 2, 60.0)

    def read_facts(self):
        """The server's version, its database properties (which must be the iq17-* presets'), its PUBLIC precision and
        scale, every PUBLIC option's value (to tell a database-level setting this server already has) and the user's
        own option values (which a connection reads before PUBLIC's)."""
        c = self.connect()
        try:
            version = c.value("SELECT @@version")
            props = {p: c.value("SELECT DB_PROPERTY('%s')" % p) for p in PROPERTIES}
            for k, v in PRESET_PROPERTIES.items():
                if props.get(k) != v:
                    raise S.Bad("the live IQ reports %s = %s, not %s: the iq17-* presets are a database of %s"
                                % (k, props.get(k), v, PRESET_PROPERTIES))
            public, mine = {}, {}
            r = c.query("SELECT user_name, \"option\", setting FROM SYS.SYSOPTIONS")
            if "error" in r:
                raise S.Bad("SYS.SYSOPTIONS: %r" % r["error"])
            for user, option, setting in r["rows"]:
                if user == "PUBLIC":
                    public[option.lower()] = setting
                elif user.lower() == self.user.lower():
                    mine[option] = setting
            m = re.match(r"SAP IQ/(\d+(?:\.\d+)+)", version or "")
            if not m:
                raise S.Bad("not an SAP IQ server: @@version is %r" % version)
        finally:
            c.close()
        self.facts = {"version": version, "iq_version": m.group(1), "properties": props, "public": public,
                      "user_options": dict(sorted(mine.items()))}
        self.log("%s; %s" % (version, props))
        return self.facts

    def session(self, profile):
        """A connection with the profile's login options set temporarily, each read back as set."""
        c = self.connect_waiting()
        try:
            for k, v in S.PROFILES[profile][1]:
                c.execute("SET TEMPORARY OPTION %s = '%s'" % (k, v))
                now = c.value("SELECT CONNECTION_PROPERTY('%s')" % k)
                if now is None or now.lower() != v.lower():
                    raise S.Bad("%s: the option %s reads %r after being set to %r" % (profile, k, now, v))
        except BaseException:
            c.close()
            raise
        return c

    # -------------------------------------------------------------- the tables

    def seed(self, schema_bytes, schema):
        """The parity schema as IQ-store tables ll_<name>: each table that is missing, not in the IQ store, or not as
        schema.yaml says (its columns' names and types, or its rows, compared with the same values the server reads
        from the schema's literals) is made afresh; one that is as it says is left alone, so a run takes no lock on a
        table another session reads (DROP TABLE waits for every reader)."""
        sha = hashlib.sha256(schema_bytes).hexdigest()
        for t in schema:
            if not re.fullmatch(r"[a-z][a-z0-9_]*", t["name"]):
                raise S.Bad("a seeded table's name must be a lower-case identifier: %s" % t["name"])
        c = self.connect_waiting()
        try:
            # the seed's string literals are taken as written: no backslash escapes
            c.execute("SET TEMPORARY OPTION escape_character = 'Off'")
            stale = [t for t in schema if not self._intact(c, t)]
            if not stale:
                self.log("the ll_* tables hold schema.yaml %s" % sha[:12])
                return
            print("  live IQ: seeding %s (schema.yaml %s)" % (", ".join(PREFIX + t["name"] for t in stale), sha[:12]),
                  file=sys.stderr)
            for t in stale:
                c.execute("DROP TABLE IF EXISTS %s%s" % (PREFIX, t["name"]))
                cols = ", ".join("%s %s NULL" % (col["name"], col["type"]) for col in t["columns"])
                c.execute("CREATE TABLE %s%s (%s)" % (PREFIX, t["name"], cols))
                cols = ", ".join(col["name"] for col in t["columns"])
                for r in t["rows"]:
                    values = ", ".join(S.literal(col["type"], v, t["name"]) for col, v in zip(t["columns"], r))
                    c.execute("INSERT INTO %s%s (%s) VALUES (%s)" % (PREFIX, t["name"], cols, values))
                c.execute("COMMIT")
            bad = [PREFIX + t["name"] for t in schema if not self._intact(c, t)]
            if bad:
                raise S.Bad("the seeded tables %s are not as schema.yaml says" % ", ".join(bad))
        finally:
            c.close()

    def _intact(self, c, t):
        """Whether ll_<t> is an IQ-store table of the user's with t's columns and rows: its SELECT * described and
        fetched as the server describes and fetches the schema's own literals, each CAST to its column's type."""
        n = PREFIX + t["name"]
        r = c.query("SELECT CASE WHEN EXISTS (SELECT 1 FROM SYS.SYSIQTAB i WHERE i.table_id = t.table_id) THEN 'IQ'"
                    " ELSE 'catalog' END FROM SYS.SYSTAB t WHERE t.creator = USER_ID() AND t.table_name = '%s'"
                    " AND t.table_type_str = 'BASE'" % n)
        if "error" in r:
            raise S.Bad("SYS.SYSTAB: %r" % r["error"])
        if r["rows"] != [["IQ"]]:
            return False
        have = c.query("SELECT * FROM %s" % n)
        if "error" in have:
            return False

        def item(col, v):
            return "CAST(%s AS %s) AS %s" % (S.literal(col["type"], v, t["name"]), col["type"], col["name"])

        rows = t["rows"] or [[None] * len(t["columns"])]
        want = c.query(" UNION ALL ".join("SELECT " + ", ".join(item(col, v) for col, v in zip(t["columns"], row))
                                          + " FROM DUMMY" + ("" if t["rows"] else " WHERE 1 = 0") for row in rows))
        if "error" in want:
            raise S.Bad("the schema's own rows of %s: %r" % (t["name"], want["error"]))
        # a CHAR(n) value the IQ store keeps padded to n (D-S1), which the front end's CAST does not pad
        chars = [i for i, col in enumerate(have["columns"]) if col[1] == "char"]

        def key(row):
            return [("" if v is None else "v" + (v.rstrip(" ") if i in chars else v)) for i, v in enumerate(row)]

        return have["columns"] == want["columns"] and sorted(map(key, have["rows"])) == sorted(map(key, want["rows"]))


# ------------------------------------------------------------------ never sent: iq_crashers.txt

def read_crashers(path):
    """(keys, shapes): the case keys and the SQL shapes (regular expressions over a case's SQL as written) that hang or
    crash IQ 17.1, each with its reason. Lines: `key <case key>  # reason` or `shape <regex>  # reason`."""
    keys, shapes = {}, []
    if not os.path.exists(path):
        return keys, shapes
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            body, _, why = line.partition("  # ")
            body = body.strip()
            if not body or body.startswith("#"):
                continue
            kind, _, value = body.partition(" ")
            if kind == "key" and value.strip():
                keys[value.strip()] = why.strip()
            elif kind == "shape" and value.strip():
                try:
                    shapes.append((re.compile(value.strip()), why.strip()))
                except re.error as e:
                    raise S.Bad("%s:%d: a shape is a regular expression: %s" % (path, n, e))
            else:
                raise S.Bad("%s:%d: a line is `key <case key>  # reason` or `shape <regex>  # reason`" % (path, n))
    return keys, shapes


def crasher(c, crashers):
    keys, shapes = crashers
    if c["key"] in keys:
        return keys[c["key"]]
    for pattern, why in shapes:
        if pattern.search(c["sql"]):
            return why
    return None


# ------------------------------------------------------------------ the table rewrite
# A case reads the oracle's tables (t, u, ... owned by DBA); the live IQ's are ll_t, ll_u, ... owned by the user. A
# tokenizer that knows SQL Anywhere's strings, quoted names and comments finds every table position (after FROM, JOIN,
# APPLY and a FROM list's comma, at any nesting). Strategy A (the default): a base table becomes ll_<name> with its own
# name as its correlation name when it has none (`FROM t` -> `FROM ll_t AS t`, `FROM DBA.t` -> `FROM <user>.ll_t AS
# t`), so t.col, the derived column names (`t.id+1`) and the errors read as before. Strategy B (a query naming a base
# table's column owner.table.column): no correlation name is added, and the owner and table parts of each
# owner.table.column and table.column of a base table are rewritten too; refused beside a correlation name or a CTE
# spelled like a base table. A CTE named like a base table is never rewritten. An owner DBA becomes the user; any
# other owner, and server or database qualifiers, are kept. (The corpus run of 2026-10-14 used this rewrite on 3,446
# runs.)

_NOT_ALIAS = {"where", "group", "having", "order", "union", "except", "intersect", "minus", "window", "for", "limit",
              "offset", "option", "join", "inner", "left", "right", "full", "outer", "cross", "natural", "key", "on",
              "using", "with", "apply", "into", "fetch", "pivot", "unpivot", "force", "no", "contains", "lateral",
              "from", "select", "as", "straight_join", "values", "to", "rows", "range", "partition", "start", "connect",
              "returning", "and", "or", "not", "then", "else", "end", "when", "is", "in", "like", "between"}
_ENDS_FROM = {"where", "group", "having", "order", "union", "except", "intersect", "minus", "window", "for", "limit",
              "offset", "option", "into", "fetch", "select"}


class Refuse(Exception):
    """The rewrite would not be faithful."""


def _tokens(sql):
    out, i, n = [], 0, len(sql)
    while i < n:
        c = sql[i]
        if c.isspace():
            j = i
            while j < n and sql[j].isspace():
                j += 1
            out.append(("ws", sql[i:j]))
            i = j
            continue
        if sql.startswith("--", i) or sql.startswith("//", i):
            j = sql.find("\n", i)
            j = n if j < 0 else j
            out.append(("comment", sql[i:j]))
            i = j
            continue
        if sql.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if sql.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif sql.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            out.append(("comment", sql[i:j]))
            i = j
            continue
        if c in "'\"`[":
            close = "]" if c == "[" else c
            j = i + 1
            while j < n:
                if sql[j] == close:
                    if close in "'\"" and j + 1 < n and sql[j + 1] == close:
                        j += 2
                        continue
                    break
                j += 1
            out.append(("string" if c == "'" else "qident", sql[i:j + 1]))
            i = j + 1
            continue
        if c.isalpha() or c in "_@#$":
            j = i + 1
            while j < n and (sql[j].isalnum() or sql[j] in "_@#$"):
                j += 1
            out.append(("word", sql[i:j]))
            i = j
            continue
        if c.isdigit():
            j = i + 1
            while j < n and (sql[j].isalnum() or sql[j] == "."):
                j += 1
            out.append(("number", sql[i:j]))
            i = j
            continue
        out.append(("punct", c))
        i += 1
    return out


def _name(tok):
    k, t = tok
    if k == "word":
        return t.lower()
    if k == "qident":
        return t[1:-1].lower()
    return None


def _spelled(tok, new):
    k, t = tok
    return t[0] + new + t[-1] if k == "qident" else new


def rewrite(sql, tables, owner):
    """(sql over the live IQ's tables, the strategy: None when no base table is read, "A" or "B"). Raises Refuse."""
    toks = _tokens(sql)
    sig = [i for i, t in enumerate(toks) if t[0] not in ("ws", "comment")]

    def tok(p):
        return toks[sig[p]] if p < len(sig) else ("eof", "")

    def low(p):
        return tok(p)[1].lower() if tok(p)[0] == "word" else None

    def close_paren(r):
        depth = 0
        while r < len(sig):
            if tok(r) == ("punct", "("):
                depth += 1
            elif tok(r) == ("punct", ")"):
                depth -= 1
                if depth == 0:
                    break
            r += 1
        return r

    ctes = set()
    for p in range(len(sig)):
        if low(p) != "with":
            continue
        q = p + 1
        if low(q) == "recursive":
            q += 1
        while True:
            nm = _name(tok(q)) if tok(q)[0] in ("word", "qident") else None
            if nm is None:
                break
            r = q + 1
            if tok(r) == ("punct", "("):
                r = close_paren(r) + 1
            if low(r) == "as" and tok(r + 1) == ("punct", "("):
                ctes.add(nm)
                r = close_paren(r + 1)
                if tok(r + 1) == ("punct", ","):
                    q = r + 2
                    continue
            break

    refs, aliases = [], set()
    frames = [{"in_from": False, "expect": False}]
    p = 0
    while p < len(sig):
        k, t = tok(p)
        f = frames[-1]
        lw = t.lower() if k == "word" else None
        if (k, t) == ("punct", "("):
            nf = {"in_from": False, "expect": False}
            if f["in_from"] and f["expect"]:
                if low(p + 1) not in ("select", "with", "values"):
                    nf = {"in_from": True, "expect": True}
                f["expect"] = False
                f["after_paren"] = True
            frames.append(nf)
            p += 1
            continue
        if (k, t) == ("punct", ")"):
            if len(frames) > 1:
                frames.pop()
            f = frames[-1]
            if f.get("after_paren"):
                f["after_paren"] = False
                q = p + 1
                if low(q) == "as":
                    q += 1
                if tok(q)[0] in ("word", "qident") and (tok(q)[0] == "qident" or low(q) not in _NOT_ALIAS):
                    aliases.add(_name(tok(q)))
            p += 1
            continue
        if lw == "from":
            f["in_from"], f["expect"] = True, True
            p += 1
            continue
        if f["in_from"]:
            if lw in _ENDS_FROM:
                f["in_from"], f["expect"] = False, False
                p += 1
                continue
            if lw in ("join", "apply") or (k, t) == ("punct", ","):
                f["expect"] = True
                p += 1
                continue
            if lw == "on":
                f["expect"] = False
                p += 1
                continue
            if f["expect"] and k in ("word", "qident") and (k == "qident" or lw not in _NOT_ALIAS):
                parts, q = [p], p + 1
                while tok(q) == ("punct", "."):
                    if tok(q + 1)[0] in ("word", "qident"):
                        parts.append(q + 1)
                        q += 2
                    elif tok(q + 1) == ("punct", "."):
                        parts.append(None)
                        q += 1
                    else:
                        break
                f["expect"] = False
                if tok(q) == ("punct", "("):
                    p = q  # a procedure in FROM (sa_rowgenerator(...)): its '(' is read as an expression's
                    continue
                a, has_alias = q, False
                if low(a) == "as":
                    a += 1
                if tok(a)[0] == "qident" or (tok(a)[0] == "word" and low(a) not in _NOT_ALIAS):
                    has_alias = True
                    aliases.add(_name(tok(a)))
                refs.append((parts, has_alias))
                p = a + 1 if has_alias else q
                continue
        if lw == "select":
            f["in_from"], f["expect"] = False, False
        p += 1

    base = [(parts, has_alias) for parts, has_alias in refs
            if _name(tok(parts[-1])) in tables and _name(tok(parts[-1])) not in ctes]
    if not base:
        return sql, None
    in_ref = {pp for parts, _ in refs for pp in parts if pp is not None}
    three = [p for p in range(len(sig)) if p not in in_ref and tok(p)[0] in ("word", "qident")
             and tok(p + 1) == ("punct", ".") and tok(p + 2)[0] in ("word", "qident") and tok(p + 3) == ("punct", ".")
             and (tok(p + 4)[0] in ("word", "qident") or tok(p + 4) == ("punct", "*"))
             and not (p > 0 and tok(p - 1) == ("punct", ".")) and _name(tok(p + 2)) in tables]
    strategy = "B" if three else "A"
    edits, inserts = {}, {}

    def owner_edit(pos):
        if pos is not None and _name(tok(pos)) == "dba":
            edits[sig[pos]] = _spelled(tok(pos), owner)

    def table_edit(pos):
        edits[sig[pos]] = _spelled(tok(pos), PREFIX + tok(pos)[1].strip('"[]`'))

    if strategy == "B":
        shadow = (aliases | ctes) & tables
        if shadow:
            raise Refuse("an owner.table.column beside a correlation name or a CTE spelled like a base table (%s)"
                         % ", ".join(sorted(shadow)))
        for parts, _ in base:
            table_edit(parts[-1])
            if len(parts) >= 2:
                owner_edit(parts[-2])
        for p in three:
            owner_edit(p)
            table_edit(p + 2)
        for p in range(len(sig)):
            if p in in_ref or sig[p] in edits:
                continue
            if _name(tok(p)) in tables and tok(p + 1) == ("punct", ".") and not (p > 0 and tok(p - 1) == ("punct", ".")):
                table_edit(p)
    else:
        for parts, has_alias in base:
            table_edit(parts[-1])
            if len(parts) >= 2:
                owner_edit(parts[-2])
            if not has_alias:
                inserts[sig[parts[-1]]] = " AS " + tok(parts[-1])[1]
    out = []
    for i, (k, t) in enumerate(toks):
        out.append(edits.get(i, t))
        if i in inserts:
            out.append(inserts[i])
    return "".join(out), strategy


def names_back(columns, sql, tables, owner):
    """Under strategy B the live IQ's derived names show the rewritten owner.table (DBA.t.id -> <user>.ll_t.id): each
    read back as the case wrote it."""
    m = re.search(r"(?i)\b(dba)\s*\.", sql)
    written = m.group(1) if m else "DBA"
    pattern = re.compile(r"(?i)\b%s(%s)\b" % (re.escape(PREFIX), "|".join(re.escape(t) for t in sorted(tables))))
    return [[pattern.sub(r"\1", re.sub(r"\b%s\." % re.escape(owner), written + ".", c[0]))] + list(c[1:])
            for c in columns]


# ------------------------------------------------------------------ what a run sends, or why it sends nothing

def _same(given, have):
    return have is not None and given.strip().lower() == str(have).strip().lower()


def plan(c, profile, facts):
    """(login options, the settings' statements, the SET statements, the SELECT, None) for a run the live IQ judges,
    or (..., the reason it is not judged): the database properties and PUBLIC options given must be this server's
    (none can be set: a database's properties are fixed when it is made, and HDLADMIN may not set a shared server's
    PUBLIC options), CommProtocol picks the login, a query-level setting is a temporary option; a SET statement must
    be temporary and not PUBLIC's (a user's or PUBLIC's option outlives the case, on a shared server)."""
    login = list(S.PROFILES[profile][1])
    statements = []
    sets, select = S.set_prefix(c["run"])
    for level, name, value in c["given"]:
        low = name.lower()
        if level == "database" and low == "commprotocol":
            if value.lower() == "tds":
                login = list(S.TDS)
            elif value.lower() == "cmdseq":
                if S.PROFILES[profile][1] == S.TDS:
                    login = []
            else:
                return login, statements, sets, select, "the database's CommProtocol %r: this oracle logs in by" \
                                                        " CmdSeq, a TDS login emulated" % value
    for level, name, value in c["given"]:
        low = name.lower()
        if level == "database":
            if low == "commprotocol":
                continue
            if low in S.DB_SETTINGS:
                have = facts["properties"].get(S.DB_SETTINGS[low])
                if not _same(value, have):
                    return login, statements, sets, select, "the database property %s = '%s': this server's is %r," \
                        " and a database's properties are fixed when it is made" % (name, value, have)
                continue
            have = facts["public"].get(low)
            if not _same(value, have):
                return login, statements, sets, select, "the database-level option %s = '%s' (PUBLIC's): this" \
                    " server's PUBLIC value is %r, and HDLADMIN may not set a shared server's PUBLIC options" \
                    % (name, value, have)
            continue
        statements.append("SET TEMPORARY OPTION %s = '%s'" % (name, value.replace("'", "''")))
    for st in sets:
        s = S._uncommented(st)
        if re.match(r"SET\s+(EXISTING\s+)?(TEMPORARY\s+)?OPTION\b", s, re.I):
            if not re.match(r"SET\s+(EXISTING\s+)?TEMPORARY\s+OPTION\b", s, re.I):
                return login, statements, sets, select, "a SET OPTION at the user's or PUBLIC's level (%s): it" \
                    " outlives the case on a shared server, so it is never sent" % s.strip()[:80]
            if re.match(r"SET\s+(EXISTING\s+)?TEMPORARY\s+OPTION\s+(\"?PUBLIC\"?|\[PUBLIC\])\s*\.", s, re.I):
                return login, statements, sets, select, "a SET TEMPORARY OPTION PUBLIC.x (%s): it holds for every" \
                    " connection of a shared server until it stops, so it is never sent" % s.strip()[:80]
    return login, statements, sets, select, None


def not_sent_reason(e):
    if e is not None and e["sqlcode"] in PERMISSION:
        return "HDLADMIN may not set this on the live server (SQLCODE %d: %s): the environment's restriction, not" \
               " IQ's answer" % (e["sqlcode"], e["message"])
    return None


# ------------------------------------------------------------------ running the cases

def answer_isolated(server, c, profile, login, statements, sets, select):
    """A case with settings or SET statements, on a connection of its own (closed after it: every option it set is
    temporary): the login's options, its settings, its variables, its SET statements, then its SELECT; the first error
    is the answer, but an option HDLADMIN may not set, which is no answer."""
    conn = server.connect_waiting()
    try:
        for k, v in login:
            conn.execute("SET TEMPORARY OPTION %s = '%s'" % (k, v))
        e = None
        for st in statements:
            e = conn.run(st)
            if e is not None:
                break
        if e is None:
            e = S.bind_params(conn, c["bound"], profile)
        if e is None:
            for st in sets:
                e = conn.run(st)
                if e is not None:
                    break
        why = not_sent_reason(e)
        if why is not None:
            return {"not_judged": why}
        a = {"error": e} if e is not None else S.answer_now(conn, select)
        conn.run("ROLLBACK")
        return a
    finally:
        conn.close()


def answer_plain(conn, c, profile, select):
    failed = S.bind_params(conn, c["bound"], profile)
    if failed is not None:
        a = {"error": failed}
    else:
        try:
            a = S.answer(conn, profile, select)
        finally:
            if c["bound"]:
                S.drop_params(conn, [p[0] for p in c["bound"]], profile)
    conn.execute("ROLLBACK")
    return a


def snap_runs(server, runs, schema_bytes, tables, crashers, report, confirm_against=None):
    """Every (case, profile) answer of runs (a list of (case, profile)) as the line written to iq-answers.jsonl;
    crashes, failures and unstable answers go to report. confirm_against: the SQL Anywhere answers (key, profile) ->
    line; a run whose answer differs from its iq16 twin's is run a second time, and must answer alike."""
    facts = server.facts
    out = {}
    sessions = {}
    done = 0
    t0 = time.time()
    try:
        for c, profile in runs:
            done += 1
            if done % 250 == 0:
                print("  live IQ: %d of %d runs (%.0f s)" % (done, len(runs), time.time() - t0), file=sys.stderr)
            line = {"id": c["key"], "profile": profile, "oracle": "iq", "db": DATABASE,
                    "db_properties": {k: facts["properties"][k] for k in PRESET_PROPERTIES},
                    "iq_version": facts["iq_version"], "sqlSha": S.sql_sha(c, profile, schema_bytes)}
            if S.PROFILES[profile][1] == S.TDS:
                line["login"] = "tds emulated"
            a = one(server, sessions, c, profile, tables, crashers, report)
            if a is None:
                continue
            if confirm_against is not None and "not_judged" not in a:
                twin = confirm_against.get((c["key"], "iq16-" + profile[len("iq17-"):]))
                if twin is None or differs(a, twin):
                    again = one(server, sessions, c, profile, tables, crashers, report)
                    if again is None:
                        continue
                    if not alike(a, again):
                        report.append(("UNSTABLE", c["key"], profile, "the live IQ answered otherwise a second time"))
                        continue
                    a["_confirmed"] = True
            line.update(a)
            out[(c["key"], profile)] = line
    finally:
        for conn in sessions.values():
            conn.close()
    return out


def one(server, sessions, c, profile, tables, crashers, report):
    """One run's answer ({columns, rows}, {error} or {not_judged}); None when it could not be snapped (reported)."""
    why = crasher(c, crashers)
    if why is not None:
        return {"not_judged": "never sent: it hangs or crashes IQ 17.1 (tools/iq-oracle/iq_crashers.txt: %s)" % why}
    login, statements, sets, select, why = plan(c, profile, server.facts)
    if why is not None:
        return {"not_judged": why}
    try:
        probe, strategy = rewrite(select, tables, server.user)
    except Refuse as e:
        return {"not_judged": "the table rewrite would not be faithful: %s" % e}
    isolated = c.get("settings") is not None or bool(sets) or login != list(S.PROFILES[profile][1])
    replaced = NOT_UTF8[0]
    try:
        if isolated:
            a = answer_isolated(server, c, profile, login, statements, sets, probe)
        else:
            if profile not in sessions:
                sessions[profile] = server.session(profile)
            a = answer_plain(sessions[profile], c, profile, probe)
    except TimedOut:
        _drop(sessions, profile)
        return {"not_judged": "ran past %d s, cancelled by the client (HDLADMIN may not set request_timeout)"
                % TIMEOUT}
    except S.Lost as e:
        _drop(sessions, profile)
        report.append(("CRASH", c["key"], profile, "the live IQ's connection was lost (%s): never retried on a shared"
                       " server; add it to tools/iq-oracle/iq_crashers.txt" % e))
        return None
    except S.Bad as e:
        _drop(sessions, profile)
        report.append(("FAILED", c["key"], profile, str(e)))
        return None
    except (ValueError, ArithmeticError, LookupError, OSError) as e:
        _drop(sessions, profile)
        report.append(("FAILED", c["key"], profile, "%s: %s" % (type(e).__name__, e)))
        return None
    if strategy == "B" and "columns" in a:
        a["columns"] = names_back(a["columns"], select, tables, server.user)
    if NOT_UTF8[0] != replaced and "columns" in a:
        a["note"] = NOT_UTF8_NOTE
    return a


def _drop(sessions, profile):
    conn = sessions.pop(profile, None)
    if conn is not None:
        try:
            conn.close()
        except Exception:
            pass


def differs(a, sa):
    """Whether the live IQ's answer differs from SQL Anywhere's: in its error's code, its column names or its rows."""
    if ("error" in a) != ("error" in sa):
        return True
    if "error" in a:
        return a["error"]["sqlcode"] != sa["error"]["sqlcode"]
    return [col[0] for col in a["columns"]] != [col[0] for col in sa.get("columns", [])] or a["rows"] != sa.get("rows")


def alike(a, b):
    """Two answers of one run alike: the same error code, or the same columns and the same rows in any order (IQ
    returns rows a query does not order in its plan's order)."""
    if ("error" in a) != ("error" in b) or ("not_judged" in a) != ("not_judged" in b):
        return False
    if "error" in a:
        return a["error"]["sqlcode"] == b["error"]["sqlcode"]
    if "not_judged" in a:
        return True
    key = lambda r: [("" if v is None else "v" + v) for v in r]
    return a["columns"] == b["columns"] and sorted(a["rows"], key=key) == sorted(b["rows"], key=key)


def meta(facts):
    return {
        "tool": "tools/iq-oracle/snap.py --oracle iq (live_iq.py)",
        "format": 2,
        "server": "SAP IQ 17.1 (a HANA Cloud data lake), through SQL Anywhere 17's dbcapi over TLS",
        "version": facts["version"],
        "iq_version": facts["iq_version"],
        "databases": {DATABASE: {"properties": facts["properties"],
                                 "public": {k: facts["public"].get(k) for k in PUBLIC}}},
        "user_options": facts["user_options"],
        "profiles": {p: dict({"db": DATABASE, "options": dict(S.PROFILES[p][1])},
                             **({"login": TDS_NOTE} if S.PROFILES[p][1] == S.TDS else {})) for p in oracle_profiles()},
        "tables": "the parity schema as IQ-store tables %s<name>, owned by the user, each re-seeded when it is not as"
                  " schema.yaml says (checked on every run); each case's table references rewritten to them (an owner"
                  " DBA read as the user)" % PREFIX,
        "timeout": "%d s per statement, cancelled by the client (HDLADMIN may not set request_timeout)" % TIMEOUT,
        "never_sent": CRASHERS,
        "settings": "a case's query-level settings are temporary options on a connection of its own; a database"
                    " property or a PUBLIC option given other than this server's, a SET statement at the user's or"
                    " PUBLIC's level, and an option HDLADMIN may not set (-121) are answered not_judged",
        "sqlSha": "as SQL Anywhere's answers' but with no decision and no expect: real IQ judges an iq17-* run",
        "confirmed": "on --write, a run whose answer differs from its iq16 twin's SQL Anywhere answer is run twice and"
                     " must answer alike",
    }


def main(args, repo, run, schema_bytes, schema, committed, report):
    """The live IQ's answers for run (a list of (case, profile)) and its meta: (answers, meta)."""
    host = args.iq_host or os.environ.get("IQ_ORACLE_HOST")
    if not host:
        raise S.Bad("--iq-host HOST:PORT (or IQ_ORACLE_HOST) names the live IQ")
    if ":" not in host:
        host += ":443"
    user = args.iq_user or os.environ.get("IQ_ORACLE_USER") or "HDLADMIN"
    sqlany17 = os.environ.get("SQLANY17")
    if not sqlany17:
        raise S.Bad("SQLANY17 is not set: run through tools/iq-oracle/snap.sh")
    workdir = tempfile.mkdtemp(prefix="iq-oracle-live-")
    atexit.register(shutil.rmtree, workdir, True)
    ca = args.iq_ca or ca_chain(host, workdir)
    server = Server(sqlany17, host, user, ca, args.verbose)
    facts = server.read_facts()
    server.seed(schema_bytes, schema)
    crashers = read_crashers(os.path.join(repo, CRASHERS))
    tables = {t["name"] for t in schema}
    t0 = time.time()
    fresh = snap_runs(server, run, schema_bytes, tables, crashers, report,
                      confirm_against={k: v for k, v in committed.items() if S.oracle_of(k[1]) == "sa"}
                      if args.write else None)
    confirmed = sum(1 for v in fresh.values() if v.pop("_confirmed", False))
    print("live IQ %s: %d runs in %.0f s; %d not judged; %d confirmed by a second run" % (
        facts["iq_version"], len(fresh), time.time() - t0, sum(1 for v in fresh.values() if "not_judged" in v),
        confirmed))
    return fresh, meta(facts)
