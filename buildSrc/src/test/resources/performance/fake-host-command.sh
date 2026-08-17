#!/bin/sh
set -eu

encode_argument() {
  printf '%s' "$1"
}

command_name=$(basename "$0")
{
  encode_argument "$command_name"
  for argument in "$@"; do
    printf '\t'
    encode_argument "$argument"
  done
  printf '\n'
} >>"${FAKE_HOST_LOG:?}"

case "$command_name" in
  git)
    case "${1:-}" in
      rev-parse)
        case "${2:-}" in
          --show-toplevel) printf '%s\n' "${FAKE_REPO_ROOT:?}" ;;
          HEAD) printf '%s\n' "${FAKE_GIT_SHA:?}" ;;
          *) exit 91 ;;
        esac
        ;;
      status)
        printf '%s' "${FAKE_GIT_STATUS:-}"
        ;;
      *) exit 91 ;;
    esac
    ;;
  docker)
    if [ -n "${FAKE_DOCKER_FAIL_MATCH:-}" ]; then
      case " $* " in
        *" ${FAKE_DOCKER_FAIL_MATCH} "*) exit "${FAKE_DOCKER_FAIL_CODE:-1}" ;;
      esac
    fi
    case " $* " in
      *" image inspect "*)
        printf '%s\n' 'linux|arm64|v8|sha256:6c7425db05efdcf0ba40d989898857b093f14ceaf9684c9c31a072c159f4590e'
        ;;
      *" buildx imagetools inspect --raw "*)
        printf '%s\n' '{"config":{"digest": "sha256:ad6963934ee96838c09d99f3c4df6f991cd00ed70fa8a48f7045517d7ae8991c"}}'
        ;;
      *" volume create "*)
        for last_argument in "$@"; do :; done
        printf '%s\n' "$last_argument"
        ;;
    esac
    ;;
  java|gradle|sudo|dzdo|osascript)
    exit 97
    ;;
  *)
    exit 98
    ;;
esac
