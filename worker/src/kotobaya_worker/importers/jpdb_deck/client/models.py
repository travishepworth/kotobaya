"""Typed results of a jpdb deck fetch."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol


@dataclass(frozen=True)
class DeckWord:
    vid: int
    spelling: str
    reading: str
    occurrence_count: int | None
    global_rank_bucket: int | None


@dataclass(frozen=True)
class SkippedRow:
    """A row the parser could not read; kept so nothing is dropped silently."""

    raw: str
    reason: str


@dataclass
class DeckVocabulary:
    title: str
    words: list[DeckWord] = field(default_factory=list)
    skipped: list[SkippedRow] = field(default_factory=list)
    raw_rows: int = 0


class JpdbDeckSource(Protocol):
    def fetch_deck(self, jpdb_id: str) -> DeckVocabulary: ...
