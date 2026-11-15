# HANDOFF ADR033-SERVER
Status: DONE. Branch worktree-agent-a8c79bd6546cdc6d6 (merged main first for the contract commit).

Changes (server only):
- QueueLoader: LEARN_AHEAD_MS = 0 (ADR-033). Pending deck entries are now always loaded at the normal-budget size (also under keep_going) so counts from the normal build are exact.
- ReviewApi.render: `counts` always from the normal build (keep_going=true re-builds from the same loaded input with keepGoing=false); learning = due learning + later-today learning, no double count. `next_learning_due_at` = core nextLearningDueAt, omitted when null, independent of kinds/keep_going.
- Audit: `/stats/summary` `due` and `more_available` already use the normal build with the same formula (identical numbers to /review/queue counts); no divergence, no change. Summary does not expose next_learning_due_at (not in task).
- Updated existing tests that relied on learn-ahead / keep-going counts: GrammarQueueSentenceTest (advance clock 20 min), ReviewApiTest keep-going counts.new now 0.

New tests (ReviewApiTest): aLearningCardNotYetDueIsNotServedButReportedAndCounted (a), countsIgnoreKeepGoingWhileItemsStillServeNewCards (b), aCardAnsweredAgainIsNotReturnedByTheNextFetch (c).
`make check MODULE=server`: first full run had only the two now-fixed failures; review.* suite green after fixes (full re-run not repeated).
No new dependencies. No contract change requests.
