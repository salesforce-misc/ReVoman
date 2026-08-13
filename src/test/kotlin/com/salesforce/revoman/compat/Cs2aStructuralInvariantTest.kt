/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.compat

import com.google.common.truth.Truth.assertThat
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class Cs2aStructuralInvariantTest {
  @Test
  fun `built root jar satisfies the complete CS2a structural contract`() {
    Cs2aStructuralContract.assertBuiltJar(configuredRootJar())
  }

  @Test
  fun `contract rejects a Java callable lifecycle operation`() {
    val model = Cs2aStructuralContract.read(configuredRootJar())
    val index =
      model.surface.indexOfFirst { entry ->
        entry.owner == EXECUTION_SESSION && entry.kind == JvmSurfaceKind.METHOD
      }
    assertThat(index).isAtLeast(0)
    val mutation =
      model.copy(
        surface =
          model.surface.toMutableList().also { entries ->
            entries[index] = entries[index].copy(sourceCallable = true, memberSynthetic = false)
          }
      )

    assertThrows<IllegalArgumentException> { Cs2aStructuralContract.assertModel(mutation) }
  }

  @Test
  fun `contract rejects an operational PostmanSDK dependency outside the allowlist`() {
    val model = Cs2aStructuralContract.read(configuredRootJar())
    val mutation =
      model.copy(
        references =
          model.references +
            JvmClassReferences(
              owner = "example/ForbiddenOperationalOwner",
              classes = setOf(POSTMAN_SDK),
              members = emptySet(),
              descriptors = emptySet(),
              strings = emptySet(),
            )
      )

    assertThrows<IllegalArgumentException> { Cs2aStructuralContract.assertModel(mutation) }
  }

  @Test
  fun `contract rejects a RunbookExe dependency on the public facade`() {
    val model = Cs2aStructuralContract.read(configuredRootJar())
    val runbook = model.references.single { it.owner == RUNBOOK_EXE }
    val mutation =
      model.copy(
        references =
          model.references.map { references ->
            if (references.owner == RUNBOOK_EXE) {
              runbook.copy(classes = runbook.classes + REVOMAN)
            } else {
              references
            }
          }
      )

    assertThrows<IllegalArgumentException> { Cs2aStructuralContract.assertModel(mutation) }
  }

  private companion object {
    const val RUNTIME_PACKAGE = "com/salesforce/revoman/internal/runtime/"
    const val EXECUTION_SESSION = "${RUNTIME_PACKAGE}ExecutionSession"
    const val POSTMAN_SDK = "com/salesforce/revoman/internal/postman/PostmanSDK"
    const val RUNBOOK_EXE = "com/salesforce/revoman/internal/exe/RunbookExeKt"
    const val REVOMAN = "com/salesforce/revoman/ReVoman"
  }
}

internal data class Cs2aStructuralModel(
  val surface: List<JvmSurfaceEntry>,
  val references: List<JvmClassReferences>,
)

internal object Cs2aStructuralContract {
  fun read(rootJar: Path): Cs2aStructuralModel =
    Cs2aStructuralModel(
      surface = JvmSurfaceInventory.readJar(rootJar),
      references = JvmSurfaceInventory.readJarReferences(rootJar),
    )

  fun assertBuiltJar(rootJar: Path) {
    assertModel(read(rootJar))
  }

  fun assertModel(model: Cs2aStructuralModel) {
    assertLegacyEvaluatorAndNodeModulesBoundary(model)
    assertRunbookBoundary(model.references)
    assertLifecycleVisibility(model.surface)
    assertPostmanDependencyBoundary(model)
  }

  private fun assertLegacyEvaluatorAndNodeModulesBoundary(model: Cs2aStructuralModel) {
    require(model.surface.none { it.owner == JSEVALUATOR }) {
      "$JSEVALUATOR reappeared in the built archive"
    }
    require(
      model.surface.none { entry ->
        entry.owner == POSTMAN_SDK &&
          entry.kind == JvmSurfaceKind.METHOD &&
          entry.name in FORBIDDEN_EVALUATOR_METHODS
      }
    ) {
      "legacy PostmanSDK evaluator method reappeared"
    }
    listOf(KICK, KICK_BUILDER).forEach { owner ->
      require(
        model.surface.any { entry ->
          entry.owner == owner &&
            entry.kind == JvmSurfaceKind.METHOD &&
            entry.name == NODE_MODULES_PATH &&
            entry.sourceCallable
        }
      ) {
        "$owner#$NODE_MODULES_PATH disappeared from the supported source surface"
      }
    }
    val contextBuilders =
      model.references.filter { references ->
        references.members.any { member ->
          member.owner == GRAAL_CONTEXT &&
            member.name == "newBuilder" &&
            member.descriptor == CONTEXT_BUILDER_DESCRIPTOR
        }
      }
    require(contextBuilders.map(JvmClassReferences::owner) == listOf(SANDBOX_BRIDGE)) {
      "Context.newBuilder owners must be exactly $SANDBOX_BRIDGE: ${contextBuilders.map { it.owner }}"
    }
    val operationalOwners =
      model.references.filter { refs ->
        refs.owner == REVOMAN || EXECUTION_PACKAGES.any(refs.owner::startsWith)
      }
    require(
      operationalOwners.none { refs ->
        refs.members.any { it.name == NODE_MODULES_PATH } ||
          refs.strings.any { it in COMMON_JS_OPTION_STRINGS }
      }
    ) {
      "an executor/evaluator consumes deprecated nodeModulesPath"
    }
  }

  private fun assertRunbookBoundary(references: List<JvmClassReferences>) {
    references
      .filter { it.owner == RUNBOOK_EXE || it.owner.startsWith("$RUNBOOK_EXE\$") }
      .forEach { refs ->
        require(REVOMAN !in refs.classes) { "${refs.owner} names public ReVoman" }
        require(
          refs.members.none { member ->
            member.owner == REVOMAN || member.descriptor.contains("L$REVOMAN;")
          } && refs.descriptors.none { it.contains("L$REVOMAN;") }
        ) {
          "${refs.owner} calls or carries the public ReVoman boundary"
        }
      }
  }

  private fun assertLifecycleVisibility(surface: List<JvmSurfaceEntry>) {
    LIFECYCLE_INTERFACE_OWNERS.forEach { owner ->
      val rows = surface.filter { it.owner == owner }
      require(rows.isNotEmpty()) { "missing lifecycle owner $owner" }
      require(rows.filter { it.kind != JvmSurfaceKind.CLASS }.none { it.sourceCallable }) {
        "$owner exposes a Java-source-callable operation"
      }
    }
    LIFECYCLE_IMPLEMENTATION_OWNERS.forEach { owner ->
      val rows = surface.filter { it.owner == owner }
      require(rows.isNotEmpty()) { "missing lifecycle implementation $owner" }
      require(rows.none { it.sourceCallable }) { "$owner became Java source callable" }
    }
    LIFECYCLE_FACTORY_METHODS.forEach { (owner, names) ->
      names.forEach { name ->
        val rows = surface.filter { entry ->
          entry.owner == owner && entry.kind == JvmSurfaceKind.METHOD && entry.name == name
        }
        require(rows.isNotEmpty()) { "missing lifecycle factory $owner#$name" }
        require(rows.none { it.sourceCallable } && rows.all { it.memberSynthetic }) {
          "$owner#$name became Java source callable"
        }
      }
    }
    val diagnostics = surface.filter { it.owner == LIFECYCLE_DIAGNOSTICS }
    require(diagnostics.isNotEmpty()) { "missing lifecycle diagnostics facade" }
    require(diagnostics.none { it.sourceCallable }) {
      "lifecycle diagnostics exposes Java-source-callable state or operations"
    }
  }

  private fun assertPostmanDependencyBoundary(model: Cs2aStructuralModel) {
    val referenceOwners =
      model.references
        .filter { refs ->
          refs.classes.any { reference -> classReferenceNamesOwner(reference, POSTMAN_SDK) } ||
            refs.members.any {
              it.owner == POSTMAN_SDK || it.descriptor.contains("L$POSTMAN_SDK;")
            } ||
            refs.descriptors.any { it.contains("L$POSTMAN_SDK;") } ||
            model.surface.any {
              it.owner == refs.owner && it.descriptor.contains("L$POSTMAN_SDK;")
            }
        }
        .mapTo(linkedSetOf()) { it.owner }
    require(referenceOwners == POSTMAN_SDK_REFERENCE_OWNERS) {
      "PostmanSDK reference owners differ: expected=$POSTMAN_SDK_REFERENCE_OWNERS, actual=$referenceOwners"
    }
  }

  private fun classReferenceNamesOwner(reference: String, owner: String): Boolean {
    if (reference == owner) return true
    val componentStart = reference.indexOfFirst { it != '[' }
    return componentStart > 0 && reference.substring(componentStart) == "L$owner;"
  }

  private const val POSTMAN_PACKAGE = "com/salesforce/revoman/internal/postman/"
  private const val RUNTIME_PACKAGE = "com/salesforce/revoman/internal/runtime/"
  private const val POSTMAN_SDK = "${POSTMAN_PACKAGE}PostmanSDK"
  private const val POSTMAN_SDK_FACADE = "${POSTMAN_PACKAGE}PostmanSDKKt"
  private const val POSTMAN_SDK_IMPLEMENTATION = "$POSTMAN_SDK_FACADE\$postmanSDK\$1"
  private const val JSEVALUATOR = "$POSTMAN_SDK\$JSEvaluator"
  private const val KICK = "com/salesforce/revoman/input/config/Kick"
  private const val KICK_BUILDER = "$KICK\$Builder"
  private const val REVOMAN = "com/salesforce/revoman/ReVoman"
  private const val RUNBOOK_EXE = "com/salesforce/revoman/internal/exe/RunbookExeKt"
  private const val SANDBOX_BRIDGE = "${POSTMAN_PACKAGE}sandbox/SandboxBridge"
  private const val GRAAL_CONTEXT = "org/graalvm/polyglot/Context"
  private const val CONTEXT_BUILDER_DESCRIPTOR =
    "([Ljava/lang/String;)Lorg/graalvm/polyglot/Context\$Builder;"
  private const val NODE_MODULES_PATH = "nodeModulesPath"
  private const val LIFECYCLE_DIAGNOSTICS = "${RUNTIME_PACKAGE}ExecutionLifecycleDiagnostics"
  private val FORBIDDEN_EVALUATOR_METHODS = setOf("evaluateJS", "jsonStrToObj")
  private val COMMON_JS_OPTION_STRINGS = setOf("js.commonjs-require", "js.commonjs-require-cwd")
  private val EXECUTION_PACKAGES =
    setOf(
      "com/salesforce/revoman/internal/runtime/",
      "com/salesforce/revoman/internal/exe/",
      "com/salesforce/revoman/internal/postman/",
    )
  private val LIFECYCLE_INTERFACE_OWNERS =
    setOf(
      "${RUNTIME_PACKAGE}InternalCloseable",
      "${RUNTIME_PACKAGE}ResourceScope",
      "${RUNTIME_PACKAGE}ScriptExecutor",
      "${RUNTIME_PACKAGE}SandboxRuntime",
      "${RUNTIME_PACKAGE}SandboxFactory",
      "${RUNTIME_PACKAGE}KickExecution",
      "${RUNTIME_PACKAGE}KickExecutionFactory",
      "${RUNTIME_PACKAGE}KickBody",
      "${RUNTIME_PACKAGE}ExecutionSession",
      "${RUNTIME_PACKAGE}ExecutionSessionFactory",
      "${RUNTIME_PACKAGE}ReVomanRuntime",
    )
  private val LIFECYCLE_FACTORY_METHODS =
    mapOf(
      "${RUNTIME_PACKAGE}ResourceScopeKt" to setOf("resourceScope"),
      "${RUNTIME_PACKAGE}KickExecutionKt" to setOf("kickExecution"),
      "${RUNTIME_PACKAGE}KickRunnerKt" to setOf("kickExecutionFactory"),
      "${RUNTIME_PACKAGE}ExecutionSessionKt" to
        setOf("executionSession", "executionSessionFactory"),
      "${RUNTIME_PACKAGE}ReVomanRuntimeKt" to setOf("reVomanRuntime"),
    )
  private val LIFECYCLE_IMPLEMENTATION_OWNERS =
    setOf(
      "${RUNTIME_PACKAGE}ResourceScopeKt\$resourceScope\$1",
      "${RUNTIME_PACKAGE}KickExecutionKt\$kickExecution\$1",
      "${RUNTIME_PACKAGE}KickExecutionKt\$kickExecution\$1\$executor\$1",
      "${RUNTIME_PACKAGE}ExecutionSessionKt\$executionSession\$1",
      "${RUNTIME_PACKAGE}ExecutionSessionKt\$executionSessionFactory\$1",
      "${RUNTIME_PACKAGE}KickRunnerKt\$kickExecutionFactory\$1",
      "${RUNTIME_PACKAGE}KickRunnerKt\$kickExecutionFactory\$body\$1",
      "${RUNTIME_PACKAGE}ReVomanRuntimeKt\$reVomanRuntime\$1",
      "${RUNTIME_PACKAGE}ReVomanRuntimeKt\$reVomanRuntime\$sandboxFactory\$1",
    )
  private const val RUNNER_OWNER = "${RUNTIME_PACKAGE}KickRunnerKt\$kickExecutionFactory\$body\$1"
  private const val RUNNER = "$RUNNER_OWNER\$execute"
  private val POSTMAN_SDK_REFERENCE_OWNERS = buildSet {
    add(POSTMAN_SDK)
    add(POSTMAN_SDK_IMPLEMENTATION)
    add(POSTMAN_SDK_FACADE)
    add(RUNNER_OWNER)
    (1..8).forEach { add("$RUNNER\$executeStepsSerially\$\$inlined\$info\$$it") }
    (1..8).forEach { add("$RUNNER\$executeStepsSerially\$\$inlined\$warn\$$it") }
    (1..12).forEach { add("$RUNNER\$runStep\$\$inlined\$info\$$it") }
    (1..4).forEach { add("$RUNNER\$runStep\$\$inlined\$warn\$$it") }
    (1..4).forEach { add("$RUNNER\$runStep\$lambda\$5\$\$inlined\$warn\$$it") }
  }
}
