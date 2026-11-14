"""Runner handler for ``media-import`` (openapi ``importMedia``; MEDIA_PIPELINE.md 2, 3.2).

Fetch the uploaded blobs (or the URL, or the pasted text) -> extract (T402) -> cut audio clips (T409, optional)
-> per plan: read the base, append the new segments after the existing ones, parse with media-scoped overrides
(T403b), aggregate, publish (T404a). Publishing is the last step, so a failure or a cancellation leaves the
previous content version (or, for a new media, nothing) in place.

Segments: one per file in array order, except an EPUB, which gives one per chapter in book order; a URL or a
paste gives one. Existing segments of an append are carried verbatim (their tokens are not re-parsed; that is
``media-parse``). Registration in ``build_registry`` is W-5's.
"""

from __future__ import annotations

import json
import logging
import uuid
from collections.abc import Callable, Mapping, Sequence
from dataclasses import asdict, dataclass, field
from typing import Any
from urllib.parse import urlsplit

import psycopg
from psycopg.rows import dict_row

from kotobaya_worker.audio.clips import ClipOptions, cut_clips
from kotobaya_worker.audio.storage import fetched_source, make_clip_store
from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.package.s3 import S3Client, S3Config
from kotobaya_worker.extract import ExtractedSegment, ExtractedSentence, detect_format, extract
from kotobaya_worker.extract.html import MAX_BYTES, TIMEOUT_S, Fetcher, check_url, default_fetcher
from kotobaya_worker.importers.jpdb_history.apply import make_fetch_blob
from kotobaya_worker.importers.jpdb_history.handler import Context
from kotobaya_worker.media.pipeline.base import BaseReader, MediaBase, PgMediaReader
from kotobaya_worker.media.pipeline.parser import PgSegmentParser, SegmentParser
from kotobaya_worker.media.pipeline.plan import (
    TITLE_MAX,
    DraftSegment,
    DraftSentence,
    from_base,
    log_media_summary,
    plan_request,
)
from kotobaya_worker.media.publish.blobs import (
    BlobTable,
    ObjectStore,
    PgBlobTable,
    S3ObjectStore,
)
from kotobaya_worker.media.publish.handoff import (
    CreateSpec,
    DeckOptions,
    PgPublishStore,
    PublishRequest,
    PublishStore,
    publish_replanning,
)

KIND = "media-import"
log = logging.getLogger(__name__)

FetchBlob = Callable[[str], bytes]
# (audio/video blob hash, the segment's sentences) -> sentence index -> clip blob hash. Missing = no clip.
ClipCutter = Callable[[str, Sequence[ExtractedSentence]], Mapping[int, str]]

EXTRACT_END = 0.1
AUDIO_END = 0.3
PARSE_END = 0.85


@dataclass(frozen=True)
class ImportFile:
    blob_hash: str
    filename: str
    title: str | None = None
    audio_blob_hash: str | None = None


@dataclass(frozen=True)
class ImportJob:
    """The ``media-import`` payload: ``MediaImportRequest`` with ``media_id`` always set."""

    media_id: str
    source: str
    title: str | None = None
    kind: str | None = None
    files: tuple[ImportFile, ...] = ()
    url: str | None = None
    text: str | None = None
    format: str = "auto"
    ruby: str = "hint"
    cut_audio: bool = False
    deck: DeckOptions = field(default_factory=DeckOptions)

    @staticmethod
    def from_payload(p: Mapping[str, Any]) -> ImportJob:
        media_id = p.get("media_id")
        if not isinstance(media_id, str):
            raise TypeError("media-import payload needs media_id (set by the server)")
        media_id = str(uuid.UUID(media_id))
        source = p.get("source")
        if source not in ("file", "url", "paste"):
            raise ValueError(f"media-import: unknown source {source!r}")
        files = tuple(
            ImportFile(
                blob_hash=str(f["blob_hash"]),
                filename=str(f["filename"]),
                title=f.get("title") or None,
                audio_blob_hash=f.get("audio_blob_hash") or None,
            )
            for f in p.get("files") or ()
        )
        url, text = p.get("url"), p.get("text")
        if source == "file" and not files:
            raise ValueError("media-import: source file needs files")
        if source == "url" and not isinstance(url, str):
            raise ValueError("media-import: source url needs url")
        if source == "paste" and not (isinstance(text, str) and text):
            raise ValueError("media-import: source paste needs text")
        opts = p.get("options") or {}
        d = p.get("deck") or {}
        title, kind = p.get("title"), p.get("kind")
        return ImportJob(
            media_id=media_id,
            source=source,
            title=title if isinstance(title, str) and title else None,
            kind=kind if isinstance(kind, str) and kind else None,
            files=files,
            url=url if isinstance(url, str) else None,
            text=text if isinstance(text, str) else None,
            format=str(p.get("format") or "auto"),
            ruby=str(opts.get("ruby", "hint")),
            cut_audio=bool(opts.get("cut_audio", False)),
            deck=DeckOptions(
                priority=int(d.get("priority", 0)),
                enabled=bool(d.get("enabled", True)),
                new_cards_per_day=d.get("new_cards_per_day"),
            ),
        )


@dataclass(frozen=True)
class Extracted:
    """One new segment and the audio file its clips come from (subtitle files only)."""

    segment: ExtractedSegment
    audio_blob_hash: str | None = None


def _one_segment(segs: Sequence[ExtractedSegment], kind: str, title: str) -> ExtractedSegment:
    """Several extracted segments (a TXT with chapter headings) -> the one segment the contract asks for."""
    if len(segs) == 1:
        return ExtractedSegment(segs[0].kind, title or segs[0].title, segs[0].sentences)
    return ExtractedSegment(kind, title, tuple(s for seg in segs for s in seg.sentences))


def _require_text(segs: Sequence[ExtractedSegment], what: str) -> None:
    if not any(seg.sentences for seg in segs):
        raise ValueError(f"{what}: no text found")


def extract_sources(
    ctx: Context, job: ImportJob, fetch_blob: FetchBlob, fetch_url: Fetcher
) -> list[Extracted]:
    """The new segments, in order. Every source must yield some text (nothing is dropped silently)."""
    options = {"ruby": job.ruby}
    if job.source == "url":
        assert job.url is not None
        ctx.progress(0.0, "fetching URL")
        check_url(job.url)
        body = fetch_url(job.url, MAX_BYTES, TIMEOUT_S)
        name = urlsplit(job.url).path or "page"
        segs = extract(body, name, "html" if job.format == "auto" else job.format, options)
        _require_text(segs, job.url)
        return [Extracted(_one_segment(segs, "whole", ""))]
    if job.source == "paste":
        assert job.text is not None
        fmt = "txt" if job.format == "auto" else job.format
        segs = extract(job.text.encode("utf-8"), "paste", fmt, options)
        _require_text(segs, "pasted text")
        return [Extracted(_one_segment(segs, "whole", job.title or ""))]
    out: list[Extracted] = []
    for n, f in enumerate(job.files):
        ctx.check_cancelled()
        ctx.progress(
            EXTRACT_END * n / len(job.files), f"reading {f.filename} ({n + 1}/{len(job.files)})"
        )
        fmt = detect_format(f.filename, job.format)
        segs = extract(fetch_blob(f.blob_hash), f.filename, fmt, options)
        _require_text(segs, f.filename)
        if fmt == "epub":
            out.extend(Extracted(s) for s in segs if s.sentences)
        else:
            out.append(Extracted(_one_segment(segs, "volume", f.title or ""), f.audio_blob_hash))
    return out


def _location(raw: str | None) -> dict[str, Any] | None:
    if raw is None:
        return None
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise TypeError("extractor location must be a JSON object")
    return value


def to_drafts(
    ctx: Context, job: ImportJob, extracted: Sequence[Extracted], cut: ClipCutter | None
) -> list[DraftSegment]:
    """Extracted segments -> drafts, with one audio clip per timed sentence when ``cut_audio`` is on."""
    with_audio = [e for e in extracted if e.audio_blob_hash is not None] if job.cut_audio else []
    if with_audio and cut is None:
        raise RuntimeError("cut_audio requested but no clip cutter is configured")
    out: list[DraftSegment] = []
    done = 0
    for e in extracted:
        seg = e.segment
        clips: Mapping[int, str] = {}
        if job.cut_audio and e.audio_blob_hash is not None and cut is not None:
            ctx.check_cancelled()
            ctx.progress(
                EXTRACT_END + (AUDIO_END - EXTRACT_END) * done / len(with_audio),
                f"cutting audio {seg.title} ({done + 1}/{len(with_audio)})",
            )
            clips = cut(e.audio_blob_hash, seg.sentences)
            missing = sum(
                1 for i, s in enumerate(seg.sentences) if s.start_ms is not None and i not in clips
            )
            if missing:
                log.warning(
                    "media %s, %s: %d sentence(s) without a clip", job.media_id, seg.title, missing
                )
            done += 1
        out.append(
            DraftSegment(
                seg.kind,
                seg.title,
                tuple(
                    DraftSentence(
                        text=s.text,
                        start_ms=s.start_ms,
                        end_ms=s.end_ms,
                        audio_blob_hash=clips.get(i),
                        location=_location(s.location),
                    )
                    for i, s in enumerate(seg.sentences)
                ),
            )
        )
    return out


def create_spec(job: ImportJob, base: MediaBase) -> CreateSpec | None:
    """``create`` for a new media; checks that an existing one may be appended to."""
    if not base.exists:
        if job.title is None or job.kind is None:
            raise RuntimeError(f"unknown_media: media {job.media_id} does not exist")
        return CreateSpec(job.title[:TITLE_MAX], job.kind, job.source, None, job.deck)
    if not base.live:
        raise RuntimeError(f"media_deleted: media {job.media_id} is deleted")
    if base.source != job.source:
        raise RuntimeError(
            f"media {job.media_id} has source {base.source!r}, this import is {job.source!r}"
        )
    if job.title is not None or base.content_version == 0:
        raise RuntimeError(f"media {job.media_id} already exists; a new-media import cannot append")
    return None


def run_import(
    ctx: Context,
    *,
    fetch_blob: FetchBlob,
    parser: SegmentParser,
    base_reader: BaseReader,
    store: PublishStore,
    objects: ObjectStore,
    blobs: BlobTable,
    cut: ClipCutter | None = None,
    fetch_url: Fetcher = default_fetcher,
    **publish_kw: Any,
) -> Mapping[str, Any]:
    job = ImportJob.from_payload(ctx.payload)
    done = store.succeeded_result(ctx.job_id)  # an earlier attempt already published
    if done is not None:
        log_media_summary(KIND, done, adopted=True)
        return dict(done)
    extracted = extract_sources(ctx, job, fetch_blob, fetch_url)
    new = to_drafts(ctx, job, extracted, cut)
    ctx.check_cancelled()

    def plan() -> PublishRequest:
        base = base_reader.read(job.media_id)
        create = create_spec(job, base)
        drafts = [from_base(s, reparse=False) for s in base.segments] + new
        return plan_request(
            ctx, job.media_id, base, drafts, parser, create=create, progress=(AUDIO_END, PARSE_END)
        ).request

    result = publish_replanning(ctx, plan, store=store, objects=objects, blobs=blobs, **publish_kw)
    log_media_summary(KIND, asdict(result))
    return asdict(result)


def make_clip_cutter(
    client: S3Client, conn: psycopg.Connection[Any], opts: ClipOptions | None = None
) -> ClipCutter:
    """Stream the source blob to a temp file, cut one clip per distinct timing, store clips by hash."""
    store = None

    def cut(audio_blob_hash: str, sentences: Sequence[ExtractedSentence]) -> Mapping[int, str]:
        nonlocal store
        if store is None:
            store = make_clip_store(client, conn)
        with fetched_source(client, audio_blob_hash) as src:
            res = cut_clips(src, sentences, opts or ClipOptions(), store)
        for i, err in sorted(res.failed.items())[:5]:
            log.warning("clip for sentence %d of %s failed: %s", i, audio_blob_hash, err)
        return res.hashes

    return cut


def make_media_import(
    database_url: str,
    objects: ObjectStore | None = None,
    fetch_blob: FetchBlob | None = None,
) -> Callable[[Context], Mapping[str, Any]]:
    """Build the runner handler (``objects``/blobs default to the S3 bucket named by the environment)."""

    def handler(ctx: Context) -> Mapping[str, Any]:
        client = S3Client(S3Config.from_env())
        store_objects = objects or S3ObjectStore(client)
        with (
            psycopg.connect(to_conninfo(database_url), autocommit=True, row_factory=dict_row) as c,
            psycopg.connect(to_conninfo(database_url), autocommit=True) as pc,
        ):
            return run_import(
                ctx,
                fetch_blob=fetch_blob or make_fetch_blob(),
                parser=PgSegmentParser(pc),
                base_reader=PgMediaReader(c),
                store=PgPublishStore(c),
                objects=store_objects,
                blobs=PgBlobTable(c),
                cut=make_clip_cutter(client, c),
            )

    return handler


__all__ = [
    "KIND",
    "ClipCutter",
    "ImportFile",
    "ImportJob",
    "extract_sources",
    "make_media_import",
    "run_import",
    "to_drafts",
]
