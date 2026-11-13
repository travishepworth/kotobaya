"""Worker parse service (contracts/api/PARSER.md 5, contracts/api/worker-parse.yaml).

``POST /internal/parse`` and ``GET /internal/health`` on ``PARSER_PORT`` (default 8082), bound
to ``PARSER_BIND`` (default ``0.0.0.0``), stdlib ``http.server`` with one thread per request.
At most ``PARSER_MAX_CONCURRENCY`` (default 4) parses run at once; more are answered ``503
busy`` immediately. No authentication (network policy only). User text is never logged.

W-5 wires :func:`start_parse_service` into ``runner/__main__.py``.
"""

from __future__ import annotations

import json
import logging
import os
import threading
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Protocol

import psycopg

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.extract.split import split_sentences
from kotobaya_worker.parse.match import MatchLookup
from kotobaya_worker.parse.match.postgres import MatchCache, PostgresMatchLookup, current_release
from kotobaya_worker.parse.overrides import OverrideRow, load_overrides
from kotobaya_worker.parse.pipeline import parse_sentences, parser_version
from kotobaya_worker.parse.service import rules as rules_mod
from kotobaya_worker.parse.sudachi import Analyzer, get_analyzer

log = logging.getLogger(__name__)

MAX_TEXT_CODE_POINTS = 20_000
MAX_BODY_BYTES = 256 * 1024
DEFAULT_PORT = 8082
DEFAULT_BIND = "0.0.0.0"
DEFAULT_MAX_CONCURRENCY = 4

Json = dict[str, Any]


class NotReady(Exception):
    """No dictionary release loaded yet, Sudachi still loading, or the database is unreachable."""


@dataclass(frozen=True)
class Health:
    sudachi_loaded: bool
    dict_release: str | None
    parser_version: str | None


class ParseBackend(Protocol):
    def health(self) -> Health:
        """Never touches the database."""
        ...

    def parse(self, text: str) -> Json:
        """``WorkerParseResult`` for ``text``; raises :class:`NotReady`."""
        ...

    def tokenise(self, texts: Sequence[str]) -> tuple[str, list[list[Any]]]:
        """(parser_version, tokens) per text, each text exactly one sentence (no splitting),
        global overrides only; raises :class:`NotReady`."""
        ...


def parse_text(
    text: str,
    *,
    lookup: MatchLookup,
    overrides: Sequence[OverrideRow],
    dict_release: str,
    analyzer: Analyzer,
) -> Json:
    """Split (PARSER.md 3), parse each sentence with global overrides only, build the body."""
    sentences = split_sentences(text)
    tokens = parse_sentences(
        sentences, None, None, lookup=lookup, overrides=overrides, analyzer=analyzer
    )
    return {
        "parser_version": parser_version(dict_release, analyzer),
        "sentences": [
            {"text": s, "tokens": [t.to_json() for t in toks]}
            for s, toks in zip(sentences, tokens, strict=True)
        ],
    }


class StaticParseBackend:
    """Backend over a fixed lookup and override set (tests, offline use)."""

    def __init__(
        self,
        lookup: MatchLookup,
        dict_release: str | None,
        analyzer: Analyzer | None,
        overrides: Sequence[OverrideRow] = (),
    ) -> None:
        self.lookup = lookup
        self.dict_release = dict_release
        self.analyzer = analyzer
        self.overrides = list(overrides)

    def health(self) -> Health:
        if self.analyzer is None:
            return Health(False, None, None)
        if self.dict_release is None:
            return Health(True, None, None)
        return Health(True, self.dict_release, parser_version(self.dict_release, self.analyzer))

    def parse(self, text: str) -> Json:
        if self.analyzer is None or self.dict_release is None:
            raise NotReady("no dictionary release loaded" if self.analyzer else "starting")
        return parse_text(
            text,
            lookup=self.lookup,
            overrides=self.overrides,
            dict_release=self.dict_release,
            analyzer=self.analyzer,
        )

    def tokenise(self, texts: Sequence[str]) -> tuple[str, list[list[Any]]]:
        if self.analyzer is None or self.dict_release is None:
            raise NotReady("no dictionary release loaded" if self.analyzer else "starting")
        toks = parse_sentences(
            texts, None, None, lookup=self.lookup, overrides=self.overrides, analyzer=self.analyzer
        )
        return parser_version(self.dict_release, self.analyzer), [list(t) for t in toks]


class PostgresParseBackend:
    """Reads the current release and the live global overrides per request (PARSER.md 5.2:
    every override committed before the request is reflected). Dictionary lookups are cached
    per release. Sudachi loads on a background thread; :meth:`health` reports it."""

    def __init__(
        self,
        conninfo: str,
        load_analyzer: Callable[[], Analyzer] = get_analyzer,
        connect_timeout: int = 5,
    ) -> None:
        self._conninfo = conninfo
        self._connect_timeout = connect_timeout
        self._lock = threading.Lock()
        self._analyzer: Analyzer | None = None
        self._release: str | None = None
        self._cache = MatchCache()
        self._cache_release: str | None = None
        self._load_analyzer = load_analyzer

    def start(self) -> None:
        threading.Thread(target=self._load, name="parse-sudachi-load", daemon=True).start()

    def _load(self) -> None:
        an = self._load_analyzer()
        with self._lock:
            self._analyzer = an
        try:  # best effort, so health shows the release before the first request
            with self._connect() as conn:
                self._remember_release(current_release(conn))
        except psycopg.Error:
            log.warning("parse service: database not reachable at startup")

    def _connect(self) -> psycopg.Connection[Any]:
        return psycopg.connect(self._conninfo, connect_timeout=self._connect_timeout)

    def _remember_release(self, release: str | None) -> MatchCache:
        with self._lock:
            self._release = release
            if release != self._cache_release:
                self._cache = MatchCache()
                self._cache_release = release
            return self._cache

    def health(self) -> Health:
        with self._lock:
            an, release = self._analyzer, self._release
        if an is None:
            return Health(False, None, None)
        if release is None:
            return Health(True, None, None)
        return Health(True, release, parser_version(release, an))

    def parse(self, text: str) -> Json:
        with self._lock:
            an = self._analyzer
        if an is None:
            raise NotReady("Sudachi dictionary still loading")
        try:
            with self._connect() as conn:
                conn.execute("set transaction isolation level repeatable read, read only")
                release = current_release(conn)
                cache = self._remember_release(release)
                if release is None:
                    raise NotReady("no dictionary release loaded yet")
                overrides = load_overrides(conn, None)
                return parse_text(
                    text,
                    lookup=PostgresMatchLookup(conn, cache),
                    overrides=overrides,
                    dict_release=release,
                    analyzer=an,
                )
        except psycopg.OperationalError as exc:
            raise NotReady("database unreachable") from exc

    def tokenise(self, texts: Sequence[str]) -> tuple[str, list[list[Any]]]:
        with self._lock:
            an = self._analyzer
        if an is None:
            raise NotReady("Sudachi dictionary still loading")
        try:
            with self._connect() as conn:
                conn.execute("set transaction isolation level repeatable read, read only")
                release = current_release(conn)
                cache = self._remember_release(release)
                if release is None:
                    raise NotReady("no dictionary release loaded yet")
                toks = parse_sentences(
                    texts,
                    None,
                    None,
                    lookup=PostgresMatchLookup(conn, cache),
                    overrides=load_overrides(conn, None),
                    analyzer=an,
                )
                return parser_version(release, an), [list(t) for t in toks]
        except psycopg.OperationalError as exc:
            raise NotReady("database unreachable") from exc


# ------------------------------------------------------------------ HTTP


def problem(status: int, code: str, title: str, detail: str) -> tuple[int, Json]:
    return status, {"type": "about:blank", "title": title, "status": status, "code": code,
                    "detail": detail}  # fmt: skip


def _invalid(detail: str) -> tuple[int, Json]:
    return problem(400, "invalid_request", "Invalid request", detail)


def validate_request(content_type: str | None, body: bytes) -> str | tuple[int, Json]:
    """The request ``text``, or an error (status, problem). Pure; the size of ``body`` is
    checked here too (the handler also refuses to read an oversized body)."""
    if len(body) > MAX_BODY_BYTES:
        return problem(413, "payload_too_large", "Payload too large", "body over 256 KiB")
    media = (content_type or "").split(";", 1)[0].strip().lower()
    if media != "application/json":
        return _invalid("Content-Type must be application/json")
    try:
        doc = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        return _invalid("body is not valid JSON")
    if not isinstance(doc, dict):
        return _invalid("body must be a JSON object")
    extra = set(doc) - {"text"}
    if extra:
        return _invalid("unknown member: " + ", ".join(sorted(extra)))
    text = doc.get("text")
    if not isinstance(text, str):
        return _invalid("text must be a string")
    if text == "":
        return _invalid("text is empty")
    if len(text) > MAX_TEXT_CODE_POINTS:
        return problem(413, "payload_too_large", "Payload too large",
                       f"text over {MAX_TEXT_CODE_POINTS} code points")  # fmt: skip
    if "\u0000" in text:
        return _invalid("text contains U+0000")
    if any(0xD800 <= ord(c) <= 0xDFFF for c in text):
        return _invalid("text contains a lone surrogate")
    return text


def health_response(backend: ParseBackend) -> tuple[int, Json]:
    h = backend.health()
    if not h.sudachi_loaded:
        return 503, {"status": "starting", "dict_release": None, "parser_version": None}
    return 200, {"status": "ready", "dict_release": h.dict_release,
                 "parser_version": h.parser_version}  # fmt: skip


class ParseService:
    """A running parse service. ``port`` is the bound port (useful with port 0 in tests)."""

    def __init__(self, backend: ParseBackend, bind: str, port: int, max_concurrency: int) -> None:
        self.backend = backend
        self.slots = threading.BoundedSemaphore(max_concurrency)
        self.max_concurrency = max_concurrency
        self.httpd = ThreadingHTTPServer((bind, port), _make_handler(self))
        self.httpd.daemon_threads = True
        self.port: int = self.httpd.server_address[1]
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        self._thread = threading.Thread(
            target=self.httpd.serve_forever, name="parse-service", daemon=True
        )
        self._thread.start()

    def stop(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        if self._thread is not None:
            self._thread.join(timeout=5)

    def handle_parse(self, content_type: str | None, body: bytes) -> tuple[int, Json]:
        checked = validate_request(content_type, body)
        if not isinstance(checked, str):
            return checked
        if not self.slots.acquire(blocking=False):
            return problem(503, "busy", "Busy", f"{self.max_concurrency} parses in flight")
        try:
            return 200, self.backend.parse(checked)
        except NotReady as exc:
            return problem(503, "not_ready", "Not ready", str(exc))
        except Exception:
            log.exception("parse service: internal error")
            return problem(500, "internal", "Internal error", "unexpected error")
        finally:
            self.slots.release()

    def handle_rules(self, content_type: str | None, body: bytes) -> tuple[int, Json]:
        checked = rules_mod.validate(content_type, body)
        if len(checked) == 2:
            return checked
        rules, sentences, max_matches = checked
        if not sentences:  # validate only: no dictionary, no database, no concurrency slot
            try:
                return 200, rules_mod.evaluate(rules, [], max_matches, self.backend.tokenise)
            except Exception:
                log.exception("parse service: internal error")
                return problem(500, "internal", "Internal error", "unexpected error")
        if not self.slots.acquire(blocking=False):
            return problem(503, "busy", "Busy", f"{self.max_concurrency} parses in flight")
        try:
            return 200, rules_mod.evaluate(rules, sentences, max_matches, self.backend.tokenise)
        except NotReady as exc:
            return problem(503, "not_ready", "Not ready", str(exc))
        except Exception:
            log.exception("parse service: internal error")
            return problem(500, "internal", "Internal error", "unexpected error")
        finally:
            self.slots.release()


def _make_handler(service: ParseService) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def _send(self, status: int, payload: Json, content_type: str) -> None:
            data = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _problem(self, status: int, payload: Json) -> None:
            self._send(status, payload, "application/problem+json")

        def _not_found(self) -> None:
            self.close_connection = True
            self._problem(404, {"type": "about:blank", "title": "Not Found", "status": 404})

        def do_GET(self) -> None:
            if self.path != "/internal/health":
                self._not_found()
                return
            status, payload = health_response(service.backend)
            self._send(status, payload, "application/json")

        def do_POST(self) -> None:
            if self.path not in ("/internal/parse", "/internal/grammar/rules/test"):
                self._not_found()
                return
            is_rules = self.path == "/internal/grammar/rules/test"
            max_body = rules_mod.MAX_BODY_BYTES if is_rules else MAX_BODY_BYTES
            rid = self.headers.get("X-Request-Id", "")[:100]
            length_raw = self.headers.get("Content-Length")
            try:
                length = int(length_raw) if length_raw is not None else -1
            except ValueError:
                length = -1
            if length < 0:
                self.close_connection = True
                self._problem(*_invalid("Content-Length required"))
                return
            if length > max_body:
                self.close_connection = True  # the body is not read
                self._problem(
                    *problem(
                        413,
                        "payload_too_large",
                        "Payload too large",
                        f"body over {max_body // 1024} KiB",
                    )
                )
                return
            body = self.rfile.read(length)
            handle = service.handle_rules if is_rules else service.handle_parse
            status, payload = handle(self.headers.get("Content-Type"), body)
            log.info("parse request id=%s status=%d bytes=%d", rid, status, length)
            if status == 200:
                self._send(status, payload, "application/json")
            else:
                self._problem(status, payload)

        def log_message(self, format: str, *args: object) -> None:
            return

    return Handler


def start_parse_service(
    database_url: str | None = None,
    *,
    backend: ParseBackend | None = None,
    bind: str | None = None,
    port: int | None = None,
    max_concurrency: int | None = None,
) -> ParseService:
    """Start serving on a daemon thread and return at once (Sudachi loads in the background).

    Defaults come from ``PARSER_BIND``, ``PARSER_PORT``, ``PARSER_MAX_CONCURRENCY`` and
    ``DATABASE_URL``. Pass ``backend`` to serve something other than the Postgres backend.
    """
    if backend is None:
        url = database_url if database_url is not None else os.environ["DATABASE_URL"]
        pg = PostgresParseBackend(to_conninfo(url))
        pg.start()
        backend = pg
    service = ParseService(
        backend,
        bind if bind is not None else os.environ.get("PARSER_BIND", DEFAULT_BIND),
        port if port is not None else int(os.environ.get("PARSER_PORT", DEFAULT_PORT)),
        max_concurrency
        if max_concurrency is not None
        else int(os.environ.get("PARSER_MAX_CONCURRENCY", DEFAULT_MAX_CONCURRENCY)),
    )
    service.start()
    return service


__all__ = [
    "Health",
    "NotReady",
    "ParseBackend",
    "ParseService",
    "PostgresParseBackend",
    "StaticParseBackend",
    "health_response",
    "parse_text",
    "start_parse_service",
    "validate_request",
]
