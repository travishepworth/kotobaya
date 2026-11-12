# HANDOFF C-5

STATUS: DONE
BRANCH: t/C-5-sync-portable-patterns
CHECK: `make check MODULE=contracts` → green

## Changed (≤ 6 lines)
- SYNC.md new 2.1: patterns are ECMA-262 (`$` = end of input, the ECMA `\s` set); depth limits MAX_OP_DEPTH 500 and MAX_DOCUMENT_DEPTH 512; invariant: every legal op fits in any envelope. Also updated 4, 5.2, 5.3, 6.1, 16, 17
- tools/validate.py: `ecma_to_python` translator plus self-tests, registered for 2020-12 `$ref`s; depth rules in raw-push verdict
- Json.kt / OpCodec.kt: KDoc only. SUMMARY.md: C-5 section
- NEW examples (T202a: re-embed): invalid/op-id-trailing-newline.json, invalid/note-created-tag-ideographic-space.json, invalid/note-created-tag-nbsp.json, invalid/note-created-tag-bom.json, raw-push/op-too-deep.{body.txt,expected.json}, raw-push/body-too-deep.{body.txt,expected.json}

## Acceptance → tests
- ECMA patterns, depth → check.sh (examples above, translator self-test vectors)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Depth counts containers on the deepest path; the op object is 1. op-too-deep: depth 500 is checked and depth 501 is schema_invalid. body-too-deep: body depth 513 → 400.
- T202a's parser checks `depth > MAX` on every value, scalars included. It disagrees with this definition only when the innermost container is empty, so count containers.
- jsonschema needed `version=` on extend. Without it, `$ref`s into files that declare `$schema` silently used Python `re`.
