package dev.unifiedsync.mixin;

import dev.unifiedsync.UnifiedSync;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityGuardMixin {
  @Inject(method = "move", at = @At("HEAD"), cancellable = true)
  private void ums$move(MoverType type, Vec3 delta, CallbackInfo ci) {
    if ((Object) this instanceof ServerPlayer p && UnifiedSync.protectedPlayer(p)) ci.cancel();
  }

  @Inject(method = "load", at = @At("HEAD"))
  private void ums$externalLoad(net.minecraft.nbt.CompoundTag tag, CallbackInfo ci) {
    if ((Object) this instanceof ServerPlayer p && UnifiedSync.service() != null)
      UnifiedSync.service().externalPlayerLoad(p);
  }
}
