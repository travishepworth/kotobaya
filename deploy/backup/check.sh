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

for f in lib.sh backup.sh verify.sh restore.sh restore-k8s.sh restore-drill.sh; do bash -n "$here/$f"; done
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

# --- notify: failure posts, and never fails the caller --------------------------------------------
NOTIFY_URL="" notify t m 2>/dev/null
NOTIFY_URL="http://127.0.0.1:9/none" notify t m 2>/dev/null   # unreachable: warns, returns 0

# --- chart ---------------------------------------------------------------------------------------
cd "$chart/.."
helm template t kotobaya >"$tmp/off.yaml"
! grep -q 'kind: CronJob' "$tmp/off.yaml" || fail "backup.enabled=false must render no CronJob"
helm lint kotobaya --set backup.enabled=true >/dev/null
helm template t kotobaya --set backup.enabled=true --set backup.notify.url=http://ntfy.example.invalid/x >"$tmp/on.yaml"
eq cronjobs "$(grep -c '^kind: CronJob' "$tmp/on.yaml")" 3
for s in backup.sh verify.sh restore.sh; do grep -q "/opt/backup/$s" "$tmp/on.yaml" || fail "no job runs $s"; done
grep -q '/sbin/tini' "$tmp/on.yaml" || fail "jobs must run under tini"
grep -q 'name: STALE_SECONDS' "$tmp/on.yaml" || fail "staleness limit not rendered"
grep -q 'volumePreallocate=false' "$tmp/off.yaml" || fail "SeaweedFS must not preallocate volumes"
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
