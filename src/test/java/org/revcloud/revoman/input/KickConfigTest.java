package org.revcloud.revoman.input;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KickConfigTest {
  @Test
  @DisplayName("Success Config")
  void successConfig() {
    final var kick = Kick.configure().stepNameToSuccessType(Map.of());
  }
}
