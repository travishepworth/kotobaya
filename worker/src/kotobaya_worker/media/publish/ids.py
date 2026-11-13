"""Id stability across versions of one media (contracts/db/SNAPSHOT.md 3.4).

Segment id: the base version's id for the same ordinal. Sentence id: the base id of the sentence with the same
segment ordinal, the same sentence ordinal and byte-identical text. Anything else gets a fresh UUIDv7. Ids are
never taken from another media: ``existing`` is read for this media only.
"""

from __future__ import annotations

import uuid
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from typing import Any

import psycopg
from psycopg.rows import tuple_row

from kotobaya_worker.importers.jpdb_history.apply import uuid7


@dataclass(frozen=True)
class ExistingSegment:
    id: str
    ordinal: int


@dataclass(frozen=True)
class ExistingSentence:
    id: str
    segment_ordinal: int
    ordinal: int
    text: str


@dataclass(frozen=True)
class ExistingContent:
    content_version: int
    segments: Sequence[ExistingSegment] = ()
    sentences: Sequence[ExistingSentence] = ()


@dataclass(frozen=True)
class NewSegment:
    ordinal: int
    sentence_texts: Sequence[str]


@dataclass(frozen=True)
class AssignedIds:
    segment_ids: dict[int, str]  # segment ordinal -> id
    sentence_ids: dict[tuple[int, int], str]  # (segment ordinal, sentence ordinal) -> id


def _mint() -> str:
    return str(uuid7())


def assign_ids(
    media_id: str,
    existing: ExistingContent,
    new_segments: Sequence[NewSegment],
    mint: Callable[[], str] = _mint,
) -> AssignedIds:
    """Ids for the new version. ``existing`` must be rows of ``media_id`` only (the reader guarantees it)."""
    del media_id
    seg_by_ord = {s.ordinal: s.id for s in existing.segments}
    sent_by_key = {(s.segment_ordinal, s.ordinal, s.text): s.id for s in existing.sentences}
    segment_ids: dict[int, str] = {}
    sentence_ids: dict[tuple[int, int], str] = {}
    used: set[str] = set()

    def take(candidate: str | None) -> str:
        if candidate is not None and candidate not in used:
            used.add(candidate)
            return candidate
        fresh = mint()
        while fresh in used:
            fresh = mint()
        used.add(fresh)
        return fresh

    for seg in new_segments:
        segment_ids[seg.ordinal] = take(seg_by_ord.get(seg.ordinal))
        for i, text in enumerate(seg.sentence_texts):
            sentence_ids[(seg.ordinal, i)] = take(sent_by_key.get((seg.ordinal, i, text)))
    return AssignedIds(segment_ids, sentence_ids)


class PgBaseReader:
    """Read-only: the media's current rows in one REPEATABLE READ snapshot (conn must be autocommit)."""

    def __init__(self, conn: psycopg.Connection[Any]) -> None:
        self._c = conn

    def read(self, media_id: uuid.UUID | str) -> ExistingContent:
        mid = uuid.UUID(str(media_id))
        with self._c.transaction(), self._c.cursor(row_factory=tuple_row) as cur:
            cur.execute("set transaction isolation level repeatable read")
            m = cur.execute(
                "select content_version from app.media where id = %s", (mid,)
            ).fetchone()
            if m is None:
                return ExistingContent(0)
            segs = cur.execute(
                "select id, ordinal from app.media_segment where media_id = %s order by ordinal",
                (mid,),
            ).fetchall()
            ord_of = {r[0]: r[1] for r in segs}
            sents = cur.execute(
                "select id, segment_id, ordinal, text from app.sentence where media_id = %s "
                "order by ordinal",
                (mid,),
            ).fetchall()
        return ExistingContent(
            int(m[0]),
            [ExistingSegment(str(r[0]), r[1]) for r in segs],
            sorted(
                (ExistingSentence(str(r[0]), ord_of[r[1]], r[2], r[3]) for r in sents),
                key=lambda x: (x.segment_ordinal, x.ordinal),
            ),
        )
