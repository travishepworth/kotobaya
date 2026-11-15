"""In-memory lookup of the loaded dictionary, keyed by T101's ``normalize_form``."""

from __future__ import annotations

import unicodedata
from collections.abc import Iterable
from dataclasses import dataclass, field
from typing import Any

import psycopg

from ..models import Entry
from ..normalize import normalize_form

# Bound forms: a first sense tagged with one of these (and not a copula) is an affix or
# auxiliary (-そう "appearing that", 化 "-ization"), never the stand-alone word of a list row.
BOUND_POS = frozenset({"aux", "aux-v", "aux-adj", "suf", "n-suf", "pref", "n-pref"})
COPULA_POS = frozenset({"cop", "cop-da"})
# Kanji forms that are not really used: rarely-used, search-only, outdated (JMdict ke_inf).
RARE_KANJI_TAGS = frozenset({"rK", "sK", "oK"})


def is_real_kanji(tags: Iterable[str]) -> bool:
    return RARE_KANJI_TAGS.isdisjoint(tags)


def kana_level(has_real_kanji: bool, first_sense_misc: Iterable[str], any_uk: bool) -> int:
    """How strongly the entry is a kana word.

    0: no kanji form in real use (none, or only rK/sK/oK forms: 然う, 居る, 〼);
    1: usually kana (``uk``) on the first sense; 2: ``uk`` on a later sense only (見る, 人);
    3: written in kanji.
    """
    if not has_real_kanji:
        return 0
    if "uk" in first_sense_misc:
        return 1
    return 2 if any_uk else 3


def is_bound(first_sense_pos: Iterable[str]) -> bool:
    pos = frozenset(first_sense_pos)
    return not BOUND_POS.isdisjoint(pos) and COPULA_POS.isdisjoint(pos)


@dataclass
class EntryIndex:
    """Live entries only (retired entries have no form rows, so they never match)."""

    by_norm: dict[str, set[int]] = field(default_factory=dict)  # any form -> seqs
    kanji_norm: dict[str, set[int]] = field(default_factory=dict)  # kanji forms only
    kana_norm: dict[str, set[int]] = field(default_factory=dict)  # readings only
    readings: dict[int, dict[str, str]] = field(default_factory=dict)  # seq -> norm -> reading
    pos: dict[int, frozenset[str]] = field(default_factory=dict)
    # Candidate ranking for frequency rows (freqmatch.py):
    common: set[tuple[int, str, str]] = field(default_factory=set)  # (seq, kind, norm) priority
    first_kanji: dict[int, str] = field(default_factory=dict)  # seq -> norm of kanji form 0
    kana_level: dict[int, int] = field(default_factory=dict)  # seq -> see kana_level()
    reading_ord: dict[tuple[int, str], int] = field(default_factory=dict)  # (seq, norm) -> ord
    # (seq, NFKC reading as written) -> ord: tells ず from ズ, which share a norm
    exact_readings: dict[tuple[int, str], int] = field(default_factory=dict)
    bound: set[int] = field(default_factory=set)  # seqs whose first sense is a bound form

    def match(self, term: str, reading: str | None = None) -> list[int]:
        """Entries having ``term`` as a form and (when given) ``reading`` as a reading."""
        seqs = self.by_norm.get(normalize_form(term), set())
        if reading:
            rn = normalize_form(reading)
            seqs = {s for s in seqs if rn in self.readings.get(s, {})}
        return sorted(seqs)

    def reading_text(self, seq: int, reading: str) -> str | None:
        """The reading exactly as in ``dict_entry.readings`` (None if the entry lacks it)."""
        return self.readings.get(seq, {}).get(normalize_form(reading))

    def add_form(self, seq: int, kind: str, ord_: int, form: str, norm: str, common: bool) -> None:
        self.by_norm.setdefault(norm, set()).add(seq)
        target = self.kanji_norm if kind == "kanji" else self.kana_norm
        target.setdefault(norm, set()).add(seq)
        if kind == "reading":
            self.readings.setdefault(seq, {}).setdefault(norm, form)
            self.reading_ord.setdefault((seq, norm), ord_)
            self.exact_readings.setdefault((seq, unicodedata.normalize("NFKC", form)), ord_)
        elif ord_ == 0:
            self.first_kanji[seq] = norm
        if common:
            self.common.add((seq, kind, norm))

    def add_entry(self, seq: int, pos: Iterable[str], level: int, bound: bool) -> None:
        self.pos[seq] = frozenset(pos)
        self.kana_level[seq] = level
        if bound:
            self.bound.add(seq)


def index_from_entries(entries: Iterable[Entry]) -> EntryIndex:
    """The index :func:`load_index` would build after loading ``entries`` (tests, small runs)."""
    idx = EntryIndex()
    for e in entries:
        for kind, forms in (("kanji", e.kanji), ("reading", e.readings)):
            for i, f in enumerate(forms):
                idx.add_form(e.seq, kind, i, f.text, normalize_form(f.text), f.common)
        first = e.senses[0] if e.senses else None
        idx.add_entry(
            e.seq,
            {p for s in e.senses for p in s.pos},
            kana_level(
                any(is_real_kanji(k.tags) for k in e.kanji),
                first.misc if first else (),
                any("uk" in s.misc for s in e.senses),
            ),
            is_bound(first.pos if first else ()),
        )
    return idx


def load_index(conn: psycopg.Connection[Any]) -> EntryIndex:
    idx = EntryIndex()
    with conn.cursor() as cur:
        cur.execute(
            "SELECT seq, kind, ord, form, norm, common FROM ref.dict_entry_form "
            "ORDER BY seq, kind, ord"
        )
        for seq, kind, ord_, form, norm, common in cur:
            idx.add_form(seq, kind, ord_, form, norm, bool(common))
        cur.execute(
            """
            SELECT seq, pos,
                   EXISTS (SELECT 1 FROM jsonb_array_elements(kanji_forms) k
                           WHERE NOT (k->'tags' ?| %s)),
                   coalesce(array(SELECT jsonb_array_elements_text(senses->0->'misc')), '{}'),
                   'uk' = ANY(misc),
                   coalesce(array(SELECT jsonb_array_elements_text(senses->0->'pos')), '{}')
            FROM ref.dict_entry WHERE retired_release_id IS NULL
            """,
            (sorted(RARE_KANJI_TAGS),),
        )
        for seq, pos, has_kanji, misc0, any_uk, pos0 in cur:
            idx.add_entry(
                seq, pos, kana_level(bool(has_kanji), misc0, bool(any_uk)), is_bound(pos0)
            )
    return idx
