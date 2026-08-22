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
                    "flink.telegram.bridge.telegram.bots.2.default-chat-id",
                    "flink.telegram.bridge.telegram.inboundPrivateOnly",
                    "flink.telegram.bridge.telegram.groupMessageEnabled");

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

        System.setProperty("flink.telegram.bridge.telegram.bots.1.id", "personal-bot");
        System.setProperty("flink.telegram.bridge.telegram.bots.1.token", "token-from-system");
        System.setProperty("flink.telegram.bridge.telegram.bots.2.id", "backup-bot");
        System.setProperty("flink.telegram.bridge.telegram.bots.2.token", "token-2");
        System.setProperty("flink.telegram.bridge.telegram.bots.2.default-chat-id", "chat-2");

        AppConfig config = createConfig(properties);

        assertEquals(2, config.telegramBots().size());
        assertEquals("personal-bot", config.telegramBots().get(0).id());
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
        assertEquals("personal", config.telegramBots().get(0).id());
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

    @Test
    void exposesInboundAndGroupMessageDefaultsAndConfiguredBotIds() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("telegram.bots.1.id", "personal");
        properties.setProperty("telegram.bots.1.token", "token-1");
        properties.setProperty("telegram.bots.2.id", "operator");
        properties.setProperty("telegram.bots.2.token", "token-2");
        AppConfig defaults = createConfig(properties);
        assertTrue(defaults.inboundPrivateOnly());
        assertTrue(!defaults.groupMessageEnabled());
        assertEquals(java.util.Set.of("personal", "operator"), defaults.telegramBotIds());

        System.setProperty("flink.telegram.bridge.telegram.inboundPrivateOnly", "false");
        System.setProperty("flink.telegram.bridge.telegram.groupMessageEnabled", "true");
        AppConfig configured = createConfig(properties);
        assertTrue(!configured.inboundPrivateOnly());
        assertTrue(configured.groupMessageEnabled());
    }

    @Test
    void parsesAdmissionFlagsFailClosed() throws Exception {
        for (String value : List.of("yes", "1", "on", "TRUE")) {
            System.setProperty("flink.telegram.bridge.telegram.inboundPrivateOnly", value);
            assertTrue(createConfig(new Properties()).inboundPrivateOnly(), value);
        }
        System.setProperty("flink.telegram.bridge.telegram.inboundPrivateOnly", "false");
        assertTrue(!createConfig(new Properties()).inboundPrivateOnly());

        for (String value : List.of("yes", "1", "on", "false")) {
            System.setProperty("flink.telegram.bridge.telegram.groupMessageEnabled", value);
            assertTrue(!createConfig(new Properties()).groupMessageEnabled(), value);
        }
        System.setProperty("flink.telegram.bridge.telegram.groupMessageEnabled", "true");
        assertTrue(createConfig(new Properties()).groupMessageEnabled());
    }

    private static AppConfig createConfig(Properties properties) throws Exception {
        Constructor<AppConfig> constructor = AppConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        return constructor.newInstance(properties);
    }
}
