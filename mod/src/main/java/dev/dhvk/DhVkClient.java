package dev.dhvk;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** dhvk 客户端入口点。 */
public final class DhVkClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DhVkClient.class);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[dhvk] client entrypoint initialized (mc 26.2, fabric)");
        LOGGER.info("[dhvk] vk handles: {}", VkHandles.format());
    }
}
