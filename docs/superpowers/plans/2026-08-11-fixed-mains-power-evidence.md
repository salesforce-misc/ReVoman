# Fixed-Mains Power Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Represent observed external power, fixed-mains attestation, and unavailable smoke evidence truthfully so the controlled Linux Task 13 host can capture release evidence without fabricating `onAcPower=true`.

**Architecture:** Replace the overloaded policy and result Booleans with two enums at the existing policy and host-health seams. `LinuxHostProbe` remains the sole controlled adapter: observed modes retain strict sysfs parsing, while fixed mains is accepted only for an existing empty power-supply directory. `HostHealthGate` evaluates the recorded enum against the policy requirement, and the synthetic smoke adapter records `UNAVAILABLE` without becoming controlled-eligible.

**Tech Stack:** Kotlin 2.4, Java 21, Moshi code generation, JSON Schema draft 2020-12, JUnit 5, Truth, Gradle 9.7, Qodana.

## Global Constraints

- This intentionally corrects `revoman-controlled-host/v1` and `revoman-benchmark/v1` before the first controlled v1 evidence is published; do not add v2 compatibility machinery.
- Replace `requireAcPower` and `onAcPower`; do not retain nullable or deprecated compatibility fields.
- Exact policy enum values: `OBSERVE_EXTERNAL_POWER`, `REQUIRE_EXTERNAL_POWER`, `FIXED_MAINS`.
- Exact sample enum values: `EXTERNAL_POWER_ONLINE`, `EXTERNAL_POWER_OFFLINE`, `FIXED_MAINS`, `UNAVAILABLE`.
- `FIXED_MAINS` is valid only when `/sys/class/power_supply` exists and has no entries; it is never inferred from a missing path, hostname, chassis type, or command-line flag.
- Observed controlled probing remains fail-closed for missing, unknown, or malformed sysfs evidence.
- Synthetic smoke records `UNAVAILABLE`; it remains structurally useful and release-ineligible.
- Do not change Task 13 counts, seed `5928239383101656625`, workload, thresholds, or release-gate rules.
- Do not add benchmark-path logging; the evidence enum and stable rejection reasons are the diagnostic surface.
- Preserve the unrelated untracked `.superpowers/` and `docs/revoman-graphalow-licensing-brief.md` in the primary checkout.

---

### Task 1: Replace Boolean power state with explicit policy and evidence enums

**Files:**
- Modify: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/host/ControlledHostPolicy.kt`
- Modify: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/host/LinuxHostProbe.kt`
- Modify: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/host/HostHealthGate.kt`
- Modify: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/model/BenchmarkResultV1.kt`
- Modify: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/run/BenchmarkCampaign.kt`
- Modify: `benchmark-driver/src/main/resources/schema/revoman-controlled-host-v1.schema.json`
- Modify: `benchmark-driver/src/main/resources/schema/revoman-benchmark-v1.schema.json`
- Modify: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/host/HostHealthGateTest.kt`
- Modify: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/model/BenchmarkResultSchemaTest.kt`
- Modify: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/run/BenchmarkCampaignTest.kt`
- Modify: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/run/AlternatingBlockSchedulerTest.kt`
- Modify: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/stats/StatisticsTest.kt`
- Modify: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/stats/TheilSenTest.kt`
- Modify: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/compare/ComparisonFixtures.kt`
- Modify: `benchmark-driver/src/test/resources/host/*.json`
- Modify: `benchmark-driver/src/test/resources/results/v1/*.json`
- Modify: `DEVELOPMENT.md`
- Modify: `docs/modules/ROOT/pages/performance.adoc`
- Modify: `docs/superpowers/benchmarks/baseline.md`
- Modify: `docs/superpowers/plans/2026-08-09-performance-cs1-benchmark-foundation.md`

**Interfaces:**
- Consumes: strict policy loading through `ControlledHostPolicy.load(Path)`, controlled sampling through `HostHealthProbe`, paired health evaluation through `HostHealthGate.assess`, and canonical result validation through `BenchmarkResultV1.validate()`.
- Produces: `PowerEvidenceRequirement`, `PowerEvidence`, strict v1 JSON representations, fixed-mains sampling for Task 13, and truthful smoke evidence.

- [ ] **Step 1: Add failing policy, probe, gate, smoke, and schema tests**

Add the exact enums to test expectations before production types exist:

```kotlin
enum class PowerEvidenceRequirement {
    OBSERVE_EXTERNAL_POWER,
    REQUIRE_EXTERNAL_POWER,
    FIXED_MAINS,
}

enum class PowerEvidence {
    EXTERNAL_POWER_ONLINE,
    EXTERNAL_POWER_OFFLINE,
    FIXED_MAINS,
    UNAVAILABLE,
}
```

In `HostHealthGateTest`, add focused tests with these behavioural assertions:

```kotlin
@Test
fun `fixed mains records explicit evidence only for an empty power supply directory`() {
    val root = temporaryDirectory.resolve("fixed-mains")
    writeLinuxHost(root)
    root.resolve("sys/class/power_supply").toFile().deleteRecursively()
    Files.createDirectories(root.resolve("sys/class/power_supply"))

    val snapshot =
        linuxProbe(root, requirement = PowerEvidenceRequirement.FIXED_MAINS).sample()

    assertThat(snapshot.powerEvidence).isEqualTo(PowerEvidence.FIXED_MAINS)
}

@Test
fun `fixed mains rejects every power supply entry instead of bypassing telemetry`() {
    val root = temporaryDirectory.resolve("fixed-mains-with-entry")
    writeLinuxHost(root)

    val failure = assertThrows<IllegalStateException> {
        linuxProbe(root, requirement = PowerEvidenceRequirement.FIXED_MAINS).sample()
    }

    assertThat(failure).hasMessageThat().contains("FIXED_MAINS")
    assertThat(failure).hasMessageThat().contains("empty")
}

@Test
fun `observed external power records online and offline while required power rejects offline`() {
    val root = temporaryDirectory.resolve("observed-power")
    writeLinuxHost(root)
    val online =
        linuxProbe(root, requirement = PowerEvidenceRequirement.OBSERVE_EXTERNAL_POWER).sample()
    write(root, "sys/class/power_supply/AC/online", "0\n")
    val offline =
        linuxProbe(root, requirement = PowerEvidenceRequirement.OBSERVE_EXTERNAL_POWER).sample()

    assertThat(online.powerEvidence).isEqualTo(PowerEvidence.EXTERNAL_POWER_ONLINE)
    assertThat(offline.powerEvidence).isEqualTo(PowerEvidence.EXTERNAL_POWER_OFFLINE)
    assertThat(
            HostHealthGate(
                    linuxPolicy()
                        .copy(
                            powerEvidenceRequirement =
                                PowerEvidenceRequirement.REQUIRE_EXTERNAL_POWER
                        )
                )
                .assess(offline, listOf(offline), offline)
                .reasons
        )
        .containsExactly(HostHealthReason.EXTERNAL_POWER_REQUIRED)
}
```

Also add tests that:

- strict policy schema rejects `requireAcPower` and requires `powerEvidenceRequirement`;
- strict result schema rejects `onAcPower` and requires `powerEvidence`;
- `FIXED_MAINS` gate rejects observed/unavailable samples with
  `HostHealthReason.POWER_EVIDENCE_MISMATCH`;
- observed Linux modes retain the existing missing/unknown/malformed failures; and
- `BenchmarkCampaign` smoke evidence contains only `PowerEvidence.UNAVAILABLE` and remains
  release-ineligible.

- [ ] **Step 2: Run the focused tests and capture the missing-contract RED**

Run:

```bash
./gradlew :benchmark-driver:test \
  --tests '*HostHealthGateTest' \
  --tests '*BenchmarkResultSchemaTest' \
  --tests '*BenchmarkCampaignTest' \
  --rerun-tasks --no-build-cache --console=plain
```

Expected: `compileTestKotlin` fails on missing `PowerEvidenceRequirement`, `PowerEvidence`,
`powerEvidenceRequirement`, and `powerEvidence` symbols. If it fails for syntax or fixture setup,
fix the test and rerun until the failure is solely the absent production contract.

- [ ] **Step 3: Add the policy requirement and sample evidence types**

In `ControlledHostPolicy.kt`, add:

```kotlin
enum class PowerEvidenceRequirement {
    OBSERVE_EXTERNAL_POWER,
    REQUIRE_EXTERNAL_POWER,
    FIXED_MAINS,
}
```

Replace:

```kotlin
val requireAcPower: Boolean,
```

with:

```kotlin
val powerEvidenceRequirement: PowerEvidenceRequirement,
```

In `BenchmarkResultV1.kt`, add:

```kotlin
enum class PowerEvidence {
    EXTERNAL_POWER_ONLINE,
    EXTERNAL_POWER_OFFLINE,
    FIXED_MAINS,
    UNAVAILABLE,
}
```

Replace `HostHealthSnapshot.onAcPower` with:

```kotlin
val powerEvidence: PowerEvidence,
```

- [ ] **Step 4: Make the controlled Linux probe produce explicit evidence**

Replace `readOnAcPower()` with a dispatcher that reads the policy requirement:

```kotlin
private fun readPowerEvidence(): PowerEvidence =
    when (policy.powerEvidenceRequirement) {
        PowerEvidenceRequirement.FIXED_MAINS -> readFixedMainsEvidence()
        PowerEvidenceRequirement.OBSERVE_EXTERNAL_POWER,
        PowerEvidenceRequirement.REQUIRE_EXTERNAL_POWER -> readExternalPowerEvidence()
    }
```

`readFixedMainsEvidence()` must require the power-supply directory and reject any entry, including
files and symlinks, before returning `PowerEvidence.FIXED_MAINS`:

```kotlin
private fun readFixedMainsEvidence(): PowerEvidence {
    val supplyRoot = resolve("sys/class/power_supply")
    check(Files.isDirectory(supplyRoot)) {
        "FIXED_MAINS requires the Linux power-supply directory: $supplyRoot"
    }
    Files.newDirectoryStream(supplyRoot).use { entries ->
        check(!entries.iterator().hasNext()) {
            "FIXED_MAINS requires an empty Linux power-supply directory: $supplyRoot"
        }
    }
    return PowerEvidence.FIXED_MAINS
}
```

Keep the existing strict type and `online` parsing in `readExternalPowerEvidence()`, returning
`EXTERNAL_POWER_ONLINE` or `EXTERNAL_POWER_OFFLINE`. Do not catch its failures or fall back to fixed
mains. Set `HostHealthSnapshot.powerEvidence = readPowerEvidence()` in `snapshot()`.

- [ ] **Step 5: Evaluate required evidence with stable reasons**

Replace `HostHealthReason.AC_POWER_REQUIRED` with:

```kotlin
const val EXTERNAL_POWER_REQUIRED: String = "external-power-required"
const val POWER_EVIDENCE_MISMATCH: String = "power-evidence-mismatch"
```

In `HostHealthGate.assess`, preserve the current reason ordering position and use:

```kotlin
when (policy.powerEvidenceRequirement) {
    PowerEvidenceRequirement.OBSERVE_EXTERNAL_POWER -> Unit
    PowerEvidenceRequirement.REQUIRE_EXTERNAL_POWER ->
        if (samples.any { it.powerEvidence != PowerEvidence.EXTERNAL_POWER_ONLINE }) {
            add(HostHealthReason.EXTERNAL_POWER_REQUIRED)
        }
    PowerEvidenceRequirement.FIXED_MAINS ->
        if (samples.any { it.powerEvidence != PowerEvidence.FIXED_MAINS }) {
            add(HostHealthReason.POWER_EVIDENCE_MISMATCH)
        }
}
```

Do not reject `UNAVAILABLE` for the internal smoke `OBSERVE_EXTERNAL_POWER` policy. Controlled Linux
observation remains strict because `LinuxHostProbe` never produces `UNAVAILABLE`.

- [ ] **Step 6: Remove synthetic healthy power and migrate Kotlin constructors**

Change `SyntheticHostProbe.health()` to:

```kotlin
powerEvidence = PowerEvidence.UNAVAILABLE,
```

and `smokePolicy()` to:

```kotlin
powerEvidenceRequirement = PowerEvidenceRequirement.OBSERVE_EXTERNAL_POWER,
```

Mechanically replace every production/test `HostHealthSnapshot(onAcPower = true)` with the exact
appropriate enum, using `EXTERNAL_POWER_OFFLINE` only for the battery fixture and `UNAVAILABLE`
only for synthetic smoke. Replace policy constructors using `requireAcPower = true/false` with
`REQUIRE_EXTERNAL_POWER`/`OBSERVE_EXTERNAL_POWER` respectively. Do not leave either old identifier
in production, tests, JSON, or documentation.

- [ ] **Step 7: Make both strict v1 schemas encode only the new fields**

In `revoman-controlled-host-v1.schema.json`, replace required/property `requireAcPower` with:

```json
"powerEvidenceRequirement": {
  "enum": ["OBSERVE_EXTERNAL_POWER", "REQUIRE_EXTERNAL_POWER", "FIXED_MAINS"]
}
```

In `revoman-benchmark-v1.schema.json`, replace required/property `onAcPower` with:

```json
"powerEvidence": {
  "enum": [
    "EXTERNAL_POWER_ONLINE",
    "EXTERNAL_POWER_OFFLINE",
    "FIXED_MAINS",
    "UNAVAILABLE"
  ]
}
```

Update every host/result JSON fixture canonically. Preserve each fixture's intended invalidity: do
not accidentally repair `invalid-count`, `invalid-missing-hash`, `invalid-unknown-field`, or
`invalid-both-sample-forms` beyond the power-field migration.

- [ ] **Step 8: Run focused GREEN and mutation checks**

Run the Step 2 command. Expected: all focused tests pass.

Then temporarily mutate each production contract, one at a time, and prove its owning test fails:

1. let `FIXED_MAINS` accept a nonempty directory;
2. emit `EXTERNAL_POWER_ONLINE` from `SyntheticHostProbe`; and
3. omit the fixed-mains gate mismatch check.

Restore each mutation immediately and rerun its owning test to green. Record exact commands and
failure names in the task report; do not commit mutations.

- [ ] **Step 9: Update operator and evidence documentation**

Update `DEVELOPMENT.md`, `performance.adoc`, `baseline.md`, and the still-active Task 13 section of
the CS1 plan to state:

- observed external power is runtime sysfs evidence;
- fixed mains is an administrator-owned host-specific attestation with runtime telemetry marked
  not applicable;
- fixed mains requires an existing empty `/sys/class/power_supply` directory;
- smoke records unavailable power evidence and cannot support release claims; and
- the Task 13 policy for `gopalaaksh-wsl3` uses
  `"powerEvidenceRequirement": "FIXED_MAINS"` rather than `requireAcPower`.

Do not add measured values, provisional thresholds, or a performance claim.

- [ ] **Step 10: Run proportional repository verification**

From a clean worktree, export a current target manifest if absent, then run serially:

```bash
./gradlew clean writeBenchmarkTargetManifest \
  -I benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts \
  -Pbenchmark.targetId=current-fixed-mains \
  -Pbenchmark.targetManifest=build/benchmark-target-current.json \
  --no-build-cache --no-configuration-cache --console=plain

./gradlew :benchmark-driver:test :benchmark-driver:integrationTest \
  -Pbenchmark.targetManifest="$PWD/build/benchmark-target-current.json" \
  -Pbenchmark.adapter=baseline-83f3cd70 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

./gradlew :benchmark-driver:benchmarkHarnessSelfTest \
  :benchmark-driver:jmhClasses :benchmark-driver:installDist \
  spotlessCheck detekt \
  -Pbenchmark.targetManifest="$PWD/build/benchmark-target-current.json" \
  -Pbenchmark.adapter=baseline-83f3cd70 \
  --rerun-tasks --no-build-cache --console=plain

./gradlew qodanaScan \
  -Pbenchmark.targetManifest="$PWD/build/benchmark-target-current.json" \
  -Pbenchmark.adapter=baseline-83f3cd70 \
  --rerun-tasks --no-build-cache --console=plain
```

Keep Qodana in the separate serial invocation. Under Gradle 9.7, `qodanaScan` declares the
repository root as an input while Detekt and Spotless write outputs beneath that root, so
co-scheduling those tasks fails Gradle's implicit-dependency validation before analysis. The
spec owner approved serial execution of the same constituent gates; changing global build wiring
is unrelated scope.

Also run:

```bash
git diff --check
rg -n 'requireAcPower|onAcPower' \
  benchmark-driver/src DEVELOPMENT.md docs/modules/ROOT/pages/performance.adoc \
  docs/superpowers/benchmarks/baseline.md \
  docs/superpowers/plans/2026-08-09-performance-cs1-benchmark-foundation.md
```

Expected: every Gradle gate passes; the final `rg` returns no matches; no live benchmark child
processes or Task 13 evidence files remain from tests.

- [ ] **Step 11: Review, commit, merge, push, and confirm CI**

Request a fresh Standards + Spec review over the complete diff. Resolve all Critical/Important
findings with focused RED/GREEN tests and one scoped re-review. Stage only the design, plan, power
contract implementation, fixtures, and documentation, then commit:

```bash
git add \
  docs/superpowers/specs/2026-08-11-fixed-mains-power-evidence-design.md \
  docs/superpowers/plans/2026-08-11-fixed-mains-power-evidence.md \
  benchmark-driver DEVELOPMENT.md docs/modules/ROOT/pages/performance.adoc \
  docs/superpowers/benchmarks/baseline.md \
  docs/superpowers/plans/2026-08-09-performance-cs1-benchmark-foundation.md
git commit -m "feat: record fixed mains power evidence"
```

Merge the reviewed branch into local `master`, push `master`, and confirm Build, Qodana, and Docs
workflows are green at the exact pushed SHA before provisioning the remote controlled host.
