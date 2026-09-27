package dev.unifiedsync.mixin;

import dev.unifiedsync.*;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "hellfirepvp.astralsorcery.common.research.ResearchManager", remap = false)
public abstract class AstralSaveMixin {
  @Inject(method = "scheduleSave(Ljava/util/UUID;Z)V", at = @At("HEAD"), cancellable = true)
  private static void ums$save(UUID uuid, boolean force, CallbackInfo ci) {
    Coordinator c = UnifiedSync.service();
    if (c != null && c.astralEnabled()) ci.cancel();
  }
}
