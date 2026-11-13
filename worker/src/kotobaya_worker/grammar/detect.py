"""Grammar points' rules -> detected spans for a media's sentences (pure).

``detection_rules`` is ``[{"rule": "<text>", "draft": bool}]`` (V1__init.sql). Drafts are skipped,
invalid rules are reported and skipped; ``rule_index`` is the index into the whole array, drafts
included. The result is exactly the ``grammar_span`` records of a content archive (SNAPSHOT.md 3,
rule 8): one per (sentence, point, start, end) — the lowest matching ``rule_index`` wins — ordered
by (sentence position, start, end, point).
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from typing import Any

from kotobaya_worker.grammar.dsl import GrammarRuleError, Pattern, parse_rule
from kotobaya_worker.grammar.matcher import find_matches, to_mtokens
from kotobaya_worker.media.publish.snapshot import GrammarSpanRec


@dataclass(frozen=True)
class PointRules:
    """One live grammar point and its raw ``detection_rules`` value."""

    grammar_point_id: str
    detection_rules: Any


@dataclass(frozen=True)
class CompiledPoint:
    grammar_point_id: str
    rules: tuple[tuple[int, Pattern], ...]  # (rule_index, pattern)


@dataclass(frozen=True)
class RuleProblem:
    grammar_point_id: str
    rule_index: int
    message: str


@dataclass(frozen=True)
class DetectSentence:
    id: str
    text: str
    tokens: Sequence[Any]


def compile_points(points: Iterable[PointRules]) -> tuple[list[CompiledPoint], list[RuleProblem]]:
    out: list[CompiledPoint] = []
    problems: list[RuleProblem] = []
    for p in sorted(points, key=lambda p: p.grammar_point_id):
        rules = p.detection_rules if isinstance(p.detection_rules, list) else []
        compiled: list[tuple[int, Pattern]] = []
        for k, entry in enumerate(rules):
            if not isinstance(entry, dict) or not isinstance(entry.get("rule"), str):
                problems.append(RuleProblem(p.grammar_point_id, k, "not a {rule, draft} object"))
                continue
            if entry.get("draft") is True:
                continue
            try:
                compiled.append((k, parse_rule(entry["rule"])))
            except GrammarRuleError as e:
                problems.append(RuleProblem(p.grammar_point_id, k, str(e)))
        if compiled:
            out.append(CompiledPoint(p.grammar_point_id, tuple(compiled)))
    return out, problems


def rules_digest(points: Iterable[PointRules]) -> str:
    """A short stable hash of every point's rules (job dedupe keys: new rules -> new key)."""
    canon = sorted((p.grammar_point_id, p.detection_rules) for p in points)
    raw = json.dumps(canon, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]


def _len16(text: str) -> int:
    return len(text.encode("utf-16-le")) // 2


def detect_spans(
    points: Sequence[CompiledPoint], sentences: Iterable[DetectSentence]
) -> list[GrammarSpanRec]:
    out: list[GrammarSpanRec] = []
    for sent in sentences:
        toks = to_mtokens(sent.tokens)
        limit = _len16(sent.text)
        best: dict[tuple[int, int, str], int] = {}
        for point in points:
            for k, pattern in point.rules:
                for m in find_matches(pattern, toks):
                    if not 0 <= m.start < m.end <= limit:
                        continue  # tokens inconsistent with the text: never publish a bad span
                    key = (m.start, m.end, point.grammar_point_id)
                    if key not in best or k < best[key]:
                        best[key] = k
        out.extend(GrammarSpanRec(sent.id, gp, s, e, k) for (s, e, gp), k in sorted(best.items()))
    return out


__all__ = [
    "CompiledPoint",
    "DetectSentence",
    "PointRules",
    "RuleProblem",
    "compile_points",
    "detect_spans",
    "rules_digest",
]
