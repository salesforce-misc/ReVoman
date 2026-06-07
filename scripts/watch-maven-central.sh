#!/usr/bin/env bash
# Watch Maven Central for a ReVoman release and notify when the jar is live.
#
# Usage:
#   scripts/watch-maven-central.sh [VERSION] [INTERVAL_SECONDS]
#
#   VERSION           Version to wait for. Defaults to VERSION in buildSrc/src/main/kotlin/Config.kt.
#   INTERVAL_SECONDS  Poll interval. Defaults to 60.
#
# Exits 0 the moment the .jar returns HTTP 200 from repo1.maven.org.
# On macOS it also fires a desktop notification, a terminal bell, and `say`.

set -euo pipefail

GROUP_PATH="com/salesforce/revoman/revoman"
BASE="https://repo1.maven.org/maven2/${GROUP_PATH}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

VERSION="${1:-}"
if [[ -z "${VERSION}" ]]; then
  VERSION="$(grep -E 'const val VERSION' "${ROOT_DIR}/buildSrc/src/main/kotlin/Config.kt" \
    | sed -E 's/.*"([^"]+)".*/\1/')"
fi
INTERVAL="${2:-60}"

JAR_URL="${BASE}/${VERSION}/revoman-${VERSION}.jar"

notify() {
  local msg="$1"
  if [[ "$(uname)" == "Darwin" ]]; then
    osascript -e "display notification \"${msg}\" with title \"Maven Central\" sound name \"Glass\"" >/dev/null 2>&1 || true
    printf '\a'                                  # terminal bell
    say "revoman ${VERSION} is live on maven central" >/dev/null 2>&1 || true
  fi
}

echo "Watching ${JAR_URL}"
echo "Polling every ${INTERVAL}s. Ctrl-C to stop."

attempt=0
while true; do
  attempt=$((attempt + 1))
  code="$(curl -s -o /dev/null -w '%{http_code}' "${JAR_URL}" || echo 000)"
  ts="$(date '+%H:%M:%S')"
  if [[ "${code}" == "200" ]]; then
    echo "[${ts}] attempt ${attempt}: HTTP 200 — LIVE"
    notify "revoman ${VERSION} is live"
    echo "✅ revoman ${VERSION} is on Maven Central: ${JAR_URL}"
    exit 0
  fi
  echo "[${ts}] attempt ${attempt}: HTTP ${code} — not yet"
  sleep "${INTERVAL}"
done
