"""T207c: ``apply_plan`` against a real Postgres, the server's runner played by a thread."""

from __future__ import annotations

import threading
import time
import uuid
from typing import Any

import psycopg
import pytest

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.importers.jpdb_history import ImportOptions, plan_import
from kotobaya_worker.importers.jpdb_history.apply import INTERNAL_KIND, make_apply_plan
from kotobaya_worker.importers.jpdb_history.ref import InMemoryRefLookup

RESULT = {"reviews_imported": 1, "reviews_id_collisions": 0}


class Ctx:
    def __init__(self, job_id: uuid.UUID) -> None:
        self.payload: dict[str, Any] = {}
        self.job_id = job_id

    def progress(self, fraction: float, message: str | None = None) -> None:
        pass

    def check_cancelled(self) -> None:
        pass


def _server_runner(db_url: str, stop: threading.Event) -> None:
    """Claims the internal child like the server's runner and ends it succeeded."""
    with psycopg.connect(to_conninfo(db_url), autocommit=True) as c:
        while not stop.is_set():
            row = c.execute(
                "update app.job set status = 'running', locked_by = 's', attempts = attempts + 1 "
                "where id = (select id from app.job where kind = %s and status = 'queued' limit 1) "
                "returning id, payload->'plan'->>'version', payload->>'parent_job_id'",
                (INTERNAL_KIND,),
            ).fetchone()
            if row is None:
                time.sleep(0.05)
                continue
            assert row[1] == "1"
            c.execute(
                "update app.job set status = 'succeeded', finished_at = now(), "
                'result = \'{"reviews_imported": 1, "reviews_id_collisions": 0}\'::jsonb, '
                "payload = payload - 'plan' where id = %s",
                (row[0],),
            )
            return


def test_apply_plan_hands_the_plan_over_waits_and_a_retry_adopts_the_succeeded_child(
    db_url: str,
) -> None:
    parent = uuid.uuid4()
    plan = plan_import({}, InMemoryRefLookup(), ImportOptions())
    apply_plan = make_apply_plan(db_url, poll_interval=0.05, queued_timeout=20)
    stop = threading.Event()
    t = threading.Thread(target=_server_runner, args=(db_url, stop))
    t.start()
    try:
        assert dict(apply_plan(plan, Ctx(parent))) == RESULT
    finally:
        stop.set()
        t.join()
    with psycopg.connect(to_conninfo(db_url), autocommit=True) as c:
        rows = c.execute(
            "select status, max_attempts, dedupe_key, payload->>'parent_job_id' from app.job where kind = %s",
            (INTERNAL_KIND,),
        ).fetchall()
        assert rows == [("succeeded", 1, f"{INTERNAL_KIND}:{parent}", str(parent))]
    # A retry of the parent (a new attempt) adopts the succeeded child: no second row, no server needed.
    assert dict(apply_plan(plan, Ctx(parent))) == RESULT
    with psycopg.connect(to_conninfo(db_url)) as c:
        assert c.execute("select count(*) from app.job").fetchone() == (1,)


def test_a_child_nobody_claims_fails_the_attempt(db_url: str) -> None:
    plan = plan_import({}, InMemoryRefLookup(), ImportOptions())
    apply_plan = make_apply_plan(db_url, poll_interval=0.05, queued_timeout=0.3)
    with pytest.raises(TimeoutError):
        apply_plan(plan, Ctx(uuid.uuid4()))
