package dev.unifiedsync.mixin;

import dev.unifiedsync.UnifiedSync;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class PlayerGuardMixin {
  @Inject(
      method = {"tick", "doTick"},
      at = @At("HEAD"),
      cancellable = true)
  private void ums$tick(CallbackInfo ci) {
    if (!UnifiedSync.action((ServerPlayer) (Object) this)) ci.cancel();
  }
}
