package dev.unifiedsync;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.*;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(value = "unifiedsync", dist = Dist.DEDICATED_SERVER)
public final class UnifiedSync {
  private static final java.util.List<
          java.util.function.Supplier<? extends dev.unifiedsync.api.SyncModule>>
      EXTRA_MODULES = new java.util.concurrent.CopyOnWriteArrayList<>();

  /** Call from an add-on mod's common setup, before ServerStartingEvent. */
  public static void registerModule(
      java.util.function.Supplier<? extends dev.unifiedsync.api.SyncModule> factory) {
    if (coordinator != null)
      throw new IllegalStateException("Register modules before server startup");
    EXTRA_MODULES.add(java.util.Objects.requireNonNull(factory));
  }

  static java.util.List<java.util.function.Supplier<? extends dev.unifiedsync.api.SyncModule>>
      extraModules() {
    return java.util.List.copyOf(EXTRA_MODULES);
  }

  public static final Logger LOG = LogUtils.getLogger();
  private static volatile Coordinator coordinator;
  private static volatile boolean configBroken;

  public UnifiedSync() {
    NeoForge.EVENT_BUS.addListener(this::starting);
    NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::stopping);
    NeoForge.EVENT_BUS.addListener(this::tick);
    NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::join);
    NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::logout);
    NeoForge.EVENT_BUS.addListener(this::clonePlayer);
    NeoForge.EVENT_BUS.addListener(this::commands);
    NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::command);
    NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::damage);
    NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::pickup);
    NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::attack);
    NeoForge.EVENT_BUS.addListener(
        EventPriority.HIGHEST, (PlayerInteractEvent.RightClickBlock e) -> interact(e));
    NeoForge.EVENT_BUS.addListener(
        EventPriority.HIGHEST, (PlayerInteractEvent.RightClickItem e) -> interact(e));
    NeoForge.EVENT_BUS.addListener(
        EventPriority.HIGHEST, (PlayerInteractEvent.LeftClickBlock e) -> interact(e));
    NeoForge.EVENT_BUS.addListener(
        EventPriority.HIGHEST, (PlayerInteractEvent.EntityInteract e) -> interact(e));
    NeoForge.EVENT_BUS.addListener(
        EventPriority.HIGHEST, (PlayerInteractEvent.EntityInteractSpecific e) -> interact(e));
  }

  public static Coordinator service() {
    return coordinator;
  }

  public static boolean protectedPlayer(ServerPlayer p) {
    return configBroken || (coordinator != null && coordinator.protectedPlayer(p));
  }

  public static boolean action(ServerPlayer p) {
    return !configBroken && (coordinator == null || coordinator.beforeAction(p));
  }

  private void starting(ServerStartingEvent e) {
    try {
      coordinator =
          new Coordinator(
              e.getServer(),
              Config.load(FMLPaths.CONFIGDIR.get().resolve("unified-mod-sync.properties")));
    } catch (Exception ex) {
      configBroken = true;
      LOG.error(
          "Invalid config/unified-mod-sync.properties; players remain protected until restart", ex);
    }
  }

  private void stopping(ServerStoppingEvent e) {
    if (coordinator != null) coordinator.close();
  }

  private void tick(ServerTickEvent.Post e) {
    if (coordinator != null) coordinator.tick();
  }

  private void join(PlayerEvent.PlayerLoggedInEvent e) {
    if (coordinator != null && e.getEntity() instanceof ServerPlayer p) coordinator.join(p);
  }

  private void logout(PlayerEvent.PlayerLoggedOutEvent e) {
    if (coordinator != null && e.getEntity() instanceof ServerPlayer p) coordinator.logout(p);
  }

  private void clonePlayer(PlayerEvent.Clone e) {
    if (coordinator != null && e.getEntity() instanceof ServerPlayer p) coordinator.clonePlayer(p);
  }

  private void commands(RegisterCommandsEvent e) {
    SyncCommands.register(e.getDispatcher());
  }

  private void command(CommandEvent e) {
    if (e.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer p
        && protectedPlayer(p)) {
      String raw = e.getParseResults().getReader().getString().stripLeading();
      String name =
          raw.split("\\s+", 2)[0].replaceFirst("^/", "").toLowerCase(java.util.Locale.ROOT);
      if (name.equals("ums") && p.hasPermissions(3)) return;
      if (coordinator == null || !coordinator.config.allowedCommands().contains(name)) {
        e.setCanceled(true);
        p.sendSystemMessage(net.minecraft.network.chat.Component.literal("同步保护中，暂时不能使用此命令。"));
      }
    }
  }

  private void damage(LivingIncomingDamageEvent e) {
    if (e.getEntity() instanceof ServerPlayer p && protectedPlayer(p)) e.setCanceled(true);
  }

  private void pickup(ItemEntityPickupEvent.Pre e) {
    if (e.getPlayer() instanceof ServerPlayer p && !action(p))
      e.setCanPickup(net.neoforged.neoforge.common.util.TriState.FALSE);
  }

  private void attack(AttackEntityEvent e) {
    if (e.getEntity() instanceof ServerPlayer p && !action(p)) e.setCanceled(true);
  }

  private void interact(PlayerInteractEvent e) {
    if (e instanceof net.neoforged.bus.api.ICancellableEvent cancel
        && e.getEntity() instanceof ServerPlayer p
        && !action(p)) cancel.setCanceled(true);
  }
}
