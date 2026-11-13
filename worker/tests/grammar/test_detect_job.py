"""T506a: grammar-detect over the T404b fake server (real publish protocol, contract validate.py).

Idempotent (second run publishes nothing, same spans); a rule edit removes stale detected spans;
manual spans are never read, published or touched; drafts and invalid rules are skipped; fan-out.
"""

from __future__ import annotations

import importlib.util
import uuid
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

from kotobaya_worker.grammar.detect import (
    DetectSentence,
    PointRules,
    compile_points,
    detect_spans,
    rules_digest,
)
from kotobaya_worker.grammar.job import dedupe_key, run_detect_media, run_fan_out
from kotobaya_worker.media.publish.snapshot import (
    ContentArchive,
    GrammarSpanRec,
    SegmentRec,
    SentenceRec,
)
from kotobaya_worker.parse.segment import segment

ROOT = Path(__file__).resolve().parents[3]


def _load(name: str, path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


ps = _load(
    "t506a_pipeline_support",
    ROOT / "worker" / "tests" / "media" / "pipeline" / "pipeline_support.py",
)

MEDIA_ID = "0193a1b2-0007-7000-8000-00000000b506"
SEG_ID = "0193a1b2-0007-7000-8000-0000000a0001"
GP_ZARU = "0193a1b2-0007-7000-8000-0000000c0001"
GP_WAKE = "0193a1b2-0007-7000-8000-0000000c0002"
GP_DELETED_RULES = "0193a1b2-0007-7000-8000-0000000c0003"
TEXTS = [
    "社長の命令なら従わざるを得ない。",
    "約束したから、やめるわけにはいかない。",
    "雨なので、行かざるを得なかった。",
    "今日はいい天気だ。",
]


def c12(text: str) -> list[dict[str, Any]]:
    return [
        {
            "s": t.s, "e": t.e, "surface": t.surface, "lemma": t.lemma, "reading": t.reading,
            "pos": list(t.pos), "cform": t.cform, "seq": None, "class": "other", "override": False,
        }
        for t in segment(text)
    ]  # fmt: skip


def sentence_id(i: int) -> str:
    return f"0193a1b2-0007-7000-8000-0000000b{i:04d}"


class Rules:
    def __init__(self, points: list[PointRules]) -> None:
        self.points = points

    def load(self) -> list[PointRules]:
        return list(self.points)


class Spans:
    """Installed spans: detected ones are what the (fake) server installed; manual ones live beside them."""

    def __init__(self, server: Any) -> None:
        self.server = server
        self.manual: set[GrammarSpanRec] = set()

    def detected(self, media_id: str) -> set[GrammarSpanRec]:
        assert media_id == MEDIA_ID
        return set(self.server.content.grammar_spans)


class Env:
    def __init__(self, points: list[PointRules]) -> None:
        self.store, self.objects = ps.FakeStore(), ps.FakeObjects()
        self.blobs = ps.FakeBlobTable(self.objects)
        self.server = ps.FakeServer(self.store, self.objects)
        toks = [c12(t) for t in TEXTS]
        self.server.content = ContentArchive(
            MEDIA_ID,
            1,
            [
                SegmentRec(
                    SEG_ID, 0, "chapter", "一", len(TEXTS), sum(map(len, toks)), sum(map(len, toks))
                )
            ],
            [SentenceRec(sentence_id(i), SEG_ID, i, t, toks[i]) for i, t in enumerate(TEXTS)],
        )
        self.rules = Rules(points)
        self.spans = Spans(self.server)

    def run(self) -> dict[str, Any]:
        ctx = ps.Ctx()
        ctx.payload = {"media_id": MEDIA_ID}
        return dict(
            run_detect_media(
                ctx, MEDIA_ID, rules=self.rules, base_reader=self.server, spans=self.spans,
                store=self.store, objects=self.objects, blobs=self.blobs, sleep=self.server.sleep,
            )
        )  # fmt: skip

    @property
    def installed(self) -> list[tuple[str, str, str, int]]:
        """(sentence text, point, span text, rule_index) of the installed detected spans."""
        text = {s.id: s.text for s in self.server.content.sentences}
        return sorted(
            (text[g.sentence_id], g.grammar_point_id,
             _slice(text[g.sentence_id], g.start_offset, g.end_offset), g.rule_index)
            for g in self.server.content.grammar_spans
        )  # fmt: skip


def _slice(text: str, s: int, e: int) -> str:
    return text.encode("utf-16-le")[2 * s : 2 * e].decode("utf-16-le")


ZARU = PointRules(GP_ZARU, [{"rule": "[verb:未然形] ざる を 得 ない", "draft": False}])
WAKE = PointRules(
    GP_WAKE,
    [
        {"rule": "わけ に は いか", "draft": True},  # drafts are never applied
        {"rule": "(わけ | 訳) に は [lemma=行く] ない", "draft": False},
        {"rule": "(broken", "draft": False},  # invalid: skipped, the others still run
    ],
)


def test_first_run_publishes_detected_spans() -> None:
    env = Env([ZARU, WAKE])
    res = env.run()
    assert res["changed"] is True and res["content_version"] == 2
    assert res["spans"] == 3 and res["invalid_rules"] == 1
    assert env.installed == [
        (TEXTS[0], GP_ZARU, "従わざるを得ない", 0),
        (TEXTS[1], GP_WAKE, "わけにはいかない", 1),
        (TEXTS[2], GP_ZARU, "行かざるを得なかっ", 0),
    ]
    # tokens, texts and sentence ids are carried over untouched
    assert [s.id for s in env.server.content.sentences] == [sentence_id(i) for i in range(4)]
    assert [s.tokens for s in env.server.content.sentences] == [c12(t) for t in TEXTS]


def test_second_run_is_a_no_op_with_the_same_spans() -> None:
    env = Env([ZARU, WAKE])
    env.run()
    before = env.installed
    publishes = len(env.store.rows)
    res = env.run()
    assert res == {
        "media_id": MEDIA_ID, "spans": 3, "invalid_rules": 1, "changed": False, "content_version": 2,
    }  # fmt: skip
    assert env.installed == before
    assert len(env.store.rows) == publishes and env.server.version == 2


def test_rule_edit_removes_stale_detected_spans() -> None:
    env = Env([ZARU, WAKE])
    env.run()
    env.rules.points = [
        PointRules(GP_ZARU, [{"rule": "従わ ざる を 得 ない", "draft": False}]),
        PointRules(GP_WAKE, []),  # rules cleared
    ]
    res = env.run()
    assert res["changed"] is True and res["content_version"] == 3
    assert env.installed == [(TEXTS[0], GP_ZARU, "従わざるを得ない", 0)]


def test_deleted_point_drops_out() -> None:
    env = Env([ZARU, WAKE])
    env.run()
    env.rules.points = [WAKE]  # ZARU deleted: the rule source lists live points only
    env.run()
    assert [g for _, g, _, _ in env.installed] == [GP_WAKE]


def test_manual_spans_are_never_read_published_or_touched() -> None:
    env = Env([ZARU])
    manual = GrammarSpanRec(sentence_id(3), GP_ZARU, 0, 2, 0)
    same_as_detected = GrammarSpanRec(sentence_id(0), GP_ZARU, 7, 15, 0)
    env.spans.manual = {manual, same_as_detected}
    env.run()
    env.rules.points = []
    env.run()
    assert env.installed == []
    assert env.spans.manual == {manual, same_as_detected}
    # a manual span covering a detected span does not make the detected one count as installed
    env2 = Env([ZARU])
    env2.spans.manual = {same_as_detected}
    assert env2.run()["changed"] is True


def test_media_that_cannot_carry_spans_is_skipped() -> None:
    env = Env([ZARU])
    env.server.live = False
    assert env.run() == {"media_id": MEDIA_ID, "changed": False, "skipped": "media_deleted"}
    env.server.live, env.server.source = True, "jpdb"
    assert env.run()["skipped"] == "not_applicable"
    env.server.content = None
    assert env.run()["skipped"] == "unknown_media"
    assert env.store.rows == {}


def test_stale_base_replans_on_the_new_version() -> None:
    env = Env([ZARU])
    real_read = env.server.read
    bumped = {"done": False}

    def read(media_id: str) -> Any:
        base = real_read(media_id)
        if not bumped["done"] and env.store.rows:
            # someone else published v2 while our first publish was queued
            bumped["done"] = True
            assert env.server.content is not None
            env.server.content = ContentArchive(
                MEDIA_ID, 2, env.server.content.segments, env.server.content.sentences
            )
        return base

    env.server.read = read
    orig_sleep = env.server.sleep

    def sleep(t: float) -> None:
        read(MEDIA_ID)
        orig_sleep(t)

    env.server.sleep = sleep
    ctx = ps.Ctx()
    res = run_detect_media(
        ctx, MEDIA_ID, rules=env.rules, base_reader=env.server, spans=env.spans,
        store=env.store, objects=env.objects, blobs=env.blobs, sleep=sleep,
    )  # fmt: skip
    assert res["changed"] is True and res["content_version"] == 3
    assert len(env.installed) == 2


def test_detect_spans_dedupes_by_key_keeping_the_lowest_rule_index() -> None:
    pts, problems = compile_points(
        [PointRules(GP_ZARU, [{"rule": "ざる を 得 ない"}, {"rule": "ざる を 得 ない"}, "junk"])]
    )
    assert [(p.rule_index, p.message) for p in problems] == [(2, "not a {rule, draft} object")]
    got = detect_spans(pts, [DetectSentence(sentence_id(0), TEXTS[0], c12(TEXTS[0]))])
    assert [(g.start_offset, g.end_offset, g.rule_index) for g in got] == [(9, 15, 0)]


class Queue:
    def __init__(self, ids: list[str]) -> None:
        self.ids = ids
        self.keys: set[str] = set()

    def media_ids(self) -> list[str]:
        return list(self.ids)

    def enqueue(self, media_id: str, digest: str) -> bool:
        key = dedupe_key(media_id, digest)
        if key in self.keys:
            return False
        self.keys.add(key)
        return True


def test_fan_out_enqueues_one_job_per_media_per_rule_set() -> None:
    ids = [str(uuid.UUID(int=i + 1)) for i in range(3)]
    q, rules = Queue(ids), Rules([ZARU])
    ctx = ps.Ctx()
    first = run_fan_out(ctx, rules=rules, queue=q)
    assert (first["media"], first["enqueued"]) == (3, 3)
    assert run_fan_out(ctx, rules=rules, queue=q)["enqueued"] == 0  # same rules: collapses
    rules.points = [ZARU, WAKE]
    assert run_fan_out(ctx, rules=rules, queue=q)["enqueued"] == 3  # edited rules: new runs
    assert rules_digest([ZARU, WAKE]) == rules_digest([WAKE, ZARU])


@pytest.mark.parametrize("bad", [None, 5, "x", {}])
def test_non_list_detection_rules_are_ignored(bad: Any) -> None:
    pts, _ = compile_points([PointRules(GP_DELETED_RULES, bad)])
    assert pts == []
