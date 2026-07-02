package com.mcsow.mixin;

import com.mcsow.movement.SpecialState;
import com.mcsow.movement.WarsowPmove;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void mcsow$onTravel(Vec3d movementInput, CallbackInfo ci) {
        PlayerEntity self = (PlayerEntity)(Object)this;
        if (!WarsowPmove.isEnabled()) return;
        if (self.isSpectator() || self.getAbilities().flying) return;
        if (self.hasVehicle()) return;
        if (self.isGliding()) return;

        boolean special = SpecialState.isDown(self.getId());
        boolean crouchPressed = self.isSneaking();

        if (self instanceof ClientPlayerEntity cpe) {
            Vec2f mv = cpe.input.getMovementInput();
            float fwdPush = mv.y * WarsowPmove.DEFAULT_PLAYERSPEED;
            float sidePush = mv.x * WarsowPmove.DEFAULT_PLAYERSPEED;
            boolean jumpPressed = self.isJumping();
            WarsowPmove.move(self, special, jumpPressed, crouchPressed, fwdPush, sidePush);
        }
        ci.cancel();
    }
}
