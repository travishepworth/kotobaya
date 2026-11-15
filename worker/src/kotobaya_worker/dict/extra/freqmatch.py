"""Map frequency-list rows to dictionary entries: one row -> at most one ``seq``.

A row is ``(term, reading?, rank)``. Every entry having the term as some form is a candidate;
at most one wins, deterministically (smallest key):

* term written with kanji (or anything not pure kana): candidates having the term as a kanji
  form (and the reading, if given). Key: the kanji form carries a JMdict priority tag
  (``common``) > it is the entry's first kanji form > the entry is not a bound form > lowest seq.
* kana-only term: candidates having the term as a reading. Key, in order:

  1. exact script: the term as written (NFKC, before katakana folding) is one of the entry's
     readings, so もの is 物 and not the loanword モノ;
  2. a priority-tagged kana word: the reading is ``common`` and the entry has no kanji form in
     real use (none, or only rK/sK/oK forms: 然う, 居る);
  3. the reading is ``common`` (とき is 時, not the untagged uk 鴇 "ibis");
  4. usually-kana level (index.kana_level: no real kanji, ``uk`` on the first sense, ``uk`` on a
     later sense, kanji word);
  5. best rank of a kanji row of the same source mapped to the entry;
  6. the reading is the entry's first reading;
  7. not a bound form (aux / affix: -そう loses to the adverb そう).

  If the winner is owned by a kanji row of the same source and is not a usually-kana word
  (level >= 2), the row is ``deferred``: it never falls back to the next homophone. If two
  candidates tie on the whole key the row is ``ambiguous`` (かた: 過多, 潟, 型, 肩, 方 are all
  priority-tagged kanji words) and maps to nothing.

Rows with no candidate are ``unmatched``. ``rows == mapped + unmatched + deferred +
ambiguous``; nothing is dropped silently.

The Yomitan ``㋕`` display marker (jpdb kana lists) is not used: it says which spelling was
counted, while the entry is fixed by the term and reading either way, and a kana term already
says it is the kana spelling.

The media parser (``parse.match.match_seq``) ranks with Sudachi POS, a derived reading and
``freq_rank``; a list row has neither POS nor (always) a reading, and ``freq_rank`` is what
this module computes, so the two rankings share only the tie-breaks (priority tag, lowest seq).
"""

from __future__ import annotations

import unicodedata
from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field

from ..normalize import normalize_form
from .freq import FreqRow
from .index import EntryIndex

_KANA_EXTRA = frozenset("ー〜～・")
_NO_RANK = 2**31


def is_kana_term(norm: str) -> bool:
    """Hiragana/katakana only (plus the long-vowel mark); ``norm`` is ``normalize_form``-ed."""
    return bool(norm) and all(
        0x3041 <= ord(c) <= 0x309F or 0x30A0 <= ord(c) <= 0x30FF or c in _KANA_EXTRA for c in norm
    )


def _filter_reading(idx: EntryIndex, seqs: Iterable[int], reading: str | None) -> list[int]:
    if not reading:
        return list(seqs)
    rn = normalize_form(reading)
    return [s for s in seqs if rn in idx.readings.get(s, {})]


def _written_key(idx: EntryIndex, norm: str, seq: int) -> tuple[int, int, int, int]:
    return (
        0 if (seq, "kanji", norm) in idx.common else 1,
        0 if idx.first_kanji.get(seq) == norm else 1,
        1 if seq in idx.bound else 0,
        seq,
    )


_KanaKey = tuple[int, int, int, int, int, int, int]
DEFER_LEVEL = 2  # kana_level from which a kanji row of the same source owns the entry


def _kana_key(
    idx: EntryIndex, written: str, norm: str, seq: int, claimed: Mapping[int, int]
) -> _KanaKey:
    common = (seq, "reading", norm) in idx.common
    level = idx.kana_level.get(seq, 3)
    return (
        0 if (seq, written) in idx.exact_readings else 1,
        0 if common and level == 0 else 1,
        0 if common else 1,
        level,
        claimed.get(seq, _NO_RANK),
        0 if idx.reading_ord.get((seq, norm), 0) == 0 else 1,
        1 if seq in idx.bound else 0,
    )


@dataclass(frozen=True)
class Pick:
    seq: int | None
    deferred: bool = False  # the winner is owned by a kanji row of the same source
    ambiguous: bool = False  # two candidates tie on the whole key


def pick(
    idx: EntryIndex, term: str, reading: str | None, claimed: Mapping[int, int] | None = None
) -> Pick:
    """The one entry for a row. ``claimed``: seq -> best rank of the source's kanji rows
    mapped to it (empty when the row is looked up on its own)."""
    claimed = claimed or {}
    norm = normalize_form(term)
    if is_kana_term(norm):
        cands = _filter_reading(idx, idx.kana_norm.get(norm, ()), reading)
        if cands:
            written = unicodedata.normalize("NFKC", term)
            ranked = sorted((_kana_key(idx, written, norm, s, claimed), s) for s in cands)
            key, seq = ranked[0]
            if seq in claimed and idx.kana_level.get(seq, 3) >= DEFER_LEVEL:
                return Pick(None, deferred=True)
            if len(ranked) > 1 and ranked[1][0] == key:
                return Pick(None, ambiguous=True)
            return Pick(seq)
    else:
        cands = _filter_reading(idx, idx.kanji_norm.get(norm, ()), reading)
        if cands:
            return Pick(min(cands, key=lambda s: _written_key(idx, norm, s)))
    # Odd spellings (a kana-looking kanji form, a reading that is not kana): any form.
    rest = idx.match(term, reading)
    return Pick(min(rest, key=lambda s: _written_key(idx, norm, s)) if rest else None)


def pick_entry(
    idx: EntryIndex, term: str, reading: str | None, claimed: Mapping[int, int] | None = None
) -> int | None:
    return pick(idx, term, reading, claimed).seq


@dataclass(frozen=True)
class FreqMapping:
    best: dict[int, int]  # seq -> best (lowest) rank over the rows mapped to it
    row_seqs: list[int | None] = field(default_factory=list)  # per input row, input order
    deferred: int = 0
    ambiguous: int = 0

    @property
    def rows(self) -> int:
        return len(self.row_seqs)

    @property
    def mapped(self) -> int:
        return sum(s is not None for s in self.row_seqs)

    @property
    def unmatched(self) -> int:
        return self.rows - self.mapped - self.deferred - self.ambiguous


def map_freq_rows(idx: EntryIndex, rows: Iterable[FreqRow]) -> FreqMapping:
    """Kanji rows first (they claim their entries), then kana rows; result is order-free."""
    rows = list(rows)
    kana = [is_kana_term(normalize_form(r.term)) for r in rows]
    out: list[int | None] = [None] * len(rows)
    claimed: dict[int, int] = {}
    for i, r in enumerate(rows):
        if not kana[i]:
            seq = pick_entry(idx, r.term, r.reading)
            out[i] = seq
            if seq is not None and (seq not in claimed or r.rank < claimed[seq]):
                claimed[seq] = r.rank
    deferred = ambiguous = 0
    for i, r in enumerate(rows):
        if kana[i]:
            p = pick(idx, r.term, r.reading, claimed)
            out[i] = p.seq
            deferred += p.deferred
            ambiguous += p.ambiguous
    best: dict[int, int] = {}
    for r, seq in zip(rows, out, strict=True):
        if seq is not None and (seq not in best or r.rank < best[seq]):
            best[seq] = r.rank
    return FreqMapping(best, out, deferred, ambiguous)
