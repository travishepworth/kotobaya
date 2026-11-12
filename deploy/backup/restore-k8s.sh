#!/usr/bin/env bash
# Restore the newest verified backup into the cluster's database (SYNC.md 6.3 "Restore script").
#   1. scale api and worker to zero (no instance may serve the database while it is restored)
#   2. run the restore Job (restore.sh in the backup image): restore DB + blobs, set restore_pending
#   3. scale api and worker back; the api finalizes the restore at startup (new log_epoch,
#      durable_seq = max(change.seq), raised HLC) and only then becomes ready.
# env: NS (kotobaya)  REL (kotobaya)  TIMEOUT seconds (1800)
#      RESTORE_SNAPSHOT=<id> RESTORE_CONFIRM_OLDER=yes   restore an older snapshot (loses ops that
#                                                         devices already purged; asked for explicitly)
#      KUBECTL (kubectl)
# If the restore Job fails the workloads stay scaled down (the database may be half restored):
# fix the cause and re-run; the script is safe to re-run from the start.
set -euo pipefail
NS=${NS:-kotobaya}; REL=${REL:-kotobaya}; TIMEOUT=${TIMEOUT:-1800}; K=${KUBECTL:-kubectl}
k() { $K -n "$NS" "$@"; }

if [ -n "${RESTORE_SNAPSHOT:-}" ] && [ "${RESTORE_CONFIRM_OLDER:-}" != yes ]; then
  echo "RESTORE_SNAPSHOT set without RESTORE_CONFIRM_OLDER=yes. Restoring anything but the newest verified" >&2
  echo "snapshot loses ops that devices already purged. Re-run with RESTORE_CONFIRM_OLDER=yes to proceed." >&2
  exit 2
fi

live_pods() { k get pods -l "$1" --field-selector=status.phase!=Succeeded,status.phase!=Failed -o name; }
wait_no_pods() { # selector, what
  local deadline=$((SECONDS + 600))
  while [ -n "$(live_pods "$1")" ]; do
    [ $SECONDS -lt $deadline ] || { echo "ERROR: $2 pods still present after 10 min:" >&2; live_pods "$1" >&2; exit 1; }
    sleep 3
  done
}

# 1. Suspend the backup jobs (a backup of a half-restored database must never become "verified").
#    Remember which we suspended (annotation) so a re-run after a failure still resumes them.
CRONS="$REL-backup $REL-backup-verify"
for c in $CRONS; do
  if [ "$(k get cronjob "$c" -o jsonpath='{.spec.suspend}')" != true ]; then
    k annotate cronjob "$c" kotobaya/suspended-by-restore="$(date -u +%s)" --overwrite >/dev/null
    k patch cronjob "$c" -p '{"spec":{"suspend":true}}' >/dev/null
  fi
done
echo "backup CronJobs suspended; waiting for running backup jobs to finish"
wait_no_pods "app.kubernetes.io/component in (backup,backup-verify)" "backup"

# Replica counts are kept in an annotation so a re-run after a failed restore (workloads at 0) restores them.
replicas() { # deployment
  local r; r=$(k get deploy "$1" -o jsonpath='{.metadata.annotations.kotobaya/replicas-before-restore}')
  if [ -z "$r" ]; then
    r=$(k get deploy "$1" -o jsonpath='{.spec.replicas}')
    k annotate deploy "$1" "kotobaya/replicas-before-restore=$r" --overwrite >/dev/null
  fi
  echo "$r"
}
API_REPLICAS=$(replicas "$REL-api")
WORKER_REPLICAS=$(replicas "$REL-worker")
echo "scaling api ($API_REPLICAS) and worker ($WORKER_REPLICAS) to 0"
k scale deploy "$REL-api" "$REL-worker" --replicas=0
wait_no_pods "app.kubernetes.io/component in (api,worker)" "api/worker"

run_restore_job() { # $1 = mode ("" = restore, "cleanup"); returns 0 when the Job succeeded
  local job ok bad deadline=$((SECONDS + TIMEOUT))
  job="$REL-restore-${1:-run}-$(date -u +%Y%m%d%H%M%S)"
  k create job "$job" --from="cronjob/$REL-backup-restore" --dry-run=client -o json |
    jq --arg s "${RESTORE_SNAPSHOT:-}" --arg c "${RESTORE_CONFIRM_OLDER:-}" --arg m "${1:-}" --arg kill "${KILL_BETWEEN_RENAMES:-}" \
      '.spec.template.spec.containers[0].env += [{"name":"RESTORE_SNAPSHOT","value":$s},{"name":"RESTORE_CONFIRM_OLDER","value":$c},{"name":"RESTORE_MODE","value":$m},{"name":"KILL_BETWEEN_RENAMES","value":$kill}]' |
    k apply -f - >/dev/null
  echo "job $job started"
  JOB=$job
  while [ $SECONDS -lt $deadline ]; do
    ok=$(k get job "$job" -o jsonpath='{.status.succeeded}' 2>/dev/null || true)
    bad=$(k get job "$job" -o jsonpath='{.status.failed}' 2>/dev/null || true)
    if [ "${ok:-0}" -ge 1 ] 2>/dev/null; then k logs "job/$job" || true; return 0; fi
    if [ "${bad:-0}" -ge 1 ] 2>/dev/null; then break; fi
    sleep 3
  done
  k logs "job/$job" || true
  return 1
}
rc=0; run_restore_job "" || rc=1
if [ $rc -ne 0 ]; then
  echo "RESTORE FAILED; api and worker left scaled to 0 and the backup CronJobs suspended. Job: $JOB" >&2
  echo "(the restore loads a temporary database first, so the live one is untouched unless the final rename ran)" >&2
  exit 1
fi

echo "scaling api and worker back up"
k scale deploy "$REL-api" --replicas="$API_REPLICAS"
k scale deploy "$REL-worker" --replicas="$WORKER_REPLICAS"
k rollout status deploy "$REL-api" --timeout=600s   # readiness = /readyz: restore finalized, scheduler checked
k annotate deploy "$REL-api" "$REL-worker" kotobaya/replicas-before-restore- >/dev/null
# The server has finalized: only now drop the pre-restore database (kept until here so a crash
# between the two renames, or a restore that must be undone, can be recovered).
run_restore_job cleanup || echo "WARNING: could not drop <db>_pre_restore_* databases; drop them by hand (README)" >&2
# Only now (restore finalized by the server) may backups run again. The watchdog CronJob is never suspended.
for c in $CRONS; do
  if [ -n "$(k get cronjob "$c" -o jsonpath='{.metadata.annotations.kotobaya/suspended-by-restore}')" ]; then
    k patch cronjob "$c" -p '{"spec":{"suspend":false}}' >/dev/null
    k annotate cronjob "$c" kotobaya/suspended-by-restore- >/dev/null
  fi
done
echo "restore complete; api is ready; backup CronJobs resumed"
