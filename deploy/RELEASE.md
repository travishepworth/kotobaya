# Release checklist

Follow this for every deploy to the home cluster (namespace `kotobaya`) and every APK install.
Tick each step off in `plan/STATE.md` or the release LOG line. Skipping a step needs a reason written there.

## 0. Before you start
- [ ] Everything you're shipping is on `main`, and `make test` is green on that exact commit.
- [ ] Pick the pin: `PIN=$(git rev-parse main)`, then `git worktree add --detach <scratchpad>/rel<ver> $PIN`.
      Build and deploy only from this worktree. Don't rebuild because `main` moved; newer work goes in the next release.
- [ ] Decide versions. Each image has its own sequence: `helm -n kotobaya get values kotobaya | grep tag` shows what's live.
      Bump only the images that changed (`git diff --stat <live pin> $PIN -- server web worker`). Never reuse a tag.
- [ ] Migrations: `git diff --name-only <live pin> $PIN | grep -iE 'migration|\.sql$'`. If there are any, read them.
      They run forward-only in the api init container, so the backup below is the only way back.

## 1. Back up the database (always, even with no migrations)
`kubectl exec … pg_dump > file` breaks on large output: the websocket drops and you get a truncated file with exit 1. Dump inside the pod instead:
```
kubectl -n kotobaya exec kotobaya-postgres-0 -- sh -c 'pg_dump -U "${POSTGRES_USER:-kotobaya}" -d "${POSTGRES_DB:-kotobaya}" -Fc -f /tmp/predeploy.dump'
F=~/kotobaya-backups/predeploy-api-<new api ver>-$(date -u +%Y%m%dT%H%M%SZ).dump
kubectl -n kotobaya cp kotobaya-postgres-0:/tmp/predeploy.dump "$F" && chmod 600 "$F"
pg_restore -l "$F" >/dev/null && echo ok      # must print ok
kubectl -n kotobaya exec kotobaya-postgres-0 -- rm -f /tmp/predeploy.dump
```
- [ ] Size is close to the previous dump (`ls -l ~/kotobaya-backups`). A big drop means stop and check why.

## 2. Build and push images (from the pinned worktree)
```
R=core.harbor.batk.me/kotobaya
docker buildx build --platform linux/amd64 --load -f server/Dockerfile --build-arg KOTOBAYA_VERSION=<api ver> -t $R/api:<api ver> .
docker buildx build --platform linux/amd64 --load -f web/Dockerfile    --build-arg KOTOBAYA_VERSION=<web ver> -t $R/web:<web ver> .
docker buildx build --platform linux/amd64 --load -f worker/Dockerfile -t $R/worker:<worker ver> worker   # only if worker changed
docker push $R/api:<api ver>; docker push $R/web:<web ver>
```
(`make images push` gives every image the same VERSION. Use it only when all images move together.)

## 3. Values file (single source of truth)
- [ ] Edit `~/repos/kubernetes/kotobaya/values.yaml`: set the new tags **and** check every other tag matches what's live.
      Run `diff <(helm -n kotobaya get values kotobaya -o yaml) ~/repos/kubernetes/kotobaya/values.yaml`; the only differences should be the bumps you meant.
- [ ] **Never use `--set` for tags.** It leaves the file stale, and the next upgrade from the file silently rolls back. (Happened before 0.1.31: the file said api 0.1.28/web 0.1.33 while 0.1.30/0.1.35 were live.)

## 4. Deploy (chart from the pinned worktree)
```
helm upgrade --install kotobaya deploy/helm/kotobaya -n kotobaya -f ~/repos/kubernetes/kotobaya/values.yaml
kubectl -n kotobaya rollout status deploy/kotobaya-api deploy/kotobaya-web
```
- [ ] `helm -n kotobaya history kotobaya --max 1`: note the revision.
- [ ] Pods Running, api init container (migrations) completed, no restarts.
- [ ] `curl -s https://kotobaya.batk.me/api/v1/health` shows the new api `version`; `kubectl -n kotobaya get deploy kotobaya-web -o jsonpath="{..image}"` shows the new web tag.
- [ ] Smoke: log in on the web, open Home and Review, grade one card, check the api logs show no errors.
- [ ] Visual check of every UI change in this release: take a browser screenshot and look at it. No overlap, everything laid out in normal flow.

## 5. APK (if android changed)
```
ANDROID_KEYSTORE_PATH=~/.config/.android/debug.keystore ANDROID_KEYSTORE_PASSWORD=android \
ANDROID_KEY_ALIAS=androiddebugkey ANDROID_KEY_PASSWORD=android \
VERSION_NAME=<x.y.z> VERSION_CODE=<1yz> flock /tmp/kotobaya-gradle.lock ./gradlew --no-daemon -q :android:assembleAnkidroidRelease
```
- [ ] Release build, not debug. VERSION_NAME and VERSION_CODE set; without them it builds 0.0.0/code 1, a downgrade.
- [ ] `apksigner verify --print-certs` shows cert SHA-256 `ec6b7f45…dbf6cd`. On a mismatch, stop and ask: reinstalling wipes unsynced reviews.
- [ ] Don't send the APK file. Tell the user "ready". When they plug in: `adb install -r <apk>`, then check user 10 has it (`adb shell pm list packages --user 10 --show-versioncode | grep com.ichi2.anki`).
- [ ] Never clear app data.
- [ ] Perf gate: after installing, `make perf-device ARGS="--user 10"` (add a `--speed` run when a number is close). No APK release without its table (`plan/perf/device-<version>-<date>.md`) attached to the LOG line, unless the user waives it. A non-zero exit means a target was missed.
- [ ] Take an `adb exec-out screencap -p` screenshot of each changed screen in Private Space (user 10) and look at it.

## 6. Other clients
- [ ] CLI changed? `uv tool install --force --no-sources --with ./clients/python ./cli`, then `systemctl --user restart kotobaya`, and check it's active.

## 7. Record and clean up
- [ ] STATE.md "LIVE" line: helm rev, image versions, pin SHA, APK version/code, backup path.
- [ ] LOG.md RELEASE line. HUMAN_CHECKS: what the user should look at on the phone or web.
- [ ] `git worktree remove` the release worktree once the release is confirmed good.

## Rollback
- Images: set the previous tags in values.yaml and run `helm upgrade` (same command). Or `helm rollback kotobaya <rev>` if values.yaml is updated to match afterwards.
- Data (only if a migration or bug damaged it): scale api/worker to 0, then `pg_restore --clean -d kotobaya` from the predeploy dump inside the postgres pod, then scale back up.
- APK: `adb install -r -d <previous apk>` from `~/kotobaya-apk/` (`-d` allows a downgrade and keeps data).
