package com.mcsow.movement;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public final class WarsowPmove {
    private static boolean enabled = true;

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean e) { enabled = e; }

    // Copy user-tunable movement values from config into the live fields.
    public static void applyConfig(com.mcsow.config.McSowConfig.Data d) {
        GRAVITY           = d.gravity;
        DEFAULT_JUMPSPEED = d.jumpSpeed;
        DEFAULT_DASHSPEED = d.dashSpeed;
        PM_DASHUPSPEED    = d.dashUpSpeed;
        PM_WJUPSPEED      = d.wallJumpUpSpeed;
        PM_AIRACCELERATE  = d.airAccelerate;
        PM_AIRCONTROL     = d.airControl;
        AIR_SUBSTEPS      = Math.max(1, d.airSubsteps);
        CROUCH_JUMP_RATIO = d.crouchJumpRatio;
    }

    // === Warsow constants (exact values from gs_pmove.cpp) ===
    // All velocities in Warsow units/sec; multiplied by UNIT_SCALE * FT at output.

    private static final float FT = 0.05f; // 20 ticks/sec
    private static final float UNIT_SCALE = 0.01875f; // 1 Warsow unit → MC blocks

    // water movement
    private static final float WATER_GRAVITY_SCALE  = 0.5f;  // half gravity while in water (no friction on it)
    private static final float WATER_FRICTION_SCALE = 0.1f;  // water friction = ground friction × 0.1
    private static float       WATER_UPSPEED        = 280.0f; // up-boost from jump/dash held in water (friction-limited)

    // config-tunable (see McSowConfig / applyConfig); GRAVITY_COMPENSATE is fixed at 1.4
    private static float GRAVITY                  = 1120.0f;
    private static final float GRAVITY_SCALE      = 1.0f; // no scaling, raw Warsow
    private static final float GRAVITY_COMPENSATE = 1120.0f / 800.0f; // = 1.4

    // speeds
    public static final float DEFAULT_PLAYERSPEED   = 320.0f;
    public static final float DEFAULT_WALKSPEED     = 160.0f;
    public static final float DEFAULT_CROUCHEDSPEED = 100.0f;
    public static float DEFAULT_JUMPSPEED           = 280.0f * GRAVITY_COMPENSATE; // config-tunable
    public static float DEFAULT_DASHSPEED           = 450.0f; // minimum dash speed (config-tunable)

    // crouch-jump: fraction of horizontal speed converted to vertical (rest kept). (config-tunable)
    private static float CROUCH_JUMP_RATIO          = 0.75f;


    // friction / acceleration
    private static final float PM_FRICTION         = 8.0f;
    private static final float PM_ACCELERATE       = 12.0f;
    private static float PM_AIRACCELERATE          = 1.075f; // Warsow 1.0, tuned ×1.075 (config-tunable)
    private static final float PM_AIRDECELERATE    = 2.0f;
    private static final float PM_DECELERATE       = 12.0f;
    private static final float PM_WATERFRICTION    = 1.0f;
    private static final float PM_WATERACCELERATE  = 10.0f;

    // air control (Warsow gs_pmove.c "Air Control" mode: PMFEAT_AIRCONTROL, no FWDBUNNY)
    private static float PM_AIRCONTROL               = 150.0f; // inertia→forward conversion (config-tunable)
    private static final float PM_STRAFE_BUNNY_ACCEL = 70.0f;  // accel when +strafe (side only)
    private static final float PM_WISHSPEED          = 30.0f;  // clamp for +strafe wishspeed

    // Sub-steps per 20 Hz MC tick for the air integration, to approximate
    // Warsow's higher physics tick rate (air-strafe/air-control resolve finer).
    private static int         AIR_SUBSTEPS          = 3; // config-tunable

    // dash
    public static final int    PM_DASHJUMP_TIMEDELAY        = 1000;
    public static final int    PM_SPECIAL_CROUCH_INHIBIT    = 400;
    public static final int    PM_AIRCONTROL_BOUNCE_DELAY   = 200;
    public static final int    PM_CROUCHSLIDE_TIMEDELAY     = 700;
    public static final int    PM_CROUCHSLIDE_FADE          = 500;
    public static final int    PM_CROUCHSLIDE_CONTROL       = 3;
    public static final int    CROUCHTIME                   = 100;

    private static float PM_DASHUPSPEED             = 174.0f * 1.15f * GRAVITY_COMPENSATE; // dash height ×1.15 (config-tunable)
    private static final float PM_OVERBOUNCE         = 1.01f;
    private static final float STEPSIZE              = 18.0f;
    private static final float SPEEDKEY              = 500.0f;

    // walljump (Warsow gs_pmove.c PM_CheckWallJump, non-OLDWALLJUMP path)
    public static final int    PM_WALLJUMP_TIMEDELAY = PM_DASHJUMP_TIMEDELAY; // own cooldown, same length as dash
    private static float PM_WJUPSPEED                = 330.0f * 1.09f * GRAVITY_COMPENSATE; // walljump up-boost ×1.09 (config-tunable)
    private static final float PM_WJBOUNCEFACTOR     = 0.3f;  // outward push along wall normal
    private static final float PM_WJMINSPEED         = (DEFAULT_WALKSPEED + DEFAULT_PLAYERSPEED) * 0.5f; // 240
    // proximity (MC blocks) used by the wall-momentum buffer to test if a wall cleared
    private static final double WJ_WALL_PROBE        = 0.12;
    // walljump wall detection: 9 x-z footprint planes at feet→2.0 in 0.25 steps; a
    // predicted next-frame collision at any plane + velocity heading into the wall
    // (angle < 89°) makes the player eligible for a walljump next frame.
    private static final int    WJ_PLANES            = 9;
    private static final double WJ_PLANE_SPACING     = 0.25;
    private static final double WJ_ANGLE_COS         = Math.cos(Math.toRadians(89.0));

    // wall-momentum buffer: frames to keep a wall-clamped speed and restore it if the
    // clamped direction opens up (corner-skip) or a crouch-jump happens
    private static final int    WALL_BUFFER_FRAMES   = 2;

    // max ledge height (MC blocks) we auto-step up onto (0.6 = MC's default step height),
    // both grounded and mid-air
    private static final double STEP_UP_HEIGHT        = 0.6;
    // how far below the feet a surface may be for step-up to apply (so it works mid-air)
    private static final double STEP_GROUND_DROP     = 1.0;

    private static final java.util.Map<Integer, PlayerMoveState> STATES = new java.util.HashMap<>();

    private static class PlayerMoveState {
        Vec3d velocity = Vec3d.ZERO;
        boolean jumpHeld;
        boolean specialHeld;
        int dashTime;
        boolean dashing;
        int walljumpTime;
        boolean walljumpCount;   // already walljumped since leaving this wall/ground
        boolean walljumping;     // in the launch window (until apex or ground)
        boolean wallEligible;    // last-frame detection: a wall collision is imminent next frame
        Vec3d wallNormal = Vec3d.ZERO;   // away-from-wall unit direction of the detected wall
        Vec3d wallVelocity = Vec3d.ZERO; // velocity going into the wall (pre-collision), for the launch
        double wallSaveX;        // speed clamped by a wall on X, buffered for restore
        double wallSaveZ;
        int wallBufferX;         // frames left to restore the clamped X speed
        int wallBufferZ;
        boolean forceGround;     // step-up just happened → treat next tick as grounded
        int crouchTime;
        int crouchSlideTime;
        boolean crouchSliding;
        boolean wasInAir;
        boolean jumped;
    }

    private static PlayerMoveState state(PlayerEntity p) {
        return STATES.computeIfAbsent(p.getId(), k -> new PlayerMoveState());
    }

    public static void clear(PlayerEntity p) {
        STATES.remove(p.getId());
    }

    // Keep our internal (Warsow-unit) velocity aligned with the player's real MC
    // velocity. Called while vanilla controls motion (creative fly, elytra) so that
    // when Warsow movement resumes, momentum is preserved instead of snapping to a
    // stale value. MC velocity is blocks/tick; invert the output scaling to undo it.
    public static void syncFromActual(PlayerEntity player) {
        PlayerMoveState s = state(player);
        Vec3d mcVel = player.getVelocity();
        double k = 1.0 / (FT * UNIT_SCALE); // blocks/tick → Warsow units/sec
        s.velocity = new Vec3d(mcVel.x * k, mcVel.y * k, mcVel.z * k);
    }

    // ================================================================
    //  MAIN ENTRY
    // ================================================================
    public static void move(PlayerEntity player, boolean specialKeyDown,
                             boolean jumpPressed, boolean crouchPressed,
                             float fwdInput, float sideInput) {
        PlayerMoveState s = state(player);
        Vec3d vel = s.velocity;

        // ---- on-ground (checked first, before anything) ----
        // a step-up last tick forces us grounded this tick (holding jump overrides it
        // by jumping off, via checkJump + the airborne recheck below)
        boolean onGround = player.isOnGround() || s.forceGround;
        s.forceGround = false;
        boolean justLanded = onGround && s.wasInAir;

        // ---- water state ----
        boolean inWater = player.isTouchingWater();
        boolean submerged = player.isSubmergedInWater();

        // ---- reset vertical speed on ground contact (not just on jump/dash) ----
        if (!inWater && onGround && vel.y < 0) vel = new Vec3d(vel.x, 0, vel.z);

        // ---- dynamic speed/jump from MC modifiers (Speed, Soul Speed, sprint,
        //      attributes → movement-speed attribute; Jump Boost; sneak/Swift Sneak) ----
        double maxspeed = playerMaxSpeed(player);
        float jumpSpeed = DEFAULT_JUMPSPEED + (float) jumpBoostBonus(player);
        float fwdPush  = (float) (fwdInput  * maxspeed);
        float sidePush = (float) (sideInput * maxspeed);

        // ---- WALLJUMP (runs BEFORE jump so a jump press cannot overwrite it). Eligibility
        //      was set last frame by the collision prediction; if dash is held now, launch. ----
        boolean walljumped = false;
        if (!inWater) {
            Vec3d wj = tryWalljump(vel, s, onGround, specialKeyDown);
            if (wj != null) { vel = wj; walljumped = true; }
        }

        // ---- JUMP (skipped if we walljumped, or in water where jump/dash push you up). ----
        s.jumped = false;
        if (!inWater && !walljumped) {
            if (justLanded && jumpPressed) s.jumpHeld = false;
            vel = checkJump(vel, s, onGround, jumpPressed, crouchPressed, jumpSpeed);
        }

        // ---- direction vectors from yaw (needed for dash) ----
        float yawRad = player.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        float sy = MathHelper.sin(yawRad);
        float cy = MathHelper.cos(yawRad);
        Vec3d forward = new Vec3d(-sy, 0, cy);
        Vec3d right   = new Vec3d(cy, 0, sy);

        // ---- timer decrements ----
        int ms = 50;
        if (s.dashTime > 0) s.dashTime = Math.max(0, s.dashTime - ms);
        if (s.walljumpTime > 0) s.walljumpTime = Math.max(0, s.walljumpTime - ms);
        if (s.crouchTime > 0) s.crouchTime = Math.max(0, s.crouchTime - ms);
        if (s.crouchSlideTime > 0) {
            s.crouchSlideTime = Math.max(0, s.crouchSlideTime - ms);
            if (s.crouchSlideTime <= 0) {
                s.crouchSlideTime = 0;
                s.crouchSliding = false;
            }
        }

        // ---- if jumped this tick, skip dash entirely (no cooldown). No dash/walljump in
        //      water — dash becomes an upward push in the water branch. ----
        boolean guard = s.jumped || (onGround && jumpPressed);
        if (!inWater && !guard) {
            // landing while holding R counts as a fresh press, but the
            // dash cooldown (dashTime) still gates when it can fire
            if (justLanded && specialKeyDown) {
                s.specialHeld = false;
            }
            vel = checkDash(vel, s, onGround, specialKeyDown, forward, right, fwdPush, sidePush);
        }

        // recheck ground after jump/dash
        if (onGround && vel.y > 1.0f) {
            onGround = false;
            justLanded = false;
        }

        // ---- skip ground physics on landing only if a trigger (jump/dash) keeps us airborne ----
        boolean stayAirborne = justLanded && (s.jumped || s.dashing);

        // ---- friction (ground only; water handles its own friction in its branch) ----
        if (!inWater && onGround && !stayAirborne) {
            vel = applyFriction(vel, s, FT);
        }

        // ---- wish direction ----
        double wishX = forward.x * fwdPush + right.x * sidePush;
        double wishZ = forward.z * fwdPush + right.z * sidePush;
        double wishLen = Math.sqrt(wishX * wishX + wishZ * wishZ);
        // clamp to the (dynamic) max speed computed above
        float wishspeed = (float) Math.min(wishLen, maxspeed);
        Vec3d wishdir;
        if (wishLen > 0.001) {
            wishdir = new Vec3d(wishX / wishLen, 0, wishZ / wishLen);
        } else {
            wishdir = Vec3d.ZERO;
        }

        // ---- water / ground / air move → this tick's displacement in Warsow units ----
        Vec3d dispWs;
        if (inWater) {
            // Air control horizontally. Jump AND dash push you up (dash uses the jump path
            // here); held → keep rising. Friction hits horizontal (submerged only) and the
            // upward jump/dash velocity, but NOT gravity, which is halved. Sub-stepped.
            double subFt = (double) FT / AIR_SUBSTEPS;
            dispWs = Vec3d.ZERO;
            for (int i = 0; i < AIR_SUBSTEPS; i++) {
                if (jumpPressed || specialKeyDown) {
                    vel = new Vec3d(vel.x, Math.max(vel.y, WATER_UPSPEED), vel.z);
                }
                vel = airMove(vel, wishdir, wishspeed, sidePush, fwdPush, false, (float) subFt);
                vel = waterFriction(vel, s, submerged, (float) subFt);
                vel = vel.add(0, -WATER_GRAVITY_SCALE * GRAVITY * subFt * GRAVITY_SCALE, 0);
                dispWs = dispWs.add(vel.multiply(subFt));
            }
        } else if (onGround) {
            if (!stayAirborne) {
                vel = groundMove(vel, wishdir, wishspeed, FT);
            }
            // Apply gravity as a small downward probe so MC keeps firm ground contact.
            // MC only sets onGround when a downward move is blocked; without this,
            // delta.y == 0 makes onGround flicker off every other tick. The floor
            // collision zeroes this vertical component again via the reconciliation below.
            vel = vel.add(0, -GRAVITY * FT * GRAVITY_SCALE, 0);
            dispWs = vel.multiply(FT);
        } else {
            // Sub-step the air integration to approximate Warsow's higher physics
            // tick rate: run air accel + control + gravity AIR_SUBSTEPS times, and
            // accumulate the per-sub-step displacement. Collision happens once, via
            // the single player.move() below.
            double subFt = (double) FT / AIR_SUBSTEPS;
            dispWs = Vec3d.ZERO;
            for (int i = 0; i < AIR_SUBSTEPS; i++) {
                vel = airMove(vel, wishdir, wishspeed, sidePush, fwdPush, s.walljumping, (float) subFt);
                vel = vel.add(0, -GRAVITY * subFt * GRAVITY_SCALE, 0);
                dispWs = dispWs.add(vel.multiply(subFt));
            }
        }

        // ---- track landing state for next tick ----
        s.wasInAir = !onGround;

        // ---- walljump detection: predict a next-frame wall collision from this frame's
        //      velocity, set eligibility + save the into-wall velocity for tryWalljump. ----
        updateWallEligibility(player, s, vel);

        // ---- apply to entity (Warsow units → MC blocks via UNIT_SCALE) ----
        // MC's move() sweeps collisions and stops us flush against blocks. We then
        // reconcile our internal velocity: if an axis moved less than intended it was
        // blocked, so zero that axis so we don't keep pushing into the surface. This
        // fixes the "float under a block" bug (ceiling kills upward vel → fall next
        // tick) and clamps into-wall velocity while still allowing motion away from it.
        Vec3d delta = new Vec3d(dispWs.x * UNIT_SCALE, dispWs.y * UNIT_SCALE, dispWs.z * UNIT_SCALE);
        Vec3d before = player.getEntityPos();
        player.setVelocity(delta);
        player.move(MovementType.SELF, delta);
        Vec3d actual = player.getEntityPos().subtract(before);

        double eps = 1.0e-4;
        double vx = vel.x, vy = vel.y, vz = vel.z;
        boolean blockedX = Math.abs(delta.x) > eps && Math.abs(actual.x) < Math.abs(delta.x) - eps;
        boolean blockedY = Math.abs(delta.y) > eps && Math.abs(actual.y) < Math.abs(delta.y) - eps;
        boolean blockedZ = Math.abs(delta.z) > eps && Math.abs(actual.z) < Math.abs(delta.z) - eps;

        // ceiling/floor: just kill vertical velocity (no momentum buffer)
        if (blockedY) vy = 0;

        // Step-up: if we hit a low ledge (≤ STEP_UP_HEIGHT, e.g. a slab) with a surface
        // within 1 block below our feet, snap the player up onto it and KEEP horizontal
        // speed instead of clamping — so stepping onto slabs/edges is smooth. Works
        // mid-air (not just grounded), as long as there's ground close below.
        if ((blockedX || blockedZ) && hasGroundBelow(player)) {
            double step = tryStepUp(player, blockedX ? delta.x : 0.0, blockedZ ? delta.z : 0.0, STEP_UP_HEIGHT);
            // Only step up if raising the player straight up by `step` is actually clear
            // (where setPosition will put us). Without this, a ceiling above the current
            // spot — e.g. a fence with a block over it — makes MC shove us back down every
            // tick, fighting the step-up and jittering.
            if (step > 0 && isFree(player, player.getBoundingBox().offset(0, step, 0))) {
                player.setPosition(player.getX(), player.getY() + step, player.getZ());
                s.forceGround = true;   // start next tick grounded (applied at frame end below)
                blockedX = false;
                blockedZ = false;
            }
        }

        // Wall-momentum buffer (corner-skip): when a horizontal axis is clamped by a
        // wall, remember the speed we lost; then for the next WALL_BUFFER_FRAMES ticks,
        // if that direction opens up (e.g. you round the corner) restore that speed
        // instead of eating the loss.
        Box pbox = player.getBoundingBox();
        if (blockedX) {
            s.wallSaveX = vel.x;
            s.wallBufferX = WALL_BUFFER_FRAMES;
            vx = 0;
        } else if (s.wallBufferX > 0) {
            if (isFree(player, pbox.offset(Math.copySign(WJ_WALL_PROBE, s.wallSaveX), 0, 0))) {
                vx = s.wallSaveX;      // direction cleared → give the speed back
                s.wallBufferX = 0;
            } else {
                s.wallBufferX--;
            }
        }
        if (blockedZ) {
            s.wallSaveZ = vel.z;
            s.wallBufferZ = WALL_BUFFER_FRAMES;
            vz = 0;
        } else if (s.wallBufferZ > 0) {
            if (isFree(player, pbox.offset(0, 0, Math.copySign(WJ_WALL_PROBE, s.wallSaveZ)))) {
                vz = s.wallSaveZ;
                s.wallBufferZ = 0;
            } else {
                s.wallBufferZ--;
            }
        }
        vel = new Vec3d(vx, vy, vz);

        // ---- store reconciled velocity for next tick ----
        s.velocity = vel;

        // ---- force grounded at the VERY END of the frame (a step-up this tick set the
        //      flag); the corresponding check is at the very start of move() next tick ----
        if (s.forceGround) player.setOnGround(true);
    }

    // ================================================================
    //  ACCELERATION HELPERS
    // ================================================================
    private static Vec3d accelerate(Vec3d vel, Vec3d wishdir, float wishspeed, float accel, float ft) {
        double cur = vel.dotProduct(wishdir);
        double add = wishspeed - cur;
        if (add <= 0) return vel;
        double as = accel * ft * wishspeed;
        if (as > add) as = add;
        return vel.add(wishdir.x * as, wishdir.y * as, wishdir.z * as);
    }

    // Warsow PM_Aircontrol: converts inertia to wish-direction while preserving
    // horizontal speed (redirect only, no speed gain). Only runs when NOT holding a
    // strafe key — matches Warsow's `if( smove != 0 ) return;` guard.
    private static Vec3d airControl(Vec3d vel, Vec3d wishdir, float wishspeed, float sidePush, float ft) {
        if (PM_AIRCONTROL == 0) return vel;
        if (sidePush != 0) return vel;   // can't air-control while +strafe
        if (wishspeed == 0) return vel;

        double z = vel.y;
        Vec3d hvel = new Vec3d(vel.x, 0, vel.z);
        double speed = hvel.length();
        if (speed < 0.001) return vel;
        Vec3d dir = hvel.normalize();

        double dot = dir.dotProduct(wishdir);
        double k = 32.0 * PM_AIRCONTROL * dot * dot * ft;

        if (dot > 0) {
            // can't change direction while slowing down (dot <= 0)
            dir = new Vec3d(
                dir.x * speed + wishdir.x * k,
                0,
                dir.z * speed + wishdir.z * k
            );
            double ns = dir.length();
            if (ns > 0.001) dir = dir.normalize();
            return new Vec3d(dir.x * speed, z, dir.z * speed);
        }
        return vel;
    }

    // ================================================================
    //  FRICTION (Warsow PM_Friction)
    // ================================================================
    private static Vec3d applyFriction(Vec3d vel, PlayerMoveState s, float ft) {
        double spdSq = vel.x * vel.x + vel.z * vel.z; // horizontal only for ground friction
        if (spdSq < 1.0) {
            return new Vec3d(0, vel.y, 0);
        }
        double spd = Math.sqrt(spdSq);
        float control = Math.max((float) spd, PM_DECELERATE);
        double drop = control * PM_FRICTION * ft;

        if (s.crouchSliding) {
            if (s.crouchSlideTime < PM_CROUCHSLIDE_FADE) {
                drop *= 1.0 - Math.sqrt((double) s.crouchSlideTime / PM_CROUCHSLIDE_FADE);
            } else {
                drop = 0;
            }
        }

        double newSpd = Math.max(0, spd - drop);
        double ratio = (spd > 0.001) ? newSpd / spd : 0;
        return new Vec3d(vel.x * ratio, vel.y, vel.z * ratio);
    }

    // Water friction: ground friction on the horizontal (only when submerged — the
    // surface is frictionless except for jump/dash), plus friction on the UPWARD
    // (jump/dash) vertical velocity so rising is limited. Downward velocity (gravity) is
    // left alone, per spec.
    private static Vec3d waterFriction(Vec3d vel, PlayerMoveState s, boolean submerged, float ft) {
        double vx = vel.x, vy = vel.y, vz = vel.z;
        float friction = PM_FRICTION * WATER_FRICTION_SCALE; // ground friction × 0.2
        if (submerged) { // horizontal (surface is frictionless except jump/dash)
            double spd = Math.sqrt(vx * vx + vz * vz);
            if (spd < 1.0) { vx = 0; vz = 0; }
            else {
                double drop = Math.max(spd, PM_DECELERATE) * friction * ft;
                double ratio = Math.max(0, spd - drop) / spd;
                vx *= ratio; vz *= ratio;
            }
        }
        if (vy > 0) { // friction the jump/dash rise, not gravity's descent
            vy = Math.max(0, vy - Math.max(vy, PM_DECELERATE) * friction * ft);
        }
        return new Vec3d(vx, vy, vz);
    }

    // ================================================================
    //  JUMP (Warsow PM_CheckJump)
    // ================================================================
    private static Vec3d checkJump(Vec3d vel, PlayerMoveState s, boolean onGround,
                                    boolean jumpPressed, boolean crouchPressed, float jumpSpeed) {
        if (!jumpPressed) {
            s.jumpHeld = false;
            return vel;
        }
        if (s.jumpHeld) return vel;
        if (!onGround) return vel;

        s.jumpHeld = true;
        s.jumped = true;

        if (crouchPressed) {
            // crouch-jump: trade horizontal momentum for height. Convert 75% of the
            // horizontal speed into vertical and keep 25% as horizontal. At high speed
            // the big vertical component "launches" you upward; the reduced horizontal
            // lets you land precisely on a block (awkward terrain).
            // If we clipped a wall within the last WALL_BUFFER_FRAMES ticks, use the
            // pre-collision (buffered) speed so a crouch-jump right after a wall hit keeps
            // the momentum we went in with.
            double useX = (s.wallBufferX > 0) ? s.wallSaveX : vel.x;
            double useZ = (s.wallBufferZ > 0) ? s.wallSaveZ : vel.z;
            double hspeed = Math.sqrt(useX * useX + useZ * useZ);
            double keep = 1.0 - CROUCH_JUMP_RATIO;
            vel = new Vec3d(useX * keep, jumpSpeed + CROUCH_JUMP_RATIO * hspeed, useZ * keep);
            s.wallBufferX = 0;
            s.wallBufferZ = 0;
        } else {
            vel = new Vec3d(vel.x, jumpSpeed, vel.z);
        }

        s.dashTime = 0;
        s.dashing = false;
        return vel;
    }

    // ================================================================
    //  MC MODIFIER SCALING (movement-speed attribute, Jump Boost, sneak/Swift Sneak)
    // ================================================================

    // Effective max ground speed in Warsow units. Scales DEFAULT_PLAYERSPEED by the
    // player's movement-speed attribute multiplier (Speed potion, Soul Speed, sprint,
    // item/attribute modifiers all fold into this), then applies the sneak factor
    // while sneaking on the ground.
    public static double playerMaxSpeed(PlayerEntity player) {
        double base = player.getAttributeBaseValue(EntityAttributes.MOVEMENT_SPEED);
        double cur  = player.getAttributeValue(EntityAttributes.MOVEMENT_SPEED);
        double mul  = (base > 1.0e-6) ? cur / base : 1.0; // 1.0 at no modifiers
        double speed = DEFAULT_PLAYERSPEED * mul;
        if (player.isSneaking() && player.isOnGround()) {
            speed *= sneakFactor(player);
        }
        return speed;
    }

    // Sneaking speed multiplier per the MC wiki: base 0.3× walk, +0.15 per Swift Sneak
    // level (max level 3 → 0.75×).
    private static double sneakFactor(PlayerEntity player) {
        int lvl = Math.min(3, swiftSneakLevel(player));
        return 0.3 + 0.15 * lvl;
    }

    private static int swiftSneakLevel(PlayerEntity player) {
        try {
            World world = player.getEntityWorld();
            Registry<Enchantment> ench = world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
            RegistryEntry<Enchantment> swift = ench.getOrThrow(Enchantments.SWIFT_SNEAK);
            return EnchantmentHelper.getEquipmentLevel(swift, player);
        } catch (Exception e) {
            return 0;
        }
    }

    // Extra jump velocity (Warsow units) from the Jump Boost effect. Vanilla adds
    // 0.1·(amplifier+1) blocks/tick to jump velocity; convert to Warsow units.
    private static double jumpBoostBonus(PlayerEntity player) {
        StatusEffectInstance jb = player.getStatusEffect(StatusEffects.JUMP_BOOST);
        if (jb == null) return 0.0;
        double mcBlocksPerTick = 0.1 * (jb.getAmplifier() + 1);
        return mcBlocksPerTick / (FT * UNIT_SCALE);
    }

    // ================================================================
    //  DASH (Warsow PM_CheckDash)
    // ================================================================
    private static Vec3d checkDash(Vec3d vel, PlayerMoveState s, boolean onGround,
                                    boolean specialDown, Vec3d flatFwd, Vec3d right,
                                    float fwdPush, float sidePush) {
        if (!specialDown) s.specialHeld = false;
        if (!specialDown || s.specialHeld || s.dashTime > 0) {
            if (!onGround) s.dashing = false;
            return vel;
        }
        if (!onGround) return vel;

        s.specialHeld = true;
        s.dashing = true;

        float upSpeed = PM_DASHUPSPEED;

        // dash direction: input-based if wasd pressed, otherwise camera forward
        boolean hasInput = Math.abs(fwdPush) > 0.001f || Math.abs(sidePush) > 0.001f;
        Vec3d dashDir;
        if (hasInput) {
            dashDir = new Vec3d(
                flatFwd.x * fwdPush + right.x * sidePush,
                0,
                flatFwd.z * fwdPush + right.z * sidePush
            );
            if (dashDir.lengthSquared() < 0.0001f) dashDir = flatFwd;
            dashDir = dashDir.normalize();
        } else {
            dashDir = flatFwd;
        }

        float hSpeed = (float) Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        float dashSpeed = Math.max(hSpeed, DEFAULT_DASHSPEED);

        s.dashTime = PM_DASHJUMP_TIMEDELAY;
        return new Vec3d(dashDir.x * dashSpeed, upSpeed, dashDir.z * dashSpeed);
    }

    // ================================================================
    //  WALLJUMP (Warsow PM_CheckWallJump) — hug a wall and press special to
    //  launch off it. The launch always goes away from the wall normal.
    // ================================================================
    // ---- Walljump detection (runs at end of the tick). Nine x-z footprint planes span
    //      the player's height (feet, +0.25 … +2.0). If the next-frame horizontal move would
    //      collide at any plane AND the velocity is heading into that wall (angle < 89°), the
    //      player becomes eligible next frame and we save the into-wall velocity + normal. ----
    private static void updateWallEligibility(PlayerEntity player, PlayerMoveState s, Vec3d vel) {
        s.wallEligible = false;
        double stepX = vel.x * FT * UNIT_SCALE;   // next-frame horizontal move (MC blocks)
        double stepZ = vel.z * FT * UNIT_SCALE;
        boolean moveX = Math.abs(stepX) > 1.0e-6;
        boolean moveZ = Math.abs(stepZ) > 1.0e-6;
        if (!moveX && !moveZ) return;

        Box box = player.getBoundingBox();
        double e = 0.02;
        double nx = 0, nz = 0;
        for (int i = 0; i < WJ_PLANES; i++) {
            double y = box.minY + i * WJ_PLANE_SPACING;
            Box plane = new Box(box.minX, y, box.minZ, box.maxX, y + e, box.maxZ);
            if (moveX && !isFree(player, plane.offset(stepX, 0, 0))) nx = -Math.signum(stepX);
            if (moveZ && !isFree(player, plane.offset(0, 0, stepZ))) nz = -Math.signum(stepZ);
        }
        if (nx == 0 && nz == 0) return;
        Vec3d normal = new Vec3d(nx, 0, nz).normalize();

        // velocity must be heading INTO the wall: angle between it and the into-wall
        // direction (−normal) below 89° (exclusive), else it's parallel/away — not eligible.
        Vec3d hvel = new Vec3d(vel.x, 0, vel.z);
        if (hvel.lengthSquared() < 1.0e-9) return;
        double cos = hvel.normalize().dotProduct(normal.multiply(-1.0));
        if (cos <= WJ_ANGLE_COS) return;

        s.wallEligible = true;
        s.wallNormal = normal;
        s.wallVelocity = vel;
    }

    // ---- Walljump launch. If last frame flagged an imminent wall collision and dash is
    //      held now, launch off it exactly like Warsow, using the SAVED pre-collision
    //      velocity. Returns the new velocity, or null if no walljump. ----
    private static Vec3d tryWalljump(Vec3d vel, PlayerMoveState s, boolean onGround, boolean specialDown) {
        // housekeeping (mirrors Warsow): clear walljump state on ground, at apex, and reset
        // the once-per-wall count after the cooldown expires
        if (onGround) { s.walljumping = false; s.walljumpCount = false; }
        if (s.walljumping && vel.y < 0) s.walljumping = false;
        if (s.walljumpTime <= 0) s.walljumpCount = false;

        // gates: eligible (collision predicted last frame), dash held, airborne, off cooldown
        if (!s.wallEligible || onGround || !specialDown || s.walljumpCount || s.walljumpTime > 0)
            return null;
        // don't walljump in the first 100 ms of a dash jump
        if (s.dashing && s.dashTime > (PM_DASHJUMP_TIMEDELAY - 100)) return null;

        Vec3d normal = s.wallNormal;
        Vec3d wv = s.wallVelocity; // velocity going into the wall (pre-collision)

        // Warsow non-stun launch: unit-normalise the horizontal velocity, slide it along the
        // wall, add a 30% outward push, restore speed (min PM_WJMINSPEED), keep faster up-speed.
        float oldUp = (float) wv.y;
        Vec3d hv = new Vec3d(wv.x, 0, wv.z);
        float hSpeed = (float) hv.length();
        if (hSpeed > 0.001f) hv = hv.multiply(1.0 / hSpeed);
        hv = clipVelocity(hv, normal, 1.0005);
        hv = hv.add(normal.x * PM_WJBOUNCEFACTOR, 0, normal.z * PM_WJBOUNCEFACTOR);
        if (hSpeed < PM_WJMINSPEED) hSpeed = PM_WJMINSPEED;
        double len = hv.length();
        if (len > 0.001) hv = hv.multiply(hSpeed / len);
        else hv = new Vec3d(normal.x * hSpeed, 0, normal.z * hSpeed);

        s.walljumpCount = true;
        s.walljumping = true;
        s.walljumpTime = PM_WALLJUMP_TIMEDELAY; // own cooldown (same length as dash), distinct
        s.wallEligible = false;

        return new Vec3d(hv.x, Math.max(oldUp, PM_WJUPSPEED), hv.z);
    }

    private static boolean isFree(PlayerEntity player, Box box) {
        return player.getEntityWorld().isSpaceEmpty(player, box);
    }

    // True if there's a solid surface within STEP_GROUND_DROP (1 block) below the feet
    // (footprint-only, so side walls don't count). Lets step-up work mid-air near ground.
    private static boolean hasGroundBelow(PlayerEntity player) {
        Box b = player.getBoundingBox();
        Box under = new Box(b.minX, b.minY - STEP_GROUND_DROP, b.minZ, b.maxX, b.minY, b.maxZ);
        return !isFree(player, under);
    }

    // If a horizontal collision is a low ledge we can step onto, return the height (MC
    // blocks) to rise; otherwise 0. Probes into the blocked direction: the obstacle must
    // be present at foot height but clear at some height ≤ STEP_UP_HEIGHT (so a full-height
    // wall returns 0, but a slab returns ~0.5).
    private static double tryStepUp(PlayerEntity player, double dirX, double dirZ, double maxHeight) {
        if (dirX == 0 && dirZ == 0) return 0;
        Box box = player.getBoundingBox();
        double hx = dirX != 0 ? Math.copySign(0.3, dirX) : 0;
        double hz = dirZ != 0 ? Math.copySign(0.3, dirZ) : 0;
        // must actually be blocked in that direction at current height
        if (isFree(player, box.offset(hx, 0, hz))) return 0;
        for (double h = 0.05; h <= maxHeight + 1.0e-6; h += 0.05) {
            if (isFree(player, box.offset(hx, h, hz))) return h;
        }
        return 0;
    }

    // Warsow GS_ClipVelocity: remove the component of vel along normal.
    private static Vec3d clipVelocity(Vec3d vel, Vec3d normal, double overbounce) {
        double dot = vel.dotProduct(normal);
        return new Vec3d(
            vel.x - dot * normal.x * overbounce,
            vel.y - dot * normal.y * overbounce,
            vel.z - dot * normal.z * overbounce
        );
    }

    // ================================================================
    //  GROUND MOVE
    // ================================================================
    private static Vec3d groundMove(Vec3d vel, Vec3d wishdir, float wishspeed, float ft) {
        if (vel.y > 0) vel = new Vec3d(vel.x, 0, vel.z);
        return accelerate(vel, wishdir, wishspeed, PM_ACCELERATE, ft);
    }

    // ================================================================
    //  AIR MOVE — exact port of Warsow gs_pmove.c "Air Control" branch
    //  (PMFEAT_AIRCONTROL && !PMFEAT_FWDBUNNY). No forward-bunny: holding a
    //  key without turning does not build speed. Speed comes from strafe
    //  acceleration (PM_Accelerate at an angle) and air control redirects.
    // ================================================================
    private static Vec3d airMove(Vec3d vel, Vec3d wishdir, float wishspeed,
                                  float sidePush, float fwdPush, boolean walljumping, float ft) {
        // during the walljump launch window Warsow inhibits both air accel and air
        // control so the launch trajectory is preserved (only gravity acts)
        if (walljumping) return vel;

        float wishspeed2 = wishspeed;

        // decelerate when pushing against current velocity, else normal air accel
        float accel = (vel.dotProduct(wishdir) < 0) ? PM_AIRDECELERATE : PM_AIRACCELERATE;

        // +strafe bunnyhopping: strafe key held, forward/back NOT held
        if (sidePush != 0 && fwdPush == 0) {
            if (wishspeed > PM_WISHSPEED) wishspeed = PM_WISHSPEED;
            accel = PM_STRAFE_BUNNY_ACCEL;
        }

        vel = accelerate(vel, wishdir, wishspeed, accel, ft);
        // air control (redirect, speed-preserving); internally no-ops while +strafe
        vel = airControl(vel, wishdir, wishspeed2, sidePush, ft);

        return vel;
    }

    private WarsowPmove() {}
}
