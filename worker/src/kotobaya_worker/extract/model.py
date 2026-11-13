"""Shared extractor model: what every own-file extractor returns."""

from __future__ import annotations

import posixpath
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Protocol

# (UTF-16 start, UTF-16 end, reading) into the sentence text; used for ruby/furigana (T402b).
ReadingHint = tuple[int, int, str]

FORMATS = ("srt", "ass", "vtt", "epub", "txt", "mokuro", "html", "texthooker")

_EXTENSIONS: dict[str, str] = {
    ".srt": "srt",
    ".ass": "ass",
    ".ssa": "ass",
    ".vtt": "vtt",
    ".epub": "epub",
    ".txt": "txt",
    ".mokuro": "mokuro",
    ".html": "html",
    ".htm": "html",
    ".xhtml": "html",
}


@dataclass(frozen=True)
class ExtractedSentence:
    text: str
    start_ms: int | None = None
    end_ms: int | None = None
    location: str | None = None
    reading_hints: tuple[ReadingHint, ...] = ()


@dataclass(frozen=True)
class ExtractedSegment:
    kind: str  # episode | chapter | volume | page | whole
    title: str
    sentences: tuple[ExtractedSentence, ...]


class Extractor(Protocol):
    def __call__(
        self, data: bytes, filename: str, options: Mapping[str, object]
    ) -> list[ExtractedSegment]: ...


def default_title(filename: str) -> str:
    """File name without directory and extension (the segment title default)."""
    base = posixpath.basename(filename.replace("\\", "/"))
    stem, _ext = posixpath.splitext(base)
    return stem or base


def detect_format(filename: str, declared: str = "auto") -> str:
    """Resolve the `format` of a file import. `declared` is the request's `format` enum value."""
    if declared != "auto":
        if declared not in FORMATS:
            raise ValueError(f"unknown format: {declared!r}")
        return declared
    ext = posixpath.splitext(filename.lower())[1]
    try:
        return _EXTENSIONS[ext]
    except KeyError:
        raise ValueError(f"cannot detect format from file name {filename!r}") from None
