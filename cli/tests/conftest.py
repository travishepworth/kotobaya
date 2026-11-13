"""Fake HTTP at the generated client's transport boundary (no live server)."""

from __future__ import annotations

import json
from collections import deque
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urlsplit

import pytest
from kotobaya_client.rest import RESTClientObject, RESTResponse

Route = tuple[str, str]  # (method, path)


class _Raw:
    """Stands in for urllib3.HTTPResponse; RESTResponse only reads these fields."""

    def __init__(self, status: int, data: bytes) -> None:
        self.status = status
        self.reason = "" if status < 400 else "error"
        self.data = data
        self.headers = {"content-type": "application/json"}


@dataclass
class Recorded:
    method: str
    url: str
    headers: dict[str, Any]
    body: Any


@dataclass
class FakeHttp:
    routes: dict[Route, deque[tuple[int, Any]]] = field(default_factory=dict)
    calls: list[Recorded] = field(default_factory=list)

    def add(self, method: str, path: str, status: int, body: Any = None) -> None:
        """Queue a response; the last queued response repeats once the queue is drained."""
        self.routes.setdefault((method, path), deque()).append((status, body))

    def handle(self, method: str, url: str, headers: Any, body: Any) -> RESTResponse:
        parts = urlsplit(url)
        self.calls.append(Recorded(method, url, dict(headers or {}), body))
        queue = self.routes.get((method, parts.path))
        if queue is None:
            return RESTResponse(_Raw(404, b'{"code":"not_found","title":"Not Found"}'))
        status, payload = queue.popleft() if len(queue) > 1 else queue[0]
        data = b"" if payload is None else json.dumps(payload).encode()
        return RESTResponse(_Raw(status, data))

    def last(self) -> Recorded:
        return self.calls[-1]


@pytest.fixture
def fake_http(monkeypatch: pytest.MonkeyPatch) -> FakeHttp:
    fake = FakeHttp()

    def request(
        self: RESTClientObject,
        method: str,
        url: str,
        headers: Any = None,
        body: Any = None,
        post_params: Any = None,
        _request_timeout: Any = None,
    ) -> RESTResponse:
        return fake.handle(method, url, headers, body)

    monkeypatch.setattr(RESTClientObject, "request", request)
    return fake


@pytest.fixture
def env() -> dict[str, str]:
    return {"KOTOBAYA_URL": "https://kotobaya.test", "KOTOBAYA_TOKEN": "kbp_secrettoken"}


def card_json(card_id: str, text: str = "<b>猫</b>") -> dict[str, Any]:
    return {
        "id": card_id,
        "buried_until": None,
        "created_at": "2026-11-12T09:30:00.000Z",
        "deleted_at": None,
        "difficulty": None,
        "due_at": "2026-11-13T00:00:00.000Z",
        "flags": 0,
        "interval_days": None,
        "kind": "recognition",
        "lapses": 2,
        "last_review_at": None,
        "leech": False,
        "merged_into_id": None,
        "note": {
            "entry_seq": None,
            "fields": {"front": text, "back": "cat"},
            "grammar_point_id": None,
            "id": "01900000-0000-7000-8000-0000000000aa",
            "kanji_literal": None,
            "mnemonic": "",
            "note_type_id": "01900000-0000-7000-8000-0000000000bb",
            "source_sentence": "",
            "tags": [],
            "type": "vocab",
        },
        "note_id": "01900000-0000-7000-8000-0000000000aa",
        "preset_id": "01900000-0000-7000-8000-0000000000cc",
        "reps": 3,
        "stability": None,
        "state": "review",
        "step": None,
        "suspended": False,
        "updated_at": "2026-11-12T09:30:00.000Z",
    }


def job_json(
    job_id: str, status: str, progress: float = 0.0, error: str | None = None
) -> dict[str, Any]:
    return {
        "attempts": 1,
        "created_at": "2026-11-12T09:30:00.000Z",
        "error": error,
        "finished_at": None,
        "id": job_id,
        "kind": "media-import",
        "max_attempts": 3,
        "payload": {},
        "progress": progress,
        "progress_message": f"stage {status}",
        "result": None,
        "started_at": None,
        "status": status,
    }
