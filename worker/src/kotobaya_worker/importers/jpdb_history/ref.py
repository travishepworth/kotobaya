"""Read-only JMdict lookup used by the jpdb mapper (``ref.dict_entry`` / ``ref.dict_entry_form``).

Forms are compared by ``normalize_form`` (NFKC + katakana folded to hiragana), the same key as
``ref.dict_entry_form.norm``, so the in-memory and Postgres lookups agree.
"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Iterable
from typing import Any, Protocol

import psycopg

from kotobaya_worker.dict.models import Entry
from kotobaya_worker.dict.normalize import normalize_form


def norm(text: str) -> str:
    """The mapper's text key: ``normalize_form`` of the stripped text (S02 ``norm``)."""
    return normalize_form(text.strip()).strip()


class RefLookup(Protocol):
    """What the mapper needs from the dictionary. All arguments are already ``norm``-alised."""

    def has_seq(self, seq: int) -> bool: ...

    def seqs_with_kanji(self, form: str) -> frozenset[int]: ...

    def seqs_with_reading(self, form: str) -> frozenset[int]: ...

    def usually_kana(self, seq: int) -> bool: ...


class InMemoryRefLookup:
    """Dictionary held in memory: tests (JMdict subset) and small runs."""

    def __init__(self) -> None:
        self._seqs: set[int] = set()
        self._uk: set[int] = set()
        self._kanji: dict[str, set[int]] = defaultdict(set)
        self._reading: dict[str, set[int]] = defaultdict(set)

    @staticmethod
    def from_entries(entries: Iterable[Entry]) -> InMemoryRefLookup:
        ref = InMemoryRefLookup()
        for e in entries:
            ref.add(
                e.seq,
                kanji=[k.text for k in e.kanji],
                readings=[r.text for r in e.readings],
                usually_kana=any("uk" in s.misc for s in e.senses),
            )
        return ref

    def add(
        self, seq: int, *, kanji: Iterable[str], readings: Iterable[str], usually_kana: bool = False
    ) -> None:
        self._seqs.add(seq)
        if usually_kana:
            self._uk.add(seq)
        for k in kanji:
            self._kanji[norm(k)].add(seq)
        for r in readings:
            self._reading[norm(r)].add(seq)

    def has_seq(self, seq: int) -> bool:
        return seq in self._seqs

    def seqs_with_kanji(self, form: str) -> frozenset[int]:
        return frozenset(self._kanji.get(form, ()))

    def seqs_with_reading(self, form: str) -> frozenset[int]:
        return frozenset(self._reading.get(form, ()))

    def usually_kana(self, seq: int) -> bool:
        return seq in self._uk


class PostgresRefLookup:
    """Read-only lookup over ``ref.dict_entry`` / ``ref.dict_entry_form``, cached per instance.

    Retired entries are kept (rows are never deleted; soft references keep resolving).
    """

    def __init__(self, conn: psycopg.Connection[Any]) -> None:
        self._conn = conn
        self._entry: dict[int, bool | None] = {}  # seq -> usually_kana, None = no such seq
        self._forms: dict[tuple[str, str], frozenset[int]] = {}

    def _lookup_entry(self, seq: int) -> bool | None:
        if seq not in self._entry:
            row = self._conn.execute(
                "select 'uk' = any(misc) from ref.dict_entry where seq = %s", (seq,)
            ).fetchone()
            self._entry[seq] = None if row is None else bool(row[0])
        return self._entry[seq]

    def has_seq(self, seq: int) -> bool:
        return self._lookup_entry(seq) is not None

    def usually_kana(self, seq: int) -> bool:
        return self._lookup_entry(seq) is True

    def _seqs(self, kind: str, form: str) -> frozenset[int]:
        if not form:
            return frozenset()
        key = (kind, form)
        if key not in self._forms:
            rows = self._conn.execute(
                "select distinct seq from ref.dict_entry_form where kind = %s and norm = %s",
                key,
            ).fetchall()
            self._forms[key] = frozenset(int(r[0]) for r in rows)
        return self._forms[key]

    def seqs_with_kanji(self, form: str) -> frozenset[int]:
        return self._seqs("kanji", form)

    def seqs_with_reading(self, form: str) -> frozenset[int]:
        return self._seqs("reading", form)
