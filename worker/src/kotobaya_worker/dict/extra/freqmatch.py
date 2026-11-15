"""Map frequency-list rows to dictionary entries: one row -> at most one ``seq``.

A row is ``(term, reading?, rank)``. Every entry having the term as some form is a candidate;
exactly one wins, deterministically (smallest key):

* term written with kanji (or anything not pure kana): candidates having the term as a kanji
  form (and the reading, if given). Key: the kanji form carries a JMdict priority tag
  (``common``) > it is the entry's first kanji form > the entry is not a bound form > lowest seq.
* kana-only term: candidates having the term as a reading. Key: the reading carries a
  priority tag > usually-kana level (no kanji form or ``uk`` on the first sense, then ``uk`` on
  a later sense, then kanji words) > best rank of a kanji row of the same source mapped to the
  entry > not a bound form > lowest seq.

So そう maps to the adverb 然う (uk), not to 草/宋/躁 or the auxiliary -そう; だ to the copula.
Priority comes before the usually-kana level because JMdict tags many rare words ``uk``
(とき would map to 鴇 "ibis" instead of 時, ところ to 野老 instead of 所). Two common
usually-kana words (いる: 要る, 居る) are told apart by the source's own kanji rows when it has
them (居る/いる ranked above 要る/いる), else by lowest seq.

A kana row never claims an entry that is not usually written in kana (level > 0) when a
kanji row of the same source maps to that entry: the kanji row owns it and the kana row is
``deferred`` (it falls back to the next candidate, if any). Rows with no candidate are
``unmatched``. ``rows == mapped + unmatched + deferred``; nothing is dropped silently.

The media parser (``parse.match.match_seq``) ranks with Sudachi POS, a derived reading and
``freq_rank``; a list row has neither POS nor (always) a reading, and ``freq_rank`` is what
this module computes, so the two rankings share only the tie-breaks (priority tag, lowest seq).
"""

from __future__ import annotations

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


def _kana_key(
    idx: EntryIndex, norm: str, seq: int, claimed: Mapping[int, int]
) -> tuple[int, int, int, int, int]:
    return (
        0 if (seq, "reading", norm) in idx.common else 1,
        idx.kana_level.get(seq, 2),
        claimed.get(seq, _NO_RANK),
        1 if seq in idx.bound else 0,
        seq,
    )


@dataclass(frozen=True)
class Pick:
    seq: int | None
    deferred: bool = False  # candidates existed, all owned by kanji rows


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
            free = [s for s in cands if idx.kana_level.get(s, 2) == 0 or s not in claimed]
            if not free:
                return Pick(None, deferred=True)
            return Pick(min(free, key=lambda s: _kana_key(idx, norm, s, claimed)))
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

    @property
    def rows(self) -> int:
        return len(self.row_seqs)

    @property
    def mapped(self) -> int:
        return sum(s is not None for s in self.row_seqs)

    @property
    def unmatched(self) -> int:
        return self.rows - self.mapped - self.deferred


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
    deferred = 0
    for i, r in enumerate(rows):
        if kana[i]:
            p = pick(idx, r.term, r.reading, claimed)
            out[i] = p.seq
            deferred += p.deferred
    best: dict[int, int] = {}
    for r, seq in zip(rows, out, strict=True):
        if seq is not None and (seq not in best or r.rank < best[seq]):
            best[seq] = r.rank
    return FreqMapping(best, out, deferred)
