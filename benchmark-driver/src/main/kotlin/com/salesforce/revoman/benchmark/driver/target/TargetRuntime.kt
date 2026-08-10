/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.target

import java.net.URLClassLoader
import java.nio.file.Path

/** Owns one platform-parent classloader for an independently versioned target runtime. */
class TargetRuntime private constructor(private val loader: URLClassLoader) : AutoCloseable {
    /** Loads a target class without consulting the benchmark driver's application classloader. */
    fun loadClass(name: String): Class<*> = loader.loadClass(name)

    /** Installs the target loader as TCCL only for [block], restoring the prior loader on failure. */
    fun <T> withTargetContext(block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = loader
        return try {
            block()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    /** Releases every JAR opened by this target classloader. */
    override fun close() = loader.close()

    companion object {
        /** Opens [manifest] without re-reading or hashing target artifact contents. */
        fun open(manifest: VerifiedTargetManifest): TargetRuntime {
            val urls =
                manifest.manifest.classpath
                    .map { Path.of(it.executionPath).toUri().toURL() }
                    .toTypedArray()
            return TargetRuntime(URLClassLoader(urls, ClassLoader.getPlatformClassLoader()))
        }
    }
}
