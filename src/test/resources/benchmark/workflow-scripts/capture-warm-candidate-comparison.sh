set -euo pipefail
DRIVER="$GITHUB_WORKSPACE/harness/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver"
"$DRIVER" run-paired --mode warm --intent controlled \
  --baseline "$RUN_ROOT/manifests/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
  --candidate "$RUN_ROOT/manifests/candidate.json" --candidate-adapter "$CANDIDATE_ADAPTER" \
  --workload lifecycle.no-script-one-step.v1 --blocks 5 --forks-per-block 1 \
  --warmups 20 --iterations 100 --seed 5928239383101656625 \
  --metrics latency,allocation \
  --host-policy "$HOST_POLICY_PATH" \
  --artifacts-dir "$RUN_ROOT/jfr/warm-candidate" \
  --output "$RUN_ROOT/results/warm-candidate.json"
