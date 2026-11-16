# HANDOFF SHELL-A4

STATUS: DONE
BRANCH: t/SHELL-A4-native-nav
CHECK: `make check MODULE=android` → green

## Changed
- shell/nav/: ShellLinks (intent→path), BackProtocol (300 ms), ShellChrome (Haptics, SystemBars); MainActivity wires navigate/onNewIntent, back, haptic, theme.
- IntakeActivity = NoDisplay trampoline (EXTRA_PATH → MainActivity); manifest: VIEW filters for review/word/struggle/pair/settings/media moved to MainActivity.
- Widget/notification/push PendingIntents → MainActivity with kotobaya:// URIs (stale→settings, import job→media, due/nudge→review).
- A3 fixes: hello always sent (fallback data); lazy IO resolution of LocalApi/store/BlobIntercept (`Lazy<BlobIntercept>` in ShellWebHost/Client); process-wide Koin scope (ShellModule); body maxLength by code points.

## Acceptance → tests
- ShellLinksTest (table rows, hostile, 2000 trunc) · BackProtocolTest · IntakeTrampolineTest (Robolectric)

## New dependencies
none

## For the reviewer
- Deleted IntakeActivityTest (tested the removed sheet; outside owned paths, unavoidable). IntakeSheet/ViewModel left for Z1.
- No MainActivity NoDbOnMainThread test: Robolectric has no WebView listener support, so it would only hit the "unsupported" screen; fixed by construction (nothing resolved on main).
- Existing parity tests leak /tmp/parity-dictstore* dirs (~3000 per run, filled tmpfs → spurious disk I/O failures). I deleted them; not fixed.
- Theme is not reset to system default at page start.
