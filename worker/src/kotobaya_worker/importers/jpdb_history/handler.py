"""Runner adapter for ``jpdb-history-import`` jobs.

The worker reads the export and plans the import; applying the plan (ops, replay, final
``JobResultJpdbHistory``) belongs to the server side (T207b), injected as ``apply_plan``. Blob
storage access is injected as ``fetch_blob`` (blob hash -> export bytes).

Registry wiring (``runner/__main__.py``): ``fetch_blob`` and ``apply_plan`` come from ``apply``.
"""

from __future__ import annotations

import json
import logging
import uuid
from collections.abc import Callable, Mapping
from typing import Any, Protocol

import psycopg

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.importers.jpdb_history.plan import ImportOptions, ImportPlan, plan_import
from kotobaya_worker.importers.jpdb_history.ref import PostgresRefLookup, RefLookup
from kotobaya_worker.logs import job_summary

KIND = "jpdb-history-import"
log = logging.getLogger(__name__)


class Context(Protocol):
    """The part of ``runner.JobContext`` the handler uses (progress writes are status-guarded)."""

    payload: dict[str, Any]
    job_id: uuid.UUID

    def progress(self, fraction: float, message: str | None = None) -> None: ...

    def check_cancelled(self) -> None: ...


FetchBlob = Callable[[str], bytes]
ApplyPlan = Callable[[ImportPlan, Context], Mapping[str, Any]]


def run(
    ctx: Context, fetch_blob: FetchBlob, ref: RefLookup, apply_plan: ApplyPlan
) -> Mapping[str, Any]:
    """Parse the job payload (``JpdbHistoryImportRequest``), plan, hand the plan to ``apply_plan``."""
    blob_hash = ctx.payload.get("blob_hash")
    if not isinstance(blob_hash, str) or not blob_hash:
        raise ValueError("jpdb-history-import payload needs blob_hash")
    options = ImportOptions.from_payload(ctx.payload)
    ctx.progress(0.0, "reading export")
    export = json.loads(fetch_blob(blob_hash))
    if not isinstance(export, dict):
        raise TypeError("jpdb export is not a JSON object")
    ctx.progress(0.05, "mapping")
    plan = plan_import(export, ref, options)
    log_plan(plan)
    ctx.check_cancelled()
    ctx.progress(0.2, "planned")
    result = apply_plan(plan, ctx)
    log_result(plan, result)
    return result


def log_plan(plan: ImportPlan) -> int:
    """Mapping summary: export rows in, mapped groups out, unmapped / ignored / bad events dropped."""
    c = plan.counts
    return job_summary(
        log,
        "jpdb-history-import plan",
        inputs={
            "vocabulary": c.vocabulary_total,
            "kanji": c.kanji_total,
            "review_events": c.reviews_total,
        },
        outputs={
            "vocabulary_carded": c.vocabulary_carded,
            "vocabulary_status_only": c.vocabulary_status_only,
            "kanji_carded": c.kanji_carded,
            "reviews_rating": c.reviews_rating,
        },
        dropped={
            "vocabulary_unmapped": len(plan.unmapped),
            "kanji_unmapped": len(plan.kanji_unmapped),
            "vocabulary_en_jp_ignored": c.vocabulary_en_jp_ignored,
            "reviews_unknown_grade": c.reviews_unknown_grade,
            "reviews_duplicate_dropped": c.reviews_duplicate_dropped,
        },
    )


def _n(result: Mapping[str, Any], key: str) -> int:
    v = result.get(key)
    if isinstance(v, list):
        return len(v)
    return v if isinstance(v, int) and not isinstance(v, bool) else 0


def log_result(plan: ImportPlan, result: Mapping[str, Any]) -> int:
    """Final summary from ``JobResultJpdbHistory``.

    Output is the cards the import leaves in place (created, or already there for a re-import) and,
    when reviews are not imported, the word statuses it maps. Rows in and none of that out is the
    0-card import (0.1.9) and logs ERROR.
    """
    rows_in = _n(result, "vocabulary_total") + _n(result, "kanji_total")
    if plan.options.import_reviews:
        outputs = {
            "cards_created": _n(result, "cards_created"),
            "kanji_cards_created": _n(result, "kanji_cards_created"),
            "notes_existing": _n(result, "notes_existing"),
            "kanji_notes_existing": _n(result, "kanji_notes_existing"),
        }
    else:
        c = plan.counts
        outputs = {"vocabulary_mapped": c.vocabulary_carded + c.vocabulary_status_only}
    outputs["word_statuses_set"] = _n(result, "word_statuses_set")
    return job_summary(
        log,
        KIND,
        inputs={"rows": rows_in, "review_events": _n(result, "reviews_total")},
        outputs=outputs,
        dropped={
            "unmapped": _n(result, "unmapped"),
            "kanji_unmapped": _n(result, "kanji_unmapped"),
            "reviews_skipped": _n(result, "reviews_skipped"),
            "reviews_unknown_grade": _n(result, "reviews_unknown_grade"),
            "reviews_duplicate_dropped": _n(result, "reviews_duplicate_dropped"),
        },
        require_input=True,
        reviews_imported=_n(result, "reviews_imported"),
        reviews_already_imported=_n(result, "reviews_already_imported"),
    )


def make_jpdb_history_import(
    database_url: str, fetch_blob: FetchBlob, apply_plan: ApplyPlan
) -> Callable[[Context], Mapping[str, Any]]:
    """Build the runner handler; the dictionary is read from ``ref.*`` (read-only)."""

    def handler(ctx: Context) -> Mapping[str, Any]:
        with psycopg.connect(to_conninfo(database_url)) as conn:
            conn.read_only = True
            return run(ctx, fetch_blob, PostgresRefLookup(conn), apply_plan)

    return handler
