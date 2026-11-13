"""Helpers for the T403b tests: the fixture JMdict as an in-memory lookup, golden vectors."""

from __future__ import annotations

import json
from functools import lru_cache
from itertools import chain
from pathlib import Path
from typing import Any

from kotobaya_worker.dict.jmdict import parse_jmdict
from kotobaya_worker.dict.models import Entry
from kotobaya_worker.parse.match import InMemoryMatchLookup, Token

from .conftest import DICT_FIXTURES, ROOT

PARSER_CASES = ROOT / "contracts" / "api" / "examples" / "parser-cases.json"
TOKEN_SCHEMA = ROOT / "contracts" / "api" / "token.schema.json"


@lru_cache(maxsize=1)
def fixture_entries() -> tuple[Entry, ...]:
    return tuple(chain.from_iterable(parse_jmdict(p) for p in DICT_FIXTURES))


@lru_cache(maxsize=1)
def fixture_lookup() -> InMemoryMatchLookup:
    return InMemoryMatchLookup.from_entries(fixture_entries())


@lru_cache(maxsize=1)
def parser_cases() -> dict[str, Any]:
    data: dict[str, Any] = json.loads(PARSER_CASES.read_text(encoding="utf-8"))
    return data


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def token_from_json(d: dict[str, Any]) -> Token:
    return Token(
        s=d["s"],
        e=d["e"],
        surface=d["surface"],
        lemma=d["lemma"],
        reading=d["reading"],
        pos=tuple(d["pos"]),
        cform=d["cform"],
        seq=d["seq"],
        cls=d["class"],
        override=d["override"],
    )


def len16(text: str) -> int:
    return len(text.encode("utf-16-le")) // 2


def slice16(text: str, s: int, e: int) -> str:
    return text.encode("utf-16-le")[2 * s : 2 * e].decode("utf-16-le")


def assert_array_invariants(text: str, tokens: list[dict[str, Any]]) -> None:
    """PARSER.md 1.2 (1)-(3): ascending, non-overlapping, surrogate-safe, surface = text[s:e]."""
    units = text.encode("utf-16-le")
    prev = 0
    for t in tokens:
        assert prev <= t["s"] < t["e"] <= len16(text), (text, t)
        for off in (t["s"], t["e"]):
            if off < len16(text):
                cu = int.from_bytes(units[2 * off : 2 * off + 2], "little")
                assert not 0xDC00 <= cu <= 0xDFFF, f"offset {off} splits a surrogate pair"
        assert slice16(text, t["s"], t["e"]) == t["surface"], (text, t)
        prev = t["e"]
