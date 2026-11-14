"""Queue a ``deck-build`` for every live list deck (VOCAB_LISTS.md 3.4)."""

from __future__ import annotations

import json
from typing import Any

import psycopg

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.importers.jpdb_history.apply import EVENTS_CHANNEL, uuid7

KIND = "deck-build"


def enqueue_list_deck_builds(database_url: str) -> int:
    """One job per live list deck, dedupe ``deck-build:<id>`` (a queued or running build is kept).

    Returns the number of jobs inserted. ``database_url`` is the JDBC-form ``DATABASE_URL``.
    """
    inserted = 0
    with psycopg.connect(to_conninfo(database_url), autocommit=True) as conn:
        rows = conn.execute(
            "SELECT id FROM app.deck WHERE type = 'list' AND deleted_at IS NULL ORDER BY id"
        ).fetchall()
        for (deck_id,) in rows:
            inserted += _enqueue(conn, str(deck_id))
    return inserted


def _enqueue(conn: psycopg.Connection[Any], deck_id: str) -> int:
    job = uuid7()
    cur = conn.execute(
        "INSERT INTO app.job (id, kind, payload, dedupe_key) VALUES (%s, %s, %s::jsonb, %s) "
        "ON CONFLICT DO NOTHING",
        (job, KIND, json.dumps({"deck_id": deck_id}), f"{KIND}:{deck_id}"),
    )
    if cur.rowcount:
        conn.execute(
            "SELECT pg_notify(%s, %s)",
            (EVENTS_CHANNEL, json.dumps({"type": "job.updated", "data": {"id": str(job)}})),
        )
    return int(cur.rowcount)
