"""THE ONE API LEDGERS, PYTHON'S HALF (docs/DATACUBE_ONE_API_PLAN_2026_10_21.md §3). Every known place where Python
keeps a DataCube rule of its own, writes a page by no command, or does a job by another rule than the browser's is
listed here by its item in the plan's register (§4), and only there. A new one fails here; a listed one that is gone
fails too, until its item is closed and its entry taken off; and each ledger's size is pinned, so growing one is an
edit of its ceiling, made only with a dated note. (The register is held to every ledger by
datacube/test/one-api-ledger.test.ts, which reads this file.)

  L4  every write of a whole page (``_unnoted``), and every assignment of the flags that decide how a page is written
      (``_unserved``, ``_dirty``), by where it is made
  L5  every write into a page's document -- the document, or a part of it reached through a handle (a tile's view, a
      grid's cube or query) or a local name bound to either -- by where it is made: from a headless answer (its
      right-hand side pinned), or a gap
  L6  Python's copies of TypeScript rules: each by its function, or by the code that is it (a marker)
  L7  derived state Python leaves as it was where the screen derives it again (a following chart, a selection, totals)
  L9  who writes the keeper at all (what it takes whole: a page born or loaded, N1, an undo or a redo, N2)
  L10 (Python's half) the same job done by another rule than the browser's

A behavioural entry asserts the gap as it is today: closing it makes the assertion fail, and its entry comes off."""

import ast
import copy
import json
import os
import tempfile
import unittest
from collections import Counter
from pathlib import Path

import pandas as pd

import legend_lite as ll
from legend_lite import datacube, engine as engine_module, page as page_module

PAGE = Path(page_module.__file__)
ENGINE = Path(engine_module.__file__)
SESSION = PAGE.with_name('datacube.py')
MODEL = PAGE.with_name('model.py')

ALLOWED = ()  # an entry with no item: allowed for good, its reason beside it

# each ledger's size, pinned: lower it with the fix that shrank it; raise it only with a dated note here
# 2026-10-21 (One API S4, docs/DATACUBE_ONE_API_PLAN_2026_10_21.md N1, N2): L9_KEEPER 6 -> 8 -- the page written whole
# is its own keeper write now (`restore`), split from `settle`, which takes commands only: Python's load (N1) and an
# open page's undo or redo (N2), both named exceptions the plan's end state keeps; signed off by the user, 2026-10-22 (D10).
# 2026-10-22 (S5, Y6): L9_KEEPER 8 -> 6 -- the engine's serve_page, a test-only hook, deleted with its two writes
CEILINGS = {'L4_WHOLE': 1, 'L4_FLAGS': 13, 'L5_DOCUMENT': 8, 'L6_FUNCTIONS': 8, 'MARKERS': 10, 'L9_KEEPER': 6,
            'BEHAVIOUR': 0}


def trades():
    return pd.DataFrame({'region': ['EMEA', 'AMER', 'EMEA'], 'desk': ['FX', 'Rates', 'Rates'],
                         'notional': [100.0, 200.0, 300.0]})


def _where(tree):
    """Each node of ``tree`` with where it is: its class and function, as ``Class.function`` (or ``function.inner``),
    and the innermost function it is in (for names bound in it)."""
    def walk(node, stack, scope):
        for child in ast.iter_child_nodes(node):
            named = isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))
            inner = stack + [child.name] if named else stack
            here = child if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)) else scope
            yield child, '.'.join(inner[-2:]) if inner else '(top)', here
            yield from walk(child, inner, here)
    return walk(tree, [], None)


def _held_to(test, found, ledger, what, ceiling):
    """``found`` (a Counter of sites) against ``ledger`` (site -> (items, count, ...)): no site unlisted, none listed
    gone; and the ledger's size its pinned ceiling."""
    problems = []
    for site, n in found.items():
        if site not in ledger:
            problems.append(f'{what}: {site} ({n}) is a new gap -- make it a command or a headless answer, or list it')
        elif ledger[site][1] != n:
            problems.append(f'{what}: {site} is found {n} times, listed {ledger[site][1]}')
    problems += [f'{what}: {site} is listed but gone -- close its items and take it off' for site in ledger
                 if site not in found]
    test.assertEqual(problems, [])
    test.assertEqual(len(ledger), CEILINGS[ceiling], f'{ceiling} holds {len(ledger)} entries, its ceiling says '
                     f'{CEILINGS[ceiling]}: lower it with the fix; raise it only with a dated note')


# -- L4 ------------------------------------------------------------------------------------------------------------

L4_WHOLE = {
    'Page.load': (ALLOWED, 1),  # N1: a page born whole
}

# every assignment of the write's flags, by where and what: `_unserved = None` is a whole write as `_unnoted` is
L4_FLAGS = {
    'Page._unnoted:_unserved=None': (ALLOWED, 1),  # the one place a page is written whole; its callers are L4_WHOLE
    'Page.__init__:_unserved=[]': (ALLOWED, 1),
    'Page.__init__:_dirty=False': (ALLOWED, 1),
    'Page._doc:_dirty=True': (ALLOWED, 1),  # every document assignment goes through the property (L5)
    'Page._pull:_unserved=[]': (ALLOWED, 1),
    'Page._discard:_dirty=False': (ALLOWED, 1),
    'Page._discard:_unserved=[]': (ALLOWED, 1),
    'Page._commit:_dirty=False': (ALLOWED, 1),
    'Page._commit:_unserved=[]': (ALLOWED, 1),
    'Page.grid:_dirty=was[5]': (ALLOWED, 1),  # a refused call puts back what was
    'Page.grid:_unserved=was[4]': (ALLOWED, 1),
    'Page.join:_dirty=was[4]': (ALLOWED, 1),
    'Page.join:_unserved=was[3]': (ALLOWED, 1),
}

# -- L5 ------------------------------------------------------------------------------------------------------------

# site -> (items, count, the right-hand side an allowed one assigns: a headless answer, pinned)
L5_DOCUMENT = {
    'Page.__init__': (ALLOWED, 1, "_plain(_json.loads(said['page']))"),  # headless pageNew's page
    'Page._doc': (ALLOWED, 1, 'doc'),  # the property itself: every other assignment is through it
    'Page._pull': (ALLOWED, 1, '(doc, version)'),  # the keeper's copy: what the commands made (headless pageRebase), or a restore read by pageWritten
    'Page._apply_page': (ALLOWED, 1, 'page'),  # headless pageApply's page (its binding pinned in MARKERS)
    'Page.grid': (ALLOWED, 1, '(was[0], was[1])'),  # a refused call puts back the page as it was
    'Page.join': (ALLOWED, 1, '(was[0], was[1])'),
    'Page.load': (ALLOWED, 1, 'doc'),  # N1: headless pageRead's page
    'Grid._kept': (ALLOWED, 1, "_plain(_json.loads(kept['page']))"),  # headless pageWithCube's page
}

ROOTS = {'_doc', '_document', '_view', '_cube', '_query'}
MUTATORS = {'append', 'extend', 'pop', 'update', 'insert', 'remove', 'clear', 'setdefault', 'popitem'}


def _root(target):
    """What a target writes into: the attribute that is a document root (``self._doc``, a handle's ``_view``), the
    name it starts from, or None."""
    while isinstance(target, (ast.Subscript, ast.Attribute)):
        if isinstance(target, ast.Attribute) and target.attr in ROOTS:
            return target
        target = target.value
    return target if isinstance(target, ast.Name) else None


class Static(unittest.TestCase):
    """L4, L5, L6, L9's writers: read from Python's own sources, by Python's own parser."""

    def setUp(self):
        self.tree = ast.parse(PAGE.read_text('utf-8'))

    def test_l4_every_whole_page_write_and_every_write_flag_is_listed(self):
        found = Counter(where for node, where, _ in _where(self.tree)
                        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                        and node.func.attr == '_unnoted')
        _held_to(self, found, L4_WHOLE, 'page.py _unnoted()', 'L4_WHOLE')
        flags = Counter()
        for node, where, _ in _where(self.tree):
            if isinstance(node, (ast.Assign, ast.AnnAssign)):
                for target in (node.targets if isinstance(node, ast.Assign) else [node.target]):
                    for one in (target.elts if isinstance(target, ast.Tuple) else [target]):
                        if isinstance(one, ast.Attribute) and one.attr in ('_dirty', '_unserved'):
                            value = node.value
                            if isinstance(target, ast.Tuple) and isinstance(value, ast.Tuple):
                                value = value.elts[target.elts.index(one)]
                            flags[f'{where}:{one.attr}={ast.unparse(value)}'] += 1
        _held_to(self, flags, L4_FLAGS, "page.py's write flags", 'L4_FLAGS')

    def test_l5_every_write_into_a_page_document_is_listed(self):
        found, sides = Counter(), {}
        aliases = {}  # function -> names bound in it from a document root
        for node, _, scope in _where(self.tree):
            if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
                r = _root(node.value) if isinstance(node.value, (ast.Subscript, ast.Attribute)) else None
                if isinstance(r, ast.Attribute) and r.attr in ROOTS:
                    aliases.setdefault(scope, set()).add(node.targets[0].id)

        def writes(target, scope, whole):
            """Whether ``target`` writes the document: a root assigned whole, or anything under a root or an alias."""
            if whole and isinstance(target, ast.Attribute) and target.attr in ('_doc', '_document'):
                return True
            if not isinstance(target, ast.Subscript):
                return False
            r = _root(target)
            return (isinstance(r, ast.Attribute) and r.attr in ROOTS) or (
                isinstance(r, ast.Name) and r.id in aliases.get(scope, ()))

        for node, where, scope in _where(self.tree):
            if isinstance(node, (ast.Assign, ast.AugAssign, ast.AnnAssign)):
                for target in (node.targets if isinstance(node, ast.Assign) else [node.target]):
                    for one in (target.elts if isinstance(target, ast.Tuple) else [target]):
                        if writes(one, scope, True):
                            found[where] += 1
                            sides[where] = ast.unparse(node.value)
            if isinstance(node, ast.Delete) and any(writes(t, scope, False) for t in node.targets):
                found[where] += 1
            if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr in MUTATORS
                    and writes(ast.Subscript(value=node.func.value, slice=ast.Constant(0)), scope, False)):
                found[where] += 1
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == 'setattr' \
                    and len(node.args) > 1 and isinstance(node.args[1], ast.Constant) and node.args[1].value in ROOTS:
                found[where] += 1
            if isinstance(node, ast.Subscript) and isinstance(node.value, ast.Attribute) and node.value.attr == '__dict__':
                found[where] += 1
        _held_to(self, found, L5_DOCUMENT, 'page.py document', 'L5_DOCUMENT')
        moved = [f'{site}: assigns {sides.get(site)!r}, pinned {side!r}' for site, (items, _, side) in L5_DOCUMENT.items()
                 if side is not None and sides.get(site) != side]
        self.assertEqual(moved, [], "an allowed assignment's right-hand side changed: is it still a headless answer?")

    def test_l6_pythons_copies_of_typescript_rules_are_listed(self):
        defined = {node.name for node, _, _ in _where(self.tree) if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))}
        gone = [f'{name} ({", ".join(items)})' for name, items in L6_FUNCTIONS.items() if name not in defined]
        self.assertEqual(gone, [], 'gone from page.py: close their items and take them off')
        self.assertTrue(MODEL.exists(), 'model.py is gone: close Z7 and take it off')
        self.assertEqual(len(L6_FUNCTIONS), CEILINGS['L6_FUNCTIONS'])

    def test_markers_the_code_that_is_each_gap(self):
        problems = []
        for path, text, items, count in MARKERS:
            found = path.read_text('utf-8').count(text)
            if found != count:
                problems.append(f'{path.name} holds {text!r} {found} times, not {count}: fixed? close '
                                f'{", ".join(items)} and take it off; moved? pin it again')
        self.assertEqual(problems, [])
        self.assertEqual(len(MARKERS), CEILINGS['MARKERS'])

    def test_l9_who_writes_the_keeper(self):
        found = Counter()
        for path in (PAGE, ENGINE, SESSION):
            for node, where, _ in _where(ast.parse(path.read_text('utf-8'))):
                if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                        and node.func.attr in ('keep', 'write', 'settle', 'restore', 'forget')
                        and 'keeper' in ast.unparse(node.func.value)):
                    found[f'{path.name}:{where}:{node.func.attr}'] += 1
        _held_to(self, found, L9_KEEPER, 'keeper writers', 'L9_KEEPER')


# -- L6 ------------------------------------------------------------------------------------------------------------

L6_FUNCTIONS = {
    '_ids': ('Z2',),
    '_class': ('Z4',),
    '_facts': ('Z5',),
    '_column_choices': ('Z7',),
    '_end': ('Z7',),
    '_value': ('Z12',),
    '_given_filter': ('Z12',),
    '_copy_base_of': ('Z15',),
}

# (file, the code that is the gap, its items, how many times it is there)
MARKERS = [
    (PAGE, "path = next((c for c in made_classes if c.rsplit('::', 1)[-1] == class_name), None)", ('Z6',), 1),
    (PAGE, "*({'kind': 'column', 'name': d, 'change': {'kind': 'dimension'}} for d in dimensions),", ('Z8',), 1),
    (PAGE, "'near': None, 'title': title, 'columns': None}], 'a title')", ('Z9',), 1),
    (PAGE, 'which is no frame (a page in Python is over ', ('Z14', 'B7'), 1),
    (ENGINE, 'self._claimed: set[str] = set()', ('Z17',), 1),
    (PAGE, "record = _json.dumps({'id': id, 'name': named, 'content': self._written()}, indent=None)", ('Z18', 'B4'), 1),
    (PAGE, "def __init__(self, name: str = 'Untitled page') -> None:", ('B3',), 1),
    (PAGE, "Path(path).write_text(self.to_json() + '\\n', 'utf-8', newline='')", ('B5',), 1),
    (PAGE, 'id = self._saved if self._saved is not None else str(uuid.uuid4())', ('B13',), 1),
    # the page _apply_page assigns is headless pageApply's, as read (L5 pins the assignment, this its binding)
    (PAGE, "page = _plain(_json.loads(said['page']))", ALLOWED, 1),
]

# -- L9 ------------------------------------------------------------------------------------------------------------

L9_KEEPER = {
    'page.py:Page.__init__:keep': (ALLOWED, 1),  # N1: a page born, as headless pageNew made it
    'page.py:Page._commit:settle': (ALLOWED, 1),  # every Python write: its commands, the keeper keeping what they make
    'page.py:Page._commit:restore': (ALLOWED, 1),  # N1: a page loaded, written whole at the version it was made at
    'engine.py:Engine._page_said:settle': (ALLOWED, 1),  # an open page's write: its commands, applied (H1)
    'engine.py:Engine._page_said:restore': (ALLOWED, 1),  # N2: an open page's undo or redo, at its version only
    'datacube.py:_Session.sweep:forget': (ALLOWED, 1),  # a page gone: no longer kept
}


# -- L7, L9, L10: behaviour, as it is today ------------------------------------------------------------------------

# each behavioural gap by its items: the test that holds it, which asserts the gap as it is (closing it fails the test)
BEHAVIOUR = {}  # B8 closed in S5 (OneRule)


class Session(unittest.TestCase):
    """A session of its own (a site of its own), its pages closed after."""

    def setUp(self):
        self._site = os.environ.get('LEGEND_LITE_SITE')
        os.environ['LEGEND_LITE_SITE'] = tempfile.mkdtemp(dir=os.environ.get('TEST_TMPDIR'))
        datacube._session = datacube._Session()

    def tearDown(self):
        for view in list(datacube._session.pages):
            view.close()
        if datacube._session._headless is not None:
            datacube._session._headless.close()
        if self._site is None:
            os.environ.pop('LEGEND_LITE_SITE', None)
        else:
            os.environ['LEGEND_LITE_SITE'] = self._site

    def served(self, name):
        """A Python page of one grid, served as a tab or a widget would open it; the engine that answers its writes."""
        page = ll.Page(name)
        page.grid(trades(), name='t')
        datacube._session.keeper.serve(page._key, True)
        return page, datacube._session.engine()

    def written(self, engine, page, write, version=None):
        """An open page's write, as the tab sends it -- ``{edits}`` or ``{restore}`` -- at the version the keeper has now,
        unless given."""
        version = datacube._session.keeper.current(page._key)[0] if version is None else version
        return engine._page_said(f'page={page._key}&version={version}', json.dumps(write))


class HostApplies(Session):
    """L9 (closed in S4: docs/DATACUBE_ONE_API_PLAN_2026_10_21.md H1-H3, B12): the page's keeper keeps what an open page's
    commands make, never a page it sends -- but an undo or a redo, a restore kept at its version only (N2)."""

    def test_h1_an_open_pages_write_is_kept_as_its_commands_make_it(self):
        page, engine = self.served('applied')
        said = self.written(engine, page, {'edits': [{'page': {'kind': 'renamePage', 'name': 'said'}}]})
        self.assertEqual(said[0], 200, said)
        self.assertEqual(datacube._session.keeper.current(page._key)[1]['name'], 'said')
        self.assertEqual(page.name, 'said')
        # a page sent with its edits is no write the host takes
        sent = self.written(engine, page, {'page': {**page.to_dict(), 'name': 'sent'}, 'edits': []})
        self.assertEqual(sent[0], 400, sent)

    def test_h2_n2_a_restore_is_kept_at_its_version_only(self):
        page, engine = self.served('restored')
        at = datacube._session.keeper.current(page._key)[0]
        said = self.written(engine, page, {'restore': {**page.to_dict(), 'name': 'undone'}})
        self.assertEqual(said[0], 200, said)
        self.assertEqual(page.name, 'undone')
        # behind the page (Python moved it since): refused, nothing kept
        page.name = 'moved in Python'
        stale = self.written(engine, page, {'restore': {**page.to_dict(), 'name': 'undone again'}}, version=at + 1)
        self.assertEqual(stale[0], 409, stale)
        self.assertEqual(page.name, 'moved in Python')

    def test_h3_b12_an_id_given_and_taken_off_within_one_write_is_held_and_said(self):
        page, engine = self.served('held')
        edits = [{'page': {'kind': 'addSheet', 'id': 'sheet-9', 'name': 'Briefly'}},
                 {'page': {'kind': 'removeSheet', 'sheet': 'sheet-9'}}]
        said = self.written(engine, page, {'edits': edits})
        self.assertEqual(said[0], 200, said)
        self.assertIn('sheet-9', datacube._session.keeper.held(page._key), 'every id its commands gave out is held')
        # and an open page reads them with the page: it gives none out again
        answer = engine.answer('GET', '/page.json', f'page={page._key}', None)
        self.assertIn('sheet-9', json.loads(answer.body)['held'])
        # and Python, which takes the keeper's held ids with the page: its next sheet is past every one held (the S4 audit's
        # 5: a sheet given out from what the page shows alone would be sheet-2)
        self.assertEqual(page.sheet('Mine').id, 'sheet-10')

    def test_a_grid_the_host_cannot_hold_is_refused_as_the_write_adds_it(self):
        # (the S4 audit's 2) a page on Python holds grids over its frames and joins of them (headless grid.ts onHost, until
        # S5): one over any other source is refused as the write adds it, in DataCube's words, and nothing is kept
        page, engine = self.served('holds')
        at = datacube._session.keeper.current(page._key)[0]
        cube = copy.deepcopy(page.to_dict()['cubes'][0]['cube'])
        cube['source'] = {'_type': 'class', 'name': 'Trade', 'class': 'trades::domain::Trade', 'project': 'datacube:trades',
                          'workspace': 'datacube', 'revision': '3', 'mapping': 'trades::mapping::Model',
                          'runtime': 'trades::runtime::Browser', 'columns': cube['source']['columns']}
        said = self.written(engine, page, {'edits': [{'page': {'kind': 'addGrid', 'document': cube, 'sheet': 'sheet-1',
                                                                'near': None, 'title': None, 'columns': None}}]})
        self.assertEqual(said.status, 409, said)
        self.assertIn('is no frame and no join of frames: a page on Python reads frames', said.body.decode('utf-8'))
        self.assertEqual(datacube._session.keeper.current(page._key)[0], at, 'nothing kept')

    def test_a_failure_of_the_librarys_is_said_as_one_never_as_a_refusal(self):
        # a write the keeper failed on -- a bug, not a refusal -- is a 500 that names it: never "no such page" (a KeyError)
        # nor a refusal the open page reopens on
        page, engine = self.served('failed')

        def broken(*_):
            raise KeyError('a bug of the keeper\'s')
        datacube._session.keeper.combine = broken
        said = self.written(engine, page, {'edits': [{'page': {'kind': 'renamePage', 'name': 'never'}}]})
        self.assertEqual(said.status, 500, said)
        self.assertIn('KeyError', said.body.decode('utf-8'))


class SameJob(Session):
    """L10, Python's half: the same job done by another rule than the browser's (none open: B8 closed in S5)."""


class OneRule(Session):
    """L10 closed: the job done by DataCube's one rule, as the browser does it."""

    def test_b8_a_detached_chart_has_no_grid_to_follow_in_python_as_on_the_screen(self):
        page = ll.Page('detached')
        grid = page.grid(trades(), name='t', rows=['region'], measures={'notional': 'sum'})
        page.grid(trades(), name='u')
        chart = grid.chart('bar', x='desk')
        page.remove(grid)
        self.assertTrue(page.to_dict()['views'][-1]['spec']['frozen'], 'detached: frozen, its grid gone')
        # DataCube's setChart refuses it, in its words: the screen's pill is off for a detached chart
        with self.assertRaisesRegex(ValueError, "^'chart-1' is detached -- its grid was removed, and it keeps its own copy of "
                                                "that grid's query -- so it has no grid to follow$"):
            chart.plot(frozen=False)
        self.assertTrue(page.to_dict()['views'][-1]['spec']['frozen'], 'still frozen')


class Ledger(unittest.TestCase):
    def test_each_behavioural_gap_names_a_test_that_holds_it(self):
        tests = {name for case in (SameJob,) for name in dir(case) if name.startswith('test_')}
        self.assertEqual(sorted(set(BEHAVIOUR) - tests), [], 'listed, but no such test')
        self.assertEqual(sorted(tests - set(BEHAVIOUR)), [], 'a behavioural test that names no item')
        self.assertEqual(len(BEHAVIOUR), CEILINGS['BEHAVIOUR'])


if __name__ == '__main__':
    unittest.main()
