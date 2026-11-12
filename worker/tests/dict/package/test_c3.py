"""T103b: DICT_PACKAGE.md (C-3) corruption guards: digests, diff base, strict apply, blob rows."""

from __future__ import annotations

import copy
import hashlib
import sqlite3
import struct
from collections.abc import Callable
from pathlib import Path
from typing import Any

import psycopg
import pytest
import zstandard
from psycopg.types.json import Jsonb
from synth import make_rows
from test_package import _FakeS3, _insert, dump, fake_s3  # noqa: F401

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.package.release import (
    apply_diff_file,
    build_artifacts,
    build_release,
    decompress_file,
    fetch_base,
)
from kotobaya_worker.dict.package.s3 import S3Client, S3Config
from kotobaya_worker.dict.package.schema import DDL, TABLE_BY_NAME, TABLES
from kotobaya_worker.dict.package.source import referenced_seqs
from kotobaya_worker.dict.package.sqlite import (
    EMPTY_TABLE_DIGEST,
    apply_diff,
    build_sqlite,
    compute_diff,
    open_readonly,
    table_digest,
    table_digests,
)

Diff = dict[str, Any]


# --- table digest (3.5) -------------------------------------------------------------------


def _mem() -> sqlite3.Connection:
    con = sqlite3.connect(":memory:")
    con.executescript(DDL)
    return con


def test_empty_table_digest_is_spec_value() -> None:
    assert EMPTY_TABLE_DIGEST == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    con = _mem()
    for t in TABLES:
        assert table_digest(con, t) == EMPTY_TABLE_DIGEST
    assert hashlib.sha256(b"").hexdigest() == EMPTY_TABLE_DIGEST


def test_digest_exact_encoding() -> None:
    con = _mem()
    con.execute("INSERT INTO example_sentence VALUES (-2, 'ja', 'é', '', NULL)")
    con.execute("INSERT INTO example_sentence VALUES (1, 'ja', 'x', 'y', 7)")
    # ORDER BY id: -2 first. null = 00, int = 01 + int64 BE, text = 02 + u32 BE length + UTF-8.
    want = (
        b"\x01" + struct.pack(">q", -2)
        + b"\x02\x00\x00\x00\x02ja"
        + b"\x02\x00\x00\x00\x02" + "é".encode()
        + b"\x02\x00\x00\x00\x00"
        + b"\x00"
        + b"\x01" + struct.pack(">q", 1)
        + b"\x02\x00\x00\x00\x02ja"
        + b"\x02\x00\x00\x00\x01x"
        + b"\x02\x00\x00\x00\x01y"
        + b"\x01" + struct.pack(">q", 7)
    )  # fmt: skip
    assert table_digest(con, TABLE_BY_NAME["example_sentence"]) == hashlib.sha256(want).hexdigest()


def test_digest_rejects_real_values() -> None:
    con = _mem()
    con.execute(
        "INSERT INTO kanji VALUES ('a','[]','[]','[]','[]', 1.5, NULL, NULL, NULL, NULL, '[]')"
    )
    with pytest.raises(ValueError, match="float"):
        table_digest(con, TABLE_BY_NAME["kanji"])


# --- diff envelope and strict apply (3.2, 3.4) --------------------------------------------


def _pair(tmp_path: Path) -> tuple[Path, Path, Diff]:
    a, b = tmp_path / "a.sqlite", tmp_path / "b.sqlite"
    build_sqlite(a, make_rows(2, 60))
    build_sqlite(b, make_rows(3, 60, changed=frozenset({10}), skip=frozenset({3}), extra=(61,)))
    return a, b, compute_diff(a, b)


def test_diff_carries_exact_table_digests(tmp_path: Path) -> None:
    _, b, diff = _pair(tmp_path)
    con = open_readonly(b)
    assert list(diff["table_digests"]) == [t.name for t in TABLES]
    assert diff["table_digests"] == table_digests(con)
    assert diff["row_counts"].keys() == diff["tables"].keys() == diff["table_digests"].keys()


def test_apply_good_diff_matches_target(tmp_path: Path) -> None:
    a, b, diff = _pair(tmp_path)
    apply_diff(a, diff)
    assert dump(a) == dump(b)


def test_digest_mismatch_with_same_counts_rejected(tmp_path: Path) -> None:
    a, _, diff = _pair(tmp_path)
    before = dump(a)
    bad = copy.deepcopy(diff)
    rows = bad["tables"]["dict_entry_gloss"]["upsert"]
    assert rows
    rows[0][4] = rows[0][4] + "!"  # same row count, different content
    assert bad["row_counts"] == diff["row_counts"]
    with pytest.raises(ValueError, match="digests"):
        apply_diff(a, bad)
    assert dump(a) == before  # rolled back


def test_digest_value_tampered_rejected(tmp_path: Path) -> None:
    a, _, diff = _pair(tmp_path)
    before = dump(a)
    diff["table_digests"]["kanji"] = "0" * 64
    with pytest.raises(ValueError, match="digests"):
        apply_diff(a, diff)
    assert dump(a) == before


def test_wrong_base_same_release_id_rejected(tmp_path: Path) -> None:
    """Same (id, scope) as the diff's from_release, but different content: only digests see it."""
    _a, _, diff = _pair(tmp_path)
    wrong = tmp_path / "wrong.sqlite"
    build_sqlite(wrong, make_rows(2, 60))
    con = sqlite3.connect(wrong)  # same counts as `a`, one row's content differs
    con.execute("UPDATE dict_entry_gloss SET gloss_lower = 'zz' WHERE seq = 20 AND gloss_ord = 0")
    con.commit()
    con.close()
    before = dump(wrong)
    with pytest.raises(ValueError, match="digests"):
        apply_diff(wrong, diff)
    assert dump(wrong) == before


def test_wrong_base_release_rejected(tmp_path: Path) -> None:
    _, _, diff = _pair(tmp_path)
    other = tmp_path / "r4.sqlite"
    build_sqlite(other, make_rows(4, 60))
    before = dump(other)
    with pytest.raises(ValueError, match="diff is 2"):
        apply_diff(other, diff)
    assert dump(other) == before


def test_wrong_scope_rejected(tmp_path: Path) -> None:
    _, _, diff = _pair(tmp_path)
    other = tmp_path / "all2.sqlite"
    build_sqlite(other, make_rows(2, 60, scope="all"))
    with pytest.raises(ValueError, match="diff is 2/top60k"):
        apply_diff(other, diff)


def _drop_digests(d: Diff) -> None:
    del d["table_digests"]


def _drop_digest_key(d: Diff) -> None:
    del d["table_digests"]["kanji"]


def _extra_count_key(d: Diff) -> None:
    d["row_counts"]["bogus"] = 0


def _missing_count_key(d: Diff) -> None:
    del d["row_counts"]["entry_pitch"]


def _extra_table(d: Diff) -> None:
    d["tables"]["bogus"] = {"columns": [], "pk": [], "upsert": [], "delete": []}


def _missing_table(d: Diff) -> None:
    del d["tables"]["kanji"]


def _bad_pk(d: Diff) -> None:
    d["tables"]["dict_entry_form"]["pk"] = ["seq"]


def _bad_columns(d: Diff) -> None:
    d["tables"]["kanji"]["columns"] = d["tables"]["kanji"]["columns"][::-1]


def _bad_version(d: Diff) -> None:
    d["format_version"] = 2


def _bad_format(d: Diff) -> None:
    d["format"] = "other"


@pytest.mark.parametrize(
    "mutate",
    [
        _drop_digests,
        _drop_digest_key,
        _extra_count_key,
        _missing_count_key,
        _extra_table,
        _missing_table,
        _bad_pk,
        _bad_columns,
        _bad_version,
        _bad_format,
    ],
)
def test_malformed_envelope_rejected_without_touching_file(
    tmp_path: Path, mutate: Callable[[Diff], None]
) -> None:
    a, _, diff = _pair(tmp_path)
    before = dump(a)
    mutate(diff)
    with pytest.raises(ValueError):
        apply_diff(a, diff)
    assert dump(a) == before


def test_to_release_mismatch_rejected(tmp_path: Path) -> None:
    a, _, diff = _pair(tmp_path)
    before = dump(a)
    diff["to_release"] = 99
    with pytest.raises(ValueError):
        apply_diff(a, diff)
    assert dump(a) == before


def test_foreign_key_check_failure_rolls_back(tmp_path: Path) -> None:
    """Counts and digests all match the (corrupt) target, so only foreign_key_check catches it."""
    a, b, _ = _pair(tmp_path)
    bad = tmp_path / "bad.sqlite"
    bad.write_bytes(b.read_bytes())
    con = sqlite3.connect(bad)
    con.execute("INSERT INTO entry_freq VALUES (9999, 'jpdb', 1)")  # dangling parent
    con.commit()
    con.close()
    diff = compute_diff(a, bad)
    before = dump(a)
    with pytest.raises(ValueError, match="foreign_key_check"):
        apply_diff(a, diff)
    assert dump(a) == before


def test_file_with_wrong_user_version_rejected(tmp_path: Path) -> None:
    a, _, diff = _pair(tmp_path)
    con = sqlite3.connect(a)
    con.execute("PRAGMA user_version = 2")
    con.commit()
    con.close()
    with pytest.raises(ValueError, match="user_version"):
        apply_diff(a, diff)


# --- manifest entries ---------------------------------------------------------------------


def test_manifest_has_format_version_and_uncompressed_size(tmp_path: Path) -> None:
    a, b, _ = _pair(tmp_path)
    arts = build_artifacts(b, "top60k", tmp_path / "out", base=a)
    for art in arts:
        e = art.manifest_entry()
        assert e["format_version"] == 1
        raw = zstandard.ZstdDecompressor().stream_reader(art.path.open("rb")).read()
        assert e["uncompressed_size_bytes"] == len(raw)
        assert e["size_bytes"] == art.path.stat().st_size
    assert arts[0].manifest_entry()["uncompressed_size_bytes"] == b.stat().st_size


# --- diff base = the published file (1.5) -------------------------------------------------


def _published(tmp_path: Path) -> tuple[list[dict[str, Any]], bytes, dict[str, bytes]]:
    p = tmp_path / "p2.sqlite"
    build_sqlite(p, make_rows(2, 30))
    art = build_artifacts(p, "top60k", tmp_path / "pub")[0]
    data = art.path.read_bytes()
    return [art.manifest_entry()], data, {art.blob_hash: data}


def test_fetch_base_returns_published_file(tmp_path: Path) -> None:
    pub, _, store = _published(tmp_path)
    got = fetch_base(pub, 2, "top60k", store.get, tmp_path / "w")
    assert got is not None
    assert dump(got) == dump(tmp_path / "p2.sqlite")


def test_fetch_base_rejects_hash_mismatch(tmp_path: Path) -> None:
    pub, data, _ = _published(tmp_path)
    corrupted = data[:-1] + bytes([data[-1] ^ 1])
    assert fetch_base(pub, 2, "top60k", lambda _h: corrupted, tmp_path / "w") is None


def test_fetch_base_missing_blob_or_artifact(tmp_path: Path) -> None:
    pub, _, _ = _published(tmp_path)
    assert fetch_base(pub, 2, "top60k", lambda _h: None, tmp_path / "w") is None
    assert fetch_base([], 2, "top60k", lambda _h: b"x", tmp_path / "w") is None  # no artifacts
    assert fetch_base(pub, 2, "all", lambda _h: b"x", tmp_path / "w") is None  # other scope
    diff_only = [{**pub[0], "kind": "diff"}]
    assert fetch_base(diff_only, 2, "top60k", lambda _h: b"x", tmp_path / "w") is None


def test_fetch_base_rejects_file_of_another_release(tmp_path: Path) -> None:
    """Hash is right but the file is release 2 while the caller expects release 5."""
    pub, _, store = _published(tmp_path)
    assert fetch_base(pub, 5, "top60k", store.get, tmp_path / "w") is None


def test_fetch_base_rejects_undecodable_bytes(tmp_path: Path) -> None:
    junk = b"not zstd at all"
    pub = [
        {
            "kind": "full", "scope": "top60k", "from_release": None, "format_version": 1,
            "blob_hash": hashlib.sha256(junk).hexdigest(), "size_bytes": len(junk),
            "uncompressed_size_bytes": 1,
        }
    ]  # fmt: skip
    assert fetch_base(pub, 2, "top60k", lambda _h: junk, tmp_path / "w") is None


# --- Postgres: chain, blob rows, restart, referenced set ----------------------------------


def _release(cur: psycopg.Cursor[Any], rid: int, current: bool) -> None:
    cur.execute(
        "INSERT INTO ref.dict_release (id, version, built_at, is_current, sources)"
        " VALUES (%s, %s, now(), %s, '{}')",
        (rid, f"2026.11.{rid}", current),
    )


def _publish(conn: psycopg.Connection[Any], rid: int, arts: list[Any]) -> None:
    conn.execute(
        "UPDATE ref.dict_release SET artifacts = %s WHERE id = %s",
        (Jsonb([a.manifest_entry() for a in arts]), rid),
    )
    conn.commit()


def test_release_chain_blob_rows_and_published_base(
    tmp_path: Path,
    db_url: str,
    fake_s3: str,  # noqa: F811
) -> None:
    s3 = S3Client(S3Config(fake_s3, "kotobaya"))
    with psycopg.connect(to_conninfo(db_url)) as conn:
        with conn.cursor() as cur:
            _release(cur, 1, False)
            _release(cur, 2, True)
            _insert(cur, make_rows(1, 40), 1)
        conn.commit()

        pkg1, arts1 = build_release(conn, 1, "all", tmp_path / "o1", s3=s3)
        assert [a.kind for a in arts1] == ["full"]  # nothing older: no diff
        row = conn.execute(
            "SELECT hash, size_bytes, content_type, kind, origin FROM app.blob"
        ).fetchall()
        assert row == [
            (arts1[0].blob_hash, arts1[0].size_bytes, "application/zstd", "snapshot", "build")
        ]
        assert f"kotobaya/{arts1[0].blob_hash}" in _FakeS3.store  # bare key
        _publish(conn, 1, arts1)

        # ref.* now holds newer content than the published release 1.
        conn.execute("UPDATE ref.dict_entry SET headword = '改' WHERE seq = 1")
        conn.commit()

        # Fresh out_dir and no previous_package: the base comes from the published blob.
        pkg2, arts2 = build_release(conn, 2, "all", tmp_path / "o2", s3=s3)
        assert [(a.kind, a.from_release) for a in arts2] == [("full", None), ("diff", 1)]
        hashes = {r[0] for r in conn.execute("SELECT hash FROM app.blob").fetchall()}
        assert hashes == {a.blob_hash for a in (*arts1, *arts2)}
        dev = tmp_path / "device.sqlite"
        decompress_file(arts1[0].path, dev)
        apply_diff_file(dev, arts2[1].path)
        assert dump(dev) == dump(pkg2)
        assert dump(dev) != dump(pkg1)

        # Published base gone (e.g. bucket wiped): no diff, never a rebuild from ref.*.
        _FakeS3.store.pop(f"kotobaya/{arts1[0].blob_hash}")
        _, arts = build_release(conn, 2, "all", tmp_path / "o3", s3=s3)
        assert [a.kind for a in arts] == ["full"]

        # Published base corrupted: no diff.
        _FakeS3.store[f"kotobaya/{arts1[0].blob_hash}"] = b"garbage"
        _, arts = build_release(conn, 2, "all", tmp_path / "o4", s3=s3)
        assert [a.kind for a in arts] == ["full"]

        # Release 1 lists no artifacts (restart before it was published): no diff.
        _FakeS3.store[f"kotobaya/{arts1[0].blob_hash}"] = arts1[0].path.read_bytes()
        _publish(conn, 1, [])
        _, arts = build_release(conn, 2, "all", tmp_path / "o5", s3=s3)
        assert [a.kind for a in arts] == ["full"]


def test_referenced_set_notes_word_status_deck_items(db_url: str) -> None:
    with psycopg.connect(to_conninfo(db_url)) as conn:
        dev = "00000000-0000-7000-8000-0000000000aa"
        ntype = "00000000-0000-7000-8000-000000000101"
        conn.execute("INSERT INTO app.device (id, name, kind) VALUES (%s, 'd', 'script')", (dev,))
        conn.execute(
            "INSERT INTO app.word_status (entry_seq, status, auto_status, updated_at)"
            " VALUES (5, 'known', 'known', now())"
        )
        for seq, deleted in ((6, False), (7, True)):
            conn.execute(
                "INSERT INTO app.note (id, note_type_id, type, entry_seq, created_by_device_id,"
                " created_at, updated_at, deleted_at) VALUES (gen_random_uuid(), %s, 'vocab', %s,"
                " %s, now(), now(), CASE WHEN %s THEN now() END)",
                (ntype, seq, dev, deleted),
            )
        conn.execute(
            "INSERT INTO app.deck (id, name, type, created_at, updated_at)"
            " VALUES ('00000000-0000-7000-8000-0000000000d1', 'd', 'user', now(), now())"
        )
        for seq, deleted in ((8, False), (9, True)):
            conn.execute(
                "INSERT INTO app.deck_item (deck_id, item_key, entry_seq, added_at, deleted_at)"
                " VALUES ('00000000-0000-7000-8000-0000000000d1', %s, %s, now(),"
                " CASE WHEN %s THEN now() END)",
                (f"e:{seq}", seq, deleted),
            )
        conn.commit()
        assert referenced_seqs(conn) == [5, 6, 8]
