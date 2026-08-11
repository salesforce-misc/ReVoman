/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.host

import com.salesforce.revoman.benchmark.driver.integrity.ContentHasher
import com.salesforce.revoman.benchmark.driver.model.HostHealthSnapshot
import com.salesforce.revoman.benchmark.driver.model.PowerEvidence
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

/** Distinguishes a recorded smoke observation from unavailable best-effort evidence. */
enum class SmokeHostHealthStatus {
    OBSERVED,
    UNKNOWN,
}

/** Smoke-only health recording; it can never satisfy controlled release eligibility. */
data class SmokeHostHealthRecord(
    val status: SmokeHostHealthStatus,
    val snapshot: HostHealthSnapshot?,
    val unknownReasons: List<String>,
) {
    val controlledEligible: Boolean = false
}

/**
 * Reads Linux procfs/sysfs health with explicit policy, filesystem, platform, clock, and sleep
 * seams. Missing, malformed, ambiguous, or unsupported evidence throws instead of defaulting.
 */
class LinuxHostProbe(
    private val policy: ControlledHostPolicy,
    private val root: Path = Path.of("/"),
    private val osName: () -> String = { System.getProperty("os.name") },
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleepMillis: (Long) -> Unit = Thread::sleep,
) : HostHealthProbe {
    init {
        policy.validate()
        require(root.isAbsolute && root.normalize() == root) {
            "Linux probe root must be absolute and normalized: $root"
        }
        require(Files.isDirectory(root)) { "Linux probe root must be an existing directory: $root" }
    }

    /** Samples every required controlled Linux signal and validates the pinned host identity. */
    override fun sample(): HostHealthSnapshot {
        requireLinux()
        val identity = verifyIdentity()
        val cpuBefore = readCpuTicks()
        sleepMillis(policy.probeIntervalMillis)
        val cpuAfter = readCpuTicks()
        return snapshot(identity, cpuBefore, cpuAfter)
    }

    /** Brackets target execution with Linux CPU counters and samples all other health afterward. */
    override fun <T> sampleDuring(execution: () -> T): SampledHostExecution<T> {
        requireLinux()
        val identity = verifyIdentity()
        val cpuBefore = readCpuTicks()
        val outcome = runCatching(execution)
        var failure = outcome.exceptionOrNull()
        var sampled: HostHealthSnapshot? = null
        try {
            sleepMillis(policy.probeIntervalMillis)
            sampled = snapshot(identity, cpuBefore, readCpuTicks())
        } catch (samplingFailure: Throwable) {
            failure = mergeProbeFailures(failure, samplingFailure)
        }
        failure?.let { throw it }
        return SampledHostExecution(outcome.getOrThrow(), requireNotNull(sampled))
    }

    private fun requireLinux() {
        check(osName().equals("Linux", ignoreCase = true)) {
            "Controlled host probing requires Linux"
        }
    }

    private fun snapshot(
        identity: CpuIdentity,
        cpuBefore: CpuTicks,
        cpuAfter: CpuTicks,
    ): HostHealthSnapshot {
        val memory = readMemory()
        return HostHealthSnapshot(
                capturedAtNanos = nanoTime(),
                loadAverage = readLoadAverage(),
                cpuBusyFraction = cpuBusyFraction(cpuBefore, cpuAfter),
                availableMemoryBytes = memory.availableBytes,
                swapUsedBytes = memory.swapUsedBytes,
                thermalValue = readThermalCelsius(),
                powerEvidence = readPowerEvidence(),
                governors = readGovernors(identity.processorIds),
            )
            .also { it.validate("linuxHostProbe") }
    }

    /** Records an explicit UNKNOWN in smoke mode without converting it into controlled evidence. */
    fun sampleForSmoke(): SmokeHostHealthRecord =
        try {
            SmokeHostHealthRecord(
                status = SmokeHostHealthStatus.OBSERVED,
                snapshot = sample(),
                unknownReasons = emptyList(),
            )
        } catch (failure: Exception) {
            SmokeHostHealthRecord(
                status = SmokeHostHealthStatus.UNKNOWN,
                snapshot = null,
                unknownReasons = listOf(failure.message ?: failure.javaClass.name),
            )
        }

    private fun verifyIdentity(): CpuIdentity {
        val cpuInfo = readRequired("proc/cpuinfo")
        val processors = parseProcessors(cpuInfo)
        check(processors.size == policy.cpuCount) {
            "Controlled host processor count mismatch: expected=${policy.cpuCount}, actual=${processors.size}"
        }
        val duplicateIds = processors.groupingBy(Processor::id).eachCount().filterValues { it > 1 }.keys
        check(duplicateIds.isEmpty()) { "Controlled host has duplicate processor IDs: $duplicateIds" }
        processors.forEach { processor ->
            check(processor.models.size == 1) {
                "Controlled host processor ${processor.id} must have exactly one model entry"
            }
            check(processor.models.single() == policy.cpuModel) {
                "Controlled host processor ${processor.id} model mismatch: " +
                    "expected=${policy.cpuModel}, actual=${processor.models.single()}"
            }
        }
        val machineId = readRequired("etc/machine-id").trim()
        check(machineId.isNotEmpty()) { "Controlled host machine-id must not be empty" }
        val fingerprint = ContentHasher.sha256(machineId.toByteArray(UTF_8))
        check(fingerprint == policy.hostFingerprintSha256) {
            "Controlled host fingerprint mismatch: expected=${policy.hostFingerprintSha256}, actual=$fingerprint"
        }
        return CpuIdentity(processors.map(Processor::id).sorted())
    }

    private fun parseProcessors(cpuInfo: String): List<Processor> {
        val processors = mutableListOf<Processor>()
        var processorId: Int? = null
        var models = mutableListOf<String>()

        fun finishProcessor() {
            processorId?.let { id -> processors += Processor(id, models.toList()) }
            processorId = null
            models = mutableListOf()
        }

        cpuInfo.lineSequence().forEach { line ->
            when (line.substringBefore(':').trim()) {
                "processor" -> {
                    finishProcessor()
                    val rawId = line.substringAfter(':', missingDelimiterValue = "").trim()
                    val id = rawId.toIntOrNull()
                    check(id != null && id >= 0) { "Invalid Linux processor ID: $rawId" }
                    processorId = id
                }
                "model name" -> {
                    val id = checkNotNull(processorId) {
                        "Linux processor model appeared before a processor ID"
                    }
                    val model = line.substringAfter(':', missingDelimiterValue = "").trim()
                    check(model.isNotEmpty()) { "Linux processor $id model must not be blank" }
                    models += model
                }
            }
        }
        finishProcessor()
        check(processors.isNotEmpty()) { "Linux /proc/cpuinfo contains no processor entries" }
        return processors
    }

    private fun readLoadAverage(): Double {
        val token = readRequired("proc/loadavg").trim().split(Regex("\\s+")).firstOrNull()
        val value = token?.toDoubleOrNull()
        check(value != null && value.isFinite() && value >= 0.0) {
            "Invalid Linux /proc/loadavg one-minute value: $token"
        }
        return value
    }

    private fun readCpuTicks(): CpuTicks {
        val cpuLine =
            readRequired("proc/stat").lineSequence().firstOrNull { line -> line.startsWith("cpu ") }
        check(cpuLine != null) { "Linux /proc/stat is missing the aggregate cpu line" }
        val values =
            cpuLine.trim().split(Regex("\\s+")).drop(1).mapIndexed { index, token ->
                token.toLongOrNull()?.also { value -> check(value >= 0) }
                    ?: error("Invalid Linux /proc/stat cpu field[$index]: $token")
            }
        check(values.size >= 4) { "Linux /proc/stat aggregate cpu line has too few fields" }
        val accounted = values.take(8)
        val total = accounted.fold(0L, Math::addExact)
        val idle = Math.addExact(values[3], values.getOrElse(4) { 0L })
        return CpuTicks(total = total, idle = idle)
    }

    private fun cpuBusyFraction(before: CpuTicks, after: CpuTicks): Double {
        val totalDelta = Math.subtractExact(after.total, before.total)
        val idleDelta = Math.subtractExact(after.idle, before.idle)
        check(totalDelta > 0 && idleDelta >= 0 && idleDelta <= totalDelta) {
            "Linux /proc/stat cpu counters did not advance monotonically"
        }
        return Math.subtractExact(totalDelta, idleDelta).toDouble() / totalDelta.toDouble()
    }

    private fun readMemory(): MemorySample {
        val entries =
            readRequired("proc/meminfo")
                .lineSequence()
                .filter(String::isNotBlank)
                .filter { line -> line.substringBefore(':') in REQUIRED_MEMINFO_KEYS }
                .map { line ->
                    val match = MEMINFO_LINE.matchEntire(line)
                    check(match != null) { "Invalid Linux /proc/meminfo line or unit: $line" }
                    val key = match.groupValues[1]
                    val kibibytes = match.groupValues[2].toLong()
                    check(kibibytes >= 0) { "Negative Linux /proc/meminfo value: $line" }
                    key to Math.multiplyExact(kibibytes, 1_024L)
                }
                .toList()
        check(entries.map(Pair<String, Long>::first).distinct().size == entries.size) {
            "Linux /proc/meminfo contains duplicate keys"
        }
        val values = entries.toMap()
        val available = requireNotNull(values["MemAvailable"]) {
            "Linux /proc/meminfo is missing MemAvailable"
        }
        val swapTotal = requireNotNull(values["SwapTotal"]) {
            "Linux /proc/meminfo is missing SwapTotal"
        }
        val swapFree = requireNotNull(values["SwapFree"]) {
            "Linux /proc/meminfo is missing SwapFree"
        }
        check(swapFree <= swapTotal) { "Linux swap free exceeds swap total" }
        return MemorySample(available, Math.subtractExact(swapTotal, swapFree))
    }

    private fun readGovernors(processorIds: List<Int>): List<String> =
        processorIds.map { cpu ->
            readRequired("sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_governor")
                .trim()
                .also { governor -> check(governor.isNotEmpty()) { "CPU $cpu governor is empty" } }
        }

    private fun readPowerEvidence(): PowerEvidence =
        when (policy.powerEvidenceRequirement) {
            PowerEvidenceRequirement.FIXED_MAINS -> readFixedMainsEvidence()
            PowerEvidenceRequirement.OBSERVE_EXTERNAL_POWER,
            PowerEvidenceRequirement.REQUIRE_EXTERNAL_POWER -> readExternalPowerEvidence()
        }

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

    private fun readExternalPowerEvidence(): PowerEvidence {
        val supplyRoot = resolve("sys/class/power_supply")
        val supplies = listDirectories(supplyRoot)
        val typedSupplies =
            supplies.map { supply ->
                val type = readRequired(relative(supply.resolve("type"))).trim()
                check(type in SUPPORTED_POWER_SUPPLY_TYPES) {
                    "Unsupported Linux power-supply type: $type"
                }
                supply to type
            }
        val external =
            typedSupplies.filter { (_, type) -> type != "Battery" }.map(Pair<Path, String>::first)
        check(external.isNotEmpty()) { "Linux power-supply sysfs has no external power source" }
        return if (external.any { supply ->
            when (val online = readRequired(relative(supply.resolve("online"))).trim()) {
                "0" -> false
                "1" -> true
                else -> error("Invalid Linux power-supply online value: $online")
            }
        }
        ) {
            PowerEvidence.EXTERNAL_POWER_ONLINE
        } else {
            PowerEvidence.EXTERNAL_POWER_OFFLINE
        }
    }

    private fun readThermalCelsius(): Double {
        val thermalRoot = resolve("sys/class/thermal")
        val zones = listDirectories(thermalRoot).filter { it.fileName.toString().startsWith("thermal_zone") }
        check(zones.isNotEmpty()) { "Linux thermal sysfs has no thermal zones" }
        return requireNotNull(
            zones.maxOfOrNull { zone ->
                val raw = readRequired(relative(zone.resolve("temp"))).trim().toLongOrNull()
                check(raw != null && raw >= 0) { "Invalid Linux thermal millidegree value: $raw" }
                raw.toDouble() / 1_000.0
            }
        )
    }

    private fun listDirectories(directory: Path): List<Path> {
        check(Files.isDirectory(directory)) { "Required Linux directory is missing: $directory" }
        return Files.newDirectoryStream(directory).use { stream ->
            stream.filter(Files::isDirectory).sortedBy { it.fileName.toString() }
        }
    }

    private fun readRequired(relative: String): String {
        val path = resolve(relative)
        check(Files.isRegularFile(path) && Files.isReadable(path)) {
            "Required Linux probe file is missing or unreadable: $path"
        }
        return Files.readString(path, UTF_8)
    }

    private fun resolve(relative: String): Path = root.resolve(relative).normalize()

    private fun relative(path: Path): String = root.relativize(path.normalize()).toString()

    private data class CpuTicks(val total: Long, val idle: Long)

    private data class CpuIdentity(val processorIds: List<Int>)

    private data class Processor(val id: Int, val models: List<String>)

    private data class MemorySample(val availableBytes: Long, val swapUsedBytes: Long)

    private companion object {
        val SUPPORTED_POWER_SUPPLY_TYPES: Set<String> =
            setOf(
                "Battery",
                "UPS",
                "Mains",
                "USB",
                "USB_DCP",
                "USB_CDP",
                "USB_ACA",
                "USB_C",
                "USB_PD",
                "USB_PD_DRP",
                "Apple Brick ID",
                "Wireless",
            )
        val REQUIRED_MEMINFO_KEYS: Set<String> = setOf("MemAvailable", "SwapTotal", "SwapFree")
        val MEMINFO_LINE: Regex = Regex("([A-Za-z_()]+):\\s+([0-9]+) kB")
    }
}

private fun mergeProbeFailures(primary: Throwable?, secondary: Throwable): Throwable =
    primary?.also { failure ->
        if (failure !== secondary) failure.addSuppressed(secondary)
    } ?: secondary
