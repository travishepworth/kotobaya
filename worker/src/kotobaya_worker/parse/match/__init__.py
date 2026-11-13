"""JMdict matching and token classes (SPEC Parsing step 3; contracts/api/PARSER.md 1-2).

A raw token from :func:`kotobaya_worker.parse.segment` becomes a C-12 :class:`Token` here:
``seq`` from :func:`match_seq`, ``class`` from :func:`derive_class`.

Candidate lookup (first non-empty tier wins): entries having the lemma as a kanji or kana
form; else the surface; else the lemma's derived reading as a kana form (POS-compatible
entries only, never for proper nouns). Ranking within the tier, deterministic:

1. reading agreement: the entry has a reading equal to the lemma's derived reading;
2. POS compatibility: Sudachi POS level 1 ↔ the entry's JMdict POS tags;
3. written-form fit: a kana-spelt token prefers entries usually written in kana;
4. frequency rank in the first configured list (``ref.freq_list`` lowest ``priority``);
5. the matched form carries a JMdict priority tag (``common``);
6. lowest ``seq``.

Digit strings (``名詞-数詞`` whose lemma is all digits) are never matched (``seq`` null).
"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from typing import Any, Literal, Protocol

from kotobaya_worker.dict.models import Entry
from kotobaya_worker.dict.normalize import fold_katakana, is_kanji, normalize_form
from kotobaya_worker.parse.segment import RawToken

TokenClass = Literal["content", "particle", "auxiliary", "name", "other"]
CLASSES: tuple[TokenClass, ...] = ("content", "name", "particle", "auxiliary", "other")
MAX_SEQ = 2**53 - 1


@dataclass(frozen=True)
class Token:
    """One token element (PARSER.md 1.1, token format 1). Offsets are UTF-16 code units."""

    s: int
    e: int
    surface: str
    lemma: str
    reading: str
    pos: tuple[str, ...]
    cform: str | None
    seq: int | None
    cls: TokenClass
    override: bool

    def to_json(self) -> dict[str, Any]:
        return {
            "s": self.s,
            "e": self.e,
            "surface": self.surface,
            "lemma": self.lemma,
            "reading": self.reading,
            "pos": list(self.pos),
            "cform": self.cform,
            "seq": self.seq,
            "class": self.cls,
            "override": self.override,
        }


# ------------------------------------------------------------------ dictionary lookups


@dataclass(frozen=True)
class FormHit:
    """An entry that has a given normalised form."""

    seq: int
    kind: Literal["kanji", "reading"]
    common: bool


@dataclass(frozen=True)
class EntryInfo:
    seq: int
    pos: frozenset[str]  # ref.dict_entry.pos
    readings: frozenset[str]  # normalised reading forms
    kana_usual: bool  # no kanji forms, or "uk" on any sense
    freq_rank: int | None  # rank in the first configured frequency list


class MatchLookup(Protocol):
    """What matching needs from the dictionary release. Arguments are ``normalize_form``-ed."""

    def forms(self, norm: str) -> Sequence[FormHit]: ...

    def entry(self, seq: int) -> EntryInfo | None:
        """The live entry, or None when the release does not hold ``seq``."""
        ...

    def is_key(self, norm: str) -> bool: ...


class InMemoryMatchLookup:
    """Dictionary held in memory: unit tests (JMdict fixture subsets) and small runs."""

    def __init__(self) -> None:
        self._forms: dict[str, dict[int, FormHit]] = defaultdict(dict)
        self._entries: dict[int, EntryInfo] = {}

    @staticmethod
    def from_entries(
        entries: Iterable[Entry], freq: Mapping[int, int] | None = None
    ) -> InMemoryMatchLookup:
        ref = InMemoryMatchLookup()
        ranks = freq or {}
        for e in entries:
            ref.add(
                e.seq,
                pos=[p for s in e.senses for p in s.pos],
                kanji=[(k.text, k.common) for k in e.kanji],
                readings=[(r.text, r.common) for r in e.readings],
                usually_kana=any("uk" in s.misc for s in e.senses),
                freq_rank=ranks.get(e.seq),
            )
        return ref

    def add(
        self,
        seq: int,
        *,
        pos: Iterable[str],
        kanji: Iterable[tuple[str, bool]] = (),
        readings: Iterable[tuple[str, bool]],
        usually_kana: bool = False,
        freq_rank: int | None = None,
    ) -> None:
        kanji = list(kanji)
        readings = list(readings)
        for kind, forms in (("kanji", kanji), ("reading", readings)):
            for text, common in forms:
                n = normalize_form(text)
                prev = self._forms[n].get(seq)
                # One hit per (norm, seq): a kanji hit beats a reading hit, common sticks.
                hit = FormHit(
                    seq,
                    "kanji" if kind == "kanji" or (prev and prev.kind == "kanji") else "reading",
                    common or bool(prev and prev.common),
                )
                self._forms[n][seq] = hit
        self._entries[seq] = EntryInfo(
            seq=seq,
            pos=frozenset(pos),
            readings=frozenset(normalize_form(r) for r, _ in readings),
            kana_usual=usually_kana or not kanji,
            freq_rank=freq_rank,
        )

    def forms(self, norm: str) -> Sequence[FormHit]:
        return tuple(self._forms.get(norm, {}).values())

    def entry(self, seq: int) -> EntryInfo | None:
        return self._entries.get(seq)

    def is_key(self, norm: str) -> bool:
        return norm in self._forms


# ------------------------------------------------------------------ class (PARSER.md 2)

_CONTENT_POS = frozenset(
    {"名詞", "代名詞", "動詞", "形容詞", "形状詞", "副詞", "連体詞", "接続詞", "感動詞", "接頭辞", "接尾辞"}
)  # fmt: skip
_AUX_TAGS = frozenset({"aux", "aux-v", "aux-adj"})


def derive_class(
    pos: Sequence[str], seq: int | None, entry_pos: frozenset[str] | None
) -> TokenClass:
    """PARSER.md 2. ``pos`` may be the full six Sudachi fields or the trimmed list."""
    p0 = pos[0] if len(pos) > 0 else None
    p1 = pos[1] if len(pos) > 1 else None
    e = entry_pos if (seq is not None and entry_pos) else frozenset()
    if p0 == "助詞":
        return "particle"
    if p0 == "助動詞":
        return "auxiliary"
    if p0 == "名詞" and p1 == "固有名詞":
        return "name"
    if e and e <= {"n-pr"}:
        return "name"
    if e and e <= {"prt"}:
        return "particle"
    if e and e <= _AUX_TAGS:
        return "auxiliary"
    if p0 == "名詞" and p1 == "数詞":
        return "other"
    if p0 in _CONTENT_POS:
        return "content"
    if not pos and seq is not None:
        return "content"
    return "other"


# ------------------------------------------------------------------ matching

# Sudachi POS level 1 -> JMdict POS tags that agree with it (verbs: any "v..." tag).
_POS_TAGS: dict[str, frozenset[str]] = {
    "名詞": frozenset(
        {"n", "n-adv", "n-t", "n-suf", "n-pref", "n-pr", "ctr", "num", "pn", "adj-na", "adj-no"}
    ),
    "代名詞": frozenset({"pn", "n"}),
    "形容詞": frozenset({"adj-i", "adj-ix"}),
    "形状詞": frozenset({"adj-na", "adj-no", "adj-t", "adj-f", "adj-nari"}),
    "副詞": frozenset({"adv", "adv-to", "n-adv"}),
    "連体詞": frozenset({"adj-pn", "adj-f"}),
    "接続詞": frozenset({"conj"}),
    "感動詞": frozenset({"int"}),
    "助詞": frozenset({"prt"}),
    "助動詞": frozenset({"aux", "aux-v", "aux-adj", "cop", "cop-da"}),
    "接頭辞": frozenset({"pref", "n-pref"}),
    "接尾辞": frozenset({"suf", "n-suf", "ctr"}),
}
_VERB_EXCLUDED = frozenset({"vs"})  # "noun taking する" is a noun tag


def pos_compatible(pos: Sequence[str], entry_pos: frozenset[str]) -> bool:
    p0 = pos[0] if pos else ""
    if p0 == "動詞":
        return any(t.startswith("v") and t not in _VERB_EXCLUDED for t in entry_pos)
    tags = _POS_TAGS.get(p0)
    return tags is not None and not tags.isdisjoint(entry_pos)


def _is_kana(text: str) -> bool:
    return bool(text) and all(
        0x3041 <= ord(c) <= 0x309F or 0x30A0 <= ord(c) <= 0x30FF for c in text
    )


def _has_kanji(text: str) -> bool:
    return any(is_kanji(c) for c in text)


def lemma_reading(tok: RawToken) -> str | None:
    """Hiragana reading of the lemma, derived from the surface reading; None if unknown.

    A kana lemma is its own reading. Otherwise the lemma and surface share a stem and differ
    only in trailing kana (見|まし -> 見る, 高かっ -> 高い): stem reading + the lemma's tail.
    """
    lemma = normalize_form(tok.lemma)
    if _is_kana(lemma):
        return lemma
    surface = normalize_form(tok.surface)
    reading = fold_katakana(tok.reading)
    n = 0
    while n < min(len(surface), len(lemma)) and surface[n] == lemma[n]:
        n += 1
    if n == 0:
        return None
    s_tail, l_tail = surface[n:], lemma[n:]
    if (s_tail and not _is_kana(s_tail)) or (l_tail and not _is_kana(l_tail)):
        return None
    s_tail = fold_katakana(s_tail)
    if not reading.endswith(s_tail):
        return None
    stem = reading[: len(reading) - len(s_tail)]
    return (stem + fold_katakana(l_tail)) if stem else None


def _is_digit_string(text: str) -> bool:
    return bool(text) and all(c.isdigit() for c in normalize_form(text))


def match_seq(tok: RawToken, lookup: MatchLookup) -> int | None:
    """The JMdict ``ent_seq`` for one raw token, or None (unmapped). Deterministic."""
    if tok.pos[:2] == ("名詞", "数詞") and _is_digit_string(tok.lemma):
        return None
    lemma = normalize_form(tok.lemma)
    surface = normalize_form(tok.surface)
    reading = lemma_reading(tok)
    kana_token = _is_kana(surface)

    hits: Sequence[FormHit] = lookup.forms(lemma)
    reading_tier = False
    if not hits and surface != lemma:
        hits = lookup.forms(surface)
    if (
        not hits
        and reading is not None
        and reading != lemma
        and tok.pos[:2] != ("名詞", "固有名詞")
    ):
        hits = [h for h in lookup.forms(reading) if h.kind == "reading"]
        reading_tier = True

    best: tuple[tuple[int, int, int, int, int, int], int] | None = None
    for h in hits:
        info = lookup.entry(h.seq)
        if info is None:
            continue
        pos_ok = pos_compatible(tok.pos, info.pos)
        if reading_tier and not pos_ok:
            continue
        reading_ok = reading is not None and reading in info.readings
        form_fit = not kana_token or h.kind == "kanji" or info.kana_usual or _has_kanji(lemma)
        key = (
            0 if reading_ok else 1,
            0 if pos_ok else 1,
            0 if form_fit else 1,
            info.freq_rank if info.freq_rank is not None else MAX_SEQ,
            0 if h.common else 1,
            h.seq,
        )
        if best is None or key < best[0]:
            best = (key, h.seq)
    return best[1] if best is not None else None


def entry_pos(lookup: MatchLookup, seq: int | None) -> frozenset[str] | None:
    if seq is None:
        return None
    info = lookup.entry(seq)
    return info.pos if info is not None else None


def to_token(tok: RawToken, lookup: MatchLookup) -> Token:
    """Step 3 for one raw token: match ``seq``, derive ``class``."""
    seq = match_seq(tok, lookup)
    return Token(
        s=tok.s,
        e=tok.e,
        surface=tok.surface,
        lemma=tok.lemma,
        reading=tok.reading,
        pos=tok.pos,
        cform=tok.cform,
        seq=seq,
        cls=derive_class(tok.pos, seq, entry_pos(lookup, seq)),
        override=False,
    )


__all__ = [
    "CLASSES",
    "EntryInfo",
    "FormHit",
    "InMemoryMatchLookup",
    "MatchLookup",
    "Token",
    "TokenClass",
    "derive_class",
    "lemma_reading",
    "match_seq",
    "pos_compatible",
    "to_token",
]
