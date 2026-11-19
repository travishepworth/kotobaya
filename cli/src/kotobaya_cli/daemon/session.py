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


def _parse_instant(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value)
    except ValueError:
        return None


def utc_now() -> datetime:
    return datetime.now(UTC)


KIND_LABELS = {
    "vocab": "Vocab",
    "kanji": "Kanji",
    "phrase": "Phrase",
    "grammar": "Grammar",
    "sentence": "Sentence",
    "custom": "Custom",
}


def kind_label(note_type: str) -> str:
    """English label shown on every client's card-type badge: vocab -> Vocab, grammar -> Grammar."""
    return KIND_LABELS.get(note_type) or note_type.replace("_", " ").title()


@dataclass(frozen=True)
class CardEntry:
    """One queued card. `text` stays None until the server sends QueueItem.text (ARCH-CARD-TEXT)."""

    card_id: str
    kind: str
    previews: dict[str, str] | None = None
    text: dict[str, str] | None = None
    note_type: str | None = None  # Card.note.type (NoteBaseType value), None when not sent
    # C1: rating name -> due_at (ISO UTC) of the preview when that answer leaves the card in
    # learning/relearning (preview interval 0 days); absent for answers that graduate the card.
    learn_due: dict[str, str] | None = None


@dataclass(frozen=True)
class Counts:
    new: int
    learning: int
    review: int


PHASES = ("reviewing", "goal_reached", "done_for_today", "waiting", "empty")


@dataclass(frozen=True)
class UntilStopParts:
    """ADR-049 `until_stop_parts`: until_stop split into learning, later, review and new."""

    learning: int
    later: int
    review: int
    new: int


@dataclass(frozen=True)
class Loop:
    """ADR-037 `ReviewLoop` as sent by getReviewQueue; the only source of phase and numbers."""

    phase: str
    keep_going: bool
    ready_learning: int
    ready_review: int
    ready_total: int
    later_count: int
    next_due_at: str | None  # UTC ISO; None iff later_count is 0
    backlog_reviews: int
    backlog_beyond_limit: int
    reviews_today: int
    reviews_limit: int | None  # None = reviews unlimited
    new_today: int
    new_goal: int
    new_available: bool
    # ADR-049: None when the server is older (no until_stop_parts sent); consumers fall back.
    until_stop_parts: UntilStopParts | None = None


@dataclass(frozen=True)
class QueueSnapshot:
    items: list[CardEntry]
    counts: Counts
    day_ends_at: str
    # ADR-037: the loop. The HTTP backend raises BackendError("too_old") when the server sends
    # none, so a real snapshot always has it; None only appears in unit-test snapshots.
    loop: Loop | None = None
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
        self.study_day: str | None = None
        self.more_available = False
        self.next_due_at: str | None = None
        self.loop: Loop | None = None
        # C1: T = min(loop.later_today.next_due_at of the last response, due_at of every answered
        # card that is still learning). From T on the cached queue may not be served.
        self.response_due: datetime | None = None
        self.answered_due: dict[str, datetime] = {}
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
        self.loop = snapshot.loop
        self.study_day = snapshot.study_day or self.study_day
        self.next_due_at = (
            snapshot.loop.next_due_at if snapshot.loop else snapshot.next_learning_due_at
        )
        self.response_due = _parse_instant(self.next_due_at)
        self.answered_due = {c: d for c, d in self.answered_due.items() if c in pending}
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

    def note_answer(self, card: CardEntry, rating: int) -> None:
        """C1: remember when an answered card that stays in learning falls due (rating 1-4)."""
        name = {1: "again", 2: "hard", 3: "good", 4: "easy"}.get(rating, "")
        due = _parse_instant((card.learn_due or {}).get(name))
        if due is not None:
            self.answered_due[card.card_id] = due

    @property
    def due_floor(self) -> datetime | None:
        """C1's T, or None when nothing is expected to fall due."""
        times = [t for t in (self.response_due, *self.answered_due.values()) if t is not None]
        return min(times) if times else None

    def due_passed(self) -> bool:
        floor = self.due_floor
        return floor is not None and self._clock() >= floor

    def drop_queue(self) -> None:
        """C1: do not serve the cached cards; a refetch decides what comes next."""
        self.queue = []

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
        if entry.note_type:
            card["kind_label"] = kind_label(entry.note_type)
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
    def timer_due(self) -> str | None:
        """C4: the one timer instant; only in phase waiting."""
        if self.loop is not None and self.loop.phase != "waiting":
            return None
        return self.next_due_at

    @property
    def can_keep_going(self) -> bool:
        loop = self.loop
        return (
            loop is not None
            and loop.phase in ("goal_reached", "done_for_today")
            and not self.keep_going
            and self.current is None
            and self.conn == "ok"
        )

    def _loop_fields(self) -> dict[str, object]:
        loop = self.loop if self.conn == "ok" else None
        return {
            "phase": None if loop is None else loop.phase,
            "ready": None if loop is None else loop.ready_total,
            "later_today": None if loop is None else loop.later_count,
            "backlog": None if loop is None else loop.backlog_reviews,
            "backlog_beyond_limit": None if loop is None else loop.backlog_beyond_limit,
            "reviews_today": None if loop is None else loop.reviews_today,
            "reviews_limit": None if loop is None else loop.reviews_limit,
            "new_today": None if loop is None else loop.new_today,
            "new_goal": None if loop is None else loop.new_goal,
            # The next learning card's instant (a hint) for the widget's "N later" line; the
            # top-level next_due_at below is the waiting timer only.
            "later_next_due_at": None if loop is None else loop.next_due_at,
            # ADR-049: the four parts of until_stop (null from an older server or while not ok).
            "until_stop_parts": None
            if loop is None or loop.until_stop_parts is None
            else {
                "learning": loop.until_stop_parts.learning,
                "later": loop.until_stop_parts.later,
                "review": loop.until_stop_parts.review,
                "new": loop.until_stop_parts.new,
            },
        }

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
            "next_due_at": self.timer_due if current is None and counts is not None else None,
            **self._loop_fields(),
        }

    def take_state(self) -> dict[str, object]:
        """State for an emitted line: a notice is one-shot, so it is cleared afterwards."""
        state = self.state()
        self.notice = None
        return state
