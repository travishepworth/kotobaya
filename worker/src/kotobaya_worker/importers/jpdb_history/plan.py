"""jpdb history export -> ``ImportPlan`` (pure; no DB beyond the read-only ``RefLookup``).

Authority: contracts/api/openapi.yaml ``importJpdbHistory`` and ``JobResultJpdbHistory`` (C-4),
ADR-008, ADR-009. ``plan_import`` decides everything that depends only on the export and the
dictionary: mapping, groups, de-duplication, grades, review ids, suspension, the per-record
buckets. ``job_result`` adds what depends on the live database (existing notes, cards, review
ids, overrides) and returns a complete ``JobResultJpdbHistory`` object.

``ImportPlan.to_json()`` schema (version 1), consumed by the server job (T207b)::

    {"version": 1,
     "options": {"import_reviews": bool, "import_word_status": bool},
     "vocab_groups": [{                       # one per mapped seq, first-appearance order
        "entry_seq": int, "fate": "carded" | "status_only", "card_kind": "meaning",
        "rows": [{"vid": int | null, "spelling": str, "reading": str,
                  "review_count": int, "method": str}],
        "reviews": [Review]}],                # rating events, group order; [] if status_only
     "kanji_notes": [{"character": str, "fields": {"literal": str, "keyword"?: str}}],
     "kanji_groups": [{                       # one per (character, card_kind)
        "character": str, "card_kind": "keyword" | "writing",
        "known_only": bool, "suspended_on_create": bool, "row_count": int,
        "reviews": [Review]}],
     "unmapped": [{"jpdb_vid", "spelling", "reading", "review_count"}],
     "kanji_unmapped": [{"character", "card_kind", "review_count"}],
     "counts": {...}}                         # RecordCounts, see below
    Review = {"id": uuid, "reviewed_at": int ms, "rating": 1..4, "button": str | null,
              "grade": str, "utc_offset_min": null, "duration_ms": null,
              "review_type": "scheduled", "import_source": "jpdb"}

The plan always lists every group, whatever the options; the emitter honours ``options``
(``import_reviews = false``: no notes, cards or reviews; ``import_word_status = false``: no
``word.status_set``). ``ledger`` (one entry per input row and per event, with its bucket) is
a diagnostic used by the tests and is not part of ``to_json``.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import asdict, dataclass, field
from typing import Any, Literal

from kotobaya_worker.importers.jpdb_history.mapping import map_vocab
from kotobaya_worker.importers.jpdb_history.ref import RefLookup
from kotobaya_worker.importers.jpdb_history.review_id import (
    INSTANT_MS_MAX,
    kanji_key,
    review_id,
    vocab_key,
)

PLAN_VERSION = 1

JP_EN = "cards_vocabulary_jp_en"
EN_JP = "cards_vocabulary_en_jp"
KANJI_KEYWORD = "cards_kanji_char_keyword"
KANJI_WRITING = "cards_kanji_keyword_char"
KANJI_ARRAYS: tuple[tuple[str, Literal["keyword", "writing"]], ...] = (
    (KANJI_KEYWORD, "keyword"),
    (KANJI_WRITING, "writing"),
)

# grade -> (rating, button); ADR-008: jpdb hard = FSRS Hard. Case-sensitive.
GRADE_TABLE: dict[str, tuple[int, str | None]] = {
    "nothing": (1, "nothing"),
    "something": (1, "something"),
    "hard": (2, "hard"),
    "okay": (3, "okay"),
    "easy": (4, "easy"),
    "pass": (3, None),
    "fail": (1, None),
}
KNOWN, UNKNOWN = "known", "unknown"

# Event buckets (JobResultJpdbHistory order). RATING is split by ``job_result`` into
# reviews_imported / reviews_already_imported / reviews_skipped (import_reviews = false).
B_SKIPPED = "reviews_skipped"
B_MALFORMED = "reviews_unknown_grade"
B_DUPLICATE = "reviews_duplicate_dropped"
B_UNKNOWN = "status_events_unknown"
B_KNOWN = "status_events_known"
B_UNKNOWN_GRADE = "reviews_unknown_grade"
B_RATING = "rating"
# Row buckets.
R_CARDED = "vocabulary_carded"
R_STATUS_ONLY = "vocabulary_status_only"
R_EN_JP = "vocabulary_en_jp_ignored"
R_UNMAPPED = "unmapped"
R_KANJI = "kanji_carded"
R_KANJI_UNMAPPED = "kanji_unmapped"


@dataclass(frozen=True)
class ImportOptions:
    import_reviews: bool = True
    import_word_status: bool = True

    @staticmethod
    def from_payload(payload: Mapping[str, Any]) -> ImportOptions:
        """``JpdbHistoryImportRequest`` defaults: both true."""
        return ImportOptions(
            import_reviews=payload.get("import_reviews", True) is not False,
            import_word_status=payload.get("import_word_status", True) is not False,
        )


@dataclass(frozen=True)
class PlannedReview:
    """One ``review.logged`` to emit (``card_id`` is the server's)."""

    id: str
    reviewed_at: int  # ms UTC = timestamp * 1000
    rating: int
    button: str | None
    grade: str

    def to_json(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "reviewed_at": self.reviewed_at,
            "rating": self.rating,
            "button": self.button,
            "grade": self.grade,
            "utc_offset_min": None,
            "duration_ms": None,
            "review_type": "scheduled",
            "import_source": "jpdb",
        }


@dataclass(frozen=True)
class VocabRow:
    vid: int | None
    spelling: str
    reading: str
    review_count: int
    method: str


@dataclass(frozen=True)
class VocabGroup:
    """Mapped ``cards_vocabulary_jp_en`` rows sharing one seq: one note, one ``meaning`` card."""

    entry_seq: int
    fate: Literal["carded", "status_only"]
    rows: tuple[VocabRow, ...]
    reviews: tuple[PlannedReview, ...]
    card_kind: Literal["meaning"] = "meaning"

    def to_json(self) -> dict[str, Any]:
        return {
            "entry_seq": self.entry_seq,
            "fate": self.fate,
            "card_kind": self.card_kind,
            "rows": [asdict(r) for r in self.rows],
            "reviews": [r.to_json() for r in self.reviews],
        }


@dataclass(frozen=True)
class KanjiNote:
    character: str
    keyword: str | None  # from the export (trimmed), else None -> renderer uses ref.kanji

    def fields(self) -> dict[str, str]:
        out = {"literal": self.character}
        if self.keyword is not None:
            out["keyword"] = self.keyword
        return out


@dataclass(frozen=True)
class KanjiGroup:
    """Kanji rows sharing (character, card kind): one card on the character's note."""

    character: str
    card_kind: Literal["keyword", "writing"]
    known_only: bool
    suspended_on_create: bool  # writing, or known-only; applies only when the card is created
    row_count: int
    reviews: tuple[PlannedReview, ...]

    def to_json(self) -> dict[str, Any]:
        return {
            "character": self.character,
            "card_kind": self.card_kind,
            "known_only": self.known_only,
            "suspended_on_create": self.suspended_on_create,
            "row_count": self.row_count,
            "reviews": [r.to_json() for r in self.reviews],
        }


@dataclass(frozen=True)
class UnmappedVocab:
    jpdb_vid: int
    spelling: str
    reading: str
    review_count: int


@dataclass(frozen=True)
class UnmappedKanji:
    character: str
    card_kind: str
    review_count: int


@dataclass(frozen=True)
class LedgerEntry:
    array: str
    row_index: int
    event_index: int | None  # None = the row itself
    bucket: str


@dataclass(frozen=True)
class RecordCounts:
    """Per-record buckets that do not depend on the database."""

    vocabulary_total: int = 0
    vocabulary_carded: int = 0
    vocabulary_status_only: int = 0
    vocabulary_en_jp_ignored: int = 0
    kanji_total: int = 0
    kanji_carded: int = 0
    reviews_total: int = 0
    reviews_rating: int = 0  # kept rating events of mapped groups (before import/existing split)
    reviews_skipped_rows: int = 0  # events of en_jp / unmapped rows
    reviews_unknown_grade: int = 0
    status_events_unknown: int = 0
    status_events_known: int = 0
    reviews_duplicate_dropped: int = 0


@dataclass(frozen=True)
class ImportPlan:
    options: ImportOptions
    vocab_groups: tuple[VocabGroup, ...]
    kanji_notes: tuple[KanjiNote, ...]
    kanji_groups: tuple[KanjiGroup, ...]
    unmapped: tuple[UnmappedVocab, ...]
    kanji_unmapped: tuple[UnmappedKanji, ...]
    counts: RecordCounts
    ledger: tuple[LedgerEntry, ...] = field(default=(), repr=False, compare=False)
    version: int = PLAN_VERSION

    def review_ids(self) -> list[str]:
        return [r.id for g in self.vocab_groups for r in g.reviews] + [
            r.id for g in self.kanji_groups for r in g.reviews
        ]

    def word_status_seqs(self) -> list[int]:
        """Seqs to set KNOWN (``origin = jpdb_import``) unless the word already has an override."""
        if not self.options.import_word_status:
            return []
        return [g.entry_seq for g in self.vocab_groups if g.fate == "status_only"]

    def to_json(self) -> dict[str, Any]:
        return {
            "version": self.version,
            "options": asdict(self.options),
            "vocab_groups": [g.to_json() for g in self.vocab_groups],
            "kanji_notes": [
                {"character": n.character, "fields": n.fields()} for n in self.kanji_notes
            ],
            "kanji_groups": [g.to_json() for g in self.kanji_groups],
            "unmapped": [asdict(u) for u in self.unmapped],
            "kanji_unmapped": [asdict(u) for u in self.kanji_unmapped],
            "counts": asdict(self.counts),
        }


# ------------------------------------------------------------------ export reading (drift-tolerant


def _array(export: Mapping[str, Any], name: str) -> list[Any]:
    value = export.get(name)
    return value if isinstance(value, list) else []


def _events(row: Any) -> list[Any]:
    revs = row.get("reviews") if isinstance(row, dict) else None
    return revs if isinstance(revs, list) else []


def _str(row: Any, name: str) -> str:
    value = row.get(name) if isinstance(row, dict) else None
    return value if isinstance(value, str) else ""


def _int(value: Any) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) else None


@dataclass(frozen=True)
class _Event:
    timestamp: int
    grade: str
    row_order: int
    index: int
    key: str  # review id key of the event's own row


def _well_formed(raw: Any) -> tuple[int, str] | None:
    """An object with an integer ``timestamp`` (inside the instant_ms range) and a string grade."""
    if not isinstance(raw, dict):
        return None
    ts, grade = _int(raw.get("timestamp")), raw.get("grade")
    if ts is None or not isinstance(grade, str) or not 0 <= ts * 1000 <= INSTANT_MS_MAX:
        return None
    return ts, grade


@dataclass
class _Merged:
    """A group's history after merge + de-dup."""

    kept: list[_Event]
    buckets: dict[tuple[int, int], str]  # (row_order, index) -> event bucket

    def known_only(self) -> bool:
        return bool(self.kept) and all(e.grade == KNOWN for e in self.kept)

    def reviews(self) -> tuple[PlannedReview, ...]:
        out: list[PlannedReview] = []
        occ: dict[int, int] = {}
        for e in self.kept:
            n = occ.get(e.timestamp, 0)
            occ[e.timestamp] = n + 1
            rating = GRADE_TABLE.get(e.grade)
            if rating is None:
                continue
            out.append(
                PlannedReview(
                    id=review_id(e.key, e.timestamp, e.grade, n),
                    reviewed_at=e.timestamp * 1000,
                    rating=rating[0],
                    button=rating[1],
                    grade=e.grade,
                )
            )
        return tuple(out)


def _merge(rows: Sequence[tuple[int, Any, str]]) -> _Merged:
    """Merge rows (row_order, row, key): sort by timestamp (ties: row order, element order),
    drop exact duplicates (same timestamp and grade as an earlier kept event)."""
    buckets: dict[tuple[int, int], str] = {}
    events: list[_Event] = []
    for row_order, row, key in rows:
        for i, raw in enumerate(_events(row)):
            wf = _well_formed(raw)
            if wf is None:
                buckets[(row_order, i)] = B_MALFORMED
            else:
                events.append(_Event(wf[0], wf[1], row_order, i, key))
    events.sort(key=lambda e: (e.timestamp, e.row_order, e.index))
    seen: set[tuple[int, str]] = set()
    kept: list[_Event] = []
    for e in events:
        sig = (e.timestamp, e.grade)
        if sig in seen:
            buckets[(e.row_order, e.index)] = B_DUPLICATE
            continue
        seen.add(sig)
        kept.append(e)
        if e.grade == UNKNOWN:
            bucket = B_UNKNOWN
        elif e.grade == KNOWN:
            bucket = B_KNOWN
        elif e.grade in GRADE_TABLE:
            bucket = B_RATING
        else:
            bucket = B_UNKNOWN_GRADE
        buckets[(e.row_order, e.index)] = bucket
    return _Merged(kept, buckets)


# ------------------------------------------------------------------ planning


def plan_import(
    export: Mapping[str, Any], ref_lookup: RefLookup, options: ImportOptions
) -> ImportPlan:
    """Plan a jpdb history import. Pure: same export + dictionary -> same plan (and review ids)."""
    ledger: list[LedgerEntry] = []

    def skip_row(array: str, i: int, row: Any, bucket: str) -> None:
        ledger.append(LedgerEntry(array, i, None, bucket))
        for j in range(len(_events(row))):
            ledger.append(LedgerEntry(array, i, j, B_SKIPPED))

    # --- vocab
    jp_en = _array(export, JP_EN)
    en_jp = _array(export, EN_JP)
    unmapped: list[UnmappedVocab] = []
    by_seq: dict[int, list[tuple[int, Any, VocabRow]]] = {}
    for i, row in enumerate(jp_en):
        vid = _int(row.get("vid")) if isinstance(row, dict) else None
        spelling, reading = _str(row, "spelling"), _str(row, "reading")
        mapped = map_vocab(ref_lookup, spelling, reading, vid)
        n = len(_events(row))
        if mapped.seq is None:
            unmapped.append(UnmappedVocab(vid if vid is not None else 0, spelling, reading, n))
            skip_row(JP_EN, i, row, R_UNMAPPED)
            continue
        by_seq.setdefault(mapped.seq, []).append(
            (i, row, VocabRow(vid, spelling, reading, n, mapped.method))
        )
    for i, row in enumerate(en_jp):
        skip_row(EN_JP, i, row, R_EN_JP)

    vocab_groups: list[VocabGroup] = []
    for seq, members in by_seq.items():
        merged = _merge([(i, row, vocab_key(vr.vid)) for i, row, vr in members])
        status_only = merged.known_only()
        row_bucket = R_STATUS_ONLY if status_only else R_CARDED
        for i, row, _ in members:
            ledger.append(LedgerEntry(JP_EN, i, None, row_bucket))
            for j in range(len(_events(row))):
                ledger.append(LedgerEntry(JP_EN, i, j, merged.buckets[(i, j)]))
        vocab_groups.append(
            VocabGroup(
                entry_seq=seq,
                fate="status_only" if status_only else "carded",
                rows=tuple(vr for _, _, vr in members),
                reviews=() if status_only else merged.reviews(),
            )
        )

    # --- kanji
    kanji_unmapped: list[UnmappedKanji] = []
    by_char_kind: dict[tuple[str, Literal["keyword", "writing"]], list[tuple[int, Any]]] = {}
    keywords: dict[str, str | None] = {}
    for array, kind in KANJI_ARRAYS:
        for i, row in enumerate(_array(export, array)):
            ch = _str(row, "character")
            if len(ch) != 1 or "\x00" in ch:
                # U+0000 cannot be a kanji_literal (SYNC.md 2.1): the whole row (every event) is reported
                # unmapped, whatever the options; its rating events count as skipped.
                kanji_unmapped.append(UnmappedKanji(ch, kind, len(_events(row))))
                skip_row(array, i, row, R_KANJI_UNMAPPED)
                continue
            by_char_kind.setdefault((ch, kind), []).append((i, row))
            kw = _str(row, "keyword").strip()
            if keywords.get(ch) is None:
                keywords[ch] = kw or None

    kanji_groups: list[KanjiGroup] = []
    array_of = {kind: array for array, kind in KANJI_ARRAYS}
    for (ch, kind), krows in by_char_kind.items():
        key = kanji_key(kind, ch)
        merged = _merge([(i, row, key) for i, row in krows])
        array = array_of[kind]
        for i, row in krows:
            ledger.append(LedgerEntry(array, i, None, R_KANJI))
            for j in range(len(_events(row))):
                ledger.append(LedgerEntry(array, i, j, merged.buckets[(i, j)]))
        known_only = merged.known_only()
        kanji_groups.append(
            KanjiGroup(
                character=ch,
                card_kind=kind,
                known_only=known_only,
                suspended_on_create=kind == "writing" or known_only,
                row_count=len(krows),
                reviews=merged.reviews(),
            )
        )
    kanji_notes = tuple(KanjiNote(ch, kw) for ch, kw in keywords.items())

    # --- counts (from the ledger, so every record is counted exactly once)
    tally: dict[str, int] = {}
    for e in ledger:
        tally[e.bucket] = tally.get(e.bucket, 0) + 1
    rows_vocab = len(jp_en) + len(en_jp)
    counts = RecordCounts(
        vocabulary_total=rows_vocab,
        vocabulary_carded=tally.get(R_CARDED, 0),
        vocabulary_status_only=tally.get(R_STATUS_ONLY, 0),
        vocabulary_en_jp_ignored=tally.get(R_EN_JP, 0),
        kanji_total=sum(len(_array(export, a)) for a, _ in KANJI_ARRAYS),
        kanji_carded=tally.get(R_KANJI, 0),
        reviews_total=sum(1 for e in ledger if e.event_index is not None),
        reviews_rating=tally.get(B_RATING, 0),
        reviews_skipped_rows=tally.get(B_SKIPPED, 0),
        reviews_unknown_grade=tally.get(B_MALFORMED, 0),  # == B_UNKNOWN_GRADE
        status_events_unknown=tally.get(B_UNKNOWN, 0),
        status_events_known=tally.get(B_KNOWN, 0),
        reviews_duplicate_dropped=tally.get(B_DUPLICATE, 0),
    )
    return ImportPlan(
        options=options,
        vocab_groups=tuple(vocab_groups),
        kanji_notes=kanji_notes,
        kanji_groups=tuple(kanji_groups),
        unmapped=tuple(unmapped),
        kanji_unmapped=tuple(kanji_unmapped),
        counts=counts,
        ledger=tuple(ledger),
    )


# ------------------------------------------------------------------ job result


@dataclass(frozen=True)
class ExistingState:
    """Live database state the result depends on (empty = first import into an empty account)."""

    vocab_note_seqs: frozenset[int] = frozenset()  # live vocab notes by entry_seq
    meaning_card_seqs: frozenset[int] = frozenset()  # seqs whose live note has a live meaning card
    kanji_note_chars: frozenset[str] = frozenset()  # live kanji notes by kanji_literal
    kanji_cards: frozenset[tuple[str, str]] = frozenset()  # live (character, card kind)
    review_ids: frozenset[str] = frozenset()  # review_log ids (any state, undone included)
    word_override_seqs: frozenset[int] = frozenset()  # seqs with a live word-status override


def job_result(plan: ImportPlan, existing: ExistingState | None = None) -> dict[str, Any]:
    """The full ``JobResultJpdbHistory`` this plan yields against ``existing`` (None = empty)."""
    existing = existing or ExistingState()
    c = plan.counts
    opts = plan.options
    imported = already = collisions = 0
    if opts.import_reviews:
        emitted: dict[str, str] = {}  # review id -> group it first appeared in
        groups = [(f"v{g.entry_seq}", g.reviews) for g in plan.vocab_groups] + [
            (f"k{k.character}/{k.card_kind}", k.reviews) for k in plan.kanji_groups
        ]
        for group, reviews in groups:
            for r in reviews:
                rid = r.id
                # An id seen earlier in this run is "already imported" too (it would hit review_log).
                if rid in existing.review_ids:
                    already += 1
                elif rid in emitted:
                    already += 1
                    # Held by a different group's card: the known id collision (contract, C-9).
                    collisions += emitted[rid] != group
                else:
                    imported += 1
                    emitted[rid] = group
    skipped = c.reviews_skipped_rows + (0 if opts.import_reviews else c.reviews_rating)

    notes_created = notes_existing = cards_created = 0
    k_notes_created = k_notes_existing = k_cards_created = k_cards_suspended = 0
    if opts.import_reviews:
        for g in plan.vocab_groups:
            if g.fate != "carded":
                continue
            if g.entry_seq in existing.vocab_note_seqs:
                notes_existing += 1
            else:
                notes_created += 1
            if g.entry_seq not in existing.meaning_card_seqs:
                cards_created += 1
        for n in plan.kanji_notes:
            if n.character in existing.kanji_note_chars:
                k_notes_existing += 1
            else:
                k_notes_created += 1
        for k in plan.kanji_groups:
            if (k.character, k.card_kind) not in existing.kanji_cards:
                k_cards_created += 1
                k_cards_suspended += k.suspended_on_create
    statuses = sum(1 for s in plan.word_status_seqs() if s not in existing.word_override_seqs)

    return {
        "vocabulary_total": c.vocabulary_total,
        "vocabulary_carded": c.vocabulary_carded,
        "vocabulary_status_only": c.vocabulary_status_only,
        "vocabulary_en_jp_ignored": c.vocabulary_en_jp_ignored,
        "notes_created": notes_created,
        "notes_existing": notes_existing,
        "cards_created": cards_created,
        "kanji_total": c.kanji_total,
        "kanji_carded": c.kanji_carded,
        "kanji_notes_created": k_notes_created,
        "kanji_notes_existing": k_notes_existing,
        "kanji_cards_created": k_cards_created,
        "kanji_cards_suspended": k_cards_suspended,
        "kanji_unmapped": [asdict(u) for u in plan.kanji_unmapped],
        "reviews_total": c.reviews_total,
        "reviews_imported": imported,
        "reviews_already_imported": already,
        "reviews_skipped": skipped,
        "reviews_unknown_grade": c.reviews_unknown_grade,
        "reviews_duplicate_dropped": c.reviews_duplicate_dropped,
        "status_events_unknown": c.status_events_unknown,
        "status_events_known": c.status_events_known,
        "word_statuses_set": statuses,
        "unmapped": [asdict(u) for u in plan.unmapped],
        "reviews_id_collisions": collisions,
    }
