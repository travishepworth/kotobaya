"""The media's current published rows: the base a media job builds on (SNAPSHOT.md 3.4, MEDIA_PIPELINE.md 3.2).

Read-only. Unlike ``media.publish.ids.PgBaseReader`` this reads whole rows (tokens, timings, blobs, location),
because an archive is complete: an append import carries the existing segments verbatim and a re-parse keeps
everything but the tokens.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from typing import Any, Protocol

import psycopg
from psycopg.rows import tuple_row

from kotobaya_worker.grammar.detect import PointRules
from kotobaya_worker.media.publish.ids import ExistingContent, ExistingSegment, ExistingSentence
from kotobaya_worker.media.publish.snapshot import GrammarSpanRec


@dataclass(frozen=True)
class BaseSentence:
    id: str
    ordinal: int
    text: str
    tokens: list[Any]
    origin: str = "media"
    start_ms: int | None = None
    end_ms: int | None = None
    audio_blob_hash: str | None = None
    image_blob_hash: str | None = None
    location: dict[str, Any] | None = None


@dataclass(frozen=True)
class BaseSegment:
    id: str
    ordinal: int
    kind: str
    title: str
    sentences: tuple[BaseSentence, ...]  # ascending ordinal


@dataclass(frozen=True)
class MediaBase:
    """``exists`` false = no media row (a new media); then everything else is empty / 0."""

    exists: bool
    content_version: int = 0
    source: str | None = None
    live: bool = True
    segments: tuple[BaseSegment, ...] = ()  # ascending ordinal
    # Read in the same snapshot (SCHEMA.md 4.4 grammar-detect, C-13): every publish re-detects spans.
    grammar_rules: tuple[PointRules, ...] = ()  # live points with non-empty detection_rules
    detected_spans: frozenset[GrammarSpanRec] = frozenset()  # installed origin = 'detected' rows

    def existing(self) -> ExistingContent:
        """The id-stability view (``media.publish.ids.assign_ids``)."""
        return ExistingContent(
            self.content_version,
            [ExistingSegment(s.id, s.ordinal) for s in self.segments],
            [
                ExistingSentence(t.id, s.ordinal, t.ordinal, t.text)
                for s in self.segments
                for t in s.sentences
            ],
        )


NO_MEDIA = MediaBase(exists=False)


class BaseReader(Protocol):
    def read(self, media_id: str) -> MediaBase: ...


class StaticBaseReader:
    """A fixed base (tests, offline use)."""

    def __init__(self, base: MediaBase = NO_MEDIA) -> None:
        self.base = base
        self.reads = 0

    def read(self, media_id: str) -> MediaBase:
        del media_id
        self.reads += 1
        return self.base


_SENTENCES_SQL = """
select s.id, seg.ordinal, s.ordinal, s.text, s.tokens, s.origin, s.start_ms, s.end_ms,
       s.audio_blob_hash, s.image_blob_hash, s.location
from app.sentence s join app.media_segment seg on seg.id = s.segment_id
where s.media_id = %s
order by seg.ordinal, s.ordinal
"""
_RULES_SQL = (
    "select id, detection_rules from app.grammar_point "
    "where deleted_at is null and detection_rules <> '[]'::jsonb order by id"
)
_SPANS_SQL = (
    "select sentence_id, grammar_point_id, start_offset, end_offset, rule_index "
    "from app.sentence_grammar_span where media_id = %s and origin = 'detected'"
)


class PgMediaReader:
    """One REPEATABLE READ snapshot of the media row and its segments and sentences (conn: autocommit)."""

    def __init__(self, conn: psycopg.Connection[Any]) -> None:
        self._c = conn

    def read(self, media_id: str) -> MediaBase:
        mid = uuid.UUID(media_id)
        with self._c.transaction(), self._c.cursor(row_factory=tuple_row) as cur:
            cur.execute("set transaction isolation level repeatable read, read only")
            rules = tuple(PointRules(str(r[0]), r[1]) for r in cur.execute(_RULES_SQL).fetchall())
            m = cur.execute(
                "select content_version, source, deleted_at is null from app.media where id = %s",
                (mid,),
            ).fetchone()
            if m is None:
                return MediaBase(exists=False, grammar_rules=rules)
            segs = cur.execute(
                "select id, ordinal, kind, title from app.media_segment where media_id = %s "
                "order by ordinal",
                (mid,),
            ).fetchall()
            sents = cur.execute(_SENTENCES_SQL, (mid,)).fetchall()
            spans = frozenset(
                GrammarSpanRec(str(r[0]), str(r[1]), r[2], r[3], r[4])
                for r in cur.execute(_SPANS_SQL, (mid,)).fetchall()
            )
        by_seg: dict[int, list[BaseSentence]] = {}
        for r in sents:
            by_seg.setdefault(r[1], []).append(
                BaseSentence(
                    id=str(r[0]),
                    ordinal=r[2],
                    text=r[3],
                    tokens=list(r[4]),
                    origin=r[5],
                    start_ms=r[6],
                    end_ms=r[7],
                    audio_blob_hash=r[8],
                    image_blob_hash=r[9],
                    location=r[10],
                )
            )
        return MediaBase(
            exists=True,
            content_version=int(m[0]),
            source=str(m[1]),
            live=bool(m[2]),
            segments=tuple(
                BaseSegment(str(r[0]), r[1], r[2], r[3], tuple(by_seg.get(r[1], ()))) for r in segs
            ),
            grammar_rules=rules,
            detected_spans=spans,
        )
