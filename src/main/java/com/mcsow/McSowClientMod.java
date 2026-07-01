package com.mcsow;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class McSowClientMod implements ClientModInitializer {
    public static KeyBinding specialKey;
    private static boolean specialPressed = false;

    @Override
    public void onInitializeClient() {
        specialKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.mcsow.special",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            KeyBinding.Category.MISC
        ));

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            specialPressed = specialKey.isPressed();
        });
    }

    public static boolean isSpecialDown() {
        return specialPressed;
    }
}
