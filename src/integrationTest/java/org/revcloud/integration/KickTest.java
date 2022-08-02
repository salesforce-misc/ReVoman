package org.revcloud.integration;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.revcloud.input.Kick;

class KickTest {
  @Test
  @DisplayName("Test Default values")
  void defaultValues() {
    final var kickOffConfig =
        Kick.configure()
            .templatePath("templatePath")
            .environmentPath(null)
            .itemNameToSuccessType(Map.of())
            .off();
    System.out.println(kickOffConfig);
  }
}
