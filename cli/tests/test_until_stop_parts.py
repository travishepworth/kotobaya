"""ADR-049 `until_stop_parts` in the daemon state, the watch renderer and the schema."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from conftest import make_loop
from jsonschema import Draft202012Validator  # type: ignore[import-untyped]

from kotobaya_cli import watch
from kotobaya_cli.daemon.session import Counts, QueueSnapshot, Session, UntilStopParts

SCHEMA = json.loads((Path(__file__).parents[1] / "schema" / "state.v1.json").read_text())
DAY_END = "2026-11-19T15:00:00.000Z"


def _state(loop: Any | None) -> dict[str, Any]:
    s = Session()
    s.conn = "ok"
    s.apply_queue(
        QueueSnapshot([], Counts(5, 2, 9), DAY_END, loop=loop, next_learning_due_at=None),
        check_current=False,
    )
    state: dict[str, Any] = s.state()
    Draft202012Validator(SCHEMA).validate(state)  # raises on drift
    return state


PARTS = UntilStopParts(learning=9, later=14, review=0, new=0)


def test_state_carries_the_four_parts_and_validates() -> None:
    state = _state(make_loop("waiting", later_count=14, until_stop_parts=PARTS))
    assert state["until_stop_parts"] == {"learning": 9, "later": 14, "review": 0, "new": 0}


def test_older_server_state_has_null_parts_and_validates() -> None:
    state = _state(make_loop("reviewing"))
    assert state["until_stop_parts"] is None


def test_no_loop_has_null_parts() -> None:
    assert _state(None)["until_stop_parts"] is None


def test_fields_read_the_parts_and_fall_back_to_counts() -> None:
    state: dict[str, Any] = {"counts": {"new": 3, "learning": 26, "review": 7}}
    old = watch._fields(state)  # old server: the deprecated learning count includes later
    assert (old["new"], old["learning"], old["later"], old["review"]) == (3, 26, 0, 7)
    state["until_stop_parts"] = {"new": 1, "learning": 9, "later": 14, "review": 2}
    f = watch._fields(state)
    assert (f["new"], f["learning"], f["later"], f["review"]) == (1, 9, 14, 2)


def test_template_renders_the_later_field() -> None:
    state: dict[str, Any] = {"conn": "ok"}
    state["until_stop_parts"] = {"new": 0, "learning": 9, "later": 14, "review": 0}
    assert watch.renderer("{learning}+{later}")(state) == "9+14"
    assert "later" in watch.FIELD_NAMES


def test_waybar_hides_zero_new_and_never_shows_later() -> None:
    state: dict[str, Any] = {"conn": "ok", "pending": 0}
    state["until_stop_parts"] = {"new": 0, "learning": 9, "later": 0, "review": 3}
    assert json.loads(watch.renderer("waybar")(state))["text"] == "9 learning · 3 review"
    state["until_stop_parts"] = {"new": 2, "learning": 9, "later": 14, "review": 3}
    text = json.loads(watch.renderer("waybar")(state))["text"]
    assert text == "2 new · 9 learning · 3 review"


def test_waybar_falls_back_to_counts_on_old_server() -> None:
    old = {"conn": "ok", "pending": 0, "counts": {"new": 0, "learning": 26, "review": 9}}
    assert json.loads(watch.renderer("waybar")(old))["text"] == "26 learning · 9 review"
