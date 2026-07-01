package com.mcsow.movement;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SpecialState {
    private static final Map<Integer, Boolean> held = new ConcurrentHashMap<>();

    public static void set(int entityId, boolean down) {
        if (down) held.put(entityId, true);
        else held.remove(entityId);
    }

    public static boolean isDown(int entityId) {
        return held.containsKey(entityId);
    }

    private SpecialState() {}
}
