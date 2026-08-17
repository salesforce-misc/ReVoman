/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.process

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class JdkProcessExecutorTest :
  FunSpec(
    {
      test("captures both process streams through pre-opened no-follow files") {
        withTempRoot("process-streams-") { root ->
          val stdout = root.resolve("stdout.log")
          val stderr = root.resolve("stderr.log")
          Files.writeString(stdout, "old stdout")
          Files.writeString(stderr, "old stderr")

          val result = JdkProcessExecutor().execute(spec(root, stdout, stderr))

          result.exitCode shouldBe 0
          Files.readString(stdout) shouldBe "safe stdout"
          Files.readString(stderr) shouldBe "safe stderr"
        }
      }

      test("does not follow log-path symlinks installed after channels are opened") {
        withTempRoot("process-symlink-") { root ->
          val stdout = root.resolve("stdout.log")
          val stderr = root.resolve("stderr.log")
          val stdoutTarget = root.resolve("stdout-target")
          val stderrTarget = root.resolve("stderr-target")
          Files.writeString(stdout, "old stdout")
          Files.writeString(stderr, "old stderr")
          Files.writeString(stdoutTarget, "unchanged stdout target")
          Files.writeString(stderrTarget, "unchanged stderr target")
          val executor =
            JdkProcessExecutor(
              hooks =
                ProcessExecutorHooks(
                  beforeProcessStart = {
                    Files.delete(stdout)
                    Files.delete(stderr)
                    Files.createSymbolicLink(stdout, stdoutTarget)
                    Files.createSymbolicLink(stderr, stderrTarget)
                  },
                ),
            )

          val result = executor.execute(spec(root, stdout, stderr))

          result.exitCode shouldBe 0
          Files.readString(stdoutTarget) shouldBe "unchanged stdout target"
          Files.readString(stderrTarget) shouldBe "unchanged stderr target"
          Files.isSymbolicLink(stdout) shouldBe true
          Files.isSymbolicLink(stderr) shouldBe true
        }
      }
    },
  )

private fun spec(root: Path, stdout: Path, stderr: Path): ProcessSpec =
  ProcessSpec(
    executable = Path.of("/bin/sh"),
    arguments = listOf("-c", "printf 'safe stdout'; printf 'safe stderr' >&2"),
    classpath = emptyList(),
    workingDirectory = root,
    environment = emptyMap(),
    stdoutPath = stdout,
    stderrPath = stderr,
    resultPath = root.resolve("result.json"),
  )

private inline fun withTempRoot(prefix: String, block: (Path) -> Unit) {
  val root = Files.createTempDirectory(prefix)
  try {
    block(root)
  } finally {
    root.toFile().deleteRecursively()
  }
}
