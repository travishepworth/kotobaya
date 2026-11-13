"""Pure optimizer maths: split, train (py-fsrs 6.3.2), and the held-out evaluation.

Split (worker configuration, FSRS_PUBLISH.md 1 step 2): by card, in time. Cards are ordered by the time of their
first review; the latest ``holdout_fraction`` of the cards (at least one, never all) are held out with their whole
history. A card is therefore never in both sets, and the held-out set is entirely newer cards, so a model that
memorised old cards gains nothing on it. The held-out cards are never shown to the trainer.

Evaluation (one procedure for current and new weights): replay every held-out card with a fuzz-free py-fsrs
``Scheduler(parameters=weights)``; for each review that comes at least one day after the card's previous review
(the same reviews the py-fsrs optimizer scores) take the predicted retrievability ``p`` and the outcome
``y = rating > Again``. ``log_loss`` is the mean binary log loss; ``rmse_bins`` groups the predictions into equal-width
bins of ``p`` and is the count-weighted RMSE between each bin's mean ``p`` and mean ``y``.
"""

from __future__ import annotations

import math
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime

from fsrs import Card, Rating, Scheduler
from fsrs.scheduler import DEFAULT_PARAMETERS, LOWER_BOUNDS_PARAMETERS, UPPER_BOUNDS_PARAMETERS

N_WEIGHTS = 21
BINS = 20
EPS = 1e-6  # predictions are clipped to [EPS, 1 - EPS], as torch's BCELoss clamps its log


@dataclass(frozen=True)
class Review:
    card_id: str
    reviewed_at: datetime  # timezone-aware
    rating: int  # 1..4


Trainer = Callable[[Sequence[Review]], list[float]]


@dataclass(frozen=True)
class Evaluation:
    log_loss: float
    rmse_bins: float
    count: int  # scored reviews


def _by_card(reviews: Sequence[Review]) -> dict[str, list[Review]]:
    cards: dict[str, list[Review]] = {}
    for r in reviews:  # input order is (reviewed_at, hlc, id); keep it
        cards.setdefault(r.card_id, []).append(r)
    return cards


def split_by_card(
    reviews: Sequence[Review], holdout_fraction: float = 0.2
) -> tuple[list[Review], list[Review]] | None:
    """``(train, holdout)``, or None when there are fewer than two cards (no non-empty split)."""
    cards = _by_card(reviews)
    if len(cards) < 2:
        return None
    order = sorted(cards, key=lambda c: (cards[c][0].reviewed_at, c))
    n_hold = min(len(order) - 1, max(1, math.ceil(len(order) * holdout_fraction)))
    hold = set(order[len(order) - n_hold :])
    train = [r for r in reviews if r.card_id not in hold]
    holdout = [r for r in reviews if r.card_id in hold]
    return train, holdout


def weights_in_bounds(weights: Sequence[float]) -> bool:
    """The C-1 bounds (py-fsrs 6.3.2 ``LOWER/UPPER_BOUNDS_PARAMETERS``), inclusive; finite, exactly 21."""
    return len(weights) == N_WEIGHTS and all(
        math.isfinite(w) and lo <= w <= hi
        for w, lo, hi in zip(weights, LOWER_BOUNDS_PARAMETERS, UPPER_BOUNDS_PARAMETERS, strict=True)
    )


def evaluate(weights: Sequence[float], reviews: Sequence[Review]) -> Evaluation | None:
    """Held-out log loss and RMSE (bins) of ``weights``; None when no review is scorable."""
    scheduler = Scheduler(parameters=list(weights), enable_fuzzing=False)
    loss = 0.0
    n = 0
    bin_n = [0] * BINS
    bin_p = [0.0] * BINS
    bin_y = [0.0] * BINS
    for history in _by_card(reviews).values():
        card = Card(card_id=0, due=history[0].reviewed_at)
        for r in history:
            if card.last_review is not None and (r.reviewed_at - card.last_review).days > 0:
                p = min(1.0 - EPS, max(EPS, scheduler.get_card_retrievability(card, r.reviewed_at)))
                y = 1.0 if r.rating > 1 else 0.0
                loss -= y * math.log(p) + (1.0 - y) * math.log(1.0 - p)
                n += 1
                b = min(BINS - 1, int(p * BINS))
                bin_n[b] += 1
                bin_p[b] += p
                bin_y[b] += y
            card, _ = scheduler.review_card(card, Rating(r.rating), r.reviewed_at)
    if n == 0:
        return None
    sq = sum(c * (bin_p[i] / c - bin_y[i] / c) ** 2 for i, c in enumerate(bin_n) if c)
    return Evaluation(loss / n, math.sqrt(sq / n), n)


def train_pyfsrs(train: Sequence[Review]) -> list[float]:
    """Trains with the py-fsrs 6.3.2 optimizer (torch, CPU). Starts from the default weights; returns the
    defaults unchanged when the data has fewer than 512 scorable reviews (library behaviour)."""
    from fsrs import ReviewLog
    from fsrs.optimizer import Optimizer

    ids: dict[str, int] = {}
    logs = [
        ReviewLog(
            card_id=ids.setdefault(r.card_id, len(ids)),
            rating=Rating(r.rating),
            review_datetime=r.reviewed_at,
            review_duration=None,
        )
        for r in train
    ]
    return [float(w) for w in Optimizer(logs).compute_optimal_parameters()]


def defaults() -> list[float]:
    return [float(w) for w in DEFAULT_PARAMETERS]


@dataclass(frozen=True)
class Config:
    min_reviews: int = 1000
    holdout_fraction: float = 0.2

    @staticmethod
    def from_env(env: Mapping[str, str]) -> Config:
        return Config(
            min_reviews=int(env.get("OPTIMIZER_MIN_REVIEWS", "1000")),
            holdout_fraction=float(env.get("OPTIMIZER_HOLDOUT_FRACTION", "0.2")),
        )
