package dev.unifiedsync;

import static org.junit.jupiter.api.Assertions.*;

import dev.unifiedsync.api.ResourceKey;
import org.junit.jupiter.api.Test;

class ResourceKeyTest {
  @Test
  void validationKeepsAsciiBoundaries() {
    assertDoesNotThrow(() -> new ResourceKey("a_z09", "AZaz09_-"));
    assertDoesNotThrow(() -> new ResourceKey("a".repeat(32), "Z".repeat(64)));
    for (String module : new String[] {"", "A", "a-b", "a".repeat(33), "é", "a\n"})
      assertThrows(IllegalArgumentException.class, () -> new ResourceKey(module, "id"));
    for (String id : new String[] {"", "a/b", "a:b", "a".repeat(65), "中", "x\n"})
      assertThrows(IllegalArgumentException.class, () -> new ResourceKey("module", id));
  }
}
