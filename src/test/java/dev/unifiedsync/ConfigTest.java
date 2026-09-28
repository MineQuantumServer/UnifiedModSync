package dev.unifiedsync;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {
  @TempDir Path dir;

  @Test
  void existingConfigWithoutModeFollowsWorld() throws Exception {
    Path file = dir.resolve("sync.properties");
    Files.writeString(file, "autosave-seconds=25\n");
    assertTrue(Config.load(file).worldSave());
  }

  @Test
  void intervalIsExplicitOptIn() throws Exception {
    Path file = dir.resolve("sync.properties");
    Files.writeString(file, "autosave-mode=interval\nautosave-seconds=25\n");
    var config = Config.load(file);
    assertFalse(config.worldSave());
    assertEquals(25, config.autosave());
  }

  @Test
  void invalidModeRejected() throws Exception {
    Path file = dir.resolve("sync.properties");
    Files.writeString(file, "autosave-mode=typo\n");
    assertThrows(IllegalArgumentException.class, () -> Config.load(file));
  }
}
