# HANDOFF T508b

STATUS: DONE
BRANCH: t/T508b-grammar-capture-android
CHECK: `make check MODULE=android` -> green

## Changed
- android/.../grammar/: GrammarCaptureScreen.kt (select span in read-only field, new/attach, draft rule, server rule test, 422 inline), GrammarCaptureRepository.kt.
- res/values/strings_grammar_capture.xml (new strings).
- "Saw something weird" button hooked into media sentence list (MediaBrowseScreens) and struggle hot-word sentences.

## Acceptance -> tests
- Flow reachable from a sentence -> UI only, no tests (per ticket).

## New dependencies
- none

## For the reviewer
- Android has no dictionary-example sentence view, so no hook there.
- Draft rule = selected text (Android sentences carry no tokens; web uses token surfaces).
- Repo uses its own OkHttp call (ApiClient lacks PUT/PATCH), same pattern as StruggleRepository.
- Online only; mutations go through server handlers that emit ops.
