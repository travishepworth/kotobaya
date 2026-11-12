"""Deterministic review ids for imported jpdb events (openapi ``importJpdbHistory`` "Review ids").

The one place the id is defined; the golden vector is in ``test_review_id_golden_vector``.
"""

from __future__ import annotations

import hashlib
import uuid

INSTANT_MS_MAX = (1 << 48) - 1  # common.schema.json instant_ms maximum


def vocab_key(vid: int | None) -> str:
    """``"v" + vid`` of the event's own row; ``"v0"`` when the row has no integer vid."""
    return f"v{vid if vid is not None else 0}"


def kanji_key(card_kind: str, character: str) -> str:
    """``"k:keyword:" + character`` or ``"k:writing:" + character``."""
    return f"k:{card_kind}:{character}"


def review_id(key: str, timestamp: int, grade: str, occ: int) -> str:
    """UUIDv7: 48-bit ``reviewed_at`` (= timestamp x 1000) then bits of SHA-256 of the name.

    ``name = "jpdb-review/v1|" + key + "|" + timestamp + "|" + grade + "|" + occ`` where
    ``timestamp`` is the export's decimal seconds and ``occ`` the 0-based index of the event among
    its group's kept events with the same timestamp. Bytes: ``b[0..5]`` = reviewed_at big-endian,
    ``b[6] = 0x70 | (h[0] & 0x0F)``, ``b[7] = h[1]``, ``b[8] = 0x80 | (h[2] & 0x3F)``,
    ``b[9..15] = h[3..9]``.
    """
    reviewed_at = timestamp * 1000
    if not 0 <= reviewed_at <= INSTANT_MS_MAX:
        raise ValueError(f"timestamp {timestamp} outside the instant_ms range")
    name = f"jpdb-review/v1|{key}|{timestamp}|{grade}|{occ}"
    h = hashlib.sha256(name.encode("utf-8")).digest()
    b = (
        reviewed_at.to_bytes(6, "big")
        + bytes([0x70 | (h[0] & 0x0F), h[1], 0x80 | (h[2] & 0x3F)])
        + h[3:10]
    )
    return str(uuid.UUID(bytes=b))
