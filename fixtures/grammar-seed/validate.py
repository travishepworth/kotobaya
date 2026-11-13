"""Validate grammar-seed.json. Stdlib only, Python 3.12.

Exit status is 0 when every check passes, 1 otherwise.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

SEED_PATH = Path(__file__).resolve().parent / "grammar-seed.json"
MIN_COUNT = 250
ALLOWED_JLPT = frozenset({1, 2, 3})
ALLOWED_KEYS = frozenset({"title", "pattern", "jlpt"})


def validate(entries: object) -> list[str]:
    errors: list[str] = []
    if not isinstance(entries, list):
        return ["top level must be a JSON array"]
    if len(entries) < MIN_COUNT:
        errors.append(f"count {len(entries)} is below the minimum {MIN_COUNT}")

    seen: dict[tuple[str, int], int] = {}
    seen_titles: dict[tuple[str, int], int] = {}
    for index, entry in enumerate(entries):
        where = f"entry {index}"
        if not isinstance(entry, dict):
            errors.append(f"{where}: not an object")
            continue
        keys = set(entry.keys())
        if keys != ALLOWED_KEYS:
            errors.append(f"{where}: keys {sorted(keys)} must be exactly {sorted(ALLOWED_KEYS)}")
            continue

        title = entry["title"]
        pattern = entry["pattern"]
        jlpt = entry["jlpt"]

        for field, value in (("title", title), ("pattern", pattern)):
            if not isinstance(value, str):
                errors.append(f"{where}: {field} must be a string")
            elif value.strip() == "":
                errors.append(f"{where}: {field} is empty")
            elif "\x00" in value:
                errors.append(f"{where}: {field} contains U+0000")

        if isinstance(jlpt, bool) or not isinstance(jlpt, int) or jlpt not in ALLOWED_JLPT:
            errors.append(f"{where}: jlpt {jlpt!r} must be one of 1, 2, 3")

        if isinstance(pattern, str) and isinstance(jlpt, int) and not isinstance(jlpt, bool):
            key = (pattern, jlpt)
            if key in seen:
                errors.append(
                    f"{where}: duplicate (pattern, jlpt) {key!r}, first at entry {seen[key]}"
                )
            else:
                seen[key] = index

        if isinstance(title, str) and isinstance(jlpt, int) and not isinstance(jlpt, bool):
            title_key = (title.strip().casefold(), jlpt)
            if title_key in seen_titles:
                errors.append(
                    f"{where}: duplicate (title, jlpt) {(title, jlpt)!r}, first at entry {seen_titles[title_key]}"
                )
            else:
                seen_titles[title_key] = index

    return errors


def main() -> int:
    try:
        raw = SEED_PATH.read_text(encoding="utf-8")
    except OSError as exc:
        print(f"cannot read {SEED_PATH}: {exc}", file=sys.stderr)
        return 1
    try:
        entries = json.loads(raw)
    except json.JSONDecodeError as exc:
        print(f"invalid JSON in {SEED_PATH}: {exc}", file=sys.stderr)
        return 1

    errors = validate(entries)
    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        print(f"{len(errors)} error(s)", file=sys.stderr)
        return 1

    assert isinstance(entries, list)
    print(f"OK: {len(entries)} grammar point stubs")
    return 0


if __name__ == "__main__":
    sys.exit(main())
