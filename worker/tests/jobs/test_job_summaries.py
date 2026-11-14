"""OBS-1w: every job handler ends with a count summary; drops WARN, zero output ERRORs."""

from __future__ import annotations

import json
import logging
import uuid
from collections.abc import Mapping
from pathlib import Path
from typing import Any

import pytest

from kotobaya_worker.decks.build import Plan, log_build_summary
from kotobaya_worker.dict.models import VocabListReport
from kotobaya_worker.dict.vocab import _log_line
from kotobaya_worker.grammar.detect import PointRules
from kotobaya_worker.grammar.job import log_detect_summary, run_fan_out
from kotobaya_worker.importers.jpdb_deck.handler import log_deck_summary
from kotobaya_worker.importers.jpdb_deck.plan import DeckPlan, DeckStats
from kotobaya_worker.importers.jpdb_history import ImportPlan, InMemoryRefLookup, job_result
from kotobaya_worker.importers.jpdb_history.handler import Context, run
from kotobaya_worker.jobs.dict_refresh import log_refresh_summary
from kotobaya_worker.media.pipeline.plan import log_media_summary
from kotobaya_worker.optimizer.job import log_optimize_summary
from kotobaya_worker.tts import run_tts
from kotobaya_worker.tts.job import log_tts_summary


def _summaries(caplog: pytest.LogCaptureFixture, what: str) -> list[logging.LogRecord]:
    return [
        r
        for r in caplog.records
        if isinstance(getattr(r, "fields", None), dict)
        and r.fields["event"] == "job_summary"  # type: ignore[attr-defined]
        and r.fields["what"] == what  # type: ignore[attr-defined]
    ]


@pytest.fixture(autouse=True)
def _info(caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level(logging.INFO)


# ------------------------------------------------------------------ jpdb-history-import
class FakeCtx:
    def __init__(self, payload: dict[str, Any]) -> None:
        self.payload = payload
        self.job_id = uuid.uuid4()

    def progress(self, fraction: float, message: str | None = None) -> None:
        pass

    def check_cancelled(self) -> None:
        pass


def _row(vid: int, spelling: str, reading: str) -> dict[str, Any]:
    return {
        "vid": vid,
        "spelling": spelling,
        "reading": reading,
        "reviews": [{"timestamp": 5, "grade": "okay"}],
    }


def _apply(plan: ImportPlan, ctx: Context) -> Mapping[str, Any]:
    return job_result(plan)


def _history(export: dict[str, Any], payload: dict[str, Any] | None = None) -> Mapping[str, Any]:
    ref = InMemoryRefLookup()
    ref.add(1, kanji=["猫"], readings=["ねこ"])
    ctx = FakeCtx({"blob_hash": "ab", **(payload or {})})
    return run(ctx, lambda h: json.dumps(export).encode(), ref, _apply)


def test_jpdb_history_zero_card_import_logs_error(caplog: pytest.LogCaptureFixture) -> None:
    """The 0.1.9 shape: rows in, nothing maps, 0 cards -> ERROR (plan and final summary)."""
    out = _history({"cards_vocabulary_jp_en": [_row(9, "犬", "いぬ"), _row(8, "鳥", "とり")]})
    assert out["cards_created"] == 0
    (final,) = _summaries(caplog, "jpdb-history-import")
    assert final.levelno == logging.ERROR and "ZERO OUTPUT" in final.getMessage()
    assert final.fields["dropped"]["unmapped"] == 2  # type: ignore[attr-defined]
    (plan,) = _summaries(caplog, "jpdb-history-import plan")
    assert plan.levelno == logging.INFO  # only the final summary escalates (OBS-1w2)


def test_jpdb_history_end_to_end_one_warning_and_result_unchanged(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """Real handler: plan line is INFO, final is the only WARNING, result equals the plan's."""
    export = {"cards_vocabulary_jp_en": [_row(1, "猫", "ねこ"), _row(9, "犬", "いぬ")]}
    out = _history(export)
    ref = InMemoryRefLookup()
    ref.add(1, kanji=["猫"], readings=["ねこ"])
    from kotobaya_worker.importers.jpdb_history import ImportOptions, plan_import

    assert out == job_result(plan_import(export, ref, ImportOptions.from_payload({})))
    (plan,) = _summaries(caplog, "jpdb-history-import plan")
    (final,) = _summaries(caplog, "jpdb-history-import")
    assert plan.levelno == logging.INFO and final.levelno == logging.WARNING
    assert [r for r in caplog.records if r.levelno >= logging.WARNING] == [final]


def test_jpdb_history_empty_export_logs_error(caplog: pytest.LogCaptureFixture) -> None:
    _history({})
    (final,) = _summaries(caplog, "jpdb-history-import")
    assert final.levelno == logging.ERROR and final.fields["outcome"] == "zero_input"  # type: ignore[attr-defined]


def test_jpdb_history_partial_mapping_warns(caplog: pytest.LogCaptureFixture) -> None:
    out = _history({"cards_vocabulary_jp_en": [_row(1, "猫", "ねこ"), _row(9, "犬", "いぬ")]})
    assert out["cards_created"] == 1
    (final,) = _summaries(caplog, "jpdb-history-import")
    assert final.levelno == logging.WARNING
    assert final.fields["in"]["rows"] == 2 and final.fields["out"]["cards_created"] == 1  # type: ignore[attr-defined]


def test_jpdb_history_clean_import_is_info(caplog: pytest.LogCaptureFixture) -> None:
    _history({"cards_vocabulary_jp_en": [_row(1, "猫", "ねこ")]})
    (final,) = _summaries(caplog, "jpdb-history-import")
    assert final.levelno == logging.INFO


# ------------------------------------------------------------------ jpdb-deck-import
def _deck_plan(**stats: int) -> DeckPlan:
    return DeckPlan(stats=DeckStats(**stats))


def test_jpdb_deck_zero_rows_logs_error(caplog: pytest.LogCaptureFixture) -> None:
    """BUG-11 / 0.1.9: a deck that fetched 0 rows published an empty media; now ERROR."""
    log_deck_summary("123", 0, _deck_plan(), {"media_id": "m", "unique_entries": 0})
    (s,) = _summaries(caplog, "jpdb-deck-import")
    assert s.levelno == logging.ERROR


def test_jpdb_deck_all_unmapped_logs_error(caplog: pytest.LogCaptureFixture) -> None:
    log_deck_summary("123", 5, _deck_plan(fetched=5, unmapped=5), {})
    (s,) = _summaries(caplog, "jpdb-deck-import")
    assert s.levelno == logging.ERROR


def test_jpdb_deck_some_unmapped_or_skipped_warns(caplog: pytest.LogCaptureFixture) -> None:
    log_deck_summary(
        "123", 6, _deck_plan(fetched=6, mapped=4, unmapped=1, skipped=1, merged_entries=4), {}
    )
    (s,) = _summaries(caplog, "jpdb-deck-import")
    assert s.levelno == logging.WARNING
    assert s.fields["dropped"] == {"unmapped": 1, "ignored": 0, "skipped": 1}  # type: ignore[attr-defined]


# ------------------------------------------------------------------ media-import / media-parse
def _media(**kw: int) -> dict[str, Any]:
    base = {"media_id": "m", "content_version": 1, "segments": 1, "sentences": 0, "tokens": 0,
            "unmapped_tokens": 0, "unique_entries": 0, "unmapped": []}  # fmt: skip
    base.update(kw)
    return base


@pytest.mark.parametrize("kind", ["media-import", "media-parse"])
def test_media_levels(kind: str, caplog: pytest.LogCaptureFixture) -> None:
    assert log_media_summary(kind, _media(sentences=3, tokens=9)) == logging.ERROR
    assert (
        log_media_summary(
            kind, _media(sentences=30, tokens=90, unmapped_tokens=9, unique_entries=5)
        )
        == logging.WARNING
    )
    assert log_media_summary(kind, _media(sentences=3, tokens=9, unique_entries=7)) == logging.INFO
    assert [r.levelno for r in _summaries(caplog, kind)] == [
        logging.ERROR,
        logging.WARNING,
        logging.INFO,
    ]


def test_media_few_unmapped_tokens_is_info(caplog: pytest.LogCaptureFixture) -> None:
    ok = _media(sentences=30, tokens=100, unmapped_tokens=5, unique_entries=5)
    assert log_media_summary("media-import", ok) == logging.INFO
    over = _media(sentences=30, tokens=100, unmapped_tokens=6, unique_entries=5)
    assert log_media_summary("media-import", over) == logging.WARNING
    zero = _media(sentences=30, tokens=100, unmapped_tokens=1, unique_entries=0)
    assert log_media_summary("media-import", zero) == logging.ERROR


# ------------------------------------------------------------------ dict-refresh / vocab lists
def test_dict_refresh_zero_entries_logs_error(caplog: pytest.LogCaptureFixture) -> None:
    lvl = log_refresh_summary({"release_id": 2, "entries": 0, "retired": 0}, 3, 4, resumed=False)
    assert lvl == logging.ERROR
    assert _summaries(caplog, "dict-refresh")[0].levelno == logging.ERROR


def test_dict_refresh_unmatched_vocab_rows_warn(caplog: pytest.LogCaptureFixture) -> None:
    result = {"release_id": 2, "entries": 100, "retired": 0,
              "vocab_lists": [{"id": "jlpt-n5", "entries": 10, "unmatched": 2}]}  # fmt: skip
    assert log_refresh_summary(result, 4, 4, resumed=False) == logging.WARNING
    (s,) = _summaries(caplog, "dict-refresh")
    assert s.fields["dropped"] == {"vocab_unmatched": 2}  # type: ignore[attr-defined]


def test_dict_refresh_no_artifacts_logs_error() -> None:
    assert log_refresh_summary({"entries": 100}, 3, 0, resumed=True) == logging.ERROR


def test_vocab_list_levels(caplog: pytest.LogCaptureFixture) -> None:
    assert _log_line(VocabListReport("jlpt-n5", 10, 0, 0, 0, 10)) == logging.ERROR
    assert _log_line(VocabListReport("jlpt-n4", 10, 8, 0, 0, 2)) == logging.WARNING
    assert _log_line(VocabListReport("jlpt-n3", 10, 9, 1, 0, 0)) == logging.INFO
    assert len(_summaries(caplog, "vocab list jlpt-n5")) == 1


# ------------------------------------------------------------------ deck-build
def test_deck_build_unmatched_rows_warn(caplog: pytest.LogCaptureFixture) -> None:
    plan = Plan("d", "jlpt-n5", 1, [1, 2, 3], 2, set())
    assert log_build_summary(plan, {"changed": True}) == logging.WARNING
    (s,) = _summaries(caplog, "deck-build")
    assert s.fields["in"] == {"list_rows": 5} and s.fields["out"] == {"items": 3}  # type: ignore[attr-defined]
    assert log_build_summary(Plan("d", "freq-jpdb-1000", 1, [1], 0, set()), {}) == logging.INFO


# ------------------------------------------------------------------ tts
class _TtsCtx:
    def __init__(self) -> None:
        self.payload: dict[str, Any] = {"text": "猫", "voice": "3"}

    def check_cancelled(self) -> None:
        pass


class _Synth:
    def synthesize(self, text: str, voice: str) -> bytes:
        import io
        import wave

        buf = io.BytesIO()
        with wave.open(buf, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(1000)
            w.writeframes(b"\x00\x00" * 250)
        return buf.getvalue()


class _Store:
    def lookup(self, key: str) -> dict[str, Any] | None:
        return None

    def store(self, path: Path, digest: str) -> None:
        pass


def _convert(wav: bytes, out: Path) -> None:
    out.write_bytes(b"OggS")


def test_tts_logs_summary(caplog: pytest.LogCaptureFixture) -> None:
    run_tts(
        _TtsCtx(),
        synth=_Synth(),
        convert=_convert,
        cache=_Store(),
        sink=_Store(),
    )
    (s,) = _summaries(caplog, "tts")
    assert s.levelno == logging.INFO and s.fields["out"] == {"duration_ms": 250}  # type: ignore[attr-defined]
    assert log_tts_summary("猫", "3", {"duration_ms": 0}, size_bytes=4, cached=False) == (
        logging.ERROR
    )


# ------------------------------------------------------------------ fsrs-optimize
def test_optimizer_levels(caplog: pytest.LogCaptureFixture) -> None:
    declined = {"published": False, "review_count": 20, "reason": "too_few_reviews"}
    assert log_optimize_summary("p", 20, declined) == logging.INFO
    bad = {"published": False, "review_count": 900, "reason": "out_of_bounds"}
    assert log_optimize_summary("p", 900, bad) == logging.WARNING
    assert log_optimize_summary("p", 0, {"published": False, "reason": "too_few_reviews"}) == (
        logging.INFO
    )
    assert len(_summaries(caplog, "fsrs-optimize")) == 3


# ------------------------------------------------------------------ grammar-detect
def test_grammar_detect_levels(caplog: pytest.LogCaptureFixture) -> None:
    assert log_detect_summary({"media_id": "m", "changed": False, "spans": 0}) == logging.INFO
    assert log_detect_summary({"media_id": "m", "spans": 3, "invalid_rules": 1}) == logging.WARNING
    assert log_detect_summary({"media_id": "m", "skipped": "media_deleted"}) == logging.WARNING
    assert len(_summaries(caplog, "grammar-detect")) == 3


class _Rules:
    def load(self) -> list[PointRules]:
        return []


class _Queue:
    def release(self, job_id: uuid.UUID) -> None:
        pass

    def media_ids(self) -> list[str]:
        return ["a", "b"]

    def enqueue(self, media_id: str, digest: str) -> bool:
        return media_id == "a"


def test_grammar_fan_out_summary(caplog: pytest.LogCaptureFixture) -> None:
    run_fan_out(FakeCtx({}), rules=_Rules(), queue=_Queue())
    (s,) = _summaries(caplog, "grammar-detect fan-out")
    assert s.levelno == logging.INFO
    assert s.fields["out"] == {"enqueued": 1, "already_queued": 1}  # type: ignore[attr-defined]
