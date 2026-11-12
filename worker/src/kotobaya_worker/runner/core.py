"""Queue runner over ``app.job`` (no broker): SKIP LOCKED claiming, retries, heartbeats, cancel.

Rules inherited from the server side (T109a):
- every write after the claim (heartbeat, progress, result, retry, failure) is guarded by
  ``status = 'running' AND locked_by = <me>``, because cancel flips the row to ``cancelled`` at once;
- ``job.updated`` events come from the server polling ``app.job``; the ``pg_notify`` on
  ``kotobaya_events`` is only a "poll now" hint;
- only the API's closed ``JobKind`` list is used.
"""

from __future__ import annotations

import json
import logging
import os
import random
import socket
import threading
import time
import uuid
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from typing import Any

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from kotobaya_worker.dict.dburl import to_conninfo

log = logging.getLogger("kotobaya.worker.runner")

EVENTS_CHANNEL = "kotobaya_events"

API_JOB_KINDS: frozenset[str] = frozenset(
    {
        "dict-refresh",
        "media-import",
        "media-parse",
        "jpdb-deck-import",
        "jpdb-history-import",
        "fsrs-optimize",
        "tts",
        "deck-build",
    }
)

_COLUMNS = "id, kind, payload, attempts, max_attempts"


class JobCancelled(Exception):
    """Raised inside a handler (by ``ctx.check_cancelled`` / ``ctx.progress``) once the job was cancelled."""


Handler = Callable[["JobContext"], Mapping[str, Any] | None]


class Registry:
    """Handlers keyed by JobKind slug. Later tickets register theirs; tests register fakes."""

    def __init__(self) -> None:
        self._handlers: dict[str, Handler] = {}

    def register(self, kind: str, handler: Handler) -> None:
        if kind not in API_JOB_KINDS:
            raise ValueError(f"unknown job kind {kind!r}; the API's JobKind list is closed")
        if kind in self._handlers:
            raise ValueError(f"handler for {kind!r} already registered")
        self._handlers[kind] = handler

    def get(self, kind: str) -> Handler | None:
        return self._handlers.get(kind)

    def kinds(self) -> list[str]:
        return sorted(self._handlers)


@dataclass(frozen=True)
class RunnerConfig:
    conninfo: str
    worker_id: str = field(default_factory=lambda: f"{socket.gethostname()}-{uuid.uuid4().hex[:8]}")
    heartbeat_interval: float = 15.0  # contract: at least every 30 s
    stale_after: float = 300.0  # contract: older than 5 min is stuck
    poll_interval: float = 2.0
    backoff_base: float = 5.0
    backoff_cap: float = 600.0

    @staticmethod
    def from_env(env: Mapping[str, str] | None = None) -> RunnerConfig:
        e = os.environ if env is None else env
        return RunnerConfig(conninfo=to_conninfo(e["DATABASE_URL"]))


def _hint(conn: psycopg.Connection[Any], job_id: uuid.UUID) -> None:
    """Optional "poll now" hint for the server's JobWatcher; never required for correctness."""
    payload = json.dumps({"type": "job.updated", "data": {"id": str(job_id)}})
    conn.execute("select pg_notify(%s, %s)", (EVENTS_CHANNEL, payload))


class JobContext:
    """What a handler sees: the job, a progress reporter and the cancel flag."""

    def __init__(
        self,
        conn: psycopg.Connection[Any],
        worker_id: str,
        job_id: uuid.UUID,
        kind: str,
        payload: dict[str, Any],
        attempt: int,
        cancel_event: threading.Event,
    ) -> None:
        self._conn = conn
        self._worker_id = worker_id
        self.job_id = job_id
        self.kind = kind
        self.payload = payload
        self.attempt = attempt
        self._cancel = cancel_event

    def is_cancelled(self) -> bool:
        """True once the job is no longer ours and running (cancelled via the API, or reclaimed)."""
        if self._cancel.is_set():
            return True
        row = self._conn.execute(
            "select 1 from app.job where id = %s and status = 'running' and locked_by = %s",
            (self.job_id, self._worker_id),
        ).fetchone()
        if row is None:
            self._cancel.set()
        return self._cancel.is_set()

    def check_cancelled(self) -> None:
        if self.is_cancelled():
            raise JobCancelled(str(self.job_id))

    def progress(self, fraction: float, message: str | None = None) -> None:
        """Report progress in 0..1; raises JobCancelled if the job was cancelled meanwhile."""
        fraction = min(1.0, max(0.0, fraction))
        cur = self._conn.execute(
            "update app.job set progress = %s, progress_message = %s "
            "where id = %s and status = 'running' and locked_by = %s",
            (fraction, message, self.job_id, self._worker_id),
        )
        if cur.rowcount == 0:
            self._cancel.set()
            raise JobCancelled(str(self.job_id))
        _hint(self._conn, self.job_id)


class Runner:
    """One runner = one claim loop. Run several (threads or processes) for concurrency."""

    def __init__(self, config: RunnerConfig, registry: Registry) -> None:
        self.config = config
        self.registry = registry
        self._main: psycopg.Connection[Any] | None = None
        self._side: psycopg.Connection[Any] | None = None  # heartbeat + handler context

    # --- connections ---------------------------------------------------------------------
    def _connect(self) -> psycopg.Connection[Any]:
        return psycopg.connect(self.config.conninfo, autocommit=True, row_factory=dict_row)

    @property
    def main(self) -> psycopg.Connection[Any]:
        if self._main is None or self._main.closed:
            self._main = self._connect()
        return self._main

    @property
    def side(self) -> psycopg.Connection[Any]:
        if self._side is None or self._side.closed:
            self._side = self._connect()
        return self._side

    def close(self) -> None:
        for c in (self._main, self._side):
            if c is not None and not c.closed:
                c.close()

    # --- queue operations ----------------------------------------------------------------
    def reclaim_stale(self) -> int:
        """Requeue (or fail, if out of attempts) running jobs whose heartbeat went stale."""
        stale = (
            "status = 'running' and kind = any(%s) "
            "and coalesce(heartbeat_at, started_at, created_at) "
            "< now() - make_interval(secs => %s)"
        )
        args = (self.registry.kinds(), self.config.stale_after)
        with self.main.transaction():
            failed = self.main.execute(
                "update app.job set status = 'failed', finished_at = now(), locked_by = null, "
                "error = 'worker lost (heartbeat stale); attempts exhausted' "
                f"where {stale} and attempts >= max_attempts returning id",
                args,
            ).fetchall()
            requeued = self.main.execute(
                "update app.job set status = 'queued', locked_by = null, heartbeat_at = null, "
                "run_after = now(), error = 'worker lost (heartbeat stale)' "
                f"where {stale} and attempts < max_attempts returning id",
                args,
            ).fetchall()
            for r in failed + requeued:
                _hint(self.main, r["id"])
        return len(failed) + len(requeued)

    def claim(self) -> dict[str, Any] | None:
        kinds = self.registry.kinds()
        if not kinds:
            return None
        with self.main.transaction():
            row = self.main.execute(
                "update app.job set status = 'running', attempts = attempts + 1, "
                "locked_by = %s, started_at = now(), heartbeat_at = now(), "
                "progress = 0, progress_message = null "
                "where id = (select id from app.job where status = 'queued' "
                "and run_after <= now() and kind = any(%s) "
                "order by run_after, created_at for update skip locked limit 1) "
                f"returning {_COLUMNS}",
                (self.config.worker_id, kinds),
            ).fetchone()
            if row is not None:
                _hint(self.main, row["id"])
        return row

    def _backoff(self, attempts: int) -> float:
        base: float = min(self.config.backoff_cap, self.config.backoff_base * 2 ** (attempts - 1))
        return base * random.uniform(0.5, 1.0)

    def _finish_ok(self, job_id: uuid.UUID, result: Mapping[str, Any] | None) -> None:
        with self.main.transaction():
            cur = self.main.execute(
                "update app.job set status = 'succeeded', result = %s, progress = 1, "
                "finished_at = now(), locked_by = null, error = null "
                "where id = %s and status = 'running' and locked_by = %s",
                (
                    Jsonb(dict(result)) if result is not None else None,
                    job_id,
                    self.config.worker_id,
                ),
            )
            if cur.rowcount:
                _hint(self.main, job_id)

    def _finish_error(self, job: dict[str, Any], error: str) -> None:
        job_id = job["id"]
        with self.main.transaction():
            if job["attempts"] < job["max_attempts"]:
                cur = self.main.execute(
                    "update app.job set status = 'queued', locked_by = null, heartbeat_at = null, "
                    "error = %s, run_after = now() + make_interval(secs => %s) "
                    "where id = %s and status = 'running' and locked_by = %s",
                    (error, self._backoff(job["attempts"]), job_id, self.config.worker_id),
                )
            else:
                cur = self.main.execute(
                    "update app.job set status = 'failed', error = %s, finished_at = now(), "
                    "locked_by = null where id = %s and status = 'running' and locked_by = %s",
                    (error, job_id, self.config.worker_id),
                )
            if cur.rowcount:
                _hint(self.main, job_id)

    def _finish_cancelled(self, job_id: uuid.UUID) -> None:
        # The API normally set this already (guard makes us a no-op); cover a handler-raised cancel.
        with self.main.transaction():
            cur = self.main.execute(
                "update app.job set status = 'cancelled', finished_at = now(), locked_by = null "
                "where id = %s and status = 'running' and locked_by = %s",
                (job_id, self.config.worker_id),
            )
            if cur.rowcount:
                _hint(self.main, job_id)

    # --- execution -----------------------------------------------------------------------
    def _heartbeat_loop(
        self, job_id: uuid.UUID, stop: threading.Event, cancel: threading.Event
    ) -> None:
        while not stop.wait(self.config.heartbeat_interval):
            try:
                cur = self.side.execute(
                    "update app.job set heartbeat_at = now() "
                    "where id = %s and status = 'running' and locked_by = %s",
                    (job_id, self.config.worker_id),
                )
                if cur.rowcount == 0:
                    cancel.set()
                    return
            except psycopg.Error:
                log.warning("heartbeat failed for %s", job_id, exc_info=True)

    def execute(self, job: dict[str, Any]) -> None:
        job_id: uuid.UUID = job["id"]
        handler = self.registry.get(job["kind"])
        stop, cancel = threading.Event(), threading.Event()
        beat = threading.Thread(
            target=self._heartbeat_loop, args=(job_id, stop, cancel), daemon=True
        )
        beat.start()
        outcome: Callable[[], None]
        try:
            if handler is None:  # claim filters by kind; never crash silently anyway
                raise LookupError(f"no handler for {job['kind']}")
            ctx = JobContext(
                self.side, self.config.worker_id, job_id, job["kind"], job["payload"],
                job["attempts"], cancel,
            )  # fmt: skip
            result = handler(ctx)
            outcome = lambda: self._finish_ok(job_id, result)
        except JobCancelled:
            outcome = lambda: self._finish_cancelled(job_id)
        except Exception as e:
            log.warning(
                "job %s (%s) attempt %s failed", job_id, job["kind"], job["attempts"],
                exc_info=True,
            )  # fmt: skip
            msg = f"{type(e).__name__}: {e}"
            outcome = lambda: self._finish_error(job, msg)
        stop.set()
        beat.join()
        outcome()

    def run_once(self) -> bool:
        """Claim and run at most one job. Returns False when nothing was claimable."""
        job = self.claim()
        if job is None:
            return False
        self.execute(job)
        return True

    def run_until_idle(self) -> int:
        """Drain the queue (used by tests). Returns the number of jobs run."""
        n = 0
        while self.run_once():
            n += 1
        return n

    def run_forever(self, stop: threading.Event) -> None:
        last_reclaim = 0.0
        while not stop.is_set():
            try:
                if time.monotonic() - last_reclaim >= self.config.heartbeat_interval:
                    self.reclaim_stale()
                    last_reclaim = time.monotonic()
                if self.run_once():
                    continue
            except psycopg.Error:
                log.warning("database error in runner loop; reconnecting", exc_info=True)
                self.close()
            stop.wait(self.config.poll_interval)
        self.close()
