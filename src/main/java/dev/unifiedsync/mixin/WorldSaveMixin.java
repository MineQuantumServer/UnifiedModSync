package dev.unifiedsync.mixin;

import dev.unifiedsync.UnifiedSync;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public abstract class WorldSaveMixin {
  @Inject(method = "saveAllChunks(ZZZ)Z", at = @At("RETURN"))
  private void ums$afterWorldSave(
      boolean suppressLog, boolean flush, boolean forced, CallbackInfoReturnable<Boolean> result) {
    if (!result.getReturnValueZ() || UnifiedSync.service() == null) return;
    // /save-off can leave every world unsaved despite saveAllChunks returning true.
    for (var level : ((MinecraftServer) (Object) this).getAllLevels()) {
      if (forced || !level.noSave) {
        UnifiedSync.service().worldSaved();
        return;
      }
    }
  }
}
