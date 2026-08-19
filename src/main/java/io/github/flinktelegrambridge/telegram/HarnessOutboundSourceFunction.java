package io.github.flinktelegrambridge.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flinktelegrambridge.config.AppConfig;
import io.github.flinktelegrambridge.protocol.HarnessStreamEntry;
import io.github.flinktelegrambridge.service.HarnessEnvelopeParser;
import io.github.flinktelegrambridge.service.HarnessOutboundProcessor;
import io.github.flinktelegrambridge.service.HarnessOutboundRouter;
import io.github.flinktelegrambridge.redis.HarnessRedisStreamClient;
import io.github.flinktelegrambridge.redis.LettuceHarnessRedisStreamClient;
import io.github.flinktelegrambridge.registry.LettuceQuestionCallbackRegistry;
import io.github.flinktelegrambridge.registry.QuestionCallbackRegistry;
import org.apache.flink.streaming.api.functions.source.legacy.RichSourceFunction;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Flink source that consumes the {@code harness:outbound} Stream through a
 * consumer group via {@code XREADGROUP}, reclaims stale pending entries via
 * {@code XAUTOCLAIM} on restart, and emits Telegram outbound payload JSON after
 * parsing + routing. Entries are ACKed after the routing decision (see
 * {@link HarnessOutboundProcessor}).
 */
public final class HarnessOutboundSourceFunction extends RichSourceFunction<String> {

    private static final int BATCH_SIZE = 64;
    private static final long BLOCK_MILLIS = 1000L;
    private static final long CLAIM_MIN_IDLE_MILLIS = 60_000L;
    private static final long RECLAIM_INTERVAL_MILLIS = 30_000L;

    private final String redisUri;
    private final String redisUsername;
    private final String redisPassword;
    private final String stream;
    private final String group;
    private final String consumerName;
    private final long idleWaitMillis;
    private final long reclaimIntervalMillis;
    private final HarnessRedisStreamClient providedClient;
    private final QuestionCallbackRegistry providedCallbackRegistry;

    private transient HarnessRedisStreamClient client;
    private transient HarnessOutboundProcessor processor;
    private transient QuestionCallbackRegistry callbackRegistry;
    private volatile boolean running = true;

    public HarnessOutboundSourceFunction() {
        this(AppConfig.load());
    }

    HarnessOutboundSourceFunction(AppConfig config) {
        this(config, null, RECLAIM_INTERVAL_MILLIS);
    }

    HarnessOutboundSourceFunction(
            AppConfig config, HarnessRedisStreamClient providedClient, long reclaimIntervalMillis) {
        this(config, providedClient, null, reclaimIntervalMillis);
    }

    HarnessOutboundSourceFunction(
            AppConfig config,
            HarnessRedisStreamClient providedClient,
            QuestionCallbackRegistry providedCallbackRegistry,
            long reclaimIntervalMillis) {
        this.redisUri = requireNonBlank(config.redisUri(), "Redis URI must not be blank.");
        this.redisUsername = config.redisUsername();
        this.redisPassword = config.redisPassword();
        this.stream = config.harnessRedisOutboundStream();
        this.group = config.harnessRedisConsumerGroup();
        this.consumerName = buildConsumerName(group);
        this.idleWaitMillis = config.redisQueueConsumerIdleWaitMillis();
        this.providedClient = providedClient;
        this.providedCallbackRegistry = providedCallbackRegistry;
        this.reclaimIntervalMillis = reclaimIntervalMillis;
    }

    @Override
    public void run(SourceContext<String> ctx) throws Exception {
        client =
                providedClient != null
                        ? providedClient
                        : new LettuceHarnessRedisStreamClient(redisUri, redisUsername, redisPassword);
        callbackRegistry =
                providedCallbackRegistry != null
                        ? providedCallbackRegistry
                        : new LettuceQuestionCallbackRegistry(AppConfig.load());
        processor =
                new HarnessOutboundProcessor(
                        client,
                        stream,
                        group,
                        new HarnessEnvelopeParser(),
                        new HarnessOutboundRouter(),
                        new ObjectMapper(),
                        callbackRegistry);
        try {
            client.ensureGroup(stream, group);
            long lastReclaimMillis = 0L;
            while (running) {
                List<HarnessStreamEntry> entries =
                        client.consume(stream, group, consumerName, BATCH_SIZE, BLOCK_MILLIS);
                // Reclaim stale pending entries left by dead consumers on a fixed
                // cadence, independent of read volume — a busy stream would
                // otherwise never reclaim them (restart recovery).
                long now = System.currentTimeMillis();
                if (now - lastReclaimMillis >= reclaimIntervalMillis) {
                    lastReclaimMillis = now;
                    if (client.pendingCount(stream, group) > 0) {
                        List<HarnessStreamEntry> claimed =
                                client.claim(stream, group, consumerName, CLAIM_MIN_IDLE_MILLIS, BATCH_SIZE);
                        emit(ctx, processor.process(claimed));
                    }
                }
                if (entries.isEmpty()) {
                    waitIdle();
                    continue;
                }
                emit(ctx, processor.process(entries));
            }
        } finally {
            if (client != null) {
                client.close();
            }
            if (callbackRegistry != null) {
                callbackRegistry.close();
            }
        }
    }

    @Override
    public void cancel() {
        running = false;
    }

    private void emit(SourceContext<String> ctx, List<String> outboundMessages) {
        for (String outboundMessage : outboundMessages) {
            synchronized (ctx.getCheckpointLock()) {
                if (running) {
                    ctx.collect(outboundMessage);
                }
            }
        }
    }

    private void waitIdle() throws InterruptedException {
        if (idleWaitMillis > 0 && running) {
            Thread.sleep(idleWaitMillis);
        }
    }

    static String buildConsumerName(String group) {
        return group + "-" + hostname() + "-" + randomSuffix();
    }

    private static String hostname() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (host != null && !host.isBlank()) {
                return host;
            }
        } catch (UnknownHostException ignored) {
            // fall through to the random-only suffix
        }
        return "unknown";
    }

    private static String randomSuffix() {
        return Integer.toHexString(ThreadLocalRandom.current().nextInt());
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
