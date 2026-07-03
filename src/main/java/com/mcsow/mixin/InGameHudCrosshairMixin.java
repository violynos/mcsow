package com.mcsow.mixin;

import com.mcsow.config.McSowConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the vanilla crosshair while the strafe HUD is enabled — the HUD draws its own centre
 * marker instead. No resource pack needed; the crosshair returns when the HUD is toggled off.
 */
@Mixin(InGameHud.class)
public class InGameHudCrosshairMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void mcsow$hideCrosshair(DrawContext ctx, RenderTickCounter tick, CallbackInfo ci) {
        if (McSowConfig.hudActive()) ci.cancel();
    }
}
