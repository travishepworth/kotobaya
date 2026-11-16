"""The daemon process: FIFO and socket in, SSE + timers in the background, state lines out."""

from __future__ import annotations

import asyncio
import contextlib
import json
import os
import signal
import stat
import threading
from collections.abc import Awaitable, Callable, Mapping
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Protocol

from kotobaya_cli.api import CliError
from kotobaya_cli.daemon.backend import Backend, BackendError, EventStream, classify
from kotobaya_cli.daemon.journal import Journal
from kotobaya_cli.daemon.protocol import (
    MAX_LINE_BYTES,
    Action,
    AnswerCmd,
    Command,
    Invalid,
    StatusCmd,
    SubscribeCmd,
    parse_fifo_line,
    parse_socket_request,
)
from kotobaya_cli.daemon.review import ReviewHandler
from kotobaya_cli.daemon.session import Clock, QueueSnapshot, Session, utc_now
from kotobaya_cli.watch import next_due_in

TIMER_SECONDS = 60.0
MAX_BACKOFF_S = 30.0
RETRY_BACKOFF_S = (2.0, 5.0, 10.0, 30.0)  # refetch delays after a failed queue fetch
NEXT_DUE_SLACK_S = 1.0  # ADR-033: refetch this long after a learning card's due instant
KEEP_GOING_RETRIES = (
    3  # C-27: refetches of a keep-going response with only entries left to materialize
)


TOO_OLD_NOTICE = "server too old: this CLI needs a Kotobaya server with the review loop (ADR-037)"
C1_POST_WAIT_S = 3.0  # C1: how long a refetch waits for the answer POST before it goes anyway


@dataclass(frozen=True)
class Paths:
    fifo: Path
    sock: Path
    journal: Path  # CLI-REVIEW writes here; the daemon only knows where it lives

    @property
    def keep_going_file(self) -> Path:
        """C2: the study day Keep going was started on (daemon state dir, next to the journal)."""
        return self.journal.parent / "keep-going-day"

    @staticmethod
    def from_env(env: Mapping[str, str]) -> Paths:
        runtime = env.get("XDG_RUNTIME_DIR") or f"/tmp/kotobaya-{os.getuid()}"
        state = env.get("XDG_STATE_HOME") or str(
            Path(env.get("HOME", "~")).expanduser() / ".local/state"
        )
        return Paths(
            fifo=Path(runtime) / "kotobaya" / "in",
            sock=Path(runtime) / "kotobaya" / "sock",
            journal=Path(state) / "kotobaya" / "journal",
        )


class AnswerHandler(Protocol):
    """Seam for CLI-REVIEW: journal-first answers, undo and replay of unsent entries."""

    async def answer(self, daemon: Daemon, button: str) -> None: ...

    async def undo(self, daemon: Daemon) -> None: ...

    async def on_online(self, daemon: Daemon) -> None: ...

    def pending_count(self) -> int: ...

    def pending_cards(self) -> dict[str, str]:
        """Card id -> op ("answer" / "undo") of its latest unsent journal entry."""
        ...

    def begin_fetch(self) -> int:
        """Token taken before a queue fetch; passed to end_fetch once its snapshot is applied."""
        ...

    def end_fetch(self, token: int) -> None: ...

    def close(self) -> None:
        """Cancel background sends; the journal keeps whatever is unsent."""
        ...

    async def idle(self) -> None:
        """Return once nothing more can be sent right now."""
        ...


class NoAnswers:
    """Default until CLI-REVIEW: answering is not available, with a notice."""

    async def answer(self, daemon: Daemon, button: str) -> None:
        daemon.session.set_notice("answering is not available yet")

    async def undo(self, daemon: Daemon) -> None:
        daemon.session.set_notice("undo is not available yet")

    async def on_online(self, daemon: Daemon) -> None:
        return None

    def pending_count(self) -> int:
        return 0

    def pending_cards(self) -> dict[str, str]:
        return {}

    def begin_fetch(self) -> int:
        return 0

    def end_fetch(self, token: int) -> None:
        return None

    def close(self) -> None:
        return None

    async def idle(self) -> None:
        return None


def dumps(state: dict[str, object]) -> str:
    return json.dumps(state, ensure_ascii=False, separators=(",", ":"))


class Subscriber:
    """Bounded mailbox that drops the oldest line: every line is a full state, so a reader
    that falls behind only misses intermediate states."""

    def __init__(self) -> None:
        self.queue: asyncio.Queue[str] = asyncio.Queue(maxsize=64)

    def put(self, line: str) -> None:
        if self.queue.full():
            with contextlib.suppress(asyncio.QueueEmpty):
                self.queue.get_nowait()
        self.queue.put_nowait(line)


class Daemon:
    def __init__(
        self,
        backend: Backend,
        paths: Paths,
        *,
        clock: Clock = utc_now,
        handler: AnswerHandler | None = None,
        timer_seconds: float = TIMER_SECONDS,
    ) -> None:
        self.backend = backend
        self.paths = paths
        self.session = Session(clock)
        self.handler: AnswerHandler = handler or NoAnswers()
        self._clock = clock
        self._timer_seconds = timer_seconds
        self._subscribers: set[Subscriber] = set()
        self._inbox: asyncio.Queue[tuple[Command, asyncio.Future[dict[str, object]] | None]] = (
            asyncio.Queue()
        )
        self._fetching = False
        self._dirty = False
        self._want_check = False
        self._settings_stale = True
        self._retry_task: asyncio.Task[None] | None = None
        self._failures = 0
        self._stale_due = 0  # fetches in a row that returned no card although next_due was past
        self._tasks: set[asyncio.Task[None]] = set()
        self._stop = asyncio.Event()
        self._timer_wake = asyncio.Event()
        self._last_tick: str | None = None
        self._stop_thread = threading.Event()
        self._stream: EventStream | None = None
        self._fifo_buf = b""
        self._writers: set[asyncio.StreamWriter] = set()
        self._restore_keep_going()

    # ---- keep going, persisted per study day (C2) ---------------------------------

    def _restore_keep_going(self) -> None:
        try:
            day = self.paths.keep_going_file.read_text(encoding="utf-8").strip()
        except OSError:
            return
        if day:
            self.session.keep_going = True
            self.session.keep_going_day = day

    def _save_keep_going(self) -> None:
        """Persist (or, with keep going off, delete) the day file; a failure is never fatal."""
        file = self.paths.keep_going_file
        try:
            if self.session.keep_going and self.session.keep_going_day:
                file.parent.mkdir(parents=True, exist_ok=True)
                tmp = file.with_name(file.name + ".tmp")
                tmp.write_text(self.session.keep_going_day + "\n", encoding="utf-8")
                tmp.replace(file)
            else:
                file.unlink(missing_ok=True)
        except OSError:
            self.session.set_notice("keep going state not saved")

    # ---- output -------------------------------------------------------------

    def emit(self) -> dict[str, object]:
        """Broadcast the full state as one NDJSON line and return it."""
        self.session.pending = self.handler.pending_count()
        state = self.session.take_state()
        line = dumps(state)
        for sub in self._subscribers:
            sub.put(line)
        return state

    # ---- commands -----------------------------------------------------------

    async def submit(self, cmd: Command) -> dict[str, object]:
        """Queue a command for the single worker and wait for the resulting state."""
        future: asyncio.Future[dict[str, object]] = asyncio.get_running_loop().create_future()
        await self._inbox.put((cmd, future))
        return await future

    def submit_nowait(self, cmd: Command) -> None:
        self._inbox.put_nowait((cmd, None))

    async def handle(self, cmd: Command) -> dict[str, object]:
        session = self.session
        if isinstance(cmd, Invalid):
            session.set_notice(cmd.notice)
        elif isinstance(cmd, AnswerCmd):
            if session.current is not None and not session.revealed:
                session.set_notice("reveal the card before grading")  # nothing else changes
            else:
                await self.handler.answer(self, cmd.button)
        elif isinstance(cmd, Action):
            if cmd.name == "reveal":
                session.reveal()
            elif cmd.name == "skip":
                if session.conn != "ok":
                    session.set_notice("offline")
                elif not session.skip():
                    session.set_notice("nothing to skip to")
            elif cmd.name == "undo":
                await self.handler.undo(self)
            elif cmd.name == "refresh":
                self._spawn_refetch()
            elif cmd.name == "keep_going":
                # C-26/C-27: same semantics as the web: past today's limits until a keep-going fetch is empty.
                session.keep_going = True
                session.keep_going_day = session.study_day  # None: adopted from the next response
                self._save_keep_going()
                self._spawn_refetch()
            elif cmd.name == "stop_keep_going":
                # Back to the normal limits (the web leaves keep going by leaving the screen).
                session.keep_going = False
                session.keep_going_day = None
                self._save_keep_going()
                self._spawn_refetch()
        return self.emit()

    def serve_due_first(self) -> bool:
        """C1: called after an answer advanced the queue. Once now >= T the cached cards are held
        back and the queue is fetched again (after the answer's POST), so a card that came due is
        the very next one. True when it did."""
        session = self.session
        if not session.queue or not session.due_passed():
            return False
        session.drop_queue()
        session.loading = True
        task = asyncio.create_task(self._refetch_after_post())
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return True

    async def _refetch_after_post(self) -> None:
        with contextlib.suppress(TimeoutError):
            await asyncio.wait_for(self.handler.idle(), C1_POST_WAIT_S)
        await self.refetch()

    def _spawn_refetch(self) -> None:
        """Refetch in the background so the command worker stays free for reveal and answers."""
        self.session.loading = True
        task = asyncio.create_task(self.refetch())
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def idle(self) -> None:
        """Wait for background refetches started by commands (tests, shutdown)."""
        while self._tasks:
            await asyncio.wait(set(self._tasks))

    async def _worker(self) -> None:
        while True:
            cmd, future = await self._inbox.get()
            try:
                state = await self.handle(cmd)
            except Exception as exc:  # a bad command must never kill the daemon  # noqa: BLE001
                self.session.set_notice(f"internal error: {type(exc).__name__}")
                state = self.emit()
            if future is not None and not future.done():
                future.set_result(state)

    # ---- refetch ------------------------------------------------------------

    async def refetch(self, *, check_current: bool = False, emit: bool = True) -> None:
        """Refetch the queue. Coalesces overlapping requests; never swaps the card on screen
        unless `check_current` is set and the server dropped it."""
        self._want_check = self._want_check or check_current
        if self._fetching:
            self._dirty = True
            return
        self._fetching = True
        try:
            while True:
                self._dirty = False
                check, self._want_check = self._want_check, False
                await self._fetch_once(check)
                if not self._dirty:
                    break
        finally:
            self._fetching = False
            self.session.loading = False
        if emit:
            self.emit()

    async def _fetch_once(self, check_current: bool) -> None:
        was_ok = self.session.conn == "ok"
        # A snapshot may predate a POST that lands while it is in flight: also filter what was
        # pending when the fetch started.
        token = self.handler.begin_fetch()
        pending = self.handler.pending_cards()
        try:
            if self._settings_stale:
                self.session.grading_buttons = await asyncio.to_thread(
                    self.backend.fetch_grading_buttons
                )
                self._settings_stale = False
            snapshot = await self._fetch_snapshot()
        except Exception as exc:  # noqa: BLE001
            self._settings_stale = True
            kind = classify(exc).kind
            if kind == "too_old":
                self._server_too_old()
                return
            if kind == "auth":
                self.session.conn = "auth"
            elif kind != "degraded" or self.session.conn == "auth":
                self.session.conn = "offline"
            # degraded (slow or failing server): keep conn as it was, so answers still POST
            # (journaled, idempotent) and the shown queue stays usable; just retry soon.
            self._schedule_retry()
            return
        self._failures = 0
        self.session.conn = "ok"
        self.session.more_available = snapshot.more_available
        pending.update(self.handler.pending_cards())
        self.session.apply_queue(snapshot, check_current=check_current, pending=pending)
        self.handler.end_fetch(token)
        self._stale_due = (
            self._stale_due + 1
            if not snapshot.items
            and (
                due := self._seconds_until(
                    snapshot.loop.next_due_at if snapshot.loop else snapshot.next_learning_due_at
                )
            )
            is not None
            and due <= 0
            else 0
        )
        self._timer_wake.set()
        if not was_ok:
            await self.handler.on_online(self)

    def _server_too_old(self) -> None:
        """No `loop` in the response: one clear status, no cards, no fallback derivation."""
        session = self.session
        session.conn = "server_too_old"
        session.queue = []
        session.counts = None
        session.loop = None
        session.set_notice(TOO_OLD_NOTICE)
        self._timer_wake.set()  # the periodic timer retries; an upgraded server is picked up

    def _schedule_retry(self) -> None:
        """Refetch after a failed fetch: 2 s, 5 s, 10 s, then every 30 s, until one succeeds."""
        delay = RETRY_BACKOFF_S[min(self._failures, len(RETRY_BACKOFF_S) - 1)]
        self._failures += 1
        if self._retry_task is not None and not self._retry_task.done():
            return

        async def retry() -> None:
            await asyncio.sleep(delay)
            await self.refetch()

        self._retry_task = asyncio.create_task(retry())

    async def _fetch_snapshot(self) -> QueueSnapshot:
        """One queue fetch, with keep going when it is on (C-26/C-27).

        Keep going is dropped when the study day differs from the one it started in (then the normal
        queue is fetched). A keep-going response with no item but deck entries the server still has
        to turn into cards (`to_materialize` > 0) is fetched again, so keep going ends only on a
        truly empty keep-going fetch."""
        session = self.session
        if not session.keep_going:
            return await asyncio.to_thread(self.backend.fetch_queue)
        snapshot = await asyncio.to_thread(self.backend.fetch_queue, True)
        if session.keep_going_day is None:
            session.keep_going_day = snapshot.study_day
            self._save_keep_going()
        elif snapshot.study_day != session.keep_going_day:
            session.keep_going = False  # C2: the stored day is not today: drop it, fetch normally
            session.keep_going_day = None
            self._save_keep_going()
            return await asyncio.to_thread(self.backend.fetch_queue)
        for _ in range(KEEP_GOING_RETRIES):
            if snapshot.items or snapshot.to_materialize <= 0:
                break
            snapshot = await asyncio.to_thread(self.backend.fetch_queue, True)
        return snapshot

    def _seconds_until(self, instant: str | None) -> float | None:
        if instant is None:
            return None
        try:
            return (datetime.fromisoformat(instant) - self._clock()).total_seconds()
        except ValueError:
            return None

    def _timer_wait(self) -> float:
        """Seconds to the next periodic refetch: min of the timer, the day end and, while the
        queue is empty, the next learning card's due instant (+ slack; ADR-033)."""
        wait = self._timer_seconds
        day_end = self._seconds_until(self.session.day_ends_at)
        if day_end is not None:
            wait = min(wait, max(1.0, day_end))
        if self.session.current is None:
            due = self._seconds_until(self.session.timer_due)  # C4: waiting only
            if due is not None:
                # A due instant already past with still no card (clock skew) backs off.
                slack = NEXT_DUE_SLACK_S * 2 ** min(self._stale_due, 5)
                wait = min(wait, max(0.0, due) + slack)
        return wait

    def tick(self) -> bool:
        """While waiting (ADR-033), re-emit the state when the m:ss countdown text changed, so a
        widget rendering `next_due_in` counts down. Emits nothing in any other state."""
        session = self.session
        if session.conn != "ok" or session.current is not None or session.timer_due is None:
            self._last_tick = None
            return False
        text = next_due_in(session.timer_due, self._clock())
        if text == self._last_tick:
            return False
        self._last_tick = text
        self.emit()
        return True

    async def _ticker(self) -> None:
        while True:
            await asyncio.sleep(1.0)
            self.tick()

    async def _timer(self) -> None:
        while True:
            self._timer_wake.clear()
            try:
                # A fetch that changes the schedule wakes this to recompute the wait.
                await asyncio.wait_for(self._timer_wake.wait(), self._timer_wait())
            except TimeoutError:
                await self.refetch()

    # ---- SSE (thread) -------------------------------------------------------

    def _sse_worker(self, loop: asyncio.AbstractEventLoop) -> None:
        backoff = 1.0
        while not self._stop_thread.is_set():
            try:
                stream = self.backend.open_events()
            except Exception as exc:  # noqa: BLE001
                kind = classify(exc).kind
                asyncio.run_coroutine_threadsafe(self._stream_down(kind), loop)
            else:
                self._stream = stream
                backoff = 1.0
                asyncio.run_coroutine_threadsafe(self.refetch(check_current=True), loop)
                try:
                    with contextlib.suppress(Exception):  # a broken stream means reconnect
                        for event in stream:
                            if event == "sync.changed":
                                asyncio.run_coroutine_threadsafe(
                                    self.refetch(check_current=True), loop
                                )
                finally:
                    self._stream = None
                    with contextlib.suppress(Exception):
                        stream.close()
                if not self._stop_thread.is_set():
                    asyncio.run_coroutine_threadsafe(self._stream_down("offline"), loop)
            if self._stop_thread.wait(backoff):
                return
            backoff = min(backoff * 2, MAX_BACKOFF_S)

    async def _stream_down(self, kind: str) -> None:
        conn = "auth" if kind == "auth" else "offline"
        if self.session.conn != conn:
            self.session.conn = conn
            self._settings_stale = True
            self.emit()

    # ---- FIFO ---------------------------------------------------------------

    def _open_fifo(self) -> int:
        path = self.paths.fifo
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        try:
            if not stat.S_ISFIFO(path.lstat().st_mode):
                # A writer using `1<>` on a missing path leaves a regular file behind.
                path.unlink()
        except FileNotFoundError:
            pass
        if not path.exists():
            os.mkfifo(path, 0o600)
        return os.open(path, os.O_RDWR | os.O_NONBLOCK)

    def feed_fifo(self, data: bytes) -> None:
        """Consume raw FIFO bytes: complete lines become commands, junk becomes a notice."""
        self._fifo_buf += data
        while b"\n" in self._fifo_buf:
            raw, self._fifo_buf = self._fifo_buf.split(b"\n", 1)
            self._fifo_line(raw)
        if len(self._fifo_buf) > MAX_LINE_BYTES:
            self._fifo_buf = b""
            self.submit_nowait(Invalid("input line too long"))

    def _fifo_line(self, raw: bytes) -> None:
        if len(raw) > MAX_LINE_BYTES:
            self.submit_nowait(Invalid("input line too long"))
            return
        cmd = parse_fifo_line(raw.decode("utf-8", errors="replace"))
        if cmd is not None:
            self.submit_nowait(cmd)

    def _on_fifo_readable(self, fd: int) -> None:
        try:
            data = os.read(fd, 65536)
        except (BlockingIOError, InterruptedError):
            return
        except OSError:
            return
        self.feed_fifo(data)

    # ---- socket -------------------------------------------------------------

    async def _client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        async def send_line(line: str) -> None:
            writer.write(line.encode() + b"\n")
            await writer.drain()

        async def send(state: dict[str, object]) -> None:
            await send_line(dumps(state))

        self._writers.add(writer)
        try:
            while True:
                try:
                    raw = await reader.readline()
                except (ValueError, asyncio.LimitOverrunError):
                    self.session.set_notice("invalid request: too long")
                    await send(self.session.state())
                    break
                if not raw:
                    break
                cmd = parse_socket_request(raw.decode("utf-8", errors="replace"))
                if isinstance(cmd, StatusCmd):
                    await send(self.session.state())
                elif isinstance(cmd, SubscribeCmd):
                    await self._subscribe(reader, send_line)
                    break
                else:
                    await send(await self.submit(cmd))
        except (ConnectionError, OSError):
            pass
        finally:
            self._writers.discard(writer)
            writer.close()
            with contextlib.suppress(Exception):
                await writer.wait_closed()

    async def _subscribe(
        self, reader: asyncio.StreamReader, send_line: Callable[[str], Awaitable[None]]
    ) -> None:
        """Stream state lines until the client disconnects (EOF) or the daemon stops."""
        sub = Subscriber()
        self._subscribers.add(sub)
        eof = asyncio.create_task(reader.read(1))
        try:
            sub.put(dumps(self.session.state()))
            while True:
                nxt = asyncio.create_task(sub.queue.get())
                await asyncio.wait({nxt, eof}, return_when=asyncio.FIRST_COMPLETED)
                if nxt.done():
                    await send_line(nxt.result())
                else:
                    nxt.cancel()
                if eof.done():
                    return
        finally:
            eof.cancel()
            self._subscribers.discard(sub)

    # ---- lifecycle ----------------------------------------------------------

    def stop(self) -> None:
        self._stop_thread.set()
        stream = self._stream
        if stream is not None:
            with contextlib.suppress(Exception):
                stream.close()
        self._stop.set()

    async def run(self, *, install_signals: bool = True) -> None:
        loop = asyncio.get_running_loop()
        fd = self._open_fifo()
        loop.add_reader(fd, self._on_fifo_readable, fd)
        self.paths.sock.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        with contextlib.suppress(FileNotFoundError):
            self.paths.sock.unlink()
        server = await asyncio.start_unix_server(self._client, path=str(self.paths.sock))
        self.paths.sock.chmod(0o600)
        if install_signals:
            for sig in (signal.SIGINT, signal.SIGTERM):
                loop.add_signal_handler(sig, self.stop)
        worker = asyncio.create_task(self._worker())
        thread = threading.Thread(target=self._sse_worker, args=(loop,), daemon=True)
        thread.start()
        initial = asyncio.create_task(self.refetch())
        timer = asyncio.create_task(self._timer())
        ticker = asyncio.create_task(self._ticker())
        try:
            await self._stop.wait()
        finally:
            self._stop_thread.set()
            self.handler.close()
            for task in (worker, timer, ticker, initial, *self._tasks, self._retry_task):
                if task is None:
                    continue
                task.cancel()
            loop.remove_reader(fd)
            os.close(fd)
            server.close()
            for writer in list(self._writers):
                writer.close()
            await server.wait_closed()
            with contextlib.suppress(FileNotFoundError):
                self.paths.sock.unlink()


def run_daemon(backend: Backend, paths: Paths) -> int:
    """Blocking entry point for `kotobaya daemon`."""
    try:
        handler = ReviewHandler(Journal(paths.journal))
        asyncio.run(Daemon(backend, paths, handler=handler).run())
    except OSError as exc:
        raise CliError(f"daemon cannot start: {exc}") from exc
    except KeyboardInterrupt:
        return 130
    return 0


__all__ = ["Backend", "BackendError", "Daemon", "Paths", "run_daemon"]
