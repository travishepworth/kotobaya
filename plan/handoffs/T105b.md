# HANDOFF T105b (attempt 2)

STATUS: DONE
BRANCH: t/T105b-auth-scopes (rebased on main)
CHECK: `make check MODULE=server` → green; `make check MODULE=deploy` → green

## Changed (≤ 6 lines)
- Main.kt composeApp builds jobs first and passes `Auth(events = AuthEvents.of(jobs.bus, jobs.hub))`. `credentialValid` now re-checks `TokenValidator.isLive` (uncached) every `eventsGuardMs`
- pairing_uri: PUBLIC_URL first, then X-Forwarded-Proto/Host from trusted peers (`ClientAddressResolver.isTrusted`), then a direct https request. Otherwise `500 internal` saying to set PUBLIC_URL
- Chart: `api.publicUrl` sets env PUBLIC_URL; it is empty by default and up.sh is unchanged
- Body-key use updates last_used_at. Re-pairing a `script` device id with another kind is `422 /kind`
- Pairing limiter global cap is now 1000 per 15 min, a backstop only. The 40-bit, 10-minute code and the per-address limit are the real protection

## Acceptance → tests
- Scope table, revocation, pairing and body key: unchanged from attempt 1 (ScopeEnforcementTest, DevicesApiTest, TokensApiTest)
- Stream wiring → wiring/RevocationWiringTest uses the real composeApp: a device revoke ends the stream, device.revoked is published, and an expired token's stream ends
- Origin branches → DevicesApiTest.publicUrlWins…, withoutPublicUrlForwarded…, …PlainHttp…, publicOriginBranches

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The direct-https branch is unit-tested only, because Ktor's test engine always reports scheme http.
- Ankiconnect auth failures, CSRF included, go in the 200 envelope. CCR candidate: bulkScheduleCards should need review+write; it is implemented as review only.
