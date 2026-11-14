# AND-QUEUE — Android Home/Review shows "caught up" while 299 cards are due

- Phase: P9 hotfix · Model: opus · Risk: high
- Deps: none (live 0.1.11)
- Branch: t/AND-QUEUE-due-cards
- Owned paths (only edit these): android/, core/ (only if root cause is in core; say so in HANDOFF), fixtures/ (anonymized test data only)
- Module(s) for CI: android (+ core if touched)

## Read
- Spec: review queue / daily limits / study day sections of docs/SPEC.md
- Contracts: contracts/core/ (queue builder interfaces), contracts/db/ (card, deck, deck_item, review_log, settings)
- Prior tickets touching this code: plan/tickets/AND-PERF.md (todayReviews index rewrite, LoadGate), AND-STRICT.md, AND-POSTSYNC.md, AND-DECKS.md

## Symptom (live 0.1.11, real user, phone in UTC+9)
- Sync completes fine (pulledOps=36). Home and Review show "caught up" / no reviews.
- Phone DB (a copy, PRIVATE user data — read it, never commit it, never copy rows verbatim into the repo):
  `/tmp/claude-1000/-home-travmonkey-repos-kotobaya/14e7df7d-ac8c-49b3-99e3-adc26fe8ab51/scratchpad/dbcopy/kotobaya-app.db`
  (open read-only, or work on your own copy of it).
- In that DB: 9,219 non-new cards; 299 are suspended=0, deleted_at NULL, not merged, not buried, state in review/relearning, due_at <= now. 78,719 review_log rows, but only ~122 since today's 04:00 JST rollover. Settings table has only scheduler.kanji_before_vocab=true and scheduler.grading_buttons=4 (no limits, no timezone).
- Decks: one live media deck "Fatal Twelve" with content_version=1 but installed_content_version=0 (items never downloaded), one deleted media deck, a "Test" user deck with 1 deck_item, two deleted smoke decks. deck_item has only 3 rows total.
- 124 learning cards are suspended: VERIFIED identical on the server, so real data, not a bug.
- The server queue (same core builder, GET /review/queue) returns counts new 10 / learning 1 / review 183 for this user. So the bug is Android-side (local queue loading, limits, deck scoping, or how Android feeds the core builder).

## Do
1. Reproduce on the JVM against the DB copy (e.g. a throwaway local test or main that runs the Android local queue path — LocalQueueLoader / todayReviews / LoadGate / whatever Home and Review call — on that file with a fixed `now` = 2026-11-14T06:10Z, zone Asia/Tokyo). Find why it yields zero due. Suspects: deck-membership scoping (media deck with uninstalled items, or cards only reachable through deck_item), AND-PERF todayReviews/limit rewrite (unit, day-boundary or zone error making the review limit look used up), default-limit handling when settings have no limit keys, deck enabled/priority filter, Home vs Review using different loaders.
2. Fix the root cause. Keep AND-PERF performance (no full scans of review_log on the main path).
3. Regression test: a synthetic, anonymized fixture with the same shape (several thousand review-state cards with a few hundred due, a media deck with content_version > installed_content_version and no items, a user deck with 1 item, deleted decks, many old review_log rows plus a few today, no limit settings, phone zone UTC+9). Must fail on pre-fix code and pass after. Also assert Home's due count and the Review queue agree.
4. Secondary (report only, fix only if clearly a bug): the phone posted client-error "GET /meta failed: network" x7 at 05:55Z while offline. Confirm it's benign (e.g. not a retry storm, not reported when offline by design?) — one line in HANDOFF.
5. Note in HANDOFF (do not fix): server computes study day in UTC (day_ends_at 04:00Z) while the user is UTC+9; Android presumably uses device zone. Say whether Android vs server zone disagreement could cause or worsen this bug.

## Acceptance (each bullet -> at least one named test)
- [ ] Regression test from the real-shaped fixture fails before the fix, passes after.
- [ ] Home due count == Review queue size for that fixture (under limits).
- [ ] `make check MODULE=android` green (and core if touched).

## Out of scope
Server changes, timezone-setting feature, deck-content download UI, UI restyling.

## Notes from previous attempts
