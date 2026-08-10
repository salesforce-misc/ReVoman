set -euo pipefail
TARGET_ROOT="$GITHUB_WORKSPACE/candidate"
TARGET_ID=candidate
TARGET_MANIFEST="$RUN_ROOT/manifests/candidate.json"
"$TARGET_ROOT/gradlew" -p "$TARGET_ROOT" \
  -I "$GITHUB_WORKSPACE/harness/benchmark-driver/build/install/benchmark-driver/libexec/benchmark-target.init.gradle.kts" \
  clean writeBenchmarkTargetManifest \
  -Pbenchmark.targetId="$TARGET_ID" \
  -Pbenchmark.targetManifest="$TARGET_MANIFEST"
