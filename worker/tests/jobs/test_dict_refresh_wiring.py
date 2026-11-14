from __future__ import annotations

import json

import pytest

from kotobaya_worker.jobs.dict_refresh import DictSources
from kotobaya_worker.runner import API_JOB_KINDS
from kotobaya_worker.runner.__main__ import build_registry

SHA = "0" * 64


def test_registry_has_all_registered_kinds(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DATABASE_URL", "jdbc:postgresql://localhost/x")
    assert set(build_registry().kinds()) == {
        "dict-refresh",
        "jpdb-history-import",
        "media-import",
        "media-parse",
        "jpdb-deck-import",
        "grammar-detect",
        "tts",
        "fsrs-optimize",
        "deck-build",
    }


def test_every_api_job_kind_has_a_handler(monkeypatch: pytest.MonkeyPatch) -> None:
    """The worker owns every public JobKind: a kind without a handler would sit queued forever."""
    monkeypatch.setenv("DATABASE_URL", "jdbc:postgresql://localhost/x")
    assert set(build_registry().kinds()) == set(API_JOB_KINDS)


def test_sources_json_priority_and_fingerprint() -> None:
    d = {"sha256": SHA}
    cfg = {
        "jmdict": d,
        "kanjidic": d,
        "krad": d,
        "freq_lists": {
            "jpdb": {**d, "url": "http://x/a.zip"},
            "bccwj": {**d, "url": "http://x/b.zip"},
        },
    }
    src = DictSources.from_json(json.dumps(cfg))
    assert [(s, f.config.priority) for s, f in src.freq.items()] == [("jpdb", 1), ("bccwj", 2)]
    assert "freq:bccwj" in src.fingerprint(["jpdb", "bccwj"])
    assert "freq:bccwj" not in src.fingerprint(["jpdb"])


def _base() -> dict[str, object]:
    d = {"sha256": SHA}
    return {"jmdict": d, "kanjidic": d, "krad": d}


def test_jlpt_config_default_null_and_errors() -> None:
    default = DictSources.from_json(json.dumps(_base()))
    assert default.jlpt is not None and list(default.jlpt) == ["n5", "n4", "n3", "n2", "n1"]
    assert "jlpt" in default.fingerprint([])
    off = DictSources.from_json(json.dumps({**_base(), "jlpt": None}))
    assert off.jlpt is None and "jlpt" not in off.fingerprint([])
    with pytest.raises(ValueError, match="jlpt"):
        DictSources.from_json(json.dumps({**_base(), "jlpt": {"n5": {"url": "u", "sha256": SHA}}}))
    levels = {lv: {"url": "u", "sha256": SHA} for lv in ("n5", "n4", "n3", "n2", "n1")}
    levels["n3"] = {"url": "u"}
    with pytest.raises(ValueError, match="sha256"):
        DictSources.from_json(json.dumps({**_base(), "jlpt": levels}))
