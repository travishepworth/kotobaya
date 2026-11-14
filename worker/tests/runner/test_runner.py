"""Runner acceptance tests against a real Postgres (see conftest)."""

from __future__ import annotations

import logging
import threading
import time
import uuid
from collections.abc import Mapping
from typing import Any

import psycopg
import pytest

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.runner import JobContext, Registry, Runner, RunnerConfig


def _cfg(db_url: str, **kw: Any) -> RunnerConfig:
    base: dict[str, Any] = {
        "heartbeat_interval": 0.05,
        "poll_interval": 0.05,
        "backoff_base": 0.02,
        "stale_after": 300.0,
    }
    base.update(kw)
    return RunnerConfig(conninfo=to_conninfo(db_url), **base)


def _sql(db_url: str) -> psycopg.Connection[Any]:
    return psycopg.connect(to_conninfo(db_url), autocommit=True)


def _insert(
    db_url: str, kind: str = "deck-build", n: int = 1, max_attempts: int = 3
) -> list[uuid.UUID]:
    ids = [uuid.uuid4() for _ in range(n)]
    with _sql(db_url) as c, c.cursor() as cur:
        cur.executemany(
            "insert into app.job (id, kind, max_attempts) values (%s, %s, %s)",
            [(i, kind, max_attempts) for i in ids],
        )
    return ids


def _row(db_url: str, job_id: uuid.UUID) -> dict[str, Any]:
    with _sql(db_url) as c:
        cur = c.execute(
            "select status, attempts, error, finished_at, locked_by, progress, result "
            "from app.job where id = %s",
            (job_id,),
        )
        cols = [d.name for d in cur.description or []]
        row = cur.fetchone()
        assert row is not None
        return dict(zip(cols, row, strict=True))


def _drain_until(runner: Runner, db_url: str, job_id: uuid.UUID, timeout: float = 20) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        runner.run_until_idle()
        if _row(db_url, job_id)["finished_at"] is not None:
            return
        time.sleep(0.02)
    raise AssertionError("job did not finish")


def test_1000_jobs_three_concurrent_runners_exactly_once(db_url: str) -> None:
    ids = _insert(db_url, "tts", n=1000)
    seen: dict[uuid.UUID, int] = {}
    lock = threading.Lock()

    def handler(ctx: JobContext) -> Mapping[str, Any]:
        with lock:
            seen[ctx.job_id] = seen.get(ctx.job_id, 0) + 1
        return {"ok": True}

    def work() -> None:
        reg = Registry()
        reg.register("tts", handler)
        r = Runner(_cfg(db_url), reg)
        try:
            r.run_until_idle()
        finally:
            r.close()

    threads = [threading.Thread(target=work) for _ in range(3)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=300)

    assert set(seen) == set(ids)
    assert all(v == 1 for v in seen.values())
    with _sql(db_url) as c:
        rows = c.execute("select status, attempts, count(*) from app.job group by 1, 2").fetchall()
    assert rows == [("succeeded", 1, 1000)]


def test_retry_backoff_then_success_attempts_3(db_url: str) -> None:
    (jid,) = _insert(db_url)
    calls: list[int] = []

    def flaky(ctx: JobContext) -> None:
        calls.append(ctx.attempt)
        if ctx.attempt < 3:
            raise RuntimeError("boom")

    reg = Registry()
    reg.register("deck-build", flaky)
    r = Runner(_cfg(db_url), reg)
    assert r.run_once()
    mid = _row(db_url, jid)
    assert mid["status"] == "queued" and "boom" in mid["error"] and mid["finished_at"] is None
    _drain_until(r, db_url, jid)
    done = _row(db_url, jid)
    assert (done["status"], done["attempts"]) == ("succeeded", 3)
    assert calls == [1, 2, 3]
    r.close()


def test_retries_exhausted_ends_failed(db_url: str) -> None:
    (jid,) = _insert(db_url, max_attempts=2)

    def always(ctx: JobContext) -> None:
        raise ValueError("nope")

    reg = Registry()
    reg.register("deck-build", always)
    r = Runner(_cfg(db_url), reg)
    _drain_until(r, db_url, jid)
    row = _row(db_url, jid)
    assert (row["status"], row["attempts"]) == ("failed", 2)
    assert "nope" in row["error"] and row["finished_at"] is not None
    r.close()


def test_crash_recovery_stale_heartbeat_reclaimed_once(db_url: str) -> None:
    (jid,) = _insert(db_url)
    with _sql(db_url) as c:
        c.execute(
            "update app.job set status='running', attempts=1, locked_by='dead', "
            "started_at=now() - interval '10 min', heartbeat_at=now() - interval '10 min' "
            "where id=%s",
            (jid,),
        )
    reg = Registry()
    reg.register("deck-build", lambda ctx: None)
    a, b = Runner(_cfg(db_url), reg), Runner(_cfg(db_url), reg)
    assert a.reclaim_stale() + b.reclaim_stale() == 1
    assert a.reclaim_stale() == 0
    assert _row(db_url, jid)["status"] == "queued"
    a.run_until_idle()
    row = _row(db_url, jid)
    assert (row["status"], row["attempts"]) == ("succeeded", 2)
    a.close()
    b.close()


def test_stale_heartbeat_out_of_attempts_fails(db_url: str) -> None:
    (jid,) = _insert(db_url, max_attempts=1)
    with _sql(db_url) as c:
        c.execute(
            "update app.job set status='running', attempts=1, locked_by='dead', "
            "heartbeat_at=now() - interval '10 min' where id=%s",
            (jid,),
        )
    reg = Registry()
    reg.register("deck-build", lambda ctx: None)
    r = Runner(_cfg(db_url), reg)
    assert r.reclaim_stale() == 1
    row = _row(db_url, jid)
    assert row["status"] == "failed" and row["finished_at"] is not None
    r.close()


def test_cancel_running_job(db_url: str) -> None:
    (jid,) = _insert(db_url)
    started = threading.Event()
    exited = threading.Event()

    def slow(ctx: JobContext) -> None:
        started.set()
        try:
            for i in range(1000):
                ctx.progress(i / 1000, f"step {i}")
                ctx.check_cancelled()
                time.sleep(0.01)
        finally:
            exited.set()

    reg = Registry()
    reg.register("deck-build", slow)
    r = Runner(_cfg(db_url), reg)
    t = threading.Thread(target=r.run_until_idle)
    t.start()
    assert started.wait(10)
    # what the API's cancel does (T109a): flip at once.
    with _sql(db_url) as c:
        c.execute(
            "update app.job set status='cancelled', finished_at=now(), locked_by=null where id=%s",
            (jid,),
        )
    t.join(10)
    assert exited.is_set() and not t.is_alive()
    row = _row(db_url, jid)
    assert row["status"] == "cancelled" and row["finished_at"] is not None
    assert row["result"] is None
    r.close()


def test_progress_and_result_recorded(db_url: str) -> None:
    (jid,) = _insert(db_url)

    def h(ctx: JobContext) -> Mapping[str, Any]:
        ctx.progress(0.5, "half")
        with _sql(db_url) as c:
            assert c.execute(
                "select progress, progress_message from app.job where id=%s", (ctx.job_id,)
            ).fetchone() == (0.5, "half")
        return {"n": 1}

    reg = Registry()
    reg.register("deck-build", h)
    r = Runner(_cfg(db_url), reg)
    r.run_until_idle()
    row = _row(db_url, jid)
    assert row["status"] == "succeeded" and row["result"] == {"n": 1} and row["progress"] == 1
    r.close()


def test_unregistered_kind_left_queued(db_url: str) -> None:
    (jid,) = _insert(db_url, "tts")
    reg = Registry()
    reg.register("deck-build", lambda ctx: None)
    r = Runner(_cfg(db_url), reg)
    assert not r.run_once()
    assert _row(db_url, jid)["status"] == "queued"
    with pytest.raises(ValueError):
        reg.register("not-a-kind", lambda ctx: None)
    r.close()


def test_failures_log_stack_with_job_id_and_final_attempt_is_error(
    db_url: str, caplog: pytest.LogCaptureFixture
) -> None:
    """OBS-1w: every handler record carries job_id/kind; failures log the stack trace."""
    from kotobaya_worker.logs import JobContextFilter, JsonFormatter

    (job_id,) = _insert(db_url, "tts", max_attempts=2)

    def handler(ctx: JobContext) -> Mapping[str, Any]:
        logging.getLogger("kotobaya.test").info("inside handler")
        raise RuntimeError("boom")

    reg = Registry()
    reg.register("tts", handler)
    runner = Runner(_cfg(db_url), reg)
    flt = JobContextFilter()
    caplog.handler.addFilter(flt)
    caplog.set_level(logging.INFO)
    try:
        _drain_until(runner, db_url, job_id)
    finally:
        runner.close()
        caplog.handler.removeFilter(flt)

    inside = [r for r in caplog.records if r.getMessage() == "inside handler"]
    assert inside and all(
        getattr(r, "job_id", None) == str(job_id) and getattr(r, "job_kind", None) == "tts"
        for r in inside
    )
    fails = [
        r for r in caplog.records if "failed" in r.getMessage() and str(job_id) in r.getMessage()
    ]
    assert [r.levelno for r in fails] == [logging.WARNING, logging.ERROR]
    assert all(r.exc_info is not None for r in fails)
    line = JsonFormatter().format(fails[-1])
    assert "\n" not in line and '"exc": "Traceback' in line and str(job_id) in line
