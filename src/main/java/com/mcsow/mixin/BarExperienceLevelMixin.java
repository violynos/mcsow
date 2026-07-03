package com.mcsow.mixin;

import com.mcsow.config.McSowConfig;
import com.mcsow.hud.StrafeHud;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.bar.Bar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When "Speed on XP bar" is enabled, hides the vanilla XP level number so the strafe HUD can draw
 * the speed there instead. The strafe HUD calls the same method to draw the speed, guarded by
 * {@link StrafeHud#renderingSpeedOnXp} so its own draw isn't cancelled.
 */
@Mixin(Bar.class)
public interface BarExperienceLevelMixin {
    @Inject(method = "drawExperienceLevel", at = @At("HEAD"), cancellable = true)
    private static void mcsow$suppressXpLevel(DrawContext ctx, TextRenderer tr, int level, CallbackInfo ci) {
        if (McSowConfig.hudActive() && McSowConfig.get().speedOnXpBar && !StrafeHud.renderingSpeedOnXp) ci.cancel();
    }
}
