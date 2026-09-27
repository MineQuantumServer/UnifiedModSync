package dev.unifiedsync;

import static net.minecraft.commands.Commands.*;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import java.util.*;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class SyncCommands {
  public static void register(CommandDispatcher<CommandSourceStack> d) {
    var root = literal("ums").requires(s -> s.hasPermission(3));
    root.executes(
        c -> {
          say(c, "/ums status|save|backups|rollback|retry|release <玩家名|all> [模块|all] [备份序号]");
          return 1;
        });
    for (String op : List.of("status", "retry", "release"))
      root.then(literal(op).then(target().executes(c -> run(c, op, "all", 0))));
    for (String op : List.of("save", "backups", "rollback")) {
      var module =
          argument("module", StringArgumentType.word())
              .suggests(
                  (c, b) -> {
                    b.suggest("all");
                    if (UnifiedSync.service() != null)
                      UnifiedSync.service().moduleIds().forEach(b::suggest);
                    return b.buildFuture();
                  });
      if (op.equals("rollback"))
        module.then(
            argument("index", IntegerArgumentType.integer(0, 99))
                .executes(
                    c ->
                        run(
                            c,
                            op,
                            StringArgumentType.getString(c, "module"),
                            IntegerArgumentType.getInteger(c, "index"))));
      else module.executes(c -> run(c, op, StringArgumentType.getString(c, "module"), 0));
      root.then(literal(op).then(target().then(module)));
    }
    d.register(root);
  }

  private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
      target() {
    return argument("target", StringArgumentType.word())
        .suggests(
            (c, b) -> {
              b.suggest("all");
              for (String name : c.getSource().getServer().getPlayerNames()) b.suggest(name);
              return b.buildFuture();
            });
  }

  private static void say(CommandContext<CommandSourceStack> c, String text) {
    c.getSource().sendSuccess(() -> Component.literal(text), false);
  }

  private static int run(
      CommandContext<CommandSourceStack> c, String op, String module, int index) {
    Coordinator service = UnifiedSync.service();
    if (service == null || !service.active()) {
      say(c, "统一同步未启用；请检查 config/unified-mod-sync.properties 后重启。");
      return 0;
    }
    String target = StringArgumentType.getString(c, "target");
    List<ServerPlayer> players;
    if (target.equals("all"))
      players = List.copyOf(c.getSource().getServer().getPlayerList().getPlayers());
    else {
      ServerPlayer p = c.getSource().getServer().getPlayerList().getPlayerByName(target);
      if (p == null) {
        say(c, "玩家必须在当前服务器在线。");
        return 0;
      }
      players = List.of(p);
    }
    for (ServerPlayer p : players) {
      var s = service.session(p.getUUID());
      if (s == null) continue;
      try {
        switch (op) {
          case "status" ->
              say(
                  c,
                  p.getScoreboardName()
                      + ": "
                      + s.state
                      + " / modules="
                      + service.moduleIds()
                      + " / resources="
                      + s.owned.size()
                      + " / "
                      + s.error);
          case "save" -> service.save(s, module, "manual", t -> say(c, t));
          case "rollback" -> service.rollback(s, module, index, t -> say(c, t));
          case "backups" -> service.history(s, module, t -> say(c, t));
          case "retry" -> service.retry(s, t -> say(c, t));
          case "release" -> service.releaseProtection(s, t -> say(c, t));
        }
      } catch (Exception e) {
        service.operationFailed(s, e);
        say(c, p.getScoreboardName() + ": 操作未完成：" + e.getMessage());
      }
    }
    return players.size();
  }
}
