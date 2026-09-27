package dev.unifiedsync.mixin;

import dev.unifiedsync.UnifiedSync;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PacketGuardMixin {
  @Shadow public ServerPlayer player;

  // Hybrid servers may dispatch Bukkit plugin channels in this override before calling super.
  @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
  private void ums$customPayload(
      net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket packet,
      CallbackInfo ci) {
    String id = packet.payload().type().id().toString();
    if (id.equals("minecraft:brand")
        || id.equals("minecraft:register")
        || id.equals("minecraft:unregister")
        || packet.payload() instanceof net.neoforged.neoforge.network.payload.CommonVersionPayload
        || packet.payload() instanceof net.neoforged.neoforge.network.payload.CommonRegisterPayload)
      return;
    PacketUtils.ensureRunningOnSameThread(
        packet, (ServerGamePacketListenerImpl) (Object) this, player.serverLevel());
    if (!UnifiedSync.action(player)) ci.cancel();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  @Inject(
      method = {
        "handleMovePlayer",
        "handleMoveVehicle",
        "handlePlayerInput",
        "handleContainerClick",
        "handleContainerButtonClick",
        "handleSetCreativeModeSlot",
        "handlePlayerAction",
        "handleUseItemOn",
        "handleUseItem",
        "handleInteract",
        "handleSetCarriedItem",
        "handlePlaceRecipe",
        "handleEditBook",
        "handleSignUpdate",
        "handlePlayerCommand",
        "handleSetCommandBlock",
        "handleSetCommandMinecart",
        "handleSetStructureBlock",
        "handleSetJigsawBlock",
        "handleJigsawGenerate",
        "handleRenameItem",
        "handleSelectTrade",
        "handleSetBeaconPacket",
        "handlePlayerAbilities",
        "handlePickItem",
        "handlePaddleBoat",
        "handleContainerSlotStateChanged",
        "handleTeleportToEntityPacket"
      },
      at = @At("HEAD"),
      cancellable = true)
  private void ums$guard(@Coerce Object packet, CallbackInfo ci) {
    PacketUtils.ensureRunningOnSameThread(
        (Packet) packet, (ServerGamePacketListenerImpl) (Object) this, player.serverLevel());
    if (!UnifiedSync.action(player)) {
      ci.cancel();
      player.containerMenu.broadcastFullState();
    }
  }

  @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
  private void ums$command(
      net.minecraft.network.protocol.game.ServerboundChatCommandPacket packet, CallbackInfo ci) {
    PacketUtils.ensureRunningOnSameThread(
        packet, (ServerGamePacketListenerImpl) (Object) this, player.serverLevel());
    if (!ums$commandAllowed(packet.command())) ci.cancel();
  }

  @Inject(method = "handleSignedChatCommand", at = @At("HEAD"), cancellable = true)
  private void ums$signed(
      net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket packet,
      CallbackInfo ci) {
    PacketUtils.ensureRunningOnSameThread(
        packet, (ServerGamePacketListenerImpl) (Object) this, player.serverLevel());
    if (!ums$commandAllowed(packet.command())) ci.cancel();
  }

  @Unique
  private boolean ums$commandAllowed(String command) {
    if (UnifiedSync.action(player)) return true;
    String name = command.stripLeading().split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
    if (name.equals("ums") && player.hasPermissions(3)) return true;
    return UnifiedSync.service() != null
        && UnifiedSync.service().config.allowedCommands().contains(name);
  }
}
