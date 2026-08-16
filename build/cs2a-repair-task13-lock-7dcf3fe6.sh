#!/usr/bin/env bash
set -Eeuo pipefail

readonly EXPECTED_DEVICE_INODE=64516:2228908

dzdo /bin/bash -s -- "$EXPECTED_DEVICE_INODE" <<'CS2A_ROOT'
set -Eeuo pipefail

readonly LOCK_FILE=/opt/revoman-benchmark/task13.lock
readonly EXPECTED_DEVICE_INODE=$1

test -f "$LOCK_FILE"
test ! -L "$LOCK_FILE"
test "$(stat -Lc '%d:%i' "$LOCK_FILE")" = "$EXPECTED_DEVICE_INODE"

exec 9<>"$LOCK_FILE"
flock -n 9
test "$(stat -Lc '%d:%i' /proc/$$/fd/9)" = "$EXPECTED_DEVICE_INODE"
test "$(stat -Lc '%d:%i' "$LOCK_FILE")" = "$EXPECTED_DEVICE_INODE"

current_metadata=$(stat -Lc '%u:%g:%a:%s' "$LOCK_FILE")
expected_uid=$(id -u gopala.akshintala)
expected_gid=$(id -g gopala.akshintala)

case "$current_metadata" in
  0:0:600:0)
    ;;
  "$expected_uid:$expected_gid:660:0")
    chown root:root -- "$LOCK_FILE"
    chmod 0600 -- "$LOCK_FILE"
    ;;
  *)
    printf 'unexpected task13.lock metadata: %s\n' "$current_metadata" >&2
    exit 70
    ;;
esac

test "$(stat -Lc '%u:%g:%a:%s' "$LOCK_FILE")" = 0:0:600:0
test "$(stat -Lc '%d:%i' "$LOCK_FILE")" = "$EXPECTED_DEVICE_INODE"
flock -u 9
printf 'CS2A_TASK13_LOCK_READY=%s\n' "$EXPECTED_DEVICE_INODE"
CS2A_ROOT
