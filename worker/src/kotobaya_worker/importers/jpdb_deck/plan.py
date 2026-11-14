"""Pure planning for ``jpdb-deck-import``: map rows, merge by seq, account for every row (MEDIA_PIPELINE.md 5.3).

A stored correction wins over the automatic mapper (``map_vocab``). Rows mapping to the same seq are merged
(ADR-009: vid == JMdict seq, 260 vids repeat with different spellings): counts are summed, the best (lowest)
global rank bucket is kept. ``mapped + unmapped + skipped == fetched`` and ``entries + merged + unmapped + skipped == fetched`` always hold
(``DeckStats.check``).
A row without an occurrence count counts once (the word is in the deck, so it occurs at least once).
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field

from kotobaya_worker.importers.jpdb_deck.client.models import DeckVocabulary
from kotobaya_worker.importers.jpdb_history.mapping import map_vocab
from kotobaya_worker.importers.jpdb_history.ref import RefLookup

_NAME = frozenset({"n-pr"})
_PARTICLE = frozenset({"prt"})
_AUX = frozenset({"aux", "aux-v", "aux-adj"})


@dataclass(frozen=True)
class Correction:
    """A row of ``app.jpdb_vocab`` whose ``resolution`` is not ``unresolved``."""

    resolution: str  # 'mapped' | 'ignored'
    entry_seq: int | None = None


@dataclass
class MergedEntry:
    seq: int
    count: int = 0
    best_rank: int | None = None  # lowest "Top N" bucket seen (smaller = more common)


@dataclass(frozen=True)
class VocabItem:
    """One element of the payload's ``jpdb.vocab``."""

    jpdb_vid: int
    spelling: str
    reading: str
    occurrence_count: int
    resolution: str  # 'unresolved' | 'mapped' | 'ignored'
    entry_seq: int | None


@dataclass
class DeckStats:
    fetched: int = 0
    mapped: int = 0  # rows that map to a seq (automatic or by correction)
    unmapped: int = 0  # rows that stay unmapped, ignored ones included
    ignored: int = 0  # subset of ``unmapped`` removed from the queue by an ``ignored`` correction
    skipped: int = 0  # rows without a usable vid (client-skipped or vid <= 0)
    merged_entries: int = 0  # distinct seqs
    merged: int = 0  # mapped rows folded onto an entry another row already produced

    def check(self) -> None:
        if self.mapped + self.unmapped + self.skipped != self.fetched:
            raise AssertionError(f"row accounting broke: {self}")
        # in = out + merged + dropped
        if self.merged_entries + self.merged + self.unmapped + self.skipped != self.fetched:
            raise AssertionError(f"merge accounting broke: {self}")


@dataclass
class DeckPlan:
    entries: dict[int, MergedEntry] = field(default_factory=dict)
    vocab: list[VocabItem] = field(default_factory=list)
    unmapped_token_count: int = 0
    stats: DeckStats = field(default_factory=DeckStats)

    @property
    def token_count(self) -> int:
        return sum(e.count for e in self.entries.values()) + self.unmapped_token_count


def clean(text: str) -> str:
    return text.replace("\x00", "")


def pos_class(entry_pos: frozenset[str]) -> str:
    """PARSER.md 2.1 for jpdb occurrences (``P = []``): rows 4-6, else row 9 (``content``)."""
    if entry_pos and entry_pos <= _NAME:
        return "name"
    if entry_pos and entry_pos <= _PARTICLE:
        return "particle"
    if entry_pos and entry_pos <= _AUX:
        return "auxiliary"
    return "content"


def plan_deck(
    deck: DeckVocabulary, corrections: Mapping[int, Correction], ref: RefLookup
) -> DeckPlan:
    plan = DeckPlan()
    st = plan.stats
    st.fetched = deck.raw_rows
    st.skipped = max(deck.raw_rows - len(deck.words), 0)
    vocab: dict[int, VocabItem] = {}

    def note(vid: int, spelling: str, reading: str, n: int, res: str, seq: int | None) -> None:
        old = vocab.get(vid)
        total = n + (old.occurrence_count if old else 0)
        vocab[vid] = VocabItem(vid, clean(spelling), clean(reading), total, res, seq)

    for w in deck.words:
        if w.vid <= 0:
            st.skipped += 1
            continue
        n = w.occurrence_count if w.occurrence_count and w.occurrence_count > 0 else 1
        corr = corrections.get(w.vid)
        if (
            corr is not None
            and corr.resolution == "mapped"
            and (corr.entry_seq is None or not ref.has_seq(corr.entry_seq))
        ):
            corr = None  # stale correction (entry unknown): fall back to the automatic mapper
        seq: int | None
        if corr is not None and corr.resolution == "mapped":
            seq = corr.entry_seq
        elif corr is not None and corr.resolution == "ignored":
            seq = None
        else:
            seq = map_vocab(ref, w.spelling, w.reading, w.vid).seq
        if seq is not None:
            st.mapped += 1
            e = plan.entries.setdefault(seq, MergedEntry(seq))
            e.count += n
            rank = w.global_rank_bucket
            if rank is not None and (e.best_rank is None or rank < e.best_rank):
                e.best_rank = rank
            if corr is not None:
                note(w.vid, w.spelling, w.reading, n, "mapped", seq)
            continue
        st.unmapped += 1
        plan.unmapped_token_count += n
        if corr is not None:
            st.ignored += 1
            note(w.vid, w.spelling, w.reading, n, "ignored", None)
        else:
            note(w.vid, w.spelling, w.reading, n, "unresolved", None)
    st.merged_entries = len(plan.entries)
    st.merged = st.mapped - st.merged_entries
    plan.vocab = sorted(vocab.values(), key=lambda v: (-v.occurrence_count, v.jpdb_vid))
    st.check()
    return plan
