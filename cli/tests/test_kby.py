"""`cli/bin/kby` against a fake `kotobaya watch` and a temp runtime dir (never the real daemon)."""

from __future__ import annotations

import json
import os
import queue
import shutil
import signal
import subprocess
import tempfile
import threading
import time
from collections.abc import Iterator
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import pytest

HERE = Path(__file__).parent
KBY = HERE.parent / "bin" / "kby"
ORIG = HERE / "fixtures" / "kby.orig"
FAKE = HERE / "fixtures" / "fake_kotobaya.sh"
STALE = "Ignored: screen changed"

pytestmark = pytest.mark.skipif(shutil.which("jq") is None, reason="kby needs jq")


def raw(
    *,
    revealed: bool | None,
    conn: str = "ok",
    counts: dict[str, int] | None = None,
    card_id: str = "card-1",
    last: dict[str, Any] | None = None,
    notice: str | None = None,
    leak: bool = False,
) -> str:
    """A daemon state line. `revealed=None`: no card. `leak`: an old daemon publishing answers."""
    card: dict[str, Any] | None = None
    if revealed is not None:
        card = {"id": card_id, "kind": "vocab", "front": "猫", "revealed": revealed}
        if revealed or leak:
            card.update({"back": "cat", "reading": "ねこ", "sentence": "猫がいる"})
        card["previews"] = {"again": "1m", "hard": "6m", "good": "1d", "easy": "4d"}
    state = {
        "v": 1,
        "conn": conn,
        "counts": counts if counts is not None else {"new": 3, "learning": 2, "review": 7},
        "day_ends_at": "2026-11-16T00:00:00Z",
        "card": card,
        "last": last,
        "pending": 0,
        "notice": notice,
    }
    return json.dumps(state, ensure_ascii=False, separators=(",", ":"))


FRONT = raw(revealed=False, leak=True)
BACK = raw(revealed=True, last={"rating": "good", "undoable": True})
DONE = raw(revealed=None, counts={"new": 0, "learning": 0, "review": 0})
OFFLINE = raw(revealed=False, conn="offline")
AUTH = raw(revealed=None, conn="auth")
LOADING = raw(revealed=None, counts=None).replace(
    '"counts":{"new":3,"learning":2,"review":7}', '"counts":null'
)


@dataclass
class Env:
    root: Path
    states: Path
    runtime: Path
    config: Path
    procs: list[subprocess.Popen[str]] = field(default_factory=list)

    def env(self, **extra: str) -> dict[str, str]:
        return {
            "PATH": os.environ["PATH"],
            "HOME": str(self.root),
            "XDG_RUNTIME_DIR": str(self.runtime),
            "XDG_CONFIG_HOME": str(self.config),
            "KBY_CLI": str(FAKE),
            "FAKE_STATES": str(self.states),
            "FAKE_DIR": str(self.root),
            **extra,
        }

    @property
    def state_json(self) -> Path:
        return self.runtime / "kotobaya" / "state.json"

    def append(self, *lines: str) -> None:
        with self.states.open("a", encoding="utf-8") as f:
            for line in lines:
                f.write(line + "\n")

    def run(self, script: Path, *args: str, **extra: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [str(script), *args],
            env=self.env(**extra),
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )

    def serve(self) -> Serve:
        s = Serve(self)
        self.procs.append(s.proc)
        return s


class Serve:
    def __init__(self, e: Env) -> None:
        self.proc = subprocess.Popen(
            [str(KBY), "serve"],
            env=e.env(),
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            encoding="utf-8",
        )
        self.lines: queue.Queue[str] = queue.Queue()
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self) -> None:
        assert self.proc.stdout is not None
        for line in self.proc.stdout:
            self.lines.put(line)

    def next(self, timeout: float = 8) -> dict[str, Any]:
        line = self.lines.get(timeout=timeout)
        assert line.endswith("\n") and line.count("\n") == 1
        out = json.loads(line)
        assert isinstance(out, dict)
        return out

    def until(self, state: str) -> dict[str, Any]:
        for _ in range(20):
            line = self.next()
            if line["state"] == state:
                return line
        raise AssertionError(f"no {state} line")

    def stop(self) -> int:
        self.proc.send_signal(signal.SIGTERM)
        return self.proc.wait(timeout=10)


@pytest.fixture
def kenv() -> Iterator[Env]:
    root = Path(tempfile.mkdtemp(prefix="kby-"))
    e = Env(root, root / "states", root / "run", root / "config")
    e.states.touch()
    (e.runtime / "kotobaya").mkdir(parents=True)
    yield e
    for p in e.procs:
        if p.poll() is None:
            p.kill()
            p.wait()
    shutil.rmtree(root, ignore_errors=True)


class Fifo:
    """Plays the daemon's input FIFO so a test can see what `kby do` sent."""

    def __init__(self, e: Env) -> None:
        path = e.runtime / "kotobaya" / "in"
        os.mkfifo(path)
        self.fd = os.open(path, os.O_RDWR | os.O_NONBLOCK)

    def read(self) -> list[str]:
        try:
            data = os.read(self.fd, 65536)
        except BlockingIOError:
            return []
        return data.decode().split()

    def close(self) -> None:
        os.close(self.fd)


def test_startup_is_a_loading_line_and_every_line_is_v1_with_a_new_rev(kenv: Env) -> None:
    s = kenv.serve()
    first = s.next()
    assert first["v"] == 1 and first["state"] == "loading"
    assert first["card"] is None and first["error"] is None and first["notice"] is None
    assert first["actions"]["refresh"] == {"enabled": True}
    assert first["actions"]["reveal"] == {"enabled": False}
    kenv.append(FRONT, BACK, DONE, LOADING, AUTH, OFFLINE)
    lines = [first] + [s.next() for _ in range(6)]
    assert [x["state"] for x in lines] == [
        "loading", "front", "back", "done", "loading", "auth", "offline",
    ]  # fmt: skip
    assert all(x["v"] == 1 for x in lines)
    assert len({x["rev"] for x in lines}) == len(lines)
    assert lines[1]["rev"].startswith("card-1:") and lines[3]["rev"].startswith("-:")
    assert all(isinstance(x["counts"]["new"], int) for x in lines)
    assert lines[5]["error"] and lines[6]["error"]
    assert s.stop() == 0


def test_front_lines_omit_answer_fields_and_back_lines_carry_them(kenv: Env) -> None:
    s = kenv.serve()
    s.next()
    kenv.append(FRONT, BACK)  # FRONT is from an old daemon that leaks answers while hidden
    front, back = s.next(), s.next()
    assert front["card"] == {"id": "card-1", "kind": "vocab", "front": "猫"}
    assert back["card"] == {
        "id": "card-1", "kind": "vocab", "front": "猫",
        "reading": "ねこ", "sentence": "猫がいる", "back": "cat",
    }  # fmt: skip
    assert front["text"] == "猫 12"
    en = {k: v["enabled"] for k, v in front["actions"].items()}
    assert en == {
        "reveal": True, "again": False, "hard": False, "good": False, "easy": False,
        "undo": False, "skip": True, "refresh": True, "next": True,
    }  # fmt: skip
    assert front["actions"]["good"] == {"enabled": False, "hint": "1d"}
    en = {k: v["enabled"] for k, v in back["actions"].items()}
    assert en["reveal"] is False and en["good"] is True and en["undo"] is True
    assert back["last"] == {"rating": "good", "undoable": True}
    assert s.stop() == 0


def test_stale_rev_is_dropped_and_the_next_line_carries_the_notice(kenv: Env) -> None:
    fifo = Fifo(kenv)
    s = kenv.serve()
    s.next()
    kenv.append(BACK)
    back = s.next()
    assert json.loads(kenv.state_json.read_text()) == back

    done = kenv.run(KBY, "do", "good", "--rev", "card-1:stale")
    assert done.returncode == 0 and done.stdout == ""
    assert "good" not in fifo.read()  # only the nudge reached the daemon
    kenv.append(BACK)  # the daemon answers the nudge with a state line
    again = s.next()
    assert again["notice"] == STALE
    kenv.append(BACK)
    assert s.next()["notice"] is None  # one-shot

    cur = json.loads(kenv.state_json.read_text())["rev"]
    ok = kenv.run(KBY, "do", "good", "--rev", cur)
    assert ok.returncode == 0 and ok.stdout == ""
    assert fifo.read() == ["good"]
    fifo.close()
    assert s.stop() == 0


def test_do_refuses_unknown_and_disabled_actions_with_exit_2(kenv: Env) -> None:
    fifo = Fifo(kenv)
    s = kenv.serve()
    s.next()
    kenv.append(FRONT)
    s.next()
    for action in ("good", "again", "bogus"):
        r = kenv.run(KBY, "do", action)
        assert r.returncode == 2 and r.stdout == "" and r.stderr.strip()
    assert fifo.read() == []
    assert kenv.run(KBY, "do", "reveal").returncode == 0
    assert fifo.read() == ["reveal"]
    kenv.append(BACK)
    s.next()
    assert kenv.run(KBY, "do", "reveal").returncode == 2
    assert kenv.run(KBY, "do", "undo").returncode == 0  # BACK is undoable
    assert fifo.read() == ["undo"]
    fifo.close()
    assert s.stop() == 0


def test_daemon_down_gives_offline_line_and_recovery(kenv: Env) -> None:
    s = kenv.serve()
    s.next()
    kenv.append(FRONT, OFFLINE, FRONT)
    assert [s.next()["state"] for _ in range(3)] == ["front", "offline", "front"]
    assert s.stop() == 0


def test_watch_dying_gives_error_line_and_the_stream_recovers(kenv: Env) -> None:
    (kenv.root / "die-once").touch()
    kenv.append(FRONT)
    s = kenv.serve()
    assert s.next()["state"] == "loading"
    err = s.next()
    assert err["state"] == "error" and "boom" in err["error"]
    assert err["actions"]["refresh"]["enabled"] is True and err["card"] is None
    assert s.next(timeout=10)["state"] == "front"  # restarted after the 1 s backoff
    assert s.stop() == 0


def test_sigterm_stops_the_watch_child_and_removes_the_cache(kenv: Env) -> None:
    s = kenv.serve()
    s.next()
    kenv.append(FRONT)
    s.next()
    assert kenv.state_json.exists()
    assert (
        subprocess.run(
            ["pgrep", "-f", str(kenv.states)], capture_output=True, check=False
        ).returncode
        == 0
    )
    assert s.stop() == 0
    time.sleep(0.2)
    assert (
        subprocess.run(
            ["pgrep", "-f", str(kenv.states)], capture_output=True, check=False
        ).returncode
        == 1
    )
    assert not kenv.state_json.exists()


def test_flags_still_send_to_the_fifo_unchecked(kenv: Env) -> None:
    fifo = Fifo(kenv)
    assert kenv.run(KBY, "-1", "-r", "-u").returncode == 0
    assert fifo.read() == ["again", "reveal", "undo"]
    fifo.close()


@pytest.mark.parametrize(
    "args", [(), ("-f",), ("-f", "mine"), ("-f", "mine", "--text"), ("stream", "--raw")]
)
def test_follow_output_is_byte_identical_to_the_imported_original(
    kenv: Env, args: tuple[str, ...]
) -> None:
    widgets = kenv.config / "kotobaya" / "widgets"
    widgets.mkdir(parents=True)
    (widgets / "mine.conf").write_text(
        "text.front = {front:3} {new}\ntext.back = {back} 【{reading}】\ntooltip = {notice}\n"
    )
    kenv.append(
        LOADING, FRONT, BACK, DONE, OFFLINE, AUTH,
        raw(revealed=True, notice="reviewed elsewhere"),
    )  # fmt: skip
    old = kenv.run(ORIG, *args, FAKE_ONESHOT="1")
    new = kenv.run(KBY, *args, FAKE_ONESHOT="1")
    assert old.returncode == new.returncode
    assert old.stdout == new.stdout and old.stdout.count("\n") >= 7
