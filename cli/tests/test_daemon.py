"""Daemon: FIFO parsing never crashes, every emitted line is a full valid state."""

from __future__ import annotations

import asyncio
import json
import os
import shutil
import tempfile
import threading
from collections.abc import Callable, Iterator
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest
from conftest import make_loop
from jsonschema import Draft202012Validator  # type: ignore[import-untyped]

from kotobaya_cli.daemon.backend import BackendError, EventStream, iter_sse_events
from kotobaya_cli.daemon.journal import AnswerEntry
from kotobaya_cli.daemon.protocol import (
    Action,
    AnswerCmd,
    Invalid,
    parse_fifo_line,
    parse_socket_request,
    resolve_button,
)
from kotobaya_cli.daemon.server import Daemon, Paths
from kotobaya_cli.daemon.session import CardEntry, Counts, QueueSnapshot, Session

SCHEMA = json.loads((Path(__file__).parents[1] / "schema" / "state.v1.json").read_text())
VALIDATOR = Draft202012Validator(SCHEMA)
PREVIEWS = {"again": "10m", "hard": "1d", "good": "3d", "easy": "8d"}
DAY_END = "2026-11-15T19:00:00.000Z"


def check_state(state: Any) -> dict[str, Any]:
    VALIDATOR.validate(state)
    assert isinstance(state, dict)
    return state


def snapshot(*ids: str, counts: Counts | None = None) -> QueueSnapshot:
    items = [CardEntry(i, "recognition", dict(PREVIEWS)) for i in ids]
    return QueueSnapshot(items, counts or Counts(8, 3, 41), DAY_END)


class _Stream:
    def __init__(self) -> None:
        self.closed = threading.Event()

    def __iter__(self) -> Iterator[str]:
        self.closed.wait()
        return iter(())

    def close(self) -> None:
        self.closed.set()


class FakeBackend:
    def __init__(self) -> None:
        self.snap = snapshot("a", "b", "c")
        self.error: BackendError | None = None
        self.buttons = 4

    def fetch_queue(self, keep_going: bool = False) -> QueueSnapshot:
        if self.error:
            raise self.error
        return self.snap

    def fetch_grading_buttons(self) -> int:
        if self.error:
            raise self.error
        return self.buttons

    def open_events(self) -> EventStream:
        if self.error:
            raise self.error
        return _Stream()

    def post_answer(self, entry: AnswerEntry) -> None:
        raise BackendError("offline")

    def post_undo(self, review_id: str) -> None:
        raise BackendError("offline")


@pytest.fixture
def paths() -> Iterator[Paths]:
    root = Path(tempfile.mkdtemp(prefix="kbd"))  # short path: unix sockets cap at ~108 bytes
    yield Paths(root / "in", root / "sock", root / "journal")
    shutil.rmtree(root, ignore_errors=True)


# ---- protocol ---------------------------------------------------------------


def test_fifo_parsing_known_unknown_and_blank() -> None:
    assert parse_fifo_line("good\n") == AnswerCmd("good")
    assert parse_fifo_line("  REVEAL ") == Action("reveal")
    assert parse_fifo_line("") is None
    assert parse_fifo_line("   ") is None
    for junk in ("nope", "good 1", "\x00\x01", "x" * 5000, '{"cmd":1}', "�"):
        cmd = parse_fifo_line(junk)
        assert isinstance(cmd, Invalid)
        assert cmd.notice
        assert len(cmd.notice) < 80


def test_socket_request_parsing_never_raises() -> None:
    junk = (
        "",
        "nope",
        "[]",
        "null",
        '{"cmd":3}',
        '{"cmd":"answer"}',
        '{"cmd":"answer","rating":9}',
    )
    for line in junk:
        assert isinstance(parse_socket_request(line), Invalid)
    assert parse_socket_request('{"cmd":"answer","rating":"Good"}') == AnswerCmd("good")
    assert parse_socket_request('{"cmd":"next"}') == Action("skip")


def test_button_mapping_always_sends_a_button_valid_for_the_mode() -> None:
    assert resolve_button("good", 4) == (3, "good")
    assert resolve_button("okay", 4) == (3, "good")
    assert resolve_button("something", 4) == (1, "again")
    assert resolve_button("good", 5) == (3, "okay")
    assert resolve_button("again", 5) == (1, "nothing")
    assert resolve_button("something", 5) == (1, "something")
    assert resolve_button("easy", 5) == (4, "easy")


def test_sse_parser_yields_event_types_across_chunk_boundaries() -> None:
    raw = b': ping\n\nid: 1\nevent: hello\ndata: {"type":"hello"}\n\nid: 2\nevent: sync.changed\n'
    chunks = [raw[:7], raw[7:30], raw[30:], b'data: {"type":"sync.changed"}\n\n']
    assert list(iter_sse_events(chunks)) == ["hello", "sync.changed"]


# ---- session ----------------------------------------------------------------


def test_refetch_never_swaps_card_on_screen_unless_gone_on_sync_changed() -> None:
    s = Session()
    s.apply_queue(snapshot("a", "b"), check_current=False)
    assert s.current is not None and s.current.card_id == "a"
    s.apply_queue(snapshot("b", "c"), check_current=False)  # timer refetch: a stays
    assert [c.card_id for c in s.queue] == ["a", "b", "c"]
    assert s.notice is None
    s.apply_queue(snapshot("b", "c"), check_current=True)  # sync.changed: a is gone
    assert s.current is not None and s.current.card_id == "b"
    assert s.notice == "reviewed elsewhere"
    assert not s.revealed


def test_skip_moves_current_behind_prefetched() -> None:
    s = Session()
    s.apply_queue(snapshot("a", "b", "c"), check_current=False)
    s.reveal()
    assert s.skip()
    assert [c.card_id for c in s.queue] == ["b", "c", "a"]
    assert not s.revealed
    only = Session()
    only.apply_queue(snapshot("a"), check_current=False)
    assert not only.skip()


def test_duration_is_capped_at_60s() -> None:
    now = [datetime(2026, 11, 15, tzinfo=UTC)]
    s = Session(lambda: now[0])
    s.apply_queue(snapshot("a"), check_current=False)
    now[0] += timedelta(seconds=5)
    assert s.duration_ms() == 5000
    s.reveal()
    now[0] += timedelta(seconds=3)
    assert s.duration_ms() == 3000  # measured from the last widget-visible action
    now[0] += timedelta(minutes=10)
    assert s.duration_ms() == 60_000


def test_empty_day_has_null_card_and_text_fields_are_optional() -> None:
    s = Session()
    s.conn = "ok"
    s.apply_queue(snapshot(counts=Counts(0, 0, 0)), check_current=False)
    state = check_state(s.state())
    assert state["card"] is None and state["counts"] == {"new": 0, "learning": 0, "review": 0}
    entry = CardEntry("a", "vocab", None, {"front": "猫", "back": "cat", "reading": "ねこ"})
    s.apply_queue(QueueSnapshot([entry], Counts(1, 0, 0), DAY_END), check_current=False)
    hidden = check_state(s.state())["card"]
    assert hidden["front"] == "猫"
    assert not {"back", "reading", "sentence"} & hidden.keys()  # A2: answer-side stays hidden
    s.reveal()
    card = check_state(s.state())["card"]
    assert card["front"] == "猫" and card["back"] == "cat" and card["reading"] == "ねこ"
    assert "sentence" not in card


# ---- daemon: full state on every line --------------------------------------


async def _read_state(reader: asyncio.StreamReader) -> dict[str, Any]:
    line = await asyncio.wait_for(reader.readline(), 3)
    assert line.endswith(b"\n")
    return check_state(json.loads(line))


async def _until(
    reader: asyncio.StreamReader, pred: Callable[[dict[str, Any]], bool]
) -> dict[str, Any]:
    for _ in range(50):
        state = await _read_state(reader)
        if pred(state):
            return state
    raise AssertionError("expected state never arrived")


def test_every_emitted_line_is_a_full_valid_state_and_junk_never_crashes(paths: Paths) -> None:
    backend = FakeBackend()

    async def scenario() -> None:
        daemon = Daemon(backend, paths, timer_seconds=3600)
        task = asyncio.create_task(daemon.run(install_signals=False))
        for _ in range(100):
            if paths.sock.exists():
                break
            await asyncio.sleep(0.02)
        reader, writer = await asyncio.open_unix_connection(str(paths.sock))
        writer.write(b'{"cmd":"subscribe"}\n')
        await writer.drain()
        first = await _until(reader, lambda s: s["card"] is not None)
        assert first["conn"] == "ok"
        assert first["counts"] == {"new": 8, "learning": 3, "review": 41}
        assert first["card"] == {
            "id": "a",
            "kind": "recognition",
            "revealed": False,
            "previews": PREVIEWS,
        }

        fd = os.open(paths.fifo, os.O_RDWR | os.O_NONBLOCK)
        try:
            os.write(fd, b"reveal\n")
            await _until(reader, lambda s: s["card"]["revealed"])
            os.write(fd, b"bogus\n")
            noted = await _until(reader, lambda s: s["notice"] is not None)
            assert noted["notice"].startswith("unknown command")
            os.write(fd, b"\xff\xfe\x00\n")
            await _until(reader, lambda s: s["notice"] is not None)
            os.write(fd, b"x" * 9000 + b"\n")
            await _until(reader, lambda s: s["notice"] == "input line too long")
            os.write(fd, b"go")  # partial line, completed by the next write
            os.write(fd, b"od\n")
            await _until(reader, lambda s: s["notice"] == "answering is not available yet")
            os.write(fd, b"skip\n")
            moved = await _until(reader, lambda s: s["card"]["id"] == "b")
            assert not moved["card"]["revealed"]
        finally:
            os.close(fd)

        # request/reply socket: a second client gets a full state back for every request
        r2, w2 = await asyncio.open_unix_connection(str(paths.sock))
        reply: dict[str, Any] = {}
        for request in (b'{"cmd":"status"}\n', b"garbage\n", b'{"cmd":"reveal"}\n'):
            w2.write(request)
            await w2.drain()
            reply = await _read_state(r2)
            assert reply["v"] == 1
        assert reply["card"]["revealed"] is True
        w2.close()
        writer.close()

        daemon.stop()
        await asyncio.wait_for(task, 3)

    asyncio.run(scenario())


def test_offline_and_auth_states_are_valid_and_serve_no_new_card(paths: Paths) -> None:
    backend = FakeBackend()
    backend.error = BackendError("offline")

    async def scenario() -> None:
        daemon = Daemon(backend, paths)
        await daemon.refetch()
        state = check_state(daemon.session.state())
        assert state["conn"] == "offline" and state["card"] is None and state["counts"] is None
        backend.error = BackendError("auth")
        await daemon.refetch()
        assert check_state(daemon.session.state())["conn"] == "auth"
        backend.error = None
        await daemon.refetch()
        state = check_state(daemon.session.state())
        assert state["conn"] == "ok" and state["card"]["id"] == "a"
        backend.error = BackendError("offline")
        await daemon.refetch()
        out = await daemon.handle(Action("skip"))  # offline: skip refused, the card stays
        assert out["conn"] == "offline" and out["notice"] == "offline"
        assert check_state(out)["card"]["id"] == "a"

    asyncio.run(scenario())


# ---- ADR-033: waiting for a learning card, no learn-ahead --------------------


def _waiting(due: str | None, counts: Counts | None = None) -> QueueSnapshot:
    return QueueSnapshot([], counts or Counts(0, 1, 0), DAY_END, next_learning_due_at=due)


def test_empty_queue_with_next_due_is_waiting_and_counts_pass_through() -> None:
    s = Session()
    s.conn = "ok"
    s.apply_queue(_waiting("2026-11-15T10:05:00.000Z", Counts(5, 2, 9)), check_current=False)
    state = check_state(s.state())
    assert state["card"] is None and state["next_due_at"] == "2026-11-15T10:05:00.000Z"
    assert state["counts"] == {"new": 5, "learning": 2, "review": 9}  # the server's, unchanged
    s.apply_queue(_waiting(None), check_current=False)
    assert check_state(s.state())["next_due_at"] is None  # absent: done as before
    s.apply_queue(snapshot("a"), check_current=False)
    assert check_state(s.state())["next_due_at"] is None


def test_timer_wait_is_min_of_timer_day_end_and_next_due(paths: Paths) -> None:
    now = datetime(2026, 11, 15, 10, 0, 0, tzinfo=UTC)
    daemon = Daemon(FakeBackend(), paths, clock=lambda: now, timer_seconds=3600)
    due = (now + timedelta(seconds=90)).isoformat()
    daemon.session.apply_queue(_waiting(due), check_current=False)
    assert daemon._timer_wait() == pytest.approx(91.0)  # due + 1 s slack
    daemon.session.apply_queue(_waiting(None), check_current=False)
    assert daemon._timer_wait() == 3600  # day end is 9 h away
    past = (now - timedelta(seconds=5)).isoformat()
    daemon.session.apply_queue(_waiting(past), check_current=False)
    assert daemon._timer_wait() == pytest.approx(1.0)
    daemon.session.apply_queue(snapshot("a"), check_current=False)
    assert daemon._timer_wait() == 3600  # a card is shown: next due is irrelevant


def test_refetch_at_next_due_returns_the_card(
    paths: Paths, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr("kotobaya_cli.daemon.server.NEXT_DUE_SLACK_S", 0.05)
    backend = FakeBackend()
    due = (datetime.now(UTC) + timedelta(seconds=0.4)).isoformat()
    backend.snap = _waiting(due)
    fetches: list[int] = []
    original = backend.fetch_queue

    def fetch(keep_going: bool = False) -> QueueSnapshot:
        fetches.append(1)
        return original(keep_going)

    backend.fetch_queue = fetch  # type: ignore[method-assign]

    async def scenario() -> None:
        daemon = Daemon(backend, paths, timer_seconds=3600)
        task = asyncio.create_task(daemon.run(install_signals=False))
        for _ in range(100):
            if paths.sock.exists():
                break
            await asyncio.sleep(0.02)
        reader, writer = await asyncio.open_unix_connection(str(paths.sock))
        writer.write(b'{"cmd":"subscribe"}\n')
        await writer.drain()
        waiting = await _until(reader, lambda s: s["next_due_at"] is not None)
        assert waiting["card"] is None and waiting["next_due_at"] == due
        count = len(fetches)
        backend.snap = snapshot("late")  # the server now has the card
        got = await _until(reader, lambda s: s["card"] is not None)
        assert got["card"]["id"] == "late" and got["next_due_at"] is None
        assert len(fetches) > count  # fetched by the due-time timer (timer_seconds is 1 h)
        writer.close()
        daemon.stop()
        await asyncio.wait_for(task, 3)

    asyncio.run(scenario())


def test_waiting_re_emits_once_per_changed_second_with_a_decreasing_countdown(
    paths: Paths,
) -> None:
    from kotobaya_cli.watch import _fields

    clock = [datetime(2026, 11, 15, 10, 0, 0, tzinfo=UTC)]
    daemon = Daemon(FakeBackend(), paths, clock=lambda: clock[0], timer_seconds=3600)
    daemon.session.conn = "ok"
    due = (clock[0] + timedelta(seconds=600)).isoformat()
    daemon.session.apply_queue(_waiting(due), check_current=False)
    sub = _Sub()
    daemon._subscribers.add(sub)  # type: ignore[arg-type]
    assert daemon.tick() is True
    assert daemon.tick() is False  # same second: no extra line
    clock[0] += timedelta(seconds=1)
    assert daemon.tick() is True
    first, second = (json.loads(x) for x in sub.lines)
    assert first["next_due_at"] == second["next_due_at"] == due
    from kotobaya_cli import watch

    a = watch.next_due_in(first["next_due_at"], clock[0] - timedelta(seconds=1))
    b = watch.next_due_in(second["next_due_at"], clock[0])
    assert (a, b) == ("10:00", "9:59")
    assert _fields(second)["next_due_in"] != ""
    daemon.session.apply_queue(snapshot("a"), check_current=False)
    clock[0] += timedelta(seconds=1)
    assert daemon.tick() is False  # a card is shown: nothing emitted


def test_card_state_carries_kind_label_from_note_type() -> None:
    types = ["vocab", "kanji", "phrase", "grammar", "sentence", "custom", None]
    labels = []
    for i, note_type in enumerate(types):
        s = Session()
        s.conn = "ok"
        entry = CardEntry(f"c{i}", "meaning", None, None, note_type)
        s.apply_queue(QueueSnapshot([entry], Counts(1, 0, 0), DAY_END), check_current=False)
        labels.append(check_state(s.state())["card"].get("kind_label"))
    assert labels == ["Vocab", "Kanji", "Phrase", "Grammar", "Sentence", "Custom", None]


class _Sub:
    def __init__(self) -> None:
        self.lines: list[str] = []

    def put(self, line: str) -> None:
        self.lines.append(line)


# ---- ADR-037: the review loop --------------------------------------------------


def _loop_snapshot(
    phase: str, *ids: str, study_day: str = "2026-11-15", **loop: Any
) -> QueueSnapshot:
    items = [CardEntry(i, "recognition", dict(PREVIEWS)) for i in ids]
    return QueueSnapshot(
        items,
        Counts(40, 26, 9),  # the deprecated counts: never what the widget shows
        DAY_END,
        study_day=study_day,
        loop=make_loop(phase, **loop),
    )


def test_status_per_phase(paths: Paths) -> None:
    due = "2026-11-15T10:05:00.000Z"
    cases: list[tuple[QueueSnapshot, dict[str, Any]]] = [
        (
            _loop_snapshot("reviewing", "a", ready_review=9, ready_total=9, later_count=26),
            {"phase": "reviewing", "ready": 9, "later_today": 26, "can_keep_going": False},
        ),
        (
            _loop_snapshot("goal_reached", backlog_reviews=335, new_today=40),
            {"phase": "goal_reached", "backlog": 335, "can_keep_going": True, "next_due_at": None},
        ),
        (
            _loop_snapshot(
                "done_for_today",
                reviews_limit=200,
                reviews_today=200,
                backlog_reviews=335,
                backlog_beyond_limit=135,
            ),
            {
                "phase": "done_for_today",
                "backlog_beyond_limit": 135,
                "reviews_limit": 200,
                "can_keep_going": True,
            },
        ),
        (
            _loop_snapshot("waiting", later_count=3, next_due_at=due),
            {"phase": "waiting", "next_due_at": due, "can_keep_going": False},
        ),
        (
            _loop_snapshot("empty"),
            {"phase": "empty", "next_due_at": None, "can_keep_going": False},
        ),
    ]

    async def scenario() -> None:
        backend = FakeBackend()
        for snap, expected in cases:
            daemon = Daemon(backend, paths)
            backend.snap = snap
            await daemon.refetch()
            state = check_state(daemon.session.state())
            assert state["conn"] == "ok"
            for key, value in expected.items():
                assert state[key] == value, (expected["phase"], key)

    asyncio.run(scenario())


def test_waiting_timer_only_in_phase_waiting(paths: Paths) -> None:
    now = datetime(2026, 11, 15, 10, 0, 0, tzinfo=UTC)
    daemon = Daemon(FakeBackend(), paths, clock=lambda: now, timer_seconds=3600)
    due = (now + timedelta(seconds=90)).isoformat()
    daemon.session.apply_queue(
        _loop_snapshot("goal_reached", later_count=2, next_due_at=due), check_current=False
    )
    assert daemon._timer_wait() == 3600  # C4: a later hint is not a timer
    daemon.session.apply_queue(
        _loop_snapshot("waiting", later_count=2, next_due_at=due), check_current=False
    )
    assert daemon._timer_wait() == pytest.approx(91.0)


def test_server_without_loop_is_server_too_old(paths: Paths) -> None:
    backend = FakeBackend()
    backend.error = BackendError("too_old", "no loop")

    async def scenario() -> None:
        daemon = Daemon(backend, paths)
        sub = _Sub()
        daemon._subscribers.add(sub)  # type: ignore[arg-type]
        await daemon.refetch()
        state = check_state(daemon.session.state())
        assert state["conn"] == "server_too_old" and state["card"] is None
        assert state["counts"] is None and state["phase"] is None
        assert "server too old" in json.loads(sub.lines[-1])["notice"]

    asyncio.run(scenario())


def _recording(backend: FakeBackend) -> list[bool]:
    flags: list[bool] = []
    original = backend.fetch_queue

    def fetch(keep_going: bool = False) -> QueueSnapshot:
        flags.append(keep_going)
        return original(keep_going)

    backend.fetch_queue = fetch  # type: ignore[method-assign]
    return flags


def test_keep_going_resets_on_new_session(paths: Paths) -> None:
    """ADR-039 / CLI-KG-SCOPE: keep going lives in memory for one daemon run. A restart (a new
    review session) starts with the normal limits and nothing is written to disk."""
    backend = FakeBackend()
    flags = _recording(backend)
    backend.snap = _loop_snapshot("goal_reached")

    async def scenario() -> None:
        first = Daemon(backend, paths)
        await first.refetch()
        await first.handle(Action("keep_going"))
        await first.idle()
        assert first.session.keep_going is True
        assert first.session.keep_going_day == "2026-11-15"
        assert not paths.journal.parent.joinpath("keep-going-day").exists()
        flags.clear()
        second = Daemon(backend, paths)  # a restart
        assert second.session.keep_going is False and second.session.keep_going_day is None
        await second.refetch()
        assert flags == [False]  # the first fetch after a restart uses the normal limits
        assert second.session.keep_going is False

    asyncio.run(scenario())


def test_keep_going_dropped_on_new_day(paths: Paths) -> None:
    backend = FakeBackend()
    flags = _recording(backend)
    backend.snap = _loop_snapshot("goal_reached", study_day="2026-11-14")

    async def scenario() -> None:
        daemon = Daemon(backend, paths)
        await daemon.refetch()
        await daemon.handle(Action("keep_going"))
        await daemon.idle()
        assert daemon.session.keep_going_day == "2026-11-14"
        backend.snap = _loop_snapshot("reviewing", "a", study_day="2026-11-15")
        flags.clear()
        await daemon.refetch()
        assert flags == [True, False]  # the mismatching response is discarded, refetched normally
        assert daemon.session.keep_going is False and daemon.session.keep_going_day is None
        assert check_state(daemon.session.state())["keep_going"] is False

    asyncio.run(scenario())


class _AckBackend(FakeBackend):
    """post_answer succeeds; the queue it serves is whatever the test sets."""

    def post_answer(self, entry: AnswerEntry) -> None:
        return None


def test_due_card_served_before_cached_new(paths: Paths) -> None:
    """C1: once now >= T the cached cards are not served; the due learning card comes first."""
    from kotobaya_cli.daemon.journal import Journal
    from kotobaya_cli.daemon.review import ReviewHandler

    t0 = datetime(2026, 11, 15, 10, 0, 0, tzinfo=UTC)
    clock = [t0]
    due = (t0 + timedelta(seconds=60)).strftime("%Y-%m-%dT%H:%M:%S.000Z")
    backend = _AckBackend()
    backend.snap = _loop_snapshot(
        "reviewing", "r1", "new1", "new2", later_count=1, next_due_at=due, ready_total=1
    )

    async def scenario() -> None:
        handler = ReviewHandler(Journal(paths.journal), utc_offset_min=lambda: 0)
        daemon = Daemon(backend, paths, clock=lambda: clock[0], handler=handler, timer_seconds=3600)
        await daemon.refetch()
        assert daemon.session.due_floor == datetime.fromisoformat(due)
        # Before T the cache is served as it is.
        await daemon.handle(Action("reveal"))
        await daemon.handle(AnswerCmd("good"))
        await handler.idle()
        assert daemon.session.current is not None and daemon.session.current.card_id == "new1"
        # The learning card came due while "new1" was on screen. Answering it must not show
        # the cached "new2": the refetch puts the due card first.
        backend.snap = _loop_snapshot("reviewing", "late", "new2", ready_learning=1, ready_total=1)
        clock[0] = t0 + timedelta(seconds=61)
        await daemon.handle(Action("reveal"))
        await daemon.handle(AnswerCmd("good"))
        await daemon.idle()
        await handler.idle()
        assert daemon.session.current is not None
        assert [c.card_id for c in daemon.session.queue] == ["late", "new2"]

    asyncio.run(scenario())


def test_http_backend_maps_loop_and_rejects_a_server_without_one(fake_http: Any) -> None:
    from kotobaya_cli.api import Settings
    from kotobaya_cli.daemon.backend import HttpBackend

    body: dict[str, Any] = {
        "items": [],
        "counts": {"new": 40, "learning": 26, "review": 9},
        "study_day": "2026-11-15",
        "day_ends_at": DAY_END,
        "generated_at": "2026-11-15T09:00:00.000Z",
    }
    backend = HttpBackend(Settings("https://kotobaya.test/api/v1", "kbp_x"))
    fake_http.add("GET", "/api/v1/review/queue", 200, body)
    with pytest.raises(BackendError) as err:
        backend.fetch_queue()
    assert err.value.kind == "too_old"
    body = dict(body)
    body["loop"] = {
        "phase": "waiting",
        "keep_going": False,
        "ready": {"learning": 0, "review": 0, "total": 0},
        "later_today": {"count": 26, "next_due_at": "2026-11-15T09:06:00.000Z"},
        "backlog": {"reviews": 0, "beyond_limit": 0},
        "reviews": {"today": 0, "limit": None},
        "new": {"today": 12, "goal": 40, "available": False},
    }
    fake_http.routes.clear()
    fake_http.add("GET", "/api/v1/review/queue", 200, body)
    loop = backend.fetch_queue().loop
    assert loop is not None
    assert (loop.phase, loop.ready_total, loop.later_count, loop.next_due_at) == (
        "waiting",
        0,
        26,
        "2026-11-15T09:06:00.000Z",
    )
    assert loop.reviews_limit is None and loop.new_goal == 40
