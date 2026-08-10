# ReVoman v1 Performance Evidence Protocol

ReVoman accepts comparative performance claims only from schema-validated v1 evidence captured on
the controlled host. The protocol treats cold standalone execution and warm repeated execution in
a long-lived JVM as equal release targets. This document describes how evidence is produced; it
does not claim that a candidate is faster.

## Fixed baseline and deterministic workload

Every release campaign rebuilds and measures this full baseline commit in a clean checkout:

```text
83f3cd70f78ad733412d10cbc8287aaabafe7aac
```

End-to-end measurement uses the packaged `lifecycle.no-script-one-step.v1` workload. Its HTTP
fixture is an in-process deterministic loopback server, so the protocol does not depend on a live
organization, public API, or external network. Component-level JMH uses the separately packaged
`jmh.component-operations.v1` workload and remains single-target evidence unless the campaign
driver attaches raw fork observations to real alternating block/role coordinates.

The fixed baseline is not a historical denominator. Each candidate campaign checks out
`baseline-a` and `baseline-b` independently at the full SHA, exports a fresh manifest for each, and
runs cold and warm A/A before candidate measurement. The candidate is measured only when both A/A
comparisons are `PASS`. A non-PASS A/A result makes the campaign `INCONCLUSIVE`; thresholds are not
relaxed and unfavorable observations are not deleted.

## Controlled-host rules

- Start the manual-only `Controlled performance benchmark` workflow with a separate full 40-character
  harness commit SHA, candidate ref, versioned candidate adapter, and absolute administrator-owned
  host-policy path.
- Use the protected `performance` environment and the
  `[self-hosted, linux, revoman-controlled-benchmark]` runner labels. Ordinary push/PR CI performs
  structural checks only.
- Require `/opt/revoman-benchmark/runs` to be pre-provisioned and writable. Each workflow attempt
  creates exactly one previously absent
  `/opt/revoman-benchmark/runs/${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}`. Measured artifact
  directories are never restored from a build cache, reused, or globbed from an earlier run.
- Keep the harness, `baseline-a`, `baseline-b`, and candidate as separate clean checkouts. Build the
  installed driver only from the fixed harness checkout and export every target with its own Gradle
  wrapper plus the installed init script. Never edit or reuse one target manifest for another role.
- Run 50 cold blocks and five warm blocks, with one independent fork per role in every accepted
  block. Use seed `5928239383101656625`; use the cold/warm warmup, iteration, and provider settings
  documented in `DEVELOPMENT.md` and encoded by the workflow semantic test.
- Pass the supplied policy path, quoted, to every controlled command. Workflow code never creates,
  substitutes, or weakens controlled-host policy.
- Preserve result and JFR files on every exit, including failed and inconclusive campaigns.

## Identity, provider, and hash requirements

A v1 campaign is auditable only when its JSON validates and binds all of these inputs:

- full clean harness commit/tree and installed distribution artifact hashes;
- full clean target commits/trees, original ordered target JAR classpaths, sizes, and SHA-256 hashes;
- separately pinned baseline and candidate adapter IDs/hashes;
- workload contract and deterministic fixture-tree hashes;
- Gradle version, wrapper hash, JDK distribution/vendor/full version, and relevant JVM flags;
- controlled-host fingerprint and administrator-provisioned policy SHA-256; and
- metric provider, provider-configuration SHA-256, unit, block, role, fork, process, and pairing
  coordinates.

Run the installed driver's `verify` command on each paired campaign or normalized JMH result before
publishing it. Run `compare ... --enforce-release-gates` separately for cold and warm A/A, then for
cold and warm baseline-versus-candidate evidence. Comparison reports are outputs, never substitutes
for their referenced machine-readable results.

Exact build, export, smoke, controlled-campaign, comparison, and verification commands are in
[`DEVELOPMENT.md`](../../../DEVELOPMENT.md). The workflow contract is guarded by
`BenchmarkWorkflowTest`, including independent refs, fixed SHA, three target IDs, A/A ordering,
distinct paths, supplied adapter/policy, release enforcement, and unconditional evidence upload.

## Evidence locations and legacy output

Validated captures belong under:

```text
docs/superpowers/benchmarks/results/v1/baseline-83f3cd70/
```

That directory is populated only after a successful controlled capture. Until it contains verified
machine-readable results and their recorded hashes/providers, this repository makes no v1 measured
performance claim.

`docs/superpowers/benchmarks/results/491ea968-smoke.txt` is legacy human-readable smoke output. The
old harness let INFO logging contaminate measurements and did not provide the v1 target, harness,
fixture, provider, host-policy, or schema/hash guarantees. Keep the file only as historical harness
debugging evidence; never relabel, normalize, compare, or cite it as v1 evidence.
