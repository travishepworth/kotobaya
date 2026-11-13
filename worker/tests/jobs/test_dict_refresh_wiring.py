from __future__ import annotations

import json

import pytest

from kotobaya_worker.jobs.dict_refresh import DictSources
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
    }


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
