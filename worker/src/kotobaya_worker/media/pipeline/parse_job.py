"""Runner handler for ``media-parse`` (openapi ``reparseMedia``; MEDIA_PIPELINE.md 2, 3.2).

Re-parse every sentence of the media's current version (text from ``app.sentence``) with the current dictionary
and overrides, re-aggregate and publish a new content version. Segments, texts, timings, blobs and locations are
kept, so every segment and sentence id is kept (SNAPSHOT.md 3.4). ``source = jpdb`` never gets here (the API
answers 409); the handler refuses it anyway. Registration in ``build_registry`` is W-5's.
"""

from __future__ import annotations

import uuid
from collections.abc import Callable, Mapping
from dataclasses import asdict
from typing import Any

import psycopg
from psycopg.rows import dict_row

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.package.s3 import S3Client, S3Config
from kotobaya_worker.importers.jpdb_history.handler import Context
from kotobaya_worker.media.pipeline.base import BaseReader, MediaBase, PgMediaReader
from kotobaya_worker.media.pipeline.parser import PgSegmentParser, SegmentParser
from kotobaya_worker.media.pipeline.plan import from_base, plan_request
from kotobaya_worker.media.publish.blobs import (
    BlobTable,
    ObjectStore,
    PgBlobTable,
    S3ObjectStore,
)
from kotobaya_worker.media.publish.handoff import (
    PgPublishStore,
    PublishRequest,
    PublishStore,
    publish_replanning,
)

KIND = "media-parse"


def check_parsable(media_id: str, base: MediaBase) -> None:
    if not base.exists:
        raise RuntimeError(f"unknown_media: media {media_id} does not exist")
    if not base.live:
        raise RuntimeError(f"media_deleted: media {media_id} is deleted")
    if base.source == "jpdb":
        raise RuntimeError(f"not_applicable: media {media_id} is a jpdb deck (nothing to parse)")
    if base.content_version == 0 or not base.segments:
        raise RuntimeError(f"media {media_id} has no published content to re-parse")


def run_parse(
    ctx: Context,
    *,
    parser: SegmentParser,
    base_reader: BaseReader,
    store: PublishStore,
    objects: ObjectStore,
    blobs: BlobTable,
    **publish_kw: Any,
) -> Mapping[str, Any]:
    raw = ctx.payload.get("media_id")
    if not isinstance(raw, str):
        raise TypeError("media-parse payload needs media_id")
    media_id = str(uuid.UUID(raw))
    done = store.succeeded_result(ctx.job_id)  # an earlier attempt already published
    if done is not None:
        return dict(done)
    ctx.progress(0.0, "reading sentences")

    def plan() -> PublishRequest:
        base = base_reader.read(media_id)
        check_parsable(media_id, base)
        drafts = [from_base(s, reparse=True) for s in base.segments]
        return plan_request(
            ctx, media_id, base, drafts, parser, create=None, progress=(0.05, 0.85)
        ).request

    result = publish_replanning(ctx, plan, store=store, objects=objects, blobs=blobs, **publish_kw)
    return asdict(result)


def make_media_parse(
    database_url: str, objects: ObjectStore | None = None
) -> Callable[[Context], Mapping[str, Any]]:
    """Build the runner handler (``objects`` defaults to the S3 bucket named by the environment)."""

    def handler(ctx: Context) -> Mapping[str, Any]:
        store_objects = objects or S3ObjectStore(S3Client(S3Config.from_env()))
        with (
            psycopg.connect(to_conninfo(database_url), autocommit=True, row_factory=dict_row) as c,
            psycopg.connect(to_conninfo(database_url), autocommit=True) as pc,
        ):
            return run_parse(
                ctx,
                parser=PgSegmentParser(pc),
                base_reader=PgMediaReader(c),
                store=PgPublishStore(c),
                objects=store_objects,
                blobs=PgBlobTable(c),
            )

    return handler


__all__ = ["KIND", "check_parsable", "make_media_parse", "run_parse"]
