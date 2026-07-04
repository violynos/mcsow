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
import net.minecraft.client.gui.hud.bar.Bar;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;

/**
 * Strafe HUD overlay (recreating the Warfork strafe HUD, element by element). GUI scaling is
 * removed — everything is drawn in raw physical framebuffer pixels, unaffected by the GUI Scale
 * option. Client-only; registered from the client initializer.
 */
public final class StrafeHud {
    private static final Identifier ID = Identifier.of("mcsow", "strafe_hud");
    private static final int REF_HEIGHT = 1080; // all HUD sizes are tuned in 1080p pixels, then scaled
    private static final Identifier SPEED_FONT = Identifier.of("mcsow", "geo"); // speed-display font

    // true only while WE draw the speed into the XP-level slot, so the Bar mixin lets it through
    // (it cancels the vanilla level draw when "Speed on XP bar" is on).
    public static boolean renderingSpeedOnXp = false;

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
    private static final double TRI_MIN_SPEED = 430; // hide the triangles below this speed
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
        if (!McSowConfig.hudActive()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.options.hudHidden) return;

        double accel = WarsowPmove.getHudAccel(p);            // speed change since last tick (>0 gaining)
        boolean quakestrafe = WarsowPmove.getHudQuakestrafe(p); // diagonal (forward + strafe) inputs
        float viewYaw = p.getYaw();

        // Interpolate the velocity between ticks by the frame's tick progress so the HUD moves
        // smoothly at the render framerate instead of snapping at 20 Hz. Speed, direction and the
        // optimal angle are all derived from this displayed velocity.
        Vec3d dvel = WarsowPmove.getHudDisplayVelocity(p, tick.getTickProgress(false));
        double hSpeedSq = dvel.x * dvel.x + dvel.z * dvel.z;
        double speed = Math.sqrt(hSpeedSq);
        double velYaw = hSpeedSq < 1.0 ? Double.NaN : Math.toDegrees(Math.atan2(-dvel.x, dvel.z));
        double optimal = WarsowPmove.strafeOptimalAngle(speed);

        // ---- resolution-independent layout: GUI Scale is bypassed, and all coordinates below are
        //      in 1080p-REFERENCE pixels. The whole HUD is scaled to the real screen height at the
        //      end, so it looks identical at any resolution (1080p = 1:1, everything tuned there). ----
        int sf = mc.getWindow().getScaleFactor();
        int pw = ctx.getScaledWindowWidth() * sf;   // physical framebuffer width
        int ph = ctx.getScaledWindowHeight() * sf;  // physical framebuffer height
        double uiScale = ph / (double) REF_HEIGHT;   // 1080p reference; bigger screens scale up
        int w = (int) Math.round(pw / uiScale);      // reference-space width (== pw at 1080p)
        int h = REF_HEIGHT;                          // reference-space height (always 1080)
        int cx = w / 2;

        // FOV pinhole projection: map a yaw offset (deg from view centre) to a reference-space x,
        // so the bar lands where that world direction actually appears on screen.
        double vfov = mc.options.getFov().getValue();
        double scale = (h / 2.0) / Math.tan(Math.toRadians(vfov) / 2.0);

        int midX = w / 2;
        int midY = h / 2;
        int squareTop = midY - 1;                // top edge of the 2×2 square at the exact screen centre
        int lookBottom = squareTop - LOOK_GAP;   // bottom edge of the white look bar
        int lookTop = lookBottom - LOOK_H;       // top edge of the white look bar

        // ---- speed on the XP bar: draw the speed as the vanilla XP level number, in scaled GUI
        //      coords (before the physical-pixel transform below). The Bar mixin hides the real
        //      level while this is on; the flag lets our own draw through. ----
        boolean speedOnXp = McSowConfig.get().speedOnXpBar;
        if (speedOnXp) {
            renderingSpeedOnXp = true;
            Bar.drawExperienceLevel(ctx, mc.textRenderer, (int) Math.round(speed));
            renderingSpeedOnXp = false;
        }

        var matrices = ctx.getMatrices();
        matrices.pushMatrix();
        // reference px → physical (× uiScale), then physical → GUI-projection space (÷ sf)
        matrices.scale((float) (uiScale / sf), (float) (uiScale / sf));

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
                // Hidden below TRI_MIN_SPEED (no meaningful strafe zone at low speed).
                if (speed >= TRI_MIN_SPEED) {
                    int lookCenter = (lookTop + lookBottom) / 2;
                    int apexY = lookCenter - TRI_H / 2;
                    int baseY = lookCenter + TRI_H / 2;
                    // inner edge x = velocity ± optimal (projected); clamp so it can squish down to
                    // zero width against the pivot but never cross it (which would flip the triangle).
                    // scale the width inversely with FOV (horizontal only) so zooming in — which
                    // spreads the same angle over more pixels — doesn't shrink the triangle past the
                    // optimal marker. 110 (Quake Pro) = base width; 55 (half FOV) = double width.
                    int triW = (int) Math.round(TRI_W * (110.0 / vfov));
                    int pivotR = vx + triW, pivotL = vx - triW;
                    int xRight = Math.min(projectX(cx, scale, diff + optimal), pivotR);
                    int xLeft  = Math.max(projectX(cx, scale, diff - optimal), pivotL);
                    // green when holding quakestrafe AND speed increased since last tick
                    // (accelerating); white otherwise.
                    int triColor = (quakestrafe && accel > 0.0) ? TRI_GREEN : TRI_WHITE;
                    triangle(ctx, xRight, apexY, xRight, baseY, pivotR, baseY, triColor); // right
                    triangle(ctx, xLeft,  apexY, xLeft,  baseY, pivotL, baseY, triColor); // left
                }

                // green velocity bar (7×36, top 107px from screen top)
                int left = vx - BAR_W / 2;
                ctx.fill(left, BAR_TOP, left + BAR_W, BAR_TOP + BAR_H, BAR_COLOR);
            }
        }

        // ---- centre marker (replaces the vanilla crosshair): a white 2×2 with a 1px black
        //      outline on the four sides but not the corners — a black 4×2 + 2×4 plus behind a
        //      white 2×2. ----
        ctx.fill(midX - 2, midY - 1, midX + 2, midY + 1, BLACK); // horizontal arm (4×2)
        ctx.fill(midX - 1, midY - 2, midX + 1, midY + 2, BLACK); // vertical arm (2×4)
        ctx.fill(midX - 1, midY - 1, midX + 1, midY + 1, WHITE); // white 2×2 centre

        // ---- look-direction bar: fixed at screen centre, 2×36 white with a 1px black outline,
        //      19px above the centre square (the outline is drawn outside that 19px gap) ----
        ctx.fill(midX - LOOK_W / 2 - 1, lookTop - 1, midX + LOOK_W / 2 + 1, lookBottom + 1, BLACK); // outline
        ctx.fill(midX - LOOK_W / 2,     lookTop,     midX + LOOK_W / 2,     lookBottom,     WHITE); // bar

        // ---- speed number: Geo font, centred horizontally, bottom 46px off the screen bottom.
        //      Skipped when the speed is shown on the XP bar instead. ----
        if (!speedOnXp) {
            Text speedText = Text.literal(Integer.toString((int) Math.round(speed)))
                    .setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(SPEED_FONT)));
            float numScale = NUM_HEIGHT / BASE_DIGIT_H;
            float textW = mc.textRenderer.getWidth(speedText) * numScale;
            float numTop = (h - NUM_BOTTOM_OFF) - NUM_HEIGHT;
            matrices.pushMatrix();
            matrices.translate(midX - textW / 2f, numTop);
            matrices.scale(numScale, numScale);
            ctx.drawTextWithShadow(mc.textRenderer, speedText, 0, 0, WHITE);
            matrices.popMatrix();
        }

        matrices.popMatrix();
    }

    // project a yaw offset (degrees from view centre) to a screen x. Clamp just under ±90°:
    // past 90° tan() flips sign and returns a huge NEGATIVE x, which slips past the min/max-
    // with-pivot clamps and stretches the triangle across the screen (looking sideways at high
    // speed, where diff±optimal crosses 90°). Clamped, the edge stays far off-screen on the
    // correct side, so the pivot clamp collapses the triangle to zero width instead.
    private static int projectX(int cx, double scale, double angleDeg) {
        double a = Math.max(-89.0, Math.min(89.0, angleDeg));
        return (int) Math.round(cx + Math.tan(Math.toRadians(a)) * scale);
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
