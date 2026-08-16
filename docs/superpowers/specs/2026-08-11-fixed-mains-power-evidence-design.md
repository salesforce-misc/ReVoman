# Fixed-Mains Power Evidence Design

**Status:** Approved for implementation on 2026-08-11

## Context

The controlled Linux benchmark probe currently requires `/sys/class/power_supply` evidence and
stores the result as `onAcPower: Boolean`. That interface cannot truthfully represent an
always-powered bare-metal desktop whose kernel exposes an empty power-supply directory. Treating
that host as `onAcPower = true` would fabricate a sampled observation. The synthetic smoke probe
already does exactly that even though smoke evidence is intentionally not release-eligible.

No controlled `revoman-benchmark/v1` evidence has been published yet. The first v1 baseline is
still blocked on Task 13, so this design corrects the v1 interface before its first measured
evidence rather than creating a second protocol version or preserving an ambiguous Boolean.

## Chosen Interface

Replace `ControlledHostPolicy.requireAcPower` with a required `powerEvidenceRequirement` enum:

- `OBSERVE_EXTERNAL_POWER` reads Linux power-supply sysfs and records online or offline without
  rejecting an offline sample solely for power.
- `REQUIRE_EXTERNAL_POWER` reads Linux power-supply sysfs and requires every sample to report an
  online external source.
- `FIXED_MAINS` is an administrator-owned, host-specific attestation that runtime power telemetry
  is not applicable because the machine is hardwired to mains power.

Replace `HostHealthSnapshot.onAcPower` with a required `powerEvidence` enum:

- `EXTERNAL_POWER_ONLINE`
- `EXTERNAL_POWER_OFFLINE`
- `FIXED_MAINS`
- `UNAVAILABLE`

The policy requirement describes how evidence must be obtained and evaluated. The snapshot value
records what kind of evidence actually backed that sample. This keeps policy and evidence distinct
without exposing a nullable Boolean or a multi-field combination callers could make inconsistent.

## Probe and Gate Behaviour

For `OBSERVE_EXTERNAL_POWER` and `REQUIRE_EXTERNAL_POWER`, `LinuxHostProbe` preserves the existing
strict sysfs parser. Missing directories, missing or unknown types, malformed `online` values, and
the absence of an external source fail closed. The probe emits `EXTERNAL_POWER_ONLINE` when any
supported external source is online and `EXTERNAL_POWER_OFFLINE` otherwise.

For `FIXED_MAINS`, the probe requires `/sys/class/power_supply` to exist and contain no entries. It
does not synthesize an online observation and emits `FIXED_MAINS`. Any entry makes the attestation
inapplicable and fails closed, preventing this mode from becoming a general bypass for laptops,
batteries, UPS devices, or available runtime telemetry.

`HostHealthGate` applies these rules to every before/during/after sample:

- `REQUIRE_EXTERNAL_POWER` accepts only `EXTERNAL_POWER_ONLINE`.
- `FIXED_MAINS` accepts only `FIXED_MAINS`.
- `OBSERVE_EXTERNAL_POWER` records power evidence without making it a health rejection condition.
  A controlled Linux probe still fails before the gate if sysfs evidence is missing or malformed;
  only the synthetic smoke probe emits `UNAVAILABLE`.

The synthetic smoke probe emits `UNAVAILABLE`. Its internal structural policy accepts that state,
but smoke remains ineligible for release gates. Controlled Linux probes never emit `UNAVAILABLE`.
Stable machine-readable rejection reasons distinguish missing required external power from a
policy/evidence-mode mismatch.

## Schema and Compatibility

This is an intentional breaking correction to the benchmark v1 protocol:

- Update `revoman-controlled-host/v1` to require `powerEvidenceRequirement` and reject the removed
  `requireAcPower` field.
- Update `revoman-benchmark/v1` host-health snapshots to require `powerEvidence` and reject the
  removed `onAcPower` field.
- Update canonical JSON fixtures, generated adapters, installed schema checks, documentation, and
  provider/configuration hashes affected by the canonical policy bytes.

Creating v2 now would double schema, decoder, verifier, comparison, and installation support for a
v1 format that has no valid controlled evidence. Retaining both a mode and nullable Boolean would
permit contradictory states. The major release and pre-baseline state make the narrow v1
correction the safer interface.

## Verification

Test-first coverage must prove:

1. old Boolean policy/result fields fail strict schema decoding;
2. observed online/offline sysfs evidence is recorded exactly and required-online rejects offline;
3. `FIXED_MAINS` succeeds only with an existing empty power-supply directory;
4. any power-supply entry rejects `FIXED_MAINS`;
5. missing, unknown, and malformed observed sysfs evidence still fails closed;
6. gate decisions reject mismatched evidence for required-external and fixed-mains policies;
7. smoke records `UNAVAILABLE` rather than fabricated healthy power evidence;
8. policy and result JSON round-trip canonically and validate against their strict schemas; and
9. existing benchmark-driver unit, integration, packaging, harness, formatting, Detekt, and Qodana
   gates remain green.

After the local change is reviewed, committed, merged, and pushed, the controlled host checkout
must use that exact clean SHA. Its policy will select `FIXED_MAINS`; the canonical policy hash and
every captured health snapshot will therefore preserve the attestation explicitly in Task 13
evidence.

## Non-Goals

- Inferring fixed mains from chassis type, hostname, missing files, or a command-line flag.
- Treating peripheral batteries, UPS devices, or unknown power-supply types as fixed mains.
- Adding a macOS controlled-host probe.
- Changing Task 13 sample counts, thresholds, seed, workload, or release gates.
