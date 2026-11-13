# grammar-seed

Seed stubs for the grammar library: about 280 N3 to N1 grammar points, each with a
title, a display pattern and a JLPT level. Nothing else.

## Files

- `grammar-seed.json`: a JSON array of `{"title", "pattern", "jlpt"}` objects.
- `validate.py`: stdlib-only checker. Run `python3 fixtures/grammar-seed/validate.py`.

## Provenance

The pattern names and English labels were written by hand for this project from general
knowledge of Japanese grammar terminology. No explanations, example sentences, or wording
were copied from any textbook, dictionary, website or other source. Grammar pattern
names are short factual labels, not creative text. The file contains no meanings,
explanations, examples or links; those are written later by the user, or linked to
from published grammar dictionaries, never copied.

The level assignments are the author's approximations and may be wrong. They are only
a starting point for the user to correct.

## Licence

CC0 1.0 (public domain dedication). See the repository licence for the rest of the project.

## Field mapping to `app.grammar_point`

| Seed field | Column / op field | Notes |
| --- | --- | --- |
| `title` | `title` | English short label. Required, non-empty. |
| `pattern` | `pattern` | Display pattern, e.g. `〜ざるを得ない`. |
| `jlpt` | `jlpt` | N-level 1-3 here; the column allows 1-5. |
| (not in file) | `is_stub` | Set `true` when seeding. |
| (not in file) | `id`, `created_at`, `updated_at` | Assigned by the seeding job (T505b). |
| (not in file) | `meaning`, `explanation`, `notes`, `links`, `detection_rules`, `related_ids` | Left at column defaults. |

The op-level equivalent is `grammar.created` (`contracts/sync/ops/grammar.created.schema.json`).
The seed is loaded by T505b, not by this directory.

## Rules enforced by `validate.py`

- Top level is an array with at least 250 entries.
- Each entry has exactly the keys `title`, `pattern`, `jlpt`.
- `title` and `pattern` are non-empty strings without U+0000.
- `jlpt` is 1, 2 or 3.
- `(pattern, jlpt)` is unique.
