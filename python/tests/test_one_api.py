"""THE ONE API LEDGERS, PYTHON'S HALF (docs/DATACUBE_ONE_API_PLAN_2026_10_21.md §3). Every known place where Python
keeps a DataCube rule of its own, writes a page by no command, or does a job by another rule than the browser's is
listed here by its item in the plan's register (§4), and only there. A new one fails here; a listed one that is gone
fails too, until its item is closed and its entry taken off: the lists only shrink, and stay true. (The register is
held to every ledger by datacube/test/one-api-ledger.test.ts, which reads this file.)

  L4  every write of a whole page (``_unnoted``), by where it is made
  L5  every assignment to a page's document, by where it is made: from a headless answer, or a gap
  L6  Python's copies of TypeScript rules: each by its function, or by the code that is it (a marker)
  L7  derived state Python leaves as it was where the screen derives it again (a following chart, a selection, totals)
  L9  what the page's keeper takes whole
  L10 (Python's half) the same job done by another rule than the browser's

A behavioural entry asserts the gap as it is today: closing it makes the assertion fail, and its entry comes off."""

import ast
import json
import os
import tempfile
import unittest
from collections import Counter
from pathlib import Path

import pandas as pd

import legend_lite as ll
from legend_lite import datacube, engine as engine_module, page as page_module
from legend_lite.keeper import PageKeeper

PAGE = Path(page_module.__file__)
ENGINE = Path(engine_module.__file__)
MODEL = PAGE.with_name('model.py')

ALLOWED = ()  # an entry with no item: allowed for good, its reason beside it


def trades():
    return pd.DataFrame({'region': ['EMEA', 'AMER', 'EMEA'], 'desk': ['FX', 'Rates', 'Rates'],
                         'notional': [100.0, 200.0, 300.0]})


def _where(tree):
    """Each node of ``tree`` with where it is: its class and function, as ``Class.function`` (or ``function.inner``)."""
    def walk(node, stack):
        for child in ast.iter_child_nodes(node):
            named = isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))
            inner = stack + [child.name] if named else stack
            yield child, '.'.join(inner[-2:]) if inner else '(top)'
            yield from walk(child, inner)
    return walk(tree, [])


def _held_to(test, found, ledger, what):
    """``found`` (a Counter of sites) against ``ledger`` (site -> (items, count)): no site unlisted, none listed gone."""
    problems = []
    for site, n in found.items():
        if site not in ledger:
            problems.append(f'{what}: {site} ({n}) is a new gap -- make it a command or a headless answer, or list it')
        elif ledger[site][1] != n:
            problems.append(f'{what}: {site} is found {n} times, listed {ledger[site][1]}')
    problems += [f'{what}: {site} is listed but gone -- close its items and take it off' for site in ledger
                 if site not in found]
    test.assertEqual(problems, [])


# -- L4 ------------------------------------------------------------------------------------------------------------

L4_WHOLE = {
    'Grid._kept': (('Y3',), 1),
    '_into.made': (('Y1',), 1),
    'Page._into': (('Y1',), 1),
    'Page._keep': (('Y1',), 1),
    'Page._reopened': (('Y2',), 1),
    'Page.load': (ALLOWED, 1),  # N1: a page born whole
}

# -- L5 ------------------------------------------------------------------------------------------------------------

L5_DOCUMENT = {
    'Page.__init__': (ALLOWED, 1),  # the page as headless pageNew made it
    'Page._doc': (ALLOWED, 1),  # the property itself: every other assignment is through it
    'Page._pull': (ALLOWED, 1),  # the keeper's copy (headless's, or an open page's read by pageWritten)
    'Page._apply_page': (ALLOWED, 1),  # headless pageApply's answer
    'Page.grid': (ALLOWED, 1),  # a refused call puts back the page as it was
    'Page.join': (ALLOWED, 1),  # the same
    'Page.load': (ALLOWED, 1),  # N1: headless pageRead's page
    'Grid._kept': (('Y3', 'Z1'), 1),
    '_into.made': (('Y1', 'Z3'), 1),
    'Page._into': (('Y1', 'Z3'), 1),
    'Page._keep': (('Y1', 'Z3'), 1),
    'Page._reopened': (('Y2', 'Z1', 'Z10'), 1),
}

MUTATORS = {'append', 'extend', 'pop', 'update', 'insert', 'remove', 'clear', 'setdefault'}


def _document(target):
    while isinstance(target, ast.Subscript):
        target = target.value
    return isinstance(target, ast.Attribute) and target.attr in ('_doc', '_document')


class Static(unittest.TestCase):
    """L4, L5, L6: read from Python's own sources, by Python's own parser."""

    def setUp(self):
        self.tree = ast.parse(PAGE.read_text('utf-8'))

    def test_l4_every_whole_page_write_is_listed(self):
        found = Counter(where for node, where in _where(self.tree)
                        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                        and node.func.attr == '_unnoted')
        _held_to(self, found, L4_WHOLE, 'page.py _unnoted()')

    def test_l5_every_assignment_to_a_page_document_is_listed(self):
        found = Counter()
        for node, where in _where(self.tree):
            if isinstance(node, (ast.Assign, ast.AugAssign, ast.AnnAssign)):
                targets = node.targets if isinstance(node, ast.Assign) else [node.target]
                for target in targets:
                    for one in (target.elts if isinstance(target, ast.Tuple) else [target]):
                        if _document(one):
                            found[where] += 1
            if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr in MUTATORS
                    and _document(node.func.value)):
                found[where] += 1
        _held_to(self, found, L5_DOCUMENT, 'page.py document')

    def test_l6_pythons_copies_of_typescript_rules_are_listed(self):
        defined = {node.name for node, _ in _where(self.tree) if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))}
        gone = [f'{name} ({", ".join(items)})' for name, items in L6_FUNCTIONS.items() if name not in defined]
        self.assertEqual(gone, [], 'gone from page.py: close their items and take them off')
        self.assertTrue(MODEL.exists(), 'model.py is gone: close Z7 and take it off')

    def test_markers_the_code_that_is_each_gap(self):
        problems = []
        for path, text, items, count in MARKERS:
            found = path.read_text('utf-8').count(text)
            if found != count:
                problems.append(f'{path.name} holds {text!r} {found} times, not {count}: fixed? close '
                                f'{", ".join(items)} and take it off; moved? pin it again')
        self.assertEqual(problems, [])


# -- L6 ------------------------------------------------------------------------------------------------------------

L6_FUNCTIONS = {
    '_with_cube': ('Z1',),
    '_ids': ('Z2',),
    '_into': ('Z3',),
    '_keep': ('Z3',),
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
    (PAGE, "if not changed and cube['source'] == held['source']:", ('Z10',), 1),
    (PAGE, "if applied and not said['notes'] and cube.get('source') == source:", ('Z11',), 1),
    (PAGE, "raise ValueError('a stack is two tiles or more')", ('Z13',), 1),
    (PAGE, 'which is no frame (a page in Python is over ', ('Z14', 'B7'), 1),
    (ENGINE, 'row snap limit. Narrow the filter, ', ('Z16',), 2),
    (ENGINE, 'self._claimed: set[str] = set()', ('Z17',), 1),
    (PAGE, "record = _json.dumps({'id': id, 'name': named, 'content': self._written()}, indent=None)", ('Z18', 'B4'), 1),
    (ENGINE, "reads = {'frames': sorted(set(frames)), 'copies': [], 'ids': []}", ('Y6',), 1),
    (PAGE, "def __init__(self, name: str = 'Untitled page') -> None:", ('B3',), 1),
    (PAGE, "Path(path).write_text(self.to_json() + '\\n', 'utf-8', newline='')", ('B5',), 1),
    (PAGE, '(DataCube shows no empty page)', ('B6',), 1),
    (PAGE, 'id = self._saved if self._saved is not None else str(uuid.uuid4())', ('B13',), 1),
    (PAGE, '-- in Python, page.grid(frame) adds one', ('B14',), 1),
]


# -- L7, L9, L10: behaviour, as it is today ------------------------------------------------------------------------

# each behavioural gap by its items: the test that holds it, which asserts the gap as it is (closing it fails the test)
BEHAVIOUR = {
    'test_v1_a_following_chart_keeps_its_old_grouping_when_its_grid_is_regrouped': ('V1',),
    'test_v2_a_charts_selection_stays_when_its_grids_filter_no_longer_holds_it': ('V2',),
    'test_g3_root_totals_set_by_configure_do_not_reach_the_grids_tree': ('G3',),
    'test_h1_a_write_at_the_version_now_is_kept_as_sent_not_as_its_commands_make_it': ('H1',),
    'test_h2_a_write_by_no_command_is_taken_at_the_version_now': ('H2',),
    'test_h3_an_id_given_and_taken_off_within_one_write_is_not_held': ('H3', 'B12'),
    'test_b8_a_detached_chart_goes_live_in_python_and_not_on_the_screen': ('B8',),
}

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


class Derived(Session):
    """L7: derived state the screen derives again on every change, and Python leaves as it was."""

    def test_v1_a_following_chart_keeps_its_old_grouping_when_its_grid_is_regrouped(self):
        page = ll.Page('V1')
        grid = page.grid(trades(), name='t', rows=['region'], measures={'notional': 'sum'})
        grid.chart('bar')
        grid.group('desk')
        spec = page.to_dict()['views'][-1]['spec']
        self.assertNotIn('frozen', spec, 'the chart follows its grid')
        # the screen's chart follows the grid's new rows (followCube); Python's keeps the old
        self.assertEqual(spec['x'], 'region', "V1 open: Python's page keeps a following chart's grouping as it was")

    def test_v2_a_charts_selection_stays_when_its_grids_filter_no_longer_holds_it(self):
        page = ll.Page('V2')
        grid = page.grid(trades(), name='t', rows=['region'], measures={'notional': 'sum'})
        grid.chart('bar')
        doc = page.to_dict()
        mark = {'kind': 'condition', 'column': 'region', 'operator': 'equal', 'value': 'EMEA'}
        doc['views'][-1]['selection'] = [mark]
        doc['cubes'][0]['cube']['query']['filter'] = mark
        loaded = ll.Page.load(doc, frames={'t': trades()})
        loaded.grids[0].filter(None)
        # the screen takes a selection off with the filter that held it (cube-page.ts reconcile); Python keeps it
        self.assertEqual(loaded.to_dict()['views'][-1].get('selection'), [mark],
                         "V2 open: Python's page keeps a selection its grid's filter no longer holds")

    def test_g3_root_totals_set_by_configure_do_not_reach_the_grids_tree(self):
        page = ll.Page('G3')
        grid = page.grid(trades(), name='t', rows=['region'], measures={'notional': 'sum'})
        grid.configure({'showRootAggregation': True})
        cube = page.to_dict()['cubes'][0]['cube']
        self.assertTrue(cube['configuration']['showRootAggregation'])
        # the screen's Properties moves the tree with the setting; headless keeps the tree, so no total row shows
        self.assertFalse(cube['tree']['showTotals'], "G3 open: Python's configure leaves the tree's totals off")


class HostWrites(Session):
    """L9: what the page's keeper takes whole."""

    def test_h1_a_write_at_the_version_now_is_kept_as_sent_not_as_its_commands_make_it(self):
        keeper = PageKeeper()
        keeper.keep('p', {'name': 'before'}, {'frames': [], 'copies': [], 'ids': []}, [])
        keeper.settle('p', 1, {'name': 'sent'}, [{'page': {'kind': 'renamePage', 'name': 'said'}}],
                      {'frames': [], 'copies': [], 'ids': []}, [])
        self.assertEqual(keeper.current('p')[1]['name'], 'sent',
                         'H1 open: the keeper keeps the page as sent, whatever its commands make')

    def test_h2_a_write_by_no_command_is_taken_at_the_version_now(self):
        keeper = PageKeeper()
        keeper.keep('p', {'name': 'before'}, {'frames': [], 'copies': [], 'ids': []}, [])
        settled = keeper.settle('p', 1, {'name': 'whole'}, None, {'frames': [], 'copies': [], 'ids': []}, [])
        self.assertEqual(settled.version, 2, 'H2 open: a write by no command is taken whole')

    def test_h3_an_id_given_and_taken_off_within_one_write_is_not_held(self):
        page = ll.Page('H3')
        page.grid(trades(), name='t')
        datacube._session.keeper.serve(page._key, True)
        engine = datacube._session.engine()
        version = datacube._session.keeper.current(page._key)[0]
        edits = [{'page': {'kind': 'addSheet', 'id': 'sheet-9', 'name': 'Briefly'}},
                 {'page': {'kind': 'removeSheet', 'sheet': 'sheet-9'}}]
        said = engine._page_said(f'page={page._key}&version={version}',
                                 json.dumps({'page': page.to_dict(), 'edits': edits}))
        self.assertEqual(said[0], 200, said)
        self.assertNotIn('sheet-9', datacube._session.keeper.held(page._key),
                         'H3, B12 open: the ids held are the written page\'s, not every one its commands gave out')


class SameJob(Session):
    """L10, Python's half: the same job done by another rule than the browser's."""

    def test_b8_a_detached_chart_goes_live_in_python_and_not_on_the_screen(self):
        page = ll.Page('B8')
        grid = page.grid(trades(), name='t', rows=['region'], measures={'notional': 'sum'})
        page.grid(trades(), name='u')
        chart = grid.chart('bar', x='desk')
        page.remove(grid)
        self.assertTrue(page.to_dict()['views'][-1]['spec']['frozen'], 'detached: frozen, its grid gone')
        chart.plot(frozen=False)
        self.assertNotIn('frozen', page.to_dict()['views'][-1]['spec'],
                         'B8 open: Python lets a detached chart follow again; the screen does not (its pill is off)')


class Ledger(unittest.TestCase):
    def test_each_behavioural_gap_names_a_test_that_holds_it(self):
        tests = {name for case in (Derived, HostWrites, SameJob) for name in dir(case) if name.startswith('test_')}
        self.assertEqual(sorted(set(BEHAVIOUR) - tests), [], 'listed, but no such test')
        self.assertEqual(sorted(tests - set(BEHAVIOUR)), [], 'a behavioural test that names no item')


if __name__ == '__main__':
    unittest.main()
