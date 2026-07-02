# McSow — Warsow Movement Fabric Mod

Ports Warsow/Warfork movement (dash, walljump, bunnyhop, air control) into Minecraft, replacing vanilla player motion entirely by cancelling `travel()` in a mixin.

## File Structure

```
/home/vio/git/mcsow/
├── build.gradle              — Loom 1.14.10, Java 17 target
├── gradle.properties         — mod_version=1.2.3, yarn 1.21.11+build.6
├── buildvio.sh               — builds + copies to PrismLauncher mods
├── src/main/java/com/mcsow/
│   ├── McSowMod.java         — common init, loads config
│   ├── McSowClientMod.java   — client init, registers R key, reads press state
│   ├── config/McSowConfig.java — reads/writes config/mcsow.json (enabled toggle)
│   ├── mixin/
│   │   ├── PlayerEntityMixin.java — cancels travel(), calls move() for ClientPlayerEntity only; server just cancels
│   │   └── ClientPlayerMixin.java — feeds R-key state into SpecialState
│   └── movement/
│       ├── WarsowPmove.java  — core physics engine (full gs_pmove.c port)
│       ├── SpecialState.java — entityId→boolean ConcurrentHashMap for special button
│       └── PlayerMoveState   — inner class in WarsowPmove: velocity, timers, flags
├── src/main/resources/
│   ├── fabric.mod.json
│   ├── mcsow.mixins.json
│   └── assets/mcsow/lang/en_us.json
```

## Architecture

- **Mixin**: `PlayerEntityMixin` injects at `travel()` HEAD, cancels it.
- **Client only**: Only `ClientPlayerEntity` runs `WarsowPmove.move()`. Server-side `ServerPlayerEntity` just cancels `travel()` and does nothing — server receives position updates from client via normal MC networking.
- **State**: `HashMap<Integer, PlayerMoveState>` keyed by entity ID. Shared between client/server via the same JVM (single player). Only the client thread accesses it now.
- **`mcDelta(wsVal)`**: `wsVal * FT * UNIT_SCALE` converts Warsow velocity → MC blocks position delta per tick.
- **Config**: `config/mcsow.json` with `{"enabled": false}` to disable mod movement.

## Current Physics Flow (each tick)

1. `onGround` / `justLanded` detection (`wasInAir` from previous tick)
2. **Jump** check (`checkJump`): sets `vel.y = DEFAULT_JUMPSPEED` (never additive), resets dash state. **Crouch-jump**: if crouched, converts 75% of horizontal speed to vertical (`vel.y = DEFAULT_JUMPSPEED + 0.75·hspeed`) and keeps 25% horizontal (`vx,vz ×= 0.25`) — trade momentum for height
3. Timer decrements (dashTime, crouchTime, forwardTime, crouchSlideTime)
4. Dash guard: skip dash if `s.jumped || (onGround && jumpPressed)`
5. **Dash** check (`checkDash`): input-based direction (WASD relative to camera) or camera-forward, sets vertical to `PM_DASHUPSPEED`, horizontal to max(current speed, DEFAULT_DASHSPEED)
6. Recheck ground after jump/dash (`vel.y > 1.0f` → set airborne)
7. `stayAirborne`: skip friction/groundMove when landing with trigger
8. **Friction** (`applyFriction`): horizontal only, Warsow formula with control = max(spd, PM_DECELERATE)
9. Wish direction from forward/right vectors × WASD input
10. **Ground move** (one step) or **Air move** (sub-stepped `AIR_SUBSTEPS`×/tick): exact port of Warsow's "Air Control" branch — `PM_Accelerate` (+strafe bunny accel) then `PM_Aircontrol` redirect, plus gravity per sub-step. Displacement accumulated across sub-steps.
11. Convert accumulated Warsow-unit displacement → MC blocks via `UNIT_SCALE`, then `player.setVelocity(delta)` + `player.move(MovementType.SELF, delta)`

## Key Constants (current raw Warsow values — ×1.4 via GRAVITY)

| Constant | Base | × GRAVITY_COMPENSATE | Notes |
|---|---|---|---|
| GRAVITY | 1120.0 | — | 800 × 1.4; GRAVITY_SCALE = 1.0 |
| GRAVITY_COMPENSATE | — | GRAVITY/800 = 1.4 | Auto-scales all velocity constants |
| DEFAULT_JUMPSPEED | 280.0 | 392.0 | Jump vertical velocity |
| PM_DASHUPSPEED | 174.0 × 1.15 = 200.1 | 280.14 | Dash vertical velocity (tuned ×1.15) |
| PM_FRICTION | 8.0 | — | Warsow default |
| PM_ACCELERATE | 12.0 | — | Ground acceleration |
| PM_DECELERATE | 12.0 | — | Friction control threshold |
| PM_AIRACCELERATE | 1.075 | — | Air accel / quake-strafe gain (Warsow 1.0, tuned ×1.075) |
| PM_AIRDECELERATE | 2.0 | — | Air deceleration |
| DEFAULT_PLAYERSPEED | 320.0 | — | Base max speed |
| DEFAULT_DASHSPEED | 450.0 | — | Minimum dash horizontal speed |
| UNIT_SCALE | 0.01875 | — | Warsow units → MC blocks |
| FT | 0.05 | — | 20 ticks/sec |

## Walljump (REMOVED — deferred)

Walljump was disabled due to inconsistent collision detection, and all its code has since been deleted from `WarsowPmove.java`: `checkWalljump()`, `findWallNormal()`, `clipVelocity()` (only walljump used it), the `PM_WJ*`/`PM_WALLJUMP_*` constants, and the `walljumpTime`/`walljumpCount`/`walljumping` state fields. Recover it from git history (commit "Remove dead walljump code") when revisiting with a better collision solution.

## Build & Deploy

- **Build**: `./buildvio.sh` — prints version, runs `./gradlew build`, cleans old jars from PrismLauncher mods, copies current jar.
- **PrismLauncher instance**: "Mod Testing" at `~/.local/share/PrismLauncher/instances/Mod Testing/minecraft/mods/`
- **JDK**: Java 21 runtime (Gradle), Java 17 target (mod bytecode)

## History of Fixes

1. **Zero-movement bug**: Mixin cancels `travel()` but must call `player.move()` after `setVelocity()`
2. **Vertical compounding (dash)**: Both client+server applied physics. Starving server fixed it, plus `upSpeed` always = PM_DASHUPSPEED (not additive)
3. **Vertical compounding (jump)**: Same pattern — always set `vel.y = DEFAULT_JUMPSPEED` instead of adding to existing vel.y
4. **Client-server desync**: Stopped server from running physics entirely. Only ClientPlayerEntity runs move(). Server just cancels travel().
5. **Gravity scaling**: GRAVITY = 1120 (×1.4) via separate multiplier, velocities auto-scale via GRAVITY_COMPENSATE

## Next Steps

- Fine-tune constants (jump height, dash height, friction, gravity)
- ~~Phase 3: Air movement (air control, bunnyhop, WASD in air)~~ — DONE (v1.2.1): exact port of Warsow "Air Control" mode (`PMFEAT_AIRCONTROL`, no fwdbunny). WASD alone does not accelerate; speed comes from strafe accel + air-control redirect. Sub-stepped `AIR_SUBSTEPS=3`×/tick. Ground/jump/dash constants kept on the existing (non-Warsow) 1.4-gravity baseline per user; retune air by feel later.
- NOTE: mod's gravity baseline is NOT Warsow-exact — real Warsow is `GRAVITY=850`, `BASEGRAVITY=800`, `GRAVITY_COMPENSATE=1.0625`, `DEFAULT_DASHSPEED=475`. Mod currently uses `GRAVITY=1120`, compensate `1.4`, dash `450`. Reconcile if going for a full replica.
- **NEXT — Collision + walljump (user roadmap):**
  1. **Collision fix (prerequisite):** our `player.move()` delta doesn't truly collide — when jumping under a block the player floats for the jump duration because the server snaps us back each tick. Fix: interpolate between the pre-collision frame and the frame we contact a wall/ceiling/floor to place the player exactly flush with the block hitbox, then clamp the velocity direction so we can move *away* from the surface but never attempt to move *into* it.
  2. **Reset vertical speed on ground contact** (currently only zeroed on jump/dash) — needed so landing under/near blocks behaves.
  3. **Clamp x/z velocity when hitting a wall.**
  4. **Walljump:** when hugging a wall and pressing dash (optionally with a direction), launch away from the wall — direction is always away from the wall normal regardless of input. Restore the removed `checkWalljump`/`findWallNormal` from git history and adapt to the new collision model.
- Phase 4: Water move, crouch slide
- Phase 5: Server-side special-key networking for dedicated servers
