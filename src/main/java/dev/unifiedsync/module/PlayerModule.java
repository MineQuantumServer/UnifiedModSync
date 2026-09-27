package dev.unifiedsync.module;

import dev.unifiedsync.api.*;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;

public abstract class PlayerModule implements SyncModule {
  public ResourceKey key(ServerPlayer p) {
    return new ResourceKey(id(), p.getUUID().toString());
  }

  public Set<ResourceKey> discover(ServerPlayer p) {
    return Set.of(key(p));
  }

  protected abstract byte[] capture(ServerPlayer p) throws Exception;

  protected abstract void validate(ServerPlayer p, byte[] bytes) throws Exception;

  protected abstract void apply(ServerPlayer p, byte[] bytes) throws Exception;

  public Map<ResourceKey, byte[]> capture(ServerPlayer p, Set<ResourceKey> keys) throws Exception {
    if (!keys.equals(discover(p))) throw new IllegalStateException("Wrong player resource");
    return Map.of(key(p), capture(p));
  }

  public void validate(ServerPlayer p, Map<ResourceKey, byte[]> data) throws Exception {
    if (!data.keySet().equals(discover(p)))
      throw new IllegalStateException("Snapshot belongs to a different player/module");
    validate(p, data.get(key(p)));
  }

  public void apply(ServerPlayer p, Map<ResourceKey, byte[]> data) throws Exception {
    validate(p, data);
    apply(p, data.get(key(p)));
  }
}
