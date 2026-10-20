"""ONE KEEPER PER PAGE (legend_lite/keeper.py; docs/DATACUBE_PAGE_KEEPER_2026_10_20.md): a page written only against
the version it is at, each version with the commands that moved it there, a write that changes nothing no version; and
-- the guard of the whole design -- a page Python and an open page both edit, in any interleaving, never two pages: after
every step Python's page, the page the open page holds and the keeper's are one, and the copies held are exactly those
the kept pages read.

The open page here is DataCube's own functions run headless (pageApply, gridApply: what the tab runs), writing as the
tab writes (page.json, against the version it holds) and following as it follows (page.json?since=: the commands, or
the page)."""

import json
import os
import random
import tempfile
import unittest

import pandas as pd

import legend_lite as ll
from legend_lite import _json, datacube
from legend_lite.keeper import PageKeeper, Stale

# how many interleavings, and of how many steps: the seed of a failure is printed, so it can be run alone
SEEDS = int(os.environ.get('LEGEND_LITE_KEEPER_SEEDS', '6'))
STEPS = 14


class Keeper(unittest.TestCase):
    """The keeper alone."""

    def test_a_write_against_the_version_it_is_at_moves_it_one_and_against_another_is_refused(self):
        told = []
        keeper = PageKeeper(on_write=lambda key, version: told.append((key, version)))
        self.assertEqual(keeper.keep('p', {'name': 'a'}, ['f']), 1)
        self.assertEqual(keeper.write('p', 1, {'name': 'b'}, ['f'], [{'page': 'x'}]), 2)
        with self.assertRaises(Stale) as stale:
            keeper.write('p', 1, {'name': 'c'}, ['f'], None)
        self.assertEqual(stale.exception.version, 2)
        self.assertEqual(keeper.current('p'), (2, {'name': 'b'}))
        # a write that changes nothing is no version, and told to nobody
        self.assertEqual(keeper.write('p', 2, {'name': 'b'}, ['f'], [{'page': 'y'}]), 2)
        self.assertEqual(told, [('p', 2)])
        with self.assertRaises(KeyError):
            keeper.write('nope', 1, {}, [], None)

    def test_what_moved_it_since_a_version_is_its_commands_or_none(self):
        keeper = PageKeeper()
        keeper.keep('p', {'n': 0}, [])
        self.assertEqual(keeper.changes('p', 1), (1, []))
        self.assertEqual(keeper.changes('p', 0), (1, None), 'kept from version 1: nothing moved it there')
        keeper.write('p', 1, {'n': 1}, [], ['a'])
        keeper.write('p', 2, {'n': 2}, [], ['b', 'c'])
        self.assertEqual(keeper.changes('p', 1), (3, ['a', 'b', 'c']))
        keeper.write('p', 3, {'n': 3}, [], None)
        self.assertEqual(keeper.changes('p', 2), (4, None), 'a change by no command since')
        self.assertEqual(keeper.changes('p', 4), (4, []))
        self.assertEqual(keeper.changes('p', 9), (4, None), 'a version it never had')

    def test_its_document_is_its_own(self):
        keeper = PageKeeper()
        doc = {'views': [{'id': 'grid-1'}]}
        keeper.keep('p', doc, [])
        doc['views'].append({'id': 'grid-2'})
        self.assertEqual(keeper.current('p')[1], {'views': [{'id': 'grid-1'}]}, 'a write is copied in')

    def test_the_copies_the_pages_kept_read(self):
        keeper = PageKeeper()
        keeper.keep('p', {'copies': {'c1': {'table': 'snap'}}}, [])
        keeper.keep('q', {'copies': {'c1': {'table': 'snap_2'}}}, [])
        self.assertEqual(keeper.referenced(), {'snap', 'snap_2'})
        keeper.forget('q')
        self.assertEqual(keeper.referenced(), {'snap'})


def trades():
    return pd.DataFrame({'region': ['EMEA', 'AMER', 'EMEA'], 'desk': ['FX', 'Rates', 'Rates'],
                         'notional': [100.0, 250.0, 75.5], 'pnl': [1.5, -2.0, 0.25]})


class OpenPage:
    """A page open on a Python page, as DataCube's app holds one: its own copy at the version it opened, edited by
    DataCube's own functions (headless), written against its version, following what moved the page since."""

    def __init__(self, page):
        self.page = page
        self.engine = datacube._session.engine()
        self.headless = datacube._session.headless()
        self.version, self.doc = self.read()

    def read(self):
        said = _json.loads(self.engine.answer('GET', '/page.json', f'page={self.page._key}', None).body)
        return said['version'], said['page']

    def follow(self):
        """What moved the page since its version applied to its own copy, as the app applies it: the commands, by
        DataCube's functions, or the page whole."""
        said = _json.loads(self.engine.answer('GET', '/page.json', f'page={self.page._key}&since={self.version}', None).body)
        if 'page' in said:
            self.version, self.doc = said['version'], said['page']
            return
        for command in said['commands']:
            if 'page' in command:
                self.doc = self.applied(command['page'])
            else:
                self.doc = self.grid_applied(command['grid'], command['command'])
        self.version = said['version']

    def applied(self, command):
        held = sorted({v['id'] for v in self.doc['views']} | {s['id'] for s in self.doc['sheets']}
                      | {c['id'] for c in self.doc['cubes']})
        said = json.loads(self.headless.call('pageApply', _json.dumps(self.doc), json.dumps(held), _json.dumps([command])))
        if 'problems' in said:
            raise ValueError('; '.join(said['problems']))
        return _json.loads(said['page'])

    def grid_applied(self, grid, command):
        from legend_lite.page import _copy_base_of, _cube_spec, _with_cube
        session = datacube._session
        view = next(v for v in self.doc['views'] if v['id'] == grid)
        cube = next(c['cube'] for c in self.doc['cubes'] if c['id'] == view['cube'])
        with session.frames.serving():
            spec = _cube_spec(cube, session.frames, self.headless, _copy_base_of(session, self.doc, view['cube']))
            said = json.loads(self.headless.call('gridApply', spec, _json.dumps([command])))
        if 'problems' in said:
            raise ValueError('; '.join(said['problems']))
        return _with_cube(self.doc, view['cube'], _json.loads(said['cube']))

    def edit(self, rng):
        """One edit as a person makes one on the page: refused ones (an id gone) are let be, as the screen lets be. Its
        kind: ``addSheet`` when it gave out an id."""
        grids = [v['id'] for v in self.doc['views'] if v['kind'] == 'grid']
        choice = rng.randrange(4)
        try:
            if choice == 0:
                self.doc = self.applied({'kind': 'renamePage', 'name': f'Named there {rng.randrange(100)}'})
                return 'renamePage'
            if choice == 1:
                self.doc = self.applied({'kind': 'addSheet', 'name': f'There {rng.randrange(100)}'})
                return 'addSheet'
            if choice == 2 and grids:
                self.doc = self.grid_applied(rng.choice(grids), {'kind': 'group', 'rows': [rng.choice(['desk', 'region'])]})
                return 'group'
            sheets = [s['id'] for s in self.doc['sheets']]
            if len(sheets) > 1:
                self.doc = self.applied({'kind': 'renameSheet', 'sheet': rng.choice(sheets), 'name': 'Renamed there'})
                return 'renameSheet'
        except ValueError:
            pass
        return None

    def write(self):
        """Written as the app writes it: against its version; refused, it follows and writes again."""
        for _ in range(4):
            answer = self.engine.answer('POST', '/page.json', f'page={self.page._key}&version={self.version}',
                                        _json.dumps(self.doc))
            if answer.status == 200:
                self.version = json.loads(answer.body)['version']
                return
            if answer.status != 409:
                raise AssertionError(f'the write was refused: {answer.status} {answer.body!r}')
            # the page moved on: what moved it applied to what is open (its own edit kept), then written again
            mine = self.doc
            self.follow()
            self.doc = self.merged(mine)
        raise AssertionError('the open page never caught up')

    def merged(self, mine):
        """Its own change kept over what moved the page: the screen holds its own state and applies the editor's
        commands to it -- here, simply, its page name and sheet names where they differ (the edits it makes)."""
        doc = dict(self.doc)
        doc['name'] = mine['name']
        names = {s['id']: s.get('name') for s in mine['sheets']}
        doc['sheets'] = [({**s, 'name': names[s['id']]} if names.get(s['id']) is not None else s) for s in doc['sheets']]
        return doc


class Converges(unittest.TestCase):
    """A page Python edits and an open page edits, interleaved at random: never two pages."""

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

    def python_edit(self, page, rng):
        """One edit made in Python; refused, its refusal (else None)."""
        grids = page.grids
        choice = rng.randrange(5)
        try:
            if choice == 0:
                page.sheet(f'Python {rng.randrange(100)}')
            elif choice == 1 and grids:
                rng.choice(grids).group(rng.choice(['desk', 'region']))
            elif choice == 2:
                page.name = f'Named in Python {rng.randrange(100)}'
            elif choice == 3 and grids:
                grid = rng.choice(grids)
                if grid.snapped is None:
                    grid.snap()
                else:
                    grid.live()
            elif len(page.sheets) > 1:
                page.sheets[-1].name = 'Renamed in Python'
        except ValueError as refused:
            # refused in DataCube's words, or made against a page that moved on and no longer applying: nothing changed
            return refused
        return None

    def same(self, page, open_page, seed, step):
        kept = datacube._session.keeper.current(page._key)
        open_page.follow()
        where = f'seed {seed}, step {step}'
        self.assertEqual(open_page.version, kept[0], where)
        self.assertEqual(_json.dumps(open_page.doc), _json.dumps(kept[1]), f'{where}: the open page holds the keeper\'s')
        self.assertEqual(_json.dumps(page.to_dict()), _json.dumps(kept[1]), f'{where}: Python holds the keeper\'s')
        engine = datacube._session.engine()
        datacube._session.sweep()
        reads = datacube._session.keeper.referenced()
        self.assertEqual({t for t in engine._copies if t in engine._claimed or t in reads}, reads,
                         f'{where}: the copies held are the ones read')

    def test_python_and_an_open_page_edit_one_page_in_any_order_and_never_hold_two(self):
        for seed in range(SEEDS):
            rng = random.Random(seed)
            page = ll.Page('Converges')
            page.grid(trades(), name=f'trades_{seed}', rows=['region'], measures={'notional': 'sum'})
            page.grid(trades(), name=f'more_{seed}')
            tab = ll.show(page, browser=False)
            open_page = OpenPage(page)
            for step in range(STEPS):
                who = rng.randrange(3)
                if who == 0:
                    self.python_edit(page, rng)
                elif who == 1:
                    open_page.edit(rng)
                    open_page.write()
                else:
                    # the open page edits, Python edits on top of the page as it began its call, then the page writes
                    page._enter()
                    try:
                        theirs = open_page.edit(rng)
                        open_page.write()
                        refused = self.python_edit(page, rng)
                    finally:
                        page._leave(failed=False)
                    # made again on top of the open page's edit, refused only for a conflict: both gave out the same id
                    if refused is not None and 'changed where it is open' in str(refused):
                        self.assertEqual(theirs, 'addSheet', f'seed {seed}, step {step}: refused for no conflict: {refused}')
                self.same(page, open_page, seed, step)
            tab.close()
