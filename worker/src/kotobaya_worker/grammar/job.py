"""Runner handler for ``grammar-detect`` (C-13; contracts/db/SCHEMA.md 4.4 "``grammar-detect``").

Detected spans are media content (MEDIA_PIPELINE.md 1, SNAPSHOT.md 3): only the server writes them,
inside a ``media-publish`` install that replaces the media's detected rows with the archive's. So a
per-media job republishes the media like ``media-parse`` does, but keeps every token and only the
spans change. Manual spans are never in an archive and the install deletes ``origin = 'detected'``
rows only, so they are never touched.

- ``{"media_id": <uuid>}`` (per-media): base, live points' rules and installed detected spans come
  from one REPEATABLE READ snapshot (``PgMediaReader``); ``plan_request`` detects with those rules.
  Equal span sets: ``changed: false``, nothing published (a second run is a no-op). Else publish; every
  ``stale_base`` re-plan re-reads all three in a new snapshot and re-checks equality.
- ``{}`` (fan-out): release the ``grammar-detect:all`` key under ``pg_advisory_xact_lock(7033001)``,
  then read the rules in a fresh snapshot and enqueue one per-media job per eligible media with
  ``dedupe_key = grammar-detect:<media id>:<rules_digest>``.
"""

from __future__ import annotations

import json
import uuid
from collections.abc import Callable, Mapping
from typing import Any, Protocol

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.package.s3 import S3Client, S3Config
from kotobaya_worker.grammar.detect import PointRules, rules_digest
from kotobaya_worker.importers.jpdb_history.apply import EVENTS_CHANNEL, uuid7
from kotobaya_worker.importers.jpdb_history.handler import Context
from kotobaya_worker.media.pipeline.base import BaseReader, MediaBase, PgMediaReader
from kotobaya_worker.media.pipeline.plan import from_base, plan_request
from kotobaya_worker.media.publish.blobs import BlobTable, ObjectStore, PgBlobTable, S3ObjectStore
from kotobaya_worker.media.publish.handoff import (
    PgPublishStore,
    PublishRequest,
    PublishStore,
    publish_replanning,
)

KIND = "grammar-detect"
OP_APPLY_LOCK = 7033001


class RuleSource(Protocol):
    def load(self) -> list[PointRules]:
        """Every live grammar point with non-empty ``detection_rules`` (a fresh snapshot)."""
        ...


class DetectQueue(Protocol):
    def release(self, job_id: uuid.UUID) -> None:
        """Clear the fan-out job's own dedupe key under the op-apply advisory lock (committed)."""
        ...

    def media_ids(self) -> list[str]:
        """Live media with published content that can carry spans (not jpdb)."""
        ...

    def enqueue(self, media_id: str, digest: str) -> bool:
        """Queue ``grammar-detect {media_id}``; False if an identical job is already queued/running."""
        ...


def dedupe_key(media_id: str, digest: str) -> str:
    return f"{KIND}:{media_id}:{digest}"


def skip_reason(base: MediaBase) -> str | None:
    if not base.exists:
        return "unknown_media"
    if not base.live:
        return "media_deleted"
    if base.source == "jpdb":
        return "not_applicable"
    if base.content_version == 0 or not base.segments:
        return "not_parsed"
    return None


def _no_parse(media_id: str, sentence_ids: Any, texts: Any) -> list[list[dict[str, Any]]]:
    raise AssertionError("grammar-detect keeps every token; nothing is re-parsed")


class _Finished(Exception):
    """Planning ended without a publish (skipped or nothing changed)."""

    def __init__(self, result: dict[str, Any]) -> None:
        super().__init__("finished")
        self.result = result


def run_detect_media(
    ctx: Context,
    media_id: str,
    *,
    base_reader: BaseReader,
    store: PublishStore,
    objects: ObjectStore,
    blobs: BlobTable,
    **publish_kw: Any,
) -> Mapping[str, Any]:
    done = store.succeeded_result(ctx.job_id)  # step 1: an earlier attempt already published
    if done is not None:
        return {"media_id": media_id, "changed": True, "content_version": done["content_version"]}
    ctx.progress(0.0, "reading media and rules")
    counts: dict[str, int] = {}

    def plan() -> PublishRequest:
        base = base_reader.read(media_id)  # one snapshot: base rows, rules, installed spans
        reason = skip_reason(base)
        if reason is not None:
            raise _Finished({"media_id": media_id, "changed": False, "skipped": reason})
        drafts = [from_base(s, reparse=False) for s in base.segments]
        planned = plan_request(
            ctx, media_id, base, drafts, _no_parse, create=None, progress=(0.1, 0.8)
        )
        det = planned.detection
        counts.update(spans=len(det.spans), invalid_rules=det.invalid_rules)
        if set(det.spans) == base.detected_spans:
            raise _Finished(
                {
                    "media_id": media_id,
                    "changed": False,
                    "content_version": base.content_version,
                    **counts,
                }
            )
        ctx.progress(0.85, "publishing spans")
        return planned.request

    try:
        published = publish_replanning(
            ctx, plan, store=store, objects=objects, blobs=blobs, **publish_kw
        )
    except _Finished as f:
        return f.result
    return {
        "media_id": media_id,
        "changed": True,
        "content_version": published.content_version,
        **counts,
    }


def run_fan_out(ctx: Context, *, rules: RuleSource, queue: DetectQueue) -> Mapping[str, Any]:
    queue.release(ctx.job_id)  # (1) committed before (2) reads the rules
    digest = rules_digest(rules.load())
    ids = queue.media_ids()
    enqueued = 0
    for k, mid in enumerate(ids):
        ctx.check_cancelled()
        enqueued += queue.enqueue(mid, digest)
        ctx.progress((k + 1) / len(ids), f"queued {k + 1}/{len(ids)} media")
    return {"media": len(ids), "enqueued": enqueued, "rules_digest": digest}


# ------------------------------------------------------------------------------ Postgres


class PgRuleSource:
    """``conn``: autocommit, dict rows (each ``load`` is its own snapshot)."""

    def __init__(self, conn: psycopg.Connection[Any]) -> None:
        self._c = conn

    def load(self) -> list[PointRules]:
        rows = self._c.execute(
            "select id, detection_rules from app.grammar_point "
            "where deleted_at is null and detection_rules <> '[]'::jsonb order by id"
        ).fetchall()
        return [PointRules(str(r["id"]), r["detection_rules"]) for r in rows]


class PgDetectQueue:
    """``conn``: autocommit, dict rows."""

    def __init__(self, conn: psycopg.Connection[Any]) -> None:
        self._c = conn

    def release(self, job_id: uuid.UUID) -> None:
        with self._c.transaction():
            self._c.execute("select pg_advisory_xact_lock(%s)", (OP_APPLY_LOCK,))
            self._c.execute("update app.job set dedupe_key = null where id = %s", (job_id,))

    def media_ids(self) -> list[str]:
        rows = self._c.execute(
            "select id from app.media where deleted_at is null and content_version > 0 "
            "and source <> 'jpdb' order by id"
        ).fetchall()
        return [str(r["id"]) for r in rows]

    def enqueue(self, media_id: str, digest: str) -> bool:
        job = uuid7()
        cur = self._c.execute(
            "insert into app.job (id, kind, payload, dedupe_key) values (%s, %s, %s, %s) "
            "on conflict do nothing",
            (job, KIND, Jsonb({"media_id": media_id}), dedupe_key(media_id, digest)),
        )
        if cur.rowcount:
            self._c.execute(
                "select pg_notify(%s, %s)",
                (EVENTS_CHANNEL, json.dumps({"type": "job.updated", "data": {"id": str(job)}})),
            )
        return bool(cur.rowcount)


def make_grammar_detect(
    database_url: str, objects: ObjectStore | None = None
) -> Callable[[Context], Mapping[str, Any]]:
    """Build the runner handler (``objects`` defaults to the S3 bucket named by the environment)."""

    def handler(ctx: Context) -> Mapping[str, Any]:
        raw = ctx.payload.get("media_id")
        with psycopg.connect(to_conninfo(database_url), autocommit=True, row_factory=dict_row) as c:
            if raw is None:
                return run_fan_out(ctx, rules=PgRuleSource(c), queue=PgDetectQueue(c))
            if not isinstance(raw, str):
                raise TypeError("grammar-detect payload media_id must be a uuid string")
            return run_detect_media(
                ctx,
                str(uuid.UUID(raw)),
                base_reader=PgMediaReader(c),
                store=PgPublishStore(c),
                objects=objects or S3ObjectStore(S3Client(S3Config.from_env())),
                blobs=PgBlobTable(c),
            )

    return handler


__all__ = [
    "KIND",
    "DetectQueue",
    "PgDetectQueue",
    "PgRuleSource",
    "RuleSource",
    "dedupe_key",
    "make_grammar_detect",
    "run_detect_media",
    "run_fan_out",
    "skip_reason",
]
