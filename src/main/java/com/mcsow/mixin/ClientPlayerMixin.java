package com.mcsow.mixin;

import com.mcsow.movement.SpecialState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void mcsow$updateSpecial(CallbackInfo ci) {
        boolean held = com.mcsow.McSowClientMod.isSpecialDown();
        SpecialState.set(((ClientPlayerEntity)(Object)this).getId(), held);
    }
}
