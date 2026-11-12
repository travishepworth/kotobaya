"""Worker half of the jpdb history hand-off: ``fetch_blob`` and ``apply_plan`` (T207c).

Protocol (contracts/api/openapi.yaml ``importJpdbHistory`` "Execution", C-9), steps 2 and 4:

* step 2: a ``jpdb-history-apply`` child of this parent that already ``succeeded`` is adopted (an earlier
  attempt got that far). Otherwise the child is inserted (``ON CONFLICT DO NOTHING`` on its dedupe key) and the
  queued or running row holding the key is used; if neither is found (the holder finished in between) the
  whole step repeats.
* step 4: poll the child. ``succeeded`` -> its ``result`` is the parent's result; ``failed`` -> the attempt fails
  with its ``error``; ``cancelled`` -> the parent is re-read: if it is no longer ours and running the attempt
  ends cancelled, otherwise it fails (a live parent is never finished as cancelled). A child still ``queued``
  after ``queued_timeout`` seconds fails the attempt (no server runner is claiming it).

Parent progress in [0.2, 0.99) is written by the server runner; this side only reads. The parent's own heartbeat
is the runner's.
"""

from __future__ import annotations

import json
import time
import uuid
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from typing import Any, Protocol

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.importers.jpdb_history.handler import ApplyPlan, Context, FetchBlob
from kotobaya_worker.importers.jpdb_history.plan import ImportPlan

INTERNAL_KIND = "jpdb-history-apply"
EVENTS_CHANNEL = "kotobaya_events"
DEFAULT_QUEUED_TIMEOUT = 600.0  # seconds a child may stay queued before the attempt fails
DEFAULT_POLL_INTERVAL = 1.0
MAX_ADOPT_ROUNDS = 5


def dedupe_key(parent: uuid.UUID) -> str:
    return f"{INTERNAL_KIND}:{parent}"


def uuid7() -> uuid.UUID:
    """A time-ordered UUID (version 7) for the child row."""
    ms = int(time.time() * 1000)
    rand = uuid.uuid4().int
    value = (ms & ((1 << 48) - 1)) << 80
    value |= 0x7 << 76
    value |= ((rand >> 64) & 0xFFF) << 64
    value |= 0b10 << 62
    value |= rand & ((1 << 62) - 1)
    return uuid.UUID(int=value)


@dataclass(frozen=True)
class ChildRow:
    status: str
    result: Mapping[str, Any] | None
    error: str | None


class ChildStore(Protocol):
    """The ``app.job`` access the protocol needs (Postgres in production, a fake in the race tests)."""

    def succeeded_result(self, parent: uuid.UUID) -> Mapping[str, Any] | None: ...

    def insert(self, child: uuid.UUID, parent: uuid.UUID, plan: dict[str, Any]) -> bool: ...

    def active_child(self, parent: uuid.UUID) -> uuid.UUID | None: ...

    def get(self, child: uuid.UUID) -> ChildRow | None: ...


class PgChildStore:
    def __init__(self, conn: psycopg.Connection[Any]) -> None:
        self._c = conn

    def succeeded_result(self, parent: uuid.UUID) -> Mapping[str, Any] | None:
        row = self._c.execute(
            "select result from app.job where kind = %s and payload->>'parent_job_id' = %s "
            "and status = 'succeeded' and result is not null order by finished_at desc limit 1",
            (INTERNAL_KIND, str(parent)),
        ).fetchone()
        return None if row is None else dict(row["result"])

    def insert(self, child: uuid.UUID, parent: uuid.UUID, plan: dict[str, Any]) -> bool:
        payload = {"parent_job_id": str(parent), "plan": plan}
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
        if row is None:
            return None
        return ChildRow(row["status"], row["result"], row["error"])


def adopt_or_create_child(
    store: ChildStore, parent: uuid.UUID, plan: ImportPlan
) -> uuid.UUID | Mapping[str, Any]:
    """Step 2. Returns the succeeded child's result, or the id of the queued or running child to wait for."""
    plan_json: dict[str, Any] | None = None
    for _ in range(MAX_ADOPT_ROUNDS):
        done = store.succeeded_result(parent)
        if done is not None:
            return done
        if plan_json is None:
            plan_json = plan.to_json()
        store.insert(uuid7(), parent, plan_json)
        active = store.active_child(parent)
        if active is not None:
            return active
        # Neither inserted nor held: the holder finished between the two statements. Look again.
    raise RuntimeError(
        "could not hand the plan to the server (no queued or running apply job found)"
    )


def wait_for_child(
    store: ChildStore,
    ctx: Context,
    child: uuid.UUID,
    *,
    poll_interval: float,
    queued_timeout: float,
    sleep: Callable[[float], None] = time.sleep,
    clock: Callable[[], float] = time.monotonic,
) -> Mapping[str, Any]:
    """Step 4."""
    not_running_since = clock()
    while True:
        ctx.check_cancelled()  # raises JobCancelled once the parent is no longer ours and running
        row = store.get(child)
        if row is None:
            raise RuntimeError(f"apply job {child} disappeared")
        if row.status == "succeeded":
            if row.result is None:
                raise RuntimeError(f"apply job {child} succeeded without a result")
            return dict(row.result)
        if row.status == "failed":
            raise RuntimeError(row.error or "apply job failed")
        if row.status == "cancelled":
            # Parent cancelled -> JobCancelled (the runner ends it). Parent still ours -> retry the attempt.
            ctx.check_cancelled()
            raise RuntimeError(
                "apply job was cancelled while the import is still running; retrying"
            )
        if row.status == "running":
            not_running_since = clock()
        elif clock() - not_running_since > queued_timeout:
            raise TimeoutError(
                f"apply job {child} stayed queued for {queued_timeout:.0f} s; "
                "is the server's import runner running?"
            )
        sleep(poll_interval)


def make_apply_plan(
    database_url: str,
    *,
    poll_interval: float = DEFAULT_POLL_INTERVAL,
    queued_timeout: float = DEFAULT_QUEUED_TIMEOUT,
) -> ApplyPlan:
    """``apply_plan(plan, ctx)`` for ``make_jpdb_history_import``; ``ctx`` is the runner's ``JobContext``."""

    def apply_plan(plan: ImportPlan, ctx: Context) -> Mapping[str, Any]:
        with psycopg.connect(
            to_conninfo(database_url), autocommit=True, row_factory=dict_row
        ) as conn:
            store = PgChildStore(conn)
            found = adopt_or_create_child(store, ctx.job_id, plan)
            if isinstance(found, uuid.UUID):
                return wait_for_child(
                    store, ctx, found, poll_interval=poll_interval, queued_timeout=queued_timeout
                )
            return found

    return apply_plan


def make_fetch_blob(config: Any | None = None) -> FetchBlob:
    """Blob hash -> bytes from object storage (``S3_*`` env, read on first use), verified against the hash."""
    import hashlib

    from kotobaya_worker.dict.package.s3 import S3Client, S3Config

    def fetch_blob(blob_hash: str) -> bytes:
        client = S3Client(config or S3Config.from_env())
        data = client.get_blob(blob_hash)
        if data is None:
            raise FileNotFoundError(f"blob {blob_hash} is not in object storage")
        if hashlib.sha256(data).hexdigest() != blob_hash:
            raise ValueError(f"blob {blob_hash} is corrupt (hash mismatch)")
        return data

    return fetch_blob
