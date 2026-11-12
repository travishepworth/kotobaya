# HANDOFF T105a (attempt 2)

STATUS: DONE
BRANCH: t/T105a-auth-login
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- Attempt 1: `kotobaya/auth/` login/logout/getPrincipal, `TokenAuthenticator`, `TokenValidator` (≤5 s cache), argon2id, TOTP, rate limiter.
- [major] `ClientAddressResolver` + env `AUTH_TRUSTED_PROXIES` (CIDRs, default empty): XFF believed only from a trusted peer; client = rightmost untrusted hop; malformed hop falls back to the peer. Global cap stays 100/15 min; a success clears only that client's failures.
- [minor] TOTP: the step current at startup counts as used, so no replay after a restart.
- [minor] Argon2 m capped at 256 MiB; the semaphore keeps concurrent hashing at 256 MiB or less.

## Acceptance → tests
- Attempt-1 matrix unchanged → AuthApiTest, CryptoTest
- Proxy fixes → ClientAddressTest (spoofed…, trustedProxyChains…, malformed…, twoClientsBehindTheProxy…, defaultResolverIgnores…)
- Restart replay and memory cap → ClientAddressTest.aCodeUsedJustBefore…, argon2MemoryIsCapped…

## New dependencies
- bcprov-jdk18on 1.78.1 (approved)

## For the reviewer (≤ 3 lines)
- Wiring ticket: Main.kt still uses AllowAll; pass AUTH_PASSWORD_HASH, AUTH_TOTP_SECRET, AUTH_TRUSTED_PROXIES via Helm.
- Set Traefik `externalTrafficPolicy: Local`, and set AUTH_TRUSTED_PROXIES to the ingress/pod CIDR.
- First login after a restart may wait ≤30 s for the next TOTP code.
