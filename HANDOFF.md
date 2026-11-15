# HANDOFF ADR033-AND

STATUS: DONE
BRANCH: worktree-agent-afbab2143481b6724 (rebased on main ddfb4a7d; fix ce22c04a)
CHECK: `:android:testAnkidroidDebugUnitTest` and `:android:testDebugUnitTest` green, 400 tests (BackNavigationTest failed once in an earlier run, passed on the rerun)

## Changed
- `LocalQueueLoader.LEARN_AHEAD_MS = 0`. `SessionQueue.learnAheadMs` removed and `dayEndMs` added. `ReviewSession` shows a learning card only when `dueAt <= now`. Removed `aheadLocked`, `learnAheadAllowedLocked` and `Timed.admitted`.
- New `ReviewState.Waiting(stats, nextDueAt)`, used by all-decks sessions (normal and keep going) when only learning cards due later today remain. Deck sessions keep DeckDone.
- `ReviewViewModel`: `refreshIfEmpty` and the timed wake-up also handle Waiting. A refresh requested during a running refresh now runs again afterwards. The wake-up waits until the clock passes the due time.
- `ReviewScreen`: live "Next card in m:ss" countdown with stats, Keep going and Back. Strings are in `strings_review.xml`.
- Today counts: `LoadedQueue.counts.learning` now includes learning cards due later today, as the server does. Before this, the phone left them out.

## Acceptance → tests
- (a) `NoLearnAheadTest.aCardAnsweredAgainAsTheLastOneLeftWaitsAndResumesWhenDue`; auto-resume in `ReviewWaitingTest` (timer, on resume)
- (b) `...aNewCardOnItsTenMinuteStepDoesNotComeBackBeforeTheStep...`
- (c) `...threeLapsedCardsAreNeverShownTwiceWithinTheirStep`
- (d) `...aLongKeepGoingStreamNeverShowsACardBeforeItsDue...`, `...keepGoingServesNewCardsWhileALearningCardWaits`, `...theTodayCountsIncludeLearningCardsDueLaterToday`
- Updated: DeckEndTest (2), KeepGoingTest (stronger replacement `aRelearningCardIsNotServedBeforeItsDueWhenTheInMemoryQueueRunsDry`), DeckEntriesTest, ReviewSessionTest, LocalQueueLoaderTest

## New dependencies
- none

## For the reviewer
- Server later-learning count excludes KNOWN overrides; core and phone count them.
- Today, notifications and the widget now include waiting learning cards in "due".
