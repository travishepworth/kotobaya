"""The parser as the media pipeline sees it: one segment's sentences -> C-12 token arrays (PARSER.md 3 steps 1-4).

Media-scoped: sentence, media and global overrides apply (``parse_sentences`` with the media id and the
sentence ids the pipeline assigned before parsing).
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from typing import Any, Protocol

import psycopg

from kotobaya_worker.parse.match import MatchLookup
from kotobaya_worker.parse.match.postgres import MatchCache, PostgresMatchLookup, current_release
from kotobaya_worker.parse.overrides import OverrideRow, load_overrides
from kotobaya_worker.parse.pipeline import parse_sentences
from kotobaya_worker.parse.sudachi import Analyzer, get_analyzer

TokenArray = list[dict[str, Any]]


class SegmentParser(Protocol):
    def __call__(
        self, media_id: str, sentence_ids: Sequence[str], texts: Sequence[str]
    ) -> list[TokenArray]:
        """Tokens for each of ``texts`` (``sentence_ids`` parallel to it)."""
        ...


class StaticSegmentParser:
    """Fixed lookup and override rows (tests, offline use)."""

    def __init__(
        self,
        lookup: MatchLookup,
        analyzer: Analyzer | None = None,
        overrides: Sequence[OverrideRow] = (),
    ) -> None:
        self.lookup = lookup
        self.analyzer = analyzer
        self.overrides = list(overrides)

    def __call__(
        self, media_id: str, sentence_ids: Sequence[str], texts: Sequence[str]
    ) -> list[TokenArray]:
        out = parse_sentences(
            texts,
            media_id,
            sentence_ids,
            lookup=self.lookup,
            overrides=self.overrides,
            analyzer=self.analyzer,
        )
        return [[t.to_json() for t in ts] for ts in out]


class PgSegmentParser:
    """Current dictionary release and live overrides, read per segment (``conn``: tuple rows, autocommit).

    Lookups are cached per release; an override committed while a job runs applies from the next segment
    (a later re-parse picks up anything missed).
    """

    def __init__(
        self,
        conn: psycopg.Connection[Any],
        load_analyzer: Callable[[], Analyzer] = get_analyzer,
    ) -> None:
        self._c = conn
        self._load_analyzer = load_analyzer
        self._analyzer: Analyzer | None = None
        self._cache = MatchCache()
        self._release: str | None = None

    def __call__(
        self, media_id: str, sentence_ids: Sequence[str], texts: Sequence[str]
    ) -> list[TokenArray]:
        release = current_release(self._c)
        if release is None:
            raise RuntimeError("no dictionary release loaded yet; run dict-refresh first")
        if release != self._release:
            self._cache, self._release = MatchCache(), release
        if self._analyzer is None:
            self._analyzer = self._load_analyzer()
        out = parse_sentences(
            texts,
            media_id,
            sentence_ids,
            lookup=PostgresMatchLookup(self._c, self._cache),
            overrides=load_overrides(self._c, media_id),
            analyzer=self._analyzer,
        )
        return [[t.to_json() for t in ts] for ts in out]
