from __future__ import annotations

import gzip
import hashlib
import io
import re
import unicodedata
import zipfile
from pathlib import Path
from typing import Any

import psycopg
import pytest

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.fetch import ChecksumMismatch
from kotobaya_worker.dict.jmdict import parse_jmdict
from kotobaya_worker.dict.kanjidic import parse_kanjidic
from kotobaya_worker.dict.load import load_release
from kotobaya_worker.dict.models import Entry, ReleaseInfo
from kotobaya_worker.dict.pipeline import Sources, refresh

FIXTURES = Path(__file__).resolve().parents[3] / "fixtures" / "dict"
JMDICT = FIXTURES / "JMdict_subset.xml"
KANJIDIC = FIXTURES / "kanjidic2_subset.xml"

TABLES = {
    "dict_release": "id",
    "dict_entry": "seq",
    "dict_entry_form": "seq, kind, ord",
    "dict_entry_gloss": "seq, sense_ord, gloss_ord",
    "entry_kanji": "seq, literal",
    "kanji": "literal",
}


def _release(i: int) -> ReleaseInfo:
    return ReleaseInfo(i, f"2026.11.{i}", "2026-11-12T00:00:00+00:00")


def _connect(url: str) -> psycopg.Connection[Any]:
    return psycopg.connect(to_conninfo(url))


def _count(conn: psycopg.Connection[Any], table: str, where: str = "true") -> int:
    row = conn.execute(f"SELECT count(*) FROM ref.{table} WHERE {where}").fetchone()
    assert row is not None
    return int(row[0])


def _checksums(conn: psycopg.Connection[Any]) -> dict[str, tuple[int, str]]:
    out: dict[str, tuple[int, str]] = {}
    for t, order in TABLES.items():
        # loaded_at is a load-time stamp; everything else must be byte-identical.
        cols = (
            "id, version, built_at, is_current, sources, artifacts" if t == "dict_release" else "*"
        )
        row = conn.execute(
            f"SELECT count(*), md5(coalesce(string_agg(r::text, '|' ORDER BY {order}), '')) "
            f"FROM (SELECT {cols} FROM ref.{t}) r"
        ).fetchone()
        assert row is not None
        out[t] = (int(row[0]), str(row[1]))
    return out


def _is_cjk(ch: str) -> bool:
    n = unicodedata.name(ch, "")
    return n.startswith(("CJK UNIFIED IDEOGRAPH", "CJK COMPATIBILITY IDEOGRAPH"))


def test_fixture_counts_match_xml(db_url: str) -> None:
    raw = JMDICT.read_text(encoding="utf-8").split("\n<JMdict>\n", 1)[1]  # skip the DTD
    kd = KANJIDIC.read_text(encoding="utf-8")
    # Independent counts straight from the XML text.
    n_entries = len(re.findall(r"<entry>", raw))
    n_kanji_forms = len(re.findall(r"<keb>", raw))
    n_readings = len(re.findall(r"<reb>", raw))
    n_gloss = len(re.findall(r"<gloss[ >]", raw))
    n_chars = len(re.findall(r"<character>", kd))
    ek: set[tuple[int, str]] = set()
    for entry in re.findall(r"<entry>.*?</entry>", raw, flags=re.DOTALL):
        seq = re.search(r"<ent_seq>(\d+)</ent_seq>", entry)
        assert seq
        for keb in re.findall(r"<keb>(.*?)</keb>", entry):
            ek.update((int(seq.group(1)), ch) for ch in keb if _is_cjk(ch))
    assert (n_entries, n_chars) == (2000, 612)

    with _connect(db_url) as conn:
        res = load_release(conn, _release(1), parse_jmdict(JMDICT), parse_kanjidic(KANJIDIC))
        assert res.entries == 2000 and res.retired == 0 and res.kanji == 612
        assert _count(conn, "dict_entry") == n_entries
        assert _count(conn, "dict_entry_form", "kind = 'kanji'") == n_kanji_forms
        assert _count(conn, "dict_entry_form", "kind = 'reading'") == n_readings
        assert _count(conn, "dict_entry_gloss") == n_gloss
        assert _count(conn, "kanji") == n_chars
        assert _count(conn, "entry_kanji") == len(ek)
        rows = conn.execute("SELECT seq, literal FROM ref.entry_kanji").fetchall()
        assert {(int(r[0]), str(r[1])) for r in rows} == ek
        # exactly one current release; nothing retired; norm is folded
        assert _count(conn, "dict_release", "is_current") == 1
        assert _count(conn, "dict_entry", "retired_release_id IS NOT NULL") == 0
        norm = conn.execute(
            "SELECT norm FROM ref.dict_entry_form WHERE seq = 1000110 AND kind = 'reading' "
            "AND ord = 0"
        ).fetchone()
        assert norm == ("しーでぃーぷれーやー",)
        senses = conn.execute("SELECT pos, headword FROM ref.dict_entry WHERE seq = 1000160")
        assert senses.fetchone() == (["n"], "Ｔシャツ")
        # every kanji row is complete enough to use
        assert _count(conn, "kanji", "jlpt IS NOT NULL") > 0
        assert (
            _count(conn, "kanji", "cardinality(on_readings) + cardinality(kun_readings) = 0") < 50
        )


def test_rerun_same_release_changes_nothing(db_url: str) -> None:
    with _connect(db_url) as conn:
        load_release(conn, _release(1), parse_jmdict(JMDICT), parse_kanjidic(KANJIDIC))
        before = _checksums(conn)
        load_release(conn, _release(1), parse_jmdict(JMDICT), parse_kanjidic(KANJIDIC))
        assert _checksums(conn) == before
        with pytest.raises(ValueError):  # same id, different version
            load_release(
                conn,
                ReleaseInfo(1, "other", "2026-11-12T00:00:00+00:00"),
                parse_jmdict(JMDICT),
                [],
            )


def test_retire_and_reappear(db_url: str) -> None:
    entries = list(parse_jmdict(JMDICT))
    a, b = entries[10], entries[20]

    def without(*gone: Entry) -> list[Entry]:
        return [e for e in entries if e not in gone]

    with _connect(db_url) as conn:
        load_release(conn, _release(1), entries, [])
        r2 = load_release(conn, _release(2), without(a), [])
        assert (r2.retired, r2.reappeared, r2.entries) == (1, 0, 1999)
        row = conn.execute(
            "SELECT retired_release_id, added_release_id FROM ref.dict_entry WHERE seq = %s",
            (a.seq,),
        ).fetchone()
        assert row == (2, 1)
        for t in ("dict_entry_form", "dict_entry_gloss"):  # retired entries lose search rows
            assert _count(conn, t, f"seq = {a.seq}") == 0
        assert _count(conn, "entry_kanji", f"seq = {a.seq}") == (
            len({ch for f in a.kanji for ch in f.text if _is_cjk(ch)})
        )
        assert _count(conn, "dict_entry", f"seq = {a.seq} AND headword <> ''") == 1  # row kept

        # A reappears, B vanishes.
        r3 = load_release(conn, _release(3), without(b), [])
        assert (r3.retired, r3.reappeared, r3.entries) == (1, 1, 1999)
        row = conn.execute(
            "SELECT retired_release_id, added_release_id FROM ref.dict_entry WHERE seq = %s",
            (a.seq,),
        ).fetchone()
        assert row == (None, 1)
        assert _count(conn, "dict_entry_form", f"seq = {a.seq}") == len(a.kanji) + len(a.readings)
        assert _count(conn, "dict_entry", f"seq = {b.seq} AND retired_release_id = 3") == 1
        assert _count(conn, "dict_entry_gloss", f"seq = {b.seq}") == 0
        cur = conn.execute("SELECT id FROM ref.dict_release WHERE is_current").fetchall()
        assert cur == [(3,)]
        with pytest.raises(ValueError):  # releases only move forward
            load_release(conn, _release(2), entries, [])


def _fake_sources() -> tuple[Sources, dict[str, bytes]]:
    jm = gzip.compress(JMDICT.read_bytes())
    kd = gzip.compress(KANJIDIC.read_bytes())
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("kradfile", "# c\n亜 : ｜ 一 口\n阿 : 阝 可\n".encode("euc-jp"))
        z.writestr("kradfile2", "唖 : 口 亜\n".encode("euc-jp"))
    kz = buf.getvalue()

    def h(b: bytes) -> str:
        return hashlib.sha256(b).hexdigest()

    s = Sources.edrdg(h(jm), h(kd), h(kz))
    return s, {s.jmdict.url: jm, s.kanjidic.url: kd, s.krad.url: kz}


def test_refresh_end_to_end_with_cache(db_url: str, tmp_path: Path) -> None:
    sources, blobs = _fake_sources()
    calls: list[str] = []

    def fetch(url: str, dest: Path) -> None:
        calls.append(url)
        dest.write_bytes(blobs[url])

    res = refresh(db_url, _release(1), sources, cache_dir=tmp_path, fetch=fetch)
    assert (res.entries, res.kanji) == (2000, 612)
    assert len(calls) == 3
    refresh(db_url, _release(1), sources, cache_dir=tmp_path, fetch=fetch)
    assert len(calls) == 3  # cache hit, no new download
    with _connect(db_url) as conn:
        row = conn.execute("SELECT components FROM ref.kanji WHERE literal = '亜'").fetchone()
        assert row == (["｜", "一", "口"],)
        row = conn.execute("SELECT components FROM ref.kanji WHERE literal = '阿'").fetchone()
        assert row == (["阝", "可"],)
        src = conn.execute("SELECT sources->'jmdict'->>'license' FROM ref.dict_release").fetchone()
        assert src == ("CC BY-SA 4.0",)


def test_refresh_checksum_mismatch_writes_nothing(db_url: str, tmp_path: Path) -> None:
    sources, blobs = _fake_sources()

    def fetch(url: str, dest: Path) -> None:
        dest.write_bytes(blobs[url] + (b"tampered" if url == sources.kanjidic.url else b""))

    with pytest.raises(ChecksumMismatch):
        refresh(db_url, _release(1), sources, cache_dir=tmp_path, fetch=fetch)
    with _connect(db_url) as conn:
        for t in TABLES:
            assert _count(conn, t) == 0
