"""Parser fixture agreement with JMdict matching (CLAUDE.md "Tests that must exist"; T403b).

Token-level F1 on fixtures/parse/sentences.json counting surface (UTF-16 span) + lemma + seq,
through the full pipeline (:func:`parse_sentences`, no overrides) over the fixture JMdict
files. A produced token agrees with an expected one when span and lemma are equal and:

- the fixture ``seq`` is set: the produced ``seq`` is equal;
- the fixture ``seq`` is null for a number/symbol/punctuation/name token (null by policy): the produced one is null;
- the fixture ``seq`` is null for any other token: the fixture records "ambiguous" (its
  generator gives up when several JMdict entries fit), so any produced ``seq`` agrees.

Gating as in T403a: >= 97 % with sudachidict_full; other dictionaries hold their baseline.
"""

from __future__ import annotations

import json

import pytest

from kotobaya_worker.parse.pipeline import parse_sentences
from kotobaya_worker.parse.sudachi import DictName, get_analyzer, is_dictionary_installed

from .conftest import PARSE_FIXTURE
from .match_support import fixture_lookup

FULL_DICT_THRESHOLD = 0.97
# Measured 0.9777 (full) / 0.9754 (core); the one seq miss is 日 after a number, which
# Sudachi reads か (2083110) where the fixture says にち (2083100).
BASELINES: dict[DictName, float] = {"full": 0.977, "core": 0.975}
STRICT_NULL = frozenset({"number", "symbol", "punct", "name"})

Key = tuple[int, int, str]


def _dict_param(name: DictName) -> object:
    marks = [
        pytest.mark.skipif(
            not is_dictionary_installed(name), reason=f"sudachidict_{name} is not installed"
        )
    ]
    if name == "full":
        marks.append(pytest.mark.full_dict)
    return pytest.param(name, marks=marks, id=name)


@pytest.mark.parametrize("dict_name", [_dict_param("full"), _dict_param("core")])
def test_parser_fixture_agreement_with_seq(dict_name: DictName) -> None:
    raw = json.loads(PARSE_FIXTURE.read_text(encoding="utf-8"))["sentences"]
    assert len(raw) == 200
    texts = [s["text"] for s in raw]
    produced = parse_sentences(
        texts, None, None, lookup=fixture_lookup(), analyzer=get_analyzer(dict_name)
    )
    matched = expected_n = produced_n = wildcard = 0
    diffs: list[str] = []
    for s, toks in zip(raw, produced, strict=True):
        exp: dict[Key, tuple[int | None, str]] = {}
        pos16 = 0
        for t in s["tokens"]:
            n = len(t["surface"].encode("utf-16-le")) // 2
            if t["pos"] != "punct":
                exp[(pos16, pos16 + n, t["lemma"])] = (t["seq"], t["pos"])
            pos16 += n
        got = {(t.s, t.e, t.lemma): t.seq for t in toks}
        for key, (seq, pos) in exp.items():
            if key not in got:
                continue
            if seq is not None:
                ok = got[key] == seq
            elif pos in STRICT_NULL:
                ok = got[key] is None
            else:
                ok, wildcard = True, wildcard + 1
            if ok:
                matched += 1
            else:
                diffs.append(f"{s['id']} {key}: expected seq {seq}, got {got[key]}")
        expected_n += len(exp)
        produced_n += len(toks)
    score = 2 * matched / (expected_n + produced_n)
    print(f"\nagreement+seq[{dict_name}] = {score:.4f} (ambiguous-null tokens: {wildcard})")
    floor = BASELINES[dict_name]
    if dict_name == "full":
        floor = max(floor, FULL_DICT_THRESHOLD)
    assert score >= floor, f"{score:.4f} < {floor}\n" + "\n".join(diffs)
