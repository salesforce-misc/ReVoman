/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.compat

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.jar.JarOutputStream
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class JvmSurfaceVisibilityTest {
  @TempDir lateinit var temporaryDirectory: Path

  @Test
  fun `classfile reader accepts the JDK 21 classfile version`() {
    validateJvmClassFile(minimalClassFile(majorVersion = 65))
  }

  @Test
  fun `classfile reader rejects versions outside its supported range`() {
    val newerFailure =
      assertThrows<IllegalArgumentException> {
        validateJvmClassFile(minimalClassFile(majorVersion = 66))
      }
    val invalidFailure =
      assertThrows<IllegalArgumentException> {
        validateJvmClassFile(minimalClassFile(majorVersion = 44))
      }

    assertThat(newerFailure).hasMessageThat().contains("supported classfile major range 45..65")
    assertThat(invalidFailure).hasMessageThat().contains("supported classfile major range 45..65")
  }

  @Test
  fun `classfile reader rejects truncated and trailing class bytes`() {
    val valid = minimalClassFile(majorVersion = 65)

    assertThrows<EOFException> { validateJvmClassFile(valid.copyOf(valid.size - 1)) }
    val trailingFailure =
      assertThrows<IllegalArgumentException> {
        validateJvmClassFile(valid + byteArrayOf(0x01))
      }
    assertThat(trailingFailure).hasMessageThat().contains("trailing classfile bytes")
  }

  @Test
  fun `classfile reader rejects trailing InnerClasses attribute bytes`() {
    val failure =
      assertThrows<IllegalArgumentException> {
        validateJvmClassFile(
          minimalClassFile(majorVersion = 65, innerClassesTrailingBytes = byteArrayOf(0x01))
        )
      }

    assertThat(failure).hasMessageThat().contains("trailing InnerClasses attribute bytes")
  }

  @Test
  fun `classfile reader resolves exact constant-pool references including forward descriptors`() {
    val references = readJvmClassReferences(referenceClassFile())

    assertThat(references.owner).isEqualTo("example/Fixture")
    assertThat(references.classes)
      .containsExactly(
        "example/Fixture",
        "java/lang/Object",
        "target/Owner",
        "target/Interface",
        "[Ltarget/ArrayOnly;",
      )
    assertThat(references.members)
      .containsExactly(
        JvmMemberReference(
          JvmReferenceKind.FIELD,
          "target/Owner",
          "field",
          "Ltarget/DescriptorOnly;",
        ),
        JvmMemberReference(JvmReferenceKind.METHOD, "target/Owner", "method", "()V"),
        JvmMemberReference(
          JvmReferenceKind.INTERFACE_METHOD,
          "target/Interface",
          "invoke",
          "(Ltarget/DescriptorOnly;)V",
        ),
      )
    assertThat(references.strings).containsExactly("exact-option-string")
    assertThat(references.descriptors)
      .containsAtLeast(
        "Ltarget/DescriptorOnly;",
        "()V",
        "(Ltarget/DescriptorOnly;)V",
        "(Ltarget/OnlyInNameAndType;)V",
        "(Ltarget/OnlyInMethodType;)V",
      )
  }

  @Test
  fun `built jar exposes focused types but no Java-source-callable operations`() {
    val entries = JvmSurfaceInventory.readJar(configuredRootJar())
    val classRows =
      entries.asSequence().filter { it.kind == JvmSurfaceKind.CLASS }.associateBy { it.owner }

    TASK4_FOCUSED_INTERFACE_OWNERS.forEach { owner ->
      assertWithMessage("missing focused class row for $owner").that(classRows[owner]).isNotNull()
      assertWithMessage("focused interface remains nameable: $owner")
        .that(classRows.getValue(owner).sourceCallable)
        .isTrue()
      assertThat(
          entries.filter {
            it.owner == owner && it.kind == JvmSurfaceKind.METHOD
          }
        )
        .isNotEmpty()
      assertThat(
          entries
            .filter { it.owner == owner && it.kind == JvmSurfaceKind.METHOD }
            .all { it.memberSynthetic && !it.sourceCallable }
        )
        .isTrue()
    }

    TASK4_FOCUSED_FACTORY_OWNERS.forEach { (owner, factory) ->
      val row = entries.single {
        it.owner == owner && it.kind == JvmSurfaceKind.METHOD && it.name == factory
      }
      assertThat(row.memberSynthetic).isTrue()
      assertThat(row.sourceCallable).isFalse()
    }
    TASK4_FOCUSED_IMPLEMENTATION_OWNERS.forEach { owner ->
      val rows = entries.filter { it.owner == owner }
      assertThat(rows).isNotEmpty()
      assertThat(rows.single { it.kind == JvmSurfaceKind.CLASS }.sourceCallable).isFalse()
      assertThat(rows.single { it.kind == JvmSurfaceKind.CONSTRUCTOR }.memberAccess and 0x0005)
        .isEqualTo(0)
    }
  }

  @Test
  fun `ReVoman carries no operational PostmanSDK reference after runner extraction`() {
    val references = JvmSurfaceInventory.readJarReferences(configuredRootJar())
    val reVomanReferences = references.single { it.owner == REVOMAN }

    assertThat(reVomanReferences.classes).doesNotContain(POSTMAN_SDK)
    assertThat(
        reVomanReferences.members.filter {
          it.owner == POSTMAN_SDK || it.descriptor.contains("L$POSTMAN_SDK;")
        }
      )
      .isEmpty()
    assertThat(reVomanReferences.descriptors.filter { it.contains("L$POSTMAN_SDK;") }).isEmpty()
  }

  @Test
  fun `Task 7 cumulative additions and removals have the exact raw surface`() {
    val entries = JvmSurfaceInventory.readJar(configuredRootJar())
    val frozen = JvmSurfaceInventory.parse(Files.readString(FROZEN_JVM_ABI))
    val frozenRows = frozen.asSequence().map(JvmSurfaceEntry::render).toSet()
    val additions = entries.filter { it.render() !in frozenRows }
    val cs2Additions = additions.filter { it.render() in CS2_TASK7_RAW_JVM_ADDITIONS }
    val mockServerAdditions = additions.filter { it.render() in MOCK_HTTP_SERVER_RAW_JVM_ADDITIONS }
    val activeRows = entries.asSequence().map(JvmSurfaceEntry::render).toSet()
    val removals = frozen.filter { it.render() !in activeRows }

    assertThat(cs2Additions.map(JvmSurfaceEntry::render))
      .containsExactlyElementsIn(CS2_TASK7_RAW_JVM_ADDITIONS)
    assertThat(mockServerAdditions.map(JvmSurfaceEntry::render))
      .containsExactlyElementsIn(MOCK_HTTP_SERVER_RAW_JVM_ADDITIONS)
    assertThat(additions.map(JvmSurfaceEntry::render))
      .containsExactlyElementsIn(APPROVED_RAW_JVM_ADDITIONS)
    assertThat(removals.map(JvmSurfaceEntry::render))
      .containsExactlyElementsIn(CS2_TASK7_RAW_JVM_REMOVALS)
    val pmSandboxRows = entries.filter {
      it.owner == "com/salesforce/revoman/internal/postman/sandbox/PmSandbox"
    }
    assertThat(
        pmSandboxRows.single { it.kind == JvmSurfaceKind.FIELD && it.name == "bridge" }.memberAccess
      )
      .isEqualTo(0x0012)
    assertThat(
        pmSandboxRows
          .filter { it.kind == JvmSurfaceKind.CONSTRUCTOR && it.sourceCallable }
          .map(JvmSurfaceEntry::descriptor)
      )
      .containsExactly("()V")
    assertThat(
        entries.any {
          it.owner.startsWith("com/salesforce/revoman/internal/postman/sandbox/PmSandbox") &&
            it.descriptor == "Ljava/lang/ThreadLocal;"
        }
      )
      .isFalse()
    assertThat(
        entries.any {
          it.owner.startsWith("com/salesforce/revoman/internal/postman/sandbox/") &&
            (it.name.startsWith("pmSandboxForTest") ||
              it.name.startsWith("withRuntimeHooks") ||
              it.name.startsWith("resetForTest") ||
              it.name.startsWith("resetDefaultForTest"))
        }
      )
      .isFalse()
    val addedTask3Classes = cs2Additions.filter {
      it.kind == JvmSurfaceKind.CLASS && it.owner in TASK3_RUNTIME_OWNERS
    }
    assertThat(addedTask3Classes.map(JvmSurfaceEntry::owner))
      .containsExactlyElementsIn(TASK3_RUNTIME_OWNERS)
    assertThat(addedTask3Classes.filter(JvmSurfaceEntry::sourceCallable)).hasSize(11)
    assertThat(
        addedTask3Classes.filterNot(JvmSurfaceEntry::sourceCallable).map(JvmSurfaceEntry::owner)
      )
      .containsExactlyElementsIn(
        setOf(
          RESOURCE_SCOPE_IMPLEMENTATION,
          KICK_EXECUTION_IMPLEMENTATION,
          KICK_EXECUTOR_IMPLEMENTATION,
        )
      )
    assertThat(
        cs2Additions
          .filter { it.kind == JvmSurfaceKind.CLASS && it.owner.endsWith("\$Companion") }
          .map(JvmSurfaceEntry::owner)
      )
      .isEmpty()
    assertThat(cs2Additions.filter { it.kind == JvmSurfaceKind.FIELD }.map(JvmSurfaceEntry::name))
      .doesNotContain("INSTANCE")
    assertThat(cs2Additions.filter { it.owner in KOTLIN_ONLY_INTERFACE_OWNERS }).isNotEmpty()
    assertThat(
        cs2Additions
          .filter { it.owner in KOTLIN_ONLY_INTERFACE_OWNERS && it.kind == JvmSurfaceKind.METHOD }
          .all { it.memberSynthetic && !it.sourceCallable }
      )
      .isTrue()

    val diagnosticsRows = cs2Additions.filter { it.owner == LIFECYCLE_DIAGNOSTICS_OWNER }
    assertThat(diagnosticsRows).hasSize(15)
    assertThat(
        diagnosticsRows
          .single {
            it.kind == JvmSurfaceKind.METHOD && it.name == "drain"
          }
          .descriptor
      )
      .isEqualTo("()[Ljava/lang/Object;")
    assertThat(diagnosticsRows.filter { it.kind == JvmSurfaceKind.FIELD }.map { it.name })
      .doesNotContain("INSTANCE")
    assertThat(
        diagnosticsRows
          .filter { it.kind == JvmSurfaceKind.FIELD }
          .all { row -> row.memberAccess and 0x0002 != 0 }
      )
      .isTrue()
    assertThat(
        diagnosticsRows.filter { row ->
          row.kind == JvmSurfaceKind.FIELD &&
            (row.descriptor.contains("ExecutionSession") ||
              row.descriptor.contains("KickExecution") ||
              row.descriptor == "Ljava/lang/Throwable;")
        }
      )
      .isEmpty()

    TASK5_INTERFACE_OWNERS.forEach { owner ->
      val methods = cs2Additions.filter { it.owner == owner && it.kind == JvmSurfaceKind.METHOD }
      assertThat(methods).isNotEmpty()
      assertThat(methods.all { it.memberSynthetic && !it.sourceCallable }).isTrue()
    }
    TASK5_FACTORY_METHODS.forEach { (owner, names) ->
      assertThat(
          cs2Additions
            .filter { it.owner == owner && it.kind == JvmSurfaceKind.METHOD && it.name in names }
            .map(JvmSurfaceEntry::name)
        )
        .containsExactlyElementsIn(names)
      assertThat(
          cs2Additions
            .filter { it.owner == owner && it.kind == JvmSurfaceKind.METHOD && it.name in names }
            .all { it.memberSynthetic && !it.sourceCallable }
        )
        .isTrue()
    }
    TASK5_ANONYMOUS_OWNERS.forEach { owner ->
      val ownerRows = cs2Additions.filter { it.owner == owner }
      assertThat(ownerRows).isNotEmpty()
      assertThat(ownerRows.single { it.kind == JvmSurfaceKind.CLASS }.sourceCallable).isFalse()
      assertThat(ownerRows.all { !it.sourceCallable }).isTrue()
    }
    assertThat(
        JvmSurfaceInventory.readJarReferences(configuredRootJar())
          .filter { it.owner in TASK5_DECLARED_AND_ANONYMOUS_OWNERS }
          .flatMap { it.classes }
      )
      .doesNotContain("java/lang/AutoCloseable")
    val task6Rows = cs2Additions.filter { it.owner in TASK6_ROUTE_OWNERS }
    assertThat(task6Rows).isNotEmpty()
    assertThat(
        task6Rows.filter {
          it.kind == JvmSurfaceKind.FIELD &&
            (it.name.contains("currentRuntime", ignoreCase = true) ||
              it.name.contains("currentSession", ignoreCase = true) ||
              it.descriptor == "Ljava/lang/ThreadLocal;")
        }
      )
      .isEmpty()
    assertThat(
        task6Rows.filter {
          it.kind == JvmSurfaceKind.CLASS && it.owner.endsWith("\$Companion")
        }
      )
      .isEmpty()
    assertThat(task6Rows.filter { it.kind == JvmSurfaceKind.FIELD }.map(JvmSurfaceEntry::name))
      .doesNotContain("INSTANCE")
    assertThat(
        JvmSurfaceInventory.readJarReferences(configuredRootJar())
          .filter { it.owner in TASK6_ROUTE_OWNERS }
          .flatMap { it.classes }
      )
      .doesNotContain("java/lang/AutoCloseable")
    assertThat(
        cs2Additions
          .filter {
            it.owner in KOTLIN_ONLY_FACADE_OWNERS && it.kind == JvmSurfaceKind.METHOD
          }
          .all { !it.sourceCallable }
      )
      .isTrue()
    assertThat(
        cs2Additions
          .filter {
            it.owner == "${RUNTIME_PACKAGE}KickExecutionKt" &&
              it.kind == JvmSurfaceKind.METHOD &&
              it.name in setOf("kickExecution", "kickExecution\$default")
          }
          .all { it.memberSynthetic && !it.sourceCallable }
      )
      .isTrue()

    setOf(
        RESOURCE_SCOPE_IMPLEMENTATION,
        KICK_EXECUTION_IMPLEMENTATION,
        KICK_EXECUTOR_IMPLEMENTATION,
      )
      .forEach { implementation ->
        val implementationRows = cs2Additions.filter { it.owner == implementation }
        assertThat(implementationRows).isNotEmpty()
        assertThat(implementationRows.all { !it.sourceCallable }).isTrue()
        assertThat(implementationRows.single { it.kind == JvmSurfaceKind.CLASS }.sourceCallable)
          .isFalse()
        assertThat(
            implementationRows.single { it.kind == JvmSurfaceKind.CONSTRUCTOR }.memberAccess and
              0x0005
          )
          .isEqualTo(0)
      }
  }

  @Test
  fun `mock HTTP server exposes exactly its supported Java source surface`() {
    val entries = JvmSurfaceInventory.readJar(configuredRootJar())
    val featureRows = entries.filter { it.owner.startsWith(MOCK_HTTP_SERVER_PACKAGE) }
    val sourceCallableOwners =
      featureRows.filter(JvmSurfaceEntry::sourceCallable).map(JvmSurfaceEntry::owner).toSet()

    assertThat(sourceCallableOwners)
      .containsExactly(
        MOCK_HTTP_HANDLER,
        MOCK_HTTP_SERVER,
        MOCK_HTTP_SERVER_COMPANION,
        RECORDED_HTTP_REQUEST,
        RECORDED_HTTP_REQUEST_COMPANION,
        RECORDED_NAME_VALUE,
      )
    assertSourceCallableMembers(
      featureRows,
      MOCK_HTTP_HANDLER,
      "METHOD|handle|(Lorg/http4k/core/Request;)Lorg/http4k/core/Response;",
    )
    assertRecordedNameValueSurface(featureRows)
    assertSourceCallableMembers(
      featureRows,
      RECORDED_HTTP_REQUEST,
      "FIELD|Companion|L$RECORDED_HTTP_REQUEST_COMPANION;",
      "METHOD|bodyBytes|()[B",
      "METHOD|bodyString|()Ljava/lang/String;",
      "METHOD|bodyString|(Ljava/nio/charset/Charset;)Ljava/lang/String;",
      "METHOD|getHeaders|()Ljava/util/List;",
      "METHOD|getMethod|()Lorg/http4k/core/Method;",
      "METHOD|getPath|()Ljava/lang/String;",
      "METHOD|getQueryParameters|()Ljava/util/List;",
    )
    assertSourceCallableMembers(featureRows, RECORDED_HTTP_REQUEST_COMPANION)
    assertSourceCallableMembers(
      featureRows,
      MOCK_HTTP_SERVER,
      "FIELD|Companion|L$MOCK_HTTP_SERVER_COMPANION;",
      "METHOD|close|()V",
      "METHOD|getBaseUrl|()Ljava/lang/String;",
      "METHOD|requests|()Ljava/util/List;",
      "METHOD|start|(L$MOCK_HTTP_HANDLER;)L$MOCK_HTTP_SERVER;",
    )
    assertSourceCallableMembers(
      featureRows,
      MOCK_HTTP_SERVER_COMPANION,
      "METHOD|start|(L$MOCK_HTTP_HANDLER;)L$MOCK_HTTP_SERVER;",
    )
    assertThat(
        featureRows.filter {
          it.owner.startsWith(MOCK_HTTP_SERVER_INTERNAL_PACKAGE) && it.sourceCallable
        }
      )
      .isEmpty()
  }

  private fun assertRecordedNameValueSurface(featureRows: List<JvmSurfaceEntry>) {
    assertSourceCallableMembers(
      featureRows,
      RECORDED_NAME_VALUE,
      "CONSTRUCTOR|<init>|(Ljava/lang/String;Ljava/lang/String;)V",
      "METHOD|component1|()Ljava/lang/String;",
      "METHOD|component2|()Ljava/lang/String;",
      "METHOD|copy|(Ljava/lang/String;Ljava/lang/String;)L$RECORDED_NAME_VALUE;",
      "METHOD|equals|(Ljava/lang/Object;)Z",
      "METHOD|hashCode|()I",
      "METHOD|name|()Ljava/lang/String;",
      "METHOD|toString|()Ljava/lang/String;",
      "METHOD|value|()Ljava/lang/String;",
    )
  }

  private fun assertSourceCallableMembers(
    rows: List<JvmSurfaceEntry>,
    owner: String,
    vararg expected: String,
  ) {
    val actual =
      rows
        .asSequence()
        .filter { it.owner == owner && it.kind != JvmSurfaceKind.CLASS && it.sourceCallable }
        .map { "${it.kind}|${it.name}|${it.descriptor}" }
        .toSet()
    assertWithMessage("Java-source-callable members for $owner")
      .that(actual)
      .containsExactlyElementsIn(expected.asList())
  }

  @Test
  fun `external and same-package Java cannot name the lifecycle diagnostics facade`() {
    listOf(
        """
        import com.salesforce.revoman.internal.runtime.ExecutionLifecycleDiagnostics;

        final class LifecycleDiagnosticsExternalConsumer {
          static Object[] drain() { return ExecutionLifecycleDiagnostics.drain(); }
        }
        """
          .trimIndent(),
        """
        package com.salesforce.revoman.internal.runtime;

        final class LifecycleDiagnosticsPackageConsumer {
          static Object[] drain() { return ExecutionLifecycleDiagnostics.drain(); }
        }
        """
          .trimIndent(),
      )
      .forEachIndexed { index, source ->
        val result = compileJava("LifecycleDiagnosticsConsumer$index", source)
        assertWithMessage("lifecycle facade must be rejected: ${result.diagnostics}")
          .that(result.compiled)
          .isFalse()
        val errorCodes =
          result.diagnostics.filter { it.kind == Diagnostic.Kind.ERROR }.map(JavaDiagnostic::code)
        assertWithMessage("exact access/member diagnostics: ${result.diagnostics}")
          .that(errorCodes.any { it in CANNOT_RESOLVE_MEMBER_CODES + CANNOT_ACCESS_CODES })
          .isTrue()
        assertThat(errorCodes).doesNotContain("compiler.err.cant.apply.symbol")
      }
  }

  @Test
  fun `external Java source can name Task 3 boundary types`() {
    val result =
      compileJava(
        "RuntimeTypeReferenceConsumer",
        """
        import com.salesforce.revoman.internal.runtime.*;

        final class RuntimeTypeReferenceConsumer {
          InternalCloseable closeable;
          ResourceScope scope;
          ScriptExecutor executor;
          SandboxRuntime runtime;
          SandboxFactory factory;
          KickExecution kickExecution;
          KickExecutionFactory kickExecutionFactory;
          KickBody kickBody;
          ExecutionSession session;
          ExecutionSessionFactory sessionFactory;
          ReVomanRuntime revomanRuntime;
        }
        """
          .trimIndent(),
      )

    assertWithMessage("javac diagnostics: ${result.diagnostics}").that(result.compiled).isTrue()
  }

  @Test
  fun `external Java source cannot operate Task 3 runtime boundaries`() {
    val attempts =
      listOf(
        JavaBoundaryAttempt(
          "KickExecutionConsumer",
          "static ScriptExecutor access(KickExecution value) { value.getScripts(); return value.getScripts(); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "KickExecutionExecuteConsumer",
          "static Object access(KickExecution value) { return value.execute(); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "KickExecutionFactoryPortConsumer",
          "static Object access(KickExecutionFactory value) { return value.create(null, null); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "KickBodyConsumer",
          "static Object access(KickBody value) { return value.execute(null); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "ExecutionSessionConsumer",
          "static Object access(ExecutionSession value) { return value.executeKick(null, false, null); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "ExecutionSessionFactoryConsumer",
          "static Object access(ExecutionSessionFactory value) { return value.open(null); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "ReVomanRuntimeConsumer",
          "static Object access(ReVomanRuntime value) { return value.execute(null); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "ExecutionSessionTopLevelFactoryConsumer",
          "static Object access() { return ExecutionSessionKt.executionSession(null, null); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "ReVomanRuntimeTopLevelFactoryConsumer",
          "static Object access() { return ReVomanRuntimeKt.reVomanRuntime(); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "KickRunnerTopLevelFactoryConsumer",
          "static Object access() { return KickRunnerKt.kickExecutionFactory(null); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "KickExecutionCloseConsumer",
          "static void access(KickExecution value) { value.close(); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "KickExecutionFactoryConsumer",
          "static KickExecution access() { return KickExecutionKt.kickExecution(null); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "InternalCloseableConsumer",
          "static void access(InternalCloseable value) { value.close(); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "ResourceScopeConsumer",
          "static void access(ResourceScope value) { value.closeAfter(null); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "ResourceScopeFactoryConsumer",
          "static ResourceScope access() { return ResourceScopeKt.resourceScope(); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "UseInternalConsumer",
          "static Object access(InternalCloseable value) { return InternalCloseableKt.useInternal(value, ignored -> null); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "ScriptExecutorConsumer",
          "static Object access(ScriptExecutor value) { return value.execute(null, null, null, 1L); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "ScriptDefaultBridgeConsumer",
          "static Object access(ScriptExecutor value) { return ScriptExecutor.DefaultImpls.execute\$default(value, null, null, null, 0L, 8, null); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "SandboxRuntimeConsumer",
          "static void access(SandboxRuntime value) { value.close(); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "SandboxFactoryConsumer",
          "static SandboxRuntime access(SandboxFactory value) { return value.create(); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "SandboxFactoryLambdaConsumer",
          "static SandboxFactory access() { return () -> null; }",
          setOf("compiler.err.prob.found.req"),
        ),
        JavaBoundaryAttempt(
          "DefaultResourceScopeConsumer",
          "static Object access() { return new DefaultResourceScope(); }",
          setOf("compiler.err.cant.resolve.location"),
        ),
      )

    attempts.forEach { attempt ->
      val result =
        compileJava(
          attempt.className,
          """
          import com.salesforce.revoman.internal.runtime.*;

          final class ${attempt.className} {
            ${attempt.access}
          }
          """
            .trimIndent(),
        )

      assertWithMessage(
          "${attempt.className} mutation must be rejected by javac: ${result.diagnostics}"
        )
        .that(result.compiled)
        .isFalse()
      assertThat(result.diagnostics.map(JavaDiagnostic::kind)).contains(Diagnostic.Kind.ERROR)
      assertWithMessage("targeted javac diagnostics: ${result.diagnostics}")
        .that(
          result.diagnostics
            .filter { it.kind == Diagnostic.Kind.ERROR }
            .map(JavaDiagnostic::code)
            .any { it in attempt.expectedDiagnosticCodes }
        )
        .isTrue()
    }
  }

  @Test
  fun `preserved runbook adapter is Java callable while Task 6 session routes are not`() {
    val positive =
      compileJava(
        "RunbookAdapterConsumer",
        """
        import com.salesforce.revoman.internal.exe.RunbookExeKt;
        import com.salesforce.revoman.input.config.Runbook;
        import com.salesforce.revoman.output.RunbookRundown;
        import java.util.Map;

        final class RunbookAdapterConsumer {
          static RunbookRundown execute(Runbook runbook, Map<String, Object> environment) {
            return RunbookExeKt.executeRunbook(runbook, environment);
          }
        }
        """
          .trimIndent(),
      )
    assertWithMessage("javac diagnostics: ${positive.diagnostics}").that(positive.compiled).isTrue()

    val attempts =
      listOf(
        JavaBoundaryAttempt(
          "RuntimeListConsumer",
          "static Object access(ReVomanRuntime value) { return value.execute((java.util.List)null, null, null); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "RuntimeRunbookConsumer",
          "static Object access(ReVomanRuntime value) { return value.execute((com.salesforce.revoman.input.config.Runbook)null, null); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
        JavaBoundaryAttempt(
          "RunbookSessionHelperConsumer",
          "static Object access(ExecutionSession session) { return com.salesforce.revoman.internal.exe.RunbookExeKt.executeRunbookInSession(session, null, null); }",
          CANNOT_RESOLVE_MEMBER_CODES,
        ),
      )
    attempts.forEach { attempt ->
      val result =
        compileJava(
          attempt.className,
          """
          import com.salesforce.revoman.internal.runtime.*;

          final class ${attempt.className} {
            ${attempt.access}
          }
          """
            .trimIndent(),
        )
      assertWithMessage("${attempt.className} must be rejected: ${result.diagnostics}")
        .that(result.compiled)
        .isFalse()
      assertWithMessage("targeted javac diagnostics: ${result.diagnostics}")
        .that(
          result.diagnostics
            .filter { it.kind == Diagnostic.Kind.ERROR }
            .map(JavaDiagnostic::code)
            .any { it in attempt.expectedDiagnosticCodes }
        )
        .isTrue()
      assertThat(result.diagnostics.map(JavaDiagnostic::code))
        .doesNotContain("compiler.err.cant.apply.symbol")
      if (attempt.className == "RunbookSessionHelperConsumer") {
        assertThat(
            result.diagnostics.filter { it.kind == Diagnostic.Kind.ERROR }.map(JavaDiagnostic::code)
          )
          .containsExactly("compiler.err.cant.resolve.location.args")
      }
    }
  }

  @Test
  fun `same-package Java cannot operate Task 6 synthetic routes`() {
    val attempts =
      listOf(
        SamePackageAttempt(
          "SamePackageRuntimeListConsumer",
          "ReVomanRuntime.execute(List, PostExeHook, Map)",
          "((ReVomanRuntime) null).execute((java.util.List) null, null, null)",
        ),
        SamePackageAttempt(
          "SamePackageRuntimeRunbookConsumer",
          "ReVomanRuntime.execute(Runbook, Map)",
          "((ReVomanRuntime) null).execute((com.salesforce.revoman.input.config.Runbook) null, null)",
        ),
      )
    attempts.forEach { attempt ->
      val result =
        compileJava(
          attempt.className,
          """
          package com.salesforce.revoman.internal.runtime;

          final class ${attempt.className} {
            static Object access() { return ${attempt.operation}; }
          }
          """
            .trimIndent(),
        )
      assertWithMessage("same-package mutation must be rejected: ${result.diagnostics}")
        .that(result.compiled)
        .isFalse()
      val errorCodes =
        result.diagnostics.filter { it.kind == Diagnostic.Kind.ERROR }.map(JavaDiagnostic::code)
      assertWithMessage("targeted javac diagnostics: ${result.diagnostics}")
        .that(
          errorCodes.any {
            it in CANNOT_RESOLVE_MEMBER_CODES + CANNOT_ACCESS_CODES
          }
        )
        .isTrue()
      assertThat(errorCodes).doesNotContain("compiler.err.cant.apply.symbol")
    }
  }

  @Test
  fun `same RunbookExe package Java cannot name the synthetic session helper`() {
    val result =
      compileJava(
        "SamePackageRunbookSessionHelperConsumer",
        """
        package com.salesforce.revoman.internal.exe;

        import com.salesforce.revoman.internal.runtime.ExecutionSession;

        final class SamePackageRunbookSessionHelperConsumer {
          static Object access(ExecutionSession session) {
            return RunbookExeKt.executeRunbookInSession(session, null, null);
          }
        }
        """
          .trimIndent(),
      )

    assertWithMessage("same-package helper mutation must be rejected: ${result.diagnostics}")
      .that(result.compiled)
      .isFalse()
    val errorCodes =
      result.diagnostics.filter { it.kind == Diagnostic.Kind.ERROR }.map(JavaDiagnostic::code)
    assertWithMessage("exact member-resolution diagnostics: ${result.diagnostics}")
      .that(errorCodes)
      .containsExactly("compiler.err.cant.resolve.location.args")
    assertThat(errorCodes).doesNotContain("compiler.err.cant.apply.symbol")
  }

  @Test
  fun `same-package external Java cannot construct or operate function-local implementations`() {
    val attempts =
      listOf(
        SamePackageAttempt(
          "SamePackageNamedScopeConsumer",
          "DefaultResourceScope",
          "new DefaultResourceScope().close()",
        ),
        SamePackageAttempt(
          "SamePackageExecutionSessionConsumer",
          EXECUTION_SESSION_IMPLEMENTATION.substringAfterLast('/'),
          "(${EXECUTION_SESSION_IMPLEMENTATION.substringAfterLast('/')}) null",
        ),
        SamePackageAttempt(
          "SamePackageRuntimeConsumer",
          REVOMAN_RUNTIME_IMPLEMENTATION.substringAfterLast('/'),
          "(${REVOMAN_RUNTIME_IMPLEMENTATION.substringAfterLast('/')}) null",
        ),
        SamePackageAttempt(
          "SamePackageRunnerBodyConsumer",
          KICK_RUNNER_BODY.substringAfterLast('/'),
          "(${KICK_RUNNER_BODY.substringAfterLast('/')}) null",
        ),
        SamePackageAttempt(
          "SamePackageSessionFactoryConsumer",
          EXECUTION_SESSION_FACTORY_IMPLEMENTATION.substringAfterLast('/'),
          "(${EXECUTION_SESSION_FACTORY_IMPLEMENTATION.substringAfterLast('/')}) null",
        ),
        SamePackageAttempt(
          "SamePackageRunnerFactoryConsumer",
          KICK_RUNNER_FACTORY.substringAfterLast('/'),
          "(${KICK_RUNNER_FACTORY.substringAfterLast('/')}) null",
        ),
        SamePackageAttempt(
          "SamePackageDefaultSandboxFactoryConsumer",
          DEFAULT_SANDBOX_FACTORY_IMPLEMENTATION.substringAfterLast('/'),
          "(${DEFAULT_SANDBOX_FACTORY_IMPLEMENTATION.substringAfterLast('/')}) null",
        ),
        SamePackageAttempt(
          "SamePackageAnonymousScopeConsumer",
          RESOURCE_SCOPE_IMPLEMENTATION.substringAfterLast('/'),
          "new ${RESOURCE_SCOPE_IMPLEMENTATION.substringAfterLast('/')}().close()",
        ),
        SamePackageAttempt(
          "SamePackageKickExecutionConsumer",
          KICK_EXECUTION_IMPLEMENTATION.substringAfterLast('/'),
          "new ${KICK_EXECUTION_IMPLEMENTATION.substringAfterLast('/')}((ResourceScope) null, (SandboxFactory) null).getScripts()",
        ),
        SamePackageAttempt(
          "SamePackageKickExecutorConsumer",
          KICK_EXECUTOR_IMPLEMENTATION.substringAfterLast('/'),
          "new ${KICK_EXECUTOR_IMPLEMENTATION.substringAfterLast('/')}((" +
            "${KICK_EXECUTION_IMPLEMENTATION.substringAfterLast('/')} ) null, (ResourceScope) null, (SandboxFactory) null).execute(null, null, null, 0L)",
        ),
      )

    assertThat(attempts.map(SamePackageAttempt::implementationName).toSet())
      .containsExactlyElementsIn(
        setOf("DefaultResourceScope", RESOURCE_SCOPE_IMPLEMENTATION.substringAfterLast('/')) +
          TASK5_ANONYMOUS_OWNERS.map { it.substringAfterLast('/') }
      )

    attempts.forEach { attempt ->
      val result =
        compileJava(
          attempt.className,
          """
          package com.salesforce.revoman.internal.runtime;

          final class ${attempt.className} {
            static Object access() {
              return ${attempt.operation};
            }
          }
          """
            .trimIndent(),
        )

      assertWithMessage("same-package mutation must be rejected by javac: ${result.diagnostics}")
        .that(result.compiled)
        .isFalse()
      assertWithMessage("targeted javac diagnostics: ${result.diagnostics}")
        .that(
          result.diagnostics
            .filter { it.kind == Diagnostic.Kind.ERROR }
            .map(JavaDiagnostic::code)
            .any { it in CANNOT_RESOLVE_MEMBER_CODES + CANNOT_ACCESS_CODES }
        )
        .isTrue()
      assertWithMessage(
          "wrong constructor arity is not an ownership barrier: ${result.diagnostics}"
        )
        .that(result.diagnostics.map(JavaDiagnostic::code))
        .doesNotContain("compiler.err.cant.apply.symbol")
    }
  }

  @Test
  fun `root jar contract rejects missing directory and different paths`() {
    val expected = configuredRootJar()
    val directory = Files.createDirectory(temporaryDirectory.resolve("classes"))
    val otherJar = temporaryDirectory.resolve("other.jar")
    JarOutputStream(Files.newOutputStream(otherJar)).use { /* Produce a valid, different JAR. */ }

    assertThat(assertThrows<IllegalStateException> { requireExactRootJar(null, expected) })
      .hasMessageThat()
      .contains("Missing required system property")
    assertThat(
        assertThrows<IllegalStateException> {
          requireExactRootJar(directory.toString(), expected)
        }
      )
      .hasMessageThat()
      .contains("regular JAR")
    assertThat(
        assertThrows<IllegalStateException> { requireExactRootJar(otherJar.toString(), expected) }
      )
      .hasMessageThat()
      .contains("exact root jar task output")
  }

  @Test
  fun `external Java can name focused types but cannot operate or construct them`() {
    val nameable =
      compileJava(
        "FocusedTypeReferenceConsumer",
        """
        import com.salesforce.revoman.internal.postman.PostmanSDK;
        import com.salesforce.revoman.internal.postman.PostmanVariableScopes;
        import com.salesforce.revoman.internal.postman.RegexReplacer;
        import com.salesforce.revoman.internal.postman.StepScriptCapture;
        import com.salesforce.revoman.internal.runtime.LegacyRundownProgress;

        final class FocusedTypeReferenceConsumer {
          PostmanSDK sdk;
          PostmanVariableScopes scopes;
          RegexReplacer replacer;
          StepScriptCapture capture;
          LegacyRundownProgress progress;
        }
        """
          .trimIndent(),
      )
    assertWithMessage("javac diagnostics: ${nameable.diagnostics}").that(nameable.compiled).isTrue()

    val attempts =
      listOf(
        "static Object access(PostmanSDK v) { return v.getScopes(); }",
        "static Object access(PostmanVariableScopes v) { return v.resolve(\"key\"); }",
        "static Object access(RegexReplacer v) { return v.replaceVariablesRecursively(\"x\"); }",
        "static Object access(StepScriptCapture v) { return v.assertionsFor(null); }",
        "static Object access(LegacyRundownProgress v) { return v.getCurrentRequestName(); }",
        "static void write(PostmanVariableScopes v) { v.setEnvironmentName(\"name\"); }",
        "static Object access() { return PostmanSDKKt.postmanSDK(null, null, null, null); }",
        "static Object access() { return PostmanVariableScopesKt.postmanVariableScopes(null, null, null, null); }",
        "static Object access() { return RegexReplacerKt.regexReplacer(null, null, null); }",
        "static Object access() { return StepScriptCaptureKt.stepScriptCapture(); }",
        "static Object access() { return LegacyRundownProgressKt.legacyRundownProgress(); }",
        "static Object access() { return new PostmanSDK(null, null, null); }",
        "static Object access() { return new RegexReplacer(null, null); }",
      )
    attempts.forEachIndexed { index, access ->
      val result =
        compileJava(
          "FocusedOperationConsumer$index",
          """
          import com.salesforce.revoman.internal.postman.*;
          import com.salesforce.revoman.internal.runtime.*;

          final class FocusedOperationConsumer$index {
            $access
          }
          """
            .trimIndent(),
        )
      assertWithMessage("focused operation must be rejected: $access; ${result.diagnostics}")
        .that(result.compiled)
        .isFalse()
    }
  }

  @Test
  fun `same-package Java cannot name focused anonymous implementations`() {
    val attempts =
      listOf(
        Pair(
          POSTMAN_SDK_IMPLEMENTATION.substringAfterLast('/'),
          "com.salesforce.revoman.internal.postman",
        ),
        Pair(
          POSTMAN_VARIABLE_SCOPES_IMPLEMENTATION.substringAfterLast('/'),
          "com.salesforce.revoman.internal.postman",
        ),
        Pair(
          STEP_SCRIPT_CAPTURE_IMPLEMENTATION.substringAfterLast('/'),
          "com.salesforce.revoman.internal.postman",
        ),
        Pair(
          REGEX_REPLACER_IMPLEMENTATION.substringAfterLast('/'),
          "com.salesforce.revoman.internal.postman",
        ),
        Pair(
          LEGACY_PROGRESS_IMPLEMENTATION.substringAfterLast('/'),
          "com.salesforce.revoman.internal.runtime",
        ),
      )
    attempts.forEachIndexed { index, (implementation, packageName) ->
      val result =
        compileJava(
          "FocusedImplementationConsumer$index",
          """
          package $packageName;

          final class FocusedImplementationConsumer$index {
            $implementation value;
          }
          """
            .trimIndent(),
        )
      assertWithMessage(
          "same-package anonymous implementation must be rejected: $implementation; " +
            result.diagnostics
        )
        .that(result.compiled)
        .isFalse()
      val errorCodes =
        result.diagnostics.filter { it.kind == Diagnostic.Kind.ERROR }.map(JavaDiagnostic::code)
      assertWithMessage("implementation must be unnameable or inaccessible: ${result.diagnostics}")
        .that(errorCodes.any { it in CANNOT_RESOLVE_MEMBER_CODES + CANNOT_ACCESS_CODES })
        .isTrue()
      assertWithMessage(
          "wrong constructor arity is not an ownership barrier: ${result.diagnostics}"
        )
        .that(errorCodes)
        .doesNotContain("compiler.err.cant.apply.symbol")
    }
  }

  private fun compileJava(className: String, sourceText: String): JavaCompilationResult {
    val rootJar = configuredRootJar()
    val source = temporaryDirectory.resolve("$className.java")
    Files.writeString(source, sourceText)
    val output = Files.createDirectory(temporaryDirectory.resolve("$className-output"))
    val diagnostics = DiagnosticCollector<JavaFileObject>()
    val compiler =
      requireNotNull(ToolProvider.getSystemJavaCompiler()) { "A JDK compiler is required" }
    val externalClasspath = System.getProperty("revoman.compat.externalClasspath").orEmpty()
    val classpath =
      listOf(rootJar.toString(), externalClasspath)
        .filter(String::isNotBlank)
        .joinToString(java.io.File.pathSeparator)

    val compiled =
      compiler.getStandardFileManager(diagnostics, null, null).use { fileManager ->
        compiler
          .getTask(
            null,
            fileManager,
            diagnostics,
            listOf("-classpath", classpath, "-d", output.toString()),
            null,
            fileManager.getJavaFileObjects(source.toFile()),
          )
          .call()
      }
    return JavaCompilationResult(
      compiled = compiled,
      diagnostics =
        diagnostics.diagnostics.map { diagnostic ->
          JavaDiagnostic(
            kind = diagnostic.kind,
            code = diagnostic.code,
            message = diagnostic.getMessage(Locale.ROOT),
          )
        },
    )
  }

  private data class JavaCompilationResult(
    val compiled: Boolean,
    val diagnostics: List<JavaDiagnostic>,
  )

  private data class JavaDiagnostic(
    val kind: Diagnostic.Kind,
    val code: String,
    val message: String,
  )

  private data class JavaBoundaryAttempt(
    val className: String,
    val access: String,
    val expectedDiagnosticCodes: Set<String>,
  )

  private data class SamePackageAttempt(
    val className: String,
    val implementationName: String,
    val operation: String,
  )

  private companion object {
    val FROZEN_JVM_ABI: Path = Path.of("api/cs2-baseline-revoman-root.jvm.tsv")
    val CANNOT_RESOLVE_MEMBER_CODES =
      setOf("compiler.err.cant.resolve.location", "compiler.err.cant.resolve.location.args")
    val CANNOT_ACCESS_CODES = setOf("compiler.err.cant.access")
    const val POSTMAN_SDK = "com/salesforce/revoman/internal/postman/PostmanSDK"
    const val REGEX_REPLACER = "com/salesforce/revoman/internal/postman/RegexReplacer"
    const val REVOMAN = "com/salesforce/revoman/ReVoman"
    const val POSTMAN_PACKAGE = "com/salesforce/revoman/internal/postman/"
    const val RUNTIME_PACKAGE = "com/salesforce/revoman/internal/runtime/"
    const val MOCK_HTTP_SERVER_PACKAGE = "com/salesforce/revoman/testing/http/"
    const val MOCK_HTTP_SERVER_INTERNAL_PACKAGE = "${MOCK_HTTP_SERVER_PACKAGE}internal/"
    const val MOCK_HTTP_HANDLER = "${MOCK_HTTP_SERVER_PACKAGE}MockHttpHandler"
    const val MOCK_HTTP_SERVER = "${MOCK_HTTP_SERVER_PACKAGE}MockHttpServer"
    const val MOCK_HTTP_SERVER_COMPANION = "$MOCK_HTTP_SERVER\$Companion"
    const val RECORDED_HTTP_REQUEST = "${MOCK_HTTP_SERVER_PACKAGE}RecordedHttpRequest"
    const val RECORDED_HTTP_REQUEST_COMPANION = "$RECORDED_HTTP_REQUEST\$Companion"
    const val RECORDED_NAME_VALUE = "${MOCK_HTTP_SERVER_PACKAGE}RecordedNameValue"
    const val LIFECYCLE_DIAGNOSTICS_OWNER = "${RUNTIME_PACKAGE}ExecutionLifecycleDiagnostics"
    const val RESOURCE_SCOPE_IMPLEMENTATION = "${RUNTIME_PACKAGE}ResourceScopeKt\$resourceScope\$1"
    const val KICK_EXECUTION_IMPLEMENTATION = "${RUNTIME_PACKAGE}KickExecutionKt\$kickExecution\$1"
    const val KICK_EXECUTOR_IMPLEMENTATION = "${KICK_EXECUTION_IMPLEMENTATION}\$executor\$1"
    const val EXECUTION_SESSION_IMPLEMENTATION =
      "${RUNTIME_PACKAGE}ExecutionSessionKt\$executionSession\$1"
    const val EXECUTION_SESSION_FACTORY_IMPLEMENTATION =
      "${RUNTIME_PACKAGE}ExecutionSessionKt\$executionSessionFactory\$1"
    const val KICK_RUNNER_FACTORY = "${RUNTIME_PACKAGE}KickRunnerKt\$kickExecutionFactory\$1"
    const val KICK_RUNNER_BODY = "${RUNTIME_PACKAGE}KickRunnerKt\$kickExecutionFactory\$body\$1"
    const val REVOMAN_RUNTIME_IMPLEMENTATION =
      "${RUNTIME_PACKAGE}ReVomanRuntimeKt\$reVomanRuntime\$1"
    const val DEFAULT_SANDBOX_FACTORY_IMPLEMENTATION =
      "${RUNTIME_PACKAGE}ReVomanRuntimeKt\$reVomanRuntime\$sandboxFactory\$1"
    const val POSTMAN_VARIABLE_SCOPES = "${POSTMAN_PACKAGE}PostmanVariableScopes"
    const val STEP_SCRIPT_CAPTURE = "${POSTMAN_PACKAGE}StepScriptCapture"
    const val LEGACY_RUNDOWN_PROGRESS = "${RUNTIME_PACKAGE}LegacyRundownProgress"
    const val POSTMAN_SDK_IMPLEMENTATION = "${POSTMAN_PACKAGE}PostmanSDKKt\$postmanSDK\$1"
    const val POSTMAN_VARIABLE_SCOPES_IMPLEMENTATION =
      "${POSTMAN_PACKAGE}PostmanVariableScopesKt\$postmanVariableScopes\$1"
    const val STEP_SCRIPT_CAPTURE_IMPLEMENTATION =
      "${POSTMAN_PACKAGE}StepScriptCaptureKt\$stepScriptCapture\$1"
    const val REGEX_REPLACER_IMPLEMENTATION = "${POSTMAN_PACKAGE}RegexReplacerKt\$regexReplacer\$1"
    const val LEGACY_PROGRESS_IMPLEMENTATION =
      "${RUNTIME_PACKAGE}LegacyRundownProgressKt\$legacyRundownProgress\$1"
    val TASK4_POSTMAN_IMPLEMENTATION_OWNERS =
      setOf(
        POSTMAN_SDK_IMPLEMENTATION,
        POSTMAN_VARIABLE_SCOPES_IMPLEMENTATION,
        STEP_SCRIPT_CAPTURE_IMPLEMENTATION,
        REGEX_REPLACER_IMPLEMENTATION,
      )
    val TASK4_FOCUSED_IMPLEMENTATION_OWNERS =
      TASK4_POSTMAN_IMPLEMENTATION_OWNERS + LEGACY_PROGRESS_IMPLEMENTATION
    val TASK4_FOCUSED_INTERFACE_OWNERS =
      setOf(
        POSTMAN_SDK,
        POSTMAN_VARIABLE_SCOPES,
        STEP_SCRIPT_CAPTURE,
        REGEX_REPLACER,
        LEGACY_RUNDOWN_PROGRESS,
      )
    val TASK4_FOCUSED_FACTORY_OWNERS =
      mapOf(
        "${POSTMAN_PACKAGE}PostmanSDKKt" to "postmanSDK",
        "${POSTMAN_PACKAGE}PostmanVariableScopesKt" to "postmanVariableScopes",
        "${POSTMAN_PACKAGE}StepScriptCaptureKt" to "stepScriptCapture",
        "${POSTMAN_PACKAGE}RegexReplacerKt" to "regexReplacer",
        "${RUNTIME_PACKAGE}LegacyRundownProgressKt" to "legacyRundownProgress",
      )
    val KOTLIN_ONLY_INTERFACE_OWNERS =
      setOf(
        "${RUNTIME_PACKAGE}InternalCloseable",
        "${RUNTIME_PACKAGE}ResourceScope",
        "${RUNTIME_PACKAGE}ScriptExecutor",
        "${RUNTIME_PACKAGE}SandboxRuntime",
        "${RUNTIME_PACKAGE}SandboxFactory",
        "${RUNTIME_PACKAGE}KickExecution",
      )
    val KOTLIN_ONLY_FACADE_OWNERS =
      setOf(
        "${RUNTIME_PACKAGE}InternalCloseableKt",
        "${RUNTIME_PACKAGE}ResourceScopeKt",
        "${RUNTIME_PACKAGE}KickExecutionKt",
      )
    val TASK3_RUNTIME_OWNERS =
      KOTLIN_ONLY_INTERFACE_OWNERS +
        KOTLIN_ONLY_FACADE_OWNERS +
        setOf(
          RESOURCE_SCOPE_IMPLEMENTATION,
          KICK_EXECUTION_IMPLEMENTATION,
          KICK_EXECUTOR_IMPLEMENTATION,
          "${RUNTIME_PACKAGE}SandboxRuntimeKt",
          "${RUNTIME_PACKAGE}ScriptExecutor\$DefaultImpls",
        )
    val TASK5_INTERFACE_OWNERS =
      setOf(
        "${RUNTIME_PACKAGE}KickExecution",
        "${RUNTIME_PACKAGE}KickExecutionFactory",
        "${RUNTIME_PACKAGE}KickBody",
        "${RUNTIME_PACKAGE}ExecutionSession",
        "${RUNTIME_PACKAGE}ExecutionSessionFactory",
        "${RUNTIME_PACKAGE}ReVomanRuntime",
      )
    val TASK5_FACTORY_METHODS =
      mapOf(
        "${RUNTIME_PACKAGE}KickExecutionKt" to listOf("kickExecution"),
        "${RUNTIME_PACKAGE}KickRunnerKt" to listOf("kickExecutionFactory"),
        "${RUNTIME_PACKAGE}ExecutionSessionKt" to
          listOf("executionSession", "executionSessionFactory"),
        "${RUNTIME_PACKAGE}ReVomanRuntimeKt" to
          listOf("reVomanRuntime", "reVomanRuntime", "reVomanRuntime"),
      )
    val TASK5_ANONYMOUS_OWNERS =
      setOf(
        KICK_EXECUTION_IMPLEMENTATION,
        KICK_EXECUTOR_IMPLEMENTATION,
        EXECUTION_SESSION_IMPLEMENTATION,
        EXECUTION_SESSION_FACTORY_IMPLEMENTATION,
        KICK_RUNNER_FACTORY,
        KICK_RUNNER_BODY,
        REVOMAN_RUNTIME_IMPLEMENTATION,
        DEFAULT_SANDBOX_FACTORY_IMPLEMENTATION,
      )
    val TASK5_DECLARED_AND_ANONYMOUS_OWNERS =
      TASK5_INTERFACE_OWNERS + TASK5_FACTORY_METHODS.keys + TASK5_ANONYMOUS_OWNERS
    val TASK6_ROUTE_OWNERS =
      setOf(
        REVOMAN,
        "com/salesforce/revoman/internal/exe/RunbookExeKt",
        "${RUNTIME_PACKAGE}ReVomanRuntime",
        "${RUNTIME_PACKAGE}ReVomanRuntimeKt",
        REVOMAN_RUNTIME_IMPLEMENTATION,
      )

    fun minimalClassFile(
      majorVersion: Int,
      innerClassesTrailingBytes: ByteArray = byteArrayOf(),
    ): ByteArray =
      ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
          output.writeInt(0xCAFEBABE.toInt())
          output.writeShort(0)
          output.writeShort(majorVersion)
          output.writeShort(7)
          output.writeUtf8Constant("example/Fixture") // #1
          output.writeByte(7)
          output.writeShort(1) // #2 Class example/Fixture
          output.writeUtf8Constant("java/lang/Object") // #3
          output.writeByte(7)
          output.writeShort(3) // #4 Class java/lang/Object
          output.writeUtf8Constant("InnerClasses") // #5
          output.writeUtf8Constant("Fixture") // #6
          output.writeShort(0x0021) // ACC_PUBLIC | ACC_SUPER
          output.writeShort(2) // this_class
          output.writeShort(4) // super_class
          output.writeShort(0) // interfaces_count
          output.writeShort(0) // fields_count
          output.writeShort(0) // methods_count
          output.writeShort(1) // attributes_count
          output.writeShort(5) // InnerClasses
          output.writeInt(10 + innerClassesTrailingBytes.size)
          output.writeShort(1) // number_of_classes
          output.writeShort(2) // inner_class_info_index
          output.writeShort(0) // outer_class_info_index
          output.writeShort(6) // inner_name_index
          output.writeShort(0x0001) // ACC_PUBLIC
          output.write(innerClassesTrailingBytes)
        }
        bytes.toByteArray()
      }

    fun referenceClassFile(): ByteArray =
      ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
          output.writeInt(0xCAFEBABE.toInt())
          output.writeShort(0)
          output.writeShort(65)
          output.writeShort(32)
          output.writeUtf8Constant("example/Fixture") // #1
          output.writeByte(7)
          output.writeShort(1) // #2 Class example/Fixture
          output.writeUtf8Constant("java/lang/Object") // #3
          output.writeByte(7)
          output.writeShort(3) // #4 Class java/lang/Object
          output.writeReferenceConstant(9, 10, 13) // #5 Fieldref (forward)
          output.writeReferenceConstant(10, 10, 14) // #6 Methodref (forward)
          output.writeReferenceConstant(11, 11, 15) // #7 InterfaceMethodref (forward)
          output.writeByte(8)
          output.writeShort(20) // #8 String (forward)
          output.writeByte(16)
          output.writeShort(28) // #9 MethodType (forward)
          output.writeByte(7)
          output.writeShort(16) // #10 Class target/Owner (forward)
          output.writeByte(7)
          output.writeShort(17) // #11 Class target/Interface (forward)
          output.writeUtf8Constant("unused") // #12
          output.writeReferenceConstant(12, 18, 19) // #13 NameAndType field
          output.writeReferenceConstant(12, 21, 22) // #14 NameAndType method
          output.writeReferenceConstant(12, 23, 24) // #15 NameAndType interface method
          output.writeUtf8Constant("target/Owner") // #16
          output.writeUtf8Constant("target/Interface") // #17
          output.writeUtf8Constant("field") // #18
          output.writeUtf8Constant("Ltarget/DescriptorOnly;") // #19
          output.writeUtf8Constant("exact-option-string") // #20
          output.writeUtf8Constant("method") // #21
          output.writeUtf8Constant("()V") // #22
          output.writeUtf8Constant("invoke") // #23
          output.writeUtf8Constant("(Ltarget/DescriptorOnly;)V") // #24
          output.writeUtf8Constant("descriptorOnly") // #25
          output.writeUtf8Constant("(Ltarget/OnlyInNameAndType;)V") // #26
          output.writeReferenceConstant(12, 25, 26) // #27 unreferenced NameAndType
          output.writeUtf8Constant("(Ltarget/OnlyInMethodType;)V") // #28
          output.writeByte(16)
          output.writeShort(28) // #29 MethodType
          output.writeUtf8Constant("[Ltarget/ArrayOnly;") // #30
          output.writeByte(7)
          output.writeShort(30) // #31 Class array target/ArrayOnly
          output.writeShort(0x0021)
          output.writeShort(2)
          output.writeShort(4)
          output.writeShort(0)
          output.writeShort(0)
          output.writeShort(0)
          output.writeShort(0)
        }
        bytes.toByteArray()
      }

    fun DataOutputStream.writeUtf8Constant(value: String) {
      val encoded = value.toByteArray(Charsets.UTF_8)
      writeByte(1)
      writeShort(encoded.size)
      write(encoded)
    }

    fun DataOutputStream.writeReferenceConstant(tag: Int, first: Int, second: Int) {
      writeByte(tag)
      writeShort(first)
      writeShort(second)
    }
  }
}
