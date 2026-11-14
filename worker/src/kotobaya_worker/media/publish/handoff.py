"""The worker half of the ``media-publish`` hand-off (contracts/db/MEDIA_PIPELINE.md 3.1, 3.2).

``publish`` is steps 1, 3 and 4 plus the upload of step 2 for one plan: adopt a succeeded child, else build and
upload both archives, insert or reuse the child, wait for it. Planning (steps 2's extract/parse/aggregate) is the
caller's; on ``StaleBase`` it re-plans (``publish_replanning`` does that, at most 3 publishes).

The child-job protocol is copied from ``importers.jpdb_history.apply`` (its ``ChildStore`` and
``adopt_or_create_child`` are specific to the history plan payload and kind); ``uuid7`` and ``ChildRow`` are reused.
"""

from __future__ import annotations

import json
import time
import uuid
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from typing import Any, Protocol

import psycopg
from psycopg.types.json import Jsonb

from kotobaya_worker.importers.jpdb_history.apply import EVENTS_CHANNEL, ChildRow, uuid7
from kotobaya_worker.importers.jpdb_history.handler import Context
from kotobaya_worker.media.publish.blobs import BlobRef, BlobTable, ObjectStore, upload_snapshot
from kotobaya_worker.media.publish.snapshot import (
    ContentArchive,
    DeckArchive,
    write_content,
    write_deck_items,
)

INTERNAL_KIND = "media-publish"
DEFAULT_QUEUED_TIMEOUT = 600.0
DEFAULT_POLL_INTERVAL = 1.0
MAX_ADOPT_ROUNDS = 5
MAX_PUBLISHES = 3  # per attempt (MEDIA_PIPELINE.md 3.2 step 4)
PROGRESS_CAP = 0.9


def dedupe_key(parent: uuid.UUID) -> str:
    return f"{INTERNAL_KIND}:{parent}"


class PublishFailed(RuntimeError):
    """The child ended ``failed``; the message is the child's ``error`` (``<code>: <message>``)."""


class StaleBase(PublishFailed):
    """The child ended ``failed`` with ``stale_base:``: the media moved on; re-plan on the new base."""


@dataclass(frozen=True)
class DeckOptions:
    priority: int = 0
    enabled: bool = True
    new_cards_per_day: int | None = None


@dataclass(frozen=True)
class CreateSpec:
    """``create`` of the payload; required iff ``base_content_version`` = 0."""

    title: str
    kind: str
    source: str
    jpdb_id: str | None = None
    deck: DeckOptions = DeckOptions()

    def to_json(self) -> dict[str, Any]:
        return {
            "title": self.title,
            "kind": self.kind,
            "source": self.source,
            "jpdb_id": self.jpdb_id,
            "deck": {
                "priority": self.deck.priority,
                "enabled": self.deck.enabled,
                "new_cards_per_day": self.deck.new_cards_per_day,
            },
        }


@dataclass(frozen=True)
class PublishRequest:
    media_id: str
    base_content_version: int
    content: ContentArchive
    deck_items: DeckArchive
    create: CreateSpec | None = None
    unmapped: Sequence[Mapping[str, Any]] = ()
    jpdb: Mapping[str, Any] | None = None

    def __post_init__(self) -> None:
        version = self.base_content_version + 1
        if (self.create is None) != (self.base_content_version != 0):
            raise ValueError("create must be set iff base_content_version = 0")
        if self.content.media_id != self.media_id or self.deck_items.deck_id != self.media_id:
            raise ValueError("archive media_id/deck_id must equal media_id")
        if self.content.content_version != version or self.deck_items.content_version != version:
            raise ValueError("archives must be published as base_content_version + 1")


@dataclass(frozen=True)
class JobResultMedia:
    media_id: str
    content_version: int
    segments: int
    sentences: int
    tokens: int
    unmapped_tokens: int
    unique_entries: int
    unmapped: list[dict[str, Any]]

    @staticmethod
    def from_json(d: Mapping[str, Any]) -> JobResultMedia:
        return JobResultMedia(
            media_id=str(d["media_id"]),
            content_version=int(d["content_version"]),
            segments=int(d["segments"]),
            sentences=int(d["sentences"]),
            tokens=int(d["tokens"]),
            unmapped_tokens=int(d["unmapped_tokens"]),
            unique_entries=int(d["unique_entries"]),
            unmapped=[dict(u) for u in d["unmapped"]],
        )


def build_payload(
    req: PublishRequest, content: BlobRef, deck: BlobRef, parent: uuid.UUID
) -> dict[str, Any]:
    return {
        "format_version": 1,
        "parent_job_id": str(parent),
        "media_id": req.media_id,
        "base_content_version": req.base_content_version,
        "create": None if req.create is None else req.create.to_json(),
        "content": {"blob_hash": content.blob_hash, "size_bytes": content.size_bytes},
        "deck_items": {"blob_hash": deck.blob_hash, "size_bytes": deck.size_bytes},
        "unmapped": [dict(u) for u in req.unmapped],
        "jpdb": None if req.jpdb is None else dict(req.jpdb),
    }


class PublishStore(Protocol):
    """The ``app.job`` access the protocol needs (Postgres in production, a fake in unit tests)."""

    def succeeded_result(self, parent: uuid.UUID) -> Mapping[str, Any] | None: ...

    def insert(self, child: uuid.UUID, parent: uuid.UUID, payload: dict[str, Any]) -> bool: ...

    def active_child(self, parent: uuid.UUID) -> uuid.UUID | None: ...

    def get(self, child: uuid.UUID) -> ChildRow | None: ...


class PgPublishStore:
    def __init__(self, conn: psycopg.Connection[Any]) -> None:
        self._c = conn

    def succeeded_result(self, parent: uuid.UUID) -> Mapping[str, Any] | None:
        row = self._c.execute(
            "select result from app.job where kind = %s and payload->>'parent_job_id' = %s "
            "and status = 'succeeded' and result is not null order by finished_at desc limit 1",
            (INTERNAL_KIND, str(parent)),
        ).fetchone()
        return None if row is None else dict(row["result"])

    def insert(self, child: uuid.UUID, parent: uuid.UUID, payload: dict[str, Any]) -> bool:
        cur = self._c.execute(
            "insert into app.job (id, kind, payload, dedupe_key, max_attempts) "
            "values (%s, %s, %s, %s, 1) on conflict do nothing",
            (child, INTERNAL_KIND, Jsonb(payload), dedupe_key(parent)),
        )
        if cur.rowcount:
            self._c.execute(
                "select pg_notify(%s, %s)",
                (EVENTS_CHANNEL, json.dumps({"type": "job.updated", "data": {"id": str(child)}})),
            )
        return bool(cur.rowcount)

    def active_child(self, parent: uuid.UUID) -> uuid.UUID | None:
        row = self._c.execute(
            "select id from app.job where dedupe_key = %s and status in ('queued', 'running')",
            (dedupe_key(parent),),
        ).fetchone()
        return None if row is None else row["id"]

    def get(self, child: uuid.UUID) -> ChildRow | None:
        row = self._c.execute(
            "select status, result, error from app.job where id = %s", (child,)
        ).fetchone()
        return None if row is None else ChildRow(row["status"], row["result"], row["error"])


def adopt_or_create_child(
    store: PublishStore, parent: uuid.UUID, payload: dict[str, Any]
) -> uuid.UUID | Mapping[str, Any]:
    """Step 3 (and step 1): the succeeded child's result, or the id of the queued/running child to wait for."""
    for _ in range(MAX_ADOPT_ROUNDS):
        done = store.succeeded_result(parent)
        if done is not None:
            return done
        store.insert(uuid7(), parent, payload)
        active = store.active_child(parent)
        if active is not None:
            return active
    raise RuntimeError("could not hand the snapshots to the server (no queued or running child)")


def wait_for_child(
    store: PublishStore,
    ctx: Context,
    child: uuid.UUID,
    *,
    poll_interval: float,
    queued_timeout: float,
    sleep: Callable[[float], None] = time.sleep,
    clock: Callable[[], float] = time.monotonic,
) -> JobResultMedia:
    """Step 4: poll, honouring parent cancellation (``ctx.check_cancelled`` raises ``JobCancelled``)."""
    not_running_since = clock()
    while True:
        ctx.check_cancelled()
        row = store.get(child)
        if row is None:
            raise RuntimeError(f"media-publish job {child} disappeared")
        if row.status == "succeeded":
            if row.result is None:
                raise RuntimeError(f"media-publish job {child} succeeded without a result")
            return JobResultMedia.from_json(row.result)
        if row.status == "failed":
            error = row.error or "media-publish failed"
            raise (StaleBase if error.startswith("stale_base:") else PublishFailed)(error)
        if row.status == "cancelled":
            ctx.check_cancelled()
            raise RuntimeError("media-publish job was cancelled while its parent is still running")
        if row.status == "running":
            not_running_since = clock()
        elif clock() - not_running_since > queued_timeout:
            raise TimeoutError(
                f"media-publish job {child} stayed queued for {queued_timeout:.0f} s; "
                "is the server's publish runner running?"
            )
        sleep(poll_interval)


def publish(
    ctx: Context,
    req: PublishRequest,
    *,
    store: PublishStore,
    objects: ObjectStore,
    blobs: BlobTable,
    poll_interval: float = DEFAULT_POLL_INTERVAL,
    queued_timeout: float = DEFAULT_QUEUED_TIMEOUT,
    sleep: Callable[[float], None] = time.sleep,
    clock: Callable[[], float] = time.monotonic,
    upload_progress: float | None = None,
) -> JobResultMedia:
    """One publish of ``req`` for the job ``ctx`` (the parent). Raises ``StaleBase`` / ``PublishFailed``."""
    done = store.succeeded_result(ctx.job_id)  # step 1: an earlier attempt got that far
    if done is not None:
        return JobResultMedia.from_json(done)
    if upload_progress is not None:
        ctx.progress(upload_progress, "uploading snapshots")
    content = upload_snapshot(write_content(req.content), objects, blobs)
    deck = upload_snapshot(write_deck_items(req.deck_items), objects, blobs)
    ctx.progress(PROGRESS_CAP, "publishing")
    found = adopt_or_create_child(store, ctx.job_id, build_payload(req, content, deck, ctx.job_id))
    if isinstance(found, uuid.UUID):
        return wait_for_child(
            store, ctx, found, poll_interval=poll_interval, queued_timeout=queued_timeout,
            sleep=sleep, clock=clock,
        )  # fmt: skip
    return JobResultMedia.from_json(found)


def publish_replanning(
    ctx: Context, plan: Callable[[], PublishRequest], **deps: Any
) -> JobResultMedia:
    """``plan()`` re-reads the base and builds the request; on ``stale_base`` plan again (4th stale fails)."""
    for n in range(MAX_PUBLISHES + 1):
        try:
            return publish(ctx, plan(), **deps)
        except StaleBase:
            if n == MAX_PUBLISHES:
                raise
    raise AssertionError("unreachable")
