package com.mcsow.mixin;

import com.mcsow.movement.WarsowPmove;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Warsow movement is legitimately much faster than vanilla (dash, walljumps, launches), so the
 * server's "moved too quickly!" anti-cheat rubber-bands players. While Warsow movement is enabled,
 * force {@code shouldCheckMovement} to false so those speed/position checks are skipped.
 */
@Mixin(ServerPlayNetworkHandler.class)
public class ServerPlayNetworkHandlerMixin {
    @Inject(method = "shouldCheckMovement", at = @At("HEAD"), cancellable = true)
    private void mcsow$skipMovementCheck(boolean isFallFlying, CallbackInfoReturnable<Boolean> cir) {
        if (WarsowPmove.isEnabled()) cir.setReturnValue(false);
    }
}
