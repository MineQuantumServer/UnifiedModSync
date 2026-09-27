package dev.unifiedsync.mixin;

import java.util.*;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;

public final class OptionalMixins implements IMixinConfigPlugin {
  public void onLoad(String pkg) {}

  public String getRefMapperConfig() {
    return null;
  }

  public boolean shouldApplyMixin(String target, String mixin) {
    return !mixin.endsWith("AstralSaveMixin")
        || getClass().getClassLoader().getResource(target.replace('.', '/') + ".class") != null;
  }

  public void acceptTargets(Set<String> mine, Set<String> others) {}

  public List<String> getMixins() {
    return null;
  }

  public void preApply(String n, ClassNode c, String m, IMixinInfo i) {}

  public void postApply(String n, ClassNode c, String m, IMixinInfo i) {}
}
