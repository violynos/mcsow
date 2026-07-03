package com.mcsow.mixin;

import com.mcsow.movement.WarsowPmove;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "Fall damage from the launch height" — like a wind charge. Warsow launches (crouch step-jumps,
 * dash launches, walljumps) can gain a lot of height; fall damage should only count the distance
 * fallen BELOW where you left the ground, not from the peak. Tracks the last settled-ground Y and
 * clamps the fall-damage distance to (lastGroundY - landingY). Players only, while Warsow is enabled.
 *
 * Note: isOnGround/getY/fallDistance are public members inherited from Entity — accessed via a cast
 * rather than @Shadow, since shadowing superclass members isn't reliably remapped in production.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityFallMixin {
    @Unique private double mcsow$lastGroundY;

    // Capture the height only while settled on the ground (not mid-fall/landing), so it holds the
    // pre-flight launch height when the fall damage is computed.
    @Inject(method = "tick", at = @At("HEAD"))
    private void mcsow$trackGroundHeight(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.isOnGround() && self.fallDistance < 0.5) mcsow$lastGroundY = self.getY();
    }

    @ModifyVariable(method = "computeFallDamage", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private double mcsow$fallFromLaunchHeight(double dist) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!WarsowPmove.isEnabled() || !(self instanceof PlayerEntity)) return dist;
        return Math.max(0.0, Math.min(dist, mcsow$lastGroundY - self.getY()));
    }
}
