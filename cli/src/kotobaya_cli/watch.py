"""`kotobaya watch`: relay the daemon's state stream to desktop widgets.

Subscribes to the daemon socket and writes one line per state, in the requested format. While
the daemon is down it writes the last known state with conn "offline" (once per outage) and
retries with backoff. Long-lived; it never touches the keypress path.
"""

from __future__ import annotations

import json
import socket
import string
from collections.abc import Callable, Generator
from datetime import UTC, datetime
from pathlib import Path
from typing import TextIO

BUILTIN_FORMATS = ("json", "waybar")
SUBSCRIBE_LINE = b'{"cmd":"subscribe"}\n'
CONNECT_TIMEOUT_S = 2.0
BACKOFF_MIN_S = 0.5
BACKOFF_MAX_S = 10.0

State = dict[str, object]
Renderer = Callable[[State], str]

_SAMPLE: State = {
    "v": 1,
    "conn": "ok",
    "counts": {"new": 1, "learning": 2, "review": 3},
    "day_ends_at": "2026-11-16T00:00:00Z",
    "card": {
        "id": "x",
        "kind": "vocab",
        "kind_label": "Vocab",
        "front": "f",
        "back": "b",
        "reading": "r",
        "sentence": "s",
        "revealed": True,
        "previews": {"again": "1m", "hard": "2m", "good": "3m", "easy": "4m"},
    },
    "last": {"rating": "good", "undoable": True},
    "pending": 0,
    "notice": "n",
}


def _dumps(state: State) -> str:
    return json.dumps(state, ensure_ascii=False, separators=(",", ":"))


def _obj(value: object) -> dict[str, object]:
    return value if isinstance(value, dict) else {}


def _flag(value: object) -> str:
    return "true" if value else "false"


def _next_due_in(instant: object, now: datetime | None = None) -> str:
    """ADR-033: m:ss from now to the next learning card's due instant; "" when none or unparsable."""
    if not isinstance(instant, str) or not instant:
        return ""
    try:
        seconds = (datetime.fromisoformat(instant) - (now or datetime.now(UTC))).total_seconds()
    except ValueError:
        return ""
    total = max(0, int(seconds))
    return f"{total // 60}:{total % 60:02d}"


def _fields(state: State) -> dict[str, object]:
    """Flat values for a template. Unknown counts read as 0; absent card text reads as ""."""
    counts = _obj(state.get("counts"))
    card = _obj(state.get("card"))
    previews = _obj(card.get("previews"))
    last = _obj(state.get("last"))
    return {
        "conn": state.get("conn", ""),
        "new": counts.get("new", 0),
        "learning": counts.get("learning", 0),
        "review": counts.get("review", 0),
        "pending": state.get("pending", 0),
        "day_ends_at": state.get("day_ends_at") or "",
        "notice": state.get("notice") or "",
        "last_rating": last.get("rating", ""),
        "undoable": _flag(last.get("undoable")),
        "card_id": card.get("id", ""),
        "kind": card.get("kind", ""),
        "kind_label": card.get("kind_label", ""),
        "front": card.get("front", ""),
        "back": card.get("back", ""),
        "reading": card.get("reading", ""),
        "sentence": card.get("sentence", ""),
        "revealed": _flag(card.get("revealed")),
        "preview_again": previews.get("again", ""),
        "preview_hard": previews.get("hard", ""),
        "preview_good": previews.get("good", ""),
        "preview_easy": previews.get("easy", ""),
        "next_due_at": state.get("next_due_at") or "",
        "next_due_in": _next_due_in(state.get("next_due_at")),
    }


next_due_in = _next_due_in
FIELD_NAMES = frozenset(_fields(_SAMPLE))


def offline_state(last: State | None) -> State:
    """The last known state with conn "offline"; a blank state before the first line."""
    state: State = (
        dict(last)
        if last is not None
        else {
            "v": 1,
            "conn": "offline",
            "counts": None,
            "day_ends_at": None,
            "card": None,
            "last": None,
            "pending": 0,
            "notice": None,
        }
    )
    state["conn"] = "offline"
    state["notice"] = None
    return state


def _waybar(state: State) -> str:
    conn = str(state.get("conn", ""))
    counts = _obj(state.get("counts"))
    if conn == "ok" and counts:
        text = f"{counts.get('new')} / {counts.get('learning')} / {counts.get('review')}"
        tooltip = (
            f"new {counts.get('new')}, learning {counts.get('learning')}, "
            f"review {counts.get('review')}, pending {state.get('pending', 0)}"
        )
    elif conn == "ok":
        text, tooltip = "-", "counts not loaded yet"
    elif conn == "auth":
        text = "auth"
        tooltip = "token rejected: the PAT needs scopes read and review"
    else:
        text, tooltip = "offline", "daemon not reachable"
    card = _obj(state.get("card"))
    label = card.get("kind_label")
    if conn == "ok" and isinstance(label, str) and label:
        tooltip = f"{label}: {tooltip}"
    notice = state.get("notice")
    if isinstance(notice, str) and notice:
        tooltip = f"{tooltip}; {notice}"
    return _dumps({"text": text, "tooltip": tooltip, "class": conn, "alt": conn})


def _template(fmt: str) -> Renderer:
    def render(state: State) -> str:
        return fmt.format_map(_fields(state))

    return render


def renderer(fmt: str) -> Renderer:
    if fmt == "json":
        return _dumps
    if fmt == "waybar":
        return _waybar
    return _template(fmt)


def validate_format(text: str) -> str:
    """Accept json, waybar, or a template over the known fields. Raises ValueError otherwise."""
    if text in BUILTIN_FORMATS:
        return text
    try:
        names = [name for _, name, _, _ in string.Formatter().parse(text) if name is not None]
    except ValueError as exc:
        raise ValueError(f"bad template: {exc}") from exc
    unknown = sorted({name for name in names if name not in FIELD_NAMES})
    if unknown:
        known = ", ".join(sorted(FIELD_NAMES))
        raise ValueError(f"unknown template field(s) {', '.join(unknown)}; known: {known}")
    try:
        text.format_map(_fields(_SAMPLE))
    except (ValueError, KeyError, IndexError, TypeError) as exc:
        raise ValueError(f"bad template: {exc}") from exc
    return text


def _parse_line(raw: bytes) -> State | None:
    try:
        state = json.loads(raw)
    except ValueError:
        return None
    return state if isinstance(state, dict) else None


def _subscribe(sock_path: Path) -> Generator[State, None, None]:
    """Yield states from one connection; returns when it cannot be opened or drops."""
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
            sock.settimeout(CONNECT_TIMEOUT_S)
            sock.connect(str(sock_path))
            sock.settimeout(None)
            sock.sendall(SUBSCRIBE_LINE)
            with sock.makefile("rb") as reader:
                for raw in reader:
                    state = _parse_line(raw)
                    if state is not None:
                        yield state
    except OSError:
        return


def run_watch(
    sock_path: Path,
    fmt: str,
    out: TextIO,
    sleep: Callable[[float], None],
) -> int:
    """Relay states forever. Returns 0 when the reader goes away (broken pipe)."""
    render = renderer(fmt)
    last: State | None = None
    shown_offline = False
    backoff = BACKOFF_MIN_S
    try:
        while True:
            states = _subscribe(sock_path)
            try:
                for state in states:
                    backoff = BACKOFF_MIN_S
                    shown_offline = False
                    last = state
                    _write(out, render, state)
            finally:
                states.close()
            if not shown_offline:
                shown_offline = True
                _write(out, render, offline_state(last))
            sleep(backoff)
            backoff = min(backoff * 2, BACKOFF_MAX_S)
    except OSError:
        return 0


def _write(out: TextIO, render: Renderer, state: State) -> None:
    try:
        text = render(state)
    except ValueError:
        return
    out.write(text + "\n")
    out.flush()


__all__ = ["next_due_in", "offline_state", "renderer", "run_watch", "validate_format"]
