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
    private static final int OPTIMAL = 0xFFFFD21E; // amber — optimal-angle markers
    private static final double ACCEL_EPS = 0.5;   // Warsow units/tick to count as gain/loss

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
        TextRenderer tr = mc.textRenderer;

        // pinhole projection of a yaw offset (deg from view) to a screen x, using the
        // vertical FOV — so markers land where that world direction actually appears.
        double vfov = mc.options.getFov().getValue();
        double scale = (h / 2.0) / Math.tan(Math.toRadians(vfov) / 2.0);

        int markTop = h / 2 + 8, markBot = h / 2 + 18;

        // ---- speed number, centred just below the crosshair ----
        String speedStr = Integer.toString((int) Math.round(speed));
        ctx.drawTextWithShadow(tr, speedStr, cx - tr.getWidth(speedStr) / 2, h / 2 - 22, accelColor);

        // ---- view reference tick at centre ----
        ctx.fill(cx, markTop - 2, cx + 1, markBot + 2, 0x66FFFFFF);

        // ---- optimal-angle triangles: draw where the velocity WOULD point on screen if you
        //      were at the ideal ±optimal angle (aim your velocity marker onto one) ----
        if (!Double.isNaN(optimal) && optimal < 85.0) {
            triangleDown(ctx, projectX(cx, scale, -optimal), markTop);
            triangleDown(ctx, projectX(cx, scale,  optimal), markTop);
        }

        // ---- velocity marker: drawn at the on-screen direction you're actually moving ----
        if (!Double.isNaN(velYaw)) {
            double diff = MathHelper.wrapDegrees(velYaw - viewYaw); // −180..180; 0 = straight ahead
            if (Math.abs(diff) < 85.0) { // in front of you (projectable)
                int vx = projectX(cx, scale, diff);
                ctx.fill(vx - 1, markTop, vx + 2, markBot, accelColor);
            }
        }
    }

    // project a yaw offset (degrees from view centre) to a screen x, clamped on-screen
    private static int projectX(int cx, double scale, double angleDeg) {
        double x = cx + Math.tan(Math.toRadians(angleDeg)) * scale;
        return (int) Math.round(x);
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
