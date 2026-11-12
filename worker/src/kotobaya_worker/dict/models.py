"""Records produced by the parsers and consumed by the loader."""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class Form:
    text: str
    common: bool
    tags: tuple[str, ...] = ()
    applies_to: tuple[str, ...] = ("*",)  # readings only


@dataclass(frozen=True)
class Gloss:
    lang: str
    text: str


@dataclass(frozen=True)
class Sense:
    pos: tuple[str, ...]
    misc: tuple[str, ...]
    field: tuple[str, ...]
    dialect: tuple[str, ...]
    info: tuple[str, ...]
    applies_to_kanji: tuple[str, ...]
    applies_to_kana: tuple[str, ...]
    gloss: tuple[Gloss, ...]
    xref: tuple[str, ...]


@dataclass(frozen=True)
class Entry:
    seq: int
    kanji: tuple[Form, ...]
    readings: tuple[Form, ...]
    senses: tuple[Sense, ...]


@dataclass(frozen=True)
class Kanji:
    literal: str
    on_readings: tuple[str, ...] = ()
    kun_readings: tuple[str, ...] = ()
    nanori: tuple[str, ...] = ()
    meanings: tuple[str, ...] = ()
    stroke_count: int | None = None
    grade: int | None = None
    jlpt: int | None = None
    freq_rank: int | None = None
    radical: str | None = None
    components: tuple[str, ...] = field(default=())


@dataclass(frozen=True)
class ReleaseInfo:
    """One ``ref.dict_release`` row. ``sources`` is the attribution-page provenance."""

    id: int
    version: str
    built_at: str  # ISO 8601 UTC instant
    sources: dict[str, dict[str, str]] = field(default_factory=dict)
    artifacts: list[dict[str, object]] = field(default_factory=list)


@dataclass(frozen=True)
class LoadResult:
    release_id: int
    entries: int  # live entries after the load
    retired: int  # entries retired by this load
    reappeared: int
    kanji: int
