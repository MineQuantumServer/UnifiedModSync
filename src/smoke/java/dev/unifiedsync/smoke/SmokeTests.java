package dev.unifiedsync.smoke;

import com.mojang.authlib.GameProfile;
import dev.unifiedsync.*;
import dev.unifiedsync.api.*;
import dev.unifiedsync.module.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.network.*;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

@Mod("ums_smoke")
@GameTestHolder("ums_smoke")
@PrefixGameTestTemplate(false)
public class SmokeTests {
  private static ServerPlayer player(GameTestHelper h, String name) {
    return player(h, name, new ArrayList<>());
  }

  private static ServerPlayer player(GameTestHelper h, String name, List<Packet<?>> sent) {
    var profile = new GameProfile(UUID.randomUUID(), name);
    var p =
        new ServerPlayer(
            h.getLevel().getServer(), h.getLevel(), profile, ClientInformation.createDefault());
    Connection c =
        new Connection(PacketFlow.SERVERBOUND) {
          @Override
          public boolean isConnected() {
            return true;
          }
        };
    p.connection =
        new ServerGamePacketListenerImpl(
            h.getLevel().getServer(), c, p, CommonListenerCookie.createInitial(profile, false)) {
          @Override
          public void send(Packet<?> packet) {
            sent.add(packet);
          }

          @Override
          public void send(Packet<?> packet, PacketSendListener listener) {
            sent.add(packet);
          }
        };
    return p;
  }

  @GameTest(template = "empty", timeoutTicks = 4000)
  public static void moduleRoundtripAndProtection(GameTestHelper h) throws Exception {
    List<Packet<?>> sent = new ArrayList<>();
    ServerPlayer p = player(h, "ModuleTest", sent);
    Coordinator service = UnifiedSync.service();
    h.assertTrue(
        service != null && service.moduleIds().size() == 3,
        "All three modules must verify against real mod jars");
    var aw = new WardrobeModule();
    aw.verify();
    var keys = aw.discover(p);
    var wardrobe = aw.capture(p, keys);
    aw.validate(p, wardrobe);
    aw.apply(p, wardrobe);
    h.assertTrue(
        Arrays.equals(
            wardrobe.values().iterator().next(), aw.capture(p, keys).values().iterator().next()),
        "Wardrobe roundtrip changed data");
    var astral = new AstralModule();
    astral.verify();
    var research = astral.capture(p, astral.discover(p));
    astral.apply(p, research);
    h.assertTrue(
        Arrays.equals(
            research.values().iterator().next(),
            astral.capture(p, astral.discover(p)).values().iterator().next()),
        "Astral full codec roundtrip changed data");
    ItemStack stack =
        new ItemStack(
            BuiltInRegistries.ITEM.get(ResourceLocation.parse("sophisticatedbackpacks:backpack")));
    p.getInventory().setItem(0, stack);
    Class<?> wrapper =
        Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
    Object w = wrapper.getMethod("fromStack", ItemStack.class).invoke(null, stack);
    var inv = (IItemHandlerModifiable) wrapper.getMethod("getInventoryHandler").invoke(w);
    inv.setStackInSlot(0, new ItemStack(Items.DIAMOND, 7));
    BackpackModule bags = new BackpackModule(service.config);
    bags.verify();
    Set<ResourceKey> ids = bags.discover(p);
    h.assertTrue(ids.size() == 1, "Discover one UUID");
    var allocationBean =
        (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    for (int i = 0; i < 2000; i++) bags.discover(p, ids);
    long allocatedBefore =
        allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
    for (int i = 0; i < 10000; i++) bags.discover(p, ids);
    long allocated =
        allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocatedBefore;
    System.out.println("UMS_DISCOVER_BENCH bytes/scan=" + allocated / 10000);
    Item upgradeItem =
        BuiltInRegistries.ITEM.get(ResourceLocation.parse("sophisticatedbackpacks:pickup_upgrade"));
    h.assertTrue(upgradeItem != Items.AIR, "Upgrade test item missing");
    var upgradeHandler = (IItemHandlerModifiable) wrapper.getMethod("getUpgradeHandler").invoke(w);
    upgradeHandler.setStackInSlot(0, new ItemStack(upgradeItem));
    var saved = bags.capture(p, ids);
    upgradeHandler.setStackInSlot(0, ItemStack.EMPTY);
    // Same slot count, stale empty render metadata: mirrors a shell restored by inventory sync.
    bags.apply(p, saved);
    Object renderInfo = wrapper.getMethod("getRenderInfo").invoke(w);
    var renderedUpgrades =
        (java.util.List<ItemStack>)
            renderInfo.getClass().getMethod("getUpgradeItems").invoke(renderInfo);
    h.assertTrue(
        !renderedUpgrades.isEmpty() && renderedUpgrades.get(0).is(upgradeItem),
        "Loaded upgrades must refresh render metadata without a click");
    var restoredUpgrades =
        (IItemHandlerModifiable) wrapper.getMethod("getUpgradeHandler").invoke(w);
    h.assertTrue(
        restoredUpgrades.getStackInSlot(0).is(upgradeItem), "Upgrade contents not restored");
    // Repeated checks within one tick must still detect a new duplicate shell.
    p.getInventory().setItem(1, stack.copy());
    boolean duplicateRejected = false;
    try {
      bags.discover(p, ids);
    } catch (Exception expected) {
      duplicateRejected = true;
    }
    h.assertTrue(duplicateRejected, "Cached discovery missed a same-tick duplicate");
    p.getInventory().setItem(1, ItemStack.EMPTY);
    h.assertTrue(bags.discover(p, ids).equals(ids), "Failed scan polluted scratch state");
    bags.detached(p);
    h.assertTrue(bags.discover(p, ids).equals(ids), "Detached player cannot rediscover bags");
    CompoundTag invalid = NbtBytes.decode(saved.values().iterator().next());
    invalid
        .getCompound("inventory")
        .getList("Items", Tag.TAG_COMPOUND)
        .getCompound(0)
        .putString("id", "unifiedsync:missing_item_for_test");
    boolean rejected = false;
    try {
      bags.validate(p, Map.of(ids.iterator().next(), NbtBytes.encode(invalid)));
    } catch (Exception expected) {
      rejected = true;
    }
    h.assertTrue(rejected, "Unknown stored item must not silently become air");
    inv.setStackInSlot(0, new ItemStack(Items.DIRT, 3));
    Class<?> storage =
        Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage");
    Object st = storage.getMethod("get").invoke(null);
    CompoundTag contents =
        (CompoundTag)
            ((Optional<?>)
                    storage
                        .getMethod("getBackpackContents", UUID.class)
                        .invoke(st, UUID.fromString(ids.iterator().next().id())))
                .orElseThrow();
    contents.putString("ums_stale_key", "must disappear");
    bags.apply(p, saved);
    inv = (IItemHandlerModifiable) wrapper.getMethod("getInventoryHandler").invoke(w);
    h.assertTrue(
        inv.getStackInSlot(0).is(Items.DIAMOND) && inv.getStackInSlot(0).getCount() == 7,
        "Backpack cache was not refreshed");
    h.assertTrue(!contents.contains("ums_stale_key"), "Load merged instead of replacing NBT");
    Class<?> contextType =
        Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContext");
    Object context =
        Class.forName(contextType.getName() + "$Item")
            .getConstructor(String.class, int.class)
            .newInstance("main", 0);
    var menu =
        (net.minecraft.world.inventory.AbstractContainerMenu)
            Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer")
                .getConstructor(
                    int.class, net.minecraft.world.entity.player.Player.class, contextType)
                .newInstance(7, p, context);
    int firstUpgrade = (int) menu.getClass().getMethod("getFirstUpgradeSlot").invoke(menu);
    sent.clear();
    bags.containerOpened(p, menu);
    h.assertTrue(
        sent.stream()
            .anyMatch(
                packet ->
                    packet instanceof ClientboundContainerSetSlotPacket slot
                        && slot.getContainerId() == 7
                        && slot.getSlot() == firstUpgrade
                        && slot.getItem().is(upgradeItem)),
        "Menu open must explicitly send restored upgrade slots");
    menu.removed(p);
    service.join(p);
    h.assertTrue(UnifiedSync.protectedPlayer(p), "Join must start protected");
    Vec3 old = p.position();
    p.move(MoverType.SELF, new Vec3(1, 0, 0));
    h.assertTrue(p.position().equals(old), "Protected player moved");
    p.getInventory().selected = 0;
    p.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(4));
    h.assertTrue(p.getInventory().selected == 0, "Protected inventory packet was accepted");
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    service.session(p.getUUID()).state == Coordinator.State.READY,
                    "Awaiting SQL load: " + service.session(p.getUUID()).error))
        .thenExecute(
            () -> {
              try {
                service.save(service.session(p.getUUID()), "all", "smoke", null);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    service.session(p.getUUID()).state == Coordinator.State.READY, "Awaiting save"))
        .thenExecute(
            () -> {
              try {
                var inventory =
                    (IItemHandlerModifiable) wrapper.getMethod("getInventoryHandler").invoke(w);
                inventory.setStackInSlot(0, new ItemStack(Items.DIRT, 5));
                service.save(service.session(p.getUUID()), "backpacks", "smoke-change", null);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    service.session(p.getUUID()).state == Coordinator.State.READY,
                    "Awaiting changed save"))
        .thenExecute(
            () -> {
              try {
                service.rollback(service.session(p.getUUID()), "backpacks", 1, t -> {});
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    service.session(p.getUUID()).state == Coordinator.State.READY,
                    "Awaiting rollback: " + service.session(p.getUUID()).error))
        .thenExecute(
            () -> {
              try {
                var inventory =
                    (IItemHandlerModifiable) wrapper.getMethod("getInventoryHandler").invoke(w);
                h.assertTrue(
                    inventory.getStackInSlot(0).is(Items.DIAMOND),
                    "Rollback did not restore diamond");
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            })
        .thenExecute(() -> service.logout(p))
        .thenWaitUntil(
            () -> h.assertTrue(service.session(p.getUUID()) == null, "Awaiting deferred logout"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 6000)
  public static void canonicalParentLoadedBeforeDiscoveringNestedBackpacks(GameTestHelper h)
      throws Exception {
    ServerPlayer p = player(h, "NestedTest");
    Coordinator service = UnifiedSync.service();
    var type =
        BuiltInRegistries.ITEM.get(ResourceLocation.parse("sophisticatedbackpacks:backpack"));
    ItemStack parent = new ItemStack(type),
        child = new ItemStack(type),
        stale = new ItemStack(type);
    Class<?> wrapper =
        Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
    Object pw = wrapper.getMethod("fromStack", ItemStack.class).invoke(null, parent),
        cw = wrapper.getMethod("fromStack", ItemStack.class).invoke(null, child),
        sw = wrapper.getMethod("fromStack", ItemStack.class).invoke(null, stale);
    var pi = (IItemHandlerModifiable) wrapper.getMethod("getInventoryHandler").invoke(pw);
    var ci = (IItemHandlerModifiable) wrapper.getMethod("getInventoryHandler").invoke(cw);
    wrapper.getMethod("getInventoryHandler").invoke(sw);
    ci.setStackInSlot(0, new ItemStack(Items.EMERALD, 11));
    pi.setStackInSlot(0, child);
    p.getInventory().setItem(0, parent);
    BackpackModule m = new BackpackModule(service.config);
    m.verify();
    var keys = m.discover(p);
    h.assertTrue(keys.size() == 2, "Nested bag must be discovered");
    var snapshot = m.capture(p, keys);
    var store = new dev.unifiedsync.store.MysqlStore(service.config);
    UUID fixture = UUID.randomUUID();
    String token = UUID.randomUUID().toString();
    store.claim(fixture, token);
    store.acquire(fixture, token, keys);
    store.save(fixture, token, Map.of("backpacks", snapshot), "fixture", Set.of());
    store.release(fixture, token);
    // Stale local parent points at a bag held by somebody else. This reference must never be
    // claimed.
    UUID ghost =
        (UUID) ((Optional<?>) wrapper.getMethod("getContentsUuid").invoke(sw)).orElseThrow();
    store.claim(fixture, token);
    store.acquire(fixture, token, Set.of(new ResourceKey("backpacks", ghost.toString())));
    pi.setStackInSlot(0, stale);
    service.join(p);
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    service.session(p.getUUID()).state == Coordinator.State.READY,
                    "Awaiting canonical nested load: " + service.session(p.getUUID()).error))
        .thenExecute(
            () -> {
              try {
                h.assertTrue(m.discover(p).equals(keys), "Stale child survived canonical restore");
                store.release(fixture, token);
                service.logout(p);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            })
        .thenWaitUntil(
            () -> h.assertTrue(service.session(p.getUUID()) == null, "Awaiting nested logout"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 6000)
  public static void corruptHeadRemainsProtectedAndBackupRecovers(GameTestHelper h)
      throws Exception {
    ServerPlayer p = player(h, "CorruptionTest");
    Coordinator service = UnifiedSync.service();
    service.join(p);
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    service.session(p.getUUID()).state == Coordinator.State.READY,
                    "Awaiting initial sync"))
        .thenExecute(
            () -> {
              try {
                // Deliberately alter test data without updating its checksum.
                var c = service.config;
                Properties props = new Properties();
                props.setProperty("user", c.user());
                props.setProperty("password", c.password());
                try (var conn = new com.mysql.cj.jdbc.Driver().connect(c.jdbcUrl(), props);
                    var stmt =
                        conn.prepareStatement(
                            "UPDATE ums_resources SET payload=? WHERE grp=? AND module='astral' AND"
                                + " id=?")) {
                  stmt.setBytes(1, new byte[] {1, 2});
                  stmt.setString(2, c.group());
                  stmt.setString(3, p.getUUID().toString());
                  stmt.executeUpdate();
                }
                service.retry(service.session(p.getUUID()), t -> {});
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    service.session(p.getUUID()).state == Coordinator.State.ERROR,
                    "Corruption must fail closed"))
        .thenExecute(
            () -> {
              h.assertTrue(UnifiedSync.protectedPlayer(p), "Corrupted player must stay protected");
              try {
                service.rollback(service.session(p.getUUID()), "astral", 0, t -> {});
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    service.session(p.getUUID()).state == Coordinator.State.READY,
                    "Backup recovery must reload every module: "
                        + service.session(p.getUUID()).error))
        .thenExecute(
            () -> {
              service.releaseProtection(service.session(p.getUUID()), t -> {});
              h.assertTrue(
                  service.session(p.getUUID()).state == Coordinator.State.BYPASS
                      && !UnifiedSync.protectedPlayer(p),
                  "Administrative release failed");
              service.logout(p);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    service.session(p.getUUID()) == null, "Awaiting released session cleanup"))
        .thenSucceed();
  }

  private static void triggerAutosave(Coordinator service, ServerPlayer p) {
    if (service.config.worldSave()) p.getServer().saveEverything(true, false, false);
    else {
      service.session(p.getUUID()).lastSave = 0;
      service.tick();
    }
  }

  @GameTest(template = "empty", timeoutTicks = 4000, batch = "autosave-gui")
  public static void autosaveKeepsMenusOpenAndOrdersTransfersAndLogout(GameTestHelper h)
      throws Exception {
    List<Packet<?>> sent = new ArrayList<>();
    ServerPlayer p = player(h, "AutosaveGui", sent);
    Coordinator service = UnifiedSync.service();
    var bagItem =
        BuiltInRegistries.ITEM.get(ResourceLocation.parse("sophisticatedbackpacks:backpack"));
    ItemStack bag = new ItemStack(bagItem);
    p.getInventory().setItem(0, bag);
    Class<?> wrapper =
        Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
    Object w = wrapper.getMethod("fromStack", ItemStack.class).invoke(null, bag);
    ((IItemHandlerModifiable) wrapper.getMethod("getInventoryHandler").invoke(w))
        .setStackInSlot(0, new ItemStack(Items.DIAMOND, 7));
    var module = new BackpackModule(service.config);
    module.verify();
    var keys = module.discover(p);
    var bagKey = keys.iterator().next();
    var expectedWorldSnapshot = new java.util.concurrent.atomic.AtomicReference<byte[]>();
    service.join(p);
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    service.session(p.getUUID()).state == Coordinator.State.READY,
                    "Awaiting autosave join"))
        .thenExecute(
            () -> {
              var session = service.session(p.getUUID());
              var chest = net.minecraft.world.inventory.ChestMenu.threeRows(8, p.getInventory());
              p.containerMenu = chest;
              sent.clear();
              if (service.config.worldSave()) {
                session.lastSave = 0;
                service.tick();
                h.assertTrue(!session.busy, "World mode must ignore independent timer");
                var previous = new java.util.HashMap<ServerLevel, Boolean>();
                p.getServer()
                    .getAllLevels()
                    .forEach(
                        level -> {
                          previous.put(level, level.noSave);
                          level.noSave = true;
                        });
                try {
                  p.getServer().saveAllChunks(true, false, false);
                } finally {
                  previous.forEach((level, noSave) -> level.noSave = noSave);
                }
                h.assertTrue(!session.busy, "Disabled world saving must not trigger SQL autosave");
              }
              triggerAutosave(service, p);
              h.assertTrue(
                  session.busy && session.state == Coordinator.State.READY,
                  "Autosave must write without freezing");
              h.assertTrue(
                  !service.protectedPlayer(p) && service.beforeAction(p),
                  "Autosave blocked normal interaction");
              service.containerOpened(p, chest);
              h.assertTrue(p.containerMenu == chest, "Autosave closed ordinary GUI");
              h.assertTrue(
                  sent.stream()
                      .noneMatch(packet -> packet instanceof ClientboundContainerClosePacket),
                  "Autosave sent a close packet");
            })
        .thenExecute(
            () -> {
              if (!service.config.worldSave()) return;
              try {
                var inv =
                    (IItemHandlerModifiable) wrapper.getMethod("getInventoryHandler").invoke(w);
                inv.setStackInSlot(0, new ItemStack(Items.DIAMOND, 8));
                triggerAutosave(service, p);
                inv.setStackInSlot(0, new ItemStack(Items.DIAMOND, 9));
                triggerAutosave(service, p);
                expectedWorldSnapshot.set(module.capture(p, Set.of(bagKey)).get(bagKey));
                inv.setStackInSlot(0, new ItemStack(Items.DIAMOND, 10));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            })
        .thenWaitUntil(
            () -> h.assertTrue(!service.session(p.getUUID()).busy, "Awaiting chest autosave"))
        .thenExecute(
            () -> {
              if (!service.config.worldSave()) return;
              try {
                var session = service.session(p.getUUID());
                var latest =
                    new dev.unifiedsync.store.MysqlStore(service.config)
                        .acquire(session.uuid, session.token, Set.of(bagKey));
                h.assertTrue(
                    Arrays.equals(latest.get(bagKey), expectedWorldSnapshot.get()),
                    "Queued save must preserve the latest world-save checkpoint, not later gameplay"
                        + " state");
                h.assertTrue(p.containerMenu != p.inventoryMenu, "Queued world save closed GUI");
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            })
        .thenExecute(
            () -> {
              try {
                var contextType =
                    Class.forName(
                        "net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContext");
                Object context =
                    Class.forName(contextType.getName() + "$Item")
                        .getConstructor(String.class, int.class)
                        .newInstance("main", 0);
                var menu =
                    (net.minecraft.world.inventory.AbstractContainerMenu)
                        Class.forName(
                                "net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer")
                            .getConstructor(
                                int.class,
                                net.minecraft.world.entity.player.Player.class,
                                contextType)
                            .newInstance(9, p, context);
                p.containerMenu = menu;
                sent.clear();
                var session = service.session(p.getUUID());
                triggerAutosave(service, p);
                service.containerOpened(p, menu);
                h.assertTrue(
                    p.containerMenu == menu && service.beforeAction(p),
                    "Autosave closed or blocked backpack GUI");
                h.assertTrue(
                    sent.stream()
                        .noneMatch(packet -> packet instanceof ClientboundContainerClosePacket),
                    "Backpack autosave sent a close packet");
                // A different UUID appearing while IO is pending must still be protected
                // immediately.
                p.getInventory().setItem(1, new ItemStack(bagItem));
                h.assertTrue(
                    !service.beforeAction(p) && service.protectedPlayer(p),
                    "New bag bypassed protection during autosave");
                h.assertTrue(session.busy, "Ownership transfer overlapped pending write");
                h.assertTrue(
                    p.containerMenu == p.inventoryMenu,
                    "Backpack's own handler-bound GUI must still close during transfer");
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    service.session(p.getUUID()).state == Coordinator.State.READY
                        && !service.session(p.getUUID()).busy,
                    "Awaiting ordered ownership transfer: " + service.session(p.getUUID()).error))
        .thenExecute(
            () -> {
              try {
                h.assertTrue(module.discover(p).size() == 2, "New bag was not loaded");
                var session = service.session(p.getUUID());
                triggerAutosave(service, p);
                ((IItemHandlerModifiable) wrapper.getMethod("getInventoryHandler").invoke(w))
                    .setStackInSlot(0, new ItemStack(Items.EMERALD, 13));
                service.logout(p);
                h.assertTrue(session.busy, "Logout must wait for pending autosave");
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    service.session(p.getUUID()) == null, "Awaiting latest logout snapshot"))
        .thenExecute(
            () -> {
              var store = new dev.unifiedsync.store.MysqlStore(service.config);
              UUID reader = UUID.randomUUID();
              String token = UUID.randomUUID().toString();
              try {
                store.claim(reader, token);
                var latest = store.acquire(reader, token, Set.of(bagKey));
                var expected = module.capture(p, Set.of(bagKey));
                h.assertTrue(
                    Arrays.equals(latest.get(bagKey), expected.get(bagKey)),
                    "Logout lost changes made after autosave capture");
              } catch (Exception e) {
                throw new RuntimeException(e);
              } finally {
                try {
                  store.release(reader, token);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }
              module.detached(p);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 4000, batch = "autosave-failure")
  public static void autosaveFailureKeepsProtection(GameTestHelper h) {
    ServerPlayer p = player(h, "AutosaveFailure");
    Coordinator service = UnifiedSync.service();
    service.join(p);
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    service.session(p.getUUID()).state == Coordinator.State.READY,
                    "Awaiting failure test join"))
        .thenExecute(
            () -> {
              var session = service.session(p.getUUID());
              try {
                // Revoke this disposable session's SQL lease to force the actual background write
                // to fail.
                new dev.unifiedsync.store.MysqlStore(service.config)
                    .release(session.uuid, session.token);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              triggerAutosave(service, p);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    service.session(p.getUUID()).state == Coordinator.State.ERROR,
                    "Write failure must enter ERROR"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  service.protectedPlayer(p) && !service.beforeAction(p),
                  "Failed autosave allowed interactions");
              service.logout(p);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    service.session(p.getUUID()) == null, "Awaiting failed session cleanup"))
        .thenSucceed();
  }

  private static void chestClick(
      ServerPlayer player, int slot, net.minecraft.world.inventory.ClickType type) {
    var menu = player.containerMenu;
    player.connection.handleContainerClick(
        new ServerboundContainerClickPacket(
            menu.containerId,
            menu.getStateId(),
            slot,
            0,
            type,
            menu.getCarried().copy(),
            new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>()));
  }

  private static void assertTransferKeepsChest(
      GameTestHelper h,
      Coordinator service,
      ServerPlayer player,
      net.minecraft.world.inventory.ChestMenu chest,
      List<Packet<?>> sent) {
    h.assertTrue(
        !service.beforeAction(player) && service.protectedPlayer(player),
        "Transfer must protect before another action");
    h.assertTrue(player.containerMenu == chest, "Backpack handoff closed the chest");
    h.assertTrue(
        sent.stream().noneMatch(packet -> packet instanceof ClientboundContainerClosePacket),
        "Handoff sent a close-window packet");
  }

  @GameTest(template = "empty", timeoutTicks = 4000, batch = "chest-transfer")
  public static void backpackChestTransfersKeepWindowAndProtectContents(GameTestHelper h)
      throws Exception {
    List<Packet<?>> sent = new ArrayList<>();
    ServerPlayer player = player(h, "ChestTransfer", sent);
    Coordinator service = UnifiedSync.service();
    var bagItem =
        BuiltInRegistries.ITEM.get(ResourceLocation.parse("sophisticatedbackpacks:backpack"));
    ItemStack bag = new ItemStack(bagItem);
    player.getInventory().setItem(0, bag);
    Class<?> wrapper =
        Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
    Object w = wrapper.getMethod("fromStack", ItemStack.class).invoke(null, bag);
    var inventory = (IItemHandlerModifiable) wrapper.getMethod("getInventoryHandler").invoke(w);
    inventory.setStackInSlot(0, new ItemStack(Items.DIAMOND, 7));
    var module = new BackpackModule(service.config);
    module.verify();
    var key = module.discover(player).iterator().next();
    var expected = module.capture(player, Set.of(key)).get(key);
    var contents = new net.minecraft.world.SimpleContainer(27);
    var chest =
        new java.util.concurrent.atomic.AtomicReference<net.minecraft.world.inventory.ChestMenu>();
    service.join(player);
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    service.session(player.getUUID()).state == Coordinator.State.READY
                        && !service.session(player.getUUID()).busy,
                    "Awaiting chest-transfer join"))
        .thenExecute(
            () -> {
              player.openMenu(
                  new net.minecraft.world.SimpleMenuProvider(
                      (id, inv, p) ->
                          net.minecraft.world.inventory.ChestMenu.threeRows(id, inv, contents),
                      net.minecraft.network.chat.Component.literal("Transfer test")));
              chest.set((net.minecraft.world.inventory.ChestMenu) player.containerMenu);
              sent.clear();
              // Put the backpack into a chest while an autosave is pending.
              triggerAutosave(service, player);
              chestClick(player, 54, net.minecraft.world.inventory.ClickType.QUICK_MOVE);
              h.assertTrue(
                  player.getInventory().getItem(0).isEmpty() && contents.getItem(0).is(bagItem),
                  "Shift-click did not store the bag");
              assertTransferKeepsChest(h, service, player, chest.get(), sent);
              h.assertTrue(
                  service.session(player.getUUID()).busy,
                  "Transfer must wait for pending autosave");
              chestClick(player, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE);
              h.assertTrue(
                  contents.getItem(0).is(bagItem),
                  "Protected transfer accepted an inventory click");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    service.session(player.getUUID()).state == Coordinator.State.READY
                        && !service.session(player.getUUID()).busy,
                    "Awaiting deposit handoff: " + service.session(player.getUUID()).error))
        .thenExecute(
            () -> {
              h.assertTrue(
                  player.containerMenu == chest.get(), "Completed deposit replaced the chest");
              h.assertTrue(
                  !service.session(player.getUUID()).owned.contains(key),
                  "Deposited bag lease was not released");
              var db = new dev.unifiedsync.store.MysqlStore(service.config);
              UUID reader = UUID.randomUUID();
              String token = UUID.randomUUID().toString();
              try {
                db.claim(reader, token);
                h.assertTrue(
                    Arrays.equals(db.acquire(reader, token, Set.of(key)).get(key), expected),
                    "Deposit failed to save bag contents");
              } catch (Exception e) {
                throw new RuntimeException(e);
              } finally {
                try {
                  db.release(reader, token);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }
              // Make the local world copy stale to verify withdrawal reloads the SQL checkpoint.
              try {
                ((IItemHandlerModifiable) wrapper.getMethod("getInventoryHandler").invoke(w))
                    .setStackInSlot(0, new ItemStack(Items.DIRT, 3));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              sent.clear();
              chestClick(player, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE);
              h.assertTrue(contents.getItem(0).isEmpty(), "Shift-click did not withdraw the bag");
              assertTransferKeepsChest(h, service, player, chest.get(), sent);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    service.session(player.getUUID()).state == Coordinator.State.READY
                        && !service.session(player.getUUID()).busy,
                    "Awaiting withdrawal handoff: " + service.session(player.getUUID()).error))
        .thenExecute(
            () -> {
              int slot = -1;
              for (int i = 0; i < chest.get().slots.size(); i++)
                if (chest.get().getSlot(i).getItem().is(bagItem)) {
                  slot = i;
                  break;
                }
              h.assertTrue(slot >= 0, "Withdrawn bag missing from player slots");
              try {
                Object loaded =
                    wrapper
                        .getMethod("fromStack", ItemStack.class)
                        .invoke(null, chest.get().getSlot(slot).getItem());
                var restored =
                    (IItemHandlerModifiable)
                        wrapper.getMethod("getInventoryHandler").invoke(loaded);
                h.assertTrue(
                    restored.getStackInSlot(0).is(Items.DIAMOND)
                        && restored.getStackInSlot(0).getCount() == 7,
                    "Withdrawn bag exposed stale local contents");
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              sent.clear();
              // Normal pickup/place, including a backpack on the cursor during database load.
              chestClick(player, slot, net.minecraft.world.inventory.ClickType.PICKUP);
              h.assertTrue(
                  chest.get().getCarried().is(bagItem), "Pickup did not put bag on cursor");
              chestClick(player, 0, net.minecraft.world.inventory.ClickType.PICKUP);
              assertTransferKeepsChest(h, service, player, chest.get(), sent);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    service.session(player.getUUID()).state == Coordinator.State.READY
                        && !service.session(player.getUUID()).busy,
                    "Awaiting cursor deposit"))
        .thenExecute(
            () -> {
              sent.clear();
              chestClick(player, 0, net.minecraft.world.inventory.ClickType.PICKUP);
              h.assertTrue(
                  chest.get().getCarried().is(bagItem),
                  "Chest pickup did not retain cursor backpack");
              assertTransferKeepsChest(h, service, player, chest.get(), sent);
              h.assertTrue(
                  chest.get().getCarried().is(bagItem),
                  "Protection returned or dropped cursor backpack");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    service.session(player.getUUID()).state == Coordinator.State.READY
                        && !service.session(player.getUUID()).busy,
                    "Awaiting cursor withdrawal"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  player.containerMenu == chest.get() && chest.get().getCarried().is(bagItem),
                  "Load changed GUI or cursor");
              h.assertTrue(service.beforeAction(player), "Loaded transfer remained blocked");
              chestClick(player, 54, net.minecraft.world.inventory.ClickType.PICKUP);
              h.assertTrue(
                  chest.get().getCarried().isEmpty()
                      && player.getInventory().getItem(0).is(bagItem),
                  "Normal clicks did not resume");
              service.logout(player);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(service.session(player.getUUID()) == null, "Awaiting transfer logout"))
        .thenExecute(() -> module.detached(player))
        .thenSucceed();
  }
}
