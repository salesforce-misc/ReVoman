#!/bin/sh
set -eu

encode_argument() {
  printf '%s' "$1" | /usr/bin/od -An -v -tx1 | /usr/bin/tr -d ' \n'
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
        if [ "${FAKE_DOCKER_RAW_MANIFEST+x}" = x ]; then
          printf '%s' "$FAKE_DOCKER_RAW_MANIFEST"
        else
          raw_manifest=$(cat "${FAKE_DOCKER_RAW_MANIFEST_FILE:?}")
          if [ "${FAKE_DOCKER_CONFIG_DIGEST+x}" = x ]; then
            raw_manifest=$(printf '%s' "$raw_manifest" | sed \
              "s/sha256:ad6963934ee96838c09d99f3c4df6f991cd00ed70fa8a48f7045517d7ae8991c/$FAKE_DOCKER_CONFIG_DIGEST/")
          fi
          printf '%s' "$raw_manifest"
        fi
        ;;
      *" dev.revoman.performance.phase=finalizer-verification "*)
        printf '%s\n' verified > "${FAKE_REPO_ROOT:?}/.fake-finalizer-verified"
        ;;
      *" REVOMAN_STAGING_NAME="*)
        artifact_parent=''
        staging_name=''
        finalizer_command=''
        failure_code=''
        run_token=''
        for argument in "$@"; do
          case "$argument" in
            type=bind,src=*,dst=/artifacts)
              artifact_parent=${argument#type=bind,src=}
              artifact_parent=${artifact_parent%,dst=/artifacts}
              ;;
            REVOMAN_STAGING_NAME=*) staging_name=${argument#*=} ;;
            REVOMAN_FINALIZER_COMMAND=*) finalizer_command=${argument#*=} ;;
            REVOMAN_FAILURE_CODE=*) failure_code=${argument#*=} ;;
            REVOMAN_RUN_TOKEN=*) run_token=${argument#*=} ;;
          esac
        done
        if [ -n "${FAKE_DOCKER_FINALIZER_BIND_SOURCE:-}" ]; then
          artifact_parent=$FAKE_DOCKER_FINALIZER_BIND_SOURCE
        fi
        reservation="$artifact_parent/.$run_token.reservation"
        token_file="$reservation/token"
        staging="$artifact_parent/$staging_name"
        target="$artifact_parent/$run_token"
        [ -d "$artifact_parent" ] && [ ! -L "$artifact_parent" ] || exit 1
        [ -d "$reservation" ] && [ ! -L "$reservation" ] || exit 1
        [ -f "$token_file" ] && [ ! -L "$token_file" ] || exit 1
        token_value=$(cat "$token_file")
        [ "$token_value" = "$run_token" ] || exit 1
        [ -f "${FAKE_REPO_ROOT:?}/.fake-finalizer-verified" ] || exit 1
        [ ! -e "$staging" ] && [ ! -L "$staging" ] || exit 1
        [ ! -e "$target" ] && [ ! -L "$target" ] || exit 1
        (umask 077 && /bin/mkdir "$staging") || exit 1
        [ "$finalizer_command" = finalize-diagnostic ] || [ "$finalizer_command" = finalize-campaign ] || exit 1
        /bin/mkdir "$staging/INVALID"
        printf '%s\n' "$failure_code" > "$staging/INVALID/runner-owned"
        case "${FAKE_DOCKER_FINALIZER_BOUNDARY:-}" in
          late-file)
            printf 'keep' > "$target" || exit 1
            ;;
          late-directory)
            /bin/mkdir "$target" || exit 1
            printf 'keep' > "$target/foreign.txt"
            ;;
          late-symlink)
            /bin/ln -s "$artifact_parent/$run_token-escape" "$target" || exit 1
            ;;
          pre-move-failure|move-failure) exit 1 ;;
        esac
        [ ! -e "$target" ] && [ ! -L "$target" ] || exit 1
        /bin/mv "$staging" "$target" || exit 1
        [ ! -e "$staging" ] && [ ! -L "$staging" ] || exit 1
        [ -d "$target" ] && [ ! -L "$target" ] || exit 1
        [ -d "$target/INVALID" ] && [ -f "$target/INVALID/runner-owned" ] || exit 1
        /bin/rm -f "$token_file" || exit 1
        /bin/rmdir "$reservation" || exit 1
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
        if [ -n "${FAKE_DOCKER_VOLUME_RELABEL_AT:-}" ] &&
          [ "$inspect_count" -ge "$FAKE_DOCKER_VOLUME_RELABEL_AT" ]; then
          labels=${FAKE_DOCKER_VOLUME_RELABEL_LABELS:-someone-else|stale-token}
        fi
        if [ "$inspect_count" -eq 1 ] && [ "${FAKE_DOCKER_VOLUME_INITIAL_LABELS+x}" = x ]; then
          labels=$FAKE_DOCKER_VOLUME_INITIAL_LABELS
        elif [ "$inspect_count" -gt 1 ] && [ "${FAKE_DOCKER_VOLUME_CLEANUP_LABELS+x}" = x ]; then
          labels=$FAKE_DOCKER_VOLUME_CLEANUP_LABELS
        fi
        case "$inspect_count" in
          1) [ "${FAKE_DOCKER_VOLUME_INSPECT_1_LABELS+x}" != x ] || labels=$FAKE_DOCKER_VOLUME_INSPECT_1_LABELS ;;
          2) [ "${FAKE_DOCKER_VOLUME_INSPECT_2_LABELS+x}" != x ] || labels=$FAKE_DOCKER_VOLUME_INSPECT_2_LABELS ;;
          3) [ "${FAKE_DOCKER_VOLUME_INSPECT_3_LABELS+x}" != x ] || labels=$FAKE_DOCKER_VOLUME_INSPECT_3_LABELS ;;
          4) [ "${FAKE_DOCKER_VOLUME_INSPECT_4_LABELS+x}" != x ] || labels=$FAKE_DOCKER_VOLUME_INSPECT_4_LABELS ;;
        esac
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
