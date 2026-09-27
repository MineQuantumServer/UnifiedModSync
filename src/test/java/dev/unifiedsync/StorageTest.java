package dev.unifiedsync;

import static org.junit.jupiter.api.Assertions.*;

import dev.unifiedsync.api.*;
import dev.unifiedsync.store.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

public class StorageTest {
  @TempDir Path dir;

  @Test
  void snapshotValidation() throws Exception {
    var key = new ResourceKey("backpacks", UUID.randomUUID().toString());
    var map = Map.of(key, new byte[] {1, 2, 3});
    byte[] data = SnapshotCodec.encode(map);
    assertArrayEquals(map.get(key), SnapshotCodec.decode(data).get(key));
    String hash = SnapshotCodec.hash(data);
    data[data.length - 1] ^= 1;
    assertThrows(Exception.class, () -> SnapshotCodec.check(data, hash));
    assertThrows(Exception.class, () -> SnapshotCodec.decode(Arrays.copyOf(data, data.length - 2)));
    assertThrows(Exception.class, () -> SnapshotCodec.decode(new byte[8]));
    assertThrows(IllegalArgumentException.class, () -> new ResourceKey("bad/module", "id"));
  }

  private Config config() throws Exception {
    Path p = dir.resolve("sync.properties");
    Config.load(p);
    String s =
        Files.readString(p)
            .replace(
                "sync-group=main",
                "sync-group=test_" + UUID.randomUUID().toString().replace("-", ""))
            .replace(
                "jdbc:mysql://127.0.0.1:3306/unified_sync",
                "jdbc:mysql://127.0.0.1:13306/unified_sync_test")
            .replace("mysql.user=unified_sync", "mysql.user=root")
            .replace("redis.port=6379", "redis.port=16379")
            .replace("backup-count=10", "backup-count=2");
    Files.writeString(p, s);
    return Config.load(p);
  }

  @Test
  void mysqlFencesTransactionsBackupsAndCorruption() throws Exception {
    Assumptions.assumeTrue(Boolean.getBoolean("ums.integration"));
    Config c = config();
    MysqlStore db = new MysqlStore(c);
    db.initialize();
    UUID player = UUID.randomUUID(), other = UUID.randomUUID();
    String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
    ResourceKey bag = new ResourceKey("backpacks", UUID.randomUUID().toString());
    db.claim(player, a);
    assertThrows(Exception.class, () -> db.claim(player, b));
    assertTrue(db.acquire(player, a, Set.of(bag)).isEmpty());
    db.claim(other, b);
    assertThrows(Exception.class, () -> db.acquire(other, b, Set.of(bag)));
    for (int i = 1; i <= 3; i++)
      db.save(player, a, Map.of("backpacks", Map.of(bag, new byte[] {(byte) i})), "test", Set.of());
    assertEquals(2, db.history(player, "backpacks").size());
    assertArrayEquals(new byte[] {3}, db.backup(player, "backpacks", 0).get(bag));
    assertArrayEquals(new byte[] {2}, db.backup(player, "backpacks", 1).get(bag));
    ResourceKey unowned = new ResourceKey("wardrobe", player.toString());
    assertThrows(
        Exception.class,
        () ->
            db.save(
                player,
                a,
                Map.of(
                    "backpacks",
                    Map.of(bag, new byte[] {99}),
                    "wardrobe",
                    Map.of(unowned, new byte[] {9})),
                "bad",
                Set.of()));
    assertArrayEquals(new byte[] {3}, db.acquire(player, a, Set.of(bag)).get(bag));
    db.save(player, a, Map.of(), "release", Set.of(bag));
    assertArrayEquals(new byte[] {3}, db.acquire(other, b, Set.of(bag)).get(bag));
    assertThrows(
        Exception.class,
        () ->
            db.save(
                player, a, Map.of("backpacks", Map.of(bag, new byte[] {8})), "stale", Set.of()));
    db.release(player, a);
    db.claim(player, b);
    db.release(player, a);
    db.renew(player, b);
    Properties props = new Properties();
    props.setProperty("user", c.user());
    props.setProperty("password", c.password());
    try (Connection conn = new com.mysql.cj.jdbc.Driver().connect(c.jdbcUrl(), props);
        var p =
            conn.prepareStatement(
                "UPDATE ums_resources SET payload=? WHERE grp=? AND module=? AND id=?")) {
      p.setBytes(1, new byte[] {17});
      p.setString(2, c.group());
      p.setString(3, bag.module());
      p.setString(4, bag.id());
      p.executeUpdate();
    }
    assertThrows(Exception.class, () -> db.acquire(other, b, Set.of(bag)));
    db.release(other, b);
    db.release(player, b);
  }

  @Test
  void optionalRedisTokenFencing() throws Exception {
    Assumptions.assumeTrue(Boolean.getBoolean("ums.integration"));
    RedisLease redis = new RedisLease(config());
    UUID id = UUID.randomUUID();
    assertTrue(redis.acquire(id, "a"));
    assertFalse(redis.acquire(id, "b"));
    redis.release(id, "b");
    redis.renew(id, "a");
    assertThrows(Exception.class, () -> redis.renew(id, "b"));
    redis.release(id, "a");
    assertTrue(redis.acquire(id, "b"));
    redis.release(id, "b");
  }

  @Test
  void allModuleRollbackUsesOneAtomicCheckpointAndExpiredWritersFail() throws Exception {
    Assumptions.assumeTrue(Boolean.getBoolean("ums.integration"));
    Config c = config();
    MysqlStore db = new MysqlStore(c);
    db.initialize();
    UUID id = UUID.randomUUID();
    String token = UUID.randomUUID().toString();
    ResourceKey a = new ResourceKey("astral", id.toString()),
        w = new ResourceKey("wardrobe", id.toString());
    db.claim(id, token);
    db.acquire(id, token, Set.of(a, w));
    db.save(
        id,
        token,
        Map.of("astral", Map.of(a, new byte[] {1}), "wardrobe", Map.of(w, new byte[] {1})),
        "all",
        Set.of());
    db.save(id, token, Map.of("astral", Map.of(a, new byte[] {2})), "one", Set.of());
    var all = db.backupBundle(id, Set.of("astral", "wardrobe"), 0);
    assertArrayEquals(new byte[] {1}, all.get(a));
    assertArrayEquals(new byte[] {1}, all.get(w));
    assertArrayEquals(new byte[] {2}, db.backup(id, "astral", 0).get(a));
    assertEquals(1, db.bundleHistory(id, Set.of("astral", "wardrobe")).size());
    Properties props = new Properties();
    props.setProperty("user", c.user());
    props.setProperty("password", c.password());
    try (var conn = new com.mysql.cj.jdbc.Driver().connect(c.jdbcUrl(), props);
        var p =
            conn.prepareStatement(
                "UPDATE ums_leases SET expires=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE"
                    + " grp=? AND id=?")) {
      p.setString(1, c.group());
      p.setString(2, id.toString());
      p.executeUpdate();
    }
    assertThrows(Exception.class, () -> db.renew(id, token));
    assertThrows(
        Exception.class,
        () -> db.save(id, token, Map.of("astral", Map.of(a, new byte[] {9})), "expired", Set.of()));
    db.release(id, token);
  }
}
