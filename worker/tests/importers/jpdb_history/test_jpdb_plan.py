"""T207a: jpdb history export -> ImportPlan (contracts/api/openapi.yaml importJpdbHistory, C-4)."""

from __future__ import annotations

import hashlib
import json
import random
import uuid
from collections import Counter
from pathlib import Path
from typing import Any

import pytest

from kotobaya_worker.dict.jmdict import parse_jmdict
from kotobaya_worker.importers.jpdb_history import (
    ExistingState,
    ImportOptions,
    ImportPlan,
    InMemoryRefLookup,
    job_result,
    plan_import,
    review_id,
)

ROOT = Path(__file__).resolve().parents[4]
SAMPLE = ROOT / "fixtures" / "jpdb" / "reviews-sample.json"
JMDICT = ROOT / "fixtures" / "dict" / "JMdict_subset.xml"

# JMdict subset seqs for the sample's words (checked against fixtures/dict/JMdict_subset.xml).
GAKKOU, NEKO, MANIAU, SHIZUKA, SUSHI = 1206730, 1467640, 1215260, 1381820, 1595650

ROW_BUCKETS = {
    "vocabulary_carded",
    "vocabulary_status_only",
    "vocabulary_en_jp_ignored",
    "unmapped",
    "kanji_carded",
    "kanji_unmapped",
}


@pytest.fixture(scope="module")
def subset() -> InMemoryRefLookup:
    return InMemoryRefLookup.from_entries(parse_jmdict(JMDICT))


@pytest.fixture(scope="module")
def sample() -> dict[str, Any]:
    data: dict[str, Any] = json.loads(SAMPLE.read_text(encoding="utf-8"))
    return data


def small_ref() -> InMemoryRefLookup:
    ref = InMemoryRefLookup()
    ref.add(1206730, kanji=["学校"], readings=["がっこう"])
    ref.add(1467640, kanji=["猫"], readings=["ねこ"], usually_kana=True)
    ref.add(1381820, kanji=["静か"], readings=["しずか"])
    ref.add(1000001, kanji=[], readings=["すごい"])
    return ref


def ev(ts: Any, grade: Any, **extra: Any) -> dict[str, Any]:
    return {"timestamp": ts, "grade": grade, "from_anki": False, **extra}


def vrow(vid: Any, spelling: str, reading: str, *events: Any) -> dict[str, Any]:
    return {"vid": vid, "spelling": spelling, "reading": reading, "reviews": list(events)}


def krow(ch: Any, *events: Any, **extra: Any) -> dict[str, Any]:
    return {"character": ch, "reviews": list(events), **extra}


def assert_sums(r: dict[str, Any]) -> None:
    assert r["vocabulary_total"] == (
        r["vocabulary_carded"]
        + r["vocabulary_status_only"]
        + r["vocabulary_en_jp_ignored"]
        + len(r["unmapped"])
    )
    assert r["kanji_total"] == r["kanji_carded"] + len(r["kanji_unmapped"])
    assert r["reviews_total"] == (
        r["reviews_imported"]
        + r["reviews_already_imported"]
        + r["reviews_skipped"]
        + r["reviews_unknown_grade"]
        + r["status_events_unknown"]
        + r["status_events_known"]
        + r["reviews_duplicate_dropped"]
    )


def assert_ledger_complete(export: dict[str, Any], plan: ImportPlan) -> None:
    """Every input row and every element of its reviews[] lands in exactly one bucket."""
    rows: Counter[tuple[str, int]] = Counter()
    events: Counter[tuple[str, int, int]] = Counter()
    for e in plan.ledger:
        if e.event_index is None:
            assert e.bucket in ROW_BUCKETS
            rows[(e.array, e.row_index)] += 1
        else:
            assert e.bucket not in ROW_BUCKETS
            events[(e.array, e.row_index, e.event_index)] += 1
    want_rows: set[tuple[str, int]] = set()
    want_events: set[tuple[str, int, int]] = set()
    for array in (
        "cards_vocabulary_jp_en",
        "cards_vocabulary_en_jp",
        "cards_kanji_char_keyword",
        "cards_kanji_keyword_char",
    ):
        arr = export.get(array)
        if not isinstance(arr, list):
            continue
        for i, row in enumerate(arr):
            want_rows.add((array, i))
            revs = row.get("reviews") if isinstance(row, dict) else None
            if isinstance(revs, list):
                for j in range(len(revs)):
                    want_events.add((array, i, j))
    assert set(rows) == want_rows and all(c == 1 for c in rows.values())
    assert set(events) == want_events and all(c == 1 for c in events.values())


# ------------------------------------------------------------------ fixture: hand-computed table


def test_sample_bucket_counts_match_hand_computed_table(
    sample: dict[str, Any], subset: InMemoryRefLookup
) -> None:
    plan = plan_import(sample, subset, ImportOptions())
    r = job_result(plan)
    expected = {
        "vocabulary_total": 7,
        "vocabulary_carded": 5,  # 学校 x2 (one group), 猫, 間に合う, 寿司
        "vocabulary_status_only": 1,  # 静か (known only)
        "vocabulary_en_jp_ignored": 0,
        "notes_created": 4,  # 学校, 猫, 間に合う, 寿司
        "notes_existing": 0,
        "cards_created": 4,
        "kanji_total": 2,
        "kanji_carded": 2,
        "kanji_notes_created": 2,  # 猫, 学
        "kanji_notes_existing": 0,
        "kanji_cards_created": 2,
        "kanji_cards_suspended": 1,  # 学 writing
        "reviews_total": 24,
        "reviews_imported": 13,  # vocab 3+4+2+1, kanji 2+1
        "reviews_already_imported": 0,
        "reviews_skipped": 3,  # 山田太郎 (unmapped)
        "reviews_unknown_grade": 0,
        "status_events_unknown": 7,  # 5 vocab rows + 2 kanji rows
        "status_events_known": 1,  # 静か
        "reviews_duplicate_dropped": 0,
        "word_statuses_set": 1,
        "kanji_unmapped": [],
    }
    for k, v in expected.items():
        assert r[k] == v, k
    assert_sums(r)
    assert_ledger_complete(sample, plan)
    assert [g.entry_seq for g in plan.vocab_groups] == [GAKKOU, NEKO, MANIAU, SHIZUKA, SUSHI]


def test_sample_result_has_every_required_member(
    sample: dict[str, Any], subset: InMemoryRefLookup
) -> None:
    r = job_result(plan_import(sample, subset, ImportOptions()))
    required = {
        "vocabulary_total", "notes_created", "notes_existing", "cards_created", "reviews_total",
        "reviews_imported", "reviews_already_imported", "reviews_skipped", "word_statuses_set",
        "unmapped", "vocabulary_carded", "vocabulary_status_only", "kanji_total", "kanji_carded",
        "kanji_notes_created", "kanji_notes_existing", "kanji_cards_created", "kanji_unmapped",
        "reviews_unknown_grade", "status_events_unknown", "status_events_known",
        "reviews_duplicate_dropped", "vocabulary_en_jp_ignored", "kanji_cards_suspended",
    }  # fmt: skip
    assert set(r) == required
    json.dumps(r)


def test_plan_is_json_serialisable_and_round_trips(
    sample: dict[str, Any], subset: InMemoryRefLookup
) -> None:
    plan = plan_import(sample, subset, ImportOptions())
    doc = plan.to_json()
    again = json.loads(json.dumps(doc, ensure_ascii=False))
    assert again == doc
    assert doc["version"] == 1
    gakkou = doc["vocab_groups"][0]
    assert gakkou["entry_seq"] == GAKKOU and gakkou["card_kind"] == "meaning"
    assert [r["vid"] for r in gakkou["rows"]] == [1199000, 1199000]


# ------------------------------------------------------------------ repeated vid merge


def test_repeated_vid_rows_merge_into_one_group_with_interleaved_events(
    sample: dict[str, Any], subset: InMemoryRefLookup
) -> None:
    plan = plan_import(sample, subset, ImportOptions())
    groups = [g for g in plan.vocab_groups if g.entry_seq == GAKKOU]
    assert len(groups) == 1
    g = groups[0]
    assert g.fate == "carded" and len(g.rows) == 2
    # okay, easy, okay from the 学校 row, pass from the がっこう row, in timestamp order
    assert [(r.reviewed_at, r.rating, r.button) for r in g.reviews] == [
        (1770186400000, 3, "okay"),
        (1770450000000, 4, "easy"),
        (1771000000000, 3, "okay"),
        (1772500000000, 3, None),
    ]


def test_repeated_vid_interleaves_and_drops_exact_duplicates() -> None:
    export = {
        "cards_vocabulary_jp_en": [
            vrow(1206730, "学校", "がっこう", ev(100, "unknown"), ev(300, "okay"), ev(500, "hard")),
            vrow(1206730, "がっこう", "がっこう", ev(100, "unknown"), ev(200, "easy"),
                 ev(300, "okay"), ev(300, "hard"), ev(600, "nothing")),
        ]
    }  # fmt: skip
    plan = plan_import(export, small_ref(), ImportOptions())
    assert len(plan.vocab_groups) == 1
    g = plan.vocab_groups[0]
    assert [(r.reviewed_at // 1000, r.grade) for r in g.reviews] == [
        (200, "easy"), (300, "okay"), (300, "hard"), (500, "hard"), (600, "nothing"),
    ]  # fmt: skip
    r = job_result(plan)
    assert r["reviews_duplicate_dropped"] == 2  # unknown@100, okay@300 of the second row
    assert r["status_events_unknown"] == 1
    assert r["reviews_imported"] == 5
    assert r["notes_created"] == 1 and r["cards_created"] == 1
    assert r["vocabulary_carded"] == 2
    assert_sums(r)
    assert_ledger_complete(export, plan)


def test_two_vids_mapping_to_one_seq_form_one_group() -> None:
    export = {
        "cards_vocabulary_jp_en": [
            vrow(1206730, "学校", "がっこう", ev(100, "okay")),
            vrow(9999999, "学校", "がっこう", ev(100, "okay"), ev(200, "easy")),
        ]
    }
    plan = plan_import(export, small_ref(), ImportOptions())
    assert len(plan.vocab_groups) == 1
    assert len(plan.vocab_groups[0].reviews) == 2  # okay@100 is an exact duplicate
    assert job_result(plan)["reviews_duplicate_dropped"] == 1


def test_review_ids_are_stable_across_runs_and_unique(
    sample: dict[str, Any], subset: InMemoryRefLookup
) -> None:
    a = plan_import(sample, subset, ImportOptions())
    b = plan_import(json.loads(json.dumps(sample)), subset, ImportOptions())
    ids_a = [r.id for g in a.vocab_groups for r in g.reviews] + [
        r.id for g in a.kanji_groups for r in g.reviews
    ]
    ids_b = [r.id for g in b.vocab_groups for r in g.reviews] + [
        r.id for g in b.kanji_groups for r in g.reviews
    ]
    assert ids_a == ids_b
    assert len(ids_a) == len(set(ids_a)) == 13
    assert a.to_json() == b.to_json()


def test_review_ids_use_occurrence_index_for_same_timestamp_events() -> None:
    export = {
        "cards_vocabulary_jp_en": [
            vrow(1206730, "学校", "がっこう", ev(100, "okay"), ev(100, "hard"), ev(100, "easy"))
        ]
    }
    g = plan_import(export, small_ref(), ImportOptions()).vocab_groups[0]
    assert [r.id for r in g.reviews] == [
        review_id("v1206730", 100, "okay", 0),
        review_id("v1206730", 100, "hard", 1),
        review_id("v1206730", 100, "easy", 2),
    ]
    assert len({r.id for r in g.reviews}) == 3


# ------------------------------------------------------------------ review id (golden)


def test_review_id_golden_vector() -> None:
    assert review_id("v1206730", 1770186400, "okay", 0) == GOLDEN_ID


GOLDEN_ID = "019c2754-e100-7a5a-8054-4d25f10e66e1"


def test_review_id_matches_contract_byte_layout() -> None:
    """Independent re-derivation of the openapi `importJpdbHistory` "Review ids" rule."""
    for key, ts, grade, occ in [
        ("v1206730", 1770186400, "okay", 0),
        ("v0", 0, "fail", 3),
        ("k:writing:学", 1770300000, "hard", 1),
    ]:
        h = hashlib.sha256(f"jpdb-review/v1|{key}|{ts}|{grade}|{occ}".encode()).digest()
        b = bytearray((ts * 1000).to_bytes(6, "big"))
        b += bytes([0x70 | (h[0] & 0x0F), h[1], 0x80 | (h[2] & 0x3F)]) + h[3:10]
        want = str(uuid.UUID(bytes=bytes(b)))
        got = review_id(key, ts, grade, occ)
        assert got == want
        u = uuid.UUID(got)
        assert u.version == 7 and u.variant == uuid.RFC_4122


def test_kanji_review_ids_use_kind_and_character_key(
    sample: dict[str, Any], subset: InMemoryRefLookup
) -> None:
    plan = plan_import(sample, subset, ImportOptions())
    gaku = next(g for g in plan.kanji_groups if g.character == "学")
    assert [r.id for r in gaku.reviews] == [review_id("k:writing:学", 1770300000, "hard", 0)]


# ------------------------------------------------------------------ ADR-009 cases


def test_known_only_vocab_gets_status_only(
    sample: dict[str, Any], subset: InMemoryRefLookup
) -> None:
    plan = plan_import(sample, subset, ImportOptions())
    g = next(g for g in plan.vocab_groups if g.entry_seq == SHIZUKA)
    assert g.fate == "status_only" and g.reviews == ()
    assert plan.word_status_seqs() == [SHIZUKA]
    r = job_result(plan, ExistingState(word_override_seqs=frozenset({SHIZUKA})))
    assert r["word_statuses_set"] == 0 and r["vocabulary_status_only"] == 1
    off = job_result(plan_import(sample, subset, ImportOptions(import_word_status=False)))
    assert off["word_statuses_set"] == 0 and off["vocabulary_status_only"] == 1


def test_known_then_reviews_is_carded_without_status_override() -> None:
    export = {
        "cards_vocabulary_jp_en": [
            vrow(1381820, "静か", "しずか", ev(100, "known"), ev(200, "okay"), ev(300, "hard"))
        ]
    }
    plan = plan_import(export, small_ref(), ImportOptions())
    g = plan.vocab_groups[0]
    assert g.fate == "carded" and len(g.reviews) == 2
    assert plan.word_status_seqs() == []
    r = job_result(plan)
    assert r["word_statuses_set"] == 0 and r["status_events_known"] == 1
    assert r["vocabulary_carded"] == 1 and r["reviews_imported"] == 2


def test_repeated_known_only_rows_are_one_status_only_group() -> None:
    export = {
        "cards_vocabulary_jp_en": [
            vrow(1381820, "静か", "しずか", ev(100, "known")),
            vrow(1381820, "しずか", "しずか", ev(100, "known")),
        ]
    }
    plan = plan_import(export, small_ref(), ImportOptions())
    r = job_result(plan)
    assert r["vocabulary_status_only"] == 2 and r["word_statuses_set"] == 1
    assert r["status_events_known"] == 1 and r["reviews_duplicate_dropped"] == 1
    assert_sums(r)


def test_known_only_kanji_gets_a_suspended_card() -> None:
    export = {"cards_kanji_char_keyword": [krow("猫", ev(100, "known"))]}
    plan = plan_import(export, small_ref(), ImportOptions())
    (g,) = plan.kanji_groups
    assert g.card_kind == "keyword" and g.known_only and g.suspended_on_create
    assert g.reviews == ()
    r = job_result(plan)
    assert r["kanji_carded"] == 1 and r["kanji_cards_created"] == 1
    assert r["kanji_cards_suspended"] == 1 and r["status_events_known"] == 1


def test_writing_cards_are_suspended_keyword_cards_are_not(
    sample: dict[str, Any], subset: InMemoryRefLookup
) -> None:
    plan = plan_import(sample, subset, ImportOptions())
    by_char = {(g.character, g.card_kind): g for g in plan.kanji_groups}
    assert by_char[("学", "writing")].suspended_on_create
    assert not by_char[("猫", "keyword")].suspended_on_create


def test_both_kanji_arrays_share_one_note_and_keyword_comes_from_export() -> None:
    export = {
        "cards_kanji_char_keyword": [krow("猫", ev(100, "okay"))],
        "cards_kanji_keyword_char": [
            krow("猫", ev(200, "hard"), keyword="  cat  "),
            krow("犬", ev(300, "okay")),
        ],
    }
    plan = plan_import(export, small_ref(), ImportOptions())
    assert [(n.character, n.fields()) for n in plan.kanji_notes] == [
        ("猫", {"literal": "猫", "keyword": "cat"}),
        ("犬", {"literal": "犬"}),
    ]
    r = job_result(plan, ExistingState(kanji_cards=frozenset({("猫", "keyword")})))
    assert r["kanji_notes_created"] == 2 and r["kanji_cards_created"] == 2
    assert r["kanji_cards_suspended"] == 2  # both created cards are writing cards


def test_no_vocab_production_or_reading_cards() -> None:
    export = {
        "cards_vocabulary_jp_en": [vrow(1206730, "学校", "がっこう", ev(100, "okay"))],
        "cards_vocabulary_en_jp": [
            vrow(1206730, "学校", "がっこう", ev(100, "okay"), ev(200, "hard")),
            vrow(42, "x", "x", ev(1, "okay")),
        ],
    }
    plan = plan_import(export, small_ref(), ImportOptions())
    assert {g.card_kind for g in plan.vocab_groups} == {"meaning"}
    assert len(plan.vocab_groups) == 1 and len(plan.vocab_groups[0].reviews) == 1
    r = job_result(plan)
    assert r["vocabulary_en_jp_ignored"] == 2 and r["unmapped"] == []
    assert r["reviews_skipped"] == 3 and r["reviews_imported"] == 1
    assert r["cards_created"] == 1
    assert_sums(r)
    assert_ledger_complete(export, plan)


# ------------------------------------------------------------------ unmapped


def test_unmapped_vids_are_listed_with_spelling_reading_review_count(
    sample: dict[str, Any], subset: InMemoryRefLookup
) -> None:
    r = job_result(plan_import(sample, subset, ImportOptions()))
    assert r["unmapped"] == [
        {
            "jpdb_vid": 2147656918,
            "spelling": "山田太郎",
            "reading": "やまだたろう",
            "review_count": 3,
        }
    ]


def test_unmapped_lists_each_row_and_missing_vid_as_zero() -> None:
    export = {
        "cards_vocabulary_jp_en": [
            vrow(2147656918, "山田太郎", "やまだたろう", ev(1, "okay")),
            vrow(2147656918, "やまだ", "やまだ"),
            {"spelling": "謎", "reviews": [ev(1, "okay"), "junk"]},
        ],
        "cards_kanji_char_keyword": [krow("猫猫", ev(1, "okay")), krow(None)],
    }
    plan = plan_import(export, small_ref(), ImportOptions())
    r = job_result(plan)
    assert r["unmapped"] == [
        {
            "jpdb_vid": 2147656918,
            "spelling": "山田太郎",
            "reading": "やまだたろう",
            "review_count": 1,
        },
        {"jpdb_vid": 2147656918, "spelling": "やまだ", "reading": "やまだ", "review_count": 0},
        {"jpdb_vid": 0, "spelling": "謎", "reading": "", "review_count": 2},
    ]
    assert r["kanji_unmapped"] == [
        {"character": "猫猫", "card_kind": "keyword", "review_count": 1},
        {"character": "", "card_kind": "keyword", "review_count": 0},
    ]
    assert r["reviews_skipped"] == 4
    assert_sums(r)
    assert_ledger_complete(export, plan)


# ------------------------------------------------------------------ mapping (S02 order)


def test_vid_wins_when_text_agrees_else_text_fallback() -> None:
    ref = InMemoryRefLookup()
    ref.add(10, kanji=["上手"], readings=["じょうず"])
    ref.add(11, kanji=["上手"], readings=["うわて"])
    ref.add(20, kanji=[], readings=["すごい"], usually_kana=False)
    ref.add(21, kanji=["凄い"], readings=["すごい"], usually_kana=True)
    export = {
        "cards_vocabulary_jp_en": [
            vrow(11, "上手", "うわて", ev(1, "okay")),  # vid confirmed by text
            vrow(20, "上手", "うわて", ev(2, "okay")),  # vid disagrees with text -> pair -> 11
            vrow(99, "スゴイ", "すごい", ev(1, "okay")),  # kana-only -> prefer uk -> 21
            vrow(99, "上手", "かみて", ev(1, "okay")),  # spelling only -> smallest of {10, 11}
        ]
    }
    plan = plan_import(export, ref, ImportOptions())
    methods = [(row.vid, row.method) for g in plan.vocab_groups for row in g.rows]
    assert [g.entry_seq for g in plan.vocab_groups] == [11, 21, 10]
    assert len(plan.vocab_groups[0].rows) == 2
    assert sorted(m for _, m in methods) == [
        "kana-reading", "spelling+reading", "spelling-only", "vid=seq (confirmed by text)",
    ]  # fmt: skip


# ------------------------------------------------------------------ drift tolerance


def test_tolerates_missing_arrays_unknown_members_and_from_anki() -> None:
    export = {
        "meta": {"v": 2},
        "cards_vocabulary_jp_en": [
            {
                "vid": 1206730,
                "spelling": "学校",
                "reading": "がっこう",
                "extra": 1,
                "reviews": [
                    {"timestamp": 100, "grade": "okay", "from_anki": True, "x": 1},
                    {"timestamp": 200, "grade": "easy"},
                    {"timestamp": 300, "grade": "okay", "from_anki": "maybe"},
                ],
            }
        ],
        "cards_kanji_char_keyword": "not an array",
    }
    plan = plan_import(export, small_ref(), ImportOptions())
    r = job_result(plan)
    assert r["reviews_imported"] == 3 and r["kanji_total"] == 0
    assert_sums(r)


def test_empty_export() -> None:
    plan = plan_import({}, small_ref(), ImportOptions())
    r = job_result(plan)
    assert r["vocabulary_total"] == 0 and r["reviews_total"] == 0 and r["unmapped"] == []
    assert_sums(r)


def test_malformed_and_unknown_grade_events_are_counted() -> None:
    export = {
        "cards_vocabulary_jp_en": [
            vrow(
                1206730, "学校", "がっこう",
                ev(100, "okay"),
                ev(100, "OKAY"),  # case-sensitive: unknown grade (well-formed)
                ev(True, "okay"),  # bool is not an integer timestamp
                ev(1.5, "okay"),
                ev(200, 3),
                {"grade": "okay"},
                "string",
                ev(-1, "okay"),  # outside the instant_ms range
                ev(100, "mystery"),
            ),
            {"vid": 1467640, "spelling": "猫", "reading": "ねこ", "reviews": "nope"},
        ]
    }  # fmt: skip
    plan = plan_import(export, small_ref(), ImportOptions())
    r = job_result(plan)
    assert r["reviews_total"] == 9
    assert r["reviews_unknown_grade"] == 8 and r["reviews_imported"] == 1
    # a row whose only events are malformed (or none at all) is carded
    assert r["vocabulary_carded"] == 2 and r["notes_created"] == 2
    assert_sums(r)
    assert_ledger_complete(export, plan)


def test_import_reviews_false_writes_only_statuses(
    sample: dict[str, Any], subset: InMemoryRefLookup
) -> None:
    r = job_result(plan_import(sample, subset, ImportOptions(import_reviews=False)))
    assert r["reviews_imported"] == 0 and r["reviews_skipped"] == 3 + 13
    assert r["notes_created"] == r["cards_created"] == r["kanji_cards_created"] == 0
    assert r["kanji_notes_created"] == r["kanji_cards_suspended"] == 0
    assert r["vocabulary_carded"] == 5 and r["word_statuses_set"] == 1
    assert_sums(r)


def test_rerun_with_existing_state_imports_nothing_twice(
    sample: dict[str, Any], subset: InMemoryRefLookup
) -> None:
    plan = plan_import(sample, subset, ImportOptions())
    first = job_result(plan)
    existing = ExistingState(
        vocab_note_seqs=frozenset({GAKKOU, NEKO, MANIAU, SUSHI}),
        meaning_card_seqs=frozenset({GAKKOU, NEKO, MANIAU, SUSHI}),
        kanji_note_chars=frozenset({"猫", "学"}),
        kanji_cards=frozenset({("猫", "keyword"), ("学", "writing")}),
        review_ids=frozenset(plan.review_ids()),
        word_override_seqs=frozenset({SHIZUKA}),
    )
    second = job_result(plan, existing)
    assert second["reviews_already_imported"] == first["reviews_imported"] == 13
    assert second["reviews_imported"] == 0
    assert second["notes_created"] == 0 and second["notes_existing"] == 4
    assert second["cards_created"] == second["kanji_cards_created"] == 0
    assert second["kanji_notes_existing"] == 2 and second["word_statuses_set"] == 0
    assert_sums(second)


def test_reviewed_at_is_utc_milliseconds_independent_of_time_zone() -> None:
    export = {"cards_vocabulary_jp_en": [vrow(1206730, "学校", "がっこう", ev(1770186400, "okay"))]}
    (rev,) = plan_import(export, small_ref(), ImportOptions()).vocab_groups[0].reviews
    assert rev.reviewed_at == 1770186400000
    assert rev.to_json()["utc_offset_min"] is None
    assert rev.to_json()["review_type"] == "scheduled"
    assert rev.to_json()["import_source"] == "jpdb"


# ------------------------------------------------------------------ property: nothing dropped

GRADES = ["nothing", "something", "hard", "okay", "easy", "pass", "fail", "known", "unknown", "?"]


def _random_event(rnd: random.Random) -> Any:
    roll = rnd.random()
    if roll < 0.05:
        return rnd.choice(["junk", None, {"grade": "okay"}, ev("1", "okay"), ev(5, None)])
    return ev(rnd.randint(1, 6) * 100, rnd.choice(GRADES), from_anki=rnd.random() < 0.1)


def _random_export(rnd: random.Random) -> dict[str, Any]:
    words = [(1206730, "学校", "がっこう"), (1206730, "がっこう", "がっこう"),
             (1467640, "猫", "ねこ"), (1381820, "静か", "しずか"), (1000001, "すごい", "すごい"),
             (2147656918, "山田太郎", "やまだたろう"), (5, "謎", "なぞ")]  # fmt: skip
    out: dict[str, Any] = {}
    for array in ("cards_vocabulary_jp_en", "cards_vocabulary_en_jp"):
        if rnd.random() < 0.9:
            out[array] = [
                vrow(*rnd.choice(words), *[_random_event(rnd) for _ in range(rnd.randint(0, 6))])
                for _ in range(rnd.randint(0, 8))
            ]
    for array in ("cards_kanji_char_keyword", "cards_kanji_keyword_char"):
        if rnd.random() < 0.9:
            out[array] = [
                krow(
                    rnd.choice(["猫", "学", "校", "猫猫", ""]),
                    *[_random_event(rnd) for _ in range(rnd.randint(0, 5))],
                )
                for _ in range(rnd.randint(0, 5))
            ]
    if rnd.random() < 0.1:
        out["cards_vocabulary_jp_en"] = [None, 7, {"reviews": [None]}]
    return out


@pytest.mark.parametrize("seed", range(200))
def test_property_every_row_and_event_lands_in_exactly_one_bucket(seed: int) -> None:
    rnd = random.Random(seed)
    export = _random_export(rnd)
    options = ImportOptions(
        import_reviews=rnd.random() < 0.8, import_word_status=rnd.random() < 0.8
    )
    plan = plan_import(export, small_ref(), options)
    assert_ledger_complete(export, plan)
    ids = plan.review_ids()
    existing = ExistingState(review_ids=frozenset(rnd.sample(ids, len(ids) // 2)))
    r = job_result(plan, existing)
    assert_sums(r)
    if options.import_reviews:
        carded = {g.entry_seq for g in plan.vocab_groups if g.fate == "carded"}
        assert r["notes_created"] + r["notes_existing"] == len(carded)
        assert r["cards_created"] <= r["notes_created"] + r["notes_existing"]
        chars = {g.character for g in plan.kanji_groups}
        assert r["kanji_notes_created"] + r["kanji_notes_existing"] == len(chars)
        assert r["kanji_cards_suspended"] <= r["kanji_cards_created"]
    status_only = {g.entry_seq for g in plan.vocab_groups if g.fate == "status_only"}
    assert r["word_statuses_set"] <= len(status_only)
    assert len(ids) == len(set(ids))
