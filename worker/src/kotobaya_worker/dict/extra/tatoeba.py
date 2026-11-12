"""Tatoeba sentence pairs and linking of sentences to dictionary entries.

Pairs file: tab-separated ``jpn_id, jpn_text, eng_id, eng_text`` (``.bz2`` allowed).
"""

from __future__ import annotations

import bz2
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

from ..normalize import normalize_form
from .index import EntryIndex

MAX_FORM_LEN = 16
MIN_KANA_LEN = 2  # single kana (の, は, ...) would link every sentence to particles


@dataclass(frozen=True)
class Pair:
    id: int
    text: str
    translation_id: int | None
    translation: str


def parse_pairs(path: Path) -> Iterator[Pair]:
    seen: set[int] = set()
    with (
        bz2.open(path, "rt", encoding="utf-8")
        if path.suffix == ".bz2"
        else path.open(encoding="utf-8")
    ) as f:
        for n, raw in enumerate(f, 1):
            line = raw.rstrip("\r\n")
            if not line.strip():
                continue
            cols = line.split("\t")
            if len(cols) < 2:
                raise ValueError(f"{path.name}:{n}: expected tab-separated columns")
            cols += [""] * (4 - len(cols))  # a sentence without a translation
            sid = int(cols[0])
            if sid in seen:  # a sentence with several translations: keep the first pair
                continue
            seen.add(sid)
            tid = int(cols[2]) if cols[2].strip() else None
            yield Pair(sid, cols[1].strip(), tid, cols[3].strip())


@dataclass
class LinkIndex:
    """Normalised surface -> seqs, including ichidan / i-adjective kanji stems."""

    surfaces: dict[str, set[int]]


def build_link_index(idx: EntryIndex) -> LinkIndex:
    surfaces: dict[str, set[int]] = {}

    def add(key: str, seq: int) -> None:
        if key and len(key) <= MAX_FORM_LEN:
            surfaces.setdefault(key, set()).add(seq)

    for norm, seqs in idx.kanji_norm.items():
        for seq in seqs:
            add(norm, seq)
            pos = idx.pos.get(seq, frozenset())
            # Conjugated ichidan / i-adjective text keeps the stem: 下げた, 美しかった.
            # Bare one-kanji stems (見, 良) are too ambiguous, so they are not indexed.
            ichidan = "v1" in pos and norm.endswith("る")
            adj_i = "adj-i" in pos and norm.endswith("い")
            if len(norm) >= 3 and (ichidan or adj_i):
                add(norm[:-1], seq)
    for norm, seqs in idx.kana_norm.items():
        if len(norm) >= MIN_KANA_LEN:
            for seq in seqs:
                add(norm, seq)
    return LinkIndex(surfaces)


def link_sentence(text: str, links: LinkIndex) -> set[int]:
    """All entries whose (normalised) form occurs as a substring of the sentence.

    There is no tokeniser here, so this over-links by design; the parser (worker/parse) is the
    precise path. Used for the "examples" lookup only.
    """
    t = normalize_form(text)
    found: set[int] = set()
    n = len(t)
    for i in range(n):
        for j in range(i + 1, min(n, i + MAX_FORM_LEN) + 1):
            seqs = links.surfaces.get(t[i:j])
            if seqs:
                found |= seqs
    return found
