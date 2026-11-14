"""Runner handler for ``deck-build`` (contracts/db/VOCAB_LISTS.md 4.2-4.4, 5.1).

Plan (one REPEATABLE READ transaction: deck, current release, resolved list key, ordered items, installed
items) -> unchanged? end ``changed: false`` : write the deck-items archive, upload it, hand it to the server's
``deck-publish`` runner through a child ``app.job`` row, wait for it. ``stale_base`` re-plans (at most 3 publishes).
"""

from __future__ import annotations

import json
import re
import time
import uuid
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from typing import Any

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.package.s3 import S3Client, S3Config
from kotobaya_worker.importers.jpdb_history.apply import EVENTS_CHANNEL, ChildRow
from kotobaya_worker.importers.jpdb_history.handler import Context
from kotobaya_worker.media.publish.blobs import (
    BlobRef,
    BlobTable,
    ObjectStore,
    PgBlobTable,
    S3ObjectStore,
    upload_snapshot,
)
from kotobaya_worker.media.publish.handoff import (
    DEFAULT_POLL_INTERVAL,
    DEFAULT_QUEUED_TIMEOUT,
    MAX_PUBLISHES,
    PROGRESS_CAP,
    PublishFailed,
    StaleBase,
    adopt_or_create_child,
)
from kotobaya_worker.media.publish.snapshot import DeckArchive, DeckItemRec, write_deck_items

KIND = "deck-build"
CHILD_KIND = "deck-publish"
FREQ_BANDS = (1000, 2000, 5000, 10000, 20000)
_FREQ_KEY = re.compile(r"^freq-(.+)-(1000|2000|5000|10000|20000)$")
# Codes where a retry reaches the same error (VOCAB_LISTS.md 4.4 step 2, 5.2).
DETERMINISTIC = frozenset(
    {"unknown_deck", "invalid_payload", "deck_deleted", "no_release", "unknown_list", "empty_list",
     "unsupported_format", "invalid_snapshot"}
)  # fmt: skip
_UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


class DeckBuildError(Exception):
    """The job fails with ``error = '<code>: <message>'`` (no type-name prefix)."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.job_error = f"{code}: {message}"
        self.non_retryable = code in DETERMINISTIC


@dataclass(frozen=True)
class Plan:
    deck_id: str
    list_key: str
    base_version: int
    items: list[int]  # entry seqs in deck order
    unmatched: int
    installed: set[tuple[int | None, int]]  # live (entry_seq, position) rows

    def positioned(self) -> list[tuple[int, int]]:
        return [(seq, 1024 * k) for k, seq in enumerate(self.items, start=1)]

    @property
    def unchanged(self) -> bool:
        return self.base_version >= 1 and self.installed == set(self.positioned())


class PgPlanner:
    """Reads the plan in one REPEATABLE READ transaction (``conn`` must be autocommit)."""

    def __init__(self, conn: psycopg.Connection[Any]) -> None:
        self._c = conn

    def plan(self, deck_id: str) -> Plan:
        if not _UUID.match(deck_id):
            raise DeckBuildError("invalid_payload", f"deck_id {deck_id!r} is not a UUID")
        with self._c.transaction(), self._c.cursor(row_factory=dict_row) as cur:
            cur.execute("set transaction isolation level repeatable read")
            deck = cur.execute(
                "select type, list_key, content_version, deleted_at from app.deck where id = %s",
                (deck_id,),
            ).fetchone()
            if deck is None:
                raise DeckBuildError("unknown_deck", f"no deck {deck_id}")
            if deck["type"] != "list":
                raise DeckBuildError("invalid_payload", f"deck {deck_id} is a {deck['type']} deck")
            if deck["deleted_at"] is not None:
                raise DeckBuildError("deck_deleted", f"deck {deck_id} is deleted")
            if cur.execute("select 1 from ref.dict_release where is_current").fetchone() is None:
                raise DeckBuildError("no_release", "no current dictionary release")
            key: str = deck["list_key"]
            items, unmatched = self._items(cur, key)
            if not items:
                raise DeckBuildError("empty_list", f"list {key} has no live items")
            installed = {
                (r["entry_seq"], int(r["position"]))
                for r in cur.execute(
                    "select entry_seq, position from app.deck_item "
                    "where deck_id = %s and deleted_at is null",
                    (deck_id,),
                ).fetchall()
            }
        return Plan(deck_id, key, int(deck["content_version"]), items, unmatched, installed)

    @staticmethod
    def _items(cur: psycopg.Cursor[Any], key: str) -> tuple[list[int], int]:
        """Resolve the key (4.2) and order the live items (4.3). Returns (seqs, unmatched)."""
        vl = cur.execute(
            "select unmatched_rows from ref.vocab_list where id = %s", (key,)
        ).fetchone()
        if vl is not None:
            rows = cur.execute(
                "select i.seq from ref.vocab_list_item i join ref.dict_entry d on d.seq = i.seq "
                "where i.list_id = %s and d.retired_release_id is null "
                "order by d.freq_rank asc nulls last, i.position, i.seq",
                (key,),
            ).fetchall()
            return [int(r["seq"]) for r in rows], int(vl["unmatched_rows"])
        m = _FREQ_KEY.match(key)
        if m is not None:
            lists = cur.execute(
                "select id from ref.freq_list where replace(id, '_', '-') = %s", (m.group(1),)
            ).fetchall()
            if len(lists) == 1:
                rows = cur.execute(
                    "select f.seq from ref.entry_freq f join ref.dict_entry d on d.seq = f.seq "
                    "where f.list_id = %s and f.rank <= %s and d.retired_release_id is null "
                    "order by f.rank, f.seq",
                    (lists[0]["id"], int(m.group(2))),
                ).fetchall()
                return [int(r["seq"]) for r in rows], 0
        raise DeckBuildError("unknown_list", f"list key {key!r} does not resolve")


class PgDeckPublishStore:
    """``app.job`` access for the ``deck-publish`` child (same shape as the media publish store)."""

    def __init__(self, conn: psycopg.Connection[Any]) -> None:
        self._c = conn

    @staticmethod
    def dedupe_key(parent: uuid.UUID) -> str:
        return f"{CHILD_KIND}:{parent}"

    def succeeded_result(self, parent: uuid.UUID) -> Mapping[str, Any] | None:
        row = self._c.execute(
            "select result from app.job where kind = %s and payload->>'parent_job_id' = %s "
            "and status = 'succeeded' and result is not null order by finished_at desc limit 1",
            (CHILD_KIND, str(parent)),
        ).fetchone()
        return None if row is None else dict(row["result"])

    def insert(self, child: uuid.UUID, parent: uuid.UUID, payload: dict[str, Any]) -> bool:
        cur = self._c.execute(
            "insert into app.job (id, kind, payload, dedupe_key, max_attempts) "
            "values (%s, %s, %s, %s, 1) on conflict do nothing",
            (child, CHILD_KIND, Jsonb(payload), self.dedupe_key(parent)),
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
            (self.dedupe_key(parent),),
        ).fetchone()
        return None if row is None else row["id"]

    def get(self, child: uuid.UUID) -> ChildRow | None:
        row = self._c.execute(
            "select status, result, error from app.job where id = %s", (child,)
        ).fetchone()
        return None if row is None else ChildRow(row["status"], row["result"], row["error"])


def _wait(
    store: PgDeckPublishStore,
    ctx: Context,
    child: uuid.UUID,
    *,
    poll_interval: float,
    queued_timeout: float,
    sleep: Callable[[float], None],
    clock: Callable[[], float],
) -> Mapping[str, Any]:
    not_running_since = clock()
    while True:
        ctx.check_cancelled()
        row = store.get(child)
        if row is None:
            raise RuntimeError(f"{CHILD_KIND} job {child} disappeared")
        if row.status == "succeeded":
            if row.result is None:
                raise RuntimeError(f"{CHILD_KIND} job {child} succeeded without a result")
            return row.result
        if row.status == "failed":
            error = row.error or f"{CHILD_KIND} failed"
            raise (StaleBase if error.startswith("stale_base:") else PublishFailed)(error)
        if row.status == "cancelled":
            ctx.check_cancelled()
            raise RuntimeError(f"{CHILD_KIND} job was cancelled while its parent is still running")
        if row.status == "running":
            not_running_since = clock()
        elif clock() - not_running_since > queued_timeout:
            raise TimeoutError(
                f"{CHILD_KIND} job {child} stayed queued for {queued_timeout:.0f} s; "
                "is the server's publish runner running?"
            )
        sleep(poll_interval)


def _unchanged_result(plan: Plan) -> dict[str, Any]:
    return {
        "deck_id": plan.deck_id,
        "content_version": plan.base_version,
        "items": len(plan.items),
        "list_key": plan.list_key,
        "changed": False,
        "unmatched": plan.unmatched,
    }


def build_payload(plan: Plan, blob: BlobRef, parent: uuid.UUID) -> dict[str, Any]:
    """The ``deck-publish`` payload (``media/deck-publish.v1.schema.json``)."""
    return {
        "format_version": 1,
        "parent_job_id": str(parent),
        "deck_id": plan.deck_id,
        "list_key": plan.list_key,
        "base_content_version": plan.base_version,
        "deck_items": {"blob_hash": blob.blob_hash, "size_bytes": blob.size_bytes},
        "unmatched": plan.unmatched,
    }


def run_deck_build(
    ctx: Context,
    *,
    planner: PgPlanner,
    store: PgDeckPublishStore,
    objects: ObjectStore,
    blobs: BlobTable,
    poll_interval: float = DEFAULT_POLL_INTERVAL,
    queued_timeout: float = DEFAULT_QUEUED_TIMEOUT,
    sleep: Callable[[float], None] = time.sleep,
    clock: Callable[[], float] = time.monotonic,
) -> Mapping[str, Any]:
    done = store.succeeded_result(ctx.job_id)  # step 1: an earlier attempt got that far
    if done is not None:
        ctx.progress(1.0, "done")
        return done
    deck_id = ctx.payload.get("deck_id")
    if not isinstance(deck_id, str):
        raise DeckBuildError("invalid_payload", "payload.deck_id is missing")
    for n in range(MAX_PUBLISHES + 1):
        ctx.progress(0.05, "planning")
        plan = planner.plan(deck_id)
        if plan.unchanged:
            return _unchanged_result(plan)
        archive = DeckArchive(
            deck_id, "list", plan.base_version + 1,
            [DeckItemRec(seq, pos, 0) for seq, pos in plan.positioned()],
        )  # fmt: skip
        blob = upload_snapshot(write_deck_items(archive), objects, blobs)
        ctx.progress(min(PROGRESS_CAP, 0.5), "publishing")
        found = adopt_or_create_child(store, ctx.job_id, build_payload(plan, blob, ctx.job_id))
        try:
            if isinstance(found, uuid.UUID):
                return _wait(
                    store, ctx, found, poll_interval=poll_interval,
                    queued_timeout=queued_timeout, sleep=sleep, clock=clock,
                )  # fmt: skip
            return found
        except StaleBase:
            if n == MAX_PUBLISHES:
                raise
        except PublishFailed as e:
            code = str(e).split(":", 1)[0]
            if re.fullmatch(r"[a-z_]+", code):
                raise DeckBuildError(code, str(e).split(":", 1)[1].strip()) from e
            raise
    raise AssertionError("unreachable")


def make_deck_build(
    database_url: str, objects: ObjectStore | None = None
) -> Callable[[Context], Mapping[str, Any]]:
    """Build the runner handler (the object store defaults to the S3 bucket named by the environment)."""

    def handler(ctx: Context) -> Mapping[str, Any]:
        store_objects = objects or S3ObjectStore(S3Client(S3Config.from_env()))
        with psycopg.connect(to_conninfo(database_url), autocommit=True, row_factory=dict_row) as c:
            return run_deck_build(
                ctx,
                planner=PgPlanner(c),
                store=PgDeckPublishStore(c),
                objects=store_objects,
                blobs=PgBlobTable(c),
            )

    return handler


__all__ = ["KIND", "DeckBuildError", "Plan", "build_payload", "make_deck_build", "run_deck_build"]
