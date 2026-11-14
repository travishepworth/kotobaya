"""fsrs-optimize: the held-out gate decides whether anything is handed to the server (FSRS_PUBLISH.md 1)."""

from __future__ import annotations

import random
import uuid
from collections.abc import Mapping, Sequence
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest

from kotobaya_worker.importers.jpdb_history.apply import ChildRow
from kotobaya_worker.optimizer.job import run_optimize
from kotobaya_worker.optimizer.model import (
    Config,
    Review,
    defaults,
    evaluate,
    split_by_card,
    weights_in_bounds,
)
from kotobaya_worker.optimizer.store import PlanData

PRESET = "00000000-0000-7000-8000-000000000010"
T0 = datetime(2025, 1, 1, tzinfo=UTC)
CONFIG = Config(min_reviews=100, holdout_fraction=0.25)


def true_weights() -> list[float]:
    w = defaults()
    w[20] = 0.5  # much flatter forgetting curve than the default 0.15
    w[4] = 3.0
    return w


def simulate(w: Sequence[float], cards: int = 120, seed: int = 7) -> list[Review]:
    """Reviews whose recall follows the scheduler built from ``w``."""
    from fsrs import Card, Rating, Scheduler

    rng = random.Random(seed)
    sch = Scheduler(parameters=list(w), enable_fuzzing=False)
    out: list[Review] = []
    for i in range(cards):
        t = T0 + timedelta(days=i, hours=1)
        card = Card(card_id=i, due=t)
        for _ in range(10):
            p = sch.get_card_retrievability(card, t)
            rating = Rating.Good if card.last_review is None or rng.random() < p else Rating.Again
            out.append(Review(f"card-{i:03d}", t, int(rating)))
            card, _ = sch.review_card(card, rating, t)
            t += timedelta(days=rng.choice([2, 4, 8, 16, 30]))
    out.sort(key=lambda r: (r.reviewed_at, r.card_id))
    return out


class FakeCtx:
    def __init__(self) -> None:
        self.job_id = uuid.uuid4()
        self.payload: dict[str, Any] = {"preset_id": PRESET}
        self.progress_log: list[float] = []

    def check_cancelled(self) -> None:
        return None

    def progress(self, fraction: float, message: str | None = None) -> None:
        self.progress_log.append(fraction)


class FakeStore:
    """A queue of one child: inserted rows succeed at once with a canned result."""

    def __init__(self, plan: PlanData | None, adopted: Mapping[str, Any] | None = None) -> None:
        self.plan = plan
        self.adopted = adopted
        self.inserted: list[dict[str, Any]] = []
        self.loaded = 0
        self._child: uuid.UUID | None = None

    def succeeded_result(self, parent: uuid.UUID) -> Mapping[str, Any] | None:
        return self.adopted

    def load_plan(self, preset_id: str) -> PlanData | None:
        self.loaded += 1
        return self.plan

    def insert(self, child: uuid.UUID, parent: uuid.UUID, payload: dict[str, Any]) -> bool:
        self.inserted.append(payload)
        self._child = child
        return True

    def active_child(self, parent: uuid.UUID) -> uuid.UUID | None:
        return self._child

    def get(self, child: uuid.UUID) -> ChildRow | None:
        p = self.inserted[0]
        result = {
            "preset_id": p["preset_id"],
            "published": True,
            "weights_version": p["base_weights_version"] + 1,
            "review_count": p["review_count"],
            "log_loss": p["log_loss_after"],
            "rmse_bins": p["rmse_bins"],
            "log_loss_before": p["log_loss_before"],
            "holdout_review_count": p["holdout_review_count"],
            "reason": None,
        }
        return ChildRow("succeeded", result, None)


def run(store: FakeStore, new_weights: list[float], config: Config = CONFIG) -> Mapping[str, Any]:
    return run_optimize(
        FakeCtx(), store=store, config=config, trainer=lambda _train: new_weights,
        sleep=lambda _s: None,
    )  # fmt: skip


def plan(reviews: Sequence[Review], **kw: Any) -> PlanData:
    return PlanData(
        **{"deleted": False, "weights_version": 0, "weights": None, "reviews": reviews, **kw}
    )


def test_split_is_disjoint_by_card_and_holdout_is_newer() -> None:
    reviews = simulate(defaults())
    split = split_by_card(reviews, 0.25)
    assert split is not None
    train, holdout = split
    assert train and holdout and len(train) + len(holdout) == len(reviews)
    assert {r.card_id for r in train}.isdisjoint({r.card_id for r in holdout})
    assert min(r.reviewed_at for r in holdout) >= max(
        min(r.reviewed_at for r in train if r.card_id == c) for c in {r.card_id for r in train}
    )
    assert split_by_card(reviews[:1], 0.25) is None


def test_worse_on_holdout_is_not_published_and_nothing_handed_off() -> None:
    reviews = simulate(true_weights())
    split = split_by_card(reviews, CONFIG.holdout_fraction)
    assert split is not None
    worse = defaults()
    worse[20] = 0.8  # steep decay: badly miscalibrated on data generated with 0.5
    worse[4] = 9.0
    assert weights_in_bounds(worse)
    # the stored weights are the truth, so the "new" weights cannot win
    store = FakeStore(plan(reviews, weights=true_weights(), weights_version=3))
    result = run(store, worse)
    assert store.inserted == []
    assert result["published"] is False
    assert result["reason"] == "not_improved"
    assert result["weights_version"] == 3
    assert result["review_count"] == len(reviews)
    assert result["log_loss"] > result["log_loss_before"]
    assert result["holdout_review_count"] == len(split[1])


def test_equal_weights_are_not_published() -> None:
    reviews = simulate(true_weights())
    store = FakeStore(plan(reviews))
    result = run(store, defaults())  # same as the never-optimized current weights
    assert store.inserted == []
    assert (result["published"], result["reason"]) == (False, "not_improved")
    assert result["log_loss"] == result["log_loss_before"]


def test_better_on_holdout_is_handed_off_with_the_gate_numbers() -> None:
    reviews = simulate(true_weights())
    store = FakeStore(plan(reviews))
    result = run(store, true_weights())
    assert len(store.inserted) == 1
    p = store.inserted[0]
    assert p["format_version"] == 1 and p["fsrs_version"] == "6"
    assert p["preset_id"] == PRESET and p["base_weights_version"] == 0
    assert p["weights"] == true_weights()
    assert p["log_loss_after"] < p["log_loss_before"]
    assert 1 <= p["holdout_review_count"] < p["review_count"] == len(reviews)
    assert p["rmse_bins"] >= 0
    split = split_by_card(reviews, CONFIG.holdout_fraction)
    assert split is not None
    held = evaluate(true_weights(), split[1])
    assert held is not None and p["log_loss_after"] == held.log_loss
    assert result["published"] is True and result["weights_version"] == 1


def test_too_few_reviews_deleted_and_out_of_bounds_hand_off_nothing() -> None:
    reviews = simulate(true_weights())
    few = FakeStore(plan(reviews[:50]))
    r = run(few, true_weights())
    assert (r["published"], r["reason"], r["log_loss"], r["holdout_review_count"]) == (
        False, "too_few_reviews", None, 0,
    )  # fmt: skip
    gone = FakeStore(plan(reviews, deleted=True, weights_version=2))
    r = run(gone, true_weights())
    assert (r["reason"], r["weights_version"]) == ("preset_deleted", 2)
    wild = true_weights()
    wild[4] = 99.0
    bad = FakeStore(plan(reviews))
    r = run(bad, wild)
    assert (r["reason"], r["log_loss"], r["rmse_bins"]) == ("out_of_bounds", None, None)
    nan = true_weights()
    nan[0] = float("nan")
    assert not weights_in_bounds(nan)
    assert few.inserted == gone.inserted == bad.inserted == []


def test_an_earlier_attempts_published_child_is_adopted_without_work() -> None:
    adopted = {"preset_id": PRESET, "published": True, "weights_version": 1}
    store = FakeStore(None, adopted=adopted)
    assert run(store, true_weights()) == adopted
    assert store.loaded == 0


def test_unknown_preset_fails_the_attempt() -> None:
    with pytest.raises(LookupError, match="unknown_preset"):
        run(FakeStore(None), true_weights())


def test_naive_db_timestamps_go_through_the_whole_job() -> None:
    """Regression (BUG-3): naive datetimes from the DB made py-fsrs raise in decide."""
    rows = [
        (r.card_id, r.reviewed_at.replace(tzinfo=None), r.rating) for r in simulate(true_weights())
    ]
    reviews = [Review(c, t, r) for c, t, r in rows]  # naive in, as a row source would give
    assert all(r.reviewed_at.tzinfo is UTC for r in reviews)
    store = FakeStore(plan(reviews))
    result = run(store, true_weights())
    assert result["published"] is True
    assert len(store.inserted) == 1


def test_pg_store_normalises_naive_and_offset_timestamps() -> None:
    from datetime import timezone

    from kotobaya_worker.optimizer.store import PgStore

    rows = [
        {"card_id": "a", "reviewed_at": datetime(2025, 1, 1, 12), "rating": 3},
        {
            "card_id": "b",
            "reviewed_at": datetime(2025, 1, 1, 14, tzinfo=timezone(timedelta(hours=2))),
            "rating": 3,
        },
    ]

    class Cur:
        def __init__(self, data: Any) -> None:
            self._d = data

        def fetchone(self) -> Any:
            return self._d

        def __iter__(self) -> Any:
            return iter(self._d)

    class Tx:
        def __enter__(self) -> None:
            return None

        def __exit__(self, *a: object) -> None:
            return None

    class Conn:
        def transaction(self) -> Tx:
            return Tx()

        def execute(self, sql: str, params: Any = None) -> Cur:
            if "fsrs_params" in sql:
                return Cur({"deleted": False, "weights_version": 0, "weights": None})
            if "review_log" in sql:
                return Cur(rows)
            return Cur(None)

    loaded = PgStore(Conn()).load_plan(PRESET)  # type: ignore[arg-type]
    assert loaded is not None
    assert [r.reviewed_at for r in loaded.reviews] == [datetime(2025, 1, 1, 12, tzinfo=UTC)] * 2


def test_real_pyfsrs_trainer_and_evaluate_accept_naive_and_non_utc_reviews() -> None:
    """LOGFIX-1 (live 'datetime must be timezone-aware and set to UTC'): the real py-fsrs trainer,
    not a fake, fed naive and non-UTC aware timestamps as a DB session could hand them over."""
    from datetime import timezone

    from kotobaya_worker.optimizer.model import train_pyfsrs

    jst = timezone(timedelta(hours=9))
    reviews = [
        Review(
            r.card_id,
            r.reviewed_at.replace(tzinfo=None) if i % 2 else r.reviewed_at.astimezone(jst),
            r.rating,
        )
        for i, r in enumerate(simulate(true_weights(), cards=60))
    ]
    assert all(r.reviewed_at.tzinfo is UTC for r in reviews)
    weights = train_pyfsrs(reviews)  # 600 reviews: past py-fsrs' 512 minimum, so it really trains
    assert weights_in_bounds(weights)
    assert evaluate(weights, reviews) is not None
