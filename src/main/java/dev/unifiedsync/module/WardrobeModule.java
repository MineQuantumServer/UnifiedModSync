package dev.unifiedsync.module;

import java.lang.reflect.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;

public final class WardrobeModule extends PlayerModule {
  private Method of, context, serialize, deserialize, tag, inventory, broadcast, broadcastPlayer;
  private Constructor<?> save, load;

  public String id() {
    return "wardrobe";
  }

  public String requiredMod() {
    return "armourers_workshop";
  }

  public void verify() throws Exception {
    String b = "moe.plushie.armourers_workshop.";
    Class<?> w = Class.forName(b + "core.capability.SkinWardrobe"),
        c = Class.forName(b + "core.utils.SerializationContext"),
        s = Class.forName(b + "core.utils.TagSerializer"),
        i = Class.forName(b + "api.core.IDataSerializer");
    of = w.getMethod("of", Entity.class);
    context = c.getMethod("from", Object.class);
    save = s.getConstructor(c);
    load = s.getConstructor(CompoundTag.class, c);
    serialize = w.getMethod("serialize", i);
    deserialize = w.getMethod("deserialize", i);
    tag = s.getMethod("tag");
    inventory = w.getMethod("inventory");
    broadcast = w.getMethod("broadcast");
    broadcastPlayer = w.getMethod("broadcast", ServerPlayer.class);
  }

  private Object wardrobe(ServerPlayer p) throws Exception {
    Object w = of.invoke(null, p);
    if (w == null) throw new IllegalStateException("Wardrobe attachment missing");
    return w;
  }

  protected byte[] capture(ServerPlayer p) throws Exception {
    Object s = save.newInstance(context.invoke(null, p));
    serialize.invoke(wardrobe(p), s);
    return NbtBytes.encode((CompoundTag) tag.invoke(s));
  }

  protected void validate(ServerPlayer p, byte[] b) throws Exception {
    CompoundTag tag = NbtBytes.decode(b);
    if (tag.isEmpty()) throw new IllegalStateException("Empty wardrobe snapshot");
    ItemNbtValidation.inventory(
        p,
        tag,
        ((Container) inventory.invoke(wardrobe(p))).getContainerSize(),
        net.minecraft.world.item.ItemStack.CODEC);
    load.newInstance(tag, context.invoke(null, p));
  }

  protected void apply(ServerPlayer p, byte[] b) throws Exception {
    Object w = wardrobe(p);
    deserialize.invoke(w, load.newInstance(NbtBytes.decode(b), context.invoke(null, p)));
    ((Container) inventory.invoke(w)).setChanged();
  }

  public void activated(ServerPlayer p) throws Exception {
    Object w = wardrobe(p);
    broadcast.invoke(w);
    broadcastPlayer.invoke(w, p);
  }
}
