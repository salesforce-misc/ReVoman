# Performance Measurement Foundation — Design

- Status: Approved — 2026-08-16
- Date: 2026-08-16
- Branch: `overfullstack/perf`
- Starting SHA: `009bc8f4c1fe9fb7d393036616a3c3b6cd787aca`

## Decision summary

ReVoman will first make performance measurement trustworthy, then fix one confirmed resource-
ownership defect, measure the result, and stop before starting another optimization.

The first tranche has five deliverables:

1. A classpath-preserving, fail-closed JMH distribution.
2. Deterministic cold and warm real-wire benchmarks of the public `ReVoman.revUp` interface,
   using a minimal V3 collection.
3. Versioned JSON evidence, a focused baseline/candidate comparator, and Markdown summaries.
4. A test-driven breaking cleanup of path-resource ownership that removes the confirmed file-
   descriptor leaks.
5. An A/A calibration, baseline/candidate comparison, and evidence-based ranking of the next
   hotspot among paths exercised by the V1 workload, with other hypotheses explicitly left
   unranked. No second optimization is included.

All benchmark execution uses one pinned `linux/arm64` OCI platform image. The long-lived M4 Mac
running Docker Desktop is the sole V1 controlled reference host. GitHub's standard
`ubuntu-24.04-arm` runner executes the same image for automatic structural canaries and optional
manual diagnostics, but hosted-runner timings never establish a performance claim. The temporary
`gopalaaksh-wsl3` machine and any future VM are optional corroboration only, not dependencies.

Backward compatibility is not required. Where the current public API encodes ambiguous ownership,
the implementation will replace it directly rather than add a compatibility layer.

DuckDB, dashboards, numeric timing gates in ordinary CI, and a general-purpose performance data
platform are intentionally deferred.

## Context and confirmed failures

### The current JMH result is not evidence

At the starting SHA, the standard `jmhJar` task flattens production and benchmark dependencies into
one 92 MiB jar. The jar:

- omits the `Multi-Release: true` manifest attribute required by Graal/Truffle;
- combines service metadata in a way that prevents Log4j from loading its real provider;
- includes test classes plus JUnit, Kotest, MockK, and ByteBuddy because `includeTests` defaults to
  `true`;
- fails `jar --validate` with thousands of diagnostics; and
- can execute a failing benchmark, emit no measurement row, and still make Gradle report
  `BUILD SUCCESSFUL` because JMH is fail-open by default.

`build.gradle.kts` currently configures only the JMH version and optional include pattern. A result
file containing only a header, or JSON `[]`, is therefore currently indistinguishable from a
successful Gradle invocation unless a human inspects the output.

Adding only `Multi-Release: true` does not repair the artifact. A bounded diagnostic repack moved
execution past the Truffle error but then exposed broken Log4j provider discovery and missing Graal
JavaScript options. The root cause is dependency flattening, not one missing manifest line.

### Path-based reads leak descriptors until GC

The current path-reading helpers return `BufferedSource` values without a binding ownership
contract, and internal callers consume them without `use`/`close`:

- `FileUtils.readFileToString` and `readGzippedFileToString`;
- V2 collection loading in `ReVoman.revUp`;
- V2 environment and JSON POJO loading;
- V3 environment loading; and
- V3 request and definition reads during directory walking.

A live JDK 21 probe performed 200 absolute-path reads in one JVM. Open descriptors rose from roughly
10 to roughly 210 and returned to the starting level only after an explicit GC. This is a
correctness and long-lived-JVM resource problem, regardless of whether closing improves latency.

### Other plausible bottlenecks remain outside this tranche

The audit also found:

- successful-step progress synchronization repeatedly copies prior progress, producing quadratic
  growth with step count;
- environment replacement creates multiple whole-map copies per executed step;
- each nonblank script snapshots, proxies, serializes, and diffs all scalar scopes;
- polling retains every response and cannot preempt a synchronous HTTP call or its final sleep;
- no-op logging still eagerly builds messages, while real sinks can render full HTTP messages even
  when their output capability does not require them; and
- the file sink flushes every write and can read/rewrite the complete output on close.

These findings are inputs to later prioritization, not authorization to change them in the first
tranche. Several earlier hot paths already have fixes at the starting SHA, including shared HTTP
clients, shared Graal engines, lazy sandbox resources, persistent environment snapshots, and a regex
fast path. They must not be reimplemented.

The minimal scripted V3 scenario exercises environment replacement, sandbox-scope synchronization,
report creation, and eager no-op logging, so profiling may rank those paths. It does not exercise
step-count scaling, polling, or file-sink behavior. Those remain `UNMEASURED` hypotheses until a
future design adds bounded varying-step, polling, and sink-specific diagnostics; V1 must not rank
them from code reading or from an unrelated one-request profile.

## Goals

1. Make an empty, failed, malformed, or packaging-corrupted benchmark run fail the supported outer
   command and its Gradle build/freeze phase when applicable.
2. Run JMH from an artifact that preserves the dependency semantics used by a normal production
   classpath.
3. Measure the public `ReVoman.revUp(Kick)` lifecycle over deterministic loopback HTTP.
4. Distinguish first-use-in-a-fresh-fork behavior from warmed in-process behavior.
5. Bind every result to the code, workload, artifact, runtime, host, settings, and logs that produced
   it.
6. Compare an explicitly selected baseline and candidate and report both gain and uncertainty.
7. Fix the confirmed path-resource leak with failing tests written first.
8. Leave behind a repeatable one-hotspot-at-a-time workflow for later optimizations.

## Non-goals

- JVM launcher or operating-system process-start time.
- Duration-driven throughput or request-rate claims.
- Percentile latency claims; the V1 protocol estimates a central end-to-end cost.
- Timing enforcement on shared or hosted CI runners.
- Native-macOS benchmark execution or a host-native JDK, Gradle, JMH, compiler, or package-manager
  dependency on the controlled Mac.
- An x86_64 canonical profile, cross-architecture arithmetic, or a routine OS/architecture matrix.
- A persistent self-hosted GitHub Actions runner on the controlled Mac.
- Dependence on `gopalaaksh-wsl3`, a future local VM, or paid GitHub larger runners.
- Repository-wide Actions governance or hardening the unrelated documentation-deployment workflow;
  V1 scopes CI hardening to build, Qodana, and performance workflows it changes.
- DuckDB as authoritative storage, a dashboard, or a benchmark-results service.
- External-network benchmarks.
- A full Cartesian workload matrix.
- V2-versus-V3 performance claims.
- Parsed-collection caching.
- Redesigning the process-lifetime ZipFS registry in `ClasspathResolver`.
- Changes to `StepReport.exeTimings` or treating it as release-gating evidence.
- Progress, scope synchronization, polling, logging, or file-sink optimization.
- Expanding the public mock-server API solely for benchmark convenience.
- Preserving source or binary compatibility for the resource-ownership API cleanup.

## Terms

- **Canary**: a short structural execution proving that packaging, provider discovery, benchmark
  discovery, result production, and failure propagation work. Its score is not performance evidence.
- **Cold operation**: the first `revUp` invocation in a fresh JMH fork. JVM launcher startup and
  JMH harness startup are outside the timed operation.
- **Warm operation**: a `revUp` invocation after a fixed number of untimed `revUp` warmups in the
  same fork, retaining process-wide Graal and HTTP-client state.
- **Capture**: one validated, immutable bundle of canonicalized JMH output, normalized metadata,
  sanitized logs, and checksums.
- **Cell**: one benchmark plus its exact parameter set, mode, unit, threads, and profile.
- **Campaign**: an explicit baseline/candidate comparison across one or more required cells.
- **Canonical capture**: a valid capture made from a clean, committed tree using a checked-in
  profile on the declared `m4max-docker-linux-arm64-v1` controlled reference host.
- **A/A comparison**: two independent captures of the same SHA and treatment, used only to calibrate
  noise and sample counts. It cannot substantiate a gain claim.
- **Runtime image**: the exact `linux/arm64` OCI platform manifest selected by digest, including its
  pinned Linux userspace and JDK. A mutable tag or multi-platform index alone is not an identity.

## Architecture

One internal performance-measurement module in `buildSrc` owns the full evidence boundary:

```text
M4 Docker Desktop -----------\
                              +--> pinned linux/arm64 runtime image
GitHub ubuntu-24.04-arm -----/
                                      |
freeze / canary -----------------------\
campaign -------------------------------+--> capture -> validate -> compare -> publish
capture / compare ----------------------/                              |
                                                                       v
                                                             JSON + Markdown evidence
```

The module spans the small host adapter at `scripts/performance/run` and the deep build logic in
`buildSrc`. Its supported host interface is five containerized subcommands. `campaign` is the
normal claim/diagnostic entry point; lower-level capture and compare remain available for focused
reproduction, testing, and re-rendering existing immutable evidence:

```text
./scripts/performance/run freeze \
  --treatment-source <clean-source-directory> \
  [--harness-from <frozen-baseline-distribution-directory>] \
  --output <new-distribution-directory>
./scripts/performance/run canary \
  --distribution <frozen-distribution-directory> \
  --host-id <opaque-host-id> \
  --output <directory>
./scripts/performance/run campaign \
  --profile <cold|warm> \
  --host-id <opaque-controlled-host-id> \
  --baseline-distribution <frozen-baseline-distribution-directory> \
  --candidate-distribution <frozen-candidate-distribution-directory> \
  [--regression-policy <policy-file>] \
  --output <campaign-directory>
./scripts/performance/run capture \
  --profile <cold|warm> --forks <10|20|40> \
  --host-id <opaque-host-id> --session-id <opaque-session-id> \
  --sequence <positive-integer> --distribution <frozen-distribution-directory> \
  [--diagnostic-profiler <gc|jfr>] \
  --output <directory>
./scripts/performance/run compare \
  --kind <calibration|candidate> \
  --runner-distribution <frozen-runner-distribution-directory> \
  --baseline <capture-directory> --candidate <capture-directory> \
  [--calibration <passing-A1-A2-comparison-directory>] \
  [--regression-policy <policy-file>] --output <comparison-directory>
```

Every `--output` is a normalized relative descendant of a checked-in artifact root
(`build/performance` for disposable work or `docs/superpowers/benchmarks` for reviewed evidence).
Absolute paths, `..`, symlink traversal, an existing target, and either root directory itself are
rejected before writing. Input paths may be elsewhere, but published metadata never records their
raw spelling.

`freeze` runs before a quiet campaign. It uses the current clean capture-runner checkout to build
the benchmark harness and a supplied clean treatment source to build the production artifact. The
first, pre-fix freeze establishes the immutable harness closure. The post-fix candidate freeze must
receive that baseline distribution via `--harness-from`, verify that its protocol/source hashes
still match the current runner, and reuse its benchmark jar, launcher, schemas, profiles,
runtime/qualification policies, and non-production classpath bytes rather than rebuilding them.
Among executable/runtime payload bytes, only `app/revoman.jar` may differ. Derived differences are
limited to the treatment/freezer fields in `metadata/provenance.json`, the app size/hash fields in
`metadata/classpath.json`, and the corresponding entries in `metadata/distribution.sha256`; harness
provenance remains identical. Any other changed path or field is incompatible. If the candidate no
longer runs against that harness or its runtime dependency closure changes, the comparison requires
a newly frozen baseline; the tool never silently rebuilds both sides.

The baseline freeze produces a classpath-preserving `performance-runner` archive alongside the JMH
artifact. Canary, capture, campaign, comparison, and finalization execute that frozen runner
directly—never newly compiled build logic. A candidate distribution reuses the baseline runner
bytes. Before container creation, every evidence command and candidate freeze requires the host
adapter to be in a clean tree and its script hash to match the frozen protocol. The initial baseline
freeze is the explicit bootstrap exception: with no prior manifest, it requires a clean committed
full SHA, records the reviewed adapter bytes/hash into the new distribution, and cannot itself emit
claim evidence. Inside the container, the runner verifies its jar, dependency,
schema, profile, expected-cell, runtime, and qualification-policy hashes before reading evidence or
performing arithmetic. Every output records these executing-code hashes. Dirty, missing, or
mismatched frozen runner code is `INVALID`, even if the evidence inputs would otherwise parse; a
host-adapter mismatch stops before container launch with sanitized stderr and no artifact.

The adapter selects and verifies the Docker context/image, mounts only declared inputs, and creates
a versioned sanitized host `preflight.json`. It contains no measurement thresholds, statistics, or
A1/A2/B decision logic. The frozen containerized runner generates IDs/sequences and writes
provisional staged inputs rather than public capture/comparison bundles. In a campaign it also
performs fresh A/A escalation at 10, 20, then 40 forks and uses an unpublished provisional evaluator
to refuse B until calibration passes.

After a timed campaign, canary, or capture container exits, the adapter records versioned
`postflight.json` and `restoration.json`. A profiler capture then invokes a separate internal
containerized scrubber from the same frozen runner and image. The scrubber has no host-evidence
mount and is the only post-timing phase allowed to mount the operation volume writable; it derives
and validates the provisional profiler summary, durably records the raw-input/summary hashes,
deletes the raw recording, and fsyncs that transition. The adapter then invokes the containerized
sealing finalizer with the operation volume read-only. For a campaign, the finalizer validates all
host documents, provisional
observations, ordering, cleanup, and claim-eligibility rules; seals final captures; recomputes
comparisons from those sealed captures; writes the campaign index; and only then computes the
recursive checksum and atomically publishes the session. For a bounded canary or direct capture,
the finalizer validates the applicable host documents and provisional result, seals one diagnostic
bundle, and atomically publishes it only after postflight, cleanup, and restoration pass. This
bounded finalization path cannot mark evidence canonical or claim-bearing. Standalone `compare`
always produces a diagnostic report; only the campaign finalizer emits a claim-bearing candidate
comparison. This keeps conditional execution and evidence validity out of shell and GitHub YAML
while requiring no host JVM.

Failures before the frozen finalizer is verified emit sanitized stderr and no artifact. The fixed
host adapter first validates arguments and output-path shape without writing or invoking Docker,
then validates adapter provenance before any Docker call. It next verifies the exact Docker
context, immutable child manifest and OCI config, frozen runner/finalizer identity, JDK identity,
and required tool inventory. Only after those checks pass may it reserve a writable artifact root.
Once the finalizer is verified and the root is reserved, every failure uses the full sanitized
`INVALID` bundle. An invalid/unwritable output is an input failure; exhausted storage or a later
write/fsync/publication failure is a publication failure. Those cases emit sanitized stderr and
their prescribed nonzero exit, but cannot guarantee an artifact. The design does not pretend a
broken daemon—or an unwritable destination—can launch or store its own failure reporter.

Gradle builds and freezes the runner but is not invoked in a timed or finalizer container. Internal
Gradle task names and the host/container phase handshake are implementation details guarded by
schemas and TestKit tests; documentation and CI invoke only `scripts/performance/run`. Deleting the
host adapter would force Docker/substrate acquisition into callers, while deleting the build logic
would scatter measurement rules across shell and YAML. Both parts therefore earn their place in
one deep module.

Within `buildSrc`, a `performance-runner` subproject contains the pure Kotlin evidence engine and
has no Gradle API dependency. Thin Gradle task adapters assemble production/benchmark artifacts and
freeze that runner's ordinary jar/classpath. This keeps runtime validation, statistics, schemas,
and rendering usable from plain `java`, while build lifecycle wiring remains at the Gradle seam.

The implementation behind that interface owns:

- JMH compilation and generated benchmark metadata;
- construction and validation of the classpath-preserving distribution;
- the exact JDK executable, JVM arguments, JMH arguments, and logging profile;
- process exit handling and expected-row validation;
- capture metadata, privacy filtering, checksums, staging, and atomic publication;
- compatibility checks and statistical comparison; and
- machine-readable and human-readable reports.

Canary, capture, and campaign require explicit frozen self-contained distributions; standalone
compare requires an explicit frozen runner distribution. Each verifies its embedded build/protocol
manifest and never compiles a treatment. This permits the pre-fix baseline artifact to be rerun
beside a later candidate without checking out or rebuilding either treatment during the controlled
timing session.

This is a deep module: deleting it would scatter artifact assembly, process execution, validation,
metadata capture, compatibility rules, and comparison logic across Gradle tasks, shell scripts, CI,
and documentation. Splitting those responsibilities into provider abstractions in V1 would add
interfaces without a second implementation and is rejected.

Proposed locations:

```text
buildSrc/src/main/kotlin/performance/**
buildSrc/src/test/kotlin/performance/**
buildSrc/performance-runner/src/main/kotlin/performance/**
buildSrc/performance-runner/src/test/kotlin/performance/**
config/performance/profiles/*.json
config/performance/policies/*.json
config/performance/runtime/*.json
scripts/performance/run
src/jmh/kotlin/com/salesforce/revoman/benchmark/scenario/**
src/jmh/kotlin/com/salesforce/revoman/benchmark/*Benchmark.kt
src/jmh/resources/performance/**
docs/superpowers/benchmarks/**
```

## V1 execution environment

### Single controlled reference profile

The only V1 claim-bearing profile is `m4max-docker-linux-arm64-v1`:

- host substrate: the project's long-lived Apple M4 Max Mac on AC power;
- virtualization adapter: Docker Desktop through the explicit `desktop-linux` context;
- measured runtime: one checked-in, digest-pinned `linux/arm64` OCI platform image containing the
  exact JDK 21 runtime and Linux userspace;
- storage: frozen distributions and benchmark inputs copied to container-local storage before the
  session, never read through an APFS bind mount by a timed operation; and
- session: every A/A fork-count attempt plus the selected baseline A1, baseline A2, and candidate B
  execute sequentially in one continuously allocated timed container inside one Docker Desktop VM
  and one declared campaign; only the JMH fork JVMs are intentionally fresh.

The selected image source is the official Eclipse Temurin 21 Ubuntu 24.04 repository,
`docker.io/library/eclipse-temurin`. A mutable tag may be inspected only while creating or
deliberately revising the checked-in runtime profile. That profile records the exact
`repository@sha256:<linux-arm64-child-manifest>` reference, OCI config digest, complete JDK version,
JDK binary SHA-256, and required userspace-tool inventory. The inventory includes `/usr/bin/mv`
from GNU coreutils 9.4 because the verified finalizer owns publication. Normal freeze, canary, and
campaign runs never resolve a tag. If that digest disappears or its verified contents do not
satisfy the profile, the run fails; choosing another image is an explicit protocol revision with a
new baseline.

An online adapter phase pulls the checked-in reference by digest and verifies its local platform,
manifest/config identity, tool inventory, and JDK hash. Timed and finalizer containers are created
from that digest with `--pull=never`; `--network none` alone is insufficient because the Docker
daemon can otherwise contact a registry while creating a container. Both the Mac and GitHub ARM
must use the same already-present child manifest. Forcing an amd64 image through emulation, or
resolving two different children of one multi-platform index, creates a different profile and
cannot produce V1 claim evidence.

The checked-in profile fixes the container-visible processor count, hard memory limit, equal
memory/swap limit, PID limit, JVM heap, JVM/JMH arguments, environment/locale/timezone, writable
mounts, user, capability set, and security flags. Values must fit the Mac's current roughly 8 GiB
Docker Desktop VM allocation, are qualified before the first baseline is frozen, and cannot change
inside an A1/A2/B campaign. Timed and finalizer containers run as a declared non-root UID/GID with
a read-only root filesystem, every Linux capability dropped, `no-new-privileges`, and only declared
tmpfs or container-volume write points for temporary data and staged output. They have no host home
mount, Docker socket, credentials, external network, or privileged mode. Loopback remains available
for `MockHttpServer`.

No host environment is inherited implicitly. The runtime starts with image-declared variables plus
a checked-in allowlist of explicit overrides such as locale and UTC timezone; safe values and
hashes are recorded. Unexpected `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, proxy, repository,
credential, GitHub, or corporate variables fail validation rather than being forwarded. The JDK
binary hash is checked before and after each timed/finalizer phase so a writable volume cannot
replace the measured runtime unnoticed.

V1 deliberately measures a warm filesystem-cache condition. Dropping host/VM caches would require
privilege and cannot be made equivalent on Docker Desktop and GitHub. Immediately before every A1,
A2, and B capture, the campaign verifies and sequentially reads that role's complete frozen
distribution and fixture manifest in canonical path order, outside JMH timing, then applies the
same settling interval. The byte-count/hash log proves equal policy application even when artifact
sizes differ. "Cold" continues to mean first `revUp` in a fresh fork, not cold storage.

The performance commands require no native Java, Gradle, JMH, compiler, language package manager,
or benchmark daemon on the Mac. Their host-native dependencies are limited to Docker Desktop/CLI,
the repository checkout and Git client already needed for development, and standard read-only
macOS utilities used by preflight. The campaign must not use the corporate `java` wrapper. Any
preparation requiring network access completes before the timed offline container starts. This
does not redefine the repository's separate developer-side Gradle requirement for Qodana.

Freeze, preparation, and timing are distinct containers using the same pinned image. `freeze` is an
online, non-timed operation: it resolves the clean treatment plus capture-runner build closure and
emits one validated frozen distribution containing the executable runner archive. A campaign never
builds either supplied distribution and never invokes Gradle. After the daemon pre-pulls the image,
a non-timed preparation container starts with `--network none --pull=never`, has no host-home,
credential, secret, or Docker-socket mount, verifies both frozen artifacts, and copies them plus
fresh staged-output/temp state into new container-managed volumes. After it exits, the host performs
quiet qualification and settling. One timed container then executes the frozen runner directly
with `--network none --pull=never`; it receives only those verified volumes and declared tmpfs
mounts. A fresh-volume integration test must prove this path works without consulting a host Gradle
cache, pulling an image after the offline boundary, resolving dependencies, or attempting container
egress.

Every timed campaign, canary, or capture container writes provisional output only to an
operation-scoped Docker volume. It never mounts the host evidence destination. After timing and
host postflight, a profiler scrubber, when needed, mounts only that operation volume writable and
has no host bind. It atomically persists and fsyncs a validated provisional summary and scrub
intent, deletes and fsyncs the raw recording, then persists a completion marker. A sealing
finalizer subsequently mounts the operation volume read-only plus exactly one reserved host
artifact parent writable. Before its first write it verifies the reservation token from inside the
mounted view, which anchors the bind despite Docker Desktop's host/container inode translation. It
validates the complete scrub transition and absence of raw profiler data, materializes the
sanitized staging tree there, and publishes with
`/usr/bin/mv -nT --no-copy -- SOURCE DEST`. A no-clobber skip is converted to a nonzero finalizer
failure unless the source disappeared and the exact destination appeared; every collision must
retain the source and leave the destination unchanged, and a cross-filesystem move must fail
without copy fallback. The campaign finalizer applies the full dependency DAG; the bounded
canary/capture finalizer seals only one permanently diagnostic bundle. Source distributions remain
read-only in every non-freeze phase; provisional operation output is writable only by the timed
runner and the narrowly scoped profiler scrubber.

### Fail-closed Mac qualification

The versioned checked-in Mac qualification policy defines exact unprivileged commands, parsers,
units, thresholds, sampling cadence/count, settling and idle durations, timeouts, allowlisted
process/container identities, and stale-lock handling. Its canonical hash is part of protocol and
evidence identity; prose such as "heavy" or "sustained" is never an implementation default. The
host adapter is non-interactive and never invokes `sudo` or waits for a password. It must:

1. acquire one shared atomic host/profile operation lock before staging recovery, image pull, or
   any subcommand container; freeze/canary/capture/compare hold it through their publication, while
   a campaign holds it through preparation, quiet preflight, timing, postflight, restoration,
   finalization, and evidence publication; a live or unprovably stale lock fails closed;
2. select `desktop-linux` explicitly and validate the daemon by API calls rather than trusting the
   currently unreliable `docker desktop status` output;
3. require arm64, the declared Docker Desktop/Engine/LinuxKit versions and resource allocation,
   the selected image digest, and no amd64 emulation;
4. require Docker Desktop to be the only active container/VM adapter, stop and later restore only
   explicitly allowlisted user-owned interference, and reject unknown containers, VMs, or
   sustained heavy processes instead of killing them blindly;
5. require the declared AC-power/power-mode state and reject macOS thermal warnings, active backup
   or update work, sustained CPU load, or memory pressure;
6. require every online preparation container to have exited, then use `caffeinate`, a fixed
   settling window, and a minimum user-idle window immediately before the unattended campaign;
7. run a fixed-cadence lightweight host/container watcher from before the first timed capture until
   terminal timed-container exit—B completion, the final failed A2 when calibration is exhausted,
   or an earlier fork/child/scenario failure—recording only sanitized policy observations; any new
   container/VM/adapter, prohibited process class, sustained resource threshold breach, backup,
   update, thermal, power, or memory event permanently invalidates the campaign even if it clears;
   ordinary transient system processes do not fail by name alone;
8. record pre/post thermal, CPU, memory-pressure, swap/page, container, and runtime fingerprints;
9. publish a sanitized full `INVALID` bundle with exact reasons only after the frozen finalizer is
   verified and the artifact root is reserved; earlier argument/output/provenance/lock/daemon/
   context/image/finalizer failures emit sanitized stderr and no artifact; and
10. release the operation lock last: after output/invalid publication for a bounded subcommand, or
    after final campaign evidence publication/invalid staging quarantine for `campaign`.

The watcher and adapter use only the policy-declared built-in tools such as `pmset`, `tmutil`,
`memory_pressure`, `vm_stat`, `ioreg`, `ps`, `sysctl`, and `caffeinate`. Their fixed observation
overhead is present for A1, A2, and B and is recorded with the campaign.

Only built-in, unprivileged observations are required. `powermetrics`, privileged host tuning,
kernel extensions, and disabling corporate controls are outside V1. If a future check genuinely
needs elevation, the adapter fails immediately and prints the exact human checkpoint command; it
never prompts or retries silently.

Docker cannot freeze macOS scheduling, M4 frequency/thermal behavior, Docker hypervisor behavior,
or host background activity. The same-session A/A gate and scoped claim language address those
limits. A macOS or Docker upgrade changes the recorded host/runtime fingerprint and requires fresh
calibration; historical captures from a different fingerprint are never compared arithmetically.

If this Docker profile still fails A/A at 40 forks after qualification, it is diagnostic only. A
native-macOS profile may then be designed as a separate fallback, but it is not implemented or
maintained speculatively in V1.

### Verified environment inputs

As of 2026-08-16, GitHub documents the standard public `ubuntu-24.04-arm` runner as a fresh arm64 VM
with four CPUs, 16 GiB RAM, and 14 GiB storage, free for public repositories. GitHub's native macOS
arm64 runner is an M1-class three-CPU/seven-GiB environment and does not support nested
virtualization. These facts justify the Linux ARM adapter but do not make hosted timings compatible
with the M4. Revalidate them before implementation against the
[GitHub-hosted runner reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners).

Read-only inspection of the controlled Mac found Apple M4 Max/arm64, macOS 26.6.1, 64 GiB host RAM,
and a live Docker Desktop arm64 Linux engine exposed as `desktop-linux`, with 16 virtual CPUs,
roughly 8 GiB RAM, and LinuxKit 6.12.76. Colima has since been removed and `desktop-linux` is the
active context. These are starting observations, not hard-coded forever: the profile captures and
qualifies current values before baseline. Docker resource controls and multi-platform identity must
follow Docker's
[resource-constraint](https://docs.docker.com/engine/containers/resource_constraints/) and
[multi-platform image](https://docs.docker.com/build/building/multi-platform/) contracts.

The host's `/usr/local/bin/java` is a corporate guidance wrapper, and macOS reports no registered
native JDK. That is intentional for this design: all JVM execution occurs inside the pinned image.
The selected child is built from Adoptium's pinned
[Temurin 21 Ubuntu 24.04 Dockerfile](https://github.com/adoptium/containers/blob/df6138afaf1b564116e895b0acd51d70e11cd996/21/jdk/ubuntu/noble/Dockerfile)
and published through the official
[Eclipse Temurin image repository](https://hub.docker.com/_/eclipse-temurin).

Repository guidance still tells developers to start Colima for Qodana even though Colima has been
removed. This tranche updates `DEVELOPMENT.md` and the `build.gradle.kts` Qodana comment to the
verified Docker Desktop/`desktop-linux` invocation. The documented command must validate the
daemon/context, remain non-interactive, and require no second local VM.

## Authoritative benchmark artifact

### Classpath-preserving distribution

The authoritative artifact is a versioned directory/archive containing untouched dependency jars:

```text
revoman-performance-<version>/
├── bin/                         # generated launcher metadata/scripts
├── app/revoman.jar              # production classes/resources
├── benchmark/revoman-jmh.jar    # benchmark + generated JMH classes/resources
├── lib/*.jar                    # exact JMH/production dependency jars, never unzipped
├── runner/
│   ├── performance-runner.jar   # capture/comparison/finalizer implementation
│   └── lib/*.jar                # exact runner dependencies, never unzipped
├── protocol/
│   ├── adapter/run              # canonical host adapter bytes/hash
│   ├── profiles/{canary,cold,warm}.json
│   ├── runtime/{linux-arm64,m4max-docker,github-hosted}.json
│   ├── qualification/{m4max-docker,github-hosted}.json
│   ├── schemas/*.json
│   ├── test-vectors/bootstrap-v1.json
│   └── expected-cells.json
└── metadata/
    ├── classpath.json           # named ordered benchmark/runner entries, coordinates, hashes
    ├── provenance.json          # treatment, harness, and freezer SHAs/clean-source proofs
    ├── protocol.json            # measurement-protocol closure
    └── distribution.sha256      # every other distribution file; never hashes itself
```

The benchmark jar contains generated `META-INF/BenchmarkList` and `META-INF/CompilerHints`. Test
output and test dependencies are excluded. Both the benchmark and runner use explicit ordered
classpaths; dependency jars retain their own manifests, multi-release classes, service descriptors,
module descriptors, and signatures. The distribution embeds all three V1 capture-profile families,
the common/runtime/substrate declarations, and their supported qualification policies. The optional
regression policy is external comparison input and is deliberately not frozen into capture protocol.

`includeTests=false` is set at the JMH plugin configuration seam, before bytecode generation and
generated-metadata compilation. Filtering the final distribution is not sufficient because test
benchmarks could already have contaminated `BenchmarkList`.

The production jar intentionally embeds `kotlinx-collections-immutable`. Its standalone resolved
jar is therefore recorded in dependency metadata with placement `embedded:app/revoman.jar` and is
not also placed under `lib/`. All other dependency jars are copied untouched. The classpath uses an
explicit ordered entry list—never a wildcard. Duplicate validation computes each loadable
binary-class identity for the selected recorded JVM feature version after applying each jar's own
multi-release rules, ignores per-jar
`module-info.class` descriptors on the classpath, and rejects any remaining identity supplied by
more than one effective entry. The forked JVM command line must match that ordered list exactly.

The standard flattened `jmh` and `jmhJar` tasks are disabled with an explanatory `GradleException`.
They cannot remain as an easy fail-open alternative. Documentation and CI use only the
`scripts/performance/run` interface. JMH compilation, bytecode generation, runner/distribution
assembly, and metadata tasks that do not flatten or directly expose the artifact remain private
implementation dependencies; capture, comparison, finalization, and recovery are runner phases,
not Gradle tasks.

### Artifact validation

Before launching JMH, the module must verify:

1. Production and benchmark jars validate individually.
2. No test class or test-only dependency is present in the ordered classpath.
3. No duplicate effective loadable binary class exists within either ordered runtime classpath
   after the stated multi-release/module-descriptor rules.
4. Generated benchmark metadata exists and lists the expected benchmark set; the runner archive,
   schemas, embedded protocol files, and adapter hash match their manifests.
5. Every effective classpath entry matches `metadata/classpath.json`, and every distribution file
   except the checksum manifest itself matches `metadata/distribution.sha256`.
6. The fork command uses that exact ordered classpath with no wildcard or extra entry.
7. The selected Java executable is JDK 21 or newer and is exactly the executable recorded later.
8. The output/staging directory is new; stale result files cannot satisfy the run.

The launcher invokes JMH with `failOnError=true`/`-foe true` and JSON output. The frozen runner and
supported outer subcommand must also fail if JMH exits nonzero, the result is absent or malformed,
or row validation fails.

`metadata/protocol.json` defines the exact measurement-protocol closure. Its canonical hash covers:

- all `buildSrc` performance task-adapter and `performance-runner` implementation/schema sources;
- benchmark/scenario sources and packaged resources;
- launcher/container-adapter templates and expected-cell declarations;
- benchmark-only logging configuration;
- all embedded canary/cold/warm profile families, runtime/substrate declarations, exact supported
  qualification policies, schemas, expected cells, bootstrap vectors, host adapter, and OCI
  platform digest; and
- Gradle wrapper, JMH Gradle-plugin, JMH core, Kotlin compiler, and Java toolchain identities.

The distribution also records hashes of the compiled benchmark jar, runner archive/classpath, and
launcher implementation. Those compiled hashes and the protocol hash must match between baseline
and candidate. A/A further requires every artifact hash, including the production jar, to match.

`provenance.json` distinguishes the treatment source that produced `app/revoman.jar`, the original
clean capture-runner source that froze the immutable harness, and the clean runner source executing
the current `freeze`. Candidate freeze preserves the baseline's harness provenance and bytes while
recording its own treatment/freezer SHA and proof that every protocol-owned source hash still
matches. These roles are never collapsed into one ambiguous distribution Git SHA.

Shadow and a manually repaired fat jar are rejected for V1. Shadow would be the fallback only if a
single-file executable became a binding requirement; hand-maintained service merging would create a
repository-owned shading engine with poor locality and ongoing dependency-upgrade risk.

## Benchmark protocol

### Structural canary

The `canary` subcommand validates and runs an explicit authoritative distribution with one fork,
zero warmups, one short measurement, and no timing threshold. It requires exactly the declared
canary rows.

The sandbox canary:

- initializes Log4j and asserts that the real production provider/context factory is active;
- rejects SimpleLogger fallback or Log4j provider/status initialization errors;
- evaluates a minimal `PmSandbox` JavaScript expression so Graal/Truffle multi-release loading and
  JavaScript option discovery are exercised; and
- produces one finite nonempty raw measurement.

The V3 real-wire canary executes one bounded operation and validates its functional invariants. Its
score is discarded; it proves that the end-to-end scenario is runnable from the packaged
distribution.

A Gradle TestKit fixture supplies a deliberately failing benchmark setup to the same frozen runner
used by the canary. Both the build fixture and supported subcommand must return nonzero and leave an
invalid diagnostic bundle, never a successful empty table. The production canary itself contains no
intentionally failing row.

### Primary V3 real-wire scenario

The benchmark owns a minimal V3 fixture rather than borrowing the broader, stateful integration
fixture:

```text
performance/revup-v3/
├── .resources/definition.yaml
├── benchmark.request.yaml
└── benchmark.environment.yaml
```

The request is `GET {{baseUrl}}/benchmark`.

- The V3 environment defines `markerSeed: fixture-marker`.
- A deterministic pre-request script reads that value and sets a derived marker consumed by the
  outgoing request. This makes V3 environment loading observable on the wire.
- A strict loopback handler accepts only the expected method, path, and derived marker and returns
  the exact bytes `{"id":42}` with `Content-Type: application/json; charset=utf-8`.
- A deterministic response script parses the body, records one passing `pm.test`, and writes the ID
  into the environment.
- Only `baseUrl` is dynamically overlaid with the server's ephemeral origin.

V3 is the primary format because `revUp` reloads and normalizes it on every invocation. V3 therefore
exercises the public production path, including classpath/JAR directory resolution, definition and
environment reads, YAML parsing, source hashing, V3-to-V2 normalization, scripts, HTTP, environment
synchronization, and report creation. V2 still performs parsing and would not isolate the runtime;
it would only omit V3-specific work and the exact ownership defects in scope.

The fixture identity is not `Item.sourceHash`. Build creates a canonical JSON tree manifest over
every regular file under the source fixture root. Entries are sorted by relative UTF-8 path and
contain exactly `{path, byteLength, sha256}`; canonical JSON is UTF-8 with lexicographically ordered
object keys, no insignificant whitespace, and a trailing newline. The tree hash is SHA-256 of those
canonical bytes. Symlinks and unexpected files are rejected, and the environment file is included.
Artifact preflight recomputes every entry hash from the bytes packaged inside the benchmark jar and
requires exact equality with the source manifest before execution.

### Scenario lifecycle

A shared benchmark scenario hides server startup, handler behavior, `Kick` construction, counters,
validation, and teardown. Cold and warm JMH benchmark classes are thin adapters over it.

- The mock server is trial-scoped and uses an ephemeral loopback port.
- Trial setup is transactional: resources remain local until acquisition is complete, and any setup
  failure eagerly closes the server because JMH may skip teardown for an unready trial state.
- The immutable `Kick` is reused within a fork.
- `Rundown.learnedLedger` is never fed into the next run, so each operation performs real HTTP.
- Warm operations reuse the same server origin, HTTP connection pool, and process-wide Graal engine.
- The existing request ledger remains bounded because every profile has fixed operation counts.
- No duration-driven benchmark is permitted for this scenario.
- Handler matching and request-ledger capture occur inside the HTTP round trip and are classified as
  fixed measured fixture overhead.
- Post-operation assertions run in `@TearDown(Level.Invocation)`, outside the timed method, and any
  assertion or teardown exception fails the fork.
- Each invocation verifies one successful report, HTTP 200, exact JSON content type/body, expected
  assertion, expected final environment ID, and a one-request handler-count delta.
- Trial teardown verifies the retained request total and wire shape, then closes the server while
  preserving both verification and close failures.
- A measured `revUp` failure or invocation-validation failure eagerly closes the trial resource via
  an idempotent failure path; cleanup cost in an already-invalid invocation is irrelevant, while
  primary and close failures are both preserved.

The benchmark reuses public `MockHttpServer`, `MockHttpHandler`, `ReVoman.revUp`, `Kick`, and
`Rundown`. It does not promote `DeterministicMockApi`, add a reset method to `MockHttpServer`, or
create a shared fixture source set for one tiny scenario.

### Cold and warm profiles

Both profile families use JMH `SingleShotTime`, one operation per iteration, one thread, and
predeclared fixed variants.

| Profile family | Fork variants | Warmup iterations | Measurement iterations | Meaning |
|---|---:|---:|---:|---|
| Canary | 1 | 0 | 1 | Structural validity only |
| Cold | 10, 20, 40 | 0 | 1 | First `revUp` in each fresh fork |
| Warm | 10, 20, 40 | 5 | 10 | Warmed `revUp`; fork variants support A/A escalation |

Before any baseline distribution is frozen, the checked-in cold and warm family documents define
all three exact variants; the distribution protocol hash covers the complete family. A campaign
starts at 10 and may select 20 or 40 without rebuilding because no profile bytes changed. Each
capture records the selected variant hash, and A1/A2/B compatibility requires the same variant.
Changing any family definition after freezing creates a new protocol identity and requires new
baseline and candidate distributions.

JVM launcher startup is explicitly excluded. If it becomes important, it will be a separate
external-process benchmark rather than being mislabeled as JMH cold behavior.

The cold operation is specifically the first `revUp` after JMH state setup, server startup, and
Log4j provider preflight. Log4j initialization and fixture acquisition are excluded and kept
identical across profiles; “cold” does not mean first use of every library in the process.

### Logging profile

Benchmarks use a uniquely named benchmark-only Log4j configuration selected in fork JVM arguments.
The real Log4j provider must initialize, while application output is set to `OFF`. The run uses
`RunLogSink.NoOp`, and the banner is disabled before the first `revUp`.

This profile intentionally measures current eager no-op message construction but excludes terminal
or file I/O. Console logging, file logging, and alternate sink capabilities are later independent
profiles, not parameters mixed into the primary cell.

GC profiling is allowed for warm diagnostic captures. JFR is diagnostic-only and cannot replace
the canonical primary metric in V1.

The optional `capture --diagnostic-profiler <gc|jfr>` selector is legal only with `--profile warm`.
Both named diagnostic variants and their exact JVM/JMH/JFR settings are frozen inside `warm.json`
before the baseline is built; the flag selects one of those immutable variants rather than adding
operator-supplied arguments. A profiler capture is permanently diagnostic, is rejected as A/A,
candidate-comparison, or campaign input, and cannot be made claim-bearing. Omitting the flag selects
the ordinary warm variant used by calibration and campaigns.

For JFR, the raw recording never leaves the operation-scoped Docker volume. The timed runner fsyncs
the recording and records its SHA-256 in the immutable provisional capture. After postflight, the
frozen profiler scrubber uses the pinned JDK's JFR tooling to derive an allowlisted, path-free
provisional summary containing the profiler/settings identity, raw-recording SHA-256, duration,
dropped-sample counts, and bounded application/Graal/Truffle/Okio/Moshi/http4k execution and
allocation aggregates by class/method without source paths, thread names, command lines,
environment, or system properties. It atomically writes and fsyncs the summary, validates it,
writes and fsyncs a scrub intent binding the provisional-capture, raw-input, and summary hashes,
deletes the raw recording and fsyncs its directory, then writes and fsyncs a completion marker.
Recovery can safely repeat before intent, resume deletion while raw input remains, or complete from
the durable intent after deletion; it can never synthesize a summary after losing the raw input.
The read-only sealing finalizer validates all three hash bindings, the completion marker, and raw
absence before publishing `profiler-summary.json`. GC diagnostics use the same optional summary
file but derive it from validated JMH secondary metrics and require no raw-recording deletion.
These summaries may rank exercised hypotheses; they are never comparator inputs.

## Capture evidence

### Bundle layout

The `campaign` subcommand owns one run-ID-scoped parent containing `campaign.json`, every attempted
A1/A2 capture and calibration comparison at each fork count, the selected B capture and candidate
comparison when calibration passes, sanitized adapter/preflight/postflight logs, and a recursive
checksum manifest. `campaign.json` records the qualification-policy hash, executing-runner/adapter
hashes, exact role/sequence/path/hash graph, and why the campaign escalated, stopped, or ran B. No
symlink or `latest` alias selects evidence. Each child
bundle remains inside a unique same-filesystem `.<run-id>.staging` parent outside the valid/invalid
namespaces until campaign postflight. A known success or failure is sanitized, indexed, checksummed,
and published by one atomic parent rename.
An abruptly orphaned staging parent is recovered and published as `INVALID` by the next invocation
after privacy filtering; if safe recovery cannot be proven, it remains locally quarantined and is
never uploaded or mistaken for evidence. The recursive checksum excludes itself, sorts normalized
relative UTF-8 paths lexicographically, and hashes every other regular file exactly once.

The timed runner writes canonicalized observations, sanitized logs, and
`capture.provisional.json` files. Its A/A evaluator writes only
`calibration.provisional.json`; that unpublished decision may authorize B but is not comparison
evidence. No provisional file contains `canonical` status or a public bundle checksum. After
postflight, the frozen finalizer performs this exact dependency order:

1. validate the adapter/preflight/watcher/postflight/restoration documents, every provisional
   observation, executing-code/protocol identity, session order, and cleanup outcome, then compute
   the final campaign status/claim-eligibility decision as immutable in-memory state;
2. derive every final capture document and evidence strength, then compute each immutable child
   capture checksum;
3. recompute every calibration comparison from the sealed A1/A2 captures and, if B ran, recompute
   the candidate comparison from sealed A2/B plus the sealed calibration result; any final
   calibration outcome differing from the provisional B/no-B decision invalidates the campaign;
4. write comparison documents/checksums, then write `campaign.json` referencing only final child
   IDs, relative paths, and checksums;
5. compute the parent recursive checksum last, fsync the required files/directories, and atomically
   rename the parent into the valid or `INVALID` namespace.

The finalizer never patches an already-checksummed public child in place. It is restartable from
the immutable provisional inputs while the parent remains staged. Tests interrupt before and after
every transition above; recovery must either reproduce the same final bytes or publish/quarantine
an `INVALID` parent, never expose a partially canonical campaign.

Inside a campaign, each attempted capture uses the following child layout but remains unpublished
inside the campaign's staging parent until the final parent rename. A direct diagnostic `capture`
first writes provisional output to its operation-scoped Docker volume. After the adapter records
bounded postflight, cleanup, and restoration, the frozen finalizer validates those documents,
materializes the entire diagnostic bundle in a new sibling staging directory under its destination
parent, and publishes it with one same-filesystem atomic rename. A canary uses the same bounded
sealing lifecycle and can never become canonical. The capture layout is:

```text
<UTC timestamp>-<full SHA>-<profile>/
├── capture.json
├── jmh-result.json
├── profiler-summary.json       # present only for a frozen gc/jfr diagnostic variant
├── stdout.log
├── stderr.log
└── checksums.sha256
```

`jmh-result.json` is a canonical privacy-filtered projection of JMH output, not a byte-for-byte copy
of the temporary child-process file. Measurement arrays and semantic values are preserved, while
absolute executable paths are replaced with stable tokens. `capture.json` records the SHA-256 of
the temporary raw input and the sanitizer version. The unsanitized file is deleted after validation
and is never published.

`checksums.sha256` covers every other published file in the bundle. Scrub intent/completion markers
are internal operation-volume state and are not published. Failed attempts receive the same
privacy treatment, are retained separately as explicit `INVALID` diagnostic bundles, and can never
appear in the valid-capture namespace.

When `profiler-summary.json` is absent, `capture.json` records profiler variant `none`. When it is
present, `capture.json` records its hash, the frozen variant identity, and the raw profiler-input
hash; publication fails if the scrub transition is incomplete or the raw recording still exists
anywhere in the operation or staging tree.

### Required metadata

`capture.json` is a strict, versioned schema with capture-global sections for run identity,
environment, artifacts, protocol, and a `cells[]` array. Global metadata contains:

- schema and benchmark-protocol versions;
- capture ID, process-run ID, performance-session ID, session sequence, status, UTC timestamps, and
  process exit;
- evidence strength (`canary`, `diagnostic`, or `canonical`) and claim-eligibility reasons, derived
  from the verified adapter/runtime/host profile rather than accepted as an operator flag;
- treatment Git SHA/build-clean proof, immutable-harness Git SHA/clean proof, distribution-freezer
  Git SHA/tree-clean proof, and capture-runner Git SHA/tree-clean assertion, with their distinct
  roles preserved;
- benchmark source/protocol hash, exact qualification-policy hash, canonical workload-tree hash,
  and current host-adapter hash;
- production, benchmark, distribution, and ordered-classpath hashes;
- executing performance-runner jar, ordered runner-classpath, schema, renderer, and comparator
  hashes;
- dependency coordinates and hashes;
- Gradle, JMH Gradle-plugin, JMH core, Kotlin compiler, and schema/sanitizer versions;
- JDK binary SHA-256, vendor, complete version, and JVM arguments, but not its absolute path;
- selected OCI platform-manifest/config digests and runtime-image identity;
- measured Linux OS, runtime kernel (LinuxKit locally or the hosted Linux kernel), arm64
  architecture, container-visible CPU/memory/swap/PID limits, storage/network/security settings,
  and an operator-supplied opaque host ID;
- a discriminated substrate fingerprint: controlled Mac captures require macOS version/build,
  hardware model class, Docker Desktop/Engine versions, and declared VM resources; GitHub captures
  require runner label/image version, kernel, Docker Engine version, and advertised resources;
  neither variant records a serial number, hostname, or raw machine identifier;
- a discriminated qualification result: `controlledMacCampaign` requires sanitized preflight,
  watcher, postflight, power, thermal, CPU-idle, memory-pressure, swap/page, user-idle,
  interference, cleanup, and restoration outcomes; `controlledMacBoundedDiagnostic` is used by
  standalone canary/capture and requires the same operation lock plus bounded preflight, watcher,
  postflight, cleanup, and restoration, while A/A/B session-only fields are inapplicable with
  schema-defined reasons; `githubHosted` requires runner/container setup and cleanup outcomes and
  marks all Mac-only fields inapplicable with schema-defined reasons;
- logging profile; and
- profile identity plus declared forks, warmup, measurement, and profilers.

Every `cells[]` entry contains benchmark name, parameters, mode, unit, threads, batch size, primary
metric name/direction, an exact JSON pointer and hash for its authoritative `jmh-result.json` row,
declared sample dimensions, and derived per-fork summaries. Raw observations occur only in
`jmh-result.json`; validation recomputes every summary and requires exact agreement. Diagnostic
secondary metrics such as GC profiler output are identified separately and never become required
comparison cells implicitly.

The bundle must not contain usernames, home/workspace paths, hostnames, IP addresses, unallowlisted
environment variables, command-line secrets, or raw machine IDs. Checked-in non-secret runtime
settings such as locale/timezone may be recorded only through the strict environment allowlist.
JMH's absolute `jvm` field, human `VM invoker` line, commands, and logs are sanitized before
publication. The opaque host ID is supplied explicitly to `canary`, `campaign`, or `capture` by the
operator and contains no personal information.

### Validity rules

A capture is valid only if all of these hold:

1. The capture-runner tree is clean, and distribution provenance proves that its treatment and
   immutable harness were built from their distinct recorded clean full SHAs; a candidate further
   proves that its harness bytes came unchanged from the declared baseline distribution.
2. Artifact preflight passed.
3. The child process exited zero with fail-on-error enabled.
4. Log scanning found no known provider fallback, multi-release, interpreter-fallback, or benchmark
   failure signature.
5. JMH JSON is present, parseable, and nonempty.
6. The exact expected benchmark/parameter matrix occurs once—no missing, duplicate, or unexpected
   row.
7. Every controlled primary raw observation is finite and strictly positive, and its dimensions
   match the declared forks and iterations. Derived JMH `scoreError`/`scoreConfidence` values may be
   `NaN` for the one-sample canary and are discarded rather than treated as comparator inputs.
8. Functional scenario verification and teardown succeeded.
9. Every output validates against its schema and checksum manifest.
10. The executing adapter, runner/classpath, schemas, embedded profiles, runtime declarations,
    expected cells, and qualification policy exactly match the frozen protocol manifest.
11. A capture marked `canonical` passed the complete controlled-Mac preflight/postflight and exact
    runtime-profile checks; GitHub-hosted or other-host captures can be at most `diagnostic`.

A direct lower-level `capture` invocation can be at most `diagnostic`. Only `campaign` may finalize
its staged A1/A2/B children as `canonical` after campaign postflight, cleanup, recursive checksum,
and claim-eligibility validation all succeed.

No later normalization step may turn an invalid run into a valid capture.

## Comparator

### Explicit selection and compatibility

The standalone comparator receives an explicit frozen runner distribution, comparison kind, and
baseline/candidate paths. The campaign finalizer uses the identically hashed runner embedded in its
baseline distribution. Both verify executing comparator/schema/renderer/qualification-policy
identity before reading inputs. Baseline and candidate are relational roles written into comparison
evidence; they are not mutable labels stored inside capture bundles. The comparator never guesses
from `latest`, Git ancestry, timestamps, filenames, parent directories, or a database query.

A candidate comparison requires distinct treatment SHAs. An A/A comparison requires the same
treatment SHA and every distribution artifact hash to match, and is labeled calibration-only. Both
kinds require distinct capture IDs, process-run IDs, and directories; supplying one capture twice
is `INVALID`.

`kind=candidate` additionally requires the passing A1/A2 calibration comparison directory. The
candidate comparator verifies its checksum/schema, that its A2 capture ID exactly equals the selected
performance baseline, that A1/A2 calibration passed for every required cell, and that A1, A2, and B
share the same session ID with consecutive sequence values inside the declared duration. Missing,
failed, stale, or unrelated calibration evidence makes the candidate comparison `INVALID`.
Only the finalizer inside that same staged campaign may mark the recomputed candidate comparison
claim-bearing, and only when A1, A2, B, and the campaign all become `canonical` in the finalization
DAG. Standalone `compare` output is always diagnostic even when its inputs came from a canonical
campaign; it can re-render or verify math but cannot create new claim evidence.

For each cell, the following identities must match exactly:

- capture schema and benchmark-protocol version;
- benchmark name and parameter set;
- benchmark source/protocol hash and V3 workload-tree hash;
- exact qualification-policy hash;
- compiled benchmark jar, launcher implementation, frozen performance-runner/classpath,
  adapter, schemas, comparator/renderer, and logging-configuration hashes;
- mode, unit, threads, batch size, forks, warmup, measurement, and profiler identity;
- Gradle, JMH Gradle-plugin, JMH core, Kotlin compiler, and sanitizer versions;
- JDK binary SHA-256, vendor, complete version, and JVM arguments;
- selected OCI platform-manifest/config digests, measured Linux identity, container limits,
  storage/network/security settings, and Docker Engine version;
- logging profile;
- opaque host ID, substrate kind and exact kind-specific fingerprint, architecture, and declared
  processor count; and
- the complete dependency graph and dependency hashes.

Candidate treatment SHAs and production-artifact hashes are required to differ. Immutable-harness
provenance, benchmark/runner/launcher bytes, protocol-owned source hashes, runtime dependencies,
and executing adapter/runner identities must match exactly. Distribution-freezer and host-adapter
checkout SHAs may differ because the production fix changes the repository commit, but each must be
clean and its protocol-owned byte hashes must match; those full SHAs are recorded rather than used
as compatibility shortcuts. A dependency change creates a new protocol identity and requires a new
baseline in V1; it is never silently treated as compatible. Incompatible captures produce an
`INCOMPATIBLE` result, not a warning followed by arithmetic.

### Estimator and uncertainty

Only the declared primary `SingleShotTime` wall-time metric is compared in V1, and lower is better.
GC-profiler and other secondary metrics remain diagnostic. Each primary cell requires at least ten
independent forks.

For each compatible cell:

1. Reduce every fork independently to the median of its measurement iterations. Cold forks contain
   one observation. For an even count, median is the arithmetic mean of the two middle sorted
   values.
2. Compute the point ratio as candidate median fork-summary divided by baseline median fork-summary.
3. Report percentage gain as `(1 - ratio) * 100`; positive is faster for a lower-is-better metric.
4. For each of 20,000 replicates, resample the baseline array to its original fork count and the
   candidate array to its original fork count, independently and with replacement. Compute each
   resample median with rule 1, then record candidate median divided by baseline median.
5. Generate draws with the versioned counter-based `revoman-bootstrap-v1` SHA-256 stream, not a
   platform PRNG. `u32(x)` is exactly four unsigned big-endian bytes; `lp(s)` is `u32` of the UTF-8
   byte length followed by those bytes. Cell identity bytes are ASCII
   `revoman-cell-v1\0`, then `lp(benchmark)`, `lp(profile)`, `u32(parameterCount)`, parameter
   `lp(key),lp(value)` pairs sorted by unsigned UTF-8 key bytes, `lp(mode)`, `lp(unit)`,
   `u32(threads)`, `u32(batchSize)`, `lp(primaryMetric)`, and `lp(direction)`. The 32-byte stream seed
   is SHA-256 of ASCII `revoman-bootstrap-v1\0`, `lp(baselineCaptureId)`,
   `lp(candidateCaptureId)`, and `u32(cellIdentityByteCount)` plus the cell bytes. For replicate `r`
   in `0..19999`, side byte `0` for baseline or `1` for candidate, draw index `d`, and retry `k`,
   hash the seed followed by `u32(r)`, the one-byte side, `u32(d)`, and `u32(k)`; interpret the first
   eight digest bytes as unsigned big-endian `u`. Accept when
   `u < floor(2^64 / n) * n` and select index `u mod n`; otherwise increment `k` (overflow is an
   implementation error). Baseline draws are enumerated
   before candidate draws. The protocol ships known-answer vectors covering seed bytes, accepted
   indices, even medians, ratios, and type-7 endpoints. In both domain literals, `\0` denotes one
   byte `0x00`, not the two printable characters backslash and zero.
6. Sort the 20,000 bootstrap ratios and report the 2.5th and 97.5th percentiles using Hyndman-Fan
   type 7 linear interpolation.
7. Use unrounded values for every classification; round only display fields.

Canonicalized JMH JSON remains authoritative input; normalized values never replace it. The interval
describes conditional fork-resampling uncertainty within the captured session. It does not estimate
between-day or between-session host drift, and every Markdown report states that limitation.

### A/A calibration and session order

Each profile starts with two consecutive, independent captures of the same frozen distribution at
ten forks. Calibration succeeds only when:

- the 95% ratio interval contains `1.0`;
- the point ratio lies in `[0.95, 1.05]`; and
- the ratio interval width is at most `0.10`.

If calibration fails, fork count doubles to 20 and then 40, with two fresh captures and the
predeclared variant identity each time. All variants share the frozen family/protocol hash. If the
40-fork variant still fails, that profile family is too noisy for a V1
performance claim; the result remains diagnostic and no baseline is approved. A count change is
never made after inspecting candidate results.

Before the production fix, the operator may run the lower-level `capture` plus diagnostic
`compare` commands against an already frozen pre-fix distribution to discover whether any
predeclared variant qualifies on the Mac. This never edits or "finalizes" profile bytes after seeing
data. If a profile or qualification policy must change, the distribution is discarded, the protocol
revision is committed, and a fresh baseline is frozen before calibration restarts.

Canonical candidate acquisition occurs in one declared performance session using previously frozen
self-contained distributions: two baseline A/A captures followed immediately by the candidate
capture. The second A/A capture is the comparison baseline. The profile declares a maximum session
duration, initially two hours; exceeding it invalidates the campaign. Capture metadata records the
session ID and monotonically increasing sequence. This ordering bounds—but does not statistically
model—temporal drift.

One campaign ID and performance-session ID span all 10/20/40 attempts. Each fork-count attempt also
has a distinct attempt ID, and sequence increments for every capture. Failed lower-count attempts
may precede the selected variant, but no timed capture may occur between that variant's passing A1,
A2, and B; those three sequence values must therefore be consecutive even when they are not
`1,2,3`. The two-hour session clock begins immediately before the first 10-fork A1 and ends after B
or the final failed 40-fork A/A. Setup and finalization have separate bounded adapter timeouts and
cannot turn an over-duration session valid.

The claim is explicitly profile-scoped:

> On the `m4max-docker-linux-arm64-v1` controlled reference profile, candidate `<SHA>` had
> candidate/baseline point ratio `<R>` for `<cell>` (95% conditional fork-resampling ratio interval
> `[L, U]`), corresponding to point gain `<gain>%`, versus baseline `<SHA>` in one same-session
> A/A-qualified campaign.

The report must not generalize that number to native macOS, GitHub-hosted ARM, x86_64, production
machines, or another session. A separately valid hosted diagnostic may be cited only as directional
corroboration with its own ratio and interval.

### Outcomes

Direction and policy are separate:

- `IMPROVEMENT`: the interval's upper bound is strictly below `1.0`.
- `REGRESSION`: the interval's lower bound is strictly above `1.0`.
- `INCONCLUSIVE`: otherwise, including equality with `1.0`.

If no external regression policy is supplied, policy outcome is `NOT_ENFORCED` while gain and
uncertainty are still reported. With a maximum regression budget `b`:

- `PASS`: the interval's upper bound is at most `1 + b`;
- `FAIL`: the interval's lower bound is above `1 + b`; and
- `INCONCLUSIVE`: otherwise. Equality of the upper bound with `1 + b` passes; equality of only the
  lower bound is inconclusive.

An enforced campaign passes only when every required cell passes. Cells are never averaged together
to hide a regression. Invalid, incompatible, or undersampled cells cannot pass.

### Process exit contract

Evidence publication never implies command success. Every supported subcommand uses this stable
terminal exit contract:

| Exit | Terminal state |
|---:|---|
| `0` | Valid freeze/canary/capture; valid comparison or campaign with no regression policy; or enforced `PASS` |
| `2` | Bootstrap, input, protocol, environment, artifact, or other pre-measurement validation failure |
| `3` | Fork/child/scenario failure or missing, malformed, empty, or invalid measurement output |
| `4` | `INCOMPATIBLE` comparison inputs |
| `5` | Standalone calibration did not pass, or campaign calibration exhausted 40 forks without B |
| `6` | Enforced regression-policy `FAIL` |
| `7` | Enforced regression-policy `INCONCLUSIVE` |
| `8` | Internal, finalization, recovery, checksum, or publication failure |

An `INVALID` result is never exit zero. Intermediate 10/20-fork calibration misses are handled
inside `campaign` and do not terminate it; the final 40-fork miss is exit `5`. Directional
`REGRESSION` or `INCONCLUSIVE` with `NOT_ENFORCED` remains valid evidence and exits `0`; supplying a
regression policy makes `FAIL`/`INCONCLUSIVE` exits `6`/`7`. If a later publication/finalization
failure prevents trustworthy evidence, exit `8` supersedes the earlier outcome. Tests assert both
the evidence state and process exit for every row.

Standalone `compare` atomically publishes a diagnostic directory containing versioned
`comparison.json`, concise `comparison.md`, and `checksums.sha256` covering both. The campaign
finalizer writes the same logical files as staged campaign children in the DAG above. Reports
contain capture identity, executing-comparator provenance, compatibility, point ratios, percentage
gains, intervals, direction, and policy outcome.
When a regression policy is supplied, both outputs record its canonical hash and thresholds. That
policy does not alter capture protocol identity or require recapture; it is distinct from the frozen
qualification policy that is an exact compatibility key. Candidate outputs also record the
calibration-evidence hash and A1/A2/B capture IDs. JSON plus Markdown are the V1 sources of truth.

DuckDB may later query those JSON files as a derived read model. It is not part of capture,
validation, comparison, or publication in V1.

## Breaking resource-ownership redesign

### Ownership rule

Library-resolved path resources are owned by the library and closed in the same lexical scope in
which they are opened. Caller-supplied `InputStream` values remain caller-owned.

The implementation will:

- remove public `bufferFile` overloads, `bufferV3Definition`, and `bufferInputStream` rather than
  deprecate or wrap them;
- retain value-returning public operations such as `readFileToString`, making them close internally;
- retain `readInputStreamToString` with an explicit non-closing caller-ownership contract;
- keep raw source-opening helpers internal, using a scoped read/parse function for owned path
  sources and a deliberately non-closing adapter for caller streams;
- parse V2 collections, V2 environments, and JSON POJOs inside the owned source scope;
- read V3 definitions and request files with scoped `FileSystem` operations;
- close the complete gzip source chain; and
- preserve the parse/read failure as primary while attaching a close failure as suppressed.

The repository currently has no production call to `bufferV3Definition`; its retained value comes
from historical public surface rather than current internal leverage. By the deletion test, removing
it and the ambiguous `bufferFile` API makes the module deeper: callers request a value or parse
operation without coordinating a hidden resource lifecycle.

The change intentionally updates API dumps, documentation, and all repository call sites in one
pass. No deprecated aliases or compatibility adapter will be added.

`ClasspathResolver` separately owns its cached `NioFileSystem` instances for process lifetime.
Scoped path reads close every per-read `Source`; they do not close the cached ZipFS and poison later
lookups. V1 characterizes that repeated reads from the same JAR URI do not grow open handles per
read, but it does not add eviction or shutdown for the one registry entry per distinct JAR URI.

### Tests written before the fix

The red tests must demonstrate:

- a path resource is closed after a successful string read;
- a path resource is closed after its parser throws;
- V2 collection and environment sources close after success and malformed input;
- V3 root/child definitions, request YAML, and environment YAML close after success and malformed
  input;
- a gzip source chain closes after success and corrupt input;
- classpath/JAR and absolute-filesystem reads both close every per-read source;
- parse failure remains primary if close also fails;
- caller-provided input streams remain open after successful and failing string/parser operations;
- repeated reads from one cached JAR filesystem close each source without closing or multiplying the
  process-lifetime ZipFS entry.

A deterministic close-tracking filesystem/source double is the authoritative test mechanism. A
Unix-only JDK 21 regression probe additionally verifies that hundreds of absolute-path reads do not
cause open descriptor count to grow linearly without requesting GC. Unsupported platforms skip only
that runtime probe, not the deterministic ownership tests.

## CI and verification strategy

Ordinary CI proves structure and correctness, never timing:

- existing unit and integration tests;
- build-logic unit tests and Gradle TestKit coverage for JMH generation, runner assembly,
  distribution freeze, and the thin Gradle task adapters;
- plain-runner unit/integration tests for validation, capture, comparison, finalization, recovery,
  schemas, and deterministic math; end-to-end contract tests for all five
  `scripts/performance/run` subcommands and their host/container handshakes;
- capture schema, golden JSON/Markdown, compatibility, comparison, checksum, and atomic-publication
  tests;
- negative tests for nonzero fork/child-process exits, missing/extra/duplicate rows,
  empty/header-only results,
  malformed JSON, nonfinite values, log fallbacks, stale output, and teardown failure;
- an artifact-validator fixture whose duplicate class appears only through the selected JVM's
  multi-release version, proving runtime-version-aware collision detection;
- the `canary` subcommand on JDK 21 with no numeric threshold;
- intentional ABI/API update verification;
- full `build`; and
- `qodanaScan` before push.

The existing build workflow is the automatic CI lane. It pins `ubuntu-24.04-arm`, invokes the same
digest-pinned `linux/arm64` runtime image used by the Mac, grants only `contents: read`, checks out
without persisted credentials, and pins every `uses:` action to a full commit SHA. It invokes the
boundaries explicitly rather than assuming root `build` discovers them:

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

The ordinary build commands use the workflow's exact configured JDK; distribution freeze and
benchmark execution go through the runtime-image adapter. Checkout and artifact transport remain
host workflow steps; measurement rules do not move into YAML. Before the frozen finalizer is
verified, an adapter failure emits sanitized stderr and no artifact. After finalizer verification
and successful reservation of the validated writable artifact root, every failure leaves a full
sanitized `INVALID` bundle. If reservation or later publication is impossible, it instead emits
sanitized stderr and the required nonzero exit without promising an artifact. The workflow uploads
the complete sanitized `build/performance` directory with an unconditional `if: always()` step,
`if-no-files-found: error`, and seven-day retention, so any artifact that can be created survives
task failure. Numeric scores from this artifact are never compared or gated.

A separate `workflow_dispatch`-only diagnostic workflow accepts explicit full baseline and
candidate SHAs and one profile. In one standard `ubuntu-24.04-arm` job it checks out a trusted
capture-runner plus the two treatment worktrees, freezes the baseline, freezes the candidate with
`--harness-from` that baseline, then runs A1, A2, B, validation, comparison, and publication using
the same runtime-image digest. It is optional corroboration: hosted hardware is not the controlled
M4, so its numeric result is labeled diagnostic and never gates a merge or substantiates the scoped
claim.
The workflow independently declares `permissions: contents: read`, checks out with
`persist-credentials: false`, receives no secrets or OIDC permission, requires both SHAs to be
reachable from repository-owned refs, and removes the GitHub token and credentials from build and
timed-container environments. Its action references are full commit SHAs, and its sanitized
diagnostic bundle is retained for at most 90 days.
There is no scheduled, post-merge, `pull_request_target`, cross-run-latest-artifact, or automatic
baseline-promotion path in V1.

This tranche also hardens `.github/workflows/qodana.yml`, because it currently combines a Qodana
Cloud secret and write scopes with mutable third-party action refs. Every action moves to a reviewed
full commit SHA, checkout uses `persist-credentials: false`, and the runner label is fixed at
`ubuntu-24.04`. The `pull_request` job has only `contents: read`, receives no `QODANA_TOKEN`,
disables Qodana annotations,
PR comments, and fix-pushing, and uploads the community-linter report only as a workflow artifact.
The trusted `push`-to-`master` job grants only `contents: read` plus `security-events: write`, exposes
`QODANA_TOKEN` only to the pinned scan step for Qodana Cloud reporting, and has no contents,
pull-request, or checks write scope. Neither path uses `pull_request_target`. If the pinned Qodana
version demonstrably cannot operate under those scopes, the workflow fails closed and the plan
records the smallest documented exception rather than restoring blanket write access. GitHub
recommends full-SHA pinning for third-party actions and documents fork PRs as secretless/read-only;
JetBrains documents that the Cloud token is optional for Community linters:
[GitHub Actions threat protection](https://docs.github.com/en/code-security/tutorials/secure-your-organization/protect-against-threats),
[GitHub workflow permissions](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax),
and [Qodana GitHub Actions](https://www.jetbrains.com/help/qodana/github.html).

The action pin is not enough because the linter container receives the Cloud token. `qodana.yaml`
therefore uses a reviewed immutable OCI index reference, never `:2026.1`; the workflow verifies the
selected platform child before scanning. Read-only inspection on 2026-08-16 resolved index
`sha256:f1c5d3efe2f550409c4d95d266c5dc2025a8069d82c9516781eae72e7383b55d`, with
`linux/amd64` child `sha256:6e03fbb417f0f268333ae04d97c4221bdb6bb666a30f0de8b4a34c521e797622`
and `linux/arm64` child `sha256:a8ea6d25700098433060c62818b6172a3ebbf409573353cb7b35b76b07093870`.
Implementation revalidates those immutable references and records the deliberate update procedure;
a later Qodana upgrade reviews and pins a new index rather than following a moved tag.

The claim-bearing full quiet campaign is also explicit, but it runs through the local adapter on the
controlled Mac rather than through GitHub. Once invoked, it completes unattended without passwords
or mid-run choices. V1 does not register the everyday corporate Mac as a persistent self-hosted
runner for this public repository and does not install a polling daemon. GitHub-triggered one-shot
runners or an attestation-verifying local puller require a separate security design and are
deferred.

Controlled-host timing additionally requires:

- the `m4max-docker-linux-arm64-v1` host/profile ID and exact selected runtime-image digest;
- a clean committed tree;
- the checked-in cold or warm profile;
- passing non-interactive Mac, Docker, container, distribution, and offline-readiness preflight;
- the declared A1 → A2 → B session sequence and maximum duration;
- baseline and candidate captured in the same continuously allocated compatible Docker Desktop VM;
- deterministic container-local input/cache preconditioning before each capture;
- no external network dependency during capture; and
- passing postflight, evidence publication, cleanup, and allowlisted-state restoration.

`StepReport.exeTimings` may be printed beside a run for diagnosis, but end-to-end JMH wall time minus
summed phase timings is not a gate. Current phase telemetry omits setup/load/report/event/logging work
and has known contract drift.

## Delivery workflow

The first implementation session follows this checkpoint sequence:

1. Pin the anonymously pullable `linux/arm64` runtime-image child digest and test the thin Docker
   adapter, freeze seam, environment fingerprint, image pre-pull/`--pull=never` boundary, offline
   transition, Mac preflight/postflight, invalid-bundle, and cleanup behavior. Update Qodana
   guidance from removed Colima to verified Docker Desktop/`desktop-linux` usage, and harden the
   Qodana workflow's event-specific secret/permission/action-pin boundary.
2. Build and test the classpath distribution and fail-closed canary inside that runtime; wire the
   automatic `ubuntu-24.04-arm` canary without a numeric gate.
3. Build and test strict capture/evidence handling.
4. Build and test the explicit comparator and Markdown renderer.
5. Add and characterize the minimal V3 scenario; run only structural canaries so far.
6. Commit the complete immutable profiles/policies, freeze the pre-fix baseline distribution, then
   run lower-level diagnostic A/A calibration and a preliminary capture on the controlled Mac. If
   calibration requires a protocol edit, discard that artifact, commit the revision, refreeze, and
   restart calibration before touching production code.
7. Add failing resource-ownership tests.
8. Implement the breaking ownership cleanup and make the tests pass.
9. Freeze the candidate distribution; in one controlled Mac/Docker session run baseline A1,
   baseline A2, then candidate B; compare B explicitly with A2 and publish all evidence bundles.
10. Run the optional manual GitHub ARM diagnostic only as corroboration, then use frozen warm
    `--diagnostic-profiler gc` and `--diagnostic-profiler jfr` captures to profile/rank only the
    remaining audited hotspots exercised by the primary workload. Record the others as `UNMEASURED`
    with the exact future diagnostic each would need, and recommend at most one next optimization.
11. Run all acceptance gates and stop. The next optimization requires a new design/plan or an
    approved continuation.

For every later hotspot, the repeatable loop is:

```text
characterize behavior -> capture baseline -> make one change -> capture candidate -> compare -> decide
```

Correctness changes remain test-driven. Performance claims remain measurement-driven. A plausible
hotspot found by code reading is a profiling hypothesis until compatible captures support it.

## Acceptance criteria

The tranche is complete only when:

1. The old fail-open reproduction is covered by a failing-then-passing build-logic test.
2. The authoritative distribution runs Graal and the real Log4j provider without flattening
   dependencies or duplicating classes, while direct `jmh`/`jmhJar` use fails with migration
   guidance.
3. A setup failure, empty result, malformed result, unexpected row, or known runtime fallback makes
   the outer task fail.
4. The V3 real-wire canary runs from the packaged distribution and proves all functional invariants.
5. Cold and warm captures are valid only for distributions built from clean commits by a clean
   capture runner and contain all required metadata, artifacts, sanitized logs, and checksums.
6. Explicit compatible, distinct baseline/candidate inputs produce deterministic JSON and Markdown
   comparison reports with gain and conditional fork-resampling uncertainty.
7. Incompatible captures are rejected before statistics are computed.
8. Published valid and invalid bundles contain no forbidden path/host data, while canonicalized JMH
   measurements retain exact semantic values and a hash of the temporary raw input.
9. Deterministic ownership tests fail on the starting implementation and pass after the breaking
   cleanup.
10. The real JDK 21 descriptor probe no longer shows linear descriptor growth across repeated path
   reads.
11. Failed calibration ends without B and without a claim; passing calibration may produce a valid
    A1/A2/B session, whose candidate result uses `INCONCLUSIVE` without significance language when
    its interval crosses `1.0`.
12. Hotspots exercised by the primary workload are ranked from current measurements and frozen
    GC/JFR diagnostic summaries whose capture IDs and checksums are cited; raw JFR is neither
    published nor retained, and crash tests prove scrub recovery around summary persistence and raw
    deletion. Step-scaling, polling, file-sink, and any other unexercised hypotheses
    are explicitly `UNMEASURED`, with no second production optimization bundled into this tranche.
13. Unit, integration, canary, ABI/API, build, and Qodana gates pass.
14. The Mac and `ubuntu-24.04-arm` canary verify the same pinned `linux/arm64` platform-manifest
    digest and JDK binary hash without amd64 emulation.
15. A fresh controlled-Mac performance invocation requires no native JVM/build tool, no privilege
    prompt, and no secret or home-directory mount; arguments/output shape and adapter provenance
    fail before Docker with no artifact, and Docker/runtime/finalizer verification fails before
    reservation with no artifact. After finalizer verification and reservation, failures produce a
    full `INVALID` bundle. Output-reservation/publication failures instead produce sanitized stderr
    and the prescribed nonzero exit without claiming an artifact exists.
16. The Mac adapter proves explicit `desktop-linux` selection, offline timed execution,
    container-local inputs, deterministic cleanup, and restoration of only allowlisted state.
17. Automatic GitHub CI uploads the structural canary while discarding numeric timing, and the
    optional full hosted campaign is explicit and labeled diagnostic.
18. No persistent self-hosted runner, polling daemon, temporary x86 host, or future VM is required
    to reproduce a claim-bearing campaign on the controlled Mac.
19. Qodana documentation and build guidance no longer require Colima, and the verified Docker
    Desktop/`desktop-linux` invocation passes through the project's normal Gradle toolchain without
    a second local VM or privilege prompt.
20. Canary, capture, campaign, finalizer, and comparison reject any adapter, frozen-runner,
    classpath, schema, profile, runtime, expected-cell, or qualification-policy mismatch before
    accepting observations or computing claim math.
21. Interruption tests at every finalization transition expose no partially canonical child;
    restart either reproduces identical final bytes or yields/quarantines an `INVALID` parent.
22. Standalone `compare` is always diagnostic; only the campaign finalizer can emit a claim-bearing
    comparison after sealing A1/A2/B in dependency order.
23. Qodana PRs are secretless/read-only, trusted `master` pushes scope the Cloud token to the scan
    step and grant only required SARIF permission, every action reference is a full commit SHA, and
    the linter resolves from the reviewed immutable OCI index to the declared platform child.

## Alternatives considered

### Repair the existing fat jar

Rejected. It requires maintaining multi-release manifest behavior, service-file merging, module
descriptor exclusions, signature handling, and future dependency-specific duplicate policy. The
classpath distribution achieves the same benchmark capability while deleting the shading problem.

### Use Shadow

Rejected for V1 because a single-file artifact is not required. It is safer than manual shading but
still makes packaging correctness depend on merge policy and plugin-version integration.

### Use V2 as the primary workload

Rejected for the primary end-to-end cell. V2 offers a smaller one-file fixture, but it omits the V3
directory/JAR, YAML, conversion, hashing, and resource-ownership work that public `revUp` performs
for V3 consumers. A minimal V2 companion may later be added as a differential loader diagnostic;
it is not required for the first performance claim.

### Use StepReport timings

Rejected as authoritative measurement. They are useful for localization but omit meaningful work,
truncate phase values in some projections, and are not consistently serialized.

### Store evidence in DuckDB

Deferred. V1 produces a small number of immutable bundles, and explicit paths are safer than an
implicit database query for baseline selection. DuckDB can query JSON later without becoming the
write-path authority.

### Build a general evidence platform

Rejected by the deletion test. V1 has one runner, one evidence schema, one comparator, and one
reporter. Provider interfaces, a database, remote publication, dashboards, and a matrix scheduler
would add surface without current leverage.

### Use native macOS as the V1 measurement runtime

Deferred. Native execution removes Docker's VM scheduler but requires a host JDK and separately
pins fewer behaviorally relevant runtime inputs. GitHub's arm64 macOS runner is an M1 environment
and cannot run the same Linux container because nested virtualization is unsupported. Given the
corporate restrictions on the long-lived Mac, V1 keeps native dependencies minimal and first
qualifies the reproducible Linux/arm64 Docker profile. Native macOS may receive a separate design
only if that profile fails A/A at 40 forks.

### Keep x86_64 or a future VM as the canonical environment

Rejected as a V1 dependency. `gopalaaksh-wsl3` expires in roughly one month, and no replacement VM
is guaranteed. It may provide temporary directional corroboration, but the implementation,
acceptance gates, and claim workflow must remain complete after it disappears. Cross-architecture
captures are never compared.

### Attach the everyday Mac as a persistent GitHub Actions runner

Rejected. This is a public repository, and a persistent runner would expose a corporate workstation
to repository workflow execution while adding credentials, lifecycle, and cleanup state. The V1
canonical campaign is an explicit, unattended local invocation. A GitHub-triggered one-shot runner
or trusted local puller would require a separate security design.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Loopback and JIT noise obscure small changes | Multiple independent forks, A/A calibration, per-fork reduction, uncertainty interval |
| Warm fixture retains requests | Fixed single-shot counts; trial-scoped server; no duration benchmark |
| V3 loader overhead dilutes a downstream optimization | Preserve component benchmarks for localization; add V2 differential only when needed |
| Benchmark source changes between commits | Protocol and workload hashes are exact compatibility keys |
| Dirty source or stale outputs contaminate a capture | Clean-tree preflight, new staging directory, atomic publication, checksums |
| JMH/log output exposes local paths | Schema-aware canonicalization and log sanitization before valid or invalid publication |
| CI runner variation produces false regressions | CI runs canaries only; timing stays on a declared controlled host |
| Mac Docker is run through the wrong adapter or architecture | Require explicit `desktop-linux`, exact arm64 child digest, daemon/API fingerprint, and reject emulation |
| Docker Desktop or macOS scheduling makes the controlled profile noisy | Quiet preflight/postflight, one uninterrupted VM allocation, A/A escalation through 40 forks, diagnostic-only result on failure |
| APFS file sharing contaminates loader timing | Copy frozen distributions to container-local storage and deterministically precondition before every capture |
| Host, Docker, or LinuxKit upgrade is mistaken for a compatible historical run | Record the full substrate/runtime fingerprint; rerun baseline in the same new session and reject cross-fingerprint captures |
| Corporate controls make native tools unavailable | Require no host JVM/build tool or privileged tuning; containerize the complete measured runtime and fail instead of bypassing controls |
| Host drift is mistaken for fork uncertainty | A1/A2/B same-session order, duration bound, explicit conditional-interval wording |
| API cleanup causes consumer breakage | Break is intentional; update API record/docs directly; no compatibility layer |
| FD-count probe is platform-specific or GC-sensitive | Deterministic close-tracking tests are authoritative; Unix FD probe is secondary |
| Closing a cached ZipFS breaks later classpath reads | Close per-read sources only; preserve and characterize the process-lifetime registry |
| Comparison math appears more certain than the samples allow | Minimum independent forks, raw-data retention, interval reporting, `INCONCLUSIVE` outcome |

## Relevant source locations

- `build.gradle.kts` — current JMH configuration/source-set wiring and stale Colima-specific Qodana
  comment.
- `DEVELOPMENT.md` — stale Colima-specific Qodana guidance to replace with the verified Docker
  Desktop/`desktop-linux` invocation.
- `gradle/libs.versions.toml` — JMH, Graal, Log4j, and build dependency versions.
- `src/jmh/kotlin/com/salesforce/revoman/benchmark/SandboxBenchmark.kt` — existing sandbox probe.
- `src/main/kotlin/com/salesforce/revoman/ReVoman.kt` — public lifecycle and V2/V3 dispatch.
- `src/main/kotlin/com/salesforce/revoman/input/FileUtils.kt` — ambiguous path-source ownership.
- `src/main/kotlin/com/salesforce/revoman/input/ClasspathResolver.kt` — resolved filesystem and
  process-lifetime ZipFS ownership.
- `src/main/kotlin/com/salesforce/revoman/input/json/JsonPojoUtils.kt` — path-source JSON parsing.
- `src/main/kotlin/com/salesforce/revoman/internal/postman/template/Environment.kt` — environment
  loading.
- `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Loader.kt` — V3 walk,
  parsing, conversion input, and unclosed sources.
- `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3EnvLoader.kt` — V3
  environment read.
- `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3ToV2Converter.kt` — V3
  normalization boundary.
- `src/main/kotlin/com/salesforce/revoman/internal/exe/UnmarshallResponse.kt` — response content-type
  branch exercised by the real-wire fixture.
- `src/main/kotlin/com/salesforce/revoman/testing/http/MockHttpServer.kt` — loopback server and
  bounded request ledger.
- `.github/workflows/build.yml` — ordinary CI, which currently has no performance canary.
- `.github/workflows/qodana.yml` — mutable action refs and overbroad secret/write-permission scope
  to harden by event.
- `qodana.yaml` — mutable linter tag to replace with the reviewed immutable OCI index reference.
- `.github/workflows/performance-campaign.yml` — proposed explicit GitHub ARM diagnostic adapter;
  never a claim-bearing hosted lane.
- `config/performance/runtime/*.json` — proposed runtime-image, Docker, substrate, and security
  profile declarations.
- `scripts/performance/run` — proposed thin Docker/Mac adapter; no measurement or comparison logic.
- `docs/superpowers/benchmarks/baseline.md` — current manual, unauditable benchmark workflow.

## Reproduction commands

Run these from the repository root at the starting SHA. They modify no source files; they create
normal `build/` output and a temporary diagnostic directory.

On the current machine, bare `java` resolves to a Salesforce guidance wrapper that can exit zero
without launching a JVM. The reproduction therefore names the real Corretto 21 installation.

### Invalid, fail-open JMH artifact

```bash
set -euo pipefail

EXPECTED_SHA=009bc8f4c1fe9fb7d393036616a3c3b6cd787aca
REVOMAN_JDK21=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn
REAL_JAVA="$REVOMAN_JDK21/bin/java"
REAL_JAR="$REVOMAN_JDK21/bin/jar"
JMH_ARTIFACT=build/libs/revoman-0.90.0-jmh.jar
REPRO_DIR="$(mktemp -d "${TMPDIR:-/tmp}/revoman-jmh-repro.XXXXXX")"

test "$(git rev-parse HEAD)" = "$EXPECTED_SHA"
test -x "$REAL_JAVA"
git status --short
"$REAL_JAVA" -version

./gradlew jmhJar \
  -Dorg.gradle.java.home="$REVOMAN_JDK21" \
  --rerun-tasks \
  --no-build-cache \
  --no-daemon \
  --no-configuration-cache \
  --console=plain
test -f "$JMH_ARTIFACT"
ls -lh "$JMH_ARTIFACT"

set +e
./gradlew jmh \
  -Dorg.gradle.java.home="$REVOMAN_JDK21" \
  -Pjmh.includes=SandboxBenchmark \
  --rerun-tasks \
  --no-build-cache \
  --no-daemon \
  --no-configuration-cache \
  --console=plain \
  >"$REPRO_DIR/gradle-jmh.log" 2>&1
GRADLE_STATUS=$?
set -e

echo "gradle_status=$GRADLE_STATUS"
test "$GRADLE_STATUS" -eq 0
rg -n 'BUILD SUCCESSFUL|Multi-Release classes are not configured correctly|NotInjectableException' \
  "$REPRO_DIR/gradle-jmh.log"

sed -n '1,20p' build/results/jmh/results.txt
RESULT_ROWS="$(
  awk 'NR > 1 && NF { count++ } END { print count + 0 }' \
    build/results/jmh/results.txt
)"
echo "jmh_result_rows=$RESULT_ROWS"
test "$RESULT_ROWS" -eq 0
```

Expected: the jar is approximately 92 MiB, Gradle exits zero and prints `BUILD SUCCESSFUL`, while
the JMH result contains only its header and `jmh_result_rows=0`.

Inspect and validate the jar:

```bash
unzip -p "$JMH_ARTIFACT" META-INF/MANIFEST.MF

if unzip -p "$JMH_ARTIFACT" META-INF/MANIFEST.MF |
  tr -d '\r' |
  rg -q '^Multi-Release: true$'
then
  echo "Unexpected: Multi-Release is present"
  exit 1
else
  echo "Confirmed: Multi-Release is absent"
fi

VERSIONED_MODULES="$(
  "$REAL_JAR" tf "$JMH_ARTIFACT" |
    rg '^META-INF/versions/[0-9]+/module-info\.class$' |
    wc -l |
    tr -d ' '
)"
echo "versioned_module_info_entries=$VERSIONED_MODULES"
test "$VERSIONED_MODULES" -eq 30

set +e
"$REAL_JAR" --validate --file "$JMH_ARTIFACT" \
  >"$REPRO_DIR/jar-validate.log" 2>&1
JAR_VALIDATE_STATUS=$?
set -e

echo "jar_validate_status=$JAR_VALIDATE_STATUS"
test "$JAR_VALIDATE_STATUS" -ne 0
wc -l "$REPRO_DIR/jar-validate.log"
sed -n '1,20p' "$REPRO_DIR/jar-validate.log"
tail -n 20 "$REPRO_DIR/jar-validate.log"
```

Expected: the manifest contains only `Manifest-Version` and JMH `Main-Class`, there are 30 versioned
`module-info.class` entries, and `jar --validate` exits nonzero with thousands of diagnostics.

Contrast JMH's default failure behavior with `-foe true`:

```bash
set +e
"$REAL_JAVA" -jar "$JMH_ARTIFACT" \
  '.*SandboxBenchmark.*' \
  -f 1 -wi 0 -i 1 -r 10ms \
  -rf json \
  -rff "$REPRO_DIR/fail-open.json" \
  -o "$REPRO_DIR/fail-open.txt" \
  >"$REPRO_DIR/fail-open.console" 2>&1
FAIL_OPEN_STATUS=$?
set -e

echo "fail_open_status=$FAIL_OPEN_STATUS"
test "$FAIL_OPEN_STATUS" -eq 0
test "$(tr -d '[:space:]' <"$REPRO_DIR/fail-open.json")" = '[]'
rg -n 'NotInjectableException|SimpleLogger|Multi-Release classes are not configured correctly|<failure>' \
  "$REPRO_DIR/fail-open.console" \
  "$REPRO_DIR/fail-open.txt"

set +e
"$REAL_JAVA" -jar "$JMH_ARTIFACT" \
  '.*SandboxBenchmark.*' \
  -f 1 -wi 0 -i 1 -r 10ms \
  -foe true \
  -rf json \
  -rff "$REPRO_DIR/fail-closed.json" \
  -o "$REPRO_DIR/fail-closed.txt" \
  >"$REPRO_DIR/fail-closed.console" 2>&1
FAIL_CLOSED_STATUS=$?
set -e

echo "fail_closed_status=$FAIL_CLOSED_STATUS"
test "$FAIL_CLOSED_STATUS" -ne 0
test ! -s "$REPRO_DIR/fail-closed.json"
rg -n 'Benchmark caught the exception|Multi-Release classes are not configured correctly|NotInjectableException|<failure>' \
  "$REPRO_DIR/fail-closed.console" \
  "$REPRO_DIR/fail-closed.txt"
```

Expected: the default run exits zero with JSON `[]`; the fail-on-error run exits nonzero and leaves
the JSON result absent or empty. This is why both fail-on-error and explicit row validation are
required.

### File-descriptor leak

The descriptor probe uses the dependency-complete current JMH jar only as a classpath. It does not
execute Graal, so the jar's known manifest defect does not affect this probe.

```bash
"$REVOMAN_JDK21/bin/jshell" \
  --class-path "$JMH_ARTIFACT" <<'EOF'
import com.salesforce.revoman.input.FileUtils;
import com.sun.management.UnixOperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;

var os = (UnixOperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
var file = Path.of("README.adoc").toAbsolutePath().toString();

var before = os.getOpenFileDescriptorCount();
for (int i = 0; i < 200; i++) FileUtils.readFileToString(file);
var afterReads = os.getOpenFileDescriptorCount();

System.gc();
Thread.sleep(1000);
var afterGc = os.getOpenFileDescriptorCount();

System.out.printf(
    "before=%d after_200_reads=%d after_gc=%d delta=%d%n",
    before, afterReads, afterGc, afterReads - before);
/exit
EOF
```

Verified on macOS/Apple Silicon with Corretto 21.0.11:

```text
before=10 after_200_reads=210 after_gc=10 delta=200
```

Absolute counts can vary. The defining observation is approximately 200 additional open
descriptors after 200 reads without GC, followed by recovery only after GC-driven cleanup. The
probe requires a Unix-like JDK exposing `UnixOperatingSystemMXBean`; deterministic ownership tests
remain authoritative on every platform.
