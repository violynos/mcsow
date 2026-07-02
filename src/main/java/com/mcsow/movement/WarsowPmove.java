package com.mcsow.movement;

import net.minecraft.entity.MovementType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class WarsowPmove {
    private static boolean enabled = true;

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean e) { enabled = e; }

    // === Warsow constants (exact values from gs_pmove.cpp) ===
    // All velocities in Warsow units/sec; multiplied by UNIT_SCALE * FT at output.

    private static final float FT = 0.05f; // 20 ticks/sec
    private static final float UNIT_SCALE = 0.01875f; // 1 Warsow unit → MC blocks

    private static final float GRAVITY            = 1120.0f;
    private static final float GRAVITY_SCALE      = 1.0f; // no scaling, raw Warsow
    private static final float GRAVITY_COMPENSATE = GRAVITY / 800.0f; // = 1.0 at 800

    // speeds
    public static final float DEFAULT_PLAYERSPEED   = 320.0f;
    public static final float DEFAULT_WALKSPEED     = 160.0f;
    public static final float DEFAULT_CROUCHEDSPEED = 100.0f;
    public static final float DEFAULT_JUMPSPEED     = 280.0f * GRAVITY_COMPENSATE;
    public static final float DEFAULT_DASHSPEED     = 450.0f; // minimum dash speed

    // friction / acceleration
    private static final float PM_FRICTION         = 8.0f;
    private static final float PM_ACCELERATE       = 12.0f;
    private static final float PM_AIRACCELERATE    = 1.0f;
    private static final float PM_AIRDECELERATE    = 2.0f;
    private static final float PM_DECELERATE       = 12.0f;
    private static final float PM_WATERFRICTION    = 1.0f;
    private static final float PM_WATERACCELERATE  = 10.0f;

    // air control
    private static final float PM_AIRCONTROL         = 150.0f;
    private static final float PM_STRAFE_BUNNY_ACCEL = 70.0f;
    private static final float PM_WISHSPEED          = 30.0f;
    private static final float PM_AIR_FORWARD_ACCEL  = 1.00001f;
    private static final float PM_BUNNY_ACCEL        = 0.1593f;
    private static final float PM_BUNNY_TOPSPEED     = 925.0f;
    private static final float PM_TURN_ACCEL         = 4.0f;
    private static final float PM_BACKTOSIDERATIO    = 0.8f;
    private static final float PM_FORWARD_ACCEL_TIMEDELAY = 0;

    // dash
    public static final int    PM_DASHJUMP_TIMEDELAY        = 1000;
    public static final int    PM_SPECIAL_CROUCH_INHIBIT    = 400;
    public static final int    PM_AIRCONTROL_BOUNCE_DELAY   = 200;
    public static final int    PM_CROUCHSLIDE_TIMEDELAY     = 700;
    public static final int    PM_CROUCHSLIDE_FADE          = 500;
    public static final int    PM_CROUCHSLIDE_CONTROL       = 3;
    public static final int    CROUCHTIME                   = 100;

    private static final float PM_DASHUPSPEED       = 174.0f * 1.15f * GRAVITY_COMPENSATE; // dash height tuned ×1.15
    private static final float PM_OVERBOUNCE         = 1.01f;
    private static final float STEPSIZE              = 18.0f;
    private static final float SPEEDKEY              = 500.0f;

    private static final java.util.Map<Integer, PlayerMoveState> STATES = new java.util.HashMap<>();

    private static class PlayerMoveState {
        Vec3d velocity = Vec3d.ZERO;
        boolean jumpHeld;
        boolean specialHeld;
        int dashTime;
        boolean dashing;
        int crouchTime;
        int forwardTime;
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

    private static double mcDelta(double warsowVel) {
        return warsowVel * FT * UNIT_SCALE;
    }

    // ================================================================
    //  MAIN ENTRY
    // ================================================================
    public static void move(PlayerEntity player, boolean specialKeyDown,
                             boolean jumpPressed, boolean crouchPressed,
                             float fwdPush, float sidePush) {
        PlayerMoveState s = state(player);
        Vec3d vel = s.velocity;

        // ---- on-ground (checked first, before anything) ----
        boolean onGround = player.isOnGround();
        boolean justLanded = onGround && s.wasInAir;

        // ---- JUMP FIRST (must run before timers, dash, directions) ----
        s.jumped = false;
        if (justLanded && jumpPressed) s.jumpHeld = false;
        vel = checkJump(vel, s, onGround, jumpPressed);

        // ---- direction vectors from yaw (needed for dash) ----
        float yawRad = player.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        float sy = MathHelper.sin(yawRad);
        float cy = MathHelper.cos(yawRad);
        Vec3d forward = new Vec3d(-sy, 0, cy);
        Vec3d right   = new Vec3d(cy, 0, sy);

        // ---- timer decrements ----
        int ms = 50;
        if (s.dashTime > 0) s.dashTime = Math.max(0, s.dashTime - ms);
        if (s.crouchTime > 0) s.crouchTime = Math.max(0, s.crouchTime - ms);
        if (s.forwardTime > 0) s.forwardTime = Math.max(0, s.forwardTime - ms);
        if (s.crouchSlideTime > 0) {
            s.crouchSlideTime = Math.max(0, s.crouchSlideTime - ms);
            if (s.crouchSlideTime <= 0) {
                s.crouchSlideTime = 0;
                s.crouchSliding = false;
            }
        }

        // ---- if jumped this tick, skip dash entirely (no cooldown) ----
        boolean guard = s.jumped || (onGround && jumpPressed);
        if (!guard) {
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

        // ---- friction (ground only) ----
        if (onGround && !stayAirborne) {
            vel = applyFriction(vel, s, FT);
        }

        // ---- wish direction ----
        double wishX = forward.x * fwdPush + right.x * sidePush;
        double wishZ = forward.z * fwdPush + right.z * sidePush;
        double wishLen = Math.sqrt(wishX * wishX + wishZ * wishZ);
        // clamp to maxspeed
        float maxspeed = DEFAULT_PLAYERSPEED;
        float wishspeed = (float) Math.min(wishLen, maxspeed);
        Vec3d wishdir;
        if (wishLen > 0.001) {
            wishdir = new Vec3d(wishX / wishLen, 0, wishZ / wishLen);
        } else {
            wishdir = Vec3d.ZERO;
        }

        // ---- ground or air move ----
        if (onGround) {
            if (!stayAirborne) {
                vel = groundMove(vel, wishdir, wishspeed, FT);
            }
        } else {
            // horizontal air control + bunnyhop (airMove preserves vel.y),
            // then gravity as an independent vertical term
            vel = airMove(vel, wishdir, wishspeed, sidePush, fwdPush, s, FT, maxspeed);
            vel = vel.add(0, -GRAVITY * FT * GRAVITY_SCALE, 0);
        }

        // ---- track landing state for next tick ----
        s.wasInAir = !onGround;

        // ---- store velocity for next tick ----
        s.velocity = vel;

        // ---- apply to entity ----
        Vec3d delta = new Vec3d(mcDelta(vel.x), mcDelta(vel.y), mcDelta(vel.z));
        player.setVelocity(delta);
        player.move(MovementType.SELF, delta);
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

    private static Vec3d airAccelerate(Vec3d vel, Vec3d wishdir, float wishspeed, float maxSpeed, float ft) {
        if (wishspeed == 0) return vel;

        Vec3d curVel = new Vec3d(vel.x, 0, vel.z);
        double curSpd = curVel.length();

        if (wishspeed > curSpd * 1.01f) {
            double as = curSpd + PM_AIR_FORWARD_ACCEL * maxSpeed * ft;
            if (as < wishspeed) wishspeed = (float) as;
        } else {
            float f = (PM_BUNNY_TOPSPEED - (float) curSpd) / (PM_BUNNY_TOPSPEED - maxSpeed);
            if (f < 0) f = 0;
            wishspeed = Math.max((float) curSpd, maxSpeed) + PM_BUNNY_ACCEL * f * maxSpeed * ft;
        }

        Vec3d wv = new Vec3d(wishdir.x * wishspeed, 0, wishdir.z * wishspeed);
        Vec3d ad = wv.subtract(curVel);
        double add = ad.length();
        if (add < 0.001) return vel;
        ad = ad.normalize();

        double as = PM_TURN_ACCEL * maxSpeed * ft;
        if (as > add) as = add;

        if (PM_BACKTOSIDERATIO < 1.0f) {
            Vec3d curDir = curVel.normalize();
            double dot = ad.dotProduct(curDir);
            if (dot < 0) {
                ad = ad.add(curDir.x * -(1.0f - PM_BACKTOSIDERATIO) * dot,
                            0,
                            curDir.z * -(1.0f - PM_BACKTOSIDERATIO) * dot);
            }
        }

        return new Vec3d(vel.x + as * ad.x, vel.y, vel.z + as * ad.z);
    }

    private static Vec3d airControl(Vec3d vel, Vec3d wishdir, float wishspeed, float ft) {
        if (PM_AIRCONTROL == 0) return vel;
        if (wishspeed == 0) return vel;

        double z = vel.y;
        Vec3d hvel = new Vec3d(vel.x, 0, vel.z);
        double speed = hvel.length();
        if (speed < 0.001) return vel;
        Vec3d dir = hvel.normalize();

        double dot = dir.dotProduct(wishdir);
        double k = 32.0 * PM_AIRCONTROL * dot * dot * ft;

        if (dot > 0) {
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

    // ================================================================
    //  JUMP (Warsow PM_CheckJump)
    // ================================================================
    private static Vec3d checkJump(Vec3d vel, PlayerMoveState s, boolean onGround, boolean jumpPressed) {
        if (!jumpPressed) {
            s.jumpHeld = false;
            return vel;
        }
        if (s.jumpHeld) return vel;
        if (!onGround) return vel;

        s.jumpHeld = true;
        s.jumped = true;

        vel = new Vec3d(vel.x, DEFAULT_JUMPSPEED, vel.z);

        s.dashTime = 0;
        s.dashing = false;
        return vel;
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
    //  GROUND MOVE
    // ================================================================
    private static Vec3d groundMove(Vec3d vel, Vec3d wishdir, float wishspeed, float ft) {
        if (vel.y > 0) vel = new Vec3d(vel.x, 0, vel.z);
        return accelerate(vel, wishdir, wishspeed, PM_ACCELERATE, ft);
    }

    // ================================================================
    //  AIR MOVE (Warsow PM_Move air path)
    // ================================================================
    private static Vec3d airMove(Vec3d vel, Vec3d wishdir, float wishspeed,
                                  float sidePush, float fwdPush,
                                  PlayerMoveState s, float ft, float maxSpeed) {
        float wishspeed2 = wishspeed;
        float accel = PM_AIRACCELERATE;
        if (s.dashing) accel = 0;

        boolean strafeBunny = sidePush != 0 && fwdPush == 0;
        if (strafeBunny && wishspeed > PM_WISHSPEED) {
            wishspeed = PM_WISHSPEED;
            accel = PM_STRAFE_BUNNY_ACCEL;
        }

        boolean accelerating = vel.dotProduct(wishdir) > 0;
        boolean inhibit = s.dashing;

        boolean fwdBunny = true;
        if (s.forwardTime > 0) {
            fwdBunny = false;
        }
        if (!(sidePush == 0 && fwdPush != 0)) {
            fwdBunny = false;
        }

        if (fwdBunny && !inhibit && accelerating && sidePush == 0 && fwdPush != 0) {
            vel = airAccelerate(vel, wishdir, wishspeed, maxSpeed, ft);
        } else {
            vel = accelerate(vel, wishdir, wishspeed, accel, ft);
            if (!s.dashing) {
                vel = airControl(vel, wishdir, wishspeed2, ft);
            }
        }

        return vel;
    }

    private WarsowPmove() {}
}
