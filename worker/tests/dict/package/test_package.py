"""T103: device dictionary package: schema, diff round trip, budget, S3, Postgres mapping."""

from __future__ import annotations

import http.server
import json
import re
import sqlite3
import threading
from collections.abc import Iterator
from pathlib import Path
from typing import Any, ClassVar

import psycopg
import pytest
from synth import make_rows

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.package.release import (
    apply_diff_file,
    build_artifacts,
    build_release,
    decompress_file,
)
from kotobaya_worker.dict.package.s3 import S3Client, S3Config, blob_key
from kotobaya_worker.dict.package.schema import TABLES
from kotobaya_worker.dict.package.sqlite import (
    apply_diff,
    build_sqlite,
    check_package,
    compute_diff,
    open_readonly,
)

ROOT = Path(__file__).resolve().parents[4]
DICT_SQ = ROOT / "contracts/db/sqldelight/ref/kotobaya/db/ref/Dict.sq"
BUDGET_BYTES = 150 * 1000 * 1000  # docs/DECISIONS.md: phone dictionary size budget 150 MB


def dump(path: Path) -> dict[str, list[tuple[Any, ...]]]:
    con = open_readonly(path)
    try:
        return {
            t.name: con.execute(f"SELECT * FROM {t.name} ORDER BY {','.join(t.pk)}").fetchall()
            for t in TABLES
        }
    finally:
        con.close()


def schema_of(con: sqlite3.Connection) -> list[tuple[str, str, str]]:
    return sorted(
        (a, b, " ".join(c.split()))
        for a, b, c in con.execute("SELECT type, name, sql FROM sqlite_master WHERE sql NOT NULL")
    )


def test_schema_equals_dict_sq(tmp_path: Path) -> None:
    sq = re.sub(r"--[^\n]*", "", DICT_SQ.read_text(encoding="utf-8"))
    sq = sq.replace(" AS kotlin.Boolean", "")
    ref = sqlite3.connect(":memory:")
    ref.executescript(sq)
    build_sqlite(tmp_path / "p.sqlite", make_rows(1, 5))
    mine = open_readonly(tmp_path / "p.sqlite")
    assert schema_of(mine) == schema_of(ref)


def test_output_readonly_user_version_no_dangling(tmp_path: Path) -> None:
    p = tmp_path / "p.sqlite"
    counts = build_sqlite(p, make_rows(1, 50, extra=(900, 901)))
    assert counts["dict_entry"] == 52
    con = open_readonly(p)
    assert con.execute("PRAGMA user_version").fetchone()[0] == 1
    with pytest.raises(sqlite3.OperationalError):
        con.execute("DELETE FROM dict_entry")
    assert check_package(p) == []


def test_dangling_child_rows_rejected(tmp_path: Path) -> None:
    rows = make_rows(1, 5)
    rows["entry_freq"].append((999, "jpdb", 1))
    with pytest.raises(ValueError, match="dangling"):
        build_sqlite(tmp_path / "p.sqlite", rows)


def test_nothing_silently_dropped(tmp_path: Path) -> None:
    rows = make_rows(1, 40)
    rows["dict_entry"].append(rows["dict_entry"][0])  # duplicate pk must raise, not vanish
    with pytest.raises(sqlite3.IntegrityError):
        build_sqlite(tmp_path / "p.sqlite", rows)


def test_diff_a_to_b_equals_fresh_b(tmp_path: Path) -> None:
    a, b, fresh_b = tmp_path / "a.sqlite", tmp_path / "b.sqlite", tmp_path / "fb.sqlite"
    build_sqlite(a, make_rows(2, 60))
    kw: dict[str, Any] = {
        "retired": frozenset({7}),  # retired entry stays (flag flips)
        "changed": frozenset({10, 11}),  # content changes, child rows change
        "skip": frozenset({3, 4}),  # left the scope: rows and children deleted
        "extra": (61, 62, 63),  # added (and referenced-style, no freq_rank)
    }
    build_sqlite(b, make_rows(3, 60, **kw))
    build_sqlite(fresh_b, make_rows(3, 60, **kw))
    diff = compute_diff(a, b)
    assert diff["from_release"] == 2 and diff["to_release"] == 3
    entries = diff["tables"]["dict_entry"]
    assert sorted(r[0] for r in entries["upsert"]) == [7, 10, 11, 61, 62, 63]
    assert sorted(r[0] for r in entries["delete"]) == [3, 4]
    assert [r[0] for r in diff["tables"]["dict_release"]["delete"]] == [2]
    apply_diff(a, diff)
    assert dump(a) == dump(fresh_b)
    assert check_package(a) == []


def test_diff_survives_compressed_blobs(tmp_path: Path) -> None:
    a, b = tmp_path / "a.sqlite", tmp_path / "b.sqlite"
    build_sqlite(a, make_rows(2, 30))
    build_sqlite(b, make_rows(3, 30, changed=frozenset({5}), extra=(31,)))
    arts = build_artifacts(b, "top60k", tmp_path / "out", previous_package=a)
    assert [x.kind for x in arts] == ["full", "diff"]
    assert arts[1].from_release == 2 and arts[0].from_release is None
    full = tmp_path / "full.sqlite"
    decompress_file(arts[0].path, full)
    assert dump(full) == dump(b)
    apply_diff_file(a, arts[1].path)
    assert dump(a) == dump(b)
    entry = arts[1].manifest_entry()
    assert set(entry) == {"kind", "scope", "from_release", "blob_hash", "size_bytes"}
    assert re.fullmatch(r"[0-9a-f]{64}", entry["blob_hash"])


def test_apply_diff_rejects_wrong_base_and_is_atomic(tmp_path: Path) -> None:
    a, b, c = tmp_path / "a.sqlite", tmp_path / "b.sqlite", tmp_path / "c.sqlite"
    build_sqlite(a, make_rows(2, 20))
    build_sqlite(b, make_rows(3, 20, changed=frozenset({1})))
    build_sqlite(c, make_rows(4, 20))
    diff = compute_diff(a, b)
    before = dump(c)
    with pytest.raises(ValueError, match="diff is 2"):
        apply_diff(c, diff)
    assert dump(c) == before
    diff["row_counts"]["dict_entry"] += 1  # corrupt: post-apply verification fails, rolls back
    before_a = dump(a)
    with pytest.raises(ValueError, match="row counts"):
        apply_diff(a, diff)
    assert dump(a) == before_a


def test_top60k_budget_scaled(tmp_path: Path) -> None:
    """6,000 synthetic entries stand for 60,000 (x10); the scaled sizes must fit the budget."""
    n, scale = 6000, 10
    p = tmp_path / "p.sqlite"
    build_sqlite(p, make_rows(1, n))
    art = build_artifacts(p, "top60k", tmp_path / "out")[0]
    assert p.stat().st_size * scale < BUDGET_BYTES
    assert art.size_bytes * scale < BUDGET_BYTES
    print(
        f"synthetic: raw {p.stat().st_size / n:.0f} B/entry, zst {art.size_bytes / n:.0f} B/entry"
    )


class _FakeS3(http.server.BaseHTTPRequestHandler):
    store: ClassVar[dict[str, bytes]] = {}
    buckets: ClassVar[set[str]] = set()
    auth: ClassVar[list[str]] = []

    def log_message(self, *a: Any) -> None:
        pass

    def _bucket(self) -> str:
        return self.path.strip("/").split("/")[0]

    def do_HEAD(self) -> None:
        ok = self.path.strip("/") in self.store or (
            self.path.strip("/") == self._bucket() and self._bucket() in self.buckets
        )
        self.send_response(200 if ok else 404)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_PUT(self) -> None:
        n = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(n)
        self.auth.append(self.headers.get("Authorization", ""))
        if self.path.strip("/") == self._bucket():
            self.buckets.add(self._bucket())
        else:
            assert self._bucket() in self.buckets
            self.store[self.path.strip("/")] = body
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()


@pytest.fixture
def fake_s3() -> Iterator[str]:
    _FakeS3.store, _FakeS3.buckets, _FakeS3.auth = {}, set(), []
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _FakeS3)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{srv.server_address[1]}"
    srv.shutdown()


@pytest.mark.parametrize("access_key", ["", "ak"])
def test_upload_creates_bucket_content_addressed(
    tmp_path: Path, fake_s3: str, access_key: str
) -> None:
    p = tmp_path / "p.sqlite"
    build_sqlite(p, make_rows(1, 10))
    s3 = S3Client(S3Config(fake_s3, "kotobaya", access_key, "sk" if access_key else ""))
    art = build_artifacts(p, "top60k", tmp_path / "out", s3=s3)[0]
    stored = _FakeS3.store[f"kotobaya/{blob_key(art.blob_hash)}"]
    assert stored == art.path.read_bytes() and len(stored) == art.size_bytes
    assert all(a.startswith("AWS4-HMAC-SHA256") for a in _FakeS3.auth) == bool(access_key)
    build_artifacts(p, "top60k", tmp_path / "out", s3=s3)  # idempotent re-run


def _insert(cur: psycopg.Cursor[Any], rows: dict[str, list[tuple[Any, ...]]], rel: int) -> None:
    """Load synthetic device rows into ref.* (the inverse mapping, to test the reader)."""
    import json

    for r in rows["freq_list"]:
        cur.execute(
            "INSERT INTO ref.freq_list (id, title, priority) VALUES (%s,%s,%s)"
            " ON CONFLICT DO NOTHING",
            r,
        )
    for lit, on, kun, nan, mean, sc, gr, jl, fr, rad, comp in rows["kanji"]:
        cur.execute(
            "INSERT INTO ref.kanji VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)"
            " ON CONFLICT DO NOTHING",
            (lit, json.loads(on), json.loads(kun), json.loads(nan), json.loads(mean),
             sc, gr, jl, fr, rad, json.loads(comp)),
        )  # fmt: skip
    for s in rows["dict_entry"]:
        seq, lang, hw, hwr, com, kf, rd, sn, pos, misc, fr, ret = s
        cur.execute(
            "INSERT INTO ref.dict_entry (seq, language, headword, headword_reading, is_common,"
            " kanji_forms, readings, senses, pos, misc, freq_rank, added_release_id,"
            " retired_release_id) VALUES (%s,%s,%s,%s,%s,%s::jsonb,%s::jsonb,%s::jsonb,%s,%s,%s,"
            "%s,%s) ON CONFLICT (seq) DO UPDATE SET retired_release_id = excluded.retired_release_id,"
            " senses = excluded.senses",
            (seq, lang, hw, hwr, bool(com), kf, rd, sn, json.loads(pos), json.loads(misc), fr,
             1, rel if ret else None),
        )  # fmt: skip
    for r in rows["dict_entry_form"]:
        cur.execute(
            "INSERT INTO ref.dict_entry_form VALUES (%s,%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING",
            (*r[:5], bool(r[5])),
        )
    for r in rows["dict_entry_gloss"]:
        cur.execute(
            "INSERT INTO ref.dict_entry_gloss VALUES (%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING",
            r[:5],
        )
    for r in rows["entry_kanji"]:
        cur.execute("INSERT INTO ref.entry_kanji VALUES (%s,%s) ON CONFLICT DO NOTHING", r)
    for r in rows["entry_freq"]:
        cur.execute("INSERT INTO ref.entry_freq VALUES (%s,%s,%s) ON CONFLICT DO NOTHING", r)
    for seq, rd, pos in rows["entry_pitch"]:
        cur.execute(
            "INSERT INTO ref.entry_pitch VALUES (%s,%s,%s) ON CONFLICT DO NOTHING",
            (seq, rd, json.loads(pos)),
        )
    for r in rows["example_sentence"]:
        cur.execute("INSERT INTO ref.example_sentence VALUES (%s,%s,%s,%s,%s)", r)
    for r in rows["example_sentence_entry"]:
        cur.execute("INSERT INTO ref.example_sentence_entry VALUES (%s,%s)", r)


def _norm(row: tuple[Any, ...]) -> tuple[Any, ...]:
    """Postgres jsonb re-spaces JSON text; compare the parsed values."""
    import json

    return (*row[:5], *(json.loads(c) for c in row[5:10]), *row[10:])


def test_postgres_to_package_mapping_counts(tmp_path: Path, db_url: str) -> None:
    n = 40
    with psycopg.connect(to_conninfo(db_url)) as conn:
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO ref.dict_release (id, version, built_at, is_current, sources)"
                ' VALUES (1,\'2026.11.1\', now(), true, \'{"jmdict":{"version":"v"}}\')'
            )
            # entries 1..n with freq_rank = seq; 100 and 101 are unranked (referenced-only);
            # 102 is retired and referenced; 103 is retired and not referenced.
            rows = make_rows(1, n, extra=(100, 101, 102, 103), retired=frozenset({102, 103}))
            _insert(cur, rows, 1)
        conn.commit()
        out = tmp_path / "out"
        pkg, arts = build_release(conn, 1, "top60k", out, referenced=[100, 102], top_n=30)
        pkg_all, _ = build_release(conn, 1, "all", out)
    con = open_readonly(pkg)
    seqs = {r[0] for r in con.execute("SELECT seq FROM dict_entry")}
    assert seqs == {*range(1, 31), 100, 102}
    assert con.execute("SELECT retired FROM dict_entry WHERE seq = 102").fetchone() == (1,)
    assert con.execute("SELECT scope, version FROM dict_release").fetchone() == (
        "top60k",
        "2026.11.1",
    )
    # example sentences only for present entries; kanji complete
    ex = con.execute("SELECT count(*) FROM example_sentence").fetchone()[0]
    assert ex == len([s for s in range(1, 31) if s % 5 == 0] + [100])
    assert con.execute("SELECT count(*) FROM kanji").fetchone()[0] == len(rows["kanji"])
    assert check_package(pkg) == []
    # full scope equals what synth produced, row for row
    assert [_norm(r) for r in dump(pkg_all)["dict_entry"]] == [
        _norm(r) for r in sorted(rows["dict_entry"])
    ]
    assert dump(pkg_all)["dict_entry_gloss"] == sorted(rows["dict_entry_gloss"])
    assert [(a, b, json.loads(c)) for a, b, c in dump(pkg_all)["entry_pitch"]] == [
        (a, b, json.loads(c)) for a, b, c in sorted(rows["entry_pitch"])
    ]
    assert arts[0].kind == "full"
