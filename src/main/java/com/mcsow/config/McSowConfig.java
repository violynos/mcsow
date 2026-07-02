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

        // --- movement tunables (defaults = current tuning) ---
        public float gravity         = 1120.0f;             // downward accel (Warsow units/s^2)
        public float jumpSpeed       = 280.0f * 1.4f;       // 392  — jump up-velocity
        public float dashSpeed       = 450.0f;              // minimum dash horizontal speed
        public float dashUpSpeed     = 174.0f * 1.15f * 1.4f; // 280.14 — dash vertical velocity
        public float wallJumpUpSpeed = 330.0f * 1.09f * 1.4f; // 503.58 — walljump vertical velocity
        public float airAccelerate   = 1.075f;              // quake-strafe / air accel
        public float airControl      = 150.0f;              // air-control redirect strength
        public int   airSubsteps     = 3;                   // air physics sub-steps per tick
        public float crouchJumpRatio = 0.75f;               // fraction of horizontal → vertical
    }

    public static Data get() {
        if (INSTANCE == null) load();
        return INSTANCE;
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
