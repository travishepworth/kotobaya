"""T404b: media-import / media-parse over the fake parser and fake server (fixture agreement, append,
re-parse with overrides, cancellation, failures, audio, URL/paste)."""

from __future__ import annotations

import hashlib
import uuid
from collections.abc import Mapping, Sequence
from typing import Any

import pytest
from pipeline_support import (
    MEDIA,
    Ctx,
    FakeBlobTable,
    FakeObjects,
    FakeParser,
    FakeServer,
    FakeStore,
)

from kotobaya_worker.extract import ExtractedSentence
from kotobaya_worker.media.pipeline.import_job import run_import
from kotobaya_worker.media.pipeline.parse_job import run_parse
from kotobaya_worker.media.publish.snapshot import ContentArchive, DeckItemRec
from kotobaya_worker.parse.overrides import OverrideRow

MEDIA_ID = "0193a1b2-0007-7000-8000-00000000b404"
SRT = (MEDIA / "sample.srt").read_bytes()
EPUB = (MEDIA / "sample.epub").read_bytes()
HTML = (MEDIA / "sample.html").read_bytes()
BLOBS = {hashlib.sha256(b).hexdigest(): b for b in (SRT, EPUB, HTML)}
SRT_H, EPUB_H = hashlib.sha256(SRT).hexdigest(), hashlib.sha256(EPUB).hexdigest()
AUDIO_H = "a" * 64

# seq -> (count, pos_class, sentence ordinals): hand-computed from fake-parser's lexicon (pipeline_support).
SRT_OCCURRENCES: dict[int, tuple[int, str, tuple[int, ...]]] = {
    1001: (1, "content", (0,)), 1002: (1, "content", (1,)), 1003: (1, "content", (1,)),
    1004: (1, "content", (1,)), 1005: (1, "content", (2,)), 1006: (1, "content", (2,)),
    1007: (2, "content", (2, 8)), 1008: (1, "content", (3,)), 1009: (1, "content", (3,)),
    1010: (2, "content", (5, 6)), 1011: (1, "content", (6,)), 1012: (1, "content", (6,)),
    1013: (1, "content", (6,)), 1014: (1, "content", (7,)), 1015: (1, "content", (7,)),
    1016: (1, "content", (7,)), 1017: (1, "content", (7,)), 1018: (1, "content", (8,)),
    1019: (2, "content", (6, 8)),  # あ (other) + じゃあ (content): tie -> content
    1020: (1, "content", (3,)), 1021: (1, "content", (3,)),
    2001: (1, "particle", (1,)), 2002: (1, "particle", (1,)), 2003: (2, "particle", (2, 3)),
    2004: (1, "particle", (2,)), 2005: (2, "particle", (3, 6)), 2006: (2, "particle", (4, 6)),
    2007: (2, "particle", (5, 6)), 2008: (1, "particle", (7,)), 2009: (2, "particle", (6, 7)),
    2010: (1, "particle", (7,)),
    3001: (1, "auxiliary", (1,)), 3002: (2, "auxiliary", (2, 8)), 3003: (1, "auxiliary", (4,)),
}  # fmt: skip
EPUB_CH1: dict[int, tuple[int, str, tuple[int, ...]]] = {
    1001: (1, "content", (2,)), 1014: (1, "content", (0,)), 1015: (1, "content", (0,)),
    1021: (1, "content", (0,)), 1022: (1, "content", (0,)), 1023: (1, "content", (1,)),
    1024: (1, "content", (1,)), 1025: (1, "content", (1,)), 1027: (1, "content", (1,)),
    1028: (1, "content", (1,)), 1029: (1, "content", (2,)), 1030: (1, "content", (2,)),
    2006: (2, "particle", (1, 2)), 2008: (1, "particle", (0,)), 2009: (2, "particle", (1,)),
    2011: (2, "particle", (0, 1)), 2012: (1, "particle", (2,)),
    3003: (3, "auxiliary", (0, 1, 2)),
}  # fmt: skip
EPUB_CH2: dict[int, tuple[int, str, tuple[int, ...]]] = {
    1022: (1, "content", (1,)), 1031: (1, "content", (0,)), 1032: (1, "content", (0,)),
    1033: (1, "content", (0,)), 1034: (1, "content", (0,)), 1035: (1, "content", (1,)),
    1036: (1, "content", (1,)), 1037: (1, "content", (1,)), 1038: (1, "content", (1,)),
    2003: (2, "particle", (0, 1)), 2005: (1, "particle", (1,)), 2006: (1, "particle", (0,)),
    2009: (1, "particle", (0,)), 2011: (1, "particle", (1,)), 2013: (1, "particle", (1,)),
    3001: (1, "auxiliary", (1,)), 3003: (1, "auxiliary", (0,)),
}  # fmt: skip


class Env:
    def __init__(self, parser: FakeParser | None = None) -> None:
        self.store, self.objects = FakeStore(), FakeObjects()
        self.blobs = FakeBlobTable(self.objects)
        self.server = FakeServer(self.store, self.objects)
        self.parser = parser or FakeParser()
        self.cut_calls: list[tuple[str, int]] = []

    def cut(self, audio: str, sentences: Sequence[ExtractedSentence]) -> Mapping[int, str]:
        self.cut_calls.append((audio, len(sentences)))
        # every timed sentence but the first gets a clip; one clip per distinct timing
        return {
            i: hashlib.sha256(f"{s.start_ms}-{s.end_ms}".encode()).hexdigest()
            for i, s in enumerate(sentences)
            if i > 0 and s.start_ms is not None
        }

    def run_import(self, payload: dict[str, Any], ctx: Ctx | None = None) -> Mapping[str, Any]:
        ctx = ctx or Ctx()
        ctx.payload = {"media_id": MEDIA_ID, **payload}
        return run_import(
            ctx,
            fetch_blob=BLOBS.__getitem__,
            parser=self.parser,
            base_reader=self.server,
            store=self.store,
            objects=self.objects,
            blobs=self.blobs,
            cut=self.cut,
            fetch_url=lambda url, max_bytes, timeout: HTML,
            sleep=self.server.sleep,
        )

    def run_parse(self, ctx: Ctx | None = None) -> Mapping[str, Any]:
        ctx = ctx or Ctx()
        ctx.payload = {"media_id": MEDIA_ID}
        return run_parse(
            ctx,
            parser=self.parser,
            base_reader=self.server,
            store=self.store,
            objects=self.objects,
            blobs=self.blobs,
            sleep=self.server.sleep,
        )

    @property
    def content(self) -> ContentArchive:
        assert self.server.content is not None
        return self.server.content


def new_media(files: list[dict[str, Any]], **extra: Any) -> dict[str, Any]:
    return {"title": "サンプル", "kind": "anime", "source": "file", "files": files, **extra}


def occurrences_by_ordinal(
    content: ContentArchive, ordinal: int
) -> dict[int, tuple[int, str, tuple[int, ...]]]:
    seg = next(s for s in content.segments if s.ordinal == ordinal)
    sent_ord = {t.id: t.ordinal for t in content.sentences if t.segment_id == seg.id}
    return {
        o.entry_seq: (o.count, o.pos_class, tuple(sent_ord[i] for i in o.sentence_ids))
        for o in content.occurrences
        if o.segment_id == seg.id
    }


def assert_contract_identities(content: ContentArchive) -> None:
    """SNAPSHOT.md 3.3 rule 6 and the sentence_ids cap/order of rule 5, for every segment."""
    for seg in content.segments:
        occs = [o for o in content.occurrences if o.segment_id == seg.id]
        assert seg.token_count == sum(o.count for o in occs) + seg.unmapped_token_count
        ords = {t.id: t.ordinal for t in content.sentences if t.segment_id == seg.id}
        for o in occs:
            got = [ords[i] for i in o.sentence_ids]
            assert len(got) <= 20 and got == sorted(set(got))


# ---------------------------------------------------------------- coverage fixture agreement
def test_coverage_fixture_agreement_srt() -> None:
    env = Env()
    result = env.run_import(new_media([{"blob_hash": SRT_H, "filename": "sample.srt"}]))
    c = env.content
    (seg,) = c.segments
    assert (seg.kind, seg.title, seg.ordinal) == ("episode", "sample", 0)
    assert (seg.sentence_count, seg.token_count, seg.unmapped_token_count) == (9, 49, 6)
    assert occurrences_by_ordinal(c, 0) == SRT_OCCURRENCES
    assert_contract_identities(c)
    # timings carried; no clips without cut_audio
    first = min(c.sentences, key=lambda t: t.ordinal)
    assert (first.text, first.start_ms, first.end_ms, first.audio_blob_hash) == (
        "おはよう。", 1000, 3500, None,
    )  # fmt: skip
    # deck: 9 entries with count 2 ranked first (by seq), then the count-1 entries by seq
    assert env.server.deck is not None
    items = list(env.server.deck.items)
    twos = [1007, 1010, 1019, 2003, 2005, 2006, 2007, 2009, 3002]
    assert items[:9] == [DeckItemRec(s, i + 1, 2) for i, s in enumerate(twos)]
    ones = sorted(set(SRT_OCCURRENCES) - set(twos))
    assert items[9:] == [DeckItemRec(s, i + 10, 1) for i, s in enumerate(ones)]
    assert result == {
        "media_id": MEDIA_ID, "content_version": 1, "segments": 1, "sentences": 9,
        "tokens": 49, "unmapped_tokens": 6, "unique_entries": 34, "unmapped": [],
    }  # fmt: skip
    payload = next(iter(env.store.rows.values()))["payload"]
    assert payload["create"] == {
        "title": "サンプル", "kind": "anime", "source": "file", "jpdb_id": None,
        "deck": {"priority": 0, "enabled": True, "new_cards_per_day": None},
    }  # fmt: skip


def test_coverage_fixture_agreement_epub_one_segment_per_chapter() -> None:
    env = Env()
    result = env.run_import(
        new_media([{"blob_hash": EPUB_H, "filename": "sample.epub"}], kind="novel")
    )
    c = env.content
    assert [(s.ordinal, s.kind, s.title) for s in c.segments] == [
        (0, "chapter", "第一章"), (1, "chapter", "第二章"),
    ]  # fmt: skip
    assert [(s.sentence_count, s.token_count, s.unmapped_token_count) for s in c.segments] == [
        (3, 24, 1), (2, 18, 0),
    ]  # fmt: skip
    assert occurrences_by_ordinal(c, 0) == EPUB_CH1
    assert occurrences_by_ordinal(c, 1) == EPUB_CH2
    assert_contract_identities(c)
    loc = min(c.sentences, key=lambda t: (t.segment_id != c.segments[0].id, t.ordinal)).location
    assert loc == {"cfi": "/6/2!/p[1]"}  # JSON object, not a string
    assert env.server.deck is not None
    head = [(i.entry_seq, i.position, i.occurrence_count) for i in env.server.deck.items[:6]]
    assert head == [
        (3003, 1, 4),
        (2006, 2, 3),
        (2009, 3, 3),
        (2011, 4, 3),
        (1022, 5, 2),
        (2003, 6, 2),
    ]
    assert (result["segments"], result["sentences"], result["tokens"]) == (2, 5, 42)
    assert (result["unmapped_tokens"], result["unique_entries"]) == (1, 30)


def test_sentence_ids_capped_at_20_in_ordinal_order_through_the_pipeline() -> None:
    text = "\n\n".join(f"猫の{i}。" for i in range(25))
    env = Env()
    env.run_import({"title": "t", "kind": "web", "source": "paste", "text": text})
    c = env.content
    occ = {o.entry_seq: o for o in c.occurrences}
    assert occ[1010].count == 25 and len(occ[1010].sentence_ids) == 20
    assert occurrences_by_ordinal(c, 0)[1010][2] == tuple(range(20))
    assert_contract_identities(c)


# ---------------------------------------------------------------- append and re-parse
def test_append_keeps_existing_ids_and_adds_segments_after_the_last_ordinal() -> None:
    env = Env()
    env.run_import(new_media([{"blob_hash": SRT_H, "filename": "sample.srt", "title": "第1話"}]))
    v1 = env.content
    calls_v1 = len(env.parser.calls)
    result = env.run_import(
        {"source": "file", "files": [{"blob_hash": EPUB_H, "filename": "book.epub"}]}, Ctx()
    )
    v2 = env.content
    assert result["content_version"] == 2 and result["segments"] == 3
    assert [s.ordinal for s in v2.segments] == [0, 1, 2]
    seg0_v1, seg0_v2 = v1.segments[0], next(s for s in v2.segments if s.ordinal == 0)
    assert seg0_v2 == seg0_v1  # same id, title, counts
    old = {t.id: t for t in v1.sentences}
    kept = [t for t in v2.sentences if t.segment_id == seg0_v1.id]
    assert kept == sorted(old.values(), key=lambda t: t.ordinal)  # ids, tokens, timings verbatim
    assert {t.id for t in v2.sentences if t.segment_id != seg0_v1.id}.isdisjoint(old)
    assert len(env.parser.calls) - calls_v1 == 2  # only the two new chapters were parsed
    payload = [r["payload"] for r in env.store.rows.values()][-1]
    assert payload["create"] is None and payload["base_content_version"] == 1
    assert occurrences_by_ordinal(v2, 0) == SRT_OCCURRENCES
    assert occurrences_by_ordinal(v2, 2) == EPUB_CH2


def test_append_to_media_of_another_source_or_deleted_fails() -> None:
    env = Env()
    env.run_import(new_media([{"blob_hash": SRT_H, "filename": "sample.srt"}]))
    with pytest.raises(RuntimeError, match="source"):
        env.run_import({"source": "paste", "text": "猫"})
    env.server.live = False
    with pytest.raises(RuntimeError, match="media_deleted"):
        env.run_import({"source": "file", "files": [{"blob_hash": SRT_H, "filename": "b.srt"}]})
    assert env.server.version == 1


def test_reparse_after_override_changes_only_affected_tokens_and_keeps_ids() -> None:
    env = Env()
    env.run_import(new_media([{"blob_hash": SRT_H, "filename": "sample.srt"}]))
    v1 = env.content
    s5 = next(t for t in v1.sentences if t.ordinal == 5)  # （猫の鳴き声）
    s6 = next(t for t in v1.sentences if t.ordinal == 6)  # あ、猫が窓の外を見てる。
    env.parser.overrides = [
        OverrideRow("00000000-0000-7000-8000-000000000001", MEDIA_ID, None, "鳴き声",
                    ({"surface": "鳴き声", "seq": 1040, "class": "content"},), "h1"),
        OverrideRow("00000000-0000-7000-8000-000000000002", MEDIA_ID, s6.id, "猫",
                    ({"surface": "猫", "seq": 1050},), "h2"),
        OverrideRow("00000000-0000-7000-8000-000000000003", str(uuid.uuid4()), None, "窓",
                    ({"surface": "窓", "seq": 1060},), "h3"),  # another media: ignored
    ]  # fmt: skip
    result = env.run_parse()
    v2 = env.content
    assert result["content_version"] == 2
    assert [s.id for s in v2.segments] == [s.id for s in v1.segments]
    by_id = {t.id: t for t in v2.sentences}
    assert set(by_id) == {t.id for t in v1.sentences}
    for t in v1.sentences:
        new = by_id[t.id]
        assert (new.ordinal, new.text, new.start_ms, new.end_ms) == (
            t.ordinal, t.text, t.start_ms, t.end_ms,
        )  # fmt: skip
        changed = [(a, b) for a, b in zip(t.tokens, new.tokens, strict=True) if a != b]
        if t.id == s5.id:
            assert [(a["surface"], a["seq"], b["seq"], b["override"]) for a, b in changed] == [
                ("鳴き声", None, 1040, True)
            ]
        elif t.id == s6.id:
            assert [(a["surface"], a["seq"], b["seq"]) for a, b in changed] == [("猫", 1010, 1050)]
        else:
            assert changed == []
    occ = occurrences_by_ordinal(v2, 0)
    assert occ[1040] == (1, "content", (5,))
    assert occ[1050] == (1, "content", (6,))
    assert occ[1010] == (1, "content", (5,))  # 猫 in sentence 5 untouched (sentence scope)
    assert 1060 not in occ and occ[1011] == SRT_OCCURRENCES[1011]
    seg = v2.segments[0]
    assert (seg.token_count, seg.unmapped_token_count) == (49, 5)
    assert_contract_identities(v2)


def test_reparse_refuses_jpdb_unknown_and_deleted_media() -> None:
    env = Env()
    with pytest.raises(RuntimeError, match="unknown_media"):
        env.run_parse()
    env.run_import(new_media([{"blob_hash": SRT_H, "filename": "sample.srt"}]))
    env.server.source = "jpdb"
    with pytest.raises(RuntimeError, match="not_applicable"):
        env.run_parse()
    env.server.source, env.server.live = "file", False
    with pytest.raises(RuntimeError, match="media_deleted"):
        env.run_parse()
    assert env.server.version == 1


# ---------------------------------------------------------------- cancellation and failures
def test_cancellation_mid_import_publishes_nothing() -> None:
    env = Env()
    ctx = Ctx()

    def cancel() -> None:
        ctx.cancelled = True

    env.parser.on_call = cancel  # cancelled while the first segment is parsed
    files = [
        {"blob_hash": SRT_H, "filename": "ep1.srt"},
        {"blob_hash": SRT_H, "filename": "ep2.srt"},
    ]
    with pytest.raises(RuntimeError, match="cancelled"):
        env.run_import(new_media(files), ctx)
    assert len(env.parser.calls) == 1  # stopped between segments
    assert env.store.rows == {} and env.objects.objects == {} and env.blobs.rows == {}
    assert env.server.content is None


def test_cancelled_append_or_failing_parse_leaves_previous_version() -> None:
    env = Env()
    env.run_import(new_media([{"blob_hash": SRT_H, "filename": "sample.srt"}]))
    v1, children = env.content, len(env.store.rows)
    ctx = Ctx()
    ctx.cancelled = True
    with pytest.raises(RuntimeError, match="cancelled"):
        env.run_import(
            {"source": "file", "files": [{"blob_hash": SRT_H, "filename": "b.srt"}]}, ctx
        )

    def boom() -> None:
        raise ValueError("parser broke")

    env.parser.on_call = boom
    with pytest.raises(ValueError, match="parser broke"):
        env.run_parse()
    assert env.content is v1 and len(env.store.rows) == children


def test_file_without_text_fails_the_job() -> None:
    empty = b"1\n00:00:01,000 --> 00:00:02,000\n\n"
    h = hashlib.sha256(empty).hexdigest()
    BLOBS[h] = empty
    env = Env()
    with pytest.raises(ValueError, match="no text"):
        env.run_import(new_media([{"blob_hash": h, "filename": "empty.srt"}]))
    assert env.store.rows == {}


def test_retry_after_success_returns_the_published_result_without_work() -> None:
    env = Env()
    ctx = Ctx()
    first = env.run_import(new_media([{"blob_hash": SRT_H, "filename": "sample.srt"}]), ctx)
    calls, puts = len(env.parser.calls), env.objects.puts
    again = env.run_import(new_media([{"blob_hash": SRT_H, "filename": "sample.srt"}]), ctx)
    assert again == first and len(env.parser.calls) == calls and env.objects.puts == puts


def test_stale_base_replans_on_top_of_the_new_version() -> None:
    env = Env()
    env.run_import(new_media([{"blob_hash": SRT_H, "filename": "sample.srt"}]))
    real_sleep = env.server.sleep
    bumped = {"done": False}

    def concurrent_reparse_wins(t: float) -> None:
        if not bumped["done"]:  # another job published v2 meanwhile
            bumped["done"] = True
            assert env.server.content is not None
            env.server.content = ContentArchive(
                MEDIA_ID, 2, env.server.content.segments, env.server.content.sentences,
                env.server.content.occurrences,
            )  # fmt: skip
        real_sleep(t)

    ctx = Ctx()
    ctx.payload = {"media_id": MEDIA_ID, "source": "file",
                   "files": [{"blob_hash": EPUB_H, "filename": "b.epub"}]}  # fmt: skip
    result = run_import(
        ctx, fetch_blob=BLOBS.__getitem__, parser=env.parser, base_reader=env.server,
        store=env.store, objects=env.objects, blobs=env.blobs, sleep=concurrent_reparse_wins,
    )  # fmt: skip
    assert bumped["done"] and result["content_version"] == 3 and result["segments"] == 3


# ---------------------------------------------------------------- audio, URL, paste
def test_cut_audio_sets_sentence_audio_blob_hash() -> None:
    env = Env()
    files = [{"blob_hash": SRT_H, "filename": "ep1.srt", "audio_blob_hash": AUDIO_H}]
    env.run_import(new_media(files, options={"cut_audio": True}))
    assert env.cut_calls == [(AUDIO_H, 9)]
    sents = sorted(env.content.sentences, key=lambda t: t.ordinal)
    assert sents[0].audio_blob_hash is None  # the fake cutter failed this one: no clip, no error
    expect = hashlib.sha256(b"1000-3500").hexdigest()
    assert sents[1].audio_blob_hash == expect  # same cue as sentence 0, own clip entry
    assert all(t.audio_blob_hash for t in sents[1:])
    # without cut_audio the audio file is ignored
    env2 = Env()
    env2.run_import(new_media(files))
    assert env2.cut_calls == [] and all(t.audio_blob_hash is None for t in env2.content.sentences)


def test_url_and_paste_give_one_segment() -> None:
    env = Env()
    env.run_import(
        {"title": "記事", "kind": "web", "source": "url", "url": "https://example.com/a"}
    )
    (seg,) = env.content.segments
    assert seg.kind == "whole" and seg.sentence_count > 0
    paste = "第一章\n猫が来た。\n第二章\n猫の外。"
    env2 = Env()
    env2.run_import({"title": "貼り付け", "kind": "other", "source": "paste", "text": paste})
    (seg2,) = env2.content.segments
    assert (seg2.kind, seg2.title, seg2.sentence_count) == ("whole", "貼り付け", 2)
