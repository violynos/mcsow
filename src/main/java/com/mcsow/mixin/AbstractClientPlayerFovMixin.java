package com.mcsow.mixin;

import com.mcsow.movement.WarsowPmove;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the FOV steady while Warsow physics drives movement — vanilla widens the FOV with movement
 * speed (the sprint zoom), but Warsow has no sprint and a steady run speed, so the zoom is
 * misleading. Returns a multiplier of 1.0 (no zoom) only while Warsow is actually in control.
 */
@Mixin(AbstractClientPlayerEntity.class)
public abstract class AbstractClientPlayerFovMixin {
    @Inject(method = "getFovMultiplier", at = @At("HEAD"), cancellable = true)
    private void mcsow$stableFov(boolean b, float tickDelta, CallbackInfoReturnable<Float> cir) {
        if (WarsowPmove.controlsMovement((PlayerEntity) (Object) this)) cir.setReturnValue(1.0f);
    }
}
