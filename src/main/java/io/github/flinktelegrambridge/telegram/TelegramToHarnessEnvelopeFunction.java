package io.github.flinktelegrambridge.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flinktelegrambridge.config.AppConfig;
import io.github.flinktelegrambridge.protocol.TelegramPayloads;
import io.github.flinktelegrambridge.service.HarnessInboundRouter;
import io.github.flinktelegrambridge.service.QuestionCallbackResult;
import io.github.flinktelegrambridge.registry.LettuceQuestionCallbackRegistry;
import io.github.flinktelegrambridge.registry.QuestionCallbackRegistry;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.util.Collector;

/** Flink adapter that resolves opaque Telegram question callbacks into answer envelopes. */
public final class TelegramToHarnessEnvelopeFunction extends RichFlatMapFunction<String, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private transient QuestionCallbackRegistry callbackRegistry;
    private transient HarnessInboundRouter router;
    private final QuestionCallbackRegistry providedRegistry;
    private final TelegramCallbackResponder providedResponder;
    private transient TelegramCallbackResponder responder;

    public TelegramToHarnessEnvelopeFunction() {
        this(null, null);
    }

    TelegramToHarnessEnvelopeFunction(QuestionCallbackRegistry providedRegistry, TelegramCallbackResponder providedResponder) {
        this.providedRegistry = providedRegistry;
        this.providedResponder = providedResponder;
    }

    @Override
    public void open(OpenContext openContext) {
        callbackRegistry = providedRegistry == null ? new LettuceQuestionCallbackRegistry(AppConfig.load()) : providedRegistry;
        router = new HarnessInboundRouter(callbackRegistry);
        responder = providedResponder == null ? new TelegramBotCallbackResponder(AppConfig.load()) : providedResponder;
    }

    @Override
    public void flatMap(String value, Collector<String> out) throws Exception {
        TelegramPayloads.TelegramInboundMessage inbound = MAPPER.readValue(value, TelegramPayloads.TelegramInboundMessage.class);
        QuestionCallbackResult callbackResult = null;
        if ("callback_query".equals(inbound.updateType())) {
            callbackResult = router.handleCallback(inbound);
            responder.respond(inbound, callbackResult);
        }
        java.util.Optional<io.github.flinktelegrambridge.protocol.HarnessEnvelope> routed = callbackResult == null
                ? router.route(inbound)
                : callbackResult.envelope();
        routed.ifPresent(envelope -> {
            try {
                out.collect(MAPPER.writeValueAsString(envelope));
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to serialize harness inbound envelope", exception);
            }
        });
    }

    @Override
    public void close() throws Exception {
        if (callbackRegistry != null) callbackRegistry.close();
        if (responder != null) responder.close();
        super.close();
    }
}
