/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.integration.jarmode

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.StepPick.ExeStepPick
import com.salesforce.revoman.input.isV3Collection
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Reproduces the failure mode described in the slim-design revert (core commit 2555a4147495): v3
 * collection directories packaged inside a JAR that lives on a CHILD classloader (not the okio
 * class's classloader) are invisible to `okio.FileSystem.RESOURCES`. ReVoman then mis-routes the
 * path to the v2 Moshi parser and EOFs.
 *
 * Setup: a fixture is packaged into a temp jar with a unique top-level prefix (`jar-only/...`) that
 * does NOT exist on the main test classpath. The temp jar is mounted on a `URLClassLoader` and
 * installed as the thread context classloader. `okio.FileSystem.RESOURCES`, however, is statically
 * bound to the okio class's classloader, which is the app classloader and cannot see the temp jar
 * -- so any consumer that funnels jar-backed v3 paths through `RESOURCES` will fail to detect them
 * as v3.
 */
class JarModeRevUpKtTest {

  private lateinit var tempJar: File
  private var savedCcl: ClassLoader? = null

  @BeforeEach
  fun setUp() {
    tempJar = buildJarOnlyFixture()
    savedCcl = Thread.currentThread().contextClassLoader
    val jarOnlyCl = URLClassLoader(arrayOf(tempJar.toURI().toURL()), savedCcl)
    Thread.currentThread().contextClassLoader = jarOnlyCl
  }

  @AfterEach
  fun tearDown() {
    Thread.currentThread().contextClassLoader = savedCcl
    tempJar.delete()
  }

  /**
   * Sanity: `Thread.currentThread().contextClassLoader` can load the marker file via
   * `getResourceAsStream`. Confirms the jar fixture is mounted before exercising revoman.
   */
  @Test
  fun testFixtureVisibleViaContextClassLoader() {
    val ccl = Thread.currentThread().contextClassLoader
    val def = ccl.getResourceAsStream("jar-only/flat/.resources/definition.yaml")
    assertThat(def).isNotNull()
    def!!.close()
  }

  /**
   * After the resolver fix: `isV3Collection` resolves the v3 collection directory by probing the
   * marker `.resources/definition.yaml` through the thread context classloader, even when the jar
   * lives on a child URLClassLoader invisible to `okio.FileSystem.RESOURCES`.
   */
  @Test
  fun testIsV3CollectionTrueForChildClassLoaderJarDir() {
    assertThat(isV3Collection("jar-only/flat")).isTrue()
  }

  /**
   * Reproduces the production bug: ReVoman.revUp called with a v3 collection path that lives on a
   * child classloader's jar fails to route to V3Loader. The current contract of
   * `ReVoman.revUp(Kick)` uses `okio.FileSystem.RESOURCES` for classpath resolution, which is bound
   * to the okio class's classloader and cannot see the jar mounted on the thread context
   * classloader. Result: `isV3Collection` returns false, the v2 Moshi path is taken, and parsing
   * fails.
   *
   * Asserts on `providedStepsToExecuteCount` (set from the parsed collection size BEFORE any HTTP
   * execution) so the test proves V3 detection without firing network requests. All steps are
   * filtered out via a never-true `runOnlySteps` so the HTTP path stays cold.
   *
   * Currently FAILS pre-fix. After revoman is taught to use the thread context classloader for
   * classpath resolution, this test should PASS with `providedStepsToExecuteCount == 3`.
   */
  @Test
  fun testRevUpReadsV3CollectionFromChildClassLoaderJar() {
    val rundown =
      ReVoman.revUp(
        Kick.configure().templatePath("jar-only/flat").runOnlyStep(ExeStepPick { _ -> false }).off()
      )
    assertThat(rundown.providedStepsToExecuteCount).isEqualTo(3)
    assertThat(rundown.stepReports).isEmpty()
  }

  /**
   * Path segments containing spaces and brackets must round-trip through `cl.getResource` (which
   * URL-encodes them as `%20`, `%5B`, `%5D`) without corrupting the entry name passed to NIO ZipFS.
   * Real bazel runfiles tests have folder names like `0 - auth`, so this is the load-bearing case
   * for the Core FTest UnifiedValidationE2ETest.testAllRulesPositiveCleanSA.
   */
  @Test
  fun testRevUpReadsV3CollectionWithSpacesAndBracketsFromJar() {
    val rundown =
      ReVoman.revUp(
        Kick.configure()
          .templatePath("jar-only/with [brackets]")
          .runOnlyStep(ExeStepPick { _ -> false })
          .off()
      )
    assertThat(rundown.providedStepsToExecuteCount).isEqualTo(1)
  }

  private fun buildJarOnlyFixture(): File {
    val flatSrc = File("src/test/resources/pm-templates/v3/flat")
    val bracketsSrc = File("src/test/resources/pm-templates/v3/with [brackets]")
    val envSrc = File("src/test/resources/pm-templates/v3/test.environment.yaml")
    require(flatSrc.isDirectory) { "flat fixture not found: $flatSrc" }
    require(bracketsSrc.isDirectory) { "brackets fixture not found: $bracketsSrc" }
    require(envSrc.isFile) { "env yaml not found: $envSrc" }
    val jar = Files.createTempFile("jar-only-v3-fixture-", ".jar").toFile().apply { deleteOnExit() }
    val manifest = Manifest().apply { mainAttributes.putValue("Manifest-Version", "1.0") }
    JarOutputStream(jar.outputStream(), manifest).use { jos ->
      writeDirInto(jos, flatSrc, prefix = "jar-only/flat")
      writeDirInto(jos, bracketsSrc, prefix = "jar-only/with [brackets]")
      jos.putNextEntry(JarEntry("jar-only/test.environment.yaml"))
      envSrc.inputStream().use { it.copyTo(jos) }
      jos.closeEntry()
    }
    return jar
  }

  private fun writeDirInto(jos: JarOutputStream, dir: File, prefix: String) {
    dir.listFiles().orEmpty().forEach { child ->
      val entryName = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
      if (child.isDirectory) {
        jos.putNextEntry(JarEntry("$entryName/"))
        jos.closeEntry()
        writeDirInto(jos, child, prefix = entryName)
      } else {
        jos.putNextEntry(JarEntry(entryName))
        child.inputStream().use { it.copyTo(jos) }
        jos.closeEntry()
      }
    }
  }
}
