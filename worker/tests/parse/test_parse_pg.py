"""T403b against a real Postgres (docker; skipped without it): the ref.* match lookup, the
override loader's scope filter, and the per-request Postgres parse backend."""

from __future__ import annotations

import importlib.util
import json
from collections.abc import Sequence
from pathlib import Path
from typing import Any

import psycopg
import pytest

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.parse.match import FormHit
from kotobaya_worker.parse.match.postgres import PostgresMatchLookup, current_release
from kotobaya_worker.parse.overrides import load_overrides
from kotobaya_worker.parse.service import NotReady, PostgresParseBackend
from kotobaya_worker.parse.sudachi import get_analyzer, installed_dictionaries

_spec = importlib.util.spec_from_file_location(
    "parse_pg_fixtures", Path(__file__).resolve().parents[1] / "runner" / "conftest.py"
)
assert _spec is not None and _spec.loader is not None
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)
pg_server = _mod.pg_server
db_url = _mod.db_url

MEDIA = "0193a1b2-0c12-7000-8000-0000000000e1"
OTHER = "0193a1b2-0c12-7000-8000-0000000000e9"
SENT = "0193a1b2-0c12-7000-8000-0000000000f1"
HLC = "0193a1b2c3d4-0000-0193a1b2-0c12-7000-8000-0000000000d1"


def _entry(c: psycopg.Connection[Any], seq: int, kanji: list[str], readings: list[str],
           pos: list[str], *, misc: Sequence[str] = (), retired: bool = False) -> None:  # fmt: skip
    c.execute(
        "insert into ref.dict_entry (seq, headword, headword_reading, kanji_forms, readings,"
        " senses, pos, misc, added_release_id, retired_release_id)"
        " values (%s, %s, %s, %s::jsonb, %s::jsonb, '[]'::jsonb, %s, %s, 1, %s)",
        (seq, (kanji or readings)[0], readings[0],
         json.dumps([{"text": k, "common": False, "tags": []} for k in kanji]),
         json.dumps([{"text": r, "common": False, "tags": [], "applies_to": ["*"]} for r in readings]),
         list(pos), list(misc), 1 if retired else None),
    )  # fmt: skip
    for kind, forms in (("kanji", kanji), ("reading", readings)):
        for i, f in enumerate(forms):
            c.execute(
                "insert into ref.dict_entry_form (seq, kind, ord, form, norm, common)"
                " values (%s, %s, %s, %s, %s, %s)",
                (seq, kind, i, f, f, seq == 1203250),
            )


def _seed(url: str, *, with_release: bool = True) -> None:
    with psycopg.connect(to_conninfo(url), autocommit=True) as c:
        if not with_release:
            return
        c.execute(
            "insert into ref.dict_release (id, version, built_at, is_current)"
            " values (1, '2026.11.1', now(), true)"
        )
        _entry(c, 1203250, ["外"], ["そと"], ["n"])
        _entry(c, 1203260, ["外"], ["ほか"], ["n", "adj-no"])
        _entry(c, 1000001, ["外"], ["そと"], ["n"], retired=True)
        _entry(c, 1213080, ["映画"], ["えいが"], ["n"])
        c.execute(
            "insert into ref.freq_list (id, title, priority) values ('a', 'A', 0), ('b', 'B', 1)"
        )
        c.execute(
            "insert into ref.entry_freq (seq, list_id, rank) values"
            " (1203260, 'a', 50), (1203260, 'b', 1), (1203250, 'b', 2)"
        )
        rows = [
            ("00000000-0000-7000-8000-000000000001", None, None, "映画", [{"surface": "映画", "seq": None}], None),
            ("00000000-0000-7000-8000-000000000002", MEDIA, None, "外", [{"surface": "外"}], None),
            ("00000000-0000-7000-8000-000000000003", MEDIA, SENT, "外", [{"surface": "外"}], None),
            ("00000000-0000-7000-8000-000000000004", OTHER, None, "外", [{"surface": "外"}], None),
            ("00000000-0000-7000-8000-000000000005", None, None, "外", [{"surface": "外"}], "now()"),
        ]  # fmt: skip
        for oid, media, sent, match, toks, deleted in rows:
            c.execute(
                "insert into app.parse_override (id, media_id, sentence_id, match_text, tokens,"
                " created_at, updated_at, deleted_at, hlc)"
                f" values (%s, %s, %s, %s, %s::jsonb, now(), now(), {deleted or 'null'}, %s)",
                (oid, media, sent, match, json.dumps(toks), HLC),
            )


def test_postgres_lookup_forms_entries_and_first_freq_list(db_url: str) -> None:
    _seed(db_url)
    with psycopg.connect(to_conninfo(db_url)) as c:
        lk = PostgresMatchLookup(c)
        assert current_release(c) == "2026.11.1"
        # retired entries are never candidates and have no entry
        assert list(lk.forms("外")) == [
            FormHit(1203250, "kanji", True), FormHit(1203260, "kanji", False),
        ]  # fmt: skip
        assert lk.entry(1000001) is None
        soto, hoka = lk.entry(1203250), lk.entry(1203260)
        assert soto is not None and hoka is not None
        assert (soto.pos, soto.readings, soto.kana_usual) == (
            frozenset({"n"}),
            frozenset({"そと"}),
            False,
        )
        # rank from the first configured list (priority 0) only
        assert (soto.freq_rank, hoka.freq_rank) == (None, 50)
        assert lk.is_key("そと") and not lk.is_key("うち")


def test_load_overrides_scope_filter(db_url: str) -> None:
    _seed(db_url)
    with psycopg.connect(to_conninfo(db_url)) as c:
        media = {o.id[-1] for o in load_overrides(c, MEDIA)}
        glob = {o.id[-1] for o in load_overrides(c, None)}
    assert media == {"1", "2", "3"}  # not the other media's row, not the deleted one
    assert glob == {"1"}


@pytest.mark.skipif(not installed_dictionaries(), reason="no Sudachi dictionary installed")
def test_postgres_backend_parses_with_global_overrides_and_reports_not_ready(db_url: str) -> None:
    backend = PostgresParseBackend(to_conninfo(db_url), load_analyzer=get_analyzer)
    backend._load()  # synchronously, instead of start()'s background thread
    assert backend.health().dict_release is None
    with pytest.raises(NotReady):
        backend.parse("映画を見た。")
    _seed(db_url)
    body = backend.parse("外で映画を見た。")
    assert ";dict=2026.11.1;" in body["parser_version"]
    toks = {t["surface"]: t for t in body["sentences"][0]["tokens"]}
    assert (toks["映画"]["override"], toks["映画"]["seq"]) == (True, None)
    assert (toks["外"]["override"], toks["外"]["seq"]) == (False, 1203250)
    assert backend.health().dict_release == "2026.11.1"


def test_postgres_backend_unreachable_db_is_not_ready() -> None:
    backend = PostgresParseBackend(
        "host=127.0.0.1 port=1 dbname=x user=x", load_analyzer=get_analyzer, connect_timeout=1
    )
    if installed_dictionaries():
        backend._load()
        with pytest.raises(NotReady):
            backend.parse("映画")
