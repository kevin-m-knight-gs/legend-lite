"""A DataCube page built in Python: grids over frames, their charts, sheets, layouts and stacks -- everything the page does
in DataCube, said in code (docs/DATACUBE_PYTHON_PAGES_DESIGN_2026_10_09.md).

    import legend_lite as ll
    page = ll.Page('Q3 review')
    trades = page.grid(trades_df, name='trades', rows=['region', 'desk'], columns=['year'], dimensions=['year'],
                       measures={'notional': 'sum'}, filter=[('region', 'notEqual', 'APAC')])
    trades.calculate('margin', '$x.pnl->toOne() / $x.notional->toOne()')   # Pure, checked by the compiler here
    by_desk = trades.chart('bar', x='desk', y=[('notional', 'sum')], split='region')
    summary = page.sheet('Summary')
    summary.add(by_desk)
    ll.show(page)                       # under the cell in a notebook, else a browser tab; live from then on
    trades.group('desk')                # the page as it is open now: a change made in DataCube is on it already
    page.save()                         # into this user's saved pages, as DataCube's Save keeps it (Open… lists it)
    page.save('q3.page.json')           # DataCube's own page document (Export > Page File writes the same)
    again = ll.Page.load('q3.page.json', frames={'trades': trades_df})

A page IS its document: DataCube's page document (its own version; datacube/src/page-document.ts), the one copy of it. Its
grids, charts and sheets are handles onto it -- each its id -- whose methods edit it in place, each edit checked at the
line that makes it (a column the frame has, a calculated column the compiler types, a word DataCube's vocabulary has).
So whatever DataCube writes in a page is kept as it is, read back from where it is open by taking the open page's
document (``read``), and the document's own words reach what no method names: ``configure`` (a grid's configuration),
``options`` (a chart's), ``layout_from`` (a sheet's bands). Each grid's cube is over its frame by the frame's name
(``{"_type": "frame"}``), served by this process's engine, as ``show(df)`` serves one. A tile is placed and taken off as
DataCube's layout places it (datacube/src/layout/bands.ts): a grid in a band of its own at the bottom, a chart beside its
grid (as many to a band as DataCube's layout takes), a tile taken off leaving its neighbours closing over its place.
"""

from __future__ import annotations

import contextlib
import copy
import datetime
import functools
import itertools
import json
import math
import numbers
import uuid
import warnings
import weakref
from collections.abc import Mapping, Sequence
from decimal import Decimal
from pathlib import Path
from typing import Any

from ._extra import require_datacube

# DataCube's: the datacube extra's packages, or one sentence saying to install it (_extra.py)
require_datacube()

from . import _json
from ._types import is_boolean, is_decimal, is_float, is_integer, is_number
from .keeper import Stale

_KEEP: Any = object()

# each page's key on its session's keeper and engine (page.json?page=): never given twice in a process, so a page
# collected never shares one with a page made after it
_KEYS = itertools.count(1)


def _operation(run: Any) -> Any:
    """A call on a page, or on one of its tiles or sheets: the page as its keeper has it when the call starts (its
    version pinned for the call, a change made in DataCube meanwhile read only by the next call), and -- the call over --
    what it changed written (Page._changed), or, refused, nothing changed (docs/DATACUBE_PAGE_KEEPER_2026_10_20.md)."""
    @functools.wraps(run)
    def call(self: Any, *args: Any, **kwargs: Any) -> Any:
        with (self if isinstance(self, Page) else self._page)._call():
            return run(self, *args, **kwargs)
    return call


def _operations(cls: Any) -> Any:
    """Each public method and property of ``cls`` a page's call (``_operation``)."""
    for name, value in list(vars(cls).items()):
        if name.startswith('_') or isinstance(value, (classmethod, staticmethod)):
            continue
        if isinstance(value, property):
            setattr(cls, name, property(value.fget and _operation(value.fget), value.fset and _operation(value.fset),
                                        value.fdel, value.__doc__))
        elif callable(value):
            setattr(cls, name, _operation(value))
    return cls


def _with_cube(doc: dict[str, Any], cube: str, document: dict[str, Any]) -> dict[str, Any]:
    """``doc`` with its cube ``cube`` (its id) replaced by ``document``: a new page document, ``doc`` as it was."""
    return {**doc, 'cubes': [{**c, 'cube': document} if c['id'] == cube else c for c in doc['cubes']]}


def _given_name(session: Any, part: str, name: Any, what: str) -> str | None:
    """A name a page, a tile or a sheet is given, by DataCube's one rule (page-names.ts givenName, asked headless:
    nameGiven, as the screen's renames ask it): its text trimmed, a page's or a tile's blank refused in DataCube's words,
    a sheet's blank none of its own (None). Python only refuses what is not text."""
    if not isinstance(name, str):
        raise ValueError(f'{what} is a name, not {name!r}')
    said = json.loads(session.headless().call('nameGiven', part, name))
    if 'problems' in said:
        raise ValueError(f'{what}: ' + '; '.join(said['problems']))
    return said['name']


def _plain(value: Any, exact: bool = False) -> Any:
    """Document values as DataCube reads them (cube-document.ts): plain numbers, but in a Pure lambda (a calculated
    column's), whose numbers stay exact (``Decimal``, as the compiler writes them)."""
    if isinstance(value, dict):
        inside = exact or value.get('_type') == 'lambda'
        return {k: _plain(v, inside) for k, v in value.items()}
    if isinstance(value, list):
        return [_plain(v, exact) for v in value]
    if isinstance(value, Decimal) and not exact:
        return float(value)
    return value


def _copied(value: Mapping[str, Any]) -> dict[str, Any]:
    """A copy of document values, as JSON carries them (a value JSON cannot carry refused here)."""
    return _carried(dict(value))


def _carried(value: Any) -> Any:
    """Any value as JSON carries it, whatever its shape: what a chart is told goes to DataCube as given, for DataCube's
    reader to judge in its own words (_made)."""
    return _plain(_json.loads(_json.dumps(value)))


# -- filters ----------------------------------------------------------------------------------------------------------

def _value(value: Any) -> Any:
    """A filter's value as DataCube keeps one (snapshot.ts, FilterValue): text, a number, a boolean, a date relative to
    when the query runs (``{'relative': ...}``, in DataCube's words: ``'today'``, ``'now'``; gridFilter judges the word)
    or a variant column's JSON (``{'json': text}``). A date and a Decimal go as their exact text, which DataCube writes
    as the column's type writes it (``'2024-01-31'``, ``'12.30'``)."""
    if isinstance(value, (bool, str)):
        return value
    if isinstance(value, numbers.Integral):
        return int(value)
    if isinstance(value, Decimal) and value.is_finite():
        return str(value)
    if isinstance(value, numbers.Real) and not isinstance(value, Decimal) and math.isfinite(value):
        return float(value)
    if isinstance(value, datetime.date) and not isinstance(value, datetime.datetime):
        return value.isoformat()
    if isinstance(value, Mapping) and ((set(value) == {'relative'} and isinstance(value['relative'], str))
                                       or (set(value) == {'json'} and isinstance(value['json'], str))):
        # a relative date's word is DataCube's to judge (gridFilter: snapshot.ts isRelativeDate)
        return dict(value)
    raise ValueError(f'a filter takes text, a number, a boolean, a date, {{"relative": word}} or {{"json": text}}, '
                     f'not {value!r} (a timestamp as its text: "2024-01-31 10:30:00")')


def _given_filter(node: Any) -> dict[str, Any]:
    """A filter as Python takes one -- a condition ``(column, operator[, operand])``, a group ``('and' | 'or',
    [nodes])`` or ``('not', node)``, a list being all of them -- in the shape of the filter editor's tree, each
    operand's values as DataCube keeps them (``_value``). What an operator takes, and whether a column takes it,
    DataCube's code says (gridFilter)."""
    if isinstance(node, list):
        node = ('and', node)
    if not isinstance(node, tuple) or len(node) < 2:
        raise ValueError(f'a filter is a condition (column, operator, value), a group ("and", [...]) or ("not", ...), '
                         f'not {node!r}')
    head = node[0]
    if head in ('and', 'or') and len(node) == 2 and isinstance(node[1], (list, tuple)):
        return {'kind': head, 'children': [_given_filter(child) for child in node[1]]}
    if head == 'not' and len(node) == 2:
        return {'kind': 'not', 'child': _given_filter(node[1])}
    if len(node) > 3:
        raise ValueError(f'a condition is (column, operator) or (column, operator, value), not {node!r}')
    condition: dict[str, Any] = {'kind': 'condition', 'column': node[0], 'operator': node[1]}
    if len(node) == 3:
        given = node[2]
        if isinstance(given, (set, frozenset)):
            raise ValueError('a filter takes a list of values, in their order (a list or a tuple), not a set')
        condition['operand'] = [_value(v) for v in given] if isinstance(given, (list, tuple)) else _value(given)
    return condition


# -- the handles ------------------------------------------------------------------------------------------------------

@_operations
class Tile:
    """A tile of a page (a grid or a chart): its view in the page's document, by its id."""

    _kind = ''

    def __init__(self, page: Page, id: str) -> None:
        self._page = page
        self._id = id
        # its tile gone (removed, or closed in DataCube): it stays refused, whatever later has its id
        self._dead = False

    @property
    def page(self) -> Page:
        return self._page

    @property
    def id(self) -> str:
        return self._id

    @property
    def _view(self) -> dict[str, Any]:
        """Its view in the document now, or refused: it is no longer on the page (closed there, or removed)."""
        view = None if self._dead else \
            next((v for v in self._page._doc['views'] if v['id'] == self._id and v['kind'] == self._kind), None)
        if view is None:
            raise ValueError(f'{self!r} is no longer on its page')
        return view

    @property
    def title(self) -> str | None:
        return self._view.get('title')

    @title.setter
    def title(self, title: str | None) -> None:
        _ = self._view
        if title is not None and not isinstance(title, str):
            raise ValueError(f'a title is a name, not {title!r}')
        # by the command the screen's rename builds (a chart has a title; a grid's None takes its own off)
        self._page._apply_page([{'kind': 'renameTile', 'tile': self._id, 'title': title}], 'a title')

    @property
    def sheet(self) -> Sheet:
        """The sheet it is on."""
        _ = self._view
        return next(self._page._sheet(s['id']) for s in self._page._doc['sheets']
                    if self._id in self._page._tiles(s['layout']))

    def move_to(self, sheet: Sheet) -> None:
        """Onto another sheet of its page, in a band of its own at the bottom (its charts stay where they are)."""
        sheet.add(self)


@_operations
class Grid(Tile):
    """A grid over a frame: its groupings (``group``), pivot (``pivot``), each column's Column Properties (``column``,
    ``measure``), filter, sorts and calculated columns, as the grid's panels set them; its configuration
    (``configure``); its charts (``chart``)."""

    _kind = 'grid'

    @property
    def _cube(self) -> dict[str, Any]:
        return self._page._cube(self._view['cube'])

    @property
    def _query(self) -> dict[str, Any]:
        return self._cube['query']

    @property
    def frame(self) -> str:
        """The name of the frame it is over -- a grid reading one; a join's grid reads several (``frames``)."""
        frames = self.frames
        if len(frames) != 1:
            raise ValueError(f'this grid reads the frames {", ".join(frames)}: see frames')
        return frames[0]

    @property
    def frames(self) -> list[str]:
        """The frames it reads, as DataCube says what a grid reads (cube-document.ts leavesOf, headless cubeLeaves;
        docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §18.3): a frame's grid its one, a join's each of its leaves'."""
        return _cube_frames(self._page._session, self._cube)

    def _apply(self, commands: list[dict[str, Any]]) -> Grid:
        """The grid edited by commands, each the one DataCube's screen builds for the same edit (grid-commands.ts:
        docs/DATACUBE_COMMANDS_STAGE1_2026_10_18.md) -- ``{'kind': 'group', 'rows': [...]}``, ``{'kind': 'addSort',
        ...}``, ``{'kind': 'configure', 'patch': {...}}`` and the rest -- applied by DataCube's own function in order
        (headless gridApply), the grid then compiled as Apply compiles it: refused in DataCube's words, nothing set; else
        the cube document DataCube writes, its open groups kept as the screen keeps them."""
        engine, frames = self._headless()
        with frames.serving():
            said = json.loads(engine.call('gridApply', self._spec(engine, frames), _json.dumps(commands)))
        if 'problems' in said:
            raise ValueError('; '.join(said['problems']))
        return self._kept(said, 4)

    def _kept(self, said: dict[str, Any], stacklevel: int) -> Grid:
        """The cube document DataCube wrote for an edit, kept whole -- never a part of it Python sets itself -- and what
        opening it over its frame changed, said in DataCube's words, as a load says it."""
        source = _copied(self._cube.get('source', {}))
        cube = _plain(_json.loads(said['cube']))
        self._page._doc = _with_cube(self._page._doc, self._view['cube'], cube)
        # the commands DataCube applied, as the screen would have built them: the page's record of its edits, and what a
        # page open on it applies to this grid -- unless the cube was opened again over a frame that changed (its source
        # is another, or opening it left something out): a change by no command, which it follows by opening the page
        # again (the phase 3 audit's 8)
        applied = [_plain(c) for c in said['commands']]
        self._page._commands.extend(applied)
        if applied and not said['notes'] and cube.get('source') == source:
            self._page._note([{'grid': self._id, 'command': c} for c in applied])
        else:
            self._page._unnoted()
        for note in said['notes']:
            warnings.warn(f"{cube['source']['name']}: {note}", stacklevel=stacklevel)
        self._page._changed()
        return self

    # -- headless: DataCube's own code, in Python (docs/DATACUBE_HEADLESS_PYTHON_DESIGN_2026_10_11.md §4) ------------

    def _headless(self) -> tuple[Any, Any]:
        """The session's headless engine, got first (the session's lock, taken and let go), and the frames: a caller then
        holds the frames still (the frames' lock) -- the order a frame's registering takes the two, so neither waits on
        the other."""
        session = self._page._session
        return session.headless(), session.frames

    def _spec(self, engine: Any, frames: Any, live: bool = False) -> dict[str, Any]:
        """This grid's cube and its base, as the bundle's grid functions take them (the frames held still), asked of
        ``engine`` -- got before the frames were held (``_headless``): getting it takes the session's lock, which a
        frame's registering takes before the frames', so asking for it here, the frames held, could wait on it forever.
        A snapped grid's base is its copy (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §18.8), unless ``live``: what it is
        a copy of (what a snap pulls, what a class is made of)."""
        return _cube_spec(self._cube, frames, engine, None if live else self._page._copy_base(self._view['cube']))

    def view(self) -> GridView:
        """What the grid shows, as DataCube computes it: its columns and rows, their groups and totals, its pivot, and
        what its row cap cut -- by DataCube's own code, run headless in V8 (the `datacube` extra), as the grid on screen
        runs it: its configuration folded into its query, its columns laid out as the screen lays them out."""
        import json

        from ._headless import cells
        engine, frames = self._headless()
        with frames.serving():
            # DataCube's cells are exact as it gives them (an Integer or a Float a number, a Decimal its text): read as
            # JSON reads them, an integer past 2^53 and a non-finite float from their tags
            return GridView(cells(json.loads(engine.call('gridView', self._spec(engine, frames)))))

    @property
    def relation(self) -> str:
        """Its rows at their finest level, as Pure: filtered and extended by its calculated columns, grouped by its
        innermost group when it is grouped (a flat grid's rows as they are) -- the relation DataCube's Create a Class
        makes a class of: over its live base, a snapped grid's too (its copy lasts only as long as this session)."""
        engine, frames = self._headless()
        with frames.serving():
            return engine.call('gridRelation', self._spec(engine, frames, live=True))

    def to_pandas(self) -> Any:
        """Every row of ``relation``, as a pandas DataFrame: DataCube says the query, Python plans it over the grid's base
        model (a frame's, a join's of frames: what DataCube says it is over; a snapped grid's, its copy's) and its own
        database answers it, typed by the plan, the rows coming to Python from there (never through DataCube's engine)."""
        from . import compiler
        from .frames import to_pandas
        engine, frames = self._headless()
        with frames.serving():
            spec = self._spec(engine, frames)
            held = frames.model_for(spec['model'])
            if held is None:
                raise ValueError(f'{self._cube["source"]["name"]}: its model reads a table this session does not serve')
            return to_pandas(frames.run(held.plan(compiler.parse(engine.call('gridRelation', spec)), spec['runtime'])))

    def _class(self, name: str | None = None, columns: Mapping[str, Any] | None = None,
               documentation: str | None = None) -> tuple[dict[str, Any], dict[str, Any]]:
        """Its rows as a class's, as the window asks for them: DataCube's own request (headless ``gridClass``: its rows
        at their finest level, every table they read -- a join's each (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §18.3)
        -- and each one's first rows as the window writes them into the project, planned and run on Python's database,
        as every headless grid's are, and the names offered), with the frames held still. Answers the writer's class
        action and what DataCube said; a grid whose rows cannot be a class refused in DataCube's words
        (``ValueError``)."""
        engine, frames = self._headless()
        # named as the window names it: by its source's name (a frame's, a join's title)
        source = self._cube['source']['name']
        with frames.serving():
            # of its live base, a snapped grid's too: a class maps the tables its rows are read from, never a copy, which
            # lasts only as long as this session (§18.8)
            asked = _json.loads(engine.call('gridClass', self._spec(engine, frames, live=True),
                                            {'source': source, 'page': self._page.name}))
        if 'refused' in asked:
            raise _NotATable(f'{source!r} cannot be a class: {asked["refused"]}')
        if 'problems' in asked:
            raise ValueError(f'{source!r} cannot be a class: {"; ".join(asked["problems"])}')
        cls = {'query': asked['query'], 'source': asked['source'], 'name': name or asked['names']['className'],
               **({'documentation': documentation} if documentation else {}),
               **({'columns': _column_choices(columns)} if columns else {}),
               'where': {'from': asked['database'], 'as': 'Python', 'samples': asked['samples']}}
        return cls, asked

    def export(self, kind: str = 'csv', to: str | Path | None = None) -> str | bytes | Path:
        """The grid as its Export menu exports it, by DataCube's own code (the screen's core, run headless): what it
        shows, with its formats, appearance and heatmaps -- ``'csv'`` (raw values), ``'excel'`` (a workbook, the tree
        as outlines), ``'html'``, ``'text'`` (formatted, to paste) or ``'pdf'``: the kinds its menu offers, a kind it
        does not refused in DataCube's words. Answers the file's content (text, or bytes for a workbook or a PDF), or,
        given ``to``, writes it there and answers the path."""
        import base64
        engine, frames = self._headless()
        with frames.serving():
            said = _json.loads(engine.call('gridExport', self._spec(engine, frames), kind))
        if 'problems' in said:
            raise ValueError('; '.join(said['problems']))
        content: str | bytes = said['content'] if said['text'] else base64.b64decode(said['content'])
        if to is None:
            return content
        path = Path(to)
        # the bytes as the export wrote them: CSV's own CRLF, no newline translated on any system
        path.write_bytes(content.encode('utf-8') if isinstance(content, str) else content)
        return path

    def create_class(self, name: str | None = None, *, package: str | None = None,
                     columns: Mapping[str, Any] | None = None, documentation: str | None = None,
                     project: str | None = None, sdlc: Any = None, writer: Any = None) -> str:
        """Its rows at their finest level (``relation``) made a class of the page's model, as DataCube's Create a Class…
        makes it (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md B4): with everything it needs to run (its relation function,
        mapping, the frames' store, connection and runtime -- the place ``Python`` -- and its first rows as sample
        data), into the page's project in this user's model home, made with the page's first class. ``name``: the
        class's (suggested from its source's name, as the window suggests it); ``package``: the page's (suggested from
        the page's name before its first class). ``columns``: by column, ``'property'`` (the default), ``'key'`` (kept
        for an association to join on, not shown) or ``'omit'``, or ``{'as', 'name', 'documentation'}``. ``project``:
        the name of the project made with the page's first class (the page's name unless given, as the window offers
        it). ``sdlc`` and ``writer``: another SDLC or writer than this user's (legend_lite.model). Answers the class's
        path."""
        from . import model as models
        cls, asked = self._class(name, columns, documentation)
        made = self._page._into(package or asked['names']['package'], sdlc, writer,
                                lambda s, w, at, made: models.create_class(s, w, *at, cls, made=made), project)
        path = next((c['class'] for c in made.answer.get('classes', [])), None)
        self._page._keep(self._view['cube'], asked['tables'], path)
        return path or cls['name']

    def group(self, *rows: str) -> Grid:
        """Grouped by ``rows``, outermost first (the Rows zone): dimensions, as the screen offers them
        (``column(name, kind='dimension')`` makes one); none, not grouped."""
        return self._apply([{'kind': 'group', 'rows': list(rows)}])

    def pivot(self, *columns: str) -> Grid:
        """Pivoted on ``columns`` (the Columns zone): dimensions, as for ``group``; none, not pivoted."""
        return self._apply([{'kind': 'pivot', 'columns': list(columns)}])

    def column(self, name: str, *, kind: str | None = None, aggregate: Any = _KEEP, weight: Any = _KEEP) -> Grid:
        """``name``'s Column Properties, as the panel sets them: its ``kind`` (``'dimension'``, which can be grouped and
        pivoted on, or ``'measure'``; a calculated column's is its editor's), its Aggregation (``aggregate``: one the
        panel offers for its type; a measure's only) and a weighted average's ``weight`` column -- None clears either,
        as the panel's "none" does. What is not given is left as it is."""
        change = {**({'kind': kind} if kind is not None else {}),
                  **({'aggregate': aggregate} if aggregate is not _KEEP else {}),
                  **({'weight': weight} if weight is not _KEEP else {})}
        return self._apply([{'kind': 'column', 'name': name, 'change': change}])

    def measure(self, column: str, fn: str, name: str | None = None, *, weight: str | None = None) -> Grid:
        """``column`` aggregated by ``fn`` when the grid groups (one Aggregation offers for its type, and none is assumed:
        ``column(name, kind='measure')`` makes a measure at the one DataCube gives its type; ``wavg`` takes a ``weight``
        column, and only it does): ``column``'s own Aggregation, as Column Properties sets it -- replacing the one it
        had; a measure's, as the panel refuses a dimension's (``column(name, kind='measure')`` makes one) -- unless
        ``name`` names another column: then a calculated column of that name reading ``column``, with that Aggregation,
        as the screen would add one. A grouped ``column`` takes no Aggregation of its own: give the measure a ``name``."""
        measure = {'name': column if name is None else name, 'column': column, 'fn': fn,
                   **({'weight': weight} if weight is not None else {})}
        return self._apply([{'kind': 'measures', 'measures': [measure]}])

    def filter(self, condition: Any) -> Grid:
        """Its filter: a condition ``(column, operator[, value])``, a list of them (all of them), a group
        ``('and' | 'or', [...])`` or ``('not', ...)`` -- the filter editor's own conditions, set as it sets them
        (DataCube's code, headless: gridFilter): each operator's value as it takes one (none, one, a list, another
        column), one a column of its type is offered, the grid with it compiled, in the editor's normal form (a group of
        one is that one, an empty one none: ``[]`` takes the filter off, as ``None`` does); ``None`` for none."""
        if condition is None:
            return self._apply([{'kind': 'filter', 'filter': None}])
        self._page._current(self._view['cube'], 4)
        engine, frames = self._headless()
        with frames.serving():
            said = json.loads(engine.call('gridFilter', self._spec(engine, frames), _json.dumps(_given_filter(condition))))
        if 'problems' in said:
            raise ValueError(f'{"; ".join(said["problems"])} -- in Python a condition is (column, operator) or '
                             '(column, operator, value), its value a list where the operator takes several')
        # the cube with it, set by the filter command the filter editor's Apply builds
        return self._kept(said, 3)

    def sort(self, column: str, direction: str = 'asc') -> Grid:
        """A sort, after those it has, as the header menu's Add Ascending and Add Descending add one (grid-edits.ts
        addSort: a sort it had of ``column`` taken first; one it has already changes nothing): ``column`` (one of the
        grid's columns, or a pivot's header) ``'asc'`` or ``'desc'``."""
        return self._apply([{'kind': 'addSort', 'column': column, 'direction': direction}])

    def calculate(self, name: str, expression: str, *, kind: str | None = None) -> Grid:
        """A calculated column computed per row, in Pure over the row ``$x`` (``'$x.qty * 2'``; a column that can be
        empty, as a dataframe's can, ``->toOne()`` first for arithmetic), added as the grid's own column editor adds one
        (DataCube's code, headless: gridCalculate): its name as DataCube takes one, ``kind`` ``'measure'`` or
        ``'dimension'`` as declared (not given, a measure, as the editor starts a new column -- summed when grouped if it
        is a number, its unique value if not), and the grid with it compiled as DataCube would run it -- reading any of
        the grid's columns before it, a window column's too.
        A mistake fails at this line, in DataCube's and the compiler's words."""
        if not isinstance(name, str) or not isinstance(expression, str):
            raise ValueError(f'a calculated column is a name and an expression, not {name!r} and {expression!r}')
        self._page._current(self._view['cube'], 4)
        engine, frames = self._headless()
        with frames.serving():
            said = json.loads(engine.call('gridCalculate', self._spec(engine, frames), name, f'x|{expression}', kind))
        if 'problems' in said:
            raise ValueError('; '.join(said['problems']))
        # the cube with it, as DataCube writes the editor's Apply: the column typed, the state folded
        return self._kept(said, 3)

    def configure(self, settings: Mapping[str, Any]) -> Grid:
        """Any of the grid's configuration (its Properties), in its saved document's own keys (``reportTitle``,
        ``maxRows``, ``appearance``, ``columns``: each column's formats and colours, ...), set as DataCube sets a host's
        (its ``applyConfiguration``: the appearance and each column's merged into what they have; None clears a
        setting) and folded into its query as the screen folds it, by a configure command (headless gridApply). A
        column's ``kind``, ``aggregateFn`` and ``aggregationParameters`` go by Column Properties' rules, as ``column()``
        does: a kind is set, never cleared; ``excludedFromPivot`` is True or False."""
        return self._apply([{'kind': 'configure', 'patch': _copied(settings)}])

    def chart(self, mark: str, *, x: Any = _KEEP, y: Sequence[Any] | None = None,
              split: Any = _KEEP, title: str | None = None, frozen: bool | None = None,
              options: Mapping[str, Any] | None = None, grain: str | None = None,
              bins: Mapping[str, float] | None = None, tooltip: Sequence[Any] | None = None,
              layers: Sequence[Mapping[str, Any]] | None = None, label: Any = None, size: Any = None,
              facet: Mapping[str, str] | None = None, at: Mapping[str, str] | None = None) -> Chart:
        """A chart of this grid, beside it: ``mark`` (a mark DataCube draws), its category ``x``, what it plots ``y`` --
        ``[(column, aggregate), ...]``, the measure DataCube's own new chart would plot unless given -- and a ``split``
        (one series per value), each DataCube's own new chart's unless given (None: none); ``frozen`` keeps its own
        grouping rather than following the grid's (and stays when the grid is removed, as in DataCube) -- unless told, a
        chart told its ``x``, ``y`` or ``split`` keeps them, as a hand on them in DataCube's options freezes the chart,
        and one told none of them follows the grid (told them and ``frozen=False``, one the grid would replace is
        refused); ``options``: the chart's Options, in its spec's own keys (one left out is its default).

        The spec's slots, each in its own keys: a measure of ``y`` may be a dict -- ``column``, ``fn`` (an aggregate, or
        ``'value'``: a group-level column of the grid, plotted as it is), its own ``mark``, ``axis`` and ``showAs``
        (``{'kind': ...}``); ``grain`` takes a date across to its period; ``bins`` a number across to its bins
        (``{'width': w}`` or ``{'count': n}``); ``label`` a measure written on each mark instead of its own value;
        ``tooltip`` further measures a hover lists; ``layers`` reference lines, bands, thresholds and pinned extremes
        (each a dict, its ``kind`` saying which); ``size`` a scatter's third column, each point sized by it (a bubble);
        ``facet`` small multiples of bars, lines or areas, a chart per value of ``{'column': ...}`` and/or
        ``{'row': ...}``. A ``'boxplot'`` plots one number column's five figures -- ``min``, ``percentile25``,
        ``median``, ``percentile75``, ``max``, in that order -- taking those of the measure it would plot unless ``y``
        says them. A ``'map'`` colours each country its ``x`` names (an ISO code or a name); a ``'bubblemap'`` puts a
        bubble at each place ``x`` names, where ``at={'lat': ..., 'lon': ...}`` -- two number columns -- says it is.
        Every word is DataCube's (datacube/src/chart-spec.ts): one it does not know -- a mark, an
        axis, a show-as, a grain, a layer's kind or statistic -- is refused by its reader, naming the words it takes.

        DataCube makes the chart -- its own new chart, changed as its options form changes one (a date across runs in time
        order) -- and judges it as it will draw it, by its own rules run in V8 (the datacube extra; the bundle's
        gridChart): what it would refuse to draw is refused here, in its words, and nothing is kept."""
        given = {**_given(mark, x, y, split, frozen), **({'options': _carried(options)} if options is not None else {}),
                 **_slots(grain, bins, tooltip, layers, label, size, facet, at)}
        # made as DataCube makes a new chart (what is not given is its own default), and judged as it will draw it: what
        # DataCube would refuse to draw is refused here, in its words, before the chart is kept
        spec = _made(self._page, self._view['cube'], given)
        if title is not None and not isinstance(title, str):
            raise ValueError(f'a title is a name, not {title!r}')
        # beside this grid, titled by its number unless given one, as the screen adds a chart (page-commands.ts addChart)
        applied = self._page._apply_page([{'kind': 'addChart', 'near': self._id, 'spec': spec, 'title': title,
                                           'columns': None}], 'a title')
        return self._page._tile(applied[0]['id'], Chart)

    @property
    def charts(self) -> list[Chart]:
        """The charts of this grid, on any sheet."""
        cube = self._view['cube']
        return [c for c in self._page.charts if c._view['cube'] == cube]

    def update(self, frame: Any) -> None:
        """Another frame for this grid (one reading a single frame: ``frame``): a page showing it queries it again, and
        -- its columns changed -- opens again over them, every grid reading the frame with it (a snapped one reads its
        copy still, until it goes live)."""
        _ = self._view
        self._page._update(self.frame, frame)

    # -- Snap (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §18.8): where its rows are read from, as page state ----------------

    def snap(self) -> Grid:
        """Its rows read once: its base query (its every column, as DataCube's Snap pulls it: headless gridSnapPull)
        copied by this session's engine -- as a tab's Snap copies one through it (engine.py ``snap_copy``), a
        warehouse table's pulled on the warehouse -- and the grid reading the copy from then on (the page's ``snap``
        command, written as DataCube's Snap writes it: headless gridSnapped): every call on it, and the page shown,
        read the copy, whatever its frames do meanwhile. Snapped already, it is snapped again (the copy taken anew,
        the old one let go). Refused in the engine's words (a copy past the snap limit, say), the grid as it was."""
        _ = self._view
        page, session = self._page, self._page._session
        engine, frames = self._headless()
        with frames.serving():
            spec = self._spec(engine, frames, live=True)
            pull = engine.call('gridSnapPull', spec)
        # copied outside the frames: the engine holds them itself, on its own thread
        made = session.engine().snap_copy(spec['model'], spec['runtime'], pull)
        try:
            with page._batch(tell=False):
                page._apply_page([json.loads(engine.call('gridSnapped', self._id, _json.dumps(made)))], 'a snap')
            # written: the page reads the copy from now on (a copy it read before let go then: the engine's sweep)
            page._changed()
        except BaseException:
            # the copy belongs to no page: let go
            session.engine().release_copy(made['table'])
            raise
        return self

    def live(self) -> Grid:
        """Its rows read live again: the page's ``live`` command (as DataCube's Go Live), its copy let go, and the grid
        opened over its frames as they are now (what changed meanwhile said, as a load says it). Refused when it reads
        them live already."""
        _ = self._view
        page = self._page
        with page._batch():
            page._apply_page([{'kind': 'live', 'grid': self._id}], 'go live')
            page._current(self._view['cube'], 3)
        return self

    @property
    def snapped(self) -> dict[str, Any] | None:
        """The copy it reads when it is snapped, as the page writes it down -- its ``label`` ("Snap 14:02"), when it was
        ``takenAt`` (an ISO instant), its ``rowCount``, its ``table``, and its ``pulledBy`` when it was pulled from a
        warehouse -- or None: it reads its rows live."""
        copy = self._page._doc.get('copies', {}).get(self._view['cube'])
        return None if copy is None else _copied(copy)

    def __repr__(self) -> str:
        return f'<Grid {self._id}>'


def _given(mark: str, x: Any, y: Sequence[Any] | None, split: Any, frozen: bool | None) -> dict[str, Any]:
    """What a chart was told, in its spec's own keys (chart-spec.ts): DataCube makes the chart of it and judges it
    (_made), so nothing here is a rule of the chart's -- only Python's arguments turned into the spec's keys (an x or a
    split told None is null: none; frozen told False is null: the chart taken live). Its version is DataCube's: the
    chart it starts from (its own new chart, or the one edited) carries it."""
    return {'mark': mark, **({'x': x} if x is not _KEEP else {}),
            **({'y': _measures(y)} if y is not None else {}),
            **({'split': split} if split is not _KEEP else {}),
            **({'frozen': True if frozen else None} if frozen is not None else {})}


def _measure(plotted: Any) -> Any:
    """One measure of a chart in the spec's own keys: a ``(column, aggregate)`` pair is ``{'column', 'fn'}``, Python's
    shorthand; anything else goes as given (a dict may say its own ``mark``, ``axis`` and ``showAs``), for DataCube's
    reader to judge in its words (_made)."""
    if isinstance(plotted, tuple) and len(plotted) == 2:
        return {'column': _carried(plotted[0]), 'fn': _carried(plotted[1])}
    return _carried(plotted)


def _measures(plotted: Any) -> Any:
    """Measures in the spec's own keys (_measure each), or what was given when it is no list, for DataCube to judge."""
    return [_measure(p) for p in plotted] if isinstance(plotted, (list, tuple)) else _carried(plotted)


def _made(page: Page, cube: str, given: Mapping[str, Any], before: Mapping[str, Any] | None = None) -> dict[str, Any]:
    """The chart DataCube makes of what it was told -- what is not told is its own new chart's, and an edit of ``before``
    keeps the rest -- judged as DataCube will draw it (a live chart regrouped the way its grid is) against the chart's cube
    as DataCube opens it: its frame's columns and its calculated ones as the compiler types them now (the bundle's
    gridChart; chart-spec.ts chartFrom, followCube and chartProblems). The chart rules are written once, there: what
    DataCube would refuse to draw is refused here, in its words, and nothing is kept. A frozen chart whose grid was
    removed is asked against its cube, which stays with it. (V8 is the datacube extra's, as this module is: _extra.py.)"""
    page._current(cube, 5)
    session = page._session
    engine, frames = session.headless(), session.frames
    with frames.serving():
        made = _json.loads(engine.call('gridChart', _cube_spec(page._cube(cube), frames, engine, page._copy_base(cube)),
                                       _json.dumps(given), None if before is None else _json.dumps(before)))
    if made['problems']:
        raise ValueError(' '.join(made['problems']))
    return made['spec']


def _cube_spec(cube: Mapping[str, Any], frames: Any, engine: Any, copy: Mapping[str, Any] | None = None) -> dict[str, Any]:
    """A cube document and its base, as the bundle's grid functions take them (the frames held still): the base as
    DataCube says it is over the frames this session serves (headless gridBase -- a frame's own model, a join's of
    frames one model of them; docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §18.3), never by the cube's kind here -- or, its
    grid snapped, its ``copy``: the model DataCube wrote for the copy as it landed, and the relation that reads it
    (§18.8). The cube document never carries its base, so one opened over its copy is written as the live one is."""
    text = _json.dumps(cube)
    if copy is not None:
        return {'cube': text, 'model': copy['model'], 'runtime': copy['runtime'],
                'relation': _json.dumps({'_type': 'lambda', 'parameters': [], 'body': [copy['source']]})}
    return {'cube': text, **json.loads(engine.call('gridBase', text, frames.hosted()))}


def _cube_leaves(session: Any, cube: Mapping[str, Any]) -> list[dict[str, str]]:
    """A cube's leaves as DataCube says what a cube reads (headless cubeLeaves: cube-document.ts leavesOf), in its order:
    each ``{"_type", "name"}`` -- a frame's name its table in the frames' database."""
    return json.loads(session.headless().call('cubeLeaves', _json.dumps(cube)))


def _cube_frames(session: Any, cube: Mapping[str, Any]) -> list[str]:
    """The frames a cube reads, as DataCube says (its leaves), each once, in its order."""
    return list(dict.fromkeys(leaf['name'] for leaf in _cube_leaves(session, cube) if leaf['_type'] == 'frame'))


def _slots(grain: str | None, bins: Mapping[str, float] | None, tooltip: Sequence[Any] | None,
           layers: Sequence[Mapping[str, Any]] | None, label: Any = None, size: Any = None,
           facet: Mapping[str, str] | None = None, at: Mapping[str, str] | None = None) -> dict[str, Any]:
    """The spec's version 2 slots a chart was given, in the spec's own keys and as given: DataCube's reader and rules
    judge each (_made), in their words."""
    given = {'size': None if size is None else _measure(size), 'facet': facet, 'grain': grain, 'bins': bins,
             'label': None if label is None else _measure(label),
             'tooltip': None if tooltip is None else _measures(tooltip), 'layers': layers, 'at': at}
    return {key: _carried(value) for key, value in given.items() if value is not None}


class GridView:
    """A grid's view, as DataCube computed it (``Grid.view()``): its ``columns`` (each a dict: ``name``, ``path``, its
    pivot's values and the measure, ``type``, ``dimension``; a grouped grid's first is ``__tree``, its groups' labels,
    as on screen), its ``rows`` (each a list, one value per column, numbers exact: a Decimal's ``Decimal``), their
    ``tree`` (each row's ``path``, ``level``, and whether it is a ``group``, ``expanded`` or a ``total``),
    the levels the row cap ``truncated``, a flat grid's ``total_rows`` when the cap cut it, and the ``query`` and ``sql``
    that answered it."""

    def __init__(self, view: Mapping[str, Any]) -> None:
        self.columns: list[dict[str, Any]] = list(view['columns'])
        # each value as its column's type says (by legend-lite's lattice, either compiler's name: _types): a Decimal's
        # text (DataCube's exact decimals) a Decimal -- a Number's too, which is one when the database answered an exact
        # decimal (an Integer's quartile) --, a Float a float even when JSON wrote it as a whole number (10.0 is
        # written 10)
        def typed(v: Any, type: str) -> Any:
            if (is_decimal(type) or is_number(type)) and isinstance(v, str):
                return Decimal(v)
            if is_float(type) and isinstance(v, int) and not isinstance(v, bool):
                return float(v)
            return v
        types = [c['type'] for c in self.columns]
        self.rows: list[list[Any]] = [[typed(v, t) for v, t in zip(row, types)] for row in view['rows']]
        self.tree: list[dict[str, Any]] = list(view['tree'])
        self.truncated: list[dict[str, Any]] = list(view['truncated'])
        self.total_rows: int | None = view.get('totalRows')
        self.query: str = view['query']
        self.sql: str = view['sql']

    def to_pandas(self) -> Any:
        """The rows as the grid shows them, a DataFrame with one column per column shown: an Integer or a Boolean
        column with nulls an integer or a boolean still (pandas' nullable types, as ``frames.to_pandas``)."""
        import pandas as pd

        def column(i: int, type: str) -> Any:
            values = [row[i] for row in self.rows]
            integer = is_integer(type)
            # an Integer past 64 bits kept exact, as Python's own rows keep it (frames.to_pandas): pandas' objects
            if integer and any(isinstance(v, int) and not -2 ** 63 <= v < 2 ** 63 for v in values):
                return pd.Series(values, dtype=object)
            nullable = pd.Int64Dtype() if integer else pd.BooleanDtype() if is_boolean(type) else None
            return pd.Series(values, dtype=nullable)
        # by position: a pivot's columns may share a name
        frame = pd.DataFrame({i: column(i, c['type']) for i, c in enumerate(self.columns)})
        frame.columns = [c['name'] for c in self.columns]
        return frame

    def __repr__(self) -> str:
        return f'<GridView: {len(self.rows)} rows, {len(self.columns)} columns{", truncated" if self.truncated else ""}>'


@_operations
class Chart(Tile):
    """A chart of a grid: its mark, what it plots, its options; following its grid unless frozen."""

    _kind = 'chart'

    @property
    def grid(self) -> Grid | None:
        """Its grid; None when the grid was removed and the chart, frozen, stayed (DataCube's detached chart)."""
        cube = self._view['cube']
        return next((g for g in self._page.grids if g._view['cube'] == cube), None)

    def plot(self, mark: str | None = None, *, x: Any = _KEEP, y: Any = _KEEP, split: Any = _KEEP,
             frozen: bool | None = None, at: Any = _KEEP) -> Chart:
        """What it plots changed, as DataCube's options form changes it (chart-spec.ts editChart): its ``mark``, ``x``
        (None: none), ``y``, ``split`` (None: none), a bubble map's places ``at`` (``{'lat': ..., 'lon': ...}``; None:
        none) or ``frozen`` (False: following its grid); what is not given stays, a
        new column across takes its own order and a grain or bins go with the old one, and a live chart told its ``x``,
        ``y`` or ``split`` freezes unless told ``frozen``. DataCube makes the edit and judges it (_made): a detached chart
        against its cube, which stays."""
        view = self._view
        given = {**({'mark': mark} if mark is not None else {}), **({'x': x} if x is not _KEEP else {}),
                 **({'y': _measures(y)} if y is not _KEEP else {}),
                 **({'split': split} if split is not _KEEP else {}),
                 **({'at': None if at is None else _carried(at)} if at is not _KEEP else {}),
                 **({'frozen': True if frozen else None} if frozen is not None else {})}
        self._page._apply_page([{'kind': 'setChart', 'chart': self._id,
                                 'spec': _made(self._page, view['cube'], given, view['spec'])}])
        return self

    def options(self, settings: Mapping[str, Any]) -> Chart:
        """The chart's Options (``orientation``, ``stack``, ``sort``, ``limit``, ``labels``, ``legend``), in its spec's own
        keys; one left out is the chart's default. DataCube makes the edit and judges it (_made)."""
        view = self._view
        self._page._apply_page([{'kind': 'setChart', 'chart': self._id,
                                 'spec': _made(self._page, view['cube'], {'options': _carried(settings)}, view['spec'])}])
        return self

    def __repr__(self) -> str:
        return f'<Chart {self._id}>'


@_operations
class Sheet:
    """A sheet of a page: a whole screen of tiles, in places -- each a tile, or a stack of tiles shown one at a time --
    laid out in bands, each band's places side by side (``layout``, ``arrange``, ``layout_from``)."""

    def __init__(self, page: Page, id: str) -> None:
        self._page = page
        self._id = id
        self._dead = False

    @property
    def page(self) -> Page:
        return self._page

    @property
    def id(self) -> str:
        return self._id

    @property
    def _doc(self) -> dict[str, Any]:
        sheet = None if self._dead else next((s for s in self._page._doc['sheets'] if s['id'] == self._id), None)
        if sheet is None:
            raise ValueError(f'{self!r} is no longer on its page')
        return sheet

    @property
    def name(self) -> str | None:
        """Its own name; None: named after its first grid, as DataCube names a sheet."""
        return self._doc.get('name')

    @name.setter
    def name(self, name: str | None) -> None:
        _ = self._doc
        if name is not None and not isinstance(name, str):
            raise ValueError(f"a sheet's name is a name, not {name!r}")
        self._page._apply_page([{'kind': 'renameSheet', 'sheet': self._id, 'name': name}], "a sheet's name")

    @property
    def tiles(self) -> list[Tile]:
        """Its tiles in reading order, a stack's in its tabs' order."""
        return [self._page._tile_of(t) for t in self._page._tiles(self._doc['layout'])]

    def add(self, *tiles: Tile) -> Sheet:
        """Puts ``tiles`` on this sheet, each at its bottom, from the sheet it was on, as the screen's Move to Sheet
        moves a tile (page-commands.ts moveTile): one already on this sheet stays where it is."""
        _ = self._doc
        for tile in tiles:
            if tile._page is not self._page:
                raise ValueError(f'{tile!r} is another page\'s')
            _ = tile._view
        self._page._apply_page([{'kind': 'moveTile', 'tile': t._id, 'sheet': self._id, 'columns': None} for t in tiles])
        return self

    def layout(self, *bands: Sequence[Tile]) -> Sheet:
        """Its places laid out in bands, top to bottom, each band's places side by side and alike, as the layout picker
        lays them out: ``sheet.layout([grid, chart], [other])``. Every tile on the sheet is in one band; a stack is named
        by any of its tiles. (``layout_from`` takes any layout DataCube draws: splits, sizes, heights.)"""
        for band in bands:
            if isinstance(band, Tile) or not band:
                raise ValueError('a band is a list of tiles, one at least: sheet.layout([grid, chart], [other])')
            for tile in band:
                if not isinstance(tile, Tile):
                    raise ValueError(f'a band is a list of tiles, not {tile!r}')
                _ = tile._view
        # the bands as DataCube's layout picker lays them out (headless layoutBands: its rows shape, the places in order)
        said = json.loads(self._page._session.headless().call(
            'layoutBands', _json.dumps(self._doc['layout']), json.dumps([[t._id for t in band] for band in bands])))
        if 'problems' in said:
            raise ValueError('; '.join(said['problems']))
        if said['preset'] is None:
            return self  # a sheet with no tiles, laid out in no bands: as it is (no shape names no bands)
        return self._arranged(said['preset'], said['order'])

    def arrange(self, shape: str) -> Sheet:
        """Its places arranged as one of DataCube's layout picker's shapes, in reading order (a stack is one place):
        ``'side-by-side'`` (one band), ``'stacked'`` (a band each), ``'rows:2-1'`` (bands of 2 then 1),
        ``'columns:1-2'``, ``'focus-left'`` (the first half the page, the rest beside it) and the picker's others, as
        DataCube names them."""
        if not isinstance(shape, str):
            raise ValueError(f"a shape is the layout picker's name ('side-by-side', 'rows:2-1', ...), not {shape!r}")
        return self._arranged(shape, None)

    def _arranged(self, preset: str, order: list[str] | None) -> Sheet:
        """Its places arranged as the screen's layout picker arranges them (page-commands.ts arrange), in ``order`` (their
        tiles; None: reading order)."""
        try:
            self._page._apply_page([{'kind': 'arrange', 'sheet': self._id, 'preset': preset, 'order': order}])
        except ValueError as e:
            raise ValueError(f'{e} (a shape as the layout picker names it, or sheet.layout(...) band by band)') from None
        return self

    def layout_from(self, layout: Mapping[str, Any]) -> Sheet:
        """Its layout in the page document's own words -- ``{"kind": "bands", "fit": ..., "bands": [{"height": ...,
        "node": ...}]}``, each node a ``{"tile": id}``, a ``{"stack": [ids]}`` or a ``{"split": "row" | "column",
        "parts": [{"node": ..., "size": share}]}`` -- as ``page.to_dict()`` shows a sheet's. Every tile on the sheet is in
        it once, and only they: the page with it is read as DataCube reads one, and refused in its words."""
        _ = self._doc  # a sheet no longer on its page refused, as every sheet call refuses one
        if not isinstance(layout, Mapping):
            raise ValueError(f"a sheet's layout is {{'kind': 'bands', 'fit': ..., 'bands': [...]}}, not {layout!r}")
        self._page._apply_page([{'kind': 'layout', 'sheet': self._id, 'layout': _copied(layout)}])
        return self

    def __repr__(self) -> str:
        return f'<Sheet {self._id}>'


# -- the page ---------------------------------------------------------------------------------------------------------

@_operations
class Page:
    """A DataCube page: its grids over frames (``grid``), their charts, its sheets (``sheet``), stacks (``stack``) and
    layouts -- shown with ``ll.show(page)``, live from then on; saved as DataCube's page document (``save``,
    ``to_json``, ``to_dict``) and loaded again (``load``); read back as it is open (``read``)."""

    def __init__(self, name: str = 'Untitled page') -> None:
        from .datacube import _current
        if name is None:
            raise ValueError('a page has a name')
        self._session = _current()
        if not isinstance(name, str):
            raise ValueError(f"a page's name is a name, not {name!r}")
        # the page, started as DataCube starts a page (headless pageNew: its name by DataCube's rule, no grid yet, its
        # one sheet an empty board), and kept by its session's keeper from now on: the one copy of it every editor --
        # this page, a tab or a notebook widget open on it -- writes, versioned (docs/DATACUBE_PAGE_KEEPER_2026_10_20.md)
        said = json.loads(self._session.headless().call('pageNew', name))
        if 'problems' in said:
            raise ValueError("a page's name: " + '; '.join(said['problems']))
        self._key_name = f'page-{next(_KEYS)}'
        # the document a call works on: the keeper's as the call started (`_version`), changed by the call -- `_dirty`
        # -- until it is written (`_changed`); `_depth`: how deep in calls on the page this thread is
        self._document: dict[str, Any] = _plain(_json.loads(said['page']))
        self._version = self._session.keeper.keep(self._key_name, self._document, [])
        self._dirty = False
        self._depth = 0
        # collected, forgotten by the keeper at the session's next safe moment (never in the collector), its copies with it
        weakref.finalize(self, self._session.gone.append, self._key_name)
        # every grid command DataCube applied for this page's grids, in order, as the screen would have built them
        self._commands: list[dict[str, Any]] = []
        # every page command DataCube applied to this page, in order, as applied (page-commands.ts)
        self._page_commands: list[dict[str, Any]] = []
        # what changed it since it was last served, as the commands it was (a page's, a grid's with its id) -- what a page
        # open on it applies rather than opening it again (docs/DATACUBE_LIVE_COMMANDS_2026_10_18.md); None once it was
        # changed by no command, which the open page follows by opening it again
        self._unserved: list[dict[str, Any]] | None = []
        # each handle given, by its kind and id: the same object each time it is asked for
        self._handles: dict[tuple[type[Any], str], Any] = {}
        # every id the page has held: none is given out again, so no handle meets a new tile under its old one's id
        self._ids: set[str] = _ids(self._doc)
        # the ids it held at the version it is at (`_version`): what a change it made since is made again on top of,
        # when the page moved on -- an id that change gave out is the change's own, never one held before (_rebased)
        self._held: set[str] = set(self._ids)
        # where it is shown (datacube.show_page): told each time it changes
        self._shown: list[Any] = []
        self._quiet = 0
        # the saved page it was last saved as (``save()``): its id, or None
        self._saved: str | None = None

    # -- the page's one copy, its keeper's (docs/DATACUBE_PAGE_KEEPER_2026_10_20.md) ------------------------------------

    @property
    def _doc(self) -> dict[str, Any]:
        """The page's document as this call has it: the keeper's as it started, with what the call changed. Asked outside
        any call (a view, the engine), the keeper's as it is now."""
        if self._depth == 0 and not self._dirty:
            self._pull()
        return self._document

    @_doc.setter
    def _doc(self, doc: dict[str, Any]) -> None:
        self._document = doc
        self._dirty = True

    def _pull(self) -> None:
        """The keeper's document taken, when it moved on (an open page wrote it): a handle of a tile it no longer has,
        gone; every id it holds, held."""
        kept = self._session.keeper.current(self._key_name)
        if kept is None or kept[0] == self._version:
            return
        version, doc = kept
        doc = copy.deepcopy(doc)
        self._forget(_ids(self._document) - _ids(doc))
        self._document, self._version = doc, version
        self._ids |= _ids(doc)
        self._held = set(self._ids)
        self._unserved = []

    @contextlib.contextmanager
    def _call(self) -> Any:
        """Work on the page as one call (``_operation``; ``load``): the page as the keeper has it when the outermost
        begins, what it changed written once when it ends, let go when it raises -- a public call made inside it (the
        page's ``name``, read while a cube opens) part of it, never a write of a page half made."""
        self._enter()
        try:
            yield
        except BaseException:
            self._leave(failed=True)
            raise
        self._leave(failed=False)

    def _enter(self) -> None:
        if self._depth == 0 and not self._dirty:
            self._pull()
        self._depth += 1

    def _leave(self, failed: bool) -> None:
        self._depth -= 1
        if self._depth == 0 and self._dirty and not self._quiet:
            if failed:
                self._discard()
            else:
                self._changed()

    def _discard(self) -> None:
        """What this call changed and did not write, let go: the page the keeper's."""
        self._dirty = False
        self._unserved = []
        self._version = 0
        self._pull()

    def _commit(self) -> None:
        """WHAT CHANGED, WRITTEN: the page's document, against the version its call started at, with the commands that
        changed it (for a page open on it to apply). The page moved on meanwhile -- an open page wrote it -- the commands
        are applied again on the page as it is now (``_rebased``), as DataCube's own function applies them; a change by no
        command, or one that no longer applies (a tile closed there), is refused, nothing changed."""
        keeper = self._session.keeper
        commands = self._unserved
        for _ in range(8):
            try:
                self._version = keeper.write(self._key_name, self._version, self._document, sorted(self._frames()),
                                             commands)
                break
            except Stale:
                if commands is None:
                    self._discard()
                    raise ValueError('the page changed where it is open while this change was made, which is no command '
                                     'to make again on it: make the change again') from None
                try:
                    self._version, self._document, held = _rebased(self, commands)
                    self._ids |= held
                except ValueError as refused:
                    self._discard()
                    raise ValueError(f'the page changed where it is open while this change was made: {refused}') from None
        else:
            self._discard()
            raise ValueError('the page kept changing where it is open while this change was made: make it again')
        self._dirty = False
        self._unserved = []
        self._held = set(self._ids)

    # -- the page's model (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md B4): its classes, into a project ----------------------

    @property
    def model(self) -> dict[str, Any] | None:
        """Where the page's classes are, as the page document keeps it (DataCube's ``PageModelRef``): its project,
        workspace and package, each grid's table and class by its cube, and the revision it is pinned to (the one its
        last model action saved); None before its first class."""
        ref = self._doc.get('model')
        return _copied(ref) if ref else None

    def _names(self) -> dict[str, Any]:
        """The page's model as DataCube's windows name it before its first class (headless ``pageModelNames``): its
        package, its data space, its project to make and DataCube's workspace in it."""
        return _json.loads(self._session.headless().call('pageModelNames', self.name))

    def _into(self, package: str, sdlc: Any, writer: Any, call: Any, project: str | None = None) -> Any:
        """A model action into the page's project (this user's model home, unless ``sdlc`` and ``writer`` say otherwise):
        the page's own, or one made as DataCube makes a page's (``pageModelNames``: in its group, named after the page,
        or ``project``), the page's from the moment it is made."""
        from . import model as models
        ref = self._doc.get('model')
        if ref:
            at = (ref['project'], ref['workspace'], ref['package'])
        else:
            names = self._names()
            made_project = names['project']
            at = (models.NewProject(project or made_project['name'], made_project['group'], made_project['description']),
                  names['workspace'], package)

        def made(project_id: str) -> None:
            self._doc = {**self._doc, 'model': {'project': project_id, 'workspace': at[1], 'package': at[2], 'tables': {}}}
            # the page's model is no page command's: a page open on it opens it again
            self._unnoted()
            self._changed()
        created = call(sdlc if sdlc is not None else models.local_sdlc(),
                       writer if writer is not None else models.local_writer(), at, made)
        # the page pinned to what it saved (page-model.ts ``pin``): its class grids are read at it
        ref = self._doc['model']
        if ref.get('revision') != created.revision:
            self._doc = {**self._doc, 'model': {**ref, 'revision': created.revision}}
            self._unnoted()
            self._changed()
        return created

    def _keep(self, cube: str, tables: Sequence[str], cls: str | None) -> None:
        """The page's model now keeps ``tables`` (every table the grid ``cube`` reads, a join's each) and ``cls`` for it
        (page-model.ts ``keepTables``, ``keepClass``)."""
        ref = dict(self._doc['model'])
        ref['tables'] = {**ref.get('tables', {}), cube: list(tables)}
        if cls is not None:
            ref['classes'] = {**ref.get('classes', {}), cube: cls}
        self._doc = {**self._doc, 'model': ref}
        self._unnoted()
        self._changed()

    def create_association(self, a: Any, b: Any, *, name: str | None = None, sdlc: Any = None,
                           writer: Any = None) -> str:
        """An association between two of the page's classes, as DataCube's Create an Association… makes it: each end
        a class and what it is joined on -- ``(class, on)``, or ``{'class', 'on', 'name', 'multiplicity', 'unique'}``
        with the name the other class reaches it by and how many of it one of the other has -- preset, as the window
        presets it, from whether its key is unique over its class's rows when its class was made from one of this page's
        grids. Answers the association's path."""
        from . import model as models
        ref = self._doc.get('model')
        if not ref:
            raise ValueError("an association relates two of the page's classes: make them first (grid.create_class, or "
                             'page.create_data_space for the whole page)')
        ends = [_end(a), _end(b)]

        def made_with_facts(s: Any, w: Any, at: Any, m: Any) -> Any:
            self._facts(s, w, at, ends)
            return models.create_association(s, w, *at, {**({'name': name} if name else {}), 'ends': ends}, made=m)
        made = self._into(ref['package'], sdlc, writer, made_with_facts)
        return next((x['association'] for x in made.answer.get('associations', [])), name or 'the association')

    def _facts(self, sdlc: Any, writer: Any, at: Any, ends: list[dict[str, Any]]) -> None:
        """Each end that says neither how many nor whether its key is unique given the fact, as Create an Association…
        presets it (model-actions.ts ``unique``): the class's key read through its relation function over the project
        (the writer's ``joinable`` names the function and the column), DataCube's query for a value held twice
        (headless ``classDuplicate``) planned over the project's model through its place's runtime and run on Python's
        database -- no row, and the key is unique. An end whose facts cannot be read (the project's tables not Python's)
        is left for the writer to refuse in words, as the window leaves it for the person."""
        from . import model as models
        open_ends = [e for e in ends if 'unique' not in e and 'multiplicity' not in e]
        if not open_ends:
            return
        text, _ = models.project_state(sdlc, at[0], at[1])
        joinable = {j['class']: j for j in writer.create_association(text, at[2], None).get('joinable', [])}
        frames, headless = self._session.frames, self._session.headless()
        for end in open_ends:
            j = joinable.get(end['class'], {})
            column = next((k.get('column') for k in j.get('keys', []) if k['name'] == end['on']), None)
            if j.get('function') is None or column is None:
                continue
            query, runtime = headless.call('classDuplicate', j['function'], column), f'{at[2]}::runtime::Python'
            # planned as the page's own grids are, by Python's own compiler over the model it holds, and run there
            with frames.serving():
                held = frames.model_for(text)
                if held is None:
                    continue
                end['unique'] = frames.run(held.plan(_json.loads(query), runtime)).num_rows == 0

    def create_data_space(self, name: str | None = None, *, title: str | None = None, description: str | None = None,
                          associations: Sequence[Any] = (), project: str | None = None, sdlc: Any = None,
                          writer: Any = None) -> str:
        """The whole page in one go, as DataCube's Create a Data Space… makes it: each grid not yet a class made one
        (named as the window suggests; a grid over no table of its own left out, as the window leaves it; a sample a
        class cannot hold refuses the whole, as the window refuses it, before anything is written), the
        associations given (as ``create_association`` takes them: pairs of ends, or ``{'name', 'ends'}``) made, and a
        data space over the page's classes -- one revision, the project made with it when the page has none (named
        ``project``, else after the page). ``name``: the data space's (suggested from the page's name). Answers the data
        space's path."""
        from . import model as models
        ref = self._doc.get('model')
        names = self._names()
        taken = (ref or {}).get('classes', {})
        classes, kept = [], []
        for grid in self.grids:
            cube = grid._view['cube']
            if cube in taken:
                continue
            try:
                cls, asked = grid._class()
            except _NotATable:
                continue
            classes.append(cls)
            kept.append((cube, asked['tables'], cls['name']))
        package = ref['package'] if ref else names['package']
        data_space = {'name': name or names['dataSpace'], **({'title': title} if title else {}),
                      **({'description': description} if description else {})}
        joined = [x if isinstance(x, Mapping) else {'ends': [_end(x[0]), _end(x[1])]} for x in associations]
        made = self._into(package, sdlc, writer, lambda s, w, at, m: models.create_data_space(
            s, w, *at, data_space, classes, [{**j, 'ends': [_end(e) for e in j['ends']]} for j in joined], made=m),
            project)
        made_classes = [c['class'] for c in made.answer.get('classes', [])]
        for cube, tables, class_name in kept:
            path = next((c for c in made_classes if c.rsplit('::', 1)[-1] == class_name), None)
            self._keep(cube, tables, path)
        return made.answer.get('dataSpace') or data_space['name']

    @property
    def name(self) -> str:
        return self._doc['name']

    @name.setter
    def name(self, name: str) -> None:
        if name is None:
            raise ValueError('a page has a name')
        if not isinstance(name, str):
            raise ValueError(f"a page's name is a name, not {name!r}")
        # the page's name is each cube's too: DataCube writes them (page-commands.ts renamePage), as the screen does
        self._apply_page([{'kind': 'renamePage', 'name': name}], "a page's name")

    @property
    def sheets(self) -> list[Sheet]:
        """Its sheets, in their tabs' order."""
        return [self._sheet(s['id']) for s in self._doc['sheets']]

    @property
    def grids(self) -> list[Grid]:
        return [self._tile(v['id'], Grid) for v in self._doc['views'] if v['kind'] == 'grid']

    @property
    def charts(self) -> list[Chart]:
        return [self._tile(v['id'], Chart) for v in self._doc['views'] if v['kind'] == 'chart']

    # -- building ------------------------------------------------------------------------------------------------------

    def grid(self, frame: Any, *, name: str | None = None, rows: Sequence[str] = (), columns: Sequence[str] = (),
             measures: Mapping[str, str] | Sequence[tuple[str, str]] | None = None, filter: Any = None,
             sort: Sequence[tuple[str, str]] = (), dimensions: Sequence[str] = (), title: str | None = None,
             sheet: Sheet | None = None, snap: bool = False) -> Grid:
        """A grid over ``frame`` (a pandas or polars DataFrame, an Arrow table, or a function returning one), served
        under ``name`` (``frame``, ``frame_2``, ... unless given), in a band of its own at the bottom of ``sheet`` (the
        first unless given). Each query reads the frame as it is then; the grid's Snap keeps its rows as they are. A
        name served already is this grid's when it serves this same frame (two grids over one frame), and refused
        when it serves another: ``grid.update(frame)`` replaces a frame, every grid
        over it showing the new one. ``rows``, ``columns``, ``measures`` (``{column: aggregate}`` or ``[(column,
        aggregate)]``), ``filter`` and ``sort``: as ``group``, ``pivot``, ``measure``, ``filter`` and ``sort`` say;
        ``dimensions``: columns made dimensions first (``column(name, kind='dimension')``: a number is a measure until
        then, and only a dimension is grouped or pivoted on) -- all checked before the grid is on the page, so a
        mistake leaves the page and the frames as they were. ``snap``: the frame read once, by the grid's Snap
        (``grid.snap()``), as it is now -- the frame stays served as it is, so another grid over it can read it live."""
        if sheet is not None and sheet._page is not self:
            raise ValueError(f'{sheet!r} is another page\'s')
        target = self.sheets[0] if sheet is None else sheet
        _ = target._doc
        if title is not None:
            title = _given_name(self._session, 'tile', title, 'a title')
        session = self._session
        if name is not None and name in session.frames:
            table = session.frames[name]
            if table.given is not frame:
                raise ValueError(f'{name!r} serves another frame already: grid.update(frame) replaces a frame, every grid '
                                 f'over it showing the new one; or give this grid a name of its own')
            served, new = table.name, False
        else:
            served, new = session.register(frame, name), True
        # the page as it was, to go back to when the grid is refused
        was = (self._doc, set(self._ids), len(self._page_commands), len(self._commands),
               None if self._unserved is None else list(self._unserved), self._dirty)
        id = None
        try:
            # its cube as DataCube makes a grid's over a frame: capped at the row limit when it is not the page's only grid
            cube, _, _ = self._frame_cube(served, len(self._doc['cubes']) + 1)
            # on its sheet, as the screen adds a grid (page-commands.ts addGrid): its id after every one held, a band at
            # the bottom, told nothing yet
            with self._batch(tell=False):
                applied = self._apply_page([{'kind': 'addGrid', 'document': cube, 'sheet': target.id, 'near': None,
                                             'title': title, 'columns': None}], 'a title')
            id = applied[0]['id']
            grid = self._tile(id, Grid)
            # set before it is placed, nothing told meanwhile: its query's parts as one, as DataCube sets them (a new
            # grid's cube has none: nothing to ask when none is given)
            with self._batch(tell=False):
                pairs = list(measures.items() if isinstance(measures, Mapping) else measures or ())
                # in the order a person would make them: the kinds first (a column made a dimension can then be
                # grouped), the row groups and the pivot, the measures (a measure of a grouped column is one of its own),
                # the sorts last (one may name a column a measure made)
                commands = [
                    *({'kind': 'column', 'name': d, 'change': {'kind': 'dimension'}} for d in dimensions),
                    *([{'kind': 'group', 'rows': list(rows)}] if rows else []),
                    *([{'kind': 'pivot', 'columns': list(columns)}] if columns else []),
                    *([{'kind': 'measures', 'measures': [{'name': c, 'column': c, 'fn': fn} for c, fn in pairs]}]
                      if pairs else []),
                    *([{'kind': 'sorts', 'sorts': [{'column': c, 'direction': d} for c, d in sort]}] if sort else []),
                ]
                if commands:
                    grid._apply(commands)
                if filter is not None:
                    grid.filter(filter)
                if snap:
                    # its query as set, copied: the copy holds every column of its base, so a later edit reads it too
                    grid.snap()
        except BaseException:
            # the page as it was (the grid, its id and the commands it was given gone), and a frame served for it served
            # no more
            self._doc, self._ids = was[0], was[1]
            self._dirty = was[5]
            del self._page_commands[was[2]:]
            del self._commands[was[3]:]
            self._unserved = was[4]
            if id is not None:
                self._forget({id})
            if new:
                session.unregister(served)
            raise
        self._changed()
        return grid

    def join(self, mine: Grid, theirs: Grid, how: Mapping[str, Any], *, title: str | None = None,
             sheet: Sheet | None = None, snap: bool = False) -> Grid:
        """A grid of ``mine`` joined with ``theirs`` (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §17), as the tab's Join…
        makes one: each grid's rows at their finest level, as each is now, combined as ``how`` says -- DataCube's own
        join (join-how.ts), read strictly: ``{'form': 'join', 'kind': 'inner' | 'left' | 'right' | 'full', 'on':
        {'rows': [{'left': 'customer_id', 'op': '==', 'right': 'id'}]}, 'prefix': 'their_', 'keep': None}`` (``keep``,
        the columns of theirs to keep, every one when None), ``{'form': 'semi' | 'anti', 'on': ...}``, ``{'form':
        'asOf', 'match': {...}, 'on': ..., 'prefix', 'keep'}`` or ``{'form': 'append'}``. Written by DataCube (headless
        joinCube) over the frames the two read, judged by the compiler -- a refusal in its words, the page as it was --
        and added on ``sheet`` (the first unless given) by the page command every grid is added by. Each input is kept
        by value: the join does not follow later edits of the grids joined. ``snap``: the join read once, by its grid's
        Snap (``grid.snap()``); refused, the page is as it was."""
        for g in (mine, theirs):
            if g._page is not self:
                raise ValueError(f'{g!r} is another page\'s')
        if sheet is not None and sheet._page is not self:
            raise ValueError(f'{sheet!r} is another page\'s')
        target = self.sheets[0] if sheet is None else sheet
        _ = target._doc
        if title is not None:
            title = _given_name(self._session, 'tile', title, 'a title')
        session = self._session
        engine, frames = session.headless(), session.frames
        # each input as the page names it (its tile's title, else its source's), and the tables its leaves read, in
        # their order: a frame's its own name
        inputs = [{'name': g.title or g._cube['source']['name'], 'cube': g._cube} for g in (mine, theirs)]
        tables = [leaf['name'] for g in (mine, theirs) for leaf in _cube_leaves(session, g._cube)]
        with frames.serving():
            made = json.loads(engine.call('joinCube', _json.dumps({
                'left': inputs[0], 'right': inputs[1], 'how': how, 'tables': tables,
            }), self.name, len(self._doc['cubes']) + 1))
        for note in made['notes']:
            warnings.warn(note, stacklevel=2)
        was = (self._doc, set(self._ids), len(self._page_commands),
               None if self._unserved is None else list(self._unserved), self._dirty)
        with self._batch(tell=False):
            applied = self._apply_page([{'kind': 'addGrid', 'document': _plain(_json.loads(made['cube'])),
                                         'sheet': target.id, 'near': None, 'title': title, 'columns': None}], 'a title')
        grid = self._tile(applied[0]['id'], Grid)
        if snap:
            try:
                with self._batch(tell=False):
                    grid.snap()
            except BaseException:
                # the page as it was: the join, its id and its command gone
                self._doc, self._ids = was[0], was[1]
                self._dirty = was[4]
                del self._page_commands[was[2]:]
                self._unserved = was[3]
                self._forget({grid.id})
                raise
        self._changed()
        return grid

    def sheet(self, name: str | None = None) -> Sheet:
        """A new sheet after the last (``name``: its own; None, named after its first grid)."""
        if name is not None and not isinstance(name, str):
            raise ValueError(f"a sheet's name is a name, not {name!r}")
        # as the screen adds one (page-commands.ts addSheet): its id after every one the page has held
        applied = self._apply_page([{'kind': 'addSheet', 'name': name}], "a sheet's name")
        return self._sheet(applied[0]['id'])

    def stack(self, *tiles: Tile) -> None:
        """``tiles`` in one place, as tabs, one shown at a time, where the first one is: all on one sheet. Stacked as
        DataCube stacks a tile dropped on another's middle (bands.ts stackTiles): the first one's place keeps its own
        tabs first, the others follow in the order given, and a tile stacked already brings its stack's others after
        them."""
        strays = [t for t in tiles if not isinstance(t, Tile)]
        if strays:
            raise ValueError(f'a stack is of tiles (grids and charts), not {", ".join(repr(t) for t in strays)}')
        if len({t._id for t in tiles}) < 2:
            raise ValueError('a stack is two tiles or more')
        if any(t._page is not self for t in tiles):
            raise ValueError('the tiles of a stack are of this page')
        for t in tiles:
            _ = t._view
        self._apply_page([{'kind': 'stack', 'tiles': [t._id for t in tiles]}])

    def remove(self, tile: Tile) -> None:
        """A tile off the page, its neighbours closing over its place: a grid with its charts, but a frozen one, which
        stays without it (as DataCube keeps one); a chart."""
        if tile._page is not self:
            raise ValueError(f'{tile!r} is another page\'s')
        _ = tile._view
        before = {v['id'] for v in self._doc['views']}

        def shown(page: dict[str, Any]) -> None:
            # a cube stays while a view shows it (a frozen chart's, its grid gone); a page with none is one DataCube does
            # not read, and a shown page is served again: refused before anything changes
            if not page['cubes'] and self._shown:
                raise ValueError('a page that is shown has a grid at least (DataCube shows no empty page): add another '
                                 'first, or close it')

        # as the screen removes one (page-commands.ts removeTile): a grid with the charts following it, a frozen one
        # staying detached; a chart's selection taken off its grid's filter; the model kept for the cubes still held
        self._apply_page([{'kind': 'removeTile', 'tile': tile._id}], check=shown)
        self._forget(before - {v['id'] for v in self._doc['views']})

    # -- the document --------------------------------------------------------------------------------------------------

    def to_dict(self) -> dict[str, Any]:
        """The page as DataCube's page document (its own version), a copy: what is done to it is not done to the page. A
        calculated column's numbers are exact (``Decimal``), as the compiler writes them: ``to_json`` and ``save`` write
        them so. It is the page's document as Python holds it; ``to_json`` and ``save`` write it as DataCube writes it
        (its reader's, headless: a chart's options filled, a key DataCube does not know left out)."""
        return _copied(self._doc)

    def to_json(self, indent: int | None = 2) -> str:
        """The page document as JSON text, its numbers exact, as DataCube writes it (what ``save`` writes): refused in
        DataCube's words when DataCube would not open it (a page with no grid)."""
        return _json.dumps(self._written(), indent=indent)

    def save(self, path: str | Path | None = None, *, name: str | None = None) -> str | None:
        """Saved. With no ``path``, as DataCube's Save keeps a page: into this user's saved pages (``ll.home()/pages``,
        what Open… lists in DataCube shown from Python), by legend-lite's own saved-page rules (the native library's,
        the page's and the server's: docs/ONE_PLATFORM_PROGRAM_2026_10_12.md B6) -- over the copy this page was last
        saved as, else as a new one -- named ``name`` (the page's own when not given); its id is returned. With a
        ``path``, the page document as a file instead (as DataCube's Export > Page File writes it)."""
        if path is not None:
            if name is not None:
                raise ValueError("a page saved as a file takes no name: its document carries the page's own")
            Path(path).write_text(self.to_json() + '\n', 'utf-8', newline='')
            return None
        from ._home import THIS_USER, pages_folder, store_call
        named = _given_name(self._session, 'page', name if name is not None else self.name, "a saved page's name")
        id = self._saved if self._saved is not None else str(uuid.uuid4())
        record = _json.dumps({'id': id, 'name': named, 'content': self._written()}, indent=None)
        status, said = (store_call('lite_saved_cubes', pages_folder(), 'PUT', f'/{id}', '', record, THIS_USER)
                        if self._saved is not None else (404, ''))
        if status == 404:
            # never saved, or its saved copy deleted since (in DataCube's Open…): saved as a new one under its id
            status, said = store_call('lite_saved_cubes', pages_folder(), 'POST', '', '', record, THIS_USER)
        if status != 200:
            try:
                said = json.loads(said).get('message', said)
            except (ValueError, AttributeError):
                pass  # not the store's {"message"}: said as it came
            raise ValueError(f'the page was not saved: {said}')
        self._saved = id
        return id

    @classmethod
    def load(cls, document: str | Path | Mapping[str, Any], frames: Mapping[str, Any]) -> Page:
        """A page document (a file, or its dict) as a page again, its grids over ``frames`` by their frame names (each
        cube's ``{"_type": "frame", "name": ...}``), each served: the document as DataCube reads it (its own version
        only; refused in its words when DataCube would not open it), each cube as DataCube opens it
        over its frame as it is now -- what DataCube leaves out of it warned, in its words. A grid saved snapped opens
        live, said: its copy lasted only as long as the session that made it (``grid.snap()`` takes one anew)."""
        from .datacube import _current
        # read on the session the page will be made on (Page() takes the current one)
        doc = _read_page(_copied(document) if isinstance(document, Mapping) else Path(document).read_text('utf-8'),
                         _current())
        session = _current()
        wanted = sorted({f for c in doc['cubes'] for f in _cube_frames(session, c['cube'])})
        missing = [n for n in wanted if n not in frames]
        if missing:
            raise ValueError(f'the page reads the frames {", ".join(missing)}: give them, frames={{name: frame}}')
        page = cls(doc['name'])
        # loaded as one call on the page: written once, whole, when it ends (a public call inside, as a cube opens,
        # never writes a page half loaded)
        with page._call():
            for name in wanted:
                page._session.register(frames[name], name)
            # live: a copy lasts only as long as the session that made it, so a page saved with a snapped grid opens
            # with it reading its frames, each said (DataCube's own rule, headless pageOpenedLive: §18.8)
            opened = json.loads(session.headless().call('pageOpenedLive', _json.dumps(doc)))
            for note in opened['notes']:
                warnings.warn(note, stacklevel=2)
            doc = _plain(_json.loads(opened['page']))
            page._doc = doc
            page._ids |= _ids(doc)
            # each cube as DataCube opens it over its frame as it is now (served above): what it leaves out, left out
            # here too and said once, so no later call on the grid sends it
            for c in doc['cubes']:
                page._reopened(c['id'], 3)
            # a page loaded is a page made whole, by no command
            page._unnoted()
        return page

    # -- shown ---------------------------------------------------------------------------------------------------------

    @property
    def _key(self) -> str:
        """Its name on the engine (page.json?page=): this page object's own."""
        return self._key_name

    def _showable(self) -> None:
        """Refused when it cannot be shown: a page DataCube would not open (one with no grid), in DataCube's words."""
        self._written()

    def _written(self) -> dict[str, Any]:
        """The page as DataCube reads it, to be written, saved or shown: refused in DataCube's words -- a page DataCube
        says has no cube with Python's own call to make one after them, as a filter's refusals say Python's call form."""
        try:
            return _read_page(self._doc, self._session)
        except _NoCube as refused:
            raise ValueError(f'{refused} -- in Python, page.grid(frame) adds one') from None

    def _changed(self) -> None:
        """What changed, written by the page's keeper (``_commit``), its commands with it -- what a page open on it
        applies, or, changed by no command, opens again by -- and the copies no page reads any more let go (the session's
        sweep). Inside a batch, written when the batch ends."""
        if self._quiet:
            return
        if self._dirty:
            self._commit()
        self._session.sweep()

    class _Batch:
        def __init__(self, page: Page, tell: bool) -> None:
            self.page = page
            self.tell = tell

        def __enter__(self) -> None:
            self.page._quiet += 1

        def __exit__(self, kind: Any, *_: Any) -> None:
            self.page._quiet -= 1
            # written once, when it went through; a change that raised writes nothing (the call lets it go)
            if kind is None and self.tell:
                self.page._changed()

    def _batch(self, tell: bool = True) -> Page._Batch:
        """Several changes, told as one (``tell=False``: not told, what changed not being on the page yet)."""
        return Page._Batch(self, tell)

    # -- the document's parts ------------------------------------------------------------------------------------------

    def _apply_page(self, commands: list[dict[str, Any]], what: str | None = None,
                    check: Any = None) -> list[dict[str, Any]]:
        """The page edited by commands, each the one DataCube's screen builds for the same edit (page-commands.ts:
        docs/DATACUBE_COMMANDS_PAGE_2026_10_18.md), applied by DataCube's own function (headless pageApply): refused in
        DataCube's words (``what`` before them), nothing changed; ``check`` may refuse the page they make, before it is
        kept. Else the page as DataCube leaves it, the ids it has held, and the commands as applied -- an id DataCube
        allocated in each that made a tile or a sheet -- recorded and returned."""
        said = json.loads(self._session.headless().call(
            'pageApply', _json.dumps(self._doc), json.dumps(sorted(self._ids)), _json.dumps(commands)))
        if 'problems' in said:
            words = '; '.join(said['problems'])
            raise ValueError(f'{what}: {words}' if what else words)
        page = _plain(_json.loads(said['page']))
        if check is not None:
            check(page)
        self._doc = page
        self._ids = set(said['held'])
        applied = [_plain(c) for c in said['commands']]
        self._page_commands.extend(applied)
        self._note([{'page': c} for c in applied])
        self._changed()
        return applied

    def _note(self, commands: list[dict[str, Any]]) -> None:
        """Commands that changed the page, for a page open on it to apply (``_changed`` serves them)."""
        if self._unserved is not None:
            self._unserved.extend(commands)

    def _unnoted(self) -> None:
        """The page changed by no command: a page open on it opens it again."""
        self._unserved = None

    def _tiles(self, layout: dict[str, Any]) -> list[str]:
        """A sheet's tiles in reading order, a stack's in its tabs' order, as DataCube reads its layout (bands.ts tiles,
        asked headless)."""
        return json.loads(self._session.headless().call('layoutTiles', _json.dumps(layout)))

    def _forget(self, ids: set[str]) -> None:
        """The handles of tiles and sheets gone, refused from now on."""
        for key in [k for k in self._handles if k[1] in ids]:
            self._handles.pop(key)._dead = True

    def _frames(self) -> set[str]:
        """The frames its cubes read (each cube's leaves: a join's every frame), a frozen chart's whose grid is gone
        included."""
        return {f for c in self._doc['cubes'] for f in _cube_frames(self._session, c['cube'])}

    def _cube(self, id: str) -> dict[str, Any]:
        return next(c['cube'] for c in self._doc['cubes'] if c['id'] == id)

    def _copy_base(self, cube: str) -> dict[str, Any] | None:
        """The copy cube ``cube`` (its id) reads when its grid is snapped (§18.8) -- its model, runtime and relation, as
        the engine holding it modelled it -- or None: it reads its rows live. A copy the page names that this session
        no longer holds is refused, never read live in its place. Asked with the frames held: it takes no session lock."""
        return _copy_base_of(self._session, self._doc, cube)

    def _served(self) -> list[str]:
        return [n for n in self._frames() if n in self._session.frames]

    def _tile(self, id: str, kind: type[Any]) -> Any:
        handle = self._handles.get((kind, id))
        if handle is None:
            handle = self._handles[(kind, id)] = kind(self, id)
        return handle

    def _tile_of(self, id: str) -> Tile:
        view = next(v for v in self._doc['views'] if v['id'] == id)
        return self._tile(id, Grid if view['kind'] == 'grid' else Chart)

    def _sheet(self, id: str) -> Sheet:
        return self._tile(id, Sheet)

    def _update(self, name: str, frame: Any) -> None:
        """Frame ``name`` served again over ``frame``: each grid's cube over it reopened over the frame as the compiler
        types it now (``_reopened``); the page served again when one changed."""
        session = self._session
        session.register(frame, name)
        # every cube reading the frame reopened (a join of it among them), whichever changed
        reading = [c['id'] for c in self._doc['cubes'] if name in _cube_frames(self._session, c['cube'])]
        reopened = [self._reopened(id, 4) for id in reading]
        if any(reopened):
            self._changed()

    def _reopened(self, id: str, stacklevel: int) -> bool:
        """The page's cube ``id`` as DataCube opens it over its frame as the compiler types it now (``_frame_cube``:
        cube-document.ts rebuilt -- a new column hidden; a gone one left out; the open groups dropped when the grouping
        changed), taken when it is
        not the cube held -- the frame's columns moved, or DataCube left a part out -- each note warned once, in
        DataCube's words. So the page holds the cube every headless call opens, and no later call sends a part DataCube
        leaves out. Whether it was taken. Asked when a page is loaded (``load``: its frames served), when a frame is
        served again (``_update``), and before a filter, a calculated column or a chart is checked against it
        (``_current``: a frame changed in place)."""
        if id in self._doc.get('copies', {}):
            # snapped: it reads its copy, which its frames do not move (it is opened over them again when it goes live)
            return False
        held = next(c['cube'] for c in self._doc['cubes'] if c['id'] == id)
        label = held['source']['name']
        cube, notes, changed = self._opened_cube(held)
        # its frames' columns as they were, nothing left out: the cube stands as it is (opened again, the same)
        if not changed and cube['source'] == held['source']:
            return False
        for note in notes:
            warnings.warn(f'{label}: {note}', stacklevel=stacklevel)
        self._doc = _with_cube(self._doc, id, cube)
        # a change by no command: a page open on it opens it again
        self._unnoted()
        return True

    def _current(self, cube: str, stacklevel: int) -> None:
        """The cube ``cube`` (its id) as DataCube opens it over its frame now, taken and said (``_reopened``) before a
        call checks a change against it and writes the change into it: a frame changed in place (a column gone, a
        calculated column that read it left out) is the cube DataCube checks, never the one held before."""
        self._reopened(cube, stacklevel + 1)

    def _opened_cube(self, saved: dict[str, Any]) -> tuple[dict[str, Any], list[str], bool]:
        """A saved cube as DataCube opens it over the frames as they are now -- a frame's, a join's of frames (headless
        gridOpened: cube-document.ts rebuilt, a join opened as the tab opens one) -- what DataCube says of what changed,
        and whether it left a part out."""
        engine, frames = self._session.headless(), self._session.frames
        with frames.serving():
            made = json.loads(engine.call('gridOpened', _json.dumps(saved), frames.hosted(), self.name,
                                          len(self._doc['cubes'])))
        return _plain(_json.loads(made['cube'])), list(made['notes']), made['changed']

    def _frame_cube(self, frame: str, grids_on_page: int,
                    saved: dict[str, Any] | None = None) -> tuple[dict[str, Any], list[str], bool]:
        """A grid's cube over ``frame`` as DataCube makes one (cube-document.ts rebuilt, asked headless: gridCube): a
        new grid's (its rows as they are; capped at the row limit when ``grids_on_page``, the page's grids counting
        it, are more than one: DataCube's gridCapped), or ``saved`` rebuilt over the frame as it is now; what DataCube
        says of what changed; and whether it left a part out."""
        engine, frames = self._session.headless(), self._session.frames
        with frames.serving():
            table = frames[frame]
            spec = {'model': table.model, 'runtime': table.runtime, 'source': table.accessor, 'frame': frame}
            made = json.loads(engine.call(
                'gridCube', spec, None if saved is None else _json.dumps(saved), self.name, frame, grids_on_page))
        return _plain(_json.loads(made['cube'])), list(made['notes']), made['changed']

    def __repr__(self) -> str:
        return f'<Page {self.name!r}: {len(self.grids)} grids, {len(self.charts)} charts, {len(self.sheets)} sheets>'


def _copy_base_of(session: Any, doc: dict[str, Any], cube: str) -> dict[str, Any] | None:
    """The copy cube ``cube`` of ``doc`` reads, as the engine holding it modelled it, or None: it reads its rows live. A
    copy the page names that the session no longer holds is refused, never read live in its place."""
    snapped = doc.get('copies', {}).get(cube)
    if snapped is None:
        return None
    held = session.copy_of(snapped['table'])
    if held is None:
        name = next(c['cube'] for c in doc['cubes'] if c['id'] == cube)['source']['name']
        raise ValueError(f'{name}: its snap ({snapped["label"]}) is no longer held by this session: grid.live() reads its '
                         'rows live again, grid.snap() snaps it anew')
    return held


def _rebased(page: Page, noted: list[dict[str, Any]]) -> tuple[int, dict[str, Any], set[str]]:
    """A PAGE'S CHANGE MADE AGAIN ON THE PAGE AS IT IS NOW (docs/DATACUBE_PAGE_KEEPER_2026_10_20.md): ``noted``, the
    commands that made it -- a page's, or a grid's by its tile's id -- applied in order, by DataCube's own functions
    (headless pageApply, gridApply over each grid's base as it is now), to the keeper's document: its version, the page
    they make and the ids it holds -- or, one no longer applying (a tile closed, an id given since), refused in
    DataCube's words (``ValueError``)."""
    session = page._session
    kept = session.keeper.current(page._key)
    if kept is None:
        raise ValueError('the page is no longer kept')
    version, doc = kept[0], copy.deepcopy(kept[1])
    # the ids held before the change (the ones it gave out its own, given again as it gave them) and the page's now
    held = page._held | _ids(doc)
    engine, frames = session.headless(), session.frames
    for entry in noted:
        if 'page' in entry:
            said = json.loads(engine.call('pageApply', _json.dumps(doc), json.dumps(sorted(held)),
                                          _json.dumps([entry['page']])))
            if 'problems' in said:
                raise ValueError('; '.join(said['problems']))
            doc, held = _plain(_json.loads(said['page'])), set(said['held'])
            continue
        view = next((v for v in doc['views'] if v['id'] == entry['grid'] and v['kind'] == 'grid'), None)
        if view is None:
            raise ValueError(f"{entry['grid']} is no longer on the page")
        cube = next(c['cube'] for c in doc['cubes'] if c['id'] == view['cube'])
        base = _copy_base_of(session, doc, view['cube'])
        with frames.serving():
            said = json.loads(engine.call('gridApply', _cube_spec(cube, frames, engine, base),
                                          _json.dumps([entry['command']])))
        if 'problems' in said:
            raise ValueError('; '.join(said['problems']))
        doc = _with_cube(doc, view['cube'], _plain(_json.loads(said['cube'])))
    return version, doc, held


def _ids(doc: dict[str, Any]) -> set[str]:
    """The ids a page document gives its cubes, views and sheets."""
    return {x['id'] for part in ('cubes', 'views', 'sheets') for x in doc[part]}


class _NotATable(ValueError):
    """A grid DataCube cannot make a class of, its source no table of its own: Create a Data Space… leaves it out."""


class _NoCube(ValueError):
    """DataCube's reader refused a page for having no cube (headless pageRead's ``noCube``): told, so Python can add
    the call that makes one."""


def _read_page(doc: Any, session: Any) -> dict[str, Any]:
    """``doc`` (a page document, or its JSON text as it is) as DataCube reads a page document and would write it again
    (page-document.ts readPage and pageToJson, asked headless, on ``session``'s engine: the page's own): of DataCube's
    own version, every rule of its reader held, and refused in its words otherwise -- Python
    keeps no second copy of them. Then what a page in Python adds: each cube reads frames -- its leaves, as DataCube says
    what a cube reads (headless cubeLeaves: a frame's grid its one, a join's of frames each;
    docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §18.3)."""
    engine = session.headless()
    said = json.loads(engine.call('pageRead', doc if isinstance(doc, str) else _json.dumps(doc)))
    if 'problems' in said:
        raise (_NoCube if said.get('noCube') else ValueError)('; '.join(said['problems']))
    page: dict[str, Any] = _plain(_json.loads(said['page']))
    for c in page['cubes']:
        for leaf in json.loads(engine.call('cubeLeaves', _json.dumps(c['cube']))):
            if leaf['_type'] != 'frame':
                raise ValueError(f'the cube {c["id"]} reads {leaf["name"]}, which is no frame (a page in Python is over '
                                 'frames)')
    return page


def _column_choices(columns: Mapping[str, Any]) -> dict[str, dict[str, str]]:
    """A person's choices for the class's columns, as the window gives them (model-changes' ``ColumnChoice``)."""
    out: dict[str, dict[str, str]] = {}
    for column, choice in columns.items():
        if isinstance(choice, str):
            choice = {'as': choice}
        # its role ('property', 'key' or 'omit') is the writer's to judge (CreateClass refuses another, by name)
        out[column] = {k: v for k, v in choice.items() if k in ('as', 'name', 'documentation')}
    return out


def _end(end: Any) -> dict[str, Any]:
    """An association's end as the writer takes it (model-changes' ``AssociationEnd``): ``(class, on)`` or a mapping."""
    if isinstance(end, Mapping):
        if not isinstance(end.get('class'), str) or not isinstance(end.get('on'), str):
            raise ValueError(f"an association's end names its class and what it is joined on: {dict(end)!r}")
        return {k: end[k] for k in ('class', 'on', 'name', 'multiplicity', 'unique') if k in end}
    cls, on = end
    return {'class': cls, 'on': on}
