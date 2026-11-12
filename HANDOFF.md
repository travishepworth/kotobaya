# HANDOFF T105b

STATUS: DONE
BRANCH: t/T105b-auth-scopes
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- auth/Scopes.kt: `ScopePolicy` (Auth's default ScopeCheck, driven by spec `security`), `PushOpAuth.check` and `requireDevice()` for T202
- auth/Devices.kt, PairingCodes.kt: pairings (in memory; a restart invalidates codes), pair, list/get/patch/revoke, /devices/self
- auth/ApiTokens.kt: /tokens create/list/get/revoke. AnkiConnectGate.kt: body-key auth; actions plug in via `Auth(ankiConnect=)`
- auth/AuthEvents.kt: `AuthEvents.of(bus, hub)` sends device.* events and calls `closeToken` on revocation. W-1 passes `Auth(events=...)`
- ClientAddress.kt: XFF walked right-to-left, IPv6 keyed on /64, IP parsing without DNS. UsageRecorder sets last_used_at/last_seen_at

## Acceptance → tests
- Scope table → ScopeEnforcementTest.everyScopedOperationRefuses…, publicOperationsNeedNoCredential…, scriptTokensWithoutAdmin…
- Revocation → TokensApiTest.tokenRevocationIsEffective…, DevicesApiTest.revokingADeviceKillsAllItsTokens…, revocationEndsOpenEventStreams…
- Pairing → DevicesApiTest.pairingCodeIsSingleUse, …Expires…, rePairOverwrites…, wrongCodesAreRateLimited…, hlcRegressed…, twoRequestsRacing…
- Body key → TokensApiTest.ankiConnect*, aBodyKeyIsAcceptedNowhereButAnkiConnect

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- New optional env `PUBLIC_URL` (pairing_uri). Without it the URL comes from the Host header. Deploy should set it.
- Ankiconnect: every auth failure, CSRF included, goes in the 200 envelope. `key` accepts only personal tokens. CCR candidate: bulkScheduleCards should need review+write (FOLLOWUPS); implemented as review only.
