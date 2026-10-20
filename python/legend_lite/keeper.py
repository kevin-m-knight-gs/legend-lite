"""ONE KEEPER PER PAGE (docs/DATACUBE_PAGE_KEEPER_2026_10_20.md): the one copy of each page a session's Python pages
hold, shown or not, and of each one's version. Either editor -- Python's page, a tab or a notebook widget open on it --
writes a page only against the version it is at: a write against an older one is refused (``Stale``), and its writer
re-applies its commands on top of the page as it is now, or opens it again. So the two never hold two pages.

Each version keeps the commands that moved the page to it (a page's, or a grid's with its tile's id), or none -- a change
by no command -- so a page open at an older version follows by the commands since, or opens the page again. A write
that leaves the page as it was is no new version.

The keeper also says which copies (Snap: docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §18.8) the pages it keeps read: a copy
lives while some page reads it (engine.py ``sweep``).
"""

from __future__ import annotations

import copy
import threading
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from typing import Any

# how many versions back a page keeps the commands of: a page open on an older one opens the page again
STEPS_KEPT = 64


class Stale(Exception):
    """A write against a version that is no longer the page's: ``version`` is the page's now."""

    def __init__(self, version: int) -> None:
        super().__init__(f'the page is at version {version}')
        self.version = version


@dataclass
class _Kept:
    version: int
    doc: dict[str, Any]
    frames: list[str]
    # the commands that moved the page to each version kept, or None: moved by no command
    steps: dict[int, list[Any] | None] = field(default_factory=dict)
    # shown somewhere (a tab, a notebook widget): what an open page may read and write
    served: bool = False


class PageKeeper:
    """The pages a session keeps, by their keys (each Python page's own: ``Page._key``)."""

    def __init__(self, on_write: Callable[[str, int], None] | None = None) -> None:
        self._lock = threading.Lock()
        self._pages: dict[str, _Kept] = {}
        # told each new version (its page's key, the version), outside the lock: where the page is shown follows it
        self.on_write = on_write

    def keep(self, key: str, doc: dict[str, Any], frames: Iterable[str]) -> int:
        """A page kept from now on (a new Python page), at version 1."""
        with self._lock:
            self._pages[key] = _Kept(1, copy.deepcopy(doc), sorted(set(frames)), {1: None})
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
        """The frames the page reads (its cubes' leaves'), as its last write said, or None."""
        with self._lock:
            kept = self._pages.get(key)
            return None if kept is None else list(kept.frames)

    def write(self, key: str, base: int, doc: dict[str, Any], frames: Iterable[str], commands: list[Any] | None) -> int:
        """The page made ``doc`` (reading ``frames``), moved there from version ``base`` by ``commands`` (None: by no
        command): its new version -- or, the page no longer at ``base``, ``Stale``. A document the page has already is
        no new version. A page not kept is a ``KeyError``."""
        kept_doc = copy.deepcopy(doc)
        kept_commands = None if commands is None else copy.deepcopy(commands)
        kept_frames = sorted(set(frames))
        with self._lock:
            kept = self._pages[key]
            if kept.version != base:
                raise Stale(kept.version)
            if kept_doc == kept.doc and kept_frames == kept.frames:
                return kept.version
            kept.version += 1
            kept.doc = kept_doc
            kept.frames = kept_frames
            kept.steps[kept.version] = kept_commands
            for old in [v for v in kept.steps if v <= kept.version - STEPS_KEPT]:
                del kept.steps[old]
            version = kept.version
        if self.on_write is not None:
            self.on_write(key, version)
        return version

    def changes(self, key: str, since: int) -> tuple[int, list[Any] | None]:
        """What moved the page since version ``since``: its version now, and the commands since, in order -- or None,
        when a version since was moved by no command or is no longer kept (the page is then opened again)."""
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
        """The copies the pages kept read, by table."""
        with self._lock:
            return {c['table'] for kept in self._pages.values() for c in kept.doc.get('copies', {}).values()}
