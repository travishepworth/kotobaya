"""The answer journal: unsent answers and undos, durable (fsynced) before any POST.

One JSON object per line, in send order. Every change rewrites the file atomically
(tmp + fsync + rename + directory fsync), so a crash leaves either the old or the new journal.
"""

from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class AnswerEntry:
    """One POST /review/answer body. `id` is minted once and reused on every retry and replay."""

    id: str
    card_id: str
    rating: int
    button: str
    reviewed_at: str
    duration_ms: int
    utc_offset_min: int

    def to_json(self) -> dict[str, object]:
        return {
            "op": "answer",
            "id": self.id,
            "card_id": self.card_id,
            "rating": self.rating,
            "button": self.button,
            "reviewed_at": self.reviewed_at,
            "duration_ms": self.duration_ms,
            "utc_offset_min": self.utc_offset_min,
        }


@dataclass(frozen=True)
class UndoEntry:
    """One POST /review/undo for `review_id` (the answered card is kept for queue bookkeeping)."""

    review_id: str
    card_id: str

    @property
    def id(self) -> str:
        return self.review_id

    def to_json(self) -> dict[str, object]:
        return {"op": "undo", "review_id": self.review_id, "card_id": self.card_id}


Entry = AnswerEntry | UndoEntry


def _parse(raw: str) -> Entry:
    obj = json.loads(raw)
    if not isinstance(obj, dict):
        raise TypeError("not an object")
    op = obj.get("op")
    if op == "answer":
        entry = AnswerEntry(
            id=obj["id"],
            card_id=obj["card_id"],
            rating=obj["rating"],
            button=obj["button"],
            reviewed_at=obj["reviewed_at"],
            duration_ms=obj["duration_ms"],
            utc_offset_min=obj["utc_offset_min"],
        )
        types = (
            (entry.id, str),
            (entry.card_id, str),
            (entry.rating, int),
            (entry.button, str),
            (entry.reviewed_at, str),
            (entry.duration_ms, int),
            (entry.utc_offset_min, int),
        )
        if not all(isinstance(v, t) for v, t in types):
            raise ValueError("bad field type")
        return entry
    if op == "undo":
        undo = UndoEntry(review_id=obj["review_id"], card_id=obj["card_id"])
        if not (isinstance(undo.review_id, str) and isinstance(undo.card_id, str)):
            raise ValueError("bad field type")
        return undo
    raise ValueError("unknown op")


class Journal:
    """Entries in send order. Loading never loses data: unreadable lines are kept in a
    `journal.corrupt.<ms>` copy and reported through `notice`."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self.notice: str | None = None
        self._entries: list[Entry] = []
        self._load()

    @property
    def entries(self) -> list[Entry]:
        return list(self._entries)

    def _load(self) -> None:
        try:
            text = self.path.read_text(encoding="utf-8", errors="replace")
        except FileNotFoundError:
            return
        bad = 0
        for raw in text.splitlines():
            if not raw.strip():
                continue
            try:
                self._entries.append(_parse(raw))
            except (ValueError, KeyError, TypeError):
                bad += 1
        if bad:
            keep = self.path.with_name(f"{self.path.name}.corrupt.{time.time_ns() // 1_000_000}")
            keep.write_bytes(self.path.read_bytes())
            self.notice = f"journal: {bad} corrupt line(s) kept in {keep.name}"
            self._write()

    def append(self, entry: Entry) -> None:
        """Add an entry and make it durable before returning."""
        self._entries.append(entry)
        self._write()

    def remove(self, entry: Entry) -> None:
        self._entries = [e for e in self._entries if e is not entry]
        self._write()

    def _write(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        tmp = self.path.with_name(self.path.name + ".tmp")
        data = "".join(json.dumps(e.to_json(), separators=(",", ":")) + "\n" for e in self._entries)
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        try:
            view = memoryview(data.encode())
            while view:
                view = view[os.write(fd, view) :]
            os.fsync(fd)
        finally:
            os.close(fd)
        os.replace(tmp, self.path)
        dfd = os.open(self.path.parent, os.O_RDONLY)
        try:
            os.fsync(dfd)
        finally:
            os.close(dfd)
