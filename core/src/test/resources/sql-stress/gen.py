"""Stress queries over the parity schema, scaled along one axis at a time, plus combined monsters.

usage: python3 gen.py <out.tsv> [seed]
Each line: id <TAB> sql. Every query orders by all of its output columns, so its answer is deterministic.
"""
import random
import sys

out = sys.argv[1]
R = random.Random(int(sys.argv[2]) if len(sys.argv) > 2 else 7)

# every table has an integer id 1..10; the columns each offers to a select list or a predicate
TABLES = {
    'persons': [('name', 'text'), ('age', 'int'), ('salary', 'num'), ('hire_date', 'date'), ('dept_id', 'int')],
    'departments': [('name', 'text'), ('budget', 'num'), ('created_at', 'ts')],
    'orders': [('person_id', 'int'), ('amount', 'num'), ('order_date', 'ts'), ('status', 'text')],
    'numbers': [('int_val', 'int'), ('numeric_val', 'num'), ('small_val', 'int'), ('big_val', 'int')],
    'strings': [('val', 'text'), ('nullable_val', 'text')],
    'dates': [('d', 'date'), ('ts', 'ts')],
}
NAMES = list(TABLES)
cases = []


def add(cid, sql):
    cases.append((cid, ' '.join(sql.split())))


def pred(alias, table):
    col, t = R.choice(TABLES[table])
    c = f'{alias}.{col}'
    return {
        'int': lambda: f'({c} IS NULL OR {c} > {R.choice([-1, 0, 2, 20])})',
        'num': lambda: f'({c} IS NULL OR {c} >= {R.choice(["0", "10.5", "100"])})',
        'text': lambda: f"({c} IS NULL OR {c} <> '{R.choice(['Alice', 'x', 'pending'])}')",
        'date': lambda: f"({c} IS NULL OR {c} > DATE '2000-01-01')",
        'ts': lambda: f"({c} IS NULL OR {c} > TIMESTAMP '2000-01-01 00:00:00')",
    }[t]()


# ---- A. N-way joins, mixed types, ON filters, a tree of join keys; plain and aggregated
JOIN_KINDS = ['JOIN', 'LEFT JOIN', 'RIGHT JOIN', 'FULL JOIN', 'LEFT JOIN', 'JOIN']


def joins(n, agg):
    aliases = []
    sql = f'FROM persons t1'
    aliases.append(('t1', 'persons'))
    for k in range(2, n + 1):
        table = NAMES[(k * 7) % len(NAMES)]
        kind = JOIN_KINDS[k % len(JOIN_KINDS)]
        j, jt = R.choice(aliases)
        off = R.choice(['', '', '', ' + 1', ' - 1'])
        on = f't{k}.id = {j}.id{off}'
        if R.random() < 0.3:
            on += ' AND ' + pred(f't{k}', table)
        sql += f' {kind} {table} t{k} ON {on}'
        aliases.append((f't{k}', table))
    where = ' AND '.join(pred(a, t) for a, t in R.sample(aliases, min(3, len(aliases))))
    picks = R.sample(aliases, min(6, len(aliases)))
    cols = []
    for i, (a, t) in enumerate(picks):
        col, _ = R.choice(TABLES[t])
        cols.append((f'{a}.{col}', f'c{i}'))
    if not agg:
        select = ', '.join(f'{e} AS {n_}' for e, n_ in [(f'{picks[0][0]}.id', 'k')] + cols)
        order = ', '.join(['k'] + [n_ for _, n_ in cols])
        return f'SELECT {select} {sql} WHERE {where} ORDER BY {order}'
    key = f'{picks[0][0]}.id'
    a2 = picks[-1][0]
    return (f'SELECT {key} AS k, count(*) AS n, count({a2}.id) AS m, max({cols[0][0]}) AS hi, '
            f'min({cols[-1][0]}) AS lo {sql} WHERE {where} GROUP BY {key} HAVING count(*) >= 1 '
            f'ORDER BY k NULLS FIRST, n, m, hi NULLS FIRST, lo NULLS FIRST')


for n in [2, 5, 10, 15, 20, 30, 40, 50]:
    add(f'join_{n}', joins(n, False))
    add(f'join_agg_{n}', joins(n, True))


# ---- B. FROM-subquery nesting
def from_nest(d):
    q = 'SELECT id, name, age FROM persons WHERE age IS NOT NULL'
    for i in range(1, d + 1):
        if i % 3 == 0:
            q = f'SELECT id, max(name) AS name, max(age) AS age FROM ({q}) q{i} GROUP BY id'
        elif i % 3 == 1:
            q = f'SELECT id, name, age + 1 AS age FROM ({q}) q{i} WHERE q{i}.id > 0'
        else:
            q = f'SELECT q{i}.id, q{i}.name, q{i}.age FROM ({q}) q{i} JOIN orders o{i} ON o{i}.id = q{i}.id'
    return f'SELECT id, name, age FROM ({q}) top ORDER BY id, name, age'


for d in [1, 5, 10, 20, 30, 50]:
    add(f'from_nest_{d}', from_nest(d))


# ---- C. IN nesting and correlated EXISTS chains
def in_nest(d):
    q = 'SELECT id FROM numbers WHERE int_val IS NOT NULL'
    for i in range(d):
        t = ['orders', 'persons', 'numbers', 'dates'][i % 4]
        q = f'SELECT id FROM {t} WHERE id IN ({q}) OR id = {i % 10 + 1}'
    return f'SELECT id, name FROM persons WHERE id IN ({q}) ORDER BY id, name'


def exists_nest(d):
    inner = ''
    prev = 'p'
    tabs = ['orders', 'numbers', 'strings', 'dates', 'departments']
    parts = []
    for i in range(1, d + 1):
        t = tabs[i % len(tabs)]
        outer_ref = 'p' if i % 4 == 0 else prev
        parts.append((f'e{i}', t, outer_ref))
        prev = f'e{i}'
    for a, t, ref in reversed(parts):
        cond = f'{a}.id = {ref}.id'
        inner = f'EXISTS (SELECT 1 FROM {t} {a} WHERE {cond}' + (f' AND {inner}' if inner else '') + ')'
    return f'SELECT p.id, p.name FROM persons p WHERE {inner} ORDER BY p.id, p.name'


for d in [1, 3, 5, 10, 20, 30]:
    add(f'in_nest_{d}', in_nest(d))
    add(f'exists_nest_{d}', exists_nest(d))


# ---- D. scalar subqueries in the select list, many and nested
def scalars(k):
    cols = []
    for i in range(k):
        t, c = [('orders', 'amount'), ('numbers', 'int_val'), ('departments', 'budget')][i % 3]
        key = 'person_id' if t == 'orders' else 'id'
        cols.append(f'(SELECT max(x{i}.{c}) FROM {t} x{i} WHERE x{i}.{key} = p.id + {i % 3}) AS s{i}')
    return f'SELECT p.id, {", ".join(cols)} FROM persons p ORDER BY p.id'


def scalar_nest(d):
    q = 'SELECT max(n.small_val) FROM numbers n WHERE n.id = p.id'
    for i in range(d):
        q = f'SELECT coalesce(({q}), 0) + 1'
    return f'SELECT p.id, ({q}) AS v FROM persons p ORDER BY p.id, v'


for k in [1, 5, 20, 50]:
    add(f'scalars_{k}', scalars(k))
for d in [1, 5, 10, 20]:
    add(f'scalar_nest_{d}', scalar_nest(d))


# ---- E. CTE chains that reuse earlier CTEs (a DAG)
def cte_chain(n):
    ctes = ['c1 AS (SELECT id, age, name FROM persons)']
    for i in range(2, n + 1):
        b = max(1, i // 2)
        ctes.append(f'c{i} AS (SELECT a.id, greatest(a.age, b.age) + 1 AS age, a.name FROM c{i - 1} a '
                    f'LEFT JOIN c{b} b ON b.id = a.id WHERE a.id > 0)')
    return f'WITH {", ".join(ctes)} SELECT id, age, name FROM c{n} ORDER BY id, age, name'


for n in [2, 5, 10, 20, 40]:
    add(f'cte_chain_{n}', cte_chain(n))


# ---- F. set-operation chains
def setops(n):
    parts = []
    for i in range(n):
        t = NAMES[i % len(NAMES)]
        parts.append(f'(SELECT id, {i % 4} AS tag FROM {t} WHERE id > {i % 5})')
    ops = ['UNION', 'UNION ALL', 'EXCEPT', 'INTERSECT', 'UNION']
    q = parts[0]
    for i, p in enumerate(parts[1:]):
        q = f'({q} {ops[i % len(ops)]} {p})'
    return f'SELECT id, tag FROM {q} u ORDER BY id, tag'


for n in [2, 5, 10, 20, 40]:
    add(f'setops_{n}', setops(n))


# ---- G. wide and deep expressions
for w in [10, 100, 500, 2000]:
    whens = ' '.join(f'WHEN age = {i} THEN {i * 2}' for i in range(w))
    add(f'case_{w}', f'SELECT id, CASE {whens} ELSE -1 END AS v FROM persons ORDER BY id, v')
    ors = ' OR '.join(f'id = {i}' for i in range(w))
    add(f'or_chain_{w}', f'SELECT id FROM persons WHERE {ors} ORDER BY id')
    plus = ' + '.join(['id'] + [str(i % 7) for i in range(w)])
    add(f'plus_chain_{w}', f'SELECT id, {plus} AS v FROM persons ORDER BY id, v')
    add(f'parens_{w}', f'SELECT id, {"(" * w}id + 1{")" * w} AS v FROM persons ORDER BY id, v')
    if w <= 500:
        cols = ', '.join(f'id + {i} AS c{i}' for i in range(w))
        add(f'select_width_{w}', f'SELECT {cols} FROM persons ORDER BY c0')
for w in [10, 100]:
    inl = ', '.join(str(i) for i in range(w))
    add(f'in_list_{w}', f'SELECT id FROM persons WHERE id IN ({inl}) AND age NOT IN ({inl}) ORDER BY id')
# nested casts, unaliased (the default name reads through each), integer within bigint within integer: added
# 2026-10-14 after the tenth line's find (ten casts were 9 MB of SQL)
def casts(n):
    e = 'age'
    for i in range(n):
        e = f'CAST({e} AS {"INTEGER" if i % 3 else "BIGINT"})'
    return e
for n in [10, 30, 100]:
    add(f'cast_nest_{n}', f'SELECT id, {casts(n)} FROM persons ORDER BY id')


# ---- H. many window functions over a join
def windows(k):
    fns = []
    for i in range(k):
        part = ['p.dept_id', 'o.status', 'p.active', '1'][i % 4]
        fn = ['row_number()', 'rank()', 'sum(o.amount)', 'count(*)', 'max(p.age)', 'lag(p.id)', 'dense_rank()'][i % 7]
        frame = ' ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING' if fn.startswith(('sum', 'count', 'max')) else ''
        fns.append(f'{fn} OVER (PARTITION BY {part} ORDER BY p.id, o.id{frame}) AS w{i}')
    return (f'SELECT p.id AS pid, o.id AS oid, {", ".join(fns)} FROM persons p JOIN orders o ON o.person_id = p.id '
            f'ORDER BY pid, oid')


for k in [1, 5, 20, 50]:
    add(f'windows_{k}', windows(k))


# ---- I. combined monsters
def monster(i):
    n = R.choice([8, 12, 16])
    ctes = f'WITH base AS ({joins(n, False).split(" ORDER BY")[0]}), agg AS (SELECT k, count(*) AS n FROM base GROUP BY k)'
    q = (f'{ctes} SELECT b.k, a.n, (SELECT count(*) FROM orders o WHERE o.person_id = b.k) AS orders_n, '
         f'rank() OVER (ORDER BY a.n DESC, b.k) AS r '
         f'FROM (SELECT DISTINCT k FROM base WHERE k IN (SELECT id FROM persons WHERE EXISTS '
         f'(SELECT 1 FROM orders o2 WHERE o2.person_id = persons.id))) b '
         f'LEFT JOIN agg a ON a.k = b.k ORDER BY b.k, a.n, orders_n, r')
    return q


for i in range(6):
    add(f'monster_{i}', monster(i))

with open(out, 'w') as f:
    for cid, sql in cases:
        f.write(f'{cid}\t{sql}\n')
print(len(cases), 'queries;', max(len(s) for _, s in cases), 'chars at most')
