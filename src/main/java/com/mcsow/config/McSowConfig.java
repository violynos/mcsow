package com.mcsow.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcsow.movement.WarsowPmove;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class McSowConfig {
    private static final Path CONFIG_PATH = Path.of("config", "mcsow.json");

    private McSowConfig() {}

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            save(true);
            return;
        }
        try {
            String text = Files.readString(CONFIG_PATH);
            JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
            boolean enabled = obj.has("enabled") ? obj.get("enabled").getAsBoolean() : true;
            WarsowPmove.setEnabled(enabled);
        } catch (Exception e) {
            WarsowPmove.setEnabled(true);
        }
    }

    private static void save(boolean enabled) {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            Files.writeString(CONFIG_PATH, obj.toString());
        } catch (IOException ignored) {}
    }
}
