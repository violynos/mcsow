# McSow

**McSow is a Minecraft mod that implements [Warsow](https://en.wikipedia.org/wiki/Warsow_(video_game))/Warfork movement in Minecraft as faithfully and cleanly as possible.**

It replaces vanilla player motion entirely — cancelling `travel()` in a mixin and running a direct port of Warsow's `gs_pmove.c` physics — so you get real air-strafing, bunnyhopping, dashing, and walljumping in Minecraft.

## A note on the values

The physics *formulas* are a faithful port of Warsow's, but some *constants* were deliberately retuned to fit Minecraft's scale and block grid rather than copied 1:1:

- **Dash is a touch stronger than in Warfork**, tuned so a dash clears roughly half a block — enough to feel right against Minecraft's terrain.
- **Crouch-jumping was added** (not a Warsow mechanic) to trade horizontal speed for height, so you can pop precisely onto blocks and handle awkward terrain.
- **Gravity/jump baseline is intentionally non-Warsow** (scaled for Minecraft feel), and air-strafe gain is sub-stepped to make up for Minecraft's 20 tps.
- Movement respects Minecraft **modifiers** — Speed, Soul Speed, Jump Boost, Swift Sneak, sprint, and the movement-speed attribute all scale the physics.

Most of these are tunable in-game (see Configuration).

## Dependencies

- **Fabric Loader** ≥ 0.16.0
- **Fabric API**
- **Minecraft** 1.21.11
- **Mod Menu** *(optional)* — for the in-game configuration screen

## Controls

- **Movement / jump / sneak** — your normal Minecraft keys
- **Dash / Special** — `R` by default (rebindable in Controls). Used for dashing on the ground and walljumping in the air.

## Movement tech

- **Quake strafing** — hold forward + a strafe key and turn the mouse so your aim is ~80° off your velocity to gain speed.
- **Source strafing** — hold a single strafe key and turn the mouse that way to accelerate.
- **Air control** — hold forward and turn the mouse to curve your trajectory without losing speed (sharp turns bleed a little).
- **Bunnyhopping** — chain jumps to preserve and build momentum; ground friction only bites when you're actually on the ground.
- **Dash** — tap Special on the ground to dash; direction follows your movement keys, or the camera if none are held.
- **Dash turning** — release every movement key mid-air and land with Special held: your velocity vector rotates to point where the camera is looking (redirect your momentum in a new direction).
- **Walljump** — in the air, hug a wall and press Special to launch away from it (also counts as a dash).
- **Crouch-jump** — jump while sneaking to convert most of your horizontal speed into height (great for awkward terrain; at high speed it launches you upward).
- **Momentum preservation** — your real velocity is tracked through creative flight and elytra, so you don't lose all your speed the moment you stop flying or land a glide.

## Configuration

- `config/mcsow.json` — `{"enabled": true}` toggles the mod's movement on/off.
- With **Mod Menu** installed, open the config screen from the mods list to tune the movement constants (air acceleration, dash height, walljump height, crouch-jump ratio, gravity, sub-steps, …). Each value has hover text explaining what it does and how it affects movement.

## Building

```bash
./gradlew build          # jar → build/libs/
./buildvio.sh            # build + copy the jar into the PrismLauncher "Mod Testing" instance
```

- **Toolchain:** Loom 1.14.10, Gradle 9.6.1, JDK 21 runtime, Java 17 mod target, yarn `1.21.11+build.6`.

## License

GPL-2.0
