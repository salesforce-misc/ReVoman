/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.support

import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import performance.runner.RunnerExit

/** Runs the production atomic publisher behind its existing command seam for adapter fixtures. */
object RunnerOwnedPublicationFixture {
  @JvmStatic
  fun main(arguments: Array<String>) {
    require(arguments.size == 6)
    val source = Path.of(arguments[0]).toRealPath()
    val artifactParent = Path.of(arguments[1]).toRealPath()
    val runToken = arguments[2]
    val destination = arguments[3]
    val terminal = RunnerExit.entries.single { exit -> exit.code == arguments[4].toInt() }
    val boundary = arguments[5]

    val requestClass = Class.forName("performance.publication.AtomicPublicationRequest")
    val commandClass = Class.forName("performance.publication.PublicationCommand")
    val request =
      requestClass
        .getConstructor(
          Path::class.java,
          Path::class.java,
          String::class.java,
          RunnerExit::class.java,
          String::class.java,
        )
        .newInstance(source, artifactParent, runToken, terminal, destination)
    val command =
      Proxy.newProxyInstance(commandClass.classLoader, arrayOf(commandClass)) { _, method, values ->
        require(method.name == "execute")
        @Suppress("UNCHECKED_CAST")
        val commandArguments = values.single() as List<String>
        require(commandArguments.take(4) == listOf("/usr/bin/mv", "-nT", "--no-copy", "--"))
        val staging = Path.of(commandArguments[4])
        val target = Path.of(commandArguments[5])
        when (boundary) {
          "late-file" -> target.writeText("keep")
          "late-directory" -> target.createDirectory().resolve("foreign.txt").writeText("keep")
          "late-symlink" ->
            Files.createSymbolicLink(target, artifactParent.resolve("$runToken-escape"))
          "pre-move-failure",
          "move-failure" -> Unit
          "" -> {
            require(!Files.exists(target))
            Files.move(staging, target, ATOMIC_MOVE)
          }
          else -> error("unknown publication boundary")
        }
        if (boundary.isEmpty()) 0 else 1
      }
    val publisherClass = Class.forName("performance.publication.AtomicPublisher")
    val publisher = publisherClass.getField("INSTANCE").get(null)
    val publish =
      publisherClass.methods.single { method ->
        method.name.startsWith("publish\$") &&
          method.parameterTypes.contentEquals(arrayOf(requestClass, commandClass))
      }
    val outcome = publish.invoke(publisher, request, command)
    val expectedType =
      if (boundary.isEmpty()) {
        "performance.publication.PublicationOutcome\$Published"
      } else {
        "performance.publication.PublicationOutcome\$Rejected"
      }
    require(outcome.javaClass.name == expectedType)
    if (boundary.isEmpty()) {
      require(outcome.javaClass.getMethod("getTarget").invoke(outcome) == artifactParent.resolve(destination))
      require(outcome.javaClass.getMethod("getExit").invoke(outcome) == terminal)
    } else {
      require(
        outcome.javaClass.getMethod("getExit").invoke(outcome) ==
          RunnerExit.INTERNAL_OR_PUBLICATION_FAILED,
      )
    }
  }
}
