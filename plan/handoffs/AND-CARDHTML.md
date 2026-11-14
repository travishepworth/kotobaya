# HANDOFF AND-CARDHTML

STATUS: DONE
BRANCH: t/AND-CARDHTML-empty-render
CHECK: `make check MODULE=android` → green (core untouched)

## Changed (≤ 6 lines)
- Root cause 1 (the error page): CardWebView answered every request through `CardPage.decide`. WebView passes `loadDataWithBaseURL`'s own document to `shouldInterceptRequest` as `data:text/html;charset=utf-8;base64,`. That got a blocked 403 response with an empty body, so Chromium showed ERR_HTTP_RESPONSE_CODE_FAILURE. The HTML was never empty: the repro on the DB copy gives a 725-char page. Fix: main-frame `data:` → `PageRequest.Document` → `null`.
- Root cause 2 (blank card): the production ReviewBackend never passed a DictionaryLookup. All 6594 vocab notes have `fields={}` plus `entry_seq`, so the front was `<div class="kb-expression"></div>`. Kanji data was not passed either. Fix: `InstalledRenderData`, and both lookups are now required. The phone's `dict_release_id=2`, so the dictionary is installed.
- Hardening: `CardPayloads`/`CardFailures`. A blank render or a main-frame load error shows the i18n `CardUnavailable` state, logs under KotobayaLoad, and sends one ClientErrors report per distinct reason (at most 10).

## Acceptance → tests
- Real-shaped regression → CardHtmlRegressionTest (vocab + kanji, with a real dictionary package)
- Blank/failed → error + report, no data: URL → CardPayloadTest, CardHtmlRegressionTest.withoutDictionaryData…

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- I could not check root cause 1 on a device: a data: load has no HTTP status, so only our intercepted 403 fits the error. Please check on the phone.
