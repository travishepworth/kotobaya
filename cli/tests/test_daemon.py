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
