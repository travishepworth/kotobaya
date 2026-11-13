"""Tokens -> occurrences and segment counts (SPEC Parsing step 5; SNAPSHOT.md 3.3 rules 6-7, 4.2, 5).

Pure. Per segment, over its sentences with ``origin = 'media'`` (generated sentences never count):

- one occurrence per distinct non-null token ``seq``: ``count`` = tokens with that seq, ``pos_class`` = the most
  frequent token ``class`` (ties: PARSER.md 2.1 order), ``sentence_ids`` = the first 20 sentences by ordinal
  that contain it;
- ``token_count`` = all tokens, ``unmapped_token_count`` = tokens with ``seq`` null, so
  ``token_count = sum(count) + unmapped_token_count``.

Deck items: distinct entries over all segments, ``occurrence_count`` summed, ranked by it (ties by seq).
"""

from __future__ import annotations

from collections import Counter
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass

from kotobaya_worker.media.publish.handoff import JobResultMedia
from kotobaya_worker.media.publish.snapshot import DeckItemRec, OccurrenceRec, media_deck_items
from kotobaya_worker.parse.match import CLASSES

MAX_SENTENCE_IDS = 20


@dataclass(frozen=True)
class SentenceTokens:
    """One sentence of a segment: its id, ordinal, C-12 token array (JSON objects) and origin."""

    id: str
    ordinal: int
    tokens: Sequence[Mapping[str, object]]
    origin: str = "media"


@dataclass(frozen=True)
class SegmentCounts:
    segment_id: str
    sentence_count: int
    token_count: int
    unmapped_token_count: int
    occurrences: tuple[OccurrenceRec, ...]  # ascending entry_seq


def pos_class(classes: Iterable[str]) -> str:
    """Most frequent class; ties go to the first in ``content > name > particle > auxiliary > other``."""
    counts = Counter(classes)
    if not counts:
        raise ValueError("pos_class of no tokens")
    unknown = set(counts) - set(CLASSES)
    if unknown:
        raise ValueError(f"unknown token class {sorted(unknown)!r}")
    return max(CLASSES, key=lambda c: (counts[c], -CLASSES.index(c)))


def _seq(token: Mapping[str, object]) -> int | None:
    seq = token.get("seq")
    if seq is None:
        return None
    if not isinstance(seq, int) or isinstance(seq, bool) or seq < 1:
        raise ValueError(f"token seq must be null or a positive integer, got {seq!r}")
    return seq


def aggregate_segment(segment_id: str, sentences: Sequence[SentenceTokens]) -> SegmentCounts:
    ordered = sorted(sentences, key=lambda s: s.ordinal)
    if len({s.ordinal for s in ordered}) != len(ordered):
        raise ValueError(f"segment {segment_id}: duplicate sentence ordinal")
    tokens = unmapped = 0
    count: Counter[int] = Counter()
    classes: dict[int, list[str]] = {}
    where: dict[int, list[str]] = {}
    for sent in ordered:
        if sent.origin != "media":
            continue
        for t in sent.tokens:
            tokens += 1
            seq = _seq(t)
            if seq is None:
                unmapped += 1
                continue
            count[seq] += 1
            classes.setdefault(seq, []).append(str(t.get("class")))
            ids = where.setdefault(seq, [])
            if len(ids) < MAX_SENTENCE_IDS and (not ids or ids[-1] != sent.id):
                ids.append(sent.id)
    occs = tuple(
        OccurrenceRec(segment_id, seq, count[seq], pos_class(classes[seq]), tuple(where[seq]))
        for seq in sorted(count)
    )
    return SegmentCounts(segment_id, len(ordered), tokens, unmapped, occs)


def deck_items(segments: Iterable[SegmentCounts]) -> list[DeckItemRec]:
    """Media-deck items: positions 1..n by in-deck frequency, ties by entry_seq (SNAPSHOT.md 4.2)."""
    return media_deck_items(o for s in segments for o in s.occurrences)


def job_result(
    media_id: str, content_version: int, segments: Sequence[SegmentCounts]
) -> JobResultMedia:
    """The ``JobResultMedia`` the server's runner reports for these archives (MEDIA_PIPELINE.md 3.3 step 10)."""
    return JobResultMedia(
        media_id=media_id,
        content_version=content_version,
        segments=len(segments),
        sentences=sum(s.sentence_count for s in segments),
        tokens=sum(s.token_count for s in segments),
        unmapped_tokens=sum(s.unmapped_token_count for s in segments),
        unique_entries=len({o.entry_seq for s in segments for o in s.occurrences}),
        unmapped=[],
    )
