"""Runner handler for ``grammar-detect``: (re-)tag media sentences with detected grammar spans.

Detected spans are media content (MEDIA_PIPELINE.md 1, SNAPSHOT.md 3): only the server writes them,
inside a ``media-publish`` install that replaces the media's detected rows with the archive's. So this
job republishes a media like ``media-parse`` does (T404b path), but keeps every token and swaps in the
new spans. Manual spans are never in an archive and the install only deletes ``origin = 'detected'``
rows, so they are never touched.

Not registered in ``build_registry`` yet: ``grammar-detect`` is not a value of the API's ``JobKind``
(the runner's list is closed), pending a contract change (T506a HANDOFF).

Payloads:

- ``{"media_id": <uuid>}``: one media. Live points' non-draft rules are matched against the stored
  tokens; if the result equals the installed detected spans nothing is published (idempotent: a second
  run is a no-op), else a new content version is published with the new spans (stale spans gone).
- ``{}`` (or any payload without ``media_id``, e.g. ``{"grammar_point_id": ...}`` after a rule edit):
  fan-out. Enqueues one ``grammar-detect {"media_id"}`` job per live, parsed, non-jpdb media with
  ``dedupe_key = grammar-detect:<media>:<rules digest>``, so an edit made while a media job is queued or
  running still gets its own run, and identical requests collapse.
"""

from __future__ import annotations

import json
import logging
import uuid
from collections.abc import Callable, Mapping
from dataclasses import replace
from typing import Any, Protocol

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.package.s3 import S3Client, S3Config
from kotobaya_worker.grammar.detect import (
    CompiledPoint,
    DetectSentence,
    PointRules,
    compile_points,
    detect_spans,
    rules_digest,
)
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
from kotobaya_worker.media.publish.snapshot import GrammarSpanRec

log = logging.getLogger(__name__)
KIND = "grammar-detect"


class RuleSource(Protocol):
    def load(self) -> list[PointRules]:
        """Every live grammar point with its ``detection_rules``."""
        ...


class SpanReader(Protocol):
    def detected(self, media_id: str) -> set[GrammarSpanRec]:
        """The media's installed ``origin = 'detected'`` spans."""
        ...


class DetectQueue(Protocol):
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


def _sentences(req: PublishRequest) -> list[DetectSentence]:
    return [DetectSentence(s.id, s.text, s.tokens) for s in req.content.sentences]


def run_detect_media(
    ctx: Context,
    media_id: str,
    *,
    rules: RuleSource,
    base_reader: BaseReader,
    spans: SpanReader,
    store: PublishStore,
    objects: ObjectStore,
    blobs: BlobTable,
    **publish_kw: Any,
) -> Mapping[str, Any]:
    done = store.succeeded_result(ctx.job_id)  # an earlier attempt already published
    if done is not None:
        return {"media_id": media_id, "changed": True, "content_version": done["content_version"]}
    ctx.progress(0.0, "reading rules")
    points, problems = compile_points(rules.load())
    for p in problems:
        log.warning(
            "grammar point %s rule %d skipped: %s", p.grammar_point_id, p.rule_index, p.message
        )
    base = base_reader.read(media_id)
    reason = skip_reason(base)
    if reason is not None:
        return {"media_id": media_id, "changed": False, "skipped": reason}

    def plan_from(b: MediaBase, pts: list[CompiledPoint]) -> PublishRequest:
        drafts = [from_base(s, reparse=False) for s in b.segments]
        req = plan_request(ctx, media_id, b, drafts, _no_parse, create=None, progress=(0.1, 0.8))
        found = detect_spans(pts, _sentences(req.request))
        return replace(req.request, content=replace(req.request.content, grammar_spans=found))

    first = plan_from(base, points)
    wanted = set(first.content.grammar_spans)
    result: dict[str, Any] = {
        "media_id": media_id,
        "spans": len(wanted),
        "invalid_rules": len(problems),
    }
    if wanted == spans.detected(media_id):
        return {**result, "changed": False, "content_version": base.content_version}
    ctx.progress(0.85, "publishing spans")
    plans = iter([first])

    def plan() -> PublishRequest:
        nxt = next(plans, None)  # the first plan was already built on this base
        if nxt is not None:
            return nxt
        b = base_reader.read(media_id)
        if skip_reason(b) is not None:
            raise RuntimeError(f"media {media_id} can no longer be tagged ({skip_reason(b)})")
        return plan_from(b, points)

    published = publish_replanning(
        ctx, plan, store=store, objects=objects, blobs=blobs, **publish_kw
    )
    return {**result, "changed": True, "content_version": published.content_version}


def run_fan_out(ctx: Context, *, rules: RuleSource, queue: DetectQueue) -> Mapping[str, Any]:
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
    def __init__(self, conn: psycopg.Connection[Any]) -> None:
        self._c = conn

    def load(self) -> list[PointRules]:
        rows = self._c.execute(
            "select id, detection_rules from app.grammar_point "
            "where deleted_at is null and detection_rules <> '[]'::jsonb order by id"
        ).fetchall()
        return [PointRules(str(r["id"]), r["detection_rules"]) for r in rows]


class PgSpanReader:
    def __init__(self, conn: psycopg.Connection[Any]) -> None:
        self._c = conn

    def detected(self, media_id: str) -> set[GrammarSpanRec]:
        rows = self._c.execute(
            "select sentence_id, grammar_point_id, start_offset, end_offset, rule_index "
            "from app.sentence_grammar_span where media_id = %s and origin = 'detected'",
            (uuid.UUID(media_id),),
        ).fetchall()
        return {
            GrammarSpanRec(
                str(r["sentence_id"]),
                str(r["grammar_point_id"]),
                r["start_offset"],
                r["end_offset"],
                r["rule_index"],
            )
            for r in rows
        }


class PgDetectQueue:
    def __init__(self, conn: psycopg.Connection[Any]) -> None:
        self._c = conn

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
                rules=PgRuleSource(c),
                base_reader=PgMediaReader(c),
                spans=PgSpanReader(c),
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
    "PgSpanReader",
    "RuleSource",
    "SpanReader",
    "dedupe_key",
    "make_grammar_detect",
    "run_detect_media",
    "run_fan_out",
]
