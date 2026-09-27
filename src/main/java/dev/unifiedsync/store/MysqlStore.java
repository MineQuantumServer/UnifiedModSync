package dev.unifiedsync.store;

import dev.unifiedsync.Config;
import dev.unifiedsync.api.*;
import java.sql.*;
import java.util.*;

/**
 * MySQL is authoritative even when Redis is disabled. All writes are fenced by database-time
 * leases.
 */
public final class MysqlStore {
  private final Config config;

  public MysqlStore(Config config) {
    this.config = config;
  }

  private Connection open() throws SQLException {
    Properties p = new Properties();
    p.setProperty("user", config.user());
    p.setProperty("password", config.password());
    p.setProperty("connectTimeout", "" + config.timeout());
    p.setProperty("socketTimeout", "" + config.timeout());
    // Explicit driver instance: no JPMS service-loader or competing server JDBC driver dependency.
    return new com.mysql.cj.jdbc.Driver().connect(config.jdbcUrl(), p);
  }

  public void initialize() throws SQLException {
    try (Connection c = open();
        Statement s = c.createStatement()) {
      s.execute(
          "CREATE TABLE IF NOT EXISTS ums_leases (grp VARCHAR(48) CHARACTER SET ascii COLLATE"
              + " ascii_bin NOT NULL, id CHAR(36) CHARACTER SET ascii NOT NULL, token CHAR(36)"
              + " CHARACTER SET ascii NOT NULL, expires DATETIME(6) NOT NULL, PRIMARY KEY(grp,id))"
              + " ENGINE=InnoDB");
      s.execute(
          "CREATE TABLE IF NOT EXISTS ums_resources (grp VARCHAR(48) CHARACTER SET ascii COLLATE"
              + " ascii_bin NOT NULL, module VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT"
              + " NULL, id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, payload"
              + " LONGBLOB NULL, sha CHAR(64) CHARACTER SET ascii NULL, token CHAR(36) CHARACTER"
              + " SET ascii NULL, expires DATETIME(6) NULL, revision BIGINT NOT NULL DEFAULT 0,"
              + " PRIMARY KEY(grp,module,id)) ENGINE=InnoDB");
      s.execute(
          "CREATE TABLE IF NOT EXISTS ums_backups (seq BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
              + " grp VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, player CHAR(36)"
              + " CHARACTER SET ascii NOT NULL, module VARCHAR(32) CHARACTER SET ascii COLLATE"
              + " ascii_bin NOT NULL, created TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
              + " reason VARCHAR(32) NOT NULL, batch CHAR(36) CHARACTER SET ascii NOT NULL, payload"
              + " LONGBLOB NOT NULL, sha CHAR(64) CHARACTER SET ascii NOT NULL, INDEX"
              + " player_history(grp,player,module,seq)) ENGINE=InnoDB");
      try {
        s.executeQuery("SELECT batch FROM ums_backups LIMIT 0").close();
      } catch (SQLException e) {
        if (e.getErrorCode() != 1054) throw e;
        try {
          s.execute(
              "ALTER TABLE ums_backups ADD COLUMN batch CHAR(36) CHARACTER SET ascii NOT NULL"
                  + " DEFAULT ''");
        } catch (SQLException race) {
          if (race.getErrorCode() != 1060) throw race;
        }
        s.execute("UPDATE ums_backups SET batch=UUID() WHERE batch=''");
      }
    }
  }

  private PreparedStatement query(Connection c, String sql, Object... values) throws SQLException {
    PreparedStatement p = c.prepareStatement(sql);
    for (int i = 0; i < values.length; i++) p.setObject(i + 1, values[i]);
    return p;
  }

  private int exec(Connection c, String sql, Object... values) throws SQLException {
    try (var p = query(c, sql, values)) {
      return p.executeUpdate();
    }
  }

  public void claim(UUID player, String token) throws Exception {
    try (Connection c = open()) {
      c.setAutoCommit(false);
      try {
        exec(
            c,
            "INSERT IGNORE INTO ums_leases"
                + " VALUES(?,?,?,TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)))",
            config.group(),
            player.toString(),
            token,
            config.lease());
        try (var p =
                query(
                    c,
                    "SELECT token, expires>CURRENT_TIMESTAMP(6) FROM ums_leases WHERE grp=? AND"
                        + " id=? FOR UPDATE",
                    config.group(),
                    player.toString());
            var r = p.executeQuery()) {
          if (!r.next() || (!token.equals(r.getString(1)) && r.getBoolean(2)))
            throw new LeaseBusyException("Player is still leased by another session");
        }
        exec(
            c,
            "UPDATE ums_leases SET token=?,expires=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6))"
                + " WHERE grp=? AND id=?",
            token,
            config.lease(),
            config.group(),
            player.toString());
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    }
  }

  private void fence(Connection c, UUID player, String token) throws Exception {
    try (var p =
            query(
                c,
                "SELECT token,expires>CURRENT_TIMESTAMP(6) FROM ums_leases WHERE grp=? AND id=? FOR"
                    + " UPDATE",
                config.group(),
                player.toString());
        var r = p.executeQuery()) {
      if (!r.next() || !token.equals(r.getString(1)) || !r.getBoolean(2))
        throw new IllegalStateException("Player lease expired or was replaced");
    }
  }

  public Map<ResourceKey, byte[]> acquire(UUID player, String token, Set<ResourceKey> keys)
      throws Exception {
    return acquire(player, token, keys, false);
  }

  public void acquireForRestore(UUID player, String token, Set<ResourceKey> keys) throws Exception {
    acquire(player, token, keys, true);
  }

  private Map<ResourceKey, byte[]> acquire(
      UUID player, String token, Set<ResourceKey> keys, boolean restore) throws Exception {
    try (Connection c = open()) {
      c.setAutoCommit(false);
      try {
        fence(c, player, token);
        Map<ResourceKey, byte[]> result = new TreeMap<>();
        for (ResourceKey key : new TreeSet<>(keys)) {
          exec(
              c,
              "INSERT IGNORE INTO ums_resources(grp,module,id) VALUES(?,?,?)",
              config.group(),
              key.module(),
              key.id());
          try (var p =
                  query(
                      c,
                      "SELECT payload,sha,token,expires>CURRENT_TIMESTAMP(6) FROM ums_resources"
                          + " WHERE grp=? AND module=? AND id=? FOR UPDATE",
                      config.group(),
                      key.module(),
                      key.id());
              var r = p.executeQuery()) {
            if (!r.next()) throw new SQLException("Resource disappeared");
            if (r.getBoolean(4) && !token.equals(r.getString(3)))
              throw new LeaseBusyException("Resource is held by another player/server: " + key);
            byte[] bytes = r.getBytes(1);
            if (bytes != null && !restore) {
              SnapshotCodec.check(bytes, r.getString(2));
              result.put(key, bytes);
            }
          }
          exec(
              c,
              "UPDATE ums_resources SET token=?,expires=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6))"
                  + " WHERE grp=? AND module=? AND id=?",
              token,
              config.lease(),
              config.group(),
              key.module(),
              key.id());
        }
        c.commit();
        return result;
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    }
  }

  public void renew(UUID player, String token) throws Exception {
    try (Connection c = open()) {
      c.setAutoCommit(false);
      try {
        fence(c, player, token);
        // A resource lease which expired is never silently revived.
        try (var p =
                query(
                    c,
                    "SELECT id FROM ums_resources WHERE grp=? AND token=? AND"
                        + " expires<=CURRENT_TIMESTAMP(6) FOR UPDATE",
                    config.group(),
                    token);
            var r = p.executeQuery()) {
          if (r.next()) throw new IllegalStateException("Resource lease expired");
        }
        exec(
            c,
            "UPDATE ums_leases SET expires=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)) WHERE grp=?"
                + " AND id=? AND token=?",
            config.lease(),
            config.group(),
            player.toString(),
            token);
        exec(
            c,
            "UPDATE ums_resources SET expires=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)) WHERE"
                + " grp=? AND token=?",
            config.lease(),
            config.group(),
            token);
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    }
  }

  public void save(
      UUID player,
      String token,
      Map<String, Map<ResourceKey, byte[]>> modules,
      String reason,
      Set<ResourceKey> release)
      throws Exception {
    try (Connection c = open()) {
      c.setAutoCommit(false);
      try {
        fence(c, player, token);
        Map<ResourceKey, byte[]> sorted = new TreeMap<>();
        modules.values().forEach(sorted::putAll);
        for (var e : sorted.entrySet()) {
          var k = e.getKey();
          byte[] bytes = e.getValue();
          if (bytes == null || bytes.length == 0 || bytes.length > SnapshotCodec.MAX)
            throw new IllegalArgumentException("Invalid payload size");
          int count =
              exec(
                  c,
                  "UPDATE ums_resources SET payload=?,sha=?,revision=revision+1 WHERE grp=? AND"
                      + " module=? AND id=? AND token=? AND expires>CURRENT_TIMESTAMP(6)",
                  bytes,
                  SnapshotCodec.hash(bytes),
                  config.group(),
                  k.module(),
                  k.id(),
                  token);
          if (count != 1) throw new IllegalStateException("Write fence rejected " + k);
        }
        String batch = UUID.randomUUID().toString();
        for (var e : new TreeMap<>(modules).entrySet()) {
          byte[] snapshot = SnapshotCodec.encode(e.getValue());
          exec(
              c,
              "INSERT INTO ums_backups(grp,player,module,reason,batch,payload,sha)"
                  + " VALUES(?,?,?,?,?,?,?)",
              config.group(),
              player.toString(),
              e.getKey(),
              reason,
              batch,
              snapshot,
              SnapshotCodec.hash(snapshot));
          List<Long> old = new ArrayList<>();
          try (var p =
                  query(
                      c,
                      "SELECT seq FROM ums_backups WHERE grp=? AND player=? AND module=? ORDER BY"
                          + " seq DESC LIMIT 18446744073709551615 OFFSET "
                          + config.backups(),
                      config.group(),
                      player.toString(),
                      e.getKey());
              var r = p.executeQuery()) {
            while (r.next()) old.add(r.getLong(1));
          }
          for (Long seq : old) exec(c, "DELETE FROM ums_backups WHERE seq=?", seq);
        }
        for (ResourceKey k : new TreeSet<>(release))
          exec(
              c,
              "UPDATE ums_resources SET token=NULL,expires=NULL WHERE grp=? AND module=? AND id=?"
                  + " AND token=?",
              config.group(),
              k.module(),
              k.id(),
              token);
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    }
  }

  public Map<ResourceKey, byte[]> backup(UUID player, String module, int index) throws Exception {
    try (Connection c = open();
        var p =
            query(
                c,
                "SELECT payload,sha FROM ums_backups WHERE grp=? AND player=? AND module=? ORDER BY"
                    + " seq DESC LIMIT 1 OFFSET "
                    + index,
                config.group(),
                player.toString(),
                module);
        var r = p.executeQuery()) {
      if (!r.next())
        throw new IllegalStateException("No backup at index " + index + " for " + module);
      byte[] data = r.getBytes(1);
      SnapshotCodec.check(data, r.getString(2));
      return SnapshotCodec.decode(data);
    }
  }

  public List<String> history(UUID player, String module) throws Exception {
    List<String> result = new ArrayList<>();
    try (Connection c = open();
        var p =
            query(
                c,
                "SELECT created,reason FROM ums_backups WHERE grp=? AND player=? AND module=? ORDER"
                    + " BY seq DESC",
                config.group(),
                player.toString(),
                module);
        var r = p.executeQuery()) {
      int i = 0;
      while (r.next()) result.add("#" + (i++) + " " + r.getTimestamp(1) + " " + r.getString(2));
    }
    return result;
  }

  public Map<ResourceKey, byte[]> backupBundle(UUID player, Set<String> modules, int index)
      throws Exception {
    if (modules.size() == 1) return backup(player, modules.iterator().next(), index);
    try (Connection c = open()) {
      String batch =
          commonBatches(c, player, modules).stream()
              .skip(index)
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "No complete all-module checkpoint at index " + index));
      Map<ResourceKey, byte[]> result = new TreeMap<>();
      try (var p =
              query(
                  c,
                  "SELECT module,payload,sha FROM ums_backups WHERE grp=? AND player=? AND batch=?",
                  config.group(),
                  player.toString(),
                  batch);
          var r = p.executeQuery()) {
        while (r.next())
          if (modules.contains(r.getString(1))) {
            byte[] bytes = r.getBytes(2);
            SnapshotCodec.check(bytes, r.getString(3));
            result.putAll(SnapshotCodec.decode(bytes));
          }
      }
      return result;
    }
  }

  private List<String> commonBatches(Connection c, UUID player, Set<String> modules)
      throws Exception {
    if (modules.isEmpty()) throw new IllegalArgumentException("No modules selected");
    String in = String.join(",", Collections.nCopies(modules.size(), "?"));
    List<Object> args = new ArrayList<>(List.of(config.group(), player.toString()));
    args.addAll(new TreeSet<>(modules));
    args.add(modules.size());
    List<String> result = new ArrayList<>();
    try (var p =
            query(
                c,
                "SELECT batch FROM ums_backups WHERE grp=? AND player=? AND module IN ("
                    + in
                    + ") GROUP BY batch HAVING COUNT(DISTINCT module)=? ORDER BY MAX(seq) DESC",
                args.toArray());
        var r = p.executeQuery()) {
      while (r.next()) result.add(r.getString(1));
    }
    return result;
  }

  public List<String> bundleHistory(UUID player, Set<String> modules) throws Exception {
    if (modules.size() == 1) return history(player, modules.iterator().next());
    List<String> result = new ArrayList<>();
    try (Connection c = open()) {
      for (String batch : commonBatches(c, player, modules))
        try (var p =
                query(
                    c,
                    "SELECT created,reason FROM ums_backups WHERE grp=? AND player=? AND batch=?"
                        + " ORDER BY seq DESC LIMIT 1",
                    config.group(),
                    player.toString(),
                    batch);
            var r = p.executeQuery()) {
          if (r.next())
            result.add(
                "#"
                    + result.size()
                    + " "
                    + r.getTimestamp(1)
                    + " "
                    + r.getString(2)
                    + " [all modules]");
        }
    }
    return result;
  }

  public void release(UUID player, String token) throws Exception {
    try (Connection c = open()) {
      c.setAutoCommit(false);
      try {
        exec(
            c,
            "UPDATE ums_resources SET token=NULL,expires=NULL WHERE grp=? AND token=?",
            config.group(),
            token);
        exec(
            c,
            "DELETE FROM ums_leases WHERE grp=? AND id=? AND token=?",
            config.group(),
            player.toString(),
            token);
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    }
  }
}
