package dev.unifiedsync.module;

import dev.unifiedsync.api.SnapshotCodec;
import java.io.*;
import net.minecraft.nbt.*;

public final class NbtBytes {
  public static byte[] encode(CompoundTag tag) throws IOException {
    var b = new ByteArrayOutputStream();
    NbtIo.write(tag, new DataOutputStream(b));
    if (b.size() > SnapshotCodec.MAX) throw new IOException("NBT too large");
    return b.toByteArray();
  }

  public static CompoundTag decode(byte[] b) throws IOException {
    if (b == null || b.length == 0 || b.length > SnapshotCodec.MAX)
      throw new IOException("Invalid NBT size");
    var in = new DataInputStream(new ByteArrayInputStream(b));
    var tag = NbtIo.read(in, NbtAccounter.create(64L * 1024 * 1024));
    if (tag == null || in.available() != 0) throw new IOException("Invalid/trailing NBT");
    return tag;
  }
}
