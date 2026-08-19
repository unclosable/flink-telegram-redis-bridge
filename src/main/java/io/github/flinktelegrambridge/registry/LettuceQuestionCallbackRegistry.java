package io.github.flinktelegrambridge.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.github.flinktelegrambridge.config.AppConfig;
import io.github.flinktelegrambridge.protocol.QuestionCallbackBinding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Redis-backed callback registry; entries expire so unanswered prompts do not accumulate. */
public final class LettuceQuestionCallbackRegistry implements QuestionCallbackRegistry {

    static final String KEY_PREFIX = "harness:question-callback:";
    private final long ttlSeconds;
    private static final String CONSUME_SCRIPT = "local v=redis.call('GET',KEYS[1]); if v then redis.call('DEL',KEYS[1]); end; return v";
    private static final String COMPLETE_SCRIPT = "if redis.call('EXISTS',KEYS[2]) == 1 then return nil end; local v=redis.call('GET',KEYS[1]); if not v then return nil end; redis.call('SET',KEYS[2],'1','EX',ARGV[1]); redis.call('DEL',KEYS[1]); return v";
    private static final String CONSUME_FREE_TEXT_SCRIPT = "local k=redis.call('GET',KEYS[1]); if not k then return nil end; redis.call('DEL',KEYS[1]); local v=redis.call('GET',KEYS[2]..k); if v then redis.call('DEL',KEYS[2]..k); end; return v";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;

    public LettuceQuestionCallbackRegistry(AppConfig config) {
        RedisURI uri = RedisURI.create(config.redisUri());
        if (config.redisUsername() != null && !config.redisUsername().isBlank()) uri.setUsername(config.redisUsername());
        if (config.redisPassword() != null && !config.redisPassword().isBlank()) uri.setPassword(config.redisPassword().toCharArray());
        this.redisClient = RedisClient.create(uri);
        this.connection = redisClient.connect();
        this.commands = connection.sync();
        this.ttlSeconds = config.callbackRegistryTtlSeconds();
    }

    @Override
    public void register(QuestionCallbackBinding binding) {
        try {
            commands.setex(key(binding.callbackKey()), ttlSeconds, MAPPER.writeValueAsString(binding));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to register Telegram question callback", exception);
        }
    }

    @Override
    public QuestionCallbackBinding consume(String callbackKey) {
        try {
            String json = commands.eval(CONSUME_SCRIPT, ScriptOutputType.VALUE, new String[] {key(callbackKey)});
            return json == null ? null : MAPPER.readValue(json, QuestionCallbackBinding.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to consume Telegram question callback", exception);
        }
    }

    @Override
    public QuestionCallbackBinding consumeAndComplete(QuestionCallbackBinding binding) {
        try {
            String completionKey = binding.completionKey() == null || binding.completionKey().isBlank()
                    ? binding.callbackKey() : binding.completionKey();
            String json = commands.eval(COMPLETE_SCRIPT, ScriptOutputType.VALUE,
                    new String[] {key(binding.callbackKey()), completedKey(completionKey)}, Long.toString(ttlSeconds));
            return json == null ? null : MAPPER.readValue(json, QuestionCallbackBinding.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to complete Telegram question", exception);
        }
    }

    @Override
    public boolean isCompleted(QuestionCallbackBinding binding) {
        String completionKey = binding.completionKey() == null || binding.completionKey().isBlank()
                ? binding.callbackKey() : binding.completionKey();
        return commands.exists(completedKey(completionKey)) > 0;
    }

    @Override
    public QuestionCallbackBinding find(String callbackKey) {
        try {
            String json = commands.get(key(callbackKey));
            return json == null ? null : MAPPER.readValue(json, QuestionCallbackBinding.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to look up Telegram question callback", exception);
        }
    }

    @Override
    public QuestionCallbackBinding consumeFreeText(String chatId, String botId, Integer replyToMessageId) {
        if (replyToMessageId == null) return null;
        try {
            String json = commands.eval(CONSUME_SCRIPT, ScriptOutputType.VALUE,
                    new String[] {freeTextKey(chatId, botId, replyToMessageId)});
            return json == null ? null : MAPPER.readValue(json, QuestionCallbackBinding.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to consume Telegram free-text question", exception);
        }
    }

    @Override
    public boolean isFreeTextPrompt(String chatId, String botId, Integer replyToMessageId) {
        return replyToMessageId != null && commands.exists(freeTextMarkerKey(chatId, botId, replyToMessageId)) > 0;
    }

    @Override
    public void registerFreeTextPrompt(QuestionCallbackBinding binding, Integer messageId) {
        if (messageId == null) throw new IllegalArgumentException("Telegram prompt message id is required");
        try {
            String json = MAPPER.writeValueAsString(binding);
            commands.setex(freeTextKey(binding.chatId(), binding.botId(), messageId), ttlSeconds, json);
            commands.setex(freeTextMarkerKey(binding.chatId(), binding.botId(), messageId), ttlSeconds * 7, "1");
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to register Telegram free-text prompt", exception);
        }
    }

    @Override
    public List<String> toggle(QuestionCallbackBinding binding) {
        String key = selectionKey(binding);
        if (commands.sismember(key, binding.value())) commands.srem(key, binding.value());
        else commands.sadd(key, binding.value());
        commands.expire(key, ttlSeconds);
        return List.copyOf(commands.smembers(key));
    }

    @Override
    public List<String> confirm(QuestionCallbackBinding binding) {
        String key = selectionKey(binding);
        List<String> selected = List.copyOf(commands.smembers(key));
        commands.del(key);
        return selected;
    }

    @Override
    public void close() {
        connection.close();
        redisClient.shutdown();
    }

    private static String key(String callbackKey) {
        return KEY_PREFIX + callbackKey;
    }

    private static String selectionKey(QuestionCallbackBinding binding) {
        try {
            String identity = binding.conversationId() + "\u0000" + binding.correlationId() + "\u0000" + binding.questionId() + "\u0000" + binding.chatId();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return "harness:question-selection:" + java.util.HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to derive question selection key", exception);
        }
    }

    private static String freeTextKey(String chatId, String botId, Integer messageId) {
        try {
            String identity = (chatId == null ? "" : chatId) + "\u0000" + (botId == null ? "" : botId) + "\u0000" + messageId;
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return "harness:question-free-text:" + java.util.HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to derive free-text question key", exception);
        }
    }

    private static String freeTextMarkerKey(String chatId, String botId, Integer messageId) {
        return freeTextKey(chatId, botId, messageId) + ":seen";
    }

    private static String completedKey(String completionKey) {
        return "harness:question-completed:" + completionKey;
    }
}
