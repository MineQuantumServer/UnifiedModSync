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
          public void send(Packet<?> packet) {}

          @Override
          public void send(Packet<?> packet, PacketSendListener listener) {}
        };
    return p;
  }

  @GameTest(template = "empty", timeoutTicks = 4000)
  public static void moduleRoundtripAndProtection(GameTestHelper h) throws Exception {
    ServerPlayer p = player(h, "ModuleTest");
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
    var saved = bags.capture(p, ids);
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
}
