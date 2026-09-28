package dev.unifiedsync;

import dev.unifiedsync.api.*;
import dev.unifiedsync.module.*;
import dev.unifiedsync.store.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayer;

public final class Coordinator implements AutoCloseable {
  public enum State {
    WAITING,
    LOADING,
    READY,
    SAVING,
    ERROR,
    BYPASS,
    CLOSING
  }

  public static final class Session {
    public final UUID uuid;
    public final String token = UUID.randomUUID().toString();
    public ServerPlayer player;
    public volatile State state = State.WAITING;
    public String error = "";
    public Vec3 anchor;
    public long joined = System.nanoTime(), lastLease, lastSave;
    public boolean claimed, busy, renewing, closing, released;
    public CompletableFuture<?> pending = CompletableFuture.completedFuture(null),
        heartbeat = CompletableFuture.completedFuture(null);
    public long retryAt;
    public int generation, rounds;
    public final Object ioLock = new Object();
    public Set<ResourceKey> owned = new TreeSet<>();
    private Map<String, Map<ResourceKey, byte[]>> queuedWorldSave;

    Session(ServerPlayer p) {
      player = p;
      uuid = p.getUUID();
      anchor = p.position();
    }
  }

  @FunctionalInterface
  private interface IO<T> {
    T run() throws Exception;
  }

  @FunctionalInterface
  private interface Done<T> {
    void accept(T value) throws Exception;
  }

  public final Config config;
  private final MinecraftServer server;
  private final MysqlStore db;
  private final RedisLease redis;
  private final ExecutorService workers =
      Executors.newFixedThreadPool(
          4,
          r -> {
            Thread t = new Thread(r, "UnifiedSync-IO");
            t.setDaemon(true);
            return t;
          });
  private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
  private final Map<UUID, ServerPlayer> waitingJoins = new HashMap<>();
  private final Map<String, SyncModule> modules = new LinkedHashMap<>();
  private volatile String startupError;
  private volatile boolean initialized;
  private int ticks;
  private volatile boolean stopping;

  public Coordinator(MinecraftServer server, Config config) {
    this.server = server;
    this.config = config;
    db = new MysqlStore(config);
    redis = config.redis() ? new RedisLease(config) : null;
    try {
      List<SyncModule> available =
          new ArrayList<>(
              List.of(new WardrobeModule(), new BackpackModule(config), new AstralModule()));
      ServiceLoader.load(SyncModule.class).forEach(available::add);
      UnifiedSync.extraModules().forEach(factory -> available.add(factory.get()));
      for (SyncModule m : available)
        if (config.modules().contains(m.id()) && ModList.get().isLoaded(m.requiredMod())) {
          m.verify();
          if (modules.put(m.id(), m) != null)
            throw new IllegalStateException("Duplicate module " + m.id());
        }
      Set<String> known = new HashSet<>();
      available.forEach(m -> known.add(m.id()));
      for (String id : config.modules())
        if (!known.contains(id)) throw new IllegalStateException("Unknown configured module " + id);
      if (config.enabled() && modules.isEmpty())
        throw new IllegalStateException("No installed enabled sync modules");
    } catch (Exception e) {
      startupError = "Module compatibility check failed: " + brief(e);
      UnifiedSync.LOG.error("Module verification failed", e);
    }
    if (config.enabled() && startupError == null)
      workers.submit(
          () -> {
            try {
              db.initialize();
              if (redis != null) redis.ping();
              initialized = true;
            } catch (Exception e) {
              startupError = "Database initialization failed: " + brief(e);
              UnifiedSync.LOG.error("Database initialization failed", e);
            }
          });
  }

  public Set<String> moduleIds() {
    return Set.copyOf(modules.keySet());
  }

  public Session session(UUID id) {
    return sessions.get(id);
  }

  public boolean active() {
    return config.enabled();
  }

  public boolean astralEnabled() {
    return active() && modules.containsKey("astral");
  }

  public boolean protectedPlayer(ServerPlayer p) {
    if (!active() || p instanceof FakePlayer) return false;
    Session s = sessions.get(p.getUUID());
    return s == null || s.player != p || !(s.state == State.READY || s.state == State.BYPASS);
  }

  public void join(ServerPlayer p) {
    if (!active() || p instanceof FakePlayer) return;
    Session old = sessions.get(p.getUUID());
    if (old != null) {
      old.closing = true;
      waitingJoins.put(p.getUUID(), p);
      return;
    }
    sessions.put(p.getUUID(), new Session(p));
  }

  public void clonePlayer(ServerPlayer p) {
    Session s = session(p.getUUID());
    if (s != null) {
      s.player = p;
      s.anchor = p.position();
    }
  }

  public void logout(ServerPlayer p) {
    waitingJoins.remove(p.getUUID(), p);
    Session s = session(p.getUUID());
    if (s != null && s.player == p) s.closing = true;
  }

  private static long seconds(long since) {
    return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - since);
  }

  public void tick() {
    ticks++;
    for (Session s : List.copyOf(sessions.values())) {
      if (s.closing) {
        if (!s.busy && !s.renewing) finishLogout(s);
        continue;
      }
      if (s.state == State.BYPASS) continue;
      // KTG's authoritative NMS load can restore position while the client is already frozen.
      if (s.state == State.WAITING) s.anchor = s.player.position();
      if (s.claimed && seconds(s.lastLease) >= config.lease() / 2 && s.state != State.ERROR)
        fail(s, new IllegalStateException("Lease confirmation overdue"));
      if (s.claimed
          && !s.renewing
          && !s.released
          && seconds(s.lastLease) >= Math.max(2, config.lease() / 4)) renew(s);
      if (protectedPlayer(s.player)) {
        s.player.setDeltaMovement(Vec3.ZERO);
        s.player.fallDistance = 0;
        if (s.player.position().distanceToSqr(s.anchor) > 0.001)
          s.player.connection.teleport(
              s.anchor.x, s.anchor.y, s.anchor.z, s.player.getYRot(), s.player.getXRot());
        if (ticks % 40 == 0)
          s.player.displayClientMessage(
              Component.literal(
                  s.state == State.ERROR ? "数据同步异常，已进入保护状态，请联系服务器管理员。" : "正在同步数据，请稍候……"),
              true);
      }
      if (s.state == State.WAITING && !s.busy && System.nanoTime() >= s.retryAt) {
        try {
          if (startupError != null) throw new IllegalStateException(startupError);
          if (seconds(s.joined) > config.loadTimeout())
            throw new IllegalStateException("Timed out waiting for database / KTG4 / login delay");
          if (initialized && seconds(s.joined) >= config.delay() && KtgGate.ready(s.player, config))
            startLoad(s);
        } catch (Exception e) {
          fail(s, e);
        }
      } else if (s.state == State.READY) {
        try {
          if (ticks % 20 == 0 && !KtgGate.ready(s.player, config))
            throw new IllegalStateException(
                "KTG4 started another inventory load; retry when complete");
          if (checkResources(s)
              && !s.busy
              && !config.worldSave()
              && seconds(s.lastSave) >= config.autosave()) autosave(s);
        } catch (Exception e) {
          fail(s, e);
        }
      } else if (s.state == State.LOADING) {
        if (seconds(s.joined) > config.loadTimeout())
          fail(s, new IllegalStateException("Load timed out"));
        else if (!s.busy && s.claimed && System.nanoTime() >= s.retryAt)
          try {
            loadRound(s);
          } catch (Exception e) {
            fail(s, e);
          }
      }
    }
  }

  private <T> void async(Session s, IO<T> action, Done<T> done) {
    if (s.busy) throw new IllegalStateException("Session already busy");
    s.busy = true;
    int generation = s.generation;
    s.pending =
        CompletableFuture.runAsync(
            () -> {
              T result = null;
              Exception error = null;
              try {
                synchronized (s.ioLock) {
                  result = action.run();
                }
              } catch (Exception e) {
                error = e;
              }
              T value = result;
              Exception failure = error;
              server.execute(
                  () -> {
                    if (stopping) return;
                    s.busy = false;
                    if (s.generation != generation || sessions.get(s.uuid) != s) {
                      if (sessions.get(s.uuid) != s) releaseAbandoned(s);
                      return;
                    }
                    if (s.state == State.LOADING
                        && failure instanceof LeaseBusyException
                        && seconds(s.joined) < config.loadTimeout()) {
                      s.state = s.claimed ? State.LOADING : State.WAITING;
                      s.retryAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                      if (s.claimed) s.rounds--;
                      return;
                    }
                    if (failure != null) {
                      fail(s, failure);
                      return;
                    }
                    try {
                      done.accept(value);
                    } catch (Exception e) {
                      fail(s, e);
                    }
                  });
            },
            workers);
  }

  private void renew(Session s) {
    s.renewing = true;
    long started = System.nanoTime();
    s.heartbeat =
        CompletableFuture.runAsync(
            () -> {
              Exception failure = null;
              try {
                synchronized (s.ioLock) {
                  db.renew(s.uuid, s.token);
                  if (redis != null) redis.renew(s.uuid, s.token);
                }
              } catch (Exception e) {
                failure = e;
              }
              Exception error = failure;
              server.execute(
                  () -> {
                    if (stopping) return;
                    s.renewing = false;
                    if (error != null) {
                      s.released = true;
                      fail(s, error);
                    } else s.lastLease = started;
                  });
            },
            workers);
  }

  private void freeze(Session s, State state) {
    s.queuedWorldSave = null;
    s.anchor = s.player.position();
    s.state = state;
    s.player.closeContainer();
  }

  private void startLoad(Session s) {
    freeze(s, State.LOADING);
    async(
        s,
        () -> {
          boolean r = false;
          try {
            if (redis != null) {
              if (!redis.acquire(s.uuid, s.token))
                throw new LeaseBusyException("Redis lease held by another server");
              r = true;
            }
            db.claim(s.uuid, s.token);
            return System.nanoTime();
          } catch (Exception e) {
            if (r) redis.release(s.uuid, s.token);
            throw e;
          }
        },
        time -> {
          s.claimed = true;
          s.lastLease = time;
          loadRound(s);
        });
  }

  private Set<ResourceKey> discover(Session s) throws Exception {
    Set<ResourceKey> all = new TreeSet<>();
    for (SyncModule m : modules.values()) all.addAll(m.discover(s.player, s.owned));
    return all;
  }

  private Map<ResourceKey, byte[]> capture(Session s, Set<ResourceKey> keys) throws Exception {
    Map<ResourceKey, byte[]> all = new TreeMap<>();
    for (SyncModule m : modules.values()) {
      Set<ResourceKey> subset = filter(keys, m.id());
      if (!subset.isEmpty()) {
        var captured = m.capture(s.player, subset);
        if (!captured.keySet().equals(subset))
          throw new IllegalStateException(
              "Module capture returned incorrect resource keys: " + m.id());
        m.validate(s.player, captured);
        all.putAll(captured);
      }
    }
    return all;
  }

  private static Set<ResourceKey> filter(Collection<ResourceKey> keys, String module) {
    Set<ResourceKey> result = new TreeSet<>();
    for (ResourceKey k : keys) if (k.module().equals(module)) result.add(k);
    return result;
  }

  private Map<String, Map<ResourceKey, byte[]>> split(
      Map<ResourceKey, byte[]> data, Collection<String> selected) {
    Map<String, Map<ResourceKey, byte[]>> result = new TreeMap<>();
    for (String id : selected) {
      Map<ResourceKey, byte[]> values = new TreeMap<>();
      data.forEach(
          (k, v) -> {
            if (k.module().equals(id)) values.put(k, v);
          });
      result.put(id, values);
    }
    return result;
  }

  private void validateApply(Session s, Map<ResourceKey, byte[]> data) throws Exception {
    var groups =
        split(
            data,
            data.keySet().stream()
                .map(ResourceKey::module)
                .collect(java.util.stream.Collectors.toSet()));
    for (var e : groups.entrySet()) modules.get(e.getKey()).validate(s.player, e.getValue());
    // No fallback to empty state. A partial apply remains protected and is never automatically
    // saved.
    for (var e : groups.entrySet()) modules.get(e.getKey()).apply(s.player, e.getValue());
  }

  private void loadRound(Session s) throws Exception {
    if (s.closing) return;
    if (++s.rounds > config.maxDepth() + 3)
      throw new IllegalStateException("Backpack graph did not converge");
    Set<ResourceKey> needed = discover(s);
    Set<ResourceKey> extra = new TreeSet<>(needed);
    extra.removeAll(s.owned);
    if (extra.isEmpty()) {
      Set<ResourceKey> stale = new TreeSet<>(s.owned);
      stale.removeAll(needed);
      s.owned = needed;
      Map<ResourceKey, byte[]> current = capture(s, needed);
      var groups = split(current, modules.keySet());
      async(
          s,
          () -> {
            db.save(s.uuid, s.token, groups, "load", stale);
            return null;
          },
          v -> {
            if (!s.closing) {
              s.state = State.READY;
              s.lastSave = System.nanoTime();
              for (SyncModule module : modules.values()) module.activated(s.player);
              s.player.containerMenu.broadcastFullState();
            }
          });
      return;
    }
    async(
        s,
        () -> db.acquire(s.uuid, s.token, extra),
        data -> {
          s.owned.addAll(extra);
          Set<ResourceKey> missing = new TreeSet<>(extra);
          missing.removeAll(data.keySet());
          if (!missing.isEmpty()) {
            if (!config.importLocal())
              throw new IllegalStateException(
                  "No database snapshot; local import is disabled: " + missing);
            data.putAll(capture(s, missing));
          }
          validateApply(s, data);
          loadRound(s);
        });
  }

  /**
   * Invoked before gameplay packets AND before player ticking so new bag contents cannot be used
   * before loading.
   */
  public boolean beforeAction(ServerPlayer p) {
    if (!active() || p instanceof FakePlayer) return true;
    Session s = session(p.getUUID());
    if (s == null || s.player != p || s.closing) return false;
    if (s.state == State.BYPASS) return true;
    if (s.state != State.READY) return false;
    if (!server.isSameThread()) return true;
    try {
      if (seconds(s.lastLease) >= config.lease() / 2)
        throw new IllegalStateException("Lease confirmation overdue");
      return checkResources(s);
    } catch (Exception e) {
      fail(s, e);
      return false;
    }
  }

  public void containerOpened(
      ServerPlayer p, net.minecraft.world.inventory.AbstractContainerMenu menu) {
    if (!active() || p instanceof FakePlayer) return;
    if (!beforeAction(p)) {
      p.closeContainer();
      return;
    }
    Session s = session(p.getUUID());
    if (s == null || s.state != State.READY || p.containerMenu != menu) return;
    try {
      for (SyncModule module : modules.values()) module.containerOpened(p, menu);
    } catch (Exception e) {
      p.closeContainer();
      fail(s, e);
    }
  }

  public void externalPlayerLoad(ServerPlayer p) {
    Session s = session(p.getUUID());
    if (s != null
        && s.player == p
        && s.state != State.WAITING
        && s.state != State.BYPASS
        && s.state != State.ERROR)
      fail(
          s,
          new IllegalStateException(
              "Another plugin loaded full player NBT after synchronization started; wait for it to"
                  + " finish, then /ums retry"));
  }

  public void operationFailed(Session s, Exception e) {
    if (!s.busy && (s.state == State.LOADING || s.state == State.SAVING)) fail(s, e);
  }

  private boolean checkResources(Session s) throws Exception {
    if (s.state != State.READY) return false;
    Set<ResourceKey> current = discover(s);
    if (current.equals(s.owned)) return true;
    if (s.busy) {
      // Only background autosave may hold IO while READY. Block use of new resources now;
      // its completion will reconcile ownership before another database operation starts.
      freeze(s, State.SAVING);
      return false;
    }
    freeze(s, State.LOADING);
    s.joined = System.nanoTime();
    s.rounds = 0;
    Set<ResourceKey> removed = new TreeSet<>(s.owned);
    removed.removeAll(current);
    Map<ResourceKey, byte[]> previous = capture(s, s.owned);
    var data = split(previous, modules.keySet());
    async(
        s,
        () -> {
          db.save(s.uuid, s.token, data, "transfer", removed);
          return null;
        },
        v -> {
          s.owned.removeAll(removed);
          loadRound(s);
        });
    return false;
  }

  private void autosave(Session s) throws Exception {
    // Capture detached byte arrays on the server thread. The player may keep using the same
    // leased resources while this point-in-time snapshot is written. Do not apply it back.
    backgroundSave(s, split(capture(s, s.owned), modules.keySet()), "autosave");
  }

  /** Main-thread hook after a successful whole-server world-save call. */
  public void worldSaved() {
    if (!active() || stopping || !config.worldSave()) return;
    if (!server.isSameThread())
      throw new IllegalStateException("World save must run on server thread");
    for (Session s : List.copyOf(sessions.values())) {
      if (s.closing || s.state != State.READY) continue;
      try {
        if (seconds(s.lastLease) >= config.lease() / 2)
          throw new IllegalStateException("Lease confirmation overdue");
        if (!checkResources(s)) continue;
        var snapshot = split(capture(s, s.owned), modules.keySet());
        // At most one queued world checkpoint per player; later world saves supersede it.
        if (s.busy) s.queuedWorldSave = snapshot;
        else backgroundSave(s, snapshot, "world-save");
      } catch (Exception e) {
        fail(s, e);
      }
    }
  }

  private void backgroundSave(
      Session s, Map<String, Map<ResourceKey, byte[]>> data, String reason) {
    async(
        s,
        () -> {
          db.save(s.uuid, s.token, data, reason, Set.of());
          return null;
        },
        v -> {
          s.lastSave = System.nanoTime();
          if (s.state == State.SAVING) s.state = State.READY;
          // async's generation fence prevents a failed session from being reactivated here.
          // Logout waits for busy=false and captures the latest data before releasing leases.
          if (s.closing) s.queuedWorldSave = null;
          else if (checkResources(s) && !s.busy && s.queuedWorldSave != null) {
            var queued = s.queuedWorldSave;
            s.queuedWorldSave = null;
            backgroundSave(s, queued, "world-save");
          }
        });
  }

  public void save(Session s, String selected, String reason, Consumer<String> reply)
      throws Exception {
    if (s.state != State.READY || s.busy || !checkResources(s))
      throw new IllegalStateException("Player is not READY");
    Set<String> ids = select(selected);
    freeze(s, State.SAVING);
    Set<ResourceKey> keys = new TreeSet<>();
    ids.forEach(id -> keys.addAll(filter(s.owned, id)));
    var data = split(capture(s, keys), ids);
    async(
        s,
        () -> {
          db.save(s.uuid, s.token, data, reason, Set.of());
          return null;
        },
        v -> {
          s.state = State.READY;
          s.lastSave = System.nanoTime();
          if (reply != null) reply.accept("已保存 " + s.player.getScoreboardName() + " / " + selected);
        });
  }

  private Set<String> select(String id) {
    if (id.equals("all")) return moduleIds();
    if (!modules.containsKey(id))
      throw new IllegalArgumentException("Unknown/unavailable module: " + id);
    return Set.of(id);
  }

  public void rollback(Session s, String selected, int index, Consumer<String> reply)
      throws Exception {
    if (s.busy || !s.claimed || s.released || s.closing || s.state == State.BYPASS)
      throw new IllegalStateException("Need an owned, idle session; use retry first");
    boolean healthy = s.state == State.READY;
    Set<String> ids = select(selected);
    Set<ResourceKey> actual = new TreeSet<>();
    for (SyncModule m : modules.values()) actual.addAll(m.discover(s.player));
    if (healthy && !actual.equals(s.owned))
      throw new IllegalStateException("Inventory ownership changed; use retry first");
    Set<ResourceKey> expected = new TreeSet<>();
    ids.forEach(id -> expected.addAll(filter(actual, id)));
    freeze(s, State.SAVING);
    async(
        s,
        () -> {
          Map<ResourceKey, byte[]> target = db.backupBundle(s.uuid, ids, index);
          if (!target.keySet().equals(expected))
            throw new IllegalStateException(
                "Backup UUIDs differ from current inventory; refusing unsafe rollback");
          db.acquireForRestore(s.uuid, s.token, expected);
          return target;
        },
        target -> {
          s.owned.addAll(expected);
          var groups = split(target, ids);
          for (var e : groups.entrySet()) modules.get(e.getKey()).validate(s.player, e.getValue());
          Map<String, Map<ResourceKey, byte[]>> before =
              healthy ? split(capture(s, expected), ids) : Map.of();
          async(
              s,
              () -> {
                if (healthy) db.save(s.uuid, s.token, before, "before-rollback", Set.of());
                return null;
              },
              v -> {
                validateApply(s, target);
                async(
                    s,
                    () -> {
                      db.save(s.uuid, s.token, groups, "rollback", Set.of());
                      return null;
                    },
                    done -> {
                      reply.accept(
                          "已回档 "
                              + s.player.getScoreboardName()
                              + " / "
                              + selected
                              + " / #"
                              + index);
                      if (healthy) {
                        s.state = State.READY;
                        s.lastSave = System.nanoTime();
                        for (SyncModule module : modules.values()) module.activated(s.player);
                      } else retry(s, reply);
                    });
              });
        });
  }

  public void history(Session s, String selected, Consumer<String> reply) {
    Set<String> ids = select(selected);
    workers.submit(
        () -> {
          try {
            List<String> lines = db.bundleHistory(s.uuid, ids);
            server.execute(() -> lines.forEach(reply));
          } catch (Exception e) {
            server.execute(() -> reply.accept("查询失败：" + brief(e)));
          }
        });
  }

  public void retry(Session s, Consumer<String> reply) {
    if (s.busy || s.renewing) throw new IllegalStateException("Operation still running");
    freeze(s, State.LOADING);
    s.generation++;
    async(
        s,
        () -> {
          releaseNow(s);
          db.initialize();
          if (redis != null) redis.ping();
          return null;
        },
        v -> {
          Session next = new Session(s.player);
          sessions.put(s.uuid, next);
          initialized = true;
          if (startupError != null && startupError.startsWith("Database")) startupError = null;
          reply.accept("已重新排队同步 " + s.player.getScoreboardName());
        });
  }

  public void releaseProtection(Session s, Consumer<String> reply) {
    if (s.busy || s.renewing) throw new IllegalStateException("Operation still running");
    s.generation++;
    s.state = State.BYPASS;
    s.released = true;
    workers.submit(
        () -> {
          try {
            synchronized (s.ioLock) {
              releaseNow(s);
            }
          } catch (Exception e) {
            UnifiedSync.LOG.warn("Lease cleanup failed for {}", s.uuid);
          }
        });
    reply.accept("已解除保护：" + s.player.getScoreboardName() + "。该会话自动保存已停用；使用 /ums retry 恢复同步。");
  }

  private void fail(Session s, Exception e) {
    if (s.state == State.BYPASS) return;
    s.generation++;
    s.queuedWorldSave = null;
    s.state = State.ERROR;
    s.error = brief(e);
    s.anchor = s.player.position();
    UnifiedSync.LOG.error("Sync failed for {}: {}", s.uuid, s.error, e);
    if (s.player.connection != null)
      s.player.sendSystemMessage(Component.literal("数据同步异常，已进入保护状态，请联系服务器管理员。"));
  }

  private static String brief(Throwable e) {
    while (e.getCause() != null) e = e.getCause();
    String m = e.getMessage();
    return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
  }

  private void releaseNow(Session s) throws Exception {
    db.release(s.uuid, s.token);
    if (redis != null) redis.release(s.uuid, s.token);
  }

  private void releaseAbandoned(Session s) {
    workers.submit(
        () -> {
          try {
            synchronized (s.ioLock) {
              releaseNow(s);
            }
          } catch (Exception e) {
            UnifiedSync.LOG.warn("Old session lease cleanup failed for {}", s.uuid);
          }
        });
  }

  private void finishLogout(Session s) {
    State previous = s.state;
    s.state = State.CLOSING;
    s.busy = true;
    Map<String, Map<ResourceKey, byte[]>> data = null;
    if (previous == State.READY && s.claimed && !s.released)
      try {
        data = split(capture(s, s.owned), modules.keySet());
      } catch (Exception e) {
        UnifiedSync.LOG.error("Logout capture failed for {}", s.uuid, e);
      }
    for (SyncModule module : modules.values())
      try {
        module.detached(s.player);
      } catch (Exception e) {
        UnifiedSync.LOG.error("Module cleanup failed", e);
      }
    Map<String, Map<ResourceKey, byte[]>> captured = data;
    s.pending =
        CompletableFuture.runAsync(
            () -> {
              try {
                synchronized (s.ioLock) {
                  if (captured != null) db.save(s.uuid, s.token, captured, "logout", Set.of());
                  releaseNow(s);
                }
              } catch (Exception e) {
                recovery(s, captured);
                UnifiedSync.LOG.error("Logout save failed for {}; lease will expire", s.uuid, e);
              } finally {
                server.execute(
                    () -> {
                      if (stopping) return;
                      sessions.remove(s.uuid, s);
                      ServerPlayer next = waitingJoins.remove(s.uuid);
                      if (next != null && next.connection.isAcceptingMessages())
                        sessions.put(s.uuid, new Session(next));
                    });
              }
            },
            workers);
  }

  private void recovery(Session s, Map<String, Map<ResourceKey, byte[]>> data) {
    if (data == null) return;
    try {
      Path dir = server.getWorldPath(LevelResource.ROOT).resolve("unifiedsync-recovery");
      Files.createDirectories(dir);
      Map<ResourceKey, byte[]> all = new TreeMap<>();
      data.values().forEach(all::putAll);
      Files.write(
          dir.resolve(s.uuid + "-" + System.currentTimeMillis() + ".ums"),
          SnapshotCodec.encode(all),
          StandardOpenOption.CREATE_NEW);
    } catch (Exception e) {
      UnifiedSync.LOG.error("Recovery file failed for {}", s.uuid, e);
    }
  }

  public void close() {
    stopping = true;
    List<CompletableFuture<?>> shutdown = new ArrayList<>();
    for (Session s : List.copyOf(sessions.values())) {
      Map<String, Map<ResourceKey, byte[]>> data = null;
      if (s.state == State.READY && s.claimed && !s.released)
        try {
          data = split(capture(s, s.owned), modules.keySet());
        } catch (Exception e) {
          UnifiedSync.LOG.error("Shutdown capture failed", e);
        }
      s.closing = true;
      s.generation++;
      s.state = State.CLOSING;
      final var snapshot = data;
      shutdown.add(
          CompletableFuture.allOf(s.pending, s.heartbeat)
              .handle((v, e) -> null)
              .thenRunAsync(
                  () -> {
                    try {
                      synchronized (s.ioLock) {
                        if (snapshot != null)
                          db.save(s.uuid, s.token, snapshot, "shutdown", Set.of());
                        releaseNow(s);
                      }
                    } catch (Exception e) {
                      recovery(s, snapshot);
                      UnifiedSync.LOG.error("Shutdown save failed for {}", s.uuid, e);
                    }
                  },
                  workers));
    }
    try {
      CompletableFuture.allOf(shutdown.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
    } catch (Exception e) {
      UnifiedSync.LOG.error(
          "Shutdown IO timeout; review recovery files and wait for leases to expire", e);
    }
    workers.shutdown();
  }
}
