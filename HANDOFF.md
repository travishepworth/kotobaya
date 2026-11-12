# HANDOFF T201a

STATUS: DONE
BRANCH: t/T201a-card-generator
CHECK: `make check MODULE=core` → green

## Changed (≤ 6 lines)
- core/.../kotobaya/cards/: DefaultCardGenerator (implements contracts CardGenerator), BuiltInNoteTypes (6 seeds), TemplateFields (`{{key}}` escaped, `#`/`^` sections, `{{FrontSide}}`).
- Tests: commonTest/cards (generator, substitution); jvmTest/cards drift test vs V1__init.sql.

## Acceptance → tests
- Default cards per type → CardGeneratorTest.defaultCardsPerNoteType
- Toggle on/off → enablingOptionalKindCreatesForExistingNotes, disablingOptionalKindCreatesNothing
- Escaping/missing → TemplateFieldsTest.escapesHtmlInValues, missingFieldsAndSections
- Drift → BuiltInNoteTypesDriftTest.builtInsEqualV1Seeds

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Followed the contract's CardGenerator signatures (no note fields), so "skip a kind whose template renders empty" is not implemented; it belongs to the caller/T213a.
- Drift test lives in core/src/jvmTest/kotlin/kotobaya/cards/ (outside the listed owned paths, but required by "jvm test source set").
- Branch was stale; merged main first.
