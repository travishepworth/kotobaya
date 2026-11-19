from __future__ import annotations

import hashlib
import http.client
import os
import time
import urllib.error
from dataclasses import replace
from email.message import Message
from pathlib import Path

import pytest

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.fetch import (
    MAX_ATTEMPTS,
    STALE_PART_AGE_S,
    ChecksumMismatch,
    SourceSpec,
    ensure_source,
    fetch_source,
)
from kotobaya_worker.dict.jmdict import parse_jmdict
from kotobaya_worker.dict.kanjidic import parse_krad
from kotobaya_worker.dict.load import display_headword, entry_kanji_rows, entry_row, form_rows
from kotobaya_worker.dict.models import Entry, Form
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


def test_headword_rule_kana_words_on_fixture() -> None:
    """ADR-046 (a), SCHEMA.md 4.5 "Headword": every kanji form rK/sK -> the first reading."""
    entries = {e.seq: e for e in parse_jmdict(FIXTURES / "JMdict_subset.xml")}
    kana_words = {
        s
        for s, e in entries.items()
        if e.kanji and all({"rK", "sK"} & set(f.tags) for f in e.kanji)
    }
    assert len(kana_words) == 137
    for s, e in entries.items():
        hw = display_headword(e)
        if s in kana_words or not e.kanji:
            assert hw == e.readings[0].text, s
        else:
            assert hw == e.kanji[0].text, s
        row = entry_row(e)
        assert row[1] == hw and row[2] == e.readings[0].text
    for s, want in (
        (1000580, "あれ"),
        (1021360, "イタリア"),
        (1001620, "おにぎり"),
        (1577980, "いる"),
    ):
        assert display_headword(entries[s]) == want
    assert display_headword(entries[1012730]) == "軈て"  # one form is oK, not rK/sK
    assert display_headword(entries[1001670]) == "お化け"  # first form untagged
    # Forms and entry_kanji are untouched by the rule: every kanji form stays searchable.
    for e in entries.values():
        assert [r[3] for r in form_rows(e)] == [f.text for f in (*e.kanji, *e.readings)]
    assert any(r[3] == "伊太利" for r in form_rows(entries[1021360]))
    assert {"伊", "太", "利"} <= {r[1] for r in entry_kanji_rows(entries[1021360])}


def test_headword_rule_edge_cases() -> None:
    def e(*kanji: tuple[str, tuple[str, ...]]) -> Entry:
        return Entry(
            1, tuple(Form(t, False, tags) for t, tags in kanji), (Form("よみ", False),), ()
        )

    assert display_headword(e()) == "よみ"
    assert display_headword(e(("新嘉坡", ("ateji", "rK")), ("新加坡", ("rK",)))) == "よみ"
    assert display_headword(e(("歌留多", ("rK",)), ("骨牌", ("sK",)))) == "よみ"
    assert display_headword(e(("寿司", ("ateji",)))) == "寿司"
    assert display_headword(e(("軈て", ("rK",)), ("漸て", ("oK",)))) == "軈て"
    assert display_headword(e(("彼", ("rk",)))) == "彼"  # case-sensitive
    assert display_headword(e(("鮨", ()), ("寿司", ("rK",)))) == "鮨"


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


def test_unpinned_fetch_of_changed_file_records_digest(tmp_path: Path) -> None:
    """F-13: no pin -> any content accepted, digest + Last-Modified recorded, re-fetched daily."""
    spec = SourceSpec("x.bin", "http://example.invalid/x")
    body = [b"monday"]

    def fetch(url: str, dest: Path) -> dict[str, str]:
        dest.write_bytes(body[0])
        return {"Last-Modified": "Mon, 09 Nov 2026 00:00:00 GMT"}

    a = fetch_source(spec, tmp_path, fetch)
    assert a.spec.sha256 == hashlib.sha256(b"monday").hexdigest()
    assert a.spec.provenance()["last_modified"] == "Mon, 09 Nov 2026 00:00:00 GMT"
    body[0] = b"tuesday"
    b = fetch_source(spec, tmp_path, fetch)
    assert b.spec.sha256 == hashlib.sha256(b"tuesday").hexdigest()


def test_pinned_mismatch_fails_clearly(tmp_path: Path) -> None:
    spec = _spec(b"good")

    def bad(url: str, dest: Path) -> None:
        dest.write_bytes(b"changed")

    with pytest.raises(ChecksumMismatch, match="sha256 mismatch"):
        fetch_source(spec, tmp_path, bad)


# ---------------------------------------------------------------- LOGFIX-1: retries and stale pins
def test_partial_download_is_retried_then_verified(tmp_path: Path) -> None:
    spec = _spec(b"good")
    bodies = [b"go", b"good"]  # first transfer truncated

    def fetch(url: str, dest: Path) -> None:
        dest.write_bytes(bodies.pop(0))

    got = fetch_source(spec, tmp_path, fetch)
    assert got.path.read_bytes() == b"good" and bodies == []
    assert [p.name for p in tmp_path.iterdir()] == ["x.bin"]  # no .part left behind


def test_transient_fetch_error_is_retried(tmp_path: Path) -> None:
    spec = _spec(b"good")
    calls: list[int] = []

    def fetch(url: str, dest: Path) -> None:
        calls.append(1)
        if len(calls) == 1:
            raise http.client.IncompleteRead(b"go", 2)
        dest.write_bytes(b"good")

    assert fetch_source(spec, tmp_path, fetch).path.read_bytes() == b"good"
    assert len(calls) == 2


def test_strict_pin_mismatch_raises_after_all_attempts(tmp_path: Path) -> None:
    spec = _spec(b"good")
    calls: list[int] = []

    def fetch(url: str, dest: Path) -> None:
        calls.append(1)
        dest.write_bytes(b"changed")

    with pytest.raises(ChecksumMismatch):
        fetch_source(spec, tmp_path, fetch)
    assert len(calls) == MAX_ATTEMPTS
    assert list(tmp_path.iterdir()) == []


def test_republished_edrdg_file_with_stale_pin_is_accepted_and_recorded(tmp_path: Path) -> None:
    """The live ChecksumMismatch: a pinned kanjidic2 republished upstream must not fail forever."""
    spec = SourceSpec(
        "kanjidic2.xml.gz", "http://example.invalid/k", hashlib.sha256(b"old").hexdigest(),
        strict=False,
    )  # fmt: skip
    calls: list[int] = []

    def fetch(url: str, dest: Path) -> None:
        calls.append(1)
        dest.write_bytes(b"new")

    got = fetch_source(spec, tmp_path, fetch)
    assert got.path.read_bytes() == b"new"
    assert got.spec.sha256 == hashlib.sha256(b"new").hexdigest()  # new digest -> new release
    assert len(calls) == 2  # two downloads agreed before the pin was overridden


def test_stale_pin_never_accepts_a_partial_download(tmp_path: Path) -> None:
    spec = SourceSpec("j.gz", "http://example.invalid/j", hashlib.sha256(b"old").hexdigest(),
                      strict=False)  # fmt: skip
    bodies = [b"ne", b"new", b"new"]

    def fetch(url: str, dest: Path) -> None:
        dest.write_bytes(bodies.pop(0))

    assert fetch_source(spec, tmp_path, fetch).path.read_bytes() == b"new"
    flapping = [b"a", b"b", b"c"]

    def fetch2(url: str, dest: Path) -> None:
        dest.write_bytes(flapping.pop(0))

    with pytest.raises(ChecksumMismatch):
        fetch_source(replace(spec, name="k.gz"), tmp_path, fetch2)


def test_dict_sources_edrdg_pins_are_advisory_other_pins_strict() -> None:
    from kotobaya_worker.jobs.dict_refresh import DictSources

    src = DictSources.from_json(
        '{"kanjidic": {"sha256": "ab"}, "kanjium": {"url": "http://x/k", "sha256": "cd"}, "jlpt": null}'
    )
    assert src.core.kanjidic.sha256 == "ab" and not src.core.kanjidic.strict
    assert src.kanjium is not None and src.kanjium.strict


# ---------------------------------------------------------------- OBS-1w: HTTP errors and stale parts
def _http_error(code: int) -> urllib.error.HTTPError:
    return urllib.error.HTTPError("http://example.invalid/x", code, "x", Message(), None)


@pytest.mark.parametrize("code", [403, 404])
def test_http_4xx_is_not_retried(tmp_path: Path, code: int) -> None:
    calls: list[int] = []

    def fetch(url: str, dest: Path) -> None:
        calls.append(1)
        raise _http_error(code)

    with pytest.raises(urllib.error.HTTPError):
        fetch_source(_spec(b"good"), tmp_path, fetch)
    assert len(calls) == 1


def test_http_5xx_is_retried(tmp_path: Path) -> None:
    calls: list[int] = []

    def fetch(url: str, dest: Path) -> None:
        calls.append(1)
        if len(calls) == 1:
            raise _http_error(503)
        dest.write_bytes(b"good")

    assert fetch_source(_spec(b"good"), tmp_path, fetch).path.read_bytes() == b"good"
    assert len(calls) == 2


def test_stale_part_files_are_removed_fresh_ones_kept(tmp_path: Path) -> None:
    old = tmp_path / "x.bin.abc123.part"
    fresh = tmp_path / "x.bin.def456.part"
    other = tmp_path / "y.bin.abc123.part"
    for p in (old, fresh, other):
        p.write_bytes(b"partial")
    stale = time.time() - STALE_PART_AGE_S - 60
    os.utime(old, (stale, stale))
    os.utime(other, (stale, stale))

    def fetch(url: str, dest: Path) -> None:
        dest.write_bytes(b"good")

    fetch_source(_spec(b"good"), tmp_path, fetch)
    assert not old.exists()
    assert fresh.exists() and other.exists()  # young, or another source's
