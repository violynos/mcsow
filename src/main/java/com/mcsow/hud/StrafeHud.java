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

    private static final double ACCEL_EPS = 0.5;   // Warsow units/tick to count as gain/loss
    private static final double RANGE_DEG = 90.0;  // angle mapped to half the bar width
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
        float viewYaw = p.getYaw();

        int accelColor = accel > ACCEL_EPS ? GAIN : (accel < -ACCEL_EPS ? LOSE : NEUTRAL);

        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();
        int cx = w / 2;
        TextRenderer tr = mc.textRenderer;

        // ---- speed number, centred just below the crosshair ----
        String speedStr = Integer.toString((int) Math.round(speed));
        ctx.drawTextWithShadow(tr, speedStr, cx - tr.getWidth(speedStr) / 2, h / 2 + 12, accelColor);

        // ---- angle bar (velocity direction relative to view) ----
        int barX = cx - BAR_W / 2;
        int barY = h / 2 + 26;
        ctx.fill(barX - 1, barY - 1, barX + BAR_W + 1, barY + BAR_H + 1, BG);
        ctx.fill(cx - 1, barY - 3, cx + 1, barY + BAR_H + 3, TICK); // centre = looking along velocity

        double diff = Double.NaN;
        if (!Double.isNaN(velYaw)) {
            diff = MathHelper.wrapDegrees(velYaw - viewYaw); // −180..180; 0 = aligned
            double clamped = MathHelper.clamp(diff, -RANGE_DEG, RANGE_DEG);
            int ix = cx + (int) Math.round(clamped / RANGE_DEG * (BAR_W / 2.0));
            ctx.fill(ix - 2, barY - 1, ix + 2, barY + BAR_H + 1, accelColor);
        }

        // ---- strafe arrows flanking the crosshair; the one on the side your velocity is
        //      offset toward lights up (bigger with a wider angle), tinted by accel ----
        if (!Double.isNaN(diff)) {
            int size = 3 + (int) Math.round(Math.min(Math.abs(diff), RANGE_DEG) / RANGE_DEG * 7.0);
            int ay = h / 2;
            boolean right = diff > 0;
            int base = right ? cx + 14 : cx - 14 - size;
            for (int i = 0; i < size; i++) {
                // simple triangle: taller toward the tip
                int col = i;
                int x = right ? base + i : base + (size - 1 - i);
                ctx.fill(x, ay - col, x + 1, ay + col + 1, accelColor);
            }
        }
    }
}
