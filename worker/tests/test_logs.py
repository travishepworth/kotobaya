"""OBS-1w: structured formatter, job scope, summary level rule."""

from __future__ import annotations

import io
import json
import logging

import pytest

from kotobaya_worker import logs


def test_summary_level_rule() -> None:
    assert logs.summary_level({"in": 3}, {"out": 3}, {})[0] == logging.INFO
    assert logs.summary_level({"in": 3}, {"out": 2}, {"skipped": 1})[0] == logging.WARNING
    assert logs.summary_level({"in": 3}, {"out": 0}, {"skipped": 3}) == (
        logging.ERROR,
        logs.OUTCOME_ZERO,
    )
    assert logs.summary_level({"in": 0}, {"out": 0}, {})[0] == logging.INFO
    assert logs.summary_level({"in": 0}, {"out": 0}, {}, require_input=True) == (
        logging.ERROR,
        logs.OUTCOME_NO_INPUT,
    )


def test_job_summary_record_carries_counts_and_job(caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level(logging.INFO)
    with logs.job_scope("j-1", "media-import"):
        level = logs.job_summary(
            logging.getLogger("t"),
            "media-import",
            inputs={"sentences": 4},
            outputs={"unique_entries": 0},
            media_id="m",
        )
    assert level == logging.ERROR
    (rec,) = caplog.records
    assert rec.levelno == logging.ERROR and "ZERO OUTPUT" in rec.getMessage()
    assert (
        getattr(rec, "job_id", None) == "j-1" and getattr(rec, "job_kind", None) == "media-import"
    )
    line = json.loads(logs.JsonFormatter().format(rec))
    assert line["event"] == "job_summary" and line["outcome"] == "zero_output"
    assert line["in"] == {"sentences": 4} and line["out"] == {"unique_entries": 0}
    assert line["job_id"] == "j-1" and line["kind"] == "media-import" and line["media_id"] == "m"


def test_configure_writes_one_json_line_per_event_with_stack_and_scope() -> None:
    buf = io.StringIO()
    root = logging.getLogger()
    saved = (list(root.handlers), root.level)
    try:
        logs.configure({}, stream=buf)
        log = logging.getLogger("kotobaya.test.obs")
        with logs.job_scope("j-2", "tts"):
            try:
                raise ValueError("bad")
            except ValueError:
                log.exception("it broke")
        log.info("outside")
    finally:
        for h in list(root.handlers):
            root.removeHandler(h)
        for h in saved[0]:
            root.addHandler(h)
        root.setLevel(saved[1])
    lines = buf.getvalue().splitlines()
    assert len(lines) == 2
    first, second = (json.loads(x) for x in lines)
    assert first["level"] == "ERROR" and first["job_id"] == "j-2" and first["kind"] == "tts"
    assert "ValueError: bad" in first["exc"] and first["exc"].startswith("Traceback")
    assert "job_id" not in second and second["msg"] == "outside"


def test_key_value_formatter_quotes_values() -> None:
    rec = logging.LogRecord("n", logging.WARNING, __file__, 1, "two words", None, None)
    out = logs.KeyValueFormatter().format(rec)
    assert 'msg="two words"' in out and "level=WARNING" in out and "\n" not in out


def test_invalid_log_level_falls_back_to_info() -> None:
    buf = io.StringIO()
    root = logging.getLogger()
    old_level, old_handlers = root.level, list(root.handlers)
    try:
        logs.configure({"LOG_LEVEL": "LOUD"}, buf)
        assert root.level == logging.INFO
        assert "invalid LOG_LEVEL" in buf.getvalue()
    finally:
        for h in list(root.handlers):
            root.removeHandler(h)
        for h in old_handlers:
            root.addHandler(h)
        root.setLevel(old_level)
