"""JLPT vocabulary lists (contracts/db/VOCAB_LISTS.md 2-5) on the dict fixtures: mapping counts and deck-build."""

from __future__ import annotations

import importlib.util
import json
import uuid
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from types import ModuleType
from typing import Any

import psycopg
import psycopg.rows
import pytest

from kotobaya_worker.decks.build import (
    DeckBuildError,
    PgDeckPublishStore,
    PgPlanner,
    run_deck_build,
)
from kotobaya_worker.decks.enqueue import enqueue_list_deck_builds
from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.jmdict import parse_jmdict
from kotobaya_worker.dict.kanjidic import parse_kanjidic
from kotobaya_worker.dict.load import load_release
from kotobaya_worker.dict.models import ReleaseInfo
from kotobaya_worker.dict.vocab import VocabSource, parse_vocab_csv
from kotobaya_worker.importers.jpdb_history.apply import uuid7
from kotobaya_worker.media.publish.blobs import PgBlobTable
from kotobaya_worker.media.publish.snapshot import read_deck_items

ROOT = Path(__file__).resolve().parents[4]
FIX = ROOT / "fixtures" / "dict"
JLPT = FIX / "jlpt"
SEQ_NOMU, SEQ_JUU, SEQ_OTAKU_KANA = 1169870, 1579840, 1034420


def _validate() -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "c21_validate", ROOT / "contracts" / "db" / "media" / "validate.py"
    )
    assert spec is not None and spec.loader is not None
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _sources(*, with_empty_n3: bool = False) -> list[VocabSource]:
    out = [
        VocabSource("jlpt-n5", "jlpt", 0, "JLPT N5", parse_vocab_csv(JLPT / "n5.csv", "jlpt n5")),
        VocabSource("jlpt-n4", "jlpt", 1, "JLPT N4", parse_vocab_csv(JLPT / "n4.csv", "jlpt n4")),
    ]
    if with_empty_n3:
        out.append(VocabSource("jlpt-n3", "jlpt", 2, "JLPT N3", []))
    return out


def _load(db_url: str, sources: list[VocabSource]) -> Any:
    with psycopg.connect(to_conninfo(db_url)) as conn:
        return load_release(
            conn,
            ReleaseInfo(1, "2026.11.1", "2026-11-12T00:00:00+00:00"),
            parse_jmdict(FIX / "JMdict_subset.xml"),
            parse_kanjidic(FIX / "kanjidic2_subset.xml"),
            sources,
        )


# ---------------------------------------------------------------- CSV parsing
def test_csv_bom_crlf_blank_lines_and_whitespace(tmp_path: Path) -> None:
    p = tmp_path / "x.csv"
    p.write_bytes(
        "﻿waller_definition,kanji,jmdict_seq,kana\r\nx, 家 ,1191730, いえ \r\n\r\n"
        'y,,,"あ\r\nい"\r\n'.encode()
    )
    rows = parse_vocab_csv(p, "t")
    assert [(r.source_row, r.jmdict_seq, r.kana, r.kanji) for r in rows] == [
        (1, 1191730, "いえ", "家"),
        (2, None, "あ\r\nい", ""),
    ]


def test_csv_missing_column_fails(tmp_path: Path) -> None:
    p = tmp_path / "x.csv"
    p.write_text("jmdict_seq,kana\n1,あ\n", encoding="utf-8")
    with pytest.raises(ValueError, match="invalid_source: jlpt n5"):
        parse_vocab_csv(p, "jlpt n5")


def test_short_row_is_invalid(tmp_path: Path) -> None:
    p = tmp_path / "x.csv"
    p.write_text("jmdict_seq,kana,kanji\n1191730,いえ\n", encoding="utf-8")
    (row,) = parse_vocab_csv(p, "t")
    assert row.invalid


# ---------------------------------------------------------------- mapping counts
def test_mapping_counts_nothing_dropped(db_url: str) -> None:
    res = _load(db_url, _sources())
    assert [r.to_json() for r in res.vocab_lists] == [
        {"id": "jlpt-n5", "source_rows": 16, "entries": 12, "duplicates": 1, "shadowed": 0, "unmatched": 3},
        {"id": "jlpt-n4", "source_rows": 9, "entries": 4, "duplicates": 0, "shadowed": 3, "unmatched": 2},
    ]  # fmt: skip
    n_rows = {"jlpt-n5": 16, "jlpt-n4": 9}
    with psycopg.connect(to_conninfo(db_url)) as c:
        for lid, src, ent, dup, sh, un in c.execute(
            "select id, source_rows, entry_count, duplicate_rows, shadowed_rows, unmatched_rows "
            "from ref.vocab_list"
        ).fetchall():
            assert src == n_rows[lid] == ent + dup + sh + un
        unmatched = {
            (r[0], r[1]): (r[2], r[3], r[4])
            for r in c.execute(
                "select list_id, source_row, jmdict_seq, reason, candidates "
                "from ref.vocab_list_unmatched"
            ).fetchall()
        }
        assert unmatched == {
            ("jlpt-n5", 13): (9999999, "no_entry", []),
            ("jlpt-n5", 14): (None, "invalid_row", []),
            ("jlpt-n5", 15): (None, "ambiguous", [1046340, 1046840]),
            ("jlpt-n4", 4): (None, "ambiguous", [1008490, 2872968]),
            ("jlpt-n4", 8): (None, "invalid_row", []),
        }
        n5: dict[int, int] = dict(
            c.execute("select seq, position from ref.vocab_list_item where list_id = 'jlpt-n5'")
        )
        assert n5[SEQ_JUU] == 8  # first row of the duplicate pair keeps its position
        assert n5[SEQ_OTAKU_KANA] == 12  # kana-only preferred over お宅
        assert n5[1578850] == 11  # unknown seq: matched by forms
        assert n5[1591110] == 10
        n4: dict[int, int] = dict(
            c.execute("select seq, position from ref.vocab_list_item where list_id = 'jlpt-n4'")
        )
        assert SEQ_NOMU not in n4 and 1522150 not in n4  # shadowed by N5 (by seq and by forms)
        assert set(n4) == {1157170, 1002400, 1000420, 1000660}
        # lists are tied to the loaded release; a reload without lists clears them
        assert c.execute("select distinct release_id from ref.vocab_list").fetchall() == [(1,)]


# ---------------------------------------------------------------- deck-build
@dataclass
class FakeCtx:
    payload: dict[str, Any]
    job_id: uuid.UUID

    def progress(self, fraction: float, message: str | None = None) -> None:
        pass

    def check_cancelled(self) -> None:
        pass


class MemObjects:
    def __init__(self) -> None:
        self.objects: dict[str, bytes] = {}

    def put(self, blob_hash: str, data: bytes) -> None:
        self.objects[blob_hash] = data


class FakeServer:
    """Stands in for the server's DeckPublishRunner: validates the payload with the contract's
    validate.py, installs the archive and finishes the child."""

    def __init__(self, conn: psycopg.Connection[Any], objects: MemObjects) -> None:
        self.conn = conn
        self.objects = objects
        self.validate = _validate()
        self.published: list[Mapping[str, Any]] = []

    def __call__(self, _seconds: float) -> None:
        row = self.conn.execute(
            "select id, payload from app.job where kind = 'deck-publish' and status = 'queued'"
        ).fetchone()
        assert row is not None
        payload = row["payload"]
        blob = self.objects.objects[payload["deck_items"]["blob_hash"]]
        result = self.validate.validate_deck_publish(payload, blob)  # raises on any contract breach
        arch = read_deck_items(blob)
        self.conn.execute("delete from app.deck_item where deck_id = %s", (payload["deck_id"],))
        for it in arch.items:
            self.conn.execute(
                "insert into app.deck_item (deck_id, item_key, entry_seq, position, occurrence_count, "
                "added_at) values (%s, %s, %s, %s, 0, now())",
                (payload["deck_id"], f"e:{it.entry_seq}", it.entry_seq, it.position),
            )
        self.conn.execute(
            "update app.deck set content_version = %s where id = %s",
            (result["content_version"], payload["deck_id"]),
        )
        self.conn.execute(
            "update app.job set status = 'succeeded', result = %s::jsonb, finished_at = now() where id = %s",
            (json.dumps(result), row["id"]),
        )
        self.published.append(payload)


def _deck(conn: psycopg.Connection[Any], list_key: str) -> str:
    deck_id = str(uuid7())
    conn.execute(
        "insert into app.deck (id, name, type, list_key, created_at, updated_at) "
        "values (%s, %s, 'list', %s, now(), now())",
        (deck_id, list_key, list_key),
    )
    return deck_id


def _run(
    conn: psycopg.Connection[Any], deck_id: str, server: FakeServer | None, objects: MemObjects
) -> Any:
    ctx = FakeCtx({"deck_id": deck_id}, uuid7())
    return run_deck_build(
        ctx,
        planner=PgPlanner(conn),
        store=PgDeckPublishStore(conn),
        objects=objects,
        blobs=PgBlobTable(conn),
        sleep=server or (lambda _s: None),
        poll_interval=0,
    )


@pytest.fixture
def loaded(db_url: str) -> Any:
    _load(db_url, _sources(with_empty_n3=True))
    with psycopg.connect(
        to_conninfo(db_url), autocommit=True, row_factory=psycopg.rows.dict_row
    ) as conn:
        # Global frequency orders the deck: N5's 飲む first, then 家 (ranks 1, 2); the rest keep source order.
        conn.execute("update ref.dict_entry set freq_rank = 1 where seq = %s", (SEQ_NOMU,))
        conn.execute("update ref.dict_entry set freq_rank = 2 where seq = 1191730")
        yield conn


def test_deck_build_publishes_archive_and_payload(
    db_url: str, loaded: psycopg.Connection[Any]
) -> None:
    objects = MemObjects()
    deck_id = _deck(loaded, "jlpt-n5")
    server = FakeServer(loaded, objects)
    result = _run(loaded, deck_id, server, objects)
    assert result == {
        "deck_id": deck_id, "content_version": 1, "items": 12,
        "list_key": "jlpt-n5", "changed": True, "unmatched": 3,
    }  # fmt: skip
    (payload,) = server.published  # validate.py accepted payload + blob (see FakeServer)
    arch = read_deck_items(objects.objects[payload["deck_items"]["blob_hash"]])
    assert (arch.deck_type, arch.content_version, arch.deck_id) == ("list", 1, deck_id)
    assert (
        len(arch.items)
        == 12
        == _one(loaded, "select entry_count as v from ref.vocab_list where id = 'jlpt-n5'")
    )
    assert [i.position for i in arch.items] == [1024 * k for k in range(1, 13)]
    assert [i.entry_seq for i in arch.items][:2] == [SEQ_NOMU, 1191730]
    assert all(i.occurrence_count == 0 for i in arch.items)
    assert loaded.execute(
        "select 1 from app.blob where hash = %s and kind = 'snapshot' and origin = 'build'",
        (payload["deck_items"]["blob_hash"],),
    ).fetchone()

    # Same release, same items: the rebuild is a no-op and queues no child.
    again = _run(loaded, deck_id, None, objects)
    assert again["changed"] is False and again["content_version"] == 1 and again["items"] == 12
    assert _one(loaded, "select count(*) as v from app.job where kind = 'deck-publish'") == 1


def test_empty_list_fails_without_child(db_url: str, loaded: psycopg.Connection[Any]) -> None:
    deck_id = _deck(loaded, "jlpt-n3")  # stored, zero rows
    with pytest.raises(DeckBuildError) as ei:
        _run(loaded, deck_id, None, MemObjects())
    assert ei.value.job_error.startswith("empty_list: ") and ei.value.non_retryable
    assert _one(loaded, "select count(*) as v from app.job") == 0


def test_plan_errors(db_url: str, loaded: psycopg.Connection[Any]) -> None:
    def code(deck_id: str) -> str:
        with pytest.raises(DeckBuildError) as ei:
            _run(loaded, deck_id, None, MemObjects())
        return ei.value.code

    assert code(str(uuid7())) == "unknown_deck"
    assert code(_deck(loaded, "jlpt-n1")) == "unknown_list"
    assert code(_deck(loaded, "freq-nope-1000")) == "unknown_list"
    gone = _deck(loaded, "jlpt-n4")
    loaded.execute("update app.deck set deleted_at = now() where id = %s", (gone,))
    assert code(gone) == "deck_deleted"
    user = str(uuid7())
    loaded.execute(
        "insert into app.deck (id, name, type, created_at, updated_at) "
        "values (%s, 'u', 'user', now(), now())",
        (user,),
    )
    assert code(user) == "invalid_payload"


def test_refresh_requeues_live_list_decks_once(
    db_url: str, loaded: psycopg.Connection[Any]
) -> None:
    live = _deck(loaded, "jlpt-n5")
    gone = _deck(loaded, "jlpt-n4")
    loaded.execute("update app.deck set deleted_at = now() where id = %s", (gone,))
    assert enqueue_list_deck_builds(_jdbc(loaded)) == 1
    assert enqueue_list_deck_builds(_jdbc(loaded)) == 0  # deduped while queued
    rows = loaded.execute(
        "select payload, dedupe_key from app.job where kind = 'deck-build'"
    ).fetchall()
    assert [(r["payload"], r["dedupe_key"]) for r in rows] == [
        ({"deck_id": live}, f"deck-build:{live}")
    ]


def _one(conn: psycopg.Connection[Any], sql: str) -> Any:
    row = conn.execute(sql).fetchone()
    assert row is not None
    return row["v"]


def _jdbc(conn: psycopg.Connection[Any]) -> str:
    i = conn.info
    return f"jdbc:postgresql://{i.host}:{i.port}/{i.dbname}?user={i.user}&password={i.password}"
