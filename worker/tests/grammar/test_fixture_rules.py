"""T506a acceptance: 50+ hand-written rules over real Sudachi tokens.

Every rule set matches each positive example with a span covering the marked grammar (and not much
more), and matches none of its negatives, under two tokenisations of the real parser.
"""

from __future__ import annotations

import pytest

from kotobaya_worker.grammar import TestSentence, parse_rule, test_rules
from kotobaya_worker.parse.segment import utf16_slice

from .fixture_support import TOKENISERS, FixturePoint, load_points, seed_patterns

SLACK = 6  # UTF-16 units a span may extend on each side of the marked text (verb stem, ending)
POINTS = load_points()


def test_fixture_shape() -> None:
    assert len(POINTS) >= 50
    assert len({p.pattern for p in POINTS}) == len(POINTS)
    seed = seed_patterns()
    for p in POINTS:
        assert seed.get(p.pattern) == p.jlpt, f"{p.pattern} is not an N{p.jlpt} seed point"
        assert p.jlpt in (1, 2, 3)
        assert len(p.positive) >= 2 and len(p.negative) >= 2, p.pattern
        assert all(ex.marks for ex in p.positive), p.pattern
        assert not any(ex.marks for ex in p.negative), p.pattern
        for rule in p.rules:
            parse_rule(rule)


def _run(point: FixturePoint, tokeniser: str, positive: bool) -> list[str]:
    tok = TOKENISERS[tokeniser]
    examples = point.positive if positive else point.negative
    sents = [TestSentence(ex.text, tok(ex.text)) for ex in examples]
    res = test_rules(point.rules, sents)
    assert not res.errors, res.errors
    problems: list[str] = []
    for si, ex in enumerate(examples):
        got = [m for m in res.matches if m.sentence_index == si]
        if not positive:
            if got:
                problems.append(f"negative matched: {ex.text} -> {[m.text for m in got]}")
            continue
        for s, e in ex.marks:
            ok = any(
                s - SLACK <= m.start_offset <= s and e <= m.end_offset <= e + SLACK for m in got
            )
            if not ok:
                toks = [
                    (t["surface"] if isinstance(t, dict) else t.surface) for t in sents[si].tokens
                ]
                problems.append(
                    f"positive missed {utf16_slice(ex.text, s, e)!r} in {ex.text} "
                    f"-> {[m.text for m in got]} tokens={toks}"
                )
    return problems


@pytest.mark.parametrize("tokeniser", sorted(TOKENISERS))
@pytest.mark.parametrize("point", POINTS, ids=[p.pattern for p in POINTS])
def test_rule_matches_positives_and_no_negatives(point: FixturePoint, tokeniser: str) -> None:
    problems = _run(point, tokeniser, True) + _run(point, tokeniser, False)
    assert not problems, "\n".join(problems)
