"""Weekly CronJob entry point: one ``fsrs-optimize`` job per live preset (dedupe ``fsrs-optimize:<id>``).

``python -m kotobaya_worker.optimizer.enqueue``; needs ``DATABASE_URL``. A preset that already has a queued or
running job is skipped by the dedupe index.
"""

from __future__ import annotations

import json
import os
import uuid
from typing import Any

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.importers.jpdb_history.apply import EVENTS_CHANNEL, uuid7

KIND = "fsrs-optimize"


def enqueue_all(conn: psycopg.Connection[Any]) -> tuple[int, int]:
    """Returns ``(enqueued, already_queued)`` over the live presets."""
    presets = conn.execute(
        "select id from app.fsrs_params where deleted_at is null order by id"
    ).fetchall()
    enqueued = 0
    for p in presets:
        job: uuid.UUID = uuid7()
        cur = conn.execute(
            "insert into app.job (id, kind, payload, dedupe_key) "
            "values (%s, %s, %s, %s) on conflict do nothing",
            (job, KIND, Jsonb({"preset_id": str(p["id"])}), f"{KIND}:{p['id']}"),
        )
        if cur.rowcount:
            enqueued += 1
            conn.execute(
                "select pg_notify(%s, %s)",
                (EVENTS_CHANNEL, json.dumps({"type": "job.updated", "data": {"id": str(job)}})),
            )
    return enqueued, len(presets) - enqueued


def main() -> None:
    with psycopg.connect(
        to_conninfo(os.environ["DATABASE_URL"]), autocommit=True, row_factory=dict_row
    ) as conn:
        enqueued, skipped = enqueue_all(conn)
    print(f"fsrs-optimize: enqueued {enqueued}, already queued {skipped}")


if __name__ == "__main__":
    main()
