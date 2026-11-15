"""WRK-FREQ-MATCH: the freq rebuild entry point against Postgres (current release, list deck)."""

from __future__ import annotations

import json
import shutil
import uuid
import zipfile
from pathlib import Path
from typing import Any

import psycopg
import pytest

from kotobaya_worker.decks.build import PgPlanner
from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.extra.load import FreqListConfig
from kotobaya_worker.dict.extra.pipeline import FreqListSource
from kotobaya_worker.dict.fetch import SourceSpec, sha256_file
from kotobaya_worker.dict.jmdict import parse_jmdict
from kotobaya_worker.dict.kanjidic import parse_kanjidic
from kotobaya_worker.dict.load import load_release
from kotobaya_worker.dict.models import ReleaseInfo
from kotobaya_worker.dict.pipeline import Sources
from kotobaya_worker.jobs.dict_refresh import DictSources
from kotobaya_worker.jobs.freq_rebuild import rebuild_freq

DATA = Path(__file__).resolve().parent / "data"
FIXTURES = Path(__file__).resolve().parents[4] / "fixtures" / "dict"
SOU_ADV, KUSA_DRAFT, KUSA, DA_COP = 2137720, 2414580, 1401910, 2089020

ROWS = [
    ["そう", "freq", {"reading": "そう", "frequency": {"value": 3, "displayValue": "3㋕"}}],
    ["だ", "freq", 1],
    ["草", "freq", {"reading": "そう", "frequency": 900}],
    ["草", "freq", {"reading": "くさ", "frequency": 800}],
    ["くさ", "freq", {"reading": "くさ", "frequency": {"value": 700, "displayValue": "700㋕"}}],
    ["存在しない語", "freq", 5],
]


def _zip(dest: Path) -> Path:
    with zipfile.ZipFile(dest, "w") as z:
        z.writestr("term_meta_bank_1.json", json.dumps(ROWS, ensure_ascii=False))
    return dest


def _sources(z: Path) -> DictSources:
    unused = SourceSpec("unused", "test://unused", None, strict=False)
    spec = SourceSpec("freq-cejc.zip", "test://cejc.zip", sha256_file(z))
    freq = {"cejc": FreqListSource(FreqListConfig("cejc", "CEJC", 1), spec)}
    return DictSources(Sources(unused, unused, unused), None, None, freq, None)


def _load(url: str) -> None:
    with psycopg.connect(to_conninfo(url)) as conn:
        info = ReleaseInfo(1, "2026.11.1", "2026-11-12T00:00:00+00:00")
        load_release(
            conn,
            info,
            parse_jmdict(DATA / "JMdict_freqmatch.xml"),
            parse_kanjidic(FIXTURES / "kanjidic2_subset.xml"),
        )


def _q(url: str, sql: str) -> list[tuple[Any, ...]]:
    with psycopg.connect(to_conninfo(url)) as conn:
        return conn.execute(sql).fetchall()


def test_rebuild_maps_one_entry_per_row_and_queues_list_decks(db_url: str, tmp_path: Path) -> None:
    _load(db_url)
    z = _zip(tmp_path / "cejc.zip")
    deck = uuid.uuid4()
    with psycopg.connect(to_conninfo(db_url)) as conn:
        # Stale fan-out left by the old loader: every そう homophone ranked 3.
        conn.execute("INSERT INTO ref.freq_list (id, title, priority, entry_count) "
                     "VALUES ('cejc', 'CEJC', 1, 0)")  # fmt: skip
        conn.execute(
            "INSERT INTO ref.entry_freq (seq, list_id, rank) SELECT DISTINCT seq, 'cejc', 3 "
            "FROM ref.dict_entry_form WHERE norm = 'そう'"
        )
        conn.execute(
            "INSERT INTO app.deck (id, name, type, list_key, created_at, updated_at) "
            "VALUES (%s, 'conversation 1k', 'list', 'freq-cejc-1000', now(), now())",
            (deck,),
        )
    calls: list[str] = []

    def net(url: str, dest: Path) -> None:
        calls.append(url)
        shutil.copyfile(z, dest)

    res = rebuild_freq(db_url, _sources(z), cache_dir=tmp_path / "cache", fetch=net)
    (fr,) = res.lists
    assert (fr.rows, fr.mapped, fr.unmatched, fr.deferred) == (6, 4, 1, 1)
    assert fr.rows == fr.mapped + fr.unmatched + fr.deferred
    assert fr.loaded == 4 and res.deck_builds_queued == 1
    assert _q(db_url, "SELECT seq, rank FROM ref.entry_freq ORDER BY seq") == sorted(
        [(SOU_ADV, 3), (DA_COP, 1), (KUSA_DRAFT, 900), (KUSA, 800)]
    )
    assert _q(db_url, "SELECT entry_count FROM ref.freq_list") == [(4,)]
    ranked = dict(_q(db_url, "SELECT seq, freq_rank FROM ref.dict_entry WHERE freq_rank > 0"))
    assert ranked == {SOU_ADV: 3, DA_COP: 1, KUSA_DRAFT: 900, KUSA: 800}
    jobs = _q(db_url, "SELECT kind, payload->>'deck_id' FROM app.job")
    assert jobs == [("deck-build", str(deck))]

    # The band the deck build reads: one entry for そう.
    with psycopg.connect(to_conninfo(db_url), autocommit=True) as conn:
        plan = PgPlanner(conn).plan(str(deck))
    assert plan.items == [DA_COP, SOU_ADV, KUSA, KUSA_DRAFT]

    # Re-run: cached source reused (no download), same rows, build deduped while queued.
    again = rebuild_freq(db_url, _sources(z), cache_dir=tmp_path / "cache", fetch=net)
    assert calls == ["test://cejc.zip"]
    assert again.lists == res.lists and again.freq_rank_changed == 0
    assert again.deck_builds_queued == 0


def test_rebuild_rejects_unknown_list_and_missing_release(db_url: str, tmp_path: Path) -> None:
    z = _zip(tmp_path / "cejc.zip")
    with pytest.raises(ValueError, match="not configured"):
        rebuild_freq(db_url, _sources(z), ["jpdb"], cache_dir=tmp_path / "c")
    (tmp_path / "c").mkdir()
    shutil.copyfile(z, tmp_path / "c" / "freq-cejc.zip")  # cached and pinned: no download
    with pytest.raises(ValueError, match="no current dictionary release"):
        rebuild_freq(db_url, _sources(z), cache_dir=tmp_path / "c")
