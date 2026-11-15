"""The server side of the daemon: queue, settings and the SSE stream. Blocking; run in threads."""

from __future__ import annotations

import json
from collections.abc import Iterable, Iterator
from datetime import UTC, datetime
from typing import Protocol

import urllib3
from kotobaya_client.api.review_api import ReviewApi
from kotobaya_client.api.settings_api import SettingsApi
from kotobaya_client.api_client import ApiClient
from kotobaya_client.configuration import Configuration
from kotobaya_client.exceptions import ApiException, OpenApiException
from kotobaya_client.models.review_answer_request import ReviewAnswerRequest
from kotobaya_client.models.review_button import ReviewButton
from kotobaya_client.models.review_undo_request import ReviewUndoRequest

from kotobaya_cli.api import Settings
from kotobaya_cli.daemon.journal import AnswerEntry
from kotobaya_cli.daemon.session import CardEntry, Counts, QueueSnapshot, format_interval

QUEUE_LIMIT = 20
HTTP_TIMEOUT = (3.0, 15.0)
SSE_TIMEOUT = urllib3.Timeout(connect=5.0, read=60.0)  # the server pings every 25 s
GRADING_KEY = "scheduler.grading_buttons"


class BackendError(Exception):
    """kind is 'offline' (unreachable / 5xx / timeout), 'auth' (401/403) or 'terminal'.

    Review POSTs (classify_review) also use 'forbidden' (403: the token lacks the review scope)
    and 'rejected' (an unexpected 4xx, e.g. a proxy's plain-text 404: retried, never dropped)."""

    def __init__(self, kind: str, message: str = "") -> None:
        super().__init__(message or kind)
        self.kind = kind


def classify(exc: Exception) -> BackendError:
    """Map a transport or API failure to a BackendError."""
    if isinstance(exc, BackendError):
        return exc
    if isinstance(exc, ApiException):
        status = exc.status or 0
        if status in (401, 403):
            return BackendError("auth", f"HTTP {status}")
        if status >= 500 or status in (0, 408, 429):
            return BackendError("offline", f"HTTP {status}")
        return BackendError("terminal", f"HTTP {status}")
    return BackendError("offline", type(exc).__name__)


TERMINAL_REVIEW_STATUSES = (409, 410, 422)


def _problem_code(exc: ApiException) -> str | None:
    try:
        body = json.loads(exc.body or "")
    except ValueError:
        return None
    code = body.get("code") if isinstance(body, dict) else None
    return code if isinstance(code, str) else None


def classify_review(exc: Exception) -> BackendError:
    """Map a failed POST /review/answer or /review/undo. Only 409/410/422, and a 404 that is the
    server's own problem+json `not_found`, are terminal (the journal entry is dropped). Every
    other 4xx may come from a proxy or ingress during a deploy and is retried."""
    if isinstance(exc, BackendError):
        return exc
    if isinstance(exc, ApiException):
        status = exc.status or 0
        if status == 401:
            return BackendError("auth", "HTTP 401")
        if status == 403:
            return BackendError("forbidden", "HTTP 403")
        if status in TERMINAL_REVIEW_STATUSES:
            return BackendError("terminal", f"HTTP {status}")
        if status == 404 and _problem_code(exc) == "not_found":
            return BackendError("terminal", "HTTP 404")
        if 400 <= status < 500 and status not in (408, 429):
            return BackendError("rejected", f"HTTP {status}")
        return BackendError("offline", f"HTTP {status}")
    return BackendError("offline", type(exc).__name__)


class EventStream(Protocol):
    def __iter__(self) -> Iterator[str]: ...

    def close(self) -> None: ...


class Backend(Protocol):
    def fetch_queue(self, keep_going: bool = False) -> QueueSnapshot: ...

    def fetch_grading_buttons(self) -> int: ...

    def open_events(self) -> EventStream: ...

    def post_answer(self, entry: AnswerEntry) -> None: ...

    def post_undo(self, review_id: str) -> None: ...


def iter_sse_events(chunks: Iterable[bytes]) -> Iterator[str]:
    """Yield the event type of every complete SSE event; comments and unknown fields are skipped."""
    buffer = b""
    event_type = ""
    for chunk in chunks:
        buffer += chunk
        while b"\n" in buffer:
            raw, buffer = buffer.split(b"\n", 1)
            line = raw.decode("utf-8", errors="replace").rstrip("\r")
            if line == "":
                if event_type:
                    yield event_type
                event_type = ""
            elif line.startswith("event:"):
                event_type = line[len("event:") :].strip()
            elif line.startswith("data:") and not event_type:
                try:
                    payload = json.loads(line[len("data:") :])
                except ValueError:
                    continue
                if isinstance(payload, dict) and isinstance(payload.get("type"), str):
                    event_type = payload["type"]


class _HttpEventStream:
    def __init__(self, response: urllib3.BaseHTTPResponse) -> None:
        self._response = response

    def __iter__(self) -> Iterator[str]:
        return iter_sse_events(self._response.stream(1024))

    def close(self) -> None:
        self._response.release_conn()
        self._response.close()


class HttpBackend:
    """Generated-client backend with one keep-alive connection pool for the daemon's lifetime."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._client = ApiClient(Configuration(host=settings.base_url, access_token=settings.token))
        self._review = ReviewApi(self._client)
        self._app_settings = SettingsApi(self._client)

    def fetch_queue(self, keep_going: bool = False) -> QueueSnapshot:
        """GET /review/queue. The server turns drawn deck entries into cards inside this call (C-27)."""
        try:
            queue = self._review.get_review_queue(
                limit=QUEUE_LIMIT, keep_going=keep_going, _request_timeout=HTTP_TIMEOUT
            )
        except (OpenApiException, urllib3.exceptions.HTTPError, OSError) as exc:
            raise classify(exc) from exc
        now = datetime.now(UTC)
        items = []
        for item in queue.items:
            previews = None
            if item.preview is not None:
                p = item.preview
                previews = {
                    name: format_interval(opt.due_at, opt.interval_days, now)
                    for name, opt in (
                        ("again", p.again),
                        ("hard", p.hard),
                        ("good", p.good),
                        ("easy", p.easy),
                    )
                }
            # ARCH-CARD-TEXT adds QueueItem.text; the generated model lacks it until regenerated.
            text_obj = getattr(item, "text", None)
            text = None
            if text_obj is not None:
                text = {
                    name: value
                    for name in ("front", "back", "reading", "sentence")
                    if isinstance(value := getattr(text_obj, name, None), str)
                }
            items.append(
                CardEntry(
                    card_id=item.card.id, kind=str(item.card.kind), previews=previews, text=text
                )
            )
        c = queue.counts
        return QueueSnapshot(
            items=items,
            counts=Counts(new=c.new, learning=c.learning, review=c.review),
            day_ends_at=queue.day_ends_at.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%S.")
            + f"{queue.day_ends_at.microsecond // 1000:03d}Z",
            more_available=bool(queue.more_available),
            study_day=queue.study_day.isoformat(),
            to_materialize=queue.to_materialize or 0,
        )

    def fetch_grading_buttons(self) -> int:
        try:
            listing = self._app_settings.list_settings(_request_timeout=HTTP_TIMEOUT)
        except (OpenApiException, urllib3.exceptions.HTTPError, OSError) as exc:
            raise classify(exc) from exc
        for setting in listing.items:
            if setting.key == GRADING_KEY and setting.value == 5:
                return 5
        return 4

    def post_answer(self, entry: AnswerEntry) -> None:
        """POST /review/answer with the journaled body, unchanged on every retry."""
        try:
            request = ReviewAnswerRequest(
                id=entry.id,
                card_id=entry.card_id,
                rating=entry.rating,
                button=ReviewButton(entry.button),
                reviewed_at=datetime.fromisoformat(entry.reviewed_at),
                duration_ms=entry.duration_ms,
                utc_offset_min=entry.utc_offset_min,
            )
        except ValueError as exc:  # a journal entry the contract cannot carry: never retry
            raise BackendError("terminal", "invalid entry") from exc
        try:
            self._review.answer_review(request, _request_timeout=HTTP_TIMEOUT)
        except (OpenApiException, urllib3.exceptions.HTTPError, OSError) as exc:
            raise classify_review(exc) from exc

    def post_undo(self, review_id: str) -> None:
        """POST /review/undo; an already undone review is a 200, so retries are safe."""
        try:
            request = ReviewUndoRequest(review_id=review_id)
        except ValueError as exc:
            raise BackendError("terminal", "invalid entry") from exc
        try:
            self._review.undo_review(request, _request_timeout=HTTP_TIMEOUT)
        except (OpenApiException, urllib3.exceptions.HTTPError, OSError) as exc:
            raise classify_review(exc) from exc

    def open_events(self) -> EventStream:
        pool = self._client.rest_client.pool_manager
        try:
            response = pool.request(
                "GET",
                self._settings.base_url + "/events?types=sync.changed",
                headers={
                    "Authorization": f"Bearer {self._settings.token}",
                    "Accept": "text/event-stream",
                },
                preload_content=False,
                timeout=SSE_TIMEOUT,
            )
        except (urllib3.exceptions.HTTPError, OSError) as exc:
            raise classify(exc) from exc
        if response.status != 200:
            response.release_conn()
            raise classify(ApiException(status=response.status))
        return _HttpEventStream(response)
