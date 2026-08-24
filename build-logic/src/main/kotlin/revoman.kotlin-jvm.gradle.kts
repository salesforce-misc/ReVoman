import com.adarshr.gradle.testlogger.theme.ThemeType.MOCHA
import com.diffplug.spotless.LineEnding.PLATFORM_NATIVE
import dev.detekt.gradle.Detekt

plugins {
  kotlin("jvm")
  id("com.diffplug.spotless")
  id("dev.detekt")
  id("com.adarshr.test-logger")
}

val libs: VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies { testImplementation(libs.findBundle("kotest").get()) }

kotlin {
  jvmToolchain(libs.findVersion("jdk").get().requiredVersion.toInt())
  compilerOptions {
    freeCompilerArgs.addAll(
      "-jvm-default=enable",
      "-progressive",
      "-Xannotation-default-target=param-property",
      "-Xconsistent-data-class-copy-visibility",
    )
  }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

spotless {
  lineEndings = PLATFORM_NATIVE
  kotlin {
    target("src/*/kotlin/**/*.kt", "src/*/java/**/*.kt")
    targetExclude("build/**", ".gradle/**", "generated/**", "**/bin/**", "out/**", "tmp/**")
    ktfmt().googleStyle()
    trimTrailingWhitespace()
    endWithNewline()
  }
  kotlinGradle {
    target("*.gradle.kts", "src/**/*.gradle.kts")
    targetExclude("build/**", ".gradle/**", "generated/**", "**/bin/**", "out/**", "tmp/**")
    ktfmt().googleStyle()
    trimTrailingWhitespace()
    endWithNewline()
  }
  java {
    target("src/*/java/**/*.java")
    targetExclude("build/**", ".gradle/**", "generated/**", "**/bin/**", "out/**", "tmp/**")
    googleJavaFormat()
    importOrder()
    removeUnusedImports()
    forbidWildcardImports()
    trimTrailingWhitespace()
    leadingTabsToSpaces(2)
    endWithNewline()
  }
}

detekt {
  parallel = true
  buildUponDefaultConfig = true
  baseline = layout.settingsDirectory.file("detekt/baseline.xml").asFile
  config.setFrom(layout.settingsDirectory.file("detekt/config.yml"))
}

testlogger.theme = MOCHA

tasks.withType<Detekt>().configureEach { reports { checkstyle.required.set(true) } }
