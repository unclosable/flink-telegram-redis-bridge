package io.github.flinktelegrambridge.redis;

import io.lettuce.core.Consumer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.models.stream.ClaimedMessages;
import io.lettuce.core.models.stream.PendingMessages;
import io.github.flinktelegrambridge.config.AppConfig;
import io.github.flinktelegrambridge.config.InfrastructureConnections;
import io.github.flinktelegrambridge.protocol.HarnessStreamEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lettuce-backed implementation of {@link HarnessRedisStreamClient}.
 */
public final class LettuceHarnessRedisStreamClient implements HarnessRedisStreamClient {

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;

    public LettuceHarnessRedisStreamClient(AppConfig config) {
        this(InfrastructureConnections.createRedisClient(config));
    }

    public LettuceHarnessRedisStreamClient(
            String redisUri, String redisUsername, String redisPassword) {
        this(createRedisClient(redisUri, redisUsername, redisPassword));
    }

    public LettuceHarnessRedisStreamClient(RedisClient redisClient) {
        this.redisClient = redisClient;
        this.connection = redisClient.connect();
        this.commands = connection.sync();
    }

    @Override
    public String xadd(String stream, String json) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put(HarnessStreamEntry.JSON_FIELD, json);
        return commands.xadd(stream, body);
    }

    @Override
    public void ensureGroup(String stream, String group) {
        try {
            commands.xgroupCreate(
                    XReadArgs.StreamOffset.from(stream, "0-0"),
                    group,
                    XGroupCreateArgs.Builder.mkstream(true));
        } catch (RedisCommandExecutionException exception) {
            if (isBusyGroup(exception)) {
                return;
            }
            throw exception;
        }
    }

    @Override
    public List<HarnessStreamEntry> consume(
            String stream, String group, String consumer, int count, long blockMillis) {
        List<StreamMessage<String, String>> messages =
                commands.xreadgroup(
                        Consumer.from(group, consumer),
                        new XReadArgs().count(count).block(blockMillis),
                        XReadArgs.StreamOffset.lastConsumed(stream));
        return toEntries(messages);
    }

    @Override
    public long ack(String stream, String group, String... ids) {
        if (ids == null || ids.length == 0) {
            return 0L;
        }
        Long acknowledged = commands.xack(stream, group, ids);
        return acknowledged == null ? 0L : acknowledged;
    }

    @Override
    public List<HarnessStreamEntry> claim(
            String stream, String group, String consumer, long minIdleMillis, int count) {
        XAutoClaimArgs<String> args =
                new XAutoClaimArgs<String>()
                        .consumer(Consumer.from(group, consumer))
                        .minIdleTime(minIdleMillis)
                        .startId("0-0")
                        .count(count);
        ClaimedMessages<String, String> claimed = commands.xautoclaim(stream, args);
        if (claimed == null) {
            return List.of();
        }
        return toEntries(claimed.getMessages());
    }

    @Override
    public long pendingCount(String stream, String group) {
        try {
            PendingMessages pending = commands.xpending(stream, group);
            return pending == null ? 0L : pending.getCount();
        } catch (RedisCommandExecutionException exception) {
            if (isNoGroup(exception)) {
                return -1L;
            }
            throw exception;
        }
    }

    @Override
    public void close() {
        connection.close();
        redisClient.shutdown();
    }

    private static List<HarnessStreamEntry> toEntries(List<StreamMessage<String, String>> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<HarnessStreamEntry> entries = new ArrayList<>(messages.size());
        for (StreamMessage<String, String> message : messages) {
            entries.add(HarnessStreamEntry.fromBody(message.getId(), message.getBody()));
        }
        return entries;
    }

    private static boolean isBusyGroup(RedisCommandExecutionException exception) {
        String message = exception.getMessage();
        return message != null && message.contains("BUSYGROUP");
    }

    private static boolean isNoGroup(RedisCommandExecutionException exception) {
        String message = exception.getMessage();
        return message != null && message.contains("NOGROUP");
    }

    private static RedisClient createRedisClient(
            String redisUri, String redisUsername, String redisPassword) {
        RedisURI resolvedRedisUri = RedisURI.create(redisUri);
        if (redisUsername != null && !redisUsername.isBlank()) {
            resolvedRedisUri.setUsername(redisUsername);
        }
        if (redisPassword != null && !redisPassword.isBlank()) {
            resolvedRedisUri.setPassword(redisPassword.toCharArray());
        }
        return RedisClient.create(resolvedRedisUri);
    }
}
