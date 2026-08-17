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
        image_id=${FAKE_DOCKER_IMAGE_ID:-sha256:6c7425db05efdcf0ba40d989898857b093f14ceaf9684c9c31a072c159f4590e}
        case " $* " in
          *'{{.Os}}|{{.Architecture}}|{{.Variant}}|{{.Id}}'*)
            printf 'linux|arm64|v8|%s\n' "$image_id"
            ;;
          *)
            descriptor_digest=${FAKE_DOCKER_DESCRIPTOR_DIGEST-sha256:6c7425db05efdcf0ba40d989898857b093f14ceaf9684c9c31a072c159f4590e}
            printf 'PLATFORM linux arm64 v8\n'
            printf 'ID %s\n' "$image_id"
            if [ -n "${FAKE_DOCKER_REPO_DIGEST:-}" ]; then
              printf 'REPO %s\n' "$FAKE_DOCKER_REPO_DIGEST"
            fi
            if [ -n "$descriptor_digest" ]; then
              printf 'DESCRIPTOR %s\n' "$descriptor_digest"
            fi
            ;;
        esac
        ;;
      *" buildx imagetools inspect --raw "*)
        config_digest=${FAKE_DOCKER_CONFIG_DIGEST:-sha256:ad6963934ee96838c09d99f3c4df6f991cd00ed70fa8a48f7045517d7ae8991c}
        printf '{"config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest": "%s"}}\n' "$config_digest"
        ;;
      *" volume create "*)
        create_count_file="${FAKE_REPO_ROOT:?}/.fake-docker-volume-create-count"
        create_count=0
        if [ -f "$create_count_file" ]; then
          IFS= read -r create_count < "$create_count_file"
        fi
        create_count=$((create_count + 1))
        printf '%s\n' "$create_count" > "$create_count_file"
        owner=''
        run_token=''
        for argument in "$@"; do
          case "$argument" in
            dev.revoman.performance.owner=*) owner=${argument#*=} ;;
            dev.revoman.performance.token=*) run_token=${argument#*=} ;;
          esac
        done
        if [ -n "${FAKE_DOCKER_VOLUME_CREATE_OUTPUT:-}" ]; then
          volume_name=$FAKE_DOCKER_VOLUME_CREATE_OUTPUT
        elif [ "${FAKE_DOCKER_VOLUME_COLLISION:-0}" = 1 ]; then
          volume_name='revoman-fake-volume-collision'
        else
          volume_name="revoman-fake-volume-$create_count"
        fi
        case "$volume_name" in
          ''|*[!A-Za-z0-9_.-]*) ;;
          *) printf '%s|%s\n' "$owner" "$run_token" > "${FAKE_REPO_ROOT:?}/.fake-docker-volume-labels-$volume_name" ;;
        esac
        printf '%s\n' "$volume_name"
        ;;
      *" volume inspect "*)
        for volume_name in "$@"; do :; done
        inspect_count_file="${FAKE_REPO_ROOT:?}/.fake-docker-volume-inspect-count-$volume_name"
        inspect_count=0
        if [ -f "$inspect_count_file" ]; then
          IFS= read -r inspect_count < "$inspect_count_file"
        fi
        inspect_count=$((inspect_count + 1))
        printf '%s\n' "$inspect_count" > "$inspect_count_file"
        labels='missing|missing'
        labels_file="${FAKE_REPO_ROOT:?}/.fake-docker-volume-labels-$volume_name"
        if [ -f "$labels_file" ]; then
          IFS= read -r labels < "$labels_file"
        fi
        if [ "$inspect_count" -eq 1 ] && [ "${FAKE_DOCKER_VOLUME_INITIAL_LABELS+x}" = x ]; then
          labels=$FAKE_DOCKER_VOLUME_INITIAL_LABELS
        elif [ "$inspect_count" -gt 1 ] && [ "${FAKE_DOCKER_VOLUME_CLEANUP_LABELS+x}" = x ]; then
          labels=$FAKE_DOCKER_VOLUME_CLEANUP_LABELS
        fi
        printf '%s\n' "$labels"
        ;;
      *" volume rm "*)
        :
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
