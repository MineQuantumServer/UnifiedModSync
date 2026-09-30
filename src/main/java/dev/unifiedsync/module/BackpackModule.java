package dev.unifiedsync.module;

import dev.unifiedsync.*;
import dev.unifiedsync.api.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.items.IItemHandler;

public final class BackpackModule implements SyncModule {
  private com.mojang.serialization.Codec<ItemStack> oversizedCodec;
  private final Config config;
  private Class<?> item, consumer;
  private Method from, uuid, handler, refresh, getStorage, getContents, setContents, provider, run;
  private Method upgrades, renderUpgrades, firstUpgradeSlot;
  private Class<?> backpackMenu, storageMenu;
  private Field upgradeSlots;
  private final Map<UUID, ScanContext> scans = new HashMap<>();

  // Server-thread confined. Reuse scratch collections, never reuse a scan's observations.
  private final class ScanContext {
    final Set<ResourceKey> keys = new HashSet<>();
    final Set<ItemStack> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<UUID> path = new HashSet<>();
    final Map<UUID, ResourceKey> resources = new HashMap<>();
    final Map<UUID, Set<Object>> wrappers = new HashMap<>();
    Set<ResourceKey> loaded, result = Set.of();
    final Object callback =
        Proxy.newProxyInstance(
            consumer.getClassLoader(),
            new Class<?>[] {consumer},
            (o, m, a) -> {
              if (m.getName().equals("accept")) {
                scan((ItemStack) a[0], 0, this);
                return false;
              }
              return null;
            });
  }

  public BackpackModule(Config config) {
    this.config = config;
  }

  public String id() {
    return "backpacks";
  }

  public String requiredMod() {
    return "sophisticatedbackpacks";
  }

  public void verify() throws Exception {
    oversizedCodec =
        (com.mojang.serialization.Codec<ItemStack>)
            Class.forName("net.p3pp3rf1y.sophisticatedcore.util.CodecHelper")
                .getField("OVERSIZED_ITEM_STACK_CODEC")
                .get(null);
    String b = "net.p3pp3rf1y.sophisticatedbackpacks.";
    item = Class.forName(b + "backpack.BackpackItem");
    Class<?> w = Class.forName(b + "backpack.wrapper.BackpackWrapper"),
        s = Class.forName(b + "backpack.BackpackStorage"),
        p = Class.forName(b + "util.PlayerInventoryProvider");
    from = w.getMethod("fromStack", ItemStack.class);
    uuid = w.getMethod("getContentsUuid");
    handler = w.getMethod("getInventoryHandler");
    refresh = w.getMethod("onContentsNbtUpdated");
    upgrades = w.getMethod("getUpgradeHandler");
    renderUpgrades =
        Class.forName("net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeHandler")
            .getMethod("setRenderUpgradeItems");
    backpackMenu = Class.forName(b + "common.gui.BackpackContainer");
    storageMenu =
        Class.forName("net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase");
    firstUpgradeSlot = backpackMenu.getMethod("getFirstUpgradeSlot");
    upgradeSlots = backpackMenu.getField("upgradeSlots");
    getStorage = s.getMethod("get");
    getContents = s.getMethod("getBackpackContents", UUID.class);
    setContents = s.getMethod("setBackpackContents", UUID.class, CompoundTag.class);
    provider = p.getMethod("get");
    consumer = Class.forName(b + "util.PlayerInventoryProvider$BackpackInventorySlotConsumer");
    run = p.getMethod("runOnBackpacks", Player.class, consumer);
  }

  @SuppressWarnings("unchecked")
  private Optional<CompoundTag> local(UUID id) throws Exception {
    return (Optional<CompoundTag>) getContents.invoke(getStorage.invoke(null), id);
  }

  public Set<ResourceKey> discover(ServerPlayer p) throws Exception {
    return discover(p, null);
  }

  public Set<ResourceKey> discover(ServerPlayer p, Set<ResourceKey> loaded) throws Exception {
    ScanContext c = scans.computeIfAbsent(p.getUUID(), ignored -> new ScanContext());
    c.loaded = loaded;
    try {
      run.invoke(provider.invoke(null), p, c.callback);
      for (int i = 0; i < p.getInventory().getContainerSize(); i++)
        scan(p.getInventory().getItem(i), 0, c);
      for (int i = 0; i < p.getEnderChestInventory().getContainerSize(); i++)
        scan(p.getEnderChestInventory().getItem(i), 0, c);
      scan(p.containerMenu.getCarried(), 0, c);
      if (!c.result.equals(c.keys)) c.result = Set.copyOf(c.keys);
      c.resources.values().retainAll(c.keys);
      c.wrappers.keySet().retainAll(c.resources.keySet());
      return c.result;
    } finally {
      c.keys.clear();
      c.visited.clear();
      c.path.clear();
      c.loaded = null;
    }
  }

  @Override
  public void detached(ServerPlayer p) {
    scans.remove(p.getUUID());
  }

  @SuppressWarnings("unchecked")
  private void scan(ItemStack stack, int depth, ScanContext c) throws Exception {
    Set<ResourceKey> keys = c.keys;
    Set<ItemStack> visited = c.visited;
    Set<UUID> path = c.path;
    Set<ResourceKey> loaded = c.loaded;
    if (stack.isEmpty() || !visited.add(stack)) return;
    if (depth > config.maxDepth())
      throw new IllegalStateException("Backpack nesting exceeds configured limit");
    if (item.isInstance(stack.getItem())) {
      Object w = from.invoke(null, stack);
      Optional<UUID> opt = (Optional<UUID>) uuid.invoke(w);
      // A genuinely new, uninitialised shell is empty; initialise it so it can receive a lease
      // before use.
      if (opt.isEmpty()) {
        handler.invoke(w);
        opt = (Optional<UUID>) uuid.invoke(w);
      }
      if (opt.isEmpty()) throw new IllegalStateException("Backpack has no contents UUID");
      UUID id = opt.get();
      if (!path.add(id)) throw new IllegalStateException("Cyclic nested backpack UUID " + id);
      var key = c.resources.computeIfAbsent(id, value -> new ResourceKey(id(), value.toString()));
      // Same stack exposed by multiple providers is filtered above; distinct shells sharing an UUID
      // are suspicious.
      if (!keys.add(key)) throw new IllegalStateException("Duplicate backpack UUID " + id);
      if (keys.size() > config.maxBags()) throw new IllegalStateException("Too many backpacks");
      c.wrappers.computeIfAbsent(id, k -> Collections.newSetFromMap(new WeakHashMap<>())).add(w);
      if ((loaded == null || loaded.contains(key)) && local(id).isPresent()) {
        IItemHandler inv = (IItemHandler) handler.invoke(w);
        for (int i = 0; i < inv.getSlots(); i++) scan(inv.getStackInSlot(i), depth + 1, c);
      }
      path.remove(id);
    } else {
      ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
      if (contents != null)
        for (ItemStack child : contents.nonEmptyItems()) scan(child, depth + 1, c);
    }
  }

  @Override
  public void containerOpened(
      ServerPlayer p, net.minecraft.world.inventory.AbstractContainerMenu menu) throws Exception {
    if (!backpackMenu.isInstance(menu)) return;
    // Upgrade slots live outside vanilla menu.slots. Send explicit updates after menu init so
    // hybrid-server inventory synchronization cannot omit them from the initial contents packet.
    int first = (int) firstUpgradeSlot.invoke(menu);
    var slots = (java.util.List<net.minecraft.world.inventory.Slot>) upgradeSlots.get(menu);
    for (int i = 0; i < slots.size(); i++) {
      p.connection.send(
          new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
              menu.containerId, menu.incrementStateId(), first + i, slots.get(i).getItem().copy()));
    }
  }

  @Override
  public void activated(ServerPlayer p) {
    p.inventoryMenu.broadcastFullState();
  }

  @Override
  public boolean canKeepContainerOpenDuringTransfer(
      ServerPlayer p, net.minecraft.world.inventory.AbstractContainerMenu menu) {
    // Ordinary container slots hold backpack shells, not their contents handler. Sophisticated
    // menus retain inventory/upgrade handlers that onContentsNbtUpdated may invalidate.
    return !storageMenu.isInstance(menu);
  }

  public Map<ResourceKey, byte[]> capture(ServerPlayer p, Set<ResourceKey> keys) throws Exception {
    Map<ResourceKey, byte[]> result = new TreeMap<>();
    for (ResourceKey k : keys) {
      if (!k.module().equals(id())) throw new IllegalStateException("Wrong module");
      result.put(
          k,
          NbtBytes.encode(
              local(UUID.fromString(k.id()))
                  .orElseThrow(
                      () -> new IllegalStateException("Missing local backpack data: " + k.id()))));
    }
    return result;
  }

  public void validate(ServerPlayer p, Map<ResourceKey, byte[]> data) throws Exception {
    if (data.size() > config.maxBags())
      throw new IllegalStateException("Too many backpack records");
    for (var e : data.entrySet()) {
      if (!e.getKey().module().equals(id())) throw new IllegalStateException("Wrong module");
      UUID.fromString(e.getKey().id());
      CompoundTag root = NbtBytes.decode(e.getValue());
      for (String name : java.util.List.of("inventory", "upgradeInventory")) {
        if (!root.contains(name)) continue;
        if (!(root.get(name) instanceof CompoundTag inv))
          throw new IllegalStateException("Invalid backpack " + name);
        int size = inv.contains("Size", Tag.TAG_INT) ? inv.getInt("Size") : 0;
        ItemNbtValidation.inventory(
            p, inv, size, name.equals("inventory") ? oversizedCodec : ItemStack.CODEC);
      }
    }
  }

  public void apply(ServerPlayer p, Map<ResourceKey, byte[]> data) throws Exception {
    validate(p, data);
    Object storage = getStorage.invoke(null);
    for (var e : data.entrySet()) {
      UUID id = UUID.fromString(e.getKey().id());
      CompoundTag replacement = NbtBytes.decode(e.getValue());
      Optional<CompoundTag> previous = local(id);
      if (previous.isPresent()) {
        CompoundTag existing = previous.get();
        for (String key : new HashSet<>(existing.getAllKeys())) existing.remove(key);
        existing.merge(replacement);
      } else setContents.invoke(storage, id, replacement);
      ((SavedData) storage).setDirty();
      for (ScanContext context : scans.values()) {
        for (Object wrapper : context.wrappers.getOrDefault(id, Set.of())) {
          refresh.invoke(wrapper);
          // Loading NBT does not fire onContentsChanged. Rebuild the shell's upgrade render data
          // explicitly, including when slot count is unchanged, before the inventory full sync.
          renderUpgrades.invoke(upgrades.invoke(wrapper));
        }
      }
    }
  }
}
