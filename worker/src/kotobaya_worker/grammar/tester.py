"""``test_rules``: the rule tester the server's ``testGrammarRules`` (T506b) can call via the parse service.

Pure: rules and already-tokenised sentences in, matches or per-rule errors out. Tokenising the
sentences (``parse.pipeline.parse_sentences``) is the caller's job.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any

from kotobaya_worker.grammar.dsl import GrammarRuleError, Pattern, parse_rule
from kotobaya_worker.grammar.matcher import find_matches, to_mtokens
from kotobaya_worker.parse.segment import utf16_slice


@dataclass(frozen=True)
class TestSentence:
    __test__ = False  # not a pytest class

    text: str
    tokens: Sequence[Any]  # C-12 token objects or objects with the same attributes


@dataclass(frozen=True)
class RuleError:
    rule_index: int
    message: str
    column: int

    def to_json(self) -> dict[str, Any]:
        return {"rule_index": self.rule_index, "message": self.message, "column": self.column}


@dataclass(frozen=True)
class RuleMatch:
    rule_index: int
    sentence_index: int
    start_offset: int  # UTF-16, inclusive
    end_offset: int  # UTF-16, exclusive
    text: str

    def to_json(self) -> dict[str, Any]:
        return {
            "rule_index": self.rule_index,
            "sentence_index": self.sentence_index,
            "start_offset": self.start_offset,
            "end_offset": self.end_offset,
            "text": self.text,
        }


@dataclass(frozen=True)
class RuleTestResult:
    __test__ = False

    matches: tuple[RuleMatch, ...]
    errors: tuple[RuleError, ...]

    def to_json(self) -> dict[str, Any]:
        return {
            "matches": [m.to_json() for m in self.matches],
            "errors": [e.to_json() for e in self.errors],
        }


def test_rules(rules: Sequence[str], sentences: Sequence[TestSentence]) -> RuleTestResult:
    """Every match of every valid rule in every sentence; each invalid rule gives one error.

    Matches are ordered by (sentence, start, rule, end); an invalid rule does not stop the others.
    """
    errors: list[RuleError] = []
    compiled: list[tuple[int, Pattern]] = []
    for k, rule in enumerate(rules):
        try:
            compiled.append((k, parse_rule(rule)))
        except GrammarRuleError as e:
            errors.append(RuleError(k, e.message, e.column))
    matches: list[RuleMatch] = []
    for si, sent in enumerate(sentences):
        toks = to_mtokens(sent.tokens)
        for k, pattern in compiled:
            for m in find_matches(pattern, toks):
                text = utf16_slice(sent.text, m.start, m.end)
                matches.append(RuleMatch(k, si, m.start, m.end, text))
    matches.sort(key=lambda m: (m.sentence_index, m.start_offset, m.rule_index, m.end_offset))
    return RuleTestResult(tuple(matches), tuple(errors))


test_rules.__test__ = False  # type: ignore[attr-defined]

__all__ = ["RuleError", "RuleMatch", "RuleTestResult", "TestSentence", "test_rules"]
