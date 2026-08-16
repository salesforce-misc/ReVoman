set -euo pipefail
if [[ "$HOST_POLICY_PATH" != /* || ! -f "$HOST_POLICY_PATH" || ! -r "$HOST_POLICY_PATH" ]]; then
  echo "host_policy_path must be an absolute readable file" >&2
  exit 2
fi
RUNS_PARENT=/opt/revoman-benchmark/runs
if [[ ! -d "$RUNS_PARENT" || ! -w "$RUNS_PARENT" ]]; then
  echo "$RUNS_PARENT must be pre-provisioned and writable" >&2
  exit 1
fi
RUN_ROOT="/opt/revoman-benchmark/runs/${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
if [[ -e "$RUN_ROOT" || -L "$RUN_ROOT" ]]; then
  echo "Refusing to reuse existing benchmark run root: $RUN_ROOT" >&2
  exit 1
fi
umask 077
mkdir "$RUN_ROOT"
mkdir "$RUN_ROOT/manifests" "$RUN_ROOT/results" "$RUN_ROOT/jfr"
printf 'RUN_ROOT=%s\n' "$RUN_ROOT" >> "$GITHUB_ENV"
