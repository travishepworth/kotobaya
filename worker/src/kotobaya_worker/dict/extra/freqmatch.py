"""Map frequency-list rows to dictionary entries: one row -> at most one ``seq``.

A row is ``(term, reading?, rank)``. Every entry having the term as some form is a candidate;
at most one wins, deterministically (smallest key):

* term written with kanji (or anything not pure kana): candidates having the term as a kanji
  form (and the reading, if given). Key: the kanji form carries a JMdict priority tag
  (``common``) > it is the entry's first kanji form > the entry is not a bound form > lowest seq.
* kana-only term: candidates having the term as a reading. A hiragana term only matches a
  reading spelt that way (ている is not テイル "tail"); a katakana term also matches hiragana
  readings. "Kanji rows" below are the rows of the same source that map to an entry.

  1. Paired: a candidate a kanji row maps at the row's exact rank wins outright. jpdb lists the
     kana spelling of a word as its own row at the same rank (the ``㋕`` marker), e.g. いくら
     and 幾ら/いくら at 711, so the pair is one word and needs no marker parsing.
  2. Far kanji words are not candidates: an entry with real kanji (level >= 2) whose kanji row
     ranks much worse than this row (``is_near``) is a different word. た (5) is the past
     auxiliary, not 他 (198); ます (12) is 〼, not 増す (1773).
  3. Key, in order: exact script (the term as written is one of the entry's readings) >
     a priority-tagged kana word (``common`` reading, no kanji form in real use: 然う, 居る) >
     rank of a near kanji row > ``common`` reading > usually-kana level (index.kana_level) >
     the matching reading is the entry's first, judged in the term's own script > not a
     bound form (aux / affix: -そう loses to the adverb そう).
  4. If the winner has a near kanji row and is not a usually-kana word (level >= 2) the row
     is ``deferred`` (CEJC とる 500 belongs to 取る 123): never a fall back to the next
     homophone. If the top two tie on the whole key and are kanji words the row is
     ``ambiguous`` (かた: 過多, 潟, 型, 肩, 方); tied kana words are spelt alike (ああ "ah" /
     ああ "like that") and take the lowest seq.

Rows with no candidate are ``unmatched``. ``rows == mapped + unmatched + deferred +
ambiguous``; nothing is dropped silently.

The media parser (``parse.match.match_seq``) ranks with Sudachi POS, a derived reading and
``freq_rank``; a list row has neither POS nor (always) a reading, and ``freq_rank`` is what
this module computes, so the two rankings share only the tie-breaks (priority tag, lowest seq).
"""

from __future__ import annotations

import unicodedata
from collections.abc import Iterable, Mapping
from collections.abc import Set as AbstractSet
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


def is_near(claimed_rank: int, rank: int) -> bool:
    """The kanji row and the kana row plausibly count the same word: the kanji spelling ranks
    better than, or not far below, the kana spelling. た (5) vs 他 (198) is far: different words."""
    return claimed_rank <= 2 * rank + 50


def _is_hiragana_term(written: str) -> bool:
    return not any(0x30A1 <= ord(c) <= 0x30FA or 0x30FD <= ord(c) <= 0x30FF for c in written)


def _kana_key(
    idx: EntryIndex, written: str, norm: str, seq: int, claimed: Mapping[int, int]
) -> _KanaKey:
    common = (seq, "reading", norm) in idx.common
    level = idx.kana_level.get(seq, 3)
    return (
        0 if (seq, written) in idx.exact_readings else 1,
        0 if common and level == 0 else 1,
        claimed.get(seq, _NO_RANK),
        0 if common else 1,
        level,
        0
        if idx.exact_readings.get((seq, written), idx.reading_ord.get((seq, norm), 0)) == 0
        else 1,
        1 if seq in idx.bound else 0,
    )


@dataclass(frozen=True)
class Pick:
    seq: int | None
    deferred: bool = False  # the winner is owned by a kanji row of the same source
    ambiguous: bool = False  # two kanji words tie on the whole key


def _pick_kana(
    idx: EntryIndex,
    term: str,
    norm: str,
    cands: list[int],
    claimed: Mapping[int, int],
    claimed_at: Mapping[int, AbstractSet[int]],
    rank: int | None,
) -> Pick:
    written = unicodedata.normalize("NFKC", term)
    if _is_hiragana_term(written):
        # ている is not テイル "tail": a reading that exists only in katakana does not match.
        cands = [s for s in cands if (s, written) in idx.exact_readings]
        if not cands:
            return Pick(None)
    if rank is not None:
        # The same word listed twice at one rank (jpdb ㋕ pairs: いくら and 幾ら/いくら at 711).
        paired = [s for s in cands if rank in claimed_at.get(s, ())]
        if paired:
            return Pick(min(paired, key=lambda s: (_kana_key(idx, written, norm, s, claimed), s)))
        # A kanji word the source ranked far from this row is a different word (た the past
        # auxiliary is not 他, ranked 198 against 5; だけ is not 岳): it is no candidate.
        far = {
            s
            for s in cands
            if s in claimed
            and not is_near(claimed[s], rank)
            and idx.kana_level.get(s, 3) >= DEFER_LEVEL
        }
        if far:
            cands = [s for s in cands if s not in far]
            if not cands:
                return Pick(None, deferred=True)
        claimed = {s: claimed[s] for s in cands if s in claimed and is_near(claimed[s], rank)}
    ranked = sorted((_kana_key(idx, written, norm, s, claimed), s) for s in cands)
    key, seq = ranked[0]
    if seq in claimed and idx.kana_level.get(seq, 3) >= DEFER_LEVEL:
        return Pick(None, deferred=True)
    if len(ranked) > 1 and ranked[1][0] == key and key[4] > 0:
        # Kanji words that tie (かた: 過多, 潟, 型, 肩, 方) are different words: no winner.
        # Tied kana words (ああ "ah" / ああ "like that") are spelt the same: lowest seq.
        return Pick(None, ambiguous=True)
    return Pick(seq)


def pick(
    idx: EntryIndex,
    term: str,
    reading: str | None,
    claimed: Mapping[int, int] | None = None,
    *,
    rank: int | None = None,
    claimed_at: Mapping[int, AbstractSet[int]] | None = None,
) -> Pick:
    """The one entry for a row of rank ``rank``. ``claimed``: seq -> best rank of the source's
    kanji rows mapped to it; ``claimed_at``: seq -> every such rank (both empty when the row is
    looked up on its own)."""
    norm = normalize_form(term)
    if is_kana_term(norm):
        cands = _filter_reading(idx, idx.kana_norm.get(norm, ()), reading)
        if cands:
            return _pick_kana(idx, term, norm, cands, claimed or {}, claimed_at or {}, rank)
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
    # per input row: "mapped" | "unmatched" | "deferred" | "ambiguous"
    row_status: list[str] = field(default_factory=list)

    def _count(self, status: str) -> int:
        return sum(st == status for st in self.row_status)

    @property
    def rows(self) -> int:
        return len(self.row_seqs)

    @property
    def mapped(self) -> int:
        return self._count("mapped")

    @property
    def unmatched(self) -> int:
        return self._count("unmatched")

    @property
    def deferred(self) -> int:
        return self._count("deferred")

    @property
    def ambiguous(self) -> int:
        return self._count("ambiguous")


def _status(p: Pick) -> str:
    if p.seq is not None:
        return "mapped"
    return "deferred" if p.deferred else "ambiguous" if p.ambiguous else "unmatched"


def map_freq_rows(idx: EntryIndex, rows: Iterable[FreqRow]) -> FreqMapping:
    """Kanji rows first (they claim their entries), then kana rows; result is order-free."""
    rows = list(rows)
    kana = [is_kana_term(normalize_form(r.term)) for r in rows]
    out: list[int | None] = [None] * len(rows)
    status = ["unmatched"] * len(rows)
    claimed: dict[int, int] = {}
    claimed_at: dict[int, set[int]] = {}
    for i, r in enumerate(rows):
        if not kana[i]:
            seq = pick_entry(idx, r.term, r.reading)
            out[i] = seq
            if seq is not None:
                status[i] = "mapped"
                claimed[seq] = min(claimed.get(seq, r.rank), r.rank)
                claimed_at.setdefault(seq, set()).add(r.rank)
    for i, r in enumerate(rows):
        if kana[i]:
            p = pick(idx, r.term, r.reading, claimed, rank=r.rank, claimed_at=claimed_at)
            out[i] = p.seq
            status[i] = _status(p)
    best: dict[int, int] = {}
    for r, seq in zip(rows, out, strict=True):
        if seq is not None and (seq not in best or r.rank < best[seq]):
            best[seq] = r.rank
    return FreqMapping(best, out, status)
