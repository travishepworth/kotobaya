"""POST /internal/grammar/rules/test (worker-parse.yaml 1.1.0); needs no Sudachi dictionary."""

from __future__ import annotations

import http.client
import json
from collections.abc import Iterator, Sequence
from typing import Any

import pytest

from kotobaya_worker.parse.service import (
    Health,
    NotReady,
    ParseService,
    start_parse_service,
)

RULE = "[verb:未然形] ざる を 得 ない"


def tok(
    s: int, e: int, surface: str, lemma: str, pos: list[str], cform: str | None = None
) -> dict[str, Any]:
    return {"s": s, "e": e, "surface": surface, "lemma": lemma, "reading": surface,
            "pos": pos, "cform": cform, "seq": None, "class": "content", "override": False}  # fmt: skip


SENT = {
    "text": "行かざるを得ない。",
    "tokens": [
        tok(0, 2, "行か", "行く", ["動詞", "非自立可能"], "未然形-一般"),
        tok(2, 4, "ざる", "ず", ["助動詞"], "連体形-補助"),
        tok(4, 5, "を", "を", ["助詞", "格助詞"]),
        tok(5, 6, "得", "得る", ["動詞", "非自立可能"], "未然形-一般"),
        tok(6, 8, "ない", "ない", ["助動詞"], "終止形-一般"),
    ],
}


class FakeBackend:
    def __init__(self, ready: bool = True) -> None:
        self.ready = ready
        self.calls = 0

    def health(self) -> Health:
        return Health(True, None, None)

    def parse(self, text: str) -> dict[str, Any]:
        raise NotReady("unused")

    def tokenise(self, texts: Sequence[str]) -> tuple[str, list[list[Any]]]:
        self.calls += 1
        if not self.ready:
            raise NotReady("no dictionary release loaded yet")
        return "kp1;fake", [SENT["tokens"] if t == SENT["text"] else [] for t in texts]  # type: ignore[misc]


@pytest.fixture
def backend() -> FakeBackend:
    return FakeBackend()


@pytest.fixture
def service(backend: FakeBackend) -> Iterator[ParseService]:
    svc = start_parse_service(backend=backend, bind="127.0.0.1", port=0, max_concurrency=1)
    yield svc
    svc.stop()


def post(
    svc: ParseService, doc: Any, path: str = "/internal/grammar/rules/test"
) -> tuple[int, Any]:
    conn = http.client.HTTPConnection("127.0.0.1", svc.port, timeout=30)
    conn.request(
        "POST", path, body=json.dumps(doc).encode(), headers={"Content-Type": "application/json"}
    )
    resp = conn.getresponse()
    return resp.status, json.loads(resp.read())


def test_validate_only_reports_column_and_other_rules_survive(service: ParseService) -> None:
    status, body = post(service, {"rules": [RULE, "[verbb] ない"]})
    assert status == 200
    assert body["matches"] == [] and body["truncated"] is False and body["parser_version"] is None
    assert [e["rule_index"] for e in body["errors"]] == [1]
    assert body["errors"][0]["column"] >= 1 and body["errors"][0]["message"]


def test_validate_only_ignores_not_ready_and_busy(
    backend: FakeBackend, service: ParseService
) -> None:
    backend.ready = False
    assert service.slots.acquire(blocking=False)  # the only slot is taken
    status, body = post(service, {"rules": [RULE], "sentences": []})
    assert status == 200 and body["errors"] == []
    assert backend.calls == 0


def test_tokenised_match_with_invalid_rule(service: ParseService) -> None:
    status, body = post(service, {"rules": [RULE, "[verbb] ない"], "sentences": [SENT]})
    assert status == 200
    assert body["matches"] == [
        {
            "rule_index": 0,
            "sentence_index": 0,
            "start_offset": 0,
            "end_offset": 8,
            "text": "行かざるを得ない",
        }
    ]
    assert [e["rule_index"] for e in body["errors"]] == [1]
    assert body["parser_version"] is None


def test_string_sentences_mixed_and_truncation(service: ParseService) -> None:
    status, body = post(service, {"rules": [RULE], "sentences": ["パン", SENT["text"], SENT]})
    assert status == 200
    assert [m["sentence_index"] for m in body["matches"]] == [1, 2]
    assert body["parser_version"] == "kp1;fake"
    status, body = post(service, {"rules": [RULE], "sentences": [SENT, SENT], "max_matches": 1})
    assert len(body["matches"]) == 1 and body["truncated"] is True


def test_string_sentences_not_ready_and_busy(backend: FakeBackend, service: ParseService) -> None:
    backend.ready = False
    status, body = post(service, {"rules": [RULE], "sentences": ["x"]})
    assert (status, body["code"]) == (503, "not_ready")
    status, body = post(service, {"rules": [RULE], "sentences": [SENT]})  # tokenised never needs it
    assert status == 200
    assert service.slots.acquire(blocking=False)
    status, body = post(service, {"rules": [RULE], "sentences": [SENT]})
    assert (status, body["code"]) == (503, "busy")


@pytest.mark.parametrize(
    "doc",
    [
        {"rules": []},
        {"rules": ["a"] * 21},
        {"rules": [RULE], "extra": 1},
        {
            "rules": [RULE],
            "sentences": [{"text": "ab", "tokens": [tok(0, 3, "ab", "ab", ["名詞"])]}],
        },
        {
            "rules": [RULE],
            "sentences": [
                {
                    "text": "ab",
                    "tokens": [tok(1, 2, "b", "b", ["名詞"]), tok(0, 1, "a", "a", ["名詞"])],
                }
            ],
        },
        {"rules": [RULE], "max_matches": 0},
    ],
)
def test_400(service: ParseService, doc: Any) -> None:
    status, body = post(service, doc)
    assert (status, body["code"]) == (400, "invalid_request")


def test_413_too_many_sentences(service: ParseService) -> None:
    status, body = post(service, {"rules": [RULE], "sentences": ["a"] * 501})
    assert (status, body["code"]) == (413, "payload_too_large")
