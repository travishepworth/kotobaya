"""Writers and readers for the C-11 snapshot archives (contracts/db/SNAPSHOT.md 2-4).

Bytes are deterministic: records in the order of SNAPSHOT.md 3.2 / 4.2, compact JSON with sorted members,
one fixed zstd level, so identical input gives an identical blob hash.
"""

from __future__ import annotations

import io
import json
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any

import zstandard

CONTENT_FORMAT = "kotobaya-media-content"
DECK_FORMAT = "kotobaya-deck-items"
FORMAT_VERSION = 1
ZSTD_LEVEL = 3  # fixed: the same input must give the same bytes


@dataclass(frozen=True)
class SegmentRec:
    id: str
    ordinal: int
    kind: str
    title: str
    sentence_count: int
    token_count: int
    unmapped_token_count: int


@dataclass(frozen=True)
class SentenceRec:
    id: str
    segment_id: str
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
class OccurrenceRec:
    segment_id: str
    entry_seq: int
    count: int
    pos_class: str
    sentence_ids: tuple[str, ...] = ()


@dataclass(frozen=True)
class GrammarSpanRec:
    sentence_id: str
    grammar_point_id: str
    start_offset: int
    end_offset: int
    rule_index: int


@dataclass(frozen=True)
class DeckItemRec:
    entry_seq: int
    position: int
    occurrence_count: int


@dataclass(frozen=True)
class ContentArchive:
    media_id: str
    content_version: int
    segments: Sequence[SegmentRec]
    sentences: Sequence[SentenceRec] = ()
    occurrences: Sequence[OccurrenceRec] = ()
    grammar_spans: Sequence[GrammarSpanRec] = field(default_factory=tuple)


@dataclass(frozen=True)
class DeckArchive:
    deck_id: str
    deck_type: str
    content_version: int
    items: Sequence[DeckItemRec]


class SnapshotError(ValueError):
    pass


def _line(obj: Mapping[str, Any]) -> str:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n"


def _compress(text: str) -> bytes:
    return zstandard.ZstdCompressor(level=ZSTD_LEVEL, threads=0).compress(text.encode("utf-8"))


def content_lines(a: ContentArchive) -> list[str]:
    seg_ord = {s.id: s.ordinal for s in a.segments}
    segments = sorted(a.segments, key=lambda s: s.ordinal)
    sentences = sorted(a.sentences, key=lambda s: (seg_ord[s.segment_id], s.ordinal))
    position = {s.id: i for i, s in enumerate(sentences)}
    occurrences = sorted(a.occurrences, key=lambda o: (seg_ord[o.segment_id], o.entry_seq))
    spans = sorted(
        a.grammar_spans,
        key=lambda g: (position[g.sentence_id], g.start_offset, g.end_offset, g.grammar_point_id),
    )
    out = [
        _line(
            {
                "record": "header",
                "format": CONTENT_FORMAT,
                "format_version": FORMAT_VERSION,
                "media_id": a.media_id,
                "content_version": a.content_version,
                "counts": {
                    "segment": len(segments),
                    "sentence": len(sentences),
                    "occurrence": len(occurrences),
                    "grammar_span": len(spans),
                },
            }
        )
    ]
    for s in segments:
        out.append(
            _line(
                {
                    "record": "segment",
                    "id": s.id,
                    "ordinal": s.ordinal,
                    "kind": s.kind,
                    "title": s.title[:300],
                    "sentence_count": s.sentence_count,
                    "token_count": s.token_count,
                    "unmapped_token_count": s.unmapped_token_count,
                }
            )
        )
    for t in sentences:
        out.append(
            _line(
                {
                    "record": "sentence",
                    "id": t.id,
                    "segment_id": t.segment_id,
                    "ordinal": t.ordinal,
                    "text": t.text,
                    "tokens": t.tokens,
                    "origin": t.origin,
                    "start_ms": t.start_ms,
                    "end_ms": t.end_ms,
                    "audio_blob_hash": t.audio_blob_hash,
                    "image_blob_hash": t.image_blob_hash,
                    "location": t.location,
                }
            )
        )
    for o in occurrences:
        out.append(
            _line(
                {
                    "record": "occurrence",
                    "segment_id": o.segment_id,
                    "entry_seq": o.entry_seq,
                    "count": o.count,
                    "pos_class": o.pos_class,
                    "sentence_ids": list(o.sentence_ids),
                }
            )
        )
    for g in spans:
        out.append(
            _line(
                {
                    "record": "grammar_span",
                    "sentence_id": g.sentence_id,
                    "grammar_point_id": g.grammar_point_id,
                    "start_offset": g.start_offset,
                    "end_offset": g.end_offset,
                    "rule_index": g.rule_index,
                }
            )
        )
    return out


def write_content(a: ContentArchive) -> bytes:
    """The compressed ``kotobaya-media-content`` archive."""
    return _compress("".join(content_lines(a)))


def deck_lines(a: DeckArchive) -> list[str]:
    items = sorted(a.items, key=lambda i: (i.position, i.entry_seq))
    out = [
        _line(
            {
                "record": "header",
                "format": DECK_FORMAT,
                "format_version": FORMAT_VERSION,
                "deck_id": a.deck_id,
                "deck_type": a.deck_type,
                "content_version": a.content_version,
                "counts": {"item": len(items)},
            }
        )
    ]
    for i in items:
        out.append(
            _line(
                {
                    "record": "item",
                    "entry_seq": i.entry_seq,
                    "position": i.position,
                    "occurrence_count": i.occurrence_count,
                }
            )
        )
    return out


def write_deck_items(a: DeckArchive) -> bytes:
    """The compressed ``kotobaya-deck-items`` archive."""
    return _compress("".join(deck_lines(a)))


def media_deck_items(occurrences: Iterable[OccurrenceRec]) -> list[DeckItemRec]:
    """Media-deck items (SNAPSHOT.md 4.2, 5): distinct entries ranked by total count, ties by entry_seq."""
    totals: dict[int, int] = {}
    for o in occurrences:
        totals[o.entry_seq] = totals.get(o.entry_seq, 0) + o.count
    ranked = sorted(totals.items(), key=lambda kv: (-kv[1], kv[0]))
    return [DeckItemRec(seq, i + 1, n) for i, (seq, n) in enumerate(ranked)]


def _records(blob: bytes) -> list[dict[str, Any]]:
    reader = zstandard.ZstdDecompressor().stream_reader(io.BytesIO(blob), read_across_frames=True)
    text = io.TextIOWrapper(reader, encoding="utf-8", newline="\n")
    out: list[dict[str, Any]] = []
    for raw in text:
        if not raw.endswith("\n"):
            raise SnapshotError("last line has no LF")
        out.append(json.loads(raw[:-1]))
    if not out or out[0].get("record") != "header":
        raise SnapshotError("line 1 is not the header")
    return out


def read_content(blob: bytes) -> ContentArchive:
    recs = _records(blob)
    h = recs[0]
    if h.get("format") != CONTENT_FORMAT or h.get("format_version") != FORMAT_VERSION:
        raise SnapshotError("not a kotobaya-media-content v1 archive")
    segs: list[SegmentRec] = []
    sents: list[SentenceRec] = []
    occs: list[OccurrenceRec] = []
    spans: list[GrammarSpanRec] = []
    for r in recs[1:]:
        kind = r["record"]
        if kind == "segment":
            segs.append(
                SegmentRec(
                    r["id"],
                    r["ordinal"],
                    r["kind"],
                    r["title"],
                    r["sentence_count"],
                    r["token_count"],
                    r["unmapped_token_count"],
                )
            )
        elif kind == "sentence":
            sents.append(
                SentenceRec(
                    r["id"],
                    r["segment_id"],
                    r["ordinal"],
                    r["text"],
                    r["tokens"],
                    r["origin"],
                    r["start_ms"],
                    r["end_ms"],
                    r["audio_blob_hash"],
                    r["image_blob_hash"],
                    r["location"],
                )
            )
        elif kind == "occurrence":
            occs.append(
                OccurrenceRec(
                    r["segment_id"],
                    r["entry_seq"],
                    r["count"],
                    r["pos_class"],
                    tuple(r["sentence_ids"]),
                )
            )
        elif kind == "grammar_span":
            spans.append(
                GrammarSpanRec(
                    r["sentence_id"],
                    r["grammar_point_id"],
                    r["start_offset"],
                    r["end_offset"],
                    r["rule_index"],
                )
            )
        else:
            raise SnapshotError(f"unknown record {kind!r}")
    counts = h["counts"]
    if counts != {
        "segment": len(segs),
        "sentence": len(sents),
        "occurrence": len(occs),
        "grammar_span": len(spans),
    }:
        raise SnapshotError("header counts do not match the records")
    return ContentArchive(h["media_id"], h["content_version"], segs, sents, occs, spans)


def read_deck_items(blob: bytes) -> DeckArchive:
    recs = _records(blob)
    h = recs[0]
    if h.get("format") != DECK_FORMAT or h.get("format_version") != FORMAT_VERSION:
        raise SnapshotError("not a kotobaya-deck-items v1 archive")
    items = [DeckItemRec(r["entry_seq"], r["position"], r["occurrence_count"]) for r in recs[1:]]
    if h["counts"] != {"item": len(items)}:
        raise SnapshotError("header counts do not match the records")
    return DeckArchive(h["deck_id"], h["deck_type"], h["content_version"], items)
