"""``app.job`` / review access for the optimizer (Postgres in production, a fake in the unit tests).

Read-only on learning data: the only write is the ``fsrs-params-publish`` child row (FSRS_PUBLISH.md 1 step 4).
"""

from __future__ import annotations

import json
import uuid
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any, Protocol

import psycopg
from psycopg.types.json import Jsonb

from kotobaya_worker.importers.jpdb_history.apply import EVENTS_CHANNEL, ChildRow
from kotobaya_worker.optimizer.model import Review, as_utc

INTERNAL_KIND = "fsrs-params-publish"


def dedupe_key(parent: uuid.UUID) -> str:
    return f"{INTERNAL_KIND}:{parent}"


@dataclass(frozen=True)
class PlanData:
    """One snapshot of the preset row and its reviews (FSRS_PUBLISH.md 1 step 2)."""

    deleted: bool
    weights_version: int
    weights: list[float] | None  # None = never optimized
    reviews: Sequence[Review]


class Store(Protocol):
    def succeeded_result(self, parent: uuid.UUID) -> Mapping[str, Any] | None: ...

    def load_plan(self, preset_id: str) -> PlanData | None:
        """None when the preset row does not exist."""
        ...

    def insert(self, child: uuid.UUID, parent: uuid.UUID, payload: dict[str, Any]) -> bool: ...

    def active_child(self, parent: uuid.UUID) -> uuid.UUID | None: ...

    def get(self, child: uuid.UUID) -> ChildRow | None: ...


_REVIEWS_SQL = (
    "select r.card_id::text as card_id, r.reviewed_at, r.rating "
    "from app.review_log r join app.card c on c.id = r.card_id "
    "where c.preset_id = %s::uuid and r.review_type in ('scheduled', 'learning') "
    "and r.undone_at is null and r.rating is not null "
    "order by r.reviewed_at, r.hlc, r.id"
)


class PgStore:
    def __init__(self, conn: psycopg.Connection[Any]) -> None:
        self._c = conn  # autocommit, dict_row

    def succeeded_result(self, parent: uuid.UUID) -> Mapping[str, Any] | None:
        row = self._c.execute(
            "select result from app.job where kind = %s and payload->>'parent_job_id' = %s "
            "and status = 'succeeded' and result is not null order by finished_at desc limit 1",
            (INTERNAL_KIND, str(parent)),
        ).fetchone()
        return None if row is None else dict(row["result"])

    def load_plan(self, preset_id: str) -> PlanData | None:
        with self._c.transaction():
            self._c.execute("set transaction isolation level repeatable read")
            p = self._c.execute(
                "select deleted_at is not null as deleted, weights_version, weights "
                "from app.fsrs_params where id = %s::uuid",
                (preset_id,),
            ).fetchone()
            if p is None:
                return None
            reviews = [
                Review(str(r["card_id"]), as_utc(r["reviewed_at"]), int(r["rating"]))
                for r in self._c.execute(_REVIEWS_SQL, (preset_id,))
            ]
        weights = None if p["weights"] is None else [float(w) for w in p["weights"]]
        return PlanData(bool(p["deleted"]), int(p["weights_version"]), weights, reviews)

    def insert(self, child: uuid.UUID, parent: uuid.UUID, payload: dict[str, Any]) -> bool:
        cur = self._c.execute(
            "insert into app.job (id, kind, payload, dedupe_key, max_attempts) "
            "values (%s, %s, %s, %s, 1) on conflict do nothing",
            (child, INTERNAL_KIND, Jsonb(payload), dedupe_key(parent)),
        )
        if cur.rowcount:
            self._c.execute(
                "select pg_notify(%s, %s)",
                (EVENTS_CHANNEL, json.dumps({"type": "job.updated", "data": {"id": str(child)}})),
            )
        return bool(cur.rowcount)

    def active_child(self, parent: uuid.UUID) -> uuid.UUID | None:
        row = self._c.execute(
            "select id from app.job where dedupe_key = %s and status in ('queued', 'running')",
            (dedupe_key(parent),),
        ).fetchone()
        return None if row is None else row["id"]

    def get(self, child: uuid.UUID) -> ChildRow | None:
        row = self._c.execute(
            "select status, result, error from app.job where id = %s", (child,)
        ).fetchone()
        return None if row is None else ChildRow(row["status"], row["result"], row["error"])
