package dev.unifiedsync.api;

import java.util.*;
import net.minecraft.server.level.ServerPlayer;

/** All methods run on the SERVER THREAD. Byte arrays cross to database workers. */
public interface SyncModule {
  String id();

  String requiredMod();

  void verify() throws Exception;

  /** Discover authoritative resource IDs without changing their content. */
  Set<ResourceKey> discover(ServerPlayer player) throws Exception;

  /** Resolve nested references only after their parent resource has loaded. */
  default Set<ResourceKey> discover(ServerPlayer player, Set<ResourceKey> loaded) throws Exception {
    return discover(player);
  }

  /** Snapshot must fail for missing/corrupt data; never substitute an empty payload. */
  Map<ResourceKey, byte[]> capture(ServerPlayer player, Set<ResourceKey> keys) throws Exception;

  void validate(ServerPlayer player, Map<ResourceKey, byte[]> data) throws Exception;

  void apply(ServerPlayer player, Map<ResourceKey, byte[]> data) throws Exception;

  /** Called after protection is lifted, so client refresh requests are no longer blocked. */
  default void activated(ServerPlayer player) throws Exception {}

  /** Called after the server opens a menu, with a ready and lease-checked session. */
  default void containerOpened(
      ServerPlayer player, net.minecraft.world.inventory.AbstractContainerMenu menu)
      throws Exception {}

  /**
   * Opt in only when applying transferred resources cannot invalidate this menu's item handlers.
   * The menu stays visible, but gameplay remains blocked until transfer completes. Called only when
   * this module's resource IDs change. Unknown modules default to closing.
   */
  default boolean canKeepContainerOpenDuringTransfer(
      ServerPlayer player, net.minecraft.world.inventory.AbstractContainerMenu menu) {
    return false;
  }

  default void detached(ServerPlayer player) throws Exception {}
}
