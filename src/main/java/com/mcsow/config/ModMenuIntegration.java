package com.mcsow.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Registers the McSow config screen with Mod Menu (see the "modmenu" entrypoint). */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return McSowConfigScreen::create;
    }
}
