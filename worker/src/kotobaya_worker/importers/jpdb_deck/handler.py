"""Runner handler for ``jpdb-deck-import`` (MEDIA_PIPELINE.md 3.2, 5.3, 5.4; SCHEMA.md 4.3).

Fetch (cache, T401a) -> read corrections -> map and merge -> one ``whole`` segment, occurrences, deck items ->
publish through the T404a kit. The result is the server's ``JobResultMedia``. Registration is W-5's.

Retry: the segment id of a new media is derived from the media id, so the same job re-planned gives the same
snapshot bytes (same blob hashes) and the kit adopts the existing ``media-publish`` child.
"""

from __future__ import annotations

import logging
import os
import uuid
from collections.abc import Callable, Mapping
from dataclasses import asdict, dataclass
from typing import Any

import psycopg
from psycopg.rows import dict_row

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.package.s3 import S3Client, S3Config
from kotobaya_worker.importers.jpdb_deck.client import (
    CachedFetcher,
    DeckVocabulary,
    JpdbDeckSource,
    PagesDeckSource,
    default_cache_dir,
)
from kotobaya_worker.importers.jpdb_deck.client.api import ApiDeckSource
from kotobaya_worker.importers.jpdb_deck.client.pages import normalize_deck_id
from kotobaya_worker.importers.jpdb_deck.plan import DeckPlan, plan_deck, pos_class
from kotobaya_worker.importers.jpdb_deck.repo import DeckRepo, PgDeckRepo
from kotobaya_worker.importers.jpdb_history.handler import Context
from kotobaya_worker.importers.jpdb_history.ref import PostgresRefLookup, RefLookup
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
from kotobaya_worker.media.publish.ids import ExistingContent, NewSegment, PgBaseReader, assign_ids
from kotobaya_worker.media.publish.snapshot import (
    ContentArchive,
    DeckArchive,
    OccurrenceRec,
    SegmentRec,
    media_deck_items,
)

KIND = "jpdb-deck-import"
log = logging.getLogger(__name__)
FULL_IMPORT_MAX_AGE_S = (
    7 * 24 * 3600.0
)  # a full import refetches a deck older than this; remap never does
TITLE_MAX = 300


class _CancellingFetcher(CachedFetcher):
    """Checks cancellation before every page/request, so a cancel is honoured between pages."""

    def __init__(self, ctx: Context, **kw: Any) -> None:
        super().__init__(**kw)
        self._ctx = ctx

    def get(self, url: str, **kw: Any) -> str:
        self._ctx.check_cancelled()
        return super().get(url, **kw)


SourceFactory = Callable[[Context, bool], JpdbDeckSource]


def default_source_factory(ctx: Context, remap_only: bool) -> JpdbDeckSource:
    """Pages source, or the API source when ``JPDB_API_KEY`` is set; remap_only uses the cache at any age."""
    fetcher = _CancellingFetcher(
        ctx, cache_dir=default_cache_dir(), max_age=None if remap_only else FULL_IMPORT_MAX_AGE_S
    )
    pages = PagesDeckSource(fetcher)
    key = os.environ.get("JPDB_API_KEY")
    return ApiDeckSource(fetcher, key, pages) if key else pages


@dataclass(frozen=True)
class DeckJob:
    jpdb_id: str
    media_id: str
    title: str | None
    kind: str
    deck: DeckOptions
    remap_only: bool

    @staticmethod
    def from_payload(p: Mapping[str, Any]) -> DeckJob:
        jpdb_id, media_id = p.get("jpdb_id"), p.get("media_id")
        if not isinstance(jpdb_id, str) or not jpdb_id:
            raise ValueError("jpdb-deck-import payload needs jpdb_id")
        if not isinstance(media_id, str):
            raise TypeError("jpdb-deck-import payload needs media_id (set by the server)")
        uuid.UUID(media_id)
        jpdb_id = normalize_deck_id(jpdb_id)
        d = p.get("deck") or {}
        title = p.get("title")
        return DeckJob(
            jpdb_id=jpdb_id,
            media_id=media_id,
            title=title if isinstance(title, str) and title else None,
            kind=str(p.get("kind") or "other"),
            deck=DeckOptions(
                priority=int(d.get("priority", 0)),
                enabled=bool(d.get("enabled", True)),
                new_cards_per_day=d.get("new_cards_per_day"),
            ),
            remap_only=bool(p.get("remap_only", False)),
        )


def build_archives(
    job: DeckJob,
    plan: DeckPlan,
    title: str,
    existing: ExistingContent,
    entry_pos: Mapping[int, frozenset[str]],
) -> tuple[ContentArchive, DeckArchive]:
    """One ``whole`` segment, no sentences; occurrences with ``sentence_ids = []`` (SCHEMA.md 4.3)."""
    version = existing.content_version + 1
    mid = uuid.UUID(job.media_id)
    ids = assign_ids(
        job.media_id,
        existing,
        [NewSegment(0, [])],
        mint=lambda: str(
            uuid.uuid5(mid, "segment:0")
        ),  # deterministic: a retry gives the same bytes
    )
    seg_id = ids.segment_ids[0]
    seg = SegmentRec(
        id=seg_id,
        ordinal=0,
        kind="whole",
        title=title[:TITLE_MAX],
        sentence_count=0,
        token_count=plan.token_count,
        unmapped_token_count=plan.unmapped_token_count,
    )
    occs = [
        OccurrenceRec(seg_id, e.seq, e.count, pos_class(entry_pos.get(e.seq, frozenset())), ())
        for e in sorted(plan.entries.values(), key=lambda e: e.seq)
    ]
    content = ContentArchive(job.media_id, version, [seg], [], occs)
    deck = DeckArchive(job.media_id, "media", version, media_deck_items(occs))
    return content, deck


def jpdb_payload(job: DeckJob, plan: DeckPlan) -> dict[str, Any]:
    return {
        "jpdb_id": job.jpdb_id,
        "vocab": [asdict(v) for v in plan.vocab],
    }


def unmapped_list(plan: DeckPlan) -> list[dict[str, Any]]:
    return [
        {
            "jpdb_vid": v.jpdb_vid,
            "spelling": v.spelling,
            "reading": v.reading,
            "occurrence_count": v.occurrence_count,
        }
        for v in plan.vocab
        if v.resolution == "unresolved"
    ]


def run_import(
    ctx: Context,
    *,
    source: JpdbDeckSource,
    repo: DeckRepo,
    ref: RefLookup,
    base_reader: Any,
    store: PublishStore,
    objects: ObjectStore,
    blobs: BlobTable,
    **publish_kw: Any,
) -> Mapping[str, Any]:
    job = DeckJob.from_payload(ctx.payload)
    if job.remap_only:
        repo.claim_remap(ctx.job_id)  # R1, before any correction is read
    ctx.progress(0.0, "fetching deck")
    deck: DeckVocabulary = source.fetch_deck(job.jpdb_id)
    ctx.check_cancelled()
    ctx.progress(0.5, "mapping")
    title = (job.title or deck.title or job.jpdb_id).strip() or job.jpdb_id
    vids = sorted({w.vid for w in deck.words if w.vid > 0})

    def plan_request() -> PublishRequest:
        ctx.check_cancelled()
        state = repo.media_state(job.media_id)
        if state is not None and (not state.live or state.source != "jpdb"):
            raise RuntimeError(f"media_deleted: media {job.media_id} is gone or not a jpdb media")
        if state is None and job.remap_only:
            raise RuntimeError(f"media_deleted: media {job.media_id} does not exist")
        existing = base_reader.read(job.media_id)
        plan = plan_deck(deck, repo.corrections(vids), ref)
        log.info("jpdb deck %s: %s", job.jpdb_id, plan.stats)
        content, deck_items = build_archives(
            job, plan, title, existing, repo.entry_pos(list(plan.entries))
        )
        base = existing.content_version
        create = (
            CreateSpec(
                title=title[:TITLE_MAX],
                kind=job.kind,
                source="jpdb",
                jpdb_id=job.jpdb_id,
                deck=job.deck,
            )
            if base == 0
            else None
        )
        return PublishRequest(
            media_id=job.media_id,
            base_content_version=base,
            content=content,
            deck_items=deck_items,
            create=create,
            unmapped=unmapped_list(plan),
            jpdb=jpdb_payload(job, plan),
        )

    result = publish_replanning(
        ctx, plan_request, store=store, objects=objects, blobs=blobs, **publish_kw
    )
    return asdict(result)


def make_jpdb_deck_import(
    database_url: str,
    objects: ObjectStore | None = None,
    source_factory: SourceFactory = default_source_factory,
) -> Callable[[Context], Mapping[str, Any]]:
    """Build the runner handler (``objects`` defaults to the S3 bucket named by the environment)."""

    def handler(ctx: Context) -> Mapping[str, Any]:
        store_objects = objects or S3ObjectStore(S3Client(S3Config.from_env()))
        remap = bool(ctx.payload.get("remap_only", False))
        source = source_factory(ctx, remap)
        with (
            psycopg.connect(to_conninfo(database_url), autocommit=True, row_factory=dict_row) as c,
            psycopg.connect(to_conninfo(database_url)) as refc,
        ):
            refc.read_only = True
            return run_import(
                ctx,
                source=source,
                repo=PgDeckRepo(c),
                ref=PostgresRefLookup(refc),
                base_reader=PgBaseReader(c),
                store=PgPublishStore(c),
                objects=store_objects,
                blobs=PgBlobTable(c),
            )

    return handler
