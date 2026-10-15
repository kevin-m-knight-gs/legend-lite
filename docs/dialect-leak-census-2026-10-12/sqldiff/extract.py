"""Whole SQL statements out of a PCT test.log run with -Dlegend.diagnostics=dump-sql.

A record runs from one tag ([sql:<origin>], [script], [sql-fail]) to the next line that is not SQL: a test log line
('[LegendLite PCT] ...', '[pct-...', '[chB...', JUnit's tree, a timestamped log line) or the next tag. The
'[LegendLite PCT] Executing: <expression>' lines are kept as context, so a diff names the expression.
usage: python3 -I extract.py <test.log> <out> (sqlrun.sh runs it on each target)"""
import re
import sys

TAG = re.compile(r"^\[(sql:[a-z]+|script|sql-fail)\] ")
CONTEXT = re.compile(r"^'?\[LegendLite PCT\] Executing: ")
NOT_SQL = re.compile(r"^('?\[|\s*[├│└╷╵✔✘↷]|\d{4}-\d\d-\d\d|[A-Z][a-z]+ \d+, \d{4}|INFO|WARN|SEVERE|Exception|\tat |Caused by)")

out, cur = [], None
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    line = line.rstrip("\n")
    if TAG.match(line):
        if cur is not None:
            out.append("\n".join(cur))
        cur = [line]
    elif CONTEXT.match(line):
        if cur is not None:
            out.append("\n".join(cur))
            cur = None
        out.append(line.lstrip("'"))
    elif cur is not None and line.strip() and not NOT_SQL.match(line):
        cur.append(line)
    else:
        if cur is not None:
            out.append("\n".join(cur))
            cur = None
if cur is not None:
    out.append("\n".join(cur))
with open(sys.argv[2], "w", encoding="utf-8") as f:
    f.write("\n\x1e\n".join(out) + "\n")
print(sys.argv[2], len(out), "records")
