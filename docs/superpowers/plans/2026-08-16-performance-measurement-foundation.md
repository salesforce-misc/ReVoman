# Performance Measurement Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the fail-open JMH setup with a reproducible, classpath-preserving, fail-closed
performance measurement system; establish a controlled Mac/Docker baseline and comparator; then use
it to test and measure the intentional breaking resource-ownership cleanup.

**Architecture:** A pure Kotlin `performance-runner` owns distribution validation, JMH execution,
evidence schemas, comparison math, campaign state, and atomic publication. A thin Bash 3.2 host
adapter owns only Docker/Mac acquisition and observations, while Gradle owns build/freeze assembly.
The pre-fix and candidate distributions share byte-identical harness, runner, runtime, profiles,
and dependencies; only the production jar and derived treatment provenance may differ.

**Tech Stack:** JDK 21, Kotlin 2.4.20-RC, Gradle 9.7.0, JMH 1.37 with
`me.champeau.jmh` 0.7.3, Kotest/JUnit 5, NetworkNT JSON Schema Validator 3.0.6, Bash 3.2,
Docker Desktop Linux/ARM64, GitHub Actions `ubuntu-24.04-arm`, JFR, and Log4j 3 beta.

**Approved design:**
[`docs/superpowers/specs/2026-08-16-performance-measurement-foundation-design.md`](../specs/2026-08-16-performance-measurement-foundation-design.md)

## Global Constraints

- Backward source and binary compatibility is **not required** for the resource-ownership cleanup.
  Remove the ambiguous public source-opening APIs outright; do not add deprecated aliases or an
  adapter layer.
- Do not optimize progress, environment/scope synchronization, polling, logging, report creation,
  or file sinks in this tranche. The only production change is the confirmed resource-ownership
  fix.
- JDK 21 is the minimum. For pre-adapter development on this Mac, invoke Gradle with
  `-Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn`;
  export that same directory as `JAVA_HOME` at the start of the implementation shell so the wrapper
  can launch, and do not invoke bare `java`, which may resolve to the corporate guidance wrapper.
- The supported performance commands must require no host-native Java, Gradle, compiler, package
  manager, password, `sudo`, `dzdo`, `osascript`, privileged container, Docker socket mount, home
  mount, secret, or external network during timed/scrubber/finalizer phases.
- The sole claim-bearing runtime is `m4max-docker-linux-arm64-v1` through Docker Desktop's explicit
  `desktop-linux` context. GitHub ARM output is structural or diagnostic only.
- The claim-bearing runtime image is
  `docker.io/library/eclipse-temurin@sha256:6c7425db05efdcf0ba40d989898857b093f14ceaf9684c9c31a072c159f4590e`.
  Verify it as `linux/arm64`, record its OCI config/JDK hashes, and fail if unavailable; never fall
  back to a mutable tag. Its required inventory includes `/usr/bin/mv` from GNU coreutils 9.4 for
  finalizer-owned no-clobber publication.
- Freeze these common measured/scrubber/sealer limits before the baseline: CPUs `0-3`, memory
  `6 GiB`, memory-swap `6 GiB`, PID limit `512`, substrate-declared non-root UID/GID, read-only
  root, `cap-drop=ALL`, `no-new-privileges`, and only declared tmpfs/volume write points. The sole
  exception is the networkless/no-bind volume initializer: container UID `0`, `cap-drop=ALL` plus
  only `CHOWN`, used before timing to transfer new named-volume directories to that non-root UID.
- All JSON schemas use Draft 2020-12, reject unknown properties, discriminate substrate and
  qualification variants, and are themselves in the protocol hash.
- JSON plus Markdown are authoritative. DuckDB, dashboards, result services, automatic baseline
  promotion, and numeric CI gates are not part of V1.
- A valid evidence directory is immutable and atomically published. Invalid output never exits
  zero. If a writable artifact root cannot be reserved, emit sanitized stderr and the prescribed
  nonzero exit without pretending an artifact exists.
- The pre-fix baseline is frozen only after Tasks 1-12 are committed and green. After that freeze,
  do not modify `buildSrc/**/performance*`, `scripts/performance/run`, `config/performance/**`,
  `src/jmh/**`, the Gradle wrapper, JMH/Kotlin/JDK versions, or benchmark/runtime dependencies. If
  one must change, discard every baseline artifact, commit the protocol revision, and restart at
  Task 13.
- Candidate freeze must use `--harness-from` the preserved baseline distribution. It must never
  regenerate benchmark metadata, runner code, schemas, profiles, runtime declarations, or
  non-production dependencies.
- Use latches, FIFOs, fake clocks, and injected process executors in tests; do not use arbitrary
  sleeps.
- Follow `STYLE.md`, preserve copyright headers, use `./gradlew spotlessApply`, and keep all
  existing unit/integration tests green.
- Run `./gradlew qodanaScan` through Docker Desktop before any authorized push. Do not push or
  dispatch a GitHub workflow without explicit user authorization.

## File and Module Map

| Area | Files | Responsibility |
|---|---|---|
| Runner entry | `buildSrc/performance-runner/src/main/kotlin/performance/cli/**`, `runner/**` | Parse frozen commands and return the stable exit contract without Gradle APIs |
| Canonical evidence | `performance/json/**`, `schema/**`, `model/**`, `hash/**` | Strict JSON, schemas, privacy-safe models, protocol/checksum identities |
| Distribution | `performance/distribution/**` | Prove immutable distribution/classpath/JDK identity before execution |
| Capture | `performance/process/**`, `capture/**`, `profile/**` | Launch JMH fail-closed, validate exact cells, create provisional observations and profiler summaries |
| Compare | `performance/compare/**` | Compatibility, deterministic bootstrap, classification, JSON/Markdown rendering |
| Campaign | `performance/campaign/**`, `finalize/**`, `publication/**` | A/A escalation, A1/A2/B ordering, two-phase sealing, recovery, atomic publication |
| Gradle freeze | `buildSrc/src/main/kotlin/performance/**`, `revoman.performance-conventions.gradle.kts` | Build benchmark/runner archives and freeze classpath-preserving distributions |
| Host adapter | `scripts/performance/run`, `config/performance/runtime/**`, `policies/**` | Docker acquisition, lock, Mac observations, watcher, cleanup/restoration, phase handshake |
| Benchmark | `src/jmh/kotlin/**`, `src/jmh/resources/performance/**`, `src/jmhTest/**` | Sandbox canary plus deterministic V3 real-wire cold/warm scenario |
| CI/security | `.github/workflows/{build,qodana,performance-campaign}.yml`, `qodana.yaml` | Structural ARM canary, manual sealed hosted canary, least privilege and immutable actions/images |
| Ownership fix | `FileUtils.kt`, `ReVoman.kt`, JSON/environment/V3 loaders and focused tests | Close library-owned sources; preserve caller-owned streams and cached ZipFS lifetime |
| Evidence | `docs/superpowers/benchmarks/**` | Reviewed captures, comparisons, campaign reports, and hotspot ranking; never raw JFR |

---

### Task 1: Add the Pure Kotlin Runner and Stable Exit Contract

**Files:**

- Modify: `buildSrc/settings.gradle.kts`
- Modify: `buildSrc/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `buildSrc/performance-runner/build.gradle.kts`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/cli/PerformanceRunnerMain.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/runner/RunnerCommand.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/runner/RunnerDependencies.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/runner/RunnerEngine.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/runner/RunnerExit.kt`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/runner/RunnerExitContractTest.kt`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/cli/PerformanceRunnerMainTest.kt`

**Interfaces:**

- Consumes: no prior implementation task.
- Produces:

```kotlin
enum class RunnerExit(val code: Int) {
    SUCCESS(0),
    INPUT_OR_PREFLIGHT_INVALID(2),
    MEASUREMENT_INVALID(3),
    INCOMPATIBLE(4),
    CALIBRATION_FAILED(5),
    POLICY_FAILED(6),
    POLICY_INCONCLUSIVE(7),
    INTERNAL_OR_PUBLICATION_FAILED(8),
}

data class RunnerOutcome(
    val exit: RunnerExit,
    val publishedArtifact: java.nio.file.Path?,
)

sealed interface RunnerCommand

class RunnerEngine(private val dependencies: RunnerDependencies) {
    fun execute(command: RunnerCommand): RunnerOutcome
}

internal fun runMain(args: List<String>, dependencies: RunnerDependencies): Int
```

- `main` is the only function allowed to call `exitProcess`; tests call `runMain`.
- The runner has no `gradleApi()`, TestKit, root-project, or application-library dependency.

- [ ] **Step 1: Add red exit-contract tests**

Create the two test files. The core table assertion is:

```kotlin
class RunnerExitContractTest : io.kotest.core.spec.style.FunSpec({
    test("stable process exit table") {
        RunnerExit.entries.associateWith(RunnerExit::code) shouldBe
            mapOf(
                RunnerExit.SUCCESS to 0,
                RunnerExit.INPUT_OR_PREFLIGHT_INVALID to 2,
                RunnerExit.MEASUREMENT_INVALID to 3,
                RunnerExit.INCOMPATIBLE to 4,
                RunnerExit.CALIBRATION_FAILED to 5,
                RunnerExit.POLICY_FAILED to 6,
                RunnerExit.POLICY_INCONCLUSIVE to 7,
                RunnerExit.INTERNAL_OR_PUBLICATION_FAILED to 8,
            )
    }

    test("invalid outcomes never exit zero") {
        RunnerExit.entries.filterNot { it == RunnerExit.SUCCESS }.map { it.code shouldNotBe 0 }
    }
})
```

`PerformanceRunnerMainTest` passes an invalid command and asserts exit `2`, sanitized stderr, and no
JVM termination.

- [ ] **Step 2: Run the focused tests and observe RED**

```bash
./gradlew --no-daemon \
  -Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn \
  -p buildSrc :performance-runner:test \
  --tests 'performance.runner.RunnerExitContractTest' \
  --tests 'performance.cli.PerformanceRunnerMainTest'
```

Expected: failure because the subproject and runner types do not exist.

- [ ] **Step 3: Wire the isolated subproject**

Add `include("performance-runner")` to `buildSrc/settings.gradle.kts`. Configure the subproject with
the Kotlin JVM and `application` plugins, JDK 21 toolchain, `mainClass` set to
`performance.cli.PerformanceRunnerMainKt`, Kotest/JUnit Platform for tests, and no Gradle API. Add
direct catalog entries for `com.networknt:json-schema-validator:3.0.6` and
`com.squareup.moshi:moshi:1.15.2`; the latter matches the currently resolved production Moshi.

Add `testImplementation(gradleTestKit())` plus Kotest to the parent `buildSrc` project now, because
later adapter/freeze tasks use TestKit. Add `implementation(project(":performance-runner"))` only to
the parent; never add the inverse dependency.

- [ ] **Step 4: Implement the minimal runner types**

Implement the exact interfaces above. In `RunnerCommand.kt`, define only the command names needed by
the frozen inner runner:

```kotlin
sealed interface RunnerCommand {
    data class ValidateDistribution(val arguments: List<String>) : RunnerCommand
    data class Capture(val arguments: List<String>) : RunnerCommand
    data class Compare(val arguments: List<String>) : RunnerCommand
    data class Campaign(val arguments: List<String>) : RunnerCommand
    data class ScrubProfiler(val arguments: List<String>) : RunnerCommand
    data class FinalizeDiagnostic(val arguments: List<String>) : RunnerCommand
    data class FinalizeCampaign(val arguments: List<String>) : RunnerCommand
    data class Recover(val arguments: List<String>) : RunnerCommand
}
```

At this checkpoint, unimplemented but syntactically valid commands return
`INPUT_OR_PREFLIGHT_INVALID` with the enumerated reason `COMMAND_NOT_AVAILABLE`; later tasks replace
one branch at a time. Unknown flags, duplicate flags, missing values, and raw absolute output paths
also return exit `2`.

- [ ] **Step 5: Run GREEN and prove no Gradle API leaked**

```bash
./gradlew --no-daemon \
  -Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn \
  -p buildSrc :performance-runner:test

./gradlew --no-daemon \
  -Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn \
  -p buildSrc :performance-runner:dependencies --configuration runtimeClasspath
```

Expected: tests pass; the runtime classpath contains Kotlin/JSON dependencies but no Gradle API,
TestKit, or root ReVoman artifact.

- [ ] **Step 6: Format and commit checkpoint 1**

```bash
./gradlew -Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn spotlessApply
git add buildSrc gradle/libs.versions.toml
git commit -m "build(perf): add pure Kotlin runner and exit contract"
```

---

### Task 2: Define Canonical JSON, Evidence Models, and Strict Schemas

**Files:**

- Create: `buildSrc/performance-runner/src/main/kotlin/performance/json/CanonicalJson.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/hash/Sha256.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/model/EvidenceIdentity.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/model/EvidenceStatus.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/model/HostDocuments.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/model/CaptureDocument.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/schema/EvidenceSchemaValidator.kt`
- Create resources:
  - `buildSrc/performance-runner/src/main/resources/performance/protocol/schemas/capture-v1.schema.json`
  - `capture-provisional-v1.schema.json`
  - `preflight-v1.schema.json`
  - `watcher-v1.schema.json`
  - `postflight-v1.schema.json`
  - `restoration-v1.schema.json`
  - `profiler-summary-v1.schema.json`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/json/CanonicalJsonTest.kt`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/schema/CaptureSchemaContractTest.kt`
- Test resources: `buildSrc/performance-runner/src/test/resources/performance/golden/capture/**`

**Interfaces:**

- Consumes: `RunnerExit` from Task 1.
- Produces:

```kotlin
@JvmInline value class Sha256 private constructor(val hex: String) {
    companion object {
        fun parse(value: String): Sha256
        fun digest(bytes: ByteArray): Sha256
    }
}

enum class EvidenceStatus { VALID, INVALID }
enum class EvidenceStrength { CANARY, DIAGNOSTIC, CANONICAL }

sealed interface QualificationEvidence {
    val policyHash: Sha256

    data class ControlledMacCampaign(
        override val policyHash: Sha256,
        val preflight: HostDocumentRef,
        val watcher: HostDocumentRef,
        val postflight: HostDocumentRef,
        val restoration: HostDocumentRef,
        val cleanupPassed: Boolean,
    ) : QualificationEvidence

    data class ControlledMacBoundedDiagnostic(
        override val policyHash: Sha256,
        val preflight: HostDocumentRef,
        val watcher: HostDocumentRef,
        val postflight: HostDocumentRef,
        val restoration: HostDocumentRef,
        val campaignFieldsInapplicableReason: String,
    ) : QualificationEvidence

    data class GithubHosted(
        override val policyHash: Sha256,
        val setup: HostDocumentRef,
        val cleanup: HostDocumentRef,
        val macFieldsInapplicableReason: String,
    ) : QualificationEvidence
}

object CanonicalJson {
    fun parseStrict(bytes: ByteArray): tools.jackson.databind.JsonNode
    fun encode(value: tools.jackson.databind.JsonNode): ByteArray
}

class EvidenceSchemaValidator {
    fun validate(schema: SchemaKind, canonicalJson: ByteArray): List<SchemaViolation>
}
```

`CaptureDocument` has typed top-level sections for identity, status/strength/reasons, four distinct
provenance roles, protocol hashes, ordered artifact/classpath hashes, runtime/substrate identity,
qualification, logging/profile identity, cells, and optional profiler-summary reference. The exact
required metadata list in the approved design's “Required metadata” section is normative; every
corresponding schema property is required or explicitly inapplicable through its discriminator.

- [ ] **Step 1: Write canonicalization and strict-schema tests**

The canonical test must assert exact bytes:

```kotlin
val input = CanonicalJson.parseStrict("""{"z":1.00,"a":{"y":2,"b":true}}""".encodeToByteArray())
CanonicalJson.encode(input).decodeToString() shouldBe
    """{"a":{"b":true,"y":2},"z":1.00}
"""
```

Schema tests cover a valid document and one mutation each for unknown property, missing property,
wrong SHA format, forbidden absolute path, forbidden hostname/username/IP field, missing
qualification discriminator field, illegal canonical strength on GitHub, and a profiler summary
that names a source path or thread.

- [ ] **Step 2: Run RED**

```bash
./gradlew -p buildSrc :performance-runner:test \
  --tests 'performance.json.CanonicalJsonTest' \
  --tests 'performance.schema.CaptureSchemaContractTest'
```

Expected: compilation failure for the missing JSON/model/schema types.

- [ ] **Step 3: Implement canonical JSON and SHA validation**

Configure Jackson to parse integers as `BigInteger` and decimals as `BigDecimal`, reject duplicate
keys/trailing tokens/non-standard numbers, recursively sort object keys by unsigned UTF-8 bytes,
preserve array order and decimal scale, write UTF-8 with no insignificant whitespace, and append
exactly one newline. `Sha256.parse` accepts only lowercase 64-hex.

- [ ] **Step 4: Add every strict schema and golden document**

Use NetworkNT Draft 2020-12 validation with `additionalProperties: false`. Use `oneOf` plus an exact
`kind` discriminator for `controlledMacCampaign`, `controlledMacBoundedDiagnostic`, and
`githubHosted`. Encode inapplicability as an enumerated reason, never JSON `null`. The privacy
schema rejects absolute paths, parent traversal, home/workspace spellings, usernames, hostnames,
IPs, and secret-shaped fields. It permits normalized relative artifact references only in their
explicit schema properties; the same sanitizer policy applies to logs.

- [ ] **Step 5: Run GREEN and byte-stability twice**

```bash
./gradlew -p buildSrc :performance-runner:test --tests 'performance.json.*' --tests 'performance.schema.*'
./gradlew -p buildSrc :performance-runner:test --tests 'performance.json.*' --tests 'performance.schema.*'
```

Expected: both runs pass and golden bytes/checksums are identical.

- [ ] **Step 6: Commit checkpoint 2**

```bash
./gradlew spotlessApply
git add buildSrc/performance-runner gradle/libs.versions.toml
git commit -m "feat(perf): define canonical performance evidence"
```

---

### Task 3: Pin the ARM Runtime and Add the Thin Host Adapter

**Files:**

- Create: `scripts/performance/run`
- Create: `config/performance/runtime/runtime-profile-v1.schema.json`
- Create: `config/performance/runtime/temurin-21-linux-arm64-v1.json`
- Create: `config/performance/runtime/m4max-docker-linux-arm64-v1.json`
- Create: `config/performance/runtime/github-hosted-arm64-v1.json`
- Create: `buildSrc/src/test/kotlin/performance/adapter/HostAdapterContractTest.kt`
- Create: `buildSrc/src/test/kotlin/performance/adapter/DockerRuntimeProfileTest.kt`
- Create: `buildSrc/src/test/kotlin/performance/adapter/ArtifactRootContractTest.kt`
- Create: `buildSrc/src/test/kotlin/performance/support/FakeHost.kt`
- Create: `buildSrc/src/test/resources/performance/fake-host-command.sh`

**Interfaces:**

- Consumes: Task 1 runner CLI.
- Produces the five supported host commands exactly: `freeze`, `canary`, `campaign`, `capture`, and
  `compare`. The optional profiler selector belongs only to direct warm `capture`.
- `scripts/performance/run` is one hashable Bash-3.2-compatible file. It defines sourceable
  functions and invokes `main "$@"` only when executed, allowing tests to replace command functions
  without a production test flag.

- [ ] **Step 1: Write failing adapter and artifact-root tests**

Tests source or invoke `/bin/bash scripts/performance/run` with a fake `PATH`; one packaged-CLI test
also invokes `./scripts/performance/run` directly and asserts the Git executable bit. Assert:

```text
absolute output                         -> exit 2, sanitized stderr, no artifact
output containing ..                    -> exit 2
symlink traversal                       -> exit 2
existing output                         -> exit 2, never overwrite
artifact root itself                    -> exit 2
initially unwritable parent              -> exit 2, sanitized stderr, no artifact promise
injected write/fsync/rename failure      -> exit 8 only after reservation succeeded
unknown or duplicate flag               -> exit 2
bare host java/gradle/sudo/dzdo          -> never invoked
```

Fake-Docker assertions require explicit `--context desktop-linux` on Mac; preparation/timing/
scrubbing/finalization use `--network none --pull=never`; timed/scrubber/finalizer runs add `--read-only`,
`--cap-drop ALL`, `--security-opt no-new-privileges`, CPUs `0-3`, `--memory 6g`,
`--memory-swap 6g`, `--pids-limit 512`, non-root user, declared tmpfs/volumes, and no home,
credential, secret, or Docker-socket mount. Timed/scrubber containers have no host-output mount;
the sealer has exactly the reserved artifact parent and no other writable host bind.

Add dirty-tree and adapter-provenance cases for all five public commands. Initial `freeze` has no
prior adapter to compare, but requires a clean full SHA; candidate `freeze --harness-from` and every
canary/campaign/capture/compare require byte equality with the supplied frozen adapter before any
Docker invocation. A mismatch or dirty relevant source is exit `2`, with sanitized stderr, no
artifact, and a zero-call Docker spy. Also prove argument/output-shape rejection performs no write
and no Docker call, and that no output reservation exists before the exact runtime plus frozen
runner/finalizer identity and finalizer tool inventory have been verified.

- [ ] **Step 2: Run RED**

```bash
./gradlew -p buildSrc :test --tests 'performance.adapter.*'
```

Expected: failure because the adapter and profiles are absent.

- [ ] **Step 3: Implement strict argument/output handling first**

Implement a table-driven parser with no `eval`. Normalize from repository root and accept only a
new descendant of `build/performance` or `docs/superpowers/benchmarks`. Leave the final target
absent. Apply this fail-closed order: validate arguments and output-path shape without writing or
invoking Docker; validate the frozen adapter provenance before Docker; verify the exact Docker
context, immutable runtime, frozen runner/finalizer identity, and finalizer tool inventory; only
then atomically reserve the output by creating one deterministic hidden sibling
`.<target-name>.reservation` with mode `0700`. The token records only the logical run token and is
held through final publication/verification. Before reservation failure, print only an enumerated
code and logical output token; never echo a raw path or publish an artifact. Initial parent/
unwritability failure is exit `2`; only a write/fsync/publication failure after token creation is
exit `8`.

The public capture synopsis is:

```text
capture --profile cold|warm --forks 10|20|40 --host-id ID --session-id ID \
        --sequence POSITIVE --distribution DIR [--diagnostic-profiler gc|jfr] --output DIR
```

Reject the profiler flag unless `profile=warm`; profiler captures are permanently diagnostic and
must carry a frozen named variant.

- [ ] **Step 4: Pin and probe the exact ARM child**

Run only this online identity checkpoint:

```bash
RUNTIME_REF='docker.io/library/eclipse-temurin@sha256:6c7425db05efdcf0ba40d989898857b093f14ceaf9684c9c31a072c159f4590e'
docker --context desktop-linux pull --platform linux/arm64 "$RUNTIME_REF"
docker --context desktop-linux image inspect "$RUNTIME_REF"
docker --context desktop-linux run --rm --pull=never --platform linux/arm64 \
  "$RUNTIME_REF" /bin/sh -lc 'set -eu; test "$(uname -m)" = aarch64; for tool in sh tar sha256sum mv; do command -v "$tool"; done; java -version; sha256sum "$(command -v java)"; /usr/bin/mv --version'
docker buildx imagetools inspect --raw "$RUNTIME_REF"
```

Expected: `aarch64`, Temurin 21, and all required tools. The approved observed identity is OCI
config `sha256:ad6963934ee96838c09d99f3c4df6f991cd00ed70fa8a48f7045517d7ae8991c`, JDK
`21.0.11+10-LTS`, Java executable `/opt/java/openjdk/bin/java` with SHA-256
`1cedc51a4102638f1f06077acb3611b88f3061f9c7d76bd0a0df7f8607a9367b`, and tools
`/usr/bin/sh`, `/usr/bin/tar` (GNU tar 1.35), `/usr/bin/sha256sum` (GNU coreutils 9.4), and
`/usr/bin/mv` (GNU coreutils 9.4).
Copy the observed identity into the canonical profile. The schema test rejects a tag, an index
digest, unresolved values, amd64, or a missing hash.

Before implementation, probe the exact publication command on the actual writable finalizer bind
under every frozen security constraint. For an absent destination,
`/usr/bin/mv -nT --no-copy -- SOURCE DEST` must publish exactly. For regular-file, directory, and
symlink destinations created between the precheck and move, the finalizer operation must be
nonzero, retain the staging source, and leave the destination and any symlink target unchanged,
with no nesting, overwrite, or escape. A true cross-filesystem move must likewise fail with the
source retained and no copy fallback. Stop and reopen the gate if the exact pinned environment
cannot demonstrate all of those properties.

- [ ] **Step 5: Implement phase-specific Docker construction**

Only image acquisition and freeze may use network. Use the already-present child digest with
`--pull=never` thereafter. A narrowly scoped untimed volume initializer runs as container UID `0`
with `--cap-drop ALL --cap-add CHOWN`, read-only root, no host bind/network/socket, creates only the
declared named-volume directories, and transfers them to the substrate profile's declared non-root
UID/GID. Preparation then copies frozen inputs and exits as that non-root identity. Timing mounts
only those volumes/tmpfs and writes provisional output to an operation volume. A profiler scrubber
mounts only that operation volume writable and never sees a host artifact path. Finalization mounts
the scrubbed operation volume read-only plus exactly one reserved host artifact parent writable,
using the invoking substrate's recorded non-root UID/GID. Before any finalizer write, verify the
reservation token from inside that mounted view; do not compare host and container inode/device
numbers because Docker Desktop translates them. Publish from a sibling staging directory with
`/usr/bin/mv -nT --no-copy -- SOURCE DEST`, then require that the source disappeared and the exact
destination appeared. Convert a no-clobber skip to nonzero finalizer failure; retain the source and
leave an existing file, directory, or symlink destination unchanged. Never nest, overwrite, follow
a symlink, or copy across filesystems. GitHub ARM uses its Docker Engine without adding the Mac
`desktop-linux` context.

Add live Linux fixture tests that initialize a fresh root-owned named volume, then prove timed and
finalizer identities can write their declared volume/bind locations without root, `sudo`, world-
writable permissions, or a host ownership mutation. The initializer is not a privileged container
and has no capability except `CHOWN`.

- [ ] **Step 6: Run adapter GREEN gates without stdin or privilege**

```bash
/bin/bash -n scripts/performance/run
chmod 0755 scripts/performance/run
test -x scripts/performance/run
SUDO_ASKPASS=/usr/bin/false ./gradlew -p buildSrc :test --tests 'performance.adapter.*' </dev/null
rg -n '(^|[[:space:]])(sudo|dzdo|osascript)([[:space:]]|$)' scripts/performance
```

Expected: tests pass and `rg` prints no matches.

- [ ] **Step 7: Commit checkpoint 3**

```bash
git add scripts/performance/run
test "$(git ls-files --stage scripts/performance/run | awk '{print $1}')" = 100755
git add scripts/performance config/performance/runtime buildSrc/src/test
git commit -m "feat(perf): pin the arm64 runtime and add the host adapter"
```

---

### Task 4: Validate Frozen, Classpath-Preserving Distributions

**Files:**

- Create: `buildSrc/performance-runner/src/main/kotlin/performance/distribution/DistributionLayout.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/distribution/DistributionManifest.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/distribution/DistributionValidator.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/distribution/EffectiveClasspath.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/distribution/JarValidator.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/distribution/VerifiedDistribution.kt`
- Create resources:
  - `buildSrc/performance-runner/src/main/resources/performance/protocol/schemas/distribution-classpath-v1.schema.json`
  - `distribution-provenance-v1.schema.json`
  - `distribution-protocol-v1.schema.json`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/distribution/DistributionValidatorTest.kt`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/distribution/MultiReleaseCollisionTest.kt`
- Test support: `buildSrc/performance-runner/src/test/kotlin/performance/distribution/DistributionFixture.kt`

**Interfaces:**

- Consumes: `Sha256`, canonical JSON, schemas, and exit contract from Tasks 1-2.
- Produces the only proof type accepted by later process-launch code:

```kotlin
data class DistributionValidationRequest(
    val root: java.nio.file.Path,
    val selectedJava: JavaRuntimeIdentity,
    val expectedProtocolHash: Sha256? = null,
)

sealed interface DistributionValidation {
    data class Valid(val distribution: VerifiedDistribution) : DistributionValidation
    data class Invalid(val problems: List<DistributionProblem>) : DistributionValidation
}

class DistributionValidator {
    fun validate(request: DistributionValidationRequest): DistributionValidation
}

data class VerifiedDistribution internal constructor(
    val root: java.nio.file.Path,
    val metadata: DistributionMetadata,
    val benchmarkClasspath: List<java.nio.file.Path>,
    val runnerClasspath: List<java.nio.file.Path>,
)
```

No public factory or test-only escape hatch may construct `VerifiedDistribution` without validation.

- [ ] **Step 1: Add red validator fixtures**

Build tiny jars in test temporary directories, not checked-in binaries. Cover one valid distribution
and one mutation each for:

- missing or wrong `distribution.sha256` entry;
- invalid jar and missing `META-INF/BenchmarkList` or `META-INF/CompilerHints`;
- test class, JUnit/Kotest/MockK/ByteBuddy test-only dependency, or unexpected benchmark;
- wildcard, absolute, repeated, missing, or unordered classpath entry;
- duplicate ordinary binary class;
- duplicate effective class visible only through `META-INF/versions/21`;
- legitimate `module-info.class` present in multiple jars;
- incorrect `Multi-Release` manifest semantics;
- selected JDK below 21 or different from the declared runtime identity;
- stale/nonempty staging output; and
- runner, adapter, schema, profile, policy, expected-cell, or protocol hash mismatch.

The multi-release test fixture must assert that `module-info.class` is exempt while a second
effective `example.Duplicate` is rejected for feature version 21.

- [ ] **Step 2: Run the focused tests and observe RED**

```bash
./gradlew -p buildSrc :performance-runner:test \
  --tests 'performance.distribution.DistributionValidatorTest' \
  --tests 'performance.distribution.MultiReleaseCollisionTest'
```

Expected: compilation failure because the distribution proof and validator do not exist.

- [ ] **Step 3: Implement layout and checksum validation**

Accept only the exact versioned layout in the approved design. Resolve every entry under the
distribution root without following a symlink out of it. Validate all three strict canonical
metadata documents against the Task 4 schemas, hash all
files except `metadata/distribution.sha256`, reject undeclared files, and require the checksum
manifest to be lexicographically ordered with one lowercase SHA-256 per relative path.

Validate the runner and benchmark classpaths independently. Preserve the declared ordering and
return those exact lists in `VerifiedDistribution`; never scan `lib/` to invent a classpath.

- [ ] **Step 4: Implement JVM-effective duplicate detection**

For every jar, inspect its own manifest. At the selected feature version, map a multi-release entry
to its effective binary class, ignore each jar's `module-info.class`, and reject any other effective
identity supplied more than once across the same classpath. Do not unzip, merge, or rewrite a jar.
Run the JDK's `jar --validate` equivalent through `JarFile` checks and report stable enumerated
problems without embedding host paths.

- [ ] **Step 5: Make validation GREEN and prove process launch is unreachable on invalid input**

```bash
./gradlew -p buildSrc :performance-runner:test --tests 'performance.distribution.*Test'
```

Add an assertion using a process spy that every invalid fixture returns before any child process is
requested. The valid fixture must preserve the exact benchmark and runner classpath order.

- [ ] **Step 6: Commit checkpoint 4**

```bash
./gradlew spotlessApply
git add buildSrc/performance-runner
git commit -m "feat(perf): validate frozen performance distributions"
```

---

### Task 5: Assemble and Freeze Distributions Without Flattening Dependencies

**Files:**

- Modify: `build.gradle.kts`
- Modify: `buildSrc/build.gradle.kts`
- Create: `buildSrc/src/main/kotlin/performance/PerformanceMeasurementPlugin.kt`
- Create: `buildSrc/src/main/kotlin/performance/PerformanceBenchmarkJarTask.kt`
- Create: `buildSrc/src/main/kotlin/performance/AssemblePerformanceDistributionTask.kt`
- Create: `buildSrc/src/main/kotlin/performance/GenerateProtocolManifestTask.kt`
- Create: `buildSrc/src/main/kotlin/performance/VerifyPerformanceDistributionTask.kt`
- Create: `buildSrc/src/main/kotlin/revoman.performance-conventions.gradle.kts`
- Modify: `scripts/performance/run`
- Test: `buildSrc/src/test/kotlin/performance/PerformanceMeasurementPluginTest.kt`
- Test: `buildSrc/src/test/kotlin/performance/DistributionFreezeContractTest.kt`
- Test fixtures: `buildSrc/src/test/resources/fixtures/performance/**`

**Interfaces:**

- Consumes: runtime/adapter from Task 3 and validation from Task 4.
- Produces private Gradle tasks `performanceBenchmarkJar`,
  `assemblePerformanceDistribution`, and `verifyPerformanceDistribution`; callers continue to use
  only `scripts/performance/run freeze`.
- Initial freeze accepts `--treatment-source`; candidate freeze additionally requires
  `--harness-from` and copies the frozen harness closure byte-for-byte.

- [ ] **Step 1: Capture the fail-open artifact as a red TestKit fixture**

Create a fixture benchmark whose trial setup throws. First assert the current flattened behavior:
test classes/test dependencies are packaged, the jar lacks valid multi-release handling, an empty
JSON table can accompany a successful outer invocation, and fallback provider signatures appear.
Then state the desired result: the supported canary/freeze path must return nonzero and no valid
capture.

- [ ] **Step 2: Add red assembly/freeze contract tests**

The TestKit matrix must require:

- `includeTests=false` is applied before JMH bytecode generation;
- the benchmark jar contains JMH classes plus generated metadata but no test output;
- application, benchmark, dependency, and runner jars remain separate and byte-identical to inputs;
- every classpath is explicit and ordered;
- the runner archive comes from `:performance-runner:installDist`;
- a dirty treatment or capture-runner tree fails;
- initial freeze records treatment, harness, and freezer roles separately;
- candidate freeze permits differences only in `app/revoman.jar` and the explicitly derived
  treatment/provenance/checksum fields; and
- direct `jmh` and `jmhJar` throw a migration `GradleException` instead of being skipped or
  succeeding.

- [ ] **Step 3: Run RED**

```bash
./gradlew -p buildSrc :test \
  --tests 'performance.PerformanceMeasurementPluginTest' \
  --tests 'performance.DistributionFreezeContractTest'
```

Expected: the existing JMH plugin path violates the packaging and failure-propagation assertions.

- [ ] **Step 4: Implement private classpath-preserving tasks**

Configure `jmh.includeTests = false` at plugin configuration time. Make
`performanceBenchmarkJar` consume only compiled JMH output and generated JMH classes/resources.
Copy production and resolved dependency jars unchanged into the distribution, accounting for
`kotlinx-collections-immutable` as `embedded:app/revoman.jar`. Generate classpath/provenance/protocol
metadata canonically, then invoke the Task 4 validator.

The protocol closure includes all implementation/schema sources, compiled runner and benchmark
bytes, adapter, profiles, runtime and qualification policies, expected cells, bootstrap vector,
fixture resources, Gradle wrapper, plugin/core/Kotlin/JDK identities, and the selected OCI child
digest. It excludes treatment production sources and the optional regression policy.

- [ ] **Step 5: Implement container-only freeze and baseline harness reuse**

`scripts/performance/run freeze` may use an online preparation container and a private
container-managed Gradle cache. It may not call host Gradle/Java or mount host home/credentials.
Publish the completed distribution only after runner validation.

For candidate freeze, mount the baseline distribution read-only, verify its protocol against the
clean current runner, copy its harness/runner/protocol/dependency bytes, replace only the production
jar, and compare an allowlisted structured diff. Any non-allowlisted difference is a protocol/
artifact validation failure and exits `2`; exit `4` remains reserved for comparison inputs. Never
rebuild both sides to make them match.

- [ ] **Step 6: Run GREEN on TestKit distributions and compile current JMH sources**

```bash
./gradlew -p buildSrc :test --tests 'performance.*Distribution*Test' \
  --tests 'performance.PerformanceMeasurementPluginTest'
./gradlew jmhClasses jmhRunBytecodeGenerator jmhCompileGeneratedClasses
```

Expected: all TestKit fixture tasks pass; `jar --validate` passes for each fixture jar; current JMH
sources compile; the old flattened tasks fail intentionally with migration guidance when invoked
directly. Do **not** run the root distribution tasks yet: profiles, vectors, campaign schemas,
qualification policy, and final V3 resources do not all exist until Task 11. Task 11 owns the first
real root assembly/validation gate.

- [ ] **Step 7: Commit checkpoint 5**

```bash
git add build.gradle.kts buildSrc scripts/performance/run
git commit -m "build(perf): assemble classpath-preserving JMH distributions"
```

---

### Task 6: Capture JMH Fail-Closed and Emit Provisional Evidence

**Files:**

- Create: `buildSrc/performance-runner/src/main/kotlin/performance/process/ProcessExecutor.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/process/JdkProcessExecutor.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/capture/CaptureProfile.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/capture/CaptureRunner.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/capture/JmhResultCanonicalizer.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/capture/JmhResultValidator.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/capture/ExpectedCells.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/capture/PrivacyFilter.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/capture/ProfilerSummary.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/capture/ProfilerScrubber.kt`
- Create: `config/performance/profiles/{canary,cold,warm}.json`
- Create: `config/performance/expected-cells.json`
- Create resources:
  - `buildSrc/performance-runner/src/main/resources/performance/protocol/schemas/capture-profile-family-v1.schema.json`
  - `expected-cells-v1.schema.json`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/capture/CaptureRunnerTest.kt`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/capture/JmhResultCanonicalizerTest.kt`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/capture/PrivacyFilterTest.kt`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/capture/ProfilerSummaryTest.kt`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/capture/ProfilerScrubberTest.kt`
- Test fixtures: `buildSrc/performance-runner/src/test/resources/performance/jmh/**`

**Interfaces:**

```kotlin
fun interface ProcessExecutor {
    fun execute(spec: ProcessSpec): ProcessResult
}

data class CaptureRequest(
    val distribution: VerifiedDistribution,
    val profile: CaptureProfile,
    val identity: CaptureIdentity,
    val provisionalRoot: java.nio.file.Path,
)

sealed interface CaptureOutcome {
    data class Provisional(val document: ProvisionalCaptureDocument) : CaptureOutcome
    data class Invalid(val reasons: List<CaptureFailure>) : CaptureOutcome
}

class CaptureRunner(private val processExecutor: ProcessExecutor) {
    fun capture(request: CaptureRequest): CaptureOutcome
}
```

`ProcessExecutor` is the only external-process seam. Capture can produce provisional material only;
it has no API capable of marking evidence canonical or claim-bearing.

- [ ] **Step 1: Add the full failing-result matrix**

With a fake process executor, assert invalid exit `3` for nonzero child exit, missing/empty/header-
only/malformed JSON, zero rows, duplicate/missing/extra rows, nonpositive or nonfinite primary fork
observation, fallback `SimpleLogger`, Log4j provider/status failure, Graal/Truffle packaging failure,
scenario invariant failure, and teardown failure. Assert invalid exit `2` for profile, identity,
protocol, or distribution mismatch before launch.

A valid fixture must yield exactly the declared cells and a provisional document, never final
checksums. Parsing may ingest nonfinite derived JMH fields only to discard them; canonical evidence
must remain strict JSON and retain exact finite primary observations plus the SHA-256 of raw input.

- [ ] **Step 2: Run RED**

```bash
./gradlew -p buildSrc :performance-runner:test --tests 'performance.capture.*Test'
```

Expected: compilation failures for process/capture types.

- [ ] **Step 3: Implement exact JMH invocation and validation**

Build the fork command from `VerifiedDistribution.benchmarkClasspath` and the selected Java binary.
Pass `-foe true`, JSON format, a new result path, frozen JVM/JMH flags, fork/warmup/measurement
counts, batch size, threads, mode, and unit from the profile. Reject any mismatch between invocation,
expected cells, and returned rows; do not accept an empty table because the child exited zero.

Stop timers/process accounting before runner logging. Sanitize paths, hostnames, usernames, IPs,
environment variables, and command lines from public evidence without modifying benchmark names,
params, modes, units, or measurement arrays.

- [ ] **Step 4: Add the bounded diagnostic profiler variants**

Permit `--diagnostic-profiler gc|jfr` only for direct `capture --profile warm`. Resolve it to the
frozen `gc` or `jfr` diagnostic variant embedded in `warm.json`. Permanently set evidence strength
to diagnostic and record the variant/profile/profiler hashes.

For GC, publish only the declared allocation/GC counters. For JFR, the timed runner fsyncs the raw
operation-volume recording and records its hash in the immutable provisional capture; it does not
delete or publish it. The separate frozen `ProfilerScrubber` derives a bounded schema-validated
summary with required duration, dropped-sample counts, event classes, and sample/allocation/lock/IO
aggregates and symbol names, but no host paths, thread names/dumps, commands, environment, or system
properties. It persists the summary and scrub intent, deletes raw input, and records completion as
specified in Task 9. Campaign and comparator code reject profiler-bearing captures before
arithmetic.

- [ ] **Step 5: Run GREEN and prove the provisional profiler handoff**

```bash
./gradlew -p buildSrc :performance-runner:test --tests 'performance.capture.*Test'
```

Expected: all failure cases are nonzero; valid ordinary and profiler fixtures are provisional; the
capture fixture proves raw bytes/hash remain available before scrub. The scrubber success fixture
then proves validated summary persistence and raw deletion. Summary golden tests require duration
and dropped-sample counts and reject missing or privacy-unsafe completeness data. Task 9 adds the
container handshake and crash-recovery matrix around that transaction.

- [ ] **Step 6: Commit checkpoint 6**

```bash
git add buildSrc/performance-runner config/performance/profiles config/performance/expected-cells.json
git commit -m "feat(perf): fail closed on JMH captures"
```

---

### Task 7: Add the Deterministic Comparator and JSON/Markdown Reports

**Files:**

- Create: `buildSrc/performance-runner/src/main/kotlin/performance/compare/CellIdentity.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/compare/BootstrapV1.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/compare/CaptureCompatibility.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/compare/CaptureComparator.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/compare/ComparisonRenderer.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/model/ComparisonDocument.kt`
- Create resources:
  - `buildSrc/performance-runner/src/main/resources/performance/protocol/schemas/comparison-v1.schema.json`
  - `calibration-provisional-v1.schema.json`
  - `regression-policy-v1.schema.json`
  - `buildSrc/performance-runner/src/main/resources/performance/protocol/test-vectors/bootstrap-v1.json`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/compare/BootstrapV1KnownAnswerTest.kt`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/compare/CaptureComparatorTest.kt`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/compare/ComparisonRendererGoldenTest.kt`

**Interfaces:**

```kotlin
data class ForkSamples(val measurements: List<Double>)

data class RatioEstimate(
    val pointRatio: Double,
    val gainPercent: Double,
    val lower95Ratio: Double,
    val upper95Ratio: Double,
)

object BootstrapV1 {
    fun estimate(
        baselineCaptureId: String,
        candidateCaptureId: String,
        cell: CellIdentity,
        baseline: List<ForkSamples>,
        candidate: List<ForkSamples>,
    ): RatioEstimate
}

class CaptureComparator {
    fun compare(request: ComparisonRequest): ComparisonComputation
}
```

Only checksum/schema-validated sealed bundles may reach `CaptureComparator`. Standalone rendering
is always diagnostic; there is no boolean or CLI flag that requests claim-bearing output. Task 7
produces deterministic report bytes in memory/staging; Task 9 wires their atomic public publication.

- [ ] **Step 1: Check in an independently reviewed known-answer vector**

Use these literal inputs and expected values; production code must never generate the expected
fixture during the test:

```text
baseline capture id: baseline-a1
candidate capture id: candidate-b
benchmark: com.salesforce.revoman.benchmark.RevUpV3WarmBenchmark.revUp
profile: warm
params: []
mode/unit/threads/batch/metric/direction: ss, ms/op, 1, 1, score, lower-is-better
cell byte count: 142
cell bytes hex:
7265766f6d616e2d63656c6c2d7631000000003b636f6d2e73616c6573666f7263652e7265766f6d616e2e62656e63686d61726b2e526576557056335761726d42656e63686d61726b2e7265765570000000047761726d00000000000000027373000000056d732f6f7000000001000000010000000573636f72650000000f6c6f7765722d69732d626574746572
seed hex: d52e488aa4efe5750f992d76743928ca5e46c07ad4b9467c510433bc762468d4
baseline fork medians: [11, 21, 31]
candidate fork medians: [9, 19, 29]
first replicate baseline indices: [2, 0, 2, 0, 0, 0]
first replicate candidate indices: [1, 1, 0, 1, 2, 2]
point ratio: 0.9047619047619048
gain percent: 9.523809523809524
20,000-replicate type-7 lower ratio: 0.2903225806451613
20,000-replicate type-7 upper ratio: 2.6363636363636362
```

Before implementing, reproduce the vector with a second small implementation outside production
packages and review both byte encodings. Store integer widths, unsigned big-endian encoding,
length-prefix rules, field order, PRNG transition/draw order, rejection sampling, resample sizes,
median rule, and type-7 quantile rule in the checked-in vector.

- [ ] **Step 2: Add red compatibility, boundary, and rendering tests**

Reject before arithmetic: same capture twice; wrong A1/A2/B session/sequence; profiler-bearing
capture; nonpositive/nonfinite sample; mismatched protocol/runner/adapter/JDK/runtime/profile/
qualification-policy/log/expected-cell/harness/classpath hashes; and unsealed or checksum-invalid
bundles. First verify each bundle's app/treatment hashes against its own manifest. Then apply
comparison-kind rules: A/A requires identical app and treatment hashes; candidate comparison
requires distinct app/treatment identities and permits exactly the frozen distribution contract's
production-jar and derived provenance/checksum deltas.

Test exact equality at `1.0`, calibration bounds, and regression thresholds. Test even-sample
medians. Render under at least two locales/timezones and require byte-identical JSON/Markdown.
Markdown must label `[L, U]` as a **candidate/baseline ratio interval**, not as a percentage.

- [ ] **Step 3: Run RED**

```bash
./gradlew -p buildSrc :performance-runner:test --tests 'performance.compare.*Test'
```

Expected: comparator/bootstrap types and golden reports are absent.

- [ ] **Step 4: Implement compatibility before statistics**

Validate schemas/checksums and exact compatibility as one phase. Return exit `4` with enumerated
reasons and no estimate object when incompatible. Treat only strictly positive finite primary
observations as eligible. Distinguish qualification policy from optional regression policy: the
former is an exact capture key; the latter is hashed report input and never changes capture
identity.

- [ ] **Step 5: Implement the frozen estimator and reports**

Compute one median per fork, the ratio `median(candidate forks) / median(baseline forks)`, and gain
`(1 - ratio) * 100`. Seed from the specified identities/canonical cell bytes, use the frozen PRNG
and independent fork resampling with replacement for 20,000 replicates, then type-7 2.5%/97.5%
ratio quantiles. Make endpoint equality explicit. Never average cells or emit significance language
when the interval crosses `1.0`.

Render strict `comparison.json` and concise `comparison.md` bytes. Include
capture IDs/hashes, comparator implementation/schema/vector hashes, compatibility, ratio, gain,
ratio interval, direction, calibration reference, policy hash/thresholds/outcome, and reason codes.
Do not expose them publicly or create `checksums.sha256` in this task; Task 9 atomically publishes
the rendered pair and its checksum through the shared evidence finalizer.

- [ ] **Step 6: Run GREEN twice for determinism**

```bash
LC_ALL=C TZ=UTC ./gradlew -p buildSrc :performance-runner:test --tests 'performance.compare.*Test'
LC_ALL=en_US.UTF-8 TZ=Asia/Kolkata ./gradlew -p buildSrc :performance-runner:test \
  --tests 'performance.compare.*Test'
```

Expected: both invocations produce identical golden bytes.

- [ ] **Step 7: Commit checkpoint 7**

```bash
git add buildSrc/performance-runner
git commit -m "feat(perf): add deterministic capture comparison"
```

---

### Task 8: Orchestrate A/A Escalation and A1/A2/B Session Order

**Files:**

- Create: `buildSrc/performance-runner/src/main/kotlin/performance/campaign/CampaignRunner.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/campaign/CampaignState.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/campaign/ProfileFamily.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/campaign/SessionOrder.kt`
- Create:
  `buildSrc/performance-runner/src/main/kotlin/performance/campaign/ProvisionalCalibrationEvaluator.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/campaign/RolePreconditioner.kt`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/campaign/CampaignRunnerTest.kt`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/campaign/SessionOrderTest.kt`

**Interfaces:**

```kotlin
data class CampaignRequest(
    val baseline: VerifiedDistribution,
    val candidate: VerifiedDistribution,
    val profileFamily: ProfileFamily,
    val session: SessionIdentity,
    val provisionalRoot: java.nio.file.Path,
    val regressionPolicy: RegressionPolicy?,
)

class CampaignRunner(
    private val captureRunner: CaptureRunner,
    private val calibrationEvaluator: ProvisionalCalibrationEvaluator,
    private val rolePreconditioner: RolePreconditioner,
    private val clock: java.time.Clock,
) {
    fun run(request: CampaignRequest): CampaignProvisionalOutcome
}

class ProvisionalCalibrationEvaluator {
    fun evaluate(
        baseline: ValidatedProvisionalCapture,
        candidate: ValidatedProvisionalCapture,
    ): ProvisionalCalibrationDecision
}

fun interface RolePreconditioner {
    fun prepare(role: CaptureRole, distribution: VerifiedDistribution): PreconditioningReceipt
}
```

The provisional evaluator and sealed `CaptureComparator` share only `BootstrapV1` and compatibility
primitives. The provisional proof type is internal, cannot be passed to `CaptureComparator`, and
cannot render or publish evidence.

- [ ] **Step 1: Add the red state-machine matrix**

Use fake capture/comparison ports and a mutable injected clock. Assert:

- a 10-fork calibration miss creates a fresh 20-fork A1/A2 pair;
- a 20-fork miss creates a fresh 40-fork A1/A2 pair;
- a 40-fork miss exits `5`, never launches B, and cannot claim;
- the first passing A/A immediately launches exactly one B;
- B compares against selected A2, not A1 or a discarded attempt;
- selected A1/A2/B sequence numbers are consecutive within one session;
- baseline/candidate roles are explicit and cannot be inferred from paths;
- a profile/session maximum-duration breach is permanently invalid even if the clock later moves;
- any failed or contaminated capture stops the state machine; and
- no campaign path accepts a diagnostic-profiler variant.

Before **every** A1, A2, and B—including discarded 10/20-fork attempts—the call ledger must show
`precondition(role) -> capture(role)`. The preconditioner reads the role's full distribution and
fixture manifests in canonical relative-path order, recomputes every byte count/hash, records the
receipt, and applies the same frozen role-settle duration through an injected sleeper. Missing,
reordered, unequal-policy, or stale receipts invalidate before the fork. Tests use a fake sleeper,
never wall-clock sleep.

- [ ] **Step 2: Run RED**

```bash
./gradlew -p buildSrc :performance-runner:test --tests 'performance.campaign.*Test'
```

Expected: campaign types are absent.

- [ ] **Step 3: Implement the frozen escalation ladder as one policy**

Represent `10 -> 20 -> 40` as one checked-in profile-family policy whose full ladder is included in
the protocol hash. An attempt ID includes profile family, fork count, and ordinal; changing fork
count does not mutate protocol identity or rebuild a distribution. Generate session ID and sequence
inside the frozen runner. Keep all attempts provisional in the private operation volume.

- [ ] **Step 4: Implement calibration and candidate transitions**

After each A1/A2 pair, run `ProvisionalCalibrationEvaluator` over validated-provisional proof types,
using the Task 7 pure estimator but never its sealed-bundle/publication interface. Only a passing
selected pair enables B. Add a compile/runtime negative test that neither provisional type can
reach `CaptureComparator` or emit public comparison evidence. Do not expose a public “calibration
passed” switch. Preserve discarded attempts as diagnostic campaign children, with explicit
nonselected status, but never use them in the candidate result.

- [ ] **Step 5: Run GREEN and inspect the call ledger**

```bash
./gradlew -p buildSrc :performance-runner:test --tests 'performance.campaign.*Test'
```

Expected: the fake ledger is exactly
`P(A1),A1,P(A2),A2[,P(A1),A1,P(A2),A2][,P(A1),A1,P(A2),A2][,P(B),B]` for each tested path, every
preconditioning receipt has canonical paths/bytes/hashes plus equal settle policy, and tests contain
no real sleep or wall-clock dependency.

- [ ] **Step 6: Commit checkpoint 8**

```bash
git add buildSrc/performance-runner
git commit -m "feat(perf): orchestrate calibrated campaigns"
```

---

### Task 9: Finalize, Publish, and Recover Evidence Atomically

**Files:**

- Create: `buildSrc/performance-runner/src/main/kotlin/performance/finalize/EvidenceFinalizer.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/finalize/FinalizationState.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/publication/AtomicPublisher.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/publication/ChecksumManifest.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/publication/StagingRecovery.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/model/CampaignDocument.kt`
- Create resource:
  `buildSrc/performance-runner/src/main/resources/performance/protocol/schemas/campaign-v1.schema.json`
- Modify: `scripts/performance/run`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/finalize/EvidenceFinalizerTest.kt`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/finalize/FinalizationCrashMatrixTest.kt`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/publication/AtomicPublisherTest.kt`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/publication/StagingRecoveryTest.kt`
- Test: `buildSrc/performance-runner/src/test/kotlin/performance/runner/RunnerTerminalMatrixTest.kt`
- Test: `buildSrc/src/test/kotlin/performance/adapter/FinalizerHandshakeContractTest.kt`

**Interfaces:**

```kotlin
class ProfilerScrubber {
    fun scrub(request: ProfilerScrubRequest): ProfilerScrubOutcome
}

class EvidenceFinalizer {
    fun finalizeDiagnostic(request: DiagnosticFinalizationRequest): FinalizationOutcome
    fun finalizeStandaloneComparison(
        request: StandaloneComparisonFinalizationRequest,
    ): FinalizationOutcome
    fun finalizeCampaign(request: CampaignFinalizationRequest): FinalizationOutcome
}

class StagingRecovery {
    fun recover(artifactRoot: java.nio.file.Path): List<RecoveryOutcome>
}

internal fun interface FinalizationCheckpoint {
    fun reached(transition: FinalizationTransition)
}
```

`FinalizationCheckpoint` exists only for deterministic crash injection. Tests assert public files,
not private transition implementation. Only `finalizeCampaign` may construct canonical strength or
a claim-bearing comparison.

#### Approved serial Task 9 submilestones

Task 9 executes as the semantics-preserving sequence **9A -> 9B -> 9C**. Each submilestone has one
exclusive writer, one commit, and an independent read-only review before the next writer starts. No
submilestone may weaken or defer the final Task 9 acceptance contract below.

- **9A — diagnostic capture sealing:** consume one verified non-profiler diagnostic provisional
  capture, its exact private operation tree, and verified bounded qualification; derive and seal the
  exact five-file private diagnostic bundle, then require `CaptureBundleVerifier` to accept it. 9A
  rejects canary/profiler/canonical routes and owns no reservation, public publication, campaign,
  recovery, adapter, Docker, or post-reservation `INVALID` behavior. Limit: 500 new production lines,
  750 new test lines, and only the approved sealer/test plus this plan and handoff.
- **9B — campaign computation:** consume only sealed paths, reverify them at the verifier-owned seam,
  validate the full attempt/receipt graph, recompute calibration and candidate comparisons, and
  derive one deterministic private campaign tree. Only this campaign computation may mint canonical
  strength; it owns no Docker, reservation, public publication, or recovery.
- **9C — publication and recovery integration:** consume immutable 9A/9B outputs and implement every
  remaining Task 9 behavior: canary/profiler sealing integration, scrub durability, reservation,
  atomic no-copy publication, sanitized post-reservation `INVALID` handling, staging recovery,
  adapter/CLI handshake, crash matrix, and terminal matrix. Publication must not recompute evidence
  or choose strength.

- [ ] **Step 1: Add red bounded-finalization tests**

For direct canary/capture, assert the timed container writes only into an operation volume. The host
adapter must finish watcher join, postflight, cleanup, and restoration before launching the frozen
profiler scrubber when required. The scrubber sees the operation volume writable but no host bind;
the sealing finalizer then sees it read-only and exactly one reserved artifact parent writable. It
validates all host/provisional/scrub inputs, materializes a sibling staging tree, fsyncs, and renames
once. A bounded bundle is always diagnostic—even on the controlled Mac.

Assert the exact capture files: `capture.json`, canonical `jmh-result.json`, optional
`profiler-summary.json`, `stdout.log`, `stderr.log`, and `checksums.sha256`. Fail if an undeclared
file or raw `.jfr` exists.

- [ ] **Step 2: Add the red campaign-DAG and crash matrix**

Inject failure immediately before and after every transition:

1. validate host documents, provisional observations, executing identities, ordering, cleanup,
   and immutable final status;
2. derive all final capture documents and child checksums;
3. recompute calibration comparisons, verify they agree with provisional B/no-B decisions, then
   derive candidate comparison from sealed A2/B;
4. write comparison children and `campaign.json` with only relative paths, IDs, and final hashes;
5. compute the recursive parent checksum, fsync files/directories, and perform one same-filesystem
   rename into the valid or `INVALID` namespace.

At every injected crash, no valid child may be visible independently. Recovery must reproduce
byte-identical final output or publish/quarantine a sanitized `INVALID` parent. A calibration
recomputation disagreement invalidates the entire campaign.

Add profiler crash checkpoints before/after provisional-summary temp write, atomic rename/fsync,
scrub-intent fsync, raw-JFR unlink/directory fsync, and completion-marker fsync. Recovery rules are:
derive again only while validated raw bytes remain; after durable intent, resume/verify deletion;
after raw deletion, require the existing intent and summary hashes to complete. If raw vanished
before a durable intent, quarantine `INVALID`—never synthesize a summary. The sealing finalizer must
see a completion marker, matching provisional/raw/summary hashes, and no `.jfr` anywhere.

- [ ] **Step 3: Run RED**

```bash
./gradlew -p buildSrc :performance-runner:test \
  --tests 'performance.finalize.*Test' \
  --tests 'performance.publication.*Test' \
  --tests 'performance.runner.RunnerTerminalMatrixTest'
./gradlew -p buildSrc :test --tests 'performance.adapter.FinalizerHandshakeContractTest'
```

Expected: finalizer/publication types and the host handshake are absent.

- [ ] **Step 4: Implement checksums and same-filesystem publication**

Hash every other regular file exactly once, using normalized UTF-8 relative paths sorted
lexicographically. Reject symlinks, special files, duplicate normalized paths, parent traversal, and
checksum self-inclusion. Write into `.<run-id>.staging` under the destination parent, fsync each
file plus staging/destination directories, and verify the deterministic sibling reservation token
from inside the finalizer's mounted view before the first write. Publish with
`/usr/bin/mv -nT --no-copy -- SOURCE DEST`; after a zero status require the source to be absent and
the exact final target to exist, otherwise return exit `8`. A destination collision must return
nonzero with staging retained and the destination unchanged. Verify the final target, then
remove/fsync the reservation token. Cross-filesystem rename is an exit-`8` negative case; never
fall back to a recursive copy.

- [ ] **Step 5: Implement two distinct sealing paths**

`ProfilerScrubber.scrub` implements the isolated writable-volume transaction above and never sees a host
destination. `finalizeDiagnostic` consumes one scrubbed bounded operation and derives at most
diagnostic strength. `finalizeStandaloneComparison` publishes the Task 7 deterministic report pair
plus its checksum and is always diagnostic. `finalizeCampaign` consumes the complete attempt graph
and derives strength from verified qualification; it never trusts a provisional comparison
document. All execute from the frozen
runner archive whose implementation, dependency, schema, renderer, vector, and policy hashes match
the distribution. Record that comparator provenance in every final report.

Publication failure always supersedes a prior measurement/policy status with exit `8`. Before the
frozen finalizer is verified, emit only sanitized stderr and no artifact. After finalizer
verification and writable output reservation, every failure uses the full sanitized `INVALID`
bundle.

Add one table-driven packaged-CLI test for the complete stable terminal matrix: valid operations and
unenforced direction exit `0`; input/preflight/freeze/protocol failure `2`; measurement failure `3`;
comparison incompatibility `4`; final calibration failure `5`; enforced `PASS`/`FAIL`/
`INCONCLUSIVE` exit `0/6/7`; and internal/finalization/publication failure `8`, which supersedes any
earlier status. Assert the exact VALID/INVALID/diagnostic/canonical artifact state for every row.

- [ ] **Step 6: Implement deterministic staging recovery**

At the next invocation under the same host/profile lock, inspect only operation-labelled staging
trees/volumes. Revalidate immutable provisional bytes and transition markers. Resume idempotently
when safe; otherwise privacy-filter and publish/quarantine `INVALID`. Never delete an unprovably
owned volume or infer validity from a directory name.

- [ ] **Step 7: Run GREEN and the publication-failure matrix**

```bash
./gradlew -p buildSrc :performance-runner:test \
  --tests 'performance.finalize.*Test' \
  --tests 'performance.publication.*Test' \
  --tests 'performance.runner.RunnerTerminalMatrixTest'
./gradlew -p buildSrc :test --tests 'performance.adapter.FinalizerHandshakeContractTest'
```

Expected: every crash point exposes either no public target or one complete schema/checksum-valid
target; no test accepts partial canonical evidence.

- [ ] **Step 8: Commit checkpoint 9**

```bash
git add buildSrc/performance-runner buildSrc/src/test scripts/performance/run
git commit -m "feat(perf): atomically finalize performance evidence"
```

---

### Task 10: Qualify and Reserve the Controlled Mac Without Privilege

**Files:**

- Create: `config/performance/policies/qualification-policy-v1.schema.json`
- Create: `config/performance/policies/m4max-docker-linux-arm64-v1.json`
- Create: `config/performance/policies/github-hosted-arm64-v1.json`
- Modify: `scripts/performance/run`
- Test: `buildSrc/src/test/kotlin/performance/adapter/OperationLockContractTest.kt`
- Test: `buildSrc/src/test/kotlin/performance/adapter/MacQualificationContractTest.kt`
- Test: `buildSrc/src/test/kotlin/performance/adapter/WatcherLifecycleContractTest.kt`
- Test: `buildSrc/src/test/kotlin/performance/adapter/ArtifactFailureContractTest.kt`

**Interfaces:**

- Consumes: Task 2 host-document schemas, Task 3 adapter, and Task 9 finalization handshake.
- Produces versioned policy-bound `preflight.json`, `watcher.json`, `postflight.json`, and
  `restoration.json`, plus operation-labelled Docker resources and one atomic host/profile lock.
- Policy values are data, but command entry points/parsers are fixed code; never execute policy
  strings with `eval`.

- [ ] **Step 1: Add red operation-lock tests**

Require the lock to be acquired before recovery, pull, preparation, or any container. Represent it
as one mode-`0700` atomic `mkdir` below the user's private `$TMPDIR`, keyed by host/profile. Record
PID, independently queried process-start identity, operation token, profile, and adapter hash.

Test live contention, PID reuse, corrupt record, dead-but-unprovable owner, dead provable owner with
matching live Docker object, and safely recoverable stale lock. Only the final safe case may recover.
Release occurs last—after final publication or quarantine—and every signal path preserves that
ordering.

- [ ] **Step 2: Add red qualification/watcher tests**

Use sourced Bash functions, fake commands, latches, and FIFOs. Assert:

- `desktop-linux` is selected explicitly and daemon API identity wins over unreliable
  `docker desktop status`;
- wrong platform, emulation, image/config/JDK hash, Docker/LinuxKit version, VM allocation, power,
  thermal, backup/update, user-idle, CPU, memory, swap/page, or environment state fails closed;
- no inherited `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, proxy, repository, credential, GitHub, or
  corporate variable reaches a container;
- every Docker object carries operation/profile labels;
- only exact operation-owned or policy-allowlisted user-owned interference may be stopped and later
  restored; unknown state is rejected, never broadly killed;
- the watcher starts before first timed capture and joins only after terminal timed-container exit;
- HID idle is qualified before `caffeinate -dims -w "$CONTROLLER_PID"`, `-u` is never used, and the
  controller terminates/joins that child on every signal/ordinary exit;
- a transient ordinary OS process is allowed, while a new container/VM/adapter, prohibited class,
  or sustained resource/power/thermal/backup/update event permanently invalidates the operation;
- cleanup/restoration failure invalidates finalization; and
- GitHub uses `githubHosted` fields and never invokes Mac commands.

- [ ] **Step 3: Run RED**

```bash
./gradlew -p buildSrc :test \
  --tests 'performance.adapter.OperationLockContractTest' \
  --tests 'performance.adapter.MacQualificationContractTest' \
  --tests 'performance.adapter.WatcherLifecycleContractTest' \
  --tests 'performance.adapter.ArtifactFailureContractTest'
```

Expected: the current adapter has none of the lifecycle contract.

- [ ] **Step 4: Check in the initial literal qualification policy**

Use only unprivileged `pmset`, `tmutil`, `memory_pressure`, `vm_stat`, `ioreg`, `ps`, `sysctl`,
`caffeinate`, Docker, and Git parsers. Freeze these initial controlled-Mac values:

- 12 preflight samples at five-second cadence;
- aggregate CPU idle median at least 90% and no sample below 80%;
- user idle at least 300 seconds;
- `memory_pressure` normal, no preflight swap-in/page-out delta, and AC power; `pmset -g therm`
  must either report all three current Apple-Silicon “no warning/status recorded” states or, on its
  numeric format, `CPU_Speed_Limit=100`, `Scheduler_Limit=100`, and zero speed-limited processes;
- 60-second settle after preparation and before the first A1;
- after the Task 8 canonical cache read, the same 10-second role-settle before every A1, A2, and B;
- watcher five-second cadence; permanent CPU breach after three consecutive samples below 70%
  idle, or immediately for non-normal memory pressure, swap/page-out growth, non-AC power, thermal
  degradation, backup/update activity, or an unknown container/VM/adapter;
- ordinary processes invalidate only when they match the checked-in prohibited class or exceed the
  checked-in sustained CPU/memory rule for three samples;
- two-hour timed campaign, 15-minute preparation/finalization limits, and bounded 10-minute direct
  canary/capture; and
- an initially empty automatic process-stop allowlist. Operation-labelled stale containers may be
  stopped; every other conflict fails immediately with its sanitized identity class. Add an exact
  user-owned process to the stop/restore allowlist only after a live dry run identifies it and a
  dedicated test proves identity matching and restoration.

These numbers are one versioned hypothesis, not dynamically tuned defaults. If the bounded dry run
shows they are infeasible, change the policy/tests in a separate commit **before** Task 13, discard
all outputs from the prior version, and do not loosen a threshold after seeing candidate data.

- [ ] **Step 5: Implement trap-driven acquisition and qualification**

Order every operation as: validate/reserve output -> acquire lock -> recover owned staging ->
online pull/identity -> offline preparation exits -> quiet preflight/settle -> start watcher -> timed
runner -> stop/join watcher -> postflight -> cleanup -> allowlisted restoration -> finalizer or
quarantine -> release lock. `INT`, `TERM`, `HUP`, and ordinary exit use the same state machine.

Qualify the 300-second HID idle requirement **before** starting `caffeinate`. Then launch
`caffeinate -dims -w "$CONTROLLER_PID"` as a controller-owned child; explicitly prohibit
`-u`, which synthesizes user activity. Signal/ordinary-exit tests require it to terminate and join
before postflight/restoration/finalization. If any future requirement needs elevation, print one
exact human command and exit `2`; never prompt, sleep waiting for input, or call `sudo`/`dzdo`.

- [ ] **Step 6: Run synthetic GREEN and privilege scans**

```bash
/bin/bash -n scripts/performance/run
SUDO_ASKPASS=/usr/bin/false ./gradlew -p buildSrc :test --tests 'performance.adapter.*' </dev/null
rg -n '(^|[[:space:]])(sudo|dzdo|osascript)([[:space:]]|$)' scripts/performance
```

Expected: tests pass and `rg` has no matches.

- [ ] **Step 7: Commit checkpoint 10 before any clean-tree-guarded live run**

```bash
git add config/performance/policies scripts/performance/run buildSrc/src/test
git commit -m "feat(perf): enforce deterministic Mac qualification"
test -z "$(git status --porcelain)"
```

The real live qualification needs the final V3 canary assets and therefore runs after Task 11 is
committed. Task 11 owns that bounded diagnostic plus real lock contention. If it changes policy,
return here, update tests/policy in a new pre-baseline commit, and rerun the Task 11 canary.

---

### Task 11: Add the Minimal V3 Real-Wire Workload and Structural Canary

**Files:**

- Create: `src/jmh/kotlin/com/salesforce/revoman/benchmark/scenario/RevUpV3Scenario.kt`
- Create: `src/jmh/kotlin/com/salesforce/revoman/benchmark/RevUpV3ColdBenchmark.kt`
- Create: `src/jmh/kotlin/com/salesforce/revoman/benchmark/RevUpV3WarmBenchmark.kt`
- Replace: `src/jmh/kotlin/com/salesforce/revoman/benchmark/SandboxBenchmark.kt`
  with `SandboxCanaryBenchmark.kt`
- Retain as diagnostic-only: existing component benchmarks under
  `src/jmh/kotlin/com/salesforce/revoman/benchmark/**`
- Create:
  - `src/jmh/resources/performance/revup-v3/.resources/definition.yaml`
  - `src/jmh/resources/performance/revup-v3/benchmark.request.yaml`
  - `src/jmh/resources/performance/revup-v3/benchmark.environment.yaml`
  - `src/jmh/resources/performance/log4j2-performance.xml`
- Create: `src/jmhTest/kotlin/com/salesforce/revoman/benchmark/scenario/RevUpV3ScenarioContractTest.kt`
- Create: `src/jmhTest/kotlin/com/salesforce/revoman/benchmark/SandboxCanaryContractTest.kt`
- Modify: `build.gradle.kts` to register `jmhTest`
- Modify: `config/performance/expected-cells.json`
- Modify: `config/performance/profiles/{canary,cold,warm}.json`

**Interfaces:**

```kotlin
class RevUpV3Scenario private constructor(/* owned server and immutable Kick */) : AutoCloseable {
    fun execute(): Rundown
    fun verifyInvocation()
    override fun close()

    companion object {
        fun start(): RevUpV3Scenario
    }
}
```

Cold/warm benchmark classes delegate setup, timed `execute`, invocation teardown verification, and
trial close only. Profile files—not drifting annotations—own fork/warmup/measurement counts.

- [ ] **Step 1: Write the real-wire scenario contract before JMH adapters**

Use the public `MockHttpServer`, `MockHttpHandler`, `ReVoman.revUp`, `Kick`, and `Rundown`. Require:

- exact `GET /benchmark`;
- a wire header marker derived from V3 environment `markerSeed=fixture-marker`;
- exact status `200`, `Content-Type: application/json; charset=utf-8`, and bytes `{"id":42}`;
- one passing, non-skipped `pm.test`;
- final environment ID `42`;
- exactly one request-ledger/handler-count delta and one successful report;
- no reuse of `Rundown.learnedLedger`;
- transactional setup that eagerly closes a partially acquired server;
- eager idempotent close on measured/verification failure; and
- verification/parse failure primary with close failure suppressed.

Use latches/futures for lifecycle tests; do not sleep. Include malformed V3/environment/response and
handler-mismatch cases.

- [ ] **Step 2: Run the scenario RED test**

```bash
./gradlew jmhTest --tests \
  'com.salesforce.revoman.benchmark.scenario.RevUpV3ScenarioContractTest'
```

Expected: `jmhTest` and the scenario do not exist.

- [ ] **Step 3: Implement the fixture, tree identity, and scenario**

Build a canonical fixture manifest with every regular file sorted by UTF-8 relative path and exact
`path`, `byteLength`, and `sha256`; reject symlinks and unexpected files. Package it with the
benchmark jar and recompute from packaged bytes before execution. Overlay only the ephemeral
`baseUrl`. Keep the server trial-scoped and the `Kick` immutable; bounded fixed profiles prevent
unlimited request-ledger growth.

- [ ] **Step 4: Add the sandbox and V3 JMH adapters**

The sandbox canary initializes the real Log4j provider/context, rejects fallback/status errors, and
evaluates a minimal `PmSandbox` expression. The V3 canary performs one full operation. Cold uses the
first operation in each fork; warm uses five untimed plus ten measured operations. All use
`SingleShotTime`, one operation/batch/thread, benchmark-only Log4j `OFF`, `RunLogSink.NoOp`, and no
banner.

Keep existing regex/environment/marshalling component benchmarks packaged and named in benchmark
metadata as diagnostic-only localization aids. They are never required canary/cold/warm cells and
cannot enter a campaign unless a future protocol explicitly promotes them.

- [ ] **Step 5: Run scenario/canary GREEN and distribution validation**

```bash
./gradlew jmhTest
./gradlew jmhClasses
```

Expected: all functional invariants pass; generated metadata exactly matches the declared packaged
benchmark set; required-cell profiles contain only sandbox/V3 canary and V3 cold/warm cells.

- [ ] **Step 6: Commit checkpoint 11 before the clean-tree-guarded canary**

```bash
git add src/jmh src/jmhTest build.gradle.kts config/performance
git commit -m "perf: add packaged V3 real-wire workload"
test -z "$(git status --porcelain)"
```

- [ ] **Step 7: Run the supported structural and live-qualification canary**

Use ignored output so the checkout stays clean:

```bash
TASK11_SHA="$(git rev-parse HEAD)"
./gradlew performanceBenchmarkJar assemblePerformanceDistribution verifyPerformanceDistribution
./scripts/performance/run freeze \
  --treatment-source . \
  --output "build/performance/task11-distribution-$TASK11_SHA"
./scripts/performance/run canary \
  --distribution "build/performance/task11-distribution-$TASK11_SHA" \
  --host-id m4max-docker-canary-v1 \
  --output "build/performance/task11-canary-$TASK11_SHA"
```

Expected: exit `0`, exactly declared rows, real provider/Graal/V3 checks pass, a sealed diagnostic
bundle exists, and no score threshold is evaluated. Inspect the real sanitized Mac preflight,
watcher, postflight, cleanup, and restoration documents; prove no native Java/Gradle access,
password, or privilege prompt occurred. While a latch-backed invocation holds the real
host/profile lock, a second invocation must exit `2` without touching the first operation.

If the bounded run demonstrates an infeasible parser/threshold, discard its output, change the
Task 10 policy and tests in a new **pre-baseline** commit, then rebuild and rerun this canary. Do not
freeze Task 13 until it passes from a clean commit.

---

### Task 12: Wire Sustainable CI, Harden Qodana, and Document Docker Desktop

**Files:**

- Modify: `.github/workflows/build.yml`
- Create: `.github/workflows/performance-campaign.yml`
- Modify: `.github/workflows/qodana.yml`
- Modify: `qodana.yaml`
- Modify: `DEVELOPMENT.md`
- Modify: `build.gradle.kts` Qodana guidance
- Create: `api/revoman-root.api`
- Create: `src/fdProbeTest/kotlin/.gitkeep`
- Create: `buildSrc/src/test/kotlin/performance/ci/WorkflowSecurityContractTest.kt`
- Create: `buildSrc/src/test/kotlin/performance/ci/QodanaSecurityContractTest.kt`
- Create: `buildSrc/src/test/kotlin/performance/DocumentationContractTest.kt`

**Interfaces:**

- Automatic PR/push lane: GitHub-hosted `ubuntu-24.04-arm` correctness plus structural canary;
  numeric timings are discarded.
- Explicit `workflow_dispatch` lane: two trusted freezes plus one sealed candidate canary, always
  diagnostic because host hardware is uncontrolled.
- Controlled-Mac claim lane remains an explicit local command; this repository does not register
  the everyday Mac as a self-hosted runner or install a polling daemon.

- [ ] **Step 1: Add red workflow-security tests**

Parse YAML structurally. Require immutable `uses:` SHAs, exact runner labels, event-specific least
privilege, `persist-credentials: false`, secretless PR/manual performance jobs, unconditional
sanitized artifact upload, and no benchmark math/threshold/Docker flags duplicated in YAML. Assert
the existing mutable refs, broad Qodana permissions, Colima text, and missing canary/manual workflow
fail.

Also require automatic `timeout-minutes: 90`, manual diagnostic `timeout-minutes: 240`, and a first
manual step that exits nonzero unless `GITHUB_REF` is exactly `refs/heads/master`. A skipped job is
not an acceptable guard.

- [ ] **Step 2: Pin all action and Qodana identities**

Use the reviewed action commits exactly:

```text
actions/checkout@11d5960a326750d5838078e36cf38b85af677262
actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3
gradle/actions/setup-gradle@ed408507eac070d1f99cc633dbcf757c94c7933a
actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02
github/codeql-action/upload-sarif@f3712979fa5f215279b101dd0a2e3bdfb4353324
JetBrains/qodana-action@4861e015da555e86a72b862892aba6c2b93e6891
```

Pin `qodana.yaml` to immutable index
`jetbrains/qodana-jvm-community@sha256:f1c5d3efe2f550409c4d95d266c5dc2025a8069d82c9516781eae72e7383b55d`
and verify the selected platform child before scanning: amd64
`sha256:6e03fbb417f0f268333ae04d97c4221bdb6bb666a30f0de8b4a34c521e797622`,
arm64 `sha256:a8ea6d25700098433060c62818b6172a3ebbf409573353cb7b35b76b07093870`.
If the scanner cannot consume that immutable index, fail and document the smallest reviewed
exception; never restore a mutable tag.

- [ ] **Step 3: Wire the automatic ARM structural lane**

In `build.yml`, use exact `ubuntu-24.04-arm`, workflow `permissions: contents: read`, credentialless
checkout, read-only Gradle cache for PRs, and an explicit 90-minute job timeout so build/freeze/
canary/finalization plus unconditional artifact upload fit without inheriting the current 30-minute
cap. Invoke explicitly:

```bash
./gradlew -p buildSrc test
./gradlew build
./scripts/performance/run freeze \
  --treatment-source . \
  --output build/performance/distribution
./scripts/performance/run canary \
  --distribution build/performance/distribution \
  --host-id github-hosted-arm64-canary-v1 \
  --output build/performance/canary
```

Upload all sanitized `build/performance` output with `if: always()`, `if-no-files-found: error`, and
seven-day retention. The canary has no numeric gate.

- [ ] **Step 4: Add the explicit hosted diagnostic-canary workflow**

Trigger only with `workflow_dispatch`. Give the job a 240-minute timeout. Its first step must test
`GITHUB_REF == refs/heads/master` and fail otherwise; then checkout/assert trusted master before
processing inputs. Inputs are lowercase distinct full `baseline_sha` and `candidate_sha`. Validate
both commits exist and are reachable
from repository-owned remote branches/tags, excluding pull-request refs. Checkout trusted master
with full history/no credentials, make detached treatment worktrees, and call only baseline freeze,
candidate freeze with `--harness-from`, and one candidate-distribution canary using
`github-hosted-arm64-canary-v1`. Use the existing `finalize-diagnostic` path; do not run A1/A2/B,
comparison, or campaign finalization.

Declare `permissions: contents: read`, no secrets/OIDC/schedule/PR target/artifact download/latest
lookup/promotion. Unset token/credential variables for build and timed child processes. Upload the
sanitized `build/performance` tree under `if: always()`, error if no artifact, retain 30 days, and
label all results diagnostic/nonclaiming.

- [ ] **Step 5: Split Qodana by trust boundary**

Set workflow-level `permissions: {}`. The PR job gets only `contents: read`, no `QODANA_TOKEN`, an
empty Qodana GitHub token, no annotations/comments/fix pushes/caches/cloud upload, and only a pinned
artifact upload. The trusted master-push job gets `contents: read` plus `security-events: write`,
passes an empty GitHub token, and exposes `QODANA_TOKEN` only to the pinned scan step. Both use exact
`ubuntu-24.04`, credentialless checkout, and never `pull_request_target`.

- [ ] **Step 6: Replace Colima guidance and document supported lanes**

Document `docker --context desktop-linux info`, the normal JDK-21 Gradle prerequisites for Qodana,
and the verified local command using
`-Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn`.
State that performance commands need only
Docker/Git/standard macOS utilities; no native JVM, second VM, password, or privilege. Explain
automatic structural ARM canary, optional explicit hosted diagnostic canary, and controlled-Mac-only
claim. Remove every Colima instruction, including the Qodana comment in `build.gradle.kts`.

- [ ] **Step 7: Activate Kotlin ABI validation and capture the pre-fix surface**

Opt in to `org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation` and enable the Kotlin
plugin's `abiValidation {}` block in `build.gradle.kts`; the root project name fixes the dump path as
`api/revoman-root.api`. Generate and review the pre-fix dump:

```bash
./gradlew \
  -Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn \
  updateKotlinAbi checkKotlinAbi
```

Require the dump to contain these four methods that Task 15 will intentionally remove:

```text
okio.BufferedSource bufferFile(java.lang.String)
okio.BufferedSource bufferFile(java.io.File)
okio.BufferedSource bufferInputStream(java.io.InputStream)
okio.BufferedSource bufferV3Definition(java.lang.String)
```

Also record the retained descriptors for both `readFileToString` overloads,
`readGzippedFileToString(String)`, and `readInputStreamToString(InputStream)`. Commit this pre-fix
dump before the baseline distribution; do not update it silently during the ownership change.

In the same pre-baseline build configuration, register an isolated `JvmTestSuite` named
`fdProbeTest`, using JUnit Jupiter plus the project-under-test dependency, `maxParallelForks = 1`,
and its own test worker. Wire it into `check`, keep it outside production/JMH jars and Kover's
production denominator, and preserve the empty source directory with `.gitkeep`. Task 14 adds its
first test without changing protocol-owned build logic after the freeze.

- [ ] **Step 8: Run GREEN security/documentation tests**

```bash
./gradlew -p buildSrc :test \
  --tests 'performance.ci.WorkflowSecurityContractTest' \
  --tests 'performance.ci.QodanaSecurityContractTest' \
  --tests 'performance.DocumentationContractTest'
```

Expected: workflows and documentation satisfy the frozen contracts; no mutable action/image ref or
Colima dependency remains in the tested files.

- [ ] **Step 9: Commit checkpoint 12 and freeze the protocol boundary**

```bash
git add .github DEVELOPMENT.md qodana.yaml build.gradle.kts buildSrc/src/test \
  src/fdProbeTest api/revoman-root.api
git commit -m "ci(perf): add ARM canary and harden measurement workflows"
git status --short
```

Expected: clean worktree. Record the commit as the immutable harness/protocol SHA. From this point
through Task 15, modifying any protocol-owned path listed in Global Constraints invalidates the
baseline and requires restarting at Task 13.

---

### Task 13: Freeze the Pre-Fix Baseline and Calibrate the Mac

**Files:**

- Acquire every result under ignored
  `build/performance/evidence-staging/baseline/<full-baseline-SHA>/`; after acquisition, copy the
  complete sealed bundles to `docs/superpowers/benchmarks/baseline/<full-baseline-SHA>/` and add a
  reviewed `baseline-receipt.json`, derived `baseline-receipt.md`, and recursive
  `checksums.sha256`.
- Preserve locally, but do not commit, the validated distribution under:
  `build/performance/distributions/<full-baseline-SHA>/`
- No production, benchmark, runner, adapter, profile, policy, build, or CI file may change.

**Checkpoint invariant:** The baseline treatment and immutable harness are the clean Task 12 commit.
The distribution directory and its checksum manifest are retained until Task 16 completes. A
missing or mutated distribution is not rebuilt after the ownership fix; restart from this task at
the original clean pre-fix commit.

- [ ] **Step 1: Prove the freeze boundary is clean and immutable**

```bash
test -z "$(git status --porcelain)"
BASELINE_SHA="$(git rev-parse HEAD)"
BASELINE_STAGE="build/performance/evidence-staging/baseline/$BASELINE_SHA"
test "$(printf '%s' "$BASELINE_SHA" | wc -c | tr -d ' ')" -eq 40
git cat-file -e "$BASELINE_SHA^{commit}"
```

Accumulate the receipt inputs under `$BASELINE_STAGE`; do not create anything under tracked `docs/`
while evidence commands remain. Record the full SHA, protocol hash, runtime child/config/JDK hashes,
qualification-policy hash, adapter hash, and source-tree-clean proof. Never use an abbreviated SHA
as distribution identity. The final `baseline-receipt.json` also records the distribution recursive
hash, each child relative path/hash, selected cold/warm fork count or final failure, and explicit
`diagnosticOnly=true`; Markdown is derived from it and the parent checksum covers both plus all
children.

- [ ] **Step 2: Freeze and validate the pre-fix distribution**

```bash
./scripts/performance/run freeze \
  --treatment-source . \
  --output "build/performance/distributions/$BASELINE_SHA"
```

Expected: exit `0`; distribution validator passes; its treatment, harness, freezer, and runner roles
all name the same clean full SHA; the runtime/platform/JDK hashes match the checked-in profile. Copy
the recursive distribution hash—not the large distribution—into the baseline receipt.

- [ ] **Step 3: Run Mac and hosted-shaped structural canary contracts locally**

```bash
./scripts/performance/run canary \
  --distribution "build/performance/distributions/$BASELINE_SHA" \
  --host-id m4max-docker-canary-v1 \
  --output "$BASELINE_STAGE/canary"
```

Expected: exit `0`, sealed diagnostic bundle, exact sandbox/V3 rows, no threshold. Recompute every
checksum and schema before continuing. A canary failure blocks production changes.

- [ ] **Step 4: Run autonomous lower-level A/A calibration for cold and warm**

For each profile, use one opaque session ID and monotonically increasing sequence across fork
counts. At 10 forks run baseline A1 and A2, then standalone calibration compare. If compare exits
`5`, repeat with fresh A1/A2 at 20, then 40. Stop at the first pass; a non-`5` error aborts. A 40-fork
miss records the family diagnostic-only and blocks a later V1 claim for that profile.

The exact command shape for each attempt is:

```bash
./scripts/performance/run capture \
  --profile "$PROFILE" --forks "$FORKS" \
  --host-id m4max-docker-linux-arm64-v1 \
  --session-id "$SESSION_ID" --sequence "$A1_SEQUENCE" \
  --distribution "build/performance/distributions/$BASELINE_SHA" \
  --output "$BASELINE_STAGE/$PROFILE/${FORKS}-a1"

./scripts/performance/run capture \
  --profile "$PROFILE" --forks "$FORKS" \
  --host-id m4max-docker-linux-arm64-v1 \
  --session-id "$SESSION_ID" --sequence "$A2_SEQUENCE" \
  --distribution "build/performance/distributions/$BASELINE_SHA" \
  --output "$BASELINE_STAGE/$PROFILE/${FORKS}-a2"

./scripts/performance/run compare \
  --kind calibration \
  --runner-distribution "build/performance/distributions/$BASELINE_SHA" \
  --baseline "$BASELINE_STAGE/$PROFILE/${FORKS}-a1" \
  --candidate "$BASELINE_STAGE/$PROFILE/${FORKS}-a2" \
  --output "$BASELINE_STAGE/$PROFILE/${FORKS}-calibration"
```

Use fork order `10,20,40` and sequences `1/2`, `3/4`, `5/6`; derive
`SESSION_ID=baseline-<full-SHA>-<profile>` and never include a username/hostname. Run the loop under
`caffeinate` through the adapter, with stdin closed; it must not ask for a choice or password.

- [ ] **Step 5: Review calibration validity before touching production**

For the selected cold and warm attempts, independently recompute schema/checksums and confirm the
interval contains `1.0`, point ratio is within `[0.95,1.05]`, and interval width is at most `0.10`.
Confirm the evidence is diagnostic, not claim-bearing. Record selected fork count or final 40-fork
failure in the receipt.

If runtime, profile, policy, or harness behavior must change, delete/quarantine all Task 13 outputs,
make the change in a protocol commit, and rerun Tasks 11-13. Do not inspect candidate results first.

- [ ] **Step 6: Promote and commit only complete sanitized baseline evidence**

After all commands finish, require `git status --porcelain` still empty. Revalidate every staged
schema/checksum, copy only complete sealed child bundles into a new
`docs/superpowers/benchmarks/baseline/$BASELINE_SHA` tree, generate the reviewed receipt/Markdown/
recursive checksum there, and validate it again. Never copy reservation tokens, staging markers,
operation metadata, or raw inputs.

```bash
git add "docs/superpowers/benchmarks/baseline/$BASELINE_SHA"
git diff --cached --check
git commit -m "perf: record pre-fix baseline calibration"
```

Keep `build/performance/distributions/$BASELINE_SHA` untracked and checksum-verified. The evidence
commit may change repository HEAD, but it must not change any protocol-owned path or the baseline
treatment/harness identity.

---

### Task 14: Add RED Resource-Ownership and Descriptor Tests

**Files:**

- Create: `src/test/kotlin/com/salesforce/revoman/input/ResourceOwnershipTestFixtures.kt`
- Create: `src/test/kotlin/com/salesforce/revoman/input/FileUtilsResourceOwnershipTest.kt`
- Create: `src/test/kotlin/com/salesforce/revoman/V2ResourceOwnershipTest.kt`
- Create:
  `src/test/kotlin/com/salesforce/revoman/internal/postman/template/EnvironmentResourceOwnershipTest.kt`
- Create:
  `src/test/kotlin/com/salesforce/revoman/input/json/JsonPojoResourceOwnershipTest.kt`
- Create:
  `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3ResourceOwnershipTest.kt`
- Modify:
  `src/test/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3LoaderJarTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/input/ClasspathResolverJarTest.kt`
- Create:
  `src/fdProbeTest/kotlin/com/salesforce/revoman/input/FileDescriptorRegressionTest.kt`

**Test seam:**

`ResourceOwnershipTestFixtures.kt` provides `CloseTrackingFileSystem : ForwardingFileSystem`, a
`ForwardingSource` wrapper tracking opens, close attempts, successful closes, and live sources,
configurable read/close failures, and `CloseTrackingInputStream`. Scoped MockK replacement of the
top-level resolver uses `mockkStatic("com.salesforce.revoman.input.ClasspathResolver")` and
unconditionally restores it. Use a JUnit resource lock if static replacement can overlap.

- [ ] **Step 1: Characterize value and borrowed-stream contracts**

In `FileUtilsResourceOwnershipTest`, require path reads to close after success and read failure;
when read and close both fail, the read failure stays primary and close is the sole suppressed
exception. Require the full gzip chain to close on valid and corrupt input. Require classpath and
absolute paths to behave identically. Require `readInputStreamToString` to leave the caller stream
open after success and read failure.

- [ ] **Step 2: Add V2/JSON/environment lifecycle tests**

- Through `ReVoman.revUp` and `Kick.configure`, use valid minimal collection
  `{"item":[],"auth":null}` and malformed collection via `templatePath`; owned sources close.
- Repeat through `templateInputStream`; borrowed streams remain open on success/failure.
- Through `Environment.mergeEnvs`, cover V2 JSON path success/malformed, V3 YAML path
  success/malformed, and stream success/malformed.
- Through `jsonFileToPojo`, cover valid/malformed resolver data and parser-plus-close failure
  suppression.

- [ ] **Step 3: Add V3 and cached-ZipFS lifecycle tests**

Call injectable `V3Loader.load(Path, FileSystem)` with the tracking filesystem. Verify root
definition, child definition, request YAML, and environment YAML close after success and each
malformed-input path; require `opens == closes` before backing filesystem teardown.

Extend `V3LoaderJarTest` to assert every `NioZipFileSystem.source` closes. Extend
`ClasspathResolverJarTest` to read repeatedly from one temporary JAR URI and assert each per-read
source closes, the cached ZipFS stays open, and no additional provider entry appears. Do not expose,
close, evict, or redesign private `jarFileSystems`; that process-lifetime registry is a distinct
owner.

- [ ] **Step 4: Add the isolated JDK descriptor regression probe**

Warm a small absolute file, obtain `UnixOperatingSystemMXBean`, record open descriptors, perform
exactly 200 `FileUtils.readFileToString` calls without `System.gc()`, and assert `delta < 20`.
macOS/Linux fail if the bean is unexpectedly unavailable; only genuinely unsupported OSes skip.

- [ ] **Step 5: Run RED and preserve the exact evidence**

```bash
./gradlew \
  -Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn \
  test \
  --tests 'com.salesforce.revoman.input.FileUtilsResourceOwnershipTest' \
  --tests 'com.salesforce.revoman.V2ResourceOwnershipTest' \
  --tests 'com.salesforce.revoman.internal.postman.template.EnvironmentResourceOwnershipTest' \
  --tests 'com.salesforce.revoman.input.json.JsonPojoResourceOwnershipTest' \
  --tests 'com.salesforce.revoman.internal.postman.template.v3.V3ResourceOwnershipTest' \
  --tests 'com.salesforce.revoman.internal.postman.template.v3.V3LoaderJarTest' \
  --tests 'com.salesforce.revoman.input.ClasspathResolverJarTest' \
  --rerun-tasks --no-build-cache --no-daemon --console=plain

./gradlew \
  -Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn \
  fdProbeTest --rerun-tasks --no-build-cache --no-daemon --console=plain
```

Expected before the fix: owned-source assertions include `opened=1, closed=0`; the runtime probe is
approximately `delta=200`. Borrowed-stream and process-lifetime ZipFS characterization assertions
must already pass. If they do not, fix the test seam—not production behavior—until each failure
matches the approved root cause.

- [ ] **Step 6: Commit the RED checkpoint without touching protocol or production code**

```bash
git add src/test src/fdProbeTest
git diff --cached --check
git commit -m "test(io): expose leaked path resources"
```

Record the failing focused commands in the commit message or implementation log. Do not run the
candidate freeze yet.

---

### Task 15: Implement the Breaking Scoped Resource-Ownership Module

**Files:**

- Create: `src/main/kotlin/com/salesforce/revoman/input/ResourceReads.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/input/FileUtils.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/ReVoman.kt`
- Modify:
  `src/main/kotlin/com/salesforce/revoman/internal/postman/template/Environment.kt`
- Modify: `src/main/kotlin/com/salesforce/revoman/input/json/JsonPojoUtils.kt`
- Modify:
  `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3EnvLoader.kt`
- Modify:
  `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Loader.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/ExeUtilsTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/input/FileUtilsTest.kt`
- Modify: `api/revoman-root.api`

**Interfaces:**

```kotlin
internal fun <T> readOwnedPath(
    filePath: String,
    action: okio.BufferedSource.() -> T,
): T

internal fun <T> readOwnedPath(
    path: okio.Path,
    fileSystem: okio.FileSystem,
    action: okio.BufferedSource.() -> T,
): T

internal fun <T> readBorrowedInput(
    inputStream: java.io.InputStream,
    action: okio.BufferedSource.() -> T,
): T
```

Public value-returning readers remain. Public `bufferFile(String)`, `bufferFile(File)`,
`bufferInputStream(InputStream)`, and `bufferV3Definition(String)` are deleted outright. Backward
source/binary compatibility is intentionally not required; do not deprecate, alias, or wrap them.

- [ ] **Step 1: Implement the deep lifecycle seam only**

`readOwnedPath(String)` resolves through existing `resolveClasspath`, preserving its
`FileNotFoundException`. The `(Path, FileSystem)` overload uses `FileSystem.read` or
`source(path).buffer().use`. Kotlin `use` must preserve the action/read failure and suppress a close
failure.

`readBorrowedInput` buffers a `ForwardingSource` whose `close()` is deliberately a no-op, and closes
only that wrapper. Document caller ownership. For gzip, use one dedicated scoped operation around
the outermost `gzip().buffer()` chain so the complete chain closes once; do not nest owned reads and
double-close the same source.

- [ ] **Step 2: Migrate every production call site in one pass**

- `FileUtils.kt`: retain value readers, close owned paths internally, and update ownership KDoc.
- `ReVoman.kt`: run Moshi V2 path parsing inside `readOwnedPath` and stream parsing inside
  `readBorrowedInput`.
- `Environment.kt`: apply the same owned/borrowed split for environment parsers.
- `JsonPojoUtils.kt`: parse inside the owned scope.
- `V3EnvLoader.kt`: read the environment inside the supplied filesystem scope.
- `V3Loader.kt`: scope both root/child definition and request reads through its supplied
  `FileSystem`.

Preserve `ClasspathResolver.kt` process-lifetime ZipFS caching exactly. Never close a wrapper around
`FileSystem.SYSTEM`, and never make callers coordinate a raw `Source` lifecycle.

- [ ] **Step 3: Remove obsolete internal test calls and deleted-API tests**

Change `ExeUtilsTest` to use `readFileToString` plus Moshi's string overload. Remove tests that exist
only to exercise `bufferV3Definition`; replace any remaining repository call with a value/scoped
operation. Use IntelliJ index references plus a text search to prove no call remains:

```bash
rg -n '\b(bufferFile|bufferInputStream|bufferV3Definition)\b' \
  src/main src/test src/integrationTest src/jmh src/jmhTest
```

Expected: no executable reference; historical design documents may still describe the intentional
removal.

- [ ] **Step 4: Run the focused ownership suite GREEN**

```bash
./gradlew \
  -Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn \
  test \
  --tests 'com.salesforce.revoman.input.*ResourceOwnershipTest' \
  --tests 'com.salesforce.revoman.V2ResourceOwnershipTest' \
  --tests 'com.salesforce.revoman.internal.postman.template.*ResourceOwnershipTest' \
  --tests 'com.salesforce.revoman.input.json.JsonPojoResourceOwnershipTest' \
  --tests 'com.salesforce.revoman.internal.postman.template.v3.V3LoaderJarTest' \
  --tests 'com.salesforce.revoman.input.ClasspathResolverJarTest' \
  --no-daemon --console=plain

./gradlew \
  -Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn \
  fdProbeTest --rerun-tasks --no-build-cache --no-daemon --console=plain
```

Expected: deterministic opens equal closes, borrowed streams remain open, cached ZipFS remains open,
and descriptor growth is below 20 without GC.

- [ ] **Step 5: Prove and accept exactly the intentional ABI break**

```bash
set +e
./gradlew \
  -Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn \
  checkKotlinAbi --no-daemon --console=plain
ABI_STATUS=$?
set -e
test "$ABI_STATUS" -ne 0

./gradlew \
  -Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn \
  updateKotlinAbi checkKotlinAbi --no-daemon --console=plain
git diff -- api/revoman-root.api
/usr/bin/javap -classpath build/classes/kotlin/main \
  -public com.salesforce.revoman.input.FileUtils
```

Review the diff and `javap`: exactly the four raw source-opening methods disappear; all four
value-returning descriptors remain unchanged. Any other public surface delta blocks the commit.

- [ ] **Step 6: Run correctness and benchmark-compilation gates**

```bash
./gradlew -p buildSrc test
./gradlew \
  -Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn \
  test fdProbeTest integrationTest checkKotlinAbi compileJmhKotlin jmhClasses build \
  --no-daemon --console=plain
```

Passing unit/integration tests without `compileJmhKotlin`/`jmhClasses` is not sufficient because the
frozen harness must still compile against the intentionally changed public API.

- [ ] **Step 7: Commit the GREEN ownership checkpoint**

```bash
./gradlew spotlessApply
git add src/main src/test src/fdProbeTest api/revoman-root.api
git diff --cached --check
git commit -m "fix(io): close library-owned path resources"
test -z "$(git status --porcelain)"
```

This commit is the candidate treatment SHA. Do not modify it or the harness before candidate freeze.

---

### Task 16: Freeze the Candidate, Run Claims, and Rank Measured Hotspots

**Files:**

- Acquire campaigns and profiler captures under ignored
  `build/performance/evidence-staging/candidate/<full-candidate-SHA>/**`, then preserve complete
  sanitized copies under:
  - `docs/superpowers/benchmarks/campaigns/<campaign-id>/**`
  - `docs/superpowers/benchmarks/diagnostics/<capture-id>/**`
  - `docs/superpowers/benchmarks/analyses/<full-candidate-SHA>-hotspots/hotspot-ranking.json`
  - `docs/superpowers/benchmarks/analyses/<full-candidate-SHA>-hotspots/hotspot-ranking.md`
  - `docs/superpowers/benchmarks/analyses/<full-candidate-SHA>-hotspots/checksums.sha256`
- Preserve locally, but do not commit, candidate distribution under:
  `build/performance/distributions/<full-candidate-SHA>/`
- No production or protocol-owned source changes.

- [ ] **Step 1: Freeze candidate strictly from the preserved baseline harness**

```bash
test -z "$(git status --porcelain)"
CANDIDATE_SHA="$(git rev-parse HEAD)"
CANDIDATE_STAGE="build/performance/evidence-staging/candidate/$CANDIDATE_SHA"
# Set BASELINE_SHA to the exact 40-hex value in the checksum-validated Task 13 baseline receipt.
test "$(printf '%s' "$BASELINE_SHA" | wc -c | tr -d ' ')" -eq 40
test -d "build/performance/distributions/$BASELINE_SHA"
./scripts/performance/run freeze \
  --treatment-source . \
  --harness-from "build/performance/distributions/$BASELINE_SHA" \
  --output "build/performance/distributions/$CANDIDATE_SHA"
```

Expected: runner/benchmark/protocol/dependency/adapter bytes and harness provenance are identical;
only `app/revoman.jar` and allowlisted treatment/freezer/size/hash/checksum fields differ. Validate
both distributions again immediately before timing.

- [ ] **Step 2: Run one explicit cold claim campaign**

```bash
./scripts/performance/run campaign \
  --profile cold \
  --host-id m4max-docker-linux-arm64-v1 \
  --baseline-distribution "build/performance/distributions/$BASELINE_SHA" \
  --candidate-distribution "build/performance/distributions/$CANDIDATE_SHA" \
  --output "$CANDIDATE_STAGE/campaigns/$CANDIDATE_SHA-cold"
```

The command autonomously performs fresh A/A escalation and, only after a pass, B. It must run A1,
A2, and B consecutively in one continuously allocated timed container, compare B against A2, and
finalize one atomic parent. With no regression policy, a valid `IMPROVEMENT`, `REGRESSION`, or
`INCONCLUSIVE` is evidence and exits `0`; do not turn it into a merge gate or significance claim.

- [ ] **Step 3: Run one explicit warm claim campaign**

```bash
./scripts/performance/run campaign \
  --profile warm \
  --host-id m4max-docker-linux-arm64-v1 \
  --baseline-distribution "build/performance/distributions/$BASELINE_SHA" \
  --candidate-distribution "build/performance/distributions/$CANDIDATE_SHA" \
  --output "$CANDIDATE_STAGE/campaigns/$CANDIDATE_SHA-warm"
```

If either family exhausts 40-fork calibration, preserve its diagnostic `INVALID`/calibration
evidence, do not run B, do not claim a gain, and continue correctness acceptance. Never change the
profile based on candidate observations.

- [ ] **Step 4: Validate and state the scoped result exactly**

Recompute schemas, recursive checksums, A1/A2/B ordering, distributions, calibration, bootstrap,
and report rendering with the frozen runner. For each valid cell, report candidate/baseline point
ratio, its explicitly labeled 95% conditional fork-resampling ratio interval, and point gain.
Classify by the unrounded interval. Scope every claim to
`m4max-docker-linux-arm64-v1`; do not extrapolate to native macOS, GitHub, x86, production, or
another day/session.

- [ ] **Step 5: Run frozen candidate-only GC and JFR diagnostics**

Take the exact selected fork count from the schema/checksum-validated warm campaign. If warm never
calibrated, use its final declared 40-fork diagnostic variant and label the ranking non-claiming.
Then run:

```bash
./scripts/performance/run capture \
  --profile warm --forks "$WARM_FORKS" --diagnostic-profiler gc \
  --host-id m4max-docker-linux-arm64-v1 \
  --session-id "diagnostic-$CANDIDATE_SHA-warm-gc" --sequence 1 \
  --distribution "build/performance/distributions/$CANDIDATE_SHA" \
  --output "$CANDIDATE_STAGE/diagnostics/$CANDIDATE_SHA-warm-gc"

./scripts/performance/run capture \
  --profile warm --forks "$WARM_FORKS" --diagnostic-profiler jfr \
  --host-id m4max-docker-linux-arm64-v1 \
  --session-id "diagnostic-$CANDIDATE_SHA-warm-jfr" --sequence 1 \
  --distribution "build/performance/distributions/$CANDIDATE_SHA" \
  --output "$CANDIDATE_STAGE/diagnostics/$CANDIDATE_SHA-warm-jfr"
```

Verify both bundles are permanently diagnostic/non-comparable, summaries are checksummed/privacy-
safe, capture metadata records summary and raw-input hashes, and duration/dropped-sample fields are
present and acceptable for ranking. Verify the scrub intent/completion hashes internally and that
no raw `.jfr` remains in public, staging, or operation-volume output after successful cleanup.

- [ ] **Step 6: Write the evidence-linked hotspot ranking**

First require the source checkout is still clean, revalidate every sealed campaign/diagnostic
bundle, and copy the complete sanitized campaign and GC/JFR capture bundles into the declared
`docs/superpowers/benchmarks/{campaigns,diagnostics}` paths. Preserve `capture.json`, canonical
`jmh-result.json`, `profiler-summary.json`, sanitized logs, and checksums; exclude raw JFR, scrub
markers, reservation tokens, and operation volumes.

Create strict JSON plus derived Markdown and checksum. Cite treatment SHA, cold/warm campaign IDs
and recursive hashes, GC/JFR capture IDs, profiler-summary hashes, raw-input hashes, durations, and
dropped-sample counts. A JFR summary with absent/invalid completeness fields cannot support a rank.
Rank only
hypotheses with evidence in the V3 workload:

- eager legacy Graal context: `PostmanSDK.kt:110`, `118-146`, `244-257`;
- V3 loading/YAML/hash: `V3Loader.kt:25-107`, `V3YamlReader.kt:16-27`,
  `V3EnvLoader.kt:17-23`;
- environment copying: `RegexReplacer.kt:136-145`, invoked at `ReVoman.kt:437`;
- sandbox snapshot/proxy/diff: `PmJsEval.kt:107-136`, `SandboxBridge.kt:213-228`, `302-317`;
- report/current-run allocation: `ReVoman.kt:415-535`; and
- eager no-op rendering: `RunLogContext.kt:48-50`, `86-109`.

A code-reading hypothesis receives no numeric rank without capture evidence. Explicitly label these
`UNMEASURED` and name their future workload: step scaling/progress (`ReVoman.kt:430`,
`PostmanSDK.kt:173-179`), polling/list retention/sleep (`Polling.kt:44,59,83,89`,
`PollingReport.kt:16`), file sink flushing/rewrite (`FileRunLogSink.kt:250-269,298-304`), console/
alternate sinks, and external-network behavior. Recommend at most one next optimization; do not
implement it in this tranche.

- [ ] **Step 7: Commit only sanitized evidence and analysis**

```bash
git add docs/superpowers/benchmarks/campaigns docs/superpowers/benchmarks/diagnostics \
  docs/superpowers/benchmarks/analyses
git diff --cached --check
git commit -m "perf: record ownership campaign and hotspot ranking"
```

Do not add distributions, raw JMH input, raw JFR, host paths, identifiers, or operation volumes.

---

### Task 17: Run Final Acceptance and Prepare Review

**Files:**

- Modify only if a verification failure demonstrates a defect in an in-scope implementation file.
  Any required protocol-owned change invalidates Task 13/16 measurements and requires a fresh
  baseline/candidate cycle.
- Create no new feature or optimization.

- [ ] **Step 1: Run formatting, focused build logic, and all correctness suites**

```bash
/bin/bash -n scripts/performance/run

./gradlew --no-daemon \
  -Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn \
  spotlessCheck

./gradlew --no-daemon \
  -Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn \
  -p buildSrc test

./gradlew --no-daemon \
  -Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn \
  test fdProbeTest integrationTest checkKotlinAbi jmhTest \
  compileJmhKotlin jmhClasses build
```

Expected: all pass on the committed candidate/evidence SHA. Capture the commands, SHA, and terminal
statuses in the review handoff.

- [ ] **Step 2: Re-run structural and failure-propagation gates**

```bash
./gradlew performanceBenchmarkJar assemblePerformanceDistribution verifyPerformanceDistribution

set +e
./gradlew jmh
JMH_STATUS=$?
./gradlew jmhJar
JMH_JAR_STATUS=$?
set -e
test "$JMH_STATUS" -ne 0
test "$JMH_JAR_STATUS" -ne 0

./scripts/performance/run canary \
  --distribution "build/performance/distributions/$CANDIDATE_SHA" \
  --host-id m4max-docker-canary-v1 \
  --output "build/performance/final-canary-$CANDIDATE_SHA"
```

Also run the intentionally failing TestKit benchmark and require nonzero outer status plus a full
sanitized `INVALID` diagnostic bundle. Confirm ordinary JMH component benchmarks remain diagnostic
and cannot appear as campaign cells.

- [ ] **Step 3: Re-run privacy, checksum, runtime, and privilege gates**

```bash
rg -n '(^|[[:space:]])(sudo|dzdo|osascript)([[:space:]]|$)' scripts/performance
find docs/superpowers/benchmarks build/performance -type f -name '*.jfr' -print
git diff --check
git status --short
```

Expected: privilege scan and JFR search print nothing; evidence schemas/checksums validate; exact
ARM child/config/JDK hashes match on Mac and hosted-profile fixtures; worktree is clean after any
final evidence commit.

- [ ] **Step 4: Run Qodana through Docker Desktop**

```bash
docker --context desktop-linux info
DOCKER_CONTEXT=desktop-linux ./gradlew \
  -Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn \
  qodanaScan
```

Expected: Qodana succeeds with the immutable image/action configuration and no Colima, second VM,
password, or privilege prompt. If corporate policy blocks only this gate, preserve the exact
non-secret failure evidence and report it; do not loosen workflow permissions or pins.

- [ ] **Step 5: Obtain independent code and evidence review**

Run `superpowers:requesting-code-review` against the Task 12 protocol commit and final SHA. Review
both standards and approved-spec conformance, with dedicated checks for distribution identity,
finalization crash safety, comparator known-answer vector, runtime/CI security, ownership primary/
suppressed failures, exact ABI removals, and performance-claim wording. Fix only verified defects;
repeat affected tests and invalidate/reacquire measurements when protocol or candidate bytes change.

- [ ] **Step 6: Leave GitHub publication as an explicit authorization gate**

Do not push, open a PR, or dispatch either workflow without a fresh explicit user request. After an
authorized push, require automatic `ubuntu-24.04-arm` build/canary success and artifact upload. Run
the manual hosted canary only when separately requested; discard its numeric timing and never treat
it as a comparison or Mac-campaign corroboration.

- [ ] **Step 7: Stop at the tranche boundary**

Report the ownership correctness result, exact ABI break, cold/warm comparison classifications,
evidence hashes, ranking, one optional next optimization, and every deferred hypothesis. Do not
start that optimization. The next change begins with a new approved design/plan and a new compatible
baseline.

---

## Spec-to-Plan Acceptance Map

| Approved acceptance area | Owning checkpoints |
|---|---|
| Fail-open JMH reproduction, packaging, provider/Graal, exact rows | Tasks 4-6, 11, 17 |
| V3 real-wire correctness and cold/warm profiles | Task 11 |
| Clean provenance, runtime/protocol/classpath compatibility | Tasks 3-6, 12-13, 16 |
| Strict evidence, privacy, checksum, two-phase atomic publication | Tasks 2, 6, 9-10 |
| Comparator, known-answer bootstrap, calibration, A1/A2/B | Tasks 7-9, 13, 16 |
| Breaking ownership correctness, borrowed streams, ZipFS, FD probe | Tasks 12, 14-15 |
| GC/JFR summaries and evidence-linked hotspot ranking | Tasks 6 and 16 |
| ARM canary, manual diagnostic, Qodana/public-repo security | Task 12 and Task 17 |
| No privilege/native-JVM/self-hosted-runner dependency | Tasks 3, 10, 12, 17 |
| Full unit/integration/JMH/API/build/Qodana gates | Task 17 |

## Deferred Work and Non-Goals

- No DuckDB, dashboard, database, result service, automatic baseline promotion, or history service.
- No numeric PR gate, scheduled campaign, persistent/self-hosted runner, local polling daemon,
  future VM, temporary x86 host, native-macOS benchmark profile, or amd64 emulation.
- No privileged cache dropping, host tuning, `powermetrics`, kernel extension, Docker resource
  reconfiguration, or attempt to disable corporate management software.
- No progress/environment/sandbox/polling/logging/report/file-sink optimization beyond measuring
  what the V3 workload actually exercises. Only the resource-ownership cleanup changes production.
- No general benchmark-fixture framework, `MockHttpServer` reset API, shared integration fixture
  source set, ZipFS eviction/shutdown registry, Shadow jar, repaired fat jar, or alternate storage
  engine.
- No compatibility aliases for removed raw-source APIs. Backward compatibility is explicitly not
  required.
- No claim that Mac Docker numbers generalize to GitHub, native macOS, x86, production traffic, or
  another session; hosted output is directional corroboration only.
