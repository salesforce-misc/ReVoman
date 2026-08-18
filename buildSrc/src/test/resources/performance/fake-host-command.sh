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
      *" pull --platform "*)
        :
        ;;
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
      *" dev.revoman.performance.phase=preparation "*)
        finalizer_input_index='none'
        for argument in "$@"; do
          case "$argument" in
            REVOMAN_FINALIZER_INPUT_INDEX=*) finalizer_input_index=${argument#*=} ;;
          esac
        done
        printf '%s\n' prepared > "${FAKE_REPO_ROOT:?}/.fake-preparation-complete"
        if [ "$finalizer_input_index" != none ]; then
          printf '%s\n' baseline > "$FAKE_REPO_ROOT/.fake-finalizer-distribution"
        fi
        ;;
      *" dev.revoman.performance.phase=fixture-finalizer-preparation "*)
        printf '%s\n' fixture > "${FAKE_REPO_ROOT:?}/.fake-finalizer-distribution"
        ;;
      *" dev.revoman.performance.phase=freeze-bootstrap "*)
        [ -f "${FAKE_REPO_ROOT:?}/.fake-preparation-complete" ] || exit 1
        printf '%s\n' initial > "$FAKE_REPO_ROOT/.fake-freeze-bootstrap-distribution"
        printf '%s\n' initial > "$FAKE_REPO_ROOT/.fake-provisional-distribution"
        printf '%s\n' initial > "$FAKE_REPO_ROOT/.fake-finalizer-distribution"
        ;;
      *" dev.revoman.performance.phase=finalizer-verification "*)
        [ -f "${FAKE_REPO_ROOT:?}/.fake-finalizer-distribution" ] || exit 1
        printf '%s\n' verified > "${FAKE_REPO_ROOT:?}/.fake-finalizer-verified"
        ;;
      *" dev.revoman.performance.phase=recovery "*)
        [ -f "${FAKE_REPO_ROOT:?}/.fake-finalizer-verified" ] || exit 1
        printf '%s\n' recovered > "${FAKE_REPO_ROOT:?}/.fake-recovery-complete"
        ;;
      *" dev.revoman.performance.phase=freeze "*)
        [ -f "${FAKE_REPO_ROOT:?}/.fake-preparation-complete" ] || exit 1
        [ -f "$FAKE_REPO_ROOT/.fake-finalizer-verified" ] || exit 1
        case " $* " in
          *" REVOMAN_HARNESS_FROM=/inputs/finalizer "*)
            [ -f "$FAKE_REPO_ROOT/.fake-finalizer-distribution" ] || exit 1
            printf '%s\n' candidate > "$FAKE_REPO_ROOT/.fake-provisional-distribution"
            printf '%s\n' candidate > "$FAKE_REPO_ROOT/.fake-freeze-validated-distribution"
            ;;
          *)
            [ -f "$FAKE_REPO_ROOT/.fake-freeze-bootstrap-distribution" ] || exit 1
            [ -f "$FAKE_REPO_ROOT/.fake-provisional-distribution" ] || exit 1
            printf '%s\n' initial > "$FAKE_REPO_ROOT/.fake-freeze-validated-distribution"
            ;;
        esac
        ;;
      *" REVOMAN_FINALIZER_COMMAND="*)
        artifact_parent=''
        failure_code=''
        run_token=''
        revoman_command=''
        finalizer_command=''
        for argument in "$@"; do
          case "$argument" in
            type=bind,src=*,dst=/artifacts)
              artifact_parent=${argument#type=bind,src=}
              artifact_parent=${artifact_parent%,dst=/artifacts}
              ;;
            REVOMAN_FAILURE_CODE=*) failure_code=${argument#*=} ;;
            REVOMAN_RUN_TOKEN=*) run_token=${argument#*=} ;;
            REVOMAN_COMMAND=*) revoman_command=${argument#*=} ;;
            REVOMAN_FINALIZER_COMMAND=*) finalizer_command=${argument#*=} ;;
          esac
        done
        if [ -n "${FAKE_DOCKER_FINALIZER_BIND_SOURCE:-}" ]; then
          artifact_parent=$FAKE_DOCKER_FINALIZER_BIND_SOURCE
        fi
        reservation="$artifact_parent/.$run_token.reservation"
        token_file="$reservation/token"
        target="$artifact_parent/$run_token"
        [ -d "$artifact_parent" ] && [ ! -L "$artifact_parent" ] || exit 8
        [ -d "$reservation" ] && [ ! -L "$reservation" ] || exit 8
        [ -f "$token_file" ] && [ ! -L "$token_file" ] || exit 8
        token_value=$(cat "$token_file")
        [ "$token_value" = "$run_token" ] || exit 8
        [ -f "${FAKE_REPO_ROOT:?}/.fake-finalizer-verified" ] || exit 8
        case "$revoman_command:$finalizer_command" in
          freeze:finalize-freeze|campaign:finalize-campaign|canary:finalize-diagnostic|capture:finalize-diagnostic|compare:finalize-standalone-comparison) ;;
          *) exit 8 ;;
        esac
        case " $* " in
          *" dev.revoman.performance.phase=finalizer "*) ;;
          *) exit 8 ;;
        esac
        fixture="$FAKE_PUBLICATION_FIXTURE_ROOT/$revoman_command"
        destination=$run_token
        terminal=0
        if [ "$failure_code" != NONE ]; then
          fixture="$FAKE_PUBLICATION_FIXTURE_ROOT/invalid"
          destination="INVALID-$run_token"
          terminal=2
        fi
        "$FAKE_JAVA_COMMAND" -cp "$FAKE_TEST_CLASSPATH" \
          performance.support.RunnerOwnedPublicationFixture \
          "$fixture" "$artifact_parent" "$run_token" "$destination" "$terminal" \
          "${FAKE_DOCKER_FINALIZER_BOUNDARY:-}" \
          >"$FAKE_REPO_ROOT/.fake-publication-helper.log" 2>&1 || exit 8
        case "${FAKE_DOCKER_FINALIZER_BOUNDARY:-}" in
          late-file|late-directory|late-symlink|pre-move-failure|move-failure) exit 8 ;;
        esac
        exit "$terminal"
        ;;
      *" dev.revoman.performance.phase=image-verification "* | \
      *" dev.revoman.performance.phase=volume-initializer "* | \
      *" dev.revoman.performance.phase=timed "* | \
      *" dev.revoman.performance.phase=scrubber "* | \
      *" dev.revoman.performance.phase=finalizer "*)
        :
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
      *) exit 98 ;;
    esac
    ;;
  java|gradle|sudo|dzdo|osascript)
    exit 97
    ;;
  *)
    exit 98
    ;;
esac
