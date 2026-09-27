package dev.unifiedsync.module;

import com.mojang.serialization.Codec;
import java.util.HashSet;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Reject invalid slots/registries before upstream loaders can silently replace an item with air.
 */
final class ItemNbtValidation {
  static void inventory(
      ServerPlayer player, CompoundTag inventory, int size, Codec<ItemStack> codec) {
    if (size < 0 || size > 32768) throw new IllegalStateException("Invalid inventory size");
    if (!inventory.contains("Items")) return;
    if (!(inventory.get("Items") instanceof ListTag items)
        || (!items.isEmpty() && items.getElementType() != Tag.TAG_COMPOUND))
      throw new IllegalStateException("Invalid inventory item list");
    var occupied = new HashSet<Integer>();
    for (Tag value : items) {
      CompoundTag item = (CompoundTag) value;
      if (!item.contains("Slot", Tag.TAG_ANY_NUMERIC))
        throw new IllegalStateException("Missing inventory slot");
      int slot =
          item.get("Slot") instanceof ByteTag ? item.getByte("Slot") & 0xff : item.getInt("Slot");
      if (slot < 0 || slot >= size || !occupied.add(slot))
        throw new IllegalStateException("Invalid or duplicate inventory slot: " + slot);
      ItemStack decoded =
          codec
              .parse(player.registryAccess().createSerializationContext(NbtOps.INSTANCE), item)
              .getOrThrow();
      if (decoded.isEmpty())
        throw new IllegalStateException("Stored item unexpectedly decoded as empty");
    }
  }
}
