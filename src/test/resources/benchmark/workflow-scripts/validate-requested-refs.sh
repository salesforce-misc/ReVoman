set -euo pipefail
if [[ ! "$HARNESS_REF" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "harness_ref must be a full 40-character commit SHA" >&2
  exit 2
fi
if [[ ! "$CANDIDATE_REF" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "candidate_ref must be a full 40-character commit SHA" >&2
  exit 2
fi
