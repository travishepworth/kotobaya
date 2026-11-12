from __future__ import annotations

import shutil
import zipfile
from pathlib import Path
from typing import Any

import psycopg
import pytest

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.extra.freq import parse_freq_zip
from kotobaya_worker.dict.extra.kanjium import parse_accents
from kotobaya_worker.dict.extra.load import FreqListConfig
from kotobaya_worker.dict.extra.pipeline import ExtraSources, FreqListSource, refresh_extra
from kotobaya_worker.dict.extra.tatoeba import parse_pairs
from kotobaya_worker.dict.fetch import ChecksumMismatch, SourceSpec, sha256_file
from kotobaya_worker.dict.jmdict import parse_jmdict
from kotobaya_worker.dict.kanjidic import parse_kanjidic
from kotobaya_worker.dict.load import load_release
from kotobaya_worker.dict.models import ReleaseInfo

DATA = Path(__file__).resolve().parent / "data"
FIXTURES = Path(__file__).resolve().parents[4] / "fixtures" / "dict"

# Floors for the coverage report, from the tiny inputs in data/ (see the counts asserted below).
FLOOR_PITCH = 9
FLOOR_FREQ_RANK = 8
FLOOR_EXAMPLES = 8

NEKO, HON, MOTO, GAKKOU = 1467640, 1522150, 1260670, 1206730
NIHONGO, NOMU, SENSEI, DENSHA, TENKI, BURASAGERU = (
    1464530,
    1169870,
    1387990,
    1443530,
    1438690,
    1011250,
)
CHECKED = (
    "SELECT 'pitch', md5(coalesce(string_agg(t::text, '|' ORDER BY seq, reading), '')), count(*) "
    "FROM ref.entry_pitch t UNION ALL "
    "SELECT 'list', md5(coalesce(string_agg(t::text, '|' ORDER BY id), '')), count(*) "
    "FROM ref.freq_list t UNION ALL "
    "SELECT 'freq', md5(coalesce(string_agg(t::text, '|' ORDER BY seq, list_id), '')), count(*) "
    "FROM ref.entry_freq t UNION ALL "
    "SELECT 'rank', md5(coalesce(string_agg(seq || ':' || freq_rank, '|' ORDER BY seq), '')), "
    "count(freq_rank) FROM ref.dict_entry UNION ALL "
    "SELECT 'sent', md5(coalesce(string_agg(t::text, '|' ORDER BY id), '')), count(*) "
    "FROM ref.example_sentence t UNION ALL "
    "SELECT 'link', md5(coalesce(string_agg(t::text, '|' ORDER BY sentence_id, seq), '')), "
    "count(*) FROM ref.example_sentence_entry t UNION ALL "
    "SELECT 'src', md5(sources::text), 0 FROM ref.dict_release"
)


def _zip(src_dir: Path, dest: Path) -> None:
    with zipfile.ZipFile(dest, "w") as z:
        for f in sorted(src_dir.glob("term_meta_bank_*.json")):
            z.write(f, f.name)


def _spec(name: str, path: Path) -> SourceSpec:
    return SourceSpec(name, f"test://{name}", sha256_file(path))


class Net:
    """Injected network: serves local files by URL and counts the calls."""

    def __init__(self, files: dict[str, Path]) -> None:
        self.files = files
        self.calls: list[str] = []

    def __call__(self, url: str, dest: Path) -> None:
        self.calls.append(url)
        shutil.copyfile(self.files[url], dest)


@pytest.fixture
def setup(tmp_path: Path) -> tuple[ExtraSources, Net]:
    za, zb = tmp_path / "a.zip", tmp_path / "b.zip"
    _zip(DATA / "freq_a", za)
    _zip(DATA / "freq_b", zb)
    files = {"kanjium.tsv": DATA / "accents.tsv", "a.zip": za, "b.zip": zb,
             "tatoeba.tsv": DATA / "tatoeba_jpn_eng.tsv"}  # fmt: skip
    specs = {k: _spec(k, p) for k, p in files.items()}
    net = Net({s.url: files[k] for k, s in specs.items()})
    sources = ExtraSources(
        kanjium=specs["kanjium.tsv"],
        frequency=(
            FreqListSource(FreqListConfig("list-a", "List A", 0), specs["a.zip"]),
            FreqListSource(FreqListConfig("list-b", "List B", 1), specs["b.zip"]),
        ),
        tatoeba=specs["tatoeba.tsv"],
    )
    return sources, net


def _load_dict(url: str) -> None:
    with psycopg.connect(to_conninfo(url)) as conn:
        info = ReleaseInfo(1, "2026.11.1", "2026-11-12T00:00:00+00:00")
        load_release(
            conn,
            info,
            parse_jmdict(FIXTURES / "JMdict_subset.xml"),
            parse_kanjidic(FIXTURES / "kanjidic2_subset.xml"),
        )


def _state(url: str) -> list[tuple[Any, ...]]:
    with psycopg.connect(to_conninfo(url)) as conn:
        return conn.execute(CHECKED).fetchall()


def test_parsers_see_every_row() -> None:
    # Nothing is silently skipped: row counts straight from the files.
    assert len(list(parse_accents(DATA / "accents.tsv"))) == 13
    assert len(list(parse_pairs(DATA / "tatoeba_jpn_eng.tsv"))) == 6  # duplicate id 2 collapsed


def test_freq_zip_parse(tmp_path: Path) -> None:
    z = tmp_path / "a.zip"
    _zip(DATA / "freq_a", z)
    rows = list(parse_freq_zip(z))
    assert len(rows) == 7  # 8 meta rows, one of them "pitch"
    assert {r.rank for r in rows if r.term == "先生"} == {80}
    bad = tmp_path / "bad.zip"
    with zipfile.ZipFile(bad, "w") as zf:
        zf.writestr("term_meta_bank_1.json", '[["x","freq",{"frequency":"many"}]]')
    with pytest.raises(ValueError):
        list(parse_freq_zip(bad))


def test_checksum_mismatch_writes_nothing(
    db_url: str, setup: tuple[ExtraSources, Net], tmp_path: Path
) -> None:
    sources, net = setup
    _load_dict(db_url)
    before = _state(db_url)
    assert sources.kanjium is not None
    bad = ExtraSources(kanjium=SourceSpec("kanjium.tsv", sources.kanjium.url, "0" * 64))
    with pytest.raises(ChecksumMismatch):
        refresh_extra(db_url, 1, bad, cache_dir=tmp_path / "c", fetch=net)
    assert _state(db_url) == before


def test_requires_loaded_release(
    db_url: str, setup: tuple[ExtraSources, Net], tmp_path: Path
) -> None:
    sources, net = setup
    with pytest.raises(ValueError, match="not loaded"):
        refresh_extra(db_url, 1, sources, cache_dir=tmp_path / "c", fetch=net)


def test_extra_load_coverage_and_idempotent(
    db_url: str,
    setup: tuple[ExtraSources, Net],
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    sources, net = setup
    _load_dict(db_url)
    cache = tmp_path / "cache"
    res = refresh_extra(db_url, 1, sources, cache_dir=cache, fetch=net)
    printed = capsys.readouterr().out
    assert "with pitch" in printed and "with examples" in printed
    print(printed)

    # Mapping counts: every input row is either loaded or counted as unmatched.
    assert res.pitch is not None and res.examples is not None
    assert (res.pitch.rows, res.pitch.unmatched, res.pitch.loaded) == (13, 2, 10)
    fa, fb = res.freq
    assert (fa.rows, fa.unmatched, fa.loaded) == (7, 1, 5)
    assert (fb.rows, fb.unmatched, fb.loaded) == (5, 0, 5)
    assert (res.examples.sentences, res.examples.unlinked) == (6, 1)

    rep = res.report
    assert rep.entries == 2000
    assert rep.with_pitch >= FLOOR_PITCH
    assert rep.with_freq_rank >= FLOOR_FREQ_RANK
    assert rep.with_examples >= FLOOR_EXAMPLES

    with psycopg.connect(to_conninfo(db_url)) as conn:
        pitch: dict[Any, Any] = dict(
            conn.execute("SELECT seq || ':' || reading, positions FROM ref.entry_pitch")
        )
        assert pitch[f"{NIHONGO}:にほんご"] == [0, 3]  # two rows merged, order kept
        assert pitch[f"{NIHONGO}:にっぽんご"] == [0]
        assert pitch[f"{HON}:ほん"] == [1] and pitch[f"{MOTO}:もと"] == [0]
        assert f"{NEKO}:ニャン" not in pitch
        # freq_rank: best rank within the highest-priority list that has the entry.
        rank: dict[Any, Any] = dict(
            conn.execute("SELECT seq, freq_rank FROM ref.dict_entry WHERE freq_rank > 0")
        )
        assert rank[NEKO] == 900  # list-a (priority 0) beats list-b's 5; min of 1200/900
        assert rank[HON] == 50 and rank[SENSEI] == 80 and rank[NIHONGO] == 400
        assert rank[NOMU] == 700 and rank[DENSHA] == 2000 and rank[TENKI] == 1500
        assert BURASAGERU not in rank and len(rank) == 8
        counts: dict[Any, Any] = dict(conn.execute("SELECT id, entry_count FROM ref.freq_list"))
        assert counts == {"list-a": 5, "list-b": 5}
        # examples
        links = {
            (int(s), int(q))
            for s, q in conn.execute("SELECT sentence_id, seq FROM ref.example_sentence_entry")
        }
        assert (1, NEKO) in links and (1, GAKKOU) in links
        assert {(2, SENSEI), (2, DENSHA), (2, NIHONGO), (3, TENKI), (4, NOMU)} <= links
        assert (5, BURASAGERU) in links  # ichidan stem of a conjugated form
        assert not [q for s, q in links if s == 6]
        row = conn.execute("SELECT translation, translation_id FROM ref.example_sentence "
                           "WHERE id = 2").fetchone()  # fmt: skip
        assert row == ("The teacher speaks Japanese on the train.", 102)
        row = conn.execute("SELECT translation, translation_id FROM ref.example_sentence "
                           "WHERE id = 6").fetchone()  # fmt: skip
        assert row == ("", None)
        src = conn.execute("SELECT sources FROM ref.dict_release").fetchone()
        assert src is not None and {"kanjium", "tatoeba", "freq:list-a"} <= set(src[0])

    # Idempotent re-run: identical rows and checksum, and nothing downloaded again.
    before, n_calls = _state(db_url), len(net.calls)
    res2 = refresh_extra(db_url, 1, sources, cache_dir=cache, fetch=net)
    assert _state(db_url) == before and len(net.calls) == n_calls
    assert res2.report == rep
