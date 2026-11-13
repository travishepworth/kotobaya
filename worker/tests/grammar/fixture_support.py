"""The hand-written rule fixture (fixtures/grammar-rules/rules.json) and real-Sudachi tokenisers for it."""

from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any

from kotobaya_worker.dict.jmdict import parse_jmdict
from kotobaya_worker.dict.normalize import normalize_form
from kotobaya_worker.parse.segment import segment

ROOT = Path(__file__).resolve().parents[3]
RULES_FIXTURE = ROOT / "fixtures" / "grammar-rules" / "rules.json"
SEED = ROOT / "fixtures" / "grammar-seed" / "grammar-seed.json"
DICT_FIXTURES = (
    ROOT / "fixtures" / "dict" / "JMdict_subset.xml",
    ROOT / "fixtures" / "dict" / "JMdict_parse_extra.xml",
)
OPEN, CLOSE = "【", "】"


@dataclass(frozen=True)
class Example:
    text: str  # markers removed
    marks: tuple[tuple[int, int], ...]  # UTF-16 ranges the grammar occupies


@dataclass(frozen=True)
class FixturePoint:
    pattern: str
    jlpt: int
    rules: tuple[str, ...]
    positive: tuple[Example, ...]
    negative: tuple[Example, ...]


def _len16(text: str) -> int:
    return len(text.encode("utf-16-le")) // 2


def parse_example(raw: str) -> Example:
    text, marks, start = "", [], -1
    for ch in raw:
        if ch == OPEN:
            start = _len16(text)
        elif ch == CLOSE:
            marks.append((start, _len16(text)))
        else:
            text += ch
    return Example(text, tuple(marks))


@lru_cache(maxsize=1)
def load_points() -> tuple[FixturePoint, ...]:
    raw: dict[str, Any] = json.loads(RULES_FIXTURE.read_text(encoding="utf-8"))
    return tuple(
        FixturePoint(
            p["pattern"],
            p["jlpt"],
            tuple(p["rules"]),
            tuple(parse_example(s) for s in p["positive"]),
            tuple(parse_example(s) for s in p["negative"]),
        )
        for p in raw["points"]
    )


@lru_cache(maxsize=1)
def seed_patterns() -> dict[str, int]:
    return {p["pattern"]: p["jlpt"] for p in json.loads(SEED.read_text(encoding="utf-8"))}


@lru_cache(maxsize=1)
def _jmdict_keys() -> frozenset[str]:
    keys: set[str] = set()
    for path in DICT_FIXTURES:
        for entry in parse_jmdict(path):
            for form in entry.kanji + entry.readings:
                keys.add(normalize_form(form.text))
    return frozenset(keys)


def _plain(text: str) -> list[Any]:
    """Sudachi mode C, no JMdict fallback or joins (what a full dictionary mostly keeps)."""
    return list(segment(text))


def _with_fixture_dict(text: str) -> list[Any]:
    """The production path with the small fixture JMdict: unknown C tokens re-split (B, then A);
    as C-12 JSON objects (the stored token shape)."""
    return [
        {
            "s": t.s,
            "e": t.e,
            "surface": t.surface,
            "lemma": t.lemma,
            "reading": t.reading,
            "pos": list(t.pos),
            "cform": t.cform,
        }
        for t in segment(text, _jmdict_keys().__contains__)
    ]


TOKENISERS: dict[str, Callable[[str], list[Any]]] = {
    "mode-c": _plain,
    "fixture-jmdict": _with_fixture_dict,
}
