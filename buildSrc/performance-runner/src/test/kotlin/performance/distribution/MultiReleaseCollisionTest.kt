/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.distribution

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path
import performance.support.DistributionFixture
import performance.support.DistributionFixture.Companion.BENCHMARK_DEPENDENCY
import performance.support.DistributionFixture.Companion.PRODUCTION_JAR
import performance.support.DistributionFixture.Companion.compiledClass
import performance.support.DistributionFixture.Companion.compiledModuleInfo

class MultiReleaseCollisionTest :
  FunSpec(
    {
      test("feature 21 rejects a duplicate class supplied only by a versioned entry") {
        withMultiReleaseFixture { fixture ->
          fixture.replaceJar(
            BENCHMARK_DEPENDENCY,
            mapOf(
              "META-INF/versions/21/example/Application.class" to
                compiledClass("example.Application", publicType = false),
              "example/Dependency.class" to compiledClass("example.Dependency"),
            ),
            multiRelease = true,
          )

          fixture.assertInvalidWithoutProcess(DistributionProblem.DUPLICATE_EFFECTIVE_CLASS)
        }
      }

      test("module descriptors in multiple jars are exempt from effective class collisions") {
        withMultiReleaseFixture { fixture ->
          fixture.replaceJar(
            PRODUCTION_JAR,
            mapOf(
              "META-INF/versions/21/module-info.class" to
                compiledModuleInfo("fixture.production"),
              "example/Application.class" to compiledClass("example.Application"),
              "module-info.class" to compiledModuleInfo("fixture.production"),
            ),
            multiRelease = true,
          )
          fixture.replaceJar(
            BENCHMARK_DEPENDENCY,
            mapOf(
              "META-INF/versions/21/module-info.class" to
                compiledModuleInfo("fixture.dependency"),
              "example/Dependency.class" to compiledClass("example.Dependency"),
              "module-info.class" to compiledModuleInfo("fixture.dependency"),
            ),
            multiRelease = true,
          )
          val processSpy = MultiReleaseProcessSpy()

          val validation = fixture.validateBeforeProcess(processSpy)

          validation.shouldBeInstanceOf<DistributionValidation.Valid>()
          processSpy.requests shouldBe 1
        }
      }

      test("classes versioned above the selected feature do not participate in collisions") {
        withMultiReleaseFixture { fixture ->
          fixture.replaceJar(
            BENCHMARK_DEPENDENCY,
            mapOf(
              "META-INF/versions/22/example/Application.class" to
                compiledClass("example.Application", publicType = false),
              "example/Dependency.class" to compiledClass("example.Dependency"),
            ),
            multiRelease = true,
          )
          val processSpy = MultiReleaseProcessSpy()

          val validation = fixture.validateBeforeProcess(processSpy)

          validation.shouldBeInstanceOf<DistributionValidation.Valid>()
          processSpy.requests shouldBe 1
        }
      }

      test("versioned classes require a true Multi-Release manifest attribute") {
        withMultiReleaseFixture { fixture ->
          fixture.replaceJar(
            BENCHMARK_DEPENDENCY,
            mapOf(
              "META-INF/versions/21/example/Versioned.class" to
                compiledClass("example.Versioned", publicType = false),
              "example/Dependency.class" to compiledClass("example.Dependency"),
            ),
            multiRelease = false,
          )

          fixture.assertInvalidWithoutProcess(DistributionProblem.INVALID_MULTI_RELEASE_JAR)
        }
      }

      test("standard multi-release directory entries are accepted") {
        withMultiReleaseFixture { fixture ->
          fixture.replaceJar(
            BENCHMARK_DEPENDENCY,
            mapOf(
              "META-INF/versions/" to byteArrayOf(),
              "META-INF/versions/21/" to byteArrayOf(),
              "META-INF/versions/21/example/Versioned.class" to
                compiledClass("example.Versioned", publicType = false),
              "example/Dependency.class" to compiledClass("example.Dependency"),
            ),
            multiRelease = true,
          )
          val processSpy = MultiReleaseProcessSpy()

          val validation = fixture.validateBeforeProcess(processSpy)

          validation.shouldBeInstanceOf<DistributionValidation.Valid>()
          processSpy.requests shouldBe 1
        }
      }

      test("the JDK validator rejects incompatible public APIs across releases") {
        withMultiReleaseFixture { fixture ->
          fixture.replaceJar(
            BENCHMARK_DEPENDENCY,
            mapOf(
              "META-INF/versions/21/example/VersionedApi.class" to
                compiledClass("example.VersionedApi", release = 21),
              "example/Dependency.class" to compiledClass("example.Dependency"),
              "example/VersionedApi.class" to
                compiledClass(
                  "example.VersionedApi",
                  members = "public void stable() {}",
                  release = 8,
                ),
            ),
            multiRelease = true,
          )

          fixture.assertInvalidWithoutProcess(DistributionProblem.INVALID_JAR)
        }
      }

      test("multi-release paths below version 9 are rejected") {
        withMultiReleaseFixture { fixture ->
          fixture.replaceJar(
            BENCHMARK_DEPENDENCY,
            mapOf(
              "META-INF/versions/8/example/Versioned.class" to
                compiledClass("example.Versioned", publicType = false, release = 8),
              "example/Dependency.class" to compiledClass("example.Dependency"),
            ),
            multiRelease = true,
          )

          fixture.assertInvalidWithoutProcess(DistributionProblem.INVALID_MULTI_RELEASE_JAR)
        }
      }
    },
  )

private class MultiReleaseProcessSpy {
  var requests: Int = 0
    private set
  var requestedRoot: Path? = null
    private set

  fun request(distribution: VerifiedDistribution) {
    requestedRoot = distribution.root
    requests += 1
  }
}

private fun DistributionFixture.validateBeforeProcess(
  processSpy: MultiReleaseProcessSpy,
): DistributionValidation =
  DistributionValidator().validate(request()).also { validation ->
    if (validation is DistributionValidation.Valid) {
      processSpy.request(validation.distribution)
    }
  }

private fun DistributionFixture.assertInvalidWithoutProcess(expected: DistributionProblem) {
  val processSpy = MultiReleaseProcessSpy()
  val validation = validateBeforeProcess(processSpy) as DistributionValidation.Invalid

  validation.problems shouldContain expected
  processSpy.requests shouldBe 0
}

private inline fun withMultiReleaseFixture(block: (DistributionFixture) -> Unit) {
  val fixture = DistributionFixture.create()
  try {
    block(fixture)
  } finally {
    fixture.close()
  }
}
