"""Runner adapter for ``jpdb-history-import`` jobs.

The worker reads the export and plans the import; applying the plan (ops, replay, final
``JobResultJpdbHistory``) belongs to the server side (T207b), injected as ``apply_plan``. Blob
storage access is injected as ``fetch_blob`` (blob hash -> export bytes).

Registry wiring (``runner/__main__.py``): ``fetch_blob`` and ``apply_plan`` come from ``apply``.
"""

from __future__ import annotations

import json
import uuid
from collections.abc import Callable, Mapping
from typing import Any, Protocol

import psycopg

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.importers.jpdb_history.plan import ImportOptions, ImportPlan, plan_import
from kotobaya_worker.importers.jpdb_history.ref import PostgresRefLookup, RefLookup

KIND = "jpdb-history-import"


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
    ctx.check_cancelled()
    ctx.progress(0.2, "planned")
    return apply_plan(plan, ctx)


def make_jpdb_history_import(
    database_url: str, fetch_blob: FetchBlob, apply_plan: ApplyPlan
) -> Callable[[Context], Mapping[str, Any]]:
    """Build the runner handler; the dictionary is read from ``ref.*`` (read-only)."""

    def handler(ctx: Context) -> Mapping[str, Any]:
        with psycopg.connect(to_conninfo(database_url)) as conn:
            conn.read_only = True
            return run(ctx, fetch_blob, PostgresRefLookup(conn), apply_plan)

    return handler
