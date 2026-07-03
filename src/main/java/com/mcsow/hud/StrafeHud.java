package com.mcsow.hud;

import com.mcsow.config.McSowConfig;
import com.mcsow.mixin.DrawContextAccessor;
import com.mcsow.movement.WarsowPmove;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;

/**
 * Strafe HUD overlay (recreating the Warfork strafe HUD, element by element). GUI scaling is
 * removed — everything is drawn in raw physical framebuffer pixels, unaffected by the GUI Scale
 * option. Client-only; registered from the client initializer.
 */
public final class StrafeHud {
    private static final Identifier ID = Identifier.of("mcsow", "strafe_hud");

    // ---- velocity bar (green): where the player is actually moving on screen ----
    private static final int BAR_W = 7;          // px wide
    private static final int BAR_H = 36;         // px tall
    private static final int BAR_TOP = 107;      // px from the top of the screen to the bar's top pixel
    private static final int BAR_COLOR = 0x8000FF00; // green, ~50% alpha

    // ---- look-direction bar (white, black-outlined): fixed at screen centre, never moves ----
    private static final int LOOK_W = 2;         // px wide (white part)
    private static final int LOOK_H = 36;        // px tall
    private static final int LOOK_GAP = 19;      // px gap above the centre 2×2 square (excludes the outline)
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;

    // ---- optimal-strafe zone: two right triangles, one either side of the velocity. Each has its
    //      90° corner at the bottom, a top point directly above it, and its pivot (scale anchor) at
    //      the far outer bottom corner. The inner (flat) vertical edge marks the optimal strafe
    //      angle — it sits at velocity ± optimal, so the triangle scales around its pivot. ----
    private static final int TRI_W = 476;         // px wide (each, inner edge → pivot at optimal=0)
    private static final int TRI_H = 16;          // px tall
    private static final int TRI_WHITE = 0x80FFFFFF; // default (~50% alpha)
    private static final int TRI_GREEN = 0x8000FF00; // lit: quakestrafe + crosshair on this triangle

    // ---- speed number ----
    private static final int NUM_HEIGHT = 24;     // px tall (digit glyph height)
    private static final int NUM_BOTTOM_OFF = 46; // px from the screen bottom to the number's bottom
    private static final float BASE_DIGIT_H = 7f;  // vanilla font digit glyph height at scale 1

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
        double accel = WarsowPmove.getHudAccel(p);            // speed change since last tick (>0 gaining)
        double velYaw = WarsowPmove.getHudVelYaw(p);          // NaN when ~still
        double optimal = WarsowPmove.getHudOptimalAngle(p);   // optimal strafe angle (deg), 0 below ~450 speed
        boolean quakestrafe = WarsowPmove.getHudQuakestrafe(p); // diagonal (forward + strafe) inputs
        float viewYaw = p.getYaw();

        // ---- GUI scaling removed: undo Minecraft's GUI Scale so this HUD renders 1:1 with the
        //      physical framebuffer. All coordinates below are in physical pixels. ----
        int sf = mc.getWindow().getScaleFactor();
        int w = ctx.getScaledWindowWidth() * sf;   // physical framebuffer width
        int h = ctx.getScaledWindowHeight() * sf;  // physical framebuffer height
        int cx = w / 2;

        // FOV pinhole projection: map a yaw offset (deg from view centre) to a screen x, so the
        // bar lands where that world direction actually appears on screen.
        double vfov = mc.options.getFov().getValue();
        double scale = (h / 2.0) / Math.tan(Math.toRadians(vfov) / 2.0);

        int midX = w / 2;
        int midY = h / 2;
        int squareTop = midY - 1;                // top edge of the 2×2 square at the exact screen centre
        int lookBottom = squareTop - LOOK_GAP;   // bottom edge of the white look bar
        int lookTop = lookBottom - LOOK_H;       // top edge of the white look bar

        var matrices = ctx.getMatrices();
        matrices.pushMatrix();
        matrices.scale(1.0f / sf, 1.0f / sf); // cancel the GUI scale → 1 unit == 1 physical pixel

        // ---- velocity-anchored elements: the strafe-zone triangles and the green velocity bar
        //      sit at the on-screen player-velocity direction ----
        if (!Double.isNaN(velYaw)) {
            double diff = MathHelper.wrapDegrees(velYaw - viewYaw); // 0 = straight ahead
            if (Math.abs(diff) < 85.0) { // in front of the view (avoids the tan() blow-up behind you)
                int vx = projectX(cx, scale, diff);

                // Two right triangles, vertical edge centred on the white look bar's centre. Each
                // inner (flat) vertical edge sits at velocity ± the optimal strafe angle — it IS
                // the optimal-angle marker. The pivot (far outer bottom corner) is anchored at
                // velocity ± TRI_W, so the triangle scales around it: the inner edges meet at
                // velocity when optimal = 0 and separate (opening the optimal zone) as you speed up.
                int lookCenter = (lookTop + lookBottom) / 2;
                int apexY = lookCenter - TRI_H / 2;
                int baseY = lookCenter + TRI_H / 2;
                // inner edge x = velocity ± optimal (projected); clamp so it can squish down to
                // zero width against the pivot but never cross it (which would flip the triangle).
                int pivotR = vx + TRI_W, pivotL = vx - TRI_W;
                int xRight = Math.min(projectX(cx, scale, diff + optimal), pivotR);
                int xLeft  = Math.max(projectX(cx, scale, diff - optimal), pivotL);
                // green when holding quakestrafe AND speed increased since last tick (accelerating);
                // white otherwise.
                int triColor = (quakestrafe && accel > 0.0) ? TRI_GREEN : TRI_WHITE;
                triangle(ctx, xRight, apexY, xRight, baseY, pivotR, baseY, triColor); // right
                triangle(ctx, xLeft,  apexY, xLeft,  baseY, pivotL, baseY, triColor); // left

                // green velocity bar (7×36, top 107px from screen top)
                int left = vx - BAR_W / 2;
                ctx.fill(left, BAR_TOP, left + BAR_W, BAR_TOP + BAR_H, BAR_COLOR);
            }
        }

        // ---- look-direction bar: fixed at screen centre, 2×36 white with a 1px black outline,
        //      19px above the centre square (the outline is drawn outside that 19px gap) ----
        ctx.fill(midX - LOOK_W / 2 - 1, lookTop - 1, midX + LOOK_W / 2 + 1, lookBottom + 1, BLACK); // outline
        ctx.fill(midX - LOOK_W / 2,     lookTop,     midX + LOOK_W / 2,     lookBottom,     WHITE); // bar

        // ---- speed number: 24px-tall digits, centred horizontally, bottom 46px off the screen
        //      bottom. Vanilla digits are 7px tall, so scale by 24/7 via a nested matrix. ----
        String speedStr = Integer.toString((int) Math.round(speed));
        float numScale = NUM_HEIGHT / BASE_DIGIT_H;
        float textW = mc.textRenderer.getWidth(speedStr) * numScale;
        float numTop = (h - NUM_BOTTOM_OFF) - NUM_HEIGHT;
        matrices.pushMatrix();
        matrices.translate(midX - textW / 2f, numTop);
        matrices.scale(numScale, numScale);
        ctx.drawTextWithShadow(mc.textRenderer, speedStr, 0, 0, WHITE);
        matrices.popMatrix();

        matrices.popMatrix();
    }

    // project a yaw offset (degrees from view centre) to a screen x
    private static int projectX(int cx, double scale, double angleDeg) {
        return (int) Math.round(cx + Math.tan(Math.toRadians(angleDeg)) * scale);
    }

    // Draw a true filled triangle (A,B,C) in screen pixels. The 1.21.11 GUI is retained-mode and
    // has no triangle primitive, so we submit a custom render element into DrawContext's
    // GuiRenderState, reusing the engine's GUI pipeline (POSITION_COLOR, quad-mode) and emitting
    // the triangle as a degenerate quad A,B,C,C. Batched/sorted with the rest of the HUD. Shared
    // primitive for the angled elements still to come.
    static void triangle(DrawContext ctx,
            float ax, float ay, float bx, float by, float cx, float cy, int argb) {
        // Normalise winding so the GUI pipeline's back-face cull never drops the triangle,
        // regardless of the order the caller passed A,B,C: keep a consistent orientation by
        // swapping B and C when the signed area is the wrong sign.
        boolean flip = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax) > 0;
        final float bX = flip ? cx : bx, bY = flip ? cy : by;
        final float cX = flip ? bx : cx, cY = flip ? by : cy;

        Matrix3x2f m = new Matrix3x2f(ctx.getMatrices()); // snapshot: the stack is reused after this call
        // bounds must be in real screen space (post-transform), else retained-mode culling can
        // clip the element when a scale/translate is active on the matrix stack.
        Vector2f ta = m.transformPosition(new Vector2f(ax, ay));
        Vector2f tb = m.transformPosition(new Vector2f(bX, bY));
        Vector2f tc = m.transformPosition(new Vector2f(cX, cY));
        int minX = (int) Math.floor(Math.min(ta.x, Math.min(tb.x, tc.x)));
        int minY = (int) Math.floor(Math.min(ta.y, Math.min(tb.y, tc.y)));
        int maxX = (int) Math.ceil(Math.max(ta.x, Math.max(tb.x, tc.x)));
        int maxY = (int) Math.ceil(Math.max(ta.y, Math.max(tb.y, tc.y)));
        ScreenRect bounds = new ScreenRect(minX, minY, maxX - minX, maxY - minY);

        GuiRenderState state = ((DrawContextAccessor) (Object) ctx).mcsow$getState();
        state.addSimpleElement(new SimpleGuiElementRenderState() {
            @Override public RenderPipeline pipeline()     { return RenderPipelines.GUI; }
            @Override public TextureSetup   textureSetup() { return TextureSetup.empty(); }
            @Override public ScreenRect     scissorArea()  { return null; }
            @Override public ScreenRect     bounds()       { return bounds; }
            @Override public void setupVertices(VertexConsumer vc) {
                vc.vertex(m, ax, ay).color(argb);
                vc.vertex(m, bX, bY).color(argb);
                vc.vertex(m, cX, cY).color(argb);
                vc.vertex(m, cX, cY).color(argb); // degenerate 4th vertex = filled triangle
            }
        });
    }
}
