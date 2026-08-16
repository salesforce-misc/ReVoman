/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.cli

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** Reserves and publishes a related set of absent output files with failure-atomic rollback. */
internal class AtomicOutputSet private constructor(
    private val slots: List<OutputSlot>,
    private val publishMove: (Path, Path) -> Unit,
) : AutoCloseable {
    val paths: List<Path> = slots.map(OutputSlot::output)
    private var published = false

    fun prepare(index: Int, writer: (Path) -> Unit) {
        check(!published) { "Output set is already published" }
        val slot = slots[index]
        check(slot.prepared == null) { "${slot.name} is already prepared" }
        val temporary = Files.createTempFile(slot.output.parent, ".${slot.output.fileName}.", ".prepared")
        slot.prepared = temporary
        writer(temporary)
    }

    fun publish() {
        check(!published) { "Output set is already published" }
        slots.forEach { slot ->
            check(slot.prepared != null) { "${slot.name} was not prepared" }
            check(Files.isSameFile(slot.output, slot.guard)) {
                "${slot.name} reservation was replaced before publication: ${slot.output}"
            }
            check(Files.readAllBytes(slot.guard).contentEquals(slot.guardBytes)) {
                "${slot.name} reservation guard changed before publication: ${slot.output}"
            }
        }
        try {
            slots.forEach { slot ->
                publishMove(requireNotNull(slot.prepared), slot.output)
                slot.prepared = null
                slot.outputPublished = true
            }
            slots.forEach { slot -> Files.delete(slot.guard) }
            published = true
        } catch (failure: Throwable) {
            rollback(failure)
            throw failure
        }
    }

    override fun close() {
        if (!published) rollback(null)?.let { throw it }
    }

    private fun rollback(primary: Throwable?): Throwable? {
        var failure = primary
        slots.asReversed().forEach { slot ->
            failure = cleanup(failure) { slot.prepared?.let(Files::deleteIfExists) }
            failure =
                cleanup(failure) {
                    when {
                        slot.outputPublished -> Files.deleteIfExists(slot.output)
                        Files.exists(slot.output, NOFOLLOW_LINKS) && Files.exists(slot.guard, NOFOLLOW_LINKS) -> {
                            check(Files.isSameFile(slot.output, slot.guard)) {
                                "Refusing to remove replaced output reservation: ${slot.output}"
                            }
                            Files.deleteIfExists(slot.output)
                        }
                    }
                }
            failure = cleanup(failure) { Files.deleteIfExists(slot.guard) }
        }
        return failure
    }

    companion object {
        fun reserve(
            inputs: List<Pair<String, Path>>,
            outputs: List<Pair<String, Path>>,
            publish: (Path, Path) -> Unit = ::moveAtomically,
        ): AtomicOutputSet {
            require(outputs.isNotEmpty()) { "Output set must not be empty" }
            val canonicalInputs =
                inputs.map { (name, input) ->
                    val absolute = input.toAbsolutePath().normalize()
                    val canonical = absolute.toRealPath()
                    require(canonical == absolute && Files.isRegularFile(canonical)) {
                        "$name must be a canonical regular file: $input"
                    }
                    name to canonical
                }
            val normalizedOutputs =
                outputs.map { (name, requested) ->
                    val path = requested.toAbsolutePath().normalize()
                    val parent = requireNotNull(path.parent) { "$name requires a parent" }
                    require(Files.isDirectory(parent) && parent.toRealPath() == parent) {
                        "$name parent must be an existing canonical directory: $parent"
                    }
                    require(Files.isWritable(parent)) { "$name parent must be writable: $parent" }
                    require(!Files.exists(path, NOFOLLOW_LINKS)) { "$name must be absent: $path" }
                    require(canonicalInputs.none { (_, input) -> input == path }) {
                        "$name aliases an input path: $path"
                    }
                    name to path
                }
            require(normalizedOutputs.map(Pair<String, Path>::second).distinct().size == normalizedOutputs.size) {
                "Output paths must be pairwise distinct: $normalizedOutputs"
            }

            val slots = mutableListOf<OutputSlot>()
            try {
                normalizedOutputs.forEach { (name, output) ->
                    require(!Files.exists(output, NOFOLLOW_LINKS)) {
                        "$name aliases an existing input or output: $output"
                    }
                    val guardBytes = "revoman-output-reservation/v1\u0000${UUID.randomUUID()}".toByteArray(UTF_8)
                    val guard = Files.createTempFile(output.parent, ".${output.fileName}.", ".guard")
                    Files.write(guard, guardBytes)
                    try {
                        Files.createLink(output, guard)
                    } catch (failure: Throwable) {
                        Files.deleteIfExists(guard)
                        throw failure
                    }
                    slots += OutputSlot(name, output, guard, guardBytes)
                }
                return AtomicOutputSet(slots.toList(), publish)
            } catch (failure: Throwable) {
                AtomicOutputSet(slots.toList(), publish).rollback(failure)
                throw failure
            }
        }

        private fun moveAtomically(source: Path, target: Path) {
            Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
        }
    }
}

private data class OutputSlot(
    val name: String,
    val output: Path,
    val guard: Path,
    val guardBytes: ByteArray,
    var prepared: Path? = null,
    var outputPublished: Boolean = false,
)

private fun cleanup(primary: Throwable?, action: () -> Unit): Throwable? =
    try {
        action()
        primary
    } catch (failure: Throwable) {
        primary?.also { existing ->
            if (existing !== failure) existing.addSuppressed(failure)
        } ?: failure
    }
