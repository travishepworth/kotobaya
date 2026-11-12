"""Search-key normalisation (contracts/db/SCHEMA.md 4.5, ``dict_entry_form.norm``)."""

from __future__ import annotations

import unicodedata

_KATA_FIRST = 0x30A1
_KATA_LAST = 0x30F6
_ITER_KATA = (0x30FD, 0x30FE)  # ヽ ヾ -> ゝ ゞ
_SHIFT = 0x60


def fold_katakana(text: str) -> str:
    """Fold katakana to hiragana; the prolonged sound mark and all else stay."""
    out: list[str] = []
    for ch in text:
        cp = ord(ch)
        if _KATA_FIRST <= cp <= _KATA_LAST or cp in _ITER_KATA:
            out.append(chr(cp - _SHIFT))
        else:
            out.append(ch)
    return "".join(out)


def normalize_form(text: str) -> str:
    """NFKC, then katakana folded to hiragana. Queries are normalised the same way."""
    return fold_katakana(unicodedata.normalize("NFKC", text))


def is_kanji(ch: str) -> bool:
    """True for CJK ideographs (unified, extensions, compatibility)."""
    cp = ord(ch)
    return (
        0x3400 <= cp <= 0x4DBF
        or 0x4E00 <= cp <= 0x9FFF
        or 0xF900 <= cp <= 0xFAFF
        or 0x20000 <= cp <= 0x2FA1F
        or 0x30000 <= cp <= 0x323AF
    )
