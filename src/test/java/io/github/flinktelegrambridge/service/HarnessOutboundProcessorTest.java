package io.github.flinktelegrambridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flinktelegrambridge.protocol.HarnessStreamEntry;
import io.github.flinktelegrambridge.protocol.TelegramPayloads;
import io.github.flinktelegrambridge.redis.HarnessRedisStreamClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessOutboundProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void processesAndAcksEntriesWithFakeClient() throws Exception {
        FakeClient client = new FakeClient();
        HarnessOutboundProcessor processor =
                new HarnessOutboundProcessor(
                        client,
                        "harness:outbound",
                        "flink-harness-inbound",
                        new HarnessEnvelopeParser(),
                        new HarnessOutboundRouter(),
                        MAPPER);

        List<HarnessStreamEntry> entries =
                List.of(
                        new HarnessStreamEntry("1-0", outboundJson("assistant_message")),
                        new HarnessStreamEntry("1-1", outboundJson("error")),
                        new HarnessStreamEntry("1-2", outboundJson("some_new_type")),
                        new HarnessStreamEntry("1-3", "{not json"));

        List<String> forwarded = processor.process(entries);

        // assistant_message + error forwarded; unknown type + malformed dropped
        assertEquals(2, forwarded.size());
        TelegramPayloads.TelegramOutboundMessage first =
                MAPPER.readValue(forwarded.get(0), TelegramPayloads.TelegramOutboundMessage.class);
        assertEquals("12345", first.chatId());
        assertEquals("hi", first.text());
        TelegramPayloads.TelegramOutboundMessage second =
                MAPPER.readValue(forwarded.get(1), TelegramPayloads.TelegramOutboundMessage.class);
        assertTrue(second.text().startsWith("Error:"));

        // every entry acknowledged exactly once, in order
        assertEquals(List.of("1-0", "1-1", "1-2", "1-3"), client.ackedIds);
    }

    private static String outboundJson(String type) {
        return "{\"version\":1,\"conversation_id\":\"telegram:chat:12345\","
                + "\"session_id\":\"s1\",\"correlation_id\":\"telegram-update-1\","
                + "\"type\":\""
                + type
                + "\",\"content\":{\"text\":\"hi\"},"
                + "\"metadata\":{\"chatId\":\"12345\"}}";
    }

    private static final class FakeClient implements HarnessRedisStreamClient {
        final List<String> ackedIds = new ArrayList<>();

        @Override
        public String xadd(String stream, String json) {
            return "0-0";
        }

        @Override
        public void ensureGroup(String stream, String group) {}

        @Override
        public List<HarnessStreamEntry> consume(
                String stream, String group, String consumer, int count, long blockMillis) {
            return List.of();
        }

        @Override
        public long ack(String stream, String group, String... ids) {
            ackedIds.addAll(List.of(ids));
            return ids.length;
        }

        @Override
        public List<HarnessStreamEntry> claim(
                String stream, String group, String consumer, long minIdleMillis, int count) {
            return List.of();
        }

        @Override
        public long pendingCount(String stream, String group) {
            return 0L;
        }

        @Override
        public void close() {}
    }
}
