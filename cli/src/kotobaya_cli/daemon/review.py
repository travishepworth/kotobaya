"""Journal-first answers and undo (docs/CLI-DAEMON.md, Review flow 1-7).

Every answer/undo is written to the journal and fsynced before anything else happens. A single
background task sends entries strictly in order, each with the id minted when it was journaled,
and removes an entry only after a 2xx or a terminal 409/410/422 (or the server's own 404
`not_found`), with a notice. Offline errors and any other 4xx are retried with backoff; 401 waits
for the daemon to come back online; 403 stops reviewing until restart. Unsent entries are replayed
on the next start.
"""

from __future__ import annotations

import asyncio
import contextlib
from collections.abc import Callable
from datetime import UTC, datetime
from typing import TYPE_CHECKING

from kotobaya_cli.api import uuid7
from kotobaya_cli.daemon.backend import BackendError, classify_review
from kotobaya_cli.daemon.journal import AnswerEntry, Entry, Journal, UndoEntry
from kotobaya_cli.daemon.protocol import resolve_button
from kotobaya_cli.daemon.session import CardEntry, Last

if TYPE_CHECKING:
    from kotobaya_cli.daemon.backend import Backend
    from kotobaya_cli.daemon.server import Daemon

UTC_OFFSET_LIMIT = 1080  # contract bound for utc_offset_min
RETRY_BASE_S = 1.0
RETRY_MAX_S = 30.0
FORBIDDEN_NOTICE = "review forbidden: the token needs the review scope (restart after fixing)"


def local_utc_offset_min() -> int:
    offset = datetime.now().astimezone().utcoffset()
    return 0 if offset is None else int(offset.total_seconds() // 60)


def iso_ms(instant: datetime) -> str:
    utc = instant.astimezone(UTC)
    return utc.strftime("%Y-%m-%dT%H:%M:%S.") + f"{utc.microsecond // 1000:03d}Z"


def _send(backend: Backend, entry: Entry) -> None:
    if isinstance(entry, AnswerEntry):
        backend.post_answer(entry)
    else:
        backend.post_undo(entry.review_id)


def _not_ok_notice(conn: str) -> str:
    return "token rejected" if conn == "auth" else "offline"


class ReviewHandler:
    """The daemon's AnswerHandler."""

    def __init__(
        self,
        journal: Journal,
        *,
        utc_offset_min: Callable[[], int] = local_utc_offset_min,
        mint_id: Callable[[], str] = uuid7,
        retry_base_s: float = RETRY_BASE_S,
        retry_max_s: float = RETRY_MAX_S,
    ) -> None:
        self._journal = journal
        self._utc_offset_min = utc_offset_min
        self._mint_id = mint_id
        self._retry_base_s = retry_base_s
        self._retry_max_s = retry_max_s
        self._last_card: CardEntry | None = None
        self._task: asyncio.Task[None] | None = None
        self._wake: asyncio.Event | None = None
        self._forbidden = False  # 403 on a review POST: sticky until the daemon restarts
        self._rejected_notice_for: str | None = None
        # Card ids whose answer was acked, with an ack sequence number: kept out of refetched
        # queues until a fetch that started after the ack has been applied (end_fetch).
        self._acked: dict[str, int] = {}
        self._ack_seq = 0

    # ---- AnswerHandler ------------------------------------------------------

    async def answer(self, daemon: Daemon, button: str) -> None:
        session = daemon.session
        if self._forbidden:
            session.set_notice(FORBIDDEN_NOTICE)
            return
        if session.conn != "ok":
            session.set_notice(_not_ok_notice(session.conn))  # no offline reviewing in v1
            return
        card = session.current
        if card is None:
            session.set_notice("nothing to review")
            return
        rating, sent_button = resolve_button(button, session.grading_buttons)
        offset = max(-UTC_OFFSET_LIMIT, min(UTC_OFFSET_LIMIT, self._utc_offset_min()))
        entry = AnswerEntry(
            id=self._mint_id(),
            card_id=card.card_id,
            rating=rating,
            button=sent_button,
            reviewed_at=iso_ms(session.now()),
            duration_ms=session.duration_ms(),
            utc_offset_min=offset,
        )
        self._journal.append(entry)  # durable before the card advances or anything is sent
        session.pop_current()
        session.last = Last(rating=sent_button, undoable=True, review_id=entry.id)
        self._last_card = card
        self._kick(daemon)

    async def undo(self, daemon: Daemon) -> None:
        session = daemon.session
        last, card = session.last, self._last_card
        if last is None or not last.undoable or card is None:
            session.set_notice("nothing to undo")
            return
        if self._forbidden:
            session.set_notice(FORBIDDEN_NOTICE)
            return
        if session.conn != "ok":
            session.set_notice(_not_ok_notice(session.conn))
            return
        self._journal.append(UndoEntry(review_id=last.review_id, card_id=card.card_id))
        session.push_front(card)
        session.last = Last(rating=last.rating, undoable=False, review_id=last.review_id)
        self._last_card = None
        self._kick(daemon)

    async def on_online(self, daemon: Daemon) -> None:
        if self._journal.notice:
            daemon.session.set_notice(self._journal.notice)
            self._journal.notice = None
        self._kick(daemon)

    def pending_count(self) -> int:
        return len(self._journal.entries)

    def pending_cards(self) -> dict[str, str]:
        pending = dict.fromkeys(self._acked, "answer")
        for entry in self._journal.entries:
            pending[entry.card_id] = "answer" if isinstance(entry, AnswerEntry) else "undo"
        return pending

    def begin_fetch(self) -> int:
        return self._ack_seq

    def end_fetch(self, token: int) -> None:
        """A fetch that started at `token` was applied: answers acked before it are reflected."""
        self._acked = {card: seq for card, seq in self._acked.items() if seq > token}

    def close(self) -> None:
        if self._task is not None:
            self._task.cancel()
            self._task = None

    async def idle(self) -> None:
        """Wait until the sender has nothing it can send (journal empty, or blocked on auth)."""
        while self._task is not None and not self._task.done():
            await asyncio.wait({self._task})

    # ---- sender -------------------------------------------------------------

    def _kick(self, daemon: Daemon) -> None:
        if self._wake is None:
            self._wake = asyncio.Event()
        self._wake.set()
        if self._forbidden:
            return
        if self._task is None or self._task.done():
            self._task = asyncio.create_task(self._drain(daemon))

    async def _drain(self, daemon: Daemon) -> None:
        try:
            while True:
                sent = await self._send_pending(daemon)
                if sent:
                    await daemon.refetch()  # counts and the cards after the current one only
                blocked = daemon.session.conn == "auth" or self._forbidden
                if not sent or not self._journal.entries or blocked:
                    return
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001  # e.g. the journal cannot be rewritten
            daemon.session.set_notice(f"journal error: {type(exc).__name__}")
            daemon.emit()

    def _acked_ok(self, entry: Entry) -> None:
        self._ack_seq += 1
        if isinstance(entry, AnswerEntry):
            self._acked[entry.card_id] = self._ack_seq
        else:
            self._acked.pop(entry.card_id, None)

    def _drop_terminal(self, daemon: Daemon, entry: Entry, err: BackendError) -> None:
        self._journal.remove(entry)
        what = "answer" if isinstance(entry, AnswerEntry) else "undo"
        daemon.session.set_notice(f"{what} not saved ({err})")
        if isinstance(entry, AnswerEntry):
            last = daemon.session.last
            if last is not None and last.review_id == entry.id:
                daemon.session.last = Last(last.rating, undoable=False, review_id=last.review_id)
                self._last_card = None
            for queued in self._journal.entries:  # its undo would only 404: drop it quietly
                if isinstance(queued, UndoEntry) and queued.review_id == entry.id:
                    self._journal.remove(queued)

    async def _send_pending(self, daemon: Daemon) -> bool:
        """Send entries in order until the journal is empty or the head cannot be sent now.
        True when at least one entry left the journal."""
        assert self._wake is not None
        delay = self._retry_base_s
        progressed = False
        while self._journal.entries:
            self._wake.clear()
            entry = self._journal.entries[0]
            try:
                await asyncio.to_thread(_send, daemon.backend, entry)
            except Exception as exc:  # noqa: BLE001
                err = classify_review(exc)
                if err.kind == "terminal":
                    self._drop_terminal(daemon, entry, err)
                    daemon.emit()
                    progressed = True
                    continue
                if err.kind == "auth":
                    daemon.session.conn = "auth"
                    daemon.emit()
                    return progressed
                if err.kind == "forbidden":
                    self._forbidden = True
                    daemon.session.set_notice(FORBIDDEN_NOTICE)
                    daemon.emit()
                    return progressed
                if err.kind == "rejected" and self._rejected_notice_for != entry.id:
                    self._rejected_notice_for = entry.id  # once per entry, not per retry
                    daemon.session.set_notice(f"server rejected the review ({err}), retrying")
                    daemon.emit()
                # offline / 5xx / timeout / unexpected 4xx: resend the same entry later
                with contextlib.suppress(TimeoutError):
                    await asyncio.wait_for(self._wake.wait(), delay)
                delay = min(delay * 2, self._retry_max_s)
                continue
            self._journal.remove(entry)
            self._acked_ok(entry)
            daemon.emit()
            progressed = True
            delay = self._retry_base_s
        return progressed
