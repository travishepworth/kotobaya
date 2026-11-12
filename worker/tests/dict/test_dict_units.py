from __future__ import annotations

import hashlib
from pathlib import Path

import pytest

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.fetch import ChecksumMismatch, SourceSpec, ensure_source
from kotobaya_worker.dict.jmdict import parse_jmdict
from kotobaya_worker.dict.kanjidic import parse_krad
from kotobaya_worker.dict.normalize import normalize_form

FIXTURES = Path(__file__).resolve().parents[3] / "fixtures" / "dict"


def test_norm_nfkc_then_katakana_to_hiragana() -> None:
    assert normalize_form("ＣＤプレーヤー") == "CDぷれーやー"
    assert normalize_form("ﾀﾍﾞﾙ") == "たべる"  # half-width katakana, dakuten composed
    assert normalize_form("食べる") == "食べる"
    assert normalize_form("ヴ") == "ゔ"


def test_database_url_jdbc_to_conninfo() -> None:
    info = to_conninfo("jdbc:postgresql://db.example:5433/kotoba?user=app&password=p%40ss")
    assert "host=db.example" in info
    assert "port=5433" in info
    assert "dbname=kotoba" in info
    assert "user=app" in info
    assert "password=p@ss" in info
    with pytest.raises(ValueError):
        to_conninfo("mysql://x/y")


def test_jmdict_tags_keep_entity_codes() -> None:
    first = next(parse_jmdict(FIXTURES / "JMdict_subset.xml"))
    assert first.seq == 1000110
    assert first.senses[0].pos == ("n",)
    assert first.kanji[0].common is True  # spec1


def test_krad_parse_euc_jp() -> None:
    data = "# comment\n亜 : ｜ 一 口\n阿 : 阝 可\n".encode("euc-jp")
    assert parse_krad(data) == {"亜": ("｜", "一", "口"), "阿": ("阝", "可")}


def _spec(data: bytes) -> SourceSpec:
    return SourceSpec("x.bin", "http://example.invalid/x", hashlib.sha256(data).hexdigest())


def test_checksum_mismatch_aborts_and_leaves_no_file(tmp_path: Path) -> None:
    spec = _spec(b"good")

    def bad(url: str, dest: Path) -> None:
        dest.write_bytes(b"evil")

    with pytest.raises(ChecksumMismatch):
        ensure_source(spec, tmp_path, bad)
    assert list(tmp_path.iterdir()) == []


def test_cached_file_is_not_redownloaded(tmp_path: Path) -> None:
    spec = _spec(b"good")
    calls: list[str] = []

    def fetch(url: str, dest: Path) -> None:
        calls.append(url)
        dest.write_bytes(b"good")

    first = ensure_source(spec, tmp_path, fetch)
    second = ensure_source(spec, tmp_path, fetch)
    assert first == second
    assert len(calls) == 1
    first.write_bytes(b"corrupted")  # cached file with a wrong checksum is re-fetched
    ensure_source(spec, tmp_path, fetch)
    assert len(calls) == 2
