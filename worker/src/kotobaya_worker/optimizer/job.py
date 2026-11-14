"""Runner handler for ``fsrs-optimize`` (contracts/db/FSRS_PUBLISH.md 1; the parent job).

Reads the preset and its reviews in one snapshot, trains on the training cards, gates on held-out log loss
(strictly lower than the current weights, within the C-1 bounds) and, only then, inserts an
``fsrs-params-publish`` child for the server's runner and waits for it. The worker never writes
``app.fsrs_params`` (CLAUDE.md rule 8).

Review selection: reviews of cards whose ``preset_id`` is the job's, ``review_type`` in (scheduled, learning),
not undone, with a rating, ordered ``(reviewed_at, hlc, id)``. Minimum and split: see ``model.py`` and ``Config``.
"""

from __future__ import annotations

import logging
import os
import time
import uuid
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any, Protocol

import psycopg
from psycopg.rows import dict_row

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.importers.jpdb_history.apply import ChildRow
from kotobaya_worker.logs import job_summary
from kotobaya_worker.media.publish.handoff import adopt_or_create_child
from kotobaya_worker.optimizer.model import (
    Config,
    Trainer,
    defaults,
    evaluate,
    split_by_card,
    train_pyfsrs,
    weights_in_bounds,
)
from kotobaya_worker.optimizer.store import INTERNAL_KIND, PgStore, PlanData, Store

KIND = "fsrs-optimize"
log = logging.getLogger(__name__)
PROGRESS_CAP = 0.9
POLL_INTERVAL = 1.0
QUEUED_TIMEOUT = 600.0


class Context(Protocol):
    job_id: uuid.UUID
    payload: dict[str, Any]

    def check_cancelled(self) -> None: ...

    def progress(self, fraction: float, message: str | None = None) -> None: ...


class PublishFailed(RuntimeError):
    """The child ended ``failed``; the message is its ``error`` (``<code>: <message>``)."""


@dataclass(frozen=True)
class Outcome:
    """The planning decision: a final not-published result, or a payload to hand to the server."""

    result: dict[str, Any] | None
    payload: dict[str, Any] | None


def _result(
    preset: str,
    *,
    published: bool,
    version: int,
    review_count: int,
    log_loss: float | None = None,
    rmse_bins: float | None = None,
    log_loss_before: float | None = None,
    holdout: int = 0,
    reason: str | None = None,
) -> dict[str, Any]:
    return {
        "preset_id": preset,
        "published": published,
        "weights_version": version,
        "review_count": review_count,
        "log_loss": log_loss,
        "rmse_bins": rmse_bins,
        "log_loss_before": log_loss_before,
        "holdout_review_count": holdout,
        "reason": reason,
    }


def decide(
    preset_id: str,
    parent: uuid.UUID,
    plan: PlanData,
    *,
    config: Config,
    trainer: Trainer,
    now_ms: int,
) -> Outcome:
    """FSRS_PUBLISH.md 1 steps 2-3: pure given the snapshot (``trainer`` is the only heavy part)."""
    base = plan.weights_version
    n = len(plan.reviews)

    def stop(reason: str, **kw: Any) -> Outcome:
        return Outcome(
            _result(preset_id, published=False, version=base, review_count=n, reason=reason, **kw),
            None,
        )

    if plan.deleted:
        return stop("preset_deleted")
    split = (
        split_by_card(plan.reviews, config.holdout_fraction) if n >= config.min_reviews else None
    )
    if split is None:
        return stop("too_few_reviews")
    train, holdout = split
    weights = trainer(train)
    if not weights_in_bounds(weights):
        return stop("out_of_bounds", holdout=len(holdout))
    current = plan.weights if plan.weights is not None else defaults()
    before = evaluate(current, holdout)
    after = evaluate(weights, holdout)
    if before is None or after is None:
        return stop("not_improved", holdout=len(holdout))
    stats = {
        "log_loss": after.log_loss,
        "rmse_bins": after.rmse_bins,
        "log_loss_before": before.log_loss,
        "holdout": len(holdout),
    }
    if not (after.log_loss < before.log_loss):  # also false for NaN
        return stop("not_improved", **stats)
    return Outcome(
        None,
        {
            "format_version": 1,
            "parent_job_id": str(parent),
            "preset_id": preset_id,
            "base_weights_version": base,
            "fsrs_version": "6",
            "weights": list(weights),
            "optimized_at": now_ms,
            "review_count": n,
            "holdout_review_count": len(holdout),
            "log_loss_before": before.log_loss,
            "log_loss_after": after.log_loss,
            "rmse_bins": after.rmse_bins,
        },
    )


def _wait(
    store: Store,
    ctx: Context,
    child: uuid.UUID,
    sleep: Callable[[float], None],
    clock: Callable[[], float],
) -> Mapping[str, Any]:
    idle_since = clock()
    while True:
        ctx.check_cancelled()
        row: ChildRow | None = store.get(child)
        if row is None:
            raise RuntimeError(f"{INTERNAL_KIND} job {child} disappeared")
        if row.status == "succeeded":
            if row.result is None:
                raise RuntimeError(f"{INTERNAL_KIND} job {child} succeeded without a result")
            return row.result
        if row.status == "failed":
            raise PublishFailed(row.error or f"{INTERNAL_KIND} failed")
        if row.status == "cancelled":
            ctx.check_cancelled()
            raise RuntimeError(
                f"{INTERNAL_KIND} job was cancelled while its parent is still running"
            )
        if row.status == "running":
            idle_since = clock()
        elif clock() - idle_since > QUEUED_TIMEOUT:
            raise TimeoutError(
                f"{INTERNAL_KIND} job {child} stayed queued for {QUEUED_TIMEOUT:.0f} s; "
                "is the server's publish runner running?"
            )
        sleep(POLL_INTERVAL)


def run_optimize(
    ctx: Context,
    *,
    store: Store,
    config: Config,
    trainer: Trainer = train_pyfsrs,
    now_ms: Callable[[], int] = lambda: int(datetime.now(UTC).timestamp() * 1000),
    sleep: Callable[[float], None] = time.sleep,
    clock: Callable[[], float] = time.monotonic,
) -> Mapping[str, Any]:
    preset_id = ctx.payload.get("preset_id")
    if not isinstance(preset_id, str):
        raise TypeError("fsrs-optimize payload needs a string `preset_id`")
    done = store.succeeded_result(ctx.job_id)  # step 1: an earlier attempt got that far
    if done is not None:
        log_optimize_summary(preset_id, None, done)
        return done
    ctx.progress(0.05, "loading reviews")
    plan = store.load_plan(preset_id)
    if plan is None:
        raise LookupError(f"unknown_preset: no preset {preset_id}")
    ctx.progress(0.2, "training")
    outcome = decide(preset_id, ctx.job_id, plan, config=config, trainer=trainer, now_ms=now_ms())
    if outcome.result is not None:
        log_optimize_summary(preset_id, len(plan.reviews), outcome.result)
        return outcome.result
    assert outcome.payload is not None
    ctx.progress(PROGRESS_CAP, "publishing")
    found = adopt_or_create_child(store, ctx.job_id, outcome.payload)
    if isinstance(found, uuid.UUID):
        found = _wait(store, ctx, found, sleep, clock)
    log_optimize_summary(preset_id, len(plan.reviews), found)
    return found


def log_optimize_summary(preset_id: str, reviews: int | None, result: Mapping[str, Any]) -> int:
    """Reviews in, reviews trained/evaluated out. Not publishing (too few, not improved) is normal
    and stays INFO; weights outside the C-1 bounds WARN."""
    count = result.get("review_count")
    n = reviews if reviews is not None else (count if isinstance(count, int) else 0)
    reason = result.get("reason")
    return job_summary(
        log,
        KIND,
        inputs={"reviews": n},
        # Every selected review is considered; declining to publish is a result, not lost data.
        outputs={"reviews_considered": n, "published": 1 if result.get("published") else 0},
        dropped={"out_of_bounds": 1 if reason == "out_of_bounds" else 0},
        preset_id=preset_id,
        reason=reason,
        log_loss_before=result.get("log_loss_before"),
        log_loss=result.get("log_loss"),
        holdout=result.get("holdout_review_count"),
    )


def make_fsrs_optimize(database_url: str) -> Callable[[Any], Mapping[str, Any]]:
    config = Config.from_env(os.environ)

    def handler(ctx: Any) -> Mapping[str, Any]:
        with psycopg.connect(to_conninfo(database_url), autocommit=True, row_factory=dict_row) as c:
            return run_optimize(ctx, store=PgStore(c), config=config)

    return handler


__all__ = ["KIND", "make_fsrs_optimize", "run_optimize"]
