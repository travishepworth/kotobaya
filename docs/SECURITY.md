# Security review (T609)

Reviewed 2026-11-13 against SPEC "Infrastructure › Security / Networking and TLS / Jobs", "Public API › Auth",
Overview principles 3-4, `contracts/api/openapi.yaml` §2 and the P6 surfaces, `contracts/sync/SYNC.md` 6.2-6.3.
Fixes are only in `deploy/`; every other finding is a proposed ticket below.

## 1. Threat model

One user, one namespace, reached only over the VPN. Kotobaya holds the review log, notes, media, tokens and backups.
These are the threats that matter:

| # | Adversary / event | What they hold | Main controls |
|---|---|---|---|
| A1 | Another host or person on the VPN | network reach to the ingress | auth on everything, login rate limit, TOTP, no public ingress |
| A2 | Leaked or abused scoped token (Yomitan key in a browser extension, script token) | one `kbp_` token | scopes, revocation, hashed storage; **S-01** turns a `write` key into full control |
| A3 | Lost phone | device token (`read write review`) | revoke the device (takes effect at once, `/events` streams too) |
| A4 | Hostile content (imported pages/URLs, uploaded media, AnkiConnect media) | input to the worker and blob store | URL scheme checks, size caps, worker egress policy (**S-02**), blob serving (**S-01**) |
| A5 | Compromised neighbour workload in the cluster | pod network | NetworkPolicies, default deny both ways, no ServiceAccount tokens |
| A6 | Compromised backup target | restic repository files | restic encryption, password only in a Secret |

Out of scope: cluster-admin or node compromise, the ingress controller/Gateway itself, image supply chain beyond tag
pinning, and denial of service by the owner's own devices.

## 2. Checklist

PASS = holds now (after the fixes in §3). FAIL = open finding (§4). HUMAN = needs the owner (cluster or secret
settings outside this repo).

### Chart (`deploy/helm/kotobaya`)
| Item | Result | Evidence |
|---|---|---|
| Every workload non-root (pod `runAsNonRoot` + explicit `runAsUser`) | PASS | `check_hardening.py` on every toggle combination |
| Read-only root filesystem | PASS (fixed) | all containers. VOICEVOX is a documented exception (S-10) |
| `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`, seccomp `RuntimeDefault` | PASS | `check_hardening.py` |
| No ServiceAccount token unless needed | PASS (fixed) | only the backup watchdog (get on 2 CronJobs) |
| Resource limits | PASS | memory limit on every container, including the migrate container (fixed). CPU limits are left out on purpose: single node, requests set, and CFS throttling hurts the JVM and Sudachi |
| NetworkPolicy default deny, ingress | PASS | namespace-wide (unchanged) |
| NetworkPolicy default deny, egress | PASS (fixed) | release pods: DNS only, plus the paths in §5 |
| Only the needed paths | PASS (fixed) | §5; probed on k3d (§6) |
| No Secret values in `values.yaml` | PASS | asserted by `check.sh` |
| Ingress/HTTPRoute VPN-only | HUMAN | documented in `values.yaml`. Enforced by the controller's Service/Gateway, not by the chart |
| Response security headers | PASS (HTTPRoute) / FAIL (Ingress) | chart HTTPRoute sets them (fixed). Ingress path: S-07. The live deploy's own `gateway.yaml` lives outside this repo: HUMAN, add the same filters |

### Auth (`server/src/main/kotlin/kotobaya/auth/`)
| Item | Result | Evidence |
|---|---|---|
| Scope enforcement per operation | PASS | `ScopePolicy` reads every operation's `security` from openapi at runtime. Test: `ScopeEnforcementTest.everyScopedOperationRefusesACredentialLackingItsScopeAndAdmitsOneHoldingIt` |
| Token revocation immediate (≤5 s cache, generation bump) | PASS | `TokenValidator`. `DevicesApiTest.revokingADeviceKillsAllItsTokensAtOnce…` |
| Revocation ends `/events` streams | PASS | `TokenValidator.isLive` guard. `RevocationWiringTest`, `DevicesApiTest.revocationEndsOpenEventStreamsOfTheRevokedTokens` |
| AnkiConnect body `key`: personal tokens only, `write`, revocable | PASS | `AnkiConnectGate`. `aRevokedKeyIsRefusedAndAReadOnlyKeyLacksTheScope`, `aBodyKeyIsAcceptedNowhereButAnkiConnect` |
| Tokens stored hashed (SHA-256), CSPRNG | PASS | `Tokens.kt` |
| Session cookie `HttpOnly; Secure; SameSite=Strict; Path=/api` | PASS | `loginSetsSessionCookieWithContractAttributesAndStoresOnlyTheHash` |
| CSRF header on cookie writes | PASS | `cookieWritesNeedTheCsrfHeaderButBearerWritesDoNot`. Preflights never allow `X-Kotobaya-Csrf` |
| Login rate limit (5/15 min per address, 100 global) | PASS, see S-06 | `rateLimiter*` tests. The live deploy sets `api.trustedProxies` |
| TOTP (RFC 6238, ±1 step, single use, no password oracle) | PASS / HUMAN | `totp*`, `missingTotpIsTotpRequiredWithoutCheckingThePassword`. TOTP is optional: turn it on |
| Argon2 verification bounded (heap) | PASS | `Auth.hashing` semaphore |

### Surfaces added in P6
| Item | Result | Evidence |
|---|---|---|
| AnkiConnect CORS `*` | PASS | the server sends no CORS headers at all yet (S-05). Cookies never work cross-origin |
| AnkiConnect `storeMediaFile` / `addNote` `url` (SSRF) | PASS | refused with "url is not supported" (`AnkiConnectModule.kt`). Contract drift: S-05 |
| Stored media served from the app origin | **FAIL** | S-01 |
| Export with `read` dumps all learning data | FAIL (low) | S-04 |
| TTS input limits | PASS | text 1-500, voice ≤100 (`TtsModule`). Worker accepts numeric speakers only, URL-encoded, 120 s timeout. VOICEVOX reachable from the worker only |
| Optimizer job inputs | PASS | the server re-validates every worker result (finite, op-schema bounds, strict log-loss gate). The CronJob only enqueues with `DATABASE_URL` |
| Worker fetch of user URLs (media import) | **FAIL**, contained | S-02. The new egress policy blocks private, link-local and cluster ranges |

### Backups (`deploy/backup`)
| Item | Result | Evidence |
|---|---|---|
| restic repository encrypted, password from a Secret | PASS | restic always encrypts. `envFrom` Secret, asserted not inline (`backup/check.sh`) |
| Verify job restores into a scratch DB and compares counts | PASS | `verify.sh`. Weekly |
| Restore leaves CronJobs suspended until the server finalizes | PASS | `restore-k8s.sh`. The optimizer CronJob is now suspended too (fixed, S-12). Drill run in §6 |
| Watchdog alerts on an unreadable repository | PASS (fixed) | was a silent "OK" (S-11) |
| durable_seq / epoch handshake (SYNC 6.3) | PASS | `durable_seq.sql`, `restore_pending` guards (T112 checks) |
| Off-cluster backups actually running | **HUMAN** | the live deploy has `backup.enabled=false` and DECISIONS has no restic target. Principle 4 ("nightly off-cluster backups") is not met until one is configured |

## 3. Fixes made in `deploy/` (one line each)

- `readOnlyRootFilesystem: true` in `containerSecurityContext`, plus `/tmp` emptyDirs for postgres, migrate, optimize, ntfy and voicevox. Rationale: the SPEC requires read-only root filesystems, and no container had one.
- `automountServiceAccountToken: false` on every pod except the watchdog. Rationale: no other pod calls the Kubernetes API, so a stolen token is useless.
- Postgres admits only DATABASE_URL holders, and the object store admits only S3-credential holders on 8333 (plus itself). Rationale: web, ntfy and voicevox could reach the database before.
- Egress default deny with explicit paths (§5); the worker's internet access excludes private, CGNAT, link-local and loopback ranges. Rationale: this contains the worker SSRF (S-02) and any compromised pod.
- Edge ingress is limited to the app ports from `networkPolicy.ingressFrom`, default any namespace, so you can narrow it to the Gateway's namespace. Rationale: before, any pod could reach any port of the api and web.
- ntfy ingress comes from the edge and the api only. Rationale: before, it was open from every namespace.
- `networkPolicy.egress.externalServices` is required when Postgres or S3 is external, and rendering fails without it. Rationale: a silent egress block would take the app down.
- HTTPRoute `ResponseHeaderModifier` sets nosniff, `X-Frame-Options: SAMEORIGIN` and `Referrer-Policy: no-referrer` everywhere, and a `CSP: sandbox` on `/api/v1/blobs`. Rationale: a deploy-side guard for S-01.
- The migrate container gets resource limits, and the migrate hook pod gets labels. Rationale: every container needs a limit, and NetworkPolicies need labels to match.
- The ntfy image is now `binwiederhier/ntfy`; it was `binwiederhall`, which does not exist. ntfy gets `NTFY_BASE_URL` only when the URL has no path. Rationale: with `ntfy.enabled=true` the pod could not start (S-09).
- `check_fresh` and `watchdog.sh` now fail when `restic snapshots` fails. Rationale: an unreachable or deleted repository reported "watchdog OK" (S-11).
- `restore-k8s.sh` suspends and resumes `<rel>-optimize` and waits for its pods. Rationale: no job may write to the database while it is being swapped (S-12).
- `check.sh` and the new `check_hardening.py` assert all of the above on every toggle combination.

## 4. Findings and proposed tickets

| ID | Sev | Finding and failure scenario | Where | Proposed ticket |
|---|---|---|---|---|
| S-01 | HIGH | **Stored XSS through blobs.** `getBlob` serves bytes with the uploader's `Content-Type` from the app origin, with no `nosniff`, CSP or `Content-Disposition`. `putBlob` (`write`/`import`) stores any type, and AnkiConnect stores `.svg` as `image/svg+xml`. Scenario: a leaked Yomitan key uploads an SVG or HTML file with script; the owner opens `/api/v1/blobs/<hash>`; the script runs same-origin with the session cookie (`Path=/api`), sets `X-Kotobaya-Csrf`, mints an `admin` token and exports everything. The chart HTTPRoute now sandboxes blobs; the Ingress path and the live `gateway.yaml` do not. | `server/…/blobs/BlobsModule.kt` (`get`), `ankiconnect/AnkiMedia.kt`, contract `putBlob` | **SEC-1 server:** send `X-Content-Type-Options: nosniff` and `Content-Security-Policy: sandbox; default-src 'none'` on every blob response; use `Content-Disposition: attachment` unless the type is `audio/*` or a raster `image/*`; never serve `image/svg+xml`, `text/*` or `*/html` inline. Add a contract note on `getBlob`. |
| S-02 | MED | **Worker SSRF on media import by URL.** `extract/html.check_url` checks only the scheme and follows redirects. Scenario: an `import` token imports `http://169.254.169.254/…`, a LAN admin page or an in-cluster service and reads the response back as sentences. It is contained by the new worker egress policy, but only where NetworkPolicy is enforced. | `worker/src/kotobaya_worker/extract/html/__init__.py` | **SEC-2 worker:** resolve the host and refuse loopback, private, link-local, CGNAT and multicast addresses on every hop, connecting to the checked IP (no DNS-rebinding window). Add tests. |
| S-03 | MED | **App uses the Postgres superuser.** With the bundled Postgres, `POSTGRES_USER` is a superuser, and `DATABASE_URL` (api, worker, backup, optimize) uses it. Scenario: any SQL injection becomes command execution on the database pod (`COPY … PROGRAM`), and a leaked worker env leaks full DB control. | `deploy/helm` (postgres + secrets), `deploy/backup` | **SEC-3 deploy:** separate roles. The app owns the schemas and is not a superuser; backup gets `CREATEDB` and read access; the superuser is used only for init. Needs a one-off migration of the live DB, so it is a HUMAN step. |
| S-04 | LOW | **`read` exports everything.** `GET /export` and `/export/anki` need only `read`, which every device token and any dashboard token holds. Scenario: a read-only stats token leaks and the whole collection is exfiltrated. Single user, so the impact is limited. | contract `exportAll`/`exportAnki` | **SEC-4 contract (C-ticket):** a dedicated `export` scope (sessions hold it; devices do not), or require `admin`. |
| S-05 | LOW | **AnkiConnect contract drift.** The contract says `url` media is supported and that `OPTIONS` is answered with `Access-Control-Allow-Origin: *`. The server refuses `url`, which is the safer choice, and sends no CORS headers. Scenario: a page-based client that needs CORS fails; someone "fixes" it by adding a URL fetch in the api, which has no egress guard. | contract `ankiConnect`, `AnkiConnectModule.kt` | **SEC-5 contract (C-ticket):** document `url` as unsupported, and either implement the preflight exactly as specified or drop it from the contract. |
| S-06 | LOW | **Login limiter and TOTP replay state are in memory.** Each api restart resets the failure counts. The global cap (100 per 15 min) lets anyone on the VPN block password logins for 15 min; existing sessions keep working. Trusting `X-Forwarded-For` from the whole pod CIDR lets any pod spoof its address (only the global cap then applies). | `auth/LoginRateLimiter.kt`, `Totp.kt` | Accepted for one user. **SEC-6 (optional):** persist the limiter and last TOTP step in `server_state`, and narrow `api.trustedProxies` to the Gateway pods' CIDR. |
| S-07 | LOW | **Web nginx sends no security headers** (no CSP or `frame-ancestors`, no `nosniff`). Ingress deployments get none; HTTPRoute deployments get them from the chart. | `web/Dockerfile` (inline nginx conf) | **SEC-7 web:** a proper `nginx.conf` with CSP (`default-src 'self'`, `frame-ancestors 'self'`, hashes for SvelteKit's inline bootstrap), `nosniff` and `Referrer-Policy`. |
| S-08 | INFO | Sessions last 30 days with no idle timeout. Logout and revocation work. | `Auth.kt` | none (contract choice) |
| S-09 | MED (functional) | **ntfy could not run from the chart:** the image name was wrong, and ntfy refuses a base-url with a path (`/ntfy`). Both are fixed so the pod starts, but ntfy does not support sub-path hosting (web UI, attachments), and UnifiedPush through `/ntfy` is untested end to end. | `deploy/helm/kotobaya/templates/ntfy.yaml` | **SEC-9 deploy:** host ntfy on its own name (`ntfy.<domain>`, `ntfy.baseUrl`), then test a push to a phone. |
| S-10 | INFO | VOICEVOX keeps a writable root filesystem (no documented read-only mode). It has no egress and only the worker can reach it. | `voicevox.yaml` | **SEC-10 deploy:** try `voicevox.readOnlyRootFilesystem=true` with emptyDirs, and keep it if synthesis works. |
| S-11 | MED | Watchdog reported OK on an unreadable restic repository. **Fixed** (`lib.sh`, `watchdog.sh`, tested in `backup/check.sh`). | `deploy/backup` | done |
| S-12 | LOW | The optimizer CronJob kept running during a restore. **Fixed** (`restore-k8s.sh`). | `deploy/backup` | done |
| S-13 | INFO | No emptyDir has a `sizeLimit` except the backup ones. A 2 GiB upload or a dictionary build can fill node disk, and then the pod is evicted. | chart | **SEC-13 deploy:** set limits once real usage is known. |
| S-14 | INFO | `postgres:17` follows the major tag (security patches; pin a digest if you want reproducible deploys). The other images pin tags, not digests. | `values.yaml` | none |

## 5. Network paths (rendered NetworkPolicies)

| From | To | Port | Policy |
|---|---|---|---|
| `networkPolicy.ingressFrom` (Gateway/ingress, Prometheus) | api, web | 8080, 3000 | `allow-edge` |
| `networkPolicy.ingressFrom`, api | ntfy | 8080 | `allow-ntfy` |
| api | worker (parse service) | 8082 | `allow-parser` / `egress-api` |
| api, worker, backup×3, optimize, migrate | postgres | 5432 | `allow-postgres` / `egress-data` |
| api, worker, backup×3 | objectstore | 8333 | `allow-objectstore` / `egress-data` |
| objectstore | itself | any | master/volume/filer in one pod |
| worker | voicevox | 50021 | `allow-voicevox` / `egress-worker` |
| worker | public internet (no RFC 1918, CGNAT, 169.254/16, 127/8) | 80, 443 | `egress-worker` |
| api | external ntfy (only with `ntfy.externalBaseUrl`) | 80, 443 | `egress-api` |
| backup, verify, restore, watchdog | `networkPolicy.egress.backupTo` (default anywhere: restic repo, NOTIFY_URL, kube API) | any | `egress-backup` |
| every release pod | DNS | 53 | `egress-dns` |

Kubelet probes are node traffic and are always allowed. On CNIs that treat the API server as a special entity
(Cilium), narrow `backupTo` with care: the watchdog needs the API server.

## 6. How this was verified (2026-11-13)

- `docker run --read-only --cap-drop ALL` as the chart's UIDs worked for postgres:17 (init and ready), SeaweedFS 3.80 (S3 put/get round trip), web 0.1.2, worker 0.1.2, api `migrate`, and ntfy 2.14.0 (fails with a path in base-url, works without one).
- A throwaway k3d cluster (k3s, kube-router NetworkPolicy) ran the hardened chart with backup, ntfy and the optimizer on, using images 0.1.2. All pods became Ready and `/readyz` returned 200. Probe matrix: worker to postgres, s3, 1.1.1.1:443 and example.com:443 OPEN; worker to filer 8888, master 9333, ntfy, api, kube API, 169.254.169.254, 192.168.1.1 BLOCKED; web to postgres, s3, internet BLOCKED; api to parser, ntfy, postgres OPEN and to the internet BLOCKED; another namespace to api, web, ntfy OPEN and to postgres and parser BLOCKED.
- On the same cluster, backup, verify and watchdog Jobs succeeded, and the full `restore-k8s.sh` drill succeeded (preflight, suspend, restore, finalize, cleanup, resume); the optimizer CronJob was resumed afterwards.
- `make check MODULE=deploy`.
