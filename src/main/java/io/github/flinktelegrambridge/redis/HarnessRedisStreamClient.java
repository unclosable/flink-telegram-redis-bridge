package io.github.flinktelegrambridge.redis;

import io.github.flinktelegrambridge.protocol.HarnessStreamEntry;
import java.util.List;

/**
 * Minimal Redis Streams facade for the harness bridge, shared by the outbound
 * (Telegram &rarr; Redis) and inbound (Redis &rarr; Telegram) jobs.
 *
 * <p>Kept as an interface so the routing logic can be tested against a fake and
 * the Lettuce specifics stay in one place.
 */
public interface HarnessRedisStreamClient extends AutoCloseable {

    /**
     * Appends a JSON payload as a single-field entry and returns the new entry id.
     */
    String xadd(String stream, String json);

    /**
     * Creates the consumer group if it does not yet exist (MKSTREAM, starting at
     * the first entry so no earlier message is lost).
     */
    void ensureGroup(String stream, String group);

    /**
     * Reads up to {@code count} new messages for the group/consumer via
     * {@code XREADGROUP}, blocking up to {@code blockMillis}.
     */
    List<HarnessStreamEntry> consume(
            String stream, String group, String consumer, int count, long blockMillis);

    /** Acknowledges one or more entry ids via {@code XACK}. */
    long ack(String stream, String group, String... ids);

    /**
     * Reclaims stale pending entries (idle at least {@code minIdleMillis}) for the
     * consumer via {@code XAUTOCLAIM}.
     */
    List<HarnessStreamEntry> claim(
            String stream, String group, String consumer, long minIdleMillis, int count);

    /**
     * Returns the pending-entry count for the group via {@code XPENDING}, or
     * {@code -1} when the group does not exist yet.
     */
    long pendingCount(String stream, String group);

    @Override
    void close();
}
