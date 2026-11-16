"""CLI-REVIEW: journal-first answers and undo, retry/replay with the same id, terminal drops,
reviewed-elsewhere replacement and five-button mapping."""

from __future__ import annotations

import asyncio
import json
import os
import shutil
import tempfile
import threading
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest
from conftest import FakeHttp

from kotobaya_cli.api import Settings
from kotobaya_cli.daemon.backend import BackendError, EventStream, HttpBackend
from kotobaya_cli.daemon.journal import AnswerEntry, Journal, UndoEntry
from kotobaya_cli.daemon.protocol import Action, AnswerCmd, parse_fifo_line, parse_socket_request
from kotobaya_cli.daemon.review import FORBIDDEN_NOTICE, ReviewHandler
from kotobaya_cli.daemon.server import Daemon, Paths
from kotobaya_cli.daemon.session import CardEntry, Counts, QueueSnapshot

DAY_END = "2026-11-15T19:00:00.000Z"
T0 = datetime(2026, 11, 15, 9, 0, tzinfo=UTC)


async def grade(daemon: Daemon, button: str) -> dict[str, object]:
    """Reveal the current card, then grade it (the daemon refuses grading a hidden card)."""
    await daemon.handle(Action("reveal"))
    return await daemon.handle(AnswerCmd(button))


def snapshot(*ids: str) -> QueueSnapshot:
    return QueueSnapshot([CardEntry(i, "recognition") for i in ids], Counts(1, 2, 3), DAY_END)


class _Stream:
    def __iter__(self) -> Iterator[str]:
        return iter(())

    def close(self) -> None:
        return None


class FakeServer:
    """Fake backend that dedupes review ids like the server: one review.logged per id."""

    def __init__(self, journal_path: Path | None = None) -> None:
        self.snap = snapshot("a", "b", "c")
        self.buttons = 4
        self.journal_path = journal_path
        self.events: list[str] = []
        self.answers: list[AnswerEntry] = []
        self.undos: list[str] = []
        self.logged: dict[str, int] = {}
        self.answer_errors: list[BackendError] = []  # raised (and consumed) before applying
        self.lose_response = False  # apply, then fail as if the response was lost
        self.undo_errors: list[BackendError] = []
        self.on_disk_at_post: list[list[str]] = []
        self.lock = threading.Lock()

    def fetch_queue(self, keep_going: bool = False) -> QueueSnapshot:
        return self.snap

    def fetch_grading_buttons(self) -> int:
        return self.buttons

    def open_events(self) -> EventStream:
        return _Stream()

    def post_answer(self, entry: AnswerEntry) -> None:
        with self.lock:
            self.events.append(f"post:{entry.id}")
            if self.journal_path is not None:
                self.on_disk_at_post.append([e.id for e in Journal(self.journal_path).entries])
            self.answers.append(entry)
            if self.answer_errors:
                raise self.answer_errors.pop(0)
            if entry.id not in self.logged:
                self.logged[entry.id] = 1  # a re-sent id returns 200 and emits nothing
                self.snap = QueueSnapshot(
                    [i for i in self.snap.items if i.card_id != entry.card_id],
                    self.snap.counts,
                    DAY_END,
                )
            if self.lose_response:
                self.lose_response = False
                raise BackendError("offline", "read timeout")

    def post_undo(self, review_id: str) -> None:
        with self.lock:
            self.undos.append(review_id)
            if self.undo_errors:
                raise self.undo_errors.pop(0)


@pytest.fixture
def paths() -> Iterator[Paths]:
    root = Path(tempfile.mkdtemp(prefix="kbr"))
    yield Paths(root / "in", root / "sock", root / "state" / "journal")
    shutil.rmtree(root, ignore_errors=True)


def make(server: FakeServer, paths: Paths, **kw: Any) -> tuple[Daemon, ReviewHandler]:
    kw.setdefault("retry_base_s", 0.01)
    handler = ReviewHandler(Journal(paths.journal), utc_offset_min=lambda: 540, **kw)
    daemon = Daemon(server, paths, clock=lambda: T0, handler=handler, timer_seconds=3600)
    return daemon, handler


def st(state: dict[str, object]) -> dict[str, Any]:
    return state


def ids(daemon: Daemon) -> list[str]:
    return [c.card_id for c in daemon.session.queue]


# ---- journal ------------------------------------------------------------------


def test_journal_roundtrip_and_atomic_rewrite(paths: Paths) -> None:
    j = Journal(paths.journal)
    assert j.entries == []
    a = AnswerEntry("01", "a", 3, "good", "2026-11-15T09:00:00.000Z", 1200, 540)
    u = UndoEntry("01", "a")
    j.append(a)
    j.append(u)
    assert Journal(paths.journal).entries == [a, u]
    j.remove(a)
    assert Journal(paths.journal).entries == [u]
    assert not paths.journal.with_name("journal.tmp").exists()


def test_corrupt_journal_lines_are_preserved_not_silently_dropped(paths: Paths) -> None:
    good = AnswerEntry("01", "a", 3, "good", "2026-11-15T09:00:00.000Z", 0, 0)
    paths.journal.parent.mkdir(parents=True)
    paths.journal.write_text(json.dumps(good.to_json()) + "\n{broken\n")
    j = Journal(paths.journal)
    assert j.entries == [good]
    assert j.notice is not None and "corrupt" in j.notice
    kept = list(paths.journal.parent.glob("journal.corrupt.*"))
    assert len(kept) == 1 and "{broken" in kept[0].read_text()


# ---- answer -------------------------------------------------------------------


def test_journal_written_and_fsynced_before_post_answer(
    paths: Paths, monkeypatch: pytest.MonkeyPatch
) -> None:
    server = FakeServer(paths.journal)
    real_fsync = os.fsync

    def fsync(fd: int) -> None:
        server.events.append("fsync")
        real_fsync(fd)

    monkeypatch.setattr(os, "fsync", fsync)

    async def scenario() -> None:
        daemon, handler = make(server, paths)
        await daemon.refetch()
        server.events.clear()
        out = st(await grade(daemon, "good"))
        assert out["card"]["id"] == "b"  # advanced at once, before the POST
        assert out["last"] == {"rating": "good", "undoable": True}
        await handler.idle()
        assert server.events.index("fsync") < server.events.index(f"post:{server.answers[0].id}")
        assert server.on_disk_at_post == [[server.answers[0].id]]
        sent = server.answers[0]
        assert (sent.card_id, sent.rating, sent.button) == ("a", 3, "good")
        assert sent.reviewed_at == "2026-11-15T09:00:00.000Z" and sent.utc_offset_min == 540
        assert Journal(paths.journal).entries == []
        assert handler.pending_count() == 0

    asyncio.run(scenario())


def test_grade_on_a_hidden_card_is_refused_and_changes_nothing(paths: Paths) -> None:
    server = FakeServer(paths.journal)

    async def scenario() -> None:
        daemon, handler = make(server, paths)
        await daemon.refetch()
        before = daemon.session.state()
        out = st(await daemon.handle(AnswerCmd("good")))
        assert out["notice"] == "reveal the card before grading"
        assert {**out, "notice": None} == {**before, "notice": None}
        assert out["card"]["id"] == "a" and out["last"] is None and not out["card"]["revealed"]
        await handler.idle()
        assert server.answers == [] and Journal(paths.journal).entries == []
        await daemon.handle(Action("reveal"))
        out = st(await daemon.handle(AnswerCmd("good")))  # revealed: grading works
        assert out["card"]["id"] == "b" and out["last"] == {"rating": "good", "undoable": True}
        await handler.idle()
        assert len(server.answers) == 1

    asyncio.run(scenario())


def test_retry_reuses_same_id_no_double_review_logged(paths: Paths) -> None:
    server = FakeServer()
    server.answer_errors = [BackendError("offline"), BackendError("offline", "HTTP 503")]
    server.lose_response = True

    async def scenario() -> None:
        daemon, handler = make(server, paths)
        await daemon.refetch()
        await grade(daemon, "good")
        await handler.idle()
        assert len(server.answers) == 4  # two failures, one lost response, one success
        assert len({a.id for a in server.answers}) == 1
        assert len({a.reviewed_at for a in server.answers}) == 1
        assert server.logged == {server.answers[0].id: 1}
        assert handler.pending_count() == 0

    asyncio.run(scenario())


def test_daemon_crash_between_journal_and_post_replays_exactly_once(paths: Paths) -> None:
    server = FakeServer()

    async def first_life() -> str:
        daemon, handler = make(server, paths)
        await daemon.refetch()
        server.answer_errors = [BackendError("offline")] * 1000  # the POST never lands
        await grade(daemon, "easy")
        await asyncio.sleep(0.05)
        assert handler.pending_count() == 1
        handler.close()  # the process dies here
        return Journal(paths.journal).entries[0].id

    review_id = asyncio.run(first_life())
    assert server.logged == {}
    server.answer_errors = []

    async def second_life() -> None:
        daemon, handler = make(server, paths)
        assert handler.pending_count() == 1
        await daemon.refetch()  # coming online replays the journal
        await handler.idle()
        assert handler.pending_count() == 0
        assert ids(daemon) == ["b", "c"]

    asyncio.run(second_life())
    assert server.logged == {review_id: 1}
    assert server.answers[-1].id == review_id


def test_crash_after_post_before_journal_removal_replays_without_double_log(
    paths: Paths,
) -> None:
    server = FakeServer()
    server.lose_response = True

    async def first_life() -> None:
        daemon, handler = make(server, paths, retry_base_s=3600)
        await daemon.refetch()
        await grade(daemon, "good")
        await asyncio.sleep(0.05)
        handler.close()

    asyncio.run(first_life())
    assert len(Journal(paths.journal).entries) == 1

    async def second_life() -> None:
        daemon, handler = make(server, paths)
        await daemon.refetch()
        await handler.idle()

    asyncio.run(second_life())
    assert list(server.logged.values()) == [1]
    assert len({a.id for a in server.answers}) == 1 and len(server.answers) == 2
    assert Journal(paths.journal).entries == []


@pytest.mark.parametrize("status", [409, 410, 422])
def test_terminal_4xx_drops_the_entry_with_a_notice(paths: Paths, status: int) -> None:
    server = FakeServer()
    server.answer_errors = [BackendError("terminal", f"HTTP {status}")]
    notices: list[object] = []

    async def scenario() -> None:
        daemon, handler = make(server, paths)
        await daemon.refetch()
        await grade(daemon, "good")
        original = daemon.emit

        def spy() -> dict[str, object]:
            state = original()
            notices.append(state["notice"])
            return state

        daemon.emit = spy  # type: ignore[method-assign]
        await handler.idle()
        assert handler.pending_count() == 0
        assert Journal(paths.journal).entries == []
        assert len(server.answers) == 1  # not retried

    asyncio.run(scenario())
    assert any(isinstance(n, str) and str(status) in n for n in notices)


def test_auth_failure_keeps_entry_and_sets_conn_auth(paths: Paths) -> None:
    server = FakeServer()
    server.answer_errors = [BackendError("auth", "HTTP 401")]

    async def scenario() -> None:
        daemon, handler = make(server, paths)
        await daemon.refetch()
        await grade(daemon, "good")
        await handler.idle()
        assert daemon.session.conn == "auth"
        assert handler.pending_count() == 1
        out = st(await grade(daemon, "good"))  # no reviewing without a valid token
        assert out["notice"] is not None and out["card"]["id"] == "b"
        assert handler.pending_count() == 1
        await daemon.refetch()  # token works again → replay
        await handler.idle()
        assert handler.pending_count() == 0 and len(server.logged) == 1

    asyncio.run(scenario())


def test_offline_answer_is_refused_and_nothing_journaled(paths: Paths) -> None:
    server = FakeServer()

    async def scenario() -> None:
        daemon, handler = make(server, paths)
        await daemon.refetch()
        daemon.session.conn = "offline"
        out = st(await grade(daemon, "good"))
        assert out["notice"] == "offline" and out["card"]["id"] == "a"
        assert handler.pending_count() == 0 and not paths.journal.exists()

    asyncio.run(scenario())


def test_answer_with_no_card_is_a_notice(paths: Paths) -> None:
    server = FakeServer()
    server.snap = snapshot()

    async def scenario() -> None:
        daemon, handler = make(server, paths)
        await daemon.refetch()
        out = st(await grade(daemon, "good"))
        assert out["notice"] == "nothing to review" and handler.pending_count() == 0

    asyncio.run(scenario())


def test_five_button_mode_always_sends_a_mode_button(paths: Paths) -> None:
    server = FakeServer()
    server.buttons = 5

    async def scenario() -> None:
        daemon, handler = make(server, paths)
        await daemon.refetch()
        await grade(daemon, "good")
        out = st(await grade(daemon, "something"))
        await handler.idle()
        assert [(a.rating, a.button) for a in server.answers] == [(3, "okay"), (1, "something")]
        assert out["last"]["rating"] == "something"

    asyncio.run(scenario())


def test_duration_is_measured_from_shown_or_last_action(paths: Paths) -> None:
    server = FakeServer()
    now = [T0]
    handler = ReviewHandler(Journal(paths.journal), utc_offset_min=lambda: -300)
    daemon = Daemon(server, paths, clock=lambda: now[0], handler=handler, timer_seconds=3600)

    async def scenario() -> None:
        await daemon.refetch()
        now[0] += timedelta(seconds=4)
        await daemon.handle(Action("reveal"))
        now[0] += timedelta(seconds=2)
        await grade(daemon, "hard")
        await handler.idle()
        assert server.answers[0].duration_ms == 2000
        assert server.answers[0].utc_offset_min == -300

    asyncio.run(scenario())


# ---- the card on screen -------------------------------------------------------


def test_current_card_replaced_only_when_gone_from_the_queue(paths: Paths) -> None:
    server = FakeServer()

    async def scenario() -> None:
        daemon, handler = make(server, paths, retry_base_s=3600)
        await daemon.refetch()
        server.answer_errors = [BackendError("offline")]
        await grade(daemon, "good")  # a answered, POST pending
        await asyncio.sleep(0.02)
        assert handler.pending_count() == 1
        # The server still lists a (POST not landed): it must not come back.
        await daemon.refetch(check_current=True, emit=False)
        assert ids(daemon) == ["b", "c"]
        assert daemon.session.notice is None
        # Timer refetch with a new order: the card on screen stays.
        server.snap = snapshot("c", "b", "d")
        await daemon.refetch(emit=False)
        assert ids(daemon)[0] == "b" and daemon.session.notice is None
        # sync.changed and b is still there: no swap.
        await daemon.refetch(check_current=True, emit=False)
        assert ids(daemon)[0] == "b" and daemon.session.notice is None
        # sync.changed and b is gone (reviewed elsewhere): replaced with a notice.
        server.snap = snapshot("c", "d")
        await daemon.refetch(check_current=True, emit=False)
        assert ids(daemon) == ["c", "d"]
        assert daemon.session.notice == "reviewed elsewhere"
        handler.close()

    asyncio.run(scenario())


def test_stale_snapshot_from_before_the_post_landed_does_not_resurrect_the_card(
    paths: Paths,
) -> None:
    server = FakeServer()
    handler = ReviewHandler(Journal(paths.journal), utc_offset_min=lambda: 0)
    daemon = Daemon(server, paths, clock=lambda: T0, handler=handler, timer_seconds=3600)
    stale = snapshot("a", "b", "c")

    async def scenario() -> None:
        await daemon.refetch()
        gate = threading.Event()
        original = server.post_answer

        def slow_post(entry: AnswerEntry) -> None:
            gate.wait(2)
            original(entry)

        server.post_answer = slow_post  # type: ignore[method-assign]
        await grade(daemon, "good")
        before = handler.pending_cards()
        assert before == {"a": "answer"}
        # A refetch that started while a was pending must keep filtering it even if the POST
        # lands before the snapshot is applied.
        daemon.session.apply_queue(stale, check_current=False, pending=before)
        assert ids(daemon) == ["b", "c"]
        gate.set()
        await handler.idle()
        assert ids(daemon) == ["b", "c"]

    asyncio.run(scenario())


# ---- undo ---------------------------------------------------------------------


def test_undo_targets_the_last_answered_id(paths: Paths) -> None:
    server = FakeServer()

    async def scenario() -> None:
        daemon, handler = make(server, paths)
        await daemon.refetch()
        await grade(daemon, "good")
        await daemon.handle(Action("reveal"))
        await grade(daemon, "again")
        await handler.idle()
        last_id = server.answers[-1].id
        assert server.answers[-1].card_id == "b"
        out = st(await daemon.handle(Action("undo")))
        assert out["card"]["id"] == "b" and out["card"]["revealed"] is False
        assert out["last"] == {"rating": "again", "undoable": False}
        await handler.idle()
        assert server.undos == [last_id]
        again = st(await daemon.handle(Action("undo")))
        assert again["notice"] == "nothing to undo" and server.undos == [last_id]

    asyncio.run(scenario())


def test_undo_is_journaled_and_sent_after_its_pending_answer(
    paths: Paths, monkeypatch: pytest.MonkeyPatch
) -> None:
    server = FakeServer()

    async def scenario() -> None:
        daemon, handler = make(server, paths, retry_base_s=3600)
        await daemon.refetch()
        server.answer_errors = [BackendError("offline")]
        await grade(daemon, "good")
        await asyncio.sleep(0.02)
        out = st(await daemon.handle(Action("undo")))
        assert out["card"]["id"] == "a"
        kinds = [type(e).__name__ for e in Journal(paths.journal).entries]
        assert kinds == ["AnswerEntry", "UndoEntry"]
        assert out["pending"] == 2
        # The undone card has an unsent undo: a sync.changed refetch must not replace it.
        await daemon.refetch(check_current=True, emit=False)
        assert ids(daemon)[0] == "a" and daemon.session.notice is None
        handler.close()

    asyncio.run(scenario())

    async def replay() -> None:
        daemon, handler = make(server, paths)
        await daemon.refetch()
        await handler.idle()
        assert handler.pending_count() == 0

    asyncio.run(replay())
    answer_id = server.answers[-1].id
    assert server.undos == [answer_id]
    assert list(server.logged) == [answer_id]


def test_undo_of_a_review_the_server_never_saw_is_dropped_with_a_notice(paths: Paths) -> None:
    server = FakeServer()
    server.undo_errors = [BackendError("terminal", "HTTP 404")]

    async def scenario() -> None:
        daemon, handler = make(server, paths)
        await daemon.refetch()
        await grade(daemon, "good")
        await handler.idle()
        await daemon.handle(Action("undo"))
        await handler.idle()
        assert handler.pending_count() == 0 and len(server.undos) == 1

    asyncio.run(scenario())


def test_reviewed_at_is_utc_ms_and_offset_is_clamped(paths: Paths) -> None:
    from datetime import timezone

    from kotobaya_cli.daemon.review import iso_ms

    jst = timezone(timedelta(hours=9))
    assert iso_ms(datetime(2026, 11, 16, 3, 4, 5, 678900, tzinfo=jst)) == (
        "2026-11-15T18:04:05.678Z"
    )
    server = FakeServer()

    async def scenario() -> None:
        handler = ReviewHandler(Journal(paths.journal), utc_offset_min=lambda: 5000)
        daemon = Daemon(server, paths, clock=lambda: T0, handler=handler, timer_seconds=3600)
        await daemon.refetch()
        await grade(daemon, "good")
        await handler.idle()
        assert server.answers[0].utc_offset_min == 1080

    asyncio.run(scenario())


# ---- attempt 2: classification, failed journal writes, acked-card filtering ---------------


@pytest.mark.parametrize(
    ("status", "body", "kind"),
    [
        (409, {"code": "card_not_reviewable", "title": "Conflict"}, "terminal"),
        (410, {"code": "gone", "title": "Gone"}, "terminal"),
        (422, {"code": "validation_failed", "title": "Invalid"}, "terminal"),
        (404, {"code": "not_found", "title": "Not Found"}, "terminal"),
        (404, "404 page not found", "rejected"),  # ingress/proxy during a deploy
        (404, None, "rejected"),
        (421, None, "rejected"),
        (400, {"code": "invalid_request"}, "rejected"),
        (401, {"code": "unauthenticated"}, "auth"),
        (403, {"code": "insufficient_scope"}, "forbidden"),
        (429, None, "offline"),
        (503, {"code": "unavailable"}, "offline"),
    ],
)
def test_review_post_classification_drops_only_409_410_422_and_server_404(
    fake_http: FakeHttp, status: int, body: Any, kind: str
) -> None:
    backend = HttpBackend(Settings(base_url="https://kotobaya.test/api/v1", token="kbp_x"))
    fake_http.add("POST", "/api/v1/review/answer", status, body)
    fake_http.add("POST", "/api/v1/review/undo", status, body)
    entry = AnswerEntry(
        "01931f41-0000-7000-8000-000000000f01",
        "01931f40-1a2b-7c3d-8e4f-000000000c01",
        3,
        "good",
        "2026-11-15T09:00:00.000Z",
        100,
        540,
    )
    with pytest.raises(BackendError) as answered:
        backend.post_answer(entry)
    assert answered.value.kind == kind
    with pytest.raises(BackendError) as undone:
        backend.post_undo(entry.id)
    assert undone.value.kind == kind
    raw = fake_http.calls[0].body
    sent = json.loads(raw) if isinstance(raw, (str, bytes)) else raw
    assert sent["id"] == entry.id and sent["button"] == "good"


def test_unexpected_4xx_keeps_the_entry_and_retries_with_one_notice(paths: Paths) -> None:
    server = FakeServer()
    server.answer_errors = [BackendError("rejected", "HTTP 404")] * 3
    notices: list[object] = []

    async def scenario() -> None:
        daemon, handler = make(server, paths)
        await daemon.refetch()
        original = daemon.emit

        def spy() -> dict[str, object]:
            state = original()
            notices.append(state["notice"])
            return state

        daemon.emit = spy  # type: ignore[method-assign]
        await grade(daemon, "good")
        await handler.idle()
        assert handler.pending_count() == 0
        assert len(server.answers) == 4 and len({a.id for a in server.answers}) == 1
        assert len(server.logged) == 1

    asyncio.run(scenario())
    assert [n for n in notices if n] == ["server rejected the review (HTTP 404), retrying"]


def test_forbidden_is_sticky_and_keeps_the_journal(paths: Paths) -> None:
    server = FakeServer()
    server.answer_errors = [BackendError("forbidden", "HTTP 403")]

    async def scenario() -> None:
        daemon, handler = make(server, paths)
        await daemon.refetch()
        await grade(daemon, "good")
        await handler.idle()
        assert handler.pending_count() == 1 and daemon.session.conn == "ok"
        out = st(await grade(daemon, "good"))
        assert out["notice"] == FORBIDDEN_NOTICE and out["card"]["id"] == "b"
        daemon.session.conn = "offline"
        await daemon.refetch()  # coming back online does not resend while forbidden
        await handler.idle()
        assert len(server.answers) == 1 and handler.pending_count() == 1

    asyncio.run(scenario())

    async def restarted() -> None:  # token fixed, daemon restarted: replayed with the same id
        daemon, handler = make(server, paths)
        await daemon.refetch()
        await handler.idle()
        assert handler.pending_count() == 0

    asyncio.run(restarted())
    assert len({a.id for a in server.answers}) == 1 and len(server.logged) == 1


def test_terminal_drop_of_last_answer_makes_it_not_undoable_and_drops_its_undo(
    paths: Paths,
) -> None:
    server = FakeServer()

    async def scenario() -> None:
        daemon, handler = make(server, paths, retry_base_s=3600)
        await daemon.refetch()
        server.answer_errors = [BackendError("offline")]
        await grade(daemon, "good")
        await asyncio.sleep(0.02)
        handler.close()  # stop the sender sleeping in backoff
        await daemon.handle(Action("undo"))  # queued behind the unsent answer
        server.answer_errors = [BackendError("terminal", "HTTP 410")]
        await handler.idle()
        assert server.undos == []
        assert Journal(paths.journal).entries == []
        last = daemon.session.last
        assert last is not None and last.undoable is False

    asyncio.run(scenario())

    async def drop_last() -> None:
        daemon, handler = make(server, paths)
        server.snap = snapshot("x", "y")
        await daemon.refetch()
        server.answer_errors = [BackendError("terminal", "HTTP 409")]
        await grade(daemon, "good")
        await handler.idle()
        last = daemon.session.last
        assert last is not None and last.undoable is False
        out = st(await daemon.handle(Action("undo")))
        assert out["notice"] == "nothing to undo" and out["card"]["id"] == "y"

    asyncio.run(drop_last())


def test_failed_journal_write_leaves_no_entry_and_no_second_id(
    paths: Paths, monkeypatch: pytest.MonkeyPatch
) -> None:
    server = FakeServer()
    real_replace = os.replace
    full = [True]

    def replace(src: Any, dst: Any) -> None:
        if full[0]:
            raise OSError(28, "No space left on device")
        real_replace(src, dst)

    monkeypatch.setattr(os, "replace", replace)

    async def scenario() -> None:
        daemon, handler = make(server, paths)
        await daemon.refetch()
        for _ in range(2):  # pressing again while the disk is full (the worker turns the
            with pytest.raises(OSError):  # exception into an "internal error" notice)
                await grade(daemon, "good")
            assert ids(daemon) == ["a", "b", "c"] and daemon.session.last is None
            assert handler.pending_count() == 0
        assert not paths.journal.exists()
        assert not paths.journal.with_name("journal.tmp").exists()
        full[0] = False  # space frees up
        await grade(daemon, "good")
        await handler.idle()
        assert len(server.logged) == 1 and len(server.answers) == 1

    asyncio.run(scenario())


def test_failed_journal_remove_keeps_the_entry_in_memory(
    paths: Paths, monkeypatch: pytest.MonkeyPatch
) -> None:
    j = Journal(paths.journal)
    a = AnswerEntry("01", "a", 3, "good", "2026-11-15T09:00:00.000Z", 0, 0)
    j.append(a)

    def fail(src: Any, dst: Any) -> None:
        raise OSError(28, "No space left on device")

    monkeypatch.setattr(os, "replace", fail)
    with pytest.raises(OSError):
        j.remove(a)
    assert j.entries == [a]


def test_snapshot_fetched_before_the_answer_does_not_reshow_an_acked_card(paths: Paths) -> None:
    server = FakeServer()
    applied: list[list[str]] = []

    async def scenario() -> None:
        daemon, handler = make(server, paths)
        await daemon.refetch()
        original_apply = daemon.session.apply_queue

        def spy_apply(snap: QueueSnapshot, **kw: Any) -> None:
            original_apply(snap, **kw)
            applied.append(ids(daemon))

        daemon.session.apply_queue = spy_apply  # type: ignore[method-assign,assignment]
        gate = threading.Event()
        stale = snapshot("a", "b", "c")
        fresh_fetch = server.fetch_queue

        def slow_stale_fetch(keep_going: bool = False) -> QueueSnapshot:
            server.fetch_queue = fresh_fetch  # type: ignore[method-assign]
            gate.wait(2)
            return stale

        server.fetch_queue = slow_stale_fetch  # type: ignore[method-assign]
        fetch = asyncio.create_task(daemon.refetch())  # starts before the answer
        await asyncio.sleep(0.02)
        await grade(daemon, "good")
        for _ in range(100):  # the POST is acked while the stale fetch is in flight
            if handler.pending_count() == 0:
                break
            await asyncio.sleep(0.01)
        assert handler.pending_count() == 0
        gate.set()
        await fetch
        await handler.idle()
        assert applied and all("a" not in q for q in applied)
        assert ids(daemon) == ["b", "c"]
        assert handler.pending_cards() == {}  # forgotten once a later fetch was applied

    asyncio.run(scenario())


# ---- keep going (C-26/C-27) ---------------------------------------------------


class KeepGoingServer(FakeServer):
    """Normal queue: one card, then empty with more_available. Keep going: batches of 3 unanswered
    cards out of 10; once, a response with no item but entries still to materialize."""

    def __init__(self) -> None:
        super().__init__()
        self.all = [f"c{i}" for i in range(10)]
        self.flags: list[bool] = []
        self.hiccup = True

    def fetch_queue(self, keep_going: bool = False) -> QueueSnapshot:
        with self.lock:
            self.flags.append(keep_going)
            done = {a.card_id for a in self.answers}
            left = [i for i in self.all if i not in done]
            counts = Counts(len(left), 0, 0)
            if not keep_going:
                items = left[:1] if not done else []
                return QueueSnapshot(
                    [CardEntry(i, "meaning") for i in items],
                    counts,
                    DAY_END,
                    more_available=not items and bool(left),
                    study_day="2026-11-15",
                )
            if len(done) == 4 and self.hiccup:
                self.hiccup = False
                return QueueSnapshot([], counts, DAY_END, study_day="2026-11-15", to_materialize=3)
            return QueueSnapshot(
                [CardEntry(i, "meaning") for i in left[:3]], counts, DAY_END, study_day="2026-11-15"
            )


def test_keep_going_streams_every_card_once_and_ends_on_an_empty_keep_going_fetch(
    paths: Paths,
) -> None:
    server = KeepGoingServer()

    async def scenario() -> None:
        daemon, handler = make(server, paths)
        await daemon.refetch()
        await grade(daemon, "good")
        await handler.idle()
        out = st(daemon.session.state())
        assert out["card"] is None and out["more_available"] is True and out["keep_going"] is False
        out = st(await daemon.handle(Action("keep_going")))
        assert out["keep_going"] is True and out["loading"] is True  # fetch runs in the background
        await daemon.idle()
        out = st(daemon.session.state())
        assert out["loading"] is False and out["card"] is not None
        shown = ["c0"]
        for _ in range(20):
            card = daemon.session.current
            if card is None:
                break
            shown.append(card.card_id)
            await grade(daemon, "good")
            await handler.idle()
        assert shown == server.all  # every card once, in order, through the materialize hiccup
        out = st(daemon.session.state())
        assert out["card"] is None and out["keep_going"] is True and out["more_available"] is False
        assert all(
            server.flags[server.flags.index(True) :]
        )  # every fetch after the action keeps going

    asyncio.run(scenario())


def test_keep_going_ends_when_the_study_day_changes(paths: Paths) -> None:
    server = KeepGoingServer()

    async def scenario() -> None:
        daemon, _handler = make(server, paths)
        await daemon.handle(Action("keep_going"))
        await daemon.idle()
        assert daemon.session.keep_going_day == "2026-11-15"
        original = server.fetch_queue

        def next_day(keep_going: bool = False) -> QueueSnapshot:
            snap = original(keep_going)
            return QueueSnapshot(snap.items, snap.counts, DAY_END, study_day="2026-11-16")

        server.fetch_queue = next_day  # type: ignore[method-assign]
        await daemon.refetch()
        assert daemon.session.keep_going is False
        assert server.flags[-1] is False

    asyncio.run(scenario())


def test_keep_going_is_a_fifo_and_socket_action() -> None:
    assert parse_fifo_line("keep_going\n") == Action("keep_going")
    assert parse_socket_request('{"cmd": "keep_going"}') == Action("keep_going")


def test_queue_fetch_timeout_keeps_answers_working_and_schedules_a_refetch(
    paths: Paths, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr("kotobaya_cli.daemon.server.RETRY_BACKOFF_S", (0.05,))
    server = FakeServer()

    async def scenario() -> None:
        daemon, handler = make(server, paths)
        await daemon.refetch()
        assert daemon.session.conn == "ok"
        shown = daemon.session.current
        assert shown is not None
        real = server.fetch_queue
        calls: list[int] = []

        def slow(keep_going: bool = False) -> QueueSnapshot:
            calls.append(1)
            if len(calls) == 1:
                raise BackendError("degraded", "read timeout")
            return real(keep_going)

        server.fetch_queue = slow  # type: ignore[method-assign]
        await daemon.refetch()
        assert daemon.session.conn == "ok"  # a slow fetch is not offline
        await daemon.handle(Action("reveal"))
        await grade(daemon, "good")  # still accepted and POSTed
        await handler.idle()
        assert [a.card_id for a in server.answers] == [shown.card_id]
        for _ in range(100):  # the scheduled retry refetches
            if len(calls) >= 2:
                break
            await asyncio.sleep(0.02)
        assert len(calls) >= 2
        await daemon.idle()

    asyncio.run(scenario())


def test_keep_going_does_not_block_a_concurrent_reveal(paths: Paths) -> None:
    server = FakeServer()

    async def scenario() -> None:
        daemon, _handler = make(server, paths)
        await daemon.refetch()
        release = threading.Event()
        real = server.fetch_queue

        def blocked(keep_going: bool = False) -> QueueSnapshot:
            release.wait(5)
            return real(keep_going)

        server.fetch_queue = blocked  # type: ignore[method-assign]
        worker = asyncio.create_task(daemon._worker())
        try:
            await asyncio.wait_for(daemon.submit(Action("keep_going")), 1)
            out = await asyncio.wait_for(daemon.submit(Action("reveal")), 1)
            assert out["loading"] is True and out["card"]["revealed"] is True  # type: ignore[index]
        finally:
            release.set()
            await daemon.idle()
            worker.cancel()

    asyncio.run(scenario())
