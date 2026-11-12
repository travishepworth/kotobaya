#!/usr/bin/env bash
# In-cluster restore (contracts/sync/SYNC.md 6.2 / 6.3 "Restore script"). Run by restore-k8s.sh in a
# Job with the api and worker scaled to zero. Drops and recreates the application database, so it
# refuses while anything is connected (plain DROP DATABASE, no FORCE).
#
#   1. Restore the newest VERIFIED snapshot. An older one only with RESTORE_SNAPSHOT=<id> AND
#      RESTORE_CONFIRM_OLDER=yes: ops devices already purged (they trusted the newer durable_seq)
#      would be lost.
#   2. Restore the blobs (rclone copy; adds the mirror's objects, deletes nothing).
#   3. In the restored database set restore_pending = '1'. Nothing else in server_state:
#      the server finalizes at startup (new log_epoch, durable_seq, HLC).
#   Data-loss warning (F-4, SYNC.md 6.3 restore step 1): refused unless RESTORE_ACCEPT_DATA_LOSS=yes
#   (or --accept-data-loss) when the backup may be older than what devices already purged: the live
#   database is readable, has the backup's log_epoch and a durable_seq above the backup's S; or, with
#   the live database unreadable, a retained backup of that epoch recorded L above S. The message
#   names the number of seqs at risk. RESTORE_MODE=preflight runs only the snapshot choice and these
#   checks (restore-k8s.sh runs it BEFORE stopping anything, so a refusal leaves the server up).
# Re-running after a crash starts from step 1 again.
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"
trap 'finish restore' EXIT

for a in "$@"; do
  case $a in --accept-data-loss) RESTORE_ACCEPT_DATA_LOSS=yes;; *) echo "unknown argument: $a" >&2; exit 2;; esac
done
: "${WORK_DIR:=/work}"
db_env
LIKE_BASE="${LIVE_DB//_/\\_}"
LIKE_OLD="${LIKE_BASE}\\_pre\\_restore\\_%"
dbs_pre_restore() { psqlq -d postgres -c "SELECT datname FROM pg_database WHERE datname LIKE '$LIKE_OLD' ORDER BY 1"; }

# RESTORE_MODE=cleanup (run by restore-k8s.sh after the server finalized): drop the pre-restore copies.
if [ "${RESTORE_MODE:-}" = cleanup ]; then
  wait_net
  # Only the copy THIS run created (RESTORE_STAMP); older operator-kept copies are never touched.
  [ -n "${RESTORE_STAMP:-}" ] || { log "cleanup needs RESTORE_STAMP; nothing dropped"; trap - EXIT; exit 0; }
  d="${LIVE_DB}_pre_restore_${RESTORE_STAMP}"
  psqlq -d postgres -c "DROP DATABASE IF EXISTS \"$d\" WITH (FORCE)"; log "dropped $d (if it existed)"
  trap - EXIT; exit 0
fi

restic_need; rclone_env; wait_net
rm -rf "${WORK_DIR:?}"/* 2>/dev/null || true

# Picks SNAP (newest verified, or RESTORE_SNAPSHOT with confirmation) and refuses on the data-loss
# warning. Exits 2 on a refusal.
choose_snapshot() {
  local newest want snaps tags E S
  newest=$(restic snapshots --tag verified --latest 1 --json | jq -r 'sort_by(.time) | last | .id // empty')
  [ -n "$newest" ] || { FAIL_DETAIL="no verified snapshot to restore. "; log "$FAIL_DETAIL"; exit 1; }
  SNAP=$newest
  if [ -n "${RESTORE_SNAPSHOT:-}" ]; then
    want=$(restic snapshots "$RESTORE_SNAPSHOT" --json | jq -r '.[0].id // empty')
    [ -n "$want" ] || { log "snapshot $RESTORE_SNAPSHOT not found"; exit 1; }
    if [ "$want" != "$newest" ]; then
      log "WARNING: $want is NOT the newest verified snapshot ($newest). Restoring it loses ops that devices"
      log "WARNING: already purged. Devices cannot recover them. Set RESTORE_CONFIRM_OLDER=yes to proceed."
      [ "${RESTORE_CONFIRM_OLDER:-}" = yes ] || { FAIL_DETAIL="older snapshot refused without confirmation. "; exit 2; }
      restic snapshots "$want" --json | jq -e '.[0].tags | index("verified")' >/dev/null \
        || { log "snapshot $want is not verified"; exit 1; }
      SNAP=$want
    fi
  fi
  snaps=$(restic snapshots --tag verified --json)
  tags=$(jq -c --arg id "$SNAP" '[.[] | select(.id == $id)] | first | .tags // []' <<<"$snaps")
  E=$(tag_value epoch <<<"$tags"); S=$(tag_value seq <<<"$tags")
  if [ -z "$S" ] || [ -z "$E" ]; then
    FAIL_DETAIL="snapshot $SNAP has no epoch:/seq: tags; cannot check for data loss. "; log "$FAIL_DETAIL"; exit 1
  fi
  if [ "$E" != none ] && data_loss_check "$E" "$S" "$snaps"; then
    log "WARNING: DATA LOSS: restoring snapshot $SNAP (seqs up to $S) may lose up to $LOSS_SEQS seqs."
    log "WARNING: $LOSS_WHY."
    log "WARNING: devices may already have purged those ops; if so they cannot be recovered."
    log "WARNING: Pass --accept-data-loss (RESTORE_ACCEPT_DATA_LOSS=yes) to proceed."
    [ "${RESTORE_ACCEPT_DATA_LOSS:-}" = yes ] || { FAIL_DETAIL="restore refused: up to $LOSS_SEQS seqs may be lost (re-run with --accept-data-loss). "; exit 2; }
    log "data-loss warning accepted explicitly"
  fi
}
# Preflight: only the checks, no recovery, no changes (restore-k8s.sh runs it before stopping anything).
if [ "${RESTORE_MODE:-}" = preflight ]; then
  choose_snapshot; log "preflight OK: snapshot $SNAP may be restored"; trap - EXIT; exit 0
fi

# Crash recovery: a previous run that died between the two renames leaves no live database.
if [ "$(psqlq -d postgres -c "SELECT count(*) FROM pg_database WHERE datname = '$LIVE_DB'")" = 0 ]; then
  olds=$(dbs_pre_restore)
  case $(printf '%s' "$olds" | grep -c .) in
    1) log "live database $LIVE_DB is missing; a previous restore died between its renames. Putting $olds back."
       psqlq -d postgres -c "ALTER DATABASE \"$olds\" RENAME TO \"$LIVE_DB\"";;
    0) log "live database $LIVE_DB is missing and there is no ${LIVE_DB}_pre_restore_* copy: restoring into a new one";;
    *) FAIL_DETAIL="live database missing and several ${LIVE_DB}_pre_restore_* copies exist ($(echo $olds)). Rename the right one back to $LIVE_DB by hand (ALTER DATABASE ... RENAME TO), then re-run. "
       log "$FAIL_DETAIL"; exit 4;;
  esac
fi
# Leftover temporary copies of earlier attempts are worthless: drop them.
for d in $(psqlq -d postgres -c "SELECT datname FROM pg_database WHERE datname LIKE '${LIKE_BASE}\\_restoring\\_%'"); do
  psqlq -d postgres -c "DROP DATABASE \"$d\" WITH (FORCE)"
done

choose_snapshot

log "1/3 restoring snapshot $SNAP"
restic restore "$SNAP" --target "$WORK_DIR/r" >/dev/null
DUMP=$(find "$WORK_DIR/r" -name db.dump | head -n 1)
COUNTS=$(find "$WORK_DIR/r" -name counts.json | head -n 1)
BLOBS=$(dirname "$DUMP")/blobs
[ -s "$DUMP" ] && [ -s "$COUNTS" ] && [ -d "$BLOBS" ] || { log "snapshot lacks db.dump, counts.json or blobs/"; exit 1; }
pg_restore --list "$DUMP" >/dev/null

# Load into a temporary database first: a failed or mismatched restore leaves the live DB untouched.
create_scratch "${LIVE_DB}_restoring"
pg_restore --no-owner --exit-on-error -d "$SCRATCH_DB" "$DUMP"

log "2/3 restoring blobs"
rclone copy "$BLOBS" "src:$S3_BUCKET" --transfers 4
check_blobs "$SCRATCH_DB" "$BLOBS"

log "3/3 restore_pending, then swap the restored database in"
psqlq -d "$SCRATCH_DB" <<'SQL'
INSERT INTO app.server_state (key, value, updated_at) VALUES ('restore_pending', '1', now())
ON CONFLICT (key) DO UPDATE SET value = '1', updated_at = now();
SQL
# the restored data must equal the backup (the epoch is still the old one until the server finalizes)
compare_counts "$COUNTS" "$SCRATCH_DB"
# Swap: the renames fail while anything is connected (api/worker must be stopped).
# From here the EXIT trap must NOT drop the restored copy (a death between the renames would
# otherwise destroy both): SCRATCH_DB is cleared first. If the job dies between the renames the live
# name is missing, and the next run puts the pre-restore copy back (start of this script).
# The pre-restore database is KEPT; restore-k8s.sh drops it (RESTORE_MODE=cleanup) once the server
# has finalized. Manual cleanup: DROP DATABASE "<db>_pre_restore_<timestamp>".
RESTORED=$SCRATCH_DB; SCRATCH_DB=""
OLD="${LIVE_DB}_pre_restore_${RESTORE_STAMP:-$(date -u +%Y%m%d%H%M%S)}"
if [ "$(psqlq -d postgres -c "SELECT count(*) FROM pg_database WHERE datname = '$LIVE_DB'")" != 0 ]; then
  psqlq -d postgres -c "ALTER DATABASE \"$LIVE_DB\" RENAME TO \"$OLD\"" \
    || { psqlq -d postgres -c "DROP DATABASE \"$RESTORED\" WITH (FORCE)"; exit 1; }   # live still in place
fi
if [ -n "${KILL_BETWEEN_RENAMES:-}" ] && [ "${DRILL:-}" != 1 ]; then
  log "KILL_BETWEEN_RENAMES is a drill-only hook and requires DRILL=1; refusing"; exit 2
fi
[ -z "${KILL_BETWEEN_RENAMES:-}" ] || { log "KILL_BETWEEN_RENAMES set (drill): dying between the renames"; kill -9 $$; }
psqlq -d postgres -c "ALTER DATABASE \"$RESTORED\" RENAME TO \"$LIVE_DB\""
log "restore OK: snapshot $SNAP loaded, restore_pending set; previous database kept as $OLD until the server has finalized"
