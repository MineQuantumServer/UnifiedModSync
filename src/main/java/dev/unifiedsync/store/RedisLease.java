package dev.unifiedsync.store;

import dev.unifiedsync.Config;
import java.util.*;
import redis.clients.jedis.*;
import redis.clients.jedis.params.SetParams;

public final class RedisLease {
  private final Config config;

  public RedisLease(Config config) {
    this.config = config;
  }

  private Jedis open() {
    var builder =
        DefaultJedisClientConfig.builder()
            .connectionTimeoutMillis(config.timeout())
            .socketTimeoutMillis(config.timeout())
            .database(config.redisDatabase())
            .ssl(config.redisTls());
    if (!config.redisUser().isBlank()) builder.user(config.redisUser());
    if (!config.redisPassword().isEmpty()) builder.password(config.redisPassword());
    return new Jedis(new HostAndPort(config.redisHost(), config.redisPort()), builder.build());
  }

  private String key(UUID uuid) {
    return "unifiedsync:" + config.group() + ":lease:" + uuid;
  }

  public void ping() {
    try (Jedis j = open()) {
      j.ping();
    }
  }

  public boolean acquire(UUID uuid, String token) {
    try (Jedis j = open()) {
      return "OK".equals(j.set(key(uuid), token, SetParams.setParams().nx().ex(config.lease())));
    }
  }

  public void renew(UUID uuid, String token) {
    try (Jedis j = open()) {
      Object result =
          j.eval(
              "if redis.call('get',KEYS[1])==ARGV[1] then return"
                  + " redis.call('expire',KEYS[1],ARGV[2]) else return 0 end",
              List.of(key(uuid)),
              List.of(token, Integer.toString(config.lease())));
      if (!Long.valueOf(1).equals(result))
        throw new IllegalStateException("Redis session lease lost");
    }
  }

  public void release(UUID uuid, String token) {
    try (Jedis j = open()) {
      j.eval(
          "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return"
              + " 0 end",
          List.of(key(uuid)),
          List.of(token));
    }
  }
}
