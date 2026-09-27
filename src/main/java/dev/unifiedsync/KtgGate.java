package dev.unifiedsync;

import java.lang.reflect.*;
import net.minecraft.server.level.ServerPlayer;

/** Optional Bukkit bridge. NeoForge-only servers never resolve Bukkit classes. */
public final class KtgGate {
  private static Class<?> find(String name) throws ClassNotFoundException {
    for (ClassLoader l :
        new ClassLoader[] {
          KtgGate.class.getClassLoader(),
          Thread.currentThread().getContextClassLoader(),
          ClassLoader.getSystemClassLoader()
        })
      try {
        return Class.forName(name, true, l);
      } catch (ClassNotFoundException ignored) {
      }
    throw new ClassNotFoundException(name);
  }

  public static boolean ready(ServerPlayer player, Config config) throws Exception {
    Class<?> bukkit;
    try {
      bukkit = find("org.bukkit.Bukkit");
    } catch (ClassNotFoundException e) {
      if (config.ktgMode().equals("required"))
        throw new IllegalStateException("KTG4 is required but Bukkit is absent");
      return true;
    }
    Object manager = bukkit.getMethod("getPluginManager").invoke(null);
    Class<?> pm = find("org.bukkit.plugin.PluginManager"),
        pluginType = find("org.bukkit.plugin.Plugin");
    for (String name : new String[] {"YouerModSync", "AWWardrobeSync"}) {
      Object conflict = pm.getMethod("getPlugin", String.class).invoke(manager, name);
      if (conflict != null && (boolean) pluginType.getMethod("isEnabled").invoke(conflict)) {
        if (name.equals("AWWardrobeSync") && !config.modules().contains("wardrobe")) continue;
        if (name.equals("YouerModSync")) {
          if (!config.modules().contains("backpacks")) continue;
          Object yaml = conflict.getClass().getMethod("getConfig").invoke(conflict);
          if (!(boolean)
              yaml.getClass()
                  .getMethod("getBoolean", String.class, boolean.class)
                  .invoke(yaml, "modules.sophisticatedbackpacks", false)) continue;
        }
        throw new IllegalStateException("Disable overlapping sync plugin/module: " + name);
      }
    }
    if (config.ktgMode().equals("off")) return true;
    Object plugin = pm.getMethod("getPlugin", String.class).invoke(manager, "KnapsackToGo4");
    if (plugin == null) {
      if (config.ktgMode().equals("required"))
        throw new IllegalStateException("KTG4 required but not installed");
      return true;
    }
    if (!(boolean) pluginType.getMethod("isEnabled").invoke(plugin))
      throw new IllegalStateException("KTG4 is disabled");
    ClassLoader loader = plugin.getClass().getClassLoader();
    Class<?> base = Class.forName("cn.jja8.knapsackToGo4.bukkit.KnapsackToGo4", true, loader);
    Object work = base.getField("work").get(null);
    Class<?> adapter =
        Class.forName("cn.jja8.knapsackToGo4.bukkit.work.BukkitGo4Player", true, loader);
    Object bp = bukkit.getMethod("getPlayer", java.util.UUID.class).invoke(null, player.getUUID());
    if (bp == null || work == null) return false;
    Object gp = adapter.getConstructor(find("org.bukkit.entity.Player")).newInstance(bp);
    for (Method m : work.getClass().getMethods())
      if (m.getName().equals("isLoaded") && m.getParameterCount() == 1)
        return (boolean) m.invoke(work, gp);
    throw new NoSuchMethodException("KTG4 Work.isLoaded");
  }
}
