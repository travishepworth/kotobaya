"""WRK-FREQ-MATCH: one frequency-list row -> at most one entry (no homophone fan-out).

Entries are real JMdict records (``data/JMdict_freqmatch.xml``, CC BY-SA 4.0, EDRDG).
"""

from __future__ import annotations

from pathlib import Path

import pytest

from kotobaya_worker.dict.extra.freq import FreqRow
from kotobaya_worker.dict.extra.freqmatch import map_freq_rows, pick, pick_entry
from kotobaya_worker.dict.extra.index import EntryIndex, index_from_entries
from kotobaya_worker.dict.jmdict import parse_jmdict

DATA = Path(__file__).resolve().parent / "data"

SOU_ADV = 2137720  # 然う (rK, uk): "so; in that way"
SOU_AUX = 1006610  # -そう "appearing that" (aux, kana only)
KUSA_DRAFT = 2414580  # 草 そう
SOU_VIBRANCY = 2406720  # 壮; 草 そう
KUSA = 1401910  # 草 くさ (common)
DA_COP = 2089020
KA_PRT = 2028970
NE_PRT = 2029080
NEKO = 1467640
SURU = 1157170  # 為る (uk)
TOKI, TOKI_IBIS = 1315840, 1944260  # 時 (common, kanji word); 鴇 (uk, rare)
IRU_NEED, IRU_BE = 1546640, 1577980  # 要る (uk); 居る (uk, kanji form rK): both common
MONO, MONO_LOAN, MONO_PRT = 1502390, 1135480, 2780660  # 物; モノ "mono"; もの particle
MASU_LOAN, MASU_POLITE = 1127800, 2210290  # マス "mass"; 〼 (sK, untagged)
MASU_BOX, MASU_INCREASE = 1346290, 1403120  # 升, 増す: priority-tagged kanji words
KATA = {1196230, 1207710, 1250090, 1258950, 1516925}  # 過多 潟 型 肩 方 (all common かた)
HOMOPHONES = {2253390, 2843391, 2843390, 1398030, 1399540, 1176700, KUSA_DRAFT, SOU_VIBRANCY}


@pytest.fixture(scope="module")
def idx() -> EntryIndex:
    return index_from_entries(parse_jmdict(DATA / "JMdict_freqmatch.xml"))


def test_fixture_has_the_homophones(idx: EntryIndex) -> None:
    # Guard: the old any-form match fanned そう out to every homophone.
    assert HOMOPHONES | {SOU_ADV, SOU_AUX} <= set(idx.match("そう"))


@pytest.mark.parametrize(
    ("term", "want"),
    [("そう", SOU_ADV), ("だ", DA_COP), ("か", KA_PRT), ("ね", NE_PRT), ("する", SURU)],
)
def test_kana_term_maps_to_the_usually_kana_word(idx: EntryIndex, term: str, want: int) -> None:
    assert pick_entry(idx, term, None) == want
    assert pick_entry(idx, term, term) == want  # yomitan rows often repeat the kana as reading


def test_exact_script_beats_a_folded_match(idx: EntryIndex) -> None:
    # もの / モノ and ます / マス fold to the same key; the spelling as written decides.
    assert pick_entry(idx, "もの", None) not in (MONO_LOAN, None)
    assert pick_entry(idx, "モノ", None) == MONO_LOAN  # 物 has モノ too, untagged
    assert pick_entry(idx, "マス", None) == MASU_LOAN
    assert pick_entry(idx, "ます", None) != MASU_LOAN
    assert pick_entry(idx, "ソウ", None) == SOU_ADV  # no exact candidate: folded match


def test_kanji_term_with_reading_maps_to_that_entry_only(idx: EntryIndex) -> None:
    # 草 is the first kanji form of "draft" but only the second of "vibrancy".
    assert pick_entry(idx, "草", "そう") == KUSA_DRAFT
    assert pick_entry(idx, "草", "くさ") == KUSA


def test_kanji_term_with_two_entries_picks_the_priority_tagged(idx: EntryIndex) -> None:
    assert pick_entry(idx, "草", None) == KUSA  # 草 (news1/ichi1) beats both そう entries


def test_no_candidate_is_none(idx: EntryIndex) -> None:
    assert pick_entry(idx, "存在しない語", None) is None
    assert pick_entry(idx, "草", "ねこ") is None  # reading filter leaves nothing


def test_kana_row_does_not_claim_a_kanji_rows_entry(idx: EntryIndex) -> None:
    # くさ / ねこ are not usually written in kana; the kanji rows own those entries.
    rows = [FreqRow("草", "くさ", 300), FreqRow("くさ", None, 200), FreqRow("ねこ", None, 50)]
    with_kanji = map_freq_rows(idx, [*rows, FreqRow("猫", "ねこ", 900)])
    assert with_kanji.best == {KUSA: 300, NEKO: 900}
    assert with_kanji.deferred == 2 and with_kanji.row_seqs == [KUSA, None, None, NEKO]
    # Without a kanji row in the source the kana row maps to the entry itself.
    alone = map_freq_rows(idx, [FreqRow("ねこ", None, 50)])
    assert alone.best == {NEKO: 50} and alone.deferred == 0


def test_kana_row_keeps_a_usually_kana_entry_also_listed_in_kanji(idx: EntryIndex) -> None:
    m = map_freq_rows(idx, [FreqRow("為る", "する", 40), FreqRow("する", None, 10)])
    assert m.best == {SURU: 10} and m.row_seqs == [SURU, SURU]


def test_mapping_counts_sum_to_input_and_one_seq_per_row(idx: EntryIndex) -> None:
    rows = [
        FreqRow("そう", None, 1),
        FreqRow("だ", "だ", 2),
        FreqRow("か", None, 3),
        FreqRow("草", "そう", 4),
        FreqRow("草", "くさ", 5),
        FreqRow("くさ", None, 6),  # deferred to 草/くさ
        FreqRow("存在しない語", None, 7),  # unmatched
        FreqRow("ソウ", None, 8),  # duplicate of row 1: same entry, best rank kept
        FreqRow("かた", None, 9),  # ambiguous: five priority-tagged kanji words
    ]
    m = map_freq_rows(idx, rows)
    assert (m.rows, m.mapped, m.unmatched, m.deferred, m.ambiguous) == (9, 5 + 1, 1, 1, 1)
    assert m.rows == m.mapped + m.unmatched + m.deferred + m.ambiguous
    assert len(m.row_seqs) == m.rows
    assert sum(s is not None for s in m.row_seqs) == m.mapped
    assert m.best == {SOU_ADV: 1, DA_COP: 2, KA_PRT: 3, KUSA_DRAFT: 4, KUSA: 5}
    assert not HOMOPHONES - {KUSA_DRAFT} & set(m.best)


def test_empty_source(idx: EntryIndex) -> None:
    m = map_freq_rows(idx, [])
    assert (m.rows, m.mapped, m.unmatched, m.deferred, m.ambiguous) == (0, 0, 0, 0, 0)
    assert m.best == {}


def test_order_of_rows_does_not_matter(idx: EntryIndex) -> None:
    rows = [FreqRow("くさ", None, 6), FreqRow("ソウ", None, 8), FreqRow("草", "くさ", 5),
            FreqRow("そう", None, 1)]  # fmt: skip
    a, b = map_freq_rows(idx, rows), map_freq_rows(idx, list(reversed(rows)))
    assert a.best == b.best == {KUSA: 5, SOU_ADV: 1}
    assert (a.mapped, a.deferred) == (b.mapped, b.deferred) == (3, 1)


def test_priority_beats_a_rare_usually_kana_word(idx: EntryIndex) -> None:
    assert pick_entry(idx, "とき", None) == TOKI  # not 鴇 "ibis" (uk, untagged)


def test_kana_row_defers_instead_of_falling_back_to_the_next_homophone(idx: EntryIndex) -> None:
    # 時 owns とき; the kana row must not slide down to 鴇 "ibis".
    m = map_freq_rows(idx, [FreqRow("時", "とき", 30), FreqRow("とき", None, 40)])
    assert m.row_seqs == [TOKI, None] and m.deferred == 1
    assert m.best == {TOKI: 30} and TOKI_IBIS not in m.best


def test_iru_is_the_kana_word_not_the_kanji_word(idx: EntryIndex) -> None:
    # Both common and uk; 居る's only kanji form is rK, so it is a kana word outright.
    assert pick_entry(idx, "いる", None) == IRU_BE
    m = map_freq_rows(
        idx, [FreqRow("いる", None, 5), FreqRow("居る", "いる", 20), FreqRow("要る", "いる", 900)]
    )
    assert m.row_seqs == [IRU_BE, IRU_BE, IRU_NEED]  # uk entries stay claimable by kana rows
    assert m.best == {IRU_BE: 5, IRU_NEED: 900} and m.deferred == 0


def test_kata_ties_are_ambiguous_not_lowest_seq(idx: EntryIndex) -> None:
    assert KATA <= set(idx.match("かた"))
    p = pick(idx, "かた", None)
    assert p.seq is None and p.ambiguous
    # With the source's kanji rows the best-ranked owner wins the key and owns the entry.
    m = map_freq_rows(idx, [FreqRow("方", "かた", 50), FreqRow("肩", "かた", 900),
                            FreqRow("かた", None, 60)])  # fmt: skip
    assert m.row_seqs == [1516925, 1258950, None] and (m.deferred, m.ambiguous) == (1, 0)


def test_masu_is_never_a_wrong_word(idx: EntryIndex) -> None:
    # 〼 (the polite auxiliary) has no priority tag; 升 and 増す tie: no winner, counted.
    p = pick(idx, "ます", None)
    assert p.seq is None and p.ambiguous
