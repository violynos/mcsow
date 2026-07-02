package com.mcsow.config;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Cloth Config screen for the movement tunables, opened from Mod Menu. Each entry has
 * hover text explaining what it does and how it affects movement. Client-only: this class
 * is only referenced from the "modmenu" entrypoint, so it isn't loaded server-side.
 */
public final class McSowConfigScreen {
    private McSowConfigScreen() {}

    public static Screen create(Screen parent) {
        McSowConfig.Data cfg = McSowConfig.get();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.literal("McSow Movement"))
                .setSavingRunnable(McSowConfig::save);

        ConfigEntryBuilder eb = builder.entryBuilder();

        ConfigCategory general = builder.getOrCreateCategory(Text.literal("General"));
        general.addEntry(eb.startBooleanToggle(Text.literal("Enabled"), cfg.enabled)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Master switch. When off, vanilla Minecraft movement is used instead of Warsow physics."))
                .setSaveConsumer(v -> cfg.enabled = v)
                .build());

        ConfigCategory air = builder.getOrCreateCategory(Text.literal("Air"));
        air.addEntry(eb.startFloatField(Text.literal("Air acceleration"), cfg.airAccelerate)
                .setDefaultValue(1.075f)
                .setTooltip(
                    Text.literal("How hard you accelerate mid-air toward your wish direction."),
                    Text.literal("Drives quake-strafe / strafe-jump speed gain — higher = faster speed build."),
                    Text.literal("Warsow default 1.0; McSow default 1.075."))
                .setSaveConsumer(v -> cfg.airAccelerate = v)
                .build());
        air.addEntry(eb.startFloatField(Text.literal("Air control"), cfg.airControl)
                .setDefaultValue(150.0f)
                .setTooltip(
                    Text.literal("Strength of curving your velocity toward where you look (hold forward, no strafe key)."),
                    Text.literal("Higher = tighter air turns without losing speed. 0 disables air control."))
                .setSaveConsumer(v -> cfg.airControl = v)
                .build());
        air.addEntry(eb.startIntField(Text.literal("Air sub-steps / tick"), cfg.airSubsteps)
                .setDefaultValue(3).setMin(1).setMax(8)
                .setTooltip(
                    Text.literal("Runs the air physics integration this many times per tick."),
                    Text.literal("Approximates Warsow's higher tick rate — higher = smoother/stronger strafing, a little more CPU."))
                .setSaveConsumer(v -> cfg.airSubsteps = v)
                .build());

        ConfigCategory dash = builder.getOrCreateCategory(Text.literal("Dash & Walljump"));
        dash.addEntry(eb.startFloatField(Text.literal("Dash speed (minimum)"), cfg.dashSpeed)
                .setDefaultValue(450.0f)
                .setTooltip(Text.literal("Minimum horizontal speed a dash gives you (keeps your current speed if it's already faster)."))
                .setSaveConsumer(v -> cfg.dashSpeed = v)
                .build());
        dash.addEntry(eb.startFloatField(Text.literal("Dash up-speed"), cfg.dashUpSpeed)
                .setDefaultValue(174.0f * 1.15f * 1.4f)
                .setTooltip(
                    Text.literal("Vertical velocity added by a dash — the dash's hop height."),
                    Text.literal("Tuned so a dash clears about half a block in Minecraft."))
                .setSaveConsumer(v -> cfg.dashUpSpeed = v)
                .build());
        dash.addEntry(eb.startFloatField(Text.literal("Walljump up-speed"), cfg.wallJumpUpSpeed)
                .setDefaultValue(330.0f * 1.09f * 1.4f)
                .setTooltip(Text.literal("Vertical velocity of a walljump — how high you pop when you kick off a wall."))
                .setSaveConsumer(v -> cfg.wallJumpUpSpeed = v)
                .build());

        ConfigCategory jump = builder.getOrCreateCategory(Text.literal("Jump & Gravity"));
        jump.addEntry(eb.startFloatField(Text.literal("Jump speed"), cfg.jumpSpeed)
                .setDefaultValue(280.0f * 1.4f)
                .setTooltip(Text.literal("Upward velocity of a normal jump. Higher = you jump higher."))
                .setSaveConsumer(v -> cfg.jumpSpeed = v)
                .build());
        jump.addEntry(eb.startFloatField(Text.literal("Gravity"), cfg.gravity)
                .setDefaultValue(1120.0f)
                .setTooltip(
                    Text.literal("Downward acceleration. Higher = you fall faster and jumps are shorter/snappier."),
                    Text.literal("McSow default 1120 (scaled for Minecraft feel; raw Warsow is ~850)."))
                .setSaveConsumer(v -> cfg.gravity = v)
                .build());
        jump.addEntry(eb.startFloatField(Text.literal("Crouch-jump conversion"), cfg.crouchJumpRatio)
                .setDefaultValue(0.75f).setMin(0f).setMax(1f)
                .setTooltip(
                    Text.literal("Fraction of horizontal speed converted to height on a crouch-jump."),
                    Text.literal("0.75 = trade 75% of your speed for a big vertical pop and keep 25% horizontal."))
                .setSaveConsumer(v -> cfg.crouchJumpRatio = v)
                .build());

        return builder.build();
    }
}
