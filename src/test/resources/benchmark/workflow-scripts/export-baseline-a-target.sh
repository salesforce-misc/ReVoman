set -euo pipefail
TARGET_ROOT="$GITHUB_WORKSPACE/baseline-a"
TARGET_ID=baseline-a
TARGET_MANIFEST="$RUN_ROOT/manifests/baseline-a.json"
"$TARGET_ROOT/gradlew" -p "$TARGET_ROOT" \
  -I "$GITHUB_WORKSPACE/harness/benchmark-driver/build/install/benchmark-driver/libexec/benchmark-target.init.gradle.kts" \
  clean writeBenchmarkTargetManifest \
  -Pbenchmark.targetId="$TARGET_ID" \
  -Pbenchmark.targetManifest="$TARGET_MANIFEST"
