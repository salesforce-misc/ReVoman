/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.target

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Caches all target classes, members, and method handles during adapter preparation. */
internal class ReflectiveTarget(private val runtime: TargetRuntime) {
    private val classes = mutableMapOf<String, Class<*>>()
    private val methods = mutableMapOf<MethodKey, Method>()
    private val constructors = mutableMapOf<ConstructorKey, Constructor<*>>()
    private val fields = mutableMapOf<FieldKey, Field>()
    private val preparedMethods = mutableMapOf<Any, PreparedTargetMethod>()
    private val lookup = MethodHandles.lookup()

    fun type(name: String): Class<*> = classes.getOrPut(name) { runtime.loadClass(name) }

    fun staticMethod(
        owner: Class<*>,
        name: String,
        returnType: Class<*>,
        vararg parameterTypes: Class<*>,
    ): PreparedTargetMethod = method(owner, name, returnType, parameterTypes.toList())

    fun virtualMethod(
        owner: Class<*>,
        name: String,
        returnType: Class<*>,
        vararg parameterTypes: Class<*>,
    ): PreparedTargetMethod = method(owner, name, returnType, parameterTypes.toList())

    fun method(
        ownerInternalName: String,
        name: String,
        descriptor: String,
        isStatic: Boolean,
    ): PreparedTargetMethod {
        val owner = type(ownerInternalName.replace('/', '.'))
        val methodType =
            runCatching {
                    MethodType.fromMethodDescriptorString(descriptor, owner.classLoader)
                }
                .getOrElse { cause ->
                    throw IllegalArgumentException(
                        "Invalid target descriptor for $ownerInternalName.$name: $descriptor",
                        cause,
                    )
                }
        val key = MethodKey(owner, name, methodType.parameterList())
        val targetMethod =
            methods.getOrPut(key) {
                runCatching {
                        owner.getDeclaredMethod(name, *methodType.parameterArray())
                    }
                    .getOrElse { cause ->
                        throw IllegalArgumentException(
                            "Target descriptor does not resolve: " +
                                "$ownerInternalName.$name$descriptor",
                            cause,
                        )
                    }
                    .also(::makeAccessible)
            }
        require(targetMethod.returnType == methodType.returnType()) {
            "Target descriptor return type does not match $ownerInternalName.$name$descriptor: " +
                "actual=${targetMethod.returnType.name}"
        }
        val expectedInvocation = if (isStatic) "STATIC" else "VIRTUAL"
        require(Modifier.isStatic(targetMethod.modifiers) == isStatic) {
            "Target invocation kind mismatch for $ownerInternalName.$name$descriptor: " +
                "expected $expectedInvocation"
        }
        return preparedMethods.getOrPut(key) {
            PreparedTargetMethod(lookup.unreflect(targetMethod))
        }
    }

    fun constructor(owner: Class<*>, vararg parameterTypes: Class<*>): PreparedTargetMethod {
        val key = ConstructorKey(owner, parameterTypes.toList())
        val constructor =
            constructors.getOrPut(key) {
                owner.getDeclaredConstructor(*parameterTypes).also(::makeAccessible)
            }
        return preparedMethods.getOrPut(key) {
            PreparedTargetMethod(lookup.unreflectConstructor(constructor))
        }
    }

    fun fieldGetter(owner: Class<*>, name: String, fieldType: Class<*>): PreparedTargetMethod {
        val field = field(owner, name)
        require(field.type == fieldType) {
            "Unexpected target field type for ${owner.name}.$name: " +
                "expected=${fieldType.name}, actual=${field.type.name}"
        }
        return fieldHandle(owner, name, field)
    }

    fun fieldGetter(owner: Class<*>, name: String): PreparedTargetMethod {
        val field = field(owner, name)
        return fieldHandle(owner, name, field)
    }

    private fun field(owner: Class<*>, name: String): Field {
        val key = FieldKey(owner, name)
        return fields.getOrPut(key) { findField(owner, name).also(::makeAccessible) }
    }

    private fun fieldHandle(
        owner: Class<*>,
        name: String,
        field: Field,
    ): PreparedTargetMethod =
        preparedMethods.getOrPut(FieldKey(owner, name)) {
            PreparedTargetMethod(lookup.unreflectGetter(field))
        }

    private fun method(
        owner: Class<*>,
        name: String,
        returnType: Class<*>,
        parameterTypes: List<Class<*>>,
    ): PreparedTargetMethod {
        val key = MethodKey(owner, name, parameterTypes)
        val method =
            methods.getOrPut(key) {
                owner.getDeclaredMethod(name, *parameterTypes.toTypedArray()).also(::makeAccessible)
            }
        require(method.returnType == returnType) {
            "Unexpected target return type for ${owner.name}.$name: " +
                "expected=${returnType.name}, actual=${method.returnType.name}"
        }
        return preparedMethods.getOrPut(key) { PreparedTargetMethod(lookup.unreflect(method)) }
    }

    private fun findField(owner: Class<*>, name: String): Field =
        generateSequence(owner) { it.superclass }
            .mapNotNull { current -> runCatching { current.getDeclaredField(name) }.getOrNull() }
            .firstOrNull() ?: throw NoSuchFieldException("${owner.name}.$name")

    private fun makeAccessible(member: Constructor<*>) {
        check(member.trySetAccessible()) { "Cannot access target constructor: $member" }
    }

    private fun makeAccessible(member: Method) {
        check(member.trySetAccessible()) { "Cannot access target method: $member" }
    }

    private fun makeAccessible(member: Field) {
        check(member.trySetAccessible()) { "Cannot access target field: $member" }
    }

    private data class MethodKey(
        val owner: Class<*>,
        val name: String,
        val parameterTypes: List<Class<*>>,
    )

    private data class ConstructorKey(
        val owner: Class<*>,
        val parameterTypes: List<Class<*>>,
    )

    private data class FieldKey(val owner: Class<*>, val name: String)
}
