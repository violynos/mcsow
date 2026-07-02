# McSow — Warsow Movement Fabric Mod

Ports Warsow/Warfork movement (dash, walljump, bunnyhop, air control) into Minecraft, replacing vanilla player motion entirely by cancelling `travel()` in a mixin.

## File Structure

```
/home/vio/git/mcsow/
├── build.gradle              — Loom 1.14.10, Java 17 target
├── gradle.properties         — mod_version=1.1.2, yarn 1.21.11+build.6
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
2. **Jump** check (`checkJump`): sets `vel.y = DEFAULT_JUMPSPEED` (never additive), resets dash/walljump state
3. Timer decrements (dashTime, crouchTime, forwardTime, crouchSlideTime)
4. Dash guard: skip dash if `s.jumped || (onGround && jumpPressed)`
5. **Dash** check (`checkDash`): input-based direction (WASD relative to camera) or camera-forward, sets vertical to `PM_DASHUPSPEED`, horizontal to max(current speed, DEFAULT_DASHSPEED)
6. Recheck ground after jump/dash (`vel.y > 1.0f` → set airborne)
7. `stayAirborne`: skip friction/groundMove when landing with trigger
8. **Friction** (`applyFriction`): horizontal only, Warsow formula with control = max(spd, PM_DECELERATE)
9. Wish direction from forward/right vectors × WASD input
10. **Ground move** or **Air move** (gravity only in air currently; air control deferred)
11. Store velocity → `player.setVelocity(delta)` + `player.move(MovementType.SELF, delta)`

## Key Constants (current raw Warsow values — ×1.4 via GRAVITY)

| Constant | Base | × GRAVITY_COMPENSATE | Notes |
|---|---|---|---|
| GRAVITY | 1120.0 | — | 800 × 1.4; GRAVITY_SCALE = 1.0 |
| GRAVITY_COMPENSATE | — | GRAVITY/800 = 1.4 | Auto-scales all velocity constants |
| DEFAULT_JUMPSPEED | 280.0 | 392.0 | Jump vertical velocity |
| PM_DASHUPSPEED | 174.0 | 243.6 | Dash vertical velocity |
| PM_FRICTION | 8.0 | — | Warsow default |
| PM_ACCELERATE | 12.0 | — | Ground acceleration |
| PM_DECELERATE | 12.0 | — | Friction control threshold |
| PM_AIRACCELERATE | 1.0 | — | Air acceleration |
| PM_AIRDECELERATE | 2.0 | — | Air deceleration |
| DEFAULT_PLAYERSPEED | 320.0 | — | Base max speed |
| DEFAULT_DASHSPEED | 450.0 | — | Minimum dash horizontal speed |
| UNIT_SCALE | 0.01875 | — | Warsow units → MC blocks |
| FT | 0.05 | — | 20 ticks/sec |

## Dead/Walljump (REMOVED)

Walljump was removed due to inconsistent collision detection. `checkWalljump()` and `findWallNormal()` code still exists in `WarsowPmove.java` but is never called. Timer decrement for `walljumpTime` was also removed. Will revisit with better collision solution.

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
- Phase 3: Air movement (air control, bunnyhop, WASD in air)
- Phase 4: Water move, crouch slide
- Phase 5: Server-side special-key networking for dedicated servers
- Improve walljump collision detection (deferred)
