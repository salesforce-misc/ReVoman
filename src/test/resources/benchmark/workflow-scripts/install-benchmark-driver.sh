set -euo pipefail
"$GITHUB_WORKSPACE/harness/gradlew" -p "$GITHUB_WORKSPACE/harness" \
  :benchmark-driver:installDist
