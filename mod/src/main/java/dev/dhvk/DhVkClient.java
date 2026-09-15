package dev.dhvk;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** dhvk 客户端入口点。 */
public final class DhVkClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DhVkClient.class);

    /** 供 mixin 使用:ClientPackSource 构造器早于入口点执行,共享本类日志器。 */
    public static void logDevHookInstalled() {
        LOGGER.info("[dhvk] developmentConfig hook installed: classpath assets/ dirs exposed to vanilla pack");
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("[dhvk] client entrypoint initialized (mc 26.2, fabric)");
        LOGGER.info("[dhvk] vk handles: {}", VkHandles.format());
    }
}
