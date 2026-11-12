"""Fixture sanity tests. Run: uv run --with pytest pytest fixtures/tools"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def test_fsrs_vectors_count_and_coverage() -> None:
    doc = json.loads((ROOT / "fsrs" / "vectors.json").read_text())
    seqs = doc["sequences"]
    assert len(seqs) >= 200
    lapses = [s for s in seqs if any(r["state"] == "Relearning" for r in s["reviews"])]
    assert len(lapses) >= 50
    longgap = [s for s in seqs if any(r["gap_days"] >= 120 for r in s["reviews"])]
    assert len(longgap) >= 30
    assert doc["py_fsrs_version"]


def test_dict_subsets() -> None:
    jm = (ROOT / "dict" / "JMdict_subset.xml").read_text()
    assert jm.count("<entry>") == 2000
    kd = (ROOT / "dict" / "kanjidic2_subset.xml").read_text()
    assert kd.count("<character>") >= 100


def test_sentences() -> None:
    data = json.loads((ROOT / "parse" / "sentences.json").read_text())
    assert len(data["sentences"]) == 20
    for s in data["sentences"]:
        assert "".join(t["surface"] for t in s["tokens"]) == s["text"]
