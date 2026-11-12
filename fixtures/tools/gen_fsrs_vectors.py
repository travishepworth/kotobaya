# /// script
# requires-python = ">=3.12"
# dependencies = ["fsrs==6.3.2"]
# ///
"""Generate FSRS golden vectors with py-fsrs (pinned).

Run: uv run fixtures/tools/gen_fsrs_vectors.py
"""
import json
import random
from datetime import datetime, timedelta, timezone
from pathlib import Path

from fsrs import Card, Rating, Scheduler

PY_FSRS_VERSION = "6.3.2"
SEED = 20261112
N = 240
OUT = Path(__file__).resolve().parent.parent / "fsrs" / "vectors.json"
START = datetime(2024, 1, 1, 9, 0, tzinfo=timezone.utc)
R = {1: Rating.Again, 2: Rating.Hard, 3: Rating.Good, 4: Rating.Easy}
RATING_NAMES = {1: "Again", 2: "Hard", 3: "Good", 4: "Easy"}


def make_sequence(rng: random.Random, kind: str) -> list[tuple[float, int]]:
    """Return (gap_days_before_review, rating) steps; first gap is 0."""
    n = rng.randint(4, 25)
    steps: list[tuple[float, int]] = [(0.0, rng.choice([1, 3, 3, 3, 4, 2]))]
    for _ in range(n - 1):
        if kind == "lapses":
            rating = rng.choice([1, 1, 3, 3, 2, 4])
        elif kind == "easy":
            rating = rng.choice([3, 4, 4, 4])
        elif kind == "longgap":
            rating = rng.choice([3, 3, 2, 1, 4])
        else:
            rating = rng.choices([1, 2, 3, 4], weights=[1, 2, 8, 2])[0]
        if kind == "longgap" and rng.random() < 0.35:
            gap = float(rng.randint(120, 1500))
        elif kind == "sameday":
            gap = rng.choice([0.0, 0.0, 0.01, 1.0, 3.0])
        else:
            gap = float(rng.choice([0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 60, 90]))
            gap += rng.choice([0.0, 0.0, 0.25, 0.5])
        steps.append((gap, rating))
    return steps


def run(scheduler: Scheduler, steps: list[tuple[float, int]]) -> list[dict]:
    card = Card()
    now = START
    out = []
    for gap, rating in steps:
        now = now + timedelta(days=gap)
        card, _log = scheduler.review_card(card, R[rating], now)
        out.append(
            {
                "gap_days": gap,
                "rating": rating,
                "review_at": now.isoformat(),
                "state": card.state.name,
                "step": card.step,
                "stability": card.stability,
                "difficulty": card.difficulty,
                "due": card.due.isoformat(),
                "interval_seconds": (card.due - now).total_seconds(),
            }
        )
    return out


def main() -> None:
    rng = random.Random(SEED)
    scheduler = Scheduler(enable_fuzzing=False)  # library defaults otherwise
    kinds = ["normal", "lapses", "longgap", "easy", "sameday"]
    seqs = []
    for i in range(N):
        kind = kinds[i % len(kinds)]
        steps = make_sequence(rng, kind)
        seqs.append({"id": f"seq{i:03d}", "kind": kind, "reviews": run(scheduler, steps)})
    doc = {
        "generator": "fixtures/tools/gen_fsrs_vectors.py",
        "py_fsrs_version": PY_FSRS_VERSION,
        "seed": SEED,
        "start": START.isoformat(),
        "scheduler": {
            "desired_retention": scheduler.desired_retention,
            "learning_steps_seconds": [s.total_seconds() for s in scheduler.learning_steps],
            "relearning_steps_seconds": [s.total_seconds() for s in scheduler.relearning_steps],
            "maximum_interval": scheduler.maximum_interval,
            "enable_fuzzing": scheduler.enable_fuzzing,
            "parameters": list(scheduler.parameters),
        },
        "rating_names": RATING_NAMES,
        "sequences": seqs,
    }
    OUT.write_text(json.dumps(doc, indent=1) + "\n")
    print(f"wrote {len(seqs)} sequences to {OUT}")


if __name__ == "__main__":
    main()
