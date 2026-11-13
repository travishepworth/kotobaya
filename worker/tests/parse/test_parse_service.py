"""Worker parse service (T403b; PARSER.md 5, worker-parse.yaml)."""

from __future__ import annotations

import http.client
import json
import threading
from collections.abc import Iterator
from typing import Any

import jsonschema  # type: ignore[import-untyped]
import pytest

from kotobaya_worker.parse.overrides import OverrideRow
from kotobaya_worker.parse.service import (
    Health,
    NotReady,
    ParseService,
    StaticParseBackend,
    start_parse_service,
    validate_request,
)
from kotobaya_worker.parse.sudachi import get_analyzer, installed_dictionaries

from .match_support import TOKEN_SCHEMA, assert_array_invariants, fixture_lookup, load_json

pytestmark = pytest.mark.skipif(
    not installed_dictionaries(), reason="no Sudachi dictionary installed"
)

RELEASE = "2026.11.1"


def _global(match: str, tokens: list[dict[str, Any]], media: str | None = None) -> OverrideRow:
    return OverrideRow("0193a1b2-0c12-7000-8000-000000000001", media, None, match,
                       tuple(tokens), "0193a1b2c3d4-0000-0193a1b2-0c12-7000-8000-0000000000d1")  # fmt: skip


@pytest.fixture
def service() -> Iterator[ParseService]:
    backend = StaticParseBackend(
        fixture_lookup(),
        RELEASE,
        get_analyzer(),
        overrides=[_global("映画", [{"surface": "映画", "seq": None}])],
    )
    svc = start_parse_service(backend=backend, bind="127.0.0.1", port=0, max_concurrency=2)
    yield svc
    svc.stop()


def _request(svc: ParseService, method: str, path: str, body: bytes | None = None,
             content_type: str | None = "application/json") -> tuple[int, str, Any]:  # fmt: skip
    conn = http.client.HTTPConnection("127.0.0.1", svc.port, timeout=30)
    headers = {"X-Request-Id": "test-1"}
    if content_type is not None:
        headers["Content-Type"] = content_type
    conn.request(method, path, body=body, headers=headers)
    resp = conn.getresponse()
    raw = resp.read()
    conn.close()
    return resp.status, resp.getheader("Content-Type") or "", json.loads(raw) if raw else None


def _post(svc: ParseService, doc: Any) -> tuple[int, str, Any]:
    return _request(svc, "POST", "/internal/parse", json.dumps(doc).encode("utf-8"))


def _assert_problem(status: int, ctype: str, body: Any, want_status: int, code: str) -> None:
    assert (status, ctype, body["status"], body["code"]) == (
        want_status, "application/problem+json", want_status, code,
    )  # fmt: skip
    assert isinstance(body["type"], str) and isinstance(body["title"], str)


def test_valid_request_returns_c12_shape(service: ParseService) -> None:
    # PARSER.md 3: 」 after a terminator ends the sentence only before an opener, whitespace or
    # the end of the line, so 「はい。」そうです！ is one sentence and 「はい。」 「いいえ。」 two.
    text = "昨日、友達と映画を見ました。\n「はい。」そうです！\n「はい。」　「いいえ。」"
    status, ctype, body = _post(service, {"text": text})
    assert (status, ctype) == (200, "application/json")
    assert set(body) == {"parser_version", "sentences"}
    pv = body["parser_version"]
    assert pv.startswith("kp1;sudachi=") and f";dict={RELEASE};" in pv and len(pv) <= 200
    assert all(0x20 <= ord(c) <= 0x7E for c in pv)
    assert [s["text"] for s in body["sentences"]] == [
        "昨日、友達と映画を見ました。", "「はい。」そうです！", "「はい。」", "「いいえ。」",
    ]  # fmt: skip
    validator = jsonschema.Draft202012Validator(load_json(TOKEN_SCHEMA))
    for s in body["sentences"]:
        assert set(s) == {"text", "tokens"} and s["text"] in text
        assert_array_invariants(s["text"], s["tokens"])
        for t in s["tokens"]:
            validator.validate(t)
    first = body["sentences"][0]["tokens"]
    assert [t["surface"] for t in first] == ["昨日", "友達", "と", "映画", "を", "見", "まし", "た"]
    assert first[0]["seq"] == 1579260 and first[5]["seq"] == 1259290
    # the global override applied, flagged
    eiga = first[3]
    assert (eiga["seq"], eiga["override"]) == (None, True)
    # deterministic
    assert _post(service, {"text": text})[2] == body


def test_whitespace_only_text_yields_no_sentences(service: ParseService) -> None:
    status, _, body = _post(service, {"text": " 　\n\t"})
    assert status == 200 and body["sentences"] == []


def test_text_over_20000_code_points_rejected(service: ParseService) -> None:
    status, ctype, body = _post(service, {"text": "あ" * 20_001})
    _assert_problem(status, ctype, body, 413, "payload_too_large")
    # 20,000 astral code points (40,000 UTF-16 units) are still within the limit
    assert validate_request("application/json", json.dumps({"text": "𠮷" * 20_000}).encode()) \
        == "𠮷" * 20_000  # fmt: skip


def test_body_over_256_kib_rejected(service: ParseService) -> None:
    body = json.dumps({"text": "a"}).encode() + b" " * (256 * 1024)
    status, ctype, doc = _request(service, "POST", "/internal/parse", body)
    _assert_problem(status, ctype, doc, 413, "payload_too_large")


@pytest.mark.parametrize(
    ("body", "ctype"),
    [
        (b"{not json", "application/json"),
        (b"\xff\xfe", "application/json"),
        (b"[]", "application/json"),
        (b'{"txt": "a"}', "application/json"),
        (b'{"text": 5}', "application/json"),
        (b'{"text": ""}', "application/json"),
        (b'{"text": "a\\u0000b"}', "application/json"),
        (b'{"text": "a", "extra": 1}', "application/json"),
        (b'{"text": "a"}', "text/plain"),
        (b'{"text": "a"}', None),
    ],
    ids=["malformed", "not-utf8", "array", "missing", "non-string", "empty", "nul", "extra",
         "content-type", "no-content-type"],
)  # fmt: skip
def test_invalid_requests_are_400(service: ParseService, body: bytes, ctype: str | None) -> None:
    status, rctype, doc = _request(service, "POST", "/internal/parse", body, ctype)
    _assert_problem(status, rctype, doc, 400, "invalid_request")


def test_charset_parameter_accepted(service: ParseService) -> None:
    status, _, _ = _request(service, "POST", "/internal/parse", b'{"text": "a"}',
                            "application/json; charset=utf-8")  # fmt: skip
    assert status == 200


def test_unknown_paths_are_404(service: ParseService) -> None:
    assert _request(service, "GET", "/internal/parse")[0] == 404
    assert _request(service, "POST", "/parse", b"{}")[0] == 404
    assert _request(service, "GET", "/healthz")[0] == 404


def test_health_ready(service: ParseService) -> None:
    status, ctype, body = _request(service, "GET", "/internal/health", content_type=None)
    assert (status, ctype, body["status"], body["dict_release"]) == (
        200, "application/json", "ready", RELEASE,
    )  # fmt: skip
    assert body["parser_version"].startswith("kp1;")


def _serve(backend: Any, **kw: Any) -> ParseService:
    return start_parse_service(backend=backend, bind="127.0.0.1", port=0, **kw)


def test_health_and_parse_before_release_and_while_starting() -> None:
    no_release = _serve(StaticParseBackend(fixture_lookup(), None, get_analyzer()))
    starting = _serve(StaticParseBackend(fixture_lookup(), None, None))
    try:
        assert _request(no_release, "GET", "/internal/health", content_type=None)[::2] == (
            200, {"status": "ready", "dict_release": None, "parser_version": None},
        )  # fmt: skip
        status, ctype, body = _post(no_release, {"text": "a"})
        _assert_problem(status, ctype, body, 503, "not_ready")
        assert _request(starting, "GET", "/internal/health", content_type=None)[::2] == (
            503, {"status": "starting", "dict_release": None, "parser_version": None},
        )  # fmt: skip
        status, ctype, body = _post(starting, {"text": "a"})
        _assert_problem(status, ctype, body, 503, "not_ready")
    finally:
        no_release.stop()
        starting.stop()


class _Blocking:
    """Backend whose parse blocks until released, to fill the concurrency slots."""

    def __init__(self) -> None:
        self.entered = threading.Semaphore(0)
        self.release = threading.Event()

    def health(self) -> Health:
        return Health(True, RELEASE, "kp1;test")

    def parse(self, text: str) -> dict[str, Any]:
        self.entered.release()
        self.release.wait(10)
        if text == "boom":
            raise RuntimeError("secret user text must not leak")
        if text == "db":
            raise NotReady("database unreachable")
        return {"parser_version": "kp1;test", "sentences": []}


def test_concurrency_limit_answers_busy_without_queueing() -> None:
    backend = _Blocking()
    svc = _serve(backend, max_concurrency=1)
    try:
        results: list[int] = []
        t = threading.Thread(target=lambda: results.append(_post(svc, {"text": "a"})[0]))
        t.start()
        assert backend.entered.acquire(timeout=10)
        status, ctype, body = _post(svc, {"text": "b"})
        _assert_problem(status, ctype, body, 503, "busy")
        # health never counts against the limit
        assert _request(svc, "GET", "/internal/health", content_type=None)[0] == 200
        backend.release.set()
        t.join(10)
        assert results == [200]
        assert _post(svc, {"text": "c"})[0] == 200  # slot freed
    finally:
        backend.release.set()
        svc.stop()


def test_backend_errors_map_to_500_and_503() -> None:
    backend = _Blocking()
    backend.release.set()
    svc = _serve(backend)
    try:
        status, ctype, body = _post(svc, {"text": "boom"})
        _assert_problem(status, ctype, body, 500, "internal")
        assert "secret" not in json.dumps(body)
        status, ctype, body = _post(svc, {"text": "db"})
        _assert_problem(status, ctype, body, 503, "not_ready")
    finally:
        svc.stop()
