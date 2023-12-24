package com.salesforce.revoman.input

import io.kotest.matchers.string.shouldNotBeBlank
import java.io.File
import org.junit.jupiter.api.Test

class FileUtilsTest {
  @Test
  fun `read file from resources to string`() {
    readFileInResourcesToString("env-with-regex.json").shouldNotBeBlank()
  }

  @Test
  fun `read file to string`() {
    val file = File("src/test/resources/env-with-regex.json")
    readFileToString(file).shouldNotBeBlank()
  }
}
