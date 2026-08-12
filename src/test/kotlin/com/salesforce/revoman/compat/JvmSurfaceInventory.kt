/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.compat

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.jar.JarFile

internal enum class JvmSurfaceKind {
  CLASS,
  FIELD,
  CONSTRUCTOR,
  METHOD,
}

internal data class JvmSurfaceEntry(
  val owner: String,
  val kind: JvmSurfaceKind,
  val name: String,
  val descriptor: String,
  val ownerAccess: Int,
  val memberAccess: Int,
  val ownerSynthetic: Boolean,
  val memberSynthetic: Boolean,
  val bridge: Boolean,
  val sourceCallable: Boolean,
) {
  fun render(): String =
    listOf(
        owner,
        kind.name,
        name,
        descriptor,
        accessHex(ownerAccess),
        accessHex(memberAccess),
        ownerSynthetic.toString(),
        memberSynthetic.toString(),
        bridge.toString(),
        sourceCallable.toString(),
      )
      .joinToString("\t")

  companion object {
    fun parse(line: String): JvmSurfaceEntry {
      val columns = line.split('\t')
      require(columns.size == JVM_SURFACE_HEADER.size) {
        "Expected ${JVM_SURFACE_HEADER.size} JVM surface columns, got ${columns.size}: $line"
      }
      return JvmSurfaceEntry(
        owner = columns[0],
        kind = JvmSurfaceKind.valueOf(columns[1]),
        name = columns[2],
        descriptor = columns[3],
        ownerAccess = columns[4].removePrefix("0x").toInt(16),
        memberAccess = columns[5].removePrefix("0x").toInt(16),
        ownerSynthetic = columns[6].toBooleanStrict(),
        memberSynthetic = columns[7].toBooleanStrict(),
        bridge = columns[8].toBooleanStrict(),
        sourceCallable = columns[9].toBooleanStrict(),
      )
    }
  }
}

internal val JVM_SURFACE_HEADER =
  listOf(
    "owner",
    "kind",
    "name",
    "descriptor",
    "ownerAccess",
    "memberAccess",
    "ownerSynthetic",
    "memberSynthetic",
    "bridge",
    "sourceCallable",
  )

internal object JvmSurfaceInventory {
  fun readJar(jar: Path): List<JvmSurfaceEntry> {
    require(Files.isRegularFile(jar)) { "JVM inventory input must be a regular JAR: $jar" }
    val parsedClasses =
      JarFile(jar.toFile()).use { archive ->
        archive
          .entries()
          .asSequence()
          .filter { !it.isDirectory && it.name.endsWith(".class") }
          .map { entry ->
            archive.getInputStream(entry).use { stream ->
              ClassFileReader(stream.readAllBytes()).read()
            }
          }
          .toList()
      }
    val duplicateOwners =
      parsedClasses.groupingBy(ParsedClass::owner).eachCount().filterValues {
        it > 1
      }
    require(duplicateOwners.isEmpty()) { "Duplicate class owners in $jar: $duplicateOwners" }

    val classesByOwner = parsedClasses.associateBy(ParsedClass::owner)
    val sourceCallableByOwner = mutableMapOf<String, Boolean>()
    fun ownerIsSourceCallable(
      owner: String,
      visiting: MutableSet<String> = mutableSetOf(),
    ): Boolean {
      sourceCallableByOwner[owner]?.let {
        return it
      }
      val parsed = classesByOwner.getValue(owner)
      check(visiting.add(owner)) { "Cyclic InnerClasses ownership for $owner" }
      val selfVisible =
        parsed.hasSourceName &&
          !hasFlag(parsed.effectiveAccess, ACC_SYNTHETIC) &&
          (hasFlag(parsed.effectiveAccess, ACC_PUBLIC) ||
            (parsed.outerOwner != null && hasFlag(parsed.effectiveAccess, ACC_PROTECTED)))
      val result =
        selfVisible &&
          (parsed.outerOwner?.let { outer ->
            classesByOwner[outer]?.let { ownerIsSourceCallable(outer, visiting) } ?: false
          } ?: true)
      visiting.remove(owner)
      sourceCallableByOwner[owner] = result
      return result
    }

    val rows =
      parsedClasses
        .asSequence()
        .flatMap { parsed ->
          val ownerSourceCallable = ownerIsSourceCallable(parsed.owner)
          val ownerSynthetic = hasFlag(parsed.effectiveAccess, ACC_SYNTHETIC)
          buildList {
              add(
                JvmSurfaceEntry(
                  owner = parsed.owner,
                  kind = JvmSurfaceKind.CLASS,
                  name = "<class>",
                  descriptor = "L${parsed.owner};",
                  ownerAccess = parsed.effectiveAccess,
                  memberAccess = 0,
                  ownerSynthetic = ownerSynthetic,
                  memberSynthetic = false,
                  bridge = false,
                  sourceCallable = ownerSourceCallable,
                )
              )
              parsed.members.forEach { member ->
                val memberSynthetic = hasFlag(member.access, ACC_SYNTHETIC)
                val sourceCallable =
                  ownerSourceCallable &&
                    !memberSynthetic &&
                    member.name != "<clinit>" &&
                    (hasFlag(member.access, ACC_PUBLIC) || hasFlag(member.access, ACC_PROTECTED))
                add(
                  JvmSurfaceEntry(
                    owner = parsed.owner,
                    kind = member.kind,
                    name = member.name,
                    descriptor = member.descriptor,
                    ownerAccess = parsed.effectiveAccess,
                    memberAccess = member.access,
                    ownerSynthetic = ownerSynthetic,
                    memberSynthetic = memberSynthetic,
                    bridge =
                      member.kind == JvmSurfaceKind.METHOD && hasFlag(member.access, ACC_BRIDGE),
                    sourceCallable = sourceCallable,
                  )
                )
              }
            }
            .asSequence()
        }
        .toList()
    val rendered = rows.asSequence().map(JvmSurfaceEntry::render).toList()
    val duplicates = rendered.groupingBy { it }.eachCount().filterValues { it > 1 }
    require(duplicates.isEmpty()) { "Duplicate JVM inventory rows in $jar: ${duplicates.keys}" }
    return rows.sortedWith(
      compareBy({ it.render().lowercase(Locale.ROOT) }, JvmSurfaceEntry::render)
    )
  }

  fun render(entries: List<JvmSurfaceEntry>): String {
    val rendered = entries.asSequence().map(JvmSurfaceEntry::render).toList()
    require(rendered.toSet().size == rendered.size) { "Duplicate JVM inventory rows" }
    val sorted = rendered.sortedWith(compareBy({ it.lowercase(Locale.ROOT) }, { it }))
    return (listOf(JVM_SURFACE_HEADER.joinToString("\t")) + sorted).joinToString(
      "\n",
      postfix = "\n",
    )
  }

  fun parse(text: String): List<JvmSurfaceEntry> {
    val lines = text.lineSequence().filter(String::isNotEmpty).toList()
    require(lines.firstOrNull() == JVM_SURFACE_HEADER.joinToString("\t")) {
      "Unexpected JVM surface header: ${lines.firstOrNull()}"
    }
    val entries = lines.asSequence().drop(1).map(JvmSurfaceEntry::parse).toList()
    require(entries.asSequence().map(JvmSurfaceEntry::render).toSet().size == entries.size) {
      "Duplicate JVM surface rows"
    }
    return entries
  }

  fun freeze(jar: Path, output: Path) {
    require(!Files.exists(output)) { "Refusing to overwrite immutable JVM baseline: $output" }
    output.parent?.let(Files::createDirectories)
    Files.writeString(
      output,
      render(readJar(jar)),
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE_NEW,
      StandardOpenOption.WRITE,
    )
  }
}

internal fun configuredRootJar(): Path =
  requireExactRootJar(
    configured = System.getProperty(ROOT_JAR_PROPERTY),
    expected =
      System.getProperty(EXPECTED_ROOT_JAR_PROPERTY)?.let(Path::of)
        ?: error("Missing required system property $EXPECTED_ROOT_JAR_PROPERTY"),
  )

internal fun requireExactRootJar(configured: String?, expected: Path): Path {
  check(!configured.isNullOrBlank()) { "Missing required system property $ROOT_JAR_PROPERTY" }
  val configuredPath = Path.of(configured)
  check(Files.isRegularFile(configuredPath)) {
    "$ROOT_JAR_PROPERTY must point to a regular JAR, got: $configuredPath"
  }
  val actual = configuredPath.toRealPath()
  val exactExpected = expected.toRealPath()
  check(actual == exactExpected) {
    "$ROOT_JAR_PROPERTY must equal the exact root jar task output: expected=$exactExpected, actual=$actual"
  }
  JarFile(actual.toFile()).use { /* Opening the archive rejects a non-JAR regular file. */ }
  return actual
}

fun main(args: Array<String>) {
  require(args.size == 2) { "Usage: JvmSurfaceInventoryKt <root.jar> <output.tsv>" }
  JvmSurfaceInventory.freeze(Path.of(args[0]), Path.of(args[1]))
}

private data class ParsedClass(
  val owner: String,
  val effectiveAccess: Int,
  val outerOwner: String?,
  val hasSourceName: Boolean,
  val members: List<ParsedMember>,
)

private data class ParsedMember(
  val kind: JvmSurfaceKind,
  val access: Int,
  val name: String,
  val descriptor: String,
)

private data class InnerClassAccess(
  val access: Int,
  val outerOwner: String?,
  val hasSourceName: Boolean,
)

internal fun validateJvmClassFile(bytes: ByteArray) {
  ClassFileReader(bytes).read()
}

private class ClassFileReader(bytes: ByteArray) {
  private val input = DataInputStream(ByteArrayInputStream(bytes))
  private lateinit var utf8: Array<String?>
  private lateinit var classNameIndices: IntArray

  fun read(): ParsedClass {
    require(input.readInt() == CLASS_FILE_MAGIC) { "Invalid JVM classfile magic" }
    input.readUnsignedShort() // minor version
    val majorVersion = input.readUnsignedShort()
    require(
      majorVersion in MIN_SUPPORTED_CLASSFILE_MAJOR_VERSION..JAVA_21_CLASSFILE_MAJOR_VERSION
    ) {
      "Expected supported classfile major range " +
        "$MIN_SUPPORTED_CLASSFILE_MAJOR_VERSION..$JAVA_21_CLASSFILE_MAJOR_VERSION, got $majorVersion"
    }
    readConstantPool()

    val classAccess = input.readUnsignedShort()
    val thisClassIndex = input.readUnsignedShort()
    val owner = className(thisClassIndex)
    input.readUnsignedShort() // super_class
    repeat(input.readUnsignedShort()) { input.readUnsignedShort() }

    val members = buildList {
      repeat(input.readUnsignedShort()) { add(readMember(JvmSurfaceKind.FIELD)) }
      repeat(input.readUnsignedShort()) {
        val member = readMember(JvmSurfaceKind.METHOD)
        add(if (member.name == "<init>") member.copy(kind = JvmSurfaceKind.CONSTRUCTOR) else member)
      }
    }
    var innerClassAccess: InnerClassAccess? = null
    repeat(input.readUnsignedShort()) {
      val attributeName = utf8(input.readUnsignedShort())
      val attributeLength = input.readInt()
      require(attributeLength >= 0) { "Unsupported class attribute length: $attributeLength" }
      val attributeBytes = ByteArray(attributeLength)
      input.readFully(attributeBytes)
      if (attributeName == "InnerClasses") {
        DataInputStream(ByteArrayInputStream(attributeBytes)).use { attribute ->
          repeat(attribute.readUnsignedShort()) {
            val innerClassIndex = attribute.readUnsignedShort()
            val outerClassIndex = attribute.readUnsignedShort()
            val innerNameIndex = attribute.readUnsignedShort()
            val access = attribute.readUnsignedShort()
            if (innerClassIndex == thisClassIndex) {
              innerClassAccess =
                InnerClassAccess(
                  access = access,
                  outerOwner = outerClassIndex.takeIf { it != 0 }?.let(::className),
                  hasSourceName = innerNameIndex != 0,
                )
            }
          }
          require(attribute.available() == 0) {
            "Unexpected trailing InnerClasses attribute bytes: ${attribute.available()}"
          }
        }
      }
    }
    require(input.available() == 0) {
      "Unexpected trailing classfile bytes: ${input.available()}"
    }

    return ParsedClass(
      owner = owner,
      effectiveAccess = innerClassAccess?.access ?: classAccess,
      outerOwner = innerClassAccess?.outerOwner,
      hasSourceName = innerClassAccess?.hasSourceName ?: true,
      members = members,
    )
  }

  private fun readConstantPool() {
    val count = input.readUnsignedShort()
    utf8 = arrayOfNulls(count)
    classNameIndices = IntArray(count)
    var index = 1
    while (index < count) {
      when (val tag = input.readUnsignedByte()) {
        CONSTANT_UTF8 -> utf8[index] = readModifiedUtf8(input.readUnsignedShort())
        CONSTANT_INTEGER,
        CONSTANT_FLOAT -> input.skipFully(4)
        CONSTANT_LONG,
        CONSTANT_DOUBLE -> {
          input.skipFully(8)
          index++
        }
        CONSTANT_CLASS -> classNameIndices[index] = input.readUnsignedShort()
        CONSTANT_STRING,
        CONSTANT_METHOD_TYPE,
        CONSTANT_MODULE,
        CONSTANT_PACKAGE -> input.skipFully(2)
        CONSTANT_FIELD_REF,
        CONSTANT_METHOD_REF,
        CONSTANT_INTERFACE_METHOD_REF,
        CONSTANT_NAME_AND_TYPE,
        CONSTANT_DYNAMIC,
        CONSTANT_INVOKE_DYNAMIC -> input.skipFully(4)
        CONSTANT_METHOD_HANDLE -> input.skipFully(3)
        else -> error("Unsupported JVM constant-pool tag $tag at index $index")
      }
      index++
    }
  }

  private fun readMember(defaultKind: JvmSurfaceKind): ParsedMember {
    val access = input.readUnsignedShort()
    val name = utf8(input.readUnsignedShort())
    val descriptor = utf8(input.readUnsignedShort())
    repeat(input.readUnsignedShort()) {
      input.readUnsignedShort()
      val length = input.readInt()
      require(length >= 0) { "Unsupported member attribute length: $length" }
      input.skipFully(length)
    }
    return ParsedMember(defaultKind, access, name, descriptor)
  }

  private fun className(index: Int): String = utf8(classNameIndices[index])

  private fun utf8(index: Int): String =
    requireNotNull(utf8.getOrNull(index)) { "Missing CONSTANT_Utf8 at index $index" }

  private fun readModifiedUtf8(length: Int): String {
    val encoded = ByteArray(length + 2)
    encoded[0] = (length ushr 8).toByte()
    encoded[1] = length.toByte()
    input.readFully(encoded, 2, length)
    return DataInputStream(ByteArrayInputStream(encoded)).use(DataInputStream::readUTF)
  }
}

private fun DataInputStream.skipFully(byteCount: Int) {
  var remaining = byteCount
  while (remaining > 0) {
    val skipped = skipBytes(remaining)
    check(skipped > 0) { "Unexpected end of classfile while skipping $byteCount bytes" }
    remaining -= skipped
  }
}

private fun accessHex(access: Int): String = String.format(Locale.ROOT, "0x%04X", access)

private fun hasFlag(access: Int, flag: Int): Boolean = access and flag != 0

private const val ROOT_JAR_PROPERTY = "revoman.compat.rootJar"
private const val EXPECTED_ROOT_JAR_PROPERTY = "revoman.compat.expectedRootJar"
private const val CLASS_FILE_MAGIC = 0xCAFEBABE.toInt()
private const val MIN_SUPPORTED_CLASSFILE_MAJOR_VERSION = 45
private const val JAVA_21_CLASSFILE_MAJOR_VERSION = 65
private const val ACC_PUBLIC = 0x0001
private const val ACC_PROTECTED = 0x0004
private const val ACC_BRIDGE = 0x0040
private const val ACC_SYNTHETIC = 0x1000
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
