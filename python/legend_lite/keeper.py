"""ONE KEEPER PER PAGE (docs/DATACUBE_PAGE_KEEPER_2026_10_20.md): the one copy of each page a session's Python pages
hold, shown or not, and its version -- the host's storage, which never reads a page: what a page reads (its frames, its
copies, its ids) comes with each write from DataCube's own code (headless pageReads, pageWritten, pageRebase), and so
does every rule of how two editors' edits combine (page-rebase.ts).

Either editor -- Python's page, a tab or a notebook widget open on it -- writes a page against the version it is at, with
the edits that made it (``settle``). Written against an older one, the keeper has DataCube make the writer's edits again
on top of the page as it is now (``combine``: headless pageRebase) and keeps what that makes; a change by no command, or
edits that no longer apply, are refused (``Refused``). So the two never hold two pages, and neither keeps a rule of its
own for combining them.

Each version keeps the edits that moved the page to it (a page's, or a grid's with its tile's id), or none -- a change
by no command -- so a page open at an older version follows by the edits since, or opens the page again. A write that
leaves the page as it was is no new version.
"""

from __future__ import annotations

import copy
import threading
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from typing import Any

# how many versions back a page keeps the edits of: a page open on an older one opens the page again
STEPS_KEPT = 64
# how many times a write is made again on top of a page that keeps moving before it is refused (each makes it again on
# the page as it is then: only a writer that keeps losing the race to another meets it)
COMBINED_AT_MOST = 64


class Stale(Exception):
    """A write against a version that is no longer the page's: ``version`` is the page's now."""

    def __init__(self, version: int) -> None:
        super().__init__(f'the page is at version {version}')
        self.version = version


class Refused(Exception):
    """A write the page cannot take: made against a version it moved on from, and a change by no command, or edits that
    no longer apply there (DataCube's words)."""


@dataclass(frozen=True)
class Settled:
    """A write kept: the page's version now; whether its edits were made again on top of a page that had moved on (the
    writer then takes the page as the keeper has it); and each id they gave out that was given out afresh."""
    version: int
    combined: bool
    renamed: Mapping[str, str]


@dataclass
class _Kept:
    version: int
    doc: dict[str, Any]
    # what the page reads, as DataCube said with its last write: {frames, copies, ids}
    reads: dict[str, list[str]]
    # every id the page has held: none is given out again
    held: list[str]
    # the edits that moved the page to each version kept, or None: moved by no command
    steps: dict[int, list[Any] | None] = field(default_factory=dict)
    # shown somewhere (a tab, a notebook widget): what an open page may read and write
    served: bool = False


# combine(page, held, edits): DataCube's pageRebase as the host asks it -- {page, held, edits, renamed, reads}, or
# {problems}
Combine = Callable[[dict[str, Any], list[str], list[Any]], dict[str, Any]]


class PageKeeper:
    """The pages a session keeps, by their keys (each Python page's own: ``Page._key``)."""

    def __init__(self, on_write: Callable[[str, int], None] | None = None, combine: Combine | None = None) -> None:
        self._lock = threading.Lock()
        self._pages: dict[str, _Kept] = {}
        # told each new version (its page's key, the version), outside the lock: where the page is shown follows it
        self.on_write = on_write
        # how a write made against an older version is made again on top (asked outside the lock: DataCube's, headless)
        self.combine = combine

    def keep(self, key: str, doc: dict[str, Any], reads: Mapping[str, list[str]], held: list[str]) -> int:
        """A page kept from now on (a new Python page), at version 1."""
        with self._lock:
            self._pages[key] = _Kept(1, copy.deepcopy(doc), _reads(reads), sorted(set(held)), {1: None})
        return 1

    def forget(self, key: str) -> None:
        """A page no longer kept (its Python page collected)."""
        with self._lock:
            self._pages.pop(key, None)

    def current(self, key: str) -> tuple[int, dict[str, Any]] | None:
        """The page's version and its document -- the keeper's own: read it, never change it -- or None."""
        with self._lock:
            kept = self._pages.get(key)
            return None if kept is None else (kept.version, kept.doc)

    def frames(self, key: str) -> list[str] | None:
        """The frames the page reads, as DataCube said with its last write, or None."""
        with self._lock:
            kept = self._pages.get(key)
            return None if kept is None else list(kept.reads['frames'])

    def held(self, key: str) -> list[str]:
        """Every id the page has held."""
        with self._lock:
            kept = self._pages.get(key)
            return [] if kept is None else list(kept.held)

    def write(self, key: str, base: int, doc: dict[str, Any], reads: Mapping[str, list[str]], edits: list[Any] | None,
              held: list[str]) -> int:
        """The page made ``doc`` (reading what ``reads`` says), moved there from version ``base`` by ``edits`` (None: by no
        command): its new version -- or, the page no longer at ``base``, ``Stale``. A document the page has already is no
        new version. A page not kept is a ``KeyError``."""
        kept_doc = copy.deepcopy(doc)
        kept_edits = None if edits is None else copy.deepcopy(edits)
        kept_reads = _reads(reads)
        with self._lock:
            kept = self._pages[key]
            if kept.version != base:
                raise Stale(kept.version)
            kept.held = sorted(set(kept.held) | set(held) | set(kept_reads['ids']))
            if kept_doc == kept.doc and kept_reads == kept.reads:
                return kept.version
            kept.version += 1
            kept.doc = kept_doc
            kept.reads = kept_reads
            kept.steps[kept.version] = kept_edits
            for old in [v for v in kept.steps if v <= kept.version - STEPS_KEPT]:
                del kept.steps[old]
            version = kept.version
        if self.on_write is not None:
            self.on_write(key, version)
        return version

    def settle(self, key: str, base: int, doc: dict[str, Any], edits: list[Any] | None, reads: Mapping[str, list[str]],
               held: list[str]) -> Settled:
        """A WRITE, SETTLED (docs/DATACUBE_PAGE_KEEPER_2026_10_20.md): ``doc``, made by ``edits`` from version ``base``,
        kept as written while the page is still at ``base``; else the edits made again on top of the page as it is now
        (``combine``: DataCube's pageRebase) and what that makes kept -- or ``Refused``: a change by no command, or edits
        that no longer apply there."""
        try:
            return Settled(self.write(key, base, doc, reads, edits, held), False, {})
        except Stale:
            pass
        if edits is None:
            raise Refused('the page changed where it is open while this change was made, which is no command to make '
                          'again on it: make the change again')
        if self.combine is None:
            raise Refused('the page changed where it is open while this change was made')
        for _ in range(COMBINED_AT_MOST):
            with self._lock:
                kept = self._pages[key]
                at, now, now_held = kept.version, kept.doc, list(kept.held)
            said = self.combine(now, now_held, edits)
            if 'problems' in said:
                raise Refused('the page changed where it is open while this change was made: ' + '; '.join(said['problems']))
            try:
                version = self.write(key, at, said['page'], said['reads'], said['edits'], said['held'])
            except Stale:
                continue
            return Settled(version, True, dict(said['renamed']))
        raise Refused('the page kept changing where it is open while this change was made: make it again')

    def changes(self, key: str, since: int) -> tuple[int, list[Any] | None]:
        """What moved the page since version ``since``: its version now, and the edits since, in order -- or None, when
        a version since was moved by no command or is no longer kept (the page is then opened again)."""
        with self._lock:
            kept = self._pages[key]
            if since > kept.version:
                return kept.version, None
            moved = [kept.steps.get(v) for v in range(since + 1, kept.version + 1)]
            if any(m is None for m in moved):
                return kept.version, None
            return kept.version, [c for m in moved for c in m or []]

    def serve(self, key: str, served: bool) -> None:
        """The page shown somewhere, or shown nowhere any more."""
        with self._lock:
            kept = self._pages.get(key)
            if kept is not None:
                kept.served = served

    def served(self, key: str) -> bool:
        with self._lock:
            kept = self._pages.get(key)
            return kept is not None and kept.served

    def referenced(self) -> set[str]:
        """The copies the pages kept read, by table, as DataCube said with each one's last write."""
        with self._lock:
            return {t for kept in self._pages.values() for t in kept.reads['copies']}


def _reads(reads: Mapping[str, list[str]]) -> dict[str, list[str]]:
    """What a page reads, as kept: its frames, its copies and its ids, each a sorted list."""
    return {part: sorted(set(reads.get(part, []))) for part in ('frames', 'copies', 'ids')}
