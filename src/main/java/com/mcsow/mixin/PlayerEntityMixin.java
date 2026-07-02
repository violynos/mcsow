package com.mcsow.mixin;

import com.mcsow.config.McSowConfig;
import com.mcsow.movement.WarsowPmove;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
    // Scale all exhaustion (hunger drain) by the configured multiplier. addExhaustion
    // no-ops on the client (isClient check inside), so this only affects the authoritative
    // server-side hunger. Covers vanilla movement/jump exhaustion.
    @ModifyVariable(method = "addExhaustion(F)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float mcsow$scaleExhaustion(float exhaustion) {
        return exhaustion * (float) McSowConfig.get().hungerMultiplier;
    }

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void mcsow$onTravel(Vec3d movementInput, CallbackInfo ci) {
        PlayerEntity self = (PlayerEntity)(Object)this;

        // Client physics runs in ClientPlayerEntityTravelMixin — let it handle it.
        if (self.getEntityWorld().isClient()) return;

        // Server: only cancel travel so the client's physics is authoritative.
        // If vanilla should control, do nothing.
        boolean vanillaControls = !WarsowPmove.isEnabled()
            || self.isSpectator()
            || self.getAbilities().flying
            || self.hasVehicle()
            || self.isGliding();
        if (vanillaControls) return;
        ci.cancel();
    }
}
