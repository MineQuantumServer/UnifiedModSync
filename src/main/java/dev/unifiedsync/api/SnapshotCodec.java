package dev.unifiedsync.api;

import java.io.*;
import java.security.*;
import java.util.*;

public final class SnapshotCodec {
  public static final int MAX = 32 * 1024 * 1024;

  public static byte[] encode(Map<ResourceKey, byte[]> map) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeInt(0x554d5301);
    out.writeInt(map.size());
    for (var e : new TreeMap<>(map).entrySet()) {
      out.writeUTF(e.getKey().module());
      out.writeUTF(e.getKey().id());
      byte[] value = e.getValue();
      if (value == null || value.length == 0 || value.length > MAX)
        throw new IOException("Invalid snapshot payload");
      out.writeInt(value.length);
      out.write(value);
      if (bytes.size() > MAX) throw new IOException("Snapshot exceeds limit");
    }
    return bytes.toByteArray();
  }

  public static Map<ResourceKey, byte[]> decode(byte[] bytes) throws IOException {
    if (bytes == null || bytes.length > MAX) throw new IOException("Invalid snapshot size");
    var in = new DataInputStream(new ByteArrayInputStream(bytes));
    if (in.readInt() != 0x554d5301) throw new IOException("Unsupported snapshot schema");
    int count = in.readInt();
    if (count < 0 || count > 4096) throw new IOException("Invalid resource count");
    Map<ResourceKey, byte[]> result = new TreeMap<>();
    for (int i = 0; i < count; i++) {
      ResourceKey key = new ResourceKey(in.readUTF(), in.readUTF());
      int n = in.readInt();
      if (n <= 0 || n > MAX || n > in.available()) throw new IOException("Truncated snapshot");
      if (result.put(key, in.readNBytes(n)) != null) throw new IOException("Duplicate resource");
    }
    if (in.available() != 0) throw new IOException("Trailing snapshot bytes");
    return result;
  }

  public static String hash(byte[] b) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  public static void check(byte[] bytes, String hash) throws IOException {
    if (bytes == null || !hash(bytes).equals(hash))
      throw new IOException("Snapshot checksum mismatch");
  }
}
