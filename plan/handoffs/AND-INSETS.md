# HANDOFF AND-INSETS

STATUS: DONE
BRANCH: t/AND-INSETS-window-insets
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- `nav/AppShell.kt`: the shell is now the only place that applies insets. On phones the content area pads for safeDrawing top and sides, and consumes the nav-bar inset when the bottom bar is shown. The whole column uses `imePadding`. On the rail layout the Row pads for safeDrawing.
- `MainActivity.kt`: `setDecorFitsSystemWindows(false)` makes the app edge-to-edge on every API level, and `SOFT_INPUT_ADJUST_RESIZE` lets IME insets reach Compose below API 30.
- New `test/.../ui/AppShellInsetsTest.kt`.

## Acceptance → tests
- Tabs and pushed screens start below the status bar → `AppShellInsetsTest` (phone tab, immersive Review, rail). Every route renders inside AppShell, so one shell test covers them all.
- make check green → yes.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `AppShell.kt` is in `nav/`, not `ui/`. I treated it as owned because the ticket names the "nav shell". The app has no Scaffold or TopAppBar, so nothing else could double-pad.
- No Roborazzi shots changed: the matrix renders screens without the shell. Not tested: KbSheet's own `navigationBarsPadding` inside M3 ModalBottomSheet may double-pad. A real device has not been checked.
- AppShellInsetsTest must use the default-SDK sandbox: under sdk 34 it hung (Compose never went idle) after the app-start tests.
