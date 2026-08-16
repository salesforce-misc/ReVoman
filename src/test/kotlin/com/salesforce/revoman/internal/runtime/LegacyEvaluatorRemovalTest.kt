/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.runtime

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.compat.JvmSurfaceInventory
import com.salesforce.revoman.compat.JvmSurfaceKind
import com.salesforce.revoman.compat.configuredRootJar
import java.util.jar.JarFile
import org.junit.jupiter.api.Test

class LegacyEvaluatorRemovalTest {
  @Test
  fun `built root jar removes the legacy evaluator surface but retains nodeModulesPath source shape`() {
    val rootJar = configuredRootJar()
    val entries = JvmSurfaceInventory.readJar(rootJar)
    val references = JvmSurfaceInventory.readJarReferences(rootJar)
    val forbiddenEvaluatorSurface = buildList {
      JarFile(rootJar.toFile()).use { archive ->
        if (archive.getJarEntry("$POSTMAN_SDK\$JSEvaluator.class") != null) {
          add("$POSTMAN_SDK\$JSEvaluator.class")
        }
      }
      entries
        .filter {
          it.owner == POSTMAN_SDK &&
            it.kind == JvmSurfaceKind.METHOD &&
            it.name in setOf("evaluateJS", "jsonStrToObj")
        }
        .mapTo(this) { "$POSTMAN_SDK#${it.name}${it.descriptor}" }
    }

    assertThat(forbiddenEvaluatorSurface).isEmpty()
    assertThat(
        entries.any {
          it.owner == KICK &&
            it.kind == JvmSurfaceKind.METHOD &&
            it.name == "nodeModulesPath" &&
            it.sourceCallable
        }
      )
      .isTrue()
    assertThat(
        entries.any {
          it.owner == KICK_BUILDER &&
            it.kind == JvmSurfaceKind.METHOD &&
            it.name == "nodeModulesPath" &&
            it.sourceCallable
        }
      )
      .isTrue()

    val contextBuilderOwners =
      references
        .filter { classReferences ->
          classReferences.members.any {
            it.owner == GRAAL_CONTEXT &&
              it.name == "newBuilder" &&
              it.descriptor == CONTEXT_NEW_BUILDER_DESCRIPTOR
          }
        }
        .map { it.owner }
    assertThat(contextBuilderOwners).containsExactly(SANDBOX_BRIDGE)

    val executionOwners = references.filter {
      it.owner == REVOMAN || EXECUTION_PACKAGES.any(it.owner::startsWith)
    }
    assertThat(
        executionOwners.flatMap { owner ->
          owner.members
            .filter { it.name == "nodeModulesPath" }
            .map { "${owner.owner} -> ${it.owner}#${it.name}${it.descriptor}" }
        }
      )
      .isEmpty()
    assertThat(
        executionOwners.flatMap { owner ->
          owner.strings.filter { it in COMMON_JS_OPTION_STRINGS }.map { "${owner.owner} -> $it" }
        }
      )
      .isEmpty()

    setOf(POSTMAN_SDK, REGEX_REPLACER).forEach { focusedOwner ->
      assertThat(
          entries.filter {
            it.owner == focusedOwner && it.kind != JvmSurfaceKind.CLASS && it.sourceCallable
          }
        )
        .isEmpty()
    }
    assertThat(
        entries
          .single {
            it.owner == POSTMAN_SDK_FACADE &&
              it.kind == JvmSurfaceKind.METHOD &&
              it.name == "postmanSDK"
          }
          .memberSynthetic
      )
      .isTrue()
    assertThat(
        entries
          .single {
            it.owner == REGEX_REPLACER_FACADE &&
              it.kind == JvmSurfaceKind.METHOD &&
              it.name == "regexReplacer"
          }
          .memberSynthetic
      )
      .isTrue()

    val referenceOwners =
      references
        .filter { refs ->
          refs.classes.any { classReference ->
            classReferenceNamesOwner(classReference, POSTMAN_SDK)
          } ||
            refs.members.any {
              it.owner == POSTMAN_SDK || it.descriptor.contains("L$POSTMAN_SDK;")
            } ||
            refs.descriptors.any { it.contains("L$POSTMAN_SDK;") } ||
            entries.any {
              it.owner == refs.owner && it.descriptor.contains("L$POSTMAN_SDK;")
            }
        }
        .mapTo(linkedSetOf()) { it.owner }
    assertThat(referenceOwners)
      .containsExactlyElementsIn(
        POSTMAN_SDK_DECLARATION_OWNERS + RUNNER_POSTMAN_SDK_REFERENCE_OWNERS
      )
  }

  private fun classReferenceNamesOwner(classReference: String, owner: String): Boolean {
    if (classReference == owner) return true
    val componentStart = classReference.indexOfFirst { it != '[' }
    return componentStart > 0 && classReference.substring(componentStart) == "L$owner;"
  }

  private companion object {
    const val POSTMAN_SDK = "com/salesforce/revoman/internal/postman/PostmanSDK"
    const val POSTMAN_SDK_FACADE = "com/salesforce/revoman/internal/postman/PostmanSDKKt"
    const val POSTMAN_SDK_IMPLEMENTATION = "$POSTMAN_SDK_FACADE\$postmanSDK\$1"
    const val REGEX_REPLACER = "com/salesforce/revoman/internal/postman/RegexReplacer"
    const val REGEX_REPLACER_FACADE = "com/salesforce/revoman/internal/postman/RegexReplacerKt"
    const val KICK = "com/salesforce/revoman/input/config/Kick"
    const val KICK_BUILDER = "com/salesforce/revoman/input/config/Kick\$Builder"
    const val REVOMAN = "com/salesforce/revoman/ReVoman"
    const val RUNNER_OWNER =
      "com/salesforce/revoman/internal/runtime/KickRunnerKt\$kickExecutionFactory\$body\$1"
    const val RUNNER = "${RUNNER_OWNER}\$execute"
    const val SANDBOX_BRIDGE = "com/salesforce/revoman/internal/postman/sandbox/SandboxBridge"
    const val GRAAL_CONTEXT = "org/graalvm/polyglot/Context"
    const val CONTEXT_NEW_BUILDER_DESCRIPTOR =
      "([Ljava/lang/String;)Lorg/graalvm/polyglot/Context\$Builder;"
    val COMMON_JS_OPTION_STRINGS = setOf("js.commonjs-require", "js.commonjs-require-cwd")
    val EXECUTION_PACKAGES =
      setOf(
        "com/salesforce/revoman/internal/runtime/",
        "com/salesforce/revoman/internal/exe/",
        "com/salesforce/revoman/internal/postman/",
      )
    val POSTMAN_SDK_DECLARATION_OWNERS =
      setOf(POSTMAN_SDK, POSTMAN_SDK_IMPLEMENTATION, POSTMAN_SDK_FACADE)
    val RUNNER_POSTMAN_SDK_REFERENCE_OWNERS =
      setOf(
        RUNNER_OWNER,
        "${RUNNER}\$executeStepsSerially\$\$inlined\$info\$1",
        "${RUNNER}\$executeStepsSerially\$\$inlined\$info\$2",
        "${RUNNER}\$executeStepsSerially\$\$inlined\$info\$3",
        "${RUNNER}\$executeStepsSerially\$\$inlined\$info\$4",
        "${RUNNER}\$executeStepsSerially\$\$inlined\$info\$5",
        "${RUNNER}\$executeStepsSerially\$\$inlined\$info\$6",
        "${RUNNER}\$executeStepsSerially\$\$inlined\$info\$7",
        "${RUNNER}\$executeStepsSerially\$\$inlined\$info\$8",
        "${RUNNER}\$executeStepsSerially\$\$inlined\$warn\$1",
        "${RUNNER}\$executeStepsSerially\$\$inlined\$warn\$2",
        "${RUNNER}\$executeStepsSerially\$\$inlined\$warn\$3",
        "${RUNNER}\$executeStepsSerially\$\$inlined\$warn\$4",
        "${RUNNER}\$executeStepsSerially\$\$inlined\$warn\$5",
        "${RUNNER}\$executeStepsSerially\$\$inlined\$warn\$6",
        "${RUNNER}\$executeStepsSerially\$\$inlined\$warn\$7",
        "${RUNNER}\$executeStepsSerially\$\$inlined\$warn\$8",
        "${RUNNER}\$runStep\$\$inlined\$info\$1",
        "${RUNNER}\$runStep\$\$inlined\$info\$10",
        "${RUNNER}\$runStep\$\$inlined\$info\$11",
        "${RUNNER}\$runStep\$\$inlined\$info\$12",
        "${RUNNER}\$runStep\$\$inlined\$info\$2",
        "${RUNNER}\$runStep\$\$inlined\$info\$3",
        "${RUNNER}\$runStep\$\$inlined\$info\$4",
        "${RUNNER}\$runStep\$\$inlined\$info\$5",
        "${RUNNER}\$runStep\$\$inlined\$info\$6",
        "${RUNNER}\$runStep\$\$inlined\$info\$7",
        "${RUNNER}\$runStep\$\$inlined\$info\$8",
        "${RUNNER}\$runStep\$\$inlined\$info\$9",
        "${RUNNER}\$runStep\$\$inlined\$warn\$1",
        "${RUNNER}\$runStep\$\$inlined\$warn\$2",
        "${RUNNER}\$runStep\$\$inlined\$warn\$3",
        "${RUNNER}\$runStep\$\$inlined\$warn\$4",
        "${RUNNER}\$runStep\$lambda\$5\$\$inlined\$warn\$1",
        "${RUNNER}\$runStep\$lambda\$5\$\$inlined\$warn\$2",
        "${RUNNER}\$runStep\$lambda\$5\$\$inlined\$warn\$3",
        "${RUNNER}\$runStep\$lambda\$5\$\$inlined\$warn\$4",
      )
  }
}
