/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.host

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.HostHealthSnapshot
import com.squareup.moshi.JsonDataException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class HostHealthGateTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `controlled host policy fixture validates strictly and round trips canonically`() {
        val source = resourcePath("/host/valid.json")
        BenchmarkJson.validateSchema(source, CONTROLLED_HOST_SCHEMA_RESOURCE)

        val policy = ControlledHostPolicy.load(source).policy
        val first = temporaryDirectory.resolve("first.json")
        val second = temporaryDirectory.resolve("second.json")
        BenchmarkJson.write(first, policy)
        BenchmarkJson.write(second, BenchmarkJson.read<ControlledHostPolicy>(first))

        assertThat(policy.schema).isEqualTo("revoman-controlled-host/v1")
        assertThat(Files.readAllBytes(second)).isEqualTo(Files.readAllBytes(first))
        assertThat(Files.readString(first).indexOf("performance"))
            .isLessThan(Files.readString(first).indexOf("schedutil"))
    }

    @Test
    fun `controlled policy rejects unknown fields missing identity and relative paths`() {
        val valid = Files.readString(resourcePath("/host/valid.json"))
        val unknown = temporaryDirectory.resolve("unknown.json")
        Files.writeString(unknown, valid.replace("\"cpuCount\": 2", "\"cpuCount\": 2, \"unexpected\": true"))
        val missing = temporaryDirectory.resolve("missing.json")
        Files.writeString(
            missing,
            valid.replace(Regex("\\s*\"hostFingerprintSha256\"[^\\n]+\\n"), "\n"),
        )

        assertThrows<IllegalArgumentException> {
            BenchmarkJson.validateSchema(unknown, CONTROLLED_HOST_SCHEMA_RESOURCE)
        }
        assertThrows<JsonDataException> { BenchmarkJson.read<ControlledHostPolicy>(unknown) }
        assertThrows<IllegalArgumentException> {
            BenchmarkJson.validateSchema(missing, CONTROLLED_HOST_SCHEMA_RESOURCE)
        }
        assertThrows<IllegalArgumentException> { ControlledHostPolicy.load(Path.of("relative.json")) }
    }

    @Test
    fun `high load thermal pressure swap growth battery and wrong governor reject whole pair`() {
        val policy = policy()
        val gate = HostHealthGate(policy)
        val valid = validSnapshot()
        val cases =
            listOf(
                "high-load.json" to HostHealthReason.LOAD_AVERAGE_EXCEEDS_MAXIMUM,
                "thermal.json" to HostHealthReason.THERMAL_VALUE_EXCEEDS_MAXIMUM,
                "swap-growth.json" to HostHealthReason.SWAP_GROWTH_EXCEEDS_MAXIMUM,
                "on-battery.json" to HostHealthReason.AC_POWER_REQUIRED,
                "wrong-governor.json" to HostHealthReason.GOVERNOR_NOT_ALLOWED,
            )

        cases.forEach { (fixture, expectedReason) ->
            val unhealthy = snapshotFixture(fixture)
            val decision =
                when (fixture) {
                    "swap-growth.json" -> gate.assess(valid, listOf(unhealthy), unhealthy)
                    else -> gate.assess(valid, listOf(unhealthy), valid.copy(capturedAtNanos = 3))
                }

            assertThat(decision.accepted).isFalse()
            assertThat(decision.reasons).containsExactly(expectedReason)
        }
    }

    @Test
    fun `health rejection reasons use stable policy order and only health samples`() {
        val policy = policy()
        val invalid =
            validSnapshot().copy(
                loadAverage = 2.0,
                cpuBusyFraction = 0.9,
                availableMemoryBytes = 512,
                swapUsedBytes = 4_000_000,
                thermalValue = 90.0,
                onAcPower = false,
                governors = listOf("powersave"),
            )

        val decision = HostHealthGate(policy).assess(validSnapshot(), listOf(invalid), invalid)

        assertThat(decision.reasons)
            .containsExactly(
                HostHealthReason.LOAD_AVERAGE_EXCEEDS_MAXIMUM,
                HostHealthReason.CPU_BUSY_FRACTION_EXCEEDS_MAXIMUM,
                HostHealthReason.AVAILABLE_MEMORY_BELOW_MINIMUM,
                HostHealthReason.SWAP_GROWTH_EXCEEDS_MAXIMUM,
                HostHealthReason.THERMAL_VALUE_EXCEEDS_MAXIMUM,
                HostHealthReason.AC_POWER_REQUIRED,
                HostHealthReason.GOVERNOR_NOT_ALLOWED,
            )
            .inOrder()
    }

    @Test
    fun `health timeline requires nonempty nondecreasing during samples`() {
        val gate = HostHealthGate(policy())
        val before = validSnapshot().copy(capturedAtNanos = 10)
        val during = validSnapshot().copy(capturedAtNanos = 9)
        val after = validSnapshot().copy(capturedAtNanos = 11)

        val emptyFailure = assertThrows<IllegalArgumentException> {
            gate.assess(before, emptyList(), after)
        }
        val chronologyFailure = assertThrows<IllegalArgumentException> {
            gate.assess(before, listOf(during), after)
        }

        assertThat(emptyFailure).hasMessageThat().contains("healthDuring")
        assertThat(chronologyFailure).hasMessageThat().contains("non-decreasing")
    }

    @Test
    fun `linux probe reads proc and sysfs with exact units through injected seams`() {
        val root = temporaryDirectory.resolve("linux-root")
        writeLinuxHost(root)
        val machineId = "host-machine-id"
        val expectedPolicy =
            policy().copy(
                hostFingerprintSha256 = ContentHasher.sha256(machineId.toByteArray()),
                cpuModel = "Benchmark CPU",
                cpuCount = 2,
                probeIntervalMillis = 10,
            )
        val probe =
            LinuxHostProbe(
                policy = expectedPolicy,
                root = root,
                osName = { "Linux" },
                nanoTime = { 123_456L },
                sleepMillis = { millis ->
                    assertThat(millis).isEqualTo(10)
                    write(
                        root,
                        "proc/stat",
                        "cpu 150 0 150 900 0 0 0 0 0 0\n",
                    )
                },
            )

        val snapshot = probe.sample()

        assertThat(snapshot.capturedAtNanos).isEqualTo(123_456L)
        assertThat(snapshot.loadAverage).isEqualTo(0.25)
        assertThat(snapshot.cpuBusyFraction).isWithin(0.000_001).of(0.5)
        assertThat(snapshot.availableMemoryBytes).isEqualTo(2_097_152_000L)
        assertThat(snapshot.swapUsedBytes).isEqualTo(1_048_576L)
        assertThat(snapshot.thermalValue).isEqualTo(48.0)
        assertThat(snapshot.onAcPower).isTrue()
        assertThat(snapshot.governors).containsExactly("performance", "schedutil").inOrder()
    }

    @Test
    fun `linux during sample brackets the callback CPU interval`() {
        val root = temporaryDirectory.resolve("linux-during-bracket")
        writeLinuxHost(root)
        val events = mutableListOf<String>()
        val probe =
            LinuxHostProbe(
                policy = linuxPolicy(),
                root = root,
                osName = { "Linux" },
                nanoTime = { 123_456L },
                sleepMillis = {
                    events += "sleep"
                    write(root, "proc/stat", "cpu 150 0 150 900 0 0 0 0 0 0\n")
                },
            )

        val sampled =
            probe.sampleDuring {
                events += "callback"
                Files.delete(root.resolve("proc/stat"))
                "completed"
            }

        assertThat(sampled.value).isEqualTo("completed")
        assertThat(sampled.snapshot.cpuBusyFraction).isWithin(0.000_001).of(0.5)
        assertThat(events).containsExactly("callback", "sleep").inOrder()
    }

    @Test
    fun `linux during sampling failure is suppressed on target failure`() {
        val root = temporaryDirectory.resolve("linux-during-failures")
        writeLinuxHost(root)
        val primary = IllegalArgumentException("target failed")
        val probe =
            LinuxHostProbe(
                policy = linuxPolicy(),
                root = root,
                osName = { "Linux" },
                nanoTime = { 123_456L },
                sleepMillis = { error("during sampling failed") },
            )

        val failure = assertThrows<IllegalArgumentException> {
            probe.sampleDuring<Unit> { throw primary }
        }

        assertThat(failure).isSameInstanceAs(primary)
        assertThat(failure.suppressed.map(Throwable::message))
            .containsExactly("during sampling failed")
    }

    @Test
    fun `controlled linux probe fails closed while smoke records unknown without eligibility`() {
        val root = temporaryDirectory.resolve("missing-linux-root")
        Files.createDirectories(root)
        val unsupported =
            LinuxHostProbe(
                policy = policy(),
                root = root,
                osName = { "Mac OS X" },
                nanoTime = { 0L },
                sleepMillis = {},
            )

        val controlledFailure = assertThrows<IllegalStateException> { unsupported.sample() }
        val smoke = unsupported.sampleForSmoke()

        assertThat(controlledFailure).hasMessageThat().contains("Linux")
        assertThat(smoke.status).isEqualTo(SmokeHostHealthStatus.UNKNOWN)
        assertThat(smoke.snapshot).isNull()
        assertThat(smoke.unknownReasons).containsExactly(controlledFailure.message)
        assertThat(smoke.controlledEligible).isFalse()
    }

    @Test
    fun `linux probe rejects an unknown power supply type instead of treating it as AC`() {
        val root = temporaryDirectory.resolve("unknown-power-root")
        writeLinuxHost(root)
        write(root, "sys/class/power_supply/AC/type", "Unknown\n")
        val probe =
            LinuxHostProbe(
                policy = linuxPolicy(),
                root = root,
                osName = { "Linux" },
                nanoTime = { 123_456L },
                sleepMillis = {
                    write(root, "proc/stat", "cpu 150 0 150 900 0 0 0 0 0 0\n")
                },
            )

        val failure = assertThrows<IllegalStateException> { probe.sample() }

        assertThat(failure).hasMessageThat().contains("power-supply type")
        assertThat(failure).hasMessageThat().contains("Unknown")
    }

    @Test
    fun `linux probe reads governors from non contiguous processor IDs`() {
        val root = temporaryDirectory.resolve("non-contiguous-processors")
        writeLinuxHost(root)
        write(
            root,
            "proc/cpuinfo",
            "processor : 2\nmodel name : Benchmark CPU\n" +
                "processor : 7\nmodel name : Benchmark CPU\n",
        )
        Files.delete(root.resolve("sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"))
        Files.delete(root.resolve("sys/devices/system/cpu/cpu1/cpufreq/scaling_governor"))
        write(root, "sys/devices/system/cpu/cpu2/cpufreq/scaling_governor", "performance\n")
        write(root, "sys/devices/system/cpu/cpu7/cpufreq/scaling_governor", "schedutil\n")

        val snapshot = linuxProbe(root).sample()

        assertThat(snapshot.governors).containsExactly("performance", "schedutil").inOrder()
    }

    @Test
    fun `linux probe rejects duplicate missing and malformed processor identity fields`() {
        val cases =
            listOf(
                Triple(
                    "count-mismatch",
                    "processor : 0\nmodel name : Benchmark CPU\n",
                    "count",
                ),
                Triple(
                    "duplicate-id",
                    "processor : 0\nmodel name : Benchmark CPU\n" +
                        "processor : 0\nmodel name : Benchmark CPU\n",
                    "duplicate",
                ),
                Triple(
                    "missing-model",
                    "processor : 0\nmodel name : Benchmark CPU\n" +
                        "processor : 1\n",
                    "model",
                ),
                Triple(
                    "duplicate-model",
                    "processor : 0\nmodel name : Benchmark CPU\nmodel name : Benchmark CPU\n" +
                        "processor : 1\nmodel name : Benchmark CPU\n",
                    "model",
                ),
                Triple(
                    "model-mismatch",
                    "processor : 0\nmodel name : Benchmark CPU\n" +
                        "processor : 1\nmodel name : Different CPU\n",
                    "model mismatch",
                ),
                Triple(
                    "malformed-id",
                    "processor : zero\nmodel name : Benchmark CPU\n" +
                        "processor : 1\nmodel name : Benchmark CPU\n",
                    "processor",
                ),
            )

        cases.forEach { (name, cpuInfo, expectedMessage) ->
            val root = temporaryDirectory.resolve(name)
            writeLinuxHost(root)
            write(root, "proc/cpuinfo", cpuInfo)

            val failure = assertThrows<IllegalStateException>(name) { linuxProbe(root).sample() }

            assertThat(failure).hasMessageThat().contains(expectedMessage)
        }
    }

    @Test
    fun `linux probe rejects malformed nonmonotonic and overflowing CPU counters`() {
        val malformedRoot = temporaryDirectory.resolve("malformed-cpu-counters")
        writeLinuxHost(malformedRoot)
        write(malformedRoot, "proc/stat", "cpu invalid 0 100 800\n")
        val malformed = assertThrows<IllegalStateException> { linuxProbe(malformedRoot).sample() }
        assertThat(malformed).hasMessageThat().contains("cpu field")

        val nonmonotonicRoot = temporaryDirectory.resolve("nonmonotonic-cpu-counters")
        writeLinuxHost(nonmonotonicRoot)
        val nonmonotonic = assertThrows<IllegalStateException> {
            linuxProbe(
                    nonmonotonicRoot,
                    afterCpuStat = "cpu 90 0 90 700 0 0 0 0 0 0\n",
                )
                .sample()
        }
        assertThat(nonmonotonic).hasMessageThat().contains("monotonically")

        val overflowRoot = temporaryDirectory.resolve("overflow-cpu-counters")
        writeLinuxHost(overflowRoot)
        write(
            overflowRoot,
            "proc/stat",
            "cpu ${Long.MAX_VALUE} 1 0 0 0 0 0 0 0 0\n",
        )
        assertThrows<ArithmeticException> { linuxProbe(overflowRoot).sample() }
    }

    @Test
    fun `linux probe rejects missing duplicate malformed and overflowing memory fields`() {
        val missingRoot = temporaryDirectory.resolve("missing-memory")
        writeLinuxHost(missingRoot)
        write(missingRoot, "proc/meminfo", "SwapTotal: 2048 kB\nSwapFree: 1024 kB\n")
        val missing = assertThrows<IllegalArgumentException> { linuxProbe(missingRoot).sample() }
        assertThat(missing).hasMessageThat().contains("MemAvailable")

        val duplicateRoot = temporaryDirectory.resolve("duplicate-memory")
        writeLinuxHost(duplicateRoot)
        write(
            duplicateRoot,
            "proc/meminfo",
            "MemAvailable: 2048 kB\nMemAvailable: 1024 kB\n" +
                "SwapTotal: 2048 kB\nSwapFree: 1024 kB\n",
        )
        val duplicate = assertThrows<IllegalStateException> { linuxProbe(duplicateRoot).sample() }
        assertThat(duplicate).hasMessageThat().contains("duplicate")

        val malformedRoot = temporaryDirectory.resolve("malformed-memory")
        writeLinuxHost(malformedRoot)
        write(
            malformedRoot,
            "proc/meminfo",
            "MemAvailable: 2048 bytes\nSwapTotal: 2048 kB\nSwapFree: 1024 kB\n",
        )
        val malformed = assertThrows<IllegalStateException> { linuxProbe(malformedRoot).sample() }
        assertThat(malformed).hasMessageThat().contains("unit")

        val overflowRoot = temporaryDirectory.resolve("overflow-memory")
        writeLinuxHost(overflowRoot)
        write(
            overflowRoot,
            "proc/meminfo",
            "MemAvailable: ${Long.MAX_VALUE} kB\nSwapTotal: 2048 kB\nSwapFree: 1024 kB\n",
        )
        assertThrows<ArithmeticException> { linuxProbe(overflowRoot).sample() }
    }

    @Test
    fun `linux probe rejects missing governors thermal and invalid power evidence`() {
        val governorRoot = temporaryDirectory.resolve("missing-governor")
        writeLinuxHost(governorRoot)
        Files.delete(governorRoot.resolve("sys/devices/system/cpu/cpu1/cpufreq/scaling_governor"))
        val governor = assertThrows<IllegalStateException> { linuxProbe(governorRoot).sample() }
        assertThat(governor).hasMessageThat().contains("scaling_governor")

        val thermalRoot = temporaryDirectory.resolve("missing-thermal")
        writeLinuxHost(thermalRoot)
        Files.delete(thermalRoot.resolve("sys/class/thermal/thermal_zone0/temp"))
        val thermal = assertThrows<IllegalStateException> { linuxProbe(thermalRoot).sample() }
        assertThat(thermal).hasMessageThat().contains("thermal_zone0")

        val onlineRoot = temporaryDirectory.resolve("invalid-online")
        writeLinuxHost(onlineRoot)
        write(onlineRoot, "sys/class/power_supply/AC/online", "2\n")
        val online = assertThrows<IllegalStateException> { linuxProbe(onlineRoot).sample() }
        assertThat(online).hasMessageThat().contains("online value")

        val batteryOnlyRoot = temporaryDirectory.resolve("battery-only")
        writeLinuxHost(batteryOnlyRoot)
        write(batteryOnlyRoot, "sys/class/power_supply/AC/type", "Battery\n")
        val batteryOnly = assertThrows<IllegalStateException> { linuxProbe(batteryOnlyRoot).sample() }
        assertThat(batteryOnly).hasMessageThat().contains("no external power")
    }

    private fun policy(): ControlledHostPolicy =
        BenchmarkJson.read(resourcePath("/host/valid.json"))

    private fun linuxPolicy(): ControlledHostPolicy =
        policy().copy(
            hostFingerprintSha256 = ContentHasher.sha256("host-machine-id".toByteArray()),
            cpuModel = "Benchmark CPU",
            cpuCount = 2,
            probeIntervalMillis = 10,
        )

    private fun linuxProbe(
        root: Path,
        afterCpuStat: String = "cpu 150 0 150 900 0 0 0 0 0 0\n",
    ): LinuxHostProbe =
        LinuxHostProbe(
            policy = linuxPolicy(),
            root = root,
            osName = { "Linux" },
            nanoTime = { 123_456L },
            sleepMillis = {
                write(root, "proc/stat", afterCpuStat)
            },
        )

    private fun snapshotFixture(name: String): HostHealthSnapshot =
        BenchmarkJson.read(resourcePath("/host/$name"))

    private fun validSnapshot(): HostHealthSnapshot =
        HostHealthSnapshot(
            capturedAtNanos = 1,
            loadAverage = 0.25,
            cpuBusyFraction = 0.25,
            availableMemoryBytes = 2_147_483_648,
            swapUsedBytes = 0,
            thermalValue = 50.0,
            onAcPower = true,
            governors = listOf("performance", "performance"),
        )

    private fun writeLinuxHost(root: Path) {
        write(
            root,
            "proc/cpuinfo",
            "processor : 0\nmodel name : Benchmark CPU\nprocessor : 1\nmodel name : Benchmark CPU\n",
        )
        write(root, "etc/machine-id", "host-machine-id\n")
        write(root, "proc/loadavg", "0.25 0.20 0.15 1/100 42\n")
        write(root, "proc/stat", "cpu 100 0 100 800 0 0 0 0 0 0\n")
        write(
            root,
            "proc/meminfo",
            "MemTotal: 4096000 kB\nMemAvailable: 2048000 kB\nSwapTotal: 2048 kB\n" +
                "SwapFree: 1024 kB\nHugePages_Total: 0\nDirectMap4k: 32 kB\n",
        )
        write(root, "sys/devices/system/cpu/cpu0/cpufreq/scaling_governor", "performance\n")
        write(root, "sys/devices/system/cpu/cpu1/cpufreq/scaling_governor", "schedutil\n")
        write(root, "sys/class/power_supply/AC/type", "Mains\n")
        write(root, "sys/class/power_supply/AC/online", "1\n")
        write(root, "sys/class/power_supply/BAT0/type", "Battery\n")
        write(root, "sys/class/thermal/thermal_zone0/temp", "48000\n")
    }

    private fun write(root: Path, relative: String, content: String) {
        val path = root.resolve(relative)
        Files.createDirectories(requireNotNull(path.parent))
        Files.writeString(path, content)
    }

    private fun resourcePath(name: String): Path =
        Path.of(requireNotNull(javaClass.getResource(name)) { "Missing test resource: $name" }.toURI())
}
