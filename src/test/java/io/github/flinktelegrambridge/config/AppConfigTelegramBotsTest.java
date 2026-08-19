package io.github.flinktelegrambridge.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTelegramBotsTest {

    private static final List<String> SYSTEM_KEYS =
            List.of(
                    "flink.telegram.bridge.telegram.bots.1.id",
                    "flink.telegram.bridge.telegram.bots.1.token",
                    "flink.telegram.bridge.telegram.bots.1.default-chat-id",
                    "flink.telegram.bridge.telegram.bots.2.id",
                    "flink.telegram.bridge.telegram.bots.2.token",
                    "flink.telegram.bridge.telegram.bots.2.default-chat-id");

    @AfterEach
    void clearSystemProperties() {
        for (String key : SYSTEM_KEYS) {
            System.clearProperty(key);
        }
    }

    @Test
    void loadsIndexedTelegramBotsAndUsesPrimaryValues() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("telegram.bots.1.id", "from-properties");
        properties.setProperty("telegram.bots.1.token", "token-from-properties");
        properties.setProperty("telegram.bots.1.default-chat-id", "chat-1");

        System.setProperty("flink.telegram.bridge.telegram.bots.1.id", "primary-bot");
        System.setProperty("flink.telegram.bridge.telegram.bots.1.token", "token-from-system");
        System.setProperty("flink.telegram.bridge.telegram.bots.2.id", "backup-bot");
        System.setProperty("flink.telegram.bridge.telegram.bots.2.token", "token-2");
        System.setProperty("flink.telegram.bridge.telegram.bots.2.default-chat-id", "chat-2");

        AppConfig config = createConfig(properties);

        assertEquals(2, config.telegramBots().size());
        assertEquals("primary-bot", config.telegramBots().get(0).id());
        assertEquals("token-from-system", config.telegramBots().get(0).token());
        assertEquals("chat-1", config.telegramBots().get(0).defaultChatId());
        assertEquals("backup-bot", config.telegramBots().get(1).id());
        assertEquals("token-from-system", config.telegramBotToken());
        assertEquals("chat-1", config.telegramOutboundDefaultChatId());
    }

    @Test
    void fallsBackToLegacySingleBotWhenNoIndexedTokenIsSet() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("telegram.bot-token", "legacy-token");
        properties.setProperty("telegram.outbound.default-chat-id", "legacy-chat");

        AppConfig config = createConfig(properties);

        assertEquals(1, config.telegramBots().size());
        assertEquals("primary", config.telegramBots().get(0).id());
        assertEquals("legacy-token", config.telegramBots().get(0).token());
        assertEquals("legacy-chat", config.telegramBots().get(0).defaultChatId());
        assertEquals("legacy-token", config.telegramBotToken());
        assertEquals("legacy-chat", config.telegramOutboundDefaultChatId());
    }

    @Test
    void returnsEmptyBotListWithoutLegacyOrIndexedConfig() throws Exception {
        AppConfig config = createConfig(new Properties());
        assertTrue(config.telegramBots().isEmpty());
    }

    private static AppConfig createConfig(Properties properties) throws Exception {
        Constructor<AppConfig> constructor = AppConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        return constructor.newInstance(properties);
    }
}
