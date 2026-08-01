#!/usr/bin/env bash
# One-shot release pipeline for ReVoman, with automatic propagation into Core.
#
#   bump version (Config.kt + README.adoc + docs/antora.yml) -> commit "Release X"
#     -> push master -> publish to Maven Central -> WAIT until the jar is live on
#     repo1.maven.org -> bump the dependency version in Core via graph-tool -> commit -> push
#
# Usage:
#   scripts/release.sh <new-version> [poll-interval-seconds]
#   scripts/release.sh 0.9.12
#   scripts/release.sh 0.9.12 30
#
#   Set SKIP_CORE=1 to publish to Maven Central only and stop BEFORE touching Core
#   (bump + commit the version files, publish, wait for the jar — no Core push):
#     SKIP_CORE=1 scripts/release.sh 0.90.0
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
# SKIP_CORE=1 stops the pipeline after the Maven Central publish + jar-live wait,
# before the Core dependency bump/commit/push. Default 0 (full propagation).
SKIP_CORE="${SKIP_CORE:-0}"

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

# Monotonic-version guard: the new version MUST sort strictly ABOVE the version Maven Central
# currently advertises as <release> in maven-metadata.xml — the field the version badge and every
# `latest`/range resolver reads. Maven orders per-segment numerically, so e.g. 0.9.18 sorts BELOW a
# pre-existing 0.82.0 (segment 9 < 82); publishing it would leave the badge/resolvers pinned to the
# older release. `sort -V` reproduces that per-segment numeric order without extra deps. A transient
# metadata-fetch failure only WARNS (a network blip must not wedge a legit release); the compare
# runs only when we actually have a live <release>.
METADATA_URL="https://repo1.maven.org/maven2/${GROUP_PATH}/maven-metadata.xml"
LIVE_RELEASE="$(curl -s "${METADATA_URL}" | perl -ne 'print $1 if m{<release>(.*?)</release>}')"
if [[ -z "${LIVE_RELEASE}" ]]; then
  echo "WARN: could not read <release> from ${METADATA_URL} — skipping the monotonic-version guard." >&2
elif [[ "${NEW_VERSION}" == "${LIVE_RELEASE}" ]]; then
  die "revoman ${NEW_VERSION} equals the current Maven Central <release>. Pick a higher version."
else
  highest="$(printf '%s\n%s\n' "${LIVE_RELEASE}" "${NEW_VERSION}" | sort -V | tail -1)"
  [[ "${highest}" == "${NEW_VERSION}" ]] || die \
    "revoman ${NEW_VERSION} sorts BELOW the current Maven Central <release> ${LIVE_RELEASE} (Maven orders per-segment numerically). Publishing it would leave the badge/resolvers pinned to ${LIVE_RELEASE}. Pick a version above ${LIVE_RELEASE}."
fi

CURRENT="$(grep -E 'const val VERSION' buildSrc/src/main/kotlin/Config.kt | sed -E 's/.*"([^"]+)".*/\1/')"
echo "Releasing ${CURRENT} -> ${NEW_VERSION} (current Maven Central <release>: ${LIVE_RELEASE:-unknown})"

# --- 2. bump version (Config.kt + README.adoc + docs/antora.yml) ------------
step "Bump version files"
# perl -i is byte-for-byte identical on macOS and Linux (GNU vs BSD `sed -i` differ on the backup-suffix arg).
perl -i -pe "s/(const val VERSION = \")[^\"]+(\")/\${1}${NEW_VERSION}\${2}/" buildSrc/src/main/kotlin/Config.kt
perl -i -pe "s/(:revoman-version: ).*/\${1}${NEW_VERSION}/" README.adoc
# Antora asciidoc attribute (docs site). It uses the SOFT-SET form `revoman-version: <v>@` —
# the trailing `@` lets a page override the attribute; preserve it. Matches only the value between
# the colon-space and the trailing `@`, so the `@` survives the bump.
perl -i -pe "s/(revoman-version: )[^\@\s]+(\@)/\${1}${NEW_VERSION}\${2}/" docs/antora.yml
git --no-pager diff -- buildSrc/src/main/kotlin/Config.kt README.adoc docs/antora.yml

# --- 3. commit + push master ------------------------------------------------
step "Commit + push master"
git add buildSrc/src/main/kotlin/Config.kt README.adoc docs/antora.yml
git commit -s -m "Release ${NEW_VERSION}"
git push origin master

# --- 4. publish to Maven Central --------------------------------------------
step "Publish to Maven Central (${PUBLISH_CMD[*]})"
"${PUBLISH_CMD[@]}"

# --- 5. wait until the jar is live ------------------------------------------
step "Wait for jar to appear on Maven Central"
"${REVOMAN_DIR}/scripts/watch-maven-central.sh" "${NEW_VERSION}" "${INTERVAL}"

# --- 6. propagate into Core -------------------------------------------------
if [[ "${SKIP_CORE}" == "1" ]]; then
  step "DONE (SKIP_CORE=1)"
  echo "✅ revoman ${NEW_VERSION} published to Maven Central. Core propagation SKIPPED."
  echo "   To propagate later, run from your Core checkout:"
  echo "     bazel run //:graph-tool -- set-dependency-version com.salesforce.revoman:revoman --new-version=${NEW_VERSION}"
  echo "     bazel run //:graph-tool -- pin-dependencies"
  exit 0
fi

step "Bump revoman dependency in Core"
[[ -n "${CORE_DIR}" && -d "${CORE_DIR}" ]] || die "Core checkout not found. Set CORE_DIR=<path> to your Core repo."
cd "${CORE_DIR}"
core_branch="$(git branch --show-current)"
echo "Core branch: ${core_branch}"
# Bump the revoman dependency by its Maven coordinate, then regenerate the pinned catalog. Run from
# CORE_DIR (cd'd above). `set-dependency-version <group:artifact>` is the right subcommand for a
# Maven-coord dep like revoman; `set-version-variable --variable-name=<VAR>` is for named version
# variables (e.g. _HTTP4K_VERSION). Convenience zsh wrappers: `graph-set-dep-version <coord> <v>` /
# `graph-set-version-variable <VAR> <v>`.
bazel run //:graph-tool -- set-dependency-version com.salesforce.revoman:revoman --new-version="${NEW_VERSION}"
bazel run //:graph-tool -- pin-dependencies

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
