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
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.jar.JarFile
import org.junit.jupiter.api.Test

class RunbookExeStructureTest {
  @Test
  fun `runbook step body routes only to the session kick boundary`() {
    val runStepBody =
      readMethodInvocations(RUNBOOK_EXE).single {
        it.method.name == "runStepBody" && it.method.descriptor == RUN_STEP_BODY_DESCRIPTOR
      }

    assertWithMessage("method-scoped invokes for ${runStepBody.method}: ${runStepBody.invocations}")
      .that(runStepBody.routing())
      .containsExactly(
        invokeStatic(RUNBOOK_EXE, "checkConsumesOrHalt", CHECK_CONSUMES_DESCRIPTOR),
        invokeStatic(EXECUTION_SESSION, "executeKick\$default", EXECUTE_KICK_DEFAULT_DESCRIPTOR),
        invokeStatic(RUNBOOK_EXE, "producedValues", PRODUCED_VALUES_DESCRIPTOR),
        invokeStatic(RUNBOOK_EXE, "checkProducesOrHalt", CHECK_PRODUCES_DESCRIPTOR),
        invokeStatic(RUNBOOK_EXE, "producedValues", PRODUCED_VALUES_DESCRIPTOR),
      )
      .inOrder()
  }

  @Test
  fun `runbook compatibility adapter and synthetic session helper keep exact routing`() {
    val surface = JvmSurfaceInventory.readJar(configuredRootJar())
    val adapter = surface.single {
      it.owner == RUNBOOK_EXE &&
        it.kind == JvmSurfaceKind.METHOD &&
        it.name == "executeRunbook" &&
        it.descriptor == RUNBOOK_ADAPTER_DESCRIPTOR
    }
    val helper = surface.single {
      it.owner == RUNBOOK_EXE &&
        it.kind == JvmSurfaceKind.METHOD &&
        it.name == RUNBOOK_SESSION_HELPER_NAME &&
        it.descriptor == RUNBOOK_SESSION_HELPER_DESCRIPTOR
    }

    assertThat(adapter.render())
      .isEqualTo(
        "$RUNBOOK_EXE\tMETHOD\texecuteRunbook\t$RUNBOOK_ADAPTER_DESCRIPTOR\t" +
          "0x0031\t0x0019\tfalse\tfalse\tfalse\ttrue"
      )
    assertThat(helper.memberSynthetic).isTrue()
    assertThat(helper.sourceCallable).isFalse()

    val methods = readMethodInvocations(RUNBOOK_EXE).associateBy(MethodInvocations::method)
    assertThat(methods.getValue(MethodKey("executeRunbook", RUNBOOK_ADAPTER_DESCRIPTOR)).routing())
      .containsExactly(
        invokeStatic(REVOMAN_RUNTIME_KT, "reVomanRuntime", "()L$REVOMAN_RUNTIME;"),
        invokeInterface(REVOMAN_RUNTIME, "execute", RUNTIME_RUNBOOK_DESCRIPTOR),
      )
      .inOrder()
    assertThat(
        methods
          .getValue(MethodKey(RUNBOOK_SESSION_HELPER_NAME, RUNBOOK_SESSION_HELPER_DESCRIPTOR))
          .routing()
      )
      .containsExactly(invokeStatic(RUNBOOK_EXE, "executeStep", EXECUTE_STEP_DESCRIPTOR))

    val references = JvmSurfaceInventory.readJarReferences(configuredRootJar())
    references
      .filter { it.owner == RUNBOOK_EXE || it.owner.startsWith("$RUNBOOK_EXE\$") }
      .forEach { ownerReferences ->
        assertWithMessage("${ownerReferences.owner} must not reference $REVOMAN")
          .that(ownerReferences.classes)
          .doesNotContain(REVOMAN)
        assertWithMessage("${ownerReferences.owner} must not reference $REVOMAN members")
          .that(
            ownerReferences.members.filter {
              it.owner == REVOMAN || it.descriptor.contains("L$REVOMAN;")
            }
          )
          .isEmpty()
        assertThat(ownerReferences.descriptors.filter { it.contains("L$REVOMAN;") }).isEmpty()
      }
  }

  @Test
  fun `public overloads dispatch only through matching defaults and runtime descriptors`() {
    val methods = readMethodInvocations(REVOMAN).associateBy(MethodInvocations::method)
    val generatedToDefaults =
      mapOf(
        MethodKey("revUp", "([L$KICK;)Ljava/util/List;") to
          MethodInvocation(
            INVOKESTATIC,
            REVOMAN,
            "revUp\$default",
            "(L$POST_EXE_HOOK;Ljava/util/Map;[L$KICK;ILjava/lang/Object;)Ljava/util/List;",
          ),
        MethodKey("revUp", "(L$POST_EXE_HOOK;[L$KICK;)Ljava/util/List;") to
          MethodInvocation(
            INVOKESTATIC,
            REVOMAN,
            "revUp\$default",
            "(L$POST_EXE_HOOK;Ljava/util/Map;[L$KICK;ILjava/lang/Object;)Ljava/util/List;",
          ),
        MethodKey("revUp", "(Ljava/util/List;)Ljava/util/List;") to
          MethodInvocation(
            INVOKESTATIC,
            REVOMAN,
            "revUp\$default",
            "(Ljava/util/List;L$POST_EXE_HOOK;Ljava/util/Map;ILjava/lang/Object;)Ljava/util/List;",
          ),
        MethodKey("revUp", "(Ljava/util/List;L$POST_EXE_HOOK;)Ljava/util/List;") to
          MethodInvocation(
            INVOKESTATIC,
            REVOMAN,
            "revUp\$default",
            "(Ljava/util/List;L$POST_EXE_HOOK;Ljava/util/Map;ILjava/lang/Object;)Ljava/util/List;",
          ),
        MethodKey("revUp", "(L$RUNBOOK;)L$RUNBOOK_RUNDOWN;") to
          MethodInvocation(
            INVOKESTATIC,
            REVOMAN,
            "revUp\$default",
            "(L$RUNBOOK;Ljava/util/Map;ILjava/lang/Object;)L$RUNBOOK_RUNDOWN;",
          ),
      )
    generatedToDefaults.forEach { (method, expected) ->
      assertWithMessage("routing for $method")
        .that(methods.getValue(method).routing())
        .containsExactly(expected)
    }

    val defaultsToPrimary =
      mapOf(
        MethodKey(
          "revUp\$default",
          "(L$POST_EXE_HOOK;Ljava/util/Map;[L$KICK;ILjava/lang/Object;)Ljava/util/List;",
        ) to
          MethodInvocation(
            INVOKESTATIC,
            REVOMAN,
            "revUp",
            "(L$POST_EXE_HOOK;Ljava/util/Map;[L$KICK;)Ljava/util/List;",
          ),
        MethodKey(
          "revUp\$default",
          "(Ljava/util/List;L$POST_EXE_HOOK;Ljava/util/Map;ILjava/lang/Object;)Ljava/util/List;",
        ) to
          MethodInvocation(
            INVOKESTATIC,
            REVOMAN,
            "revUp",
            "(Ljava/util/List;L$POST_EXE_HOOK;Ljava/util/Map;)Ljava/util/List;",
          ),
        MethodKey(
          "revUp\$default",
          "(L$RUNBOOK;Ljava/util/Map;ILjava/lang/Object;)L$RUNBOOK_RUNDOWN;",
        ) to
          MethodInvocation(
            INVOKESTATIC,
            REVOMAN,
            "revUp",
            "(L$RUNBOOK;Ljava/util/Map;)L$RUNBOOK_RUNDOWN;",
          ),
      )
    defaultsToPrimary.forEach { (method, expected) ->
      assertWithMessage("routing for $method")
        .that(methods.getValue(method).routing())
        .containsExactly(expected)
    }

    val primariesToRuntime =
      mapOf(
        MethodKey(
          "revUp",
          "(L$POST_EXE_HOOK;Ljava/util/Map;[L$KICK;)Ljava/util/List;",
        ) to RUNTIME_LIST_DESCRIPTOR,
        MethodKey(
          "revUp",
          "(Ljava/util/List;L$POST_EXE_HOOK;Ljava/util/Map;)Ljava/util/List;",
        ) to RUNTIME_LIST_DESCRIPTOR,
        MethodKey("revUp", "(L$RUNBOOK;Ljava/util/Map;)L$RUNBOOK_RUNDOWN;") to
          RUNTIME_RUNBOOK_DESCRIPTOR,
        MethodKey("revUp", SINGLE_KICK_DESCRIPTOR) to RUNTIME_KICK_DESCRIPTOR,
      )
    primariesToRuntime.forEach { (method, descriptor) ->
      assertWithMessage("routing for $method")
        .that(methods.getValue(method).routing())
        .containsExactly(
          invokeStatic(REVOMAN_RUNTIME_KT, "reVomanRuntime", "()L$REVOMAN_RUNTIME;"),
          invokeInterface(REVOMAN_RUNTIME, "execute", descriptor),
        )
        .inOrder()
    }
  }

  @Test
  fun `runtime runbook implementation invokes only the session helper`() {
    val methods =
      readMethodInvocations(REVOMAN_RUNTIME_IMPLEMENTATION).associateBy(MethodInvocations::method)
    val runbookMethod = methods.getValue(MethodKey("execute", RUNTIME_RUNBOOK_DESCRIPTOR))

    assertThat(runbookMethod.routing())
      .containsExactly(
        invokeInterface(
          EXECUTION_SESSION_FACTORY,
          "open",
          "(Ljava/util/Map;)L$EXECUTION_SESSION;",
        ),
        invokeStatic(
          RUNBOOK_EXE,
          RUNBOOK_SESSION_HELPER_NAME,
          RUNBOOK_SESSION_HELPER_DESCRIPTOR,
        ),
      )
      .inOrder()
  }

  private fun readMethodInvocations(owner: String): List<MethodInvocations> {
    val entryName = "$owner.class"
    val bytes =
      JarFile(configuredRootJar().toFile()).use { archive ->
        val entry = requireNotNull(archive.getJarEntry(entryName)) { "Missing $entryName" }
        archive.getInputStream(entry).use { it.readAllBytes() }
      }
    return MethodCodeReader(bytes).read(owner)
  }

  private data class MethodKey(val name: String, val descriptor: String)

  private data class MethodInvocations(
    val method: MethodKey,
    val invocations: List<MethodInvocation>,
  )

  private fun MethodInvocations.routing(): List<MethodInvocation> = invocations.filter {
    it.owner == REVOMAN ||
      it.owner == REVOMAN_RUNTIME ||
      it.owner == REVOMAN_RUNTIME_KT ||
      it.owner == RUNBOOK_EXE ||
      it.owner == EXECUTION_SESSION ||
      it.owner == EXECUTION_SESSION_FACTORY
  }

  private data class MethodInvocation(
    val opcode: Int,
    val owner: String,
    val name: String,
    val descriptor: String,
  )

  private class MethodCodeReader(bytes: ByteArray) {
    private val source = ByteArrayInputStream(bytes)
    private val input = DataInputStream(source)
    private lateinit var tags: IntArray
    private lateinit var firstIndices: IntArray
    private lateinit var secondIndices: IntArray
    private lateinit var utf8: Array<String?>

    fun read(expectedOwner: String): List<MethodInvocations> {
      require(input.readInt() == CLASS_FILE_MAGIC) { "Invalid classfile magic" }
      input.readUnsignedShort()
      val major = input.readUnsignedShort()
      require(major in MIN_CLASSFILE_MAJOR..MAX_CLASSFILE_MAJOR) {
        "Unsupported classfile major version $major"
      }
      readConstantPool()
      input.readUnsignedShort()
      val owner = className(input.readUnsignedShort())
      require(owner == expectedOwner) { "Expected $expectedOwner, got $owner" }
      input.readUnsignedShort()
      repeat(input.readUnsignedShort()) { input.readUnsignedShort() }
      repeat(input.readUnsignedShort()) { skipMember() }
      val methods =
        List(input.readUnsignedShort()) {
          input.readUnsignedShort()
          val method = MethodKey(utf8(input.readUnsignedShort()), utf8(input.readUnsignedShort()))
          val invocations = mutableListOf<MethodInvocation>()
          repeat(input.readUnsignedShort()) {
            val attributeName = utf8(input.readUnsignedShort())
            val attribute = readAttributeBytes()
            if (attributeName == "Code") invocations += readCode(attribute, method)
          }
          MethodInvocations(method, invocations)
        }
      repeat(input.readUnsignedShort()) {
        input.readUnsignedShort()
        readAttributeBytes()
      }
      require(source.available() == 0) { "Unexpected trailing classfile bytes for $owner" }
      return methods
    }

    private fun readConstantPool() {
      val count = input.readUnsignedShort()
      tags = IntArray(count)
      firstIndices = IntArray(count)
      secondIndices = IntArray(count)
      utf8 = arrayOfNulls(count)
      var index = 1
      while (index < count) {
        val tag = input.readUnsignedByte()
        tags[index] = tag
        when (tag) {
          CONSTANT_UTF8 -> utf8[index] = input.readUTF()
          CONSTANT_INTEGER,
          CONSTANT_FLOAT -> input.skipExactly(4)
          CONSTANT_LONG,
          CONSTANT_DOUBLE -> {
            input.skipExactly(8)
            index++
          }
          CONSTANT_CLASS,
          CONSTANT_STRING,
          CONSTANT_METHOD_TYPE,
          CONSTANT_MODULE,
          CONSTANT_PACKAGE -> firstIndices[index] = input.readUnsignedShort()
          CONSTANT_FIELD_REF,
          CONSTANT_METHOD_REF,
          CONSTANT_INTERFACE_METHOD_REF,
          CONSTANT_NAME_AND_TYPE,
          CONSTANT_DYNAMIC,
          CONSTANT_INVOKE_DYNAMIC -> {
            firstIndices[index] = input.readUnsignedShort()
            secondIndices[index] = input.readUnsignedShort()
          }
          CONSTANT_METHOD_HANDLE -> input.skipExactly(3)
          else -> error("Unsupported constant-pool tag $tag at index $index")
        }
        index++
      }
    }

    private fun skipMember() {
      input.skipExactly(6)
      repeat(input.readUnsignedShort()) {
        input.readUnsignedShort()
        readAttributeBytes()
      }
    }

    private fun readAttributeBytes(): ByteArray {
      val length = input.readInt()
      require(length >= 0) { "Negative attribute length $length" }
      return ByteArray(length).also(input::readFully)
    }

    private fun readCode(attribute: ByteArray, method: MethodKey): List<MethodInvocation> {
      val codeInput = DataInputStream(ByteArrayInputStream(attribute))
      codeInput.readUnsignedShort()
      codeInput.readUnsignedShort()
      val codeLength = codeInput.readInt()
      require(codeLength >= 0) { "Negative Code length for $method" }
      val code = ByteArray(codeLength).also(codeInput::readFully)
      val invocations = walkCode(code, method)
      repeat(codeInput.readUnsignedShort()) { codeInput.skipExactly(8) }
      repeat(codeInput.readUnsignedShort()) {
        codeInput.readUnsignedShort()
        val length = codeInput.readInt()
        require(length >= 0) { "Negative nested Code attribute length for $method" }
        codeInput.skipExactly(length)
      }
      require(codeInput.available() == 0) { "Trailing Code attribute bytes for $method" }
      return invocations
    }

    private fun walkCode(code: ByteArray, method: MethodKey): List<MethodInvocation> {
      val invocations = mutableListOf<MethodInvocation>()
      var offset = 0
      while (offset < code.size) {
        val opcode = code.u1(offset)
        val length =
          when (opcode) {
            INVOKEVIRTUAL,
            INVOKESPECIAL,
            INVOKESTATIC -> {
              invocations += resolveInvocation(opcode, code.u2(offset + 1), method)
              3
            }
            INVOKEINTERFACE -> {
              invocations += resolveInvocation(opcode, code.u2(offset + 1), method)
              require(code.u1(offset + 3) > 0 && code.u1(offset + 4) == 0) {
                "Malformed invokeinterface at $offset in $method"
              }
              5
            }
            TABLESWITCH -> tableSwitchLength(code, offset, method)
            LOOKUPSWITCH -> lookupSwitchLength(code, offset, method)
            WIDE -> wideLength(code, offset, method)
            else -> fixedInstructionLength(opcode)
          }
        require(length > 0 && offset + length <= code.size) {
          "Truncated opcode 0x${opcode.toString(16)} at $offset in $method"
        }
        offset += length
      }
      require(offset == code.size) {
        "Instruction walk ended at $offset of ${code.size} in $method"
      }
      return invocations
    }

    private fun resolveInvocation(
      opcode: Int,
      constantPoolIndex: Int,
      method: MethodKey,
    ): MethodInvocation {
      val actualTag = tags.getOrElse(constantPoolIndex) { -1 }
      require(actualTag == CONSTANT_METHOD_REF || actualTag == CONSTANT_INTERFACE_METHOD_REF) {
        "Opcode 0x${opcode.toString(16)} at $method references CP tag $actualTag"
      }
      val nameAndTypeIndex = secondIndices[constantPoolIndex]
      require(tags[nameAndTypeIndex] == CONSTANT_NAME_AND_TYPE) {
        "Expected NameAndType at CP index $nameAndTypeIndex"
      }
      return MethodInvocation(
        opcode = opcode,
        owner = className(firstIndices[constantPoolIndex]),
        name = utf8(firstIndices[nameAndTypeIndex]),
        descriptor = utf8(secondIndices[nameAndTypeIndex]),
      )
    }

    private fun className(index: Int): String {
      require(tags.getOrElse(index) { -1 } == CONSTANT_CLASS) {
        "Expected Class at CP index $index"
      }
      return utf8(firstIndices[index])
    }

    private fun utf8(index: Int): String =
      requireNotNull(utf8.getOrNull(index)) { "Missing Utf8 at CP index $index" }
  }

  private companion object {
    const val RUNBOOK_EXE = "com/salesforce/revoman/internal/exe/RunbookExeKt"
    const val REVOMAN = "com/salesforce/revoman/ReVoman"
    const val REVOMAN_RUNTIME = "com/salesforce/revoman/internal/runtime/ReVomanRuntime"
    const val REVOMAN_RUNTIME_KT = "com/salesforce/revoman/internal/runtime/ReVomanRuntimeKt"
    const val REVOMAN_RUNTIME_IMPLEMENTATION =
      "com/salesforce/revoman/internal/runtime/ReVomanRuntimeKt\$reVomanRuntime\$1"
    const val EXECUTION_SESSION = "com/salesforce/revoman/internal/runtime/ExecutionSession"
    const val EXECUTION_SESSION_FACTORY =
      "com/salesforce/revoman/internal/runtime/ExecutionSessionFactory"
    const val POST_EXE_HOOK = "com/salesforce/revoman/input/PostExeHook"
    const val KICK = "com/salesforce/revoman/input/config/Kick"
    const val RUNBOOK = "com/salesforce/revoman/input/config/Runbook"
    const val RUNDOWN = "com/salesforce/revoman/output/Rundown"
    const val RUNBOOK_RUNDOWN = "com/salesforce/revoman/output/RunbookRundown"
    const val RUNBOOK_ADAPTER_DESCRIPTOR = "(L$RUNBOOK;Ljava/util/Map;)L$RUNBOOK_RUNDOWN;"
    const val RUNBOOK_SESSION_HELPER_NAME = "executeRunbookInSession"
    const val RUNBOOK_SESSION_HELPER_DESCRIPTOR =
      "(L$EXECUTION_SESSION;L$RUNBOOK;Ljava/util/Map;)L$RUNBOOK_RUNDOWN;"
    const val EXECUTE_STEP_DESCRIPTOR =
      "(L$EXECUTION_SESSION;L$RUNBOOK;Lcom/salesforce/revoman/output/log/RunLogSink;" +
        "Lcom/salesforce/revoman/input/config/RunbookStep;" +
        "Lcom/salesforce/revoman/internal/exe/RunbookAcc;)" +
        "Lcom/salesforce/revoman/internal/exe/RunbookAcc;"
    const val EXECUTE_KICK_DEFAULT_DESCRIPTOR =
      "(L$EXECUTION_SESSION;L$KICK;ZLkotlin/jvm/functions/Function2;ILjava/lang/Object;)L$RUNDOWN;"
    const val CHECK_CONSUMES_DESCRIPTOR =
      "(Lcom/salesforce/revoman/input/config/RunbookStep;Ljava/util/Set;)V"
    const val PRODUCED_VALUES_DESCRIPTOR =
      "(Lcom/salesforce/revoman/input/config/RunbookStep;L$RUNDOWN;)Ljava/util/Map;"
    const val CHECK_PRODUCES_DESCRIPTOR =
      "(Lcom/salesforce/revoman/input/config/RunbookStep;L$RUNDOWN;)V"
    const val RUNTIME_LIST_DESCRIPTOR =
      "(Ljava/util/List;L$POST_EXE_HOOK;Ljava/util/Map;)Ljava/util/List;"
    const val RUNTIME_RUNBOOK_DESCRIPTOR = "(L$RUNBOOK;Ljava/util/Map;)L$RUNBOOK_RUNDOWN;"
    const val RUNTIME_KICK_DESCRIPTOR = "(L$KICK;)L$RUNDOWN;"
    const val RUN_STEP_BODY_DESCRIPTOR =
      "(Lcom/salesforce/revoman/internal/runtime/ExecutionSession;" +
        "Lcom/salesforce/revoman/input/config/Runbook;" +
        "Lcom/salesforce/revoman/output/log/RunLogSink;" +
        "Lcom/salesforce/revoman/input/config/RunbookStep;" +
        "Lcom/salesforce/revoman/internal/exe/RunbookAcc;J" +
        "Lcom/salesforce/revoman/internal/exe/StepCloseGuard;)" +
        "Lcom/salesforce/revoman/internal/exe/RunbookAcc;"
    const val SINGLE_KICK_DESCRIPTOR = "(L$KICK;)L$RUNDOWN;"

    fun invokeStatic(owner: String, name: String, descriptor: String) =
      MethodInvocation(INVOKESTATIC, owner, name, descriptor)

    fun invokeInterface(owner: String, name: String, descriptor: String) =
      MethodInvocation(INVOKEINTERFACE, owner, name, descriptor)
  }
}

private fun DataInputStream.skipExactly(byteCount: Int) {
  require(byteCount >= 0) { "Negative skip length $byteCount" }
  var remaining = byteCount
  while (remaining > 0) {
    val skipped = skipBytes(remaining)
    check(skipped > 0) { "Unexpected end of classfile while skipping $byteCount bytes" }
    remaining -= skipped
  }
}

private fun ByteArray.u1(index: Int): Int = getOrNull(index)?.toInt()?.and(0xff) ?: -1

private fun ByteArray.u2(index: Int): Int {
  require(index >= 0 && index + 1 < size) { "Missing u2 at bytecode offset $index" }
  return (u1(index) shl 8) or u1(index + 1)
}

private fun ByteArray.s4(index: Int): Int {
  require(index >= 0 && index + 3 < size) { "Missing s4 at bytecode offset $index" }
  return (u1(index) shl 24) or (u1(index + 1) shl 16) or (u1(index + 2) shl 8) or u1(index + 3)
}

private fun tableSwitchLength(code: ByteArray, offset: Int, method: Any): Int {
  val padding = (4 - ((offset + 1) and 3)) and 3
  val body = offset + 1 + padding
  val low = code.s4(body + 4)
  val high = code.s4(body + 8)
  require(high >= low) { "Malformed tableswitch range $low..$high at $offset in $method" }
  val entries = high.toLong() - low.toLong() + 1L
  val length = 1L + padding + 12L + entries * 4L
  require(length <= Int.MAX_VALUE) { "Oversized tableswitch at $offset in $method" }
  return length.toInt()
}

private fun lookupSwitchLength(code: ByteArray, offset: Int, method: Any): Int {
  val padding = (4 - ((offset + 1) and 3)) and 3
  val body = offset + 1 + padding
  val pairs = code.s4(body + 4)
  require(pairs >= 0) { "Negative lookupswitch pair count at $offset in $method" }
  val length = 1L + padding + 8L + pairs.toLong() * 8L
  require(length <= Int.MAX_VALUE) { "Oversized lookupswitch at $offset in $method" }
  return length.toInt()
}

private fun wideLength(code: ByteArray, offset: Int, method: Any): Int =
  when (val widened = code.u1(offset + 1)) {
    IINC -> 6
    in ILOAD..ALOAD,
    in ISTORE..ASTORE,
    RET -> 4
    else -> error("Malformed wide opcode 0x${widened.toString(16)} at $offset in $method")
  }

private fun fixedInstructionLength(opcode: Int): Int =
  when (opcode) {
    in 0x00..0x0f -> 1
    BIPUSH -> 2
    SIPUSH -> 3
    LDC -> 2
    LDC_W,
    LDC2_W -> 3
    in ILOAD..ALOAD -> 2
    in 0x1a..0x35 -> 1
    in ISTORE..ASTORE -> 2
    in 0x3b..0x83 -> 1
    IINC -> 3
    in 0x85..0x98 -> 1
    in 0x99..0xa8 -> 3
    RET -> 2
    in 0xac..0xb1 -> 1
    in 0xb2..0xb8 -> 3
    INVOKEINTERFACE,
    INVOKEDYNAMIC -> 5
    NEW -> 3
    NEWARRAY -> 2
    ANEWARRAY -> 3
    ARRAYLENGTH,
    ATHROW -> 1
    CHECKCAST,
    INSTANCEOF -> 3
    MONITORENTER,
    MONITOREXIT -> 1
    MULTIANEWARRAY -> 4
    IFNULL,
    IFNONNULL -> 3
    GOTO_W,
    JSR_W -> 5
    BREAKPOINT,
    IMPDEP1,
    IMPDEP2 -> 1
    else -> error("Unsupported bytecode opcode 0x${opcode.toString(16)}")
  }

private const val CLASS_FILE_MAGIC = 0xCAFEBABE.toInt()
private const val MIN_CLASSFILE_MAJOR = 45
private const val MAX_CLASSFILE_MAJOR = 65
private const val CONSTANT_UTF8 = 1
private const val CONSTANT_INTEGER = 3
private const val CONSTANT_FLOAT = 4
private const val CONSTANT_LONG = 5
private const val CONSTANT_DOUBLE = 6
private const val CONSTANT_CLASS = 7
private const val CONSTANT_STRING = 8
private const val CONSTANT_FIELD_REF = 9
private const val CONSTANT_METHOD_REF = 10
private const val CONSTANT_INTERFACE_METHOD_REF = 11
private const val CONSTANT_NAME_AND_TYPE = 12
private const val CONSTANT_METHOD_HANDLE = 15
private const val CONSTANT_METHOD_TYPE = 16
private const val CONSTANT_DYNAMIC = 17
private const val CONSTANT_INVOKE_DYNAMIC = 18
private const val CONSTANT_MODULE = 19
private const val CONSTANT_PACKAGE = 20
private const val BIPUSH = 0x10
private const val SIPUSH = 0x11
private const val LDC = 0x12
private const val LDC_W = 0x13
private const val LDC2_W = 0x14
private const val ILOAD = 0x15
private const val ALOAD = 0x19
private const val ISTORE = 0x36
private const val ASTORE = 0x3a
private const val IINC = 0x84
private const val RET = 0xa9
private const val TABLESWITCH = 0xaa
private const val LOOKUPSWITCH = 0xab
private const val INVOKEVIRTUAL = 0xb6
private const val INVOKESPECIAL = 0xb7
private const val INVOKESTATIC = 0xb8
private const val INVOKEINTERFACE = 0xb9
private const val INVOKEDYNAMIC = 0xba
private const val NEW = 0xbb
private const val NEWARRAY = 0xbc
private const val ANEWARRAY = 0xbd
private const val ARRAYLENGTH = 0xbe
private const val ATHROW = 0xbf
private const val CHECKCAST = 0xc0
private const val INSTANCEOF = 0xc1
private const val MONITORENTER = 0xc2
private const val MONITOREXIT = 0xc3
private const val WIDE = 0xc4
private const val MULTIANEWARRAY = 0xc5
private const val IFNULL = 0xc6
private const val IFNONNULL = 0xc7
private const val GOTO_W = 0xc8
private const val JSR_W = 0xc9
private const val BREAKPOINT = 0xca
private const val IMPDEP1 = 0xfe
private const val IMPDEP2 = 0xff
