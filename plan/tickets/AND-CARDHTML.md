# AND-CARDHTML — Review WebView shows "ERR_HTTP_RESPONSE_CODE_FAILURE" for an empty data: URL

- Phase: P9 hotfix · Model: opus · Risk: high
- Deps: AND-QUEUE, AND-REVIEW-STALE (merged)
- Branch: t/AND-CARDHTML-empty-render
- Owned paths (only edit these): android/, assets/card/ (only if needed), core/ (only if root cause is in core rendering; say so), fixtures/ (anonymized only)
- Module(s) for CI: android (+ core if touched)

## Symptom (live 0.1.11, user 10, phone UI Japanese)
On the Review screen the card WebView shows Chromium's error page:
"ウェブページへのアクセス不可 — ウェブページ（data:text/html;charset=utf-8;base64,）は次の理由で読み込めませんでした: net::ERR_HTTP_RESPONSE_CODE_FAILURE"
The data: URL has an EMPTY base64 payload, so the HTML handed to the WebView was "". The queue does have cards (see plan/handoffs/AND-QUEUE.md).
Real phone DB copy (PRIVATE: read-only, never commit it or copy rows verbatim):
/tmp/claude-1000/-home-travmonkey-repos-kotobaya/14e7df7d-ac8c-49b3-99e3-adc26fe8ab51/scratchpad/dbcopy/kotobaya-app.db
Its due cards use note types ...101/...102 and are notes for dictionary entries (entry_seq). The Fatal Twelve media deck items were never downloaded. Check whether the local dictionary DB/blob is present on the phone and how rendering behaves when the dict entry, note type template, fields or card CSS asset is missing.

## Do
1. Reproduce on the JVM: take the first cards ReviewSession would show from the DB copy and run the exact Android render path (core renderer -> HTML -> WebView load call). Find why it yields "". Suspects: missing dict entry/offline dictionary, note type template lookup, field mapping, asset/CSS loading, a race where the WebView loads before the card arrives (initial state ""), or base64 encoding of a non-ASCII string.
2. Fix the root cause so these cards render.
3. Hardening: never load an empty data: URL. Use loadDataWithBaseURL (or the existing equivalent) with real HTML. If rendering fails or yields blank, show an i18n error state in Compose (not the Chromium page), log it with a tag (KotobayaLoad or the existing review tag), and send a client-error report (existing reporter, rate-limited).
4. The Chromium error page itself (ERR_HTTP_RESPONSE_CODE_FAILURE on a data: URL) must not be shown to users.

## Acceptance
- [ ] Regression test: render of a real-shaped (anonymized) due card that previously produced "" now produces non-empty HTML containing the expected front field.
- [ ] Test: blank/failed render -> error state + report, never an empty data: URL (unit-level on the component that builds the WebView payload).
- [ ] make check MODULE=android green (and core if touched).

## Out of scope
Home hero, queue refresh (done), server, deck-content download.

## Notes from previous attempts
