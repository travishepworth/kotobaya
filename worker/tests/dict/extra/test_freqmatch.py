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


def test_masu_alone_is_never_a_wrong_word(idx: EntryIndex) -> None:
    # Without the source's rows: 〼 (the polite auxiliary) has no priority tag and 升 / 増す
    # tie, so there is no winner (counted). With them, see test_far_kanji_row_is_a_different_word.
    p = pick(idx, "ます", None)
    assert p.seq is None and p.ambiguous


# Attempt 3: the source's own rows decide before JMdict tags (real ranks from the pinned lists).
TA_PAST, TA_OTHER = 2654250, 1949190  # た aux "did"; 他 "other"
TORU, TORU_EAT = 1326980, 2021030  # 取る; 摂る (uk)
TSUKU, TSUKU_BREATHE = 1495740, 1444150  # 付く; 吐く (uk)
TEERU = 1078940  # テール / テイル "tail"
AA_AH, AA_LIKE_THAT = 1565440, 2085080  # 嗚呼 (rK) "ah!"; ああ "like that": both common
ZU_NOT, ZU_PLURAL = 2829645, 2731770  # ず aux "not"; 流 ズ/ず "-s"
IKURA, IKURA_ROE = 1219980, 1021180  # 幾ら (uk); イクラ


def test_kana_row_paired_at_the_same_rank_takes_the_kanji_rows_entry(idx: EntryIndex) -> None:
    # jpdb lists いくら and 幾ら/いくら both at 711 (the ㋕ pair): one word.
    m = map_freq_rows(idx, [FreqRow("いくら", None, 711), FreqRow("幾ら", "いくら", 711)])
    assert m.row_seqs == [IKURA, IKURA] and IKURA_ROE not in m.best
    # A paired kanji word is taken too (jpdb 物/もの and もの at 43), no deferral.
    m = map_freq_rows(idx, [FreqRow("もの", None, 43), FreqRow("物", "もの", 43)])
    assert m.row_seqs == [MONO, MONO] and m.row_status == ["mapped", "mapped"]


def test_near_kanji_row_outranks_a_usually_kana_homophone(idx: EntryIndex) -> None:
    # CEJC: とる 500, 取る 123 -> the kanji row owns it, not 摂る (uk). BCCWJ つく 85 / 付く 143.
    m = map_freq_rows(idx, [FreqRow("とる", None, 500), FreqRow("取る", "とる", 123)])
    assert m.row_seqs == [None, TORU] and m.row_status[0] == "deferred"
    assert TORU_EAT not in m.best
    m = map_freq_rows(idx, [FreqRow("つく", None, 85), FreqRow("付く", "つく", 143)])
    assert m.row_seqs == [None, TSUKU] and TSUKU_BREATHE not in m.best


def test_far_kanji_row_is_a_different_word(idx: EntryIndex) -> None:
    # BCCWJ: た 5 vs 他 198 / 多 2943 / 田 7731, ます 12 vs 増す 1773 / 升 15821: the
    # auxiliaries, not deferred.
    m = map_freq_rows(
        idx,
        [FreqRow("た", "た", 5), FreqRow("他", "た", 198), FreqRow("多", "た", 2943),
         FreqRow("田", "た", 7731), FreqRow("ます", "ます", 12), FreqRow("増す", "ます", 1773),
         FreqRow("升", "ます", 15821)],
    )  # fmt: skip
    assert m.row_seqs[0] == TA_PAST and m.row_seqs[1] == TA_OTHER
    assert m.row_seqs[4] == MASU_POLITE and m.row_seqs[5] == MASU_INCREASE
    assert m.mapped == m.rows


def test_hiragana_term_never_matches_a_katakana_only_reading(idx: EntryIndex) -> None:
    assert TEERU in idx.match("ている")  # folded keys collide
    m = map_freq_rows(idx, [FreqRow("ている", "ている", 13)])
    assert m.row_seqs == [None] and m.row_status == ["unmatched"]
    assert pick_entry(idx, "テイル", None) == TEERU  # katakana terms still match


def test_tied_kana_words_take_the_lowest_seq_not_ambiguous(idx: EntryIndex) -> None:
    p = pick(idx, "ああ", None)
    assert p.seq == AA_AH and not p.ambiguous and AA_LIKE_THAT > AA_AH


def test_first_reading_is_judged_in_the_terms_own_script(idx: EntryIndex) -> None:
    # 流 has ズ first and ず second; the aux ず has ず first.
    assert pick_entry(idx, "ず", None) == ZU_NOT
    assert ZU_PLURAL in idx.match("ず")
