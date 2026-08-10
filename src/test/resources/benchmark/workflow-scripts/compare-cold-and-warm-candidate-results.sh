set -euo pipefail
DRIVER="$GITHUB_WORKSPACE/harness/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver"
set +e
"$DRIVER" compare \
  --input "$RUN_ROOT/results/cold-candidate.json" \
  --output-json "$RUN_ROOT/results/comparison-candidate-cold.json" \
  --output-md "$RUN_ROOT/results/comparison-candidate-cold.md" \
  --enforce-release-gates
cold_status=$?
"$DRIVER" compare \
  --input "$RUN_ROOT/results/warm-candidate.json" \
  --output-json "$RUN_ROOT/results/comparison-candidate-warm.json" \
  --output-md "$RUN_ROOT/results/comparison-candidate-warm.md" \
  --enforce-release-gates
warm_status=$?
set -e
if (( cold_status != 0 || warm_status != 0 )); then
  exit 3
fi
