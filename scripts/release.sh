#!/usr/bin/env bash
# One-shot release pipeline for ReVoman, with automatic propagation into Core.
#
#   bump version (Config.kt + README.adoc) -> commit "Release X" -> push master
#     -> publish to Maven Central -> WAIT until the jar is live on repo1.maven.org
#     -> bump the dependency version in Core via graph-tool -> commit -> push
#
# Usage:
#   scripts/release.sh <new-version> [poll-interval-seconds]
#   scripts/release.sh 0.9.12
#   scripts/release.sh 0.9.12 30
#
# Run it detached so it survives closing the terminal:
#   nohup scripts/release.sh 0.9.12 > /tmp/revoman-release-0.9.12.log 2>&1 &
#   tail -f /tmp/revoman-release-0.9.12.log
#
# GUARDS (this is a publish + push pipeline, so it refuses to run on a messy state):
#   - revoman working tree MUST be clean (so the release commit touches ONLY the
#     two version files, never your in-flight work).
#   - must be on the `master` branch.
#   - the target version must NOT already exist on Maven Central.
#
# NOTE on GPG: revoman has commit.gpgsign=true. Detached runs need the GPG
# passphrase already cached in gpg-agent, otherwise the commit step blocks on a
# pinentry prompt that nohup can't answer. Cache it once before launching, e.g.
# by making any signed commit interactively earlier in the session.

set -euo pipefail

# --- inputs -----------------------------------------------------------------
NEW_VERSION="${1:?Usage: release.sh <new-version> [poll-interval-seconds]   e.g. release.sh 0.9.12}"
INTERVAL="${2:-60}"

REVOMAN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Core checkout: override with CORE_DIR=<path>; else the first of the common locations that exists.
CORE_DIR="${CORE_DIR:-}"
if [[ -z "${CORE_DIR}" ]]; then
  for c in "${HOME}/core-public/core" "/opt/workspace/core-public/core" "/Users/${USER}/core-public/core"; do
    [[ -d "${c}" ]] && CORE_DIR="${c}" && break
  done
fi

GROUP_PATH="com/salesforce/revoman/revoman"
JAR_URL="https://repo1.maven.org/maven2/${GROUP_PATH}/${NEW_VERSION}/revoman-${NEW_VERSION}.jar"

# Exact tasks you publish with. Swap to system `gradle` here if you prefer.
PUBLISH_CMD=(./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository -Dorg.gradle.parallel=false --no-configuration-cache)

step() { printf '\n=== %s ===\n' "$1"; }
die()  { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

# --- 1. guards --------------------------------------------------------------
step "Pre-flight guards"
cd "${REVOMAN_DIR}"

[[ -z "$(git status --porcelain)" ]] || {
  git status --short >&2
  die "revoman working tree is dirty. Commit or stash your in-flight work first — a release must only touch the version files."
}

branch="$(git branch --show-current)"
[[ "${branch}" == "master" ]] || die "expected branch 'master', on '${branch}'."

code="$(curl -s -o /dev/null -w '%{http_code}' "${JAR_URL}" || echo 000)"
[[ "${code}" != "200" ]] || die "revoman ${NEW_VERSION} is already on Maven Central. Pick a new version."

CURRENT="$(grep -E 'const val VERSION' buildSrc/src/main/kotlin/Config.kt | sed -E 's/.*"([^"]+)".*/\1/')"
echo "Releasing ${CURRENT} -> ${NEW_VERSION}"

# --- 2. bump version (Config.kt + README.adoc) ------------------------------
step "Bump version files"
# perl -i is byte-for-byte identical on macOS and Linux (GNU vs BSD `sed -i` differ on the backup-suffix arg).
perl -i -pe "s/(const val VERSION = \")[^\"]+(\")/\${1}${NEW_VERSION}\${2}/" buildSrc/src/main/kotlin/Config.kt
perl -i -pe "s/(:revoman-version: ).*/\${1}${NEW_VERSION}/" README.adoc
git --no-pager diff -- buildSrc/src/main/kotlin/Config.kt README.adoc

# --- 3. commit + push master ------------------------------------------------
step "Commit + push master"
git add buildSrc/src/main/kotlin/Config.kt README.adoc
git commit -s -m "Release ${NEW_VERSION}"
git push origin master

# --- 4. publish to Maven Central --------------------------------------------
step "Publish to Maven Central (${PUBLISH_CMD[*]})"
"${PUBLISH_CMD[@]}"

# --- 5. wait until the jar is live ------------------------------------------
step "Wait for jar to appear on Maven Central"
"${REVOMAN_DIR}/scripts/watch-maven-central.sh" "${NEW_VERSION}" "${INTERVAL}"

# --- 6. propagate into Core -------------------------------------------------
step "Bump revoman dependency in Core"
[[ -n "${CORE_DIR}" && -d "${CORE_DIR}" ]] || die "Core checkout not found. Set CORE_DIR=<path> to your Core repo."
cd "${CORE_DIR}"
core_branch="$(git branch --show-current)"
echo "Core branch: ${core_branch}"
# _REVOMAN_VERSION (in third_party/dependencies/com_salesforce_revoman.bzl) drives BOTH the source dep
# and the pinned catalog; `set-version-variable --pin-dependencies` updates the variable AND
# regenerates the pinned catalog in one step. The final positional arg is the Core checkout path.
# (The subcommand was once `update-version-variable` + a separate `pin-dependencies` call; graph-tool
# renamed it to `set-version-variable` and folded pinning into the `--pin-dependencies` flag.)
bazel run //:graph-tool -- set-version-variable \
  --variable-name=_REVOMAN_VERSION --new-version="${NEW_VERSION}" --scm=git --pin-dependencies --batch-mode "${CORE_DIR}"

if [[ -z "$(git status --porcelain)" ]]; then
  die "graph-tool made no changes — is Core already on ${NEW_VERSION}?"
fi

step "Commit + push Core"
git --no-pager diff --stat
git add -u
git commit -s -m "Bump com.salesforce.revoman:revoman to ${NEW_VERSION}"
# Core checkouts don't use a remote named `origin` (they are versioned, e.g. `264`); push to the
# current branch's configured upstream remote instead of a hardcoded name.
core_push_remote="$(git rev-parse --abbrev-ref --symbolic-full-name '@{push}' 2>/dev/null | cut -d/ -f1)"
git push "${core_push_remote:-origin}" HEAD

step "DONE"
echo "✅ revoman ${NEW_VERSION} published to Maven Central and propagated into Core (${core_branch})."
