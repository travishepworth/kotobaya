"""Session state: the single source of truth behind every emitted line. No I/O here."""

from __future__ import annotations

import math
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime

STATE_VERSION = 1
DURATION_CAP_MS = 60_000
QUEUE_LIMIT = 20  # items asked of GET /review/queue
TEXT_FIELDS = ("front", "back", "reading", "sentence")
Clock = Callable[[], datetime]


def utc_now() -> datetime:
    return datetime.now(UTC)


@dataclass(frozen=True)
class CardEntry:
    """One queued card. `text` stays None until the server sends QueueItem.text (ARCH-CARD-TEXT)."""

    card_id: str
    kind: str
    previews: dict[str, str] | None = None
    text: dict[str, str] | None = None


@dataclass(frozen=True)
class Counts:
    new: int
    learning: int
    review: int


@dataclass(frozen=True)
class QueueSnapshot:
    items: list[CardEntry]
    counts: Counts
    day_ends_at: str
    # C-26/C-27: `more_available`, `study_day` and `to_materialize` of the response.
    more_available: bool = False
    study_day: str | None = None
    to_materialize: int = 0
    # ADR-033: earliest instant a not-yet-due learning card falls due today (ISO UTC), or None.
    next_learning_due_at: str | None = None


@dataclass(frozen=True)
class Last:
    rating: str
    undoable: bool
    review_id: str  # kept for undo; not part of the emitted state


def format_interval(due_at: datetime, interval_days: int, now: datetime) -> str:
    """'10m', '3h', '3d', '2mo', '1y' for a rating's next due time."""
    if interval_days <= 0:
        minutes = max(1, math.ceil((due_at - now).total_seconds() / 60))
        return f"{minutes}m" if minutes < 60 else f"{math.ceil(minutes / 60)}h"
    if interval_days < 30:
        return f"{interval_days}d"
    if interval_days < 365:
        return f"{interval_days // 30}mo"
    return f"{interval_days // 365}y"


class Session:
    """Queue prefix (head = current card), counts, reveal flag and timing."""

    def __init__(self, clock: Clock = utc_now) -> None:
        self._clock = clock
        self.conn = "offline"
        self.counts: Counts | None = None
        self.day_ends_at: str | None = None
        self.queue: list[CardEntry] = []
        self.revealed = False
        self.shown_at = clock()
        self.last_action_at = self.shown_at
        self.last: Last | None = None
        self.pending = 0
        self.notice: str | None = None
        self.grading_buttons = 4
        # Keep going (C-26/C-27): in memory only, for this daemon run; dropped on a study-day change.
        self.keep_going = False
        self.keep_going_day: str | None = None
        self.more_available = False
        self.next_due_at: str | None = None
        self.loading = False  # a refresh / keep-going refetch is running in the background

    @property
    def current(self) -> CardEntry | None:
        return self.queue[0] if self.queue else None

    # ---- mutation -----------------------------------------------------------

    def _new_card_shown(self) -> None:
        self.revealed = False
        self.shown_at = self._clock()
        self.last_action_at = self.shown_at

    def set_notice(self, text: str) -> None:
        self.notice = text

    def apply_queue(
        self,
        snapshot: QueueSnapshot,
        *,
        check_current: bool,
        pending: Mapping[str, str] | None = None,
    ) -> None:
        """Take a refetched queue. The card on screen is replaced only when `check_current` is set
        and the server no longer has it (reviewed elsewhere, suspended, deleted).

        `pending` maps card ids with unsent journal entries to the latest one's op
        ("answer" / "undo"): a card with an unsent answer is left out (the snapshot may predate
        the POST), and a card with any unsent entry is never replaced as "gone"."""
        pending = pending or {}
        items = [i for i in snapshot.items if pending.get(i.card_id) != "answer"]
        self.counts = snapshot.counts
        self.day_ends_at = snapshot.day_ends_at
        self.next_due_at = snapshot.next_learning_due_at
        current = self.current
        if current is None:
            self.queue = items
            if self.queue:
                self._new_card_shown()
        elif (
            check_current
            # A full snapshot may simply have pushed the card past the limit, and a card the user
            # already revealed is answered (a 409 is handled then), not swapped away.
            and len(snapshot.items) < QUEUE_LIMIT
            and not self.revealed
            and current.card_id not in pending
            and all(i.card_id != current.card_id for i in items)
        ):
            self.queue = items
            self.notice = "reviewed elsewhere"
            self._new_card_shown()
        else:
            self.queue = [current] + [i for i in items if i.card_id != current.card_id]

    def reveal(self) -> None:
        if self.current is not None and not self.revealed:
            self.revealed = True
            self.last_action_at = self._clock()

    def skip(self) -> bool:
        """Move the current card behind the prefetched ones. False when there is nothing to skip to."""
        if len(self.queue) < 2:
            return False
        self.queue.append(self.queue.pop(0))
        self._new_card_shown()
        return True

    def pop_current(self) -> CardEntry | None:
        """Seam for CLI-REVIEW: drop the answered head and show the next prefetched card."""
        if not self.queue:
            return None
        answered = self.queue.pop(0)
        self._new_card_shown()
        return answered

    def push_front(self, entry: CardEntry) -> None:
        """Seam for CLI-REVIEW undo: put a card back at the head, unrevealed."""
        self.queue = [entry] + [i for i in self.queue if i.card_id != entry.card_id]
        self._new_card_shown()

    def now(self) -> datetime:
        return self._clock()

    def duration_ms(self) -> int:
        """Answer time minus max(shown_at, last widget-visible action), capped at 60 s."""
        start = max(self.shown_at, self.last_action_at)
        return min(DURATION_CAP_MS, max(0, int((self._clock() - start).total_seconds() * 1000)))

    # ---- output -------------------------------------------------------------

    def _card_dict(self, entry: CardEntry) -> dict[str, object]:
        card: dict[str, object] = {"id": entry.card_id, "kind": entry.kind}
        if entry.text:
            # While hidden only the front leaves the daemon; the rest is answer-side.
            for name in TEXT_FIELDS if self.revealed else ("front",):
                value = entry.text.get(name)
                if value is not None:
                    card[name] = value
        card["revealed"] = self.revealed
        if entry.previews:
            card["previews"] = dict(entry.previews)
        return card

    @property
    def can_keep_going(self) -> bool:
        return (
            self.more_available
            and not self.keep_going
            and self.current is None
            and self.counts is not None
            and self.conn == "ok"
        )

    def state(self) -> dict[str, object]:
        """The full state object (schema/state.v1.json). Does not clear the notice."""
        counts = self.counts
        current = self.current
        return {
            "v": STATE_VERSION,
            "conn": self.conn,
            "counts": None
            if counts is None
            else {"new": counts.new, "learning": counts.learning, "review": counts.review},
            "day_ends_at": self.day_ends_at,
            "card": None if current is None else self._card_dict(current),
            "last": None
            if self.last is None
            else {"rating": self.last.rating, "undoable": self.last.undoable},
            "pending": self.pending,
            "notice": self.notice,
            "keep_going": self.keep_going,
            "more_available": self.more_available,
            # ADR-031/033: Keep going is offered whenever nothing is shown, the server says it has
            # more (done, done-for-now and waiting alike) and it is not already on.
            "can_keep_going": self.can_keep_going,
            "loading": self.loading,
            # ADR-033: only while nothing is shown; the widget's "waiting" state.
            "next_due_at": self.next_due_at if current is None and counts is not None else None,
        }

    def take_state(self) -> dict[str, object]:
        """State for an emitted line: a notice is one-shot, so it is cleared afterwards."""
        state = self.state()
        self.notice = None
        return state
