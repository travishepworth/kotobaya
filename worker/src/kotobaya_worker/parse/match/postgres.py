"""Postgres-backed :class:`MatchLookup` over ``ref.dict_entry`` / ``ref.dict_entry_form`` /
``ref.entry_freq``.

Retired entries (``retired_release_id`` set) are not in the current release, so they are
never candidates and their ``entry`` is None (PARSER.md 2: ``E`` = the empty set).
Frequency is the entry's rank in the first configured list (lowest ``ref.freq_list.priority``).

Results are cached in a :class:`MatchCache`, which a caller may share between lookups (and
threads) as long as they read the same dictionary release.
"""

from __future__ import annotations

import threading
from collections.abc import Sequence
from typing import Any

import psycopg

from kotobaya_worker.parse.match import EntryInfo, FormHit

_FORMS_SQL = """
select f.seq, bool_or(f.kind = 'kanji'), bool_or(f.common)
from ref.dict_entry_form f
join ref.dict_entry e on e.seq = f.seq
where f.norm = %s and e.retired_release_id is null
group by f.seq
order by f.seq
"""

_ENTRY_SQL = """
select e.pos,
       coalesce(array(select f.norm from ref.dict_entry_form f
                      where f.seq = e.seq and f.kind = 'reading'), '{}'),
       jsonb_array_length(e.kanji_forms) = 0 or 'uk' = any(e.misc),
       (select ef.rank from ref.entry_freq ef
         where ef.seq = e.seq
           and ef.list_id = (select id from ref.freq_list order by priority limit 1))
from ref.dict_entry e
where e.seq = %s and e.retired_release_id is null
"""

_CURRENT_RELEASE_SQL = "select version from ref.dict_release where is_current"


class MatchCache:
    """Thread-safe memo of form and entry lookups for one dictionary release."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.forms: dict[str, tuple[FormHit, ...]] = {}
        self.entries: dict[int, EntryInfo | None] = {}


class PostgresMatchLookup:
    def __init__(self, conn: psycopg.Connection[Any], cache: MatchCache | None = None) -> None:
        self._conn = conn
        self._cache = cache if cache is not None else MatchCache()

    def forms(self, norm: str) -> Sequence[FormHit]:
        if not norm:
            return ()
        with self._cache.lock:
            hit = self._cache.forms.get(norm)
        if hit is not None:
            return hit
        rows = self._conn.execute(_FORMS_SQL, (norm,)).fetchall()
        out = tuple(
            FormHit(
                int(r[0]),
                "kanji" if r[1] else "reading",
                bool(r[2]),
            )
            for r in rows
        )
        with self._cache.lock:
            self._cache.forms[norm] = out
        return out

    def entry(self, seq: int) -> EntryInfo | None:
        with self._cache.lock:
            if seq in self._cache.entries:
                return self._cache.entries[seq]
        row = self._conn.execute(_ENTRY_SQL, (seq,)).fetchone()
        info = (
            None
            if row is None
            else EntryInfo(
                seq=seq,
                pos=frozenset(row[0] or ()),
                readings=frozenset(row[1] or ()),
                kana_usual=bool(row[2]),
                freq_rank=None if row[3] is None else int(row[3]),
            )
        )
        with self._cache.lock:
            self._cache.entries[seq] = info
        return info

    def is_key(self, norm: str) -> bool:
        return len(self.forms(norm)) > 0


def current_release(conn: psycopg.Connection[Any]) -> str | None:
    """``ref.dict_release.version`` of the current release, or None before the first load."""
    row = conn.execute(_CURRENT_RELEASE_SQL).fetchone()
    return None if row is None else str(row[0])
