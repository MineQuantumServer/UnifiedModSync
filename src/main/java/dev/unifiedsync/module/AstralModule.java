package dev.unifiedsync.module;

import com.mojang.serialization.Codec;
import dev.unifiedsync.api.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.network.PacketDistributor;

public final class AstralModule extends PlayerModule {
  private Codec<Object> codec;
  private Map<UUID, Object> cache;
  private Method progress, perks;
  private Object remove, add;
  private Method packet, applyAll;

  public String id() {
    return "astral";
  }

  public String requiredMod() {
    return "astralsorcery";
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public void verify() throws Exception {
    String b = "hellfirepvp.astralsorcery.common.";
    Class<?> p = Class.forName(b + "research.PlayerProgress"),
        m = Class.forName(b + "research.ResearchManager");
    codec = (Codec<Object>) p.getField("SAVE_CODEC").get(null);
    var f = m.getDeclaredField("serverProgress");
    f.setAccessible(true);
    cache = (Map<UUID, Object>) f.get(null);
    progress = m.getMethod("getProgress", Player.class, LogicalSide.class);
    Class<?> action = Class.forName(b + "perk.PerkManager$Action");
    perks =
        Class.forName(b + "perk.PerkManager")
            .getDeclaredMethod("modifyAllPerks", Player.class, LogicalSide.class, action);
    perks.setAccessible(true);
    remove = Enum.valueOf((Class) action, "REMOVE");
    add = Enum.valueOf((Class) action, "ADD");
    packet = Class.forName(b + "network.play.PktSyncPlayerProgress").getMethod("newRequest", p);
    applyAll = Class.forName(b + "network.play.PktSyncPerkActivity").getMethod("applyAll");
  }

  private Object decode(byte[] b) throws Exception {
    return codec.parse(NbtOps.INSTANCE, NbtBytes.decode(b)).getOrThrow();
  }

  protected byte[] capture(ServerPlayer p) throws Exception {
    Object v = progress.invoke(null, p, LogicalSide.SERVER);
    return NbtBytes.encode((CompoundTag) codec.encodeStart(NbtOps.INSTANCE, v).getOrThrow());
  }

  protected void validate(ServerPlayer p, byte[] b) throws Exception {
    decode(b);
  }

  protected void apply(ServerPlayer p, byte[] b) throws Exception {
    Object v = decode(b);
    perks.invoke(null, p, LogicalSide.SERVER, remove);
    cache.put(p.getUUID(), v);
    perks.invoke(null, p, LogicalSide.SERVER, add);
    PacketDistributor.sendToPlayer(p, (CustomPacketPayload) packet.invoke(null, v));
    PacketDistributor.sendToPlayer(p, (CustomPacketPayload) applyAll.invoke(null));
  }

  public void detached(ServerPlayer p) {
    cache.remove(p.getUUID());
  }
}
