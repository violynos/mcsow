# McSow — Warsow Movement Fabric Mod

Ports Warsow/Warfork movement (dash, walljump, bunnyhop, air control) into Minecraft, replacing vanilla player motion entirely by cancelling `travel()` in a mixin.

## File Structure

```
/home/vio/git/mcsow/
├── build.gradle              — Loom 1.14.10, Java 17 target
├── gradle.properties         — mod_version=1.5.2, yarn 1.21.11+build.6
├── buildvio.sh               — builds + copies to PrismLauncher mods
├── src/main/java/com/mcsow/
│   ├── McSowMod.java         — common init, loads config
│   ├── McSowClientMod.java   — client init, registers R key, reads press state
│   ├── config/McSowConfig.java — Gson Data holder (enabled + movement tunables), load/save/apply
│   ├── config/McSowConfigScreen.java — Cloth Config screen with per-value hover tooltips
│   ├── config/ModMenuIntegration.java — Mod Menu entrypoint → config screen
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
- **Velocity sync (v1.3.2)**: while vanilla controls motion (creative fly, elytra glide, spectator, vehicle, or disabled), the mixin calls `WarsowPmove.syncFromActual()` each tick to keep the internal Warsow-unit velocity aligned with the player's real MC velocity — so momentum carries over when Warsow movement resumes (fixes losing all speed the frame you stop flying / land with elytra).
- **Config screen (v1.5.0)**: Mod Menu (`17.0.0`) + Cloth Config (`21.11.153`), added as optional (`recommends`) deps. `McSowConfig.Data` is a Gson holder whose field defaults are the current tuning; `McSowConfig.apply()` pushes values into `WarsowPmove.applyConfig()`. The exposed movement constants (`GRAVITY`, `DEFAULT_JUMPSPEED`, `DEFAULT_DASHSPEED`, `PM_DASHUPSPEED`, `PM_WJUPSPEED`, `PM_AIRACCELERATE`, `PM_AIRCONTROL`, `AIR_SUBSTEPS`, `CROUCH_JUMP_RATIO`) were changed from `static final` to mutable `static` fields. `GRAVITY_COMPENSATE` is now a fixed 1.4 literal (no longer derived from GRAVITY, so tuning gravity doesn't desync jump/dash defaults). Each screen entry has hover text explaining the value.
- **MC modifier scaling (v1.4.0)**: `move()` takes raw input (−1..1) and scales by `playerMaxSpeed(player)` = `DEFAULT_PLAYERSPEED × (movementSpeedAttr / baseAttr)`, so the **movement-speed attribute** carries Speed potion, Soul Speed, sprint, and item/attribute modifiers automatically. **Jump Boost**: `jumpBoostBonus()` adds `0.1·(amp+1)` blocks/tick (→ Warsow units) to jump velocity. **Sneaking** (on ground): speed × `sneakFactor` = `0.3 + 0.15·swiftSneakLevel` (Swift Sneak max 3 → 0.75), per the MC wiki. API verified against `yarn-1.21.11+build.6` mappings: `EntityAttributes.MOVEMENT_SPEED`, `StatusEffects.JUMP_BOOST`, `Enchantments.SWIFT_SNEAK` via dynamic registry.

## Current Physics Flow (each tick)

1. `onGround` / `justLanded` detection (`wasInAir` from previous tick)
2. **Jump** check (`checkJump`): sets `vel.y = jumpSpeed` (never additive), resets dash state. **Crouch-jump** (v1.4.0a): if crouched, always converts 75% of horizontal speed to vertical (`vel.y = jumpSpeed + 0.75·hspeed`) and keeps 25% horizontal — trade momentum for height; at high speed the big vertical component launches you upward. (The earlier separate keep-horizontal "launch" branch was removed — it contradicted crouch-jump's speed-reduction.)
3. Timer decrements (dashTime, crouchTime, forwardTime, crouchSlideTime)
4. Dash guard: skip dash if `s.jumped || (onGround && jumpPressed)`
5. **Dash** check (`checkDash`): input-based direction (WASD relative to camera) or camera-forward, sets vertical to `PM_DASHUPSPEED`, horizontal to max(current speed, DEFAULT_DASHSPEED)
5b. **Walljump** (`checkWalljump`): airborne + special pressed + hugging a wall → launch away from the wall normal (clip into-wall component, restore speed min `PM_WJMINSPEED`, up-boost `PM_WJUPSPEED`). Wall normal from `findWallNormal` (4 cardinal box probes). Cooldown `PM_WALLJUMP_TIMEDELAY`.
6. Recheck ground after jump/dash (`vel.y > 1.0f` → set airborne); reset vertical on ground contact (`onGround && vel.y<0 → vel.y=0`)
7. `stayAirborne`: skip friction/groundMove when landing with trigger
8. **Friction** (`applyFriction`): horizontal only, Warsow formula with control = max(spd, PM_DECELERATE)
9. Wish direction from forward/right vectors × WASD input
10. **Ground move** (one step) or **Air move** (sub-stepped `AIR_SUBSTEPS`×/tick): exact port of Warsow's "Air Control" branch — `PM_Accelerate` (+strafe bunny accel) then `PM_Aircontrol` redirect, plus gravity per sub-step. Air accel/control inhibited during the walljump launch (`s.walljumping`). Displacement accumulated across sub-steps.
11. Convert accumulated displacement → MC blocks via `UNIT_SCALE`; `player.move()` sweeps collisions. **Collision reconciliation**: compare intended vs actual per-axis movement; a blocked axis (moved less than intended) has its internal velocity zeroed — fixes float-under-block (ceiling kills upward vel → fall), lands cleanly, and clamps into-wall velocity while still allowing motion away from the wall. **Wall-momentum buffer (v1.4.1)**: a horizontal (wall) clamp saves the lost speed and, for `WALL_BUFFER_FRAMES=4` ticks, restores it if that direction opens up (corner-skip). Vertical (floor/ceiling) is not buffered. **Step-up (v1.5.1, mid-air in v1.5.2)**: on a horizontal collision with a surface within `STEP_GROUND_DROP=1` block below the feet (`hasGroundBelow`, footprint-only so it works mid-air near ground, not just grounded), `tryStepUp` probes whether the obstacle is a low ledge (clear at some height ≤ `STEP_UP_HEIGHT=0.6`, e.g. a slab); if so the player is snapped up (`setPosition` +step) and horizontal speed is kept instead of clamped, for smooth slab stepping.

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

## Walljump (RE-IMPLEMENTED v1.3.0)

Restored and rebuilt on top of the new collision reconciliation. `checkWalljump()`: when airborne, holding a fresh special press, off cooldown, and hugging a wall, launch away from the wall. `findWallNormal()` now probes the **4 cardinal directions** (MC blocks are axis-aligned, so this is more reliable than the old 12-way diagonal probe that caused the original "inconsistent collision" removal). Launch = clip into-wall component + outward `PM_WJBOUNCEFACTOR` + restore speed (min `PM_WJMINSPEED=240`) + up-boost `PM_WJUPSPEED=330×1.09×1.4` (height tuned ×1.09). A walljump **counts as a dash** — it sets `dashing` and starts the dash cooldown (`dashTime = PM_DASHJUMP_TIMEDELAY`). State: `walljumpTime` (cooldown `PM_WALLJUMP_TIMEDELAY=1300`), `walljumpCount` (once per wall contact), `walljumping` (launch window, inhibits air accel/control until apex). Shares the special key with dash; dash fires on ground, walljump in the air.

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
- ~~Collision + walljump (user roadmap)~~ — DONE (v1.3.0):
  1. ~~Collision fix (float-under-block).~~ Done via per-axis reconciliation after `player.move()` (MC already places flush; we zero the blocked axis's internal velocity).
  2. ~~Reset vertical speed on ground contact.~~ Done (`onGround && vel.y<0 → 0`, plus reconciliation on landing).
  3. ~~Clamp x/z velocity on wall hit.~~ Done via reconciliation.
  4. ~~Walljump.~~ Done (`checkWalljump`, 4-cardinal `findWallNormal`).
  - Tuning candidates by feel: `PM_WJUPSPEED`, `PM_WJMINSPEED`, `WJ_WALL_PROBE`, `PM_WALLJUMP_TIMEDELAY`.
- ~~Crouch movement + MC modifiers~~ — DONE (v1.3.3/1.4.0): crouch launch (land at speed + jump + crouch), sneak slowdown (0.3× + Swift Sneak), and modifier scaling (movement-speed attribute → Speed/Soul Speed/sprint/attributes; Jump Boost). Tuning candidates: `CROUCH_LAUNCH_FACTOR`, `CROUCH_LAUNCH_MIN_SPEED`; whether sprint should stack on the base 320 (currently walking = 320, sprint scales up from there).
- Phase 4: Water move, crouch slide
- Phase 5: Server-side special-key networking for dedicated servers
