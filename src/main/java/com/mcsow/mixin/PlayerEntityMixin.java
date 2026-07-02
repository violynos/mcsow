package com.mcsow.mixin;

import com.mcsow.config.McSowConfig;
import com.mcsow.movement.SpecialState;
import com.mcsow.movement.WarsowPmove;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec2f;
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

        // Situations where Warsow movement should NOT run and vanilla handles motion
        // (creative fly, elytra glide, spectator, riding a vehicle, or mod disabled).
        boolean vanillaControls = !WarsowPmove.isEnabled()
            || self.isSpectator()
            || self.getAbilities().flying
            || self.hasVehicle()
            || self.isGliding();

        if (self instanceof ClientPlayerEntity cpe) {
            if (vanillaControls) {
                // Keep our internal velocity in sync with the player's real velocity so
                // momentum carries over seamlessly when we resume control (otherwise
                // landing/stopping an elytra or fly for a frame zeroes your speed).
                WarsowPmove.syncFromActual(self);
                return;
            }
            boolean special = SpecialState.isDown(self.getId());
            boolean crouchPressed = self.isSneaking();
            Vec2f mv = cpe.input.getMovementInput();
            // pass raw input (-1..1); move() scales by the player's dynamic max speed
            boolean jumpPressed = self.isJumping();
            WarsowPmove.move(self, special, jumpPressed, crouchPressed, mv.y, mv.x);
            ci.cancel();
            return;
        }

        // Non-client players (e.g. the integrated server's copy): keep cancelling
        // travel so only the client runs physics — unless vanilla should control.
        if (vanillaControls) return;
        ci.cancel();
    }
}
