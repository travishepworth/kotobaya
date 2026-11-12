# Backup image: pg_dump/pg_restore 17, restic, rclone (S3 blob mirror), plus the scripts in this directory.
# Build context: deploy/backup/.   docker build -t <registry>/backup:<tag> deploy/backup
FROM alpine:3.21
RUN apk add --no-cache tini kubectl bash curl jq restic rclone postgresql17-client coreutils \
 && pg_dump --version | grep -q ' 17\.'
COPY lib.sh backup.sh verify.sh restore.sh watchdog.sh durable_seq.sql /opt/backup/
RUN chmod 0555 /opt/backup/*.sh
USER 65534
ENV HOME=/tmp
WORKDIR /tmp
