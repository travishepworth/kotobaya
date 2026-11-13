"""Sentences -> C-12 tokens (contracts/api/PARSER.md 3, steps 1-4).

Pure apart from the injected dictionary lookup, Sudachi analyzer and override rows: the same
texts, ``parser_version`` and override rows give the same tokens. Step 5 (occurrences,
segment counts) belongs to the media pipeline.
"""

from __future__ import annotations

import logging
from collections.abc import Sequence
from functools import lru_cache
from importlib import metadata

from kotobaya_worker.parse.match import MatchLookup, Token, entry_pos, to_token
from kotobaya_worker.parse.normalize import normalize_text
from kotobaya_worker.parse.overrides import Analyse, OverrideRow, apply_overrides
from kotobaya_worker.parse.segment import segment
from kotobaya_worker.parse.sudachi import Analyzer, Morph, get_analyzer

log = logging.getLogger(__name__)

# Bump whenever worker parse code can give different tokens for the same text and
# dictionaries (PARSER.md 6). The full parser_version also names the Sudachi dictionary,
# the JMdict release and the worker version.
PARSER_VERSION = "kp1"


@lru_cache(maxsize=8)
def _dist_version(dist: str) -> str:
    try:
        return metadata.version(dist)
    except metadata.PackageNotFoundError:
        return "unknown"


def parser_version(dict_release: str, analyzer: Analyzer | None = None) -> str:
    """``kp<n>;sudachi=<dict>-<version>;dict=<release>;worker=<version>`` (printable ASCII)."""
    an = analyzer if analyzer is not None else get_analyzer()
    sudachi = f"{an.dict_name}-{_dist_version(f'sudachidict-{an.dict_name}')}"
    worker = _dist_version("kotobaya-worker")
    raw = f"{PARSER_VERSION};sudachi={sudachi};dict={dict_release};worker={worker}"
    clean = "".join(c if 0x20 <= ord(c) <= 0x7E else "?" for c in raw)
    return clean[:200]


def make_analyse(analyzer: Analyzer) -> Analyse:
    """Analyse a surface alone: normalise + Sudachi mode C, no fallback (PARSER.md 4.4 b)."""

    def analyse(surface: str) -> Sequence[Morph]:
        return analyzer.analyze(normalize_text(surface).text, None)

    return analyse


def base_tokens(text: str, lookup: MatchLookup, analyzer: Analyzer) -> list[Token]:
    """Steps 1-3 for one sentence: normalise, segment, match, classify."""
    return [to_token(r, lookup) for r in segment(text, lookup.is_key, analyzer)]


def parse_sentences(
    texts: Sequence[str],
    media_id: str | None,
    sentence_ids: Sequence[str] | None,
    *,
    lookup: MatchLookup,
    overrides: Sequence[OverrideRow] = (),
    analyzer: Analyzer | None = None,
    skipped: set[str] | None = None,
) -> list[list[Token]]:
    """Tokens (PARSER.md 1) for each sentence of ``texts``, overrides applied (4).

    ``media_id`` None = ``POST /parse``: global overrides only. ``sentence_ids`` (lowercase
    UUID strings, parallel to ``texts``) enable sentence-scoped overrides. Skipped override
    rows are logged once per call at warn (id only) and added to ``skipped``.
    """
    if sentence_ids is not None and len(sentence_ids) != len(texts):
        raise ValueError("sentence_ids must be parallel to texts")
    an = analyzer if analyzer is not None else get_analyzer()
    analyse = make_analyse(an)
    mid = media_id.lower() if media_id is not None else None
    bad: set[str] = set()
    out: list[list[Token]] = []
    for k, text in enumerate(texts):
        base = base_tokens(text, lookup, an)
        sid = sentence_ids[k].lower() if sentence_ids is not None else None
        out.append(
            apply_overrides(
                text,
                base,
                overrides,
                media_id=mid,
                sentence_id=sid,
                entry_pos=lambda seq: entry_pos(lookup, seq),
                analyse=analyse,
                skipped=bad,
            )
        )
    for oid in sorted(bad):
        log.warning("parse override %s skipped: token surfaces do not match match_text", oid)
    if skipped is not None:
        skipped.update(bad)
    return out
