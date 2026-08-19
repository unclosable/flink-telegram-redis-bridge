package io.github.flinktelegrambridge.telegram;

import io.github.flinktelegrambridge.config.AppConfig;
import io.github.flinktelegrambridge.redis.HarnessRedisStreamClient;
import io.github.flinktelegrambridge.redis.LettuceHarnessRedisStreamClient;
import org.apache.flink.streaming.api.functions.sink.legacy.RichSinkFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flink sink that appends each incoming JSON envelope string to a Redis Stream
 * via {@code XADD}.
 */
public final class HarnessRedisStreamSinkFunction extends RichSinkFunction<String> {

    private static final Logger LOG = LoggerFactory.getLogger(HarnessRedisStreamSinkFunction.class);

    private final String stream;
    private final String redisUri;
    private final String redisUsername;
    private final String redisPassword;
    private transient HarnessRedisStreamClient client;

    public HarnessRedisStreamSinkFunction(String stream) {
        this(stream, AppConfig.load());
    }

    HarnessRedisStreamSinkFunction(String stream, AppConfig config) {
        this.stream = requireNonBlank(stream, "Stream must not be blank.");
        this.redisUri = requireNonBlank(config.redisUri(), "Redis URI must not be blank.");
        this.redisUsername = config.redisUsername();
        this.redisPassword = config.redisPassword();
    }

    @Override
    public void invoke(String value, Context context) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            client().xadd(stream, value);
        } catch (RuntimeException firstFailure) {
            LOG.warn(
                    "Redis XADD failed for stream {}. Resetting Redis client and retrying once: {}",
                    stream,
                    firstFailure.getMessage());
            resetClient();
            try {
                client().xadd(stream, value);
            } catch (RuntimeException retryFailure) {
                retryFailure.addSuppressed(firstFailure);
                throw retryFailure;
            }
        }
    }

    @Override
    public void close() throws Exception {
        resetClient();
        super.close();
    }

    private HarnessRedisStreamClient client() {
        if (client == null) {
            client = new LettuceHarnessRedisStreamClient(redisUri, redisUsername, redisPassword);
        }
        return client;
    }

    private void resetClient() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
