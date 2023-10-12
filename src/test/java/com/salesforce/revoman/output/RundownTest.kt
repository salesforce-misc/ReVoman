package com.salesforce.revoman.output

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RundownTest {

  @Test
  fun `build step name`() {
    buildStepName("1.2.1", "POST", "product-setup", "OneTime", "One-Time Product") shouldBe
      "1.2.1${INDEX_SEPARATOR}POST${HTTP_METHOD_SEPARATOR}product-setup${FOLDER_DELIMITER}OneTime${FOLDER_DELIMITER}One-Time Product"
  }
}
