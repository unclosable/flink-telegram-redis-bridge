package io.github.flinktelegrambridge.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Bridge configuration. System properties override environment, which overrides properties. */
public final class AppConfig {
    private static final AppConfig INSTANCE = loadConfig();
    private final String redisUri, redisUsername, redisPassword, inboundStream, outboundStream, consumerGroup;
    private final long idleWaitMillis, callbackRegistryTtlSeconds;
    private final String legacyToken, defaultChatId;
    private final List<TelegramBotConfig> telegramBots;
    public record TelegramBotConfig(String id, String token, String defaultChatId) implements Serializable {}
    private AppConfig(Properties p) {
        redisUri = value(p, "redis.uri", "REDIS_URI", "flink.telegram.bridge.redis.uri", "redis://localhost:6379");
        redisUsername = value(p, "redis.username", "REDIS_USERNAME", "flink.telegram.bridge.redis.username", "");
        redisPassword = value(p, "redis.password", "REDIS_PASSWORD", "flink.telegram.bridge.redis.password", "");
        inboundStream = value(p, "harness.redis.inbound-stream", "HARNESS_REDIS_INBOUND_STREAM", "flink.telegram.bridge.inboundStream", "harness:inbound");
        outboundStream = value(p, "harness.redis.outbound-stream", "HARNESS_REDIS_OUTBOUND_STREAM", "flink.telegram.bridge.outboundStream", "harness:outbound");
        consumerGroup = value(p, "harness.redis.consumer-group", "HARNESS_REDIS_CONSUMER_GROUP", "flink.telegram.bridge.consumerGroup", "flink-harness-inbound");
        idleWaitMillis = Long.parseLong(value(p, "redis.queue.consumer.idle-wait-millis", "REDIS_QUEUE_CONSUMER_IDLE_WAIT_MILLIS", "flink.telegram.bridge.idleWaitMillis", "1000"));
        callbackRegistryTtlSeconds = Long.parseLong(value(p, "telegram.callback-registry-ttl-seconds", "TELEGRAM_CALLBACK_REGISTRY_TTL_SECONDS", "flink.telegram.bridge.telegram.callbackRegistryTtlSeconds", "86400"));
        legacyToken = value(p, "telegram.bot-token", "TELEGRAM_BOT_TOKEN", "flink.telegram.bridge.telegram.botToken", "");
        defaultChatId = value(p, "telegram.outbound.default-chat-id", "TELEGRAM_OUTBOUND_DEFAULT_CHAT_ID", "flink.telegram.bridge.telegram.defaultChatId", "");
        telegramBots = resolveBots(p, legacyToken, defaultChatId);
    }
    public static AppConfig load() { return INSTANCE; }
    public String redisUri() { return redisUri; } public String redisUsername() { return redisUsername; } public String redisPassword() { return redisPassword; }
    public String harnessRedisInboundStream() { return inboundStream; } public String harnessRedisOutboundStream() { return outboundStream; }
    public String harnessRedisConsumerGroup() { return consumerGroup; } public long redisQueueConsumerIdleWaitMillis() { return idleWaitMillis; }
    public long callbackRegistryTtlSeconds() { return callbackRegistryTtlSeconds; }
    public List<TelegramBotConfig> telegramBots() { return telegramBots; }
    public String telegramBotToken() { if (!telegramBots.isEmpty()) return telegramBots.get(0).token(); if (legacyToken.isBlank()) throw new IllegalStateException("Telegram bot credential is required"); return legacyToken; }
    public String telegramOutboundDefaultChatId() { return telegramBots.isEmpty() ? defaultChatId : telegramBots.get(0).defaultChatId(); }
    private static List<TelegramBotConfig> resolveBots(Properties p, String legacyToken, String defaultChatId) {
        List<TelegramBotConfig> bots = new ArrayList<>();
        for (int n=1;n<=16;n++) { String prefix="telegram.bots."+n+"."; String token=value(p,prefix+"token","TELEGRAM_BOT_"+n+"_TOKEN","flink.telegram.bridge.telegram.bots."+n+".token",""); if (!token.isBlank()) bots.add(new TelegramBotConfig(value(p,prefix+"id","TELEGRAM_BOT_"+n+"_ID","flink.telegram.bridge.telegram.bots."+n+".id","bot-"+n), token, value(p,prefix+"default-chat-id","TELEGRAM_BOT_"+n+"_DEFAULT_CHAT_ID","flink.telegram.bridge.telegram.bots."+n+".default-chat-id", defaultChatId))); }
        if (bots.isEmpty() && !legacyToken.isBlank()) bots.add(new TelegramBotConfig("primary", legacyToken, defaultChatId));
        return List.copyOf(bots);
    }
    private static AppConfig loadConfig() { Properties p=new Properties(); try(InputStream in=AppConfig.class.getClassLoader().getResourceAsStream("application.properties")){if(in!=null)p.load(in);}catch(IOException e){throw new UncheckedIOException(e);} return new AppConfig(p); }
    private static String value(Properties p,String key,String env,String sys,String fallback) { String v=System.getProperty(sys); if(v==null||v.isBlank())v=System.getenv(env); if(v==null||v.isBlank())v=p.getProperty(key); return v==null||v.isBlank()?fallback:v.trim(); }
}
