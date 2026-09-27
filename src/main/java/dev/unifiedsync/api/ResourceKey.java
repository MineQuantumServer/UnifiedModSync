package dev.unifiedsync.api;

public record ResourceKey(String module, String id) implements Comparable<ResourceKey> {
  public ResourceKey {
    if (!valid(module, 32, false) || !valid(id, 64, true))
      throw new IllegalArgumentException("Invalid resource key");
  }

  private static boolean valid(String value, int limit, boolean resource) {
    if (value == null || value.isEmpty() || value.length() > limit) return false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if ((c >= 'a' && c <= 'z')
          || (c >= '0' && c <= '9')
          || c == '_'
          || (resource && ((c >= 'A' && c <= 'Z') || c == '-'))) continue;
      return false;
    }
    return true;
  }

  @Override
  public int compareTo(ResourceKey o) {
    int c = module.compareTo(o.module);
    return c == 0 ? id.compareTo(o.id) : c;
  }
}
