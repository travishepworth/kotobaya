"""T404a: snapshot round-trip, id stability, retry/adoption, and the Postgres-backed paths."""

from __future__ import annotations

import hashlib
import uuid
from typing import Any

import psycopg
import pytest
from psycopg.rows import dict_row
from publish_support import (
    MEDIA_ID,
    Ctx,
    FakeBlobTable,
    FakeObjects,
    FakeStore,
    build_archives,
    db_url,  # noqa: F401
    existing_from,
    load_validate,
    pg_server,  # noqa: F401
)

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.media.publish.blobs import PgBlobTable, upload_snapshot
from kotobaya_worker.media.publish.handoff import (
    CreateSpec,
    PgPublishStore,
    PublishRequest,
    StaleBase,
    publish,
    publish_replanning,
)
from kotobaya_worker.media.publish.ids import ExistingContent, PgBaseReader
from kotobaya_worker.media.publish.snapshot import (
    DeckArchive,
    read_content,
    read_deck_items,
    write_content,
    write_deck_items,
)

TEXTS = [["おはよう。", "行くぞ。", "待て！"], ["ただいま。", "おかえり。"]]
RESULT = {
    "media_id": MEDIA_ID,
    "content_version": 1,
    "segments": 2,
    "sentences": 5,
    "tokens": 5,
    "unmapped_tokens": 0,
    "unique_entries": 3,
    "unmapped": [],
}


def _first() -> PublishRequest:
    content, deck = build_archives(TEXTS, ExistingContent(0), 1)
    return PublishRequest(MEDIA_ID, 0, content, deck, create=CreateSpec("テスト", "anime", "file"))


# ---------------------------------------------------------------- snapshots
def test_snapshot_round_trip_validates_and_is_deterministic() -> None:
    content, deck = build_archives(TEXTS, ExistingContent(0), 1)
    cb, db = write_content(content), write_deck_items(deck)
    rc = read_content(cb)
    seg_ord = {x.id: x.ordinal for x in content.segments}
    assert list(rc.segments) == sorted(content.segments, key=lambda x: x.ordinal)
    assert list(rc.sentences) == sorted(
        content.sentences, key=lambda x: (seg_ord[x.segment_id], x.ordinal)
    )
    assert list(rc.occurrences) == sorted(
        content.occurrences, key=lambda x: (seg_ord[x.segment_id], x.entry_seq)
    )
    assert (rc.media_id, rc.content_version, list(rc.grammar_spans)) == (MEDIA_ID, 1, [])
    assert write_content(rc) == cb
    rd = read_deck_items(db)
    assert (rd.deck_id, rd.deck_type, rd.content_version) == (MEDIA_ID, "media", 1)
    assert list(rd.items) == sorted(deck.items, key=lambda x: (x.position, x.entry_seq))
    v = load_validate()
    parsed = v.validate_content(v.parse_lines(v.zstd_decompress(cb)))
    v.validate_pair(parsed, v.validate_deck(v.parse_lines(v.zstd_decompress(db))))
    # the same records in another order, written again: identical bytes, identical hash
    shuffled = type(content)(
        content.media_id,
        content.content_version,
        list(reversed(content.segments)),
        list(reversed(content.sentences)),
        list(reversed(content.occurrences)),
    )
    assert write_content(shuffled) == cb
    assert hashlib.sha256(write_content(content)).hexdigest() == hashlib.sha256(cb).hexdigest()
    assert write_deck_items(DeckArchive("x", "media", 1, [])) == write_deck_items(
        DeckArchive("x", "media", 1, [])
    )


# ---------------------------------------------------------------- id stability
def test_id_stability_one_sentence_edited_keeps_all_other_ids() -> None:
    v1, _ = build_archives(TEXTS, ExistingContent(0), 1)
    edited = [["おはよう。", "行くぞ！", "待て！"], TEXTS[1]]
    v2, _ = build_archives(edited, existing_from(v1), 2)
    assert [s.id for s in v2.segments] == [s.id for s in v1.segments]
    old = {(s.segment_id, s.ordinal): s.id for s in v1.sentences}
    new = {(s.segment_id, s.ordinal): s.id for s in v2.sentences}
    changed = [k for k in new if new[k] != old[k]]
    assert len(changed) == 1
    seg0 = v1.segments[0].id
    assert changed == [(seg0, 1)]
    assert new[(seg0, 1)] not in old.values()


def test_id_stability_insert_shifts_ordinals_and_only_unshifted_keep_ids() -> None:
    v1, _ = build_archives(TEXTS, ExistingContent(0), 1)
    inserted = [["おはよう。", "えっ？", "行くぞ。", "待て！"], TEXTS[1]]
    v2, _ = build_archives(inserted, existing_from(v1), 2)
    old = {(s.segment_id, s.ordinal): s.id for s in v1.sentences}
    seg0 = v1.segments[0].id
    new = {(s.segment_id, s.ordinal): s.id for s in v2.sentences}
    assert new[(seg0, 0)] == old[(seg0, 0)]  # unchanged: kept
    for ordinal in (1, 2, 3):  # inserted + shifted: fresh ids, none reused
        assert new[(seg0, ordinal)] not in old.values()
    assert len(set(new.values())) == len(new)
    for key in [(v1.segments[1].id, 0), (v1.segments[1].id, 1)]:  # other segment untouched
        assert new[key] == old[key]
    # nothing but tokens change (same texts as v1 -> v3 vs a v2 with identical texts): every id kept
    v2b, _ = build_archives(inserted, existing_from(v2), 3)
    assert [s.id for s in v2b.sentences] == [s.id for s in v2.sentences]
    assert [s.id for s in v2b.segments] == [s.id for s in v2.segments]


def test_id_stability_returning_to_old_text_does_not_resurrect_ids() -> None:
    v1, _ = build_archives(TEXTS, ExistingContent(0), 1)
    v2, _ = build_archives([["おはよう。", "えっ？", "待て！"], TEXTS[1]], existing_from(v1), 2)
    v3, _ = build_archives(TEXTS, existing_from(v2), 3)
    ids1 = {s.id for s in v1.sentences}
    seg0 = v1.segments[0].id
    by_ord = {s.ordinal: s.id for s in v3.sentences if s.segment_id == seg0}
    assert by_ord[1] not in ids1  # base is v2's rows only; v1's id is gone for good
    assert by_ord[0] in ids1 and by_ord[2] in ids1


# ---------------------------------------------------------------- blobs, retry
def test_blob_upload_is_idempotent_and_row_follows_object() -> None:
    objs = FakeObjects()
    table = FakeBlobTable(objs)
    data = write_content(build_archives(TEXTS, ExistingContent(0), 1)[0])
    a = upload_snapshot(data, objs, table)
    b = upload_snapshot(data, objs, table)
    assert a == b and a.blob_hash == hashlib.sha256(data).hexdigest()
    assert list(table.rows) == [a.blob_hash]


def test_publish_retry_adopts_the_existing_child_and_uploads_nothing_new() -> None:
    store, objs = FakeStore(), FakeObjects()
    table = FakeBlobTable(objs)
    ctx = Ctx()
    req = _first()  # a retry re-plans the same content; ids of a new media are minted once per plan
    calls = {"n": 0}

    def crash(_: float) -> None:
        raise KeyboardInterrupt  # the first attempt dies while waiting for the child

    with pytest.raises(KeyboardInterrupt):
        publish(ctx, req, store=store, objects=objs, blobs=table, sleep=crash)
    assert len(store.rows) == 1 and len(table.rows) == 2
    (child,) = store.rows

    def server(_: float) -> None:
        calls["n"] += 1
        store.rows[child].update(status="succeeded", result=RESULT)

    res = publish(ctx, req, store=store, objects=objs, blobs=table, sleep=server)
    assert res.segments == 2 and res.content_version == 1
    assert (
        len(store.rows) == 1 and len(table.rows) == 2
    )  # adopted: no second child, no new blob row
    # payload validates against C-11's schema and matches the blobs
    p = store.rows[child]["payload"]
    v = load_validate()
    v.validate_publish(
        p, objs.objects[p["content"]["blob_hash"]], objs.objects[p["deck_items"]["blob_hash"]]
    )
    # a retry after success returns the stored result without building or uploading anything
    puts = objs.puts
    again = publish(ctx, req, store=store, objects=objs, blobs=table)
    assert again == res and objs.puts == puts


def test_stale_base_replans_and_fourth_stale_fails() -> None:
    store, objs = FakeStore(), FakeObjects()
    table = FakeBlobTable(objs)
    ctx = Ctx()
    planned = {"n": 0}

    def plan() -> PublishRequest:
        planned["n"] += 1
        return _first()

    def server(_: float) -> None:
        for r in store.rows.values():
            if r["status"] == "queued":
                r.update(status="failed", error="stale_base: moved")

    with pytest.raises(StaleBase):
        publish_replanning(ctx, plan, store=store, objects=objs, blobs=table, sleep=server)
    assert planned["n"] == 4


# ---------------------------------------------------------------- Postgres
def _insert_media(c: psycopg.Connection[Any], version: int) -> None:
    c.execute(
        "insert into app.media (id, title, kind, source, content_version, created_at, updated_at) "
        "values (%s, 't', 'anime', 'file', %s, now(), now())",
        (MEDIA_ID, version),
    )


def test_pg_base_reader_blobs_and_retry_against_postgres(db_url: str) -> None:  # noqa: F811
    v1, _ = build_archives(TEXTS, ExistingContent(0), 1)
    with psycopg.connect(to_conninfo(db_url), autocommit=True, row_factory=dict_row) as c:
        assert PgBaseReader(c).read(MEDIA_ID) == ExistingContent(0)
        _insert_media(c, 1)
        for s in v1.segments:
            c.execute(
                "insert into app.media_segment (id, media_id, ordinal, kind) "
                "values (%s, %s, %s, 'episode')",
                (s.id, MEDIA_ID, s.ordinal),
            )
        for t in v1.sentences:
            c.execute(
                "insert into app.sentence (id, media_id, segment_id, ordinal, text) "
                "values (%s, %s, %s, %s, %s)",
                (t.id, MEDIA_ID, t.segment_id, t.ordinal, t.text),
            )
        base = PgBaseReader(c).read(MEDIA_ID)
        assert base == existing_from(v1)

        # blob table: idempotent
        objs = FakeObjects()
        pgblobs = PgBlobTable(c)
        data = write_content(v1)
        upload_snapshot(data, objs, pgblobs)
        upload_snapshot(data, objs, pgblobs)
        row = c.execute("select kind, origin, content_type, size_bytes from app.blob").fetchall()
        assert row == [
            {
                "kind": "snapshot",
                "origin": "build",
                "content_type": "application/zstd",
                "size_bytes": len(data),
            }
        ]

        # publish v2 twice for the same parent: one child row, no second blob rows
        v2, deck2 = build_archives(TEXTS, base, 2)
        req = PublishRequest(MEDIA_ID, 1, v2, deck2)
        store = PgPublishStore(c)
        ctx = Ctx()

        def server(_: float) -> None:
            c.execute(
                "update app.job set status = 'succeeded', finished_at = now(), result = %s::jsonb "
                "where kind = 'media-publish' and status = 'queued'",
                ((f'{{"media_id":"{MEDIA_ID}","content_version":2,"segments":2,"sentences":5,"tokens":5,'
                 '"unmapped_tokens":0,"unique_entries":3,"unmapped":[]}'),),
            )  # fmt: skip

        first = publish(ctx, req, store=store, objects=objs, blobs=pgblobs, sleep=server)
        second = publish(ctx, req, store=store, objects=objs, blobs=pgblobs)
        assert first == second and first.content_version == 2
        assert c.execute("select count(*) as n from app.job").fetchone() == {"n": 1}
        assert c.execute("select count(*) as n from app.blob").fetchone() == {"n": 3}
        job = c.execute("select dedupe_key, max_attempts from app.job").fetchone()
        assert job == {"dedupe_key": f"media-publish:{ctx.job_id}", "max_attempts": 1}
        assert uuid.UUID(MEDIA_ID)
