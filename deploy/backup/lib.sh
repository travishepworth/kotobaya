#!/usr/bin/env bash
# Shared helpers for backup.sh, verify.sh, restore.sh. Source it; it sets no shell options itself.

log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" >&2; }

# Failure notification: POST to NOTIFY_URL (ntfy-compatible: body = message, Title header).
# Never fails the caller; the script's own non-zero exit is what makes the Job visibly fail.
notify() {
  local title=$1 msg=$2
  if [ -z "${NOTIFY_URL:-}" ]; then log "NOTIFY_URL not set; not notifying: $title"; return 0; fi
  curl -fsS -m 20 -H "Title: $title" -H "Priority: high" -H "Tags: rotating_light" \
    -d "$msg" "$NOTIFY_URL" >/dev/null || log "WARNING: notification to NOTIFY_URL failed"
}

# EXIT trap of the job scripts: report failure once, drop the scratch database.
# Usage: trap 'finish "<job name>"' EXIT
finish() {
  local rc=$?
  if [ "$rc" -ne 0 ]; then
    log "FAILED: $1 (exit $rc)"
    notify "kotobaya $1 FAILED" "$1 failed with exit code $rc in $(hostname) at $(date -u +%FT%TZ). ${FAIL_DETAIL:-}"
  fi
  [ -z "${SCRATCH_DB:-}" ] || drop_scratch || true
  exit "$rc"
}

urldecode() { local s=${1//+/ }; printf '%b' "${s//%/\\x}"; }

# JDBC URL (jdbc:postgresql://host[:port]/db?user=..&password=..) -> PG* environment for libpq tools.
# The same DATABASE_URL the server uses; credentials may be in the query string (as the chart does)
# or in userinfo.
jdbc_to_pg() {
  local url=${1#jdbc:}
  url=${url#postgresql://}; url=${url#postgres://}
  local hostpart=${url%%/*} rest=${url#*/}
  local db=${rest%%\?*} query=""
  case $rest in *\?*) query=${rest#*\?};; esac
  local hostport=$hostpart
  PGUSER=""; PGPASSWORD=""
  case $hostpart in *@*)
    local ui=${hostpart%@*}; hostport=${hostpart#*@}
    PGUSER=$(urldecode "${ui%%:*}"); case $ui in *:*) PGPASSWORD=$(urldecode "${ui#*:}");; esac;;
  esac
  PGHOST=${hostport%%:*}
  case $hostport in *:*) PGPORT=${hostport##*:};; *) PGPORT=5432;; esac
  PGDATABASE=$(urldecode "$db")
  local kv k v ifs=$IFS
  IFS='&'
  for kv in $query; do
    k=${kv%%=*}; v=${kv#*=}
    case $k in user) PGUSER=$(urldecode "$v");; password) PGPASSWORD=$(urldecode "$v");; esac
  done
  IFS=$ifs
  export PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD
}

# Requires DATABASE_URL; sets PG* and LIVE_DB.
db_env() {
  : "${DATABASE_URL:?DATABASE_URL is required}"
  jdbc_to_pg "$DATABASE_URL"
  LIVE_DB=$PGDATABASE; export LIVE_DB
  : "${PGUSER:?no user in DATABASE_URL}"
}

psqlq() { psql -X -q -v ON_ERROR_STOP=1 -At "$@"; }

# Scratch database. V1 needs a UTF-8 ctype (a C ctype aborts the migration), so create it C.UTF-8.
create_scratch() {
  SCRATCH_DB="${1:-kotobaya_scratch}_$(date -u +%Y%m%d%H%M%S)_$$"
  psqlq -d postgres -c "CREATE DATABASE \"$SCRATCH_DB\" TEMPLATE template0 ENCODING 'UTF8' LC_COLLATE 'C.UTF-8' LC_CTYPE 'C.UTF-8'" >/dev/null
  log "scratch database $SCRATCH_DB created"
}
drop_scratch() {
  local d=$SCRATCH_DB; SCRATCH_DB=""
  psqlq -d postgres -c "DROP DATABASE IF EXISTS \"$d\" WITH (FORCE)" >/dev/null && log "scratch database $d dropped"
}

# Counts of database $1 as one JSON object: what backup.sh records at dump time (from the dump
# itself) and verify.sh compares after a later restore.
db_counts() {
  psqlq -d "$1" <<'SQL'
SELECT json_build_object(
  'change_max_seq', (SELECT coalesce(max(seq), 0) FROM app.change),
  'review_log',     (SELECT count(*) FROM app.review_log),
  'note',           (SELECT count(*) FROM app.note),
  'card',           (SELECT count(*) FROM app.card),
  'dict_entry',     (SELECT count(*) FROM ref.dict_entry),
  'blob',           (SELECT count(*) FROM app.blob),
  'log_epoch',      (SELECT value FROM app.server_state WHERE key = 'log_epoch'),
  'v1_checksum',    (SELECT checksum::text FROM public.flyway_schema_history WHERE version = '1' AND success)
);
SQL
}

# rclone remote "src" = the application's S3 store (S3_* env, same names the server uses).
rclone_env() {
  : "${S3_ENDPOINT:?}" "${S3_BUCKET:?}" "${S3_ACCESS_KEY:?}" "${S3_SECRET_KEY:?}"
  export RCLONE_CONFIG_SRC_TYPE=s3 RCLONE_CONFIG_SRC_PROVIDER=Other \
    RCLONE_CONFIG_SRC_ENDPOINT="$S3_ENDPOINT" RCLONE_CONFIG_SRC_REGION="${S3_REGION:-us-east-1}" \
    RCLONE_CONFIG_SRC_ACCESS_KEY_ID="$S3_ACCESS_KEY" RCLONE_CONFIG_SRC_SECRET_ACCESS_KEY="$S3_SECRET_KEY" \
    RCLONE_CONFIG_SRC_FORCE_PATH_STYLE=true RCLONE_CONFIG_SRC_NO_CHECK_BUCKET=true
}

# Every app.blob row of database $1 must exist as a file named <hash> in directory $2:
# a restored database must never reference a blob the mirror lacks.
check_blobs() {
  local db=$1 dir=$2 missing
  missing=$(comm -23 <(psqlq -d "$db" -c "SELECT hash FROM app.blob" | sort) \
                     <( (cd "$dir" && ls -1A) | sort))
  if [ -n "$missing" ]; then
    FAIL_DETAIL="blobs referenced by the database but missing from the mirror: $(echo "$missing" | head -5 | tr '\n' ' ')"
    log "$FAIL_DETAIL"; return 1
  fi
}

restic_need() { : "${RESTIC_REPOSITORY:?}" "${RESTIC_PASSWORD:?}"; }

# SIGTERM (pod deletion, activeDeadlineSeconds) must run the EXIT trap so the failure is announced.
# bash runs the trap once its foreground child ends; the Job's command is `tini -g`, which signals
# the whole process group. SIGKILL (OOM, eviction) cannot be trapped: check_fresh covers those.
trap 'exit 143' TERM INT

# Staleness: return 1 (FAIL_DETAIL set) when the newest verified snapshot is older than STALE_SECONDS
# (chart: backup.staleHours, about 2x the schedule), so a job that dies silently (OOMKill, eviction,
# deadline) or never runs is noticed by the next job that does run.
# No verified snapshot at all is not "stale" here (fresh repository); verify.sh rejects that itself.
check_fresh() {
  local newest age t e
  # Each timestamp is parsed (any UTC offset) to epoch seconds; never compare the strings.
  newest=0
  while read -r t; do
    [ -n "$t" ] || continue
    e=$(date -u -d "$t" +%s); [ "$e" -gt "$newest" ] && newest=$e
  done < <(restic snapshots --tag verified --json | jq -r '.[].time')
  [ "$newest" -gt 0 ] || return 0
  age=$(( $(date -u +%s) - newest ))
  if [ "$age" -gt "${STALE_SECONDS:-187200}" ]; then
    FAIL_DETAIL="newest verified backup is $((age / 3600)) h old (limit $(( ${STALE_SECONDS:-187200} / 3600 )) h): backups are not completing. "
    log "$FAIL_DETAIL"; return 1
  fi
}

# Compare recorded counts ($1 = JSON file) with the live counts of database $2. Prints each field.
# BACKUP_VERIFY_INJECT_FAILURE=1 (drill only) corrupts the expected note count by one.
compare_counts() {
  local want=$1 db=$2 got f w g rc=0
  got=$(db_counts "$db")
  for f in change_max_seq review_log note card dict_entry blob log_epoch v1_checksum; do
    w=$(jq -r ".$f" "$want"); g=$(jq -r ".$f" <<<"$got")
    if [ "${BACKUP_VERIFY_INJECT_FAILURE:-}" = 1 ] && [ "$f" = note ]; then w=$((w + 1)); fi
    if [ "$w" = "$g" ] && { [ "$w" != null ] || [ "$f" = log_epoch ]; }; then log "check $f: $g OK"
    else log "check $f: recorded=$w restored=$g MISMATCH"; rc=1; FAIL_DETAIL="${FAIL_DETAIL:-}count mismatch on $f (recorded $w, restored $g). "; fi
  done
  return $rc
}

# A freshly started pod can be refused by the cluster's network policy for the first seconds.
# Wait (up to ~2 min) until Postgres and the S3 endpoint answer.
wait_net() {
  local i
  for i in $(seq 1 40); do
    pg_isready -q -d postgres && break
    [ "$i" = 40 ] && { log "postgres not reachable"; return 1; }
    sleep 3
  done
  if [ -n "${S3_ENDPOINT:-}" ]; then
    for i in $(seq 1 40); do
      curl -s -o /dev/null -m 3 "$S3_ENDPOINT" && break
      [ "$i" = 40 ] && { log "S3 endpoint not reachable"; return 1; }
      sleep 3
    done
  fi
}
