"""Structured logging for the worker (OBS-1; stdlib only).

- :func:`configure` installs one stderr handler with :class:`JsonFormatter` (one JSON object per
  line) or, with ``LOG_FORMAT=text``, a plain ``key=value`` line. ``LOG_LEVEL`` sets the level.
- :func:`job_scope` binds ``job_id`` and ``kind`` for the current thread; every record formatted
  inside it carries them (the runner wraps each job in one).
- :func:`job_summary` is the end-of-job count line every handler emits. Level rule (OBS-1 goals):
  non-empty input and zero output -> ERROR; anything dropped or skipped -> WARNING; else INFO.

Grep in ``kubectl logs``: ``"event": "job_summary"``, ``"outcome": "zero_output"``,
``"outcome": "dropped"``, ``"level": "ERROR"``.
"""

from __future__ import annotations

import contextvars
import json
import logging
import os
import sys
import threading
import traceback
from collections.abc import Collection, Iterator, Mapping
from contextlib import contextmanager
from datetime import UTC, datetime
from types import TracebackType
from typing import Any, TextIO

_JOB_ID: contextvars.ContextVar[str | None] = contextvars.ContextVar("job_id", default=None)
_JOB_KIND: contextvars.ContextVar[str | None] = contextvars.ContextVar("job_kind", default=None)

SUMMARY_EVENT = "job_summary"
OUTCOME_OK = "ok"
OUTCOME_DROPPED = "dropped"
OUTCOME_ZERO = "zero_output"
OUTCOME_NO_INPUT = "zero_input"


@contextmanager
def job_scope(job_id: object, kind: str) -> Iterator[None]:
    """Bind ``job_id`` / ``kind`` to every record logged by this thread until the block exits."""
    t1 = _JOB_ID.set(str(job_id))
    t2 = _JOB_KIND.set(kind)
    try:
        yield
    finally:
        _JOB_KIND.reset(t2)
        _JOB_ID.reset(t1)


def current_job() -> tuple[str | None, str | None]:
    return _JOB_ID.get(), _JOB_KIND.get()


class JobContextFilter(logging.Filter):
    """Adds ``job_id`` / ``job_kind`` to a record from the current :func:`job_scope` (if unset)."""

    def filter(self, record: logging.LogRecord) -> bool:
        job_id, kind = current_job()
        if getattr(record, "job_id", None) is None:
            record.job_id = job_id
        if getattr(record, "job_kind", None) is None:
            record.job_kind = kind
        return True


def _fields(record: logging.LogRecord) -> dict[str, Any]:
    out: dict[str, Any] = {
        "ts": datetime.fromtimestamp(record.created, UTC).isoformat(timespec="milliseconds"),
        "level": record.levelname,
        "logger": record.name,
        "msg": record.getMessage(),
    }
    job_id = getattr(record, "job_id", None) or _JOB_ID.get()
    kind = getattr(record, "job_kind", None) or _JOB_KIND.get()
    if job_id is not None:
        out["job_id"] = job_id
    if kind is not None:
        out["kind"] = kind
    extra = getattr(record, "fields", None)
    if isinstance(extra, Mapping):
        for k, v in extra.items():
            out.setdefault(str(k), v)
    if record.exc_info and record.exc_info[0] is not None:
        out["exc"] = "".join(traceback.format_exception(*record.exc_info)).rstrip()
    elif record.exc_text:
        out["exc"] = record.exc_text
    if record.stack_info:
        out["stack"] = record.stack_info
    return out


class JsonFormatter(logging.Formatter):
    """One JSON object per line: ts, level, logger, msg, job_id, kind, summary fields, exc."""

    def format(self, record: logging.LogRecord) -> str:
        return json.dumps(_fields(record), ensure_ascii=False, default=str)


class KeyValueFormatter(logging.Formatter):
    """``ts=.. level=.. logger=.. job_id=.. kind=.. msg="..."``; the stack trace is a quoted value."""

    def format(self, record: logging.LogRecord) -> str:
        parts = []
        for k, v in _fields(record).items():
            s = v if isinstance(v, str) else json.dumps(v, ensure_ascii=False, default=str)
            if not s or any(c in s for c in ' "=\n\t'):
                s = json.dumps(s, ensure_ascii=False)
            parts.append(f"{k}={s}")
        return " ".join(parts)


def configure(
    env: Mapping[str, str] | None = None, stream: TextIO | None = None
) -> logging.Handler:
    """Replace the root handlers with one structured stderr handler; returns it.

    An invalid ``LOG_LEVEL`` falls back to INFO with a warning."""
    e = os.environ if env is None else env
    handler = logging.StreamHandler(stream or sys.stderr)
    fmt = e.get("LOG_FORMAT", "json").lower()
    handler.setFormatter(KeyValueFormatter() if fmt in ("text", "kv") else JsonFormatter())
    handler.addFilter(JobContextFilter())
    root = logging.getLogger()
    for h in list(root.handlers):
        root.removeHandler(h)
    root.addHandler(handler)
    requested = e.get("LOG_LEVEL", "INFO").upper()
    level = logging.getLevelName(requested)
    bad_level = not isinstance(level, int)
    root.setLevel(logging.INFO if bad_level else level)
    logging.captureWarnings(True)
    if bad_level:
        logging.getLogger(__name__).warning("invalid LOG_LEVEL %r; using INFO", requested)
    return handler


def install_excepthooks(logger: logging.Logger) -> None:
    """Uncaught exceptions are logged with their stack trace; the main-thread hook then chains to
    ``sys.__excepthook__`` (thread failures are only logged)."""

    def main_hook(t: type[BaseException], v: BaseException, tb: TracebackType | None) -> None:
        logger.critical("uncaught exception", exc_info=(t, v, tb))
        sys.__excepthook__(t, v, tb)

    def thread_hook(args: threading.ExceptHookArgs) -> None:
        if args.exc_value is None or args.exc_type is SystemExit:
            return
        name = args.thread.name if args.thread is not None else "?"
        logger.error(
            "uncaught exception in thread %s",
            name,
            exc_info=(args.exc_type, args.exc_value, args.exc_traceback),
        )

    sys.excepthook = main_hook
    threading.excepthook = thread_hook


def _fmt_counts(counts: Mapping[str, int]) -> str:
    return " ".join(f"{k}={v}" for k, v in counts.items()) or "-"


def summary_level(
    inputs: Mapping[str, int],
    outputs: Mapping[str, int],
    dropped: Mapping[str, int],
    *,
    require_input: bool = False,
    required_outputs: Collection[str] = (),
) -> tuple[int, str]:
    """(level, outcome) per the OBS-1 rule. ``inputs`` empty = unknown, never an ERROR, unless
    ``require_input`` (a source that is never legitimately empty, e.g. a jpdb deck).
    ``required_outputs``: output keys that must each be > 0 for non-empty input."""
    if require_input and sum(inputs.values()) == 0:
        return logging.ERROR, OUTCOME_NO_INPUT
    some_in = sum(inputs.values()) > 0
    if some_in and any(outputs.get(k, 0) <= 0 for k in required_outputs):
        return logging.ERROR, OUTCOME_ZERO
    if some_in and sum(outputs.values()) == 0:
        return logging.ERROR, OUTCOME_ZERO
    if any(v > 0 for v in dropped.values()):
        return logging.WARNING, OUTCOME_DROPPED
    return logging.INFO, OUTCOME_OK


def job_summary(
    logger: logging.Logger,
    what: str,
    *,
    inputs: Mapping[str, int],
    outputs: Mapping[str, int],
    dropped: Mapping[str, int] | None = None,
    require_input: bool = False,
    required_outputs: Collection[str] = (),
    max_level: int | None = None,
    warn_as_info: bool = False,
    **extra: Any,
) -> int:
    """Log the end-of-job count line for ``what`` and return the level used.

    ``inputs`` / ``outputs`` / ``dropped`` are named counts. ERROR when the inputs sum to > 0 and
    the outputs to 0 (or to nothing at all with ``require_input``); WARNING when any dropped or
    skipped count is > 0; INFO otherwise. ``max_level`` caps the level; ``warn_as_info`` demotes only WARNING (outcome is unchanged). ``extra`` (ids, flags) is added to the structured record.
    """
    drop = dict(dropped or {})
    level, outcome = summary_level(
        inputs, outputs, drop, require_input=require_input, required_outputs=required_outputs
    )
    if max_level is not None:
        level = min(level, max_level)
    if warn_as_info and level == logging.WARNING:
        level = logging.INFO
    job_id, kind = current_job()
    note = {
        OUTCOME_ZERO: " - ZERO OUTPUT from non-empty input",
        OUTCOME_NO_INPUT: " - ZERO INPUT where some is required",
        OUTCOME_DROPPED: " - rows dropped or skipped",
        OUTCOME_OK: "",
    }[outcome]
    ids = " ".join(f"{k}={v}" for k, v in extra.items())
    logger.log(
        level,
        "%s summary%s: in %s | out %s | dropped %s%s",
        what,
        f" {ids}" if ids else "",
        _fmt_counts(inputs),
        _fmt_counts(outputs),
        _fmt_counts(drop),
        note,
        extra={
            "job_id": job_id,
            "job_kind": kind,
            "fields": {
                "event": SUMMARY_EVENT,
                "what": what,
                "outcome": outcome,
                "in": dict(inputs),
                "out": dict(outputs),
                "dropped": drop,
                **extra,
            },
        },
    )
    return level


__all__ = [
    "OUTCOME_DROPPED",
    "OUTCOME_NO_INPUT",
    "OUTCOME_OK",
    "OUTCOME_ZERO",
    "SUMMARY_EVENT",
    "JobContextFilter",
    "JsonFormatter",
    "KeyValueFormatter",
    "configure",
    "current_job",
    "install_excepthooks",
    "job_scope",
    "job_summary",
    "summary_level",
]
