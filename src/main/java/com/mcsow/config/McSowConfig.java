package com.mcsow.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mcsow.movement.WarsowPmove;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistent config for McSow. Values live in {@link Data}; field initializers are the
 * defaults (the current in-game tuning). Loaded once at startup and re-saved from the
 * Mod Menu config screen; {@link #apply()} pushes values into {@link WarsowPmove}.
 */
public final class McSowConfig {
    private static final Path PATH = Path.of("config", "mcsow.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Data INSTANCE;

    private McSowConfig() {}

    /** Serializable config holder. Field initializers = default values. */
    public static final class Data {
        public boolean enabled = true;
        public boolean strafeHud = false;    // show the strafe HUD overlay
        public boolean speedOnXpBar = false; // show speed as the XP-bar level number (hides the HUD's own number)

        // --- movement tunables (defaults = raw Warfork values, at the correct 9/320 scale) ---
        public float gravity         = 800.0f;              // downward accel (Warfork units/s^2)
        public float jumpSpeed       = 280.0f;              // jump up-velocity
        public float dashSpeed       = 450.0f;              // minimum dash horizontal speed
        public float dashUpSpeed     = 174.0f;              // dash vertical velocity
        public float wallJumpUpSpeed = 330.0f;              // walljump vertical velocity
        public float airAccelerate   = 1.0f;                // quake-strafe / air accel
        public float airControl      = 150.0f;              // air-control redirect strength
        public int   airSubsteps     = 3;                   // air physics sub-steps per tick
        public float hungerMultiplier = 3.0f;               // ×vanilla exhaustion (hunger drain rate)
    }

    public static Data get() {
        if (INSTANCE == null) load();
        return INSTANCE;
    }

    /** The strafe HUD / SOW GUI is only active when BOTH the master switch and the HUD toggle are on. */
    public static boolean hudActive() {
        Data d = get();
        return d.enabled && d.strafeHud;
    }

    public static void load() {
        try {
            if (Files.exists(PATH)) {
                INSTANCE = GSON.fromJson(Files.readString(PATH), Data.class);
            }
        } catch (Exception ignored) {
            // fall through to defaults
        }
        if (INSTANCE == null) INSTANCE = new Data();
        apply();
    }

    public static void save() {
        if (INSTANCE == null) INSTANCE = new Data();
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(INSTANCE));
        } catch (IOException ignored) {}
        apply();
    }

    /** Push config into the movement engine. */
    public static void apply() {
        WarsowPmove.setEnabled(INSTANCE.enabled);
        WarsowPmove.applyConfig(INSTANCE);
    }
}
