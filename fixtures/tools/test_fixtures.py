"""Fixture sanity tests. Run: uv run --with pytest pytest fixtures/tools"""
import json
import re
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


NO_LEMMA = {"punct", "name", "symbol", "number"}  # pos classes with no JMdict lemma / seq


def _entries(name: str) -> tuple[set[str], set[int]]:
    xml = (ROOT / "dict" / name).read_text(encoding="utf-8")
    forms = set(re.findall(r"<(?:keb|reb)>(.*?)</(?:keb|reb)>", xml))
    seqs = {int(m) for m in re.findall(r"<ent_seq>(\d+)</ent_seq>", xml)}
    return forms, seqs


def _sentences() -> list[dict]:
    return json.loads((ROOT / "parse" / "sentences.json").read_text())["sentences"]


def test_sentences() -> None:
    data = _sentences()
    assert len(data) == 200
    assert [s["id"] for s in data] == [f"s{i:02d}" for i in range(1, 201)]
    for s in data:
        assert "".join(t["surface"] for t in s["tokens"]) == s["text"], s["id"]
        for t in s["tokens"]:
            assert set(t) == {"surface", "lemma", "reading", "pos", "seq"}, (s["id"], t)


def test_parse_extra_is_disjoint_from_subset() -> None:
    _, sub = _entries("JMdict_subset.xml")
    _, extra = _entries("JMdict_parse_extra.xml")
    assert extra and not (sub & extra)


def test_sentence_lemmas_resolve_in_subset_or_extra() -> None:
    forms = _entries("JMdict_subset.xml")[0] | _entries("JMdict_parse_extra.xml")[0]
    for s in _sentences():
        for t in s["tokens"]:
            if t["pos"] not in NO_LEMMA:
                assert t["lemma"] in forms, (s["id"], t["lemma"])


def test_sentence_seqs_exist() -> None:
    seqs = _entries("JMdict_subset.xml")[1] | _entries("JMdict_parse_extra.xml")[1]
    with_seq = 0
    for s in _sentences():
        for t in s["tokens"]:
            if t["pos"] in NO_LEMMA:
                assert t["seq"] is None, (s["id"], t)
            elif t["seq"] is not None:
                with_seq += 1
                assert t["seq"] in seqs, (s["id"], t)
    assert with_seq > 800
