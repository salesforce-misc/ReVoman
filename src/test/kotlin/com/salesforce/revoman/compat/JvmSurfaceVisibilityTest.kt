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
  fun `built jar records the complete intentionally reachable legacy sandbox surface`() {
    val entries = JvmSurfaceInventory.readJar(configuredRootJar())
    val classRows =
      entries.asSequence().filter { it.kind == JvmSurfaceKind.CLASS }.associateBy { it.owner }

    LEGACY_SANDBOX_OWNERS.forEach { owner ->
      assertWithMessage("missing legacy class row for $owner").that(classRows[owner]).isNotNull()
      assertWithMessage("legacy class must remain Java-source-callable in Task 1: $owner")
        .that(classRows.getValue(owner).sourceCallable)
        .isTrue()
    }

    assertThat(
        entries.any {
          it.owner == POSTMAN_SDK && it.kind == JvmSurfaceKind.CONSTRUCTOR && it.sourceCallable
        }
      )
      .isTrue()
    assertThat(
        entries.any {
          it.owner == POSTMAN_SDK &&
            it.kind == JvmSurfaceKind.METHOD &&
            it.name == "evaluateJS" &&
            it.sourceCallable
        }
      )
      .isTrue()
    assertThat(
        entries.any {
          it.owner == REGEX_REPLACER && it.kind == JvmSurfaceKind.CONSTRUCTOR && it.sourceCallable
        }
      )
      .isTrue()
  }

  @Test
  fun `Task 3 runtime additions and approved bridge removal have the exact raw surface`() {
    val entries = JvmSurfaceInventory.readJar(configuredRootJar())
    val frozen = JvmSurfaceInventory.parse(Files.readString(FROZEN_JVM_ABI))
    val frozenRows = frozen.asSequence().map(JvmSurfaceEntry::render).toSet()
    val additions = entries.filter { it.render() !in frozenRows }
    val activeRows = entries.asSequence().map(JvmSurfaceEntry::render).toSet()
    val removals = frozen.filter { it.render() !in activeRows }

    assertThat(additions.map(JvmSurfaceEntry::render))
      .containsExactlyElementsIn(CS2_TASK3_RAW_JVM_ADDITIONS)
    assertThat(removals.map(JvmSurfaceEntry::render))
      .containsExactlyElementsIn(CS2_TASK3_RAW_JVM_REMOVALS)
    assertThat(removals.single().memberSynthetic).isTrue()
    assertThat(removals.single().sourceCallable).isFalse()
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
    val addedClasses = additions.filter { it.kind == JvmSurfaceKind.CLASS }
    assertThat(addedClasses.map(JvmSurfaceEntry::owner))
      .containsExactlyElementsIn(TASK3_RUNTIME_OWNERS)
    assertThat(addedClasses.filter(JvmSurfaceEntry::sourceCallable)).hasSize(11)
    assertThat(addedClasses.filterNot(JvmSurfaceEntry::sourceCallable).map(JvmSurfaceEntry::owner))
      .containsExactlyElementsIn(
        setOf(
          RESOURCE_SCOPE_IMPLEMENTATION,
          KICK_EXECUTION_IMPLEMENTATION,
          KICK_EXECUTOR_IMPLEMENTATION,
        )
      )
    assertThat(additions.map(JvmSurfaceEntry::owner)).doesNotContain("${RUNTIME_PACKAGE}Companion")
    assertThat(additions.filter { it.kind == JvmSurfaceKind.FIELD }.map(JvmSurfaceEntry::name))
      .doesNotContain("INSTANCE")
    assertThat(additions.filter { it.owner in KOTLIN_ONLY_INTERFACE_OWNERS }).isNotEmpty()
    assertThat(
        additions
          .filter { it.owner in KOTLIN_ONLY_INTERFACE_OWNERS && it.kind == JvmSurfaceKind.METHOD }
          .all { it.memberSynthetic && !it.sourceCallable }
      )
      .isTrue()
    assertThat(
        additions
          .filter {
            it.owner in KOTLIN_ONLY_FACADE_OWNERS && it.kind == JvmSurfaceKind.METHOD
          }
          .all { !it.sourceCallable }
      )
      .isTrue()
    assertThat(
        additions
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
        val implementationRows = additions.filter { it.owner == implementation }
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
  fun `same-package external Java cannot construct or operate function-local implementations`() {
    val attempts =
      listOf(
        SamePackageAttempt(
          "SamePackageNamedScopeConsumer",
          "DefaultResourceScope",
          "new DefaultResourceScope().close()",
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
  fun `external Java source can compile against the intentional legacy sandbox surface`() {
    val result =
      compileJava(
        "LegacySandboxConsumer",
        """
        import com.salesforce.revoman.internal.postman.PostmanSDK;
        import com.salesforce.revoman.internal.postman.RegexReplacer;
        import java.util.Collections;
        import java.util.LinkedHashMap;

        final class LegacySandboxConsumer {
          static Object access() {
            RegexReplacer replacer = new RegexReplacer(Collections.emptyMap(), null);
            PostmanSDK sdk = new PostmanSDK(null, null, replacer, new LinkedHashMap<>());
            sdk.setEnvironmentVariable("token", "value");
            return sdk.evaluateJS("1 + 1", Collections.emptyMap());
          }
        }
        """
          .trimIndent(),
      )

    assertWithMessage("javac diagnostics: ${result.diagnostics}").that(result.compiled).isTrue()
  }

  @Test
  fun `external Java source cannot read a non-source-callable built-jar member`() {
    val result =
      compileJava(
        "LegacySandboxPrivateMemberConsumer",
        """
        import com.salesforce.revoman.internal.postman.PostmanSDK;

        final class LegacySandboxPrivateMemberConsumer {
          static Object access(PostmanSDK sdk) {
            return sdk.jsEvaluator;
          }
        }
        """
          .trimIndent(),
      )

    assertWithMessage("mutation must be rejected by javac: ${result.diagnostics}")
      .that(result.compiled)
      .isFalse()
    assertThat(result.diagnostics.map(JavaDiagnostic::kind)).contains(Diagnostic.Kind.ERROR)
    assertWithMessage("javac diagnostics: ${result.diagnostics}")
      .that(result.diagnostics.map(JavaDiagnostic::code))
      .contains("compiler.err.report.access")
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
    const val RUNTIME_PACKAGE = "com/salesforce/revoman/internal/runtime/"
    const val RESOURCE_SCOPE_IMPLEMENTATION = "${RUNTIME_PACKAGE}ResourceScopeKt\$resourceScope\$1"
    const val KICK_EXECUTION_IMPLEMENTATION = "${RUNTIME_PACKAGE}KickExecutionKt\$kickExecution\$1"
    const val KICK_EXECUTOR_IMPLEMENTATION = "${KICK_EXECUTION_IMPLEMENTATION}\$executor\$1"
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
    val LEGACY_SANDBOX_OWNERS =
      setOf(
        POSTMAN_SDK,
        "$POSTMAN_SDK\$JSEvaluator",
        "$POSTMAN_SDK\$Variables",
        "$POSTMAN_SDK\$Request",
        "$POSTMAN_SDK\$Response",
        "$POSTMAN_SDK\$Xml2Json",
        "com/salesforce/revoman/internal/postman/Info",
        REGEX_REPLACER,
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

    fun DataOutputStream.writeUtf8Constant(value: String) {
      val encoded = value.toByteArray(Charsets.UTF_8)
      writeByte(1)
      writeShort(encoded.size)
      write(encoded)
    }
  }
}
