"""Draft segments -> one ``PublishRequest`` (MEDIA_PIPELINE.md 3.2 step 2; SNAPSHOT.md 3).

Shared by ``media-import`` and ``media-parse``. Ids first (SNAPSHOT.md 3.4, so sentence-scoped overrides can
apply), then each segment that has unparsed sentences is parsed, one segment at a time with progress and a
cancellation check between segments, then aggregated. Nothing is uploaded here: publishing is the last step.
"""

from __future__ import annotations

import logging
from collections.abc import Sequence
from dataclasses import dataclass, field, replace
from typing import Any

from kotobaya_worker.coverage.aggregate import (
    SegmentCounts,
    SentenceTokens,
    aggregate_segment,
    deck_items,
    job_result,
)
from kotobaya_worker.importers.jpdb_history.handler import Context
from kotobaya_worker.media.pipeline.base import BaseSegment, MediaBase
from kotobaya_worker.media.pipeline.parser import SegmentParser
from kotobaya_worker.media.publish.handoff import CreateSpec, PublishRequest
from kotobaya_worker.media.publish.ids import NewSegment, assign_ids
from kotobaya_worker.media.publish.snapshot import (
    ContentArchive,
    DeckArchive,
    SegmentRec,
    SentenceRec,
)

log = logging.getLogger(__name__)
TITLE_MAX = 300


@dataclass(frozen=True)
class DraftSentence:
    """A sentence of the next version. ``tokens`` None = parse it; set = keep these tokens."""

    text: str
    tokens: list[Any] | None = None
    origin: str = "media"
    start_ms: int | None = None
    end_ms: int | None = None
    audio_blob_hash: str | None = None
    image_blob_hash: str | None = None
    location: dict[str, Any] | None = None


@dataclass(frozen=True)
class DraftSegment:
    kind: str
    title: str
    sentences: tuple[DraftSentence, ...] = field(default_factory=tuple)

    def needs_parse(self) -> bool:
        return any(s.tokens is None for s in self.sentences)


def from_base(seg: BaseSegment, *, reparse: bool) -> DraftSegment:
    """An existing segment carried into the next version, its tokens kept or dropped for re-parsing."""
    return DraftSegment(
        seg.kind,
        seg.title,
        tuple(
            DraftSentence(
                text=t.text,
                tokens=None if reparse else list(t.tokens),
                origin=t.origin,
                start_ms=t.start_ms,
                end_ms=t.end_ms,
                audio_blob_hash=t.audio_blob_hash,
                image_blob_hash=t.image_blob_hash,
                location=t.location,
            )
            for t in seg.sentences
        ),
    )


@dataclass(frozen=True)
class Planned:
    request: PublishRequest
    counts: tuple[SegmentCounts, ...]


def plan_request(
    ctx: Context,
    media_id: str,
    base: MediaBase,
    drafts: Sequence[DraftSegment],
    parser: SegmentParser,
    *,
    create: CreateSpec | None,
    progress: tuple[float, float] = (0.3, 0.85),
) -> Planned:
    """Segment ordinal = position in ``drafts``. ``base`` is the version being replaced."""
    if not drafts:
        raise ValueError("a media needs at least one segment")
    version = base.content_version + 1
    ids = assign_ids(
        media_id,
        base.existing(),
        [NewSegment(i, [t.text for t in d.sentences]) for i, d in enumerate(drafts)],
    )
    lo, hi = progress
    n_parse = sum(1 for d in drafts if d.needs_parse())
    parsed = 0
    segs: list[SegmentRec] = []
    sents: list[SentenceRec] = []
    counts: list[SegmentCounts] = []
    for so, draft in enumerate(drafts):
        ctx.check_cancelled()  # between segments
        seg_id = ids.segment_ids[so]
        sent_ids = [ids.sentence_ids[(so, i)] for i in range(len(draft.sentences))]
        drafted = list(draft.sentences)
        if draft.needs_parse():
            ctx.progress(
                lo + (hi - lo) * parsed / n_parse,
                f"parsing {draft.title or f'segment {so + 1}'} ({parsed + 1}/{n_parse})",
            )
            todo = [i for i, s in enumerate(drafted) if s.tokens is None]
            tokens = parser(media_id, [sent_ids[i] for i in todo], [drafted[i].text for i in todo])
            if len(tokens) != len(todo):
                raise RuntimeError("parser returned a different number of sentences")
            for i, toks in zip(todo, tokens, strict=True):
                drafted[i] = replace(drafted[i], tokens=list(toks))
            parsed += 1
        recs = [
            SentenceRec(
                id=sent_ids[i],
                segment_id=seg_id,
                ordinal=i,
                text=s.text,
                tokens=list(s.tokens or []),
                origin=s.origin,
                start_ms=s.start_ms,
                end_ms=s.end_ms,
                audio_blob_hash=s.audio_blob_hash,
                image_blob_hash=s.image_blob_hash,
                location=s.location,
            )
            for i, s in enumerate(drafted)
        ]
        c = aggregate_segment(
            seg_id, [SentenceTokens(r.id, r.ordinal, r.tokens, r.origin) for r in recs]
        )
        segs.append(
            SegmentRec(
                id=seg_id,
                ordinal=so,
                kind=draft.kind,
                title=draft.title[:TITLE_MAX],
                sentence_count=c.sentence_count,
                token_count=c.token_count,
                unmapped_token_count=c.unmapped_token_count,
            )
        )
        sents.extend(recs)
        counts.append(c)
    ctx.check_cancelled()
    content = ContentArchive(
        media_id, version, segs, sents, [o for c in counts for o in c.occurrences]
    )
    deck = DeckArchive(media_id, "media", version, deck_items(counts))
    log.info("media %s v%d planned: %s", media_id, version, job_result(media_id, version, counts))
    return Planned(
        PublishRequest(
            media_id=media_id,
            base_content_version=base.content_version,
            content=content,
            deck_items=deck,
            create=create,
        ),
        tuple(counts),
    )
