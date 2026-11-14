# AND-REVIEWERR — Review and write VMs surface failures (AND-POSTSYNC review minors)

- Phase: P10 (0.1.10) · Model: sonnet · Risk: med · Deps: AND-POSTSYNC, AND-INSETS merged
- Branch: t/AND-REVIEWERR · Owned paths: android/** · Module: android

## Do
Since AND-POSTSYNC, StateViewModel.launchIo logs failures and calls onFailure instead of crashing.
1. ReviewViewModel: override onFailure → error state with Retry (i18n strings), clear sessionJob so enter() retries.
2. ReviewSession.answer: if mint/advance throws, remove the token from answeredTokens and surface an error (snackbar/inline), so the grade buttons don't go silently dead.
3. Audit VMs with user-initiated writes (settings, pairing, intake, search, struggle): add onFailure overrides that show an error message instead of only logcat.
## Acceptance
- [ ] Robolectric test: Review load failure shows error + retry reloads; answer failure re-enables the card.
- [ ] make check MODULE=android green.
