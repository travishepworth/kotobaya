"""T401b: row accounting, corrections, snapshot validity, retry adoption, and the Postgres-backed path."""

from __future__ import annotations

import importlib.util
import json
import uuid
from collections.abc import Collection, Mapping
from pathlib import Path
from types import ModuleType
from typing import Any

import psycopg
import pytest
from psycopg.rows import dict_row

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.importers.jpdb_deck import Correction, plan_deck, pos_class, run_import
from kotobaya_worker.importers.jpdb_deck.client import (
    DeckVocabulary,
    SkippedRow,
    parse_vocab_list_html,
)
from kotobaya_worker.importers.jpdb_deck.repo import MediaState, PgDeckRepo
from kotobaya_worker.importers.jpdb_history.apply import ChildRow
from kotobaya_worker.importers.jpdb_history.ref import InMemoryRefLookup
from kotobaya_worker.media.publish.blobs import PgBlobTable
from kotobaya_worker.media.publish.handoff import PgPublishStore
from kotobaya_worker.media.publish.ids import ExistingContent, ExistingSegment, PgBaseReader

ROOT = Path(__file__).resolve().parents[4]
FIX = ROOT / "fixtures" / "jpdb" / "deck"
MEDIA_ID = "0193a1b2-0008-7000-8000-0000000000aa"


def validator() -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "c11_validate", ROOT / "contracts" / "db" / "media" / "validate.py"
    )
    assert spec is not None and spec.loader is not None
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def fixture_deck() -> DeckVocabulary:
    """The T401a page fixtures (9 rows: 8 words + 1 malformed) plus a repeat of 水 under another vid."""
    deck = DeckVocabulary(title="Sample Anime Deck")
    for i in (1, 2, 3):
        words, skipped, raw = parse_vocab_list_html(
            (FIX / f"vocabulary-list-{i}.html").read_text(encoding="utf-8")
        )
        deck.words.extend(words)
        deck.skipped.extend(skipped)
        deck.raw_rows += raw
    extra = type(deck.words[0])(1000099, "水", "みず", 4, None)
    deck.words.append(extra)
    deck.raw_rows += 1
    return deck


def ref() -> InMemoryRefLookup:
    r = InMemoryRefLookup()
    r.add(1000001, kanji=["番組"], readings=["ばんぐみ"])
    r.add(1000003, kanji=["食べる"], readings=["たべる"])
    r.add(1000005, kanji=["学校"], readings=["がっこう"])
    r.add(1000006, kanji=[], readings=["は"])
    r.add(5000, kanji=["水"], readings=["みず"])  # vid 1000007 and 1000099 both land here
    r.add(7777, kanji=["此"], readings=["こ"])  # only reachable through a correction
    return r


# ----------------------------------------------------------------- row accounting
def test_every_fetched_row_is_mapped_unmapped_or_skipped_and_duplicates_merge() -> None:
    deck = fixture_deck()
    plan = plan_deck(deck, {}, ref())
    st = plan.stats
    assert st.fetched == 10 and len(deck.skipped) == 1
    assert (st.mapped, st.unmapped, st.skipped) == (6, 3, 1)
    assert st.mapped + st.unmapped + st.skipped == st.fetched
    # 6 mapped rows -> 5 seqs; the two 水 rows are one entry with summed counts
    assert st.merged_entries == 5
    assert plan.entries[5000].count == 2 + 4
    assert plan.entries[1000006].count == 300
    # unmapped: この 197, ラーメン 3, でも 1 -- nothing dropped, all in vocab and in the token count
    assert plan.unmapped_token_count == 201
    assert [(v.jpdb_vid, v.occurrence_count, v.resolution) for v in plan.vocab] == [
        (1000002, 197, "unresolved"),
        (1000004, 3, "unresolved"),
        (1000008, 1, "unresolved"),
    ]
    assert plan.token_count == 11 + 42 + 25 + 300 + 6 + 201


def test_rows_with_a_bad_vid_are_skipped_not_dropped() -> None:
    deck = fixture_deck()
    deck.words.append(type(deck.words[0])(0, "x", "x", 5, None))
    deck.raw_rows += 1
    st = plan_deck(deck, {}, ref()).stats
    assert (st.mapped, st.unmapped, st.skipped, st.fetched) == (6, 3, 2, 11)


def test_pos_class_for_jpdb_occurrences() -> None:
    assert pos_class(frozenset({"n-pr"})) == "name"
    assert pos_class(frozenset({"prt"})) == "particle"
    assert pos_class(frozenset({"aux", "aux-v"})) == "auxiliary"
    assert pos_class(frozenset({"prt", "n"})) == "content"
    assert pos_class(frozenset()) == "content"


# ----------------------------------------------------------------- corrections
def test_stored_corrections_map_or_ignore_a_previously_unmapped_vid() -> None:
    deck = fixture_deck()
    corr = {1000002: Correction("mapped", 7777), 1000004: Correction("ignored")}
    plan = plan_deck(deck, corr, ref())
    st = plan.stats
    assert (st.mapped, st.unmapped, st.skipped, st.ignored) == (7, 2, 1, 1)
    assert plan.entries[7777].count == 197
    by_vid = {v.jpdb_vid: v for v in plan.vocab}
    assert by_vid[1000002].resolution == "mapped" and by_vid[1000002].entry_seq == 7777
    assert by_vid[1000004].resolution == "ignored"
    # ignored leaves the queue (unresolved) but its tokens stay unmapped
    assert [v.jpdb_vid for v in plan.vocab if v.resolution == "unresolved"] == [1000008]
    assert plan.unmapped_token_count == 3 + 1
    assert plan.token_count == 11 + 42 + 25 + 300 + 6 + 197 + 4
    # a correction to an entry the dictionary lacks is ignored (falls back to the automatic mapper)
    stale = plan_deck(deck, {1000002: Correction("mapped", 999)}, ref())
    assert stale.stats.unmapped == 3


# ----------------------------------------------------------------- handler, in memory
class Ctx:
    def __init__(self, job_id: uuid.UUID, payload: dict[str, Any]) -> None:
        self.job_id = job_id
        self.payload = payload
        self.cancelled = False

    def progress(self, fraction: float, message: str | None = None) -> None:
        pass

    def check_cancelled(self) -> None:
        if self.cancelled:
            raise RuntimeError("cancelled")


class FakeObjects:
    def __init__(self) -> None:
        self.objects: dict[str, bytes] = {}

    def put(self, blob_hash: str, data: bytes) -> None:
        self.objects[blob_hash] = data


class FakeBlobs:
    def __init__(self) -> None:
        self.rows: dict[str, int] = {}

    def insert_snapshot(self, blob_hash: str, size_bytes: int) -> None:
        self.rows.setdefault(blob_hash, size_bytes)


class FakeStore:
    def __init__(self) -> None:
        self.rows: dict[uuid.UUID, dict[str, Any]] = {}

    def succeeded_result(self, parent: uuid.UUID) -> Mapping[str, Any] | None:
        for r in self.rows.values():
            if r["payload"]["parent_job_id"] == str(parent) and r["status"] == "succeeded":
                return r["result"]  # type: ignore[no-any-return]
        return None

    def insert(self, child: uuid.UUID, parent: uuid.UUID, payload: dict[str, Any]) -> bool:
        if self.active_child(parent) is not None:
            return False
        self.rows[child] = {"payload": payload, "status": "queued", "result": None, "error": None}
        return True

    def active_child(self, parent: uuid.UUID) -> uuid.UUID | None:
        for k, r in self.rows.items():
            if r["payload"]["parent_job_id"] == str(parent) and r["status"] in (
                "queued",
                "running",
            ):
                return k
        return None

    def get(self, child: uuid.UUID) -> ChildRow | None:
        r = self.rows.get(child)
        return None if r is None else ChildRow(r["status"], r["result"], r["error"])


class FakeRepo:
    def __init__(self, corrections: Mapping[int, Correction] | None = None) -> None:
        self._corr = dict(corrections or {})
        self.claims: list[uuid.UUID] = []
        self.state: MediaState | None = None

    def claim_remap(self, job_id: uuid.UUID) -> None:
        self.claims.append(job_id)

    def media_state(self, media_id: str) -> MediaState | None:
        return self.state

    def corrections(self, vids: Collection[int]) -> Mapping[int, Correction]:
        return {v: c for v, c in self._corr.items() if v in vids}

    def entry_pos(self, seqs: Collection[int]) -> Mapping[int, frozenset[str]]:
        return {1000006: frozenset({"prt"})}


class FakeSource:
    def __init__(self, deck: DeckVocabulary) -> None:
        self.deck = deck
        self.calls = 0

    def fetch_deck(self, jpdb_id: str) -> DeckVocabulary:
        self.calls += 1
        return self.deck


class FakeBase:
    def __init__(self, existing: ExistingContent) -> None:
        self.existing = existing

    def read(self, media_id: str) -> ExistingContent:
        return self.existing


def server(store: FakeStore) -> Any:
    """Plays the media-publish runner: validates the payload with C-11's checker, then succeeds the child."""

    def step(_: float) -> None:
        for r in store.rows.values():
            if r["status"] != "queued":
                continue
            r.update(status="succeeded", result=RESULT)

    return step


def run_job(
    payload: dict[str, Any],
    repo: FakeRepo,
    objects: FakeObjects,
    blobs: FakeBlobs,
    store: FakeStore,
    job_id: uuid.UUID,
    existing: ExistingContent | None = None,
    deck: DeckVocabulary | None = None,
    sleep: Any = None,
) -> Mapping[str, Any]:
    return run_import(
        Ctx(job_id, payload),
        source=FakeSource(deck or fixture_deck()),
        repo=repo,
        ref=ref(),
        base_reader=FakeBase(existing or ExistingContent(0)),
        store=store,
        objects=objects,
        blobs=blobs,
        sleep=sleep or server(store),
        poll_interval=0,
    )


PAYLOAD = {
    "jpdb_id": "anime/9999",
    "media_id": MEDIA_ID,
    "title": "Sample Anime Deck",
    "kind": "anime",
    "deck": {"priority": 5},
}
RESULT = {
    "media_id": MEDIA_ID,
    "content_version": 1,
    "segments": 1,
    "sentences": 0,
    "tokens": 0,
    "unmapped_tokens": 0,
    "unique_entries": 0,
    "unmapped": [],
}


def test_snapshots_validate_against_c11_and_token_counts_add_up() -> None:
    v = validator()
    store, objects, blobs = FakeStore(), FakeObjects(), FakeBlobs()
    repo = FakeRepo({1000002: Correction("mapped", 7777), 1000004: Correction("ignored")})
    job = uuid.uuid4()
    run_job(PAYLOAD, repo, objects, blobs, store, job)
    (row,) = store.rows.values()
    payload = row["payload"]
    out = v.validate_publish(
        payload,
        objects.objects[payload["content"]["blob_hash"]],
        objects.objects[payload["deck_items"]["blob_hash"]],
    )
    # token_count = sum(occurrence.count) + unmapped_token_count is rule 3.3.6, enforced by the validator
    assert out["tokens"] == 11 + 42 + 25 + 300 + 6 + 197 + 4
    assert out["unmapped_tokens"] == 4 and out["unique_entries"] == 6
    assert payload["create"]["source"] == "jpdb" and payload["create"]["deck"]["priority"] == 5
    assert payload["unmapped"] == [
        {"jpdb_vid": 1000008, "spelling": "でも", "reading": "でも", "occurrence_count": 1}
    ]
    assert payload["jpdb"]["jpdb_id"] == "anime/9999"
    # the particle entry got its class from the dictionary POS
    rows = v.parse_lines(v.zstd_decompress(objects.objects[payload["content"]["blob_hash"]]))
    classes = {r["entry_seq"]: r["pos_class"] for r in rows if r["record"] == "occurrence"}
    assert classes[1000006] == "particle" and classes[1000001] == "content"
    assert all(r["sentence_ids"] == [] for r in rows if r["record"] == "occurrence")
    assert not repo.claims


def test_retry_gives_identical_snapshots_and_adopts_the_existing_child() -> None:
    store, objects, blobs = FakeStore(), FakeObjects(), FakeBlobs()
    repo, job = FakeRepo(), uuid.uuid4()
    calls: list[int] = []

    def no_server(_: float) -> None:
        calls.append(1)
        if len(calls) == 1:
            raise TimeoutError("worker died while waiting")

    with pytest.raises(TimeoutError):
        run_job(PAYLOAD, repo, objects, blobs, store, job, sleep=no_server)
    (child,) = store.rows
    first_blobs = dict(blobs.rows)
    # retry: same parent job id, new attempt
    run_job(PAYLOAD, repo, objects, blobs, store, job, sleep=server(store))
    assert list(store.rows) == [child]  # adopted, no second child
    assert blobs.rows == first_blobs  # identical bytes -> identical hashes, nothing new uploaded
    # and once succeeded, another attempt returns the stored result without a new child
    run_job(PAYLOAD, repo, objects, blobs, store, job)
    assert len(store.rows) == 1


def test_remap_claims_first_and_requires_a_live_jpdb_media() -> None:
    store, objects, blobs = FakeStore(), FakeObjects(), FakeBlobs()
    repo, job = FakeRepo(), uuid.uuid4()
    payload = {"jpdb_id": "anime/9999", "media_id": MEDIA_ID, "remap_only": True}
    with pytest.raises(RuntimeError, match="media_deleted"):
        run_job(payload, repo, objects, blobs, store, job)
    assert repo.claims == [job]
    repo.state = MediaState(live=False, source="jpdb")
    with pytest.raises(RuntimeError, match="media_deleted"):
        run_job(payload, repo, objects, blobs, store, job)
    repo.state = MediaState(live=True, source="jpdb")
    seg = str(uuid.uuid5(uuid.UUID(MEDIA_ID), "segment:0"))
    run_job(
        payload, repo, objects, blobs, store, job, ExistingContent(3, [ExistingSegment(seg, 0)])
    )
    (row,) = store.rows.values()
    assert row["payload"]["base_content_version"] == 3 and row["payload"]["create"] is None


def test_cancellation_is_honoured() -> None:
    ctx = Ctx(uuid.uuid4(), dict(PAYLOAD))
    ctx.cancelled = True
    with pytest.raises(RuntimeError, match="cancelled"):
        run_import(
            ctx,
            source=FakeSource(fixture_deck()),
            repo=FakeRepo(),
            ref=ref(),
            base_reader=FakeBase(ExistingContent(0)),
            store=FakeStore(),
            objects=FakeObjects(),
            blobs=FakeBlobs(),
        )


def test_skipped_rows_from_the_client_stay_counted() -> None:
    deck = DeckVocabulary(title="t", raw_rows=2, skipped=[SkippedRow("x", "bad")])
    deck.words.append(type(fixture_deck().words[0])(1000001, "番組", "ばんぐみ", 3, None))
    st = plan_deck(deck, {}, ref()).stats
    assert (st.fetched, st.mapped, st.skipped) == (2, 1, 1)


# ----------------------------------------------------------------- Postgres
def test_postgres_corrections_remap_claim_and_publish(db_url: str) -> None:
    v2 = (ROOT / "contracts" / "db" / "V2__jpdb_vocab.sql").read_text(encoding="utf-8")
    job = uuid.uuid4()
    with psycopg.connect(to_conninfo(db_url), autocommit=True, row_factory=dict_row) as c:
        c.execute(v2)
        c.execute(
            "insert into ref.dict_release (id, version, built_at, is_current) "
            "values (1, 'r1', now(), true)"
        )
        c.execute(
            "insert into ref.dict_entry (seq, headword, headword_reading, readings, senses, pos, "
            "added_release_id) values (1000006, 'は', 'は', '[{}]', '[]', '{prt}', 1)"
        )
        c.execute(
            "insert into app.jpdb_vocab (jpdb_vid, spelling, reading, resolution, entry_seq, "
            "first_seen_at, resolved_at) values "
            "(1000002, 'この', 'この', 'ignored', null, now(), now()), "
            "(1000004, 'ラーメン', 'ラーメン', 'unresolved', null, now(), null)"
        )
        c.execute(
            "insert into app.job (id, kind, payload, dedupe_key, status) values "
            "(%s, 'jpdb-deck-import', '{}', 'jpdb-deck-remap:x', 'running')",
            (job,),
        )
        repo = PgDeckRepo(c)
        assert repo.media_state(MEDIA_ID) is None
        assert dict(repo.corrections([1000002, 1000004, 5])) == {1000002: Correction("ignored")}
        assert repo.entry_pos([1000006, 5]) == {1000006: frozenset({"prt"})}
        objects, blobs = FakeObjects(), PgBlobTable(c)

        def runner(_: float) -> None:
            c.execute(
                "update app.job set status = 'succeeded', finished_at = now(), result = %s::jsonb "
                "where kind = 'media-publish' and status = 'queued'",
                (json.dumps({**RESULT, "tokens": 1, "unique_entries": 1}),),
            )

        payload = {"jpdb_id": "anime/9999", "media_id": MEDIA_ID, "title": "T", "remap_only": True}
        with pytest.raises(RuntimeError, match="media_deleted"):
            run_import(
                Ctx(job, payload), source=FakeSource(fixture_deck()), repo=repo, ref=ref(),
                base_reader=PgBaseReader(c), store=PgPublishStore(c), objects=objects, blobs=blobs,
            )  # fmt: skip
        # R1 ran first: the job's dedupe key is free
        assert c.execute("select dedupe_key from app.job where id = %s", (job,)).fetchone() == {
            "dedupe_key": None
        }
        # a full import of the same deck publishes through the real job table
        payload = {"jpdb_id": "anime/9999", "media_id": MEDIA_ID, "title": "T"}
        res = run_import(
            Ctx(job, payload), source=FakeSource(fixture_deck()), repo=repo, ref=ref(),
            base_reader=PgBaseReader(c), store=PgPublishStore(c), objects=objects, blobs=blobs,
            sleep=runner, poll_interval=0,
        )  # fmt: skip
        assert res["content_version"] == 1
        child = c.execute("select payload from app.job where kind = 'media-publish'").fetchone()
        assert child is not None
        p = child["payload"]
        v = validator()
        v.validate_publish(
            p,
            objects.objects[p["content"]["blob_hash"]],
            objects.objects[p["deck_items"]["blob_hash"]],
        )
        by_vid = {x["jpdb_vid"]: x["resolution"] for x in p["jpdb"]["vocab"]}
        assert by_vid == {1000002: "ignored", 1000004: "unresolved", 1000008: "unresolved"}
        assert c.execute("select count(*) as n from app.blob").fetchone() == {"n": 2}
