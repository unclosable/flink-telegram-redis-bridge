package io.github.flinktelegrambridge.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flinktelegrambridge.protocol.QuestionCallbackBinding;
import io.github.flinktelegrambridge.protocol.TelegramPayloads;
import io.github.flinktelegrambridge.service.QuestionCallbackResult;
import io.github.flinktelegrambridge.registry.QuestionCallbackRegistry;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.util.Collector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramToHarnessEnvelopeFunctionTest {
    @Test
    void acknowledgesHandledCallbackAndForwardsAnswer() throws Exception {
        FakeRegistry registry = new FakeRegistry();
        registry.register(new QuestionCallbackBinding(
                "key", "correlation", "telegram:chat:123", "question", "Yes", "123", "", false));
        FakeResponder responder = new FakeResponder();
        TelegramToHarnessEnvelopeFunction function = new TelegramToHarnessEnvelopeFunction(registry, responder);
        function.open(new OpenContext() {});
        List<String> output = new ArrayList<>();
        TelegramPayloads.TelegramInboundMessage callback = new TelegramPayloads.TelegramInboundMessage(
                "telegram", "", "callback_query", 1, 2, "123", "private", null,
                3L, null, null, null, 4, "{}", "callback-id", "hq:key");

        function.flatMap(new ObjectMapper().writeValueAsString(callback), new Collector<>() {
            @Override public void collect(String record) { output.add(record); }
            @Override public void close() {}
        });

        assertNotNull(responder.result);
        assertTrue(responder.result.handled());
        assertEquals("✓", responder.result.acknowledgement());
        assertEquals(1, output.size());
        function.close();
    }

    private static final class FakeResponder implements TelegramCallbackResponder {
        QuestionCallbackResult result;
        @Override public void respond(TelegramPayloads.TelegramInboundMessage inbound, QuestionCallbackResult result) { this.result = result; }
    }

    private static final class FakeRegistry implements QuestionCallbackRegistry {
        final Map<String, QuestionCallbackBinding> bindings = new HashMap<>();
        @Override public void register(QuestionCallbackBinding binding) { bindings.put(binding.callbackKey(), binding); }
        @Override public QuestionCallbackBinding find(String callbackKey) { return bindings.get(callbackKey); }
        @Override public QuestionCallbackBinding consume(String callbackKey) { return bindings.remove(callbackKey); }
        @Override public List<String> toggle(QuestionCallbackBinding binding) { return List.of(); }
        @Override public List<String> confirm(QuestionCallbackBinding binding) { return List.of(); }
    }
}
