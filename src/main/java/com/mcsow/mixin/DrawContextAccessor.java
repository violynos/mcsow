package com.mcsow.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.render.state.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link DrawContext}'s package-private {@code state} field (the retained-mode
 * {@link GuiRenderState}) so the strafe HUD can submit custom render elements — e.g. true
 * filled triangles — that batch/sort correctly with the rest of the HUD.
 */
@Mixin(DrawContext.class)
public interface DrawContextAccessor {
    @Accessor("state")
    GuiRenderState mcsow$getState();
}
