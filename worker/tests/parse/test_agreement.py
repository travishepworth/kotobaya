"""Parser fixture agreement (CLAUDE.md "Tests that must exist"; T403a acceptance).

Token-level agreement on fixtures/parse/sentences.json: a produced token agrees with the
fixture when its UTF-16 span (so its surface) AND its lemma are equal. Punctuation is not a
token on either side. Score is F1 = 2 * matched / (expected + produced), so both missing and
extra tokens count. With sudachidict_full the bar is 97 % (spec); other dictionaries must not
fall below their recorded baseline (ADR-019).
"""

from __future__ import annotations

from collections.abc import Callable

import pytest

from kotobaya_worker.parse import segment
from kotobaya_worker.parse.sudachi import DictName, get_analyzer, is_dictionary_installed

from .conftest import FixtureSentence

FULL_DICT_THRESHOLD = 0.97
# Recorded baselines (F1 on the 200-sentence fixture); raise them when the parser improves.
BASELINES: dict[DictName, float] = {"full": 0.978, "core": 0.976}  # measured 0.9785 / 0.9762

Span = tuple[int, int, str]


def _expected(s: FixtureSentence) -> list[Span]:
    out: list[Span] = []
    pos = 0
    for t in s.tokens:
        n = len(t.surface.encode("utf-16-le")) // 2
        if t.pos != "punct":
            out.append((pos, pos + n, t.lemma))
        pos += n
    return out


def _render(text: str, spans: list[Span]) -> str:
    u = text.encode("utf-16-le")
    return " ".join(f"{u[2 * a : 2 * b].decode('utf-16-le')}<{lemma}" for a, b, lemma in spans)


def _agreement(
    sentences: tuple[FixtureSentence, ...], dict_name: DictName, is_key: Callable[[str], bool]
) -> tuple[float, list[str]]:
    analyzer = get_analyzer(dict_name)
    matched = expected_n = produced_n = 0
    diffs: list[str] = []
    for s in sentences:
        exp = _expected(s)
        got: list[Span] = [(t.s, t.e, t.lemma) for t in segment(s.text, is_key, analyzer)]
        common = len(set(exp) & set(got))
        matched += common
        expected_n += len(exp)
        produced_n += len(got)
        if common != len(exp) or common != len(got):
            diffs.append(
                f"{s.id} {s.text}\n  expected: {_render(s.text, exp)}\n"
                f"  got:      {_render(s.text, got)}"
            )
    return 2 * matched / (expected_n + produced_n), diffs


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
def test_parser_fixture_agreement(
    dict_name: DictName,
    sentences: tuple[FixtureSentence, ...],
    is_key: Callable[[str], bool],
) -> None:
    assert len(sentences) == 200
    score, diffs = _agreement(sentences, dict_name, is_key)
    floor = BASELINES[dict_name]
    if dict_name == "full":
        floor = max(floor, FULL_DICT_THRESHOLD)
    print(f"\nagreement[{dict_name}] = {score:.4f} ({len(diffs)} sentences differ)")
    assert score >= floor, f"agreement[{dict_name}] {score:.4f} < {floor}\n" + "\n".join(diffs)
