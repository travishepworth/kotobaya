# HANDOFF T105a

STATUS: DONE
BRANCH: t/T105a-auth-login
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- `kotobaya/auth/`: `Auth` (login/logout/getPrincipal handlers + `authenticator`), `TokenAuthenticator` (header wins over cookie, CSRF, `ScopeCheck` hook for T105b), `TokenValidator` (≤5 s cache, generation-guarded invalidation, `revoke`/`invalidateDevice`).
- `PasswordHasher` (argon2id PHC, default m=64 MiB t=3 p=4), `Totp` (RFC 6238, current+previous step, single use), `LoginRateLimiter` (5/address + 100 global per 15 min, in memory).
- Secrets: env `AUTH_PASSWORD_HASH` (required), `AUTH_TOTP_SECRET` (base32, optional). Setup: `java -cp server-all.jar kotobaya.auth.HashPasswordKt`.

## Acceptance → tests
- Login matrix → AuthApiTest.wrongPassword…, missingTotpIsTotpRequired…, wrongTotpGivesTheSame…, expiredTotpStep…, previousStepCode…, totpCodeIsAcceptedOnlyOnce
- Brute force → bruteForceLimitTriggersAndRecovers; CryptoTest.rateLimiter*
- Revocation → logoutTakesEffect…, revocationThroughTheValidator…, tokenOfARevokedDevice…, cacheLivesAtMostFiveSeconds
- Cookie/format/hash → loginSetsSessionCookie…, CryptoTest.tokenStrings…

## New dependencies
- bcprov-jdk18on 1.78.1 — already transitive via minio; declared explicitly for Argon2.

## For the reviewer (≤ 3 lines)
- NOT WIRED: Main.kt (not my path) still uses AllowAll. Needs `Auth(db, AuthConfig.fromEnv())` → `AppDeps(authenticator=, handlers=)`; Helm must pass the two AUTH_* keys.
- Rate limit keys on TCP peer (no XFF trust); TOTP replay state and limiter reset on restart.
- No scheme filter (logout must 422 bearer device tokens); T105b owns it.
