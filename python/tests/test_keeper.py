"""ONE KEEPER PER PAGE (legend_lite/keeper.py; docs/DATACUBE_PAGE_KEEPER_2026_10_20.md): a page written only against
the version it is at, each version with the commands that moved it there, a write that changes nothing no version; and
-- the guard of the whole design -- a page Python and an open page both edit, in any interleaving, never two pages and
never an edit lost: after every step Python's page, the page the open page holds and the keeper's are one; what every
edit that went through said (a name, a sheet, a grid's groups, a tile, a Snap) is on it, in the order the edits were
written; an edit is refused only for a real conflict; and the copies held are exactly those the kept pages read.

The open page here is DataCube's own functions run headless (pageApply, gridApply: what the tab runs), writing as the
tab is to write (page.json, against the version it holds, its edits as commands: refused, it follows what moved the
page and makes its commands again on top -- step 2 of the note) and following as it follows (page.json?since=: the
commands, or the page). The intent each edit carries is kept apart, in a model of its own (``Intent``), never read back
from the mechanism it checks."""

import json
import os
import random
import tempfile
import threading
import unittest

import pandas as pd

import legend_lite as ll
from legend_lite import _json, datacube
from legend_lite.keeper import PageKeeper, Stale

# how many interleavings, and of how many steps: the seed of a failure is printed, so it can be run alone (a long soak:
# LEGEND_LITE_KEEPER_SEEDS=500)
SEEDS = int(os.environ.get('LEGEND_LITE_KEEPER_SEEDS', '8'))
STEPS = 30
# a refusal for a conflict the page cannot settle names the page changed where it is open (page.py _commit)
CONFLICT = 'changed where it is open'


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
        # the frames it reads are part of what it is: a write of the same page over other frames is a version
        self.assertEqual(keeper.write('p', 2, {'name': 'b'}, ['f', 'g'], None), 3)
        self.assertEqual(keeper.frames('p'), ['f', 'g'])
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

    def test_only_its_last_versions_keep_their_commands(self):
        keeper = PageKeeper()
        keeper.keep('p', {'n': 0}, [])
        for n in range(1, 70):
            keeper.write('p', n, {'n': n}, [], [n])
        self.assertEqual(keeper.changes('p', 69 - 63 + 1)[1], list(range(69 - 63 + 1, 70)), 'the last 63 moves')
        self.assertEqual(keeper.changes('p', 2), (70, None), 'older: the page opened again')

    def test_its_document_is_its_own(self):
        keeper = PageKeeper()
        doc = {'views': [{'id': 'grid-1'}]}
        keeper.keep('p', doc, [])
        doc['views'].append({'id': 'grid-2'})
        self.assertEqual(keeper.current('p')[1], {'views': [{'id': 'grid-1'}]}, 'kept: copied in')
        written = {'views': [{'id': 'grid-3'}]}
        commands = [{'page': {'kind': 'x'}}]
        keeper.write('p', 1, written, [], commands)
        written['views'].clear()
        commands[0]['page']['kind'] = 'y'
        self.assertEqual(keeper.current('p')[1], {'views': [{'id': 'grid-3'}]}, 'written: copied in')
        self.assertEqual(keeper.changes('p', 1)[1], [{'page': {'kind': 'x'}}], 'its commands too')

    def test_the_copies_the_pages_kept_read_and_where_each_is_shown(self):
        keeper = PageKeeper()
        keeper.keep('p', {'copies': {'c1': {'table': 'snap'}}}, [])
        keeper.keep('q', {'copies': {'c1': {'table': 'snap_2'}}}, [])
        self.assertEqual(keeper.referenced(), {'snap', 'snap_2'})
        keeper.forget('q')
        self.assertEqual(keeper.referenced(), {'snap'})
        self.assertFalse(keeper.served('p'))
        keeper.serve('p', True)
        self.assertTrue(keeper.served('p'))
        keeper.serve('p', False)
        self.assertFalse(keeper.served('p'))


def trades():
    return pd.DataFrame({'region': ['EMEA', 'AMER', 'EMEA'], 'desk': ['FX', 'Rates', 'Rates'],
                         'notional': [100.0, 250.0, 75.5], 'pnl': [1.5, -2.0, 0.25]})


def grid_rows(doc, grid):
    view = next(v for v in doc['views'] if v['id'] == grid)
    return next(c['cube'] for c in doc['cubes'] if c['id'] == view['cube'])['query'].get('rows', [])


class Intent:
    """WHAT THE EDITS THAT WENT THROUGH SAID, in the order they were written -- kept apart from the page, by rules of its
    own: a page's name, each sheet's name, the grids and charts on it, each grid's groups and whether it is snapped, the
    titles given. The page must say all of it after every step."""

    def __init__(self, doc):
        self.name = doc['name']
        self.sheets = {s['id']: s.get('name') for s in doc['sheets']}
        self.grids = {v['id']: {'cube': v['cube'], 'rows': grid_rows(doc, v['id']),
                                'snapped': v['cube'] in doc.get('copies', {})}
                      for v in doc['views'] if v['kind'] == 'grid'}
        self.charts = {v['id']: {'cube': v['cube'], 'frozen': bool(v['spec'].get('frozen'))}
                       for v in doc['views'] if v['kind'] == 'chart'}
        self.titles = {}

    def said(self, fact):
        what, *rest = fact
        if what == 'name':
            self.name = rest[0]
        elif what == 'sheet':
            self.sheets[rest[0]] = rest[1]
        elif what == 'title':
            self.titles[rest[0]] = rest[1]
        elif what == 'rows':
            self.grids[rest[0]]['rows'] = list(rest[1])
        elif what == 'snapped':
            self.grids[rest[0]]['snapped'] = rest[1]
        elif what == 'grid':
            self.grids[rest[0]] = {'cube': rest[1], 'rows': list(rest[2]), 'snapped': False}
        elif what == 'chart':
            self.charts[rest[0]] = {'cube': rest[1], 'frozen': rest[2]}
        elif what == 'remove':
            tile = rest[0]
            gone = {tile}
            if tile in self.grids:
                # a grid taken off takes the charts that follow it (a frozen one stays, its own)
                cube = self.grids.pop(tile)['cube']
                gone |= {c for c, chart in self.charts.items() if chart['cube'] == cube and not chart['frozen']}
            for id in gone:
                self.charts.pop(id, None)
                self.titles.pop(id, None)
        else:
            raise AssertionError(f'no such fact {fact!r}')

    def check(self, test, doc, where):
        views = {v['id']: v for v in doc['views']}
        test.assertEqual(doc['name'], self.name, f'{where}: its name')
        test.assertEqual({s['id']: s.get('name') for s in doc['sheets']}, self.sheets, f'{where}: its sheets')
        test.assertEqual(sorted(i for i, v in views.items() if v['kind'] == 'grid'), sorted(self.grids), f'{where}: grids')
        test.assertEqual(sorted(i for i, v in views.items() if v['kind'] == 'chart'), sorted(self.charts), f'{where}: charts')
        for id, grid in self.grids.items():
            test.assertEqual(grid_rows(doc, id), grid['rows'], f'{where}: {id} grouped')
            test.assertEqual(views[id]['cube'] in doc.get('copies', {}), grid['snapped'], f'{where}: {id} snapped')
        for id, title in self.titles.items():
            test.assertEqual(views[id].get('title'), title, f'{where}: {id} titled')


class OpenPage:
    """A page open on a Python page, as DataCube's app is to hold one: the page at the version it is at (``base``), what
    is on its screen (``doc``: the base with its own edits not yet written, ``pending``), edited by DataCube's own
    functions (headless), written against its version as commands; refused, it follows what moved the page since and makes
    its own edits again on top."""

    def __init__(self, page):
        self.page = page
        self.engine = datacube._session.engine()
        self.headless = datacube._session.headless()
        said = self.get('')
        self.version, self.base = said['version'], said['page']
        self.doc = self.base
        # each edit not yet written: (its entry -- a page command, or a grid's -- its kind, what it said)
        self.pending = []
        # how many of its writes were refused, the page having moved on; how many times it opened the page again whole
        self.stale = 0
        self.reopened = 0

    def get(self, since):
        answer = self.engine.answer('GET', '/page.json', f'page={self.page._key}{since}', None)
        if answer.status != 200:
            raise AssertionError(f'the page was not read: {answer.status} {answer.body!r}')
        return _json.loads(answer.body)

    def applied(self, doc, command):
        held = sorted({v['id'] for v in doc['views']} | {s['id'] for s in doc['sheets']} | {c['id'] for c in doc['cubes']})
        said = json.loads(self.headless.call('pageApply', _json.dumps(doc), json.dumps(held), _json.dumps([command])))
        if 'problems' in said:
            raise ValueError('; '.join(said['problems']))
        return _json.loads(said['page'])

    def grid_applied(self, doc, grid, command):
        from legend_lite.page import _copy_base_of, _cube_spec, _with_cube
        session = datacube._session
        view = next((v for v in doc['views'] if v['id'] == grid and v['kind'] == 'grid'), None)
        if view is None:
            raise ValueError(f'{grid} is no longer on the page')
        cube = next(c['cube'] for c in doc['cubes'] if c['id'] == view['cube'])
        with session.frames.serving():
            spec = _cube_spec(cube, session.frames, self.headless, _copy_base_of(session, doc, view['cube']))
            said = json.loads(self.headless.call('gridApply', spec, _json.dumps([command])))
        if 'problems' in said:
            raise ValueError('; '.join(said['problems']))
        return _with_cube(doc, view['cube'], _json.loads(said['cube']))

    def made(self, doc, entry):
        """``entry`` applied to ``doc``: the page it makes, and what it said (its facts, read from what it made only
        where an id was given out)."""
        if 'grid' in entry:
            after = self.grid_applied(doc, entry['grid'], entry['command'])
            command = entry['command']
            return after, ([('rows', entry['grid'], command['rows'])] if command['kind'] == 'group' else [])
        command = entry['page']
        after = self.applied(doc, command)
        kind = command['kind']
        if kind == 'renamePage':
            return after, [('name', command['name'])]
        if kind == 'addSheet':
            (made,) = {s['id'] for s in after['sheets']} - {s['id'] for s in doc['sheets']}
            return after, [('sheet', made, command['name'])]
        if kind == 'renameSheet':
            return after, [('sheet', command['sheet'], command['name'])]
        if kind == 'renameTile':
            return after, [('title', command['tile'], command['title'])]
        if kind == 'removeTile':
            return after, [('remove', command['tile'])]
        return after, []

    def follow(self):
        """What moved the page since its version, applied to its base as the app applies it -- the commands, by DataCube's
        functions, or the page whole -- and its own edits made again on top. The kinds of its own edits that no longer
        apply (let go, as the screen lets them go)."""
        said = self.get(f'&since={self.version}')
        if 'commands' in said:
            try:
                base = self.base
                for entry in said['commands']:
                    base = (self.applied(base, entry['page']) if 'page' in entry
                            else self.grid_applied(base, entry['grid'], entry['command']))
                self.base = base
            except ValueError:
                # its commands do not apply here -- a copy a page between read let go meanwhile (known limit 6 of the
                # note), a tile closed -- so the page is opened again whole, as the app opens it (datacube-app.ts
                # followLive)
                self.reopened += 1
                said = self.get('')
        if 'page' in said:
            self.base = said['page']
        self.version = said['version']
        self.doc, kept, refused = self.base, [], []
        for entry, kind, _ in self.pending:
            try:
                self.doc, facts = self.made(self.doc, entry)
                kept.append((entry, kind, facts))
            except ValueError:
                refused.append(kind)
        self.pending = kept
        return refused

    def edit(self, rng):
        """One edit as a person makes one on the page, onto its screen, not yet written: its kind, or None when there was
        none to make or DataCube refused it (a person's refused edit changes nothing)."""
        doc = self.doc
        grids = [v['id'] for v in doc['views'] if v['kind'] == 'grid']
        charts = [v['id'] for v in doc['views'] if v['kind'] == 'chart']
        sheets = [s['id'] for s in doc['sheets']]
        choices = {
            'renamePage': lambda: {'page': {'kind': 'renamePage', 'name': f'Named there {rng.randrange(100)}'}},
            'addSheet': lambda: {'page': {'kind': 'addSheet', 'name': f'There {rng.randrange(100)}'}},
            'renameSheet': lambda: {'page': {'kind': 'renameSheet', 'sheet': rng.choice(sheets),
                                             'name': f'Renamed there {rng.randrange(100)}'}},
            'moveSheet': lambda: len(sheets) > 1 and {'page': {'kind': 'moveSheet', 'sheet': rng.choice(sheets),
                                                               'to': rng.randrange(len(sheets))}},
            'renameTile': lambda: {'page': {'kind': 'renameTile', 'tile': rng.choice(grids + charts),
                                            'title': f'Titled there {rng.randrange(100)}'}},
            # a grid only while three are on the page: two taken off at once, one on each side, leave one (a page of no
            # grid is known limit 5 of the note: Converged.test_a_page_with_no_grid_written_there_is_pythons)
            'removeTile': lambda: (charts or len(grids) > 2) and {
                'page': {'kind': 'removeTile', 'tile': rng.choice(charts + (grids if len(grids) > 2 else []))}},
            'group': lambda: grids and {'grid': rng.choice(grids),
                                        'command': {'kind': 'group', 'rows': [rng.choice(['desk', 'region'])]}},
            'addSort': lambda: grids and {'grid': rng.choice(grids), 'command': {
                'kind': 'addSort', 'column': rng.choice(['desk', 'region']), 'direction': rng.choice(['asc', 'desc'])}},
            'pivot': lambda: grids and {'grid': rng.choice(grids),
                                        'command': {'kind': 'pivot', 'columns': rng.choice([[], ['region'], ['desk']])}},
        }
        kind = rng.choice(sorted(choices))
        entry = choices[kind]()
        if not entry:
            return None
        try:
            self.doc, facts = self.made(self.doc, entry)
        except ValueError:
            return None
        self.pending.append((entry, kind, facts))
        return kind

    def write(self):
        """Its edits written as the app is to write them: against its version, as commands; refused, it follows and
        writes again -- for as long as the page keeps moving (Python in a tight loop delays an open page's write, and
        loses none of it: the two threads' test found a fixed few tries starved). What they said, in the order written,
        and the kinds of those it let go."""
        refused = []
        for _ in range(1000):
            if not self.pending:
                return [], refused
            answer = self.engine.answer('POST', '/page.json', f'page={self.page._key}&version={self.version}',
                                        _json.dumps(self.doc))
            if answer.status == 200:
                self.version = json.loads(answer.body)['version']
                facts = [f for _, _, said in self.pending for f in said]
                self.base, self.pending = self.doc, []
                return facts, refused
            if answer.status != 409:
                raise AssertionError(f'the write was refused: {answer.status} {answer.body!r}')
            self.stale += 1
            refused += self.follow()
        raise AssertionError('the open page never caught up')


PYTHON_KINDS = ('sheet', 'group', 'name', 'snap', 'live', 'renameSheet', 'sort', 'pivot', 'measure', 'chart', 'grid',
                'remove', 'title', 'moveTo')


def python_edit(page, rng, frame):
    """One edit made in Python: its kind and what it said -- or, refused, its kind and the refusal; (None, None) when
    there was none to make."""
    grids, charts, sheets = page.grids, page.charts, page.sheets
    kind = rng.choice(PYTHON_KINDS)
    try:
        if kind == 'sheet':
            name = f'Python {rng.randrange(100)}'
            return kind, [('sheet', page.sheet(name).id, name)]
        if kind == 'name':
            name = f'Named in Python {rng.randrange(100)}'
            page.name = name
            return kind, [('name', name)]
        if kind == 'renameSheet':
            sheet = rng.choice(sheets)
            sheet.name = name = f'Renamed in Python {rng.randrange(100)}'
            return kind, [('sheet', sheet.id, name)]
        if kind == 'title' and grids + charts:
            tile = rng.choice(grids + charts)
            tile.title = title = f'Titled in Python {rng.randrange(100)}'
            return kind, [('title', tile.id, title)]
        if kind == 'remove' and (charts or len(grids) > 2):
            # a grid only while three are on the page (OpenPage.edit says why)
            tile = rng.choice(charts + (grids if len(grids) > 2 else []))
            page.remove(tile)
            return kind, [('remove', tile.id)]
        if kind == 'grid':
            rows = [rng.choice(['desk', 'region'])]
            # over a frame the page reads already (its object, as served): no frame served again, no grid opened again
            grid = page.grid(frame[1], name=frame[0], rows=rows)
            return kind, [('grid', grid.id, grid._view['cube'], rows)]
        if not grids:
            return None, None
        grid = rng.choice(grids)
        if kind == 'group':
            column = rng.choice(['desk', 'region'])
            grid.group(column)
            return kind, [('rows', grid.id, [column])]
        if kind == 'snap':
            grid.snap()
            return kind, [('snapped', grid.id, True)]
        if kind == 'live':
            if grid.snapped is None:
                return None, None
            grid.live()
            return kind, [('snapped', grid.id, False)]
        if kind == 'sort':
            grid.sort(rng.choice(['desk', 'region']), rng.choice(['asc', 'desc']))
            return kind, []
        if kind == 'pivot':
            grid.pivot(*rng.choice([[], ['region'], ['desk']]))
            return kind, []
        if kind == 'measure':
            grid.measure('pnl', rng.choice(['sum', 'max']))
            return kind, []
        if kind == 'chart':
            chart = grid.chart('bar', x=rng.choice(['desk', 'region']), frozen=rng.choice([None, True]))
            return kind, [('chart', chart.id, chart._view['cube'], bool(chart._view['spec'].get('frozen')))]
        if kind == 'moveTo':
            grid.move_to(rng.choice(sheets))
            return kind, []
        return None, None
    except ValueError as refused:
        # refused in DataCube's words, or made against a page that moved on and no longer applying: nothing changed
        return kind, refused


class Converged(unittest.TestCase):
    """The page served from a session of its own (a site of its own), its tabs closed after."""

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

    def built(self, seed):
        """A page of two grids, and the frame of its first (its name, its object), for the grids Python adds."""
        page = ll.Page('Converges')
        frame = (f'trades_{seed}', trades())
        page.grid(frame[1], name=frame[0], rows=['region'], measures={'notional': 'sum'})
        page.grid(trades(), name=f'more_{seed}')
        return page, frame

    def copies_read(self, where):
        engine = datacube._session.engine()
        datacube._session.sweep()
        reads = datacube._session.keeper.referenced()
        self.assertEqual({t for t in engine._copies if t in engine._claimed or t in reads}, reads,
                         f'{where}: the copies held are the ones read')
        self.assertEqual(set(engine._copies) - reads, set(), f'{where}: no copy held that no page reads')

    def one(self, page, open_page, intent, where):
        """Python, the open page and the keeper hold one page, which says what every edit written said; the copies held
        are the ones read."""
        kept = datacube._session.keeper.current(page._key)
        refused = open_page.follow()
        self.assertEqual(refused, [], f'{where}: nothing pending, nothing let go')
        self.assertEqual(open_page.version, kept[0], where)
        self.assertEqual(_json.dumps(open_page.doc), _json.dumps(kept[1]), f'{where}: the open page holds the keeper\'s')
        self.assertEqual(_json.dumps(page.to_dict()), _json.dumps(kept[1]), f'{where}: Python holds the keeper\'s')
        intent.check(self, kept[1], where)
        self.copies_read(where)


class EmptyPage(Converged):
    """KNOWN LIMIT 5 (docs/DATACUBE_PAGE_KEEPER_2026_10_20.md, found by the convergence soak): a page of no grid is one
    Python holds (``ll.Page`` starts as one) but no write of the open page can say -- DataCube's reader refuses a page with
    no cube, and the tab writes nothing for a blank page -- so the last grid taken off there leaves two pages. Step 2
    makes it a page both sides write; this test then passes, and its mark comes off."""

    @unittest.expectedFailure
    def test_a_page_with_no_grid_written_there_is_pythons(self):
        page, _ = self.built(0)
        tab = ll.show(page, browser=False)
        try:
            open_page = OpenPage(page)
            for grid in [v['id'] for v in open_page.doc['views'] if v['kind'] == 'grid']:
                open_page.doc, facts = open_page.made(open_page.doc, {'page': {'kind': 'removeTile', 'tile': grid}})
                open_page.pending.append(({'page': {'kind': 'removeTile', 'tile': grid}}, 'removeTile', facts))
            open_page.write()
            self.assertEqual(page.grids, [])
        finally:
            tab.close()


class Converges(Converged):
    """A page Python edits and an open page edits, interleaved at random: one page, every edit's intent on it."""

    def test_python_and_an_open_page_edit_one_page_in_any_order_and_never_hold_two(self):
        made = {k: 0 for k in PYTHON_KINDS}
        made_there = {}
        for seed in range(SEEDS):
            rng = random.Random(seed)
            page, frame = self.built(seed)
            tab = ll.show(page, browser=False)
            open_page = OpenPage(page)
            intent = Intent(page.to_dict())
            for step in range(STEPS):
                where = f'seed {seed}, step {step}'
                who = rng.randrange(4)
                if who == 0:
                    # Python alone
                    kind, said = python_edit(page, rng, frame)
                    if isinstance(said, list):
                        made[kind] += 1
                        for fact in said:
                            intent.said(fact)
                    elif said is not None:
                        self.assertNotIn(CONFLICT, str(said), f'{where}: {kind} refused with nothing in its way')
                elif who == 1:
                    # the open page alone
                    theirs = open_page.edit(rng)
                    facts, refused = open_page.write()
                    self.assertEqual(refused, [], f'{where}: nothing moved the page: {theirs} let go')
                    if theirs:
                        made_there[theirs] = made_there.get(theirs, 0) + 1
                    for fact in facts:
                        intent.said(fact)
                elif who == 2:
                    # the open page writes while a Python call is under way: Python's change made again on top of it
                    page._enter()
                    try:
                        theirs = open_page.edit(rng)
                        facts, _ = open_page.write()
                        kind, said = python_edit(page, rng, frame)
                    finally:
                        page._leave(failed=False)
                    for fact in facts:
                        intent.said(fact)
                    if theirs:
                        made_there[theirs] = made_there.get(theirs, 0) + 1
                    if isinstance(said, list):
                        made[kind] += 1
                        for fact in said:
                            intent.said(fact)
                    elif said is not None and CONFLICT in str(said):
                        # refused only for a real conflict: both gave out one id, or the tile was taken off there
                        self.assertIn(theirs, ('addSheet', 'removeTile'), f'{where}: {kind} refused for no conflict: {said}')
                else:
                    # Python writes while the open page holds an edit not yet written: refused, the open page follows
                    # and makes its edit again on top
                    theirs = open_page.edit(rng)
                    kind, said = python_edit(page, rng, frame)
                    if isinstance(said, list):
                        made[kind] += 1
                        for fact in said:
                            intent.said(fact)
                    elif said is not None:
                        self.assertNotIn(CONFLICT, str(said), f'{where}: {kind} refused with nothing written in its way')
                    facts, refused = open_page.write()
                    if refused:
                        # let go only where Python took off what it edited
                        self.assertEqual(kind, 'remove', f'{where}: {theirs} let go for no conflict after {kind}')
                    elif theirs:
                        made_there[theirs] = made_there.get(theirs, 0) + 1
                    for fact in facts:
                        intent.said(fact)
                self.one(page, open_page, intent, where)
            tab.close()
        # every kind of edit went through somewhere, on each side: none of them is never tried
        self.assertEqual([k for k, n in made.items() if n == 0], [], f'Python edits made: {made}')
        there = ('renamePage', 'addSheet', 'renameSheet', 'moveSheet', 'renameTile', 'removeTile', 'group', 'addSort', 'pivot')
        self.assertEqual([k for k in there if not made_there.get(k)], [], f'open page edits made: {made_there}')


class Threads(Converged):
    """Python's calls and the open page's writes on two threads at once, as they come (Python's own thread, the engine's
    HTTP thread): no deadlock, one page, every edit written on it in its version's order."""

    def test_python_and_an_open_page_on_two_threads_never_hold_two_pages_or_lose_an_edit(self):
        page, frame = self.built(0)
        tab = ll.show(page, browser=False)
        start = page.to_dict()
        written, failures = [], []
        open_page = OpenPage(page)

        def there():
            rng = random.Random(1)
            try:
                for _ in range(40):
                    if open_page.edit(rng) is None:
                        continue
                    facts, _ = open_page.write()
                    written.append((open_page.version, 1, facts))
            except BaseException as e:  # noqa: BLE001 -- said by the main thread
                failures.append(e)

        worker = threading.Thread(target=there, daemon=True)
        worker.start()
        rng = random.Random(2)
        for n in range(40):
            kind, said = python_edit(page, rng, frame)
            if isinstance(said, list):
                written.append((page._version, 0, said))
        worker.join(timeout=600)
        self.assertFalse(worker.is_alive(), 'the open page\'s thread never finished: a deadlock')
        self.assertEqual(failures, [])
        # the two met: each wrote, and the open page was refused for Python's writes at least once (else this proved
        # nothing about two threads)
        self.assertGreaterEqual(sum(1 for w in written if w[1] == 0), 10, 'Python wrote')
        self.assertGreaterEqual(sum(1 for w in written if w[1] == 1), 10, 'the open page wrote')
        self.assertGreater(open_page.stale, 0, 'no write of the open page met one of Python\'s')
        kept = datacube._session.keeper.current(page._key)
        self.assertEqual(_json.dumps(page.to_dict()), _json.dumps(kept[1]), 'Python holds the keeper\'s')
        intent = Intent(start)
        for _, _, facts in sorted(written, key=lambda w: (w[0], w[1])):
            for fact in facts:
                intent.said(fact)
        intent.check(self, kept[1], 'two threads')
        self.copies_read('two threads')
        tab.close()
