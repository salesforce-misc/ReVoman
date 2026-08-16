/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.target

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodType

/** One exactly linked target member with a cached, allocation-minimal arity-specific invoker. */
internal class PreparedTargetMethod(private val handle: MethodHandle) {
    private val arity: Int = handle.type().parameterCount()
    private var fastInvoker: Any? = null

    fun call0(): TargetCall0 {
        requireArity(0)
        return fastInvoker as? TargetCall0
            ?: normalizedHandle(0)
                .let { exact -> TargetCall0 { exact.invokeExact() } }
                .also { fastInvoker = it }
    }

    fun call1(): TargetCall1 {
        requireArity(1)
        return fastInvoker as? TargetCall1
            ?: normalizedHandle(1)
                .let { exact -> TargetCall1 { first -> exact.invokeExact(first) } }
                .also { fastInvoker = it }
    }

    fun call2(): TargetCall2 {
        requireArity(2)
        return fastInvoker as? TargetCall2
            ?: normalizedHandle(2)
                .let { exact ->
                    TargetCall2 { first, second -> exact.invokeExact(first, second) }
                }
                .also { fastInvoker = it }
    }

    fun call3(): TargetCall3 {
        requireArity(3)
        return fastInvoker as? TargetCall3
            ?: normalizedHandle(3)
                .let { exact ->
                    TargetCall3 { first, second, third -> exact.invokeExact(first, second, third) }
                }
                .also { fastInvoker = it }
    }

    fun call4(): TargetCall4 {
        requireArity(4)
        return fastInvoker as? TargetCall4
            ?: normalizedHandle(4)
                .let { exact ->
                    TargetCall4 { first, second, third, fourth ->
                        exact.invokeExact(first, second, third, fourth)
                    }
                }
                .also { fastInvoker = it }
    }

    fun call5(): TargetCall5 {
        requireArity(5)
        return fastInvoker as? TargetCall5
            ?: normalizedHandle(5)
                .let { exact ->
                    TargetCall5 { first, second, third, fourth, fifth ->
                        exact.invokeExact(first, second, third, fourth, fifth)
                    }
                }
                .also { fastInvoker = it }
    }

    fun call6(): TargetCall6 {
        requireArity(6)
        return fastInvoker as? TargetCall6
            ?: normalizedHandle(6)
                .let { exact ->
                    TargetCall6 { first, second, third, fourth, fifth, sixth ->
                        exact.invokeExact(first, second, third, fourth, fifth, sixth)
                    }
                }
                .also { fastInvoker = it }
    }

    private fun normalizedHandle(expectedArity: Int): MethodHandle =
        handle.asType(MethodType.genericMethodType(expectedArity))

    private fun requireArity(expected: Int) {
        require(arity == expected) { "Target method arity is $arity, not $expected" }
    }
}

internal fun interface TargetCall0 {
    fun invoke(): Any?
}

internal fun interface TargetCall1 {
    fun invoke(first: Any?): Any?
}

internal fun interface TargetCall2 {
    fun invoke(first: Any?, second: Any?): Any?
}

internal fun interface TargetCall3 {
    fun invoke(first: Any?, second: Any?, third: Any?): Any?
}

internal fun interface TargetCall4 {
    fun invoke(first: Any?, second: Any?, third: Any?, fourth: Any?): Any?
}

internal fun interface TargetCall5 {
    fun invoke(first: Any?, second: Any?, third: Any?, fourth: Any?, fifth: Any?): Any?
}

internal fun interface TargetCall6 {
    fun invoke(first: Any?, second: Any?, third: Any?, fourth: Any?, fifth: Any?, sixth: Any?): Any?
}
