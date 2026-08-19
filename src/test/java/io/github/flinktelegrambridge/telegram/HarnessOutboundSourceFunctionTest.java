package io.github.flinktelegrambridge.telegram;

import io.github.flinktelegrambridge.config.AppConfig;
import io.github.flinktelegrambridge.protocol.HarnessStreamEntry;
import io.github.flinktelegrambridge.redis.HarnessRedisStreamClient;
import io.github.flinktelegrambridge.registry.QuestionCallbackRegistry;
import org.apache.flink.streaming.api.functions.source.legacy.SourceFunction;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessOutboundSourceFunctionTest {

    private static final String VALID_ASSISTANT =
            "{\"version\":1,\"conversation_id\":\"telegram:chat:12345\","
                    + "\"session_id\":\"s1\",\"correlation_id\":\"telegram-update-1\","
                    + "\"type\":\"assistant_message\",\"content\":{\"text\":\"hi\"},"
                    + "\"metadata\":{\"chatId\":\"12345\"}}";

    @Test
    void reclaimsPendingEntriesEvenWhenReadIsNonEmpty() throws Exception {
        FakeClient client = new FakeClient();
        HarnessOutboundSourceFunction source =
                new HarnessOutboundSourceFunction(AppConfig.load(), client, new QuestionCallbackRegistry() {
                    @Override public void register(io.github.flinktelegrambridge.protocol.QuestionCallbackBinding binding) {}
                    @Override public io.github.flinktelegrambridge.protocol.QuestionCallbackBinding find(String callbackKey) { return null; }
                    @Override public io.github.flinktelegrambridge.protocol.QuestionCallbackBinding consume(String callbackKey) { return null; }
                    @Override public java.util.List<String> toggle(io.github.flinktelegrambridge.protocol.QuestionCallbackBinding binding) { return java.util.List.of(); }
                    @Override public java.util.List<String> confirm(io.github.flinktelegrambridge.protocol.QuestionCallbackBinding binding) { return java.util.List.of(); }
                }, 0L);

        CollectingContext ctx = new CollectingContext();
        final Throwable[] failure = new Throwable[1];
        Thread runner =
                new Thread(
                        () -> {
                            try {
                                source.run(ctx);
                            } catch (Throwable error) {
                                failure[0] = error;
                            }
                        });
        runner.start();

        // Wait for the consumer loop to actually start (first XREADGROUP), then
        // for a reclaim and a forwarded message to happen, then cancel.
        long deadline = System.currentTimeMillis() + 5000L;
        while (client.consumeCalls.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        deadline = System.currentTimeMillis() + 5000L;
        while (client.claimCalls.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        deadline = System.currentTimeMillis() + 5000L;
        while (ctx.size() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        source.cancel();
        runner.join(5000L);

        if (failure[0] != null) {
            throw new AssertionError("source run() failed", failure[0]);
        }

        // Reclaim ran even though consume() returned non-empty entries (S2 fix).
        assertTrue(client.claimCalls.get() > 0, "expected XAUTOCLAIM to run on its cadence");
        assertTrue(client.consumeCalls.get() > 0, "expected XREADGROUP to have run");
        // Entries were consumed and ACKed after routing.
        assertTrue(client.ackedIds.contains("1-0"), "expected the consumed entry to be ACKed");
        // The assistant message was forwarded to the sink context.
        assertTrue(ctx.size() >= 1, "expected the assistant message to be forwarded");
    }

    private static final class FakeClient implements HarnessRedisStreamClient {
        final List<String> ackedIds = new ArrayList<>();
        final AtomicInteger claimCalls = new AtomicInteger();
        final AtomicInteger consumeCalls = new AtomicInteger();
        final AtomicInteger pendingCalls = new AtomicInteger();

        @Override
        public String xadd(String stream, String json) {
            return "0-0";
        }

        @Override
        public void ensureGroup(String stream, String group) {}

        @Override
        public List<HarnessStreamEntry> consume(
                String stream, String group, String consumer, int count, long blockMillis) {
            consumeCalls.incrementAndGet();
            return List.of(new HarnessStreamEntry("1-0", VALID_ASSISTANT));
        }

        @Override
        public long ack(String stream, String group, String... ids) {
            synchronized (ackedIds) {
                ackedIds.addAll(List.of(ids));
            }
            return ids.length;
        }

        @Override
        public List<HarnessStreamEntry> claim(
                String stream, String group, String consumer, long minIdleMillis, int count) {
            claimCalls.incrementAndGet();
            return List.of();
        }

        @Override
        public long pendingCount(String stream, String group) {
            return pendingCalls.incrementAndGet() == 1 ? 1L : 0L;
        }

        @Override
        public void close() {}
    }

    private static final class CollectingContext implements SourceFunction.SourceContext<String> {
        final List<String> collected = new ArrayList<>();

        @Override
        public void collect(String element) {
            synchronized (collected) {
                collected.add(element);
            }
        }

        @Override
        public void collectWithTimestamp(String element, long timestamp) {
            collect(element);
        }

        @Override
        public void emitWatermark(Watermark mark) {}

        @Override
        public void markAsTemporarilyIdle() {}

        @Override
        public Object getCheckpointLock() {
            return this;
        }

        int size() {
            synchronized (collected) {
                return collected.size();
            }
        }

        @Override
        public void close() {}
    }
}
