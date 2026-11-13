"""Shared fixtures for parser tests: the 200-sentence fixture and a JMdict key predicate."""

from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any

import pytest

from kotobaya_worker.dict.jmdict import parse_jmdict
from kotobaya_worker.dict.normalize import normalize_form

ROOT = Path(__file__).resolve().parents[3]
PARSE_FIXTURE = ROOT / "fixtures" / "parse" / "sentences.json"
DICT_FIXTURES = (
    ROOT / "fixtures" / "dict" / "JMdict_subset.xml",
    ROOT / "fixtures" / "dict" / "JMdict_parse_extra.xml",
)


def pytest_configure(config: pytest.Config) -> None:
    config.addinivalue_line(
        "markers", "full_dict: needs sudachidict_full (skipped when it is not installed)"
    )


@dataclass(frozen=True)
class FixtureToken:
    surface: str
    lemma: str
    pos: str


@dataclass(frozen=True)
class FixtureSentence:
    id: str
    text: str
    tokens: tuple[FixtureToken, ...]


@lru_cache(maxsize=1)
def load_sentences() -> tuple[FixtureSentence, ...]:
    raw: dict[str, Any] = json.loads(PARSE_FIXTURE.read_text(encoding="utf-8"))
    return tuple(
        FixtureSentence(
            id=s["id"],
            text=s["text"],
            tokens=tuple(FixtureToken(t["surface"], t["lemma"], t["pos"]) for t in s["tokens"]),
        )
        for s in raw["sentences"]
    )


@lru_cache(maxsize=1)
def jmdict_keys() -> frozenset[str]:
    """``norm`` of every kanji and kana form in the fixture JMdict files (SCHEMA.md 4.5)."""
    keys: set[str] = set()
    for path in DICT_FIXTURES:
        for entry in parse_jmdict(path):
            for form in entry.kanji + entry.readings:
                keys.add(normalize_form(form.text))
    return frozenset(keys)


@pytest.fixture(scope="session")
def sentences() -> tuple[FixtureSentence, ...]:
    return load_sentences()


@pytest.fixture(scope="session")
def is_key() -> Callable[[str], bool]:
    keys = jmdict_keys()
    return keys.__contains__
