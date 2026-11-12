#!/usr/bin/env bash
# Offline checks for the backup tooling (called by deploy/helm/check.sh, so `make check MODULE=deploy` runs it):
#   - script syntax (+ shellcheck when installed)
#   - lib.sh unit tests: JDBC URL conversion, count comparison and failure injection
#   - chart renders with and without backup.enabled; no secret values in the templates
# The end-to-end restore drill needs Docker + k3d: deploy/backup/restore-drill.sh.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
chart="$here/../helm/kotobaya"
fail() { echo "FAIL: $*" >&2; exit 1; }
eq() { [ "$2" = "$3" ] || fail "$1: got '$2', want '$3'"; }

for f in lib.sh backup.sh verify.sh restore.sh watchdog.sh restore-k8s.sh restore-drill.sh; do bash -n "$here/$f"; done
if command -v shellcheck >/dev/null; then shellcheck -S warning -x -e SC1091 "$here"/*.sh; fi

# shellcheck source=lib.sh
. "$here/lib.sh"

# --- JDBC URL -> PG* ---------------------------------------------------------------------------
t() { unset PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD; jdbc_to_pg "$1"; echo "$PGHOST|$PGPORT|$PGDATABASE|$PGUSER|$PGPASSWORD"; }
eq jdbc-chart "$(t 'jdbc:postgresql://kotobaya-postgres:5432/kotobaya?user=kotobaya&password=s3cr%26t')" 'kotobaya-postgres|5432|kotobaya|kotobaya|s3cr&t'
eq jdbc-noport "$(t 'jdbc:postgresql://db.example.invalid/app?user=u&password=p&sslmode=require')" 'db.example.invalid|5432|app|u|p'
eq jdbc-userinfo "$(t 'jdbc:postgresql://me:pw@h:6543/d')" 'h|6543|d|me|pw'
eq jdbc-plain "$(t 'postgresql://h/d?user=a')" 'h|5432|d|a|'

# --- compare_counts ------------------------------------------------------------------------------
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
cat >"$tmp/counts.json" <<'JSON'
{"change_max_seq":150,"review_log":10,"note":4,"card":5,"dict_entry":2,"blob":1,"log_epoch":"e1","v1_checksum":"123"}
JSON
db_counts() { cat "$tmp/counts.json"; }
compare_counts "$tmp/counts.json" ignored 2>/dev/null || fail "identical counts must pass"
BACKUP_VERIFY_INJECT_FAILURE=1 compare_counts "$tmp/counts.json" ignored 2>/dev/null && fail "injected corruption must fail"
db_counts() { jq '.card = 6' "$tmp/counts.json"; }
FAIL_DETAIL=""; compare_counts "$tmp/counts.json" ignored 2>/dev/null && fail "card count mismatch must fail"
case $FAIL_DETAIL in *card*) ;; *) fail "FAIL_DETAIL should name card: $FAIL_DETAIL";; esac
db_counts() { jq '.v1_checksum = null' "$tmp/counts.json"; }
compare_counts "$tmp/counts.json" ignored 2>/dev/null && fail "missing V1 checksum must fail"
unset -f db_counts

# --- staleness (stubbed restic) ---------------------------------------------------------------------
restic() { echo "[{\"time\":\"$STUB_TIME\"}]"; }
STUB_TIME="$(date -u -d '-1 hour' +%FT%T.123456789Z)" STALE_SECONDS=7200 check_fresh 2>/dev/null || fail "fresh snapshot flagged stale"
STUB_TIME="$(date -u -d '-3 hours' +%FT%T.123456789Z)"
if STALE_SECONDS=7200 check_fresh 2>/dev/null; then fail "stale snapshot not flagged"; fi
restic() { echo "[]"; }
check_fresh 2>/dev/null || fail "empty repository must not be stale"
unset -f restic

# --- durable_seq.sql / restore guards are exercised by restore-drill.sh; assert their shape here
grep -q 'pg_advisory_xact_lock(7033001)' "$here/durable_seq.sql" || fail "durable_seq.sql must take the sync lock"
grep -q "restore_pending" "$here/durable_seq.sql" || fail "durable_seq.sql must check restore_pending"
grep -q 'restore_pending' "$here/backup.sh" || fail "backup.sh must refuse while restore_pending"

# --- restore data-loss warnings (F-4): run restore.sh in preflight mode against fake restic/psql ------
fb="$tmp/fakebin"; mkdir -p "$fb" "$tmp/work"
cat >"$fb/restic" <<'EOS'
#!/usr/bin/env bash
# fake restic: `snapshots ... [id] --json` prints $FAKE_SNAPS, filtered by an 8-hex snapshot id argument
[ "$1" = snapshots ] || { echo "fake restic: unexpected $*" >&2; exit 9; }
id=""; for a in "$@"; do case $a in [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) id=$a;; esac; done
if [ -n "$id" ]; then jq -c --arg id "$id" '[.[] | select(.id == $id)]' "$FAKE_SNAPS"; else cat "$FAKE_SNAPS"; fi
EOS
cat >"$fb/psql" <<'EOS'
#!/usr/bin/env bash
# fake psql: the live-state query answers $FAKE_LIVE ("epoch|durable_seq|restore_pending"), or fails when unset
case "$*" in *durable_seq*) [ -n "${FAKE_LIVE:-}" ] || exit 2; echo "$FAKE_LIVE";; *) echo 1;; esac
EOS
printf '#!/bin/sh\nexit 0\n' >"$fb/pg_isready"; cp "$fb/pg_isready" "$fb/curl"; chmod +x "$fb"/*
mksnap() { # id time epoch seq L   (L may be empty = snapshot from before F-4)
  jq -nc --arg id "$1" --arg t "$2" --arg e "$3" --arg s "$4" --arg l "$5" \
    '{id:$id, time:$t, tags:(["kotobaya","verified","epoch:"+$e,"seq:"+$s] + (if $l == "" then [] else ["L:"+$l] end))}'
}
preflight() { # extra args to restore.sh; env: FAKE_LIVE, RESTORE_SNAPSHOT, ... ; prints the log
  PATH="$fb:$PATH" RESTORE_MODE=preflight WORK_DIR="$tmp/work" FAKE_SNAPS="$tmp/snaps.json" \
    DATABASE_URL='jdbc:postgresql://h/kotobaya?user=u&password=p' RESTIC_REPOSITORY=x RESTIC_PASSWORD=x \
    S3_ENDPOINT=http://x S3_BUCKET=b S3_ACCESS_KEY=a S3_SECRET_KEY=s bash "$here/restore.sh" "$@" 2>&1
}
expect() { # name want_rc want_text -- args...
  local name=$1 want=$2 text=$3 out rc; shift 4
  set +e; out=$(preflight "$@"); rc=$?; set -e
  [ "$rc" = "$want" ] || fail "$name: exit $rc, want $want: $out"
  case $out in *"$text"*) ;; *) fail "$name: output lacks '$text': $out";; esac
}
E1=0194d5e0-0000-7000-8000-000000000001; E2=0194d5e0-0000-7000-8000-000000000002
# newest verified snapshot: epoch E1, S=100, L=100 (nothing raised durable_seq)
{ mksnap aaaaaaa1 2026-01-01T00:00:00Z $E1 80 80; mksnap aaaaaaa2 2026-01-02T00:00:00Z $E1 100 100; } | jq -s . >"$tmp/snaps.json"
unset FAKE_LIVE RESTORE_SNAPSHOT RESTORE_CONFIRM_OLDER RESTORE_ACCEPT_DATA_LOSS
FAKE_LIVE="$E1|100|0" expect live-equal 0 "preflight OK" --
FAKE_LIVE="$E1|130|0" expect live-ahead-refused 2 "lose up to 30 seqs" --
FAKE_LIVE="$E1|130|0" expect live-ahead-accepted 0 "preflight OK" -- --accept-data-loss
FAKE_LIVE="$E1|130|0" RESTORE_ACCEPT_DATA_LOSS=yes expect live-ahead-accepted-env 0 "preflight OK" --
FAKE_LIVE="$E2|500|0" expect live-other-epoch 0 "preflight OK" --          # a different log: not comparable
FAKE_LIVE="" expect unreadable-no-L-above 0 "preflight OK" --              # metadata: max L == S
FAKE_LIVE="$E1|900|1" expect live-restore-pending-ignored 0 "preflight OK" --
# backups were disabled for a while: the newest backup recorded L=140 > S=100
{ mksnap aaaaaaa1 2026-01-01T00:00:00Z $E1 80 80; mksnap aaaaaaa2 2026-01-02T00:00:00Z $E1 100 140; } | jq -s . >"$tmp/snaps.json"
FAKE_LIVE="" expect meta-refused 2 "lose up to 40 seqs" --
FAKE_LIVE="" expect meta-accepted 0 "preflight OK" -- --accept-data-loss
FAKE_LIVE="$E1|900|1" expect live-restore-pending-uses-meta 2 "lose up to 40 seqs" --
FAKE_LIVE="$E1|100|0" expect live-readable-wins-over-meta 0 "preflight OK" --
# L of another epoch, a null L and a snapshot without an L tag never count
{ mksnap aaaaaaa1 2026-01-01T00:00:00Z $E2 80 999; mksnap aaaaaaa2 2026-01-02T00:00:00Z $E1 100 null; mksnap aaaaaaa3 2026-01-03T00:00:00Z $E1 100 ""; } | jq -s . >"$tmp/snaps.json"
FAKE_LIVE="" expect meta-ignored 0 "preflight OK" --
# choosing an older snapshot still needs its own confirmation, and then the data-loss warning applies too
{ mksnap aaaaaaa1 2026-01-01T00:00:00Z $E1 80 80; mksnap aaaaaaa2 2026-01-02T00:00:00Z $E1 100 100; } | jq -s . >"$tmp/snaps.json"
FAKE_LIVE="$E1|100|0" RESTORE_SNAPSHOT=aaaaaaa1 expect older-needs-confirm 2 "RESTORE_CONFIRM_OLDER" --
FAKE_LIVE="$E1|100|0" RESTORE_SNAPSHOT=aaaaaaa1 RESTORE_CONFIRM_OLDER=yes expect older-live-ahead 2 "lose up to 20 seqs" --
FAKE_LIVE="$E1|100|0" RESTORE_SNAPSHOT=aaaaaaa1 RESTORE_CONFIRM_OLDER=yes expect older-both-flags 0 "preflight OK" -- --accept-data-loss
# a snapshot without identity tags cannot be checked: refuse rather than guess
jq -nc '[{id:"aaaaaaa9",time:"2026-01-04T00:00:00Z",tags:["kotobaya","verified"]}]' >"$tmp/snaps.json"
FAKE_LIVE="" expect no-tags 1 "no epoch:/seq: tags" --
grep -q 'restic tag --add "L:' "$here/backup.sh" || fail "backup.sh must record L as a snapshot tag"
grep -q 'run_restore_job preflight' "$here/restore-k8s.sh" || fail "restore-k8s.sh must preflight before stopping anything"
grep -q '"name":"RESTORE_ACCEPT_DATA_LOSS"' "$here/restore-k8s.sh" || fail "restore-k8s.sh must pass RESTORE_ACCEPT_DATA_LOSS"

# --- notify: failure posts, and never fails the caller --------------------------------------------
NOTIFY_URL="" notify t m 2>/dev/null
NOTIFY_URL="http://127.0.0.1:9/none" notify t m 2>/dev/null   # unreachable: warns, returns 0

# --- chart ---------------------------------------------------------------------------------------
cd "$chart/.."
helm template t kotobaya >"$tmp/off.yaml"
! grep -q 'kind: CronJob' "$tmp/off.yaml" || fail "backup.enabled=false must render no CronJob"
helm lint kotobaya --set backup.enabled=true >/dev/null
helm template t kotobaya --set backup.enabled=true --set backup.notify.url=http://ntfy.example.invalid/x >"$tmp/on.yaml"
eq cronjobs "$(grep -c "^kind: CronJob" "$tmp/on.yaml")" 4
for s in backup.sh verify.sh restore.sh watchdog.sh; do grep -q "/opt/backup/$s" "$tmp/on.yaml" || fail "no job runs $s"; done
grep -q 'name: t-backup-watchdog' "$tmp/on.yaml" || fail "watchdog CronJob missing"
grep -q 'kind: Role$' "$tmp/on.yaml" && grep -q 'verbs: \[get\]' "$tmp/on.yaml" || fail "watchdog RBAC (get cronjobs) missing"
awk '/^kind: CronJob/{c=1} c&&/name: t-backup-watchdog/{w=1} w&&/suspend: true/{bad=1} /^---/{c=0;w=0} END{exit bad}' "$tmp/on.yaml" || fail "watchdog CronJob must never be suspended"
# restore-k8s.sh may suspend only the backup and backup-verify CronJobs, never the watchdog
grep -q 'CRONS="\$REL-backup \$REL-backup-verify"' "$here/restore-k8s.sh" || fail "restore-k8s.sh CRONS must be exactly backup + backup-verify"
grep -q '/sbin/tini' "$tmp/on.yaml" || fail "jobs must run under tini"
grep -q 'name: STALE_SECONDS' "$tmp/on.yaml" || fail "staleness limit not rendered"
grep -q 'volumePreallocate=false' "$tmp/off.yaml" || fail "SeaweedFS must not preallocate volumes"
grep -q 'name: BACKUPS_DISABLED, value: "true"' "$tmp/off.yaml" || fail "backup.enabled=false must set BACKUPS_DISABLED on the api"
! grep -q 'BACKUPS_DISABLED' "$tmp/on.yaml" || fail "backup.enabled=true must not set BACKUPS_DISABLED"
! grep -q 'backup-watchdog' "$tmp/off.yaml" || fail "backup.enabled=false must render no watchdog"
grep -q 'backup:\$(VERSION)' "$here/../../Makefile" || fail "Makefile images/push must cover the backup image"
# restore-k8s.sh / restore.sh safety (T111b 4-7)
grep -q 'elif .*suspended-by-restore' "$here/restore-k8s.sh" || fail "restore-k8s.sh must refresh the suspension timestamp on retry"
grep -q 'RESTORE_STAMP=' "$here/restore-k8s.sh" && grep -q '"name":"RESTORE_STAMP"' "$here/restore-k8s.sh" || fail "restore-k8s.sh must pass a per-run RESTORE_STAMP"
grep -q '"name":"DRILL"' "$here/restore-k8s.sh" || fail "restore-k8s.sh must pass DRILL"
grep -q 'Next steps' "$here/restore-k8s.sh" || fail "restore-k8s.sh must print next steps on rollout timeout"
grep -q 'DRILL:-}" != 1' "$here/restore.sh" || fail "restore.sh must gate KILL_BETWEEN_RENAMES on DRILL=1"
grep -q '_pre_restore_\${RESTORE_STAMP}' "$here/restore.sh" || fail "cleanup must drop only this run's pre-restore database"
! grep -q 'for d in \$(dbs_pre_restore); do psqlq -d postgres -c "DROP' "$here/restore.sh" || fail "cleanup must not drop every pre-restore database"
grep -q 'DRILL=1 KILL_BETWEEN_RENAMES' "$here/restore-drill.sh" || fail "drill must pass DRILL=1"
grep -q 'suspend: true' "$tmp/on.yaml" || fail "restore CronJob must be suspended"
grep -q 'secretRef' "$tmp/on.yaml" || fail "restic credentials must come from a Secret"
grep -q 'name: DATABASE_URL' "$tmp/on.yaml" || fail "backup pods need DATABASE_URL"
grep -q 'name: S3_BUCKET' "$tmp/on.yaml" || fail "backup pods need S3_*"
grep -q 'http://ntfy.example.invalid/x' "$tmp/on.yaml" || fail "backup.notify.url not rendered"
for v in RESTIC_PASSWORD RESTIC_REPOSITORY AWS_SECRET_ACCESS_KEY; do
  ! grep -E "name: $v" "$tmp/on.yaml" || fail "$v must come from the Secret via envFrom, not be set inline"
done
! grep -rEi 'RESTIC_PASSWORD[=:] *[^ ]|AWS_SECRET_ACCESS_KEY[=:] *[^ ]' "$chart/templates"/backup* "$chart/values.yaml" || fail "secret value in chart"
echo "backup check OK"
