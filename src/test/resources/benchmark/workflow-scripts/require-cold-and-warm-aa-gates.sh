set -euo pipefail
DRIVER="$GITHUB_WORKSPACE/harness/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver"
set +e
"$DRIVER" compare \
  --input "$RUN_ROOT/results/cold-aa.json" \
  --output-json "$RUN_ROOT/results/comparison-aa-cold.json" \
  --output-md "$RUN_ROOT/results/comparison-aa-cold.md" \
  --enforce-release-gates
cold_status=$?
"$DRIVER" compare \
  --input "$RUN_ROOT/results/warm-aa.json" \
  --output-json "$RUN_ROOT/results/comparison-aa-warm.json" \
  --output-md "$RUN_ROOT/results/comparison-aa-warm.md" \
  --enforce-release-gates
warm_status=$?
set -e
if (( cold_status != 0 || warm_status != 0 )); then
  printf '%s\n' \
    '# INCONCLUSIVE' \
    '' \
    'Candidate measurement was not started because cold or warm A/A did not pass.' \
    > "$RUN_ROOT/results/INCONCLUSIVE.md"
  exit 3
fi
