package dev.unifiedsync.api;

public record ResourceKey(String module, String id) implements Comparable<ResourceKey> {
  public ResourceKey {
    if (!module.matches("[a-z0-9_]{1,32}") || !id.matches("[a-zA-Z0-9_-]{1,64}"))
      throw new IllegalArgumentException("Invalid resource key");
  }

  @Override
  public int compareTo(ResourceKey o) {
    int c = module.compareTo(o.module);
    return c == 0 ? id.compareTo(o.id) : c;
  }
}
