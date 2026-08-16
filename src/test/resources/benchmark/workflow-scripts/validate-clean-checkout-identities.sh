set -euo pipefail
[[ "$(git -C "$GITHUB_WORKSPACE/harness" rev-parse HEAD)" == "$HARNESS_REF" ]]
[[ "$(git -C "$GITHUB_WORKSPACE/baseline-a" rev-parse HEAD)" == "83f3cd70f78ad733412d10cbc8287aaabafe7aac" ]]
[[ "$(git -C "$GITHUB_WORKSPACE/baseline-b" rev-parse HEAD)" == "83f3cd70f78ad733412d10cbc8287aaabafe7aac" ]]
[[ "$(git -C "$GITHUB_WORKSPACE/candidate" rev-parse HEAD)" == "$CANDIDATE_REF" ]]
for checkout in harness baseline-a baseline-b candidate; do
  if [[ -n "$(git -C "$GITHUB_WORKSPACE/$checkout" status --porcelain --untracked-files=all)" ]]; then
    echo "$checkout checkout is not clean" >&2
    exit 1
  fi
done
