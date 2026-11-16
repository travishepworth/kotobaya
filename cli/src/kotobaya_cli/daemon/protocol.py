"""Input commands (FIFO lines, socket requests) and the button mapping. Pure, never raises."""

from __future__ import annotations

import json
from dataclasses import dataclass

BUTTON_RATING = {
    "again": 1,
    "nothing": 1,
    "something": 1,
    "hard": 2,
    "good": 3,
    "okay": 3,
    "easy": 4,
}
FOUR_BUTTONS = ("again", "hard", "good", "easy")
FIVE_BUTTONS = ("nothing", "something", "hard", "okay", "easy")
ACTIONS = ("reveal", "undo", "skip", "refresh", "keep_going", "stop_keep_going")
MAX_LINE_BYTES = 4096  # FIFO writes under PIPE_BUF are atomic; anything longer is junk


@dataclass(frozen=True)
class Action:
    """reveal / undo / skip / refresh / keep_going."""

    name: str


@dataclass(frozen=True)
class AnswerCmd:
    """One of the seven button names, as the user typed it."""

    button: str


@dataclass(frozen=True)
class Invalid:
    notice: str


@dataclass(frozen=True)
class StatusCmd:
    """Socket only: reply with the current state."""


@dataclass(frozen=True)
class SubscribeCmd:
    """Socket only: keep the connection open and stream state lines."""


Command = Action | AnswerCmd | Invalid | StatusCmd | SubscribeCmd


def _clip(text: str) -> str:
    clean = "".join(ch if ch.isprintable() else "?" for ch in text)
    return clean if len(clean) <= 24 else clean[:24] + "..."


def parse_fifo_line(line: str) -> Command | None:
    """Parse one FIFO line. Blank lines are ignored (None); anything else unknown is Invalid."""
    word = line.strip().lower()
    if not word:
        return None
    if word in BUTTON_RATING:
        return AnswerCmd(word)
    if word in ACTIONS:
        return Action(word)
    return Invalid(f"unknown command: {_clip(line.strip())}")


def parse_socket_request(line: str) -> Command:
    """Parse one socket request: {"cmd": "status|next|answer|reveal|undo|refresh|keep_going|subscribe"}."""
    try:
        request = json.loads(line)
    except ValueError:
        return Invalid("invalid request: not JSON")
    if not isinstance(request, dict):
        return Invalid("invalid request: not an object")
    cmd = request.get("cmd")
    if cmd == "status":
        return StatusCmd()
    if cmd == "subscribe":
        return SubscribeCmd()
    if cmd == "next":
        return Action("skip")
    if cmd in ("reveal", "undo", "refresh", "skip", "keep_going", "stop_keep_going"):
        return Action(str(cmd))
    if cmd == "answer":
        rating = request.get("rating")
        if isinstance(rating, str) and rating.lower() in BUTTON_RATING:
            return AnswerCmd(rating.lower())
        return Invalid("invalid request: unknown rating")
    return Invalid(f"unknown command: {_clip(str(cmd))}")


def resolve_button(name: str, grading_buttons: int) -> tuple[int, str]:
    """(rating, button to send) for a typed button name under the user's button mode.

    A name that belongs to the other mode is translated by rating, so the request always carries
    a `button` valid for the mode (the contract requires it in five-button mode).
    """
    rating = BUTTON_RATING[name]
    names = FIVE_BUTTONS if grading_buttons == 5 else FOUR_BUTTONS
    if name in names:
        return rating, name
    if grading_buttons == 5:
        return rating, {1: "nothing", 3: "okay"}[rating]
    return rating, {1: "again", 3: "good"}[rating]
