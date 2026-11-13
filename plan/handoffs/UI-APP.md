# HANDOFF UI-APP

STATUS: DONE (state-layer requests below)
BRANCH: t/UI-APP-rebuild (rebased on origin/main)
CHECK: `make check MODULE=android` → green; `make screenshots-app` → 660 PNGs, clean

## Changed (≤ 6 lines)
- Round 1: every screen rebuilt on DESIGN.md (`ui/components/`, `nav/` shell, Review first). Media tab removed.
- Round 2 #2: `ui/theme/SystemBars.kt` `KbSystemBars()` sets bar colours and icon contrast from the theme in use. Called in AppRoot and IntakeActivity (nav bar only there).
- #6: the Hero counts only `type == "user"` decks. #8: the Today overline uses the study date (`scheduler.timezone` + rollover, read off main in the nav glue).
- #3: the finished page has a quiet "Back to Today" link. Undo is hidden there when there is nothing to undo.

## Acceptance → tests
- Bars follow the Dark/Light override on a light system → SystemBarsTest (2)
- Nav → AppStartsTest, NoDbOnMainThreadTest, SettingsTabTest

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- #1 is unchanged. Capture is still reachable: Struggle › word › sentences calls `captureEntry`. Search and intake have no server sentence id (`putSpan` needs one). Request: sentence hits with id/mediaId in Search, or a create-sentence call.
- 17 curated PNGs refreshed (Today, review finished). System bars aren't in the Robolectric shots, so the unit test covers them.
- Earlier requests: ReviewMeta remaining/deck, HomeUiState totals, Stats VM, pitch, deck colour.
