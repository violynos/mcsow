package com.mcsow.hud;

import com.mcsow.config.McSowConfig;
import com.mcsow.movement.WarsowPmove;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/**
 * Strafe HUD overlay (inspired by the Warfork strafe HUD). Shows the player's horizontal
 * speed, a bar of the velocity-vs-view angle, and left/right strafe arrows — all tinted
 * green when gaining speed, red when losing it. Speed is in Warsow units (a dash ≈ 450,
 * matching Warfork). Client-only; registered from the client initializer.
 */
public final class StrafeHud {
    private static final Identifier ID = Identifier.of("mcsow", "strafe_hud");

    // colours (ARGB)
    private static final int GAIN    = 0xFF43D047; // green — accelerating
    private static final int LOSE    = 0xFFE04343; // red — decelerating
    private static final int NEUTRAL = 0xFFDDDDDD; // ~white — steady
    private static final int BG      = 0x90000000;
    private static final int TICK    = 0xFFFFFFFF;

    private static final int    OPTIMAL = 0xFFFFD21E; // amber — optimal-angle markers
    private static final double ACCEL_EPS = 0.5;   // Warsow units/tick to count as gain/loss
    private static final double RANGE_DEG = 30.0;  // angle mapped to half the bar width
    private static final int    BAR_W = 180;
    private static final int    BAR_H = 6;

    private StrafeHud() {}

    public static void register() {
        HudElementRegistry.addLast(ID, StrafeHud::render);
    }

    private static void render(DrawContext ctx, RenderTickCounter tick) {
        if (!McSowConfig.get().strafeHud) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.options.hudHidden) return;

        double speed = WarsowPmove.getHudSpeed(p);
        double accel = WarsowPmove.getHudAccel(p);
        double velYaw = WarsowPmove.getHudVelYaw(p);
        double optimal = WarsowPmove.getHudOptimalAngle(p);
        float viewYaw = p.getYaw();

        int accelColor = accel > ACCEL_EPS ? GAIN : (accel < -ACCEL_EPS ? LOSE : NEUTRAL);

        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();
        int cx = w / 2;
        int barX = cx - BAR_W / 2;
        int barY = h / 2 + 26;
        double halfW = BAR_W / 2.0;
        TextRenderer tr = mc.textRenderer;

        // ---- speed number, centred just below the crosshair ----
        String speedStr = Integer.toString((int) Math.round(speed));
        ctx.drawTextWithShadow(tr, speedStr, cx - tr.getWidth(speedStr) / 2, h / 2 + 12, accelColor);

        // ---- angle bar; centre = looking straight along your velocity ----
        ctx.fill(barX - 1, barY - 1, barX + BAR_W + 1, barY + BAR_H + 1, BG);
        ctx.fill(cx - 1, barY - 3, cx + 1, barY + BAR_H + 3, TICK);

        // ---- optimal-angle triangles at ±optimal (aim your velocity marker onto one) ----
        if (!Double.isNaN(optimal)) {
            int off = (int) Math.round(Math.min(optimal, RANGE_DEG) / RANGE_DEG * halfW);
            triangleDown(ctx, cx - off, barY - 2);
            triangleDown(ctx, cx + off, barY - 2);
        }

        // ---- velocity direction marker relative to view, tinted by accel ----
        if (!Double.isNaN(velYaw)) {
            double diff = MathHelper.wrapDegrees(velYaw - viewYaw); // −180..180; 0 = aligned
            double clamped = MathHelper.clamp(diff, -RANGE_DEG, RANGE_DEG);
            int ix = cx + (int) Math.round(clamped / RANGE_DEG * halfW);
            ctx.fill(ix - 1, barY - 1, ix + 1, barY + BAR_H + 1, accelColor);
        }
    }

    // small downward-pointing triangle whose tip sits at (px, tipY)
    private static void triangleDown(DrawContext ctx, int px, int tipY) {
        int size = 4;
        for (int r = 0; r < size; r++) {
            int half = size - r;      // widest at top, tip at bottom
            int y = tipY - size + r;
            ctx.fill(px - half, y, px + half, y + 1, OPTIMAL);
        }
    }
}
