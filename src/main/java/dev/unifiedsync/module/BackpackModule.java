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
  private final Map<UUID, Set<Object>> wrappers = new HashMap<>();

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
    Set<ResourceKey> result = new TreeSet<>();
    Set<ItemStack> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    var proxy =
        Proxy.newProxyInstance(
            consumer.getClassLoader(),
            new Class<?>[] {consumer},
            (o, m, a) -> {
              if (m.getName().equals("accept")) {
                scan((ItemStack) a[0], 0, result, visited, new HashSet<>(), loaded);
                return false;
              }
              return null;
            });
    run.invoke(provider.invoke(null), p, proxy);
    for (int i = 0; i < p.getInventory().getContainerSize(); i++)
      scan(p.getInventory().getItem(i), 0, result, visited, new HashSet<>(), loaded);
    for (int i = 0; i < p.getEnderChestInventory().getContainerSize(); i++)
      scan(p.getEnderChestInventory().getItem(i), 0, result, visited, new HashSet<>(), loaded);
    scan(p.containerMenu.getCarried(), 0, result, visited, new HashSet<>(), loaded);
    return result;
  }

  @SuppressWarnings("unchecked")
  private void scan(
      ItemStack stack,
      int depth,
      Set<ResourceKey> keys,
      Set<ItemStack> visited,
      Set<UUID> path,
      Set<ResourceKey> loaded)
      throws Exception {
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
      var key = new ResourceKey(id(), id.toString());
      // Same stack exposed by multiple providers is filtered above; distinct shells sharing an UUID
      // are suspicious.
      if (!keys.add(key)) throw new IllegalStateException("Duplicate backpack UUID " + id);
      if (keys.size() > config.maxBags()) throw new IllegalStateException("Too many backpacks");
      wrappers.computeIfAbsent(id, k -> Collections.newSetFromMap(new WeakHashMap<>())).add(w);
      if ((loaded == null || loaded.contains(key)) && local(id).isPresent()) {
        IItemHandler inv = (IItemHandler) handler.invoke(w);
        for (int i = 0; i < inv.getSlots(); i++)
          scan(inv.getStackInSlot(i), depth + 1, keys, visited, path, loaded);
      }
      path.remove(id);
    } else {
      ItemContainerContents c = stack.get(DataComponents.CONTAINER);
      if (c != null)
        for (ItemStack child : c.nonEmptyItems())
          scan(child, depth + 1, keys, visited, path, loaded);
    }
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
      for (Object wrapper : wrappers.getOrDefault(id, Set.of())) refresh.invoke(wrapper);
    }
  }
}
