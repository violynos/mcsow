package com.mcsow;

import com.mcsow.config.McSowConfig;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class McSowMod implements ModInitializer {
    public static final String MOD_ID = "mcsow";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        McSowConfig.load();
        LOGGER.info("McSow initialized — movement physics loaded.");
    }
}
