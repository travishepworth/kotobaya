"""WRK-FREQ-MATCH regression against the full JMdict and the pinned frequency lists.

Skipped when the caches are absent: ``~/.cache/kotobaya-fixtures/JMdict_e.gz`` (see
fixtures/dict/README.md) and ``~/.cache/kotobaya-fixtures/freq/{jpdb,bccwj,cejc}.zip`` (the
DICT_SOURCES_JSON downloads, checked against the sha256 pins below).
``None`` means the row maps to nothing and is counted (deferred or ambiguous), never a wrong word.
"""

from __future__ import annotations

from collections import Counter
from pathlib import Path

import pytest

from kotobaya_worker.dict.extra.freq import FreqRow, parse_freq_zip
from kotobaya_worker.dict.extra.freqmatch import FreqMapping, map_freq_rows
from kotobaya_worker.dict.extra.index import EntryIndex, index_from_entries
from kotobaya_worker.dict.fetch import sha256_file
from kotobaya_worker.dict.jmdict import parse_jmdict

JMDICT = Path("~/.cache/kotobaya-fixtures/JMdict_e.gz").expanduser()
FREQ_DIR = Path("~/.cache/kotobaya-fixtures/freq").expanduser()
PINS = {
    "jpdb": "4fa06c784155ea0ea0953740b99d421296c775ee0d035cfe1ff65a40d7d3e685",
    "bccwj": "7d17054735e738d02e9f7f62fdad5d6e592a458abd93301367500d04d0c000c3",
    "cejc": "273c603b7ea285debfd8b7d41dc326f97b2fd42c2ed94f22e6dafc1c9cbd8a6b",
}

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


# ---------------------------------------------------------------- the real lists

IKURA, GE, TA_PAST, MASU_POLITE, AA = 1219980, 2006580, 2654250, 2210290, 1565440
NARU, MONO, HOU_KANJI = 1375610, 1502390, 1516930
# (kana term, rank) -> expected entry, or the status of a row that must map to nothing.
REAL: dict[str, dict[tuple[str, int], int | str]] = {
    "jpdb": {("いくら", 711): IKURA, ("げ", 629): GE, ("ああ", 445): AA, ("する", 11): SURU,
             ("ある", 14): ARU, ("こと", 15): KOTO, ("なる", 16): NARU, ("いる", 18): IRU,
             ("もの", 43): MONO, ("ところ", 104): TOKORO, ("とき", 215): TOKI},
    "bccwj": {("た", 5): TA_PAST, ("た", 7): TA_PAST, ("ます", 12): MASU_POLITE,
              ("ます", 14): MASU_POLITE, ("ああ", 534): AA, ("ああ", 793): AA,
              ("つく", 85): "deferred", ("ている", 13): "unmatched"},
    "cejc": {("た", 4): TA_PAST, ("ます", 40): MASU_POLITE, ("ああ", 27): AA,
             ("ああ", 409): AA, ("とる", 500): "deferred", ("つく", 656): "deferred"},
}  # fmt: skip
# The core words' entries must be in each list's top 1000 (through kanji or kana rows).
CORE = (ARU, IRU, MONO, KOTO, TOKI, SURU, NARU, HOU_KANJI, TOKORO)
# deferred + ambiguous rows with rank <= 1000 after attempt 3; must not grow.
TOP1000_LOST = {"jpdb": 3, "bccwj": 5, "cejc": 9}


@pytest.fixture(scope="module", params=list(PINS))
def real(request: pytest.FixtureRequest, idx: EntryIndex) -> tuple[str, list[FreqRow], FreqMapping]:
    name: str = request.param
    path = FREQ_DIR / f"{name}.zip"
    if not path.is_file():
        pytest.skip(f"{path} not cached")
    assert sha256_file(path) == PINS[name], f"{path} is not the pinned file"
    rows = list(parse_freq_zip(path))
    return name, rows, map_freq_rows(idx, rows)


def test_real_list(real: tuple[str, list[FreqRow], FreqMapping]) -> None:
    name, rows, m = real
    assert m.rows == len(rows) == m.mapped + m.unmatched + m.deferred + m.ambiguous
    at = {(r.term, r.rank): i for i, r in enumerate(rows) if r.reading in (None, r.term)}
    got: dict[tuple[str, int], int | str | None] = {}
    for key, want in REAL[name].items():
        i = at[key]
        got[key] = m.row_seqs[i] if isinstance(want, int) else m.row_status[i]
    assert got == REAL[name]
    for seq in CORE:
        assert m.best.get(seq, 1 << 31) <= 1000, (name, seq)
    top = Counter(st for r, st in zip(rows, m.row_status, strict=True) if r.rank <= 1000)
    assert top["deferred"] + top["ambiguous"] <= TOP1000_LOST[name], top
