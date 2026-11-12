"""jpdb vocab -> JMdict seq, ported from the S02 spike (``spikes/jpdb-map/jpdbmap.py`` ``map_word``).

Order (spikes/jpdb-map/REPORT.md "Mapping"): (1) the vid is a JMdict seq and agrees with the
spelling+reading pair, the spelling, or (kana words) the reading; (2) exact (spelling, reading)
pair; (3) kana-only word by reading, preferring ``uk`` entries; (4) spelling only. Where a text
fallback yields several candidates (S02 saw none on the real export) the smallest seq is chosen
so the result is deterministic.
"""

from __future__ import annotations

from dataclasses import dataclass

from kotobaya_worker.importers.jpdb_history.ref import RefLookup, norm


@dataclass(frozen=True)
class Mapped:
    seq: int | None  # None = unmapped
    method: str
    candidates: int  # how many seqs the winning rule offered (1 = unambiguous)


UNMAPPED = Mapped(None, "unmapped", 0)


def map_vocab(ref: RefLookup, spelling: str, reading: str, vid: int | None) -> Mapped:
    s, r = norm(spelling), norm(reading)
    if not s and not r:
        return UNMAPPED
    by_kanji = ref.seqs_with_kanji(s) if s else frozenset[int]()
    by_reading_r = ref.seqs_with_reading(r) if r else frozenset[int]()
    pair = by_kanji & by_reading_r
    if vid is not None and ref.has_seq(vid):
        if vid in pair:
            return Mapped(vid, "vid=seq (confirmed by text)", 1)
        if vid in by_kanji:
            return Mapped(vid, "vid=seq (spelling only)", 1)
        if s == r and vid in by_reading_r:
            return Mapped(vid, "vid=seq (kana)", 1)
    if pair:
        return Mapped(min(pair), "spelling+reading", len(pair))
    if s and s == r and by_reading_r:
        uk = [x for x in by_reading_r if ref.usually_kana(x)]
        pool = uk or list(by_reading_r)
        return Mapped(min(pool), "kana-reading", len(pool))
    if by_kanji:
        return Mapped(min(by_kanji), "spelling-only", len(by_kanji))
    return UNMAPPED
