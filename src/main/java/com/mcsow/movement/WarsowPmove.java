package com.mcsow.movement;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class WarsowPmove {
    // --- pmove constants ---
    public static final int    PM_DASHJUMP_TIMEDELAY         = 1000;
    public static final int    PM_WALLJUMP_TIMEDELAY          = 1300;
    public static final int    PM_WALLJUMP_FAILED_TIMEDELAY  = 700;
    public static final int    PM_SPECIAL_CROUCH_INHIBIT     = 400;

    public static final float  PM_FRICTION                   = 8.0f;
    public static final float  PM_ACCELERATE                 = 12.0f;
    public static final float  PM_AIRACCELERATE              = 1.0f;
    public static final float  PM_AIRDECELERATE              = 2.0f;
    public static final float  PM_AIRCONTROL                 = 150.0f;
    public static final float  PM_STRAFE_BUNNY_ACCEL         = 70.0f;
    public static final float  PM_WISHSPEED                  = 30.0f;
    public static final float  PM_AIR_FORWARD_ACCEL          = 1.00001f;
    public static final float  PM_BUNNY_ACCEL                = 0.1586f;
    public static final float  PM_BUNNY_TOPSPEED             = 900.0f;
    public static final float  PM_TURN_ACCEL                 = 6.0f;
    public static final float  PM_BACKTOSIDERATIO            = 0.9f;

    public static final float  DEFAULT_PLAYERSPEED           = 320.0f;
    public static final float  DEFAULT_WALKSPEED             = 160.0f;
    public static final float  DEFAULT_CROUCHEDSPEED         = 100.0f;
    public static final float  DEFAULT_JUMPSPEED             = 280.0f;
    public static final float  DEFAULT_DASHSPEED             = 475.0f;
    public static final float  GRAVITY                       = 850.0f;
    public static final float  GRAVITY_COMPENSATE            = GRAVITY / 800.0f;

    public static final float  PM_DASHUPSPEED                = 174.0f * GRAVITY_COMPENSATE;
    public static final float  PM_WJUPSPEED                  = 330.0f * GRAVITY_COMPENSATE;
    public static final float  PM_WJBOUNCEFACTOR             = 0.3f;
    public static final float  PM_WJMINSPEED                 = (DEFAULT_WALKSPEED + DEFAULT_PLAYERSPEED) * 0.5f;

    private static final int CROUCHTIME = 100;

    private static final java.util.Map<PlayerEntity, PlayerMoveState> STATES = new java.util.WeakHashMap<>();

    private static class PlayerMoveState {
        boolean jumpHeld;
        boolean specialHeld;
        int dashTime;
        int walljumpTime;
        boolean walljumpCount;
        boolean walljumping;
        boolean dashing;
        int crouchTime;
    }

    private static PlayerMoveState state(PlayerEntity p) {
        return STATES.computeIfAbsent(p, k -> new PlayerMoveState());
    }

    public static void clear(PlayerEntity p) {
        STATES.remove(p);
    }

    // ================================================================
    //  MAIN ENTRY — called from PlayerEntityMixin (overrides travel())
    // ================================================================
    public static void move(PlayerEntity player, boolean specialKeyDown,
                             boolean jumpPressed, boolean crouchPressed,
                             float fwdPush, float sidePush) {
        PlayerMoveState s = state(player);
        float ft = 0.05f;

        // view vectors
        float yawRad = player.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        float pitchRad = player.getPitch() * MathHelper.RADIANS_PER_DEGREE;
        float sy = MathHelper.sin(yawRad);
        float cy = MathHelper.cos(yawRad);
        float sp = MathHelper.sin(pitchRad);
        float cp = MathHelper.cos(pitchRad);

        Vec3d forward  = new Vec3d(-sy * cp, sp, cy * cp).normalize();
        Vec3d right    = new Vec3d(cy, 0, sy).normalize();
        Vec3d flatFwd  = new Vec3d(-sy, 0, cy).normalize();

        // max speeds
        float maxSpeed = DEFAULT_PLAYERSPEED;
        float walkSpeed = Math.min(DEFAULT_WALKSPEED, maxSpeed * 0.66f);
        float crouchSpeed = Math.min(DEFAULT_CROUCHEDSPEED, maxSpeed * 0.5f);

        // tick timers
        if (s.dashTime > 0)     s.dashTime     = Math.max(0, s.dashTime - 50);
        if (s.walljumpTime > 0) s.walljumpTime = Math.max(0, s.walljumpTime - 50);

        // crouch
        if (crouchPressed
            && s.walljumpTime < (PM_WALLJUMP_TIMEDELAY - PM_SPECIAL_CROUCH_INHIBIT)
            && s.dashTime     < (PM_DASHJUMP_TIMEDELAY - PM_SPECIAL_CROUCH_INHIBIT)) {
            s.crouchTime = Math.min(CROUCHTIME, s.crouchTime + 50);
        } else if (!crouchPressed && s.crouchTime > 0) {
            Box standBox = player.getBoundingBox()
                .withMinY(player.getY())
                .withMaxY(player.getY() + 1.8);
            if (player.getEntityWorld().isSpaceEmpty(player, standBox)) {
                s.crouchTime = Math.max(0, s.crouchTime - 50);
            }
        }

        boolean onGround = player.isOnGround();
        Vec3d vel = player.getVelocity();

        // jump
        vel = checkJump(vel, s, onGround, jumpPressed);

        // dash
        vel = checkDash(vel, s, onGround, specialKeyDown, flatFwd, right, fwdPush, sidePush);

        // walljump
        vel = checkWalljump(player, vel, s, onGround, specialKeyDown);

        // friction
        if (onGround && !player.isTouchingWater()) {
            vel = applyFriction(vel, ft);
        }

        // wish direction from input
        Vec3d wishvel = new Vec3d(
            forward.x * fwdPush + right.x * sidePush,
            0,
            forward.z * fwdPush + right.z * sidePush
        );
        double rawWish = wishvel.length();
        Vec3d wishdir = rawWish > 0.001 ? wishvel.normalize() : Vec3d.ZERO;
        float wishspeed = (float) rawWish;

        float cap = s.crouchTime > 0 ? crouchSpeed : (crouchPressed ? walkSpeed : maxSpeed);
        if (wishspeed > cap) wishspeed = cap;

        // --- movement type dispatch ---
        if (player.isTouchingWater()) {
            vel = waterMove(vel, forward, right, fwdPush, sidePush, maxSpeed, ft);
        } else if (onGround) {
            vel = groundMove(vel, wishdir, wishspeed, ft);
        } else {
            vel = airMove(vel, wishdir, wishspeed, sidePush, fwdPush, s, ft, maxSpeed);
        }

        // snap small components
        vel = new Vec3d(
            Math.abs(vel.x) < 0.005 ? 0 : vel.x,
            Math.abs(vel.y) < 0.005 ? 0 : vel.y,
            Math.abs(vel.z) < 0.005 ? 0 : vel.z
        );

        player.setVelocity(vel);
    }

    // ================================================================
    //  ACCELERATION HELPERS (immutable Vec3d)
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

        return new Vec3d(vel.x + as * ad.x, vel.y, vel.z + as * ad.z);
    }

    private static Vec3d airControl(Vec3d vel, Vec3d wishdir, float wishspeed, float ft) {
        if (PM_AIRCONTROL == 0) return vel;

        double z = vel.y;
        Vec3d hvel = new Vec3d(vel.x, 0, vel.z);
        double speed = hvel.length();
        if (speed < 0.001) return vel;
        Vec3d dir = hvel.normalize();

        double dot = dir.dotProduct(wishdir);
        double k = 32 * PM_AIRCONTROL * dot * dot * ft;

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

    private static Vec3d clipVelocity(Vec3d vel, Vec3d normal, double overbounce) {
        double dot = vel.dotProduct(normal);
        return new Vec3d(
            vel.x - dot * normal.x * overbounce,
            vel.y - dot * normal.y * overbounce,
            vel.z - dot * normal.z * overbounce
        );
    }

    // ================================================================
    //  FRICTION
    // ================================================================
    private static Vec3d applyFriction(Vec3d vel, float ft) {
        double spd = vel.length();
        if (spd < 0.1) return new Vec3d(0, vel.y, 0);

        float control = Math.max((float) spd, PM_AIRDECELERATE);
        double drop = control * PM_FRICTION * ft;
        double newSpd = Math.max(0, spd - drop);
        return vel.multiply(newSpd / spd);
    }

    // ================================================================
    //  JUMP
    // ================================================================
    private static Vec3d checkJump(Vec3d vel, PlayerMoveState s, boolean onGround, boolean jumpPressed) {
        if (!jumpPressed) { s.jumpHeld = false; return vel; }
        if (s.jumpHeld) return vel;
        if (!onGround) return vel;

        s.jumpHeld = true;
        float js = DEFAULT_JUMPSPEED * GRAVITY_COMPENSATE;
        vel = new Vec3d(vel.x, vel.y > 0.1 ? vel.y + js : js, vel.z);

        s.dashTime = 0;
        s.walljumpTime = 0;
        s.walljumping = false;
        s.dashing = false;
        return vel;
    }

    // ================================================================
    //  DASH
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
        s.walljumping = false;

        float upSpeed = vel.y <= 0 ? PM_DASHUPSPEED : PM_DASHUPSPEED + (float) vel.y;

        Vec3d dashDir = new Vec3d(
            flatFwd.x * fwdPush + right.x * sidePush,
            0,
            flatFwd.z * fwdPush + right.z * sidePush
        );
        if (dashDir.lengthSquared() < 0.0001f) dashDir = flatFwd;
        dashDir = dashDir.normalize();

        float hSpeed = (float) Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        float dashSpeed = Math.max(hSpeed, DEFAULT_DASHSPEED);

        s.dashTime = PM_DASHJUMP_TIMEDELAY;
        return new Vec3d(dashDir.x * dashSpeed, upSpeed, dashDir.z * dashSpeed);
    }

    // ================================================================
    //  WALLJUMP
    // ================================================================
    private static Vec3d checkWalljump(PlayerEntity player, Vec3d vel, PlayerMoveState s,
                                        boolean onGround, boolean specialDown) {
        if (!specialDown) s.specialHeld = false;
        if (onGround)                       { s.walljumping = false; s.walljumpCount = false; }
        if (s.walljumping && vel.y < 0)      s.walljumping = false;
        if (s.walljumpTime <= 0)             s.walljumpCount = false;
        if (s.dashing && s.dashTime > (PM_DASHJUMP_TIMEDELAY - 100)) return vel;
        if (onGround || !specialDown || s.specialHeld || s.walljumpCount || s.walljumpTime > 0) return vel;

        Vec3d normal = findWallNormal(player);
        if (normal == null) return vel;

        float oldUp = (float) vel.y;
        Vec3d hv = new Vec3d(vel.x, 0, vel.z);
        float hSpeed = (float) hv.length();

        hv = clipVelocity(hv, normal, 1.0005f);
        hv = hv.add(normal.x * PM_WJBOUNCEFACTOR, 0, normal.z * PM_WJBOUNCEFACTOR);
        if (hSpeed < PM_WJMINSPEED) hSpeed = PM_WJMINSPEED;
        hv = hv.normalize().multiply(hSpeed);

        s.specialHeld = true;
        s.walljumpCount = true;
        s.walljumping = true;
        s.dashing = false;
        s.walljumpTime = PM_WALLJUMP_TIMEDELAY;

        return new Vec3d(hv.x, Math.max(oldUp, PM_WJUPSPEED), hv.z);
    }

    private static Vec3d findWallNormal(PlayerEntity player) {
        Box box = player.getBoundingBox();
        double hw = box.getLengthX() / 2;
        double hd = box.getLengthZ() / 2;
        Vec3d vel = player.getVelocity();
        double velOffset = Math.abs(vel.x) * 0.015 + Math.abs(vel.z) * 0.015;

        // check 12 radial directions
        for (int i = 0; i < 12; i++) {
            double angle = (2 * Math.PI / 12) * i;
            double dx = Math.cos(angle) * (hw + velOffset + 0.1);
            double dz = Math.sin(angle) * (hd + velOffset + 0.1);
            Box probe = box.offset(dx, 0, dz);
            if (!player.getEntityWorld().isSpaceEmpty(player, probe)) {
                return new Vec3d(dx, 0, dz).normalize();
            }
        }
        return null;
    }

    // ================================================================
    //  WATER
    // ================================================================
    private static Vec3d waterMove(Vec3d vel, Vec3d forward, Vec3d right,
                                    float fwdPush, float sidePush, float maxSpeed, float ft) {
        Vec3d wishvel = new Vec3d(
            forward.x * fwdPush + right.x * sidePush,
            forward.y * fwdPush + right.y * sidePush,
            forward.z * fwdPush + right.z * sidePush
        );
        if (fwdPush == 0 && sidePush == 0) {
            wishvel = wishvel.add(0, -60 * ft, 0);
        }
        double wl = wishvel.length();
        if (wl > 0.001) {
            Vec3d wd = wishvel.normalize();
            float ws = (float) Math.min(wl, maxSpeed) * 0.5f;
            vel = accelerate(vel, wd, ws, 10, ft);
        }
        return vel;
    }

    // ================================================================
    //  GROUND
    // ================================================================
    private static Vec3d groundMove(Vec3d vel, Vec3d wishdir, float wishspeed, float ft) {
        if (vel.y > 0) vel = new Vec3d(vel.x, 0, vel.z);
        vel = accelerate(vel, wishdir, wishspeed, PM_ACCELERATE, ft);
        return vel.add(0, -GRAVITY * ft, 0);
    }

    // ================================================================
    //  AIR
    // ================================================================
    private static Vec3d airMove(Vec3d vel, Vec3d wishdir, float wishspeed,
                                  float sidePush, float fwdPush,
                                  PlayerMoveState s, float ft, float maxSpeed) {
        float wishspeed2 = wishspeed;
        boolean decel = vel.dotProduct(wishdir) < 0;
        float accel = (decel && !s.walljumping) ? PM_AIRDECELERATE : PM_AIRACCELERATE;
        if (s.walljumping || s.dashing) accel = 0;

        // +strafe bunnyhop
        boolean strafeBunny = sidePush != 0 && fwdPush == 0;
        if (strafeBunny && wishspeed > PM_WISHSPEED) {
            wishspeed = PM_WISHSPEED;
            accel = PM_STRAFE_BUNNY_ACCEL;
        }

        boolean fwdBunny = true;
        boolean accelerating = vel.dotProduct(wishdir) > 0;
        boolean inhibit = s.walljumping || s.dashing;

        if (fwdBunny && !inhibit && accelerating && sidePush == 0 && fwdPush != 0) {
            // forward bunnyhop
            vel = airAccelerate(vel, wishdir, wishspeed, maxSpeed, ft);
        } else {
            vel = accelerate(vel, wishdir, wishspeed, accel, ft);
            if (!s.walljumping && !s.dashing) {
                vel = airControl(vel, wishdir, wishspeed2, ft);
            }
        }

        return vel.add(0, -GRAVITY * ft, 0);
    }

    private WarsowPmove() {}
}
