"""ll.Page (legend_lite.page; docs/DATACUBE_PYTHON_PAGES_DESIGN_2026_10_09.md): a DataCube page built in Python -- its
grids over frames, their charts, sheets, layouts and stacks -- written as DataCube's own page document; a calculated
column checked by the compiler at the line that adds it; a filter, an aggregate or a chart outside DataCube's words
refused there; tiles placed and taken off as DataCube's layout places them; saved and loaded again as it was, what it
does not model kept; shown in a tab and live from then on (a change served again, a frame updated moving its version),
and read back into the same page as the open page says it is."""

import copy
import datetime
import json
import os
import tempfile
import unittest
import urllib.error
import urllib.request
import warnings
from decimal import Decimal
from pathlib import Path

import pandas as pd

import legend_lite as ll
from legend_lite import datacube
from legend_lite import page as page_module
from legend_lite._types import plain_type


def trades():
    return pd.DataFrame({'region': ['EMEA', 'AMER', 'EMEA'], 'desk': ['FX', 'Rates', 'Rates'],
                         'notional': [100.0, 250.0, 75.5], 'pnl': [1.5, -2.0, 0.25]})


def desks():
    return pd.DataFrame({'desk': ['FX', 'Rates'], 'head': ['Ana', 'Ben']})


def numbers(tree):
    """The exact numbers in a protocol tree, wherever they are."""
    if isinstance(tree, dict):
        return [n for v in tree.values() for n in numbers(v)]
    if isinstance(tree, list):
        return [n for v in tree for n in numbers(v)]
    return [tree] if isinstance(tree, Decimal) else []


class Paged(unittest.TestCase):
    """Pages on a session of their own, with a site of its own and no browser."""

    def setUp(self):
        self._site = os.environ.get('LEGEND_LITE_SITE')
        os.environ['LEGEND_LITE_SITE'] = tempfile.mkdtemp(dir=os.environ.get('TEST_TMPDIR'))
        datacube._session = datacube._Session()

    def tearDown(self):
        for view in list(datacube._session.pages):
            view.close()
        # its DataCube engine (headless) closed with it: a session dropped open leaves that engine's folder to the garbage
        # collector, which warns about it under whichever later test it reaches (CorpusPage counts every warning)
        if datacube._session._headless is not None:
            datacube._session._headless.close()
        if self._site is None:
            os.environ.pop('LEGEND_LITE_SITE', None)
        else:
            os.environ['LEGEND_LITE_SITE'] = self._site

    def built(self):
        page = ll.Page('Q3 review')
        grid = page.grid(trades(), name='trades', rows=['region'], measures={'notional': 'sum'},
                         filter=[('desk', 'notEqual', 'Equity')], sort=[('notional', 'desc')])
        # DataCube's own new chart of the grid -- across, its row group; up, its measure -- following it
        chart = grid.chart('bar', title='Notional by region')
        return page, grid, chart

    def bands(self, sheet):
        return sheet.page.to_dict()['sheets'][sheet.page.sheets.index(sheet)]['layout']['bands']


class Document(Paged):
    def test_a_page_is_datacubes_own_document_one_cube_per_grid_over_its_frame(self):
        page, grid, chart = self.built()
        doc = page.to_dict()
        self.assertEqual((doc['kind'], doc['version'], doc['name']), ('datacube.page', 6, 'Q3 review'))
        cube = doc['cubes'][0]
        self.assertEqual(cube['id'], grid.id)
        self.assertEqual(cube['cube']['source']['_type'], 'frame')
        self.assertEqual(cube['cube']['source']['name'], 'trades')
        # the columns as the compiler types the frame
        self.assertEqual([(c['name'], c['type']) for c in cube['cube']['query']['columns']],
                         [('region', 'String'), ('desk', 'String'), ('notional', 'Float'), ('pnl', 'Float')])
        query = cube['cube']['query']
        self.assertEqual(query['rows'], ['region'])
        # a measure is its column's Aggregation, in Column Properties, as the screen sets it: no measures list
        self.assertNotIn('measures', query)
        self.assertEqual(cube['cube']['configuration']['columns']['notional'], {'aggregateFn': 'sum'})
        self.assertEqual(query['sorts'], [{'column': 'notional', 'direction': 'desc'}])
        # a list of one condition, in the filter editor's normal form: a group of one is that one, as DataCube writes it
        self.assertEqual(query['filter'],
                         {'kind': 'condition', 'column': 'desk', 'operator': 'notEqual', 'value': 'Equity'})
        self.assertEqual(json.loads(page.to_json())['cubes'][0]['cube']['query']['filter'], query['filter'],
                         'read back as DataCube reads it: the same')
        self.assertEqual([(v['id'], v['kind'], v['cube']) for v in doc['views']],
                         [(grid.id, 'grid', grid.id), (chart.id, 'chart', grid.id)])
        # made as DataCube makes a new chart (_made): what it was not told is DataCube's own -- the grid's row group
        # across, its measure up, a category leading with the largest -- and, told nothing of its grouping, it follows
        # the grid
        spec = doc['views'][1]['spec']
        self.assertEqual({k: v for k, v in spec.items() if k != 'options'},
                         {'version': 2, 'mark': 'bar', 'x': 'region', 'y': [{'column': 'notional', 'fn': 'sum'}]})
        # told its grouping, a chart keeps it, as a hand on it in DataCube's options freezes one -- unless told otherwise:
        # then it follows the grid, and one told what the grid would replace is refused, never quietly dropped
        grid.chart('bar', x='desk')
        self.assertTrue(page.to_dict()['views'][-1]['spec']['frozen'])
        grid.chart('bar', x='region', frozen=False)
        self.assertNotIn('frozen', page.to_dict()['views'][-1]['spec'])
        with self.assertRaisesRegex(ValueError, "follows its grid takes 'region' across, not 'desk'"):
            grid.chart('bar', x='desk', frozen=False)
        self.assertEqual(spec['options']['sort'], {'by': 'y', 'direction': 'desc'})
        # one sheet: the grid in a band of its own -- the page's first, the whole screen, as a page started on the screen
        # has it -- the chart beside it, as DataCube places them
        self.assertEqual(doc['sheets'], [{'id': 'sheet-1', 'layout': {'kind': 'bands', 'fit': True, 'bands': [
            {'height': 1, 'node': {'split': 'row', 'parts': [
                {'node': {'tile': grid.id}, 'size': 0.5}, {'node': {'tile': chart.id}, 'size': 0.5}]}}]}}])

    def test_the_document_is_a_copy_and_its_numbers_exact(self):
        page, grid, _ = self.built()
        doc = page.to_dict()
        doc['cubes'][0]['cube']['query']['rows'].append('desk')
        doc['sheets'][0]['layout']['bands'].clear()
        self.assertEqual(page.to_dict()['cubes'][0]['cube']['query']['rows'], ['region'], 'what is done to it is not done to the page')
        self.assertEqual(len(page.to_dict()['sheets'][0]['layout']['bands']), 1)
        # a calculated column's decimal stays its digits, in the document and the file
        grid.calculate('scaled', '$x.notional->toOne() * 1.50D')
        lambda_ = page.to_dict()['cubes'][0]['cube']['query']['derived'][0]['lambda']
        self.assertIn('1.50', [str(v) for v in numbers(lambda_)])
        self.assertIn('1.50', page.to_json())
        path = os.path.join(tempfile.mkdtemp(dir=os.environ.get('TEST_TMPDIR')), 'q3.page.json')
        page.save(path)
        self.assertIn('1.50', open(path, encoding='utf-8').read())
        again = ll.Page.load(path, frames={'trades': trades()})
        self.assertEqual(again.to_json(), page.to_json())
        with self.assertRaises(ValueError):
            page.save(path, name='a file carries its page\'s name')
        # a value JSON cannot carry is refused where it is given
        with self.assertRaises(TypeError):
            grid.configure({'when': datetime.date(2024, 1, 31)})

    def test_saved_as_datacubes_save_keeps_it_into_this_users_saved_pages(self):
        # page.save() with no path: legend-lite's saved-page rules in the native library (B6), over ll.home()/pages --
        # what DataCube's Open… lists when it is shown from Python, the tab's own Save keeping it the same way
        from legend_lite.engine import Engine
        page, grid, _ = self.built()
        grid.calculate('scaled', '$x.notional->toOne() * 1.50D')
        first = page.save()
        engine = Engine(ll.Frames())
        listed = json.loads(engine.answer('POST', '/api/pure/v1/query/dataCube/search', '', '{}').body)
        self.assertIn((first, page.name, 'this user'), [(q['id'], q['name'], q['owner']) for q in listed])
        opened = engine.answer('GET', f'/api/pure/v1/query/dataCube/{first}', '', None)
        self.assertEqual(opened.status, 200)
        # its content the page document, its decimal's digits kept
        self.assertEqual(json.loads(opened.body)['content'], json.loads(page.to_json()))
        self.assertIn(b'1.50', opened.body)
        # saved again: over the copy it was saved as, its new name taken
        self.assertEqual(page.save(name='Q3, again'), first)
        again = json.loads(engine.answer('GET', f'/api/pure/v1/query/dataCube/{first}', '', None).body)
        self.assertEqual(again['name'], 'Q3, again')
        # deleted meanwhile (in DataCube's Open…): saved again as a new one under its id
        self.assertEqual(engine.answer('DELETE', f'/api/pure/v1/query/dataCube/{first}', '', None).status, 204)
        self.assertEqual(page.save(), first)
        # another page is another saved page; one DataCube would not open (no grid) is not saved, in DataCube's words
        other = ll.Page('Other')
        with self.assertRaisesRegex(ValueError, 'it has no cube'):
            other.save()
        other.grid(trades(), name='other_trades')
        self.assertNotEqual(other.save(), first)
        with self.assertRaises(ValueError):
            page.save(name=' ')

    def test_tiles_are_placed_and_taken_off_as_datacube_places_them(self):
        page, grid, chart = self.built()
        sheet = page.sheets[0]
        # four places to a band at most: a chart beyond goes into a band of its own below its grid's
        more = [grid.chart('line', title=f'line {i}') for i in range(3)]
        self.assertEqual([len(b['node']['parts']) if 'split' in b['node'] else 1 for b in self.bands(sheet)], [4, 1])
        # a grid goes into a band of its own at the bottom
        other = page.grid(desks(), name='desks')
        self.assertEqual(self.bands(sheet)[-1], {'height': 0.5, 'node': {'tile': other.id}})
        # taken off: its neighbours close over its place, a band it emptied gone
        page.remove(more[2])
        self.assertEqual([len(b['node']['parts']) if 'split' in b['node'] else 1 for b in self.bands(sheet)], [4, 1])
        page.remove(more[1])
        self.assertEqual(self.bands(sheet)[0]['node']['parts'][2], {'node': {'tile': more[0].id}, 'size': 1 / 3})
        # a grid removed takes the charts that follow it
        page.remove(grid)
        self.assertEqual((page.grids, page.charts), ([other], []))
        self.assertEqual(self.bands(sheet), [{'height': 0.5, 'node': {'tile': other.id}}])
        for gone in (grid, chart):
            with self.assertRaisesRegex(ValueError, 'no longer on its page'):
                gone.title = 'again'

    def test_each_page_edit_is_the_command_datacubes_screen_builds_and_its_ids_are_datacubes(self):
        # (docs/DATACUBE_COMMANDS_PAGE_2026_10_18.md) each page edit is a page command applied by DataCube (headless
        # pageApply): the page records each as applied, the id DataCube gave a new tile or sheet in it
        page, grid, chart = self.built()
        done = len(page._page_commands)
        again = grid.chart('line', title='again')
        extra = page.sheet('Extra')
        chart.title = 'Renamed'
        made = page._page_commands[done:]
        self.assertEqual([c['kind'] for c in made], ['addChart', 'addSheet', 'renameTile'])
        self.assertEqual((made[0]['id'], made[0]['near'], made[1]['id']), (again.id, grid.id, extra.id))
        self.assertEqual(made[2], {'kind': 'renameTile', 'tile': chart.id, 'title': 'Renamed'})
        # a tile moved onto its own sheet stays where it is, as the screen's Move to Sheet does (it moved it to the bottom)
        before = page.to_dict()['sheets']
        page.sheets[0].add(grid)
        self.assertEqual(page.to_dict()['sheets'], before)
        # a chart taken off and a new one made: never under the id the old one had
        page.remove(again)
        self.assertNotEqual(grid.chart('pie', x='region', title='later').id, again.id)

    def test_a_sheet_is_arranged_as_datacube_arranges_it(self):
        # DataCube's own arrangements (bands.ts arrange, headless): the layout picker's every shape, in reading order
        page, grid, chart = self.built()
        more = [grid.chart('line', title=f'line {i}') for i in range(2)]
        sheet = page.sheets[0]
        sheet.arrange('rows:1-3')
        self.assertEqual([len(b['node']['parts']) if 'split' in b['node'] else 1 for b in self.bands(sheet)], [1, 3])
        self.assertEqual([b['height'] for b in self.bands(sheet)], [0.5, 0.5])
        sheet.arrange('focus-left')
        bands = self.bands(sheet)
        self.assertEqual((len(bands), bands[0]['node']['parts'][0]), (1, {'node': {'tile': grid.id}, 'size': 0.5}))
        self.assertEqual(sheet.tiles, [grid, chart, *more])

    def test_a_grid_over_a_frame_of_other_columns_is_rebuilt_as_datacube_rebuilds_one(self):
        # DataCube's reopening over the frame as it is now (cube-document.ts rebuilt, headless): a gone column left out
        # of everything that read it, said in DataCube's words; a new one hidden; the page one DataCube opens
        page = ll.Page('changed')
        grid = page.grid(trades(), name='changed_trades', rows=['region', 'desk'], measures={'notional': 'sum'})
        with self.assertWarnsRegex(UserWarning, 'left out grouping by desk'):
            grid.update(trades().drop(columns=['desk']).assign(book='B1'))
        cube = page.to_dict()['cubes'][0]['cube']
        self.assertEqual(cube['query']['rows'], ['region'])
        self.assertNotIn('desk', [c['name'] for c in cube['query']['columns']])
        self.assertEqual(cube['configuration']['columns']['book'], {'hidden': True})
        self.assertEqual([c['name'] for c in cube['source']['columns']], ['region', 'notional', 'pnl', 'book'])
        ll.Page.load(page.to_dict(), frames={'changed_trades': trades().drop(columns=['desk']).assign(book='B1')})

    def test_a_page_saved_before_a_grids_aggregates_were_its_columns_is_refused_in_datacubes_words(self):
        # no older page is read (the user, 2026-10-17): a version 1 cube -- every cube saved before a grid's aggregates
        # became its columns' Aggregation -- is refused by its version, and a measures list in a cube of this one by
        # name; never converted, never opened showing other numbers than it showed
        saved = ll.Page('counts')
        saved.grid(trades(), name='old_counts', rows=['region'])
        old = saved.to_dict()
        old['cubes'][0]['cube']['version'] = 1
        with self.assertRaisesRegex(ValueError, r'saved by an older version \(1 < 2\)'):
            ll.Page.load(old, frames={'old_counts': trades()})
        listed = saved.to_dict()
        listed['cubes'][0]['cube']['query']['measures'] = [{'name': 'notional', 'column': 'notional', 'fn': 'average'}]
        with self.assertRaisesRegex(ValueError, "'query.measures': a grid carries no measures list"):
            ll.Page.load(listed, frames={'old_counts': trades()})
        # a calculated column whose lambda the protocol cannot read: refused as the page it is in (a ValueError), never
        # a JavaScript exception
        saved.grids[0].calculate('twice', '$x.notional->toOne() * 2')
        broken = saved.to_dict()
        broken['cubes'][0]['cube']['query']['derived'][0]['lambda']['body'] = 'oops'
        with self.assertRaisesRegex(ValueError, 'calculated column "twice"\'s lambda'):
            ll.Page.load(broken, frames={'old_counts': trades()})

    def test_a_live_frame_changed_in_place_is_the_cube_a_filter_a_column_and_a_chart_are_checked_against(self):
        # a Live frame's column deleted in place: the next filter takes the cube DataCube opens over the frame now (its
        # calculated column that read the gone column left out, said), so a column of that name can be made again, once
        frame = trades()
        page = ll.Page('in place')
        grid = page.grid(frame, name='in_place', rows=['region'])
        grid.calculate('twice', '$x.notional->toOne() * 2')
        del frame['notional']
        with self.assertWarnsRegex(UserWarning, r'in_place: left out calculated column twice \(it uses notional\)'):
            grid.filter(('desk', 'equal', 'FX'))
        grid.calculate('twice', '$x.pnl->toOne() * 2')
        self.assertEqual([d['name'] for d in page.to_dict()['cubes'][0]['cube']['query']['derived']], ['twice'])

    def test_a_layout_of_splits_and_sizes_is_kept_as_tiles_come_and_go(self):
        page, grid, chart = self.built()
        other = page.grid(desks(), name='desks')
        sheet = page.sheets[0]
        # the layout in the document's own words: the grid at 70%, the chart over the other grid beside it
        sheet.layout_from({'kind': 'bands', 'fit': False, 'bands': [{'height': 1.2, 'node': {'split': 'row', 'parts': [
            {'node': {'tile': grid.id}, 'size': 0.7},
            {'node': {'split': 'column', 'parts': [{'node': {'tile': chart.id}, 'size': 0.4},
                                                   {'node': {'tile': other.id}, 'size': 0.6}]}, 'size': 0.3}]}}]})
        self.assertFalse(page.to_dict()['sheets'][0]['layout']['fit'])
        # a grid added keeps it, in a band of its own at the bottom
        heads = page.grid(desks(), name='heads')
        bands = self.bands(sheet)
        self.assertEqual(bands[0]['node']['parts'][0], {'node': {'tile': grid.id}, 'size': 0.7})
        self.assertEqual(bands[1], {'height': 0.5, 'node': {'tile': heads.id}})
        # one taken off: the column it was in gives its share to the tile left, which takes the column's place
        page.remove(chart)
        self.assertEqual(self.bands(sheet)[0], {'height': 1.2, 'node': {'split': 'row', 'parts': [
            {'node': {'tile': grid.id}, 'size': 0.7}, {'node': {'tile': other.id}, 'size': 0.3}]}})
        # a chart goes beside its grid, its band's places evened out, as DataCube adds one
        added = other.chart('pie', x='desk', y=[('desk', 'count')], title='Desks')
        self.assertEqual([p['node'] for p in self.bands(sheet)[0]['node']['parts']],
                         [{'tile': grid.id}, {'tile': other.id}, {'tile': added.id}])
        self.assertEqual([p['size'] for p in self.bands(sheet)[0]['node']['parts']], [1 / 3] * 3)
        with self.assertRaisesRegex(ValueError, 'cannot be laid out: band 0: shares summing to 1.4'):
            sheet.layout_from({'kind': 'bands', 'fit': True, 'bands': [{'height': 1, 'node': {'split': 'row', 'parts': [
                {'node': {'tile': grid.id}, 'size': 0.7}, {'node': {'tile': other.id}, 'size': 0.7}]}},
                {'height': 1, 'node': {'split': 'row', 'parts': [{'node': {'tile': added.id}, 'size': 0.5},
                                                                 {'node': {'tile': heads.id}, 'size': 0.5}]}}]})
        # a layout leaving a tile of the sheet out, refused by the layout command (page-commands.ts): every tile once
        with self.assertRaisesRegex(ValueError, "a sheet's layout holds its tiles, each once"):
            sheet.layout_from({'kind': 'bands', 'fit': True, 'bands': [{'height': 1, 'node': {'tile': grid.id}}]})
        # its shape read as a saved page's sheet is, refused in the page reader's words -- never kept to be refused only
        # when the page is saved, nor a JavaScript error (the phase 1 audit's 1)
        with self.assertRaisesRegex(ValueError, "the layout's fit is not true or false"):
            sheet.layout_from({'kind': 'bands', 'fit': 'yes', 'bands': [{'height': 1, 'node': {'tile': grid.id}}]})
        with self.assertRaisesRegex(ValueError, "the layout's bands are not a list"):
            sheet.layout_from({'kind': 'bands', 'fit': True})

    def test_a_sheet_gone_is_refused_its_layout_and_an_empty_one_laid_out_in_no_bands_is_as_it_was(self):
        # (the audit of neema2/legend-lite#47) an empty sheet laid out in no bands is as it is -- no shape names no
        # bands -- and a sheet no longer on its page is refused, layout_from too, as every sheet call refuses one
        page, grid, chart = self.built()
        empty = page.sheet('Empty')
        self.assertIs(empty.layout(), empty)
        self.assertEqual(self.bands(empty), [])
        # taken out as reading the open page takes out a sheet closed there (Page.read: the document, then its handles)
        page._doc['sheets'] = [s for s in page._doc['sheets'] if s['id'] != empty.id]
        page._forget({empty.id})
        with self.assertRaisesRegex(ValueError, 'no longer on its page'):
            empty.layout_from({'kind': 'bands', 'fit': True, 'bands': []})

    def test_a_grid_beside_others_is_capped_as_datacube_caps_a_source_added_beside_others(self):
        # DataCube's one rule (gridCapped), of the page's grids counting the new one: the page's first grid is not capped,
        # one beside it is
        page, grid, chart = self.built()
        other = page.grid(desks(), name='desks')
        cubes = {c['id']: c['cube'] for c in page.to_dict()['cubes']}
        self.assertNotIn('maxRows', cubes[grid._view['cube']]['configuration'])
        self.assertIsInstance(cubes[other._view['cube']]['configuration']['maxRows'], int)

    def test_an_empty_list_of_conditions_is_no_filter(self):
        # the filter editor's normal form: an empty group is none, so [] takes the filter off, as None does
        page, grid, chart = self.built()
        grid.filter([])
        self.assertNotIn('filter', page.to_dict()['cubes'][0]['cube']['query'])

    def test_sheets_layouts_and_stacks(self):
        page, grid, chart = self.built()
        other = page.grid(desks(), name='desks')
        summary = page.sheet('Summary')
        summary.add(chart, other)
        summary.layout([other], [chart])
        doc = page.to_dict()
        self.assertEqual([(s['id'], s.get('name')) for s in doc['sheets']], [('sheet-1', None), ('sheet-2', 'Summary')])
        self.assertEqual(doc['sheets'][1]['layout']['bands'], [
            {'height': 0.5, 'node': {'tile': other.id}}, {'height': 0.5, 'node': {'tile': chart.id}}])
        self.assertEqual(chart.sheet, summary)
        # two tiles in one place, as tabs, where the first was (the band the second emptied gone)
        page.stack(other, chart)
        self.assertEqual(self.bands(summary), [{'height': 0.5, 'node': {'stack': [other.id, chart.id]}}])
        # onto a tile stacked already, as DataCube drops one on its middle: its place keeps its tabs, the new one after
        third = other.chart('pie', x='desk', y=[('desk', 'count')], title='Desks')
        self.assertEqual(self.bands(summary)[0]['node']['parts'][1]['node'], {'tile': third.id})
        page.stack(other, third)
        self.assertEqual(self.bands(summary), [{'height': 0.5, 'node': {'stack': [other.id, chart.id, third.id]}}])
        with self.assertRaisesRegex(ValueError, 'one sheet'):
            page.stack(grid, chart)
        summary.arrange('side-by-side')
        self.assertEqual(summary.tiles, [other, chart, third])
        self.assertEqual(self.bands(summary), [{'height': 1, 'node': {'stack': [other.id, chart.id, third.id]}}])
        # the layout picker's shapes, as DataCube names them; one it does not name is refused in its words
        with self.assertRaisesRegex(ValueError, 'not a layout: diagonal'):
            summary.arrange('diagonal')
        self.assertIs(page.sheets[1], summary, 'the same handle each time')

    def test_a_chart_takes_the_spec_version_2_slots_in_their_own_words(self):
        page, grid, _ = self.built()
        chart = grid.chart('bar', x='region', y=[
            ('notional', 'sum'),
            {'column': 'pnl', 'fn': 'average', 'mark': 'line', 'axis': 'right', 'showAs': {'kind': 'movingAverage', 'rows': 3}},
        ], label=('pnl', 'max'), tooltip=[('notional', 'max')],
            layers=[{'kind': 'line', 'value': 100, 'label': 'Target'}, {'kind': 'stat', 'stat': 'average'},
                    {'kind': 'band', 'axis': 'y', 'from': 0, 'to': 50}])
        spec = page.to_dict()['views'][-1]['spec']
        self.assertEqual(spec['version'], 2)
        self.assertEqual(spec['y'][1], {'column': 'pnl', 'fn': 'average', 'mark': 'line', 'axis': 'right',
                                        'showAs': {'kind': 'movingAverage', 'rows': 3}})
        self.assertEqual(spec['label'], {'column': 'pnl', 'fn': 'max'})
        self.assertEqual([layer['kind'] for layer in spec['layers']], ['line', 'stat', 'band'])
        # bins on a number across, kept while it stays across and gone with it
        binned = grid.chart('bar', x='notional', bins={'width': 50}, y=[('pnl', 'count')])
        self.assertEqual(page.to_dict()['views'][-1]['spec']['bins'], {'width': 50})
        binned.plot(x='region')
        self.assertNotIn('bins', page.to_dict()['views'][-1]['spec'])
        # re-plotted, a measure's own words survive
        chart.plot('line')
        self.assertEqual(page.to_dict()['views'][-2]['spec']['y'][1]['mark'], 'line')
        # every word one of DataCube's, and nothing DataCube would refuse to draw, refused at the line that names it, in
        # DataCube's own words (its reader and its rules, asked: _made) -- each chart told its column across, so frozen
        # as a hand on it in DataCube freezes it, and judged as written
        for bad, why in [({'grain': 'decade'}, 'grain "decade" is not one of'),
                         ({'grain': 'month'}, "'region' is not a date"),
                         ({'bins': {'width': 3}}, "'region' is not a number"),
                         ({'y': [{'column': 'notional', 'fn': 'sum', 'mark': 'pie'}]}, "measure 1's mark"),
                         ({'y': [{'column': 'notional', 'fn': 'sum', 'axis': 'top'}]}, "measure 1's axis"),
                         ({'y': [{'column': 'notional', 'fn': 'sum', 'showAs': {'kind': 'forecast'}}]}, 'shown as something'),
                         ({'y': [{'column': 'margin', 'fn': 'value'}]}, 'no group-level column'),
                         ({'y': [('notional', 'wavg')]}, 'no weighted average'),
                         ({'layers': [{'kind': 'sparkle'}]}, 'layer 1 is of a kind'),
                         ({'layers': [{'kind': 'stat', 'stat': 'mode'}]}, "layer 1's statistic"),
                         ({'layers': [{'kind': 'line', 'value': True}]}, 'layer 1 has no value'),
                         ({'layers': [{'kind': 'stat', 'stat': 'average', 'measure': 2}]}, 'reads value 3'),
                         ({'layers': [{'kind': 'extremes'}], 'options': {'stack': 'percent'}}, '100% stack'),
                         ({'options': {'stack': 'stacked100'}}, 'stack "stacked100" is not one of'),
                         ({'bins': {'size': 3}}, 'neither a width nor a count'),
                         ({'bins': {'width': '10'}}, 'neither a width nor a count'),
                         ({'bins': {'width': 0}}, 'not a positive number'),
                         ({'bins': {'count': 2.5}}, 'not a whole number'),
                         ({'options': {'limit': 0}}, 'limit 0 is not a whole number'),
                         # what Python's arguments carry goes as given: DataCube's reader judges its shape too
                         ({'y': [('notional',)]}, 'measure 1 is not a column and an aggregate'),
                         ({'y': [{'column': 'notional', 'fn': 'sum', 'colour': 'red'}]}, 'no "colour" in measure 1'),
                         ({'y': 'notional'}, 'its y "notional" is not a list of measures'),
                         ({'options': {'stak': 'stacked'}}, 'no "stak" in its options'),
                         ({'options': 'wide'}, 'its options "wide" are not an object'),
                         ({'layers': [{'kind': 'line', 'value': 1, 'mesure': 1}]}, 'no "mesure" in layer 1'),
                         ({'bins': {'width': 5, 'cnt': 3}}, 'no "cnt" in its bins'),
                         ({'bins': 50}, 'neither a width nor a count')]:
            with self.subTest(bad=bad), self.assertRaisesRegex(ValueError, why):
                grid.chart('bar', x='region', **{'y': [('notional', 'sum')], **bad})
        with self.assertRaisesRegex(ValueError, 'no reference lines'):
            grid.chart('scatter', x='notional', y=[('pnl', 'sum')], layers=[{'kind': 'line', 'value': 1}])
        # small multiples and a bubble (batch 3), each refused where DataCube does not draw it
        grid.chart('bar', x='region', facet={'column': 'desk'})
        self.assertEqual(page.to_dict()['views'][-1]['spec']['facet'], {'column': 'desk'})
        grid.chart('scatter', x='notional', y=[('pnl', 'sum')], size=('notional', 'sum'))
        self.assertEqual(page.to_dict()['views'][-1]['spec']['size'], {'column': 'notional', 'fn': 'sum'})
        for mark, given, why in [('pie', {'x': 'region', 'facet': {'column': 'desk'}}, 'not drawn as small multiples'),
                                 ('bar', {'x': 'region', 'facet': {'column': 'region'}}, 'across or split already'),
                                 ('bar', {'x': 'region', 'facet': {'side': 'desk'}}, 'names neither a row nor a column'),
                                 ('bar', {'x': 'region', 'facet': {'column': 'desk', 'rows': 'pnl'}}, 'no "rows" in its facet'),
                                 ('bar', {'x': 'region', 'facet': {'column': 'desk'}, 'options': {'brush': True}}, 'no brush'),
                                 ('bar', {'x': 'region', 'size': ('pnl', 'sum')}, 'Only a scatter'),
                                 ('bar', {'x': 'region', 'facet': {'row': 'desk', 'column': 'desk'}}, 'two columns'),
                                 ('waterfall', {'x': 'region', 'y': [{'column': 'pnl', 'fn': 'sum', 'showAs': {'kind': 'rank'}}]}, 'shown as something else'),
                                 ('matrix', {'y': [{'column': 'pnl', 'fn': 'sum', 'showAs': {'kind': 'rank'}}, ('notional', 'sum')]}, 'plots rows')]:
            with self.subTest(mark=mark, given=given), self.assertRaisesRegex(ValueError, why):
                grid.chart(mark, **given)
        # a live chart follows its grid -- regrouped the way the grid is (DataCube's followCube), each value on the grid's
        # Aggregation of its column -- so a value told another aggregate and told to follow is refused: the grid's
        # Aggregation would replace it; values on the grid's Aggregations follow it, two of them too
        with self.assertRaisesRegex(ValueError, "follows its grid takes sum of 'notional', sum of 'pnl' as its values, "
                                                "not max of 'notional', sum of 'pnl'"):
            grid.chart('bar', x='region', y=[('notional', 'max'), ('pnl', 'sum')], frozen=False)
        grid.chart('bar', x='region', y=[('notional', 'sum'), ('pnl', 'sum')], frozen=False,
                   layers=[{'kind': 'line', 'value': 1, 'measure': 1}])
        # and a chart written fine cannot be changed into one DataCube refuses: by plot or by options
        lined = grid.chart('bar', x='region', y=[('notional', 'sum'), ('pnl', 'sum')],
                           layers=[{'kind': 'stat', 'stat': 'average'}, {'kind': 'line', 'value': 1, 'measure': 1}])
        kept = page.to_dict()['views'][-1]['spec']
        with self.assertRaisesRegex(ValueError, 'no reference lines'):
            lined.plot('scatter', x='notional')
        with self.assertRaisesRegex(ValueError, 'reads value 2'):
            lined.plot('bar', y=[('notional', 'sum')])
        with self.assertRaisesRegex(ValueError, '100% stack'):
            lined.options({'stack': 'percent'})
        self.assertEqual(page.to_dict()['views'][-1]['spec'], kept, 'a refused change leaves the chart as it was')

    def test_charts_are_refused_exactly_where_datacube_refuses_them(self):
        """DataCube's chart rules, as cases (datacube/test/fixtures/chart-rules.json), which chart-spec.ts is held to:
        Python refuses exactly the same charts by asking DataCube's own code (page._made), never by rules of its own. A
        frame makes the cases' String, Float, StrictDate and DateTime columns; a Date and a StrictTime are calculated
        columns' (Pure's firstDayOfMonth returns a Date, and a time of day is written %10:30:00), judged with the
        compiler's types, as DataCube judges them. Every case is asked, none skipped."""
        rules = json.loads(Path(os.environ['LEGEND_LITE_CHART_RULES']).read_text('utf-8'))
        made = {'String': ['EMEA', 'APAC'], 'Float': [1.5, 2.5], 'StrictDate': [datetime.date(2024, 1, 1), datetime.date(2024, 1, 2)],
                'DateTime': [pd.Timestamp('2024-01-01 09:30'), pd.Timestamp('2024-01-01 10:30')]}
        framed = [c for c in rules['columns'] if c['type'] in made]
        page = ll.Page('rules')
        grid = page.grid(pd.DataFrame({c['name']: made[c['type']] for c in framed}), name='rules')
        types = {c.name: plain_type(c.type) for c in grid._page._session.frames[grid.frame].columns}
        self.assertEqual(types, {c['name']: c['type'] for c in framed}, 'the frame is typed as the cases say')
        days = next(c['name'] for c in framed if c['type'] == 'StrictDate')
        dated = next(c['name'] for c in rules['columns'] if c['type'] == 'Date')
        grid.calculate(dated, f'$x.{days}->toOne()->firstDayOfMonth()')
        timed = next(c['name'] for c in rules['columns'] if c['type'] == 'StrictTime')
        self.assertEqual({c['type'] for c in rules['columns']} - set(made), {'Date', 'StrictTime'}, 'the cases\' types')
        # a time of day is lowered as SQL's TIME (main's 5f7d6ed, 2026-10-17): calculated like the Date
        grid.calculate(timed, '%10:30:00')
        cube = grid._view['cube']
        for case in rules['cases']:
            with self.subTest(case=case['case']):
                if case['refused']:
                    with self.assertRaises(ValueError):
                        page_module._made(page, cube, case['spec'])
                else:
                    page_module._made(page, cube, case['spec'])

    def test_batch_3_kinds_are_written_and_refused_as_datacube_draws_them(self):
        page, grid, _ = self.built()
        # each new kind, written with what it needs
        grid.chart('waterfall', x='desk')
        grid.chart('dumbbell', x='region', y=[('pnl', 'sum'), ('notional', 'sum')])
        # a flow's second column is its own grouping, not the grid's: told it, the chart keeps it (frozen)
        grid.chart('sankey', x='region', split='desk')
        grid.chart('radar', x='desk', y=[('notional', 'sum'), ('pnl', 'sum'), ('notional', 'max')])
        grid.chart('gauge', options={'target': 1000})
        grid.chart('sunburst')
        grid.chart('matrix', y=[('notional', 'sum'), ('pnl', 'sum')])
        marks = [v['spec']['mark'] for v in page.to_dict()['views'] if v['kind'] == 'chart']
        self.assertEqual(marks[-7:], ['waterfall', 'dumbbell', 'sankey', 'radar', 'gauge', 'sunburst', 'matrix'])
        # and what DataCube would refuse to draw, refused at the line that writes it, in its words
        for mark, given, why in [('sankey', {'x': 'region'}, 'needs a second column'),
                                 ('funnel', {'x': 'region', 'split': 'desk'}, 'one series'),
                                 ('dumbbell', {'x': 'region'}, 'two values'),
                                 ('radar', {'x': 'region'}, 'three values'),
                                 ('matrix', {'x': 'region', 'y': [('pnl', 'sum'), ('notional', 'sum')]}, 'has no column across'),
                                 ('matrix', {'y': [('pnl', 'sum')]}, 'two to 5'),
                                 ('gauge', {}, 'needs a target'),
                                 ('gauge', {'x': 'region', 'options': {'target': 1}}, 'one value'),
                                 ('pareto', {'x': 'region', 'options': {'sort': {'by': 'x', 'direction': 'asc'}}}, 'largest'),
                                 ('calendar', {'x': 'region'}, 'runs along dates'),
                                 ('rose', {'x': 'region', 'layers': [{'kind': 'line', 'value': 1}]}, 'no reference lines'),
                                 ('chord', {'x': 'region', 'split': 'desk', 'tooltip': [('pnl', 'sum')]}, 'no further measures')]:
            with self.subTest(mark=mark, given=given), self.assertRaisesRegex(ValueError, why):
                grid.chart(mark, **given)

    def test_batch_4_a_box_plot_plots_one_columns_five_figures_as_datacube_draws_it(self):
        page, grid, _ = self.built()
        # told the kind alone, the five figures of what DataCube's own new chart would plot: the grid's measure
        grid.chart('boxplot', x='desk')
        spec = [v['spec'] for v in page.to_dict()['views'] if v['kind'] == 'chart'][-1]
        five = ('min', 'percentile25', 'median', 'percentile75', 'max')
        self.assertEqual((spec['mark'], [(m['column'], m['fn']) for m in spec['y']]),
                         ('boxplot', [('notional', fn) for fn in five]))
        grid.chart('boxplot', x='region', y=[('pnl', fn) for fn in five])
        # and what DataCube would refuse to draw, refused in its words
        for given, why in [({'y': [('pnl', 'sum')]}, 'five figures of one column'),
                           ({'y': [('desk', fn) for fn in five]}, 'not a number'),
                           ({'split': 'region'}, 'one series')]:
            with self.subTest(given=given), self.assertRaisesRegex(ValueError, why):
                grid.chart('boxplot', x='desk', **given)

    def test_batch_5_maps_are_written_and_refused_as_datacube_draws_them(self):
        page = ll.Page('Maps')
        sales = pd.DataFrame({'country': ['USA', 'FRA', 'GBR'], 'city': ['New York', 'Paris', 'London'],
                              'lat': [40.71, 48.86, 51.51], 'lon': [-74.01, 2.35, -0.13], 'revenue': [500.0, 300.0, 200.0]})
        grid = page.grid(sales, name='sales_geo', rows=['country'], measures={'revenue': 'sum'})
        # a map of each country by its code; a bubble at each city, where its latitude and longitude say
        grid.chart('map', x='country')
        grid.chart('bubblemap', x='city', at={'lat': 'lat', 'lon': 'lon'})
        specs = [v['spec'] for v in page.to_dict()['views'] if v['kind'] == 'chart']
        self.assertEqual([s['mark'] for s in specs], ['map', 'bubblemap'])
        self.assertEqual(specs[1]['at'], {'lat': 'lat', 'lon': 'lon'})
        # a map asks for the world's countries, not DataCube's first fifty
        self.assertEqual(specs[0]['options']['limit'], 250)
        for mark, given, why in [('map', {'x': 'revenue'}, 'not text'),
                                 ('bubblemap', {'x': 'city'}, 'latitude'),
                                 ('bubblemap', {'x': 'city', 'at': {'lat': 'city', 'lon': 'lon'}}, 'not a number'),
                                 ('bubblemap', {'x': 'city', 'at': {'lat': 'lat', 'height': 'lon'}}, 'its places'),
                                 ('bar', {'x': 'city', 'at': {'lat': 'lat', 'lon': 'lon'}}, 'places nothing')]:
            with self.subTest(mark=mark, given=given), self.assertRaisesRegex(ValueError, why):
                grid.chart(mark, **given)
        # a chart made a bubble map by plot, told its places; and its places changed (the phase 6 audit)
        bar = grid.chart('bar', x='city')
        bar.plot('bubblemap', at={'lat': 'lat', 'lon': 'lon'})
        bubbles = [v['spec'] for v in page.to_dict()['views'] if v['kind'] == 'chart'][-1]
        self.assertEqual((bubbles['mark'], bubbles['at']), ('bubblemap', {'lat': 'lat', 'lon': 'lon'}))
        with self.assertRaisesRegex(ValueError, 'longitude'):
            bar.plot(at={'lat': 'lat'})

    def test_batch_3_leftovers_are_written_and_refused_as_datacube_draws_them(self):
        page = ll.Page('Leftovers')
        tasks = pd.DataFrame({'desk': ['FX', 'FX', 'Rates'], 'region': ['EMEA', 'AMER', 'EMEA'],
                              'day': pd.to_datetime(['2024-01-02', '2024-03-04', '2024-02-01']).date,
                              'pnl': [1.5, 2.0, 0.5]})
        grid = page.grid(tasks, name='tasks', rows=['desk'], measures={'pnl': 'sum'})
        grid.chart('waffle', x='desk')
        # a Marimekko lays its columns out in their order across: DataCube's new chart of one takes that order
        grid.chart('marimekko', x='region', split='desk')
        grid.chart('gantt', x='desk', y=[('day', 'min'), ('day', 'max')])
        specs = [v['spec'] for v in page.to_dict()['views'] if v['kind'] == 'chart']
        self.assertEqual([s['mark'] for s in specs], ['waffle', 'marimekko', 'gantt'])
        self.assertEqual(specs[1]['options']['sort'], {'by': 'x', 'direction': 'asc'})
        for mark, given, why in [('marimekko', {'x': 'region'}, 'made of'),
                                 ('marimekko', {'x': 'region', 'split': 'desk', 'options': {'sort': {'by': 'y', 'direction': 'desc'}}}, 'order by category'),
                                 ('waffle', {'x': 'desk', 'y': [('pnl', 'sum'), ('pnl', 'max')]}, 'one value'),
                                 ('gantt', {'x': 'desk'}, 'min or max of a date column'),
                                 ('gantt', {'x': 'desk', 'y': [('day', 'min'), ('pnl', 'max')]}, 'min or max of a date column')]:
            with self.subTest(mark=mark, given=given), self.assertRaisesRegex(ValueError, why):
                grid.chart(mark, **given)

    def test_a_chart_plots_what_it_is_told_and_a_frozen_one_stays_when_its_grid_goes(self):
        page, grid, chart = self.built()
        # a hand on its split freezes it, as in DataCube's options
        chart.plot('line', split='desk')
        chart.options({'labels': True})
        spec = page.to_dict()['views'][1]['spec']
        self.assertEqual({k: v for k, v in spec.items() if k != 'options'},
                         {'version': 2, 'mark': 'line', 'x': 'region', 'y': [{'column': 'notional', 'fn': 'sum'}], 'split': 'desk',
                          'frozen': True})
        self.assertTrue(spec['options']['labels'])
        # a line with nothing across, as written, is no chart DataCube draws: refused, and the chart kept as it was
        with self.assertRaisesRegex(ValueError, 'Pick a column across'):
            chart.plot(x=None)
        self.assertEqual(page.to_dict()['views'][1]['spec'], spec)
        with self.assertRaisesRegex(ValueError, 'a chart has a title'):
            chart.title = None
        # its grid removed, a frozen chart stays, over its grid's cube (DataCube's detached chart), as a following one
        # -- told nothing of its grouping -- does not
        follows = grid.chart('pie')
        self.assertNotIn('frozen', page.to_dict()['views'][-1]['spec'])
        page.remove(grid)
        self.assertEqual((page.grids, page.charts, chart.grid), ([], [chart], None))
        doc = page.to_dict()
        self.assertEqual([c['id'] for c in doc['cubes']], [grid.id])
        self.assertEqual([v['id'] for v in doc['views']], [chart.id])
        with self.assertRaisesRegex(ValueError, 'no longer on its page'):
            follows.options({'labels': True})
        # and a page so loads again
        self.assertEqual(ll.Page.load(doc, frames={'trades': trades()}).to_dict(), doc)
        # a detached chart is still a chart of its cube: changed, it is made and judged against it
        chart.plot('bar')
        self.assertEqual(page.to_dict()['views'][0]['spec']['mark'], 'bar')

    def test_a_calculated_column_is_checked_by_the_compiler_where_it_is_added(self):
        page, grid, _ = self.built()
        # a dataframe's columns can be empty: arithmetic takes them toOne(), as DataCube's compiler asks
        grid.calculate('margin', '$x.pnl->toOne() / $x.notional->toOne()')
        derived = page.to_dict()['cubes'][0]['cube']['query']['derived']
        self.assertEqual(derived[0]['name'], 'margin')
        self.assertEqual(derived[0]['lambda']['_type'], 'lambda')
        # added as DataCube's column editor adds one: a measure unless told, as the editor starts one (a number summed
        # when grouped, anything else its unique value), declared as told; refused in DataCube's and the compiler's
        # words -- a column it does not have, a multiplicity, a name the grid has already, a kind DataCube has not
        self.assertEqual(derived[0]['kind'], 'measure')
        grid.calculate('loss', '$x.pnl->toOne() < 0', kind='dimension')
        self.assertEqual(page.to_dict()['cubes'][0]['cube']['query']['derived'][-1]['kind'], 'dimension')
        with self.assertRaisesRegex(ValueError, "a column's kind is 'dimension' or 'measure', not 'sum'"):
            grid.calculate('odd', '$x.pnl', kind='sum')
        with self.assertRaisesRegex(ValueError, 'nope'):
            grid.calculate('wrong', '$x.nope * 2')
        with self.assertRaisesRegex(ValueError, 'multiplicity'):
            grid.calculate('nullable', '$x.pnl / $x.notional')
        with self.assertRaisesRegex(ValueError, "'margin' is already a column"):
            grid.calculate('margin', '$x.pnl')
        with self.assertRaisesRegex(ValueError, 'a calculated column needs a name'):
            grid.calculate(' ', '$x.pnl')
        # one reads the calculated columns before it, typed as the grid runs them
        grid.calculate('margin_pct', '$x.margin * 100')
        self.assertEqual([d['name'] for d in page.to_dict()['cubes'][0]['cube']['query']['derived']],
                         ['margin', 'loss', 'margin_pct'])
        # a chart can plot it, as any column
        grid.chart('line', x='region', y=[('margin', 'average')])

    def test_a_grid_is_edited_as_datacubes_screen_edits_it_and_refused_in_its_words(self):
        # grouping, pivot, Column Properties and sorts by the one module the screen's panels, header menu and drag zones
        # call (grid-commands.ts, headless gridApply: docs/DATACUBE_COMMANDS_STAGE1_2026_10_18.md), the grid compiled as
        # Apply compiles it
        page, grid, _ = self.built()
        before = page.to_dict()
        # an aggregate Aggregation does not offer for the column's type
        with self.assertRaisesRegex(ValueError, "'sum' is not offered on desk, a String column: min, max, joinStrings"):
            grid.measure('desk', 'sum', name='desks')
        # only a weighted average takes a weight, refused rather than dropped; and it takes one (the query's own words)
        with self.assertRaisesRegex(ValueError, r'only a weighted average \(wavg\) takes a weight'):
            grid.measure('pnl', 'sum', name='p', weight='notional')
        with self.assertRaisesRegex(ValueError, 'uses wavg but has no weight column'):
            grid.measure('pnl', 'wavg', name='w')
        with self.assertRaisesRegex(ValueError, "a sort is 'asc' or 'desc', not 'up'"):
            grid.sort('region', 'up')
        # the screen groups and pivots by dimensions: a number is a measure until Column Properties makes it one
        with self.assertRaisesRegex(ValueError, "'nope' is not a column the row groups can take: the grid's dimensions "
                                                'are desk, region'):
            grid.group('nope')
        with self.assertRaisesRegex(ValueError, "'pnl' is a measure, and the row groups take dimensions"):
            grid.group('region', 'pnl')
        with self.assertRaisesRegex(ValueError, "the row groups name 'region' twice"):
            grid.group('region', 'region')
        # a measure of a grouped column is a calculated column of its own; a count of text is offered nowhere
        with self.assertRaisesRegex(ValueError, r"'count' is not offered on region, a String column: .* \(Show leaf "
                                                r"count counts a group's rows\)"):
            grid.measure('region', 'count', name='trades')
        with self.assertRaisesRegex(ValueError, "'desk' is already a column"):
            grid.measure('pnl', 'sum', name='desk')
        with self.assertRaisesRegex(ValueError, "'nope' is not a column of the grid"):
            grid.measure('nope', 'count', name='n')
        # no Aggregation is assumed for a measure (DataCube's default is its type's: column(name, kind='measure'))
        with self.assertRaises(TypeError):
            grid.measure('pnl')
        # Column Properties' own rules: a calculated column's kind is its editor's; a dimension takes no Aggregation
        with self.assertRaisesRegex(ValueError, "'region' is a dimension, and only a measure takes an Aggregation"):
            grid.column('region', aggregate='unique')
        with self.assertRaisesRegex(ValueError, "'region' is grouped or pivoted on: its kind cannot change"):
            grid.column('region', kind='measure')
        # a measure of a dimension is its Aggregation, which Column Properties refuses a dimension
        with self.assertRaisesRegex(ValueError, "'desk' is a dimension, and only a measure takes an Aggregation"):
            grid.measure('desk', 'unique')
        # configure goes by the same rules for a column's kind and Aggregation: a kind is set, never cleared
        with self.assertRaisesRegex(ValueError, "'count' is not offered on desk"):
            grid.configure({'columns': {'desk': {'kind': 'measure', 'aggregateFn': 'count'}}})
        with self.assertRaisesRegex(ValueError, "desk's kind is set, never cleared"):
            grid.configure({'columns': {'desk': {'kind': None}}})
        with self.assertRaisesRegex(ValueError, "desk's excludedFromPivot is true or false"):
            grid.configure({'columns': {'desk': {'excludedFromPivot': None}}})
        # a setting DataCube has not, a column the grid has not, a setting's name misspelt: refused, never kept
        with self.assertRaisesRegex(ValueError, "'maxRow' is not a grid's setting"):
            grid.configure({'maxRow': 5})
        with self.assertRaisesRegex(ValueError, "'notionl' is not a column of the grid"):
            grid.configure({'columns': {'notionl': {'hidden': True}}})
        with self.assertRaisesRegex(ValueError, "'aggregatefn' is not a column's setting"):
            grid.configure({'columns': {'pnl': {'aggregatefn': 'max'}}})
        with self.assertRaisesRegex(ValueError, "'fontsize' is not an appearance setting"):
            grid.configure({'appearance': {'fontsize': 14}})
        # a command DataCube does not know, or a field it does not take, is refused, never ignored (grid-commands.ts)
        with self.assertRaisesRegex(ValueError, r"'pivots' is not a grid's command: group, pivot, "):
            grid._apply([{'kind': 'pivots', 'columns': ['desk']}])
        with self.assertRaisesRegex(ValueError, r"a 'pivot' command takes columns, not pivotOn"):
            grid._apply([{'kind': 'pivot', 'pivotOn': ['desk']}])
        self.assertEqual(page.to_dict(), before, 'nothing refused changed the page')
        # a measure is its column's Aggregation, replacing the one it had; a named one, a calculated column with its own;
        # a weighted average with its weight; a count of a number; a sort on a calculated column
        grid.measure('notional', 'average')
        grid.measure('pnl', 'wavg', name='w_pnl', weight='notional').measure('pnl', 'count', name='trades')
        grid.measure('notional', 'max', name=' top_notional ').sort('w_pnl', 'desc')
        cube = page.to_dict()['cubes'][0]['cube']
        self.assertNotIn('measures', cube['query'])
        self.assertEqual(cube['configuration']['columns']['notional'], {'aggregateFn': 'average'})
        self.assertEqual([d['name'] for d in cube['query']['derived']], ['w_pnl', 'trades', 'top_notional'])
        self.assertEqual(cube['configuration']['columns']['w_pnl'],
                         {'aggregateFn': 'wavg', 'aggregationParameters': ['notional']})
        self.assertEqual(cube['query']['sorts'][-1], {'column': 'w_pnl', 'direction': 'desc'})
        # kept out of the pivot and back in, explicitly: each reaches the query (a measure spread again)
        grid.configure({'columns': {'pnl': {'excludedFromPivot': True}}})
        self.assertIs(next(c for c in grid._query['columns'] if c['name'] == 'pnl')['excludedFromPivot'], True)
        grid.configure({'columns': {'pnl': {'excludedFromPivot': False}}})
        self.assertIs(next(c for c in grid._query['columns'] if c['name'] == 'pnl')['excludedFromPivot'], False)
        # Column Properties' "none": the Aggregation cleared, the column on its type's default again
        grid.column('notional', aggregate=None)
        cube = page.to_dict()['cubes'][0]['cube']
        self.assertNotIn('notional', cube['configuration'].get('columns', {}))
        self.assertNotIn('aggregate', next(c for c in cube['query']['columns'] if c['name'] == 'notional'))
        names = {c['name'] for c in grid.view().columns}
        self.assertTrue({'notional', 'top_notional', 'w_pnl', 'trades'} <= names, names)
        # an integer made a dimension, as Column Properties makes one, is then grouped by
        counted = ll.Page('Counted').grid(trades().assign(year=2024), name='by_year')
        with self.assertRaisesRegex(ValueError, "'year' is a measure, and the row groups take dimensions"):
            counted.group('year')
        counted.column('year', kind='dimension').group('year')
        self.assertEqual(counted._query['rows'], ['year'])
        self.assertEqual(ll.Page('In one go').grid(trades().assign(year=2024), name='by_year_2', rows=['year'],
                                                     dimensions=['year'])._query['rows'], ['year'])

    def test_pandas_missing_value_is_no_nan_in_a_class_sample(self):
        # pandas' own missing value is no NaN in the rows: pa.Table.from_pandas reads it as null (frames.py), so a class's
        # sample writes it as null, as every missing value is -- only a NaN the data holds is refused (test_model)
        missing = ll.Page('Missing').grid(pd.DataFrame({'desk': ['FX', 'EQ'], 'rate': [1.5, float('nan')]}),
                                          name='missing_rate')
        cls, asked = missing._class()
        (table,) = asked['tables']
        rows = cls['where']['samples'][table]['rows']
        self.assertEqual(sorted(r[1] is None for r in rows), [False, True])

    def test_a_sort_is_a_column_or_a_pivots_header_and_an_edit_keeps_its_open_groups(self):
        # a sort names a column the grid has, a calculated one too, or -- pivoted -- a pivot's header, as the screen's
        # Sorts panel and header menu offer them (grid-edits.ts setSorts); a sort of a column replaces the one it had
        detailed = ll.Page('Detailed').grid(trades(), name='detailed_trades', rows=['region'])
        detailed.measure('notional', 'max', name='top').sort('notional', 'desc').sort('top').sort('notional', 'asc')
        self.assertEqual(detailed._query['sorts'], [{'column': 'top', 'direction': 'asc'},
                                                    {'column': 'notional', 'direction': 'asc'}])
        # an add that would change nothing changes nothing, as the menu offers none (grid-edits.ts addSort)
        detailed.sort('top', 'asc')
        self.assertEqual(detailed._query['sorts'][0], {'column': 'top', 'direction': 'asc'})
        page = ll.Page('Sorted')
        grid = page.grid(trades(), name='sorted_trades', columns=['desk'], measures={'notional': 'sum'})
        with self.assertRaisesRegex(ValueError, "the sort names 'nope', which the grid does not have"):
            grid.sort('nope')
        grid.sort('FX__|__notional', 'desc')
        self.assertEqual(grid._query['sorts'], [{'column': 'FX__|__notional', 'direction': 'desc'}])
        grid.sort('FX__|__notional', 'asc')
        grid.pivot()
        self.assertEqual(grid._query['sorts'], [], "unpivoted: the pivot header's sort goes with its headers")
        grid.group('region')
        # its open groups, as DataCube keeps them: the screen's edits leave them, a regrouping too (app.ts #edit) -- a
        # path the new grouping has not is shown closed
        page._doc['cubes'][0]['cube']['tree'] = {'open': [['EMEA']], 'showTotals': True}
        grid.sort('region', 'desc')
        self.assertEqual(page.to_dict()['cubes'][0]['cube']['tree'], {'open': [['EMEA']], 'showTotals': True})
        grid.group('desk')
        self.assertEqual(page.to_dict()['cubes'][0]['cube']['tree'], {'open': [['EMEA']], 'showTotals': True})

    def test_names_and_values_outside_datacubes_words_are_refused_where_they_are_written(self):
        page, grid, _ = self.built()
        before = page.to_dict()
        with self.assertRaisesRegex(ValueError, "not one of DataCube's filter operators"):
            grid.filter(('desk', 'isNot', 'FX'))
        with self.assertRaisesRegex(ValueError, 'not a column'):
            grid.filter(('nope', 'equal', 'FX'))
        with self.assertRaisesRegex(ValueError, 'takes no value'):
            grid.filter(('desk', 'isEmpty', 'FX'))
        with self.assertRaisesRegex(ValueError, 'a list of values, in their order'):
            grid.filter(('desk', 'in', {'FX', 'Rates'}))
        with self.assertRaisesRegex(ValueError, 'not several'):
            grid.filter(('desk', 'equal', ['FX']))
        with self.assertRaisesRegex(ValueError, 'one at least'):
            grid.filter(('desk', 'in', []))
        # an empty group is no filter only at the top (filter([]), below); inside a filter it is refused, never dropped
        with self.assertRaisesRegex(ValueError, "an 'and' needs one condition at least"):
            grid.filter(('or', [('desk', 'equal', 'FX'), ('and', [])]))
        with self.assertRaisesRegex(ValueError, "an 'or' needs one condition at least"):
            grid.filter(('not', ('or', [])))
        with self.assertRaisesRegex(ValueError, "'contains' is not offered on notional, a Float column"):
            grid.filter(('notional', 'contains', '1'))
        with self.assertRaisesRegex(ValueError, 'no overload'):
            grid.filter(('region', 'lessThan', {'relative': 'today'}))
        for wrong in (None, float('nan'), object(), datetime.datetime(2024, 1, 31)):
            with self.assertRaisesRegex(ValueError, 'takes text, a number'):
                grid.filter(('notional', 'greaterThan', wrong))
        with self.assertRaisesRegex(ValueError, "not one of DataCube's aggregates"):
            grid.measure('pnl', 'total')
        with self.assertRaisesRegex(ValueError, 'is not one this DataCube draws'):
            grid.chart('violin', x='region')
        with self.assertRaisesRegex(ValueError, 'not a column'):
            grid.group('nope')
        # DataCube's one name rule (page-names.ts givenName): a tile's or a page's blank is refused, in its words
        with self.assertRaisesRegex(ValueError, "a tile's name is not blank"):
            grid.chart('bar', title='')
        with self.assertRaisesRegex(ValueError, "a page's name is not blank"):
            page.name = '  '
        with self.assertRaisesRegex(ValueError, "a page's name: a page's name is not blank"):
            ll.Page('  ')
        with self.assertRaisesRegex(ValueError, 'is a name, not 3'):
            grid.title = 3
        self.assertEqual(page.to_dict(), before, 'nothing refused changed the page')
        # ... a sheet's blank is none of its own (named after its first grid), as the screen's rename makes it; a name
        # is kept trimmed, as the screen keeps one
        blank = page.sheet('  ')
        self.assertIsNone(blank.name)
        blank.name = ' Summary '
        self.assertEqual(blank.name, 'Summary')
        blank.name = ''
        self.assertIsNone(blank.name)
        # what it takes: nested groups and a negation, as the filter editor writes them; a date and a decimal as their
        # exact text, a relative date as DataCube keeps one
        grid.filter(('or', [('region', 'equal', 'EMEA'), ('not', ('desk', 'in', ['FX', 'Rates']))]))
        self.assertEqual(page.to_dict()['cubes'][0]['cube']['query']['filter']['children'][1],
                         {'kind': 'not', 'child': {'kind': 'condition', 'column': 'desk', 'operator': 'in', 'value': ['FX', 'Rates']}})
        grid.filter([('notional', 'greaterThan', Decimal('12.30')), ('region', 'equal', datetime.date(2024, 1, 31)),
                     ('notional', 'lessThan', 10)])
        self.assertEqual([c['value'] for c in page.to_dict()['cubes'][0]['cube']['query']['filter']['children']],
                         ['12.30', '2024-01-31', 10])
        dated = page.grid(pd.DataFrame({'day': [datetime.date(2024, 1, 1)], 'qty': [1]}), name='dated')
        dated.filter(('day', 'lessThan', {'relative': 'today'}))
        self.assertEqual(page.to_dict()['cubes'][1]['cube']['query']['filter']['value'], {'relative': 'today'})
        # a relative date's word is DataCube's to judge (gridFilter), never a list of Python's
        with self.assertRaisesRegex(ValueError, r'takes text, a number, a boolean, a date as its text, \{"relative"'):
            dated.filter(('day', 'lessThan', {'relative': 'yesterday'}))

    def test_a_grid_refused_leaves_the_page_and_the_frames_as_they_were(self):
        page, _, _ = self.built()
        before = page.to_dict()
        commands = list(page._commands)
        # the last: its grouping applied, then its filter refused -- the grouping's command taken back with the grid
        for given in ({'rows': ['nope']}, {'measures': {'head': 'total'}}, {'filter': ('head', 'nope', 1)},
                      {'sort': [('head', 'sideways')]}, {'rows': ['desk'], 'filter': ('head', 'nope', 1)}):
            with self.assertRaises(ValueError):
                page.grid(desks(), name='desks', **given)
            self.assertEqual(page.to_dict(), before)
            self.assertEqual(page._commands, commands, 'no command of the grid refused is kept')
            self.assertNotIn('desks', datacube._session.frames, 'a frame served for the grid refused is served no more')
        self.assertEqual(page.grid(desks(), name='desks').id, 'grid-2', 'the ids go on as if nothing was refused')

    def test_a_name_served_already_is_shared_by_its_own_frame_and_refused_to_another(self):
        page = ll.Page('Two over one')
        df = trades()
        first = page.grid(df, name='trades', rows=['region'])
        second = page.grid(df, name='trades', rows=['desk'])
        self.assertEqual([g.frame for g in page.grids], ['trades', 'trades'])
        served = datacube._session.frames['trades']
        # another frame under the name: refused (grid.update replaces a frame), the name serving what it served
        for other, mode in ((trades(), 'live'), (df, 'snapped')):
            with self.assertRaisesRegex(ValueError, r'grid\.update\(frame\) replaces a frame'):
                page.grid(other, name='trades', mode=mode)
        self.assertIs(datacube._session.frames['trades'], served)
        self.assertEqual(page.grids, [first, second])
        # and a refused grid over its own frame takes nothing away from the frame the others show
        with self.assertRaises(ValueError):
            page.grid(df, name='trades', rows=['nope'])
        self.assertIs(datacube._session.frames['trades'], served)

    def test_no_handle_meets_a_new_tile_under_its_old_ones_id(self):
        # (and a handle gone is refused wherever it is given: here, to a layout)
        page, grid, chart = self.built()
        later = grid.chart('line', x='region', title='later')
        page.remove(later)
        again = grid.chart('pie', x='region', title='again')
        self.assertNotEqual(again.id, later.id, 'an id the page held is not given out again')
        with self.assertRaisesRegex(ValueError, 'no longer on its page'):
            later.title = 'still the old one?'
        with self.assertRaisesRegex(ValueError, 'no longer on its page'):
            page.sheets[0].layout([grid, chart, again, later])
        page.remove(grid)
        other = page.grid(desks(), name='desks')
        self.assertNotEqual(other.id, grid.id)
        with self.assertRaisesRegex(ValueError, 'no longer on its page'):
            grid.group('desk')
        # a tile stacked with itself is no stack; one taken off, or not a tile, is refused before anything is asked
        with self.assertRaisesRegex(ValueError, 'two tiles or more'):
            page.stack(other, other)
        with self.assertRaisesRegex(ValueError, 'no longer on its page'):
            page.stack(other, later)
        with self.assertRaisesRegex(ValueError, 'a stack is of tiles'):
            page.stack(other, 'chart-1')

    def test_saved_and_loaded_again_as_it_was_what_it_does_not_model_kept(self):
        page, grid, chart = self.built()
        summary = page.sheet('Summary')
        summary.add(chart)
        path = os.path.join(tempfile.mkdtemp(dir=os.environ.get('TEST_TMPDIR')), 'q3.page.json')
        page.save(path)
        saved = json.loads(open(path, encoding='utf-8').read())
        # a field a newer writer added, kept through a load and a save
        saved['variables'] = [{'name': 'asOf'}]
        saved['cubes'][0]['cube']['query']['keepGroupedColumns'] = True
        again = ll.Page.load(saved, frames={'trades': trades()})
        self.assertEqual(again.to_dict(), saved)
        # the dict it was loaded from is its own: what is done to it is not done to the page
        saved['sheets'][1]['name'] = 'Changed after'
        self.assertEqual(again.sheets[1].name, 'Summary')
        with self.assertRaisesRegex(ValueError, 'frames trades'):
            ll.Page.load(saved, frames={})

    def test_a_page_as_datacube_writes_one_loads_and_changes_with_what_it_does_not_model_kept(self):
        # what the UI writes beside what Python does: a column's kind and aggregate, an open group, a chart's
        # selection, the options all filled, a stack, a split of a split with its sizes, a cube's field it keeps
        page, grid, chart = self.built()
        other = page.grid(desks(), name='desks')
        doc = page.to_dict()
        columns = doc['cubes'][0]['cube']['query']['columns']
        columns[0].update({'kind': 'dimension'})
        columns[2].update({'kind': 'measure', 'aggregate': 'average', 'excludedFromPivot': True})
        doc['cubes'][0]['cube']['tree'] = {'open': [['EMEA']], 'showTotals': True}
        doc['cubes'][0]['cube']['unknown'] = {'fromANewerWriter': 1}
        doc['cubes'][0]['cube']['configuration'] = {'reportTitle': 'Trades', 'showRootAggregation': True}
        chart_view = next(v for v in doc['views'] if v['kind'] == 'chart')
        chart_view['selection'] = [{'kind': 'condition', 'column': 'region', 'operator': 'equal', 'value': 'EMEA'}]
        chart_view['spec']['options'] = {'orientation': 'vertical', 'stack': 'none', 'sort': {'by': 'x', 'direction': 'asc'},
                                         'limit': 25, 'labels': False, 'legend': 'bottom'}
        doc['sheets'][0]['layout'] = {'kind': 'bands', 'fit': True, 'bands': [
            {'height': 0.62, 'node': {'split': 'row', 'parts': [
                {'node': {'stack': [grid.id, other.id]}, 'size': 0.35}, {'node': {'tile': chart.id}, 'size': 0.65}]}}]}
        loaded = ll.Page.load(doc, frames={'trades': trades(), 'desks': desks()})
        self.assertEqual(loaded.to_dict(), doc)
        # changed in Python: what it does not model is still there
        loaded.grids[0].group('desk')
        # a part of the grid-wide appearance is merged into what it has, as the screen merges a host's patch
        loaded.grids[1].configure({'appearance': {'fontSize': 14}})
        self.assertEqual(loaded.to_dict()['cubes'][1]['cube']['configuration'].get('appearance'), {'fontSize': 14})
        loaded.charts[0].options({'labels': True})
        after = loaded.to_dict()
        self.assertEqual(after['cubes'][0]['cube']['query']['columns'], columns)
        self.assertEqual(after['cubes'][0]['cube']['unknown'], {'fromANewerWriter': 1})
        after_chart = next(v for v in after['views'] if v['kind'] == 'chart')
        self.assertEqual(after_chart['selection'], chart_view['selection'])
        # a live chart written in the UI, regrouped from Python: across, the grid's first row group, in that column's
        # order (a category's: the largest first); what else the UI wrote stays
        self.assertEqual(after_chart['spec']['x'], after['cubes'][0]['cube']['query']['rows'][0])
        self.assertEqual(after_chart['spec']['options'], {**chart_view['spec']['options'], 'labels': True,
                                                          'sort': {'by': 'y', 'direction': 'desc'}})
        self.assertEqual(after['sheets'], doc['sheets'])

    def test_a_kpi_is_a_chart_of_the_grid_its_number_and_its_trend(self):
        page, grid, _ = self.built()
        # with no trend (x None: a KPI keeps the column across of the chart it was made from, as DataCube's options do)
        kpi = grid.chart('kpi', x=None, options={'target': 300, 'better': 'lower'}, title='Notional')
        spec = page.to_dict()['views'][-1]['spec']
        self.assertEqual({k: v for k, v in spec.items() if k != 'options'},
                         {'version': 2, 'mark': 'kpi', 'y': [{'column': 'notional', 'fn': 'sum'}]})
        self.assertEqual((spec['options']['target'], spec['options']['better']), (300, 'lower'))
        kpi.plot(x='region')
        self.assertEqual(page.to_dict()['views'][-1]['spec']['x'], 'region')

    def test_told_none_a_chart_has_no_split_as_it_has_no_trend(self):
        page = ll.Page('two rows')
        grid = page.grid(trades(), name='trades', rows=['region', 'desk'], measures={'notional': 'sum'})
        # DataCube's own new chart of a grid grouped twice: its second row group the split
        grid.chart('bar')
        self.assertEqual(page.to_dict()['views'][-1]['spec']['split'], 'desk')
        # told none, one series: a hand taking the split away, so the chart keeps it so (frozen)
        grid.chart('bar', split=None)
        spec = page.to_dict()['views'][-1]['spec']
        self.assertNotIn('split', spec)
        self.assertTrue(spec['frozen'])
        # and told to follow the grid as well, refused: the grid would split it again
        with self.assertRaisesRegex(ValueError, "follows its grid takes 'desk' as its split, not none"):
            grid.chart('bar', split=None, frozen=False)

    def test_a_document_is_read_as_datacube_reads_it_and_refused_in_its_words(self):
        # DataCube's own reader (page-document.ts readPage, headless): Python keeps no copy of its rules
        page, _, _ = self.built()
        doc = page.to_dict()
        unplaced = copy.deepcopy(doc)
        unplaced['sheets'][0]['layout']['bands'] = []
        with self.assertRaisesRegex(ValueError, 'no sheet has a place for grid-1'):
            ll.Page.load(unplaced, frames={'trades': trades()})
        with self.assertRaisesRegex(ValueError, r'written by a newer version \(99 > 5\)'):
            ll.Page.load({**doc, 'version': 99}, frames={'trades': trades()})
        twice = copy.deepcopy(doc)
        twice['sheets'].append({'id': 'sheet-1', 'layout': {'kind': 'bands', 'fit': True, 'bands': []}})
        with self.assertRaisesRegex(ValueError, 'two sheets are sheet-1'):
            ll.Page.load(twice, frames={'trades': trades()})
        cubes = copy.deepcopy(doc)
        cubes['cubes'].append(copy.deepcopy(cubes['cubes'][0]))
        with self.assertRaisesRegex(ValueError, 'two cubes share an id'):
            ll.Page.load(cubes, frames={'trades': trades()})
        listed = copy.deepcopy(doc)
        listed['views'][0]['cube'] = ['grid-1']
        with self.assertRaisesRegex(ValueError, 'shows no cube of this page'):
            ll.Page.load(listed, frames={'trades': trades()})
        # an older page is not read (the user, 2026-10-17), in DataCube's words
        with self.assertRaisesRegex(ValueError, r'saved by an older version \(3 < 6\): it is no longer read'):
            ll.Page.load({**doc, 'version': 3}, frames={'trades': trades()})
        # and what Python adds: a page in Python is over frames
        elsewhere = copy.deepcopy(doc)
        elsewhere['cubes'][0]['cube']['source'] = {'_type': 'remoteFile', 'name': 'trades.parquet',
                                                   'url': 'https://example.com/trades.parquet',
                                                   'columns': doc['cubes'][0]['cube']['source']['columns']}
        with self.assertRaisesRegex(ValueError, 'reads trades.parquet, which is no frame'):
            ll.Page.load(elsewhere, frames={'trades': trades()})


class Live(Paged):
    def get(self, path):
        web = datacube._session.web()
        request = urllib.request.Request(web.url + path, headers={'Authorization': web.authorization})
        try:
            with urllib.request.urlopen(request, timeout=30) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode('utf-8')

    def said(self, page, document, version=None):
        """The open page saying what it is now, as DataCube's app posts it (src/app/datacube-app.ts): the version it
        opened, its document."""
        version = datacube._session.engine().page_versions(page._key)['version'] if version is None else version
        return datacube._session.engine().answer('POST', '/page.json', f'page={page._key}&version={version}',
                                                 json.dumps(document)).status

    def test_shown_in_a_tab_served_and_each_change_served_again(self):
        page, grid, _ = self.built()
        tab = ll.show(page, browser=False)
        self.assertIn(f'/engine.html?page={page._key}#token=', tab.url)
        status, said = self.get(f'/page.json?page={page._key}')
        self.assertEqual((status, said['version'], said['page']), (200, 1, page.to_dict()))
        # a change in Python: served again, its version moved
        page.sheet('More')
        self.assertEqual(self.get(f'/version.json?page={page._key}')[1]['version'], 2)
        page.name = 'Q3, again'
        self.assertEqual(self.get(f'/page.json?page={page._key}')[1]['page']['name'], 'Q3, again')
        # a frame updated: the frame's version moves once, the page's not
        before = self.get(f'/version.json?page={page._key}')[1]
        grid.update(trades().iloc[:2])
        after = self.get(f'/version.json?page={page._key}')[1]
        self.assertEqual(after['version'], before['version'])
        self.assertEqual(after['frames']['trades'], before['frames']['trades'] + 1)
        # a page shown keeps a grid: DataCube shows no empty page
        with self.assertRaisesRegex(ValueError, 'a grid at least'):
            page.remove(grid)
        tab.close()
        self.assertEqual(self.get(f'/page.json?page={page._key}')[0], 404)

    def test_a_change_in_python_is_served_as_the_commands_it_was_which_a_page_open_on_it_applies(self):
        # (docs/DATACUBE_LIVE_COMMANDS_2026_10_18.md) a page open at version v asks what moved it since: the page's and
        # the grids' commands, in order, as DataCube's screen builds them -- a change by no command opens it again
        page, grid, _ = self.built()
        tab = ll.show(page, browser=False)
        since = lambda v: self.get(f'/page.json?page={page._key}&since={v}')[1]
        self.assertEqual(since(1), {'version': 1, 'commands': []})
        page.sheet('More')
        grid.group('desk')
        moved = since(1)
        self.assertEqual(moved['version'], 3)
        self.assertEqual(moved['commands'][0], {'page': {'kind': 'addSheet', 'id': page.sheets[1].id, 'name': 'More'}})
        self.assertEqual(moved['commands'][1], {'grid': grid.id, 'command': {'kind': 'group', 'rows': ['desk']}})
        # a grid added: its cube document in its addGrid, and the commands that set it after, as one serve
        before = since(3)['version']
        other = page.grid(trades(), name='orders', rows=['desk'])
        moved = since(before)
        self.assertEqual(moved['version'], before + 1, 'one serve')
        added = moved['commands'][0]['page']
        self.assertEqual((added['kind'], added['id'], added['document']['source']['name']), ('addGrid', other.id, 'orders'))
        self.assertEqual(moved['commands'][1:], [{'grid': other.id, 'command': {'kind': 'group', 'rows': ['desk']}}])
        # a refused change serves nothing
        with self.assertRaises(ValueError):
            page.name = '   '
        self.assertEqual(since(before + 1), {'version': before + 1, 'commands': []})
        # a frame whose columns changed reopens its cubes: no command, the page opened again
        grid.update(trades().drop(columns=['pnl']))
        self.assertIn('page', since(before + 1))
        tab.close()

    def test_what_python_serves_whole_rather_than_as_commands(self):
        # (the phase 3 audit's 5, 8 and test gaps) a refused grid serves nothing, and none of it later; what one open view
        # said, read back, is served whole next; a grid's edit over a Live frame that changed in place, whole
        page, grid, _ = self.built()
        tab = ll.show(page, browser=False)
        since = lambda v: self.get(f'/page.json?page={page._key}&since={v}')[1]
        version = lambda: self.get(f'/version.json?page={page._key}')[1]['version']
        with self.assertRaises(ValueError):
            page.grid(trades(), name='refused', rows=['nope'])
        self.assertEqual(since(1), {'version': 1, 'commands': []}, 'the refused grid served nothing')
        page.sheet('Next')
        self.assertEqual([c['page']['kind'] for c in since(1)['commands']], ['addSheet'], 'and none of it later')
        # one open view says the page is as it renamed it; Python reads it, and its next change is served whole
        doc = page.to_dict()
        doc['sheets'][0]['name'] = 'Renamed in a view'
        self.assertEqual(self.said(page, doc), 204)
        page.read()
        at = version()
        page.sheet('After the read')
        self.assertIn('page', since(at))
        # a grid over a Live frame (a function) whose rows gained a column in place: its edit opens the cube again over
        # the frame as it is, which is no command
        holder = {'df': trades()}
        live = page.grid(lambda: holder['df'], name='livef')
        at = version()
        holder['df'] = trades().assign(extra=1)
        live.group('desk')
        self.assertIn('page', since(at))
        tab.close()

    def test_an_empty_page_is_not_shown(self):
        # DataCube opens no page without a grid: refused in its words, then Python's call that makes one, and never
        # written either
        with self.assertRaisesRegex(ValueError, r'^cannot read saved page: it has no cube -- in Python, page\.grid\(frame\) '
                                                r'adds one$'):
            ll.Page('Empty').to_json()
        with self.assertRaisesRegex(ValueError, 'it has no cube'):
            ll.show(ll.Page('Empty'), browser=False)
        self.assertEqual(datacube._session.pages, [])

    def test_read_back_into_the_same_page_as_the_open_page_says_it_is(self):
        page, grid, chart = self.built()
        self.assertIs(page.read(), page, 'not open: itself')
        ll.show(page, browser=False)
        self.assertIs(page.read(), page, 'open, but it said nothing yet')
        # the open page says what it is now: a sheet renamed, the grid regrouped, the chart closed, in DataCube
        open_now = page.to_dict()
        open_now['sheets'][0]['name'] = 'Renamed in DataCube'
        open_now['cubes'][0]['cube']['query']['rows'] = ['desk']
        open_now['views'] = [v for v in open_now['views'] if v['id'] != chart.id]
        open_now['sheets'][0]['layout']['bands'][0]['node'] = {'tile': grid.id}
        self.assertEqual(self.said(page, open_now), 204)
        version = datacube._session.engine().page_versions(page._key)['version']
        self.assertIs(page.read(), page)
        self.assertEqual(page.sheets[0].name, 'Renamed in DataCube')
        # the same page, its handles still good: the grid is the grid, regrouped; the chart closed there is gone here
        self.assertIs(page.grids[0], grid)
        self.assertEqual(page.to_dict()['cubes'][0]['cube']['query']['rows'], ['desk'])
        self.assertEqual(page.charts, [])
        with self.assertRaisesRegex(ValueError, 'no longer on its page'):
            chart.options({'labels': True})
        # read quietly (the open page has it already); a change after serves it with DataCube's changes kept
        self.assertEqual(datacube._session.engine().page_versions(page._key)['version'], version)
        grid.measure('pnl', 'sum')
        served = self.get(f'/page.json?page={page._key}')[1]
        self.assertEqual(served['page']['sheets'][0]['name'], 'Renamed in DataCube')
        self.assertEqual(served['page']['cubes'][0]['cube']['query']['rows'], ['desk'])

    def test_a_tile_closed_in_datacube_and_its_id_given_again_there_is_not_its_old_handles(self):
        page, grid, chart = self.built()
        ll.show(page, browser=False)
        # DataCube closed the chart, and later made another under the same id (a page opened afresh counts from the
        # ids it has)
        open_now = page.to_dict()
        open_now['views'] = [v for v in open_now['views'] if v['id'] != chart.id]
        open_now['sheets'][0]['layout']['bands'][0]['node'] = {'tile': grid.id}
        self.assertEqual(self.said(page, open_now), 204)
        page.read()
        again = copy.deepcopy(open_now)
        again['views'].append({**page.to_dict()['views'][0], 'id': chart.id, 'kind': 'chart', 'title': 'new there',
                               'spec': {'version': 2, 'mark': 'pie', 'y': [{'column': 'notional', 'fn': 'sum'}], 'options': {}}})
        again['sheets'][0]['layout']['bands'].append({'height': 0.5, 'node': {'tile': chart.id}})
        self.assertEqual(self.said(page, again), 204)
        page.read()
        with self.assertRaisesRegex(ValueError, 'no longer on its page'):
            chart.title = 'the old chart'
        self.assertEqual(page.charts[0].title, 'new there')

    def test_a_page_opening_on_it_opens_it_as_it_is_open_now(self):
        page, _, _ = self.built()
        ll.show(page, browser=False)
        open_now = page.to_dict()
        open_now['sheets'][0]['name'] = 'Renamed in DataCube'
        self.assertEqual(self.said(page, open_now), 204)
        # another tab, or the first reloaded: the page as the open one said it is, read into Python or not
        self.assertEqual(self.get(f'/page.json?page={page._key}')[1]['page']['sheets'][0]['name'], 'Renamed in DataCube')
        page.read()
        ll.show(page, browser=False)
        self.assertEqual(self.get(f'/page.json?page={page._key}')[1], {'version': 1, 'page': page.to_dict()})

    def test_shown_again_and_closed_and_shown_again(self):
        page, grid, _ = self.built()
        first = ll.show(page, browser=False)
        # shown again in another tab: the page as it is, the tab open on it not opened again
        ll.show(page, browser=False)
        self.assertEqual(self.get(f'/version.json?page={page._key}')[1]['version'], 1)
        # closed everywhere and shown again: its version goes on, so the first showing's tab says nothing taken
        for view in list(datacube._session.pages):
            view.close()
        del first
        ll.show(page, browser=False)
        self.assertEqual(self.get(f'/version.json?page={page._key}')[1]['version'], 2)
        self.assertEqual(self.said(page, page.to_dict(), version=1), 409)

    def test_a_frozen_chart_left_without_its_grid_follows_its_frame(self):
        page, grid, chart = self.built()
        chart.plot(frozen=True)
        page.grid(desks(), name='desks')
        page.remove(grid)
        ll.show(page, browser=False)
        before = self.get(f'/version.json?page={page._key}')[1]['frames']['trades']
        datacube._session.register(trades().iloc[:1], 'trades', 'live')
        self.assertEqual(self.get(f'/version.json?page={page._key}')[1]['frames']['trades'], before + 1)

    def test_what_the_open_page_says_of_a_page_since_replaced_is_not_read(self):
        page, _, _ = self.built()
        ll.show(page, browser=False)
        stale = page.to_dict()
        stale['sheets'][0]['name'] = 'Of the page before'
        page.sheet('More')
        self.assertEqual(self.said(page, stale, version=1), 409)
        self.assertEqual([s.name for s in page.read().sheets], [None, 'More'])


class Joined(Paged):
    """A join's grid on a page Python serves (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §17, §18.3): made by page.join as
    the tab's Join… makes one, its rows planned by Python over one model of the two frames it reads, saved and loaded
    over those frames, reopened when one of them is served again, and the page served with both frames' versions."""

    HOW = {'form': 'join', 'kind': 'left', 'on': {'rows': [{'left': 'desk', 'op': '==', 'right': 'desk'}]},
           'prefix': 'd_', 'keep': None}
    # the engine's answers as a tab reads them (Live's), without running Live's own tests again
    get = Live.get
    said = Live.said

    def joined(self):
        page = ll.Page('Joins')
        mine = page.grid(trades(), name='trades')
        theirs = page.grid(desks(), name='desks')
        return page, page.join(mine, theirs, self.HOW)

    def test_a_join_of_two_frames_grids_its_rows_planned_by_python_over_both(self):
        page, joined = self.joined()
        self.assertEqual(joined.frames, ['trades', 'desks'])
        with self.assertRaisesRegex(ValueError, 'reads the frames trades, desks'):
            _ = joined.frame
        rows = joined.to_pandas()
        self.assertEqual(sorted(zip(rows['region'], rows['desk'], rows['head'])),
                         [('AMER', 'Rates', 'Ben'), ('EMEA', 'FX', 'Ana'), ('EMEA', 'Rates', 'Ben')])
        self.assertEqual(page._frames(), {'trades', 'desks'})
        # edited as any grid is, run by DataCube over the join's base
        joined.group('head')
        self.assertEqual(sorted(r[0] for r in joined.view().rows if r[0] is not None), ['Ana', 'Ben'])
        # a refusal in the compiler's words, nothing added
        with self.assertRaisesRegex(Exception, 'nope'):
            page.join(page.grids[0], page.grids[1], {**self.HOW, 'on': {'rows': [{'left': 'nope', 'op': '==', 'right': 'desk'}]}})
        self.assertEqual(len(page.grids), 3)

    def test_saved_and_loaded_over_its_frames_and_reopened_when_one_is_served_again(self):
        page, joined = self.joined()
        saved = page.to_dict()
        cube = next(c['cube'] for c in saved['cubes'] if c['cube']['source']['_type'] == 'join')
        self.assertEqual([cube['source'][s]['source']['name'] for s in ('left', 'right')], ['trades', 'desks'])
        with self.assertRaisesRegex(ValueError, 'the page reads the frames desks'):
            ll.Page.load(saved, frames={'trades': trades()})
        again = ll.Page.load(saved, frames={'trades': trades(), 'desks': desks()})
        grid = next(g for g in again.grids if g.frames == ['trades', 'desks'])
        self.assertEqual(len(grid.to_pandas()), 3)
        # its frame served again with a column more: the join opened over it as the tab opens a saved join
        with warnings.catch_warnings(record=True) as said:
            warnings.simplefilter('always')
            next(g for g in again.grids if g.frames == ['desks']).update(desks().assign(floor=[3, 4]))
        self.assertTrue(any('floor' in str(w.message) for w in said), [str(w.message) for w in said])
        # every column of theirs kept (keep None): the new one among them, under its own name (mine has none of it)
        self.assertIn('floor', list(grid.to_pandas().columns))

    def test_shown_served_with_both_frames_versions(self):
        page, joined = self.joined()
        tab = ll.show(page, browser=False)
        try:
            status, versions = self.get(f'/version.json?page={page._key}')
            self.assertEqual((status, sorted(versions['frames'])), (200, ['desks', 'trades']))
            # the page in DataCube's hands, read back: a join of frames is a page Python holds
            self.assertEqual(self.said(page, page.to_dict()), 204)
            self.assertIs(page.read(), page)
        finally:
            tab.close()


class CorpusPage(unittest.TestCase):
    """THE SAVED-PAGE CORPUS (docs/DATACUBE_COMMAND_API_HOMEWORK_2026_10_17.md §11, part 3): the corpus's frame page,
    written by DataCube's own writers (datacube/test/fixtures/pages/make.ts), loaded in Python over its frame with
    nothing to say, and written again as the same document -- the page DataCube saved is the page Python keeps."""

    def setUp(self):
        self._site = os.environ.get('LEGEND_LITE_SITE')
        os.environ['LEGEND_LITE_SITE'] = tempfile.mkdtemp(dir=os.environ.get('TEST_TMPDIR'))
        datacube._session = datacube._Session()

    def tearDown(self):
        for view in list(datacube._session.pages):
            view.close()
        # its DataCube engine (headless) closed with it: a session dropped open leaves that engine's folder to the garbage
        # collector, which warns about it under whichever later test it reaches (CorpusPage counts every warning)
        if datacube._session._headless is not None:
            datacube._session._headless.close()
        if self._site is None:
            os.environ.pop('LEGEND_LITE_SITE', None)
        else:
            os.environ['LEGEND_LITE_SITE'] = self._site

    @staticmethod
    def frame():
        """The frame the page was made over, typed as its cubes record it: text, a date, a float and an integer."""
        return pd.DataFrame({
            'region': ['EMEA', 'EMEA', 'AMER', 'APAC'],
            'desk': ['Rates', 'FX', 'Rates', 'FX'],
            'traded': [datetime.date(2024, 1, 2), datetime.date(2024, 2, 3), datetime.date(2024, 3, 4), datetime.date(2024, 4, 5)],
            'notional': [100.5, 20.25, 300.75, 4.5],
            'qty': [1, 2, 3, 4],
        })

    def test_the_frame_page_loads_with_nothing_to_say_and_is_written_as_it_was(self):
        saved = json.loads(Path(os.environ['LEGEND_LITE_CORPUS_PAGE']).read_text('utf-8'))
        with warnings.catch_warnings(record=True) as said:
            warnings.simplefilter('always')
            page = ll.Page.load(saved, frames={'trades': self.frame()})
        self.assertEqual([str(w.message) for w in said], [], 'DataCube opens each cube with nothing to say')
        self.assertEqual(page.to_dict(), saved)
        self.assertEqual([g.title for g in page.grids], ['Positions', 'By desk'])
        self.assertEqual(len(page.charts), 2)

    def test_a_cube_reopened_over_a_changed_frame_keeps_what_only_its_query_carries(self):
        # the frame has a column more, so each cube is rebuilt over it (headless gridCube) -- and a column's Aggregation
        # the saved cube holds only in its query (a document written before its configuration said it) is kept, as the
        # screen keeps it (cube-state.ts openedState), not folded away with a configuration that does not name it
        saved = json.loads(Path(os.environ['LEGEND_LITE_CORPUS_PAGE']).read_text('utf-8'))
        positions = saved['cubes'][0]['cube']
        del positions['configuration']['columns']['qty']['aggregateFn']
        frame = self.frame().assign(book=['B1', 'B2', 'B1', 'B2'])
        with warnings.catch_warnings(record=True) as said:
            warnings.simplefilter('always')
            page = ll.Page.load(saved, frames={'trades': frame})
        self.assertTrue(said and all('new column' in str(w.message) and 'book' in str(w.message) for w in said),
                        [str(w.message) for w in said])
        written = page.to_dict()['cubes'][0]['cube']
        qty = next(c for c in written['query']['columns'] if c['name'] == 'qty')
        self.assertEqual(qty.get('aggregate'), 'average', 'the Aggregation the query carried is kept')
        self.assertEqual(written['configuration']['columns']['qty'].get('aggregateFn'), 'average',
                         'and read back into the configuration, as the screen reads it')
        self.assertTrue(written['configuration']['columns']['book']['hidden'], 'the new column is hidden')
