"""T207a: runner adapter for jpdb-history-import (no DB: in-memory dictionary)."""

from __future__ import annotations

import json
from collections.abc import Mapping
from typing import Any

import pytest

from kotobaya_worker.importers.jpdb_history import ImportPlan, InMemoryRefLookup, job_result
from kotobaya_worker.importers.jpdb_history.handler import Context, run
from kotobaya_worker.runner import API_JOB_KINDS


class FakeCtx:
    def __init__(self, payload: dict[str, Any]) -> None:
        self.payload = payload
        self.progressed: list[float] = []

    def progress(self, fraction: float, message: str | None = None) -> None:
        self.progressed.append(fraction)

    def check_cancelled(self) -> None:
        pass


EXPORT = {
    "cards_vocabulary_jp_en": [
        {
            "vid": 1,
            "spelling": "猫",
            "reading": "ねこ",
            "reviews": [{"timestamp": 5, "grade": "okay"}],
        }
    ]
}


def ref() -> InMemoryRefLookup:
    r = InMemoryRefLookup()
    r.add(1, kanji=["猫"], readings=["ねこ"])
    return r


def test_handler_plans_with_payload_options_and_hands_plan_over() -> None:
    seen: list[ImportPlan] = []

    def apply(plan: ImportPlan, ctx: Context) -> Mapping[str, Any]:
        seen.append(plan)
        return job_result(plan)

    ctx = FakeCtx({"blob_hash": "ab", "import_reviews": False})
    out = run(ctx, lambda h: json.dumps(EXPORT).encode(), ref(), apply)
    assert seen[0].options.import_reviews is False and seen[0].options.import_word_status
    assert out["reviews_skipped"] == 1 and out["vocabulary_carded"] == 1
    assert ctx.progressed and "jpdb-history-import" in API_JOB_KINDS


def test_handler_rejects_missing_blob_hash() -> None:
    with pytest.raises(ValueError):
        run(FakeCtx({}), lambda h: b"{}", ref(), lambda p, c: {})
