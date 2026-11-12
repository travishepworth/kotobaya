"""T207c carried items on the plan: NUL kanji rows and review id collisions in ``job_result``."""

from __future__ import annotations

import pytest

from kotobaya_worker.importers.jpdb_history import ImportOptions, job_result, plan_import

from .test_jpdb_plan import assert_sums, ev, krow, small_ref, vrow


@pytest.mark.parametrize("import_reviews", [True, False])
def test_nul_kanji_row_is_unmapped_with_every_event_whatever_the_options(
    import_reviews: bool,
) -> None:
    export = {
        "cards_kanji_char_keyword": [
            krow("\x00", ev(100, "okay"), ev(200, "known"), ev(300, "easy"))
        ],
    }
    plan = plan_import(export, small_ref(), ImportOptions(import_reviews=import_reviews))
    assert plan.kanji_groups == () and plan.kanji_notes == ()
    (u,) = plan.kanji_unmapped
    assert (u.character, u.card_kind, u.review_count) == ("\x00", "keyword", 3)
    r = job_result(plan)
    assert r["kanji_total"] == 1 and r["kanji_carded"] == 0 and len(r["kanji_unmapped"]) == 1
    assert r["reviews_skipped"] == 3, "every event of the row counts skipped"
    assert_sums(r)


def test_job_result_counts_review_id_collisions_as_already_imported() -> None:
    # One vid text-mapped to two seqs, same timestamp and grade: the v1 id is the same for both groups.
    export = {
        "cards_vocabulary_jp_en": [
            vrow(7, "学校", "がっこう", ev(100, "okay")),
            vrow(7, "猫", "ねこ", ev(100, "okay")),
        ]
    }
    plan = plan_import(export, small_ref(), ImportOptions())
    ids = plan.review_ids()
    assert len(ids) == 2 and ids[0] == ids[1]
    r = job_result(plan)
    assert (r["reviews_imported"], r["reviews_already_imported"]) == (1, 1)
    assert r["reviews_id_collisions"] == 1
    assert_sums(r)
