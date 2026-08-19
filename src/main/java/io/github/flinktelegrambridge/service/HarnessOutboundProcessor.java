package io.github.flinktelegrambridge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flinktelegrambridge.protocol.HarnessParseResult;
import io.github.flinktelegrambridge.protocol.HarnessStreamEntry;
import io.github.flinktelegrambridge.protocol.RoutingDecision;
import io.github.flinktelegrambridge.redis.HarnessRedisStreamClient;
import io.github.flinktelegrambridge.registry.QuestionCallbackRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses, routes and acknowledges a batch of outbound stream entries.
 *
 * <p>Every entry is acknowledged exactly once after its routing decision is made
 * ("ACK after safe admission"): forwarded entries are serialized into the JSON
 * shape accepted by {@code TelegramMessageSinkFunction}, and dropped entries are
 * logged. ACKing in a {@code finally} block guarantees poison messages cannot
 * loop forever.
 */
public final class HarnessOutboundProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(HarnessOutboundProcessor.class);

    private final HarnessRedisStreamClient client;
    private final String stream;
    private final String group;
    private final HarnessEnvelopeParser parser;
    private final HarnessOutboundRouter router;
    private final ObjectMapper mapper;
    private final QuestionCallbackRegistry callbackRegistry;

    public HarnessOutboundProcessor(
            HarnessRedisStreamClient client,
            String stream,
            String group,
            HarnessEnvelopeParser parser,
            HarnessOutboundRouter router,
            ObjectMapper mapper) {
        this(client, stream, group, parser, router, mapper, null);
    }

    public HarnessOutboundProcessor(
            HarnessRedisStreamClient client,
            String stream,
            String group,
            HarnessEnvelopeParser parser,
            HarnessOutboundRouter router,
            ObjectMapper mapper,
            QuestionCallbackRegistry callbackRegistry) {
        this.client = client;
        this.stream = stream;
        this.group = group;
        this.parser = parser;
        this.router = router;
        this.mapper = mapper;
        this.callbackRegistry = callbackRegistry;
    }

    /**
     * Processes the entries and returns the JSON strings that must be forwarded to
     * the Telegram sink. Each input entry is acknowledged exactly once.
     */
    public List<String> process(List<HarnessStreamEntry> entries) {
        List<String> toForward = new ArrayList<>();
        if (entries == null) {
            return toForward;
        }
        for (HarnessStreamEntry entry : entries) {
            processOne(entry, toForward);
        }
        return toForward;
    }

    private void processOne(HarnessStreamEntry entry, List<String> toForward) {
        try {
            if (entry.body() == null || entry.body().isBlank()) {
                LOG.warn("Dropping empty outbound entry {}", entry.id());
                return;
            }
            HarnessParseResult result = parser.parse(entry.body());
            RoutingDecision decision = router.route(result);
            if (decision.shouldForward()) {
                registerCallbacks(decision);
                for (var outboundMessage : decision.outboundMessages()) {
                    toForward.add(mapper.writeValueAsString(outboundMessage));
                }
            } else {
                LOG.info("Dropping outbound entry {}: {}", entry.id(), decision.reason());
            }
        } catch (JsonProcessingException exception) {
            LOG.warn(
                    "Failed to serialize outbound message for entry {}; dropping: {}",
                    entry.id(),
                    exception.getMessage());
        } finally {
            ack(entry);
        }
    }

    private void registerCallbacks(RoutingDecision decision) {
        if (decision.callbackBindings().isEmpty()) return;
        if (callbackRegistry == null) {
            throw new IllegalStateException("Question callback registry is required for question_request");
        }
        for (var binding : decision.callbackBindings()) {
            // Free-text bindings must be registered by the Telegram sink after it receives
            // the prompt's real message id from the Bot API.
            if (!binding.freeText()) callbackRegistry.register(binding);
        }
    }

    private void ack(HarnessStreamEntry entry) {
        try {
            client.ack(stream, group, entry.id());
        } catch (RuntimeException exception) {
            LOG.warn("Failed to ACK outbound entry {}: {}", entry.id(), exception.getMessage());
        }
    }
}
