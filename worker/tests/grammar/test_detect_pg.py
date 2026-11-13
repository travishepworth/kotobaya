"""T506a: the Postgres adapters of grammar-detect against the V1 schema (docker; skipped without it)."""

from __future__ import annotations

import importlib.util
import uuid
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import psycopg
import pytest
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.grammar.detect import PointRules
from kotobaya_worker.grammar.job import (
    KIND,
    PgDetectQueue,
    PgRuleSource,
    dedupe_key,
)
from kotobaya_worker.media.pipeline.base import PgMediaReader
from kotobaya_worker.media.publish.snapshot import GrammarSpanRec

ROOT = Path(__file__).resolve().parents[3]
_spec = importlib.util.spec_from_file_location(
    "t506a_pg", ROOT / "worker" / "tests" / "runner" / "conftest.py"
)
assert _spec is not None and _spec.loader is not None
_pg = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_pg)
pg_server = _pg.pg_server
db_url = _pg.db_url

M_FILE, M_JPDB, M_DELETED, M_UNPARSED = (str(uuid.UUID(int=i)) for i in range(1, 5))
GP_LIVE, GP_DELETED, GP_EMPTY = (str(uuid.UUID(int=i)) for i in range(11, 14))
SENT = str(uuid.UUID(int=21))


@pytest.fixture
def conn(db_url: str) -> Iterator[psycopg.Connection[Any]]:
    with psycopg.connect(to_conninfo(db_url), autocommit=True, row_factory=dict_row) as c:
        for mid, source, version, deleted in (
            (M_FILE, "file", 2, False),
            (M_JPDB, "jpdb", 1, False),
            (M_DELETED, "file", 1, True),
            (M_UNPARSED, "file", 0, False),
        ):
            c.execute(
                "insert into app.media (id, title, kind, source, content_version, created_at, updated_at, "
                "deleted_at) values (%s, 't', 'novel', %s, %s, now(), now(), "
                "case when %s then now() end)",
                (mid, source, version, deleted),
            )
        rules = [{"rule": "ざる を 得 ない", "draft": False}]
        for gid, value, deleted in (
            (GP_LIVE, rules, False),
            (GP_DELETED, rules, True),
            (GP_EMPTY, [], False),
        ):
            c.execute(
                "insert into app.grammar_point (id, title, detection_rules, created_at, updated_at, "
                "deleted_at) values (%s, 't', %s, now(), now(), case when %s then now() end)",
                (gid, Jsonb(value), deleted),
            )
        c.execute(
            "insert into app.sentence_grammar_span (sentence_id, grammar_point_id, start_offset, "
            "end_offset, origin, media_id, rule_index) values (%s, %s, 1, 5, 'detected', %s, 0)",
            (SENT, GP_LIVE, M_FILE),
        )
        c.execute(
            "insert into app.sentence_grammar_span (sentence_id, grammar_point_id, start_offset, "
            "end_offset, origin, media_id, hlc) values (%s, %s, 1, 5, 'manual', %s, %s)",
            (SENT, GP_LIVE, M_FILE, "0193a1b20000-0000-00000000-0000-7000-8000-000000000001"),
        )
        yield c


def test_rule_source_lists_live_points_with_rules(conn: psycopg.Connection[Any]) -> None:
    assert PgRuleSource(conn).load() == [
        PointRules(GP_LIVE, [{"rule": "ざる を 得 ない", "draft": False}])
    ]


def test_media_reader_snapshot_has_rules_and_detected_spans_only(
    conn: psycopg.Connection[Any],
) -> None:
    base = PgMediaReader(conn).read(M_FILE)
    assert base.detected_spans == frozenset({GrammarSpanRec(SENT, GP_LIVE, 1, 5, 0)})
    assert [p.grammar_point_id for p in base.grammar_rules] == [GP_LIVE]
    missing = PgMediaReader(conn).read(str(uuid.UUID(int=99)))
    assert not missing.exists and [p.grammar_point_id for p in missing.grammar_rules] == [GP_LIVE]


def test_fan_out_release_clears_own_key(conn: psycopg.Connection[Any]) -> None:
    job = uuid.UUID(int=77)
    conn.execute(
        "insert into app.job (id, kind, payload, dedupe_key) values (%s, %s, '{}', %s)",
        (job, KIND, "grammar-detect:all"),
    )
    PgDetectQueue(conn).release(job)
    assert conn.execute("select dedupe_key from app.job where id = %s", (job,)).fetchone() == {
        "dedupe_key": None
    }


def test_queue_lists_taggable_media_and_dedupes(conn: psycopg.Connection[Any]) -> None:
    q = PgDetectQueue(conn)
    assert q.media_ids() == [M_FILE]
    assert q.enqueue(M_FILE, "abc") is True
    assert q.enqueue(M_FILE, "abc") is False  # same rules, still queued
    assert q.enqueue(M_FILE, "def") is True
    rows = conn.execute(
        "select kind, payload, dedupe_key, status from app.job order by dedupe_key"
    ).fetchall()
    assert rows == [
        {
            "kind": KIND,
            "payload": {"media_id": M_FILE},
            "dedupe_key": dedupe_key(M_FILE, d),
            "status": "queued",
        }
        for d in ("abc", "def")
    ]
