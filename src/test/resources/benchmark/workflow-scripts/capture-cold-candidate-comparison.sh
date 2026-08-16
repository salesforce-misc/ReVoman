set -euo pipefail
DRIVER="$GITHUB_WORKSPACE/harness/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver"
"$DRIVER" run-paired --mode cold --intent controlled \
  --baseline "$RUN_ROOT/manifests/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
  --candidate "$RUN_ROOT/manifests/candidate.json" --candidate-adapter "$CANDIDATE_ADAPTER" \
  --workload lifecycle.no-script-one-step.v1 --blocks 50 --forks-per-block 1 \
  --warmups 0 --iterations 1 --seed 5928239383101656625 \
  --metrics latency,peak-rss,allocation \
  --host-policy "$HOST_POLICY_PATH" \
  --artifacts-dir "$RUN_ROOT/jfr/cold-candidate" \
  --output "$RUN_ROOT/results/cold-candidate.json"
