#!/usr/bin/env bash
# Daily watchdog. NEVER suspended by restore-k8s.sh. It alerts (and fails the Job) when:
#   - the newest verified snapshot is older than STALE_SECONDS (check_fresh), or
#   - the backup or backup-verify CronJob is suspended with no `kotobaya/suspended-by-restore`
#     annotation (someone turned backups off), or
#   - that annotation (value = epoch seconds when restore-k8s.sh suspended it) is older than
#     STUCK_SECONDS (a failed or dead restore left the backups suspended).
# Needs: RESTIC_*, NOTIFY_URL, BACKUP_CRONJOBS (space separated names), and the get-cronjobs RBAC.
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"
trap 'finish backup-watchdog' EXIT

: "${STUCK_SECONDS:=10800}"
: "${BACKUP_CRONJOBS:?}"
restic_need
problems=""

# A failing `restic snapshots` must alert, not read as "0 snapshots" or "fresh" (T609).
if ! snaps=$(restic snapshots --tag verified --json); then
  problems="${problems}cannot read the restic repository (unreachable, wrong password or missing). "
elif [ "$(jq length <<<"$snaps")" = 0 ]; then
  problems="${problems}no verified backup exists in the repository (expected after the first nightly run). "
else
  check_fresh || problems="$problems$FAIL_DETAIL"
fi
FAIL_DETAIL=""

for c in $BACKUP_CRONJOBS; do
  json=$(kubectl get cronjob "$c" -o json)
  if [ "$(jq -r '.spec.suspend // false' <<<"$json")" = true ]; then
    since=$(jq -r '.metadata.annotations["kotobaya/suspended-by-restore"] // empty' <<<"$json")
    if [ -z "$since" ]; then
      problems="${problems}CronJob $c is suspended (not by a restore): backups are off. "
    elif [ $(( $(date -u +%s) - since )) -gt "$STUCK_SECONDS" ]; then
      problems="${problems}CronJob $c has been suspended by a restore for over $((STUCK_SECONDS / 3600)) h: the restore did not finish; re-run restore-k8s.sh. "
    fi
  fi
done

if [ -n "$problems" ]; then FAIL_DETAIL=$problems; log "$problems"; exit 1; fi
log "watchdog OK: backups fresh and CronJobs active"
