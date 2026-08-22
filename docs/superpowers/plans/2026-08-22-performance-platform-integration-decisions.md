# Performance platform integration decisions

**Date:** 2026-08-22

**Status:** Ready for whole-branch review and merge gate

## History and scope

PR A starts at exact master commit `50ded34b78b416a5523ebb57d1ce77a03e691912`.
Commit `e6d3e79d0ec3bc674835ef22fd62ad3e7775ab3b` is the required true merge. Its
parents are the master base and platform tip
`e96e6cbae05d57e4ce368c5d2cd31a85c6cf63f0`, in that order. Commit
`cd2ce54f8981872e41338a370f8775f825e81c07` repairs inherited verification
fixtures without changing production code. The authoritative specification and three plans were
then cherry-picked as `52c79be79f32776dc008fbaac0cd15d65d2ea417`.

Task 3 retires only these legacy entry points:

- `.github/workflows/benchmark.yml`
- `Dockerfile.perf`
- `scripts/compare-jmh.py`
- `scripts/tests/test_compare_jmh.py`
- `scripts/perf-docker`

Historical benchmark reports and evidence artifacts under `docs/superpowers/benchmarks/` remain
untouched. The branch has no diff from the exact master base under `src/main`, `src/test`, or
`src/integrationTest`.

## Inherited platform repairs

The follow-up repair keeps the production performance policy intact and fixes test portability:

- Renderer goldens use fixed synthetic evidence identities after validating a real checksum-closed
  comparison. Executing-JDK hashes no longer make presentation goldens vendor-dependent.
- Artifact adapter tests use a local 10-second Docker watchdog. Production finalization stays at
  900,000 milliseconds.
- Docker bind fixtures live under the checkout's Docker-visible `build/` directory.
- Lock tests accept GNU and BSD `stat` syntax.
- The adapter policy parser uses a GNU AWK-safe quoted-string expression.

The timeout regression now observes the same local override used by every `invokeArtifact` call.
It checks the 10,000-millisecond default and a 5-millisecond explicit override.

## Runner visibility boundary

Cross-project import inventory found one small bridge from `performance-runner` into build logic:
`Sha256`, `CanonicalJson`, `JavaRuntimeIdentity`, `DistributionValidationRequest`,
`DistributionValidation`, `DistributionValidator`, and `RunnerExit`. The validated-distribution
proof and its immutable metadata value types remain public because
`DistributionValidation.Valid` exposes that proof. These intentional APIs have KDoc.

All other default-public top-level runner declarations are now `internal`. The CLI
`performance.cli.PerformanceRunnerMainKt.main(String[])` remains callable and documented. A
buildSrc test reaches the already-internal atomic publisher by reflection; that fixture does not
justify widening the Kotlin API.

## Java and frozen distributions

Java support is exactly major version 21. Vendor is not a routing key or allowlist. Every campaign
still binds the complete checked-in JDK identity, so comparisons require exact JDK equality even
though another reviewed Java 21 vendor profile is valid platform input.

The formal campaign uses one role-neutral three-distribution chain:

| Distribution | Production commit | Harness |
|---|---|---|
| D1 | `d343df32d0b258cd5f37ab2606eb773e55b0ea6d` | Clean merged-master runner |
| D2 | `9439dc416ca7676c1f501a93924d7d3900f33e16` | D1 via `--harness-from` |
| D3 | `d42614fa4982d8f960354ba07a2027f84b5ef1bc` | D2 via `--harness-from` |

D2 is the candidate for request bootstrap and the baseline for lazy Ajv. Campaign state and A/A
evidence are never reused between those two edges.

## Evidence and workflow gates

GitHub build and manual performance workflows run structural canaries only. Their outputs are
diagnostic and `ClaimEligible=false`. Workflow contracts retain full action SHA pins,
credential-free checkout, token scrubbing around the runner, and uploads rooted at the sanitized
`build/performance` tree. Qodana keeps its immutable index digest, architecture-specific child
digests, and secretless pull-request job.

Claim-bearing evidence requires merged PR A and PR B ancestry, exact clean distributions, dynamic
host qualification, same-session A/A admission, immediate B execution, recursive checksums, and
independent evidence review. No hosted canary, standalone comparison, GC capture, JFR capture, or
old report can satisfy those gates. This integration runs no formal measurements.

## Verification rulings

Every Gradle command in the four new design and plan documents, the in-scope workflows, and this
record uses `-q` or `--quiet`. IntelliJ or another IDE may still send `SIGTERM` to a long-running
Gradle process. A terminated process is never accepted as a result. The gate requires a natural
exit code of zero, fresh test reports, and no owned test process left running.

An attempted verification path ending in `java/21.0.2` was invalid, and subsequent ambient
Corretto 21 runs did not bind the selected host runtime. Neither is accepted as final evidence.
The final host-side compile, focused tests, ABI check, and formatting check use the installed
Temurin 21.0.12 home declared by the integration plans; Qodana remains separate container evidence.

The merged build uses Kotlin's ABI validation tasks. `apiDump` and `apiCheck` do not exist. The
plans now name `updateKotlinAbi` and `checkKotlinAbi`, and `api/revoman-root.api` is regenerated from
the unchanged master production source.

The API generator owns a blank separator at EOF. Removing it makes `checkKotlinAbi` fail byte
comparison, so the review request to remove that blank is rejected. Whitespace checks exclude only
`api/revoman-root.api`; all other changed files must pass `git diff --check`. This also corrects the
Task 1-2 report's broader claim that the merged tree had no whitespace findings.

## Review closure

The legacy-path contract was added first and failed on the still-present benchmark workflow. All
five paths were confirmed present before deletion, and the same contract passed after deletion.
The workflow quiet-mode requirement also ran red before the workflow commands gained `--quiet`.
The strengthened artifact-timeout test failed to compile before its shared helper existed, then
passed through the real local override.

The remaining review minors are closed in place: import ordering in the renderer golden,
continuation indentation in the Docker runtime test, `when` use in `CaptureBundleVerifier`, and
range-based `substring` calls in `JarValidator`. Qodana's first pass identified an array-valued
private data class in the inherited V3 benchmark fixture; it has no value-equality consumer, so it
is now a regular private class. Focused compilation covers both buildSrc and performance-runner,
followed by their focused tests, formatting, ABI validation, distribution validation, and static
analysis.

The first clean-tree distribution verification also rejected two current-master lifecycle
benchmarks as unexpected. Both are deliberately retained in the JMH jar, so the frozen
distribution allowlist now names `scriptFreeOneStep` and `scriptedOneStep` and exact-set validation
remains enabled.

## Whole-branch gate corrections

The standards review found no remaining Critical, Important, or Minor issue. The specification
review found one Important documentation gap: `README.adoc` and the published performance page had
not received the formal-versus-diagnostic evidence boundary promised by the integration plan. Both
public surfaces now state that only finalized, checksum-valid controlled-host campaigns with
`claimEligible=true` can support a claim. Hosted canaries, direct JMH, standalone comparisons,
GC/JFR investigations, application elapsed time, and historical reports remain diagnostic.

The exact combined Gradle gate exposed a separate task-input defect. The protocol-manifest task's
`captureRunnerSourceDirectory` points at the repository root only so checked-in closure files can
receive stable relative names. Annotating that path context as an `InputDirectory` recursively
made every build output under the checkout an undeclared input and created false dependencies on
kapt, Node setup, and Kotlin ABI tasks. Every actual closure source is already declared through
`protocolSources`, while compiled artifacts and dependencies have their own inputs. The root
property is therefore `@Internal`, while the normalized root-relative mapping of every declared
protocol source is a scalar `@Input`. That mapping changes exactly when the root would change
containment or manifest logical paths, but remains stable when an equivalent checkout is relocated.
Adding ordering dependencies would retain the incorrect input model and couple the manifest to
unrelated build work. An annotation contract and a behavioral build-cache contract cover this
boundary: changing only the configured mapping forces manifest regeneration and changes its
logical paths; repeating the same mapping is up to date. The clean-tree distribution gate remains
the end-to-end regression for combined-task execution.

## Protected root observation

Read-only inspection found the protected root still at
`47d03c0fc3b0b01ac06d7a3a80bf925ae5ce201e`. Its `.idea/kotlinc.xml` hash is still
`c995703f125cf3ad057ffdd509b211bd3a5533c22307a17323fef86cb3c9b694`. IntelliJ or Eclipse tooling
has also modified `.classpath` and `.settings/org.eclipse.buildship.core.prefs` outside this
worktree. This task records that external IDE drift and does not edit, stage, reset, or otherwise
modify the protected root.
