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

  private companion object {
    const val POSTMAN_SDK = "com/salesforce/revoman/internal/postman/PostmanSDK"
    const val REGEX_REPLACER = "com/salesforce/revoman/internal/postman/RegexReplacer"
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
