package dev.unifiedsync.mixin;

import dev.unifiedsync.UnifiedSync;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.network.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class CustomPayloadGuardMixin {
  @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
  private void ums$payload(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
    if ((Object) this instanceof ServerGamePacketListenerImpl listener) {
      String id = packet.payload().type().id().toString();
      if (id.equals("minecraft:brand")
          || id.equals("minecraft:register")
          || id.equals("minecraft:unregister")
          || packet.payload() instanceof net.neoforged.neoforge.network.payload.CommonVersionPayload
          || packet.payload()
              instanceof net.neoforged.neoforge.network.payload.CommonRegisterPayload) return;
      PacketUtils.ensureRunningOnSameThread(packet, listener, listener.player.serverLevel());
      if (!UnifiedSync.action(listener.player)) ci.cancel();
    }
  }
}
