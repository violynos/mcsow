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

        int yMid = h / 2; // the horizon = your (horizontal) movement direction line

        // ---- speed number, centred above the crosshair ----
        String speedStr = Integer.toString((int) Math.round(speed));
        ctx.drawTextWithShadow(tr, speedStr, cx - tr.getWidth(speedStr) / 2, yMid - 24, accelColor);

        // ---- two optimal-angle triangles: horizontal wedges pointing outward, flush with the
        //      horizon; each inner (toward-centre) vertical edge sits at the ideal ±optimal
        //      angle your movement should be at. Keep your velocity marker on that inner edge. ----
        if (!Double.isNaN(optimal) && optimal < 85.0) {
            wedge(ctx, projectX(cx, scale,  optimal), yMid, +1); // right
            wedge(ctx, projectX(cx, scale, -optimal), yMid, -1); // left
        }

        // ---- velocity marker: the on-screen direction you're actually moving, accel-tinted ----
        if (!Double.isNaN(velYaw)) {
            double diff = MathHelper.wrapDegrees(velYaw - viewYaw); // 0 = straight ahead
            if (Math.abs(diff) < 85.0) {
                int vx = projectX(cx, scale, diff);
                ctx.fill(vx - 1, yMid - 8, vx + 2, yMid + 9, accelColor);
            }
        }
    }

    // project a yaw offset (degrees from view centre) to a screen x
    private static int projectX(int cx, double scale, double angleDeg) {
        return (int) Math.round(cx + Math.tan(Math.toRadians(angleDeg)) * scale);
    }

    // horizontal triangle wedge: tall vertical inner edge at baseX (= the optimal angle),
    // tapering to a point `LEN` px outward in direction dir (+1 right / −1 left), centred on
    // yMid so it's flush with the horizon.
    private static void wedge(DrawContext ctx, int baseX, int yMid, int dir) {
        final int LEN = 12, HALF = 6;
        for (int i = 0; i <= LEN; i++) {
            int x = baseX + dir * i;
            int hh = (int) Math.round(HALF * (1.0 - (double) i / LEN));
            ctx.fill(x, yMid - hh, x + 1, yMid + hh + 1, OPTIMAL);
        }
    }
}
