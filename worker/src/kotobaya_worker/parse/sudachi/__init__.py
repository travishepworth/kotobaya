"""SudachiPy wrapper: mode C segmentation with a B-then-A fallback (SPEC Parsing step 2).

Only this package imports ``sudachipy``; everything downstream sees plain :class:`Morph`
records.

Threading: loading a Sudachi dictionary is expensive (hundreds of MB for ``full``), so
:func:`get_analyzer` loads it once per process per dictionary name and caches the
:class:`Analyzer`. Sudachi tokenizers are NOT safe to share between threads, so the analyzer
keeps one tokenizer per thread (``threading.local``); an :class:`Analyzer` may therefore be
shared freely across threads, and :meth:`Analyzer.analyze` is safe to call concurrently.
"""

from __future__ import annotations

import importlib.util
import threading
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from functools import cache
from typing import Literal

from sudachipy import (  # type: ignore[import-untyped]  # ships no py.typed
    Dictionary,
    Morpheme,
    SplitMode,
    Tokenizer,
)

DictName = Literal["full", "core", "small"]
# Preference order: the spec asks for ``full``; ``core`` is the offline fallback (ADR-019).
DICT_PREFERENCE: tuple[DictName, ...] = ("full", "core", "small")


@dataclass(frozen=True)
class Morph:
    """One Sudachi morpheme as plain data. Offsets are code points into the analysed text."""

    begin: int
    end: int
    surface: str
    dictionary_form: str
    normalized_form: str
    reading: str  # katakana, as Sudachi reports it
    pos: tuple[str, str, str, str, str, str]  # Sudachi's 6 fields; [4] conj type, [5] cform

    @property
    def conj_type(self) -> str:
        return self.pos[4]

    @property
    def cform(self) -> str:
        return self.pos[5]


KeepPredicate = Callable[[Morph], bool]


def _to_morph(m: Morpheme) -> Morph:
    p = m.part_of_speech()
    return Morph(
        begin=m.begin(),
        end=m.end(),
        surface=m.surface(),
        dictionary_form=m.dictionary_form(),
        normalized_form=m.normalized_form(),
        reading=m.reading_form(),
        pos=(p[0], p[1], p[2], p[3], p[4], p[5]),
    )


def is_dictionary_installed(name: DictName) -> bool:
    return importlib.util.find_spec(f"sudachidict_{name}") is not None


def installed_dictionaries() -> tuple[DictName, ...]:
    """Installed Sudachi dictionaries, most preferred first."""
    return tuple(n for n in DICT_PREFERENCE if is_dictionary_installed(n))


def best_dictionary() -> DictName:
    found = installed_dictionaries()
    if not found:
        raise RuntimeError(
            "no Sudachi dictionary installed: install sudachidict_full (or sudachidict_core)"
        )
    return found[0]


_NO_SPLIT: tuple[SplitMode, ...] = ()
_FALLBACK_MODES: tuple[SplitMode, ...] = (SplitMode.B, SplitMode.A)


def _refine(m: Morpheme, keep: KeepPredicate, modes: Sequence[SplitMode]) -> list[Morph]:
    """Keep ``m`` if the predicate accepts it; else split with the next mode, recursively.

    A mode that does not split ``m`` (Sudachi returns no sub-morphemes) is skipped, so a C
    token that B leaves whole is still tried with A.
    """
    morph = _to_morph(m)
    if not modes or keep(morph):
        return [morph]
    parts = m.split(modes[0])
    if len(parts) == 0:
        return _refine(m, keep, modes[1:])
    out: list[Morph] = []
    for part in parts:
        out.extend(_refine(part, keep, modes[1:]))
    return out


class Analyzer:
    """A loaded Sudachi dictionary. Thread-safe: one tokenizer per calling thread."""

    def __init__(self, dict_name: DictName) -> None:
        self.dict_name = dict_name
        self._dictionary = Dictionary(dict=dict_name)
        self._local = threading.local()

    def _tokenizer(self) -> Tokenizer:
        tok: Tokenizer | None = getattr(self._local, "tokenizer", None)
        if tok is None:
            tok = self._dictionary.create(SplitMode.C)
            self._local.tokenizer = tok
        return tok

    def analyze(self, text: str, keep: KeepPredicate | None = None) -> list[Morph]:
        """Mode C morphemes of ``text``; those ``keep`` rejects are re-split with B, then A.

        ``keep=None`` disables the fallback (plain mode C).
        """
        modes = _NO_SPLIT if keep is None else _FALLBACK_MODES
        accept: KeepPredicate = keep if keep is not None else (lambda _m: True)
        out: list[Morph] = []
        for m in self._tokenizer().tokenize(text, SplitMode.C):
            out.extend(_refine(m, accept, modes))
        return out


@cache
def _load(dict_name: DictName) -> Analyzer:
    return Analyzer(dict_name)


def get_analyzer(dict_name: DictName | None = None) -> Analyzer:
    """The process-wide analyzer for ``dict_name`` (default: best installed dictionary)."""
    return _load(dict_name if dict_name is not None else best_dictionary())
