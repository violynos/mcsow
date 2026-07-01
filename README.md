# McSow

Port Warsow/Warfork movement (dash, walljump, bunnyhop, air control) into a Fabric Minecraft mod.

## Goal

Implement Quake/Warsow-style physics in Minecraft: air acceleration, bunnyhopping, walljumping, and dash mechanics.

## Constraints

- Fabric mod loader
- Dash bound to R key (Warsow right-click equivalent)
- Target Minecraft 1.21.11
- Loom 1.14.10, Gradle 9.6.1, JDK 21 (runtime), Java 17 (mod target)

## Progress

### Done
- Project scaffolded: `build.gradle`, `gradle.properties`, `settings.gradle`, `fabric.mod.json`, `mcsow.mixins.json`
- Gradle wrapper generated with Gradle 9.6.1
- JDK 21 installed
- Core `gs_pmove.c` ported to `WarsowPmove.java` (friction, accel, air accelerate, air control, jump, dash, walljump, crouch, water)
- `PlayerEntityMixin` cancels `travel()` and routes to Warsow physics
- `ClientPlayerMixin` reads R key press and pushes state via `SpecialState` map
- `McSowClientMod` registers `key.mcsow.special` (default R)
- `.gitignore`, `setup.sh`, `install-jdk.sh`

### In Progress
- Fixing Yarn 1.21.11 mapping names so Java compilation succeeds

### Blocked
- Compilation errors: incorrect Yarn method/field names for 1.21.11 (`player.input`, `player.getWorld()`, `box.getXLength()`, `player.isFallFlying()`, `KeyBinding` constructor category type)

## Key Decisions

- Dash/walljump uses a static `Map<entityId, boolean>` (`SpecialState`) instead of cross-mixin `@Unique` field access
- Player movement replaced by cancelling `travel()` entirely in a `@Inject(cancellable)` mixin, not item-by-item edits
- `Vec3d` treated as immutable (all helpers return new `Vec3d`)

## Next Steps

1. Resolve Yarn mapping errors — see `AGENTS.md` for 1.21.11 API differences
2. Get `./gradlew build` green
3. Test in-game with PrismLauncher (1.21.11 instance)
4. Add server-side special-key networking so dash/walljump work on dedicated servers

## Critical Context

- Yarn branch `1.21.11`; merged jar at Fabric Loom's minecraftMaven cache
- Walljump wall detection uses simple AABB-offset probes instead of proper hull traces
- Current friction skips water checks and `SURF_SLICK` detection from the original C code

## Relevant Files

- `src/main/java/com/mcsow/movement/WarsowPmove.java` — core physics engine (full gs_pmove.c port)
- `src/main/java/com/mcsow/mixin/PlayerEntityMixin.java` — cancels `travel()`, calls `WarsowPmove.move()`
- `src/main/java/com/mcsow/mixin/ClientPlayerMixin.java` — feeds R-key state into `SpecialState`
- `src/main/java/com/mcsow/movement/SpecialState.java` — entityId→boolean map for special-button state
- `build.gradle` — Loom 1.14.10, Java release 17, Yarn 1.21.11+build.6
- `gradle.properties` — mod version 0.1.0, Fabric API 0.136.0+1.21.11, loader 0.16.10
- `setup.sh` — installs JDK 21 + runs `./gradlew build`
