package com.mcsow.mixin;

import com.mcsow.movement.SpecialState;
import com.mcsow.movement.WarsowPmove;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(PlayerEntity.class)
public abstract class ClientPlayerEntityTravelMixin {
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void mcsow$onTravel(Vec3d movementInput, CallbackInfo ci) {
        PlayerEntity self = (PlayerEntity)(Object)this;
        if (!(self instanceof ClientPlayerEntity cpe)) return;

        boolean vanillaControls = !WarsowPmove.isEnabled()
            || self.isSpectator()
            || self.getAbilities().flying
            || self.hasVehicle()
            || self.isGliding()
            || self.isClimbing(); // ladders/vines: let vanilla handle climbing

        if (vanillaControls) {
            WarsowPmove.syncFromActual(self);
            return;
        }

        boolean special = SpecialState.isDown(self.getId());
        boolean crouchPressed = self.isSneaking();
        Vec2f mv = cpe.input.getMovementInput();
        boolean jumpPressed = self.isJumping();
        WarsowPmove.move(self, special, jumpPressed, crouchPressed, mv.y, mv.x);
        ci.cancel();
    }
}
