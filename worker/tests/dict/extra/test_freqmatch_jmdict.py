"""WRK-FREQ-MATCH regression against the full JMdict (skipped when the cached file is absent).

The cache is ``~/.cache/kotobaya-fixtures/JMdict_e.gz`` (see fixtures/dict/README.md).
``None`` means the row maps to nothing and is counted (deferred or ambiguous), never a wrong word.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from kotobaya_worker.dict.extra.freq import FreqRow
from kotobaya_worker.dict.extra.freqmatch import map_freq_rows
from kotobaya_worker.dict.extra.index import EntryIndex, index_from_entries
from kotobaya_worker.dict.jmdict import parse_jmdict

JMDICT = Path("~/.cache/kotobaya-fixtures/JMdict_e.gz").expanduser()

SOU, DA, KA, NE = 2137720, 2089020, 2028970, 2029080  # 然う adv, copula, particles
MONO_PRT, IRU, ARU, SURU, KOTO = 2780660, 1577980, 1296400, 1157170, 1313580
TOKI, TOKORO, HOU, HITO, MIRU = 1315840, 1343100, 1516930, 1580640, 1259290
IKU, KURU, WAKE, KATA = 1578850, 1547720, 1538330, 1516925

# Kana rows on their own (a source without kanji rows, e.g. a kana-only list).
ALONE: dict[str, int | None] = {
    "そう": SOU, "だ": DA, "か": KA, "ね": NE,
    "もの": MONO_PRT,  # the kana-only particle (spec1) outranks 物 (uk on a later sense only)
    "ます": None,  # 升 / 増す tie (both tagged); 〼 is untagged: ambiguous
    "いる": IRU, "ある": None,  # 有る / 或る: both tagged and uk on the first sense: ambiguous
    "する": SURU, "こと": KOTO, "とき": TOKI, "ところ": TOKORO, "ほう": HOU, "ひと": HITO,
    "みる": MIRU, "いく": IKU, "くる": KURU, "わけ": WAKE,
    "かた": None,  # 過多 潟 型 肩 方: all tagged kanji words: ambiguous
}  # fmt: skip

# The same kana rows in a source that also lists the kanji spellings (jpdb, BCCWJ).
KANJI_ROWS = [("時", "とき"), ("所", "ところ"), ("事", "こと"), ("物", "もの"), ("方", "ほう"),
              ("方", "かた"), ("人", "ひと"), ("見る", "みる"), ("行く", "いく"), ("来る", "くる"),
              ("訳", "わけ"), ("有る", "ある"), ("居る", "いる"), ("為る", "する")]  # fmt: skip
WITH_KANJI: dict[str, int | None] = {
    **ALONE,
    # Kanji words: the kanji row owns the entry, the kana row is deferred.
    "とき": None, "ところ": None, "ほう": None, "ひと": None, "みる": None, "いく": None,
    "くる": None, "わけ": None, "かた": None,
    # Usually-kana words stay with the kana row; the kanji rows break the ある tie.
    "こと": KOTO, "いる": IRU, "する": SURU, "ある": ARU,
}  # fmt: skip
OWNED = {"時": TOKI, "所": TOKORO, "方/ほう": HOU, "方/かた": KATA, "人": HITO, "見る": MIRU,
         "行く": IKU, "来る": KURU, "訳": WAKE}  # fmt: skip


@pytest.fixture(scope="module")
def idx() -> EntryIndex:
    if not JMDICT.is_file():
        pytest.skip(f"{JMDICT} not cached")
    return index_from_entries(parse_jmdict(JMDICT))


def test_kana_rows_alone(idx: EntryIndex) -> None:
    rows = [FreqRow(t, None, i) for i, t in enumerate(ALONE, start=1)]
    m = map_freq_rows(idx, rows)
    assert dict(zip(ALONE, m.row_seqs, strict=True)) == ALONE
    assert m.deferred == 0 and m.ambiguous == 3 and m.unmatched == 0


def test_kana_rows_with_kanji_rows(idx: EntryIndex) -> None:
    kanji = [FreqRow(k, r, i) for i, (k, r) in enumerate(KANJI_ROWS, start=1)]
    kana = [FreqRow(t, None, 100 + i) for i, t in enumerate(WITH_KANJI)]
    m = map_freq_rows(idx, kanji + kana)
    got = dict(zip(WITH_KANJI, m.row_seqs[len(kanji) :], strict=True))
    assert got == WITH_KANJI
    owned = {k if k != "方" else f"方/{r}": s for (k, r), s in zip(KANJI_ROWS, m.row_seqs)}
    assert {k: owned[k] for k in OWNED} == OWNED
    assert m.rows == m.mapped + m.unmatched + m.deferred + m.ambiguous
    assert m.deferred == 9 and m.ambiguous == 1  # ます
