package dev.unifiedsync;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public record Config(
    boolean enabled,
    String serverId,
    String group,
    String jdbcUrl,
    String user,
    String password,
    boolean redis,
    String redisHost,
    int redisPort,
    String redisUser,
    String redisPassword,
    int redisDatabase,
    boolean redisTls,
    int timeout,
    int lease,
    int delay,
    int loadTimeout,
    int autosave,
    boolean worldSave,
    int backups,
    int maxBags,
    int maxDepth,
    Set<String> modules,
    boolean importLocal,
    String ktgMode,
    Set<String> allowedCommands) {
  public static Config load(Path path) throws IOException {
    if (!Files.exists(path)) {
      Files.createDirectories(path.getParent());
      try (var in = Config.class.getResourceAsStream("/unified-mod-sync.example.properties")) {
        Files.copy(in, path);
      }
    }
    Properties p = new Properties();
    try (var r = Files.newBufferedReader(path)) {
      p.load(r);
    }
    String group = p.getProperty("sync-group", "main");
    if (!group.matches("[a-zA-Z0-9_-]{1,48}"))
      throw new IllegalArgumentException("Invalid sync-group");
    String ktg = p.getProperty("ktg-mode", "auto");
    if (!Set.of("auto", "required", "off").contains(ktg))
      throw new IllegalArgumentException("Invalid ktg-mode");
    String saveMode = p.getProperty("autosave-mode", "world");
    if (!Set.of("world", "interval").contains(saveMode))
      throw new IllegalArgumentException("Invalid autosave-mode");
    return new Config(
        bool(p, "enabled", false),
        p.getProperty("server-id", "server"),
        group,
        p.getProperty("mysql.url"),
        p.getProperty("mysql.user"),
        System.getenv().getOrDefault("UMS_MYSQL_PASSWORD", p.getProperty("mysql.password", "")),
        bool(p, "redis.enabled", false),
        p.getProperty("redis.host", "127.0.0.1"),
        num(p, "redis.port", 6379, 1, 65535),
        p.getProperty("redis.user", ""),
        System.getenv().getOrDefault("UMS_REDIS_PASSWORD", p.getProperty("redis.password", "")),
        num(p, "redis.database", 0, 0, 15),
        bool(p, "redis.tls", false),
        num(p, "network-timeout-ms", 3000, 500, 10000),
        num(p, "lease-seconds", 90, 30, 600),
        num(p, "join-delay-seconds", 5, 0, 600),
        num(p, "load-timeout-seconds", 120, 10, 1800),
        num(p, "autosave-seconds", 60, 5, 3600),
        saveMode.equals("world"),
        num(p, "backup-count", 10, 2, 100),
        num(p, "max-backpacks", 128, 1, 1024),
        num(p, "max-nesting-depth", 8, 1, 32),
        list(p, "modules", "wardrobe,backpacks,astral"),
        bool(p, "import-local", true),
        ktg,
        list(p, "protected-command-allowlist", "login,l,register,reg,2fa"));
  }

  private static boolean bool(Properties p, String k, boolean d) {
    return Boolean.parseBoolean(p.getProperty(k, "" + d));
  }

  private static int num(Properties p, String k, int d, int min, int max) {
    int v = Integer.parseInt(p.getProperty(k, "" + d));
    if (v < min || v > max) throw new IllegalArgumentException(k);
    return v;
  }

  private static Set<String> list(Properties p, String k, String d) {
    Set<String> r = new LinkedHashSet<>();
    for (String s : p.getProperty(k, d).split(",")) if (!s.isBlank()) r.add(s.trim());
    return Set.copyOf(r);
  }

  @Override
  public String toString() {
    return "UnifiedSync[" + serverId + ", " + group + "]";
  }
}
